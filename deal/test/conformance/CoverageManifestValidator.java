package deal.test.conformance;

import deal.ast.ImportDeclaration;
import deal.ast.StatementNode;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.ModuleResolver.ModuleNotFoundException;
import deal.checker.NameResolver;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.diagnostics.CompilerDiagnostic;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.module.ModuleShapeValidator;
import deal.module.StdlibModuleResolver;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.SemanticIrTextDecodeException;
import deal.semantic.ir.SemanticProfile;
import deal.test.ConformanceHarnessMetadata;
import deal.types.Type;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The reusable Coverage Manifest Validation component of the v1.2
 * corpus coverage contract (ISSUE-0474; the manifest and validator
 * design is {@code v12-coverage-manifest-and-validator} D1-D8, the C7
 * per-kind table and Corpus Coverage Manifest contract are
 * {@code v12-three-backend-conformance-corpus}, and the T14 consumer is
 * the Coverage Manifest Validator component of
 * {@code v12-zero-skip-conformance-gate}).
 *
 * <p>Validates one Corpus Coverage Manifest document
 * ({@code test/conformance/v1.2-coverage.json}, schema version 1)
 * against the on-disk corpus, the closed requirement inventory, and the
 * C7 per-kind gate rules, and returns the complete failure list. The
 * fixed check order is parse/schema → inventory completeness →
 * {@code specSections} completeness → per-entry checks (D8): every
 * failure is collected into one list in deterministic order
 * ({@code TreeSet}-ordered inventory iteration, sorted discovery walks)
 * and the returned list is empty exactly when the manifest validates
 * clean. There is no fail-fast shortcut and no default expectation —
 * the same inputs always produce the identical outcome.</p>
 *
 * <p>Rules enforced, each failure naming the requirement:</p>
 * <ul>
 *   <li><b>Schema (D1):</b> closed root fields
 *       {@code version, requirements, observables, specSections,
 *       deferred}; closed entry fields {@code title, kind, fixtures};
 *       {@code version} exactly 1; every entry carries a non-empty
 *       {@code title}, a {@code kind} from the closed vocabulary
 *       {@code runtime | compile | benchmark}, and a non-empty array of
 *       unique plain relative fixture paths (absolute paths and
 *       {@code "."}/{@code ".."} segments are rejected).</li>
 *   <li><b>Inventory (D2):</b> the closed {@link #REQUIRED_BULLETS} and
 *       {@link #REQUIRED_OBSERVABLES} maps close the requirement
 *       inventory inside the validator: an uncovered bullet or
 *       observable, a row carrying the wrong kind, or an unknown
 *       requirement/observable id is a gate failure.</li>
 *   <li><b>Deferral (D3):</b> the {@code deferred} map must name exactly
 *       the closed {@link #KNOWN_DEFERRED} set with non-empty reasons,
 *       and a deferred row must not carry a coverage entry.</li>
 *   <li><b>{@code specSections} (D5):</b> every {@code @spec}
 *       first-component (the part before the {@code " — "} separator)
 *       used by any discovered corpus fixture must be listed; an
 *       uncovered component fails naming the first fixture using it.
 *       Discovery walks every {@code .deal} file under the corpus root
 *       exactly like the gate — the {@code host-fixtures} subtree
 *       excluded, companion modules without {@code @spec} skipped,
 *       sorted.</li>
 *   <li><b>Per-entry (D4):</b> every fixture path resolves to an
 *       existing file (under the corpus root for {@code runtime}/
 *       {@code compile}, under the repo root for {@code benchmark});
 *       the fixture's exact {@code @expected} tag (the
 *       {@code test/ConformanceTest.java} grammar) is classified as its
 *       kind requires ({@code runtime-ok} | {@code runtime-error CODE}
 *       for {@code runtime}; {@code compile-ok} | exact
 *       {@code compile-error CODE} | tracked
 *       {@code known-fail compile-error CODE} for {@code compile};
 *       {@code known-fail compile-ok} and {@code known-fail runtime-*}
 *       are mismatches in any row); the C FFI declaration-rules row
 *       pins a real {@code compile-error E7001|E7002} classification;
 *       {@code runtime}-kind fixtures carry a sibling Structured
 *       Expectation Sidecar that validates clean under
 *       {@link SidecarSchemaValidator} schema v1 with the fixture's
 *       compilation set and the corpus module index; Diagnostics-row
 *       fixtures each carry a Compile Expectation Sidecar
 *       ({@code mode: "compile-error"}) and any sidecar next to another
 *       {@code compile}-kind fixture must also validate as a compile
 *       sidecar; {@code benchmark}-kind artifacts exist and compile
 *       under the embedded backend-neutral v1.2 frontend (lexer →
 *       parser with the v1.2 int32 profile →
 *       {@link ModuleShapeValidator} → {@link NameResolver} over the
 *       spec stdlib surface → {@link TypeChecker} with no error
 *       diagnostics).</li>
 * </ul>
 *
 * <p>The validator is deterministic and side-effect-free over its
 * inputs: it parses the manifest text it is given, reads fixture and
 * sidecar files under the corpus root, runs the embedded backend-neutral
 * frontend over benchmark artifacts (whose name resolution reads the
 * spec stdlib surface), performs no other I/O, and mutates nothing. It
 * is a reusable test-side component: today's consumers are its unit
 * matrix and the authoring-time suites, and the differential gate's
 * Coverage Manifest Validator component consumes the same API at T14.
 * The manifest is a gate artifact, not runtime metadata: production
 * compiler/runtime/stdlib code never depends on the manifest or this
 * validator.</p>
 */
public final class CoverageManifestValidator {

    /** The only manifest schema version this validator accepts. */
    public static final int MANIFEST_SCHEMA_VERSION = 1;

    /**
     * The closed per-kind vocabulary of the Corpus Coverage Manifest
     * (corpus C7): {@code runtime} fixtures are runtime-classified with
     * three-backend sidecars, {@code compile} fixtures are
     * frontend-classified, {@code benchmark} entries name a compiling
     * artifact.
     */
    public enum Kind {
        RUNTIME, COMPILE, BENCHMARK;

        /**
         * The kind of the given manifest value
         * ({@code "runtime"} → {@link #RUNTIME},
         * {@code "compile"} → {@link #COMPILE},
         * {@code "benchmark"} → {@link #BENCHMARK}), case-insensitively,
         * or {@code null} for a null or unknown value.
         */
        public static Kind of(String name) {
            if (name == null) {
                return null;
            }
            try {
                return valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        /** The manifest value spelling of this kind. */
        public String manifestName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The Diagnostics §Conformance tests row: every fixture it names
     * must carry a Compile Expectation Sidecar (D4).
     */
    public static final String DIAGNOSTICS_REQUIREMENT =
        "conformance-diagnostics";

    /**
     * The C FFI declaration-rules row: every fixture it names must pin
     * a real {@code compile-error E7001|E7002} classification (D4 — the
     * parser-emitted C-marker cardinality/placement arms).
     */
    public static final String FFI_DECLARATION_RULES_REQUIREMENT =
        "conformance-ffi-declaration-rules";

    /**
     * The closed requirement inventory (D2): the 18 §Conformance tests
     * rows (spec-v1.2.md:2939-2955) with their C7 kinds, as authored in
     * the manifest. The two deferred C FFI runtime rows are absent here
     * until T14 adds them together with their divergent fixture.
     */
    public static final Map<String, Kind> REQUIRED_BULLETS = Map.ofEntries(
        Map.entry("conformance-lexer", Kind.COMPILE),
        Map.entry("conformance-parser", Kind.COMPILE),
        Map.entry("conformance-type-checker", Kind.COMPILE),
        Map.entry("conformance-diagnostics", Kind.COMPILE),
        Map.entry("conformance-host-abi-declarations", Kind.COMPILE),
        Map.entry("conformance-ffi-declaration-rules", Kind.COMPILE),
        Map.entry("conformance-ffi-invalid-manifest-policy", Kind.COMPILE),
        Map.entry("conformance-runtime-sentinels", Kind.RUNTIME),
        Map.entry("conformance-runtime-int32", Kind.RUNTIME),
        Map.entry("conformance-abi", Kind.RUNTIME),
        Map.entry("conformance-host-abi-boundary", Kind.RUNTIME),
        Map.entry("conformance-stdlib-console", Kind.RUNTIME),
        Map.entry("conformance-stdlib-string", Kind.RUNTIME),
        Map.entry("conformance-stdlib-table", Kind.RUNTIME),
        Map.entry("conformance-stdlib-json", Kind.RUNTIME),
        Map.entry("conformance-stdlib-math", Kind.RUNTIME),
        Map.entry("conformance-stdlib-time", Kind.RUNTIME),
        Map.entry("conformance-ai-codegen-benchmark", Kind.BENCHMARK)
    );

    /**
     * The closed observable inventory (D2): the 11 §Backend conformance
     * contract observables (spec-v1.2.md:2103-2120) with their C7 kinds
     * — compile-time diagnostics is {@code compile}; the other ten are
     * {@code runtime}.
     */
    public static final Map<String, Kind> REQUIRED_OBSERVABLES =
        Map.ofEntries(
            Map.entry("observable-compile-time-diagnostics", Kind.COMPILE),
            Map.entry("observable-successful-runtime-values", Kind.RUNTIME),
            Map.entry("observable-runtime-error-codes-source-locations",
                Kind.RUNTIME),
            Map.entry("observable-module-import-export", Kind.RUNTIME),
            Map.entry("observable-host-boundary", Kind.RUNTIME),
            Map.entry("observable-null-missing-field", Kind.RUNTIME),
            Map.entry("observable-class-nominal-identity", Kind.RUNTIME),
            Map.entry("observable-function-type-identity-async-marker",
                Kind.RUNTIME),
            Map.entry("observable-evaluation-order", Kind.RUNTIME),
            Map.entry("observable-integer-overflow-division", Kind.RUNTIME),
            Map.entry("observable-jsonable-serialization", Kind.RUNTIME)
        );

    /**
     * The closed deferral bookkeeping (D3): exactly the two C FFI
     * runtime rows deferred to the zero-skip flip (T14), each with a
     * non-empty reason. T14 adds the rows together with their divergent
     * E6006 sidecar fixture and removes the bookkeeping in the same
     * change; the manifest carries no entry referencing a nonexistent
     * fixture in any state.
     */
    public static final Map<String, String> KNOWN_DEFERRED = Map.of(
        "conformance-ffi-abi-mapping",
        "Deferred to the zero-skip flip (T14): the C FFI ABI-mapping "
            + "runtime row lands in one change together with its "
            + "divergent E6006 sidecar fixture, after the backend epics "
            + "register E6006 in deal/diagnostics/DiagnosticCode.java; "
            + "no coverage entry exists until that change.",
        "conformance-ffi-runtime-errors",
        "Deferred to the zero-skip flip (T14): the C FFI runtime-errors "
            + "and unsupported-backend rejection row lands in one change "
            + "together with its divergent E6006 sidecar fixture, after "
            + "the backend epics register E6006 in "
            + "deal/diagnostics/DiagnosticCode.java; no coverage entry "
            + "exists until that change."
    );

    /** The closed manifest root field set (schema v1). */
    private static final Set<String> ROOT_FIELDS = Set.of(
        "version", "requirements", "observables", "specSections",
        "deferred");

    /** The closed manifest entry field set (schema v1). */
    private static final Set<String> ENTRY_FIELDS =
        Set.of("title", "kind", "fixtures");

    /** The document-level failure name for manifest-shape failures. */
    private static final String MANIFEST_DOC = "<manifest>";

    /** The document-level failure name for specSections failures. */
    private static final String SPEC_SECTIONS_DOC = "<specSections>";

    private CoverageManifestValidator() {
        // Static utility; no instances.
    }

    // =========================================================================
    // Result types
    // =========================================================================

    /**
     * One coverage gate failure: the requirement it names (or
     * {@code "<manifest>"}/{@code "<specSections>"} for document-level
     * failures), the offending fixture path (empty when no single
     * fixture applies), the offending field, and the exact reason.
     */
    public record CoverageFailure(
        String requirement, String fixture, String field, String reason) {

        public CoverageFailure {
            Objects.requireNonNull(requirement, "requirement must not be null");
            fixture = fixture == null ? "" : fixture;
            Objects.requireNonNull(field, "field must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }

        /** The gate-readable failure line: requirement, fixture, field, reason. */
        public String message() {
            StringBuilder sb = new StringBuilder(requirement);
            if (!fixture.isEmpty()) {
                sb.append(": ").append(fixture);
            }
            return sb.append(": ").append(field).append(": ").append(reason)
                .toString();
        }

        @Override
        public String toString() {
            return message();
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Validates one Corpus Coverage Manifest document against the
     * on-disk corpus and the closed requirement inventory, and returns
     * the complete failure list. The returned list is empty exactly
     * when the manifest validates clean. The check order is fixed —
     * parse/schema → inventory completeness → {@code specSections}
     * completeness → per-entry checks — so the result is deterministic
     * for any given inputs.
     *
     * @param manifestJson the manifest document text (non-null)
     * @param corpusRoot   the corpus root directory — the
     *                     {@code test/conformance} directory (non-null)
     * @param repoRoot     the repository root directory — the home of
     *                     {@code benchmark}-kind artifacts and of the
     *                     spec stdlib surface (non-null)
     * @return the complete deterministic failure list (empty = clean)
     */
    public static List<CoverageFailure> validate(String manifestJson,
            Path corpusRoot, Path repoRoot) {
        Objects.requireNonNull(manifestJson, "manifestJson must not be null");
        Objects.requireNonNull(corpusRoot, "corpusRoot must not be null");
        Objects.requireNonNull(repoRoot, "repoRoot must not be null");
        Path corpus = corpusRoot.toAbsolutePath().normalize();
        Path repo = repoRoot.toAbsolutePath().normalize();
        List<CoverageFailure> failures = new ArrayList<>();

        // D8 fixed check order: parse/schema → inventory completeness →
        // specSections completeness → per-entry checks.
        ParsedManifest parsed = parseAndSchemaValidate(manifestJson, failures);
        validateInventory(parsed, failures);
        List<CorpusFixture> discovered = discoverCorpus(corpus);
        validateSpecSections(parsed, discovered, failures);
        validateEntries(parsed, discovered, corpus, repo, failures);
        return List.copyOf(failures);
    }

    // =========================================================================
    // Parse and schema validation (D1)
    // =========================================================================

    /** One schema-clean manifest entry: id, kind, and fixture paths. */
    private record ManifestEntry(String id, Kind kind, List<String> fixtures) {}

    /**
     * The parsed manifest: schema-clean entries only (an entry that
     * failed schema validation is absent so the per-entry rules never
     * run on a structurally broken entry), the valid {@code specSections}
     * list (empty when absent or structurally invalid), and the
     * {@code deferred} map of string reasons (absent/invalid entries
     * are missing from it).
     */
    private record ParsedManifest(
        Map<String, ManifestEntry> requirements,
        Map<String, ManifestEntry> observables,
        List<String> specSections,
        Map<String, String> deferred
    ) {}

    private static ParsedManifest parseAndSchemaValidate(String manifestJson,
            List<CoverageFailure> failures) {
        Map<String, ManifestEntry> requirements = new TreeMap<>();
        Map<String, ManifestEntry> observables = new TreeMap<>();
        List<String> specSections = new ArrayList<>();
        Map<String, String> deferred = new TreeMap<>();

        CanonicalJson.Value root;
        try {
            root = CanonicalJson.parse(manifestJson);
        } catch (SemanticIrTextDecodeException | IllegalArgumentException e) {
            failures.add(failure(MANIFEST_DOC, "", "<manifest>",
                "malformed manifest JSON: " + e.getMessage()));
            return new ParsedManifest(requirements, observables,
                specSections, deferred);
        }
        if (!(root instanceof CanonicalJson.Obj obj)) {
            failures.add(failure(MANIFEST_DOC, "", "<manifest>",
                "the manifest root must be a JSON object"));
            return new ParsedManifest(requirements, observables,
                specSections, deferred);
        }
        Map<String, CanonicalJson.Value> rootFields = new LinkedHashMap<>();
        for (CanonicalJson.Entry entry : obj.entries()) {
            if (!ROOT_FIELDS.contains(entry.key())) {
                failures.add(failure(MANIFEST_DOC, "", entry.key(),
                    "unknown field in the manifest root (closed root "
                        + "fields: version, requirements, observables, "
                        + "specSections, deferred)"));
            }
            rootFields.putIfAbsent(entry.key(), entry.value());
        }
        CanonicalJson.Value versionValue = rootFields.get("version");
        if (!(versionValue instanceof CanonicalJson.Int version)) {
            failures.add(failure(MANIFEST_DOC, "", "version",
                versionValue == null
                    ? "missing mandatory version field"
                    : "version must be the integer "
                        + MANIFEST_SCHEMA_VERSION));
        } else if (version.value() != MANIFEST_SCHEMA_VERSION) {
            failures.add(failure(MANIFEST_DOC, "", "version",
                "unknown manifest schema version " + version.value()
                    + " (supported: " + MANIFEST_SCHEMA_VERSION + ")"));
        }
        parseEntrySection(rootFields.get("requirements"), "requirement",
            requirements, failures);
        parseEntrySection(rootFields.get("observables"), "observable",
            observables, failures);
        parseSpecSections(rootFields.get("specSections"), specSections,
            failures);
        parseDeferred(rootFields.get("deferred"), deferred, failures);
        return new ParsedManifest(requirements, observables, specSections,
            deferred);
    }

    /** One entry section (requirements or observables): closed entry shape. */
    private static void parseEntrySection(CanonicalJson.Value sectionValue,
            String sectionName, Map<String, ManifestEntry> out,
            List<CoverageFailure> failures) {
        if (sectionValue == null) {
            failures.add(failure(MANIFEST_DOC, "", sectionName,
                "missing mandatory " + sectionName + " object"));
            return;
        }
        if (!(sectionValue instanceof CanonicalJson.Obj section)) {
            failures.add(failure(MANIFEST_DOC, "", sectionName,
                sectionName + " must be a JSON object"));
            return;
        }
        for (CanonicalJson.Entry sectionEntry : section.entries()) {
            parseOneEntry(sectionEntry.key(), sectionEntry.value(),
                sectionName, out, failures);
        }
    }

    /** One manifest entry: closed fields, non-empty title, kind, fixtures. */
    private static void parseOneEntry(String id, CanonicalJson.Value value,
            String sectionName, Map<String, ManifestEntry> out,
            List<CoverageFailure> failures) {
        if (!(value instanceof CanonicalJson.Obj entryObj)) {
            failures.add(failure(id, "", "<entry>",
                "the " + sectionName + " entry must be a JSON object"));
            return;
        }
        Map<String, CanonicalJson.Value> entryFields = new LinkedHashMap<>();
        for (CanonicalJson.Entry field : entryObj.entries()) {
            if (!ENTRY_FIELDS.contains(field.key())) {
                failures.add(failure(id, "", field.key(),
                    "unknown field in the " + sectionName + " entry "
                        + "(closed entry fields: title, kind, fixtures)"));
            }
            entryFields.putIfAbsent(field.key(), field.value());
        }
        CanonicalJson.Value titleValue = entryFields.get("title");
        if (!(titleValue instanceof CanonicalJson.Str title)
                || title.value().trim().isEmpty()) {
            failures.add(failure(id, "", "title",
                titleValue == null
                    ? "missing mandatory title field"
                    : "title must be a non-empty string"));
        }
        CanonicalJson.Value kindValue = entryFields.get("kind");
        Kind kind = null;
        if (!(kindValue instanceof CanonicalJson.Str kindStr)) {
            failures.add(failure(id, "", "kind",
                kindValue == null
                    ? "missing mandatory kind field"
                    : "kind must be a string"));
        } else {
            kind = Kind.of(kindStr.value());
            if (kind == null) {
                failures.add(failure(id, "", "kind",
                    "unknown kind value \"" + kindStr.value() + "\" "
                        + "(the closed vocabulary is runtime, compile, "
                        + "benchmark)"));
            }
        }
        List<String> fixtures = parseFixtures(id, sectionName,
            entryFields.get("fixtures"), failures);
        if (kind != null && fixtures != null) {
            out.put(id, new ManifestEntry(id, kind, fixtures));
        }
    }

    /**
     * The fixtures array: non-empty, every item a plain relative path
     * string, no duplicates. Returns the path list, or {@code null}
     * when the array is structurally unusable.
     */
    private static List<String> parseFixtures(String id, String sectionName,
            CanonicalJson.Value value, List<CoverageFailure> failures) {
        if (!(value instanceof CanonicalJson.Arr arr)) {
            failures.add(failure(id, "", "fixtures",
                value == null
                    ? "missing mandatory fixtures array"
                    : "fixtures must be a JSON array"));
            return null;
        }
        List<String> paths = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (CanonicalJson.Value item : arr.items()) {
            if (!(item instanceof CanonicalJson.Str pathStr)) {
                failures.add(failure(id, "", "fixtures",
                    "every fixture path must be a string"));
                continue;
            }
            String path = pathStr.value();
            if (!isPlainRelativePath(path)) {
                failures.add(failure(id, path, "fixtures",
                    "fixture paths must be plain relative paths — "
                        + "absolute paths and \".\" or \"..\" segments "
                        + "are rejected, got \"" + path + "\""));
                continue;
            }
            if (!seen.add(path)) {
                failures.add(failure(id, path, "fixtures",
                    "duplicate fixture path \"" + path + "\""));
                continue;
            }
            paths.add(path);
        }
        if (paths.isEmpty()) {
            failures.add(failure(id, "", "fixtures",
                "the fixtures array must be non-empty"));
            return null;
        }
        return paths;
    }

    /** True iff the path is a plain relative path (no absolute form, no dot segments). */
    private static boolean isPlainRelativePath(String path) {
        if (path.isEmpty() || path.startsWith("/")
                || path.matches("^[A-Za-z]:[/\\\\].*")) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** The specSections array: a list of section-name strings. */
    private static void parseSpecSections(CanonicalJson.Value value,
            List<String> specSections, List<CoverageFailure> failures) {
        if (value == null) {
            failures.add(failure(SPEC_SECTIONS_DOC, "", "specSections",
                "missing mandatory specSections array"));
            return;
        }
        if (!(value instanceof CanonicalJson.Arr arr)) {
            failures.add(failure(SPEC_SECTIONS_DOC, "", "specSections",
                "specSections must be a JSON array of section names"));
            return;
        }
        List<String> names = new ArrayList<>();
        for (CanonicalJson.Value item : arr.items()) {
            if (!(item instanceof CanonicalJson.Str str)) {
                failures.add(failure(SPEC_SECTIONS_DOC, "", "specSections",
                    "every specSections entry must be a string"));
                return;
            }
            names.add(str.value());
        }
        specSections.addAll(names);
    }

    /** The deferred map: row id → string reason. */
    private static void parseDeferred(CanonicalJson.Value value,
            Map<String, String> deferred, List<CoverageFailure> failures) {
        if (value == null) {
            failures.add(failure(MANIFEST_DOC, "", "deferred",
                "missing mandatory deferred object"));
            return;
        }
        if (!(value instanceof CanonicalJson.Obj deferredObj)) {
            failures.add(failure(MANIFEST_DOC, "", "deferred",
                "deferred must be a JSON object"));
            return;
        }
        for (CanonicalJson.Entry entry : deferredObj.entries()) {
            if (!(entry.value() instanceof CanonicalJson.Str reason)) {
                failures.add(failure(entry.key(), "", "deferred",
                    "the deferral reason must be a string"));
                continue;
            }
            deferred.put(entry.key(), reason.value());
        }
    }

    // =========================================================================
    // Inventory completeness and deferral bookkeeping (D2/D3)
    // =========================================================================

    private static void validateInventory(ParsedManifest parsed,
            List<CoverageFailure> failures) {
        // Uncovered and wrong-kind bullets/observables, TreeSet-ordered.
        for (String id : new TreeSet<>(REQUIRED_BULLETS.keySet())) {
            ManifestEntry entry = parsed.requirements().get(id);
            if (entry == null) {
                failures.add(failure(id, "", "inventory",
                    "uncovered §Conformance tests bullet — the "
                        + "requirements map names no entry for \"" + id
                        + "\""));
                continue;
            }
            Kind required = REQUIRED_BULLETS.get(id);
            if (entry.kind() != required) {
                failures.add(failure(id, "", "kind",
                    "the requirement \"" + id + "\" requires kind "
                        + required.manifestName() + " (C7), got "
                        + entry.kind().manifestName()));
            }
        }
        for (String id : new TreeSet<>(REQUIRED_OBSERVABLES.keySet())) {
            ManifestEntry entry = parsed.observables().get(id);
            if (entry == null) {
                failures.add(failure(id, "", "inventory",
                    "uncovered §Backend conformance contract observable "
                        + "— the observables map names no entry for \""
                        + id + "\""));
                continue;
            }
            Kind required = REQUIRED_OBSERVABLES.get(id);
            if (entry.kind() != required) {
                failures.add(failure(id, "", "kind",
                    "the observable \"" + id + "\" requires kind "
                        + required.manifestName() + " (C7), got "
                        + entry.kind().manifestName()));
            }
        }
        // Unknown ids, TreeSet-ordered; a deferred id carrying a coverage
        // entry is the deferral coverage conflict, not a plain unknown id.
        for (String id : new TreeSet<>(parsed.requirements().keySet())) {
            if (REQUIRED_BULLETS.containsKey(id)) {
                continue;
            }
            if (KNOWN_DEFERRED.containsKey(id)) {
                failures.add(failure(id, "", "deferred",
                    "the deferred row \"" + id + "\" must not carry a "
                        + "coverage entry (T14 adds the row and removes "
                        + "the bookkeeping in one change)"));
            } else {
                failures.add(failure(id, "", "inventory",
                    "unknown requirement id \"" + id + "\" — the "
                        + "requirement inventory is closed inside the "
                        + "validator"));
            }
        }
        for (String id : new TreeSet<>(parsed.observables().keySet())) {
            if (!REQUIRED_OBSERVABLES.containsKey(id)) {
                failures.add(failure(id, "", "inventory",
                    "unknown observable id \"" + id + "\" — the "
                        + "observable inventory is closed inside the "
                        + "validator"));
            }
        }
        // Deferral bookkeeping: exactly the closed set, non-empty reasons.
        for (String id : new TreeSet<>(KNOWN_DEFERRED.keySet())) {
            if (!parsed.deferred().containsKey(id)) {
                failures.add(failure(id, "", "deferred",
                    "missing deferred row \"" + id + "\" — the deferral "
                        + "bookkeeping must name exactly the closed T14 "
                        + "set"));
                continue;
            }
            if (parsed.deferred().get(id).trim().isEmpty()) {
                failures.add(failure(id, "", "deferred",
                    "the deferred row \"" + id + "\" must carry a "
                        + "non-empty reason"));
            }
        }
        for (String id : new TreeSet<>(parsed.deferred().keySet())) {
            if (!KNOWN_DEFERRED.containsKey(id)) {
                failures.add(failure(id, "", "deferred",
                    "unknown deferred row \"" + id + "\" — the deferral "
                        + "bookkeeping is closed to the two T14 rows"));
            }
        }
    }

    // =========================================================================
    // Corpus discovery and @spec component completeness (D5)
    // =========================================================================

    /**
     * One discovered corpus fixture: its canonical corpus-relative slash
     * path, its header-stripped source (the compiler-facing form), its
     * exact {@code @expected} tag, and its {@code @spec} first-component
     * ({@code null} when the fixture carries no {@code @spec} — a
     * companion module without coverage of its own).
     */
    private record CorpusFixture(
        String corpusPath,
        String source,
        String expectedTag,
        String specComponent
    ) {}

    /**
     * The corpus discovery walk exactly like the gate: every
     * {@code .deal} file under the corpus root, the {@code host-fixtures}
     * subtree excluded, sorted.
     */
    private static List<CorpusFixture> discoverCorpus(Path corpusRoot) {
        List<CorpusFixture> fixtures = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(corpusRoot)) {
            List<Path> files = stream
                .filter(p -> p.toString().endsWith(".deal"))
                .filter(p -> {
                    Path rel = corpusRoot.relativize(p);
                    return rel.getNameCount() == 0
                        || !"host-fixtures".equals(
                            rel.getName(0).toString());
                })
                .sorted()
                .toList();
            for (Path file : files) {
                String corpusPath = slash(corpusRoot.relativize(file));
                String raw = readRaw(file);
                String expectedTag = readTag(raw, "// @expected:");
                String spec = readTag(raw, "// @spec:");
                String component = spec.isEmpty()
                    ? null
                    : firstComponentOf(spec);
                fixtures.add(new CorpusFixture(corpusPath,
                    ConformanceHarnessMetadata.stripClassificationHeaders(raw),
                    expectedTag, component));
            }
        } catch (IOException e) {
            // An unreadable corpus root discovers nothing; every per-entry
            // path check then reports its missing fixture honestly.
        }
        return fixtures;
    }

    /** The {@code @spec} first-component: the part before the " — " separator. */
    private static String firstComponentOf(String spec) {
        String first = spec.split(" \u2014 ", 2)[0].trim();
        return first.isEmpty() ? spec.trim() : first;
    }

    /** Every used {@code @spec} first-component must be listed (D5). */
    private static void validateSpecSections(ParsedManifest parsed,
            List<CorpusFixture> discovered, List<CoverageFailure> failures) {
        Set<String> listed = new HashSet<>(parsed.specSections());
        Map<String, String> firstUse = new LinkedHashMap<>();
        for (CorpusFixture fixture : discovered) {
            if (fixture.specComponent() == null) {
                continue;
            }
            firstUse.putIfAbsent(fixture.specComponent(),
                fixture.corpusPath());
        }
        for (Map.Entry<String, String> use : firstUse.entrySet()) {
            if (!listed.contains(use.getKey())) {
                failures.add(failure(use.getValue(), use.getValue(),
                    "specSections",
                    "uncovered @spec first-component \"" + use.getKey()
                        + "\" — specSections must list every component "
                        + "used by a discovered corpus fixture"));
            }
        }
    }

    // =========================================================================
    // Per-entry rules (D4)
    // =========================================================================

    private static void validateEntries(ParsedManifest parsed,
            List<CorpusFixture> discovered, Path corpusRoot, Path repoRoot,
            List<CoverageFailure> failures) {
        Map<String, CorpusFixture> corpusByPath = new LinkedHashMap<>();
        Set<String> corpusModuleIndex = new LinkedHashSet<>();
        for (CorpusFixture fixture : discovered) {
            corpusByPath.put(fixture.corpusPath(), fixture);
            corpusModuleIndex.add(fixture.corpusPath());
        }
        TreeMap<String, ManifestEntry> entries = new TreeMap<>();
        entries.putAll(parsed.requirements());
        entries.putAll(parsed.observables());
        for (Map.Entry<String, ManifestEntry> entry : entries.entrySet()) {
            validateOneEntry(entry.getKey(), entry.getValue(), corpusByPath,
                corpusModuleIndex, corpusRoot, repoRoot, failures);
        }
    }

    private static void validateOneEntry(String requirement,
            ManifestEntry entry, Map<String, CorpusFixture> corpusByPath,
            Set<String> corpusModuleIndex, Path corpusRoot, Path repoRoot,
            List<CoverageFailure> failures) {
        for (String fixturePath : entry.fixtures()) {
            Path resolved = entry.kind() == Kind.BENCHMARK
                ? repoRoot.resolve(fixturePath)
                : corpusRoot.resolve(fixturePath);
            if (!Files.isRegularFile(resolved)) {
                failures.add(failure(requirement, fixturePath, "fixtures",
                    "unknown or missing fixture path \"" + fixturePath
                        + "\""));
                continue;
            }
            if (entry.kind() == Kind.BENCHMARK) {
                validateBenchmarkFixture(requirement, fixturePath,
                    resolved, repoRoot, failures);
                continue;
            }
            CorpusFixture fixture = corpusByPath.get(fixturePath);
            String expectedTag = fixture != null
                ? fixture.expectedTag()
                : readTag(readRaw(resolved), "// @expected:");
            if (expectedTag.isEmpty()) {
                failures.add(failure(requirement, fixturePath,
                    "@expected",
                    "no @expected tag — the classification grammar of "
                        + "test/ConformanceTest.java requires an exact "
                        + "@expected tag on every classified fixture"));
                continue;
            }
            String mismatch = classificationMismatch(entry.kind(),
                expectedTag);
            if (mismatch != null) {
                failures.add(failure(requirement, fixturePath,
                    "@expected", mismatch));
            }
            if (FFI_DECLARATION_RULES_REQUIREMENT.equals(requirement)
                    && !expectedTag.equals("compile-error E7001")
                    && !expectedTag.equals("compile-error E7002")) {
                failures.add(failure(requirement, fixturePath,
                    "@expected",
                    "the C FFI declaration-rules row requires a real "
                        + "compile-error E7001|E7002 pin (the "
                        + "parser-emitted C-marker cardinality/placement "
                        + "arms) — a compile-ok fixture, a non-family "
                        + "code, or a tracked known-fail pin (which pins "
                        + "the code's absence) does not pin the rule, got "
                        + "\"" + expectedTag + "\""));
            }
            Path sidecarPath = sidecarFor(corpusRoot, fixturePath);
            if (entry.kind() == Kind.RUNTIME) {
                validateRuntimeSidecar(requirement, fixturePath,
                    expectedTag, fixture, corpusByPath, corpusModuleIndex,
                    corpusRoot, sidecarPath, failures);
            } else {
                validateCompileSidecar(requirement, fixturePath,
                    expectedTag, fixture, corpusByPath, corpusModuleIndex,
                    corpusRoot, sidecarPath, failures);
            }
        }
    }

    /**
     * The kind/classification cross-check (D4): returns {@code null}
     * when the tag classifies as the kind requires, else the mismatch
     * reason.
     */
    private static String classificationMismatch(Kind kind,
            String expectedTag) {
        switch (kind) {
            case RUNTIME -> {
                if (expectedTag.equals("runtime-ok")
                        || (expectedTag.startsWith("runtime-error ")
                            && expectedTag.length()
                                > "runtime-error ".length())) {
                    return null;
                }
                return "a runtime-kind entry requires a runtime-ok or "
                    + "runtime-error CODE classification, got \""
                    + expectedTag + "\"";
            }
            case COMPILE -> {
                if (expectedTag.equals("compile-ok")
                        || (expectedTag.startsWith("compile-error ")
                            && expectedTag.length()
                                > "compile-error ".length())
                        || (expectedTag.startsWith(
                                "known-fail compile-error ")
                            && expectedTag.length()
                                > "known-fail compile-error ".length())) {
                    return null;
                }
                return "a compile-kind entry requires a compile-ok, "
                    + "exact compile-error CODE, or tracked known-fail "
                    + "compile-error CODE classification (known-fail "
                    + "compile-ok and known-fail runtime-* are "
                    + "kind/classification mismatches in any row), got \""
                    + expectedTag + "\"";
            }
            default -> {
                return null;
            }
        }
    }

    /** Runtime sidecar: must exist and validate clean under the T1 schema. */
    private static void validateRuntimeSidecar(String requirement,
            String fixturePath, String expectedTag, CorpusFixture fixture,
            Map<String, CorpusFixture> corpusByPath,
            Set<String> corpusModuleIndex, Path corpusRoot,
            Path sidecarPath, List<CoverageFailure> failures) {
        if (!Files.isRegularFile(sidecarPath)) {
            failures.add(failure(requirement, fixturePath, "sidecar",
                "a runtime-kind fixture requires a sibling Structured "
                    + "Expectation Sidecar (schema v1, exactly the three "
                    + "backends), missing "
                    + sidecarPath.getFileName()));
            return;
        }
        validateSidecarWith(requirement, fixturePath, expectedTag, fixture,
            corpusByPath, corpusModuleIndex, corpusRoot, sidecarPath,
            failures);
    }

    /**
     * Compile-kind sidecar: the Diagnostics row requires one per fixture;
     * any sidecar next to a compile-kind fixture must be a Compile
     * Expectation Sidecar ({@code mode: "compile-error"}) and validate
     * clean under the T1 schema.
     */
    private static void validateCompileSidecar(String requirement,
            String fixturePath, String expectedTag, CorpusFixture fixture,
            Map<String, CorpusFixture> corpusByPath,
            Set<String> corpusModuleIndex, Path corpusRoot,
            Path sidecarPath, List<CoverageFailure> failures) {
        if (!Files.isRegularFile(sidecarPath)) {
            if (DIAGNOSTICS_REQUIREMENT.equals(requirement)) {
                failures.add(failure(requirement, fixturePath, "sidecar",
                    "the Diagnostics requirement requires every named "
                        + "fixture to carry a Compile Expectation Sidecar "
                        + "(mode \"compile-error\"), missing "
                        + sidecarPath.getFileName()));
            }
            return;
        }
        String sidecarText = readRaw(sidecarPath);
        if (!"compile-error".equals(compileSidecarMode(sidecarText))) {
            failures.add(failure(requirement, fixturePath, "sidecar.mode",
                "a sidecar next to a compile-kind fixture must be a "
                    + "Compile Expectation Sidecar with mode "
                    + "\"compile-error\""));
        }
        validateSidecarWith(requirement, fixturePath, expectedTag, fixture,
            corpusByPath, corpusModuleIndex, corpusRoot, sidecarPath,
            failures);
    }

    /** Delegates the sidecar decision to the T1 SidecarSchemaValidator. */
    private static void validateSidecarWith(String requirement,
            String fixturePath, String expectedTag, CorpusFixture fixture,
            Map<String, CorpusFixture> corpusByPath,
            Set<String> corpusModuleIndex, Path corpusRoot,
            Path sidecarPath, List<CoverageFailure> failures) {
        List<SidecarSchemaValidator.CompilationModule> compilationSet =
            compilationSetOf(fixturePath, fixture, corpusByPath, corpusRoot);
        SidecarSchemaValidator.ValidationContext context =
            new SidecarSchemaValidator.ValidationContext(fixturePath,
                expectedTag, compilationSet, corpusModuleIndex);
        Optional<SidecarSchemaValidator.ClassificationFailure> sidecarFailure =
            SidecarSchemaValidator.validate(context, readRaw(sidecarPath));
        if (sidecarFailure.isPresent()) {
            SidecarSchemaValidator.ClassificationFailure f =
                sidecarFailure.get();
            failures.add(failure(requirement, fixturePath, f.field(),
                f.reason()));
        }
    }

    /** The sidecar's {@code mode} string value, or {@code null} when absent/malformed. */
    private static String compileSidecarMode(String sidecarText) {
        try {
            CanonicalJson.Value root = CanonicalJson.parse(sidecarText);
            if (!(root instanceof CanonicalJson.Obj obj)) {
                return null;
            }
            for (CanonicalJson.Entry entry : obj.entries()) {
                if (entry.key().equals("mode")) {
                    return entry.value() instanceof CanonicalJson.Str str
                        ? str.value()
                        : null;
                }
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The sibling sidecar path of a corpus-relative fixture path (C2 naming). */
    private static Path sidecarFor(Path corpusRoot, String corpusPath) {
        String stem = corpusPath.endsWith(".deal")
            ? corpusPath.substring(0, corpusPath.length() - ".deal".length())
            : corpusPath;
        return corpusRoot.resolve(stem + ".expect.json");
    }

    // =========================================================================
    // Compilation-set resolution (transitive corpus import closure)
    // =========================================================================

    /**
     * The fixture's compilation set for the sidecar checks: the fixture
     * plus every transitively imported corpus module, each with its
     * corpus-relative path and header-stripped source. Relative imports
     * ({@code ./}, {@code ../}) resolve against the importing module's
     * corpus directory with the {@code .deal}/{@code .d.deal} fallback;
     * stdlib and host imports resolve outside the corpus and contribute
     * no module (the sidecar {@code sourceFile} rule never names them).
     */
    private static List<SidecarSchemaValidator.CompilationModule>
            compilationSetOf(String fixturePath, CorpusFixture fixture,
                Map<String, CorpusFixture> corpusByPath, Path corpusRoot) {
        String source;
        if (fixture != null) {
            source = fixture.source();
        } else {
            source = ConformanceHarnessMetadata.stripClassificationHeaders(
                readRaw(corpusRoot.resolve(fixturePath)));
        }
        List<SidecarSchemaValidator.CompilationModule> set =
            new ArrayList<>();
        collectCompilationModules(fixturePath, source, corpusByPath,
            corpusRoot, set, new HashSet<>());
        return set;
    }

    private static void collectCompilationModules(String corpusPath,
            String source, Map<String, CorpusFixture> corpusByPath,
            Path corpusRoot,
            List<SidecarSchemaValidator.CompilationModule> set,
            Set<String> inProgress) {
        if (!inProgress.add(corpusPath)) {
            return; // cycle guard — the module is already emitted once
        }
        set.add(new SidecarSchemaValidator.CompilationModule(corpusPath,
            source));
        for (String importPath : relativeImportPaths(source)) {
            String resolved = resolveRelativeImport(corpusPath, importPath,
                corpusRoot);
            if (resolved == null) {
                continue;
            }
            CorpusFixture dependency = corpusByPath.get(resolved);
            if (dependency != null) {
                collectCompilationModules(dependency.corpusPath(),
                    dependency.source(), corpusByPath, corpusRoot, set,
                    inProgress);
            }
        }
    }

    /** Every relative-import module path of the source, via the real parser. */
    private static List<String> relativeImportPaths(String source) {
        List<String> paths = new ArrayList<>();
        LexResult lex = new Lexer(source,
            "<coverage-manifest-compilation-set>").tokenize();
        if (lex.hasErrors()) {
            return paths;
        }
        ParseResult result = new Parser(lex.tokens(),
            "<coverage-manifest-compilation-set>", lex.directiveEvents())
            .parse();
        if (result.hasErrors()) {
            return paths;
        }
        for (StatementNode statement : result.program().statements()) {
            if (statement instanceof ImportDeclaration decl) {
                if (decl.modulePath().startsWith("./")
                        || decl.modulePath().startsWith("../")) {
                    paths.add(decl.modulePath());
                }
            }
        }
        return paths;
    }

    /**
     * Resolves a relative import against the importing module's corpus
     * directory, mirroring {@code ConformanceTest.resolveCompanionPath}:
     * the raw path, then the {@code .deal} and {@code .d.deal}
     * extensions. Returns the corpus-relative slash path, or
     * {@code null} when the resolution leaves the corpus or no file
     * exists.
     */
    private static String resolveRelativeImport(String importer,
            String importPath, Path corpusRoot) {
        Path parent = Path.of(importer).getParent();
        Path base = parent == null
            ? corpusRoot
            : corpusRoot.resolve(parent);
        for (Path candidate : new Path[] {
                base.resolve(importPath).normalize(),
                base.resolve(importPath + ".deal").normalize(),
                base.resolve(importPath + ".d.deal").normalize() }) {
            if (!candidate.startsWith(corpusRoot)) {
                continue;
            }
            if (Files.exists(candidate)) {
                return slash(corpusRoot.relativize(candidate));
            }
        }
        return null;
    }

    // =========================================================================
    // Benchmark artifact compilation (embedded backend-neutral v1.2 frontend)
    // =========================================================================

    private static void validateBenchmarkFixture(String requirement,
            String fixturePath, Path resolved, Path repoRoot,
            List<CoverageFailure> failures) {
        List<CompilerDiagnostic> errors =
            benchmarkCompileErrors(resolved, repoRoot);
        if (!errors.isEmpty()) {
            failures.add(failure(requirement, fixturePath, "benchmark",
                "the benchmark artifact does not compile under the "
                    + "embedded backend-neutral v1.2 frontend: "
                    + errors.get(0)));
        }
    }

    /**
     * Runs the backend-neutral v1.2 frontend (lexer → parser with the
     * v1.2 int32 profile → module shape gate → name resolution with the
     * spec stdlib surface → type checker) and returns every error
     * diagnostic.
     */
    @SuppressWarnings("deprecation")
    private static List<CompilerDiagnostic> benchmarkCompileErrors(
            Path artifact, Path repoRoot) {
        String filename = artifact.toString();
        String source = readRaw(artifact);
        if (source.isEmpty() && !Files.isRegularFile(artifact)) {
            return List.of(CompilerDiagnostic.synthetic("E9999", "error",
                "cannot read the benchmark artifact", filename,
                "missing anchor: benchmark artifact '" + filename + "'"));
        }
        List<CompilerDiagnostic> errors = new ArrayList<>();
        LexResult lex = new Lexer(source, filename).tokenize();
        errors.addAll(errorDiagnostics(lex.diagnostics()));
        if (lex.hasErrors()) {
            return errors;
        }
        Parser parser = new Parser(lex.tokens(), filename,
            SemanticProfile.DEAL_V1_2_INT32, lex.directiveEvents());
        ParseResult parseResult = parser.parse();
        errors.addAll(errorDiagnostics(parseResult.diagnostics()));
        if (parseResult.hasErrors()) {
            return errors;
        }
        errors.addAll(errorDiagnostics(ModuleShapeValidator.validate(
            parseResult.program(), filename, false)));
        if (!errors.isEmpty()) {
            return errors;
        }
        ModuleResolver resolver = new StdlibSurfaceResolver(repoRoot);
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable;
        try {
            symTable = nr.resolve(parseResult.program());
        } catch (Exception e) {
            errors.add(CompilerDiagnostic.synthetic("E9999", "error",
                e.getMessage(), filename,
                "missing anchor: benchmark artifact '" + filename + "'"));
            return errors;
        }
        errors.addAll(errorDiagnostics(nr.diagnostics()));
        CheckResult result = TypeChecker.check(filename, symTable, nr,
            parseResult.program());
        errors.addAll(errorDiagnostics(result.diagnostics()));
        return errors;
    }

    /** The error-severity diagnostics of a phase. */
    private static List<CompilerDiagnostic> errorDiagnostics(
            List<CompilerDiagnostic> diagnostics) {
        List<CompilerDiagnostic> errors = new ArrayList<>();
        for (CompilerDiagnostic diagnostic : diagnostics) {
            if ("error".equals(diagnostic.severity())) {
                errors.add(diagnostic);
            }
        }
        return errors;
    }

    /**
     * The benchmark name resolver: the spec stdlib surface
     * ({@link StdlibModuleResolver}) and nothing else — the benchmark
     * artifact is a standalone repo file, not a corpus module, so every
     * non-stdlib import is not found.
     */
    private static final class StdlibSurfaceResolver implements ModuleResolver {

        private final Map<String, Map<String, Type>> stdlibExports;

        StdlibSurfaceResolver(Path repoRoot) {
            this.stdlibExports = StdlibModuleResolver.stdlibExports(
                repoRoot.resolve("std").toAbsolutePath().normalize()
                    .toString());
        }

        @Override
        public Map<String, Type> resolveModule(String modulePath,
                String importingModule, Set<String> modulesInProgress)
                throws ModuleNotFoundException {
            Map<String, Type> exports = stdlibExports.get(modulePath);
            if (exports != null) {
                return exports;
            }
            throw new ModuleNotFoundException(
                "Module not found: " + modulePath);
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            return null;
        }
    }

    // =========================================================================
    // Small helpers
    // =========================================================================

    private static CoverageFailure failure(String requirement,
            String fixture, String field, String reason) {
        return new CoverageFailure(requirement, fixture, field, reason);
    }

    /** The exact metadata tag value of one header prefix (first 40 lines). */
    private static String readTag(String source, String prefix) {
        String[] lines = source.split("\n", -1);
        int linesToScan = Math.min(lines.length, 40);
        for (int i = 0; i < linesToScan; i++) {
            String line = lines[i].trim();
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static String readRaw(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }

    private static String slash(Path path) {
        return path.toString().replace(java.io.File.separatorChar, '/');
    }
}
