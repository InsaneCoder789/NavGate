import React, { useEffect, useRef } from "react";
import { StyleSheet, View } from "react-native";
import MapView, { Marker, Polyline, Region } from "react-native-maps";
import { CameraController } from "./CameraController";

export type Coordinates = {
  latitude: number;
  longitude: number;
};

type Props = {
  userLocation: Coordinates | null;
  routeCoords: Coordinates[];
  focusOnRoute?: boolean;
  heading?: number;
};

// 🔥 INDIA BOUNDS (Mumbai + Odisha safe region)
const INDIA_BOUNDS = {
  minLat: 15,
  maxLat: 23,
  minLng: 70,
  maxLng: 90,
};

// 🔥 Clamp function (prevents Europe bug)
function clampCoords(coords: Coordinates): Coordinates {
  return {
    latitude: Math.min(
      Math.max(coords.latitude, INDIA_BOUNDS.minLat),
      INDIA_BOUNDS.maxLat
    ),
    longitude: Math.min(
      Math.max(coords.longitude, INDIA_BOUNDS.minLng),
      INDIA_BOUNDS.maxLng
    ),
  };
}

export default function MapViewEngine({
  userLocation,
  routeCoords,
  focusOnRoute = false,
  heading = 0,
}: Props) {
  const mapRef = useRef<MapView>(null!);
  const cameraRef = useRef<CameraController | null>(null);

  // 🔥 INIT CAMERA
  useEffect(() => {
    if (mapRef.current && !cameraRef.current) {
      cameraRef.current = new CameraController(mapRef.current);
    }
  }, []);

  // 🔥 FOLLOW USER (CLAMPED)
  useEffect(() => {
    if (userLocation && cameraRef.current) {
      const safe = clampCoords(userLocation);

      cameraRef.current.followUser(
        safe.latitude,
        safe.longitude,
        heading
      );
    }
  }, [userLocation, heading]);

  // 🔥 FIT ROUTE (CLAMPED)
  useEffect(() => {
    if (focusOnRoute && routeCoords.length > 0 && mapRef.current) {
      const safeRoute = routeCoords.map(clampCoords);

      mapRef.current.fitToCoordinates(safeRoute, {
        edgePadding: {
          top: 100,
          right: 50,
          bottom: 200,
          left: 50,
        },
        animated: true,
      });
    }
  }, [routeCoords, focusOnRoute]);

  // 🔥 FORCE REGION LOCK
  function handleRegionChange(region: Region) {
    const clamped = clampCoords({
      latitude: region.latitude,
      longitude: region.longitude,
    });

    if (
      clamped.latitude !== region.latitude ||
      clamped.longitude !== region.longitude
    ) {
      mapRef.current.animateToRegion({
        ...region,
        latitude: clamped.latitude,
        longitude: clamped.longitude,
      });
    }
  }

  return (
    <View style={styles.container}>
      <MapView
        ref={mapRef}
        style={styles.map}
        showsUserLocation
        showsCompass
        showsMyLocationButton={false}
        pitchEnabled={false}
        rotateEnabled={false}

        // 🔥 LOCK MAP MOVEMENT
        onRegionChangeComplete={handleRegionChange}

        // 🔥 LIMIT ZOOM OUT (prevents world view)
        minZoomLevel={10}
        maxZoomLevel={20}

        initialRegion={
          userLocation
            ? {
                latitude: userLocation.latitude,
                longitude: userLocation.longitude,
                latitudeDelta: 0.01,
                longitudeDelta: 0.01,
              }
            : {
                latitude: 20.2961, // Bhubaneswar fallback
                longitude: 85.8245,
                latitudeDelta: 0.05,
                longitudeDelta: 0.05,
              }
        }
      >
        {userLocation && (
          <Marker coordinate={clampCoords(userLocation)} title="You" />
        )}

        {routeCoords.length > 0 && (
          <Polyline
            coordinates={routeCoords.map(clampCoords)}
            strokeWidth={6}
            strokeColor="#2979FF"
            lineCap="round"
            lineJoin="round"
          />
        )}
      </MapView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  map: { flex: 1 },
});