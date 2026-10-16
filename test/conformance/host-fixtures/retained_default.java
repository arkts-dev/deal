import deal.codegen.jvm.JvmRuntime;

// Host fixture implementation for the legal module-level default-call
// witness: one retained shared runtime array is returned by every provider
// call, and the counter plus the declaration-order trace are
// invocation-local to one host module load (a fresh process starts at
// zero, so any leakage is observable in the witness's exact counts).

final class HostRetained_default {
  private static final JvmRuntime.Array ORIGINAL = makeOriginal();
  private static int calls = 0;
  private static String order = "";

  private static JvmRuntime.Array makeOriginal() {
    JvmRuntime.Array array = new JvmRuntime.Array(2);
    array.elements.add(Long.valueOf(1));
    array.elements.add(Long.valueOf(2));
    return array;
  }

  public static Object retained() {
    return ORIGINAL;
  }

  public static Object provideFirst() {
    calls = calls + 1;
    order = order + "A";
    return ORIGINAL;
  }

  public static Object provideSecond() {
    calls = calls + 1;
    order = order + "B";
    return ORIGINAL;
  }

  public static Object bump() {
    calls = calls + 1;
    return null;
  }

  public static Object calls() {
    return Integer.valueOf(calls);
  }

  public static Object order() {
    return order;
  }
}
