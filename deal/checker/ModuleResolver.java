package deal.checker;

import java.util.Map;
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
     * @return a map from export name to resolved type
     * @throws ModuleNotFoundException if the module cannot be found
     */
    Map<String, Type> resolveModule(String modulePath, String importingModule)
        throws ModuleNotFoundException;

    /** Exception thrown when a module cannot be found. */
    final class ModuleNotFoundException extends Exception {
        private static final long serialVersionUID = 1L;

        public ModuleNotFoundException(String message) {
            super(message);
        }
    }
}
