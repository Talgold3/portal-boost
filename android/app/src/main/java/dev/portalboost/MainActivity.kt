package dev.portalboost

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private enum class Tone { WAIT, BUSY, OK, WARN }
    private enum class Phase { IDLE, STARTING, WAITING, LIVE }

    private class Health(val title: TextView, val detail: TextView, val icons: Map<Tone, View>)
    private class CopyRow(val key: String, val page: String, val value: TextView, val copyIcon: View, val copied: View, val text: () -> String)

    companion object {
        private val MAIN_PROFILES = listOf("45", "55", "65", "100")
        private val EXTRA_PROFILES = listOf(Triple("35", "35", "Lighter"), Triple("200", "200", "Experimental"), Triple("off", "Off", "Stock quality"))
        private val NOTES = mapOf(
            "35" to "Lighter boost for a crowded Wi-Fi.",
            "45" to "Most stable on a busy Wi-Fi.",
            "55" to "Sharper picture with headroom to spare.",
            "65" to "Sharpest picture that stayed smooth in testing.",
            "100" to "Highest quality. Needs a clean 5 GHz channel.",
            "200" to "Experimental. Expect stutter on most Wi-Fi.",
            "off" to "Relays at stock quality, no boost.",
        )
        private const val PROXY_IP = "192.168.49.1"
    }

    private lateinit var pal: Palette
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private val refresh: () -> Unit = { render() }

    private var tab = "home"
    private lateinit var content: FrameLayout
    private val pages = mutableMapOf<String, ScrollView>()
    private val tabViews = mutableMapOf<String, Pair<ImageView, TextView>>()

    private lateinit var ring: PowerRing
    private lateinit var statusLine: TextView
    private lateinit var quality: Segmented
    private lateinit var qualityFoot: TextView
    private val health = mutableListOf<Health>()
    private val copyRows = mutableListOf<CopyRow>()
    private lateinit var eye: ImageView

    private var step = 0
    private lateinit var setupBox: LinearLayout

    private lateinit var hopPortal: TextView
    private lateinit var hopPs5: TextView
    private lateinit var hopLoss: TextView
    private val extraChecks = mutableMapOf<String, View>()
    private val extraRows = mutableListOf<View>()
    private lateinit var extraFoot: TextView
    private lateinit var ps5Detail: TextView
    private lateinit var ps5Ip: EditText
    private lateinit var logView: TextView

    private var searching = false
    private var findOnly = false
    private var searchToken = 0
    private var searchFailed = false
    private var copied: String? = null
    private var showPw = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pal = Palette((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        prefs = Settings.prefs(this)
        window.statusBarColor = pal.bg
        window.navigationBarColor = pal.bar
        if (!pal.dark) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pal.bg) }
        content = FrameLayout(this)
        pages["home"] = page(buildHome())
        pages["setup"] = page(buildSetup())
        pages["adv"] = page(buildAdvanced())
        pages.values.forEach { content.addView(it, MATCH_PARENT, MATCH_PARENT) }
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(pal.sep) }, MATCH_PARENT, 1)
        root.addView(buildTabBar(), MATCH_PARENT, dp(62))
        setContentView(root)

        showTab(if (prefs.getBoolean("onboarded", false)) "home" else "setup")
    }

    override fun onStart() { super.onStart(); LogBus.addListener(refresh) }
    override fun onStop() { LogBus.removeListener(refresh); super.onStop() }

    // ---------------------------------------------------------------- building blocks

    private fun page(child: View) = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(child)
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), 0, dp(16), dp(32))
    }

    private fun text(size: Float, color: Int, weight: Int = 400, mono: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = face(weight, mono)
        includeFontPadding = false
    }

    private fun largeTitle(title: String, logo: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(28), dp(4), dp(6))
        if (logo) {
            addView(ImageView(context).apply {
                setImageDrawable(tinted(R.drawable.ic_bolt, pal.tint)); adjustViewBounds = true; contentDescription = null
            }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(24)).apply { marginEnd = dp(10) })
        }
        addView(text(32f, pal.label, 700).apply { this.text = title; letterSpacing = -0.02f })
    }

    private fun header(title: String, top: Int = 30) = text(13f, pal.label2, 500).apply {
        text = title.uppercase()
        letterSpacing = 0.02f
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top); bottomMargin = dp(7) }
        setPadding(dp(16), 0, dp(16), 0)
    }

    private fun footer(t: String) = text(13f, pal.label2).apply {
        text = t
        setLineSpacing(0f, 1.15f)
        setPadding(dp(16), dp(8), dp(16), 0)
    }

    private fun group() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(pal.grouped, dpf(12f))
        clipToOutline = true
    }

    /** Adds [rows] to a grouped list with hairlines starting at [inset], like iOS. */
    private fun LinearLayout.rows(inset: Int, vararg rows: View) = apply {
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(pal.sep) },
                LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { marginStart = inset })
            addView(r, MATCH_PARENT, WRAP_CONTENT)
        }
    }

    private fun tile(color: Int, icon: Int, box: Int = 30, glyph: Int = 18, radius: Float = 8f) = FrameLayout(this).apply {
        background = rounded(color, dpf(radius))
        val glyphColor = if (color == Palette.YELLOW) 0xFF1A1A1A.toInt() else Color.WHITE
        addView(ImageView(context).apply { setImageDrawable(tinted(icon, glyphColor)) },
            FrameLayout.LayoutParams(dp(glyph), dp(glyph), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(box), dp(box))
    }

    private fun rowShell(onClick: (() -> Unit)? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        setPadding(dp(16), dp(9), dp(16), dp(9))
        if (onClick != null) { foreground = ripple(pal.pressed); setOnClickListener { onClick() } }
    }

    private fun textStack(title: String, detail: String?, titleFirst: Boolean = true): Triple<LinearLayout, TextView, TextView> {
        val t = text(16f, pal.label).apply { this.text = title }
        val d = text(13f, pal.label2).apply { this.text = detail ?: ""; visibility = if (detail == null) View.GONE else View.VISIBLE }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (titleFirst) { addView(t); addView(d, lp(top = 3)) } else { addView(d); addView(t, lp(top = 3)) }
        }
        return Triple(box, t, d)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun weight1() = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)

    /** A plain "label ............ value" row. */
    private fun valueRow(label: String, value: String): Pair<View, TextView> {
        val v = text(16f, pal.label2).apply { text = value }
        val row = rowShell().apply {
            addView(text(16f, pal.label).apply { text = label }, weight1())
            addView(v)
        }
        return row to v
    }

    /** A row whose value is copied on tap; shows "Copied" for a moment. */
    private fun copyRow(key: String, label: String, pageName: String, extra: View? = null, value: () -> String): View {
        val (stack, valueView, _) = textStack("", label, titleFirst = false)
        valueView.typeface = face(500, mono = true)
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        val icon = ImageView(this).apply { setImageDrawable(tinted(R.drawable.ic_copy, pal.tint)) }
        val done = text(14f, pal.green, 500).apply { text = "Copied"; visibility = View.GONE }
        val trail = FrameLayout(this).apply {
            addView(icon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER_VERTICAL or Gravity.END))
            addView(done, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END))
        }
        val row = rowShell { copy(key, label, value()) }.apply {
            addView(stack, weight1())
            if (extra != null) addView(extra)
            addView(trail, LinearLayout.LayoutParams(dp(56), WRAP_CONTENT))
            contentDescription = "$label. Tap to copy"
        }
        copyRows += CopyRow(key, pageName, valueView, icon, done, value)
        return row
    }

    private fun healthRow(color: Int, icon: Int): Pair<View, Health> {
        val (stack, t, d) = textStack("", "")
        val icons = mapOf(
            Tone.OK to ImageView(this).apply { setImageResource(R.drawable.ic_ok) },
            Tone.WARN to ImageView(this).apply { setImageResource(R.drawable.ic_warn) },
            Tone.BUSY to ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
                indeterminateTintList = ColorStateList.valueOf(pal.label2)
            },
            Tone.WAIT to text(17f, pal.label3).apply { text = "–" },
        )
        val trail = FrameLayout(this)
        icons.forEach { (tone, v) ->
            val s = if (tone == Tone.WAIT) WRAP_CONTENT else dp(if (tone == Tone.BUSY) 18 else 22)
            trail.addView(v, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
        }
        val row = rowShell().apply {
            addView(tile(color, icon))
            addView(stack, weight1().apply { marginStart = dp(12) })
            addView(trail, LinearLayout.LayoutParams(dp(24), dp(24)))
        }
        return row to Health(t, d, icons)
    }

    private fun actionRow(label: String, onClick: () -> Unit) = rowShell(onClick).apply {
        addView(text(16f, pal.tint).apply { text = label })
    }

    // ---------------------------------------------------------------- Boost (home)

    private fun buildHome(): View = column().apply {
        addView(largeTitle("Portal Boost", logo = true))

        ring = PowerRing(context, pal).apply { setOnClickListener { toggle() } }
        addView(ring, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(22)
        })
        statusLine = text(15f, pal.label2).apply { gravity = Gravity.CENTER }
        addView(statusLine, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })

        addView(header("Quality", top = 26))
        quality = Segmented(context, pal, MAIN_PROFILES) { setProfile(MAIN_PROFILES[it]) }
        addView(quality, MATCH_PARENT, WRAP_CONTENT)
        qualityFoot = footer("")
        addView(qualityFoot)

        addView(header("Connection"))
        val rows = listOf(
            healthRow(Palette.TEAL, R.drawable.ic_wifi),
            healthRow(Palette.BLUE, R.drawable.ic_gamepad),
            healthRow(Palette.YELLOW, R.drawable.ic_handheld),
            healthRow(Palette.RED, R.drawable.ic_bolt),
        )
        health.clear(); health += rows.map { it.second }
        addView(group().rows(dp(58), *rows.map { it.first }.toTypedArray()))

        addView(header("Portal Wi-Fi"))
        eye = ImageView(context).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            foreground = ripple(pal.pressed)
            setOnClickListener { showPw = !showPw; render() }
        }
        addView(group().rows(
            dp(16),
            copyRow("ssid", "Network", "home") { LogBus.status.hotspotSsid ?: ProxyService.P2P_SSID },
            copyRow("pw", "Password", "home", extra = eye) { LogBus.status.hotspotPass ?: Settings.passphrase(this@MainActivity) },
            copyRow("proxy", "Proxy (manual)", "home") { LogBus.status.proxyAddr ?: "$PROXY_IP:${ProxyService.HTTP_PROXY_PORT}" },
        ))
        addView(footer("Tap a value to copy it. Enter these on the Portal under Wi-Fi › Enter Manually."))
    }

    // ---------------------------------------------------------------- Setup (first run)

    private fun buildSetup(): View = column().apply {
        setupBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        addView(setupBox)
    }

    private fun rebuildSetup() {
        copyRows.removeAll { it.page == "setup" }
        val box = setupBox
        box.removeAllViews()
        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            contentDescription = "Step ${step + 1} of 4"
            for (i in 0..3) addView(View(context).apply { background = rounded(if (i == step) pal.label else pal.fill, dpf(4f)) },
                LinearLayout.LayoutParams(dp(7), dp(7)).apply { if (i > 0) marginStart = dp(7) })
        }
        box.addView(dots, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(40); bottomMargin = dp(26) })

        val (color, icon, title) = when (step) {
            0 -> Triple(Palette.TEAL, R.drawable.ic_wifi, "Join your home 5 GHz Wi-Fi")
            1 -> Triple(Palette.BLUE, R.drawable.ic_dial, "Set a fixed router channel")
            2 -> Triple(Palette.YELLOW, R.drawable.ic_handheld, "Connect the Portal")
            else -> Triple(Palette.RED, R.drawable.ic_bolt, "Make it automatic")
        }
        box.addView(tile(color, icon, box = 68, glyph = 34, radius = 17f))
        box.addView(text(26f, pal.label, 700).apply {
            text = title; gravity = Gravity.CENTER; letterSpacing = -0.02f
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) })
        val lead = when (step) {
            0 -> "Connect this phone to the same router as your PS5, on the 5 GHz band. Keep the PS5 wired to the router."
            1 -> "Open your router settings and set the 5 GHz band like this."
            2 -> "On the Portal, open Wi-Fi settings and choose Enter Manually. Tap a value to copy it."
            else -> "Optional. On the Portal, forget your home Wi-Fi so it always rejoins the boosted network."
        }
        box.addView(text(16f, pal.label2).apply {
            text = lead; gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10); bottomMargin = dp(22); marginStart = dp(8); marginEnd = dp(8) })

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun note(t: String) = group().rows(dp(16), rowShell().apply { addView(text(16f, pal.label).apply { text = t; setLineSpacing(0f, 1.15f) }, weight1()) })
        when (step) {
            0 -> body.addView(note("Portal Boost creates a private network for the Portal on the same channel as your home Wi-Fi."))
            1 -> {
                body.addView(group().rows(dp(16),
                    valueRow("Channel", "36, 40, 44 or 48").first,
                    valueRow("Width", "40 MHz").first,
                    valueRow("Auto channel", "Off").first))
                body.addView(footer("On Auto, the router may pick a DFS channel such as 64. The Portal network can't use DFS channels, so it lands elsewhere and lag becomes unplayable."))
            }
            2 -> body.addView(group().rows(dp(16),
                copyRow("ssid", "SSID", "setup") { ProxyService.P2P_SSID },
                valueRow("Security", "WPA2").first,
                copyRow("pw", "Password", "setup") { Settings.passphrase(this) },
                valueRow("Proxy", "Use").first,
                copyRow("proxyip", "Proxy address", "setup") { PROXY_IP },
                copyRow("port", "Port", "setup") { ProxyService.HTTP_PROXY_PORT.toString() }))
            else -> {
                body.addView(note("When Portal Boost is off, the Portal falls back to home Wi-Fi and plays at stock quality."))
                body.addView(footer("The Portal needs internet to start Remote Play. Portal Boost passes it through, so nothing else changes."))
            }
        }
        box.addView(body, MATCH_PARENT, WRAP_CONTENT)

        box.addView(text(17f, Color.WHITE, 600).apply {
            text = if (step == 3) "Done" else "Continue"
            gravity = Gravity.CENTER
            background = rounded(pal.tint, dpf(14f))
            foreground = ripple(0x33FFFFFF)
            setOnClickListener { nextStep() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(50)).apply { topMargin = dp(26) })
        box.addView(text(16f, pal.tint).apply {
            text = "Back"
            gravity = Gravity.CENTER
            visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
            setOnClickListener { step--; rebuildSetup(); pages["setup"]?.scrollTo(0, 0) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply { topMargin = dp(4) })
        render()
    }

    private fun nextStep() {
        if (step < 3) { step++; rebuildSetup(); pages["setup"]?.scrollTo(0, 0); return }
        prefs.edit().putBoolean("onboarded", true).apply()
        step = 0
        showTab("home")
    }

    // ---------------------------------------------------------------- Advanced

    private fun buildAdvanced(): View = column().apply {
        addView(largeTitle("Advanced", logo = false))

        addView(header("Latency", top = 16))
        val (r1, v1) = valueRow("Portal → phone", "–")
        val (r2, v2) = valueRow("Phone → PS5", "–")
        val (r3, v3) = valueRow("Video loss (PS5 → phone)", "–")
        hopPortal = v1; hopPs5 = v2; hopLoss = v3
        addView(group().rows(dp(16), r1, r2, r3))
        addView(footer("Round-trip ping on each Wi-Fi hop. The picture crosses both, which adds about 4–7 ms compared with a direct connection. " +
            "Video loss counts stream packets that never reached the phone; a few per thousand is normal, bursts mean the home Wi-Fi hop is the problem."))

        addView(header("More profiles"))
        val rows = EXTRA_PROFILES.map { (key, label, detail) ->
            val check = ImageView(context).apply { setImageDrawable(tinted(R.drawable.ic_check, pal.tint)) }
            extraChecks[key] = check
            rowShell { setProfile(key) }.apply {
                addView(textStack(label, detail).first, weight1())
                addView(check, LinearLayout.LayoutParams(dp(20), dp(20)))
            }
        }
        extraRows.clear(); extraRows += rows
        addView(group().rows(dp(16), *rows.toTypedArray()))
        extraFoot = footer("")
        addView(extraFoot)

        addView(header("PS5"))
        val (ps5Stack, _, d) = textStack("PS5", "")
        ps5Detail = d
        ps5Ip = EditText(context).apply {
            background = null
            setPadding(0, 0, 0, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(pal.label)
            setHintTextColor(pal.label3)
            hint = "Found automatically"
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            setText(prefs.getString("ps5", ""))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { prefs.edit().putString("ps5", s.toString().trim()).apply() }
            })
        }
        addView(group().rows(
            dp(16),
            rowShell().apply { addView(ps5Stack, weight1()) },
            rowShell().apply {
                addView(text(16f, pal.label).apply { text = "Address" })
                addView(ps5Ip, weight1().apply { marginStart = dp(12) })
            },
            actionRow("Find PS5 now") { findNow() },
        ))
        addView(footer("Portal Boost finds the PS5 when you start. Enter an address only if it can't."))

        addView(header("Log"))
        logView = text(11.5f, pal.label2, 500, mono = true).apply {
            setBackgroundColor(pal.log)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setLineSpacing(0f, 1.3f)
            setTextIsSelectable(true)
        }
        addView(group().rows(0, logView, actionRow("Copy Log") { copy("log", "Log", LogBus.text()) }, actionRow("Clear Log") { LogBus.clear() }))

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        addView(text(13f, pal.label3).apply { text = "Portal Boost $version"; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(24) })
    }

    // ---------------------------------------------------------------- tab bar

    private fun buildTabBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(pal.bar)
        listOf(Triple("home", "Boost", R.drawable.ic_bolt), Triple("setup", "Setup", R.drawable.ic_list), Triple("adv", "Advanced", R.drawable.ic_sliders))
            .forEach { (key, label, icon) ->
                val iv = ImageView(context).apply { setImageDrawable(getDrawable(icon)!!.mutate()) }
                val tv = text(10.5f, pal.label3, 500).apply { text = label }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    foreground = ripple(pal.pressed)
                    addView(iv, LinearLayout.LayoutParams(dp(24), dp(24)))
                    addView(tv, lp(top = 4))
                    setOnClickListener { showTab(key) }
                }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                tabViews[key] = iv to tv
            }
    }

    private fun showTab(t: String) {
        tab = t
        pages.forEach { (k, v) -> v.visibility = if (k == t) View.VISIBLE else View.GONE }
        tabViews.forEach { (k, v) ->
            val c = if (k == t) pal.tint else if (pal.dark) pal.label3 else pal.label2
            v.first.drawable.setTint(c); v.second.setTextColor(c)
            v.first.parent.let { (it as View).contentDescription = v.second.text.toString() + if (k == t) ", selected" else "" }
        }
        if (t == "setup") rebuildSetup() else render()
    }

    // ---------------------------------------------------------------- state → screen

    private fun currentProfile(): String = prefs.getString("profile", "65") ?: "65"

    private fun render() {
        if (!::logView.isInitialized) return
        val s = LogBus.status
        val profile = if (s.running) s.profile else currentProfile()
        val phase = when {
            s.running && s.portalSeen -> Phase.LIVE
            s.running && s.hotspotSsid != null -> Phase.WAITING
            s.running || (searching && !findOnly) -> Phase.STARTING
            else -> Phase.IDLE
        }
        val locked = s.running || (searching && !findOnly)
        // Android only honours the low-latency Wi-Fi lock while the app is visible with the screen on.
        if (s.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val dfs = s.running && s.homeFreq in 5260..5720
        val mismatch = s.running && s.groupFreq > 0 && s.homeFreq > 0 && s.groupFreq != s.homeFreq
        val latency = if (s.portalMs != null && s.ps5Ms != null) Math.round(s.portalMs + s.ps5Ms) else null

        // Ring + status line
        when (phase) {
            Phase.IDLE -> ring.set(PowerRing.Mode.IDLE, 0f, "", "", "Start")
            Phase.STARTING -> ring.set(PowerRing.Mode.BUSY, 0f, "", "Tap to cancel", "Starting")
            Phase.WAITING -> ring.set(PowerRing.Mode.BUSY, 0f, "", "Tap to stop", "Waiting")
            Phase.LIVE -> ring.set(PowerRing.Mode.LIVE, (s.mbps / 100.0).toFloat(), "%.0f".format(s.mbps), "Mbps", "")
        }
        val warn = dfs || mismatch || (phase == Phase.IDLE && searchFailed)
        statusLine.setTextColor(if (warn) pal.orange else pal.label2)
        statusLine.text = when {
            dfs || mismatch -> "Router channel needs attention"
            phase == Phase.IDLE && searchFailed -> "No PS5 found. Is it on, or in rest mode?"
            phase == Phase.IDLE -> "Ready when you are"
            phase == Phase.STARTING && !s.running -> "Looking for your PS5…"
            phase == Phase.STARTING -> "Creating the Portal network…"
            phase == Phase.WAITING -> "Waiting for the Portal to connect"
            latency != null -> "Boosting · $latency ms"
            else -> "Boosting · profile $profile"
        }

        // Quality
        quality.select(MAIN_PROFILES.indexOf(profile))
        quality.locked = locked
        if (locked) {
            qualityFoot.text = "Stop boosting to change quality. The Portal keeps its old setting until a new session starts."
            qualityFoot.setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(R.drawable.ic_lock, pal.label2).apply { setBounds(0, 0, dp(14), dp(14)) }, null, null, null)
            qualityFoot.compoundDrawablePadding = dp(6)
        } else {
            val prefix = when {
                profile !in MAIN_PROFILES -> "Using ${if (profile == "off") "Off" else "profile $profile"} from Advanced. "
                profile == "65" -> "Recommended. "
                else -> ""
            }
            qualityFoot.text = prefix + NOTES[profile]
            qualityFoot.setCompoundDrawablesRelative(null, null, null, null)
        }

        // Connection checks
        val savedName = prefs.getString("ps5Name", null)
        setHealth(0, "Router channel", when {
            !s.running -> Tone.WAIT to "Checked when you start"
            dfs -> Tone.WARN to "On DFS ${channel(s.homeFreq)}. Set 5 GHz to 36, 40, 44 or 48, not Auto."
            mismatch -> Tone.WARN to "The Portal network landed on ${channel(s.groupFreq)}. Expect lag."
            s.homeFreq <= 0 -> Tone.BUSY to "Reading home Wi-Fi…"
            else -> Tone.OK to "${channel(s.homeFreq).replaceFirstChar { it.uppercase() }} · ${band(s.homeFreq)}"
        })
        setHealth(1, if (s.running) s.ps5Name ?: "PS5" else savedName ?: "PS5", when {
            searching -> Tone.BUSY to "Searching the home network…"
            searchFailed && !s.running -> Tone.WARN to "Not found. Turn it on, or set its address in Advanced."
            s.running && s.ps5Found -> Tone.OK to "Found at ${s.ps5Ip}"
            s.running -> Tone.BUSY to "Waiting for an answer from ${s.ps5Ip}"
            else -> Tone.WAIT to "Found automatically when you start"
        })
        setHealth(2, "Portal", when {
            s.portalSeen -> Tone.OK to "Connected"
            s.running && s.hotspotSsid != null -> Tone.BUSY to "Start Remote Play on the Portal"
            else -> Tone.WAIT to "Not connected"
        })
        setHealth(3, "Boost", when {
            profile == "off" -> Tone.WAIT to "Off · stock quality"
            s.boost == LogBus.Boost.APPLIED -> Tone.OK to "Applied · profile $profile"
            s.boost == LogBus.Boost.NOT_APPLIED -> Tone.WARN to "Not applied. Close Remote Play on the Portal and connect again."
            else -> Tone.WAIT to "Applied when Remote Play starts"
        })

        // Copy rows
        copyRows.forEach { r ->
            val v = r.text()
            r.value.text = when (r.key) {
                "pw" -> if (r.page == "home" && !showPw) "•".repeat(12) else v
                "proxy" -> v.replace(":", " : ")
                else -> v
            }
            r.copyIcon.visibility = if (copied == r.key) View.GONE else View.VISIBLE
            r.copied.visibility = if (copied == r.key) View.VISIBLE else View.GONE
        }
        eye.setImageDrawable(tinted(if (showPw) R.drawable.ic_eye_off else R.drawable.ic_eye, pal.label2))
        eye.contentDescription = if (showPw) "Hide password" else "Show password"

        // Advanced
        hopPortal.text = s.portalMs?.let { "%.0f ms".format(it) } ?: "–"
        hopPs5.text = s.ps5Ms?.let { "%.0f ms".format(it) } ?: "–"
        hopLoss.text = s.lossPct?.let { "%.1f %%".format(it) } ?: "–"
        extraChecks.forEach { (k, v) -> v.visibility = if (k == profile) View.VISIBLE else View.INVISIBLE }
        extraRows.forEach { it.isEnabled = !locked; it.alpha = if (locked) 0.45f else 1f }
        extraFoot.text = if (locked) "Stop boosting to change quality." else "Choosing one of these replaces the quality on the Boost tab."
        val ip = s.ps5Ip ?: prefs.getString("ps5", "")?.takeIf { it.isNotEmpty() }
        ps5Detail.text = when {
            searching -> "Searching…"
            ip != null -> listOfNotNull(if (s.running) s.ps5Name else savedName, ip).joinToString(" · ")
            else -> "Not found yet"
        }
        if (prefs.getString("ps5", "") != ps5Ip.text.toString() && !ps5Ip.hasFocus()) ps5Ip.setText(prefs.getString("ps5", ""))
        ps5Ip.isEnabled = !locked
        if (tab == "adv") logView.text = LogBus.tail(80).ifEmpty { "Nothing logged yet." }
    }

    private fun setHealth(i: Int, title: String, state: Pair<Tone, String>) {
        val h = health[i]
        h.title.text = title
        h.detail.text = state.second
        h.detail.setTextColor(if (state.first == Tone.WARN) pal.orange else pal.label2)
        h.icons.forEach { (tone, v) -> v.visibility = if (tone == state.first) View.VISIBLE else View.GONE }
    }

    private fun channel(f: Int) = when {
        f in 2412..2484 -> "channel ${(f - 2407) / 5}"
        f in 4900..5900 -> "channel ${(f - 5000) / 5}"
        f > 5900 -> "a 6 GHz channel"
        else -> "an unknown channel"
    }

    private fun band(f: Int) = if (f < 3000) "2.4 GHz" else if (f < 5900) "5 GHz" else "6 GHz"

    // ---------------------------------------------------------------- actions

    private fun setProfile(p: String) {
        if (LogBus.status.running || (searching && !findOnly)) return
        prefs.edit().putString("profile", p).apply()
        render()
    }

    private fun copy(key: String, label: String, value: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
        copied = key
        render()
        ui.removeCallbacksAndMessages("copied")
        ui.postAtTime({ copied = null; render() }, "copied", android.os.SystemClock.uptimeMillis() + 1500)
    }

    private fun toggle() {
        if (LogBus.status.running) {
            startService(Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_STOP))
            return
        }
        if (searching) { searchToken++; searching = false; render(); return }
        val missing = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), 1); return }
        locateThen(launch = true)
    }

    private fun findNow() {
        if (LogBus.status.running || searching) return
        locateThen(launch = false)
    }

    /** Finds the PS5 off the UI thread (saved address first, then broadcast), then optionally starts boosting. */
    private fun locateThen(launch: Boolean) {
        searching = true; findOnly = !launch; searchFailed = false
        render()
        val token = ++searchToken
        val saved = prefs.getString("ps5", "")
        thread(name = "find-ps5") {
            val found = Ps5Finder.locate(this, saved)
            runOnUiThread {
                if (token != searchToken) return@runOnUiThread
                searching = false
                if (found == null) {
                    searchFailed = true
                    LogBus.log("no PS5 answered. Is it on (or in rest mode with network enabled) and on the same Wi-Fi?")
                    render(); return@runOnUiThread
                }
                prefs.edit().putString("ps5", found.ip).putString("ps5Name", found.name).apply()
                if (!ps5Ip.hasFocus()) ps5Ip.setText(found.ip)
                if (launch) start(found) else render()
            }
        }
    }

    private fun start(ps5: Ps5Finder.Found) {
        LogBus.clear()
        startForegroundService(
            Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_START)
                .putExtra(ProxyService.EXTRA_PS5, ps5.ip)
                .putExtra(ProxyService.EXTRA_PS5_NAME, ps5.name)
                .putExtra(ProxyService.EXTRA_PROFILE, currentProfile()),
        )
    }

    private fun neededPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val denied = permissions.filterIndexed { i, p ->
            grantResults[i] != PackageManager.PERMISSION_GRANTED && p != Manifest.permission.POST_NOTIFICATIONS
        }
        if (denied.isNotEmpty()) {
            searchFailed = false
            statusLine.text = "Portal Boost needs the Nearby devices permission to create the Portal network."
            statusLine.setTextColor(pal.orange)
            return
        }
        locateThen(launch = true)
    }
}
