package deal.module;

import java.util.function.BiConsumer;

/** Optional current-thread phase durations; nested scopes restore the prior observer. */
public final class CompilationTiming {
    private static final ThreadLocal<BiConsumer<String, Long>> OBSERVER = new ThreadLocal<>();

    private CompilationTiming() {}

    /** Observe monotonic nanoseconds until close, on this thread in nesting order. */
    public static AutoCloseable observe(BiConsumer<String, Long> observer) {
        var previous = OBSERVER.get();
        OBSERVER.set(observer);
        return () -> {
            if (previous == null) OBSERVER.remove();
            else OBSERVER.set(previous);
        };
    }

    static void record(String phase, long startedNanos) {
        var observer = OBSERVER.get();
        if (observer != null) {
            try { observer.accept(phase, System.nanoTime() - startedNanos); }
            catch (RuntimeException ignored) { /* Diagnostics must not fail compilation. */ }
        }
    }
}
