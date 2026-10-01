package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
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
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.StructuredBodyTable;
import deal.test.conformance.CorpusDiscovery;
import deal.test.conformance.SidecarExpectations;
import deal.test.conformance.SidecarSchemaValidator;

import java.io.File;
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
 *       three-consumer tuple identity. Where the oracle's host seam cannot
 *       carry the value the corpus host returns (the non-operation async
 *       handle), the oracle leg renders the same closed arm.</li>
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
 *       dispatched runtime-classified 390), the sidecar schema (version
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
        Map<String, String> moduleDirToCorpus) {
    }

    /** The release-owned production invocation. */
    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    private static String stripHeaders(String raw, int[] stripped) {
        String source = ConformanceHarnessMetadata.stripClassificationHeaders(raw);
        stripped[0] = raw.split("\n", -1).length - source.split("\n", -1).length;
        return source;
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
        Path root = Files.createTempDirectory("projection-parity");
        Path mirror = root.resolve("conformance");
        CorpusDiscovery.DiscoveryResult discovery =
            CorpusDiscovery.discover(CONFORMANCE);
        Map<String, CorpusDiscovery.Fixture> byPath = new LinkedHashMap<>();
        for (CorpusDiscovery.Fixture fixture : discovery.fixtures()) {
            byPath.put(fixture.corpusPath(), fixture);
        }
        CorpusDiscovery.Fixture entryFixture = byPath.get(spec.fixtureFile());
        if (entryFixture == null) {
            throw new IllegalStateException("the corpus carries no fixture "
                + spec.fixtureFile());
        }
        Map<String, Integer> stripped = new LinkedHashMap<>();
        Map<String, String> modules = new LinkedHashMap<>();
        for (SidecarSchemaValidator.CompilationModule module
                : CorpusDiscovery.compilationSet(entryFixture, byPath, CONFORMANCE)) {
            int[] count = new int[1];
            modules.put(module.corpusPath(), module.source());
            stripped.put(module.corpusPath(), count[0]);
        }
        // The gate core's compilation set is the header-stripped closure;
        // the header-line delta of every module is re-measured from the
        // file on disk (the lane's deployment map) so a captured line
        // rebases onto raw corpus coordinates.
        for (String corpusPath : new ArrayList<>(modules.keySet())) {
            String raw = Files.readString(CONFORMANCE.resolve(corpusPath),
                StandardCharsets.UTF_8);
            int[] count = new int[1];
            String source = stripHeaders(raw, count);
            modules.put(corpusPath, source);
            stripped.put(corpusPath, count[0]);
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
        // The drive's own entry module: it imports the fixture and calls
        // its probe export, so the failing call runs inside the entry
        // module's {@code main} on all three consumers.
        if (spec.entry() == Entry.DRIVER) {
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
        for (String source : modules.values()) {
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
            Files.writeString(declaration, ConformanceHarnessMetadata
                .stripClassificationHeaders(Files.readString(Path.of("test",
                    "conformance", "host-fixtures", stem + ".d.deal"))));
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
            deleteRecursively(root);
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
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT),
            Set.of());
        if (lowering.hasErrors() || lowering.project() == null) {
            deleteRecursively(root);
            throw new IllegalStateException(spec.what() + ": the production "
                + "project lowering failed: " + lowering.diagnostics());
        }
        return new Compiled(root, mirror, spec, invocation, built.input(),
            built.index(), manifests.manifests(),
            orchestrator.hostDeclarationSurface(), identities, externC, stripped,
            lowering.project(), lowering.tables(), lowering.registries(), hostStems,
            modulePathToCorpus, moduleDirToCorpus);
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

    // =========================================================================
    // The three consumers
    // =========================================================================

    /** The oracle's program drive of one compiled case. */
    private static Tuple oracleTuple(Compiled compiled) {
        SemanticOracle.HostResponder responder = responderFor(compiled.spec());
        SemanticRuntimeModel.ConsumerRun run;
        if (compiled.spec().entry() == Entry.ASYNC) {
            run = SemanticOracle.invokeAsyncEntry(compiled.project(),
                compiled.tables(), responder, compiled.checkedProject().entryModule(),
                compiled.spec().probe(), List.of());
        } else {
            run = SemanticOracle.executeProjectInits(compiled.project(),
                compiled.tables(), compiled.registries(), responder);
        }
        if (!(run.terminal()
                instanceof SemanticRuntimeModel.Terminal.DealFailure failure)) {
            throw new IllegalStateException(compiled.spec().what()
                + ": the oracle did not fail: " + run.terminal());
        }
        return tupleOf(failure.error(), compiled, compiled.spec().fixtureFile());
    }

    /**
     * The closed E7 host seam of the async cases (the scripted bad handle
     * for the shape fixture and the violating completion value for the
     * completion fixture).
     */
    private static SemanticOracle.HostResponder responderFor(Case spec) {
        if (spec.oracleResponder() == null) {
            return null;
        }
        return new SemanticOracle.HostResponder() {
            @Override
            public String startAsync(ModuleId module, String export,
                    deal.semantic.ir.RuntimeDescriptor.Func descriptor,
                    List<SemanticOracle.Value> args, String operationLabel) {
                if ("async_shape_bad".equals(spec.hostStem())) {
                    return null;
                }
                return operationLabel;
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
            switch (spec.corpusPath()) {
                case "host-abi/host-bytes-param-mismatch-e8010",
                     "host-abi/host-nullable-function-param-bad",
                     "host-abi/host-rest-bad",
                     "host-abi/host-async-bad" -> {
                    assertModuleExportOrigins(compiled);
            Tuple oracle = oracleTuple(compiled);
                    assertPinned(oracle, pin, spec, "oracle");
                    checkEq(null, firstDifferingField(oracle, lua), spec.what()
                        + ": the oracle and the LuaJIT artifact render the "
                        + "identical tuple");
                }
                case "host-abi/host-async-shape-bad" -> {
                    // The oracle's host seam models a non-operation handle as
                    // the absent handle (its ASYNC_OPERATION_HANDLE terminal
                    // renders {@code nothing}); the value the corpus host
                    // returns is the closed arm's own render, identical to
                    // both production artifacts.
                    BoundaryFailure arm = FailureContractRegistry.render(
                        FailureArmId.ASYNC_SHAPE, Map.of("actual", "number"),
                        "async operation", "number", null);
                    checkEq(lua.message(), arm.message(), spec.what()
                        + ": the oracle arm render and the artifacts render the "
                        + "identical message");
                    checkEq(lua.expected(), arm.expected(), spec.what()
                        + ": the oracle arm render and the artifacts render the "
                        + "identical expected field");
                    checkEq(lua.actual(), arm.actual(), spec.what()
                        + ": the oracle arm render and the artifacts render the "
                        + "identical actual field");
                }
                case "host-abi/host-empty-return-bad" -> assertOracleCell(spec, lua,
                    FailurePolicyId.HOST_SYNC_RETURN, RuntimeDescriptor.Int.INSTANCE,
                    BoundaryValueView.of(ActualKind.NOTHING), BoundaryContext.none());
                case "host-abi/host-nullable-return-bad" -> assertOracleCell(spec, lua,
                    FailurePolicyId.HOST_SYNC_RETURN,
                    RuntimeDescriptor.parseCanonicalText("?string"),
                    BoundaryValueView.ofNumber(1.0), BoundaryContext.none());
                case "host-abi/host-invalid-utf8-e8010" -> assertOracleCell(spec, lua,
                    FailurePolicyId.HOST_SYNC_RETURN, RuntimeDescriptor.String.INSTANCE,
                    BoundaryValueView.of(ActualKind.INVALID_UNICODE),
                    BoundaryContext.none());
                default -> {
                    // The class-construction and host-load families' oracle
                    // renders are the closed arm table's (the three-renderer
                    // identity below); the artifacts carry the pinned tuples.
                }
            }
        } finally {
            deleteRecursively(compiled.root());
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
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT),
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
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT),
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
                List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT),
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

    /** Runs the LuaJIT production artifact under the deferred-entry probe. */
    private static Tuple luaArtifact(Compiled compiled) throws Exception {
        Path out = compiled.root().resolve("out-luajit");
        deal.module.ProductionProjectEmission.Result result = emit(compiled,
            Backend.LUAJIT, out);
        for (String stem : compiled.hostStems()) {
            Path host = out.resolve("host/" + stem + ".lua");
            Files.createDirectories(host.getParent());
            Files.copy(Path.of("test", "conformance", "host-fixtures", stem + ".lua"),
                host, StandardCopyOption.REPLACE_EXISTING);
        }
        String artifact = out.resolve(result.artifactRelativePath())
            .toAbsolutePath().toString();
        Files.writeString(out.resolve("probe.lua"),
            luaProbe(artifact, compiled.checkedProject().entryModule().path()),
            StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder("luajit", "probe.lua");
        builder.directory(out.toFile());
        builder.environment().put("DEAL_DEFER_MAIN", "1");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        check(exit == 0, compiled.spec().what() + " (luajit): the probe runs: exit="
            + exit + " output=" + output);
        Tuple tuple = parseLuaProbe(output, compiled);
        check(tuple != null, compiled.spec().what() + " (luajit): the probe "
            + "captures the failure: " + output);
        return tuple;
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

    /** Runs the JVM production artifact under {@code javac} plus {@code java}. */
    private static Tuple jvmArtifact(Compiled compiled) throws Exception {
        Path out = compiled.root().resolve("out-jvm");
        deal.module.ProductionProjectEmission.Result result = emit(compiled,
            Backend.JVM, out);
        String className = JvmBackend.classNameFor(
            compiled.checkedProject().entryModule().path());
        Path classes = compiled.root().resolve("classes");
        Files.createDirectories(classes);
        Files.writeString(out.resolve("Probe.java"), jvmProbe(className,
            compiled.checkedProject().entryModule().path()), StandardCharsets.UTF_8);
        String classpath = absoluteClasspath();
        List<String> javacArgs = new ArrayList<>(List.of("javac", "--release", "25",
            "-proc:none", "-cp", classpath, "-d", classes.toString()));
        for (String stem : compiled.hostStems()) {
            String hostClass = JvmBackend.classNameFor("host/" + stem);
            Files.copy(Path.of("test", "conformance", "host-fixtures", stem + ".java"),
                out.resolve(hostClass + ".java"),
                StandardCopyOption.REPLACE_EXISTING);
            javacArgs.add(out.resolve(hostClass + ".java").toAbsolutePath()
                .toString());
        }
        javacArgs.add(out.resolve(className + ".java").toAbsolutePath().toString());
        javacArgs.add(out.resolve("Probe.java").toAbsolutePath().toString());
        ProcessBuilder javac = new ProcessBuilder(javacArgs).redirectErrorStream(true);
        javac.directory(out.toFile());
        Process javacProcess = javac.start();
        String javacOutput = new String(javacProcess.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int javacExit = javacProcess.waitFor();
        check(javacExit == 0, compiled.spec().what() + " (jvm): the production "
            + "artifact compiles: " + javacOutput);
        if (javacExit != 0) {
            return null;
        }
        ProcessBuilder java = new ProcessBuilder("java", "-cp",
            classpath + File.pathSeparator + classes, "Probe");
        java.directory(out.toFile());
        java.redirectErrorStream(true);
        Process javaProcess = java.start();
        String output = new String(javaProcess.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = javaProcess.waitFor();
        check(exit == 0, compiled.spec().what() + " (jvm): the probe runs: exit="
            + exit + " output=" + output);
        Tuple tuple = parseJvmProbe(output, compiled);
        check(tuple != null, compiled.spec().what() + " (jvm): the probe captures "
            + "the failure: " + output);
        return tuple;
    }

    private static String jvmProbe(String className, String entryModulePath) {
        return """
            final class Probe {
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
            """.formatted(className, className, entryModulePath);
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
            ProcessBuilder builder = new ProcessBuilder("luajit", "probe.lua");
            builder.directory(workspace.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            int exit = process.waitFor();
            check(exit == 0, "the emitted prelude runs under luajit: exit=" + exit
                + " output=" + output);
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
                ]==]
                local chunk = assert(load(text:sub(1, #text - #tail) .. body, "arms"))
                chunk()
                """.formatted(artifact.toAbsolutePath().toString()),
                StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder("luajit", "probe.lua");
            builder.directory(workspace.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            check(process.waitFor() == 0, "the emitted prelude's inner renderer runs "
                + "under luajit: " + output);
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
            BoundaryValueView.of(ActualKind.INVALID_UNICODE), BoundaryContext.none());
        checkEq("return value 1 type mismatch: " + FailureContractRegistry
            .renderInner(FailureArmId.HOST_STRING_INVALID_UTF8, Map.of()),
            stringCarrier.message(), "the invalid-UTF-8 inner reason is the "
                + "inner-only arm's own text");
        checkEq(FailureContractRegistry.renderInner(
            FailureArmId.HOST_STRING_SURROGATE, Map.of()),
            deal.codegen.jvm.JvmRuntime.stringCarrierReason(true),
            "the JVM runtime renders the surrogate inner text");

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
                row("element", "[int]", {1, "x"})
                """, StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder("luajit", "probe.lua");
            builder.directory(workspace.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            check(process.waitFor() == 0, "the deployed matcher runs under luajit: "
                + output);
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

    private static void testUnchangedSurfaces() throws Exception {
        System.out.println("-- the unchanged surfaces: the corpus membership/count, "
            + "the sidecar schema, the comparison contract, and the landed row "
            + "data --");
        CorpusDiscovery.DiscoveryResult discovery =
            CorpusDiscovery.discover(CONFORMANCE);
        checkEq(List.of(), discovery.failures(),
            "the corpus discovery reports no classification failure");
        int dispatched = 0;
        for (CorpusDiscovery.Fixture fixture : discovery.fixtures()) {
            if (fixture.runtimeClassified()) {
                dispatched++;
            }
        }
        checkEq(390, dispatched, "the dispatched runtime-classified corpus count "
            + "stays the pinned 390");
        for (Case spec : NAMED) {
            String raw = Files.readString(CONFORMANCE.resolve(spec.sidecarFile()),
                StandardCharsets.UTF_8);
            check(raw.contains("\"version\": 1"), spec.what()
                + ": the sidecar keeps its schema version");
            SidecarExpectations.StructuredExpectationSidecar sidecar =
                SidecarExpectations.StructuredExpectationSidecar.parse(raw);
            checkEq(new java.util.TreeSet<>(List.of("luajit", "jvm", "js")),
                new java.util.TreeSet<>(sidecar.byBackend().keySet()), spec.what()
                    + ": the sidecar dispatches the landed three backends");
            SidecarExpectations.ErrorExpectation pin = luajitPin(spec);
            check(pin != null, spec.what() + ": the sidecar carries its error pin");
        }

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

        // The landed INT32_RESULT row data, and the JSON_TO_ERROR row's
        // appended cycle template (ISSUE-0711 W4: the template and its arm
        // land together; the walk template stays index 0 and the std/json
        // template index 1).
        checkEq(List.of("value at {fieldPath} is not JSON serializable: {actual}",
            "unsupported type for JSON encoding: {actual}",
            "cyclic value cannot be encoded as JSON"),
            FailureContractRegistry.row(FailurePolicyId.JSON_TO_ERROR).templates(),
            "the JSON_TO_ERROR row carries the appended cycle template");
        checkEq(List.of("int out of safe range"),
            FailureContractRegistry.row(FailurePolicyId.INT32_RESULT).templates(),
            "the INT32_RESULT row data stays as landed");
        checkEq(FailurePolicyId.JSON_TO_ERROR,
            FailureContractRegistry.arm(FailureArmId.JSON_TO_WALK).policy(),
            "the walk template keeps its bound arm");
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
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Canonical Projection Parity Tests (ISSUE-0705) ===");
        testNamedDivergences();
        testArmFamilies();
        testCallbackEntrySlot();
        testMissingDeclaredAnnotationFailsClosed();
        testSerializedArmTableIdentity();
        testMarkedArmsAndFieldShapes();
        testHostInnerReasonPassThroughs();
        testForEachDeletedSlot();
        testCompletenessAndSingleSourceNegatives();
        testUnchangedSurfaces();
        System.out.println();
        System.out.println("Canonical projection parity: " + passed + " passed, "
            + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
