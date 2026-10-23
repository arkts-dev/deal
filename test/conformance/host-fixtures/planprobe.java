

final class HostPlanprobe {
  private static long n = 0L;

  public static Object nextValue() {
    n = n + 1;
    return Double.valueOf(n * 10L);
  }

  public static Object valueCount() {
    return Double.valueOf(n);
  }
}
