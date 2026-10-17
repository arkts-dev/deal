package deal.test;

import deal.ast.ExportDeclaration;
import deal.ast.FunctionDeclaration;
import deal.ast.ProgramNode;
import deal.ast.StatementNode;
import deal.checker.ModuleResolver;
import deal.checker.Symbol;
import deal.identity.CanonicalModuleIdentity;
import deal.module.StdlibModuleResolver;
import deal.types.Type;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stub module resolver for unit-testing the checker without a real module system.
 */
final class StubModuleResolver implements ModuleResolver {

    private final Map<String, Map<String, Type>> modules = new HashMap<>();
    private final Map<String, Symbol.ClassSymbol> classSymbols = new HashMap<>();

    StubModuleResolver() {
        modules.putAll(StdlibModuleResolver.stdlibExports(
            Path.of("std").toAbsolutePath().normalize().toString()));
    }

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
        if (exports != null) {
            return exports;
        }
        throw new ModuleNotFoundException("Module not found: " + modulePath);
    }

    /**
     * The imported module's own declared class symbols by declared name
     * (the exact declaring occurrence route): the stub serves the class
     * symbols registered for that module path, so a harness can model
     * two same-named declarations of one canonical identity (an imported
     * one and the local one) without a textual or identity-first match.
     */
    @Override
    public Map<String, Symbol.ClassSymbol> resolveModuleClassSymbols(
            String modulePath, String importingModule,
            Set<String> modulesInProgress) {
        Map<String, Symbol.ClassSymbol> declared = new HashMap<>();
        for (Map.Entry<String, Symbol.ClassSymbol> entry : classSymbols.entrySet()) {
            if (entry.getKey().startsWith(modulePath + ":")) {
                declared.put(entry.getValue().name(), entry.getValue());
            }
        }
        return declared;
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

    static void deployJsSupport(Path dir) throws IOException {
        Path runtimeDir = dir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.js"), runtimeDir.resolve("runtime.js"));
        Path stdDir = dir.resolve("std");
        Files.createDirectories(stdDir);
        for (String stdlibModule : StdlibModuleResolver.SPEC_STDLIB_MODULES) {
            Path source = Path.of(stdlibModule + ".js");
            if (Files.exists(source)) {
                Files.copy(source, stdDir.resolve(
                    stdlibModule.substring(4) + ".js"));
            }
        }
    }

    static String buildJsRunner(ProgramNode program) {
        List<String> zeroAry = new ArrayList<>();
        for (StatementNode statement : program.statements()) {
            if (statement instanceof ExportDeclaration exported
                    && exported.declaration() instanceof FunctionDeclaration function
                    && function.params().isEmpty()
                    && !function.name().contains("$")) {
                zeroAry.add(function.name());
            }
        }
        StringBuilder source = new StringBuilder();
        source.append("\"use strict\";\n");
        source.append("const $rt = require(\"./deal/runtime\");\n");
        source.append("const $zeroAry = [");
        for (int index = 0; index < zeroAry.size(); index++) {
            if (index > 0) {
                source.append(", ");
            }
            source.append('\"').append(zeroAry.get(index)).append('\"');
        }
        source.append("];\n");
        source.append("(async () => {\n");
        source.append("  const $mod = require(\"./Main\");\n");
        source.append("  for (const $k of Object.keys($mod)) {\n");
        source.append("    if ($k.indexOf(\"$\") !== -1) { continue; }\n");
        source.append("    const $v = $mod[$k];\n");
        source.append("    if ($v && $v.$kind === \"function\" && $zeroAry.indexOf($k) !== -1) {\n");
        source.append("      const $r = await $v.$f();\n");
        source.append("      if ($r !== null && $r !== $rt.undefined) { console.log($r); }\n");
        source.append("    }\n");
        source.append("  }\n");
        source.append("})().catch((e) => {\n");
        source.append("  const $err = $rt.reifyError(e);\n");
        source.append("  $rt.reportUncaught($err.file !== $rt.undefined\n");
        source.append("    ? $rt.errorValue($err.code, $err.message + \" at \" + $err.file\n");
        source.append("        + \":\" + $err.line + \":\" + $err.column, $err.file, $err.line, $err.column)\n");
        source.append("    : $err);\n");
        source.append("});\n");
        return source.toString();
    }

}
