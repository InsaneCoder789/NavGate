package com.rohanc.navgate.ui.ar

import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

enum class VoiceAvailability { Initializing, OfflineReady, Unavailable }

@Composable
fun VoiceGuidance(enabled:Boolean,utteranceKey:String,message:String?):VoiceAvailability {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var speaker by remember{mutableStateOf<TextToSpeech?>(null)}
    var availability by remember{mutableStateOf(VoiceAvailability.Initializing)}
    DisposableEffect(context,lifecycle){
        var disposed=false
        var engine:TextToSpeech?=null
        val handler=Handler(Looper.getMainLooper())
        engine=TextToSpeech(context){status -> handler.post {
            if(!disposed){
                val tts=engine
                val voices=runCatching{tts?.voices.orEmpty()}.getOrDefault(emptySet())
                val locale=Locale.getDefault()
                val candidates=voices.map{VoiceCandidate(it.name,it.locale.language,it.locale.country,it.isNetworkConnectionRequired,
                    !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),it.quality)}
                val selected=chooseOfflineVoice(candidates,locale.language,locale.country)
                    ?: chooseOfflineVoice(candidates,Locale.ENGLISH.language,Locale.US.country)
                val voice=voices.find{it.name==selected}
                if(status==TextToSpeech.SUCCESS && tts!=null && voice!=null && tts.setVoice(voice)==TextToSpeech.SUCCESS){
                    speaker=tts;availability=VoiceAvailability.OfflineReady
                } else availability=VoiceAvailability.Unavailable
            }
        }}
        val observer=LifecycleEventObserver{_,event -> if(event==Lifecycle.Event.ON_STOP) engine?.stop()}
        lifecycle.addObserver(observer)
        onDispose{disposed=true;lifecycle.removeObserver(observer);engine?.stop();engine?.shutdown();speaker=null}
    }
    LaunchedEffect(enabled,utteranceKey,message,speaker){
        val text=message?.trim().orEmpty()
        if(!enabled || text.isBlank()) speaker?.stop()
        else speaker?.let{if(it.speak(text,TextToSpeech.QUEUE_FLUSH,null,utteranceKey)==TextToSpeech.ERROR) availability=VoiceAvailability.Unavailable}
    }
    return availability
}
