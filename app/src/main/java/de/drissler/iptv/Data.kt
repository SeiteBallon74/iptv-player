package de.drissler.iptv

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val headers: Map<String, String>
)

/** Hält die Listen im Speicher, damit der Player sie ohne Intent-Übergabe kennt. */
object ChannelStore {
    var all: List<Channel> = emptyList()
    var playing: List<Channel> = emptyList()
    var index: Int = 0
}

object Prefs {
    const val DEFAULT_UA = "VLC/3.0.20 LibVLC/3.0.20"
    private fun p(c: Context): SharedPreferences = c.getSharedPreferences("iptv", Context.MODE_PRIVATE)

    fun url(c: Context) = p(c).getString("url", "") ?: ""
    fun userAgent(c: Context) = p(c).getString("ua", "")?.takeIf { it.isNotBlank() }
        ?: p(c).getString("working_ua", "")?.takeIf { it.isNotBlank() } ?: DEFAULT_UA
    fun workingUserAgent(c: Context) = p(c).getString("working_ua", "") ?: ""
    fun setWorkingUserAgent(c: Context, ua: String) = p(c).edit().putString("working_ua", ua).apply()
    fun rawUserAgent(c: Context) = p(c).getString("ua", "") ?: ""
    fun bufferMode(c: Context) = p(c).getInt("buffer", 1)
    fun cachedUrl(c: Context) = p(c).getString("cached_url", "") ?: ""
    fun lastGroup(c: Context) = p(c).getString("last_group", "") ?: ""
    fun favorites(c: Context): Set<String> = p(c).getStringSet("favs", emptySet())?.toSet() ?: emptySet()

    fun save(c: Context, url: String, ua: String, buffer: Int) =
        p(c).edit().putString("url", url).putString("ua", ua).putInt("buffer", buffer).apply()

    fun setCachedUrl(c: Context, url: String) = p(c).edit().putString("cached_url", url).apply()
    fun setLastGroup(c: Context, g: String) = p(c).edit().putString("last_group", g).apply()

    fun toggleFavorite(c: Context, url: String): Boolean {
        val set = favorites(c).toMutableSet()
        val nowFav = if (set.contains(url)) { set.remove(url); false } else { set.add(url); true }
        p(c).edit().putStringSet("favs", set).apply()
        return nowFav
    }
}

object M3uParser {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(reader: BufferedReader): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var logo: String? = null
        var group = ""
        var headers = HashMap<String, String>()

        reader.forEachLine { raw ->
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    name = displayName(line) ?: attrs["tvg-name"]
                    logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                    group = attrs["group-title"]?.trim().orEmpty()
                    headers = HashMap()
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> group = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> headers["User-Agent"] = value
                        "http-referrer", "http-referer" -> headers["Referer"] = value
                        "http-origin" -> headers["Origin"] = value
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    // Header im Kodi-Stil: url|User-Agent=...&Referer=...
                    var url = line
                    if (url.contains('|')) {
                        url.substringAfter('|').split('&').forEach { kv ->
                            val k = kv.substringBefore('=')
                            val v = kv.substringAfter('=', "")
                            if (k.isNotBlank() && v.isNotBlank()) headers[k] = v
                        }
                        url = url.substringBefore('|')
                    }
                    out.add(Channel(name?.takeIf { it.isNotBlank() } ?: url, url, logo, group.ifBlank { "Ohne Gruppe" }, headers))
                    name = null; logo = null; group = ""; headers = HashMap()
                }
            }
        }
        return out
    }

    /** Name steht nach dem ersten Komma außerhalb von Anführungszeichen. */
    private fun displayName(line: String): String? {
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) return line.substring(i + 1).trim()
        }
        return null
    }
}

object PlaylistRepo {
    private val UA_FALLBACKS = listOf(
        "IPTVSmartersPlayer",
        "okhttp/4.9.0",
        "TiviMate/4.7.0 (Fire TV)",
        Prefs.DEFAULT_UA,
        "Mozilla/5.0 (Linux; Android 9; AFTKRT) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    )

    private fun file(c: Context) = File(c.filesDir, "playlist.m3u")

    fun loadCached(c: Context, url: String): List<Channel> {
        val f = file(c)
        if (!f.exists() || Prefs.cachedUrl(c) != url) return emptyList()
        return f.bufferedReader().use { M3uParser.parse(it) }
    }

    /** Lädt die Liste. get.php-Links (Xtream) laufen über player_api.php. */
    fun download(c: Context, url: String): List<Channel> {
        val uri = Uri.parse(url)
        val user = uri.getQueryParameter("username")
        val pass = uri.getQueryParameter("password")
        val tmp = File(c.filesDir, "playlist.tmp")
        if (url.contains("get.php") && !user.isNullOrBlank() && !pass.isNullOrBlank()) {
            val base = url.substringBefore("/get.php")
            buildXtream(c, base, user, pass, tmp)
        } else {
            fetchWithFallback(c, url, tmp) { f -> f.length() > 0 }
        }
        val list = tmp.bufferedReader().use { M3uParser.parse(it) }
        if (list.isEmpty()) { tmp.delete(); throw IOException("Keine Sender in der Liste gefunden") }
        tmp.renameTo(file(c).also { it.delete() })
        Prefs.setCachedUrl(c, url)
        return list
    }

    private fun buildXtream(c: Context, base: String, user: String, pass: String, out: File) {
        val api = "$base/player_api.php?username=${enc(user)}&password=${enc(pass)}"
        val tmp = File(c.filesDir, "api.tmp")

        val ua = fetchWithFallback(c, api, tmp) { f -> firstChar(f) == '{' }
        val info = JSONObject(tmp.readText()).optJSONObject("user_info")
        if (info != null && info.optString("auth") == "0") throw IOException("Zugangsdaten abgelehnt")
        val state = info?.optString("status").orEmpty()
        if (state.isNotEmpty() && !state.equals("Active", true)) throw IOException("Konto-Status: $state")

        val cats = HashMap<String, String>()
        if (fetch("$api&action=get_live_categories", ua, tmp) in 200..299 && firstChar(tmp) == '[') {
            val arr = JSONArray(tmp.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                cats[o.optString("category_id")] = o.optString("category_name")
            }
        }

        val code = fetch("$api&action=get_live_streams", ua, tmp)
        if (code !in 200..299 || firstChar(tmp) != '[') throw IOException("Senderliste: Server antwortet mit HTTP $code")
        val arr = JSONArray(tmp.readText())
        tmp.delete()

        out.bufferedWriter().use { w ->
            w.write("#EXTM3U\n")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("stream_id")
                if (id.isBlank()) continue
                val name = o.optString("name").replace("\n", " ").trim()
                val logo = clean(o.optString("stream_icon"))
                val group = clean(cats[o.optString("category_id")] ?: "")
                w.write("#EXTINF:-1 tvg-logo=\"$logo\" group-title=\"$group\",$name\n")
                w.write("$base/live/$user/$pass/$id.ts\n")
            }
        }
    }

    /** Probiert verschiedene App-Kennungen, bis der Server liefert; merkt sich die funktionierende. */
    private fun fetchWithFallback(c: Context, url: String, dest: File, valid: (File) -> Boolean): String {
        val candidates = LinkedHashSet<String>()
        Prefs.rawUserAgent(c).takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        Prefs.workingUserAgent(c).takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        candidates.addAll(UA_FALLBACKS)
        var last = "keine Antwort"
        for (ua in candidates) {
            val code = try { fetch(url, ua, dest) } catch (e: IOException) { last = e.message ?: "Netzwerkfehler"; continue }
            if (code in 200..299 && valid(dest)) {
                Prefs.setWorkingUserAgent(c, ua)
                return ua
            }
            last = "HTTP $code"
        }
        throw IOException("Server lehnt ab ($last)")
    }

    /** Lädt url nach dest, folgt Weiterleitungen, gibt den HTTP-Code zurück. */
    private fun fetch(url: String, ua: String, dest: File): Int {
        var current = url
        for (i in 0..6) {
            val cn = URL(current).openConnection() as HttpURLConnection
            cn.instanceFollowRedirects = false
            cn.connectTimeout = 15000
            cn.readTimeout = 60000
            cn.setRequestProperty("User-Agent", ua)
            try {
                val code = cn.responseCode
                if (code in 300..399) {
                    val loc = cn.getHeaderField("Location") ?: return code
                    current = URL(URL(current), loc).toString()
                    continue
                }
                if (code in 200..299) cn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                return code
            } finally {
                cn.disconnect()
            }
        }
        throw IOException("Zu viele Weiterleitungen")
    }

    private fun firstChar(f: File): Char? = f.bufferedReader().use { r ->
        var ch = r.read()
        while (ch != -1 && (ch.toChar().isWhitespace() || ch == 0xFEFF)) ch = r.read()
        if (ch == -1) null else ch.toChar()
    }

    private fun clean(v: String) = v.replace('"', '\'').replace('\n', ' ')
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
}
