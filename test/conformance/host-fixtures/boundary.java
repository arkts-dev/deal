public final class HostBoundary {
  private static int nextValue;

  public static Object intValue() { return Double.valueOf(17); }
  public static Object stringValue() { return "host"; }
  public static Object nullableString(boolean flag) { return flag ? "host" : null; }
  public static Object nullValue() { return null; }
  public static Object nextValue() { nextValue += 1; return Double.valueOf(nextValue); }
  public static Object echoInt(double value) { return Double.valueOf(value); }
  public static Object echoNumber(double value) { return Double.valueOf(value); }
  public static Object echoBoolean(boolean value) { return Boolean.valueOf(value); }
  public static Object echoString(String value) { return value; }
  public static Object nullableInt(Double value) { return value; }
  public static Object extraExport() { return "ignored"; }
}
