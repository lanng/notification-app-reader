package br.com.c6tag.diagnostico.monitor

import br.com.c6tag.diagnostico.diagnostic.DiagnosticEvent
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEventType
import br.com.c6tag.diagnostico.diagnostic.NotificationClassifier
import java.security.MessageDigest
import java.text.Normalizer

internal class MovementPolicy {
    private val classifier = NotificationClassifier()

    fun resolve(
        pending: PendingMovement,
        command: ResolvePending,
        currentReferenceId: Long?,
        balanceCents: Long,
    ): PendingResolution {
        if (pending.id != command.pendingId) return PendingResolution.Rejected(RejectionCode.INVALID_INPUT)
        if (pending.referenceId != currentReferenceId || command.expectedReferenceId != currentReferenceId) {
            return PendingResolution.Rejected(RejectionCode.STALE_REFERENCE)
        }
        if (pending.status != PendingStatus.OPEN) return PendingResolution.AlreadyResolved
        if (command.choice == PendingChoice.DO_NOT_COUNT) return PendingResolution.NotCounted
        if (pending.sourceAlreadyApplied) return PendingResolution.Rejected(RejectionCode.ALREADY_APPLIED)
        val kind = pending.value.kind
        val amount = pending.value.amountCents
        if (kind == null || amount == null || amount < 0L) {
            return PendingResolution.Rejected(RejectionCode.UNREADABLE_AMOUNT)
        }
        return when (val decision = apply(kind, amount, balanceCents)) {
            is MovementDecision.Apply -> PendingResolution.Applied(decision)
            else -> PendingResolution.Rejected(RejectionCode.AMOUNT_OVERFLOW)
        }
    }

    // This hash compares evidence; it does not anonymize the original notification.
    // The store must retain every observed hash, including enriched/conflicting variants.
    fun evidence(event: DiagnosticEvent): SourceEvidence? {
        if (event.groupSummary == true) return null
        val title = normalize(event.title.orEmpty())
        val kind = when {
            title.equals("Débito C6 Tag", ignoreCase = true) -> MovementKind.TAG_DEBIT
            title.equals("Você recebeu um Pix", ignoreCase = true) -> MovementKind.PIX_CREDIT
            else -> return null
        }
        val interpretation = classifier.classify(
            title, event.text?.let(::normalize), event.bigText?.let(::normalize),
            event.lines.map(::normalize), false,
        )
        val publication = if (
            !event.packageName.isNullOrBlank() && !event.key.isNullOrBlank() &&
            event.postedAtMs != null && event.postedAtMs > 0L
        ) {
            PublicationIdentity(event.packageName, event.key, event.postedAtMs)
        } else {
            null
        }
        val fields = listOfNotNull(event.text, event.bigText).plus(event.lines)
            .map(::normalize).filter(String::isNotEmpty).distinct()
        // Length prefixes preserve field boundaries without deleting distinguishing data.
        val canonical = (listOf(title) + fields).joinToString("") { "${it.length}:$it" }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return SourceEvidence(
            publication, SourceValue(kind, interpretation.amountCents), hash,
        )
    }

    fun decide(observation: FinancialObservation, context: MovementContext): MovementDecision {
        val event = observation.event
        return decideCandidate(
            FinancialCandidate(observation.id, event.type, event.observedAtMs, event.packageName, evidence(event)),
            context,
        )
    }

    // Durable evidence can be reevaluated after cancellation without retaining raw bank text.
    fun decideCandidate(candidate: FinancialCandidate, context: MovementContext): MovementDecision {
        if (
            context.expectedPackageName.isBlank() || candidate.packageName != context.expectedPackageName ||
            candidate.type != DiagnosticEventType.POSTED_OR_UPDATED
        ) return MovementDecision.Ignore
        val evidence = candidate.evidence ?: return MovementDecision.Ignore
        val reference = context.reference
        if (reference == null) {
            return if (context.referenceSessionActive) {
                MovementDecision.Pending(PendingReason.REFERENCE_IN_PROGRESS)
            } else {
                MovementDecision.Ignore
            }
        }
        val kind = evidence.value.kind ?: return MovementDecision.Ignore
        val amount = evidence.value.amountCents
            ?: return MovementDecision.Pending(PendingReason.INVALID_AMOUNT)
        val publication = evidence.publication
            ?: return MovementDecision.Pending(PendingReason.INSUFFICIENT_IDENTITY)

        context.sources.matchingPublication?.let { previous ->
            return if (previous == evidence.value) {
                MovementDecision.Ignore
            } else {
                MovementDecision.Pending(PendingReason.CONFLICTING_PUBLICATION)
            }
        }
        if (candidate.observationId <= 0L || candidate.observedAtMs < 0L) {
            return MovementDecision.Pending(PendingReason.UNCERTAIN_BOUNDARY)
        }
        when (ReferencePolicy().position(reference, ReferenceCandidate(candidate.observationId, candidate.observedAtMs))) {
            ReferencePosition.COVERED -> return MovementDecision.Ignore
            ReferencePosition.CONCURRENT -> return MovementDecision.Pending(PendingReason.CONCURRENT_REFERENCE)
            ReferencePosition.UNCERTAIN -> return MovementDecision.Pending(PendingReason.UNCERTAIN_BOUNDARY)
            ReferencePosition.AFTER -> Unit
        }
        if (context.referenceSessionActive) {
            return MovementDecision.Pending(PendingReason.REFERENCE_IN_PROGRESS)
        }
        if (
            candidate.observationId <= reference.observationCursor || candidate.observedAtMs <= reference.confirmedAtMs ||
            publication.postedAtMs <= reference.confirmedAtMs || publication.postedAtMs > candidate.observedAtMs
        ) return MovementDecision.Pending(PendingReason.UNCERTAIN_BOUNDARY)
        if (context.sources.sameKeySeen) return MovementDecision.Pending(PendingReason.REUSED_KEY)
        if (context.sources.identicalContentSeen) return MovementDecision.Pending(PendingReason.IDENTICAL_CONTENT)
        return apply(kind, amount, context.balanceCents)
    }

    private fun apply(kind: MovementKind, amount: Long, balance: Long): MovementDecision = try {
        MovementDecision.Apply(
            kind, amount,
            when (kind) {
                MovementKind.TAG_DEBIT -> Math.subtractExact(balance, amount)
                MovementKind.PIX_CREDIT -> Math.addExact(balance, amount)
            },
        )
    } catch (_: ArithmeticException) {
        MovementDecision.Pending(PendingReason.AMOUNT_OVERFLOW)
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFC)
            .replace(Regex("[\\p{javaWhitespace}\\p{Z}]+"), " ").trim()
}
