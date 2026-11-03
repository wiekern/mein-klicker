# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

MeinKlicker is a personal-use Android auto-clicker (no ads, no network permission, not published; sideloaded via `adb install -r`). The Android project has **not been scaffolded yet** — only specs exist. Read these before implementing:

- `specs/261103-auto-clicker-mvp/prd.md` — requirements (Chinese)
- `specs/261103-auto-clicker-mvp/tech-design.md` — architecture, versions, module design, implementation order. Where it conflicts with the PRD, the tech design wins.

## Conventions

- Specs live under `specs/<YYMMDD-a-b-c>/` (date prefix + kebab-case slug).
- Respond to the user in Chinese.
- Target devices: OPPO / vivo / OnePlus (ColorOS / OriginOS), portrait only.

## Build environment (this machine)

Versions are pinned to what is already cached locally and to what the installed Android Studio (Iguana, max AGP 8.3) can sync: AGP 8.3.2, Gradle 8.4, Kotlin 1.9.23, Compose Compiler 1.5.11, compileSdk/targetSdk 34, minSdk 26. Don't bump AGP past 8.3.x without upgrading Android Studio.

```bash
export JAVA_HOME="/path/to/Android Studio/jbr"   # JDK 17; system java is also 17 but use JBR
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew testDebugUnitTest --tests "com.meinklicker.engine.ClickEngineTest"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s MeinKlicker
```

SDK: `/path/to/Android/Sdk` (goes into `local.properties`). No global `gradle` on PATH; generate the wrapper from the cached `~/.gradle/wrapper/dists/gradle-8.4-all`.

## Architecture

Single `app` module, package `com.meinklicker`: `data` (Scheme model + JSON file repo) → `engine` (RunController state machine, ClickEngine loop, GestureDispatcher interface) → `service`/`overlay` (AccessibilityService hosting all floating windows) → `ui` (Compose main screens). Manual DI via `AppContainer`; no Hilt/Room/Navigation.

Key decisions that span multiple files:

- **All floating windows are owned by the AccessibilityService** and use `TYPE_ACCESSIBILITY_OVERLAY` — so no `SYSTEM_ALERT_WINDOW` permission, and overlays die with the service. Overlays are plain Views (not Compose); only the Activity uses Compose.
- **Coordinates are absolute screen pixels everywhere.** Overlay windows use `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS` + cutout `SHORT_EDGES` with `TOP|START` gravity; touches use `rawX/rawY`. This keeps overlay positions, saved steps, and `dispatchGesture` in the same coordinate space.
- **Gestures are strictly serial**: `GestureDispatcher.tap()` suspends until `onCompleted/onCancelled`, because a new `dispatchGesture` cancels an in-flight one.
- `engine` must stay Android-free (only depends on the `GestureDispatcher` interface) so it is unit-testable with a fake dispatcher and `runTest` virtual time. The service observes `RunController.state` to show/hide the trigger zone; the state machine never touches views.
- **Trigger mode (intercept → replay)**: the trigger-zone window catches the tap; on `ACTION_UP` (using `ACTION_DOWN` coordinates) set the window `FLAG_NOT_TOUCHABLE`, wait one frame, then `tap()` at that point so the underlying app receives it, wait `delayAfterMs`, run the step loop. The trigger tap is not part of the loop. After the loop finishes or is stopped, return to Armed.
- Volume-down in `onKeyEvent` is an emergency stop while Running (in case a step lands on the control bar).
- ColorOS kills background services and can disable the accessibility service; the main screen must show live service status.
