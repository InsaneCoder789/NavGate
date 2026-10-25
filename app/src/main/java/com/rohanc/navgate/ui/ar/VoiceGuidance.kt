package com.rohanc.navgate.ui.ar

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

enum class VoiceAvailability { Initializing, OfflineReady, Unavailable }

internal interface GuidanceSpeech {
    val availability: VoiceAvailability
    fun update(enabled: Boolean, utteranceKey: String, message: String?)
}

/** Owns an installed offline voice. Active guidance survives ON_STOP; trip end/disposal stops speech. */
internal class OfflineSpeech(context: Context) : GuidanceSpeech {
    override var availability by mutableStateOf(VoiceAvailability.Initializing)
        private set
    private var engine: TextToSpeech? = null
    private var disposed = false
    private var enabled = false
    private var pendingKey = ""
    private var pendingText = ""
    private var spokenKey: String? = null

    init {
        val handler = Handler(Looper.getMainLooper())
        engine = TextToSpeech(context.applicationContext) { status -> handler.post {
            if (!disposed) {
                val tts = engine
                val voices = runCatching { tts?.voices.orEmpty() }.getOrDefault(emptySet())
                val locale = Locale.getDefault()
                val candidates = voices.map { VoiceCandidate(it.name, it.locale.language, it.locale.country,
                    it.isNetworkConnectionRequired, !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED), it.quality) }
                val selected = chooseOfflineVoice(candidates, locale.language, locale.country)
                    ?: chooseOfflineVoice(candidates, Locale.ENGLISH.language, Locale.US.country)
                val voice = voices.find { it.name == selected }
                availability = if (status == TextToSpeech.SUCCESS && tts != null && voice != null &&
                    tts.setVoice(voice) == TextToSpeech.SUCCESS) VoiceAvailability.OfflineReady else VoiceAvailability.Unavailable
                speakPending()
            }
        } }
    }

    override fun update(enabled: Boolean, utteranceKey: String, message: String?) {
        if (disposed) return
        this.enabled = enabled
        pendingKey = utteranceKey
        pendingText = message?.trim().orEmpty()
        if (!enabled || pendingText.isBlank()) {
            engine?.stop()
            spokenKey = null
        } else speakPending()
    }

    private fun speakPending() {
        if (!enabled || pendingText.isBlank() || availability != VoiceAvailability.OfflineReady || spokenKey == pendingKey) return
        if (engine?.speak(pendingText, TextToSpeech.QUEUE_FLUSH, null, pendingKey) == TextToSpeech.ERROR) {
            availability = VoiceAvailability.Unavailable
        } else spokenKey = pendingKey
    }

    fun close() { disposed = true; engine?.stop(); engine?.shutdown(); engine = null }
}

@Composable
internal fun rememberOfflineSpeech(): OfflineSpeech {
    val context = LocalContext.current
    val speech = remember(context.applicationContext) { OfflineSpeech(context.applicationContext) }
    DisposableEffect(speech) { onDispose { speech.close() } }
    return speech
}
