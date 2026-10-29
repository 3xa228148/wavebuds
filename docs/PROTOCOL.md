# JBL Wave Buds control protocol

Reference for the BLE control protocol of the original JBL Wave Buds (2021, product id `0x2090`), as used by this app. Everything here was observed in the Bluetooth traffic of the author's own earbuds; every example is a real frame from those captures, with the Bluetooth address, serial number and phone-name hash replaced.

Conventions: hex bytes are lowercase and space-separated; multi-byte numbers are little-endian; "→" separates a request from the reply.

## 1. Transport

Control runs over BLE GATT, independent of the audio link (A2DP/HFP over classic Bluetooth). The LE link needs no bonding, encryption or authentication.

| Item | Value |
|---|---|
| Service | `65786365-6c70-6f69-6e74-2e636f6d0000` (the bytes spell ASCII `excelpoint.com`) |
| Notify characteristic | `…2e636f6d0001`, enable through its CCCD `0x2902` (`01 00`) |
| Write characteristic | `…2e636f6d0002`, write with response |
| MTU | the official app requests 512; the largest frame is 120 bytes |

The earbuds accept a single LE connection: while one app holds it they stop advertising, and a second app cannot find them.

## 2. Discovery

Both earbuds advertise (each with its own random LE address). The advert carries service data for UUID `0xfddf`:

| Offset | Size | Meaning |
|---|---|---|
| 0 | 2 | product id, `90 20` = `0x2090` |
| 2 | 1 | colour (`01` = white) |
| 3 | 2 | CRC-16 of the Bluetooth name of the phone the buds are paired with (poly `0xa001`, init `0`) |
| 5 | … | further bytes, not needed |

Example: `90 20 01 c3 7a …`.

Their Google Fast Pair model id is `08 62 ab`. Per the Fast Pair specification, a discoverable (pairing-mode) device advertises service data `0xfe2c` containing just the model id; outside pairing mode the `0xfe2c` data is an account-key filter instead (observed).

## 3. Frames

```
aa <cmd> <len> <payload: len bytes>
```

The equalizer command `a2` uses a two-byte length in writes and replies (`aa a2 74 00 …`); its read requests use the one-byte form.

Send one request at a time and wait for its reply. Most replies reuse the request's command byte; some commands are answered with a generic acknowledgement:

```
aa 00 02 <cmd> <status>        status 00 = ok, 01 = not supported
```

## 4. Pushed on subscribe

Right after notifications are enabled the buds send three frames without being asked:

```
aa 12 24  4a 42 4c 20 57 41 56 45 20 42 55 44 53 2d 4c 00  90 20  00 64  11 22 33 44 55 66  05 09 00 64 64 64 00 a1 10 01
          "JBL WAVE BUDS-L" NUL                               pid    ?      classic MAC reversed (66:55:44:33:22:11)   ?
aa 25 0d  01 00 00 64 64 ff ff 64 00 a1 10 ff ff                          battery, see 6
aa 91 07  12 01 00 02 00 03 00                                            ambient, see 7
```

The name suffix tells which bud currently holds the control link (`-L` / `-R`). The classic address is the one Android pairs with for audio.

## 5. Status, serial, host tag, power

| Function | Frames |
|---|---|
| status | `aa 21 01 30` → `aa 22 0d 30 00 00 ff 31 00 01 00 00 01 00 1e 00` |
| serial number | `aa 94 01 01` → `aa 94 11 02 <16 ASCII bytes>` |
| host tag | `aa 61 02 <crc16>` → `aa 00 02 61 00` (the official app echoes bytes 3–4 of the advert) |
| power off | `aa 97 00` |

Status payload byte 10 is "auto power-off enabled", bytes 11–12 its timeout in minutes (`00 1e 00` above: disabled, 30 min).

## 6. Battery

`aa 25 01 00` → `aa 25 0d 01 00 00 <left %> <right %> ff ff …`

The buds also push this frame when a level changes. The case level is not reported (`ff`).

## 7. Ambient sound

| Function | Frames |
|---|---|
| read | `aa 91 01 11` → `aa 91 07 12 01 00 02 <aa> 03 <tt>` |
| Ambient Aware | `aa 91 07 10 01 00 02 01 03 00` → `aa 91 07 12 01 00 02 01 03 00` |
| TalkThru | `aa 91 07 10 01 00 02 00 03 01` → `aa 91 07 12 01 00 02 00 03 01` |
| off | `aa 91 01 13` → `aa 91 07 12 01 00 02 00 03 00` |

## 8. VoiceAware

How much of your own voice you hear during calls.

| Function | Frames |
|---|---|
| read | `aa 98 01 01` → `aa 98 03 02 <level> <on>` |
| set | `aa 98 03 00 <level> <on>` → `aa 98 03 02 <level> <on>` |

Level `01` low, `02` medium, `03` high. Example: `aa 98 03 00 03 01` (on, high). Every set is followed by an extra `aa 9b 03 02 01 00`.

## 9. Smart Audio & Video

Three 16-bit parameters followed by `ff ff`:

| Mode | Parameters | Bytes |
|---|---|---|
| off (normal) | 197, 46, 280 | `c5 00 2e 00 18 01 ff ff` |
| audio | 256, 46, 280 | `00 01 2e 00 18 01 ff ff` |
| video | 197, 46, 120 | `c5 00 2e 00 78 00 ff ff` |

Read `aa 82 00` → `aa 83 08 <8 bytes>`; set `aa 81 08 <8 bytes>` → `aa 83 08 <8 bytes>`. After a set the buds restart their audio pipeline and drop the LE link; they advertise again about 10–15 s later.

## 10. Touch gestures

Each bud has four touch events, each mapped to an action:

| Event | Left | Right |
|---|---|---|
| tap | `06` | `09` |
| double tap | `07` | `0a` |
| triple tap | `08` | `0b` |
| touch and hold | `0c` | `0e` |

| Action | Code |
|---|---|
| none | `00` |
| volume up / down | `01` / `02` |
| TalkThru | `04` |
| next / previous track | `05` / `06` |
| play / pause | `08` |
| ambient sound on/off | `0d` |
| voice assistant | `a1` |

Read both buds: `aa 77 02 01 ff` → `aa 77 11 02` + 8 pairs `<event> <action>`, e.g.
`aa 77 11 02 06 00 07 00 08 00 0c 00 09 00 0a 00 0b 00 0e 00` (all none).

Set one bud: `aa 77 09 00` + 4 pairs, echoed back with `00` → `02`:
`aa 77 09 00 06 08 0c a1 07 05 08 06` → `aa 77 09 02 06 08 0c a1 07 05 08 06` (left bud: playback preset).

The official app only offers four presets (tap, hold, double, triple):

| Preset | Actions |
|---|---|
| nothing | `00 00 00 00` |
| ambient sound | `0d a1 04 00` |
| volume | `01 a1 02 00` |
| playback | `08 a1 05 06` |

Call handling (double tap answers/ends, hold rejects/mutes) is built in and not part of this mapping.

## 11. Voice prompts, volume limiter, auto power-off, find my buds

| Function | Frames |
|---|---|
| voice prompts read | `aa 93 01 04` → `aa 93 02 05 <on>` |
| voice prompts set | `aa 93 02 03 <on>` → `aa 93 02 05 <on>` |
| volume limiter read | `aa a5 01 01` → `aa a5 03 02 01 <on>` |
| volume limiter set | `aa a5 03 00 01 <on>` → `aa a5 03 02 01 <on>` |
| auto power-off set | `aa 33 03 <on> <minutes, 2 bytes>` → `aa 00 02 33 00`, e.g. `aa 33 03 01 3c 00` = on, 60 min |
| find my buds status | `aa 23 01 01` → `aa 23 03 02 <left> <right>` + `aa 00 02 23 00` |
| find my buds set | `aa 23 03 00 <left> <right>` → `aa 23 03 02 <left> <right>` + `aa 00 02 23 00`; `01` beep, `00` stop, `ff` leave as is |

The current auto power-off setting is read from the status frame (section 5).

## 12. Equalizer

Command `a2`, 116-byte payload (two-byte length `74 00`):

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `00` write, `02` reply / read result, `04` factory curve |
| 1 | 1 | category: `00` off, `01` JAZZ, `02` VOCAL, `03` BASS, `09` EXTREME BASS, `e6` custom |
| 2 | 4 | calibration (float, dB), see below |
| 6 | 1 | `30` |
| 7 | 4 | left pre-gain (float, dB) |
| 11 | 4 | right pre-gain (float, dB) |
| 15 | 1 | band count, `0a` |
| 16 | 10 × 10 | bands: `01` (peaking filter), gain (float, dB), frequency (float, Hz), Q (byte, `01`) |

Bands are 32, 64, 125, 250, 500, 1k, 2k, 4k, 8k and 16k Hz; gains from −8 to +8 dB.

| Function | Frames |
|---|---|
| read current EQ | `aa a2 02 01 ff` → `aa a2 74 00 02 …` |
| read factory curve | `aa a2 02 01 ca` → `aa a2 74 00 04 ca …` (9 bands, Q stored ×8) |
| write | `aa a2 74 00 00 …` → `aa a2 74 00 02 …` (echo) |

Example, JAZZ:

```
aa a2 74 00 00 01  05 ea 9d 40  30  00 00 80 c0  00 00 80 c0  0a
   01 00 00 80 40 00 00 00 42 01     +4 dB @ 32 Hz
   01 00 00 40 40 00 00 80 42 01     +3 dB @ 64 Hz
   … 8 more bands
```

Presets (gains 32 Hz → 16 kHz, then pre-gain; all in dB):

| Preset | Gains | Pre-gain |
|---|---|---|
| JAZZ | 4, 3, 1, 2.5, −1.5, −1.5, 0, 1, 3, 4 | −4 |
| VOCAL | −1.5, −3, −3, 1, 3.5, 3.5, 3, 1.5, 0, −1.5 | −3.5 |
| BASS | 3, 3, 2, 0, 0, 0, 0, 0, 0, 0 | −3 |
| EXTREME BASS | 4, 3, 5, 3.5, −1, 0, 0, 2, 2, 0 | −8 |

"Off" is category `00` with every gain, the pre-gain and the calibration at 0. A custom curve uses category `e6` and pre-gain 0.

**Calibration** is the headroom the firmware keeps before the EQ. The official app computes it in a native library; this value reproduces it within 0.01 dB for the captured custom curves and within 0.06 dB for the presets:

> the maximum, over 20 Hz–20 kHz, of the summed magnitude response (dB) of the factory curve and the user curve, each band an RBJ peaking biquad at 48 kHz, plus 0.406 dB

(factory-curve Q = stored byte / 8, user-curve Q = 1). For the presets the app's own values are 4.935 (JAZZ), 3.132 (VOCAL), 3.996 (BASS) and 5.201 (EXTREME BASS), so this app sends the presets exactly as captured. See `Proto.calibration()` in the source and `tests/ProtoTest.java`.

## 13. Pairing quirk

The buds refuse a new classic pairing with HCI status `0x18` (Pairing Not Allowed) unless they are in pairing mode. In pairing mode Android's normal bonding works; the app then pages the known classic address and confirms the pairing request.
