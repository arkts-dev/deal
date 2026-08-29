// Host fixture implementation for the host-bad-return conformance test:
// a junk return value for the declared int return type — the wrapper
// must raise E8010 at the call site.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostBad_return {
  public static Object getNumber() {
    return "not a number";
  }
}
