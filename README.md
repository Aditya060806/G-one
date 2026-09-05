<div align="center">

# ∞ Infinity

**A fully offline AI assistant for Android.**

Qwen2.5-1.5B runs directly on the device through llama.cpp compiled with the NDK.
No API keys. No servers. No network calls anywhere in the codebase.

`Kotlin` · `Jetpack Compose` · `llama.cpp / ggml` · `ML Kit` · `Room` · `Material 3`

</div>

---

## Table of Contents

- [What it does](#what-it-does)
- [Why it's built this way](#why-its-built-this-way)
- [Architecture](#architecture)
- [The inference pipeline](#the-inference-pipeline)
- [Native layer](#native-layer)
- [Feature pipelines](#feature-pipelines)
  - [Chat](#1-chat)
  - [Circle Learn](#2-circle-learn-flagship)
  - [PDF Summarizer](#3-pdf-summarizer)
  - [OCR Scanner](#4-ocr-scanner)
  - [Screenshot Explainer](#5-screenshot-explainer)
  - [Quiz Generator](#6-quiz-generator)
  - [Knowledge Vault](#7-knowledge-vault-library)
- [State model](#state-model)
- [Reliability engineering](#reliability-engineering)
- [Project structure](#project-structure)
- [Tech stack](#tech-stack)
- [Build & run](#build--run)
- [Design system](#design-system)
- [Permissions](#permissions)
- [Known limitations](#known-limitations)
- [Troubleshooting](#troubleshooting)

---

## What it does

Infinity is an on-device AI workspace. Everything below runs with the network turned off.

| Feature | What it does | Input |
|---|---|---|
| **Chat** | Streaming conversation with markdown + code block rendering | Text, voice |
| **Circle Learn** | Floating bubble over *any* app — drag to select a region, OCR it, run an AI action | Screen region |
| **PDF Summarizer** | Extracts text from PDFs and summarizes | `.pdf` |
| **OCR Scanner** | Reads text from images, then summarizes / explains / converts to notes | Camera, gallery |
| **Screenshot Explainer** | Explains errors, code, and technical screenshots | Gallery |
| **Quiz Generator** | Produces 5 MCQs with answers | Pasted text, image |
| **Knowledge Vault** | Full-text-searchable local store; every result auto-saves here | — |

All seven share **one** loaded model instance and **one** inference pipeline.

---

## Why it's built this way

A few decisions drive the whole design:

**One model, one engine, one lock.** A 1.04 GB model can only be loaded once on a phone. `AIRepository` is a process-wide singleton, and the C++ layer serializes generation behind a mutex so two screens can never corrupt a shared `llama_context`.

**The engine is an interface, not a dependency.** ViewModels talk to `AIRepository`, which talks to `LocalAIEngine`. `LlamaEngine` is one implementation. Nothing above the repository knows llama.cpp exists.

**Partial output is never thrown away.** On a mid-range phone a 1.5B model may take 60–90 s just to prefill. Every generation path is written so that once the first token arrives, whatever was produced is preserved and saved — timeout, cancellation, or error.

**Everything is a `Flow`.** Tokens stream from a C++ pthread → JNI callback → `callbackFlow` → `StateFlow` → Compose. No polling, no blocking the main thread.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  UI          Jetpack Compose · Material 3 · Navigation Compose   │
│              12 routes · AiBodyOrb state visualiser              │
└───────────────────────────────┬──────────────────────────────────┘
                                │ StateFlow
┌───────────────────────────────▼──────────────────────────────────┐
│  ViewModel   Chat · Pdf · Ocr · Screenshot · Quiz · Circle       │
│              Library · Theme                                     │
└───────────────────────────────┬──────────────────────────────────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        │                       │                       │
┌───────▼─────────┐   ┌─────────▼─────────┐   ┌─────────▼─────────┐
│  AIRepository   │   │ LibraryRepository │   │  Extractors       │
│  (singleton)    │   │  (singleton)      │   │  Pdf · Ocr        │
└───────┬─────────┘   └─────────┬─────────┘   └───────────────────┘
        │                       │
┌───────▼─────────┐   ┌─────────▼─────────┐
│ LocalAIEngine   │   │ Room + FTS4       │
│  ↳ LlamaEngine  │   │ infinity_library  │
└───────┬─────────┘   └───────────────────┘
        │
┌───────▼──────────────────────────────────────────────────────────┐
│  LlamaJniBridge          5 external fun + LlamaCallback          │
└───────┬──────────────────────────────────────────────────────────┘
        │ JNI
┌───────▼──────────────────────────────────────────────────────────┐
│  libinfinity_jni.so   →   llama_core   →   ggml_cpu (ARM64 NEON) │
└──────────────────────────────────────────────────────────────────┘
```

---

## The inference pipeline

### Cold start

```
App launch
  └─ ViewModel.init → AIRepository.initialize()          [Mutex, idempotent]
       ├─ ModelStorageManager.isModelExtracted()?
       │    └─ no  → copy assets/models/qwen.gguf  →  filesDir/models/
       │              8 MB buffer · writes .tmp · atomic rename
       │              progress reported 0.0 → 1.0 to the UI
       └─ LlamaEngine.loadModel(path)
            └─ JNI loadModel(path, nCtx=2048, nThreads=4)
                 ├─ llama_backend_init() + register CPU backend   [once]
                 ├─ llama_model_load_from_file()   n_gpu_layers = 0
                 └─ llama_init_from_model()        n_batch = n_ubatch = 512
```

Extraction happens **once per install**. `isModelExtracted()` is checked *outside* the mutex so concurrent callers return immediately instead of queueing behind a multi-minute copy, then re-checked inside the lock to close the race.

### Generation

```
ViewModel.generate(history, userInput)
  └─ AIRepository.generate()
       └─ TokenStreamBuffer.clean(          ← trims leading whitespace, 1st token
            LlamaEngine.generate() )
              ├─ state = Thinking
              ├─ PromptFormatter.buildPrompt()      ← ChatML for Qwen 2.5
              └─ JNI generate(prompt, maxTokens=512, callback)
                   │
                   │  ── C++ side, detached pthread ──────────────────
                   ├─ lock g_gen_mutex          only one generation ever runs
                   ├─ AttachCurrentThread("llama-inference")
                   ├─ g_stop = false
                   ├─ snapshot g_model / g_ctx under g_state_mutex
                   ├─ llama_tokenize (two-pass: size, then fill)
                   ├─ llama_memory_clear()
                   ├─ PREFILL  for i in 0..n step 512
                   │     └─ re-check g_ctx, llama_decode(batch)
                   ├─ sampler chain: top_p(0.9) → temp(0.7) → dist
                   └─ DECODE   for i in 0..maxTokens
                         ├─ llama_sampler_sample()
                         ├─ break if llama_vocab_is_eog()
                         ├─ llama_token_to_piece() → onToken(String)
                         └─ llama_decode(next)
                   ── back on the JVM ──────────────────────────────
              ├─ onToken   → CAS Thinking→Responding, trySend(token)
              ├─ onComplete→ state = Idle, close channel
              └─ onError   → state = Error, close(exception)
       │
       └─ .buffer(UNLIMITED)     C++ never blocks waiting on the collector
```

`awaitClose { stopGeneration() }` means cancelling the coroutine — user navigates away, presses Stop, ViewModel dies — automatically flips the atomic stop flag in C++ and the decode loop exits at the next token boundary.

### Prompt format

Qwen 2.5 requires ChatML exactly. `PromptFormatter` emits:

```
<|im_start|>system
You are Infinity, a helpful, concise, and intelligent AI assistant.
You run entirely offline on the user's device. …<|im_end|>
<|im_start|>user
…<|im_end|>
<|im_start|>assistant
                    ← generation begins here
```

Feature screens (PDF, OCR, Quiz, Circle) reuse the same formatter by passing an empty history and their instruction as the user turn, so there is exactly one prompt-construction path in the app.

---

## Native layer

### Conditional build

`app/src/main/cpp/CMakeLists.txt` probes for the vendored sources and picks a target:

| Condition | Builds | Result |
|---|---|---|
| `llama/include/llama.h` exists | `infinity_jni.cpp` | Full engine |
| missing | `infinity_jni_stub.cpp` | App still compiles and runs; AI features surface a clean error |

The stub exists so the project is never in a broken state — every JNI entry point returns a safe "not available" value instead of crashing on `System.loadLibrary`.

### Targets

| Target | Kind | Contents |
|---|---|---|
| `ggml_cpu` | static | `ggml.c/.cpp`, `gguf.cpp`, alloc, backend, quants, threading, opt, full `ggml-cpu/` + `arch/arm/` + `llamafile/sgemm.cpp` |
| `llama_core` | static | 25 `llama*.cpp` core units, `unicode` + `unicode-data`, plus **all 128** per-architecture files globbed from `src/models/*.cpp` |
| `infinity_jni` | **shared** | `infinity_jni.cpp` → links `llama_core ggml_cpu android log dl` |

### Compiler flags — and one that matters

```cmake
set(SAFE_MATH_FLAGS "-O3 -fno-finite-math-only")
```

`-ffast-math` is deliberately **not** used. It implies `-ffinite-math-only`, which tells the compiler `INFINITY` can't occur — and ggml relies on `INFINITY` / `-INFINITY` throughout its softmax and masking code. Enabling it produces silently wrong logits. `-O3` plus explicit non-finite math gives the speed without the corruption.

Two more deliberate choices:

- **AMX excluded.** `amx.cpp` / `mmq.cpp` are Intel x86 Advanced Matrix Extensions. On ARM64 they either fail to compile or emit dead code, so they're left out of the source list.
- **`arm64-v8a` only.** Shipping four ABIs of a static llama.cpp build would bloat the APK for no benefit; every Android device capable of running a 1.5B model is arm64.

### Thread safety

Two mutexes and one atomic guard all shared state:

| Guard | Protects |
|---|---|
| `g_state_mutex` | reads/writes of `g_model` and `g_ctx` |
| `g_gen_mutex` | held for the entire lifetime of a generation thread — serializes inference |
| `g_stop` (`std::atomic<bool>`) | cooperative cancellation, safe from any thread |

Consequences that were designed for, not stumbled into:

- `loadModel` assigns `g_model` **and** `g_ctx` inside one lock, so `isModelLoaded()` can never observe a half-initialized engine.
- `unloadModel` acquires `g_gen_mutex` **first**, so it blocks until any running generation finishes before freeing memory. No use-after-free.
- Prefill locks **per 512-token chunk** rather than across the whole loop, so `unloadModel` can get in between chunks instead of waiting out a multi-second prefill.
- The decode loop re-reads `g_ctx` under the lock before every `llama_decode`, so a concurrent unload turns into a clean early exit.
- `onComplete()` fires **only** on natural termination. A stop-flag exit deliberately calls nothing, because the Kotlin channel is already closed and the partial response must survive.

---

## Feature pipelines

### 1. Chat

`ChatScreen` · `ChatViewModel`

- Streaming bubbles; tokens appended to a `StateFlow<List<ChatMessage>>` with a `▍` caret while live
- Fenced code blocks (triple backticks with an optional language tag) are parsed into `MessageSegment.CodeBlock` and rendered with a terminal-style header, language label, and copy button
- Long-press → `ModalBottomSheet`: Copy · Share · Save to Vault · Regenerate · Select Text
- Inline copy/share/save icons appear on completed messages
- `SelectionContainer` everywhere so text is selectable
- First-launch model extraction shown as a determinate progress bar
- Voice input via Android `SpeechRecognizer`
- Send button morphs: **Mic** (empty) → **Send** (typed) → **Stop** (generating)

### 2. Circle Learn (flagship)

The most involved subsystem. A floating bubble sits above every app; you drag a box around anything on screen and get an AI action menu. Runs entirely in `WindowManager` overlays — **no Activity in the normal path**.

```
CircleLearnEntryScreen
  ├─ request SYSTEM_ALERT_WINDOW  (Settings.canDrawOverlays)
  ├─ request POST_NOTIFICATIONS   (API 33+)
  └─ MediaProjection consent dialog
       └─ startForegroundService(InfinityOverlayService, ACTION_START)
            ├─ startForeground(notification)        ← before getMediaProjection, required on API 34+
            ├─ initMediaProjection()                ← created ONCE, reused for every capture
            └─ FloatingBubbleView.show()

  ── per tap ────────────────────────────────────────────────────────
  bubble tap
    ├─ bubble.hide()          so it isn't in the screenshot
    ├─ delay(120 ms)          let the hide settle
    ├─ captureScreen()
    │    ├─ createVirtualDisplay(AUTO_MIRROR → ImageReader surface)
    │    ├─ delay(300 ms)     let one frame render
    │    ├─ acquireLatestImage() → copyPixelsFromBuffer
    │    ├─ crop away rowStride padding
    │    └─ vd.release()      VirtualDisplay only — MediaProjection stays alive
    ├─ RegionSelectionView    dim + PorterDuff.CLEAR hole, corner accents, live size
    ├─ CircleLearnProcessor   crop → ML Kit OCR → clean → ContentTypeDetector
    ├─ CircleLearnBottomSheetHost   suggested + all 14 actions
    └─ dismiss → removeOverlays() → ensureBubble()   ← bubble survives, ready again
```

**Why MediaProjection is created once:** re-requesting it per capture would re-trigger the system consent dialog every single time. Creating it at service start and only cycling the `VirtualDisplay` makes the second tap as fast as the first. A `MediaProjection.Callback` watches for the system killing the projection, and `captureScreen` can re-init once as a recovery path.

**Compose without an Activity.** `OverlayComposeHost` and `FloatingBubbleView` each implement `LifecycleOwner`, `ViewModelStoreOwner`, and `SavedStateRegistryOwner` by hand and install them as view-tree owners, because Compose walks to the *window root* looking for them. For the bubble that means the owners go on the root `FrameLayout` **before** `addView`, not on the `AbstractComposeView` itself.

**Content-aware actions.** `ContentTypeDetector` classifies the OCR text heuristically — no AI call — and reorders suggestions:

| Detected | Suggested first |
|---|---|
| `CODE` | Explain Code · Find Bugs · Interview Questions |
| `FORMULA` | Explain · Solve Example · Practice Questions |
| `TABLE` | Summarize · Key Points · Notes |
| `PARAGRAPH` | Notes · Summarize · Flashcards |

Full action set (14): Explain, Summarize, Generate Notes, Flashcards, Quiz, Viva Questions, Translate, Explain Code, Find Bugs, Interview Questions, Solve Example, Practice Questions, Key Points, Save to Vault.

**The bubble itself** is a Compose `Canvas` drawing a lemniscate (∞) from four cubic béziers over a frosted glass disc with specular highlights. It breathes when idle, traces the path with `PathMeasure.getSegment` while thinking or generating, spins a scan ring during OCR, scales on drag, and magnetically snaps to the nearest screen edge on release. Tap vs. drag is discriminated at an 8 dp threshold.

`CircleLearnActivity` is a **fallback only** — used when overlay permission is unavailable, receiving the screenshot through a static handoff.

### 3. PDF Summarizer

`PdfTextExtractor` is a **from-scratch PDF text extractor**. No third-party PDF library.

```
Uri
 ├─ PdfRenderer                     → page count only (for progress)
 └─ raw byte scan (ISO-8859-1)
     ├─ walk every stream … endstream block
     ├─ read the preceding << … >> dictionary
     ├─ FlateDecode?  → InflaterInputStream  (corrupt/non-zlib skipped silently)
     ├─ contains "BT"? → parse BT … ET text sections
     ├─ extract ( literal ) and < hex > string operands
     ├─ fallback: bare string literals for flat PDFs
     └─ clean()
```

`clean()` is doing real work, because raw PDF extraction inflates token counts 3–4×:

1. strip control characters — each would otherwise become its own token
2. strip `\x80–\x9F` ISO-8859-1 artifacts
3. strip leaked PDF operators (`BT ET Tf Td Tm Tj TJ cm re gs Do …`)
4. collapse whitespace runs and 3+ newlines
5. drop lines that are pure coordinate noise

The operator regex uses `(?<![A-Za-z]) … (?![A-Za-z])` rather than `\b`, because `\b` does **not** fire between a digit and a letter — so `720Td` and `12cm` would otherwise survive.

Scanned/image-only PDFs return an explanatory error rather than silently producing nothing.

### 4. OCR Scanner

ML Kit `TextRecognition` (Latin, on-device) wrapped in `suspendCancellableCoroutine`. Cleans control characters, collapses whitespace, and drops single-character noise lines. Returns `Pair(text, wasTruncated)` so the UI can show a truncation notice.

Four actions: **Summarize · Key Points · Explain · Convert to Notes**

### 5. Screenshot Explainer

Same OCR front-end, tuned for technical content — Android Studio errors, stack traces, code, exam questions.

Four actions: **Explain · Simplify · Fix Error · Extract Key Info**

### 6. Quiz Generator

Accepts pasted text (800-char counter in the UI) or an image via OCR. Prompts for exactly 5 MCQs with A/B/C/D options and stated answers.

### 7. Knowledge Vault (Library)

Room with a real FTS4 index.

```kotlin
@Fts4(contentEntity = LibraryEntry::class)
@Entity(tableName = "library_entries_fts")
data class LibraryEntryFts(val title: String, val content: String)
```

`contentEntity` means the FTS table is an external-content index — Room keeps it in sync via triggers, `rowid` maps to the entry `id`, and content isn't duplicated on disk.

- Search queries are sanitized (FTS special characters removed) and suffixed with `*` so `error` matches `errors`
- Reactive: `combine(filter, query).flatMapLatest { … }` cancels the previous DB flow on every change, so no stale results
- Filter chips per `EntryType`, live count pills, two-tap delete confirmation
- Titles auto-derive from the first non-blank line, capped at 60 chars
- **Every** feature auto-saves on completion — including partial results

| `EntryType` | Written by |
|---|---|
| `PDF_SUMMARY` | PDF Summarizer |
| `OCR` | OCR Scanner, Circle Learn |
| `SCREENSHOT` | Screenshot Explainer |
| `QUIZ` | Quiz Generator, Circle (Quiz/Practice/Interview) |
| `NOTE` | Chat save, Circle (Notes/Flashcards) |

---

## State model

One sealed class is the app's single source of truth for engine status:

```kotlin
sealed class AIInferenceState {
    object Idle                                       // ready
    object Loading                                    // extracting or loading into RAM
    object Thinking                                   // prefill
    data class Responding(val partialText: String)    // streaming
    data class Error(val message: String)
}
```

It flows `LlamaEngine → AIRepository → ViewModel → UI` and drives, from the same value: the chat header dot, the Settings engine row, the dashboard status line, and every parameter of `AiBodyOrb`.

`LlamaEngine` holds it in **both** an `AtomicReference` and a `MutableStateFlow`. That's intentional: `MutableStateFlow` has no compare-and-set API, and `onToken` is invoked from a C++ thread where the `Thinking → Responding` transition must happen exactly once. The `AtomicReference` is the CAS source of truth; the `StateFlow` is the observable mirror.

### AiBodyOrb

A pure-Canvas visualiser — ambient glow, expanding ripple rings, drifting outer ring, rotating arc segments, mid sphere, core sphere, two specular highlights. Every animation parameter is derived from state:

| State | Pulse | Rotation | Extras |
|---|---|---|---|
| Idle | 2800 ms, 0.96–1.04 | 20 s | — |
| Loading | 1200 ms, 0.92–1.06 | 8 s | ripple + arcs |
| Thinking | 1000 ms, 0.93–1.07 | 6 s | arcs |
| Responding | 900 ms, 0.92–1.08 | 4 s | ripple + arcs |
| Error | 400 ms, 0.88–1.12 | 20 s | red, unstable flicker |

---

## Reliability engineering

The parts of this codebase that took the most iteration.

### Dual watchdogs

`AiTextProcessor` guards every feature-screen generation with two independent timers:

| Watchdog | Timeout | Fires when | Action |
|---|---|---|---|
| First token | 180 s | **zero** tokens arrived | stop, surface error |
| Total | 300 s | still streaming | stop, **save partial** |

The first-token watchdog is disarmed the instant a token appears, so it can never interrupt a healthy-but-slow stream. It only catches a genuinely stalled prefill.

### Partial-output preservation

The rule, applied in every path — `onCompletion`, `catch`, `try/catch`, watchdog:

> If the first token was received and output is non-blank, finish as `Partial` and save. Never replace real content with an error message.

`Partial` is a first-class UI state in the PDF and OCR screens, badged in amber rather than green.

### User-stop vs. failure

A `userStopped` flag is threaded through so the completion handler can tell a deliberate stop from a crash. Without it, pressing Stop mid-response would wipe the partial answer and replace it with "I couldn't generate a response."

### Idempotent initialization

`AIRepository.initialize()` is wrapped in a `Mutex` and short-circuits when already initialized. Six ViewModels call it in their `init` block; the model loads exactly once.

### Resource lifetimes

| Resource | Handling |
|---|---|
| Model / context | Freed under `g_gen_mutex` so no generation is in flight |
| `AssetFileDescriptor` | `.use {}` — was a leaked fd |
| Extraction temp file | `.tmp` + atomic rename; leftovers cleaned on retry |
| `MediaProjection` | One per service session; stopped only in `onDestroy` / `ACTION_STOP` |
| `VirtualDisplay` + `ImageReader` | Created and released per capture in a `finally` |
| Screenshot bitmaps | Recycled on replacement and dismissal |
| ML Kit recognizer | `close()` in `onCleared()` |
| `SpeechRecognizer` | `DisposableEffect { onDispose { destroy() } }` |
| Overlay hosts | `stop()` before `removeView`, `ViewModelStore.clear()` |

---

## Project structure

```
app/src/main/
├── AndroidManifest.xml
├── assets/models/
│   └── qwen.gguf                     1.04 GB · gitignored · download separately
├── cpp/
│   ├── CMakeLists.txt                conditional real/stub build
│   ├── infinity_jni.cpp              JNI bridge, threading, sampling
│   ├── infinity_jni_stub.cpp         safe no-op fallback
│   └── llama/                        vendored llama.cpp (~1,300 files)
│       ├── include/  src/  src/models/ (128 archs)  ggml/  common/
└── java/com/infinity/ai/
    ├── MainActivity.kt
    ├── ai/
    │   ├── engine/      LocalAIEngine.kt · LlamaEngine.kt
    │   ├── prompts/     PromptFormatter.kt            ChatML
    │   ├── repository/  AIRepository.kt               singleton
    │   ├── runtime/     LlamaJniBridge.kt             external fun + callback
    │   ├── state/       AIInferenceState.kt
    │   ├── storage/     ModelStorageManager.kt        asset → filesDir
    │   └── streaming/   TokenStreamBuffer.kt
    ├── circle/          10 files — the Circle Learn subsystem
    │   ├── InfinityOverlayService.kt   foreground service, capture, overlays
    │   ├── FloatingBubbleView.kt       draggable Compose ∞ bubble
    │   ├── RegionSelectionView.kt      drag-to-select canvas
    │   ├── OverlayComposeHost.kt       Compose without an Activity
    │   ├── CircleLearnProcessor.kt     crop → OCR → detect
    │   ├── ContentTypeDetector.kt      heuristics + 14 CircleActions
    │   ├── CircleLearnViewModel.kt
    │   ├── CircleLearnBottomSheet.kt
    │   ├── CircleLearnActivity.kt      fallback path
    │   └── OverlayPermissionHelper.kt
    ├── data/
    │   ├── ThemePreference.kt          DataStore
    │   └── library/                    Room + FTS4 (4 files)
    ├── model/           ChatMessage.kt
    ├── ocr/             OcrTextExtractor.kt · AiTextProcessor.kt
    ├── pdf/             PdfTextExtractor.kt
    ├── ui/
    │   ├── components/  AiBodyOrb.kt · Components.kt
    │   ├── navigation/  AppNavigation.kt              12 routes
    │   ├── screens/     12 screens + SharedFeatureComponents.kt
    │   └── theme/       Color.kt · Theme.kt · Type.kt
    └── viewmodel/       7 ViewModels
```

**Navigation routes:** `splash` → `dashboard` · `chat` · `tools` · `library` · `settings` (bottom nav) · `pdf_summary` · `ocr` · `screenshot` · `quiz` · `circle_learn` · `voice`

---

## Tech stack

| Layer | Choice |
|---|---|
| Language | Kotlin 2.0.21 |
| UI | Jetpack Compose · Compose BOM 2024.09.00 · Material 3 |
| Navigation | Navigation Compose 2.8.9 |
| Async | Coroutines · `StateFlow` · `callbackFlow` |
| Inference | llama.cpp / ggml, CPU backend, ARM NEON |
| Model | Qwen2.5-1.5B-Instruct, GGUF Q4_K_M, ~1.04 GB |
| OCR | ML Kit Text Recognition 16.0.1 (on-device) |
| Database | Room 2.7.1 + FTS4, KSP |
| Preferences | DataStore 1.1.7 |
| Build | AGP 8.10.1 · Gradle 8.11.1 · NDK 28.2.13676358 · CMake 3.22.1 |
| Min / Target SDK | 24 (Android 7.0) / 36 |
| ABI | `arm64-v8a` |

### Engine parameters

| Parameter | Value | Note |
|---|---|---|
| `n_ctx` | 2048 | safe on 4–6 GB devices |
| `n_batch` / `n_ubatch` | 512 | matches the prefill chunk size |
| `n_threads` | 4 | |
| `n_gpu_layers` | 0 | CPU only |
| Max output tokens | 512 | |
| Sampling | top-p 0.9 → temp 0.7 → dist | |
| Max feature input | 800 chars | see [limitations](#known-limitations) |

---

## Build & run

### Requirements

- Android Studio (Ladybug or newer)
- **Android NDK 28.2.13676358** and **CMake 3.22.1** — install via SDK Manager → SDK Tools → *Show Package Details*
- An `arm64-v8a` device or emulator, Android 7.0+
- ~4 GB free on device (1 GB APK asset + 1 GB extracted copy + headroom)
- ~6 GB free RAM on the build machine

### Setup

**1 — Clone**

```bash
git clone <repo-url>
cd "G(one) - APP"
```

**2 — Add the model** *(not in the repo; `*.gguf` is gitignored)*

Download `Qwen2.5-1.5B-Instruct` in **GGUF Q4_K_M** from Hugging Face, rename it to `qwen.gguf`, and place it at:

```
app/src/main/assets/models/qwen.gguf
```

**3 — Verify llama.cpp sources are present**

`app/src/main/cpp/llama/` is vendored in this repo and must contain `include/llama.h`. CMake checks for that file: if it's missing you get the stub build and AI features will report "engine not set up."

> ⚠️ **Do not run `setup_llama.ps1`.** It's a stale artifact targeting llama.cpp `b4570`, which used a flat file layout (`llama.cpp`, `ggml.c` at the repo root). The vendored tree here uses the modern layout (`src/`, `ggml/src/`, `src/models/`, `common/jinja/`) that `CMakeLists.txt` requires. Running the script would overwrite working sources with an incompatible set.

**4 — Build**

```bash
./gradlew assembleDebug
```

The first build compiles all of ggml plus 128 llama.cpp architecture files — expect **10–20 minutes**. Later builds are incremental.

### First launch

The 1.04 GB model is copied from assets to internal storage once, with a progress bar in the chat screen. This takes 30–90 s depending on storage speed. Every launch after that loads straight from `filesDir`.

### Enabling Circle Learn

1. Open **Circle Learn** from the dashboard or Tools
2. Grant **Display over other apps** (`SYSTEM_ALERT_WINDOW`)
3. Allow **notifications** (Android 13+) — the foreground service requires one
4. Accept the **screen capture** consent dialog
5. Tap **Start Circle Learn** — the ∞ bubble appears over every app

Stop it from the persistent notification or the in-app button.

---

## Design system

Dark-first, professional blue. Defined in `ui/theme/Color.kt`, applied through Material 3 schemes.

| Token | Dark | Light |
|---|---|---|
| Background | `#0A0E1A` | `#F8FAFC` |
| Surface | `#111827` | `#FFFFFF` |
| Surface elevated | `#1A2235` | `#F1F5F9` |
| Border | `#1E2D45` | `#E5EAF3` |
| Text primary | `#E8EDF5` | `#0F172A` |
| Text secondary | `#7A8BA8` | `#64748B` |

**Brand** `Blue500 #4F8CFF` · `Blue600 #3A7BF7` · `Blue400 #6FA8FF` · `Blue50 #EEF4FF`
**Status** Success `#10B981` · Warn `#F59E0B` · Error `#EF4444`

Patterns: bento-grid dashboard, glass cards with hairline borders, spring press-scale on every card (0.95–0.98), 220 ms crossfade route transitions, edge-to-edge with proper inset handling, custom type scale with negative tracking on headlines.

Theme choice persists in DataStore and applies instantly.

---

## Permissions

| Permission | Used for | When |
|---|---|---|
| `RECORD_AUDIO` | Voice input | On first mic tap |
| `CAMERA` | OCR capture | On first camera tap |
| `SYSTEM_ALERT_WINDOW` | Circle Learn bubble + overlays | Circle Learn setup |
| `FOREGROUND_SERVICE` + `_MEDIA_PROJECTION` | Screen capture service | Circle Learn setup |
| `POST_NOTIFICATIONS` | Required notification for the service | Circle Learn setup, API 33+ |

The app's manifest declares **no `INTERNET` permission**, and there is no networking code anywhere in the source — no HTTP client, no socket, no SDK that phones home. Inference, OCR, and storage are all local.

A `FileProvider` (`${applicationId}.provider`, `cache-path`) hands camera captures to the OCR pipeline without exposing raw file URIs.

---

## Known limitations

Documented honestly — these are real and worth knowing before filing a bug.

### Design constraints

**800-character input cap.** `OcrTextExtractor`, `PdfTextExtractor`, and `AiTextProcessor` all cap extracted text at 800 characters (~200 tokens). With a 2048-token context, a 512-token output budget, ChatML scaffolding, and the 3–4× token inflation of PDF artifacts, this is the safe ceiling for single-shot generation. **Practical effect: a long PDF is summarized from roughly its first section, not the whole document.** Chunked map-reduce summarization is the fix and is not implemented.

**No conversation token budget.** `PromptFormatter` takes the last 20 messages unconditionally. A long chat can exceed `n_ctx = 2048`, which surfaces as `Prompt decode failed`. Start a new chat to recover.

**Single concurrent generation.** `g_gen_mutex` serializes inference by design. Starting a generation on a second screen queues behind the first rather than failing fast, and the shared `AIInferenceState` will reflect whichever is active.

**Text-based PDFs only.** Scanned/image PDFs return an explanatory error. Running them through the OCR path is not wired up.

**Latin script OCR.** ML Kit's default recognizer only. Chinese/Devanagari/Japanese/Korean models aren't bundled.

**Rectangle selection only** in Circle Learn. `RegionSelectionView` is structured for freehand (store a `Path` in `ACTION_MOVE`) but ships with rectangles.

### Known bugs

| Issue | Impact | Location |
|---|---|---|
| User message duplicated in every prompt — `historyForPrompt` keeps the just-added user message *and* it's passed again as `userInput`, producing two identical `<|im_start|>user` turns | Wasted context, degraded output | `ChatViewModel.generateReply` / `startFromSuggestion` |
| `SettingsScreen` calls `viewModel()` internally, creating a second `NavBackStackEntry`-scoped `ChatViewModel`; leaving Settings triggers its `onCleared()` → `repository.unload()`, freeing the model for every other screen | Model reloads unexpectedly | `SettingsScreen.kt:34` |
| `composable("circle_learn")` registered twice | Dead code; second overwrites first | `AppNavigation.kt:216,223` |
| `Responding.partialText` is never populated — `LlamaEngine` always constructs `Responding()` | Voice transcript card never renders | `LlamaEngine.onToken` |
| Voice results route through `startFromSuggestion`, which replaces the whole message list | Speaking clears chat history | `AppNavigation` speech listener |
| `withTimeout` wraps the blocking JNI `loadModel`, so it can't interrupt; if it ever trips, `initializationError` is permanent | Requires app restart | `AIRepository.initialize` |
| No `top_k` and no repetition penalty in the sampler chain; `top_p` is applied before `temp` | 1.5B model can loop | `infinity_jni.cpp` |
| `themes.xml` still uses the old brown `#0D0A08` for window/status/nav bars | Brown flash on cold start | `res/values/themes.xml` |

### Performance expectations

On a mid-range device (Snapdragon 6-series class, 6 GB RAM):

| Phase | Time |
|---|---|
| First-launch model extraction | 30–90 s, once |
| Model load into RAM | 3–8 s per cold start |
| Prefill (800-char input) | 5–20 s |
| Generation | ~2–6 tokens/sec |

The 3-minute first-token and 5-minute total watchdogs are sized for the low end of this range, not the average.

---

## Troubleshooting

<details>
<summary><b>"AI engine not set up. Run setup_llama.ps1"</b></summary>

CMake fell back to the stub because `app/src/main/cpp/llama/include/llama.h` wasn't found. Restore the vendored `llama/` directory — do **not** run `setup_llama.ps1`, see the [build notes](#setup). Then Build → Clean Project and rebuild.
</details>

<details>
<summary><b>"Failed to load AI model"</b></summary>

Usually a missing or truncated `qwen.gguf`. Confirm the file is at `app/src/main/assets/models/qwen.gguf` and roughly 1.04 GB. A valid GGUF starts with the ASCII magic `GGUF`. Also check free space on device — extraction needs ~1 GB beyond the APK.
</details>

<details>
<summary><b>"Prompt decode failed"</b></summary>

Context overflow — the prompt exceeded 2048 tokens. Start a new chat, or use a shorter input.
</details>

<details>
<summary><b>Cannot lock execution history cache … already been locked by this process</b></summary>

A Gradle daemon has a wedged lock on `.gradle/<version>/executionHistory`. Restarting Studio does **not** fix it, because Studio reconnects to the same surviving daemon.

```powershell
./gradlew --stop
# if that doesn't clear it, kill the daemon directly:
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -like "*GradleDaemon*" } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

Remove-Item ".gradle\8.11.1\executionHistory" -Recurse -Force
Remove-Item ".gradle\8.11.1\fileChanges\last-build.bin" -Force
```

Then re-sync. A clean Gradle lock file is 17 bytes; a stale one is larger because it still holds an owner record.

The usual trigger is memory pressure killing a build mid-write. This project's native build spawns many parallel clang processes on top of the Gradle and Kotlin daemons — free up RAM before building.
</details>

<details>
<summary><b>Native build fails with odd or truncated paths</b></summary>

This project's path contains parentheses. CMake and Ninja normally handle that, but if you see a mangled path in the error, move the project to a path with no parentheses or spaces and rebuild.
</details>

<details>
<summary><b>Circle Learn bubble doesn't appear</b></summary>

All four grants are required: overlay permission, notifications (API 33+), screen capture consent, and a running foreground service. The entry screen shows live status cards for the first two. If the bubble vanishes after use, the service was killed — restart it from the entry screen. Battery optimization can also kill it; exempt the app if it keeps happening.
</details>

<details>
<summary><b>OCR returns "No text detected"</b></summary>

ML Kit needs reasonably sized, high-contrast Latin text. Very small fonts, heavy skew, low contrast, or non-Latin scripts will fail. For Circle Learn, select a tighter region around the text.
</details>

---

<div align="center">

**Infinity** · v1.0.0 · Engine `Infinity-X1`
Qwen2.5-1.5B-Instruct · llama.cpp · 100% on-device

*No network. No accounts. No telemetry. Your data never leaves the device.*

</div>
