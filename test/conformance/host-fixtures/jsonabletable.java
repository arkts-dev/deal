// Host fixture implementation for the three-lane @jsonable plain-table
// content conformance test (ISSUE-0868). Every declared table-typed return
// is the JVM runtime table carrier carrying the JSON-shaped data the class
// helper's toJson walk accepts: an object, an object with a nested object
// and an array member, and a self-referential table for the closed cycle
// arm. The closed arms' returns carry a non-finite numeric leaf and an
// invalid (unpaired-surrogate) object key, the two content shapes the
// finite acyclic JSON domain excludes.

final class HostJsonabletable {
  public static Object makeTable() {
    deal.codegen.jvm.JvmRuntime.Table t = new deal.codegen.jvm.JvmRuntime.Table();
    t.write("a", Long.valueOf(1L));
    return t;
  }

  public static Object makeNested() {
    deal.codegen.jvm.JvmRuntime.Table inner = new deal.codegen.jvm.JvmRuntime.Table();
    inner.write("a", Long.valueOf(1L));
    deal.codegen.jvm.JvmRuntime.Array xs = new deal.codegen.jvm.JvmRuntime.Array(2);
    xs.elements.add(Long.valueOf(10L));
    xs.elements.add(Long.valueOf(20L));
    deal.codegen.jvm.JvmRuntime.Table t = new deal.codegen.jvm.JvmRuntime.Table();
    t.write("inner", inner);
    t.write("xs", xs);
    return t;
  }

  public static Object makeCycle() {
    deal.codegen.jvm.JvmRuntime.Table t = new deal.codegen.jvm.JvmRuntime.Table();
    t.write("self", t);
    return t;
  }

  public static Object makeInfinite() {
    deal.codegen.jvm.JvmRuntime.Table t = new deal.codegen.jvm.JvmRuntime.Table();
    t.write("a", Double.valueOf(Double.POSITIVE_INFINITY));
    return t;
  }

  public static Object makeNotANumber() {
    deal.codegen.jvm.JvmRuntime.Table t = new deal.codegen.jvm.JvmRuntime.Table();
    t.write("a", Double.valueOf(Double.NaN));
    return t;
  }

  public static Object makeNestedInfinite() {
    deal.codegen.jvm.JvmRuntime.Table inner = new deal.codegen.jvm.JvmRuntime.Table();
    inner.write("a", Double.valueOf(Double.POSITIVE_INFINITY));
    deal.codegen.jvm.JvmRuntime.Table t = new deal.codegen.jvm.JvmRuntime.Table();
    t.write("inner", inner);
    return t;
  }

  public static Object makeInfiniteElements() {
    deal.codegen.jvm.JvmRuntime.Array xs = new deal.codegen.jvm.JvmRuntime.Array(2);
    xs.elements.add(Double.valueOf(Double.POSITIVE_INFINITY));
    xs.elements.add(Long.valueOf(2L));
    deal.codegen.jvm.JvmRuntime.Table t = new deal.codegen.jvm.JvmRuntime.Table();
    t.write("xs", xs);
    return t;
  }

  public static Object makeBadKey() {
    deal.codegen.jvm.JvmRuntime.Table t = new deal.codegen.jvm.JvmRuntime.Table();
    t.write("\uD800", Long.valueOf(1L));
    return t;
  }

  public static Object makeNestedBadKey() {
    deal.codegen.jvm.JvmRuntime.Table inner = new deal.codegen.jvm.JvmRuntime.Table();
    inner.write("\uD800", Long.valueOf(1L));
    deal.codegen.jvm.JvmRuntime.Table t = new deal.codegen.jvm.JvmRuntime.Table();
    t.write("inner", inner);
    return t;
  }
}
