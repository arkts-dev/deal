

final class HostNullable_fn {
  public static Object register($DealRt.Fn1_I_R_I cb) {
    if (cb == null) {
      return Integer.valueOf(0);
    }
    return Integer.valueOf(cb.invoke(41));
  }
}
