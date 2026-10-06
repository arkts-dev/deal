

final class HostCfg {
  public static final java.util.Map<String, Object> Endpoint_defaults =
      java.util.Map.of("path", "/");

  public static final java.util.Map<String, Object> ServerConfig_defaults =
      java.util.Map.of("port", Integer.valueOf(8080),
          "endpoint", new Object(), "tags", new Object(),
          "note", new Object());

  public static Object describe($DealRt.$Host$host$scfg$ServerConfig s) {
    return s.endpoint.path + ":" + s.port;
  }
}
