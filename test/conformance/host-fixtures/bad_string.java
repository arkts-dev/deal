// Host-module fixture (host-module-abi D6): a host surface whose
// returned strings violate DEAL v1.2 string encoding rules. A JVM host
// is byte-oriented at a raw string boundary, so the malformed-encoding
// export returns the same raw UTF-8 bytes the Lua host returns
// ("a\x80b" — a stray continuation byte); the surrogate export returns
// the UTF-16 lone surrogate code unit (the JVM carrier's surrogate code
// point, the analog of the Lua host's CESU-style ED A0 80 encoding).
// The declared (string) boundary must reject both returns with E8010
// and select the pinned reason from the carrier's encoding class, never
// from its length.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostBad_string {
  public static Object badString() {
    return new byte[] { 0x61, (byte) 0x80, 0x62 };
  }

  public static Object surrogateString() {
    return "\uD800";
  }
}
