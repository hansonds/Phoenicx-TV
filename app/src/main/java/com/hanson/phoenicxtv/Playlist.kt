package com.hanson.phoenicxtv

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class Channel(
    val name: String, val url: String, val logo: String?, val group: String,
    val userAgent: String? = null, val referer: String? = null
)

class Group(val name: String, val channels: List<Channel>)

/** The saved playlist source: either a plain M3U link or Xtream Codes credentials. */
class Source(
    val type: String,          // "m3u" or "xtream"
    val name: String,
    val url: String,           // M3U link (m3u) or server base URL (xtream)
    val user: String,
    val pass: String
) {
    fun toJson(): String = JSONObject()
        .put("type", type).put("name", name).put("url", url)
        .put("user", user).put("pass", pass).toString()

    companion object {
        fun fromJson(text: String?): Source? {
            if (text.isNullOrBlank()) return null
            return try {
                val o = JSONObject(text)
                Source(
                    o.optString("type", "m3u"), o.optString("name"), o.optString("url"),
                    o.optString("user"), o.optString("pass")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

object Playlist {
    private const val UA = "PhoenicxTV/1.0"

    /** Downloads the source and returns its live channels. Call from a background thread. */
    fun load(source: Source): List<Channel> {
        return if (source.type == "xtream") loadXtream(source) else parseM3u(fetch(source.url))
    }

    fun groupsOf(channels: List<Channel>): List<Group> {
        val order = LinkedHashMap<String, MutableList<Channel>>()
        for (c in channels) order.getOrPut(c.group) { ArrayList() }.add(c)
        return order.map { Group(it.key, it.value) }
    }

    // ---------- M3U ----------

    fun parseM3u(text: String): List<Channel> {
        val result = ArrayList<Channel>()
        var name: String? = null
        var logo: String? = null
        var group = "Other"
        var ua: String? = null
        var ref: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                ua = null
                ref = null
                val comma = line.lastIndexOf(',')
                name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                val attrs = if (comma >= 0) line.substring(0, comma) else line
                logo = attr(attrs, "tvg-logo")
                group = attr(attrs, "group-title")?.takeIf { it.isNotBlank() } ?: "Other"
                if (name.isEmpty()) name = attr(attrs, "tvg-name") ?: "Channel"
            } else if (line.startsWith("#EXTGRP:", ignoreCase = true)) {
                group = line.substring(8).trim().ifEmpty { "Other" }
            } else if (line.startsWith("#EXTVLCOPT:", ignoreCase = true)) {
                // VLC-style per-channel headers, used by many providers.
                val opt = line.substring(11)
                val eq = opt.indexOf('=')
                if (eq > 0) {
                    val key = opt.substring(0, eq).trim().lowercase()
                    val value = opt.substring(eq + 1).trim()
                    if (key == "http-user-agent") ua = value
                    if (key == "http-referrer") ref = value
                }
            } else if (!line.startsWith("#")) {
                if (name != null) {
                    // Kodi-style "url|User-Agent=...&Referer=..." suffix.
                    var url = line
                    val bar = line.indexOf('|')
                    if (bar > 0) {
                        url = line.substring(0, bar)
                        for (pair in line.substring(bar + 1).split('&')) {
                            val eq = pair.indexOf('=')
                            if (eq <= 0) continue
                            val key = pair.substring(0, eq).trim().lowercase()
                            val value = java.net.URLDecoder.decode(pair.substring(eq + 1).trim(), "UTF-8")
                            if (key == "user-agent") ua = value
                            if (key == "referer" || key == "referrer") ref = value
                        }
                    }
                    result.add(Channel(name, url, logo, group, ua, ref))
                }
                name = null
                logo = null
                ua = null
                ref = null
            }
        }
        return result
    }

    private fun attr(line: String, key: String): String? {
        val k = "$key=\""
        val i = line.indexOf(k, ignoreCase = true)
        if (i < 0) return null
        val start = i + k.length
        val end = line.indexOf('"', start)
        if (end < 0) return null
        return line.substring(start, end).trim()
    }

    // ---------- Xtream Codes ----------

    private fun loadXtream(s: Source): List<Channel> {
        val base = s.url.trimEnd('/')
        val u = URLEncoder.encode(s.user, "UTF-8")
        val p = URLEncoder.encode(s.pass, "UTF-8")
        val api = "$base/player_api.php?username=$u&password=$p"

        val categories = HashMap<String, String>()
        try {
            val arr = JSONArray(fetch("$api&action=get_live_categories"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                categories[o.optString("category_id")] = o.optString("category_name")
            }
        } catch (e: Exception) {
            // Categories are optional; channels still load below.
        }

        val streams = JSONArray(fetch("$api&action=get_live_streams"))
        val result = ArrayList<Channel>(streams.length())
        for (i in 0 until streams.length()) {
            val o = streams.getJSONObject(i)
            val id = o.optString("stream_id")
            if (id.isEmpty()) continue
            val name = o.optString("name").ifEmpty { "Channel $id" }
            val logo = o.optString("stream_icon").takeIf { it.startsWith("http") }
            val group = categories[o.optString("category_id")]?.takeIf { it.isNotBlank() } ?: "Other"
            result.add(Channel(name, "$base/live/${s.user}/${s.pass}/$id.ts", logo, group))
        }
        return result
    }

    // ---------- HTTP ----------

    fun fetch(url: String): String {
        var current = url
        var redirects = 0
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            if (code in 300..399 && redirects < 6) {
                val loc = conn.getHeaderField("Location") ?: throw Exception("HTTP $code")
                conn.disconnect()
                current = URL(URL(current), loc).toString()
                redirects++
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw Exception("HTTP $code")
            }
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8), 65536).use { r ->
                val buf = CharArray(65536)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
            }
            conn.disconnect()
            return sb.toString()
        }
    }
}
