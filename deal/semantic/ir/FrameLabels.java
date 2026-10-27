package deal.semantic.ir;

/**
 * The canonical stack-frame label of one lowered function (the
 * {@code DEALRuntimeError} "stack frames" surface,
 * docs/spec-v1.5.md §Host error and debugging and §Source maps and debugging):
 * every active DEAL call is named by both its original source identity
 * and its generated function name, so a
 * host-visible error frame is never a bare numeric id.
 *
 * <p>One label is
 * {@code <modulePath>#<sourceName>@F<functionId>}:
 * <ul>
 *   <li>{@code modulePath} is the owning module's original source identity
 *       (the module path the artifact's own function-to-module resolution
 *       publishes);</li>
 *   <li>{@code sourceName} is the lowered function's original DEAL
 *       declaration name, or {@code <anonymous>} for a name-less function
 *       expression;</li>
 *   <li>{@code F<functionId>} is the generated function name — the target
 *       factory the shared emitter emits for the function id
 *       ({@code F<id>} on both LuaJIT and JVM), never an invented
 *       spelling.</li>
 * </ul>
 *
 * <p>The label is derived identically by the oracle and by both shared
 * emitters from the same {@link LoweredFunction} facts, so the error
 * frames their traces compare are byte-equal. An id outside any known
 * unit (never produced by a real project) falls back to the generated
 * name alone — the label never guesses a module or a source name.</p>
 *
 */
public final class FrameLabels {

    private FrameLabels() {
        // Static derivation only; no instances.
    }

    /** The anonymous source name of a name-less function expression. */
    public static final String ANONYMOUS = "<anonymous>";

    /**
     * The frame label of one lowered function: the owning module path,
     * the original source name (or the anonymous form), and the generated
     * function name.
     */
    public static String of(String modulePath, FunctionId functionId, String sourceName) {
        return modulePath + "#" + (sourceName == null ? ANONYMOUS : sourceName)
            + "@F" + functionId.id();
    }

    /** The frame label of a function whose owning module is unknown. */
    public static String of(FunctionId functionId) {
        return "F" + functionId.id();
    }
}
