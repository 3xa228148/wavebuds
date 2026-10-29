<p align="center">
  <img src="docs/banner.png" width="720" alt="Wave Buds: open-source Android app for JBL Wave Buds">
</p>

<p align="center">
  <a href="../../releases/latest"><img src="https://img.shields.io/github/v/release/3xa228148/wavebuds?label=release&color=2563eb" alt="Latest release"></a>
  <a href="../../actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/3xa228148/wavebuds/build.yml?label=build" alt="Build status"></a>
  <img src="https://img.shields.io/badge/Android-13%2B-3ddc84?logo=android&logoColor=white" alt="Android 13+">
  <img src="https://img.shields.io/badge/APK-58%20KB-0f172a" alt="APK size 58 KB">
  <img src="https://img.shields.io/badge/internet-not%20used-0f172a" alt="No internet permission">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-0f172a" alt="MIT license"></a>
</p>

<p align="center">
  <b>English</b> · <a href="README.ru.md">Русский</a>
</p>

**Wave Buds** is a free, open-source alternative to the official **JBL Headphones** app for the original **JBL Wave Buds** true wireless earbuds (2021 model, Bluetooth product id `0x2090`). Change ambient sound (Ambient Aware, TalkThru), the equalizer, touch controls, VoiceAware and auto power-off, see the battery level, find a lost earbud, and keep the earbuds connected. A ~58 KB APK with no internet permission, no analytics and no account; it opens instantly.

<p align="center">
  <img src="docs/screenshot.png" width="300" alt="Wave Buds main screen">
</p>

## Why

|  | JBL Headphones | Wave Buds |
|---|---|---|
| Download size | ~200 MB | **58 KB** |
| From opening the app to changing a setting | ~30 s | **instant** |
| Internet, analytics, account | yes | **none** |
| Settings after the earbuds are re-paired | can be reset | **restored** |
| Re-pairing after the phone and earbuds forget each other | by hand | **automatic** |

A small background service keeps the earbuds' control link open, so the app shows everything at once: connecting and reading every setting takes under a second. What you choose is saved on the phone and written back to the earbuds whenever they connect.

## Features

| Feature | What it does |
|---|---|
| 🔋 **Battery** | Left and right earbud, in the app and in a silent notification. A bud in the case shows a dash. |
| 🌊 **Ambient sound** | Off, Ambient Aware or TalkThru. |
| 🎚️ **Equalizer** | Off, the four JBL presets (Jazz, Vocal, Bass, Extreme Bass) or your own 10-band curve, 32 Hz–16 kHz, −8…+8 dB. |
| 👆 **Touch controls** | Per earbud: nothing, ambient sound, volume or playback; the app spells out what tap, double tap, triple tap and hold do. |
| 🗣️ **VoiceAware** | On/off and low, medium or high. |
| 🎬 **Smart Audio & Video** | Off, audio (quality) or video (low latency). The earbuds restart; the app reconnects by itself. |
| ⚙️ **More** | Voice prompts, volume limiter, auto power-off (off, 30 min, 1 h, 2 h). |
| 📍 **Find my earbuds** | A loud beep from the left or right earbud. |
| 💾 **Settings profile** | If the earbuds come back with other settings, yours are re-applied. |
| 🔗 **Stay connected** | Connects the audio when the earbuds are around but not connected, and pairs them again when the phone and the earbuds have forgotten each other. A switch turns it off when you unpair on purpose. |
| 📝 **Event log** | For troubleshooting. English and Russian, following the system language. |

## Install

Download from the [latest release](../../releases/latest). Requires Android 13 or newer.

| | Normal APK | Magisk module |
|---|---|---|
| File | `WaveBuds.apk` | `WaveBuds-magisk.zip` |
| Root | not needed | Magisk |
| Re-pairing | Android shows its "Pair?" dialog, you confirm | confirmed silently, but only for the pairing the app itself started, and only while the switch is on |
| Connecting audio | done by Android (paired earbuds connect by themselves) | the app can also connect it itself |
| How | install the APK | install the zip in the Magisk app and reboot; remove the normal APK first |

The Magisk module installs the app as a privileged system app with `BLUETOOTH_PRIVILEGED`; it does not run as root.

**First start.** Open the app and allow "Nearby devices" (Bluetooth) and notifications. After that the service starts on its own after every reboot. Let it run in the background: it uses filtered low-power BLE scanning and is idle most of the time.

> [!NOTE]
> The earbuds accept a single control connection, so the official JBL app and Wave Buds cannot both be connected. Force-stop one to use the other.

## Privacy and safety

- No `INTERNET` permission, no analytics, no account. Android backup is turned off for the app, so its settings (including your earbuds' Bluetooth address) are not copied to a cloud backup.
- Only **your** earbuds are touched: ones paired with this phone, or ones you used with the app before. Someone else's Wave Buds nearby are recognised by their info frame (and by the phone-name tag in their advert) and left alone; the app never writes settings to them or tries to pair with them.
- The earbuds' control link has no authentication (a property of the earbuds), which is why the app checks the above before it reads or writes anything.

## Build

No Gradle: one shell script calls the SDK tools directly.

Requirements: JDK 17+ (releases are built with JDK 21), Android SDK with `platforms;android-33` and `build-tools;35.0.0`, Python 3.

```sh
export ANDROID_SDK=~/Android/Sdk      # default: ~/.local/opt/android-sdk
./test.sh                             # protocol tests, plain JVM
./build.sh                            # -> out/WaveBuds.apk
./module/make-zip.sh                  # -> out/WaveBuds-magisk.zip
```

The APK is signed with `$WAVEBUDS_KEYSTORE` (default `~/.android/wavebuds.jks`, created on the first build). Keep that file: updates must be signed with the same key.

<details>
<summary><b>Check that a release was built from this source</b></summary>

The build is reproducible: apart from the signature, a release APK is byte-for-byte what `build.sh` produces from the tagged commit with the same JDK major version and build-tools.

```sh
git checkout <release commit>
./verify.sh ~/Downloads/WaveBuds.apk    # -> OK: identical apart from the signature
```

</details>

<details>
<summary><b>Source layout</b></summary>

| Path | What |
|---|---|
| `src/local/wavebuds/Proto.java` | frames and encodings, equalizer calibration |
| `src/local/wavebuds/BudsService.java` | connection logic, trust checks, settings profile |
| `src/local/wavebuds/MainActivity.java` | the UI |
| `tests/ProtoTest.java` | protocol tests on real captured frames |
| `module/` | Magisk module |
| `docs/PROTOCOL.md` | the control protocol |

</details>

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
