<img src="docs/icon.png" width="96" align="right" alt="Wave Buds icon">

# Wave Buds: open-source Android app for JBL Wave Buds

[English](README.md) | [Русский](README.ru.md)

**Wave Buds** is a free, open-source alternative to the official **JBL Headphones** app for the original **JBL Wave Buds** true wireless earbuds (2021 model, Bluetooth product id `0x2090`). Change ambient sound (Ambient Aware, TalkThru), the equalizer, touch controls, VoiceAware, auto power-off and more, see the battery level, find lost earbuds, and keep them connected. A ~58 KB APK with no internet permission, no analytics and no account; it opens instantly.

<img src="docs/screenshot.png" width="300" alt="Wave Buds main screen">

## Why

The official app weighs about 200 MB and needs around 30 seconds after opening before any setting can be changed. And when the earbuds have to be paired again, their settings can be reset.

Wave Buds keeps a small background service with the earbuds' control link open, so the app shows everything at once (connecting and reading all settings takes under a second). What you choose is saved on the phone and written back to the earbuds every time they connect.

## Features

- **Battery** of the left and right earbud, in the app and in a silent notification.
- **Ambient sound**: off, Ambient Aware, TalkThru.
- **Equalizer**: off, the four JBL presets (Jazz, Vocal, Bass, Extreme Bass) or your own 10-band curve (32 Hz–16 kHz, −8…+8 dB).
- **Touch controls** per earbud: nothing, ambient sound, volume or playback (each choice sets tap, double tap, triple tap and hold; the app explains what each one does).
- **VoiceAware**: on/off and low/medium/high.
- **Smart Audio & Video**: off, audio (quality) or video (low latency). Switching restarts the earbuds; the app reconnects by itself.
- **Voice prompts**, **volume limiter**, **auto power-off** (off, 30 min, 1 h, 2 h).
- **Find my earbuds**: a loud beep from the left or right earbud.
- **Settings profile**: your choices are re-applied whenever the earbuds come back with different settings.
- **Stay connected**: if the earbuds are around but audio is not connected, the service connects them; if the phone and the earbuds forgot each other, it pairs them again as soon as the earbuds are in pairing mode. A switch turns this off when you want to unpair on purpose.
- **Event log** for troubleshooting.
- English and Russian, following the system language.

## Install

Download from the [Releases](../../releases) page. Requires Android 13 or newer.

**Normal APK** (`WaveBuds.apk`, no root). Everything works, with two differences from the root version: when the earbuds need to be paired again, Android shows its usual "Pair?" dialog that you confirm, and the app cannot force an audio connection (rarely needed: paired earbuds connect by themselves).

**Magisk module** (`WaveBuds-magisk.zip`, root). Installs the app as a privileged system app (`BLUETOOTH_PRIVILEGED`), so re-pairing is confirmed silently and the app can connect the audio profiles itself. Install the zip in the Magisk app and reboot; to remove, delete the module. Uninstall the normal APK first if you had it.

### First start

Open the app and allow "Nearby devices" (Bluetooth) and notifications. The service then starts on its own after every reboot. Keep it allowed to run in the background; it uses filtered low-power BLE scanning and is idle most of the time.

The earbuds accept only one control connection, so the official JBL app and Wave Buds cannot be connected at the same time.

## Build

No Gradle: one shell script calls the SDK tools directly.

Requirements: JDK 17+, Android SDK with `platforms;android-33` and `build-tools;35.0.0`, Python 3.

```sh
export ANDROID_SDK=~/Android/Sdk      # default: ~/.local/opt/android-sdk
./build.sh                            # -> out/WaveBuds.apk
./module/make-zip.sh                  # -> out/WaveBuds-magisk.zip
```

The APK is signed with `$WAVEBUDS_KEYSTORE` (default `~/.android/wavebuds.jks`, created on the first build). Keep that file: updates must be signed with the same key.

Source layout: `Proto.java` (frames and encodings), `BudsService.java` (connection logic, profile), `MainActivity.java` (UI), `module/` (Magisk module).

## Protocol

The control protocol (BLE GATT, `aa <cmd> <len> <payload>` frames) is documented in [docs/PROTOCOL.md](docs/PROTOCOL.md), with real frames for every command.

## Limitations

- Only the original Wave Buds (`0x2090`). Wave Buds 2, Wave Buds 3 and other JBL models use different protocols.
- The earbuds refuse a new pairing unless they are in pairing mode, so automatic re-pairing waits for that. They enter it by themselves after losing the pairing; manually: with the earbud in your ear, tap once, then touch and hold.
- No firmware updates; use the official app for those.
- Tested on one phone (Android 13) with one pair of earbuds.

## Disclaimer

Not affiliated with or endorsed by JBL or Harman International. JBL is a trademark of its owner. The protocol was documented from the Bluetooth traffic of the author's own earbuds, for interoperability.

## License

[MIT](LICENSE)
