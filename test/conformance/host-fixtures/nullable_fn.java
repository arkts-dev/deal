

final class HostNullable_fn {
  public static Object register($DealRt.Fn1_N_R_N cb) {
    if (cb == null) {
      return Double.valueOf(0);
    }
    return Double.valueOf(cb.invoke(41));
  }
}
