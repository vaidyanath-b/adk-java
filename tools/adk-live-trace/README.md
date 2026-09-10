# Human-readable Gemini Live and PostgreSQL tracing

This package provides a thread-safe Java file logger, two source-matched integration patches, a client playback observer, and executable contract tests. It targets `redbus-labs/adk-java` commit `ce1a2b57b7e72946b61b0eccb2aff38758f707f9` and `googleapis/java-genai` tag `v1.58.0`. The patches instrument a local fork; they do not connect to Gemini or a database by themselves.

The logging module has no ADK or SDK dependency. Both patched projects depend on `dev.adk:live-trace:1.0.0`; this avoids a dependency cycle. Java 8-compatible logger bytecode can be used by the Java 17 ADK project. Jackson is its only library dependency.

Start with the integration steps below. [sample-session.log](sample-session.log) illustrates the output, and [VALIDATION.md](VALIDATION.md) records verified checks and remaining runtime validation. `lib/live-trace-1.0.0.jar` is the compiled neutral logger; it is not a fat JAR and still requires Jackson. Building this module installs its dependency metadata for the two patched projects.

## What is recorded

| Layer | Hook | Records |
|---|---|---|
| SDK transport initialization | `AsyncLive.connect`, `GenAiWebSocketClient.onOpen` | Connect attempt, socket open, actual serialized setup, setup dispatch return |
| SDK inbound transport | `handleIncomingMessage`, before initial setup handling or conversion | Every received JSON message, including setupComplete, interrupted, transcripts, generationComplete, toolCallCancellation, goAway, resumption updates |
| SDK outbound transport | `AsyncSession.send`, inside the async send task | Actual converted JSON before send, local dispatch return or failure |
| SDK controls | `onWebsocketPing`, `onWebsocketPong`, `onError`, `onClose` | Ping/pong payload size, errors, close code and initiator |
| ADK live event stream | `Runner.runLiveImpl`, before `appendEvent` | Normalized events, IDs, invocation ID, partial flags, transcript observations and turn boundaries |
| Session storage | `PostgresSessionService.getSession`, `appendEvent` | Load beginning/result, loaded state, event append, state-save request and return |
| Actual PostgreSQL driver calls | `PostgresDBHelper` connection wrapped by `JdbcTrace` | SQL templates, execution duration, commit/rollback/savepoint/close beginning and outcome |
| Cache route | PostgreSQL helper and session service | Redis get/set outcome and whether the direct PostgreSQL path is enabled |
| Frontend WebSocket | `LiveWebSocketHandler` | Frontend input, event output dispatch, connection open/close, client-reported playback telemetry |
| Actual application player | `examples/playback-trace.mjs` | Reset/truncation beginning, returned measured counters/text, failures, client clocks |

Source links and exact method names are in [SOURCES.md](SOURCES.md). The SDK patch deliberately sits below ADK normalization. An ADK plugin alone cannot recover a message that the connector discarded.

## Timing and interpretation

Each record contains an ISO UTC timestamp with exactly three fractional digits, epoch milliseconds, a monotonic elapsed-nanoseconds value, a file sequence, thread name, and app/user/session/run identity. Wire records additionally identify the particular model connection. Capture occurs when the logger is called, before JSON formatting; it measures local observation or dispatch, not a hidden server event.

Use the monotonic clock to measure local intervals. Wall time can jump with clock correction. Sequence describes serialized file order, not a universal causal ordering between concurrent callbacks. The synchronous writer serializes a complete record under a lock, preventing interleaved text and avoiding an unbounded logging queue. A slow disk can delay callbacks; use this for diagnostic runs and measure that overhead. No hard-real-time precision is claimed.

`DISPATCH_RETURNED` means the WebSocket library accepted the local send call. It does not prove TCP delivery, Gemini processing, browser receipt, or audible playback. `WIRE.IN.MESSAGE` with `interrupted=true` is the exact millisecond at which this client observed that server signal. It is not the exact server-side speech-detection time. Local VAD may separately call `tracer.emit('VAD.LOCAL_START')` at its detection callback.

Client reports carry both `clientEpochMs` and `clientMonotonicMs`; the server adds its own receipt timestamp. These clock domains are not silently subtracted. Estimate clock offset with a measured round-trip exchange if cross-machine latency analysis is needed. Client telemetry is labeled `CLIENT.REPORTED`, not server-verified.

Transcription `finished`, ADK `partial`, and `turnComplete` are separate fields. Missing `partial` or `finished` remains missing/null; the observer never fabricates a final recognition result. `boundaryIndex` counts observed ADK turn-complete boundaries and is not a Gemini turn identifier. Late input transcription can cross that boundary. The raw wire record retains every explicit transcript flag even when the ADK event converter drops it.

## Payload policy

The writer sanitizes a deep copy, never the object sent to Gemini or saved to PostgreSQL. Under MIME-bearing media objects, `data` becomes `[PCM Audio Data: X bytes omitted]` for PCM and `[Media Data: X bytes omitted]` otherwise. It supports `mimeType` and `mime_type`, base64 strings, Jackson binary nodes, and numeric arrays. Base64 byte counts are calculated without allocating decoded PCM. Malformed base64 is omitted with an unknown size. Generic binary nodes are also omitted.

Instructions, text, function names/arguments/results, transcription strings, and state values remain readable. An ordinary tool argument named `data` is preserved when it is not a media blob. Credential-named JSON properties are redacted. This is a structured media filter, not a detector for secrets or PCM hidden in arbitrary strings; call the logger with the real SDK JSON structure, and do not pre-stringify a nested media object into a text field.

Malformed JSON produces a metadata record with character count and SHA-256, never an unsafe raw-text fallback. Exception class and stack are recorded; exception messages are suppressed because they can include raw payloads. The logger reports write/format failures and rejects late records; `checkHealthy()` and `close()` throw if the trace is incomplete. A crash can leave the last text record incomplete. Default per-record writes go to the OS; `forceEachRecord=true` additionally forces file content at every record and costs considerably more latency. Close forces content in either mode. This is not a transactional audit database.

The file name is `logs/adk-live-session-{safeSessionId}-{runUuid}.log`. A fresh file is created per run and records are appended to it. The UUID avoids mixing processes/reconnect experiments, and the sanitized session slug cannot create path traversal. The complete original identity is inside each record. Keep one sink open for the entire invocation, including all of its model connections.

## Fork integration

### 1. Build the neutral logger module

Use JDK 17 and Maven. From this package directory:

```sh
mvn clean install
```

The test-phase execution runs `TraceContractTest` without requiring Gemini, PostgreSQL, Redis, audio devices, or credentials. The Node client helper has its own test:

```sh
node examples/playback-trace.test.mjs
```

Do not use test failure suppression to produce a supposedly verified artifact. See [VALIDATION.md](VALIDATION.md) for the actual checks performed in this workspace and their limits.

### 2. Patch the SDK

In a checkout of `googleapis/java-genai` at `v1.58.0`:

```sh
git apply --check /absolute/path/adk-live-trace/patches/sdk-logging.patch
git apply /absolute/path/adk-live-trace/patches/sdk-logging.patch
cp /absolute/path/adk-live-trace/integration-tests/AsyncLiveTraceTest.java src/test/java/com/google/genai/
mvn install
```

On this public SDK checkout, the full suite passed its executed tests but failed the aggregate coverage thresholds. The inherited Clirr checker also failed with `Invalid byte tag in constant pool: 19`. For a local diagnostic build after running those tests, use `mvn install -DskipTests -Djacoco.skip=true -Dclirr.skip=true`. This bypasses both gates; it is not evidence that either gate passed. See [VALIDATION.md](VALIDATION.md).

The patch changes the artifact version to `1.58.0-live-trace-SNAPSHOT`, adds the neutral dependency, and adds `connect(model, config, WireObserver)` while preserving the existing overload. The observer is passed explicitly to the WebSocket client and `AsyncSession`; it is never obtained from a ThreadLocal inside an asynchronous callback. The first setupComplete is logged before the SDK consumes it. Binary WebSocket messages are decoded as UTF-8 JSON by the existing SDK before logging. Ping/pong payloads are represented by sizes only.

### 3. Patch the ADK fork

In a checkout of the pinned ADK commit:

```sh
git apply --check /absolute/path/adk-live-trace/patches/adk-logging.patch
git apply /absolute/path/adk-live-trace/patches/adk-logging.patch
mvn -pl core -am install
```

If using the dev WebSocket adapter, also build its reactor module with `mvn -pl dev -am install`. Use the repository's normal formatting, test and validation gates before merging. The patch changes the parent SDK version property and adds the logger dependency to core. It adds a `@JsonIgnore` observer to `LlmRequest`, with a disabled default; the observer is never serialized into setup or history. `BaseLlmFlow` resolves it for the invocation, and `Gemini.connect` passes it into the SDK-facing connector.

The patch instruments `Runner`, so `PostgresRunner` inherits the hooks. No invented `PostgresRunner.runLive` override or checkpoint method is required. Verify dependency resolution in your application:

```sh
mvn dependency:tree -Dincludes=com.google.genai:google-genai,dev.adk:live-trace
```

Expect exactly the patched SDK version and one logger version. A stock SDK at runtime will not contain the new connect overload.

### 4. Register the trace BEFORE constructing/loading the session

The registry follows this fork's PostgreSQL primary key, which is globally keyed by session ID. It rejects simultaneous traced invocations with the same session ID, including accidental reuse across apps/users. App/user/run identity is still recorded in the sink. If you change storage to composite keys or want concurrent invocations for one session, extend the registry and pass explicit operation context through the helper; do not remove the collision check.

Registration and cleanup surround the invocation. The following is application integration code; `pumpMicrophoneInto`, the agent definition, and event playback are your existing application methods:

```java
String sessionId = "session-123"; // Globally unique in this fork's Postgres schema.
LiveRequestQueue queue = new LiveRequestQueue();
try (LiveTrace trace = new LiveTrace(Paths.get("logs"), appName, userId, sessionId, false);
     java.io.Closeable registration = TraceRegistry.register(sessionId, trace)) {
  PostgresRunner runner = new PostgresRunner(agent, appName);
  // Registration precedes this eager PostgreSQL/Redis lookup.
  Session session = runner.sessionService()
      .getSession(appName, userId, sessionId, Optional.empty()).blockingGet();
  if (session == null) {
    session = runner.sessionService()
        .createSession(appName, userId, (Map<String,Object>) null, sessionId).blockingGet();
  }
  RunConfig config = RunConfig.builder()
      .setStreamingMode(RunConfig.StreamingMode.BIDI)
      .setResponseModalities(ImmutableList.of(new Modality(Modality.Known.AUDIO)))
      .build();
  CountDownLatch terminated = new CountDownLatch(1);
  Disposable subscription = runner.runLive(session, queue, config)
      .doFinally(terminated::countDown)
      .subscribe(this::playOrDisplayEvent,
          e -> trace.error("RUN.FAILED", e),
          () -> trace.record("RUN.COMPLETED", LiveTrace.fields()));
  try {
    pumpMicrophoneInto(queue); // Must return when capture stops; produces PCM blobs.
  } finally {
    queue.close();
    if (!terminated.await(5, TimeUnit.SECONDS)) {
      trace.record("RUN.SHUTDOWN_TIMEOUT", LiveTrace.fields());
      subscription.dispose();
    }
    // Wait for actual WS.CLOSE callbacks, not just AsyncSession.close() return.
    if (!trace.awaitTransportsClosed(5000)) {
      trace.record("WS.SHUTDOWN_TIMEOUT", LiveTrace.fields());
    }
    trace.checkHealthy();
    // try-with-resources removes the registry lease, then forces/closes the file.
    // close() reports incomplete shutdown if a transport is still active.
  }
}
```

Imports are `dev.adk.trace.*`, `com.google.adk.agents.*`, `com.google.adk.runner.PostgresRunner`, `com.google.adk.sessions.Session`, `com.google.genai.types.Modality`, Guava `ImmutableList`, RxJava `Disposable`, `java.nio.file.Paths`, `java.util.Map`, `java.util.Optional`, and `java.util.concurrent.*`. Reuse the application's existing runner/service lifecycle where appropriate rather than constructing a new runner for each audio chunk.

The stock dev handler obtains its runner from `RunnerService`. Register the sink at the application's connection/session owner before that handler loads the session; retain the lease until its queue has closed and model close callbacks finish. The supplied frontend patch consumes an existing registration; it intentionally does not open a file for every unauthenticated socket automatically. If you register per socket, store the sink/lease on the socket attributes and perform bounded shutdown on a separate owner task, not in a blocking WebSocket callback. Do not pass raw query-string authentication headers into the log.

### 5. Add playback observation where playback actually occurs

ADK does not know how many PCM frames remain in a browser AudioWorklet/ring buffer or how many audio sources have been scheduled. Import `playbackTrace` from the supplied `.mjs` file and give it a function that sends a text frame on your frontend socket. Surround the real buffer-reset and transcript-truncation operations using the examples in that file. Return counts and transcript text measured by those operations; never return assumed zeros merely because Gemini reported interruption.

The handler patch accepts an allowlisted `trace` envelope and logs it without forwarding it to Gemini. This is a custom application extension, not a Gemini protocol message. Without the patch, the stock handler can treat this unknown envelope as an empty live request and close the stream. All counts and client clocks are explicitly client-reported. Use your existing authenticated, session-bound frontend channel; a client report is an observation from that client, not proof of actual speaker playback.

Nothing in the logger cancels audio or tools, rewrites transcripts, changes VAD, or fixes ADK cancellation support. It records the actual operations you instrument. If your player does not truncate a transcript, the wire interruption plus `playbackCleared: not observable in ADK` is the honest record; do not fabricate a truncation event.

## PostgreSQL meanings and existing behavior

`PostgresRunner` is a constructor wrapper around `Runner`, `PostgresArtifactService`, `PostgresSessionService`, and a memory service. There is no explicit checkpoint routine in it. The patch records real JDBC savepoints/checkpoint SQL if used; it never inserts a PostgreSQL `CHECKPOINT` command.

The pinned session service performs work eagerly before returning `Single.just`/`Maybe.just`. This is why hooks surround the actual methods and helper calls rather than only attaching `doOnSubscribe` to an already-returned reactive value. It may load Redis first. When `use_kafka` is enabled, this helper skips the direct PostgreSQL block; any Kafka-consumer commit needs logging in that consumer, outside this runner's process. A cache result is not a PostgreSQL commit.

The helper disables auto-commit, executes the session upsert, commits inside the event loop, and commits again after processing parts. It catches some event-insertion errors, rolls back the current transaction, and swallows the exception. Earlier commits may already be durable. `STATE.SAVE_RETURNED` is therefore deliberately not called `STATE.COMMITTED`. The JDBC proxy records each real commit/rollback return and the patch marks swallowed errors. Fixing that transaction design is separate work, not hidden inside the logging change.

The concrete Postgres append override also does not apply the interface's partial-event filter before writing its own updated session. Do not assume a partial event was skipped just because the default service contract skips partial events. Logs retain that distinction.

## Protocol and model compatibility

The Java dev handler accepts `content` and `blob` messages and starts from app/user/session query parameters. It does not accept an initial `BidiRunLiveConfig` frame. This package logs the actual Java RunConfig-derived setup at the SDK transport boundary, including instructions, voice configuration and declarations actually sent. Do not log a hypothetical config object and label it wire setup.

`gemini-2.0-flash` is not a guarantee of a usable Live endpoint. Select an available Live-capable model for your account/backend. For `gemini-3.1-flash-live-preview`, current Google guidance restricts `clientContent` to initial history and requires realtime text for subsequent text input. The pinned fork uses `clientContent` for ordinary text and streaming-tool updates. The logger exposes that incompatibility; it does not silently change the protocol or claim compatibility with every model name.

Known gaps in this ADK/SDK path remain visible: tool-call cancellation is logged but not executed by the connector; generationComplete can disappear during ADK normalization; absent turnComplete can map to partial=false; the SDK receive future represents callback registration rather than socket lifetime; and model resumption messages do not establish an automatic ADK reconnect loop. Raw wire logs and explicit close tracking are essential for distinguishing these cases.

## Acceptance run in the application

1. Start a registered trace and check loaded session identity/state, storage route, and exact model setup.
2. Send known 640-byte input PCM and 960-byte output fixture chunks through the corresponding paths; confirm sizes and no base64/arrays in the file. Check instructions, text, tools and transcript strings remain.
3. Speak during playback. Correlate raw server interruption, ADK interruption, client receipt, actual player reset counters and any real transcript edit. Keep the clock domains separate.
4. Invoke an ordinary tool and a streaming tool. Inspect matching call IDs for ordinary responses and actual clientContent updates for the streaming path. Observe cancellation without assuming task disposal.
5. Force PostgreSQL failure and Redis hit/miss in a non-production test database. Check commit/rollback and swallowed-error records; do not infer durability from append completion.
6. Disconnect Gemini unexpectedly, then stop capture. Verify shutdown timeouts or real close callbacks are logged and file close reports any incompleteness.
7. Repeat with two different sessions concurrently. Confirm files and wire connection IDs remain isolated. Attempt duplicate registration for the same session and confirm it is rejected.

These runtime steps require your configured model, database/cache and playback application. They are not simulated by the included library contract tests.

The playback telemetry adapter fails open if its socket cannot send: the actual player reset still runs. Check `failedReports` or call `checkHealthy()` to detect an incomplete client trace.
