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
// ordinary DEAL reads observe them), a nullable nested-array element
// that must stay the language null, and one inner array returned at both
// positions of a declared string[][] (the same production array, so DEAL
// identity holds and a mutation through either alias is observed through
// the other). The nested-tail export returns a
// declared string[][] whose later element is not an array: the return is
// rejected, and the retained earlier slots must stay untouched (the
// name-shifted accessor below is the probe's observation of the retained
// raw carriers and the array alias).
//
// The native-* exports return the declared shapes through this host's own
// `$DealRt` array carriers (the classes the deployed host compiles
// against): nativeBadStringArray's second element is the unpaired U+D800
// surrogate, nativeBadNestedTail's later element is not an array while its
// earlier nested carrier is valid (the retained accessor observes both
// carriers after the rejected return), and the valid controls must land as
// usable DEAL arrays at every nesting level (nativeSharedNestedStringArray
// returns one inner carrier at both positions, so the projection must keep
// one DEAL array). Java String[] storage never proves Unicode validity, so
// the declared-array check must walk these carrier elements recursively.
//
// The top-level class is deliberately package-private: the corpus file
// keeps its <name>.java stem while the JVM lane deploys the source
// beside the default-package artifacts under the classNameFor name
// Host<Name>.java (test/JvmConformanceTest.java HOST_JAVA pattern).
final class HostBad_string {
  private static deal.codegen.jvm.JvmRuntime.Array retainedNested;
  private static deal.codegen.jvm.JvmRuntime.Array retainedNestedInner;
  private static $DealRt.__RefArray retainedNative;
  private static $DealRt.__StringArray retainedNativeInner;

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

  public static Object sharedNestedStringArray() {
    deal.codegen.jvm.JvmRuntime.Array inner =
        new deal.codegen.jvm.JvmRuntime.Array(1);
    inner.elements.add(new byte[] { 0x6F, 0x6B });
    deal.codegen.jvm.JvmRuntime.Array outer =
        new deal.codegen.jvm.JvmRuntime.Array(2);
    outer.elements.add(inner);
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

  public static Object nativeBadStringArray() {
    return new $DealRt.__StringArray(new String[] { "ok", "\uD800" });
  }

  public static Object nativeBadNestedTail() {
    $DealRt.__StringArray inner =
        new $DealRt.__StringArray(new String[] { "ok" });
    $DealRt.__RefArray outer =
        new $DealRt.__RefArray(new Object[] { inner, "not an array" });
    retainedNative = outer;
    retainedNativeInner = inner;
    return outer;
  }

  public static Object nativeStringArray() {
    return new $DealRt.__StringArray(new String[] { "ok" });
  }

  public static Object nativeNestedStringArray() {
    return new $DealRt.__RefArray(new Object[] {
      new $DealRt.__StringArray(new String[] { "ok" }),
    });
  }

  public static Object nativeSharedNestedStringArray() {
    $DealRt.__StringArray inner =
        new $DealRt.__StringArray(new String[] { "ok" });
    return new $DealRt.__RefArray(new Object[] { inner, inner });
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

  /**
   * The retained observation of the rejected {@link #nativeBadNestedTail}
   * return: the outer declared carrier's element classes, the nested
   * carrier's element class, the rejected tail element's class, and the
   * alias relation between the retained outer slot and the allocated
   * nested carrier. A failed return must leave the whole native carrier
   * untouched, so this stays
   * {@code $DealRt$__StringArray|java.lang.String|java.lang.String|alias};
   * an in-place element projection would replace the nested carrier's raw
   * storage before the later sibling element rejected the return.
   */
  static java.lang.String retainedNativeSlots() {
    if (retainedNative == null) {
      return "no-call";
    }
    java.lang.Object first = retainedNative.data[0];
    java.lang.Object inner = first instanceof $DealRt.__StringArray array
        && array.data.length > 0 ? array.data[0] : null;
    java.lang.Object tail = retainedNative.data[1];
    return first.getClass().getName()
        + "|" + (inner == null ? "null" : inner.getClass().getName())
        + "|" + (tail == null ? "null" : tail.getClass().getName())
        + "|" + (first == retainedNativeInner ? "alias" : "rebound");
  }
}
