package deal.semantic;

/**
 * The closed module route of a route-plan entry (foundation F4; parent
 * canonical surfaces): each implementation module is wholly
 * {@link #LEGACY} or {@link #SHARED} — there is no per-node fallback and
 * no third route.
 *
 * <p>Closed set — exactly the two values below; no open or unknown
 * fallback member and no external extension point exist. A
 * {@code LEGACY} module executes through its retained target artifact
 * (represented to shared dependants by {@code ExternalModuleInterface}
 * plus {@link TargetModuleAbi}); a {@code SHARED} module lowers to one
 * common {@code LoweredModuleUnit}. Shadow {@code SHARED} entries of a
 * {@code COMMON_SHADOW} plan are recorded in the plan's
 * {@code shadowModules} and never drive production publication
 * (foundation F4 rule 5).</p>
 */
public enum ModuleRoute {

    /** The retained legacy emitter route. */
    LEGACY,

    /** The common-lowering shared route. */
    SHARED
}
