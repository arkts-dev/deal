package deal.semantic;

/**
 * The closed lowering/route target axis of the common lowering layer
 * (foundation F4/F5/F7): the two code-generation targets that route
 * plans, {@code TargetModuleAbi} records, and the release capability
 * registry span.
 *
 * <p>Closed set — exactly {@link #LUAJIT} and {@link #JVM}; no open or
 * unknown fallback member and no external extension point exist. The
 * declaration order is normative: the release capability registry orders
 * each capability's entries {@code LUAJIT} before {@code JVM} (foundation
 * F7), so the canonical JSON digest of the registry is byte-identical
 * across builds.</p>
 */
public enum Target {

    /** The LuaJIT code-generation target. */
    LUAJIT,

    /** The JVM code-generation target. */
    JVM
}
