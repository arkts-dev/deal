// Host fixture implementation for the host-nullable-return-ok/bad
// conformance tests. Java null is the null result; an int return
// exercises the wrong-representation E8010 path.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostNullable_return {
  public static Object find(String s) {
    if ("__BAD__".equals(s)) return Long.valueOf(42L);
    if ("__NULL__".equals(s)) return null;
    return s;
  }
}
