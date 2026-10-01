package co.rivium.flags

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ClientTest {

    private val themeDark = flagJson("theme", "string", true, JsonPrimitive("dark"), "dark", "VARIANT", 3)
    private val themeLight = flagJson("theme", "string", true, JsonPrimitive("light"), "light", "VARIANT", 4)

    private fun ok(vararg flags: Pair<String, JsonObject>, etag: String? = "\"e2-1\"") =
        Reply(headers = etag?.let { mapOf("ETag" to it) } ?: emptyMap(), body = responseBody(flags.toMap()))

    private fun HttpRequest.userId(): String? = ((bodyJson()["context"] as JsonObject)["userId"] as? JsonPrimitive)?.contentOrNull
    private fun HttpRequest.attributes(): Any? = (bodyJson()["context"] as JsonObject)["attributes"].kotlin()

    // ---------------------------------------------------------------- anonymous id

    @Test
    fun anonymousIdIsPersistedLowercaseUuidV4() {
        val store = MemoryStore()
        val a = makeClient(MockTransport(), store)
        assertTrue(a.anonymousId.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")))
        assertEquals(a.anonymousId, store.map["anonymous_id"])
        assertEquals(a.anonymousId, makeClient(MockTransport(), store).anonymousId)
    }

    @Test
    fun resetKeepsAnonymousIdAndResetAnonymousIdReplacesIt() {
        val store = MemoryStore()
        val c = makeClient(MockTransport(), store)
        val id = c.anonymousId
        c.identify("u1", mapOf("plan" to "pro"))
        c.reset()
        assertEquals(id, c.anonymousId)
        assertNull(c.userId)
        assertTrue(c.attributes.isEmpty())
        val fresh = c.resetAnonymousId()
        assertNotEquals(id, fresh)
        assertEquals(fresh, c.anonymousId)
        assertEquals(fresh, store.map["anonymous_id"])
    }

    @Test
    fun legacyKeysRemoved() {
        val store = MemoryStore().apply { putString("cached_flags", "{}"); putString("user_id", "x") }
        makeClient(MockTransport(), store)
        assertNull(store.map["cached_flags"])
        assertNull(store.map["user_id"])
    }

    @Test
    fun serverSecretRefused() {
        val e = runCatching { makeClient(MockTransport(), apiKey = "rv_srv_secret") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertFalse(e!!.message!!.contains("rv_srv_secret"))
    }

    @Test
    fun typeMismatchDetail() = runBlocking {
        val c = makeClient(MockTransport { ok("theme" to themeDark) })
        c.start()
        c.awaitReady()
        val d = c.getBooleanDetail("theme", true)
        assertEquals(FlagReason.TYPE_MISMATCH, d.reason)
        assertTrue(d.value)
        assertFalse(d.enabled)
        assertNull(d.variant)
        c.close()
    }

    @Test
    fun anonymousIdSentAlsoWithUserIdAndDefaultsOmitted() = runBlocking {
        val t = MockTransport { Reply(body = responseBody(emptyMap())) }
        val c = makeClient(t)
        c.identify("u1")
        c.start()
        c.awaitReady()
        val body = t.request(0).bodyJson()
        val ctx = body["context"] as JsonObject
        assertEquals("u1", (ctx["userId"] as JsonPrimitive).content)
        assertEquals(c.anonymousId, (ctx["anonymousId"] as JsonPrimitive).content)
        assertFalse(body.containsKey("environment"))
        assertFalse(body.containsKey("flagKeys"))
        c.close()
    }

    // ---------------------------------------------------------------- getters

    @Test
    fun notReadyThenFlagNotFound() = runBlocking {
        val c = makeClient(MockTransport { ok("theme" to themeDark) })
        assertFalse(c.isReady)
        assertEquals(FlagReason.NOT_READY, c.getStringDetail("theme", "x").reason)
        assertEquals("x", c.getString("theme", "x"))
        assertTrue(c.isEnabled("theme", true))
        c.start()
        assertTrue(c.awaitReady())
        assertEquals("dark", c.getString("theme", "x"))
        assertEquals(FlagReason.FLAG_NOT_FOUND, c.getStringDetail("nope", "x").reason)
        assertEquals(setOf("theme"), c.getAll().keys)
        c.close()
    }

    // ---------------------------------------------------------------- cache and ETag

    @Test
    fun cacheServedAtStartAndScopedToKeyEnvironmentAndUser() = runBlocking {
        val store = MemoryStore()
        val a = makeClient(MockTransport { ok("theme" to themeDark) }, store, environment = "production")
        a.identify("u1", mapOf("plan" to "pro"))
        a.start()
        a.awaitReady()
        a.close()
        assertFalse("never store the key", store.map.values.any { it.contains("rv_test_key") })

        val offline = MockTransport { Reply(networkError = true) }
        val b = makeClient(offline, store, environment = "production")
        assertTrue(b.isReady)
        assertEquals("dark", b.getString("theme", "x"))
        assertEquals("u1", b.userId)
        assertEquals("pro", b.attributes["plan"])
        b.start()
        waitUntil { offline.count >= 1 }
        delay(50)
        assertEquals("never clear the cache on failure", "dark", b.getString("theme", "x"))
        b.close()

        assertFalse(makeClient(offline, store, environment = "staging").isReady)
        assertFalse(makeClient(offline, store, apiKey = "rv_test_other", environment = "production").isReady)
    }

    @Test
    fun ifNoneMatchOnlyForSameContextAnd304KeepsResults() = runBlocking {
        val t = MockTransport { req ->
            if (req.header("If-None-Match") == "\"e2-1\"") Reply(status = 304, headers = mapOf("ETag" to "\"e2-1\""))
            else ok("theme" to themeDark)
        }
        val c = makeClient(t)
        c.start()
        c.awaitReady()
        assertNull(t.request(0).header("If-None-Match"))
        assertTrue(c.refresh())
        assertEquals("\"e2-1\"", t.request(1).header("If-None-Match"))
        assertEquals("dark", c.getString("theme", "x"))
        c.setAttributes(mapOf("plan" to "pro"))
        waitUntil { t.count >= 3 }
        assertNull("context changed: no ETag", t.request(2).header("If-None-Match"))
        c.close()
    }

    @Test
    fun userChangeDropsCacheImmediately() = runBlocking {
        val t = MockTransport { req -> if (req.userId() == "u2") ok("theme" to themeLight, etag = "\"e2-2\"") else ok("theme" to themeDark) }
        val c = makeClient(t)
        c.identify("u1")
        c.start()
        c.awaitReady()
        assertEquals("dark", c.getString("theme", "x"))
        c.identify("u2")
        assertFalse("previous user's results must not be served", c.isReady)
        assertEquals(FlagReason.NOT_READY, c.getStringDetail("theme", "x").reason)
        assertTrue(c.awaitReady())
        assertEquals("light", c.getString("theme", "x"))
        c.setAttributes(mapOf("plan" to "pro"))
        assertTrue("same user: keep serving meanwhile", c.isReady)
        c.close()
    }

    @Test
    fun identifyIsDebouncedAndSameContextDoesNotRefetch() = runBlocking {
        val t = MockTransport { ok("theme" to themeDark) }
        val c = makeClient(t)
        c.start()
        c.awaitReady()
        waitUntil { t.count == 1 }
        c.setAttributes(mapOf("a" to 1))
        c.setAttributes(mapOf("a" to 2))
        c.setAttributes(mapOf("a" to 3))
        waitUntil { t.count >= 2 }
        delay(100)
        assertEquals(2, t.count)
        assertEquals(mapOf("a" to 3.0), t.request(1).attributes())
        c.setAttributes(mapOf("a" to 3))
        delay(100)
        assertEquals(2, t.count)
        c.close()
    }

    @Test
    fun newerRequestSupersedesOlder() = runBlocking {
        val t = MockTransport { req ->
            if (req.userId() == "slow") Reply(body = responseBody(mapOf("theme" to themeDark)), delayMs = 300)
            else ok("theme" to themeLight)
        }
        val c = makeClient(t)
        c.identify("slow")
        val first = async { c.refresh() }
        delay(50)
        c.identify("fast")
        val second = c.refresh()
        assertTrue(second)
        assertFalse(first.await())
        delay(400)
        assertEquals("last write wins", "light", c.getString("theme", "x"))
        c.close()
    }

    // ---------------------------------------------------------------- errors

    @Test
    fun unauthorizedStopsAutomaticFetchesUntilRefresh() = runBlocking {
        val t = MockTransport { Reply(status = 401, body = """{"statusCode":401,"message":"Invalid API key"}""") }
        val c = makeClient(t)
        val ev = EventRecorder()
        c.addListener(ev)
        c.start()
        waitUntil { ev.errors.size == 1 }
        assertEquals(RiviumFlagsError.Kind.UNAUTHORIZED, ev.errors[0].kind)
        assertEquals("Invalid API key", ev.errors[0].message)
        c.setAttributes(mapOf("plan" to "pro"))
        delay(100)
        assertEquals("no automatic retry after 401", 1, t.count)
        c.refresh()
        assertEquals("refresh() resumes", 2, t.count)
        c.close()
    }

    @Test
    fun notFoundAndForbiddenReportedAndNotRetried() = runBlocking {
        for ((status, kind) in listOf(404 to RiviumFlagsError.Kind.ENVIRONMENT_NOT_FOUND, 403 to RiviumFlagsError.Kind.FORBIDDEN)) {
            val t = MockTransport { Reply(status = status, body = """{"statusCode":$status,"code":"environment_not_found","message":"nope"}""") }
            val c = makeClient(t, environment = "nope")
            val ev = EventRecorder()
            c.addListener(ev)
            c.start()
            waitUntil { ev.errors.size == 1 }
            assertEquals(kind, ev.errors[0].kind)
            assertEquals(status, ev.errors[0].statusCode)
            assertEquals("environment_not_found", ev.errors[0].code)
            delay(100)
            assertEquals(1, t.count)
            c.close()
        }
    }

    @Test
    fun badRequestSameBodyNotRetriedAutomatically() = runBlocking {
        val t = MockTransport { Reply(status = 400, body = """{"statusCode":400,"code":"invalid_request","message":"bad"}""") }
        val c = makeClient(t)
        c.start()
        waitUntil { t.count == 1 }
        delay(50)
        c.didEnterForeground()
        delay(100)
        assertEquals(1, t.count)
        c.setAttributes(mapOf("x" to 1))
        waitUntil { t.count == 2 }
        assertEquals("a different body is sent", 2, t.count)
        c.close()
    }

    @Test
    fun rateLimitedRetriesAfterRetryAfterAndServerErrorKeepsCache() = runBlocking {
        val calls = AtomicInteger()
        val t = MockTransport {
            when (calls.incrementAndGet()) {
                1 -> Reply(status = 429, headers = mapOf("Retry-After" to "0"))
                2 -> ok("theme" to themeDark)
                else -> Reply(status = 503)
            }
        }
        val c = makeClient(t, random = { 0.0 })
        val ev = EventRecorder()
        c.addListener(ev)
        c.start()
        assertTrue("retried after Retry-After", c.awaitReady(3000))
        assertEquals(2, t.count)
        assertTrue(waitUntil { ev.errors.any { it.kind == RiviumFlagsError.Kind.RATE_LIMITED } })
        assertFalse(c.refresh())
        assertEquals("5xx keeps serving the cache", "dark", c.getString("theme", "x"))
        waitUntil { ev.errors.any { it.kind == RiviumFlagsError.Kind.SERVER } }
        c.close()
    }

    @Test
    fun backoffSchedule() {
        assertEquals(7.0, Backoff.delaySeconds(0, 7.0, 0.0), 1e-9)
        assertEquals(8.4, Backoff.delaySeconds(0, 7.0, 1.0), 1e-9)
        assertEquals(14.0, Backoff.delaySeconds(1, 7.0, 0.5), 1e-9)
        assertEquals(5.0, Backoff.delaySeconds(0, null, 0.5), 1e-9)
        assertEquals(40.0, Backoff.delaySeconds(3, null, 0.5), 1e-9)
        assertEquals(300.0, Backoff.delaySeconds(10, null, 0.5), 1e-9)
        assertEquals(300.0, Backoff.delaySeconds(10, null, 1.0), 1e-9)
        assertEquals(240.0, Backoff.delaySeconds(10, null, 0.0), 1e-9)
        assertEquals(12.0, Backoff.parseRetryAfter("12"), 1e-9)
        assertEquals(5.0, Backoff.parseRetryAfter(null), 1e-9)
        assertEquals(5.0, Backoff.parseRetryAfter("garbage"), 1e-9)
    }

    // ---------------------------------------------------------------- attributes and events

    @Test
    fun attributeSanitizing() {
        val c = makeClient(MockTransport())
        c.setAttributes(
            mapOf(
                "plan" to "pro", "age" to 31, "beta" to true, "tags" to listOf("a", 1, null), "none" to null,
                "nested" to mapOf("a" to 1), "userId" to "spoof", "anonymousId" to "spoof", "long" to "x".repeat(1025),
                "nan" to Double.NaN, "obj" to Any(),
            ),
        )
        assertEquals(
            mapOf("age" to 31.0, "beta" to true, "none" to null, "plan" to "pro", "tags" to listOf("a", 1.0, null)),
            c.attributes,
        )
    }

    @Test
    fun readyOnceAndUpdatedEvents() = runBlocking {
        val t = MockTransport { req ->
            if ((req.attributes() as Map<*, *>)["v"] == 2.0) ok("theme" to themeLight, etag = "\"e2-2\"") else ok("theme" to themeDark)
        }
        val c = makeClient(t)
        val ev = EventRecorder()
        c.addListener(ev)
        c.start()
        waitUntil { ev.updates.size == 1 }
        assertEquals(1, ev.ready.get())
        c.setAttributes(mapOf("v" to 2))
        waitUntil { ev.updates.size == 2 }
        assertEquals(setOf("theme"), ev.updates[1])
        assertEquals("ready fires once", 1, ev.ready.get())
        c.close()
    }

    @Test
    fun pollingIntervalClamp() {
        assertEquals(0L, RiviumFlagsConfig("k").effectivePollIntervalSeconds)
        assertEquals(60L, RiviumFlagsConfig("k", refreshIntervalSeconds = 10).effectivePollIntervalSeconds)
        assertEquals(120L, RiviumFlagsConfig("k", refreshIntervalSeconds = 120).effectivePollIntervalSeconds)
        assertFalse("toString never prints the key", RiviumFlagsConfig("rv_live_secret").toString().contains("rv_live_secret"))
    }

    @Test
    fun foregroundRefetchOnlyWhenStale() = runBlocking {
        val t = MockTransport { ok("theme" to themeDark) }
        val c = makeClient(t)
        c.start()
        c.awaitReady()
        waitUntil { t.count == 1 }
        c.didEnterBackground()
        c.didEnterForeground()
        delay(100)
        assertEquals("fresh results: no refetch on foreground", 1, t.count)
        c.close()
    }
}
