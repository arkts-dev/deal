// Host fixture implementation for the host-null-return-ok conformance
// test. Java null is the JVM null sentinel for a sync function declared
// ->null — the wrapper's sync-null check accepts it and rejects any
// non-sentinel value.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostNullreturn_ok {
  public static Object ping() {
    return null;
  }
}
