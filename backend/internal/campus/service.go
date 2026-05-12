package campus

import (
	"math"
	"strings"
)

type Service struct {
	places []Place
}

func NewService() *Service {
	places := []Place{
		{ID: "gate-1", Title: "North Gate", Subtitle: "Main arrival gate", Latitude: 20.349884, Longitude: 85.807529, Type: "Gate"},
		{ID: "lib-1", Title: "Central Library", Subtitle: "Quiet study hub", Latitude: 20.350080, Longitude: 85.808021, Type: "Academic"},
		{ID: "sport-1", Title: "Sports Complex", Subtitle: "Indoor courts and arena", Latitude: 20.350412, Longitude: 85.808665, Type: "Sports"},
		{ID: "hostel-1", Title: "Maple Residence", Subtitle: "Student housing block", Latitude: 20.349210, Longitude: 85.808990, Type: "Residential"},
		{ID: "cafe-1", Title: "Terrace Cafe", Subtitle: "Coffee and quick meals", Latitude: 20.349560, Longitude: 85.808330, Type: "Food"},
		{ID: "bus-1", Title: "Transit Plaza", Subtitle: "Shuttle and bus pickup zone", Latitude: 20.350905, Longitude: 85.807775, Type: "Transit"},
	}
	for i := range places {
		places[i].Coordinate = Coordinate{Latitude: places[i].Latitude, Longitude: places[i].Longitude}
	}
	return &Service{places: places}
}

func (s *Service) Search(query string) []Place {
	if strings.TrimSpace(query) == "" {
		return s.places
	}
	query = strings.ToLower(strings.TrimSpace(query))
	matches := make([]Place, 0)
	for _, place := range s.places {
		if strings.Contains(strings.ToLower(place.Title), query) || strings.Contains(strings.ToLower(place.Subtitle), query) {
			matches = append(matches, place)
		}
	}
	return matches
}

func (s *Service) Route(req RouteRequest) RouteResponse {
	midpoint := Coordinate{
		Latitude:  (req.Origin.Latitude+req.Destination.Latitude)/2.0 + 0.00008,
		Longitude: (req.Origin.Longitude+req.Destination.Longitude)/2.0 - 0.00005,
	}
	firstLeg := distance(req.Origin, midpoint)
	secondLeg := distance(midpoint, req.Destination)
	total := firstLeg + secondLeg
	return RouteResponse{
		DistanceMeters:  total,
		DurationSeconds: total / 1.35,
		PathCoordinates: []Coordinate{req.Origin, midpoint, req.Destination},
		Steps: []RouteStep{
			{
				Instruction:     "Head toward the campus spine",
				DistanceMeters:  firstLeg,
				TargetLatitude:  midpoint.Latitude,
				TargetLongitude: midpoint.Longitude,
				ManeuverType:    "Straight",
			},
			{
				Instruction:     "Continue to your destination",
				DistanceMeters:  secondLeg,
				TargetLatitude:  req.Destination.Latitude,
				TargetLongitude: req.Destination.Longitude,
				ManeuverType:    "Arrive",
			},
		},
	}
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
