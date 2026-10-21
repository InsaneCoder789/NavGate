package com.rohanc.navgate.ui.map

import com.rohanc.navgate.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ManualRouteGeoJsonTest {
    @Test fun `map labels preserve unicode quotes order and lon lat without invalid JSON`() {
        val pin=RoutePin("a","Stop \"A\" · मुंबई",GeoPoint(18.92188,72.83468))
        val feature=Json.parseToJsonElement(ManualRouteGeoJson.points(listOf(pin))).jsonObject["features"]!!.jsonArray.single().jsonObject
        assertEquals("1. ${pin.label}",feature["properties"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        val coordinates=feature["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(pin.point.longitude,coordinates[0].jsonPrimitive.double,0.0)
        assertEquals(pin.point.latitude,coordinates[1].jsonPrimitive.double,0.0)
    }
}
