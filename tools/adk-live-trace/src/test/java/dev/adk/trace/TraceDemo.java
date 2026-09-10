package dev.adk.trace;

import java.nio.file.Paths;
import java.util.Base64;

/** Synthetic fixture only: no network, microphone, Gemini session, or PostgreSQL connection. */
public final class TraceDemo {
  public static void main(String[] args) throws Exception {
    try (LiveTrace trace = new LiveTrace(Paths.get(args[0]), "synthetic-fixture", "demo", "example", false)) {
      trace.record("FIXTURE", LiveTrace.fields("synthetic", true, "description", "Illustrates formatting; not a captured Gemini run"));
      WireObserver wire = WireObserver.forTrace(trace);
      wire.message("WS.CONNECT_BEGIN", "{}");
      wire.message("WS.OPEN", "{}");
      wire.message("WIRE.OUT.SETUP.ATTEMPT", "{\"setup\":{\"model\":\"models/<configured-live-model>\",\"generationConfig\":{\"responseModalities\":[\"AUDIO\"],\"speechConfig\":{\"voiceConfig\":{\"prebuiltVoiceConfig\":{\"voiceName\":\"Kore\"}}}},\"systemInstruction\":{\"parts\":[{\"text\":\"Help the traveler.\"}]},\"tools\":[{\"functionDeclarations\":[{\"name\":\"lookup_bus\",\"description\":\"Look up a bus\"}]}],\"inputAudioTranscription\":{},\"outputAudioTranscription\":{}}}");
      wire.message("WIRE.IN.MESSAGE", "{\"setupComplete\":{}}");
      String input = Base64.getEncoder().encodeToString(new byte[640]);
      wire.message("WIRE.OUT.MESSAGE.ATTEMPT", "{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=16000\",\"data\":\""+input+"\"}}}");
      EventObserver events = new EventObserver(trace);
      events.accept("{\"id\":\"e1\",\"partial\":true,\"inputTranscription\":{\"text\":\"Next bus\"}}");
      wire.message("WIRE.IN.MESSAGE", "{\"toolCall\":{\"functionCalls\":[{\"id\":\"fc1\",\"name\":\"lookup_bus\",\"args\":{\"route\":\"42\"}}]}}");
      wire.message("WIRE.OUT.MESSAGE.ATTEMPT", "{\"toolResponse\":{\"functionResponses\":[{\"id\":\"fc1\",\"name\":\"lookup_bus\",\"response\":{\"departure\":\"18:30\"}}]}}");
      wire.message("WIRE.IN.MESSAGE", "{\"serverContent\":{\"modelTurn\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"audio/pcm;rate=24000\",\"data\":\""+Base64.getEncoder().encodeToString(new byte[960])+"\"}}]},\"outputTranscription\":{\"text\":\"The next bus leaves\"}}}");
      wire.message("WIRE.IN.MESSAGE", "{\"serverContent\":{\"interrupted\":true}}");
      events.accept("{\"id\":\"e2\",\"interrupted\":true}");
      wire.message("WIRE.IN.MESSAGE", "{\"toolCallCancellation\":{\"ids\":[\"fc2\"]}}");
      trace.record("CLIENT.REPORTED.PLAYBACK.RESET.RETURNED", LiveTrace.fields("synthetic", true, "queuedFramesCleared", 3, "scheduledSourcesStopped", 2, "clientEpochMs", System.currentTimeMillis()));
      trace.record("CLIENT.REPORTED.TRANSCRIPT.TRUNCATE.RETURNED", LiveTrace.fields("synthetic", true, "transcriptBefore", "The next bus leaves", "transcriptAfter", "The next"));
      events.accept("{\"id\":\"e3\",\"turnComplete\":true,\"partial\":false}");
      trace.record("STATE.SAVE_REQUEST", LiveTrace.fields("route", "42"));
      trace.record("DB.commit.RETURNED", LiveTrace.fields("synthetic", true, "connectionId", "demo-connection", "operationId", "demo-commit"));
      wire.message("WS.CLOSE_REQUESTED", "{}");
      wire.message("WS.CLOSE", "{\"code\":1000,\"remote\":false}");
      System.out.println(trace.path());
    }
  }
}
