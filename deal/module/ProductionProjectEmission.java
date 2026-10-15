package deal.module;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.SourceMapGenerator;
import deal.codegen.SourceMapPaths;
import deal.codegen.SourceMapRecorder;
import deal.codegen.jvm.JvmNames;
import deal.codegen.jvm.JvmSemanticEmitter;
import deal.codegen.lua.FfiEmissionInput;
import deal.codegen.lua.LuaSemanticEmitter;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticRange;
import deal.distribution.DistributionHome;
import deal.ffi.FfiGeneratedModule;
import deal.identity.CanonicalModuleIdentity;
import deal.publication.PublicationStager;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedProjectInput;
import deal.semantic.CompilerInvocation;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.ir.DiagnosticOriginCarrier;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.ResolvedImport;
import deal.semantic.ir.SemanticCapability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ProductionProjectEmission {

    /** The registered rule ID of every emitter-coverage failure. */
    public static final String SHARED_EMITTER_COVERAGE = "SHARED_EMITTER_COVERAGE";

    public static final String HOST_MODULE_IMPORT = "HOST_MODULE_IMPORT";

    /**
     * The stable detail token of the FFI provider-gap outcome (the
     * extern-C load emission's binding step; design source
     * {@code plan-evaluator-provider-binding-surface} P4 and the
     * provider-gap fail-closed contract): a wrapper-incapable provider or
     * an import alias naming two provider modules fails the compile closed
     * with one E6005 {@code SHARED_EMITTER_COVERAGE} whose detail names the
     * consuming module, the extern-C import statement's origin, the
     * import's raw specifier, the resolved declaration module, and the
     * offending provider alias and module path.
     */
    public static final String FFI_PROVIDER_GAP = "FFI_PROVIDER_GAP";

    /** The unchanged LuaJIT runtime deployment copy source name. */
    private static final String RUNTIME_LUA = "deal/runtime.lua";

    private ProductionProjectEmission() {
        // Static production entry only; no instances.
    }

    /** The two outcomes of one production arm run. */
    public enum Outcome {
        /** The one project artifact (and the LuaJIT copies) staged. */
        EMITTED,
        /** Nothing staged; the carried E6005/E6000 is the compile failure. */
        FAILED
    }

    /**
     * The outcome of one production arm run: {@link Outcome#EMITTED} with
     * the one staged project artifact's relative path, the produced
     * project, and no diagnostic, or {@link Outcome#FAILED} with exactly
     * the first diagnostic, no produced project, and no staged artifact.
     *
     */
    public record Result(
            Outcome outcome,
            List<CompilerDiagnostic> diagnostics,
            ExecutableLoweredProject producedProject,
            String artifactRelativePath) {

        public Result {
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(diagnostics, "diagnostics must not be null");
            diagnostics = List.copyOf(diagnostics);
            if (outcome == Outcome.EMITTED
                    && (!diagnostics.isEmpty() || artifactRelativePath == null
                        || producedProject == null)) {
                throw new IllegalArgumentException(
                    "an emitted production run carries the one project artifact"
                        + " path, its produced project, and no diagnostic");
            }
            if (outcome == Outcome.FAILED
                    && (diagnostics.isEmpty() || artifactRelativePath != null
                        || producedProject != null)) {
                throw new IllegalArgumentException(
                    "a failed production run carries at least one diagnostic,"
                        + " no produced project, and no artifact path");
            }
        }

        /** Whether the run staged the one project artifact. */
        public boolean emitted() {
            return outcome == Outcome.EMITTED;
        }

        /** The first diagnostic, or {@code null} when the run emitted. */
        public CompilerDiagnostic firstDiagnostic() {
            return diagnostics.isEmpty() ? null : diagnostics.get(0);
        }
    }

    /**
     * Runs the production arm for one release-owned production compile:
     * the closure guard's whole-closure step (both HOST-kind declaration
     * kinds admitted and realized) over the closure's resolved import
     * facts, the one project lowering, the one emission (with the one
     * source-map recorder: the project's source comments and per-module
     * {@code .deal.map.json} sidecars are the v1.3 default and the
     * accepted {@code sourceMap} spelling selects nothing — the one staged
     * project artifact with its per-module sidecars, and the complete
     * LuaJIT deployment copies. The caller (the phase-4 dispatch)
     * owns the arm selection, the emission record, and the publication
     * transaction; this unit stages artifacts only.
     *
     */
    public static Result run(
            CompilerInvocation invocation,
            CheckedProjectInput checkedProject,
            ProjectInterfaceIndex interfaceIndex,
            List<SemanticRequirementManifest> requirementManifests,
            HostDeclarationSurface declarationSurface,
            Map<ModuleId, CanonicalModuleIdentity> declarationModuleIdentities,
            Map<ModuleId, FfiGeneratedModule> externCModules,
            String manifestDirectory,
            BuiltinErrorDeclaration builtinError,
            List<IntrinsicKind> conversionIntrinsics,
            Set<String> callbackExports,
            Backend backend,
            boolean sourceMap,
            DistributionHome distributionHome,
            PublicationStager stager) throws IOException {
        Objects.requireNonNull(invocation, "invocation must not be null");
        Objects.requireNonNull(checkedProject, "checkedProject must not be null");
        Objects.requireNonNull(interfaceIndex, "interfaceIndex must not be null");
        Objects.requireNonNull(requirementManifests,
            "requirementManifests must not be null");
        Objects.requireNonNull(declarationSurface,
            "declarationSurface must not be null");
        Objects.requireNonNull(declarationModuleIdentities,
            "declarationModuleIdentities must not be null");
        Objects.requireNonNull(externCModules, "externCModules must not be null");
        Objects.requireNonNull(manifestDirectory,
            "manifestDirectory must not be null");
        Objects.requireNonNull(builtinError, "builtinError must not be null");
        Objects.requireNonNull(conversionIntrinsics,
            "conversionIntrinsics must not be null");
        Objects.requireNonNull(callbackExports, "callbackExports must not be null");
        Objects.requireNonNull(backend, "backend must not be null");
        Objects.requireNonNull(distributionHome, "distributionHome must not be null");
        Objects.requireNonNull(stager, "stager must not be null");
        if (backend != Backend.LUAJIT && backend != Backend.JVM) {
            throw new IllegalArgumentException(
                "the production project emission targets LuaJIT or JVM only; got "
                    + backend);
        }

        Optional<CompilerDiagnostic> hostImport =
            hostImportGuard(checkedProject, declarationSurface, invocation);
        if (hostImport.isPresent()) {
            return new Result(Outcome.FAILED, List.of(hostImport.get()), null, null);
        }

        // (3) The one project lowering over the compile's declared inputs.
        // A failure returns the lowering's first E6005 and stages nothing;
        // no retry and no fallback exist.
        SemanticLowerer.ProjectLoweringResult lowering = SemanticLowerer.lowerProject(
            invocation, checkedProject, interfaceIndex, requirementManifests,
            declarationSurface, declarationModuleIdentities, externCModules,
            builtinError, conversionIntrinsics, callbackExports);
        if (lowering.hasErrors()) {
            return new Result(Outcome.FAILED, lowering.diagnostics(), null, null);
        }
        ExecutableLoweredProject project = lowering.project();

        // (4) The one emission per target — with the one source-map
        // recorder (the v1.3 default debugging artifacts; the accepted
        // {@code sourceMap} spelling selects nothing) — then the one
        // staged project artifact and its per-module source-map sidecars.
        // An emitter gap is E6005 SHARED_EMITTER_COVERAGE and stages
        // nothing.
        String jvmClassName = backend == Backend.JVM
            ? JvmNames.classNameFor(project.entryModule().path())
            : null;
        String artifactRelativePath = backend == Backend.JVM
            ? jvmClassName + ".java"
            : project.entryModule().path().replace('.', '/') + ".lua";
        SourceMapRecorder sourceMapRecorder =
            sourceMapRecorder(checkedProject, artifactRelativePath, stager);
        String artifactSource;
        if (backend == Backend.JVM) {
            JvmSemanticEmitter.EmissionResult emission;
            try {
                emission = JvmSemanticEmitter.emitProductionProject(project,
                    lowering.tables(), lowering.registries(), jvmClassName,
                    declarationSurface, sourceMapRecorder);
            } catch (IllegalStateException emitterGap) {
                return new Result(Outcome.FAILED,
                    List.of(sharedEmitterCoverage(project.entryModule().path(),
                        emitterGap, invocation)), null, null);
            }
            artifactSource = emission.source();
        } else {
            String source;
            try {
                source = LuaSemanticEmitter.emitProductionProject(project,
                    lowering.tables(), lowering.registries(),
                    declarationSurface,
                    new FfiEmissionInput(externCModules, manifestDirectory),
                    sourceMapRecorder);
            } catch (IllegalStateException emitterGap) {
                return new Result(Outcome.FAILED,
                    List.of(sharedEmitterCoverage(project.entryModule().path(),
                        emitterGap, invocation)), null, null);
            }
            artifactSource = source;
        }
        stager.stage(artifactRelativePath,
            artifactSource.getBytes(StandardCharsets.UTF_8));
        stageSourceMapSidecars(project, sourceMapRecorder,
            artifactRelativePath, stager);

        // (5) The complete LuaJIT deployment copies, staged from the
        // resolved distribution surface after the one project artifact. A
        // missing runtime or a missing spec-listed stdlib implementation
        // is missing required deployment content: the pinned E6000 with
        // nothing published. The JVM target stages no deployment copy.
        if (backend == Backend.LUAJIT) {
            Optional<DistributionHome.ResolvedSource> runtime =
                stager.stageRuntimeCopy(RUNTIME_LUA, distributionHome);
            if (runtime.isEmpty()) {
                return new Result(Outcome.FAILED,
                    List.of(runtimeLibraryMissing()), null, null);
            }
            for (String stdlibModule : StdlibModuleResolver.SPEC_STDLIB_MODULES) {
                String implementation = stdlibModule.substring("std/".length());
                if (stager.stageStdlibCopy(implementation, "lua",
                        distributionHome).isEmpty()) {
                    return new Result(Outcome.FAILED,
                        List.of(stdlibLibraryMissing(implementation, "lua")),
                        null, null);
                }
            }
        }
        return new Result(Outcome.EMITTED, List.of(), project,
            artifactRelativePath);
    }

    /**
     * The one source-map recorder of a production run: one
     * {@link SourceMapGenerator} per constituent module with the shared
     * project-relative display source name ({@link SourceMapPaths}, the same
     * normalization the JavaScript orchestrator arm consumes), so the emitted
     * source comments and the staged sidecars carry one file spelling and a
     * single mapping authority owns the emission. The v1.3 default debugging
     * artifacts always install it; the accepted {@code sourceMap} request
     * spelling does not select a recorder-less emission.
     */
    private static SourceMapRecorder sourceMapRecorder(
            CheckedProjectInput checkedProject, String artifactRelativePath,
            PublicationStager stager) {
        Path root = stager.publicationRoot();
        Path generated = root.resolve(artifactRelativePath);
        Map<ModuleId, String> displaySourceNames = new LinkedHashMap<>();
        for (CheckedModuleInput module : checkedProject.modules()) {
            displaySourceNames.put(module.moduleId(),
                SourceMapPaths.sourceDisplay(module.sourceId(), root));
        }
        return new SourceMapRecorder(displaySourceNames);
    }

    /**
     * Stages the project's source-map sidecars into the same publication
     * transaction as the artifact: one {@code <module>.deal.map.json} per
     * constituent module — its own source file as {@code source}, the one
     * shared artifact as {@code generated} — carrying the mappings recorded
     * for that module's ops. A module that recorded no mappable op still
     * receives its sidecar with an empty mappings array, so every
     * constituent source file keeps exactly one sidecar. The stager publishes
     * the whole set atomically, so a failure anywhere before the swap leaves
     * the prior artifacts and maps untouched.
     */
    private static void stageSourceMapSidecars(ExecutableLoweredProject project,
            SourceMapRecorder recorder, String artifactRelativePath,
            PublicationStager stager) throws IOException {
        Path root = stager.publicationRoot();
        String generatedDisplay = SourceMapPaths.generatedDisplay(root,
            root.resolve(artifactRelativePath));
        for (ModuleId module : project.modules().keySet()) {
            SourceMapGenerator generator = recorder.generatorFor(module);
            if (generator == null) {
                generator = new SourceMapGenerator();
            }
            String json = generator.toJson(
                recorder.displayName(module, module.path()), generatedDisplay);
            stager.stage(module.path().replace('.', '/') + ".deal.map.json",
                json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Optional<CompilerDiagnostic> hostImportGuard(
            CheckedProjectInput checkedProject,
            HostDeclarationSurface declarationSurface,
            CompilerInvocation invocation) {
        for (CheckedModuleInput module : checkedProject.modules()) {
            for (ResolvedImport importFact : module.imports()) {
                if (importFact.kind() != ExternalModuleKind.HOST) {
                    continue;
                }
                if (!declarationSurface.modules()
                        .containsKey(importFact.resolvedModuleId())) {
                    // An uncovered declaration import: the lowering's
                    // declaration-fact agreement reports it; this guard
                    // never defaults a missing fact.
                    continue;
                }

            }
        }
        return Optional.empty();
    }

    private static CompilerDiagnostic hostModuleImportFailure(ModuleId emitting,
            String rawSpecifier, String resolvedModulePath,
            CompilerInvocation invocation) {
        return FailureContractRegistry.e6005(new LoweringFailureDetail(
            emitting.path(), SemanticCapability.MODULES, SHARED_EMITTER_COVERAGE,
            invocation.semanticProfile(), LoweredModuleUnit.FORMAT_VERSION,
            "ProductionProjectEmission " + SHARED_EMITTER_COVERAGE + " "
                + HOST_MODULE_IMPORT + " (emitting module '" + emitting.path()
                + "', import '" + rawSpecifier + "' resolved to '"
                + resolvedModulePath + "')"));
    }

    private static CompilerDiagnostic sharedEmitterCoverage(
            String entryModulePath, IllegalStateException gap,
            CompilerInvocation invocation) {
        // The production arm owns classification and projection, not source
        // selection: a source-bearing emitter rejection signal carries the
        // range of its explicit rejected op (or detached body), and an
        // ordinary genuinely originless IllegalStateException keeps the
        // canonical empty-file synthetic range.
        DiagnosticRange carried = gap instanceof DiagnosticOriginCarrier carrier
            ? carrier.diagnosticRange() : null;
        if (gap instanceof LuaSemanticEmitter.FfiProviderGap providerGap) {
            // The specialized provider gap keeps its consuming-module detail
            // and its extern-C import statement's own origin.
            LoweringFailureDetail detail = new LoweringFailureDetail(
                providerGap.consumingModulePath(), SemanticCapability.MODULES,
                SHARED_EMITTER_COVERAGE, invocation.semanticProfile(),
                LoweredModuleUnit.FORMAT_VERSION,
                "ProductionProjectEmission " + SHARED_EMITTER_COVERAGE + " "
                    + FFI_PROVIDER_GAP + " (" + providerGap.getMessage()
                    + ")");
            return FailureContractRegistry.e6005(detail, carried);
        }
        return FailureContractRegistry.e6005(new LoweringFailureDetail(
            entryModulePath, SemanticCapability.MODULES, SHARED_EMITTER_COVERAGE,
            invocation.semanticProfile(), LoweredModuleUnit.FORMAT_VERSION,
            "ProductionProjectEmission " + SHARED_EMITTER_COVERAGE + " ("
                + gap.getMessage() + ")"), carried);
    }

    /**
     * The pinned missing-stdlib E6000 of the deployment copies (the same
     * synthetic error shape as the missing-runtime diagnostic): a
     * spec-listed stdlib implementation the resolved distribution surface
     * does not provide is missing required deployment content, never a
     * silent omission. Shared with the JavaScript deployment copies, so
     * every backend reports one spelling.
     */
    public static CompilerDiagnostic stdlibLibraryMissing(
            String moduleName, String extension) {
        String path = "std/" + moduleName + "." + extension;
        return CompilerDiagnostic.syntheticError(DiagnosticCode.E6000,
            "Stdlib module not found: " + path, "",
            "missing anchor: stdlib module path '" + path + "'");
    }

    /**
     * The pinned missing-runtime E6000 of the LuaJIT deployment copies
     * (unchanged text and shape: an anchorless synthetic error with the
     * runtime path note).
     */
    private static CompilerDiagnostic runtimeLibraryMissing() {
        return CompilerDiagnostic.syntheticError(DiagnosticCode.E6000,
            "Runtime library not found: " + RUNTIME_LUA, "",
            "missing anchor: runtime library path '" + RUNTIME_LUA + "'");
    }
}
