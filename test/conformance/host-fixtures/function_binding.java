// Host fixture implementation for the host-origin function-value binding
// subject: two same-signature exports (inc/dec) and a call trace naming the
// export each invocation reached, so a stale dispatch is observable.

final class HostFunction_binding {
  private static String trace = "";

  public static Object inc(double x) {
    trace = trace + "I";
    return Double.valueOf(x + 1);
  }

  public static Object dec(double x) {
    trace = trace + "D";
    return Double.valueOf(x - 1);
  }

  public static Object trace() {
    return trace;
  }
}
