# Remaining work

Written 21 October 2026 after the maps-style journey rebuild. Items are grouped by how much they block a real release.

## Must have before real users

- **Background guidance.** Location and speech stop when the app leaves the screen. Needs a foreground service with a persistent notification and Android 14 service-type declarations.
- **Real-world navigation testing.** Driving and walking with physical GPS, heading, audio, process death and interrupted trips are untested. Current evidence is simulated fixes only.
- **Own routing, search and tile servers.** The app depends on public demo endpoints (Valhalla, Photon, OpenFreeMap) with fair-use limits and no uptime promise. Self-host or contract them, add a fallback router, and contact the Valhalla operator before distribution.
- **Privacy and store readiness.** In-app disclosure that routes and search text go to third parties, a privacy policy, Play data-safety form, release signing, R8 shrinking with keep rules for Room, kotlinx-serialization and MapLibre.
- **Slow-network map loading.** Show a skeleton and a clear retry state; the map can sit blank for over a minute on a poor connection.

## Maps-style screens still on the old layout

- Saved, Recents (places and trips), Settings, region data, My routes editor, onboarding, camera guidance, full steps page.
- Bottom sheets with three drag positions (peek, half, full) instead of the fixed sizes used now.
- Home sheet title: replace "Explore nearby" with the name of the area around the user (offline nearest-place lookup).
- Remove the old region pill, dock and unused place/directions branches from the original shell.

## Routing features

- Alternative routes drawn on the map with their own time labels, and route options (avoid tolls, highways, ferries).
- More travel modes: two-wheeler, cycling, public transport.
- Multi-stop trips, and turning saved manual itineraries into real routes.
- Heading-up map with tilt, user arrow, lane and junction guidance, speed display during navigation.
- Offline road routing and offline base maps.

## Search and places

- True category search (restaurants, petrol, hospitals) from a POI source instead of searching for the word.
- Place details: opening hours, phone, website, photos where a legal source exists.
- Per-recent delete, search by voice, and sharing a pin as a link.
- Keep installed campus data out of results when the user is far from that campus.

## Engineering

- Split `NavGateFrontend.kt` (one very large function) into small screens; an unexplained Compose slot-table crash appeared when one footer was added to it.
- Foreground-service, process-death and 16 KB-page device tests in CI; headless emulator profile that does not hang the system process.
- Dependency and CVE audit; verify and deploy the optional Go backend; review Supabase RLS policies.
- Refresh `IMPLEMENTATION_STATUS.md` into one current page.

## Known limitations today

- Start needs a GPS fix under 8 s old and accurate to 25 m; indoors it will refuse and explain why.
- Estimates carry no live traffic.
- Walking and driving times come from the public Valhalla demo server.

## Written but not yet run on a device (2026-10-21)
- AR guidance is driven by a checkpoint track (only the current road is loaded); the line is continuous, no gates are drawn.
- Offline resilience: connectivity banner, rejoin-the-route pointer, route-corridor map download (`RouteTilePrefetcher`), Valhalla retry, skipping the live-route refresh while offline.
- Touch cues before turns (`TurnHaptics`), screen stays on while guiding, trip summary and "Save parking spot" on arrival.
- Verify on a real drive: offline map coverage along a route, haptic patterns, AR line placement, battery use of AR at 30 fps.

## Live traffic (not built)
- A TomTom-based traffic layer was tried and removed: no open-source live per-road feed exists. Realistic options later are crowd-sourced speeds from NavGate users (needs a backend), or a paid provider such as HERE or TomTom. Counting nearby phones over Wi-Fi/Bluetooth is not viable (see project notes).
