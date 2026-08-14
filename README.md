# Hermes Quick Prompt

A small Android app that lets you fire a text prompt at a self-hosted
[Hermes Agent](https://github.com/NousResearch/hermes-agent) instance from
anywhere on your phone, in two taps.

## What it is

Hermes Quick Prompt acts as your phone's default Digital Assistant. Long-press
the power button (or hold Home on gesture navigation) and a prompt sheet pops
up immediately. Type your question, hit Send, and the answer streams back from
your Hermes instance — without opening a browser or switching apps.

## Build

### Requirements

- JDK 21 (`JAVA_HOME` must point to a JDK 21 installation)
- Android SDK with platform-35 and build-tools 35+
- `ANDROID_HOME` pointing to the SDK root

### Build the APK

```bash
export JAVA_HOME=/path/to/jdk21
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Run unit tests

```bash
./gradlew testDebugUnitTest
```

## Setting the app as your default Digital Assistant

1. Install the APK on your device.
2. Go to **Settings → Apps → Default apps → Digital assistant app**.
3. Select **Hermes Quick Prompt**.
4. Long-press the power button (or hold the Home button) — the prompt sheet
   will appear.

On some devices this path is:
- Samsung: **Settings → Advanced features → Side key → Double press → Open app**
  *(does not use the Digital Assistant slot; use the Digital Assistant setting)*
- Pixel: **Settings → Apps → Default apps → Digital assistant app → Hermes Quick Prompt**

## Pointing it at a Hermes instance

1. Open the app normally from the launcher (tap the icon).
2. Enter your Hermes server address, e.g. `https://hermes.example.com` or
   `http://192.168.1.10:8080` for a local LAN instance.
3. Enter your API key (stored securely in DataStore, never logged or exported).
4. Optionally specify a model name (e.g. `openrouter/anthropic/claude-sonnet-4`).
5. Tap **Test connection** to verify, then **Save**.

## Hermes API reference

See the [Hermes Agent API docs](https://github.com/NousResearch/hermes-agent)
for the full API contract. This app uses:

| Endpoint | Method | Purpose |
|---|---|---|
| `/v1/runs` | POST | Start a new agent run |
| `/v1/runs/{run_id}/events` | GET (SSE) | Stream run events |
| `/v1/runs/{run_id}/stop` | POST | Cancel a run |
| `/v1/health` | GET | Health check (no auth) |

## Architecture

```
app/src/main/java/dev/hermesprompt/app/
  HermesPromptApp.kt          — Application + AppContainer (manual DI)
  data/
    HermesApi.kt              — OkHttp API client (SSE streaming)
    SseParser.kt              — Pure SSE frame parser (unit-tested)
    RunState.kt               — Sealed run state machine
    SettingsStore.kt          — DataStore persistence
    SettingsValidator.kt      — URL normalization/validation (unit-tested)
  ui/
    MainActivity.kt           — Intent dispatch (ASSIST vs launcher)
    prompt/
      PromptSheetScreen.kt    — Bottom sheet Compose UI
      PromptViewModel.kt      — Run lifecycle state machine
    settings/
      SettingsScreen.kt       — Settings form
      SettingsViewModel.kt    — Settings load/save/test
    theme/
      Theme.kt, Type.kt, Color.kt
```

## License

Apache 2.0 — see [LICENSE](LICENSE).
