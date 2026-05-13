package main

import (
	"log"
	"net/http"
	"os"

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
	if err := http.ListenAndServe(addr, server.Routes()); err != nil {
		log.Fatal(err)
	}
}
