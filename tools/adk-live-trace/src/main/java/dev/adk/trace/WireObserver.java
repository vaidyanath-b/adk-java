package dev.adk.trace;

/** SDK-neutral interface. Pass the same instance explicitly to connect and AsyncSession. */
public interface WireObserver {
  WireObserver NONE = (tag, json) -> {};
  void message(String tag, String json);
  default void failure(String tag, Throwable error) {}
  static WireObserver forTrace(LiveTrace trace) {
    return new WireObserver() {
      private final String connectionId = java.util.UUID.randomUUID().toString();
      @Override public void message(String tag, String json) {
        LiveTrace.Stamp stamp = LiveTrace.now();
        if (tag.equals("WS.CONNECT_BEGIN")) trace.transportOpened(connectionId);
        trace.json(stamp, tag, json, connectionId);
        if (tag.equals("WS.CLOSE")) trace.transportClosed(connectionId);
      }
      @Override public void failure(String tag, Throwable error) { trace.error(tag, error); }
    };
  }
}
