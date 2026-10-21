# NavGate branding

Approved direction: a blue/cyan N combining an open gateway and a route, on ink navy. Applied only to the welcome presentation and launcher assets. The app's map, shared glass system, theme, routing, search, GPS rules and persistence are unchanged by this work.

## Production files

- `app/src/main/res/drawable-nodpi/navgate_brand_mark.png`: transparent mark used in onboarding and the adaptive launcher foreground.
- `app/src/main/res/drawable-nodpi/navgate_onboarding_discover.jpg`: Find your place.
- `app/src/main/res/drawable-nodpi/navgate_onboarding_routes.jpg`: Choose your route.
- `app/src/main/res/drawable-nodpi/navgate_onboarding_navigate.jpg`: Navigate with confidence.
- `docs/branding/favicon.ico`: PNG-backed 16, 32, 48, 64, 128 and 256 px favicon export. This Android repository has no website consumer; the file is prepared for future web use.
- `ui/journey/BrandOnboarding.kt`: native text, buttons, progress and Back handling. No text or controls are baked into the artwork.

Skip and Explore first use the existing `dismissOnboarding` action. Enable location uses the existing `requestLocation` action; permission denial leaves exploration available. Users who already completed onboarding are not reset. Settings > Show welcome again opens the new presentation. No new persistence keys, permissions, dependencies or route behavior.

Artwork is illustrative rather than a screenshot or a promise of the live map's geographic accuracy. The palette here is local to onboarding: #08111F, #397CFF, #73DEED and #F2F6FA. The main app retains its current theme. The primary button uses a deeper blue for white-text contrast.

## Generation provenance and prompt set

Generated with the built-in imagegen tool using the previously approved NavGate brand board and onboarding board as visual references. No API key or fallback CLI was used. Native copy and Android integration are authored separately. JPEG encoding and icon resizing are mechanical exports of the generated originals, whose alpha is retained on the mark.

Shared artwork prompt: production Android onboarding background art only, one portrait 2:3 illustration, full bleed, no phone frame, text, typography, logo, buttons, panels or progress dots. A premium dimensional miniature Indian coastal city at blue hour; ink navy #08111F, restrained electric blue #397CFF and ice cyan #73DEED, small warm lights, realistic architectural relief. Important story in the upper two thirds, quiet upper margin and lower fade into navy for native overlays. Illustrative map, no actual geographic accuracy claim; no neon sci-fi, purple or watermarks.

1. Discover: tilted coastal street map, blue cafe destination marker, two quiet category markers, a waterfront Indian gateway, no route line.
2. Routes: matching street map, one electric-blue route following streets from a small start ring to a destination pin, warm civic architecture, no labels.
3. Navigate: closer tilted waterfront road, a blue route, small white/blue navigation chevron and subtle accuracy circle, bridge at the right, no direction card or UI.
4. Mark: recreate the approved blue/cyan N-gateway-route symbol alone on genuine transparency. Preserve its left pillar, descending blue diagonal, inset cyan gate stroke, right curved cyan route pillar and circular endpoint; flat color, crisp edges, generous transparent padding. No wordmark, board, labels, rounded-square background, shadows or glow.

## Verification

- ARM64 app and instrumentation APK assembly passed in an isolated build directory.
- Samsung SM-S926B API36: all five `NativeFrontendFlowTest` tests passed, including the new three-page welcome, Back, optional Explore first and existing search/save/directions/guidance flows. These tests use isolated fixtures; they do not establish physical GPS or live routing acceptance.
- Device screenshots of all three pages were inspected; local evidence is under `.qa/branding/`.
- ICO container and all six embedded PNG frames validated.
- Full project lint is blocked by the existing `JourneyScreenshotTest.kt:63` direct LocalContext-to-Activity cast. That unrelated test was left unchanged.

## Logo margins and launch reveal

The adaptive icon foreground now has a 23% proportional inset, keeping the visible mark comfortably inside circular and rounded-square masks. A fractional inset is intentional: an absolute dp inset on a raster foreground scales with the bitmap intrinsic size and was insufficient under Android adaptive masking. The welcome header uses a 40 dp image box, explicit spacing and a bounded one-line wordmark with 24 dp side margins.

`ui/BrandLaunch.kt` draws a deterministic, one-shot mist field around the existing mark. The mark resolves from blur, holds briefly, then softens and dissolves with the mist into the app in 1.45 seconds. This is native Compose drawing; no video, network call, extra dependency or generated animation asset is involved. The frontend initializes underneath the overlay. Android 12+ uses a matching navy launch window with a transparent system placeholder so a second oversized system icon is not shown.

The reveal is saved as completed across Activity recreation, and does not replay on a normal return from Recents. App reduce-motion and Android disabled-animations settings skip the decorative delay. This recreates the requested mist/liquid-flow direction; it is not a verified frame-for-frame copy of Blinkit or Zomato.

Unlocked-device follow-up: final proportional icon inset was visually inspected on Samsung API36. All eight launch/onboarding/frontend instrumentation tests passed after the correction, including one-shot completion and reduced-motion checks. A stopped-process cold launch was captured through to the real map; local recording and screenshots are in `.qa/branding-splash/`.
