package com.rohanc.navgate.ui

import com.rohanc.navgate.core.*
import org.junit.Assert.*
import org.junit.Test

class FrontendContractTest {
    @Test fun `signal changes preserve utility and steps callers`() {
        for(status in listOf(SessionStatus.Navigating,SessionStatus.SignalLimited,SessionStatus.Rerouting,SessionStatus.GuidanceUnavailable)){
            for(screen in listOf("Settings","Offline","Steps","Camera"))assertEquals(screen,sessionDestination(status,screen))
        }
        assertEquals("Navigation",sessionDestination(SessionStatus.Navigating,"Route"))
        assertEquals("Arrival",sessionDestination(SessionStatus.Arrived,"Steps"))
        assertEquals("Explore",sessionDestination(SessionStatus.Stopped,"Camera"))
    }
    @Test fun `wireframe back paths retain endpoint intent and actual caller`() {
        fun back(screen:String,active:Boolean=false,intent:String="place")=frontendBack(screen,active,"Saved","Route","Settings",intent)
        assertEquals("Directions",back("Route"));assertEquals("Search",back("Results"))
        assertEquals("Directions",back("Search",intent="from"));assertEquals("Saved",back("Search",intent="saved"))
        assertEquals("Saved",back("Place"));assertEquals("Route",back("Settings"));assertEquals("Settings",back("Offline"))
        assertEquals("Navigation",back("Steps",true));assertEquals("Route",back("Steps"))
    }
    @Test fun `all trip surfaces select Go while capability labels never promise connectivity`() {
        for(screen in listOf("Route","Directions","Navigation","Steps","Arrival","Camera"))assertEquals("Directions",selectedFrontendTab(screen))
        assertEquals("Saved",selectedFrontendTab("Search","saved"));assertEquals("Directions",selectedFrontendTab("Results","from"))
        assertTrue(readinessLabel(DestinationReadiness.ReviewedEntrance).contains("connectivity"))
        assertTrue(readinessLabel(DestinationReadiness.MissingGraph).contains("not been reviewed"))
    }
}
