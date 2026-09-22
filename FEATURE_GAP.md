# Feature gap: REFERENCE (GONE) → TARGET (G-one)

- REFERENCE: `C:\Users\Aditya Pandey\Downloads\GONE` (read-only input)
- TARGET: this repository
- Compared: 2026-09-16, against TARGET `main` including the uncommitted Request B work
- **Status** describes TARGET relative to REFERENCE: `Missing`, `Partial`, `Present but broken`, `Present`, or `Intentionally dropped`.
- **Risk:** L / M / H.
- **Effort:** S ≤ ½ day, M ½–2 days, L > 2 days.
- REFERENCE paths are relative to `app/src/main/java/com/infinity/ai/` unless they name a repo-root file.

## Basis

REFERENCE was built for an Arduino Uno + EMG sensor + HC-05 (Bluetooth Classic SPP, 9600 baud), with an LED and buzzer driven by the firmware. The hardware available for TARGET is different:

| Component | Role |
|---|---|
| ESP32-S3 DevKit / WROOM-1 | Controller. **Bluetooth LE only: no Classic Bluetooth** |
| MAX30102 | Heart rate + SpO₂ |
| DS18B20 | Temperature (worn on skin) |
| MPU6050 | Motion + fall |
| EMG module + electrode pads | Muscle activity |
| microSD module + card | Offline buffering |
| LiPo, TP4056, 3.3 V LDO, switch | Power |
| Perfboard, enclosure | Build |
| HM-10 | BLE UART module (not needed: the ESP32-S3 exposes the same FFE0/FFE1 profile natively) |

Decisions this report assumes:

| Topic | Decision |
|---|---|
| Transport | ESP32-S3 native BLE, HM-10-compatible serial profile (service FFE0, characteristic FFE1 notify + write) |
| DS18B20 | Labelled **skin** temperature; excluded from fever, heat and core-temperature rules |
| EMG alerts | REFERENCE levels scaled to 12-bit (≈1600 / 2800 / 3600), tunable, uncalibrated |
| Clock | App writes the current time to the wearable on connect (`T:<epoch_ms>`); firmware stamps readings from then on |
| Reports | Ported as observations only, plus a user-initiated share-sheet text export |
| Firmware | New ESP32-S3 sketch + hardware docs in this repo |

### Parts constraints

These are from part datasheets and still need confirming on the bench.

- **EMG input:** the ESP32-S3 ADC must never see more than 3.3 V. Power the EMG module at 3.3 V or divide its output, and use an ADC1 pin (GPIO1–10).
- **I²C:** MAX30102 (0x57) and MPU6050 (0x68) can share the bus.
- **DS18B20:** needs a 4.7 kΩ pull-up unless it's on a breakout. The pull-up isn't in the parts list.
- **MPU6050:** the default ±2 g range clips below TARGET's 2.5 g fall-impact threshold. Firmware must use ±8 g and report the peak magnitude per packet.
- **Battery level:** needs a resistor divider to an ADC1 pin (not in the parts list). Until then battery % is unknown.
- **LED / buzzer:** none in the parts list, so REFERENCE's on-wearable alert can't be built as specified.

---

## Sensors

| # | Feature | Where in REFERENCE | Status | What's missing | Hardware/deps | Risk | Est. effort |
|---|---|---|---|---|---|---|---|
| S1 | EMG channel | `bluetooth/VitalsPacketParser.kt`, `health/data/HealthEntities.kt` (`emgRaw`) | Missing | `EMG` key as 12-bit ADC counts (0..4095; anything else rejected as a unit/resolution mismatch), EMG-only lines accepted, sample and persistence fields | EMG module + pads, ESP32-S3 ADC1 | M | M |
| S2 | EMG alert (active / warning / critical, sustained) | `health/anomaly/AnomalyDetectionEngine.kt#checkEmg`, `health/anomaly/AnomalyThresholds.kt` | Missing | New type "Sustained high muscle activity". Thresholds 1600/2800/3600 tunable and **uncalibrated**. Duration-based (≥ 15 s). Values at the rail (≥ 4090) or flat 0 mean an electrode problem, not activity. Severity capped at MODERATE. Template + validator allow-list + tests | S1; on-body calibration | H | M |
| S3 | Heart rate from wearable | `bluetooth/VitalsPacketParser.kt` (`BPM`, synthesized when absent) | Present | TARGET parses `HR` and has HR rules; MAX30102 feeds it via firmware (C1). REFERENCE's `BPM` alias not needed | MAX30102 | L | — |
| S4 | SpO₂ from wearable | `health/data/DerivedVitalsCalculator.kt` (estimated) | Present | TARGET parses `SPO2` with rules; real value from MAX30102 via C1 | MAX30102 | M | — |
| S5 | Skin temperature | `health/data/DerivedVitalsCalculator.kt` (estimated body temperature) | Missing | `STEMP` key (20..42 °C), `skinTempC` field, charted separately, never used by core-temperature rules | DS18B20 + 4.7 kΩ | M | M |
| S6 | Motion + fall detection | Firmware `FALL` flag in `HARDWARE_SETUP.md`, `bluetooth/VitalsPacketParser.kt` | Present | TARGET `MOT` + fall rule. Firmware must use ±8 g and send the peak magnitude per packet | MPU6050 | M | — |
| S7 | Per-sensor status (absent / no contact / lead-off / SD / clock synced) | none | Missing | `ST` bitmask key surfaced on the device screen and live monitor instead of silent blanks | C1 | L | S |
| S8 | Synthetic heart rate | `health/mock/RealisticHeartRateSynthesizer.kt` | Intentionally dropped | Fabricated vital (REFERENCE defect 1) | — | — | — |
| S9 | Derived temperature/SpO₂ | `health/data/DerivedVitalsCalculator.kt` | Intentionally dropped | Fabricated (REFERENCE defect 2); real sensors available | — | — | — |
| S10 | `FALL` = EMG > 600 | `HARDWARE_SETUP.md` sketch, `bluetooth/VitalsPacketParser.kt` | Intentionally dropped | Not a fall (REFERENCE defect 3) | — | — | — |
| S11 | Raw pulse waveform (`PULSE`) | `bluetooth/VitalsPacketParser.kt`, `ui/screens/HealthMonitorScreen.kt#CardiacWaveformCanvas` | Missing | **Not in current scope:** a real PPG trace needs ≥ 20 Hz raw IR over BLE. Revisit after measuring BLE throughput on the board | MAX30102 | M | M |
| S12 | Simulated EMG + skin temperature | `health/mock/MockVitalsSimulator.kt` | Missing | Deterministic EMG (rest, sustained high, electrode off) and skin-temperature tracks in `VitalsScenario` | S1, S5 | L | S |
| S13 | 2 Hz wearable → 5 s pipeline cadence | none (REFERENCE stored every packet) | Missing | Aggregator before `MonitoringPipeline`: HR/SpO₂ median, MOT max, EMG mean + max, skin temperature mean. Raw 2 Hz only feeds the live trace | S1 | M | M |
| S14 | HR/SpO₂/temperature threshold alerts | `health/anomaly/AnomalyDetectionEngine.kt` | Present | TARGET's duration- and trend-aware rules; REFERENCE thresholds not copied | — | — | — |

## Circuits / Actuators

| # | Feature | Where in REFERENCE | Status | What's missing | Hardware/deps | Risk | Est. effort |
|---|---|---|---|---|---|---|---|
| C1 | Wearable firmware | `HARDWARE_SETUP.md` (Uno sketch) | Missing | New ESP32-S3 sketch (not a port): NimBLE FFE0/FFE1, MAX30102 HR/SpO₂, DS18B20, MPU6050 ±8 g peak, EMG on ADC1, 2 Hz lines, `ST` bitmask, time sync, SD log + replay | All parts; esp32 core + libraries (B6) | M | L |
| C2 | On-wearable LED + buzzer alert | `HARDWARE_SETUP.md` sketch (D7/D8) | Missing | **Blocked by hardware:** no LED or buzzer in the parts list. Not in current scope | LED, buzzer | L | S |
| C3 | Wiring and assembly docs | `HARDWARE_SETUP.md` | Missing | `docs/HARDWARE_SETUP.md` for ESP32-S3: pin map, 3.3 V limits, pull-up, ADC1-only EMG, ±8 g, BLE (no pairing PIN), EMG calibration steps | — | L | S |
| C4 | Power path | none (9 V battery) | Missing | Docs for LiPo/TP4056/LDO/switch; battery % unavailable without a divider | Power parts | M | S |

## Connectivity

| # | Feature | Where in REFERENCE | Status | What's missing | Hardware/deps | Risk | Est. effort |
|---|---|---|---|---|---|---|---|
| N1 | Bluetooth Classic SPP (HC-05) | `bluetooth/BluetoothManager.kt` | Intentionally dropped | ESP32-S3 has no Classic Bluetooth; no HC-05 available | — | — | — |
| N2 | BLE GATT link (FFE0/FFE1 notify + write) | `bluetooth/BluetoothManager.kt` (same role) | Missing | `BleUartVitalsSource : VitalsSource`: connect, MTU request, notifications, bytes into existing parser, `SourceStatus` mapping. Errors surfaced: no adapter, BT off, permission denied, location services off (API ≤ 30), device not found, GATT error, service/characteristic missing | C1 | H | L |
| N3 | Automatic reconnect | `bluetooth/BluetoothManager.kt` (fixed 5 s) | Missing | Capped exponential backoff, `Recovering(attempt, reason)`, `parser.reset()` on reconnect | N2 | M | M |
| N4 | Stale-link detection | `bluetooth/BluetoothManager.kt:124` (ineffective) | Missing | Watchdog: no bytes for 10 s → disconnect GATT → reconnect | N2 | M | S |
| N5 | Find and remember the wearable | `bluetooth/BluetoothManager.kt#getPairedDevices`, `ui/screens/DeviceScreen.kt` | Partial | TARGET onboarding lists bonded Classic devices and stores only a name. Needs a BLE scan filtered by FFE0 and saving MAC + name + transport in the existing `devices` table | N2, P2, P3 | M | M |
| N6 | Auto-connect to last device | `health/repository/HealthRepository.kt#autoConnectLastDevice` | Missing | `MonitoringDataSource`: boolean → Simulated / BLE(address); `HealthMonitoringService` builds the BLE source | N2, N5 | M | M |
| N7 | Packet protocol | `bluetooth/VitalsPacketParser.kt` (`EMG,FALL,BPM,PULSE`) | Partial | Framing, fragmentation and malformed-line handling already present. Missing keys `EMG`, `STEMP`, `ST`, `BUF`. `TS` sanity bounds (reject > 2 min ahead of or > 30 days behind arrival) | — | M | S |
| N8 | SD backlog replay after reconnect | none | Missing | Firmware replays buffered lines (`BUF:1`) before live data. App rejects backlog lines without `TS`, skips duplicates by timestamp, stores events from replayed data as history without push notifications | microSD, N9 | H | M |
| N9 | Time sync, app → wearable | none | Missing | App writes `T:<epoch_ms>\n` to FFE1 on connect; firmware reports "synced" in `ST` | N2, C1 | M | S |

## UI/UX

| # | Feature | Where in REFERENCE | Status | What's missing | Hardware/deps | Risk | Est. effort |
|---|---|---|---|---|---|---|---|
| U1 | Device screen | `ui/screens/DeviceScreen.kt` | Missing | Adapter/permission state with requests, scan list, connect/disconnect/forget, `SourceStatus`, last packet time, parser accepted/rejected counts, per-sensor status, clock synced, protocol card | N2, N5 | M | M |
| U2 | Live EMG card + trace with level lines | `ui/screens/HealthMonitorScreen.kt#EmgWaveformCanvas` | Missing | Real samples only; "no EMG sensor" / "check electrodes" otherwise | S1, S7 | L | M |
| U3 | Skin temperature card + chart | none (REFERENCE showed estimated temperature) | Missing | Labelled "Skin temp", separate from core temperature | S5 | L | S |
| U4 | Cardiac waveform / dual oscilloscope | `ui/screens/HealthMonitorScreen.kt#DualHealthOscilloscope` | Missing | Not in current scope (S11). REFERENCE version fabricated (defect 4) | S11 | — | — |
| U5 | No-signal / waiting / connection badge | `ui/screens/HealthMonitorScreen.kt` | Partial | Wire TARGET screens to live `SourceStatus` and error reasons | N2 | L | S |
| U6 | Session controls | `ui/screens/HealthMonitorScreen.kt#SessionControls` | Missing | Start/stop/cancel, elapsed time, sample count, generating state | D2 | M | M |
| U7 | Report view | `ui/screens/ReportViewScreen.kt` | Missing | Duration, 5 representative points, min/avg/max of real channels, events during session, disconnect gaps, provenance; no recommendations | D3 | M | M |
| U8 | Report vault | `ui/screens/ReportVaultScreen.kt` | Missing | List, open, delete with confirmation, share export. No e-mail chip or debug panel | D3 | L | S |
| U9 | Alerts list with acknowledge | `ui/screens/HealthAlertsScreen.kt` | Present | `TrailsScreen` alerts tab + `InAppAlertStack` + notifications | — | — | — |
| U10 | Health history | `ui/screens/HealthHistoryScreen.kt` | Partial | `TrailsScreen` trends exist; EMG and skin-temperature series arrive with S1/S5 | S1, S5 | L | S |
| U11 | Simulator toggle + scenario picker | `ui/screens/SettingsScreen.kt#SimulatorSection` | Present | Settings toggle + deterministic scenarios | — | — | — |
| U12 | Emergency auto SMS/call/location toggles | `ui/screens/SettingsScreen.kt` | Intentionally dropped | TARGET keeps user-confirmed `EmergencyActions` | — | — | — |
| U13 | Cloud e-mail sharing settings | `ui/screens/SettingsScreen.kt` | Intentionally dropped | Replaced by D4 | — | — | — |
| U14 | Orb reflecting health + AI state | `ui/navigation/AppNavigation.kt` | Present | `ui/components/AiBodyOrb.kt` (not compared line by line) | — | — | — |
| U15 | `GoneLogo` header | `ui/screens/DashboardScreen.kt:66` | Missing | Cosmetic; not in current scope | — | L | S |

## Data & Persistence

| # | Feature | Where in REFERENCE | Status | What's missing | Hardware/deps | Risk | Est. effort |
|---|---|---|---|---|---|---|---|
| D1 | New reading columns | `health/data/HealthEntities.kt` (`emgRaw`) | Missing | Additive migration 2→3: `emgMean`, `emgMax`, `skinTempC` on `vitals_readings`; exported schema; migration SQL test | S1, S5 | M | S |
| D2 | Sessions | `health/data/HealthEntities.kt#HealthSession`, `health/repository/HealthRepository.kt` | Missing | `health_sessions` table (same v3 migration); active session survives process death | — | M | M |
| D3 | Session report | `health/repository/SessionManager.kt`, `health/repository/LocalReportAnalyzer.kt`, `HealthReportEntity` | Missing | One `session_reports` table. Deterministic builder, real channels only, status = highest TARGET severity in session, observational wording. Optional validated LLM summary | D2 | H | L |
| D4 | Report export | `health/sharing/ReportSharingService.kt` (Resend e-mail) | Missing | Replacement: user-initiated plain-text share sheet; no network, no new permission | D3 | L | S |
| D5 | Device record | `devices` table, `getPrimaryDevice` | Partial | TARGET table exists but is unused. Write MAC/name/transport/lastSeenAt; battery and firmware version stay null | N5 | L | S |
| D6 | Duplicate report tables | `health/data/HealthEntities.kt` (`session_reports` + `health_reports`) | Intentionally dropped | One table (D3) | — | — | — |
| D7 | Separate DB with destructive migration | `health/data/HealthDatabase.kt` | Intentionally dropped | REFERENCE defect 7 | — | — | — |
| D8 | E-mail status + WorkManager retry | `health/sharing/ReportSharingService.kt`, `EmailRetryWorker` | Intentionally dropped | Network feature | — | — | — |
| D9 | Wipe all readings on simulator/connect | `health/repository/HealthRepository.kt#startSimulator/stopSimulator/connectDevice` | Intentionally dropped | REFERENCE defect 6 | — | — | — |
| D10 | Emergency/sharing DataStore prefs | `data/EmergencyAlertPreference.kt`, `data/ReportSharingPreference.kt` | Intentionally dropped | Features dropped | — | — | — |
| D11 | Raw-reading retention | `health/repository/HealthRepository.kt#pruneOldReadings` (30 days, never called) | Present | TARGET `HealthMonitoringService` already trims readings older than 7 days every 6 hours (`RETENTION_MILLIS`). Corrected after Phase 2: first listed as Missing | — | — | — |

## Permissions / Platform

| # | Feature | Where in REFERENCE | Status | What's missing | Hardware/deps | Risk | Est. effort |
|---|---|---|---|---|---|---|---|
| P1 | BLUETOOTH_CONNECT at connect time | `ui/screens/DeviceScreen.kt` | Partial | Declared; requested in onboarding/service. Add request + permanently-denied path on the device screen | N2 | L | S |
| P2 | BLUETOOTH_SCAN (API 31+) | `AndroidManifest.xml` (declared, unused) | Partial | Declared `neverForLocation`; runtime request missing | N5 | L | S |
| P3 | ACCESS_FINE_LOCATION, maxSdkVersion 30 | `AndroidManifest.xml` (declared for SMS location) | Missing | Needed for BLE scans on API 24–30 (minSdk 24), together with location services on | N5 | M | S |
| P4 | Foreground service holding the link | none (app singleton) | Present | `HealthMonitoringService` (connectedDevice) | — | — | — |
| P5 | Health notifications | none | Present | `HealthNotifications` | — | — | — |
| P6 | SEND_SMS / CALL_PHONE | `health/sharing/EmergencyAlertManager.kt` | Present (2026-09-19, at the owner's request) | Automatic SOS, opt-in, with a cancellable countdown: `health/sos/` | — | — | — |
| P7 | Location for emergency SMS + play-services-location | `health/sharing/EmergencyAlertManager.kt` | Intentionally dropped | Feature dropped (distinct from P3) | — | — | — |
| P8 | INTERNET / ACCESS_NETWORK_STATE | `AndroidManifest.xml` | Intentionally dropped | TARGET is offline | — | — | — |
| P9 | RECEIVE_BOOT_COMPLETED | `AndroidManifest.xml` | Intentionally dropped | No receiver in REFERENCE | — | — | — |

## Build / Infra

| # | Feature | Where in REFERENCE | Status | What's missing | Hardware/deps | Risk | Est. effort |
|---|---|---|---|---|---|---|---|
| B1 | work-runtime-ktx 2.9.1 | `gradle/libs.versions.toml` | Intentionally dropped | E-mail retry only | — | — | — |
| B2 | okhttp 4.12.0 | `gradle/libs.versions.toml` | Intentionally dropped | Resend only | — | — | — |
| B3 | play-services-location 21.3.0 | `gradle/libs.versions.toml` | Intentionally dropped | P7 | — | — | — |
| B4 | `buildConfig` + `RESEND_API_KEY` | `app/build.gradle.kts` | Intentionally dropped | Secret in APK (defect 9) | — | — | — |
| B5 | AGP / Kotlin / Room / Compose BOM | `gradle/libs.versions.toml` | Present | Identical versions; BLE uses platform APIs, no new dependencies | — | — | — |
| B6 | Firmware toolchain + compile check | none | Missing | `arduino-cli` is installed but its data directory is broken. Needs `esp32:esp32` core + NimBLE-Arduino, SparkFun MAX3010x, OneWire, DallasTemperature (network downloads). MPU6050 via raw I²C | Network | M | S |
| B7 | Tests for the hardware path | `ExampleUnitTest.kt` only | Missing | Parser keys/bounds, aggregator, BLE source via fake GATT seam, backlog/dedup, EMG rule, skin-temperature isolation, migration SQL, report builder | — | L | M |

---

## TARGET-only features (not in REFERENCE)

- **Detection:** duration- and trend-aware 12-rule engine, risk scores, heat index, baseline deviation, escalation that bypasses cooldown.
- **Explanations:** deterministic template written before alerting; LLM rewrite validated by `HealthPromptBuilder.validate`.
- **Service and alerts:** `MonitoringPipeline`, connectedDevice foreground service, health notifications with deep links, MakeMeHealthy reminders.
- **Channels:** environment channels (ambient temperature, humidity, AQI) and `TS` timestamp support.
- **Parser:** fragment-safe packet parser with accepted/rejected stats.
- **Simulation:** deterministic 9-scenario simulator, `MockHealthDataSeeder` demo data.
- **Emergency:** user-confirmed `EmergencyActions` (dialer / SMS compose).
- **Onboarding:** profile + `UserProfile`.
- **AI:** `ModelLeaseTracker`, per-request native cancellation, prompt token budgets.
- **UI:** newer Chat, Tools, Settings and Circle UI; `TrailsScreen`.
- **Persistence and tests:** single `GoneDatabase` with exported schemas and hand-written migrations; JVM unit tests for the health engine and AI plumbing.

## Deliberate divergences (porting as written would be wrong)

1. **No synthetic, derived or offset vitals.** A missing reading shows "—" with the reason from `ST`.
2. **`FALL` isn't a fall.** It's `EMG > 600` in REFERENCE firmware. Falls come from the MPU6050 peak magnitude.
3. **EMG severity capped at MODERATE, thresholds uncalibrated.** Red stays reserved for CRITICAL, and REFERENCE's "critical spasm" wording is a diagnosis.
4. **Skin temperature never feeds fever, heat or core-load rules.**
5. **No automatic location, no cloud e-mail, no diet/exercise/lifestyle advice.** Automatic SMS and call were added back on 2026-09-19 at the owner's request, as an opt-in SOS with a cancellable countdown, driven only by CRITICAL alerts (plus very hard impacts and calibrated EMG) from live wearable readings — not by REFERENCE's derived "Concerning" status.
6. **No destructive migration, second database, wipe-on-connect, `Random` simulator or global `engine.stop()`.**
7. **BLE instead of SPP.** The SPP transport doesn't fit the available hardware and isn't ported unused.
8. **Replayed SD backlog never raises push notifications.** Past events appear in Trails as recorded while disconnected.

## Defects found in REFERENCE

1. **Fabricated heart rate.** `bluetooth/VitalsPacketParser.kt` substitutes `RealisticHeartRateSynthesizer` output whenever BPM is missing or out of range. The documented firmware never sends BPM, so every displayed heart rate is synthetic and labelled "LIVE SENSOR".
2. **Fabricated temperature and SpO₂.** `health/data/DerivedVitalsCalculator.kt` estimates both from EMG + BPM. They drive report status, and "Concerning" triggers automatic SMS/calls.
3. **Mislabelled fall flag.** Firmware `FALL` = `EMG > 600`; the app shows "Fall Detected!" and counts fall events.
4. **Fake cardiac waveform.** Drawn from BPM (`520 + (bpm-72)*4.5`) when there's no pulse data.
5. **Read timeout doesn't work.** `withTimeoutOrNull` around blocking `readLine()` can't interrupt it (`bluetooth/BluetoothManager.kt:124`).
6. **Silent data loss.** `startSimulator`/`stopSimulator` delete all readings, including real ones; `connectDevice` does too when no session is active.
7. **Health history lost on upgrade.** `fallbackToDestructiveMigration` on the health database.
8. **Broken simulator scenarios.** LOW_SPO2 and FEVER emit no SpO₂/temperature values.
9. **Leaked secret.** The Resend API key is compiled into `BuildConfig` and can be extracted from the APK.
10. **Cancels other AI work.** `AIRepository.generateSessionReport` calls a global `engine.stop()`, cancelling unrelated generations.
11. **Dead code and wrong docs.**
    - `SessionReport.aiAnalysis` is never written.
    - `pruneOldReadings` is never called.
    - RECEIVE_BOOT_COMPLETED has no receiver.
    - Location permission is never requested.
    - A debug panel ships in the report vault.
    - The README names nonexistent `BtState.kt` / `LlamaCallback.kt` and describes a protocol (BPM, PULSE, accelerometer, 10 Hz) the sketch doesn't implement.
12. **No health notifications.** The only notification channel belongs to the overlay service.

## Cannot be verified without the physical hardware

JVM tests with fakes and the simulator cover the software paths. The emulator has no BLE. These need a phone and the assembled wearable:

- BLE connect, reconnect and stale-link recovery.
- Time sync and SD backlog replay across a real disconnect.
- MAX30102 HR/SpO₂ plausibility and contact detection.
- DS18B20 skin readings.
- MPU6050 impact peaks against the 2.5 g threshold.
- EMG levels 1600/2800/3600 (calibration).
- Power path and battery runtime.
- Firmware compile check: pending the B6 toolchain downloads. Flashing isn't covered.

## Open questions

- **EMG module model.** An envelope output is averaged in firmware; a raw bipolar output needs RMS.
- **Firmware toolchain.** Approval to download the esp32 core and libraries for the compile check.
- **Commits.** None: changes stay in the working tree, as you asked.
- **Optional features:**
  - DevKit onboard RGB LED as a status light (C2).
  - Pulse waveform (S11).

---

## Port outcome (Phase 3)

What happened to each `Missing` or `Partial` row. Verification status is in `VERIFICATION.md`; **nothing here has run on the wearable.**

| Rows | Outcome | Where |
|---|---|---|
| S1, S5, S7, N7 | Implemented: `EMG`, `EMGPK`, `EMGBITS`, `STEMP`, `ST` and `BUF` keys; `TS` trust bounds; EMG-only and status-only lines | `health/source/SensorPacketParser.kt`, `WearableStatus.kt` |
| S2 | Implemented: `emg.sustainedHigh`, levels 1600/2800/3600 tunable in `AnomalyThresholds` and uncalibrated. At rest only, rail values ignored, capped at MODERATE | `health/detect/AnomalyRules.kt` |
| S12 | Implemented: EMG and skin temperature on every scenario, plus `SUSTAINED_MUSCLE_ACTIVITY` | `health/source/VitalsScenario.kt` |
| S13 | Implemented: 5-second buckets; median HR/SpO₂, peak motion, mean + peak EMG | `health/source/SampleAggregator.kt` |
| S11, U4 | Not done: out of scope as stated | — |
| C1, C3, C4 | Implemented: sketch and docs. `EMG_MODE` switch covers envelope and raw modules, so the EMG model question no longer blocks | `firmware/g_one_wearable/`, `docs/HARDWARE_SETUP.md` |
| C2 | Not done: no LED or buzzer in the parts list | — |
| N2, N3, N4, N9 | Implemented: FFE0/FFE1 link, capped backoff, 10 s silent-link watchdog, `T:` clock sync on every connect | `health/source/ble/` |
| N5, N6, D5 | Implemented: BLE scan picker (onboarding and Device screen), saved wearable, auto-connect when monitoring starts, `devices` row updated on connect | `WearableScanner.kt`, `MonitoringDataSource.kt`, `HealthMonitoringService.kt` |
| N8 | Implemented: replayed or over-10-minute-old readings stored, checked on their own timeline, de-duplicated, never notified | `MonitoringPipeline.onHistoricalSample` |
| U1, U5 | Implemented: Device screen; link state, sensor problems and reconnect reasons on the Monitor | `health/ui/DeviceScreen.kt`, `LiveMonitorScreen.kt` |
| U2, U3, U10 | Implemented: EMG card + raw live trace, skin temperature card, EMG and skin trends in Trails | `LiveMonitorScreen.kt`, `TrailsScreen.kt` |
| U6, U7, U8, D2, D3, D4 | Implemented: sessions, deterministic report, report view and list, share-sheet text, delete; optional validated model summary | `health/session/`, `health/data/SessionRepository.kt`, `health/ui/ReportScreens.kt` |
| D1 | Implemented: schema v3, additive migration 2→3 | `health/data/GoneMigrations.kt`, `app/schemas/.../3.json` |
| P1, P2, P3 | Implemented: permission handling in the picker; `ACCESS_FINE/COARSE_LOCATION` with `maxSdkVersion 30` | `WearablePicker.kt`, `AndroidManifest.xml` |
| B6 | Implemented: compiled with arduino-cli 1.5.0 (esp32 3.3.11) in both EMG modes; no warnings from the sketch. Toolchain installed in an isolated scratch directory, not in `%LOCALAPPDATA%\Arduino15` | `firmware/g_one_wearable/` |
| B7 | Implemented: 90 new unit tests (246 → 336) | `app/src/test/...` |
| U15 | Not done: cosmetic, out of scope | — |

**Found and fixed during the port, outside the table.** The Trails trend cards showed invented values for channels with no readings: 72 bpm, "36.6 °C · Optimal core body temperature", 97 %. The wearable has no core temperature sensor, so it would have shown a fabricated body temperature all the time. Absent channels now get no card.
