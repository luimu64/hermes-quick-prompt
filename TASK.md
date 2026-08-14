# Hermes Quick Prompt — Android app build brief

Build a complete, production-quality Android app from scratch in this directory.
Follow this brief exactly. Do not ask questions; make reasonable engineering
decisions consistent with the brief. Verify the build compiles before finishing.

## Product

"Hermes Quick Prompt" — a small Android app that lets the user fire a text
prompt at a self-hosted Hermes Agent instance (https://github.com/NousResearch/hermes-agent)
from anywhere on the phone, in two taps. Think "quick capture for your AI agent".

User stories:
1. The user summons the assistant (long-press the power button, i.e. the
   Android "assistant" invocation) and a bottom popup sheet appears with a
   text box and a Send button.
2. The user types a prompt, hits Send. A loading indicator shows while the
   prompt executes on the configured Hermes instance. When the agent finishes,
   the answer text appears in the sheet.
3. Tapping anywhere outside the popup sheet (or pressing Back) closes the sheet
   and cancels the in-flight interaction (the remote run is stopped).
4. A minimal Settings screen lets the user configure the Hermes server address
   and API key (and optionally a model name). The app remembers settings.

The app must be generic and publicly releasable: neutral branding, no
hardcoded secrets, no device-specific hacks, clean package, README + LICENSE.

## Non-negotiables

- Jetpack Compose (Material 3), Kotlin, single-module app.
- minSdk 26, targetSdk 35, compileSdk 35.
- No Firebase, no analytics, no ads, no third-party SDKs beyond the standard
  Jetpack/OkHttp/Kotlinx stack. INTERNET permission only.
- DRY, modular architecture. Small well-named classes with single
  responsibilities. No god objects, no copy-paste. Keep it lean — this is a
  small app; don't cargo-cult in 20 layers.
- No secrets in code or in git. API key lives in DataStore, never in BuildConfig
  unless it's a placeholder.
- The app talks to Hermes over HTTPS by default but must also work over plain
  HTTP (self-hosted LAN instances are a first-class use case) — so the base URL
  setting accepts `http://` or `https://` and cleartext traffic is allowed only
  when the configured URL is `http://` (use a network security config that
  permits cleartext, scoped appropriately, or an equivalent mechanism).
- Unit tests for the pure logic (URL normalization, SSE frame parsing, run-state
  machine). They must pass with `./gradlew testDebugUnitTest`.
- A README.md (what it is, how to build, how to set the app as the default
  digital assistant app, how to point it at a Hermes instance, API docs link)
  and an Apache-2.0 LICENSE.

## Summon mechanism (power button long-press)

The clean, Play-Store-safe way: the app registers an activity that handles
`android.intent.action.ASSIST`. The user sets the app as their **default
digital assistant app** (Settings → Apps → Default apps → Digital assistant).
From then on, long-pressing the power button (or holding Home on gesture nav)
launches the app's activity directly. This is exactly how apps like Alexa do
it — no SYSTEM_ALERT_WINDOW, no accessibility service, no root.

Implementation requirements:
- The launcher entry point opens the Settings screen (first-run experience:
  user must configure the server before the prompt sheet is useful).
- `MainActivity` inspects its intent. If the intent action is
  `android.intent.action.ASSIST` / `android.intent.action.VOICE_ASSIST` (or the
  activity was launched with `EXTRA_ASSIST_CONTEXT`), it shows the **prompt
  sheet** directly, full-screen translucent activity, sheet anchored to the
  bottom, dimmed scrim behind it. Otherwise (normal launcher launch) it shows
  the Settings screen.
- The sheet must auto-focus and pop the IME (keyboard) when summoned.
- On launch via ASSIST, if the server is not configured yet, show a hint in the
  sheet telling the user to open the app and configure it (with a button that
  opens Settings).

## Hermes API contract (verified against the real server — do not invent fields)

Base URL the user configures, e.g. `https://hermes.example.com`. All requests
send `Authorization: Bearer <API_KEY>`.

### Start a run
`POST {base}/v1/runs`
Body:
```json
{ "input": "the user's prompt" }
```
Optional body fields: `"model": "<model name>"` (omit when not configured),
`"session_id": "<stable id>"` (use a fixed per-app session id like
"hermes-quick-prompt" so the agent keeps context across prompts — fine to
omit if it complicates things).
Response: HTTP 202, JSON `{"run_id": "run_<hex>", "status": "started"}`.

### Run events (SSE stream — this is how the app receives the answer)
`GET {base}/v1/runs/{run_id}/events`
- Response: `text/event-stream`.
- Each frame: `data: {json}\n\n` — the JSON always contains `"event"`,
  `"run_id"`, `"timestamp"`, plus event-specific fields. There is no separate
  `event:` line; the event name is inside the JSON payload.
- Event names and payload fields:
  - `message.delta` → `{"delta": "<text chunk>"}` — accumulate for live text
  - `reasoning.available` → `{"text": "<reasoning>"}` — ignore for display
  - `tool.started` / `tool.completed` / `tool.failed` / `tool.progress` — ignore
  - `assistant.completed` → `{"content": "<final answer text>", "completed": true}`
  - `run.completed` → `{"output": "<final answer text>", "usage": {...}}` — the
    authoritative final answer
  - `error` → `{"message": "..."}`
  - `done` → stream is over
  - `run.started`, `message.started` — ignore
- Stream ends with `: stream closed` comment frame. Keepalives may appear
  (`: keepalive`). An HTTP 404 on this endpoint right after POST is a race —
  retry briefly.

### Cancel a run (used when the user dismisses the sheet mid-run)
`POST {base}/v1/runs/{run_id}/stop` — fire-and-forget; ignore errors.

### Health check (used by Settings "Test connection")
`GET {base}/v1/health` → `{"status": "ok", ...}` — no auth required.

### Error handling rules
- Non-2xx on POST /v1/runs: show the error message from the JSON body
  (`error.message` when present) in the sheet, with the HTTP status.
- Network failures: show a friendly message ("Cannot reach server").
- Any of these states end the loading state: `run.completed` (success, show
  `output`), `error` event (show message), stream EOF without completion
  (show "Connection lost"), HTTP error (show message).

## Screen & behavior spec

### Prompt sheet (the popup)
- Full-screen translucent activity (dimmed scrim). Sheet is a rounded-top
  Surface anchored to the bottom, width = full screen, wraps content height
  (max ~60% of screen, scrollable if taller).
- Contents:
  - Small header row: app name / "Hermes" label + a settings gear icon
    (opens Settings) — optional but nice.
  - `OutlinedTextField`, multi-line (min 1 line, max ~6), placeholder
    "Ask Hermes anything…", auto-focused with IME shown, IME action = Send
    (with imeAction=Send) when single-line content fits.
  - Send button (IconButton or Button, FilledTonal). Disabled when the text is
    blank or a run is in flight.
  - Idle state: just the input + send.
  - Loading state: replace the send button with a small `CircularProgressIndicator`
    and show live accumulated text below the input as it streams (nice-to-have;
    required: a clear loading indicator). A small "Stop" affordance in the
    header is a bonus (cancels the run + closes) — implement if trivial.
  - Result state: the final answer text below the input (SelectableText or
    scrollable text), and the sheet stays open so the user can read it. Send is
    re-enabled (the sheet stays interactive — user can send another prompt or
    dismiss by tapping outside / Back).
- Dismissal:
  - Tapping the scrim (outside the sheet) closes the activity.
  - Back button closes the activity.
  - If a run is in flight when dismissed: cancel the coroutine, close the SSE
    connection, fire-and-forget `POST /v1/runs/{run_id}/stop`, and clear
    loading state. No crash, no leak (ViewModel `viewModelScope` cancelled).
  - If the keyboard is open and the user taps outside, dismiss (standard
    `ModalBottomSheet`-like behavior; a plain Surface + scrim Box works fine —
    don't pull in `ModalBottomSheet` API unless it behaves correctly; the key
    requirement is: tap outside → close+cancel).

### Settings screen
- Launcher home screen. Fields:
  - Server address (OutlinedTextField, `KeyboardType.Uri`): e.g.
    `https://hermes.example.com`. Normalize on save: trim, strip trailing `/`,
    strip any path, must start with `http://` or `https://` (prepend `https://`
    if no scheme given).
  - API key (OutlinedTextField, password toggle, `KeyboardType.Password`).
  - Model (optional, free text): e.g. `openrouter/anthropic/claude-sonnet-4`.
    Empty = server default.
  - "Test connection" button → hits `/v1/health`; shows success/error inline.
  - "Save" button (or save-on-exit via DataStore) + a note that the prompt
    sheet is summoned by setting this app as the default assistant app.
- Validation errors shown inline (invalid URL, empty address).

## Architecture (keep it modular but lean)

```
app/src/main/java/dev/hermesprompt/app/
  HermesPromptApp.kt          — Application: builds the AppContainer (manual DI)
  data/
    HermesApi.kt              — OkHttp-based client: startRun(), runEvents() (SSE),
                                stopRun(), health(). One class, thin, no UI deps.
    SseParser.kt              — pure function: ByteString/line stream -> parsed
                                events (data: lines with JSON). Unit-testable.
    RunState.kt               — sealed class: Idle, Running(runId, text), Done(text),
                                Error(message). Plus an accumulator for deltas.
    SettingsStore.kt          — DataStore<Preferences>: serverUrl, apiKey, model.
    SettingsValidator.kt      — pure URL normalization/validation. Unit-testable.
  ui/
    MainActivity.kt           — intent dispatch (ASSIST vs launcher), hosts screens
    prompt/
      PromptSheetScreen.kt    — scrim + bottom sheet composable
      PromptViewModel.kt      — state machine for the run lifecycle
    settings/
      SettingsScreen.kt       — settings form
      SettingsViewModel.kt    — loads/saves settings, test-connection
    theme/
      Theme.kt, Type.kt, Color.kt — Material 3 theme (default dynamic color if
                                    available, sensible fallback palette)
```

- Manual DI via an `AppContainer` (Application-scoped), constructor injection
  into ViewModels via a simple ViewModelProvider.Factory. No Hilt — it's a two
  screen app; Hilt would be ceremony.
- Repository pattern NOT required — the ViewModel can talk to HermesApi
  directly through the AppContainer; adding a repository that only forwards
  calls is the kind of DRY theater this brief rejects.
- Networking: OkHttp 4.12.x. SSE via a streaming OkHttp call (enqueue + read
  response body line-by-line on Dispatchers.IO), parse frames, emit into a
  Flow. Retrofit not required (and SSE in Retrofit is awkward); OkHttp direct
  is fine and lean. kotlinx-serialization for JSON.
- Coroutines/Flow for everything async. viewModelScope for the sheet VM.

## Build setup (the environment is already provisioned — use it)

Environment (already set up on this machine; the build will only work with these):
- `JAVA_HOME=/opt/data/android-toolchain/jdk-21.0.12+8` — export it in every
  shell command that runs gradle.
- `ANDROID_HOME=/opt/data/android-toolchain/sdk` — same.
- Gradle 8.13 installed at `/opt/data/android-toolchain/gradle-8.13`.
  Generate the wrapper: run `/opt/data/android-toolchain/gradle-8.13/bin/gradle wrapper --gradle-version 8.13`
  once, then use `./gradlew` for everything (so the repo is self-contained).
- Use a local `local.properties` with `sdk.dir=/opt/data/android-toolchain/sdk`
  (this file is gitignored) OR rely on ANDROID_HOME env — prefer env, but
  either works.

Version pins (known-good, stable — do not chase latest):
- AGP 8.7.3, Kotlin 2.0.21, `org.jetbrains.kotlin.plugin.compose` 2.0.21
- Compose BOM 2024.12.01, activity-compose 1.9.3, lifecycle 2.8.7,
  navigation not required (two screens — simple state switch in MainActivity;
  keep it lean), datastore-preferences 1.1.1, core-ktx 1.15.0,
  okhttp 4.12.0, kotlinx-serialization-json 1.7.3, kotlinx-coroutines 1.9.0
- junit 4.13.2 + kotlinx-coroutines-test for unit tests.
- Package `dev.hermesprompt.app`, applicationId `dev.hermesprompt.app`,
  versionCode 1, versionName "1.0.0", app name "Hermes Quick Prompt".
- minifyEnabled false for debug; for release build set minifyEnabled true with
  default proguard-rules (keep file minimal, no special rules needed).
- `namespace` = `dev.hermesprompt.app`.
- Manifest: `MainActivity` with `android:exported="true"`, `launchMode="singleTask"`,
  intent-filters: (1) launcher MAIN/LAUNCHER, (2) `android.intent.action.ASSIST`
  with DEFAULT category. Set `android:supportsRtl`, `android:allowBackup="true"`.
  Theme: a translucent theme for the sheet path is handled by swapping
  `windowIsTranslucent` via a second theme used with `Theme.Material3`-based
  styles — implement cleanly: base app theme (opaque) + `Theme.HermesPrompt.Overlay`
  (translucent, no action bar, no window animations or minimal fade) applied to
  MainActivity but swapped at runtime based on intent action, or use
  `setTheme()` before super.onCreate based on the intent. Choose whichever is
  cleanest and document it.

## Deliverables & verification (must actually run these)

1. Project builds: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
2. Unit tests pass: `./gradlew testDebugUnitTest`.
3. `git init` the project, sensible .gitignore (build/, .gradle/, local.properties,
   .idea/), commit everything with clear commit messages.
4. Report in your final message: the file tree, how the summon mechanism works,
   what tests cover, and the exact commands you ran + their results.

## Constraints

- Do NOT modify anything outside this project directory. No global config.
- Do NOT touch /opt/hermes, /opt/data/config.yaml, the gateway, or the Hermes
  instance. The app is a client; the server is out of scope.
- Do NOT commit the API key, local.properties, or any build artifacts.
- If a version pin fails to resolve, pick the nearest stable version and note
  the change in your final report.
- Do not add features beyond this brief (no history screen, no widgets, no
  notifications). Lean and correct beats broad and broken.
