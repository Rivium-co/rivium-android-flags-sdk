package co.rivium.flags

/**
 * Configuration for the Rivium Flags client.
 *
 * @param apiKey public project key (`rv_live_…` / `rv_test_…`). Never put a server secret in an app.
 * @param environment environment key (`development`, `staging`, `production`, custom). `null` = the Default layer.
 * @param flagKeys only evaluate these flags; `null` = every flag of the project.
 * @param refreshIntervalSeconds foreground polling. `0` (default) = off; otherwise at least 60 s. Every evaluate
 *   request is a billed usage event, so keep this off unless you need it.
 * @param cacheEnabled persist the last results on the device and serve them at start / offline.
 * @param requestTimeoutMs network timeout per request.
 * @param debug verbose logs (never contain the API key).
 */
data class RiviumFlagsConfig @JvmOverloads constructor(
    val apiKey: String,
    val environment: String? = null,
    val flagKeys: List<String>? = null,
    val refreshIntervalSeconds: Long = 0,
    val cacheEnabled: Boolean = true,
    val requestTimeoutMs: Long = 10_000,
    val debug: Boolean = false,
    val baseUrl: String = "https://flags.rivium.co",
) {
    /** Effective polling interval in seconds: 0 (off) or ≥ 60. */
    internal val effectivePollIntervalSeconds: Long
        get() = if (refreshIntervalSeconds <= 0) 0 else maxOf(60, refreshIntervalSeconds)

    override fun toString(): String =
        "RiviumFlagsConfig(environment=$environment, flagKeys=$flagKeys, refreshIntervalSeconds=$refreshIntervalSeconds, " +
            "cacheEnabled=$cacheEnabled, debug=$debug, baseUrl=$baseUrl)" // never prints the key
}

/** Why a flag has its value. Unknown server reasons are kept as-is. */
data class FlagReason(val value: String) {
    override fun toString(): String = value

    companion object {
        @JvmField val ON = FlagReason("ON")
        @JvmField val VARIANT = FlagReason("VARIANT")
        @JvmField val DISABLED = FlagReason("DISABLED")
        @JvmField val PREREQUISITE_FAILED = FlagReason("PREREQUISITE_FAILED")
        @JvmField val NOT_TARGETED = FlagReason("NOT_TARGETED")
        @JvmField val OUTSIDE_ROLLOUT = FlagReason("OUTSIDE_ROLLOUT")
        @JvmField val NO_BUCKETING_ID = FlagReason("NO_BUCKETING_ID")
        @JvmField val ERROR = FlagReason("ERROR")
        /** SDK: key absent from the results; the code default is returned. */
        @JvmField val FLAG_NOT_FOUND = FlagReason("FLAG_NOT_FOUND")
        /** SDK: no cached or fetched results yet; the code default is returned. */
        @JvmField val NOT_READY = FlagReason("NOT_READY")
        /** SDK: a typed getter was called on a flag of another value type; the code default is returned. */
        @JvmField val TYPE_MISMATCH = FlagReason("TYPE_MISMATCH")
    }
}

/** Value type of a flag. */
enum class FlagValueType(val wire: String) {
    BOOLEAN("boolean"), STRING("string"), NUMBER("number"), JSON("json");

    companion object {
        @JvmStatic fun fromWire(s: String?): FlagValueType? = values().firstOrNull { it.wire == s }
    }
}

/**
 * One evaluated flag as returned by `POST /public/v2/evaluate`.
 *
 * `value` is a Kotlin view of the JSON value: `Boolean`, `String`, `Double` (all numbers), `List<Any?>`,
 * `Map<String, Any?>` or `null`.
 */
data class FlagResult(
    val key: String,
    /** Raw value type from the server (`boolean` / `string` / `number` / `json`). */
    val valueType: String,
    val enabled: Boolean,
    val value: Any?,
    val variant: String?,
    val reason: FlagReason,
    val version: Int,
) {
    val type: FlagValueType? get() = FlagValueType.fromWire(valueType)
}

/** The full answer of a getter: the value your code should use, plus why. */
data class FlagDetail<T>(
    val key: String,
    val value: T,
    val enabled: Boolean,
    val variant: String?,
    val reason: FlagReason,
    /** Flag version on the server; null for SDK reasons (NOT_READY, FLAG_NOT_FOUND). */
    val version: Int?,
)

/** An error reported to listeners. Getters never throw. */
data class RiviumFlagsError(
    val kind: Kind,
    val statusCode: Int?,
    /** Server error code (e.g. `environment_not_found`) when present. */
    val code: String?,
    val message: String,
) {
    enum class Kind {
        /** 400 / 413: the request body was refused; it is not retried. */
        INVALID_REQUEST,
        /** 401: invalid key, Flags not enabled, or no Flags project. Automatic fetches stop until `refresh()`. */
        UNAUTHORIZED,
        /** 403: refused (for example an IP allow-list). Not retried automatically. */
        FORBIDDEN,
        /** 404: the environment does not exist or is inactive. Not retried automatically. */
        ENVIRONMENT_NOT_FOUND,
        /** 429: rate limited; retried after `Retry-After` with back-off. */
        RATE_LIMITED,
        /** 5xx: retried with back-off. */
        SERVER,
        /** Network failure / timeout; retried with back-off. */
        NETWORK,
        /** The response could not be read. */
        INVALID_RESPONSE,
    }

    override fun toString(): String =
        "Rivium Flags ${kind.name.lowercase()}${statusCode?.let { " ($it)" } ?: ""}${code?.let { " $it" } ?: ""}: $message"
}

/** Listener for client events. Callbacks run on the main thread. All methods are optional. */
interface RiviumFlagsListener {
    /** Results are available (from the device cache or the network). Fires once per client. */
    fun onReady() {}

    /** Results changed after a fetch. [changedKeys] lists added, removed or changed flags. */
    fun onUpdate(changedKeys: Set<String>) {}

    fun onError(error: RiviumFlagsError) {}
}
