# Riddle Diary (Android)

An Android port of [Riddle](https://github.com/MaximeRivest/Riddle), **built for Onyx Boox e-ink tablets**. Write on the page with your stylus; after a pause the ink dissolves and a reply writes itself back stroke by stroke.

Inspired by the reMarkable Paper Pro diary demo — rebuilt for Boox with Onyx pen input and a two-step vision pipeline (OCR → reply).

## Download

Pre-built APK for quick testing (no compiler needed):

**[Download Riddle Diary (APK)](https://github.com/andreicimpoeru/riddle-android/releases/latest/download/Riddle-Diary.apk)** (latest release)

Sideload to your Boox, then configure your API settings in the app.

## Tested on

- **Device:** Onyx Boox (stylus + e-ink)
- **Model:** self-hosted **Gemma 4 12B QAT** (vision) via OpenAI-compatible API (Ollama / local server)
- **Setup:** two-step pipeline — OCR the handwriting image, then generate a text reply

Your mileage may vary with other Boox models, firmware versions, or vision models. Handwriting OCR quality depends heavily on the model.

## Features

- Full-screen ink canvas with stylus pressure and eraser
- Onyx Boox `TouchHelper` integration (MotionEvent fallback on other devices)
- Two-step oracle: handwriting OCR, then text reply
- OpenAI-compatible API (OpenAI, Ollama, vLLM, LocalAI, OpenRouter, etc.)
- Animated reply in Dancing Script (Zhang–Suen stroke tracing)

## Requirements

- **Onyx Boox tablet** with stylus (primary target)
- Wi‑Fi access to your inference server (or cloud API)
- A **vision-capable** model for step 1 (OCR) and any chat model for step 2

For building from source: Android Studio or Android SDK (API 34).

## Quick start (Boox)

1. Download **Riddle Diary** (`Riddle-Diary.apk`) from [Releases](https://github.com/andreicimpoeru/riddle-android/releases/latest).
2. Install on your Boox (enable sideloading / unknown sources if needed).
3. Open **The Diary** settings and configure:
   - **API base** — e.g. `http://192.168.1.50:11434/v1` (Ollama on your LAN)
   - **API key** — leave blank for most self-hosted servers
   - **Model** — your vision model name (e.g. `gemma-4-12b-qat`)
4. Tap **Open the diary**, write, rest your pen, and wait for the reply.

## Build from source

1. Clone the repo.
2. Copy `local.properties.example` to `local.properties` and set `sdk.dir` to your Android SDK path.
3. Build:

```bash
./gradlew assembleDebug
```

4. Install `app/build/outputs/apk/debug/app-debug.apk` on your tablet.

### Build with Flatpak Android Studio

```bash
flatpak run --command=sh com.google.AndroidStudio -c '
  export JAVA_HOME=/app/extra/jbr
  export PATH=$JAVA_HOME/bin:$PATH
  export ANDROID_HOME=$HOME/Android/Sdk
  cd /path/to/riddle-android
  ./gradlew assembleDebug
'
```

## How it works

```
pen input → ink surface → idle 2.8s → PNG
    → step 1: vision OCR (transcribe handwriting)
    → step 2: text reply
    → reply strokes animated onto the page → fade
```

## License

MIT — see [LICENSE](LICENSE). Dancing Script font is SIL Open Font License 1.1.

Not affiliated with reMarkable AS, Onyx, or Warner Bros.