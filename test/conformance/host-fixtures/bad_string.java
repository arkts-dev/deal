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
// position, the decoded element results inside the production array
// carriers (the element projection must land them back in the array so
// ordinary DEAL reads observe them), and a nullable nested-array element
// that must stay the language null. The nested-tail export returns a
// declared string[][] whose later element is not an array: the return is
// rejected, and the retained earlier slots must stay untouched (the
// name-shifted accessor below is the probe's observation of the retained
// raw carriers and the array alias).
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostBad_string {
  private static deal.codegen.jvm.JvmRuntime.Array retainedNested;
  private static deal.codegen.jvm.JvmRuntime.Array retainedNestedInner;

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

  public static Object rawNullableArrayArray() {
    deal.codegen.jvm.JvmRuntime.Array inner =
        new deal.codegen.jvm.JvmRuntime.Array(1);
    inner.elements.add(new byte[] { 0x6F, 0x6B });
    deal.codegen.jvm.JvmRuntime.Array outer =
        new deal.codegen.jvm.JvmRuntime.Array(2);
    outer.elements.add(null);
    outer.elements.add(inner);
    return outer;
  }

  public static Object badNestedTail() {
    deal.codegen.jvm.JvmRuntime.Array inner =
        new deal.codegen.jvm.JvmRuntime.Array(1);
    inner.elements.add(new byte[] { 0x6F, 0x6B });
    deal.codegen.jvm.JvmRuntime.Array outer =
        new deal.codegen.jvm.JvmRuntime.Array(2);
    outer.elements.add(inner);
    outer.elements.add("not an array");
    retainedNested = outer;
    retainedNestedInner = inner;
    return outer;
  }

  /**
   * The retained observation of the rejected {@link #badNestedTail}
   * return: the outer array's element class, the nested array's element
   * class, the rejected tail element's class, and the alias relation
   * between the retained outer slot and the allocated nested array. A
   * failed return must mutate nothing, so this stays
   * {@code JvmRuntime$Array|[B|java.lang.String|alias}; a partial
   * (nested) projection would report the decoded {@code java.lang.String}
   * in place of {@code [B}.
   */
  static java.lang.String retainedNestedSlots() {
    if (retainedNested == null) {
      return "no-call";
    }
    java.lang.Object first = retainedNested.elements.get(0);
    java.lang.Object inner = first instanceof deal.codegen.jvm.JvmRuntime.Array array
        && !array.elements.isEmpty() ? array.elements.get(0) : null;
    java.lang.Object tail = retainedNested.elements.get(1);
    return first.getClass().getName()
        + "|" + (inner == null ? "null" : inner.getClass().getName())
        + "|" + (tail == null ? "null" : tail.getClass().getName())
        + "|" + (first == retainedNestedInner ? "alias" : "rebound");
  }
}
