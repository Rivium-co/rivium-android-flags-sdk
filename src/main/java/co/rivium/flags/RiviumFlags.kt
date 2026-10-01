package co.rivium.flags

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * Rivium Flags client for Android.
 *
 * Flags are evaluated on the Rivium Flags server (`POST /public/v2/evaluate`) for the current context; the SDK keeps
 * only the results, caches them on the device and serves them offline. No targeting rules ever reach the app.
 *
 * ```kotlin
 * val flags = RiviumFlags(applicationContext, RiviumFlagsConfig(apiKey = "rv_live_xxx", environment = "production"))
 * flags.start()
 * flags.identify("user-123", mapOf("plan" to "pro"))
 *
 * if (flags.isEnabled("new-checkout")) { … }
 * val theme = flags.getString("theme", "light")
 * ```
 *
 * Thread-safe. Getters never block on the network and never throw. Listener callbacks run on the main thread.
 */
class RiviumFlags internal constructor(
    val config: RiviumFlagsConfig,
    private val store: KeyValueStore,
    private val transport: HttpTransport,
    private val callbackExecutor: Executor,
    dispatcher: CoroutineDispatcher,
    private val application: Application?,
    private val debounceMs: Long = 250,
    private val random: () -> Double = { Math.random() },
) {
    /**
     * Creates a client. Loads the anonymous id and cached results; no network until [start].
     * @throws IllegalArgumentException when [RiviumFlagsConfig.apiKey] is a server secret (`rv_srv_…`).
     */
    constructor(context: Context, config: RiviumFlagsConfig) : this(
        config = config,
        store = SharedPreferencesStore(
            context.applicationContext.getSharedPreferences(StorageKeys.PREFS_NAME, Context.MODE_PRIVATE),
        ),
        transport = OkHttpTransport(config.requestTimeoutMs),
        callbackExecutor = MainThreadExecutor,
        dispatcher = Dispatchers.IO,
        application = context.applicationContext as? Application,
    )

    private val log = FlagsLog(config.debug)
    private val fingerprint = keyFingerprint(config.apiKey)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<RiviumFlagsListener>()
    private val readyFlow = MutableStateFlow(false)

    // ---- state, guarded by `lock`
    private var anonId: String
    private var userIdState: String? = null
    private var attributesState: Map<String, Any?> = emptyMap()
    private var cache: CachedEvaluation? = null
    private var readyFired = false
    private var started = false
    private var closed = false
    private var generation = 0
    private var inFlight: Deferred<Boolean>? = null
    private var debounceJob: Job? = null
    private var retryJob: Job? = null
    private var pollJob: Job? = null
    private var failures = 0
    private var blockedStatus: Int? = null
    private var rejectedBody: String? = null
    private var lastSuccessAt: Long? = null
    private var pendingRetry = false
    private var isForeground = true
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null

    init {
        require(!config.apiKey.startsWith("rv_srv_")) {
            "[Rivium Flags] a server secret (rv_srv_…) was passed as apiKey; use the public project key in apps"
        }
        val stored = store.getString(StorageKeys.ANONYMOUS_ID)
        anonId = if (isUsableId(stored)) stored!! else newAnonymousId().also { store.putString(StorageKeys.ANONYMOUS_ID, it) }
        StorageKeys.LEGACY.forEach { store.remove(it) }

        if (config.apiKey.isEmpty()) log.error("apiKey is empty")

        if (config.cacheEnabled) loadPersisted() else {
            store.remove(StorageKeys.CACHE)
            store.remove(StorageKeys.CONTEXT)
        }
        readyFlow.value = cache != null
    }

    // ================================================================ lifecycle

    /** Serves the cached results (if any) and fetches fresh ones. Safe to call more than once. */
    fun start() {
        val hasResults: Boolean
        synchronized(lock) {
            if (started || closed) return
            started = true
            hasResults = cache != null
        }
        installLifecycleCallbacks()
        if (hasResults) resultsAvailable()
        scope.launch { fetch(explicit = false) }
        startPolling()
    }

    /** Waits until results are available (cache or network) or [timeoutMs] passes. Returns [isReady]. */
    suspend fun awaitReady(timeoutMs: Long = 5_000): Boolean {
        if (isReady) return true
        return withTimeoutOrNull(timeoutMs) { readyFlow.first { it } } ?: false
    }

    /**
     * Fetches fresh results now. Also clears a stop caused by 401/403/404 so automatic fetches resume.
     * Returns true when the results are current (200 or 304).
     */
    suspend fun refresh(): Boolean {
        val ok = fetch(explicit = true)
        startPolling()
        return ok
    }

    /** Callback form of [refresh]; [callback] runs on the main thread. */
    fun refresh(callback: (Boolean) -> Unit) {
        scope.launch {
            val ok = refresh()
            callbackExecutor.execute { callback(ok) }
        }
    }

    /** Stops all network work and listeners. The cached results stay on the device. */
    fun close() {
        synchronized(lock) {
            closed = true
            generation++
            inFlight = null; debounceJob = null; retryJob = null; pollJob = null
        }
        scope.cancel()
        listeners.clear()
        lifecycleCallbacks?.let { application?.unregisterActivityLifecycleCallbacks(it) }
        lifecycleCallbacks = null
    }

    // ================================================================ context

    /** The install's anonymous id (UUID v4). Sent with every request; survives [reset]. */
    val anonymousId: String get() = synchronized(lock) { anonId }

    /** The current user id, or null when signed out. */
    val userId: String? get() = synchronized(lock) { userIdState }

    /** The current targeting attributes (numbers as Double). */
    val attributes: Map<String, Any?> get() = synchronized(lock) { attributesState }

    /**
     * Sets the signed-in user and (optionally) replaces the attributes; `attributes = null` keeps the current ones.
     * A different user id drops the cached results of the previous user immediately. Refetches (debounced 250 ms).
     */
    @JvmOverloads
    fun identify(userId: String?, attributes: Map<String, Any?>? = null) {
        updateContext(changeUser = true, newUserId = userId, newAttributes = attributes?.let { sanitizeAttributes(it, log) })
    }

    /** Sets (or clears, with null) the user id. Keeps the attributes. */
    fun setUserId(userId: String?) = updateContext(changeUser = true, newUserId = userId, newAttributes = null)

    /** Replaces all targeting attributes. Values: String, Number, Boolean, null or lists of those. */
    fun setAttributes(attributes: Map<String, Any?>) =
        updateContext(changeUser = false, newUserId = null, newAttributes = sanitizeAttributes(attributes, log))

    /** Sign-out: clears the user id, attributes and cached results. Keeps the anonymous id. Refetches. */
    fun reset() {
        synchronized(lock) {
            userIdState = null
            attributesState = emptyMap()
            cache = null
            readyFlow.value = false
            generation++
            inFlight?.cancel(); inFlight = null
            retryJob?.cancel(); retryJob = null
            store.remove(StorageKeys.CACHE)
        }
        scheduleContextFetch()
    }

    /** Generates and stores a new anonymous id (the install is re-bucketed). Returns the new id. */
    fun resetAnonymousId(): String {
        val id = synchronized(lock) {
            anonId = newAnonymousId()
            store.putString(StorageKeys.ANONYMOUS_ID, anonId)
            generation++
            inFlight?.cancel(); inFlight = null
            anonId
        }
        scheduleContextFetch()
        return id
    }

    // ================================================================ getters

    /** True when results are available (from the device cache or the network). */
    val isReady: Boolean get() = synchronized(lock) { cache != null }

    /** The flag's `enabled` (true only for reasons ON / VARIANT); [defaultValue] when not ready or not found. */
    @JvmOverloads
    fun isEnabled(key: String, defaultValue: Boolean = false): Boolean {
        val (ready, r) = snapshot(key)
        return if (ready && r != null) r.enabled else defaultValue
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean = getBooleanDetail(key, defaultValue).value
    fun getString(key: String, defaultValue: String): String = getStringDetail(key, defaultValue).value
    fun getNumber(key: String, defaultValue: Double): Double = getNumberDetail(key, defaultValue).value
    fun getJson(key: String, defaultValue: Any?): Any? = getJsonDetail(key, defaultValue).value

    fun getBooleanDetail(key: String, defaultValue: Boolean): FlagDetail<Boolean> =
        detail(key, defaultValue, FlagValueType.BOOLEAN) { it as? Boolean }

    fun getStringDetail(key: String, defaultValue: String): FlagDetail<String> =
        detail(key, defaultValue, FlagValueType.STRING) { it as? String }

    fun getNumberDetail(key: String, defaultValue: Double): FlagDetail<Double> =
        detail(key, defaultValue, FlagValueType.NUMBER) { (it as? Number)?.toDouble() }

    fun getJsonDetail(key: String, defaultValue: Any?): FlagDetail<Any?> =
        detailNullable(key, defaultValue, FlagValueType.JSON)

    /** Untyped detail: the served value whatever the flag's type (no TYPE_MISMATCH). */
    @JvmOverloads
    fun getDetail(key: String, defaultValue: Any? = null): FlagDetail<Any?> = detailNullable(key, defaultValue, null)

    /** Every result currently held (empty when not ready). */
    fun getAll(): Map<String, FlagResult> = synchronized(lock) { cache?.flags ?: emptyMap() }

    // ================================================================ listeners

    fun addListener(listener: RiviumFlagsListener) { listeners.addIfAbsent(listener) }
    fun removeListener(listener: RiviumFlagsListener) { listeners.remove(listener) }

    // ================================================================ internals: getters

    private fun snapshot(key: String): Pair<Boolean, FlagResult?> =
        synchronized(lock) { (cache != null) to cache?.flags?.get(key) }

    private fun <T> detail(key: String, defaultValue: T, expected: FlagValueType, extract: (Any?) -> T?): FlagDetail<T> {
        val (ready, r) = snapshot(key)
        if (!ready) return FlagDetail(key, defaultValue, false, null, FlagReason.NOT_READY, null)
        if (r == null) return FlagDetail(key, defaultValue, false, null, FlagReason.FLAG_NOT_FOUND, null)
        if (r.valueType != expected.wire) return FlagDetail(key, defaultValue, false, null, FlagReason.TYPE_MISMATCH, r.version)
        // Right type but no usable value (e.g. an off value of null served as false): code default, server reason.
        return FlagDetail(key, extract(r.value) ?: defaultValue, r.enabled, r.variant, r.reason, r.version)
    }

    private fun detailNullable(key: String, defaultValue: Any?, expected: FlagValueType?): FlagDetail<Any?> {
        val (ready, r) = snapshot(key)
        if (!ready) return FlagDetail(key, defaultValue, false, null, FlagReason.NOT_READY, null)
        if (r == null) return FlagDetail(key, defaultValue, false, null, FlagReason.FLAG_NOT_FOUND, null)
        if (expected != null && r.valueType != expected.wire) {
            return FlagDetail(key, defaultValue, false, null, FlagReason.TYPE_MISMATCH, r.version)
        }
        return FlagDetail(key, r.value, r.enabled, r.variant, r.reason, r.version)
    }

    // ================================================================ internals: context

    private fun updateContext(changeUser: Boolean, newUserId: String?, newAttributes: Map<String, Any?>?) {
        if (changeUser && newUserId != null && newUserId.length > 256) {
            log.error("userId must be 1-256 characters; identify ignored")
            return
        }
        val changed = synchronized(lock) {
            var changed = false
            if (changeUser) {
                val id = newUserId?.takeIf { it.isNotEmpty() }
                if (id != userIdState) {
                    userIdState = id
                    changed = true
                    // Another user: never serve the previous user's results.
                    cache = null
                    readyFlow.value = false
                    store.remove(StorageKeys.CACHE)
                }
            }
            if (newAttributes != null && newAttributes != attributesState) {
                attributesState = newAttributes
                changed = true
            }
            if (changed) {
                generation++
                inFlight?.cancel(); inFlight = null
                retryJob?.cancel(); retryJob = null
            }
            changed
        }
        if (changed) scheduleContextFetch()
    }

    private fun scheduleContextFetch() {
        synchronized(lock) {
            persistContextLocked()
            debounceJob?.cancel()
            debounceJob = null
            if (!started || closed) return
            debounceJob = scope.launch {
                delay(debounceMs)
                fetch(explicit = false)
            }
        }
    }

    private fun currentContextLocked() = EvalContext(userIdState, anonId, attributesState)

    // ================================================================ internals: network

    internal fun buildBody(context: EvalContext): String {
        val root = LinkedHashMap<String, JsonElement>()
        config.environment?.let { root["environment"] = JsonPrimitive(it) }
        root["context"] = context.toJson()
        config.flagKeys?.let { keys -> root["flagKeys"] = JsonArray(keys.map { JsonPrimitive(it) }) }
        return JsonObject(root).toString()
    }

    internal fun buildRequest(body: String, etag: String?): HttpRequest {
        val headers = LinkedHashMap<String, String>()
        headers["x-api-key"] = config.apiKey
        headers["x-rivium-sdk"] = SdkInfo.HEADER
        headers["content-type"] = "application/json"
        headers["accept"] = "application/json"
        if (etag != null) headers["If-None-Match"] = etag
        return HttpRequest(config.baseUrl.trimEnd('/') + "/public/v2/evaluate", headers, body.toByteArray(Charsets.UTF_8))
    }

    /** One request in flight; a newer call supersedes the older one (last write wins). */
    internal suspend fun fetch(explicit: Boolean): Boolean {
        val job: Deferred<Boolean> = synchronized(lock) {
            if (closed) return false
            if (explicit) blockedStatus = null else if (blockedStatus != null) return false
            val ctx = currentContextLocked()
            val body = buildBody(ctx)
            if (!explicit && rejectedBody == body) return false
            val c = cache
            val etag = if (c != null && c.matches(config.environment, config.flagKeys, ctx)) c.etag else null
            val gen = ++generation
            inFlight?.cancel()
            retryJob?.cancel(); retryJob = null
            pendingRetry = false
            val d = scope.async { perform(gen, body, ctx, etag) }
            inFlight = d
            d
        }
        return try {
            job.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // the caller itself was cancelled → propagate
            false
        }
    }

    private suspend fun perform(gen: Int, body: String, context: EvalContext, etag: String?): Boolean {
        val request = buildRequest(body, etag)
        log.debug { "evaluate ${if (context.userId == null) "anonymous" else "user"} context${if (etag == null) "" else " (If-None-Match)"}" }
        val response = try {
            transport.send(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(gen, RiviumFlagsError(RiviumFlagsError.Kind.NETWORK, null, null, e.message ?: e.javaClass.simpleName), null)
            return false
        }
        return handle(gen, response, body, context, etag)
    }

    private fun handle(gen: Int, response: HttpResponse, body: String, context: EvalContext, sentEtag: String?): Boolean {
        when (response.status) {
            200 -> {
                val parsed = parseFlags(response.body)
                if (parsed == null) {
                    failed(gen, RiviumFlagsError(RiviumFlagsError.Kind.INVALID_RESPONSE, 200, null, "response is not a Rivium Flags v2 result"), null)
                    return false
                }
                val etag = response.header("ETag")
                val changedKeys = synchronized(lock) {
                    if (gen != generation || closed) return false
                    val old = cache?.flags ?: emptyMap()
                    val next = CachedEvaluation(fingerprint, config.environment, config.flagKeys, context, etag, parsed, System.currentTimeMillis())
                    cache = next
                    lastSuccessAt = next.fetchedAt
                    failures = 0
                    rejectedBody = null
                    persistCacheLocked()
                    (old.keys + parsed.keys).filter { old[it] != parsed[it] }.toSet()
                }
                log.debug { "received ${parsed.size} flag results" }
                resultsAvailable()
                if (changedKeys.isNotEmpty()) emit { it.onUpdate(changedKeys) }
                return true
            }
            304 -> {
                val current = synchronized(lock) {
                    if (gen != generation || closed) return false
                    val c = cache
                    if (c == null || sentEtag == null) {
                        false
                    } else {
                        val now = System.currentTimeMillis()
                        cache = c.copy(fetchedAt = now)
                        lastSuccessAt = now
                        failures = 0
                        persistCacheLocked()
                        true
                    }
                }
                if (!current) {
                    failed(gen, RiviumFlagsError(RiviumFlagsError.Kind.INVALID_RESPONSE, 304, null, "304 without cached results"), null)
                    return false
                }
                log.debug { "results unchanged (304)" }
                resultsAvailable()
                return true
            }
        }
        val status = response.status
        val (code, message) = parseError(response.body)
        val msg = message ?: "HTTP $status"
        when (status) {
            400, 413 -> {
                synchronized(lock) {
                    if (gen != generation) return false
                    rejectedBody = body
                }
                report(RiviumFlagsError(RiviumFlagsError.Kind.INVALID_REQUEST, status, code, msg))
            }
            401, 403, 404 -> {
                val kind = when (status) {
                    401 -> RiviumFlagsError.Kind.UNAUTHORIZED
                    403 -> RiviumFlagsError.Kind.FORBIDDEN
                    else -> RiviumFlagsError.Kind.ENVIRONMENT_NOT_FOUND
                }
                synchronized(lock) {
                    if (gen != generation) return false
                    blockedStatus = status
                    pollJob?.cancel(); pollJob = null
                    retryJob?.cancel(); retryJob = null
                }
                report(RiviumFlagsError(kind, status, code, msg))
            }
            429 -> failed(
                gen,
                RiviumFlagsError(RiviumFlagsError.Kind.RATE_LIMITED, status, code, msg),
                Backoff.parseRetryAfter(response.header("Retry-After")),
            )
            else -> failed(
                gen,
                RiviumFlagsError(if (status >= 500) RiviumFlagsError.Kind.SERVER else RiviumFlagsError.Kind.INVALID_RESPONSE, status, code, msg),
                null,
            )
        }
        return false
    }

    /** Network / 5xx / 429: keep serving the cache and retry with back-off. */
    private fun failed(gen: Int, error: RiviumFlagsError, retryAfterS: Double?) {
        val delayS = synchronized(lock) {
            if (gen != generation || closed) return
            val d = Backoff.delaySeconds(failures, retryAfterS, random())
            failures++
            retryJob?.cancel()
            retryJob = scope.launch {
                delay((d * 1000).toLong())
                retryFired()
            }
            d
        }
        report(error, "; retrying in ${"%.0f".format(delayS)} s")
    }

    private fun retryFired() {
        synchronized(lock) {
            retryJob = null
            if (closed) return
            if (!isForeground) { pendingRetry = true; return }
        }
        scope.launch { fetch(explicit = false) }
    }

    private fun report(error: RiviumFlagsError, suffix: String = "") {
        when (error.kind) {
            RiviumFlagsError.Kind.NETWORK, RiviumFlagsError.Kind.SERVER, RiviumFlagsError.Kind.RATE_LIMITED -> log.warn("$error$suffix")
            else -> log.error("$error$suffix")
        }
        emit { it.onError(error) }
    }

    // ================================================================ internals: events

    private fun resultsAvailable() {
        val fire = synchronized(lock) {
            readyFlow.value = cache != null
            val f = !readyFired && !closed
            readyFired = true
            f
        }
        if (fire) emit { it.onReady() }
    }

    private fun emit(event: (RiviumFlagsListener) -> Unit) {
        if (listeners.isEmpty()) return
        val snapshot = listeners.toList()
        callbackExecutor.execute {
            for (l in snapshot) {
                try { event(l) } catch (t: Throwable) { log.error("listener threw: ${t.message}") }
            }
        }
    }

    // ================================================================ internals: polling and app lifecycle

    private fun startPolling() {
        val interval = config.effectivePollIntervalSeconds
        if (interval <= 0) return
        synchronized(lock) {
            if (!started || closed || !isForeground || blockedStatus != null || pollJob != null) return
            pollJob = scope.launch {
                while (isActive) {
                    delay(interval * 1000)
                    fetch(explicit = false)
                }
            }
        }
    }

    private fun stopPolling() {
        synchronized(lock) { pollJob?.cancel(); pollJob = null }
    }

    internal fun didEnterForeground() {
        val go = synchronized(lock) {
            isForeground = true
            if (!started || closed) return
            val last = lastSuccessAt
            val stale = last == null || System.currentTimeMillis() - last > FOREGROUND_STALE_MS
            val g = stale || pendingRetry
            pendingRetry = false
            g
        }
        if (go) scope.launch { fetch(explicit = false) }
        startPolling()
    }

    internal fun didEnterBackground() {
        synchronized(lock) { isForeground = false }
        stopPolling()
    }

    private fun installLifecycleCallbacks() {
        val app = application ?: return
        val cb = object : Application.ActivityLifecycleCallbacks {
            private var startedActivities = 0
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) didEnterForeground()
            }
            override fun onActivityStopped(activity: Activity) {
                startedActivities = maxOf(0, startedActivities - 1)
                if (startedActivities == 0 && !activity.isChangingConfigurations) didEnterBackground()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        lifecycleCallbacks = cb
        app.registerActivityLifecycleCallbacks(cb)
    }

    // ================================================================ internals: persistence and parsing

    private fun loadPersisted() {
        store.getString(StorageKeys.CONTEXT)?.let { raw ->
            try {
                val o = json.parseToJsonElement(raw) as JsonObject
                userIdState = (o["userId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                @Suppress("UNCHECKED_CAST")
                attributesState = (JsonConv.fromJson(o["attributes"]) as? Map<String, Any?>) ?: emptyMap()
            } catch (_: Exception) {
                store.remove(StorageKeys.CONTEXT)
            }
        }
        val raw = store.getString(StorageKeys.CACHE) ?: return
        val cached = try { CachedEvaluation.fromJson(json.parseToJsonElement(raw)) } catch (_: Exception) { null }
        if (cached == null || cached.keyFingerprint != fingerprint || cached.environment != config.environment ||
            cached.context.userId != userIdState
        ) {
            store.remove(StorageKeys.CACHE)
            return
        }
        cache = cached
        lastSuccessAt = cached.fetchedAt
    }

    private fun persistCacheLocked() {
        if (!config.cacheEnabled) return
        cache?.let { store.putString(StorageKeys.CACHE, it.toJson().toString()) }
    }

    private fun persistContextLocked() {
        if (!config.cacheEnabled) return
        if (userIdState == null && attributesState.isEmpty()) {
            store.remove(StorageKeys.CONTEXT)
            return
        }
        val o = LinkedHashMap<String, JsonElement>()
        o["userId"] = userIdState?.let { JsonPrimitive(it) } ?: JsonNull
        o["attributes"] = JsonConv.toJson(attributesState) ?: JsonObject(emptyMap())
        store.putString(StorageKeys.CONTEXT, JsonObject(o).toString())
    }

    private fun parseFlags(bytes: ByteArray): Map<String, FlagResult>? = try {
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        val flags = root?.get("flags") as? JsonObject
        flags?.let { parseFlagMap(it) }
    } catch (_: Exception) {
        null
    }

    private fun parseError(bytes: ByteArray): Pair<String?, String?> = try {
        val o = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        val code = (o?.get("code") as? JsonPrimitive)?.contentOrNull
        val message = when (val m = o?.get("message")) {
            is JsonPrimitive -> m.contentOrNull
            is JsonArray -> m.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString("; ")
            else -> null
        }
        code to message
    } catch (_: Exception) {
        null to null
    }

    companion object {
        internal const val FOREGROUND_STALE_MS = 15 * 60 * 1000L
        const val SDK_VERSION = SdkInfo.VERSION
    }
}

// ==================================================================== internal models

private object MainThreadExecutor : Executor {
    private val handler = Handler(Looper.getMainLooper())
    override fun execute(command: Runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) command.run() else handler.post(command)
    }
}

/** The context sent with every evaluate request. Attribute numbers are Doubles. */
internal data class EvalContext(val userId: String?, val anonymousId: String, val attributes: Map<String, Any?>) {
    fun toJson(): JsonObject {
        val o = LinkedHashMap<String, JsonElement>()
        userId?.let { o["userId"] = JsonPrimitive(it) }
        o["anonymousId"] = JsonPrimitive(anonymousId)
        o["attributes"] = JsonConv.toJson(attributes) ?: JsonObject(emptyMap())
        return JsonObject(o)
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromJson(e: JsonElement?): EvalContext? {
            val o = e as? JsonObject ?: return null
            val anon = (o["anonymousId"] as? JsonPrimitive)?.contentOrNull ?: return null
            return EvalContext(
                (o["userId"] as? JsonPrimitive)?.contentOrNull,
                anon,
                (JsonConv.fromJson(o["attributes"]) as? Map<String, Any?>) ?: emptyMap(),
            )
        }
    }
}

/** What is persisted on the device. Never contains the API key. */
internal data class CachedEvaluation(
    val keyFingerprint: String,
    val environment: String?,
    val flagKeys: List<String>?,
    val context: EvalContext,
    val etag: String?,
    val flags: Map<String, FlagResult>,
    val fetchedAt: Long,
) {
    fun matches(environment: String?, flagKeys: List<String>?, context: EvalContext): Boolean =
        this.environment == environment && this.flagKeys == flagKeys && this.context == context

    fun toJson(): JsonObject {
        val o = LinkedHashMap<String, JsonElement>()
        o["keyFingerprint"] = JsonPrimitive(keyFingerprint)
        o["environment"] = environment?.let { JsonPrimitive(it) } ?: JsonNull
        o["flagKeys"] = flagKeys?.let { k -> JsonArray(k.map { JsonPrimitive(it) }) } ?: JsonNull
        o["context"] = context.toJson()
        o["etag"] = etag?.let { JsonPrimitive(it) } ?: JsonNull
        o["fetchedAt"] = JsonPrimitive(fetchedAt)
        o["flags"] = JsonObject(flags.mapValues { (_, r) -> r.toJson() })
        return JsonObject(o)
    }

    companion object {
        fun fromJson(e: JsonElement): CachedEvaluation? {
            val o = e as? JsonObject ?: return null
            return CachedEvaluation(
                keyFingerprint = (o["keyFingerprint"] as? JsonPrimitive)?.contentOrNull ?: return null,
                environment = (o["environment"] as? JsonPrimitive)?.contentOrNull,
                flagKeys = (o["flagKeys"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                context = EvalContext.fromJson(o["context"]) ?: return null,
                etag = (o["etag"] as? JsonPrimitive)?.contentOrNull,
                flags = parseFlagMap(o["flags"] as? JsonObject ?: return null),
                fetchedAt = (o["fetchedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }
    }
}

internal fun FlagResult.toJson(): JsonObject = JsonObject(
    linkedMapOf(
        "key" to JsonPrimitive(key),
        "valueType" to JsonPrimitive(valueType),
        "enabled" to JsonPrimitive(enabled),
        "value" to (JsonConv.toJson(value) ?: JsonNull),
        "variant" to (variant?.let { JsonPrimitive(it) } ?: JsonNull),
        "reason" to JsonPrimitive(reason.value),
        "version" to JsonPrimitive(version),
    ),
)

/** Lenient parse of the `flags` map; unusable entries are skipped. */
internal fun parseFlagMap(flags: JsonObject): Map<String, FlagResult> {
    val out = LinkedHashMap<String, FlagResult>()
    for ((key, value) in flags) {
        val o = value as? JsonObject ?: continue
        out[key] = FlagResult(
            key = (o["key"] as? JsonPrimitive)?.contentOrNull ?: key,
            valueType = (o["valueType"] as? JsonPrimitive)?.contentOrNull ?: "json",
            enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false,
            value = JsonConv.fromJson(o["value"]),
            variant = (o["variant"] as? JsonPrimitive)?.contentOrNull,
            reason = FlagReason((o["reason"] as? JsonPrimitive)?.contentOrNull ?: "ERROR"),
            version = (o["version"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0,
        )
    }
    return out
}
