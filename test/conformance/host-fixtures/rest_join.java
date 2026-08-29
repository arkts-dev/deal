// Host fixture implementation for the host-rest-ok/host-rest-bad
// conformance tests: the v1.2 fixed-array parameter form (DEAL v1.2
// removed rest parameters — the join export takes a fixed string[]
// parameter, never a Java varargs). The boundary wrapper checks the
// array against the declared string[] element type.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostRest_join {
  public static Object join(String sep, String[] parts) {
    return String.join(sep, parts);
  }
}
