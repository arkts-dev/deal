import java.util.concurrent.CompletableFuture;

// Host fixture implementation for the three-lane host-carrier class-value
// conformance test (the ISSUE-0848 acceptance shapes). Each declared class
// exports its canonical host identity through the synthesized record's
// $identity and a <C>_defaults table (construction reads both). A class
// value crosses to the host as the generated record with its declared
// fields, a declared function parameter is the per-signature wrapper
// invoked through invoke, and the asynchronous exports return real backend
// async operations (completed futures) whose completions are the declared
// class values. Every produced instance carries value = 42 so each
// assertion proves the declared value survived the crossing.

final class HostCarrierclass {
  public static final String Config = "@$external/host.carrierclass/Config";
  public static final String Holder = "@$external/host.carrierclass/Holder";
  public static final java.util.Map<String, Object> Config_defaults =
      java.util.Map.of("value", Double.valueOf(0));

  public static final java.util.Map<String, Object> Holder_defaults =
      java.util.Map.of("label", "", "primary", new Object(),
          "optional", new Object(), "maybe", new Object(),
          "items", new Object());

  static $DealRt.$Host$host$scarrierclass$Config config(double value) {
    return new $DealRt.$Host$host$scarrierclass$Config(value);
  }

  static $DealRt.$Host$host$scarrierclass$Holder holder(
      $DealRt.$Host$host$scarrierclass$Config primary) {
    return new $DealRt.$Host$host$scarrierclass$Holder(
        "holder", primary, null, null, false,
        new $DealRt.$HostArr$host$scarrierclass$Config(
            new Object[] { config(42) }));
  }

  public static Object describeAsync(
      $DealRt.$Host$host$scarrierclass$Config c) {
    return CompletableFuture.completedFuture(Double.valueOf(c.value));
  }

  public static Object makeAsync() {
    return CompletableFuture.completedFuture(config(42));
  }

  public static Object makeSync() {
    return config(42);
  }

  public static Object makeAll() {
    $DealRt.$Host$host$scarrierclass$Config shared = config(42);
    return new $DealRt.$HostArr$host$scarrierclass$Config(
        new Object[] { shared, shared });
  }

  public static Object maybeMake(boolean flag) {
    return flag ? config(42) : null;
  }

  public static Object describeOrNull(
      $DealRt.$Host$host$scarrierclass$Config c) {
    return Double.valueOf(c == null ? -1 : c.value);
  }

  public static Object maybeMakeAsync(boolean flag) {
    return CompletableFuture.completedFuture(maybeMake(flag));
  }

  public static Object makeHolder() {
    return holder(config(42));
  }

  // The declared callback position's synthesized wrapper type: the shape
  // id escapes the canonical descriptor of the function type, so this
  // name carries the corpus lane's dotted external identity of Config.
  public static Object consume(
      $DealRt.Fn1_$$a$$external$shost$icarrierclass$sConfig_R_$$a$$external$shost$icarrierclass$sConfig g) {
    if (g == null) {
      return Double.valueOf(-2);
    }
    try {
      $DealRt.$Host$host$scarrierclass$Config result = g.invoke(config(42));
      return Double.valueOf(result == null ? -3 : result.value);
    } catch (RuntimeException failure) {
      return Double.valueOf(-4);
    }
  }

  public static Object makeWrongKind() {
    return Double.valueOf(42);
  }

  public static Object makeNullClass() {
    return null;
  }

  public static Object makeWrongElement() {
    return new $DealRt.$HostArr$host$scarrierclass$Config(
        new Object[] { config(42), "junk" });
  }

  public static Object makeSparse() {
    return new $DealRt.$HostArr$host$scarrierclass$Config(
        new Object[] { config(42), null, config(42) });
  }

  public static Object makeMap() {
    return java.util.Map.of("alpha", config(42));
  }
}
