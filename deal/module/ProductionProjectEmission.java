package deal.module;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.jvm.JvmSemanticEmitter;
import deal.codegen.lua.FfiEmissionInput;
import deal.codegen.lua.LuaSemanticEmitter;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
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

    /** The pinned C9 warning of the LuaJIT target (printed verbatim). */
    public static final String WARNING_LUAJIT =
        "Warning: --source-map produces no source-map sidecars with the LuaJIT"
            + " project emission (source maps are LuaJIT/JVM-unavailable in this"
            + " release)";

    /** The pinned C9 warning of the JVM target (printed verbatim). */
    public static final String WARNING_JVM =
        "Warning: --source-map produces no source-map sidecars with the JVM"
            + " project emission (source maps are LuaJIT/JVM-unavailable in this"
            + " release)";

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
     * the one staged project artifact's relative path and no diagnostic,
     * or {@link Outcome#FAILED} with exactly the first diagnostic and no
     * staged artifact.
     *
     */
    public record Result(
            Outcome outcome,
            List<CompilerDiagnostic> diagnostics,
            String artifactRelativePath) {

        public Result {
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(diagnostics, "diagnostics must not be null");
            diagnostics = List.copyOf(diagnostics);
            if (outcome == Outcome.EMITTED
                    && (!diagnostics.isEmpty() || artifactRelativePath == null)) {
                throw new IllegalArgumentException(
                    "an emitted production run carries the one project artifact"
                        + " path and no diagnostic");
            }
            if (outcome == Outcome.FAILED
                    && (diagnostics.isEmpty() || artifactRelativePath != null)) {
                throw new IllegalArgumentException(
                    "a failed production run carries at least one diagnostic and"
                        + " no artifact path");
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
     * the C9 warning, the closure guard's whole-closure step (both
     * HOST-kind declaration kinds admitted and realized) over the
     * closure's resolved import facts, the one project lowering, the one
     * emission, the one staged project
     * artifact, and the unchanged LuaJIT deployment copies. The caller
     * (the phase-4 dispatch) owns the arm selection, the emission record,
     * and the publication transaction; this unit stages artifacts only.
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
            boolean sourceMapExplicit,
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

        // (1) The C9 disposition: the pinned warning once, before emission,
        // only for an explicit --source-map request; this unit never stages
        // a .deal.map.json sidecar.
        if (sourceMapExplicit) {
            System.err.println(backend == Backend.JVM ? WARNING_JVM : WARNING_LUAJIT);
        }

        Optional<CompilerDiagnostic> hostImport =
            hostImportGuard(checkedProject, declarationSurface, invocation);
        if (hostImport.isPresent()) {
            return new Result(Outcome.FAILED, List.of(hostImport.get()), null);
        }

        // (3) The one project lowering over the compile's declared inputs.
        // A failure returns the lowering's first E6005 and stages nothing;
        // no retry and no fallback exist.
        SemanticLowerer.ProjectLoweringResult lowering = SemanticLowerer.lowerProject(
            invocation, checkedProject, interfaceIndex, requirementManifests,
            declarationSurface, declarationModuleIdentities, externCModules,
            builtinError, conversionIntrinsics, callbackExports);
        if (lowering.hasErrors()) {
            return new Result(Outcome.FAILED, lowering.diagnostics(), null);
        }
        ExecutableLoweredProject project = lowering.project();

        // (4) The one emission per target, then the one staged project
        // artifact. An emitter gap is E6005 SHARED_EMITTER_COVERAGE and
        // stages nothing.
        String artifactRelativePath;
        String artifactSource;
        if (backend == Backend.JVM) {
            String className = JvmBackend.classNameFor(project.entryModule().path());
            JvmSemanticEmitter.EmissionResult emission;
            try {
                emission = JvmSemanticEmitter.emitProductionProject(project,
                    lowering.tables(), lowering.registries(), className,
                    declarationSurface);
            } catch (IllegalStateException emitterGap) {
                return new Result(Outcome.FAILED,
                    List.of(sharedEmitterCoverage(project.entryModule().path(),
                        emitterGap, invocation)), null);
            }
            artifactRelativePath = className + ".java";
            artifactSource = emission.source();
        } else {
            String source;
            try {
                source = LuaSemanticEmitter.emitProductionProject(project,
                    lowering.tables(), lowering.registries(),
                    declarationSurface,
                    new FfiEmissionInput(externCModules, manifestDirectory));
            } catch (IllegalStateException emitterGap) {
                return new Result(Outcome.FAILED,
                    List.of(sharedEmitterCoverage(project.entryModule().path(),
                        emitterGap, invocation)), null);
            }
            artifactRelativePath =
                project.entryModule().path().replace('.', '/') + ".lua";
            artifactSource = source;
        }
        stager.stage(artifactRelativePath,
            artifactSource.getBytes(StandardCharsets.UTF_8));

        // (5) The unchanged LuaJIT deployment copies, staged from the
        // resolved distribution surface after the one project artifact. A
        // missing runtime is the pinned E6000; an absent stdlib module is
        // skipped silently (unchanged omission semantics). The JVM target
        // stages no deployment copy.
        if (backend == Backend.LUAJIT) {
            Optional<DistributionHome.ResolvedSource> runtime =
                stager.stageRuntimeCopy(RUNTIME_LUA, distributionHome);
            if (runtime.isEmpty()) {
                return new Result(Outcome.FAILED,
                    List.of(runtimeLibraryMissing()), null);
            }
            for (String stdlibModule : StdlibModuleResolver.SPEC_STDLIB_MODULES) {
                stager.stageStdlibCopy(
                    stdlibModule.substring("std/".length()), "lua",
                    distributionHome);
            }
        }
        return new Result(Outcome.EMITTED, List.of(), artifactRelativePath);
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
        if (gap instanceof LuaSemanticEmitter.FfiProviderGap providerGap) {
            return FailureContractRegistry.e6005(new LoweringFailureDetail(
                providerGap.consumingModulePath(), SemanticCapability.MODULES,
                SHARED_EMITTER_COVERAGE, invocation.semanticProfile(),
                LoweredModuleUnit.FORMAT_VERSION,
                "ProductionProjectEmission " + SHARED_EMITTER_COVERAGE + " "
                    + FFI_PROVIDER_GAP + " (" + providerGap.getMessage()
                    + ")"));
        }
        return FailureContractRegistry.e6005(new LoweringFailureDetail(
            entryModulePath, SemanticCapability.MODULES, SHARED_EMITTER_COVERAGE,
            invocation.semanticProfile(), LoweredModuleUnit.FORMAT_VERSION,
            "ProductionProjectEmission " + SHARED_EMITTER_COVERAGE + " ("
                + gap.getMessage() + ")"));
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
