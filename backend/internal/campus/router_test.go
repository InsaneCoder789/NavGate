package campus

import (
	"errors"
	"testing"
)

type testProvider struct {
	name  string
	route RouteResponse
	err   error
	calls int
}

func (p *testProvider) Name() string {
	return p.name
}

func (p *testProvider) Route(req RouteRequest) (RouteResponse, error) {
	p.calls++
	if p.err != nil {
		return RouteResponse{}, p.err
	}
	return p.route, nil
}

func TestProviderChainFallback(t *testing.T) {
	primary := &testProvider{name: "primary", err: errors.New("down")}
	fallback := &testProvider{name: "fallback", route: RouteResponse{RouteSource: "fallback"}}
	chain := NewProviderChain(primary, fallback)
	route, err := chain.Route(RouteRequest{})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if route.RouteSource != "fallback" {
		t.Fatalf("expected fallback route source, got %q", route.RouteSource)
	}
	if primary.calls != 1 || fallback.calls != 1 {
		t.Fatalf("expected one call for each provider, got primary=%d fallback=%d", primary.calls, fallback.calls)
	}
}

func TestProviderChainAllFail(t *testing.T) {
	first := &testProvider{name: "first", err: errors.New("fail-1")}
	second := &testProvider{name: "second", err: errors.New("fail-2")}
	chain := NewProviderChain(first, second)
	_, err := chain.Route(RouteRequest{})
	if err == nil {
		t.Fatalf("expected error when all providers fail")
	}
}
