// Host fixture implementation for the ISSUE-0340 default-plan fixtures
// (JVM lane). nextValue() increments a static counter and returns the
// count * 10; valueCount() reads it — the JVM host-slice mirror of the
// Lua host fixture's mutable state. The top-level class is deliberately
// package-private: the corpus file keeps its <name>.java stem while the
// JVM lane deploys the source beside the default-package artifacts
// under the classNameFor name Host<Name>.java
// (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostPlanprobe {
  private static long n = 0L;

  public static Object nextValue() {
    n = n + 1;
    return Long.valueOf(n * 10L);
  }

  public static Object valueCount() {
    return Long.valueOf(n);
  }
}
