final class HostNever_completion {
  public static Object bad() { return 42.0; }
  public static void nothing() {}
  public static Object badAsync() { return java.util.concurrent.CompletableFuture.completedFuture(42.0); }
}
