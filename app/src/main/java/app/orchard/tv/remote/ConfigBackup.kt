package app.orchard.tv.remote

import android.content.Context
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.weather.Weather
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything Aerial remembers, as one JSON document: a backup before a factory reset, and a way to
 * carry a TV's setup onto a second one.
 *
 * Pictures stay behind: wallpapers and custom icons are files, and what is stored is only the name
 * Aerial knows them by. A restored TV therefore keeps its own pictures and its layout, and gets the
 * choices — frame theme, night mode, focus style, city, screensaver, hidden apps, the PIN.
 */
object ConfigBackup {

    /** The preference files worth carrying over; anything else is bookkeeping. */
    private val FILES = listOf("settings", "wallpaper", "layout", "weather", "runtime", "system")

    /** Values that describe the running process rather than a choice the viewer made. */
    private val SKIP = setOf("system:home_in_front", "system:revivals")

    private const val VERSION = 1

    fun export(context: Context): JSONObject {
        val files = JSONObject()
        FILES.forEach { name ->
            runCatching { context.getSharedPreferences(name, Context.MODE_PRIVATE).all }.getOrNull()?.let { all ->
                val entries = JSONObject()
                all.forEach { (key, value) -> encode(value)?.let { entries.put(key, it) } }
                files.put(name, entries)
            }
        }
        return JSONObject()
            .put("app", "aerial-tv")
            .put("version", VERSION)
            .put("savedAt", System.currentTimeMillis())
            .put("files", files)
    }

    /**
     * Writes the values back and re-reads them into the running app. Returns how many were applied.
     * The output comes from the viewer's own TV, but a malformed or foreign document only skips
     * what it cannot read rather than failing outright.
     */
    fun restore(context: Context, json: JSONObject): Int {
        val files = json.optJSONObject("files") ?: return 0
        var applied = 0
        FILES.forEach { name ->
            val entries = files.optJSONObject(name) ?: return@forEach
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val edit = prefs.edit()
            // Replace rather than merge: a backup is a whole setup, and a key it does not have is
            // one that was not chosen.
            edit.clear()
            entries.keys().forEach { key ->
                if ("$name:$key" in SKIP) return@forEach
                val slot = entries.optJSONObject(key) ?: return@forEach
                val wrote = runCatching {
                    when {
                        slot.has("s") -> edit.putString(key, slot.getString("s"))
                        slot.has("z") -> edit.putBoolean(key, slot.getBoolean("z"))
                        slot.has("i") -> edit.putInt(key, slot.getInt("i"))
                        slot.has("l") -> edit.putLong(key, slot.getLong("l"))
                        slot.has("f") -> edit.putFloat(key, slot.getDouble("f").toFloat())
                        slot.has("S") -> edit.putStringSet(key, slot.getJSONArray("S").toStringSet())
                        else -> return@runCatching false
                    }
                    true
                }.getOrDefault(false)
                if (wrote) applied++
            }
            edit.apply()
        }
        reload(context)
        return applied
    }

    /** Reads the restored values into the objects the screens read from. */
    private fun reload(context: Context) {
        AerialSettings.init(context)
        Wallpapers.init(context)
        Weather.init(context)
        app.orchard.tv.system.RunTime.init(context)
    }

    private fun encode(value: Any?): JSONObject? = when (value) {
        is String -> JSONObject().put("s", value)
        is Boolean -> JSONObject().put("z", value)
        is Int -> JSONObject().put("i", value)
        is Long -> JSONObject().put("l", value)
        is Float -> JSONObject().put("f", value.toDouble())
        is Set<*> -> JSONObject().put("S", JSONArray(value.mapNotNull { it as? String }))
        else -> null
    }

    private fun JSONArray.toStringSet(): Set<String> =
        (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }.toSet()
}
