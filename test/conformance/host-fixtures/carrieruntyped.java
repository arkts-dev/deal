// Host fixture implementation for the three-lane untyped-nested-carrier
// round-trip conformance test. The host reports the member kinds it
// observed (the DEAL class instance identity and top-level fields, the
// function wrapper and its invocation result, and the builtin Error
// instance identity with its code/message), and echoes the container back.
// The echoed container is a fresh table carrier holding the same member
// values, so the values the host observed are the values DEAL receives
// back. The array export reports each element's class member the same way
// and the observed alias identity between the elements.

final class HostCarrieruntyped {
  // Transcript formatting belongs to the fixture, not the DEAL numeric kind.
  static String numericText(Object value) {
    if (value instanceof Number n) {
      return java.math.BigDecimal.valueOf(n.doubleValue()).stripTrailingZeros().toPlainString();
    }
    return String.valueOf(value);
  }

  static String report(deal.codegen.jvm.JvmRuntime.Table t) {
    Object member = t.read("point");
    String kind = "false";
    String fields = "-";
    if (member instanceof deal.codegen.jvm.JvmRuntime.ClassInstance instance) {
      String name = String.valueOf(instance.classIdText());
      kind = String.valueOf(name.endsWith("/Point"));
      fields = numericText(instance.read("x")) + ","
          + numericText(instance.read("y"));
    }
    String alias = String.valueOf(t.read("point") == t.read("twin"));
    String stepText = "none";
    Object step = t.read("step");
    if (step instanceof deal.codegen.jvm.JvmRuntime.FunctionValue function) {
      try {
        stepText = "true:" + numericText(
            function.fn.invoke(new Object[] { Double.valueOf(41) }));
      } catch (RuntimeException failure) {
        stepText = "false:" + failure.getMessage();
      }
    }
    String errText = "none";
    Object err = t.read("err");
    if (err instanceof deal.codegen.jvm.JvmRuntime.ClassInstance error
        && "Error".equals(error.classIdText())) {
      errText = "true:" + String.valueOf(error.read("code")) + ":"
          + String.valueOf(error.read("message"));
    }
    return kind + "|" + fields + "|" + alias + "|" + stepText + "|" + errText;
  }

  // One array element's class member as the host observes it: the declared
  // class identity and the top-level declared fields. A member left in a
  // DEAL-internal representation carries no host-readable class tag and no
  // top-level field, so it reports "false|-" here.
  static String reportElement(deal.codegen.jvm.JvmRuntime.Table item) {
    Object member = item.read("point");
    if (!(member instanceof deal.codegen.jvm.JvmRuntime.ClassInstance instance)) {
      return "false|-";
    }
    String name = String.valueOf(instance.classIdText());
    return String.valueOf(name.endsWith("/Point")) + "|"
        + numericText(instance.read("x")) + ","
        + numericText(instance.read("y"));
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
    Object firstPoint = value.data.length > 0
        ? ((deal.codegen.jvm.JvmRuntime.Table) value.data[0]).read("point")
        : null;
    Object[] elements = new Object[value.data.length];
    for (int index = 0; index < elements.length; index++) {
      deal.codegen.jvm.JvmRuntime.Table item =
          (deal.codegen.jvm.JvmRuntime.Table) value.data[index];
      deal.codegen.jvm.JvmRuntime.Table out = copyTable(item);
      String alias = index == 0 ? "self"
          : String.valueOf(item.read("point") == firstPoint);
      out.write("observed", reportElement(item) + "|" + alias);
      elements[index] = out;
    }
    return new $DealRt.__RefArray(elements);
  }
}
