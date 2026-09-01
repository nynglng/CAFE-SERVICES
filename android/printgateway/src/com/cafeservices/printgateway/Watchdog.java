package com.cafeservices.printgateway;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * Puts the gateway back on its feet without anyone touching the tablet.
 *
 * A foreground service is meant to survive, and on stock Android it does. The
 * tablet in the cafe is not stock Android: phone makers layer their own
 * battery saving on top, and it will freeze or kill an app that has been in
 * the background a while regardless of what the service says about itself.
 * The symptom at the counter is exact - a sale prints nothing until someone
 * opens the gateway, because opening it is what brings the process back.
 *
 * Two ways back in, both cheap:
 *
 * A boot receiver, so a tablet that was turned off overnight is listening
 * again before anyone thinks to check. Without it the first sale of the day
 * silently goes nowhere.
 *
 * A repeating alarm, because an alarm is delivered to the app by the system
 * rather than run inside it - so it still arrives when the process is gone,
 * and starting the service is the first thing it does. Ten minutes is a
 * compromise: often enough that a killed gateway is back before the next
 * customer, rare enough to cost nothing. It is deliberately inexact so
 * Android can batch it with other wakeups.
 *
 * This is a safety net, not the fix. The fix is exempting the app from
 * battery optimisation on the tablet itself - see MainActivity - because a
 * gateway that has to be resurrected has already missed a print.
 */
public class Watchdog extends BroadcastReceiver {

    public static final String ACTION_CHECK = "com.cafeservices.printgateway.WATCHDOG";

    /**
     * How often to check, and it is deliberately impatient.
     *
     * This is the last line of defence: when everything else has failed and
     * the process has been frozen anyway, an alarm is still delivered, because
     * the system thaws an app to hand one over. The moment it thaws, the
     * accept loop drains whatever Loyverse left in the queue - the bytes are
     * still there, waiting - and the receipt prints by itself.
     *
     * Ten minutes was uselessly slow for someone standing at a counter. Ninety
     * seconds is the difference between a print that arrives late and a print
     * that never arrives until a person picks the phone up.
     */
    private static final long EVERY_MS = 90 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null || intent.getAction() == null
                ? "an alarm" : intent.getAction();
        if (!PrintServerService.isRunning()) {
            Log.i(PrintServerService.TAG, "watchdog: gateway was not listening, "
                    + "starting it after " + action);
            start(context);
        }
        // Re-arm every time. A repeating alarm does not survive a reboot, and
        // on some devices does not survive the app being force stopped either.
        schedule(context);
    }

    /** Start the listener, by whichever route this version of Android allows. */
    static void start(Context context) {
        Intent service = new Intent(context, PrintServerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }

    /** Arm the next check. Safe to call repeatedly; it replaces its own alarm. */
    static void schedule(Context context) {
        AlarmManager alarms =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) {
            return;
        }
        Intent check = new Intent(context, Watchdog.class).setAction(ACTION_CHECK);
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, check,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // ELAPSED_REALTIME rather than the wall clock, so changing the tablet's
        // time or crossing midnight cannot skip a check. One shot, re-armed on
        // every delivery: a repeating alarm gets batched and deferred, and a
        // deferred rescue is not a rescue.
        long at = SystemClock.elapsedRealtime() + EVERY_MS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarms.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending);
        } else {
            alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending);
        }
    }
}
