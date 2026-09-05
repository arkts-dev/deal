package deal.semantic;

import deal.ast.ArrayLiteralExpr;
import deal.ast.AssignmentExpr;
import deal.ast.AwaitExpression;
import deal.ast.BinaryExpr;
import deal.ast.BinaryOp;
import deal.ast.Block;
import deal.ast.BreakStatement;
import deal.ast.CallExpr;
import deal.ast.ClassDeclaration;
import deal.ast.ClassField;
import deal.ast.ContinueStatement;
import deal.ast.DeleteStatement;
import deal.ast.Either;
import deal.ast.ExportDeclaration;
import deal.ast.ExpressionNode;
import deal.ast.ExpressionStatement;
import deal.ast.ForInit;
import deal.ast.ForOfStatement;
import deal.ast.ForStatement;
import deal.ast.FunctionDeclaration;
import deal.ast.FunctionExpr;
import deal.ast.HasExpr;
import deal.ast.IdentifierExpr;
import deal.ast.IfStatement;
import deal.ast.ImportDeclaration;
import deal.ast.IndexExpr;
import deal.ast.LiteralExpr;
import deal.ast.LiteralValue;
import deal.ast.MemberAccessExpr;
import deal.ast.ObjectLiteralExpr;
import deal.ast.Property;
import deal.ast.ReturnStatement;
import deal.ast.StatementNode;
import deal.ast.TemplateLiteralExpr;
import deal.ast.ThrowStatement;
import deal.ast.TryStatement;
import deal.ast.UnaryExpr;
import deal.ast.UnaryOp;
import deal.ast.VariableDeclaration;
import deal.ast.WhileStatement;
import deal.checker.CheckResult;
import deal.checker.Symbol;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.ResolvedImport;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticOpKind;
import deal.types.Type;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The requirement-manifest computation of the pre-lowering foundation
 * (F3, one component): one read-only classification over the checked
 * project, producing exactly one {@link SemanticRequirementManifest} per
 * implementation module in {@code CheckedProjectInput} dependency order.
 *
 * <p><b>Closed capability claims.</b> Every implementation module claims
 * {@code FOUNDATION_VALUES}; {@code STDLIB_TIME_CONFLICT} is claimed
 * whenever any part of the closed four-part trigger fires — a sound
 * superset of "the module obtains or invokes {@code std/time.nowMillis}
 * through the checked DEAL module graph" (parent D8: a claiming module is
 * never common-lowerable in any purpose; over-claims only force LEGACY,
 * the safe direction). The I3 derivation rows (signed-int32 foundation,
 * ISSUE-0395) add exactly one further claim — {@code SIGNED_INT32} —
 * selected by the construct kind, never the magnitude:</p>
 *
 * <ul>
 *   <li>an {@code IntLiteral} of any magnitude → {@code CONST(Int)} → claims
 *       {@code SIGNED_INT32} — a small literal ({@code 1}) claims exactly
 *       like {@code 2147483647}, small literals waive nothing;</li>
 *   <li>a unary negation over an int-typed operand → {@code UNARY(INT32_NEG)}
 *       → claims {@code SIGNED_INT32};</li>
 *   <li>an arithmetic or comparison binary over int-typed operands →
 *       {@code BINARY(INT32_*)} → claims {@code SIGNED_INT32};</li>
 *   <li>an {@code int(...)} intrinsic call → {@code INTRINSIC_CALL(INT_CONVERT)}
 *       → claims {@code SIGNED_INT32}; an {@code number(...)} intrinsic
 *       call → {@code INTRINSIC_CALL(NUMBER_CONVERT)} → claims
 *       {@code FOUNDATION_VALUES} (the module-level row every manifest
 *       carries by construction — {@code NUMBER_CONVERT} never claims
 *       {@code SIGNED_INT32}).</li>
 * </ul>
 *
 * <p>Post-activation this makes every int-using module require target
 * capability {@code SIGNED_INT32} at plan time (F4 rule 4). The closed
 * {@code ConstructKind} set and the S4 capability catalog are untouched —
 * only the construct→capability derivation rows extend.</p>
 *
 * <p>The stdlib epic's plan-time arm ({@code stdlib-operations-and-time-lock}
 * D9): a module whose checked source contains a cataloged stdlib call
 * claims {@code STDLIB_SEMANTICS} — selected by the closed checked-fact
 * recognition predicate ({@link StdlibCallRecognition}: a
 * {@code ModuleSymbol} on a {@code STDLIB}-classified import plus the
 * closed {@link StdlibFunctionCatalog}, never a module/name pair) — so
 * F4 route rule 4's promotion gate covers stdlib-using modules before
 * lowering. A module without a cataloged call never claims it, and a
 * stdlib-export value read (a non-callee position) claims no stdlib
 * capability from the read and adds no route rule (D3). The claim is
 * derived from the checked source only — identical under every
 * invocation purpose.</p>
 *
 * <p><b>The remainder of the closed claims.</b></p>
 * <ul>
 *   <li><b>Arm A — direct module-object access:</b> a
 *       {@code MemberAccessExpr{object: IdentifierExpr, field:
 *       "nowMillis"}} in call position or any other value position whose
 *       object resolves through {@code CheckResult.symbolTable} (the
 *       module-scope chain) to a {@link Symbol.ModuleSymbol} whose name —
 *       the import alias — joins to the resolved imported module
 *       {@code std/time} with STDLIB classification. The trigger is the
 *       member access itself, never only a direct call site
 *       (first-class function values: {@code let f: () => int =
 *       time.nowMillis; f();} has no call-site access to detect).</li>
 *   <li><b>Arm B — table-typed access in a direct {@code std/time}
 *       importer (no exclusivity claim):</b> any {@code nowMillis} member
 *       access whose object expression's checked type in
 *       {@code CheckResult.typeMap} is {@link Type.Table}, in a module
 *       whose resolved import records contain {@code std/time} directly
 *       (alias-independent scan — the direct-import case of Arm C's
 *       closure gate). {@code Type.Table} erases module identity, so the
 *       arm over-claims on unrelated table fields in direct importers.</li>
 *   <li><b>Arm C — table-typed access in the transitive import closure
 *       (no exclusivity claim):</b> the same Table-typed-object access in
 *       a module whose transitive import closure contains {@code std/time}
 *       — {@code closure(M) = {direct std/time imports of M} \u222a \u22c3
 *       closure(D) over M's resolved imports D}. The closure is computed
 *       over the dependency-ordered index in one pass — never a fixpoint
 *       iteration. The orchestrator tolerates declaration-only import
 *       cycles at this phase (cycle members are contiguous in the
 *       dependency order, and every cycle edge is declaration-only), so
 *       the pass first decomposes the import graph into strongly
 *       connected components (one deterministic pass over the index
 *       order) and treats each component atomically: every member's
 *       closure carries the component's union of direct {@code std/time}
 *       imports, a conservative over-claim that only forces LEGACY.</li>
 *   <li><b>Arm D — claim propagation:</b> a module importing any module
 *       whose manifest claims {@code STDLIB_TIME_CONFLICT} also claims
 *       it — transitive by construction, closing cross-module
 *       function-wrapper escape ({@code getNow} wrappers reach dependents
 *       with no member access anywhere in the dependent). Within one
 *       component the propagated claim is the component's union of
 *       access-arm claims, assigned to every member in the same single
 *       pass (never a fixpoint iteration).</li>
 * </ul>
 *
 * <p><b>Alias→module-path join (Arm A's gate).</b> No new
 * name-resolution machinery exists: the identifier resolution is the
 * checker's, and the join is a read-only walk over the module's
 * {@code ImportDeclaration} records — AST facts the checker already
 * consumed during {@code processImports} — matched to the input's
 * resolved {@link ResolvedImport} records (alias + raw specifier) and
 * gated on the resolved target being {@code std.time} with STDLIB
 * classification. {@code Symbol.ModuleSymbol} carries no module path, so
 * the join never re-resolves an import.</p>
 *
 * <p><b>{@code constructCoverage}.</b> One row per reachable construct
 * of the module's AST over the closed construct→op detector table (S4):
 * the row key is the {@link ConstructKind} the detector maps the AST
 * shape to and the row value is exactly
 * {@link ConstructKind#mappedOpKinds()} verbatim (enforced by the
 * manifest record). The excluded {@code std/time.nowMillis} row has no
 * detector trigger and never appears; at lowering start the unit producer
 * copies these rows onto the unit's own enum-keyed
 * {@code constructCoverage} (S1 — the validator's R-COVERAGE fact).
 * ISSUE-0231..0239 extend only the construct→op detector rows, never the
 * closed {@code ConstructKind} set.</p>
 *
 * <p><b>Errors.</b> E6005 through {@code FailureContractRegistry} (T5)
 * with {@code LoweringFailureDetail {module, capability:
 * FOUNDATION_VALUES, validatorRule:
 * MANIFEST_INTERNAL_ERROR_SENTINEL, semanticProfile, irVersion, origin}}
 * for internally inconsistent checked/interface facts only — an import
 * resolving outside the dependency-ordered index, an implementation input
 * entry without its {@code CheckResult} (producer-defect guard: present
 * by construction), an out-of-grammar checked fact the detector requires
 * (a missing or {@link Type.Error} typeMap entry for a
 * {@code nowMillis}-access object, an object literal, or a binary
 * expression; a module symbol without its import declaration). No other
 * new error exists and no SHARED-ineligibility condition is an error
 * here. The computation is strictly read-only — no AST, checker, or
 * symbol-table mutation — and deterministic: dependency-order iteration,
 * the deterministic component decomposition, and the canonical JSON
 * facility make repeated computation byte-identical.</p>
 */
public final class LoweringSupport {

    /**
     * The manifest fact-defect identifier carried as the E6005
     * {@code validatorRule} (foundation F3): a support fact-defect
     * identifier, not a {@code SemanticIrValidator} rule — the closed
     * 14-condition validator rule set is unchanged.
     */
    public static final String MANIFEST_INTERNAL_ERROR_SENTINEL =
        "MANIFEST_INTERNAL_ERROR_SENTINEL";

    /**
     * The resolved dotted module path of the spec stdlib time module
     * (the raw import specifier {@code "std/time"} resolves to the
     * dotted module path {@code "std.time"} — the same fact the
     * interface index records, foundation F2).
     */
    static final String STD_TIME_RESOLVED_MODULE_PATH = "std.time";

    private LoweringSupport() {
        // Static computation surface only; no instances.
    }

    /**
     * Computes exactly one {@link SemanticRequirementManifest} per
     * implementation module in {@code CheckedProjectInput} dependency
     * order (foundation F3): the closure gate and the propagated claim
     * are one dependency-ordered pass — never a fixpoint iteration —
     * with the tolerated declaration-only import cycles treated as
     * atomic components (see the class documentation).
     *
     * @param invocation the release-owned compiler invocation; non-null
     * @param input      the checked project input (implementation modules
     *                   in dependency order); non-null
     * @param index      the project interface index covering the full
     *                   dependency closure; non-null
     * @return the manifest result: the dependency-ordered manifests plus
     *         no diagnostics on success, {@code null} manifests plus one
     *         E6005 on an inconsistent-fact defect
     */
    public static RequirementManifestResult computeManifests(CompilerInvocation invocation,
                                                             CheckedProjectInput input,
                                                             ProjectInterfaceIndex index) {
        Objects.requireNonNull(invocation, "invocation must not be null");
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(index, "index must not be null");
        try {
            ImportGraph graph = decompose(index, input);

            // One dependency-ordered pass over the index entries: closure(M)
            // = the component's union of direct std/time imports plus the
            // closures of the already-processed cross-component imports.
            // Same-component imports are covered by the component union, so
            // the pass terminates without ever revisiting an entry.
            Map<ModuleId, Boolean> closureHasTime = graph.closureHasTime();

            // One dependency-ordered pass over the input modules, grouped
            // by component: the access arms plus the cross-component
            // propagated claims, then the component union assigned to
            // every member (never a fixpoint iteration).
            Map<ModuleId, SemanticRequirementManifest> byId = new LinkedHashMap<>();
            List<SemanticRequirementManifest> manifests = new ArrayList<>();
            List<CheckedModuleInput> currentComponent = new ArrayList<>();
            long currentComponentId = -1L;
            for (CheckedModuleInput module : input.modules()) {
                if (module.kind() != CheckedModuleKind.IMPLEMENTATION) {
                    throw new FactDefect(module.moduleId(),
                        "input entry has kind " + module.kind()
                            + "; the parent-pinned DECLARATION input kind has no producer "
                            + "and every input entry is IMPLEMENTATION (inconsistent input)");
                }
                CheckResult checks = module.checks();
                if (checks == null) {
                    throw new FactDefect(module.moduleId(),
                        "implementation module has no CheckResult (missing checked facts; "
                            + "every input entry is IMPLEMENTATION and typeCheckAll fills its "
                            + "CheckResult)");
                }
                Long componentId = graph.sccOf().get(module.moduleId());
                if (componentId == null) {
                    throw new FactDefect(module.moduleId(),
                        "module is missing from the dependency-ordered closure computation "
                            + "(the interface index contradicts the checked project)");
                }
                if (currentComponent.isEmpty() || componentId == currentComponentId) {
                    currentComponent.add(module);
                    currentComponentId = componentId;
                    continue;
                }
                finalizeComponent(currentComponent, graph, closureHasTime, byId, manifests);
                currentComponent = new ArrayList<>();
                currentComponent.add(module);
                currentComponentId = componentId;
            }
            if (!currentComponent.isEmpty()) {
                finalizeComponent(currentComponent, graph, closureHasTime, byId, manifests);
            }
            return new RequirementManifestResult(manifests, List.of());
        } catch (FactDefect defect) {
            return failedResult(invocation, defect.module(), defect.getMessage());
        }
    }

    /**
     * Finalizes one atomic component of the input pass: computes each
     * member's base claim (access arms plus cross-component propagated
     * claims — same-component imports are deferred to the union), then
     * assigns the component's union to every member. One deterministic
     * step per component, never a fixpoint iteration.
     */
    private static void finalizeComponent(List<CheckedModuleInput> component,
                                          ImportGraph graph,
                                          Map<ModuleId, Boolean> closureHasTime,
                                          Map<ModuleId, SemanticRequirementManifest> byId,
                                          List<SemanticRequirementManifest> manifests)
            throws FactDefect {
        Map<ModuleId, Boolean> baseClaims = new LinkedHashMap<>();
        Map<ModuleId, ModuleScan> scans = new LinkedHashMap<>();
        for (CheckedModuleInput module : component) {
            ModuleScan scan = scanModule(module);
            scans.put(module.moduleId(), scan);
            boolean directTime = hasDirectStdTimeImport(module.imports());
            Boolean closureTime = closureHasTime.get(module.moduleId());
            if (closureTime == null) {
                throw new FactDefect(module.moduleId(),
                    "module is missing from the dependency-ordered closure computation "
                        + "(the interface index contradicts the checked project)");
            }
            boolean base = scan.armA
                || (scan.tableNowMillisAccess && directTime)
                || (scan.tableNowMillisAccess && closureTime);
            for (ResolvedImport resolvedImport : module.imports()) {
                if (resolvedImport.kind() != ExternalModuleKind.IMPLEMENTATION) {
                    continue;
                }
                if (Objects.equals(graph.sccOf().get(resolvedImport.resolvedModuleId()),
                        graph.sccOf().get(module.moduleId()))) {
                    continue; // same-component import — the union covers it
                }
                SemanticRequirementManifest dependency =
                    byId.get(resolvedImport.resolvedModuleId());
                if (dependency == null) {
                    throw new FactDefect(module.moduleId(),
                        "import '" + resolvedImport.modulePath() + "' resolves to the "
                            + "implementation module '" + resolvedImport.resolvedModuleId()
                            + "' without a manifest in dependency order (inconsistent "
                            + "checked/index facts)");
                }
                if (dependency.capabilities()
                        .contains(SemanticCapability.STDLIB_TIME_CONFLICT)) {
                    base = true;
                }
            }
            baseClaims.put(module.moduleId(), base);
        }

        boolean componentClaim = false;
        for (Boolean base : baseClaims.values()) {
            componentClaim |= base;
        }

        for (CheckedModuleInput module : component) {
            boolean claimsTimeConflict = baseClaims.get(module.moduleId()) || componentClaim;
            EnumSet<SemanticCapability> capabilities =
                EnumSet.of(SemanticCapability.FOUNDATION_VALUES);
            if (claimsTimeConflict) {
                capabilities.add(SemanticCapability.STDLIB_TIME_CONFLICT);
            }
            // I3 derivation row: int constructs claim SIGNED_INT32 at
            // the construct kind, never the magnitude (a small literal
            // claims exactly like 2147483647) — post-activation F4 rule 4
            // requires SIGNED_INT32 at plan time for every int-using
            // module.
            if (scans.get(module.moduleId()).signedInt32) {
                capabilities.add(SemanticCapability.SIGNED_INT32);
            }
            // The plan-time STDLIB_SEMANTICS arm (stdlib epic T5, D9): a
            // module whose checked source contains a cataloged stdlib call
            // claims STDLIB_SEMANTICS before lowering; a module without a
            // cataloged call never claims it, and a stdlib-export value
            // read claims nothing from the read (D3 — no route rule).
            if (scans.get(module.moduleId()).stdlibCall) {
                capabilities.add(SemanticCapability.STDLIB_SEMANTICS);
            }
            SemanticRequirementManifest manifest = new SemanticRequirementManifest(
                module.moduleId(), capabilities, scans.get(module.moduleId()).coverage);
            byId.put(module.moduleId(), manifest);
            manifests.add(manifest);
        }
    }

    // =========================================================================
    // Import-graph decomposition + the transitive closure (Arm C gate)
    // =========================================================================

    /**
     * The deterministic import-graph facts of one manifest computation:
     * the index entries in dependency (insertion) order, the strongly
     * connected component of every index entry (declaration-only cycles
     * tolerated by the orchestrator are atomic), the component's union of
     * direct {@code std/time} imports, and the per-entry closure computed
     * in one dependency-ordered pass.
     */
    private static final class ImportGraph {
        private final Map<ModuleId, Long> sccOf;
        private final Map<ModuleId, Boolean> closureHasTime;

        ImportGraph(Map<ModuleId, Long> sccOf, Map<ModuleId, Boolean> closureHasTime) {
            this.sccOf = sccOf;
            this.closureHasTime = closureHasTime;
        }

        Map<ModuleId, Long> sccOf() {
            return sccOf;
        }

        Map<ModuleId, Boolean> closureHasTime() {
            return closureHasTime;
        }
    }

    /**
     * Decomposes the index's import graph and computes the per-entry
     * transitive closure in deterministic passes:
     * <ol>
     *   <li>every import of every entry must resolve to an index entry —
     *       anything else is an inconsistent fact (E6005, fail closed:
     *       the closure gate can never silently under-claim);</li>
     *   <li>one deterministic Tarjan pass over the index order assigns
     *       each entry its strongly connected component;</li>
     *   <li>one pass groups the components' direct {@code std/time}
     *       imports;</li>
     *   <li>one dependency-ordered pass computes
     *       {@code closureHasTime(M) = componentHasTime(component(M)) \u2228
     *       \u22c3 closureHasTime(D)} over M's cross-component resolved
     *       imports D — never a fixpoint iteration.</li>
     * </ol>
     */
    private static ImportGraph decompose(ProjectInterfaceIndex index,
                                         CheckedProjectInput input) throws FactDefect {
        List<ModuleId> order = new ArrayList<>(index.modules().keySet());
        Map<ModuleId, Integer> nodeOf = new HashMap<>();
        for (int position = 0; position < order.size(); position++) {
            nodeOf.put(order.get(position), position);
        }
        for (ExternalModuleInterface entry : index.modules().values()) {
            for (ResolvedImport resolvedImport : entry.imports()) {
                if (!index.modules().containsKey(resolvedImport.resolvedModuleId())) {
                    throw new FactDefect(entry.moduleId(),
                        "import '" + resolvedImport.modulePath() + "' of module '"
                            + entry.moduleId() + "' resolves to '"
                            + resolvedImport.resolvedModuleId()
                            + "' which is missing from the dependency-ordered index "
                            + "(inconsistent resolution facts)");
                }
            }
        }

        Map<ModuleId, Long> sccOf = tarjanComponents(index, order, nodeOf);
        Map<Long, Boolean> componentHasTime = new LinkedHashMap<>();
        for (ExternalModuleInterface entry : index.modules().values()) {
            long componentId = sccOf.get(entry.moduleId());
            componentHasTime.merge(componentId,
                hasDirectStdTimeImport(entry.imports()), Boolean::logicalOr);
        }

        Map<ModuleId, Boolean> closureHasTime = new LinkedHashMap<>();
        for (ExternalModuleInterface entry : index.modules().values()) {
            long componentId = sccOf.get(entry.moduleId());
            boolean hasTime = componentHasTime.get(componentId);
            for (ResolvedImport resolvedImport : entry.imports()) {
                Long importedComponentId = sccOf.get(resolvedImport.resolvedModuleId());
                if (importedComponentId == null) {
                    throw new FactDefect(entry.moduleId(),
                        "import '" + resolvedImport.modulePath() + "' of module '"
                            + entry.moduleId() + "' resolves to '"
                            + resolvedImport.resolvedModuleId()
                            + "' which is missing from the dependency-ordered index "
                            + "(inconsistent resolution facts)");
                }
                if (importedComponentId == componentId) {
                    continue; // same-component import — the union covers it
                }
                Boolean importedClosure = closureHasTime.get(resolvedImport.resolvedModuleId());
                if (importedClosure == null) {
                    throw new FactDefect(entry.moduleId(),
                        "cross-component import '" + resolvedImport.modulePath()
                            + "' of module '" + entry.moduleId()
                            + "' targets a module not yet processed in the "
                            + "dependency-ordered index (inconsistent ordering facts)");
                }
                hasTime |= importedClosure;
            }
            closureHasTime.put(entry.moduleId(), hasTime);
        }

        for (CheckedModuleInput module : input.modules()) {
            if (!sccOf.containsKey(module.moduleId())) {
                throw new FactDefect(module.moduleId(),
                    "implementation module is missing from the interface index (the "
                        + "index contradicts the checked project)");
            }
        }
        return new ImportGraph(sccOf, closureHasTime);
    }

    /**
     * Deterministic Tarjan strongly-connected-components decomposition
     * over the index order: nodes are visited in index (dependency)
     * order and adjacency follows each entry's resolved imports in source
     * order, so the component ids are fully determined. Declaration-only
     * import cycles tolerated by the orchestrator become atomic
     * components — a conservative superset of every acyclic pass, which
     * only forces LEGACY.
     */
    private static Map<ModuleId, Long> tarjanComponents(ProjectInterfaceIndex index,
                                                        List<ModuleId> order,
                                                        Map<ModuleId, Integer> nodeOf) {
        int size = order.size();
        int[] discovered = new int[size];
        int[] lowLink = new int[size];
        Arrays.fill(discovered, -1);
        boolean[] onStack = new boolean[size];
        Deque<Integer> stack = new ArrayDeque<>();
        Map<ModuleId, Long> sccOf = new LinkedHashMap<>();
        int[] nextIndex = {0};
        long[] nextComponent = {0L};
        for (int node = 0; node < size; node++) {
            if (discovered[node] == -1) {
                strongConnect(node, index, order, nodeOf, discovered, lowLink, onStack,
                    stack, sccOf, nextIndex, nextComponent);
            }
        }
        return sccOf;
    }

    private static void strongConnect(int node, ProjectInterfaceIndex index,
                                      List<ModuleId> order, Map<ModuleId, Integer> nodeOf,
                                      int[] discovered, int[] lowLink, boolean[] onStack,
                                      Deque<Integer> stack, Map<ModuleId, Long> sccOf,
                                      int[] nextIndex, long[] nextComponent) {
        discovered[node] = nextIndex[0];
        lowLink[node] = nextIndex[0];
        nextIndex[0]++;
        stack.push(node);
        onStack[node] = true;

        ExternalModuleInterface entry = index.modules().get(order.get(node));
        for (ResolvedImport resolvedImport : entry.imports()) {
            Integer importedNode = nodeOf.get(resolvedImport.resolvedModuleId());
            if (importedNode == null) {
                continue; // guarded by decompose's resolution check (E6005)
            }
            if (discovered[importedNode] == -1) {
                strongConnect(importedNode, index, order, nodeOf, discovered, lowLink,
                    onStack, stack, sccOf, nextIndex, nextComponent);
                lowLink[node] = Math.min(lowLink[node], lowLink[importedNode]);
            } else if (onStack[importedNode]) {
                lowLink[node] = Math.min(lowLink[node], discovered[importedNode]);
            }
        }

        if (lowLink[node] == discovered[node]) {
            long componentId = nextComponent[0];
            nextComponent[0]++;
            int member;
            do {
                member = stack.pop();
                onStack[member] = false;
                sccOf.put(order.get(member), componentId);
            } while (member != node);
        }
    }

    /**
     * The alias-independent direct-import gate shared by Arm B and the
     * closure computation: the resolved import records contain the spec
     * stdlib time module — the resolved imported module has the dotted
     * module path {@code std.time} (the raw specifier {@code "std/time"}
     * resolved) and STDLIB classification.
     */
    private static boolean hasDirectStdTimeImport(List<ResolvedImport> imports) {
        for (ResolvedImport resolvedImport : imports) {
            if (resolvedImport.kind() == ExternalModuleKind.STDLIB
                    && STD_TIME_RESOLVED_MODULE_PATH
                        .equals(resolvedImport.resolvedModuleId().path())) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // Arm A alias join — a read-only walk over the module's import records
    // =========================================================================

    /**
     * The pinned alias→module-path join (Arm A's gate, F3): the module
     * symbol's name — the import alias — matches an
     * {@code ImportDeclaration} of the module's AST, and that
     * declaration's resolved import (matched by alias + raw specifier in
     * the input's resolved import records) targets the spec stdlib time
     * module with STDLIB classification. Never a re-resolution of
     * imports: the identifier resolution is the checker's, the join is
     * the read-only walk over the {@code ImportDeclaration} records the
     * checker already consumed during {@code processImports}.
     */
    private static boolean aliasJoinHitsStdTime(CheckedModuleInput module, String alias)
            throws FactDefect {
        boolean foundDeclaration = false;
        for (StatementNode statement : module.ast().statements()) {
            if (!(statement instanceof ImportDeclaration declaration)
                    || !declaration.alias().equals(alias)) {
                continue;
            }
            foundDeclaration = true;
            ResolvedImport resolved = findResolvedImport(module.imports(),
                declaration.alias(), declaration.modulePath());
            if (resolved == null) {
                throw new FactDefect(module.moduleId(),
                    "import declaration (alias '" + declaration.alias()
                        + "', specifier '" + declaration.modulePath()
                        + "') has no resolved import record (inconsistent import facts)");
            }
            if (resolved.kind() == ExternalModuleKind.STDLIB
                    && STD_TIME_RESOLVED_MODULE_PATH
                        .equals(resolved.resolvedModuleId().path())) {
                return true;
            }
        }
        if (!foundDeclaration) {
            throw new FactDefect(module.moduleId(),
                "module symbol '" + alias + "' has no matching import declaration in the "
                    + "module AST (inconsistent checked facts)");
        }
        return false;
    }

    private static ResolvedImport findResolvedImport(List<ResolvedImport> imports,
                                                     String alias, String modulePath) {
        for (ResolvedImport resolvedImport : imports) {
            if (resolvedImport.alias().equals(alias)
                    && resolvedImport.modulePath().equals(modulePath)) {
                return resolvedImport;
            }
        }
        return null;
    }

    // =========================================================================
    // Module scan: the four arms + the closed construct→op detector
    // =========================================================================

    /**
     * The per-module scan state: the Arm A trigger, the Table-typed
     * {@code nowMillis}-access trigger (the Arms B/C candidate), and the
     * reachable-construct coverage rows (enum-keyed, recorded over the
     * closed construct→op detector table of S4).
     */
    private static final class ModuleScan {
        boolean armA;
        boolean tableNowMillisAccess;

        /** The I3 {@code SIGNED_INT32} trigger: any int construct —
         * {@code CONST(Int)} (an int literal of any magnitude),
         * {@code UNARY(INT32_NEG)}, {@code BINARY(INT32_*)}, or
         * {@code INTRINSIC_CALL(INT_CONVERT)}. */
        boolean signedInt32;

        /** The plan-time {@code STDLIB_SEMANTICS} trigger: a call whose
         *  callee the closed checked-fact recognition predicate maps to a
         *  catalog entry (D1) — the only common stdlib form (D3); a
         *  stdlib-export value read never sets it. */
        boolean stdlibCall;
        final Map<ConstructKind, List<SemanticOpKind>> coverage =
            new EnumMap<>(ConstructKind.class);

        void cover(ConstructKind kind) {
            coverage.putIfAbsent(kind, kind.mappedOpKinds());
        }
    }

    private static ModuleScan scanModule(CheckedModuleInput module) throws FactDefect {
        ModuleScan scan = new ModuleScan();
        walkStatements(module.ast().statements(), module, scan);
        return scan;
    }

    private static void walkStatements(List<StatementNode> statements,
                                       CheckedModuleInput module, ModuleScan scan)
            throws FactDefect {
        for (StatementNode statement : statements) {
            walkStatement(statement, module, scan);
        }
    }

    private static void walkStatement(StatementNode statement, CheckedModuleInput module,
                                      ModuleScan scan) throws FactDefect {
        switch (statement) {
            case ImportDeclaration ignored -> scan.cover(ConstructKind.IMPORT_EXPORT_ENTRY);
            case ExportDeclaration exportDeclaration -> {
                scan.cover(ConstructKind.IMPORT_EXPORT_ENTRY);
                walkStatement(exportDeclaration.declaration(), module, scan);
            }
            case ClassDeclaration classDeclaration -> {
                scan.cover(ConstructKind.CLASS_DECLARATION);
                for (ClassField field : classDeclaration.fields()) {
                    if (field.defaultExpr().isPresent()) {
                        walkExpression(field.defaultExpr().get(), module, scan);
                    }
                }
            }
            case FunctionDeclaration functionDeclaration -> {
                scan.cover(ConstructKind.FUNCTION_DECLARATION_EXPRESSION);
                if (functionDeclaration.body() != null) {
                    walkStatement(functionDeclaration.body(), module, scan);
                }
            }
            case VariableDeclaration variableDeclaration -> {
                scan.cover(ConstructKind.VARIABLE_DECLARATION);
                walkExpression(variableDeclaration.initializer(), module, scan);
            }
            case ReturnStatement returnStatement -> {
                scan.cover(ConstructKind.RETURN_EXPRESSION_STATEMENT);
                if (returnStatement.expr().isPresent()) {
                    walkExpression(returnStatement.expr().get(), module, scan);
                }
            }
            case IfStatement ifStatement -> {
                scan.cover(ConstructKind.IF_WHILE_FOR_FOR_OF);
                walkExpression(ifStatement.condition(), module, scan);
                walkStatement(ifStatement.thenBlock(), module, scan);
                if (ifStatement.elseBranch().isPresent()) {
                    walkEither(ifStatement.elseBranch().get(), module, scan);
                }
            }
            case WhileStatement whileStatement -> {
                scan.cover(ConstructKind.IF_WHILE_FOR_FOR_OF);
                walkExpression(whileStatement.condition(), module, scan);
                walkStatement(whileStatement.body(), module, scan);
            }
            case ForStatement forStatement -> {
                scan.cover(ConstructKind.IF_WHILE_FOR_FOR_OF);
                if (forStatement.init().isPresent()) {
                    walkForInit(forStatement.init().get(), module, scan);
                }
                if (forStatement.condition().isPresent()) {
                    walkExpression(forStatement.condition().get(), module, scan);
                }
                if (forStatement.update().isPresent()) {
                    walkExpression(forStatement.update().get(), module, scan);
                }
                walkStatement(forStatement.body(), module, scan);
            }
            case ForOfStatement forOfStatement -> {
                scan.cover(ConstructKind.IF_WHILE_FOR_FOR_OF);
                walkExpression(forOfStatement.iterable(), module, scan);
                walkStatement(forOfStatement.body(), module, scan);
            }
            case BreakStatement ignored -> scan.cover(ConstructKind.BREAK_CONTINUE);
            case ContinueStatement ignored -> scan.cover(ConstructKind.BREAK_CONTINUE);
            case ExpressionStatement expressionStatement -> {
                scan.cover(ConstructKind.RETURN_EXPRESSION_STATEMENT);
                walkExpression(expressionStatement.expr(), module, scan);
            }
            case DeleteStatement deleteStatement -> {
                scan.cover(ConstructKind.DELETE);
                // The target's own member/index access is the write
                // position of the delete chain (D14) — the commit ops are
                // MEMBER_DELETE/INDEX_DELETE, named by the DELETE row,
                // never by the read rows; its receiver/key walk normally.
                walkWriteTarget(deleteStatement.target(), module, scan);
            }
            case TryStatement tryStatement -> {
                scan.cover(ConstructKind.TRY_CATCH_THROW);
                walkStatement(tryStatement.tryBlock(), module, scan);
                walkStatement(tryStatement.catchBlock(), module, scan);
            }
            case ThrowStatement throwStatement -> {
                scan.cover(ConstructKind.TRY_CATCH_THROW);
                walkExpression(throwStatement.expr(), module, scan);
            }
            case Block block -> walkStatements(block.statements(), module, scan);
        }
    }

    private static void walkEither(Either<IfStatement, Block> branch,
                                   CheckedModuleInput module, ModuleScan scan)
            throws FactDefect {
        switch (branch) {
            case Either.Left<IfStatement, Block> left ->
                walkStatement(left.value(), module, scan);
            case Either.Right<IfStatement, Block> right ->
                walkStatement(right.value(), module, scan);
        }
    }

    private static void walkForInit(ForInit init, CheckedModuleInput module, ModuleScan scan)
            throws FactDefect {
        switch (init) {
            case ForInit.VarDecl varDecl -> walkStatement(varDecl.decl(), module, scan);
            case ForInit.AssignExpr assignExpr -> walkExpression(assignExpr.expr(), module, scan);
        }
    }

    private static void walkExpression(ExpressionNode expression, CheckedModuleInput module,
                                       ModuleScan scan) throws FactDefect {
        switch (expression) {
            case LiteralExpr literalExpr -> {
                scan.cover(ConstructKind.SCALAR_LITERAL);
                // I3: CONST(Int) claims SIGNED_INT32 at the construct
                // kind, never the magnitude — a small literal claims
                // exactly like 2147483647.
                if (literalExpr.value() instanceof LiteralValue.IntLiteral) {
                    scan.signedInt32 = true;
                }
            }
            case IdentifierExpr ignored -> scan.cover(ConstructKind.IDENTIFIER);
            case BinaryExpr binaryExpr -> {
                Type type = checkedType(module, binaryExpr);
                // `string +` lowers to STRING_CONCAT (S4); every other
                // binary operator — arithmetic and comparison alike — is
                // the UNARY/BINARY row (logical operators use
                // selector-bearing BRANCH, named by the same row).
                if (binaryExpr.op() == BinaryOp.ADD && type == Type.String.INSTANCE) {
                    scan.cover(ConstructKind.STRING_CONCAT_TEMPLATE);
                } else {
                    scan.cover(ConstructKind.UNARY_ARITHMETIC_COMPARISON);
                    // I3: BINARY(INT32_*) claims SIGNED_INT32 — every
                    // arithmetic and comparison selector over int-typed
                    // operands (number/string/boolean/null/nullable/
                    // reference selectors do not).
                    if (checkedType(module, binaryExpr.left())
                            == Type.Int.INSTANCE) {
                        scan.signedInt32 = true;
                    }
                }
                walkExpression(binaryExpr.left(), module, scan);
                walkExpression(binaryExpr.right(), module, scan);
            }
            case UnaryExpr unaryExpr -> {
                scan.cover(ConstructKind.UNARY_ARITHMETIC_COMPARISON);
                // I3: UNARY(INT32_NEG) claims SIGNED_INT32 — a negation
                // over an int-typed operand; BOOL_NOT and NUMBER_NEG do not.
                if (unaryExpr.op() == UnaryOp.NEG
                        && checkedType(module, unaryExpr.expr())
                            == Type.Int.INSTANCE) {
                    scan.signedInt32 = true;
                }
                walkExpression(unaryExpr.expr(), module, scan);
            }
            case CallExpr callExpr -> {
                // A cataloged stdlib call is the closed CALL row's
                // STDLIB_CALL form — never CROSS_MODULE_CALL: the row's
                // mapped op kinds are {CALL, CALLBACK_INVOKE,
                // INTRINSIC_CALL, STDLIB_CALL, ASYNC_START, AWAIT}, the
                // exact production of the lowerer's stdlib branch. The
                // classification runs the closed checked-fact
                // recognition predicate (D1 — ModuleSymbol on a
                // STDLIB-classified import plus the catalog), never a
                // module/name pair.
                boolean stdlibCall = StdlibCallRecognition.recognize(callExpr.callee(),
                    module.checks().symbolTable(), module.imports()).isPresent();
                if (stdlibCall) {
                    // The plan-time STDLIB_SEMANTICS arm (D9): a cataloged
                    // stdlib call claims STDLIB_SEMANTICS before lowering,
                    // so route rule 4's promotion gate covers the module.
                    scan.stdlibCall = true;
                }
                boolean crossModule = !stdlibCall
                    && callExpr.callee() instanceof MemberAccessExpr member
                    && member.object() instanceof IdentifierExpr identifier
                    && module.checks().symbolTable().resolve(identifier.name())
                        instanceof Symbol.ModuleSymbol;
                scan.cover(crossModule
                    ? ConstructKind.CROSS_MODULE_CALL
                    : ConstructKind.CALL);
                // I3: INTRINSIC_CALL(INT_CONVERT) claims SIGNED_INT32;
                // NUMBER_CONVERT claims FOUNDATION_VALUES (the module-level
                // row every manifest carries by construction) and never
                // SIGNED_INT32.
                if (callExpr.callee() instanceof IdentifierExpr identifier) {
                    Symbol symbol = module.checks().symbolTable()
                        .resolve(identifier.name());
                    if (symbol instanceof Symbol.IntrinsicSymbol intrinsic
                            && "int".equals(intrinsic.name())) {
                        scan.signedInt32 = true;
                    }
                }
                walkExpression(callExpr.callee(), module, scan);
                for (ExpressionNode argument : callExpr.args()) {
                    walkExpression(argument, module, scan);
                }
            }
            case MemberAccessExpr memberAccessExpr -> {
                scan.cover(ConstructKind.MEMBER_ACCESS);
                walkExpression(memberAccessExpr.object(), module, scan);
                if ("nowMillis".equals(memberAccessExpr.field())) {
                    checkNowMillisAccess(memberAccessExpr, module, scan);
                }
            }
            case IndexExpr indexExpr -> {
                scan.cover(ConstructKind.INDEX_ACCESS);
                walkExpression(indexExpr.array(), module, scan);
                walkExpression(indexExpr.index(), module, scan);
            }
            case ArrayLiteralExpr arrayLiteralExpr -> {
                scan.cover(ConstructKind.ARRAY_OBJECT_LITERAL);
                walkElements(arrayLiteralExpr.elements(), module, scan);
            }
            case ObjectLiteralExpr objectLiteralExpr -> {
                // A class-typed object literal constructs a class
                // (CLASS_NEW/CLASS_FACTORY); every other object literal
                // constructs an insertion-ordered table (TABLE_NEW).
                Type type = checkedType(module, objectLiteralExpr);
                scan.cover(type instanceof Type.Class
                    ? ConstructKind.CLASS_OBJECT_LITERAL
                    : ConstructKind.ARRAY_OBJECT_LITERAL);
                for (Property property : objectLiteralExpr.properties()) {
                    walkExpression(property.value(), module, scan);
                }
            }
            case FunctionExpr functionExpr -> {
                scan.cover(ConstructKind.FUNCTION_DECLARATION_EXPRESSION);
                walkStatement(functionExpr.body(), module, scan);
            }
            case HasExpr hasExpr -> {
                scan.cover(ConstructKind.HAS);
                walkExpression(hasExpr.object(), module, scan);
            }
            case AssignmentExpr assignmentExpr -> {
                scan.cover(ConstructKind.ASSIGNMENT);
                // The target's own member/index access is the write
                // position of the address chain (D14: MEMBER_WRITE/
                // INDEX_WRITE commit ops — never MEMBER_READ/INDEX_READ),
                // so it records no MEMBER_ACCESS/INDEX_ACCESS row; its
                // receiver and key sub-expressions are ordinary reads and
                // walk normally.
                walkWriteTarget(assignmentExpr.target(), module, scan);
                walkExpression(assignmentExpr.value(), module, scan);
            }
            case TemplateLiteralExpr templateLiteralExpr -> {
                scan.cover(ConstructKind.STRING_CONCAT_TEMPLATE);
                walkElements(templateLiteralExpr.parts(), module, scan);
            }
            case AwaitExpression awaitExpression -> {
                scan.cover(ConstructKind.AWAIT_ASYNC_CALL);
                walkExpression(awaitExpression.callee(), module, scan);
            }
        }
    }

    private static void walkElements(List<ExpressionNode> elements, CheckedModuleInput module,
                                     ModuleScan scan) throws FactDefect {
        for (ExpressionNode element : elements) {
            walkExpression(element, module, scan);
        }
    }

    /**
     * Walks an assignment/delete target's sub-expressions without
     * recording a read row for the target's own member/index access: the
     * write position of the address chain (D14) produces the commit ops
     * ({@code MEMBER_WRITE}/{@code INDEX_WRITE},
     * {@code MEMBER_DELETE}/{@code INDEX_DELETE}), which are named by the
     * {@code ASSIGNMENT}/{@code DELETE} rows, never by the
     * {@code MEMBER_ACCESS}/{@code INDEX_ACCESS} read rows. The target's
     * receiver and key sub-expressions are ordinary reads and record
     * their own rows ({@code INDEX_ACCESS} for a chained receiver, etc.).
     * A non-member/index target (an identifier) walks normally.
     */
    private static void walkWriteTarget(ExpressionNode target, CheckedModuleInput module,
                                        ModuleScan scan) throws FactDefect {
        switch (target) {
            case MemberAccessExpr member ->
                walkExpression(member.object(), module, scan);
            case IndexExpr index -> {
                walkExpression(index.array(), module, scan);
                walkExpression(index.index(), module, scan);
            }
            default -> walkExpression(target, module, scan);
        }
    }

    /**
     * The {@code nowMillis} member-access trigger: Arm A (module-object
     * access plus the alias→module-path join) and the Table-typed-object
     * candidate for Arms B/C. The trigger is the member access itself in
     * call or any value position — never only a direct call site.
     */
    private static void checkNowMillisAccess(MemberAccessExpr memberAccess,
                                             CheckedModuleInput module, ModuleScan scan)
            throws FactDefect {
        if (memberAccess.object() instanceof IdentifierExpr identifier) {
            Symbol symbol = module.checks().symbolTable().resolve(identifier.name());
            if (symbol instanceof Symbol.ModuleSymbol moduleSymbol
                    && aliasJoinHitsStdTime(module, moduleSymbol.name())) {
                scan.armA = true;
            }
        }
        Type objectType = checkedType(module, memberAccess.object());
        if (objectType == Type.Table.INSTANCE) {
            scan.tableNowMillisAccess = true;
        }
    }

    /**
     * The defensive checked-fact guard (F2/F3): a position the detector
     * classifies on must carry its checked type — a missing
     * {@code typeMap} entry or the internal checker sentinel
     * {@link Type.Error} is a producer defect (E6005), never a silent
     * under-claim and never a crash. Post-phase-3 success every checked
     * expression carries its type, so this guard never fires on valid
     * input.
     */
    private static Type checkedType(CheckedModuleInput module, ExpressionNode expression)
            throws FactDefect {
        Type type = module.checks().typeMap().get(expression);
        if (type == null || type == Type.Error.INSTANCE) {
            throw new FactDefect(module.moduleId(),
                "expression at " + expression.span().file() + ":"
                    + expression.span().startLine() + ":" + expression.span().startColumn()
                    + " has no usable checked type (missing checked facts / internal "
                    + "checker sentinel)");
        }
        return type;
    }

    // =========================================================================
    // E6005 through the failure contract registry
    // =========================================================================

    /** An internally inconsistent checked/interface fact (never a crash). */
    private static final class FactDefect extends Exception {
        private final ModuleId module;

        FactDefect(ModuleId module, String message) {
            super(message);
            this.module = Objects.requireNonNull(module, "module must not be null");
        }

        ModuleId module() {
            return module;
        }
    }

    private static RequirementManifestResult failedResult(CompilerInvocation invocation,
                                                          ModuleId module, String reason) {
        LoweringFailureDetail detail = new LoweringFailureDetail(module.path(),
            SemanticCapability.FOUNDATION_VALUES, MANIFEST_INTERNAL_ERROR_SENTINEL,
            invocation.semanticProfile(), LoweredModuleUnit.FORMAT_VERSION,
            "LoweringSupport " + MANIFEST_INTERNAL_ERROR_SENTINEL + " (" + reason + ")");
        return new RequirementManifestResult(null,
            List.of(FailureContractRegistry.e6005(detail)));
    }
}
