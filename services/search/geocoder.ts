import { SearchResult } from "./autocomplete";

export type Coordinates = {
  latitude: number;
  longitude: number;
};

export type AddressResult = {
  name: string;
  display: string;
  coords: Coordinates;
};

export class Geocoder {

  /**
   * Convert selected place → coordinates
   */
  getCoordinates(place: SearchResult): Coordinates | null {
    if (!place || place.lat == null || place.lon == null) {
      console.warn("Invalid place passed to Geocoder");
      return null;
    }

    return {
      latitude: Number(place.lat),
      longitude: Number(place.lon),
    };
  }

  /**
   * 🔥 Reverse geocode (coords → address)
   */
  async reverseGeocode(coords: Coordinates): Promise<AddressResult | null> {
    try {
      const res = await fetch(
        `https://nominatim.openstreetmap.org/reverse?lat=${coords.latitude}&lon=${coords.longitude}&format=json`
      );

      const data = await res.json();

      if (!data || !data.display_name) return null;

      return {
        name: data.name || data.display_name,
        display: data.display_name,
        coords,
      };

    } catch (err) {
      console.log("Reverse geocode error:", err);
      return null;
    }
  }

  /**
   * 🔥 Validate coordinates (avoid map crashes)
   */
  isValidCoords(coords: Coordinates): boolean {
    return (
      coords &&
      typeof coords.latitude === "number" &&
      typeof coords.longitude === "number" &&
      coords.latitude >= -90 &&
      coords.latitude <= 90 &&
      coords.longitude >= -180 &&
      coords.longitude <= 180
    );
  }

  /**
   * 🔥 Distance helper (for future use)
   */
  calculateDistance(a: Coordinates, b: Coordinates): number {
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