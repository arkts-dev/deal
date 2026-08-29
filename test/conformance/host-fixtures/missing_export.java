// Host fixture implementation for the host-missing-export conformance
// test. The declared surface names "missing", which this implementation
// omits: the loader must raise E8011 at load time, before any exported
// function auto-invocation (R3).
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostMissing_export {
  public static Object ping() {
    return "pong";
  }
}
