# Soniox Realtime Final Transcript Replacement — Implementation Plan

**Repo:** `voice-input`  
**Bug:** Final Soniox transcripts sometimes do not replace grey partials in the pop-up and only the partials are inserted into the original IME field.  
**Goal:** Ensure partials (grey) reliably transition to finals (white) in the pop-up, and the text inserted via the accessibility service is the **best available** (prefer finals; allow early user submit).

---

## 1) Summary of Desired Behavior

1. App is invoked → Soniox realtime socket created and kept alive (at least until first speech is detected).  
2. Silero VAD + Smart Turn v3 detects speech start/stop.  
3. While speaking: show partials in grey.  
4. When Soniox emits finals: replace the corresponding partials and render them in white.  
5. When Smart Turn decides **end of speech**:
   - Wait until **all currently shown partials** are promoted to finals **or** a short grace timeout elapses.
   - Insert resulting text back into the original input (via accessibility service).
6. User can press “Insert now” at any time to close immediately and insert whatever is currently shown (finals + any remaining partial tail).

---

## 2) Likely Root Causes (to be eliminated)

- **Race between VAD stop → pipeline close → Soniox finals**: finals arrive after we cancel collectors or close the socket, so UI never receives them.
- **Out-of-order or de-duplicated events**: finals may update an earlier “segment” which the UI has already discarded/overwritten due to index drift.
- **UI not subscribed on main-safe state**: finals pushed on a background dispatcher without marshaling through ViewModel/state flow → recomposition missed or overwritten by a later partial.
- **Session re-init on end-of-speech**: creating a second collector invalidates the active one mid-stream.
- **Premature insertion**: we immediately insert upon VAD stop instead of waiting for Soniox’s finalization window.

This plan fixes all five by centralizing transcript aggregation, sequencing, lifecycle, and drain-before-close behavior.

---

## 3) High-Level Design

### 3.1 New “TranscriptAggregator” (pure Kotlin)
A single source of truth that merges **partial** and **final** events deterministically.

```kotlin
// providers/soniox/TranscriptAggregator.kt
data class TranscriptRenderState(
    val finalText: String,      // concatenated finals
    val partialTail: String,    // the current unfinalized tail
    val hasOpenSegments: Boolean,
)

sealed interface TranscriptEvent {
    data class Partial(
        val segmentId: String,  // or Int if available
        val seq: Long,          // monotonically increasing per segment (or create locally)
        val text: String
    ) : TranscriptEvent

    data class Final(
        val segmentId: String,
        val seq: Long,
        val text: String
    ) : TranscriptEvent
}

class TranscriptAggregator {
    private val finals = linkedMapOf<String, Pair<Long, String>>()     // segmentId -> (seq, text)
    private val partials = linkedMapOf<String, Pair<Long, String>>()   // segmentId -> (seq, text)
    private val _state = MutableStateFlow(TranscriptRenderState("", "", false))
    val state: StateFlow<TranscriptRenderState> = _state

    private var lastEventAtMs = SystemClock.elapsedRealtime()

    fun onEvent(e: TranscriptEvent) {
        lastEventAtMs = SystemClock.elapsedRealtime()
        when (e) {
            is TranscriptEvent.Partial -> {
                val prev = partials[e.segmentId]?.first ?: -1L
                if (e.seq >= prev) partials[e.segmentId] = e.seq to e.text
                recompute()
            }
            is TranscriptEvent.Final -> {
                // when final arrives, drop corresponding partial
                val prevF = finals[e.segmentId]?.first ?: -1L
                if (e.seq >= prevF) finals[e.segmentId] = e.seq to e.text
                partials.remove(e.segmentId)
                recompute()
            }
        }
    }

    fun clear() {
        finals.clear(); partials.clear()
        recompute()
    }

    private fun recompute() {
        val finalText = finals.values.joinToString(separator = "") { it.second }
        val partialTail = partials.values.lastOrNull()?.second ?: ""
        _state.value = TranscriptRenderState(
            finalText = finalText,
            partialTail = partialTail,
            hasOpenSegments = partials.isNotEmpty()
        )
    }

    suspend fun awaitDrain(graceMs: Long = 800L, idleMs: Long = 250L) {
        // Wait until no partials and we have been idle for a small window.
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val s = _state.value
            val idle = (SystemClock.elapsedRealtime() - lastEventAtMs) >= idleMs
            if (!s.hasOpenSegments && idle) return
            if (SystemClock.elapsedRealtime() - start >= graceMs) return
            delay(30)
        }
    }
}
```

**Notes**
- We maintain segment-level ordering using a simple `seq` (from Soniox if available; otherwise auto-increment per segment when events arrive).
- Finals **always** remove their partial counterpart and move to the `finals` map.
- UI simply renders `finalText + partialTail` with different styles.

### 3.2 Soniox Realtime client → events
Normalize Soniox websocket messages into the `TranscriptEvent` model above.

```kotlin
// providers/soniox/RealtimeSttClient.kt
sealed interface ServerMsg {
    data class Transcript(
        val segmentId: String,
        val isFinal: Boolean,
        val text: String,
        val seq: Long
    ) : ServerMsg
    object KeepAlive : ServerMsg
    data class Error(val cause: Throwable) : ServerMsg
}

interface RealtimeSttClient : Closeable {
    val serverMsgs: Flow<ServerMsg> // hot flow; shared
    suspend fun connect()
    fun startAudio()  // begin sending audio frames
    fun stopAudio()   // stop sending; keep socket open to receive finals
    override fun close()
}
```

**Key behaviors**
- **Socket lifetime**: created on invocation, `connect()` immediately, keep socket alive at least until first VAD speech start.
- **Start/Stop audio**: controlled by Smart Turn. Stopping audio **does not** close the socket; we wait for finals.
- **KeepAlive/Ping** and reconnect policy added.

### 3.3 ViewModel that owns the aggregator
```kotlin
// providers/soniox/RealtimeSttViewModel.kt
class RealtimeSttViewModel(
    private val client: RealtimeSttClient,
    private val aggregator: TranscriptAggregator = TranscriptAggregator(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val uiState: StateFlow<TranscriptRenderState> = aggregator.state

    init {
        viewModelScope.launch(io) {
            client.serverMsgs.collect { msg ->
                when (msg) {
                    is ServerMsg.Transcript -> {
                        if (msg.isFinal) {
                            aggregator.onEvent(TranscriptEvent.Final(msg.segmentId, msg.seq, msg.text))
                        } else {
                            aggregator.onEvent(TranscriptEvent.Partial(msg.segmentId, msg.seq, msg.text))
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    suspend fun awaitDrainAndClose(graceMs: Long) {
        aggregator.awaitDrain(graceMs = graceMs)
        client.close()
    }

    fun clear() = aggregator.clear()
}
```

All Soniox updates flow through the aggregator and then through a single `StateFlow` that the UI observes on the main thread.

### 3.4 UI rendering (pop-up)
```kotlin
// RecognizerView.kt (or the composable that shows realtime text)
@Composable
fun RealtimeTranscript(state: TranscriptRenderState) {
    val styled = buildAnnotatedString {
        append(state.finalText) // default (white)
        if (state.partialTail.isNotEmpty()) {
            withStyle(SpanStyle(color = Color.Gray)) {
                append(state.partialTail)
            }
        }
    }
    Text(text = styled, /* style, maxLines, etc. */)
}
```

### 3.5 End-of-speech sequencing
- Smart Turn detects end → call `client.stopAudio()` immediately (stop sending audio frames).  
- Do **not** close the socket yet.  
- Call `viewModel.awaitDrainAndClose(graceMs = 800)` to allow Soniox to finalize.  
- After await completes, insert `finalText + partialTail` (partial tail should often be empty by then).

### 3.6 Early submit (user action)
- If the user taps “Insert now”, we bypass `awaitDrainAndClose` and proceed with what’s currently displayed.  
- We still close the client afterwards.

---

## 4) Changes by File (implementation checklist)

> **All paths are relative to** `app/src/main/java/org/futo/voiceinput/`

### A) Soniox provider
- **providers/soniox/RealtimeSttClient.kt**
  - Add `serverMsgs: Flow<ServerMsg>` with standardized message model.
  - Add `connect()`, `startAudio()`, `stopAudio()`, `close()` semantics.
  - Ensure incoming websocket callbacks are delivered on `Dispatchers.IO` then emitted to a hot `SharedFlow`/`Channel` → `Flow`.
  - Add keepalive and basic reconnect (bounded retries).

- **providers/soniox/SonioxRealtimeRecognizer.kt**
  - Replace any ad-hoc merging logic with `TranscriptAggregator` instance.
  - Wire Smart Turn start/stop to `client.startAudio()`/`stopAudio()`.
  - On end-of-speech: `aggregator.awaitDrain(graceMs)` before closing + inserting.

- **providers/soniox/RealtimeSttViewModel.kt**
  - Own the `TranscriptAggregator`.
  - Map `ServerMsg.Transcript` → `TranscriptEvent` and call `aggregator.onEvent`.
  - Expose `uiState: StateFlow<TranscriptRenderState>` for Compose.

- **providers/soniox/SonioxAsyncRecognizer.kt** (if used for background or alt path)
  - Ensure parity with the realtime path or delete stale logic to avoid duplicate collectors.

### B) Recognizer orchestration
- **recognizer/RecognizerControl.kt** (or `RecognizeActivity.kt` / `VoiceInputMethodService.kt` depending on current flow)
  - Lifecycle: create ViewModel & client at invocation; call `client.connect()` immediately.
  - Start VAD; when **speech starts** → `client.startAudio()` (ensure socket already connected).
  - When **speech ends**:
    1) `client.stopAudio()`  
    2) `viewModel.awaitDrainAndClose(graceMs = 800)`  
    3) Insert text via `TextInsertionAccessibilityService` and then dismiss.
  - Wire “Insert now” button to “insert immediately” path without drain.

### C) UI (pop-up)
- **RecognizerView.kt**
  - Render `finalText` in normal text color and `partialTail` in grey using `AnnotatedString` (see sample).
  - Ensure recomposition on `uiState` changes.
  - Add an explicit “Insert now”/“Done” button (if not already present) to support early submit.

### D) Accessibility insert
- **accessibility/TextInsertionAccessibilityService.kt**
  - No logic change required; just ensure the text passed is `finalText + partialTail` from the ViewModel state at the moment we insert.
  - If the service currently trims trailing whitespace/periods, preserve that behavior.

### E) Smart Turn & VAD
- **smartturn/SileroVad.kt**, **smartturn/SmartTurnEngine.kt**
  - Ensure we **do not** cancel the Soniox client on end-of-speech; only stop sending audio and wait for finals via ViewModel drain.
  - If there is an existing callback sequence that closes recognition immediately, route it through the new drain path.

---

## 5) Threading & Lifecycle

- **Websocket** callbacks → `Dispatchers.IO` → push `ServerMsg` into a `MutableSharedFlow(replay=0, extraBufferCapacity=64)`.
- **ViewModel** collects `serverMsgs` on `Dispatchers.IO` and calls `aggregator.onEvent(...)` (thread-safe).
- **Aggregator** internally updates a `MutableStateFlow`, which Compose collects on the main thread.
- **End-of-speech**: Stop audio (IO) → `awaitDrain` suspends on IO (polling every ~30ms) to let finals arrive → on completion, main thread inserts text and dismisses.

This ensures finals can overtake partials before close and that UI always has a consistent render state.

---

## 6) Protocol Notes (Soniox specifics)

- If Soniox provides a **stable `segment_id`** and an **`is_final` boolean**, map directly:
  - `is_final=false` → `TranscriptEvent.Partial`
  - `is_final=true`  → `TranscriptEvent.Final`
- If Soniox sends **incremental token diffs**, normalize to segment text and bump `seq` per update so we only apply monotonic changes.
- If a “final” arrives **without** a prior partial for that `segmentId`, the aggregator will just place it in `finals` (ok).

---

## 7) Closing, Cancellation & Timeouts

- `awaitDrain(graceMs=800, idleMs=250)` defaults recommended; make these tunable in settings/dev config:
  - `graceMs`: max wait after VAD end for finals to arrive (0.8–1.2s typical).
  - `idleMs`: how long since the last event before we consider the stream idle (to avoid waiting forever if Soniox is silent).

- On **socket failure** during drain → proceed with best-effort insertion (finals + partial tail at failure time).

---

## 8) Telemetry & Logging (dev builds)

- Add debug logs at levels:
  - Socket lifecycle: connect/startAudio/stopAudio/close.
  - Event counters: partials N, finals N, last seq per segment.
  - Drain outcome: waited X ms, openSegments M → closed.
- Add a developer-visible overlay toggle to print `segmentId`, `seq`, `isFinal` during testing.

---

## 9) Tests

### Unit
- `TranscriptAggregatorTest`
  - Partial then Final for same `segmentId` → final replaces partial.
  - Out-of-order: Final(seq=3) arrives after Partial(seq=2) → final wins.
  - Multiple segments: finals concatenate; only **last** partial becomes the grey tail.
  - `awaitDrain()` returns immediately if no partials; waits (<= grace) if partial present.

### Instrumented (UI)
- Fake Soniox stream sending: P…P…F for each segment; assert UI transitions grey→white and final text order correct.
- End-of-speech: stopAudio, deliver finals after 200–400ms; assert we waited and inserted finals.
- Early submit: press button mid-stream; assert whatever is displayed is inserted.

---

## 10) Step-by-Step Implementation

1. **Create** `TranscriptAggregator.kt` and unit tests.  
2. **Refactor** `RealtimeSttClient.kt` to expose `serverMsgs: Flow<ServerMsg>` and `startAudio/stopAudio`.  
3. **Wire** `RealtimeSttViewModel` to collect `serverMsgs` → `aggregator`. Expose `uiState`.  
4. **Update** orchestrator (activity/service) to:
   - `client.connect()` on open,
   - `client.startAudio()` on VAD start,
   - on VAD stop → `client.stopAudio()` then `awaitDrainAndClose(graceMs)`.  
5. **Update** Compose view to render `finalText + partialTail` with white/grey styling + add “Insert now”.  
6. **Ensure** insertion path reads from `uiState` at the exact moment of insertion.  
7. **Add** dev logging and (optionally) a feature flag to revert to old behavior.  
8. **Run** unit + instrumented tests; manual QA on real devices (poor & good connectivity).

---

## 11) Acceptance Criteria

- During continuous speech, partial text appears in grey; when a final arrives **for the same content**, grey text disappears and the equivalent white text replaces it **without duplication**.
- On end-of-speech, the app **waits briefly** for finals; inserted text is equal to or better than what the user saw.
- If the user taps “Insert now”, the inserted text matches the on-screen concatenation (finals + current partial tail).
- No regressions in latency, crashes, or double insertion.  
- Works across repeated invocations and with poor networks.

---

## 12) Risk & Mitigation

- **Long waits for finals**: bounded `graceMs`, early submit UI.
- **Reconnection churn**: keep the socket through VAD cycles; only close after drain.
- **Incorrect segment mapping**: enforce monotonic `seq` per segment and stable `segmentId`. If Soniox uses timestamps instead, derive a synthetic `segmentId` per finalized chunk.

---

## 13) Optional Enhancements (later)

- Display per-word stabilization (fade-in from grey to white progressively).
- Highlight last stable punctuation when finals arrive.
- Add setting for “Prefer instant insert (no wait for finals)”.

---

## 14) Small Code Snippets / Patches

> **Adapters may need adjustment based on the exact existing code; the following illustrates the shape of the changes.**

**End-of-speech flow (orchestrator):**
```kotlin
// Somewhere in RecognizerControl / RecognizeActivity
scope.launch {
    // ... VAD start
    client.startAudio()

    // ... on VAD end:
    client.stopAudio()
    viewModel.awaitDrainAndClose(graceMs = 800)
    val s = viewModel.uiState.value
    accessibility.insertText(s.finalText + s.partialTail)
    dismissPopup()
}
```

**Early submit button:**
```kotlin
Button(onClick = {
    val s = viewModel.uiState.value
    accessibility.insertText(s.finalText + s.partialTail)
    client.close()
    dismissPopup()
}) { Text("Insert now") }
```

**Message mapping:**
```kotlin
client.serverMsgs.collect { msg ->
    when (msg) {
        is ServerMsg.Transcript -> {
            if (msg.isFinal)
                aggregator.onEvent(TranscriptEvent.Final(msg.segmentId, msg.seq, msg.text))
            else
                aggregator.onEvent(TranscriptEvent.Partial(msg.segmentId, msg.seq, msg.text))
        }
        else -> Unit
    }
}
```

---

## 15) Rollout

- Gate new drain behavior behind a dev toggle for the first build.  
- After verification, enable by default.

---

**Done. This plan removes races, standardizes event merging, and ensures finals reliably replace partials on-screen and in the inserted text, while keeping a responsive “Insert now” escape hatch.**
