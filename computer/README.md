# Weave for your computer

The side of Weave that runs on the computer. The phone app connects to it, sees its screen and drives it.

It runs on Linux with Hyprland, and on Windows 10 and 11.

## Install

Linux:

```bash
curl -fsSL https://t3lluz.com/weave/install.sh | sh
```

Windows, in PowerShell:

```powershell
irm https://t3lluz.com/weave/install.ps1 | iex
```

Neither needs root or admin. Both start Weave now and at every login, and end by showing a code to scan.

- **Linux** needs `python3`, `gpu-screen-recorder`, `wl-clipboard`, `wtype`, `notify-send` and write access to `/dev/uinput`. It installs a user service, `weave.service`.
- **Windows** brings its own Python and ffmpeg, all under `%LOCALAPPDATA%\Weave`. Windows asks once whether Python may use the network: say yes.

## Pair a phone

```
weave pair
```

It draws a QR code. In the app, tap **Scan a code**, or point the phone's camera at it. The code works once, for ten minutes, and lets that phone in without a question.

Two other ways in:

- **Same Wi-Fi.** The app lists computers it finds on the network. Tap one, then answer the question that pops up on the computer (or run `weave allow`).
- **By address.** In the app, tap **By address** and type the computer's address or Tailscale name. The computer asks the same question.

A paired phone connects by itself after that. `weave forget` unpairs.

## Where it works from

On the same network, or anywhere over [Tailscale](https://tailscale.com). The phone tries the computer's local address first, then its tailnet one.

What the phone and the computer say to each other is not encrypted by Weave. Tailscale encrypts it; on a plain local network it is as private as that network. On networks you don't trust, set `"allow_lan": false` in the config and use Tailscale only.

## Commands

```
weave pair            show a code for a phone to scan
weave status          what is connected and running
weave allow | deny    answer a phone waiting to pair
weave forget [NAME]   unpair a phone, or all of them
weave clip            send this clipboard to the phone
weave roam            sound that follows the earbuds: where they are (Linux, a lab)
weave roam now        hand the earbuds to the phone
weave roam back       take them back
weave roam watch      the link's strength as it is read, twice a second
```

Settings live in `~/.config/weave/config.json` on Linux and `%APPDATA%\Weave\config.json` on Windows.

## What each system does

| | Linux (Hyprland) | Windows |
| --- | --- | --- |
| Screen, every monitor | yes | yes |
| Mouse, keyboard, text in any language | yes | yes |
| Two fingers as on the computer's own touchpad: swipe back and forward, pinch, the desktop's gestures | yes (a touchpad of its own) | a quick sideways flick goes back and forward |
| The computer's sound on the phone | yes | yes |
| Clipboard both ways, and kept in sync | yes | yes |
| Apps with their icons, for shortcuts | yes | yes |
| Key bindings listed for shortcuts | yes (Hyprland's) | no (Windows has no list of them) |
| Keyboard rises when a click lands in a text field | yes | yes |
| Lock, media keys, wake from sleep | yes | yes |
| The phone's sound and screen on the computer | yes | not yet |
| Sound that follows the earbuds to the phone (a lab) | yes | not yet |

## Sound that follows the earbuds

A lab, off until the app turns it on (Settings, Labs). Bluetooth earbuds reach some ten metres from the computer; a phone in your pocket reaches the computer from anywhere. So when the earbuds' link to the computer gets weak, the phone takes them over and plays the computer's sound, and the computer goes silent. Back in reach, the computer takes them back.

- **It needs** PipeWire with its Pulse side (`pactl`, `pw-cli`, `pw-metadata`), BlueZ (`bluetoothctl`, `gdbus`, `busctl`) and `ffmpeg`. No root: the kernel lets any user read a link's strength.
- **The switch is automatic** when the computer reaches the phone over adb (Developer options, Wireless debugging). Android lets no app connect earbuds, only its shell, so the computer runs a small helper as that shell. Without adb, connect the earbuds in the phone's Bluetooth settings and the sound follows them all the same.
- **Automatic or manual.** Automatic hands them over when the link gets weak and takes them back when the phone is beside the computer again. Manual moves them only when you say so: in the app's menu under Sound, from the Earbuds tile in the phone's quick settings, or with `weave roam now` and `weave roam back`.
- **When nobody has them** (out of reach with no phone to take them), what plays on the computer is paused, so it doesn't run on unheard or come out of the speakers of an empty room.

`weave roam watch` shows what the computer reads: the link's strength (0 is fine on most controllers, negative is too weak), how much sound is getting out, and at what point it hands over. The point is Early, Balanced or Late in the app, or `roam.away` and `roam.hold` in the config for a number of your own.

## How it is built

`weave/server.py` is the daemon, the same on both systems. `weave/desk.py` picks what differs: on Linux `hypr.py`, `inject.py` (uinput), `capture.py` (gpu-screen-recorder), `catalog.py`, `shell.py`; on Windows the same names under `weave/win/` (Win32 through ctypes, ffmpeg's gdigrab, and three small C# helpers compiled on the PC for sound, text focus and icons).

`tools/audit.py HOST` connects as a phone would and checks everything end to end. It moves the pointer and types, so point it at a test machine.

```bash
python3 -m unittest discover -s tests
```

The QR code is drawn by [Nayuki's QR Code generator](https://www.nayuki.io/page/qr-code-generator-library) (MIT), in `weave/qrcodegen.py`.
