package dev.adk.trace;

import java.io.Closeable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local registration for this fork's globally unique PostgreSQL session primary key.
 * Rejects simultaneous runs with the same ID; never uses thread affinity. Wire callbacks capture
 * a concrete observer at connection creation. Register before session load, remove after shutdown.
 */
public final class TraceRegistry {
  private static final ConcurrentHashMap<String, LiveTrace> ACTIVE = new ConcurrentHashMap<>();
  private TraceRegistry() {}
  public static Closeable register(String sessionId, LiveTrace trace) {
    if (ACTIVE.putIfAbsent(sessionId, trace) != null) throw new IllegalStateException("Session already traced");
    return () -> ACTIVE.remove(sessionId, trace);
  }
  public static LiveTrace find(String sessionId) { return ACTIVE.get(sessionId); }
  public static WireObserver observer(String sessionId) {
    LiveTrace trace = find(sessionId);
    return trace == null ? WireObserver.NONE : WireObserver.forTrace(trace);
  }
  public static void json(String sessionId, String tag, String json) {
    LiveTrace trace = find(sessionId); if (trace != null) trace.json(tag, json);
  }
  public static void event(String sessionId, String tag, Object... fields) {
    LiveTrace trace = find(sessionId); if (trace != null) trace.record(tag, LiveTrace.fields(fields));
  }
  public static void error(String sessionId, String tag, Throwable error) {
    LiveTrace trace = find(sessionId); if (trace != null) trace.error(tag, error);
  }
}
