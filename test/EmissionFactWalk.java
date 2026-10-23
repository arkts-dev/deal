package deal.semantic;

import deal.module.CompilationOrchestrator;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ResolvedImport;
import deal.semantic.ir.SemanticIdAllocator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The production per-module walk's shape-map emission facts over one module
 * of a production compile (the ISSUE-0877 emission-fact seam).
 *
 * <p>The project entry's own per-module walk activates the E7 call machine
 * through a package-private {@code ModuleLowerer} setter, so a test outside
 * this package cannot construct the identical walk. This class runs exactly
 * that walk — the same construction order, the same class-core arguments and
 * the same {@code E9} container seam the project entry uses, with the
 * creation-rule/shape-map analysis facts enabled so the walk's
 * {@link SemanticLowerer.ShapeMapFacts} surface carries its records — and
 * returns the produced unit beside those facts. It adds no alternate
 * lowering, no test-only semantics and no production authority: a failure is
 * returned as the walk's own diagnostics.</p>
 */
public final class EmissionFactWalk {

    /** One module walk's produced unit, its emission facts, and its failures. */
    public record Walk(LoweredModuleUnit unit,
                       SemanticLowerer.ShapeMapFacts facts,
                       List<String> diagnostics) {

        public Walk {
            diagnostics = List.copyOf(diagnostics);
        }

        /** True exactly when the walk produced a unit and reported no failure. */
        public boolean succeeded() {
            return unit != null && diagnostics.isEmpty();
        }
    }

    private EmissionFactWalk() {
    }

    /**
     * The one production walk over the given implementation module of the
     * compile's checked project. A module absent from the checked project, a
     * missing requirement manifest, or a walk failure returns a {@code Walk}
     * with no unit and the explaining diagnostics.
     */
    public static Walk walk(CompilationOrchestrator orchestrator, String modulePath) {
        List<String> diagnostics = new ArrayList<>();
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        if (built == null || built.hasErrors() || built.input() == null
                || built.index() == null) {
            diagnostics.add("the compile produced no checked project: "
                + (built == null ? "null" : built.diagnostics()));
            return new Walk(null, SemanticLowerer.ShapeMapFacts.empty(), diagnostics);
        }
        if (manifests == null || manifests.hasErrors() || manifests.manifests() == null) {
            diagnostics.add("the compile produced no requirement manifest: "
                + (manifests == null ? "null" : manifests.diagnostics()));
            return new Walk(null, SemanticLowerer.ShapeMapFacts.empty(), diagnostics);
        }
        CheckedModuleInput module = null;
        for (CheckedModuleInput candidate : built.input().modules()) {
            if (candidate.moduleId().path().equals(modulePath)) {
                module = candidate;
            }
        }
        if (module == null) {
            diagnostics.add("the checked project carries no module '" + modulePath + "'");
            return new Walk(null, SemanticLowerer.ShapeMapFacts.empty(), diagnostics);
        }
        SemanticRequirementManifest manifest = null;
        for (SemanticRequirementManifest candidate : manifests.manifests()) {
            if (candidate.moduleId().equals(module.moduleId())) {
                manifest = candidate;
            }
        }
        if (manifest == null) {
            diagnostics.add("the compile carries no requirement manifest for module '"
                + modulePath + "'");
            return new Walk(null, SemanticLowerer.ShapeMapFacts.empty(), diagnostics);
        }
        // The same full-program walk the project entry runs, with the
        // creation-rule/shape-map analysis facts enabled: the emission fact
        // surface exists exactly in that mode (`shapeMapFacts()` is empty
        // outside it), and the walk's lowering decisions are the production
        // ones either way.
        SemanticLowerer.ModuleLowerer lowerer = new SemanticLowerer.ModuleLowerer(
            module.moduleId(), module.sourceId(), module.checks(),
            SemanticIdAllocator.over(List.of(module.moduleId())),
            true, true, true, true, true, true, module.ast().span(),
            true, true, built.index().modules().get(module.moduleId()), Map.of());
        lowerer.setModuleImports(module.imports());
        lowerer.setRegistrationSeeds(ClassRegistrationSeeds.builtinErrorOnly());
        lowerer.setDeclaredConversionIntrinsics(List.of(IntrinsicKind.INT_CONVERT,
            IntrinsicKind.NUMBER_CONVERT, IntrinsicKind.BYTES_NEW));
        lowerer.setE7Facts(module.exports(), Map.of(), Set.of());
        try {
            lowerer.lowerProjectModule(module.ast().statements());
        } catch (RuntimeException defect) {
            diagnostics.add("the walk failed: " + defect);
            return new Walk(null, lowerer.shapeMapFacts(), diagnostics);
        }
        LoweredModuleUnit unit = lowerer.buildUnit(manifest.constructCoverage(),
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            orchestrator.invocation().semanticProfile(),
            built.index().interfaceIndexDigest(),
            orchestrator.invocation().capabilityRegistryHash(),
            ContainerClaimingSeam.E9_GATE_ACTIVATION);
        return new Walk(unit, lowerer.shapeMapFacts(), diagnostics);
    }
}
