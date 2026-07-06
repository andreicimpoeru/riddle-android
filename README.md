# riddle-android

An Android port of [Riddle](https://github.com/MaximeRivest/Riddle) for Boox and other e-ink tablets. Write on the page with your stylus; after a pause the ink dissolves and a reply writes itself back stroke by stroke.

Inspired by the reMarkable Paper Pro diary demo — rebuilt for Android with Onyx pen input and a two-step vision pipeline (OCR → reply).

## Features

- Full-screen ink canvas with stylus pressure and eraser
- Onyx Boox `TouchHelper` integration (MotionEvent fallback on other devices)
- Two-step oracle: handwriting OCR, then text reply
- OpenAI-compatible API (OpenAI, Ollama, vLLM, LocalAI, OpenRouter, etc.)
- Animated reply in Dancing Script (Zhang–Suen stroke tracing)

## Requirements

- Android Studio or Android SDK (API 34)
- Boox tablet (optional; works on any Android device with a stylus for testing)
- A vision-capable model for step 1 (OCR) and any chat model for step 2

## Setup

1. Clone the repo.
2. Copy `local.properties.example` to `local.properties` and set `sdk.dir` to your Android SDK path.
3. Open in Android Studio, or build from the command line:

```bash
./gradlew assembleDebug
```

4. Install `app/build/outputs/apk/debug/app-debug.apk` on your tablet.
5. In the app settings, set:
   - **API base** — e.g. `http://192.168.1.50:11434/v1` (Ollama) or `https://api.openai.com/v1`
   - **API key** — optional for local servers
   - **Model** — your vision model name (e.g. `gemma-3-12b-it`)

## Build with Flatpak Android Studio

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
    → step 2: text reply (concise assistant)
    → reply strokes animated onto the page → fade
```

## License

MIT — see [LICENSE](LICENSE). Dancing Script font is SIL Open Font License 1.1.

Not affiliated with reMarkable AS, Onyx, or Warner Bros.