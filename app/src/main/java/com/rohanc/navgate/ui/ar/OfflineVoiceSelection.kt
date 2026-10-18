package com.rohanc.navgate.ui.ar

internal data class VoiceCandidate(val name:String,val language:String,val country:String,val networkRequired:Boolean,val installed:Boolean,val quality:Int)

/** Never choose a network voice or trigger an automatic voice-data download. */
internal fun chooseOfflineVoice(voices:List<VoiceCandidate>,language:String,country:String):String? = voices
    .filter { !it.networkRequired && it.installed && it.language==language }
    .sortedWith(compareByDescending<VoiceCandidate>{it.country==country}.thenByDescending{it.quality}.thenBy{it.name})
    .firstOrNull()?.name
