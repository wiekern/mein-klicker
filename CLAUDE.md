# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

MeinKlicker is an Android auto-clicker: ordered taps with delays and loops, saved schemes positioned by dragging floating markers, and a "manual trigger" mode where the user taps the first point themselves and the app replays that tap to the app underneath, then runs the remaining steps. Sideloaded via `adb install -r`. UI text and docs are in Chinese.

Maintainers may keep private notes in `CLAUDE.local.md` (gitignored); read it if present.

## Build

Pinned versions: AGP 8.3.2, Gradle 8.4 (wrapper), Kotlin 1.9.23, Compose Compiler 1.5.11, compileSdk/targetSdk 34, minSdk 26. AGP 8.3 is the newest that Android Studio Iguana can sync — don't bump it without upgrading Android Studio. Needs JDK 17 (Android Studio's bundled JBR works) and `sdk.dir` in `local.properties`.

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew testDebugUnitTest --tests "com.meinklicker.engine.ClickEngineTest"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s MeinKlicker
```

Some ROMs mute app logs; in debug builds trigger-replay latency and interception events are also appended to `files/latency.log` (`adb shell run-as com.meinklicker cat files/latency.log`).

## Architecture

Single `app` module, package `com.meinklicker`: `data` (Scheme model + JSON file repo) → `engine` (RunController state machine, ClickEngine loop, GestureDispatcher interface) → `service`/`overlay` (AccessibilityService hosting all floating windows) → `ui` (Compose main screens). Manual DI via `AppContainer`; no Hilt/Room/Navigation.

Key decisions that span multiple files:

- **All floating windows are owned by the AccessibilityService** and use `TYPE_ACCESSIBILITY_OVERLAY` — so no `SYSTEM_ALERT_WINDOW` permission, and overlays die with the service. Overlays are plain Views built in code (not Compose); only the Activity uses Compose.
- **Coordinates are absolute screen pixels everywhere.** Overlay windows use `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS` + cutout `SHORT_EDGES` with `TOP|START` gravity; touches use `rawX/rawY`. This keeps overlay positions, saved steps, and `dispatchGesture` in the same coordinate space.
- **Gestures are strictly serial**: `GestureDispatcher.tap()` suspends until `onCompleted/onCancelled`, because a new `dispatchGesture` cancels an in-flight one.
- `engine` must stay Android-free (only depends on the `GestureDispatcher` interface) so it is unit-testable with a fake dispatcher and `runTest` virtual time. `RunController` lives in `AppContainer` (process-wide `MainScope`) and looks schemes up by id at start, so edits apply to the next run. The service observes `RunController.state` to show/hide the trigger zone; the state machine never touches views.
- **Trigger mode (intercept → replay)**: the trigger-zone window catches the tap; on `ACTION_UP` (using `ACTION_DOWN` coordinates) set the window `FLAG_NOT_TOUCHABLE`, wait two frames + 16 ms, then `tap()` at that point so the underlying app receives it. If the zone still receives the replayed touch (flag not yet applied by the input system), the replay is retried (max 2). Then wait `delayAfterMs` and run the step loop. The trigger tap is not part of the loop. After the loop finishes or is stopped, return to Armed.
- Before running/arming, steps and the trigger zone must not overlap the control bar (refused with a message). Volume-down in `onKeyEvent` is an emergency stop while Running.
- User-facing messages go through `MessageOverlay`, not Toast — some ROMs suppress Toasts when notifications are disabled.
- OEM ROMs (ColorOS / OriginOS) kill background services and may disable the accessibility service; the main screen shows live service status and links to battery / auto-start settings.
