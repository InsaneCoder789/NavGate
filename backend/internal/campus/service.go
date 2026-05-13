package campus

import (
	"embed"
	"encoding/json"
	"fmt"
	"math"
	"sort"
	"strings"
)

//go:embed seed/*.json
var seedFS embed.FS

type Service struct {
	pois       []CustomPoi
	poisByID   map[string]CustomPoi
	categories []CategorySummary
	router     Router
}

func NewService() *Service {
	return NewServiceWithRouter(NewConfiguredRouterFromEnv())
}

func NewServiceWithRouter(router Router) *Service {
	pois := mustLoadPOIs()
	return &Service{
		pois:       pois,
		poisByID:   indexPois(pois),
		categories: buildCategories(pois),
		router:     router,
	}
}

func mustLoadPOIs() []CustomPoi {
	content, err := seedFS.ReadFile("seed/custom_pois.json")
	if err != nil {
		panic(fmt.Errorf("read custom POI seed: %w", err))
	}
	var pois []CustomPoi
	if err := json.Unmarshal(content, &pois); err != nil {
		panic(fmt.Errorf("decode custom POI seed: %w", err))
	}
	for i := range pois {
		pois[i].Name = strings.TrimSpace(pois[i].Name)
		pois[i].Subtitle = strings.TrimSpace(pois[i].Subtitle)
	}
	sort.Slice(pois, func(i, j int) bool {
		if pois[i].CampusNumber == pois[j].CampusNumber {
			return pois[i].Name < pois[j].Name
		}
		return pois[i].CampusNumber < pois[j].CampusNumber
	})
	return pois
}

func indexPois(pois []CustomPoi) map[string]CustomPoi {
	indexed := make(map[string]CustomPoi, len(pois))
	for _, poi := range pois {
		indexed[poi.ID] = poi
	}
	return indexed
}

func buildCategories(pois []CustomPoi) []CategorySummary {
	counts := map[string]*CategorySummary{}
	for _, poi := range pois {
		id := slug(poi.CategoryNormalized)
		current := counts[id]
		if current == nil {
			current = &CategorySummary{ID: id, Label: poi.CategoryNormalized, Representative: poi.PlaceType}
			counts[id] = current
		}
		current.Count++
	}
	categories := make([]CategorySummary, 0, len(counts))
	for _, item := range counts {
		categories = append(categories, *item)
	}
	sort.Slice(categories, func(i, j int) bool { return categories[i].Label < categories[j].Label })
	return categories
}

func (s *Service) Search(query, city, campus, category string, limit int) []PlaceSearchResult {
	query = strings.ToLower(strings.TrimSpace(query))
	city = strings.ToLower(strings.TrimSpace(city))
	campus = strings.ToLower(strings.TrimSpace(campus))
	category = strings.ToLower(strings.TrimSpace(category))
	if limit <= 0 {
		limit = 40
	}
	results := make([]PlaceSearchResult, 0, limit)
	for _, poi := range s.pois {
		if city != "" && strings.ToLower(poi.City) != city {
			continue
		}
		if campus != "" && strings.ToLower(poi.CampusLabel) != campus {
			continue
		}
		if category != "" && strings.ToLower(poi.CategoryNormalized) != category && strings.ToLower(poi.CategoryRaw) != category {
			continue
		}
		if query != "" && !matchesPoi(poi, query) {
			continue
		}
		results = append(results, toSearchResult(poi))
		if len(results) >= limit {
			break
		}
	}
	return results
}

func (s *Service) PlaceByID(id string) (PlaceDetails, bool) {
	poi, ok := s.poisByID[id]
	if !ok {
		return PlaceDetails{}, false
	}
	return PlaceDetails{
		ID:                   poi.ID,
		Title:                poi.Name,
		Subtitle:             poi.Subtitle,
		Latitude:             poi.Latitude,
		Longitude:            poi.Longitude,
		Type:                 poi.PlaceType,
		City:                 poi.City,
		Region:               poi.Region,
		CampusLabel:          poi.CampusLabel,
		CampusNumber:         poi.CampusNumber,
		CategoryRaw:          poi.CategoryRaw,
		CategoryNormalized:   poi.CategoryNormalized,
		GoogleMapsURL:        poi.GoogleMapsURL,
		IsReviewedUnassigned: poi.IsReviewedUnassigned,
		SearchTags:           poi.SearchTags,
	}, true
}

func (s *Service) Categories() []CategorySummary {
	return s.categories
}

func (s *Service) Route(req RouteRequest) RouteResponse {
	if req.Profile == "" {
		req.Profile = "walking"
	}
	if req.DestinationPlaceID != "" {
		if poi, ok := s.poisByID[req.DestinationPlaceID]; ok {
			req.Destination = Coordinate{Latitude: poi.Latitude, Longitude: poi.Longitude}
		}
	}
	if s.router != nil {
		if route, err := s.router.Route(req); err == nil {
			return route
		}
	}
	return fallbackRoute(req)
}

func matchesPoi(poi CustomPoi, query string) bool {
	if strings.Contains(strings.ToLower(poi.Name), query) || strings.Contains(strings.ToLower(poi.Subtitle), query) {
		return true
	}
	if strings.Contains(strings.ToLower(poi.CategoryRaw), query) || strings.Contains(strings.ToLower(poi.CategoryNormalized), query) {
		return true
	}
	for _, tag := range poi.SearchTags {
		if strings.Contains(strings.ToLower(tag), query) {
			return true
		}
	}
	return false
}

func toSearchResult(poi CustomPoi) PlaceSearchResult {
	return PlaceSearchResult{
		ID:          poi.ID,
		Title:       poi.Name,
		Subtitle:    poi.Subtitle,
		Latitude:    poi.Latitude,
		Longitude:   poi.Longitude,
		Type:        poi.PlaceType,
		City:        poi.City,
		CampusLabel: poi.CampusLabel,
		Category:    poi.CategoryNormalized,
		Coordinate:  Coordinate{Latitude: poi.Latitude, Longitude: poi.Longitude},
	}
}

func fallbackRoute(req RouteRequest) RouteResponse {
	midpoint := Coordinate{
		Latitude:  (req.Origin.Latitude+req.Destination.Latitude)/2.0 + 0.00008,
		Longitude: (req.Origin.Longitude+req.Destination.Longitude)/2.0 - 0.00005,
	}
	firstLeg := distance(req.Origin, midpoint)
	secondLeg := distance(midpoint, req.Destination)
	total := firstLeg + secondLeg
	return RouteResponse{
		DistanceMeters:  total,
		DurationSeconds: total / defaultSpeedForProfile(req.Profile),
		TravelMode:      strings.ToLower(req.Profile),
		PathCoordinates: []Coordinate{req.Origin, midpoint, req.Destination},
		RouteSource:     "fallback",
		RouteConfidence: "medium",
		SupportsAR:      true,
		Warnings:        []string{"using simplified fallback routing"},
		Steps: []RouteStep{
			{Instruction: "Head toward the next route segment", DistanceMeters: firstLeg, DurationSeconds: firstLeg / defaultSpeedForProfile(req.Profile), TargetLatitude: midpoint.Latitude, TargetLongitude: midpoint.Longitude, ManeuverType: "Straight"},
			{Instruction: "Continue to your destination", DistanceMeters: secondLeg, DurationSeconds: secondLeg / defaultSpeedForProfile(req.Profile), TargetLatitude: req.Destination.Latitude, TargetLongitude: req.Destination.Longitude, ManeuverType: "Arrive"},
		},
	}
}

func defaultSpeedForProfile(profile string) float64 {
	if strings.EqualFold(profile, "driving") {
		return 8.0
	}
	return 1.35
}

func distance(a, b Coordinate) float64 {
	const radius = 6371000.0
	dLat := radians(b.Latitude - a.Latitude)
	dLon := radians(b.Longitude - a.Longitude)
	lat1 := radians(a.Latitude)
	lat2 := radians(b.Latitude)
	term := math.Sin(dLat/2)*math.Sin(dLat/2) + math.Cos(lat1)*math.Cos(lat2)*math.Sin(dLon/2)*math.Sin(dLon/2)
	return radius * 2 * math.Atan2(math.Sqrt(term), math.Sqrt(1-term))
}

func radians(value float64) float64 {
	return value * math.Pi / 180.0
}

func slug(value string) string {
	value = strings.ToLower(strings.TrimSpace(value))
	value = strings.ReplaceAll(value, " ", "-")
	value = strings.ReplaceAll(value, "/", "-")
	for strings.Contains(value, "--") {
		value = strings.ReplaceAll(value, "--", "-")
	}
	return strings.Trim(value, "-")
}
