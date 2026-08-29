// Host fixture implementation for the host-empty-return-bad conformance
// test: Java null for the declared int return (the JVM zero-result
// form) — the wrapper's presence rule must raise E8010 on every call
// path, including the discard path.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostEmpty_return {
  public static Object ping() {
    return null;
  }
}
