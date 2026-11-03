package com.blutrixx.plugins.alarms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat

object AlarmsNotifier {
    private const val TAG = "Alarms"
    const val DEFAULT_CHANNEL = "blutrixx_alarms"
    const val ACTION_SNOOZE = "com.blutrixx.plugins.alarms.SNOOZE"

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
    private fun openIntent(ctx: Context, url: String?): Intent {
        val i = if (!url.isNullOrBlank()) Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(ctx.packageName)
        else ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent()
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    /** Returns true when a notification was handed to Android (false: notifications are switched off). */
    fun post(ctx: Context, a: Alarm, due: Long): Boolean {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications are off, ${a.id} not shown")
            return false
        }
        val channel = ensureChannel(ctx)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val tap = PendingIntent.getActivity(ctx, a.id.hashCode() xor 0x5A5A, openIntent(ctx, a.url), flags)
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(smallIcon(ctx))
            .setContentTitle(a.title)
            .setContentText(a.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
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
            val snoozePi = PendingIntent.getBroadcast(ctx, a.id.hashCode() xor 0x1234, snooze, flags)
            b.addAction(0, "Snooze ${a.snoozeMinutes} min", snoozePi)
        }
        return try {
            nm.notify(a.id.hashCode(), b.build())
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification refused for ${a.id}: ${e.message}")
            false
        }
    }
}
