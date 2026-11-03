package com.blutrixx.plugins.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Android forgets every alarm when the phone restarts or the app is updated. Arm them again. */
class AlarmsBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                Log.d("Alarms", "Restoring reminders after ${intent.action}")
                AlarmsScheduler.restoreAll(context)
            }
        }
    }
}
