package com.blutrixx.plugins.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

object AlarmsScheduler {
    private const val TAG = "Alarms"
    const val ACTION_FIRE = "com.blutrixx.plugins.alarms.FIRE"
    const val DEFAULT_MISSED_WINDOW_MINUTES = 360

    private fun manager(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Android 12+ lets the person switch exact alarms off ("Alarms & reminders"). Android 14+ starts with it off. */
    fun canExact(ctx: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager(ctx).canScheduleExactAlarms()

    private fun pending(ctx: Context, id: String, due: Long): PendingIntent {
        val intent = Intent(ctx, AlarmsReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("alarms://fire/${Uri.encode(id)}"))
            .putExtra("id", id)
            .putExtra("due", due)
        return PendingIntent.getBroadcast(ctx, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Arms the first time still ahead. Returns it, or null when none is left. Only one Android alarm exists per reminder. */
    fun arm(ctx: Context, a: Alarm): Long? {
        val now = System.currentTimeMillis()
        val next = a.times.firstOrNull { it > now } ?: return null
        val pi = pending(ctx, a.id, next)
        val am = manager(ctx)
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm refused for ${a.id}, using an inexact one: ${e.message}")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
        return next
    }

    fun disarm(ctx: Context, id: String) {
        val pi = pending(ctx, id, 0L)
        manager(ctx).cancel(pi)
        pi.cancel()
    }

    /**
     * After a reboot or an app update Android has forgotten every alarm. Arm them again from the store.
     * A time that passed while the phone was off still shows once (the latest one), if it is recent enough.
     */
    fun restoreAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val window = AlarmsStore.config(ctx).optInt("missedWindowMinutes", DEFAULT_MISSED_WINDOW_MINUTES) * 60_000L
        for (a in AlarmsStore.all(ctx)) {
            val missed = a.times.filter { it <= now && it >= now - window }.maxOrNull()
            if (missed != null) {
                val shown = AlarmsNotifier.post(ctx, a, missed)
                AlarmsStore.addHistory(ctx, a.id, a.title, missed, shown, "restore")
            }
            val rest = a.copy(times = a.times.filter { it > now })
            if (rest.times.isEmpty()) {
                AlarmsStore.remove(ctx, a.id)
            } else {
                AlarmsStore.save(ctx, rest)
                arm(ctx, rest)
            }
        }
    }
}
