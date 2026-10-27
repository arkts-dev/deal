package deal.module;

import deal.ast.ImportDeclaration;
import deal.ast.StatementNode;
import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.SourceMapGenerator;
import deal.codegen.SourceMapPaths;
import deal.codegen.SourceMapRecorder;
import deal.codegen.SharedEmissionFailure;
import deal.codegen.js.JsEmissionInput;
import deal.codegen.js.JsModuleArtifact;
import deal.codegen.js.JsProjectEmission;
import deal.codegen.js.JsSemanticEmitter;
import deal.codegen.js.JsSourceFile;
import deal.codegen.js.RuntimeDeclarationProjection;
import deal.codegen.js.RuntimeDeclarationSurface;
import deal.codegen.jvm.JvmNames;
import deal.codegen.jvm.JvmSemanticEmitter;
import deal.codegen.lua.LuaSemanticEmitter;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticRange;
import deal.module.CheckoutResources;
import deal.identity.CanonicalModuleIdentity;
import deal.publication.PublicationStager;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedModuleKind;
import deal.semantic.CheckedProjectInput;
import deal.semantic.CompilerInvocation;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.ir.DiagnosticOriginCarrier;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.FailureContractRegistry;
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
    /** The unchanged LuaJIT runtime deployment copy source name. */
    private static final String RUNTIME_LUA = "deal/runtime.lua";

    /** The unchanged JavaScript runtime deployment copy source name. */
    private static final String RUNTIME_JS = "deal/runtime.js";

    private ProductionProjectEmission() {
        // Static production entry only; no instances.
    }

    /** The two outcomes of one production arm run. */
    public enum Outcome {
        /** The one project artifact and the target's deployment copies staged. */
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


    public static Result run(
            CompilerInvocation invocation,
            CheckedProjectInput checkedProject,
            ProjectInterfaceIndex interfaceIndex,
            List<SemanticRequirementManifest> requirementManifests,
            HostDeclarationSurface declarationSurface,
            Map<ModuleId, CanonicalModuleIdentity> declarationModuleIdentities,
            BuiltinErrorDeclaration builtinError,
            Set<String> callbackExports,
            Backend backend,
            boolean sourceMap,
            CheckoutResources checkoutResources,
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
        Objects.requireNonNull(builtinError, "builtinError must not be null");
        Objects.requireNonNull(callbackExports, "callbackExports must not be null");
        Objects.requireNonNull(backend, "backend must not be null");
        Objects.requireNonNull(checkoutResources, "checkoutResources must not be null");
        Objects.requireNonNull(stager, "stager must not be null");
        if (backend != Backend.LUAJIT && backend != Backend.JVM
                && backend != Backend.JS) {
            throw new IllegalArgumentException(
                "the production project emission targets LuaJIT, JVM or JS only;"
                    + " got " + backend);
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
            declarationSurface, declarationModuleIdentities,
            builtinError, callbackExports);
        if (lowering.hasErrors()) {
            return new Result(Outcome.FAILED, lowering.diagnostics(), null, null);
        }
        ExecutableLoweredProject project = lowering.project();

        if (backend == Backend.JS) {
            return emitJsProject(project, lowering, checkedProject,
                declarationSurface, declarationModuleIdentities, invocation,
                checkoutResources, stager);
        }

        // (4) The one emission per target — with the one source-map
        // recorder (the v1.4 default debugging artifacts; the accepted
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
            Optional<CompilerDiagnostic> deployment =
                stageDeploymentCopies(RUNTIME_LUA, "lua", checkoutResources,
                    stager);
            if (deployment.isPresent()) {
                return new Result(Outcome.FAILED, List.of(deployment.get()),
                    null, null);
            }
        }
        return new Result(Outcome.EMITTED, List.of(), project,
            artifactRelativePath);
    }

    /**
     * The JavaScript arm of the one production project emission (design
     * source {@code wiki:javascript-semantic-ir-production-migration},
     * "production_owner" and "artifact_layout"): the one
     * {@link JsSemanticEmitter#emitProductionProject} invocation over the
     * already-lowered project returns every implementation module's CommonJS
     * artifact and coordinate list, then this owner stages the complete set
     * in the existing exclusive-root transaction — one artifact per
     * implementation unit at its own {@code moduleId.path().replace('.',
     * '/') + ".js"} path, that module's own version-1 {@code .deal.map.json}
     * sidecar, and the complete JavaScript runtime and stdlib deployment
     * copies.
     *
     * <p>{@code Result.artifactRelativePath} names the entry module's own
     * artifact; it does not enumerate the staged set. The renderer runs once
     * and is never re-invoked, re-lowered, or routed to an alternate emitter;
     * an invalid input shape or a fail-closed emitter rejection becomes one
     * E6005 {@link #SHARED_EMITTER_COVERAGE} carrying the offending
     * operation's range when the signal identified one, and nothing is
     * published.</p>
     *
     */
    private static Result emitJsProject(
            ExecutableLoweredProject project,
            SemanticLowerer.ProjectLoweringResult lowering,
            CheckedProjectInput checkedProject,
            HostDeclarationSurface declarationSurface,
            Map<ModuleId, CanonicalModuleIdentity> declarationModuleIdentities,
            CompilerInvocation invocation,
            CheckoutResources checkoutResources,
            PublicationStager stager) throws IOException {
        JsEmissionInput input;
        JsProjectEmission emission;
        try {
            input = jsEmissionInput(project, lowering, checkedProject,
                declarationSurface, declarationModuleIdentities, stager);
            emission = JsSemanticEmitter.emitProductionProject(input);
        } catch (IllegalStateException emitterGap) {
            // A SharedEmissionFailure carries the rejected op's own copied
            // origin; a genuinely originless signal keeps the canonical
            // synthetic projection.
            return new Result(Outcome.FAILED,
                List.of(sharedEmitterCoverage(project.entryModule().path(),
                    emitterGap, invocation)), null, null);
        } catch (IllegalArgumentException invalidInput) {
            // An invalid or incomplete structural input shape is the same
            // fail-closed classification: one E6005 with no artifact.
            return new Result(Outcome.FAILED,
                List.of(sharedEmitterCoverage(project.entryModule().path(),
                    new SharedEmissionFailure(invalidInput.getMessage(),
                        invalidInput, null), invocation)), null, null);
        }

        for (JsModuleArtifact artifact : emission.modules()) {
            stager.stage(artifact.artifactRelativePath(),
                artifact.source().getBytes(StandardCharsets.UTF_8));
            if (artifact.hasMappings()) {
                stager.stage(sidecarPathOf(artifact.moduleId()),
                    jsSourceMapSidecar(artifact,
                        input.sourceFiles().get(artifact.moduleId()), stager)
                        .getBytes(StandardCharsets.UTF_8));
            }
        }

        // The complete JavaScript deployment copies: the runtime and every
        // spec-listed stdlib implementation are required deployment content,
        // so an unresolved copy is the pinned E6000 with nothing published.
        Optional<CompilerDiagnostic> deployment =
            stageDeploymentCopies(RUNTIME_JS, "js", checkoutResources, stager);
        if (deployment.isPresent()) {
            return new Result(Outcome.FAILED, List.of(deployment.get()), null,
                null);
        }
        return new Result(Outcome.EMITTED, List.of(), project,
            emission.entryArtifactRelativePath());
    }

    /**
     * The canonical rendering input of the JavaScript production arm over the
     * one lowered project: the project's own admitted companions (body
     * tables, class-factory registries, registration seeds and namespace
     * registrations), the one structural declaration projection built from
     * the shared declaration facts and class seeds, and one authoritative
     * source identity per implementation unit. The renderer receives no AST,
     * checker object, or default-expression AST: the declaration surface is
     * the structural projection, and the source identities are the checked
     * source facts projected through {@link SourceMapPaths} against the live
     * output root.
     *
     */
    private static JsEmissionInput jsEmissionInput(
            ExecutableLoweredProject project,
            SemanticLowerer.ProjectLoweringResult lowering,
            CheckedProjectInput checkedProject,
            HostDeclarationSurface declarationSurface,
            Map<ModuleId, CanonicalModuleIdentity> declarationModuleIdentities,
            PublicationStager stager) {
        Path root = stager.publicationRoot();
        Map<ModuleId, JsSourceFile> sourceFiles = new LinkedHashMap<>();
        for (CheckedModuleInput module : checkedProject.modules()) {
            if (module.kind() != CheckedModuleKind.IMPLEMENTATION) {
                continue;
            }
            sourceFiles.put(module.moduleId(), new JsSourceFile(
                module.sourceId(),
                SourceMapPaths.sourceDisplay(module.sourceId(), root)));
        }
        RuntimeDeclarationSurface declarations =
            RuntimeDeclarationProjection.project(declarationSurface,
                declarationModuleIdentities, lowering.seeds());
        return new JsEmissionInput(project, lowering.tables(),
            lowering.registries(), lowering.seeds(), lowering.namespaces(),
            declarations, sourceFiles, true);
    }

    /** The version-1 sidecar path of one implementation artifact. */
    private static String sidecarPathOf(ModuleId module) {
        return module.path().replace('.', '/') + ".deal.map.json";
    }

    /**
     * The one version-1 sidecar of one implementation artifact: the module's
     * own recorded coordinate list serialized with the existing
     * {@link SourceMapGenerator}, its {@code source} string exactly the
     * renderer's owner-supplied display name (so the emitted comment and the
     * sidecar never disagree) and its {@code generated} string the
     * {@link SourceMapPaths} live-root projection of the module's own
     * artifact — never a private-stage path.
     *
     */
    private static String jsSourceMapSidecar(JsModuleArtifact artifact,
            JsSourceFile sourceFile, PublicationStager stager) {
        SourceMapGenerator generator = new SourceMapGenerator();
        for (SourceMapGenerator.Mapping mapping : artifact.mappings()) {
            generator.emitStatement(mapping.generatedLine(),
                mapping.generatedColumn(), mapping.sourceLine(),
                mapping.sourceColumn());
        }
        Path root = stager.publicationRoot();
        String generatedDisplay = SourceMapPaths.generatedDisplay(root,
            root.resolve(artifact.artifactRelativePath()));
        return generator.toJson(sourceFile.displayName(), generatedDisplay);
    }

    /**
     * Stages the complete deployment copies of one deployed-runtime target in
     * the existing transaction: the runtime library and every spec-listed
     * stdlib implementation, resolved through the one
     * {@link CheckoutResources} lookup (project-local stdlib overrides,
     * otherwise source-checkout files). A
     * missing required copy yields the pinned E6000 and stages nothing
     * further; an unused optional resource gains no requirement.
     *
     */
    private static Optional<CompilerDiagnostic> stageDeploymentCopies(
            String runtimeFileName, String extension,
            CheckoutResources checkoutResources, PublicationStager stager)
            throws IOException {
        Optional<java.nio.file.Path> runtime =
            stager.stageRuntimeCopy(runtimeFileName, checkoutResources);
        if (runtime.isEmpty()) {
            return Optional.of(runtimeLibraryMissing(runtimeFileName));
        }
        for (String stdlibModule : StdlibModuleResolver.SPEC_STDLIB_MODULES) {
            String implementation = stdlibModule.substring("std/".length());
            if (stager.stageStdlibCopy(implementation, extension,
                    checkoutResources).isEmpty()) {
                return Optional.of(
                    stdlibLibraryMissing(implementation, extension));
            }
        }
        return Optional.empty();
    }

    /**
     * The one source-map recorder of a production run: one
     * {@link SourceMapGenerator} per constituent module with the shared
     * project-relative display source name ({@link SourceMapPaths}, the same
     * normalization the JavaScript orchestrator arm consumes), so the emitted
     * source comments and the staged sidecars carry one file spelling and a
     * single mapping authority owns the emission. The v1.4 default debugging
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
    /**
     * The importing declaration's own statement range: the module's recorded
     * import declaration whose alias and raw specifier are the resolved
     * import's, never a guessed coordinate or another import's span. A
     * producer defect that records no such declaration keeps the canonical
     * anchorless synthetic shape instead of inventing a coordinate.
     *
     */
    private static DiagnosticRange importStatementRange(
            CheckedModuleInput module, ResolvedImport importFact) {
        for (StatementNode statement : module.ast().statements()) {
            if (statement instanceof ImportDeclaration declaration
                    && declaration.alias().equals(importFact.alias())
                    && declaration.modulePath()
                        .equals(importFact.modulePath())) {
                return declaration.span().range();
            }
        }
        return DiagnosticRange.synthetic(module.sourceId());
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
        // ordinary genuinely originless signal keeps the canonical empty-file
        // synthetic range.
        DiagnosticRange carried = gap instanceof DiagnosticOriginCarrier carrier
            ? carrier.diagnosticRange() : null;

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
     * The pinned missing-runtime E6000 of the deployment copies of one
     * deployed-runtime target (unchanged text and shape: an anchorless
     * synthetic error with the runtime path note).
     */
    private static CompilerDiagnostic runtimeLibraryMissing(
            String runtimeFileName) {
        return CompilerDiagnostic.syntheticError(DiagnosticCode.E6000,
            "Runtime library not found: " + runtimeFileName, "",
            "missing anchor: runtime library path '" + runtimeFileName + "'");
    }
}
