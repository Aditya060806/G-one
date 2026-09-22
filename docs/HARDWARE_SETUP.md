# G-one wearable: hardware setup

How to build the wearable that `firmware/g_one_wearable` runs on, and how the app talks to it.

> **Status:** the sensor code is the owner's bench sketch, confirmed working on this board with
> the wiring below. The firmware adds the Bluetooth link to it; see `VERIFICATION.md` for what
> has been checked end to end with the app.
>
> The sync logic (acknowledged records, resume, catch-up) is checked without hardware by
> `tools/wearable-emulator/emulate.py --selftest`, which runs the same algorithm against a
> simulated phone. That is a check of the design, not of this C++ on an ESP32.

REFERENCE (the GONE project) was built around an Arduino Uno, an EMG sensor and an HC-05 over Classic Bluetooth. None of that carries over:

| | REFERENCE | This build |
|---|---|---|
| Controller | Arduino Uno, 5 V, 10-bit ADC | ESP32-S3, 3.3 V, 12-bit ADC |
| Link | HC-05, Classic Bluetooth serial | Built-in Bluetooth LE |
| Sensors | EMG only | MAX30100, DS18B20, MPU6050, EMG |
| Storage / power | none / 9 V battery | none: live only / LiPo |

## Parts

| Part | Role | Notes |
|---|---|---|
| ESP32-S3-DevKitC-1 (WROOM-1) | Controller and BLE | BLE only, no Classic Bluetooth |
| MAX30100 module (the board printed "MAX30100/30102") | Heart rate, SpO₂ | I²C address 0x57. Hobby-grade: motion and poor contact produce wrong values. A MAX30102 needs different firmware: the MAX30100 library does not drive it, and the SparkFun MAX3010x library does not drive a MAX30100 |
| DS18B20 | **Skin** temperature | Needs a **4.7 kΩ pull-up** on data unless it is on a breakout board that has one |
| MPU6050 module | Motion, impacts | I²C address 0x68, on its own I²C bus. Firmware sets ±8 g |
| EMG module + electrode pads | Muscle activity | Output must never exceed 3.3 V at the ESP32 pin |
| LiPo 500–1000 mAh, TP4056, 3.3 V LDO, switch | Power | See "Power" below |
| HM-10 | Not needed | The ESP32-S3 provides the same FFE0/FFE1 profile itself |

**Not in the parts list, and why it matters:**

- **LED or buzzer:** REFERENCE flashed an LED and sounded a buzzer on high EMG. There is no equivalent here; alerts come from the phone.
- **Battery-voltage divider** (two resistors to an ADC1 pin): without it, battery level cannot be measured, so the app never shows a battery figure.

## Wiring (ESP32-S3-DevKitC-1)

| Signal | ESP32-S3 pin | Connects to |
|---|---|---|
| 3V3 | 3V3 | MAX30100 VIN, MPU6050 VCC, DS18B20 VDD, EMG module supply (if 3.3 V-capable) |
| GND | GND | All grounds |
| I²C bus 0 SDA | GPIO 8 | MAX30100 SDA |
| I²C bus 0 SCL | GPIO 9 | MAX30100 SCL |
| I²C bus 1 SDA | GPIO 6 | MPU6050 SDA |
| I²C bus 1 SCL | GPIO 7 | MPU6050 SCL (AD0 to GND) |
| 1-Wire | GPIO 4 | DS18B20 DQ, plus 4.7 kΩ to 3V3 |
| EMG | GPIO 1 (ADC1_CH0) | EMG module output |

- **EMG on ADC1 only.** ADC2 pins read garbage while the radio is on.
- **EMG voltage.** Many EMG modules run from 5 V and can output close to 5 V. Power the module from 3.3 V if it supports that; otherwise add a divider so the output stays below 3.3 V. More than 3.3 V on GPIO 1 can damage the ESP32.
- **No SD card.** The build has none, and the firmware does not use one: each reading goes
  straight to the phone, and one taken while the phone is out of range is lost. GPIO 10–13 are
  free. The app still speaks the card-backed protocol below, so a wearable with a card would
  work without app changes.
- **If an SD module is added back:** The common "Catalex" style module has a 5 V regulator and level shifter and may not work from 3.3 V. Use a 3.3 V-native module, or power it from 5 V (USB VBUS or a boost converter) while keeping its logic at 3.3 V.
- **Two I²C buses.** The MAX30100 library drives the default bus and reads it every
  millisecond from its own task; giving the MPU6050 a second bus means the two never wait
  for each other.
- **Avoid pins** 0, 3, 45 and 46 (strapping) and 19/20 (USB).

## Power

LiPo → TP4056 charger (use the version with the DW01 protection chip) → on/off switch → 3.3 V LDO → ESP32-S3 3V3 pin.

- **LDO:** choose a low-dropout regulator rated **500 mA or more**, such as RT9080 or AP2112K, with the input and output capacitors its datasheet specifies. The radio, the MAX30100's LEDs and SD-card writes can briefly draw 250–300 mA together, so 250 mA parts (MCP1700, HT7333) are marginal. An AMS1117 needs about 4.4 V in and will brown out as the LiPo discharges.
- **Budget:** with BLE advertising or connected plus the sensors, expect tens of milliamps. Measure the real current on the bench before estimating runtime.
- **USB and battery together:** the DevKit's own regulator feeds 3V3 from USB. Do not connect the LiPo path and USB at the same time unless the 3V3 feed is diode-isolated.

## Flashing

1. Install the **esp32 by Espressif** board package, then choose board **ESP32S3 Dev Module**. For an **ESP32-S3-N16R8** set, under Tools:

   | Setting | Value |
   |---|---|
   | Flash Size | 16MB (128Mb) |
   | Flash Mode | QIO 80MHz |
   | PSRAM | OPI PSRAM |
   | Partition Scheme | 16M Flash (3MB APP/9.9MB FATFS) |
   | USB CDC On Boot | Enabled if you plug the Serial Monitor into the board's native **USB** port; Disabled on its **COM/UART** port |

   The N16R8's octal PSRAM uses GPIO 35–37; the wiring above leaves them free.
2. Install these libraries: **MAX30100lib** (OXullo Intersecans), **NimBLE-Arduino 2.x** (h2zero), **OneWire** and **DallasTemperature**.

   The sketch was compiled with these versions; other releases may need small changes:

   | Component | Version |
   |---|---|
   | arduino-cli | 1.5.0 |
   | esp32 by Espressif | 3.3.11 |
   | MAX30100lib | 1.2.1 |
   | NimBLE-Arduino | 2.5.1 |
   | OneWire | 2.3.8 |
   | DallasTemperature | 4.0.6 |

   The equivalent command line:

   ```
   arduino-cli compile --fqbn esp32:esp32:esp32s3:FlashSize=16M,FlashMode=qio,PSRAM=opi,PartitionScheme=app3M_fat9M_16MB,CDCOnBoot=cdc firmware/g_one_wearable
   ```

3. In `g_one_wearable.ino`, set `EMG_MODE` to match your EMG module:

   | Module output | Setting |
   |---|---|
   | Envelope (rectified and smoothed, e.g. MyoWare ENV) | `EMG_MODE_ENVELOPE` |
   | Raw signal centred at mid-rail | `EMG_MODE_RAW_RMS` |

4. Flash, then open Serial Monitor at 115200 baud.
   - A start-up box lists which sensors answered.
   - Every second: every sensor's value, then `APP:` (connected or advertising, whether the
     phone has set the clock, how many readings are waiting for it) and `SENT:`, the exact
     line the app was last sent. A value on the phone can be checked against that line.

### What a card would hold (not used by this build)

| File | What it is |
|---|---|
| `/backlog.txt`, `/backlog.idx`, `/seq.txt`, `/boot.txt` | The readings the phone has not acknowledged yet, and where sending resumes. The app depends on these; do not delete them while readings are waiting |
| `/g-one.csv` | One row a second of everything measured, including the gyroscope, which the link does not carry; `timestamp` is milliseconds since power-on. The bench sketch's log, kept as it was. Nothing in the app reads it |

## Protocol

The full description, which the firmware, the app and the emulator all follow, is
[`docs/WEARABLE_PROTOCOL.md`](WEARABLE_PROTOCOL.md). In short:

**Transport:** BLE service `FFE0`, characteristic `FFE1` (the HM-10 serial profile). The wearable sends lines as notifications; the phone writes commands.

**Wearable → phone,** twice a second while nothing is waiting on the SD card:

```
HR:72,SPO2:97,STEMP:33.9,MOT:1.02,EMG:1234,EMGPK:1810,EMGBITS:12,ST:7F,TS:1726470000000
```

| Key | Meaning | App accepts |
|---|---|---|
| `HR` | Heart rate, bpm. Only with skin contact and a valid reading | 20–300 |
| `SPO2` | SpO₂, %. Same conditions | 50–100 |
| `STEMP` | **Skin** temperature, °C. Never used as body temperature | 20–42 |
| `MOT` | Peak acceleration magnitude since the last line, g | 0–16 |
| `EMG` | Mean EMG level since the last line, 12-bit ADC counts | 0–4095 |
| `EMGPK` | Highest EMG level since the last line | `EMG`–4095 |
| `EMGBITS` | ADC resolution. EMG fields are dropped unless it is 12 | 12 |
| `ST` | Sensor status bitmask, hex (bits below) | 1–4 hex digits |
| `TS` | Epoch milliseconds. Sent only after the clock is set | within 30 days back / 2 min ahead |
| `BUF`, `SEQ`, `LOST` | On records replayed from the SD card: the flag, the record number, and records that cannot be delivered | see the protocol file |
| `PEND`, `LAST` | On the catch-up status line: records waiting, newest record number | see the protocol file |

| `ST` bit | Meaning |
|---|---|
| 0x01 | MAX30100 detected |
| 0x02 | A pulse is being found (held 3 s between beats) |
| 0x04 | DS18B20 detected |
| 0x08 | MPU6050 detected |
| 0x10 | EMG input neither pinned at the rail nor flat |
| 0x20 | SD card mounted |
| 0x40 | Clock set by the phone |

**Phone → wearable:** `T:<epoch ms>` after every connection (and again if the wearable still reports its clock unset), `ACK:<n>` once readings up to record `n` are stored, and `STOP` when the wearer stops monitoring, so the time until the next connection is not recorded and replayed as if they had been out of range.

**How nothing gets lost — with an SD card only.** This build has none and sends live only; the
points below describe the card-backed protocol the app supports:

- **Kept until acknowledged.** While the phone is away, every line is stored on the SD card with a record number. The wearable deletes records only after the phone acknowledges them, and the phone acknowledges only readings that are in its database.
- **Resumes, never restarts.** The last acknowledged record is saved on the card (`/backlog.idx`), so after a dropped link, an app restart or a wearable restart, sending resumes from there.
- **In order.** While anything is waiting, new lines are stored too and sent after the older ones. Live lines resume once the phone has everything.
- **Readings before the clock is set** are stored with the wearable's uptime and converted to real times when the phone sets the clock during the same power-on. Readings from an earlier power-on that never got the time cannot be placed; the phone is told how many (`LOST`) and shows the count.
- **App side:** readings from the past are stored and checked by the same rules, but never raise a notification.

## Keeping sync running

Android and phone makers stop background apps to save battery, and a stopped app cannot talk to the wearable. On the app's **Device** screen:

- **Keep syncing with the screen off:** tap *Allow background use* and confirm Android's dialog.
- **Samsung phones** also need: Settings › Battery › Background usage limits › **Never sleeping apps** › add G-one.
- **Sync automatically when nearby** (Android 12 and later): switch it on and approve Android's "manage this device" dialog. Android then watches for the wearable and starts monitoring when it comes into range, even if G-one is not open.
- **Sync now** connects immediately instead of waiting for the next retry. The app also reconnects as soon as Bluetooth is switched back on.

If Android stops monitoring anyway, it resumes by itself when the system restarts the service, and after the phone restarts, as long as you had started it and not stopped it.

## Calibrating the EMG levels

The default EMG levels (`AnomalyThresholds.emgActiveLevel / emgHighLevel / emgVeryHighLevel`, 1600 / 2800 / 3600) are REFERENCE's Uno levels scaled to 12 bits. **They are uncalibrated**: the same contraction reads differently with electrode placement, skin and amplifier gain.

**In the app (recommended):** start monitoring with the wearable, then Device › Muscle sensor levels › **Calibrate**.

1. Relax the muscle completely for 20 seconds.
2. After a 3-second countdown, clench firmly for 5 seconds.
3. The app places the levels between your relaxed reading (95th percentile) and your clench (median): active at 30 %, high at 60 %, very high at 90 % of the way.
4. Save. Monitoring uses the new levels from its next start. *Use defaults* goes back.

The app refuses levels it cannot trust: too few readings, relaxed and clenched less than 150 counts apart (check the pads), or a clench pinned at the top of the range (lower the module's gain).

**By hand**, e.g. on the bench with Serial Monitor: note the typical relaxed `EMG` and the typical firm-clench `EMG`, then pick levels between them the same way. Keep "very high" below 4090, which the app treats as a detached pad. Confirm that `EMG` goes to 4095 or flat when a pad is lifted, and that the app shows "Check pads" rather than high activity.

## Bench checks before trusting readings

| Check | Expected |
|---|---|
| Serial Monitor start-up box | Every sensor "READY" |
| Lift the finger off the MAX30100 | Within about 5 seconds `HR` and `SPO2` disappear from `SENT:`, `ST` loses bit 0x02, and the app shows "not touching skin" |
| Compare `HR` with a manual pulse count over 60 s at rest | Within a few bpm. If not, adjust placement or the IR LED current (`setIRLedCurrent`) |
| Hold the DS18B20 against the wrist | `STEMP` settles around 32–35 °C. The app labels it skin temperature and raises no fever alert |
| Drop the wearable onto a cushion from 30 cm | `MOT` peak above 2.5 g for one line |
| Leave it still on a table | `MOT` about 1.0 |
| Disconnect the phone for a minute, then reconnect | Live readings resume; Trails shows a gap for that minute (nothing is kept on the board) |
| Power-cycle the wearable while the app monitors | App shows "Reconnecting", then "Connected and receiving"; clock set again |
| Start-up box | `App link : live only, nothing is stored on the board` |
