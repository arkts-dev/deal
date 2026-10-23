import java.util.concurrent.CompletableFuture;

// Host fixture implementation for the three-lane host-carrier async-class
// conformance test. Each declared class exports its canonical host
// identity through the synthesized record's $identity and a
// <C>_defaults table (construction reads both). A class value crosses to
// the host as the generated record with its declared fields (the
// nested-class, array and optional-field carriers), a declared function
// parameter is the per-signature wrapper invoked through invoke, and the
// async exports return real backend async operations (completed futures)
// whose completions are the declared class/array values.

final class HostCarrierasync {
  // Transcript formatting belongs to the fixture, not the DEAL numeric kind.
  static String numericText(Object value) {
    if (value instanceof Number n) {
      return java.math.BigDecimal.valueOf(n.doubleValue()).stripTrailingZeros().toPlainString();
    }
    return String.valueOf(value);
  }

  static final String EP = "@$external/host.carrierasync/Endpoint";
  static final String SC = "@$external/host.carrierasync/ServerConfig";
  static final String FARM = "@$external/host.carrierasync/Farm";

  public static final Object Endpoint_defaults =
      java.util.Map.of("path", "/");

  public static final java.util.Map<String, Object> ServerConfig_defaults =
      java.util.Map.of("port", Double.valueOf(0),
          "endpoint", new Object(), "parts", new Object(),
          "tags", new Object(), "note", new Object());

  public static final java.util.Map<String, Object> Farm_defaults =
      java.util.Map.of("name", "",
          "servers", new Object(), "primary", new Object());

  static $DealRt.$Host$host$scarrierasync$Endpoint endpoint(String path) {
    return new $DealRt.$Host$host$scarrierasync$Endpoint(path);
  }

  static $DealRt.$Host$host$scarrierasync$ServerConfig config(double port,
      String path) {
    return new $DealRt.$Host$host$scarrierasync$ServerConfig(
        port, endpoint(path),
        new $DealRt.__StringArray(new String[] { "x", "y" }),
        null, false, null, true);
  }

  static String arrayText($DealRt.__StringArray value, boolean present) {
    if (!present) {
      return "absent";
    }
    if (value == null) {
      return "null";
    }
    return value.data.length + ":" + value.data[0];
  }

  static String textOr(String value, boolean present) {
    if (!present) {
      return "absent";
    }
    if (value == null) {
      return "null";
    }
    return value;
  }

  public static Object describeAsync(
      $DealRt.$Host$host$scarrierasync$ServerConfig s,
      $DealRt.Fn1_N_R_N g) {
    String gText = "none";
    if (g != null) {
      try {
        gText = "true:" + numericText(g.invoke(41));
      } catch (RuntimeException failure) {
        gText = "false:" + failure.getMessage();
      }
    }
    String text = "SC|" + numericText(s.port) + "|" + s.endpoint.path + "|"
        + arrayText(s.parts, true) + "|" + arrayText(s.tags, s.tags$present)
        + "|" + textOr(s.note, s.note$present) + "|" + gText;
    return CompletableFuture.completedFuture(text);
  }

  public static Object make() {
    return CompletableFuture.completedFuture(
        new $DealRt.$Host$host$scarrierasync$ServerConfig(
            4242, endpoint("/made"),
            new $DealRt.__StringArray(new String[] { "a", "b" }),
            null, false, null, true));
  }

  public static Object makeAll() {
    $DealRt.$Host$host$scarrierasync$ServerConfig shared = config(11, "/one");
    return new $DealRt.$HostArr$host$scarrierasync$ServerConfig(
        new Object[] { shared, shared });
  }

  public static Object makeFarm() {
    $DealRt.$Host$host$scarrierasync$ServerConfig shared = config(12, "/two");
    return new $DealRt.$Host$host$scarrierasync$Farm("farm",
        new $DealRt.$HostArr$host$scarrierasync$ServerConfig(
            new Object[] { shared }),
        endpoint("/p"));
  }
}
