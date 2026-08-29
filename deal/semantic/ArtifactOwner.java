package deal.semantic;

/**
 * The closed artifact-owner axis of a {@link TargetModuleAbi} record
 * (foundation F5): the artifact the ABI record describes.
 *
 * <p>Closed set — exactly the three values below; no open or unknown
 * fallback member and no external extension point exist. Plan-time
 * records carry {@link #RETAINED_LUAJIT} or {@link #RETAINED_JVM} — one
 * per legacy dependency of a shared module, derived from the index facts
 * at plan time; a shared module's emitter emits its own ABI manifest
 * against the same record type with {@link #SHARED} (ISSUE-0239).</p>
 */
public enum ArtifactOwner {

    /** The shared common-lowering artifact (emission-owned records). */
    SHARED,

    /** The retained LuaJIT artifact (plan-time records for a LUAJIT plan). */
    RETAINED_LUAJIT,

    /** The retained JVM artifact (plan-time records for a JVM plan). */
    RETAINED_JVM
}
