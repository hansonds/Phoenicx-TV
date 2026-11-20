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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
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
    private var current: Attempt? = null
    private var aspect = 0f

    // Stall watchdog state.
    private var hasPlayed = false          // the current channel has shown video at least once
    private var bufferingSince = 0L        // when the current buffering spell started (0 = not buffering)
    private var lastPosition = -1L
    private var frozenSince = 0L
    private var reconnects = 0

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
        // Live TV: always pick the best quality the device can play instead of ramping up from the lowest.
        val selector = DefaultTrackSelector(this)
        selector.parameters = selector.buildUponParameters()
            .setForceHighestSupportedBitrate(true)
            .build()
        // Start (and resume after a network hiccup) as soon as 1–2 s of video is ready,
        // while keeping up to 50 s buffered ahead to ride out slow patches.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 1000, 2000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val p = ExoPlayer.Builder(this, renderers)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .build()
        p.setVideoSurfaceView(surface)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                progress.visibility =
                    if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (playbackState == Player.STATE_BUFFERING) {
                    if (bufferingSince == 0L) bufferingSince = now()
                } else {
                    bufferingSince = 0L
                }
                if (playbackState == Player.STATE_READY) {
                    hasPlayed = true
                    reconnects = 0
                }
                // A live stream should never "end": treat it as a dropped connection.
                if (playbackState == Player.STATE_ENDED) scheduleReconnect()
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
        handler.post(watchdog)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(reconnectNow)
        player?.release()
        player = null
    }

    /**
     * Checks the player once a second and reconnects by itself when playback is stuck:
     * buffering for too long, or the picture frozen while the player claims to be playing.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            val p = player ?: return
            val t = now()
            if (p.playWhenReady && current != null) {
                when (p.playbackState) {
                    Player.STATE_BUFFERING -> {
                        val limit = if (hasPlayed) STALL_RECONNECT_MS else FIRST_START_LIMIT_MS
                        if (bufferingSince != 0L && t - bufferingSince > limit) {
                            bufferingSince = 0L
                            if (hasPlayed) reconnect() else tryNextOrFail(null)
                        }
                    }
                    Player.STATE_READY -> {
                        val pos = p.currentPosition
                        if (pos != lastPosition) {
                            lastPosition = pos
                            frozenSince = t
                        } else if (t - frozenSince > FROZEN_RECONNECT_MS) {
                            frozenSince = t
                            reconnect()
                        }
                    }
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val reconnectNow = Runnable { reconnect() }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnectNow)
        // 1 s for the first few tries, then back off a little so a dead server isn't hammered.
        val delay = if (reconnects < 5) 1000L else 3000L
        handler.postDelayed(reconnectNow, delay)
    }

    /** Reloads the current stream from the live edge. */
    private fun reconnect() {
        val a = current ?: return
        reconnects++
        showInfo(getString(R.string.player_reconnecting))
        load(a)
    }

    private fun now() = android.os.SystemClock.elapsedRealtime()

    private fun play() {
        val c = channels[index]
        setResult(RESULT_OK, Intent().putExtra(EXTRA_INDEX, index))
        showInfo(c.name)
        aspect = 0f

        // Things to try, in order: as given → forced HLS (playlists without .m3u8) → the other extension.
        attempts = ArrayList()
        hasPlayed = false
        reconnects = 0
        handler.removeCallbacks(reconnectNow)
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
        val a = attempts.removeAt(0)
        current = a
        load(a)
    }

    private fun load(a: Attempt) {
        val p = player ?: return
        val c = channels[index]
        progress.visibility = View.VISIBLE
        bufferingSince = now()
        lastPosition = -1L
        frozenSince = now()

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(c.userAgent ?: DEFAULT_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(15000)
        if (c.referer != null) http.setDefaultRequestProperties(mapOf("Referer" to c.referer))

        // Lenient MPEG-TS parsing for streams that lack clean access-unit markers.
        // (Starting on non-IDR keyframes is deliberately NOT enabled: it makes the first seconds blocky.)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
            .setTsExtractorMode(TsExtractor.MODE_MULTI_PMT)
            .setTsExtractorTimestampSearchBytes(188 * 2000)

        val item = MediaItem.Builder().setUri(a.url).setMimeType(a.mimeType).build()
        val factory = DefaultMediaSourceFactory(http, extractors)
            .setLoadErrorHandlingPolicy(QuickRetryPolicy())
        p.setMediaSource(factory.createMediaSource(item))
        p.prepare()
        p.playWhenReady = true
    }

    private fun onError(error: PlaybackException) {
        // Fell behind the live window (e.g. after a long network pause): jump back to live.
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player?.seekToDefaultPosition()
            player?.prepare()
            return
        }
        // The channel was already playing: it's a network drop, keep reconnecting.
        if (hasPlayed) { scheduleReconnect(); return }
        tryNextOrFail(error)
    }

    /** Before the channel has ever played: try the next way of opening it, or give up with a reason. */
    private fun tryNextOrFail(error: PlaybackException?) {
        if (attempts.isNotEmpty()) { startNext(); return }
        current = null
        player?.stop()
        progress.visibility = View.GONE
        val reason = if (error != null) describe(error) else "timeout"
        Toast.makeText(this, getString(R.string.player_error) + " (" + reason + ")", Toast.LENGTH_LONG).show()
    }

    /** Retries a failed network request after 1 s instead of the default growing delay. */
    private class QuickRetryPolicy : DefaultLoadErrorHandlingPolicy(3) {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val d = super.getRetryDelayMsFor(info)
            return if (d == androidx.media3.common.C.TIME_UNSET) d else 1000L
        }
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
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(reconnectNow)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_INDEX = "index"
        private const val STALL_RECONNECT_MS = 6000L   // buffering this long mid-play → reconnect
        private const val FROZEN_RECONNECT_MS = 5000L  // picture not moving this long → reconnect
        private const val FIRST_START_LIMIT_MS = 15000L // channel never started → try next way / fail
        // Browser-like UA: some providers refuse unknown players.
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}
