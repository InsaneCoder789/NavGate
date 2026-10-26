package com.rohanc.navgate.ui.map

import com.rohanc.navgate.core.CampusPlace
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.data.search.MapPin
import com.rohanc.navgate.data.search.PoiSource
import com.rohanc.navgate.data.search.PoiCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlacePinLoaderTest {
    private class Counting : PoiSource {
        var reads = 0
        override fun covers(point: GeoPoint) = true
        override suspend fun warmUp() {}
        override suspend fun nearby(category: PoiCategory, near: GeoPoint, limit: Int) = emptyList<CampusPlace>()
        override suspend fun nearbyAll(near: GeoPoint, limit: Int) = emptyList<CampusPlace>()
        override suspend fun search(query: String, near: GeoPoint?, limit: Int) = emptyList<CampusPlace>()
        override suspend fun pins(south: Double, west: Double, north: Double, east: Double, limit: Int): List<MapPin> {
            reads++; return listOf(MapPin("poi:n$reads", "Cafe $reads", "cafe", (south + north) / 2, (west + east) / 2))
        }
    }

    @Test fun aCellIsReadOnceAndKeptWhileThePlaceIsPannedBackOver() = runBlocking {
        val source = Counting(); val loader = PlacePinLoader(source)
        loader.around(19.2000, 72.8600, 19.2090, 72.8690)
        val first = source.reads
        loader.around(19.2005, 72.8605, 19.2085, 72.8685)
        assertEquals(first, source.reads)
    }

    @Test fun aViewThatCoversTooManyCellsReadsNothing() = runBlocking {
        val source = Counting()
        assertTrue(PlacePinLoader(source).around(18.0, 72.0, 20.0, 74.0).isEmpty()); assertEquals(0, source.reads)
    }

    @Test fun campusPlacesReplaceTheIndexedCopyOfTheSameName() {
        val indexed = listOf(MapPin("poi:1", "KIIT Library", "library", 20.35, 85.82), MapPin("poi:2", "Cafe", "cafe", 20.351, 85.821))
        val campus = listOf(MapPin("kiit:9", "KIIT Library", "library", 20.35001, 85.82001))
        assertEquals(listOf("kiit:9", "poi:2"), PlacePinLoader.merge(indexed, campus).map { it.id })
    }

    @Test fun namesWithQuotesStayValidJson() {
        val json = PlacePinLoader.geoJson(listOf(MapPin("a", "Joe's \"Best\" \\ Cafe", "cafe", 19.0, 72.8)))
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(json)
        assertTrue(parsed.toString().contains("Best"))
    }

    @Test fun theViewBoxGrowsWithTheMetersPerDp() {
        val near = PlacePinLoader.box(19.2, 72.86, 0.5, 400f, 800f); val far = PlacePinLoader.box(19.2, 72.86, 2.0, 400f, 800f)
        assertTrue(far[2] - far[0] > near[2] - near[0])
    }
}
