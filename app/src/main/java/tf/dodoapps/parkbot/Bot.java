package tf.dodoapps.parkbot;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.telephony.*;
import org.json.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import tf.dodoapps.parkbot.core.*;

final class Bot {
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    static volatile boolean stopRequested;
    static String time(long value) { return Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE HH:mm")); }
    static Engine engine(Context c) { return new Engine(new Storage(c), new Phone(c), System::currentTimeMillis, () -> stopRequested); }
    static boolean granted(Context c, String p) { return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }
    static boolean exact(Context c) { return Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager.class).canScheduleExactAlarms(); }
    @SuppressLint("MissingPermission")
    static List<SubscriptionInfo> sims(Context c) {
        if (!granted(c, Manifest.permission.READ_PHONE_STATE)) return Collections.emptyList();
        List<SubscriptionInfo> list = c.getSystemService(SubscriptionManager.class).getActiveSubscriptionInfoList();
        return list == null ? Collections.emptyList() : list;
    }
    static final class Storage implements Engine.Store {
        private final SharedPreferences prefs;
        Storage(Context c) { prefs = c.getSharedPreferences("parking", Context.MODE_PRIVATE); }
        public Session load() {
            Session s = new Session();
            try {
                JSONObject o = new JSONObject(prefs.getString("session", "{}"));
                s.id = o.optString("id"); s.number = o.optString("number"); s.message = o.optString("message");
                s.token = o.optString("token"); s.detail = o.optString("detail", s.detail); s.status = o.optString("status", "IDLE");
                s.simId = o.optInt("simId", -1); s.parts = o.optInt("parts"); s.sentMask = o.optInt("sentMask"); s.sentCount = o.optInt("sentCount");
                s.startAt = o.optLong("startAt"); s.stopAt = o.optLong("stopAt"); s.intervalMs = o.optLong("intervalMs");
                s.nextAt = o.optLong("nextAt"); s.submittedAt = o.optLong("submittedAt"); s.lastSentAt = o.optLong("lastSentAt");
                return s;
            } catch (JSONException e) { throw new IllegalStateException("Saved session could not be read. No SMS was sent.", e); }
        }
        public void save(Session s) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", s.id).put("number", s.number).put("message", s.message).put("token", s.token).put("detail", s.detail).put("status", s.status);
                o.put("simId", s.simId).put("parts", s.parts).put("sentMask", s.sentMask).put("sentCount", s.sentCount);
                o.put("startAt", s.startAt).put("stopAt", s.stopAt).put("intervalMs", s.intervalMs).put("nextAt", s.nextAt).put("submittedAt", s.submittedAt).put("lastSentAt", s.lastSentAt);
                if (!prefs.edit().putString("session", o.toString()).commit()) throw new IllegalStateException("Could not save session. Sending stopped.");
            } catch (JSONException e) { throw new IllegalStateException(e); }
        }
        public void event(String text) {
            try {
                JSONArray old = new JSONArray(prefs.getString("history", "[]"));
                JSONArray log = new JSONArray(); log.put(time(System.currentTimeMillis()) + "\n" + text);
                for (int i = 0; i < Math.min(99, old.length()); i++) log.put(old.getString(i));
                prefs.edit().putString("history", log.toString()).apply();
            } catch (JSONException e) { android.util.Log.e("ParkBot", "History", e); }
        }
        String history() {
            try {
                JSONArray a = new JSONArray(prefs.getString("history", "[]")); StringBuilder result = new StringBuilder();
                for (int i = 0; i < a.length(); i++) result.append(a.getString(i)).append("\n\n");
                return a.length() == 0 ? "No activity yet." : result.toString();
            } catch (JSONException e) { return "History could not be read."; }
        }
    }
    static final class Phone implements Engine.Platform {
        private final Context c;
        Phone(Context context) { c = context.getApplicationContext(); }
        public String blockingReason(int simId) {
            if (!exact(c)) return "Allow Alarms & reminders in Setup.";
            if (!granted(c, Manifest.permission.SEND_SMS)) return "Allow SMS access in Setup. If it stays blocked, check the installer's restricted-permission settings.";
            if (!granted(c, Manifest.permission.READ_PHONE_STATE)) return "Allow Phone access to select your SIM.";
            try { if (sims(c).stream().noneMatch(s -> s.getSubscriptionId() == simId)) return "The selected SIM is unavailable. Check Setup."; }
            catch (RuntimeException e) { return "Could not read the SIM. Check Phone permission and SIM settings."; }
            return null;
        }
        public int parts(String text) { return SmsMessage.calculateLength(text, false)[0]; }
        @SuppressWarnings("deprecation")
        @SuppressLint("MissingPermission")
        public void send(Session s, BooleanSupplier allowed) {
            SmsManager manager = Build.VERSION.SDK_INT >= 31 ? c.getSystemService(SmsManager.class).createForSubscriptionId(s.simId) : SmsManager.getSmsManagerForSubscriptionId(s.simId);
            ArrayList<String> parts = manager.divideMessage(s.message);
            if (parts.size() != s.parts) throw new IllegalStateException("SMS encoding changed; review the message");
            ArrayList<PendingIntent> callbacks = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                Intent intent = new Intent(c, BotReceiver.class).setAction("tf.dodoapps.parkbot.SENT")
                    .setData(Uri.parse("parkbot://sent/" + s.token + "/" + i)).putExtra("token", s.token).putExtra("part", i);
                callbacks.add(PendingIntent.getBroadcast(c, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            }
            if (!allowed.getAsBoolean()) throw new IllegalStateException("Stopped, stop time reached, or permission changed");
            if (parts.size() == 1) manager.sendTextMessage(s.number, null, parts.get(0), callbacks.get(0), null);
            else manager.sendMultipartTextMessage(s.number, null, parts, callbacks, null);
        }
        @SuppressLint("ScheduleExactAlarm")
        public void arm(Long at) {
            AlarmManager manager = c.getSystemService(AlarmManager.class);
            PendingIntent pending = PendingIntent.getBroadcast(c, 1, new Intent(c, BotReceiver.class).setAction("tf.dodoapps.parkbot.TICK"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (at == null) manager.cancel(pending);
            else manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Math.max(at, System.currentTimeMillis() + 100), pending);
            notifyState(c);
        }
    }
    @SuppressLint("MissingPermission")
    static void notifyState(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("parking", "Parking status", NotificationManager.IMPORTANCE_DEFAULT));
        if (Build.VERSION.SDK_INT >= 33 && !granted(c, Manifest.permission.POST_NOTIFICATIONS)) return;
        Session s = new Storage(c).load();
        if (s.status.equals("IDLE") || s.status.equals("STOPPED")) { nm.cancel(1); return; }
        String title = s.status.equals("FAILED") ? "ParkBot needs attention" : s.active() ? "ParkBot is running" : "Parking session finished";
        String detail = s.status.equals("SCHEDULED") ? "Next SMS " + time(s.nextAt) + " · stop " + time(s.stopAt) : s.detail;
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder n = new Notification.Builder(c, "parking").setSmallIcon(R.drawable.ic_status).setContentTitle(title)
            .setContentText(detail).setStyle(new Notification.BigTextStyle().bigText(detail)).setContentIntent(open).setOngoing(s.active())
            .setOnlyAlertOnce(!s.status.equals("FAILED")).setVisibility(Notification.VISIBILITY_PRIVATE);
        if (s.active()) {
            PendingIntent stop = PendingIntent.getBroadcast(c, 2, new Intent(c, BotReceiver.class).setAction("tf.dodoapps.parkbot.STOP"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            n.addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_status), "Stop parking", stop).build());
        }
        nm.notify(1, n.build());
    }
    static void failure(Context c, Exception e) {
        android.util.Log.e("ParkBot", "Operation failed", e);
        try { engine(c).stop("Stopped: " + e.getMessage()); } catch (Exception ignored) { }
    }
}
