package com.blutrixx.plugins.alarms

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's log, if the device-utils plugin is in the app (by reflection: no dependency on it). Reminder titles are the person's own words:
 * never write them, only the id, the times and what happened.
 */
internal object AppLogBridge {
    private val write = try { Class.forName("com.blutrixx.device.utils.AppLog").getMethod("write", Char::class.javaPrimitiveType, String::class.java, String::class.java) } catch (e: Throwable) { null }
    fun w(level: Char, message: String) { try { write?.invoke(null, level, "alarms", message) } catch (e: Throwable) { } }
    fun clock(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
}
