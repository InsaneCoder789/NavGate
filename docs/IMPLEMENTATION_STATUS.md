# NavGate implementation status

Updated 18 October 2026. Scope: native Kotlin local-first application engine and its Android integration, with engineering contracts ready for the user's future Stitch screens. No frontend redesign or paid service was introduced.

## Verified implementation

- Versioned, validated campus packs; exact directed endpoint geometry; nullable information-only destination coordinates.
- Local A* with explicit failures, direction-preserving origin projection, spatial candidate index, and bounded two-version index cache. Independent Dijkstra comparisons exercise path costs.
- One serialized navigation session for map/camera: usable-fix gating, bounded segment progress, repeated-fix arrival, single-flight reroute, stale-result rejection, graph-pinned checkpoint integrity and explicit resume.
- Headless command coordinator with generation-checked search/plan results, destination readiness, observable errors and speech events.
- Room version activation/local search, non-destructive legacy user migration, bounded bookmarks/recents/history, stable trip timestamps, atomic checkpoint/summary persistence, and DataStore preference migration.
- Production ViewModel/repository wiring; foreground framework location without Play Services; optional lifecycle-aware compass aid and installed-offline TTS with text fallback.
- Bounded signed stream imports plus Android document URI adapter. Updates are disabled until a publisher public key is bundled; imported documents cannot establish trust roots.
- Reproducible real OSM snapshot and candidate importer. Candidates remain separate from production routable data.

Implemented APIs and engineering diagrams: [BACKEND_INTEGRATION.md](BACKEND_INTEGRATION.md). Proposal and longer-term acceptance phases: [TECHNICAL_PROPOSAL.md](TECHNICAL_PROPOSAL.md).

## Executed evidence

Final code verification job: `job-mvdwzry5-cd46aefc`, completed with exit 0. Java 22, Android SDK 35, API 34 read-only emulator.

| Gate | Evidence |
| --- | --- |
| Core JVM suite | 34 tests, zero failures/errors in `navigation-core/build/test-results/test` |
| Android JVM suite | 25 tests, zero failures/errors in `app/build/test-results/testDebugUnitTest` |
| Isolated Android persistence/integration suite | 10 tests, zero failures/errors in `app/build/outputs/androidTest-results/connected/debug` |
| APK assembly | `:app:assembleDebug` passed; `app/build/outputs/apk/debug/app-debug.apk` |
| Android lint | `:app:lintDebug` passed; `app/build/reports/lint-results-debug.html` |
| Python import regressions | Seven tests passed with `python3 -m unittest discover -s scripts/tests -v` |
| Actual bundled catalogue flow | Production repository + coordinator + Room bootstrap/search/planning exercised in instrumentation; 175 records, 84 null coordinates, explicit MissingGraph, no route/checkpoint/trip fabrication |
| App launch | APK installed after instrumentation cleanup; `am start -W` reported COLD / Status ok; process remained running, no AndroidRuntime error in inspected recent log window |
| Optional Go preservation | No `backend/` diff from baseline `7ddd187`; Go toolchain unavailable on this host, so Go tests were not rerun |
| Git delivery | Exactly 20 new backend commits from baseline `7ddd187`, each pushed; final remote SHA/count checked after documentation commit |

Instrumentation owns uniquely named test databases and preferences. Synthetic walking graphs are confined to tests. App launch evidence covers startup only, not rendered journeys, physical sensors or outdoor navigation.

Earlier jobs: Java 25 baseline startup failed before compilation (`job-mvduygac-6a5a4d33`); switching to installed Java 22 resolved it. `job-mvdv9s6i-adbe607b` verified initial core/app compilation; `job-mvdvygeu-5b85525d` verified first integrated persistence snapshot; `job-mvdwnty1-57589b20`, `job-mvdwrq7a-35ccaad0`, and `job-mvdwvf9y-5b3ccbc1` verified subsequent recovery, retention, heading and voice increments. The final job supersedes those narrower code snapshots.

## Data and physical acceptance limits

The user supplied destination lists/coordinates, not a walking-path/entrance dataset, and authorized real-route source investigation. The acquired bounded OSM snapshot has 2,298 ways and 13,374 nodes; filtering produces 9,203 candidate nodes and 19,488 directed candidates. The snapshot does not establish usable entrances for the workbook destinations. It is not field-verified access evidence. See `data/osm/README.md` and `data/reports/kiit-osm-candidates.json`.

The APK therefore ships an information-only catalogue and returns MissingGraph for KIIT navigation. A public campus-navigation release still needs reviewed walking connectivity/entrances, workbook provenance/redistribution review, physical walking/GNSS/heading/audio tests, and interrupted/background/process-death field acceptance. Emulator reopen/checkpoint tests do not substitute for all OS process-death scenarios.

Offline basemap installation, automated update hosting, broader city routing, indoor routing and world-anchored AR are not implemented. Routing-pack storage never claims offline tile availability. The backend has no ML model requirement for graph routing. Public APIs is a discovery directory, not a guarantee of free unrestricted provider capacity.

These are explicit data/product acceptance gaps. Passing engine tests does not establish production navigation readiness.

## Backend commit ledger

Baseline: `7ddd187`, main tracking origin/main at `https://github.com/InsaneCoder789/NavGate.git`. New commits:

1. `2bfe99a` — Kotlin core module and geographic geometry.
2. `73228ac` — Versioned pack validation and signatures.
3. `2dc27fa` — A* and independent Dijkstra comparisons.
4. `e6c1c35` — Shared session and recovery rules.
5. `dce0034` — Information-only catalogue pipeline.
6. `3d27699` — Durable Room schema and schema export.
7. `08ad87a` — Immutable version activation and local search.
8. `110cbce` — DataStore preference migration.
9. `8929738` — Serialized coordinator and recovered-route integrity.
10. `cbaaf6e` — Bounded signed-document import.
11. `4237d90` — Android production integration and persistence.
12. `a59a93d` — OSM source and restriction-aware candidate extraction.
13. `a2734ca` — Stable trip timestamps and retention verification.
14. `9f543eb` — Bounded version-specific routing indexes.
15. `07cedcf` — Heading quality/reference correction and alignment gating.
16. `0a566ec` — Installed offline voice selection and text fallback.
17. `b4e30e5` — Android document-picker import boundary.
18. `12f39f8` — Destination readiness alongside search.
19. `f7fb3b3` — Production-catalogue integration regression and indexed recovery checks.
20. Final documentation commit — Implemented backend contract, engineering diagrams, README migration and this evidence audit. Its exact SHA is available from `git log -1`; embedding its own SHA would require rewriting the commit.

Pending frontend/design/session artifacts are intentionally outside these backend commits.

## Worldwide search expansion

The production service now uses Photon-backed worldwide search in addition to installed local records; selected offline regions no longer bound online search. Search labels and initial camera behavior reflect the new scope. See [WORLDWIDE_SEARCH.md](WORLDWIDE_SEARCH.md). Host live checks found Paris, Sydney and New York. Job `job-mvgrbq9f-ba8048af` passed the build, lint, 34 core tests and 45 app unit tests, then failed while installing the Android test APK on Pixel_9_Pro with a package-service Broken pipe error. Native/live Android tests did not run in that job. Worldwide routing remains a separate provider boundary.
