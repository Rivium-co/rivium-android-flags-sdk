<p align="center">
  <a href="https://rivium.co">
    <img src="https://rivium.co/logo.png" alt="Rivium" width="120" />
  </a>
</p>

<h3 align="center">Rivium Flags Android SDK</h3>

<p align="center">
  Feature flag management for Android with offline caching, targeting rules, and rollout control.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-1.9+-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 1.9+" />
  <img src="https://img.shields.io/badge/API-21+-3DDC84?logo=android&logoColor=white" alt="API 21+" />
  <a href="https://central.sonatype.com/artifact/co.rivium/rivium-flags-android"><img src="https://img.shields.io/maven-central/v/co.rivium/rivium-flags-android" alt="Maven Central" /></a>
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="MIT License" />
</p>

---

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("co.rivium:rivium-flags-android:0.1.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'co.rivium:rivium-flags-android:0.1.0'
}
```

## Quick Start

```kotlin
import co.rivium.flags.RiviumFlags
import co.rivium.flags.RiviumFlagsConfig

// Initialize
val flags = RiviumFlags(context, RiviumFlagsConfig(
    apiKey = "YOUR_API_KEY",
    environment = "production",
    enableOfflineCache = true
))

flags.init { event, data ->
    println("[$event] $data")
}

// Set user context
flags.setUserId("user-123")
flags.setUserAttributes(mapOf("plan" to "pro", "country" to "US"))

// Check flags
val darkMode = flags.isEnabled("dark_mode")
val variant = flags.getValue("checkout_flow")

// Full evaluation
val result = flags.evaluate("checkout_flow")
println("enabled: ${result.enabled}, value: ${result.value}, variant: ${result.variant}")

// Refresh from server
flags.refresh()
```

> **Note:** `init()` and `refresh()` are suspend functions — call them from a coroutine scope.

## Features

- **Boolean & Multivariate Flags** — Simple on/off toggles or multi-variant flags with weighted distribution
- **Targeting Rules** — Target users by attributes (equals, contains, regex, in, greater_than, and more)
- **Rollout Percentages** — Gradual rollouts with deterministic MD5-based bucketing
- **Offline Caching** — Flags cached in SharedPreferences for offline access
- **Environment Overrides** — Separate flag values per environment (development, staging, production)
- **Connectivity Aware** — Automatic online/offline detection with ConnectivityManager
- **Coroutines** — Native Kotlin coroutines support

## Documentation

For full documentation, visit [rivium.co/docs](https://rivium.co/docs).

## License

MIT License — see [LICENSE](LICENSE) for details.
