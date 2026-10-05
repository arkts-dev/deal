package deal.test;

import deal.diagnostics.CompilerDiagnostic;
import deal.module.CompilationOrchestrator;
import deal.module.ProductionProjectEmission;
import deal.project.CliOverrides;
import deal.project.ProjectLocator;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.CallMode;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClosedSelector;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.OperationContractSnapshot;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticIrDumper;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.ValueId;
import deal.test.conformance.JvmLane;
import deal.test.conformance.LuaLane;
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

    /**
     * The closed body-local cell's remaining clauses (K12; the validator's
     * {@code bodyLocalReturnCell}): each omitted clause has its own named
     * negative control — the descriptor-kind failure policy, the declared
     * return descriptor, and the absence of an assigned static invocation
     * shape — so the audit can never report a closed-rule violation as
     * passing (the reproduction the review measured: a {@code CLOSURE_NEW}
     * -owned {@code int} {@code FUNCTION_RETURN} boundary changed to
     * {@code string} under {@code TYPE_DESCRIPTOR}, which the validator
     * rejects with E6005 and the audit must reject by name).
     */
    private static void testIdentityGuardClosedCellRegressions()
            throws Exception {
        System.out.println("-- the guard's closed-cell negative controls: "
            + "each omitted clause fails by name --");
        ExecutableLoweredProject project =
            productionProject(EQUALITY_ANCHOR, "cutover-audit-closed-cell-", null);
        if (project == null) {
            return;
        }
        BodyLocalCell cell = firstClosureNewCell(project);
        check(cell != null,
            "the anchor project carries a CLOSURE_NEW-owned body-local cell");
        if (cell == null) {
            return;
        }
        Map<OpId, SemanticOp> byId = opsById(cell.unit());
        SemanticOp boundary = byId.get(cell.boundary());
        SemanticOp identity = byId.get(cell.identity());
        check(boundary != null && identity != null,
            "the seedable cell's boundary and identity ops resolve");
        if (boundary == null || identity == null
                || !(boundary.payload()
                    instanceof KindPayload.BoundaryPayload boundaryPayload)
                || !(identity.payload()
                    instanceof KindPayload.ClosureNewPayload closureNew)) {
            return;
        }

        // (d) The descriptor-kind failure policy: the boundary carries a
        // policy that violates the descriptor-kind rule for its descriptor
        // (the int descriptor must be checked under TYPE_DESCRIPTOR).
        SemanticOp wrongPolicy = reseed(boundary, boundaryPayload,
            FailurePolicyId.FUNCTION_SIGNATURE);
        LoweredModuleUnit wrongPolicyUnit = withOps(cell.unit(),
            withReplacedOp(cell.unit(), boundary.opId(), wrongPolicy));
        Optional<ProducedProjectIdentityAudit.Violation> descriptorKind =
            ProducedProjectIdentityAudit.firstViolation(
                withUnit(project, wrongPolicyUnit));
        check(descriptorKind.isPresent()
                && descriptorKind.get().rule().equals(
                    ProducedProjectIdentityAudit.BODY_LOCAL_DESCRIPTOR_KIND_MISMATCH),
            "a boundary policy violating the descriptor-kind rule fails the "
                + "audit by name: " + descriptorKind);

        // (e) The declared return descriptor: the CLOSURE_NEW-owned int
        // boundary is changed to string under the still-matching
        // TYPE_DESCRIPTOR policy, so only the declared-signature comparison
        // can reject it.
        SemanticOp wrongDescriptor = reseed(boundary,
            new KindPayload.BoundaryPayload(boundaryPayload.kind(),
                RuntimeDescriptor.String.INSTANCE, boundaryPayload.input(),
                boundaryPayload.realization()),
            boundary.failurePolicy());
        LoweredModuleUnit wrongDescriptorUnit = withOps(cell.unit(),
            withReplacedOp(cell.unit(), boundary.opId(), wrongDescriptor));
        Optional<ProducedProjectIdentityAudit.Violation> declaredDescriptor =
            ProducedProjectIdentityAudit.firstViolation(
                withUnit(project, wrongDescriptorUnit));
        check(declaredDescriptor.isPresent()
                && declaredDescriptor.get().rule().equals(
                    ProducedProjectIdentityAudit.BODY_LOCAL_RETURN_DESCRIPTOR_MISMATCH),
            "a boundary checking a descriptor other than the allocated "
                + "function's declared return descriptor fails the audit by "
                + "name: " + declaredDescriptor);
        checkEq(boundary.opId().toString(), declaredDescriptor
                .map(ProducedProjectIdentityAudit.Violation::opId).orElse(null),
            "the declared-descriptor violation names the boundary op id");

        // (f) The assigned static invocation shape: a statically
        // materialized CALL of the closure names the boundary as its return
        // boundary, so the body has an assigned invocation shape and its
        // reserved identity must live on that op, never on the body's
        // creation op.
        SemanticOp witness = staticCallWitness(cell, boundary,
            closureNew.signature());
        LoweredModuleUnit assignedUnit = withOps(cell.unit(),
            withAddedOp(cell.unit(), witness));
        Optional<ProducedProjectIdentityAudit.Violation> assignedShape =
            ProducedProjectIdentityAudit.firstViolation(
                withUnit(project, assignedUnit));
        check(assignedShape.isPresent()
                && assignedShape.get().rule().equals(
                    ProducedProjectIdentityAudit.BODY_LOCAL_ASSIGNED_INVOCATION_SHAPE),
            "a body-local cell named by a statically materialized "
                + "invocation fails the audit by name: " + assignedShape);

        // The audit and the validator agree on every seeded clause: the
        // validator rejects each seeded unit (R-BOUNDARY-TRIPLE) while the
        // audit names the omitted clause, so the audit can never report a
        // closed-rule violation as passing.
        checkValidatorRejects(wrongPolicyUnit, "the descriptor-kind seed");
        checkValidatorRejects(wrongDescriptorUnit,
            "the declared-descriptor seed");
        checkValidatorRejects(assignedUnit,
            "the assigned-invocation-shape seed");
    }

    /**
     * Asserts the validator rejects one seeded unit (the audit's
     * independent-clause parity proof), and that its E6005 names the
     * closed boundary rule.
     */
    private static void checkValidatorRejects(LoweredModuleUnit unit,
            String subject) {
        Optional<CompilerDiagnostic> diagnostic =
            SemanticIrValidator.validate(unit,
                new SemanticIrValidator.ComparisonFacts(unit.interfaceHash(),
                    SemanticProfile.DEAL_V1_2_INT32,
                    productionInvocation().capabilityRegistryHash()));
        check(diagnostic.isPresent(),
            subject + ": the validator rejects the seeded unit");
        if (diagnostic.isPresent()) {
            check(diagnostic.get().message().contains("R-BOUNDARY-TRIPLE")
                    || diagnostic.get().message().contains(
                        "boundary"),
                subject + ": the validator's E6005 names the boundary "
                    + "rule: " + diagnostic.get().message());
        }
    }

    /**
     * The lane integrations (ISSUE-0694; HR4(b) and the lane project
     * identity audit contract): both lanes' audit paths consume the very
     * project the lane's own compile produced — the production arm's
     * read-only {@code producedProject()} reference, never a re-lowering —
     * and a seeded regression fails the lane's audit by name (the fixture,
     * the module path, the function, the unresolved op id, and the rule).
     * The JVM lane's compile path audits the project the same orchestrator
     * run produced before the artifact is compiled, and the LuaJIT lane's
     * compile path audits the project its compile exposes; each propagates
     * a present failure as a lane compile failure, so an identity
     * regression can never pass and can never skip. The source-level
     * assertions pin the wiring itself, so it cannot be silently dropped.
     */
    private static void testLaneIdentityGuardIntegration() throws Exception {
        System.out.println("-- the lane integrations: both lanes audit the "
            + "project their own compile produced and fail by name on a "
            + "seeded regression --");
        Path root = repoRoot();
        if (root != null) {
            String jvmLane = Files.readString(
                root.resolve("test/conformance/JvmLane.java"),
                StandardCharsets.UTF_8);
            check(jvmLane.contains("laneIdentityFailure(")
                    && jvmLane.contains("run.producedProject()"),
                "the JVM lane's compile path audits the project the same "
                    + "orchestrator run produced");
            check(jvmLane.contains(
                    "CompilationOutcome.failure(identityFailure.get())"),
                "the JVM lane propagates the named violation as a lane "
                    + "compile failure");
            String luaLane = Files.readString(
                root.resolve("test/conformance/LuaLane.java"),
                StandardCharsets.UTF_8);
            check(luaLane.contains("laneIdentityFailure(")
                    && luaLane.contains("compileOutcome.producedProject()"),
                "the LuaJIT lane's compile path audits the project its own "
                    + "compile exposes");
            check(luaLane.contains("identityFailure.get()")
                    && luaLane.contains("MismatchClass.PROCESS_FAILURE"),
                "the LuaJIT lane propagates the named violation as a lane "
                    + "failure");
        }

        ExecutableLoweredProject project =
            productionProject(EQUALITY_ANCHOR, "cutover-lane-audit-", null);
        if (project == null) {
            return;
        }
        check(JvmLane.laneIdentityFailure(project, EQUALITY_ANCHOR).isEmpty(),
            "the JVM lane's audit accepts the produced project");
        check(LuaLane.laneIdentityFailure(project, EQUALITY_ANCHOR).isEmpty(),
            "the LuaJIT lane's audit accepts the produced project");
        check(JvmLane.laneIdentityFailure(null, EQUALITY_ANCHOR).isEmpty(),
            "a lane that ran no production arm has nothing to audit (the "
                + "harness path)");
        BodyLocalCell cell = firstBodyLocalCell(project);
        check(cell != null, "the anchor project carries the seedable cell");
        if (cell == null) {
            return;
        }
        LoweredModuleUnit dangling = withOps(cell.unit(),
            withoutOp(cell.unit(), cell.identity()));
        ExecutableLoweredProject seeded = withUnit(project, dangling);
        for (String lane : List.of("jvm", "luajit")) {
            Optional<String> failure = lane.equals("jvm")
                ? JvmLane.laneIdentityFailure(seeded, EQUALITY_ANCHOR)
                : LuaLane.laneIdentityFailure(seeded, EQUALITY_ANCHOR);
            check(failure.isPresent(),
                "the " + lane + " lane's audit fails the seeded regression");
            if (failure.isEmpty()) {
                continue;
            }
            String text = failure.get();
            check(text.contains(EQUALITY_ANCHOR)
                    && text.contains(cell.unit().moduleId().path())
                    && text.contains(cell.function().toString())
                    && text.contains(cell.identity().toString())
                    && text.contains(
                        ProducedProjectIdentityAudit.ENCLOSING_INVOCATION_NOT_EMITTED),
                "the " + lane + " lane's failure names the fixture, the "
                    + "module path, the function, the unresolved op id, and "
                    + "the rule: " + text);
            // The lane's named failure is recognized as a K12 violation by
            // the settled-lane acceptance run, which fails it hard at every
            // revision (never as a reportable precondition deficit), while
            // an unrelated lane mismatch is not.
            check(CutoverCorpusAcceptanceAuditTest.isLaneIdentityFailure(text),
                "the settled-lane acceptance recognizes the " + lane
                    + " lane's named identity failure as a hard failure: "
                    + text);
            check(!CutoverCorpusAcceptanceAuditTest.isLaneIdentityFailure(
                    "the lane compiled and executed the fixture"),
                "an ordinary lane mismatch is not an identity failure");
            check(!CutoverCorpusAcceptanceAuditTest.isLaneIdentityFailure(null),
                "a missing lane mismatch detail is not an identity failure");
        }
    }

    /**
     * One op reseeded with the given payload and failure policy (the
     * negative-control surface): the contract snapshot is rebuilt over the
     * reseeded fields with its digest recomputed by the landed
     * canonicalizer, so every seed is a well-formed op the validator would
     * see.
     */
    private static SemanticOp reseed(SemanticOp op, KindPayload payload,
            FailurePolicyId policy) {
        ClosedSelector selector = payload instanceof KindPayload.SelectorCarrying carrying
            ? carrying.selector() : null;
        OperationContractSnapshot placeholder = new OperationContractSnapshot(
            OperationContractSnapshot.VERSION, op.kind(), op.resultType(),
            op.operandTypes(), selector, payload, policy,
            op.contract().referencedSemanticIds(), "");
        String digest = ContractSnapshotCanonicalizer.digest(placeholder);
        OperationContractSnapshot contract = new OperationContractSnapshot(
            OperationContractSnapshot.VERSION, op.kind(), op.resultType(),
            op.operandTypes(), selector, payload, policy,
            op.contract().referencedSemanticIds(), digest);
        return new SemanticOp(op.opId(), op.kind(), op.origin(), op.result(),
            op.resultType(), op.operands(), op.operandTypes(), payload, policy,
            contract);
    }

    /**
     * One statically materialized CALL witness of the given boundary: an
     * indirect (non-dynamic) callee naming the boundary as its return
     * boundary — the assigned-invocation-shape witness the closed cell
     * refuses.
     */
    private static SemanticOp staticCallWitness(BodyLocalCell cell,
            SemanticOp boundary, RuntimeDescriptor.Func signature) {
        OpId witnessId = new OpId(cell.unit().moduleId(),
            nextFreeOpId(cell.unit()));
        KindPayload.CallPayload payload = new KindPayload.CallPayload(
            CallMode.INDIRECT,
            new KindPayload.CallCallee.Indirect(new ValueId(0)),
            signature, List.of(), cell.boundary(), null, null, null);
        OperationContractSnapshot placeholder = new OperationContractSnapshot(
            OperationContractSnapshot.VERSION, SemanticOpKind.CALL, null,
            List.of(), null, payload, FailurePolicyId.NO_DEAL_FAILURE,
            List.of(), "");
        String digest = ContractSnapshotCanonicalizer.digest(placeholder);
        OperationContractSnapshot contract = new OperationContractSnapshot(
            OperationContractSnapshot.VERSION, SemanticOpKind.CALL, null,
            List.of(), null, payload, FailurePolicyId.NO_DEAL_FAILURE,
            List.of(), digest);
        SourceOrigin origin = boundary.origin();
        return new SemanticOp(witnessId, SemanticOpKind.CALL, origin, null,
            null, List.of(), List.of(), payload,
            FailurePolicyId.NO_DEAL_FAILURE, contract);
    }

    /** The next unused op id of one unit. */
    private static long nextFreeOpId(LoweredModuleUnit unit) {
        long max = 0;
        for (SemanticOp op : unit.ops()) {
            max = Math.max(max, op.opId().id());
        }
        return max + 1;
    }

    /** The unit's ops with one op replaced by a reseeded op. */
    private static List<SemanticOp> withReplacedOp(LoweredModuleUnit unit,
            OpId opId, SemanticOp replacement) {
        List<SemanticOp> ops = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            ops.add(op.opId().equals(opId) ? replacement : op);
        }
        return ops;
    }

    /** The unit's ops plus one added op (the seed surface). */
    private static List<SemanticOp> withAddedOp(LoweredModuleUnit unit,
            SemanticOp added) {
        List<SemanticOp> ops = new ArrayList<>(unit.ops());
        ops.add(added);
        return ops;
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

    /**
     * The first body-local identity cell whose enclosing invocation is a
     * {@code CLOSURE_NEW} (the declared-return-descriptor clause's scope),
     * or {@code null} when the project carries none.
     */
    private static BodyLocalCell firstClosureNewCell(
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
                        && identity.kind() == SemanticOpKind.CLOSURE_NEW) {
                    return new BodyLocalCell(unit, payload.function(),
                        identity.opId(), payload.returnBoundaryOpId());
                }
            }
        }
        return null;
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
        testIdentityGuardClosedCellRegressions();
        testLaneIdentityGuardIntegration();
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== Cutover Acceptance Audit Tests Passed ===");
    }
}
