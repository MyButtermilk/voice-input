# Soniox STT on Android: GitHub Findings

## Summary
Using Exa web search, we looked for public Android apps on GitHub that integrate Soniox Speech-to-Text (STT). As of this survey, there are no clear, production Android repositories that directly show Soniox integration. We did find the official Soniox org and example libraries (Node, Python, Web), plus general frameworks (e.g., Pipecat) but not Android-specific client code.

## What We Found
- Official repos: `soniox/soniox_node`, `soniox/soniox_python`, `soniox/speech-to-text-web`, `soniox/soniox_examples` (no Android sample).
- Relevant docs (used to infer Android setup):
  - Real-time WebSocket: `wss://stt-rt.soniox.com/transcribe-websocket`
  - Async REST: `https://api.soniox.com/v1` (Files API + Transcriptions API)
  - Core concepts: token streaming, endpoint detection, manual finalization.

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
- No public Android samples found; follow official docs and adapt OkHttp/Retrofit + AudioRecord.
- For client apps, generate temporary API keys server-side (per Soniox Auth guidance).
- Tune endpoint detection and/or use manual finalization for UX; segment long sessions (<~60 min).
- Keep keys out of the app; use remote config/attestation if possible.

## References
- Docs: Real-time, Async, API Reference, Endpoint Detection, Manual Finalization
- Repos: https://github.com/soniox
