// Host fixture implementation for the host-boundary-apply-function
// conformance test. The declared parameter is a raw function type; the
// boundary wrapper validates the carried signature before the raw call
// (host-module-abi D3 case 4).
//
// ISSUE-0303 (jvm-v12-host-abi-completion D3): the DEAL function value
// arrives as its typed $DealRt wrapper — the harness host knows the
// declared signature ((x: int) => int → $DealRt.Fn1_I_R_I) and invokes
// it through the wrapper's typed invoke. No lambda conversion exists
// anywhere in the boundary.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostBoundary_apply {
  public static Object apply($DealRt.Fn1_I_R_I f, int v) {
    return Integer.valueOf(f.invoke(v) + 100);
  }
}
