final class HostProbe {
  private static deal.codegen.jvm.JvmRuntime.Array saved;
  public static void keep(Object value) { saved = (deal.codegen.jvm.JvmRuntime.Array) value; }
  public static Object echo(Object value) { return value; }
  public static void mutate(double value) { saved.elements.set(0, value); }
  public static void corrupt() { saved.elements.set(0, "bad"); }
  public static Object callback($DealRt.Fn1_N_R_N f, double value) { return f.invoke(value); }
}
