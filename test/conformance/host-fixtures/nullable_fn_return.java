import java.util.function.LongUnaryOperator;

// Host fixture implementation for the host-nullable-function-return
// conformance tests. The declared return descriptor is ?((x: int) =>
// int): Java null is the null result, and a raw JDK function value
// (here a lambda) fails the function-typed return check — function-
// typed return wrapping is excluded (assumption c), so the wrapper
// must raise E8010 for the "bad" path. The standalone JVM host uses
// the JDK functional interface as the (x: int) => int mapping — the
// shared-carrier lane maps the same position to the $DealRt
// function-value carrier.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostNullable_fn_return {
  public static Object getCallback(String mode) {
    if ("bad".equals(mode)) {
      LongUnaryOperator raw = (x) -> x;
      return raw;
    }
    return null;
  }
}
