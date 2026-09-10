package dev.adk.trace;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;

/** Observation-only proxy. Install at the actual helper connection, not around appendEvent alone. */
public final class JdbcTrace {
  private JdbcTrace() {}
  public static Connection wrap(Connection real, LiveTrace trace) {
    final String connectionId = UUID.randomUUID().toString();
    trace.record("DB.CONNECTION_ACQUIRED", LiveTrace.fields("connectionId", connectionId));
    return (Connection) Proxy.newProxyInstance(JdbcTrace.class.getClassLoader(), new Class<?>[] {Connection.class},
        (proxy, method, args) -> {
          String name = method.getName();
          boolean operation = name.equals("commit") || name.equals("rollback") || name.equals("close")
              || name.equals("setAutoCommit") || name.equals("setSavepoint") || name.equals("releaseSavepoint");
          String opId = UUID.randomUUID().toString();
          long start = System.nanoTime();
          if (operation) trace.record("DB." + name + ".BEGIN", LiveTrace.fields("connectionId", connectionId, "operationId", opId,
              "autoCommitValue", name.equals("setAutoCommit") ? args[0] : null));
          try {
            Object result = invoke(real, method, args);
            if (operation) trace.record("DB." + name + ".RETURNED", LiveTrace.fields("connectionId", connectionId,
                "operationId", opId, "durationNanos", System.nanoTime() - start));
            if (result instanceof Statement) {
              String sql = args != null && args.length > 0 && args[0] instanceof String ? (String) args[0] : "";
              return statement((Statement) result, sql, trace, connectionId);
            }
            return result;
          } catch (Throwable e) {
            if (operation) {
              trace.record("DB." + name + ".FAILED", LiveTrace.fields("connectionId", connectionId, "operationId", opId));
              trace.error("DB.ERROR", e);
            }
            throw e;
          }
        });
  }
  private static Statement statement(Statement real, String template, LiveTrace trace, String connectionId) {
    Class<?> api = real instanceof CallableStatement ? CallableStatement.class
        : real instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
    return (Statement) Proxy.newProxyInstance(JdbcTrace.class.getClassLoader(), new Class<?>[] {api},
        (proxy, method, args) -> {
          if (!method.getName().startsWith("execute")) return invoke(real, method, args);
          String sql = args != null && args.length > 0 && args[0] instanceof String ? (String) args[0] : template;
          String id = UUID.randomUUID().toString();
          long start = System.nanoTime();
          trace.record("DB.STATEMENT.BEGIN", LiveTrace.fields("connectionId", connectionId, "operationId", id,
              "method", method.getName(), "sqlTemplate", sql, "parameters", "not logged; state delta logged at ADK boundary"));
          try {
            Object result = invoke(real, method, args);
            trace.record("DB.STATEMENT.RETURNED", LiveTrace.fields("connectionId", connectionId, "operationId", id,
                "durationNanos", System.nanoTime() - start, "result", result instanceof Number || result instanceof Boolean ? result : null,
                "checkpointStatement", sql.trim().matches("(?is)^CHECKPOINT(?:\\s|;|$).*")));
            return result;
          } catch (Throwable e) {
            trace.record("DB.STATEMENT.FAILED", LiveTrace.fields("connectionId", connectionId, "operationId", id));
            trace.error("DB.ERROR", e); throw e;
          }
        });
  }
  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try { return method.invoke(target, args); } catch (InvocationTargetException e) { throw e.getCause(); }
  }
}
