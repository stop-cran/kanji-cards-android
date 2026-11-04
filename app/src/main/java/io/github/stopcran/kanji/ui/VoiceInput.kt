package io.github.stopcran.kanji.ui

import androidx.compose.ui.semantics.liveRegion
import io.github.stopcran.kanji.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.voice.OptionMatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

sealed interface VoiceStatus {
    data object Checking : VoiceStatus
    data object Available : VoiceStatus
    data class Unavailable(val reason: String) : VoiceStatus
}

/** Voice input is offered only when an on-device recognizer exists; audio is never sent to an online service. */
object VoiceSupport {
    private val availableLanguages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun languageTag(language: String): String = if (language == "ru") "ru-RU" else "en-US"

    fun recognizerIntent(language: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag(language))
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    fun knownAvailable(language: String): Boolean = language in availableLanguages

    suspend fun check(context: Context, language: String): VoiceStatus {
        if (knownAvailable(language)) return VoiceStatus.Available
        return probe(context, language).also {
            if (it == VoiceStatus.Available) availableLanguages.add(language)
        }
    }

    private suspend fun probe(context: Context, language: String): VoiceStatus {
        val languageName = context.getString(if (language == "ru") R.string.language_russian_name else R.string.language_english_name)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return VoiceStatus.Unavailable(context.getString(R.string.voice_android_version_required))
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return VoiceStatus.Unavailable(context.getString(R.string.voice_no_on_device_recognizer))
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return VoiceStatus.Available
        return suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            var done = false
            fun finish(status: VoiceStatus) {
                if (done) return
                done = true
                recognizer.destroy()
                if (cont.isActive) cont.resume(status)
            }
            cont.invokeOnCancellation { if (!done) { done = true; recognizer.destroy() } }
            recognizer.checkRecognitionSupport(
                recognizerIntent(language),
                ContextCompat.getMainExecutor(context),
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        val installed = support.installedOnDeviceLanguages.any {
                            Locale.forLanguageTag(it).language == language
                        }
                        finish(
                            if (installed) VoiceStatus.Available
                            else VoiceStatus.Unavailable(context.getString(R.string.voice_not_installed, languageName)),
                        )
                    }

                    override fun onError(error: Int) = finish(
                        VoiceStatus.Unavailable(context.getString(R.string.voice_support_query_failed, languageName)),
                    )
                },
            )
        }
    }
}

@Composable
fun rememberVoiceStatus(language: String = "en"): State<VoiceStatus> {
    val context = LocalContext.current
    return produceState<VoiceStatus>(
        if (VoiceSupport.knownAvailable(language)) VoiceStatus.Available else VoiceStatus.Checking,
        context,
        language,
    ) { value = VoiceSupport.check(context, language) }
}

private sealed interface Listen {
    data object Idle : Listen
    data object Active : Listen
    data class Note(val text: String) : Listen
}

/**
 * "Say the meaning" button for a quiz question: listens once and reports which displayed option was clearly named.
 * Hidden unless the user enabled voice input and an on-device recognizer is available.
 */
@Composable
fun VoiceButton(options: List<String>, onMatch: (index: Int, heard: String) -> Unit, language: String = "en", modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val enabled by (context.applicationContext as KanjiApp).settings.voiceInput.collectAsState()
    val status by rememberVoiceStatus(language)
    if (!enabled || status != VoiceStatus.Available) return

    var state by remember { mutableStateOf<Listen>(Listen.Idle) }
    val recognizer = remember(language) { arrayOfNulls<SpeechRecognizer>(1) }
    val latest by androidx.compose.runtime.rememberUpdatedState(Pair(options, onMatch))

    DisposableEffect(language) { onDispose { recognizer[0]?.destroy(); recognizer[0] = null } }

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        recognizer[0]?.destroy()
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer[0] = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val index = OptionMatcher.match(heard, latest.first, language)
                state = if (index != null) Listen.Idle else Listen.Note(
                    if (heard.isEmpty()) context.getString(R.string.voice_no_match)
                    else context.getString(R.string.voice_heard_no_match, heard.first()),
                )
                if (index != null) latest.second(index, heard.first())
            }

            override fun onError(error: Int) {
                state = Listen.Note(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            context.getString(R.string.voice_no_match)
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                            val languageName = context.getString(if (language == "ru") R.string.language_russian_name else R.string.language_english_name)
                            context.getString(R.string.voice_language_unavailable, languageName)
                        }
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> context.getString(R.string.voice_microphone_permission)
                        else -> context.getString(R.string.voice_input_failed)
                    },
                )
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        state = Listen.Active
        r.startListening(VoiceSupport.recognizerIntent(language))
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else state = Listen.Note(context.getString(R.string.voice_microphone_permission))
    }

    OutlinedButton(
        onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        },
        enabled = state != Listen.Active,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(
                when {
                    state == Listen.Active -> R.string.voice_listening
                    language == "ru" -> R.string.voice_say_russian_meaning
                    else -> R.string.voice_say_meaning
                },
            ),
        )
    }
    (state as? Listen.Note)?.let { Text(it.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) }
}

/** Shown in the answer state after a voice pick, so a misheard answer can be taken back before it is recorded. */
@Composable
fun VoiceHeard(heard: String, onRetry: () -> Unit) {
    Text(stringResource(R.string.heard, heard), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
    androidx.compose.material3.TextButton(onClick = onRetry) { Text(stringResource(R.string.that_s_not_what_i)) }
}
