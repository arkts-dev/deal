// Host fixture implementation for the host-null-return-bad conformance
// test: a junk return for the sync ->null declared return — the
// wrapper's sync-null check must raise E8010, including on the discard
// call path.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostNullreturn_bad {
  public static Object ping() {
    return "junk";
  }
}
