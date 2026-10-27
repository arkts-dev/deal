final class HostLanguage15 {
  public static Object echo(Object value) { return value; }
  public static Object undefinedValue() { return deal.codegen.jvm.JvmRuntime.UNDEFINED; }
  public static void nothing() {}
  public static Object maybeNumber(Object value) { return value; }
  public static Object maybeString(Object value) { return value; }
  public static Object maybeBoolean(Object value) { return value; }
  public static Object mutate($DealRt.__NumberArray value) { value.data[0] = 9; return value; }
  public static Object fresh() { return new $DealRt.__NumberArray(new double[]{4}); }
  public static Object mutateClass(Object value) { ((deal.codegen.jvm.JvmRuntime.ClassInstance)value).write("n", 8.0); return value; }
  public static double classNumber(Object value) { return ((Number)((deal.codegen.jvm.JvmRuntime.ClassInstance)value).read("n")).doubleValue(); }
  public static void callback($DealRt.Fn0_R_$void value) { value.invoke(); }
  public static Object asyncUnknown() { return java.util.concurrent.CompletableFuture.completedFuture(deal.codegen.jvm.JvmRuntime.UNDEFINED); }
  public static Object asyncUndefined() { return asyncUnknown(); }
  public static Object asyncVoid() { return asyncUnknown(); }
  public static Object invalidNested() {
    var child = new deal.codegen.jvm.JvmRuntime.Table(); child.write("bad", "\ud800");
    var value = new deal.codegen.jvm.JvmRuntime.Table(); value.write("child", child); return value;
  }
  public static Object opaque() { return new Object(); }
  public static Object graph() {
    var value = new deal.codegen.jvm.JvmRuntime.Table(); var child = new deal.codegen.jvm.JvmRuntime.Table();
    value.write("self", value); value.write("a", child); value.write("b", child); return value;
  }
}
