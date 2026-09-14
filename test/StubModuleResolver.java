package deal.test;

import deal.ast.ExportDeclaration;
import deal.ast.FunctionDeclaration;
import deal.ast.NamedType;
import deal.ast.ProgramNode;
import deal.ast.StatementNode;
import deal.ast.TypeNode;
import deal.checker.ModuleResolver;
import deal.checker.Symbol;
import deal.codegen.jvm.JvmBackend;
import deal.identity.CanonicalModuleIdentity;
import deal.module.StdlibModuleResolver;
import deal.types.Type;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Stub module resolver for unit-testing the checker without a real module system.
 */
final class StubModuleResolver implements ModuleResolver {

    private final Map<String, Map<String, Type>> modules = new HashMap<>();
    private final Map<String, Symbol.ClassSymbol> classSymbols = new HashMap<>();
    private final boolean acceptUnknownModules;

    StubModuleResolver() {
        this(false);
    }

    private StubModuleResolver(boolean acceptUnknownModules) {
        this.acceptUnknownModules = acceptUnknownModules;
        modules.putAll(StdlibModuleResolver.stdlibExports(
            Path.of("std").toAbsolutePath().normalize().toString()));
    }

    static StubModuleResolver acceptingUnknownModules() {
        return new StubModuleResolver(true);
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
        if (acceptUnknownModules) {
            return Map.of();
        }
        throw new ModuleNotFoundException("Module not found: " + modulePath);
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

    static boolean compileWithJavac(Path dir, List<String> javaFiles,
                                    StringBuilder err) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            err.append("no system Java compiler available (javax.tools)");
            return false;
        }
        DiagnosticCollector<JavaFileObject> diagnostics =
            new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler
                .getStandardFileManager(diagnostics, null,
                    StandardCharsets.UTF_8)) {
            List<File> files = new ArrayList<>();
            for (String name : javaFiles) {
                files.add(dir.resolve(name).toFile());
            }
            Iterable<? extends JavaFileObject> units =
                fileManager.getJavaFileObjectsFromFiles(files);
            StringWriter messages = new StringWriter();
            List<String> options = List.of(
                "-encoding", "UTF-8",
                "-classpath", dir.toString(),
                "-d", dir.toString(),
                "-proc:none",
                "-implicit:none",
                "-g:none");
            Boolean ok = compiler.getTask(messages, fileManager, diagnostics,
                options, null, units).call();
            if (!Boolean.TRUE.equals(ok)) {
                err.append(messages);
                for (javax.tools.Diagnostic<? extends JavaFileObject> diagnostic
                        : diagnostics.getDiagnostics()) {
                    err.append(diagnostic).append('\n');
                }
                return false;
            }
            return true;
        } catch (IOException e) {
            err.append(e);
            return false;
        }
    }

    static String buildJvmRunner(ProgramNode program, String className) {
        StringBuilder source = new StringBuilder();
        source.append("public final class JvmConformanceRunner {\n");
        source.append("    public static void main(String[] args) {\n");
        source.append("        try {\n");
        boolean invoked = false;
        for (StatementNode statement : program.statements()) {
            if (statement instanceof ExportDeclaration exported
                    && exported.declaration() instanceof FunctionDeclaration function
                    && function.params().isEmpty()) {
                invoked = true;
                String name = JvmBackend.javaName(function.name());
                if (isNullReturnType(function.returnType())) {
                    source.append("            ").append(className).append('.')
                        .append(name).append("();\n");
                } else {
                    source.append("            System.out.println(")
                        .append(className).append('.').append(name)
                        .append("());\n");
                }
            }
        }
        if (!invoked) {
            source.append("            try { Class.forName(\"")
                .append(className).append("\"); }\n");
            source.append("            catch (ClassNotFoundException e) { throw new RuntimeException(e); }\n");
        }
        source.append("        } catch (Throwable e) {\n");
        source.append("            reportError(e);\n");
        source.append("        }\n");
        source.append("    }\n");
        source.append("    private static void reportError(Throwable e) {\n");
        source.append("        Throwable t = e;\n");
        source.append("        while (t instanceof ExceptionInInitializerError && t.getCause() != null) { t = t.getCause(); }\n");
        source.append("        String code = null;\n");
        source.append("        if (\"DealError\".equals(t.getClass().getSimpleName())) {\n");
        source.append("            try {\n");
        source.append("                java.lang.reflect.Field f = t.getClass().getDeclaredField(\"code\");\n");
        source.append("                f.setAccessible(true);\n");
        source.append("                code = String.valueOf(f.get(t));\n");
        source.append("            } catch (ReflectiveOperationException ignored) { }\n");
        source.append("        }\n");
        source.append("        if (code != null) {\n");
        source.append("            System.out.println(\"DEAL_ERROR_CODE: \" + code + \" \" + t.getMessage());\n");
        source.append("        } else {\n");
        source.append("            System.out.println(\"DEAL_ERROR_CODE: \" + (t.getMessage() == null ? t.toString() : t.getMessage()));\n");
        source.append("        }\n");
        source.append("        System.exit(1);\n");
        source.append("    }\n");
        source.append("}\n");
        return source.toString();
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

    private static boolean isNullReturnType(TypeNode type) {
        return type instanceof NamedType named && "null".equals(named.name());
    }
}
