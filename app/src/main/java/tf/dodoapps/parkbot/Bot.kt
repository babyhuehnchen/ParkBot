package tf.dodoapps.parkbot

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import tf.dodoapps.parkbot.core.Engine
import tf.dodoapps.parkbot.core.Session

internal object Bot {
    val IO = Executors.newSingleThreadExecutor()
    @Volatile var stopRequested = false

    fun time(value: Long): String = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEE HH:mm"))

    fun engine(c: Context) = Engine(Storage(c), Phone(c), System::currentTimeMillis, { stopRequested })
    fun granted(c: Context, permission: String) =
        c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun exact(c: Context) = Build.VERSION.SDK_INT < 31 ||
        requireNotNull(c.getSystemService(AlarmManager::class.java)).canScheduleExactAlarms()

    @SuppressLint("MissingPermission")
    fun sims(c: Context): List<SubscriptionInfo> {
        if (!granted(c, Manifest.permission.READ_PHONE_STATE)) return emptyList()
        return requireNotNull(c.getSystemService(SubscriptionManager::class.java))
            .activeSubscriptionInfoList ?: emptyList()
    }

    class Storage(c: Context) : Engine.Store {
        private val prefs = c.getSharedPreferences("parking", Context.MODE_PRIVATE)

        override fun load(): Session {
            try {
                val o = JSONObject(prefs.getString("session", "{}") ?: "{}")
                return Session(
                    id = o.optString("id"),
                    number = o.optString("number"),
                    message = o.optString("message"),
                    token = o.optString("token"),
                    detail = o.optString("detail", "Ready when you are."),
                    status = o.optString("status", "IDLE"),
                    simId = o.optInt("simId", -1),
                    parts = o.optInt("parts"),
                    sentMask = o.optInt("sentMask"),
                    sentCount = o.optInt("sentCount"),
                    startAt = o.optLong("startAt"),
                    stopAt = o.optLong("stopAt"),
                    intervalMs = o.optLong("intervalMs"),
                    nextAt = o.optLong("nextAt"),
                    submittedAt = o.optLong("submittedAt"),
                    lastSentAt = o.optLong("lastSentAt"),
                )
            } catch (e: JSONException) {
                throw IllegalStateException("Saved session could not be read. No SMS was sent.", e)
            }
        }

        override fun save(s: Session) {
            try {
                val o = JSONObject()
                    .put("id", s.id).put("number", s.number).put("message", s.message)
                    .put("token", s.token).put("detail", s.detail).put("status", s.status)
                    .put("simId", s.simId).put("parts", s.parts).put("sentMask", s.sentMask).put("sentCount", s.sentCount)
                    .put("startAt", s.startAt).put("stopAt", s.stopAt).put("intervalMs", s.intervalMs)
                    .put("nextAt", s.nextAt).put("submittedAt", s.submittedAt).put("lastSentAt", s.lastSentAt)
                check(prefs.edit().putString("session", o.toString()).commit()) {
                    "Could not save session. Sending stopped."
                }
            } catch (e: JSONException) {
                throw IllegalStateException(e)
            }
        }

        override fun event(text: String) {
            try {
                val old = JSONArray(prefs.getString("history", "[]") ?: "[]")
                val log = JSONArray().put(time(System.currentTimeMillis()) + "\n" + text)
                for (i in 0 until minOf(99, old.length())) log.put(old.getString(i))
                prefs.edit().putString("history", log.toString()).apply()
            } catch (e: JSONException) {
                Log.e("ParkBot", "History", e)
            }
        }

        fun history(): String = try {
            val entries = JSONArray(prefs.getString("history", "[]") ?: "[]")
            if (entries.length() == 0) "No activity yet." else buildString {
                for (i in 0 until entries.length()) append(entries.getString(i)).append("\n\n")
            }
        } catch (e: JSONException) {
            "History could not be read."
        }
    }

    class Phone(context: Context) : Engine.Platform {
        private val c = context.applicationContext

        override fun blockingReason(simId: Int): String? {
            if (!exact(c)) return "Allow Alarms & reminders in Setup."
            if (!granted(c, Manifest.permission.SEND_SMS))
                return "Allow SMS access in Setup. If it stays blocked, check the installer's restricted-permission settings."
            if (!granted(c, Manifest.permission.READ_PHONE_STATE))
                return "Allow Phone access to select your SIM."
            try {
                if (sims(c).none { it.subscriptionId == simId }) return "The selected SIM is unavailable. Check Setup."
            } catch (e: RuntimeException) {
                return "Could not read the SIM. Check Phone permission and SIM settings."
            }
            return null
        }

        override fun parts(message: String) = SmsMessage.calculateLength(message, false)[0]

        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun send(s: Session, allowed: () -> Boolean) {
            val manager = if (Build.VERSION.SDK_INT >= 31)
                requireNotNull(c.getSystemService(SmsManager::class.java)).createForSubscriptionId(s.simId)
                else SmsManager.getSmsManagerForSubscriptionId(s.simId)
            val parts = manager.divideMessage(s.message)
            check(parts.size == s.parts) { "SMS encoding changed; review the message" }
            val callbacks = ArrayList<PendingIntent>()
            for (i in parts.indices) {
                val intent = Intent(c, BotReceiver::class.java).setAction("tf.dodoapps.parkbot.SENT")
                    .setData(Uri.parse("parkbot://sent/" + s.token + "/" + i))
                    .putExtra("token", s.token).putExtra("part", i)
                callbacks.add(PendingIntent.getBroadcast(c, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            check(allowed()) { "Stopped, stop time reached, or permission changed" }
            if (parts.size == 1) manager.sendTextMessage(s.number, null, parts[0], callbacks[0], null)
            else manager.sendMultipartTextMessage(s.number, null, parts, callbacks, null)
        }

        @SuppressLint("ScheduleExactAlarm")
        override fun arm(at: Long?) {
            val manager = requireNotNull(c.getSystemService(AlarmManager::class.java))
            val pending = PendingIntent.getBroadcast(c, 1,
                Intent(c, BotReceiver::class.java).setAction("tf.dodoapps.parkbot.TICK"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (at == null) manager.cancel(pending)
            else manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                maxOf(at, System.currentTimeMillis() + 100), pending)
            val s = Storage(c).load()
            if (s.active()) notifyState(c, s)
        }

        override fun ended(s: Session) {
            // Notification problems must not prevent alarm cancellation or saving history.
            try { notifyState(c, s) }
            catch (e: RuntimeException) { Log.e("ParkBot", "End notification", e) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyState(c: Context, s: Session) {
        val nm = requireNotNull(c.getSystemService(NotificationManager::class.java))
        nm.createNotificationChannel(NotificationChannel("parking", "Parking status", NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 && !granted(c, Manifest.permission.POST_NOTIFICATIONS)) return
        if (s.status == "IDLE") { nm.cancel(1); return }
        val title = when {
            s.status == "FAILED" -> "ParkBot needs attention"
            s.status == "STOPPED" -> "Parking stopped"
            s.active() -> "ParkBot is running"
            else -> "Parking session finished"
        }
        val detail = if (s.status == "SCHEDULED") "Next SMS " + time(s.nextAt) + " · stop " + time(s.stopAt) else s.detail
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, "parking").setSmallIcon(R.drawable.ic_status).setContentTitle(title)
            .setContentText(detail).setStyle(Notification.BigTextStyle().bigText(detail)).setContentIntent(open)
            .setOngoing(s.active()).setOnlyAlertOnce(s.active()).setAutoCancel(!s.active())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (s.active()) {
            val stop = PendingIntent.getBroadcast(c, 2,
                Intent(c, BotReceiver::class.java).setAction("tf.dodoapps.parkbot.STOP"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            n.addAction(Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_status), "Stop parking", stop).build())
        }
        nm.notify(1, n.build())
    }

    fun failure(c: Context, e: Exception) {
        Log.e("ParkBot", "Operation failed", e)
        try { engine(c).stop("Stopped: " + e.message) } catch (ignored: Exception) { }
    }
}