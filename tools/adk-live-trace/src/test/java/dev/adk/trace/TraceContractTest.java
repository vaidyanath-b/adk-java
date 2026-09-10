package dev.adk.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Executable contract tests, no ADK credentials, audio hardware or database required. */
public final class TraceContractTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static int assertions;
  static void check(boolean test, String message) {
    assertions++;
    if (!test) throw new AssertionError(message);
  }
  static String read(Path path) throws IOException { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
  static List<JsonNode> records(String text) throws IOException {
    List<JsonNode> out = new ArrayList<>();
    for (String chunk : text.split("\n=== ")) {
      int end = chunk.indexOf(" ===\n");
      if (end >= 0) out.add(JSON.readTree(chunk.substring(end + 5)));
    }
    return out;
  }
  public static void main(String[] args) throws Exception {
    Path root = args.length == 0 ? Files.createTempDirectory("adk-trace-tests") : Paths.get(args[0]);
    Files.createDirectories(root);
    String pcm = Base64.getEncoder().encodeToString(new byte[640]);
    ObjectNode media = LiveTrace.fields("mime_type", "audio/pcm;rate=16000", "data", pcm);
    ObjectNode input = LiveTrace.fields("blob", media, "Authorization", "Bearer secret", "text", "hello\nworld",
        "functionCall", LiveTrace.fields("name", "lookup", "args", LiveTrace.fields("data", "keep business data")));
    JsonNode clean = LiveTrace.sanitize(input);
    check(clean.path("blob").path("data").asText().equals("[PCM Audio Data: 640 bytes omitted]"), "PCM size");
    check(input.path("blob").path("data").asText().equals(pcm), "input must not mutate");
    check(clean.path("Authorization").asText().contains("omitted"), "credential redaction");
    check(clean.path("functionCall").path("args").path("data").asText().equals("keep business data"), "business data retained");
    check(LiveTrace.sanitize(LiveTrace.fields("mimeType", "audio/pcm", "data", new int[] {0,1,2})).path("data").asText().contains("3 bytes"), "numeric array");
    check(LiveTrace.sanitize(LiveTrace.fields("mimeType", "audio/pcm", "data", new byte[9])).path("data").asText().contains("9 bytes"), "binary node");
    check(LiveTrace.sanitize(LiveTrace.fields("mimeType", "image/jpeg", "data", "AAAA")).path("data").asText().contains("3 bytes"), "image omitted");
    check(LiveTrace.sanitize(LiveTrace.fields("mimeType", "audio/pcm", "data", "not!base64")).path("data").asText().contains("unknown"), "invalid base64");
    for (int size = 0; size < 20; size++) {
      String b64 = Base64.getEncoder().encodeToString(new byte[size]);
      check(LiveTrace.sanitize(LiveTrace.fields("mimeType", "audio/pcm", "data", b64)).path("data").asText().contains(": " + size + " bytes"), "base64 padding " + size);
    }

    LiveTrace trace = new LiveTrace(root, "app", "user", "../../session", false);
    check(trace.path().getParent().equals(root), "safe filename");
    WireObserver observer = WireObserver.forTrace(trace);
    try (java.io.Closeable registration = TraceRegistry.register("session-test", trace)) {
      check(TraceRegistry.find("session-test") == trace, "registration identity");
      try { TraceRegistry.register("session-test", trace); throw new AssertionError("duplicate registration"); }
      catch (IllegalStateException expected) { check(true, "duplicate rejected"); }
    }
    check(TraceRegistry.find("session-test") == null, "registration cleanup");
    observer.message("WS.CONNECT_BEGIN", "{}");
    check(!trace.awaitTransportsClosed(0), "open transport detected");
    observer.message("WIRE.OUT.REALTIME", JSON.writeValueAsString(input));
    observer.message("WIRE.IN.BAD", "{\"blob\":\"TOP_SECRET_RAW");
    EventObserver events = new EventObserver(trace);
    events.accept("{\"id\":\"e1\",\"inputTranscription\":{\"text\":\"hello\"}}");
    events.accept("{\"id\":\"e2\",\"partial\":true,\"outputTranscription\":{\"text\":\"hi\",\"finished\":false}}");
    events.accept("{\"id\":\"e3\",\"interrupted\":true}");
    events.accept("{\"id\":\"e4\",\"turnComplete\":true}");
    events.accept("{\"id\":\"e5\",\"output_transcription\":{\"text\":\"bye\",\"finished\":true}}");
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < 8; t++) {
      final int thread = t;
      futures.add(pool.submit(() -> { for (int i = 0; i < 100; i++) trace.record("CONCURRENT", LiveTrace.fields("producer", thread, "index", i)); }));
    }
    for (Future<?> f : futures) f.get();
    pool.shutdown();
    AtomicInteger commits = new AtomicInteger();
    PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(TraceContractTest.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
        (p,m,a) -> m.getName().equals("executeUpdate") ? 1 : defaultValue(m.getReturnType()));
    Connection real = (Connection) Proxy.newProxyInstance(TraceContractTest.class.getClassLoader(), new Class<?>[] {Connection.class},
        (p,m,a) -> {
          if (m.getName().equals("commit")) { if (commits.incrementAndGet() == 2) throw new SQLException("sensitive message"); return null; }
          if (m.getName().equals("prepareStatement")) return statement;
          return defaultValue(m.getReturnType());
        });
    Connection wrapped = JdbcTrace.wrap(real, trace);
    wrapped.setAutoCommit(false);
    check(wrapped.prepareStatement("UPDATE sessions SET state=? WHERE id=?").executeUpdate() == 1, "JDBC result unchanged");
    wrapped.commit();
    try { wrapped.commit(); throw new AssertionError("expected SQLException"); } catch (SQLException expected) { check(expected.getMessage().equals("sensitive message"), "JDBC exception preserved"); }
    wrapped.rollback(); wrapped.close();
    observer.message("WS.CLOSE", "{\"code\":1000}");
    check(trace.awaitTransportsClosed(0), "close callback drains transport");
    trace.close(); trace.close();
    String text = read(trace.path());
    check(!text.contains(pcm), "no audio on disk");
    check(!text.contains("TOP_SECRET_RAW"), "no malformed raw fallback");
    check(!text.contains("sensitive message"), "no exception message leak");
    check(text.contains("DB.commit.RETURNED") && text.contains("DB.commit.FAILED") && text.contains("DB.rollback.RETURNED"), "transaction outcomes");
    List<JsonNode> parsed = records(text);
    long seq = 0; int concurrent = 0; int transcripts = 0;
    for (JsonNode r : parsed) {
      check(r.path("sequence").asLong() == ++seq, "contiguous sequence");
      check(r.path("epochMs").asLong() == java.time.Instant.parse(r.path("observedAt").asText()).toEpochMilli(), "timestamp consistency");
      if (r.path("payload").has("producer")) concurrent++;
      if (r.path("payload").has("side")) {
        transcripts++;
        if (r.path("payload").path("eventId").asText().equals("e1")) check(r.path("payload").path("adkPartial").isNull(), "absence not inferred final");
      }
    }
    check(concurrent == 800, "no concurrency loss"); check(transcripts == 3, "transcript fields and snake case");
    try (LiveTrace other = new LiveTrace(root, "a", "u", "session", true)) {
      other.record("ISOLATION", LiveTrace.fields("text", "other session"));
      check(!other.path().equals(trace.path()), "unique run files");
    }
    trace.record("LATE", LiveTrace.fields());
    try { trace.checkHealthy(); throw new AssertionError("late record must fail health"); } catch (IOException expected) { check(true, "late writes surfaced"); }
    System.out.println("PASS " + assertions + " assertions; 800 concurrent records; redaction, timestamps, transcripts, JDBC and lifecycle.");
    System.out.println("Fixture log: " + trace.path());
  }
  static Object defaultValue(Class<?> c) {
    if (c == boolean.class) return false;
    if (c == int.class) return 0;
    if (c == long.class) return 0L;
    return null;
  }
}
