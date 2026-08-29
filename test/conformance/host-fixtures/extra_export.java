// Host fixture implementation for the host-extra-export-ignored
// conformance test (host-module-abi D6). The implementation supplies
// more exports than the declared surface (ping): extra and helper are
// dropped structurally by the loader and the load must succeed (R4).
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostExtra_export {
  public static Object ping() {
    return "pong";
  }

  public static Object extra() {
    return Long.valueOf(42L);
  }

  public static Object helper() {
    return "unreachable";
  }
}
