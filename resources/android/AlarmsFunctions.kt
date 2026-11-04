package com.blutrixx.plugins.alarms

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import org.json.JSONArray
import org.json.JSONObject

/** Bridge functions the PHP side calls (see nativephp.json). */
object AlarmsFunctions {
    /** One Android alarm per reminder, but the list is kept in the phone's storage, so it stays small. */
    private const val MAX_TIMES = 100

    /** The bridge may hand an array over as a Kotlin list, a JSONArray or a JSON string. */
    private fun asList(v: Any?): List<Any?> = when (v) {
        is List<*> -> v
        is JSONArray -> List(v.length()) { v.get(it) }
        is String -> try { asList(JSONArray(v)) } catch (e: Exception) { emptyList() }
        else -> emptyList()
    }

    private fun applyChannel(ctx: Context, p: Map<String, Any>) {
        AlarmsStore.mergeConfig(
            ctx,
            mapOf(
                "channelId" to (p["channelId"] as? String)?.takeIf { it.isNotBlank() },
                "channelName" to (p["channelName"] as? String)?.takeIf { it.isNotBlank() },
                "channelDescription" to (p["channelDescription"] as? String),
                "missedWindowMinutes" to (p["missedWindowMinutes"] as? Number)?.toInt(),
            ),
        )
    }

    class Configure(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            applyChannel(context, parameters)
            AlarmsStore.mergeConfig(context, mapOf("icon" to (parameters["icon"] as? String)))
            AlarmsNotifier.ensureChannel(context)
            return BridgeResponse.success(mapOf("configured" to true))
        }
    }

    class Schedule(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = (parameters["id"] as? String)?.takeIf { it.isNotBlank() }
                ?: return BridgeResponse.error("INVALID", "Missing id")
            val title = parameters["title"] as? String ?: return BridgeResponse.error("INVALID", "Missing title")
            val body = parameters["body"] as? String ?: ""
            val url = (parameters["url"] as? String)?.takeIf { it.isNotBlank() }
            val snooze = (parameters["snoozeMinutes"] as? Number)?.toInt() ?: 0
            val now = System.currentTimeMillis()
            val times = listOf(parameters["times"])
                .flatMap { asList(it) }
                .mapNotNull { (it as? Number)?.toLong()?.times(1000L) }
                .filter { it > now }
                .distinct()
                .sorted()
                .take(MAX_TIMES)
            if (times.isEmpty()) return BridgeResponse.error("NO_FUTURE_TIME", "None of the times is in the future")

            applyChannel(context, parameters)
            AlarmsNotifier.ensureChannel(context)
            val alarm = Alarm(id, title, body, url, times, snooze)
            AlarmsStore.save(context, alarm)
            AlarmsScheduler.disarm(context, id)
            val next = AlarmsScheduler.arm(context, alarm) ?: return BridgeResponse.error("NO_FUTURE_TIME", "None of the times is in the future")
            return BridgeResponse.success(
                mapOf("id" to id, "scheduled" to times.size, "next" to next / 1000, "exact" to AlarmsScheduler.canExact(context)),
            )
        }
    }

    class Cancel(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = parameters["id"] as? String ?: return BridgeResponse.error("INVALID", "Missing id")
            AlarmsScheduler.disarm(context, id)
            AlarmsStore.remove(context, id)
            return BridgeResponse.success(mapOf("cancelled" to id))
        }
    }

    class CancelAll(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val all = AlarmsStore.all(context)
            for (a in all) {
                AlarmsScheduler.disarm(context, a.id)
                AlarmsStore.remove(context, a.id)
            }
            return BridgeResponse.success(mapOf("cancelled" to all.size))
        }
    }

    class GetPending(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val now = System.currentTimeMillis()
            val items = JSONArray()
            val all = AlarmsStore.all(context).sortedBy { it.times.firstOrNull { t -> t > now } ?: Long.MAX_VALUE }
            for (a in all) {
                val ahead = a.times.filter { it > now }
                items.put(
                    JSONObject().put("id", a.id).put("title", a.title).put("body", a.body)
                        .put("url", a.url ?: JSONObject.NULL)
                        .put("next", (ahead.firstOrNull() ?: 0L) / 1000).put("remaining", ahead.size),
                )
            }
            return BridgeResponse.success(mapOf("count" to all.size, "items" to items.toString()))
        }
    }

    class Status(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return BridgeResponse.success(
                mapOf(
                    "notifications" to NotificationManagerCompat.from(context).areNotificationsEnabled(),
                    "exactAlarms" to AlarmsScheduler.canExact(context),
                    "batteryUnrestricted" to pm.isIgnoringBatteryOptimizations(context.packageName),
                    "sdk" to Build.VERSION.SDK_INT,
                    "manufacturer" to Build.MANUFACTURER,
                ),
            )
        }
    }

    class RequestNotificationPermission(private val activity: Activity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return BridgeResponse.success(mapOf("requested" to false, "granted" to NotificationManagerCompat.from(activity).areNotificationsEnabled()))
            }
            val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                activity.runOnUiThread { ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7301) }
            }
            return BridgeResponse.success(mapOf("requested" to !granted, "granted" to granted))
        }
    }

    class OpenSettings(private val activity: Activity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val page = parameters["page"] as? String ?: "app"
            val pkg = activity.packageName
            val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            val intent = when (page) {
                "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                "exact" ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg"))
                    else details
                "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                // Transsion phones (Infinix, Tecno, itel) drop alarms for a closed app unless it is allowed in Phone Master's
                // "Auto-start management". Other makers keep their own list; there the app's details page is the closest we open.
                "autostart" -> Intent("com.cyin.himgr.applicationmanager.view.activities.AUTO_START_ACTIVITY")
                else -> details
            }
            activity.runOnUiThread {
                try {
                    activity.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    activity.startActivity(details)
                }
            }
            return BridgeResponse.success(mapOf("opened" to page))
        }
    }

    class History(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val limit = (parameters["limit"] as? Number)?.toInt() ?: 50
            return BridgeResponse.success(mapOf("items" to AlarmsStore.history(context, limit).toString()))
        }
    }

    class ClearHistory(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            AlarmsStore.clearHistory(context)
            return BridgeResponse.success(mapOf("cleared" to true))
        }
    }
}
