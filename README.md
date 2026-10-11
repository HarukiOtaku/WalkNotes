# WalkNotes

English · [繁體中文](README.zh-Hant.md)

On-device walking notebook for Android. You walk, the fog lifts, you write a short note. An **open-weight Gemma model on the phone** can draft the note from the photo and/or your keywords. After the one-time model download, the walk itself does not need a server.

Hacktoberfest 2026, Week 1.

## Why it stays on the phone
- **The map of where you walked is the most sensitive data.** It is stored as ~44 m cells in the app's own directory and is never uploaded.
- **Photos and notes stay on the device.** Backup is a zip you export yourself (notes, photos, walked cells, tracks). Model weights are not in the zip.
- **No API key for inference.** LiteRT-LM + Gemma run locally.

## What it does
- Full-screen map (OpenFreeMap street tiles, or an offline fog grid)
- Record a walk with a live timer and optional persistent notification
- Notes with optional photo, search, date / photo filters, edit, map pin clusters
- Replay walks by day / month / year / all, and share an on-device MP4 of a path
- In-app language: Cantonese / Traditional Chinese / Simplified Chinese / English

## Repository layout
```
core/        Kotlin/JVM library — logic + unit tests (no Android)
cli/         JVM harness: the same .litertlm file on a desktop
androidApp/  Android 8.0+, arm64, LiteRT-LM 0.18.0
docs/branding/  launcher icon sources (kept; not required to build)
```

Application id is still `com.civicwalk.app` (the project started as CivicWalk and pivoted).

## Models (not in this repo)
| id | file | about |
|---|---|---|
| Gemma 4 E2B | `gemma-4-E2B-it.litertlm` (~2.59 GB) | public download; can look at the photo |
| Gemma 3 1B | `gemma3-1b-it-int4.litertlm` (~584 MB) | Hugging Face gated; text only |
| Gemma 3 270M | `gemma3-270m-it-q8.litertlm` (~304 MB) | Hugging Face gated; text only |

Gemma Terms of Use apply to the weights. 1B / 270M need a Hugging Face account that has accepted the Gemma licence, plus a **Read** token pasted in Settings. Do not commit tokens.

## Build
```bash
gradle :core:test
gradle :androidApp:assembleDebug
```

Needs JDK 21 and an Android SDK (`JAVA_HOME` / `ANDROID_HOME` if they are not already on `PATH`). Known-good: Gradle 9.8.0, AGP 9.x with built-in Kotlin (do **not** apply `org.jetbrains.kotlin.android`), LiteRT-LM 0.18.0. Debug APK is arm64-v8a only.
