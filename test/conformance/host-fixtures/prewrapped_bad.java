// Host fixture implementation for the host-prewrapped-bad conformance
// test. The pre-wrapped sig-table form is a LuaJIT host-loader
// mechanism; the JVM host realizes the declared-shape contract (the
// declared descriptor is the only trusted metadata): ping returns junk
// for the declared ->null return, so the wrapper's sync-null check must
// raise E8010, including the discard call path.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostPrewrapped_bad {
  public static Object ping() {
    return "junk";
  }
}
