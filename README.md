# CivicWalk — on-device civic reporting (Hacktoberfest 2026, Week 1 “Touch Grass”)

Photograph a street problem; the **open-weight Gemma model running on the phone** writes the
1823 report. After a one-time model download the app needs no network at all: the photo, the
coordinates and the draft never leave the device.

## Why open matters here (the claim this project is built around)
- **Runs with no signal.** Inference is on-device (LiteRT-LM + Gemma), so it works in a back
  alley, a lift or on a trail where a cloud API simply fails.
- **The photo never leaves the phone.** A civic report contains your exact position and often
  your neighbours' faces or plates. A cloud draft-writer must upload the original image; this
  does not.
- **Zero marginal cost.** No API key, no per-request billing, nothing to meter.
- **The map of where you have walked is the most sensitive data the app touches.** It is stored
  as ~44 m cells in the app's own directory and never uploaded.

## Repository layout
```
core/        Kotlin/JVM library — pure logic + unit tests (runs anywhere, no Android needed)
cli/         JVM harness: runs the SAME .litertlm model file on a desktop/laptop
androidApp/  the Android app (Android 8.0+, arm64), LiteRT-LM 0.18.0 + Gemma 4 E2B
```

## Verified state (2026-10-07)
| item | evidence |
|---|---|
| core logic | `gradle :core:test` → **9/9 passed** (UTF-8 truncation to the 1823 form's 1500-byte limit) |
| same model on a desktop | `gradle :cli:run --args=…gemma-4-E2B-it.litertlm` → **110 Chinese chars in 17.95 s (6.13 chars/s), CPU only, model load 5.5 s**, no OOM on a 3.8 GB VM |
| APK builds | `:androidApp:assembleDebug` → **24 MB**, `apksigner verify` OK (debug key), arm64-v8a, minSdk 26 / targetSdk 35 |
| not yet verified | **on-device run** — no phone was available to the build machine; expect 1–2 fix rounds on first install |

Model file (not in this repo): `litert-community/gemma-4-E2B-it-litert-lm` → `gemma-4-E2B-it.litertlm`
2,588,147,712 B, sha256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` (Gemma Terms of Use apply).

## Build
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=$HOME/tools/android-sdk
gradle :core:test                 # logic
gradle :cli:run --args="$HOME/models/gemma-4-E2B-it.litertlm"   # real model, desktop
gradle :androidApp:assembleDebug  # APK
```
Toolchain that is known to work: JDK 21, Gradle 9.8.0, AGP 9.4.1, Kotlin 2.4.0, compileSdk 35.
Three traps that cost time (all documented in the commit history):
1. AGP 9 has **built-in Kotlin** — applying `org.jetbrains.kotlin.android` is a hard error.
2. LiteRT-LM 0.18.0 ships **Kotlin 2.4.0 metadata** while AGP 9's built-in Kotlin is 2.2.10 —
   raise KGP on the buildscript classpath or nothing compiles.
3. Shipping every ABI made the APK 52 MB; `abiFilters += "arm64-v8a"` brought it to 24 MB.

## On-device model placement
`/sdcard/Android/data/com.civicwalk.app/files/gemma-4-E2B-it.litertlm`
— either the in-app **Download model** button, or
`adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.civicwalk.app/files/`

## Design notes found while building
- **The model mis-names the district.** Asked to write the report for coordinates in Sham Shui Po,
  Gemma 4 E2B wrote “沙田區” (Sha Tin). The app therefore never lets the model invent a district —
  the report body carries only what is visible, and the location line is filled from the device.
- **The 1823 form truncates at 1500 bytes**, so the draft is cut on a code-point boundary
  (`core/TextTruncate`), never mid-character.
- **Photo archive copy:** re-encoding through a canvas/bitmap drops EXIF including GPS — the
  record keeps the capture time and coordinates as evidence while the file itself is clean.
