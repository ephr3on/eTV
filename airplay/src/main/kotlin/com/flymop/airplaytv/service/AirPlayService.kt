/*
 * AirPlay TV - Open-source AirPlay receiver for Android TV
 *
 * Based on android-airplay-server by jqssun (GPLv3) and UxPlay (GPLv3).
 * Modified and optimized by flymop (2026) for Android TV Leanback experience.
 *
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 *
 * Changed for eTV (2026): runs in its own process (":airplay"), so a problem in the native engine
 * can never take the launcher down with it; opens eTV's AirPlay screen when something starts
 * playing (not on every connection); stays quiet during the athan (ACTION_HOLD); keeps the CPU
 * awake only while a device is connected; starts again when the network comes back; and leaves
 * a one-line status file for eTV's settings.
 */

package com.flymop.airplaytv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.flymop.airplaytv.Prefs
import com.flymop.airplaytv.R
import com.flymop.airplaytv.realDisplaySize
import com.flymop.airplaytv.audio.DacpController
import com.flymop.airplaytv.audio.DacpPlayer
import com.flymop.airplaytv.audio.DmapParser
import com.flymop.airplaytv.audio.TrackInfo
import com.flymop.airplaytv.audio.VolumeBroadcast
import com.flymop.airplaytv.bridge.LogListener
import com.flymop.airplaytv.bridge.NativeBridge
import com.flymop.airplaytv.bridge.RaopCallbackHandler
import com.flymop.airplaytv.discovery.NsdServiceManager
import com.flymop.airplaytv.renderer.AirPlayVideoPlayer
import com.flymop.airplaytv.renderer.AudioRenderer
import com.flymop.airplaytv.renderer.VideoRenderer
import com.flymop.airplaytv.viewmodel.DebugInfo
import java.net.NetworkInterface
import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

data class VideoPlaybackInfo(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = true,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val buffering: Boolean = false,
)

class AirPlayService : LifecycleService(), RaopCallbackHandler, LogListener {

    private var nativeHandle = 0L
    private var nsdManager: NsdServiceManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED

    val videoRenderer = VideoRenderer(this)
    val audioRenderer = AudioRenderer()
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val airPlayVideoPlayer by lazy { AirPlayVideoPlayer(this) }

    // hls urls point at the native httpd, valid only while the session lives
    private val _videoLocation = MutableStateFlow<String?>(null)
    val videoLocation = _videoLocation.asStateFlow()

    // eTV changes these settings from its own process: MODE_MULTI_PROCESS reads them afresh.
    @Suppress("DEPRECATION")
    private val prefs: SharedPreferences
        get() = getSharedPreferences(Prefs.NAME, Context.MODE_MULTI_PROCESS)
    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState = _serverState.asStateFlow()

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount = _connectionCount.asStateFlow()

    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect = _videoAspect.asStateFlow()

    private val _videoResolution = MutableStateFlow("")
    val videoResolution = _videoResolution.asStateFlow()

    private val _audioOnly = MutableStateFlow(false)
    val audioOnly = _audioOnly.asStateFlow()

    private val _videoPlaybackActive = MutableStateFlow(false)
    val videoPlaybackActive = _videoPlaybackActive.asStateFlow()

    private val _videoPlaybackInfo = MutableStateFlow(VideoPlaybackInfo())
    val videoPlaybackInfo = _videoPlaybackInfo.asStateFlow()

    // bumped per /play so the ui resets transport state on back-to-back plays too
    private val _videoPlaySeq = MutableStateFlow(0L)
    val videoPlaySeq = _videoPlaySeq.asStateFlow()

    private val _videoPlaybackAspect = MutableStateFlow(16f / 9f)
    val videoPlaybackAspect = _videoPlaybackAspect.asStateFlow()

    private val _videoPlaybackSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoPlaybackSize = _videoPlaybackSize.asStateFlow()

    // container/manifest metadata
    private val _videoTitle = MutableStateFlow("")
    val videoTitle = _videoTitle.asStateFlow()

    // recent /playback-info polls with no playback = pending video; polls start ~1s before /play
    @Volatile private var _lastVideoPollAt = 0L
    @Volatile private var _videoPollSuppressed = false

    fun videoSessionPending(): Boolean =
        !_videoPlaybackActive.value && !_audioOnly.value && !_mirroringActive.value &&
            !_videoPollSuppressed &&
            SystemClock.elapsedRealtime() - _lastVideoPollAt < VIDEO_POLL_PENDING_TIMEOUT_MS

    // set once mirroring reports a real size; stops with session
    private val _mirroringActive = MutableStateFlow(false)
    val mirroringActive = _mirroringActive.asStateFlow()
    val mirrorRunning = _mirroringActive.asStateFlow()

    private val _serverName by lazy {
        MutableStateFlow(Prefs.getServerName(prefs))
    }
    val serverName get() = _serverName.asStateFlow()

    private val _serverPort = MutableStateFlow(7000)
    val serverPort = _serverPort.asStateFlow()

    private val _coverArt = MutableStateFlow<ByteArray?>(null)
    val coverArt = _coverArt.asStateFlow()

    private val _activeRemotePin = MutableStateFlow<String?>(null)
    val activeRemotePin = _activeRemotePin.asStateFlow()

    private val _trackInfo = MutableStateFlow(TrackInfo())
    val trackInfo = _trackInfo.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs = _durationMs.asStateFlow()

    private val _playing = MutableStateFlow(true)
    val playing = _playing.asStateFlow()

    @Volatile private var _progressBaseMs = 0L
    @Volatile private var _progressBaseTime = 0L

    fun currentPositionMs(): Long {
        if (_progressBaseTime == 0L || !_playing.value) return _positionMs.value
        val elapsed = SystemClock.elapsedRealtime() - _progressBaseTime
        return (_progressBaseMs + elapsed).coerceIn(0, _durationMs.value)
    }

    var dacpController: DacpController? = null
        private set
    lateinit var dacpPlayer: DacpPlayer
        private set
    private val _mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var _coverArtBytes: ByteArray? = null
    private var mediaSession: MediaSessionCompat? = null
    private var mediaReceiver: BroadcastReceiver? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var _pendingVolEchoes = 0
    // senders park volume at -30 as route closes; session must not leave device silenced
    private var _preZeroIdx = -1
    @Volatile private var _senderFrac = -1f
    private var _volSyncTarget = -1f
    private var _volSyncHint = 0
    private var _volSyncDir = 0
    private var _volSyncSteps = 0
    private val _volSyncTimeout = Runnable { _volSyncEnd() }

    var logCallback: ((String) -> Unit)? = null

    @Volatile private var _lastPin: String? = null
    var pinCallback: ((String?) -> Unit)? = null
        set(value) {
            field = value
            // ui replay only: binding the activity must not mint a new native pin
            value?.invoke(_lastPin)
        }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logCallback?.invoke(msg)
    }

    override fun onLog(msg: String) {
        logCallback?.invoke(msg)
    }

    inner class LocalBinder : Binder() {
        val service: AirPlayService
            get() = this@AirPlayService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return LocalBinder()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        dacpController = DacpController(this)
        dacpPlayer = DacpPlayer(
            mainLooper,
            dacp = { dacpController },
            snapshot = {
                DacpPlayer.Snapshot(
                    track = _trackInfo.value,
                    artworkData = _coverArtBytes,
                    durationMs = _durationMs.value,
                    playing = _playing.value,
                    active = _audioOnly.value && _connectionCount.value > 0,
                )
            },
            positionMs = ::currentPositionMs,
            setPlaying = ::_setPlaying,
        )
        mediaSession = MediaSessionCompat(this, SESSION_TAG).apply {
            // OK on eTV's Now playing artwork opens the AirPlay screen again.
            _screenIntent()?.let { setSessionActivity(it) }
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (_videoPlaybackActive.value) {
                        airPlayVideoPlayer.setPlaying(true)
                        return
                    }
                    _setPlaying(true)
                    dacpController?.play()
                }
                override fun onPause() {
                    if (_videoPlaybackActive.value) {
                        airPlayVideoPlayer.setPlaying(false)
                        return
                    }
                    _setPlaying(false)
                    dacpController?.pause()
                }
                override fun onStop() {
                    if (_videoPlaybackActive.value) stopVideoPlayback()
                }
                override fun onFastForward() {
                    if (_videoPlaybackActive.value) airPlayVideoPlayer.seekBy(VIDEO_SEEK_STEP_MS)
                }
                override fun onRewind() {
                    if (_videoPlaybackActive.value) airPlayVideoPlayer.seekBy(-VIDEO_SEEK_STEP_MS)
                }
                override fun onSeekTo(pos: Long) {
                    if (_videoPlaybackActive.value) airPlayVideoPlayer.scrub(pos / 1000f)
                }
                override fun onSkipToNext() {
                    if (!_videoPlaybackActive.value) dacpController?.nextItem()
                }
                override fun onSkipToPrevious() {
                    if (!_videoPlaybackActive.value) dacpController?.prevItem()
                }
            })
        }
        mediaReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_PLAY_PAUSE -> togglePlayPause()
                    ACTION_NEXT -> dacpController?.nextItem()
                    ACTION_PREV -> dacpController?.prevItem()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREV)
        }
        ContextCompat.registerReceiver(this, mediaReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getIntExtra(VolumeBroadcast.EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
                val idx = intent.getIntExtra(VolumeBroadcast.EXTRA_VALUE, -1)
                val prev = intent.getIntExtra(VolumeBroadcast.EXTRA_PREV_VALUE, -1)
                if (idx < 0 || prev < 0 || idx == prev) return
                if (_pendingVolEchoes > 0) { _pendingVolEchoes--; return }
                if (_connectionCount.value == 0) return
                val target = idx.toFloat() / audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                Log.d(TAG, "local volume $prev -> $idx, sync sender to $target")
                _preZeroIdx = -1
                val active = _volSyncTarget >= 0f
                _volSyncTarget = target
                _volSyncHint = if (idx > prev) 1 else -1
                _volSyncDir = 0
                _volSyncSteps = 0
                if (!active) _volSyncStep()
            }
        }
        ContextCompat.registerReceiver(this, volumeReceiver, IntentFilter(VolumeBroadcast.ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED)

        airPlayVideoPlayer.onVideoSize = { width, height, aspect ->
            _videoPlaybackAspect.value = aspect
            _videoPlaybackSize.value = width to height
        }
        airPlayVideoPlayer.onTitle = { _videoTitle.value = it ?: "" }
        airPlayVideoPlayer.onEnded = { _endVideoPlayback("AirPlay Video stopped (player)") }
        airPlayVideoPlayer.onPlaybackInfo = { snapshot ->
            if (nativeHandle != 0L) {
                NativeBridge.nativeUpdatePlaybackInfo(nativeHandle, snapshot.position, snapshot.duration, snapshot.rate, snapshot.ready)
            }
            if (_videoPlaybackActive.value) {
                _updateVideoPlaybackState(snapshot.position, snapshot.rate)
                _videoPlaybackInfo.value = VideoPlaybackInfo(
                    positionMs = (snapshot.position * 1000).toLong(),
                    durationMs = if (snapshot.duration > 0f) (snapshot.duration * 1000).toLong() else 0L,
                    playing = snapshot.playWhenReady,
                    speed = snapshot.speed,
                    skipSilence = snapshot.skipSilence,
                    buffering = snapshot.buffering,
                )
            }
        }
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        lifecycleScope.launch {
            prefs.audioConfigFlow()
                .debounce(AUDIO_CONFIG_DEBOUNCE_MS)
                .collect { audioRenderer.updateConfig(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> {
                promoteToForeground()
                val name = Prefs.getServerName(prefs)
                startServer(name, ensureServiceStarted = false)
                if (_serverState.value != ServerState.RUNNING) stopSelf(startId)
            }
            ACTION_RESTART -> {
                promoteToForeground()
                restartServer()
                if (_serverState.value != ServerState.RUNNING) stopSelf(startId)
            }
            ACTION_HOLD -> {
                // Only meaningful while the receiver runs; never starts it.
                if (_serverState.value == ServerState.RUNNING) {
                    holdForAthan(true, quiet = intent.getBooleanExtra(EXTRA_QUIET, true))
                } else {
                    stopSelf(startId)
                }
            }
            ACTION_RELEASE -> {
                if (_serverState.value == ServerState.RUNNING) {
                    val wasOn = athan
                    holdForAthan(false, intent.getBooleanExtra(EXTRA_RESUME, true))
                    // Something was sent during the athan: show it now.
                    if (wasOn && (_mirroringActive.value || _videoLocation.value != null || _audioOnly.value)) launchScreen()
                } else {
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    fun startServer(name: String) {
        startServer(name, ensureServiceStarted = true)
    }

    private fun startServer(name: String, ensureServiceStarted: Boolean) {
        if (_serverState.value == ServerState.RUNNING) return
        val effectiveName = name.ifBlank { Prefs.getServerName(prefs) }

        nsdManager = NsdServiceManager(this).apply { acquireMulticastLock() }

        val hwAddr = getHwAddr()
        val keyFile = filesDir.resolve("airplay.pem").absolutePath
        val nohold = prefs.getBoolean(Prefs.ALLOW_NEW_CONN, Prefs.DEF_ALLOW_NEW_CONN)
        val requirePin = prefs.getBoolean(Prefs.REQUIRE_PIN, Prefs.DEF_REQUIRE_PIN)

        // oboe's OpenSL ES backend (pre-AAudio devices, API < 27) can't discover native
        // rate / burst size itself; feed it AudioManager values so low-latency buffer
        // sizing works there
        // see: https://github.com/google/oboe/blob/main/docs/GettingStarted.md#obtaining-optimal-latency
        NativeBridge.nativeSetDefaultStreamValues(
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        )
        nativeHandle = NativeBridge.nativeInit(this, hwAddr, effectiveName, keyFile, nohold, requirePin)
        if (nativeHandle == 0L) {
            log("Native init failed")
            lastProblem = "The AirPlay engine didn't start"
            _failStart()
            return
        }
        audioRenderer.attachEngine(nativeHandle)

        // apply settings from preferences
        val maxFps = prefs.getInt(Prefs.MAX_FPS, Prefs.DEF_MAX_FPS)
        val overscanned = prefs.getBoolean(Prefs.OVERSCANNED, Prefs.DEF_OVERSCANNED)
        val audioLatencyMs = prefs.getInt(Prefs.AUDIO_LATENCY_MS, Prefs.DEF_AUDIO_LATENCY_MS)
        val (reqW, reqH) = _displaySize(clamp = false)
        val h265 = videoRenderer.selectDecoders(reqW, reqH, maxFps, prefs.getBoolean(Prefs.H265_ENABLED, Prefs.DEF_H265_ENABLED))
        val alac = prefs.getBoolean(Prefs.ALAC_ENABLED, Prefs.DEF_ALAC_ENABLED)
        val aac = prefs.getBoolean(Prefs.AAC_ENABLED, Prefs.DEF_AAC_ENABLED)

        videoRenderer.enforceSdr = prefs.getBoolean(Prefs.ENFORCE_SDR, Prefs.DEF_ENFORCE_SDR)
        videoRenderer.keyAllowFrameDrop = prefs.getBoolean(Prefs.KEY_ALLOW_FRAME_DROP, Prefs.DEF_KEY_ALLOW_FRAME_DROP)
        videoRenderer.selector.maxOperatingRate = when (prefs.getString(Prefs.OPERATING_RATE, Prefs.DEF_OPERATING_RATE)) {
            Prefs.ON -> true; Prefs.OFF -> false; else -> null
        }
        videoRenderer.benchmarkLog = prefs.getBoolean(Prefs.BENCHMARK_LOG, Prefs.DEF_BENCHMARK_LOG)
        videoRenderer.benchmarkLogCallback = { msg -> logCallback?.invoke(msg) }
        videoRenderer.scheduledOutputBufferRelease = prefs.getBoolean(Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE, Prefs.DEF_SCHEDULED_OUTPUT_BUFFER_RELEASE)
        NativeBridge.nativeSetH265Enabled(nativeHandle, h265)
        NativeBridge.nativeSetCodecs(nativeHandle, alac, aac)
        val advertiseVideo = prefs.getBoolean(Prefs.ADVERTISE_VIDEO, Prefs.DEF_ADVERTISE_VIDEO)
        val advertiseAudio = prefs.getBoolean(Prefs.ADVERTISE_AUDIO, Prefs.DEF_ADVERTISE_AUDIO)
        NativeBridge.nativeSetHlsEnabled(nativeHandle, advertiseVideo)
        NativeBridge.nativeSetLang(nativeHandle, "", "", resources.configuration.locales.toLanguageTags().replace(',', ':'))
        NativeBridge.nativeSetAudioEnabled(nativeHandle, advertiseAudio)
        NativeBridge.nativeSetPlist(nativeHandle, "maxFPS", maxFps)
        NativeBridge.nativeSetPlist(nativeHandle, "overscanned", if (overscanned) 1 else 0)
        if (audioLatencyMs >= 0) {
            NativeBridge.nativeSetPlist(nativeHandle, "audio_delay_micros", audioLatencyMs * 1000)
        }

        // set display params
        lastOrientation = resources.configuration.orientation
        val (w, h) = _displaySize()
        videoRenderer.setResolution(w, h)
        _videoResolution.value = "${w}x${h}"
        _videoAspect.value = w.toFloat() / h
        NativeBridge.nativeSetDisplaySize(nativeHandle, w, h, maxFps)

        val requestedPort = prefs.getInt(Prefs.SERVER_PORT, Prefs.DEF_SERVER_PORT).coerceIn(1, 65535)
        // (falls back to any free port by itself; Bonjour tells the devices which)
        val port = NativeBridge.nativeStart(nativeHandle, requestedPort)
        if (port < 0) {
            log("Failed to start on port $requestedPort")
            lastProblem = "Couldn't open a network port"
            _failStart()
            return
        }
        _serverPort.value = port

        // register mdns services
        val raopTxt = NativeBridge.nativeGetRaopTxtRecords(nativeHandle) ?: emptyMap()
        val airplayTxt = NativeBridge.nativeGetAirplayTxtRecords(nativeHandle) ?: emptyMap()
        val raopName = NativeBridge.nativeGetRaopServiceName(nativeHandle) ?: "AirPlay"
        val resolvedName = NativeBridge.nativeGetServerName(nativeHandle) ?: effectiveName
        _serverName.value = resolvedName

        if (prefs.getBoolean(Prefs.ADVERTISE_AUDIO, Prefs.DEF_ADVERTISE_AUDIO)) {
            nsdManager?.registerRaop(raopName, port, raopTxt)
        }
        nsdManager?.registerAirplay(resolvedName, port, airplayTxt)

        _serverState.value = ServerState.RUNNING
        lastProblem = null
        _writeStatus("ready\n$resolvedName\n$port")
        _watchNetwork()
        if (ensureServiceStarted) {
            ContextCompat.startForegroundService(this, Intent(this, AirPlayService::class.java))
        }
        promoteToForeground()
        log("Server started on port $port")
    }

    private fun _orientationFollowsDevice(): Boolean =
        prefs.getString(Prefs.RESOLUTION, Prefs.DEF_RESOLUTION) == Prefs.AUTO

    private fun _displaySize(clamp: Boolean = true): Pair<Int, Int> {
        val res = prefs.getString(Prefs.RESOLUTION, Prefs.DEF_RESOLUTION)!!
        val portrait = when (res) {
            "portrait" -> true
            "landscape" -> false
            else -> resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        val (rawW, rawH) = realDisplaySize()
        val device = if (portrait != rawH >= rawW) rawH to rawW else rawW to rawH
        if (res == "portrait" || res == "landscape") return device
        val (w, h) = if (res.contains("x")) {
            res.split("x").let { it[0].toInt() to it[1].toInt() }
        } else device
        if (!clamp) return w to h
        // strict decoders black-screen past their limits; advertised size is only upper bound for senders
        val (maxW, maxH) = videoRenderer.maxResolution()
        return w.coerceAtMost(maxW) to h.coerceAtMost(maxH)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == lastOrientation) return
        lastOrientation = newConfig.orientation
        if (nativeHandle == 0L || _serverState.value != ServerState.RUNNING) return
        if (!_orientationFollowsDevice()) return
        val (w, h) = _displaySize()
        NativeBridge.nativeSetDisplaySize(nativeHandle, w, h, prefs.getInt(Prefs.MAX_FPS, Prefs.DEF_MAX_FPS))
        log("Advertising ${w}x${h} from next session")
    }

    private fun _failStart() {
        _writeStatus("error\n${lastProblem ?: "AirPlay didn't start"}")
        audioRenderer.detachEngine()
        if (nativeHandle != 0L) {
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        nsdManager?.release()
        nsdManager = null
        _holdWifi(false)
        wakeLock?.release()
        wakeLock = null
        _serverState.value = ServerState.ERROR
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
    }

    fun restartServer(name: String = Prefs.getServerName(prefs)) {
        stopServer(stopService = false)
        startServer(name, ensureServiceStarted = true)
    }

    fun stopServer(stopService: Boolean = true) {
        _unwatchNetwork()
        _audioOpen = false
        audioRenderer.detachEngine()
        if (nativeHandle != 0L) {
            NativeBridge.nativeStop(nativeHandle)
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        dacpController?.reset()
        nsdManager?.release()
        nsdManager = null
        _holdWifi(false)
        wakeLock?.release()
        wakeLock = null
        videoRenderer.release()
        airPlayVideoPlayer.stop()
        mediaSession?.isActive = false
        _audioOnly.value = false
        _videoPlaybackActive.value = false
        _mirroringActive.value = false
        _videoPlaybackInfo.value = VideoPlaybackInfo()
        _lastVideoPollAt = 0
        _videoPollSuppressed = false
        _coverArtBytes = null
        _trackInfo.value = TrackInfo()
        _positionMs.value = 0
        _durationMs.value = 0
        _serverState.value = ServerState.STOPPED
        _connectionCount.value = 0
        _refreshDacpPlayer()
        if (stopService) {
            _writeStatus(null)
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
        }
        log("Server stopped")
    }

    fun setVideoSurface(surface: Surface) {
        videoRenderer.setSurface(surface)
    }

    fun clearVideoSurface(surface: Surface) {
        videoRenderer.clearSurface(surface)
    }

    fun setVideoPlaybackSurface(surface: Surface) {
        airPlayVideoPlayer.setSurface(surface)
    }

    fun clearVideoPlaybackSurface(surface: Surface) {
        airPlayVideoPlayer.clearSurface(surface)
    }

    fun setVideoPlaying(playing: Boolean) {
        airPlayVideoPlayer.setPlaying(playing)
    }

    fun seekVideoTo(positionMs: Long) {
        airPlayVideoPlayer.scrub(positionMs / 1000f)
    }

    fun setVideoScrubbing(enabled: Boolean) {
        airPlayVideoPlayer.setScrubbing(enabled)
    }

    fun setVideoSpeed(speed: Float) {
        airPlayVideoPlayer.setSpeed(speed)
    }

    fun setVideoSkipSilence(enabled: Boolean) {
        airPlayVideoPlayer.setSkipSilence(enabled)
    }

    fun toggleVideoPlayPause() {
        airPlayVideoPlayer.togglePlayPause()
    }

    fun seekVideoBy(deltaMs: Long) {
        airPlayVideoPlayer.seekBy(deltaMs)
    }

    fun stopVideoPlayback() = _endVideoPlayback("AirPlay Video stopped (local)")

    fun disconnectSessions() {
        log("Disconnecting active sessions (remote control back)")
        if (nativeHandle != 0L) {
            NativeBridge.nativeDisconnectSessions(nativeHandle)
        }
        _endVideoPlayback("AirPlay Video stopped (user disconnect)")
        videoRenderer.stopSession()
        _mirroringActive.value = false
        _audioOpen = false
        audioRenderer.stop()
        dacpController?.reset()
        _audioOnly.value = false
        _coverArtBytes = null
        _trackInfo.value = TrackInfo()
        _positionMs.value = 0
        _durationMs.value = 0
        _updateMediaNotification()
        mediaSession?.isActive = false
    }

    private fun _endVideoPlayback(message: String) {
        if (!_videoPlaybackActive.value && _videoLocation.value == null) return
        // lingering polls after a stop must not bounce the UI back to a pending session
        _videoPollSuppressed = true
        _videoPlaybackActive.value = false
        _videoLocation.value = null
        airPlayVideoPlayer.stop()
        if (!_audioOnly.value) mediaSession?.isActive = false
        log(message)
    }

    override fun onDestroy() {
        stopServer()
        dacpPlayer.release()
        mediaReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        mediaReceiver = null
        volumeReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        volumeReceiver = null
        dacpController?.release()
        dacpController = null
        mediaSession?.release()
        mediaSession = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    // RaopCallbackHandler (called from native threads)

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        videoRenderer.feedFrame(data, ntpTimeNs, isH265)
    }

    override fun onVideoSessionPoll() {
        _lastVideoPollAt = SystemClock.elapsedRealtime()
    }

    override fun onVideoPlay(location: String, startPositionSeconds: Float) {
        _videoLocation.value = location
        _videoPlaySeq.value++
        _videoPollSuppressed = false
        _videoPlaybackInfo.value = VideoPlaybackInfo(positionMs = (startPositionSeconds * 1000).toLong())
        _videoPlaybackAspect.value = 16f / 9f
        _videoPlaybackSize.value = null
        _videoTitle.value = ""
        _videoPlaybackActive.value = true
        airPlayVideoPlayer.play(location, startPositionSeconds)
        if (held) {
            // During the athan the video waits, paused, and carries on afterwards.
            _heldVideo = true
            airPlayVideoPlayer.setPlaying(false)
        }
        // claim media-button routing for keys that arrive as media-session events
        mediaSession?.isActive = true
        log("AirPlay Video play: $location @ ${startPositionSeconds}s")
        launchScreen()
    }

    override fun onVideoScrub(positionSeconds: Float) {
        airPlayVideoPlayer.scrub(positionSeconds)
    }

    override fun onVideoRate(rate: Float) {
        if (held && rate > 0f) {
            _heldVideo = true
            return
        }
        airPlayVideoPlayer.setRate(rate)
    }

    override fun onVideoStop() = _endVideoPlayback("AirPlay Video stopped")

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        clearPin()
        _audioOpen = true
        audioRenderer.start()
        audioRenderer.setFormat(ct, spf)
        // During the athan nothing from AirPlay is heard.
        if (held) audioRenderer.stop()
        if (!usingScreen) _setPlaying(true)
        if (!usingScreen && !_audioOnly.value) {
            // pure music streaming (not screen mirroring audio)
            _setAudioOnly(true)
        }
        log("Audio format: ct=$ct spf=$spf screen=$usingScreen")
        if (!usingScreen) launchScreen()
    }

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        clearPin()
        if (w > 0 && h > 0) {
            _videoAspect.value = w / h
            _videoResolution.value = "${w.toInt()}x${h.toInt()}"
            videoRenderer.setResolution(w.toInt(), h.toInt())
            val starting = !_mirroringActive.value
            _mirroringActive.value = true
            if (starting) launchScreen()
        }
        log("Video size: ${srcW}x${srcH} -> ${w}x${h}")
    }

    override fun onVolumeChange(volume: Float) {
        val frac = if (volume <= -144f) 0f else ((volume + 30f) / 30f).coerceIn(0f, 1f)
        _senderFrac = frac
        Log.d(TAG, "volume ${volume}dB, frac $frac")
        _mainHandler.post {
            if (_volSyncTarget >= 0f) {
                _volSyncStep()
                return@post
            }
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val idx = (frac * max).roundToInt()
            _preZeroIdx = if (idx == 0) (if (_preZeroIdx < 0) cur else _preZeroIdx) else -1
            if (idx != cur) {
                _pendingVolEchoes++
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0)
            }
        }
    }

    override fun onClientVolume(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val db = if (vol == 0) -144f else -30f + 30f * vol / max
        Log.d(TAG, "client volume query: $vol/$max -> ${db}dB")
        return db
    }

    private fun _volSyncStep() {
        _mainHandler.removeCallbacks(_volSyncTimeout)
        val target = _volSyncTarget
        if (target < 0f) return
        val frac = _senderFrac
        val want = when {
            frac < 0f -> _volSyncHint
            abs(target - frac) <= VOL_SYNC_EPS -> 0
            target > frac -> 1
            else -> -1
        }
        if (want == 0 || (_volSyncDir != 0 && want != _volSyncDir) || _volSyncSteps >= VOL_SYNC_MAX_STEPS) {
            _volSyncEnd()
            return
        }
        _volSyncDir = want
        _volSyncSteps++
        if (want > 0) dacpController?.volumeUp() else dacpController?.volumeDown()
        _mainHandler.postDelayed(_volSyncTimeout, VOL_SYNC_TIMEOUT_MS)
    }

    private fun _volSyncEnd() {
        _mainHandler.removeCallbacks(_volSyncTimeout)
        if (_volSyncTarget >= 0f) Log.d(TAG, "volume sync done: sender $_senderFrac, target $_volSyncTarget")
        _volSyncTarget = -1f
        _volSyncDir = 0
    }

    override fun onConnectionInit() {
        val firstConnection = _connectionCount.value == 0
        _connectionCount.value++
        log("Client connected (${_connectionCount.value})")
        // eTV opens its AirPlay screen once something actually plays (mirroring, a video, music,
        // or a code to type), not on every connection: Apple devices also connect just to look.
        if (firstConnection) _mainHandler.post { _holdAwake(true) }
    }

    override fun onConnectionDestroy() {
        _connectionCount.value = (_connectionCount.value - 1).coerceAtLeast(0)
        if (_connectionCount.value == 0) {
            // clients may drop without POST /stop; must run before the poll-state reset
            _endVideoPlayback("AirPlay Video stopped (disconnect)")
            // last client gone: release audio output devices to save power
            _audioOpen = false
            audioRenderer.stop()
            _audioOnly.value = false
            _lastVideoPollAt = 0
            _videoPollSuppressed = false
            _coverArtBytes = null
            _trackInfo.value = TrackInfo()
            _positionMs.value = 0
            _durationMs.value = 0
            dacpController?.reset()
            _senderFrac = -1f
            _mainHandler.post {
                _volSyncEnd()
                if (_preZeroIdx >= 0) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, _preZeroIdx, 0)
                    _preZeroIdx = -1
                }
            }
            mediaSession?.isActive = false
            _refreshDacpPlayer()
            _updateMediaNotification()
            _mainHandler.post { if (_connectionCount.value == 0) _holdAwake(false) }
        }
        log("Client disconnected (${_connectionCount.value})")
    }

    override fun onConnectionReset(reason: Int) {
        log("Connection reset: $reason")
    }

    override fun onDisplayPin(pin: String) {
        // a new pin is the sync point with the client prompt: show every new value immediately
        if (_lastPin == pin) return
        _lastPin = pin
        _activeRemotePin.value = pin
        pinCallback?.invoke(pin)
        _updateMediaNotification()
        launchScreen()
    }

    override fun onMetadata(data: ByteArray) {
        val map = DmapParser.parse(data)
        val info = TrackInfo.fromDmap(map, _trackInfo.value.coverArt)
        _trackInfo.value = info
        if (info.durationMs > 0) _durationMs.value = info.durationMs
        _updateMediaMetadata()
        _refreshDacpPlayer()
        log("Track: ${info.artist} - ${info.title}")
    }

    override fun onCoverArt(data: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return
        _coverArtBytes = data
        _coverArt.value = data
        _trackInfo.value = _trackInfo.value.copy(coverArt = bmp)
        _updateMediaMetadata()
        _refreshDacpPlayer()
    }

    override fun onProgress(start: Long, curr: Long, end: Long) {
        val rate = 44100.0
        val posMs = ((curr - start) / rate * 1000).toLong().coerceAtLeast(0)
        val durMs = ((end - start) / rate * 1000).toLong().coerceAtLeast(0)
        // pause/resume transitions emit degenerate progress; keep the last good value
        if (durMs <= 0) return
        _positionMs.value = posMs
        _durationMs.value = durMs
        _progressBaseMs = posMs
        _progressBaseTime = SystemClock.elapsedRealtime()
        _playing.value = true
        _updatePlaybackState()
        _refreshDacpPlayer()
    }

    override fun onAudioTeardown() {
        _setPlaying(false)
    }

    override fun onDacpId(dacpId: String, activeRemote: String) {
        dacpController?.update(dacpId, activeRemote)
        log("DACP: $dacpId")
    }

    override fun onMirrorRunning(running: Boolean) {
        if (running) videoRenderer.startSession() else {
            videoRenderer.stopSession()
            _mirroringActive.value = false
        }
        _setAudioOnly(!running)
    }

    private fun _setAudioOnly(audioOnly: Boolean) {
        val prev = _audioOnly.value
        _audioOnly.value = audioOnly
        _refreshDacpPlayer()
        if (audioOnly && !prev) {
            mediaSession?.isActive = true
            log("Audio mode")
        } else if (!audioOnly && prev) {
            mediaSession?.isActive = false
            _coverArtBytes = null
            _trackInfo.value = TrackInfo()
            _positionMs.value = 0
            _durationMs.value = 0
            _updateMediaNotification()
            log("Mirror mode")
        }
    }

    private fun _updateMediaMetadata() {
        val info = _trackInfo.value
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, info.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, info.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, info.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, _durationMs.value)
        info.coverArt?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        mediaSession?.setMetadata(builder.build())
        _updateMediaNotification()
    }

    /** eTV: the sender's next / previous song (music sent over AirPlay). */
    fun nextTrack() {
        dacpController?.nextItem()
    }

    fun previousTrack() {
        dacpController?.prevItem()
    }

    fun togglePlayPause() {
        val nowPlaying = !_playing.value
        _setPlaying(nowPlaying)
        dacpController?.let { if (nowPlaying) it.play() else it.pause() }
    }

    private fun _setPlaying(playing: Boolean) {
        _playing.value = playing
        _refreshDacpPlayer()
        if (playing) {
            // resume extrapolation from current position
            _progressBaseMs = _positionMs.value
            _progressBaseTime = SystemClock.elapsedRealtime()
        } else {
            // freeze position
            _positionMs.value = currentPositionMs()
            _progressBaseTime = 0
        }
        _updatePlaybackState()
    }

    private fun _updatePlaybackState() {
        // the sender's silent raop audio session must not overwrite video session state
        if (_videoPlaybackActive.value) return
        val isPlaying = _playing.value
        val pbState = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val speed = if (isPlaying) 1f else 0f
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(pbState, _positionMs.value, speed, SystemClock.elapsedRealtime())
            .build()
        mediaSession?.setPlaybackState(state)
        _updateMediaNotification()
    }

    // consumers extrapolate position from (position, speed, updateTime): push only discontinuities
    private fun _updateVideoPlaybackState(positionSeconds: Float, rate: Float) {
        val playing = rate > 0f
        val posMs = (positionSeconds * 1000).toLong()
        val now = SystemClock.elapsedRealtime()
        val expectedMs = _lastVideoStatePosMs +
            if (_lastVideoStateRate > 0f) ((now - _lastVideoStateAtMs) * _lastVideoStateRate).toLong() else 0L
        if (rate == _lastVideoStateRate && abs(posMs - expectedMs) < 1000) return
        _lastVideoStateRate = rate
        _lastVideoStatePosMs = posMs
        _lastVideoStateAtMs = now
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_REWIND or
                    PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(
                if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                posMs,
                rate,
                now
            )
            .build()
        mediaSession?.setPlaybackState(state)
    }

    private var _lastVideoStateRate = -1f
    private var _lastVideoStatePosMs = 0L
    private var _lastVideoStateAtMs = 0L

    private fun _refreshDacpPlayer() {
        _mainHandler.post { dacpPlayer.refresh() }
    }

    private fun clearPin() {
        _lastPin = null
        _activeRemotePin.value = null
        pinCallback?.invoke(null)
        _updateMediaNotification()
    }

    fun collectDebugInfo() = DebugInfo(
        videoCodec = videoRenderer.codecName,
        videoRes = _videoResolution.value,
        videoFps = videoRenderer.fps,
        videoBitrate = videoRenderer.bitrateBps,
        videoFrames = videoRenderer.frameCount,
        droppedFrames = videoRenderer.droppedFrames,
        framePacingJitterUs = videoRenderer.framePacingJitterUs,
        audioCodec = audioRenderer.codecLabel,
        audioVolume = 100 * audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) /
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        audio = audioRenderer.audioDebug(),
        connections = _connectionCount.value,
    )

    // helpers

    private fun getHwAddr(): ByteArray {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) {
                    val mac = iface.hardwareAddress
                    if (isUsableMac(mac)) return mac!!
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get hardware address", e)
        }

        // fall back to stable per-install random address
        return persistedRandomMac()
            ?: byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
    }

    private fun isUsableMac(mac: ByteArray?): Boolean =
        mac != null && mac.size == 6 &&
            mac.any { it != 0.toByte() } &&
            !(mac[0] == 0x02.toByte() && mac.drop(1).all { it == 0.toByte() })

    private fun persistedRandomMac(): ByteArray? {
        macFromString(prefs.getString(Prefs.FALLBACK_MAC_ADDRESS, null))
            ?.takeIf { isUsableMac(it) }?.let { return it }
        repeat(10) {
            val mac = randomAaiMac()
            if (isUsableMac(mac)) {
                prefs.edit().putString(Prefs.FALLBACK_MAC_ADDRESS, macToString(mac)).apply()
                return mac
            }
        }
        return null
    }

    // random locally-administered unicast MAC in AAI SLAP quadrant
    private fun randomAaiMac(): ByteArray {
        val mac = ByteArray(6).also { SecureRandom().nextBytes(it) }
        mac[0] = ((mac[0].toInt() and 0xF0) or 0x0A).toByte()
        return mac
    }

    private fun macToString(mac: ByteArray): String = mac.joinToString(":") { "%02x".format(it) }

    private fun macFromString(s: String?): ByteArray? {
        if (s == null) return null
        return try {
            s.split(":").map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) { null }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return _buildMediaNotification()
    }

    private fun promoteToForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        foregroundStarted = true
    }

    private fun requiresPin(): Boolean {
        return prefs.getBoolean(Prefs.REQUIRE_PIN, Prefs.DEF_REQUIRE_PIN)
    }

    private fun shouldLaunchOnConnect(): Boolean {
        return prefs.getBoolean(Prefs.LAUNCH_ON_CONNECT, Prefs.DEF_LAUNCH_ON_CONNECT)
    }

    private fun _buildMediaNotification(): Notification {
        val pi = _screenIntent()
        val info = _trackInfo.value
        val isAudio = _audioOnly.value && info.title.isNotEmpty()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .apply { if (pi != null) setContentIntent(pi) }
            .setOngoing(true)

        if (isAudio) {
            builder.setContentTitle(info.title).setContentText(info.artist).setSubText(info.album)
            info.coverArt?.let { builder.setLargeIcon(it) }
            mediaSession?.sessionToken?.let { token ->
                builder.setStyle(
                    MediaNotificationCompat.MediaStyle()
                        .setMediaSession(token)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                // transport action buttons
                builder.addAction(android.R.drawable.ic_media_previous, "Prev", _mediaAction(ACTION_PREV))
                builder.addAction(android.R.drawable.ic_media_pause, "Pause", _mediaAction(ACTION_PLAY_PAUSE))
                builder.addAction(android.R.drawable.ic_media_next, "Next", _mediaAction(ACTION_NEXT))
            }
        } else {
            if (_lastPin != null) {
                // passive handoff only: do not launch/reorder the activity during pin auth
                builder.setContentTitle(getString(R.string.notification_pin_title))
                    .setContentText(getString(R.string.notification_pin_text, _lastPin))
            } else {
                builder.setContentTitle(getString(R.string.notification_title))
                    .setContentText(getString(R.string.notification_text))
            }
        }
        return builder.build()
    }

    /**
     * Brings up the app's AirPlay screen (the activity that answers [ACTION_SHOW] in this package),
     * unless the athan holds the TV now.
     */
    fun launchScreen() {
        _mainHandler.post {
            if (athan) return@post
            val launchIntent = Intent(ACTION_SHOW).setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            try {
                startActivity(launchIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch activity", e)
            }
        }
    }

    private fun _screenIntent(): PendingIntent? {
        val intent = Intent(ACTION_SHOW).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageManager.resolveActivity(intent, 0) == null) return null
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    /** "ready / name / port" or "error / why" for eTV's settings (another process); null removes it. */
    private fun _writeStatus(text: String?) {
        try {
            val f = filesDir.resolve(STATUS_FILE)
            if (text == null) f.delete() else f.writeText(text)
        } catch (_: Exception) {
        }
    }

    // ---------------- eTV: keeping the CPU awake only while a device is connected ----------------

    // eTV: Wi-Fi power saving bunches packets up (stutter, lag spikes): off while a device is connected
    private var _wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private fun _holdWifi(on: Boolean) {
        try {
            if (on) {
                if (_wifiLock?.isHeld == true) return
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                _wifiLock = wm.createWifiLock(mode, "eTV:airplay").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } else {
                _wifiLock?.takeIf { it.isHeld }?.release()
                _wifiLock = null
            }
        } catch (_: Exception) {
        }
    }

    private fun _holdAwake(on: Boolean) {
        _holdWifi(on)
        if (on) {
            if (wakeLock?.isHeld == true) return
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eTV:airplay").apply { acquire() }
            } catch (_: Exception) {
            }
        } else {
            try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
            wakeLock = null
        }
    }

    // ---------------- eTV: starting again when the network comes back ----------------

    private var _netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var _netLost = false
    private val _restartAfterNetwork = Runnable {
        // Only when nobody is connected: a session in progress carries on as it is.
        if (_serverState.value == ServerState.RUNNING && _connectionCount.value == 0) {
            log("Network is back: announcing AirPlay again")
            restartServer()
        }
    }

    private fun _watchNetwork() {
        if (_netCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                _netLost = true
            }
            override fun onAvailable(network: Network) {
                if (!_netLost) return
                _netLost = false
                _mainHandler.removeCallbacks(_restartAfterNetwork)
                _mainHandler.postDelayed(_restartAfterNetwork, 3_000L)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            _netCallback = cb
        } catch (_: Exception) {
        }
    }

    private fun _unwatchNetwork() {
        _mainHandler.removeCallbacks(_restartAfterNetwork)
        val cb = _netCallback ?: return
        _netCallback = null
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    // ---------------- eTV: quiet during the athan ----------------

    /** True while the athan is on: its screen covers the TV, so AirPlay's doesn't come up. */
    @Volatile var athan = false
        private set
    /** True while the athan plays and things are paused for it: AirPlay is silent, videos and music wait. */
    @Volatile var held = false
        private set
    private var _heldVideo = false
    private var _heldMusic = false
    /** A device sent a sound format (music, or a mirrored screen's sound) and is still connected. */
    @Volatile private var _audioOpen = false

    /**
     * [on]: the athan begins. With [quiet], silences AirPlay and pauses what can be paused (a video
     * on the TV, music on the phone), remembering what was playing. Off: the athan is over; the
     * sound comes back, and with [resume] what was playing carries on. Main thread.
     */
    fun holdForAthan(on: Boolean, resume: Boolean = true, quiet: Boolean = true) {
        if (on) {
            athan = true
            if (!quiet) return
            if (!held) {
                held = true
                _heldVideo = _videoPlaybackActive.value && _videoPlaybackInfo.value.playing
                _heldMusic = _audioOnly.value && _playing.value
            }
            if (_videoPlaybackActive.value) airPlayVideoPlayer.setPlaying(false)
            if (_audioOnly.value && _playing.value) {
                _heldMusic = true
                _setPlaying(false)
                dacpController?.pause()
            }
            audioRenderer.stop()
            return
        }
        athan = false
        if (!held) return
        held = false
        if (_audioOpen && _connectionCount.value > 0) audioRenderer.start()
        if (resume) {
            if (_heldVideo && _videoPlaybackActive.value) airPlayVideoPlayer.setPlaying(true)
            if (_heldMusic && _audioOnly.value) {
                _setPlaying(true)
                dacpController?.play()
            }
        }
        _heldVideo = false
        _heldMusic = false
    }

    private fun _mediaAction(action: String): PendingIntent {
        val intent = Intent(action).setPackage(packageName)
        return PendingIntent.getBroadcast(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun _updateMediaNotification() {
        if (_serverState.value != ServerState.RUNNING) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, _buildMediaNotification())
    }

    enum class ServerState {
        STOPPED,
        RUNNING,
        ERROR
    }

    companion object {
        /** The receiver while it runs (eTV's screens and settings talk to it directly). */
        @Volatile var instance: AirPlayService? = null
            private set
        /** Why the receiver last failed to start, if it did. */
        @Volatile var lastProblem: String? = null
        /** The tag of the receiver's media session, so eTV's Now playing can tell it apart. */
        const val SESSION_TAG = "AirPlay"
        /** Opens the app's AirPlay screen (an activity in the app with this action). */
        const val ACTION_SHOW = "com.flymop.airplaytv.SHOW"
        /** Announce again with the current settings. */
        const val ACTION_RESTART = "com.flymop.airplaytv.RESTART"
        /** The athan: go quiet, and let what's playing wait. */
        const val ACTION_HOLD = "com.flymop.airplaytv.HOLD"
        /** The athan is over: sound back; with [EXTRA_RESUME] what was playing carries on. */
        const val ACTION_RELEASE = "com.flymop.airplaytv.RELEASE"
        const val EXTRA_RESUME = "resume"
        /** With [ACTION_HOLD]: also silence and pause (false: the athan only covers the screen). */
        const val EXTRA_QUIET = "quiet"
        /** In filesDir: the receiver's state for the app's settings. */
        const val STATUS_FILE = "airplay-status"
        private const val TAG = "AirPlayService"
        private const val VOL_SYNC_EPS = 0.033f
        private const val VOL_SYNC_MAX_STEPS = 32
        private const val VOL_SYNC_TIMEOUT_MS = 800L
        private const val CHANNEL_ID = "airplay_service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_PLAY_PAUSE = "com.flymop.airplaytv.PLAY_PAUSE"
        const val ACTION_NEXT = "com.flymop.airplaytv.NEXT"
        const val ACTION_PREV = "com.flymop.airplaytv.PREV"
        const val ACTION_START_SERVER = "com.flymop.airplaytv.START_SERVER"
        // shared with dpad/double-tap seeks
        const val VIDEO_SEEK_STEP_MS = 10_000L
        const val VIDEO_POLL_PENDING_TIMEOUT_MS = 3_000L

        private const val AUDIO_CONFIG_DEBOUNCE_MS = 500L
    }
}
