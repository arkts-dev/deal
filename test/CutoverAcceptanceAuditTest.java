package deal.test;

import deal.diagnostics.CompilerDiagnostic;
import deal.module.CompilationOrchestrator;
import deal.module.ProductionProjectEmission;
import deal.project.CliOverrides;
import deal.project.ProjectLocator;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.SemanticIrDumper;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.test.conformance.ProducedProjectIdentityAudit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * ISSUE-0694: the cutover acceptance audits
 * ({@code conformance-cutover-guards-pins-and-production-source-removal}
 * HR4(b), the lane project identity audit contract, and
 * {@code luajit-jvm-single-lowering-production-cutover} C10's body-invocation
 * identity; {@code semantic-ir-construct-coverage-cutover} K12).
 *
 * <ol>
 *   <li><b>The produced-project accessor.</b> A release-owned production
 *       compile exposes the very project it lowered and emitted through
 *       {@link CompilationOrchestrator#producedProject()}; the reference is
 *       the artifact's project (the entry module of the project is the
 *       module the one project artifact is named for), the JS arm and a
 *       compile that fails before phase 4 record none, and no emission,
 *       staging, or comparison step reads it: in the production source set
 *       the token occurs only in the orchestrator (one writer, inside the
 *       production arm) and in the emission unit's result record, whose
 *       contract refuses a failed run carrying a project and an emitted run
 *       carrying none.</li>
 *   <li><b>The lane-level dangling-identity guard.</b> The corpus anchors —
 *       {@code equality/reference-identity-composite.deal} (the
 *       never-called declared function and the stored function value),
 *       {@code bytes/bytes-class-default-integration.deal} (the
 *       host-importing bytes closure), and the shape controls of item 3 —
 *       compile through the production arm,
 *       their projects carry the K12 body-local identity cell, and
 *       {@link ProducedProjectIdentityAudit} passes over them: every
 *       {@code RETURN}'s enclosing invocation identity and named return
 *       boundary resolve to emitted ops of the project closure and the
 *       body-local cells satisfy the closed rules.</li>
 *   <li><b>The guard's shape controls (the closed table's scoping).</b> The
 *       guard accepts every validator-approved shape the corpus carries and
 *       the closed table scopes its body-local cell rules accordingly: the
 *       sync {@code EXTERNAL_ENTRY}/{@code EXTERNAL_RETURN} boundary of an
 *       exported non-entry function, the multi-{@code RETURN} body whose
 *       single boundary is parented to the body's first {@code RETURN}, and
 *       the awaited operation's {@code ASYNC_START} identity each compile
 *       through the production arm with their shapes asserted present, so
 *       the controls cannot pass vacuously.</li>
 *   <li><b>The guard's negative controls (not vacuous).</b> The named
 *       regressions fail the audit by name: a never-materialized reserved
 *       identity (a restored never-called guard, or a reserved identity
 *       left dangling) reports the module path, the function, and the
 *       unresolved op id under {@code ENCLOSING_INVOCATION_NOT_EMITTED}; a
 *       dropped return boundary and a duplicated identity op report their
 *       own rules by name.</li>
 * </ol>
 *
 * <p>The drive is read-only over the repository: every fixture is
 * materialized lane-equivalently (the header-stripped compilation set, the
 * host declaration file, and one generated {@code deal.json}) in a temp
 * project deleted on completion; nothing is staged into the repository.</p>
 */
public class CutoverAcceptanceAuditTest {

    private static final Path CONFORMANCE_ROOT =
        Path.of("test", "conformance", "backend-runtime");

    /** The K12 anchor: the never-called declared function and the stored value. */
    private static final String EQUALITY_ANCHOR =
        "equality/reference-identity-composite.deal";

    /** The bytes anchor: the host-importing bytes-carrying class closure. */
    private static final String BYTES_ANCHOR =
        "bytes/bytes-class-default-integration.deal";

    /**
     * The non-body-local boundary anchor: an exported non-entry function
     * whose {@code RETURN}'s enclosing invocation is the sync
     * {@code EXTERNAL_ENTRY} and whose named boundary is therefore an
     * {@code EXTERNAL_RETURN} — the shape the validator's closed table
     * scopes outside the body-local cell, so the guard must accept it.
     */
    private static final String EXTERNAL_ENTRY_ANCHOR =
        "type-system/dynamic-return-e8001.deal";

    /**
     * The multi-RETURN anchor: a body that emits one return boundary but
     * carries more than one {@code RETURN}, each naming that boundary — the
     * shape whose boundary is parented only to the body's first
     * {@code RETURN}.
     */
    private static final String MULTI_RETURN_ANCHOR =
        "bytes/bytes-nested-fn-shapes.deal";

    /** The async anchor: an awaited operation's ASYNC_START identity shape. */
    private static final String ASYNC_ANCHOR =
        "async-await/async-await-statement.deal";

    /** The host declaration of the bytes anchor's host import. */
    private static final Path BYTES_ANCHOR_HOST_DECLARATION =
        Path.of("test", "conformance", "host-fixtures", "bytes_roundtrip.d.deal");

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            fail(message);
        }
    }

    private static void checkEq(Object expected, Object actual, String message) {
        if (java.util.Objects.equals(expected, actual)) {
            passed++;
        } else {
            fail(message + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void fail(String message) {
        failed++;
        System.out.println("FAIL: " + message);
    }

    // =========================================================================
    // 1. The produced-project accessor
    // =========================================================================

    private static void testProducedProjectAccessor() throws Exception {
        System.out.println("-- the produced-project accessor: a production "
            + "compile exposes the project it emitted --");
        Path project = Files.createTempDirectory("cutover-audit-accessor-");
        try {
            Path entry = materializeAnchor(project, EQUALITY_ANCHOR, null);

            ArmCompile production = compile(project, entry, productionInvocation());
            check(production.success(),
                "the anchor compiles through the production arm: "
                    + production.orchestrator().diagnostics());
            if (!production.success()) {
                return;
            }
            checkEq(1, production.orchestrator().semanticEmissionCount(),
                "the production compile records one project emission");
            checkEq(0, production.orchestrator().retainedEmissionCount(),
                "the production compile records no retained emission");
            ExecutableLoweredProject produced =
                production.orchestrator().producedProject();
            check(produced != null,
                "the production compile exposes the produced project");
            if (produced == null) {
                return;
            }
            checkEq(SemanticProfile.DEAL_V1_2_INT32, produced.semanticProfile(),
                "the produced project is the v1.2 profile");
            check(produced.modules().containsKey(produced.entryModule()),
                "the produced project carries its entry module");
            check(produced.modules().size() >= 1,
                "the produced project closure is non-empty");
            String artifactRelativePath = produced.entryModule().path()
                .replace('.', '/') + ".lua";
            Path artifact = project.resolve("out").resolve(artifactRelativePath);
            check(Files.isRegularFile(artifact),
                "the one project artifact is the produced project's entry "
                    + "artifact: " + artifact);
            if (Files.isRegularFile(artifact)) {
                String chunk = Files.readString(artifact, StandardCharsets.UTF_8);
                check(chunk.contains("__exportSurfaces[\""
                        + produced.entryModule().path() + "\"]"),
                    "the staged artifact carries the produced project's entry "
                        + "surface");
            }
            check(ProducedProjectIdentityAudit.audit(produced).isEmpty(),
                "the produced project passes the identity audit: "
                    + ProducedProjectIdentityAudit.audit(produced));
        } finally {
            deleteRecursively(project);
        }

        // The accessor is set by the production arm only: the JS arm (the
        // third, unchanged pipeline) records no project, and a compile that
        // fails before phase 4 (here a frontend parse error) records none
        // either, because no arm produced a project.
        Path jsProject = Files.createTempDirectory("cutover-audit-js-");
        try {
            Path entry = materializeAnchor(jsProject, EQUALITY_ANCHOR, null);
            Files.writeString(jsProject.resolve("deal.json"),
                dealJson("js", ""), StandardCharsets.UTF_8);
            ArmCompile js = compile(jsProject, entry, productionInvocation());
            check(js.orchestrator().producedProject() == null,
                "the JS arm records no produced project");
        } finally {
            deleteRecursively(jsProject);
        }

        Path broken = Files.createTempDirectory("cutover-audit-broken-");
        try {
            write(broken, "deal.json", dealJson("luajit", ""));
            write(broken, "src/main.deal",
                "export function main(: null { return null }\n");
            Path entry = broken.resolve("src/main.deal").toAbsolutePath()
                .normalize();
            ArmCompile compile = compile(broken, entry, productionInvocation());
            check(!compile.success(),
                "the parse-error fixture fails the compile before phase 4");
            check(compile.orchestrator().producedProject() == null,
                "a compile that runs no production arm records no project");
            checkEq(0, compile.orchestrator().semanticEmissionCount(),
                "no arm records a project emission for the failing compile");

            // The result contract: a failed run carries no produced project
            // and an emitted run carries one, so the reference can never be
            // observed for a run that produced nothing.
            if (!compile.orchestrator().diagnostics().isEmpty()) {
                ExecutableLoweredProject anchor = productionProject(
                    EQUALITY_ANCHOR, "cutover-audit-contract-", null);
                if (anchor != null) {
                    expectResultRejected(anchor, compile.orchestrator()
                        .diagnostics().get(0));
                }
            }
        } finally {
            deleteRecursively(broken);
        }
    }

    /**
     * The result contract's fail-closed halves: a failed run carrying a
     * produced project, and an emitted run carrying none, are both refused.
     */
    private static void expectResultRejected(ExecutableLoweredProject project,
            CompilerDiagnostic diagnostic) {
        boolean failedRefused = false;
        try {
            new ProductionProjectEmission.Result(
                ProductionProjectEmission.Outcome.FAILED,
                List.of(diagnostic), project, null);
        } catch (IllegalArgumentException expected) {
            failedRefused = true;
        }
        check(failedRefused,
            "a failed production run refuses a produced-project reference");
        boolean emittedRefused = false;
        try {
            new ProductionProjectEmission.Result(
                ProductionProjectEmission.Outcome.EMITTED,
                List.of(), null, "main.lua");
        } catch (IllegalArgumentException expected) {
            emittedRefused = true;
        }
        check(emittedRefused,
            "an emitted production run refuses a missing produced-project "
                + "reference");
    }

    private static void testProducedProjectAccessorIsReadOnly() throws Exception {
        System.out.println("-- the accessor is read-only: one writer inside the "
            + "production arm, no emission/staging/comparison reader --");
        Path root = repoRoot();
        check(root != null, "the repository root is located");
        if (root == null) {
            return;
        }
        List<Path> productionSources = javaSources(root.resolve("deal"));
        check(!productionSources.isEmpty(),
            "the production source surface is real and non-empty");
        for (Path file : productionSources) {
            String name = file.getFileName().toString();
            boolean expected = name.equals("CompilationOrchestrator.java")
                || name.equals("ProductionProjectEmission.java");
            int occurrences = countOccurrences(
                Files.readString(file, StandardCharsets.UTF_8), "producedProject");
            if (expected) {
                check(occurrences > 0,
                    "the accessor surface is declared in " + name);
            } else {
                checkEq(0, occurrences,
                    "no emission, staging, or comparison source mentions the "
                        + "produced-project reference (" + file + ")");
            }
        }
        String orchestrator = Files.readString(
            root.resolve("deal/module/CompilationOrchestrator.java"),
            StandardCharsets.UTF_8);
        checkEq(1,
            countOccurrences(orchestrator, "producedProject = Objects.requireNonNull("),
            "the orchestrator assigns the reference at exactly one writer");
        checkEq(2, countOccurrences(orchestrator, "recordProducedProject("),
            "the writer helper has one declaration and one call site");
        String arm = methodBody(orchestrator, "private void emitProductionProject()");
        check(arm != null,
            "the production arm method is located");
        if (arm != null) {
            check(arm.contains("recordProducedProject(result.producedProject());"),
                "the production arm records the produced project it received");
        }
        String writer = methodBody(orchestrator,
            "private void recordProducedProject(");
        check(writer != null, "the writer helper is located");
        if (writer != null) {
            check(writer.contains("if (producedProject != null)"),
                "the writer refuses a second record (set exactly once)");
            check(writer.contains("producedProject = Objects.requireNonNull("),
                "the writer is the reference's only assignment");
            check(writer.contains("IllegalStateException"),
                "a second record fails closed as a producer defect");
        }
        String emission = Files.readString(
            root.resolve("deal/module/ProductionProjectEmission.java"),
            StandardCharsets.UTF_8);
        check(emission.contains("ExecutableLoweredProject producedProject,"),
            "the production arm's result carries the read-only project "
                + "reference");
        checkEq(0, countOccurrences(emission, ".producedProject()"),
            "no emission or staging step of the production unit reads the "
                + "reference back");
    }

    // =========================================================================
    // 2. The lane-level dangling-identity guard over the corpus anchors
    // =========================================================================

    private static void testIdentityGuardOverAnchors() throws Exception {
        System.out.println("-- the lane-level dangling-identity guard over the "
            + "corpus anchors --");
        ExecutableLoweredProject equality =
            productionProject(EQUALITY_ANCHOR, "cutover-audit-equality-", null);
        if (equality != null) {
            List<ProducedProjectIdentityAudit.Violation> violations =
                ProducedProjectIdentityAudit.audit(equality);
            check(violations.isEmpty(),
                "the equality anchor's project passes the identity audit: "
                    + violations);
            BodyLocalCell cell = firstBodyLocalCell(equality);
            check(cell != null,
                "the equality anchor carries a body-local identity cell (the "
                    + "K12 shape the anchor pins)");
            if (cell != null) {
                check(equality.modules().get(cell.unit().moduleId()).functions()
                        .containsKey(cell.function()),
                    "the body-local cell belongs to a lowered body of the "
                        + "project closure");
                String before = SemanticIrDumper.dumpModuleText(cell.unit());
                ProducedProjectIdentityAudit.audit(equality);
                checkEq(before,
                    SemanticIrDumper.dumpModuleText(cell.unit()),
                    "the audit leaves the produced unit byte-identical "
                        + "(read-only)");
            }
        }

        ExecutableLoweredProject bytes = productionProject(BYTES_ANCHOR,
            "cutover-audit-bytes-", "host/bytes_roundtrip");
        if (bytes != null) {
            List<ProducedProjectIdentityAudit.Violation> violations =
                ProducedProjectIdentityAudit.audit(bytes);
            check(violations.isEmpty(),
                "the bytes anchor's project passes the identity audit: "
                    + violations);
            check(bytes.modules().values().stream()
                    .anyMatch(unit -> !unit.classLayouts().isEmpty()),
                "the bytes anchor's project carries the class-construction "
                    + "layouts the anchor exercises");
        }

        // The non-body-local boundary shape (the guard's scoping control):
        // an exported non-entry function whose RETURN is enclosed by the
        // sync EXTERNAL_ENTRY names an EXTERNAL_RETURN boundary. The closed
        // table scopes the FUNCTION_RETURN/parent cell rules to the
        // body-local cell, so the guard accepts this project — and the
        // assertion below pins that the shape is really present, so the
        // control cannot pass vacuously.
        ExecutableLoweredProject externalEntry = productionProject(
            EXTERNAL_ENTRY_ANCHOR, "cutover-audit-external-entry-", null);
        if (externalEntry != null) {
            List<ReturnShape> shapes = returnShapes(externalEntry);
            check(shapes.stream().anyMatch(shape ->
                    shape.identityKind().equals("EXTERNAL_ENTRY")
                        && shape.boundaryKind().equals("EXTERNAL_RETURN")),
                "the external-entry anchor carries the EXTERNAL_ENTRY/"
                    + "EXTERNAL_RETURN boundary shape: " + shapes);
            List<ProducedProjectIdentityAudit.Violation> violations =
                ProducedProjectIdentityAudit.audit(externalEntry);
            check(violations.isEmpty(),
                "the guard accepts the non-body-local boundary shape (the "
                    + "closed table scopes the FUNCTION_RETURN parent rule to "
                    + "the body-local cell): " + violations);
        }

        // The multi-RETURN body shape (the guard's parent scoping control):
        // a body that emits one return boundary but more than one RETURN
        // parents the boundary to its first RETURN, while every RETURN of
        // the body names it. The parent rule is asserted against the parent
        // RETURN, so the guard accepts the project.
        ExecutableLoweredProject multiReturn = productionProject(
            MULTI_RETURN_ANCHOR, "cutover-audit-multi-return-", null);
        if (multiReturn != null) {
            List<ReturnShape> shapes = returnShapes(multiReturn);
            check(shapes.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                        ReturnShape::function,
                        java.util.stream.Collectors.counting()))
                    .values().stream().anyMatch(count -> count > 1),
                "the multi-return anchor carries a body with more than one "
                    + "RETURN naming its single boundary: " + shapes);
            List<ProducedProjectIdentityAudit.Violation> violations =
                ProducedProjectIdentityAudit.audit(multiReturn);
            check(violations.isEmpty(),
                "the guard accepts a multi-RETURN body's single boundary "
                    + "parented to the body's first RETURN: " + violations);
        }

        // The async identity shape: an awaited operation's RETURN is
        // enclosed by the ASYNC_START identity; its boundary rules belong to
        // the async cell, not to the body-local cell.
        ExecutableLoweredProject async = productionProject(ASYNC_ANCHOR,
            "cutover-audit-async-", null);
        if (async != null) {
            List<ReturnShape> shapes = returnShapes(async);
            check(shapes.stream()
                    .anyMatch(shape -> shape.identityKind().equals("ASYNC_START")),
                "the async anchor carries the ASYNC_START identity shape: "
                    + shapes);
            List<ProducedProjectIdentityAudit.Violation> violations =
                ProducedProjectIdentityAudit.audit(async);
            check(violations.isEmpty(),
                "the guard accepts the async identity shape: " + violations);
        }
    }

    // =========================================================================
    // 3. The guard's negative controls: the named regressions
    // =========================================================================

    private static void testIdentityGuardRegressions() throws Exception {
        System.out.println("-- the guard's negative controls: the named "
            + "regressions fail by name --");
        ExecutableLoweredProject project =
            productionProject(EQUALITY_ANCHOR, "cutover-audit-regressions-", null);
        if (project == null) {
            return;
        }
        BodyLocalCell cell = firstBodyLocalCell(project);
        check(cell != null, "the anchor project carries the seedable cell");
        if (cell == null) {
            return;
        }

        // (a) The materializing op is never emitted: a restored
        // never-called guard, or a reserved identity left dangling.
        LoweredModuleUnit dangling = withOps(cell.unit(),
            withoutOp(cell.unit(), cell.identity()));
        ExecutableLoweredProject danglingProject = withUnit(project, dangling);
        Optional<ProducedProjectIdentityAudit.Violation> first =
            ProducedProjectIdentityAudit.firstViolation(danglingProject);
        check(first.isPresent(),
            "the dangling reserved identity fails the audit");
        if (first.isPresent()) {
            checkEq(ProducedProjectIdentityAudit.ENCLOSING_INVOCATION_NOT_EMITTED,
                first.get().rule(),
                "the dangling identity names its rule");
            checkEq(cell.unit().moduleId().path(), first.get().modulePath(),
                "the dangling identity names its module path");
            checkEq(cell.function().toString(), first.get().function(),
                "the dangling identity names its function");
            checkEq(cell.identity().toString(), first.get().opId(),
                "the dangling identity names the unresolved op id");
            check(first.get().describe().contains(cell.unit().moduleId().path())
                    && first.get().describe().contains(cell.function().toString())
                    && first.get().describe().contains(cell.identity().toString()),
                "the audit failure text carries the module path, the function, "
                    + "and the unresolved op id: " + first.get().describe());
        }

        // (b) The return boundary is never emitted.
        LoweredModuleUnit boundaryless = withOps(cell.unit(),
            withoutOp(cell.unit(), cell.boundary()));
        ExecutableLoweredProject boundarylessProject =
            withUnit(project, boundaryless);
        Optional<ProducedProjectIdentityAudit.Violation> missingBoundary =
            ProducedProjectIdentityAudit.firstViolation(boundarylessProject);
        check(missingBoundary.isPresent()
                && missingBoundary.get().rule()
                    .equals(ProducedProjectIdentityAudit.RETURN_BOUNDARY_NOT_EMITTED),
            "a dropped return boundary fails the audit by name: "
                + missingBoundary);

        // (c) The identity is emitted twice: the exactly-once materialization.
        LoweredModuleUnit duplicated = withOps(cell.unit(),
            withOpDuplicated(cell.unit(), cell.identity()));
        ExecutableLoweredProject duplicatedProject = withUnit(project, duplicated);
        Optional<ProducedProjectIdentityAudit.Violation> doubleIdentity =
            ProducedProjectIdentityAudit.firstViolation(duplicatedProject);
        check(doubleIdentity.isPresent()
                && doubleIdentity.get().rule()
                    .equals(ProducedProjectIdentityAudit.ENCLOSING_INVOCATION_DUPLICATED),
            "a twice-materialized identity fails the audit by name: "
                + doubleIdentity);
    }

    // =========================================================================
    // The production-arm drive and the anchor materialization
    // =========================================================================

    /** One orchestrator drive: the orchestrator plus its compile outcome. */
    private record ArmCompile(CompilationOrchestrator orchestrator,
                              boolean success) {
    }

    private static ArmCompile compile(Path project, Path entry,
            CompilerInvocation invocation) throws Exception {
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            entry.toAbsolutePath().normalize().toString(),
            new CliOverrides(null, null));
        if (located.context() == null) {
            throw new IllegalStateException("locate failed: " + located);
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), entry, false, false, false, false, null,
            invocation);
        boolean success = orchestrator.compile();
        return new ArmCompile(orchestrator, success);
    }

    /**
     * Materializes one corpus anchor lane-equivalently (its header-stripped
     * source as the entry module, its host declaration under
     * {@code bindings/} when it imports one, and one generated
     * {@code deal.json}) and returns the produced project, or {@code null}
     * when the compile fails (the failure is reported).
     */
    private static ExecutableLoweredProject productionProject(String anchor,
            String tempPrefix, String hostImport) throws Exception {
        Path project = Files.createTempDirectory(tempPrefix);
        try {
            Path entry = materializeAnchor(project, anchor, hostImport);
            ArmCompile compile = compile(project, entry, productionInvocation());
            check(compile.success(),
                anchor + ": the anchor compiles through the production arm: "
                    + compile.orchestrator().diagnostics());
            ExecutableLoweredProject produced =
                compile.orchestrator().producedProject();
            check(produced != null && compile.success(),
                anchor + ": the production compile exposes its project");
            return compile.success() ? produced : null;
        } finally {
            deleteRecursively(project);
        }
    }

    /**
     * Writes one anchor's lane-equivalent temp project: the header-stripped
     * fixture as {@code src/main.deal}, the host declaration file under
     * {@code bindings/} when the anchor imports a host module, and the
     * generated {@code deal.json}.
     */
    private static Path materializeAnchor(Path project, String anchor,
            String hostImport) throws Exception {
        String raw = Files.readString(CONFORMANCE_ROOT.resolve(anchor),
            StandardCharsets.UTF_8);
        write(project, "src/main.deal",
            ConformanceHarnessMetadata.stripClassificationHeaders(raw));
        String externals = "";
        if (hostImport != null) {
            String hostName = hostImport.substring("host/".length());
            String declaration = Files.readString(
                BYTES_ANCHOR_HOST_DECLARATION, StandardCharsets.UTF_8);
            write(project, "bindings/" + hostName + ".d.deal",
                ConformanceHarnessMetadata.stripClassificationHeaders(declaration));
            externals = ",\n  \"externals\": {\n    \"" + hostImport
                + "\": { \"declaration\": \"bindings/" + hostName
                + ".d.deal\" }\n  }";
        }
        write(project, "deal.json", dealJson("luajit", externals));
        return project.resolve("src/main.deal").toAbsolutePath().normalize();
    }

    private static String dealJson(String backend, String externals) {
        return "{\n  \"languageVersion\": \"1.2\",\n"
            + "  \"moduleRoots\": [\"src\"],\n"
            + "  \"output\": \"out\",\n"
            + "  \"backend\": \"" + backend + "\"" + externals + "\n}\n";
    }

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    // =========================================================================
    // The K12 cell, the seed builders, and the source helpers
    // =========================================================================

    /** One body-local identity cell of a produced project. */
    private record BodyLocalCell(LoweredModuleUnit unit, FunctionId function,
                                 OpId identity, OpId boundary) {
    }

    /**
     * One {@code RETURN}'s shape: the function, the kind of the op its
     * {@code enclosingInvocationOpId} resolves to, and the kind of the
     * boundary its {@code returnBoundaryOpId} resolves to.
     */
    private record ReturnShape(FunctionId function, String identityKind,
                               String boundaryKind) {
    }

    /** Every {@code RETURN} shape of the project closure, in op order. */
    private static List<ReturnShape> returnShapes(
            ExecutableLoweredProject project) {
        List<ReturnShape> shapes = new ArrayList<>();
        Map<OpId, SemanticOp> byId = new LinkedHashMap<>();
        for (LoweredModuleUnit unit : project.modules().values()) {
            for (SemanticOp op : unit.ops()) {
                byId.putIfAbsent(op.opId(), op);
            }
        }
        for (LoweredModuleUnit unit : project.modules().values()) {
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.RETURN
                        || !(op.payload()
                            instanceof KindPayload.ReturnPayload payload)) {
                    continue;
                }
                SemanticOp identity = byId.get(payload.enclosingInvocationOpId());
                SemanticOp boundary = byId.get(payload.returnBoundaryOpId());
                String boundaryKind = "NONE";
                if (boundary != null && boundary.kind() == SemanticOpKind.BOUNDARY
                        && boundary.payload()
                            instanceof KindPayload.BoundaryPayload bp) {
                    boundaryKind = bp.kind().name();
                } else if (boundary != null) {
                    boundaryKind = boundary.kind().name();
                }
                shapes.add(new ReturnShape(payload.function(),
                    identity == null ? "NONE" : identity.kind().name(),
                    boundaryKind));
            }
        }
        return shapes;
    }

    /**
     * The first body-local identity cell of the project: a {@code RETURN}
     * of a lowered body whose enclosing invocation identity resolves to the
     * body's function-value creation op ({@code CLOSURE_NEW} or
     * {@code RECURSIVE_GROUP_INIT}) — the K12 shape of a body no
     * statically materialized invocation took over.
     */
    private static BodyLocalCell firstBodyLocalCell(
            ExecutableLoweredProject project) {
        for (LoweredModuleUnit unit : project.modules().values()) {
            Map<OpId, SemanticOp> byId = opsById(unit);
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.RETURN
                        || !(op.payload()
                            instanceof KindPayload.ReturnPayload payload)
                        || !unit.functions().containsKey(payload.function())) {
                    continue;
                }
                SemanticOp identity = byId.get(payload.enclosingInvocationOpId());
                if (identity != null
                        && (identity.kind() == SemanticOpKind.CLOSURE_NEW
                            || identity.kind() == SemanticOpKind.RECURSIVE_GROUP_INIT)) {
                    return new BodyLocalCell(unit, payload.function(),
                        identity.opId(), payload.returnBoundaryOpId());
                }
            }
        }
        return null;
    }

    private static Map<OpId, SemanticOp> opsById(LoweredModuleUnit unit) {
        Map<OpId, SemanticOp> byId = new LinkedHashMap<>();
        for (SemanticOp op : unit.ops()) {
            byId.putIfAbsent(op.opId(), op);
        }
        return byId;
    }

    /** The unit's ops without the given one (the seed surface). */
    private static List<SemanticOp> withoutOp(LoweredModuleUnit unit, OpId opId) {
        List<SemanticOp> ops = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            if (!op.opId().equals(opId)) {
                ops.add(op);
            }
        }
        return ops;
    }

    /** The unit's ops with the given one emitted a second time. */
    private static List<SemanticOp> withOpDuplicated(LoweredModuleUnit unit,
            OpId opId) {
        List<SemanticOp> ops = new ArrayList<>(unit.ops());
        for (SemanticOp op : unit.ops()) {
            if (op.opId().equals(opId)) {
                ops.add(op);
                break;
            }
        }
        return ops;
    }

    /** One lowered unit with the given op list (the seed surface). */
    private static LoweredModuleUnit withOps(LoweredModuleUnit unit,
                                             List<SemanticOp> ops) {
        return new LoweredModuleUnit(unit.formatVersion(), unit.semanticProfile(),
            unit.moduleId(), unit.interfaceHash(), unit.loweringContextHash(),
            unit.requiredCapabilities(), unit.constructCoverage(),
            unit.classLayouts(), unit.functions(), unit.moduleInit(),
            unit.exportPlan(), unit.functionBindings(), ops);
    }

    /** The project with one unit replaced (the seed surface). */
    private static ExecutableLoweredProject withUnit(
            ExecutableLoweredProject project, LoweredModuleUnit unit) {
        Map<ModuleId, LoweredModuleUnit> modules =
            new LinkedHashMap<>(project.modules());
        modules.put(unit.moduleId(), unit);
        return new ExecutableLoweredProject(project.semanticProfile(),
            project.interfaceIndex(), modules, project.entryModule());
    }

    private static void write(Path root, String relative, String content)
            throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static Path repoRoot() {
        Path root = Path.of(".").toAbsolutePath().normalize();
        return Files.isDirectory(root.resolve("deal"))
            && Files.isRegularFile(root.resolve("tools/gate-manifest.sh"))
            ? root : null;
    }

    private static List<Path> javaSources(Path root) throws Exception {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> listing = Files.walk(root)) {
            return listing.filter(path -> path.getFileName().toString()
                    .endsWith(".java"))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    /** The body of the method with the given signature, or {@code null}. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            return null;
        }
        int open = source.indexOf('{', start);
        if (open < 0) {
            return null;
        }
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        return null;
    }

    private static void deleteRecursively(Path path) {
        if (path == null) {
            return;
        }
        try {
            if (Files.exists(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(entry -> {
                        try {
                            Files.deleteIfExists(entry);
                        } catch (java.io.IOException ignored) {
                            // best effort
                        }
                    });
                }
            }
        } catch (java.io.IOException ignored) {
            // best effort
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Cutover Acceptance Audit Tests (ISSUE-0694) ===\n");
        testProducedProjectAccessor();
        testProducedProjectAccessorIsReadOnly();
        testIdentityGuardOverAnchors();
        testIdentityGuardRegressions();
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== Cutover Acceptance Audit Tests Passed ===");
    }
}
