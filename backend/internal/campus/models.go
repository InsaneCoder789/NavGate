package campus

type Coordinate struct {
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
}

type Place struct {
	ID         string     `json:"id"`
	Title      string     `json:"title"`
	Subtitle   string     `json:"subtitle"`
	Latitude   float64    `json:"latitude"`
	Longitude  float64    `json:"longitude"`
	Type       string     `json:"type"`
	Coordinate Coordinate `json:"-"`
}

type RouteRequest struct {
	Origin      Coordinate `json:"origin"`
	Destination Coordinate `json:"destination"`
	Profile     string     `json:"profile"`
}

type RouteStep struct {
	Instruction     string  `json:"instruction"`
	DistanceMeters  float64 `json:"distanceMeters"`
	TargetLatitude  float64 `json:"targetLatitude"`
	TargetLongitude float64 `json:"targetLongitude"`
	ManeuverType    string  `json:"maneuverType"`
}

type RouteResponse struct {
	DistanceMeters  float64      `json:"distanceMeters"`
	DurationSeconds float64      `json:"durationSeconds"`
	PathCoordinates []Coordinate `json:"pathCoordinates"`
	Steps           []RouteStep  `json:"steps"`
}
