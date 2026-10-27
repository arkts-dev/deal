public final class HostContainer_mutation {
  public static Object replaceFirst(Object holder, Object replacement) {
    var h = (deal.codegen.jvm.JvmRuntime.Table) holder;
    var r = (deal.codegen.jvm.JvmRuntime.Table) replacement;
    var slots = (deal.codegen.jvm.JvmRuntime.Array) h.read("slots");
    var replacementArray = (deal.codegen.jvm.JvmRuntime.Array) r.read("value");
    slots.elements.set(0, replacementArray.elements.get(0));
    return Boolean.TRUE;
  }
  public static Object replaceAt(Object holder, Object replacement, double index) {
    var h = (deal.codegen.jvm.JvmRuntime.Table) holder;
    var r = (deal.codegen.jvm.JvmRuntime.Table) replacement;
    var slots = (deal.codegen.jvm.JvmRuntime.Array) h.read("slots");
    var values = (deal.codegen.jvm.JvmRuntime.Array) r.read("value");
    slots.elements.set((int) index, values.elements.get((int) index));
    return Boolean.TRUE;
  }
}
