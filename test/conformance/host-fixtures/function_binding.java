// Host fixture implementation for the host-origin function-value binding
// subject: two same-signature exports (inc/dec) and a call trace naming the
// export each invocation reached, so a stale dispatch is observable.

final class HostFunction_binding {
  private static String trace = "";

  public static Object inc(int x) {
    trace = trace + "I";
    return Integer.valueOf(x + 1);
  }

  public static Object dec(int x) {
    trace = trace + "D";
    return Integer.valueOf(x - 1);
  }

  public static Object trace() {
    return trace;
  }
}
