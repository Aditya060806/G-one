<div align="center">

# G-one

**A privacy-preserving personal health companion that runs entirely on the device.**

Vitals and environment are monitored continuously, a deterministic rule engine decides
whether something is wrong, and a local 1.5B language model explains it in plain
language a family member can act on. No cloud. No account. No network call.

**SIH 2026 · Problem Statement 26181** — Qualcomm Inc · MedTech / BioTech / HealthTech

`Kotlin` · `Jetpack Compose` · `llama.cpp / ggml` · `Room` · `ML Kit` · `Material 3`

</div>

---

> ### Build status — Phase 1 complete
>
> `gradlew clean assembleDebug testDebugUnitTest` → **BUILD SUCCESSFUL in 8m 37s**
> **220 unit tests, 0 failures.** APK 1172.7 MB with a 65.25 MB `libinfinity_jni.so`
> (arm64-v8a) and the 1.04 GB GGUF model packaged uncompressed.
>
> Phase 1 is the safety-critical spine and needs **no hardware** — it ships a
> deterministic vitals simulator. The BLE wearable link is Phase 2.

---

## Table of Contents

- [The one thing that matters](#the-one-thing-that-matters)
- [What Phase 1 delivers](#what-phase-1-delivers)
- [Pipeline ordering](#pipeline-ordering)
- [Detection engine](#detection-engine)
  - [The twelve rules](#the-twelve-rules)
  - [Risk scores](#risk-scores)
  - [Heat index](#heat-index)
  - [Noise control](#noise-control)
- [Explanation layer](#explanation-layer)
- [Monitoring service and model ownership](#monitoring-service-and-model-ownership)
- [Data layer](#data-layer)
- [Simulation](#simulation)
- [UI surface](#ui-surface)
  - [The home screen collapses](#the-home-screen-collapses)
  - [The History tabs](#the-history-tabs)
  - [Navigation](#navigation)
  - [Severity has one meaning](#severity-has-one-meaning)
  - [Elevation is asymmetric between themes](#elevation-is-asymmetric-between-themes)
  - [Motion](#motion)
  - [Charts are hand-drawn](#charts-are-hand-drawn)
- [Inherited capability](#inherited-capability)
- [Native layer](#native-layer)
- [Project structure](#project-structure)
- [Tech stack](#tech-stack)
- [Build and run](#build-and-run)
- [Testing](#testing)
- [Mapping to PS26181](#mapping-to-ps26181)
- [Permissions and the offline claim](#permissions-and-the-offline-claim)
- [What Phase 1 does not include](#what-phase-1-does-not-include)
- [Known issues](#known-issues)
- [Troubleshooting](#troubleshooting)

---

## The one thing that matters

Everything else in this document follows from one decision:

```
Sensor → Deterministic detection → Confirmed event → Local LLM explanation → Alert
```

never

```
Sensor → LLM → "diagnosis"
```

A rule engine with named, reviewable thresholds makes every medical decision. The
language model makes none. It never decides whether something is wrong, never assigns
severity, never invents a number, and never chooses what the user should do. Its only
job is to restate an already-validated finding in language a family member with no
medical training can understand.

Three properties fall out of that, and each is enforced in code rather than asserted in
a comment:

**Every alert is traceable to a rule.** `AnomalyEvidence.ruleId` carries the rule that
fired — `spo2.critical`, `baseline.deviation` — so any alert can be traced to a
threshold a clinician can review, not to a token sampled from a distribution.

**The alert never waits for the model.** A deterministic explanation is written and
delivered *before* inference is attempted. On a mid-range phone the model can be tens of
seconds away; someone whose oxygen is falling cannot wait for it.

**The model can fail in every possible way without degrading the alert.** Not loaded,
error state, timeout, exception, or output that fails validation — all of it collapses
to "keep the deterministic text". There is deliberately no code path where a model
failure makes an alert worse.

---

## What Phase 1 delivers

| Layer | Status | Notes |
|---|---|---|
| Vitals ingestion | ✅ | `VitalsSource` interface + deterministic simulator |
| Byte-level packet parsing | ✅ | `SensorPacketParser`, BLE-fragmentation aware |
| Local persistence | ✅ | Room v2, real additive migration, both schemas committed |
| Anomaly detection | ✅ | 12 rules, pure Kotlin, 0 Android dependencies |
| Risk scoring | ✅ | 0–100 heat / respiratory / cardiovascular, computed continuously |
| Deterministic explanations | ✅ | Exhaustive over every anomaly type |
| On-device LLM rewrite | ✅ | Constrained, validated, strictly optional |
| Local alerting | ✅ | Two notification channels, severity-tiered |
| Foreground service | ✅ | `connectedDevice` type, owns the model lifecycle |
| Unit test coverage | ✅ | 220 tests |
| Health UI screens | ✅ | Dashboard · Live Monitor · History · Alerts, on real pipeline state |
| BLE / HM-10 wearable | ⬜ | Phase 2 — the interface boundary is in place |
| Doctor-facing sync | ⬜ | Phase 3 |

6,685 lines of health code across 30 files — 2,745 of which are the Compose UI layer —
plus 3,202 lines of tests across 12 files.

---

## Pipeline ordering

`MonitoringPipeline` is the spine. The order of its five steps is the whole design, and
none of it is arbitrary.

```
onSample(patientId, sample)
  │
  ├─ 1. PERSIST the reading                          ← durability point
  │      Nothing downstream can lose data already on disk, even if
  │      detection throws or the process is killed a millisecond later.
  │
  ├─ 2. DETECT deterministically
  │      In-memory rolling window → 12 rules → candidates + risk scores.
  │      No model. No network. No I/O.
  │
  ├─ 3. PERSIST the event WITH its template explanation
  │      The row is complete and human-readable at this instant.
  │      templateExplanation is NON-NULL by schema.
  │
  ├─ 4. ALERT immediately, using the template          ← user is warned HERE
  │      Notification fires. No dependency on network or model.
  │
  └─ 5. UPGRADE with the LLM, best-effort
         45s timeout. Validated. Failure changes nothing.
```

Steps 1–4 have no dependency on the network **or** the model. That is what satisfies
*"recognize health risks before they become emergencies"* and *"operate effectively with
intermittent or no internet connectivity"* at the same time.

**Why the window lives in memory.** Detection needs recent history on every sample.
Re-reading it from Room each time would be roughly 1,800 rows per second against a
30-minute window — enough to keep the disk permanently busy for no benefit. Samples are
already being written for durability, so the pipeline keeps its own trimmed copy
(`ArrayDeque`, bounded by the trend horizon and a 4,000-sample hard cap) and the database
is consulted only for slow-moving state.

**Failure isolation.** Every step is wrapped. A failed reading write still lets detection
run off the in-memory window, so a storage outage degrades history but never stops the
patient being warned. This is covered by a test.

---

## Detection engine

Pure Kotlin. No Android imports anywhere in `health.domain` or `health.detect`, which is
what makes the safety-critical part directly unit-testable with no emulator, no
Robolectric, and no device.

`AnomalyDetector.evaluate(samples, baseline, now, lastEventAtByType)` is a pure function —
`now` is injected rather than read from a clock, so results are reproducible.

### The twelve rules

| Rule id | Fires on | Max severity |
|---|---|---|
| `spo2.critical` | SpO₂ below 90%, instantly, no duration needed | CRITICAL |
| `spo2.sustained` | SpO₂ below 92% held 10+ minutes — the case PS26181 names | MODERATE |
| `hr.high` | Tachycardia; motion-aware | CRITICAL |
| `hr.low` | Bradycardia at rest, 3+ samples | CRITICAL |
| `temp.fever` | Raised body temperature | CRITICAL |
| `env.heatStress` | Hot conditions **plus** a physiological response | CRITICAL |
| `env.dehydrationRisk` | Heat + rising HR + rising core temp (explicit proxy) | MODERATE |
| `resp.distress` | Poor air with low SpO₂, or falling SpO₂ + rising HR | CRITICAL |
| `cardio.strain` | Cross-signal: HR rising while SpO₂ falls, at rest | MODERATE |
| `motion.fall` | Impact spike **then** immobility | CRITICAL |
| `wellness.fatigue` | Elevated resting HR sustained 20+ min | LOW |
| `baseline.deviation` | Departure from the patient's **own** normal | MODERATE |

Several of these carry deliberate design decisions worth calling out:

**`hr.high` is motion-aware.** At rest the normal threshold applies; while moving, only
the critical threshold counts. Without that, every brisk walk generates an alert and the
user learns to dismiss them.

**`env.heatStress` requires the body to actually respond.** Environmental heat alone is
a weather forecast, not a health event. The rule needs hot conditions *and* a rising core
temperature or elevated resting heart rate, which is what keeps it from firing on every
Indian summer afternoon regardless of the wearer's condition.

**`env.dehydrationRisk` is capped at MODERATE on purpose.** There is no hydration sensor
on this hardware. It infers falling plasma volume from heat plus a rising resting HR plus
a rising core temperature — a longer inference chain than a directly measured vital — so
it never claims confidence it has not earned. The explanation text says so explicitly.

**`motion.fall` needs both halves of the signature.** An impact alone could be the device
being set down on a table. An impact followed by the wearer not moving is what escalates
to CRITICAL.

**`baseline.deviation` is the early-warning rule.** Every other rule waits for a fixed
threshold. This one fires while all vitals are still nominally normal — a resting heart
rate 25% above someone's personal baseline with SpO₂ down three points is invisible to
fixed thresholds but is exactly the 24–48-hour pre-deterioration signature. It is gated
on `PatientBaseline.isReliable` and on being at rest, because a baseline built from four
readings, or a comparison made mid-exercise, would generate confident nonsense.

The baseline itself is built from **at-rest samples only**. Folding exercise heart rates
into a resting baseline would inflate it until real tachycardia looked normal, and the
rule would quietly stop working with no error anywhere.

**All thresholds live in one injectable value object.** `AnomalyThresholds` is a data
class with `init` invariants, not scattered constants — these are clinical decisions, not
implementation details, and a reviewer should not have to read the rule engine to find
them. The defaults are widely-cited general adult reference points, documented as such and
explicitly not presented as clinically validated.

### Risk scores

`RiskScorer` produces 0–100 for heat, respiratory, and cardiovascular stress on **every**
evaluation, whether or not any rule fires.

That gap between rules and scores is the entire early-warning story. Rules are binary and
threshold-driven — they answer "alert now?". Scores are continuous, so a heat score
climbing 20 → 45 → 70 over an afternoon is visible long before anything is crossed. A
system that showed "fine" until it suddenly said "critical" would be useless for
prevention.

Three properties, all covered by tests:

- **Bounded** — always 0..100, so no clamp bug reaches the UI as a 137% risk.
- **Monotonic** — a worse input never lowers a score, which is what makes the number
  trustworthy as a trend line.
- **Graceful** — missing signals contribute 0 rather than poisoning the result, so a
  wearable with no ambient sensor still yields useful vitals-based scores.

### Heat index

`HeatIndex` implements the NWS Rothfusz regression, including both published edge
corrections and the low-range simple form.

38 °C at 25% humidity and 38 °C at 80% humidity are not the same physiological threat.
Sweat evaporation is the body's only real cooling mechanism above ~35 °C, and high
humidity disables it. For a heat-wave feature aimed at India — where coastal humidity
routinely exceeds 70% — comparing raw air temperature against a fixed threshold would
badly understate risk in exactly the conditions that kill people. The measured gap
between those two cases is about 38 °C of apparent temperature.

When humidity is unavailable, `computeOrNull` returns null rather than substituting a
default, and the engine falls back to air temperature. A fabricated humidity would
produce a confident-looking heat index no sensor supports, and that number could go on to
justify an alert.

### Noise control

Two layers, because over-alerting is a worse failure than under-alerting: it trains the
user to ignore the app.

**Per-type cooldown.** A vital hovering at a threshold would otherwise emit one event per
sample. Debouncing is structural, not cosmetic — and a test asserts that 20 consecutive
critically-low samples produce exactly one alert.

**Low-severity shadowing.** When something CRITICAL is firing, informational LOW findings
alongside it are a distraction. They are recorded as suppressed for the audit trail, not
surfaced.

**Rule fault isolation.** Each rule runs inside `runCatching`. Monitoring continuing with
one rule degraded is strictly better than the service dying and the patient going
unwatched.

---

## Explanation layer

Every event gets its explanation from `ExplanationTemplates` first, before the model is
ever asked. The `when` over `AnomalyType` is **exhaustive with no `else` branch**, so
adding an anomaly type without writing its explanation is a compile error rather than a
silent blank alert.

The voice is calm, concrete, hedged, and aimed at a family member with no medical
training. No template names a disease — that would be a diagnosis — and a test enforces it
against a list of forbidden terms.

### Three response tiers, and only three

| Tier | Severity | Intent |
|---|---|---|
| `MONITOR` | LOW | Not urgent, keep an eye on it |
| `CONTACT_DOCTOR` | MODERATE | Discuss with a doctor today |
| `SEEK_IMMEDIATE_CARE` | CRITICAL | Do not wait; names emergency services |

Free-form medical advice from a 1.5B model is the single biggest risk in a product like
this, so the app does not generate advice at all. It selects from three pre-written tiers,
chosen deterministically from rule severity. The mapping is severity-to-tier and
deliberately **not** per-type, so no future rule can quietly downgrade urgency.

The model may rephrase the *description*. It may never touch the recommendation.

### Constraining the model

`HealthPromptBuilder.SYSTEM_PROMPT` carries nine numbered rules — no diagnosis, no
invented numbers, no severity changes, no advice, no false certainty, under 60 words, plus
an explicit safe fallback for when it cannot comply. Guardrails sit in the *system* prompt
rather than a user turn because system framing survives better through a generation.

The user turn includes the deterministic explanation as a worked reference. That is the
important trick: it gives a small model a correct, safe answer to imitate, so the worst
realistic outcome is output close to the template we would have shown anyway.

### Validation is defence in depth

Prompt constraints are guidance, not a guarantee. `HealthPromptBuilder.validate` inspects
the output before anyone sees it and rejects:

- text too short or runaway long
- meta-commentary and refusals (`as an AI`, `I cannot`, stray chat tokens)
- prescriptive or diagnostic language (`take a tablet`, `mg of`, `this is caused by`)
- **any number that was never measured**

That last check is the concrete defence against the most dangerous thing the model can
do — inventing a vital sign. Every integer in the output must appear in the evidence.
Anything else is discarded and the deterministic template stands.

---

## Monitoring service and model ownership

`HealthMonitoringService` is a foreground service with
`foregroundServiceType="connectedDevice"` — which describes what it actually does, stream
vitals from a wearable, and is the type that keeps working with the screen off. BLE needs
that, because Android power-manages GATT links hard.

**Its most important responsibility is owning the model's lifecycle.**

`AIRepository` is a process-wide singleton shared with every screen. Before this service
existed, `ChatViewModel.onCleared()` called unload on it, and because `SettingsScreen`
created its own route-scoped `ChatViewModel`, simply closing the Settings screen freed the
model. Wiring background health monitoring on top of that would mean a user navigating
away could silently disable their own monitoring.

Ownership is now explicit:

- This service calls `initialize()` on start and is the **only** component permitted to
  call `AIRepository.shutdown()`.
- Screen-level ViewModels may cancel their own generation and nothing more.
- The contract is documented on `shutdown()` itself, with the regression history, so the
  bug class cannot quietly return.

Model loading is launched in its own coroutine so a slow load never delays the first
sample. Sampling defaults to one reading every 5 seconds — 1 Hz would be 86,400 rows a
day for no clinical gain — with a 7-day retention trim running every 6 hours.

### Notifications

Two channels, split by urgency:

- **Monitoring** — `IMPORTANCE_LOW`, silent, ongoing. A permanent service notification
  that buzzes gets the app uninstalled.
- **Alerts** — `IMPORTANCE_HIGH`, vibration, `CATEGORY_ALARM` for critical events.

Separating them also means a user who silences the persistent notification does not
accidentally silence critical alerts, which on one shared channel they would.
`BigTextStyle` carries the full explanation so it is readable on a locked screen, which is
when the person who needs to act is most likely to see it. Every post is wrapped —
`POST_NOTIFICATIONS` can be revoked at any time, and that must degrade the alert to
"recorded but not shown", never crash the service.

---

## Data layer

One Room database, version 2, `exportSchema = true`, with **both** `1.json` and `2.json`
committed under `app/schemas/`.

| Table | Holds |
|---|---|
| `patients` | Identity, age, chronic conditions, emergency contact |
| `devices` | Paired wearables, transport, last seen, battery |
| `vitals_readings` | Every sample, with provenance (`SIMULATED` / `BLE` / `MANUAL`) |
| `anomaly_events` | Confirmed events, evidence JSON, both explanations, status |
| `library_entries` + `_fts` | Inherited from Infinity, untouched |

`anomaly_events` carries `templateExplanation` (non-null) **and** `aiExplanation`
(nullable) rather than one mutable field, so both remain auditable after the fact and the
UI can render `aiExplanation ?: templateExplanation`.

### Why the migration is hand-written

`fallbackToDestructiveMigration()` silently drops every table on a version bump. In a
health app that means a user's entire vitals and alert history disappears on upgrade —
and critically, it is **untestable**: there is no migration to assert against, so a schema
mistake surfaces as data loss in the field rather than a red test.

Migration 1 → 2 is purely additive. It contains no `DROP`, no `DELETE`, no `ALTER` of any
pre-existing table, and tests assert that property directly rather than trusting the
comment. `GoneMigrations.MIGRATION_1_2_STATEMENTS` is exposed as a plain
`List<String>` precisely so it can be inspected.

**`1.json` had to be recovered.** Version 1 shipped with `exportSchema = false`, so no
record of the old schema existed and `MigrationTestHelper` could not build a v1 database —
the upgrade path for existing installs was untestable. It was regenerated by temporarily
pinning the database class back to version 1, capturing the export, and restoring v2.

Provenance is stored per reading because it is clinically meaningful: a chart built from
`SIMULATED` data must never be mistaken for real measurements.

---

## Simulation

`SimulatedVitalsSource` is a first-class component, not a test stub. The wearable does not
exist yet, but the spine had to be finished and verified before hardware arrives — and
simulation makes anomalies reproducible on demand, which no real sensor can do. When the
BLE implementation lands it is a *peer* of this class, so regressions in the detection
engine stay catchable forever.

| Scenario | Drives |
|---|---|
| `HEALTHY_BASELINE` | Nothing — the false-positive control |
| `HEAT_WAVE_EXPOSURE` | Heat stress, dehydration, fever |
| `DESATURATION_EPISODE` | Sustained then critical low SpO₂ |
| `TACHYCARDIA_EPISODE` | High heart rate at rest |
| `BRADYCARDIA_EPISODE` | Low heart rate |
| `FEVER_ONSET` | Fever |
| `AIR_QUALITY_EVENT` | Respiratory risk during a pollution event |
| `FALL_THEN_IMMOBILE` | Fall detection, both halves of the signature |
| `GRADUAL_DETERIORATION` | Baseline deviation, and *only* that |

`VitalsScenarioGenerator.sampleAt(index)` is a **pure function of the index**. Jitter is
derived by hashing `(seed, index, field)` rather than drawing from a sequential PRNG, so
`sampleAt(500)` returns exactly what it would after 500 sequential calls. Tests jump
straight to minute 12 of a desaturation episode instead of pumping a flow, and any failure
reproduces byte-for-byte from the seed alone.

Timing uses plain `delay`, so `runTest` drives it on virtual time — a 4-hour deterioration
scenario is exercised in milliseconds.

`GRADUAL_DETERIORATION` is the tightest constraint of the set: it must stay inside *every*
fixed threshold while still drifting away from the patient's own baseline, so that only
`baseline.deviation` can see it. A test asserts exactly that, per sample.

---

## UI surface

Four screens in `health/ui`, all reading the same state the notifications read. There is no
separate UI copy of the vitals or the risk numbers — `HealthViewModel` bridges the
service's `isRunning`/`snapshot` `StateFlow`s and the Room flows, and nothing else.

| Screen | Answers | Notable |
|---|---|---|
| `HealthDashboardScreen` | "Am I OK right now?" | Collapsing hero ring, sticky monitoring bar, quick actions, 4 vital tiles, 3 radial risk gauges, recent activity |
| `LiveMonitorScreen` | "What is happening this second?" | One vital per card, live waveform, linear risk meters, orb driven by pipeline state, honest "3s ago" |
| `HealthHistoryScreen` | "What changed over time?" | Three tabs — Trends, Events, Insights — over 4 ranges |
| `AlertsScreen` | "What did it find and why?" | Active/Seen/All tabs with live counts, evidence, template + AI explanation, acknowledge |

### The home screen collapses

Start/stop monitoring used to sit at the **bottom** of a four-screen scroll. It is the most
important control in the app and it was the hardest thing on the page to reach.

It now lives in the hero, above the fold. Because it has to stay reachable once the hero
scrolls away, a compact bar carrying the same control slides down and pins to the top. So
the primary action exists at every scroll position, in exactly one of two places, and the
scroll itself is the transition between them.

The collapse is driven off `rememberScrollState().value` over a 190dp distance. The hero
fades and scales through `graphicsLayer` and **does not change its layout height** —
animating height would reflow everything below it on every scroll frame, while fading a
fixed-size block scrolls away just as convincingly and never jitters.

The hero ring itself carries three signals: an outer sweep for the dominant risk score,
inner radial ticks for the recent heart-rate series, and a breathing halo bound to the
monitoring state. The halo **stops** when idle rather than slowing down — an animation that
keeps moving while nothing is being measured is a lie about the app's state, and a beautiful
dashboard that is silently not monitoring is worse than a plain one that is.

The scenario picker moved down into a "Data source" section that only appears while stopped.
It is a setup choice made once, and switching scenario mid-run would silently invalidate the
baseline the detector has been learning.

### The History tabs

The History tabs exist because one scroll of six charts made every question equally slow.
Each tab now answers one: **Trends** is the line charts with event markers, **Events** is a
bar chart of when things fired plus a severity donut, and **Insights** is a min/max/average
range band and a time-in-range donut.

Buckets for the aggregate views are derived from the data's own span rather than calendar
units. Reading history is capped at 2,000 samples — about 2.8 hours at the 5 s interval —
so grouping by day would put everything in a single bar.

`HealthViewModel` exposes severity helpers used only for colouring. They deliberately do
**not** decide events — the detector does that, weighing motion, duration and trend that a
single instantaneous reading cannot see. A tile can therefore look amber without an event
existing, and that is correct rather than a bug.

### Navigation

The bottom bar is health-first, five tabs, because five is the practical ceiling before
labels truncate:

```
Health · Monitor · History · Alerts · Assist
```

Tools and Settings are one tap from the Dashboard header. The Knowledge Vault is reached
from Tools. The old Infinity hub screen was removed rather than left orphaned — every
destination it offered is on Tools or the Assist tab, and keeping two tool grids would have
meant maintaining both.

### Severity has one meaning

`HealthTheme.kt` owns the tokens rather than the shared `ui/theme`, so the app-wide theme
does not acquire a dependency on the health domain.

Red is reserved for `CRITICAL` only. A system fault — sensor dropout, model failure — uses
a separate desaturated `SystemFault` tone, because "the app broke" and "you are in danger"
must never look the same. Vital numbers use tabular figures so a digit change does not
shift the layout.

There are **two** palettes behind one `HealthPalette` interface, and that is a correctness
issue rather than a matter of taste. The original colours were tuned against a near-black
background; on the light theme the same amber sits at roughly 2.1:1 against white, under
the 4.5:1 WCAG AA threshold and genuinely hard to read in daylight. `HealthColorsLight`
darkens each role until it clears 4.5:1 while holding the hue, so the colour still means
the same thing. `severityColor`, `riskColor` and `monitoringStateColor` all take the theme.

The app **defaults to light**: a health companion is read in daylight far more often than
in bed. Dark is one tap away in Settings and the choice persists.

### Charts are hand-drawn

Sparklines, line charts, radial gauges, bar charts, range bands, donuts and the waveform
are all `Canvas`, with `PathMeasure` for draw-ins. No charting library: there is exactly
one render style to support, and the APK already carries a 1 GB model.

Long ranges are stride-sampled, not averaged — an hour at 5 s is 720 points, and averaging
would erase the transient spikes that are the entire point of looking. SpO₂ and temperature
charts use pinned axes, since auto-scaling makes a 96 → 95 wobble look like a cliff.

The range-band chart is the one a line graph cannot replace. Averaging a day of heart-rate
samples into a single point hides the two numbers that matter most — how low it went and
how high — and a stretch that ran 52–148 looks identical to a flat 95 once averaged.

### Elevation is asymmetric between themes

`ui/theme/Surfaces.kt` holds `goneSurface` and `GoneCard`, and every health surface routes
through them so elevation cannot drift between screens.

The asymmetry is the point. On light it casts a soft blue-tinted shadow with only a hairline
border; on dark it draws no shadow at all and relies on a visible border. A shadow over a
near-black background is invisible work — it costs a render pass and changes nothing — while
a border on white is what makes a card look like a diagram instead of an object. Every card
in the app was previously `background + 1dp border` on both themes, which is why the light
theme read like a wireframe.

Shadows are tinted toward the background hue rather than being neutral black, because pure
black shadow over a cool background reads as grey sludge.

`GoneRadius` and `GoneElevation` exist so radii and depths are a scale rather than a
per-call-site guess. Mixed radii on adjacent surfaces is a flaw nobody can name but
everybody feels.

### Motion

`ui/theme/Motion.kt` holds the shared tokens (`GoneMotion`), a `pressScale` modifier, a
`shimmer` modifier and `StaggeredEntrance`. It lives in `ui.theme` rather than `health.ui`
because navigation and the tool screens need it too.

Centralising durations is about consistency: when every surface picks its own 300ms-ish
number the app feels subtly unsynchronised, and that reads as low quality even when no
single screen looks wrong.

Navigation uses two different transitions on purpose. Switching bottom-nav tabs is lateral
movement between peers, so it fades through with a slight scale and no directional slide —
sliding would imply an ordering the tabs do not have. Opening a detail screen is a push
deeper, so it slides in and back out the same way, which is what makes the back gesture
feel like reversal rather than another forward step.

---

## Inherited capability

G-one is built on Infinity, a working offline AI assistant, and those features are still
present and functional. The strategic reason: the hardest, riskiest part of "on-device AI
health companion" is making local LLM inference reliable on a phone — model loading, JNI
threading, cancellation, memory. That part already existed, audited and working.

Still shipping, retargetable as medical-document intake per the roadmap:

| Feature | Current behaviour |
|---|---|
| Chat | Streaming, markdown + code blocks, long-press actions |
| OCR Scanner | ML Kit text extraction → explain / check ranges / summarize / notes |
| PDF Summarizer | From-scratch PDF text extractor, no third-party library |
| Screenshot Explainer | Explains and simplifies a captured report or label, decodes terms |
| Quiz Generator | 5 MCQs from text or an image |
| Circle Learn | Floating bubble over any app, drag-select, OCR, 14 actions |
| Knowledge Vault | Room + FTS4 full-text search |

These share the same `AIRepository` singleton as the health explainer. Inference is
serialized by a mutex in C++, so a chat generation and an alert explanation cannot
interleave.

### These were retargeted, not just renamed

Inheriting a working assistant also meant inheriting its *purpose*, and that showed. The
system prompt opened with "You are Infinity, a helpful assistant" — the model did not know
it was a health companion at all. Chat offered "Help me write code". Circle Learn shipped
`EXPLAIN_CODE`, `FIND_BUGS` and `INTERVIEW_QUESTIONS`. `ContentTypeDetector` sniffed for
`{`, `fun ` and `import `. The screenshot tool had a "Fix Error" action.

All of it now points at health. `PromptFormatter.DEFAULT_SYSTEM_PROMPT` establishes G-one's
identity and its refusal boundaries: no diagnosis, no naming a medicine or a dose, and an
explicit instruction to tell the user to seek emergency care when they describe chest pain,
trouble breathing, fainting, heavy bleeding or signs of a stroke. Naming the red flags beats
a general "be careful" because it gives a 1.5B model concrete triggers rather than leaving
the judgement to it.

The prompt is deliberately short. Every caller pays for it on every generation, and the
2048-token context is shared with chat history and up to 800 characters of extracted
document text — a thorough-sounding 300-token persona would measurably shorten how much
conversation fits.

The detector now looks for clinical signals instead (`mg/dl`, `reference range`,
`haemoglobin`, `dosage`). Two details worth noting: it matches case-insensitively now,
because OCR routinely returns lab reports fully capitalised and the old case-sensitive
`contains` missed every one of them; and bare `mg` and `ml` are deliberately excluded, since
they appear inside ordinary words like "mgmt" and "html" and a false MEDICAL classification
would promote the wrong three actions.

The study actions — notes, flashcards, quiz, viva — were **kept**. They are working
features, and scanning a discharge summary into revision notes is a real use of them. Only
the developer-specific ones were removed.

The health prompts repeat "do not diagnose" even though the system prompt already forbids
it. Redundant on purpose: a small model handed a page of abnormal lab values is strongly
pulled toward naming a disease, and restating the boundary next to the data it applies to
holds better than relying on the system turn alone.

---

## Native layer

`app/src/main/cpp/CMakeLists.txt` probes for the vendored sources and picks a target:

| Condition | Builds | Result |
|---|---|---|
| `llama/include/llama.h` present | `infinity_jni.cpp` | Full engine |
| missing | `infinity_jni_stub.cpp` | App still runs; AI features report a clean error |

Three targets: `ggml_cpu` (static, ARM-only sources, Intel AMX excluded), `llama_core`
(static, 25 core units plus **all 128** per-architecture files globbed from
`src/models/*.cpp`), and `infinity_jni` (shared).

### One compiler flag worth knowing about

```cmake
set(SAFE_MATH_FLAGS "-O3 -fno-finite-math-only")
```

`-ffast-math` is deliberately **not** used. It implies `-ffinite-math-only`, and ggml
depends on `INFINITY` / `-INFINITY` throughout its softmax and masking code. Enabling it
produces silently wrong logits. `-O3` plus explicit non-finite math gives the speed
without the corruption.

### Thread safety

Two mutexes and one atomic guard all shared state: `g_state_mutex` for the model/context
pointers, `g_gen_mutex` held for a generation's entire lifetime, and an atomic `g_stop`
for cooperative cancellation. Consequences that were designed for:

- `loadModel` assigns model **and** context under one lock, so `isModelLoaded()` can never
  see a half-initialized engine.
- `unloadModel` takes `g_gen_mutex` first, blocking until any running generation finishes.
  No use-after-free.
- Prefill locks per 512-token chunk, so unload can get in between chunks instead of
  waiting out a multi-second prefill.
- `onComplete()` fires only on natural termination — a stop-flag exit calls nothing,
  because the Kotlin channel is already closed and the partial response must survive.

---

## Project structure

```
app/src/main/java/com/infinity/ai/
├── health/                          ← Phase 1, 23 files / 3,474 lines
│   ├── domain/                      PURE KOTLIN, no Android imports
│   │   ├── Vitals.kt                VitalsSample, Trend, slope math
│   │   ├── Anomaly.kt               AnomalyType, Severity, RiskScores, Evidence + JSON
│   │   └── AnomalyThresholds.kt     every threshold + PatientBaseline
│   ├── detect/                      PURE KOTLIN, the safety-critical core
│   │   ├── Window.kt                VitalsWindow, chronological normalisation
│   │   ├── HeatIndex.kt             NWS Rothfusz + EnvironmentContext
│   │   ├── RiskScorer.kt            0–100, always computed
│   │   ├── AnomalyRules.kt          12 rules + DetectionContext
│   │   └── AnomalyDetector.kt       orchestration, cooldown, shadowing
│   ├── explain/
│   │   ├── Explanation.kt           ResponseTier, Explanation
│   │   ├── ExplanationTemplates.kt  exhaustive over AnomalyType
│   │   └── HealthPromptBuilder.kt   constrained prompt + validation
│   ├── source/
│   │   ├── VitalsSource.kt          the hardware boundary
│   │   ├── SensorPacketParser.kt    BLE-fragmentation aware
│   │   ├── VitalsScenario.kt        9 scenarios, pure generator
│   │   └── SimulatedVitalsSource.kt
│   ├── data/
│   │   ├── HealthEntities.kt        Room entities + mappers
│   │   ├── HealthDao.kt             4 DAOs
│   │   ├── HealthRepository.kt      interface + Room impl
│   │   └── GoneMigrations.kt        testable additive migration
│   └── service/
│       ├── MonitoringPipeline.kt    THE SPINE — Android-free, testable
│       ├── LlamaAiExplainer.kt      optional model rewrite
│       ├── HealthNotifications.kt   channels + AlertSink
│       └── HealthMonitoringService.kt  foreground service, model owner
│   └── ui/
│       ├── HealthTheme.kt           dark + light severity palettes, vital type
│       ├── HealthComponents.kt      tiles, sparklines, waveform, tab row
│       ├── HealthCharts.kt          radial gauge, bars, range band, donut
│       ├── HealthViewModel.kt       service StateFlows + Room → UI state
│       ├── HealthDashboardScreen.kt vitals grid, radial risk gauges, run control
│       ├── LiveMonitorScreen.kt     one-vital-per-card, live strips, risk meters
│       ├── HealthHistoryScreen.kt   Trends / Events / Insights tabs
│       └── AlertsScreen.kt          evidence, explanation, acknowledge
│
├── ai/                              inherited inference trunk (unchanged design)
├── circle/                          Circle Learn overlay subsystem
├── data/library/                    GoneDatabase (v2) + inherited library
├── ocr/  pdf/  model/               extraction + shared AI text processing
├── ui/                              Compose screens, theme, navigation
│   ├── theme/Motion.kt              shared motion tokens + reusable modifiers
│   └── theme/Surfaces.kt            elevation scale, goneSurface, GoneCard
└── viewmodel/                       7 ViewModels

app/schemas/com.infinity.ai.data.library.GoneDatabase/
├── 1.json                           recovered, enables migration testing
└── 2.json                           canonical v2 schema
```

---

## Tech stack

| Layer | Choice |
|---|---|
| Language | Kotlin 2.0.21 |
| UI | Jetpack Compose · BOM 2024.09.00 · Material 3 · light-first |
| Charts | Hand-drawn `Canvas`, no charting dependency |
| Async | Coroutines · `StateFlow` · `callbackFlow` |
| Inference | llama.cpp / ggml, CPU backend, ARM NEON |
| Model | Qwen2.5-1.5B-Instruct, GGUF Q4_K_M, ~1.04 GB |
| OCR | ML Kit Text Recognition 16.0.1, on-device |
| Database | Room 2.7.1 + FTS4, KSP |
| Build | AGP 8.10.1 · Gradle 8.11.1 · NDK 28.2.13676358 · CMake 3.22.1 |
| Min / Target SDK | 24 / 36 |
| ABI | `arm64-v8a` |

### Engine parameters

| Parameter | Value |
|---|---|
| `n_ctx` | 2048 |
| `n_batch` / `n_ubatch` | 512 |
| `n_threads` | 4 |
| `n_gpu_layers` | 0 (CPU only) |
| Max output tokens | 512 |
| Health explanation timeout | 45 s |
| Sample interval | 5 s |

The 45-second explanation timeout is much shorter than the chat features' 3-minute
first-token watchdog, on purpose. Chat has a user willing to wait; this is cosmetic polish
on an alert already delivered, and holding the single serialized inference slot for
minutes would block the next anomaly's rewrite for no benefit.

---

## Build and run

### Requirements

- Android Studio (Ladybug or newer)
- **NDK 28.2.13676358** and **CMake 3.22.1** via SDK Manager → SDK Tools → *Show Package Details*
- An `arm64-v8a` device or emulator, Android 7.0+
- ~4 GB free on device (1 GB APK asset + 1 GB extracted copy + headroom)
- ~6 GB free RAM on the build machine

### Setup

**1 — Add the model.** Not in the repo (`*.gguf` is gitignored). Download
`Qwen2.5-1.5B-Instruct` in **GGUF Q4_K_M**, rename to `qwen.gguf`, place at:

```
app/src/main/assets/models/qwen.gguf
```

**2 — Verify llama.cpp sources.** `app/src/main/cpp/llama/` is vendored and must contain
`include/llama.h`. If it is missing you get the stub build and AI features report
"engine not set up".

> ⚠️ **Do not run `setup_llama.ps1`.** It targets llama.cpp `b4570`, which used a flat
> file layout. The vendored tree here uses the modern layout (`src/`, `ggml/src/`,
> `src/models/`) that `CMakeLists.txt` requires. Running it would overwrite working
> sources with an incompatible set.

**3 — Build.**

```bash
./gradlew assembleDebug
```

A clean build compiles all of ggml plus 128 llama.cpp architecture files — around
**9 minutes** on a 16-core machine. Later builds are incremental.

### Verify

```bash
./gradlew clean assembleDebug testDebugUnitTest
```

Last measured result:

```
BUILD SUCCESSFUL in 8m 37s
48 actionable tasks: 48 executed
220 tests, 0 failures
```

APK: `app/build/outputs/apk/debug/app-debug.apk` — 1172.7 MB, containing
`lib/arm64-v8a/libinfinity_jni.so` at 65.25 MB (the size confirms the real engine rather
than the stub) and `assets/models/qwen.gguf` at 1065.56 MB uncompressed.

---

## Testing

**220 unit tests across 22 classes**, plus 3 instrumented migration tests that need a
device.

| Suite | Tests | Covers |
|---|---|---|
| `AnomalyRulesTest` | 39 | Every rule, firing **and** staying silent |
| `PacketParserTest` | 20 | BLE fragmentation, timestamps, malformed input |
| `MonitoringPipelineTest` | 17 | Ordering, failure isolation, debounce |
| `RiskScorerTest` | 16 | Bounded, monotonic, graceful degradation |
| `VitalsScenarioGeneratorTest` | 16 | Determinism, scenario shapes |
| `AnomalyDetectorTest` | 15 | Cooldown, shadowing, fault isolation |
| `HealthPromptBuilderTest` | 14 | Every validation rejection path |
| `VitalsWindowTest` | 12 | Ordering, sustained-condition null handling |
| `GoneMigrationsTest` | 10 | DDL vs Room's schema, additivity, idempotency |
| `ExplanationTemplatesTest` | 9 | Exhaustive, no null leaks, no diagnoses |
| Domain + others | 52 | JSON, thresholds, baseline, heat index, tiers |

The negative cases matter as much as the positive ones. A monitor that over-alerts is
worse than useless, so **20 of the 39 rule tests assert that nothing fires** — silent on
movement artifacts, silent without environmental data, silent when the patient is at their
own normal, and silent when a more specific rule already owns the reading.

**Three tests worth singling out:**

`healthy baseline never triggers an anomaly` slides a growing window across 30 minutes of
healthy vitals and asserts zero alerts at every step. This is the false-positive control.

`gradual deterioration stays inside every fixed threshold` asserts per sample that the
early-warning scenario never crosses a fixed threshold, proving `baseline.deviation` is
the only rule that can catch it.

`alert fires from the template before the model is ever consulted` records an interleaved
call log and asserts the alert index precedes the inference index. This is the core safety
property, verified rather than described.

`migration DDL matches Room's canonical schema exactly` parses `createSql` out of
`2.json` and compares it against the hand-written migration — catching the drift that
would otherwise crash on upgrade for users who already have data, the hardest failure to
notice in development because a fresh install never runs the migration.

### The tests found two real bugs

Both in code written the same day, both caught before shipping:

1. **Templates rendered the literal string `null`.** `"measured at ${e.spo2}%"` produces
   `"measured at null%"` when a reading is absent. An alert reading that would destroy
   trust in the whole app. Fixed with formatters that degrade to a neutral phrase.
2. **`String.format("%.1f")` used the default locale**, rendering 37.4 as `"37,4"` on a
   German, Hindi or French device — a comma decimal inside a clinical reading. Pinned to
   `Locale.US`.

---

## Mapping to PS26181

| Expected solution area | Implementation |
|---|---|
| Continuous health monitoring | `VitalsSource` → Room, 5 s sampling, provenance-tagged |
| Track baseline changes | `PatientBaseline` from at-rest samples + `baseline.deviation` |
| AI-based anomaly detection | 12 deterministic rules; edge AI for explanation |
| Abnormal heart-rate patterns | `hr.high`, `hr.low`, `cardio.strain`, motion-aware |
| Heat stress / dehydration | `env.heatStress`, `env.dehydrationRisk` + real heat index |
| Respiratory indicators | `resp.distress` with AQI banding |
| Fatigue | `wellness.fatigue` |
| Fall detection | `motion.fall`, impact + immobility |
| Risk assessments | 0–100 heat / respiratory / cardiovascular, continuous |
| Disaster-specific alerts | `EnvironmentContext` shifts rule behaviour under heat/AQI |
| Environmental awareness | Ambient temp, humidity, AQI folded into rules and scores |
| Privacy-preserving edge AI | All analysis local; no networking code exists |
| Offline operation | Steps 1–4 of the pipeline have no network dependency |
| Emergency assistance | `SEEK_IMMEDIATE_CARE` tier, `CATEGORY_ALARM` notifications |
| Wellness dashboard | `HealthDashboardScreen` — vitals grid, 3 radial risk gauges, trends, alerts |
| Scalable deployment | `VitalsSource` / `SensorPacketParser` swap without touching detection |

---

## Permissions and the offline claim

| Permission | Used for |
|---|---|
| `FOREGROUND_SERVICE` + `_CONNECTED_DEVICE` | Continuous monitoring service |
| `POST_NOTIFICATIONS` | Health alerts (API 33+) |
| `RECORD_AUDIO` | Voice input |
| `CAMERA` | OCR capture |
| `SYSTEM_ALERT_WINDOW` | Circle Learn bubble |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Circle Learn screen capture |

### On the offline claim — precisely

There is **no networking code anywhere in this codebase**: no HTTP client, no socket, no
Retrofit/OkHttp/Ktor dependency. Inference, OCR, detection, and storage are all local.
Verified by grep across every Kotlin source file.

`app/src/main/AndroidManifest.xml` declares no `INTERNET` permission either. However, the
**merged** manifest that actually ships does request `INTERNET` and `ACCESS_NETWORK_STATE`,
contributed transitively by ML Kit's Google `datatransport` components (telemetry plumbing,
not the OCR model — the bundled recognizer runs offline).

So the accurate statement is: *the app makes no network calls*, not *the app cannot*. For
the stronger, verifiable guarantee, strip them at merge time:

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
```

That makes the claim checkable with `aapt dump permissions`. Not applied by default
because it changes third-party SDK behaviour and a future doctor-facing sync would need
the permission back.

---

## What Phase 1 does not include

Stated plainly, because a spine without a surface is easy to oversell.

**No UI tests.** The four health screens exist and are wired to real pipeline state, but
they are verified by compilation and manual inspection only — there are no Compose UI
tests. The 220 unit tests cover the domain, detection, explanation and migration layers,
none of which import Compose. So UI regressions are the one class of defect this build
cannot catch automatically.

**No real wearable.** `SimulatedVitalsSource` is the only implementation.
`VitalsSource` and `SensorPacketParser` exist precisely so the BLE layer drops in without
touching detection, storage, or explanation — but that work is not done.

**BLE, not Bluetooth Classic.** HM-10 is a BLE module, so the eventual implementation is
`BluetoothGatt` with service/characteristic `FFE0`/`FFE1`, not `BluetoothSocket` with the
SPP UUID. Confirm the UUIDs against your board; clones vary. Note also that enabling
notifications requires writing the CCCD descriptor, and that GATT operations must be
serialized — the Android stack silently drops a second call made while one is in flight.

**No doctor-facing sync.** `anomaly_events.syncedAt` and the `unsynced` query exist as
the schema hook; the WorkManager job does not.

**Thresholds are not clinically validated.** They are documented general adult reference
points, injectable so validated values can be dropped in without a code change.

**Fall detection is threshold-based.** No trained classifier. Auditable and needs no
training data, with obvious failure modes; a learned model is the documented upgrade.

**Explanations are English-only.**

---

## Known issues

Fixed during Phase 1:

- ~~`ChatViewModel.onCleared()` unloaded the shared model~~
- ~~`SettingsScreen` created a second route-scoped `ChatViewModel`~~
- ~~Duplicate `composable("circle_learn")` route~~
- ~~`Responding.partialText` never populated~~
- ~~Every prompt contained the user's message twice~~
- ~~`QuizViewModel` generation escaped cancellation~~
- ~~`fallbackToDestructiveMigration()` on a health database~~
- ~~Templates leaked the literal string `null`~~
- ~~Locale-dependent temperature formatting~~
- ~~Tools had a dead "Smart Notes" card with an empty `onClick`~~ (now the Knowledge Vault)
- ~~`themes.xml` used the old brown `#0D0A08`, flashing brown on every cold start~~
- ~~Launcher label still read "Infinity"~~ (now G-one)
- ~~The system prompt told the model it was "Infinity, a helpful assistant"~~
- ~~Chat, Circle Learn and the screenshot tool offered coding actions in a health app~~
- ~~`ContentTypeDetector` matched case-sensitively, missing every ALL-CAPS OCR report~~
- ~~Severity colours were dark-tuned only; amber sat at ~2.1:1 on the light theme~~
- ~~The light theme's background was a flat fill while dark got a gradient~~
- ~~Settings reported a fictional engine ("Infinity-X1", "Production Foundation")~~
- ~~Start/stop monitoring was buried at the bottom of a four-screen scroll~~
- ~~Every card used `background + 1dp border` on both themes, so light looked like a wireframe~~
- ~~`LightShadow` and `LightBorderStrong` were defined but never used~~
- ~~Release build had no signing config and would produce an uninstallable APK~~
- ~~R8 would have renamed the JNI callback, silently breaking generation in release~~

Still open:

| Issue | Impact | Location |
|---|---|---|
| Voice results route through `startFromSuggestion`, which replaces the whole message list | Speaking clears chat history | `ChatViewModel` |
| `withTimeout` wraps the blocking JNI `loadModel`, so it cannot interrupt; if it trips, `initializationError` is permanent | Requires app restart | `AIRepository.initialize` |
| No `top_k` and no repetition penalty; `top_p` applied before `temp` | 1.5B model can loop | `infinity_jni.cpp` |
| 800-character cap on OCR/PDF input | A long PDF is summarized from its first section only | extractors |
| No token budget on chat history | Long chats can exceed `n_ctx` → `Prompt decode failed` | `PromptFormatter` |

---

## Troubleshooting

<details>
<summary><b>"AI engine not set up. Run setup_llama.ps1"</b></summary>

CMake fell back to the stub because `app/src/main/cpp/llama/include/llama.h` was not
found. Restore the vendored `llama/` directory — do **not** run `setup_llama.ps1`. Then
Build → Clean Project and rebuild.
</details>

<details>
<summary><b>"Failed to load AI model"</b></summary>

Usually a missing or truncated `qwen.gguf`. Confirm it is at
`app/src/main/assets/models/qwen.gguf` and roughly 1.04 GB; a valid GGUF starts with the
ASCII magic `GGUF`. Also check free space — extraction needs ~1 GB beyond the APK.
</details>

<details>
<summary><b>Health alerts never appear</b></summary>

Check in order: is `HealthMonitoringService` running (persistent notification visible),
was `POST_NOTIFICATIONS` granted on API 33+, and has the per-type cooldown already fired
for this event type in the last 15 minutes? The cooldown is deliberate — a continuous
anomaly produces one alert, not one per sample.
</details>

<details>
<summary><b>Explanations look templated rather than AI-written</b></summary>

That is the designed fallback, not a failure. The deterministic template is always shown
first and the model only replaces it when inference succeeds *and* output passes
validation. Check logcat for `LlamaAiExplainer`: "Model not ready", "timed out", or
"output rejected by validation" each explain why the template stood.
</details>

<details>
<summary><b>Cannot lock execution history cache … already been locked by this process</b></summary>

A Gradle daemon has a wedged lock. Restarting Studio does **not** fix it, because Studio
reconnects to the same surviving daemon.

```powershell
./gradlew --stop
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -like "*GradleDaemon*" } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

Remove-Item ".gradle\8.11.1\executionHistory" -Recurse -Force
```

Then re-sync. A clean Gradle lock file is 17 bytes; a stale one is larger because it still
holds an owner record. The usual trigger is memory pressure killing a build mid-write —
this project's native build spawns many parallel clang processes, so free up RAM first.
</details>

<details>
<summary><b>Native build fails with odd or truncated paths</b></summary>

This project's path contains parentheses. CMake and Ninja normally handle that, but if a
mangled path appears in the error, move the project somewhere without parentheses.
</details>

---

<div align="center">

**G-one** · Phase 1 · SIH 2026 PS26181
Qwen2.5-1.5B-Instruct · llama.cpp · 100% on-device inference

*Deterministic detection. Generative explanation. Never the other way round.*

</div>
