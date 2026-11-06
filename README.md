# Tap Macro

An Android app that records your taps and swipes on the screen and plays them back on a loop, so a game can keep going while you're away.

- A small floating bar sits on top of any app or game: **● record**, **▶ play**, **✕ hide**.
- While recording, your taps still go through to the game, so you just play normally.
- Multi-finger input, swipes, and long presses are recorded with their real timing.
- Repeat forever or a set number of times, add a pause between rounds, and speed playback up or slow it down.
- Save several macros, rename them, and pick which one ▶ plays.
- Keeps the screen awake while a macro is playing.

**Android 8.0 or newer only.** iPhones don't allow apps to tap the screen for you (see [iPhone](#iphone) below).

## Install

1. On your Android phone, open this repo's **Releases** page and download **TapMacro.apk** from the `latest` release. You can also build it yourself (see below).
2. Open the downloaded file. If Android asks, allow your browser or file manager to **install unknown apps**.
3. If Play Protect warns you about an unknown app, tap **More details → Install anyway**.

## Set up (one time)

1. Open **Tap Macro** and tap **Open accessibility settings**.
2. Find **Tap Macro** (often under *Downloaded apps* or *Installed apps*) and switch it on.
   - **Switch greyed out, or a "Restricted setting" message?** That's Android 13+ protecting sideloaded apps. Go back to Tap Macro, tap **Open App info**, tap **⋮** in the top corner, choose **Allow restricted settings**, then switch it on again. (On some phones the ⋮ option only shows up after you've tried to switch it on once.)
3. The floating bar appears. If you hid it, tap **Show floating controls** in the app.

Android uses the Accessibility permission because it's the only way an app is allowed to tap the screen for you. Tap Macro can't read what's on your screen, and it doesn't collect or send any data.

## Use it

1. Open your game.
2. Tap **●** on the floating bar. A red border means you're recording.
3. Play normally: tap, hold, swipe. Each touch is numbered on screen.
4. Tap **■** to stop. The macro is saved and selected.
5. Tap **▶** to replay it. Tap **■** to stop.

Repeat count, pause between repeats, and speed are set in the app under **Playback settings**.

### Tips

- **Keep the bar out of the way.** A tap that lands on the bar won't reach the game. Drag the dots at the top of the bar to move it.
- **Hold the phone the same way** (portrait or landscape) when you record and when you play.
- **Recording isn't live for the game.** A touch reaches the game right after you lift your finger, not while you're holding it. That's fine for menus and buttons, but holding a joystick will feel delayed while recording. Playback uses the real timing.
- If you'd rather mark tap spots without the game reacting, turn off **Send taps to the game while recording**.
- If playback stops on its own after a while, turn off battery optimisation for Tap Macro (Settings → Apps → Tap Macro → Battery → Unrestricted). Some phones (Xiaomi, Huawei, Oppo, and others) also turn accessibility apps off in the background. Check your phone's "autostart" or "app launch" setting.

### Heads-up for online games

Many online games don't allow automated input or AFK farming, and some ban accounts for it. **Epic's Fortnite rules prohibit it.** Use this at your own risk.

## iPhone

Apple doesn't let apps tap the screen for you, so this app can't exist on iOS. The closest built-in option is **Settings → Accessibility → Switch Control → Recipes → Create New Recipe → Custom Gesture**. A recipe can replay one recorded gesture each time you trigger it, but it won't loop on its own.

## Build it yourself

You need JDK 17+ and the Android SDK (or Android Studio).

```bash
./gradlew testDebugUnitTest assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Every push also builds the APK on GitHub Actions and publishes it to the `latest` release.

Builds are signed with `app/tapmacro.keystore`, which is checked in on purpose. Because every build has the same signature, a new version installs over the old one and keeps your saved macros. It's only meant for this hobby app, so don't use that key to sign anything else.

## How it works

| File | What it does |
| --- | --- |
| `MacroService.kt` | The accessibility service. It owns the floating bar, the recording layer, and playback. |
| `CaptureView.kt` | The see-through full-screen layer used while recording. It turns touches into strokes. |
| `MacroPlanner.kt` | Groups strokes into the gestures Android can play, and applies speed and screen-size scaling. It's pure Kotlin with unit tests. |
| `MacroPlayer.kt` | Sends those gestures with the recorded timing, round after round. |
| `GestureSender.kt` | Turns planned gestures into `dispatchGesture` calls. |
| `MacroStore.kt` / `Macro.kt` | Saves macros as JSON in app storage and keeps the settings. |
| `MainActivity.kt` | The setup and settings screen. |

While recording, each finished touch is sent on to the game. The recording layer turns untouchable for a moment and the service replays the same touch with `dispatchGesture`.
