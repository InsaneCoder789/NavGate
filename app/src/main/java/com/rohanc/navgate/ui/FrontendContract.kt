package com.rohanc.navgate.ui

import com.rohanc.navgate.core.*

internal fun frontendBack(screen:String,active:Boolean,placeCaller:String,settingsCaller:String,utilityCaller:String,searchFor:String,directionCaller:String="Explore"):String=when(screen){
    "Steps","Camera"->if(active)"Navigation" else "Route"
    "Search"->if(searchFor in listOf("from","to"))"Directions" else if(searchFor=="saved")"Saved" else "Explore"
    "Results"->"Search"
    "Route"->"Directions"
    "Directions"->directionCaller
    "Setup"->"Settings"
    "MapPick"->"Directions"
    "Place"->placeCaller
    "Settings"->settingsCaller
    "Offline"->utilityCaller
    else->"Explore"
}
internal fun selectedFrontendTab(screen:String,searchFor:String="place"):String=when(screen){
    "Search","Results"->when(searchFor){"saved"->"Saved";"from","to"->"Directions";else->"Explore"}
    "MapPick"->"Directions"
    "Directions","Route","Navigation","Steps","Camera","Arrival"->"Directions"
    "Saved"->"Saved"
    "Recents"->"Recents"
    else->"Explore"
}
internal fun sessionDestination(status:SessionStatus,screen:String):String=when {
    status==SessionStatus.Arrived->"Arrival"
    status==SessionStatus.ResumeAvailable && screen in setOf("Navigation","Camera","Steps")->"Route"
    status==SessionStatus.Idle && screen in setOf("Navigation","Camera","Steps")->"Explore"
    status==SessionStatus.Stopped && screen in setOf("Navigation","Camera","Steps")->"Explore"
    status==SessionStatus.Preview && screen in setOf("Directions","Route")->"Route"
    status in NavigationSession.ACTIVE && screen in setOf("Explore","Directions","Route")->"Navigation"
    else->screen
}
