# Manual routes and cache behavior

Open **Explore → My routes** to build a personal itinerary. Name it, select walking/driving intent, choose **Pin location on map** and tap a coordinate, then enter a label and add the pin. Coordinate entry is also available. Add at least two pins, adjust their labels/order, and save. Saved itineraries can be reopened, edited and deleted locally.

Each itinerary contains 2–100 ordered labelled WGS84 points. Up to 100 itineraries are stored transactionally in Room's existing generic user-record table. IDs are stable. This feature does not alter the reviewed region graph, saved bookmarks, or active navigation. Unicode labels and exact numeric coordinates persist. Itineraries are private to the local app data; uninstalling/clearing app data removes them. There is no cloud sync.

Connecting pins is an unverified manual itinerary, not a calculated road/path route. The displayed total is a geodesic sum between consecutive points, explicitly labelled **straight-line total**. Walking/driving is the user's intended mode; it does not establish a legal or accessible connection. No ETA or turn guidance is synthesized for this feature. An actual walking/driving router must calculate each leg before these itineraries can become navigable trips.

## Caching already in the app

- Photon search uses normalized query keys, a process-memory LRU capped at 64 queries, a 15-minute positive TTL and 1-minute empty-result TTL. Restarting the process clears it. HTTP 429/503 triggers bounded server-directed backoff; failures are not cached as empty results.
- India's 660,027-record SQLite gazetteer is persistent offline data. Its source retrieval/modification dates do not imply live verification or live business status. APK upgrades compare the shipped content hash; this is not automatic daily refresh.
- The campus planner retains two immutable graph spatial indexes. Road routing now has a separate 12-entry persistent Room cache with five-minute car/fifteen-minute walking TTLs.
- Map tile reuse follows the map SDK/provider cache, independent of place search. No complete offline basemap or offline road routing guarantee is made.

## Road navigation integration

Coordinate endpoints now use the Valhalla walking/car integration described in [ROAD_NAVIGATION.md](ROAD_NAVIGATION.md). Reviewed campus routes keep their local graph contract. Nationwide availability depends on the provider's mapped roads/paths, endpoint matching and connectivity.

Manual itineraries remain user-authored ordered pins. They are not yet automatically expanded into a multi-stop provider route. Their straight-line distance and mode intent must remain separate from calculated road guidance.
