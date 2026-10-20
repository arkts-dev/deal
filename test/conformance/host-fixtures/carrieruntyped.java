// Host fixture implementation for the three-lane untyped-nested-carrier
// round-trip conformance test. The host reports the member kinds it
// observed, invokes the function member with the declared argument, and
// echoes the container back. The echoed container is a fresh table
// carrier holding the same member values, so the values the host observed
// are the values DEAL receives back; the class value's identity, its
// fields and the alias are the host-readable shape of the declared table
// mapping.

final class HostCarrieruntyped {
  static String report(deal.codegen.jvm.JvmRuntime.Table t) {
    Object member = t.read("point");
    String kind = "false";
    String fields = "-";
    if (member instanceof deal.codegen.jvm.JvmRuntime.ClassInstance instance) {
      String name = String.valueOf(instance.classIdText());
      kind = String.valueOf(name.endsWith("/Point"));
      fields = String.valueOf(instance.read("x")) + ","
          + String.valueOf(instance.read("y"));
    }
    String alias = String.valueOf(t.read("point") == t.read("twin"));
    String stepText = "none";
    Object step = t.read("step");
    if (step instanceof deal.codegen.jvm.JvmRuntime.FunctionValue function) {
      try {
        stepText = "true:" + String.valueOf(
            function.fn.invoke(new Object[] { Long.valueOf(41) }));
      } catch (RuntimeException failure) {
        stepText = "false:" + failure.getMessage();
      }
    }
    return kind + "|" + fields + "|" + alias + "|" + stepText;
  }

  static deal.codegen.jvm.JvmRuntime.Table copyTable(
      deal.codegen.jvm.JvmRuntime.Table source) {
    deal.codegen.jvm.JvmRuntime.Table out = new deal.codegen.jvm.JvmRuntime.Table();
    for (String key : source.keys) {
      out.write(key, source.entries.get(key));
    }
    return out;
  }

  public static Object roundTrip(Object value) {
    deal.codegen.jvm.JvmRuntime.Table t = (deal.codegen.jvm.JvmRuntime.Table) value;
    String observed = report(t);
    deal.codegen.jvm.JvmRuntime.Table out = copyTable(t);
    out.write("observed", observed);
    return out;
  }

  public static Object roundTripArray($DealRt.__RefArray value) {
    Object[] elements = new Object[value.data.length];
    for (int index = 0; index < elements.length; index++) {
      elements[index] = copyTable(
          (deal.codegen.jvm.JvmRuntime.Table) value.data[index]);
    }
    return new $DealRt.__RefArray(elements);
  }
}
