

final class HostCfg {
  public static final String Endpoint = "@$external/host.cfg/Endpoint";
  public static final String ServerConfig = "@$external/host.cfg/ServerConfig";
  // Transcript formatting belongs to the fixture, not the DEAL numeric kind.
  static String numericText(Object value) {
    if (value instanceof Number n) {
      return java.math.BigDecimal.valueOf(n.doubleValue()).stripTrailingZeros().toPlainString();
    }
    return String.valueOf(value);
  }

  public static final java.util.Map<String, Object> Endpoint_defaults =
      java.util.Map.of("path", "/");

  public static final java.util.Map<String, Object> ServerConfig_defaults =
      java.util.Map.of("port", Double.valueOf(8080),
          "endpoint", new Object(), "tags", new Object(),
          "note", new Object());

  public static Object describe($DealRt.$Host$host$scfg$ServerConfig s) {
    return s.endpoint.path + ":" + numericText(s.port);
  }
}
