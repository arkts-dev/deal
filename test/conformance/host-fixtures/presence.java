// Host fixture implementation for the host-export-presence conformance
// test (host-module-abi D6). The JVM lane synthesizes the declared host
// class (Config) as a shared $DealRt record carrying the canonical
// externals identity (@$external/host.presence/Config); the
// Config_defaults map is the preserved defaults-map seam — it carries
// ONLY port (8080), exactly like the Lua fixture, so a provided
// fallback/peers name raises E8007 at construction
// (host-class-extra-field pins the rejection).
//
// ISSUE-0303 (jvm-v12-host-abi-completion D4): the harness references
// the synthesized record class, never a nested JDK mirror bound to the
// host implementation.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostPresence {
  public static final java.util.Map<String, Object> Config_defaults =
      java.util.Map.of("port", Integer.valueOf(8080));

  public static Object ping() {
    return "pong";
  }
}
