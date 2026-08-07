package deal.checker;

import java.util.Map;
import java.util.Set;
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
     */
    Map<String, Type> resolveModule(String modulePath, String importingModule,
                                     Set<String> modulesInProgress)
        throws ModuleNotFoundException;

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

    /** Exception thrown when a module cannot be found. */
    final class ModuleNotFoundException extends Exception {
        private static final long serialVersionUID = 1L;

        public ModuleNotFoundException(String message) {
            super(message);
        }
    }
}
