package com.rohanc.navgate.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rohanc.navgate.data.search.IndiaPlaceIndex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** India places must come back from the bundled gazetteer, on their own and through the combined search. */
@RunWith(AndroidJUnit4::class)
class SearchRegressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun theBundledIndiaIndexFindsRourkela() = runBlocking {
        val found = IndiaPlaceIndex(context).search("Rourkela")
        println("SEARCH india direct: ${found.take(3).map { it.title + "/" + it.category }}")
        assertTrue("Rourkela in the India index", found.any { it.title == "Rourkela" })
    }

    @Test fun theCombinedSearchPutsTheIndianCityFirst() = runBlocking {
        val page = NavGateServices.get(context).searchData.searchPage("Rourkela", "mumbai")
        println("SEARCH combined: ${page.places.take(4).map { it.title + "/" + it.subtitle }} notice=${page.notice}")
        assertTrue("Rourkela city appears", page.places.any { it.title == "Rourkela" })
    }
}
