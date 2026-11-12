package com.hanson.phoenicxtv

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Saved source + cached channel list, so the app opens instantly without re-downloading. */
object Store {
    private const val PREFS = "phoenicxtv"
    private const val KEY_SOURCE = "source"
    private const val CACHE_FILE = "channels.json"

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var channels: List<Channel>? = null

    fun source(ctx: Context): Source? =
        Source.fromJson(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SOURCE, null))

    fun saveSource(ctx: Context, s: Source) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SOURCE, s.toJson()).apply()
    }

    /** Channels from memory or disk cache; null if nothing has been loaded yet. */
    fun cached(ctx: Context): List<Channel>? {
        channels?.let { return it }
        val f = File(ctx.filesDir, CACHE_FILE)
        if (!f.exists()) return null
        return try {
            val arr = JSONArray(f.readText())
            val list = ArrayList<Channel>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Channel(
                        o.getString("n"), o.getString("u"),
                        if (o.has("l")) o.getString("l") else null,
                        o.optString("g", "Other"),
                        if (o.has("a")) o.getString("a") else null,
                        if (o.has("r")) o.getString("r") else null
                    )
                )
            }
            channels = list
            list
        } catch (e: Exception) {
            null
        }
    }

    fun groups(ctx: Context): List<Group> = Playlist.groupsOf(cached(ctx) ?: emptyList())

    fun group(ctx: Context, name: String): Group? = groups(ctx).firstOrNull { it.name == name }

    /** Downloads the source on a background thread and caches the result. Callbacks run on the main thread. */
    fun refresh(ctx: Context, source: Source, onDone: (List<Channel>) -> Unit, onError: (String) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            try {
                val list = Playlist.load(source)
                if (list.isEmpty()) {
                    main.post { onError(app.getString(R.string.setup_error_none)) }
                    return@execute
                }
                val arr = JSONArray()
                for (c in list) {
                    val o = JSONObject().put("n", c.name).put("u", c.url).put("g", c.group)
                    if (c.logo != null) o.put("l", c.logo)
                    if (c.userAgent != null) o.put("a", c.userAgent)
                    if (c.referer != null) o.put("r", c.referer)
                    arr.put(o)
                }
                File(app.filesDir, CACHE_FILE).writeText(arr.toString())
                channels = list
                main.post { onDone(list) }
            } catch (e: Exception) {
                main.post { onError(e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}
