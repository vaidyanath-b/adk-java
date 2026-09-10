# Source map

ADK sources are pinned to commit `ce1a2b57b7e72946b61b0eccb2aff38758f707f9`; SDK sources are pinned to `v1.58.0`. Retrieved 2026-09-09.

| Source | Findings / hooks |
|---|---|
| [PostgresRunner.java](https://github.com/redbus-labs/adk-java/blob/ce1a2b57b7e72946b61b0eccb2aff38758f707f9/core/src/main/java/com/google/adk/runner/PostgresRunner.java) | Constructor delegation; no independent checkpoint loop |
| [Runner.java](https://github.com/redbus-labs/adk-java/blob/ce1a2b57b7e72946b61b0eccb2aff38758f707f9/core/src/main/java/com/google/adk/runner/Runner.java) | runLiveImpl, session load, event/persistence ordering |
| [PostgresSessionService.java](https://github.com/redbus-labs/adk-java/blob/ce1a2b57b7e72946b61b0eccb2aff38758f707f9/core/src/main/java/com/google/adk/sessions/PostgresSessionService.java) | Eager reads/appends, state synchronization, Redis fallback |
| [PostgresDBHelper.java](https://github.com/redbus-labs/adk-java/blob/ce1a2b57b7e72946b61b0eccb2aff38758f707f9/core/src/main/java/com/google/adk/utils/PostgresDBHelper.java) | saveSession, insertEvents, getSession, commits/rollback and swallowed errors |
| [BaseLlmFlow.java](https://github.com/redbus-labs/adk-java/blob/ce1a2b57b7e72946b61b0eccb2aff38758f707f9/core/src/main/java/com/google/adk/flows/llmflows/BaseLlmFlow.java) | Live request connection, bidirectional send/receive, normalization |
| [LlmRequest.java](https://github.com/redbus-labs/adk-java/blob/ce1a2b57b7e72946b61b0eccb2aff38758f707f9/core/src/main/java/com/google/adk/models/LlmRequest.java) | LiveConnectConfig, instruction merging, observer transport |
| [Gemini.java](https://github.com/redbus-labs/adk-java/blob/ce1a2b57b7e72946b61b0eccb2aff38758f707f9/core/src/main/java/com/google/adk/models/Gemini.java) | Model connector construction |
| [GeminiLlmConnection.java](https://github.com/redbus-labs/adk-java/blob/ce1a2b57b7e72946b61b0eccb2aff38758f707f9/core/src/main/java/com/google/adk/models/GeminiLlmConnection.java) | Media routing, cancellation TODO, partial mapping, SDK session future |
| [Functions.java](https://github.com/redbus-labs/adk-java/blob/ce1a2b57b7e72946b61b0eccb2aff38758f707f9/core/src/main/java/com/google/adk/flows/llmflows/Functions.java) | Streaming tool updates become user content; execution/cancellation distinctions |
| [LiveWebSocketHandler.java](https://github.com/redbus-labs/adk-java/blob/ce1a2b57b7e72946b61b0eccb2aff38758f707f9/dev/src/main/java/com/google/adk/web/websocket/LiveWebSocketHandler.java) | Frontend query parameters, content/blob envelopes, event serialization |
| [SDK AsyncLive.java](https://github.com/googleapis/java-genai/blob/v1.58.0/src/main/java/com/google/genai/AsyncLive.java) | Handshake, setup serialization, initial setupComplete, raw receives and socket callbacks |
| [SDK AsyncSession.java](https://github.com/googleapis/java-genai/blob/v1.58.0/src/main/java/com/google/genai/AsyncSession.java) | Actual outbound serialization and asynchronous dispatch; receive registration lifetime |
| [SDK LiveConverters.java](https://github.com/googleapis/java-genai/blob/v1.58.0/src/main/java/com/google/genai/LiveConverters.java) | Wire field names and metadata versus SDK parameters |
| [Google Live protocol reference](https://ai.google.dev/api/live) | Control-message semantics and distinction between generation/turn completion |
| [Google Live capabilities](https://ai.google.dev/gemini-api/docs/live-api/capabilities) | PCM format, VAD and model-specific clientContent restrictions |

Google documentation describes service capabilities. The pinned source defines what this fork actually exposes. Library contract tests do not establish availability of a model or database behavior in a deployment.
