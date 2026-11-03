package com.blutrixx.plugins.alarms

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One reminder: what to show, where a tap goes, and every time it should fire (epoch milliseconds, ascending). */
data class Alarm(
    val id: String,
    val title: String,
    val body: String,
    val url: String?,
    val times: List<Long>,
    val snoozeMinutes: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("body", body)
        .put("url", url ?: JSONObject.NULL)
        .put("times", JSONArray(times))
        .put("snoozeMinutes", snoozeMinutes)

    companion object {
        fun fromJson(o: JSONObject): Alarm {
            val t = o.getJSONArray("times")
            return Alarm(
                id = o.getString("id"),
                title = o.getString("title"),
                body = o.getString("body"),
                url = if (o.isNull("url")) null else o.getString("url"),
                times = List(t.length()) { t.getLong(it) },
                snoozeMinutes = o.optInt("snoozeMinutes", 0),
            )
        }
    }
}

/** Everything the plugin keeps lives in one SharedPreferences file, so receivers can read it with no PHP running. */
object AlarmsStore {
    private const val PREFS = "blutrixx_alarms"
    private const val KEY_IDS = "ids"
    private const val KEY_CONFIG = "config"
    private const val KEY_HISTORY = "history"
    private const val HISTORY_MAX = 100
    private val lock = Any()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(ctx: Context, a: Alarm) = synchronized(lock) {
        val p = prefs(ctx)
        val ids = (p.getStringSet(KEY_IDS, emptySet()) ?: emptySet()).toMutableSet()
        ids.add(a.id)
        p.edit().putStringSet(KEY_IDS, ids).putString("a:${a.id}", a.toJson().toString()).commit()
        Unit
    }

    fun load(ctx: Context, id: String): Alarm? = synchronized(lock) {
        val raw = prefs(ctx).getString("a:$id", null) ?: return null
        try { Alarm.fromJson(JSONObject(raw)) } catch (e: Exception) { null }
    }

    fun remove(ctx: Context, id: String) = synchronized(lock) {
        val p = prefs(ctx)
        val ids = (p.getStringSet(KEY_IDS, emptySet()) ?: emptySet()).toMutableSet()
        ids.remove(id)
        p.edit().putStringSet(KEY_IDS, ids).remove("a:$id").commit()
        Unit
    }

    fun all(ctx: Context): List<Alarm> = synchronized(lock) {
        val ids = prefs(ctx).getStringSet(KEY_IDS, emptySet()) ?: emptySet()
        ids.mapNotNull { load(ctx, it) }
    }

    fun config(ctx: Context): JSONObject = synchronized(lock) {
        try { JSONObject(prefs(ctx).getString(KEY_CONFIG, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    }

    fun mergeConfig(ctx: Context, values: Map<String, Any?>) = synchronized(lock) {
        val cfg = config(ctx)
        for ((k, v) in values) if (v != null) cfg.put(k, v)
        prefs(ctx).edit().putString(KEY_CONFIG, cfg.toString()).commit()
        Unit
    }

    /** Newest first. `source` is alarm, restore or snooze. */
    fun addHistory(ctx: Context, id: String, title: String, due: Long, shown: Boolean, source: String) = synchronized(lock) {
        val old = try { JSONArray(prefs(ctx).getString(KEY_HISTORY, "[]")) } catch (e: Exception) { JSONArray() }
        val list = JSONArray()
        list.put(
            JSONObject().put("id", id).put("title", title).put("due", due / 1000)
                .put("firedAt", System.currentTimeMillis() / 1000).put("shown", shown).put("source", source),
        )
        for (i in 0 until minOf(old.length(), HISTORY_MAX - 1)) list.put(old.get(i))
        prefs(ctx).edit().putString(KEY_HISTORY, list.toString()).commit()
        Unit
    }

    fun history(ctx: Context, limit: Int): JSONArray = synchronized(lock) {
        val old = try { JSONArray(prefs(ctx).getString(KEY_HISTORY, "[]")) } catch (e: Exception) { JSONArray() }
        val out = JSONArray()
        for (i in 0 until minOf(old.length(), limit)) out.put(old.get(i))
        out
    }

    fun clearHistory(ctx: Context) = synchronized(lock) {
        prefs(ctx).edit().remove(KEY_HISTORY).commit()
        Unit
    }
}
