<p align="center">
  <a href="https://rivium.co">
    <img src="https://rivium.co/logo.png" alt="Rivium" width="120" />
  </a>
</p>

<h3 align="center">Rivium Flags Android SDK</h3>

<p align="center">
  Rivium Flags client for Android: flags evaluated on the server for your user, cached on the device, typed getters with reasons.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-1.9+-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 1.9+" />
  <img src="https://img.shields.io/badge/API-21+-3DDC84?logo=android&logoColor=white" alt="API 21+" />
  <a href="https://central.sonatype.com/artifact/co.rivium/rivium-flags-android"><img src="https://img.shields.io/maven-central/v/co.rivium/rivium-flags-android" alt="Maven Central" /></a>
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="MIT License" />
</p>

---

## Installation

```kotlin
dependencies {
    implementation("co.rivium:rivium-flags-android:0.2.0")
}
```

Groovy: `implementation 'co.rivium:rivium-flags-android:0.2.0'`. Min SDK 21. Needs the `INTERNET` permission
(merged from the library manifest).

## Quick start

```kotlin
import co.rivium.flags.RiviumFlags
import co.rivium.flags.RiviumFlagsConfig

// One instance per app, e.g. in Application.onCreate
val flags = RiviumFlags(
    applicationContext,
    RiviumFlagsConfig(
        apiKey = "rv_live_xxx",          // public project key — never a server secret
        environment = "production",      // null = the Default layer
    ),
)
flags.start()                            // serves the device cache at once, then fetches

if (flags.isEnabled("new-checkout")) {
    // …
}
val theme = flags.getString("theme", "light")
```

`start()` returns immediately and does all network work off the main thread. Getters never block and never throw:
before the first result they return your default (reason `NOT_READY`). To wait for the first result:

```kotlin
lifecycleScope.launch {
    val ready = flags.awaitReady(timeoutMs = 3_000)
}
```

A server secret (`rv_srv_…`) passed as `apiKey` throws `IllegalArgumentException`.

## Identify users

```kotlin
flags.identify("user-123", mapOf("plan" to "pro", "country" to "AM", "beta" to true, "age" to 31))
flags.setAttributes(mapOf("plan" to "business"))   // replaces all attributes
flags.setUserId(null)                              // signed out, keeps attributes
flags.reset()                                      // sign-out: clears user, attributes and cached results
```

- Context changes refetch after 250 ms (debounced). A **different user id drops the cached results at once**, so one
  user never sees another user's flags.
- Attributes: String (≤ 1,024 chars), Number, Boolean, null, or lists of those; ≤ 100 keys. Anything else is dropped
  with a log warning. `userId` / `anonymousId` are not attributes.
- Every install has an **anonymous id** (UUID v4, `SharedPreferences("rivium_flags")["anonymous_id"]`). It is sent
  with every request so rollouts work before sign-in, and it survives `reset()`. `flags.resetAnonymousId()` makes a
  new one.

## Getters

| Call | Returns |
|---|---|
| `isEnabled(key, defaultValue = false)` | the flag's `enabled` (true only for `ON` / `VARIANT`) |
| `getBoolean` / `getString` / `getNumber` (Double) / `getJson` (Map / List / String / Double / Boolean / null) | the served value, or your default |
| `getBooleanDetail` / `getStringDetail` / `getNumberDetail` / `getJsonDetail` | `FlagDetail(value, enabled, variant, reason, version)` |
| `getDetail(key, defaultValue = null)` | untyped detail (any value type) |
| `getAll()` | every result held, `Map<String, FlagResult>` |

Reasons: `ON`, `VARIANT`, `DISABLED`, `PREREQUISITE_FAILED`, `NOT_TARGETED`, `OUTSIDE_ROLLOUT`, `NO_BUCKETING_ID`,
`ERROR` (from the server) and `FLAG_NOT_FOUND`, `NOT_READY`, `TYPE_MISMATCH` (from the SDK; your default is returned).
A typed getter on a flag of another type (e.g. `getBoolean` on a `string` flag) returns your default with
`TYPE_MISMATCH`.

```kotlin
val d = flags.getStringDetail("theme", "light")
Log.d("app", "${d.value} ${d.variant} ${d.reason}")
```

## Events

```kotlin
flags.addListener(object : RiviumFlagsListener {      // callbacks on the main thread
    override fun onReady() {}                          // first results (cache or network), once
    override fun onUpdate(changedKeys: Set<String>) {}
    override fun onError(error: RiviumFlagsError) {}   // kind, statusCode, code, message
})
```

## Refresh, caching and errors

- Fetches happen on `start()`, on context changes, when the app returns to the foreground and the results are older
  than 15 minutes, and on `refresh()` (suspend, or `refresh { ok -> }`).
- Optional polling: `RiviumFlagsConfig(refreshIntervalSeconds = 300)` (off by default, minimum 60 s, foreground only).
  **Every evaluate request is a billed usage event**, which is why polling is off by default.
- Results are cached in `SharedPreferences("rivium_flags")` (never the API key) and served offline; a failure never
  clears them. `ETag` / `If-None-Match` avoid re-downloading unchanged results.
- 401 / 403 / 404 stop automatic fetches until `refresh()` (bad key, refused, unknown environment). 429 waits
  `Retry-After`, then backs off ×2 up to 5 minutes; network errors and 5xx back off the same way. 400 is not retried.
- `flagKeys = listOf("a", "b")` in the config limits evaluation to those flags.
- `flags.close()` stops all network work.

## Client SDK, not server SDK

This is a **client** SDK: it uses the public key and asks the Rivium Flags server for results
(`POST /public/v2/evaluate`). No targeting rules, segments or rollout salts reach the device. Backends that evaluate
locally use the Node.js / Next.js server SDKs with a server secret — never put that secret in an app.

## Migrating from 0.1.x

0.2.0 is a new API: 0.1.x evaluated rules on the device and no longer receives them.

| 0.1.x | 0.2.0 |
|---|---|
| `suspend fun init(callback)` | `start()` + `addListener` / `awaitReady()` |
| `setUserId` + `setUserAttributes` (merge) | `identify(userId, attributes)`, `setUserId`, `setAttributes` (replace) |
| `getValue(key, defaultValue)` | `getBoolean` / `getString` / `getNumber` / `getJson` |
| `evaluate(key)` → `FlagEvalResult` | `getDetail(key)` → `FlagDetail` (adds `reason`, `version`) |
| `getAll()` → `List<FeatureFlag>` (rules) | `getAll()` → `Map<String, FlagResult>` (results only) |
| `RiviumFlags.getInstance()` | keep your own instance |
| `enableOfflineCache` | `cacheEnabled` |
| `reset()` / `dispose()` | `reset()` (sign-out) / `close()` |

The 0.1.x cache keys are deleted on first use. Users are bucketed again once (new SHA-256 bucketing on the server).

## Documentation

For full documentation, visit [rivium.co/docs](https://rivium.co/docs).

## License

MIT License — see [LICENSE](LICENSE) for details.
