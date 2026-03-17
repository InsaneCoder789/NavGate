import axios from "axios";

export type Coordinates = {
  latitude: number;
  longitude: number;
};

export type RouteStep = {
  instruction: string;
  distance: number;
  location: Coordinates;
};

export type RouteData = {
  coordinates: Coordinates[];
  steps: RouteStep[];
  distance: number;
  duration: number;
  bounds?: {
    ne: Coordinates;
    sw: Coordinates;
  };
  summary?: string;
};

/**
 * 🔥 Build human-readable navigation instruction
 */
function buildInstruction(step: any): string {
  const m = step.maneuver || {};
  const type = m.type || "";
  const modifier = m.modifier || "";

  let instruction = "Continue";

  switch (type) {
    case "depart":
      instruction = "Start navigation";
      break;

    case "arrive":
      instruction = "You have arrived";
      break;

    case "turn":
      instruction = `Turn ${modifier || ""}`.trim();
      break;

    case "merge":
      instruction = "Merge";
      break;

    case "fork":
      instruction = `Keep ${modifier || ""}`.trim();
      break;

    case "roundabout":
      instruction = "Enter roundabout";
      break;

    case "exit roundabout":
      instruction = "Exit roundabout";
      break;

    case "end of road":
      instruction = "At end of road";
      break;

    case "new name":
      instruction = "Continue";
      break;

    default:
      instruction = "Continue";
  }

  if (step.name) {
    instruction += ` onto ${step.name}`;
  }

  return instruction;
}

/**
 * 🚀 Routing Engine (OSRM)
 */
export class RoutingEngine {
  private baseUrl = "https://router.project-osrm.org/route/v1";

  async getRoute(
    start: Coordinates,
    end: Coordinates,
    mode: "driving" | "walking" | "cycling" = "driving"
  ): Promise<RouteData | null> {
    try {
      // 🔥 Construct API URL
      const url = `${this.baseUrl}/${mode}/${start.longitude},${start.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson&steps=true`;

      const res = await axios.get(url, {
        timeout: 10000,
        headers: {
          Accept: "application/json",
        },
      });

      if (!res.data?.routes?.length) {
        console.error("No route found");
        return null;
      }

      const route = res.data.routes[0];

      // 🔥 Convert route polyline → coordinates
      const coordinates: Coordinates[] = (route.geometry?.coordinates || []).map(
        (c: any) => ({
          latitude: c[1],
          longitude: c[0],
        })
      );

      // 🔥 Calculate bounds (for map fitting)
      let bounds;
      if (coordinates.length > 0) {
        const lats = coordinates.map((c) => c.latitude);
        const lngs = coordinates.map((c) => c.longitude);

        bounds = {
          ne: {
            latitude: Math.max(...lats),
            longitude: Math.max(...lngs),
          },
          sw: {
            latitude: Math.min(...lats),
            longitude: Math.min(...lngs),
          },
        };
      }

      // 🔥 Extract ALL steps (multi-leg safe)
      const steps: RouteStep[] = [];

      (route.legs || []).forEach((leg: any) => {
        (leg.steps || []).forEach((step: any) => {
          const m = step.maneuver;

          if (!m || !m.location || m.location.length < 2) return;

          steps.push({
            instruction: buildInstruction(step),
            distance: step.distance || 0,
            location: {
              latitude: m.location[1],
              longitude: m.location[0],
            },
          });
        });
      });

      return {
        coordinates,
        steps,
        distance: route.distance || 0,
        duration: route.duration || 0,
        bounds,
        summary: route.legs?.[0]?.summary || "",
      };
    } catch (err: any) {
      console.error(
        "Routing error:",
        err?.response?.data || err?.message || err
      );
      return null;
    }
  }
}