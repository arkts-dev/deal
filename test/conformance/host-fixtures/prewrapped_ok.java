// Host fixture implementation for the host-prewrapped-ok conformance
// test. The pre-wrapped sig-table form is a LuaJIT host-loader
// mechanism; the JVM host realizes the declared-shape contract (the
// declared descriptor is the only trusted metadata): greet returns the
// declared string and ping's sync ->null return is Java null, both
// enforced through the declared-descriptor wrapper.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostPrewrapped_ok {
  public static Object greet(String name) {
    return "hello " + name;
  }

  public static Object ping() {
    return null;
  }
}
