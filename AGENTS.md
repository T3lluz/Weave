# Weave, agent guide

Android app (Kotlin, Jetpack Compose, Hilt, OkHttp). The phone drives a laptop: its screen, a trackpad, the keyboard, shortcuts, sound both ways and the clipboard. Only that direction. The laptop never drives the phone here (no phone mirroring).

Package `com.t3lluz.weave`. Single module, single activity.

## Where it came from

Copied 1-1 out of DailyDash's Laptop screen on 2026-11-18 (`data/link`, `ui/screens/link`, `LinkViewModel`), with the pieces of DailyDash it leaned on: the theme (`ui/theme`, Health's room look in `ui/screens/health`), a few components, and the AI picker cut down to Claude. DailyDash's AGENTS.md has the long description of how the remote works (the swipe between monitors, the trackpad dent shader, the shortcut shelf). It still applies here, minus the phone-mirroring room.

## The laptop side

The computer runs the daemon in `computer/` (it began as tandem, `~/Projects/tandem`, which older installs still run; the names are used for both below). The wire format is in its `server.py`: TCP port 47600, frames of a kind byte, a length and a payload (JSON, H.264, AAC). `LinkSession` holds the connection.

Laptops are found through the dashboard's bridge, the same one DailyDash uses. `LinkDirectory` reads the public list at `<dashboard>/_api/link` (laptop_link.py in the t3lluz-dashboard repo). That list carries no tickets: those go only to DailyDash's paired phone hub. So the first connect from Weave makes the computer ask its owner (`weave allow`, or the notification), and it remembers this phone's token after that. A computer picked from the dashboard's list keeps the dashboard's id for it (`LinkConfig.board`); only the entry with that id may change its saved addresses or carry its key (`joinKnown`), never one with the same name or a shared address. Weave is meant to get its own small bridge later.

## AI

Claude only (`AiProvider.ANTHROPIC`). Settings → AI: sign in with a Claude plan (`ClaudeAuthClient`, Claude Code's public OAuth client, paste the code back) or an API key. `AiModels.refresh` reads the account's models from `https://api.anthropic.com/v1/models` (OAuth with the `oauth-2025-04-20` beta header, or the key), kept 12 h, with `AnthropicModels.options` as the fallback. 4.x models fold under "Older". The effort slider (`EffortSlider`, low to max) is stored in `SettingsRepository.anthropicEffort` and goes out as `output_config.effort`. The AI makes shortcuts (`LinkShortcutAi`).

## Shortcuts

A shortcut (`LinkShortcut`, data/link/LinkShortcuts.kt) is a name and up to 8 steps (`LinkStep`): an app by its desktop id, a web page, keys, words to type, or a wait. An app step uses tandem's `launch`, and launching an app that is already open brings it to the front (Hyprland has `focus_on_activate` on), which is how "focus Discord, then press its keys" works without anything new on the laptop. `LinkSession.runSteps` gives a step after an app `SETTLE_MS` (1.3 s; Vesktop takes 0.8 to 1 s to take focus on t3lluz) and counts explicit waits toward it. A shortcut can wear its own icon (`LinkGlyph`: an Iconify id such as `mdi:headphones-off` and a colour; fetched from api.iconify.design and kept as SVG, tinted when drawn) with its app's icon as a badge, and with a `LinkToggle` it has a second look and flips between the two each time it runs (`on`), with a menu item to set it by hand. The AI answers with steps, an icon (id, search words, colour) and a toggle; an icon id that doesn't exist is looked up by its words (`LinkShortcutStore.resolveGlyph`). Shortcuts saved before steps load as one step. The sheet has the step list (`StepsRoom`), the step editor and the icon room with search and colours (`LookRoom`), in LinkShortcutParts.kt.

The AI screens wear Weave's look, not DailyDash's settings cards: rooms (`HealthSection`, `RoomTitle`), the display face, `PillButton` and `PillField`. `AiTone` (the shortcuts' yellow) is the AI's colour; the account badge is the trackpad's green, a key the screen's sky. The model sheet (`ModelSheet.kt`) follows the dashboard's Hermes model picker on a phone: search, each model line with its older versions folded under the newest, and a room at the foot with the model, the effort cells and Done.

## Tiles, top rows and the trackpad's feel

Every top row is made of the dock's tiles (`ToneTile`, `IconTile`, `BackTile` and the sizes in `TileRow`, ui/components/ToneTile.kt): a flat tint of the thing's own colour, lit while on, 54 dp wide and 50 tall, with the wide one taking the rest. Back is the same tile on every screen. The remote's row is back, `StatusTile` (the laptop's name over monitor and ping), zoom, and the two fold tiles. Don't add round grey buttons.

The full-screen panel is mint glass (`PanelTint`), a circle when folded, 16 dp in from the screen's edges: a Pixel 10's screen corner is 52 dp, and a squircle 12 dp in ran into it. The bubble is dragged by its handle and rests against the nearest edge (`PanelField.rest` in LinkPanel.kt), clear of the camera's hole, and its place is kept (`LinkPrefs.panelSpot`). The panel unfolds into the room it has: leftwards from the right half, upwards from the lower half with the shortcuts over the controls (`PanelPlace`, `Modifier.panelPlace`). Its handle must stay under the finger that opened it, so the same tap folds it again: the panel is wider than half a phone's screen, and along the top and bottom the bubble doesn't rest around the middle, where the panel would have to shift (`roomy`). With the keyboard up the panel moves above the key row and shows no shortcuts.

The trackpad's grain (`Grain` in ui/util/Haptics.kt) is one texture at any pace: `STRENGTH` is the one number for more or less of it, and the strength and pitch wander a little so it reads as a surface and not a hum. Fredde asked for it uniform and faint (2026-11-19) after trying a version that followed the finger's speed.

The picture sliding over to another monitor is felt as it is seen (`HapticHelper.slideOver`, played by `LinkPreview` as the slide starts): six ticks and a soft landing, each where the slide's spring has gone another stretch (`AppMotion.Link.swipeMs`), so they thin out and fade as it slows. Fredde asked for it (2026-11-19) after Essentials' tabs, which tick every tenth of a swipe. It is one composed vibration: ticks asked for one by one off the animation's frames came 15 ms apart, a tick takes the Pixel 30, and every other one was dropped. The grain neither plays nor cancels while it runs (`phraseUntil`). `adb shell dumpsys vibrator_manager` lists what was played, with times and what was cut off.

A held drag belongs to every finger: once the hold has taken, the finger that moves furthest moves the pointer, and the drag ends when the last finger lifts (`trackpad` in LinkInput.kt). While it lasts the weave is lit under the fingers and the Left button shows held. That light falls away to nothing at its rim (`GRIP_FALL`, a radial gradient), and a tap's ring is out and gone in 440 ms, losing most of its light by halfway: Fredde asked for both (2026-11-19) after hard-edged bands and a 760 ms ring.

## The website and the README

`site/` is t3lluz.com/weave (see `deploy/server/README.md`). It wears the app's look: rooms in the subjects' colours (`color-mix` of the tone into the page's dark, 10 %), the dock's tiles with their corner shapes, and the app's display face, Google Sans Flex, self-hosted from `site/public/fonts` (OFL; the two files are Google Fonts' latin subsets, the display one pinned at width 86 and roundness 100). The hero is the trackpad: `weave.js` draws the dots, dents them under the pointer and sends the tap's ring out. The page's CSP allows no inline styles, and pictures, styles and scripts are cached for a day, so a changed one gets a new `?v=` in the HTML.

`assets/icon.svg` is a copy of `site/public/icon.svg`: T3 Code shows a project's icon from there, as it does for the plugins. Change both together.

The README follows Fredde's plugin READMEs: centred icon, name, two lines, badges, a row of links, one preview picture, then Features with bold lead-ins. Its pictures live in `site/public` too: anything outside `site/`, `computer/`, `deploy/`, `assets/` and `*.md` makes the release workflow cut a new APK.

The pictures are real screenshots, taken in the emulator (`Pixel_9_API_36`, headless) against the Windows test VM, never of Fredde's own screen. The emulator has no Google Sans Flex, so a throwaway build reads the font from the app's own files (pulled from the phone, pushed with `run-as`), and names the computer "desktop". Demo shortcuts go in as `shared_prefs/link_shortcuts.xml`. The app there points at `10.0.2.2:47700`; `weave allow` in the VM lets it in. `og.png` and `preview.png` are Chromium screenshots of small pages built from the site's own stylesheet.

## Pointer, keyboard and clipboard

The phone draws the pointer itself (`LocalPointer`, drawn by `LinkPreview` with the macOS arrow from the laptop's cursor theme, macOS27-GoldenGate, in assets/cursors): the laptop's last report ("cur", 30 a second) plus the moves sent since. The phone numbers its moves (`"s"` on each "m"), and each report names the last one the laptop's pointer already shows (`"seq"`, cap `moveseq`), so the phone draws exactly the moves after it. A tandem without numbers leaves a guess: the moves sent more than a round trip and half a polling interval before the report lands. tandem's welcome gives the gain (1 + sensitivity on Hyprland's flat profile), so the prediction is exact; without it the gain is learnt. Hyprland paints the pointer into the screen image here (software pointers, its default with an NVIDIA card), where the recorder's `-cursor no` can't leave it out. So while a phone that draws its own shows the picture, tandem sets `cursor:no_hardware_cursors` to 0 (through `hyprctl eval` on a Lua config): the pointer goes on its own plane, stays on the laptop's screens and is out of the picture. tandem puts the option back when the picture folds, the phone leaves or tandem stops, and sets it again after a Hyprland reload. The pointer's shape (hand, I-beam) can't be known: Hyprland doesn't tell, short of a plugin. The phone's keyboard works as in any app by default (`nativeKeyboard`): `LinkKeyView` keeps a copy of what was typed, sends the laptop the difference (`mirrorEdit`), reports the selection to the keyboard after each edit (AOSP-based keyboards need it), turns its cursor moves into arrow keys, and starts afresh on every click, Enter and arrow key; plain keys in a terminal (tandem's focus report says `terminal`). The keyboard rises only when a click moves from outside a text field into one. Two fingers and more: where the daemon has a touchpad of its own (cap `touchpad`, Linux: a third uinput device, `Touchpad` in `computer/weave/inject.py`), the phone sends the fingers as they are (`touch`, mm on the phone's pad) from the moment they pass the slop, and libinput reads them as it reads the laptop's pad: finger scrolling with its stop (what a browser's swipe back needs), pinch, Hyprland's own gestures. The phone says which way it wants scrolling to go and the daemon lays the fingers the other way round when Hyprland's touchpads go the other way. One finger never goes on that pad (it would move the pointer there). Without the cap (Windows, tandem) two fingers scroll as a wheel with the phone's own roll, and a quick sideways flick clicks the mouse's back or forward button. Clipboard sync (`clipsync`) sends the laptop's copies as they change; the phone sends its own quietly, and neither side sends back what came from the other. Android lets only the focused app read the clipboard, so the phone looks each time Weave's window gets the focus and remembers when the last clip it looked at was made (`LinkPrefs.clipSeen`): a clip copied in another app goes over on return. The daemon keeps the last text both had per phone in memory, so a phone that comes back gets what was copied meanwhile; the phone's own newer copy wins. Wake: tandem's welcome gives the network card's MAC and broadcast address; the finder's "Wake it" sends a magic packet (`WakeOnLan`), which works only on the same network and only once wake-on-Wi-Fi is on on the laptop.

## Build and look

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Look at changes on Fredde's phone over wireless debugging (`adb mdns services`, `adb connect`, `adb install -r`, `adb exec-out screencap -p`), with tandem running on the laptop.

## Releases

Every push to `master` runs `.github/workflows/release.yml`: tests, a signed release APK, and a GitHub Release `v0.1.<commit count>`. The version comes from the commit count (`-PversionCode`, `-PversionName`), so there are no bump commits. Release notes are the commit subjects since the last tag, so write subjects a person wants to read. `app/tester.jks` is a tester key kept in the repo so each release installs over the last.

The app updates itself from those releases, DailyDash's updater copied over (`data/update`, `AppUpdateViewModel`): it looks for `Weave-<versionName>-vc<versionCode>.apk` on the latest release, checks while the app is open and every few hours in the background (`AppUpdateWorker`), installs through PackageInstaller and shows What's New after the relaunch. Settings → Updates, the update sheet and What's New are drawn in Weave's rooms in `UpdateTone`.

## Home screen widget

`widget/` is a Glance widget of shortcuts. `ShortcutsWidgetConfigActivity` picks two to eight for one widget (from Weave's sheet for a new one, where `WidgetPinnedReceiver` puts the picks on the widget once the home screen adds it; from the home screen's widget list; from a widget's edit button). `widgetLayout` splits however many were picked into rows that fill the widget, and sizes the pictures. Gaps are padding, not spacers: Glance allows ten children in a row. A tap goes to `ShortcutRunWorker`, which runs the steps through `LinkOneShot`: the remote's own line when it is up, else a line of its own that says hello with `quiet` (no "connected" notice on the computer), sends the steps at `paceSteps`' pace and hangs up. `WidgetFaces` draws the tiles' pictures as bitmaps. Test it on the emulator against a fake laptop, never by tapping real shortcuts.

## The computer side

`computer/` is the daemon the app talks to (it began as tandem, a separate repo). One `server.py` for Linux and Windows; `desk.py` picks the system's own modules, and `weave/win/` holds Windows'. Read `computer/README.md` first. Rules that bit:

- Anything slow on Windows (PowerShell takes seconds cold) is done at startup in `shell.prewarm()` or in the background, never on a phone's hello: the welcome waited on the network card lookup once and the first connection after boot hung.
- Every program the Windows daemon starts gets `CREATE_NO_WINDOW`, or a console window flashes on the user's screen (`proc.run` does it; a new `create_subprocess_exec` must too).
- Text on Windows is typed at `TYPING_PACE` with the layout's real keys: in a burst, Windows 11's Notepad drops Shift and blurs characters sent as themselves.
- The pairing question on Windows is system modal, or it opens behind the window in front.
- Test Windows in the VM at `~/windows-dualboot/vm/weave` (`run.sh` starts it, `./win 'powershell'` runs a command in its desktop session, `host.py` must be running). `computer/tools/audit.py 127.0.0.1:47700` audits it. Never run the audit against a machine someone is using.
- Pairing: `weave pair` makes a one-time code (`pairing.py`), carried in a link's fragment. The app reads it from a scan (`LinkScan.kt`), from `weave://pair?...` (`MainActivity`), and `LinkInvite.kt` parses it. Discovery is a UDP "weave?" broadcast (`discovery.py`, `findOnNetwork`).
