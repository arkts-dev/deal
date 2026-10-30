import java.util.concurrent.CompletableFuture;

// Host fixture implementation for the three-lane arity-extended
// adapter-over-async conformance test. The declared async export reports
// the exact argument count the invocation received (the two declared
// parameters), reads the class argument's declared field, invokes the
// function argument with the declared argument and returns an operation
// carrying the declared result. The extra target parameter of an
// arity-extended call is never forwarded, so it is not a parameter of this
// method.

final class HostCarrierarity {
  public static final String Target = "@$external/host.carrierarity/Target";
  // Transcript formatting belongs to the fixture, not the DEAL numeric kind.
  static String numericText(Object value) {
    if (value instanceof Number n) {
      return java.math.BigDecimal.valueOf(n.doubleValue()).stripTrailingZeros().toPlainString();
    }
    return String.valueOf(value);
  }

  public static final java.util.Map<String, Object> Target_defaults =
      java.util.Map.of("name", "");

  /**
   * The received argument count of one crossing on this lane. The emitted
   * crossing builds a fixed argument array from the declared parameters and
   * invokes the resolved host method reflectively, so an invocation that
   * reaches this body received exactly the surface's own parameter count; a
   * forwarded extra target parameter makes the reflective invocation fail
   * closed (E8010 "host function invocation failed") before this body runs.
   * The surface's own arity is therefore the observed count.
   */
  static int receivedArity() {
    try {
      return HostCarrierarity.class.getDeclaredMethod("describeAsync",
          $DealRt.$Host$host$scarrierarity$Target.class,
          $DealRt.Fn1_N_R_N.class).getParameterCount();
    } catch (java.lang.NoSuchMethodException missing) {
      return -1;
    }
  }

  public static Object describeAsync(
      $DealRt.$Host$host$scarrierarity$Target t, $DealRt.Fn1_N_R_N g) {
    String gText = "none";
    if (g != null) {
      try {
        gText = "true:" + numericText(g.invoke(41));
      } catch (RuntimeException failure) {
        gText = "false:" + failure.getMessage();
      }
    }
    return CompletableFuture.completedFuture(
        "T|" + t.name + "|" + gText + "|n=" + receivedArity());
  }
}
