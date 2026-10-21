package com.rohanc.navgate.data.local

import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.routing.ValhallaRoutingProvider
import com.rohanc.navgate.data.search.declaredDigest
import com.rohanc.navgate.data.search.ftsExpression
import com.rohanc.navgate.model.ManeuverType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CheckpointCodecTest {
    private val request=PlanRequest(9,"mumbai",GeoPoint(18.92297,72.83329),"marine-drive",profile="driving",
        destinationPoint=GeoPoint(18.9341,72.82365),destinationTitle="Marine Drive")
    private val route=(ValhallaRoutingProvider.parse(
        javaClass.classLoader!!.getResourceAsStream("routing/mumbai-driving.json")!!.bufferedReader().readText(),request,"test",1000) as RouteResult.Ready).route
    private val snapshot=SessionSnapshot(sessionId="s1",route=route,status=SessionStatus.Navigating,alongMeters=500.0,
        remainingMeters=route.distanceMeters-500.0,remainingSeconds=route.remainingSecondsAt(500.0))

    @Test fun `progress checkpoints are small and the route is stored separately`() {
        val payload=CheckpointCodec.encode(snapshot)
        val routeJson=CheckpointCodec.encodeRoute(route)
        assertTrue("checkpoint ${payload.length} vs route ${routeJson.length}",payload.length*5<routeJson.length)
        val restored=CheckpointCodec.decode(payload){ if(it==route.id)routeJson else null }
        assertEquals(snapshot,restored.snapshot)
    }

    @Test fun `missing route record is an error rather than a route-less trip`() {
        assertThrows(IllegalArgumentException::class.java){ CheckpointCodec.decode(CheckpointCodec.encode(snapshot)){ null } }
    }

    @Test fun `legacy single-record checkpoints still load`() {
        val legacy=StoredJson.encodeToString(SessionCheckpoint(snapshot))
        assertEquals(snapshot,CheckpointCodec.decode(legacy){ null }.snapshot)
    }

    @Test fun `stored payloads tolerate fields added by a newer version`() {
        val obj=Json.parseToJsonElement(CheckpointCodec.encodeRoute(route)).jsonObject
        val withExtra=JsonObject(obj+("futureField" to JsonPrimitive("x"))).toString()
        assertEquals(route,StoredJson.decodeFromString<CampusRoute>(withExtra))
    }

    @Test fun `valhalla maneuver types keep roundabouts u-turns and slight turns`() {
        val expected=mapOf(1 to "Start",4 to "Arrive",9 to "SlightRight",10 to "Right",12 to "UTurn",15 to "Left",16 to "SlightLeft",
            20 to "KeepRight",21 to "KeepLeft",26 to "Roundabout",27 to "RoundaboutExit",11 to "SharpRight",14 to "SharpLeft",8 to "Straight",25 to "Merge")
        for((type,kind) in expected) assertEquals("type $type",kind,ValhallaRoutingProvider.maneuverKind(type))
        assertEquals(ManeuverType.Roundabout,maneuverTypeForKind("Roundabout"))
        assertEquals(ManeuverType.UTurn,maneuverTypeForKind("UTurn"))
        assertEquals(ManeuverType.SlightLeft,maneuverTypeForKind("SlightLeft"))
        assertEquals(ManeuverType.RoundaboutExit,maneuverTypeForKind("RoundaboutExit"))
        assertEquals(ManeuverType.SharpLeft,maneuverTypeForKind("SharpLeft"))
        assertEquals(ManeuverType.Merge,maneuverTypeForKind("Merge"))
        assertEquals(ManeuverType.Straight,maneuverTypeForKind("anything-else"))
    }

    @Test fun `index digest comes from the build manifest only when well formed`() {
        val digest="a".repeat(64)
        assertEquals(digest,declaredDigest("{\"index_sha256\":\"$digest\",\"bytes\":1}"))
        assertNull(declaredDigest(null));assertNull(declaredDigest("not json"))
        assertNull(declaredDigest("{\"index_sha256\":\"short\"}"));assertNull(declaredDigest("{}"))
    }

    @Test fun `multiword search uses implicit AND because Android FTS treats the word AND as a term`() {
        assertEquals("\"port*\" \"blair*\"",ftsExpression("Port Blair"))
        assertEquals("\"gateway*\" \"of*\" \"india*\"",ftsExpression("  Gateway of India "))
        assertFalse(ftsExpression("a b c")!!.contains("AND"))
        assertEquals("\"mumbai*\"",ftsExpression("\" OR * --").let{ftsExpression("Mumbai")})
        assertNull(ftsExpression(" ")); assertNull(ftsExpression("\" * --"))
    }
}
