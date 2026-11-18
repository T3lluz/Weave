# Weave, agent guide

Android app (Kotlin, Jetpack Compose, Hilt, OkHttp). The phone drives a laptop: its screen, a trackpad, the keyboard, shortcuts, sound both ways and the clipboard. Only that direction. The laptop never drives the phone here (no phone mirroring).

Package `com.t3lluz.weave`. Single module, single activity.

## Where it came from

Copied 1-1 out of DailyDash's Laptop screen on 2026-11-18 (`data/link`, `ui/screens/link`, `LinkViewModel`), with the pieces of DailyDash it leaned on: the theme (`ui/theme`, Health's room look in `ui/screens/health`), a few components, and the AI picker cut down to Claude. DailyDash's AGENTS.md has the long description of how the remote works (the swipe between monitors, the trackpad dent shader, the shortcut shelf). It still applies here, minus the phone-mirroring room.

## The laptop side

The laptop runs tandem (`~/Projects/tandem` on t3lluz, a user service). The wire format is in tandem's `server.py`: TCP port 47600, frames of a kind byte, a length and a payload (JSON, H.264, AAC). `LinkSession` holds the connection.

Laptops are found through the dashboard's bridge, the same one DailyDash uses. `LinkDirectory` reads the public list at `<dashboard>/_api/link` (laptop_link.py in the t3lluz-dashboard repo). That list carries no tickets: those go only to DailyDash's paired phone hub. So the first connect from Weave makes tandem ask its owner (`tandem allow`, or the notification), and tandem remembers this phone's token after that. Weave is meant to get its own small bridge later.

## AI

Claude only (`AiProvider.ANTHROPIC`). Settings → AI: sign in with a Claude plan (`ClaudeAuthClient`, Claude Code's public OAuth client, paste the code back) or an API key. `AiModels.refresh` reads the account's models from `https://api.anthropic.com/v1/models` (OAuth with the `oauth-2025-04-20` beta header, or the key), kept 12 h, with `AnthropicModels.options` as the fallback. 4.x models fold under "Older". The effort slider (`EffortSlider`, low to max) is stored in `SettingsRepository.anthropicEffort` and goes out as `output_config.effort`. The AI makes shortcuts (`LinkShortcutAi`).

## Shortcuts

A shortcut (`LinkShortcut`, data/link/LinkShortcuts.kt) is a name and up to 8 steps (`LinkStep`): an app by its desktop id, a web page, keys, words to type, or a wait. An app step uses tandem's `launch`, and launching an app that is already open brings it to the front (Hyprland has `focus_on_activate` on), which is how "focus Discord, then press its keys" works without anything new on the laptop. `LinkSession.runSteps` gives a step after an app `SETTLE_MS` (1.3 s; Vesktop takes 0.8 to 1 s to take focus on t3lluz) and counts explicit waits toward it. A shortcut can wear its own icon (`LinkGlyph`: an Iconify id such as `mdi:headphones-off` and a colour; fetched from api.iconify.design and kept as SVG, tinted when drawn) with its app's icon as a badge, and with a `LinkToggle` it has a second look and flips between the two each time it runs (`on`), with a menu item to set it by hand. The AI answers with steps, an icon (id, search words, colour) and a toggle; an icon id that doesn't exist is looked up by its words (`LinkShortcutStore.resolveGlyph`). Shortcuts saved before steps load as one step. The sheet has the step list (`StepsRoom`), the step editor and the icon room with search and colours (`LookRoom`), in LinkShortcutParts.kt.

The AI screens wear Weave's look, not DailyDash's settings cards: rooms (`HealthSection`, `RoomTitle`), the display face, `PillButton` and `PillField`. `AiTone` (the shortcuts' yellow) is the AI's colour; the account badge is the trackpad's green, a key the screen's sky. The model sheet (`ModelSheet.kt`) follows the dashboard's Hermes model picker on a phone: search, each model line with its older versions folded under the newest, and a room at the foot with the model, the effort cells and Done.

## Pointer, keyboard and clipboard

The phone draws the pointer itself (`LocalPointer`, drawn by `LinkPreview`): the laptop's last report ("cur", 30 a second) plus the moves sent since, with the laptop's own acceleration learnt as `gain`. It asks tandem for pictures without the pointer (`"cursor": "local"` in the hello, tandem's `localcursor` cap) and falls back to the laptop's pointer in the picture with a tandem that can't. Autocorrect (More → Trackpad) gives the phone's keyboard a copy of what was typed and sends the laptop the difference (`mirrorEdit`); it resets on every click, on Enter and on arrow keys, and stays off in terminals (tandem's focus report says `terminal`). The keyboard rises only when a click moves from outside a text field into one. Clipboard sync (`clipsync`) sends the laptop's copies as they change; the phone sends its own while the remote is in front (Android lets only the app in front read the clipboard), quietly, and neither side sends back what came from the other.

## Build and look

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Look at changes on Fredde's phone over wireless debugging (`adb mdns services`, `adb connect`, `adb install -r`, `adb exec-out screencap -p`), with tandem running on the laptop.

## Releases

Every push to `master` runs `.github/workflows/release.yml`: tests, a signed release APK, and a GitHub Release `v0.1.<commit count>`. The version comes from the commit count (`-PversionCode`, `-PversionName`), so there are no bump commits. Release notes are the commit subjects since the last tag, so write subjects a person wants to read. `app/tester.jks` is a tester key kept in the repo so each release installs over the last.

The app updates itself from those releases, DailyDash's updater copied over (`data/update`, `AppUpdateViewModel`): it looks for `Weave-<versionName>-vc<versionCode>.apk` on the latest release, checks while the app is open and every few hours in the background (`AppUpdateWorker`), installs through PackageInstaller and shows What's New after the relaunch. Settings → Updates, the update sheet and What's New are drawn in Weave's rooms in `UpdateTone`.
