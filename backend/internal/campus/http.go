package campus

import (
	"encoding/json"
	"net/http"
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
	mux.HandleFunc("/places", h.handlePlaces)
	mux.HandleFunc("/route", h.handleRoute)
	return mux
}

func (h *HTTPServer) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *HTTPServer) handlePlaces(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, h.service.Search(r.URL.Query().Get("query")))
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
	if req.Profile == "" {
		req.Profile = "Walking"
	}
	writeJSON(w, http.StatusOK, h.service.Route(req))
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}
