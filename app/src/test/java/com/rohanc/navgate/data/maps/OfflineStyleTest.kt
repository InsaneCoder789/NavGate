package com.rohanc.navgate.data.maps

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OfflineStyleTest {
    private fun assetStyle(name: String) = File("src/main/assets/$name").readText()

    @Test fun `every shipped style can be pointed at a local pack`() {
        for (name in listOf("navgate-map-daylight.json", "navgate-map-dark.json", "navgate-map-daylight-flat.json", "navgate-map-dark-flat.json")) {
            val original = Json.parseToJsonElement(assetStyle(name)).jsonObject
            val out = OfflineStyle.rewrite(assetStyle(name), "pmtiles://file:///data/pack.pmtiles", "file:///fonts/{fontstack}/{range}.pbf", "file:///sprites/ofm")!!
            val style = Json.parseToJsonElement(out).jsonObject
            val sources = style["sources"]!!.jsonObject
            assertEquals("$name vector url", "pmtiles://file:///data/pack.pmtiles", sources["openmaptiles"]!!.jsonObject["url"]!!.jsonPrimitive.content)
            assertTrue("$name no raster source left", sources.values.none { it.jsonObject["type"]!!.jsonPrimitive.content == "raster" })
            assertEquals("file:///fonts/{fontstack}/{range}.pbf", style["glyphs"]!!.jsonPrimitive.content)
            assertEquals("file:///sprites/ofm", style["sprite"]!!.jsonPrimitive.content)
            // No layer may point at a source that was removed, and the rest are kept.
            val names = sources.keys
            assertTrue(style["layers"]!!.jsonArray.all { val s = it.jsonObject["source"]?.jsonPrimitive?.contentOrNull; s == null || s in names })
            assertTrue(style["layers"]!!.jsonArray.size >= original["layers"]!!.jsonArray.size - 3)
            assertFalse(out.contains("tiles.openfreemap.org"))
        }
    }

    @Test fun `things that are not styles are refused`() {
        assertNull(OfflineStyle.rewrite("not json", "u", "g", "s"))
        assertNull(OfflineStyle.rewrite("""{"version":8,"sources":{"a":{"type":"raster"}},"layers":[]}""", "u", "g", "s"))
    }
}
