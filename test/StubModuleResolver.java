package deal.test;

import deal.checker.ModuleResolver;
import deal.types.Type;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Stub module resolver for unit-testing the checker without a real module system.
 */
final class StubModuleResolver implements ModuleResolver {

    private final Map<String, Map<String, Type>> modules = new HashMap<>();

    /**
     * Registers a mock module with its exports.
     */
    public void register(String path, Map<String, Type> exports) {
        modules.put(path, exports);
    }

    @Override
    public Map<String, Type> resolveModule(String modulePath, String importingModule,
                                            Set<String> modulesInProgress)
            throws ModuleNotFoundException {
        Map<String, Type> exports = modules.get(modulePath);
        if (exports == null) {
            throw new ModuleNotFoundException("Module not found: " + modulePath);
        }
        return exports;
    }
}
