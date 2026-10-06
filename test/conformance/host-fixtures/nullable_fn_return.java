

final class HostNullable_fn_return {
  public static Object getCallback(String mode) {
    if ("bad".equals(mode)) {
      java.util.function.IntUnaryOperator raw = (x) -> x;
      return raw;
    }
    return null;
  }
}
