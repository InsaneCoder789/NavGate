import { Coordinates, RouteStep } from "./routingEngine";

export type NavigationState = {
  currentStepIndex: number;
  instruction: string;
  distanceToTurn: number;
  eta?: number;
  origin?: Coordinates;
  destination?: Coordinates;
};

export class NavigationEngine {
  private steps: RouteStep[] = [];
  private currentStepIndex = 0;
  private origin: Coordinates | null = null;
  private destination: Coordinates | null = null;
  private isNavigating = false;

  private speedMap = {
    car: 15,
    bike: 6,
    walk: 1.4,
  };

  private currentMode: "car" | "bike" | "walk" = "car";

  setRouteSteps(steps: RouteStep[]) {
    this.steps = steps || [];
    this.currentStepIndex = 0;

    if (steps.length > 0) {
      this.origin = steps[0].location;
      this.destination = steps[steps.length - 1].location;
    }
  }

  setMode(mode: "car" | "bike" | "walk") {
    this.currentMode = mode;
  }

  // 🔥 FIXED START
  startNavigation() {
    if (this.steps.length === 0) return;

    this.isNavigating = true;

    // Skip initial useless step
    if (this.steps.length > 1) {
      this.currentStepIndex = 1;
    }
  }

  stopNavigation() {
    this.isNavigating = false;
    this.currentStepIndex = 0;
  }

  isActive() {
    return this.isNavigating;
  }

  /**
   * 🔥 MAIN NAVIGATION LOOP (SAFE + STABLE)
   */
  updateLocation(userLocation: Coordinates): NavigationState | null {
    if (!this.isNavigating || this.steps.length === 0) return null;

    let currentStep = this.steps[this.currentStepIndex];

    let distance = this.calculateDistance(
      userLocation,
      currentStep.location
    );

    // 🔥 SAFETY: prevent invalid values
    if (!isFinite(distance) || distance > 100000) {
      distance = 0;
    }

    // 🔥 FIX 1: Controlled step skipping (NO infinite loop)
    let safetyCounter = 0;

    while (
      distance < 15 &&
      this.currentStepIndex < this.steps.length - 1 &&
      safetyCounter < 5
    ) {
      this.currentStepIndex++;
      currentStep = this.steps[this.currentStepIndex];

      distance = this.calculateDistance(
        userLocation,
        currentStep.location
      );

      safetyCounter++;
    }

    // 🔥 FIX 2: Smooth progression (only ONE step jump)
    if (this.currentStepIndex < this.steps.length - 1) {
      const nextStep = this.steps[this.currentStepIndex + 1];

      const distToNext = this.calculateDistance(
        userLocation,
        nextStep.location
      );

      if (distToNext < distance && distToNext < 50) {
        this.currentStepIndex++;
        currentStep = this.steps[this.currentStepIndex];

        distance = this.calculateDistance(
          userLocation,
          currentStep.location
        );
      }
    }

    // 🔥 FIX 3: Clean instruction
    let instruction = currentStep.instruction;

    if (distance > 50) {
      instruction = `In ${Math.round(distance)} m, ${currentStep.instruction}`;
    } else if (distance > 10) {
      instruction = currentStep.instruction;
    } else {
      instruction = `Now ${currentStep.instruction}`;
    }

    // 🔥 FIX 4: Remaining distance
    let remainingDistance = distance;

    for (let i = this.currentStepIndex + 1; i < this.steps.length; i++) {
      remainingDistance += this.steps[i].distance || 0;
    }

    // 🔥 FIX 5: ETA
    const speed = this.speedMap[this.currentMode] || 10;
    const eta = remainingDistance / speed;

    return {
      currentStepIndex: this.currentStepIndex,
      instruction,
      distanceToTurn: distance,
      eta,
      origin: this.origin || undefined,
      destination: this.destination || undefined,
    };
  }

  /**
   * 🔁 Rerouting
   */
  shouldReRoute(
    userLocation: Coordinates,
    routeCoords: Coordinates[]
  ): boolean {
    if (!routeCoords || routeCoords.length === 0) return false;

    const nearest = routeCoords.reduce((prev, curr) => {
      const d1 = this.calculateDistance(userLocation, prev);
      const d2 = this.calculateDistance(userLocation, curr);
      return d1 < d2 ? prev : curr;
    });

    const distanceFromRoute = this.calculateDistance(userLocation, nearest);

    return distanceFromRoute > 50;
  }

  /**
   * 📏 Distance
   */
  private calculateDistance(a: Coordinates, b: Coordinates): number {
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

  getOrigin() {
    return this.origin;
  }

  getDestination() {
    return this.destination;
  }
}