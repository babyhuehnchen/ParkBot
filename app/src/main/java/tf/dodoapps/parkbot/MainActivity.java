package tf.dodoapps.parkbot;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import android.net.Uri;
import android.os.*;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import tf.dodoapps.parkbot.core.*;

public final class MainActivity extends AppCompatActivity {
    private static final int THEME_LIGHT = 0, THEME_DARK = 1, THEME_SYSTEM = 2;
    private int themeChoice;
    private boolean oledDark, materialColors;
    private int INK, ACCENT, MUTED, BG;

    @Override protected void attachBaseContext(Context base) {
        SharedPreferences appearance = base.getSharedPreferences("appearance", MODE_PRIVATE);
        // Preserve the former OLED and Material choices when upgrading from 1.2.0.
        if (!appearance.contains("theme_mode")) {
            int previous = appearance.getInt("theme", THEME_SYSTEM);
            appearance.edit().putInt("theme_mode", previous == 0 ? THEME_LIGHT : previous == 1 ? THEME_DARK : THEME_SYSTEM)
                .putBoolean("oled_dark", previous == 1).putBoolean("material_colors", previous == 3).remove("theme").apply();
        }
        themeChoice = appearance.getInt("theme_mode", THEME_SYSTEM);
        if (themeChoice < THEME_LIGHT || themeChoice > THEME_SYSTEM) themeChoice = THEME_SYSTEM;
        oledDark = appearance.getBoolean("oled_dark", false);
        materialColors = appearance.getBoolean("material_colors", false);
        getDelegate().setLocalNightMode(themeChoice == THEME_LIGHT ? AppCompatDelegate.MODE_NIGHT_NO
            : themeChoice == THEME_DARK ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        super.attachBaseContext(base);
    }
    private LinearLayout page;
    private ScrollView screen;
    private EditText number, message, interval;
    private Button startTime, stopTime, action, simButton;
    private CheckBox now;
    private TextView status, detail, startExplanation;
    private LocalTime start, stop;
    private SharedPreferences draft;
    private int simId = -1;
    private boolean showingActive, busy;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable update = new Runnable() { public void run() { refresh(); handler.postDelayed(this, 1000); } };
    private final DateTimeFormatter hhmm = DateTimeFormatter.ofPattern("HH:mm");

    @Override public void onCreate(Bundle state) {
        setTheme(materialColors ? R.style.AppTheme_Material : R.style.AppTheme);
        if (materialColors) DynamicColors.applyToActivityIfAvailable(this);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        // Material colors own all surfaces; OLED applies only to the standard dark palette.
        if (oledDark && dark && !materialColors) getTheme().applyStyle(R.style.ThemeOverlay_ParkBot_Oled, true);
        super.onCreate(state);
        INK = themeColor(com.google.android.material.R.attr.colorOnSurface);
        ACCENT = themeColor(androidx.appcompat.R.attr.colorPrimary);
        MUTED = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant);
        BG = themeColor(com.google.android.material.R.attr.colorSurface);
        draft = getSharedPreferences("draft", MODE_PRIVATE);
        createScreen();
        build();
        // Reattach listeners if Android restored an open time picker after rotation.
        for (String tag : new String[]{"start-picker", "stop-picker"}) {
            androidx.fragment.app.Fragment restored = getSupportFragmentManager().findFragmentByTag(tag);
            if (restored instanceof MaterialTimePicker) bindPicker((MaterialTimePicker) restored, tag.equals("start-picker"));
        }
    }
    @Override protected void onResume() {
        super.onResume(); updateSimControl(); handler.post(update);
        Bot.IO.execute(() -> { try { Bot.engine(this).tick(); } catch (Exception e) { Bot.failure(this, e); } });
    }
    @Override protected void onPause() { saveDraft(); handler.removeCallbacks(update); super.onPause(); }
    private void createScreen() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        boolean light = MaterialColors.isColorLight(BG);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(light);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(light);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        screen = new ScrollView(this);
        screen.setFillViewport(true);
        screen.setClipToPadding(true);
        screen.setBackgroundColor(BG);
        ViewCompat.setOnApplyWindowInsetsListener(screen, (view, insets) -> {
            applyScreenInsets(insets);
            return insets;
        });
        setContentView(screen);
    }
    private void applyScreenInsets(WindowInsetsCompat insets) {
        int types = WindowInsetsCompat.Type.systemBars()
            | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime();
        Insets safe = insets.getInsets(types);
        // Root insets remain available even when an AppCompat parent consumes them.
        WindowInsetsCompat root = ViewCompat.getRootWindowInsets(screen);
        if (root != null) safe = Insets.max(safe, root.getInsets(types));
        screen.setPadding(safe.left, safe.top, safe.right, safe.bottom);
    }
    private void build() {
        Session s = new Bot.Storage(this).load(); showingActive = s.active();
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(22), dp(20), dp(22), dp(28)); page.setBackgroundColor(BG);
        // Keep the inset-aware root attached when Start/Stop rebuilds the page.
        screen.removeAllViews(); screen.addView(page);
        screen.post(() -> {
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(screen);
            if (insets != null) applyScreenInsets(insets);
            ViewCompat.requestApplyInsets(screen);
        });
        add(page, text("PARKBOT", 13, ACCENT, true), 0);
        add(page, text("Parking, on repeat.", 30, INK, true), 6);
        add(page, text("Your SMS. Your timing.", 15, MUTED, false), 2);
        LinearLayout card = card();
        status = text("", 21, INK, true); detail = text("", 14, MUTED, false);
        add(card, status, 0); add(card, detail, 6);
        if (showingActive) {
            add(card, text("To " + s.number + "\n“" + s.message + "”", 16, INK, false), 16);
            add(card, text("Every " + s.intervalMs / 60_000 + " minutes · until " + Bot.time(s.stopAt) + "\n" + simLabel(s.simId), 14, MUTED, false), 12);
            action = button("Stop parking", true, () -> {
                Bot.stopRequested = true; action.setEnabled(false);
                Bot.IO.execute(() -> {
                    try { Bot.engine(this).stop("Parking stopped."); }
                    catch (Exception e) { Bot.failure(this, e); }
                    finally { Bot.stopRequested = false; runOnUiThread(() -> { if (!isDestroyed()) build(); }); }
                });
            });
            add(page, action, 22);
            add(page, text("Stopping prevents new SMS. A message already handed to your phone cannot be recalled.", 12, MUTED, false), 10);
        } else {
            number = input(draft.getString("number", ""), "Phone number or short code", false);
            number.setInputType(InputType.TYPE_CLASS_PHONE);
            message = input(draft.getString("message", ""), "Your parking SMS", true);
            interval = input(draft.getString("interval", ""), "Interval (minutes)", false); interval.setInputType(InputType.TYPE_CLASS_NUMBER);
            simId = draft.getInt("sim", -1);
            start = parseTime(draft.getString("start", ""), LocalTime.now().withSecond(0).withNano(0));
            stop = parseTime(draft.getString("stop", ""), LocalTime.now().plusHours(2).withSecond(0).withNano(0));
            LinearLayout times = card(); add(times, text("When", 18, INK, true), 0);
            now = new MaterialCheckBox(this); now.setText(R.string.start_now); now.setTextColor(INK); now.setChecked(draft.getBoolean("now", true));
            add(times, now, 5);
            LinearLayout row = new LinearLayout(this);
            // Reserve two equal slots; a single visible stop button occupies half and is centered.
            row.setWeightSum(2); row.setGravity(Gravity.CENTER_HORIZONTAL);
            startTime = button(getString(R.string.start_time_value, start.format(hhmm)), false, () -> pickTime(true));
            stopTime = button(getString(R.string.stop_time_value, stop.format(hhmm)), false, () -> pickTime(false));
            startTime.setMinHeight(dp(76)); stopTime.setMinHeight(dp(76));
            ((MaterialButton) startTime).setCornerRadius(dp(20)); ((MaterialButton) stopTime).setCornerRadius(dp(20));
            LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(0, -2, 1); startParams.setMarginEnd(dp(8));
            row.addView(startTime, startParams); row.addView(stopTime, new LinearLayout.LayoutParams(0, -2, 1)); add(times, row, 8);
            startExplanation = text(getString(R.string.start_now_explanation), 13, MUTED, false);
            add(times, startExplanation, 8);
            updateStartControl();
            now.setOnCheckedChangeListener((v, checked) -> { updateStartControl(); saveDraft(); });
            add(times, interval, 18);
            add(times, text("Seconds are ignored: 14:00:59 + 1 minute schedules the next SMS for 14:01:00.", 12, MUTED, false), 6);
            LinearLayout to = card(); add(to, text("Send to", 18, INK, true), 0);
            add(to, number, 6); add(to, button("Choose from contacts", false, this::pickContact), 10);
            add(to, text("Sending SIM", 14, MUTED, true), 18);
            simButton = button("Choose sending SIM", false, this::selectSendingSim);
            add(to, simButton, 8); updateSimControl();
            LinearLayout body = card(); add(body, text("Message", 18, INK, true), 0); add(body, message, 6);
            action = button("Start parking", true, this::prepareStart); add(page, action, 22);
            add(page, text("Start now waits for the next full minute. Renewals use the last sent minute plus your interval. Stops automatically at your stop time.", 12, MUTED, false), 10);
        }
        LinearLayout links = new LinearLayout(this);
        links.addView(button("History", false, () -> show("SMS history", new Bot.Storage(this).history())), new LinearLayout.LayoutParams(0, -2, 1));
        links.addView(button("Setup", false, this::setup), new LinearLayout.LayoutParams(0, -2, 1)); add(page, links, 20);
        add(page, text("Check the parking service’s confirmation. An SMS sent by your phone does not confirm a valid ticket.", 12, MUTED, false), 10);
        refresh();
    }
    private void refresh() {
        if (isDestroyed() || status == null) return;
        Session s = new Bot.Storage(this).load();
        if (s.active() != showingActive) { if (!showingActive) saveDraft(); build(); return; }
        String title;
        switch (s.status) {
            case "SCHEDULED": title = "Next SMS · " + Bot.time(s.nextAt); break;
            case "WAITING": title = "Sending your SMS…"; break;
            case "FAILED": title = "Needs your attention"; break;
            case "FINISHED": title = "Session complete"; break;
            case "STOPPED": title = "Parking stopped"; break;
            default: title = "Ready to park";
        }
        status.setText(title);
        detail.setText(s.detail + (s.lastSentAt > 0 ? "\nLast sent: " + Bot.time(s.lastSentAt) : "") + (s.sentCount > 0 ? "\n" + s.sentCount + " SMS sent this session" : ""));
    }
    private void saveDraft() {
        if (showingActive || number == null || now == null) return;
        draft.edit().putString("number", number.getText().toString()).putString("message", message.getText().toString())
            .putString("interval", interval.getText().toString()).putString("start", start.toString()).putString("stop", stop.toString())
            .putBoolean("now", now.isChecked()).putInt("sim", simId).apply();
    }
    private LocalTime parseTime(String raw, LocalTime fallback) { try { return LocalTime.parse(raw); } catch (Exception e) { return fallback; } }
    private void updateStartControl() {
        boolean immediate = now.isChecked();
        startTime.setVisibility(immediate ? View.GONE : View.VISIBLE);
        startTime.setText(getString(R.string.start_time_value, start.format(hhmm)));
        startTime.setContentDescription(getString(R.string.start_time_value, start.format(hhmm)));
        startExplanation.setVisibility(immediate ? View.VISIBLE : View.GONE);
    }
    private void pickTime(boolean first) {
        if (first && now.isChecked()) return;
        String tag = first ? "start-picker" : "stop-picker";
        if (getSupportFragmentManager().findFragmentByTag(tag) != null) return;
        LocalTime selected = first ? start : stop;
        MaterialTimePicker picker = new MaterialTimePicker.Builder().setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(selected.getHour()).setMinute(selected.getMinute())
            .setTitleText(first ? "Start time" : "Stop time").build();
        bindPicker(picker, first);
        picker.show(getSupportFragmentManager(), tag);
    }
    private void bindPicker(MaterialTimePicker picker, boolean first) {
        picker.addOnPositiveButtonClickListener(v -> {
            if (showingActive) return;
            if (first) { start = LocalTime.of(picker.getHour(), picker.getMinute()); updateStartControl(); }
            else { stop = LocalTime.of(picker.getHour(), picker.getMinute()); stopTime.setText(getString(R.string.stop_time_value, stop.format(hhmm))); }
            saveDraft();
        });
    }
    private void pickContact() {
        try { startActivityForResult(new Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE), 20); }
        catch (ActivityNotFoundException e) { show("No contact picker", "You can enter the number directly instead."); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 20 || result != RESULT_OK || data == null || data.getData() == null) return;
        try (Cursor c = getContentResolver().query(data.getData(), new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
            if (c != null && c.moveToFirst()) { number.setText(c.getString(0)); saveDraft(); }
        } catch (RuntimeException e) { show("Contact unavailable", "Could not read that number. Please enter it directly."); }
    }
    private void prepareStart() {
        if (busy) return;
        saveDraft();
        try {
            Engine.number(number.getText().toString());
            if (interval.getText().toString().trim().isEmpty()) throw new IllegalArgumentException("Choose an interval in minutes.");
            int minutes = Integer.parseInt(interval.getText().toString().trim());
            if (minutes < 1 || minutes > 1440) throw new IllegalArgumentException("Choose an interval of 1–1440 minutes.");
            if (message.getText().toString().trim().isEmpty()) throw new IllegalArgumentException("Enter your SMS text.");
            ArrayList<String> missing = new ArrayList<>();
            if (!Bot.granted(this, Manifest.permission.SEND_SMS)) missing.add(Manifest.permission.SEND_SMS);
            if (!Bot.granted(this, Manifest.permission.READ_PHONE_STATE)) missing.add(Manifest.permission.READ_PHONE_STATE);
            if (!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]), 10); return; }
            if (!Bot.exact(this)) {
                new MaterialAlertDialogBuilder(this).setTitle("Allow precise timing").setMessage("Enable Alarms & reminders for ParkBot, then tap Start parking again.")
                    .setPositiveButton("Open settings", (d, w) -> open(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Cancel", null).show(); return;
            }
            List<SubscriptionInfo> sims = Bot.sims(this);
            if (sims.isEmpty()) throw new IllegalArgumentException("No active SIM found. Check your phone’s SIM settings.");
            if (sims.size() == 1) { simId = sims.get(0).getSubscriptionId(); confirmStart(); }
            else if (sims.stream().anyMatch(s -> s.getSubscriptionId() == simId)) confirmStart();
            else chooseSim(sims, this::confirmStart);
        } catch (RuntimeException e) { show("Check your settings", e.getMessage()); }
    }
    private String simLabel(int id) {
        if (!Bot.granted(this, Manifest.permission.READ_PHONE_STATE)) return "Choose sending SIM";
        try {
            for (SubscriptionInfo sim : Bot.sims(this)) {
                if (sim.getSubscriptionId() == id) return "SIM " + (sim.getSimSlotIndex() + 1) + " · " + sim.getDisplayName();
            }
        } catch (RuntimeException ignored) { }
        return id < 0 ? "Choose sending SIM" : "Selected SIM unavailable · choose another";
    }
    private void updateSimControl() {
        if (showingActive || simButton == null) return;
        simButton.setText(simLabel(simId));
        simButton.setContentDescription("Sending SIM: " + simLabel(simId) + ". Tap to change.");
    }
    private void selectSendingSim() {
        if (!Bot.granted(this, Manifest.permission.READ_PHONE_STATE)) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, 12);
            return;
        }
        try {
            List<SubscriptionInfo> sims = Bot.sims(this);
            if (sims.isEmpty()) show("No SIMs available", "Check that a SIM is enabled in your phone settings.");
            else chooseSim(sims, () -> {});
        } catch (RuntimeException e) { show("SIM unavailable", e.getMessage()); }
    }
    private void chooseSim(List<SubscriptionInfo> sims, Runnable after) {
        String[] labels = sims.stream().map(s -> "SIM " + (s.getSimSlotIndex() + 1) + " · " + s.getDisplayName()).toArray(String[]::new);
        new MaterialAlertDialogBuilder(this).setTitle("Send using").setItems(labels, (d, which) -> { simId = sims.get(which).getSubscriptionId(); saveDraft(); updateSimControl(); after.run(); }).show();
    }
    private void confirmStart() {
        saveDraft();
        final String phone = number.getText().toString(), text = message.getText().toString();
        final int minutes = Integer.parseInt(interval.getText().toString().trim()), selectedSim = simId;
        final boolean immediately = now.isChecked();
        long[] window = Engine.window(ZonedDateTime.now(), start, stop, immediately);
        String sim = Bot.sims(this).stream().filter(s -> s.getSubscriptionId() == selectedSim).map(s -> "SIM " + (s.getSimSlotIndex() + 1) + " · " + s.getDisplayName()).findFirst().orElse("Selected SIM");
        new MaterialAlertDialogBuilder(this).setTitle("Start parking?")
            .setMessage("To " + phone + "\n" + sim + "\n\n" + text + "\n\nFirst SMS: " + (immediately ? "next full minute (" + Bot.time(window[0]) + ")" : Bot.time(window[0])) + "\nStop: " + Bot.time(window[1]) + "\nInterval: " + minutes + " minutes\n\nCarrier SMS charges apply. A recent ParkBot send may delay the first SMS to preserve the cooldown.")
            .setNegativeButton("Cancel", null).setPositiveButton("Start", (dialog, which) -> {
                busy = true; action.setEnabled(false);
                // Keep the reviewed date/time; only Start now follows the confirmation time.
                long first = immediately ? Engine.wholeMinuteAtOrAfter(System.currentTimeMillis()) : window[0];
                Bot.IO.execute(() -> {
                    try { Bot.engine(this).start(phone, text, selectedSim, minutes, first, window[1]); }
                    catch (Exception e) { runOnUiThread(() -> show("Could not start", e.getMessage())); }
                    finally { runOnUiThread(() -> { busy = false; if (!isDestroyed()) build(); }); }
                });
            }).show();
    }
    private void chooseTheme() {
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.theme);
        Context context = dialog.getContext();
        LinearLayout options = new LinearLayout(context);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(dp(24), dp(8), dp(24), dp(8));
        RadioGroup modes = new RadioGroup(context);
        String[] labels = getResources().getStringArray(R.array.theme_choices);
        int[] ids = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            MaterialRadioButton mode = new MaterialRadioButton(context);
            ids[i] = View.generateViewId();
            mode.setId(ids[i]); mode.setText(labels[i]); mode.setMinHeight(dp(48));
            modes.addView(mode, new RadioGroup.LayoutParams(-1, -2));
        }
        modes.check(ids[themeChoice]);
        add(options, modes, 0);
        MaterialSwitch oled = new MaterialSwitch(context);
        oled.setText(R.string.oled_dark); oled.setChecked(oledDark); oled.setMinHeight(dp(48));
        add(options, oled, 16);
        add(options, text(getString(R.string.oled_dark_description), 13, MUTED, false), 0);
        MaterialSwitch material = new MaterialSwitch(context);
        material.setText(R.string.material_colors); material.setChecked(materialColors); material.setMinHeight(dp(48));
        oled.setEnabled(!materialColors);
        material.setOnCheckedChangeListener((button, checked) -> oled.setEnabled(!checked));
        add(options, material, 12);
        add(options, text(getString(R.string.material_colors_description), 13, MUTED, false), 0);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(options);
        dialog.setView(scroll).setPositiveButton(R.string.apply_theme, (d, which) -> {
            int selected = THEME_SYSTEM;
            for (int i = 0; i < ids.length; i++) if (ids[i] == modes.getCheckedRadioButtonId()) selected = i;
            if (selected == themeChoice && oled.isChecked() == oledDark && material.isChecked() == materialColors) return;
            saveDraft();
            getSharedPreferences("appearance", MODE_PRIVATE).edit().putInt("theme_mode", selected)
                .putBoolean("oled_dark", oled.isChecked()).putBoolean("material_colors", material.isChecked()).apply();
            recreate();
        }).setNegativeButton("Cancel", null).show();
    }
    private void setup() {
        String status = "SMS: " + (Bot.granted(this, Manifest.permission.SEND_SMS) ? "allowed" : "not granted") + "\nPhone / SIM: " + (Bot.granted(this, Manifest.permission.READ_PHONE_STATE) ? "allowed" : "not granted") + "\nAlarms: " + (Bot.exact(this) ? "allowed" : "not granted");
        String[] choices = {"Grant SMS and Phone access", "Alarms & reminders", "Allow notifications", getString(R.string.theme_setting, getResources().getStringArray(R.array.theme_choices)[themeChoice]), "App / battery settings"};
        new MaterialAlertDialogBuilder(this).setTitle("Setup").setItems(choices, (d, which) -> {
            switch (which) {
                case 0: requestPermissions(new String[]{Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE}, 10); break;
                case 1: if (Build.VERSION.SDK_INT >= 31) open(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()))); else show("Alarm access", "Already available on this Android version."); break;
                case 2: if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11); else open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); break;
                case 3: chooseTheme(); break;
                case 4: open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); break;
            }
        }).setNeutralButton("Status", (d, w) -> show("Permission status", status + "\n\nIf SMS access stays blocked, the installer may need to allowlist SMS permission. Battery restrictions can delay renewal. Set ParkBot battery use to Unrestricted if your phone offers it."))
            .setNegativeButton("Done", null).show();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        updateSimControl();
        if (request == 12) {
            if (Bot.granted(this, Manifest.permission.READ_PHONE_STATE)) selectSendingSim();
            else show("Phone permission needed", "Allow Phone access to choose a sending SIM. You can grant it in Setup.");
            return;
        }
        if (request == 10) show("Permissions updated", Bot.granted(this, Manifest.permission.SEND_SMS) && Bot.granted(this, Manifest.permission.READ_PHONE_STATE) ? "Ready. Tap Start parking when you want to begin." : "SMS and Phone access are required. Check Setup → Status for details.");
    }
    private void open(Intent intent) { try { startActivity(intent); } catch (ActivityNotFoundException e) { show("Settings unavailable", "Open your phone settings and find ParkBot manually."); } }
    private void show(String title, String body) {
        if (isFinishing() || isDestroyed()) return;
        TextView value = text(body == null ? "Please try again." : body, 15, INK, false); value.setTextIsSelectable(true); value.setPadding(dp(24), dp(16), dp(24), dp(16));
        ScrollView scroll = new ScrollView(this); scroll.addView(value);
        new MaterialAlertDialogBuilder(this).setTitle(title).setView(scroll).setPositiveButton("OK", null).show();
    }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new MaterialTextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setLineSpacing(dp(3), 1); if (bold) v.setTypeface(null, Typeface.BOLD); return v;
    }
    private EditText input(String value, String hint, boolean multi) {
        TextInputEditText v = new TextInputEditText(this); v.setText(value); v.setHint(hint); v.setTextSize(16);
        v.setInputType(InputType.TYPE_CLASS_TEXT | (multi ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        if (multi) { v.setMinLines(2); v.setGravity(Gravity.TOP); } else v.setSingleLine(true);
        v.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); return v;
    }
    private int themeColor(int attribute) {
        return MaterialColors.getColor(this, attribute, "ParkBot");
    }
    private ColorStateList buttonColors(int enabled, int disabled) {
        return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{disabled, enabled});
    }
    private Button button(String title, boolean primary, Runnable click) {
        MaterialButton b = new MaterialButton(this); b.setText(title); b.setAllCaps(false); b.setTextSize(15);
        b.setMinHeight(dp(56)); b.setCornerRadius(dp(28)); b.setInsetTop(0); b.setInsetBottom(0);
        // Stateful tints keep a disabled button visibly neutral in both light and dark themes.
        b.setTextColor(buttonColors(themeColor(primary ? com.google.android.material.R.attr.colorOnPrimary : com.google.android.material.R.attr.colorOnSecondaryContainer), MaterialColors.layer(BG, INK, 0.38f)));
        b.setBackgroundTintList(buttonColors(themeColor(primary ? androidx.appcompat.R.attr.colorPrimary : com.google.android.material.R.attr.colorSecondaryContainer), MaterialColors.layer(BG, INK, 0.12f)));
        b.setOnClickListener(v -> click.run()); return b;
    }
    private LinearLayout card() {
        MaterialCardView card = new MaterialCardView(this); card.setRadius(dp(24)); card.setCardElevation(0); card.setStrokeWidth(0);
        card.setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainer));
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.addView(box, new android.widget.FrameLayout.LayoutParams(-1, -2)); add(page, card, 16); return box;
    }
    private void add(LinearLayout parent, View view, int top) {
        View child = view;
        if (view instanceof EditText) {
            TextInputLayout field = new TextInputLayout(this); field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
            field.setHint(((EditText) view).getHint()); ((EditText) view).setHint(null);
            field.addView(view, new LinearLayout.LayoutParams(-1, -2)); child = field;
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); parent.addView(child, p);
    }
}








