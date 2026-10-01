package co.rivium.flags

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

internal class MemoryStore : KeyValueStore {
    val map = ConcurrentHashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

internal class Reply(
    val status: Int = 200,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val delayMs: Long = 0,
    val networkError: Boolean = false,
)

internal class MockTransport(@Volatile var handler: (HttpRequest) -> Reply = { Reply(body = """{"flags":{}}""") }) : HttpTransport {
    val requests: MutableList<HttpRequest> = Collections.synchronizedList(mutableListOf())

    override suspend fun send(request: HttpRequest): HttpResponse {
        requests.add(request)
        val r = handler(request)
        if (r.delayMs > 0) delay(r.delayMs)
        if (r.networkError) throw IOException("offline")
        return HttpResponse(r.status, r.headers.mapKeys { it.key.lowercase() }, r.body.toByteArray())
    }

    fun request(i: Int): HttpRequest = synchronized(requests) { requests[i] }
    val count: Int get() = requests.size
}

internal fun HttpRequest.bodyJson(): JsonObject = Json.parseToJsonElement(body.toString(Charsets.UTF_8)) as JsonObject

internal fun HttpRequest.header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value

internal fun flagJson(
    key: String, type: String, enabled: Boolean, value: JsonElement, variant: String? = null, reason: String, version: Int = 1,
): JsonObject = JsonObject(
    mapOf(
        "key" to JsonPrimitive(key), "valueType" to JsonPrimitive(type), "enabled" to JsonPrimitive(enabled), "value" to value,
        "variant" to (variant?.let { JsonPrimitive(it) } ?: JsonNull), "reason" to JsonPrimitive(reason),
        "version" to JsonPrimitive(version),
    ),
)

internal fun responseBody(flags: Map<String, JsonElement>, environment: String? = null): String = JsonObject(
    mapOf(
        "environment" to (environment?.let { JsonPrimitive(it) } ?: JsonNull),
        "evaluatedAt" to JsonPrimitive("2026-10-01T12:00:00.000Z"),
        "flags" to JsonObject(flags),
    ),
).toString()

internal val directExecutor = Executor { it.run() }

internal fun makeClient(
    transport: MockTransport,
    store: MemoryStore = MemoryStore(),
    apiKey: String = "rv_test_key",
    environment: String? = null,
    flagKeys: List<String>? = null,
    random: () -> Double = { 0.5 },
): RiviumFlags = RiviumFlags(
    config = RiviumFlagsConfig(apiKey = apiKey, environment = environment, flagKeys = flagKeys),
    store = store,
    transport = transport,
    callbackExecutor = directExecutor,
    dispatcher = Dispatchers.Default,
    application = null,
    debounceMs = 10,
    random = random,
)

internal suspend fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        delay(5)
    }
    return condition()
}

internal class EventRecorder : RiviumFlagsListener {
    val ready = java.util.concurrent.atomic.AtomicInteger()
    val updates = CopyOnWriteArrayList<Set<String>>()
    val errors = CopyOnWriteArrayList<RiviumFlagsError>()
    override fun onReady() { ready.incrementAndGet() }
    override fun onUpdate(changedKeys: Set<String>) { updates.add(changedKeys) }
    override fun onError(error: RiviumFlagsError) { errors.add(error) }
}

/** JSON → Kotlin with the SDK's own conversion (numbers as Double). */
internal fun JsonElement?.kotlin(): Any? = JsonConv.fromJson(this)

internal fun jsonArrayOf(vararg s: String) = JsonArray(s.map { JsonPrimitive(it) })
