package co.rivium.flags

import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal object SdkInfo {
    const val VERSION = "0.2.0"
    const val HEADER = "android/$VERSION"
}

// ---------------------------------------------------------------- logging

internal class FlagsLog(private val debugEnabled: Boolean) {
    fun debug(message: () -> String) {
        if (debugEnabled) safe { Log.d(TAG, "$PREFIX ${message()}") }
    }

    fun warn(message: String) = safe { Log.w(TAG, "$PREFIX $message") }
    fun error(message: String) = safe { Log.e(TAG, "$PREFIX $message") }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: Throwable) { /* plain JVM (unit tests) */ }
    }

    companion object {
        const val TAG = "RiviumFlags"
        const val PREFIX = "[Rivium Flags]"
    }
}

// ---------------------------------------------------------------- storage

/** Key-value storage; SharedPreferences on devices, in memory in tests. */
internal interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

internal class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = try { prefs.getString(key, null) } catch (_: ClassCastException) { null }
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}

internal object StorageKeys {
    /** Do not change: installed apps depend on this key. */
    const val PREFS_NAME = "rivium_flags"
    const val ANONYMOUS_ID = "anonymous_id"
    const val CACHE = "rivium_flags_cache_v2"
    const val CONTEXT = "rivium_flags_context_v2"
    /** 0.1.x keys, removed on first start of 0.2.0. */
    val LEGACY = listOf("cached_flags", "user_id")
}

internal fun keyFingerprint(apiKey: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(apiKey.toByteArray(Charsets.UTF_8))
    return digest.take(8).joinToString("") { "%02x".format(it) }
}

internal fun newAnonymousId(): String = UUID.randomUUID().toString().lowercase(Locale.ROOT)

internal fun isUsableId(s: String?): Boolean = s != null && s.isNotEmpty() && s.length <= 256

// ---------------------------------------------------------------- transport

internal class HttpRequest(val url: String, val headers: Map<String, String>, val body: ByteArray)

/** Header names are lower-cased. */
internal class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
}

internal fun interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}

/** OkHttp without an HTTP cache, so ETag / 304 handling stays in the SDK. Cancels the call on coroutine cancel. */
internal class OkHttpTransport(timeoutMs: Long) : HttpTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs * 2, TimeUnit.MILLISECONDS)
        .build()

    private val jsonType = "application/json".toMediaType()

    override suspend fun send(request: HttpRequest): HttpResponse = suspendCancellableCoroutine { cont ->
        val builder = Request.Builder().url(request.url).post(request.body.toRequestBody(jsonType))
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        val call = client.newCall(builder.build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val result = try {
                        HttpResponse(
                            r.code,
                            r.headers.names().associate { it.lowercase(Locale.ROOT) to (r.header(it) ?: "") },
                            r.body?.bytes() ?: ByteArray(0),
                        )
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                        return
                    }
                    if (cont.isActive) cont.resume(result)
                }
            }
        })
    }
}

// ---------------------------------------------------------------- JSON <-> Kotlin

internal object JsonConv {
    /** Kotlin value → JSON. Null when unsupported (custom objects, NaN, infinity, non-string map keys). */
    fun toJson(v: Any?): JsonElement? = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Boolean -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is Int, is Long, is Short, is Byte -> JsonPrimitive((v as Number).toLong())
        is Number -> {
            val d = v.toDouble()
            when {
                !d.isFinite() -> null
                d == Math.rint(d) && abs(d) < 9.0e15 -> JsonPrimitive(d.toLong())
                else -> JsonPrimitive(d)
            }
        }
        is Map<*, *> -> {
            val out = LinkedHashMap<String, JsonElement>()
            var ok = true
            for ((k, value) in v) {
                val j = toJson(value)
                if (k !is String || j == null) { ok = false; break }
                out[k] = j
            }
            if (ok) JsonObject(out) else null
        }
        is Iterable<*> -> toJsonArray(v.toList())
        is Array<*> -> toJsonArray(v.toList())
        else -> null
    }

    private fun toJsonArray(items: List<Any?>): JsonElement? {
        val out = ArrayList<JsonElement>(items.size)
        for (item in items) out.add(toJson(item) ?: return null)
        return JsonArray(out)
    }

    /** JSON → Kotlin: Boolean, String, Double, List, Map, null. */
    fun fromJson(e: JsonElement?): Any? = when (e) {
        null, JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            else -> e.doubleOrNull
        }
        is JsonArray -> e.map { fromJson(it) }
        is JsonObject -> e.mapValues { fromJson(it.value) }
    }
}

// ---------------------------------------------------------------- attributes

internal object AttributeLimits {
    const val MAX_KEYS = 100
    const val MAX_KEY_LENGTH = 100
    const val MAX_STRING_LENGTH = 1024
    const val MAX_ARRAY_LENGTH = 100
}

/**
 * Keeps only what the server accepts and normalises values (numbers → Double), dropping the rest
 * with a warning instead of getting a 400.
 */
internal fun sanitizeAttributes(input: Map<String, Any?>, log: FlagsLog): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    for (key in input.keys.sorted()) {
        if (key == "userId" || key == "anonymousId") {
            log.warn("attribute \"$key\" is ignored; use identify() / the anonymous id instead")
            continue
        }
        if (key.isEmpty() || key.length > AttributeLimits.MAX_KEY_LENGTH) {
            log.warn("attribute names are 1-${AttributeLimits.MAX_KEY_LENGTH} characters; dropped one")
            continue
        }
        if (out.size >= AttributeLimits.MAX_KEYS) {
            log.warn("at most ${AttributeLimits.MAX_KEYS} attributes; extra ones dropped")
            break
        }
        val json = JsonConv.toJson(input[key])
        if (json == null || !isValidAttribute(json)) {
            log.warn("attribute \"$key\" must be a string, number, boolean, null or an array of those; dropped")
            continue
        }
        out[key] = JsonConv.fromJson(json)
    }
    return out
}

private fun isScalar(e: JsonElement): Boolean = when (e) {
    JsonNull -> true
    is JsonPrimitive -> !e.isString || e.content.length <= AttributeLimits.MAX_STRING_LENGTH
    else -> false
}

private fun isValidAttribute(e: JsonElement): Boolean =
    isScalar(e) || (e is JsonArray && e.size <= AttributeLimits.MAX_ARRAY_LENGTH && e.all { isScalar(it) })

// ---------------------------------------------------------------- back-off

internal object Backoff {
    const val DEFAULT_RETRY_AFTER_S = 5.0
    const val BASE_S = 5.0
    const val MAX_S = 300.0

    /**
     * Delay in seconds before retry number [attempt] (0-based). [retryAfterS] is the 429 header (or null).
     * [random] in 0..1 drives the ±20 % jitter. A server-given Retry-After is never shortened.
     */
    fun delaySeconds(attempt: Int, retryAfterS: Double?, random: Double): Double {
        val start = retryAfterS ?: BASE_S
        val exp = start * 2.0.pow(attempt.coerceIn(0, 20).toDouble())
        val capped = min(exp, MAX_S)
        val jittered = capped * (0.8 + 0.4 * random.coerceIn(0.0, 1.0))
        if (retryAfterS != null && attempt == 0) return max(retryAfterS, jittered)
        return min(jittered, MAX_S)
    }

    /** Retry-After in seconds or as an HTTP date. Missing / invalid → 5 s. */
    fun parseRetryAfter(header: String?, nowMs: Long = System.currentTimeMillis()): Double {
        val h = header?.trim()
        if (h.isNullOrEmpty()) return DEFAULT_RETRY_AFTER_S
        h.toDoubleOrNull()?.let { if (it.isFinite() && it >= 0) return it }
        return try {
            val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            f.timeZone = TimeZone.getTimeZone("GMT")
            val at = f.parse(h)?.time ?: return DEFAULT_RETRY_AFTER_S
            max(0.0, (at - nowMs) / 1000.0)
        } catch (_: Exception) {
            DEFAULT_RETRY_AFTER_S
        }
    }
}
