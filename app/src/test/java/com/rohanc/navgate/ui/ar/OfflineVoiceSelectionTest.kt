package com.rohanc.navgate.ui.ar

import org.junit.Assert.*
import org.junit.Test

class OfflineVoiceSelectionTest {
    @Test fun `network and uninstalled voices cannot be selected`() {
        val choices=listOf(VoiceCandidate("network","en","IN",true,true,500),VoiceCandidate("download","en","IN",false,false,500))
        assertNull(chooseOfflineVoice(choices,"en","IN"))
        assertEquals("local",chooseOfflineVoice(choices+VoiceCandidate("local","en","US",false,true,100),"en","IN"))
    }
    @Test fun `locale preference is deterministic and never selects the wrong language`() {
        val choices=listOf(VoiceCandidate("us","en","US",false,true,500),VoiceCandidate("india","en","IN",false,true,100))
        assertEquals("india",chooseOfflineVoice(choices,"en","IN"))
        assertNull(chooseOfflineVoice(choices,"hi","IN"))
        assertEquals("us",chooseOfflineVoice(choices.reversed(),"en","GB"))
    }
}
