package com.blutrixx.plugins.alarms

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * Rings an alarm-style reminder: the alarm ringtone on the alarm volume, looped, with vibration, until the person answers (Done, Snooze,
 * Dismiss, a tap, the back key on the alarm page) or three minutes pass. It is a foreground service so Android keeps the process alive
 * while it rings. The notification's own "repeat" flag is not used: Android 10 and some makers' versions (Transsion's HiOS) play it once.
 */
class AlarmRingService : Service() {
    private var player: MediaPlayer? = null
    private var ringingId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { Log.d(TAG, "Nobody answered $ringingId, stopping"); finishRing(true) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val i = intent
        val id = i?.getStringExtra("id")
        if (i == null || id == null) { stopSelf(); return START_NOT_STICKY }
        val a = Alarm(id, i.getStringExtra("title") ?: "Reminder", i.getStringExtra("body") ?: "", i.getStringExtra("url"), emptyList(),
            i.getIntExtra("minutes", 0), true, i.getStringExtra("doneUrl"))
        val due = i.getLongExtra("due", System.currentTimeMillis())

        // A second alarm while one rings: the first one's notification goes, the new one rings.
        val old = ringingId
        if (old != null && old != id) (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(old.hashCode())
        stopSound()

        val n = AlarmsNotifier.build(this, a, due, ring = true)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(id.hashCode(), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(id.hashCode(), n)
        } catch (e: Exception) {
            // Android refused a foreground service here: fall back to the notification that plays its own sound.
            Log.w(TAG, "Foreground service refused: ${e.message}")
            AppLogBridge.w('W', "the alarm sound service could not start (${e.javaClass.simpleName}), using the notification's own sound")
            running = null
            AlarmsNotifier.postPlain(this, a, due)
            stopSelf()
            return START_NOT_STICKY
        }
        running = this
        ringingId = id
        startSound()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, RING_MS)
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val p = MediaPlayer()
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            p.setDataSource(this, uri)
            p.isLooping = true
            p.setOnErrorListener { _, what, extra -> Log.w(TAG, "Alarm sound error $what/$extra"); true }
            p.prepare()
            p.start()
            player = p
        } catch (e: Exception) {
            Log.w(TAG, "Alarm sound could not start: ${e.message}")
            AppLogBridge.w('W', "the alarm sound could not start (${e.javaClass.simpleName})")
        }
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val pattern = longArrayOf(0, 600, 400, 600, 400, 600, 1500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createWaveform(pattern, 0), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            else v.vibrate(pattern, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun stopSound() {
        try { player?.let { if (it.isPlaying) it.stop(); it.release() } } catch (e: Exception) { Log.w(TAG, "Stopping the sound: ${e.message}") }
        player = null
        try { (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel() } catch (e: Exception) { /* nothing to cancel */ }
    }

    /** End the ring: silence, take the notification away, stop the service. */
    fun finishRing(removeNotification: Boolean) {
        handler.removeCallbacks(timeout)
        stopSound()
        val id = ringingId
        ringingId = null
        if (running === this) running = null
        stopForeground(true)
        if (removeNotification && id != null) (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id.hashCode())
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        stopSound()
        if (running === this) running = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Alarms"
        /** How long an unanswered alarm rings. */
        private const val RING_MS = 3 * 60_000L
        @Volatile private var running: AlarmRingService? = null

        /** The id that rings now, or null. */
        fun ringing(): String? = running?.ringingId

        fun start(ctx: Context, a: Alarm, due: Long) {
            val i = Intent(ctx, AlarmRingService::class.java)
                .putExtra("id", a.id).putExtra("title", a.title).putExtra("body", a.body).putExtra("url", a.url)
                .putExtra("doneUrl", a.doneUrl).putExtra("minutes", a.snoozeMinutes).putExtra("due", due)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        /** The person answered: silence it if it is this alarm that rings, and remove its notification either way. */
        fun stop(ctx: Context, id: String) {
            val s = running
            if (s != null && s.ringingId == id) s.finishRing(true)
            else (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id.hashCode())
        }
    }
}
