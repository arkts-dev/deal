// Host fixture implementation for the host-export-presence conformance
// test (host-module-abi D6). The JVM lane synthesizes the declared host
// class (Config) and its construction defaults from the declaration;
// the nested type below is the standalone JDK mirror of the declared
// field shapes (port, fallback?, peers?), and the Config_defaults
// field mirrors the Lua loader's mandatory defaults table (port 8080).
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostPresence {
  public static final class Config {
    public long port;
    public Object fallback;   // Config | null (optional)
    public Object peers;      // Config[] (optional)
  }

  public static final java.util.Map<String, Object> Config_defaults =
      java.util.Map.of("port", Long.valueOf(8080L));

  public static Object ping() {
    return "pong";
  }
}
