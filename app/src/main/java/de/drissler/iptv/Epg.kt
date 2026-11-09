package de.drissler.iptv

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Kurz-EPG (Jetzt/Danach) über die Xtream-API. */
object Epg {
    data class Item(val title: String, val start: Long, val end: Long)

    private val cache = ConcurrentHashMap<String, Pair<Long, List<Item>>>()
    private val liveUrl = Regex("""^(.*)/live/([^/]+)/([^/]+)/(\d+)(\.\w+)?$""")
    private val time = SimpleDateFormat("HH:mm", Locale.GERMANY)

    /** Blockierend aufrufen (Hintergrund-Thread). Leere Liste = kein EPG. */
    fun load(c: Context, streamUrl: String): List<Item> {
        val now = System.currentTimeMillis()
        cache[streamUrl]?.let { (at, items) ->
            if (now - at < 10 * 60_000) return items.filter { it.end > now }
        }
        val m = liveUrl.find(streamUrl) ?: return emptyList()
        val (base, user, pass, id) = m.destructured
        val api = "$base/player_api.php?username=${enc(user)}&password=${enc(pass)}&action=get_short_epg&stream_id=$id&limit=4"
        val items = try {
            val cn = URL(api).openConnection() as HttpURLConnection
            cn.connectTimeout = 8000
            cn.readTimeout = 10000
            cn.setRequestProperty("User-Agent", Prefs.userAgent(c))
            val text = try {
                if (cn.responseCode !in 200..299) return emptyList()
                cn.inputStream.bufferedReader().use { it.readText() }
            } finally { cn.disconnect() }
            val arr = JSONObject(text).optJSONArray("epg_listings") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val start = o.optString("start_timestamp").toLongOrNull() ?: return@mapNotNull null
                val end = o.optString("stop_timestamp").toLongOrNull() ?: return@mapNotNull null
                Item(decode(o.optString("title")), start * 1000, end * 1000)
            }.sortedBy { it.start }
        } catch (e: Exception) {
            emptyList()
        }
        cache[streamUrl] = now to items
        return items.filter { it.end > now }
    }

    /** "Jetzt 20:15 Titel · Danach 21:45 Titel" */
    fun format(items: List<Item>, separator: String = "   ·   "): String {
        if (items.isEmpty()) return ""
        val parts = ArrayList<String>()
        items.getOrNull(0)?.let { parts.add("Jetzt ${t(it.start)}–${t(it.end)}  ${it.title}") }
        items.getOrNull(1)?.let { parts.add("Danach ${t(it.start)}  ${it.title}") }
        return parts.joinToString(separator)
    }

    /** Fortschritt der laufenden Sendung in Prozent. */
    fun progress(items: List<Item>): Int {
        val it = items.firstOrNull() ?: return 0
        val now = System.currentTimeMillis()
        if (it.end <= it.start) return 0
        return (((now - it.start) * 100) / (it.end - it.start)).toInt().coerceIn(0, 100)
    }

    private fun t(ms: Long) = synchronized(time) { time.format(Date(ms)) }

    private fun decode(v: String): String = try {
        String(Base64.decode(v, Base64.DEFAULT), Charsets.UTF_8).trim()
    } catch (e: Exception) { v }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
}
