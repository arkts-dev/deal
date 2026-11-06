final class HostProbe {
  private static deal.codegen.jvm.JvmRuntime.Array saved;
  private static $DealRt.__NumberArray live;
  public static void keep(Object value) { saved = (deal.codegen.jvm.JvmRuntime.Array)value; live = null; }
  public static void corrupt() { saved.elements.set(saved.length()-1, "bad"); }
  public static void repair() { saved.elements.set(saved.length()-1, 9.0); }
  public static double size() { return live != null ? live.data.length : saved.length(); }
  public static Object echo(Object value) { return value; }
  private static deal.codegen.jvm.JvmRuntime.ClassInstance damaged;
  public static void damage(Object value, boolean restore) {
    if (!restore) damaged = (deal.codegen.jvm.JvmRuntime.ClassInstance)value;
    damaged.write("x", restore ? 9.0 : "bad");
  }
  private static deal.codegen.jvm.JvmRuntime.FunctionValue callable;
  private static deal.codegen.jvm.JvmRuntime.Fn body;
  private static String signature, spec;
  private static void replaceSignature(String value, String canonical) {
    try {
      var field=callable.getClass().getField("signature"); field.setAccessible(true); field.set(callable,value);
      field=callable.getClass().getField("spec"); field.setAccessible(true); field.set(callable,canonical);
    }
    catch (ReflectiveOperationException e) { throw new AssertionError(e); }
  }
  private static void replaceBody(deal.codegen.jvm.JvmRuntime.Fn value) {
    try { var field=callable.getClass().getField("fn"); field.setAccessible(true); field.set(callable,value); }
    catch (ReflectiveOperationException e) { throw new AssertionError(e); }
  }
  private static void rememberFunction(Object value) {
    callable=(deal.codegen.jvm.JvmRuntime.FunctionValue)value; body=callable.fn; signature=callable.signature; spec=callable.spec;
  }
  public static void poison(Object value) { rememberFunction(value); replaceBody(null); }
  public static void poisonSignature(Object value) { rememberFunction(value); replaceSignature("garbage", "garbage"); }
  public static void restoreFunction() { replaceBody(body); replaceSignature(signature, spec); }
  public static Object make() { live = new $DealRt.__NumberArray(new double[]{4,9}); return live; }
  public static void grow() { int n=live.data.length; live.data=java.util.Arrays.copyOf(live.data,n+1); live.data[n]=11; }
}
