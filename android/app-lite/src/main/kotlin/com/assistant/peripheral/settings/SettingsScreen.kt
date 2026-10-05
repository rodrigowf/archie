package com.assistant.peripheral.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.assistant.core.audio.ports.AvailableOutputs
import com.assistant.core.model.AudioOutput
import com.assistant.core.model.DeviceSettings
import com.assistant.core.network.DiscoveredServer
import com.assistant.core.voicehost.HostConnection
import com.assistant.peripheral.ui.GlyphDrawable
import com.assistant.peripheral.ui.Glyphs
import com.assistant.peripheral.ui.LitePalette
import com.assistant.peripheral.ui.dpi

/** Everything the Settings view shows. */
data class SettingsViewState(
    val settings: DeviceSettings,
    val connection: HostConnection,
    val serverName: String,
    val discovered: List<DiscoveredServer> = emptyList(),
    val scanning: Boolean = false,
    val scanMessage: String? = null,
    val outputs: AvailableOutputs = AvailableOutputs(bluetoothCallAudio = false, bluetoothMedia = false, wired = false),
    val accessibilityEnabled: Boolean = false,
    val versionName: String,
    val versionCode: Int,
)

/** What the Settings view asks for (the Activity forwards to [LiteSettings], the host and the system). */
interface SettingsActions {
    fun back()
    fun connect()
    fun disconnect()
    fun scan()
    fun selectServer(url: String)
    fun addServer()
    fun removeServer(label: String, url: String)
    fun openAccessibilitySettings()
    fun openAssistSettings()
    val settings: LiteSettings
}

/**
 * The lite Settings view (spec 14 §5.3): a ScrollView of hand-built rows, grouped as IA §7
 * "This device". Sliders commit on release, phrases on IME Done / focus loss.
 */
class SettingsScreen(context: Context, private val palette: LitePalette, private val actions: SettingsActions) : ScrollView(context) {
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var state: SettingsViewState? = null
    private var binding = false

    // Connection
    private val serverTitle = title()
    private val serverHelper = helper()
    private val connectButton = button("Connect")
    private val serverList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scanButton = button("Scan")
    private val addButton = button("Add server")
    private val scanHelper = helper()
    private val autoConnect = switch()

    // Audio
    private val micGain = seek(Steps.GAIN_MAX)
    private val micGainValue = value()
    private val echoDuck = seek(Steps.DUCK_MAX)
    private val echoDuckValue = value()
    private val outputGroup = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
    private val outputButtons = LinkedHashMap<AudioOutput, RadioButton>()

    // Wake word
    private val wakeEnabled = switch()
    private val wakePhrases = phraseField("wake up, hey archie")
    private val talkPhrases = phraseField("my friend")
    private val wakeSensitivity = seek(Steps.GAIN_MAX)
    private val wakeSensitivityValue = value()
    private val talkStop = seek(Steps.TALK_STOP_MAX)
    private val talkStopValue = value()

    // Triggers
    private val recents = switch()
    private val accessibilityHelper = helper()

    // About
    private val aboutVersion = title()
    private val aboutServer = helper()

    init {
        setBackgroundColor(palette.background)
        isFillViewport = true
        val side = context.dpi(16f)
        column.setPadding(side, 0, side, context.dpi(24f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Top bar: back + title.
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = ImageButton(context).apply {
            contentDescription = "Back"
            setImageDrawable(GlyphDrawable(Glyphs.BACK, palette.onSurface))
            scaleType = android.widget.ImageView.ScaleType.FIT_XY
            setPadding(0, 0, 0, 0)
            background = ripple(palette.background, round = true)
            setOnClickListener { actions.back() }
        }
        bar.addView(back, LinearLayout.LayoutParams(context.dpi(48f), context.dpi(48f)))
        bar.addView(TextView(context).apply {
            text = "Settings"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(palette.onSurface)
            setPadding(context.dpi(8f), 0, 0, 0)
        })
        column.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(64f)))

        group("Connection") {
            row(listOf(serverTitle, serverHelper), connectButton)
            add(serverList)
            add(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(context.dpi(16f), context.dpi(8f), context.dpi(16f), 0)
                addView(scanButton)
                addView(addButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = context.dpi(12f) })
            })
            add(scanHelper.apply { setPadding(context.dpi(16f), context.dpi(4f), context.dpi(16f), context.dpi(8f)) })
            row(listOf(title("Auto-connect"), helper("Connect to the server when the app starts")), autoConnect)
        }
        group("Audio") {
            sliderRow("Mic level", "Voice conversation microphone gain", micGain, micGainValue)
            sliderRow("Echo ducking", "Mic gain while Archie speaks — lower reduces echo", echoDuck, echoDuckValue)
            add(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dpi(16f), context.dpi(12f), context.dpi(16f), context.dpi(12f))
                addView(title("Output"))
                addView(helper("Where Archie's voice plays"))
                addView(outputGroup)
            })
        }
        group("Wake word") {
            row(listOf(title("Wake word"), helper("Listen for the phrases below")), wakeEnabled)
            fieldRow("Realtime conversation", "Comma-separated phrases that start a conversation", wakePhrases)
            fieldRow("Voice message", "Comma-separated phrases that record one message", talkPhrases)
            sliderRow("Wake sensitivity", "Higher = easier to trigger", wakeSensitivity, wakeSensitivityValue)
            sliderRow("Talk auto-stop", "Lower stops sooner after you pause; higher waits for a clearer pause", talkStop, talkStopValue)
        }
        group("Triggers") {
            row(listOf(title("Hold recents button"), accessibilityHelper), recents)
            add(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(context.dpi(16f), 0, context.dpi(16f), context.dpi(12f))
                addView(button("Accessibility").apply { setOnClickListener { actions.openAccessibilitySettings() } })
                addView(button("Assist app").apply { setOnClickListener { actions.openAssistSettings() } },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = context.dpi(12f) })
            })
        }
        group("About") {
            add(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dpi(16f), context.dpi(12f), context.dpi(16f), context.dpi(12f))
                addView(aboutVersion)
                addView(aboutServer)
            })
        }

        for ((out, label) in listOf(
            AudioOutput.AUTO to "Auto", AudioOutput.LOUDSPEAKER to "Speaker", AudioOutput.EARPIECE to "Earpiece",
            AudioOutput.BLUETOOTH to "Bluetooth", AudioOutput.WIRED to "Wired headphones",
        )) {
            val rb = RadioButton(context).apply {
                text = label
                id = View.generateViewId()
                setTextColor(palette.onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                buttonTintList = ColorStateList.valueOf(palette.primary)
                minHeight = context.dpi(48f)
            }
            outputButtons[out] = rb
            outputGroup.addView(rb)
        }

        // Listeners (ignored while binding).
        connectButton.setOnClickListener { if (state?.connection == HostConnection.OFFLINE) actions.connect() else actions.disconnect() }
        scanButton.setOnClickListener { actions.scan() }
        addButton.setOnClickListener { actions.addServer() }
        autoConnect.setOnCheckedChangeListener { _, v -> if (!binding) actions.settings.setAutoConnect(v) }
        wakeEnabled.setOnCheckedChangeListener { _, v -> if (!binding) actions.settings.setWakeEnabled(v) }
        recents.setOnCheckedChangeListener { _, v -> if (!binding) actions.settings.setRecentsTrigger(v) }
        outputGroup.setOnCheckedChangeListener { _, id ->
            if (!binding) outputButtons.entries.firstOrNull { it.value.id == id }?.let { actions.settings.setOutput(it.key) }
        }
        onCommit(micGain, micGainValue, Steps::gainLabel) { actions.settings.setMicGainStep(it) }
        onCommit(echoDuck, echoDuckValue, Steps::duckLabel) { actions.settings.setEchoDuckStep(it) }
        onCommit(wakeSensitivity, wakeSensitivityValue, Steps::gainLabel) { actions.settings.setWakeSensitivityStep(it) }
        onCommit(talkStop, talkStopValue, Steps::talkStopLabel) { actions.settings.setTalkStopStep(it) }
        commitOnDone(wakePhrases) { actions.settings.setWakePhrases(it) }
        commitOnDone(talkPhrases) { actions.settings.setTalkPhrases(it) }
    }

    fun bind(s: SettingsViewState) {
        state = s
        binding = true
        try {
            val d = s.settings
            serverTitle.text = s.serverName
            serverHelper.text = "${d.serverUrl} · " + when (s.connection) {
                HostConnection.CONNECTED -> "Connected"
                HostConnection.CONNECTING -> "Connecting…"
                HostConnection.OFFLINE -> "Offline"
            }
            connectButton.text = if (s.connection == HostConnection.OFFLINE) "Connect" else "Disconnect"
            bindServers(s)
            scanButton.isEnabled = !s.scanning
            scanButton.text = if (s.scanning) "Scanning…" else "Scan"
            scanHelper.text = s.scanMessage.orEmpty()
            scanHelper.visibility = if (s.scanMessage.isNullOrEmpty()) View.GONE else View.VISIBLE
            autoConnect.isChecked = d.autoConnect

            setSeek(micGain, micGainValue, Steps.gainStep(d.micGainLevel), Steps::gainLabel)
            setSeek(echoDuck, echoDuckValue, Steps.duckStep(d.echoDuckingGain), Steps::duckLabel)
            setSeek(wakeSensitivity, wakeSensitivityValue, Steps.gainStep(d.wakeWordMicGainLevel), Steps::gainLabel)
            setSeek(talkStop, talkStopValue, Steps.talkStopStep(d.talkSilenceSensitivity), Steps::talkStopLabel)
            outputButtons[d.audioOutput]?.let { if (!it.isChecked) outputGroup.check(it.id) }
            enable(outputButtons[AudioOutput.BLUETOOTH], s.outputs.bluetoothCallAudio || s.outputs.bluetoothMedia || d.audioOutput == AudioOutput.BLUETOOTH)
            enable(outputButtons[AudioOutput.WIRED], s.outputs.wired || d.audioOutput == AudioOutput.WIRED)

            wakeEnabled.isChecked = d.enableWakeWord
            if (!wakePhrases.hasFocus()) wakePhrases.setText(d.wakeWord)
            if (!talkPhrases.hasFocus()) talkPhrases.setText(d.talkWord)
            recents.isChecked = d.enableButtonTrigger
            accessibilityHelper.text = if (s.accessibilityEnabled) "Hold for 0.6 s to talk · accessibility service on"
            else "Hold for 0.6 s to talk · needs the accessibility service"

            aboutVersion.text = "Archie lite ${s.versionName} (${s.versionCode})"
            aboutServer.text = "Server ${d.serverUrl}"
        } finally {
            binding = false
        }
    }

    /** Unavailable outputs stay visible but disabled (live from [OutputAvailability]). */
    private fun enable(b: RadioButton?, on: Boolean) {
        b ?: return
        b.isEnabled = on
        b.alpha = if (on) 1f else 0.38f
    }

    private fun bindServers(s: SettingsViewState) {
        serverList.removeAllViews()
        val current = s.settings.serverUrl
        val saved = s.settings.savedServers.take(MAX_ROWS)
        saved.forEach { srv ->
            serverList.addView(serverRow(srv.label, srv.url, selected = srv.url == current, onLong = { actions.removeServer(srv.label, srv.url) }))
        }
        val savedUrls = saved.map { it.url }.toSet()
        s.discovered.filter { it.serverUrl !in savedUrls }.take(MAX_ROWS).forEach { d ->
            serverList.addView(serverRow("${d.host}:${d.port}", "Found on this network" + if (d.needsTrust) " · certificate not trusted" else "", selected = d.serverUrl == current, url = d.serverUrl))
        }
    }

    private fun serverRow(name: String, sub: String, selected: Boolean, url: String = sub, onLong: (() -> Unit)? = null): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dpi(16f), context.dpi(10f), context.dpi(16f), context.dpi(10f))
            minimumHeight = context.dpi(56f)
            background = ripple(if (selected) palette.secondaryContainer else palette.container, round = false)
            addView(title(name).apply { setTextColor(if (selected) palette.onSecondaryContainer else palette.onSurface) })
            addView(helper(sub))
            setOnClickListener { actions.selectServer(url) }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }

    private fun setSeek(bar: SeekBar, label: TextView, step: Int, fmt: (Int) -> String) {
        if (bar.tag == TRACKING) return
        bar.progress = step
        label.text = fmt(step)
    }

    private fun onCommit(bar: SeekBar, label: TextView, fmt: (Int) -> String, commit: (Int) -> Unit) {
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                label.text = fmt(p)
                if (fromUser && sb.tag != TRACKING && !binding) commit(p) // D-pad / accessibility step
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                sb.tag = TRACKING
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                sb.tag = null
                commit(sb.progress)
            }
        })
    }

    private fun commitOnDone(field: EditText, commit: (String) -> Unit) {
        field.setOnEditorActionListener { v, actionId, event ->
            val done = actionId == EditorInfo.IME_ACTION_DONE || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
            if (done) {
                commit(v.text.toString())
                v.clearFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
            }
            done
        }
        field.setOnFocusChangeListener { v, has -> if (!has) commit((v as EditText).text.toString()) }
    }

    // ── builders ──────────────────────────────────────────────────────────────────────────────

    private inner class Group(val box: LinearLayout) {
        fun add(v: View) = box.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        fun row(texts: List<TextView>, control: View) {
            val r = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dpi(16f), context.dpi(12f), context.dpi(16f), context.dpi(12f))
                minimumHeight = context.dpi(64f)
            }
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.forEach { col.addView(it) }
            r.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(control, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = context.dpi(12f) })
            add(r)
        }

        fun sliderRow(label: String, help: String, bar: SeekBar, valueText: TextView) {
            val r = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dpi(16f), context.dpi(12f), context.dpi(16f), context.dpi(4f))
            }
            val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(title(label), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(valueText)
            r.addView(head)
            r.addView(helper(help))
            r.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(48f)))
            add(r)
        }

        fun fieldRow(label: String, help: String, field: EditText) {
            val r = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dpi(16f), context.dpi(12f), context.dpi(16f), context.dpi(8f))
            }
            r.addView(title(label))
            r.addView(helper(help))
            r.addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            add(r)
        }
    }

    private fun group(name: String, block: Group.() -> Unit) {
        column.addView(TextView(context).apply {
            text = name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(palette.primary)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(context.dpi(16f), context.dpi(20f), 0, context.dpi(8f))
        })
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(palette.container); cornerRadius = context.dpi(16f).toFloat() }
            clipToOutline = true
        }
        Group(box).block()
        column.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun title(text: String = "") = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(palette.onSurface)
    }

    private fun helper(text: String = "") = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(palette.onSurfaceVariant)
    }

    private fun value() = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(palette.primary)
    }

    private fun switch() = Switch(context).apply {
        // API 21/22: the theme's colorAccent / colorControlNormal tint the Material switch.
        if (Build.VERSION.SDK_INT >= 23) {
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(palette.primary, palette.outline),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(palette.primaryContainer, palette.containerHighest),
            )
        }
    }

    private fun seek(max: Int) = SeekBar(context).apply {
        this.max = max
        progressTintList = ColorStateList.valueOf(palette.primary)
        thumbTintList = ColorStateList.valueOf(palette.primary)
        progressBackgroundTintList = ColorStateList.valueOf(palette.outlineVariant)
    }

    private fun phraseField(hint: String) = EditText(context).apply {
        this.hint = hint
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(palette.onSurface)
        setHintTextColor(palette.outline)
        backgroundTintList = ColorStateList.valueOf(palette.primary)
    }

    private fun button(label: String) = Button(context).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(palette.onSecondaryContainer)
        minHeight = context.dpi(48f)
        stateListAnimator = null
        background = ripple(palette.secondaryContainer, round = true)
        setPadding(context.dpi(20f), 0, context.dpi(20f), 0)
    }

    private fun ripple(color: Int, round: Boolean): RippleDrawable {
        val bg = GradientDrawable().apply {
            setColor(color)
            if (round) cornerRadius = context.dpi(24f).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(LitePalette.withAlpha(palette.onSurface, 0.12f)), bg, null)
    }

    private companion object {
        const val MAX_ROWS = 10
        const val TRACKING = "tracking"
    }
}
