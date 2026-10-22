package io.github.stopcran.kanji.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface ListeningPlayer : AutoCloseable {
    val playing: Boolean
    val positionMs: Long
    suspend fun prepare(bytes: ByteArray)
    suspend fun play(positionMs: Long, speed: Float, completed: () -> Unit, interrupted: () -> Unit, failed: (IOException) -> Unit)
    fun pause()
}

class AndroidListeningPlayer(private val context: Context) : ListeningPlayer {
    private val manager = requireNotNull(context.getSystemService(AudioManager::class.java))
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var media: MediaPlayer? = null
    private var prepared = false
    private var registered = false
    private var starting = false
    private var onInterrupted: () -> Unit = {}
    override var playing = false
        private set
    override val positionMs: Long get() = if (prepared) requireNotNull(media).currentPosition.toLong() else 0
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) interrupt()
        }, Handler(Looper.getMainLooper())).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) interrupt()
        }
    }

    private fun interrupt() {
        if (playing || starting) {
            starting = false
            onInterrupted()
            pause()
        }
    }

    override suspend fun prepare(bytes: ByteArray) {
        close()
        val player = MediaPlayer()
        media = player
        player.setAudioAttributes(attributes)
        player.setDataSource(object : MediaDataSource() {
            override fun getSize() = bytes.size.toLong()
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position < 0 || position >= bytes.size) return -1
                val count = minOf(size, bytes.size - position.toInt())
                bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                return count
            }
            override fun close() = Unit
        })
        suspendCancellableCoroutine { continuation ->
            player.setOnPreparedListener {
                prepared = true
                if (continuation.isActive) continuation.resume(Unit)
            }
            player.setOnErrorListener { _, what, extra ->
                prepared = false
                if (continuation.isActive) continuation.resumeWithException(IOException("Audio could not be prepared ($what/$extra)"))
                true
            }
            continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { if (media === player) close() } }
            player.prepareAsync()
        }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            context.registerReceiver(noisy, filter)
        }
        registered = true
    }

    override suspend fun play(
        positionMs: Long, speed: Float, completed: () -> Unit, interrupted: () -> Unit, failed: (IOException) -> Unit,
    ) {
        check(prepared) { "Audio is not prepared" }
        require(positionMs >= 0 && speed in 0.8f..1f) { "Invalid playback settings" }
        onInterrupted = interrupted
        starting = true
        try {
            check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "Audio is busy in another app. Try again when you can listen."
            }
            val player = requireNotNull(media)
            var seek: kotlinx.coroutines.CancellableContinuation<Unit>? = null
            player.setOnErrorListener { _, what, extra ->
                playing = false
                prepared = false
                manager.abandonAudioFocusRequest(focus)
                val error = IOException("Audio playback failed ($what/$extra). Resume or sync again; no failure was graded.")
                val waiting = seek
                if (waiting?.isActive == true) waiting.resumeWithException(error) else failed(error)
                true
            }
            player.setOnCompletionListener {
                playing = false
                manager.abandonAudioFocusRequest(focus)
                completed()
            }
            if (positionMs > 0) suspendCancellableCoroutine { continuation ->
                seek = continuation
                player.setOnSeekCompleteListener { if (continuation.isActive) continuation.resume(Unit) }
                continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { if (media === player) close() } }
                player.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST)
            }
            seek = null
            if (!starting) throw CancellationException("Playback was interrupted before starting")
            player.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
            player.start()
            playing = true
        } finally { starting = false }
    }

    override fun pause() {
        starting = false
        if (playing) requireNotNull(media).pause()
        playing = false
        manager.abandonAudioFocusRequest(focus)
    }

    override fun close() {
        starting = false
        playing = false
        prepared = false
        media?.release()
        media = null
        manager.abandonAudioFocusRequest(focus)
        if (registered) context.unregisterReceiver(noisy)
        registered = false
        onInterrupted = {}
    }
}
