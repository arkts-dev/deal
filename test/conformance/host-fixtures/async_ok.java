import java.util.concurrent.CompletableFuture;

// Host fixture implementation for the host-async-ok conformance test:
// a real backend async operation whose completion value satisfies the
// declared async return type string.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostAsync_ok {
  public static Object fetchValue() {
    return CompletableFuture.completedFuture("fetched");
  }
}
