# NavGate

NavGate is now a native Android foundation for an indoor AR navigation product.

## Stack

- Kotlin
- Jetpack Compose
- ARCore
- Gradle Kotlin DSL

## Current state

This project is a clean Android starter focused on the AR navigation foundation:

- ARCore manifest and dependency setup
- Compose-based home screen
- Camera permission handling
- ARCore availability checks
- project structure ready for indoor routing, anchors, and live AR guidance

## Open in Android Studio

1. Open this folder in Android Studio.
2. Let Gradle sync.
3. Run on an ARCore-capable Android device.

## First implementation targets

1. Add the live AR session renderer.
2. Add indoor building and floor models.
3. Add route guidance overlays anchored in world space.
4. Add a Go backend for building graph routing.
