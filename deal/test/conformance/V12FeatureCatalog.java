package deal.test.conformance;

import deal.ast.ExportDeclaration;
import deal.ast.FunctionDeclaration;
import deal.ast.ImportDeclaration;
import deal.ast.NamedType;
import deal.ast.ProgramNode;
import deal.ast.StatementNode;
import deal.lexer.CompilerDirective;
import deal.lexer.Lexer;
import deal.lexer.LexResult;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.semantic.ir.SemanticProfile;
import deal.test.conformance.FeatureBackendMatrix.MatrixFailure;
import deal.test.conformance.V12FeatureMetadata.Invocation;
import deal.test.conformance.V12FeatureMetadata.Metadata;
import deal.test.conformance.V12FeatureMetadata.Oracle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.nio.file.FileVisitOption;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The strict v1.2 feature catalog loader (ISSUE-0157; design source
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D12's
 * {@code V12FeatureFixtureCatalog}).
 *
 * <p>Loads one feature catalog directory — sidecar documents
 * ({@code *.feature.json}) plus their unchanged fixture sources
 * ({@code *.deal}) — and validates the whole catalog before any
 * compilation: strict metadata parsing
 * ({@link V12FeatureMetadata}), the architecture-owned backend matrix
 * ({@link FeatureBackendMatrix}), the exact root/support closure, and
 * the production metadata-rejection surface.</p>
 *
 * <p>Catalog closure is exact (D12):</p>
 * <ul>
 *   <li>The canonical feature-record id of one sidecar is its
 *       catalog-relative path with the {@code .feature.json} suffix
 *       stripped; its root source is {@code <id>.deal}.</li>
 *   <li>A sidecar whose root source does not exist, and two records
 *       claiming the same source (duplicate canonical aliases), are
 *       catalog failures.</li>
 *   <li>Every discovered source file must be owned: a root source (its
 *       record exists) or listed in at least one record's
 *       {@code support} — an unlisted source is a catalog failure.</li>
 *   <li>Every {@code support} entry must resolve to an existing source
 *       under the catalog root (an unresolved support edge is a
 *       failure), must not name the record's own root source, and must
 *       be a transitively imported catalog module of the record's root
 *       source (an unused support entry is a failure).</li>
 *   <li>Every catalog-internal module transitively imported by a
 *       record's root source must be listed in that record's
 *       {@code support} — including a rooted module, which joins a
 *       record's support graph only through explicit dual-role
 *       ownership (its own root record plus every importer listing it).
 *       A support-only source must therefore be reachable from a root's
 *       support graph.</li>
 * </ul>
 *
 * <p>Fixture sources are read-only inputs: the loader never rewrites a
 * single fixture byte (unchanged fixture-source handling).</p>
 *
 * <p>Production metadata rejection (acceptance criterion 3): every
 * fixture source of a record's graph — the root source and each
 * resolved/walked support source — is scanned with the real E5 lexer;
 * a directive-shaped {@code // @spec:} comment is E1044 in production,
 * so a fixture carrying one is a catalog failure — metadata is never
 * stripped or whitelisted.</p>
 *
 * <p>Source-project main requirements (D12): a {@code direct-main} or
 * {@code async-export} record's root source must export a non-async
 * zero-parameter {@code main(): null}; a {@code synthetic-main} or
 * {@code async-export} record's root source must export the pinned
 * oracle name as a zero-parameter function. A compile-error record
 * whose root source is a declaration file ({@code .d.deal}) requires a
 * generated entry (declaration files cannot be production entries); the
 * validated record exposes this so the execution surface never guesses.</p>
 *
 * <p>The loader is deterministic: sorted discovery walks, fixed check
 * order, and a complete failure list — never a fail-fast shortcut and
 * never a silent default.</p>
 */
public final class V12FeatureCatalog {

    /** The sidecar suffix identifying one feature-record document. */
    public static final String SIDECAR_SUFFIX = ".feature.json";

    /** The DEAL source suffixes of catalog fixtures (root and support). */
    public static final List<String> SOURCE_SUFFIXES =
        List.of(".deal", ".d.deal");

    /** The unknown directive name of production {@code // @spec:} (E1044). */
    public static final String SPEC_DIRECTIVE_NAME = "spec";

    private V12FeatureCatalog() {
        // Static utility; no instances.
    }

    // =========================================================================
    // Result types
    // =========================================================================

    /**
     * One catalog failure: the record id (or {@code "<catalog>"} for
     * catalog-level failures), the offending field, and the exact
     * reason.
     */
    public record CatalogFailure(String recordId, String field, String reason) {

        public CatalogFailure {
            Objects.requireNonNull(recordId, "recordId must not be null");
            Objects.requireNonNull(field, "field must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }

        /** The gate-readable failure line: record, field, reason. */
        public String message() {
            return recordId + ": " + field + ": " + reason;
        }

        @Override
        public String toString() {
            return message();
        }
    }

    /**
     * The validated invocation plan of one record — enough information
     * for later production direct-main, generated typed synthetic-main,
     * and {@code BackendAsyncExportInvoker} execution without
     * test-runner export guessing (acceptance criterion 4).
     */
    public record InvocationInfo(
        Invocation mode,
        List<String> backends,
        Optional<Oracle> oracle,
        Optional<String> linkedRecordTarget,
        boolean requiresMain,
        boolean needsGeneratedEntry
    ) {

        public InvocationInfo {
            Objects.requireNonNull(mode, "mode must not be null");
            Objects.requireNonNull(backends, "backends must not be null");
            backends = List.copyOf(backends);
            oracle = Objects.requireNonNull(oracle, "oracle must not be null");
            linkedRecordTarget = Objects.requireNonNull(linkedRecordTarget,
                "linkedRecordTarget must not be null");
        }
    }

    /**
     * One fully validated feature record: the canonical id, the parsed
     * metadata, the absolute sidecar and root-source paths, the
     * absolute support sources in sidecar order, and the validated
     * invocation plan.
     */
    public record ValidatedRecord(
        String id,
        Metadata metadata,
        Path sidecarPath,
        Path sourcePath,
        List<Path> supportSources,
        InvocationInfo invocationInfo
    ) {

        public ValidatedRecord {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(metadata, "metadata must not be null");
            Objects.requireNonNull(sidecarPath, "sidecarPath must not be null");
            Objects.requireNonNull(sourcePath, "sourcePath must not be null");
            Objects.requireNonNull(supportSources, "supportSources must not be null");
            supportSources = List.copyOf(supportSources);
            Objects.requireNonNull(invocationInfo, "invocationInfo must not be null");
        }

        /**
         * The canonical return descriptor ({@code R}) the oracle's
         * completion must match, when an oracle exists.
         */
        public Optional<String> oracleReturnDescriptor() {
            return invocationInfo.oracle().map(Oracle::returnDescriptor);
        }
    }

    /**
     * The complete load result: the absolute catalog root, the complete
     * failure list (empty exactly when the catalog validates clean),
     * and the validated records (populated only when clean — a catalog
     * failure starts no compiler and yields no record set).
     */
    public record CatalogResult(
        Path catalogRoot,
        List<CatalogFailure> failures,
        List<ValidatedRecord> records
    ) {

        public CatalogResult {
            Objects.requireNonNull(catalogRoot, "catalogRoot must not be null");
            Objects.requireNonNull(failures, "failures must not be null");
            failures = List.copyOf(failures);
            Objects.requireNonNull(records, "records must not be null");
            records = List.copyOf(records);
        }

        /** True exactly when the whole catalog validated clean. */
        public boolean valid() {
            return failures.isEmpty();
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Loads and validates one feature catalog directory. The walk is
     * sorted and the check order fixed, so the result is deterministic
     * for any given on-disk state.
     *
     * @param catalogRoot the catalog root directory (non-null)
     * @return the complete {@link CatalogResult} — never {@code null},
     *         never an exception
     */
    public static CatalogResult load(Path catalogRoot) {
        Objects.requireNonNull(catalogRoot, "catalogRoot must not be null");
        Path root = catalogRoot.toAbsolutePath().normalize();
        List<CatalogFailure> failures = new ArrayList<>();
        Map<String, ValidatedRecord> records = new TreeMap<>();

        // ---- Discovery (sorted) ----
        List<Path> sidecars = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        // The walk follows symlinks: a symlinked sidecar/source alias is
        // discovered, and the duplicate-canonical-alias checks below
        // compare real paths so two spellings of one file can never be
        // owned twice.
        try (Stream<Path> walk = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                if (name.endsWith(SIDECAR_SUFFIX)) {
                    sidecars.add(path);
                } else if (name.endsWith(".deal")) {
                    sources.add(path);
                }
            });
        } catch (IOException e) {
            return new CatalogResult(root,
                List.of(new CatalogFailure("<catalog>", "<walk>",
                    "cannot walk the feature catalog: " + e.getMessage())),
                List.of());
        }
        sidecars.sort(Comparator.naturalOrder());
        sources.sort(Comparator.naturalOrder());

        // ---- Strict sidecar parsing (fixed record order) ----
        Map<String, Metadata> parsedByRecord = new LinkedHashMap<>();
        Map<String, Path> sidecarById = new TreeMap<>();
        Set<Path> claimedSources = new LinkedHashSet<>();
        for (Path sidecar : sidecars) {
            String id = canonicalRelativize(root, sidecar);
            id = id.substring(0, id.length() - SIDECAR_SUFFIX.length());
            sidecarById.put(id, sidecar);
            String text;
            try {
                text = Files.readString(sidecar);
            } catch (IOException e) {
                failures.add(new CatalogFailure(id, "<sidecar>",
                    "cannot read the sidecar: " + e.getMessage()));
                continue;
            }
            V12FeatureMetadata.ParseResult parsed =
                V12FeatureMetadata.parse(text);
            if (parsed.failure().isPresent()) {
                failures.add(new CatalogFailure(id, parsed.failure().get().field(),
                    parsed.failure().get().reason()));
                continue;
            }
            Metadata metadata = parsed.metadata().orElseThrow();

            // Root source existence + duplicate canonical aliases (checked
            // before the record joins the parsed set, so a source-less
            // record contributes no closure/matrix state).
            Path source = rootSourceFor(root, id);
            if (source == null) {
                failures.add(new CatalogFailure(id, "<source>",
                    "the sidecar's root source " + id + ".deal"
                        + "(/.d.deal) does not exist (a sidecar without "
                        + "a source)"));
                continue;
            }
            Path realSource = realPathOf(source);
            if (!claimedSources.add(realSource)) {
                failures.add(new CatalogFailure(id, "<source>",
                    "duplicate canonical alias: the root source " + id
                        + ".deal resolves to the same file as another "
                        + "record's root source"));
                continue;
            }
            parsedByRecord.put(id, metadata);
        }

        // ---- Per-record support/closure/matrix/main/oracle checks ----
        Map<Path, String> sourceId = new TreeMap<>();
        for (Map.Entry<String, Path> entry : sidecarById.entrySet()) {
            Path rootSource = rootSourceFor(root, entry.getKey());
            if (rootSource != null) {
                sourceId.put(rootSource, entry.getKey());
            }
        }
        Set<String> allSources = new LinkedHashSet<>();
        for (Path source : sources) {
            allSources.add(canonicalRelativize(root, source));
        }

        for (Map.Entry<String, Metadata> entry : parsedByRecord.entrySet()) {
            String id = entry.getKey();
            Metadata metadata = entry.getValue();
            Path source = rootSourceFor(root, id);
            List<String> sourceProblems = new ArrayList<>();
            List<Path> supportSources = resolveSupportEntries(
                root, id, metadata.support(), allSources, sourceProblems);
            Set<Path> walkedClosure = new LinkedHashSet<>();
            sourceProblems.addAll(checkSupportClosure(
                root, id, metadata, source, allSources, supportSources,
                walkedClosure));
            // E5 production-@spec rejection (acceptance criterion 3)
            // covers the record's whole fixture graph: the root source,
            // every resolved support entry, and every transitively
            // walked catalog source. Directive-shaped // @spec:
            // metadata is never stripped or whitelisted on any fixture
            // the record compiles.
            for (Path fixture : specScanFixtures(source, supportSources,
                    walkedClosure)) {
                String specProblem = checkProductionSpecRejection(id, fixture);
                if (specProblem != null) {
                    sourceProblems.add(specProblem);
                }
            }
            List<String> shapeProblems = checkSourceShapes(id, metadata, source);
            sourceProblems.addAll(shapeProblems);

            for (String problem : sourceProblems) {
                failures.add(new CatalogFailure(id, "<source>", problem));
            }
            if (!sourceProblems.isEmpty()) {
                continue;
            }

            // Architecture-owned matrix: per-record row.
            Optional<MatrixFailure> matrixFail =
                FeatureBackendMatrix.validateRecord(id, metadata);
            if (matrixFail.isPresent()) {
                failures.add(new CatalogFailure(id, matrixFail.get().field(),
                    matrixFail.get().reason()));
                continue;
            }
            records.put(id, new ValidatedRecord(id, metadata,
                sidecarById.get(id), source, supportSources,
                invocationInfoOf(metadata, source)));
        }

        // ---- Catalog-level matrix rules ----
        if (records.size() == parsedByRecord.size()) {
            for (MatrixFailure failure :
                    FeatureBackendMatrix.validateCatalog(parsedByRecord)) {
                failures.add(new CatalogFailure(failure.recordId(),
                    failure.field(), failure.reason()));
            }
        }

        // ---- Catalog closure: unlisted sources ----
        for (String rel : allSources) {
            boolean isRoot = sourceId.containsKey(root.resolve(rel));
            boolean listed = false;
            for (Metadata metadata : parsedByRecord.values()) {
                if (metadata.support().contains(rel)) {
                    listed = true;
                    break;
                }
            }
            if (!isRoot && !listed) {
                failures.add(new CatalogFailure("<catalog>", "<source>",
                    "the source " + rel + " is neither a root source nor "
                        + "listed in any record's support (unlisted source)"));
            }
        }

        List<ValidatedRecord> validated = failures.isEmpty()
            ? List.copyOf(records.values())
            : List.of();
        return new CatalogResult(root, failures, validated);
    }

    // =========================================================================
    // Support resolution and closure
    // =========================================================================

    /**
     * Resolves every {@code support} entry to its absolute source: each
     * entry must name an existing catalog source, must not name the
     * record's own root source, and (checked separately by the closure
     * walk) must be transitively imported.
     */
    private static List<Path> resolveSupportEntries(Path root, String id,
            List<String> support, Set<String> allSources,
            List<String> problems) {
        List<Path> resolved = new ArrayList<>();
        Set<Path> realTargets = new LinkedHashSet<>();
        for (String entryText : support) {
            Path target = root.resolve(entryText).normalize();
            if (!target.startsWith(root)
                    || !allSources.contains(entryText)
                    || !Files.isRegularFile(target)) {
                problems.add("the support entry \"" + entryText
                    + "\" does not resolve to an existing catalog source "
                    + "(unresolved support edge)");
                continue;
            }
            if (entryText.equals(id + ".deal")) {
                problems.add("the support entry \"" + entryText
                    + "\" names the record's own root source");
                continue;
            }
            Path realTarget = realPathOf(target);
            if (!realTargets.add(realTarget)) {
                problems.add("duplicate canonical alias: the support "
                    + "entries resolve to the same file through two "
                    + "spellings (" + realTarget.getFileName() + ")");
            }
            resolved.add(target);
        }
        return resolved;
    }

    /**
     * The root/support closure check: every catalog-internal module
     * transitively imported by the root source must be listed in the
     * record's {@code support}, and every support entry must be
     * transitively imported (unused support). The import walk uses the
     * real lexer/parser and the companion resolution rules (relative
     * {@code ./}/{@code ../} specifiers with {@code .deal}/
     * {@code .d.deal} fallback); stdlib, host, and out-of-catalog
     * modules contribute no support entry.
     */
    private static List<String> checkSupportClosure(Path root, String id,
            Metadata metadata, Path source, Set<String> allSources,
            List<Path> supportSources, Set<Path> walkedClosure) {
        List<String> problems = new ArrayList<>();
        Set<String> imported = new LinkedHashSet<>();
        List<String> walkProblems = new ArrayList<>();
        walkImports(root, source, allSources, imported, walkProblems,
            new LinkedHashSet<>());
        // The walked closure (root included) is retained for the
        // production-@spec scan: every fixture source the record's
        // graph reaches must have a clean directive surface.
        for (String rel : imported) {
            walkedClosure.add(root.resolve(rel).normalize());
        }
        for (String problem : walkProblems) {
            problems.add(problem);
        }
        if (!walkProblems.isEmpty()) {
            return problems;
        }
        imported.remove(canonicalRelativize(root, source));
        for (String rel : imported) {
            if (!metadata.support().contains(rel)) {
                problems.add("the root source imports the catalog module \""
                    + rel + "\" but the record does not list it in support "
                    + "(unlisted support — explicit dual-role ownership "
                    + "requires every importer to list its target)");
            }
        }
        for (Path support : supportSources) {
            String rel = canonicalRelativize(root, support);
            if (!imported.contains(rel)) {
                problems.add("the support entry \"" + rel + "\" is never "
                    + "transitively imported by the root source (unused "
                    + "support)");
            }
        }
        return problems;
    }

    /**
     * Walks the transitive relative-import closure of one source and
     * records every catalog-internal module it reaches. Resolution is
     * the companion rule: exact file, then {@code .deal}, then
     * {@code .d.deal}. An import that resolves to nothing or to a path
     * outside the catalog root contributes nothing here (the execution
     * surface owns the resolution diagnostic).
     */
    private static void walkImports(Path root, Path source,
            Set<String> allSources, Set<String> imported,
            List<String> problems, Set<Path> visited) {
        Path normalized = source.toAbsolutePath().normalize();
        if (!visited.add(normalized)) {
            return;
        }
        if (normalized.startsWith(root) && Files.isRegularFile(normalized)) {
            imported.add(canonicalRelativize(root, normalized));
        }
        Optional<ProgramNode> program = parseProgram(normalized, problems);
        if (program.isEmpty()) {
            return;
        }
        for (StatementNode statement : program.get().statements()) {
            if (!(statement instanceof ImportDeclaration imp)) {
                continue;
            }
            if (!imp.modulePath().startsWith("./")
                    && !imp.modulePath().startsWith("../")) {
                continue;
            }
            Path resolved = resolveCompanion(imp.modulePath(),
                normalized.getParent());
            if (resolved == null) {
                continue; // resolution failure is the execution surface's domain
            }
            walkImports(root, resolved, allSources, imported, problems, visited);
        }
    }

    private static Path resolveCompanion(String importPath, Path baseDir) {
        Path resolved = baseDir.resolve(importPath).normalize();
        if (Files.exists(resolved)) {
            return resolved;
        }
        Path withExt = baseDir.resolve(importPath + ".deal").normalize();
        if (Files.exists(withExt)) {
            return withExt;
        }
        Path withDeclExt = baseDir.resolve(importPath + ".d.deal").normalize();
        if (Files.exists(withDeclExt)) {
            return withDeclExt;
        }
        return null;
    }

    // =========================================================================
    // Production metadata rejection (E5 → E1044)
    // =========================================================================

    /**
     * Scans one fixture source with the real production lexer (E5) and
     * rejects directive-shaped {@code // @spec:} metadata: in
     * production the lexer emits E1044 for the unknown {@code spec}
     * directive, so a fixture relying on it can never pass — the
     * catalog fails it here instead of stripping or whitelisting the
     * metadata. Returns the problem line, or {@code null} when clean.
     */
    private static String checkProductionSpecRejection(String id, Path source) {
        String text;
        try {
            text = Files.readString(source);
        } catch (IOException e) {
            return "cannot read the fixture source: " + e.getMessage();
        }
        LexResult lexed;
        try {
            lexed = new Lexer(text, source.toString()).tokenize();
        } catch (RuntimeException e) {
            // Fail closed: an unlexable fixture cannot prove its
            // directive surface.
            return "the fixture source does not lex: " + e.getMessage();
        }
        for (CompilerDirective event : lexed.directiveEvents()) {
            if (event.name() == null) {
                String recovered = recoveredName(text, event);
                if (SPEC_DIRECTIVE_NAME.equals(recovered)) {
                    return "the fixture source " + source.getFileName()
                        + " carries a directive-shaped // @spec: comment "
                        + "— production emits E1044 for it, so the "
                        + "metadata must not be stripped or whitelisted "
                        + "(use the sidecar's spec field instead)";
                }
            }
        }
        return null;
    }

    /**
     * The recovered unknown-name text of an E1044 directive event.
     * DiagnosticRange offsets count decoded Unicode scalars, so the
     * recovery walks code points; {@code String.substring} would slice
     * UTF-16 code units and mis-read the name whenever a non-BMP
     * scalar precedes the directive.
     */
    private static String recoveredName(String source, CompilerDirective event) {
        int start = event.nameRange().startScalarOffset();
        int end = event.nameRange().endScalarOffset();
        if (start < 0 || end < start) {
            return "";
        }
        StringBuilder recovered = new StringBuilder();
        int scalar = 0;
        int codeUnit = 0;
        while (codeUnit < source.length() && scalar < end) {
            int codePoint = source.codePointAt(codeUnit);
            if (scalar >= start) {
                recovered.appendCodePoint(codePoint);
            }
            scalar++;
            codeUnit += Character.charCount(codePoint);
        }
        if (scalar < end) {
            return ""; // the range runs past the decoded scalar count
        }
        return recovered.toString();
    }

    /**
     * The fixture sources whose production directive surface must be
     * clean for one record: the root source, every resolved support
     * entry, and every transitively walked catalog source. Order is
     * deterministic (root, support-list order, walk order).
     */
    private static Set<Path> specScanFixtures(Path source,
            List<Path> supportSources, Set<Path> walkedClosure) {
        Set<Path> fixtures = new LinkedHashSet<>();
        fixtures.add(source);
        fixtures.addAll(supportSources);
        fixtures.addAll(walkedClosure);
        return fixtures;
    }

    // =========================================================================
    // Source-project main/oracle shapes
    // =========================================================================

    /**
     * The D12 source-project shape checks: direct-main/async-export
     * roots must export non-async zero-parameter {@code main(): null};
     * synthetic-main/async-export roots must export the pinned oracle
     * name as a zero-parameter function; compile-error roots on
     * declaration files require a generated entry (exposed through the
     * invocation info, never guessed).
     */
    private static List<String> checkSourceShapes(String id, Metadata metadata,
            Path source) {
        List<String> problems = new ArrayList<>();
        List<String> parseProblems = new ArrayList<>();
        Optional<ProgramNode> program = parseProgram(source, parseProblems);
        if (parseProblems.isEmpty()) {
            // no problems (program present)
            ProgramNode ast = program.orElseThrow();
            if (metadata.invocation().isRuntimeInvocation()
                    && (metadata.invocation() == Invocation.DIRECT_MAIN
                        || metadata.invocation() == Invocation.ASYNC_EXPORT)) {
                if (!exportsNonAsyncMain(ast)) {
                    problems.add(metadata.invocation().jsonText()
                        + " requires the root source to export a non-async "
                        + "zero-parameter main(): null — no such export "
                        + "exists in the fixture");
                }
            }
            if (metadata.oracle().isPresent()) {
                String exportName = metadata.oracle().get().exportName();
                if (!exportsZeroParameterFunction(ast, exportName)) {
                    problems.add("the oracle export \"" + exportName
                        + "\" does not exist in the root source as a "
                        + "zero-parameter exported function");
                }
            }
        } else {
            problems.addAll(parseProblems);
        }
        return problems;
    }

    private static boolean exportsNonAsyncMain(ProgramNode program) {
        for (StatementNode statement : program.statements()) {
            if (statement instanceof ExportDeclaration exp
                    && exp.declaration() instanceof FunctionDeclaration fn
                    && "main".equals(fn.name())
                    && fn.params().isEmpty()
                    && !fn.isAsync()
                    && fn.returnType() instanceof NamedType named
                    && "null".equals(named.name())) {
                return true;
            }
        }
        return false;
    }

    private static boolean exportsZeroParameterFunction(ProgramNode program,
            String name) {
        for (StatementNode statement : program.statements()) {
            if (statement instanceof ExportDeclaration exp
                    && exp.declaration() instanceof FunctionDeclaration fn
                    && name.equals(fn.name())
                    && fn.params().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses one fixture source with the real production parser. Parse
     * diagnostics are tolerated (a compile-error fixture may pin a
     * frontend diagnostic); a missing program (a parse failure that
     * aborts the AST) is reported as a problem because the closure and
     * shape checks require a parseable fixture.
     */
    private static Optional<ProgramNode> parseProgram(Path source,
            List<String> problems) {
        String text;
        try {
            text = Files.readString(source);
        } catch (IOException e) {
            problems.add("cannot read the fixture source: " + e.getMessage());
            return Optional.empty();
        }
        ParseResult parsed;
        try {
            // The production-shaped parse: the real lexer's directive
            // events flow in (file-directive evaluation and declaration
            // binding exactly as in production) under the v1.2 int
            // profile, so v1.2 literal fixtures parse exactly as the
            // production pipeline parses them.
            LexResult lexed = new Lexer(text, source.toString()).tokenize();
            parsed = new Parser(lexed.tokens(), source.toString(),
                SemanticProfile.DEAL_V1_2_INT32,
                lexed.directiveEvents()).parse();
        } catch (RuntimeException e) {
            problems.add("the fixture source does not parse: " + e.getMessage());
            return Optional.empty();
        }
        if (parsed.program() == null) {
            problems.add("the fixture source does not parse to a program "
                + "(catalog closure and shape validation require a "
                + "parseable fixture)");
            return Optional.empty();
        }
        return Optional.of(parsed.program());
    }

    // =========================================================================
    // Invocation info
    // =========================================================================

    private static InvocationInfo invocationInfoOf(Metadata metadata,
            Path source) {
        boolean requiresMain = metadata.invocation() == Invocation.DIRECT_MAIN
            || metadata.invocation() == Invocation.ASYNC_EXPORT;
        boolean needsGeneratedEntry =
            metadata.expected().isCompileOutcome()
                && source.getFileName().toString().endsWith(".d.deal");
        return new InvocationInfo(metadata.invocation(), metadata.backends(),
            metadata.oracle(), metadata.linkedRecord(), requiresMain,
            needsGeneratedEntry);
    }

    // =========================================================================
    // Path helpers
    // =========================================================================

    /**
     * The root source of one record: {@code <id>.deal}, or
     * {@code <id>.d.deal} for declaration-file roots (compile-error
     * records on declaration fixtures); {@code null} when neither
     * exists.
     */
    private static Path rootSourceFor(Path root, String id) {
        Path deal = root.resolve(id + ".deal");
        if (Files.isRegularFile(deal)) {
            return deal;
        }
        Path decl = root.resolve(id + ".d.deal");
        if (Files.isRegularFile(decl)) {
            return decl;
        }
        return null;
    }

    /** The real path of one file (symlinks resolved) for alias comparison. */
    private static Path realPathOf(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** The canonical catalog-relative spelling of a path (slash separators). */
    private static String canonicalRelativize(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize())
            .toString().replace('\\', '/');
    }
}
