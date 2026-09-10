package dev.adk.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Records normalized ADK events separately from raw wire; does not infer ASR finality. */
public final class EventObserver {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final LiveTrace trace;
  private long boundary;
  public EventObserver(LiveTrace trace) { this.trace = trace; }
  public synchronized void accept(String eventJson) {
    LiveTrace.Stamp stamp = LiveTrace.now();
    trace.json(stamp, "ADK.EVENT", eventJson);
    try {
      JsonNode e = JSON.readTree(eventJson);
      for (String side : new String[] {"inputTranscription", "outputTranscription"}) {
        JsonNode t = field(e, side);
        if (!t.isMissingNode() && !t.isNull()) {
          trace.record(stamp, "TRANSCRIPT.OBSERVED", LiveTrace.fields("side", side,
              "boundaryIndex", boundary, "eventId", e.path("id"), "transcription", t,
              "adkPartial", e.path("partial"), "transcriptionFinished", t.path("finished"),
              "finality", "only explicit fields; boundary is not an ASR final marker"));
        }
      }
      if (e.path("interrupted").asBoolean(false)) {
        trace.record(stamp, "INTERRUPTION.ADK_OBSERVED", LiveTrace.fields("boundaryIndex", boundary,
            "eventId", e.path("id"), "playbackCleared", "not observable in ADK"));
      }
      if (field(e, "turnComplete").asBoolean(false)) {
        trace.record(stamp, "TURN.BOUNDARY", LiveTrace.fields("boundaryIndex", boundary++, "eventId", e.path("id")));
      }
    } catch (Exception e) { trace.error("ADK.EVENT_CLASSIFY_ERROR", e); }
  }
  private static JsonNode field(JsonNode n, String camel) {
    String snake = camel.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    return n.has(camel) ? n.path(camel) : n.path(snake);
  }
}
