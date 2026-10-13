// Host fixture implementation for the host-empty-return-bad conformance
// test: the declared void method's reflective invocation produces no
// result (the JVM host surface's absent marker) for the declared int
// return — the wrapper's presence rule must raise E8010 on every call
// path, including the discard path. The no-result form is the declared
// void method, distinct from a present Java null (which a mandatory
// declared return rejects through the HOST_SYNC_RETURN_CELL composite).
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostEmpty_return {
  public static void ping() {
  }
}
