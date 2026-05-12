package campus

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
)

type HTTPServer struct {
	service *Service
}

func NewHTTPServer(service *Service) *HTTPServer {
	return &HTTPServer{service: service}
}

func (h *HTTPServer) Routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", h.handleHealth)
	mux.HandleFunc("/places/", h.handlePlaceDetails)
	mux.HandleFunc("/places", h.handlePlaces)
	mux.HandleFunc("/categories", h.handleCategories)
	mux.HandleFunc("/route", h.handleRoute)
	mux.HandleFunc("/reroute", h.handleRoute)
	return mux
}

func (h *HTTPServer) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"status": "ok", "customPoiCount": len(h.service.pois)})
}

func (h *HTTPServer) handlePlaces(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	writeJSON(w, http.StatusOK, h.service.Search(
		r.URL.Query().Get("query"),
		r.URL.Query().Get("city"),
		r.URL.Query().Get("campus"),
		r.URL.Query().Get("category"),
		limit,
	))
}

func (h *HTTPServer) handlePlaceDetails(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/places/")
	if id == "" || id == r.URL.Path {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "place not found"})
		return
	}
	place, ok := h.service.PlaceByID(id)
	if !ok {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "place not found"})
		return
	}
	writeJSON(w, http.StatusOK, place)
}

func (h *HTTPServer) handleCategories(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, h.service.Categories())
}

func (h *HTTPServer) handleRoute(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
		return
	}
	var req RouteRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid route request"})
		return
	}
	if req.Origin == (Coordinate{}) || (req.Destination == (Coordinate{}) && req.DestinationPlaceID == "") {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "origin and destination are required"})
		return
	}
	writeJSON(w, http.StatusOK, h.service.Route(req))
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}
