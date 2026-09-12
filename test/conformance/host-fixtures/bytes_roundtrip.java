public final class HostBytes_roundtrip {
  private static int calls;
  public static Object echoBytes($DealRt.Bytes b) { calls += 1; return b; }
  public static Object nullableBytes($DealRt.Bytes b) { calls += 1; return b; }
  public static Object makeBytes(int n) { calls += 1; return new $DealRt.Bytes(new byte[n]); }
  public static int readByte($DealRt.Bytes b) { calls += 1; return b.data[0] & 0xFF; }
  public static int callCount() { return calls; }
  public static Object badBytesReturn() { calls += 1; return "not-bytes"; }
}
