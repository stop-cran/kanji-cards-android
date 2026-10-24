package io.github.stopcran.kanji.data

import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = android.app.Application::class)
class ListeningAudioFocusTest {
    @Test fun sectionRetainsFocusBetweenStagesButOrdinaryPlaybackStillReleasesIt() {
        val context = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(context.getSystemService(AudioManager::class.java))
        val focus = ListeningAudioFocus(context)
        var sectionLosses = 0
        var stageLosses = 0
        focus.startSection { sectionLosses++ }
        val request = shadow.lastAudioFocusRequest.audioFocusRequest
        focus.startStage { stageLosses++ }
        assertSame(request, shadow.lastAudioFocusRequest.audioFocusRequest)
        focus.endStage()
        assertNull(shadow.lastAbandonedAudioFocusRequest)
        shadow.lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(1, sectionLosses)
        assertEquals(0, stageLosses)
        focus.endSection()
        assertSame(request, shadow.lastAbandonedAudioFocusRequest)
        val ordinary = ListeningAudioFocus(context)
        ordinary.startStage { stageLosses++ }
        val ordinaryRequest = shadow.lastAudioFocusRequest.audioFocusRequest
        ordinary.endStage()
        assertSame(ordinaryRequest, shadow.lastAbandonedAudioFocusRequest)
    }

    @Test fun deniedSectionFocusIsAnExplicitFailure() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertThrows(IllegalStateException::class.java) { ListeningAudioFocus(context).startSection {} }
    }
}
