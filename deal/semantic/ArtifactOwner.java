package deal.semantic;

public enum ArtifactOwner {

    /** The shared common-lowering artifact (emission-owned records). */
    SHARED,

    /** The retained LuaJIT artifact (plan-time records for a LUAJIT plan). */
    RETAINED_LUAJIT,

    /** The retained JVM artifact (plan-time records for a JVM plan). */
    RETAINED_JVM
}
