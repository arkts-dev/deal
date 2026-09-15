// Host fixture implementation for the host-class-export conformance
// test (host-module-abi D6 + runtime-class-identity D1-D2). Each
// declared class export's <C>_defaults map is the load-time-validated
// mandatory defaults table: ServerConfig_defaults carries every
// declared field name (port defaulted to 8080; endpoint/tags/note as
// MISSING sentinel entries, exactly like the Lua fixture's __MISSING
// defaults), so the preserved defaults-map construction seam accepts
// the provided overlays and rejects extra names with E8007.
//
// ISSUE-0303 (jvm-v12-host-abi-completion D4): the JVM lane synthesizes
// the declared host classes (Endpoint, ServerConfig) as shared
// $DealRt records carrying the canonical externals identity
// (@$external/host/cfg/...); describe reads the boundary-crossed
// record host-side (endpoint.path and port) — the harness references
// the synthesized record class, never a nested JDK mirror bound to the
// host implementation.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
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
