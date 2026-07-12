/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.adk.tutorials;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Harness that runs a plain (non-live) agent in SSE streaming mode and logs how usageMetadata
 * arrives in afterModelCallback and onEventCallback across a single invocation.
 *
 * <p>Requires GOOGLE_API_KEY. Optional: -Dmodel=..., -Dprompt=...
 */
public class SseTokenPluginHarness {
  public static void main(String[] args) throws Exception {
    String model = System.getProperty("model", "gemini-2.5-flash");
    String prompt = System.getProperty("prompt", "Write a two-sentence fun fact about the ocean.");

    AtomicInteger modelCbSeq = new AtomicInteger();
    AtomicInteger eventCbSeq = new AtomicInteger();

    BasePlugin probe =
        new BasePlugin("sse_probe") {
          @Override
          public Maybe<LlmResponse> afterModelCallback(
              CallbackContext callbackContext, LlmResponse llmResponse) {
            System.out.printf(
                "[afterModelCallback #%d] partial=%s  usage=%s%n",
                modelCbSeq.incrementAndGet(),
                llmResponse.partial().orElse(null),
                llmResponse.usageMetadata().map(Object::toString).orElse("<none>"));
            return Maybe.empty();
          }

          @Override
          public Maybe<Event> onEventCallback(InvocationContext invocationContext, Event event) {
            System.out.printf(
                "[onEventCallback #%d]    partial=%s  usage=%s%n",
                eventCbSeq.incrementAndGet(),
                event.partial().orElse(null),
                event.usageMetadata().map(Object::toString).orElse("<none>"));
            return Maybe.empty();
          }
        };

    LlmAgent agent =
        LlmAgent.builder().name("sse_agent").model(model).instruction("You are concise.").build();

    com.google.adk.plugins.LiveTokenTrackingPlugin tokenPlugin =
        new com.google.adk.plugins.LiveTokenTrackingPlugin();
    BasePlugin printer =
        new BasePlugin("usage_printer") {
          @Override
          public io.reactivex.rxjava3.core.Completable afterRunCallback(
              InvocationContext invocationContext) {
            System.out.println(
                "[afterRunCallback] plugin aggregated usage = "
                    + tokenPlugin.usageFor(invocationContext.invocationId()));
            return io.reactivex.rxjava3.core.Completable.complete();
          }
        };

    InMemoryRunner runner =
        new InMemoryRunner(agent, "sse_app", ImmutableList.of(probe, printer, tokenPlugin));
    Session session = runner.sessionService().createSession("sse_app", "user1").blockingGet();

    RunConfig runConfig = RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build();

    Content userMessage =
        Content.builder().role("user").parts(ImmutableList.of(Part.fromText(prompt))).build();

    System.out.println("Model: " + model + "  StreamingMode: SSE");
    System.out.println("Prompt: " + prompt + "\n");

    runner
        .runAsync(session.userId(), session.id(), userMessage, runConfig)
        .blockingForEach(e -> {});

    System.out.printf(
        "%nTotals: afterModelCallback calls=%d, onEventCallback calls=%d%n",
        modelCbSeq.get(), eventCbSeq.get());
    System.out.println("Done.");
    System.exit(0);
  }
}
