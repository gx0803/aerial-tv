package app.orchard.tv.remote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the viewer has sent from the phone's page, newest first, so a search typed once can be
 * picked again instead of spelled out again with a remote.
 *
 * Kept on the TV rather than in the phone's browser: that page is meant to be opened from a
 * bookmark on whichever phone is at hand, and a word typed on one should still be there on the
 * next. Sending the same text twice moves the entry it already had to the top rather than leaving
 * two, and the list is capped so a TV that lives for years does not grow one nobody scrolls.
 */
object RemoteHistory {

    /** One remembered send: what was written, and when, as the wall clock the phone shows. */
    data class Entry(val text: String, val at: Long)

    private const val PREFS = "remote_history"
    private const val KEY = "items"

    /** How many are kept; older ones fall off the end. */
    private const val LIMIT = 40

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Newest first, whatever order they were stored in. */
    fun items(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        val doc = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until doc.length()).mapNotNull { i ->
            val o = doc.optJSONObject(i) ?: return@mapNotNull null
            val text = o.optString("text").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Entry(text, o.optLong("at"))
        }.sortedByDescending { it.at }
    }

    fun json(context: Context): JSONObject = JSONObject().put("items", JSONArray().apply {
        items(context).forEach { put(JSONObject().put("text", it.text).put("at", it.at)) }
    })

    /** Remembers [text] as the newest entry; a repeat moves the one it already had. */
    fun add(context: Context, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val kept = items(context).filter { it.text != clean }
        save(context, (listOf(Entry(clean, System.currentTimeMillis())) + kept).take(LIMIT))
    }

    fun remove(context: Context, text: String) {
        save(context, items(context).filter { it.text != text })
    }

    fun clear(context: Context) = save(context, emptyList())

    private fun save(context: Context, list: List<Entry>) {
        val doc = JSONArray()
        list.forEach { doc.put(JSONObject().put("text", it.text).put("at", it.at)) }
        prefs(context).edit().putString(KEY, doc.toString()).apply()
    }
}
