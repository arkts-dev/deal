public final class HostBoundary {
  private static long nextValue;

  public static Object intValue() { return Long.valueOf(17L); }
  public static Object stringValue() { return "host"; }
  public static Object nullableString(boolean flag) { return flag ? "host" : null; }
  public static Object nullValue() { return null; }
  public static Object nextValue() { nextValue += 1L; return Long.valueOf(nextValue); }
  public static Object echoInt(long value) { return Long.valueOf(value); }
  public static Object echoNumber(double value) { return Double.valueOf(value); }
  public static Object echoBoolean(boolean value) { return Boolean.valueOf(value); }
  public static Object echoString(String value) { return value; }
  public static Object nullableInt(Long value) { return value; }
  public static Object extraExport() { return "ignored"; }
}
