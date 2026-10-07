package deal.codegen;

import deal.ast.ClassDeclaration;
import deal.ast.FunctionDeclaration;
import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.lua.LuaBackend;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The test-scope {@link HarnessModuleCodegen} service: the orchestrator's
 * harness arm (a non-release invocation of a LuaJIT/JVM compile) drives
 * the retained AST-walking emitters through this provider.
 *
 * <p>The provider is registered through {@code META-INF/services}; the
 * release classpath carries no registration, so the production source
 * set holds no reference to the retained emitters and the release never
 * runs the harness arm.</p>
 */
public final class HarnessModuleCodegenProvider implements HarnessModuleCodegen {

    /** The service constructor: no state, no configuration. */
    public HarnessModuleCodegenProvider() {
        // Service provider only; no instances beyond the ServiceLoader one.
    }

    @Override
    public LuaResult luaGenerateToFile(ProgramNode program, CheckResult result,
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
            throws IOException {
        LuaBackend.GenerationResult generated = LuaBackend.generateToFile(
            program, result, sourcePath, modulePath,
            stageTree, stageOutputPath, liveOutputRoot, liveOutputPath,
            emitSourceMap, importResolutions, hostModules, ffiModules,
            manifestDirectory, entryModule, identityIndex, semanticProfile,
            plansByModulePath);
        return new LuaResult(generated.lua(), generated.diagnostics());
    }

    @Override
    public List<Type> collectShapes(ProgramNode program, CheckResult result,
            String sourcePath, String modulePath,
            Map<String, String> importResolutions,
            Map<String, Map<String, ClassDeclaration>> importedClasses,
            Map<String, Map<String, Type>> hostModules,
            Map<String, Map<String, List<HostModuleDeclarations.HostField>>>
                hostClassDeclarations,
            SemanticProfile semanticProfile,
            CanonicalClassIdentityIndex identityIndex,
            Function<String, CanonicalModuleIdentity> moduleIdentities) {
        return JvmBackend.collectShapes(program, result, sourcePath, modulePath,
            importResolutions, importedClasses, hostModules,
            hostClassDeclarations, semanticProfile, identityIndex,
            moduleIdentities);
    }

    @Override
    public JvmResult jvmGenerate(ProgramNode program, CheckResult result,
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
            Map<String, ImportedSurface> importedSurfaces) {
        Map<String, JvmBackend.ImportedModuleSurface> surfaces =
            new LinkedHashMap<>();
        for (Map.Entry<String, ImportedSurface> entry
                : importedSurfaces.entrySet()) {
            ImportedSurface surface = entry.getValue();
            surfaces.put(entry.getKey(),
                new JvmBackend.ImportedModuleSurface(surface.functions(),
                    surface.sourcePath()));
        }
        JvmBackend.JvmCodegenResult generated = JvmBackend.generate(
            program, result, sourcePath, modulePath, importResolutions,
            importedClasses, hostModules, hostClassDeclarations, isEntry,
            emitSharedTable, identityIndex, moduleIdentities, semanticProfile,
            sharedShapes, sharedClassDeclarations, sharedHostClasses,
            plansByModulePath, surfaces);
        return new JvmResult(generated.className(), generated.source(),
            generated.diagnostics());
    }
}
