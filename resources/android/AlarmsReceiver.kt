package com.blutrixx.plugins.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Android calls this at the reminder's time, even when the app was closed. No PHP runs here. */
class AlarmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmsScheduler.ACTION_FIRE) return
        val id = intent.getStringExtra("id") ?: return
        val a = AlarmsStore.load(context, id)
        if (a == null) {
            Log.d("Alarms", "Fired for unknown reminder $id, ignored")
            return
        }
        val now = System.currentTimeMillis()
        val due = intent.getLongExtra("due", now)
        val shown = AlarmsNotifier.post(context, a, due)
        AlarmsStore.addHistory(context, a.id, a.title, due, shown, "alarm")

        // Arm the next time in the list, so a repeating reminder keeps going while the app stays closed.
        val rest = a.copy(times = a.times.filter { it > now })
        if (rest.times.isEmpty()) {
            AlarmsStore.remove(context, a.id)
        } else {
            AlarmsStore.save(context, rest)
            AlarmsScheduler.arm(context, rest)
        }
    }
}
