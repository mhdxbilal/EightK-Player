package com.example.eightkplayer

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.preference.PreferenceManager
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.material.bottomsheet.BottomSheetDialog

class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var gestureOverlay: View
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var lockOverlay: View
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView
    private lateinit var btnSpeed: ImageButton
    private lateinit var btnSubtitle: ImageButton
    private lateinit var btnEqualizer: ImageButton
    private lateinit var btnLock: ImageButton
    private lateinit var btnPip: ImageButton
    private lateinit var btnCast: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnDecodingMode: ImageButton
    private lateinit var peekText: TextView
    private lateinit var titleText: TextView

    private lateinit var player: ExoPlayer
    private var isLocked = false
    private var currentSpeed = 1.0f
    private var currentDecodingMode = 2  // 0=HW, 1=SW (unused), 2=Hybrid
    private val modeLabels = arrayOf("HW", "SW", "HW+SW")

    private lateinit var gestureDetector: GestureDetectorCompat
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureStartVolume = 0f
    private var gestureStartBrightness = 0f
    private val peekHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private var currentVideoUri: String? = null
    private var pendingVideoUri: Uri? = null
    private var equalizer: Equalizer? = null
    private var audioSessionId = 0

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingVideoUri?.let { playVideo(it); pendingVideoUri = null }
                ?: filePickerLauncher.launch("video/*")
        } else { toast("Storage permission required"); pendingVideoUri = null }
    }
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { playVideo(it) }
    }
    private val subtitlePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { setSubtitle(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        initViews()
        initPrefs()
        initPlayer()
        initGestures()
        intent?.data?.let { uri ->
            if (hasStoragePermission()) playVideo(uri) else { pendingVideoUri = uri; requestStoragePermission() }
        }
        if (currentVideoUri == null) {
            Handler(Looper.getMainLooper()).postDelayed({ showOpenDialog() }, 500)
        }
    }

    private fun initViews() {
        playerView = findViewById(R.id.player_view)
        gestureOverlay = findViewById(R.id.gesture_overlay)
        topBar = findViewById(R.id.top_bar)
        bottomBar = findViewById(R.id.bottom_bar)
        lockOverlay = findViewById(R.id.lock_overlay)
        seekBar = findViewById(R.id.seek_bar)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        timeCurrent = findViewById(R.id.time_current)
        timeTotal = findViewById(R.id.time_total)
        btnSpeed = findViewById(R.id.btn_speed)
        btnSubtitle = findViewById(R.id.btn_subtitle)
        btnEqualizer = findViewById(R.id.btn_equalizer)
        btnLock = findViewById(R.id.btn_lock)
        btnPip = findViewById(R.id.btn_pip)
        btnCast = findViewById(R.id.btn_cast)
        btnSettings = findViewById(R.id.btn_settings)
        btnDecodingMode = findViewById(R.id.btn_decoding_mode)
        peekText = findViewById(R.id.peek_text)
        titleText = findViewById(R.id.title_text)

        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnSpeed.setOnClickListener { showSpeedDialog() }
        btnSubtitle.setOnClickListener { subtitlePickerLauncher.launch("*/*") }
        btnEqualizer.setOnClickListener { showEqualizerDialog() }
        btnLock.setOnClickListener { toggleLock() }
        btnPip.setOnClickListener { enterPipMode() }
        btnCast.setOnClickListener { toast("Cast coming soon") }
        btnSettings.setOnClickListener { showOpenDialog() }
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        btnDecodingMode.setOnClickListener {
            currentDecodingMode = (currentDecodingMode + 1) % 3
            toast("Decoding: ${modeLabels[currentDecodingMode]}")
            // With only hardware decoding, mode change is just informational
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) { val d = player.duration; if (d > 0) player.seekTo((d * progress / 1000).toLong()) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun initPlayer() {
        // Use default renderers – hardware decoding only
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                parameters.buildUpon()
                    .setMaxVideoSize(7680, 4320)
                    .setMaxVideoBitrate(100_000_000)
                    .build()
            )
        }

        player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(50000, 120000, 2500, 5000)
                    .build()
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .build()

        playerView.player = player

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    updateSeekBarAndTime()
                    updatePlayPauseIcon()
                    audioSessionId = player.audioSessionId
                    initEqualizer()
                }
                if (state == Player.STATE_ENDED) {
                    prefs.edit().remove("resume_position").apply()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                toast("Error: ${error.message}")
            }
        })

        Handler(Looper.getMainLooper()).post(object : Runnable {
            override fun run() {
                if (player.playbackState == Player.STATE_READY) updateSeekBarAndTime()
                Handler(Looper.getMainLooper()).postDelayed(this, 500)
            }
        })

        currentVideoUri?.let { uri ->
            player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            player.prepare()
            player.playWhenReady = true
        }
    }

    private fun playVideo(uri: Uri) {
        currentVideoUri = uri.toString()
        titleText.text = uri.lastPathSegment ?: "Video"
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
        updatePlayPauseIcon()
        prefs.edit().putString("last_video", uri.toString()).apply()
    }

    private fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
        updatePlayPauseIcon()
    }

    private fun updatePlayPauseIcon() {
        btnPlayPause.setImageResource(if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
    }

    private fun updateSeekBarAndTime() {
        val d = player.duration
        val p = player.currentPosition
        if (d > 0) {
            seekBar.progress = (p * 1000 / d).toInt()
            timeCurrent.text = formatTime(p)
            timeTotal.text = "/ ${formatTime(d)}"
        }
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    private fun toggleControls() {
        topBar.visibility = if (topBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        bottomBar.visibility = if (bottomBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun toggleLock() {
        isLocked = !isLocked
        lockOverlay.visibility = if (isLocked) View.VISIBLE else View.GONE
        btnLock.setImageResource(if (isLocked) android.R.drawable.ic_lock_lock else android.R.drawable.ic_lock_idle_lock)
        gestureOverlay.isEnabled = !isLocked
        if (isLocked) {
            topBar.visibility = View.GONE
            bottomBar.visibility = View.GONE
        } else {
            topBar.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
        }
    }

    private fun showSpeedDialog() {
        val speeds = arrayOf("0.25x", "0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x", "3.0x")
        val values = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
        // Convert to List to use indexOf
        val checked = values.toList().indexOf(currentSpeed).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Speed")
            .setSingleChoiceItems(speeds, checked) { dialog, which ->
                currentSpeed = values[which]
                player.setPlaybackSpeed(currentSpeed)
                dialog.dismiss()
                toast("Speed: ${speeds[which]}")
            }
            .show()
    }

    private fun setSubtitle(uri: Uri) {
        toast("Subtitle loaded: $uri")
        // Implementation would parse and set subtitle track
    }

    private fun initEqualizer() {
        if (audioSessionId != 0) {
            try {
                equalizer = Equalizer(0, audioSessionId)
                equalizer?.enabled = true
            } catch (e: Exception) { /* no EQ */ }
        }
    }

    private fun showEqualizerDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_equalizer, null)
        dialog.setContentView(view)
        val bands = equalizer?.numberOfBands ?: 0
        if (bands > 0) {
            val seekBars = listOf(
                view.findViewById<SeekBar>(R.id.eq_band0),
                view.findViewById<SeekBar>(R.id.eq_band1),
                view.findViewById<SeekBar>(R.id.eq_band2),
                view.findViewById<SeekBar>(R.id.eq_band3),
                view.findViewById<SeekBar>(R.id.eq_band4)
            )
            val min = equalizer?.bandLevelRange?.get(0) ?: -1000
            val max = equalizer?.bandLevelRange?.get(1) ?: 1000
            for (i in 0 until bands.coerceAtMost(5)) {
                seekBars[i].max = max - min
                seekBars[i].progress = (equalizer?.getBandLevel(i.toShort()) ?: 0) - min
                seekBars[i].setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) equalizer?.setBandLevel(i.toShort(), (progress + min).toShort())
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
        }
        dialog.show()
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        } else {
            toast("PIP requires Android 8+")
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        topBar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        bottomBar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initGestures() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (e.x < gestureOverlay.width / 2) {
                    player.seekTo(player.currentPosition - 10000)
                    toast("⏪ -10s")
                } else {
                    player.seekTo(player.currentPosition + 10000)
                    toast("⏩ +10s")
                }
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                peekText.text = "👆 ${formatTime(player.currentPosition)}"
                peekText.visibility = View.VISIBLE
                peekHandler.removeCallbacksAndMessages(null)
                peekHandler.postDelayed({ peekText.visibility = View.GONE }, 1500)
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isLocked) toggleControls()
                return true
            }
        })

        gestureOverlay.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStartX = event.x
                    gestureStartY = event.y
                    gestureStartVolume = getCurrentVolume()
                    gestureStartBrightness = getCurrentBrightness()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - gestureStartX
                    val dy = event.y - gestureStartY
                    if (Math.abs(dx) > 50 || Math.abs(dy) > 50) {
                        when {
                            Math.abs(dx) > Math.abs(dy) -> {
                                val d = player.duration
                                if (d > 0) {
                                    val delta = (dx / gestureOverlay.width * d).toLong()
                                    player.seekTo((player.currentPosition + delta).coerceIn(0, d))
                                    peekText.text = formatTime(player.currentPosition)
                                    peekText.visibility = View.VISIBLE
                                    peekHandler.removeCallbacksAndMessages(null)
                                    peekHandler.postDelayed({ peekText.visibility = View.GONE }, 1000)
                                }
                            }
                            event.x < gestureOverlay.width / 2 -> {
                                val brightness = (gestureStartBrightness - dy / gestureOverlay.height).coerceIn(0f, 1f)
                                setBrightness(brightness)
                                peekText.text = "☀️ ${(brightness * 100).toInt()}%"
                                peekText.visibility = View.VISIBLE
                                peekHandler.removeCallbacksAndMessages(null)
                                peekHandler.postDelayed({ peekText.visibility = View.GONE }, 1000)
                            }
                            else -> {
                                val volume = (gestureStartVolume - dy / gestureOverlay.height).coerceIn(0f, 1f)
                                setVolume(volume)
                                peekText.text = "🔊 ${(volume * 100).toInt()}%"
                                peekText.visibility = View.VISIBLE
                                peekHandler.removeCallbacksAndMessages(null)
                                peekHandler.postDelayed({ peekText.visibility = View.GONE }, 1000)
                            }
                        }
                    }
                }
            }
            true
        }
    }

    private fun getCurrentVolume(): Float {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        return am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() /
                am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
    }

    private fun setVolume(level: Float) {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        am.setStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            (level * am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)).toInt(),
            0
        )
    }

    private fun getCurrentBrightness(): Float {
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) { 0.5f }
    }

    private fun setBrightness(level: Float) {
        val lp = window.attributes
        lp.screenBrightness = level.coerceIn(0.01f, 1f)
        window.attributes = lp
    }

    private fun showOpenDialog() {
        AlertDialog.Builder(this)
            .setTitle("Open Video")
            .setItems(arrayOf("Open from Gallery", "Open URL (Network)")) { _, which ->
                when (which) {
                    0 -> if (hasStoragePermission()) filePickerLauncher.launch("video/*") else requestStoragePermission()
                    1 -> showUrlInputDialog()
                }
            }
            .show()
    }

    private fun showUrlInputDialog() {
        val input = EditText(this)
        input.hint = "Enter video URL"
        AlertDialog.Builder(this)
            .setTitle("Network Stream")
            .setView(input)
            .setPositiveButton("Play") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) playVideo(Uri.parse(url))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        requestPermissionLauncher.launch(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.READ_MEDIA_VIDEO
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
        )
    }

    private fun initPrefs() {
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val pos = prefs.getLong("resume_position", 0)
        if (pos > 0 && ::player.isInitialized) player.seekTo(pos)
    }

    override fun onPause() {
        super.onPause()
        if (::player.isInitialized && player.playbackState == Player.STATE_READY) {
            prefs.edit().putLong("resume_position", player.currentPosition).apply()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) player.release()
        equalizer?.release()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
