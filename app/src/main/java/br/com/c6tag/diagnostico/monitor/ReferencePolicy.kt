package br.com.c6tag.diagnostico.monitor

internal class ReferencePolicy {
    fun confirm(
        session: ReferenceSession,
        command: ConfirmReference,
        currentReferenceId: Long?,
        newReferenceId: Long,
        currentCursor: Long,
        candidates: List<ReferenceCandidate>,
    ): ReferenceDecision {
        validate(session, command.sessionId, command.nowMs, currentReferenceId)?.let {
            return ReferenceDecision.Rejected(it)
        }
        if (command.expectedReferenceId != currentReferenceId) {
            return ReferenceDecision.Rejected(RejectionCode.STALE_REFERENCE)
        }
        if (
            command.limitCents < 0L || newReferenceId <= (currentReferenceId ?: 0L) ||
            currentCursor < session.observationCursor
        ) return ReferenceDecision.Rejected(RejectionCode.INVALID_INPUT)
        val ids = candidates.map { it.observationId }.toSet()
        if (
            ids.size != candidates.size || candidates.any {
                it.observationId !in 1L..currentCursor || it.observedAtMs !in 0L..command.nowMs
            }
        ) return ReferenceDecision.Rejected(RejectionCode.INVALID_INPUT)
        if (!ids.containsAll(command.explicitlyIncludedObservationIds)) {
            return ReferenceDecision.Rejected(RejectionCode.UNKNOWN_OBSERVATION)
        }
        val reference = BalanceReference(
            newReferenceId, command.balanceCents, command.nowMs, currentCursor,
            session.openedAtMs, session.observationCursor,
            command.explicitlyIncludedObservationIds.toSet(), session.id,
        )
        val covered = candidates.filter {
            position(reference, it) == ReferencePosition.COVERED
        }.map { it.observationId }.toSet()
        return ReferenceDecision.Confirmed(
            reference, command.limitCents,
            session.copy(status = ReferenceSessionStatus.CONFIRMED), covered, ids - covered,
        )
    }

    fun position(reference: BalanceReference, candidate: ReferenceCandidate): ReferencePosition {
        if (candidate.observationId in reference.explicitlyIncludedObservationIds) {
            return ReferencePosition.COVERED
        }
        if (
            candidate.observationId <= reference.cursorAtSessionOpen &&
            candidate.observedAtMs < reference.sessionOpenedAtMs
        ) return ReferencePosition.COVERED
        if (
            reference.sessionId != null &&
            candidate.observedAtMs in reference.sessionOpenedAtMs..reference.confirmedAtMs
        ) {
            return ReferencePosition.CONCURRENT
        }
        if (candidate.observationId <= reference.cursorAtSessionOpen) return ReferencePosition.UNCERTAIN
        if (candidate.observationId <= reference.observationCursor) return ReferencePosition.CONCURRENT
        if (candidate.observedAtMs <= reference.confirmedAtMs) return ReferencePosition.UNCERTAIN
        return ReferencePosition.AFTER
    }

    fun cancel(
        session: ReferenceSession,
        sessionId: String,
        nowMs: Long,
        currentReferenceId: Long?,
        continuityPreserved: Boolean,
        candidates: List<ReferenceCandidate>,
    ): ReferenceDecision {
        validate(session, sessionId, nowMs, currentReferenceId)?.let {
            return ReferenceDecision.Rejected(it)
        }
        val ids = candidates.map { it.observationId }.toSet()
        val canReevaluate = currentReferenceId != null && continuityPreserved
        return ReferenceDecision.Cancelled(
            session.copy(status = ReferenceSessionStatus.CANCELLED),
            if (canReevaluate) ids else emptySet(),
            if (canReevaluate) emptySet() else ids,
        )
    }

    fun expire(
        session: ReferenceSession,
        nowMs: Long,
        candidates: List<ReferenceCandidate>,
    ): ReferenceDecision {
        if (session.status != ReferenceSessionStatus.ACTIVE) return ReferenceDecision.Unchanged
        if (session.openedAtMs < 0L || nowMs < session.openedAtMs) {
            return ReferenceDecision.Rejected(RejectionCode.INVALID_TIME)
        }
        if (nowMs - session.openedAtMs < SESSION_DURATION_MS) return ReferenceDecision.Unchanged
        return ReferenceDecision.Expired(
            session.copy(status = ReferenceSessionStatus.EXPIRED),
            candidates.map { it.observationId }.toSet(),
        )
    }

    private fun validate(
        session: ReferenceSession,
        sessionId: String,
        nowMs: Long,
        currentReferenceId: Long?,
    ): RejectionCode? = when {
        session.status != ReferenceSessionStatus.ACTIVE -> RejectionCode.SESSION_NOT_ACTIVE
        session.id.isBlank() || session.observationCursor < 0L -> RejectionCode.INVALID_INPUT
        session.id != sessionId -> RejectionCode.SESSION_MISMATCH
        session.openedAtMs < 0L || nowMs < session.openedAtMs -> RejectionCode.INVALID_TIME
        nowMs - session.openedAtMs >= SESSION_DURATION_MS -> RejectionCode.SESSION_EXPIRED
        session.baseReferenceId != currentReferenceId -> RejectionCode.STALE_REFERENCE
        else -> null
    }

    private companion object {
        const val SESSION_DURATION_MS = 15L * 60L * 1_000L
    }
}
