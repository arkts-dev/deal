package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.diagnostics.CompilerDiagnostic;
import deal.distribution.DistributionHome;
import deal.module.CompilationOrchestrator;
import deal.module.ProductionProjectEmission;
import deal.publication.PublicationStager;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.ir.IntrinsicKind;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The isolated-environment entry of the JavaScript production arm (the
 * deployment-copy negatives of ISSUE-0803): one process whose working
 * directory and distribution surface are supplied by the caller, so the
 * resolved-runtime/stdlib behaviour of the one production arm can be observed
 * where the checkout development fallback does not apply.
 *
 * <p>Usage: {@code <projectRoot> <fixtureOut> <armOut>}. The fixture project
 * is compiled through the retained LuaJIT arm for its checked facts only (the
 * fixture compile's own phase 4 may fail on the same missing deployment
 * content), then the JavaScript arm runs into its own publication root. The
 * probe prints one {@code RESULT} line ({@code emitted} or {@code failed}),
 * one {@code DIAG} line per diagnostic ({@code code|severity|message|notes}),
 * and one {@code STAGED} line per staged path; a published run prints
 * {@code PUBLISHED}.</p>
 */
public final class ProductionArmJsProbe {

    private ProductionArmJsProbe() {
        // Static entry only; no instances.
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: <projectRoot> <fixtureOut> <armOut>");
            System.exit(2);
        }
        Path projectRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path fixtureOut = Path.of(args[1]).toAbsolutePath().normalize();
        Path armOut = Path.of(args[2]).toAbsolutePath().normalize();
        Path sourceDir = projectRoot.resolve("src");

        CompilationOrchestrator fixture = new CompilationOrchestrator(
            sourceDir.resolve("app.deal"), fixtureOut, false, false, false,
            false, Backend.LUAJIT, Map.of(),
            List.of(sourceDir.toAbsolutePath()),
            Path.of(".").toAbsolutePath().normalize(), null,
            productionInvocation());
        fixture.compile();
        CheckedProjectBuildResult built = fixture.checkedProject();
        RequirementManifestResult manifests = fixture.requirementManifests();
        if (built == null || built.input() == null || built.index() == null
                || manifests == null || manifests.manifests() == null
                || fixture.hostDeclarationSurface() == null) {
            System.err.println("the fixture project produced no checked facts: "
                + (built == null ? "no checked project"
                    : String.valueOf(built.diagnostics())));
            System.exit(2);
        }

        PublicationStager stager = PublicationStager.forRoot(armOut);
        try {
            ProductionProjectEmission.Result result =
                ProductionProjectEmission.run(productionInvocation(),
                    built.input(), built.index(), manifests.manifests(),
                    fixture.hostDeclarationSurface(), Map.of(), Map.of(),
                    projectRoot.toString(),
                    BuiltinErrorDeclaration.synthesized(
                        built.input().modules().get(0).ast().span()),
                    List.of(IntrinsicKind.INT_CONVERT,
                        IntrinsicKind.NUMBER_CONVERT, IntrinsicKind.BYTES_NEW),
                    Set.of(), Backend.JS, false,
                    DistributionHome.forManifestDirectory(projectRoot.toString()),
                    stager);
            System.out.println("RESULT "
                + (result.emitted() ? "emitted" : "failed"));
            for (CompilerDiagnostic diagnostic : result.diagnostics()) {
                System.out.println("DIAG " + diagnostic.code() + "|"
                    + diagnostic.severity() + "|" + diagnostic.message() + "|"
                    + diagnostic.notes().stream()
                        .map(note -> note.message())
                        .collect(java.util.stream.Collectors.joining(";")));
            }
            for (String path : stager.stagedSet().relativePaths()) {
                System.out.println("STAGED " + path);
            }
            if (result.emitted()) {
                stager.publish();
                System.out.println("PUBLISHED");
            }
        } finally {
            stager.discard();
        }
    }

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }
}
