# Verification

What was run for the "what's left" work (session PDFs, the tools, personalised answers, voice chat and TalkBack, acknowledged wearable sync, and the bug and design pass), what it showed, and what still needs the phone and the wearable.

- **Date:** 2026-09-17
- **Branch:** `main`. Nothing is committed: all changes are in the working tree, as requested.
- **Machine:** Windows 11, JDK from `JAVA_HOME`, Gradle wrapper, arduino-cli 1.5.0, Python 3.11.9.

**What has run on the phone:** the instrumented tests and a first pass of hands-on checks, on the Samsung SM-S721B (Android 16). **Not yet:** the hands-on phone checks, the checks with the PC acting as the wearable, and anything on the wearable itself, which was not available. Those are listed under [Not verified](#not-verified).

The phone was not connected during most of the work, so the plan's Phase 0 step, reproducing each problem on the phone before fixing it, was not done. The problems were found by reading the code; the table in the plan cites the lines.

**How the build got onto the phone without losing its data.** The phone had the release build installed. A debug build is signed with a different key, and Android only installs it after an uninstall, which erases the app's data. So the debug and test builds were signed with the release key for these runs (`android.injected.signing.*`, read from `keystore.properties`, never printed) and installed over the release build with `adb install -r`. Readings, chats, reports and the extracted model stayed.

---

## Commands run and results

| Check | Command | Before this work | Now |
|---|---|---|---|
| Unit tests | `gradlew testDebugUnitTest` | 336 tests, 32 classes | **526 tests, 56 classes, 0 failures** |
| Debug and release builds | `gradlew assembleDebug assembleRelease` | Both build | **Both build**; release is signed |
| Instrumented tests | `gradlew connectedDebugAndroidTest` on the SM-S721B | Never run | **19 of 19 pass**, in two consecutive runs: 8 migration tests (1→2, 2→3, 1→3, 3→4, 1→4) and 11 TalkBack label checks |
| Kotlin warnings | full recompile (`--rerun-tasks`) of app, unit-test and instrumented-test code | 15 | **0** |
| Android lint | `gradlew lintDebug` | Fails: 3 errors, 55 warnings | **Passes: 0 errors, 45 warnings**, none new |
| Room schema | KSP export | `3.json` | **`4.json`** (adds `library_entries.useInAi`); checked by `GoneMigrationsTest` |
| Firmware | `arduino-cli compile` for the N16R8 (`FlashSize=16M,PSRAM=opi,PartitionScheme=app3M_fat9M_16MB,CDCOnBoot=cdc`), both EMG modes | Both EMG modes build | **Both build, no warnings from the sketch.** As flashed (envelope EMG): 672,243 bytes flash (21 % of the 3 MB app partition), RAM 36.1 KB (11 %); the raw-RMS setting also builds. This is the owner's working sensor sketch with the Bluetooth link added (below) |
| Sync protocol | `python tools/wearable-emulator/emulate.py --selftest` | — | **10 of 10 scenarios pass** (below) |

### Lint

Compared issue by issue with the lint report captured before this work:

| Change | Detail |
|---|---|
| −3 errors | `MissingPermission` in `HealthNotifications.kt` and `MakeMeHealthyReminderManager.kt`; `PermissionImpliesUnsupportedChromeOsHardware` for the camera |
| −10 warnings | `UseKtx` in five files, `RedundantLabel` on the main activity |
| New | None. Lint also found two `NewApi` errors and three warnings in code written during this work; all were fixed before the final run |

The 45 warnings left were all there before this work. 14 concern dependency versions in `gradle/libs.versions.toml`; the rest are `UseOfNonLambdaOffsetOverload` (8), `UnusedResources` (7), `ModifierParameter` (5), and one to three each of `ClickableViewAccessibility`, `ChromeOsAbiSupport`, `DiscouragedApi`, `DrawAllocation`, `IconDuplicatesConfig`, `IconLocation`, `ImplicitSamInstance`, `ModifierFactoryExtensionFunction` and `ViewConstructor`.

### By hand on the phone

The app was reinstalled (the person had uninstalled it, so its data was gone; Android restored the
settings from an older cloud backup made before the new no-backup rules). Checked, with screenshots:

| Check | Result |
|---|---|
| Settings: profile row, edit button, version | Pass: name, age and contact shown together with an Edit button; Version reads the build's version |
| Tools screen wording | Pass: "On-device AI and health tools" |
| Vault: search for `a:b-c"` | Pass: no crash, no database error, the app kept running |
| Quiz: type 346 characters, generate | Pass: model loaded, prompt read at 28.9 tokens/s, text streamed in |
| Quiz: playable cards | Pass: 5 questions, tapping an answer reveals it with a reason, score and "Try again" update |
| Quiz: saved by itself | Pass: appeared in the Vault, opens again as a playable quiz with Copy, Share and Ask G-one |
| Vault: delete | Pass: entry removed with an Undo snackbar |
| Vault: undo | Not proven: the snackbar had timed out before the tap landed. To repeat |

Nine problems found this way, all fixed and rebuilt (493 unit tests, 0 lint errors):

| Found on the phone | Fix |
|---|---|
| Settings showed the real version and, below it, a hard-coded "v1.0.0" | Both read the build's version |
| Status bar icons were white on light screens: the bars followed the phone's dark mode, not the app's theme | The bars follow the app's theme |
| The keyboard covered the quiz text box, and the Vault's "No results found" message | Those screens keep their content above the keyboard |
| The Vault called itself "Library" in its own header and "Vault" everywhere else | "Vault" throughout |
| The delete snackbar sat under the navigation bar | It sits above it |
| "Start over" wrapped onto two lines beside a long title | The title gives way; the label stays on one line |
| Typed quiz text was labelled "Extracted text" | "Source text" |
| The wearable card on Home cut its status off at one line | Up to two lines |
| Onboarding, chat and search fields had no spoken label (found by the label test) | Each has a label and reports its error |

### The sync self-test

`emulate.py --selftest` runs the wearable's sync logic (a line-for-line mirror of the firmware's) against a simulated phone that follows `BacklogAckTracker` and the monitoring service, on a virtual clock, with no Bluetooth:

| Scenario | Result |
|---|---|
| S1 Live lines with the clock set | Pass: 120 live readings in 60 s |
| S2 Out of range for 5 minutes | Pass: all 600 stored readings arrived; live mode resumed |
| S3 App killed mid-replay, reopened | Pass: resumed from the last acknowledged record; nothing stored twice |
| S4 Wearable restarted away from the phone, before the clock was set | Pass: readings from that power-on placed in time, none lost |
| S5 A power-on that never got the time, then another restart | Pass: its 60 readings reported as `LOST`, not dropped silently |
| S6 10 % of notifications and 20 % of acknowledgements failing | Pass: caught up; nothing stored twice |
| S7 Monitoring stopped in the app for 2 minutes, started again | Pass: nothing recorded during the stop; out of range afterwards is recorded and delivered again |

It is a check of the protocol design, not of the C++ running on an ESP32 or of Android's Bluetooth stack.
For that, `tools/wearable-check/check_wearable.py` plays the phone against the real wearable over
Bluetooth (see *On the bench*).

### The owner's working sketch, and the Bluetooth link added to it

The owner got every sensor working on the bench with their own sketch, kept unchanged in
`firmware/g_one_doctor/g_one_doctor.ino`. It showed two things the earlier firmware had wrong: the
pulse sensor is a **MAX30100** (the board is printed "MAX30100/30102"; the SparkFun library used
before only drives a MAX30102, which is why it reported the sensor missing), and the **MPU6050 is on
its own I²C bus, GPIO 6 and 7**. `firmware/g_one_wearable/g_one_wearable.ino` is now that sketch,
its sensor functions as they were, with the Bluetooth protocol added. It had no Bluetooth at all;
comparing it with what the app reads found these, each fixed and marked `CHANGED FOR THE APP` in
the sketch:

| In the bench sketch | Effect in the app | Fix |
|---|---|---|
| No Bluetooth, no line format, no clock, no acknowledgements | Nothing to connect to | The protocol from `docs/WEARABLE_PROTOCOL.md`, unchanged |
| Heart rate is a float (`72.4`) | The parser reads `HR` as a whole number, so every heart rate would be dropped | Rounded; left out while the library reports 0 |
| Accelerometer at ±2 g | The fall rule needs a 2.5 g impact, which ±2 g clips below: no fall could ever be detected | ±8 g |
| A DS18B20 or MPU6050 that stops answering keeps its last value | A dead sensor shown as a live reading | Left out after 5 s without a good reading |
| Prints block when the USB port is plugged in but not open | The loop can stop for seconds, and the app drops a link that is silent for 10 s | A 4 KB serial buffer and no waiting |
| EMG volts computed as ADC/4095 × 3.3 V | The CSV's voltage column reads high (full scale is about 3.1 V at this attenuation) | The chip's own calibration (`analogReadMilliVolts`) |

The MAX30100 library has no "finger present" signal, so the skin-contact bit means a pulse is being
found, held for 3 s between beats.

**Stop, in both directions.** Stopping monitoring in the app only closed the link, which the
wearable cannot tell from walking out of range: it recorded the whole stop to its card and replayed
it at the next connection. The app now writes `STOP` when monitoring is stopped (never when the link
drops) and waits 300 ms before closing; the wearable records nothing until the app connects again.
Four new `BleUartVitalsSourceTest` cases cover it, and self-test scenario S7. One existing test,
*cancelling the collector closes the link and reports stopped*, now waits out those 300 ms before
checking.

### On the real board and phone

`g_one_wearable` was flashed to the owner's ESP32-S3-N16R8 over USB (COM13) and run against the
app on the Samsung SM-S721B, with the board's serial output logged on the PC throughout.

| Check | Result |
|---|---|
| Start-up | MAX30100, MPU6050, DS18B20 and EMG **READY**; microSD **failed to start** (see below) |
| Accelerometer at ±8 g, board at rest | 0.05, −0.63, 0.81 g: **1.03 g** in total |
| Phone finds the board | Listed first: "G-one Wearable · G-one serial service · strong signal" |
| Connect | Streaming within seconds of Start; MTU 185; the board reported its clock set by the phone |
| Stored readings against what the board sent | 28 five-second readings: skin temperature, motion peak, EMG mean and peak. **112 values checked against the board's own `SENT:` lines, 0 disagree** |
| Stop | About 2.5 s after Stop the board reported `not connected … recording PAUSED by the app` |
| Fall rule | A 2.93 g jolt while the board was handled raised "Fall Detected" (MODERATE). At the bench sketch's ±2 g this reading could not exist |
| After the board was reflashed twice | The app reconnected by itself each time |
| Board restarts during monitoring (brown-outs, below) | The phone showed "Reconnecting", retried with back-off, and was streaming again within seconds of each boot, with the clock set again |
| Sensor problems reported by the board | A boot that found no DS18B20 or MPU6050 showed "Skin temperature sensor not detected" and "Motion sensor not detected" on the Live Monitor |
| MAX30100 library stuck at 0.6 BPM after a restart | The board sent no `HR` and reported no skin contact: the new check held |
| Session, report and PDF | A 19-minute session (07:48–08:07, 213 readings) ended with a report and a two-page PDF: observations, five points, skin temperature, motion and EMG charts, the alert, and the on-device model's summary. It says plainly that no heart-rate or oxygen readings were received, and records the one gap (85 s) while the board was down |
| Report figures against the stored readings | Skin 29.8–32.5 °C (stored 29.81–32.54), hardest movement 9.5 g (9.47), EMG average 422 and peak 4095, one gap, one alert: all match. The database holds one more reading than the report's 213, stamped 08:07:45: the last five-second reading, stored seconds after the report was built |
| Fall rule, second time | A 9.47 g jolt at 08:05 raised "Possible fall", listed in the report |

Found on the hardware and fixed:

| Found | Fix |
|---|---|
| Home's Temp tile and trend bar showed core temperature only. The wearable has only a skin sensor, so they always read "--" while it streamed 29 °C | They show skin temperature when there is no core reading, labelled "Skin" and judged against skin's range (Cool / Typical / Warm) as the Live Monitor does; never a fever reading. Checked on the phone: "31.9° Skin Typical" |
| The MAX30100 library kept reporting 0.3 BPM for two minutes with nothing on the sensor | Heart rate is sent only when backed by the library's recent beats (`docs/WEARABLE_PROTOCOL.md`); flashed, and the sketch compiles with no warnings |

Not yet checked on the hardware, and why:

- **Heart rate and SpO₂ end to end:** with a finger on the sensor the MAX30100 library found no beats at all (heart rate 0 for minutes), so there was nothing to send. The sensor initialises, but whether its LEDs are lit, and whether it has ever produced a pulse on this board, is not established. Tapping anywhere on Home's monitoring card is Start/Stop (by design, one large target), which ended one attempt early.
- **The model's summary in the PDF** added a clause the observations do not support: "with no further readings after that" after the one gap. The check it passes forbids new numbers, advice and diagnoses, not a wrong statement in words. The deterministic observations above it are correct.
- **Out of range and catch-up:** needs the SD card, which failed to start. It runs the bench sketch's own SD code at the same point in start-up, so the card, its 5 V supply or its wiring is the first suspect.
- **The PC as the phone** (`check_wearable.py`): the PC's Bluetooth was switched off.
- **Unstable power on the board:** at 06:50:03 it logged `Brownout detector was triggered` and restarted, then disappeared from USB. Back on USB at 07:25 it restarted twice more within two minutes (the phone lost the link each time with a supervision timeout, status 8, and reconnected by itself after the first), failed to start the MAX30100 on one of those boots and found it on the next, then stopped advertising and left USB altogether. The 3.3 V rail is dipping or the USB connection is intermittent: the cable, the USB-C socket, the USB port, or the combined current of the sensors and the MAX30100's 50 mA LED.

### Live only: the SD card taken out

At the owner's request the SD card was removed from the wearable and from the firmware. The board
now keeps nothing: each reading goes to the phone as it is made, and one taken while the phone is
out of range is lost (the app's record shows a gap). The card start-up, the CSV log and the
stored-record catch-up were deleted from `g_one_wearable.ino` (2,141 lines to 1,429); `ACK` and `STOP`
from the app are ignored. The app is unchanged apart from the Device screen, which now says readings
stay on the wearable only when the wearable has reported a working card. The app still implements
the whole card-backed protocol, so its tests and the emulator self-test are unaffected.

| Check | Result |
|---|---|
| Build | 609,731 bytes flash (19 %), 35.5 KB RAM; no warnings from the sketch. App `compileDebugKotlin` passes |
| Flashed, restarted | MAX30100, MPU6050, DS18B20 and EMG READY; "App link : live only, nothing is stored on the board"; advertising |

Whether the board was storing anything before, and whether that made it hot: it was not. Every
write to the card first checks that the card started, and it failed to start on every boot, so
nothing was written; nothing accumulates in memory either. The heat points to a wiring fault.

### Why the pulse sensor comes and goes

The owner saw the MAX30100's light go on and off. Two causes, both measured:

- **The board restarts.** The phone's Bluetooth log shows the link lost with status 8 (the board
  stopped answering) at 22:28:05, 22:32:13 and 22:32:23, each followed by a reconnect 2–3 s later.
  Every restart switches the sensor off and on.
- **The sensor's I²C bus idles at about 1.8 V.** The firmware now measures SDA and SCL before
  starting the sensor: 1,793 mV and 1,789 mV, on three boots in a row. The module pulls its bus up
  to its own 1.8 V regulator; the ESP32-S3 needs about 2.5 V to read a 1, so the sensor answered on
  some boots and not others, and its samples are unreliable when it does, which fits no heart rate
  ever getting through. (This did not show while the MPU6050 shared the bus: the GY-521's 3.3 V
  pull-ups held it up.) Fix, in hardware: 2.2 kΩ from SDA to 3V3 and from SCL to 3V3. The
  firmware prints `TOO LOW` at start-up until then, asks a sensor that does not answer three
  times, and then again every 5 s without a restart.

Until the resistors are fitted, the firmware copes as well as it can, and cuts the current spikes
behind the restarts: the sensor's bus runs at 100 kHz instead of 400 kHz once it has answered
(four times as long for each weakly pulled-up line to rise), the CPU at 80 MHz instead of 240, and
Bluetooth transmits at 3 dBm instead of 9. Flashed (611,107 bytes, no warnings) and watched for
70 s: one boot, no brown-out, MAX30100, MPU6050, DS18B20 and EMG all READY, bus at 1,827 / 1,856 mV
with the warning printed as designed.

### Automatic SOS

At the owner's request the app now texts, then calls, the emergency contact on its own when the
wearable reports an emergency — reversing the earlier decision that it never would. Code in
`app/src/main/java/com/gone/ai/health/sos/`.

- **What sends:** every CRITICAL alert (blood oxygen below 90 %, heart rate 150+ or 37 or less, an
  impact then no movement, the rest of the "Get help now" tier); an impact of 5 g or more even with
  movement after; sustained very high muscle activity only once EMG is calibrated. Only live
  readings from the wearable: never simulated ones, never readings replayed from the past.
- **How:** a countdown (30 s by default; 0, 15, 30 or 60 s in Settings) with an alarm-sound
  notification and an in-app card, each with "I'm OK" to stop it. If nobody taps it, it sends. Alerts
  raised together go in one text. The text goes first; the call five seconds later (optional).
  The wearer stopping monitoring counts as "I'm OK"; Android ending monitoring mid-countdown sends
  the SOS at once rather than lose it.
- **Off until switched on** in Settings › Emergency SOS, where Android asks once for SEND_SMS and
  CALL_PHONE. The number is the profile's emergency contact, now checked to be a phone number.
  "Send a test text" sends a message marked TEST, no call. The last SOS or test and its result
  show there, and as a notification.

| Check | Result |
|---|---|
| New unit tests (24) | Pass: which alerts send (and which do not), the message, the numbers, the countdown, "I'm OK", alerts gathered into one text, switched off mid-countdown, text then call, a failed text still calling, sending at once when Android ends monitoring |
| Unit tests | 526, 0 failures |
| Lint | 0 errors, 45 warnings |
| On the phone | **Not yet.** A real text and a real call to the contact need the phone connected, and the owner's go-ahead to send them |

### Live graphs start from nothing on each run

The owner asked that the sensor graphs start fresh whenever monitoring or a session starts, rather
than carrying on from where the previous run stopped. The Live Monitor's graphs and tiles, and
Home's tiles, now show only readings since the latest start of monitoring or of a session
(`liveReadings`, stored as `WearablePreferences.chartsFrom`); a session also clears the raw muscle
trace. The first reading of a run is kept although its five-second bucket begins a few seconds before
the start. Trails and Home's 2h / 2d / 30d trends are history and still show everything. An unused
heart-rate list on Home was removed.

| Check | Result |
|---|---|
| `LiveWindowTest` (5 new) | Pass: nothing before the start, the first bucket kept, a session restarting the graphs |
| Unit tests | 502, 0 failures |
| Lint | 0 errors, 45 warnings, as before |
| APK | Builds (release-signed debug). Not yet installed: the phone was not connected |

### The owner's first sketch

Before that, the owner's first sketch (a JSON payload over FFE0/FFE1) was merged in. That merge was
replaced by the above, but what the first sketch would have done still holds:

| Their code | Why it was replaced |
|---|---|
| `onConnect(NimBLEServer*)` / `onDisconnect(NimBLEServer*)` | NimBLE 2.x takes `NimBLEConnInfo&`; these hide the virtuals instead of overriding them, so they never run and nothing is ever sent. Compiling their sketch with `-Woverloaded-virtual` says so: "'virtual void NimBLEServerCallbacks::onConnect(NimBLEServer*, NimBLEConnInfo&)' was hidden" |
| A JSON object per notification | The app parses `HR:…,TS:…` lines and acknowledges records by number. A 250-byte payload in one `notify()` is also cut to MTU − 3 bytes, which is 20 on a phone that never asked for more |
| `requestTemperatures()` every 100 ms | DallasTemperature waits for the conversion by default and a DS18B20 defaults to 12-bit, so each call blocks 750 ms and the MAX30102's 32-sample FIFO overflows. The merged code asks, then collects on a later pass |
| `millis()` timestamps, no store-and-forward | The app needs epoch milliseconds, and readings taken out of range have to survive and be acknowledged |
| `110 - 25 × (red/ir)` on raw FIFO values | Not the ratio-of-ratios; the Maxim algorithm is used instead |
| `checkForBeat` with nothing on the sensor, and `rates[]` averaged over empty slots | The merged code detects beats only against skin and averages only the slots that hold one |

**It found a real bug.** The first firmware version sent the catch-up status line *before* the records stored in the same pass. `LAST` then named a record the phone could not have yet, the phone never saw that it had everything, and catch-up never ended, so live alerts would never have resumed. With the old order the self-test fails 5 of 8 scenarios; with the status line moved after the replay (firmware, emulator and `docs/WEARABLE_PROTOCOL.md`), all pass.

---

## Found and fixed by the checks during this work

| Found by | Problem | Fix |
|---|---|---|
| Emulator self-test | Catch-up never ended (above) | Status line after the replay |
| Reading the manifest against Android 11+ package visibility | The speech recogniser and text-to-speech engine were invisible to the app, so voice input could never have started on the phone (Android 16) | `<queries>` for both services |
| Lint `NewApi` (2 errors) | Two automatic-sync calls needed Android 13 while the code ran from Android 12 | Explicit version checks |
| Lint `MissingPermission` (2 baseline errors) | Notifications posted without checking the permission | Checked before posting |
| Lint `PermissionImpliesUnsupportedChromeOsHardware` (baseline error) | Camera permission made the camera required | Camera declared optional |
| Review of acknowledgement | A reading whose database write failed would still have been acknowledged and deleted from the wearable | The pipeline reports whether each reading was stored; only stored readings are acknowledged, and the link starts over from the last acknowledgement when the wearable resends |
| Lint (new warnings from this work) | `InlinedApi`, `ObsoleteSdkInt`, `UseKtx` in new code | Fixed before the final run |
| First run of the instrumented tests on the phone | Every migration test failed: the exported schemas (`app/schemas/…/1.json` to `4.json`) were never packaged into the test APK, so these tests could not have run since they were written | `androidTest` assets include `app/schemas` |
| The same run | The label tests found no screen on a locked, dozing phone | The test screen shows over the lock screen and waits until it is drawn |
| The label test, once it could run | The onboarding name and phone fields had no spoken name: their placeholder was only drawn, so TalkBack said "Edit box" | Each field has a label and reports its error; the chat input and both search fields got labels too, and "Search consultations…" became "Search chats…" |
| First run of the emulator over Bluetooth | `bless` 0.3.0 imports `pysetupdi`, which is not on PyPI, only to rename the PC's Bluetooth adapter (a registry change and an adapter restart) | The emulator supplies a stand-in for that import and disables renaming; it advertises the G-one service under the PC's own Bluetooth name |

---

## What the new unit tests cover

| Area | Test classes (tests) | What is asserted |
|---|---|---|
| Acknowledged sync | `BacklogAckTrackerTest` (13), `PacketParserSyncFieldsTest` (7), `BleUartVitalsSourceTest` (+9, now 20), `MonitoringPipelineTest` (+1) | Acknowledge only stored readings, only in order, in batches of 10 or after 1 s; a gap waits for the resend; resends skipped; `LOST` ranges count as received; a status line marks "caught up" only when everything arrived; the wearable going back to the last acknowledgement starts the link over; a failed clock sync is retried; Sync now skips the backoff; the pipeline reports whether a reading was stored |
| EMG calibration | `EmgCalibrationTest` (5) | Levels at 30/60/90 % between relaxed (95th percentile) and clench (median); a twitch does not move them; too few readings, no real contraction, or a pinned clench are refused |
| Voice | `VoiceLoopTest` (15), `SpeakableTextTest` (6), `SpeechErrorsTest` (6) | Spoken question goes to the chat and the reply is spoken sentence by sentence; hands-free listens again only after speaking; interrupting stops the reply; muting; hearing nothing ends hands-free; a missing offline pack falls back and says so; stale recogniser events ignored; markdown and code are not read out; list numbers and decimals are not sentence ends |
| Accessibility | `ChartSummaryTest` (6) | Chart sentences give the range, the latest value by time and the time span |
| Personalisation | `HealthContextBuilderTest` (8), `ChatPromptTest` (3), `ChatSuggestionsTest` (4) | Fresh readings only, last three reports, a week of alerts, shared records trimmed; simulated data labelled; missing data stated; budgets; suggestions only when their data exists |
| Session PDFs | `ReportPdfTest` (9) | Pagination, the simulated banner, absent channels, no advice wording |
| Tools | `DocumentTaskTest` (9), `DocumentChunksTest` (6), `TextQualityTest` (7), `WholeDocumentSummaryTest` (5), `QuizParserTest` (6), `MarkdownBlocksTest` (10), `LibraryQueryTest` (4), `ChatSessionStoreTest` (5) | Stop and finish races, only finished results save; unreadable PDF text detected; chunking; quiz parsing; markdown; safe search; atomic chat history with corrupt-file recovery |
| Reminders | `ReminderScheduleTest` (9) | Active hours, the next reminder, turns |
| Schema | `GoneMigrationsTest` (+5, now 20) | Migration 3→4 against `4.json`; additive; existing entries not shared with the AI |

Removed: the boilerplate `ExampleUnitTest` and `ExampleInstrumentedTest`.

---

## Not verified

### On the phone (debug build, logcat captured)

| # | Do | Expect |
|---|---|---|
| T2 | PDF Summary with a Word export, a Google Docs export, a scanned PDF and a password-protected PDF | Readable text for the first three (progress per page); a clear message for the fourth |
| T2b | Vault: delete an entry and tap Undo within a few seconds | The entry comes back |
| T3 | *Whole document* on a 20-page PDF, then Stop midway | "Part n of m" progress; stops; *Save anyway* saves it as incomplete |
| T4 | OCR, Screenshot and Quiz results | Formatted text; Copy, Share, Save, Ask G-one work; the quiz is playable with a score |
| T5 | Circle Learn: 10 captures in a row, lock the phone, unlock | Every capture works; after locking, "Tap to turn Circle Learn back on" |
| T6 | Vault: open an entry; search `a:b`; delete and undo | Entry opens; no crash; undo restores it |
| T7 | Share a PDF, an image and text into G-one | PDF Summary, OCR and a chat open with the item |
| T8 | End a monitoring session | The PDF appears; its preview matches the report; Share and Save work; the summary is added |
| T9 | With a session and an imported lab report shared with the AI, ask "How did my last session go?" | Only numbers from the report; switched off, no personal data; *What G-one can see* matches |
| T10 | Voice chat: 3 hands-free turns with airplane mode on | Replies spoken; or a clear message that the offline language pack is needed |
| T11 | Interrupt a spoken reply; mute; Read aloud in chat | Speaking stops and listening starts; muted replies are shown only; Read aloud reads one reply |
| T12 | TalkBack on (you switch it on) across Home, Monitor, Trails, Tools, Chat, Voice, Settings | Every control named with its role and state; headings; chart sentences; a finished reply announced once |
| T13 | Font size at maximum | Buttons grow; no clipped text |
| T14 | Second chat turn, then `adb shell dumpsys meminfo com.gone.ai` | Log shows prefill reusing the first turn's tokens and a shorter prefill; memory reasonable with a 4096 context |
| T15 | Settings › Profile: edit name, age and contact; SOS | Values saved; SOS uses the new contact; Version shows the build's version |
| T16 | Reminders: set active hours, restart the phone | No reminders outside the hours; reminders continue after the restart |
| T17 | Upgrade install over the previous build with data | Existing readings, reports, Vault entries and chats still there |

### On the phone, with the PC emulator as the wearable

`bless` is installed on this PC and the emulator starts advertising (checked for 25 s). It advertises under the PC's Bluetooth name, not "G-one Wearable". Run `python tools/wearable-emulator/emulate.py --scenario <name>`.

| # | Do | Expect |
|---|---|---|
| E1 | Scan on the Device screen | This PC listed first, as a device with the G-one serial service; choosing it saves it |
| E2 | Start monitoring (`normal`) | "Streaming from…"; Live Monitor updates every 5 s; clock "set" |
| E3 | `out-of-range` (away 5 min), then back | "Reconnecting…", then "Catching up" falling to zero; Trails has every reading for the gap (the count matches the emulator's log); no alerts for the gap |
| E4 | Force-stop the app mid catch-up, reopen and start monitoring | Catch-up resumes from the last acknowledged record; no gap and no doubled readings |
| E5 | `reboot` | Readings from before the clock was set appear at the right times |
| E6 | Screen off for 30 min while streaming | Still streaming; no gap beyond 10 s |
| E7 | Turn on *Sync automatically when nearby*, stop monitoring and the emulator, start the emulator again | Android's dialog pairs; monitoring starts by itself when the emulator appears |
| E8 | Bluetooth off, then on | A clear "Bluetooth is off"; reconnects within seconds of turning it on |
| E9 | `emg` scenario, then Device › Calibrate | Relax and clench phases; levels saved; a note to restart monitoring |

### On the bench (Serial Monitor, 115200 baud)

| # | Do | Expect |
|---|---|---|
| H1 | Flash, open Serial Monitor | Start-up box lists MAX30100, MPU6050, DS18B20, EMG and microSD as "READY", with the boot number and records waiting |
| H2 | Watch for 10 s | Two lines a second in the documented format; `ST` shows the present sensors |
| H3 | Finger on the MAX30100 for 30 s, then off | `HR`/`SPO2` appear after a few seconds, then disappear within about 5 s; `ST` bit 0x02 follows |
| H4 | Compare `HR` with a manual 60 s pulse count at rest | Within a few bpm |
| H5 | DS18B20 against the wrist for 2 min | `STEMP` settles around 32–35 °C |
| H6 | Still on a table / dropped onto a cushion from 30 cm | `MOT` ≈ 1.0 / one line above 2.5 |
| H7 | EMG pads on a forearm: relax 30 s, clench 5 s, lift a pad | Low / high / pinned at 4095 or flat with `ST` bit 0x10 cleared |
| H8 | `python tools/wearable-check/check_wearable.py stream --serial COMx` | Two readings a second; no field the app would reject; times agree with the PC; every line is the one printed as `SENT:` |
| H9 | `check_wearable.py backlog --away 60 --serial COMx` | About 120 stored records from the minute away, numbered without gaps, with real times; catch-up ends and live readings resume |
| H10 | `check_wearable.py stop --away 60 --serial COMx` | The board prints `recording PAUSED`; nothing from the minute away is stored |

### Phone and wearable together

| # | Do | Expect |
|---|---|---|
| P1 | Device screen → scan | The wearable is listed first with "G-one serial service" |
| P2 | Deny Nearby devices, then allow it | A clear reason with Allow / Open app settings; the scan starts after granting |
| P3 | Android 11 or older: switch Location off | "…only find Bluetooth devices while Location is switched on" with a button to turn it on |
| P4 | Choose the wearable, turn simulated vitals off, start monitoring | "Connecting to…" then "Streaming from…"; Device screen "Connected and receiving", "Up to date", clock "set" |
| P5 | Watch the Live Monitor for 1 min | Heart rate, SpO₂, motion, skin temperature and muscle activity update every 5 s; the live muscle trace moves twice a second; no core-temperature value |
| P6 | Switch the wearable off for 30 s, then on | "Reconnecting (attempt n): …", then streaming again |
| P7 | Walk out of range for 5 min, then return | "Catching up" with a falling count, then "Up to date"; Trails has the gap; no notifications for it; `/backlog.txt` deleted afterwards |
| P8 | Clench and hold still for 20 s | "Muscles have stayed tense" alert (LOW), in-app and as a notification |
| P9 | Lift an EMG pad while monitoring | "Check pads" on the muscle card and a problem line on the wearable card; no muscle alert |
| P10 | Skin temperature above 38 °C (warm the sensor gently) | No fever alert |
| P11 | Start a session, monitor 10 min, end it | Report and PDF: observations, five points, charts, status matching the alerts raised |
| P12 | Share the report PDF and text | Share sheet; nothing sent until an app is picked |
| P13 | "Write a summary on this phone" | A summary appears, or a message that the model's wording did not pass the check |
| P14 | Choose a different wearable while streaming | Monitoring stops with a message; it starts again with the new device |
| P15 | Upgrade install from the previous build (database v3 with data) | Existing data present; app opens without a crash |
| S1 | Wearable out of range for 5 min with the phone locked | No readings lost |
| S2 | Force-stop the app mid-replay | Replay resumes on the next start |
| S3 | Restart the wearable with no phone nearby, wait, reconnect | Readings from that power-on placed in time |
| S4 | Screen off for 30 min | Sync continues (battery exemption granted; on Samsung, G-one in Never sleeping apps) |
| S5 | Auto-sync on, walk back into range with the app closed | Monitoring starts by itself |

### Calibration still to do

- EMG levels, per wearer: Device › Muscle sensor levels › Calibrate (or by hand, `docs/HARDWARE_SETUP.md`).
- MAX30100 IR LED current (`setIRLedCurrent`, firmware) and placement on the wrist.
