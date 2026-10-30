

final class HostPresence {
  public static final String Config = "@$external/host.presence/Config";
  public static final java.util.Map<String, Object> Config_defaults =
      java.util.Map.of("port", Double.valueOf(8080));

  public static Object ping() {
    return "pong";
  }
}
