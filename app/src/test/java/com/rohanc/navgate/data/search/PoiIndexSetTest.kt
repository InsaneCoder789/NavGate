package com.rohanc.navgate.data.search

import com.rohanc.navgate.core.CampusPlace
import com.rohanc.navgate.core.GeoPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoiIndexSetTest {
    private class Region(val name: String, val south: Double, val north: Double, val places: List<String>) : PoiSource {
        override fun covers(point: GeoPoint) = point.latitude in south..north
        override suspend fun warmUp() = Unit
        override suspend fun nearby(category: PoiCategory, near: GeoPoint, limit: Int) = places.map { place("$name:$it") }
        override suspend fun nearbyAll(near: GeoPoint, limit: Int) = places.map { place("$name:$it") }
        override suspend fun search(query: String, near: GeoPoint?, limit: Int) = places.filter { it.contains(query, true) }.map { place("$name:$it") }
        private fun place(id: String) = CampusPlace(id = id, title = id, subtitle = "", category = "x", city = name, point = GeoPoint(south, 0.0), aliases = emptyList(), reviewed = false)
    }

    private val mumbai = Region("Mumbai", 18.8, 19.4, listOf("Gateway cafe", "Lingaraj stall"))
    private val odisha = Region("Odisha", 17.8, 22.6, listOf("Lingaraj Temple", "Nandankanan Zoo"))
    private val set = PoiIndexSet(listOf(mumbai, odisha))

    @Test fun `near me is answered by the region the user is in`() = runBlocking {
        assertEquals(listOf("Odisha:Lingaraj Temple", "Odisha:Nandankanan Zoo"), set.nearby(PoiCategory.Food, GeoPoint(20.3, 85.8)).map { it.id })
        assertEquals(listOf("Mumbai:Gateway cafe", "Mumbai:Lingaraj stall"), set.nearby(PoiCategory.Food, GeoPoint(19.0, 72.8)).map { it.id })
    }

    @Test fun `a name is searched in every region and the one the user is in comes first`() = runBlocking {
        val inOdisha = set.search("Lingaraj", GeoPoint(20.3, 85.8)).map { it.id }
        assertEquals(listOf("Odisha:Lingaraj Temple", "Mumbai:Lingaraj stall"), inOdisha)
        assertEquals(listOf("Mumbai:Lingaraj stall", "Odisha:Lingaraj Temple"), set.search("Lingaraj", GeoPoint(19.0, 72.8)).map { it.id })
        assertTrue(set.search("Lingaraj", null).size == 2)
    }

    @Test fun `nearby of every kind comes from the region the user is in`() = runBlocking {
        assertEquals(listOf("Odisha:Lingaraj Temple", "Odisha:Nandankanan Zoo"), set.nearbyAll(GeoPoint(20.3, 85.8)).map { it.id })
        assertTrue(set.nearbyAll(GeoPoint(28.6, 77.2)).isEmpty())
    }

    @Test fun `a point outside every region is not covered`() = runBlocking {
        assertTrue(set.covers(GeoPoint(20.3, 85.8)))
        assertFalse(set.covers(GeoPoint(28.6, 77.2)))
        assertTrue(set.nearby(PoiCategory.Food, GeoPoint(28.6, 77.2)).isEmpty())
    }
}
