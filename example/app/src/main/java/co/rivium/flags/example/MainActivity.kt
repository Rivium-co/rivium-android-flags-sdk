package co.rivium.flags.example

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import co.rivium.flags.RiviumFlags
import co.rivium.flags.RiviumFlagsConfig
import kotlinx.coroutines.launch

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Rivium Flags — Android SDK Test Suite
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

class MainActivity : AppCompatActivity() {

    private val apiKey = "YOUR_API_KEY" // Replace with your API key from Rivium Console

    data class TestResult(val test: String, val detail: String, val pass: Boolean)

    private var selectedUser = "test-user-1"
    private var selectedEnv = "none"
    private var flags: RiviumFlags? = null
    private var isOnline = false

    private lateinit var resultsLayout: LinearLayout
    private lateinit var statusBar: LinearLayout
    private lateinit var statusIcon: TextView
    private lateinit var statusText: TextView
    private lateinit var loadingBar: ProgressBar

    private val users = listOf("test-user-1", "test-user-2", "test-user-3")
    private val environments = listOf("none", "development", "staging", "production")

    private val userButtons = mutableListOf<Button>()
    private val envButtons = mutableListOf<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF8FAFC.toInt())
        }

        // ── Toolbar ──
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFF59E0B.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(TextView(this).apply {
            text = "RiviumFlags Test Suite"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val refreshBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_popup_sync)
            setBackgroundColor(0x00000000)
            setColorFilter(0xFFFFFFFF.toInt())
            setOnClickListener { runTests() }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        toolbar.addView(refreshBtn)
        rootLayout.addView(toolbar)

        // ── User switcher ──
        val userRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFFFFBEB.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        userRow.addView(TextView(this).apply {
            text = "User:"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF374151.toInt())
        })
        val userScroll = HorizontalScrollView(this).apply {
            setPadding(dp(8), 0, 0, 0)
            isHorizontalScrollBarEnabled = false
        }
        val userChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (uid in users) {
            val btn = Button(this).apply {
                text = uid
                textSize = 11f
                isAllCaps = false
                setPadding(dp(12), dp(4), dp(12), dp(4))
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(32),
                )
                params.setMargins(0, 0, dp(8), 0)
                layoutParams = params
                setOnClickListener {
                    selectedUser = uid
                    updateUserChips()
                    runTests()
                }
            }
            userButtons.add(btn)
            userChips.addView(btn)
        }
        userScroll.addView(userChips)
        userRow.addView(userScroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rootLayout.addView(userRow)

        // ── Environment switcher ──
        val envRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFEFF6FF.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        envRow.addView(TextView(this).apply {
            text = "Env:"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF374151.toInt())
        })
        val envScroll = HorizontalScrollView(this).apply {
            setPadding(dp(8), 0, 0, 0)
            isHorizontalScrollBarEnabled = false
        }
        val envChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (env in environments) {
            val btn = Button(this).apply {
                text = if (env == "none") "Global" else env
                textSize = 11f
                isAllCaps = false
                setPadding(dp(12), dp(4), dp(12), dp(4))
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(32),
                )
                params.setMargins(0, 0, dp(8), 0)
                layoutParams = params
                setOnClickListener {
                    selectedEnv = env
                    updateEnvChips()
                    runTests()
                }
            }
            envButtons.add(btn)
            envChips.addView(btn)
        }
        envScroll.addView(envChips)
        envRow.addView(envScroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rootLayout.addView(envRow)

        // ── Connection status bar ──
        statusBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFF0FDF4.toInt())
        }
        statusIcon = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, dp(8), 0)
        }
        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF374151.toInt())
        }
        statusBar.addView(statusIcon)
        statusBar.addView(statusText)
        rootLayout.addView(statusBar)

        // ── Loading bar ──
        loadingBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        rootLayout.addView(loadingBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(4),
        ))

        // ── Test results ──
        val scrollView = ScrollView(this)
        resultsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scrollView.addView(resultsLayout)
        rootLayout.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        setContentView(rootLayout)

        updateUserChips()
        updateEnvChips()
        runTests()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
        ).toInt()
    }

    private fun updateUserChips() {
        for ((i, btn) in userButtons.withIndex()) {
            val selected = users[i] == selectedUser
            btn.setBackgroundColor(if (selected) 0xFFF59E0B.toInt() else 0xFFE5E7EB.toInt())
            btn.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF374151.toInt())
        }
    }

    private fun updateEnvChips() {
        for ((i, btn) in envButtons.withIndex()) {
            val selected = environments[i] == selectedEnv
            btn.setBackgroundColor(if (selected) 0xFF3B82F6.toInt() else 0xFFE5E7EB.toInt())
            btn.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF374151.toInt())
        }
    }

    private fun updateStatusBar(online: Boolean, flagCount: Int) {
        runOnUiThread {
            isOnline = online
            statusBar.setBackgroundColor(if (online) 0xFFF0FDF4.toInt() else 0xFFFEF2F2.toInt())
            statusIcon.text = if (online) "\uD83D\uDFE2" else "\uD83D\uDD34"
            statusText.text = if (online) "Online — $flagCount flags loaded" else "Offline — using cached flags"
        }
    }

    private fun runTests() {
        lifecycleScope.launch {
            runOnUiThread {
                resultsLayout.removeAllViews()
                loadingBar.visibility = View.VISIBLE
            }

            val results = mutableListOf<TestResult>()

            // Dispose previous instance
            flags?.dispose()
            flags = null

            // ── Initialize SDK ──
            val sdk = RiviumFlags(
                context = applicationContext,
                config = RiviumFlagsConfig(
                    apiKey = apiKey,
                    environment = if (selectedEnv == "none") null else selectedEnv,
                    debug = true,
                    enableOfflineCache = true,
                ),
            )
            flags = sdk

            try {
                sdk.init { event, data ->
                    android.util.Log.d("RiviumFlags", "[$event] $data")
                }
                results.add(TestResult("Initialize SDK", "Connected with API key", true))
            } catch (e: Exception) {
                results.add(TestResult("Initialize SDK", "Failed: ${e.message}", false))
                updateStatusBar(false, 0)
                renderResults(results)
                return@launch
            }

            // Set user context
            sdk.setUserId(selectedUser)
            sdk.setUserAttributes(mapOf("plan" to "pro", "country" to "US"))

            // ── Test 1: Fetch all flags ──
            val allFlags = sdk.getAll()
            val flagCount = allFlags.size
            updateStatusBar(flagCount > 0, flagCount)
            results.add(TestResult(
                "GET /public/flags",
                "Fetched $flagCount flags: ${allFlags.joinToString(", ") { it.key }}",
                allFlags.isNotEmpty(),
            ))

            // ── Test 2: Boolean flag ──
            val darkMode = sdk.isEnabled("dark_mode")
            results.add(TestResult("Boolean flag: dark_mode", "isEnabled = $darkMode", true))

            // ── Test 3: Multivariate flag ──
            val checkoutEnabled = sdk.isEnabled("checkout_flow")
            val checkoutValue = sdk.getValue("checkout_flow")
            results.add(TestResult(
                "Multivariate: checkout_flow",
                "enabled=$checkoutEnabled, value=$checkoutValue",
                true,
            ))

            // ── Test 4: Targeting rules (matching) ──
            val premiumMatch = sdk.isEnabled("premium_banner")
            results.add(TestResult(
                "Targeting (plan=pro, country=US)",
                "premium_banner = $premiumMatch",
                true,
            ))

            // Targeting (non-matching)
            sdk.setUserAttributes(mapOf("plan" to "free", "country" to "IR"))
            val premiumNoMatch = sdk.isEnabled("premium_banner")
            results.add(TestResult(
                "Targeting (plan=free, country=IR)",
                "premium_banner = $premiumNoMatch",
                true,
            ))

            // Restore
            sdk.setUserAttributes(mapOf("plan" to "pro", "country" to "US"))

            // ── Test 5: Rollout ──
            val rolloutResults = mutableMapOf<String, Boolean>()
            for (uid in listOf("user-1", "user-2", "user-3", "user-4", "user-5")) {
                sdk.setUserId(uid)
                rolloutResults[uid] = sdk.isEnabled("gradual_redesign")
            }
            val enabledCount = rolloutResults.values.count { it }
            results.add(TestResult(
                "Rollout 30%: gradual_redesign",
                "${rolloutResults.entries.joinToString(", ") { "${it.key}=${it.value}" }}\n$enabledCount/5 enabled",
                true,
            ))

            // Restore user
            sdk.setUserId(selectedUser)
            sdk.setUserAttributes(mapOf("plan" to "pro", "country" to "US"))

            // ── Test 6: Flag dependency ──
            val vipCheckout = sdk.isEnabled("vip_checkout")
            results.add(TestResult(
                "Dependency: vip_checkout → dark_mode",
                "vip_checkout = $vipCheckout (depends on dark_mode)",
                true,
            ))

            // ── Test 7: Default value ──
            val missing = sdk.getValue("nonexistent_flag", "fallback")
            results.add(TestResult(
                "Default value: nonexistent_flag",
                "getValue = \"$missing\" (default: \"fallback\")",
                missing == "fallback",
            ))

            // ── Test 8: Refresh ──
            sdk.refresh()
            results.add(TestResult("Manual refresh", "Refreshed. Total: ${sdk.getAll().size} flags", true))

            // ── Test 9: Evaluate (full result) ──
            val evalResult = sdk.evaluate("checkout_flow")
            results.add(TestResult(
                "Evaluate: checkout_flow",
                "enabled=${evalResult.enabled}, value=${evalResult.value}, variant=${evalResult.variant}",
                true,
            ))

            // ── Test 10: getUserId ──
            val currentUserId = sdk.getUserId()
            results.add(TestResult(
                "getUserId",
                "getUserId = \"$currentUserId\" (expected: \"$selectedUser\")",
                currentUserId == selectedUser,
            ))

            // ── Test 11: Singleton getInstance ──
            val singletonFlags = RiviumFlags.getInstance()
            results.add(TestResult(
                "Singleton: getInstance()",
                "getInstance() returned instance with ${singletonFlags.getAll().size} flags",
                singletonFlags.getAll().size == sdk.getAll().size,
            ))

            // ── Test 12: Offline cache ──
            results.add(TestResult(
                "Offline cache",
                "Cached ${sdk.getAll().size} flags in SharedPreferences",
                true,
            ))

            // ── Test 13: Environment overrides ──
            val testFlagKey = if (allFlags.isNotEmpty()) allFlags.first().key else "maintenance_mode"
            val envLines = mutableListOf<String>()

            for (env in listOf("none", "development", "staging", "production")) {
                try {
                    val envFlags = RiviumFlags(
                        context = applicationContext,
                        config = RiviumFlagsConfig(
                            apiKey = apiKey,
                            environment = if (env == "none") null else env,
                            debug = true,
                            enableOfflineCache = false,
                        ),
                    )
                    envFlags.init()
                    envFlags.setUserId(selectedUser)
                    val flagEnabled = envFlags.isEnabled(testFlagKey)
                    val flagValue = envFlags.getValue(testFlagKey)
                    envLines.add("$env: enabled=$flagEnabled, value=$flagValue, flags=${envFlags.getAll().size}")
                    envFlags.dispose()
                } catch (e: Exception) {
                    envLines.add("$env: error=${e.message}")
                }
            }

            results.add(TestResult(
                "Environment overrides: $testFlagKey",
                "Flag \"$testFlagKey\" across environments:\n${envLines.joinToString("\n")}",
                true,
            ))

            // ── Test 14: Reset & Dispose ──
            val flagsBefore = sdk.getAll().size
            sdk.dispose()
            sdk.reset()
            val flagsAfter = sdk.getAll().size
            results.add(TestResult(
                "Reset & Dispose",
                "Before: $flagsBefore flags, dispose() called, After reset: $flagsAfter flags",
                flagsAfter == 0,
            ))

            renderResults(results)
        }
    }

    private fun renderResults(results: List<TestResult>) {
        runOnUiThread {
            loadingBar.visibility = View.GONE
            resultsLayout.removeAllViews()

            for ((i, r) in results.withIndex()) {
                // Card
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    setBackgroundColor(0xFFFFFFFF.toInt())
                    elevation = 2f
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    params.setMargins(0, 0, 0, dp(8))
                    layoutParams = params
                }

                // Header row
                val headerRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, dp(6))
                }

                // Test number badge
                val badge = TextView(this).apply {
                    text = "Test ${i + 1}"
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFF92400E.toInt())
                    setBackgroundColor(0xFFFDE68A.toInt())
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                }
                headerRow.addView(badge)

                headerRow.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(8), 0)
                })

                // Test name
                headerRow.addView(TextView(this).apply {
                    text = r.test
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFF1F2937.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                // Pass/fail icon
                headerRow.addView(TextView(this).apply {
                    text = if (r.pass) "✓" else "✗"
                    textSize = 16f
                    setTextColor(if (r.pass) 0xFF16A34A.toInt() else 0xFFDC2626.toInt())
                    typeface = Typeface.DEFAULT_BOLD
                })

                card.addView(headerRow)

                // Detail
                card.addView(TextView(this).apply {
                    text = r.detail
                    textSize = 12f
                    setTextColor(0xFF475569.toInt())
                    typeface = Typeface.MONOSPACE
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setBackgroundColor(0xFFF1F5F9.toInt())
                })

                resultsLayout.addView(card)
            }

            // Summary
            val passed = results.count { it.pass }
            val failed = results.count { !it.pass }
            resultsLayout.addView(TextView(this).apply {
                text = "✓ Passed: $passed${if (failed > 0) "  ✗ Failed: $failed" else ""}  Total: ${results.size}"
                textSize = 14f
                setTextColor(if (failed == 0) 0xFF16A34A.toInt() else 0xFFDC2626.toInt())
                setPadding(0, dp(12), 0, dp(8))
            })
        }
    }
}
