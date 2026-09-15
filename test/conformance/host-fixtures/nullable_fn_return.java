// Host fixture implementation for the host-nullable-function-return
// conformance tests. The declared return descriptor is ?((x: int) =>
// int): Java null is the null result, and a raw JDK function value
// (here a lambda) fails the function-typed return check — function-
// typed return wrapping is excluded (assumption c), so the wrapper
// must raise E8010 for the "bad" path.
//
// ISSUE-0303 (jvm-v12-host-abi-completion D2): function-typed returns
// are never wrapped — only a properly wrapped $DealRt.FnValue with a
// byte-equal descriptor passes, and this raw IntUnaryOperator lambda
// fails E8010 at the boundary exactly like the re-wrapped Lua form.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostNullable_fn_return {
  public static Object getCallback(String mode) {
    if ("bad".equals(mode)) {
      java.util.function.IntUnaryOperator raw = (x) -> x;
      return raw;
    }
    return null;
  }
}
