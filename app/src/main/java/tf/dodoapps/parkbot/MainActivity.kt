package tf.dodoapps.parkbot

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import tf.dodoapps.parkbot.core.Engine
import com.google.android.material.R as MaterialR

class MainActivity : AppCompatActivity() {
    private companion object {
        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
        const val THEME_SYSTEM = 2
    }

    private var themeChoice = THEME_SYSTEM
    private var oledDark = false
    private var materialColors = false
    private var ink = 0
    private var accent = 0
    private var muted = 0
    private var background = 0
    private lateinit var page: LinearLayout
    private lateinit var screen: ScrollView
    private lateinit var number: EditText
    private lateinit var message: EditText
    private lateinit var interval: EditText
    private lateinit var recipientEntry: LinearLayout
    private lateinit var selectedContactCard: MaterialCardView
    private lateinit var selectedContactNameView: TextView
    private lateinit var selectedContactNumberView: TextView
    private var selectedContactName: String? = null
    private lateinit var startTime: MaterialButton
    private lateinit var stopTime: MaterialButton
    private lateinit var action: MaterialButton
    private lateinit var simButton: MaterialButton
    private lateinit var now: CheckBox
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var startExplanation: TextView
    private lateinit var start: LocalTime
    private lateinit var stop: LocalTime
    private lateinit var draft: SharedPreferences
    private var simId = -1
    private var showingActive = false
    private var busy = false
    private val handler = Handler(Looper.getMainLooper())
    private val update = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    override fun attachBaseContext(base: Context) {
        val appearance = base.getSharedPreferences("appearance", MODE_PRIVATE)
        // Preserve the former OLED and Material choices when upgrading from 1.2.0.
        if (!appearance.contains("theme_mode")) {
            val previous = appearance.getInt("theme", THEME_SYSTEM)
            appearance.edit().putInt("theme_mode", when (previous) {
                0 -> THEME_LIGHT
                1 -> THEME_DARK
                else -> THEME_SYSTEM
            }).putBoolean("oled_dark", previous == 1).putBoolean("material_colors", previous == 3)
                .remove("theme").apply()
        }
        themeChoice = appearance.getInt("theme_mode", THEME_SYSTEM)
            .takeIf { it in THEME_LIGHT..THEME_SYSTEM } ?: THEME_SYSTEM
        oledDark = appearance.getBoolean("oled_dark", false)
        materialColors = appearance.getBoolean("material_colors", false)
        delegate.localNightMode = when (themeChoice) {
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        super.attachBaseContext(base)
    }

    override fun onCreate(state: Bundle?) {
        setTheme(if (materialColors) R.style.AppTheme_Material else R.style.AppTheme)
        if (materialColors) DynamicColors.applyToActivityIfAvailable(this)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        // Material colors own all surfaces; OLED applies only to the standard dark palette.
        if (oledDark && dark && !materialColors) theme.applyStyle(R.style.ThemeOverlay_ParkBot_Oled, true)
        super.onCreate(state)
        ink = themeColor(MaterialR.attr.colorOnSurface)
        accent = themeColor(androidx.appcompat.R.attr.colorPrimary)
        muted = themeColor(MaterialR.attr.colorOnSurfaceVariant)
        background = themeColor(MaterialR.attr.colorSurface)
        draft = getSharedPreferences("draft", MODE_PRIVATE)
        createScreen()
        build()
        // Reattach listeners if Android restored an open time picker after rotation.
        for (tag in arrayOf("start-picker", "stop-picker")) {
            val restored = supportFragmentManager.findFragmentByTag(tag)
            if (restored is MaterialTimePicker) bindPicker(restored, tag == "start-picker")
        }
    }

    override fun onResume() {
        super.onResume()
        updateSimControl()
        handler.post(update)
        Bot.IO.execute {
            try { Bot.engine(this).tick() }
            catch (e: Exception) { Bot.failure(this, e) }
        }
    }

    override fun onPause() {
        saveDraft()
        handler.removeCallbacks(update)
        super.onPause()
    }

    @Suppress("DEPRECATION")
    private fun createScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val light = MaterialColors.isColorLight(background)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        window.statusBarColor = background
        window.navigationBarColor = background
        screen = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = true
            setBackgroundColor(this@MainActivity.background)
        }
        ViewCompat.setOnApplyWindowInsetsListener(screen) { _, insets ->
            applyScreenInsets(insets)
            insets
        }
        setContentView(screen)
    }

    private fun applyScreenInsets(insets: WindowInsetsCompat) {
        val types = WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
        var safe = insets.getInsets(types)
        // Root insets remain available even when an AppCompat parent consumes them.
        ViewCompat.getRootWindowInsets(screen)?.let { safe = Insets.max(safe, it.getInsets(types)) }
        screen.setPadding(safe.left, safe.top, safe.right, safe.bottom)
    }

    private fun build() {
        val s = Bot.Storage(this).load()
        showingActive = s.active()
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(28))
            setBackgroundColor(this@MainActivity.background)
        }
        // Keep the inset-aware root attached when Start/Stop rebuilds the page.
        screen.removeAllViews()
        screen.addView(page)
        screen.post {
            ViewCompat.getRootWindowInsets(screen)?.let(::applyScreenInsets)
            ViewCompat.requestApplyInsets(screen)
        }
        add(page, text("PARKBOT", 13, accent, true), 0)
        add(page, text("Parking, on repeat.", 30, ink, true), 6)
        add(page, text("Your SMS. Your timing.", 15, muted, false), 2)
        val card = card()
        status = text("", 21, ink, true)
        detail = text("", 14, muted, false)
        add(card, status, 0)
        add(card, detail, 6)
        if (showingActive) {
            add(card, text("To " + s.number + "\n“" + s.message + "”", 16, ink, false), 16)
            add(card, text("Every " + s.intervalMs / 60_000 + " minutes · until " +
                Bot.time(s.stopAt) + "\n" + simLabel(s.simId), 14, muted, false), 12)
            action = button("Stop parking", true) {
                Bot.stopRequested = true
                action.isEnabled = false
                Bot.IO.execute {
                    try { Bot.engine(this).stop("Parking stopped.") }
                    catch (e: Exception) { Bot.failure(this, e) }
                    finally {
                        Bot.stopRequested = false
                        runOnUiThread { if (!isDestroyed) build() }
                    }
                }
            }
            add(page, action, 22)
            add(page, text("Stopping prevents new SMS. A message already handed to your phone cannot be recalled.", 12, muted, false), 10)
        } else {
            buildForm()
        }
        val links = LinearLayout(this)
        links.addView(button("History", false) { show("SMS history", Bot.Storage(this).history()) },
            LinearLayout.LayoutParams(0, -2, 1f))
        links.addView(button("Setup", false, ::setup), LinearLayout.LayoutParams(0, -2, 1f))
        add(page, links, 20)
        add(page, text("Check the parking service’s confirmation. An SMS sent by your phone does not confirm a valid ticket.", 12, muted, false), 10)
        refresh()
    }

    private fun buildForm() {
        number = input(draft.getString("number", ""), "Phone number or short code", false)
        number.inputType = InputType.TYPE_CLASS_PHONE
        selectedContactName = draft.getString("contact_name", null)
        if (number.text.toString().trim().isEmpty()) selectedContactName = null
        message = input(draft.getString("message", ""), "Your parking SMS", true)
        interval = input(draft.getString("interval", ""), "Interval (minutes)", false)
        interval.inputType = InputType.TYPE_CLASS_NUMBER
        simId = draft.getInt("sim", -1)
        start = parseTime(draft.getString("start", ""), LocalTime.now().withSecond(0).withNano(0))
        stop = parseTime(draft.getString("stop", ""), LocalTime.now().plusHours(2).withSecond(0).withNano(0))
        val times = card()
        add(times, text("When", 18, ink, true), 0)
        now = MaterialCheckBox(this).apply {
            setText(R.string.start_now)
            setTextColor(ink)
            isChecked = draft.getBoolean("now", true)
        }
        add(times, now, 5)
        val row = LinearLayout(this).apply {
            // Reserve two equal slots; the lone stop button occupies half and is centered.
            weightSum = 2f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        startTime = button(getString(R.string.start_time_value, start.format(hhmm)), false) { pickTime(true) }
        stopTime = button(getString(R.string.stop_time_value, stop.format(hhmm)), false) { pickTime(false) }
        startTime.minHeight = dp(76)
        stopTime.minHeight = dp(76)
        startTime.cornerRadius = dp(20)
        stopTime.cornerRadius = dp(20)
        row.addView(startTime, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        row.addView(stopTime, LinearLayout.LayoutParams(0, -2, 1f))
        add(times, row, 8)
        startExplanation = text(getString(R.string.start_now_explanation), 13, muted, false)
        add(times, startExplanation, 8)
        updateStartControl()
        now.setOnCheckedChangeListener { _, _ -> updateStartControl(); saveDraft() }
        add(times, interval, 18)
        add(times, text("Seconds are ignored: 14:00:59 + 1 minute schedules the next SMS for 14:01:00.", 12, muted, false), 6)
        val to = card()
        add(to, text("Send to", 18, ink, true), 0)
        recipientEntry = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(recipientEntry, number, 0)
        add(recipientEntry, button("Choose from contacts", false, ::pickContact), 10)
        add(to, recipientEntry, 6)
        add(to, createSelectedContactCard(), 6)
        updateRecipientControl()
        add(to, text("Sending SIM", 14, muted, true), 18)
        simButton = button("Choose sending SIM", false, ::selectSendingSim)
        add(to, simButton, 8)
        updateSimControl()
        val body = card()
        add(body, text("Message", 18, ink, true), 0)
        add(body, message, 6)
        action = button("Start parking", true, ::prepareStart)
        add(page, action, 22)
        add(page, text("Start now waits for the next full minute. Renewals use the last sent minute plus your interval. Stops automatically at your stop time.", 12, muted, false), 10)
    }

    private fun refresh() {
        if (isDestroyed || !::status.isInitialized) return
        val s = Bot.Storage(this).load()
        if (s.active() != showingActive) {
            if (!showingActive) saveDraft()
            build()
            return
        }
        status.text = when (s.status) {
            "SCHEDULED" -> "Next SMS · " + Bot.time(s.nextAt)
            "WAITING" -> "Sending your SMS…"
            "FAILED" -> "Needs your attention"
            "FINISHED" -> "Session complete"
            "STOPPED" -> "Parking stopped"
            else -> "Ready to park"
        }
        detail.text = s.detail + (if (s.lastSentAt > 0) "\nLast sent: " + Bot.time(s.lastSentAt) else "") +
            (if (s.sentCount > 0) "\n" + s.sentCount + " SMS sent this session" else "")
    }

    private fun saveDraft() {
        if (showingActive || !::number.isInitialized || !::now.isInitialized) return
        draft.edit().putString("number", number.text.toString()).putString("contact_name", selectedContactName)
            .putString("message", message.text.toString()).putString("interval", interval.text.toString())
            .putString("start", start.toString()).putString("stop", stop.toString())
            .putBoolean("now", now.isChecked).putInt("sim", simId).apply()
    }

    private fun parseTime(raw: String?, fallback: LocalTime): LocalTime =
        try { LocalTime.parse(raw) } catch (e: Exception) { fallback }

    private fun updateStartControl() {
        val immediate = now.isChecked
        startTime.visibility = if (immediate) View.GONE else View.VISIBLE
        startTime.text = getString(R.string.start_time_value, start.format(hhmm))
        startTime.contentDescription = getString(R.string.start_time_value, start.format(hhmm))
        startExplanation.visibility = if (immediate) View.VISIBLE else View.GONE
    }

    private fun pickTime(first: Boolean) {
        if (first && now.isChecked) return
        val tag = if (first) "start-picker" else "stop-picker"
        if (supportFragmentManager.findFragmentByTag(tag) != null) return
        val selected = if (first) start else stop
        val picker = MaterialTimePicker.Builder().setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(selected.hour).setMinute(selected.minute)
            .setTitleText(if (first) "Start time" else "Stop time").build()
        bindPicker(picker, first)
        picker.show(supportFragmentManager, tag)
    }

    private fun bindPicker(picker: MaterialTimePicker, first: Boolean) {
        picker.addOnPositiveButtonClickListener {
            if (showingActive) return@addOnPositiveButtonClickListener
            if (first) {
                start = LocalTime.of(picker.hour, picker.minute)
                updateStartControl()
            } else {
                stop = LocalTime.of(picker.hour, picker.minute)
                stopTime.text = getString(R.string.stop_time_value, stop.format(hhmm))
            }
            saveDraft()
        }
    }
    private fun createSelectedContactCard(): MaterialCardView {
        selectedContactCard = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(this@MainActivity.background)
            strokeWidth = dp(1)
            setStrokeColor(themeColor(MaterialR.attr.colorOutlineVariant))
        }
        val content = LinearLayout(this).apply { setPadding(dp(16), dp(8), dp(8), dp(8)) }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), dp(8), dp(8))
        }
        selectedContactNameView = text("", 18, ink, true)
        selectedContactNumberView = text("", 15, muted, false)
        selectedContactNumberView.textDirection = View.TEXT_DIRECTION_LTR
        add(labels, selectedContactNameView, 0)
        add(labels, selectedContactNumberView, 2)
        content.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val remove = button(getString(R.string.remove_contact_symbol), false, ::clearSelectedContact).apply {
            contentDescription = getString(R.string.remove_selected_contact)
            tooltipText = getString(R.string.remove_selected_contact)
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(48)
            setPadding(0, 0, 0, 0)
            textSize = 24f
            setTextColor(ink)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        }
        content.addView(remove, LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.TOP })
        selectedContactCard.addView(content, FrameLayout.LayoutParams(-1, -2))
        return selectedContactCard
    }

    private fun updateRecipientControl() {
        val selected = selectedContactName != null
        recipientEntry.visibility = if (selected) View.GONE else View.VISIBLE
        selectedContactCard.visibility = if (selected) View.VISIBLE else View.GONE
        selectedContactNameView.text = selectedContactName ?: ""
        selectedContactNumberView.text = if (selected) number.text.toString() else ""
    }

    private fun clearSelectedContact() {
        selectedContactName = null
        number.setText("")
        updateRecipientControl()
        saveDraft()
        number.requestFocus()
    }

    @Suppress("DEPRECATION")
    private fun pickContact() {
        try {
            startActivityForResult(Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE), 20)
        } catch (e: ActivityNotFoundException) {
            show("No contact picker", "You can enter the number directly instead.")
        }
    }

    @Deprecated("Uses the existing contact picker result contract")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request != 20 || result != RESULT_OK || showingActive || !::number.isInitialized) return
        val uri = data?.data ?: return
        val contact = try {
            contentResolver.query(uri, arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ), null, null, null).use { cursor ->
                require(cursor != null && cursor.moveToFirst()) { "No contact number" }
                val phone = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                val nameColumn = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val name = if (nameColumn >= 0) cursor.getString(nameColumn) else null
                require(!phone.isNullOrBlank()) { "Empty contact number" }
                phone to name
            }
        } catch (e: RuntimeException) {
            show("Contact unavailable", "Could not read that number. Please enter it directly.")
            return
        }
        selectedContactName = contact.second?.takeIf { it.isNotBlank() } ?: getString(R.string.selected_contact)
        number.setText(contact.first)
        number.clearFocus()
        updateRecipientControl()
        WindowCompat.getInsetsController(window, screen).hide(WindowInsetsCompat.Type.ime())
        saveDraft()
    }

    private fun prepareStart() {
        if (busy) return
        saveDraft()
        try {
            Engine.number(number.text.toString())
            require(interval.text.toString().trim().isNotEmpty()) { "Choose an interval in minutes." }
            val minutes = interval.text.toString().trim().toInt()
            require(minutes in 1..1440) { "Choose an interval of 1–1440 minutes." }
            require(message.text.toString().trim().isNotEmpty()) { "Enter your SMS text." }
            val missing = mutableListOf<String>()
            if (!Bot.granted(this, Manifest.permission.SEND_SMS)) missing.add(Manifest.permission.SEND_SMS)
            if (!Bot.granted(this, Manifest.permission.READ_PHONE_STATE)) missing.add(Manifest.permission.READ_PHONE_STATE)
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), 10)
                return
            }
            if (!Bot.exact(this)) {
                MaterialAlertDialogBuilder(this).setTitle("Allow precise timing")
                    .setMessage("Enable Alarms & reminders for ParkBot, then tap Start parking again.")
                    .setPositiveButton("Open settings") { _, _ ->
                        open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + packageName)))
                    }.setNegativeButton("Cancel", null).show()
                return
            }
            val sims = Bot.sims(this)
            require(sims.isNotEmpty()) { "No active SIM found. Check your phone’s SIM settings." }
            when {
                sims.size == 1 -> { simId = sims[0].subscriptionId; confirmStart() }
                sims.any { it.subscriptionId == simId } -> confirmStart()
                else -> chooseSim(sims, ::confirmStart)
            }
        } catch (e: RuntimeException) {
            show("Check your settings", e.message)
        }
    }

    private fun simLabel(id: Int): String {
        if (!Bot.granted(this, Manifest.permission.READ_PHONE_STATE)) return "Choose sending SIM"
        try {
            for (sim in Bot.sims(this)) {
                if (sim.subscriptionId == id) return "SIM " + (sim.simSlotIndex + 1) + " · " + sim.displayName
            }
        } catch (ignored: RuntimeException) { }
        return if (id < 0) "Choose sending SIM" else "Selected SIM unavailable · choose another"
    }

    private fun updateSimControl() {
        if (showingActive || !::simButton.isInitialized) return
        simButton.text = simLabel(simId)
        simButton.contentDescription = "Sending SIM: " + simLabel(simId) + ". Tap to change."
    }

    private fun selectSendingSim() {
        if (!Bot.granted(this, Manifest.permission.READ_PHONE_STATE)) {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 12)
            return
        }
        try {
            val sims = Bot.sims(this)
            if (sims.isEmpty()) show("No SIMs available", "Check that a SIM is enabled in your phone settings.")
            else chooseSim(sims) {}
        } catch (e: RuntimeException) {
            show("SIM unavailable", e.message)
        }
    }

    private fun chooseSim(sims: List<SubscriptionInfo>, after: () -> Unit) {
        val labels = sims.map { "SIM " + (it.simSlotIndex + 1) + " · " + it.displayName }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle("Send using").setItems(labels) { _, which ->
            simId = sims[which].subscriptionId
            saveDraft()
            updateSimControl()
            after()
        }.show()
    }

    private fun confirmStart() {
        saveDraft()
        val phone = number.text.toString()
        val text = message.text.toString()
        val minutes = interval.text.toString().trim().toInt()
        val selectedSim = simId
        val immediately = now.isChecked
        val times = Engine.window(ZonedDateTime.now(), start, stop, immediately)
        val sim = Bot.sims(this).firstOrNull { it.subscriptionId == selectedSim }
            ?.let { "SIM " + (it.simSlotIndex + 1) + " · " + it.displayName } ?: "Selected SIM"
        MaterialAlertDialogBuilder(this).setTitle("Start parking?")
            .setMessage("To " + phone + "\n" + sim + "\n\n" + text + "\n\nFirst SMS: " +
                (if (immediately) "next full minute (" + Bot.time(times[0]) + ")" else Bot.time(times[0])) +
                "\nStop: " + Bot.time(times[1]) + "\nInterval: " + minutes +
                " minutes\n\nCarrier SMS charges apply. A recent ParkBot send may delay the first SMS to preserve the cooldown.")
            .setNegativeButton("Cancel", null).setPositiveButton("Start") { _, _ ->
                busy = true
                action.isEnabled = false
                // Keep the reviewed date/time; only Start now follows the confirmation time.
                val first = if (immediately) Engine.wholeMinuteAtOrAfter(System.currentTimeMillis()) else times[0]
                Bot.IO.execute {
                    try { Bot.engine(this).start(phone, text, selectedSim, minutes, first, times[1]) }
                    catch (e: Exception) { runOnUiThread { show("Could not start", e.message) } }
                    finally { runOnUiThread { busy = false; if (!isDestroyed) build() } }
                }
            }.show()
    }

    private fun chooseTheme() {
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.theme)
        val context = dialog.context
        val options = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val modes = RadioGroup(context)
        val labels = resources.getStringArray(R.array.theme_choices)
        val ids = IntArray(labels.size) { View.generateViewId() }
        for (i in labels.indices) {
            val mode = MaterialRadioButton(context).apply {
                id = ids[i]
                text = labels[i]
                minHeight = dp(48)
            }
            modes.addView(mode, RadioGroup.LayoutParams(-1, -2))
        }
        modes.check(ids[themeChoice])
        add(options, modes, 0)
        val oled = MaterialSwitch(context).apply {
            setText(R.string.oled_dark)
            isChecked = oledDark
            minHeight = dp(48)
        }
        add(options, oled, 16)
        add(options, text(getString(R.string.oled_dark_description), 13, muted, false), 0)
        val material = MaterialSwitch(context).apply {
            setText(R.string.material_colors)
            isChecked = materialColors
            minHeight = dp(48)
        }
        oled.isEnabled = !materialColors
        material.setOnCheckedChangeListener { _, checked -> oled.isEnabled = !checked }
        add(options, material, 12)
        add(options, text(getString(R.string.material_colors_description), 13, muted, false), 0)
        val scroll = ScrollView(context).apply { addView(options) }
        dialog.setView(scroll).setPositiveButton(R.string.apply_theme) { _, _ ->
            val selected = ids.indexOf(modes.checkedRadioButtonId).takeIf { it >= 0 } ?: THEME_SYSTEM
            if (selected == themeChoice && oled.isChecked == oledDark && material.isChecked == materialColors)
                return@setPositiveButton
            saveDraft()
            getSharedPreferences("appearance", MODE_PRIVATE).edit().putInt("theme_mode", selected)
                .putBoolean("oled_dark", oled.isChecked).putBoolean("material_colors", material.isChecked).apply()
            recreate()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun setup() {
        val permissionStatus = "SMS: " + (if (Bot.granted(this, Manifest.permission.SEND_SMS)) "allowed" else "not granted") +
            "\nPhone / SIM: " + (if (Bot.granted(this, Manifest.permission.READ_PHONE_STATE)) "allowed" else "not granted") +
            "\nAlarms: " + (if (Bot.exact(this)) "allowed" else "not granted")
        val choices = arrayOf("Grant SMS and Phone access", "Alarms & reminders", "Allow notifications",
            getString(R.string.theme_setting, resources.getStringArray(R.array.theme_choices)[themeChoice]), "App / battery settings")
        MaterialAlertDialogBuilder(this).setTitle("Setup").setItems(choices) { _, which ->
            when (which) {
                0 -> requestPermissions(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE), 10)
                1 -> if (Build.VERSION.SDK_INT >= 31)
                    open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + packageName)))
                    else show("Alarm access", "Already available on this Android version.")
                2 -> if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
                    else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                3 -> chooseTheme()
                4 -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
            }
        }.setNeutralButton("Status") { _, _ ->
            show("Permission status", permissionStatus +
                "\n\nIf SMS access stays blocked, the installer may need to allowlist SMS permission. Battery restrictions can delay renewal. Set ParkBot battery use to Unrestricted if your phone offers it.")
        }.setNegativeButton("Done", null).show()
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(request, permissions, grants)
        updateSimControl()
        if (request == 12) {
            if (Bot.granted(this, Manifest.permission.READ_PHONE_STATE)) selectSendingSim()
            else show("Phone permission needed", "Allow Phone access to choose a sending SIM. You can grant it in Setup.")
            return
        }
        if (request == 10) show("Permissions updated",
            if (Bot.granted(this, Manifest.permission.SEND_SMS) && Bot.granted(this, Manifest.permission.READ_PHONE_STATE))
                "Ready. Tap Start parking when you want to begin."
            else "SMS and Phone access are required. Check Setup → Status for details.")
    }

    private fun open(intent: Intent) {
        try { startActivity(intent) }
        catch (e: ActivityNotFoundException) {
            show("Settings unavailable", "Open your phone settings and find ParkBot manually.")
        }
    }

    private fun show(title: String, body: String?) {
        if (isFinishing || isDestroyed) return
        val value = text(body ?: "Please try again.", 15, ink, false).apply {
            setTextIsSelectable(true)
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        val scroll = ScrollView(this).apply { addView(value) }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(scroll).setPositiveButton("OK", null).show()
    }

    private fun dp(value: Int): Int = Math.round(resources.displayMetrics.density * value)

    private fun text(value: String, size: Int, color: Int, bold: Boolean): TextView =
        MaterialTextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            setLineSpacing(dp(3).toFloat(), 1f)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun input(value: String?, hint: String, multi: Boolean): EditText =
        TextInputEditText(this).apply {
            setText(value)
            this.hint = hint
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or (if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            if (multi) { minLines = 2; gravity = Gravity.TOP } else setSingleLine(true)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }

    private fun themeColor(attribute: Int) = MaterialColors.getColor(this, attribute, "ParkBot")

    private fun buttonColors(enabled: Int, disabled: Int) =
        ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(disabled, enabled))

    private fun button(title: String, primary: Boolean, click: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = title
            isAllCaps = false
            textSize = 15f
            minHeight = dp(56)
            cornerRadius = dp(28)
            insetTop = 0
            insetBottom = 0
            // Stateful tints keep a disabled button visibly neutral in both light and dark themes.
            setTextColor(buttonColors(themeColor(if (primary) MaterialR.attr.colorOnPrimary else MaterialR.attr.colorOnSecondaryContainer),
                MaterialColors.layer(this@MainActivity.background, ink, 0.38f)))
            backgroundTintList = buttonColors(themeColor(if (primary) androidx.appcompat.R.attr.colorPrimary else MaterialR.attr.colorSecondaryContainer),
                MaterialColors.layer(this@MainActivity.background, ink, 0.12f))
            setOnClickListener { click() }
        }

    private fun card(): LinearLayout {
        val card = MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(themeColor(MaterialR.attr.colorSurfaceContainer))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        card.addView(box, FrameLayout.LayoutParams(-1, -2))
        add(page, card, 16)
        return box
    }

    private fun add(parent: LinearLayout, view: View, top: Int) {
        val child = if (view is EditText) {
            TextInputLayout(this).apply {
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                hint = view.hint
                view.hint = null
                addView(view, LinearLayout.LayoutParams(-1, -2))
            }
        } else view
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }
}