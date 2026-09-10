/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.genai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.genai.types.Blob;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.LiveSendRealtimeInputParameters;
import dev.adk.trace.LiveTrace;
import dev.adk.trace.WireObserver;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** No real socket or model. Exercises the patched callbacks and actual SDK converters. */
public final class AsyncLiveTraceTest {
  @TempDir Path directory;

  private static final class RecordingSocket extends AsyncLive.GenAiWebSocketClient {
    final List<String> sent = Collections.synchronizedList(new ArrayList<>());
    RecordingSocket(ApiClient api, CompletableFuture<AsyncSession> future, WireObserver observer) {
      super(URI.create("wss://example.invalid"), Collections.emptyMap(),
          "{\"setup\":{\"model\":\"models/test\",\"systemInstruction\":{\"parts\":[{\"text\":\"preserve instructions\"}]}}}",
          future, api, observer);
    }
    @Override public void send(String payload) { sent.add(payload); }
  }

  @Test void recordsSetupAndPreCallbackMessagesAndKeepsActualPcmUntouched() throws Exception {
    ApiClient api = mock(ApiClient.class);
    when(api.vertexAI()).thenReturn(false);
    when(api.httpOptions()).thenReturn(HttpOptions.builder().build());
    CompletableFuture<AsyncSession> future = new CompletableFuture<>();
    Path file;
    try (LiveTrace trace = new LiveTrace(directory, "test", "user", "session", false)) {
      file = trace.path();
      WireObserver observer = WireObserver.forTrace(trace);
      observer.message("WS.CONNECT_BEGIN", "{}");
      RecordingSocket socket = new RecordingSocket(api, future, observer);
      socket.onOpen(null);
      socket.onMessage("{\"setupComplete\":{}}");
      assertTrue(future.isDone());
      // SDK logs and discards this message without a receive callback; trace must retain it.
      socket.onMessage("{\"goAway\":{\"timeLeft\":\"5s\"}}");
      AsyncSession session = future.get();
      List<String> received = new ArrayList<>();
      session.receive(message -> received.add(message.toJson())).get();
      socket.onMessage("{\"serverContent\":{\"interrupted\":true}}");
      assertEquals(1, received.size());
      byte[] pcm = new byte[640];
      session.sendRealtimeInput(LiveSendRealtimeInputParameters.builder()
          .audio(Blob.builder().mimeType("audio/pcm;rate=16000").data(pcm).build()).build()).get();
      assertEquals(2, socket.sent.size());
      assertTrue(socket.sent.get(1).contains(Base64.getEncoder().encodeToString(pcm)));
      socket.onClose(1000, "closed", false);
      assertTrue(trace.awaitTransportsClosed(0));
    }
    String log = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    assertTrue(log.contains("preserve instructions"));
    assertTrue(log.contains("setupComplete"));
    assertTrue(log.contains("goAway"));
    assertTrue(log.contains("interrupted"));
    assertTrue(log.contains("[PCM Audio Data: 640 bytes omitted]"));
    assertFalse(log.contains(Base64.getEncoder().encodeToString(new byte[640])));
    assertTrue(log.contains("WS.CLOSE"));
  }

  @Test void failedInitialSetupIsStillRecorded() throws Exception {
    ApiClient api = mock(ApiClient.class);
    CompletableFuture<AsyncSession> future = new CompletableFuture<>();
    Path file;
    try (LiveTrace trace = new LiveTrace(directory, "test", "user", "bad-session", false)) {
      file = trace.path();
      WireObserver observer = WireObserver.forTrace(trace);
      RecordingSocket socket = new RecordingSocket(api, future, observer);
      socket.onMessage("{\"serverContent\":{\"turnComplete\":true}}");
      assertTrue(future.isCompletedExceptionally());
    }
    assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains("turnComplete"));
  }
}
