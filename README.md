# Weave

Your laptop, run from your phone. The laptop's screen sits at the top, the phone below it is a trackpad and keyboard, and turned sideways the laptop's screen fills the phone.

The laptop side is tandem for now, the daemon DailyDash's laptop screen talks to. Screen, sound and touches go straight between the two over Tailscale.

## What it does

- The laptop's screen, live. Pinch to zoom; the view follows the pointer and slides across when the pointer moves to another monitor.
- A trackpad. One finger moves, a tap clicks, a held finger drags, two fingers scroll, a two-finger tap is the right button.
- The phone's keyboard types on the laptop. It comes up by itself when a click lands in a text field.
- Shortcuts over the trackpad, one step or several: bring an app to the front, open a page, press keys, type, wait. Describe one to Claude ("deafen me on Discord") and it plans the steps and picks icons, or build it by hand. A shortcut can flip between two looks, like Deafen and Undeafen.
- Fold the screen away to make the phone one big trackpad, and fold the shortcuts away to give the pad their room.
- The laptop's sound on the phone, the phone's sound on the laptop, media keys, the clipboard both ways, and lock.

## Finding the laptop

Laptops running tandem announce themselves to a dashboard bridge (`/_api/link` on t3lluz.com by default, Settings changes it). Weave lists them, and a tap connects. The first time, the laptop asks whether to let this phone in: click its notification or run `tandem allow`.

No dashboard? Tap "Connect by address" and type the laptop's Tailscale name.

## AI shortcuts

Settings → AI signs in to Claude with your Claude plan (or takes an API key). The model list is your account's own, read from Anthropic, and the effort slider sets how hard it thinks.

## Install

Grab the APK from [Releases](https://github.com/T3lluz/Weave/releases/latest). Every push to `master` publishes a new one, and each installs over the last.

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

JDK 17, Android SDK 36, Android 8.0 and up.

Weave started as the Laptop screen in [DailyDash](https://github.com/T3lluz/DailyDash), copied out on 2026-11-18.

The pointer is the macOS27-GoldenGate cursor theme's arrow, as installed on the laptop.
