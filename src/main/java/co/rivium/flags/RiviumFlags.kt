package co.rivium.flags

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * RiviumFlags - Client-side Feature Flags SDK for Android
 *
 * ```kotlin
 * val flags = RiviumFlags(
 *     context = applicationContext,
 *     config = RiviumFlagsConfig(apiKey = "rv_live_xxx")
 * )
 * flags.init()
 *
 * flags.setUserId("user-123")
 *
 * if (flags.isEnabled("dark-mode")) {
 *     // dark mode enabled
 * }
 * ```
 */
class RiviumFlags(
    private val context: Context,
    private val config: RiviumFlagsConfig,
) {
    companion object {
        private const val PREFS_NAME = "rivium_flags"
        private const val KEY_CACHED_FLAGS = "cached_flags"
        private const val KEY_USER_ID = "user_id"
        private const val BASE_URL = "https://flags.rivium.co"

        @Volatile
        private var instance: RiviumFlags? = null

        fun getInstance(): RiviumFlags {
            return instance ?: throw IllegalStateException("RiviumFlags not initialized. Call init() first.")
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var flags: List<FeatureFlag> = emptyList()
    private var userId: String? = null
    private var userAttributes: MutableMap<String, Any?> = mutableMapOf()
    private var isInitialized = false
    private var callback: ((String, Map<String, Any?>?) -> Unit)? = null

    /**
     * Initialize the SDK. Loads cached flags and fetches fresh ones from the server.
     */
    suspend fun init(callback: ((String, Map<String, Any?>?) -> Unit)? = null) {
        this.callback = callback

        if (config.debug) {
            android.util.Log.d("RiviumFlags", "[init] Starting SDK init...")
            android.util.Log.d("RiviumFlags", "[init] API key: ${config.apiKey.take(20)}...")
            android.util.Log.d("RiviumFlags", "[init] Environment: ${config.environment ?: "none"}")
            android.util.Log.d("RiviumFlags", "[init] Offline cache: ${config.enableOfflineCache}")
        }

        // Load cached data
        if (config.enableOfflineCache) {
            loadCachedFlags()
            userId = prefs.getString(KEY_USER_ID, null)
            if (config.debug) {
                android.util.Log.d("RiviumFlags", "[init] Loaded ${flags.size} cached flags, userId=$userId")
            }
        }

        isInitialized = true
        instance = this

        // Always attempt to fetch — if offline, the request fails gracefully
        // and we keep using cached flags
        if (config.debug) {
            android.util.Log.d("RiviumFlags", "[init] Calling fetchFlags()...")
        }
        fetchFlags()

        if (config.debug) {
            android.util.Log.d("RiviumFlags", "[init] Done. Total flags: ${flags.size}")
        }
        callback?.invoke("initialized", mapOf("offline" to !isOnline(), "count" to flags.size))
    }

    fun setUserId(userId: String) {
        this.userId = userId
        if (config.enableOfflineCache) {
            prefs.edit().putString(KEY_USER_ID, userId).apply()
        }
    }

    fun getUserId(): String? = userId

    fun setUserAttributes(attributes: Map<String, Any?>) {
        userAttributes.putAll(attributes)
    }

    /**
     * Check if a feature flag is enabled
     */
    fun isEnabled(flagKey: String, defaultValue: Boolean = false): Boolean {
        val flag = flags.find { it.key == flagKey } ?: return defaultValue
        return evaluateFlag(flag).enabled
    }

    /**
     * Get the value of a feature flag
     */
    fun getValue(flagKey: String, defaultValue: Any? = null): Any? {
        val flag = flags.find { it.key == flagKey } ?: return defaultValue
        val result = evaluateFlag(flag)
        return result.value ?: defaultValue
    }

    /**
     * Evaluate a flag and get the full result
     */
    fun evaluate(flagKey: String): FlagEvalResult {
        val flag = flags.find { it.key == flagKey }
            ?: return FlagEvalResult(enabled = false, value = false)
        return evaluateFlag(flag)
    }

    /**
     * Get all feature flags
     */
    fun getAll(): List<FeatureFlag> = flags.toList()

    /**
     * Refresh flags from the server
     */
    suspend fun refresh() {
        fetchFlags()
        callback?.invoke("featureFlagsRefreshed", mapOf("count" to flags.size))
    }

    /**
     * Reset all state and cached data
     */
    fun reset() {
        flags = emptyList()
        userId = null
        userAttributes.clear()
        isInitialized = false
        instance = null
        prefs.edit().clear().apply()
    }

    /**
     * Dispose the SDK instance
     */
    fun dispose() {
        flags = emptyList()
        userId = null
        userAttributes.clear()
        isInitialized = false
        instance = null
    }

    // ============================================
    // PRIVATE METHODS
    // ============================================

    private val flagsUrl: String
        get() {
            val base = "${config.baseUrl ?: BASE_URL}/public/flags"
            return if (config.environment != null) "$base?environment=${config.environment}" else base
        }

    @Suppress("DEPRECATION")
    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return true // assume online if unknown
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            // API 21-22: use deprecated activeNetworkInfo
            cm.activeNetworkInfo?.isConnected ?: true
        }
    }

    private suspend fun fetchFlags() = withContext(Dispatchers.IO) {
        try {
            if (config.debug) {
                android.util.Log.d("RiviumFlags", "[fetchFlags] URL: $flagsUrl")
                android.util.Log.d("RiviumFlags", "[fetchFlags] API key: ${config.apiKey.take(20)}...")
                android.util.Log.d("RiviumFlags", "[fetchFlags] isOnline: ${isOnline()}")
            }

            val request = Request.Builder()
                .url(flagsUrl)
                .addHeader("x-api-key", config.apiKey)
                .build()

            if (config.debug) {
                android.util.Log.d("RiviumFlags", "[fetchFlags] Sending HTTP request...")
            }

            val response = client.newCall(request).execute()

            if (config.debug) {
                android.util.Log.d("RiviumFlags", "[fetchFlags] Response code: ${response.code}")
                android.util.Log.d("RiviumFlags", "[fetchFlags] Response message: ${response.message}")
            }

            if (response.isSuccessful) {
                val body = response.body?.string() ?: run {
                    if (config.debug) android.util.Log.w("RiviumFlags", "[fetchFlags] Response body is null")
                    return@withContext
                }

                if (config.debug) {
                    android.util.Log.d("RiviumFlags", "[fetchFlags] Body length: ${body.length}")
                    android.util.Log.d("RiviumFlags", "[fetchFlags] Body preview: ${body.take(500)}")
                }

                val jsonObj = json.parseToJsonElement(body).jsonObject
                val flagsArray = jsonObj["flags"]?.jsonArray ?: run {
                    if (config.debug) android.util.Log.w("RiviumFlags", "[fetchFlags] No 'flags' array in response. Keys: ${jsonObj.keys}")
                    return@withContext
                }

                if (config.debug) {
                    android.util.Log.d("RiviumFlags", "[fetchFlags] Parsed ${flagsArray.size} flags from JSON")
                }

                flags = flagsArray.map { parseFlag(it.jsonObject) }

                if (config.enableOfflineCache) {
                    prefs.edit().putString(KEY_CACHED_FLAGS, body).apply()
                }

                if (config.debug) {
                    android.util.Log.d("RiviumFlags", "[fetchFlags] SUCCESS: ${flags.size} flags loaded")
                }
            } else {
                if (config.debug) {
                    val errorBody = response.body?.string()
                    android.util.Log.e("RiviumFlags", "[fetchFlags] HTTP error ${response.code}: $errorBody")
                }
            }
        } catch (e: Exception) {
            if (config.debug) {
                android.util.Log.e("RiviumFlags", "[fetchFlags] EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
            }
            callback?.invoke("error", mapOf("message" to "Failed to fetch flags: ${e.message}"))
        }
    }

    private fun loadCachedFlags() {
        try {
            val cached = prefs.getString(KEY_CACHED_FLAGS, null) ?: return
            val jsonObj = json.parseToJsonElement(cached).jsonObject
            val flagsArray = jsonObj["flags"]?.jsonArray ?: return
            flags = flagsArray.map { parseFlag(it.jsonObject) }
        } catch (_: Exception) {}
    }

    private fun parseFlag(obj: JsonObject): FeatureFlag {
        val targeting = obj["targetingRules"]
        val variants = obj["variants"]

        return FeatureFlag(
            key = obj["key"]?.jsonPrimitive?.content ?: "",
            enabled = obj["enabled"]?.jsonPrimitive?.boolean ?: false,
            rolloutPercentage = obj["rolloutPercentage"]?.jsonPrimitive?.int ?: 100,
            targetingRules = if (targeting is JsonObject) parseJsonToMap(targeting) else null,
            variants = if (variants is JsonArray) variants.map { v ->
                val vObj = v.jsonObject
                FlagVariant(
                    key = vObj["key"]?.jsonPrimitive?.content ?: "",
                    value = vObj["value"]?.let { if (it is JsonNull) null else it.toPrimitive() },
                    weight = vObj["weight"]?.jsonPrimitive?.int ?: 0,
                )
            } else null,
            defaultValue = obj["defaultValue"]?.let { if (it is JsonNull) null else it.toPrimitive() },
        )
    }

    private fun JsonElement.toPrimitive(): Any? = when (this) {
        is JsonPrimitive -> when {
            isString -> content
            content == "true" || content == "false" -> boolean
            content.contains('.') -> double
            else -> longOrNull ?: content
        }
        is JsonNull -> null
        else -> toString()
    }

    private fun parseJsonToMap(obj: JsonObject): Map<String, Any?> {
        return obj.entries.associate { (k, v) ->
            k to parseJsonElement(v)
        }
    }

    private fun parseJsonElement(element: JsonElement): Any? = when (element) {
        is JsonPrimitive -> element.toPrimitive()
        is JsonObject -> parseJsonToMap(element)
        is JsonArray -> element.map { parseJsonElement(it) }
        is JsonNull -> null
    }

    private fun evaluateFlag(flag: FeatureFlag): FlagEvalResult {
        if (!flag.enabled) {
            return FlagEvalResult(enabled = false, value = flag.defaultValue ?: false)
        }

        if (flag.targetingRules != null && flag.targetingRules.isNotEmpty()) {
            if (!evaluateTargetingRules(flag.targetingRules, userAttributes)) {
                return FlagEvalResult(enabled = false, value = flag.defaultValue ?: false)
            }
        }

        if (userId != null) {
            val bucket = getBucket(userId!!, flag.key)
            if (bucket >= flag.rolloutPercentage) {
                return FlagEvalResult(enabled = false, value = flag.defaultValue ?: false)
            }
        }

        if (flag.variants != null && flag.variants.isNotEmpty()) {
            val variantBucket = getVariantBucket(userId ?: "", flag.key)
            var cumulative = 0
            for (variant in flag.variants) {
                cumulative += variant.weight
                if (variantBucket < cumulative) {
                    return FlagEvalResult(enabled = true, value = variant.value, variant = variant.key)
                }
            }
            return FlagEvalResult(enabled = true, value = flag.variants.first().value, variant = flag.variants.first().key)
        }

        return FlagEvalResult(enabled = true, value = true)
    }

    private fun evaluateTargetingRules(rules: Map<String, Any?>, userContext: Map<String, Any?>): Boolean {
        // Handle nested { operator, rules } format from dashboard
        val ruleList = rules["rules"]
        if (ruleList is List<*>) {
            val op = (rules["operator"] as? String ?: "AND").uppercase()
            val typedRules = ruleList.filterIsInstance<Map<String, Any?>>()
            return if (op == "OR") {
                typedRules.any { evaluateNestedRule(it, userContext) }
            } else {
                typedRules.all { evaluateNestedRule(it, userContext) }
            }
        }
        // Legacy flat format
        for ((key, rule) in rules) {
            if (!evaluateRule(key, rule, userContext)) return false
        }
        return true
    }

    private fun evaluateNestedRule(rule: Map<String, Any?>, userContext: Map<String, Any?>): Boolean {
        val attribute = rule["attribute"] as? String ?: return true
        val op = rule["operator"] as? String ?: return true
        val ruleValue = rule["value"]
        val userValue = userContext[attribute]

        return when (op) {
            "equals" -> userValue == ruleValue
            "not_equals", "notEquals" -> userValue != ruleValue
            "in" -> {
                val list = when (ruleValue) {
                    is String -> ruleValue.split(",").map { it.trim() }
                    is List<*> -> ruleValue
                    else -> emptyList<Any>()
                }
                list.contains(userValue)
            }
            "not_in", "notIn" -> {
                val list = when (ruleValue) {
                    is String -> ruleValue.split(",").map { it.trim() }
                    is List<*> -> ruleValue
                    else -> emptyList<Any>()
                }
                !list.contains(userValue)
            }
            "greater_than", "greaterThan" -> userValue is Number && ruleValue is Number && userValue.toDouble() > ruleValue.toDouble()
            "less_than", "lessThan" -> userValue is Number && ruleValue is Number && userValue.toDouble() < ruleValue.toDouble()
            "contains" -> userValue is String && userValue.contains(ruleValue?.toString() ?: "")
            "regex" -> userValue is String && Regex(ruleValue?.toString() ?: "").containsMatchIn(userValue)
            "exists" -> if (ruleValue == true) userValue != null else userValue == null
            else -> userValue == ruleValue
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun evaluateRule(key: String, rule: Any?, context: Map<String, Any?>): Boolean {
        val value = context[key]

        if (rule is Map<*, *>) {
            val ruleMap = rule as Map<String, Any?>
            if ("equals" in ruleMap) return value == ruleMap["equals"]
            if ("notEquals" in ruleMap) return value != ruleMap["notEquals"]
            if ("in" in ruleMap && ruleMap["in"] is List<*>) return (ruleMap["in"] as List<*>).contains(value)
            if ("notIn" in ruleMap && ruleMap["notIn"] is List<*>) return !(ruleMap["notIn"] as List<*>).contains(value)
            if ("greaterThan" in ruleMap) return value is Number && (ruleMap["greaterThan"] as? Number)?.let { value.toDouble() > it.toDouble() } ?: false
            if ("lessThan" in ruleMap) return value is Number && (ruleMap["lessThan"] as? Number)?.let { value.toDouble() < it.toDouble() } ?: false
            if ("greaterThanOrEqual" in ruleMap) return value is Number && (ruleMap["greaterThanOrEqual"] as? Number)?.let { value.toDouble() >= it.toDouble() } ?: false
            if ("lessThanOrEqual" in ruleMap) return value is Number && (ruleMap["lessThanOrEqual"] as? Number)?.let { value.toDouble() <= it.toDouble() } ?: false
            if ("contains" in ruleMap) return value is String && value.contains(ruleMap["contains"] as? String ?: "")
            if ("regex" in ruleMap) return value is String && Regex(ruleMap["regex"] as? String ?: "").containsMatchIn(value)
            if ("exists" in ruleMap) return if (ruleMap["exists"] == true) value != null else value == null
            if ("and" in ruleMap && ruleMap["and"] is List<*>) return (ruleMap["and"] as List<*>).all { evaluateRule(key, it, context) }
            if ("or" in ruleMap && ruleMap["or"] is List<*>) return (ruleMap["or"] as List<*>).any { evaluateRule(key, it, context) }
        }

        return value == rule
    }

    private fun getBucket(userId: String, salt: String): Int {
        val input = "$userId:$salt"
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        val hex = digest.take(4).joinToString("") { "%02x".format(it) }
        return (hex.toLong(16) % 100).toInt()
    }

    private fun getVariantBucket(userId: String, flagKey: String): Int {
        return getBucket(userId, "$flagKey:variant")
    }
}

data class RiviumFlagsConfig(
    val apiKey: String,
    val environment: String? = null,
    val baseUrl: String? = null,
    val debug: Boolean = false,
    val enableOfflineCache: Boolean = true,
)

data class FeatureFlag(
    val key: String,
    val enabled: Boolean,
    val rolloutPercentage: Int = 100,
    val targetingRules: Map<String, Any?>? = null,
    val variants: List<FlagVariant>? = null,
    val defaultValue: Any? = null,
)

data class FlagVariant(
    val key: String,
    val value: Any? = null,
    val weight: Int = 0,
)

data class FlagEvalResult(
    val enabled: Boolean,
    val value: Any? = null,
    val variant: String? = null,
)
