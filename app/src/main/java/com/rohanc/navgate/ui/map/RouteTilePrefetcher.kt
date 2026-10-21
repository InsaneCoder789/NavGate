package com.rohanc.navgate.ui.map

import android.content.Context
import com.rohanc.navgate.core.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * Saves the map along a planned route to the phone, so the map keeps drawing if the connection drops mid-trip.
 * The route is cut into ~2.5 km corridor boxes; the previous trip's boxes are removed first so storage stays bounded.
 * Everything is best effort: any failure just leaves the normal on-demand tile cache in place.
 */
object RouteTilePrefetcher {
    private const val TAG = "navgate-route:"
    private const val BOX_METERS = 2_500.0
    private const val BUFFER_DEGREES = 0.004          // about 450 m either side of the road
    private const val MAX_BOXES = 12                  // roughly the first 30 km of a trip
    private const val STYLE = "asset://navgate-map-dark.json"   // all styles share the same tile sources, so one download serves every look
    private var lastRouteId: String? = null

    /** Call on the main thread. Safe to call repeatedly for the same route. */
    fun prefetch(context: Context, routeId: String, geometry: List<GeoPoint>) {
        if (routeId == lastRouteId || geometry.size < 2) return
        lastRouteId = routeId
        runCatching {
            val app = context.applicationContext
            MapLibre.getInstance(app)
            val manager = OfflineManager.getInstance(app)
            manager.setOfflineMapboxTileCountLimit(80_000)
            manager.setMaximumAmbientCacheSize(150L * 1024 * 1024, object : OfflineManager.FileSourceCallback { override fun onSuccess() = Unit; override fun onError(message: String) = Unit })
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(regions: Array<OfflineRegion>?) {
                    val old = regions.orEmpty().filter { String(it.metadata).startsWith(TAG) }
                    if (old.isEmpty()) { create(manager, routeId, geometry); return }
                    var remaining = old.size
                    old.forEach { it.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() { if (--remaining == 0) create(manager, routeId, geometry) }
                        override fun onError(error: String) { if (--remaining == 0) create(manager, routeId, geometry) }
                    }) }
                }
                override fun onError(error: String) = create(manager, routeId, geometry)
            })
        }
    }

    private fun create(manager: OfflineManager, routeId: String, geometry: List<GeoPoint>) {
        for (box in corridorBoxes(geometry).take(MAX_BOXES)) runCatching {
            val definition = OfflineTilePyramidRegionDefinition(STYLE, LatLngBounds.from(box[2], box[3], box[0], box[1]), 10.0, 16.0, 2f)
            manager.createOfflineRegion(definition, (TAG + routeId).toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(region: OfflineRegion) = region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                override fun onError(error: String) = Unit
            })
        }
    }

    /** Boxes as [south, west, north, east], one per ~[BOX_METERS] of road, padded so the sides of the road are covered too. */
    internal fun corridorBoxes(geometry: List<GeoPoint>): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var south = Double.MAX_VALUE; var west = Double.MAX_VALUE; var north = -Double.MAX_VALUE; var east = -Double.MAX_VALUE
        var travelled = 0.0
        fun flush() { if (south <= north) out.add(doubleArrayOf(south - BUFFER_DEGREES, west - BUFFER_DEGREES, north + BUFFER_DEGREES, east + BUFFER_DEGREES)) }
        geometry.forEachIndexed { index, point ->
            if (index > 0) travelled += geometry[index - 1].distanceTo(point)
            south = minOf(south, point.latitude); north = maxOf(north, point.latitude); west = minOf(west, point.longitude); east = maxOf(east, point.longitude)
            if (travelled >= BOX_METERS) {
                flush(); travelled = 0.0
                // The next box starts where this one ended, so there is no gap between them.
                south = point.latitude; north = point.latitude; west = point.longitude; east = point.longitude
            }
        }
        flush()
        return out
    }
}
