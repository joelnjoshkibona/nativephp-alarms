package com.blutrixx.plugins.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The Snooze button on a reminder: shows it again after N minutes, as its own one-time reminder. */
class AlarmsActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        if (intent.action == AlarmsNotifier.ACTION_DISMISS) {
            AlarmRingService.stop(context, id)
            return
        }
        if (intent.action != AlarmsNotifier.ACTION_SNOOZE) return
        val minutes = intent.getIntExtra("minutes", 10)
        AlarmRingService.stop(context, id)

        val snoozeId = if (id.endsWith("~snooze")) id else "$id~snooze"
        val at = System.currentTimeMillis() + minutes * 60_000L
        val a = Alarm(snoozeId, intent.getStringExtra("title") ?: "Reminder", intent.getStringExtra("body") ?: "", intent.getStringExtra("url"), listOf(at), minutes,
            intent.getBooleanExtra("screen", false), intent.getStringExtra("doneUrl"))
        AlarmsStore.save(context, a)
        AlarmsScheduler.arm(context, a)
    }
}
