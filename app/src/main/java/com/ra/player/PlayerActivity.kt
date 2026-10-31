package com.ra.player

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.ra.player.databinding.ActivityPlayerBinding
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private enum class Mode { NONE, SEEK, VOLUME, BRIGHTNESS }

    private lateinit var b: ActivityPlayerBinding
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var items: List<VideoItem> = emptyList()
    private val subtitles = HashMap<Int, Uri>()

    // State that survives the player being released in onStop (PiP closed, app backgrounded).
    private var resumeIndex = 0
    private var resumePos = C.TIME_UNSET
    private var resumePlay = true
    private var userSpeed = 1f
    private var announcedResume = false

    private var locked = false
    private var orientationSet = false
    private var resizeIdx = 0
    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val resizeNames = arrayOf("Fit", "Stretch", "Crop")

    // A-B loop
    private var loopA = -1L
    private var loopB = -1L
    private val loopTicker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && loopA >= 0 && loopB > loopA && p.currentPosition >= loopB) {
                p.seekTo(loopA)
            }
            handler.postDelayed(this, 150)
        }
    }

    // Touch gesture state
    private var mode = Mode.NONE
    private var startPos = 0L
    private var seekTarget = -1L
    private var startVolume = 0f
    private var startBrightness = 0.5f
    private var longPressing = false
    private var prevSpeed = 1f
    private var scaleAcc = 1f
    private val hideHud = Runnable { b.hud.visibility = View.GONE }

    private val pickSubtitle =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) attachSubtitle(uri)
        }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupWindow()

        val fromIntent = intent?.action == Intent.ACTION_VIEW && intent.data != null
        items = if (fromIntent) {
            val uri = intent.data!!
            listOf(VideoItem(uri, queryName(uri) ?: uri.lastPathSegment ?: "Video", 0, 0, "", 0, 0))
        } else {
            PlaylistHolder.items
        }
        if (items.isEmpty()) {
            finish()
            return
        }
        resumeIndex = if (fromIntent) 0 else PlaylistHolder.index.coerceIn(0, items.size - 1)
        val saved = Resume.get(this, items[resumeIndex].uri)
        resumePos = if (saved > 0) saved else C.TIME_UNSET

        b.playerView.setShowSubtitleButton(true)
        b.playerView.controllerShowTimeoutMs = 3500
        b.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                b.topBar.visibility =
                    if (visibility == View.VISIBLE && !locked && !isInPictureInPictureMode) View.VISIBLE
                    else View.GONE
            }
        )

        setupButtons()
        setupGestures()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (locked) {
                    toast(getString(R.string.unlock_to_exit))
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (items.isNotEmpty()) {
            initPlayer()
            handler.post(loopTicker)
        }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(loopTicker)
        releasePlayer()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true && !locked && Build.VERSION.SDK_INT >= 26) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        b.playerView.useController = !isInPictureInPictureMode && !locked
        if (isInPictureInPictureMode) b.topBar.visibility = View.GONE
    }

    // ------------------------------------------------------------------ player

    private fun initPlayer() {
        val p = ExoPlayer.Builder(this)
            .setRenderersFactory(DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        p.addListener(listener)
        b.playerView.player = p
        p.setMediaItems(items.mapIndexed { i, v -> mediaItem(i, v) }, resumeIndex, resumePos)
        p.playbackParameters = PlaybackParameters(userSpeed)
        p.playWhenReady = resumePlay
        p.prepare()
        player = p
        b.title.text = items[resumeIndex].title

        if (!announcedResume && resumePos != C.TIME_UNSET) {
            announcedResume = true
            showHud(getString(R.string.resumed_at, Fmt.time(resumePos)))
        }
    }

    private fun releasePlayer() {
        val p = player ?: return
        if (longPressing) {
            p.setPlaybackSpeed(prevSpeed)
            longPressing = false
        }
        resumeIndex = p.currentMediaItemIndex
        resumePos = p.currentPosition
        resumePlay = p.playWhenReady
        userSpeed = p.playbackParameters.speed
        items.getOrNull(resumeIndex)?.let { Resume.save(this, it.uri, p.currentPosition, p.duration) }
        p.release()
        b.playerView.player = null
        player = null
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            b.title.text = mediaItem?.mediaMetadata?.title ?: ""
            loopA = -1
            loopB = -1
            updateLoopLabel()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (!orientationSet && videoSize.width > 0 && videoSize.height > 0) {
                orientationSet = true
                requestedOrientation = if (videoSize.width >= videoSize.height)
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (!longPressing) userSpeed = playbackParameters.speed
        }

        override fun onPlayerError(error: PlaybackException) {
            toast(getString(R.string.playback_error, error.errorCodeName))
        }
    }

    private fun mediaItem(index: Int, v: VideoItem): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(v.uri)
            .setMediaId(v.uri.toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(v.title).build())
        subtitles[index]?.let { sub ->
            val mime = subtitleMime(queryName(sub) ?: sub.toString())
            if (mime != null) {
                builder.setSubtitleConfigurations(
                    listOf(
                        MediaItem.SubtitleConfiguration.Builder(sub)
                            .setMimeType(mime)
                            .setLanguage("und")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()
                    )
                )
            }
        }
        return builder.build()
    }

    // ------------------------------------------------------------------ subtitles

    private fun subtitleMime(name: String): String? =
        when (name.substringAfterLast('.', "").lowercase()) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "ttml", "xml", "dfxp" -> MimeTypes.APPLICATION_TTML
            else -> null
        }

    private fun attachSubtitle(uri: Uri) {
        val name = queryName(uri) ?: uri.toString()
        if (subtitleMime(name) == null) {
            toast(getString(R.string.subtitle_unsupported))
            return
        }
        val p = player ?: return
        val index = p.currentMediaItemIndex
        val pos = p.currentPosition
        subtitles[index] = uri
        p.setMediaItems(items.mapIndexed { i, v -> mediaItem(i, v) }, index, pos)
        p.prepare()
        toast(getString(R.string.subtitle_loaded, name))
    }

    // ------------------------------------------------------------------ buttons

    private fun setupButtons() {
        b.back.setOnClickListener { finish() }

        b.lock.setOnClickListener { setLocked(true) }
        b.unlock.setOnClickListener { setLocked(false) }

        b.rotate.setOnClickListener {
            requestedOrientation =
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        b.aspect.setOnClickListener { cycleResize() }

        b.pip.setOnClickListener { enterPip() }

        b.subtitle.setOnClickListener { pickSubtitle.launch(arrayOf("*/*")) }

        b.loop.setOnClickListener { onLoopClick() }
        updateLoopLabel()
    }

    private fun setLocked(value: Boolean) {
        locked = value
        b.playerView.useController = !value
        if (value) {
            b.playerView.hideController()
            b.topBar.visibility = View.GONE
            b.unlock.visibility = View.VISIBLE
        } else {
            b.unlock.visibility = View.GONE
            b.playerView.useController = true
            b.playerView.showController()
        }
    }

    private fun cycleResize() {
        resizeIdx = (resizeIdx + 1) % resizeModes.size
        b.playerView.resizeMode = resizeModes[resizeIdx]
        showHud(resizeNames[resizeIdx])
    }

    private fun onLoopClick() {
        val p = player ?: return
        when {
            loopA < 0 -> {
                loopA = p.currentPosition
                showHud(getString(R.string.loop_a_set, Fmt.time(loopA)))
            }
            loopB < 0 -> {
                if (p.currentPosition > loopA + 500) {
                    loopB = p.currentPosition
                    p.seekTo(loopA)
                    showHud(getString(R.string.loop_on, Fmt.time(loopA), Fmt.time(loopB)))
                } else {
                    toast(getString(R.string.loop_b_later))
                }
            }
            else -> {
                loopA = -1
                loopB = -1
                showHud(getString(R.string.loop_off))
            }
        }
        updateLoopLabel()
    }

    private fun updateLoopLabel() {
        b.loop.text = when {
            loopA < 0 -> "A-B"
            loopB < 0 -> "A-"
            else -> "A↻B"
        }
        val active = loopA >= 0
        b.loop.setTextColor(
            getColor(if (active) R.color.accent else android.R.color.white)
        )
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26 ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            toast(getString(R.string.pip_unsupported))
            return
        }
        val size = player?.videoSize
        val ratio = if (size != null && size.width > 0 && size.height > 0) {
            val r = size.width.toFloat() / size.height
            when {
                r > 2.39f -> Rational(239, 100)
                r < 0.42f -> Rational(42, 100)
                else -> Rational(size.width, size.height)
            }
        } else Rational(16, 9)
        try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build())
        } catch (e: Exception) {
            toast(getString(R.string.pip_unsupported))
        }
    }

    // ------------------------------------------------------------------ gestures

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (b.playerView.isControllerFullyVisible) b.playerView.hideController()
                else b.playerView.showController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = b.playerView.width.toFloat()
                when {
                    e.x < w / 3f -> seekBy(-10_000)
                    e.x > w * 2f / 3f -> seekBy(10_000)
                    else -> player?.let { if (it.isPlaying) it.pause() else it.play() }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val p = player ?: return
                prevSpeed = p.playbackParameters.speed
                longPressing = true
                p.setPlaybackSpeed(2f)
                showHud("2×", persistent = true)
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
            ): Boolean {
                if (e1 == null || longPressing) return false
                val w = b.playerView.width.toFloat()
                val h = b.playerView.height.toFloat()
                if (w <= 0f || h <= 0f) return false

                if (mode == Mode.NONE) {
                    val p = player ?: return false
                    mode = when {
                        abs(e2.x - e1.x) > abs(e2.y - e1.y) -> Mode.SEEK
                        e1.x < w / 2f -> Mode.BRIGHTNESS
                        else -> Mode.VOLUME
                    }
                    startPos = p.currentPosition
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
                    startBrightness = currentBrightness()
                }

                when (mode) {
                    Mode.SEEK -> {
                        val p = player ?: return true
                        val delta = ((e2.x - e1.x) / w * 90_000f).toLong()
                        val dur = if (p.duration > 0) p.duration else Long.MAX_VALUE
                        seekTarget = (startPos + delta).coerceIn(0L, dur)
                        val shift = seekTarget - startPos
                        val sign = if (shift >= 0) "+" else "-"
                        showHud("$sign${Fmt.time(abs(shift))}  [${Fmt.time(seekTarget)}]", persistent = true)
                    }
                    Mode.VOLUME -> {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val v = (startVolume + (e1.y - e2.y) / h * 1.3f).coerceIn(0f, 1f)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).roundToInt(), 0)
                        showHud(getString(R.string.volume_pct, (v * 100).roundToInt()))
                    }
                    Mode.BRIGHTNESS -> {
                        val v = (startBrightness + (e1.y - e2.y) / h * 1.3f).coerceIn(0.02f, 1f)
                        val lp = window.attributes
                        lp.screenBrightness = v
                        window.attributes = lp
                        showHud(getString(R.string.brightness_pct, (v * 100).roundToInt()))
                    }
                    Mode.NONE -> Unit
                }
                return true
            }
        })

        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                scaleAcc = 1f
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                scaleAcc *= d.scaleFactor
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                val target = when {
                    scaleAcc > 1.15f -> 2 // crop
                    scaleAcc < 0.87f -> 0 // fit
                    else -> resizeIdx
                }
                if (target != resizeIdx) {
                    resizeIdx = target
                    b.playerView.resizeMode = resizeModes[resizeIdx]
                    showHud(resizeNames[resizeIdx])
                }
            }
        })

        b.playerView.setOnTouchListener { _, ev ->
            if (locked) return@setOnTouchListener true
            scale.onTouchEvent(ev)
            if (!scale.isInProgress) detector.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                endGesture()
            }
            true
        }
    }

    private fun endGesture() {
        if (longPressing) {
            player?.setPlaybackSpeed(prevSpeed)
            longPressing = false
            b.hud.visibility = View.GONE
        }
        if (mode == Mode.SEEK && seekTarget >= 0) {
            player?.seekTo(seekTarget)
            handler.removeCallbacks(hideHud)
            handler.postDelayed(hideHud, 600)
        }
        mode = Mode.NONE
        seekTarget = -1
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = if (p.duration > 0) p.duration else Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, dur))
        showHud(if (deltaMs > 0) "+10s" else "-10s")
    }

    private fun currentBrightness(): Float {
        val w = window.attributes.screenBrightness
        if (w >= 0f) return w
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) {
            0.5f
        }
    }

    private fun showHud(text: String, persistent: Boolean = false) {
        b.hud.text = text
        b.hud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHud)
        if (!persistent) handler.postDelayed(hideHud, 800)
    }

    // ------------------------------------------------------------------ helpers

    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, b.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val dp = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val i = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            b.topBar.setPadding(
                i.left + (8 * dp).toInt(), i.top + (4 * dp).toInt(),
                i.right + (8 * dp).toInt(), (4 * dp).toInt()
            )
            insets
        }
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
