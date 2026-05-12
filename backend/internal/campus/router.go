package campus

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"
)

type OSRMRouter struct {
	baseURL string
	client  *http.Client
}

func NewOSRMRouter() *OSRMRouter {
	return &OSRMRouter{
		baseURL: "https://router.project-osrm.org",
		client:  &http.Client{Timeout: 4 * time.Second},
	}
}

func (o *OSRMRouter) Route(req RouteRequest) (RouteResponse, error) {
	profile := "foot"
	travelMode := "walking"
	if strings.EqualFold(req.Profile, "driving") {
		profile = "driving"
		travelMode = "driving"
	}
	coords := fmt.Sprintf("%f,%f;%f,%f", req.Origin.Longitude, req.Origin.Latitude, req.Destination.Longitude, req.Destination.Latitude)
	endpoint := fmt.Sprintf("%s/route/v1/%s/%s?overview=full&steps=true&geometries=geojson", o.baseURL, profile, coords)
	httpReq, err := http.NewRequest(http.MethodGet, endpoint, nil)
	if err != nil {
		return RouteResponse{}, err
	}
	resp, err := o.client.Do(httpReq)
	if err != nil {
		return RouteResponse{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return RouteResponse{}, fmt.Errorf("osrm returned %d", resp.StatusCode)
	}
	var payload osrmRouteResponse
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return RouteResponse{}, err
	}
	if len(payload.Routes) == 0 {
		return RouteResponse{}, fmt.Errorf("osrm returned no routes")
	}
	route := payload.Routes[0]
	coordinates := make([]Coordinate, 0, len(route.Geometry.Coordinates))
	for _, point := range route.Geometry.Coordinates {
		if len(point) < 2 {
			continue
		}
		coordinates = append(coordinates, Coordinate{Latitude: point[1], Longitude: point[0]})
	}
	steps := make([]RouteStep, 0)
	if len(route.Legs) > 0 {
		for _, step := range route.Legs[0].Steps {
			coords := step.Geometry.Coordinates
			target := req.Destination
			if n := len(coords); n > 0 && len(coords[n-1]) >= 2 {
				target = Coordinate{Latitude: coords[n-1][1], Longitude: coords[n-1][0]}
			}
			instruction := buildInstruction(step)
			steps = append(steps, RouteStep{
				Instruction:     instruction,
				DistanceMeters:  step.Distance,
				DurationSeconds: step.Duration,
				TargetLatitude:  target.Latitude,
				TargetLongitude: target.Longitude,
				ManeuverType:    mapManeuver(step.Maneuver.Type, step.Maneuver.Modifier),
				BearingStart:    step.Maneuver.BearingBefore,
				BearingEnd:      step.Maneuver.BearingAfter,
				StreetName:      step.Name,
			})
		}
	}
	return RouteResponse{
		DistanceMeters:  route.Distance,
		DurationSeconds: route.Duration,
		TravelMode:      travelMode,
		PathCoordinates: coordinates,
		Steps:           steps,
		RouteSource:     "osrm",
		RouteConfidence: "high",
		SupportsAR:      true,
	}, nil
}

type osrmRouteResponse struct {
	Code   string      `json:"code"`
	Routes []osrmRoute `json:"routes"`
}

type osrmRoute struct {
	Distance float64      `json:"distance"`
	Duration float64      `json:"duration"`
	Geometry osrmGeometry `json:"geometry"`
	Legs     []osrmLeg    `json:"legs"`
}

type osrmGeometry struct {
	Coordinates [][]float64 `json:"coordinates"`
}

type osrmLeg struct {
	Steps []osrmStep `json:"steps"`
}

type osrmStep struct {
	Distance float64      `json:"distance"`
	Duration float64      `json:"duration"`
	Name     string       `json:"name"`
	Geometry osrmGeometry `json:"geometry"`
	Maneuver osrmManeuver `json:"maneuver"`
}

type osrmManeuver struct {
	Type          string  `json:"type"`
	Modifier      string  `json:"modifier"`
	BearingBefore float64 `json:"bearing_before"`
	BearingAfter  float64 `json:"bearing_after"`
}

func buildInstruction(step osrmStep) string {
	street := strings.TrimSpace(step.Name)
	verb := step.Maneuver.Type
	if street == "" {
		return strings.Title(strings.ReplaceAll(step.Maneuver.Modifier, "_", " "))
	}
	if step.Maneuver.Modifier == "" {
		return fmt.Sprintf("%s on %s", strings.Title(verb), street)
	}
	return fmt.Sprintf("%s %s on %s", strings.Title(verb), step.Maneuver.Modifier, street)
}

func mapManeuver(kind, modifier string) string {
	if kind == "arrive" {
		return "Arrive"
	}
	if kind == "depart" {
		return "Start"
	}
	switch strings.ToLower(modifier) {
	case "left":
		return "Left"
	case "slight left":
		return "SlightLeft"
	case "right":
		return "Right"
	case "slight right":
		return "SlightRight"
	default:
		return "Straight"
	}
}
