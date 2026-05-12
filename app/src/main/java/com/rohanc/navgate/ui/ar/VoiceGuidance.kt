package com.rohanc.navgate.ui.ar

import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
fun VoiceGuidance(
    enabled: Boolean,
    utteranceKey: String,
    message: String?,
) {
    val context = LocalContext.current
    var speaker by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(context) {
        lateinit var textToSpeech: TextToSpeech
        textToSpeech =
            TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    textToSpeech.language = Locale.US
                    speaker = textToSpeech
                }
            }
        onDispose {
            speaker?.stop()
            textToSpeech.stop()
            textToSpeech.shutdown()
            speaker = null
        }
    }

    LaunchedEffect(enabled, utteranceKey, message, speaker) {
        val text = message?.trim().orEmpty()
        if (!enabled || text.isBlank()) return@LaunchedEffect
        speaker?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceKey)
    }
}
