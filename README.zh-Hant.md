# WalkNotes

[English](README.md) · 繁體中文

Android 本機散步筆記。你走路、霧散開、寫下一則短筆記。手機上的**開源權重 Gemma 模型**可依相片及／或關鍵詞起草筆記。一次性下載模型之後，散步本身不需要伺服器。

Hacktoberfest 2026，第 1 週。

## 為何留在手機裡
- **你走過哪些地方的地圖，是最敏感的資料。**以約 44 公尺的格子存在應用程式自己的目錄，永不上傳。
- **相片與筆記留在本機。**備份是你自行匯出的 zip（筆記、相片、走過的格子、軌跡）。模型權重不在 zip 內。
- **推論不需要 API 金鑰。**LiteRT-LM 與 Gemma 在本機執行。

## 功能
- 全螢幕地圖（OpenFreeMap 街道圖磚，或離線霧格）
- 記錄散步，含即時計時與可選的常駐通知
- 筆記可附相片，可搜尋、依日期／相片篩選、編輯，地圖圖釘會叢集
- 依日／月／年／全部回放散步，並在本機將路徑匯出為可分享的 MP4
- 應用程式內語言：粵語／繁體中文／簡體中文／English

## 倉儲結構
```
core/        Kotlin/JVM 函式庫 — 邏輯與單元測試（不含 Android）
cli/         JVM 測試工具：在桌面使用同一份 .litertlm 檔
androidApp/  Android 8.0+、arm64、LiteRT-LM 0.18.0
docs/branding/  啟動器圖示來源（保留；建置不必使用）
```

應用程式識別碼仍為 `com.civicwalk.app`（專案起初名為 CivicWalk，後來轉向）。

## 模型（不在本倉儲）
| id | file | about |
|---|---|---|
| Gemma 4 E2B | `gemma-4-E2B-it.litertlm`（約 2.59 GB） | 公開下載；可以查看相片 |
| Gemma 3 1B | `gemma3-1b-it-int4.litertlm`（約 584 MB） | Hugging Face 有閘門；僅文字 |
| Gemma 3 270M | `gemma3-270m-it-q8.litertlm`（約 304 MB） | Hugging Face 有閘門；僅文字 |

Gemma 使用條款適用於權重。1B／270M 需要已同意 Gemma 授權的 Hugging Face 帳號，並在設定中貼上 **Read** 權杖。請勿將權杖提交到版本庫。

## 建置
```bash
gradle :core:test
gradle :androidApp:assembleDebug
```

需要 JDK 21 與 Android SDK（若尚未在 `PATH` 中，請設定 `JAVA_HOME`／`ANDROID_HOME`）。已知可用：Gradle 9.8.0、內建 Kotlin 的 AGP 9.x（**不要**套用 `org.jetbrains.kotlin.android`）、LiteRT-LM 0.18.0。Debug APK 僅含 arm64-v8a。
