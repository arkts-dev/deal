// Host-module fixture (host-module-abi D6): a host surface whose
// returned strings violate DEAL v1.2 string encoding rules. Java
// strings are UTF-16, so unpaired surrogate code units are the JVM
// analog of the Lua host's malformed UTF-8 bytes — the declared
// (string) boundary must reject both returns with E8010.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostBad_string {
  public static Object badString() {
    return "a\uD800b";
  }

  public static Object surrogateString() {
    return "\uD800";
  }
}
