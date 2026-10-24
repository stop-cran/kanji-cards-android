package io.github.stopcran.kanji.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** A diagnostic section retains the same focus request through silent read/answer windows. */
class ListeningAudioFocus(context: Context) {
    private val manager = requireNotNull(context.getSystemService(AudioManager::class.java))
    val attributes: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var stage: (() -> Unit)? = null
    private var section: (() -> Unit)? = null
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) {
                val stageListener = stage
                val sectionListener = section
                stageListener?.invoke()
                sectionListener?.invoke()
            }
        }, Handler(Looper.getMainLooper())).build()

    fun startSection(interrupted: () -> Unit) {
        acquire()
        section = interrupted
    }

    fun startStage(interrupted: () -> Unit) {
        acquire()
        stage = interrupted
    }

    private fun acquire() {
        check(manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "Audio is busy in another app. Try again when you can listen."
        }
    }

    fun endStage() {
        stage = null
        if (section == null) manager.abandonAudioFocusRequest(request)
    }

    fun endSection() {
        section = null
        if (stage == null) manager.abandonAudioFocusRequest(request)
    }
}
