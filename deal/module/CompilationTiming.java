package deal.module;

import java.util.function.BiConsumer;

/** Optional invocation-scoped monotonic phase receipts; never changes compilation. */
public final class CompilationTiming {
    private static final ThreadLocal<BiConsumer<String, Long>> OBSERVER = new ThreadLocal<>();
    private CompilationTiming() {}

    public static AutoCloseable observe(BiConsumer<String, Long> observer) {
        var previous = OBSERVER.get();
        OBSERVER.set(observer);
        return () -> { if (previous == null) OBSERVER.remove(); else OBSERVER.set(previous); };
    }

    public static void record(String phase, long startedNanos) {
        var observer = OBSERVER.get();
        if (observer != null) {
            try { observer.accept(phase, System.nanoTime() - startedNanos); }
            catch (RuntimeException ignored) { /* Observation cannot reject source or publication. */ }
        }
    }
}
