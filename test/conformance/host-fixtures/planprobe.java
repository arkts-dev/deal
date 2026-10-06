

final class HostPlanprobe {
  private static long n = 0L;

  public static Object nextValue() {
    n = n + 1;
    return Long.valueOf(n * 10L);
  }

  public static Object valueCount() {
    return Long.valueOf(n);
  }
}
