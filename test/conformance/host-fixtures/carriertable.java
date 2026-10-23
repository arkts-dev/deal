import java.util.concurrent.CompletableFuture;

// Host fixture implementation for the three-lane host-carrier
// table-member conformance test. The declared classes export their
// canonical host identities through the synthesized records' $identity
// and a <C>_defaults table. A host-provided function value is a runtime
// function value carrying the declared descriptor, a class value is the
// declared record with its fields, and the table-typed return carries a
// class member and a function member that DEAL reads at their declared
// types. The table-typed parameter is inspected member by member: the
// DEAL class member must arrive as a class instance carrying its
// canonical identity and top-level fields, the alias must stay one
// object, and the DEAL function member must be invocable with its
// declared result.

final class HostCarriertable {
  // Transcript formatting belongs to the fixture, not the DEAL numeric kind.
  static String numericText(Object value) {
    if (value instanceof Number n) {
      return java.math.BigDecimal.valueOf(n.doubleValue()).stripTrailingZeros().toPlainString();
    }
    return String.valueOf(value);
  }

  public static final java.util.Map<String, Object> Widget_defaults =
      java.util.Map.of("label", "", "weight", Double.valueOf(0),
          "action", new Object());

  public static final java.util.Map<String, Object> Board_defaults =
      java.util.Map.of("title", "", "widgets", new Object(),
          "meta", new Object());

  // The table-typed positions carry the runtime function value (the
  // DEAL function value's declared host representation), while a declared
  // function-typed class field carries the per-signature host wrapper.
  static deal.codegen.jvm.JvmRuntime.FunctionValue functionValue() {
    return new deal.codegen.jvm.JvmRuntime.FunctionValue(
        args -> Double.valueOf(42L), "function(number;number)", "(number)->number", null);
  }

  static $DealRt.Fn1_N_R_N fieldValue() {
    return new $DealRt.Fn1_N_R_N() {
      double invoke(double x) {
        return 42;
      }
    };
  }

  static $DealRt.$Host$host$scarriertable$Widget widget(String label,
      double weight) {
    return new $DealRt.$Host$host$scarriertable$Widget(label, weight,
        fieldValue());
  }

  public static Object describeAsync(
      $DealRt.$Host$host$scarriertable$Widget w, $DealRt.Fn1_N_R_N g) {
    String gText = "none";
    if (g != null) {
      try {
        gText = "true:" + numericText(g.invoke(41));
      } catch (RuntimeException failure) {
        gText = "false:" + failure.getMessage();
      }
    }
    return CompletableFuture.completedFuture(
        "W|" + w.label + "|" + numericText(w.weight) + "|" + gText);
  }

  static deal.codegen.jvm.JvmRuntime.Table LAST_TABLE;
  static Object LAST_WIDGET;
  static Object LAST_ACTION;

  public static Object makeTable() {
    LAST_WIDGET = widget("made", 9);
    LAST_ACTION = functionValue();
    LAST_TABLE = new deal.codegen.jvm.JvmRuntime.Table();
    LAST_TABLE.write("widget", LAST_WIDGET);
    LAST_TABLE.write("action", LAST_ACTION);
    return LAST_TABLE;
  }

  // The host object the copied crossing projects: every member is still
  // the exact object the host put there (no replacement by a projected
  // value and no in-place rewrite of a member into another carrier), the
  // class member stays the host class instance with its declared identity
  // and fields, and the action member stays the host function wrapper.
  public static boolean tableIntact() {
    if (LAST_TABLE == null) {
      return false;
    }
    Object member = LAST_TABLE.read("widget");
    Object action = LAST_TABLE.read("action");
    return member == LAST_WIDGET && action == LAST_ACTION
        && member instanceof deal.codegen.jvm.JvmRuntime.ClassInstance instance
        && instance.classIdText().endsWith("/Widget")
        && action instanceof deal.codegen.jvm.JvmRuntime.FunctionValue;
  }

  public static Object describeTable(Object value) {
    deal.codegen.jvm.JvmRuntime.Table t = (deal.codegen.jvm.JvmRuntime.Table) value;
    Object point = t.read("point");
    String classed = "false";
    String fields = "-";
    if (point instanceof deal.codegen.jvm.JvmRuntime.ClassInstance instance) {
      String name = String.valueOf(instance.classIdText());
      classed = String.valueOf(name.endsWith("/Point"));
      fields = numericText(instance.read("x")) + ","
          + numericText(instance.read("y"));
    }
    String alias = String.valueOf(t.read("point") == t.read("twin"));
    String stepText = "none";
    Object member = t.read("step");
    if (member instanceof deal.codegen.jvm.JvmRuntime.FunctionValue function) {
      try {
        stepText = "true:" + numericText(
            function.fn.invoke(new Object[] { Double.valueOf(41) }));
      } catch (RuntimeException failure) {
        stepText = "false:" + failure.getMessage();
      }
    }
    return "T|" + classed + "|" + fields + "|" + alias + "|" + stepText;
  }

  public static Object makeBoard() {
    deal.codegen.jvm.JvmRuntime.Table meta = new deal.codegen.jvm.JvmRuntime.Table();
    meta.write("widget", widget("w1", 5));
    meta.write("action", functionValue());
    return new $DealRt.$Host$host$scarriertable$Board("board",
        new $DealRt.$HostArr$host$scarriertable$Widget(
            new Object[] { widget("w0", 9) }),
        meta);
  }
}
