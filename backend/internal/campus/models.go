package campus

type Coordinate struct {
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
}

type CustomPoi struct {
	ID                   string   `json:"id"`
	Name                 string   `json:"name"`
	NormalizedName       string   `json:"normalizedName"`
	Subtitle             string   `json:"subtitle"`
	City                 string   `json:"city"`
	Region               string   `json:"region"`
	CampusLabel          string   `json:"campusLabel,omitempty"`
	CampusNumber         int      `json:"campusNumber,omitempty"`
	CategoryRaw          string   `json:"categoryRaw"`
	CategoryNormalized   string   `json:"categoryNormalized"`
	PlaceType            string   `json:"placeType"`
	Latitude             float64  `json:"latitude"`
	Longitude            float64  `json:"longitude"`
	GoogleMapsURL        string   `json:"googleMapsUrl,omitempty"`
	IsReviewedUnassigned bool     `json:"isReviewedUnassigned"`
	IsActive             bool     `json:"isActive"`
	SearchTags           []string `json:"searchTags"`
	SourceRow            int      `json:"sourceRow"`
}

type PlaceSearchResult struct {
	ID          string     `json:"id"`
	Title       string     `json:"title"`
	Subtitle    string     `json:"subtitle"`
	Latitude    float64    `json:"latitude"`
	Longitude   float64    `json:"longitude"`
	Type        string     `json:"type"`
	City        string     `json:"city,omitempty"`
	CampusLabel string     `json:"campusLabel,omitempty"`
	Category    string     `json:"category,omitempty"`
	Coordinate  Coordinate `json:"-"`
}

type PlaceDetails struct {
	ID                   string   `json:"id"`
	Title                string   `json:"title"`
	Subtitle             string   `json:"subtitle"`
	Latitude             float64  `json:"latitude"`
	Longitude            float64  `json:"longitude"`
	Type                 string   `json:"type"`
	City                 string   `json:"city"`
	Region               string   `json:"region"`
	CampusLabel          string   `json:"campusLabel,omitempty"`
	CampusNumber         int      `json:"campusNumber,omitempty"`
	CategoryRaw          string   `json:"categoryRaw"`
	CategoryNormalized   string   `json:"categoryNormalized"`
	GoogleMapsURL        string   `json:"googleMapsUrl,omitempty"`
	IsReviewedUnassigned bool     `json:"isReviewedUnassigned"`
	SearchTags           []string `json:"searchTags,omitempty"`
}

type CategorySummary struct {
	ID             string `json:"id"`
	Label          string `json:"label"`
	Count          int    `json:"count"`
	Representative string `json:"representativeType"`
}

type RouteRequest struct {
	Origin             Coordinate `json:"origin"`
	Destination        Coordinate `json:"destination"`
	DestinationPlaceID string     `json:"destinationPlaceId,omitempty"`
	Profile            string     `json:"profile"`
	CityHint           string     `json:"cityHint,omitempty"`
}

type RouteStep struct {
	Instruction     string  `json:"instruction"`
	DistanceMeters  float64 `json:"distanceMeters"`
	DurationSeconds float64 `json:"durationSeconds,omitempty"`
	TargetLatitude  float64 `json:"targetLatitude"`
	TargetLongitude float64 `json:"targetLongitude"`
	ManeuverType    string  `json:"maneuverType"`
	BearingStart    float64 `json:"bearingStart,omitempty"`
	BearingEnd      float64 `json:"bearingEnd,omitempty"`
	StreetName      string  `json:"streetName,omitempty"`
}

type RouteResponse struct {
	DistanceMeters  float64      `json:"distanceMeters"`
	DurationSeconds float64      `json:"durationSeconds"`
	TravelMode      string       `json:"travelMode,omitempty"`
	PathCoordinates []Coordinate `json:"pathCoordinates"`
	Steps           []RouteStep  `json:"steps"`
	RouteSource     string       `json:"routeSource,omitempty"`
	RouteConfidence string       `json:"routeConfidence,omitempty"`
	SupportsAR      bool         `json:"supportsAr"`
	Warnings        []string     `json:"warnings,omitempty"`
}
