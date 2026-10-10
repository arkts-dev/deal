// Host-module fixture (host-module-abi D6): a host surface whose
// returned strings exercise the DEAL v1.2 string carrier classes. A JVM
// host is byte-oriented at a raw string boundary, so the malformed-encoding
// export returns the same raw UTF-8 bytes the Lua host returns ("a\x80b" —
// a stray continuation byte); the surrogate exports return the UTF-16
// surrogate code unit (the JVM carrier's surrogate code point, the analog
// of the Lua host's CESU-style ED A0 80 encoding, embedded mid-string in
// the embedded case). The declared (string) boundary must reject both
// classes with E8010 and select the pinned reason from the carrier's
// encoding class, never from its length. The valid exports exercise the
// admitted carriers: the decoded raw UTF-8 byte carrier at a scalar
// position and the decoded element results inside the production array
// carriers (the element projection must land them back in the array so
// ordinary DEAL reads observe them).
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

  public static Object embeddedSurrogateString() {
    return "a\uD800b";
  }

  public static Object validString() {
    return "ok";
  }

  public static Object rawStringArray() {
    deal.codegen.jvm.JvmRuntime.Array array =
        new deal.codegen.jvm.JvmRuntime.Array(1);
    array.elements.add(new byte[] { 0x6F, 0x6B });
    return array;
  }

  public static Object rawNestedStringArray() {
    deal.codegen.jvm.JvmRuntime.Array inner =
        new deal.codegen.jvm.JvmRuntime.Array(1);
    inner.elements.add(new byte[] { 0x6F, 0x6B });
    deal.codegen.jvm.JvmRuntime.Array outer =
        new deal.codegen.jvm.JvmRuntime.Array(1);
    outer.elements.add(inner);
    return outer;
  }

  public static Object rawNullableStringArray() {
    deal.codegen.jvm.JvmRuntime.Array array =
        new deal.codegen.jvm.JvmRuntime.Array(1);
    array.elements.add(new byte[] { 0x6F, 0x6B });
    return array;
  }
}
