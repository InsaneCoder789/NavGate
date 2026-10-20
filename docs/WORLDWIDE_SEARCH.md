# Worldwide place search

The production backend now wraps the installed-region catalogue with `WorldwideSearchData` and `PhotonPlaceSearch`. Selecting KIIT or Mumbai controls offline region data, not the geographic scope of online search.

For queries of at least three characters, NavGate searches the public Photon API for OpenStreetMap places, addresses, cities and landmarks worldwide. Requests contain no country filter or campus bounding box. Empty/short searches use installed data without network calls. Global results retain OSM identities and real coordinates and can be focused, selected as endpoints, saved and shared through the existing frontend.

## Free provider and request policy

Photon is an Apache-2.0 open-source geocoder. Its public endpoint permits reasonable project use, may throttle or ban excessive usage, and provides no uptime guarantee. There is no API key, payment account or paid dependency. Source and current policy: https://github.com/komoot/photon ; API contract: https://github.com/komoot/photon/blob/master/docs/api-v1.md . Data attribution: © OpenStreetMap contributors.

The implementation debounces typed input for 600ms, serializes requests with a minimum one-second interval, caches up to 64 queries (15 minutes for matches; one minute for empty results), caps results at 12, and bounds responses to 1 MiB. Calls have finite connection/read/overall timeouts and cancellation cancels the underlying HTTP request. HTTP 429/503 triggers a minimum 60-second cooldown (numeric Retry-After honored up to one hour); there are no automatic retry loops. User queries are sent to Photon; GPS position is not included in these requests.

An unavailable service returns an explicit connection/retry notice and installed local matches. It must not be presented as a complete worldwide no-results response. Search notice and result updates share the backend's latest-generation check, so an older query cannot replace newer results. Provider ranking comes first, followed by matching installed records. OSM map coverage does not guarantee every business or address exists in its index.

## Frontend integration

`BackendSnapshot.searchNotice` / `NavGateUiState.searchNotice` communicates provider attribution, minimum query length or offline fallback. Search controls now read “Search anywhere”; online results are not called campus locations. The map begins at world scale and centers on an accepted current location when available rather than imposing KIIT/Mumbai coordinates. Choosing a result focuses its actual coordinate.

This change supplies worldwide **search**, not a worldwide road-network routing engine. The current RoutePlanner still requires a supported installed graph and destination entrance. Global search results explicitly state that directions need a supported routing provider. Do not create artificial straight-line routes or mark geocoder coordinates as reviewed entrances.

## Verification

- JVM provider tests use MockWebServer for request scope, lon/lat parsing, stable IDs, cache reuse, invalid feature rejection, HTTP throttling and cancellation.
- Hybrid-data tests check selected-region independence, short-query suppression, labelled offline fallback and cancellation propagation.
- Native Compose test selects and saves a Paris result through the actual search UI with a deterministic provider stub.
- Opt-in `WorldwideSearchLiveTest` uses real Android HTTP to find Paris, Sydney and New York: run connected tests with `-Pandroid.testInstrumentationRunnerArguments.liveWorldwideSearch=true`.
- Host-side bounded live checks already returned the named landmarks/cities in France, Australia and the USA. Android test/build results must be recorded after execution; host HTTP alone does not prove the installed app flow.

## Current verification result

Job `job-mvgrbq9f-ba8048af`: build, lint, 34 core JVM tests and 45 app JVM tests passed. APK installation succeeded, but Android test APK installation failed with a package-service Broken pipe error on the existing Pixel 9 Pro API 37 emulator. Consequently the new Compose journey and real Android provider tests remain unexecuted for this snapshot. Three host live queries succeeded; this is separate from installed-device verification.
