package app.etv.fakeplayer

import android.app.Activity
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays like a video app on a TV: a steady tone with a media session, and the usual manners
 * (as ExoPlayer apps such as YouTube behave): it pauses when another app takes the sound for a
 * moment and carries on when it gets it back, ducks when asked, and pauses when it's hidden,
 * carrying on when it's shown again. Once a second it logs whether it's playing, its own volume,
 * and how far its sound has got, so the test can tell that sound is really coming out.
 */
class PlayerActivity : Activity() {

    private val tag = "FakePlayer"
    private val h = Handler(Looper.getMainLooper())
    private lateinit var am: AudioManager
    private lateinit var track: AudioTrack
    private lateinit var session: MediaSession
    private var focus: AudioFocusRequest? = null
    @Volatile private var playing = false
    @Volatile private var alive = true
    private var resumeOnGain = false
    private var playWhenShown = false
    private var volume = 1f
    private lateinit var label: TextView

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        log("focus $change")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (playing) {
                stopSound()
                resumeOnGain = true
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setVol(0.2f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                setVol(1f)
                if (resumeOnGain) {
                    resumeOnGain = false
                    startSound()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> pause()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        am = getSystemService(AUDIO_SERVICE) as AudioManager
        label = TextView(this).apply {
            textSize = 40f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(20, 60, 90))
            gravity = Gravity.CENTER
        }
        setContentView(label)

        val rate = 44100
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, rate / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val tone = ShortArray(rate / 10) { i -> (sin(2 * PI * 440 * i / rate) * 8000).toInt().toShort() }
        Thread {
            while (alive) {
                if (playing) track.write(tone, 0, tone.size) else Thread.sleep(20)
            }
        }.start()

        session = MediaSession(this, "film")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { log("command play"); play() }
            override fun onPause() { log("command pause"); pause() }
            override fun onStop() { log("command stop"); pause() }
        }, h)
        @Suppress("DEPRECATION")
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session.isActive = true
        play()
        h.post(object : Runnable {
            override fun run() {
                Log.i(tag, "STATUS playing=$playing volume=$volume head=${track.playbackHeadPosition}")
                if (alive) h.postDelayed(this, 1000)
            }
        })
    }

    private fun setVol(v: Float) {
        volume = v
        track.setVolume(v)
    }

    private fun play() {
        val granted = if (Build.VERSION.SDK_INT >= 26) {
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener, h)
                .build()
            focus = r
            am.requestAudioFocus(r)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        if (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            setVol(1f)
            startSound()
        } else {
            log("focus refused")
            state(PlaybackState.STATE_PAUSED)
        }
    }

    private fun pause() {
        stopSound()
        resumeOnGain = false
        if (Build.VERSION.SDK_INT >= 26) focus?.let { am.abandonAudioFocusRequest(it) } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
        }
    }

    private fun startSound() {
        track.play()
        playing = true
        state(PlaybackState.STATE_PLAYING)
    }

    private fun stopSound() {
        playing = false
        track.pause()
        state(PlaybackState.STATE_PAUSED)
    }

    private fun state(s: Int) {
        label.text = if (s == PlaybackState.STATE_PLAYING) "Film ▶ playing" else "Film ❚❚ paused"
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                .setState(s, 0L, 1f)
                .build(),
        )
        log("state ${if (s == PlaybackState.STATE_PLAYING) "PLAYING" else "PAUSED"}")
    }

    // "Open the film and play": what the test sends while the athan is on, like someone starting a video.
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("play", false)) {
            log("asked to play")
            play()
        }
    }

    // Like the YouTube app: pauses when hidden, carries on when shown again.
    override fun onStop() {
        super.onStop()
        if (playing) {
            playWhenShown = true
            pause()
            log("hidden: paused")
        }
    }

    override fun onStart() {
        super.onStart()
        if (playWhenShown) {
            playWhenShown = false
            play()
            log("shown: playing again")
        }
    }

    override fun onDestroy() {
        alive = false
        session.release()
        track.release()
        super.onDestroy()
    }

    private fun log(s: String) = Log.i(tag, s)
}
