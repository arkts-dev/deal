final class HostArray_join {
  private static deal.codegen.jvm.JvmRuntime.Array saved;
  public static void keep(Object value) { saved=(deal.codegen.jvm.JvmRuntime.Array)value; }
  public static void replace(double index,Object value) { saved.elements.set((int)index,value); }
  public static void append(String value) { saved.elements.add(value); saved.length++; }
  public static double size() { return saved.length(); }
}
