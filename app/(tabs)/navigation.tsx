import React, { useEffect, useRef, useState } from "react";
import { ActivityIndicator, View } from "react-native";

import { ARCameraView } from "../../components/ar/ARCameraView";
import { ArrowRenderer } from "../../components/ar/ArrowRenderer";
import MapViewEngine from "../../components/map/MapViewEngine";
import NavigationHUD from "../../components/ui/navigationHUD";
import SearchBar from "../../components/ui/searchBar";

import { GPSService } from "../../services/location/gpsService";
import { NavigationEngine } from "../../services/navigation/navigationEngine";
import { RoutingEngine } from "../../services/navigation/routingEngine";

export default function NavigationScreen() {
  const [userLocation, setUserLocation] = useState<any>(null);
  const [routeCoords, setRouteCoords] = useState<any[]>([]);
  const [navState, setNavState] = useState<any>(null);

  const [destination, setDestination] = useState<any>(null);
  const [origin, setOrigin] = useState<any>(null);

  const [mode, setMode] = useState<"car" | "bike" | "walk">("car");
  const [isNavigating, setIsNavigating] = useState(false);

  const [isARMode, setIsARMode] = useState(false);
  const [loading, setLoading] = useState(true);

  const gps = useRef(new GPSService()).current;
  const routing = useRef(new RoutingEngine()).current;
  const navigation = useRef(new NavigationEngine()).current;

  useEffect(() => {
    init();
  }, []);

  async function init() {
    const permission = await gps.requestPermission();
    if (!permission) return;

    const current = await gps.getCurrentLocation();
    if (!current) return;

    setUserLocation(current);
    setOrigin(current); // 🔥 CRITICAL FIX (no more Europe bug)
    setLoading(false);

    gps.startTracking((loc) => {
      setUserLocation(loc);

      // 🔥 ONLY update navigation when active
      if (navigation.isActive()) {
        const state = navigation.updateLocation(loc);

        if (state) {
          setNavState({
            ...state,
            bearing: loc.heading || 0,
          });
        }
      }

      // 🔁 REROUTE if deviated
      if (
        destination &&
        navigation.shouldReRoute(loc, routeCoords)
      ) {
        handleRoute(loc, destination, mode);
      }
    });
  }

  // 📍 DESTINATION SELECT
  async function handleDestination(coords: any) {
    const start = origin ?? userLocation;
    if (!start) return;

    setDestination(coords);

    await handleRoute(start, coords, mode);
  }

  // 📍 ORIGIN SELECT
  async function handleOrigin(coords: any) {
    if (!coords) return;

    setOrigin(coords);

    // 🔥 reset route when origin changes
    setRouteCoords([]);
    setIsNavigating(false);
    navigation.stopNavigation();

    if (destination) {
      await handleRoute(coords, destination, mode);
    }
  }

  // 🧭 ROUTE ENGINE
  async function handleRoute(start: any, end: any, modeType: any) {
    const routingMode =
      modeType === "car"
        ? "driving"
        : modeType === "bike"
        ? "cycling"
        : "walking";

    const route = await routing.getRoute(start, end, routingMode);
    if (!route) return;

    setRouteCoords(route.coordinates);

    navigation.setRouteSteps(route.steps);
    navigation.setMode(modeType);

    // 🔥 Reset nav state before starting
    setNavState(null);
    setIsNavigating(false);
  }

  // 🚗 MODE CHANGE
  function handleModeChange(m: "car" | "bike" | "walk") {
    setMode(m);

    if (destination && userLocation) {
      handleRoute(origin ?? userLocation, destination, m);
    }
  }

  // ▶️ START NAVIGATION
  function handleStartNavigation() {
    if (!routeCoords.length) return;

    navigation.startNavigation();
    setIsNavigating(true);

    // 🔥 immediate HUD update
    if (userLocation) {
      const state = navigation.updateLocation(userLocation);

      if (state) {
        setNavState({
          ...state,
          bearing: userLocation.heading || 0,
        });
      }
    }
  }

  // ⏳ LOADING
  if (loading || !userLocation) {
    return (
      <View style={{ flex: 1, justifyContent: "center", alignItems: "center" }}>
        <ActivityIndicator size="large" />
      </View>
    );
  }

  return (
    <View style={{ flex: 1 }}>
      {/* 🗺 MAP MODE */}
      {!isARMode && (
        <>
          <MapViewEngine
            userLocation={origin ?? userLocation}
            routeCoords={routeCoords}
            focusOnRoute={routeCoords.length > 0}
            heading={userLocation?.heading || 0}
          />

          <SearchBar
            userLocation={userLocation} // 🔥 FIX
            onSelectDestination={handleDestination}
            onSelectOrigin={handleOrigin}
          />

          <NavigationHUD
            instruction={
              routeCoords.length === 0
                ? "Search a destination"
                : !isNavigating
                ? "Start navigation"
                : navState?.instruction || "Navigating..."
            }
            distance={navState?.distanceToTurn || 0}
            eta={navState?.eta}
            mode={mode}
            bearing={navState?.bearing || 0}
            isNavigating={isNavigating}
            onStart={handleStartNavigation}
            onModeChange={handleModeChange}
          />
        </>
      )}

      {/* 🧭 AR MODE */}
      {isARMode && (
        <ARCameraView>
          <ArrowRenderer
            direction={navState?.instruction || "Go straight"}
            distance={navState?.distanceToTurn || 0}
          />
        </ARCameraView>
      )}
    </View>
  );
}