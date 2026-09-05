package deal.test.conformance;

import deal.ast.ImportDeclaration;
import deal.ast.StatementNode;
import deal.lexer.Lexer;
import deal.lexer.LexResult;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.test.ConformanceHarnessMetadata;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Corpus discovery, classification, and module-import resolution for the
 * v1.2 differential gate core (ISSUE-0353; the classification grammar is
 * the pre-flip form of the Zero-Skip Classification Contract,
 * {@code v12-zero-skip-conformance-gate} G2 — the {@code known-fail}
 * markers and their forced-promotion rules stay active until the flip
 * deletes them).
 *
 * <p>Discovery walks every {@code .deal} file under the conformance root
 * except the {@code host-fixtures} subtree and classifies each fixture
 * exactly once from {@code compile-ok}, {@code compile-error CODE},
 * {@code runtime-ok}, {@code runtime-error CODE}, {@code companion}, and
 * {@code known-fail MODE} (a mandatory {@code @issue} tag tracks the
 * owning follow-up issue). A missing/unknown {@code @expected}, a
 * missing/stale {@code @spec} (the SPEC_HEADINGS gate), or a
 * {@code known-fail} without {@code @issue} is a classification failure;
 * there is no fallback skip branch. {@code @expected: host-fixture} is
 * reserved for the excluded host-fixtures subtree and fails anywhere
 * else.</p>
 *
 * <p>Module-import resolution derives a runtime fixture's compilation set
 * — the fixture plus its transitively imported corpus modules — by
 * walking relative imports ({@code ./}, {@code ../}) through the real
 * lexer/parser, resolving each import against the importing module's
 * corpus directory with the {@code .deal}/{@code .d.deal} extension
 * fallback (the resolution rules of the existing
 * companion-catalog/module-discovery machinery,
 * {@code test/ConformanceTest.java resolveCompanionPath}); stdlib and
 * host imports resolve outside the corpus and contribute no module. The
 * compilation set feeds the sidecar validator's {@code sourceFile}
 * compilation-graph check and the divergent-form {@code @extern-c} check
 * (T1's {@link SidecarSchemaValidator} component).</p>
 */
public final class CorpusDiscovery {

    private CorpusDiscovery() {
        // Static utility; no instances.
    }

    // =========================================================================
    // Classification grammar (pre-flip)
    // =========================================================================

    /** The classification kinds of the pre-flip Zero-Skip grammar. */
    public enum Kind {
        COMPILE_OK, COMPILE_ERROR, RUNTIME_OK, RUNTIME_ERROR, COMPANION, KNOWN_FAIL
    }

    /**
     * One fixture classification. {@code code} carries the exact pinned
     * code for {@code COMPILE_ERROR}/{@code RUNTIME_ERROR};
     * {@code knownFailMode} carries the underlying mode of a
     * {@code KNOWN_FAIL} fixture (the mode token(s) after the
     * {@code known-fail } prefix, e.g. {@code compile-ok},
     * {@code runtime-error E8001}, {@code runtime-error any}).
     */
    public record Classification(Kind kind, String code, String knownFailMode) {

        public Classification {
            Objects.requireNonNull(kind, "kind must not be null");
        }

        /**
         * The underlying execution mode of the classification —
         * {@code compile-ok}, {@code compile-error CODE}, {@code
         * runtime-ok}, {@code runtime-error CODE} (a known-fail fixture
         * resolves to its tracked mode).
         */
        public String underlyingMode() {
            return switch (kind) {
                case COMPILE_OK -> "compile-ok";
                case RUNTIME_OK -> "runtime-ok";
                case COMPILE_ERROR -> "compile-error " + code;
                case RUNTIME_ERROR -> "runtime-error " + code;
                case KNOWN_FAIL -> knownFailMode;
                case COMPANION -> "companion";
            };
        }

        /**
         * The runtime sidecar mode this classification requires —
         * {@code "runtime-ok"}, {@code "runtime-error"}, or {@code null}
         * when the fixture is not runtime-classified (runtime-classified
         * includes a {@code known-fail} whose underlying mode is a
         * runtime mode).
         */
        public String runtimeSidecarMode() {
            String mode = underlyingMode();
            if (mode.equals("runtime-ok")) {
                return "runtime-ok";
            }
            if (mode.startsWith("runtime-error ")) {
                return "runtime-error";
            }
            return null;
        }

        /** The exact pinned code of a runtime-error sidecar, or null. */
        public String runtimeErrorCode() {
            String mode = underlyingMode();
            if (mode.startsWith("runtime-error ")) {
                return mode.substring("runtime-error ".length()).trim();
            }
            return null;
        }

        /** True when the classification is {@code compile-error CODE}. */
        public boolean exactCompileError() {
            return kind == Kind.COMPILE_ERROR;
        }
    }

    /**
     * One discovered corpus fixture: its absolute file, canonical
     * corpus-relative slash path, phase (first path component), the five
     * metadata header fields, its raw source bytes and its
     * classification-header-stripped source (every compiler-facing
     * surface consumes header-free source; the metadata still reads the
     * raw bytes — ISSUE-0272 D8), the stripped header-line count (line
     * pins are authored in raw-file coordinates and rebased by it), and
     * the classification ({@code null} when classification failed).
     */
    public record Fixture(
        Path file,
        String corpusPath,
        String phase,
        String spec,
        String description,
        String expected,
        String features,
        String issue,
        String rawSource,
        String source,
        int headerLinesStripped,
        Classification classification
    ) {

        public Fixture {
            Objects.requireNonNull(file, "file must not be null");
            Objects.requireNonNull(corpusPath, "corpusPath must not be null");
            Objects.requireNonNull(phase, "phase must not be null");
            Objects.requireNonNull(spec, "spec must not be null");
            Objects.requireNonNull(description, "description must not be null");
            Objects.requireNonNull(expected, "expected must not be null");
            Objects.requireNonNull(features, "features must not be null");
            Objects.requireNonNull(issue, "issue must not be null");
            Objects.requireNonNull(rawSource, "rawSource must not be null");
            Objects.requireNonNull(source, "source must not be null");
        }

        /** True when the fixture is runtime-classified (sidecar required). */
        public boolean runtimeClassified() {
            return classification != null
                && classification.runtimeSidecarMode() != null;
        }

        /** The sibling sidecar path of the fixture (same stem). */
        public Path sidecarPath(Path conformanceRoot) {
            String stem = corpusPath.endsWith(".deal")
                ? corpusPath.substring(0, corpusPath.length() - ".deal".length())
                : corpusPath;
            return conformanceRoot.resolve(stem + ".expect.json");
        }
    }

    /** The complete discovery outcome: fixtures plus classification failures. */
    public record DiscoveryResult(
        List<Fixture> fixtures, List<SidecarSchemaValidator.ClassificationFailure> failures) {

        public DiscoveryResult {
            fixtures = List.copyOf(fixtures);
            failures = List.copyOf(failures);
        }

        /** The corpus module index: every discovered corpus-relative path. */
        public Set<String> corpusModuleIndex() {
            Set<String> index = new LinkedHashSet<>();
            for (Fixture fixture : fixtures) {
                index.add(fixture.corpusPath());
            }
            return index;
        }

        /** The discovered corpus keyed by corpus-relative path. */
        public Map<String, Fixture> corpusByPath() {
            Map<String, Fixture> byPath = new HashMap<>();
            for (Fixture fixture : fixtures) {
                byPath.put(fixture.corpusPath(), fixture);
            }
            return byPath;
        }
    }

    /**
     * The v1.2 spec sections a {@code @spec} reference may start with —
     * the SPEC_HEADINGS gate (mirrors {@code test/ConformanceTest.java}).
     */
    private static final Set<String> SPEC_HEADINGS = Set.of(
        "Lexical elements",
        "Syntactic grammar",
        "Type system",
        "Classes",
        "Functions",
        "Variables",
        "Tables",
        "Arrays",
        "Bytes",
        "Control flow",
        "Error handling",
        "Async/Await",
        "Modules, declarations, standard library, and host ABI",
        "C FFI declaration files",
        "Runtime execution model",
        "Standard library declarations",
        "Test suite basis",
        "Diagnostics"
    );

    private static boolean validSpecReference(String spec) {
        String first = spec.split(" \u2014 ", 2)[0].trim();
        return SPEC_HEADINGS.contains(first);
    }

    // =========================================================================
    // Discovery + classification
    // =========================================================================

    /**
     * Discovers and classifies every {@code .deal} file under
     * {@code conformanceRoot} (absolute, normalized) except the
     * {@code host-fixtures} subtree, in corpus-path order. Classification
     * failures accumulate into {@link DiscoveryResult#failures()} and the
     * affected fixture keeps {@code classification == null}; discovery is
     * read-only and deterministic.
     */
    public static DiscoveryResult discover(Path conformanceRoot) throws IOException {
        Objects.requireNonNull(conformanceRoot, "conformanceRoot must not be null");
        Path root = conformanceRoot.toAbsolutePath().normalize();
        List<Fixture> fixtures = new ArrayList<>();
        List<SidecarSchemaValidator.ClassificationFailure> failures =
            new ArrayList<>();

        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream
                .filter(p -> p.toString().endsWith(".deal"))
                .filter(p -> {
                    Path rel = root.relativize(p);
                    return rel.getNameCount() == 0
                        || !"host-fixtures".equals(rel.getName(0).toString());
                })
                .sorted()
                .toList();
            for (Path file : files) {
                String corpusPath = slash(root.relativize(file));
                try {
                    String raw = Files.readString(file);
                    Fixture parsed = parseFixture(root, file, corpusPath, raw);
                    Classification classification = classify(corpusPath,
                        parsed.spec(), parsed.expected(), parsed.issue(), failures);
                    fixtures.add(new Fixture(parsed.file(), parsed.corpusPath(),
                        parsed.phase(), parsed.spec(), parsed.description(),
                        parsed.expected(), parsed.features(), parsed.issue(),
                        parsed.rawSource(), parsed.source(),
                        parsed.headerLinesStripped(), classification));
                } catch (IOException e) {
                    failures.add(new SidecarSchemaValidator.ClassificationFailure(
                        corpusPath, "<file>", "cannot read: " + e.getMessage()));
                }
            }
        }
        return new DiscoveryResult(fixtures, failures);
    }

    private static Fixture parseFixture(Path root, Path file, String corpusPath,
            String raw) {
        String[] lines = raw.split("\n", -1);
        String spec = "";
        String description = "";
        String expected = "";
        String features = "";
        String issue = "";
        int linesToScan = Math.min(lines.length, 40);
        for (int i = 0; i < linesToScan; i++) {
            String line = lines[i].trim();
            if (line.startsWith("// @spec:")) {
                spec = line.substring("// @spec:".length()).trim();
            } else if (line.startsWith("// @description:")) {
                description = line.substring("// @description:".length()).trim();
            } else if (line.startsWith("// @expected:")) {
                expected = line.substring("// @expected:".length()).trim();
            } else if (line.startsWith("// @features:")) {
                features = line.substring("// @features:".length()).trim();
            } else if (line.startsWith("// @issue:")) {
                issue = line.substring("// @issue:".length()).trim();
            }
        }
        String source = ConformanceHarnessMetadata.stripClassificationHeaders(raw);
        int headerLinesStripped =
            raw.split("\n", -1).length - source.split("\n", -1).length;
        String phase = corpusPath.indexOf('/') >= 0
            ? corpusPath.substring(0, corpusPath.indexOf('/'))
            : "";
        return new Fixture(file.toAbsolutePath().normalize(), corpusPath, phase,
            spec, description, expected, features, issue, raw, source,
            headerLinesStripped, null);
    }

    /**
     * Classifies one fixture against the pre-flip grammar and records the
     * first classification failure (if any) into {@code failures}.
     * Returns the classification, or {@code null} when classification
     * failed. The check order is fixed, so classification is
     * deterministic.
     */
    public static Classification classify(String corpusPath, String spec,
            String expected, String issue,
            List<SidecarSchemaValidator.ClassificationFailure> failures) {
        if (expected.isEmpty()) {
            failure(failures, corpusPath, "@expected", "no @expected tag — the "
                + "v1.2 gate has no unclassified skips; classify the file as a "
                + "test or as '@expected: companion'");
            return null;
        }
        if (expected.equals("companion")) {
            if (!spec.isEmpty() && !validSpecReference(spec)) {
                failure(failures, corpusPath, "@spec", "companion @spec '" + spec
                    + "' does not reference a v1.2 spec section");
                return null;
            }
            return new Classification(Kind.COMPANION, null, null);
        }
        if (expected.equals("host-fixture")) {
            failure(failures, corpusPath, "@expected",
                "@expected: host-fixture is reserved for "
                    + "test/conformance/host-fixtures/*.d.deal");
            return null;
        }
        if (spec.isEmpty() || !validSpecReference(spec)) {
            failure(failures, corpusPath, "@spec", "missing or stale @spec '"
                + spec + "' — the v1.2 gate requires every expectation to "
                + "reference a v1.2 spec section");
            return null;
        }
        if (expected.startsWith("known-fail ")) {
            String mode = expected.substring("known-fail ".length()).trim();
            String modeError = knownFailModeError(mode);
            if (modeError != null) {
                failure(failures, corpusPath, "@expected", modeError);
                return null;
            }
            if (issue.isEmpty()) {
                failure(failures, corpusPath, "@issue", "@expected: known-fail "
                    + "requires a // @issue: <tracked follow-up issue> tag");
                return null;
            }
            return new Classification(Kind.KNOWN_FAIL, null, mode);
        }
        if (expected.startsWith("compile-error ")
                || expected.startsWith("runtime-error ")) {
            String code = expected.substring(expected.indexOf(' ') + 1).trim();
            if (code.isEmpty() || code.equals("any")) {
                failure(failures, corpusPath, "@expected", "'" + expected
                    + "' must name one specific diagnostic code (the 'any' "
                    + "form is reserved for @expected: known-fail)");
                return null;
            }
            boolean isCompile = expected.startsWith("compile-error ");
            return new Classification(isCompile ? Kind.COMPILE_ERROR
                : Kind.RUNTIME_ERROR, code, null);
        }
        if (expected.equals("compile-ok")) {
            return new Classification(Kind.COMPILE_OK, null, null);
        }
        if (expected.equals("runtime-ok")) {
            return new Classification(Kind.RUNTIME_OK, null, null);
        }
        failure(failures, corpusPath, "@expected", "unknown @expected '"
            + expected + "' — the v1.2 gate has no unclassified skips");
        return null;
    }

    private static void failure(
            List<SidecarSchemaValidator.ClassificationFailure> failures,
            String corpusPath, String field, String reason) {
        failures.add(new SidecarSchemaValidator.ClassificationFailure(
            corpusPath, field, reason));
    }

    /**
     * Returns an error description when {@code mode} is not a supported
     * known-fail mode, or {@code null} when it is (mirrors
     * {@code test/ConformanceTest.java}).
     */
    private static String knownFailModeError(String mode) {
        if (mode.equals("compile-ok") || mode.equals("runtime-ok")) {
            return null;
        }
        if (mode.startsWith("compile-error ") || mode.startsWith("runtime-error ")) {
            String code = mode.substring(mode.indexOf(' ') + 1).trim();
            if (code.isEmpty()) {
                return "known-fail '" + mode + "' must name a diagnostic code "
                    + "(or 'any')";
            }
            return null;
        }
        return "unknown known-fail mode '" + mode
            + "' (supported: compile-ok, compile-error CODE|any, "
            + "runtime-ok, runtime-error CODE|any)";
    }

    // =========================================================================
    // Module-import resolution (the compilation set)
    // =========================================================================

    /**
     * Derives the fixture's compilation set for the sidecar validator's
     * {@code sourceFile} compilation-graph check and the divergent-form
     * {@code @extern-c} check: the fixture itself plus every transitively
     * imported corpus module, each with its corpus-relative slash path and
     * its header-stripped source text. Depth-first, cycle-guarded,
     * deterministic.
     */
    public static List<SidecarSchemaValidator.CompilationModule> compilationSet(
            Fixture fixture, Map<String, Fixture> corpusByPath, Path conformanceRoot) {
        Objects.requireNonNull(fixture, "fixture must not be null");
        Objects.requireNonNull(corpusByPath, "corpusByPath must not be null");
        Objects.requireNonNull(conformanceRoot, "conformanceRoot must not be null");
        List<SidecarSchemaValidator.CompilationModule> set = new ArrayList<>();
        collectCompilationModules(fixture.corpusPath(), fixture.source(),
            corpusByPath, conformanceRoot, set, new HashSet<>());
        return set;
    }

    private static void collectCompilationModules(String corpusPath,
            String source, Map<String, Fixture> corpusByPath, Path conformanceRoot,
            List<SidecarSchemaValidator.CompilationModule> set,
            Set<String> inProgress) {
        if (!inProgress.add(corpusPath)) {
            return; // cycle guard — the module is already emitted once
        }
        set.add(new SidecarSchemaValidator.CompilationModule(corpusPath, source));
        for (String importPath : relativeImportPaths(source)) {
            String resolved = resolveRelativeImport(corpusPath, importPath,
                conformanceRoot);
            if (resolved == null) {
                continue;
            }
            Fixture dependency = corpusByPath.get(resolved);
            if (dependency != null) {
                collectCompilationModules(dependency.corpusPath(),
                    dependency.source(), corpusByPath, conformanceRoot, set,
                    inProgress);
            }
        }
    }

    /**
     * Every relative-import module path of the source, via the real
     * lexer/parser. A source that fails to lex or parse yields no paths
     * (fail closed: the compilation set then contains only what could be
     * proven).
     */
    public static List<String> relativeImportPaths(String source) {
        List<String> paths = new ArrayList<>();
        LexResult lex = new Lexer(source, "<corpus-import-resolution>").tokenize();
        if (lex.hasErrors()) {
            return paths;
        }
        ParseResult result = new Parser(lex.tokens(),
            "<corpus-import-resolution>", lex.directiveEvents()).parse();
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
     * the raw path, then the {@code .deal} and {@code .d.deal} extensions.
     * Returns the corpus-relative slash path, or {@code null} when the
     * resolution leaves the corpus or no file exists.
     */
    public static String resolveRelativeImport(String importer,
            String importPath, Path corpusRoot) {
        Path root = corpusRoot.toAbsolutePath().normalize();
        Path parent = Path.of(importer).getParent();
        Path base = parent == null ? root : root.resolve(parent);
        Path resolved = base.resolve(importPath).normalize();
        String name = resolved.getFileName().toString();
        // The legacy candidate order (ConformanceTest.resolveRelativePath):
        // the exact path as a regular file (a directory landing is never a
        // module), then <name>.deal, the directory index <name>/index.deal,
        // <name>.d.deal, and the declaration index <name>/index.d.deal.
        List<Path> candidates = new ArrayList<>(List.of(
            resolved,
            resolved.resolveSibling(name + ".deal"),
            resolved.resolve("index.deal"),
            resolved.resolveSibling(name + ".d.deal"),
            resolved.resolve("index.d.deal")));
        for (Path candidate : candidates) {
            if (!candidate.startsWith(root)) {
                continue;
            }
            if (Files.isRegularFile(candidate)) {
                return slash(root.relativize(candidate));
            }
        }
        return null;
    }

    /** The canonical slash-separated relative path. */
    public static String slash(Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }
}
