package com.rohanc.navgate.data.search

import com.rohanc.navgate.core.CampusPlace
import com.rohanc.navgate.core.GeoPoint
import org.junit.Assert.*
import org.junit.Test

class PlaceSearchRankingTest {
    private fun place(id:String,name:String,latitude:Double=19.0,category:String="HSP",aliases:List<String> = emptyList(),reviewed:Boolean=false) =
        CampusPlace(id,name,city="Mumbai",category=category,point=GeoPoint(latitude,72.8),aliases=aliases,reviewed=reviewed)

    @Test fun `exact names beat nearby prefix and aliases are searchable with accents`() {
        val exact=place("osm:n:1","Café Central",20.0)
        val prefix=place("osm:n:2","Cafe Central Annex")
        val alias=place("geonames:3","Old Hospital",aliases=listOf("Cafe Central"))
        assertEquals(listOf(exact,alias,prefix),PlaceSearchRanking.merge("cafe central",listOf(prefix,alias,exact),GeoPoint(19.0,72.8)))
    }
    @Test fun `proximity orders equal matches but distant branches remain separate`() {
        val near=place("osm:n:1","Hospital",19.01)
        val far=place("geonames:2","Hospital",28.0)
        assertEquals(listOf(near,far),PlaceSearchRanking.merge("hospital",listOf(far,near),GeoPoint(19.0,72.8)))
    }
    @Test fun `cross source duplicate preserves reviewed entrance while category mismatch is retained`() {
        val reviewed=place("campus:1","Central",reviewed=true)
        val national=place("geonames:2","Central",19.0001)
        val school=place("osm:n:3","Central",19.0001,"SCH")
        assertEquals(setOf(reviewed,school),PlaceSearchRanking.merge("Central",listOf(national,school,reviewed),null).toSet())
    }
    @Test fun `same provider nearby branches are not collapsed`() {
        val first=place("osm:n:1","Cafe")
        val second=place("osm:n:2","Cafe",19.0001)
        assertEquals(2,PlaceSearchRanking.merge("Cafe",listOf(first,second),null).size)
    }
    @Test fun `stale inaccurate future or invalid location cannot bias search`() {
        val point=GeoPoint(19.0,72.8)
        assertEquals(point,SearchLocation(point,600_000,5_000.0).usable())
        assertNull(SearchLocation(point,600_001,10.0).usable())
        assertNull(SearchLocation(point,-1,10.0).usable())
        assertNull(SearchLocation(point,0,5_000.1).usable())
        assertNull(SearchLocation(point,0,Double.NaN).usable())
        assertNull(SearchLocation(point,0,0.0).usable())
        assertNull(SearchLocation(GeoPoint(91.0,0.0),0,1.0).usable())
    }
    @Test fun `category queries recognize source feature codes`() {
        val hospital=place("geonames:1","KEM",category="HSP")
        val school=place("geonames:2","Hospital school",category="SCH")
        assertEquals(hospital,PlaceSearchRanking.merge("hospitals",listOf(school,hospital),null).first())
        assertEquals("education",PlaceSearchRanking.requestedCategory("universities"))
    }
    @Test fun `Latin accents fold but Indian script vowel marks remain intact`() {
        assertEquals("cafe",PlaceSearchRanking.normalize("Café"))
        assertEquals("mumbai",PlaceSearchRanking.normalize("Mumbai"))
        assertEquals("मुंबई",PlaceSearchRanking.normalize("मुंबई"))
        assertEquals("दिल्ली",PlaceSearchRanking.normalize("दिल्ली"))
        val mumbai=place("geonames:1","Mumbai",aliases=listOf("मुंबई"))
        val unrelated=place("geonames:2","मबई")
        assertEquals(mumbai,PlaceSearchRanking.merge("मुंबई",listOf(unrelated,mumbai),null).first())
    }

}
