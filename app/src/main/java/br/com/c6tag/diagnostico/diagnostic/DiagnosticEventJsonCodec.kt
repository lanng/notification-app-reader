package br.com.c6tag.diagnostico.diagnostic

import org.json.JSONArray
import org.json.JSONObject

/** Versioned internal payload encoding. This is not an export-document format. */
internal object DiagnosticEventJsonCodec {
    fun encode(event: DiagnosticEvent): String =
        JSONObject()
            .put("codecVersion", VERSION)
            .put("type", event.type.name)
            .put("observedAtMs", event.observedAtMs)
            .putNullable("postedAtMs", event.postedAtMs)
            .putNullable("packageName", event.packageName)
            .putNullable("key", event.key)
            .putNullable("notificationId", event.notificationId)
            .putNullable("tag", event.tag)
            .putNullable("groupKey", event.groupKey)
            .putNullable("groupSummary", event.groupSummary)
            .putNullable("title", event.title)
            .putNullable("text", event.text)
            .putNullable("bigText", event.bigText)
            .put("lines", JSONArray().also { array ->
                event.lines.forEach(array::put)
            })
            .putNullable("removalReason", event.removalReason)
            .put("interpretation", event.interpretation?.let(::encodeInterpretation) ?: JSONObject.NULL)
            .toString()

    fun decode(json: String): DiagnosticEvent {
        val root = JSONObject(json)
        requireVersion(root)
        requireFields(root, EVENT_FIELDS)

        val linesJson = root.getJSONArray("lines")
        val lines = buildList {
            repeat(linesJson.length()) { index ->
                add(linesJson.getString(index))
            }
        }
        val interpretation = if (root.isNull("interpretation")) {
            null
        } else {
            decodeInterpretation(root.getJSONObject("interpretation"))
        }

        return DiagnosticEvent(
            type = DiagnosticEventType.valueOf(root.getString("type")),
            observedAtMs = root.getLong("observedAtMs"),
            postedAtMs = root.nullableLong("postedAtMs"),
            packageName = root.nullableString("packageName"),
            key = root.nullableString("key"),
            notificationId = root.nullableInt("notificationId"),
            tag = root.nullableString("tag"),
            groupKey = root.nullableString("groupKey"),
            groupSummary = root.nullableBoolean("groupSummary"),
            title = root.nullableString("title"),
            text = root.nullableString("text"),
            bigText = root.nullableString("bigText"),
            lines = lines,
            removalReason = root.nullableInt("removalReason"),
            interpretation = interpretation,
        )
    }

    private fun encodeInterpretation(value: NotificationInterpretation): JSONObject =
        JSONObject()
            .put("kind", value.kind.name)
            .putNullable("amountCents", value.amountCents)
            .putNullable("reason", value.reason)

    private fun decodeInterpretation(json: JSONObject): NotificationInterpretation {
        requireFields(json, INTERPRETATION_FIELDS)
        return NotificationInterpretation(
            kind = CandidateKind.valueOf(json.getString("kind")),
            amountCents = json.nullableLong("amountCents"),
            reason = json.nullableString("reason"),
        )
    }

    private fun requireVersion(json: JSONObject) {
        if (!json.has("codecVersion") || json.getInt("codecVersion") != VERSION) {
            throw IllegalStateException("Unsupported diagnostic payload version")
        }
    }

    private fun requireFields(json: JSONObject, fields: List<String>) {
        if (fields.any { !json.has(it) }) {
            throw IllegalStateException("Incomplete diagnostic payload")
        }
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else getString(name)

    private fun JSONObject.nullableLong(name: String): Long? =
        if (isNull(name)) null else getLong(name)

    private fun JSONObject.nullableInt(name: String): Int? =
        if (isNull(name)) null else getInt(name)

    private fun JSONObject.nullableBoolean(name: String): Boolean? =
        if (isNull(name)) null else getBoolean(name)

    private const val VERSION = 1

    private val EVENT_FIELDS = listOf(
        "codecVersion",
        "type",
        "observedAtMs",
        "postedAtMs",
        "packageName",
        "key",
        "notificationId",
        "tag",
        "groupKey",
        "groupSummary",
        "title",
        "text",
        "bigText",
        "lines",
        "removalReason",
        "interpretation",
    )

    private val INTERPRETATION_FIELDS = listOf(
        "kind",
        "amountCents",
        "reason",
    )
}
