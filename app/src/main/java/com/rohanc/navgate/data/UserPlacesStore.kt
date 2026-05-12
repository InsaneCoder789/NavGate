package com.rohanc.navgate.data

import android.content.Context
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.model.PlaceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

interface UserPlacesStore {
    suspend fun savedPlaces(): List<PlaceSearchResult>

    suspend fun recentPlaces(): List<PlaceSearchResult>

    suspend fun toggleSaved(place: PlaceSearchResult): List<PlaceSearchResult>

    suspend fun recordRecent(place: PlaceSearchResult): List<PlaceSearchResult>
}

class SharedPrefsUserPlacesStore(
    context: Context,
) : UserPlacesStore {
    private val prefs = context.getSharedPreferences("navgate_user_places", Context.MODE_PRIVATE)

    override suspend fun savedPlaces(): List<PlaceSearchResult> = withContext(Dispatchers.IO) { readList(SAVED_KEY) }

    override suspend fun recentPlaces(): List<PlaceSearchResult> = withContext(Dispatchers.IO) { readList(RECENTS_KEY) }

    override suspend fun toggleSaved(place: PlaceSearchResult): List<PlaceSearchResult> =
        withContext(Dispatchers.IO) {
            val current = readList(SAVED_KEY).toMutableList()
            val index = current.indexOfFirst { it.id == place.id }
            if (index >= 0) {
                current.removeAt(index)
            } else {
                current.add(0, place)
            }
            writeList(SAVED_KEY, current.distinctBy { it.id }.take(40))
            readList(SAVED_KEY)
        }

    override suspend fun recordRecent(place: PlaceSearchResult): List<PlaceSearchResult> =
        withContext(Dispatchers.IO) {
            val updated = listOf(place) + readList(RECENTS_KEY).filterNot { it.id == place.id }
            writeList(RECENTS_KEY, updated.take(30))
            readList(RECENTS_KEY)
        }

    private fun readList(key: String): List<PlaceSearchResult> {
        val raw = prefs.getString(key, "[]") ?: "[]"
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(item.toPlace())
            }
        }
    }

    private fun writeList(key: String, places: List<PlaceSearchResult>) {
        val array = JSONArray()
        places.forEach { place -> array.put(place.toJson()) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    private fun JSONObject.toPlace(): PlaceSearchResult =
        PlaceSearchResult(
            id = getString("id"),
            title = getString("title"),
            subtitle = getString("subtitle"),
            latitude = getDouble("latitude"),
            longitude = getDouble("longitude"),
            type = PlaceType.valueOf(getString("type")),
            city = optString("city").takeIf { it.isNotBlank() },
            campusLabel = optString("campusLabel").takeIf { it.isNotBlank() },
            category = optString("category").takeIf { it.isNotBlank() },
        )

    private fun PlaceSearchResult.toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put("subtitle", subtitle)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("type", type.name)
            .put("city", city)
            .put("campusLabel", campusLabel)
            .put("category", category)

    companion object {
        private const val SAVED_KEY = "saved_places"
        private const val RECENTS_KEY = "recent_places"
    }
}

class InMemoryUserPlacesStore(
    private var saved: List<PlaceSearchResult> = emptyList(),
    private var recents: List<PlaceSearchResult> = emptyList(),
) : UserPlacesStore {
    override suspend fun savedPlaces(): List<PlaceSearchResult> = saved

    override suspend fun recentPlaces(): List<PlaceSearchResult> = recents

    override suspend fun toggleSaved(place: PlaceSearchResult): List<PlaceSearchResult> {
        saved = if (saved.any { it.id == place.id }) {
            saved.filterNot { it.id == place.id }
        } else {
            listOf(place) + saved.filterNot { it.id == place.id }
        }
        return saved
    }

    override suspend fun recordRecent(place: PlaceSearchResult): List<PlaceSearchResult> {
        recents = (listOf(place) + recents.filterNot { it.id == place.id }).take(30)
        return recents
    }
}
