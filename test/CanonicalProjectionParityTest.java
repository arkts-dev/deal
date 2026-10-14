package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.jvm.JvmRuntime;
import deal.codegen.lua.LuaSemanticEmitter;
import deal.identity.CanonicalModuleIdentity;
import deal.module.CompilationOrchestrator;
import deal.module.ProductionProjectEmission;
import deal.publication.PublicationStager;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CheckedProjectInput;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticOracle;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ActualKind;
import deal.semantic.ir.BoundaryContext;
import deal.semantic.ir.BoundaryExecutor;
import deal.semantic.ir.BoundaryFailure;
import deal.semantic.ir.BoundaryOutcome;
import deal.semantic.ir.BoundaryValueView;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.FailureArm;
import deal.semantic.ir.FailureArmId;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FailurePolicyRow;
import deal.semantic.ir.FailureProjections;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.UnicodeScalars;
import deal.test.conformance.CorpusDiscovery;
import deal.test.conformance.JvmCompilation;
import deal.test.conformance.SidecarExpectations;
import deal.test.conformance.SidecarSchemaValidator;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The canonical-projection parity verification
 * ({@code canonical-failure-projection-authority} Verification 1-6): the
 * named canonical divergences byte-exact through the release-owned
 * production pipeline on the oracle, the LuaJIT artifact under
 * {@code luajit}, and the JVM artifact under {@code javac --release 25
 * -proc:none} plus {@code java}; the per-arm three-consumer tuple
 * identity; the {@code FOR_EACH} terminal check over a deleted
 * array-element slot; the row/arm completeness and single-source
 * negatives; and the unchanged-surface accounting.
 *
 * <ol>
 *   <li><b>The named divergences.</b> Five corpus fixtures compile
 *       through the release-owned production invocation with the
 *       lane-equivalent materialization (the corpus-relative mirror, the
 *       header-stripped compilation set, the host declaration files and
 *       the corpus externals map with the dotted class-identity key
 *       first, and the drive's own entry module for a probe export) and
 *       every capture is rebased onto raw corpus coordinates. Each of the
 *       three consumers reproduces its sidecar's pinned {@code code},
 *       {@code message}, span group, {@code expected}, and {@code actual}
 *       byte-exact, and the three tuples are identical.</li>
 *   <li><b>The arm families through the production pipeline.</b> A closed
 *       fixture list covers the reachable typed-boundary, class,
 *       array-element, signature, int-ladder, signed32-range, conversion,
 *       {@code std/json}, host parameter/return/nothing/string-carrier,
 *       async-shape, completion, class-construction, and host-load
 *       families; each drive asserts the sidecar pins per consumer and the
 *       three-consumer tuple identity. The oracle's host seam retains the
 *       invalid returned async handle's own actual projection (the
 *       non-operation async handle reaches the closed arm with the
 *       carrier's kind, never the absent handle's {@code nothing}, and a
 *       reference-cyclic array carrier classifies by its outer kind
 *       exactly like both production artifacts). The host
 *       string-carrier producers drive the four closed carrier responses
 *       (lone surrogate, embedded surrogate, malformed non-surrogate, and
 *       valid) through a generated host-importing entry: every invalid
 *       class renders the complete pinned tuple on all three consumers, and
 *       every valid control's decoded value (the raw UTF-8 byte carrier at
 *       scalar, array, nested-array, nullable-element, and nullable
 *       nested-array positions) is consumed by the entry body. A declared
 *       nested tail that fails at a later element renders the element
 *       composite on all three consumers, and the JVM probe observes that
 *       the rejected return left every retained slot and the nested array
 *       alias untouched.</li>
 *   <li><b>The namespace surface at the array boundaries.</b> The
 *       {@code lib} module's identity-keyed namespace surface (a plain
 *       table without a carrier mark) is stored in a table field through
 *       the import alias cell and read at the declared {@code int[]} and
 *       {@code int[][]} bindings: the unmarked table carrier rejects with
 *       the value-derived {@code table} actual token on all three
 *       consumers, never the expected {@code array} kind substituted for
 *       its own representation. A real array carrier at the {@code table}
 *       descriptor keeps its own {@code array} token in the same matrix.
 *       Every seed is additionally driven through the release-owned
 *       production endpoints — the compiled LuaJIT project artifact the
 *       invocation publishes and the staged JVM production artifact under
 *       {@code javac --release 25 -proc:none} plus {@code java} — and each
 *       captured tuple is field-identical to the oracle's, so a
 *       regression confined to production emission, publication, or
 *       failure transport cannot pass the trace matrix alone.</li>
 *   <li><b>The synchronous host result presence.</b> The recorded
 *       {@code HOST_SYNC_RETURN} cell selects
 *       {@code HOST_SYNC_RETURN_NOTHING} for a host invocation that
 *       produced no result at all — the oracle's absent terminal, the
 *       LuaJIT wrapper's zero-result return, the JVM host surface's
 *       declared void method — at a {@code null}, a nullable, and a
 *       mandatory return, while a present language null is admitted by a
 *       {@code null} or nullable return and never collapses into absence;
 *       the corpus-pinned {@code host-empty-return-bad} cell additionally
 *       drives the full program on all three consumers.</li>
 *   <li><b>The closed arm table's renderer identity.</b> Every declared
 *       arm renders its own template with the same named parameters,
 *       expected token, and actual token from the registry renderer, the
 *       emitted Lua prelude under {@code luajit}, and the JVM runtime
 *       renderer; the field shapes fail closed, the two inner-only string
 *       carriers never render at a boundary site, and the bound walk arm
 *       (the typed-boundary projection at the call origin, ISSUE-0711)
 *       renders through the same closed table. The host inner-reason
 *       vocabulary and its pass-throughs are driven through the oracle's
 *       host cells, the JVM runtime's own producers, and the deployed
 *       matcher under {@code luajit}.</li>
 *   <li><b>The {@code FOR_EACH} terminal check.</b> A deleted
 *       array-element slot inside a typed for-of publishes the kind arm's
 *       suffix-less text with expected {@code int} and actual {@code nil}
 *       at the op's own origin on all three consumers.</li>
 *   <li><b>The array-literal element cells.</b> Every
 *       {@code ARRAY_LITERAL_ELEMENT} child's recorded
 *       {@code ARRAY_ELEMENT_DESCRIPTOR} policy renders the canonical
 *       element arm — the literal's one-based index, the element
 *       descriptor's canonical expected text, the failing value's own
 *       reference-kind actual token, and the boundary child's own
 *       lowerer-owned origin — identically on the three consumers for
 *       the composite element, the nested literal's element, the second
 *       literal position, a DEAL table carrier, and a nested element
 *       walk failure, while the admitted deferred and absent nullable
 *       elements succeed and the production artifacts publish the same
 *       code; the emitted Lua prelude's {@code __typedBoundaryKind}
 *       projects a mark-less table as the actual {@code table} token at
 *       an array-named cell (never the expected array kind) under real
 *       {@code luajit}.</li>
 *   <li><b>The negatives.</b> The row/arm completeness invariants and the
 *       single-source control fail by name (an unbound retained template,
 *       a foreign template, a duplicate binding, an {@code INNER_ONLY}
 *       arm rendered top-level, a hand-built arm whose binding slot is
 *       {@code SIBLING_OWNED} fed to the completeness check, and a
 *       deliberately composing consumer reported as {@code message}/{@code
 *       span}).</li>
 *   <li><b>The origin cells.</b> The pinned span group is asserted for
 *       every pinned fixture, and the unpinned {@code MODULE_EXPORT} cell
 *       and the host-driven callback entry slot ({@code HOST_TO_DEAL}
 *       under {@code CALLBACK_INVOKE}) are asserted against their landed
 *       emission: the export publication and the callback's parameter
 *       slots carry the invoking unit's program span in the lowering and
 *       in the three consumers' render.</li>
 *   <li><b>The unchanged surfaces.</b> The corpus membership/count (the
 *       dispatched runtime-classified 409), the sidecar schema (version
 *       and the landed three backends), the comparison contract (mandatory
 *       {@code code}/{@code message}; the span group and optional fields
 *       only when pinned; a pinned field the capture lacks fails; nothing
 *       fabricated), and the landed {@code INT32_RESULT} row data stays as
 *       landed while the {@code JSON_TO_ERROR} row carries the appended
 *       cycle template bound to its own arm.</li>
 * </ol>
 *
 * <p>Every assertion reports the fixture, the consumer, and the field it
 * compared; the drive is read-only over the corpus (a temp mirror per
 * fixture, deleted on completion) and stages no repository artifact.</p>
 */
public class CanonicalProjectionParityTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void checkEq(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + " (expected " + expected
            + ", got " + actual + ")");
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    private static final Path CONFORMANCE = Path.of("test", "conformance");
    private static final String BACKEND_RUNTIME = "backend-runtime";
    private static CorpusSnapshot corpusSnapshot;
    private static String armChunk;

    private record CorpusSnapshot(CorpusDiscovery.DiscoveryResult discovery,
                                  Map<String, CorpusDiscovery.Fixture> byPath) {
        CorpusSnapshot {
            byPath = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byPath));
        }
    }

    private static CorpusSnapshot corpusSnapshot() throws Exception {
        if (corpusSnapshot == null) {
            corpusSnapshot = corpusSnapshot(CONFORMANCE);
        }
        return corpusSnapshot;
    }

    /**
     * Loads one corpus root (CD3): the original classification metadata of
     * every raw {@code .deal} source is validated against the production
     * profile before discovery runs, so an unknown or duplicate
     * declaration is the named gate-input defect instead of
     * {@link CorpusDiscovery}'s unclassified metadata exception, and no
     * metadata-selected invocation can be reached. Discovery's own
     * unrelated errors stay distinct.
     */
    static CorpusSnapshot corpusSnapshot(Path root) throws Exception {
        Path absolute = root.toAbsolutePath().normalize();
        validateRawProfiles(absolute);
        CorpusDiscovery.DiscoveryResult discovery =
            CorpusDiscovery.discover(absolute);
        Map<String, CorpusDiscovery.Fixture> byPath = new LinkedHashMap<>();
        for (CorpusDiscovery.Fixture fixture : discovery.fixtures()) {
            byPath.put(fixture.corpusPath(), fixture);
        }
        return new CorpusSnapshot(discovery, byPath);
    }

    /**
     * Validates every raw {@code .deal} source of the root that discovery
     * would read (the {@code host-fixtures} subtree excluded), before
     * discovery can propagate an unclassified metadata exception.
     */
    private static void validateRawProfiles(Path root) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(p -> p.toString().endsWith(".deal"))
                .filter(p -> {
                    Path rel = root.relativize(p);
                    return rel.getNameCount() == 0 || !"host-fixtures".equals(
                        rel.getName(0).toString());
                })
                .sorted()
                .toList();
        }
        for (Path file : files) {
            String raw;
            try {
                raw = Files.readString(file);
            } catch (IOException unreadable) {
                continue; // discovery records its own classification failure
            }
            ConformanceHarnessMetadata.productionCorpusSource(raw,
                slash(root.relativize(file)));
        }
    }

    private static String slash(Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }

    /** One rendered failure tuple: the five compared fields. */
    private record Tuple(String code, String message, String file, Integer line,
                         Integer column, String expected, String actual) {

        /** The span group's canonical text, or {@code "-"} when absent. */
        String span() {
            return file == null ? "-" : file + ":" + line + ":" + column;
        }
    }

    /**
     * The three-consumer comparison (Verification 3): the first differing
     * field's name, or {@code null} when the tuples agree. A consumer that
     * composes its own text or picks its own span is reported by the name
     * of the field it composed.
     */
    private static String firstDifferingField(Tuple reference, Tuple candidate) {
        if (!Objects.equals(reference.code(), candidate.code())) {
            return "code";
        }
        if (!Objects.equals(reference.message(), candidate.message())) {
            return "message";
        }
        if (!Objects.equals(reference.span(), candidate.span())) {
            return "span";
        }
        if (!Objects.equals(reference.expected(), candidate.expected())) {
            return "expected";
        }
        if (!Objects.equals(reference.actual(), candidate.actual())) {
            return "actual";
        }
        return null;
    }

    // =========================================================================
    // The closed fixture cases
    // =========================================================================

    /** How the drive reaches the failing call. */
    private enum Entry {
        /** The fixture's own {@code main} is the entry (the failure is in it). */
        MAIN,
        /** The drive's own entry imports the fixture and calls its probe. */
        DRIVER,
        /** The fixture's async export is invoked directly. */
        ASYNC
    }

    /**
     * One driven corpus fixture: its corpus-relative path, its probe
     * export, the entry form, its companion modules, its host declaration
     * stem, and its oracle strategy. The pins are read from the sidecar at
     * run time, never re-authored here.
     */
    private record Case(String corpusPath, String probe, Entry entry,
                        List<String> companions, String hostStem,
                        String oracleResponder) {

        String fixtureFile() {
            return BACKEND_RUNTIME + "/" + corpusPath + ".deal";
        }

        String sidecarFile() {
            return BACKEND_RUNTIME + "/" + corpusPath + ".expect.json";
        }

        String what() {
            return corpusPath;
        }
    }

    private static Case mainEntry(String path) {
        return new Case(path, "main", Entry.MAIN, List.of(), null, null);
    }

    private static Case driver(String path, String probe, String... companions) {
        return new Case(path, probe, Entry.DRIVER, List.of(companions), null, null);
    }

    private static Case async(String path, String probe, String hostStem) {
        return new Case(path, probe, Entry.ASYNC, List.of(), hostStem,
            "async:" + hostStem);
    }

    private static Case host(String path, String probe, String hostStem) {
        return new Case(path, probe, Entry.DRIVER, List.of(), hostStem, null);
    }

    /**
     * The four named canonical divergences whose failing call is an
     * exported probe (the fifth, the absent array element, fails in the
     * fixture's own {@code main}).
     */
    private static final List<Case> NAMED = List.of(
        mainEntry("source-location/036-runtime-source-array-oob"),
        driver("runtime-errors/type-mismatch-e8001", "test_runtime_type_mismatch"),
        driver("arithmetic/int-convert-fraction", "test_int_fraction"),
        driver("runtime/int-convert-noninteger", "test_int_noninteger"),
        driver("class-runtime-errors/dynamic-bad-imported-class-param-e8001",
            "test_dynamic_bad_imported_class_param",
            "class-runtime-errors/imported_class_lib"));

    /** The remaining reachable arm families with a corpus pin. */
    private static final List<Case> FAMILIES = List.of(
        driver("type-system/dynamic-to-int-param-e8001",
            "test_dynamic_to_int_param_runtime_error"),
        driver("class-runtime-errors/dynamic-bad-class-param-e8001",
            "test_dynamic_bad_class_param"),
        driver("modules/modid-class-identity", "test_cross_module_class_identity",
            "modules/modid_lib_a/lib_a", "modules/modid_lib_b/lib_b"),
        driver("type-system/dynamic-array-element-e8003",
            "test_dynamic_array_element_runtime_error"),
        driver("descriptors/canonical-sig-mismatch-e8010",
            "test_canonical_sig_mismatch"),
        driver("arithmetic/int-convert-nan", "test_int_nan"),
        driver("arithmetic/int-convert-infinity", "test_int_infinity"),
        driver("arithmetic/int-add-overflow", "test_int_add_overflow"),
        driver("runtime/int-convert-null", "test_int_null"),
        driver("runtime/number-convert-null", "test_number_null"),
        driver("runtime-errors/json-stringify-function-e8001",
            "test_json_stringify_function_error"),
        driver("type-system/dynamic-return-e8001",
            "test_dynamic_return_runtime_error"),
        driver("arrays/index-negative-read", "test_array_negative_read"));

    /** The host-boundary families with a corpus pin. */
    private static final List<Case> HOST_FAMILIES = List.of(
        host("host-abi/host-bytes-param-mismatch-e8010",
            "test_host_bytes_param_mismatch", "bytes_roundtrip"),
        host("host-abi/host-empty-return-bad", "test_host_empty_return_bad",
            "empty_return"),
        host("host-abi/host-nullable-return-bad", "test_host_nullable_return_bad",
            "nullable_return"),
        host("host-abi/host-invalid-utf8-e8010", "test_host_bad_utf8", "bad_string"),
        host("host-abi/host-surrogate-utf8-e8010", "test_host_surrogate_utf8",
            "bad_string"),
        host("host-abi/host-nullable-function-param-bad",
            "test_host_nullable_function_param_bad", "nullable_fn"),
        host("host-abi/host-rest-bad", "test_host_rest_bad", "rest_join"),
        host("host-abi/host-class-extra-field", "test_host_class_extra_field",
            "presence"),
        host("host-abi/host-bad-return", "test_host_bad_return", "bad_return"),
        host("host-abi/host-missing-export", "test_host_missing_export",
            "missing_export"),
        async("host-abi/host-async-shape-bad", "test_host_async_shape_bad",
            "async_shape_bad"),
        async("host-abi/host-async-bad", "test_host_async_bad", "async_bad"));

    // =========================================================================
    // The lane-equivalent materialization and the production invocation
    // =========================================================================

    /** One compiled and lowered corpus project. */
    private record Compiled(
        Path root,
        Path mirror,
        Case spec,
        CompilerInvocation invocation,
        CheckedProjectInput checkedProject,
        ProjectInterfaceIndex index,
        List<SemanticRequirementManifest> manifests,
        HostDeclarationSurface surface,
        Map<ModuleId, CanonicalModuleIdentity> identities,
        Map<ModuleId, deal.ffi.FfiGeneratedModule> externCModules,
        Map<String, Integer> strippedByCorpusPath,
        ExecutableLoweredProject project,
        Map<ModuleId, StructuredBodyTable> tables,
        Map<ModuleId, ClassFactoryRegistry> registries,
        Set<String> hostStems,
        Map<String, String> modulePathToCorpus,
        Map<String, String> moduleDirToCorpus,
        JvmPreparation jvmPreparation) {
    }

    private record JvmDrive(String probeSource, Map<String, String> hostSources) {
        JvmDrive {
            hostSources = Map.copyOf(hostSources);
        }
    }

    private static final class JvmPreparation {
        private boolean emitted;
        private Path artifactClasses;
        private final Map<JvmDrive, Path> drives = new LinkedHashMap<>();
        private final Map<String, Path> probes = new LinkedHashMap<>();
        private Map<String, String> defaultHosts;
    }

    /** The release-owned production invocation. */
    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    /**
     * Materializes one corpus case exactly as the lanes do: the corpus
     * mirror at the corpus-relative paths, the header-stripped sources,
     * the plain and explicit-{@code .deal} companion copies, the host
     * declaration files under {@code bindings/} with the corpus externals
     * map (the dotted class-identity key first, then the raw import key),
     * the extern-{@code C} declarations of a {@code candidate/*} import,
     * and the drive's own entry module for a probe export.
     */
    private static Compiled compile(Case spec) throws Exception {
        return compile(spec, null);
    }

    /**
     * Materializes one corpus case, optionally with the drive's own entry
     * module supplied by the caller (the host-producer cases import the
     * host module directly and call the declared export at a fixed
     * position, so the generated module carries the pinned call origin).
     */
    private static Compiled compile(Case spec, String driverSource)
            throws Exception {
        return compile(spec, driverSource, Map.of());
    }

    /**
     * Materializes one corpus case with caller-owned host declarations for
     * the named stems (a drive whose declaration shape no on-disk
     * host-fixture carries writes its own declaration into the scratch
     * mirror, so no corpus or host-fixture membership changes).
     */
    private static Compiled compile(Case spec, String driverSource,
            Map<String, String> hostDeclarations) throws Exception {
        Map<String, CorpusDiscovery.Fixture> byPath = corpusSnapshot().byPath();
        CorpusDiscovery.Fixture entryFixture = byPath.get(spec.fixtureFile());
        if (entryFixture == null) {
            throw new IllegalStateException("the corpus carries no fixture "
                + spec.fixtureFile());
        }
        List<SidecarSchemaValidator.CompilationModule> compilationSet =
            CorpusDiscovery.compilationSet(entryFixture, byPath, CONFORMANCE);
        // The original classification metadata of every materialized module
        // is validated (CD3) before any scratch root exists: a rejected
        // declaration is a named gate-input defect and never allocates — and
        // so can never leak — an owned temporary root. Every later
        // preparation failure removes the allocated root and reports a
        // cleanup failure on the original failure.
        for (SidecarSchemaValidator.CompilationModule module : compilationSet) {
            CorpusDiscovery.Fixture moduleFixture = byPath.get(
                module.corpusPath());
            ConformanceHarnessMetadata.productionCorpusSource(
                moduleFixture.rawSource(), moduleFixture.corpusPath());
        }
        Path root = Files.createTempDirectory("projection-parity");
        try {
            return compileInto(root, spec, compilationSet, byPath, driverSource,
                hostDeclarations);
        } catch (Exception | Error failure) {
            cleanupRejectedScratch(root, failure);
            throw failure;
        }
    }

    private static Compiled compileInto(Path root, Case spec,
            List<SidecarSchemaValidator.CompilationModule> compilationSet,
            Map<String, CorpusDiscovery.Fixture> byPath, String driverSource,
            Map<String, String> hostDeclarations) throws Exception {
        Path mirror = root.resolve("conformance");
        Map<String, Integer> stripped = new LinkedHashMap<>();
        Map<String, String> modules = new LinkedHashMap<>();
        for (SidecarSchemaValidator.CompilationModule module : compilationSet) {
            CorpusDiscovery.Fixture fixture = byPath.get(module.corpusPath());
            modules.put(module.corpusPath(), fixture.source());
            stripped.put(module.corpusPath(), fixture.headerLinesStripped());
        }
        Path entry = null;
        Map<String, String> modulePathToCorpus = new LinkedHashMap<>();
        Map<String, String> moduleDirToCorpus = new LinkedHashMap<>();
        for (Map.Entry<String, String> module : modules.entrySet()) {
            Path target = mirror.resolve(module.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, module.getValue(), StandardCharsets.UTF_8);
            if (module.getKey().equals(spec.fixtureFile())) {
                entry = target;
            }
            String modulePath = module.getKey().endsWith(".deal")
                ? module.getKey().substring(0,
                    module.getKey().length() - ".deal".length())
                : module.getKey();
            modulePathToCorpus.put(modulePath, module.getKey());
            modulePathToCorpus.put(modulePath.replace('/', '.'), module.getKey());
            int slash = modulePath.lastIndexOf('/');
            moduleDirToCorpus.putIfAbsent(slash < 0 ? ""
                : modulePath.substring(0, slash), module.getKey());
        }
        // The drive's own entry module: the caller-supplied host-producer
        // module (it imports the host directly), or the fixture-probe driver
        // that imports the fixture and calls its probe export, so the
        // failing call runs inside the entry module's {@code main} on all
        // three consumers.
        if (driverSource != null) {
            Path app = mirror.resolve("app.deal");
            Files.writeString(app, driverSource, StandardCharsets.UTF_8);
            stripped.put("app.deal", 0);
            modulePathToCorpus.put("app", "app.deal");
            modulePathToCorpus.put("app.deal", "app.deal");
            entry = app;
        } else if (spec.entry() == Entry.DRIVER) {
            String fixtureSpec = spec.fixtureFile().substring(
                0, spec.fixtureFile().length() - ".deal".length());
            Path app = mirror.resolve("app.deal");
            Files.writeString(app, "import * as fx from \"./" + fixtureSpec
                + "\"\n\nexport function main(): null {\n  fx." + spec.probe()
                + "()\n  return null\n}\n", StandardCharsets.UTF_8);
            stripped.put("app.deal", 0);
            modulePathToCorpus.put("app", "app.deal");
            modulePathToCorpus.put("app.deal", "app.deal");
            entry = app;
        }
        Set<String> hostStems = new LinkedHashSet<>();
        List<String> hostScan = new ArrayList<>(modules.values());
        if (driverSource != null) {
            hostScan.add(driverSource);
        }
        for (String source : hostScan) {
            Matcher matcher = Pattern.compile("from \"host/([A-Za-z0-9_]+)\"")
                .matcher(source);
            while (matcher.find()) {
                hostStems.add(matcher.group(1));
            }
        }
        Map<String, String> externals = new LinkedHashMap<>();
        for (String stem : hostStems) {
            Path declaration = mirror.resolve("bindings/" + stem + ".d.deal");
            Files.createDirectories(declaration.getParent());
            String declared = hostDeclarations.get(stem);
            Files.writeString(declaration, declared != null ? declared
                : ConformanceHarnessMetadata.stripClassificationHeaders(
                    Files.readString(Path.of("test", "conformance",
                        "host-fixtures", stem + ".d.deal"))));
            externals.put("host." + stem, declaration.toAbsolutePath().toString());
            externals.put("host/" + stem, declaration.toAbsolutePath().toString());
        }
        CompilerInvocation invocation = productionInvocation();
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(entry,
            root.resolve("out"), false, false, false, false, Backend.LUAJIT,
            externals, List.of(mirror.toAbsolutePath()), null, null, invocation);
        boolean compiled = orchestrator.compile();
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        if (!compiled || built == null || built.input() == null || built.index() == null
                || built.hasErrors() || manifests == null
                || manifests.manifests() == null
                || orchestrator.hostDeclarationSurface() == null) {
            throw new IllegalStateException(spec.what() + ": the production "
                + "orchestrator did not compile the fixture: "
                + (built == null ? "no checked project" : built.diagnostics())
                + " / " + orchestrator.diagnostics());
        }
        Map<ModuleId, CanonicalModuleIdentity> identities = new LinkedHashMap<>();
        for (ModuleId declaration
                : orchestrator.hostDeclarationSurface().moduleIds()) {
            identities.put(declaration, new CanonicalModuleIdentity.ExternalModule(
                declaration.path().replace('/', '.')));
        }
        Map<ModuleId, deal.ffi.FfiGeneratedModule> externC = new LinkedHashMap<>();
        for (Map.Entry<String, deal.ffi.FfiGeneratedModule> generated
                : orchestrator.ffiGenerations().entrySet()) {
            externC.put(new ModuleId(generated.getKey()), generated.getValue());
        }
        SemanticLowerer.ProjectLoweringResult lowering = SemanticLowerer.lowerProject(
            invocation, built.input(), built.index(), manifests.manifests(),
            orchestrator.hostDeclarationSurface(), identities, externC,
            BuiltinErrorDeclaration.synthesized(
                built.input().modules().stream()
                    .filter(module -> module.moduleId()
                        .equals(built.input().entryModule()))
                    .findFirst().orElseThrow().ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW),
            Set.of());
        if (lowering.hasErrors() || lowering.project() == null) {
            throw new IllegalStateException(spec.what() + ": the production "
                + "project lowering failed: " + lowering.diagnostics());
        }
        return new Compiled(root, mirror, spec, invocation, built.input(),
            built.index(), manifests.manifests(),
            orchestrator.hostDeclarationSurface(), identities, externC, stripped,
            lowering.project(), lowering.tables(), lowering.registries(), hostStems,
            modulePathToCorpus, moduleDirToCorpus, new JvmPreparation());
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

    /**
     * Removes one rejected input's scratch root: the primary failure is
     * preserved and a cleanup failure is reported on it, so a rejected
     * input can never silently leave owned scratch state behind.
     */
    private static void cleanupRejectedScratch(Path root, Throwable primary) {
        deleteRecursively(root);
        if (Files.exists(root)) {
            primary.addSuppressed(new IllegalStateException(
                "the rejected input left its scratch root behind: " + root));
        }
    }

    // =========================================================================
    // The three consumers
    // =========================================================================

    /** The oracle's program drive of one compiled case. */
    private static SemanticRuntimeModel.ConsumerRun oracleRun(Compiled compiled) {
        return oracleRun(compiled, responderFor(compiled.spec()));
    }

    /** The oracle's program drive of one compiled case under an explicit responder. */
    private static SemanticRuntimeModel.ConsumerRun oracleRun(Compiled compiled,
            SemanticOracle.HostResponder responder) {
        if (compiled.spec().entry() == Entry.ASYNC) {
            return SemanticOracle.invokeAsyncEntry(compiled.project(),
                compiled.tables(), responder, compiled.checkedProject().entryModule(),
                compiled.spec().probe(), List.of());
        }
        return SemanticOracle.executeProjectInits(compiled.project(),
            compiled.tables(), compiled.registries(), responder);
    }

    /** The oracle's program drive of one compiled case that must fail. */
    private static Tuple oracleTuple(Compiled compiled) {
        return failureTuple(compiled, oracleRun(compiled));
    }

    /** The oracle's program drive of one compiled case under an explicit responder. */
    private static Tuple oracleTupleWith(Compiled compiled,
            SemanticOracle.HostResponder responder) {
        return failureTuple(compiled, oracleRun(compiled, responder));
    }

    /** The failed drive's error snapshot on the corpus coordinates. */
    private static Tuple failureTuple(Compiled compiled,
            SemanticRuntimeModel.ConsumerRun run) {
        if (!(run.terminal()
                instanceof SemanticRuntimeModel.Terminal.DealFailure failure)) {
            throw new IllegalStateException(compiled.spec().what()
                + ": the oracle did not fail: " + run.terminal());
        }
        return tupleOf(failure.error(), compiled, compiled.spec().fixtureFile());
    }

    /** The raw host carrier of the bad_string fixture's malformed string ("a\x80b"). */
    private static final byte[] RAW_MALFORMED_STRING = { 0x61, (byte) 0x80, 0x62 };

    /**
     * The closed E7 host seam of the async cases (the non-operation host
     * handle carrying the corpus host's returned number for the shape
     * fixture, and the violating completion value for the completion
     * fixture) and of the raw host string carriers (the backend-native
     * carrier marks of the bad_string fixture).
     */
    private static SemanticOracle.HostResponder responderFor(Case spec) {
        if ("empty_return".equals(spec.hostStem())) {
            // The declared void host's no-result terminal: the full-program
            // oracle drive passes the absent result through the recorded
            // return cell, where it selects HOST_SYNC_RETURN_NOTHING exactly
            // like the two production wrappers' zero-result returns.
            return new SemanticOracle.HostResponder() {
                @Override
                public SyncOutcome call(ModuleId module, String export,
                        RuntimeDescriptor.Func descriptor,
                        List<SemanticOracle.Value> args) {
                    return new SyncOutcome.Absent();
                }
            };
        }
        if ("bad_string".equals(spec.hostStem())) {
            return new SemanticOracle.HostResponder() {
                @Override
                public SyncOutcome call(ModuleId module, String export,
                        RuntimeDescriptor.Func descriptor,
                        List<SemanticOracle.Value> args) {
                    return switch (export) {
                        // The raw host carrier of "a\x80b": the malformed
                        // (non-surrogate) byte sequence of the Lua host's
                        // badString, classified by its encoding.
                        case "badString" -> new SyncOutcome.Returned(
                            new SemanticOracle.Value.StrValue("a\uD800b",
                                UnicodeScalars.utf8Defect(RAW_MALFORMED_STRING)));
                        // The U+D800 surrogate code point: the UTF-16
                        // carrier's own scalar validity selects the class.
                        case "surrogateString" -> new SyncOutcome.Returned(
                            new SemanticOracle.Value.StrValue("\uD800"));
                        // The embedded surrogate code point: the class is
                        // the offending scalar, never the carrier's length.
                        case "embeddedSurrogateString" -> new SyncOutcome.Returned(
                            new SemanticOracle.Value.StrValue("a\uD800b"));
                        // The valid carrier control.
                        case "validString" -> new SyncOutcome.Returned(
                            new SemanticOracle.Value.StrValue("ok"));
                        // The valid carrier inside the declared array
                        // positions (the decoded element results).
                        case "rawStringArray" -> new SyncOutcome.Returned(
                            new SemanticOracle.Value.ArrayValue(List.of(
                                new SemanticOracle.Value.StrValue("ok")),
                                RuntimeDescriptor.String.INSTANCE, false));
                        case "rawNestedStringArray" -> new SyncOutcome.Returned(
                            new SemanticOracle.Value.ArrayValue(List.of(
                                new SemanticOracle.Value.ArrayValue(List.of(
                                    new SemanticOracle.Value.StrValue("ok")),
                                    RuntimeDescriptor.String.INSTANCE, false)),
                                RuntimeDescriptor.parseCanonicalText("[string]"),
                                false));
                        case "rawNullableStringArray" -> new SyncOutcome.Returned(
                            new SemanticOracle.Value.ArrayValue(List.of(
                                new SemanticOracle.Value.StrValue("ok")),
                                RuntimeDescriptor.parseCanonicalText("?string"),
                                false));
                        // The nullable nested-array position: the explicit
                        // null element and the decoded array sibling.
                        case "rawNullableArrayArray" -> new SyncOutcome.Returned(
                            new SemanticOracle.Value.ArrayValue(List.of(
                                SemanticOracle.Value.NullValue.INSTANCE,
                                new SemanticOracle.Value.ArrayValue(List.of(
                                    new SemanticOracle.Value.StrValue("ok")),
                                    RuntimeDescriptor.String.INSTANCE, false)),
                                RuntimeDescriptor.parseCanonicalText("?[string]"),
                                false));
                        // The same inner host array at both positions of the
                        // declared string[][]: the one oracle array value is
                        // the one DEAL array (reference identity), and its
                        // element list is mutable like the oracle's own
                        // arrays so a DEAL element write commits in place and
                        // is observed through the sibling alias.
                        case "sharedNestedStringArray" -> {
                            SemanticOracle.Value inner =
                                new SemanticOracle.Value.ArrayValue(
                                    new java.util.ArrayList<>(List.of(
                                        new SemanticOracle.Value.StrValue("ok"))),
                                    RuntimeDescriptor.String.INSTANCE, false);
                            yield new SyncOutcome.Returned(
                                new SemanticOracle.Value.ArrayValue(
                                    new java.util.ArrayList<>(List.of(inner, inner)),
                                    RuntimeDescriptor.parseCanonicalText("[string]"),
                                    false));
                        }
                        // The rejected nested tail: element 2 is not the
                        // declared nested array, so the return fails the
                        // element arm after element 1 was checked.
                        case "badNestedTail" -> new SyncOutcome.Returned(
                            new SemanticOracle.Value.ArrayValue(List.of(
                                new SemanticOracle.Value.ArrayValue(List.of(
                                    new SemanticOracle.Value.StrValue("ok")),
                                    RuntimeDescriptor.String.INSTANCE, false),
                                new SemanticOracle.Value.StrValue("not an array")),
                                RuntimeDescriptor.parseCanonicalText("[string]"),
                                false));
                        // The native declared-carrier surface: the invalid
                        // second element (the same unpaired surrogate the
                        // scalar export returns), the nested carrier with a
                        // rejected later element, and the valid
                        // declared-carrier controls (the shared inner
                        // carrier at both positions of the declared
                        // string[][] stays one oracle array).
                        case "nativeBadStringArray" -> new SyncOutcome.Returned(
                            new SemanticOracle.Value.ArrayValue(List.of(
                                new SemanticOracle.Value.StrValue("ok"),
                                new SemanticOracle.Value.StrValue("\uD800")),
                                RuntimeDescriptor.String.INSTANCE, false));
                        case "nativeBadNestedTail" -> new SyncOutcome.Returned(
                            new SemanticOracle.Value.ArrayValue(List.of(
                                new SemanticOracle.Value.ArrayValue(List.of(
                                    new SemanticOracle.Value.StrValue("ok")),
                                    RuntimeDescriptor.String.INSTANCE, false),
                                new SemanticOracle.Value.StrValue("not an array")),
                                RuntimeDescriptor.parseCanonicalText("[string]"),
                                false));
                        case "nativeStringArray" -> new SyncOutcome.Returned(
                            new SemanticOracle.Value.ArrayValue(List.of(
                                new SemanticOracle.Value.StrValue("ok")),
                                RuntimeDescriptor.String.INSTANCE, false));
                        case "nativeNestedStringArray" -> new SyncOutcome.Returned(
                            new SemanticOracle.Value.ArrayValue(List.of(
                                new SemanticOracle.Value.ArrayValue(List.of(
                                    new SemanticOracle.Value.StrValue("ok")),
                                    RuntimeDescriptor.String.INSTANCE, false)),
                                RuntimeDescriptor.parseCanonicalText("[string]"),
                                false));
                        case "nativeSharedNestedStringArray" -> {
                            SemanticOracle.Value inner =
                                new SemanticOracle.Value.ArrayValue(
                                    new java.util.ArrayList<>(List.of(
                                        new SemanticOracle.Value.StrValue("ok"))),
                                    RuntimeDescriptor.String.INSTANCE, false);
                            yield new SyncOutcome.Returned(
                                new SemanticOracle.Value.ArrayValue(
                                    new java.util.ArrayList<>(List.of(inner, inner)),
                                    RuntimeDescriptor.parseCanonicalText("[string]"),
                                    false));
                        }
                        // The differing-depth carrier reuse: one declared
                        // string[][][][] value shares an inner array (c)
                        // between two positions with different descriptor
                        // depths (c is the root's first element and also
                        // the sole element of the root's second element e).
                        // The recursive element check descends the
                        // descriptor chain at every position and rejects
                        // the second occurrence under [string] with the
                        // outermost element arm (index 2), never admitting
                        // it on the shallower check's result.
                        case "nativeDifferingDepthStringArray" -> {
                            SemanticOracle.Value s = new SemanticOracle.Value.ArrayValue(
                                new java.util.ArrayList<>(List.of(
                                    new SemanticOracle.Value.StrValue("ok"))),
                                RuntimeDescriptor.String.INSTANCE, false);
                            SemanticOracle.Value d = new SemanticOracle.Value.ArrayValue(
                                new java.util.ArrayList<>(List.of(s)),
                                RuntimeDescriptor.parseCanonicalText("[string]"),
                                false);
                            SemanticOracle.Value c = new SemanticOracle.Value.ArrayValue(
                                new java.util.ArrayList<>(List.of(d)),
                                RuntimeDescriptor.parseCanonicalText("[[string]]"),
                                false);
                            SemanticOracle.Value e = new SemanticOracle.Value.ArrayValue(
                                new java.util.ArrayList<>(List.of(c)),
                                RuntimeDescriptor.parseCanonicalText("[[string]]"),
                                false);
                            yield new SyncOutcome.Returned(
                                new SemanticOracle.Value.ArrayValue(
                                    new java.util.ArrayList<>(List.of(c, e)),
                                    RuntimeDescriptor.parseCanonicalText(
                                        "[[[string]]]"), false));
                        }
                        default -> throw new IllegalStateException(
                            "the bad_string host seam scripts no export '"
                                + export + "'");
                    };
                }
            };
        }
        if (spec.oracleResponder() == null) {
            return null;
        }
        return new SemanticOracle.HostResponder() {
            @Override
            public AsyncStartOutcome startAsync(ModuleId module, String export,
                    deal.semantic.ir.RuntimeDescriptor.Func descriptor,
                    List<SemanticOracle.Value> args, String operationLabel) {
                if ("async_shape_bad".equals(spec.hostStem())) {
                    // The corpus host surface of host/async_shape_bad returns
                    // the integer 42 in place of an operation handle (the Lua
                    // host returns the Lua number 42, the JVM host returns
                    // Long.valueOf(42L)): the terminal check projects the
                    // returned carrier through the shared host-carrier
                    // projection, so the integer projects {@code number}
                    // exactly like both production artifacts, never the
                    // typed-boundary {@code int}.
                    return new AsyncStartOutcome.InvalidHandle(
                        new SemanticOracle.Value.IntValue(42));
                }
                return new AsyncStartOutcome.Operation(operationLabel);
            }

            @Override
            public SyncOutcome completeAsync(String operationLabel) {
                if ("async_bad".equals(spec.hostStem())) {
                    return new SyncOutcome.Returned(
                        new SemanticOracle.Value.IntValue(42));
                }
                return new SyncOutcome.Returned(
                    new SemanticOracle.Value.StrValue("fetched"));
            }
        };
    }

    /** One error snapshot projected onto the corpus coordinates. */
    private static Tuple tupleOf(SemanticRuntimeModel.ErrorSnapshot error,
            Compiled compiled, String fallbackCorpusPath) {
        String file = null;
        Integer line = null;
        Integer column = null;
        String origin = error.origin();
        if (origin != null && !origin.isEmpty() && !"-".equals(origin)) {
            int lastColon = origin.lastIndexOf(':');
            int prevColon = lastColon <= 0 ? -1
                : origin.lastIndexOf(':', lastColon - 1);
            if (prevColon > 0) {
                String rawFile = origin.substring(0, prevColon);
                String corpusPath = corpusPathOf(compiled, rawFile,
                    fallbackCorpusPath);
                file = corpusPath;
                line = Integer.valueOf(origin.substring(prevColon + 1, lastColon))
                    + compiled.strippedByCorpusPath()
                        .getOrDefault(corpusPath, 0);
                column = Integer.valueOf(origin.substring(lastColon + 1));
            }
        }
        return new Tuple(error.code(), normalize(error.message(), compiled), file, line,
            column, normalize(error.expected(), compiled),
            normalize(error.actual(), compiled));
    }

    /**
     * The corpus normalization of the emitted class-identity tokens (the
     * lane's own rule): an emitted {@code @conformance/<module path>/<C>}
     * names the declaring module by its module path; the corpus vocabulary
     * is the module's corpus-relative {@code .deal} path.
     */
    private static String normalize(String text, Compiled compiled) {
        if (text == null || !text.contains("@conformance/")) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            int at = text.indexOf("@conformance/", index);
            if (at < 0) {
                out.append(text, index, text.length());
                break;
            }
            out.append(text, index, at);
            int end = at + "@conformance/".length();
            while (end < text.length() && !isTokenBreak(text.charAt(end))) {
                end++;
            }
            out.append(normalizeToken(text.substring(at, end), compiled));
            index = end;
        }
        return out.toString();
    }

    private static boolean isTokenBreak(char c) {
        return c == ' ' || c == ',' || c == '\'' || c == '"' || c == ')' || c == '(';
    }

    private static String normalizeToken(String token, Compiled compiled) {
        String rest = token.substring("@conformance/".length());
        int slash = rest.lastIndexOf('/');
        if (slash <= 0) {
            return token;
        }
        String modulePath = rest.substring(0, slash);
        String className = rest.substring(slash + 1);
        String corpus = compiled.modulePathToCorpus().get(modulePath);
        if (corpus == null) {
            corpus = compiled.moduleDirToCorpus().get(modulePath);
        }
        return corpus == null ? token : "@conformance/" + corpus + "/" + className;
    }

    /** The corpus-relative path of one captured origin file. */
    private static String corpusPathOf(Compiled compiled, String captured,
            String fallbackCorpusPath) {
        Path mirror = compiled.mirror().toAbsolutePath().normalize();
        Path file = Path.of(captured).toAbsolutePath().normalize();
        if (!file.startsWith(mirror)) {
            return fallbackCorpusPath;
        }
        return CorpusDiscovery.slash(mirror.relativize(file));
    }

    // =========================================================================
    // 1. The named divergences through the production pipeline
    // =========================================================================

    /** One drive step that may fail; a failure is recorded, never fatal. */
    private interface Step {
        void run() throws Exception;
    }

    /** Runs one drive step, recording its failure under the case's name. */
    private static void safely(String what, Step step) {
        try {
            step.run();
        } catch (Exception failure) {
            failed++;
            System.err.println("FAIL: " + what + ": " + failure);
        }
    }

    private static void testNamedDivergences() throws Exception {
        System.out.println("-- the named canonical divergences: the oracle, the "
            + "LuaJIT artifact, and the JVM artifact reproduce the sidecar pins "
            + "byte-exact --");
        for (Case spec : NAMED) {
            safely(spec.what(), () -> driveCase(spec, true));
        }
    }

    private static void assertModuleExportOrigins(Compiled compiled) {
        int exports = 0;
        Map<String, String> moduleExportOrigins = new LinkedHashMap<>();
        for (deal.semantic.ir.LoweredModuleUnit unit
                : compiled.project().modules().values()) {
            for (deal.semantic.ir.SemanticOp op : unit.ops()) {
                if (op.kind() != deal.semantic.ir.SemanticOpKind.BOUNDARY
                        || !(op.payload()
                            instanceof deal.semantic.ir.KindPayload.BoundaryPayload payload)
                        || payload.kind()
                            != deal.semantic.ir.BoundaryKind.MODULE_EXPORT) {
                    continue;
                }
                exports++;
                String corpus = compiled.modulePathToCorpus()
                    .get(unit.moduleId().path());
                check(op.origin().span() != null
                        && op.origin().span().startColumn() == 1
                        && op.origin().span().startLine() > 0,
                    compiled.spec().what() + " (" + unit.moduleId()
                        + "): the MODULE_EXPORT boundary carries a module-level "
                        + "program span: " + op.origin().span());
                check(corpus != null && op.origin().sourceId() != null
                        && op.origin().sourceId().endsWith(corpus),
                    compiled.spec().what() + " (" + unit.moduleId()
                        + "): the MODULE_EXPORT boundary names the exporting "
                        + "unit's file: " + op.origin().sourceId());
                String origin = op.origin().sourceId() + ":"
                    + op.origin().span().startLine() + ":"
                    + op.origin().span().startColumn();
                String first = moduleExportOrigins.putIfAbsent(
                    unit.moduleId().path(), origin);
                check(first == null || first.equals(origin),
                    compiled.spec().what() + " (" + unit.moduleId()
                        + "): every export publication of one unit carries the "
                        + "same program span: " + first + " vs " + origin);
            }
        }
        check(exports > 0, compiled.spec().what()
            + ": the drive's entry unit publishes its exports through a "
            + "MODULE_EXPORT boundary");
    }

    /**
     * Drives one corpus case through the three consumers, asserts every
     * sidecar-pinned field byte-exact per consumer, and asserts the three
     * tuples are identical.
     */
    private static void driveCase(Case spec, boolean named) throws Exception {
        Compiled compiled = compile(spec);
        try {
            SidecarExpectations.ErrorExpectation pin = luajitPin(spec);
            check(pin != null, spec.what() + ": the sidecar pins a LuaJIT error");
            if (pin == null) {
                return;
            }
            assertModuleExportOrigins(compiled);
            Tuple oracle = oracleTuple(compiled);
            assertPinned(oracle, pin, spec, "oracle");
            Tuple lua = luaArtifact(compiled);
            assertPinned(lua, pin, spec, "luajit");
            Tuple jvm = jvmArtifact(compiled);
            assertPinned(jvm, pin, spec, "jvm");
            checkEq(null, firstDifferingField(oracle, lua), spec.what()
                + ": the oracle and the LuaJIT artifact render the identical tuple");
            checkEq(null, firstDifferingField(oracle, jvm), spec.what()
                + ": the oracle and the JVM artifact render the identical tuple");
            if (named) {
                assertNamedDivergence(spec, oracle, pin);
            }
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /** The named divergences' own pinned facts (Verification 1). */
    private static void assertNamedDivergence(Case spec, Tuple oracle,
            SidecarExpectations.ErrorExpectation pin) {
        switch (spec.corpusPath()) {
            case "source-location/036-runtime-source-array-oob" -> {
                checkEq("expected int", oracle.message(), spec.what()
                    + ": the suffix-less kind text");
                checkEq("int", oracle.expected(), spec.what() + ": the int token");
                checkEq("nil", oracle.actual(), spec.what()
                    + ": the absent marker projects nil");
                checkEq(Integer.valueOf(8), oracle.line(), spec.what()
                    + ": the declaration-owned read span");
                checkEq(Integer.valueOf(14), oracle.column(), spec.what()
                    + ": the declaration-owned read column");
            }
            case "runtime-errors/type-mismatch-e8001" -> {
                checkEq(Integer.valueOf(6), pin.line(), spec.what()
                    + ": the sidecar pins the callee's declared annotation line");
                checkEq(Integer.valueOf(21), pin.column(), spec.what()
                    + ": the sidecar pins the annotation column");
                checkEq(pin.line(), oracle.line(), spec.what()
                    + ": the oracle renders the callee's declared annotation");
            }
            case "arithmetic/int-convert-fraction", "runtime/int-convert-noninteger" -> {
                checkEq("expected int, got non-integer number", oracle.message(),
                    spec.what() + ": the fractional ladder text");
                checkEq("number", oracle.actual(), spec.what()
                    + ": the fractional ladder actual is the number token");
            }
            case "class-runtime-errors/dynamic-bad-imported-class-param-e8001" -> {
                checkEq("backend-runtime/class-runtime-errors/imported_class_lib.deal",
                    oracle.file(), spec.what()
                        + ": the companion's declared parameter annotation file");
                checkEq(Integer.valueOf(7), oracle.line(), spec.what()
                    + ": the companion's declared parameter annotation line");
                checkEq(Integer.valueOf(28), oracle.column(), spec.what()
                    + ": the companion's declared parameter annotation column");
            }
            default -> {
            }
        }
    }

    private static SidecarExpectations.ErrorExpectation luajitPin(Case spec)
            throws Exception {
        SidecarExpectations.StructuredExpectationSidecar sidecar =
            SidecarExpectations.StructuredExpectationSidecar.parse(Files.readString(
                CONFORMANCE.resolve(spec.sidecarFile()), StandardCharsets.UTF_8));
        SidecarExpectations.RuntimeExpectation expectation =
            sidecar.expectationFor("luajit");
        return expectation instanceof SidecarExpectations.RuntimeExpectation.Executed
            executed ? executed.error() : null;
    }

    /**
     * Asserts one consumer's tuple against the sidecar pin under the
     * unchanged comparison contract (Verification 6): {@code code} and
     * {@code message} always; the span group and the optional fields only
     * when pinned; a pinned field the capture lacks fails; nothing is
     * fabricated.
     */
    private static void assertPinned(Tuple tuple,
            SidecarExpectations.ErrorExpectation pin, Case spec, String consumer) {
        checkEq(pin.code(), tuple.code(), spec.what() + " (" + consumer
            + "): the pinned code");
        checkEq(pin.message(), tuple.message(), spec.what() + " (" + consumer
            + "): the pinned message");
        if (pin.pinsSpan()) {
            checkEq(pin.sourceFile(), tuple.file(), spec.what() + " (" + consumer
                + "): the pinned sourceFile");
            checkEq(pin.line(), tuple.line(), spec.what() + " (" + consumer
                + "): the pinned line");
            checkEq(pin.column(), tuple.column(), spec.what() + " (" + consumer
                + "): the pinned column");
        } else {
            check(tuple.file() == null, spec.what() + " (" + consumer
                + "): no span is fabricated for a span-less sidecar");
        }
        if (pin.expected().isPresent()) {
            checkEq(pin.expected().get(), tuple.expected(), spec.what() + " ("
                + consumer + "): the pinned expected field");
        } else {
            check(tuple.expected() == null, spec.what() + " (" + consumer
                + "): no expected field is fabricated");
        }
        if (pin.actual().isPresent()) {
            checkEq(pin.actual().get(), tuple.actual(), spec.what() + " ("
                + consumer + "): the pinned actual field");
        } else {
            check(tuple.actual() == null, spec.what() + " (" + consumer
                + "): no actual field is fabricated");
        }
    }

    // =========================================================================
    // 2. The arm families through the production pipeline
    // =========================================================================

    private static void testArmFamilies() throws Exception {
        System.out.println("-- the arm families: every reachable family with a "
            + "corpus pin renders the identical tuple from the three consumers --");
        for (Case spec : FAMILIES) {
            safely(spec.what(), () -> driveCase(spec, false));
        }
        for (Case spec : HOST_FAMILIES) {
            safely(spec.what(), () -> driveHostCase(spec));
        }
    }

    /**
     * Drives one host-boundary fixture: the two production artifacts
     * render the pinned tuple byte-exact, and the oracle renders the same
     * arm — through its program drive where the failing cell precedes the
     * host call (the parameter cells and the async cases), or through its
     * closed boundary engine at the pinned return cell.
     */
    private static void driveHostCase(Case spec) throws Exception {
        Compiled compiled = compile(spec);
        try {
            SidecarExpectations.ErrorExpectation pin = luajitPin(spec);
            check(pin != null, spec.what() + ": the sidecar pins a LuaJIT error");
            if (pin == null) {
                return;
            }
            Tuple lua = luaArtifact(compiled);
            assertPinned(lua, pin, spec, "luajit");
            Tuple jvm = jvmArtifact(compiled);
            assertPinned(jvm, pin, spec, "jvm");
            checkEq(null, firstDifferingField(lua, jvm), spec.what()
                + ": the two production artifacts render the identical tuple");
            // The production wrappers' mapped failure sites follow the
            // closed arm's own template and declared fields, never a
            // runtime-side text copy: the two produced tuples equal the
            // registry's render of the arm the site belongs to.
            switch (spec.corpusPath()) {
                case "host-abi/host-empty-return-bad" -> assertRegistryArm(spec, lua,
                    jvm, FailureArmId.HOST_SYNC_RETURN_NOTHING,
                    Map.of("expected", "int"), "int", "nothing");
                case "host-abi/host-bad-return" -> assertRegistryArm(spec, lua, jvm,
                    FailureArmId.HOST_SYNC_RETURN_CELL,
                    Map.of("inner", FailureProjections.kindReason(
                        RuntimeDescriptor.Int.INSTANCE)), "int", "string");
                case "host-abi/host-async-shape-bad" -> assertRegistryArm(spec, lua,
                    jvm, FailureArmId.ASYNC_SHAPE, Map.of("actual", "number"),
                    "async operation", "number");
                default -> {
                }
            }
            switch (spec.corpusPath()) {
                case "host-abi/host-bytes-param-mismatch-e8010",
                     "host-abi/host-nullable-function-param-bad",
                     "host-abi/host-rest-bad",
                     "host-abi/host-async-bad",
                     "host-abi/host-async-shape-bad",
                     "host-abi/host-invalid-utf8-e8010",
                     "host-abi/host-surrogate-utf8-e8010" -> {
                    assertModuleExportOrigins(compiled);
                    Tuple oracle = oracleTuple(compiled);
                    assertPinned(oracle, pin, spec, "oracle");
                    checkEq(null, firstDifferingField(oracle, lua), spec.what()
                        + ": the oracle and the LuaJIT artifact render the "
                        + "identical tuple");
                    checkEq(null, firstDifferingField(oracle, jvm), spec.what()
                        + ": the oracle and the JVM artifact render the "
                        + "identical tuple");
                }
                case "host-abi/host-empty-return-bad" -> {
                    // The full production program of the corpus-pinned
                    // empty-return cell: the oracle's absent terminal runs
                    // the recorded return cell at the probe's own call
                    // origin and renders the sidecar's complete tuple; the
                    // closed boundary engine's direct view of the same cell
                    // renders the identical arm.
                    assertModuleExportOrigins(compiled);
                    Tuple oracle = oracleTuple(compiled);
                    assertPinned(oracle, pin, spec, "oracle");
                    checkEq(null, firstDifferingField(oracle, lua), spec.what()
                        + ": the oracle and the LuaJIT artifact render the "
                        + "identical tuple");
                    checkEq(null, firstDifferingField(oracle, jvm), spec.what()
                        + ": the oracle and the JVM artifact render the "
                        + "identical tuple");
                    assertOracleCell(spec, lua, FailurePolicyId.HOST_SYNC_RETURN,
                        RuntimeDescriptor.Int.INSTANCE,
                        BoundaryValueView.of(ActualKind.NOTHING),
                        BoundaryContext.none());
                }
                case "host-abi/host-bad-return" -> assertOracleCell(spec, lua,
                    FailurePolicyId.HOST_SYNC_RETURN, RuntimeDescriptor.Int.INSTANCE,
                    BoundaryValueView.of(ActualKind.STRING), BoundaryContext.none());
                case "host-abi/host-nullable-return-bad" -> assertOracleCell(spec, lua,
                    FailurePolicyId.HOST_SYNC_RETURN,
                    RuntimeDescriptor.parseCanonicalText("?string"),
                    BoundaryValueView.ofNumber(1.0), BoundaryContext.none());
                default -> {
                    // The class-construction and host-load families' oracle
                    // renders are the closed arm table's (the three-renderer
                    // identity below); the artifacts carry the pinned tuples.
                }
            }
            switch (spec.corpusPath()) {
                case "host-abi/host-bad-return" -> wrapperSyncReturnKindSite(compiled);
                case "host-abi/host-invalid-utf8-e8010" -> wrapperStringCarrierSite(compiled);
                case "host-abi/host-empty-return-bad" -> wrapperNothingSite(compiled);
                default -> {
                }
            }
            if ("host-abi/host-async-shape-bad".equals(spec.corpusPath())) {
                assertAsyncHandlePresence(compiled, pin);
                assertHostWrapperArmDelegation(compiled, pin);
                assertAsyncHandleCarriers(compiled, pin);
                assertCyclicAsyncHandle(compiled, pin);
                assertNumericAsyncHandleBoundaries(compiled, pin);
                assertHarnessNullLabelAbsentHandle(compiled, pin);
            }
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /**
     * The three-consumer async-handle presence drive (ISSUE-0696
     * remediation; ISSUE-0774's required distinction): a host invocation
     * that produced no result — the LuaJIT wrapper's zero-result return,
     * the JVM host surface's declared-void method — renders the closed
     * {@code ASYNC_SHAPE} arm with the fixed {@code nothing} actual, while
     * a present language-null result — the LuaJIT runtime's null sentinel,
     * the JVM host surface's {@code Object}-returning method that returned
     * null — keeps the canonical carrier-kind {@code table} projection,
     * exactly like the oracle's {@code Absent} and
     * {@code InvalidHandle(NullValue)} outcomes. Every consumer renders
     * the registry's own complete tuple at the supplied call origin, no
     * consumer collapses the present null carrier into absence, and the
     * two shapes stay distinct by field name.
     */
    private static void assertAsyncHandlePresence(Compiled compiled,
            SidecarExpectations.ErrorExpectation pin) throws Exception {
        Tuple expectedNothing = expectedAsyncShapeTuple(pin, "nothing");
        Tuple expectedTable = expectedAsyncShapeTuple(pin, "table");
        // The oracle's two closed outcomes of the same call site.
        Tuple oracleAbsent = oracleTupleWith(compiled,
            new SemanticOracle.HostResponder() {
                @Override
                public AsyncStartOutcome startAsync(ModuleId module, String export,
                        RuntimeDescriptor.Func descriptor,
                        List<SemanticOracle.Value> args, String operationLabel) {
                    return new AsyncStartOutcome.Absent();
                }
            });
        checkEq(expectedNothing, oracleAbsent, compiled.spec().what()
            + " (oracle, absent async handle): the complete nothing tuple");
        Tuple oracleNull = oracleTupleWith(compiled,
            new SemanticOracle.HostResponder() {
                @Override
                public AsyncStartOutcome startAsync(ModuleId module, String export,
                        RuntimeDescriptor.Func descriptor,
                        List<SemanticOracle.Value> args, String operationLabel) {
                    return new AsyncStartOutcome.InvalidHandle(
                        SemanticOracle.Value.NullValue.INSTANCE);
                }
            });
        checkEq(expectedTable, oracleNull, compiled.spec().what()
            + " (oracle, present null handle): the complete table tuple");
        // The two production wrappers driven with the corresponding
        // no-result and present-null host carriers: the wrapper failure
        // sites, never a direct renderer call.
        Tuple luaAbsent = luaArtifactWithHost(compiled, "async_shape_bad",
            "return {\n  fetchValue = function()\n  end,\n}\n");
        checkEq(expectedNothing, luaAbsent, compiled.spec().what()
            + " (luajit, absent async handle): the complete nothing tuple");
        Tuple luaNull = luaArtifactWithHost(compiled, "async_shape_bad",
            "local rt = require(\"deal.runtime\")\nreturn {\n"
                + "  fetchValue = function()\n    return rt.__NULL\n  end,\n}\n");
        checkEq(expectedTable, luaNull, compiled.spec().what()
            + " (luajit, present null handle): the complete table tuple");
        Tuple jvmAbsent = jvmArtifactWithHost(compiled, "async_shape_bad", """
            final class HostAsync_shape_bad {
              public static void fetchValue() {
              }
            }
            """);
        checkEq(expectedNothing, jvmAbsent, compiled.spec().what()
            + " (jvm, absent async handle): the complete nothing tuple");
        Tuple jvmNull = jvmArtifactWithHost(compiled, "async_shape_bad", """
            final class HostAsync_shape_bad {
              public static Object fetchValue() {
                return null;
              }
            }
            """);
        checkEq(expectedTable, jvmNull, compiled.spec().what()
            + " (jvm, present null handle): the complete table tuple");
        // The two shapes stay distinct across every consumer: the present
        // null handle never collapses into the absent handle's token.
        checkEq(null, firstDifferingField(oracleAbsent, luaAbsent), compiled.spec()
            .what() + ": the oracle and the LuaJIT artifact render the identical "
            + "absent-handle tuple");
        checkEq(null, firstDifferingField(oracleAbsent, jvmAbsent), compiled.spec()
            .what() + ": the oracle and the JVM artifact render the identical "
            + "absent-handle tuple");
        checkEq(null, firstDifferingField(oracleNull, luaNull), compiled.spec()
            .what() + ": the oracle and the LuaJIT artifact render the identical "
            + "present-null tuple");
        checkEq(null, firstDifferingField(oracleNull, jvmNull), compiled.spec()
            .what() + ": the oracle and the JVM artifact render the identical "
            + "present-null tuple");
        checkEq("nothing", expectedNothing.actual(), compiled.spec().what()
            + ": the absent handle's actual token");
        checkEq("table", expectedTable.actual(), compiled.spec().what()
            + ": the present null handle's actual token");
        check(!Objects.equals(expectedNothing.message(), expectedTable.message()),
            compiled.spec().what() + ": the absent and present-null shapes render "
                + "distinct messages: " + expectedNothing.message() + " vs "
                + expectedTable.message());
    }

    /** One closed {@code ASYNC_SHAPE} tuple at the fixture's pinned call origin. */
    private static Tuple expectedAsyncShapeTuple(
            SidecarExpectations.ErrorExpectation pin, String actual) {
        BoundaryFailure arm = FailureContractRegistry.render(FailureArmId.ASYNC_SHAPE,
            Map.of("actual", actual), "async operation", actual, null);
        return new Tuple(arm.code().name(), arm.message(), pin.sourceFile(),
            pin.line(), pin.column(), arm.expected(), arm.actual());
    }

    /**
     * The caller-owned declared-async-null host surface of the completion
     * drive: a nullable completion, a {@code null} completion, and a
     * mandatory completion that the same null sentinel must still fail.
     * The declaration is written into the scratch mirror (no corpus or
     * host-fixture membership change).
     */
    private static final String NULL_COMPLETION_HOST_DECLARATION = """
        export async function fetchValue(): string | null;
        export async function pingNull(): null;
        export async function fetchStrict(): string;
        """;

    /**
     * The LuaJIT host implementation: every declared operation completes
     * with the deployed runtime's language-null sentinel (the carrier of a
     * host-crossed null).
     */
    private static final String NULL_COMPLETION_HOST_LUA = """
        local rt = require("deal.runtime")

        return {
          fetchValue = function()
            return rt.async_start(function()
              return rt.__NULL
            end)
          end,
          pingNull = function()
            return rt.async_start(function()
              return rt.__NULL
            end)
          end,
          fetchStrict = function()
            return rt.async_start(function()
              return rt.__NULL
            end)
          end,
        }
        """;

    /**
     * The JVM host implementation: every declared operation completes with
     * a present Java null — the JVM host surface's language-null carrier.
     */
    private static final String NULL_COMPLETION_HOST_JAVA = """
        import java.util.concurrent.CompletableFuture;

        final class HostAsync_nullable {
          public static Object fetchValue() {
            return CompletableFuture.completedFuture(null);
          }
          public static Object pingNull() {
            return CompletableFuture.completedFuture(null);
          }
          public static Object fetchStrict() {
            return CompletableFuture.completedFuture(null);
          }
        }
        """;

    /** The drive's generated entry module: the two admitted null completions. */
    private static final String NULL_COMPLETION_DRIVER = """
        import * as host from "host/async_nullable"

        export async function test_nullable_host_completion(): null {
          let v: string | null = await host.fetchValue()
          if (v !== null) {
            throw { code: "PROBE_HOST_NULL_COMPLETION", message: "the host null completion was not the language null" }
          }
          return null
        }

        export async function test_null_host_completion(): null {
          await host.pingNull()
          return null
        }

        export function main(): null {
          return null
        }
        """;

    /** The drive's generated entry module: the rejected null completion. */
    private static final String NULL_COMPLETION_STRICT_DRIVER = """
        import * as host from "host/async_nullable"

        export async function test_strict_host_completion(): null {
          let s: string = await host.fetchStrict()
          return null
        }

        export function main(): null {
          return null
        }
        """;

    /**
     * The host null/nullable async completions (ISSUE-0696 remediation): a
     * host operation completing with the language-null carrier — the
     * deployed runtime's sentinel on LuaJIT, a present Java null on the
     * JVM — is a valid completion of a declared {@code null} or
     * {@code T | null} async return on the oracle, the LuaJIT artifact,
     * and the JVM artifact alike, while the same null completion at a
     * declared non-null return still renders the pinned
     * {@code ASYNC_COMPLETION_KIND} tuple ({@code expected string}, actual
     * {@code null}) at the await expression. The drive's declaration is
     * caller-owned, so no corpus or host-fixture membership changes.
     */
    private static void testHostNullCompletions() throws Exception {
        System.out.println("-- the host null/nullable async completions: the "
            + "language-null carrier is admitted by a declared null or nullable "
            + "return on all three consumers and still rejected at a mandatory "
            + "return --");
        safely("the nullable and null host completions",
            CanonicalProjectionParityTest::driveHostNullCompletionSuccess);
        safely("the mandatory host completion rejects the null sentinel",
            CanonicalProjectionParityTest::driveHostMandatoryNullCompletion);
    }

    /**
     * The admitted null/nullable host completions: the oracle's async-entry
     * drive completes with a success terminal for both exports (the body's
     * own {@code !== null} probe consumed the admitted language null), and
     * both production artifacts complete without a failure event.
     */
    private static void driveHostNullCompletionSuccess() throws Exception {
        Case spec = new Case("host-abi/host-async-shape-bad",
            "test_nullable_host_completion", Entry.ASYNC, List.of(), null, null);
        Compiled compiled = compile(spec, NULL_COMPLETION_DRIVER,
            Map.of("async_nullable", NULL_COMPLETION_HOST_DECLARATION));
        try {
            SemanticOracle.HostResponder responder = nullCompletingResponder();
            for (String export : List.of("test_nullable_host_completion",
                    "test_null_host_completion")) {
                SemanticRuntimeModel.ConsumerRun run =
                    SemanticOracle.invokeAsyncEntry(compiled.project(),
                        compiled.tables(), responder,
                        compiled.checkedProject().entryModule(), export, List.of());
                check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                    "the " + export + " (oracle): the host null completion is "
                        + "admitted: " + run.terminal());
            }
            String lua = luaArtifactOutput(compiled,
                Map.of("async_nullable", NULL_COMPLETION_HOST_LUA));
            check(lua.contains("OK") && !lua.contains("ERR|"),
                "the nullable/null host completions (luajit): the production "
                    + "artifact completes without a failure event: " + lua);
            String jvm = jvmArtifactOutput(compiled, jvmProbe(
                JvmBackend.classNameFor(
                    compiled.checkedProject().entryModule().path()),
                compiled.checkedProject().entryModule().path(), ""),
                Map.of("async_nullable", NULL_COMPLETION_HOST_JAVA));
            check(jvm != null && jvm.contains("OK") && !jvm.contains("ERR|"),
                "the nullable/null host completions (jvm): the production "
                    + "artifact completes without a failure event: " + jvm);
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /**
     * The rejected mandatory host completion: the same null sentinel at a
     * declared {@code string} async return renders the closed
     * {@code ASYNC_COMPLETION_KIND} arm byte-exact on all three consumers —
     * code, message, the await-expression origin, expected, and actual — so
     * the language-null admission never widens a mandatory completion cell.
     */
    private static void driveHostMandatoryNullCompletion() throws Exception {
        Case spec = new Case("host-abi/host-async-shape-bad",
            "test_strict_host_completion", Entry.ASYNC, List.of(), null, null);
        Compiled compiled = compile(spec, NULL_COMPLETION_STRICT_DRIVER,
            Map.of("async_nullable", NULL_COMPLETION_HOST_DECLARATION));
        try {
            BoundaryFailure arm = FailureContractRegistry.render(
                FailureArmId.ASYNC_COMPLETION_KIND, Map.of("expected", "string"),
                "string", "null", null);
            Tuple expected = new Tuple(arm.code().name(), arm.message(), "app.deal",
                4, 19, arm.expected(), arm.actual());
            Tuple oracle = oracleTupleWith(compiled, nullCompletingResponder());
            checkEq(expected, oracle, "the mandatory host completion (oracle): the "
                + "complete ASYNC_COMPLETION_KIND tuple at the await expression");
            Tuple lua = luaArtifactWithHost(compiled, "async_nullable",
                NULL_COMPLETION_HOST_LUA);
            checkEq(expected, lua, "the mandatory host completion (luajit): the "
                + "complete ASYNC_COMPLETION_KIND tuple at the await expression");
            Tuple jvm = jvmArtifactWithHost(compiled, "async_nullable",
                NULL_COMPLETION_HOST_JAVA);
            checkEq(expected, jvm, "the mandatory host completion (jvm): the "
                + "complete ASYNC_COMPLETION_KIND tuple at the await expression");
            checkEq(null, firstDifferingField(oracle, lua),
                "the mandatory host completion: the oracle and the LuaJIT artifact "
                    + "render the identical tuple");
            checkEq(null, firstDifferingField(oracle, jvm),
                "the mandatory host completion: the oracle and the JVM artifact "
                    + "render the identical tuple");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /** The drive's async host seam: every declared operation completes with null. */
    private static SemanticOracle.HostResponder nullCompletingResponder() {
        return new SemanticOracle.HostResponder() {
            @Override
            public AsyncStartOutcome startAsync(ModuleId module, String export,
                    RuntimeDescriptor.Func descriptor,
                    List<SemanticOracle.Value> args, String operationLabel) {
                return new AsyncStartOutcome.Operation(operationLabel);
            }

            @Override
            public SyncOutcome completeAsync(String operationLabel) {
                return new SyncOutcome.Returned(
                    SemanticOracle.Value.NullValue.INSTANCE);
            }
        };
    }

    // =========================================================================
    // The synchronous host result presence (ISSUE-0696 remediation)
    // =========================================================================

    /**
     * The synchronous host result presence: the recorded
     * {@code HOST_SYNC_RETURN} cell selects {@code HOST_SYNC_RETURN_NOTHING}
     * for a host invocation that produced no result at all — the oracle's
     * {@link SemanticOracle.HostResponder.SyncOutcome.Absent}, the LuaJIT
     * wrapper's zero-result return, the JVM host surface's declared void
     * method — before any descriptor checking and independently of the
     * declared return's nullability. A present language null — the
     * oracle's {@link SemanticOracle.Value.NullValue}, the deployed
     * runtime's null sentinel, a returned Java null — is a value: it is
     * admitted by a declared {@code null} or nullable return and never
     * collapses into absence, while the mandatory cell keeps its own
     * zero-result rule. The drive's declaration and both host
     * implementations are caller-owned, so no corpus or host-fixture
     * membership changes.
     */
    private static final String SYNC_PRESENCE_DECLARATION = """
        export function fetchNullable(): string | null;
        export function pingNull(): null;
        export function fetchMandatory(): int;
        """;

    /**
     * The drive's entry module: a statement-call probe per declared return
     * descriptor and a consuming probe for the admitted nullable/null
     * returns ({@code test_use_nullable} asserts the admitted carrier is
     * the language null). {@code main} calls nothing, so every probe is
     * driven alone and its own pinned call origin is the compared span.
     */
    private static final String SYNC_PRESENCE_DRIVER = """
        import * as host from "host/sync_presence"

        export function test_drop_nullable(): null {
          host.fetchNullable()
          return null
        }

        export function test_drop_null(): null {
          host.pingNull()
          return null
        }

        export function test_drop_mandatory(): null {
          host.fetchMandatory()
          return null
        }

        export function test_use_nullable(): null {
          let v: string | null = host.fetchNullable()
          if (v !== null) {
            throw { code: "PROBE_SYNC_PRESENCE", message: "the host null return was not the language null" }
          }
          return null
        }

        export function test_use_null(): null {
          host.pingNull()
          return null
        }

        export function test_use_mandatory(): null {
          host.fetchMandatory()
          return null
        }

        export function main(): null {
          return null
        }
        """;

    /**
     * The declared numeric-return surface of the numeric-carrier drive
     * (ISSUE-0696 remediation): the mandatory and nullable {@code int}
     * returns and the mandatory {@code number} returns. Caller-owned
     * exactly like the sync-presence declaration, so no corpus or
     * host-fixture membership changes.
     */
    private static final String NUMERIC_RETURN_DECLARATION = """
        export function fetchInt(): int;
        export function fetchNullableInt(): int | null;
        export function fetchNumber(): number;
        export function fetchFraction(): number;
        """;

    /**
     * The numeric-carrier drive's entry module: one consuming probe per
     * declared family, so an admitted carrier whose value was lost by the
     * normalization cannot pass, and one drop probe per {@code int} return
     * descriptor, so the captured origin is the failing call expression's
     * own.
     */
    private static final String NUMERIC_RETURN_DRIVER = """
        import * as host from "host/numeric_return"

        export function test_use_int(): null {
          let v: int = host.fetchInt()
          if (v !== 7) {
            throw { code: "PROBE_NUMERIC_RETURN", message: "the int return lost its value" }
          }
          return null
        }

        export function test_use_nullable_int(): null {
          let v: int | null = host.fetchNullableInt()
          if (v !== null) {
            if (v !== 7) {
              throw { code: "PROBE_NUMERIC_RETURN", message: "the nullable int return lost its value" }
            }
            return null
          }
          throw { code: "PROBE_NUMERIC_RETURN", message: "the nullable int return was the language null" }
        }

        export function test_use_number(): null {
          let v: number = host.fetchNumber()
          if (v !== 7.0) {
            throw { code: "PROBE_NUMERIC_RETURN", message: "the number return lost its value" }
          }
          return null
        }

        export function test_use_fraction(): null {
          let v: number = host.fetchFraction()
          if (v !== 7.5) {
            throw { code: "PROBE_NUMERIC_RETURN", message: "the fractional number return lost its value" }
          }
          return null
        }

        export function test_drop_int(): null {
          host.fetchInt()
          return null
        }

        export function test_drop_nullable_int(): null {
          host.fetchNullableInt()
          return null
        }

        export function main(): null {
          return null
        }
        """;

    /** The LuaJIT absent host: every declared export returns zero results. */
    private static String luaAbsentSyncHost() {
        return """
            return {
              fetchNullable = function()
              end,
              pingNull = function()
              end,
              fetchMandatory = function()
              end,
            }
            """;
    }

    /** The JVM absent host: every declared export is a declared void method. */
    private static String jvmAbsentSyncHost() {
        return """
            final class HostSync_presence {
              public static void fetchNullable() {
              }
              public static void pingNull() {
              }
              public static void fetchMandatory() {
              }
            }
            """;
    }

    /**
     * The LuaJIT present-null host: every declared export returns the
     * deployed runtime's language-null sentinel (a present value, never a
     * zero-result return).
     */
    private static String luaPresentNullSyncHost() {
        return """
            local rt = require("deal.runtime")

            return {
              fetchNullable = function()
                return rt.__NULL
              end,
              pingNull = function()
                return rt.__NULL
              end,
              fetchMandatory = function()
                return rt.__NULL
              end,
            }
            """;
    }

    /** The JVM present-null host: every declared export returns a present Java null. */
    private static String jvmPresentNullSyncHost() {
        return """
            final class HostSync_presence {
              public static Object fetchNullable() {
                return null;
              }
              public static Object pingNull() {
                return null;
              }
              public static Object fetchMandatory() {
                return null;
              }
            }
            """;
    }

    /** The LuaJIT present-value host: {@code fetchMandatory} returns a real int. */
    private static String luaPresentValueSyncHost() {
        return """
            local rt = require("deal.runtime")

            return {
              fetchNullable = function()
                return "ok"
              end,
              pingNull = function()
                return rt.__NULL
              end,
              fetchMandatory = function()
                return 7
              end,
            }
            """;
    }

    /** The JVM present-value host: {@code fetchMandatory} returns a real boxed long. */
    private static String jvmPresentValueSyncHost() {
        return """
            final class HostSync_presence {
              public static Object fetchNullable() {
                return "ok";
              }
              public static Object pingNull() {
                return null;
              }
              public static Object fetchMandatory() {
                return java.lang.Integer.valueOf(7);
              }
            }
            """;
    }

    /** The absent sync host seam: every call terminal produces no result. */
    private static SemanticOracle.HostResponder absentSyncResponder() {
        return new SemanticOracle.HostResponder() {
            @Override
            public SyncOutcome call(ModuleId module, String export,
                    RuntimeDescriptor.Func descriptor,
                    List<SemanticOracle.Value> args) {
                return new SyncOutcome.Absent();
            }
        };
    }

    /** The present-null sync host seam: every call terminal returns the language null. */
    private static SemanticOracle.HostResponder presentNullSyncResponder() {
        return new SemanticOracle.HostResponder() {
            @Override
            public SyncOutcome call(ModuleId module, String export,
                    RuntimeDescriptor.Func descriptor,
                    List<SemanticOracle.Value> args) {
                return new SyncOutcome.Returned(
                    SemanticOracle.Value.NullValue.INSTANCE);
            }
        };
    }

    /** The present-value sync host seam: {@code fetchMandatory} returns a real int. */
    private static SemanticOracle.HostResponder presentValueSyncResponder() {
        return new SemanticOracle.HostResponder() {
            @Override
            public SyncOutcome call(ModuleId module, String export,
                    RuntimeDescriptor.Func descriptor,
                    List<SemanticOracle.Value> args) {
                return new SyncOutcome.Returned(
                    new SemanticOracle.Value.IntValue(7L));
            }
        };
    }

    /**
     * The synchronous host result presence drive: absence at every declared
     * return descriptor, the admitted present language nulls, and a present
     * value control — each on the oracle, the LuaJIT artifact, and the JVM
     * artifact through their own host terminals.
     */
    private static void testHostSyncReturnPresence() throws Exception {
        System.out.println("-- the synchronous host result presence: absence "
            + "selects HOST_SYNC_RETURN_NOTHING before descriptor checking for "
            + "every declared return descriptor, while a present language null is "
            + "admitted by a null or nullable return and never collapses into "
            + "absence --");
        safely("the absent synchronous host results",
            CanonicalProjectionParityTest::driveHostAbsentSyncReturns);
        safely("the present synchronous host nulls",
            CanonicalProjectionParityTest::driveHostPresentNullSyncReturns);
        safely("the present synchronous host value",
            CanonicalProjectionParityTest::driveHostPresentValueSyncReturn);
    }

    /** The caller-owned sync-presence project (one declaration and driver). */
    private static Compiled compileSyncPresence() throws Exception {
        Case spec = new Case("host-abi/host-async-shape-bad", "test_drop_nullable",
            Entry.MAIN, List.of(), null, null);
        return compile(spec, SYNC_PRESENCE_DRIVER,
            Map.of("sync_presence", SYNC_PRESENCE_DECLARATION));
    }

    /**
     * The absent host results: a declared void method (JVM), a zero-result
     * return (LuaJIT), and the absent terminal (oracle) select the closed
     * {@code HOST_SYNC_RETURN_NOTHING} arm at a {@code string | null}, a
     * {@code null}, and a mandatory {@code int} return, byte-exact and
     * identical on all three consumers.
     */
    private static void driveHostAbsentSyncReturns() throws Exception {
        Compiled compiled = compileSyncPresence();
        try {
            SemanticOracle.HostResponder absent = absentSyncResponder();
            assertSyncPresenceFailure(compiled, absent, "test_drop_nullable",
                hostNothingTuple("?string", 4, 3), luaAbsentSyncHost(),
                jvmAbsentSyncHost(), "the absent ?string return");
            assertSyncPresenceFailure(compiled, absent, "test_drop_null",
                hostNothingTuple("null", 9, 3), luaAbsentSyncHost(),
                jvmAbsentSyncHost(), "the absent null return");
            assertSyncPresenceFailure(compiled, absent, "test_drop_mandatory",
                hostNothingTuple("int", 14, 3), luaAbsentSyncHost(),
                jvmAbsentSyncHost(), "the absent int return");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /**
     * The present language nulls: the explicit null sentinel (LuaJIT), a
     * present Java null (JVM), and the oracle's {@code NullValue} are
     * admitted by a declared {@code null} or {@code string | null} return
     * — the consuming probe observes the language null — and are never
     * collapsed into absence. At the mandatory {@code int} return a present
     * Java null is a value like every other present carrier: all three
     * consumers render the identical closed {@code HOST_SYNC_RETURN_CELL}
     * composite (a present wrong kind), while only a true no-result host
     * invocation selects the no-value arm.
     */
    private static void driveHostPresentNullSyncReturns() throws Exception {
        Compiled compiled = compileSyncPresence();
        try {
            SemanticOracle.HostResponder presentNull = presentNullSyncResponder();
            assertSyncPresenceSuccess(compiled, presentNull, "test_use_nullable",
                luaPresentNullSyncHost(), jvmPresentNullSyncHost(),
                "the present null ?string return");
            assertSyncPresenceSuccess(compiled, presentNull, "test_use_null",
                luaPresentNullSyncHost(), jvmPresentNullSyncHost(),
                "the present null null return");
            assertSyncPresenceFailure(compiled, presentNull, "test_use_mandatory",
                hostReturnCellTuple("int", FailureProjections.kindReason(
                    RuntimeDescriptor.Int.INSTANCE), "table", 32, 3),
                luaPresentNullSyncHost(), jvmPresentNullSyncHost(),
                "the present null int return");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /**
     * The present-value control: a real int result at the mandatory return
     * is admitted on all three consumers, so the presence rule never
     * treats a present value as absence.
     */
    private static void driveHostPresentValueSyncReturn() throws Exception {
        Compiled compiled = compileSyncPresence();
        try {
            assertSyncPresenceSuccess(compiled, presentValueSyncResponder(),
                "test_drop_mandatory", luaPresentValueSyncHost(),
                jvmPresentValueSyncHost(), "the present int return");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    // =========================================================================
    // The numeric host return carriers (ISSUE-0696 remediation)
    // =========================================================================

    /**
     * The numeric host return carriers: every admitted numeric carrier of
     * the closed host ABI — the boxed {@code int} (Integer), the signed32
     * {@code long}, and the {@code double} — is validated in the pinned int
     * order (NaN, infinity, non-integer, then signed32 range) and an
     * admitted value is normalized to the declared return carrier before
     * the carrier cast, on the real emitted wrappers. The drive runs the
     * production LuaJIT artifact, the production JVM artifact, and the
     * oracle over the same caller-owned declaration: an in-range carrier is
     * admitted with its value consumed in-language, while an out-of-range,
     * fractional, NaN, or infinite carrier renders the closed
     * {@code HOST_SYNC_RETURN_CELL} composite with the selected inner arm
     * at the call origin — never a ClassCastException and never a silent
     * truncation.
     */
    private static void testHostNumericReturnCarriers() throws Exception {
        System.out.println("-- the numeric host return carriers: in-range, "
            + "out-of-range, fractional, NaN, and infinite carriers of the real "
            + "emitted wrappers --");
        safely("the numeric host return carriers",
            CanonicalProjectionParityTest::driveHostNumericReturnCarriers);
    }

    // =========================================================================
    // The numeric host async completions (ISSUE-0696 remediation)
    // =========================================================================

    /**
     * The caller-owned numeric-completion declaration of the async drives
     * (no corpus or host-fixture membership change): a mandatory int, a
     * nullable int, and a number completion, each carrying the closed host
     * numeric carriers.
     */
    private static final String NUMERIC_COMPLETION_DECLARATION = """
        export async function fetchInt(): int;
        export async function fetchNullableInt(): int | null;
        export async function fetchNumber(): number;
        """;

    /**
     * The int-completion probe: the admitted completion is consumed by a
     * real int addition, so an unnormalized carrier (a Double kept as the
     * production int) cannot reach the probe's success.
     */
    private static final String NUMERIC_COMPLETION_INT_DRIVER = """
        import * as host from "host/async_numeric"

        export async function test_use_int(): int {
          let v: int = await host.fetchInt()
          let w: int = v + 1
          return w
        }

        export function main(): null {
          return null
        }
        """;

    /** The nullable-int-completion probe: the settled completion is consumed. */
    private static final String NUMERIC_COMPLETION_NULLABLE_INT_DRIVER = """
        import * as host from "host/async_numeric"

        export async function test_use_nullable_int(): int {
          let v: int | null = await host.fetchNullableInt()
          if (v !== null) {
            let w: int = v + 1
            return w
          }
          return 0
        }

        export function main(): null {
          return null
        }
        """;

    /** The number-completion probe: the settled completion is consumed. */
    private static final String NUMERIC_COMPLETION_NUMBER_DRIVER = """
        import * as host from "host/async_numeric"

        export async function test_use_number(): number {
          let v: number = await host.fetchNumber()
          let w: number = v + 0.5
          return w
        }

        export function main(): null {
          return null
        }
        """;

    /** One numeric-completion carrier triple: the oracle value and both host carriers. */
    private record NumericCompletion(SemanticOracle.Value oracle, String lua,
                                     String jvm) {
    }

    /**
     * The numeric host async completions (ISSUE-0696 remediation; the raw
     * future path): a production host operation's completed future carries
     * the closed host numeric carriers — the boxed int (Integer), the
     * signed32 long, and the double — and crosses the single
     * {@code ASYNC_COMPLETION} boundary at the await site. The crossing
     * validates the carriers in the pinned int order and settles them onto
     * the declared production carrier (an Integer or an admitted integral
     * Double becomes the production int carrier before any in-language int
     * use), exactly like the synchronous host return cell's
     * declared-carrier normalization, so the oracle, the LuaJIT artifact,
     * and the JVM artifact render the identical complete tuple for the same
     * carrier and an out-of-range or non-integral carrier fails at the
     * await expression, never at a later consuming op. The drive's
     * declaration and every host implementation are caller-owned.
     */
    private static void testHostNumericCompletions() throws Exception {
        System.out.println("-- the numeric host async completions: the closed "
            + "host numeric carriers are validated and settled onto the declared "
            + "production carrier at the await crossing --");
        check(JvmRuntime.hostCompletion("int", "int", Integer.valueOf(7))
            instanceof Long, "the host completion seam settles the boxed int onto "
                + "the production int carrier");
        check(JvmRuntime.hostCompletion("nullable(int)", "nullable:int",
            Integer.valueOf(7)) instanceof Long, "the host completion seam "
                + "settles a nullable int completion's carrier");
        try {
            JvmRuntime.hostCompletion("int", "int", "x");
            check(false, "the host completion seam rejects a non-numeric carrier "
                + "through the completion cell's own check");
        } catch (JvmRuntime.DealError kind) {
            check("E8001".equals(kind.code) && "expected int".equals(kind.msg),
                "the host completion seam rejects a non-numeric carrier through "
                    + "the completion cell's own check: " + kind.code + " "
                    + kind.msg);
        }
        safely("the int host completions",
            CanonicalProjectionParityTest::driveHostIntCompletions);
        safely("the nullable-int host completions",
            CanonicalProjectionParityTest::driveHostNullableIntCompletions);
        safely("the number host completions",
            CanonicalProjectionParityTest::driveHostNumberCompletions);
    }

    /** The int completion drive: admitted carriers and the rejected ones. */
    private static void driveHostIntCompletions() throws Exception {
        Case spec = new Case("host-abi/host-async-shape-bad", "test_use_int",
            Entry.ASYNC, List.of(), "async_numeric", null);
        Compiled compiled = compile(spec, NUMERIC_COMPLETION_INT_DRIVER,
            Map.of("async_numeric", NUMERIC_COMPLETION_DECLARATION));
        try {
            for (NumericCompletion carrier : List.of(
                    new NumericCompletion(new SemanticOracle.Value.IntValue(7L),
                        "7", "java.lang.Integer.valueOf(7)"),
                    new NumericCompletion(new SemanticOracle.Value.IntValue(7L),
                        "7", "java.lang.Long.valueOf(7)"),
                    new NumericCompletion(new SemanticOracle.Value.IntValue(7L),
                        "7", "java.lang.Double.valueOf(7)"))) {
                assertAsyncCompletionSuccess(compiled, "async_numeric",
                    numericCompletionResponder(carrier.oracle()), "test_use_int",
                    luaAsyncNumericHost(carrier.lua()),
                    jvmAsyncNumericHost(carrier.jvm()),
                    "the int completion " + carrier.jvm());
            }
            Tuple range = asyncRangeTuple(NUMERIC_COMPLETION_INT_DRIVER, 4);
            assertAsyncCompletionFailure(compiled, "async_numeric",
                numericCompletionResponder(
                    new SemanticOracle.Value.NumValue(2147483648.0)),
                "test_use_int", range, luaAsyncNumericHost("2147483648"),
                jvmAsyncNumericHost("java.lang.Long.valueOf(2147483648L)"),
                "the out-of-range Long int completion");
            assertAsyncCompletionFailure(compiled, "async_numeric",
                numericCompletionResponder(
                    new SemanticOracle.Value.NumValue(2147483648.0)),
                "test_use_int", range, luaAsyncNumericHost("2147483648"),
                jvmAsyncNumericHost("java.lang.Double.valueOf(2147483648.0)"),
                "the out-of-range Double int completion");
            assertAsyncCompletionFailure(compiled, "async_numeric",
                numericCompletionResponder(
                    new SemanticOracle.Value.NumValue(Double.NaN)),
                "test_use_int", asyncRefinementTuple(
                    NUMERIC_COMPLETION_INT_DRIVER, 4, "NaN"),
                luaAsyncNumericHost("0/0"),
                jvmAsyncNumericHost("java.lang.Double.valueOf(java.lang.Double.NaN)"),
                "the NaN Double int completion");
            assertAsyncCompletionFailure(compiled, "async_numeric",
                numericCompletionResponder(
                    new SemanticOracle.Value.NumValue(Double.POSITIVE_INFINITY)),
                "test_use_int", asyncRefinementTuple(
                    NUMERIC_COMPLETION_INT_DRIVER, 4, "infinity"),
                luaAsyncNumericHost("math.huge"),
                jvmAsyncNumericHost(
                    "java.lang.Double.valueOf(java.lang.Double.POSITIVE_INFINITY)"),
                "the infinite Double int completion");
            assertAsyncCompletionFailure(compiled, "async_numeric",
                numericCompletionResponder(
                    new SemanticOracle.Value.NumValue(1.5)),
                "test_use_int", asyncRefinementTuple(
                    NUMERIC_COMPLETION_INT_DRIVER, 4, "number"),
                luaAsyncNumericHost("1.5"),
                jvmAsyncNumericHost("java.lang.Double.valueOf(1.5)"),
                "the fractional Double int completion");
            assertAsyncCompletionFailure(compiled, "async_numeric",
                numericCompletionResponder(
                    new SemanticOracle.Value.StrValue("x")),
                "test_use_int", asyncKindTuple(NUMERIC_COMPLETION_INT_DRIVER, 4,
                    "int", "string"),
                luaAsyncNumericHost("\"x\""),
                jvmAsyncNumericHost("\"x\""),
                "the string int completion");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /** The nullable-int completion drive: admitted carriers, null, and the range arm. */
    private static void driveHostNullableIntCompletions() throws Exception {
        Case spec = new Case("host-abi/host-async-shape-bad",
            "test_use_nullable_int", Entry.ASYNC, List.of(), "async_numeric",
            null);
        Compiled compiled = compile(spec, NUMERIC_COMPLETION_NULLABLE_INT_DRIVER,
            Map.of("async_numeric", NUMERIC_COMPLETION_DECLARATION));
        try {
            for (NumericCompletion carrier : List.of(
                    new NumericCompletion(new SemanticOracle.Value.IntValue(7L),
                        "7", "java.lang.Integer.valueOf(7)"),
                    new NumericCompletion(new SemanticOracle.Value.IntValue(7L),
                        "7", "java.lang.Long.valueOf(7)"),
                    new NumericCompletion(new SemanticOracle.Value.IntValue(7L),
                        "7", "java.lang.Double.valueOf(7)"),
                    new NumericCompletion(SemanticOracle.Value.NullValue.INSTANCE,
                        "rt.__NULL", "null"))) {
                assertAsyncCompletionSuccess(compiled, "async_numeric",
                    numericCompletionResponder(carrier.oracle()),
                    "test_use_nullable_int",
                    luaAsyncNumericHost(carrier.lua()),
                    jvmAsyncNumericHost(carrier.jvm()),
                    "the nullable-int completion " + carrier.jvm());
            }
            assertAsyncCompletionFailure(compiled, "async_numeric",
                numericCompletionResponder(
                    new SemanticOracle.Value.NumValue(2147483648.0)),
                "test_use_nullable_int",
                asyncRangeTuple(NUMERIC_COMPLETION_NULLABLE_INT_DRIVER, 4),
                luaAsyncNumericHost("2147483648"),
                jvmAsyncNumericHost("java.lang.Long.valueOf(2147483648L)"),
                "the out-of-range Long nullable-int completion");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /** The number completion drive: every numeric carrier and the kind control. */
    private static void driveHostNumberCompletions() throws Exception {
        Case spec = new Case("host-abi/host-async-shape-bad", "test_use_number",
            Entry.ASYNC, List.of(), "async_numeric", null);
        Compiled compiled = compile(spec, NUMERIC_COMPLETION_NUMBER_DRIVER,
            Map.of("async_numeric", NUMERIC_COMPLETION_DECLARATION));
        try {
            for (NumericCompletion carrier : List.of(
                    new NumericCompletion(new SemanticOracle.Value.NumValue(7.0),
                        "7", "java.lang.Integer.valueOf(7)"),
                    new NumericCompletion(new SemanticOracle.Value.NumValue(7.0),
                        "7", "java.lang.Long.valueOf(7)"),
                    new NumericCompletion(new SemanticOracle.Value.NumValue(7.5),
                        "7.5", "java.lang.Double.valueOf(7.5)"))) {
                assertAsyncCompletionSuccess(compiled, "async_numeric",
                    numericCompletionResponder(carrier.oracle()),
                    "test_use_number", luaAsyncNumericHost(carrier.lua()),
                    jvmAsyncNumericHost(carrier.jvm()),
                    "the number completion " + carrier.jvm());
            }
            assertAsyncCompletionFailure(compiled, "async_numeric",
                numericCompletionResponder(
                    new SemanticOracle.Value.StrValue("x")),
                "test_use_number", asyncKindTuple(NUMERIC_COMPLETION_NUMBER_DRIVER,
                    4, "number", "string"),
                luaAsyncNumericHost("\"x\""),
                jvmAsyncNumericHost("\"x\""),
                "the string number completion");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /** The async-completion responder: every declared operation completes with the value. */
    private static SemanticOracle.HostResponder numericCompletionResponder(
            SemanticOracle.Value value) {
        return new SemanticOracle.HostResponder() {
            @Override
            public AsyncStartOutcome startAsync(ModuleId module, String export,
                    RuntimeDescriptor.Func descriptor,
                    List<SemanticOracle.Value> args, String operationLabel) {
                return new AsyncStartOutcome.Operation(operationLabel);
            }

            @Override
            public SyncOutcome completeAsync(String operationLabel) {
                return new SyncOutcome.Returned(value);
            }
        };
    }

    /** The LuaJIT numeric-completion host: one plain carrier for every export. */
    private static String luaAsyncNumericHost(String value) {
        return """
            local rt = require("deal.runtime")

            return {
              fetchInt = function()
                return rt.async_start(function() return %s end)
              end,
              fetchNullableInt = function()
                return rt.async_start(function() return %s end)
              end,
              fetchNumber = function()
                return rt.async_start(function() return %s end)
              end,
            }
            """.formatted(value, value, value);
    }

    /** The JVM numeric-completion host: one boxed carrier expression for every export. */
    private static String jvmAsyncNumericHost(String carrier) {
        return """
            import java.util.concurrent.CompletableFuture;

            final class HostAsync_numeric {
              public static Object fetchInt() {
                return CompletableFuture.completedFuture(%s);
              }
              public static Object fetchNullableInt() {
                return CompletableFuture.completedFuture(%s);
              }
              public static Object fetchNumber() {
                return CompletableFuture.completedFuture(%s);
              }
            }
            """.formatted(carrier, carrier, carrier);
    }

    // =========================================================================
    // The array host async completions (ISSUE-0793 remediation)
    // =========================================================================

    /**
     * The caller-owned array-completion declaration of the array drives
     * (no corpus or host-fixture membership change): a mandatory int
     * array, a nullable int array, an int-or-null element array, a nested
     * int array, a nested string array, a string array, and a mandatory
     * number array.
     */
    private static final String ARRAY_COMPLETION_DECLARATION = """
        export async function fetchInts(): int[];
        export async function fetchNullableInts(): int[] | null;
        export async function fetchNullableElements(): (int | null)[];
        export async function fetchNestedInts(): int[][];
        export async function fetchNestedStrings(): string[][];
        export async function fetchStrings(): string[];
        export async function fetchNumbers(): number[];
        """;

    /**
     * The admitted int-array probe: the settled element is consumed by a
     * real int comparison and addition, so a boxed carrier that did not
     * settle onto the production int carrier (or a wrong element value)
     * cannot reach the probe's success.
     */
    private static final String ARRAY_COMPLETION_INT_DRIVER = """
        import * as host from "host/async_arrays"

        export async function test_use_int_array(): int {
          let xs: int[] = await host.fetchInts()
          let v: int = xs[0]
          if (v !== 7) {
            throw { code: "PROBE_BOXED_ELEMENT", message: "the boxed int element did not land 7" }
          }
          return v + 1
        }

        export function main(): null {
          return null
        }
        """;

    /** The admitted nullable-int-array probe (and its language-null control). */
    private static final String ARRAY_COMPLETION_NULLABLE_INT_DRIVER = """
        import * as host from "host/async_arrays"

        export async function test_use_nullable_int_array(): int {
          let xs: int[] | null = await host.fetchNullableInts()
          if (xs !== null) {
            let v: int = xs[0]
            if (v !== 7) {
              throw { code: "PROBE_BOXED_ELEMENT", message: "the nullable boxed int element did not land 7" }
            }
            return v + 1
          }
          return 0
        }

        export function main(): null {
          return null
        }
        """;

    /**
     * The admitted int-or-null element array probe: the boxed int element
     * settles and is consumed, and the language-null element stays null.
     */
    private static final String ARRAY_COMPLETION_NULLABLE_ELEMENT_DRIVER = """
        import * as host from "host/async_arrays"

        export async function test_use_nullable_elements(): int {
          let xs: (int | null)[] = await host.fetchNullableElements()
          let v: int | null = xs[0]
          let w: int | null = xs[1]
          if (w !== null) {
            throw { code: "PROBE_BOXED_ELEMENT", message: "the null element did not stay null" }
          }
          if (v !== null) {
            if (v !== 7) {
              throw { code: "PROBE_BOXED_ELEMENT", message: "the nullable-element boxed int did not land 7" }
            }
            let total: int = v + 1
            return total
          }
          return 0
        }

        export function main(): null {
          return null
        }
        """;

    /** The admitted nested-int-array probe. */
    private static final String ARRAY_COMPLETION_NESTED_INT_DRIVER = """
        import * as host from "host/async_arrays"

        export async function test_use_nested_int_array(): int {
          let xs: int[][] = await host.fetchNestedInts()
          let v: int = xs[0][0]
          if (v !== 7) {
            throw { code: "PROBE_BOXED_ELEMENT", message: "the nested boxed int element did not land 7" }
          }
          return v + 1
        }

        export function main(): null {
          return null
        }
        """;

    /**
     * The admitted number-array probe: the boxed int element is admitted at
     * the declared number element position — exactly like the oracle's
     * IntValue and the Lua artifact's integral number — and consumed by a
     * real number comparison, so a rejected carrier cannot reach the
     * probe's success.
     */
    private static final String ARRAY_COMPLETION_NUMBER_DRIVER = """
        import * as host from "host/async_arrays"

        export async function test_use_number_array(): int {
          let xs: number[] = await host.fetchNumbers()
          let v: number = xs[0]
          if (v !== 7.0) {
            throw { code: "PROBE_BOXED_ELEMENT", message: "the boxed int element did not land 7" }
          }
          return 8
        }

        export function main(): null {
          return null
        }
        """;

    /** The wrong-kind string-array probe: only the crossing's own check rejects it. */
    private static final String ARRAY_COMPLETION_STRING_DRIVER = """
        import * as host from "host/async_arrays"

        export async function test_drop_string_array(): null {
          let xs: string[] = await host.fetchStrings()
          return null
        }

        export function main(): null {
          return null
        }
        """;

    /** The wrong-kind nested string-array probe. */
    private static final String ARRAY_COMPLETION_NESTED_STRING_DRIVER = """
        import * as host from "host/async_arrays"

        export async function test_drop_nested_string_array(): null {
          let xs: string[][] = await host.fetchNestedStrings()
          return null
        }

        export function main(): null {
          return null
        }
        """;

    /**
     * The array host async completions (ISSUE-0793 remediation): a host
     * operation's completed future carries an array whose elements are the
     * raw closed host numeric carriers, and the value crosses the single
     * {@code ASYNC_COMPLETION} boundary at the await site. The boxed int at
     * any element position — the nested arrays' and the nullable positions
     * included — settles onto the production int carrier exactly like the
     * top-level crossing, so the oracle, the LuaJIT artifact, and the JVM
     * artifact admit the same element; a boxed int at a wrong-kind element
     * position renders the canonical element arm with the element's own
     * kind, never the declared element's. The drives' declarations and host
     * implementations are caller-owned.
     */
    private static void testHostArrayCompletions() throws Exception {
        System.out.println("-- the array host async completions: the boxed int at "
            + "array element positions settles onto the production carrier in place "
            + "and a wrong-kind boxed int projects its own kind on all three "
            + "consumers --");
        JvmRuntime.Array boxed = new JvmRuntime.Array(1);
        boxed.elements.add(Integer.valueOf(7));
        Object settled = JvmRuntime.hostCompletion("array(int)", "array", boxed);
        check(settled == boxed && boxed.elements.size() == 1
                && Long.valueOf(7L).equals(boxed.elements.get(0)),
            "the host completion seam preserves the completed array's identity and "
                + "settles its boxed int element onto the production int carrier");
        JvmRuntime.Array inner = new JvmRuntime.Array(1);
        inner.elements.add(Integer.valueOf(7));
        JvmRuntime.Array nested = new JvmRuntime.Array(2);
        nested.elements.add(inner);
        nested.elements.add(inner);
        Object settledNested = JvmRuntime.hostCompletion("array(array(int))",
            "array", nested);
        check(settledNested == nested && nested.elements.get(0) == inner
                && nested.elements.get(1) == inner
                && Long.valueOf(7L).equals(inner.elements.get(0)),
            "the host completion seam keeps the completed tree's identities: one "
                + "repeated inner carrier stays one array (the reference-identity "
                + "contract) and its boxed element settles in place");
        JvmRuntime.Array boxedNumber = new JvmRuntime.Array(2);
        boxedNumber.elements.add(Integer.valueOf(7));
        boxedNumber.elements.add(null);
        Object settledNumber = JvmRuntime.hostCompletion(
            "array(nullable(number))", "array", boxedNumber);
        check(settledNumber == boxedNumber
                && Long.valueOf(7L).equals(boxedNumber.elements.get(0))
                && boxedNumber.elements.get(1) == null,
            "the host completion seam admits a boxed int at a nullable number "
                + "element position in place and keeps the language null element");
        JvmRuntime.Array wrongKind = new JvmRuntime.Array(1);
        wrongKind.elements.add(Integer.valueOf(7));
        try {
            JvmRuntime.hostCompletion("array(string)", "array", wrongKind);
            check(false, "the host completion seam rejects the boxed int at a "
                + "string element position");
        } catch (JvmRuntime.DealError element) {
            check("E8003".equals(element.code)
                    && "array element 1 type mismatch".equals(element.msg)
                    && "string".equals(element.expected)
                    && "int".equals(element.actual),
                "the host completion seam renders the canonical element arm with "
                    + "the boxed int's own kind: " + element.code + " "
                    + element.msg + " expected " + element.expected + " actual "
                    + element.actual);
        }
        check(wrongKind.elements.get(0) instanceof Integer,
            "the rejected host completion commits nothing: the host's own carrier "
                + "keeps its boxed int element untouched");
        JvmRuntime.Array nestedWrongKind = new JvmRuntime.Array(1);
        JvmRuntime.Array nestedWrongInner = new JvmRuntime.Array(1);
        nestedWrongInner.elements.add(Integer.valueOf(7));
        nestedWrongKind.elements.add(nestedWrongInner);
        try {
            JvmRuntime.hostCompletion("array(array(string))", "array",
                nestedWrongKind);
            check(false, "the host completion seam rejects the nested boxed int at "
                + "a string element position");
        } catch (JvmRuntime.DealError nestedElement) {
            check("E8003".equals(nestedElement.code),
                "the host completion seam renders the nested element failure: "
                    + nestedElement.code + " " + nestedElement.msg);
        }
        check(nestedWrongInner.elements.get(0) instanceof Integer,
            "the rejected nested host completion commits nothing: the inner "
                + "carrier keeps its boxed int element untouched");
        JvmRuntime.Array cyclic = new JvmRuntime.Array(1);
        cyclic.elements.add(cyclic);
        try {
            JvmRuntime.hostCompletion("array(int)", "array", cyclic);
            check(false, "the host completion seam's cyclic carrier walk "
                + "terminates through the element arm");
        } catch (JvmRuntime.DealError cyclicFailure) {
            check("E8003".equals(cyclicFailure.code),
                "the host completion seam terminates a reference-cyclic host "
                    + "carrier through the element arm: " + cyclicFailure.code
                    + " " + cyclicFailure.msg);
        } catch (StackOverflowError overflow) {
            check(false, "the host completion seam's cyclic carrier walk must "
                + "terminate through the identity memo, never overflow");
        }
        JvmRuntime.Array rangeElement = new JvmRuntime.Array(1);
        rangeElement.elements.add(Long.valueOf(2147483648L));
        try {
            Object admitted = JvmRuntime.hostCompletion("array(int)", "array",
                rangeElement);
            check(false, "the host completion seam rejects the out-of-range "
                + "Long int element: " + admitted);
        } catch (JvmRuntime.DealError rangeFailure) {
            check("E8003".equals(rangeFailure.code)
                    && "array element 1 type mismatch".equals(rangeFailure.msg)
                    && "int".equals(rangeFailure.expected)
                    && "number".equals(rangeFailure.actual),
                "the host completion seam renders the out-of-range Long element "
                    + "through the element arm with the number carrier's own "
                    + "token: " + rangeFailure.code + " " + rangeFailure.msg
                    + " expected " + rangeFailure.expected + " actual "
                    + rangeFailure.actual);
        }
        check(Long.valueOf(2147483648L).equals(rangeElement.elements.get(0)),
            "the rejected out-of-range completion commits nothing: the host's own "
                + "carrier keeps its long element untouched");
        safely("the int[] host completion",
            CanonicalProjectionParityTest::driveHostIntArrayCompletion);
        safely("the nullable int[] host completion",
            CanonicalProjectionParityTest::driveHostNullableIntArrayCompletion);
        safely("the int-or-null element array host completion",
            CanonicalProjectionParityTest::driveHostNullableElementArrayCompletion);
        safely("the nested int[][] host completion",
            CanonicalProjectionParityTest::driveHostNestedIntArrayCompletion);
        safely("the number[] host completion",
            CanonicalProjectionParityTest::driveHostNumberArrayCompletion);
        safely("the wrong-kind string[] host completion",
            CanonicalProjectionParityTest::driveHostStringArrayCompletion);
        safely("the wrong-kind nested string[][] host completion",
            CanonicalProjectionParityTest::driveHostNestedStringArrayCompletion);
        safely("the deep later-subtree string[] host completion",
            CanonicalProjectionParityTest::driveHostDeepStringArrayCompletion);
        safely("the cyclic later-element string[] host completion",
            CanonicalProjectionParityTest::driveHostCyclicStringArrayCompletion);
        safely("the cyclic wrong-kind-element string[] host completion",
            CanonicalProjectionParityTest::driveHostCyclicWrongKindArrayCompletion);
        safely("the out-of-range long int[] host completion",
            CanonicalProjectionParityTest::driveHostRangeElementIntArrayCompletion);
        safely("the repeated shared int[] host completion",
            CanonicalProjectionParityTest::driveHostSharedArrayCompletion);
    }

    /** The array-completion case anchor of one probe export. */
    private static Case arrayCompletionCase(String probe) {
        return new Case("host-abi/host-async-shape-bad", probe, Entry.ASYNC,
            List.of(), "async_arrays", null);
    }

    /** One array-completion probe compiled with the caller-owned declaration. */
    private static Compiled compileArrayCompletion(String probe, String driver)
            throws Exception {
        return compile(arrayCompletionCase(probe), driver,
            Map.of("async_arrays", ARRAY_COMPLETION_DECLARATION));
    }

    /**
     * One admitted array host completion on all three consumers: the
     * oracle's async entry completes successfully with the probe's pinned
     * result atom (the consumed element's own arithmetic), and both
     * production artifacts complete without a failure event — their probes'
     * in-language guard raises the probe error if the settled element did
     * not land 7.
     */
    private static void assertAsyncArrayCompletionSuccess(Compiled compiled,
            String export, String resultAtom, SemanticOracle.HostResponder responder,
            String luaHost, String jvmHost, String what) throws Exception {
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.invokeAsyncEntry(
            compiled.project(), compiled.tables(), responder,
            compiled.checkedProject().entryModule(), export, List.of());
        check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success success
                && resultAtom.equals(success.resultAtom()),
            what + " (oracle): the settled element is admitted and consumed with "
                + "the pinned result " + resultAtom + ": " + run.terminal());
        String lua = luaArtifactOutput(compiled, Map.of("async_arrays", luaHost));
        check(lua.contains("OK") && !lua.contains("ERR|"), what + " (luajit): the "
            + "production artifact completes without a failure event: " + lua);
        String jvm = jvmArtifactOutput(compiled, jvmProbe(
            JvmBackend.classNameFor(compiled.checkedProject().entryModule().path()),
            compiled.checkedProject().entryModule().path(), ""),
            Map.of("async_arrays", jvmHost));
        check(jvm != null && jvm.contains("OK") && !jvm.contains("ERR|"), what
            + " (jvm): the production artifact completes without a failure event: "
            + jvm);
    }

    /** The array-completion responder: every declared operation completes with the value. */
    private static SemanticOracle.HostResponder arrayCompletionResponder(
            SemanticOracle.Value value) {
        return new SemanticOracle.HostResponder() {
            @Override
            public AsyncStartOutcome startAsync(ModuleId module, String export,
                    RuntimeDescriptor.Func descriptor,
                    List<SemanticOracle.Value> args, String operationLabel) {
                return new AsyncStartOutcome.Operation(operationLabel);
            }

            @Override
            public SyncOutcome completeAsync(String operationLabel) {
                return new SyncOutcome.Returned(value);
            }
        };
    }

    /** The oracle's one-element int array completion value (the boxed element). */
    private static SemanticOracle.Value oracleIntArrayElement(long value) {
        return new SemanticOracle.Value.ArrayValue(
            new ArrayList<>(List.of(new SemanticOracle.Value.IntValue(value))),
            RuntimeDescriptor.Int.INSTANCE, false);
    }

    /** The oracle's one-element nested int array completion value. */
    private static SemanticOracle.Value oracleNestedIntArrayElement(long value) {
        return new SemanticOracle.Value.ArrayValue(
            new ArrayList<>(List.of(oracleIntArrayElement(value))),
            RuntimeDescriptor.parseCanonicalText("[int]"), false);
    }

    /** The oracle's one-element number array completion value (the boxed element). */
    private static SemanticOracle.Value oracleNumberArrayElement(long value) {
        return new SemanticOracle.Value.ArrayValue(
            new ArrayList<>(List.of(new SemanticOracle.Value.IntValue(value))),
            RuntimeDescriptor.Number.INSTANCE, false);
    }

    /** The oracle's int-or-null element array completion value. */
    private static SemanticOracle.Value oracleNullableElementArray() {
        return new SemanticOracle.Value.ArrayValue(
            new ArrayList<>(List.of(new SemanticOracle.Value.IntValue(7L),
                SemanticOracle.Value.NullValue.INSTANCE)),
            RuntimeDescriptor.parseCanonicalText("?int"), false);
    }

    /** The oracle's string array completion value carrying one element. */
    private static SemanticOracle.Value oracleStringArrayElement(
            SemanticOracle.Value element) {
        return new SemanticOracle.Value.ArrayValue(
            new ArrayList<>(List.of(element)), RuntimeDescriptor.String.INSTANCE,
            false);
    }

    /** The LuaJIT array-completion host: one carrier expression for every export. */
    private static String luaAsyncArrayHost(String carrier) {
        return """
            local rt = require("deal.runtime")

            return {
              fetchInts = function()
                return rt.async_start(function() return %s end)
              end,
              fetchNullableInts = function()
                return rt.async_start(function() return %s end)
              end,
              fetchNullableElements = function()
                return rt.async_start(function() return %s end)
              end,
              fetchNestedInts = function()
                return rt.async_start(function() return %s end)
              end,
              fetchNestedStrings = function()
                return rt.async_start(function() return %s end)
              end,
              fetchStrings = function()
                return rt.async_start(function() return %s end)
              end,
              fetchNumbers = function()
                return rt.async_start(function() return %s end)
              end,
            }
            """.formatted(carrier, carrier, carrier, carrier, carrier, carrier,
                carrier);
    }

    /** The JVM array-completion host: one completed-future carrier per export. */
    private static String jvmAsyncArrayHost(String carrier) {
        return """
            import java.util.concurrent.CompletableFuture;

            final class HostAsync_arrays {
              private static deal.codegen.jvm.JvmRuntime.Array array(
                  java.lang.Object... elements) {
                deal.codegen.jvm.JvmRuntime.Array array =
                    new deal.codegen.jvm.JvmRuntime.Array(elements.length);
                for (java.lang.Object element : elements) {
                  array.elements.add(element);
                }
                return array;
              }

              public static Object fetchInts() {
                return CompletableFuture.completedFuture(%s);
              }
              public static Object fetchNullableInts() {
                return CompletableFuture.completedFuture(%s);
              }
              public static Object fetchNullableElements() {
                return CompletableFuture.completedFuture(%s);
              }
              public static Object fetchNestedInts() {
                return CompletableFuture.completedFuture(%s);
              }
              public static Object fetchNestedStrings() {
                return CompletableFuture.completedFuture(%s);
              }
              public static Object fetchStrings() {
                return CompletableFuture.completedFuture(%s);
              }
              public static Object fetchNumbers() {
                return CompletableFuture.completedFuture(%s);
              }
            }
            """.formatted(carrier, carrier, carrier, carrier, carrier, carrier,
                carrier);
    }

    /** The admitted int[] completion: the boxed element settles and is consumed. */
    private static void driveHostIntArrayCompletion() throws Exception {
        Compiled compiled = compileArrayCompletion("test_use_int_array",
            ARRAY_COMPLETION_INT_DRIVER);
        try {
            assertAsyncArrayCompletionSuccess(compiled, "test_use_int_array", "int:8",
                arrayCompletionResponder(oracleIntArrayElement(7L)),
                luaAsyncArrayHost("{__a = true, __n = 1, [1] = 7}"),
                jvmAsyncArrayHost("array(java.lang.Integer.valueOf(7))"),
                "the boxed int int[] completion");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /**
     * The nullable int[] completion: the boxed element settles and is
     * consumed, and the language-null control stays the language null.
     */
    private static void driveHostNullableIntArrayCompletion() throws Exception {
        Compiled compiled = compileArrayCompletion("test_use_nullable_int_array",
            ARRAY_COMPLETION_NULLABLE_INT_DRIVER);
        try {
            assertAsyncArrayCompletionSuccess(compiled, "test_use_nullable_int_array",
                "int:8", arrayCompletionResponder(oracleIntArrayElement(7L)),
                luaAsyncArrayHost("{__a = true, __n = 1, [1] = 7}"),
                jvmAsyncArrayHost("array(java.lang.Integer.valueOf(7))"),
                "the boxed int nullable int[] completion");
            assertAsyncArrayCompletionSuccess(compiled, "test_use_nullable_int_array",
                "int:0", arrayCompletionResponder(
                    SemanticOracle.Value.NullValue.INSTANCE),
                luaAsyncArrayHost("rt.__NULL"), jvmAsyncArrayHost("null"),
                "the language-null nullable int[] completion");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /** The int-or-null element array completion: the boxed element and the null element. */
    private static void driveHostNullableElementArrayCompletion() throws Exception {
        Compiled compiled = compileArrayCompletion("test_use_nullable_elements",
            ARRAY_COMPLETION_NULLABLE_ELEMENT_DRIVER);
        try {
            assertAsyncArrayCompletionSuccess(compiled, "test_use_nullable_elements",
                "int:8", arrayCompletionResponder(oracleNullableElementArray()),
                luaAsyncArrayHost("{__a = true, __n = 2, [1] = 7}"),
                jvmAsyncArrayHost("array(java.lang.Integer.valueOf(7), null)"),
                "the boxed int and null (int | null)[] completion");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /** The admitted nested int[][] completion: the nested boxed element settles. */
    private static void driveHostNestedIntArrayCompletion() throws Exception {
        Compiled compiled = compileArrayCompletion("test_use_nested_int_array",
            ARRAY_COMPLETION_NESTED_INT_DRIVER);
        try {
            assertAsyncArrayCompletionSuccess(compiled, "test_use_nested_int_array",
                "int:8", arrayCompletionResponder(oracleNestedIntArrayElement(7L)),
                luaAsyncArrayHost(
                    "{__a = true, __n = 1, [1] = {__a = true, __n = 1, [1] = 7}}"),
                jvmAsyncArrayHost("array(array(java.lang.Integer.valueOf(7)))"),
                "the nested boxed int int[][] completion");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /**
     * The admitted number[] completion: the boxed int element is admitted at
     * the declared number element position exactly like the oracle's IntValue
     * and the Lua artifact's integral number, and the probe consumes it.
     */
    private static void driveHostNumberArrayCompletion() throws Exception {
        Compiled compiled = compileArrayCompletion("test_use_number_array",
            ARRAY_COMPLETION_NUMBER_DRIVER);
        try {
            assertAsyncArrayCompletionSuccess(compiled, "test_use_number_array",
                "int:8", arrayCompletionResponder(oracleNumberArrayElement(7L)),
                luaAsyncArrayHost("{__a = true, __n = 1, [1] = 7}"),
                jvmAsyncArrayHost("array(java.lang.Integer.valueOf(7))"),
                "the boxed int number[] completion");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /** The wrong-kind string[] completion: the canonical element arm projects int. */
    private static void driveHostStringArrayCompletion() throws Exception {
        Compiled compiled = compileArrayCompletion("test_drop_string_array",
            ARRAY_COMPLETION_STRING_DRIVER);
        try {
            assertAsyncCompletionFailure(compiled, "async_arrays",
                arrayCompletionResponder(oracleStringArrayElement(
                    new SemanticOracle.Value.IntValue(7L))),
                "test_drop_string_array",
                asyncElementTuple(ARRAY_COMPLETION_STRING_DRIVER, 4, 1, "string",
                    "int"),
                luaAsyncArrayHost("{__a = true, __n = 1, [1] = 7}"),
                jvmAsyncArrayHost("array(java.lang.Integer.valueOf(7))"),
                "the boxed int string[] completion");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /** The wrong-kind nested string[][] completion: the outermost element arm. */
    private static void driveHostNestedStringArrayCompletion() throws Exception {
        Compiled compiled = compileArrayCompletion("test_drop_nested_string_array",
            ARRAY_COMPLETION_NESTED_STRING_DRIVER);
        try {
            assertAsyncCompletionFailure(compiled, "async_arrays",
                arrayCompletionResponder(new SemanticOracle.Value.ArrayValue(
                    new ArrayList<>(List.of(oracleStringArrayElement(
                        new SemanticOracle.Value.IntValue(7L)))),
                    RuntimeDescriptor.parseCanonicalText("[string]"), false)),
                "test_drop_nested_string_array",
                asyncElementTuple(ARRAY_COMPLETION_NESTED_STRING_DRIVER, 4, 1,
                    "[string]", "array"),
                luaAsyncArrayHost(
                    "{__a = true, __n = 1, [1] = {__a = true, __n = 1, [1] = 7}}"),
                jvmAsyncArrayHost("array(array(java.lang.Integer.valueOf(7)))"),
                "the nested boxed int string[][] completion");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /**
     * The deep later-subtree level count of the host-carrier depth
     * regression: the completed array's second element is a chain this many
     * production array carriers deep — a subtree the declared string element
     * descriptor never inspects.
     */
    private static final int DEEP_HOST_SUBTREE_LEVELS = 20000;

    /**
     * The deep later-subtree string[] completion: all three consumers are
     * supplied the same graph — the early wrong-kind boxed int followed by a
     * deep acyclic chain of production array carriers. The canonical
     * first-failing-element rule must decide the tuple before any conversion
     * or settlement traversal visits the raw host graph, so the oracle's
     * lazily converted boundary view and both production artifacts render
     * the same complete E8003 tuple — never a raw host error for the
     * irrelevant later subtree, and never a converted tail on the oracle.
     */
    private static void driveHostDeepStringArrayCompletion() throws Exception {
        Compiled compiled = compileArrayCompletion("test_drop_string_array",
            ARRAY_COMPLETION_STRING_DRIVER);
        try {
            assertAsyncCompletionFailure(compiled, "async_arrays",
                arrayCompletionResponder(
                    oracleDeepStringArrayCompletion(DEEP_HOST_SUBTREE_LEVELS)),
                "test_drop_string_array",
                asyncElementTuple(ARRAY_COMPLETION_STRING_DRIVER, 4, 1, "string",
                    "int"),
                luaDeepStringArrayHost(DEEP_HOST_SUBTREE_LEVELS),
                jvmDeepStringArrayHost(DEEP_HOST_SUBTREE_LEVELS),
                "the deep later-subtree string[] completion");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /**
     * The oracle's deep later-subtree completion graph: the same early
     * wrong-kind element followed by the same deep acyclic chain of
     * production array carriers the two hosts complete with, built
     * iteratively so the harness itself never depends on the stack. The
     * oracle's lazy boundary view converts only the element the declared
     * descriptor inspects, so the deep tail is a graph fact shared by all
     * three consumers without a conversion walk.
     */
    private static SemanticOracle.Value oracleDeepStringArrayCompletion(int levels) {
        SemanticOracle.Value node = new SemanticOracle.Value.ArrayValue(
            new ArrayList<>(), RuntimeDescriptor.Int.INSTANCE, false);
        for (int i = 0; i < levels; i++) {
            List<SemanticOracle.Value> elements = new ArrayList<>();
            elements.add(node);
            node = new SemanticOracle.Value.ArrayValue(elements,
                RuntimeDescriptor.parseCanonicalText("[int]"), false);
        }
        List<SemanticOracle.Value> elements = new ArrayList<>();
        elements.add(new SemanticOracle.Value.IntValue(7L));
        elements.add(node);
        return new SemanticOracle.Value.ArrayValue(elements,
            RuntimeDescriptor.String.INSTANCE, false);
    }

    /**
     * The deep later-subtree LuaJIT host: the completed value's first element
     * is the wrong-kind number and its second element is a chain of
     * production array carriers {@code levels} deep, built iteratively so the
     * host fixture itself never depends on the stack.
     */
    private static String luaDeepStringArrayHost(int levels) {
        return """
            local rt = require("deal.runtime")

            local node = {__a = true, __n = 0}
            for i = 1, %d do
              node = {__a = true, __n = 1, [1] = node}
            end

            local function completion()
              return {__a = true, __n = 2, [1] = 7, [2] = node}
            end

            return {
              fetchInts = function() return rt.async_start(completion) end,
              fetchNullableInts = function() return rt.async_start(completion) end,
              fetchNullableElements = function() return rt.async_start(completion) end,
              fetchNestedInts = function() return rt.async_start(completion) end,
              fetchNestedStrings = function() return rt.async_start(completion) end,
              fetchStrings = function() return rt.async_start(completion) end,
              fetchNumbers = function() return rt.async_start(completion) end,
            }
            """.formatted(levels);
    }

    /**
     * The deep later-subtree JVM host: the completed future's first element
     * is the wrong-kind boxed int and its second element is a chain of
     * production array carriers {@code levels} deep, built iteratively so the
     * host fixture itself never depends on the stack.
     */
    private static String jvmDeepStringArrayHost(int levels) {
        return """
            import java.util.concurrent.CompletableFuture;

            final class HostAsync_arrays {
              private static deal.codegen.jvm.JvmRuntime.Array array(
                  java.lang.Object... elements) {
                deal.codegen.jvm.JvmRuntime.Array array =
                    new deal.codegen.jvm.JvmRuntime.Array(elements.length);
                for (java.lang.Object element : elements) {
                  array.elements.add(element);
                }
                return array;
              }

              private static deal.codegen.jvm.JvmRuntime.Array deep(int levels) {
                deal.codegen.jvm.JvmRuntime.Array node =
                    new deal.codegen.jvm.JvmRuntime.Array(0);
                for (int i = 0; i < levels; i++) {
                  node = array(node);
                }
                return node;
              }

              public static Object fetchInts() {
                return CompletableFuture.completedFuture(completion(%d));
              }
              public static Object fetchNullableInts() {
                return CompletableFuture.completedFuture(completion(%d));
              }
              public static Object fetchNullableElements() {
                return CompletableFuture.completedFuture(completion(%d));
              }
              public static Object fetchNestedInts() {
                return CompletableFuture.completedFuture(completion(%d));
              }
              public static Object fetchNestedStrings() {
                return CompletableFuture.completedFuture(completion(%d));
              }
              public static Object fetchStrings() {
                return CompletableFuture.completedFuture(completion(%d));
              }
              public static Object fetchNumbers() {
                return CompletableFuture.completedFuture(completion(%d));
              }

              private static deal.codegen.jvm.JvmRuntime.Array completion(
                  int levels) {
                return array(java.lang.Integer.valueOf(7), deep(levels));
              }
            }
            """.formatted(levels, levels, levels, levels, levels, levels, levels);
    }

    /**
     * The cyclic later-element string[] completion: the completed array's
     * first element is the wrong-kind boxed int and its second element is the
     * array itself. The canonical first-failing-element rule must decide the
     * tuple before any conversion reaches the later cyclic element, and no
     * consumer may traverse the cycle: the oracle's lazily converted boundary
     * view and both production hosts render the identical complete E8003
     * element-1 tuple at the await origin.
     */
    private static void driveHostCyclicStringArrayCompletion() throws Exception {
        Compiled compiled = compileArrayCompletion("test_drop_string_array",
            ARRAY_COMPLETION_STRING_DRIVER);
        try {
            assertAsyncCompletionFailure(compiled, "async_arrays",
                arrayCompletionResponder(oracleCyclicStringArrayCompletion()),
                "test_drop_string_array",
                asyncElementTuple(ARRAY_COMPLETION_STRING_DRIVER, 4, 1, "string",
                    "int"),
                luaCyclicStringArrayHost(), jvmCyclicStringArrayHost(),
                "the cyclic later-element string[] completion");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /**
     * The oracle's cyclic later-element completion: the two-element
     * production array whose first element is the wrong-kind boxed int and
     * whose second element is the array itself. The value is written through
     * the mutable element list the oracle's own arrays carry, so one array
     * graph is shared by the drive.
     */
    private static SemanticOracle.Value oracleCyclicStringArrayCompletion() {
        List<SemanticOracle.Value> elements = new ArrayList<>();
        SemanticOracle.Value cyclic = new SemanticOracle.Value.ArrayValue(elements,
            RuntimeDescriptor.String.INSTANCE, false);
        elements.add(new SemanticOracle.Value.IntValue(7L));
        elements.add(cyclic);
        return cyclic;
    }

    /**
     * The cyclic later-element LuaJIT host: the completed value is the
     * two-element production array whose second element is the array itself,
     * so the crossing is the only place the cycle could be walked.
     */
    private static String luaCyclicStringArrayHost() {
        return """
            local rt = require("deal.runtime")

            local cyclic = {__a = true, __n = 2, [1] = 7}
            cyclic[2] = cyclic

            return {
              fetchInts = function()
                return rt.async_start(function() return cyclic end)
              end,
              fetchNullableInts = function()
                return rt.async_start(function() return cyclic end)
              end,
              fetchNullableElements = function()
                return rt.async_start(function() return cyclic end)
              end,
              fetchNestedInts = function()
                return rt.async_start(function() return cyclic end)
              end,
              fetchNestedStrings = function()
                return rt.async_start(function() return cyclic end)
              end,
              fetchStrings = function()
                return rt.async_start(function() return cyclic end)
              end,
              fetchNumbers = function()
                return rt.async_start(function() return cyclic end)
              end,
            }
            """;
    }

    /**
     * The cyclic later-element JVM host: the completed future carries the
     * two-element production array whose second element is the array itself.
     */
    private static String jvmCyclicStringArrayHost() {
        return """
            import java.util.concurrent.CompletableFuture;

            final class HostAsync_arrays {
              private static final deal.codegen.jvm.JvmRuntime.Array CYCLIC =
                  cyclic();

              private static deal.codegen.jvm.JvmRuntime.Array cyclic() {
                deal.codegen.jvm.JvmRuntime.Array array =
                    new deal.codegen.jvm.JvmRuntime.Array(2);
                array.elements.add(java.lang.Integer.valueOf(7));
                array.elements.add(array);
                return array;
              }

              public static Object fetchInts() {
                return CompletableFuture.completedFuture(CYCLIC);
              }
              public static Object fetchNullableInts() {
                return CompletableFuture.completedFuture(CYCLIC);
              }
              public static Object fetchNullableElements() {
                return CompletableFuture.completedFuture(CYCLIC);
              }
              public static Object fetchNestedInts() {
                return CompletableFuture.completedFuture(CYCLIC);
              }
              public static Object fetchNestedStrings() {
                return CompletableFuture.completedFuture(CYCLIC);
              }
              public static Object fetchStrings() {
                return CompletableFuture.completedFuture(CYCLIC);
              }
              public static Object fetchNumbers() {
                return CompletableFuture.completedFuture(CYCLIC);
              }
            }
            """;
    }

    /**
     * The cyclic wrong-kind-element string[] completion: the completed array's
     * only element is itself a reference-cyclic array. The element's own
     * carrier kind (array) decides the element arm before its contents are
     * inspected, so the cyclic interior must not be traversed and all three
     * consumers render the identical complete E8003 element-1 tuple at the
     * await origin.
     */
    private static void driveHostCyclicWrongKindArrayCompletion() throws Exception {
        Compiled compiled = compileArrayCompletion("test_drop_string_array",
            ARRAY_COMPLETION_STRING_DRIVER);
        try {
            assertAsyncCompletionFailure(compiled, "async_arrays",
                arrayCompletionResponder(
                    oracleCyclicWrongKindArrayCompletion()),
                "test_drop_string_array",
                asyncElementTuple(ARRAY_COMPLETION_STRING_DRIVER, 4, 1, "string",
                    "array"),
                luaCyclicWrongKindArrayHost(), jvmCyclicWrongKindArrayHost(),
                "the cyclic wrong-kind-element string[] completion");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /**
     * The oracle's cyclic wrong-kind-element completion: the declared string
     * array whose only element is a self-referential array.
     */
    private static SemanticOracle.Value oracleCyclicWrongKindArrayCompletion() {
        List<SemanticOracle.Value> innerElements = new ArrayList<>();
        SemanticOracle.Value inner = new SemanticOracle.Value.ArrayValue(
            innerElements, RuntimeDescriptor.String.INSTANCE, false);
        innerElements.add(inner);
        List<SemanticOracle.Value> elements = new ArrayList<>();
        elements.add(inner);
        return new SemanticOracle.Value.ArrayValue(elements,
            RuntimeDescriptor.String.INSTANCE, false);
    }

    /**
     * The cyclic wrong-kind-element LuaJIT host: the completed value's only
     * element is a self-referential production array, so a conversion that
     * descended into the wrong-kind element would recurse forever instead of
     * rendering the element arm.
     */
    private static String luaCyclicWrongKindArrayHost() {
        return """
            local rt = require("deal.runtime")

            local inner = {__a = true, __n = 1}
            inner[1] = inner
            local completion = {__a = true, __n = 1, [1] = inner}

            return {
              fetchInts = function()
                return rt.async_start(function() return completion end)
              end,
              fetchNullableInts = function()
                return rt.async_start(function() return completion end)
              end,
              fetchNullableElements = function()
                return rt.async_start(function() return completion end)
              end,
              fetchNestedInts = function()
                return rt.async_start(function() return completion end)
              end,
              fetchNestedStrings = function()
                return rt.async_start(function() return completion end)
              end,
              fetchStrings = function()
                return rt.async_start(function() return completion end)
              end,
              fetchNumbers = function()
                return rt.async_start(function() return completion end)
              end,
            }
            """;
    }

    /**
     * The cyclic wrong-kind-element JVM host: the completed future's only
     * element is a self-referential production array.
     */
    private static String jvmCyclicWrongKindArrayHost() {
        return """
            import java.util.concurrent.CompletableFuture;

            final class HostAsync_arrays {
              private static final deal.codegen.jvm.JvmRuntime.Array COMPLETION =
                  completion();

              private static deal.codegen.jvm.JvmRuntime.Array completion() {
                deal.codegen.jvm.JvmRuntime.Array inner =
                    new deal.codegen.jvm.JvmRuntime.Array(1);
                inner.elements.add(inner);
                deal.codegen.jvm.JvmRuntime.Array outer =
                    new deal.codegen.jvm.JvmRuntime.Array(1);
                outer.elements.add(inner);
                return outer;
              }

              public static Object fetchInts() {
                return CompletableFuture.completedFuture(COMPLETION);
              }
              public static Object fetchNullableInts() {
                return CompletableFuture.completedFuture(COMPLETION);
              }
              public static Object fetchNullableElements() {
                return CompletableFuture.completedFuture(COMPLETION);
              }
              public static Object fetchNestedInts() {
                return CompletableFuture.completedFuture(COMPLETION);
              }
              public static Object fetchNestedStrings() {
                return CompletableFuture.completedFuture(COMPLETION);
              }
              public static Object fetchStrings() {
                return CompletableFuture.completedFuture(COMPLETION);
              }
              public static Object fetchNumbers() {
                return CompletableFuture.completedFuture(COMPLETION);
              }
            }
            """;
    }

    /**
     * The out-of-range long int[] completion: the completed array's element is
     * a raw host long outside signed32. The crossing's pinned int ladder
     * rejects it through the shared signed32-range arm, wrapped by the element
     * arm's own render; the failing carrier's own token is the number
     * carrier's (the closed int carrier is signed32), so the oracle's
     * NumValue model and both production artifacts render the identical
     * complete E8003 tuple at the await origin and the host's carrier is never
     * settled.
     */
    private static void driveHostRangeElementIntArrayCompletion() throws Exception {
        Compiled compiled = compileArrayCompletion("test_use_int_array",
            ARRAY_COMPLETION_INT_DRIVER);
        try {
            assertAsyncCompletionFailure(compiled, "async_arrays",
                arrayCompletionResponder(new SemanticOracle.Value.ArrayValue(
                    new ArrayList<>(List.of(
                        new SemanticOracle.Value.NumValue(2147483648.0))),
                    RuntimeDescriptor.Int.INSTANCE, false)),
                "test_use_int_array",
                asyncElementTuple(ARRAY_COMPLETION_INT_DRIVER, 4, 1, "int",
                    "number"),
                luaAsyncArrayHost("{__a = true, __n = 1, [1] = 2147483648}"),
                jvmAsyncArrayHost(
                    "array(java.lang.Long.valueOf(2147483648L))"),
                "the out-of-range long int[] completion");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /**
     * The repeated-completion declaration of the shared-array drive (no
     * corpus or host-fixture membership change): one mandatory int array
     * operation the driver awaits twice.
     */
    private static final String SHARED_ARRAY_COMPLETION_DECLARATION = """
        export async function fetchSharedInts(): int[];
        """;

    /**
     * The repeated-completion probe: two awaits of one host operation whose
     * completed future carries the same static production array. The
     * crossing must preserve that array's identity — array equality is
     * reference identity — so the two awaited bindings are one array, and a
     * write through one alias must stay visible through the other; a copied
     * or per-crossing rebuilt array fails the probe before it can return.
     */
    private static final String SHARED_ARRAY_COMPLETION_DRIVER = """
        import * as host from "host/async_arrays"

        export async function test_reuse_int_array(): int {
          let xs: int[] = await host.fetchSharedInts()
          let ys: int[] = await host.fetchSharedInts()
          if (xs !== ys) {
            throw { code: "PROBE_IDENTITY", message: "the same completed array did not keep its identity" }
          }
          xs[0] = 8
          let v: int = ys[0]
          if (v !== 8) {
            throw { code: "PROBE_ALIAS", message: "the alias-observed write was lost" }
          }
          return v
        }

        export function main(): null {
          return null
        }
        """;

    /**
     * The shared-array drive's LuaJIT host: both calls complete with the one
     * module-level table, so the crossing is the only place a copy could
     * enter.
     */
    private static final String LUA_SHARED_ARRAY_HOST = """
        local rt = require("deal.runtime")

        local shared = {__a = true, __n = 1, [1] = 7}

        return {
          fetchSharedInts = function()
            return rt.async_start(function() return shared end)
          end,
        }
        """;

    /**
     * The shared-array drive's JVM host: both calls complete with the one
     * static production array, so the crossing is the only place a copy could
     * enter.
     */
    private static final String JVM_SHARED_ARRAY_HOST = """
        import java.util.concurrent.CompletableFuture;

        final class HostAsync_arrays {
          private static final deal.codegen.jvm.JvmRuntime.Array SHARED =
              array(java.lang.Integer.valueOf(7));

          private static deal.codegen.jvm.JvmRuntime.Array array(
              java.lang.Object... elements) {
            deal.codegen.jvm.JvmRuntime.Array array =
                new deal.codegen.jvm.JvmRuntime.Array(elements.length);
            for (java.lang.Object element : elements) {
              array.elements.add(element);
            }
            return array;
          }

          public static Object fetchSharedInts() {
            return CompletableFuture.completedFuture(SHARED);
          }
        }
        """;

    /**
     * The repeated shared int[] completion on all three consumers: the host
     * completes two awaits of one operation with the identical production
     * array, the awaited bindings must compare equal by reference, and the
     * write through the first alias must be observed through the second. The
     * oracle's responder returns the one Value instance, the LuaJIT host the
     * one table, and the JVM host the one static array — so any per-crossing
     * rebuild fails the drives' own in-language probes and cannot print OK.
     */
    private static void driveHostSharedArrayCompletion() throws Exception {
        Case spec = new Case("host-abi/host-async-shape-bad",
            "test_reuse_int_array", Entry.ASYNC, List.of(), "async_arrays", null);
        Compiled compiled = compile(spec, SHARED_ARRAY_COMPLETION_DRIVER,
            Map.of("async_arrays", SHARED_ARRAY_COMPLETION_DECLARATION));
        try {
            assertAsyncArrayCompletionSuccess(compiled, "test_reuse_int_array",
                "int:8", arrayCompletionResponder(oracleIntArrayElement(7L)),
                LUA_SHARED_ARRAY_HOST, JVM_SHARED_ARRAY_HOST,
                "the repeated shared int[] completion");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    // =========================================================================
    // The out-of-range host array completions (ISSUE-0795 acceptance remediation)
    // =========================================================================

    /**
     * The out-of-range host array completions (ISSUE-0795 acceptance
     * remediation): a completed array whose element is a raw host numeric
     * carrier outside the closed signed32 int range is not the production
     * int carrier, so the crossing's signed32 range step — the step the
     * crossing already applies at a top-level int position — must apply at
     * every checked int element position recursively: the direct, the
     * nullable-element, and the nested positions included. The oracle's
     * boundary view classifies the raw out-of-range host long as its number
     * view (a closed signed32 INT view cannot carry it, and the oracle's own
     * signed32-only INT factory keeps its invariant), exactly like both
     * production consumers classify the same raw value {@code number}, so
     * the enclosing array fails through the canonical element arm at the
     * await origin on all three consumers, the first failing element decides
     * before any settlement commits, and a rejected crossing leaves the
     * host's own carrier untouched.
     */
    private static void testHostOutOfRangeArrayCompletions() throws Exception {
        System.out.println("-- the out-of-range host array completions: the "
            + "recursive signed32 step at int element positions fails through the "
            + "canonical element arm on all three consumers --");
        JvmRuntime.Array direct = new JvmRuntime.Array(1);
        direct.elements.add(Long.valueOf(2147483648L));
        try {
            JvmRuntime.hostCompletion("array(int)", "array", direct);
            check(false, "the crossing rejects the out-of-range Long at a direct "
                + "int element position");
        } catch (JvmRuntime.DealError error) {
            check("E8003".equals(error.code)
                    && "array element 1 type mismatch".equals(error.msg)
                    && "int".equals(error.expected)
                    && "number".equals(error.actual),
                "the crossing renders the canonical element arm for the "
                    + "out-of-range Long at a direct int element position: "
                    + error.code + " " + error.msg + " expected " + error.expected
                    + " actual " + error.actual);
        }
        check(Long.valueOf(2147483648L).equals(direct.elements.get(0)),
            "the rejected direct crossing commits nothing: the host's own "
                + "carrier keeps its out-of-range Long element");
        JvmRuntime.Array nullableElement = new JvmRuntime.Array(1);
        nullableElement.elements.add(Long.valueOf(-2147483649L));
        try {
            JvmRuntime.hostCompletion("array(nullable(int))", "array",
                nullableElement);
            check(false, "the crossing rejects the out-of-range Long at a "
                + "nullable int element position");
        } catch (JvmRuntime.DealError error) {
            check("E8003".equals(error.code)
                    && "array element 1 type mismatch".equals(error.msg)
                    && "?int".equals(error.expected)
                    && "number".equals(error.actual),
                "the crossing renders the canonical element arm for the "
                    + "out-of-range Long at a nullable int element position: "
                    + error.code + " " + error.msg + " expected " + error.expected
                    + " actual " + error.actual);
        }
        check(Long.valueOf(-2147483649L).equals(nullableElement.elements.get(0)),
            "the rejected nullable-element crossing commits nothing: the host's "
                + "own carrier keeps its out-of-range Long element");
        JvmRuntime.Array nestedInner = new JvmRuntime.Array(1);
        nestedInner.elements.add(Long.valueOf(2147483648L));
        JvmRuntime.Array nestedOuter = new JvmRuntime.Array(1);
        nestedOuter.elements.add(nestedInner);
        try {
            JvmRuntime.hostCompletion("array(array(int))", "array", nestedOuter);
            check(false, "the crossing rejects the out-of-range Long at a nested "
                + "int element position");
        } catch (JvmRuntime.DealError error) {
            check("E8003".equals(error.code)
                    && "array element 1 type mismatch".equals(error.msg)
                    && "[int]".equals(error.expected)
                    && "array".equals(error.actual),
                "the crossing renders the canonical outer element arm for the "
                    + "out-of-range Long at a nested int element position: "
                    + error.code + " " + error.msg + " expected " + error.expected
                    + " actual " + error.actual);
        }
        check(nestedOuter.elements.get(0) == nestedInner
                && Long.valueOf(2147483648L).equals(nestedInner.elements.get(0)),
            "the rejected nested crossing commits nothing: the inner carrier "
                + "stays the same array with its out-of-range Long element");
        JvmRuntime.Array nullableArray = new JvmRuntime.Array(1);
        nullableArray.elements.add(Long.valueOf(2147483648L));
        try {
            JvmRuntime.hostCompletion("nullable(array(int))", "array",
                nullableArray);
            check(false, "the crossing rejects the out-of-range Long at an int "
                + "element position of a nullable array completion");
        } catch (JvmRuntime.DealError error) {
            check("E8003".equals(error.code) && "int".equals(error.expected)
                    && "number".equals(error.actual),
                "the crossing renders the canonical element arm behind the "
                    + "nullable array completion: " + error.code + " " + error.msg);
        }
        JvmRuntime.Array ordered = new JvmRuntime.Array(2);
        ordered.elements.add(Integer.valueOf(7));
        ordered.elements.add(Long.valueOf(2147483648L));
        try {
            JvmRuntime.hostCompletion("array(string)", "array", ordered);
            check(false, "the crossing rejects the early wrong-kind element of "
                + "the ordered carrier");
        } catch (JvmRuntime.DealError error) {
            check("E8003".equals(error.code)
                    && "array element 1 type mismatch".equals(error.msg)
                    && "string".equals(error.expected)
                    && "int".equals(error.actual),
                "the first failing element decides before the later out-of-range "
                    + "Long is inspected: " + error.code + " " + error.msg
                    + " expected " + error.expected + " actual " + error.actual);
        }
        check(Integer.valueOf(7).equals(ordered.elements.get(0))
                && Long.valueOf(2147483648L).equals(ordered.elements.get(1)),
            "the rejected ordered crossing commits nothing: both the early "
                + "wrong-kind element and the later out-of-range Long stay raw");
        JvmRuntime.Array laterRange = new JvmRuntime.Array(2);
        laterRange.elements.add(Integer.valueOf(7));
        laterRange.elements.add(Long.valueOf(2147483649L));
        try {
            JvmRuntime.hostCompletion("array(int)", "array", laterRange);
            check(false, "the crossing rejects the out-of-range Long at the "
                + "second int element position");
        } catch (JvmRuntime.DealError error) {
            check("E8003".equals(error.code)
                    && "array element 2 type mismatch".equals(error.msg)
                    && "int".equals(error.expected)
                    && "number".equals(error.actual),
                "the crossing renders the element arm of the second position "
                    + "after admitting the first: " + error.code + " " + error.msg
                    + " expected " + error.expected + " actual " + error.actual);
        }
        check(Integer.valueOf(7).equals(laterRange.elements.get(0))
                && Long.valueOf(2147483649L).equals(laterRange.elements.get(1)),
            "the rejected crossing commits no partial admission: the earlier "
                + "admitted boxed int element stays the raw Integer carrier");
        JvmRuntime.Array numberCarrier = new JvmRuntime.Array(1);
        numberCarrier.elements.add(Long.valueOf(2147483648L));
        Object admittedNumber = JvmRuntime.hostCompletion("array(number)", "array",
            numberCarrier);
        check(admittedNumber == numberCarrier
                && Long.valueOf(2147483648L).equals(numberCarrier.elements.get(0)),
            "a declared number element position admits the out-of-range Long "
                + "unchanged (the number carrier keeps its own variant)");
        try {
            JvmRuntime.hostCompletion("nullable(int)", "nullable:int",
                Long.valueOf(2147483648L));
            check(false, "the crossing rejects the out-of-range Long at the "
                + "top-level nullable int position");
        } catch (JvmRuntime.DealError error) {
            check("E8004".equals(error.code)
                    && "int out of safe range".equals(error.msg),
                "the crossing's top-level range step and its element-position "
                    + "step render the same closed range arm: " + error.code + " "
                    + error.msg);
        }
        safely("the out-of-range int[] host completion",
            CanonicalProjectionParityTest::driveHostOutOfRangeIntArrayCompletion);
        safely("the out-of-range nullable-element array host completion",
            CanonicalProjectionParityTest::driveHostOutOfRangeNullableElementCompletion);
        safely("the out-of-range nested int[][] host completion",
            CanonicalProjectionParityTest::driveHostOutOfRangeNestedIntArrayCompletion);
        safely("the out-of-range later-element string[] host completion",
            CanonicalProjectionParityTest::driveHostOutOfRangeLaterElementCompletion);
    }

    /** The discarded-await out-of-range int-array probe. */
    private static final String ARRAY_COMPLETION_DROP_INT_DRIVER = """
        import * as host from "host/async_arrays"

        export async function test_drop_int_array(): null {
          await host.fetchInts()
          return null
        }

        export function main(): null {
          return null
        }
        """;

    /** The discarded-await out-of-range nullable-element array probe. */
    private static final String ARRAY_COMPLETION_DROP_NULLABLE_ELEMENT_DRIVER = """
        import * as host from "host/async_arrays"

        export async function test_drop_nullable_elements(): null {
          await host.fetchNullableElements()
          return null
        }

        export function main(): null {
          return null
        }
        """;

    /** The discarded-await out-of-range nested-int-array probe. */
    private static final String ARRAY_COMPLETION_DROP_NESTED_INT_DRIVER = """
        import * as host from "host/async_arrays"

        export async function test_drop_nested_int_array(): null {
          await host.fetchNestedInts()
          return null
        }

        export function main(): null {
          return null
        }
        """;

    /**
     * The discarded-await later-out-of-range string-array probe: the awaited
     * result is discarded and the canonical first failing element must decide
     * before the later out-of-range raw carrier is inspected.
     */
    private static final String ARRAY_COMPLETION_DROP_ORDER_DRIVER = """
        import * as host from "host/async_arrays"

        export async function test_drop_ordered_strings(): null {
          await host.fetchStrings()
          return null
        }

        export function main(): null {
          return null
        }
        """;

    /**
     * The oracle's one-element out-of-range raw host-long int array
     * completion value: the JVM host's completed carrier is the unrestricted
     * raw {@code long} the public {@link SemanticOracle.Value.IntValue} seam
     * admits, and the oracle's boundary view classifies an integral raw
     * carrier outside signed32 as the number view both production consumers
     * project for the same raw value.
     */
    private static SemanticOracle.Value oracleOutOfRangeIntArrayElement(long value) {
        return new SemanticOracle.Value.ArrayValue(
            new ArrayList<>(List.of(new SemanticOracle.Value.IntValue(value))),
            RuntimeDescriptor.Int.INSTANCE, false);
    }

    /**
     * The oracle's nested out-of-range raw host-long int array completion
     * value: the number view at the nested int element position.
     */
    private static SemanticOracle.Value oracleOutOfRangeNestedIntArrayElement(
            long value) {
        return new SemanticOracle.Value.ArrayValue(
            new ArrayList<>(List.of(new SemanticOracle.Value.ArrayValue(
                new ArrayList<>(List.of(new SemanticOracle.Value.IntValue(value))),
                RuntimeDescriptor.Int.INSTANCE, false))),
            RuntimeDescriptor.parseCanonicalText("[int]"), false);
    }

    /**
     * The oracle's early wrong-kind int followed by the out-of-range raw
     * host-long carrier: the first failing element decides before the later
     * number view is inspected.
     */
    private static SemanticOracle.Value oracleOrderedStringArrayElement(
            long laterValue) {
        return new SemanticOracle.Value.ArrayValue(
            new ArrayList<>(List.of(new SemanticOracle.Value.IntValue(7L),
                new SemanticOracle.Value.IntValue(laterValue))),
            RuntimeDescriptor.String.INSTANCE, false);
    }

    /**
     * The out-of-range Long at a direct int[] completion on all three
     * consumers: the element arm's complete tuple at the discarded await.
     */
    private static void driveHostOutOfRangeIntArrayCompletion() throws Exception {
        Compiled compiled = compileArrayCompletion("test_drop_int_array",
            ARRAY_COMPLETION_DROP_INT_DRIVER);
        try {
            assertAsyncCompletionFailure(compiled, "async_arrays",
                arrayCompletionResponder(
                    oracleOutOfRangeIntArrayElement(2147483648L)),
                "test_drop_int_array",
                asyncElementTuple(ARRAY_COMPLETION_DROP_INT_DRIVER, 4, 1, "int",
                    "number"),
                luaAsyncArrayHost("{__a = true, __n = 1, [1] = 2147483648}"),
                jvmAsyncArrayHost("array(java.lang.Long.valueOf(2147483648L))"),
                "the out-of-range Long int[] completion");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /**
     * The out-of-range Long at an (int | null)[] completion on all three
     * consumers: the nullable element position applies the same recursive
     * range step and renders the element's own canonical tuple.
     */
    private static void driveHostOutOfRangeNullableElementCompletion()
            throws Exception {
        Compiled compiled = compileArrayCompletion("test_drop_nullable_elements",
            ARRAY_COMPLETION_DROP_NULLABLE_ELEMENT_DRIVER);
        try {
            assertAsyncCompletionFailure(compiled, "async_arrays",
                arrayCompletionResponder(
                    oracleOutOfRangeIntArrayElement(-2147483649L)),
                "test_drop_nullable_elements",
                asyncElementTuple(ARRAY_COMPLETION_DROP_NULLABLE_ELEMENT_DRIVER, 4, 1,
                    "?int", "number"),
                luaAsyncArrayHost("{__a = true, __n = 1, [1] = -2147483649}"),
                jvmAsyncArrayHost("array(java.lang.Long.valueOf(-2147483649L))"),
                "the out-of-range Long (int | null)[] completion");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /**
     * The out-of-range Long at an int[][] completion on all three consumers:
     * the nested element position applies the same recursive range step and
     * the outermost element arm renders the complete tuple.
     */
    private static void driveHostOutOfRangeNestedIntArrayCompletion()
            throws Exception {
        Compiled compiled = compileArrayCompletion("test_drop_nested_int_array",
            ARRAY_COMPLETION_DROP_NESTED_INT_DRIVER);
        try {
            assertAsyncCompletionFailure(compiled, "async_arrays",
                arrayCompletionResponder(
                    oracleOutOfRangeNestedIntArrayElement(2147483648L)),
                "test_drop_nested_int_array",
                asyncElementTuple(ARRAY_COMPLETION_DROP_NESTED_INT_DRIVER, 4, 1,
                    "[int]", "array"),
                luaAsyncArrayHost("{__a = true, __n = 1, [1] = {__a = true, "
                    + "__n = 1, [1] = 2147483648}}"),
                jvmAsyncArrayHost("array(array(java.lang.Long.valueOf(2147483648L)))"),
                "the out-of-range Long int[][] completion");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /**
     * The early wrong-kind element followed by the out-of-range Long at a
     * string[] completion on all three consumers: the canonical
     * first-failing-element rule decides the tuple before the later
     * out-of-range raw carrier is inspected.
     */
    private static void driveHostOutOfRangeLaterElementCompletion()
            throws Exception {
        Compiled compiled = compileArrayCompletion("test_drop_ordered_strings",
            ARRAY_COMPLETION_DROP_ORDER_DRIVER);
        try {
            assertAsyncCompletionFailure(compiled, "async_arrays",
                arrayCompletionResponder(
                    oracleOrderedStringArrayElement(2147483648L)),
                "test_drop_ordered_strings",
                asyncElementTuple(ARRAY_COMPLETION_DROP_ORDER_DRIVER, 4, 1, "string",
                    "int"),
                luaAsyncArrayHost(
                    "{__a = true, __n = 2, [1] = 7, [2] = 2147483648}"),
                jvmAsyncArrayHost("array(java.lang.Integer.valueOf(7), "
                    + "java.lang.Long.valueOf(2147483648L))"),
                "the early wrong-kind element before the out-of-range Long "
                    + "string[] completion");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /** The element arm's own tuple (E8003) at one await origin. */
    private static Tuple asyncElementTuple(String driver, int line,
            int oneBasedIndex, String expected, String actual) {
        BoundaryFailure arm = FailureContractRegistry.render(
            FailureArmId.ARRAY_ELEMENT_KIND,
            Map.of("oneBasedIndex", Integer.toString(oneBasedIndex)), expected,
            actual, null);
        return new Tuple(arm.code().name(), arm.message(), "app.deal", line,
            awaitColumn(driver, line), arm.expected(), arm.actual());
    }

    /**
     * One admitted numeric host completion on all three consumers: the
     * oracle's async entry completes successfully (the body's own in-language
     * use consumed the settled value), and both production artifacts complete
     * without a failure event.
     */
    private static void assertAsyncCompletionSuccess(Compiled compiled, String stem,
            SemanticOracle.HostResponder responder, String export, String luaHost,
            String jvmHost, String what) throws Exception {
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.invokeAsyncEntry(
            compiled.project(), compiled.tables(), responder,
            compiled.checkedProject().entryModule(), export, List.of());
        check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
            what + " (oracle): the settled completion is admitted and consumed: "
                + run.terminal());
        String lua = luaArtifactOutput(compiled, Map.of(stem, luaHost));
        check(lua.contains("OK") && !lua.contains("ERR|"), what + " (luajit): the "
            + "production artifact completes without a failure event: " + lua);
        String jvm = jvmArtifactOutput(compiled, jvmProbe(
            JvmBackend.classNameFor(compiled.checkedProject().entryModule().path()),
            compiled.checkedProject().entryModule().path(), ""),
            Map.of(stem, jvmHost));
        check(jvm != null && jvm.contains("OK") && !jvm.contains("ERR|"), what
            + " (jvm): the production artifact completes without a failure event: "
            + jvm);
    }

    /**
     * One rejected numeric host completion on all three consumers: the
     * complete tuple is the completion row's own arm at the await expression,
     * byte-exact and identical on the oracle, the LuaJIT artifact, and the JVM
     * artifact.
     */
    private static void assertAsyncCompletionFailure(Compiled compiled, String stem,
            SemanticOracle.HostResponder responder, String export, Tuple expected,
            String luaHost, String jvmHost, String what) throws Exception {
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.invokeAsyncEntry(
            compiled.project(), compiled.tables(), responder,
            compiled.checkedProject().entryModule(), export, List.of());
        Tuple oracle = failureTuple(compiled, run);
        checkEq(expected, oracle, what + " (oracle): the complete tuple");
        Tuple lua = luaArtifactWithHost(compiled, stem, luaHost);
        checkEq(expected, lua, what + " (luajit): the complete tuple");
        Tuple jvm = jvmArtifactWithHost(compiled, stem, jvmHost);
        checkEq(expected, jvm, what + " (jvm): the complete tuple");
        if (oracle != null && lua != null) {
            checkEq(null, firstDifferingField(oracle, lua), what
                + ": the oracle and the LuaJIT artifact render the identical tuple");
        }
        if (oracle != null && jvm != null) {
            checkEq(null, firstDifferingField(oracle, jvm), what
                + ": the oracle and the JVM artifact render the identical tuple");
        }
    }

    /** The completion row's signed32-range tuple (E8004) at one await origin. */
    private static Tuple asyncRangeTuple(String driver, int line) {
        BoundaryFailure arm = FailureContractRegistry.render(
            FailureArmId.INT32_RANGE, Map.of(), null, null, null);
        return new Tuple(arm.code().name(), arm.message(), "app.deal", line,
            awaitColumn(driver, line), arm.expected(), arm.actual());
    }

    /** The completion row's refinement tuple (E8001) at one await origin. */
    private static Tuple asyncRefinementTuple(String driver, int line, String token) {
        BoundaryFailure arm = FailureContractRegistry.render(
            FailureArmId.ASYNC_COMPLETION_REFINEMENT,
            Map.of("expected", "int", "actual", token), "int", token, null);
        return new Tuple(arm.code().name(), arm.message(), "app.deal", line,
            awaitColumn(driver, line), arm.expected(), arm.actual());
    }

    /** The completion cell's kind tuple (E8001) at one await origin. */
    private static Tuple asyncKindTuple(String driver, int line, String expected,
            String actual) {
        BoundaryFailure arm = FailureContractRegistry.render(
            FailureArmId.ASYNC_COMPLETION_KIND, Map.of("expected", expected),
            expected, actual, null);
        return new Tuple(arm.code().name(), arm.message(), "app.deal", line,
            awaitColumn(driver, line), arm.expected(), arm.actual());
    }

    /** The one-based column of the {@code await} expression in one driver line. */
    private static int awaitColumn(String driver, int line) {
        String text = driver.lines().toList().get(line - 1);
        return text.indexOf("await") + 1;
    }

    /** The caller-owned numeric-return project (one declaration and driver). */
    private static Compiled compileNumericReturn() throws Exception {
        Case spec = new Case("host-abi/host-async-shape-bad", "test_use_int",
            Entry.MAIN, List.of(), null, null);
        return compile(spec, NUMERIC_RETURN_DRIVER,
            Map.of("numeric_return", NUMERIC_RETURN_DECLARATION));
    }

    /**
     * The numeric-carrier drive: the admitted in-range carriers on the
     * consuming probes, then the range and refinement non-admissions on the
     * drop probes, each against the closed arm's own render.
     */
    private static void driveHostNumericReturnCarriers() throws Exception {
        Compiled compiled = compileNumericReturn();
        try {
            String stem = "numeric_return";
            // The admitted carriers: the boxed int, the signed32 long, and
            // the integral double all normalize to the declared int carrier
            // (the consuming probe compares the value in-language), and the
            // long/double/int carriers normalize to the declared number.
            assertHostSuccess(compiled, stem, numericReturnResponder(
                new SemanticOracle.Value.IntValue(7L)), "test_use_int",
                luaNumericHost("7"), jvmNumericHost("java.lang.Integer.valueOf(7)"),
                "the in-range Integer int return");
            assertHostSuccess(compiled, stem, numericReturnResponder(
                new SemanticOracle.Value.IntValue(7L)), "test_use_int",
                luaNumericHost("7"), jvmNumericHost("java.lang.Long.valueOf(7)"),
                "the in-range Long int return");
            assertHostSuccess(compiled, stem, numericReturnResponder(
                new SemanticOracle.Value.IntValue(7L)), "test_use_int",
                luaNumericHost("7"), jvmNumericHost("java.lang.Double.valueOf(7)"),
                "the in-range integral Double int return");
            assertHostSuccess(compiled, stem, numericReturnResponder(
                new SemanticOracle.Value.IntValue(7L)), "test_use_nullable_int",
                luaNumericHost("7"), jvmNumericHost("java.lang.Long.valueOf(7)"),
                "the in-range Long nullable-int return");
            check(BoundaryExecutor.check(FailurePolicyId.HOST_SYNC_RETURN,
                RuntimeDescriptor.Number.INSTANCE, BoundaryValueView.ofInt(7),
                BoundaryContext.none()) instanceof BoundaryOutcome.Pass,
                "the oracle's number cell admits the boxed-int carrier, the "
                    + "admission the emitted JVM wrapper applies");
            assertHostSuccess(compiled, stem, numericReturnResponder(
                new SemanticOracle.Value.NumValue(7.0)), "test_use_number",
                luaNumericHost("7"), jvmNumericHost("java.lang.Integer.valueOf(7)"),
                "the in-range Integer number return");
            assertHostSuccess(compiled, stem, numericReturnResponder(
                new SemanticOracle.Value.NumValue(7.0)), "test_use_number",
                luaNumericHost("7"), jvmNumericHost("java.lang.Long.valueOf(7)"),
                "the in-range Long number return");
            assertHostSuccess(compiled, stem, numericReturnResponder(
                new SemanticOracle.Value.NumValue(7.5)), "test_use_fraction",
                luaNumericHost("7.5"), jvmNumericHost("java.lang.Double.valueOf(7.5)"),
                "the in-range fractional Double number return");
            // The non-admitted int carriers: the signed32-range arm for an
            // out-of-range Long or Double at both int returns, and the
            // refinement arms for a fractional, NaN, or infinite Double,
            // each rendered as the host composite at the call origin.
            Tuple rangeInt = hostReturnCellTuple("int", FailureContractRegistry
                .arm(FailureArmId.INT32_RANGE).template(), "number", 39, 3);
            Tuple rangeNullableInt = hostReturnCellTuple("?int",
                FailureContractRegistry.arm(FailureArmId.INT32_RANGE).template(),
                "number", 44, 3);
            Tuple nanInt = hostReturnCellTuple("int", FailureContractRegistry
                .arm(FailureArmId.INT_CONVERSION_NAN).template(), "number", 39, 3);
            Tuple infinityInt = hostReturnCellTuple("int", FailureContractRegistry
                .arm(FailureArmId.INT_CONVERSION_INFINITY).template(), "number",
                39, 3);
            Tuple fractionInt = hostReturnCellTuple("int", FailureContractRegistry
                .arm(FailureArmId.INT_CONVERSION_FRACTIONAL).template(), "number",
                39, 3);
            assertHostFailure(compiled, stem, numericReturnResponder(
                new SemanticOracle.Value.NumValue(2147483648.0)), "test_drop_int",
                rangeInt, luaNumericHost("2147483648"),
                jvmNumericHost("java.lang.Long.valueOf(2147483648L)"),
                "the out-of-range Long int return");
            assertHostFailure(compiled, stem, numericReturnResponder(
                new SemanticOracle.Value.NumValue(2147483648.0)), "test_drop_int",
                rangeInt, luaNumericHost("2147483648"),
                jvmNumericHost("java.lang.Double.valueOf(2147483648.0)"),
                "the out-of-range Double int return");
            assertHostFailure(compiled, stem, numericReturnResponder(
                new SemanticOracle.Value.NumValue(1.5)), "test_drop_int",
                fractionInt, luaNumericHost("1.5"),
                jvmNumericHost("java.lang.Double.valueOf(1.5)"),
                "the fractional Double int return");
            assertHostFailure(compiled, stem, numericReturnResponder(
                new SemanticOracle.Value.NumValue(Double.NaN)), "test_drop_int",
                nanInt, luaNumericHost("0/0"),
                jvmNumericHost("java.lang.Double.valueOf(java.lang.Double.NaN)"),
                "the NaN Double int return");
            assertHostFailure(compiled, stem, numericReturnResponder(
                new SemanticOracle.Value.NumValue(Double.POSITIVE_INFINITY)),
                "test_drop_int", infinityInt, luaNumericHost("math.huge"),
                jvmNumericHost(
                    "java.lang.Double.valueOf(java.lang.Double.POSITIVE_INFINITY)"),
                "the infinite Double int return");
            assertHostFailure(compiled, stem, numericReturnResponder(
                new SemanticOracle.Value.NumValue(2147483648.0)),
                "test_drop_nullable_int", rangeNullableInt,
                luaNumericHost("2147483648"),
                jvmNumericHost("java.lang.Long.valueOf(2147483648L)"),
                "the out-of-range Long nullable-int return");
            assertHostFailure(compiled, stem, numericReturnResponder(
                new SemanticOracle.Value.NumValue(Double.NaN)),
                "test_drop_nullable_int", hostReturnCellTuple("?int",
                    FailureContractRegistry.arm(
                        FailureArmId.INT_CONVERSION_NAN).template(), "number",
                    44, 3), luaNumericHost("0/0"),
                jvmNumericHost("java.lang.Double.valueOf(java.lang.Double.NaN)"),
                "the NaN Double nullable-int return");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /** The numeric-return responder: the same value for every host call. */
    private static SemanticOracle.HostResponder numericReturnResponder(
            SemanticOracle.Value value) {
        return new SemanticOracle.HostResponder() {
            @Override
            public SyncOutcome call(ModuleId module, String export,
                    RuntimeDescriptor.Func descriptor,
                    List<SemanticOracle.Value> args) {
                return new SyncOutcome.Returned(value);
            }
        };
    }

    /** The LuaJIT numeric-return host: one plain number for every export. */
    private static String luaNumericHost(String value) {
        return """
            return {
              fetchInt = function() return %s end,
              fetchNullableInt = function() return %s end,
              fetchNumber = function() return %s end,
              fetchFraction = function() return %s end,
            }
            """.formatted(value, value, value, value);
    }

    /** The JVM numeric-return host: one boxed carrier expression for every export. */
    private static String jvmNumericHost(String carrier) {
        return """
            final class HostNumeric_return {
              public static Object fetchInt() { return %s; }
              public static Object fetchNullableInt() { return %s; }
              public static Object fetchNumber() { return %s; }
              public static Object fetchFraction() { return %s; }
            }
            """.formatted(carrier, carrier, carrier, carrier);
    }

    /**
     * Drives one probe export of the sync-presence project through the
     * oracle (under the supplied responder), the LuaJIT artifact (under the
     * supplied host implementation), and the JVM artifact, asserting each
     * captures the expected tuple and that the consumers agree.
     */
    private static void assertSyncPresenceFailure(Compiled compiled,
            SemanticOracle.HostResponder responder, String export, Tuple expected,
            String luaHost, String jvmHost, String what) throws Exception {
        assertHostFailure(compiled, "sync_presence", responder, export, expected,
            luaHost, jvmHost, what);
    }

    /**
     * Drives one probe export through the oracle (under the supplied
     * responder), the LuaJIT artifact, and the JVM artifact under the named
     * host stem, asserting that every consumer captures the expected tuple
     * and that the three tuples are identical: the present-null cell is the
     * same failure on all three, never an exempted divergence.
     */
    private static void assertHostFailure(Compiled compiled, String stem,
            SemanticOracle.HostResponder responder, String export, Tuple expected,
            String luaHost, String jvmHost, String what)
            throws Exception {
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.invokeSyncEntry(
            compiled.project(), compiled.tables(), compiled.registries(), responder,
            compiled.checkedProject().entryModule(), export, List.of());
        Tuple oracle = failureTuple(compiled, run);
        checkEq(expected, oracle, what + " (oracle): the complete tuple");
        Tuple lua = luaHostFailure(compiled, stem, luaHost, export);
        checkEq(expected, lua, what + " (luajit): the complete tuple");
        Tuple jvm = jvmHostFailure(compiled, stem, jvmHost, export);
        checkEq(expected, jvm, what + " (jvm): the complete tuple");
        if (oracle != null && lua != null) {
            checkEq(null, firstDifferingField(oracle, lua), what
                + ": the oracle and the LuaJIT artifact render the identical tuple");
        }
        if (oracle != null && jvm != null) {
            checkEq(null, firstDifferingField(oracle, jvm), what
                + ": the oracle and the JVM artifact render the identical tuple");
        }
    }

    /** One admitted-presence probe of the sync-presence project on all three consumers. */
    private static void assertSyncPresenceSuccess(Compiled compiled,
            SemanticOracle.HostResponder responder, String export, String luaHost,
            String jvmHost, String what) throws Exception {
        assertHostSuccess(compiled, "sync_presence", responder, export, luaHost,
            jvmHost, what);
    }

    /** One admitted host value on all three consumers under the named host stem. */
    private static void assertHostSuccess(Compiled compiled, String stem,
            SemanticOracle.HostResponder responder, String export, String luaHost,
            String jvmHost, String what) throws Exception {
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.invokeSyncEntry(
            compiled.project(), compiled.tables(), compiled.registries(), responder,
            compiled.checkedProject().entryModule(), export, List.of());
        check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
            what + " (oracle): the admitted presence completes: " + run.terminal());
        String lua = luaArtifactOutput(compiled, Map.of(stem, luaHost),
            luaExportProbe(compiled, export));
        check(lua.contains("OK") && !lua.contains("ERR|"), what + " (luajit): "
            + "the admitted presence completes without a failure event: " + lua);
        String jvm = jvmArtifactOutput(compiled, jvmExportProbe(compiled, export),
            Map.of(stem, jvmHost));
        check(jvm != null && jvm.contains("OK") && !jvm.contains("ERR|"), what
            + " (jvm): the admitted presence completes without a failure event: "
            + jvm);
    }

    /** The LuaJIT artifact's failure tuple of one named sync-presence probe. */
    private static Tuple luaSyncPresenceFailure(Compiled compiled, String hostSource,
            String export) throws Exception {
        return luaHostFailure(compiled, "sync_presence", hostSource, export);
    }

    /** The LuaJIT artifact's failure tuple of one probe under the named host stem. */
    private static Tuple luaHostFailure(Compiled compiled, String stem,
            String hostSource, String export) throws Exception {
        String output = luaArtifactOutput(compiled, Map.of(stem, hostSource),
            luaExportProbe(compiled, export));
        Tuple tuple = parseLuaProbe(output, compiled);
        check(tuple != null, export + " (luajit, " + stem + "): the probe captures "
            + "the failure: " + output);
        return tuple;
    }

    /** The JVM artifact's failure tuple of one named sync-presence probe. */
    private static Tuple jvmSyncPresenceFailure(Compiled compiled, String hostSource,
            String export) throws Exception {
        return jvmHostFailure(compiled, "sync_presence", hostSource, export);
    }

    /** The JVM artifact's failure tuple of one probe under the named host stem. */
    private static Tuple jvmHostFailure(Compiled compiled, String stem,
            String hostSource, String export) throws Exception {
        String output = jvmArtifactOutput(compiled, jvmExportProbe(compiled, export),
            Map.of(stem, hostSource));
        if (output == null) {
            return null;
        }
        Tuple tuple = parseJvmProbe(output, compiled);
        check(tuple != null, export + " (jvm, " + stem + "): the probe captures the "
            + "failure: " + output);
        return tuple;
    }

    /** The closed {@code HOST_SYNC_RETURN_NOTHING} tuple at one probe origin. */
    private static Tuple hostNothingTuple(String descriptor, int line, int column) {
        BoundaryFailure arm = FailureContractRegistry.render(
            FailureArmId.HOST_SYNC_RETURN_NOTHING, Map.of("expected", descriptor),
            descriptor, "nothing", null);
        return new Tuple(arm.code().name(), arm.message(), "app.deal", line, column,
            arm.expected(), arm.actual());
    }

    /** The closed {@code HOST_SYNC_RETURN_CELL} tuple at one probe origin. */
    private static Tuple hostReturnCellTuple(String descriptor, String inner,
            String actual, int line, int column) {
        BoundaryFailure arm = FailureContractRegistry.render(
            FailureArmId.HOST_SYNC_RETURN_CELL, Map.of("inner", inner), descriptor,
            actual, null);
        return new Tuple(arm.code().name(), arm.message(), "app.deal", line, column,
            arm.expected(), arm.actual());
    }

    /**
     * The LuaJIT deferred-entry probe of one named sync export: the module
     * entry runs once and the named export is invoked alone, so the
     * captured failure is that probe's own call origin.
     */
    private static String luaExportProbe(Compiled compiled, String export) {
        return """
            local function emit(e)
              if type(e) == "table" and e.__d then
                local file, line, column = nil, nil, nil
                if e.o ~= nil then
                  file, line, column = tostring(e.o):match("^(.*):(%%d+):(%%d+)$")
                end
                if file == nil and e.file ~= nil then
                  file, line, column = tostring(e.file), tostring(e.line),
                    tostring(e.column)
                end
                print("ERR|" .. tostring(e.code) .. "|" .. tostring(e.m) .. "|"
                  .. tostring(file) .. "|" .. tostring(line) .. "|"
                  .. tostring(column) .. "|" .. (e.e == nil and "" or tostring(e.e))
                  .. "|" .. (e.a == nil and "" or tostring(e.a)))
                return
              end
              print("RAW|" .. tostring(e))
            end
            local chunk = dofile("%s")
            local ok, err = __dealMain()
            if not ok then emit(err) os.exit(0) end
            local ok2, err2 = pcall(__exportSurfaces["%s"]["%s"].f)
            if not ok2 then emit(err2) os.exit(0) end
            print("OK")
            """.formatted(artifactPath(compiled),
                compiled.checkedProject().entryModule().path(), export);
    }

    /**
     * The JVM deferred-entry probe of one named sync export: the module
     * entry runs once and the named export is invoked alone. The probe
     * declares the harness's driver name ({@code
     * CanonicalProjectionParityDriver}), which is the file name and main
     * class {@code jvmArtifactOutput} writes and invokes for every probe
     * source.
     */
    private static String jvmExportProbe(Compiled compiled, String export) {
        String entryModule = compiled.checkedProject().entryModule().path();
        return """
            final class CanonicalProjectionParityDriver {
              public static void main(String[] args) {
                try {
                  %s.dealMain();
                } catch (Throwable t) {
                  report(t);
                  return;
                }
                deal.codegen.jvm.JvmRuntime.Table surface =
                    %s.EXPORT_SURFACES.get("%s");
                if (surface == null) { System.out.println("NOSURFACE"); return; }
                try {
                  ((deal.codegen.jvm.JvmRuntime.FunctionValue) surface.read("%s"))
                      .fn.invoke(new java.lang.Object[]{ });
                } catch (Throwable t) {
                  report(t);
                  return;
                }
                System.out.println("OK");
              }
              private static void report(java.lang.Throwable t) {
                while (t instanceof java.lang.ExceptionInInitializerError
                        && t.getCause() != null) {
                  t = t.getCause();
                }
                try {
                  System.out.println("ERR|" + str(t, "code") + "|" + t.getMessage()
                    + "|" + str(t, "origin") + "|" + str(t, "expected") + "|"
                    + str(t, "actual"));
                } catch (java.lang.Exception unreadable) {
                  System.out.println("UNREADABLE|" + unreadable);
                }
              }
              private static java.lang.String str(java.lang.Throwable t,
                  java.lang.String name) throws java.lang.Exception {
                java.lang.reflect.Field field = findField(t.getClass(), name);
                if (field == null) { return ""; }
                field.setAccessible(true);
                java.lang.Object value = field.get(t);
                return value == null ? "" : java.lang.String.valueOf(value);
              }
              private static java.lang.reflect.Field findField(
                  java.lang.Class<?> type, java.lang.String name)
                  throws java.lang.Exception {
                for (java.lang.Class<?> c = type; c != null; c = c.getSuperclass()) {
                  try {
                    return c.getDeclaredField(name);
                  } catch (java.lang.NoSuchFieldException absent) {
                    // the next level
                  }
                }
                return null;
              }
            }
            """.formatted(JvmBackend.classNameFor(entryModule), JvmBackend.classNameFor(
                entryModule), entryModule, export);
    }

    /**
     * The two production wrappers' mapped failure tuple against the closed
     * arm's own render — the arm template instantiated with its named
     * parameters and its declared expected/actual fields. The wrapper
     * failure site holds no text of its own, so the arm is the only
     * authority for the message and the projections.
     */
    private static void assertRegistryArm(Case spec, Tuple lua, Tuple jvm,
            FailureArmId armId, Map<String, String> values, String expected,
            String actual) {
        BoundaryFailure arm = FailureContractRegistry.render(armId, values, expected,
            actual, null);
        Tuple rendered = new Tuple(arm.code().name(), arm.message(), lua.file(),
            lua.line(), lua.column(), arm.expected(), arm.actual());
        checkEq(null, firstDifferingField(rendered, lua), spec.what()
            + " (luajit): the production wrapper renders the arm's own template "
            + "and declared fields, never a composition");
        checkEq(null, firstDifferingField(rendered, jvm), spec.what()
            + " (jvm): the production wrapper renders the arm's own template "
            + "and declared fields, never a composition");
    }

    /**
     * The production wrappers' mapped failure sites and their mapped inner
     * reasons through the artifact-owned closed arm table (ISSUE-0696
     * remediation; canonical failure-projection authority P1/P4). Every
     * drive loads the real production artifact, records the two
     * artifact-registered renderers, triggers the site through the
     * production wrapper (the loaded host surface entry or the fixture's
     * own export), and asserts the published tuple equals the registry's
     * own render of the arm at the supplied origin. The controls inject
     * invalid named-parameter/projection/inner bindings through the
     * recorded renderer (each fail-closed, publishing no DEAL tuple) and
     * replace the arm's serialized template in the artifact's own source
     * (the published message must follow it).
     */
    private static void assertHostWrapperArmDelegation(Compiled compiled,
            SidecarExpectations.ErrorExpectation pin) throws Exception {
        String entryModule = compiled.checkedProject().entryModule().path();
        Map<String, String> absentHost = Map.of("async_shape_bad",
            "return {\n  fetchValue = function()\n  end,\n}\n");
        String drive = "local probe = __exportSurfaces[__ENTRY__][\""
            + "test_host_async_shape_bad\"]\n"
            + "if probe == nil then fail(\"no probe export\") end\n"
            + "return pcall(probe.f)\n";
        WrapperPin valid = wrapperPin(FailureArmId.ASYNC_SHAPE,
            Map.of("actual", "nothing"), "async operation", "nothing",
            artifactOrigin(compiled, pin), "nil", false, null, null);
        checkOutput(runWrapperProbe(compiled, absentHost, artifactPath(compiled),
            wrapperRecorder(), drive, wrapperTuple(valid)), compiled.spec().what()
            + " (luajit): the production wrapper delegates its mapped failure to "
            + "the artifact-owned closed-arm renderer, publishes the registry's own "
            + "absent-handle tuple at the supplied origin, and records the delegation");
        // The site refuses invalid bindings: an extra named parameter, a
        // projection outside the arm's declared shape, and an inner-only arm
        // at the top-level site each fail closed with a named producer
        // defect and publish no DEAL tuple.
        checkOutput(runWrapperProbe(compiled, absentHost, artifactPath(compiled),
            wrapperRecorder("    local mutated = {}\n"
                + "    for k, v in pairs(values) do mutated[k] = v end\n"
                + "    mutated.extra = \"x\"\n"
                + "    return realArm(id, mutated, file, line, column, expected, "
                + "subject)\n"), drive,
            wrapperDefect("ASYNC_SHAPE", "extra parameter")),
            compiled.spec().what() + " (luajit): the wrapper's site rejects an extra "
            + "named parameter");
        checkOutput(runWrapperProbe(compiled, absentHost, artifactPath(compiled),
            wrapperRecorder("    return realArm(id, values, file, line, column, "
                + "\"not an async operation\", subject)\n"), drive,
            wrapperDefect("ASYNC_SHAPE", "async-operation token")),
            compiled.spec().what() + " (luajit): the wrapper's site rejects an "
            + "expected projection outside the arm's declared shape");
        checkOutput(runWrapperProbe(compiled, absentHost, artifactPath(compiled),
            wrapperRecorder("    return realArm(\"HOST_STRING_INVALID_UTF8\", "
                + "values, file, line, column, expected, subject)\n"), drive,
            wrapperDefect("HOST_STRING_INVALID_UTF8")),
            compiled.spec().what() + " (luajit): the wrapper's site refuses an "
            + "inner-only arm");
        // The published message follows the artifact's serialized template:
        // replacing the ASYNC_SHAPE template in the artifact's own source
        // redirects the message, so no consumer holds a copied literal.
        String mutated = "host async function must return an async operation, got "
            + "{actual} (mutated)";
        Path patched = patchedArtifact(compiled, armEntry(FailureArmId.ASYNC_SHAPE),
            mutatedEntry(FailureArmId.ASYNC_SHAPE, mutated));
        checkOutput(runWrapperProbe(compiled, absentHost, patched.toString(),
            wrapperRecorder(), drive, wrapperTuple(valid.withMessage(
                "host async function must return an async operation, got nothing "
                    + "(mutated)"))),
            compiled.spec().what() + " (luajit): the wrapper's site follows the "
            + "artifact's serialized ASYNC_SHAPE template");
    }

    // =========================================================================
    // The DEAL-to-host null crossing (ISSUE-0696 remediation)
    // =========================================================================

    /**
     * The DEAL-to-host null crossing: a chunk language null — the
     * {@code null} literal and the admitted host null of a declared
     * null/nullable return — crosses a declared {@code null} and a declared
     * nullable primitive host parameter as the deployed runtime's explicit
     * null sentinel, never as an absence: every admitting raw host reports
     * whether it observed the sentinel or a plain nil, so admission alone
     * cannot pass. A wrong-kind result after the same crossing still
     * renders the closed return cell's canonical tuple on all three
     * consumers. The drive's declaration and host implementations are
     * caller-owned, so no corpus or host-fixture membership changes.
     */
    private static final String NULL_CROSSING_DECLARATION = """
        export function takeNull(value: null): string;
        export function takeNullableInt(value: int | null): string;
        export function takeNullBadReturn(value: null): string;
        export function takeTable(value: table): string;
        export function takeArray(value: int[]): string;
        export function returnNull(): null;
        export function returnNullableInt(): int | null;
        export async function asyncTakeNull(value: null): string;
        export async function asyncTakeNullableInt(value: int | null): string;
        export async function asyncTakeNullThenInt(value: null, count: int): string;
        export async function asyncTakePair(value: int | null, count: int | null): string;
        export async function asyncTakeTable(value: table): string;
        export async function asyncTakeArray(value: int[]): string;
        """;

    /**
     * The drive's entry module: one probe per crossing shape. Each probe
     * requires the raw host's own report — {@code sentinel} is produced
     * only for the explicit null sentinel, {@code plain-nil} for a plain
     * Lua nil — so an admitted-but-unprojected null fails the probe. The
     * bad-return probe runs the same null crossing and lets the raw host's
     * wrong-kind result reach the closed return cell.
     */
    private static final String NULL_CROSSING_DRIVER = """
        import * as host from "host/null_crossing"

        export function test_take_null_literal(): null {
          let seen: string = host.takeNull(null)
          if (seen !== "sentinel") {
            throw { code: "PROBE_NULL_CROSSING", message: "the null literal crossed the null parameter as a non-sentinel" }
          }
          return null
        }

        export function test_take_nullable_int_literal(): null {
          let seen: string = host.takeNullableInt(null)
          if (seen !== "sentinel") {
            throw { code: "PROBE_NULL_CROSSING", message: "the null literal crossed the nullable primitive parameter as a non-sentinel" }
          }
          return null
        }

        export function test_take_null_from_host(): null {
          let value: null = host.returnNull()
          let seen: string = host.takeNull(value)
          if (seen !== "sentinel") {
            throw { code: "PROBE_NULL_CROSSING", message: "the admitted host null crossed the null parameter as a non-sentinel" }
          }
          return null
        }

        export function test_take_nullable_int_from_host(): null {
          let value: int | null = host.returnNullableInt()
          let seen: string = host.takeNullableInt(value)
          if (seen !== "sentinel") {
            throw { code: "PROBE_NULL_CROSSING", message: "the admitted host null crossed the nullable primitive parameter as a non-sentinel" }
          }
          return null
        }

        export function test_take_null_bad_return(): null {
          host.takeNullBadReturn(null)
          return null
        }

        export async function test_async_take_null(): null {
          let seen: string = await host.asyncTakeNull(null)
          if (seen !== "sentinel") {
            throw { code: "PROBE_NULL_CROSSING", message: "the async null crossing delivered a non-sentinel" }
          }
          return null
        }

        export async function test_async_take_nullable_int(): null {
          let seen: string = await host.asyncTakeNullableInt(null)
          if (seen !== "sentinel") {
            throw { code: "PROBE_NULL_CROSSING", message: "the async nullable crossing delivered a non-sentinel" }
          }
          return null
        }

        export async function test_async_take_null_then_int(): null {
          let seen: string = await host.asyncTakeNullThenInt(null, 7)
          if (seen !== "sentinel+7") {
            throw { code: "PROBE_NULL_CROSSING", message: "the async null crossing shifted the arguments before a non-null parameter" }
          }
          return null
        }

        export async function test_async_take_pair(): null {
          let seen: string = await host.asyncTakePair(null, 7)
          if (seen !== "sentinel+7") {
            throw { code: "PROBE_NULL_CROSSING", message: "the async nullable crossing shifted the null argument's position" }
          }
          return null
        }

        async function applyAsyncNull(f: async (value: null) => string): string {
          return await f(null)
        }

        async function applyAsyncNullableInt(f: async (value: int | null) => string): string {
          return await f(null)
        }

        export async function test_dynamic_async_take_null(): null {
          let seen: string = await applyAsyncNull(host.asyncTakeNull)
          if (seen !== "sentinel") {
            throw { code: "PROBE_NULL_CROSSING", message: "the dynamic async null crossing delivered a non-sentinel" }
          }
          return null
        }

        export async function test_dynamic_async_take_nullable_int(): null {
          let seen: string = await applyAsyncNullableInt(host.asyncTakeNullableInt)
          if (seen !== "sentinel") {
            throw { code: "PROBE_NULL_CROSSING", message: "the dynamic async nullable crossing delivered a non-sentinel" }
          }
          return null
        }

        async function applyAsyncNullThenInt(f: async (value: null, count: int) => string): string {
          return await f(null, 7)
        }

        async function applyAsyncPair(f: async (value: int | null, count: int | null) => string): string {
          return await f(null, 7)
        }

        export async function test_dynamic_async_take_null_then_int(): null {
          let seen: string = await applyAsyncNullThenInt(host.asyncTakeNullThenInt)
          if (seen !== "sentinel+7") {
            throw { code: "PROBE_NULL_CROSSING", message: "the dynamic async null crossing shifted the arguments before a non-null parameter" }
          }
          return null
        }

        export async function test_dynamic_async_take_pair(): null {
          let seen: string = await applyAsyncPair(host.asyncTakePair)
          if (seen !== "sentinel+7") {
            throw { code: "PROBE_NULL_CROSSING", message: "the dynamic async nullable crossing shifted the null argument's position" }
          }
          return null
        }

        function applyNull(f: (value: null) => string): string {
          return f(null)
        }

        function applyNullableInt(f: (value: int | null) => string): string {
          return f(null)
        }

        export function test_dynamic_take_null(): null {
          let seen: string = applyNull(host.takeNull)
          if (seen !== "sentinel") {
            throw { code: "PROBE_NULL_CROSSING", message: "the dynamic null crossing delivered a non-sentinel" }
          }
          return null
        }

        export function test_dynamic_take_nullable_int(): null {
          let seen: string = applyNullableInt(host.takeNullableInt)
          if (seen !== "sentinel") {
            throw { code: "PROBE_NULL_CROSSING", message: "the dynamic nullable crossing delivered a non-sentinel" }
          }
          return null
        }

        function bagWithNull(): table {
          let bag: table = {};
          bag.value = null
          return bag
        }

        export function test_reject_table_dynamic_null(): null {
          host.takeTable(bagWithNull().value)
          return null
        }

        export function test_reject_array_dynamic_null(): null {
          host.takeArray(bagWithNull().value)
          return null
        }

        function applyTable(f: (value: table) => string, bag: table): string {
          return f(bag.value)
        }

        function applyArray(f: (value: int[]) => string, bag: table): string {
          return f(bag.value)
        }

        export function test_dynamic_reject_table_dynamic_null(): null {
          applyTable(host.takeTable, bagWithNull())
          return null
        }

        export function test_dynamic_reject_array_dynamic_null(): null {
          applyArray(host.takeArray, bagWithNull())
          return null
        }

        export async function test_async_reject_table_dynamic_null(): null {
          await host.asyncTakeTable(bagWithNull().value)
          return null
        }

        export async function test_async_reject_array_dynamic_null(): null {
          await host.asyncTakeArray(bagWithNull().value)
          return null
        }

        async function applyAsyncTable(f: async (value: table) => string, bag: table): string {
          return await f(bag.value)
        }

        async function applyAsyncArray(f: async (value: int[]) => string, bag: table): string {
          return await f(bag.value)
        }

        export async function test_dynamic_async_reject_table_dynamic_null(): null {
          await applyAsyncTable(host.asyncTakeTable, bagWithNull())
          return null
        }

        export async function test_dynamic_async_reject_array_dynamic_null(): null {
          await applyAsyncArray(host.asyncTakeArray, bagWithNull())
          return null
        }

        export function test_reject_table_missing_field(): null {
          host.takeTable(bagWithNull().missing)
          return null
        }

        export function test_reject_array_missing_field(): null {
          host.takeArray(bagWithNull().missing)
          return null
        }

        export async function test_async_reject_table_missing_field(): null {
          await host.asyncTakeTable(bagWithNull().missing)
          return null
        }

        export async function test_async_reject_array_missing_field(): null {
          await host.asyncTakeArray(bagWithNull().missing)
          return null
        }

        function applyTableMissing(f: (value: table) => string, bag: table): string {
          return f(bag.missing)
        }

        function applyArrayMissing(f: (value: int[]) => string, bag: table): string {
          return f(bag.missing)
        }

        export function test_dynamic_reject_table_missing_field(): null {
          applyTableMissing(host.takeTable, bagWithNull())
          return null
        }

        export function test_dynamic_reject_array_missing_field(): null {
          applyArrayMissing(host.takeArray, bagWithNull())
          return null
        }

        async function applyAsyncTableMissing(f: async (value: table) => string, bag: table): string {
          return await f(bag.missing)
        }

        async function applyAsyncArrayMissing(f: async (value: int[]) => string, bag: table): string {
          return await f(bag.missing)
        }

        export async function test_dynamic_async_reject_table_missing_field(): null {
          await applyAsyncTableMissing(host.asyncTakeTable, bagWithNull())
          return null
        }

        export async function test_dynamic_async_reject_array_missing_field(): null {
          await applyAsyncArrayMissing(host.asyncTakeArray, bagWithNull())
          return null
        }

        async function applyAsyncNullValue(f: async (value: null) => string, value: null): string {
          return await f(value)
        }

        export async function test_async_take_null_from_host(): null {
          let value: null = host.returnNull()
          let seen: string = await host.asyncTakeNull(value)
          if (seen !== "sentinel") {
            throw { code: "PROBE_NULL_CROSSING", message: "the admitted host null crossed the async null host start as a non-sentinel" }
          }
          return null
        }

        export async function test_dynamic_async_take_null_from_host(): null {
          let value: null = host.returnNull()
          let seen: string = await applyAsyncNullValue(host.asyncTakeNull, value)
          if (seen !== "sentinel") {
            throw { code: "PROBE_NULL_CROSSING", message: "the dynamic async admission delivered a non-sentinel for the admitted host null" }
          }
          return null
        }

        export function main(): null {
          return null
        }
        """;

    /**
     * The LuaJIT null host: every accepting export distinguishes the
     * deployed runtime's explicit null sentinel from a plain Lua nil, and
     * every producer returns the sentinel. The bad-return export returns a
     * real int for its declared string return.
     */
    private static String luaNullCrossingHost() {
        return """
            local rt = require("deal.runtime")

            local function report(value, count)
              if value == rt.__NULL and count == 7 then return "sentinel+7" end
              if value == nil and count == 7 then return "plain-nil+7" end
              if value == 7 and (count == rt.__NULL or count == nil) then
                return "shifted"
              end
              return "other"
            end

            return {
              takeNull = function(value)
                if value == rt.__NULL then return "sentinel" end
                if value == nil then return "plain-nil" end
                return "other"
              end,
              takeNullableInt = function(value)
                if value == rt.__NULL then return "sentinel" end
                if value == nil then return "plain-nil" end
                return "other"
              end,
              takeTable = function(value)
                return "unexpected-table-admission"
              end,
              takeArray = function(value)
                return "unexpected-array-admission"
              end,
              takeNullBadReturn = function(value)
                return 7
              end,
              asyncTakeNull = function(value)
                if value == rt.__NULL then
                  return rt.async_start(function() return "sentinel" end)
                end
                if value == nil then
                  return rt.async_start(function() return "plain-nil" end)
                end
                return rt.async_start(function() return "other" end)
              end,
              asyncTakeNullableInt = function(value)
                if value == rt.__NULL then
                  return rt.async_start(function() return "sentinel" end)
                end
                if value == nil then
                  return rt.async_start(function() return "plain-nil" end)
                end
                return rt.async_start(function() return "other" end)
              end,
              asyncTakeNullThenInt = function(value, count)
                if value == rt.__NULL and count == 7 then
                  return rt.async_start(function() return "sentinel+7" end)
                end
                return rt.async_start(function() return report(value, count) end)
              end,
              asyncTakePair = function(value, count)
                if value == rt.__NULL and count == 7 then
                  return rt.async_start(function() return "sentinel+7" end)
                end
                return rt.async_start(function() return report(value, count) end)
              end,
              asyncTakeTable = function(value)
                return rt.async_start(function()
                  return "unexpected-table-admission"
                end)
              end,
              asyncTakeArray = function(value)
                return rt.async_start(function()
                  return "unexpected-array-admission"
                end)
              end,
              returnNull = function()
                return rt.__NULL
              end,
              returnNullableInt = function()
                return rt.__NULL
              end,
            }
            """;
    }

    /**
     * The JVM null host: every accepting export distinguishes the present
     * Java null — the JVM carrier of the language null — from a non-null
     * value, and every producer returns a present Java null. The bad-return
     * export returns a boxed int for its declared string return.
     */
    private static String jvmNullCrossingHost() {
        return """
            final class HostNull_crossing {
              public static Object takeNull(Object value) {
                return value == null ? "sentinel" : "other";
              }
              public static Object takeNullableInt(Integer value) {
                return value == null ? "sentinel" : "other";
              }
              public static Object takeTable(Object value) {
                return "unexpected-table-admission";
              }
              public static Object takeArray($DealRt.__IntArray value) {
                return "unexpected-array-admission";
              }
              public static Object takeNullBadReturn(Object value) {
                return java.lang.Integer.valueOf(7);
              }
              public static Object asyncTakeNull(Object value) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                    value == null ? "sentinel" : "other");
              }
              public static Object asyncTakeNullableInt(Integer value) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                    value == null ? "sentinel" : "other");
              }
              public static Object asyncTakeNullThenInt(Object value, int count) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                    value == null && count == 7 ? "sentinel+7" : "other");
              }
              public static Object asyncTakePair(Integer value, Integer count) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                    value == null && count != null && count == 7
                        ? "sentinel+7" : "other");
              }
              public static Object asyncTakeTable(Object value) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                    "unexpected-table-admission");
              }
              public static Object asyncTakeArray($DealRt.__IntArray value) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                    "unexpected-array-admission");
              }
              public static Object returnNull() {
                return null;
              }
              public static Object returnNullableInt() {
                return null;
              }
            }
            """;
    }

    /**
     * The null-crossing host seam: the accepting sync exports admit only
     * the oracle's language null ({@code NullValue}) and report it as
     * {@code sentinel}, the async exports record the observed argument for
     * their completion — the two-argument exports record the whole ordered
     * argument list, so a nil-dropped or shifted null cannot pass — the
     * producers return the language null, and the bad-return export returns
     * a real int for its declared string return. The dynamic crossings read
     * the module's loaded surface entries through the responder's own
     * opaque host-entry carrier.
     */
    private static SemanticOracle.HostResponder nullCrossingResponder() {
        return new SemanticOracle.HostResponder() {
            @Override
            public SemanticOracle.Value loadedExport(ModuleId module, String export,
                    RuntimeDescriptor descriptor) {
                // The loaded surface entries the dynamic function-valued
                // crossings read: every declared export of the drive's own
                // host module, as the oracle's opaque host-entry carrier.
                if (!module.path().endsWith("null_crossing")
                        || !(descriptor instanceof RuntimeDescriptor.Func func)) {
                    return null;
                }
                return new SemanticOracle.Value.HostEntryValue(func);
            }

            private final Map<String, String> asyncSeen = new LinkedHashMap<>();

            @Override
            public AsyncStartOutcome startAsync(ModuleId module, String export,
                    RuntimeDescriptor.Func descriptor,
                    List<SemanticOracle.Value> args, String operationLabel) {
                if (("asyncTakeNull".equals(export)
                        || "asyncTakeNullableInt".equals(export)) && args.size() == 1) {
                    asyncSeen.put(operationLabel,
                        args.get(0) instanceof SemanticOracle.Value.NullValue
                            ? "sentinel" : "other");
                } else if ("asyncTakeTable".equals(export)
                        || "asyncTakeArray".equals(export)) {
                    // The non-nullable rejections never reach the host: the
                    // recorded parameter cell rejects the language null
                    // before the start. A reached start is a wrong
                    // admission and the probe must fail on the marker.
                    asyncSeen.put(operationLabel, "unexpected-admission");
                } else if ("asyncTakeNullThenInt".equals(export)
                        || "asyncTakePair".equals(export)) {
                    // The null-before-non-null crossings: the oracle's own
                    // ordered argument list carries the language null at
                    // position 1 and the int 7 at position 2, so a shifted
                    // or nil-dropped argument fails the probe exactly like
                    // the raw hosts' own reports do.
                    asyncSeen.put(operationLabel,
                        args.size() == 2
                                && args.get(0)
                                    instanceof SemanticOracle.Value.NullValue
                                && args.get(1)
                                    instanceof SemanticOracle.Value.IntValue intValue
                                && intValue.value() == 7
                            ? "sentinel+7" : "other");
                }
                return new AsyncStartOutcome.Operation(operationLabel);
            }

            @Override
            public SyncOutcome completeAsync(String operationLabel) {
                return new SyncOutcome.Returned(new SemanticOracle.Value.StrValue(
                    asyncSeen.getOrDefault(operationLabel, "other")));
            }

            @Override
            public SyncOutcome call(ModuleId module, String export,
                    RuntimeDescriptor.Func descriptor,
                    List<SemanticOracle.Value> args) {
                return switch (export) {
                    case "takeNull", "takeNullableInt" -> new SyncOutcome.Returned(
                        new SemanticOracle.Value.StrValue(
                            args.size() == 1
                                && args.get(0)
                                    instanceof SemanticOracle.Value.NullValue
                                ? "sentinel" : "other"));
                    case "takeNullBadReturn" -> new SyncOutcome.Returned(
                        new SemanticOracle.Value.IntValue(7L));
                    case "takeTable", "takeArray" -> new SyncOutcome.Returned(
                        new SemanticOracle.Value.StrValue("unexpected-admission"));
                    case "returnNull", "returnNullableInt" -> new SyncOutcome.Returned(
                        SemanticOracle.Value.NullValue.INSTANCE);
                    default -> throw new IllegalStateException(
                        "the null-crossing responder received the undeclared "
                            + "export '" + export + "'");
                };
            }
        };
    }

    /** The caller-owned null-crossing project (one declaration and driver). */
    private static Compiled compileNullCrossing() throws Exception {
        Case spec = new Case("host-abi/host-async-shape-bad", "test_take_null_literal",
            Entry.MAIN, List.of(), null, null);
        return compile(spec, NULL_CROSSING_DRIVER,
            Map.of("null_crossing", NULL_CROSSING_DECLARATION));
    }

    /**
     * The null-crossing drive: the admitted crossings — the null literal
     * and the admitted host null against the null and the nullable primitive
     * parameter through the declaring call shape, the same values through
     * the dynamic function-valued call shape, the null literal and the
     * admitted host null through the declaring and dynamic async host
     * starts, including a null in front of a non-null argument (both
     * declared parameters nullable, and a declared {@code null} before a
     * declared {@code int}) so a nil-dropped or position-shifted argument
     * cannot pass — complete without a failure event on the oracle, the
     * LuaJIT artifact, and the JVM artifact (each raw host reporting that it
     * observed the explicit null sentinel at the recorded position), and the
     * wrong-kind return after the same crossing renders the closed return
     * cell's tuple byte-exact and identical on the three consumers.
     */
    private static void testHostNullParameterCrossing() throws Exception {
        System.out.println("-- the DEAL-to-host null crossing: the language "
            + "null crosses a null and a nullable primitive host parameter as "
            + "the explicit null sentinel, and the subsequent return failure "
            + "renders the closed arm's canonical tuple --");
        Compiled compiled = compileNullCrossing();
        try {
            safely("the null crossing admissions",
                () -> driveNullCrossingAdmissions(compiled));
            safely("the null crossing's incompatible-position rejections",
                () -> driveNullCrossingRejections(compiled));
            safely("the null crossing's subsequent failure projection",
                () -> driveNullCrossingBadReturn(compiled));
            safely("the null crossing's two-load retained exports",
                () -> driveNullCrossingTwoLoadRetention(compiled));
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /** The admitted null crossings on all three consumers. */
    private static void driveNullCrossingAdmissions(Compiled compiled)
            throws Exception {
        assertNullCrossingAdmission(compiled, "test_take_null_literal",
            "the null literal at the null parameter");
        assertNullCrossingAdmission(compiled, "test_take_nullable_int_literal",
            "the null literal at the nullable primitive parameter");
        assertNullCrossingAdmission(compiled, "test_take_null_from_host",
            "the admitted host null at the null parameter");
        assertNullCrossingAdmission(compiled, "test_take_nullable_int_from_host",
            "the admitted host null at the nullable primitive parameter");
        assertNullCrossingAdmission(compiled, "test_dynamic_take_null",
            "the dynamic null literal at the null parameter");
        assertNullCrossingAdmission(compiled, "test_dynamic_take_nullable_int",
            "the dynamic null literal at the nullable primitive parameter");
        assertAsyncNullCrossingAdmission(compiled, "test_async_take_null",
            "the async null literal at the null parameter");
        assertAsyncNullCrossingAdmission(compiled, "test_async_take_nullable_int",
            "the async null literal at the nullable primitive parameter");
        assertAsyncNullCrossingAdmission(compiled, "test_dynamic_async_take_null",
            "the dynamic async null literal at the null parameter");
        assertAsyncNullCrossingAdmission(compiled, "test_dynamic_async_take_nullable_int",
            "the dynamic async null literal at the nullable primitive parameter");
        assertAsyncNullCrossingAdmission(compiled, "test_async_take_null_from_host",
            "the admitted host null at the async null host start");
        assertAsyncNullCrossingAdmission(compiled,
            "test_dynamic_async_take_null_from_host",
            "the admitted host null at the dynamic async null host start");
        assertAsyncNullCrossingAdmission(compiled, "test_async_take_null_then_int",
            "the async null literal before a non-null int parameter");
        assertAsyncNullCrossingAdmission(compiled, "test_async_take_pair",
            "the async null literal before a non-null argument (both nullable)");
        assertAsyncNullCrossingAdmission(compiled, "test_dynamic_async_take_null_then_int",
            "the dynamic async null literal before a non-null int parameter");
        assertAsyncNullCrossingAdmission(compiled, "test_dynamic_async_take_pair",
            "the dynamic async null literal before a non-null argument (both nullable)");
    }

    /** One admitted crossing through the async host start. */
    private static void assertAsyncNullCrossingAdmission(Compiled compiled,
            String export, String what) throws Exception {
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.invokeAsyncEntry(
            compiled.project(), compiled.tables(), nullCrossingResponder(),
            compiled.checkedProject().entryModule(), export, List.of());
        check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
            what + " (oracle): the admitted crossing completes: " + run.terminal());
        String lua = luaArtifactOutput(compiled,
            Map.of("null_crossing", luaNullCrossingHost()),
            luaExportProbe(compiled, export));
        check(lua.contains("OK") && !lua.contains("ERR|"), what + " (luajit): "
            + "the admitted crossing completes and the raw host observed the "
            + "explicit null sentinel: " + lua);
        String jvm = jvmArtifactOutput(compiled, jvmExportProbe(compiled, export),
            Map.of("null_crossing", jvmNullCrossingHost()));
        check(jvm != null && jvm.contains("OK") && !jvm.contains("ERR|"), what
            + " (jvm): the admitted crossing completes and the raw host observed "
            + "the language-null carrier: " + jvm);
    }

    /**
     * One admitted crossing: the probe completes on the oracle, the LuaJIT
     * artifact, and the JVM artifact. Each probe's own body rejects a raw
     * host report other than {@code sentinel}, so the drive proves both the
     * admission and the raw-host representation.
     */
    private static void assertNullCrossingAdmission(Compiled compiled, String export,
            String what) throws Exception {
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.invokeSyncEntry(
            compiled.project(), compiled.tables(), compiled.registries(),
            nullCrossingResponder(), compiled.checkedProject().entryModule(), export,
            List.of());
        check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
            what + " (oracle): the admitted crossing completes: " + run.terminal());
        String lua = luaArtifactOutput(compiled,
            Map.of("null_crossing", luaNullCrossingHost()),
            luaExportProbe(compiled, export));
        check(lua.contains("OK") && !lua.contains("ERR|"), what + " (luajit): "
            + "the admitted crossing completes and the raw host observed the "
            + "explicit null sentinel: " + lua);
        String jvm = jvmArtifactOutput(compiled, jvmExportProbe(compiled, export),
            Map.of("null_crossing", jvmNullCrossingHost()));
        check(jvm != null && jvm.contains("OK") && !jvm.contains("ERR|"), what
            + " (jvm): the admitted crossing completes and the raw host observed "
            + "the language-null carrier: " + jvm);
    }

    /**
     * The crossing's subsequent failure projection: the raw host returns a
     * real int for the declared {@code string} return after the null
     * parameter was admitted, so the closed {@code HOST_SYNC_RETURN_CELL}
     * arm renders its own tuple at the call origin — byte-exact and
     * identical on the oracle, the LuaJIT artifact, and the JVM artifact.
     * An unnormalized parameter cell would fail earlier with a different
     * tuple, so the projection also proves the admission.
     */
    private static void driveNullCrossingBadReturn(Compiled compiled)
            throws Exception {
        String export = "test_take_null_bad_return";
        Tuple expected = hostReturnCellTuple("string",
            FailureProjections.kindReason(RuntimeDescriptor.String.INSTANCE),
            "number", 38, 3);
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.invokeSyncEntry(
            compiled.project(), compiled.tables(), compiled.registries(),
            nullCrossingResponder(), compiled.checkedProject().entryModule(), export,
            List.of());
        Tuple oracle = failureTuple(compiled, run);
        checkEq(expected, oracle, "the null crossing's subsequent failure "
            + "(oracle): the closed return cell's own tuple at the call origin");
        String luaOutput = luaArtifactOutput(compiled,
            Map.of("null_crossing", luaNullCrossingHost()),
            luaExportProbe(compiled, export));
        Tuple lua = parseLuaProbe(luaOutput, compiled);
        checkEq(expected, lua, "the null crossing's subsequent failure "
            + "(luajit): the closed return cell's own tuple at the call origin: "
            + luaOutput);
        String jvmOutput = jvmArtifactOutput(compiled,
            jvmExportProbe(compiled, export),
            Map.of("null_crossing", jvmNullCrossingHost()));
        Tuple jvm = jvmOutput == null ? null : parseJvmProbe(jvmOutput, compiled);
        checkEq(expected, jvm, "the null crossing's subsequent failure "
            + "(jvm): the closed return cell's own tuple at the call origin: "
            + jvmOutput);
        if (oracle != null && lua != null) {
            checkEq(null, firstDifferingField(oracle, lua),
                "the null crossing's subsequent failure: the oracle and the "
                    + "LuaJIT artifact render the identical tuple");
        }
        if (oracle != null && jvm != null) {
            checkEq(null, firstDifferingField(oracle, jvm),
                "the null crossing's subsequent failure: the oracle and the "
                    + "JVM artifact render the identical tuple");
        }
    }

    /**
     * The crossing's cross-chunk state isolation (LuaJIT regression): a
     * second load of the same production chunk must not rewrite a retained
     * export's crossing behavior. The probe loads the real artifact, runs
     * its module init, retains the first chunk's exported closures, loads
     * and initializes the same artifact again, and then drives the retained
     * closures: every admitted crossing — the null literal and the admitted
     * host null at the declared {@code null} and the declared nullable
     * primitive parameter through the declaring and the dynamic call shape,
     * including both async host starts over the chunk's own null
     * normalization — must still deliver the deployed runtime's explicit
     * null sentinel to the raw host, and the retained non-nullable
     * rejection must still render the declared parameter cell's canonical
     * tuple at its recorded origin. A crossing helper (or the async-entry
     * dispatch field) assigned to a global name is clobbered by the second
     * chunk, so before the fix the retained export rejects its own chunk's
     * null sentinel with a foreign tuple, admits the retained absence
     * marker, or reaches the second chunk's uninitialized activation state.
     */
    private static void driveNullCrossingTwoLoadRetention(Compiled compiled)
            throws Exception {
        String successes = "{"
            + "\"test_take_null_literal\", "
            + "\"test_take_nullable_int_literal\", "
            + "\"test_take_null_from_host\", "
            + "\"test_take_nullable_int_from_host\", "
            + "\"test_dynamic_take_null\", "
            + "\"test_dynamic_take_nullable_int\", "
            + "\"test_async_take_null\", "
            + "\"test_async_take_nullable_int\", "
            + "\"test_async_take_null_from_host\", "
            + "\"test_dynamic_async_take_null\", "
            + "\"test_dynamic_async_take_null_from_host\"}";
        String probe = """
            local function fail(m) print("PROBE-FAIL: " .. tostring(m)) os.exit(1) end
            local function emit(e)
              if type(e) == "table" and e.__d then
                local file, line, column = nil, nil, nil
                if e.o ~= nil then
                  file, line, column = tostring(e.o):match("^(.*):(%d+):(%d+)$")
                end
                print("ERR|" .. tostring(e.code) .. "|" .. tostring(e.m) .. "|"
                  .. tostring(file) .. "|" .. tostring(line) .. "|"
                  .. tostring(column) .. "|" .. (e.e == nil and "" or tostring(e.e))
                  .. "|" .. (e.a == nil and "" or tostring(e.a)))
                return
              end
              print("RAW|" .. tostring(e))
            end
            dofile(__ARTIFACT__)
            local ok, err = __dealMain()
            if not ok then fail("the first init failed") end
            local retained = {}
            for _, name in ipairs(__SUCCESS__) do
              local entry = __exportSurfaces[__MODULE__][name]
              if entry == nil then fail("no retained entry " .. name) end
              retained[name] = entry.f
            end
            local reject = __exportSurfaces[__MODULE__]["test_reject_table_missing_field"]
            if reject == nil then fail("no retained rejection entry") end
            local retainedReject = reject.f
            dofile(__ARTIFACT__)
            ok, err = __dealMain()
            if not ok then fail("the second init failed") end
            for _, name in ipairs(__SUCCESS__) do
              local ok2, err2 = pcall(retained[name])
              if not ok2 then
                fail("the retained export " .. name .. " failed: "
                  .. tostring(type(err2) == "table"
                      and (tostring(err2.code) .. "|" .. tostring(err2.m)
                        .. "|" .. tostring(err2.o)) or err2))
              end
              print("RETAINED|" .. name)
            end
            local ok3, err3 = pcall(retainedReject)
            if ok3 then fail("the retained rejection completed") end
            emit(err3)
            print("TWO-LOAD-OK")
            """;
        String probeSource = probe
            .replace("__ARTIFACT__", luaString(artifactPath(compiled)))
            .replace("__MODULE__",
                luaString(compiled.checkedProject().entryModule().path()))
            .replace("__SUCCESS__", successes);
        String output = luaArtifactOutput(compiled,
            Map.of("null_crossing", luaNullCrossingHost()), probeSource);
        check(!output.contains("PROBE-FAIL") && output.contains("TWO-LOAD-OK"),
            "the null crossing's two-load retained exports complete: " + output);
        for (String name : List.of("test_take_null_literal",
                "test_take_nullable_int_literal", "test_take_null_from_host",
                "test_take_nullable_int_from_host", "test_dynamic_take_null",
                "test_dynamic_take_nullable_int", "test_async_take_null",
                "test_async_take_nullable_int", "test_async_take_null_from_host",
                "test_dynamic_async_take_null",
                "test_dynamic_async_take_null_from_host")) {
            check(output.contains("RETAINED|" + name), name + ": the retained "
                + "export completes after a second load of the same chunk: " + output);
        }
        Tuple rejectTuple = parseLuaProbe(output, compiled);
        checkEq(hostParameterCellTuple("table", 1,
                FailureProjections.kindReason(RuntimeDescriptor.Table.INSTANCE),
                "nil", 209, 3),
            rejectTuple, "the null crossing's two-load retained rejection renders "
                + "the declared parameter cell's canonical tuple: " + output);
    }

    /**
     * The crossing's rejection controls: a language null supplied
     * dynamically (a {@code table} read) and a genuine missing field —
     * never a language null — supplied to a non-nullable {@code table}
     * and a non-nullable {@code int[]} host position must be rejected on
     * all three consumers with the identical closed tuple and origin. The
     * declaring call shape and the declaring async host start run the
     * recorded {@code HOST_PARAMETER} cell, so their tuple is the closed
     * {@code HOST_PARAMETER_CELL} composite — the declared kind's reason,
     * the declared descriptor as expected, and the failing carrier's
     * {@code table} kind (the language null) or {@code nil} kind (the
     * absent marker) as actual — while the function-valued (indirect)
     * call shape and its async start run the recorded general
     * function-parameter cell, whose closed typed-boundary kind tuple
     * names the declared kind. The loaded matcher's own table and array
     * rows admit both the null sentinel and the element-less absent
     * marker, so a projection that only normalizes the null without
     * distinguishing the declared position, or that leaves the marker to
     * the representation-only matcher, would complete on Lua while the
     * other two consumers reject.
     */
    private static void driveNullCrossingRejections(Compiled compiled)
            throws Exception {
        Tuple tableCell = hostParameterCellTuple("table", 1,
            FailureProjections.kindReason(RuntimeDescriptor.Table.INSTANCE), "table",
            153, 3);
        Tuple arrayCell = hostParameterCellTuple("[int]", 1,
            FailureProjections.kindReason(
                new RuntimeDescriptor.Array(RuntimeDescriptor.Int.INSTANCE)),
            "table", 158, 3);
        assertNullCrossingRejection(compiled, "test_reject_table_dynamic_null",
            tableCell, "the dynamically supplied null at the non-nullable "
                + "table parameter");
        assertNullCrossingRejection(compiled, "test_reject_array_dynamic_null",
            arrayCell, "the dynamically supplied null at the non-nullable "
                + "array parameter");
        assertNullCrossingRejection(compiled,
            "test_dynamic_reject_table_dynamic_null",
            typedBoundaryKindTuple("table", "null", 163, 12),
            "the function-valued call's dynamically supplied null at the "
                + "non-nullable table parameter");
        assertNullCrossingRejection(compiled,
            "test_dynamic_reject_array_dynamic_null",
            typedBoundaryKindTuple("array", "null", 167, 10),
            "the function-valued call's dynamically supplied null at the "
                + "non-nullable array parameter");
        assertAsyncNullCrossingRejection(compiled,
            "test_async_reject_table_dynamic_null",
            hostParameterCellTuple("table", 1,
                FailureProjections.kindReason(RuntimeDescriptor.Table.INSTANCE),
                "table", 181, 9),
            "the async start's dynamically supplied null at the non-nullable "
                + "table parameter");
        assertAsyncNullCrossingRejection(compiled,
            "test_async_reject_array_dynamic_null",
            hostParameterCellTuple("[int]", 1,
                FailureProjections.kindReason(
                    new RuntimeDescriptor.Array(RuntimeDescriptor.Int.INSTANCE)),
                "table", 186, 9),
            "the async start's dynamically supplied null at the non-nullable "
                + "array parameter");
        assertAsyncNullCrossingRejection(compiled,
            "test_dynamic_async_reject_table_dynamic_null",
            typedBoundaryKindTuple("table", "null", 191, 18),
            "the function-valued async start's dynamically supplied null at "
                + "the non-nullable table parameter");
        assertAsyncNullCrossingRejection(compiled,
            "test_dynamic_async_reject_array_dynamic_null",
            typedBoundaryKindTuple("array", "null", 195, 16),
            "the function-valued async start's dynamically supplied null at "
                + "the non-nullable array parameter");
        // The absent-marker controls: a missing field (never a language
        // null) supplied to the same non-nullable positions must be
        // rejected by the same cells. The deployed matcher's table row
        // admits every Lua table and its array row every element-less one,
        // so the absent marker's chunk-side marker table would cross a
        // non-nullable table/array host parameter exactly like a real
        // table unless the emitted parameter admission distinguishes it
        // first.
        assertNullCrossingRejection(compiled, "test_reject_table_missing_field",
            hostParameterCellTuple("table", 1,
                FailureProjections.kindReason(RuntimeDescriptor.Table.INSTANCE),
                "nil", 209, 3),
            "the missing field at the non-nullable table parameter");
        assertNullCrossingRejection(compiled, "test_reject_array_missing_field",
            hostParameterCellTuple("[int]", 1,
                FailureProjections.kindReason(
                    new RuntimeDescriptor.Array(RuntimeDescriptor.Int.INSTANCE)),
                "nil", 214, 3),
            "the missing field at the non-nullable array parameter");
        assertAsyncNullCrossingRejection(compiled,
            "test_async_reject_table_missing_field",
            hostParameterCellTuple("table", 1,
                FailureProjections.kindReason(RuntimeDescriptor.Table.INSTANCE),
                "nil", 219, 9),
            "the async start's missing field at the non-nullable table "
                + "parameter");
        assertAsyncNullCrossingRejection(compiled,
            "test_async_reject_array_missing_field",
            hostParameterCellTuple("[int]", 1,
                FailureProjections.kindReason(
                    new RuntimeDescriptor.Array(RuntimeDescriptor.Int.INSTANCE)),
                "nil", 224, 9),
            "the async start's missing field at the non-nullable array "
                + "parameter");
        assertNullCrossingRejection(compiled,
            "test_dynamic_reject_table_missing_field",
            typedBoundaryKindTuple("table", "nil", 229, 12),
            "the function-valued call's missing field at the non-nullable "
                + "table parameter");
        assertNullCrossingRejection(compiled,
            "test_dynamic_reject_array_missing_field",
            typedBoundaryKindTuple("array", "nil", 233, 10),
            "the function-valued call's missing field at the non-nullable "
                + "array parameter");
        assertAsyncNullCrossingRejection(compiled,
            "test_dynamic_async_reject_table_missing_field",
            typedBoundaryKindTuple("table", "nil", 247, 18),
            "the function-valued async start's missing field at the "
                + "non-nullable table parameter");
        assertAsyncNullCrossingRejection(compiled,
            "test_dynamic_async_reject_array_missing_field",
            typedBoundaryKindTuple("array", "nil", 251, 16),
            "the function-valued async start's missing field at the "
                + "non-nullable array parameter");
    }

    /** The closed {@code TYPE_DESCRIPTOR} kind tuple at one probe origin. */
    private static Tuple typedBoundaryKindTuple(String kind, String actual, int line,
            int column) {
        BoundaryFailure arm = FailureContractRegistry.render(
            FailureArmId.TYPED_BOUNDARY_KIND, Map.of("kind", kind), kind, actual,
            null);
        return new Tuple(arm.code().name(), arm.message(), "app.deal", line, column,
            arm.expected(), arm.actual());
    }

    /** The closed {@code HOST_PARAMETER_CELL} tuple at one probe origin. */
    private static Tuple hostParameterCellTuple(String descriptor, int index,
            String inner, String actual, int line, int column) {
        BoundaryFailure arm = FailureContractRegistry.render(
            FailureArmId.HOST_PARAMETER_CELL,
            Map.of("index", Integer.toString(index), "inner", inner), descriptor,
            actual, null);
        return new Tuple(arm.code().name(), arm.message(), "app.deal", line, column,
            arm.expected(), arm.actual());
    }

    /**
     * One rejected sync crossing: the three consumers render the declared
     * parameter cell's closed tuple at the recorded call origin; the raw
     * host is never invoked (its own report would complete the probe).
     */
    private static void assertNullCrossingRejection(Compiled compiled, String export,
            Tuple expected, String what) throws Exception {
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.invokeSyncEntry(
            compiled.project(), compiled.tables(), compiled.registries(),
            nullCrossingResponder(), compiled.checkedProject().entryModule(), export,
            List.of());
        Tuple oracle = failureTuple(compiled, run);
        checkEq(expected, oracle, what + " (oracle): the incompatible null is "
            + "rejected by the declared parameter cell's own tuple");
        String luaOutput = luaArtifactOutput(compiled,
            Map.of("null_crossing", luaNullCrossingHost()),
            luaExportProbe(compiled, export));
        Tuple lua = parseLuaProbe(luaOutput, compiled);
        checkEq(expected, lua, what + " (luajit): the incompatible null is "
            + "rejected by the declared parameter cell's own tuple: " + luaOutput);
        String jvmOutput = jvmArtifactOutput(compiled, jvmExportProbe(compiled, export),
            Map.of("null_crossing", jvmNullCrossingHost()));
        Tuple jvm = jvmOutput == null ? null : parseJvmProbe(jvmOutput, compiled);
        checkEq(expected, jvm, what + " (jvm): the incompatible null is rejected "
            + "by the declared parameter cell's own tuple: " + jvmOutput);
        if (oracle != null && lua != null) {
            checkEq(null, firstDifferingField(oracle, lua), what + ": the oracle "
                + "and the LuaJIT artifact render the identical tuple");
        }
        if (oracle != null && jvm != null) {
            checkEq(null, firstDifferingField(oracle, jvm), what + ": the oracle "
                + "and the JVM artifact render the identical tuple");
        }
    }

    /**
     * One rejected async crossing: the same closed parameter-cell tuple at
     * the recorded start origin on the three consumers, the raw host's
     * async start never reached.
     */
    private static void assertAsyncNullCrossingRejection(Compiled compiled,
            String export, Tuple expected, String what) throws Exception {
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.invokeAsyncEntry(
            compiled.project(), compiled.tables(), nullCrossingResponder(),
            compiled.checkedProject().entryModule(), export, List.of());
        Tuple oracle = failureTuple(compiled, run);
        checkEq(expected, oracle, what + " (oracle): the incompatible null is "
            + "rejected by the declared parameter cell's own tuple");
        String luaOutput = luaArtifactOutput(compiled,
            Map.of("null_crossing", luaNullCrossingHost()),
            luaExportProbe(compiled, export));
        Tuple lua = parseLuaProbe(luaOutput, compiled);
        checkEq(expected, lua, what + " (luajit): the incompatible null is "
            + "rejected by the declared parameter cell's own tuple: " + luaOutput);
        String jvmOutput = jvmArtifactOutput(compiled, jvmExportProbe(compiled, export),
            Map.of("null_crossing", jvmNullCrossingHost()));
        Tuple jvm = jvmOutput == null ? null : parseJvmProbe(jvmOutput, compiled);
        checkEq(expected, jvm, what + " (jvm): the incompatible null is rejected "
            + "by the declared parameter cell's own tuple: " + jvmOutput);
        if (oracle != null && lua != null) {
            checkEq(null, firstDifferingField(oracle, lua), what + ": the oracle "
                + "and the LuaJIT artifact render the identical tuple");
        }
        if (oracle != null && jvm != null) {
            checkEq(null, firstDifferingField(oracle, jvm), what + ": the oracle "
                + "and the JVM artifact render the identical tuple");
        }
    }

    /**
     * The mapped sync-return, nothing, and parameter cells of the production
     * wrappers: each publishes the registry's own complete tuple at the
     * supplied origin, delegates its mapped inner reason as a structured
     * closed-arm selection (never text), and follows the artifact's
     * serialized inner templates — so a copied matcher reason cannot pass by
     * matching today's literals. Each control also injects invalid
     * named-parameter, projection, and inner bindings at its site.
     */
    private static void testHostWrapperArmAuthority() throws Exception {
        System.out.println("-- the production wrappers' parameter, sync-return, and "
            + "nothing cells render through the artifact's closed arm table --");
        Compiled compiled = compile(mainEntry(
            "host-abi/host-bytes-param-mismatch-e8010"));
        try {
            wrapperParameterSite(compiled);
            wrapperInnerTemplateAuthority(compiled);
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /**
     * The parameter cell of the loaded host wrapper: the wrong-kind argument
     * crosses the production wrapper's own declared-parameter rule (the
     * artifact-side projection of the same call is the loaded wrapper's).
     */
    private static void wrapperParameterSite(Compiled compiled) throws Exception {
        String drive = "local surface = __exportSurfaces[\"host.bytes_roundtrip\"]\n"
            + "if surface == nil then fail(\"no host surface\") end\n"
            + "local entry = surface.echoBytes\n"
            + "if entry == nil then fail(\"no echoBytes export\") end\n"
            + "return pcall(entry.f, 5, \"probe.deal\", 3, 4)\n";
        WrapperPin valid = wrapperPin(FailureArmId.HOST_PARAMETER_CELL,
            Map.of("index", "1", "inner", FailureProjections.kindReason(
                RuntimeDescriptor.Bytes.INSTANCE)),
            "bytes", "number", "probe.deal:3:4", "5", true,
            FailureArmId.TYPED_BOUNDARY_KIND, "bytes");
        checkOutput(runWrapperProbe(compiled, Map.of(), artifactPath(compiled),
            wrapperRecorder(), drive, wrapperTuple(valid)),
            "host-abi/host-bytes-param-mismatch-e8010 (luajit): the loaded "
            + "wrapper's parameter cell publishes the registry's tuple with the "
            + "matcher-selected inner arm and the declared projections");
        checkOutput(runWrapperProbe(compiled, Map.of(), artifactPath(compiled),
            wrapperRecorder("    local mutated = {}\n"
                + "    for k, v in pairs(values) do\n"
                + "      if k ~= \"index\" then mutated[k] = v end\n"
                + "    end\n"
                + "    return realArm(id, mutated, file, line, column, expected, "
                + "subject)\n"), drive,
            wrapperDefect("HOST_PARAMETER_CELL", "supplied 1")),
            "host-abi/host-bytes-param-mismatch-e8010 (luajit): the parameter "
            + "cell rejects a missing named parameter");
        checkOutput(runWrapperProbe(compiled, Map.of(), artifactPath(compiled),
            wrapperRecorder(null, "  realInner(id, values)\n"
                + "  return \"a composed reason\"\n"), drive,
            wrapperDefect("HOST_PARAMETER_CELL",
                "closed inner-reason vocabulary")),
            "host-abi/host-bytes-param-mismatch-e8010 (luajit): the parameter "
            + "cell rejects a composed inner reason");
        checkOutput(runWrapperProbe(compiled, Map.of(), artifactPath(compiled),
            wrapperRecorder("    return realArm(id, values, file, line, column, "
                + "\"not a descriptor\", subject)\n"), drive,
            wrapperDefect("HOST_PARAMETER_CELL", "canonical descriptor")),
            "host-abi/host-bytes-param-mismatch-e8010 (luajit): the parameter "
            + "cell rejects an expected projection outside its declared shape");
        String mutated = "wanted {kind} instead";
        Path patched = patchedArtifact(compiled,
            armEntry(FailureArmId.TYPED_BOUNDARY_KIND),
            mutatedEntry(FailureArmId.TYPED_BOUNDARY_KIND, mutated));
        checkOutput(runWrapperProbe(compiled, Map.of(), patched.toString(),
            wrapperRecorder(), drive, wrapperTuple(valid.withMessage(
                "parameter 1 type mismatch: wanted bytes instead"))),
            "host-abi/host-bytes-param-mismatch-e8010 (luajit): the parameter "
            + "cell's inner reason follows the artifact's serialized kind template");
    }

    /** The sync return cell of the loaded host wrapper (the kind inner reason). */
    private static void wrapperSyncReturnKindSite(Compiled compiled) throws Exception {
        SidecarExpectations.ErrorExpectation pin = luajitPin(compiled.spec());
        check(pin != null, "host-abi/host-bad-return: the sidecar pins an error");
        if (pin == null) {
            return;
        }
        String drive = "";
        WrapperPin valid = wrapperPin(FailureArmId.HOST_SYNC_RETURN_CELL,
            Map.of("inner", FailureProjections.kindReason(
                RuntimeDescriptor.Int.INSTANCE)),
            "int", "string", artifactOrigin(compiled, pin), "\"not a number\"",
            false, FailureArmId.TYPED_BOUNDARY_KIND, "int");
        checkOutput(runWrapperProbe(compiled, Map.of(), artifactPath(compiled),
            wrapperRecorder(), drive, wrapperTuple(valid)),
            "host-abi/host-bad-return (luajit): the wrapper's sync return cell "
            + "publishes the registry's tuple with the matcher-selected kind inner");
        checkOutput(runWrapperProbe(compiled, Map.of(), artifactPath(compiled),
            wrapperRecorder(null, "  realInner(id, values)\n"
                + "  return \"a composed reason\"\n"), drive,
            wrapperDefect("HOST_SYNC_RETURN_CELL",
                "closed inner-reason vocabulary")),
            "host-abi/host-bad-return (luajit): the sync return cell rejects a "
            + "composed inner reason and publishes no tuple");
        String mutated = "return cell {inner}";
        Path patched = patchedArtifact(compiled,
            armEntry(FailureArmId.HOST_SYNC_RETURN_CELL),
            mutatedEntry(FailureArmId.HOST_SYNC_RETURN_CELL, mutated));
        checkOutput(runWrapperProbe(compiled, Map.of(), patched.toString(),
            wrapperRecorder(), drive, wrapperTuple(valid.withMessage(
                "return cell expected int"))),
            "host-abi/host-bad-return (luajit): the wrapper's sync return cell "
            + "follows the artifact's serialized outer template");
    }

    /**
     * The sync return cell with a fixed-text inner-only reason: the raw host
     * returns a malformed UTF-8 string, so the deployed matcher selects the
     * closed string-carrier arm and the artifact renders its template.
     */
    private static void wrapperStringCarrierSite(Compiled compiled) throws Exception {
        SidecarExpectations.ErrorExpectation pin = luajitPin(compiled.spec());
        check(pin != null, "host-abi/host-invalid-utf8-e8010: the sidecar pins "
            + "an error");
        if (pin == null) {
            return;
        }
        String drive = "";
        WrapperPin valid = wrapperPin(FailureArmId.HOST_SYNC_RETURN_CELL,
            Map.of("inner", FailureContractRegistry.renderInner(
                FailureArmId.HOST_STRING_INVALID_UTF8, Map.of())),
            "string", "string", artifactOrigin(compiled, pin), null, false,
            FailureArmId.HOST_STRING_INVALID_UTF8, null);
        checkOutput(runWrapperProbe(compiled, Map.of(), artifactPath(compiled),
            wrapperRecorder(), drive, wrapperTuple(valid)),
            "host-abi/host-invalid-utf8-e8010 (luajit): the wrapper's sync return "
            + "cell renders the matcher-selected string-carrier inner arm");
        checkOutput(runWrapperProbe(compiled, Map.of(), artifactPath(compiled),
            wrapperRecorder("    return realArm(id, {}, file, line, column, "
                + "expected, subject)\n"), drive,
            wrapperDefect("HOST_SYNC_RETURN_CELL", "supplied 0")),
            "host-abi/host-invalid-utf8-e8010 (luajit): the sync return cell "
            + "rejects a missing named parameter and publishes no tuple");
        String mutated = "malformed host string";
        Path patched = patchedArtifact(compiled,
            armEntry(FailureArmId.HOST_STRING_INVALID_UTF8),
            mutatedEntry(FailureArmId.HOST_STRING_INVALID_UTF8, mutated));
        checkOutput(runWrapperProbe(compiled, Map.of(), patched.toString(),
            wrapperRecorder(), drive, wrapperTuple(valid.withMessage(
                "return value 1 type mismatch: malformed host string"))),
            "host-abi/host-invalid-utf8-e8010 (luajit): the wrapper's inner "
            + "reason follows the artifact's serialized inner-only template");
    }

    /** The no-value return cell of the loaded host wrapper. */
    private static void wrapperNothingSite(Compiled compiled) throws Exception {
        SidecarExpectations.ErrorExpectation pin = luajitPin(compiled.spec());
        check(pin != null, "host-abi/host-empty-return-bad: the sidecar pins an "
            + "error");
        if (pin == null) {
            return;
        }
        String drive = "";
        WrapperPin valid = wrapperPin(FailureArmId.HOST_SYNC_RETURN_NOTHING,
            Map.of("expected", "int"), "int", "nothing",
            artifactOrigin(compiled, pin), "nil", false, null, null);
        checkOutput(runWrapperProbe(compiled, Map.of(), artifactPath(compiled),
            wrapperRecorder(), drive, wrapperTuple(valid)),
            "host-abi/host-empty-return-bad (luajit): the wrapper's no-value "
            + "return cell publishes the registry's nothing tuple");
        checkOutput(runWrapperProbe(compiled, Map.of(), artifactPath(compiled),
            wrapperRecorder("    local mutated = {}\n"
                + "    for k, v in pairs(values) do mutated[k] = v end\n"
                + "    mutated.extra = \"x\"\n"
                + "    return realArm(id, mutated, file, line, column, expected, "
                + "subject)\n"), drive,
            wrapperDefect("HOST_SYNC_RETURN_NOTHING", "extra parameter")),
            "host-abi/host-empty-return-bad (luajit): the no-value cell rejects "
            + "an extra named parameter and publishes no tuple");
        String mutated = "nothing returned where {expected} was declared";
        Path patched = patchedArtifact(compiled,
            armEntry(FailureArmId.HOST_SYNC_RETURN_NOTHING),
            mutatedEntry(FailureArmId.HOST_SYNC_RETURN_NOTHING, mutated));
        checkOutput(runWrapperProbe(compiled, Map.of(), patched.toString(),
            wrapperRecorder(), drive, wrapperTuple(valid.withMessage(
                "nothing returned where int was declared"))),
            "host-abi/host-empty-return-bad (luajit): the wrapper's no-value "
            + "cell follows the artifact's serialized outer template");
    }

    /**
     * The inner-template authority control at the mapped kind site: with the
     * artifact's serialized {@code TYPED_BOUNDARY_KIND} template replaced,
     * the deployed matcher's own composed text ("expected bytes") is outside
     * the replaced closed vocabulary, so a consumer forwarding that text
     * would fail closed instead of publishing the tuple — the produced
     * message can only come from the serialized template.
     */
    private static void wrapperInnerTemplateAuthority(Compiled compiled) throws Exception {
        String drive = "local surface = __exportSurfaces[\"host.bytes_roundtrip\"]\n"
            + "if surface == nil then fail(\"no host surface\") end\n"
            + "return pcall(surface.echoBytes.f, 5, \"probe.deal\", 3, 4)\n";
        String mutated = "the declared kind is {kind}";
        Path patched = patchedArtifact(compiled,
            armEntry(FailureArmId.TYPED_BOUNDARY_KIND),
            mutatedEntry(FailureArmId.TYPED_BOUNDARY_KIND, mutated));
        WrapperPin replaced = wrapperPin(FailureArmId.HOST_PARAMETER_CELL,
            Map.of("index", "1", "inner", FailureProjections.kindReason(
                RuntimeDescriptor.Bytes.INSTANCE)),
            "bytes", "number", "probe.deal:3:4", "5", true,
            FailureArmId.TYPED_BOUNDARY_KIND, "bytes")
            .withMessage("parameter 1 type mismatch: the declared kind is bytes");
        checkOutput(runWrapperProbe(compiled, Map.of(), patched.toString(),
            wrapperRecorder(), drive, wrapperTuple(replaced)),
            "host-abi/host-bytes-param-mismatch-e8010 (luajit): the inner "
            + "template is the text authority — the replaced serialized template "
            + "renders the published message");
        checkOutput(runWrapperProbe(compiled, Map.of(), patched.toString(),
            wrapperRecorder(null, "  return \"expected bytes\"\n"), drive,
            wrapperDefect("HOST_PARAMETER_CELL",
                "closed inner-reason vocabulary")),
            "host-abi/host-bytes-param-mismatch-e8010 (luajit): the stale matcher "
            + "text no longer passes once the serialized template is replaced "
            + "(no copied literal can stand in for the arm)");
        checkOutput(runWrapperProbe(compiled, Map.of(), artifactPath(compiled),
            wrapperRecorder(null, "  return \"the declared kind is bytes\"\n"),
            drive, wrapperDefect("HOST_PARAMETER_CELL",
                "closed inner-reason vocabulary")),
            "host-abi/host-bytes-param-mismatch-e8010 (luajit): the replaced "
            + "template's text is rejected against the unmodified artifact's "
            + "serialized vocabulary");
    }


    /**
     * One production-wrapper site drive (canonical failure-projection
     * authority P1/P4; ISSUE-0696 remediation): the artifact is loaded, the
     * deployed runtime's two host-arm renderers are recorded and forwarded
     * unchanged, the drive body triggers the mapped failure, and the
     * assertion body inspects the captured error and the recorded
     * delegation. The probe ends with {@code PROBE-OK} only when every
     * assertion holds.
     */
    private static String runWrapperProbe(Compiled compiled,
            Map<String, String> hostOverrides, String artifact, String install,
            String drive, String assertions) throws Exception {
        String entryModule = compiled.checkedProject().entryModule().path();
        // A DRIVER case's own entry main carries the fixture's probe call, so
        // the drive is the entry main itself; every other entry runs its
        // module init first (the artifact publishes the host surface there)
        // and the drive body triggers the site explicitly.
        boolean entryDrives = compiled.spec().entry() == Entry.DRIVER;
        String init = entryDrives ? "" : """
            local ok, err = __dealMain()
            if not ok then
              local why = type(err) == "table"
                and (tostring(err.code) .. ": " .. tostring(err.m)) or tostring(err)
              fail("the init failed: " .. why)
            end
            """;
        String capture = entryDrives ? "return __dealMain()\n" : drive;
        String template = """
            local function fail(m) print("PROBE-FAIL: " .. tostring(m)) os.exit(1) end
            dofile(__ARTIFACT__)
            local rt = require("deal.runtime")
            local realInner = rt.__hostInnerArm
            local realArm = rt.__hostArm
            if realInner == nil or realArm == nil then
              fail("the artifact registered no host arm renderer")
            end
            local innerCalls, armCalls = {}, {}
            __INSTALL__
            __INIT__
            local ok2, err2 = (function()
            __DRIVE__
            end)()
            if ok2 then fail("the mapped failure did not fire") end
            __ASSERTIONS__
            print("PROBE-OK")
            """;
        String probe = template
            .replace("__ARTIFACT__", luaString(artifact))
            .replace("__INSTALL__", install)
            .replace("__INIT__", init)
            .replace("__DRIVE__", capture)
            .replace("__ASSERTIONS__", assertions)
            .replace("__ENTRY__", luaString(entryModule));
        return luaArtifactOutput(compiled, hostOverrides, probe);
    }

    /** The recording renderer installation of one wrapper probe. */
    private static String wrapperRecorder() {
        return wrapperRecorder(null, null);
    }

    /** The recording renderer installation with a mutated outer forward. */
    private static String wrapperRecorder(String armForward) {
        return wrapperRecorder(armForward, null);
    }

    /**
     * The recording renderer installation, with the caller's own Lua bodies
     * spliced in: {@code armForward} replaces the outer renderer's forward
     * statement (an invalid-binding injection) and {@code innerForward}
     * replaces the inner renderer's return (a foreign-inner injection). An
     * absent body keeps the identity forward.
     */
    private static String wrapperRecorder(String armForward, String innerForward) {
        String armBody = armForward == null
            ? "    return realArm(id, values, file, line, column, expected, subject)\n"
            : armForward;
        String innerBody = innerForward == null
            ? "  return realInner(id, values)\n"
            : innerForward;
        return "rt.__hostInnerArm = function(id, values)\n"
            + "  innerCalls[#innerCalls + 1] = {id = id, values = values}\n"
            + innerBody
            + "end\n"
            + "rt.__hostArm = function(id, values, file, line, column, expected, "
            + "subject)\n"
            + "  armCalls[#armCalls + 1] = {id = id, values = values, file = file, "
            + "line = line, column = column, expected = expected, subject = subject}\n"
            + armBody
            + "end\n";
    }

    /** One wrapper probe's fail-closed assertion: a named defect, no DEAL tuple. */
    private static String wrapperDefect(String... fragments) {
        StringBuilder out = new StringBuilder();
        out.append("if type(err2) == \"table\" and err2.__d then fail(\"a DEAL tuple "
            + "was published: \" .. tostring(err2.m)) end\n");
        out.append("if type(err2) ~= \"string\" then fail(\"the fail-closed report: \" "
            + ".. tostring(err2)) end\n");
        for (String fragment : fragments) {
            out.append("if string.find(err2, ").append(luaString(fragment))
                .append(", 1, true) == nil then fail(\"the defect report: \" .. "
                    + "tostring(err2)) end\n");
        }
        return out.toString();
    }

    /** One valid wrapper-site expectation (the registry's own render). */
    private record WrapperPin(String armId, String code, String message,
                              String expected, String actual, String origin,
                              String subjectLua, boolean index, String innerArmId,
                              String innerKind) {

        WrapperPin withMessage(String mutatedMessage) {
            return new WrapperPin(armId, code, mutatedMessage, expected, actual,
                origin, subjectLua, index, innerArmId, innerKind);
        }
    }

    /** The registry render of one mapped wrapper site at the supplied origin. */
    private static WrapperPin wrapperPin(FailureArmId id, Map<String, String> values,
            String expected, String actual, String origin, String subjectLua,
            boolean index, FailureArmId innerArm, String innerKind) {
        BoundaryFailure arm = FailureContractRegistry.render(id, values, expected,
            actual, null);
        return new WrapperPin(id.name(), arm.code().name(), arm.message(),
            arm.expected(), arm.actual(), origin, subjectLua, index,
            innerArm == null ? null : innerArm.name(), innerKind);
    }

    /**
     * The artifact's own origin text of one pinned call cell: the mirror
     * source path of the fixture and the pinned raw corpus line rebased onto
     * the artifact's line.
     */
    private static String artifactOrigin(Compiled compiled,
            SidecarExpectations.ErrorExpectation pin) {
        int originLine = pin.line() - compiled.strippedByCorpusPath()
            .getOrDefault(compiled.spec().fixtureFile(), 0);
        return compiled.mirror().resolve(compiled.spec().fixtureFile()).toString()
            + ":" + originLine + ":" + pin.column();
    }

    /**
     * The valid control's assertion block: the captured error is the closed
     * arm's registry render at the supplied origin, and the recorded
     * delegation carries the arm id, the declared expected descriptor, the
     * raw failing subject, the supplied origin operands, and — where the
     * site has one — the structured inner-arm selection the deployed
     * matcher raised (never a text).
     */
    private static String wrapperTuple(WrapperPin pin) {
        StringBuilder out = new StringBuilder();
        out.append("if type(err2) ~= \"table\" or err2.__d ~= true then "
            + "fail(\"the carrier: \" .. tostring(err2)) end\n");
        out.append("if err2.code ~= ").append(luaString(pin.code()))
            .append(" then fail(\"the code: \" .. tostring(err2.code)) end\n");
        out.append("if err2.m ~= ").append(luaString(pin.message()))
            .append(" then fail(\"the message: \" .. tostring(err2.m)) end\n");
        out.append("if err2.o ~= ").append(luaString(pin.origin()))
            .append(" then fail(\"the origin: \" .. tostring(err2.o)) end\n");
        out.append("if err2.e ~= ").append(luaLiteral(pin.expected()))
            .append(" then fail(\"the expected field: \" .. tostring(err2.e)) end\n");
        out.append("if err2.a ~= ").append(luaLiteral(pin.actual()))
            .append(" then fail(\"the actual field: \" .. tostring(err2.a)) end\n");
        out.append("if #armCalls ~= 1 then fail(\"the arm renderer calls: \" .. "
            + "tostring(#armCalls)) end\n");
        out.append("local c = armCalls[1]\n");
        out.append("if c.id ~= ").append(luaString(pin.armId()))
            .append(" then fail(\"the arm id: \" .. tostring(c.id)) end\n");
        out.append("if c.expected ~= ").append(luaLiteral(pin.expected()))
            .append(" then fail(\"the declared expected operand: \" .. "
                + "tostring(c.expected)) end\n");
        if (pin.subjectLua() == null) {
            out.append("if type(c.subject) ~= \"string\" then fail(\"the failing "
                + "subject: \" .. tostring(c.subject)) end\n");
        } else {
            out.append("if c.subject ~= ").append(pin.subjectLua())
                .append(" then fail(\"the failing subject: \" .. "
                    + "tostring(c.subject)) end\n");
        }
        out.append("if tostring(c.file) .. \":\" .. tostring(c.line) .. \":\" .. "
            + "tostring(c.column) ~= ").append(luaString(pin.origin()))
            .append(" then fail(\"the origin operands: \" .. tostring(c.file) .. \":\" "
                + ".. tostring(c.line) .. \":\" .. tostring(c.column)) end\n");
        if (pin.index()) {
            out.append("if c.values.index ~= 1 then fail(\"the parameter index: \" .. "
                + "tostring(c.values.index)) end\n");
        }
        if (pin.innerArmId() != null) {
            out.append("if #innerCalls ~= 1 then fail(\"the inner renderer calls: \" "
                + ".. tostring(#innerCalls)) end\n");
            out.append("local i = innerCalls[1]\n");
            out.append("if i.id ~= ").append(luaString(pin.innerArmId()))
                .append(" then fail(\"the inner arm selection: \" .. tostring(i.id)) "
                    + "end\n");
            if (pin.innerKind() != null) {
                out.append("if i.values.kind ~= ").append(luaString(pin.innerKind()))
                    .append(" then fail(\"the inner kind operand: \" .. "
                        + "tostring(i.values.kind)) end\n");
            }
        } else {
            out.append("if #innerCalls ~= 0 then fail(\"unexpected inner renderer "
                + "calls: \" .. tostring(#innerCalls)) end\n");
        }
        return out.toString();
    }

    /** One Lua literal: nil for an absent field, else the quoted text. */
    private static String luaLiteral(String text) {
        return text == null ? "nil" : luaString(text);
    }

    /** The serialized arm entry prefix of one arm in the emitted arm table. */
    private static String armEntry(FailureArmId id) {
        FailureArm arm = FailureContractRegistry.arm(id);
        return "[" + luaString(id.name()) + "] = {c=" + luaString(
            FailureContractRegistry.codeOf(arm)) + ", t="
            + luaString(arm.template());
    }

    /** The serialized arm entry prefix of one arm with a replaced template. */
    private static String mutatedEntry(FailureArmId id, String template) {
        String entry = armEntry(id);
        return entry.substring(0, entry.length() - luaString(
            FailureContractRegistry.arm(id).template()).length())
            + luaString(template);
    }

    /** One artifact copy with the serialized arm entry replaced. */
    private static Path patchedArtifact(Compiled compiled, String needle,
            String replacement) throws Exception {
        Path artifact = Path.of(artifactPath(compiled));
        String source = Files.readString(artifact, StandardCharsets.UTF_8);
        check(source.contains(needle), compiled.spec().what()
            + ": the artifact carries the serialized arm entry " + needle);
        Path patched = artifact.getParent().resolve("patched_artifact.lua");
        Files.writeString(patched, source.replace(needle, replacement),
            StandardCharsets.UTF_8);
        return patched;
    }

    /** Fails the check unless the probe completed all its assertions. */
    private static void checkOutput(String output, String message) {
        check(output.contains("PROBE-OK"), message + ": " + output);
    }

    /** The staged LuaJIT artifact path of one compiled case. */
    private static String artifactPath(Compiled compiled) {
        return compiled.root().resolve("out").resolve(
            compiled.checkedProject().entryModule().path().replace('.', '/')
                + ".lua").toAbsolutePath().toString();
    }

    /**
     * The oracle's scripted invalid-handle drives of the async shape
     * fixture: the returned value's own carrier reaches the closed
     * {@code ASYNC_SHAPE} arm through the shared host-carrier projection,
     * never a substituted token. The corpus cell already drives the integer
     * carrier the corpus host returns ({@code Long.valueOf(42L)} on the JVM
     * host surface), and these drives pin the classification of the integer
     * and of every nonnumeric table-carried carrier against the arm's own
     * render: a plain table, an array, a bytes buffer and the DEAL
     * language-null sentinel all project the single {@code table} token the
     * two target host classifiers project (never the typed-boundary
     * {@code table}/{@code array}/{@code bytes}/{@code null} refinement).
     */
    private static void assertAsyncHandleCarriers(Compiled compiled,
            SidecarExpectations.ErrorExpectation pin) {
        assertScriptedAsyncHandle(compiled, pin, new SemanticOracle.Value.IntValue(42),
            "number", "integer carrier");
        assertScriptedAsyncHandle(compiled, pin,
            new SemanticOracle.Value.TableValue(new LinkedHashMap<>()), "table",
            "table carrier");
        assertScriptedAsyncHandle(compiled, pin, new SemanticOracle.Value.ArrayValue(
                new ArrayList<>(), RuntimeDescriptor.parseCanonicalText("[string]"),
                false),
            "table", "array carrier");
        assertScriptedAsyncHandle(compiled, pin, new SemanticOracle.Value.BytesValue(0),
            "table", "bytes carrier");
        assertScriptedAsyncHandle(compiled, pin, SemanticOracle.Value.NullValue.INSTANCE,
            "table", "language-null carrier");
    }

    /**
     * One scripted invalid-handle drive of the async shape fixture: the
     * returned carrier, the closed arm's own render for the projected
     * token, and the pinned call origin, all from the oracle's real program
     * drive.
     */
    private static void assertScriptedAsyncHandle(Compiled compiled,
            SidecarExpectations.ErrorExpectation pin, SemanticOracle.Value value,
            String actualToken, String label) {
        Tuple driven = oracleTupleWith(compiled, new SemanticOracle.HostResponder() {
            @Override
            public AsyncStartOutcome startAsync(ModuleId module, String export,
                    RuntimeDescriptor.Func descriptor,
                    List<SemanticOracle.Value> args, String operationLabel) {
                return new AsyncStartOutcome.InvalidHandle(value);
            }
        });
        String where = compiled.spec().what() + " (oracle, " + label + ")";
        BoundaryFailure arm = FailureContractRegistry.render(FailureArmId.ASYNC_SHAPE,
            Map.of("actual", actualToken), "async operation", actualToken, null);
        checkEq(arm.code().name(), driven.code(), where + ": the E8010 code");
        checkEq(arm.message(), driven.message(), where
            + ": the closed ASYNC_SHAPE arm's message");
        checkEq(arm.expected(), driven.expected(), where
            + ": the closed ASYNC_SHAPE arm's expected token");
        checkEq(arm.actual(), driven.actual(), where
            + ": the returned carrier's shared host-carrier projection");
        checkEq(pin.sourceFile(), driven.file(), where
            + ": the pinned call-origin file");
        checkEq(pin.line(), driven.line(), where + ": the pinned call-origin line");
        checkEq(pin.column(), driven.column(), where
            + ": the pinned call-origin column");
    }

    /**
     * The cyclic host carriers of the async shape fixture: the Lua host
     * returns a table that carries itself, and the JVM host returns a
     * {@code JvmRuntime.Table} whose sole entry is the table itself. Both
     * target host classifiers project the outer carrier kind ({@code
     * table}) without walking the cycle.
     */
    private static final String CYCLIC_HOST_LUA = """
        local t = {}
        t[1] = t
        return {
          fetchValue = function()
            return t
          end,
        }
        """;

    private static final String CYCLIC_HOST_JAVA = """
        final class HostAsync_shape_bad {
          public static Object fetchValue() {
            deal.codegen.jvm.JvmRuntime.Table t =
                new deal.codegen.jvm.JvmRuntime.Table();
            t.write("self", t);
            return t;
          }
        }
        """;

    /**
     * The cyclic invalid-handle regression of the async shape fixture: a
     * reference-cyclic array is a legal host carrier, so the oracle's
     * program drive must classify the returned value's outer carrier kind
     * shallowly and render the complete {@code ASYNC_SHAPE} tuple, and
     * both production artifacts driven with the corresponding cyclic host
     * carriers must render the identical tuple. The classification
     * materialized array element views before this drive existed, so the
     * cyclic carrier diverged from production and overflowed the walk
     * instead of reaching the closed arm.
     */
    private static void assertCyclicAsyncHandle(Compiled compiled,
            SidecarExpectations.ErrorExpectation pin) throws Exception {
        List<SemanticOracle.Value> elements = new ArrayList<>();
        SemanticOracle.Value cycle = new SemanticOracle.Value.ArrayValue(elements,
            RuntimeDescriptor.parseCanonicalText("[string]"), false);
        elements.add(cycle);
        Tuple oracle = oracleTupleWith(compiled, new SemanticOracle.HostResponder() {
            @Override
            public AsyncStartOutcome startAsync(ModuleId module, String export,
                    RuntimeDescriptor.Func descriptor,
                    List<SemanticOracle.Value> args, String operationLabel) {
                return new AsyncStartOutcome.InvalidHandle(cycle);
            }
        });
        String where = compiled.spec().what()
            + " (oracle, cyclic array carrier)";
        checkEq("E8010", oracle.code(), where + ": the E8010 code");
        checkEq("host async function must return an async operation, got table",
            oracle.message(), where + ": the table message");
        checkEq("async operation", oracle.expected(), where
            + ": the expected token");
        checkEq("table", oracle.actual(), where + ": the shallow table actual");
        checkEq(pin.sourceFile(), oracle.file(), where
            + ": the pinned call-origin file");
        checkEq(pin.line(), oracle.line(), where
            + ": the pinned call-origin line");
        checkEq(pin.column(), oracle.column(), where
            + ": the pinned call-origin column");
        Tuple lua = luaArtifactWithHost(compiled, "async_shape_bad",
            CYCLIC_HOST_LUA);
        checkEq(null, firstDifferingField(oracle, lua), compiled.spec().what()
            + ": the oracle and the LuaJIT artifact render the identical "
            + "cyclic-carrier tuple");
        Tuple jvm = jvmArtifactWithHost(compiled, "async_shape_bad",
            CYCLIC_HOST_JAVA);
        checkEq(null, firstDifferingField(oracle, jvm), compiled.spec().what()
            + ": the oracle and the JVM artifact render the identical "
            + "cyclic-carrier tuple");
    }

    /**
     * The numeric-boundary invalid-handle regressions of the async shape
     * fixture: immediately inside and outside both signed32 boundaries the
     * integer host carrier is a plain numeric carrier, so the oracle's
     * program drive must classify the returned value {@code number}
     * without applying the typed-boundary signed32 payload validation, and
     * both production artifacts driven with the corresponding host
     * carriers (a Lua number and a JVM {@code Long}) must render the
     * identical complete tuple. The pre-correction classification
     * validated the {@code IntValue} payload before the closed arm's own
     * render, so the out-of-signed32 drives threw
     * {@code IllegalArgumentException} instead of producing the DEAL
     * failure.
     */
    private static void assertNumericAsyncHandleBoundaries(Compiled compiled,
            SidecarExpectations.ErrorExpectation pin) throws Exception {
        for (long value : new long[] { 2147483647L, 2147483648L, -2147483648L,
                -2147483649L }) {
            String where = compiled.spec().what() + " (oracle, integer carrier "
                + value + ")";
            Tuple oracle = oracleTupleWith(compiled,
                new SemanticOracle.HostResponder() {
                    @Override
                    public AsyncStartOutcome startAsync(ModuleId module,
                            String export, RuntimeDescriptor.Func descriptor,
                            List<SemanticOracle.Value> args,
                            String operationLabel) {
                        return new AsyncStartOutcome.InvalidHandle(
                            new SemanticOracle.Value.IntValue(value));
                    }
                });
            checkEq("E8010", oracle.code(), where + ": the E8010 code");
            checkEq("host async function must return an async operation, got number",
                oracle.message(), where + ": the number message");
            checkEq("async operation", oracle.expected(), where
                + ": the expected token");
            checkEq("number", oracle.actual(), where
                + ": the numeric carrier's shared host-carrier projection");
            checkEq(pin.sourceFile(), oracle.file(), where
                + ": the pinned call-origin file");
            checkEq(pin.line(), oracle.line(), where
                + ": the pinned call-origin line");
            checkEq(pin.column(), oracle.column(), where
                + ": the pinned call-origin column");
            Tuple lua = luaArtifactWithHost(compiled, "async_shape_bad",
                numericHostLua(value));
            checkEq(null, firstDifferingField(oracle, lua), where
                + ": the oracle and the LuaJIT artifact render the identical "
                + "tuple");
            Tuple jvm = jvmArtifactWithHost(compiled, "async_shape_bad",
                numericHostJava(value));
            checkEq(null, firstDifferingField(oracle, jvm), where
                + ": the oracle and the JVM artifact render the identical "
                + "tuple");
        }
    }

    /** The Lua host carrier of one numeric-boundary drive: a plain number. */
    private static String numericHostLua(long value) {
        return """
            return {
              fetchValue = function()
                return %d
              end,
            }
            """.formatted(value);
    }

    /** The JVM host carrier of one numeric-boundary drive: a boxed long. */
    private static String numericHostJava(long value) {
        return """
            final class HostAsync_shape_bad {
              public static Object fetchValue() {
                return java.lang.Long.valueOf(%dL);
              }
            }
            """.formatted(value);
    }

    /**
     * The differential harness's null-label regression of the async shape
     * fixture: {@link SemanticDifferentialHarness.AsyncHostScript} documents
     * a null bound label as the bad-handle terminal (the two target seams
     * return {@code nil}/{@code null} alike), so all three consumers must
     * reach the complete canonical absent-handle tuple. The oracle responder
     * previously constructed a non-null operation label unconditionally and
     * threw before the comparison ran.
     */
    private static void assertHarnessNullLabelAbsentHandle(Compiled compiled,
            SidecarExpectations.ErrorExpectation pin) throws Exception {
        Path workspace = compiled.root().resolve("harness-absent-handle");
        SemanticDifferentialHarness.Expectation expectation =
            SemanticDifferentialHarness.Expectation.failure(
                "the async shape fixture's null-label absent handle",
                List.of("host.async_shape_bad.fetchValue"), "E8010", null);
        SemanticDifferentialHarness.AsyncHostScript script =
            new SemanticDifferentialHarness.AsyncHostScript(null,
                new SemanticDifferentialHarness.AsyncHostCompletion.Returned(
                    new SemanticDifferentialHarness.CallbackArg.Str("unused")));
        SemanticDifferentialHarness.Verdict verdict =
            SemanticDifferentialHarness.runAsyncEntry(compiled.project(),
                compiled.tables(), "test_host_async_shape_bad", List.of(), expectation,
                workspace, script);
        checkEq(3, verdict.runs().size(), compiled.spec().what()
            + " (harness, null label): the three consumers run: "
            + verdict.failures());
        check(verdict.pass(), compiled.spec().what()
            + " (harness, null label): the three-consumer matrix passes: "
            + verdict.failures());
        List<Tuple> tuples = new ArrayList<>();
        for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
            if (!(run.terminal() instanceof
                    SemanticRuntimeModel.Terminal.DealFailure failure)) {
                fail(compiled.spec().what() + " (harness, null label, "
                    + run.consumer() + "): the drive reaches the absent handle: "
                    + run.terminal());
                return;
            }
            Tuple tuple = tupleOf(failure.error(), compiled,
                compiled.spec().fixtureFile());
            String where = compiled.spec().what() + " (harness, null label, "
                + run.consumer() + ")";
            checkEq("E8010", tuple.code(), where + ": the E8010 code");
            checkEq("host async function must return an async operation, got nothing",
                tuple.message(), where + ": the nothing message");
            checkEq("async operation", tuple.expected(), where
                + ": the expected token");
            checkEq("nothing", tuple.actual(), where
                + ": the absent handle's actual token");
            checkEq(pin.sourceFile(), tuple.file(), where
                + ": the pinned call-origin file");
            checkEq(pin.line(), tuple.line(), where
                + ": the pinned call-origin line");
            checkEq(pin.column(), tuple.column(), where
                + ": the pinned call-origin column");
            tuples.add(tuple);
        }
        for (int i = 1; i < tuples.size(); i++) {
            checkEq(null, firstDifferingField(tuples.get(0), tuples.get(i)),
                compiled.spec().what() + " (harness, null label): the "
                    + verdict.runs().get(0).consumer() + " and the "
                    + verdict.runs().get(i).consumer()
                    + " consumers render the identical tuple");
        }
    }

    /**
     * One host return cell through the oracle's closed boundary engine:
     * the identical code, message, expected, and actual the two production
     * artifacts publish for the same arm and values.
     */
    private static void assertOracleCell(Case spec, Tuple artifact,
            FailurePolicyId policy, RuntimeDescriptor descriptor,
            BoundaryValueView view, BoundaryContext context) {
        BoundaryOutcome outcome = BoundaryExecutor.check(policy, descriptor, view,
            context);
        if (!(outcome instanceof BoundaryOutcome.Fail fail)) {
            fail(spec.what() + " (oracle): the boundary cell fails: " + outcome);
            return;
        }
        BoundaryFailure failure = fail.failure();
        Tuple cell = new Tuple(failure.code().name(), failure.message(), null, null,
            null, failure.expected(), failure.actual());
        checkEq(artifact.code(), cell.code(), spec.what()
            + ": the oracle and the artifacts render the identical code");
        checkEq(artifact.message(), cell.message(), spec.what()
            + ": the oracle and the artifacts render the identical message");
        checkEq(artifact.expected(), cell.expected(), spec.what()
            + ": the oracle and the artifacts render the identical expected field");
        checkEq(artifact.actual(), cell.actual(), spec.what()
            + ": the oracle and the artifacts render the identical actual field");
    }

    // =========================================================================
    // 2b. The declaration-owned annotation index fails closed
    // =========================================================================

    /**
     * A declared callee with no recorded parameter annotation fails closed:
     * the production entry stages no project and reports exactly one E6005
     * naming the missing annotation (never a fallback to the call span).
     * The un-doctored project is the control.
     */
    private static void testMissingDeclaredAnnotationFailsClosed() {
        System.out.println("-- a declared callee with no recorded annotation fails "
            + "closed --");
        String source = """
            function needInt(x: int): int {
              return x
            }

            export function main(): null {
              let r: int = needInt(1)
              return null
            }
            """;
        SemanticLowerer.ProjectLoweringResult control = lowerDoctored(source,
            "needInt", null);
        check(control != null && control.project() != null,
            "the declared callee with its annotation lowers cleanly: "
                + (control == null ? "null" : control.diagnostics()));
        SemanticLowerer.ProjectLoweringResult doctored = lowerDoctored(source,
            "needInt", params -> List.of(new deal.ast.Parameter(params.get(0).span(),
                params.get(0).name(), null)));
        check(doctored != null && doctored.project() == null,
            "a null-typed declared annotation stages no project: "
                + (doctored == null ? "null" : doctored.diagnostics()));
        if (doctored == null || doctored.project() != null) {
            return;
        }
        checkEq(1, doctored.diagnostics().size(),
            "exactly one diagnostic for the missing annotation");
        if (doctored.diagnostics().isEmpty()) {
            return;
        }
        deal.diagnostics.CompilerDiagnostic diagnostic = doctored.diagnostics().get(0);
        checkEq("E6005", diagnostic.code(), "the missing annotation's diagnostic code");
        check(diagnostic.message().contains("no recorded type annotation"),
            "the defect names the missing annotation: " + diagnostic.message());
    }

    /**
     * The production entry over a checked project whose named declared
     * callee's parameter list is doctored ({@code null} doctor means no
     * doctor — the control). Only annotation facts are doctored; the
     * checks, the symbol table, and the interface index stay the checked
     * project's own.
     */
    private static SemanticLowerer.ProjectLoweringResult lowerDoctored(String source,
            String calleeName,
            java.util.function.UnaryOperator<List<deal.ast.Parameter>> doctor) {
        deal.lexer.LexResult lexed = new deal.lexer.Lexer(source, MATRIX_SOURCE_ID)
            .tokenize();
        check(lexed.diagnostics().isEmpty(), "the control source lexes");
        deal.parser.ParseResult parsed = new deal.parser.Parser(lexed.tokens(),
            MATRIX_SOURCE_ID).parse();
        check(parsed.diagnostics().isEmpty(), "the control source parses");
        deal.checker.ModuleResolver resolver = new deal.checker.ModuleResolver() {
            @Override
            public Map<String, deal.types.Type> resolveModule(String modulePath,
                    String importingModule, Set<String> modulesInProgress)
                    throws deal.checker.ModuleResolver.ModuleNotFoundException {
                throw new deal.checker.ModuleResolver.ModuleNotFoundException(
                    "Module not found: " + modulePath);
            }

            @Override
            public deal.checker.Symbol.ClassSymbol resolveClassSymbol(String className,
                    String modulePath, String importingModule)
                    throws deal.checker.ModuleResolver.ModuleNotFoundException {
                return null;
            }
        };
        deal.checker.NameResolver nameResolver = new deal.checker.NameResolver(
            MATRIX_SOURCE_ID, resolver);
        deal.checker.SymbolTable symbols = nameResolver.resolve(parsed.program());
        check(nameResolver.diagnostics().isEmpty(), "the control source resolves");
        deal.checker.CheckResult checks = deal.checker.TypeChecker.check(MATRIX_SOURCE_ID,
            symbols, nameResolver, parsed.program());
        check(checks.diagnostics().isEmpty(), "the control source checks");
        deal.ast.ProgramNode program = parsed.program();
        if (doctor != null) {
            List<deal.ast.StatementNode> statements = new ArrayList<>();
            boolean found = false;
            for (deal.ast.StatementNode statement : program.statements()) {
                if (statement instanceof deal.ast.FunctionDeclaration function
                        && function.name().equals(calleeName)) {
                    found = true;
                    statements.add(new deal.ast.FunctionDeclaration(function.span(),
                        function.name(), doctor.apply(function.params()),
                        function.returnType(), function.body(), function.isAsync(),
                        function.isExternal()));
                } else {
                    statements.add(statement);
                }
            }
            check(found, "the callee '" + calleeName + "' is declared");
            program = new deal.ast.ProgramNode(program.span(), statements,
                program.fileDirectives());
        }
        CompilerInvocation invocation = productionInvocation();
        deal.semantic.CheckedModuleInput input = new deal.semantic.CheckedModuleInput(
            MATRIX_MODULE, MATRIX_SOURCE_ID, Path.of(MATRIX_SOURCE_ID), program,
            checks, List.of(), List.of(), deal.semantic.CheckedModuleKind.IMPLEMENTATION);
        deal.semantic.ModuleFact fact = new deal.semantic.ModuleFact(MATRIX_SOURCE_ID,
            MATRIX_MODULE, false, false, program, Map.of(), symbols, checks, List.of());
        CheckedProjectBuildResult built = deal.semantic.CheckedProjectBuilder.build(
            invocation, MATRIX_MODULE, List.of(fact));
        if (built == null || built.hasErrors() || built.input() == null) {
            fail("the control checked project builds: "
                + (built == null ? "null" : built.diagnostics()));
            return null;
        }
        RequirementManifestResult manifests = deal.semantic.LoweringSupport
            .computeManifests(invocation, built.input(), built.index());
        if (manifests == null || !manifests.diagnostics().isEmpty()) {
            fail("the control manifests compute");
            return null;
        }
        return SemanticLowerer.lowerProject(invocation, built.input(), built.index(),
            manifests.manifests(), new HostDeclarationSurface(Map.of()), Map.of(),
            Map.of(), BuiltinErrorDeclaration.synthesized(program.span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW),
            Set.of());
    }

    /**
     * The callback entry slot's source (P3's unpinned cell): an exported
     * callback with two declared parameters, so the one lowering records
     * the {@code CALLBACK_INVOKE} op, one {@code HOST_TO_DEAL} parameter
     * slot per declared parameter, and the single {@code DEAL_TO_HOST}
     * return cell.
     */
    private static final String CALLBACK_ENTRY_SOURCE = """
        // The callback-entry drive: the first statement starts after this
        // comment, so the program span is distinguishable from a fabricated
        // default span.
        export function cb(x: int, s: string): int {
          if (s === "boom") {
            return x + 1;
          }
          return x + 2;
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * One in-memory callback-entry seed: the executable project lowered
     * with the scenario's callback export set.
     */
    private record CallbackSeed(ExecutableLoweredProject project,
                                Map<ModuleId, StructuredBodyTable> tables,
                                deal.semantic.CheckedModuleInput input) {
    }

    /**
     * The callback-entry seed's production chain: {@link #checkedSeed}
     * under the release-owned invocation plus the one project lowering
     * with the callback export set, so the unit carries the
     * {@code CALLBACK_INVOKE} record the origin cell lives on.
     */
    private static CallbackSeed lowerCallbackSeed(String source, String what,
            Set<String> callbackExports) {
        CompilerInvocation invocation = productionInvocation();
        CheckedSeed seed = checkedSeed(source, what, invocation);
        if (seed == null) {
            return null;
        }
        SemanticLowerer.ProjectLoweringResult lowering = SemanticLowerer.lowerProject(
            invocation, seed.built().input(), seed.built().index(),
            seed.manifests().manifests(), new HostDeclarationSurface(Map.of()),
            Map.of(), Map.of(), BuiltinErrorDeclaration.synthesized(
                seed.input().ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW),
            callbackExports);
        if (lowering == null || lowering.hasErrors() || lowering.project() == null) {
            fail(what + ": the production project lowering fails: "
                + (lowering == null ? "null" : lowering.diagnostics()));
            return null;
        }
        return new CallbackSeed(lowering.project(), lowering.tables(),
            seed.input());
    }

    /**
     * Asserts one op's origin is the invoking unit's program span: the
     * same span the {@code MODULE_EXPORT} cell carries, selected by the
     * one lowering for the callback op and each of its parameter slots.
     */
    private static void assertProgramSpan(String what,
            deal.semantic.ir.SourceOrigin origin, deal.ast.Span programSpan) {
        checkEq(MATRIX_SOURCE_ID, origin.sourceId(),
            what + ": the origin names the invoking unit's file");
        checkEq(Integer.valueOf(programSpan.startLine()),
            Integer.valueOf(origin.span().startLine()),
            what + ": the origin starts on the program span's start line");
        checkEq(Integer.valueOf(programSpan.startColumn()),
            Integer.valueOf(origin.span().startColumn()),
            what + ": the origin starts on the program span's start column");
        checkEq(Integer.valueOf(programSpan.endLine()),
            Integer.valueOf(origin.span().endLine()),
            what + ": the origin ends on the program span's end line");
        checkEq(Integer.valueOf(programSpan.endColumn()),
            Integer.valueOf(origin.span().endColumn()),
            what + ": the origin ends on the program span's end column");
    }

    /**
     * <b>The callback entry slot (P3, unpinned).</b> The one lowering
     * selects the invoking unit's program span for the
     * {@code CALLBACK_INVOKE} op and for each of its {@code HOST_TO_DEAL}
     * parameter boundaries; the three consumers render that origin (the
     * failing slot on a host-driven callback invocation publishes the
     * program span with the closed kind arm's tuple).
     */
    private static void testCallbackEntrySlot() throws Exception {
        System.out.println("-- the callback entry slot's landed emission (P3): the "
            + "HOST_TO_DEAL parameter boundaries under CALLBACK_INVOKE carry the "
            + "invoking unit's program span --");
        CallbackSeed seed = lowerCallbackSeed(CALLBACK_ENTRY_SOURCE,
            "the callback-entry seed", Set.of("cb"));
        if (seed == null) {
            return;
        }
        deal.semantic.ir.LoweredModuleUnit unit = seed.project().modules()
            .get(MATRIX_MODULE);
        check(unit != null, "the callback-entry seed lowers the entry unit");
        if (unit == null) {
            return;
        }
        deal.ast.Span programSpan = seed.input().ast().span();
        deal.semantic.ir.SemanticOp callback = null;
        Map<deal.semantic.ir.OpId, deal.semantic.ir.SemanticOp> byId = new LinkedHashMap<>();
        for (deal.semantic.ir.SemanticOp op : unit.ops()) {
            byId.put(op.opId(), op);
            if (op.kind() == deal.semantic.ir.SemanticOpKind.CALLBACK_INVOKE) {
                callback = op;
            }
        }
        check(callback != null, "the callback-entry seed records the CALLBACK_INVOKE "
            + "op of the callback export");
        if (callback == null) {
            return;
        }
        assertProgramSpan("the callback entry slot (the CALLBACK_INVOKE op)",
            callback.origin(), programSpan);
        deal.semantic.ir.KindPayload.CallbackInvokePayload payload =
            (deal.semantic.ir.KindPayload.CallbackInvokePayload) callback.payload();
        checkEq(2, payload.parameterBoundaryOpIds().size(),
            "the callback entry carries one HOST_TO_DEAL parameter slot per "
                + "declared parameter");
        for (int index = 0; index < payload.parameterBoundaryOpIds().size(); index++) {
            deal.semantic.ir.OpId boundaryId = payload.parameterBoundaryOpIds().get(index);
            deal.semantic.ir.SemanticOp boundary = byId.get(boundaryId);
            String slot = "the callback entry slot " + (index + 1);
            check(boundary != null && boundary.kind() == deal.semantic.ir.SemanticOpKind.BOUNDARY,
                slot + ": the payload names a boundary op");
            if (boundary == null || boundary.kind() != deal.semantic.ir.SemanticOpKind.BOUNDARY) {
                continue;
            }
            deal.semantic.ir.KindPayload.BoundaryPayload cell =
                (deal.semantic.ir.KindPayload.BoundaryPayload) boundary.payload();
            checkEq(deal.semantic.ir.BoundaryKind.HOST_TO_DEAL, cell.kind(),
                slot + ": the boundary kind is HOST_TO_DEAL");
            checkEq(payload.descriptor().paramTypes().get(index), cell.descriptor(),
                slot + ": the boundary carries the declared parameter descriptor");
            checkEq(callback.opId(), boundary.origin().parentOpId(),
                slot + ": the boundary nests under the CALLBACK_INVOKE op");
            assertProgramSpan(slot, boundary.origin(), programSpan);
        }
        Path workspace = Files.createTempDirectory("projection-callback");
        try {
            String origin = MATRIX_SOURCE_ID + ":" + programSpan.startLine()
                + ":" + programSpan.startColumn();
            SemanticDifferentialHarness.Verdict verdict =
                SemanticDifferentialHarness.runCallback(unit,
                    seed.tables().get(MATRIX_MODULE), payload.function(),
                    List.of(new SemanticDifferentialHarness.CallbackArg.Str("nope"),
                        new SemanticDifferentialHarness.CallbackArg.Str("ok")),
                    SemanticDifferentialHarness.Expectation.failure(
                        "the callback entry slot", List.of(), "E8001", origin),
                    workspace);
            checkEq(3, verdict.runs().size(), "the callback-entry drive produced the "
                + "three consumers: " + verdict.failures());
            check(verdict.pass(), "the callback-entry three-consumer verdict passes "
                + "with the program-span origin: " + verdict.failures() + "\n"
                + verdict.report());
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                check(run.terminal()
                        instanceof SemanticRuntimeModel.Terminal.DealFailure,
                    run.consumer() + ": the callback-entry drive fails: "
                        + run.terminal());
                if (!(run.terminal()
                        instanceof SemanticRuntimeModel.Terminal.DealFailure failure)) {
                    continue;
                }
                SemanticRuntimeModel.ErrorSnapshot error = failure.error();
                System.out.println("   " + run.consumer() + ": " + error.code()
                    + " | " + error.message() + " | " + error.origin() + " | "
                    + error.expected() + " | " + error.actual());
                checkEq("expected int", error.message(), run.consumer()
                    + ": the callback slot renders the kind arm's suffix-less text");
                checkEq("int", error.expected(), run.consumer()
                    + ": the callback slot's expected token");
                checkEq("string", error.actual(), run.consumer()
                    + ": the callback slot's actual token");
                checkEq(origin, error.origin(), run.consumer()
                    + ": the callback slot's origin is the invoking unit's program "
                    + "span");
            }
        } finally {
            deleteRecursively(workspace);
        }
    }

    // =========================================================================
    // 2d. The invalid-Unicode arm through the real boundary producers
    //     (ISSUE-0705)
    // =========================================================================

    /** The invalid-Unicode callback seed: one declared {@code string[]} parameter. */
    private static final String INVALID_UNICODE_CALLBACK_SOURCE = """
        export function cb(x: int, values: string[]): int {
          return x + 1;
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The invalid-Unicode arm end-to-end (P1/P4, ISSUE-0705): the callback
     * entry's declared {@code string[]} parameter slot receives an
     * unpaired-surrogate element, and every consumer's boundary producer
     * renders the element tuple selected by its own scalar validation at
     * the invoking unit's program span — the boundary supplies the origin,
     * never a fabricated one. The valid array is the admission control; the
     * registered direct producer drives (the oracle's
     * {@code BoundaryExecutor}, the JVM {@code bcheck}, and the emitted
     * {@code __bcheck}, including the string cell, the nested element
     * cells, and the completion cell) live in {@code FailureArmAuthorityTest};
     * the direct {@code ARM_RENDERS} entry renders the arm but never
     * exercises a boundary's validation.
     */
    private static void testInvalidUnicodeBoundaryOriginDrive() throws Exception {
        System.out.println("-- the invalid-Unicode element through the callback entry's "
            + "declared string[] parameter slot --");
        CallbackSeed seed = lowerCallbackSeed(INVALID_UNICODE_CALLBACK_SOURCE,
            "the invalid-unicode callback seed", Set.of("cb"));
        if (seed == null) {
            return;
        }
        deal.semantic.ir.LoweredModuleUnit unit = seed.project().modules()
            .get(MATRIX_MODULE);
        check(unit != null, "the invalid-unicode seed lowers the entry unit");
        if (unit == null) {
            return;
        }
        deal.semantic.ir.SemanticOp callback = null;
        for (deal.semantic.ir.SemanticOp op : unit.ops()) {
            if (op.kind() == deal.semantic.ir.SemanticOpKind.CALLBACK_INVOKE) {
                callback = op;
            }
        }
        check(callback != null, "the invalid-unicode seed records the "
            + "CALLBACK_INVOKE op");
        if (callback == null) {
            return;
        }
        deal.semantic.ir.KindPayload.CallbackInvokePayload payload =
            (deal.semantic.ir.KindPayload.CallbackInvokePayload) callback.payload();
        deal.ast.Span programSpan = seed.input().ast().span();
        String origin = MATRIX_SOURCE_ID + ":" + programSpan.startLine() + ":"
            + programSpan.startColumn();
        Path workspace = Files.createTempDirectory("projection-invalid-unicode");
        try {
            SemanticDifferentialHarness.Verdict verdict =
                SemanticDifferentialHarness.runCallback(unit,
                    seed.tables().get(MATRIX_MODULE), payload.function(),
                    List.of(new SemanticDifferentialHarness.CallbackArg.Int(1),
                        new SemanticDifferentialHarness.CallbackArg.Array(List.of(
                            new SemanticDifferentialHarness.CallbackArg.Str("ok"),
                            new SemanticDifferentialHarness.CallbackArg.Str("\uD800")))),
                    SemanticDifferentialHarness.Expectation.failure(
                        "the callback array slot", List.of(), "E8003", origin),
                    workspace);
            checkEq(3, verdict.runs().size(), "the invalid-unicode drive produced "
                + "the three consumers: " + verdict.failures());
            check(verdict.pass(), "the invalid-unicode three-consumer verdict "
                + "passes: " + verdict.failures() + "\n" + verdict.report());
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                check(run.terminal()
                        instanceof SemanticRuntimeModel.Terminal.DealFailure,
                    run.consumer() + ": the invalid-unicode drive fails: "
                        + run.terminal());
                if (!(run.terminal()
                        instanceof SemanticRuntimeModel.Terminal.DealFailure failure)) {
                    continue;
                }
                SemanticRuntimeModel.ErrorSnapshot error = failure.error();
                System.out.println("   " + run.consumer() + ": " + error.code() + " | "
                    + error.message() + " | " + error.origin() + " | "
                    + error.expected() + " | " + error.actual());
                checkEq("E8003", error.code(), run.consumer()
                    + ": the invalid-unicode element code");
                checkEq("array element 2 type mismatch", error.message(),
                    run.consumer() + ": the element arm's own text");
                checkEq(origin, error.origin(), run.consumer() + ": the supplied "
                    + "boundary origin (the invoking unit's program span)");
                checkEq("string", error.expected(), run.consumer()
                    + ": the expected token");
                checkEq("invalid-unicode", error.actual(), run.consumer()
                    + ": the actual token");
            }
        } finally {
            deleteRecursively(workspace);
        }
        // The valid-array control: the same slot admits the valid elements
        // and the callback body runs to its pinned success.
        Path control = Files.createTempDirectory("projection-invalid-unicode-ok");
        try {
            SemanticDifferentialHarness.Verdict verdict =
                SemanticDifferentialHarness.runCallback(unit,
                    seed.tables().get(MATRIX_MODULE), payload.function(),
                    List.of(new SemanticDifferentialHarness.CallbackArg.Int(1),
                        new SemanticDifferentialHarness.CallbackArg.Array(List.of(
                            new SemanticDifferentialHarness.CallbackArg.Str("ok"),
                            new SemanticDifferentialHarness.CallbackArg.Str("two")))),
                    SemanticDifferentialHarness.Expectation.success(
                        "the callback array slot control", List.of(), "int:2"),
                    control);
            checkEq(3, verdict.runs().size(), "the valid-array control produced "
                + "the three consumers: " + verdict.failures());
            check(verdict.pass(), "the valid-array three-consumer verdict passes: "
                + verdict.failures() + "\n" + verdict.report());
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                    run.consumer() + ": the valid elements are admitted: "
                        + run.terminal());
            }
        } finally {
            deleteRecursively(control);
        }
    }

    // =========================================================================
    // 2e. The host string-carrier producers (ISSUE-0760)
    // =========================================================================

    /** The corpus fixture whose host surface the carrier drives compile. */
    private static final String HOST_CARRIER_ANCHOR =
        "host-abi/host-surrogate-utf8-e8010";

    /**
     * The carrier drives' generated entry module: it imports the host
     * surface directly and calls the declared export at the fixed call
     * origin (line 4, column 19) the failure tuples are pinned to.
     */
    private static final String HOST_CARRIER_DRIVER = """
        import * as host from "host/bad_string"

        export function main(): null {
          let s: string = host.%s()
          return null
        }
        """;

    /** The scalar valid control: the entry consumes the admitted carrier. */
    private static final String HOST_CARRIER_VALID_DRIVER = """
        import * as host from "host/bad_string"

        export function main(): null {
          let s: string = host.validString()
          if (s !== "ok") {
            throw { code: "PROBE_STRING_CARRIER", message: "the valid host string carrier did not decode to 'ok'" }
          }
          return null
        }
        """;

    /** The declared-array control: DEAL must read the decoded element result. */
    private static final String HOST_CARRIER_ARRAY_DRIVER = """
        import * as host from "host/bad_string"

        export function main(): null {
          let xs: string[] = host.rawStringArray()
          let s: string = xs[0]
          if (s !== "ok") {
            throw { code: "PROBE_STRING_CARRIER", message: "the raw host string element did not decode to 'ok'" }
          }
          return null
        }
        """;

    /** The nested array position control. */
    private static final String HOST_CARRIER_NESTED_DRIVER = """
        import * as host from "host/bad_string"

        export function main(): null {
          let xs: string[][] = host.rawNestedStringArray()
          let s: string = xs[0][0]
          if (s !== "ok") {
            throw { code: "PROBE_STRING_CARRIER", message: "the nested raw host string element did not decode to 'ok'" }
          }
          return null
        }
        """;

    /** The nullable element position control. */
    private static final String HOST_CARRIER_NULLABLE_DRIVER = """
        import * as host from "host/bad_string"

        export function main(): null {
          let xs: (string | null)[] = host.rawNullableStringArray()
          let s: string | null = xs[0]
          if (s !== null) {
            if (s !== "ok") {
              throw { code: "PROBE_STRING_CARRIER", message: "the nullable raw host string element did not decode to 'ok'" }
            }
            return null
          }
          throw { code: "PROBE_STRING_CARRIER", message: "the nullable raw host string element decoded to null" }
        }
        """;

    /** The nullable nested-array element control (ISSUE-0760 cycle 2). */
    private static final String HOST_CARRIER_NULLABLE_ARRAY_DRIVER = """
        import * as host from "host/bad_string"

        export function main(): null {
          let xs: (string[] | null)[] = host.rawNullableArrayArray()
          let first: string[] | null = xs[0]
          if (first !== null) {
            throw { code: "PROBE_NULLABLE_ARRAY", message: "the null nested array element decoded to a non-null value" }
          }
          let second: string[] | null = xs[1]
          if (second !== null) {
            let s: string = second[0]
            if (s !== "ok") {
              throw { code: "PROBE_NULLABLE_ARRAY", message: "the nested raw host string element did not decode to 'ok'" }
            }
            return null
          }
          throw { code: "PROBE_NULLABLE_ARRAY", message: "the non-null nested array element decoded to null" }
        }
        """;

    /**
     * The rejected nested tail (ISSUE-0760 cycle 2, finding 2): the
     * declared {@code string[][]} return carries one checked nested array
     * followed by a non-array element, so the return fails the element arm
     * after a nested conversion was computed. The complete composite tuple
     * is asserted on all three consumers, and the JVM probe observes the
     * retained slots and the array alias after the rejection.
     */
    private static final String HOST_CARRIER_NESTED_TAIL_DRIVER = """
        import * as host from "host/bad_string"

        export function main(): null {
          let xs: string[][] = host.badNestedTail()
          let s: string = xs[0][0]
          return null
        }
        """;

    /** The untouched retained observation of the rejected nested tail. */
    private static final String NESTED_TAIL_RETAINED_SLOTS =
        "deal.codegen.jvm.JvmRuntime$Array|[B|java.lang.String|alias";

    /**
     * The repeated inner-array identity control (ISSUE-0760 cycle 3): the
     * declared {@code string[][]} return carries the same host inner array
     * at both positions, so the projection yields one DEAL array whose
     * element the entry body mutates through one alias and reads through
     * the other. A per-occurrence projection (two distinct carriers) fails
     * the identity comparison or the shared-mutation read.
     */
    private static final String HOST_CARRIER_SHARED_ARRAY_DRIVER = """
        import * as host from "host/bad_string"

        export function main(): null {
          let xs: string[][] = host.sharedNestedStringArray()
          if (xs[0] !== xs[1]) {
            throw { code: "PROBE_ALIAS", message: "the same host inner array became two DEAL arrays" }
          }
          xs[0][0] = "changed"
          let s: string = xs[1][0]
          if (s !== "changed") {
            throw { code: "PROBE_ALIAS", message: "the shared host inner array did not observe the mutation through both aliases" }
          }
          return null
        }
        """;

    /**
     * The host nested-array projection (ISSUE-0760 cycles 2 and 3): a
     * nullable nested-array element stays the language null while its
     * non-null sibling decodes (all three consumers), a repeated inner
     * array stays one DEAL array observed through both aliases, and a
     * later element's rejection leaves every retained slot and the array
     * alias of the failed return untouched.
     */
    private static void testHostNestedArrayProjection() throws Exception {
        System.out.println("-- the host nested-array projection: the nullable "
            + "element stays null, the repeated inner array keeps its identity, "
            + "and the rejected nested tail mutates nothing --");
        safely("the valid nested host carriers", () -> driveHostCarrierSuccessBatch(
            new LinkedHashMap<>(Map.of(
                "test_nullable_nested", HOST_CARRIER_NULLABLE_ARRAY_DRIVER,
                "test_shared", HOST_CARRIER_SHARED_ARRAY_DRIVER))));
        safely("the rejected nested tail",
            CanonicalProjectionParityTest::driveHostNestedTail);
    }

    /**
     * The declared native array-carrier drives (the JVM host's own
     * {@code $DealRt} wrappers): the invalid second element, the nested
     * carrier with a rejected later element, and the valid controls. The
     * generated entry calls the declared export at the fixed call origin
     * the failure tuples are pinned to.
     */
    private static final String HOST_NATIVE_BAD_ARRAY_DRIVER = """
        import * as host from "host/bad_string"

        export function main(): null {
          let xs: string[] = host.nativeBadStringArray()
          return null
        }
        """;

    private static final String HOST_NATIVE_BAD_NESTED_TAIL_DRIVER = """
        import * as host from "host/bad_string"

        export function main(): null {
          let xs: string[][] = host.nativeBadNestedTail()
          return null
        }
        """;

    private static final String HOST_NATIVE_ARRAY_DRIVER = """
        import * as host from "host/bad_string"

        export function main(): null {
          let xs: string[] = host.nativeStringArray()
          let s: string = xs[0]
          if (s !== "ok") {
            throw { code: "PROBE_NATIVE_CARRIER", message: "the declared host array carrier did not land 'ok'" }
          }
          return null
        }
        """;

    private static final String HOST_NATIVE_NESTED_DRIVER = """
        import * as host from "host/bad_string"

        export function main(): null {
          let xs: string[][] = host.nativeNestedStringArray()
          let s: string = xs[0][0]
          if (s !== "ok") {
            throw { code: "PROBE_NATIVE_CARRIER", message: "the nested declared host array carrier did not land 'ok'" }
          }
          return null
        }
        """;

    private static final String HOST_NATIVE_SHARED_DRIVER = """
        import * as host from "host/bad_string"

        export function main(): null {
          let xs: string[][] = host.nativeSharedNestedStringArray()
          if (xs[0] !== xs[1]) {
            throw { code: "PROBE_NATIVE_ALIAS", message: "the same native inner carrier became two DEAL arrays" }
          }
          xs[0][0] = "changed"
          let s: string = xs[1][0]
          if (s !== "changed") {
            throw { code: "PROBE_NATIVE_ALIAS", message: "the shared native inner carrier did not observe the mutation through both aliases" }
          }
          return null
        }
        """;

    /**
     * The differing-depth carrier reuse (the acceptance-review finding):
     * the declared {@code string[][][][]} return shares one inner carrier
     * between the root's first element and the sole element of the root's
     * second element, so the same host value is checked under
     * {@code [[[string]]]} (where its element chain is valid) and again
     * under {@code [[string]]} (where the shared element's own elements
     * must be validated as a {@code string[]} and are not). The call is
     * evaluated without retaining its result, so only the host return cell
     * can reject the value: the return must fail with the outermost
     * element arm as the cell's inner reason — a check that admitted the
     * deeper occurrence on the shallower materialization (or on an
     * in-progress conversion) would complete the drive.
     */
    private static final String HOST_NATIVE_DIFFERING_DEPTH_DRIVER = """
        import * as host from "host/bad_string"

        export function main(): null {
          host.nativeDifferingDepthStringArray()
          return null
        }
        """;

    /** The untouched retained observation of the rejected native nested tail. */
    private static final String NATIVE_TAIL_RETAINED_SLOTS =
        "$DealRt$__StringArray|java.lang.String|java.lang.String|alias";

    /**
     * The host string-carrier producers end-to-end (ISSUE-0760): the three
     * invalid encoding classes (the lone surrogate, the embedded surrogate
     * at a mid-string position, and the malformed non-surrogate sequence)
     * render the complete pinned failure tuple — code, composite message,
     * span at the call expression, expected, and actual — from the oracle's
     * host seam, the emitted LuaJIT wrapper under {@code luajit}, and the
     * emitted JVM wrapper under {@code javac} plus {@code java}. The valid
     * carriers (the scalar string and the raw UTF-8 byte element inside a
     * declared array, at its nested array and nullable element positions)
     * are admitted and their decoded values are consumed by the entry body,
     * so a wrong reason selection or an unprojected element result fails
     * the drive.
     */
    private static void testHostStringCarrierProducers() throws Exception {
        System.out.println("-- the host string-carrier producers: the closed reason "
            + "selection and the decoded element results from the oracle, the "
            + "LuaJIT artifact, and the JVM artifact --");
        safely("the lone surrogate host return", () -> driveHostCarrierFailure(
            FailureArmId.HOST_STRING_SURROGATE, "surrogateString"));
        safely("the embedded surrogate host return", () -> driveHostCarrierFailure(
            FailureArmId.HOST_STRING_SURROGATE, "embeddedSurrogateString"));
        safely("the malformed non-surrogate host return", () ->
            driveHostCarrierFailure(FailureArmId.HOST_STRING_INVALID_UTF8,
                "badString"));
        safely("the valid host string carriers", () -> driveHostCarrierSuccessBatch(
            new LinkedHashMap<>(Map.of(
                "test_scalar", HOST_CARRIER_VALID_DRIVER,
                "test_array", HOST_CARRIER_ARRAY_DRIVER,
                "test_nested", HOST_CARRIER_NESTED_DRIVER,
                "test_nullable", HOST_CARRIER_NULLABLE_DRIVER))));
    }

    /** The host-carrier drive's anchor case (the generated module is the entry). */
    private static Case hostCarrierCase() {
        return new Case(HOST_CARRIER_ANCHOR, "main", Entry.MAIN, List.of(),
            "bad_string", null);
    }

    /**
     * The declared native array carriers (the JVM host's own
     * {@code $DealRt} wrapper classes): a declared {@code string[]}
     * returned as {@code $DealRt.__StringArray} with an unpaired surrogate
     * in its second slot fails the element arm like every other element —
     * the complete tuple from the oracle's host seam, the LuaJIT artifact,
     * and the JVM artifact — the nested carrier's later non-array element
     * fails after its earlier nested position was checked while the raw
     * carrier stays untouched, a carrier shared between two positions at
     * different descriptor depths is re-validated at every position (so
     * the deeper occurrence fails at the host call instead of being
     * admitted on the shallower materialization), and every valid control
     * lands as a usable DEAL array at the scalar, nested, and
     * repeated-inner-carrier positions.
     */
    private static void testHostNativeArrayCarriers() throws Exception {
        System.out.println("-- the native declared array carriers: the "
            + "$DealRt wrapper elements are checked recursively and the valid "
            + "carriers land as usable DEAL arrays --");
        safely("the native string[] carrier with an invalid element",
            () -> driveHostRejectedArray("host.nativeBadStringArray()",
                HOST_NATIVE_BAD_ARRAY_DRIVER, "[string]", 22, 2, null, null));
        safely("the native nested carrier with a rejected tail",
            () -> driveHostRejectedArray("host.nativeBadNestedTail()",
                HOST_NATIVE_BAD_NESTED_TAIL_DRIVER, "[[string]]", 24, 2,
                "retainedNativeSlots", NATIVE_TAIL_RETAINED_SLOTS));
        safely("the native carrier reused at a differing descriptor depth",
            () -> driveHostRejectedArray(
                "host.nativeDifferingDepthStringArray()",
                HOST_NATIVE_DIFFERING_DEPTH_DRIVER, "[[[[string]]]]", 3, 2,
                null, null));
        safely("the valid native host carriers", () -> driveHostCarrierSuccessBatch(
            new LinkedHashMap<>(Map.of(
                "test_native_array", HOST_NATIVE_ARRAY_DRIVER,
                "test_native_nested", HOST_NATIVE_NESTED_DRIVER,
                "test_native_shared", HOST_NATIVE_SHARED_DRIVER))));
    }

    /**
     * Drives one rejected declared array return whose inner reason is the
     * element arm: the oracle's host seam, the LuaJIT artifact, and the JVM
     * artifact each render the complete pinned tuple (the return-cell
     * composite created by the registry renderer, the outermost element
     * arm as the render's one-based index, the declared cell descriptor as
     * expected, the carrier-kind projection as actual, and the call
     * expression origin), and the three tuples are identical. When the
     * drive pins a retained observation, the JVM probe reports it after
     * the rejected return and the expected untouched line is asserted.
     */
    private static void driveHostRejectedArray(String label, String driverSource,
            String descriptor, int column, int oneBasedIndex,
            String retainedMethod, String retainedExpectation) throws Exception {
        Compiled compiled = compile(hostCarrierCase(), driverSource);
        try {
            String inner = FailureContractRegistry.render(
                FailureArmId.ARRAY_ELEMENT_KIND,
                Map.of("oneBasedIndex", Integer.toString(oneBasedIndex)),
                "string", "string", null).message();
            BoundaryFailure expected = FailureContractRegistry.render(
                FailureArmId.HOST_SYNC_RETURN_CELL, Map.of("inner", inner),
                descriptor, "table", null);
            Tuple expectedTuple = new Tuple(expected.code().name(),
                expected.message(), "app.deal", 4, column, descriptor, "table");
            Tuple oracle = oracleTuple(compiled);
            checkEq(expectedTuple, oracle, label + " (oracle): the complete "
                + "failure tuple");
            Tuple lua = luaArtifact(compiled);
            checkEq(expectedTuple, lua, label + " (luajit): the complete "
                + "failure tuple");
            String entryModule = compiled.checkedProject().entryModule().path();
            String afterFailure = retainedMethod == null ? ""
                : "System.out.println(\"SLOTS|\" + "
                    + JvmBackend.classNameFor("host/bad_string") + "."
                    + retainedMethod + "());";
            String jvmOutput = jvmArtifactOutput(compiled, jvmProbe(
                JvmBackend.classNameFor(entryModule), entryModule, afterFailure));
            Tuple jvm = jvmOutput == null ? null
                : parseJvmProbe(jvmOutput, compiled);
            checkEq(expectedTuple, jvm, label + " (jvm): the complete failure "
                + "tuple");
            checkEq(null, firstDifferingField(oracle, lua), label
                + ": the oracle and the LuaJIT artifact render the identical "
                + "tuple");
            checkEq(null, firstDifferingField(oracle, jvm), label
                + ": the oracle and the JVM artifact render the identical "
                + "tuple");
            if (retainedExpectation != null) {
                check(jvmOutput != null && jvmOutput.contains(
                        "SLOTS|" + retainedExpectation), label + " (jvm): every "
                    + "retained slot and the nested array alias are untouched "
                    + "after the rejected return: " + jvmOutput);
            }
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /**
     * Drives one invalid carrier class: the oracle's host seam, the LuaJIT
     * artifact, and the JVM artifact each render the complete pinned tuple
     * (the registry arm's composite with its declared fields and the call
     * expression origin), and the three tuples are identical.
     */
    private static void driveHostCarrierFailure(FailureArmId reason, String export)
            throws Exception {
        String label = "host." + export + "()";
        Compiled compiled = compile(hostCarrierCase(),
            HOST_CARRIER_DRIVER.formatted(export));
        try {
            BoundaryFailure expected = FailureContractRegistry.render(
                FailureArmId.HOST_SYNC_RETURN_CELL,
                Map.of("inner", FailureContractRegistry.renderInner(reason, Map.of())),
                "string", "string", null);
            Tuple expectedTuple = new Tuple(expected.code().name(), expected.message(),
                "app.deal", 4, 19, "string", "string");
            Tuple oracle = oracleTuple(compiled);
            checkEq(expectedTuple, oracle, label + " (oracle): the complete "
                + "failure tuple");
            Tuple lua = luaArtifact(compiled);
            checkEq(expectedTuple, lua, label + " (luajit): the complete "
                + "failure tuple");
            Tuple jvm = jvmArtifact(compiled);
            checkEq(expectedTuple, jvm, label + " (jvm): the complete "
                + "failure tuple");
            checkEq(null, firstDifferingField(oracle, lua), label
                + ": the oracle and the LuaJIT artifact render the identical tuple");
            checkEq(null, firstDifferingField(oracle, jvm), label
                + ": the oracle and the JVM artifact render the identical tuple");
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    /**
     * Drives the rejected nested tail: the oracle's host seam and the
     * LuaJIT artifact render the complete composite tuple (the element arm
     * as the return cell's inner reason at the call origin), and the JVM
     * artifact renders the same tuple through the retained-observation
     * probe, which also reports every retained slot and the nested array
     * alias untouched — a nested projection committed before the later
     * sibling element failed would surface in the observed classes.
     */
    private static void driveHostNestedTail() throws Exception {
        driveHostRejectedArray("host.badNestedTail()",
            HOST_CARRIER_NESTED_TAIL_DRIVER, "[[string]]", 24, 2,
            "retainedNestedSlots", NESTED_TAIL_RETAINED_SLOTS);
    }

    /**
     * Drives one valid carrier control: the oracle completes with a success
     * terminal and both production artifacts complete without a failure
     * event, after the entry body consumed the checked value.
     */
    private static void driveHostCarrierSuccessBatch(Map<String, String> sources)
            throws Exception {
        StringBuilder source = new StringBuilder("import * as host from \"host/bad_string\"\n");
        List<String> exports = sources.keySet().stream().sorted().toList();
        for (String export : exports) {
            String fixture = sources.get(export);
            source.append(fixture.substring(fixture.indexOf("export function main"))
                .replace("export function main", "export function " + export));
        }
        source.append("export function main(): null { return null }\n");
        Compiled compiled = compile(hostCarrierCase(), source.toString());
        try {
            String entry = compiled.checkedProject().entryModule().path();
            for (String export : exports) {
                safely(export, () -> {
                    SemanticRuntimeModel.ConsumerRun run = SemanticOracle.invokeSyncEntry(
                        compiled.project(), compiled.tables(), compiled.registries(),
                        responderFor(compiled.spec()), compiled.checkedProject().entryModule(),
                        export, List.of());
                    check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success success
                            && "null".equals(success.resultAtom()),
                        export + " (oracle): the isolated export succeeds: " + run.terminal());
                    String lua = luaArtifactOutput(compiled, Map.of(),
                        luaProbe(compiled.root().resolve("out").resolve(
                            entry.replace('.', '/') + ".lua").toString(), entry)
                            .replace("if string.sub(name, 1, 5) == \"test_\" then",
                                "if name == \"" + export + "\" then")
                            .replace("if not ok2 then emit(err2) os.exit(0) end",
                                "if not ok2 then emit(err2) os.exit(0) end\n"
                                    + "print(\"SUCCESS:" + export + "\")"));
                    checkEq("SUCCESS:" + export + "\nOK\n", lua,
                        export + " (luajit): the isolated export succeeds");
                    String jvm = jvmArtifactOutput(compiled,
                        jvmProbe(JvmBackend.classNameFor(entry), entry, "")
                            .replace("if (!name.startsWith(\"test_\"))",
                                "if (!name.equals(args[0]))")
                            .replace(".fn.invoke(new java.lang.Object[]{ });",
                                ".fn.invoke(new java.lang.Object[]{ });\n"
                                    + "System.out.println(\"SUCCESS:\" + name);"),
                        Map.of(), List.of(export));
                    checkEq("SUCCESS:" + export + "\nOK\n", jvm,
                        export + " (jvm): the isolated export succeeds");
                });
            }
        } finally {
            deleteRecursively(compiled.root());
        }
    }

    // =========================================================================
    // 3. The closed arm table through the serialized renderer and the runtime
    // =========================================================================

    /** One arm render: the arm's named parameters and its declared fields. */
    private record Render(FailureArmId id, Map<String, String> values,
                          String expected, String actual) {
    }

    private static Render render(FailureArmId id, String expected, String actual,
            String... values) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            map.put(values[i], values[i + 1]);
        }
        return new Render(id, map, expected, actual);
    }

    /**
     * Every declared {@code TOP_LEVEL} arm with the representative values
     * of its family: the arm's own template, expected token, and actual
     * token render identically from the registry renderer and the emitted
     * Lua prelude (the JVM runtime delegates to the registry renderer, so
     * the three consumers share one render).
     */
    private static final List<Render> ARM_RENDERS = List.of(
        render(FailureArmId.TYPED_BOUNDARY_KIND, "int", "string", "kind", "int"),
        render(FailureArmId.TYPED_BOUNDARY_INVALID_UNICODE, "string",
            "invalid-unicode"),
        render(FailureArmId.CLASS_IDENTITY, "@corpus/a/A", "@corpus/b/B",
            "expected", "@corpus/a/A", "actual", "@corpus/b/B"),
        render(FailureArmId.INT32_RANGE, null, null),
        render(FailureArmId.INT32_DIVISION_BY_ZERO, null, null),
        render(FailureArmId.INT32_NEGATIVE_EXPONENT, null, null),
        render(FailureArmId.INT_CONVERSION_NULL, "int", "null"),
        render(FailureArmId.INT_CONVERSION_NAN, "int", "NaN"),
        render(FailureArmId.INT_CONVERSION_INFINITY, "int", "infinity"),
        render(FailureArmId.INT_CONVERSION_FRACTIONAL, "int", "number"),
        render(FailureArmId.NUMBER_CONVERSION_NULL, "number", "null"),
        render(FailureArmId.ARRAY_ELEMENT_KIND, "int", "string",
            "oneBasedIndex", "2"),
        render(FailureArmId.ARRAY_READ_NEGATIVE_INDEX, null, null),
        render(FailureArmId.ARRAY_WRITE_BOUNDS, null, null),
        render(FailureArmId.ARRAY_DELETE_BOUNDS, null, null),
        render(FailureArmId.BYTES_ALLOCATE, null, null),
        render(FailureArmId.BYTES_READ, null, null),
        render(FailureArmId.BYTES_WRITE_BOUNDS, null, null),
        render(FailureArmId.BYTES_WRITE_RANGE, null, null),
        render(FailureArmId.FUNCTION_SIGNATURE_MISMATCH, "(int)->int",
            "(int)->string", "expected", "(int)->int", "actual", "(int)->string"),
        render(FailureArmId.HOST_PARAMETER_CELL, "bytes", "number",
            "index", "1", "inner", "expected bytes"),
        render(FailureArmId.HOST_SYNC_RETURN_NOTHING, "int", "nothing",
            "expected", "int"),
        render(FailureArmId.HOST_SYNC_RETURN_CELL, "?(int)->int", "table",
            "inner", "function signature mismatch: expected (int)->int, got "
                + "(string)->int"),
        render(FailureArmId.ASYNC_COMPLETION_KIND, "string", "number",
            "expected", "string"),
        render(FailureArmId.ASYNC_COMPLETION_REFINEMENT, "int", "number",
            "expected", "int", "actual", "number"),
        render(FailureArmId.ASYNC_SHAPE, "async operation", "number",
            "actual", "number"),
        render(FailureArmId.HOST_LOAD_FAILED, null, null, "module", "host/m",
            "reason", "boom"),
        render(FailureArmId.HOST_LOAD_NOT_A_MODULE, null, null, "module", "host/m"),
        render(FailureArmId.HOST_LOAD_MISSING_EXPORT, null, null, "name", "missing",
            "module", "host/m"),
        render(FailureArmId.HOST_LOAD_SIGNATURE_MISMATCH, null, null, "name", "ping",
            "module", "host/m", "expected", "(int)->int", "actual", "(string)->int"),
        render(FailureArmId.HOST_LOAD_IDENTITY_MISMATCH, null, null, "name", "C",
            "module", "host/m", "expected", "@host.m/C", "actual", "@host.n/C"),
        render(FailureArmId.HOST_LOAD_INVALID_METADATA, null, null, "name", "C",
            "module", "host/m", "defaults|fields", "defaults"),
        render(FailureArmId.CLASS_EXTRA_FIELD, null, null, "field", "fallback",
            "classId", "@$external/host.presence/Config"),
        render(FailureArmId.JSON_PARSE_ERROR, null, null,
            "oneBasedByteOffset", "3", "reason", "unexpected character"),
        render(FailureArmId.JSON_TO_WALK, null, "table",
            "fieldPath", "age", "actual", "table"),
        render(FailureArmId.JSON_STRINGIFY_UNSUPPORTED,
            "string, number, boolean, or table", "function", "actual", "function"),
        render(FailureArmId.JSON_TO_WALK_CYCLE, null, null),
        render(FailureArmId.SQRT_NEGATIVE, null, "-2.0"));

    /** One row of one renderer's output: {@code id|code|message|origin|e|a}. */
    private static Map<String, String> parseRows(String output, String label) {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String line : output.lines().toList()) {
            if (!line.startsWith("ROW|")) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            check(parts.length == 7, label + ": the row frames its fields: " + line);
            if (parts.length != 7) {
                continue;
            }
            rows.put(parts[1], parts[2] + "|" + parts[3] + "|" + parts[4] + "|"
                + parts[5] + "|" + parts[6]);
        }
        return rows;
    }

    private static void testSerializedArmTableIdentity() throws Exception {
        System.out.println("-- the closed arm table: every declared arm renders its "
            + "own template identically from the authority, the emitted Lua "
            + "prelude, and the JVM runtime renderer --");
        String origin = "parity.deal:3:7";
        Map<String, String> lua = luaArmRows(origin);
        checkEq(FailureArmId.values().length - 2, ARM_RENDERS.size(),
            "every declared top-level arm outside the two INNER_ONLY carriers is "
                + "driven with representative values");
        for (Render row : ARM_RENDERS) {
            deal.semantic.ir.BoundaryFailure authority = FailureContractRegistry.render(
                row.id(), row.values(), row.expected(), row.actual(), null);
            deal.codegen.jvm.JvmRuntime.DealError jvm = deal.codegen.jvm.JvmRuntime.arm(
                row.id(), row.values(), origin, row.expected(), row.actual());
            checkEq(authority.code().name(), jvm.code, row.id() + ": the JVM "
                + "runtime resolves the authority's own arm render");
            Tuple reference = new Tuple(authority.code().name(), authority.message(),
                null, null, null, authority.expected(), authority.actual());
            Tuple jvmTuple = new Tuple(jvm.code, jvm.msg, null, null, null, jvm.expected,
                jvm.actual);
            checkEq(null, firstDifferingField(reference, jvmTuple), row.id()
                + ": the JVM runtime renders the authority's identical tuple");
            String rendered = lua.get(row.id().name());
            check(rendered != null, row.id() + ": the emitted prelude carries the "
                + "arm's renderer row: " + lua.keySet());
            if (rendered == null) {
                continue;
            }
            String[] parts = rendered.split("\\|", -1);
            Tuple luaTuple = new Tuple(parts[0], parts[1], null, null, null,
                emptyToNull(parts[3]), emptyToNull(parts[4]));            checkEq(null, firstDifferingField(reference, luaTuple), row.id()
                + ": the emitted prelude renders the authority's identical tuple");
            checkEq(origin, parts[2], row.id() + ": the emitted prelude attaches "
                + "the call's origin");
        }
    }

    private static deal.module.ProductionProjectEmission.Result emit(Compiled compiled,
            Backend backend, Path out) throws Exception {
        PublicationStager stager = PublicationStager.forRoot(out);
        deal.module.ProductionProjectEmission.Result result;
        try {
            result = ProductionProjectEmission.run(compiled.invocation(),
                compiled.checkedProject(), compiled.index(), compiled.manifests(),
                compiled.surface(), compiled.identities(), compiled.externCModules(),
                compiled.mirror().toString(),
                BuiltinErrorDeclaration.synthesized(
                    compiled.checkedProject().modules().stream()
                        .filter(module -> module.moduleId()
                            .equals(compiled.checkedProject().entryModule()))
                        .findFirst().orElseThrow().ast().span()),
                List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW),
                Set.of(), backend, false,
                deal.distribution.DistributionHome.forManifestDirectory(
                    compiled.mirror().toString()), stager);
            if (result.emitted()) {
                stager.publish();
            }
        } finally {
            stager.discard();
        }
        if (!result.emitted()) {
            throw new IllegalStateException(compiled.spec().what() + " (" + backend
                + "): the production artifact did not emit: " + result.diagnostics());
        }
        return result;
    }

    /** Runs the LuaJIT production artifact under the deferred-entry probe
     *  and returns the probe's raw output. */
    private static String luaArtifactOutput(Compiled compiled) throws Exception {
        return luaArtifactOutput(compiled, Map.of());
    }

    /**
     * Runs the LuaJIT production artifact under the deferred-entry probe
     * with caller-owned host implementations staged for the named stems
     * (the cyclic-carrier drive), returning the probe's raw output.
     */
    private static String luaArtifactOutput(Compiled compiled,
            Map<String, String> hostOverrides) throws Exception {
        return luaArtifactOutput(compiled, hostOverrides,
            luaProbe(compiled.root().resolve("out").resolve(compiled
                .checkedProject().entryModule().path().replace('.', '/') + ".lua")
                .toAbsolutePath().toString(),
                compiled.checkedProject().entryModule().path()));
    }

    /**
     * Runs the LuaJIT production artifact under a caller-supplied probe
     * source (the production-wrapper delegation control) with
     * caller-owned host implementations staged for the named stems,
     * returning the probe's raw output.
     */
    private static String luaArtifactOutput(Compiled compiled,
            Map<String, String> hostOverrides, String probeSource) throws Exception {
        Path out = compiled.root().resolve("out");
        for (String stem : compiled.hostStems()) {
            Path host = out.resolve("host/" + stem + ".lua");
            Files.createDirectories(host.getParent());
            String override = hostOverrides.get(stem);
            if (override != null) {
                Files.writeString(host, override, StandardCharsets.UTF_8);
            } else {
                Files.copy(Path.of("test", "conformance", "host-fixtures",
                    stem + ".lua"), host, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.writeString(out.resolve("probe.lua"), probeSource,
            StandardCharsets.UTF_8);
        BoundedProcess.BoundedResult captured = BoundedProcess.runCaptured(
            List.of("luajit", "probe.lua"), out, Map.of("DEAL_DEFER_MAIN", "1"));
        String output = captured.stdout();
        String diagnostics = output + captured.stderr();
        check(!captured.stdoutTruncated() && !captured.stderrTruncated(),
            compiled.spec().what() + " (luajit): the probe output is not truncated: "
                + diagnostics);
        int exit = captured.exitCode();
        check(exit == 0, compiled.spec().what() + " (luajit): the probe runs: exit="
            + exit + " output=" + diagnostics);
        return output;
    }

    /** Runs the LuaJIT production artifact and captures its failure tuple. */
    private static Tuple luaArtifact(Compiled compiled) throws Exception {
        String output = luaArtifactOutput(compiled);
        Tuple tuple = parseLuaProbe(output, compiled);
        check(tuple != null, compiled.spec().what() + " (luajit): the probe "
            + "captures the failure: " + output);
        return tuple;
    }

    /**
     * Runs the LuaJIT production artifact and requires the deferred-entry
     * probe to complete without a failure event: the entry body consumed
     * the checked host value (the valid-carrier controls), so a wrong or
     * unprojected carrier cannot reach this outcome.
     */
    private static void luaArtifactOk(Compiled compiled, String label)
            throws Exception {
        String output = luaArtifactOutput(compiled);
        check(!output.contains("ERR|") && output.contains("OK"), label
            + " (luajit): the drive completes without a failure event: " + output);
    }

    /** The deferred-entry LuaJIT probe: the arm fields, framed. */
    private static String luaProbe(String artifact, String entryModulePath) {
        return """
            local function emit(e)
              if type(e) == "table" and e.__d then
                local file, line, column = nil, nil, nil
                if e.o ~= nil then
                  file, line, column = tostring(e.o):match("^(.*):(%%d+):(%%d+)$")
                end
                if file == nil and e.file ~= nil then
                  file, line, column = tostring(e.file), tostring(e.line),
                    tostring(e.column)
                end
                print("ERR|" .. tostring(e.code) .. "|" .. tostring(e.m) .. "|"
                  .. tostring(file) .. "|" .. tostring(line) .. "|"
                  .. tostring(column) .. "|" .. (e.e == nil and "" or tostring(e.e))
                  .. "|" .. (e.a == nil and "" or tostring(e.a)))
                return
              end
              if type(e) == "table" and e.code ~= nil then
                print("ERR|" .. tostring(e.code) .. "|" .. tostring(e.message) .. "|"
                  .. tostring(e.file) .. "|" .. tostring(e.line) .. "|"
                  .. tostring(e.column) .. "||")
                return
              end
              print("RAW|" .. tostring(e))
            end
            local chunk = dofile("%s")
            local ok, err = __dealMain()
            if not ok then emit(err) os.exit(0) end
            local surface = __exportSurfaces["%s"]
            if surface ~= nil then
              local names = {}
              for name, _ in pairs(surface) do names[#names + 1] = name end
              table.sort(names)
              for _, name in ipairs(names) do
                if string.sub(name, 1, 5) == "test_" then
                  local ok2, err2 = pcall(surface[name].f)
                  if not ok2 then emit(err2) os.exit(0) end
                end
              end
            end
            print("OK")
            """.formatted(artifact, entryModulePath);
    }

    private static Tuple parseLuaProbe(String output, Compiled compiled) {
        for (String line : output.lines().toList()) {
            if (!line.startsWith("ERR|")) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length != 8) {
                continue;
            }
            return tuple(parts[1], parts[2], parts[3], parts[4], parts[5], parts[6],
                parts[7], compiled);
        }
        return null;
    }

    /**
     * Runs the JVM production artifact under {@code javac} plus {@code java}
     * and returns the standard deferred-entry probe's raw output, or
     * {@code null} when the artifact did not compile (the failed check is
     * already recorded).
     */
    private static String jvmArtifactOutput(Compiled compiled) throws Exception {
        String entryModule = compiled.checkedProject().entryModule().path();
        return jvmArtifactOutput(compiled,
            jvmProbe(JvmBackend.classNameFor(entryModule), entryModule, ""));
    }

    /**
     * Runs the JVM production artifact with the given {@code Probe.java}
     * source (the standard deferred-entry probe, or a case-specific
     * observation probe) and returns the probe's raw output, or
     * {@code null} when the artifact did not compile (the failed check is
     * already recorded).
     */
    private static String jvmArtifactOutput(Compiled compiled, String probeSource)
            throws Exception {
        return jvmArtifactOutput(compiled, probeSource, Map.of());
    }

    /**
     * Runs the JVM production artifact with the given {@code Probe.java}
     * source and caller-owned host implementations staged for the named
     * stems (the cyclic-carrier drive): the host class source replaces the
     * corpus fixture's, so the emitted host ABI sees the caller's carrier.
     */
    private static String jvmArtifactOutput(Compiled compiled, String probeSource,
            Map<String, String> hostOverrides) throws Exception {
        return jvmArtifactOutput(compiled, probeSource, hostOverrides, List.of());
    }

    private static String jvmArtifactOutput(Compiled compiled, String probeSource,
            Map<String, String> hostOverrides, List<String> arguments) throws Exception {
        Path out = compiled.root().resolve("out-jvm");
        JvmPreparation preparation = compiled.jvmPreparation();
        if (!preparation.emitted) {
            emit(compiled, Backend.JVM, out);
            preparation.emitted = true;
        }
        if (preparation.defaultHosts == null) {
            Map<String, String> defaults = new LinkedHashMap<>();
            for (String stem : compiled.hostStems()) {
                Path host = Path.of("test", "conformance", "host-fixtures", stem + ".java");
                if (Files.isRegularFile(host)) {
                    defaults.put(stem, Files.readString(host, StandardCharsets.UTF_8));
                }
            }
            preparation.defaultHosts = Map.copyOf(defaults);
        }
        Map<String, String> hostSources = new LinkedHashMap<>(preparation.defaultHosts);
        for (String stem : compiled.hostStems()) {
            if (hostOverrides.containsKey(stem)) {
                hostSources.put(stem, hostOverrides.get(stem));
            }
            if (!hostSources.containsKey(stem)) {
                throw new IOException("no JVM host implementation for " + stem);
            }
        }
        String driverName = "CanonicalProjectionParityDriver";
        JvmDrive drive = new JvmDrive(probeSource, hostSources);
        Path classes = preparation.drives.get(drive);
        String classpath = absoluteClasspath();
        if (classes == null) {
            Path workspace = Files.createTempDirectory(compiled.root(), "jvm-drive-");
            classes = workspace.resolve("classes");
            Files.createDirectories(classes);
            boolean first = preparation.artifactClasses == null;
            String compileClasspath = first ? classpath
                : preparation.artifactClasses + File.pathSeparator + classpath;
            List<String> javacArgs = new ArrayList<>(List.of("javac", "--release",
                "25", "-proc:none", "-cp", compileClasspath, "-d", classes.toString()));
            if (first) {
                String className = JvmBackend.classNameFor(
                    compiled.checkedProject().entryModule().path());
                javacArgs.add(out.resolve(className + ".java").toAbsolutePath().toString());
            }
            for (Map.Entry<String, String> host : hostSources.entrySet()) {
                Path source = workspace.resolve(
                    JvmBackend.classNameFor("host/" + host.getKey()) + ".java");
                Files.writeString(source, host.getValue(), StandardCharsets.UTF_8);
                javacArgs.add(source.toString());
            }
            Path probeClasses = preparation.probes.get(probeSource);
            if (probeClasses == null) {
                Path driver = workspace.resolve(driverName + ".java");
                Files.writeString(driver, probeSource, StandardCharsets.UTF_8);
                javacArgs.add(driver.toString());
            }
            JvmCompilation.Result javac = JvmCompilation.compile(javacArgs, workspace);
            String javacOutput = javac.stdout() + javac.stderr();
            int javacExit = javac.exitCode();
            check(javacExit == 0, compiled.spec().what() + " (jvm): the production "
                + "artifact and drive compile: " + javacOutput);
            if (javacExit != 0) {
                return null;
            }
            if (first) {
                preparation.artifactClasses = classes;
            }
            if (probeClasses == null) {
                preparation.probes.put(probeSource, classes);
            }
            preparation.drives.put(drive, classes);
        }
        List<String> javaArgs = new ArrayList<>(List.of("java", "-cp",
            classes + File.pathSeparator + preparation.probes.get(probeSource)
                + File.pathSeparator + preparation.artifactClasses
                + File.pathSeparator + classpath, driverName));
        javaArgs.addAll(arguments);
        BoundedProcess.BoundedResult captured = BoundedProcess.runCaptured(
            javaArgs, out, Map.of());
        String output = captured.stdout();
        String diagnostics = output + captured.stderr();
        check(!captured.stdoutTruncated() && !captured.stderrTruncated(),
            compiled.spec().what() + " (jvm): the probe output is not truncated: "
                + diagnostics);
        int exit = captured.exitCode();
        check(exit == 0, compiled.spec().what() + " (jvm): the probe runs: exit="
            + exit + " output=" + diagnostics);
        return output;
    }

    /** Runs the JVM production artifact and captures its failure tuple. */
    private static Tuple jvmArtifact(Compiled compiled) throws Exception {
        String output = jvmArtifactOutput(compiled);
        if (output == null) {
            return null;
        }
        Tuple tuple = parseJvmProbe(output, compiled);
        check(tuple != null, compiled.spec().what() + " (jvm): the probe captures "
            + "the failure: " + output);
        return tuple;
    }

    /**
     * Runs the LuaJIT production artifact with a caller-owned host
     * implementation staged for one stem (the cyclic-carrier drive) and
     * captures the failure tuple.
     */
    private static Tuple luaArtifactWithHost(Compiled compiled, String stem,
            String hostSource) throws Exception {
        String output = luaArtifactOutput(compiled, Map.of(stem, hostSource));
        Tuple tuple = parseLuaProbe(output, compiled);
        check(tuple != null, compiled.spec().what() + " (luajit, host " + stem
            + "): the probe captures the failure: " + output);
        return tuple;
    }

    /**
     * Runs the JVM production artifact with a caller-owned host
     * implementation staged for one stem (the cyclic-carrier drive) and
     * captures the failure tuple.
     */
    private static Tuple jvmArtifactWithHost(Compiled compiled, String stem,
            String hostSource) throws Exception {
        String entryModule = compiled.checkedProject().entryModule().path();
        String output = jvmArtifactOutput(compiled,
            jvmProbe(JvmBackend.classNameFor(entryModule), entryModule, ""),
            Map.of(stem, hostSource));
        if (output == null) {
            return null;
        }
        Tuple tuple = parseJvmProbe(output, compiled);
        check(tuple != null, compiled.spec().what() + " (jvm, host " + stem
            + "): the probe captures the failure: " + output);
        return tuple;
    }

    /**
     * Runs the JVM production artifact and requires the probe to complete
     * without a failure event: the entry body consumed the checked host
     * value (the valid-carrier controls), so a wrong or unprojected carrier
     * (the raw UTF-8 element retained in the production array) cannot reach
     * this outcome.
     */
    private static void jvmArtifactOk(Compiled compiled, String label)
            throws Exception {
        String output = jvmArtifactOutput(compiled);
        check(output != null && !output.contains("ERR|") && output.contains("OK"),
            label + " (jvm): the drive completes without a failure event: "
                + output);
    }

    /**
     * The deferred-entry JVM probe source. {@code afterFailure} is an extra
     * statement executed after a failed entry (the case-specific retained
     * observation), or the empty string for the plain probe.
     */
    private static String jvmProbe(String className, String entryModulePath,
            String afterFailure) {
        return """
            final class CanonicalProjectionParityDriver {
              public static void main(String[] args) {
                try {
                  %s.dealMain();
                } catch (Throwable t) {
                  report(t);
                  %s
                  return;
                }
                deal.codegen.jvm.JvmRuntime.Table surface =
                    %s.EXPORT_SURFACES.get("%s");
                if (surface == null) { System.out.println("NOSURFACE"); return; }
                for (java.lang.String name : surface.keys) {
                  if (!name.startsWith("test_")) { continue; }
                  try {
                    ((deal.codegen.jvm.JvmRuntime.FunctionValue) surface.read(name))
                        .fn.invoke(new java.lang.Object[]{ });
                  } catch (Throwable t) {
                    report(t);
                    return;
                  }
                }
                System.out.println("OK");
              }
              private static void report(java.lang.Throwable t) {
                while (t instanceof java.lang.ExceptionInInitializerError
                        && t.getCause() != null) {
                  t = t.getCause();
                }
                try {
                  System.out.println("ERR|" + str(t, "code") + "|" + t.getMessage()
                    + "|" + str(t, "origin") + "|" + str(t, "expected") + "|"
                    + str(t, "actual"));
                } catch (java.lang.Exception unreadable) {
                  System.out.println("UNREADABLE|" + unreadable);
                }
              }
              private static java.lang.String str(java.lang.Throwable t,
                  java.lang.String name) throws java.lang.Exception {
                java.lang.reflect.Field field =
                    findField(t.getClass(), name);
                if (field == null) { return ""; }
                field.setAccessible(true);
                java.lang.Object value = field.get(t);
                return value == null ? "" : java.lang.String.valueOf(value);
              }
              private static java.lang.reflect.Field findField(
                  java.lang.Class<?> type, java.lang.String name)
                  throws java.lang.Exception {
                for (java.lang.Class<?> c = type; c != null; c = c.getSuperclass()) {
                  try {
                    return c.getDeclaredField(name);
                  } catch (java.lang.NoSuchFieldException absent) {
                    // the next level
                  }
                }
                return null;
              }
            }
            """.formatted(className, afterFailure, className, entryModulePath);
    }

    private static Tuple parseJvmProbe(String output, Compiled compiled) {
        for (String line : output.lines().toList()) {
            if (!line.startsWith("ERR|")) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length != 6) {
                continue;
            }
            return tuple(parts[1], parts[2], originFile(parts[3]),
                originLine(parts[3]), originColumn(parts[3]), parts[4], parts[5],
                compiled);
        }
        return null;
    }

    private static String originFile(String origin) {
        if (origin == null || origin.isEmpty()) {
            return null;
        }
        int lastColon = origin.lastIndexOf(':');
        int prevColon = lastColon <= 0 ? -1 : origin.lastIndexOf(':', lastColon - 1);
        return prevColon <= 0 ? null : origin.substring(0, prevColon);
    }

    private static String originLine(String origin) {
        if (origin == null || origin.isEmpty()) {
            return null;
        }
        int lastColon = origin.lastIndexOf(':');
        int prevColon = lastColon <= 0 ? -1 : origin.lastIndexOf(':', lastColon - 1);
        return prevColon <= 0 ? null : origin.substring(prevColon + 1, lastColon);
    }

    private static String originColumn(String origin) {
        if (origin == null || origin.isEmpty()) {
            return null;
        }
        int lastColon = origin.lastIndexOf(':');
        return lastColon <= 0 ? null : origin.substring(lastColon + 1);
    }

    /**
     * Builds one capture tuple from the probe's raw fields: the corpus
     * path and the raw line rebased by the module's stripped header-line
     * count; an absent field stays absent (never fabricated).
     */
    private static Tuple tuple(String code, String message, String file, String line,
            String column, String expected, String actual, Compiled compiled) {
        String corpusPath = null;
        Integer rawLine = null;
        Integer columnValue = null;
        if (file != null && !file.isEmpty() && !"nil".equals(file)) {
            corpusPath = corpusPathOf(compiled, file, compiled.spec().fixtureFile());
            rawLine = Integer.valueOf(line);
            columnValue = Integer.valueOf(column);
        }
        return new Tuple(code, normalize(message, compiled), corpusPath,
            rawLine == null ? null : rawLine + compiled.strippedByCorpusPath()
                .getOrDefault(corpusPath, 0),
            columnValue, emptyToNull(normalize(expected, compiled)),
            emptyToNull(normalize(actual, compiled)));
    }

    // =========================================================================
    // The emitted prelude (the arm table's serialized renderer)
    // =========================================================================

    /** The arm-only production chunk (the serialized table and renderer). */
    private static String armOnlyChunk() {
        if (armChunk == null) {
            armChunk = emitArmOnlyChunk();
        }
        return armChunk;
    }

    private static String emitArmOnlyChunk() {
        ModuleId entry = new ModuleId("arms");
        deal.semantic.ir.LoweredModuleUnit unit =
            new deal.semantic.ir.LoweredModuleUnit(
                deal.semantic.ir.LoweredModuleUnit.FORMAT_VERSION,
                deal.semantic.ir.SemanticProfile.DEAL_V1_2_INT32, entry, "hash",
                "context", Set.of(), Map.of(), Map.of(), Map.of(),
                new deal.semantic.ir.ModuleInitPlan(List.of(),
                    new deal.semantic.ir.BlockId(0)),
                new deal.semantic.ir.ExportPlan(List.of()), Map.of());
        Map<ModuleId, deal.semantic.ir.LoweredModuleUnit> modules =
            new LinkedHashMap<>();
        modules.put(entry, unit);
        ProjectInterfaceIndex index = new ProjectInterfaceIndex(
            ProjectInterfaceIndex.FORMAT_VERSION,
            Map.of(entry, new deal.semantic.ir.ExternalModuleInterface(entry,
                deal.semantic.ir.ExternalModuleKind.IMPLEMENTATION, List.of(), List.of(),
                List.of(), deal.semantic.ir.InitializationMode.ONCE_AFTER_DEPENDENCIES)));
        ExecutableLoweredProject project = new ExecutableLoweredProject(
            deal.semantic.ir.SemanticProfile.DEAL_V1_2_INT32, index, modules, entry);
        Map<ModuleId, StructuredBodyTable> tables = new LinkedHashMap<>();
        tables.put(entry, new StructuredBodyTable(
            Map.of(new deal.semantic.ir.BlockId(0), List.of()), Map.of()));
        return LuaSemanticEmitter.emitProductionProject(project, tables, Map.of(),
            new HostDeclarationSurface(Map.of()));
    }

    /**
     * Runs the emitted prelude's {@code __arm}/{@code __innerArm} renderers
     * for the arm rows under real {@code luajit}: one
     * {@code ROW|id|code|message|origin|expected|actual} line per render.
     */
    private static Map<String, String> luaArmRows(String origin) throws Exception {
        Path workspace = Files.createTempDirectory("projection-arms");
        try {
            Path artifact = workspace.resolve("arms.lua");
            Files.writeString(artifact, armOnlyChunk(), StandardCharsets.UTF_8);
            deployRuntime(workspace);
            StringBuilder body = new StringBuilder();
            body.append("local function row(id, values, expected, actual)\n")
                .append("  local ok, err = pcall(function() error(__arm(id, values, ")
                .append(luaString(origin)).append(", expected, actual), 0) end)\n")
                .append("  if ok then print(\"ROW|\"..id..\"|DEFECT|no render\") ")
                .append("return end\n")
                .append("  if type(err) ~= \"table\" then ")
                .append("print(\"ROW|\"..id..\"|DEFECT|\"..tostring(err)) return end\n")
                .append("  print(\"ROW|\"..id..\"|\"..tostring(err.code)..\"|\"")
                .append("..tostring(err.m)..\"|\"..tostring(err.o)..\"|\"")
                .append("..(err.e == nil and \"\" or tostring(err.e))..\"|\"")
                .append("..(err.a == nil and \"\" or tostring(err.a)))\nend\n");
            for (Render row : ARM_RENDERS) {
                body.append("row(\"").append(row.id().name()).append("\", ")
                    .append(luaTable(row.values())).append(", ")
                    .append(luaValue(row.expected())).append(", ")
                    .append(luaValue(row.actual())).append(")\n");
            }
            Path probe = workspace.resolve("probe.lua");
            Files.writeString(probe, """
                local text = io.open("%s"):read("*a")
                text = text:gsub("%%s*$", "")
                local tail = 'return __exportSurfaces["arms"]'
                assert(text:sub(-#tail) == tail, "the artifact tail is the surface return")
                local chunk = assert(load(text:sub(1, #text - #tail) .. [==[
                %s]==], "arms"))
                chunk()
                """.formatted(artifact.toAbsolutePath().toString(), body),
                StandardCharsets.UTF_8);
            BoundedProcess.BoundedResult captured = BoundedProcess.runCaptured(
                List.of("luajit", "probe.lua"), workspace, Map.of());
            String output = captured.stdout();
            String diagnostics = output + captured.stderr();
            check(!captured.stdoutTruncated() && !captured.stderrTruncated(),
                "the emitted prelude output is not truncated: " + diagnostics);
            int exit = captured.exitCode();
            check(exit == 0, "the emitted prelude runs under luajit: exit=" + exit
                + " output=" + diagnostics);
            return parseRows(output, "the emitted prelude");
        } finally {
            deleteRecursively(workspace);
        }
    }

    /** The two inner-only carrier arms' renderers under real {@code luajit}. */
    private static Map<String, String> luaInnerArmRows() throws Exception {
        Path workspace = Files.createTempDirectory("projection-inner-arms");
        try {
            Path artifact = workspace.resolve("arms.lua");
            Files.writeString(artifact, armOnlyChunk(), StandardCharsets.UTF_8);
            Path probe = workspace.resolve("probe.lua");
            Files.writeString(probe, """
                local text = io.open("%s"):read("*a")
                text = text:gsub("%%s*$", "")
                local tail = 'return __exportSurfaces["arms"]'
                local body = [==[
                print("ROW|HOST_STRING_INVALID_UTF8|" .. __innerArm(
                  "HOST_STRING_INVALID_UTF8", {}))
                print("ROW|HOST_STRING_SURROGATE|" .. __innerArm(
                  "HOST_STRING_SURROGATE", {}))
                __arms["MARKED_PROBE"] = {c = "E8001", t = "marked", e = "NONE",
                  a = "SIBLING_OWNED", s = "TOP_LEVEL", o = "CALL_EXPRESSION", k = ""}
                local ok, err = pcall(__arm, "MARKED_PROBE", nil, "-", nil, nil)
                print("ROW|MARKED_PROBE|" .. (ok and "RENDERED" or "DEFECT"))
                local ok2, err2 = pcall(function() error(__arm("HOST_STRING_SURROGATE",
                  {}, "-", nil, nil), 0) end)
                print("ROW|HOST_STRING_TOP_LEVEL|" .. (ok2 and "RENDERED" or "DEFECT"))
                __arms["MARKED_ORIGIN"] = {c = "E8001",
                  t = "cyclic value cannot be encoded as JSON", e = "NONE",
                  a = "NONE", s = "TOP_LEVEL", o = "SIBLING_OWNED", k = ""}
                local ok3 = pcall(__arm, "MARKED_ORIGIN", nil, "-", nil, nil)
                print("ROW|MARKED_ORIGIN|" .. (ok3 and "RENDERED" or "DEFECT"))
                __arms["MARKED_SOURCE"] = {c = "E8001",
                  t = "value at {fieldPath} is not JSON serializable: {actual}",
                  e = "NONE", a = "TYPED_BOUNDARY", s = "TOP_LEVEL",
                  o = "CALL_EXPRESSION",
                  k = "fieldPath=FIELD_PATH,actual=SIBLING_OWNED_ACTUAL"}
                local ok4 = pcall(__arm, "MARKED_SOURCE",
                  {fieldPath = "data.k", actual = "function"}, "-", nil, "function")
                print("ROW|MARKED_SOURCE|" .. (ok4 and "RENDERED" or "DEFECT"))
                local ok5, value5 = pcall(__arm, "JSON_TO_WALK",
                  {fieldPath = "data.k", actual = "function"}, "-", nil, "function")
                print("ROW|WALK_CONTROL|" .. (ok5 and value5.m or "DEFECT"))
                local ok6, value6 = pcall(__arm, "JSON_TO_WALK_CYCLE", nil, "-",
                  nil, nil)
                print("ROW|CYCLE_CONTROL|" .. (ok6 and value6.m or "DEFECT"))
                __arms["MARKED_INNER_SOURCE"] = {c = "E8010",
                  t = "parameter {index} type mismatch: {inner}", e = "NONE",
                  a = "NONE", s = "INNER_ONLY", o = "CALL_EXPRESSION",
                  k = "index=PARAMETER_INDEX,inner=SIBLING_OWNED_ACTUAL"}
                local ok7 = pcall(__innerArm, "MARKED_INNER_SOURCE",
                  {index = "1", inner = "the inner reason"})
                print("ROW|MARKED_INNER_SOURCE|" .. (ok7 and "RENDERED" or "DEFECT"))
                ]==]
                local chunk = assert(load(text:sub(1, #text - #tail) .. body, "arms"))
                chunk()
                """.formatted(artifact.toAbsolutePath().toString()),
                StandardCharsets.UTF_8);
            BoundedProcess.BoundedResult captured = BoundedProcess.runCaptured(
                List.of("luajit", "probe.lua"), workspace, Map.of());
            String output = captured.stdout();
            String diagnostics = output + captured.stderr();
            check(!captured.stdoutTruncated() && !captured.stderrTruncated(),
                "the emitted prelude's inner renderer output is not truncated: "
                    + diagnostics);
            check(captured.exitCode() == 0, "the emitted prelude's inner renderer runs "
                + "under luajit: " + diagnostics);
            Map<String, String> rows = new LinkedHashMap<>();
            for (String line : output.lines().toList()) {
                if (line.startsWith("ROW|")) {
                    rows.put(line.substring("ROW|".length(), line.indexOf('|', 4)),
                        line.substring(line.indexOf('|', 4) + 1));
                }
            }
            return rows;
        } finally {
            deleteRecursively(workspace);
        }
    }

    private static void deployRuntime(Path workspace) throws Exception {
        Path runtime = workspace.resolve("deal/runtime.lua");
        Files.createDirectories(runtime.getParent());
        Files.copy(Path.of("deal", "runtime.lua"), runtime);
        Path std = workspace.resolve("std");
        Files.createDirectories(std);
        try (Stream<Path> listing = Files.list(Path.of("std"))) {
            for (Path file : listing.sorted().toList()) {
                if (file.getFileName().toString().endsWith(".lua")) {
                    Files.copy(file, std.resolve(file.getFileName()));
                }
            }
        }
    }

    private static String luaString(String text) {
        return text == null ? "nil"
            : "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String luaValue(String text) {
        return luaString(text);
    }

    private static String luaTable(Map<String, String> values) {
        StringBuilder out = new StringBuilder("{");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (out.length() > 1) {
                out.append(", ");
            }
            out.append("[").append(luaString(entry.getKey())).append("]=")
                .append(luaValue(entry.getValue()));
        }
        return out.append("}").toString();
    }

    // =========================================================================
    // The production artifact runners
    // =========================================================================

    private static String emptyToNull(String text) {
        return text == null || text.isEmpty() ? null : text;
    }

    private static String absoluteClasspath() {
        StringBuilder resolved = new StringBuilder();
        for (String entry : System.getProperty("java.class.path", "")
                .split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            if (resolved.length() > 0) {
                resolved.append(File.pathSeparator);
            }
            resolved.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return resolved.toString();
    }

    // =========================================================================
    // 3b-3c. The inner-only arms, the marked arms, and the field shapes
    // =========================================================================

    private static void expectDefect(Runnable action, String message) {
        try {
            action.run();
            failed++;
            System.err.println("FAIL: " + message + " (no defect thrown)");
        } catch (RuntimeException expected) {
            passed++;
        }
    }

    private static void testMarkedArmsAndFieldShapes() throws Exception {
        System.out.println("-- the inner-only arms, the marked-arm guard, and the "
            + "declared field shapes fail closed --");
        Map<String, String> inner = luaInnerArmRows();
        for (FailureArmId id : List.of(FailureArmId.HOST_STRING_INVALID_UTF8,
                FailureArmId.HOST_STRING_SURROGATE)) {
            String authority = FailureContractRegistry.renderInner(id, Map.of());
            checkEq(authority, inner.get(id.name()), id + ": the emitted prelude "
                + "renders the inner-only arm's own text");
            boolean surrogate = id == FailureArmId.HOST_STRING_SURROGATE;
            checkEq(authority, deal.codegen.jvm.JvmRuntime.stringCarrierReason(
                surrogate), id + ": the JVM runtime renders the same inner text");
            expectDefect(() -> FailureContractRegistry.render(id, Map.of(), null,
                null, null), id + " rendered top-level");
            expectDefect(() -> deal.codegen.jvm.JvmRuntime.arm(id, Map.of(), "-",
                null, null), id + " rendered top-level by the JVM runtime");
        }
        check(inner.getOrDefault("MARKED_PROBE", "").startsWith("DEFECT"),
            "the emitted prelude refuses a SIBLING_OWNED entry injected into the "
                + "chunk-level arm table: " + inner);
        check(inner.getOrDefault("MARKED_ORIGIN", "").startsWith("DEFECT"),
            "the emitted prelude refuses a SIBLING_OWNED origin convention injected "
                + "into the chunk-level arm table, even with a supplied origin "
                + "operand: " + inner);
        check(inner.getOrDefault("MARKED_SOURCE", "").startsWith("DEFECT"),
            "the emitted prelude refuses a SIBLING_OWNED_ACTUAL parameter source "
                + "injected into the chunk-level arm table, even with its declared "
                + "parameters and fields supplied: " + inner);
        check(inner.getOrDefault("MARKED_INNER_SOURCE", "").startsWith("DEFECT"),
            "the emitted prelude's parameter parser refuses a SIBLING_OWNED_ACTUAL "
                + "parameter source on the inner-only render path: " + inner);
        checkEq("value at data.k is not JSON serializable: function",
            inner.getOrDefault("WALK_CONTROL", ""),
            "the emitted prelude keeps rendering the valid walk arm after the "
                + "marked-slot guards");
        checkEq("cyclic value cannot be encoded as JSON",
            inner.getOrDefault("CYCLE_CONTROL", ""),
            "the emitted prelude keeps rendering the valid cycle arm after the "
                + "marked-slot guards");
        check(inner.getOrDefault("HOST_STRING_TOP_LEVEL", "").startsWith("DEFECT"),
            "the emitted prelude refuses an INNER_ONLY arm rendered top-level: "
                + inner);
        // The walk arm is bound (ISSUE-0711): the production consumers render
        // its declared fields, and the marker's remaining fail-closed subject
        // is a hand-built marked arm fed to the data-driven completeness check.
        BoundaryFailure walk = FailureContractRegistry.render(
            FailureArmId.JSON_TO_WALK, Map.of("fieldPath", "a", "actual", "table"),
            null, "table", null);
        checkEq("value at a is not JSON serializable: table", walk.message(),
            "the bound walk arm renders its own template through the registry");
        deal.codegen.jvm.JvmRuntime.DealError walkJvm =
            deal.codegen.jvm.JvmRuntime.arm(FailureArmId.JSON_TO_WALK,
                Map.of("fieldPath", "a", "actual", "table"), "-", null, "table");
        checkEq(walk.message(), walkJvm.msg,
            "the JVM runtime renders the bound walk arm's identical message");
        FailureArm walkArm = FailureContractRegistry.arm(FailureArmId.JSON_TO_WALK);
        List<FailureArm> marked = new ArrayList<>(
            FailureContractRegistry.arms().values());
        marked.set(marked.indexOf(walkArm), new FailureArm(walkArm.id(),
            walkArm.policy(), walkArm.templateIndex(), walkArm.template(),
            walkArm.parameters(), walkArm.parameterSources(), walkArm.expectedSource(),
            null, FailureArm.ActualProjection.SIBLING_OWNED, walkArm.origin(),
            walkArm.scope(), walkArm.code()));
        expectDefect(() -> FailureContractRegistry.checkArmConsistency(
            FailureContractRegistry.rows(), marked),
            "a hand-built arm whose projection binding is SIBLING_OWNED fails the "
                + "completeness check");
        expectDefect(() -> FailureContractRegistry.render(
            FailureArmId.TYPED_BOUNDARY_KIND, Map.of("kind", "int", "extra", "x"),
            "int", "string", null), "an extra named parameter");
        expectDefect(() -> FailureContractRegistry.render(
            FailureArmId.TYPED_BOUNDARY_KIND, Map.of(), "int",
            "string", null), "a missing named parameter");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.INT32_RANGE,
            Map.of(), "int", null, null), "a fabricated expected token on a "
            + "field-less arm");
        expectDefect(() -> FailureContractRegistry.render(
            FailureArmId.TYPED_BOUNDARY_KIND, Map.of("kind", "integer"), "int",
            "string", null), "an invented kind text");
    }

    /**
     * The host inner-reason vocabulary (P2 item 3): the descriptor kind, the
     * int refinement, the signed32-range pass-through, the two string-carrier
     * texts, and the signature/identity/element pass-throughs render through
     * the oracle's host cells and the JVM runtime's own producers; the emitted
     * prelude's host cells render the descriptor-kind, range, and string
     * entries under real {@code luajit}.
     */
    private static void testHostInnerReasonPassThroughs() throws Exception {
        System.out.println("-- the host inner-reason vocabulary and its "
            + "pass-throughs --");
        BoundaryFailure range = cell(FailurePolicyId.HOST_SYNC_RETURN,
            RuntimeDescriptor.Int.INSTANCE, BoundaryValueView.ofNumber(1e10),
            BoundaryContext.none());
        checkEq("return value 1 type mismatch: " + FailureContractRegistry
            .arm(FailureArmId.INT32_RANGE).template(), range.message(),
            "the signed32-range pass-through renders the range arm's own text");
        checkEq("int", range.expected(), "the range cell keeps the declared "
            + "descriptor as its expected field");
        checkEq("number", range.actual(), "the range cell projects the "
            + "carrier-kind actual");
        checkEq(FailureContractRegistry.arm(FailureArmId.INT32_RANGE).template(),
            deal.codegen.jvm.JvmRuntime.rangeReason(),
            "the JVM runtime renders the same signed32-range text");

        BoundaryFailure refinement = cell(FailurePolicyId.HOST_SYNC_RETURN,
            RuntimeDescriptor.Int.INSTANCE, BoundaryValueView.ofNumber(Double.NaN),
            BoundaryContext.none());
        checkEq("return value 1 type mismatch: " + FailureContractRegistry
            .arm(FailureArmId.INT_CONVERSION_NAN).template(), refinement.message(),
            "the int refinement renders its own arm text as the inner reason");
        checkEq(FailureContractRegistry.arm(FailureArmId.INT_CONVERSION_NAN).template(),
            deal.codegen.jvm.JvmRuntime.refinementReason("NaN"),
            "the JVM runtime renders the same refinement text");

        BoundaryFailure kindInner = cell(FailurePolicyId.HOST_PARAMETER,
            RuntimeDescriptor.Bytes.INSTANCE, BoundaryValueView.ofNumber(3.0),
            BoundaryContext.parameter(1));
        checkEq("parameter 1 type mismatch: "
            + FailureProjections.kindReason(RuntimeDescriptor.Bytes.INSTANCE),
            kindInner.message(), "the descriptor-kind inner reason is the kind "
                + "arm's own text");
        checkEq("bytes", kindInner.expected(), "the parameter cell keeps the "
            + "declared descriptor");
        checkEq("number", kindInner.actual(), "the parameter cell projects the "
            + "carrier-kind actual");
        checkEq(FailureProjections.kindReason(RuntimeDescriptor.Bytes.INSTANCE),
            deal.codegen.jvm.JvmRuntime.kindReason("bytes"),
            "the JVM runtime renders the same descriptor-kind text");

        BoundaryFailure signature = cell(FailurePolicyId.HOST_PARAMETER,
            RuntimeDescriptor.parseCanonicalText("?(int)->int"),
            BoundaryValueView.ofFunction(
                (RuntimeDescriptor.Func) RuntimeDescriptor.parseCanonicalText(
                    "(string)->int")),
            BoundaryContext.parameter(1));
        checkEq("parameter 1 type mismatch: function signature mismatch: expected "
            + "(int)->int, got (string)->int", signature.message(),
            "the signature pass-through renders the signature arm's own text");
        checkEq(FailureContractRegistry.arm(
            FailureArmId.FUNCTION_SIGNATURE_MISMATCH).template()
                .replace("{expected}", "(int)->int").replace("{actual}",
                    "(string)->int"), signature.message().substring(
                        "parameter 1 type mismatch: ".length()),
            "the inner reason is the signature arm's instantiated template");

        BoundaryFailure identity = cell(FailurePolicyId.HOST_PARAMETER,
            RuntimeDescriptor.parseCanonicalText("@corpus/a/A"),
            BoundaryValueView.ofClass("@corpus/b/B"), BoundaryContext.parameter(1));
        checkEq("parameter 1 type mismatch: expected instance of @corpus/a/A, got "
            + "@corpus/b/B", identity.message(),
            "the identity pass-through renders the identity arm's own text");
        checkEq(FailureContractRegistry.arm(FailureArmId.CLASS_IDENTITY).template()
                .replace("{expected}", "@corpus/a/A").replace("{actual}",
                    "@corpus/b/B"),
            deal.codegen.jvm.JvmRuntime.identityReason("@corpus/a/A", "@corpus/b/B"),
            "the JVM runtime renders the same identity text");

        BoundaryFailure element = cell(FailurePolicyId.HOST_PARAMETER,
            RuntimeDescriptor.parseCanonicalText("[int]"),
            BoundaryValueView.ofArray(BoundaryValueView.ofInt(1),
                BoundaryValueView.of(ActualKind.STRING)),
            BoundaryContext.parameter(1));
        checkEq("parameter 1 type mismatch: array element 2 type mismatch",
            element.message(), "the element pass-through renders the element "
                + "arm's own text");
        checkEq(FailureContractRegistry.arm(FailureArmId.ARRAY_ELEMENT_KIND).template()
                .replace("{oneBasedIndex}", "2"),
            deal.codegen.jvm.JvmRuntime.elementReason(2),
            "the JVM runtime renders the same element text");

        BoundaryFailure stringCarrier = cell(FailurePolicyId.HOST_SYNC_RETURN,
            RuntimeDescriptor.String.INSTANCE,
            BoundaryValueView.ofInvalidUnicode(UnicodeScalars.Defect.MALFORMED),
            BoundaryContext.none());
        checkEq("return value 1 type mismatch: " + FailureContractRegistry
            .renderInner(FailureArmId.HOST_STRING_INVALID_UTF8, Map.of()),
            stringCarrier.message(), "the invalid-UTF-8 inner reason is the "
                + "inner-only arm's own text");
        checkEq("string", stringCarrier.expected(), "the invalid-UTF-8 carrier cell "
            + "keeps the declared string descriptor");
        checkEq("string", stringCarrier.actual(), "the invalid-UTF-8 carrier cell "
            + "projects the string carrier kind");
        BoundaryFailure surrogateCarrier = cell(FailurePolicyId.HOST_SYNC_RETURN,
            RuntimeDescriptor.String.INSTANCE,
            BoundaryValueView.ofInvalidUnicode(UnicodeScalars.Defect.SURROGATE),
            BoundaryContext.none());
        checkEq("return value 1 type mismatch: " + FailureContractRegistry
            .renderInner(FailureArmId.HOST_STRING_SURROGATE, Map.of()),
            surrogateCarrier.message(), "the surrogate inner reason is the "
                + "inner-only arm's own text");
        checkEq("string", surrogateCarrier.expected(), "the surrogate carrier cell "
            + "keeps the declared string descriptor");
        checkEq("string", surrogateCarrier.actual(), "the surrogate carrier cell "
            + "projects the string carrier kind");
        check(BoundaryExecutor.check(FailurePolicyId.HOST_SYNC_RETURN,
            RuntimeDescriptor.String.INSTANCE, BoundaryValueView.of(ActualKind.STRING),
            BoundaryContext.none()) instanceof BoundaryOutcome.Pass,
            "the host return cell admits the valid string carrier (the control)");
        checkEq(FailureContractRegistry.renderInner(
            FailureArmId.HOST_STRING_SURROGATE, Map.of()),
            deal.codegen.jvm.JvmRuntime.stringCarrierReason(true),
            "the JVM runtime renders the surrogate inner text");

        // The JVM producer's own selection (the emitted seam delegates to it):
        // a lone surrogate at any position is the surrogate class, the raw
        // byte carrier's first defect class selects the reason, and valid
        // carriers are admitted — never the carrier's length.
        String surrogateText = FailureContractRegistry.renderInner(
            FailureArmId.HOST_STRING_SURROGATE, Map.of());
        String invalidText = FailureContractRegistry.renderInner(
            FailureArmId.HOST_STRING_INVALID_UTF8, Map.of());
        checkEq(surrogateText, deal.codegen.jvm.JvmRuntime.hostStringReason("\uD800"),
            "the JVM host carrier selector renders the surrogate reason for a lone "
                + "surrogate");
        checkEq(surrogateText, deal.codegen.jvm.JvmRuntime.hostStringReason("a\uD800b"),
            "the JVM host carrier selector renders the surrogate reason for an "
                + "embedded lone surrogate (never the invalid-UTF-8 reason)");
        checkEq(invalidText, deal.codegen.jvm.JvmRuntime.hostStringReason(
            RAW_MALFORMED_STRING),
            "the JVM host carrier selector renders the invalid-UTF-8 reason for a "
                + "malformed non-surrogate byte sequence");
        checkEq(surrogateText, deal.codegen.jvm.JvmRuntime.hostStringReason(
            new byte[] { 0x61, (byte) 0xED, (byte) 0xA0, (byte) 0x80, 0x62 }),
            "the JVM host carrier selector renders the surrogate reason for an "
                + "embedded surrogate code point encoding");
        checkEq(null, deal.codegen.jvm.JvmRuntime.hostStringReason("ok"),
            "the JVM host carrier selector admits the valid string carrier");
        checkEq(null, deal.codegen.jvm.JvmRuntime.hostStringReason(
            new byte[] { 0x6F, 0x6B }),
            "the JVM host carrier selector admits the valid byte carrier");

        // The deployed runtime's matcher under real luajit: the same inner
        // reason texts the oracle and the JVM runtime compose.
        Map<String, String> luaRows = luaRuntimeInnerReasons();
        checkEq(FailureProjections.kindReason(RuntimeDescriptor.Bytes.INSTANCE),
            luaRows.get("kind"), "the loaded matcher renders the descriptor-kind "
                + "inner reason");
        checkEq(FailureContractRegistry.arm(FailureArmId.INT32_RANGE).template(),
            luaRows.get("range"), "the loaded matcher forwards the signed32-range "
                + "inner reason");
        checkEq(FailureContractRegistry.renderInner(
            FailureArmId.HOST_STRING_INVALID_UTF8, Map.of()), luaRows.get("utf8"),
            "the loaded matcher renders the invalid-UTF-8 inner reason");
        checkEq(FailureContractRegistry.renderInner(
            FailureArmId.HOST_STRING_SURROGATE, Map.of()),
            luaRows.get("utf8-surrogate"), "the loaded matcher renders the "
                + "surrogate inner reason for the surrogate code point encoding");
        checkEq(FailureContractRegistry.renderInner(
            FailureArmId.HOST_STRING_SURROGATE, Map.of()),
            luaRows.get("utf8-surrogate-embedded"), "the loaded matcher renders "
                + "the surrogate inner reason for an embedded surrogate code point "
                + "encoding (never the invalid-UTF-8 reason)");
        checkEq("PASS", luaRows.get("utf8-valid"),
            "the loaded matcher admits the valid string carrier");
        checkEq(FailureContractRegistry.arm(FailureArmId.ARRAY_ELEMENT_KIND).template()
                .replace("{oneBasedIndex}", "2"), luaRows.get("element"),
            "the loaded matcher renders the element inner reason");
    }

    /** One oracle cell render, or a fail-closed report. */
    private static BoundaryFailure cell(FailurePolicyId policy,
            RuntimeDescriptor descriptor, BoundaryValueView view,
            BoundaryContext context) {
        BoundaryOutcome outcome = BoundaryExecutor.check(policy, descriptor, view,
            context);
        if (outcome instanceof BoundaryOutcome.Fail fail) {
            return fail.failure();
        }
        throw new IllegalStateException("the host cell passed: " + policy + " "
            + descriptor.canonicalSpecText());
    }

    /**
     * The deployed runtime's matcher under real {@code luajit}: the inner
     * reason texts the LuaJIT target composes for a host cell (the loaded
     * matcher's own vocabulary, the third-backend authority).
     */
    private static Map<String, String> luaRuntimeInnerReasons() throws Exception {
        Path workspace = Files.createTempDirectory("projection-host-cells");
        try {
            deployRuntime(workspace);
            Path probe = workspace.resolve("probe.lua");
            Files.writeString(probe, """
                local rt = require("deal.runtime")
                rt.__INT32 = true
                local function row(label, desc, v)
                  local ok, err = pcall(rt.check_type, desc, v)
                  if ok then print("CELL|"..label.."|PASS") return end
                  print("CELL|"..label.."|"..tostring(err.message or err.m))
                end
                row("kind", "bytes", 3)
                row("range", "int", 1e10)
                row("utf8", "string", "a" .. string.char(128) .. "b")
                row("utf8-surrogate", "string", string.char(237, 160, 128))
                row("utf8-surrogate-embedded", "string",
                  "a" .. string.char(237, 160, 128) .. "b")
                row("utf8-valid", "string", "ok")
                row("element", "[int]", {1, "x"})
                """, StandardCharsets.UTF_8);
            BoundedProcess.BoundedResult captured = BoundedProcess.runCaptured(
                List.of("luajit", "probe.lua"), workspace, Map.of());
            String output = captured.stdout();
            String diagnostics = output + captured.stderr();
            check(!captured.stdoutTruncated() && !captured.stderrTruncated(),
                "the deployed matcher output is not truncated: " + diagnostics);
            check(captured.exitCode() == 0, "the deployed matcher runs under luajit: "
                + diagnostics);
            Map<String, String> rows = new LinkedHashMap<>();
            for (String line : output.lines().toList()) {
                if (line.startsWith("CELL|")) {
                    String[] parts = line.split("\\|", -1);
                    rows.put(parts[1], parts[2]);
                }
            }
            return rows;
        } finally {
            deleteRecursively(workspace);
        }
    }

    // =========================================================================
    // 4. The FOR_EACH terminal check over a deleted array-element slot
    // =========================================================================

    /** The deleted-slot seed: the FOR_EACH op's terminal element check. */
    private static final String FOREACH_DELETED_SLOT_SOURCE = """
        function main(): null {
          let xs: int[] = [1, 2];
          for (let e: int of xs) {
            if (e === 1) { delete xs[1]; }
          }
          return null;
        }
        """;

    private static final ModuleId MATRIX_MODULE = new ModuleId("main");
    private static final String MATRIX_SOURCE_ID = "test.deal";

    private record Seed(deal.semantic.ir.LoweredModuleUnit unit,
                        StructuredBodyTable table) {
    }

    /** One in-memory front-end seed: the checked project and its manifests. */
    private record CheckedSeed(deal.semantic.CheckedModuleInput input,
                               CheckedProjectBuildResult built,
                               RequirementManifestResult manifests) {
    }

    /**
     * The in-memory front end both lowering drives share: lexer → parser
     * → resolver → checker → checked project → manifests.
     */
    private static CheckedSeed checkedSeed(String source, String what,
            CompilerInvocation invocation) {
        deal.lexer.LexResult lexed = new deal.lexer.Lexer(source, MATRIX_SOURCE_ID)
            .tokenize();
        deal.parser.ParseResult parsed = new deal.parser.Parser(lexed.tokens(),
            MATRIX_SOURCE_ID).parse();
        check(parsed.diagnostics().isEmpty(), what + ": the seed parses: "
            + parsed.diagnostics());
        if (!parsed.diagnostics().isEmpty()) {
            return null;
        }
        deal.checker.ModuleResolver resolver = new deal.checker.ModuleResolver() {
            @Override
            public Map<String, deal.types.Type> resolveModule(String modulePath,
                    String importingModule, Set<String> modulesInProgress)
                    throws deal.checker.ModuleResolver.ModuleNotFoundException {
                throw new deal.checker.ModuleResolver.ModuleNotFoundException(
                    "Module not found: " + modulePath);
            }

            @Override
            public deal.checker.Symbol.ClassSymbol resolveClassSymbol(String className,
                    String modulePath, String importingModule)
                    throws deal.checker.ModuleResolver.ModuleNotFoundException {
                return null;
            }
        };
        deal.checker.NameResolver nameResolver = new deal.checker.NameResolver(
            MATRIX_SOURCE_ID, resolver);
        deal.checker.SymbolTable symbols = nameResolver.resolve(parsed.program());
        check(nameResolver.diagnostics().isEmpty(), what + ": the seed resolves: "
            + nameResolver.diagnostics());
        if (!nameResolver.diagnostics().isEmpty()) {
            return null;
        }
        deal.checker.CheckResult checks = deal.checker.TypeChecker.check(MATRIX_SOURCE_ID,
            symbols, nameResolver, parsed.program());
        check(checks.diagnostics().isEmpty(), what + ": the seed checks: "
            + checks.diagnostics());
        if (!checks.diagnostics().isEmpty()) {
            return null;
        }
        deal.module.ExportExtractor exportExtractor =
            new deal.module.ExportExtractor(MATRIX_MODULE.path(), false);
        Map<String, deal.types.Type> exports = exportExtractor.extract(parsed.program());
        check(exportExtractor.diagnostics().isEmpty(),
            what + ": the exports extract: " + exportExtractor.diagnostics());
        if (!exportExtractor.diagnostics().isEmpty()) {
            return null;
        }
        deal.semantic.CheckedModuleInput input = new deal.semantic.CheckedModuleInput(
            MATRIX_MODULE, MATRIX_SOURCE_ID, Path.of(MATRIX_SOURCE_ID),
            parsed.program(), checks, List.of(), List.of(),
            deal.semantic.CheckedModuleKind.IMPLEMENTATION);
        deal.semantic.ModuleFact fact = new deal.semantic.ModuleFact(MATRIX_SOURCE_ID,
            MATRIX_MODULE, false, false, parsed.program(), exports, symbols, checks,
            List.of());
        CheckedProjectBuildResult built = deal.semantic.CheckedProjectBuilder.build(
            invocation, MATRIX_MODULE, List.of(fact));
        check(built != null && !built.hasErrors() && built.input() != null,
            what + ": the checked project builds: "
                + (built == null ? "null" : built.diagnostics()));
        if (built == null || built.hasErrors() || built.input() == null) {
            return null;
        }
        RequirementManifestResult manifests = deal.semantic.LoweringSupport
            .computeManifests(invocation, built.input(), built.index());
        check(manifests != null && manifests.diagnostics().isEmpty(),
            what + ": the manifests compute");
        if (manifests == null || !manifests.diagnostics().isEmpty()) {
            return null;
        }
        return new CheckedSeed(input, built, manifests);
    }

    /**
     * The matrix case's in-memory production chain: {@link #checkedSeed}
     * plus the full-program lowering with the closed validators.
     */
    private static Seed lowerSeed(String source, String what) {
        CompilerInvocation invocation = CompilerProfileProvider.resolveCommonShadow(
            deal.semantic.ir.SemanticProfile.DEAL_V1_2_INT32,
            deal.semantic.ir.ReleaseState.V1_2_ACTIVE,
            deal.semantic.CapabilityRegistry.releaseRegistry());
        CheckedSeed seed = checkedSeed(source, what, invocation);
        if (seed == null) {
            return null;
        }
        String registryHash = deal.semantic.CapabilityRegistry.releaseRegistry()
            .capabilityRegistryHash();
        ProjectInterfaceIndex index = seed.built().index();
        SemanticLowerer.LoweringResult lowering = SemanticLowerer.lowerModuleFullProgram(
            seed.input(), deal.semantic.ir.SemanticProfile.DEAL_V1_2_INT32,
            seed.manifests().manifests().get(0).constructCoverage(),
            index.interfaceIndexDigest(), registryHash,
            deal.semantic.ir.SemanticIdAllocator.over(List.of(MATRIX_MODULE)));
        check(lowering != null && !lowering.hasErrors() && lowering.unit() != null,
            what + ": the full-program lowering passes the closed validators: "
                + (lowering == null ? "null" : lowering.diagnostics()));
        if (lowering == null || lowering.hasErrors() || lowering.unit() == null) {
            return null;
        }
        return new Seed(lowering.unit(), lowering.table());
    }

    private static void testForEachDeletedSlot() throws Exception {
        System.out.println("-- the FOR_EACH terminal element check over a deleted "
            + "array-element slot: the corrected tuple at the op's own origin on "
            + "all three consumers --");
        Seed seed = lowerSeed(FOREACH_DELETED_SLOT_SOURCE, "the FOR_EACH seed");
        if (seed == null) {
            return;
        }
        Path workspace = Files.createTempDirectory("projection-foreach");
        try {
            SemanticDifferentialHarness.Verdict verdict =
                SemanticDifferentialHarness.run(seed.unit(), seed.table(),
                    SemanticDifferentialHarness.Expectation.failure(
                        "the FOR_EACH deleted-element check", List.of(), "E8001",
                        "test.deal:3:3"), workspace);
            checkEq(3, verdict.runs().size(), "the FOR_EACH drive produced the three "
                + "consumers: " + verdict.failures());
            check(verdict.pass(), "the FOR_EACH three-consumer verdict passes: "
                + verdict.failures() + "\n" + verdict.report());
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                check(run.terminal()
                        instanceof SemanticRuntimeModel.Terminal.DealFailure,
                    run.consumer() + ": the FOR_EACH drive fails: " + run.terminal());
                if (!(run.terminal()
                        instanceof SemanticRuntimeModel.Terminal.DealFailure failure)) {
                    continue;
                }
                SemanticRuntimeModel.ErrorSnapshot error = failure.error();
                System.out.println("   " + run.consumer() + ": " + error.code()
                    + " | " + error.message() + " | " + error.origin() + " | "
                    + error.expected() + " | " + error.actual());
                checkEq("expected int", error.message(), run.consumer()
                    + ": the suffix-less kind text");
                checkEq("int", error.expected(), run.consumer()
                    + ": the expected token");
                checkEq("nil", error.actual(), run.consumer()
                    + ": the absent marker's nil token");
                checkEq("test.deal:3:3", error.origin(), run.consumer()
                    + ": the op's own for-of origin");
            }
        } finally {
            deleteRecursively(workspace);
        }
    }

    // =========================================================================
    // 4b. The dynamic-key computed table read (ISSUE-0717 review)
    // =========================================================================

    /**
     * The dynamic-key table read seed: a variable key, an effectful key
     * call, a missing key at a nullable position, and a present nullable
     * value's narrowed read.
     */
    private static final String DYNAMIC_TABLE_READ_SOURCE = """
        function keyOf(): string { return "x"; }
        function main(): null {
          let t: table = { x: 7 };
          let k: string = "x";
          let n: int = t[k];
          let n2: int = t[keyOf()];
          let u: table = {};
          u["y"] = 9;
          let ky: string = "y";
          let m: int = u[ky];
          let q: table = {};
          let miss: int | null = q[k];
          let v: table = { s: 1 };
          let nn: int | null = v["s"];
          let nz: int = 0;
          if (nn !== null) { nz = nn; }
          return null;
        }
        """;

    /**
     * The dynamic-key table read's contextual validation failure: a
     * present wrong-kind value at a nullable read position (E8001 at the
     * read's origin).
     */
    private static final String DYNAMIC_TABLE_READ_WRONG_KIND_SOURCE = """
        function main(): null {
          let t: table = { x: "str" };
          let k: string = "x";
          let n: int | null = t[k];
          return null;
        }
        """;

    /**
     * The dynamic-key table read's missing failure: an absent key at a
     * non-nullable read position (E8001 at the read's origin).
     */
    private static final String DYNAMIC_TABLE_READ_MISSING_SOURCE = """
        function main(): null {
          let t: table = {};
          let k: string = "x";
          let n: int = t[k];
          return null;
        }
        """;

    /**
     * The dynamic-key computed table read ({@code t[k]}): the receiver
     * completes before the key, the key's closed table slot is published
     * by {@code INDEX_NORMALIZE(TABLE_READ)}, and the {@code INDEX_READ}'s
     * {@code CONTEXTUAL_TABLE_READ} boundary child runs the literal
     * member read's contextual admission. The oracle, the LuaJIT artifact,
     * and the JVM artifact must agree event-for-event and on the pinned
     * terminal for the success and both failure shapes.
     */
    private static void testDynamicTableRead() throws Exception {
        System.out.println("-- the dynamic-key computed table read: the "
            + "three-consumer trace and terminal identity --");
        Seed success = lowerSeed(DYNAMIC_TABLE_READ_SOURCE,
            "the dynamic table read seed");
        if (success != null) {
            Path workspace = Files.createTempDirectory("projection-dyntable");
            try {
                SemanticDifferentialHarness.Verdict verdict =
                    SemanticDifferentialHarness.run(success.unit(), success.table(),
                        SemanticDifferentialHarness.Expectation.success(
                            "the dynamic table read", List.of(), "null"),
                        workspace);
                checkEq(3, verdict.runs().size(), "the dynamic table read drive "
                    + "produced the three consumers: " + verdict.failures());
                check(verdict.pass(), "the dynamic table read three-consumer "
                    + "verdict passes: " + verdict.failures() + "\n" + verdict.report());
            } finally {
                deleteRecursively(workspace);
            }
        }

        Seed wrongKind = lowerSeed(DYNAMIC_TABLE_READ_WRONG_KIND_SOURCE,
            "the dynamic table read wrong-kind seed");
        if (wrongKind != null) {
            Path workspace = Files.createTempDirectory("projection-dyntable-kind");
            try {
                SemanticDifferentialHarness.Verdict verdict =
                    SemanticDifferentialHarness.run(wrongKind.unit(), wrongKind.table(),
                        SemanticDifferentialHarness.Expectation.failure(
                            "the dynamic table read wrong-kind value", List.of(),
                            "E8001", "test.deal:4:23"),
                        workspace);
                checkEq(3, verdict.runs().size(), "the wrong-kind drive produced "
                    + "the three consumers: " + verdict.failures());
                check(verdict.pass(), "the wrong-kind three-consumer verdict "
                    + "passes (the contextual admission's pinned E8001): "
                    + verdict.failures() + "\n" + verdict.report());
            } finally {
                deleteRecursively(workspace);
            }
        }

        Seed missing = lowerSeed(DYNAMIC_TABLE_READ_MISSING_SOURCE,
            "the dynamic table read missing seed");
        if (missing != null) {
            Path workspace = Files.createTempDirectory("projection-dyntable-missing");
            try {
                SemanticDifferentialHarness.Verdict verdict =
                    SemanticDifferentialHarness.run(missing.unit(), missing.table(),
                        SemanticDifferentialHarness.Expectation.failure(
                            "the dynamic table read missing key", List.of(),
                            "E8001", "test.deal:4:16"),
                        workspace);
                checkEq(3, verdict.runs().size(), "the missing-key drive produced "
                    + "the three consumers: " + verdict.failures());
                check(verdict.pass(), "the missing-key three-consumer verdict "
                    + "passes (the read origin's pinned E8001): "
                    + verdict.failures() + "\n" + verdict.report());
            } finally {
                deleteRecursively(workspace);
            }
        }
    }

    // =========================================================================
    // 4c. The present-null and deleted-slot element cells of the dynamic
    //     array boundary (ISSUE-0753)
    // =========================================================================

    /**
     * The present-null seed: the array literal's {@code (null)[]} admission
     * and the dynamic array boundary's element check over the present null.
     */
    private static final String PRESENT_NULL_ELEMENT_SOURCE = """
        function main(): null {
          let inner: (null)[] = [null];
          let holder: table = { xs: inner };
          let ys: int[] = holder.xs;
          return null;
        }
        """;

    /** The deleted-slot seed: the same boundary cell over an absent slot. */
    private static final String DELETED_SLOT_ELEMENT_SOURCE = """
        function main(): null {
          let inner: int[] = [1];
          delete inner[0];
          let holder: table = { xs: inner };
          let ys: int[] = holder.xs;
          return null;
        }
        """;

    /** The array(null) admission seed: the present null passes the cell. */
    private static final String NULL_ARRAY_ELEMENT_SOURCE = """
        function main(): null {
          let inner: (null)[] = [null];
          let holder: table = { xs: inner };
          let ys: (null)[] = holder.xs;
          return null;
        }
        """;

    /**
     * The present-null and deleted-slot element cells of the dynamic array
     * boundary (ISSUE-0753): the explicit language null projects the
     * typed-boundary {@code null} token on all three consumers while only
     * the absent/deleted slot projects {@code nil}, and an
     * {@code array(null)} descriptor admits the present null. Every consumer
     * renders the complete tuple at the declared binding's own annotation
     * origin — the JVM array check never converts the present null to the
     * absent marker.
     */
    private static void testArrayNullAndDeletedSlotCells() throws Exception {
        System.out.println("-- the dynamic array boundary's present-null vs "
            + "deleted-slot element cells on the three consumers --");
        Path workspace = Files.createTempDirectory("projection-array-null");
        try {
            driveArrayElementFailure("the present-null element",
                PRESENT_NULL_ELEMENT_SOURCE, workspace.resolve("present-null"),
                "test.deal:4:11", "int", "null");
            driveArrayElementFailure("the deleted-slot element",
                DELETED_SLOT_ELEMENT_SOURCE, workspace.resolve("deleted-slot"),
                "test.deal:5:11", "int", "nil");
            driveArrayAdmission("the array(null) admission",
                NULL_ARRAY_ELEMENT_SOURCE, workspace.resolve("null-array"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    /**
     * One element-cell seed through the three-consumer matrix: the failing
     * consumers render the identical complete tuple — the E8003 element
     * arm's message, the expected/actual tokens, and the boundary's own
     * origin.
     */
    private static void driveArrayElementFailure(String what, String source,
            Path workspace, String originText, String expected, String actual)
            throws Exception {
        Seed seed = lowerSeed(source, what);
        if (seed == null) {
            return;
        }
        SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
            seed.unit(), seed.table(), SemanticDifferentialHarness.Expectation.failure(
                what, List.of(), "E8003", originText), workspace);
        checkEq(3, verdict.runs().size(), what + ": the drive produced the three "
            + "consumers: " + verdict.failures());
        check(verdict.pass(), what + ": the three-consumer verdict passes: "
            + verdict.failures() + "\n" + verdict.report());
        for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
            check(run.terminal()
                    instanceof SemanticRuntimeModel.Terminal.DealFailure,
                run.consumer() + " (" + what + "): the cell fails: "
                    + run.terminal());
            if (!(run.terminal()
                    instanceof SemanticRuntimeModel.Terminal.DealFailure failure)) {
                continue;
            }
            SemanticRuntimeModel.ErrorSnapshot error = failure.error();
            System.out.println("   " + run.consumer() + ": " + error.code() + " | "
                + error.message() + " | " + error.origin() + " | " + error.expected()
                + " | " + error.actual());
            checkEq("E8003", error.code(), run.consumer()
                + ": the element cell's code");
            checkEq("array element 1 type mismatch", error.message(), run.consumer()
                + ": the array element arm's own message");
            checkEq(originText, error.origin(), run.consumer() + ": the declared "
                + "binding annotation origin (unchanged by the projection)");
            checkEq(expected, error.expected(), run.consumer()
                + ": the expected token");
            checkEq(actual, error.actual(), run.consumer() + ": the actual token");
        }
    }

    /**
     * The {@code array(null)} admission control through the same matrix:
     * every consumer admits the present null, so the seed runs to success.
     */
    private static void driveArrayAdmission(String what, String source, Path workspace)
            throws Exception {
        Seed seed = lowerSeed(source, what);
        if (seed == null) {
            return;
        }
        SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
            seed.unit(), seed.table(), SemanticDifferentialHarness.Expectation.success(
                what, List.of(), "null"), workspace);
        checkEq(3, verdict.runs().size(), what + ": the drive produced the three "
            + "consumers: " + verdict.failures());
        check(verdict.pass(), what + ": the three-consumer verdict passes: "
            + verdict.failures() + "\n" + verdict.report());
        for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
            check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                run.consumer() + " (" + what + "): the present null is admitted: "
                    + run.terminal());
        }
    }

    // =========================================================================
    // 4d. The array-literal element cells (ISSUE-0806)
    // =========================================================================

    /**
     * The composite element seed: the literal child's own recorded
     * {@code ARRAY_ELEMENT_DESCRIPTOR} policy decides the deferred
     * contextual array read, so the element cell — never the generic
     * descriptor cell — renders the failure at {@code t.x}.
     */
    private static final String ARRAY_LITERAL_COMPOSITE_ELEMENT_SOURCE = """
        function main(): null {
          let t: table = { x: "bad" };
          let ys: int[][] = [t.x];
          return null;
        }
        """;

    /**
     * The nested literal seed: the failing cell belongs to the inner
     * literal, so the element failure is nested inside an outer literal's
     * own element position.
     */
    private static final String ARRAY_LITERAL_NESTED_ELEMENT_SOURCE = """
        function main(): null {
          let t: table = { x: "bad" };
          let ys: int[][][] = [[t.x]];
          return null;
        }
        """;

    /**
     * The second-position seed: the failing element is the literal's
     * second child, so the arm renders its one-based literal index 2.
     */
    private static final String ARRAY_LITERAL_SECOND_ELEMENT_SOURCE = """
        function main(): null {
          let t: table = { x: "bad" };
          let ys: int[][] = [[1, 2], t.x];
          return null;
        }
        """;

    /**
     * The table-carrier seed: the deferred read's value is a DEAL table,
     * so the element arm's actual field is the value's own table kind.
     */
    private static final String ARRAY_LITERAL_TABLE_ELEMENT_SOURCE = """
        function main(): null {
          let t: table = { x: { y: 1 } };
          let ys: int[][] = [t.x];
          return null;
        }
        """;

    /**
     * The nested-element-failure seed: the failing cell's own value is an
     * array carrier whose element walk fails at its deleted slot, so the
     * literal cell's tuple is the outer element arm over the array token
     * (the one-based literal index, the element descriptor's expected
     * text, and the value's own reference kind).
     */
    private static final String ARRAY_LITERAL_NESTED_FAILURE_SOURCE = """
        function main(): null {
          let inner: int[] = [1, 2];
          let t: table = { xs: inner };
          delete inner[0];
          let ys: int[][] = [t.xs];
          return null;
        }
        """;

    /** The admitted composite element control: the deferred read passes the cell. */
    private static final String ARRAY_LITERAL_ADMITTED_ELEMENT_SOURCE = """
        function main(): null {
          let t: table = { x: [1, 2] };
          let ys: int[][] = [t.x];
          return null;
        }
        """;

    /**
     * The admitted absent element control: the deferred read of an absent
     * key reaches the nullable element cell, whose named missing→null
     * mapping admits the element on every consumer.
     */
    private static final String ARRAY_LITERAL_MISSING_ELEMENT_SOURCE = """
        function main(): null {
          let t: table = { y: 1 };
          let ys: (int | null)[] = [t.x];
          return null;
        }
        """;

    /**
     * The array-literal element cells (ISSUE-0806): the recorded
     * {@code ARRAY_ELEMENT_DESCRIPTOR} policy of every
     * {@code ARRAY_LITERAL_ELEMENT} child renders the canonical element
     * arm — its one-based literal index, the element descriptor's
     * canonical text as expected, the failing value's own reference-kind
     * token as actual, and the boundary child's own lowerer-owned origin —
     * identically on the oracle, the LuaJIT artifact, and the JVM
     * artifact. The composite element, the nested literal's element, the
     * second literal position, a DEAL table carrier, and a nested element
     * walk failure inside the element value are each driven through the
     * three consumers, and the deferred admitted element and the absent
     * nullable element stay successes.
     */
    private static void testArrayLiteralElementCells() throws Exception {
        System.out.println("-- the array-literal element cells: the recorded "
            + "element policy, index, projections, and origin on the three "
            + "consumers --");
        Path workspace = Files.createTempDirectory("projection-array-literal");
        try {
            driveArrayLiteralElementFailure("the composite element",
                ARRAY_LITERAL_COMPOSITE_ELEMENT_SOURCE,
                workspace.resolve("composite"), "test.deal:3:22", 1, "[int]",
                "string");
            driveArrayLiteralElementFailure("the nested literal element",
                ARRAY_LITERAL_NESTED_ELEMENT_SOURCE,
                workspace.resolve("nested-literal"), "test.deal:3:25", 1, "[int]",
                "string");
            driveArrayLiteralElementFailure("the second literal element",
                ARRAY_LITERAL_SECOND_ELEMENT_SOURCE,
                workspace.resolve("second-element"), "test.deal:3:30", 2, "[int]",
                "string");
            driveArrayLiteralElementFailure("the table carrier element",
                ARRAY_LITERAL_TABLE_ELEMENT_SOURCE,
                workspace.resolve("table-carrier"), "test.deal:3:22", 1, "[int]",
                "table");
            driveArrayLiteralElementFailure("the nested element walk failure",
                ARRAY_LITERAL_NESTED_FAILURE_SOURCE,
                workspace.resolve("nested-failure"), "test.deal:5:22", 1, "[int]",
                "array");
            driveArrayLiteralElementAdmission("the admitted composite element",
                ARRAY_LITERAL_ADMITTED_ELEMENT_SOURCE,
                workspace.resolve("admitted"));
            driveArrayLiteralElementAdmission("the admitted absent nullable "
                + "element", ARRAY_LITERAL_MISSING_ELEMENT_SOURCE,
                workspace.resolve("admitted-missing"));
            driveArrayLiteralElementProduction("the production element terminal",
                ARRAY_LITERAL_COMPOSITE_ELEMENT_SOURCE,
                workspace.resolve("production"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    /**
     * One array-literal element-cell seed through the three-consumer
     * matrix: every consumer renders the identical complete tuple — the
     * E8003 element arm's message with the literal's own one-based index,
     * the element descriptor's canonical expected text, the value's own
     * reference-kind actual token, and the boundary child's own origin.
     */
    private static void driveArrayLiteralElementFailure(String what, String source,
            Path workspace, String originText, int oneBasedIndex, String expected,
            String actual) throws Exception {
        Seed seed = lowerSeed(source, what);
        if (seed == null) {
            return;
        }
        SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
            seed.unit(), seed.table(), SemanticDifferentialHarness.Expectation.failure(
                what, List.of(), "E8003", originText), workspace);
        checkEq(3, verdict.runs().size(), what + ": the drive produced the three "
            + "consumers: " + verdict.failures());
        check(verdict.pass(), what + ": the three-consumer verdict passes: "
            + verdict.failures() + "\n" + verdict.report());
        for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
            check(run.terminal()
                    instanceof SemanticRuntimeModel.Terminal.DealFailure,
                run.consumer() + " (" + what + "): the cell fails: "
                    + run.terminal());
            if (!(run.terminal()
                    instanceof SemanticRuntimeModel.Terminal.DealFailure failure)) {
                continue;
            }
            SemanticRuntimeModel.ErrorSnapshot error = failure.error();
            System.out.println("   " + run.consumer() + ": " + error.code() + " | "
                + error.message() + " | " + error.origin() + " | " + error.expected()
                + " | " + error.actual());
            checkEq("E8003", error.code(), run.consumer()
                + ": the recorded element policy's code");
            checkEq("array element " + oneBasedIndex + " type mismatch",
                error.message(), run.consumer()
                    + ": the element arm's own message with the literal index");
            checkEq(originText, error.origin(), run.consumer()
                + ": the boundary child's own lowerer-owned origin");
            checkEq(expected, error.expected(), run.consumer()
                + ": the element descriptor's canonical expected text");
            checkEq(actual, error.actual(), run.consumer()
                + ": the value's own reference-kind actual token");
        }
    }

    /**
     * The admitted deferred element through the same matrix: every consumer
     * passes the element cell, so the seed runs to success.
     */
    private static void driveArrayLiteralElementAdmission(String what, String source,
            Path workspace) throws Exception {
        Seed seed = lowerSeed(source, what);
        if (seed == null) {
            return;
        }
        SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
            seed.unit(), seed.table(), SemanticDifferentialHarness.Expectation.success(
                what, List.of(), "null"), workspace);
        checkEq(3, verdict.runs().size(), what + ": the drive produced the three "
            + "consumers: " + verdict.failures());
        check(verdict.pass(), what + ": the three-consumer verdict passes: "
            + verdict.failures() + "\n" + verdict.report());
        for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
            check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                run.consumer() + " (" + what + "): the admitted element passes "
                    + "the cell: " + run.terminal());
        }
    }

    /**
     * The production artifacts of one array-literal element-cell seed: the
     * real production chunk under luajit and the real production JVM class
     * under {@code javac --release 25 -proc:none} plus {@code java} both
     * terminate with the recorded element policy's E8003 code line and
     * exit 1 — the element arm is the production path's projection, not a
     * trace-only surface.
     */
    private static void driveArrayLiteralElementProduction(String what, String source,
            Path workspace) throws Exception {
        Seed seed = lowerSeed(source, what);
        if (seed == null) {
            return;
        }
        Files.createDirectories(workspace);
        String chunk = LuaSemanticEmitter.emitProductionModule(seed.unit(),
            seed.table(), true);
        Files.writeString(workspace.resolve("main.lua"), chunk,
            StandardCharsets.UTF_8);
        BoundedProcess.BoundedResult luaRun = BoundedProcess.runCaptured(
            List.of("luajit", "main.lua"), workspace, Map.of());
        checkEq(1, luaRun.exitCode(), what + " (luajit production): the element "
            + "failure exits 1: " + luaRun.stdout() + luaRun.stderr());
        checkEq("DEAL_ERROR_CODE: E8003\n", luaRun.stdout(), what
            + " (luajit production): the terminal publishes the recorded "
            + "element policy's code line");
        deal.codegen.jvm.JvmSemanticEmitter.EmissionResult emission =
            deal.codegen.jvm.JvmSemanticEmitter.emitProductionModule(seed.unit(),
                seed.table(), true, "ProjectionArrayLiteralDrive");
        Path sourcePath = workspace.resolve(emission.className() + ".java");
        Files.writeString(sourcePath, emission.source(), StandardCharsets.UTF_8);
        Path classes = workspace.resolve("jvm-classes");
        Files.createDirectories(classes);
        String classpath = absoluteClasspath();
        JvmCompilation.Result compile = JvmCompilation.compile(List.of("javac",
            "--release", "25", "-proc:none", "-cp", classpath, "-d",
            classes.toString(), sourcePath.toAbsolutePath().toString()), workspace);
        checkEq(0, compile.exitCode(), what + " (jvm production): the emitted "
            + "class compiles: " + compile.stdout() + compile.stderr());
        BoundedProcess.BoundedResult jvmRun = BoundedProcess.runCaptured(
            List.of("java", "-cp", classes + File.pathSeparator + classpath,
                emission.className()), workspace, Map.of());
        checkEq(1, jvmRun.exitCode(), what + " (jvm production): the element "
            + "failure exits 1: " + jvmRun.stdout() + jvmRun.stderr());
        checkEq("DEAL_ERROR_CODE: E8003\n", jvmRun.stdout(), what
            + " (jvm production): the terminal publishes the recorded element "
            + "policy's code line");
    }

    // =========================================================================
    // 4e. The emitted Lua prelude's table-carrier classification (ISSUE-0806)
    // =========================================================================

    /** The classification probe's trivial production seed. */
    private static final String TABLE_CARRIER_CLASSIFICATION_SOURCE = """
        function main(): null {
          return null;
        }
        """;

    /**
     * The emitted Lua prelude's table-carrier classification: a mark-less
     * Lua table at an array-named cell projects the actual table token,
     * never the declared/expected array kind, exactly like the oracle's
     * value view and the JVM runtime's actual-kind projection, while a
     * DEAL table and the real (and decoded) array carriers keep their own
     * kinds. The probe inserts its drive before the real production
     * chunk's retained-caller surface tail and loads the chunk under real
     * luajit, so it calls the artifact's own {@code __typedBoundaryKind}.
     */
    private static void testLuaTableCarrierClassification() throws Exception {
        System.out.println("-- the emitted Lua prelude's table-carrier "
            + "classification: the production artifact's own kind projection "
            + "under real luajit --");
        Seed seed = lowerSeed(TABLE_CARRIER_CLASSIFICATION_SOURCE,
            "the table-carrier classification seed");
        if (seed == null) {
            return;
        }
        Path workspace = Files.createTempDirectory("projection-table-carrier");
        try {
            String chunk = LuaSemanticEmitter.emitProductionModule(seed.unit(),
                seed.table(), true);
            String tail = "return __exportSurfaces[\"main\"]";
            check(chunk.endsWith(tail + "\n"), "the production chunk carries "
                + "the retained-caller surface tail: " + tailText(chunk));
            String probe = "io.write(\"KIND|\""
                + " .. __typedBoundaryKind(\"array\", {}) .. \"|\""
                + " .. __typedBoundaryKind(\"array\", { x = 1 }) .. \"|\""
                + " .. __typedBoundaryKind(\"array\", { __t = true }) .. \"|\""
                + " .. __typedBoundaryKind(\"array\", { __a = true, __n = 0 })"
                + " .. \"|\""
                + " .. __typedBoundaryKind(\"array\", { __a = true, __n = 0,"
                + " __da = true }) .. \"\\n\")\n";
            Path artifact = workspace.resolve("kind-probe.lua");
            Files.writeString(artifact, chunk.substring(0,
                chunk.length() - (tail + "\n").length()) + probe + tail + "\n",
                StandardCharsets.UTF_8);
            BoundedProcess.BoundedResult captured = BoundedProcess.runCaptured(
                List.of("luajit", "kind-probe.lua"), workspace,
                Map.of("DEAL_DEFER_MAIN", "1"));
            String output = captured.stdout() + captured.stderr();
            check(!captured.stdoutTruncated() && !captured.stderrTruncated(),
                "the classification probe output is not truncated: " + output);
            checkEq(0, captured.exitCode(), "the classification probe runs under "
                + "real luajit: " + output);
            checkEq("KIND|table|table|table|array|array\n", captured.stdout(),
                "a mark-less table projects the actual table token at an "
                    + "array-named cell (never the declared array kind), the "
                    + "DEAL table its own table kind, and the real and decoded "
                    + "array carriers their array kind");
        } finally {
            deleteRecursively(workspace);
        }
    }

    /** The trailing text of one emitted chunk (the tail assertion's detail). */
    private static String tailText(String chunk) {
        return "\"" + chunk.substring(Math.max(0, chunk.length() - 60))
            .replace("\n", "\\n") + "\"";
    }

    // =========================================================================
    // 4f. The module namespace surface at the direct and nested array
    //     boundaries (ISSUE-0807)
    // =========================================================================

    /**
     * The namespace library module of the two-module seed: one exported
     * function, so the module publishes the identity-keyed namespace
     * surface (a plain table without a carrier mark) its import alias cell
     * holds.
     */
    private static final String NAMESPACE_LIB_SOURCE = """
        export function ping(): int {
          return 1;
        }
        """;

    /**
     * The direct array boundary: the import alias cell holds the
     * {@code lib} module's namespace surface, the table field stores it,
     * and the declared {@code int[]} binding reads it through the deferred
     * contextual-read seam.
     */
    private static final String NAMESPACE_DIRECT_SOURCE = """
        import * as lib from "./lib"

        export function main(): null {
          let holder: table = { ns: lib };
          let xs: int[] = holder.ns;
          return null;
        }
        """;

    /**
     * The nested array boundary: the same namespace surface at a declared
     * {@code int[][]} binding (the nested descriptor's outer array cell).
     */
    private static final String NAMESPACE_NESTED_SOURCE = """
        import * as lib from "./lib"

        export function main(): null {
          let holder: table = { ns: lib };
          let xs: int[][] = holder.ns;
          return null;
        }
        """;

    /**
     * The stored form: the namespace surface is written into an existing
     * table field after the table's creation (not the literal entry), then
     * read at the declared array boundary.
     */
    private static final String NAMESPACE_WRITTEN_SOURCE = """
        import * as lib from "./lib"

        export function main(): null {
          let holder: table = { ns: [1] };
          holder.ns = lib;
          let xs: int[] = holder.ns;
          return null;
        }
        """;

    /**
     * The value-derived control: a real array carrier read at the
     * {@code table} descriptor keeps its own {@code array} token, so the
     * unmarked-table classification is a value rule, never a blanket
     * spelling of every table-carried value.
     */
    private static final String NAMESPACE_ARRAY_CONTROL_SOURCE = """
        import * as lib from "./lib"

        export function main(): null {
          let payload: int[] = [1];
          let holder: table = { ns: payload };
          let t: table = holder.ns;
          return null;
        }
        """;

    /**
     * The module namespace surface at the direct and nested array boundaries
     * (ISSUE-0807): the import alias cell holds the module's namespace
     * surface — a plain table without a carrier mark — and a declared array
     * binding over that surface rejects it with the value-derived
     * {@code table} actual token on the semantic oracle, the shared LuaJIT
     * artifact, and the shared JVM artifact alike. The declared static kind
     * never replaces the value's own classification: the expected
     * {@code array} kind is not substituted for the unmarked table carrier,
     * so the three consumers render the identical complete tuple. The
     * array-carrier control pins the other half of the same rule. Each seed
     * also drives the release-owned production endpoints — the published
     * LuaJIT artifact and the staged JVM artifact under real
     * {@code luajit} and {@code javac --release 25 -proc:none} plus
     * {@code java} — and every captured field is asserted against the
     * oracle's own tuple.
     */
    private static void testNamespaceSurfaceArrayBoundaries() throws Exception {
        System.out.println("-- the module namespace surface at the direct and "
            + "nested array boundaries: the unmarked table carrier projects "
            + "`table` on all three consumers --");
        driveNamespaceFailure("the namespace at the direct array boundary",
            NAMESPACE_DIRECT_SOURCE, 5, 11, "expected array", "array", "table");
        driveNamespaceFailure("the namespace at the nested array boundary",
            NAMESPACE_NESTED_SOURCE, 5, 11, "expected array", "array", "table");
        driveNamespaceFailure("the namespace written into a table field",
            NAMESPACE_WRITTEN_SOURCE, 6, 11, "expected array", "array", "table");
        driveNamespaceFailure("the array-carrier table control",
            NAMESPACE_ARRAY_CONTROL_SOURCE, 6, 18, "expected table", "table",
            "array");
    }

    /**
     * One namespace seed through the three-consumer project matrix: the
     * whole two-module project compiles through the release-owned
     * invocation, lowers through the one project lowering, and each
     * consumer renders the identical complete tuple at the boundary's own
     * origin. The seed's provenance is asserted first: the app unit imports
     * the lib module through its alias cell and the lib unit publishes its
     * export, so the stored value is the module's namespace surface, never
     * a synthesized stand-in.
     */
    private static void driveNamespaceFailure(String what, String appSource,
            int line, int column, String message, String expected, String actual)
            throws Exception {
        Path root = Files.createTempDirectory("projection-namespace");
        Path workspace = Files.createTempDirectory("projection-namespace-run");
        try {
            Compiled seed = namespaceSeed(what, root, appSource);
            if (seed == null) {
                return;
            }
            assertNamespaceSurfaceProvenance(what, seed);
            SemanticDifferentialHarness.Verdict verdict =
                SemanticDifferentialHarness.runProject(seed.project(),
                    seed.tables(), seed.registries(),
                    SemanticDifferentialHarness.Expectation.failure(what, List.of(),
                        "E8001", null),
                    workspace);
            checkEq(3, verdict.runs().size(), what + ": the drive produced the three "
                + "consumers: " + verdict.failures());
            check(verdict.pass(), what + ": the three-consumer verdict passes: "
                + verdict.failures() + "\n" + verdict.report());
            Tuple reference = null;
            Tuple oracleTuple = null;
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                check(run.terminal()
                        instanceof SemanticRuntimeModel.Terminal.DealFailure,
                    run.consumer() + " (" + what + "): the boundary rejects the "
                        + "value: " + run.terminal());
                if (!(run.terminal()
                        instanceof SemanticRuntimeModel.Terminal.DealFailure failure)) {
                    continue;
                }
                SemanticRuntimeModel.ErrorSnapshot error = failure.error();
                checkEq("E8001", error.code(), run.consumer() + " (" + what
                    + "): the typed-boundary kind arm's code");
                checkEq(message, error.message(), run.consumer() + " (" + what
                    + "): the typed-boundary kind arm's message");
                checkEq(expected, error.expected(), run.consumer() + " (" + what
                    + "): the expected token");
                checkEq(actual, error.actual(), run.consumer() + " (" + what
                    + "): the value-derived actual token (never the declared "
                    + "array kind substituted for the unmarked table carrier)");
                check(error.origin() != null && error.origin().endsWith(
                        "src/app.deal:" + line + ":" + column),
                    run.consumer() + " (" + what + "): the boundary's own origin "
                        + "src/app.deal:" + line + ":" + column + ": "
                        + error.origin());
                Tuple tuple = new Tuple(error.code(), error.message(), "app.deal",
                    line, column, error.expected(), error.actual());
                if ("semantic-oracle".equals(run.consumer())) {
                    oracleTuple = tuple;
                }
                if (reference == null) {
                    reference = tuple;
                } else {
                    checkEq(null, firstDifferingField(reference, tuple),
                        "the " + run.consumer() + " (" + what + ") tuple is "
                            + "field-identical to the first consumer's");
                }
            }
            // The registered production-endpoint drive (ISSUE-0807 review):
            // the trace matrix above selects the shared trace sessions; the
            // compiled production LuaJIT artifact and the release-owned JVM
            // production artifact are executed themselves and must capture
            // the oracle's own tuple.
            assertNamespaceProductionArtifacts(what, seed, oracleTuple);
        } finally {
            deleteRecursively(workspace);
            deleteRecursively(root);
        }
    }

    /**
     * The registered production-endpoint drive of one namespace seed
     * (ISSUE-0807): the compiled production LuaJIT artifact published by
     * the release-owned invocation under {@code out/} and the release-owned
     * JVM production artifact staged through {@link ProductionProjectEmission}
     * are executed under real {@code luajit} and {@code javac --release 25
     * -proc:none} plus {@code java}. Each artifact captures the boundary's
     * complete tuple — {@code code}, {@code message}, the boundary's own
     * origin, {@code expected}, and the value-derived {@code actual} — and
     * every field is asserted against the oracle's. The trace matrix above
     * drives the shared trace sessions, so a regression confined to
     * production emission, publication, or failure transport can pass it;
     * these endpoints execute the production artifacts themselves, never
     * inferred from compilation success.
     */
    private static void assertNamespaceProductionArtifacts(String what,
            Compiled seed, Tuple oracle) throws Exception {
        if (oracle == null) {
            fail(what + ": the trace matrix captured no semantic-oracle tuple "
                + "to compare the production artifacts against");
            return;
        }
        String artifact = artifactPath(seed);
        check(Files.isRegularFile(Path.of(artifact)), what + ": the release-owned "
            + "invocation published the production LuaJIT artifact: " + artifact);
        assertNamespaceProductionTuple(what, "the production LuaJIT artifact",
            oracle, luaArtifact(seed));
        assertNamespaceProductionTuple(what, "the production JVM artifact",
            oracle, jvmArtifact(seed));
    }

    /**
     * One production artifact's captured tuple against the oracle's: every
     * compared field is asserted by name, so a production-only divergence
     * — for example the declared {@code array} kind substituted for the
     * unmarked table carrier's own {@code table} token — fails on the field
     * it moved.
     */
    private static void assertNamespaceProductionTuple(String what, String consumer,
            Tuple reference, Tuple captured) {
        if (captured == null) {
            fail(what + " (" + consumer + "): the drive captured no failure "
                + "tuple");
            return;
        }
        checkEq(reference.code(), captured.code(), what + " (" + consumer
            + "): the captured code");
        checkEq(reference.message(), captured.message(), what + " (" + consumer
            + "): the captured message");
        checkEq(reference.span(), captured.span(), what + " (" + consumer
            + "): the captured origin (the boundary's own span)");
        checkEq(reference.expected(), captured.expected(), what + " (" + consumer
            + "): the captured expected token");
        checkEq(reference.actual(), captured.actual(), what + " (" + consumer
            + "): the captured actual token (the value-derived classification, "
            + "never the declared array kind substituted for the unmarked "
            + "table carrier)");
        checkEq(null, firstDifferingField(reference, captured), what + " ("
            + consumer + "): the captured tuple is field-identical to the "
            + "oracle's");
    }

    /**
     * The two-module namespace project through the release-owned production
     * invocation: {@code src/lib.deal} publishes its namespace surface and
     * {@code src/app.deal} stores the import alias cell's value in a table
     * field and reads it at a declared array boundary. The returned
     * production context carries the orchestrator's own published LuaJIT
     * artifact under {@code out/} and the checked inputs the release-owned
     * JVM production artifact is staged from. Its {@code Case} is the
     * drive's own descriptive metadata: the seed is not a corpus fixture,
     * so only the capture messages read it.
     */
    private static Compiled namespaceSeed(String what, Path root,
            String appSource) throws Exception {
        Path moduleRoot = root.resolve("src");
        Files.createDirectories(moduleRoot);
        Files.writeString(moduleRoot.resolve("lib.deal"), NAMESPACE_LIB_SOURCE,
            StandardCharsets.UTF_8);
        Files.writeString(moduleRoot.resolve("app.deal"), appSource,
            StandardCharsets.UTF_8);
        Path entry = moduleRoot.resolve("app.deal").toAbsolutePath();
        CompilerInvocation invocation = productionInvocation();
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(entry,
            root.resolve("out"), false, false, false, false, Backend.LUAJIT, Map.of(),
            List.of(moduleRoot.toAbsolutePath()), null, null, invocation);
        boolean compiled = orchestrator.compile();
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        check(compiled && built != null && built.input() != null
                && built.index() != null && !built.hasErrors() && manifests != null
                && manifests.manifests() != null
                && orchestrator.hostDeclarationSurface() != null,
            what + ": the two-module namespace project compiles through the "
                + "release-owned production invocation: "
                + (built == null ? "no checked project" : built.diagnostics())
                + " / " + orchestrator.diagnostics());
        if (!compiled || built == null || built.input() == null || built.index() == null
                || built.hasErrors() || manifests == null
                || manifests.manifests() == null
                || orchestrator.hostDeclarationSurface() == null) {
            return null;
        }
        SemanticLowerer.ProjectLoweringResult lowering = SemanticLowerer.lowerProject(
            invocation, built.input(), built.index(), manifests.manifests(),
            orchestrator.hostDeclarationSurface(), Map.of(), Map.of(),
            BuiltinErrorDeclaration.synthesized(
                built.input().modules().stream()
                    .filter(module -> module.moduleId()
                        .equals(built.input().entryModule()))
                    .findFirst().orElseThrow().ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW),
            Set.of());
        check(lowering != null && !lowering.hasErrors() && lowering.project() != null,
            what + ": the two-module namespace project lowers through the one "
                + "project lowering with zero diagnostics: "
                + (lowering == null ? "null" : String.valueOf(lowering.diagnostics())));
        if (lowering == null || lowering.hasErrors() || lowering.project() == null) {
            return null;
        }
        return new Compiled(root, moduleRoot, new Case(what, "main", Entry.MAIN,
            List.of(), null, null), invocation, built.input(), built.index(),
            manifests.manifests(), orchestrator.hostDeclarationSurface(),
            Map.of(), Map.of(), Map.of("app.deal", 0, "lib.deal", 0),
            lowering.project(), lowering.tables(), lowering.registries(), Set.of(),
            Map.of("app", "app.deal", "lib", "lib.deal"), Map.of(),
            new JvmPreparation());
    }

    /**
     * The namespace-surface provenance of one seed: the app unit carries
     * exactly one {@code MODULE_IMPORT} of the lib module with its alias
     * cell (the completion write the emitted artifact performs — the cell
     * holds the module-identity-keyed surface), and the lib unit publishes
     * its export onto that surface. The value the app stores in the table
     * field is therefore the module's namespace surface, never a
     * synthesized stand-in.
     */
    private static void assertNamespaceSurfaceProvenance(String what,
            Compiled seed) {
        ExecutableLoweredProject project = seed.project();
        ModuleId lib = new ModuleId("lib");
        check(project.modules().containsKey(lib), what + ": the project carries "
            + "the lib module");
        if (!project.modules().containsKey(lib)) {
            return;
        }
        List<SemanticOp> imports = new ArrayList<>();
        for (SemanticOp op : project.modules().get(project.entryModule()).ops()) {
            if (op.kind() == SemanticOpKind.MODULE_IMPORT
                    && ((KindPayload.ModuleImportPayload) op.payload())
                        .resolvedModule().equals(lib)) {
                imports.add(op);
            }
        }
        checkEq(1, imports.size(), what + ": the app unit carries exactly one "
            + "MODULE_IMPORT of the lib module (the alias cell holding the "
            + "namespace surface)");
        if (imports.size() == 1) {
            check(!((KindPayload.ModuleImportPayload) imports.get(0).payload())
                    .aliasCells().isEmpty(),
                what + ": the lib import records its alias cell");
        }
        boolean published = false;
        for (SemanticOp op : project.modules().get(lib).ops()) {
            if (op.kind() == SemanticOpKind.EXPORT_PUBLISH
                    && "ping".equals(((KindPayload.ExportPublishPayload) op.payload())
                        .name())) {
                published = true;
            }
        }
        check(published, what + ": the lib module publishes its ping export onto "
            + "the namespace surface the alias cell holds");
    }

    // =========================================================================
    // 5. The row/arm completeness negatives and the single-source control
    // =========================================================================

    private static void testCompletenessAndSingleSourceNegatives() {
        System.out.println("-- the completeness invariants and the single-source "
            + "control fail by name --");
        Map<FailurePolicyId, FailurePolicyRow> rows = FailureContractRegistry.rows();
        List<FailureArm> arms = new ArrayList<>(FailureContractRegistry.arms().values());
        FailureContractRegistry.checkArmConsistency(rows, arms);
        passed++;
        FailureArm kind = FailureContractRegistry.arm(FailureArmId.TYPED_BOUNDARY_KIND);
        List<FailureArm> unbound = new ArrayList<>();
        for (FailureArm arm : arms) {
            if (arm.id() != FailureArmId.TYPED_BOUNDARY_KIND) {
                unbound.add(arm);
            }
        }
        expectDefect(() -> FailureContractRegistry.checkArmConsistency(rows, unbound),
            "an unbound retained template");
        List<FailureArm> foreign = new ArrayList<>(arms);
        foreign.set(foreign.indexOf(kind), new FailureArm(kind.id(), kind.policy(),
            kind.templateIndex(), "expected {kind}, got {actual}", kind.parameters(),
            kind.parameterSources(), kind.expectedSource(), null,
            kind.actualProjection(), kind.origin(), kind.scope(), kind.code()));
        expectDefect(() -> FailureContractRegistry.checkArmConsistency(rows, foreign),
            "a foreign template");
        List<FailureArm> duplicate = new ArrayList<>(arms);
        duplicate.add(kind);
        expectDefect(() -> FailureContractRegistry.checkArmConsistency(rows, duplicate),
            "a duplicate binding");
        List<FailureArm> missing = new ArrayList<>(arms);
        missing.remove(FailureContractRegistry.arm(FailureArmId.CLASS_EXTRA_FIELD));
        expectDefect(() -> FailureContractRegistry.checkArmConsistency(rows, missing),
            "a missing arm for a retained template");

        // The negative single-source control: a deliberately composing
        // consumer is caught by field name.
        Tuple reference = tuple("E8001", "expected int",
            "type-mismatch-e8001.deal:6:21", "int", "string");
        checkEq(null, firstDifferingField(reference, reference),
            "the identical tuple compares equal (the control is not vacuous)");
        checkEq("message", firstDifferingField(reference, tuple("E8001",
            "expected int, got string", reference.span(), "int", "string")),
            "the superseded suffixed spelling is caught by field name message");
        checkEq("message", firstDifferingField(reference, tuple("E8001",
            "expected int, got missing", reference.span(), "int", "missing")),
            "the superseded FOR_EACH absent-element text is caught by field name "
                + "message");
        checkEq("message", firstDifferingField(reference, tuple("E8001",
            "parameter 1 type mismatch: expected bytes, got number", reference.span(),
            "bytes", "number")),
            "the superseded host composite is caught by field name message");
        checkEq("span", firstDifferingField(reference, tuple("E8001",
            "expected int", "type-mismatch-e8001.deal:9:10", "int", "string")),
            "a call-site span for a declaration-owned arm is caught by field name "
                + "span");
    }

    // =========================================================================
    // 6. The unchanged surfaces
    // =========================================================================

    private static void testComparisonContract() throws Exception {
        System.out.println("-- the pinned and absent fields through the comparison contract --");
        // The comparison contract: mandatory code/message; the pinned span
        // group and optional fields only when pinned; a pinned field the
        // capture lacks fails; an unpinned field the capture emits fails.
        SidecarExpectations.RuntimeExpectation expectation =
            SidecarExpectations.StructuredExpectationSidecar.parse(Files.readString(
                CONFORMANCE.resolve(NAMED.get(0).sidecarFile()), StandardCharsets.UTF_8))
                .expectationFor("luajit");
        SidecarExpectations.ErrorExpectation pin =
            ((SidecarExpectations.RuntimeExpectation.Executed) expectation).error();
        checkEq(Optional.empty(), deal.test.conformance.StructuredExpectationComparator
            .compare("luajit", expectation, executed(framing(pin))),
            "the pinned capture matches its sidecar under the landed comparator");
        SidecarExpectations.ErrorExpectation wrongMessage =
            new SidecarExpectations.ErrorExpectation(pin.code(), "expected int, got nil",
                pin.sourceFile(), pin.line(), pin.column(), pin.expected(), pin.actual(),
                pin.frames(), pin.cause());
        check(deal.test.conformance.StructuredExpectationComparator.compare("luajit",
            expectation, executed(framing(wrongMessage))).isPresent(),
            "a composing capture fails the landed comparison");
        SidecarExpectations.ErrorExpectation unpinnedField =
            new SidecarExpectations.ErrorExpectation(pin.code(), pin.message(),
                pin.sourceFile(), pin.line(), pin.column(), Optional.of("int"),
                Optional.empty(), pin.frames(), pin.cause());
        check(deal.test.conformance.StructuredExpectationComparator.compare("luajit",
            expectation, executed(framing(unpinnedField))).isPresent()
                || pin.actual().isEmpty(),
            "an unpinned field the capture emits fails the comparison");
        SidecarExpectations.ErrorExpectation spanless =
            new SidecarExpectations.ErrorExpectation(pin.code(), pin.message(), null,
                null, null, pin.expected(), pin.actual(), pin.frames(), pin.cause());
        check(deal.test.conformance.StructuredExpectationComparator.compare("luajit",
            expectation, executed(framing(spanless))).isPresent(),
            "a pinned span group the capture lacks fails the comparison");

    }

    private static deal.test.conformance.LaneExecution executed(String stdout) {
        return new deal.test.conformance.LaneExecution.Executed(
            stdout.getBytes(StandardCharsets.UTF_8), new byte[0], 1);
    }

    /** One tuple from a {@code file:line:column} span text (the control's form). */
    private static Tuple tuple(String code, String message, String span,
            String expected, String actual) {
        if (span == null || "-".equals(span)) {
            return new Tuple(code, message, null, null, null, expected, actual);
        }
        int lastColon = span.lastIndexOf(':');
        int prevColon = span.lastIndexOf(':', lastColon - 1);
        return new Tuple(code, message, span.substring(0, prevColon),
            Integer.valueOf(span.substring(prevColon + 1, lastColon)),
            Integer.valueOf(span.substring(lastColon + 1)), expected, actual);
    }

    private static String framing(SidecarExpectations.ErrorExpectation error) {
        return deal.test.conformance.ErrorSnapshot.CODE_LINE_PREFIX + error.code()
            + "\n" + deal.test.conformance.ErrorSnapshot.SNAPSHOT_LINE_PREFIX
            + deal.test.conformance.ErrorSnapshot.canonicalJson(error) + "\n";
    }

    // =========================================================================
    // 7. The raw-profile gate-input controls
    // =========================================================================

    /**
     * The raw-profile gate-input controls (CD3): the original
     * classification metadata of every raw corpus source is validated
     * before discovery can propagate an unclassified metadata exception, a
     * rejected declaration names the fixture and never reaches
     * materialization or compilation, and the rejected compile allocates
     * no scratch root. The pristine source stays the acceptance control.
     */
    private static void testRawProfileInputControls() throws Exception {
        System.out.println("-- the raw-profile gate-input controls: malformed "
            + "metadata is rejected before discovery, materialization or "
            + "compilation --");
        String pristine = Files.readString(CONFORMANCE.resolve(BACKEND_RUNTIME)
            .resolve("arrays/index-read.deal"), StandardCharsets.UTF_8);
        Map<String, String> rejected = new LinkedHashMap<>();
        rejected.put("legacy profile on line 41",
            padding(40) + "// @profile: legacy-safe-int\n" + pristine);
        rejected.put("legacy profile on line 40",
            padding(39) + "// @profile: legacy-safe-int\n" + pristine);
        rejected.put("empty profile",
            padding(40) + "// @profile:\n" + pristine);
        rejected.put("unknown profile",
            "// @profile: no-such-profile\n" + pristine);
        rejected.put("duplicate metadata",
            "// @profile: deal-v1.2-int32\n// @profile: deal-v1.2-int32\n"
                + pristine);
        rejected.put("cross-boundary duplicate",
            "// @profile: deal-v1.2-int32\n" + padding(39)
                + "// @profile: legacy-safe-int\n" + pristine);
        List<Path> roots = new ArrayList<>();
        Path legacyRoot = null;
        try {
            for (Map.Entry<String, String> injection : rejected.entrySet()) {
                Path root = scratchCorpus(
                    injection.getKey().replace(' ', '-'), injection.getValue());
                roots.add(root);
                if (legacyRoot == null) {
                    legacyRoot = root;
                }
                try {
                    corpusSnapshot(root);
                    check(false, "the " + injection.getKey()
                        + " is rejected before discovery");
                } catch (ConformanceHarnessMetadata.HarnessInputDefectException
                        defect) {
                    check(defect.getMessage().contains(
                            ConformanceHarnessMetadata.HARNESS_DEFECT)
                            && defect.getMessage().contains(
                                "backend-runtime/fixture.deal"),
                        "the " + injection.getKey() + " is a named "
                            + ConformanceHarnessMetadata.HARNESS_DEFECT
                            + " naming the fixture: " + defect.getMessage());
                }
            }
            Path pristineRoot = scratchCorpus("pristine", pristine);
            roots.add(pristineRoot);
            CorpusSnapshot accepted = corpusSnapshot(pristineRoot);
            check(accepted.byPath().containsKey("backend-runtime/fixture.deal")
                    && !accepted.byPath().get("backend-runtime/fixture.deal")
                        .source().contains("@profile:"),
                "the pristine declaration is accepted before discovery (the "
                    + "control)");

            // The compile rejection path: the seeded snapshot carries the
            // line-41 legacy declaration, so compile's own module validation
            // rejects it before allocating any scratch root.
            CorpusDiscovery.DiscoveryResult discovery =
                CorpusDiscovery.discover(legacyRoot);
            Map<String, CorpusDiscovery.Fixture> byPath = new LinkedHashMap<>();
            for (CorpusDiscovery.Fixture fixture : discovery.fixtures()) {
                byPath.put(fixture.corpusPath(), fixture);
            }
            CorpusSnapshot previous = corpusSnapshot;
            Set<Path> before = scratchRoots("projection-parity");
            corpusSnapshot = new CorpusSnapshot(discovery, byPath);
            try {
                compile(new Case("fixture", "main", Entry.MAIN, List.of(),
                    null, null));
                check(false, "the line-41 legacy declaration is rejected "
                    + "before compilation");
            } catch (ConformanceHarnessMetadata.HarnessInputDefectException
                    defect) {
                check(defect.getMessage().contains(
                        ConformanceHarnessMetadata.HARNESS_DEFECT)
                        && defect.getMessage().contains(
                            "backend-runtime/fixture.deal"),
                    "the rejected compile is the named gate-input defect: "
                        + defect.getMessage());
            } finally {
                corpusSnapshot = previous;
            }
            Set<Path> after = scratchRoots("projection-parity");
            check(after.equals(before), "the rejected compile leaves no "
                + "projection-parity scratch root behind: " + after);
        } finally {
            for (Path root : roots) {
                deleteRecursively(root);
            }
        }
    }

    /** A scratch corpus root carrying one {@code backend-runtime} fixture. */
    private static Path scratchCorpus(String label, String raw) throws Exception {
        Path root = Files.createTempDirectory(
            "projection-raw-profile-" + label + "-");
        Path file = root.resolve(BACKEND_RUNTIME).resolve("fixture.deal");
        Files.createDirectories(file.getParent());
        Files.writeString(file, raw, StandardCharsets.UTF_8);
        return root;
    }

    /** {@code lines} comment lines that carry no classification prefix. */
    private static String padding(int lines) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            out.append("// padding line ").append(i).append('\n');
        }
        return out.toString();
    }

    /** The temporary roots whose file name starts with {@code prefix}. */
    private static Set<Path> scratchRoots(String prefix) throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Set<Path> roots = new LinkedHashSet<>();
        try (var listing = Files.list(tmp)) {
            listing.filter(p -> p.getFileName().toString().startsWith(prefix))
                .forEach(roots::add);
        }
        return roots;
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        corpusSnapshot = null;
        armChunk = null;
        System.out.println("=== Canonical Projection Parity Tests (ISSUE-0705) ===");
        testNamedDivergences();
        testArmFamilies();
        testHostNullCompletions();
        testHostSyncReturnPresence();
        testHostNumericReturnCarriers();
        testHostNumericCompletions();
        testHostArrayCompletions();
        testHostOutOfRangeArrayCompletions();
        testHostNullParameterCrossing();
        testCallbackEntrySlot();
        testInvalidUnicodeBoundaryOriginDrive();
        testHostStringCarrierProducers();
        testHostNestedArrayProjection();
        testHostNativeArrayCarriers();
        testMissingDeclaredAnnotationFailsClosed();
        testHostWrapperArmAuthority();
        testSerializedArmTableIdentity();
        testMarkedArmsAndFieldShapes();
        testHostInnerReasonPassThroughs();
        testForEachDeletedSlot();
        testDynamicTableRead();
        testArrayNullAndDeletedSlotCells();
        testArrayLiteralElementCells();
        testLuaTableCarrierClassification();
        testNamespaceSurfaceArrayBoundaries();
        testCompletenessAndSingleSourceNegatives();
        testComparisonContract();
        testRawProfileInputControls();
        System.out.println();
        System.out.println("Canonical projection parity: " + passed + " passed, "
            + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
