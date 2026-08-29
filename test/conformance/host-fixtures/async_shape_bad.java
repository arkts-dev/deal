// Host fixture implementation for the host-async-shape-bad conformance
// test: the declared export is an async function, so the host must
// return a backend async operation; the plain number below must be
// rejected at the call site with E8010 (async-operation shape), before
// the await-site completion check ever runs.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostAsync_shape_bad {
  public static Object fetchValue() {
    return Long.valueOf(42L);
  }
}
