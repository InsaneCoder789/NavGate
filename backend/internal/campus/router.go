package campus

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"
)

type Router interface {
	Route(req RouteRequest) (RouteResponse, error)
}

type RoutingProvider interface {
	Name() string
	Route(req RouteRequest) (RouteResponse, error)
}

type ProviderChain struct {
	providers []RoutingProvider
}

func NewProviderChain(providers ...RoutingProvider) *ProviderChain {
	filtered := make([]RoutingProvider, 0, len(providers))
	for _, provider := range providers {
		if provider != nil {
			filtered = append(filtered, provider)
		}
	}
	return &ProviderChain{providers: filtered}
}

func (p *ProviderChain) Route(req RouteRequest) (RouteResponse, error) {
	var errs []string
	for _, provider := range p.providers {
		route, err := provider.Route(req)
		if err == nil {
			return route, nil
		}
		errs = append(errs, fmt.Sprintf("%s: %v", provider.Name(), err))
	}
	return RouteResponse{}, fmt.Errorf("all providers failed: %s", strings.Join(errs, " | "))
}

func NewConfiguredRouterFromEnv() Router {
	providerOrder := splitCSV(os.Getenv("NAVGATE_ROUTER_ORDER"), []string{"valhalla", "osrm"})
	providers := make([]RoutingProvider, 0, len(providerOrder))
	for _, id := range providerOrder {
		switch strings.ToLower(strings.TrimSpace(id)) {
		case "valhalla":
			providers = append(providers, NewValhallaRouter(os.Getenv("NAVGATE_VALHALLA_URL")))
		case "osrm":
			providers = append(providers, NewOSRMRouter(os.Getenv("NAVGATE_OSRM_URL")))
		}
	}
	return NewProviderChain(providers...)
}

type OSRMRouter struct {
	footBaseURL string
	carBaseURL  string
	client      *http.Client
}

func NewOSRMRouter(baseURL string) *OSRMRouter {
	if strings.TrimSpace(baseURL) == "" {
		baseURL = "http://localhost:5000"
	}
	footURL := os.Getenv("NAVGATE_OSRM_FOOT_URL")
	carURL := os.Getenv("NAVGATE_OSRM_CAR_URL")
	if strings.TrimSpace(footURL) == "" {
		footURL = baseURL
	}
	if strings.TrimSpace(carURL) == "" {
		carURL = baseURL
	}
	return &OSRMRouter{
		footBaseURL: strings.TrimSuffix(footURL, "/"),
		carBaseURL:  strings.TrimSuffix(carURL, "/"),
		client:      &http.Client{Timeout: 4 * time.Second},
	}
}

func (o *OSRMRouter) Name() string {
	return "osrm"
}

func (o *OSRMRouter) Route(req RouteRequest) (RouteResponse, error) {
	profile := "foot"
	travelMode := "walking"
	baseURL := o.footBaseURL
	if strings.EqualFold(req.Profile, "driving") {
		profile = "driving"
		travelMode = "driving"
		baseURL = o.carBaseURL
	}
	coords := fmt.Sprintf("%f,%f;%f,%f", req.Origin.Longitude, req.Origin.Latitude, req.Destination.Longitude, req.Destination.Latitude)
	endpoint := fmt.Sprintf("%s/route/v1/%s/%s?overview=full&steps=true&geometries=geojson", baseURL, profile, coords)
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

type ValhallaRouter struct {
	baseURL string
	client  *http.Client
}

func NewValhallaRouter(baseURL string) *ValhallaRouter {
	if strings.TrimSpace(baseURL) == "" {
		baseURL = "http://localhost:8002"
	}
	return &ValhallaRouter{
		baseURL: strings.TrimSuffix(baseURL, "/"),
		client:  &http.Client{Timeout: 6 * time.Second},
	}
}

func (v *ValhallaRouter) Name() string {
	return "valhalla"
}

func (v *ValhallaRouter) Route(req RouteRequest) (RouteResponse, error) {
	costing := "pedestrian"
	travelMode := "walking"
	if strings.EqualFold(req.Profile, "driving") {
		costing = "auto"
		travelMode = "driving"
	}
	payload := map[string]any{
		"locations": []map[string]float64{
			{"lat": req.Origin.Latitude, "lon": req.Origin.Longitude},
			{"lat": req.Destination.Latitude, "lon": req.Destination.Longitude},
		},
		"costing": costing,
		"directions_options": map[string]any{
			"units":    "kilometers",
			"language": "en-US",
		},
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return RouteResponse{}, err
	}
	endpoint := v.baseURL + "/route"
	httpReq, err := http.NewRequest(http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return RouteResponse{}, err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	resp, err := v.client.Do(httpReq)
	if err != nil {
		return RouteResponse{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return RouteResponse{}, fmt.Errorf("valhalla returned %d", resp.StatusCode)
	}

	var result valhallaRouteResponse
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return RouteResponse{}, err
	}
	if len(result.Trip.Legs) == 0 {
		return RouteResponse{}, fmt.Errorf("valhalla returned no legs")
	}

	totalDistanceKm := 0.0
	totalDurationSec := 0.0
	coordinates := make([]Coordinate, 0, len(result.Trip.Legs)*8)
	steps := make([]RouteStep, 0, 16)
	for _, leg := range result.Trip.Legs {
		totalDistanceKm += leg.Summary.Length
		totalDurationSec += leg.Summary.Time
		legCoordinates, err := decodePolyline6(leg.Shape)
		if err == nil && len(legCoordinates) > 0 {
			coordinates = append(coordinates, legCoordinates...)
		}
		for _, maneuver := range leg.Maneuvers {
			target := req.Destination
			if maneuver.EndShapeIndex >= 0 && maneuver.EndShapeIndex < len(legCoordinates) {
				target = legCoordinates[maneuver.EndShapeIndex]
			}
			steps = append(steps, RouteStep{
				Instruction:     strings.TrimSpace(maneuver.Instruction),
				DistanceMeters:  maneuver.Length * 1000.0,
				DurationSeconds: maneuver.Time,
				TargetLatitude:  target.Latitude,
				TargetLongitude: target.Longitude,
				ManeuverType:    mapValhallaManeuver(maneuver.Type),
				StreetName:      strings.TrimSpace(maneuver.StreetNamesJoined()),
			})
		}
	}
	if len(coordinates) == 0 {
		coordinates = []Coordinate{req.Origin, req.Destination}
	}
	return RouteResponse{
		DistanceMeters:  totalDistanceKm * 1000.0,
		DurationSeconds: totalDurationSec,
		TravelMode:      travelMode,
		PathCoordinates: coordinates,
		Steps:           steps,
		RouteSource:     "valhalla",
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

type valhallaRouteResponse struct {
	Trip struct {
		Legs []valhallaLeg `json:"legs"`
	} `json:"trip"`
}

type valhallaLeg struct {
	Summary struct {
		Time   float64 `json:"time"`
		Length float64 `json:"length"`
	} `json:"summary"`
	Shape     string             `json:"shape"`
	Maneuvers []valhallaManeuver `json:"maneuvers"`
}

type valhallaManeuver struct {
	Type          int      `json:"type"`
	Instruction   string   `json:"instruction"`
	Length        float64  `json:"length"`
	Time          float64  `json:"time"`
	BeginShapeIdx int      `json:"begin_shape_index"`
	EndShapeIndex int      `json:"end_shape_index"`
	StreetNames   []string `json:"street_names"`
}

func (m valhallaManeuver) StreetNamesJoined() string {
	if len(m.StreetNames) == 0 {
		return ""
	}
	return strings.Join(m.StreetNames, ", ")
}

func buildInstruction(step osrmStep) string {
	street := strings.TrimSpace(step.Name)
	verb := step.Maneuver.Type
	if street == "" {
		return titleCase(strings.ReplaceAll(step.Maneuver.Modifier, "_", " "))
	}
	if step.Maneuver.Modifier == "" {
		return fmt.Sprintf("%s on %s", titleCase(verb), street)
	}
	return fmt.Sprintf("%s %s on %s", titleCase(verb), step.Maneuver.Modifier, street)
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

func mapValhallaManeuver(kind int) string {
	// Common Valhalla maneuver types:
	// 1 Start, 4 Right, 6 Left, 8 Right, 10 Left, 15 Destination
	switch kind {
	case 1:
		return "Start"
	case 4, 8:
		return "Right"
	case 5, 9:
		return "SlightRight"
	case 6, 10:
		return "Left"
	case 7, 11:
		return "SlightLeft"
	case 15:
		return "Arrive"
	default:
		return "Straight"
	}
}

func splitCSV(raw string, defaults []string) []string {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return defaults
	}
	parts := strings.Split(trimmed, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		clean := strings.TrimSpace(p)
		if clean != "" {
			out = append(out, clean)
		}
	}
	if len(out) == 0 {
		return defaults
	}
	return out
}

func decodePolyline6(encoded string) ([]Coordinate, error) {
	coords := make([]Coordinate, 0, 128)
	var (
		index int
		lat   int
		lon   int
	)
	for index < len(encoded) {
		dLat, nextIdx, err := decodeNextComponent(encoded, index)
		if err != nil {
			return nil, err
		}
		index = nextIdx
		dLon, nextIdx, err := decodeNextComponent(encoded, index)
		if err != nil {
			return nil, err
		}
		index = nextIdx
		lat += dLat
		lon += dLon
		coords = append(coords, Coordinate{
			Latitude:  float64(lat) / 1e6,
			Longitude: float64(lon) / 1e6,
		})
	}
	return coords, nil
}

func decodeNextComponent(encoded string, start int) (int, int, error) {
	result := 0
	shift := 0
	index := start
	for {
		if index >= len(encoded) {
			return 0, index, fmt.Errorf("invalid polyline: truncated")
		}
		b := int(encoded[index]) - 63
		index++
		result |= (b & 0x1f) << shift
		shift += 5
		if b < 0x20 {
			break
		}
	}
	delta := result >> 1
	if result&1 != 0 {
		delta = ^delta
	}
	return delta, index, nil
}

func titleCase(value string) string {
	if strings.TrimSpace(value) == "" {
		return value
	}
	return strings.ToUpper(value[:1]) + strings.ToLower(value[1:])
}
