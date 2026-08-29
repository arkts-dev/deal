import java.util.function.LongUnaryOperator;

// Host fixture implementation for the host-nullable-function-param and
// host-nullable-function-param-bad conformance tests. The declared
// parameter is ?((x: int) => int): the wrapper accepts the DEAL null
// (Java null here) passed through unadapted and matching-sig DEAL
// functions (adapted to the host form), and raises E8010 for anything
// else. The standalone JVM host uses the JDK functional interface as
// the (x: int) => int mapping — the shared-carrier lane maps the same
// position to the $DealRt function-value carrier.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostNullable_fn {
  public static Object register(LongUnaryOperator cb) {
    if (cb == null) {
      return Long.valueOf(0L);
    }
    return Long.valueOf(cb.applyAsLong(41L));
  }
}
