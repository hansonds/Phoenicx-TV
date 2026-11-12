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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/** Full-screen live playback. Back returns to the channel list; Up/Down switch channel. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var frame: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var progress: ProgressBar
    private lateinit var info: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var channels: List<Channel> = emptyList()
    private var index = 0
    private var triedAlternate = false
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
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android) PhoenicxTV")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
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
                onError()
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
        triedAlternate = false
        setResult(RESULT_OK, Intent().putExtra(EXTRA_INDEX, index))
        showInfo(c.name)
        start(c.url)
    }

    private fun start(url: String) {
        val p = player ?: return
        progress.visibility = View.VISIBLE
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
    }

    /** Xtream servers often only answer one of .ts / .m3u8 — try the other once before giving up. */
    private fun onError() {
        val url = channels[index].url
        if (!triedAlternate) {
            triedAlternate = true
            val alt = when {
                url.endsWith(".ts") -> url.removeSuffix(".ts") + ".m3u8"
                url.endsWith(".m3u8") -> url.removeSuffix(".m3u8") + ".ts"
                else -> null
            }
            if (alt != null) { start(alt); return }
        }
        progress.visibility = View.GONE
        Toast.makeText(this, R.string.player_error, Toast.LENGTH_SHORT).show()
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
    }
}
