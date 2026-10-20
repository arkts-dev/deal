import java.util.concurrent.CompletableFuture;

// Host fixture implementation for the three-lane arity-extended
// adapter-over-async conformance test. The declared async export reads the
// class argument's declared field, invokes the function argument with the
// declared argument and returns an operation carrying the declared result.
// The extra target parameter of an arity-extended call is never forwarded,
// so it is not a parameter of this method.

final class HostCarrierarity {
  public static final java.util.Map<String, Object> Target_defaults =
      java.util.Map.of("name", "");

  public static Object describeAsync(
      $DealRt.$Host$host$scarrierarity$Target t, $DealRt.Fn1_I_R_I g) {
    String gText = "none";
    if (g != null) {
      try {
        gText = "true:" + g.invoke(41);
      } catch (RuntimeException failure) {
        gText = "false:" + failure.getMessage();
      }
    }
    return CompletableFuture.completedFuture("T|" + t.name + "|" + gText);
  }
}
