package com.rohanc.navgate.data.maps

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoadTileSourceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `a tile fetched once is read from disk the next time, even in a new session`() = runBlocking {
        var fetches = 0
        val first = RoadTileSource(pack = { _, _, _ -> null }, cacheDir = folder.newFolder("tiles"), fetch = { _, _, _ -> fetches++; byteArrayOf(1, 2, 3) })
        assertArrayEquals(byteArrayOf(1, 2, 3), first.tile(14, 1, 2))
        val second = RoadTileSource(pack = { _, _, _ -> null }, cacheDir = java.io.File(folder.root, "tiles"), fetch = { _, _, _ -> fetches++; null })
        assertArrayEquals(byteArrayOf(1, 2, 3), second.tile(14, 1, 2))
        assertEquals(1, fetches)
    }

    @Test fun `the saved map pack wins over the network and a missing tile is just null`() = runBlocking {
        val withPack = RoadTileSource(pack = { _, _, _ -> byteArrayOf(9) }, fetch = { _, _, _ -> error("the network must not be used") })
        assertArrayEquals(byteArrayOf(9), withPack.tile(14, 3, 4))
        assertNull(RoadTileSource(pack = { _, _, _ -> null }, fetch = { _, _, _ -> null }).tile(14, 5, 6))
    }
}
