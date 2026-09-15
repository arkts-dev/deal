package deal.checker;

import java.util.Map;
import java.util.Set;
import deal.ast.TypeNode;
import deal.identity.CanonicalModuleIdentity;
import deal.types.Type;

/**
 * Interface for module resolution. The type checker delegates import
 * resolution to the module system through this interface.
 *
 * <p>For unit testing, a simple stub implementation can be provided.
 * The production implementation lives in the module system.</p>
 */
public interface ModuleResolver {

    /**
     * Resolve a module path and return its exported symbols.
     *
     * @param modulePath the import path (e.g. {@code "./lib"} or {@code "std/console"})
     * @param importingModule the module path of the file doing the import
     * @param modulesInProgress set of module paths currently being resolved
     *        (for circular import detection); the resolver should pass this
     *        set when creating nested NameResolver instances
     * @return a map from export name to resolved type
     * @throws ModuleNotFoundException if the module cannot be found
     * @throws CffiImportWithoutNativeLibraryException when the imported
     *         module is a C FFI declaration file that is not backed by
     *         an externals entry specifying {@code nativeLibrary} — the
     *         v1.2 C FFI manifest policy rejection (E2010 at the import)
     */
    Map<String, Type> resolveModule(String modulePath, String importingModule,
                                     Set<String> modulesInProgress)
        throws ModuleNotFoundException, CffiImportWithoutNativeLibraryException;

    /**
     * Resolve a class symbol from an imported module.
     *
     * <p>Used by the type checker and code generator to look up class field
     * information for imported classes that are not in the local scope.</p>
     *
     * @param className the name of the class (e.g. {@code "Result"})
     * @param modulePath the module path where the class is declared
     *                   (e.g. {@code "other.module"})
     * @param importingModule the module path of the file requesting the symbol
     * @return the ClassSymbol, or {@code null} if not found
     * @throws ModuleNotFoundException if the module cannot be found
     */
    Symbol.ClassSymbol resolveClassSymbol(String className, String modulePath,
                                           String importingModule)
        throws ModuleNotFoundException;

    /**
     * Resolves a class symbol of an imported module by the declaring
     * module's canonical module identity (the v1.2 identity-carriage
     * routing — descriptor-identity-propagation D1): imported classes
     * carry the declaring source's identity, and the resolver routes on
     * it.  The default returns {@code null} (unsupported); the
     * production {@code ModuleResolverImpl} and identity-aware harnesses
     * route by the module-identity classification.
     *
     * @param className the simple class name
     * @param declaringModule the canonical module identity of the
     *                        declaring module
     * @param importingModule the module path of the file requesting the
     *                        symbol
     * @return the ClassSymbol, or {@code null} when unsupported or not
     *         found
     * @throws ModuleNotFoundException if the module cannot be found
     */
    default Symbol.ClassSymbol resolveClassSymbol(String className,
            CanonicalModuleIdentity declaringModule, String importingModule)
            throws ModuleNotFoundException {
        return null; // unsupported by default
    }

    /**
     * Checks whether a function is exported from the module with the
     * given canonical module identity.  The default returns
     * {@code false} (unsupported); identity-aware resolvers route by
     * the module-identity classification.
     *
     * @param declaringModule the canonical module identity of the module
     *                        that should export the function
     * @param functionName the function name (e.g. "User$fromJson")
     * @param importingModule the module path of the file requesting the
     *                        export
     * @return true when exported, false when unsupported or absent
     * @throws ModuleNotFoundException if the module cannot be found
     */
    default boolean isFunctionExportedFromModule(
            CanonicalModuleIdentity declaringModule, String functionName,
            String importingModule) throws ModuleNotFoundException {
        return false; // unsupported by default
    }

    /**
     * Resolves a field {@link TypeNode} against the scope of the module
     * identified by {@code modulePath} (the owning module of a class
     * declaration).
     *
     * <p>Used by the type checker to resolve field type annotations of a
     * cross-module {@link Symbol.ClassSymbol} against the declaring
     * module's scope, so that bare class names inside the field type
     * (e.g. {@code Address[]}) resolve against the owning module's
     * declarations rather than the importing module's.</p>
     *
     * <p>The default implementation returns {@code null} (unsupported);
     * callers must fall back to their local resolution when {@code null}
     * is returned. Existing implementations stay source-compatible.</p>
     *
     * @param typeNode the field type annotation to resolve
     * @param modulePath the module path where the owning class is declared
     * @param importingModule the module path of the file requesting the type
     * @return the resolved type, or {@code null} when unsupported or not found
     * @throws ModuleNotFoundException if the module cannot be found
     */
    default Type resolveTypeNodeInModule(TypeNode typeNode, String modulePath,
                                         String importingModule)
            throws ModuleNotFoundException {
        return null; // unsupported by default
    }

    /** Exception thrown when a module cannot be found. */
    final class ModuleNotFoundException extends Exception {
        private static final long serialVersionUID = 1L;

        public ModuleNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when a module import violates the v1.2 C FFI
     * manifest policy: the imported module is a C FFI declaration file
     * (a {@code .d.deal} file carrying {@code // @extern-c}) that no
     * externals entry declares with {@code nativeLibrary}. The checker
     * maps this rejection to E2010 at the import span
     * (docs/spec-v1.2.md:1891 — "A C FFI entry must include
     * nativeLibrary").
     */
    final class CffiImportWithoutNativeLibraryException extends Exception {
        private static final long serialVersionUID = 1L;

        private final String modulePath;

        public CffiImportWithoutNativeLibraryException(String modulePath) {
            super("C FFI declaration file '" + modulePath
                + "' imported without an externals entry specifying "
                + "nativeLibrary");
            java.util.Objects.requireNonNull(modulePath,
                "modulePath must not be null");
            this.modulePath = modulePath;
        }

        /** The import path of the C FFI declaration file. */
        public String modulePath() {
            return modulePath;
        }
    }
}
