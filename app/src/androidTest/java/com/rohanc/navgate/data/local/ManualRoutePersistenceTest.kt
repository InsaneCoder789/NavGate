package com.rohanc.navgate.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.rohanc.navgate.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ManualRoutePersistenceTest {
    @Test fun labelledRoutesSurviveReopenEditAndDeleteWithoutTouchingBookmarks()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="manual-route-test-${UUID.randomUUID()}.db"
        var db=Room.databaseBuilder(context,NavGateDatabase::class.java,name).build()
        try {
            val route=ManualRoute("mumbai","Waterfront","driving",listOf(
                RoutePin("a","Gateway of India",GeoPoint(18.92188,72.83468)),
                RoutePin("b","Marine Drive · मुंबई",GeoPoint(18.9341,72.82365))))
            db.user().put(UserRecord("saved","sentinel","kept",1))
            ManualRouteStore(db).save(route)
            db.close()
            db=Room.databaseBuilder(context,NavGateDatabase::class.java,name).build()
            val store=ManualRouteStore(db)
            assertEquals(route,store.list().single())
            db.user().put(UserRecord("manual-route-v1","broken","not json",2))
            assertEquals(listOf("broken"),store.load().unreadableIds)
            assertEquals(route,store.load().routes.single())
            store.delete("broken")
            val updated=route.copy(name="Evening walk",mode="walking",pins=route.pins.reversed())
            store.save(updated)
            assertEquals(updated,store.list().single())
            store.delete(route.id)
            assertTrue(store.list().isEmpty())
            assertNotNull(db.user().record("saved","sentinel"))
        } finally {db.close();context.deleteDatabase(name)}
    }
}
