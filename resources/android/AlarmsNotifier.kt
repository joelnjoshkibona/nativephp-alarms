package com.blutrixx.plugins.alarms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat

object AlarmsNotifier {
    private const val TAG = "Alarms"
    const val DEFAULT_CHANNEL = "blutrixx_alarms"
    const val ACTION_SNOOZE = "com.blutrixx.plugins.alarms.SNOOZE"
    const val ACTION_DISMISS = "com.blutrixx.plugins.alarms.DISMISS"
    const val SCREEN_CHANNEL = "blutrixx_alarm_screen"
    const val RING_CHANNEL = "blutrixx_alarm_ring"
    /** An alarm that nobody answers stops by itself after this long (it stays in the history). */
    private const val SCREEN_TIMEOUT_MS = 3 * 60_000L

    /** Android 14 lets the person switch the full-screen page off; before that it is always allowed. */
    fun canFullScreen(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()

    /** The channel of alarm-style reminders: the alarm ringtone on the alarm volume, shown on the lock screen. */
    fun ensureScreenChannel(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(SCREEN_CHANNEL, "Alarm reminders", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Reminders that take over the screen and ring until you answer"
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            ch.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        return SCREEN_CHANNEL
    }

    /** The channel of an alarm the ring service plays: loud and shown at once, but silent itself (the service plays the sound). */
    fun ensureRingChannel(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(RING_CHANNEL, "Alarm reminders (ringing)", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Shown while an alarm reminder rings"
            ch.setSound(null, null)
            ch.enableVibration(false)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        return RING_CHANNEL
    }

    fun ensureChannel(ctx: Context): String {
        val cfg = AlarmsStore.config(ctx)
        val id = cfg.optString("channelId", DEFAULT_CHANNEL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(id, cfg.optString("channelName", "Reminders"), NotificationManager.IMPORTANCE_HIGH)
            ch.description = cfg.optString("channelDescription", "Reminders you set")
            ch.enableVibration(true)
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        return id
    }

    /** The app can send its own monochrome PNG (Configure). Without one, Android's bell. */
    private fun smallIcon(ctx: Context): IconCompat {
        val b64 = AlarmsStore.config(ctx).optString("icon", "")
        if (b64.isNotEmpty()) {
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) return IconCompat.createWithBitmap(bmp)
            } catch (e: Exception) {
                Log.w(TAG, "Small icon could not be read: ${e.message}")
            }
        }
        return IconCompat.createWithResource(ctx, android.R.drawable.ic_popup_reminder)
    }

    /** A tap opens the reminder's link (the app's own deep link scheme), or just the app. */
    fun openIntent(ctx: Context, url: String?): Intent {
        val i = if (!url.isNullOrBlank()) Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(ctx.packageName)
        else ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent()
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    /**
     * Turns the screen on for a few seconds. The full-screen page does this by itself where Android allows it; some phones (Transsion's
     * HiOS, Android 14 with the setting off) hold the page back, and then a sleeping phone would ring with a dark screen.
     */
    @Suppress("DEPRECATION")
    private fun wakeScreen(ctx: Context) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "hodie:alarm")
                .acquire(15_000L)
        } catch (e: Exception) {
            Log.w(TAG, "Could not wake the screen: ${e.message}")
        }
    }

    /**
     * The notification of one reminder. `ring` is an alarm that the ring service plays: a silent channel (the service plays the sound,
     * looped), and the tap and Done go through [AlarmOpenActivity] so they silence it. Otherwise the channel plays its own sound.
     */
    fun build(ctx: Context, a: Alarm, due: Long, ring: Boolean = false): Notification {
        val channel = if (ring) ensureRingChannel(ctx) else if (a.screen) ensureScreenChannel(ctx) else ensureChannel(ctx)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        fun viaOpen(url: String?, salt: Int): PendingIntent =
            PendingIntent.getActivity(ctx, a.id.hashCode() xor salt, Intent(ctx, AlarmOpenActivity::class.java)
                .putExtra("id", a.id).putExtra("open", url).setData(Uri.parse("alarms://open/$salt/${Uri.encode(a.id)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY), flags)
        val tap = if (ring) viaOpen(a.url, 0x5A5A) else PendingIntent.getActivity(ctx, a.id.hashCode() xor 0x5A5A, openIntent(ctx, a.url), flags)
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(smallIcon(ctx))
            .setContentTitle(a.title)
            .setContentText(a.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (a.screen) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setWhen(due)
            .setShowWhen(true)
            .setContentIntent(tap)
        if (a.snoozeMinutes > 0) {
            val snooze = Intent(ctx, AlarmsActionReceiver::class.java)
                .setAction(ACTION_SNOOZE)
                .setData(Uri.parse("alarms://snooze/${Uri.encode(a.id)}"))
                .putExtra("id", a.id).putExtra("title", a.title).putExtra("body", a.body)
                .putExtra("url", a.url).putExtra("minutes", a.snoozeMinutes)
                .putExtra("screen", a.screen).putExtra("doneUrl", a.doneUrl)
            val snoozePi = PendingIntent.getBroadcast(ctx, a.id.hashCode() xor 0x1234, snooze, flags)
            b.addAction(0, "Snooze ${a.snoozeMinutes} min", snoozePi)
        }
        if (a.screen) {
            // The alarm page opens over the lock screen (or heads-up when the phone is in use).
            val page = Intent(ctx, AlarmActivity::class.java)
                .putExtra("id", a.id).putExtra("title", a.title).putExtra("body", a.body).putExtra("url", a.url)
                .putExtra("doneUrl", a.doneUrl).putExtra("minutes", a.snoozeMinutes).putExtra("due", due)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            b.setFullScreenIntent(PendingIntent.getActivity(ctx, a.id.hashCode() xor 0x3C3C, page, flags), true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            val dismiss = Intent(ctx, AlarmsActionReceiver::class.java)
                .setAction(ACTION_DISMISS).setData(Uri.parse("alarms://dismiss/${Uri.encode(a.id)}")).putExtra("id", a.id)
            val dismissPi = PendingIntent.getBroadcast(ctx, a.id.hashCode() xor 0x4D4D, dismiss, flags)
            b.addAction(0, "Dismiss", dismissPi).setDeleteIntent(dismissPi)
            if (!a.doneUrl.isNullOrBlank()) {
                val done = if (ring) viaOpen(a.doneUrl, 0x2B2B) else PendingIntent.getActivity(ctx, a.id.hashCode() xor 0x2B2B, openIntent(ctx, a.doneUrl), flags)
                b.addAction(0, "Done", done)
            }
            if (ring) b.setOngoing(true) else b.setTimeoutAfter(SCREEN_TIMEOUT_MS)
        }
        val n = b.build()
        if (a.screen && !ring) n.flags = n.flags or Notification.FLAG_INSISTENT
        return n
    }

    /** A notification that plays its channel's sound (the fallback for an alarm, and every ordinary reminder). */
    fun postPlain(ctx: Context, a: Alarm, due: Long): Boolean {
        return try {
            NotificationManagerCompat.from(ctx).notify(a.id.hashCode(), build(ctx, a, due, ring = false))
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification refused for ${a.id}: ${e.message}")
            false
        }
    }

    /** Returns true when a notification was handed to Android (false: notifications are switched off). */
    fun post(ctx: Context, a: Alarm, due: Long): Boolean {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            Log.w(TAG, "Notifications are off, ${a.id} not shown")
            return false
        }
        if (!a.screen) return postPlain(ctx, a, due)
        wakeScreen(ctx)
        // The ring service shows the notification and plays the looped sound; if Android will not run it, the plain notification does.
        return try {
            AlarmRingService.start(ctx, a, due)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Ring service not started for ${a.id}: ${e.message}")
            AppLogBridge.w('W', "the alarm sound service could not start (${e.javaClass.simpleName}), using the notification's own sound")
            postPlain(ctx, a, due)
        }
    }
}
