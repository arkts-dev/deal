final class HostAudit15 {
  public static final String C = "@$external/host.audit15/C";
  static $DealRt.__RefArray retained;
  static $DealRt.__NumberArray growing;
  static $DealRt.Fn0_R_$void callback;
  static $DealRt.__RefArray unknown;
  public static Object badClass(double kind) {
    return new $DealRt.$Host$host$saudit15$C(kind==0?null:kind==1?"\ud800":null);
  }
  public static Object nullish() { return deal.codegen.jvm.JvmRuntime.UNDEFINED; }
  public static Object echoArray($DealRt.__RefArray value) { return value; }
  public static void replace($DealRt.__RefArray value) { value.data[0]=2.0; }
  public static Object numbers($DealRt.__RefArray value) { return value; }
  public static Object booleans($DealRt.__RefArray value) { return value; }
  public static Object strings($DealRt.__RefArray value) { return value; }
  public static void keep($DealRt.__RefArray value) { retained=value; }
  public static boolean seesUndefined() { return retained.data[0]==deal.codegen.jvm.JvmRuntime.UNDEFINED; }
  public static Object fresh() { growing=new $DealRt.__NumberArray(new double[]{1});return growing; }
  public static void grow() { growing.data=new double[]{1,2}; }
  public static Object conflict() { var a=new deal.codegen.jvm.JvmRuntime.Array(1);a.elements.add(1.0);a.elementDescriptor="number";return a; }
  public static boolean callbacks($DealRt.Fn0_R_$void a,$DealRt.Fn0_R_$void b) {
    boolean same=a==b && (callback==null || callback==a);callback=a;return same;
  }
  public static Object echoFunction($DealRt.Fn0_R_$void value) { return value; }
  public static void nativeFailure() { throw new RuntimeException("native"); }
  public static Object nativeCallable() { return new $DealRt.Fn0_R_$void(){public Object invoke(){throw new RuntimeException("callable");}}; }
  public static void callbackFailure($DealRt.Fn0_R_$void value) { value.invoke(); }
  public static Object asyncFailure() { return java.util.concurrent.CompletableFuture.failedFuture(new RuntimeException("native async")); }
  public static Object asyncCallback($DealRt.Fn0_R_$void value) { return java.util.concurrent.CompletableFuture.supplyAsync(()->{value.invoke();return deal.codegen.jvm.JvmRuntime.UNDEFINED;}); }
  public static void echoError(Object value) { throw deal.codegen.jvm.JvmRuntime.raise((deal.codegen.jvm.JvmRuntime.ErrorValue)value,"host"); }
  public static void keepUnknown($DealRt.__RefArray value) { unknown=value; }
  public static void corrupt(double kind) { unknown.data[0]=kind==0?"\ud800":new Object(); }
  public static Object opaqueArray() { return new $DealRt.__RefArray(new Object[]{new Object()}); }
}
