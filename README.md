# Weave

Your laptop, run from your phone. The laptop's screen sits at the top, the phone below it is a trackpad and keyboard, and turned sideways the laptop's screen fills the phone.

The computer side is Weave too: a small program for Linux (Hyprland) and Windows, in [`computer/`](computer/README.md). Screen, sound and touches go straight between the two, on your own network or over Tailscale.

## What it does

- The laptop's screen, live. Pinch to zoom; the view follows the pointer and slides across when the pointer moves to another monitor.
- A trackpad. One finger moves, a tap clicks, a held finger drags, two fingers scroll, a two-finger tap is the right button. Two fingers swept sideways go back and forward, as on a laptop's own pad.
- The phone's keyboard types on the laptop. It comes up by itself when a click lands in a text field.
- Shortcuts over the trackpad, one step or several: bring an app to the front, open a page, press keys, type, wait. Describe one to Claude ("deafen me on Discord") and it plans the steps and picks icons, or build it by hand. A shortcut can flip between two looks, like Deafen and Undeafen.
- Fold the screen away to make the phone one big trackpad, and fold the shortcuts away to give the pad their room.
- A home screen widget runs shortcuts without opening Weave. Pick two to eight for it and they size themselves to fill it.
- Full screen, the controls and your shortcuts sit in one glass panel in the corner. It folds to a single button by itself after a few seconds.
- The laptop's sound on the phone, the phone's sound on the laptop, media keys, the clipboard both ways, and lock.

## Install

1. On the computer, Linux: `curl -fsSL https://t3lluz.com/weave/install.sh | sh`. Windows, in PowerShell: `irm https://t3lluz.com/weave/install.ps1 | iex`.
2. On the phone, the APK from [Releases](https://github.com/T3lluz/Weave/releases/latest), or from [t3lluz.com/weave](https://t3lluz.com/weave).
3. The installer ends by showing a code. In the app, tap **Scan a code**.

Later, `weave pair` on the computer shows a new code. Computers on the same Wi-Fi also show up in the app by themselves, and **By address** takes an address or a Tailscale name; either way the computer asks once whether to let the phone in.

## AI shortcuts

Settings → AI signs in to Claude with your Claude plan (or takes an API key). The model list is your account's own, read from Anthropic, and the effort slider sets how hard it thinks.

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

JDK 17, Android SDK 36, Android 8.0 and up. The computer side's tests: `cd computer && python3 -m unittest discover -s tests`.

Weave started as the Laptop screen in [DailyDash](https://github.com/T3lluz/DailyDash), copied out on 2026-11-18.

The pointer is the macOS27-GoldenGate cursor theme's arrow, as installed on the laptop.
