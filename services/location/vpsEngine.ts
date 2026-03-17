import { GPSLocation } from "./gpsService";

export type VPSState = {
  correctedLocation: GPSLocation;
  confidence: number; // 0 → 1
};

export class VPSEngine {
  private lastLocation: GPSLocation | null = null;

  /**
   * Basic VPS correction (MVP version)
   * Later we will replace this with real vision-based matching
   */
  correctPosition(current: GPSLocation): VPSState {
    if (!this.lastLocation) {
      this.lastLocation = current;
      return {
        correctedLocation: current,
        confidence: 0.5,
      };
    }

    // Simple smoothing (Kalman-lite)
    const smoothed: GPSLocation = {
      latitude: (this.lastLocation.latitude + current.latitude) / 2,
      longitude: (this.lastLocation.longitude + current.longitude) / 2,
      heading: current.heading,
      accuracy: current.accuracy,
      speed: current.speed,
    };

    this.lastLocation = smoothed;

    return {
      correctedLocation: smoothed,
      confidence: 0.7,
    };
  }

  /**
   * Calculate bearing between two points
   * Used for AR arrow direction
   */
  getBearing(from: GPSLocation, to: GPSLocation): number {
    const lat1 = (from.latitude * Math.PI) / 180;
    const lat2 = (to.latitude * Math.PI) / 180;
    const dLon = ((to.longitude - from.longitude) * Math.PI) / 180;

    const y = Math.sin(dLon) * Math.cos(lat2);
    const x =
      Math.cos(lat1) * Math.sin(lat2) -
      Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);

    const bearing = (Math.atan2(y, x) * 180) / Math.PI;

    return (bearing + 360) % 360;
  }

  /**
   * Calculate AR arrow rotation
   */
  getRelativeDirection(userHeading: number, targetBearing: number): number {
    let diff = targetBearing - userHeading;

    if (diff > 180) diff -= 360;
    if (diff < -180) diff += 360;

    return diff;
  }
}