package deal.test;

import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.types.Type;
import deal.types.Types;

import java.util.List;

public final class IdentityTestFixtures {

    private IdentityTestFixtures() { /* static fixtures only */ }

    /**
     * The canonical module identity for a single-module harness path:
     * {@code ""} &rarr; {@code BuiltinModule}; anything else &rarr; a
     * project module whose configured root text is the path itself with
     * no relative components.
     */
    public static CanonicalModuleIdentity moduleIdentityOf(String modulePath) {
        if (modulePath == null || modulePath.isEmpty()) {
            return CanonicalModuleIdentity.BuiltinModule.INSTANCE;
        }
        return new CanonicalModuleIdentity.ProjectModule(
            new ProjectModuleIdentity(modulePath, modulePath, List.of()));
    }

    /** The canonical class identity for a class declared in the module
     * path (the standalone convention above). */
    public static CanonicalClassIdentity identityOf(String modulePath,
                                                   String className) {
        return new CanonicalClassIdentity(moduleIdentityOf(modulePath),
            className);
    }

    /**
     * A Class type carrying the standalone identity and the stub module's
     * own wiring path as its defining provider (ISSUE-0809): a stub
     * harness that registers an imported declaration binds the provider,
     * so a same-named local declaration of another provider stays a
     * distinct declaration at the assignment boundaries.
     */
    public static Type.Class classType(String name, String modulePath) {
        return Types.classType(name, identityOf(modulePath, name), modulePath);
    }

    /** The intrinsic builtin {@code Error} class type (E2's synthesis). */
    public static Type.Class errorClassType() {
        return Types.classType("Error", new CanonicalClassIdentity(
            CanonicalModuleIdentity.BuiltinModule.INSTANCE, "Error"));
    }

    /**
     * The canonical class identity of the intrinsic builtin
     * {@code Error} class.
     */
    public static CanonicalClassIdentity errorIdentity() {
        return new CanonicalClassIdentity(
            CanonicalModuleIdentity.BuiltinModule.INSTANCE, "Error");
    }
}
