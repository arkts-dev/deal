package deal.module;

import deal.ast.ExportDeclaration;
import deal.ast.FunctionDeclaration;
import deal.ast.ProgramNode;
import deal.ast.Span;
import deal.ast.StatementNode;
import deal.checker.CheckResult;
import deal.checker.NameResolver;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticNote;
import deal.diagnostics.DiagnosticRange;
import deal.identity.CanonicalModuleIdentity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The merged runtime dependency graph of the default planning epics
 * (ISSUE-0543, design source
 * {@code provider-versioned-default-plans} D1/D7/D8): ordinary
 * runtime-use edges ({@code RUNTIME_USE}) and the planner's deferred
 * default edges ({@code DEFERRED_DEFAULT_BINDING}) merge over the one
 * {@link RuntimeImportDependency} carrier, a digest-free SCC pass runs
 * before any provider digest is requested, a strongly connected
 * component containing at least one runtime edge fails the compilation
 * with E2005 (with one note per runtime edge inside the SCC carrying
 * {@code (reason, from &rarr; to, sourceRange)} and no plan, FFI
 * metadata, or artifact publication), type-only cycles stay legal, and
 * acyclic graphs produce the initialization order preserving
 * first-import declaration order plus the final digest-bearing
 * dependency records.
 *
 * <p><b>Two-phase pipeline (task-pinned):</b>
 * {@link #digestFreePass} collects the provisional runtime occurrences
 * (semantic resource identity, reason, first source range — no digest),
 * merges them over the import-declaration edge structure, and runs the
 * SCC pass. Only after that pass succeeds may the orchestrator request
 * provider digests from the serializer
 * ({@code DefaultSemanticSerializer.serialize} with the additional
 * demand overload) and call {@link #finalizeGraph} to publish the final
 * digest-bearing records and complete every plan's
 * {@code runtimeDependencies}. No digest is computed or requested
 * anywhere in the digest-free pass, so digest equations are well-founded
 * exactly when they are evaluated (D9).</p>
 *
 * <p><b>Ordinary runtime-use edges (D7(a)):</b> for each implementation
 * module the complete typed evaluator IR of every top-level function
 * body (including nested function declarations and function-expression
 * bodies reached through the shared {@link DefaultIrRecorder}
 * statement walk) publishes an ordered occurrence — a call whose callee
 * resolves to a function declared in an imported module (call-site
 * range), an imported function referenced as a first-class value
 * (member-access range), or a contextual class literal typed as a
 * plan-bearing class declared in an imported module (literal range).
 * Type positions and class field defaults publish nothing (defaults are
 * the planner's {@code DEFERRED_DEFAULT_BINDING} edges). One module
 * edge is kept per {@code (importer, imported module, semantic resource
 * identity)} with the first occurrence's range and alias (D7(a)).
 * Declaration modules carry no executable bodies and publish none.</p>
 *
 * <p><b>Default edges (D7(b)/D2/D4):</b> the planner's provisional
 * occurrences of every planned class, one dependency per imported
 * resource identity per plan, carrying the first occurrence's range.</p>
 *
 * <p><b>E2005:</b> Tarjan's SCC algorithm over the merged import-edge
 * graph classifies every strongly connected component; an SCC
 * containing at least one runtime edge (either reason) is a runtime
 * SCC. Each runtime SCC emits one E2005 diagnostic with the pinned
 * anchor chain (the import declaration span of the first cycle module
 * targeting another cycle member, then that module's program span, then
 * the canonical synthetic shape — the landed
 * {@link CompilationOrchestrator#e2005Diagnostic} helper) plus one
 * note per runtime edge inside the SCC carrying
 * {@code (reason, from &rarr; to, sourceRange)}. The compilation fails
 * and publishes no plan, FFI metadata, or artifact (D1/D7). Type-only
 * SCCs are allowed and impose no initialization order.</p>
 *
 * <p><b>Initialization order:</b> {@link #initializationOrder} is the
 * one shared ordering algorithm — Kahn over the import edges in module
 * discovery order; when no module is ready, exactly the remaining cycle
 * members (type-only by construction on a successful graph pass) are
 * appended in discovery order and the sweep resumes. The phase-2 check
 * order and the graph's initialization order both derive from this one
 * method, so they are byte-identical for every compilation.</p>
 *
 * <p><b>Finalization:</b> {@link #finalizeGraph} is the sole producer
 * of completed digest-bearing records in the production pipeline: for
 * every provisional edge it resolves the provider digest (already
 * demanded through the serializer) and constructs
 * {@link RuntimeImportDependency}; every planned class completes its
 * {@code runtimeDependencies} through
 * {@link CompilerClassDefaultPlan#withRuntimeDependencies} with the
 * serializer-completed plan content preserved. A missing digest is a
 * broken wiring defect and fails with an {@link IllegalStateException}
 * — never a placeholder digest.</p>
 */
public final class ModuleDependencyGraph {

    private ModuleDependencyGraph() {
        // Static entry; no instances.
    }

    // =========================================================================
    // Input and result shapes
    // =========================================================================

    /**
     * The read-only per-module facts the graph pass consumes: the
     * module's resolved location (private semantic identity), parsed
     * program, checked facts and the name resolver (null exactly for
     * declaration modules), the import surface, the module-identity
     * classification, and the declaration flag.
     *
     * @param sourcePath        the module's absolute normalized source
     *                          path (the graph node key)
     * @param modulePath        the module's dotted module path
     * @param program           the module's parsed program
     * @param location          the module's resolved source location
     * @param checkResult       the module's phase-3 check result, or
     *                          null for declaration modules
     * @param nameResolver      the module's phase-3 name resolver, or
     *                          null for declaration modules
     * @param imports           import alias &rarr; the provider
     *                          snapshot, insertion-ordered
     * @param classification    dotted module path &rarr; canonical
     *                          public module identity
     * @param isDeclarationFile true when the module is a
     *                          {@code .d.deal} declaration module
     */
    public record ModuleInput(
        String sourcePath,
        String modulePath,
        ProgramNode program,
        SourceModuleLocation location,
        CheckResult checkResult,
        NameResolver nameResolver,
        Map<String, DefaultPlanImport> imports,
        Map<String, CanonicalModuleIdentity> classification,
        boolean isDeclarationFile
    ) {

        public ModuleInput {
            Objects.requireNonNull(sourcePath, "sourcePath");
            Objects.requireNonNull(modulePath, "modulePath");
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(location, "location");
            imports = Map.copyOf(Objects.requireNonNull(imports, "imports"));
            classification = Map.copyOf(Objects.requireNonNull(
                classification, "classification"));
            if (isDeclarationFile != (checkResult == null)) {
                throw new IllegalArgumentException(
                    "checkResult is null exactly for declaration files");
            }
            if (isDeclarationFile != (nameResolver == null)) {
                throw new IllegalArgumentException(
                    "nameResolver is null exactly for declaration files");
            }
        }
    }

    /**
     * One merged provisional runtime edge (digest-free): the module
     * level projection of an occurrence — {@code (kind, from, to,
     * importAlias, semanticResourceIdentity, sourceRange, reason)} —
     * carrying everything the SCC pass and the E2005 notes need without
     * a provider digest.
     *
     * @param kind                     the reference kind
     * @param from                     the consuming module's private
     *                                 semantic identity
     * @param to                       the provider module's private
     *                                 semantic identity
     * @param importAlias              the import alias of the first
     *                                 occurrence
     * @param semanticResourceIdentity the imported resource's private
     *                                 semantic identity
     * @param sourceRange              the first occurrence's complete
     *                                 scalar reference range
     * @param reason                   the closed edge reason
     */
    public record ProvisionalEdge(
        RuntimeResourceReference.Kind kind,
        SemanticModuleIdentity from,
        SemanticModuleIdentity to,
        String importAlias,
        SemanticResourceIdentity semanticResourceIdentity,
        DiagnosticRange sourceRange,
        RuntimeImportDependency.Reason reason
    ) {

        public ProvisionalEdge {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(importAlias, "importAlias");
            Objects.requireNonNull(semanticResourceIdentity,
                "semanticResourceIdentity");
            Objects.requireNonNull(sourceRange, "sourceRange");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The digest-free pass result: the E2005 diagnostics (empty on
     * success), the merged provisional edges and the initialization
     * order (empty when failed), the ordinary occurrences for the
     * serializer's digest-demand overload, and the failure flag.
     *
     * @param diagnostics          the E2005 diagnostics (never null;
     *                             empty exactly on success)
     * @param provisionalEdges     the merged provisional edges in
     *                             first-occurrence order (empty when
     *                             failed)
     * @param ordinaryOccurrences  the collected ordinary runtime-use
     *                             occurrences in walk order (empty when
     *                             failed)
     * @param initializationOrder  the initialization order (empty when
     *                             failed)
     * @param failed               true exactly when a runtime SCC fired
     *                             E2005
     */
    public record DigestFreeResult(
        List<CompilerDiagnostic> diagnostics,
        List<ProvisionalEdge> provisionalEdges,
        List<DefaultResourceOccurrence> ordinaryOccurrences,
        List<String> initializationOrder,
        boolean failed
    ) {

        public DigestFreeResult {
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics,
                "diagnostics"));
            provisionalEdges = List.copyOf(Objects.requireNonNull(
                provisionalEdges, "provisionalEdges"));
            ordinaryOccurrences = List.copyOf(Objects.requireNonNull(
                ordinaryOccurrences, "ordinaryOccurrences"));
            initializationOrder = List.copyOf(Objects.requireNonNull(
                initializationOrder, "initializationOrder"));
        }
    }

    /**
     * The published graph of an acyclic compilation: the merged final
     * digest-bearing dependency records and the completed plans
     * (every plan's {@code runtimeDependencies} filled with the
     * serializer-completed entry content preserved).
     *
     * @param runtimeDependencies the merged final records — ordinary
     *                            edges in first-occurrence order, then
     *                            default edges in module/plan order,
     *                            structurally deduplicated
     * @param completedPlans      module source path &rarr; the
     *                            completed planned classes in planning
     *                            order
     */
    public record PublishedGraph(
        List<RuntimeImportDependency> runtimeDependencies,
        Map<String, List<PlannedDefaultClass>> completedPlans
    ) {

        public PublishedGraph {
            runtimeDependencies = List.copyOf(Objects.requireNonNull(
                runtimeDependencies, "runtimeDependencies"));
            completedPlans = unmodifiableOrderedPlans(
                Objects.requireNonNull(completedPlans,
                    "completedPlans"));
        }

        private static Map<String, List<PlannedDefaultClass>>
                unmodifiableOrderedPlans(
                    Map<String, List<PlannedDefaultClass>> plans) {
            Map<String, List<PlannedDefaultClass>> copy =
                new LinkedHashMap<>();
            for (Map.Entry<String, List<PlannedDefaultClass>> entry
                    : plans.entrySet()) {
                copy.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
    }

    // =========================================================================
    // Digest-free SCC pass
    // =========================================================================

    /**
     * Runs the digest-free SCC pass: collects the ordinary runtime-use
     * occurrences (D7(a)), merges them with the planner's default
     * occurrences (D7(b)) over the one carrier shape, classifies the
     * strongly connected components of the import-edge graph, and emits
     * one E2005 diagnostic per runtime SCC with a note for every
     * runtime edge inside it. No provider digest is computed or
     * requested anywhere in this pass.
     *
     * @param modules         the compilation's modules in discovery
     *                        order (the graph node set)
     * @param plannedClasses  module source path &rarr; the planned
     *                        classes with their provisional occurrence
     *                        data (the planner output)
     * @param importEdgeSpans source path &rarr; (resolved import target
     *                        &rarr; the import declaration span), for
     *                        the E2005 anchor chain
     * @param programSpans    source path &rarr; the module program
     *                        span, for the E2005 anchor fallback
     * @return the diagnostics, the merged provisional edges, the
     *         ordinary occurrences, and the initialization order
     */
    public static DigestFreeResult digestFreePass(
            Map<String, ModuleInput> modules,
            Map<String, List<PlannedDefaultClass>> plannedClasses,
            Map<String, Map<String, Span>> importEdgeSpans,
            Map<String, Span> programSpans) {
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(plannedClasses, "plannedClasses");
        Objects.requireNonNull(importEdgeSpans, "importEdgeSpans");
        Objects.requireNonNull(programSpans, "programSpans");
        return new Pass(modules, plannedClasses, importEdgeSpans,
            programSpans).run();
    }

    /** The internal digest-free pass. */
    private static final class Pass {

        private final Map<String, ModuleInput> modules =
            new LinkedHashMap<>();
        private final Map<String, List<PlannedDefaultClass>>
            plannedClasses = new LinkedHashMap<>();
        private final Map<String, Map<String, Span>> importEdgeSpans;
        private final Map<String, Span> programSpans;

        /** source path &rarr; the module's resolved import targets in
         * import-declaration order (the type-level edge structure). */
        private final Map<String, List<String>> typeEdges =
            new LinkedHashMap<>();
        /** canonical source URI &rarr; source path (edge-to-node
         * projection). */
        private final Map<String, String> sourcePathByUri =
            new LinkedHashMap<>();

        private final List<DefaultResourceOccurrence>
            ordinaryOccurrences = new ArrayList<>();
        private final List<ProvisionalEdge> mergedEdges =
            new ArrayList<>();
        private final Set<ProvisionalEdge> mergedSeen =
            new LinkedHashSet<>();
        private final List<CompilerDiagnostic> diagnostics =
            new ArrayList<>();
        private boolean failed = false;

        Pass(Map<String, ModuleInput> modules,
                Map<String, List<PlannedDefaultClass>> plannedClasses,
                Map<String, Map<String, Span>> importEdgeSpans,
                Map<String, Span> programSpans) {
            this.modules.putAll(modules);
            this.plannedClasses.putAll(plannedClasses);
            this.importEdgeSpans = importEdgeSpans;
            this.programSpans = programSpans;
            for (Map.Entry<String, ModuleInput> entry : modules.entrySet()) {
                ModuleInput input = entry.getValue();
                List<String> targets = new ArrayList<>();
                for (DefaultPlanImport provider
                        : input.imports().values()) {
                    if (modules.containsKey(provider.sourcePath())) {
                        targets.add(provider.sourcePath());
                    }
                }
                typeEdges.put(entry.getKey(), targets);
                sourcePathByUri.put(input.location().semanticModuleIdentity()
                    .canonicalResolvedSourceUri(), entry.getKey());
            }
        }

        DigestFreeResult run() {
            collectOrdinaryOccurrences();
            collectDefaultEdges();
            classifyCycles();
            if (failed) {
                return new DigestFreeResult(diagnostics, List.of(),
                    List.of(), List.of(), true);
            }
            List<String> order = initializationOrder(
                new ArrayList<>(modules.keySet()), typeEdges);
            return new DigestFreeResult(diagnostics, mergedEdges,
                ordinaryOccurrences, order, false);
        }

        /**
         * The ordinary runtime-use occurrences (D7(a)): the complete
         * typed evaluator IR walk of every top-level function body of
         * every implementation module through the shared
         * {@link DefaultIrRecorder}, with the E3020 gate inert (bodies
         * walk at non-evaluator scope — await inside them is legal).
         * One occurrence per imported resource identity per module,
         * first-occurrence order (the recorder's deduplication).
         */
        private void collectOrdinaryOccurrences() {
            for (ModuleInput input : modules.values()) {
                if (input.isDeclarationFile()
                        || input.checkResult() == null
                        || input.nameResolver() == null) {
                    continue;
                }
                DefaultIrRecorder recorder = new DefaultIrRecorder(
                    input.checkResult().typeMap(),
                    input.checkResult().scopeMap(), input.imports(),
                    input.classification(), input.location(),
                    input.modulePath(), input.program(),
                    (typeNode, scope) -> {
                        input.nameResolver().setCurrentScope(scope);
                        return input.nameResolver()
                            .resolveTypeNode(typeNode);
                    },
                    Map.of());
                for (StatementNode stmt : input.program().statements()) {
                    StatementNode declaration =
                        stmt instanceof ExportDeclaration ed
                            ? ed.declaration() : stmt;
                    if (declaration instanceof FunctionDeclaration fd
                            && fd.body() != null) {
                        recorder.recordFunctionBody(fd,
                            input.checkResult().symbolTable());
                    }
                    // Class declarations publish no ordinary edge: their
                    // defaults are the planner's DEFERRED_DEFAULT_BINDING
                    // edges (D7(a) — "not type positions and not
                    // defaults").
                }
                for (DefaultResourceOccurrence occurrence
                        : recorder.occurrences()) {
                    ordinaryOccurrences.add(occurrence);
                    ProvisionalEdge edge = edgeOf(occurrence,
                        RuntimeImportDependency.Reason.RUNTIME_USE);
                    if (mergedSeen.add(edge)) {
                        mergedEdges.add(edge);
                    }
                }
            }
        }

        /** The planner's default edges (D7(b)), merged after the
         * ordinary set in module/plan order. */
        private void collectDefaultEdges() {
            for (Map.Entry<String, List<PlannedDefaultClass>> entry
                    : plannedClasses.entrySet()) {
                for (PlannedDefaultClass planned : entry.getValue()) {
                    for (DefaultResourceOccurrence occurrence
                            : planned.occurrences()) {
                        ProvisionalEdge edge = edgeOf(occurrence,
                            RuntimeImportDependency.Reason
                                .DEFERRED_DEFAULT_BINDING);
                        if (mergedSeen.add(edge)) {
                            mergedEdges.add(edge);
                        }
                    }
                }
            }
        }

        private static ProvisionalEdge edgeOf(
                DefaultResourceOccurrence occurrence,
                RuntimeImportDependency.Reason reason) {
            return ModuleDependencyGraph.edgeOf(occurrence, reason);
        }

        /**
         * Tarjan over the import-edge graph; every SCC containing at
         * least one runtime edge (either reason) emits one E2005
         * diagnostic with a note per runtime edge inside the SCC.
         * Runtime SCCs are reported in discovery order of their first
         * member.
         */
        private void classifyCycles() {
            List<List<String>> sccs = stronglyConnectedComponents(
                new ArrayList<>(modules.keySet()), typeEdges);
            List<List<String>> runtimeSccs = new ArrayList<>();
            for (List<String> scc : sccs) {
                if (scc.isEmpty()) {
                    continue;
                }
                Set<String> members = new LinkedHashSet<>(scc);
                List<ProvisionalEdge> edgesInScc = new ArrayList<>();
                for (ProvisionalEdge edge : mergedEdges) {
                    if (members.contains(sourcePathByUri.get(
                            edge.from().canonicalResolvedSourceUri()))
                            && members.contains(sourcePathByUri.get(
                                edge.to().canonicalResolvedSourceUri()))) {
                        edgesInScc.add(edge);
                    }
                }
                if (!edgesInScc.isEmpty()) {
                    runtimeSccs.add(scc);
                }
            }
            if (runtimeSccs.isEmpty()) {
                return;
            }
            failed = true;
            // Deterministic report order: first member's discovery
            // index ascending.
            List<String> nodes = new ArrayList<>(modules.keySet());
            runtimeSccs.sort((a, b) -> Integer.compare(
                nodes.indexOf(a.get(0)), nodes.indexOf(b.get(0))));
            for (List<String> scc : runtimeSccs) {
                diagnostics.add(runtimeCycleDiagnostic(scc));
            }
        }

        /**
         * One E2005 for a runtime SCC: the pinned anchor chain (the
         * landed {@link CompilationOrchestrator#e2005Diagnostic}
         * helper — import declaration span of the first cycle module
         * targeting another cycle member, then that module's program
         * span, then the canonical synthetic shape) plus one note per
         * runtime edge inside the SCC carrying
         * {@code (reason, from &rarr; to, sourceRange)}.
         */
        private CompilerDiagnostic runtimeCycleDiagnostic(
                List<String> cycle) {
            String chain = String.join(" -> ", cycle) + " -> "
                + cycle.get(0);
            String message = "Circular import with runtime dependency: "
                + chain;
            CompilerDiagnostic anchored =
                CompilationOrchestrator.e2005Diagnostic(cycle,
                    importEdgeSpans, programSpans, message);
            List<DiagnosticNote> notes =
                new ArrayList<>(anchored.notes());
            Set<String> members = new LinkedHashSet<>(cycle);
            for (ProvisionalEdge edge : mergedEdges) {
                String fromPath = sourcePathByUri.get(edge.from()
                    .canonicalResolvedSourceUri());
                String toPath = sourcePathByUri.get(edge.to()
                    .canonicalResolvedSourceUri());
                if (fromPath == null || toPath == null
                        || !members.contains(fromPath)
                        || !members.contains(toPath)) {
                    continue;
                }
                notes.add(new DiagnosticNote("runtime edge "
                    + edge.reason() + ": " + fromPath + " -> " + toPath,
                    edge.sourceRange()));
            }
            return new CompilerDiagnostic(anchored.code(),
                anchored.severity(), anchored.message(), anchored.range(),
                notes, anchored.diagnosticCode());
        }

        /**
         * Tarjan's strongly connected components over the given nodes
         * and ordered adjacency lists; every returned member list is
         * in the node order. Deterministic: nodes are visited in the
         * given order and adjacency lists keep their insertion order.
         */
        private static List<List<String>> stronglyConnectedComponents(
                List<String> nodes, Map<String, List<String>> edges) {
            Map<String, Integer> index = new LinkedHashMap<>();
            Map<String, Integer> lowlink = new LinkedHashMap<>();
            Deque<String> stack = new ArrayDeque<>();
            Set<String> onStack = new HashSet<>();
            List<List<String>> sccs = new ArrayList<>();
            int[] counter = {0};
            for (String node : nodes) {
                if (!index.containsKey(node)) {
                    strongconnect(node, nodes, edges, index, lowlink,
                        stack, onStack, sccs, counter);
                }
            }
            return sccs;
        }

        private static void strongconnect(String node, List<String> nodes,
                Map<String, List<String>> edges,
                Map<String, Integer> index, Map<String, Integer> lowlink,
                Deque<String> stack, Set<String> onStack,
                List<List<String>> sccs, int[] counter) {
            index.put(node, counter[0]);
            lowlink.put(node, counter[0]);
            counter[0]++;
            stack.push(node);
            onStack.add(node);
            for (String target : edges.getOrDefault(node, List.of())) {
                if (!nodes.contains(target)) {
                    continue;
                }
                if (!index.containsKey(target)) {
                    strongconnect(target, nodes, edges, index, lowlink,
                        stack, onStack, sccs, counter);
                    lowlink.put(node, Math.min(lowlink.get(node),
                        lowlink.get(target)));
                } else if (onStack.contains(target)) {
                    lowlink.put(node, Math.min(lowlink.get(node),
                        index.get(target)));
                }
            }
            if (lowlink.get(node).equals(index.get(node))) {
                List<String> members = new ArrayList<>();
                String member;
                do {
                    member = stack.pop();
                    onStack.remove(member);
                    members.add(member);
                } while (!member.equals(node));
                // Node order for determinism.
                List<String> ordered = new ArrayList<>();
                for (String candidate : nodes) {
                    if (members.contains(candidate)) {
                        ordered.add(candidate);
                    }
                }
                sccs.add(List.copyOf(ordered));
            }
        }
    }

    // =========================================================================
    // Initialization order (the one shared ordering algorithm)
    // =========================================================================

    /**
     * The one shared initialization ordering: Kahn over the import
     * edges in node (module discovery) order; when no module is ready,
     * exactly the remaining cycle members (computed on the remaining
     * subgraph) are appended in node order and the sweep resumes.
     * Modules reachable only from a cycle are appended once the cycle
     * members exist, so an importer never precedes its imported module.
     * The orchestrator's phase-2 check order and the graph's
     * initialization order both derive from this method, so they are
     * byte-identical for every compilation.
     *
     * @param nodes     the module source paths in discovery order
     * @param typeEdges source path &rarr; the ordered resolved import
     *                  targets
     * @return the initialization order over every node
     */
    public static List<String> initializationOrder(
            List<String> nodes,
            Map<String, ? extends Collection<String>> typeEdges) {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(typeEdges, "typeEdges");
        List<String> order = new ArrayList<>();
        Set<String> remaining = new LinkedHashSet<>(nodes);
        while (!remaining.isEmpty()) {
            boolean progress = false;
            for (java.util.Iterator<String> it =
                    remaining.iterator(); it.hasNext(); ) {
                String node = it.next();
                Collection<String> deps = typeEdges.get(node);
                if (deps == null) {
                    deps = List.of();
                }
                boolean ready = deps.stream().allMatch(order::contains);
                if (ready) {
                    order.add(node);
                    it.remove();
                    progress = true;
                }
            }
            if (!progress) {
                Set<String> cycleMembers =
                    cycleMembersWithin(remaining, typeEdges);
                if (cycleMembers.isEmpty()) {
                    // Defensive: broken dependency references to nodes
                    // outside the module set; append the leftovers in
                    // node order (the old leftover fallback).
                    for (String node : remaining) {
                        order.add(node);
                    }
                    break;
                }
                for (java.util.Iterator<String> it =
                        remaining.iterator(); it.hasNext(); ) {
                    String node = it.next();
                    if (cycleMembers.contains(node)) {
                        order.add(node);
                        it.remove();
                    }
                }
            }
        }
        return List.copyOf(order);
    }

    /**
     * The members of every cycle (SCC of size &gt; 1 or a self-import)
     * among the given remaining nodes, in node order.
     */
    private static Set<String> cycleMembersWithin(Set<String> remaining,
            Map<String, ? extends Collection<String>> typeEdges) {
        List<String> nodes = new ArrayList<>(remaining);
        Map<String, Integer> index = new LinkedHashMap<>();
        Map<String, Integer> lowlink = new LinkedHashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        List<List<String>> sccs = new ArrayList<>();
        int[] counter = {0};
        for (String node : nodes) {
            if (!index.containsKey(node)) {
                strongconnectSet(node, remaining, typeEdges, index,
                    lowlink, stack, onStack, sccs, counter);
            }
        }
        Set<String> members = new LinkedHashSet<>();
        for (List<String> scc : sccs) {
            boolean cyclic = scc.size() > 1;
            if (!cyclic) {
                String node = scc.get(0);
                Collection<String> selfDeps = typeEdges.get(node);
                cyclic = selfDeps != null
                    && selfDeps.contains(node);
            }
            if (cyclic) {
                members.addAll(scc);
            }
        }
        return members;
    }

    /** Tarjan over a node-set restriction (the remaining subgraph). */
    private static void strongconnectSet(String node,
            Set<String> allowed,
            Map<String, ? extends Collection<String>> typeEdges,
            Map<String, Integer> index, Map<String, Integer> lowlink,
            Deque<String> stack, Set<String> onStack,
            List<List<String>> sccs, int[] counter) {
        index.put(node, counter[0]);
        lowlink.put(node, counter[0]);
        counter[0]++;
        stack.push(node);
        onStack.add(node);
        Collection<String> targets = typeEdges.get(node);
        if (targets == null) {
            targets = List.of();
        }
        for (String target : targets) {
            if (!allowed.contains(target)) {
                continue;
            }
            if (!index.containsKey(target)) {
                strongconnectSet(target, allowed, typeEdges, index,
                    lowlink, stack, onStack, sccs, counter);
                lowlink.put(node, Math.min(lowlink.get(node),
                    lowlink.get(target)));
            } else if (onStack.contains(target)) {
                lowlink.put(node, Math.min(lowlink.get(node),
                    index.get(target)));
            }
        }
        if (lowlink.get(node).equals(index.get(node))) {
            List<String> members = new ArrayList<>();
            String member;
            do {
                member = stack.pop();
                onStack.remove(member);
                members.add(member);
            } while (!member.equals(node));
            sccs.add(List.copyOf(members));
        }
    }

    /**
     * The provisional edge of one occurrence under the given reason.
     */
    private static ProvisionalEdge edgeOf(
            DefaultResourceOccurrence occurrence,
            RuntimeImportDependency.Reason reason) {
        return new ProvisionalEdge(occurrence.kind(),
            occurrence.fromSemanticModuleIdentity(),
            occurrence.toSemanticModuleIdentity(),
            occurrence.importAlias(),
            occurrence.semanticResourceIdentity(),
            occurrence.sourceRange(), reason);
    }

    // =========================================================================
    // Final publication (digest-bearing records)
    // =========================================================================

    /**
     * Publishes the final digest-bearing dependency records of a
     * successful digest-free pass: one {@link RuntimeImportDependency}
     * per provisional edge with its provider digest resolved from the
     * serializer's demanded-digest map (a missing digest is a broken
     * wiring defect and fails with an {@link IllegalStateException} —
     * never a placeholder), and one completed plan per planned class
     * with its {@code runtimeDependencies} filled through
     * {@link CompilerClassDefaultPlan#withRuntimeDependencies} while
     * the serializer-completed entry content stays byte-identical.
     *
     * @param free               the successful digest-free result
     * @param plannedClasses     module source path &rarr; the planned
     *                           classes (the planner output)
     * @param serializedClasses  module source path &rarr; the
     *                           serializer-completed classes (one per
     *                           planned class, same order)
     * @param providerDigests    semantic resource identity &rarr; the
     *                           demanded provider contract digest
     * @return the merged final records and the completed plans
     */
    public static PublishedGraph finalizeGraph(
            DigestFreeResult free,
            Map<String, List<PlannedDefaultClass>> plannedClasses,
            Map<String, List<DefaultSemanticSerializer
                .SerializedDefaultClass>> serializedClasses,
            Map<SemanticResourceIdentity, String> providerDigests) {
        Objects.requireNonNull(free, "free");
        Objects.requireNonNull(plannedClasses, "plannedClasses");
        Objects.requireNonNull(serializedClasses, "serializedClasses");
        Objects.requireNonNull(providerDigests, "providerDigests");
        if (free.failed()) {
            throw new IllegalArgumentException(
                "finalization requires a successful digest-free pass"
                    + " (a runtime SCC publishes nothing)");
        }
        List<RuntimeImportDependency> merged = new ArrayList<>();
        Set<RuntimeImportDependency> seen = new LinkedHashSet<>();
        // Ordinary records first (first-occurrence order), then the
        // per-plan default records (module/plan order); structural
        // deduplication keeps one record per exact carrier content.
        for (ProvisionalEdge edge : free.provisionalEdges()) {
            if (edge.reason()
                    != RuntimeImportDependency.Reason.RUNTIME_USE) {
                continue;
            }
            RuntimeImportDependency record = recordOf(edge,
                providerDigests);
            if (seen.add(record)) {
                merged.add(record);
            }
        }
        Map<String, List<PlannedDefaultClass>> completed =
            new LinkedHashMap<>();
        for (Map.Entry<String, List<PlannedDefaultClass>> entry
                : plannedClasses.entrySet()) {
            String sourcePath = entry.getKey();
            List<PlannedDefaultClass> plannedList = entry.getValue();
            List<DefaultSemanticSerializer.SerializedDefaultClass>
                serializedList = serializedClasses.get(sourcePath);
            if (serializedList == null
                    || serializedList.size() != plannedList.size()) {
                throw new IllegalStateException(
                    "the serializer output for module '" + sourcePath
                        + "' does not match the planned classes ("
                        + (serializedList == null ? "missing" : Integer
                            .toString(serializedList.size()))
                        + " serialized vs " + plannedList.size()
                        + " planned) — broken wiring");
            }
            List<PlannedDefaultClass> moduleCompleted =
                new ArrayList<>();
            for (int i = 0; i < plannedList.size(); i++) {
                PlannedDefaultClass planned = plannedList.get(i);
                DefaultSemanticSerializer.SerializedDefaultClass
                    serialized = serializedList.get(i);
                Set<RuntimeImportDependency> planDependencies =
                    new LinkedHashSet<>();
                for (DefaultResourceOccurrence occurrence
                        : planned.occurrences()) {
                    RuntimeImportDependency record =
                        recordOf(edgeOf(occurrence,
                            RuntimeImportDependency.Reason
                                .DEFERRED_DEFAULT_BINDING),
                            providerDigests);
                    if (planDependencies.add(record)
                            && seen.add(record)) {
                        merged.add(record);
                    }
                }
                CompilerClassDefaultPlan completedPlan = serialized
                    .completedPlan().withRuntimeDependencies(
                        planDependencies);
                moduleCompleted.add(new PlannedDefaultClass(
                    completedPlan, planned.declaration(),
                    planned.occurrences()));
            }
            completed.put(sourcePath, List.copyOf(moduleCompleted));
        }
        return new PublishedGraph(merged, completed);
    }

    /**
     * The final digest-bearing record of one provisional edge: the
     * provider digest must exist in the demanded-digest map (the
     * orchestrator demands every provisional edge's digest through the
     * serializer before finalization); anything else is broken wiring.
     */
    private static RuntimeImportDependency recordOf(
            ProvisionalEdge edge,
            Map<SemanticResourceIdentity, String> providerDigests) {
        String digest = providerDigests.get(
            edge.semanticResourceIdentity());
        if (digest == null) {
            throw new IllegalStateException(
                "no provider digest was demanded for the runtime edge"
                    + " (" + edge.kind() + ", '"
                    + edge.semanticResourceIdentity()
                        .lexicalDeclarationIdentity().declaredName()
                    + "', " + edge.reason() + ") — broken wiring"
                    + " (the serializer demand must precede"
                    + " finalization)");
        }
        return new RuntimeImportDependency(edge.from(), edge.to(),
            edge.importAlias(), edge.semanticResourceIdentity(), digest,
            edge.sourceRange(), edge.reason());
    }
}
