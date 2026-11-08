package com.blutrixx.plugins.alarms

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/**
 * The alarm page: opened over the lock screen by an alarm-style reminder. The sound is the ring service's (it loops until the
 * alarm is answered), so this page only shows what to do and ends it: Done, Snooze or Dismiss. No PHP runs here.
 */
class AlarmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(color: Int, text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 18f
        b.setTextColor(Color.WHITE)
        b.background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(color) }
        b.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(12) }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun render(i: Intent) {
        val id = i.getStringExtra("id") ?: run { finish(); return }
        val title = i.getStringExtra("title") ?: "Reminder"
        val body = i.getStringExtra("body") ?: ""
        val doneUrl = i.getStringExtra("doneUrl")
        val minutes = i.getIntExtra("minutes", 0)
        val due = i.getLongExtra("due", System.currentTimeMillis())

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setBackgroundColor(Color.parseColor("#0F172A"))
        col.setPadding(dp(28), dp(72), dp(28), dp(40))

        fun text(t: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0) = TextView(this).apply {
            this.text = t; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
        }
        col.addView(text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(due)), 56f, Color.WHITE, true))
        col.addView(text(title, 28f, Color.WHITE, true, 28))
        if (body.isNotBlank()) col.addView(text(body, 17f, Color.parseColor("#CBD5E1"), false, 12))
        col.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })   // pushes the buttons down

        val cancelNotification = { AlarmRingService.stop(this, id) }
        if (!doneUrl.isNullOrBlank()) {
            col.addView(pill(Color.parseColor("#16A34A"), "Done") {
                cancelNotification()
                startActivity(AlarmsNotifier.openIntent(this, doneUrl))
                finish()
            })
        }
        if (minutes > 0) {
            col.addView(pill(Color.parseColor("#4F46E5"), "Snooze $minutes min") {
                val snooze = Intent(this, AlarmsActionReceiver::class.java)
                    .setAction(AlarmsNotifier.ACTION_SNOOZE)
                    .setData(Uri.parse("alarms://snooze/${Uri.encode(id)}"))
                    .putExtra("id", id).putExtra("title", title).putExtra("body", body)
                    .putExtra("url", i.getStringExtra("url")).putExtra("minutes", minutes)
                    .putExtra("screen", true).putExtra("doneUrl", doneUrl)
                sendBroadcast(snooze)   // the receiver silences the alarm and sets the next one
                finish()
            })
        }
        col.addView(pill(Color.parseColor("#475569"), "Dismiss") { cancelNotification(); finish() })
        setContentView(col)
    }

    @Deprecated("Back ends the alarm too: the sound must not go on with the page gone")
    override fun onBackPressed() {
        val id = intent.getStringExtra("id")
        if (id != null) AlarmRingService.stop(this, id)
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
