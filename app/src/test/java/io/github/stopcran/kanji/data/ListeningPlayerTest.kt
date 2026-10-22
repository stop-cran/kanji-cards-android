package io.github.stopcran.kanji.data

import android.app.Application
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ListeningPlayerTest {
    @Test fun focusLossDuringSeekPreventsPlaybackFromStarting() = interruptedSeek(noisy = false)

    @Test fun headphoneRemovalDuringSeekPreventsPlaybackFromStarting() = interruptedSeek(noisy = true)

    private fun interruptedSeek(noisy: Boolean) = runTest {
        val context: Application = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(AudioManager::class.java)
        val looper = shadowOf(Looper.getMainLooper())
        lateinit var native: ShadowMediaPlayer
        ShadowMediaPlayer.setCreateListener { _, shadow -> native = shadow; shadow.seekDelay = 1_000 }
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(20_000, 0) }
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val player = AndroidListeningPlayer(context)
        try {
            val prepared = async(UnconfinedTestDispatcher(testScheduler)) { player.prepare(byteArrayOf(1)) }
            looper.idle()
            prepared.await()
            var interruptions = 0
            var completed = false
            val playback = async(UnconfinedTestDispatcher(testScheduler)) {
                player.play(5_000, 1f, completed = { completed = true }, interrupted = { interruptions++ },
                    failed = { throw AssertionError(it) })
            }
            assertFalse(playback.isCompleted)
            assertEquals(5_000, native.pendingSeek)
            if (noisy) {
                context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
                looper.idle()
            } else {
                shadowOf(manager).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            }
            assertEquals(1, interruptions)
            looper.idleFor(Duration.ofSeconds(2))
            assertTrue(playback.isCancelled)
            assertFalse(player.playing)
            assertFalse(completed)
            assertNotEquals(ShadowMediaPlayer.State.STARTED, native.state)
        } finally { player.close() }
    }
}
