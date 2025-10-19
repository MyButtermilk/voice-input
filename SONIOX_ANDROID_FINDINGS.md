# Soniox STT on Android: GitHub Findings

## Summary
This project contains a working Android integration for Soniox STT in both async and realtime modes. Prior GitHub research found no mature Android samples; therefore, this repository documents concrete implementation details below.

## Implementation in this repo

### Realtime (WebSocket)
- File: `app/src/main/java/org/futo/voiceinput/providers/soniox/SonioxRealtimeRecognizer.kt`
- Client interface: `RealtimeSttClient` with implementation `SonioxRealtimeClient`
- WebSocket endpoint: `wss://stt-rt.soniox.com/transcribe-websocket`
- Session config JSON includes `api_key`, `model` (e.g., `stt-rt-preview`), `audio_format`, `sample_rate`, `num_channels`, optional `context`, and language options
- UI behavior: partial tokens are streamed into the composing text; `onRealtimeFinalResult` replaces partials with final text

### Async (REST)
- File: `app/src/main/java/org/futo/voiceinput/providers/soniox/SonioxAsyncRecognizer.kt`
- Uploads recorded audio, starts a transcription job, polls for completion
- Final transcript is committed once ready; no realtime partials in UI

### Settings
- Provider selection: `STT_PROVIDER` (`whisper_local` | `soniox_cloud`)
- Mode: `SONIOX_MODE` (`async` | `realtime`)
- API key: `SONIOX_API_KEY`
- Location: `app/src/main/java/org/futo/voiceinput/settings/`

### IME integration
- Entry point: `VoiceInputMethodService` hosts a `RecognizerView` that picks the provider based on settings
- Realtime mode renders `RealtimeStreamingResult` with partial/final text box
- Intent flow: `RecognizeActivity` keeps IME focused and returns result via `RecognizerIntent`

## Typical Android Setup (from docs, adapted to Kotlin)
- Realtime (WebSocket via OkHttp):
  - Connect to `wss://stt-rt.soniox.com/transcribe-websocket`.
  - On open, send JSON config with `api_key`, `model` (e.g., `stt-rt-preview`), `audio_format` (or `auto`), optional `language_hints`, `context`, diarization/translation flags.
  - Stream raw audio frames as binary messages; parse JSON responses with tokens (`text`, `is_final`, timings, `speaker`).
- Async (background file transcription):
  - Upload audio (Files API) or pass `audio_url`.
  - Create job: `POST /v1/transcriptions` with Bearer token.
  - Poll `GET /v1/transcriptions/{id}` or receive webhook.

### Minimal Kotlin snippets
Realtime (outline):
```kotlin
val client = OkHttpClient()
val req = Request.Builder().url("wss://stt-rt.soniox.com/transcribe-websocket").build()
client.newWebSocket(req, object: WebSocketListener() {
  override fun onOpen(ws: WebSocket, resp: Response) {
    val cfg = """{"api_key":"$SONIOX_API_KEY","model":"stt-rt-preview","audio_format":"auto"}"""
    ws.send(cfg)
    // Send PCM/WAV frames from AudioRecord as ws.send(ByteString.of(...))
  }
  override fun onMessage(ws: WebSocket, text: String) { /* parse tokens */ }
})
```
Async (outline):
```kotlin
val body = """{ "audio_url":"https://example.com/audio.mp3", "model":"stt-async-preview" }"""
val req = Request.Builder()
  .url("https://api.soniox.com/v1/transcriptions")
  .addHeader("Authorization", "Bearer $SONIOX_API_KEY")
  .post(body.toRequestBody("application/json".toMediaType()))
  .build()
```

## Notes & Recommendations
- Store API keys securely; prefer server-side key issuance or remote config
- For UX, ensure endpoint detection aligns with your VAD thresholds and allow manual finalize
- Segment long realtime sessions; handle network errors and reconnection gracefully

## References
- Official Soniox repositories and docs (`https://github.com/soniox`)
