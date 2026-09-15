// Host fixture implementation for the host-array-return-ok conformance
// test (host-module-abi D6): a fixed Java string array satisfies the
// declared string[] return — the boundary wrapper checks the carrier
// and its elements before the caller reads them back in DEAL.
//
// ISSUE-0303 (jvm-v12-host-abi-completion D1): the declared string[]
// return crosses the boundary as the SHARED $DealRt.__StringArray
// wrapper — the host returns the shared carrier, never a raw Java
// array, and the wrapper validates the carrier and elements.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostArray_return {
  public static Object split(String s) {
    return new $DealRt.__StringArray(new String[] {"a", "b", "c"});
  }
}
