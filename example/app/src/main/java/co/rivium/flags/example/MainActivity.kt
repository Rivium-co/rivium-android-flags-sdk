package co.rivium.flags.example

import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import co.rivium.flags.RiviumFlags
import co.rivium.flags.RiviumFlagsConfig
import co.rivium.flags.RiviumFlagsError
import co.rivium.flags.RiviumFlagsListener
import kotlinx.coroutines.launch

// Rivium Flags — Android example (SDK 0.2.0)
//
// Put your public project key below (Rivium Console → Flags → SDK keys). Never put a server secret in an app.
// In a real app create one RiviumFlags instance (e.g. in Application.onCreate) and share it.

class MainActivity : AppCompatActivity() {

    private lateinit var flags: RiviumFlags
    private lateinit var status: TextView
    private lateinit var results: LinearLayout

    private val listener = object : RiviumFlagsListener {
        // Called on the main thread.
        override fun onReady() = render("ready")
        override fun onUpdate(changedKeys: Set<String>) = render("updated: ${changedKeys.sorted().joinToString()}")
        override fun onError(error: RiviumFlagsError) = render(error.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        flags = RiviumFlags(
            applicationContext,
            RiviumFlagsConfig(apiKey = "YOUR_API_KEY", environment = "production", debug = true),
        )
        flags.addListener(listener)
        flags.start()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply { text = "Rivium Flags"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD })
        status = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        root.addView(status)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("user-1", "user-2").forEach { id ->
            buttons.addView(Button(this).apply {
                text = id
                setOnClickListener { flags.identify(id, mapOf("plan" to "pro", "country" to "AM")) }
            })
        }
        buttons.addView(Button(this).apply { text = "Sign out"; setOnClickListener { flags.reset(); render("signed out") } })
        buttons.addView(Button(this).apply {
            text = "Refresh"
            setOnClickListener { lifecycleScope.launch { val ok = flags.refresh(); render(if (ok) "refreshed" else "refresh failed") } }
        })
        root.addView(buttons)

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results)
        setContentView(ScrollView(this).apply { addView(root) })
        render("starting…")
    }

    override fun onDestroy() {
        flags.close()
        super.onDestroy()
    }

    private fun render(event: String) {
        status.text = buildString {
            appendLine("User: ${flags.userId ?: "signed out"}")
            appendLine("Anonymous id: ${flags.anonymousId.take(8)}…")
            appendLine("Last event: $event")
            appendLine("isEnabled(new-checkout): ${flags.isEnabled("new-checkout")}")
            val theme = flags.getStringDetail("theme", "light")
            append("theme: ${theme.value} (${theme.reason})")
        }
        results.removeAllViews()
        flags.getAll().values.sortedBy { it.key }.forEach { r ->
            results.addView(TextView(this).apply {
                text = "${r.key}  ·  ${r.valueType}  ·  ${if (r.enabled) "on" else "off"}  ·  ${r.reason}" +
                    (r.variant?.let { "  ·  $it" } ?: "") + "\n   value = ${r.value}"
                setPadding(0, dp(6), 0, dp(6))
            })
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
