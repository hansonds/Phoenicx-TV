package com.hanson.phoenicxtv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor

/** Full-screen live playback. Back returns to the channel list; Up/Down switch channel. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private class Attempt(val url: String, val mimeType: String?)

    private var player: ExoPlayer? = null
    private lateinit var frame: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var progress: ProgressBar
    private lateinit var info: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var channels: List<Channel> = emptyList()
    private var index = 0
    private var attempts: MutableList<Attempt> = ArrayList()
    private var aspect = 0f

    private val hideInfo = Runnable { info.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        frame = findViewById(R.id.frame)
        surface = findViewById(R.id.surface)
        progress = findViewById(R.id.progress)
        info = findViewById(R.id.info)

        val group = intent.getStringExtra(ChannelsActivity.EXTRA_GROUP) ?: ""
        channels = Store.group(this, group)?.channels ?: emptyList()
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, maxOf(0, channels.size - 1))
        if (channels.isEmpty()) { finish(); return }

        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resizeSurface() }
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        if (channels.isEmpty()) return
        // Hardware decoders first; the bundled FFmpeg decoder steps in for audio the device cannot play.
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val p = ExoPlayer.Builder(this, renderers).build()
        p.setVideoSurfaceView(surface)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                progress.visibility =
                    if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    resizeSurface()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onError(error)
            }
        })
        player = p
        play()
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }

    private fun play() {
        val c = channels[index]
        setResult(RESULT_OK, Intent().putExtra(EXTRA_INDEX, index))
        showInfo(c.name)

        // Things to try, in order: as given → forced HLS (playlists without .m3u8) → the other extension.
        attempts = ArrayList()
        val url = c.url
        val lower = url.lowercase()
        attempts.add(Attempt(url, null))
        if (!lower.contains(".m3u8")) attempts.add(Attempt(url, MimeTypes.APPLICATION_M3U8))
        when {
            lower.endsWith(".ts") -> attempts.add(Attempt(url.dropLast(3) + ".m3u8", null))
            lower.endsWith(".m3u8") -> attempts.add(Attempt(url.dropLast(5) + ".ts", null))
        }
        startNext()
    }

    private fun startNext() {
        val p = player ?: return
        val a = attempts.removeAt(0)
        val c = channels[index]
        progress.visibility = View.VISIBLE

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(c.userAgent ?: DEFAULT_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(30000)
        if (c.referer != null) http.setDefaultRequestProperties(mapOf("Referer" to c.referer))

        // Lenient MPEG-TS parsing: start on any keyframe and cope with streams that lack clean access-unit markers.
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            .setTsExtractorMode(TsExtractor.MODE_MULTI_PMT)
            .setTsExtractorTimestampSearchBytes(188 * 2000)

        val item = MediaItem.Builder().setUri(a.url).setMimeType(a.mimeType).build()
        p.setMediaSource(DefaultMediaSourceFactory(http, extractors).createMediaSource(item))
        p.prepare()
        p.playWhenReady = true
    }

    private fun onError(error: PlaybackException) {
        if (attempts.isNotEmpty()) { startNext(); return }
        progress.visibility = View.GONE
        Toast.makeText(this, getString(R.string.player_error) + " (" + describe(error) + ")", Toast.LENGTH_LONG).show()
    }

    /** Short human reason for the failure, shown in the toast so problems can be diagnosed. */
    private fun describe(e: PlaybackException): String {
        var cause: Throwable? = e.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return "HTTP " + cause.responseCode
            if (cause is HttpDataSource.HttpDataSourceException) {
                return if (cause.cause is java.net.SocketTimeoutException) "timeout" else "network error"
            }
            cause = cause.cause
        }
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "timeout"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "no connection"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "unsupported stream format"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "codec not supported"
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "fell behind live"
            else -> e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
        }
    }

    private fun switchChannel(delta: Int) {
        if (channels.size < 2) return
        index = (index + delta + channels.size) % channels.size
        play()
    }

    private fun showInfo(text: String) {
        info.text = text
        info.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 3000)
    }

    private fun resizeSurface() {
        if (aspect <= 0f || frame.width == 0 || frame.height == 0) return
        val w = frame.width
        val h = frame.height
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        if (w.toFloat() / h > aspect) {
            lp.height = h
            lp.width = (h * aspect).toInt()
        } else {
            lp.width = w
            lp.height = (w / aspect).toInt()
        }
        if (lp.width != surface.width || lp.height != surface.height) surface.layoutParams = lp
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { switchChannel(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { switchChannel(1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> {
                showInfo(channels[index].name); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideInfo)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_INDEX = "index"
        // Browser-like UA: some providers refuse unknown players.
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}
