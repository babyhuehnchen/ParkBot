package tf.dodoapps.parkbot;

import android.app.Activity;
import android.content.*;
import android.os.PowerManager;
import android.telephony.SmsManager;

public final class BotReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction(); int code = getResultCode();
        boolean stop = "tf.dodoapps.parkbot.STOP".equals(action);
        if (stop) Bot.stopRequested = true;
        PendingResult result = goAsync();
        PowerManager.WakeLock wake = context.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ParkBot:receiver");
        wake.acquire(9000);
        Bot.IO.execute(() -> {
            try {
                if (stop) Bot.engine(context).stop("Parking stopped.");
                else if (Intent.ACTION_TIME_CHANGED.equals(action)) Bot.engine(context).clockChanged();
                else if ("tf.dodoapps.parkbot.SENT".equals(action)) {
                    String detail;
                    switch (code) {
                        case Activity.RESULT_OK: detail = "SMS sent."; break;
                        case SmsManager.RESULT_ERROR_NO_SERVICE: detail = "No mobile service."; break;
                        case SmsManager.RESULT_ERROR_RADIO_OFF: detail = "The mobile radio is off."; break;
                        case SmsManager.RESULT_ERROR_LIMIT_EXCEEDED: detail = "Android blocked sending due to its SMS limit."; break;
                        case SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED: detail = "Short-code SMS permission was denied. Check Premium SMS access in phone settings."; break;
                        case SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED: detail = "Short-code SMS is blocked. Check Premium SMS access in phone settings."; break;
                        default: detail = "SMS failed (phone error " + code + ").";
                    }
                    Bot.engine(context).sent(intent.getStringExtra("token"), intent.getIntExtra("part", -1), code == Activity.RESULT_OK, detail);
                } else Bot.engine(context).tick();
            } catch (Exception e) { Bot.failure(context, e); }
            finally {
                if (stop) Bot.stopRequested = false;
                if (wake.isHeld()) wake.release(); result.finish();
            }
        });
    }
}
