package dev.adk.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** One explicitly-owned sink per invocation. No ThreadLocal or process-global session binding. */
public final class LiveTrace implements Closeable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final DateTimeFormatter ISO = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
  private final FileChannel channel;
  private final Path path;
  private final ObjectNode identity;
  private final long origin = System.nanoTime();
  private final boolean forceEachRecord;
  private long sequence;
  private long failedRecords;
  private IOException failure;
  private boolean closed;
  private final java.util.Set<String> transports = new java.util.HashSet<>();

  /** Capture at the callback boundary, before formatting or lock acquisition. */
  public static final class Stamp {
    public final Instant instant;
    public final long nanoTime;
    private Stamp() { instant = Instant.now(); nanoTime = System.nanoTime(); }
  }
  public static Stamp now() { return new Stamp(); }

  public LiveTrace(Path directory, String app, String user, String session, boolean forceEachRecord)
      throws IOException {
    Files.createDirectories(directory);
    String run = UUID.randomUUID().toString();
    String safe = session.replaceAll("[^A-Za-z0-9._-]", "_");
    safe = safe.substring(0, Math.min(safe.length(), 48));
    path = directory.resolve("adk-live-session-" + safe + "-" + run + ".log");
    channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    this.forceEachRecord = forceEachRecord;
    identity = fields("app", app, "user", user, "session", session, "run", run);
    record("TRACE_OPEN", fields("timing", "local observation; nanoTime durations; not server clock",
        "forceEachRecord", forceEachRecord));
  }

  public Path path() { return path; }
  public static ObjectNode fields(Object... pairs) {
    if (pairs.length % 2 != 0) throw new IllegalArgumentException("Expected key/value pairs");
    ObjectNode n = JSON.createObjectNode();
    for (int i = 0; i < pairs.length; i += 2) n.set(String.valueOf(pairs[i]), JSON.valueToTree(pairs[i + 1]));
    return n;
  }
  public void record(String tag, JsonNode payload) { record(now(), tag, payload); }

  /** Synchronous writes: no dropped queue items, but disk latency affects the caller. */
  public synchronized void record(Stamp stamp, String tag, JsonNode payload) {
    if (closed || failure != null) {
      failedRecords++;
      if (closed && failure == null) fail(new IOException("Record submitted after trace closed"));
      return;
    }
    try {
      ObjectNode record = JSON.createObjectNode();
      record.put("sequence", ++sequence);
      record.put("observedAt", ISO.format(stamp.instant));
      record.put("epochMs", stamp.instant.toEpochMilli());
      record.put("elapsedNanos", stamp.nanoTime - origin);
      record.put("thread", Thread.currentThread().getName());
      record.set("identity", identity);
      record.set("payload", sanitize(payload));
      String header = tag.replaceAll("[^A-Za-z0-9_.:-]", "_");
      byte[] bytes = ("\n=== " + header + " ===\n" + JSON.writerWithDefaultPrettyPrinter().writeValueAsString(record) + "\n")
          .getBytes(StandardCharsets.UTF_8);
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) channel.write(buffer);
      if (forceEachRecord) channel.force(false);
    } catch (IOException | RuntimeException e) {
      failedRecords++;
      fail(new IOException("Trace write or formatting failed", e));
    }
  }

  /** Do not fall back to printing malformed raw messages: they may contain media or credentials. */
  public void json(Stamp stamp, String tag, String serialized) {
    json(stamp, tag, serialized, null);
  }
  public void json(Stamp stamp, String tag, String serialized, String connectionId) {
    try {
      JsonNode n = JSON.readTree(serialized);
      record(stamp, tag, connectionId == null ? n : fields("connectionId", connectionId, "wire", n));
    } catch (IOException | RuntimeException e) {
      record(stamp, tag + ".UNPARSEABLE", fields("characters", serialized == null ? 0 : serialized.length(),
          "sha256", digest(serialized == null ? "" : serialized), "raw", "omitted", "parseFailureType", e.getClass().getName()));
    }
  }
  public void json(String tag, String serialized) { json(now(), tag, serialized); }

  public void error(String tag, Throwable error) {
    // Exception messages can embed request bodies or credentials; retain class/stack, not message.
    ArrayNode stack = JSON.createArrayNode();
    for (StackTraceElement frame : error.getStackTrace()) stack.add(frame.toString());
    record(tag, fields("exceptionType", error.getClass().getName(), "stack", stack));
  }

  public synchronized void checkHealthy() throws IOException {
    if (failure != null) throw new IOException("Incomplete trace: " + failedRecords + " failed/rejected records", failure);
  }
  synchronized void transportOpened(String id) { transports.add(id); }
  synchronized void transportClosed(String id) { transports.remove(id); notifyAll(); }
  /** Call after requesting shutdown, on the application owner thread, never on a socket callback. */
  public synchronized boolean awaitTransportsClosed(long timeoutMs) throws InterruptedException {
    if (timeoutMs < 0) throw new IllegalArgumentException("Negative timeout");
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    while (!transports.isEmpty()) {
      long left = deadline - System.nanoTime();
      if (left <= 0) return false;
      java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, left);
    }
    return true;
  }
  private void fail(IOException e) {
    if (failure == null) {
      failure = e;
      System.err.println("ADK live trace is incomplete; call checkHealthy() for details.");
    }
  }
  @Override public synchronized void close() throws IOException {
    if (closed) { checkHealthy(); return; }
    record("TRACE_CLOSE", fields("failedRecords", failedRecords));
    if (!transports.isEmpty()) fail(new IOException("Trace closed before transports terminated: " + transports.size()));
    closed = true;
    try { channel.force(false); } catch (IOException e) { fail(e); }
    try { channel.close(); } catch (IOException e) { fail(e); }
    checkHealthy();
  }

  /** Deep copy. Never mutates or replaces the payload passed to the real transport. */
  public static JsonNode sanitize(JsonNode n) {
    if (n == null) return JSON.nullNode();
    if (n.isObject()) {
      ObjectNode out = JSON.createObjectNode();
      String mime = n.path("mimeType").asText(n.path("mime_type").asText("")).toLowerCase(Locale.ROOT);
      Iterator<Map.Entry<String, JsonNode>> it = n.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> f = it.next();
        String key = f.getKey();
        String normalized = key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        if (normalized.equals("authorization") || normalized.equals("apikey") || normalized.equals("xgoogapikey")
            || normalized.equals("password") || normalized.equals("accesstoken") || normalized.equals("cookie")) {
          out.put(key, "[credential omitted]");
        } else if (key.equals("data") && !mime.isEmpty()) {
          long count = byteCount(f.getValue());
          String label = mime.startsWith("audio/pcm") ? "PCM Audio Data" : "Media Data";
          out.put(key, "[" + label + ": " + (count < 0 ? "unknown" : count) + " bytes omitted]");
        } else out.set(key, sanitize(f.getValue()));
      }
      return out;
    }
    if (n.isArray()) {
      ArrayNode out = JSON.createArrayNode();
      for (JsonNode v : n) out.add(sanitize(v));
      return out;
    }
    if (n.isBinary()) return TextNode.valueOf("[Binary Data: " + byteCount(n) + " bytes omitted]");
    return n.deepCopy();
  }

  private static long byteCount(JsonNode data) {
    if (data.isBinary()) {
      try { return data.binaryValue().length; } catch (IOException e) { return -1; }
    }
    if (data.isArray()) return data.size();
    if (!data.isTextual()) return -1;
    String s = data.textValue();
    int len = s.length(), padding = 0;
    if (len == 0) return 0;
    while (padding < len && s.charAt(len - 1 - padding) == '=') padding++;
    if (padding > 2 || (padding > 0 && len % 4 != 0)) return -1;
    int chars = len - padding;
    if (chars % 4 == 1 || (padding == 1 && chars % 4 != 3) || (padding == 2 && chars % 4 != 2)) return -1;
    for (int i = 0; i < chars; i++) {
      char c = s.charAt(i);
      if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '+' && c != '/') return -1;
    }
    return (long) chars * 6 / 8;
  }
  private static String digest(String s) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (byte b : d) out.append(String.format("%02x", b & 255));
      return out.toString();
    } catch (Exception e) { throw new IllegalStateException(e); }
  }
}
