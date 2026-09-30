# G-one — revised tech stack

Prepared 30 September 2026 against the Android version catalog, hardware guide and companion web application's package manifest.

| Layer | Technologies | Role |
|---|---|---|
| Native Android | Kotlin, Jetpack Compose, Material 3, coroutines/Flow | App interface and asynchronous state |
| Local AI | Qwen2.5-1.5B-Instruct Q4_K_M GGUF, llama.cpp/ggml, C++/JNI | Phone-side language inference; deterministic rules remain responsible for anomaly detection |
| OCR | ML Kit Text Recognition | Local text extraction |
| Local data | Room/SQLite, DataStore, app-private files | Records, settings and model/document files; not an encrypted-database claim |
| Web companion | Next.js, React, TypeScript, Tailwind CSS, Three.js | Web presentation, interactive models and emergency viewer |
| Optional hosted sharing | Vercel, Supabase Storage | Consented emergency snapshots; not cloud health inference |
| Wearable | ESP32-S3, built-in BLE, Arduino tooling | Acquisition and BLE transport |
| Sensors | MAX30100, DS18B20, MPU6050, EMG module/electrodes | Pulse/SpO₂, skin temperature, motion and muscle activity |
| Interfaces | I²C, 1-Wire, ADC | Sensor buses and analog acquisition |
| Documented power option | LiPo, TP4056, 3.3 V LDO | Hardware-guide arrangement, not evidence of battery runtime |
| Optional services | Open-Meteo/CAMS AQI, Android SMS/calling, NFC/QR links | Regional environmental context and consented emergency assistance |
| Build/design | Android Studio, Gradle, CMake, Arduino tooling, Blender | App/native builds, firmware and hardware visualization |
| Test utilities | Python | Wearable protocol emulator; not the production backend |

## Corrections to the supplied graphic

- Replace MAX30102 with MAX30100 for current prototype firmware.
- Replace HC-05 with ESP32-S3's built-in Bluetooth LE; include the missing ESP32-S3 and EMG module.
- Remove microSD from current implementation: firmware is live-only and loses disconnected readings.
- Replace Firebase with the actual optional emergency-storage path, Supabase Storage.
- Separate React/TypeScript/Next.js web technologies from native Kotlin/Compose Android.
- Add the local model, JNI runtime, OCR and persistence layers.
- Do not present Python as the application backend or Docker as a required deployment dependency.

## Artwork

The revised graphic uses the supplied infographic as its visual reference. Generated with the built-in image-generation tool; the exact prompt is saved alongside the image as `g-one-tech-stack-revised-prompt.txt`.
