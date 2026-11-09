package de.drissler.iptv

import android.content.Context
import android.content.SharedPreferences
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
    fun userAgent(c: Context) = p(c).getString("ua", "")?.takeIf { it.isNotBlank() } ?: DEFAULT_UA
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
    private fun file(c: Context) = File(c.filesDir, "playlist.m3u")

    fun loadCached(c: Context, url: String): List<Channel> {
        val f = file(c)
        if (!f.exists() || Prefs.cachedUrl(c) != url) return emptyList()
        return f.bufferedReader().use { M3uParser.parse(it) }
    }

    fun download(c: Context, url: String, ua: String): List<Channel> {
        var current = url
        var conn: HttpURLConnection? = null
        for (i in 0..6) {
            val cn = URL(current).openConnection() as HttpURLConnection
            cn.instanceFollowRedirects = false
            cn.connectTimeout = 15000
            cn.readTimeout = 60000
            cn.setRequestProperty("User-Agent", ua)
            val code = cn.responseCode
            if (code in 300..399) {
                val loc = cn.getHeaderField("Location") ?: throw IOException("Weiterleitung ohne Ziel")
                cn.disconnect()
                current = URL(URL(current), loc).toString()
                continue
            }
            if (code !in 200..299) { cn.disconnect(); throw IOException("Server antwortet mit HTTP $code") }
            conn = cn
            break
        }
        val cn = conn ?: throw IOException("Zu viele Weiterleitungen")
        val tmp = File(c.filesDir, "playlist.tmp")
        try {
            cn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } finally {
            cn.disconnect()
        }
        val list = tmp.bufferedReader().use { M3uParser.parse(it) }
        if (list.isEmpty()) { tmp.delete(); throw IOException("Keine Sender in der Liste gefunden") }
        tmp.renameTo(file(c).also { it.delete() })
        Prefs.setCachedUrl(c, url)
        return list
    }
}
