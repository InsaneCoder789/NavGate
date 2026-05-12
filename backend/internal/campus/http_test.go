package campus

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestPlacesSearch(t *testing.T) {
	server := NewHTTPServer(NewService()).Routes()
	req := httptest.NewRequest(http.MethodGet, "/places?query=library", nil)
	res := httptest.NewRecorder()

	server.ServeHTTP(res, req)

	if res.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", res.Code)
	}
	var payload []Place
	if err := json.Unmarshal(res.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if len(payload) != 1 || payload[0].Title != "Central Library" {
		t.Fatalf("unexpected payload: %+v", payload)
	}
}

func TestRouteResponse(t *testing.T) {
	server := NewHTTPServer(NewService()).Routes()
	body, _ := json.Marshal(RouteRequest{
		Origin:      Coordinate{Latitude: 20.349884, Longitude: 85.807529},
		Destination: Coordinate{Latitude: 20.350412, Longitude: 85.808665},
		Profile:     "Walking",
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
	if len(payload.PathCoordinates) != 3 {
		t.Fatalf("expected 3 path coordinates, got %d", len(payload.PathCoordinates))
	}
	if len(payload.Steps) != 2 {
		t.Fatalf("expected 2 route steps, got %d", len(payload.Steps))
	}
	if payload.Steps[1].ManeuverType != "Arrive" {
		t.Fatalf("unexpected route step: %+v", payload.Steps[1])
	}
}

func TestRouteRejectsInvalidJSON(t *testing.T) {
	server := NewHTTPServer(NewService()).Routes()
	req := httptest.NewRequest(http.MethodPost, "/route", bytes.NewBufferString("{"))
	res := httptest.NewRecorder()

	server.ServeHTTP(res, req)

	if res.Code != http.StatusBadRequest {
		t.Fatalf("expected status 400, got %d", res.Code)
	}
}
