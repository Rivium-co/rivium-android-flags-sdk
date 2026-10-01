package co.rivium.flags

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs every case of docs/sdk-test-vectors.json through the client SDK: request building (context, anonymous id,
 * headers), response parsing, typed getters, reasons. Hash vectors do not apply: client SDKs never bucket.
 */
class VectorTest {

    private data class Case(val suite: String, val name: String, val flag: JsonObject, val context: JsonObject, val expected: JsonObject)

    private fun root(): JsonObject {
        val text = javaClass.classLoader!!.getResource("sdk-test-vectors.json")!!.readText()
        return Json.parseToJsonElement(text) as JsonObject
    }

    private fun cases(): List<Case> {
        val out = mutableListOf<Case>()
        for (suite in root()["suites"] as JsonArray) {
            suite as JsonObject
            val flags = (suite["flags"] as JsonArray).map { it as JsonObject }
            for (c in suite["cases"] as JsonArray) {
                c as JsonObject
                val key = (c["flagKey"] as JsonPrimitive).content
                out += Case(
                    (suite["name"] as JsonPrimitive).content,
                    (c["name"] as JsonPrimitive).content,
                    flags.first { (it["key"] as JsonPrimitive).content == key },
                    c["context"] as? JsonObject ?: JsonObject(emptyMap()),
                    c["expected"] as JsonObject,
                )
            }
        }
        return out
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

    @Test
    fun allVectorCases() = runBlocking {
        val all = cases()
        assertEquals("vector file changed: update the expected count", 171, all.size)
        var passed = 0
        for (c in all) {
            val label = "[${c.suite}] ${c.name}"
            val key = c.flag["key"].str()!!
            val valueType = c.flag["valueType"].str()!!
            val version = (c.flag["version"] as JsonPrimitive).intOrNull ?: 1
            val exp = c.expected
            val expEnabled = (exp["enabled"] as JsonPrimitive).booleanOrNull!!
            val expValue: JsonElement = exp["value"]!!

            val transport = MockTransport {
                Reply(
                    headers = mapOf("ETag" to "\"e2-test\""),
                    body = responseBody(
                        mapOf(key to flagJson(key, valueType, expEnabled, expValue, exp["variant"].str(), exp["reason"].str()!!, version)),
                        "production",
                    ),
                )
            }
            val store = MemoryStore()
            // Empty ids are "no id": never sent; an empty stored anonymous id is replaced.
            val userId = c.context["userId"].str()?.takeIf { it.isNotEmpty() }
            val presetAnon = c.context["anonymousId"].str()?.takeIf { it.isNotEmpty() }
            presetAnon?.let { store.putString("anonymous_id", it) }
            val client = makeClient(transport, store, environment = "production", flagKeys = listOf(key))

            assertEquals(label, FlagReason.NOT_READY, client.getDetail(key).reason)

            @Suppress("UNCHECKED_CAST")
            val attrs = (c.context["attributes"].kotlin() as? Map<String, Any?>) ?: emptyMap()
            client.identify(userId, attrs)
            client.start()
            assertTrue(label, client.awaitReady(3000))

            // --- request ---
            val req = transport.request(0)
            assertEquals(label, "https://flags.rivium.co/public/v2/evaluate", req.url)
            assertEquals(label, "rv_test_key", req.header("x-api-key"))
            assertEquals(label, "android/0.2.0", req.header("x-rivium-sdk"))
            assertEquals(label, "application/json", req.header("content-type"))
            assertNull(label, req.header("If-None-Match"))
            assertNull(label, req.header("x-server-secret"))
            val body = req.bodyJson()
            assertEquals(label, setOf("environment", "context", "flagKeys"), body.keys)
            assertEquals(label, "production", body["environment"].str())
            assertEquals(label, jsonArrayOf(key), body["flagKeys"])
            val ctx = body["context"] as JsonObject
            assertEquals(label, userId, ctx["userId"].str())
            assertEquals(label, client.anonymousId, ctx["anonymousId"].str())
            presetAnon?.let { assertEquals(label, it, client.anonymousId) }
            @Suppress("UNCHECKED_CAST")
            val expectedAttrs = ((c.context["attributes"].kotlin() as? Map<String, Any?>) ?: emptyMap()) - "userId" - "anonymousId"
            assertEquals(label, expectedAttrs, ctx["attributes"].kotlin())

            // --- results ---
            val d = client.getDetail(key)
            assertEquals(label, expEnabled, d.enabled)
            assertEquals(label, expValue.kotlin(), d.value)
            assertEquals(label, exp["variant"].str(), d.variant)
            assertEquals(label, exp["reason"].str(), d.reason.value)
            assertEquals(label, version, d.version)
            assertEquals(label, expEnabled, client.isEnabled(key, !expEnabled))

            val v = expValue.kotlin()
            when (valueType) {
                "boolean" -> {
                    if (v is Boolean) assertEquals(label, v, client.getBoolean(key, !v))
                    assertEquals(label, FlagReason.TYPE_MISMATCH, client.getStringDetail(key, "dflt").reason)
                    assertEquals(label, "dflt", client.getString(key, "dflt"))
                }
                "string" -> {
                    val sd = client.getStringDetail(key, "dflt")
                    assertEquals(label, (v as? String) ?: "dflt", sd.value)
                    assertEquals(label, exp["reason"].str(), sd.reason.value)
                    assertEquals(label, FlagReason.TYPE_MISMATCH, client.getBooleanDetail(key, true).reason)
                    assertTrue(label, client.getBoolean(key, true))
                }
                "number" -> {
                    assertEquals(label, (v as? Double) ?: -1.0, client.getNumber(key, -1.0), 0.0)
                    assertEquals(label, FlagReason.TYPE_MISMATCH, client.getJsonDetail(key, null).reason)
                }
                "json" -> {
                    assertEquals(label, v, client.getJson(key, "dflt"))
                    assertEquals(label, FlagReason.TYPE_MISMATCH, client.getNumberDetail(key, 7.0).reason)
                    assertEquals(label, 7.0, client.getNumber(key, 7.0), 0.0)
                }
                else -> throw AssertionError("unknown valueType $valueType")
            }

            val missing = client.getStringDetail("__missing__", "code")
            assertEquals(label, FlagReason.FLAG_NOT_FOUND, missing.reason)
            assertEquals(label, "code", missing.value)
            assertFalse(label, missing.enabled)

            client.close()
            passed++
        }
        assertEquals(171, passed)
        println("Rivium Flags vectors: $passed/${all.size} client cases passed")
    }

    @Test
    fun hashVectorsAreServerOnly() {
        // Client SDKs never compute buckets; the 7 hash vectors apply to server SDKs only.
        assertEquals(7, (root()["hashVectors"] as JsonArray).size)
    }
}
