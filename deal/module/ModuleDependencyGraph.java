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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
     */
    public record DigestFreeResult(
        List<CompilerDiagnostic> diagnostics,
        List<ProvisionalEdge> provisionalEdges,
        List<DefaultResourceOccurrence> ordinaryOccurrences,
        List<String> initializationOrder,
        boolean failed,
        Map<String, Map<DiagnosticRange, String>> classLiteralProviders
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
            classLiteralProviders = Map.copyOf(Objects.requireNonNull(
                classLiteralProviders, "classLiteralProviders"));
        }
    }

    /**
     * The published graph of an acyclic compilation: the merged final
     * digest-bearing dependency records and the completed plans
     * (every plan's {@code runtimeDependencies} filled with the
     * serializer-completed entry content preserved).
     *
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
     * one E2005 diagnostic per cyclic component (every multi-module SCC
     * and self-import) with a note for every runtime edge inside it. No
     * provider digest is computed or requested anywhere in this pass.
     *
     * <p>The orchestrator rejects the complete resolved import graph
     * before ordered checking ({@link #importCycleDiagnostics}), so a
     * cyclic graph reaching this pass is a producer defect the pass
     * still fails closed on rather than tolerating a
     * declaration-only/type-only exception.</p>
     *
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

        /**
         * source path &rarr; the module walk's imported class-literal
         * providers (literal range &rarr; import alias), one entry per
         * constructed literal of every top-level function body. The
         * emitters consume it so an imported construction references the
         * exact recorded provider even when its resource occurrence was
         * deduplicated at an earlier literal (GitHub #6).
         */
        private final Map<String, Map<DiagnosticRange, String>>
            classLiteralProviders = new LinkedHashMap<>();
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
                    List.of(), List.of(), true, Map.of());
            }
            List<String> order = initializationOrder(
                new ArrayList<>(modules.keySet()), typeEdges);
            return new DigestFreeResult(diagnostics, mergedEdges,
                ordinaryOccurrences, order, false, classLiteralProviders);
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
                Map<DiagnosticRange, String> providers =
                    recorder.classLiteralProviders();
                if (!providers.isEmpty()) {
                    classLiteralProviders
                        .computeIfAbsent(input.sourcePath(),
                            key -> new LinkedHashMap<>())
                        .putAll(providers);
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
         * Tarjan over the import-edge graph; every cyclic component
         * (every multi-module SCC and every self-import) emits one
         * E2005 diagnostic carrying the reproducible cycle path, plus
         * a note per runtime edge inside the component. Components are
         * reported in discovery order of their first member.
         */
        private void classifyCycles() {
            List<String> nodes = new ArrayList<>(modules.keySet());
            List<List<String>> cycles =
                ModuleDependencyGraph.importCycles(nodes, typeEdges);
            if (cycles.isEmpty()) {
                return;
            }
            failed = true;
            for (List<String> component : cycles) {
                List<String> path =
                    ModuleDependencyGraph.cyclePath(component, typeEdges);
                diagnostics.add(runtimeCycleDiagnostic(component, path));
            }
        }

        private CompilerDiagnostic runtimeCycleDiagnostic(
                List<String> component, List<String> path) {
            String chain = String.join(" -> ", path);
            String message = "Circular import: " + chain;
            CompilerDiagnostic anchored =
                CompilationOrchestrator.e2005Diagnostic(path,
                    importEdgeSpans, programSpans, message);
            List<DiagnosticNote> notes =
                new ArrayList<>(anchored.notes());
            Set<String> members = new LinkedHashSet<>(component);
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
    }

    // =========================================================================
    // Complete import-graph cycle admission
    // =========================================================================

    /**
     * The cyclic strongly connected components of one import graph:
     * every SCC with more than one member plus every self-import, in
     * deterministic discovery order of the component's first member
     * (node order). Each returned component lists its members in node
     * order.
     *
     * <p>Edges are the complete resolved import graph (implementation,
     * declaration and type-only imports alike); a reference to a node
     * outside {@code nodes} is ignored as a broken dependency
     * reference. Deterministic: nodes and adjacency lists keep their
     * given order.</p>
     */
    public static List<List<String>> importCycles(List<String> nodes,
            Map<String, ? extends Collection<String>> edges) {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(edges, "edges");
        Map<String, Integer> nodeIndex = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            nodeIndex.put(nodes.get(i), i);
        }
        List<List<String>> cycles = new ArrayList<>();
        for (List<String> component
                : stronglyConnectedComponents(nodes, edges)) {
            boolean cyclic = component.size() > 1;
            if (!cyclic && !component.isEmpty()) {
                Collection<String> selfDeps = edges.get(component.get(0));
                cyclic = selfDeps != null
                    && selfDeps.contains(component.get(0));
            }
            if (cyclic) {
                cycles.add(component);
            }
        }
        cycles.sort((a, b) -> Integer.compare(
            nodeIndex.getOrDefault(a.get(0), Integer.MAX_VALUE),
            nodeIndex.getOrDefault(b.get(0), Integer.MAX_VALUE)));
        return List.copyOf(cycles);
    }

    /**
     * One reproducible concrete cycle path inside a cyclic component:
     * the shortest path {@code start -> ... -> start} through the
     * component's edges (start is the component's first member in node
     * order), rendered as {@code [start, ..., closing, start]}.
     * Deterministic by node and adjacency order. Falls back to the
     * component plus its first member when no witness is derivable (a
     * defensive shape: a cyclic SCC always derives one).
     */
    public static List<String> cyclePath(List<String> members,
            Map<String, ? extends Collection<String>> edges) {
        Objects.requireNonNull(members, "members");
        Objects.requireNonNull(edges, "edges");
        if (members.isEmpty()) {
            return List.of();
        }
        String start = members.get(0);
        Set<String> memberSet = new LinkedHashSet<>(members);
        Map<String, String> parent = new LinkedHashMap<>();
        Map<String, Integer> depth = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        depth.put(start, 0);
        queue.add(start);
        String closingFrom = null;
        while (!queue.isEmpty() && closingFrom == null) {
            String node = queue.poll();
            Collection<String> targets = edges.get(node);
            if (targets == null) {
                continue;
            }
            for (String target : targets) {
                if (!memberSet.contains(target)) {
                    continue;
                }
                if (target.equals(start)) {
                    closingFrom = node;
                    break;
                }
                if (!depth.containsKey(target)) {
                    depth.put(target, depth.get(node) + 1);
                    parent.put(target, node);
                    queue.add(target);
                }
            }
        }
        if (closingFrom == null) {
            List<String> fallback = new ArrayList<>(members);
            fallback.add(start);
            return List.copyOf(fallback);
        }
        LinkedList<String> path = new LinkedList<>();
        String cursor = closingFrom;
        while (cursor != null) {
            path.addFirst(cursor);
            cursor = parent.get(cursor);
        }
        path.add(start);
        return List.copyOf(path);
    }

    /**
     * The E2005 diagnostics of the complete resolved import graph: one
     * per cyclic component (multi-module SCC or self-import) in
     * deterministic discovery order, anchored at the first actual
     * import declaration range inside the cycle, carrying the
     * reproducible cycle path. Empty exactly when the graph is
     * acyclic. The orchestrator runs this before ordered
     * checking/lowering; no plan or artifact is published on failure.
     */
    public static List<CompilerDiagnostic> importCycleDiagnostics(
            List<String> nodes,
            Map<String, ? extends Collection<String>> edges,
            Map<String, Map<String, Span>> edgeSpans,
            Map<String, Span> programSpans) {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(edges, "edges");
        Objects.requireNonNull(edgeSpans, "edgeSpans");
        Objects.requireNonNull(programSpans, "programSpans");
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        for (List<String> component : importCycles(nodes, edges)) {
            List<String> path = cyclePath(component, edges);
            diagnostics.add(CompilationOrchestrator.e2005Diagnostic(path,
                edgeSpans, programSpans,
                "Circular import: " + String.join(" -> ", path)));
        }
        return List.copyOf(diagnostics);
    }

    /**
     * Tarjan's strongly connected components over the given nodes and
     * ordered adjacency lists; every returned member list is in node
     * order. Deterministic: nodes are visited in the given order and
     * adjacency lists keep their insertion order. References outside
     * the node set are ignored.
     */
    private static List<List<String>> stronglyConnectedComponents(
            List<String> nodes,
            Map<String, ? extends Collection<String>> edges) {
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
            Map<String, ? extends Collection<String>> edges,
            Map<String, Integer> index, Map<String, Integer> lowlink,
            Deque<String> stack, Set<String> onStack,
            List<List<String>> sccs, int[] counter) {
        index.put(node, counter[0]);
        lowlink.put(node, counter[0]);
        counter[0]++;
        stack.push(node);
        onStack.add(node);
        Collection<String> targets = edges.get(node);
        if (targets == null) {
            targets = List.of();
        }
        for (String target : targets) {
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

    // =========================================================================
    // Initialization order (the one shared ordering algorithm)
    // =========================================================================

    /**
     * The one shared initialization ordering: Kahn over the import
     * edges in node (module discovery) order. The caller must supply an
     * acyclic graph — every multi-module SCC and self-import is
     * rejected with E2005 before ordering
     * ({@link #importCycleDiagnostics}) — and a stalled sweep is
     * therefore a producer defect, not a tolerated cycle: the method
     * fails closed with an {@link IllegalStateException} instead of
     * silently ordering cycle members. Modules reachable only from a
     * cycle cannot occur on this contract, so an importer never
     * precedes its imported module. The orchestrator's phase-2 check
     * order and the graph's initialization order both derive from this
     * method, so they are byte-identical for every compilation.
     *
     */
    public static List<String> initializationOrder(
            List<String> nodes,
            Map<String, ? extends Collection<String>> typeEdges) {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(typeEdges, "typeEdges");
        Set<String> nodeSet = new LinkedHashSet<>(nodes);
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
                // A reference outside the node set is a broken
                // dependency reference (defensive): it can never
                // become ready and is ignored, exactly as the SCC pass
                // ignores it.
                boolean ready = deps.stream().filter(nodeSet::contains)
                    .allMatch(order::contains);
                if (ready) {
                    order.add(node);
                    it.remove();
                    progress = true;
                }
            }
            if (!progress) {
                throw new IllegalStateException(
                    "dependency ordering requires an acyclic import graph"
                        + " (every multi-module SCC and self-import is"
                        + " rejected with E2005 before ordering);"
                        + " remaining modules: "
                        + new ArrayList<>(remaining));
            }
        }
        return List.copyOf(order);
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
