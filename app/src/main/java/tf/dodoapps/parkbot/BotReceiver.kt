package tf.dodoapps.parkbot

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.telephony.SmsManager

class BotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val code = resultCode
        val stop = action == "tf.dodoapps.parkbot.STOP"
        if (stop) Bot.stopRequested = true
        val result = goAsync()
        val wake = requireNotNull(context.getSystemService(PowerManager::class.java))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ParkBot:receiver")
        wake.acquire(9000)
        Bot.IO.execute {
            try {
                when {
                    stop -> Bot.engine(context).stop("Parking stopped.")
                    action == Intent.ACTION_TIME_CHANGED -> Bot.engine(context).clockChanged()
                    action == "tf.dodoapps.parkbot.SENT" -> {
                        val detail = when (code) {
                            Activity.RESULT_OK -> "SMS sent."
                            SmsManager.RESULT_ERROR_NO_SERVICE -> "No mobile service."
                            SmsManager.RESULT_ERROR_RADIO_OFF -> "The mobile radio is off."
                            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Android blocked sending due to its SMS limit."
                            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "Short-code SMS permission was denied. Check Premium SMS access in phone settings."
                            SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "Short-code SMS is blocked. Check Premium SMS access in phone settings."
                            else -> "SMS failed (phone error " + code + ")."
                        }
                        Bot.engine(context).sent(intent.getStringExtra("token"), intent.getIntExtra("part", -1),
                            code == Activity.RESULT_OK, detail)
                    }
                    else -> Bot.engine(context).tick()
                }
            } catch (e: Exception) {
                Bot.failure(context, e)
            } finally {
                if (stop) Bot.stopRequested = false
                if (wake.isHeld) wake.release()
                result.finish()
            }
        }
    }
}