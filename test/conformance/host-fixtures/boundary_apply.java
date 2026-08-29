import java.util.function.LongUnaryOperator;

// Host fixture implementation for the host-boundary-apply-function
// conformance test. The declared parameter is a raw function type; the
// boundary wrapper validates the carried signature before the raw call
// and adapts the DEAL function value to the host form (host-module-abi
// D3 case 4). The standalone JVM host uses the JDK functional interface
// as the (x: int) => int mapping — the shared-carrier lane maps the
// same position to the $DealRt function-value carrier.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostBoundary_apply {
  public static Object apply(LongUnaryOperator f, long v) {
    return Long.valueOf(f.applyAsLong(v) + 100L);
  }
}
