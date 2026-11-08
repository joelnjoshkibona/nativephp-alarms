package com.blutrixx.plugins.alarms

import android.app.Activity
import android.os.Bundle

/**
 * A tap or Done on an alarm's notification: silences the alarm, then opens the reminder's link in the app. It has no screen of its own
 * (a notification button cannot start an activity from a receiver on Android 12 and later, so this is the step in between).
 */
class AlarmOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id")
        if (id != null) AlarmRingService.stop(this, id)
        try { startActivity(AlarmsNotifier.openIntent(this, intent.getStringExtra("open"))) } catch (e: Exception) { /* app not available */ }
        finish()
    }
}
