package deal.test;

import deal.checker.ModuleResolver;
import deal.checker.Symbol;
import deal.identity.CanonicalModuleIdentity;
import deal.types.Type;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Stub module resolver for unit-testing the checker without a real module system.
 */
final class StubModuleResolver implements ModuleResolver {

    private final Map<String, Map<String, Type>> modules = new HashMap<>();
    private final Map<String, Symbol.ClassSymbol> classSymbols = new HashMap<>();

    /**
     * Registers a mock module with its exports.
     */
    public void register(String path, Map<String, Type> exports) {
        modules.put(path, exports);
    }

    /**
     * Registers a class symbol for cross-module class resolution.
     * The key is "modulePath:className" (e.g. "other.module:Result").
     */
    public void registerClassSymbol(String modulePath, Symbol.ClassSymbol classSymbol) {
        classSymbols.put(modulePath + ":" + classSymbol.name(), classSymbol);
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

    @Override
    public Symbol.ClassSymbol resolveClassSymbol(String className, String modulePath,
                                                  String importingModule)
            throws ModuleNotFoundException {
        String key = modulePath + ":" + className;
        Symbol.ClassSymbol cs = classSymbols.get(key);
        if (cs == null) {
            // Also try without module path (for local classes)
            cs = classSymbols.get(":" + className);
        }
        return cs;
    }

    /**
     * The identity-keyed routing (v1.2 identity carriage): the stub's
     * registered module paths use the standalone identity convention
     * ({@link IdentityTestFixtures#moduleIdentityOf(String)}), so the
     * carried module identity maps back to the registered path.
     */
    @Override
    public Symbol.ClassSymbol resolveClassSymbol(String className,
            CanonicalModuleIdentity declaringModule, String importingModule)
            throws ModuleNotFoundException {
        for (String path : modules.keySet()) {
            if (IdentityTestFixtures.moduleIdentityOf(path)
                    .equals(declaringModule)) {
                Symbol.ClassSymbol cs = classSymbols.get(path + ":"
                    + className);
                if (cs != null) {
                    return cs;
                }
            }
        }
        return null;
    }

    /**
     * The identity-keyed export check: route the carried module identity
     * back to the registered path and consult its export map.
     */
    @Override
    public boolean isFunctionExportedFromModule(
            CanonicalModuleIdentity declaringModule, String functionName,
            String importingModule) throws ModuleNotFoundException {
        for (Map.Entry<String, Map<String, Type>> entry
                : modules.entrySet()) {
            if (IdentityTestFixtures.moduleIdentityOf(entry.getKey())
                    .equals(declaringModule)) {
                return entry.getValue().containsKey(functionName);
            }
        }
        return false;
    }
}
