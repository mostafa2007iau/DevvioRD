package server

import "net/http"

// routesForTest exposes the same mux the real listener serves, so HTTP-level
// tests exercise the production routing/auth wiring rather than a copy of it.
func (s *Server) routesForTest() http.Handler {
	mux := http.NewServeMux()
	s.routes(mux)
	return mux
}
