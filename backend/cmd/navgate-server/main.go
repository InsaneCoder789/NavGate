package main

import (
	"log"
	"net/http"
	"os"
	"time"

	"github.com/InsaneCoder789/NavGate/backend/internal/campus"
)

func main() {
	service := campus.NewService()
	server := campus.NewHTTPServer(service)
	addr := os.Getenv("NAVGATE_ADDR")
	if addr == "" {
		addr = ":8080"
	}
	log.Printf("NavGate backend listening on %s", addr)
	// Explicit timeouts stop slow clients from holding connections open indefinitely.
	httpServer := &http.Server{
		Addr:              addr,
		Handler:           server.Routes(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}
	if err := httpServer.ListenAndServe(); err != nil {
		log.Fatal(err)
	}
}
