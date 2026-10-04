# PS26181 traceability

Each requirement of the problem statement, as an external review of SIH26181 entries grouped
them, with where G-one implements it, the test that covers it, the evidence, and what is still
missing. "Measured" means a number from a run recorded in this repository; nothing here is a
clinical claim.

| # | Requirement | G-one implementation | Tests | Evidence | Still missing |
|---|---|---|---|---|---|
| 1 | Continuous monitoring | Wearable over BLE (`health/source/ble`), 5-s aggregation (`SampleAggregator`), foreground service (`HealthMonitoringService`), Room storage | `BleUartVitalsSourceTest`, `SampleAggregatorTest`, `PacketParserTest`, `MonitoringPipelineTest` | Real 19-minute session stored and reported (VERIFICATION.md) | Strap heart rate blocked by missing pull-up resistors; live-only firmware |
| 2 | Anomaly detection | 15 deterministic rules (`health/detect/AnomalyRules.kt`), personal baseline, risk scores; **trained models**: heart-rate-in-context (rule `ml.hrContext`), fall detector, sleep model (`com.gone.ai.ml`, `ml/`) | `AnomalyRulesTest`, `AnomalyDetectorTest`, `FallWindowTest`, `HrContextTest`, `SleepTest`, `ml/tests` | Fall model 96.2 % falls caught / 95.9 % no-alarm on held-out people vs the 5 g rule's 50.7 % / 89.6 %; heart-rate rule 2.2 false alarms a day vs 51.6 for the fixed rule ([MODELS.md](MODELS.md)) | Validation on the actual strap and on patients |
| 3 | Disaster alerts | NDMA SACHET CAP feeds, district matching, notifications, offline guidance (`health/disaster`, `AreaRepository`, `AreaAlertJob`) | `DisasterTest` (real saved feeds), `AreaRepositoryTest` | Live SACHET feeds fetched and parsed on 2 Oct 2026, and from the phone on 4 Oct 2026 (`AreaLiveNetworkTest`) | A live district notification not yet observed on the phone; polygon matching blocked by SACHET (HTTP 403) |
| 4 | Environmental awareness | City weather into the heat rules when the strap has no sensor (capped, labelled, no SOS); India AQI (CPCB); US AQI; forecast hints in IMD terms | `AnomalyRulesTest` (city weather), `MonitoringPipelineTest`, `IndiaAqiTest`, `AirQualityRepositoryTest` | Open-Meteo responses checked on 3 Oct 2026 | No on-body ambient sensor (needs a BME280; the app already parses `ATEMP`/`HUM`) |
| 5 | Privacy and edge AI | Detection, three trained models, the 1.5B language model and OCR all on the phone; alerts need no network; SACHET matching done locally | `ModelOnDeviceTest` (on a phone), `HealthPromptBuilderTest` | Model sizes 5–15 KB INT8, 9–27 µs per run on the phone's CPU; LLM pinned by SHA-256 (`tools/fetch_model.ps1`) | Database not encrypted; in the app the models run on the CPU (on Qualcomm AI Hub's Snapdragon phones every layer runs on the NPU, 41–55 µs); the language model is CPU-only |
| 6 | Emergency response | Automatic SMS (+ optional call) on live anomalies; **20-second "I'm OK" window for falls only**; NFC/QR emergency ID | `SosCoordinatorTest`, `SosTest`, `EmergencyPayloadTest`, `EmergencyHtmlRendererTest` | A 9.47 g jolt raised a fall alert on the strap (VERIFICATION.md) | A real SMS and call from the phone not yet recorded |
| 7 | Dashboard | Health, Monitor, Trails tabs; sessions and PDF reports; Model Lab; Your area card | `ReportPdfTest`, `SessionReportBuilderTest`, `AccessibilityLabelsTest` (on a phone) | PDF from a real session matched stored values | — |
| 8 | Scalable deployment | Android app plus one strap; standard BLE serial profile; models swappable by manifest | — | — | No smartwatch, Health Connect or Wear OS support yet |

## Design rules that hold across all of it

- **Rules decide; models are sensors; the language model only rewords.** Every model output goes
  through a named rule with fixed thresholds; the LLM can never raise, lower or invent an alert.
- **An alert never waits for the network or the model.** The pipeline stores, detects, stores
  the event, alerts, and only then queues a rewrite.
- **Estimates are labelled as estimates.** City weather, modelled AQI, forecast hints and the
  sleep estimate say what they are wherever they appear, and none of them can send an SOS.
