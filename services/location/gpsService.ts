import * as Location from "expo-location";

export type GPSLocation = {
  latitude: number;
  longitude: number;
  accuracy?: number;
  heading?: number;
  speed?: number;
};

export class GPSService {
  private subscription: Location.LocationSubscription | null = null;
  private lastLocation: GPSLocation | null = null;
  private lastTimestamp: number = 0;

  /**
   * Request permission
   */
  async requestPermission(): Promise<boolean> {
    try {
      const { status } = await Location.requestForegroundPermissionsAsync();
      return status === "granted";
    } catch (err) {
      console.log("Permission error:", err);
      return false;
    }
  }

  /**
   * Get current location once
   */
  async getCurrentLocation(): Promise<GPSLocation | null> {
    try {
      const loc = await Location.getCurrentPositionAsync({
        accuracy: Location.Accuracy.High,
      });

      const result: GPSLocation = {
        latitude: loc.coords.latitude,
        longitude: loc.coords.longitude,
        accuracy: loc.coords.accuracy ?? undefined,
        heading: loc.coords.heading ?? undefined,
        speed: loc.coords.speed ?? undefined,
      };

      this.lastLocation = result;
      this.lastTimestamp = Date.now();

      return result;
    } catch (err) {
      console.log("GPS error:", err);
      return null;
    }
  }

  /**
   * Start live tracking (SMOOTH + STABLE)
   */
  async startTracking(callback: (loc: GPSLocation) => void) {
    if (this.subscription) return;

    this.subscription = await Location.watchPositionAsync(
      {
        accuracy: Location.Accuracy.BestForNavigation,
        timeInterval: 1000,
        distanceInterval: 2,
      },
      (loc) => {
        const now = Date.now();

        let newLocation: GPSLocation = {
          latitude: loc.coords.latitude,
          longitude: loc.coords.longitude,
          accuracy: loc.coords.accuracy ?? undefined,
          heading: loc.coords.heading ?? undefined,
          speed: loc.coords.speed ?? undefined,
        };

        // 🔥 IGNORE BAD GPS JUMPS (noise filter)
        if (this.lastLocation) {
          const dist = this.calculateDistance(
            this.lastLocation,
            newLocation
          );

          const timeDiff = (now - this.lastTimestamp) / 1000;

          const speed = dist / (timeDiff || 1);

          // ❌ unrealistic jump (>50 m/s ~ 180 km/h)
          if (speed > 50) return;
        }

        // 🔥 HEADING FIX
        if (
          (!newLocation.heading || newLocation.heading === 0) &&
          this.lastLocation
        ) {
          newLocation.heading = this.calculateBearing(
            this.lastLocation,
            newLocation
          );
        }

        // 🔥 SMOOTHING (reduces jitter)
        if (this.lastLocation) {
          newLocation.latitude =
            this.lastLocation.latitude * 0.7 + newLocation.latitude * 0.3;

          newLocation.longitude =
            this.lastLocation.longitude * 0.7 + newLocation.longitude * 0.3;
        }

        this.lastLocation = newLocation;
        this.lastTimestamp = now;

        callback(newLocation);
      }
    );
  }

  /**
   * Stop tracking
   */
  stopTracking() {
    if (this.subscription) {
      this.subscription.remove();
      this.subscription = null;
    }
  }

  /**
   * Bearing calculation
   */
  private calculateBearing(start: GPSLocation, end: GPSLocation): number {
    const toRad = (deg: number) => (deg * Math.PI) / 180;
    const toDeg = (rad: number) => (rad * 180) / Math.PI;

    const lat1 = toRad(start.latitude);
    const lon1 = toRad(start.longitude);
    const lat2 = toRad(end.latitude);
    const lon2 = toRad(end.longitude);

    const dLon = lon2 - lon1;

    const y = Math.sin(dLon) * Math.cos(lat2);
    const x =
      Math.cos(lat1) * Math.sin(lat2) -
      Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);

    const bearing = toDeg(Math.atan2(y, x));
    return (bearing + 360) % 360;
  }

  /**
   * Distance helper (meters)
   */
  private calculateDistance(a: GPSLocation, b: GPSLocation): number {
    const R = 6371000;

    const dLat = ((b.latitude - a.latitude) * Math.PI) / 180;
    const dLon = ((b.longitude - a.longitude) * Math.PI) / 180;

    const lat1 = (a.latitude * Math.PI) / 180;
    const lat2 = (b.latitude * Math.PI) / 180;

    const x =
      Math.sin(dLat / 2) ** 2 +
      Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;

    return R * 2 * Math.atan2(Math.sqrt(x), Math.sqrt(1 - x));
  }
}