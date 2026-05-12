package campus

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

type stubRouter struct{}

func (stubRouter) Route(req RouteRequest) (RouteResponse, error) {
	return RouteResponse{
		DistanceMeters:  120,
		DurationSeconds: 90,
		TravelMode:      req.Profile,
		PathCoordinates: []Coordinate{req.Origin, req.Destination},
		Steps: []RouteStep{
			{Instruction: "Leave the origin", DistanceMeters: 40, TargetLatitude: req.Origin.Latitude, TargetLongitude: req.Origin.Longitude, ManeuverType: "Start"},
			{Instruction: "Arrive at destination", DistanceMeters: 80, TargetLatitude: req.Destination.Latitude, TargetLongitude: req.Destination.Longitude, ManeuverType: "Arrive"},
		},
		RouteSource:     "stub",
		RouteConfidence: "high",
		SupportsAR:      true,
	}, nil
}

func newTestServer() http.Handler {
	return NewHTTPServer(NewServiceWithRouter(stubRouter{})).Routes()
}

func TestPlacesSearch(t *testing.T) {
	server := newTestServer()
	req := httptest.NewRequest(http.MethodGet, "/places?query=library", nil)
	res := httptest.NewRecorder()

	server.ServeHTTP(res, req)

	if res.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", res.Code)
	}
	var payload []PlaceSearchResult
	if err := json.Unmarshal(res.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if len(payload) == 0 {
		t.Fatalf("expected non-empty payload")
	}
}

func TestPlaceDetails(t *testing.T) {
	server := newTestServer()
	req := httptest.NewRequest(http.MethodGet, "/places/campus-14-central-library", nil)
	res := httptest.NewRecorder()

	server.ServeHTTP(res, req)

	if res.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d body=%s", res.Code, res.Body.String())
	}
	var payload PlaceDetails
	if err := json.Unmarshal(res.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if payload.Title == "" || payload.City != "Bhubaneswar" {
		t.Fatalf("unexpected place details: %+v", payload)
	}
}

func TestCategories(t *testing.T) {
	server := newTestServer()
	req := httptest.NewRequest(http.MethodGet, "/categories", nil)
	res := httptest.NewRecorder()

	server.ServeHTTP(res, req)

	if res.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", res.Code)
	}
	var payload []CategorySummary
	if err := json.Unmarshal(res.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if len(payload) < 4 {
		t.Fatalf("expected multiple category summaries, got %d", len(payload))
	}
}

func TestRouteResponse(t *testing.T) {
	server := newTestServer()
	body, _ := json.Marshal(RouteRequest{
		Origin:      Coordinate{Latitude: 20.349884, Longitude: 85.807529},
		Destination: Coordinate{Latitude: 20.350412, Longitude: 85.808665},
		Profile:     "walking",
	})
	req := httptest.NewRequest(http.MethodPost, "/route", bytes.NewReader(body))
	res := httptest.NewRecorder()

	server.ServeHTTP(res, req)

	if res.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", res.Code)
	}
	var payload RouteResponse
	if err := json.Unmarshal(res.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if len(payload.PathCoordinates) != 2 {
		t.Fatalf("expected 2 path coordinates, got %d", len(payload.PathCoordinates))
	}
	if len(payload.Steps) != 2 {
		t.Fatalf("expected 2 route steps, got %d", len(payload.Steps))
	}
	if payload.RouteSource != "stub" || !payload.SupportsAR {
		t.Fatalf("unexpected route payload: %+v", payload)
	}
}

func TestRouteRejectsInvalidJSON(t *testing.T) {
	server := newTestServer()
	req := httptest.NewRequest(http.MethodPost, "/route", bytes.NewBufferString("{"))
	res := httptest.NewRecorder()

	server.ServeHTTP(res, req)

	if res.Code != http.StatusBadRequest {
		t.Fatalf("expected status 400, got %d", res.Code)
	}
}
