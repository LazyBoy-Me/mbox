package app.mbox

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device state: watch history, "my list" and the playback preferences the stock app keeps in
 * its settings screen. Everything is stored as JSON in one SharedPreferences file so the app works
 * with no account at all.
 */
object Store {
    private const val FILE = "mbox_local"
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun p(): android.content.SharedPreferences? = prefs

    // ------------------------------------------------------------------ settings

    var autoplayNext: Boolean
        get() = p()?.getBoolean("autoplay", true) ?: true
        set(v) { p()?.edit()?.putBoolean("autoplay", v)?.apply() }

    var dataSaver: Boolean
        get() = p()?.getBoolean("dataSaver", true) ?: true
        set(v) { p()?.edit()?.putBoolean("dataSaver", v)?.apply() }

    var wifiOnlyDownloads: Boolean
        get() = p()?.getBoolean("wifiOnly", false) ?: false
        set(v) { p()?.edit()?.putBoolean("wifiOnly", v)?.apply() }

    var subtitleLang: String
        get() = p()?.getString("subLang", "English") ?: "English"
        set(v) { p()?.edit()?.putString("subLang", v)?.apply() }

    var scaleMode: Int
        get() = p()?.getInt("scale", 0) ?: 0
        set(v) { p()?.edit()?.putInt("scale", v)?.apply() }

    /** Subtitle render scale (0.85 small, 1 normal, 1.25 large) and translation background. */
    var subSize: Float
        get() = p()?.getFloat("subSize", 1f) ?: 1f
        set(v) { p()?.edit()?.putFloat("subSize", v)?.apply() }

    var subBackground: Boolean
        get() = p()?.getBoolean("subBg", true) ?: true
        set(v) { p()?.edit()?.putBoolean("subBg", v)?.apply() }

    var speed: Float
        get() = p()?.getFloat("speed", 1f) ?: 1f
        set(v) { p()?.edit()?.putFloat("speed", v)?.apply() }

    /** Last fatal error, captured by [MBoxApp] and shown under Me → About. */
    var lastCrash: String
        get() = p()?.getString("lastCrash", "") ?: ""
        set(v) { p()?.edit()?.putString("lastCrash", v)?.apply() }

    // ------------------------------------------------------------------ history

    private fun readHistory(): MutableList<HistoryEntry> {
        val raw = p()?.getString("history", "") ?: ""
        if (raw.isBlank()) return ArrayList()
        val out = ArrayList<HistoryEntry>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    HistoryEntry(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        cover = o.optString("cover"),
                        still = o.optString("still"),
                        type = o.optInt("type", 1),
                        se = o.optInt("se"),
                        ep = o.optInt("ep"),
                        position = o.optLong("pos"),
                        duration = o.optLong("dur"),
                        updated = o.optLong("upd"),
                    )
                )
            }
        }
        return out
    }

    private fun writeHistory(list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.take(60).forEach { h ->
            arr.put(
                JSONObject()
                    .put("id", h.id).put("title", h.title).put("cover", h.cover)
                    .put("still", h.still)
                    .put("type", h.type).put("se", h.se).put("ep", h.ep)
                    .put("pos", h.position).put("dur", h.duration).put("upd", h.updated)
            )
        }
        p()?.edit()?.putString("history", arr.toString())?.apply()
    }

    fun history(): List<HistoryEntry> = readHistory().sortedByDescending { it.updated }

    fun remember(s: Subject, se: Int, ep: Int, position: Long, duration: Long) {
        if (s.id.isEmpty()) return
        val list = readHistory()
        list.removeAll { it.id == s.id && it.se == se && it.ep == ep }
        list.add(
            HistoryEntry(
                id = s.id, title = s.title, cover = s.cover,
                still = s.still.ifBlank { s.cover }, type = s.type,
                se = se, ep = ep, position = position, duration = duration,
                updated = System.currentTimeMillis(),
            )
        )
        writeHistory(list.sortedByDescending { it.updated })
    }

    fun historyFor(id: String): HistoryEntry? = readHistory().firstOrNull { it.id == id }

    fun clearHistory() = writeHistory(emptyList())

    fun removeHistory(id: String) = writeHistory(readHistory().filterNot { it.id == id })

    // ------------------------------------------------------------------ my list

    fun myList(): List<Subject> {
        val raw = p()?.getString("list", "") ?: ""
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<Subject>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Subject(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        type = o.optInt("type", 1),
                        cover = o.optString("cover"),
                        still = o.optString("still"),
                        year = o.optString("year"),
                        genre = o.optString("genre"),
                        rating = o.optString("rating"),
                        badge = o.optString("badge"),
                    )
                )
            }
        }
        return out
    }

    fun inList(id: String): Boolean = myList().any { it.id == id }

    fun toggleList(s: Subject): Boolean {
        val list = myList().toMutableList()
        val had = list.any { it.id == s.id }
        if (had) list.removeAll { it.id == s.id } else list.add(0, s)
        val arr = JSONArray()
        list.take(200).forEach { x ->
            arr.put(
                JSONObject().put("id", x.id).put("title", x.title).put("type", x.type)
                    .put("cover", x.cover).put("year", x.year).put("genre", x.genre)
                    .put("rating", x.rating).put("badge", x.badge)
            )
        }
        p()?.edit()?.putString("list", arr.toString())?.apply()
        return !had
    }

    // ------------------------------------------------------------------ search history

    fun searchHistory(): List<String> {
        val raw = p()?.getString("searches", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun addSearch(word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        val list = searchHistory().toMutableList()
        list.removeAll { it.equals(w, true) }
        list.add(0, w)
        val arr = JSONArray()
        list.take(12).forEach { arr.put(it) }
        p()?.edit()?.putString("searches", arr.toString())?.apply()
    }

    fun clearSearchHistory() = p()?.edit()?.putString("searches", "[]")?.apply()
}
