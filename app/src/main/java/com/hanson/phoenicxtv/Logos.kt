package com.hanson.phoenicxtv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Tiny channel-logo loader (memory + disk cache). Replaces a full image library. */
object Logos {
    private const val MAX_PX = 160
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(view: ImageView, url: String?) {
        view.tag = url
        view.setImageResource(R.drawable.logo_placeholder)
        if (url.isNullOrBlank() || !url.startsWith("http")) return
        memory.get(url)?.let { view.setImageBitmap(it); return }
        val dir = view.context.applicationContext.cacheDir
        pool.execute {
            val bmp = try { fromDisk(dir, url) ?: download(dir, url) } catch (e: Exception) { null }
            if (bmp != null) {
                memory.put(url, bmp)
                main.post { if (view.tag == url) view.setImageBitmap(bmp) }
            }
        }
    }

    private fun file(dir: File, url: String) = File(dir, "logo_" + Integer.toHexString(url.hashCode()))

    private fun fromDisk(dir: File, url: String): Bitmap? {
        val f = file(dir, url)
        if (!f.exists() || f.length() == 0L) return null
        return decode(f.readBytes())
    }

    private fun download(dir: File, url: String): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) PhoenicxTV")
        try {
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > 2 * 1024 * 1024) return null
            val bmp = decode(bytes) ?: return null
            try { file(dir, url).writeBytes(bytes) } catch (e: Exception) { }
            return bmp
        } finally {
            conn.disconnect()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_PX && bounds.outHeight / (sample * 2) >= MAX_PX) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
