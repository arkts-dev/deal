public final class HostArray_roundtrip {
  private static int calls;
  private static final deal.codegen.jvm.JvmRuntime.Array shared = sharedArrayValue();
  private static deal.codegen.jvm.JvmRuntime.Array sharedArrayValue() {
    var value = new deal.codegen.jvm.JvmRuntime.Array(2);
    value.elements.add(Double.valueOf(0));
    value.elements.add(Double.valueOf(0));
    return value;
  }
  public static Object echoArray($DealRt.__NumberArray b) { calls += 1; return b; }
  public static Object nullableArray($DealRt.__NumberArray b) { calls += 1; return b; }
  public static Object makeArray(double n) { calls += 1; return new $DealRt.__NumberArray(new double[(int) n]); }
  public static Object sharedArray() { return shared; }
  public static double readElement($DealRt.__NumberArray b) { calls += 1; return b.data[0]; }
  public static double callCount() { return calls; }
  public static Object badArrayReturn() { calls += 1; return "not-array"; }
}
