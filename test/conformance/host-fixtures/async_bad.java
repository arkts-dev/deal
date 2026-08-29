import java.util.concurrent.CompletableFuture;

// Host fixture implementation for the host-async-bad conformance test:
// the returned value is a valid backend async operation, but its
// completion value 42 violates the declared async return type string —
// the await-site completion check must raise E8001.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostAsync_bad {
  public static Object fetchValue() {
    return CompletableFuture.completedFuture(Long.valueOf(42L));
  }
}
