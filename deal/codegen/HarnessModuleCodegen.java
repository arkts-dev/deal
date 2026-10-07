package deal.codegen;

import deal.ast.ClassDeclaration;
import deal.ast.FunctionDeclaration;
import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.codegen.jvm.JvmSemanticEmitter;
import deal.codegen.lua.LuaSemanticEmitter;
import deal.diagnostics.CompilerDiagnostic;
import deal.ffi.FfiGeneratedModule;
import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalClassIdentityIndex;
import deal.identity.CanonicalModuleIdentity;
import deal.module.ModuleIdentityResolver;
import deal.module.PlannedDefaultClass;
import deal.semantic.ir.SemanticProfile;
import deal.types.Type;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Function;

/**
 * The retained test-scope AST module-codegen surface behind the
 * orchestrator's harness arm.
 *
 * <p>A LuaJIT/JVM compile whose invocation is not the release-owned
 * production record runs the harness arm of phase 4, whose per-module
 * emission is the retained AST-walking codegen. That codegen is no
 * longer part of the production source set: the production arm
 * ({@link LuaSemanticEmitter#emitProductionProject} and
 * {@link JvmSemanticEmitter#emitProductionProject} through
 * {@code deal.module.ProductionProjectEmission}) is the only release
 * path, and this interface is the extension point the test scope
 * registers its retained emitter implementation through
 * ({@link ServiceLoader}).
 *
 * <p>No provider exists on the release classpath, so
 * {@link #current()} fails closed there; the harness arm never runs for
 * a release-owned invocation anyway. The result carriers expose exactly
 * the fields the harness arm consumes.
 */
public interface HarnessModuleCodegen {

    /** One retained LuaJIT module emission: the generated chunk and the
     * backend diagnostics to merge. */
    record LuaResult(String lua,
                     List<CompilerDiagnostic> diagnostics) {

        public LuaResult {
            java.util.Objects.requireNonNull(lua, "lua must not be null");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** One retained JVM module emission: the derived class name, the
     * generated class source, and the backend diagnostics to merge. */
    record JvmResult(String className, String source,
                     List<CompilerDiagnostic> diagnostics) {

        public JvmResult {
            java.util.Objects.requireNonNull(className,
                "className must not be null");
            java.util.Objects.requireNonNull(source, "source must not be null");
            diagnostics = List.copyOf(diagnostics);
        }

        /** True when at least one error-level diagnostic was recorded. */
        public boolean hasErrors() {
            return diagnostics.stream()
                .anyMatch(d -> "error".equals(d.severity()));
        }
    }

    /** One imported compiled module's declared-boundary surface (D6):
     * the module's exported function declarations by name and the
     * module's source path. */
    record ImportedSurface(Map<String, FunctionDeclaration> functions,
                           String sourcePath) {

        public ImportedSurface {
            functions = functions == null ? Map.of() : Map.copyOf(functions);
        }
    }

    /**
     * Emits one module through the retained LuaJIT AST emitter with the
     * staging sink and the optional source-map sidecar, returning the
     * generated chunk and the backend diagnostics. Writes no artifact
     * when the emission produced error diagnostics.
     */
    LuaResult luaGenerateToFile(ProgramNode program, CheckResult result,
        String sourcePath, String modulePath,
        Path stageTree, Path stageOutputPath,
        Path liveOutputRoot, Path liveOutputPath,
        boolean emitSourceMap,
        Map<String, String> importResolutions,
        Map<String, Map<String, Type>> hostModules,
        Map<String, FfiGeneratedModule> ffiModules,
        String manifestDirectory,
        boolean entryModule,
        ModuleIdentityResolver.IdentityIndex identityIndex,
        SemanticProfile semanticProfile,
        Map<String, List<PlannedDefaultClass>> plansByModulePath)
        throws IOException;

    /**
     * Collects one module's closed runtime shape set through the retained
     * JVM AST emitter (the project-wide shape collection pass).
     */
    List<Type> collectShapes(ProgramNode program, CheckResult result,
        String sourcePath, String modulePath,
        Map<String, String> importResolutions,
        Map<String, Map<String, ClassDeclaration>> importedClasses,
        Map<String, Map<String, Type>> hostModules,
        Map<String, Map<String, List<HostModuleDeclarations.HostField>>>
            hostClassDeclarations,
        SemanticProfile semanticProfile,
        CanonicalClassIdentityIndex identityIndex,
        Function<String, CanonicalModuleIdentity> moduleIdentities);

    /**
     * Emits one module through the retained JVM AST emitter with the
     * project-wide shared shape surface, returning the derived class
     * name, the generated class source, and the backend diagnostics.
     */
    JvmResult jvmGenerate(ProgramNode program, CheckResult result,
        String sourcePath, String modulePath,
        Map<String, String> importResolutions,
        Map<String, Map<String, ClassDeclaration>> importedClasses,
        Map<String, Map<String, Type>> hostModules,
        Map<String, Map<String, List<HostModuleDeclarations.HostField>>>
            hostClassDeclarations,
        boolean isEntry,
        boolean emitSharedTable,
        CanonicalClassIdentityIndex identityIndex,
        Function<String, CanonicalModuleIdentity> moduleIdentities,
        SemanticProfile semanticProfile,
        List<Type> sharedShapes,
        Map<CanonicalClassIdentity, String> sharedClassDeclarations,
        Map<String, Map<String, List<HostModuleDeclarations.HostField>>>
            sharedHostClasses,
        Map<String, List<PlannedDefaultClass>> plansByModulePath,
        Map<String, ImportedSurface> importedSurfaces);

    /**
     * The registered retained codegen of the running classpath: the test
     * scope's service-provider implementation, or the fail-closed
     * default when no provider is registered (the release classpath).
     */
    static HarnessModuleCodegen current() {
        return Holder.current();
    }

    /** The lazy provider holder: the registered codegen is cached on
     * first use; the release classpath has no provider and gets the
     * fail-closed default. */
    final class Holder {

        private Holder() {
            // Static holder only; no instances.
        }

        /** The cached registered codegen; {@code null} until first use. */
        private static HarnessModuleCodegen instance;

        static HarnessModuleCodegen current() {
            HarnessModuleCodegen codegen = instance;
            if (codegen == null) {
                synchronized (Holder.class) {
                    codegen = instance;
                    if (codegen == null) {
                        codegen = ServiceLoader
                            .load(HarnessModuleCodegen.class)
                            .findFirst().orElse(UNSUPPORTED);
                        instance = codegen;
                    }
                }
            }
            return codegen;
        }
    }

    /** The fail-closed default: the retained AST codegen is test-scope
     * only, so the harness arm cannot run without its provider. */
    HarnessModuleCodegen UNSUPPORTED = new HarnessModuleCodegen() {

        private UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException(
                "the retained harness module codegen is not registered on "
                    + "this classpath: the release production path emits one "
                    + "project artifact and never runs the per-module harness "
                    + "arm");
        }

        @Override
        public LuaResult luaGenerateToFile(ProgramNode program,
                CheckResult result, String sourcePath, String modulePath,
                Path stageTree, Path stageOutputPath, Path liveOutputRoot,
                Path liveOutputPath, boolean emitSourceMap,
                Map<String, String> importResolutions,
                Map<String, Map<String, Type>> hostModules,
                Map<String, FfiGeneratedModule> ffiModules,
                String manifestDirectory, boolean entryModule,
                ModuleIdentityResolver.IdentityIndex identityIndex,
                SemanticProfile semanticProfile,
                Map<String, List<PlannedDefaultClass>> plansByModulePath) {
            throw unsupported();
        }

        @Override
        public List<Type> collectShapes(ProgramNode program,
                CheckResult result, String sourcePath, String modulePath,
                Map<String, String> importResolutions,
                Map<String, Map<String, ClassDeclaration>> importedClasses,
                Map<String, Map<String, Type>> hostModules,
                Map<String, Map<String, List<HostModuleDeclarations.HostField>>>
                    hostClassDeclarations,
                SemanticProfile semanticProfile,
                CanonicalClassIdentityIndex identityIndex,
                Function<String, CanonicalModuleIdentity> moduleIdentities) {
            throw unsupported();
        }

        @Override
        public JvmResult jvmGenerate(ProgramNode program, CheckResult result,
                String sourcePath, String modulePath,
                Map<String, String> importResolutions,
                Map<String, Map<String, ClassDeclaration>> importedClasses,
                Map<String, Map<String, Type>> hostModules,
                Map<String, Map<String, List<HostModuleDeclarations.HostField>>>
                    hostClassDeclarations,
                boolean isEntry, boolean emitSharedTable,
                CanonicalClassIdentityIndex identityIndex,
                Function<String, CanonicalModuleIdentity> moduleIdentities,
                SemanticProfile semanticProfile, List<Type> sharedShapes,
                Map<CanonicalClassIdentity, String> sharedClassDeclarations,
                Map<String, Map<String, List<HostModuleDeclarations.HostField>>>
                    sharedHostClasses,
                Map<String, List<PlannedDefaultClass>> plansByModulePath,
                Map<String, ImportedSurface> importedSurfaces) {
            throw unsupported();
        }
    };
}
