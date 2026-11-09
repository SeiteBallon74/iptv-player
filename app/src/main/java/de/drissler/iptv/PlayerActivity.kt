package de.drissler.iptv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.load

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var loading: ProgressBar
    private lateinit var overlay: View
    private lateinit var ovLogo: ImageView
    private lateinit var ovName: TextView
    private lateinit var ovInfo: TextView
    private lateinit var error: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    private var retries = 0
    private var forcedHls = false
    private var resolution = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ChannelStore.playing.isEmpty()) { finish(); return }
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.playerView)
        loading = findViewById(R.id.loading)
        overlay = findViewById(R.id.overlay)
        ovLogo = findViewById(R.id.ovLogo)
        ovName = findViewById(R.id.ovName)
        ovInfo = findViewById(R.id.ovInfo)
        error = findViewById(R.id.error)

        playerView.useController = false
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        playerView.setKeepContentOnPlayerReset(true)
        playerView.setShutterBackgroundColor(0xFF000000.toInt())
    }

    override fun onStart() {
        super.onStart()
        if (ChannelStore.playing.isEmpty()) return
        initPlayer()
        play(ChannelStore.index)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    private fun initPlayer() {
        // min, max, Start, Start nach Hänger (ms)
        val b = when (Prefs.bufferMode(this)) {
            0 -> intArrayOf(6_000, 30_000, 1_000, 2_000)
            2 -> intArrayOf(30_000, 120_000, 3_000, 8_000)
            else -> intArrayOf(15_000, 60_000, 1_500, 4_000)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(b[0], b[1], b[2], b[3])
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        player = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .build()
            .also { p ->
                p.addListener(listener)
                p.playWhenReady = true
                playerView.player = p
            }
    }

    private fun play(i: Int, asHls: Boolean = false) {
        val p = player ?: return
        val list = ChannelStore.playing
        if (list.isEmpty()) return
        val idx = ((i % list.size) + list.size) % list.size
        val ch = list[idx]
        ChannelStore.index = idx
        forcedHls = asHls
        if (!asHls) retries = 0
        resolution = ""
        handler.removeCallbacksAndMessages(null)
        error.visibility = View.GONE

        val headers = HashMap(ch.headers)
        val ua = headers.remove("User-Agent") ?: Prefs.userAgent(this)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(headers)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http), extractors)

        val mime = when {
            asHls -> MimeTypes.APPLICATION_M3U8
            ch.url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            ch.url.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        val item = MediaItem.Builder().setUri(ch.url).apply { if (mime != null) setMimeType(mime) }.build()

        p.setMediaSource(factory.createMediaSource(item))
        p.prepare()
        p.play()
        showOverlay()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            loading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (state == Player.STATE_READY) {
                retries = 0
                error.visibility = View.GONE
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height > 0) {
                resolution = "${videoSize.width}×${videoSize.height}"
                if (overlay.visibility == View.VISIBLE) updateOverlayText()
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            val p = player ?: return
            when {
                e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                    p.seekToDefaultPosition()
                    p.prepare()
                }
                !forcedHls && (e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED) -> {
                    // Link ohne Endung, der in Wahrheit HLS ist
                    play(ChannelStore.index, asHls = true)
                }
                retries < 5 -> {
                    retries++
                    loading.visibility = View.VISIBLE
                    handler.postDelayed({
                        player?.let { it.prepare(); it.play() }
                    }, 1_500L * retries)
                }
                else -> {
                    loading.visibility = View.GONE
                    error.text = "Sender nicht erreichbar\n${e.errorCodeName}\n\n▲ ▼ anderer Sender · Zurück = Liste"
                    error.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showOverlay() {
        val ch = ChannelStore.playing.getOrNull(ChannelStore.index) ?: return
        updateOverlayText()
        if (ch.logo != null) ovLogo.load(ch.logo) { crossfade(false) } else ovLogo.setImageDrawable(null)
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 4_000)
    }

    private fun updateOverlayText() {
        val ch = ChannelStore.playing.getOrNull(ChannelStore.index) ?: return
        ovName.text = "${ChannelStore.index + 1}  ${ch.name}"
        val parts = mutableListOf(ch.group, "${ChannelStore.index + 1}/${ChannelStore.playing.size}")
        if (resolution.isNotEmpty()) parts.add(resolution)
        ovInfo.text = parts.joinToString(" · ")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                play(ChannelStore.index - 1); return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                play(ChannelStore.index + 1); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                showOverlay(); return true
            }
            KeyEvent.KEYCODE_MENU -> {
                val ch = ChannelStore.playing.getOrNull(ChannelStore.index)
                if (ch != null) {
                    val fav = Prefs.toggleFavorite(this, ch.url)
                    ovInfo.text = if (fav) "★ zu Favoriten hinzugefügt" else "aus Favoriten entfernt"
                    overlay.visibility = View.VISIBLE
                    handler.removeCallbacks(hideOverlay)
                    handler.postDelayed(hideOverlay, 3_000)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                player?.let { it.playWhenReady = !it.playWhenReady }; return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
