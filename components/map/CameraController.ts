import MapView, { Region } from "react-native-maps";

export class CameraController {
  private mapRef: MapView | null;

  constructor(mapRef: MapView | null) {
    this.mapRef = mapRef;
  }

  moveTo(latitude: number, longitude: number, zoom: number = 0.01) {
    if (!this.mapRef) return;

    const region: Region = {
      latitude,
      longitude,
      latitudeDelta: zoom,
      longitudeDelta: zoom,
    };

    this.mapRef.animateToRegion(region, 500);
  }

  followUser(latitude: number, longitude: number, heading: number = 0) {
    if (!this.mapRef) return;

    this.mapRef.animateCamera(
      {
        center: { latitude, longitude },
        zoom: 18,
        heading,
        pitch: 45,
      },
      { duration: 500 }
    );
  }

  setBearing(bearing: number) {
    if (!this.mapRef) return;

    this.mapRef.animateCamera({ heading: bearing });
  }

  resetOrientation() {
    if (!this.mapRef) return;

    this.mapRef.animateCamera({ heading: 0, pitch: 0 });
  }
}