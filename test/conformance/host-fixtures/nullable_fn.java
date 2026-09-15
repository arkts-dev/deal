// Host fixture implementation for the host-nullable-function-param and
// host-nullable-function-param-bad conformance tests. The declared
// parameter is ?((x: int) => int): the wrapper accepts the DEAL null
// (Java null here) passed through unadapted and matching-sig DEAL
// functions, and raises E8010 for anything else.
//
// ISSUE-0303 (jvm-v12-host-abi-completion D1/D2/D3): the parameter
// arrives as the typed $DealRt.Fn1_I_R_I wrapper (Java null on the
// nullable form); the wrapper checked the carried descriptor
// byte-for-byte at the call (E8010 'parameter 1 type mismatch' on any
// delta — the bad fixture's (string)=>int value) and a null on a
// non-nullable function parameter raises E8010 the same way. The host
// invokes the wrapper's typed invoke.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostNullable_fn {
  public static Object register($DealRt.Fn1_I_R_I cb) {
    if (cb == null) {
      return Integer.valueOf(0);
    }
    return Integer.valueOf(cb.invoke(41));
  }
}
