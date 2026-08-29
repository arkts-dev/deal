// Host fixture implementation for the host-class-export conformance
// test (host-module-abi D6 + runtime-class-identity D1-D2). The JVM
// lane synthesizes the declared host classes (Endpoint, ServerConfig)
// and their construction defaults from the declaration; the nested
// types below are the standalone JDK mirrors of the declared field
// shapes, and the <C>_defaults fields mirror the Lua loader's
// mandatory defaults tables (port 8080; path "/"). describe reads the
// boundary-crossed instance host-side (endpoint.path and port).
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostCfg {
  public static final class Endpoint {
    public String path;
  }

  public static final class ServerConfig {
    public long port;
    public Endpoint endpoint;
    public Object tags;   // string[] (optional)
    public Object note;   // string | null (optional)
  }

  public static final java.util.Map<String, Object> Endpoint_defaults =
      java.util.Map.of("path", "/");

  public static final java.util.Map<String, Object> ServerConfig_defaults =
      java.util.Map.of("port", Long.valueOf(8080L));

  public static Object describe(ServerConfig s) {
    return s.endpoint.path + ":" + s.port;
  }
}
