package deal.test.feature;

import deal.codegen.lua.AsyncExportInvocationRequest;
import deal.codegen.lua.LuaJitAsyncExportInvoker;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.ffi.FfiBindingState;
import deal.ffi.FfiCdefBundle;
import deal.ffi.FfiCdefEntry;
import deal.ffi.FfiClassDescriptor;
import deal.ffi.FfiCompilerClassDefaultPlan;
import deal.ffi.FfiFieldDescriptor;
import deal.ffi.FfiFunctionDescriptor;
import deal.ffi.FfiGeneratedModule;
import deal.ffi.FfiType;
import deal.module.CompilationOrchestrator;
import deal.project.CliOverrides;
import deal.project.ProjectContext;
import deal.project.ProjectDeploymentIdentity;
import deal.project.ProjectLocator;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticProfile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The dedicated production-path ISSUE-0111 feature/native/cross-backend
 * release gate (E13): the only ISSUE-0111 production executor.
 *
 * <p>Execution model, per validated catalog record and mandatory
 * backend:</p>
 * <ol>
 *   <li>materialize a temporary exact-v1.2 project — a parent
 *       {@code deal.json} with the JSON string {@code "1.2"}, explicit
 *       {@code moduleRoots}, the production {@code backend} and
 *       {@code output} fields (or, when the record targets a
 *       malformed-manifest E2010, the record's
 *       {@code manifest-inject.json} bytes published verbatim as
 *       {@code deal.json}, with the sidecar's pinned
 *       {@code manifestErrorFragment} required in the resulting E2010
 *       message — a manifest-discovery E2010 can never pass on a
 *       code-only match) — with the fixture sources copied
 *       byte-identically (never rewritten);</li>
 *   <li>compile through production {@code ProjectLocator} and the full
 *       {@code CompilationOrchestrator} constructor under the documented
 *       pre-activation v1.2 invocation
 *       ({@code COMMON_SHADOW + DEAL_V1_2_INT32}, zero shadow requests —
 *       the same production compile path the conformance harnesses use
 *       for v1.2 fixtures, activation-mechanism-and-legacy-regression-
 *       authority A5);</li>
 *   <li>execute runtime records through the production runtime —
 *       {@code direct-main} via the compiled LuaJIT entry or the
 *       emitted JVM entry class compiled with {@code javac} and run
 *       with {@code java}; {@code synthetic-main} via a gate-generated
 *       typed entry that imports the unchanged fixture source, calls the
 *       declared oracle once through a normal typed boundary, and
 *       returns null; {@code async-export} via the production
 *       {@link LuaJitAsyncExportInvoker} host ABI — with every tool and
 *       fixture process run through the pinned DEALPG4 launcher's
 *       {@code run} containment mode (per-invocation native supervisor:
 *       blocked stub, verified PGID/session, TERM/KILL escalation,
 *       adopted-descendant reaping, group/session-empty proof).</li>
 * </ol>
 *
 * <p>Fail-closed rules: a missing/hung/wrong tool or launcher, a
 * containment failure (a REPORT failure token or a launcher usage
 * exit), a backend omission, a compile-only runtime record, a wrong
 * DEAL error code, or an infrastructure error reported as a DEAL error
 * fails the gate nonzero — never a skip, never a retry, never a
 * downgrade.</p>
 */
public final class V12FeatureGate {

    private static final String LAUNCHER_RELATIVE =
        "tools/deal-process-launcher-linux-x86_64";
    private static final String LAUNCHER_MANIFEST_RELATIVE =
        "tools/launcher-manifest.json";
    private static final String CORPUS_DEFAULT = "test/features";
    private static final String NATIVE_FIXTURES_DEFAULT = "test/native-fixtures";
    private static final String NATIVE_C_FIXTURES = "test/fixtures";

    private static final String CFFI_OK_MARKER = "V12-CFFI-OK";
    private static final String RUNTIME_ERROR_PREFIX = "DEAL_ERROR_CODE: ";
    /** The pinned import/call spans of the gate-owned C FFI loader
     *  emission (the generated-import shape, gate-owned evidence
     *  wiring). */
    private static final String CFFI_IMPORT_FILE = "src/main.deal";
    private static final String CFFI_IMPORT_LINE = "1";
    private static final String CFFI_IMPORT_COLUMN = "1";
    private static final String CFFI_CALL_FILE = "src/main.deal";
    private static final String CFFI_CALL_LINE = "2";
    private static final String CFFI_CALL_COLUMN = "1";

    /** The gate-owned Lua chunk runner (production-shaped error surface). */
    private static final String LUA_RUNNER = """
        -- Generated by the ISSUE-0165 feature gate: executes the compiled
        -- entry chunk exactly once; a raised DEAL error surfaces through
        -- the DEAL_ERROR_CODE convention and exit 1 (the production
        -- async-driver chunk-loading precedent).
        local ok, err = pcall(function() dofile(arg[1]) end)
        if not ok then
          local code = "UNKNOWN"
          local message = tostring(err)
          if type(err) == "table" and type(err.code) == "string" then
            code = err.code
            message = tostring(err.message)
          end
          print("DEAL_ERROR_CODE: " .. code .. " " .. message)
          os.exit(1)
        end
        """;

private final Path repoRoot;
    private final Path launcher;
    private final Path corpusRoot;
    private final Path nativeFixtures;
    private final SecureRandom random = new SecureRandom();
    /** The per-execution event files of gate-compiled native fixtures,
     *  keyed by C fixture name (populated by materializeProject, read by
     *  the C FFI driver). */
    private final java.util.Map<String, Path> nativeEvents =
        new java.util.LinkedHashMap<>();
    private int passed;
    private int failed;

    private V12FeatureGate(Path repoRoot, Path corpusRoot, Path nativeFixtures) {
        this.repoRoot = repoRoot;
        this.launcher = repoRoot.resolve(LAUNCHER_RELATIVE);
        this.corpusRoot = corpusRoot;
        this.nativeFixtures = nativeFixtures;
    }

    /**
     * Gate entry point. Exit 0 iff every catalog record executes its
     * declared outcome on every mandatory backend, the launcher
     * preflight passes, the production async-export evidence step
     * completes, and the C_FFI records load their production-generated
     * bindings through the production {@code __rt.load_ffi} pipeline
     * with the pinned real-native rows.
     */
    public static void main(String[] args) {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path corpus = cwd.resolve(CORPUS_DEFAULT);
        Path nativeFixtures = cwd.resolve(NATIVE_FIXTURES_DEFAULT);
        if (args.length >= 1) {
            corpus = Path.of(args[0]).toAbsolutePath().normalize();
        }
        if (args.length >= 2) {
            nativeFixtures = Path.of(args[1]).toAbsolutePath().normalize();
        }
        V12FeatureGate gate = new V12FeatureGate(cwd, corpus, nativeFixtures);
        int status = gate.run();
        System.out.println();
        System.out.println("=== V12 Feature Gate: " + gate.passed + " passed, "
            + gate.failed + " failed ===");
        if (status != 0) {
            System.out.println("=== V12 Feature Gate FAILED ===");
        }
        System.exit(status);
    }

    private int run() {
        try {
            preflightLauncher();
            V12FeatureFixtureCatalog catalog =
                V12FeatureFixtureCatalog.load(corpusRoot);
            System.out.println("=== V12 Feature Gate: " + catalog.records().size()
                + " catalog records validated ===");
            for (V12FeatureFixtureCatalog.RecordEntry entry : catalog.records()) {
                for (String backend : entry.metadata().backends()) {
                    executeRecord(catalog, entry, backend);
                }
            }
            asyncExportEvidence();
            return failed == 0 ? 0 : 1;
        } catch (RuntimeException e) {
            System.err.println("V12FEATURE-GATE-FAILURE: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    // =========================================================================
    // Launcher preflight (runtime page D12: the pinned launcher exists
    // and self-tests before any tool probe).
    // =========================================================================

    private void preflightLauncher() {
        System.out.println("=== Launcher preflight ===");
        if (!Files.isRegularFile(launcher) || Files.isSymbolicLink(launcher)) {
            throw new GateFailure("launcher " + launcher
                + " is not a regular non-symlink file");
        }
        if (!Files.isExecutable(launcher)) {
            throw new GateFailure("launcher " + launcher + " is not executable");
        }
        String manifestText = readText(repoRoot.resolve(LAUNCHER_MANIFEST_RELATIVE));
        V12FeatureMetadata.JsonValue.ObjectVal manifest = parseManifest(manifestText);
        String pinnedSha256 = stringField(manifest, "sha256");
        String protocol = stringField(manifest, "protocol");
        long version = longField(manifest, "version");
        String platform = stringField(manifest, "platform");
        if (!protocol.equals("DEALPG4") || version != 4
                || !platform.equals("linux-x86_64")) {
            throw new GateFailure("launcher manifest pins protocol '" + protocol
                + "' version " + version + " platform '" + platform
                + "', expected DEALPG4 4 linux-x86_64");
        }
        String actualSha256 = sha256Hex(launcher);
        if (!actualSha256.equals(pinnedSha256)) {
            throw new GateFailure("launcher SHA-256 mismatch: manifest pins "
                + pinnedSha256 + ", binary is " + actualSha256);
        }
        RunOutcome probe = runDirect(List.of(launcher.toString(), "probe"),
            repoRoot.toString());
        if (probe.exitCode != 0) {
            throw new GateFailure("launcher probe exited " + probe.exitCode
                + ": " + probe.stderr);
        }
        String probeOut = probe.stdout;
        if (!probeOut.startsWith("DEALPG4 4 linux-x86_64 CAPS ")) {
            throw new GateFailure("launcher probe identity line missing: "
                + firstLine(probeOut));
        }
        long okLines = probeOut.lines().filter(l -> l.startsWith("OK ")).count();
        if (okLines < 5) {
            throw new GateFailure("launcher probe carried " + okLines
                + " OK lines (expected at least 5): " + probeOut);
        }
        System.out.println("  launcher " + launcher.getFileName()
            + " digest + probe OK (caps verified)");
    }

    private static V12FeatureMetadata.JsonValue.ObjectVal parseManifest(String text) {
        V12FeatureMetadata.JsonValue root = V12FeatureMetadata.JsonReader.parse(text);
        if (!(root instanceof V12FeatureMetadata.JsonValue.ObjectVal obj)) {
            throw new GateFailure("launcher manifest is not a JSON object");
        }
        return obj;
    }

    private static String stringField(
            V12FeatureMetadata.JsonValue.ObjectVal obj, String key) {
        if (obj.members().get(key)
                instanceof V12FeatureMetadata.JsonValue.StringVal s) {
            return s.value();
        }
        throw new GateFailure("launcher manifest field '" + key
            + "' is not a JSON string");
    }

    private static long longField(
            V12FeatureMetadata.JsonValue.ObjectVal obj, String key) {
        if (obj.members().get(key)
                instanceof V12FeatureMetadata.JsonValue.NumberVal n) {
            try {
                return Long.parseLong(n.sourceText());
            } catch (NumberFormatException e) {
                throw new GateFailure("launcher manifest field '" + key
                    + "' is not an integer: " + n.sourceText());
            }
        }
        throw new GateFailure("launcher manifest field '" + key
            + "' is not a JSON number");
    }

    // =========================================================================
    // Record execution
    // =========================================================================

    private void executeRecord(V12FeatureFixtureCatalog catalog,
                               V12FeatureFixtureCatalog.RecordEntry entry,
                               String backend) {
        String label = entry.id() + " [" + backend + "]";
        try {
            nativeEvents.clear();
            Path project = materializeProject(catalog, entry, backend);
            CompileOutcome outcome = compileProject(project, entry, backend);
            checkIdentityArtifact(entry, outcome);
            boolean ok;
            if (entry.metadata().feature() == FeatureId.C_FFI
                    && backend.equals("luajit")) {
                // The C FFI runtime record: after the production compile
                // the gate wires the production-generated bindings
                // through the production __rt.load_ffi pipeline (the
                // generated-import emission shape) and asserts the real
                // ABI/error/ownership/replay rows.
                ok = executeCffiRecord(entry, outcome, label);
            } else {
                V12FeatureMetadata.Expectation expectation =
                    entry.metadata().expected();
                ok = switch (expectation) {
                    case V12FeatureMetadata.CompileOk ignored ->
                        checkCompileOk(entry, backend, outcome, label);
                    case V12FeatureMetadata.CompileError error ->
                        checkCompileError(entry, backend, outcome,
                            error.code(), label);
                    case V12FeatureMetadata.RuntimeOk ignored ->
                        checkRuntime(entry, backend, outcome, null, label);
                    case V12FeatureMetadata.RuntimeError error ->
                        checkRuntime(entry, backend, outcome,
                            error.code(), label);
                };
            }
            record(label, ok);
        } catch (GateFailure failure) {
            record(label, false);
            System.err.println("  FAIL " + label + ": " + failure.getMessage());
        } finally {
            // Temp projects are system-scoped; leave cleanup to the OS.
        }
    }

    /**
     * The identity-separation artifact pin (int32/bytes page D5-D6,
     * {@code strict-project-context-resolution-identity} D4): a record
     * carrying {@code identityArtifact} must emit, on every backend,
     * the pinned canonical public class descriptor in at least one
     * artifact, while the private project deployment identity (the
     * canonical manifest URI and the validated manifest-content digest
     * read from the production {@code ProjectContext}) never appears
     * in any emitted artifact text. A private-identity leak or a
     * missing public descriptor is a hard gate failure — the check
     * runs on the pure emitted artifact set before runtime execution.
     */
    private void checkIdentityArtifact(
            V12FeatureFixtureCatalog.RecordEntry entry,
            CompileOutcome outcome) {
        V12FeatureMetadata.IdentityArtifact pin =
            entry.metadata().identityArtifact();
        if (pin == null || outcome.locateE2010 != null || !outcome.success
                || outcome.outputRoot == null) {
            return;
        }
        ProjectDeploymentIdentity identity = outcome.deploymentIdentity();
        String violation = identityArtifactViolation(outcome.outputRoot,
            pin.classDescriptor(),
            identity == null ? null
                : identity.validatedManifestContentDigest(),
            identity == null ? null : identity.canonicalManifestUri());
        if (violation != null) {
            throw new GateFailure(violation);
        }
    }

    /**
     * The testable identity-separation pin: walks every emitted
     * artifact under the output root and requires (1) the pinned
     * public class descriptor to appear in at least one artifact and
     * (2) neither private deployment-identity value (the 64-hex
     * manifest-content digest or the canonical manifest URI) to appear
     * in any artifact text. Returns a violation message or null.
     */
    static String identityArtifactViolation(Path outputRoot,
            String classDescriptor, String privateDigest,
            String canonicalManifestUri) {
        if (classDescriptor == null || classDescriptor.isEmpty()
                || !classDescriptor.startsWith("@")) {
            return "identityArtifact classDescriptor must be a canonical "
                + "@-prefixed descriptor, got: " + classDescriptor;
        }
        List<String> artifacts = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(outputRoot)) {
            walk.filter(Files::isRegularFile)
                .sorted()
                .forEach(p -> artifacts.add(p.toString()));
        } catch (IOException e) {
            return "cannot walk the emitted artifacts under " + outputRoot
                + ": " + e;
        }
        boolean descriptorSeen = false;
        for (String artifact : artifacts) {
            String text = readText(Path.of(artifact));
            if (text.contains(classDescriptor)) {
                descriptorSeen = true;
            }
            if (privateDigest != null && !privateDigest.isEmpty()
                    && text.toLowerCase(Locale.ROOT)
                        .contains(privateDigest.toLowerCase(Locale.ROOT))) {
                return "private deployment identity leaked: the validated "
                    + "manifest-content digest appears in emitted artifact "
                    + artifact;
            }
            if (canonicalManifestUri != null && !canonicalManifestUri.isEmpty()
                    && text.contains(canonicalManifestUri)) {
                return "private deployment identity leaked: the canonical "
                    + "manifest URI appears in emitted artifact " + artifact;
            }
        }
        if (!descriptorSeen) {
            return "no emitted artifact under " + outputRoot
                + " carries the pinned public class descriptor '"
                + classDescriptor + "'";
        }
        return null;
    }

    private boolean checkCompileOk(V12FeatureFixtureCatalog.RecordEntry entry,
                                   String backend, CompileOutcome outcome,
                                   String label) {
        if (outcome.locateE2010 != null) {
            throw new GateFailure("expected compile-ok, locator produced E2010: "
                + outcome.locateE2010.message());
        }
        if (!outcome.success) {
            throw new GateFailure("expected compile-ok, compile failed: "
                + outcome.diagnostics);
        }
        if (entry.id().contains("jsonable-warning")
                || entry.id().contains("anchor-break")) {
            boolean warning = outcome.diagnosticsList.stream().anyMatch(d ->
                d.code().equals("E1043") && "warning".equals(d.severity()));
            if (!warning) {
                throw new GateFailure("expected the E1043 warning for the "
                    + "invalid/unattached @jsonable directive");
            }
        }
        if (entry.id().contains("anchor-attach")) {
            boolean warning = outcome.diagnosticsList.stream().anyMatch(d ->
                d.code().equals("E1043"));
            if (warning) {
                throw new GateFailure("a directly-attached valid @jsonable "
                    + "must produce no E1043 warning");
            }
        }
        return true;
    }

    private boolean checkCompileError(V12FeatureFixtureCatalog.RecordEntry entry,
                                      String backend, CompileOutcome outcome,
                                      String code, String label) {
        if (outcome.locateE2010 != null) {
            if (!code.equals(outcome.locateE2010.code())) {
                throw new GateFailure("expected compile-error " + code
                    + ", locator produced " + outcome.locateE2010.code()
                    + ": " + outcome.locateE2010.message());
            }
            switch (entry.metadata().manifestPolicy()) {
                case INJECT -> {
                    String violation = manifestE2010Violation(entry,
                        outcome.locateE2010);
                    if (violation != null) {
                        throw new GateFailure(violation);
                    }
                }
                case MISSING, MULTIPLE -> {
                    String violation = discoveryE2010Violation(entry,
                        outcome.locateE2010);
                    if (violation != null) {
                        throw new GateFailure(violation);
                    }
                }
                case GENERATED -> throw new GateFailure("a GENERATED-policy "
                    + "record must not produce a locator E2010 (the "
                    + "manifest is generated by the gate)");
            }
            assertNoArtifacts(outcome);
            return true;
        }
        if (outcome.success) {
            throw new GateFailure("expected compile-error " + code
                + ", compile succeeded");
        }
        boolean found = outcome.diagnosticsList.stream()
            .anyMatch(d -> d.code().equals(code)
                && "error".equals(d.severity())
                && fromFixture(d, entry));
        if (!found) {
            throw new GateFailure("expected compile-error " + code
                + " originating in the fixture; diagnostics: "
                + outcome.diagnostics);
        }
        if (code.equals("E1045") && entry.id().contains("scalar-range")) {
            boolean ranged = outcome.diagnosticsList.stream()
                .filter(d -> d.code().equals("E1045"))
                .anyMatch(V12FeatureGate::hasFullScalarRange);
            if (!ranged) {
                throw new GateFailure("expected a full scalar range on the "
                    + "E1045 diagnostic");
            }
        }
        assertNoArtifacts(outcome);
        return true;
    }

    /**
     * The pinned manifest-discovery E2010 check (zero- or
     * multiple-ancestor-manifest records): the E2010 must be the
     * discovery failure — a synthetic range anchored at the entry file —
     * carrying the sidecar's pinned message fragment; the
     * multiple-manifest record additionally requires the candidate-path
     * note per discovered manifest (at least two).
     *
     * @return null when compliant, otherwise the failure text
     */
    static String discoveryE2010Violation(
            V12FeatureFixtureCatalog.RecordEntry entry,
            CompilerDiagnostic diagnostic) {
        DiagnosticRange range = diagnostic.range();
        if (range == null || range.origin() != RangeOrigin.SYNTHETIC) {
            return "expected the pinned manifest-discovery E2010 (a "
                + "synthetic range) for '" + entry.id() + "'; the record "
                + "must exercise manifest discovery, not a parse failure: "
                + diagnostic.message();
        }
        String fragment = entry.metadata().manifestErrorFragment();
        if (fragment == null || !diagnostic.message().contains(fragment)) {
            return "expected the E2010 message to carry the pinned "
                + "manifestErrorFragment '" + fragment + "' for '" + entry.id()
                + "', got: " + diagnostic.message();
        }
        if (entry.metadata().manifestPolicy()
                == V12FeatureMetadata.ManifestPolicy.MULTIPLE) {
            long candidates = diagnostic.notes().stream()
                .filter(n -> n.message().startsWith("candidate manifest: "))
                .count();
            if (candidates < 2) {
                return "expected at least two candidate-manifest notes on "
                    + "the multiple-manifest E2010 for '" + entry.id()
                    + "', got " + candidates;
            }
        }
        return null;
    }

    /**
     * The pinned manifest-schema E2010 check for malformed-manifest
     * records: the E2010 must be the exercised manifest-schema failure,
     * not a manifest-discovery failure. A discovery E2010 carries a
     * synthetic range, while every manifest-parse E2010 carries a SOURCE
     * range anchored in deal.json; the sidecar's
     * {@code manifestErrorFragment} additionally pins the exact failure
     * text, so a code-only match can never vacuous-pass.
     *
     * @return null when compliant, otherwise the failure text
     */
    static String manifestE2010Violation(
            V12FeatureFixtureCatalog.RecordEntry entry,
            CompilerDiagnostic diagnostic) {
        DiagnosticRange range = diagnostic.range();
        String file = range == null ? null : range.file();
        if (range == null || range.origin() != RangeOrigin.SOURCE
                || file == null
                || !file.replace('\\', '/').endsWith("/deal.json")) {
            return "expected the pinned manifest-schema E2010 (a SOURCE range "
                + "anchored in deal.json) for '" + entry.id() + "'; the record "
                + "must exercise the injected manifest, not manifest "
                + "discovery: " + diagnostic.message();
        }
        String fragment = entry.metadata().manifestErrorFragment();
        if (fragment == null || !diagnostic.message().contains(fragment)) {
            return "expected the E2010 message to carry the pinned "
                + "manifestErrorFragment '" + fragment + "' for '" + entry.id()
                + "', got: " + diagnostic.message();
        }
        return null;
    }

    private static boolean fromFixture(CompilerDiagnostic diagnostic,
                                       V12FeatureFixtureCatalog.RecordEntry entry) {
        DiagnosticRange range = diagnostic.range();
        if (range == null || range.file() == null) {
            return false;
        }
        String file = range.file().replace('\\', '/');
        return file.endsWith(".deal") || file.endsWith(".d.deal");
    }

    private static boolean hasFullScalarRange(CompilerDiagnostic diagnostic) {
        DiagnosticRange range = diagnostic.range();
        return range != null
            && range.origin() == RangeOrigin.SOURCE
            && range.scalarLength() > 0
            && range.endScalarOffset() == range.startScalarOffset()
                + range.scalarLength()
            && range.endLine() >= range.startLine();
    }

    private void assertNoArtifacts(CompileOutcome outcome) {
        if (outcome.outputRoot == null || !Files.exists(outcome.outputRoot)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(outcome.outputRoot)) {
            boolean any = walk.anyMatch(Files::isRegularFile);
            if (any) {
                throw new GateFailure("a failed compilation published artifacts "
                    + "under " + outcome.outputRoot);
            }
        } catch (IOException e) {
            throw new GateFailure("cannot inspect the output root: " + e);
        }
    }

    private boolean checkRuntime(V12FeatureFixtureCatalog.RecordEntry entry,
                                 String backend, CompileOutcome outcome,
                                 String errorCode, String label) {
        if (outcome.locateE2010 != null || !outcome.success) {
            throw new GateFailure("expected a successful compile before "
                + "runtime execution: "
                + (outcome.locateE2010 != null
                    ? outcome.locateE2010.message()
                    : outcome.diagnostics));
        }
        RunOutcome run = switch (entry.metadata().invocation()) {
            case DIRECT_MAIN, SYNTHETIC_MAIN -> executeRuntime(entry, backend,
                outcome);
            case ASYNC_EXPORT -> executeAsyncExport(entry, backend, outcome);
            case COMPILE_ONLY -> throw new GateFailure(
                "compile-only invocation cannot run a runtime record");
        };
        return checkRunOutcome(entry, errorCode, label, run);
    }

    /**
     * The shared runtime-outcome check over one produced execution
     * (direct/synthetic entry, async-export host invocation, or the
     * gate-owned C FFI loader run): a runtime-ok must exit zero under
     * clean containment; a runtime-error must be a clean non-containment
     * target failure that surfaces the exact {@code DEAL_ERROR_CODE}
     * — an infrastructure or containment failure never matches.
     */
    private boolean checkRunOutcome(V12FeatureFixtureCatalog.RecordEntry entry,
                                    String errorCode, String label,
                                    RunOutcome run) {
        if (errorCode == null) {
            if (run.exitCode != 0) {
                throw new GateFailure("runtime-ok expected exit 0, got "
                    + run.exitCode + ": " + run.stdout + run.stderr);
            }
            if (!run.containmentClean) {
                throw new GateFailure("containment failure: " + run.stderr);
            }
            return true;
        }
        if (run.exitCode == 0 || run.containmentFailure()) {
            throw new GateFailure("runtime-error " + errorCode
                + " expected a non-containment target failure; exit "
                + run.exitCode + " failureToken " + run.failureToken
                + ": " + run.stdout + run.stderr);
        }
        String combined = run.stdout + "\n" + run.stderr;
        if (!combined.contains(RUNTIME_ERROR_PREFIX + errorCode)) {
            throw new GateFailure("runtime-error record must observe "
                + RUNTIME_ERROR_PREFIX + errorCode + "; output: " + combined);
        }
        return true;
    }

    // =========================================================================
    // Compilation through the production locator/orchestrator
    // =========================================================================

    private record CompileOutcome(boolean success, String diagnostics,
                                  List<CompilerDiagnostic> diagnosticsList,
                                  CompilerDiagnostic locateE2010,
                                  Path outputRoot,
                                  java.util.Map<String, FfiGeneratedModule>
                                      ffiGenerations,
                                  ProjectDeploymentIdentity
                                      deploymentIdentity) {
    }

    private CompileOutcome compileProject(Path project,
                                          V12FeatureFixtureCatalog.RecordEntry entry,
                                          String backend) {
        Path entryFile = project.resolve("src/main.deal")
            .toAbsolutePath().normalize();
        ProjectLocator.LocateResult located =
            ProjectLocator.locate(entryFile.toString(), null);
        if (located.context() == null) {
            String text = located.e2010() != null ? located.e2010().message()
                : located.cliDiagnostic() != null
                    ? located.cliDiagnostic().message() : "locate failed";
            return new CompileOutcome(false, text, List.of(), located.e2010(),
                null, java.util.Map.of(), null);
        }
        ProjectContext context = located.context();
        var invocation = CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.PRE_ACTIVATION,
            CapabilityRegistry.releaseRegistry());
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        List<CompilerDiagnostic> diagnostics;
        java.util.Map<String, FfiGeneratedModule> ffiGenerations;
        boolean success;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                context, entryFile, false, false, false, false, null,
                invocation);
            try {
                success = orchestrator.compile();
            } catch (IOException e) {
                throw new GateFailure("orchestrator I/O failure for " + entryFile
                    + ": " + e);
            }
            diagnostics = orchestrator.diagnostics();
            ffiGenerations = orchestrator.ffiGenerations();
        } finally {
            System.out.flush();
            System.err.flush();
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new CompileOutcome(success, renderDiagnostics(diagnostics),
            diagnostics, null,
            Path.of(context.outputPath().absoluteNormalizedPath()),
            ffiGenerations, context.projectDeploymentIdentity());
    }

    private static String renderDiagnostics(List<CompilerDiagnostic> diagnostics) {
        StringBuilder sb = new StringBuilder();
        for (CompilerDiagnostic diagnostic : diagnostics) {
            sb.append(diagnostic.code()).append(':')
                .append(diagnostic.severity()).append(':')
                .append(diagnostic.message()).append('\n');
        }
        return sb.toString();
    }

    // =========================================================================
    // Temporary exact-v1.2 project materialization
    // =========================================================================

    private Path materializeProject(V12FeatureFixtureCatalog catalog,
                                  V12FeatureFixtureCatalog.RecordEntry entry,
                                  String backend) {
        final Path project;
        try {
            project = Files.createTempDirectory("v12feature-");
        } catch (IOException e) {
            throw new GateFailure("cannot create the temporary project: " + e);
        }
        // Fixture sources copy byte-identically; the manifest injects are
        // published as deal.json below, per the manifest policy.
        List<Path> injects = catalog.recordInjectFiles(entry);
        for (Path source : catalog.recordSourceFiles(entry)) {
            if (source.getFileName().toString().equals(
                    V12FeatureFixtureCatalog.MANIFEST_INJECT)) {
                continue;
            }
            Path relative = entry.directory().relativize(source);
            copyBytes(source, project.resolve(relative.toString()));
        }
        // Support subtrees referenced by this record's support list land
        // as project siblings under their basename (relative ../ imports).
        for (String support : entry.metadata().support()) {
            Path supportDir = catalog.supportDirectory(support);
            if (supportDir == null) {
                throw new GateFailure("unresolved support path '" + support + "'");
            }
            copyTree(supportDir,
                project.resolve(supportDir.getFileName().toString()));
        }
        V12FeatureMetadata.ManifestPolicy policy =
            entry.metadata().manifestPolicy();
        switch (policy) {
            case INJECT, MULTIPLE -> {
                // Every record inject publishes verbatim as deal.json
                // beside it, so the production locator genuinely parses
                // the malformed/duplicate manifest(s). checkCompileError
                // then requires the policy's pinned E2010 (a SOURCE range
                // in deal.json for inject, a synthetic discovery range
                // for multiple), so a wrong-class E2010 can never pass on
                // a code-only match.
                for (Path inject : injects) {
                    Path relative = entry.directory().relativize(inject);
                    copyBytes(inject,
                        project.resolve(relative).resolveSibling("deal.json"));
                }
                return project;
            }
            case MISSING -> {
                // No manifest is written: the production locator must
                // produce the zero-manifest discovery E2010.
                return project;
            }
            case GENERATED -> {
                // The standard generated manifest below.
            }
        }
        if (entry.metadata().invocation()
                == V12FeatureMetadata.Invocation.SYNTHETIC_MAIN) {
            writeText(project.resolve("src/main.deal"),
                syntheticEntry(entry));
        }
        writeText(project.resolve("deal.json"),
            generatedManifest(entry, backend));
        return project;
    }

    /**
     * The gate-generated exact-v1.2 manifest: JSON string
     * {@code "1.2"}, the explicit {@code src} root, the production
     * backend/output fields, and — for C_FFI records — the production
     * externals entries of the sidecar's native plan (each declaration
     * path verbatim; each library reference the gate-compiled shared
     * object's absolute path, or the pinned absolute path of the
     * unloadable row).
     */
    private String generatedManifest(
            V12FeatureFixtureCatalog.RecordEntry entry, String backend) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"languageVersion\": \"1.2\",\n")
            .append("  \"moduleRoots\": [\"src\"],\n")
            .append("  \"backend\": \"").append(backend).append("\",\n")
            .append("  \"output\": \"build/").append(backend).append("\"");
        V12FeatureMetadata.NativeBlock nativePlan = entry.metadata().nativePlan();
        if (nativePlan != null) {
            sb.append(",\n  \"externals\": {\n");
            boolean first = true;
            for (V12FeatureMetadata.NativeEntry nativeEntry
                    : nativePlan.entries()) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append("    \"").append(jsonEscape(nativeEntry.importSpecifier()))
                    .append("\": {\n")
                    .append("      \"declaration\": \"")
                    .append(jsonEscape(nativeEntry.declaration()))
                    .append("\",\n")
                    .append("      \"nativeLibrary\": \"")
                    .append(jsonEscape(nativeLibraryPath(entry,
                        nativeEntry, backend)))
                    .append("\"\n")
                    .append("    }");
            }
            sb.append("\n  }");
        }
        sb.append("\n}\n");
        return sb.toString();
    }

    /**
     * One externals nativeLibrary value: a {@code .c} library is the
     * repository C fixture — on the LuaJIT runtime leg the gate compiles
     * it with contained GCC into a shared object and returns its
     * absolute path (the events file is recorded for the C FFI driver);
     * on a compile-error leg (the linked JVM E6003 record) a pinned
     * placeholder absolute path is used (the library is never loaded).
     * Any other value is a pinned absolute path used verbatim (the
     * valid-but-unloadable classification row).
     */
    private String nativeLibraryPath(
            V12FeatureFixtureCatalog.RecordEntry entry,
            V12FeatureMetadata.NativeEntry nativeEntry, String backend) {
        String library = nativeEntry.library();
        if (!library.endsWith(".c")) {
            return library;
        }
        boolean runtimeLeg = backend.equals("luajit")
            && (entry.metadata().expected()
                instanceof V12FeatureMetadata.RuntimeOk
                || entry.metadata().expected()
                    instanceof V12FeatureMetadata.RuntimeError);
        if (!runtimeLeg) {
            return "/v12-linked-jvm-e6003/" + library;
        }
        return compileNativeFixture(entry, library).toString();
    }

    /** Compiles one repository C fixture into a shared object with
     *  contained GCC (runtime page D8) and records its fresh event
     *  file for the C FFI driver. */
    private Path compileNativeFixture(
            V12FeatureFixtureCatalog.RecordEntry entry, String fixtureName) {
        Path cFixture = repoRoot.resolve(NATIVE_C_FIXTURES)
            .resolve(fixtureName);
        if (!Files.isRegularFile(cFixture)) {
            throw new GateFailure("record '" + entry.id()
                + "' names a missing repository C fixture " + cFixture);
        }
        try {
            Path buildDir = repoRoot.resolve("build");
            Files.createDirectories(buildDir);
            String stem = fixtureName.substring(0,
                fixtureName.length() - ".c".length());
            Path soPath = buildDir.resolve(stem + "-"
                + nonce().substring(0, 8) + ".so")
                .toAbsolutePath().normalize();
            Path eventsPath = buildDir.resolve(soPath.getFileName()
                + ".events.log").toAbsolutePath().normalize();
            Files.deleteIfExists(eventsPath);
            nativeEvents.put(fixtureName, eventsPath);
            String define = "-DFIXTURE_EVENTS_PATH=\"" + eventsPath + "\"";
            RunOutcome gcc = runContained(repoRoot.toString(),
                List.of("gcc", "-shared", "-fPIC", "-O2", define,
                    "-o", soPath.toString(), cFixture.toString()));
            if (gcc.exitCode != 0 || gcc.containmentFailure()) {
                throw new GateFailure("gcc failed to compile the fixture "
                    + fixtureName + ": " + gcc.stdout + gcc.stderr);
            }
            if (!Files.isRegularFile(soPath)) {
                throw new GateFailure("shared object missing after gcc: "
                    + soPath);
            }
            return soPath;
        } catch (IOException e) {
            throw new GateFailure("cannot prepare the native fixture "
                + fixtureName + ": " + e);
        }
    }

    /** Minimal JSON string escaping for gate-generated manifest values. */
    private static String jsonEscape(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * The typed synthetic entry (declarations page D12): imports the
     * unchanged fixture source, calls the declared oracle once through a
     * normal typed boundary, and returns null. The return annotation is
     * derived from the canonical sync oracle descriptor {@code ()->R}.
     */
    private static String syntheticEntry(
            V12FeatureFixtureCatalog.RecordEntry entry) {
        V12FeatureMetadata.Oracle oracle = entry.metadata().oracle();
        if (oracle == null || !oracle.functionDescriptor().startsWith("()->")) {
            throw new GateFailure("synthetic-main record '" + entry.id()
                + "' requires a canonical ()->R oracle");
        }
        String returnType = oracle.functionDescriptor()
            .substring("()->".length());
        return "// Generated by the ISSUE-0165 feature gate (synthetic-main):\n"
            + "// imports the unchanged fixture source, calls the declared\n"
            + "// oracle once through a normal typed boundary, returns null.\n"
            + "import * as fixture from \"./fixture\"\n"
            + "\n"
            + "export function main(): null {\n"
            + "  let result: " + returnType + " = fixture."
            + oracle.exportName() + "();\n"
            + "  if (result !== 42) {\n"
            + "    throw { code: \"TEST_FAIL\", message: \"synthetic oracle "
            + "boundary\" };\n"
            + "  }\n"
            + "  return null;\n"
            + "}\n";
    }

    private static void copyTree(Path source, Path target) {
        try (Stream<Path> walk = Files.walk(source)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                Path relative = source.relativize(file);
                copyBytes(file, target.resolve(relative.toString()));
            });
        } catch (IOException e) {
            throw new GateFailure("cannot copy support tree " + source + ": " + e);
        }
    }

    private static void copyBytes(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
        } catch (IOException e) {
            throw new GateFailure("cannot copy fixture file " + source
                + " to " + target + ": " + e);
        }
    }

    private static void writeText(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GateFailure("cannot write " + file + ": " + e);
        }
    }

    private static String readText(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GateFailure("cannot read " + file + ": " + e);
        }
    }

    // =========================================================================
    // Production runtime execution (direct/synthetic/async)
    // =========================================================================

    private RunOutcome executeRuntime(V12FeatureFixtureCatalog.RecordEntry entry,
                                      String backend, CompileOutcome outcome) {
        Path outputRoot = outcome.outputRoot;
        if (backend.equals("luajit")) {
            Path entryArtifact = outputRoot.resolve("main.lua");
            if (!Files.isRegularFile(entryArtifact)) {
                throw new GateFailure("LuaJIT entry artifact missing: "
                    + entryArtifact);
            }
            // The gate-owned runner executes the compiled entry chunk
            // exactly once and surfaces a raised DEAL error through the
            // DEAL_ERROR_CODE convention (the production async driver's
            // chunk-loading shape, harness precedent).
            writeText(outputRoot.resolve("v12_runner.lua"), LUA_RUNNER);
            return runContained(outputRoot.toString(),
                List.of("luajit", "v12_runner.lua",
                    entryArtifact.getFileName().toString()));
        }
        // JVM: compile every emitted artifact with javac, then run the
        // emitted entry class (the module carrying public static main).
        List<String> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(outputRoot)) {
            walk.filter(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().endsWith(".java"))
                .sorted()
                .forEach(p -> sources.add(p.toString()));
        } catch (IOException e) {
            throw new GateFailure("cannot list emitted JVM artifacts: " + e);
        }
        if (sources.isEmpty()) {
            throw new GateFailure("no emitted JVM artifacts under " + outputRoot);
        }
        String entryClass = findEntryClass(outputRoot);
        // The gate-owned runner invokes the emitted entry point once and
        // surfaces a raised DEAL error through the DEAL_ERROR_CODE
        // convention (the harness runner precedent).
        writeText(outputRoot.resolve("V12FeatureRunner.java"),
            jvmRunner(entryClass));
        sources.add(outputRoot.resolve("V12FeatureRunner.java").toString());
        Path classesDir = outputRoot.resolve("classes");
        List<String> javacArgv = new ArrayList<>();
        javacArgv.add("javac");
        javacArgv.add("-d");
        javacArgv.add(classesDir.toString());
        javacArgv.addAll(sources);
        RunOutcome javacRun = runContained(outputRoot.toString(), javacArgv);
        if (toolRunRejected(javacRun)) {
            throw new GateFailure("javac rejected the emitted artifacts or "
                + "containment failed (exit " + javacRun.exitCode
                + ", failure token '" + javacRun.failureToken + "'): "
                + javacRun.stdout + javacRun.stderr);
        }
        RunOutcome javaRun = runContained(outputRoot.toString(),
            List.of("java", "-cp", classesDir.toString(),
                "V12FeatureRunner"));
        return javaRun;
    }

    private static String jvmRunner(String entryClass) {
        return "// Generated by the ISSUE-0165 feature gate: invokes the emitted\n"
            + "// entry point once and surfaces a raised DEAL error through the\n"
            + "// DEAL_ERROR_CODE convention (harness runner precedent).\n"
            + "public final class V12FeatureRunner {\n"
            + "    public static void main(String[] args) {\n"
            + "        try {\n"
            + "            " + entryClass
            + ".main(new java.lang.String[0]);\n"
            + "        } catch (Throwable e) {\n"
            + "            reportError(e);\n"
            + "        }\n"
            + "        System.out.println(\"V12FEATURE-JVM-OK\");\n"
            + "    }\n"
            + "    private static void reportError(Throwable e) {\n"
            + "        Throwable t = e;\n"
            + "        while (t instanceof ExceptionInInitializerError\n"
            + "                && t.getCause() != null) {\n"
            + "            t = t.getCause();\n"
            + "        }\n"
            + "        String code = null;\n"
            + "        if (\"DealError\".equals(t.getClass().getSimpleName())) {\n"
            + "            try {\n"
            + "                java.lang.reflect.Field f =\n"
            + "                    t.getClass().getDeclaredField(\"code\");\n"
            + "                f.setAccessible(true);\n"
            + "                code = String.valueOf(f.get(t));\n"
            + "            } catch (ReflectiveOperationException ignored) {\n"
            + "            }\n"
            + "        }\n"
            + "        System.out.println(\"DEAL_ERROR_CODE: \"\n"
            + "            + (code != null ? code + \" \" + t.getMessage()\n"
            + "                : (t.getMessage() == null ? t.toString()\n"
            + "                    : t.getMessage())));\n"
            + "        System.exit(1);\n"
            + "    }\n"
            + "}\n";
    }

    private static String findEntryClass(Path outputRoot) {
        try (Stream<Path> walk = Files.walk(outputRoot)) {
            List<Path> candidates = walk
                .filter(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().endsWith(".java"))
                .filter(p -> {
                    try {
                        return Files.readString(p).contains(
                            "public static void main(java.lang.String[] args)");
                    } catch (IOException e) {
                        return false;
                    }
                })
                .sorted()
                .toList();
            if (candidates.size() != 1) {
                throw new GateFailure("expected exactly one emitted entry "
                    + "class, got " + candidates);
            }
            String name = candidates.get(0).getFileName().toString();
            return name.substring(0, name.length() - ".java".length());
        } catch (IOException e) {
            throw new GateFailure("cannot find the emitted entry class: " + e);
        }
    }

    /**
     * The testable async-export backend dispatch: null for LuaJIT (the
     * production {@link LuaJitAsyncExportInvoker} host ABI), a
     * violation message for any other backend — until the JVM
     * async-export host entry lands (owning sibling lane), a JVM
     * async-export leg is a hard gate failure and must never
     * substitute the LuaJIT host ABI.
     */
    static String asyncExportBackendViolation(String backend) {
        if (backend.equals("luajit")) {
            return null;
        }
        return "async-export on backend '" + backend
            + "' has no production host invoker in this revision "
            + "(the JVM async-export host entry is a sibling-lane "
            + "deliverable); refusing to substitute the LuaJIT ABI";
    }

    private RunOutcome executeAsyncExport(
            V12FeatureFixtureCatalog.RecordEntry entry, String backend,
            CompileOutcome outcome) {
        V12FeatureMetadata.Oracle oracle = entry.metadata().oracle();
        String backendViolation = asyncExportBackendViolation(backend);
        if (backendViolation != null) {
            // Fail-closed backend dispatch: the production async-export
            // host ABI exists for LuaJIT in this revision; the JVM
            // async-export host entry is an owning sibling-lane
            // deliverable. A JVM async-export leg must never silently
            // run the LuaJIT host ABI — a backend omission is a hard
            // gate failure (declarations D12 / architecture-owned
            // matrix), never a skip.
            throw new GateFailure(backendViolation);
        }
        Path entryArtifact = outcome.outputRoot.resolve("main.lua");
        String returnDescriptor = oracle.functionDescriptor()
            .substring("async()->".length());
        LuaJitAsyncExportInvoker.Result result =
            new LuaJitAsyncExportInvoker().invoke(new AsyncExportInvocationRequest(
                entryArtifact, oracle.exportName(), returnDescriptor));
        if (!(result instanceof LuaJitAsyncExportInvoker.Result.Value value)) {
            throw new GateFailure("async-export invocation of '" + oracle.exportName()
                + "' did not complete with a value: " + result);
        }
        if (!value.returnDescriptor().equals(returnDescriptor)
                || !value.valueJson().equals("null")) {
            throw new GateFailure("async-export completion mismatch: descriptor '"
                + value.returnDescriptor() + "' value '" + value.valueJson()
                + "' (expected descriptor '" + returnDescriptor
                + "' value 'null')");
        }
        return new RunOutcome(0, "DEAL_ASYNC_EXPORT_RESULT value null", "", "-",
            true);
    }

    // =========================================================================
    // Contained subprocess execution (launcher run mode)
    // =========================================================================

    record RunOutcome(int exitCode, String stdout, String stderr,
                      String failureToken, boolean containmentClean) {
        boolean containmentFailure() {
            return !containmentClean || !failureToken.equals("-");
        }
    }

    /**
     * The shared tool-leg outcome gate: a contained tool invocation
     * fails the record when the target exits nonzero or when
     * containment failed — a clean target exit under a REPORT failure
     * token (survivor/zombie/reap-proof failure) or an unclean
     * launcher exit is a hard gate failure, never a skip (epic
     * constraint: launcher/containment/reaping failure is a hard
     * nonzero result). Every tool leg (javac, gcc, runtime runs)
     * routes through this predicate.
     */
    static boolean toolRunRejected(RunOutcome run) {
        return run.exitCode != 0 || run.containmentFailure();
    }

    private RunOutcome runContained(String cwd, List<String> argv) {
        List<String> full = new ArrayList<>();
        full.add(launcher.toString());
        full.add("run");
        full.add(nonce());
        full.add(cwd);
        full.add("--");
        full.addAll(argv);
        return runDirect(full, cwd);
    }

    private static RunOutcome runDirect(List<String> argv, String cwd) {
        final Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(Path.of(cwd).toFile());
            process = pb.start();
        } catch (IOException e) {
            throw new GateFailure("cannot spawn " + argv.get(0) + ": " + e);
        }
        try {
            byte[] out = process.getInputStream().readAllBytes();
            byte[] err = process.getErrorStream().readAllBytes();
            int exit = process.waitFor();
            String stdout = new String(out, StandardCharsets.UTF_8);
            String stderr = new String(err, StandardCharsets.UTF_8);
            if (argv.contains("run")) {
                return parseContained(exit, stdout, stderr);
            }
            return new RunOutcome(exit, stdout, stderr, "-",
                !stderr.contains("FAILED"));
        } catch (IOException e) {
            throw new GateFailure("cannot drain the spawned process: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GateFailure("interrupted while waiting for "
                + argv.get(0));
        }
    }

    /**
     * Maps the launcher run-mode surface: exit 0 = containment-clean
     * target success, exit 1 = containment-clean target failure, any
     * other exit or a REPORT failure token = containment failure. The
     * final REPORT line (19 fields) carries the target exit code in
     * field 0 and the failure token in field 18.
     */
    private static RunOutcome parseContained(int launcherExit, String stdout,
                                             String stderr) {
        String reportLine = null;
        for (String line : stderr.split("\n")) {
            if (line.startsWith("DEALPG4 REPORT ")) {
                reportLine = line;
            }
        }
        if (reportLine == null) {
            return new RunOutcome(launcherExit, stdout, stderr, "MISSING_REPORT",
                false);
        }
        String[] fields = reportLine.substring("DEALPG4 REPORT ".length())
            .split(" ");
        if (fields.length != 19) {
            return new RunOutcome(launcherExit, stdout, stderr, "MALFORMED_REPORT",
                false);
        }
        int targetExit;
        try {
            targetExit = Integer.parseInt(fields[0]);
        } catch (NumberFormatException e) {
            return new RunOutcome(launcherExit, stdout, stderr,
                "MALFORMED_REPORT", false);
        }
        String token = fields[18];
        boolean clean = launcherExit == 0 || launcherExit == 1;
        return new RunOutcome(targetExit, stdout, stderr, token,
            clean && token.equals("-"));
    }

    private String nonce() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    // =========================================================================
    // Staged production evidence steps (gate-owned wiring)
    // =========================================================================

    /**
     * The E9 production evidence step: compile the committed async-bytes
     * oracle through the production orchestrator and invoke
     * {@code async()->null} once through the production
     * {@link LuaJitAsyncExportInvoker}; a wrong completion, a host
     * failure, or infrastructure failure fails the gate.
     */
    private void asyncExportEvidence() {
        String label = "async-bytes-oracle [luajit, async-export]";
        try {
            Path oracleSource = nativeFixtures.resolve("async-bytes-oracle.deal");
            if (!Files.isRegularFile(oracleSource)) {
                throw new GateFailure("async-bytes oracle fixture missing: "
                    + oracleSource);
            }
            Path project = Files.createTempDirectory("v12feature-async-");
            copyBytes(oracleSource, project.resolve("src/main.deal"));
            writeText(project.resolve("deal.json"), "{\n"
                + "  \"languageVersion\": \"1.2\",\n"
                + "  \"moduleRoots\": [\"src\"],\n"
                + "  \"backend\": \"luajit\",\n"
                + "  \"output\": \"build/lua\"\n"
                + "}\n");
            Path entryFile = project.resolve("src/main.deal")
                .toAbsolutePath().normalize();
            ProjectLocator.LocateResult located =
                ProjectLocator.locate(entryFile.toString(), null);
            if (located.context() == null) {
                throw new GateFailure("async oracle project did not locate: "
                    + (located.e2010() != null ? located.e2010().message()
                        : located.cliDiagnostic().message()));
            }
            var invocation = CompilerProfileProvider.resolveCommonShadow(
                SemanticProfile.DEAL_V1_2_INT32, ReleaseState.PRE_ACTIVATION,
                CapabilityRegistry.releaseRegistry());
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            boolean compiled;
            List<CompilerDiagnostic> asyncDiagnostics;
            try {
                System.setOut(new PrintStream(captured, true,
                    StandardCharsets.UTF_8));
                System.setErr(new PrintStream(captured, true,
                    StandardCharsets.UTF_8));
                CompilationOrchestrator orchestrator =
                    new CompilationOrchestrator(located.context(), entryFile,
                        false, false, false, false, null, invocation);
                try {
                    compiled = orchestrator.compile();
                } catch (IOException e) {
                    throw new GateFailure("async oracle orchestrator I/O: " + e);
                }
                asyncDiagnostics = orchestrator.diagnostics();
            } finally {
                System.out.flush();
                System.err.flush();
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
            if (!compiled) {
                throw new GateFailure("async oracle compile failed: "
                    + asyncDiagnostics);
            }
            Path entryArtifact = Path.of(
                located.context().outputPath().absoluteNormalizedPath())
                .resolve("main.lua");
            LuaJitAsyncExportInvoker.Result result =
                new LuaJitAsyncExportInvoker().invoke(
                    new AsyncExportInvocationRequest(entryArtifact, "oracle",
                        "null"));
            if (!(result instanceof LuaJitAsyncExportInvoker.Result.Value value)
                    || !value.returnDescriptor().equals("null")
                    || !value.valueJson().equals("null")) {
                throw new GateFailure("async oracle completion mismatch: "
                    + result);
            }
            // Wrong-completion proof: a sync export name is a host
            // failure, never a value and never a DEAL error.
            LuaJitAsyncExportInvoker.Result wrong =
                new LuaJitAsyncExportInvoker().invoke(
                    new AsyncExportInvocationRequest(entryArtifact, "main",
                        "null"));
            if (!(wrong instanceof LuaJitAsyncExportInvoker.Result.HostFailure)) {
                throw new GateFailure("expected HostFailure for the sync "
                    + "export 'main', got " + wrong);
            }
            record(label, true);
        } catch (GateFailure failure) {
            record(label, false);
            System.err.println("  FAIL " + label + ": " + failure.getMessage());
        } catch (IOException e) {
            record(label, false);
            System.err.println("  FAIL " + label + ": " + e);
        }
    }

    // =========================================================================
    // C FFI record execution (production generated-bindings wiring, E11)
    // =========================================================================

    /**
     * The C_FFI record execution: after the production compile the gate
     * wires the production-generated bindings through the production
     * {@code __rt.load_ffi} pipeline — the generated-import emission
     * shape ({@code load_ffi(moduleKey, cdefBundle, plans, bindings,
     * file, line, column)}) — and asserts the pinned real-native rows:
     * READY publication, UNBOUND-at-compile cells, zero import-time
     * evaluation, scalar/string/bytes/struct/pointer ABI rows, the six
     * FFI error codes, by-value struct isolation, ordinal C-keyword
     * fields, callable default plans, exact ready replay, changed-
     * identity/cdef failure caching, cross-module cdef replay, and
     * handle-scoped same-symbol/different-signature dual libraries. A
     * runtime-error expectation (the valid-but-unloadable row) asserts
     * the cached {@code FFI_LIBRARY_LOAD} re-raise instead.
     */
    private boolean executeCffiRecord(
            V12FeatureFixtureCatalog.RecordEntry entry, CompileOutcome outcome,
            String label) {
        if (outcome.locateE2010 != null || !outcome.success) {
            throw new GateFailure("C FFI record must compile before runtime "
                + "execution: "
                + (outcome.locateE2010 != null
                    ? outcome.locateE2010.message() : outcome.diagnostics));
        }
        V12FeatureMetadata.NativeBlock nativePlan = entry.metadata().nativePlan();
        List<FfiGeneratedModule> modules = new ArrayList<>();
        for (V12FeatureMetadata.NativeEntry nativeEntry : nativePlan.entries()) {
            String modulePath =
                nativeEntry.importSpecifier().replace('/', '.');
            FfiGeneratedModule module =
                outcome.ffiGenerations().get(modulePath);
            if (module == null) {
                throw new GateFailure("no production FFI generation for '"
                    + nativeEntry.importSpecifier() + "' (module path '"
                    + modulePath + "'); compiled modules: "
                    + outcome.ffiGenerations().keySet());
            }
            if (module.bindings().state() != FfiBindingState.UNBOUND) {
                throw new GateFailure("the compile must publish UNBOUND "
                    + "forward cells for '" + nativeEntry.importSpecifier()
                    + "', got " + module.bindings().state());
            }
            modules.add(module);
        }
        if (outcome.ffiGenerations().size() != nativePlan.entries().size()) {
            throw new GateFailure("unexpected FFI generation count "
                + outcome.ffiGenerations().size() + " for record '"
                + entry.id() + "' (expected " + nativePlan.entries().size()
                + ")");
        }
        if (entry.metadata().expected()
                instanceof V12FeatureMetadata.RuntimeOk) {
            for (int i = 0; i < modules.size(); i++) {
                String violation = pinnedCffiSurfaceViolation(
                    nativePlan.entries().get(i), modules.get(i));
                if (violation != null) {
                    throw new GateFailure(violation);
                }
            }
        }
        String driver = cffiDriver(entry, nativePlan, modules);
        Path driverPath = repoRoot.resolve("build")
            .resolve("v12_cffi_driver_" + nonce().substring(0, 8) + ".lua");
        writeText(driverPath, driver);
        List<String> argv = new ArrayList<>();
        argv.add("luajit");
        argv.add(driverPath.toString());
        boolean runtimeExpectation = entry.metadata().expected()
                instanceof V12FeatureMetadata.RuntimeOk
            || entry.metadata().expected()
                instanceof V12FeatureMetadata.RuntimeError;
        for (V12FeatureMetadata.NativeEntry nativeEntry : nativePlan.entries()) {
            if (nativeEntry.library().endsWith(".c")
                    && runtimeExpectation) {
                Path events = nativeEvents.get(nativeEntry.library());
                if (events == null) {
                    throw new GateFailure("no event file recorded for the "
                        + "gate-compiled fixture "
                        + nativeEntry.library());
                }
                argv.add(events.toString());
            }
        }
        RunOutcome run = runContained(repoRoot.toString(), argv);
        String errorCode = entry.metadata().expected()
                instanceof V12FeatureMetadata.RuntimeError error
            ? error.code() : null;
        boolean ok = checkRunOutcome(entry, errorCode, label, run);
        if (errorCode == null && !run.stdout.contains(CFFI_OK_MARKER)) {
            throw new GateFailure("the C FFI driver produced no "
                + CFFI_OK_MARKER + " marker: " + run.stdout + run.stderr);
        }
        return ok;
    }

    /** The pinned C FFI surfaces of the runtime records: every declared
     *  function and class name of each import specifier must match the
     *  pinned battery exactly — an omitted, renamed, or added row is a
     *  hard gate failure (no silent coverage drift). */
    private static final java.util.Map<String, java.util.Set<String>>
        PINNED_CFFI_FUNCTIONS = java.util.Map.of(
            "native/math", java.util.Set.of(
                "fixture_count_call", "fixture_call_count",
                "fixture_reset_counter", "fixture_add_int",
                "fixture_add_number", "fixture_not",
                "fixture_echo_string", "fixture_bytes_sum",
                "fixture_null_string", "fixture_bad_utf8",
                "fixture_null_pointer", "fixture_static_pointer",
                "fixture_identity_int", "fixture_extreme_int",
                "fixture_number_special", "fixture_nonzero_bool",
                "fixture_zero_bool", "fixture_long_string",
                "fixture_echo_long", "fixture_bytes_sum2",
                "fixture_make_pair", "fixture_echo_pair",
                "fixture_bump_pair", "fixture_sum_pair",
                "fixture_make_kw", "fixture_make_ptr_box",
                "fixture_make_null_ptr_box", "fixture_ptrbox_nonnull"),
            "native/dualA", java.util.Set.of("fixture_dual_combine"),
            "native/dualB", java.util.Set.of("fixture_dual_combine"));

    private static final java.util.Map<String, java.util.Set<String>>
        PINNED_CFFI_CLASSES = java.util.Map.of(
            "native/math", java.util.Set.of("Handle", "Pair", "Kw",
                "PtrBox"),
            "native/dualA", java.util.Set.of(),
            "native/dualB", java.util.Set.of());

    private static String pinnedCffiSurfaceViolation(
            V12FeatureMetadata.NativeEntry nativeEntry,
            FfiGeneratedModule module) {
        java.util.Set<String> expectedFunctions =
            PINNED_CFFI_FUNCTIONS.get(nativeEntry.importSpecifier());
        if (expectedFunctions == null) {
            return "no pinned C FFI surface for import specifier '"
                + nativeEntry.importSpecifier() + "'";
        }
        java.util.Set<String> declaredFunctions = new java.util.LinkedHashSet<>();
        for (FfiFunctionDescriptor fn
                : module.descriptor().functions()) {
            declaredFunctions.add(fn.dealName());
        }
        if (!declaredFunctions.equals(expectedFunctions)) {
            return "the declared function surface of '"
                + nativeEntry.importSpecifier() + "' is " + declaredFunctions
                + ", the pinned battery requires exactly " + expectedFunctions;
        }
        java.util.Set<String> declaredClasses = new java.util.LinkedHashSet<>();
        for (FfiClassDescriptor cls : module.descriptor().classes()) {
            declaredClasses.add(cls.name());
        }
        java.util.Set<String> expectedClasses =
            PINNED_CFFI_CLASSES.get(nativeEntry.importSpecifier());
        if (expectedClasses == null
                || !declaredClasses.equals(expectedClasses)) {
            return "the declared class surface of '"
                + nativeEntry.importSpecifier() + "' is " + declaredClasses
                + ", the pinned battery requires exactly " + expectedClasses;
        }
        return null;
    }

    /**
     * The gate-owned generated-bindings driver: serializes every
     * production {@link FfiGeneratedModule} verbatim into the
     * generated-import emission shape, loads each through the production
     * {@code __rt.load_ffi}, and runs the pinned assertion battery of
     * the record's native plan (runtime-ok) or the cached
     * {@code FFI_LIBRARY_LOAD} row (runtime-error).
     */
    private String cffiDriver(V12FeatureFixtureCatalog.RecordEntry entry,
                              V12FeatureMetadata.NativeBlock nativePlan,
                              List<FfiGeneratedModule> modules) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- Generated by the ISSUE-0165 feature gate (E13): every\n")
            .append("-- bundle/plan/binding below is serialized verbatim from the\n")
            .append("-- production LuaFfiBindingGenerator output of the record's\n")
            .append("-- production compile and loads through the production\n")
            .append("-- __rt.load_ffi pipeline (cdef certainty registry,\n")
            .append("-- handle-scoped resolver, ABI converters, wrapper/readiness\n")
            .append("-- runtime). Every assertion is a hard Lua error.\n")
            .append("local __rt = require(\"deal.runtime\")\n")
            .append("local F = ").append(luaString(CFFI_IMPORT_FILE)).append("\n")
            .append("local FL = ").append(CFFI_IMPORT_LINE).append("\n")
            .append("local FC = ").append(CFFI_IMPORT_COLUMN).append("\n")
            .append("local CF = ").append(luaString(CFFI_CALL_FILE)).append("\n")
            .append("local CL = ").append(CFFI_CALL_LINE).append("\n")
            .append("local CC = ").append(CFFI_CALL_COLUMN).append("\n")
            .append("local function v12_mk_bytes(list)\n")
            .append("  local b = __rt.bytes_new(#list, CF, CL, CC)\n")
            .append("  for i = 1, #list do\n")
            .append("    __rt.bytes_set(b, i - 1, list[i], CF, CL, CC)\n")
            .append("  end\n")
            .append("  return b\n")
            .append("end\n")
            .append("local function v12_count_lines(path)\n")
            .append("  local f = io.open(path, \"r\")\n")
            .append("  if not f then return 0, 0 end\n")
            .append("  local text = f:read(\"*a\") or \"\"\n")
            .append("  f:close()\n")
            .append("  local opens, closes = 0, 0\n")
            .append("  for line in text:gmatch(\"[^\\n]+\") do\n")
            .append("    if line == \"open\" then opens = opens + 1 end\n")
            .append("    if line == \"close\" then closes = closes + 1 end\n")
            .append("  end\n")
            .append("  return opens, closes\n")
            .append("end\n\n");
        boolean runtimeError = entry.metadata().expected()
                instanceof V12FeatureMetadata.RuntimeError;
        for (int i = 0; i < modules.size(); i++) {
            serializeModule(sb, i + 1, nativePlan.entries().get(i), modules.get(i),
                runtimeError);
        }
        if (runtimeError) {
            // The valid-but-unloadable row: the first load raises the
            // cached FFI_LIBRARY_LOAD; the exact same load re-raises the
            // cached error value (transition 4); the fresh bindings are
            // marked FAILED by the failure envelope.
            sb.append("-- ===== unloadable row: cached FFI_LIBRARY_LOAD =====\n")
                .append("local ok_, e_ = pcall(function()\n")
                .append("  return __rt.load_ffi(M1_KEY, M1_BUNDLE, M1_PLANS,")
                .append(" M1_B1, F, FL, FC)\n")
                .append("end)\n")
                .append("assert(not ok_, \"an unloadable library must fail ")
                .append("the load\")\n")
                .append("assert(type(e_) == \"table\" and e_.code == ")
                .append("\"FFI_LIBRARY_LOAD\", \"expected FFI_LIBRARY_LOAD, ")
                .append("got \" .. tostring(e_))\n")
                .append("assert(M1_B1.state == \"FAILED\", \"the failure ")
                .append("envelope must mark the bindings FAILED\")\n")
                .append("local M1_B2 = M1_BINDINGS()\n")
                .append("local ok2_, e2_ = pcall(function()\n")
                .append("  return __rt.load_ffi(M1_KEY, M1_BUNDLE, M1_PLANS,")
                .append(" M1_B2, F, FL, FC)\n")
                .append("end)\n")
                .append("assert(not ok2_ and type(e2_) == \"table\" and ")
                .append("e2_.code == \"FFI_LIBRARY_LOAD\", \"the exact failed ")
                .append("replay must re-raise FFI_LIBRARY_LOAD\")\n")
                .append("assert(e2_ == e_, \"the exact failed replay must ")
                .append("re-raise the cached error value itself\")\n")
                .append("assert(M1_B2.state == \"FAILED\", \"the failed replay ")
                .append("must mark the fresh bindings FAILED\")\n")
                .append("print(\"DEAL_ERROR_CODE: FFI_LIBRARY_LOAD \" ")
                .append(".. tostring(e_.message))\n")
                .append("os.exit(1)\n");
            return sb.toString();
        }
        for (int i = 0; i < modules.size(); i++) {
            battery(sb, i + 1, nativePlan.entries().get(i).importSpecifier(),
                modules.get(i));
        }
        sb.append("print(\"").append(CFFI_OK_MARKER).append("\")\n");
        return sb.toString();
    }

    // =========================================================================
    // Generated-module serialization (the ISSUE-0425 emission shape)
    // =========================================================================

    private void serializeModule(StringBuilder sb, int index,
                                 V12FeatureMetadata.NativeEntry nativeEntry,
                                 FfiGeneratedModule module,
                                 boolean expectLoadFailure) {
        String prefix = "M" + index;
        sb.append("-- ===== module ").append(nativeEntry.importSpecifier())
            .append(" (production-generated bindings) =====\n");
        sb.append("local ").append(prefix).append("_KEY = ")
            .append(luaString(module.descriptor().moduleKey())).append("\n");
        FfiCdefBundle bundle = module.cdefBundle();
        sb.append("local ").append(prefix).append("_BUNDLE = {\n")
            .append("  bundleDigest = ")
            .append(luaString(bundle.bundleDigest())).append(",\n")
            .append("  identityDigest = ")
            .append(luaString(bundle.identityDigest())).append(",\n")
            .append("  fullContent = ")
            .append(luaString(bundle.fullContent())).append(",\n")
            .append("  nativeLibrary = { kind = ")
            .append(luaString(bundle.nativeLibraryKind()))
            .append(", loaderText = ")
            .append(luaString(bundle.nativeLibraryLoaderText())).append(" },\n")
            .append("  entries = {\n");
        for (FfiCdefEntry entry : bundle.entries()) {
            sb.append("    { entryDigest = ")
                .append(luaString(entry.entryDigest()))
                .append(", fullText = ").append(luaString(entry.fullText()))
                .append(", ownedNames = {");
            boolean firstName = true;
            for (String name : entry.ownedNames()) {
                if (!firstName) {
                    sb.append(", ");
                }
                firstName = false;
                sb.append(luaString(name));
            }
            sb.append("} },\n");
        }
        sb.append("  },\n  functions = {\n");
        for (FfiFunctionDescriptor fn : bundle.functions()) {
            sb.append("    { dealName = ").append(luaString(fn.dealName()))
                .append(", cSymbol = ").append(luaString(fn.cSymbol()))
                .append(", privateFunctionPointerType = ")
                .append(luaString(fn.privateFunctionPointerType()))
                .append(", orderedParams = {");
            boolean firstParam = true;
            for (FfiType param : fn.orderedParams()) {
                if (!firstParam) {
                    sb.append(", ");
                }
                firstParam = false;
                sb.append(typeTable(param));
            }
            sb.append("}, returnType = ")
                .append(typeTable(fn.returnType())).append(" },\n");
        }
        sb.append("  },\n  classes = {\n");
        for (FfiClassDescriptor cls : bundle.classes()) {
            sb.append("    { name = ").append(luaString(cls.name()))
                .append(", canonicalClassIdentity = ")
                .append(luaString(cls.canonicalClassIdentity()))
                .append(", qualifiedDealDescriptor = ")
                .append(luaString(cls.qualifiedDealDescriptor()))
                .append(", kind = ").append(luaString(cls.kind().name()))
                .append(", orderedFields = {");
            boolean firstField = true;
            for (FfiFieldDescriptor field : cls.orderedFields()) {
                if (!firstField) {
                    sb.append(", ");
                }
                firstField = false;
                sb.append("{ dealName = ")
                    .append(luaString(field.dealName()))
                    .append(", fieldOrdinal = ")
                    .append(field.fieldOrdinal())
                    .append(", type = ").append(typeTable(field.type()))
                    .append(" }");
            }
            sb.append("} },\n");
        }
        sb.append("  },\n}\n");
        // Forward bindings: fresh UNBOUND cells per export name.
        sb.append("local function ").append(prefix).append("_BINDINGS()\n")
            .append("  return { moduleKey = ").append(prefix)
            .append("_KEY, state = \"UNBOUND\", cells = {\n");
        List<String> cellNames = new ArrayList<>(
            module.bindings().cells().keySet());
        java.util.Collections.sort(cellNames);
        for (String name : cellNames) {
            sb.append("    ").append(luaKey(name))
                .append(" = { state = \"UNBOUND\", wrapper = nil, ")
                .append("errorValue = nil },\n");
        }
        sb.append("  } }\nend\n");
        sb.append("local ").append(prefix).append("_B1 = ").append(prefix)
            .append("_BINDINGS()\n");
        // Plan records: one per C_STRUCT class; literal default
        // evaluators lower to gate-synthesized closures (the production
        // content strings participate verbatim in load identity). The
        // plans follow the bindings so a synthesized default closure can
        // dereference the module's cells at invocation time (the
        // adopted D6/D7 cell semantics).
        sb.append("local ").append(prefix).append("_PLANS = {\n");
        for (FfiClassDescriptor cls : bundle.classes()) {
            FfiCompilerClassDefaultPlan plan = module.plans().get(
                cls.canonicalClassIdentity());
            if (plan == null) {
                continue;
            }
            sb.append("  [").append(luaString(cls.canonicalClassIdentity()))
                .append("] = {\n    plan = {\n");
            for (FfiCompilerClassDefaultPlan.Entry planEntry
                    : plan.entries()) {
                sb.append("      { name = ")
                    .append(luaString(planEntry.name()))
                    .append(", descriptor = ")
                    .append(luaString(planEntry.canonicalDescriptor()))
                    .append(", optional = ")
                    .append(planEntry.optional() ? "true" : "false");
                if (planEntry.hasDefaultEvaluator()) {
                    sb.append(", evaluator = ")
                        .append(evaluatorClosure(prefix, module, planEntry,
                            cls.name()))
                        .append(" },\n");
                } else {
                    sb.append(" },\n");
                }
            }
            sb.append("    },\n")
                .append("    canonicalPlanContent = ")
                .append(luaString(plan.canonicalPlanContent())).append(",\n")
                .append("    semanticDefaultContents = ")
                .append(luaString(plan.semanticDefaultContents()))
                .append(",\n")
                .append("    evaluatorImplementationContents = ")
                .append(luaString(plan.evaluatorImplementationContents()))
                .append(",\n  },\n");
        }
        sb.append("}\n");
        if (expectLoadFailure) {
            return;
        }
        sb.append("local ").append(prefix).append("_EXPORTS = ")
            .append("__rt.load_ffi(").append(prefix).append("_KEY, ")
            .append(prefix).append("_BUNDLE, ").append(prefix)
            .append("_PLANS, ").append(prefix).append("_B1, F, FL, FC)\n")
            .append("assert(type(").append(prefix)
            .append("_EXPORTS) == \"table\", \"load_ffi must publish an ")
            .append("exports table for ").append(nativeEntry.importSpecifier())
            .append("\")\n")
            .append("assert(").append(prefix)
            .append("_B1.state == \"READY\", \"bindings must end READY ")
            .append("for ").append(nativeEntry.importSpecifier()).append("\")\n");
        for (String name : cellNames) {
            sb.append("assert(").append(prefix).append("_B1.cells[")
                .append(luaString(name)).append("].state == \"READY\" and ")
                .append(prefix).append("_B1.cells[")
                .append(luaString(name)).append("].wrapper == ")
                .append(prefix).append("_EXPORTS[")
                .append(luaString(name)).append("], \"cell ").append(name)
                .append(" must carry the published wrapper\")\n");
        }
        // Class-identity locals for the battery's construction rows.
        for (FfiClassDescriptor cls : bundle.classes()) {
            sb.append("local ").append(prefix).append("_ID_")
                .append(luaSafe(cls.name())).append(" = ")
                .append(luaString(cls.canonicalClassIdentity())).append("\n");
            sb.append("local ").append(prefix).append("_PLAN_")
                .append(luaSafe(cls.name())).append(" = ").append(prefix)
                .append("_PLANS[").append(luaString(cls.canonicalClassIdentity()))
                .append("] and ").append(prefix).append("_PLANS[")
                .append(luaString(cls.canonicalClassIdentity()))
                .append("].plan or nil\n");
        }
    }

    private static String typeTable(FfiType type) {
        StringBuilder sb = new StringBuilder("{ kind = ")
            .append(luaString(type.kind().name()))
            .append(", canonicalDescriptor = ")
            .append(luaString(type.canonicalDescriptor()));
        if (type.canonicalClassIdentity() != null) {
            sb.append(", canonicalClassIdentity = ")
                .append(luaString(type.canonicalClassIdentity()));
        }
        return sb.append(" }").toString();
    }

    /**
     * The gate-synthesized default evaluator closure. Two closed
     * canonical shapes lower to faithful Lua closures: a literal
     * default ({@code {"k":"literal","value":V}}), and a zero-argument
     * call to a same-module declared function
     * ({@code {"k":"call","args":[],"callee":{"k":"identifier",
     * "name":N}}} with {@code N} a declared binding cell) — the
     * adopted D6/D7 semantics: the closure dereferences the current
     * cell at invocation time and raises the cached
     * {@code FFI_LIBRARY_LOAD} before readiness. Any other evaluator
     * content is a hard gate failure (never a silently dropped
     * default).
     */
    private static String evaluatorClosure(String prefix,
                                           FfiGeneratedModule module,
                                           FfiCompilerClassDefaultPlan.Entry
                                               planEntry,
                                           String className) {
        final CanonicalJson.Value parsed;
        try {
            parsed = CanonicalJson.parse(planEntry.evaluatorContent());
        } catch (RuntimeException e) {
            throw new GateFailure("default evaluator of field '"
                + planEntry.name() + "' of class '" + className
                + "' carries unparseable canonical content: " + e);
        }
        if (!(parsed instanceof CanonicalJson.Obj obj)) {
            throw new GateFailure("default evaluator of field '"
                + planEntry.name() + "' of class '" + className
                + "' is not a canonical object");
        }
        String kind = null;
        CanonicalJson.Value value = null;
        for (CanonicalJson.Entry member : obj.entries()) {
            if (member.key().equals("k")) {
                kind = member.value() instanceof CanonicalJson.Str s
                    ? s.value() : null;
            } else if (member.key().equals("value")) {
                value = member.value();
            }
        }
        if (kind == null) {
            throw new GateFailure("default evaluator of field '"
                + planEntry.name() + "' of class '" + className
                + "' carries no canonical kind");
        }
        if (kind.equals("literal") && value != null) {
            return "function() return " + luaLiteral(value) + " end";
        }
        if (kind.equals("call")) {
            String callee = zeroArgIdentifierCallee(obj);
            if (callee != null && module.bindings().cells()
                    .containsKey(callee)) {
                return "function()\n"
                    + "  local cell = " + prefix + "_B1.cells["
                    + luaString(callee) + "]\n"
                    + "  if cell.state ~= \"READY\" then\n"
                    + "    error(__rt._err(\"FFI_LIBRARY_LOAD\","
                    + " \"default evaluator of '" + className + "."
                    + planEntry.name() + "' invoked before module "
                    + "readiness\", CF, CL, CC, nil, nil))\n"
                    + "  end\n"
                    + "  return cell.wrapper.f(CF, CL, CC)\n"
                    + "end";
            }
        }
        throw new GateFailure("default evaluator of field '"
            + planEntry.name() + "' of class '" + className
            + "' is not a synthesizable shape (literal or zero-argument "
            + "same-module call): " + planEntry.evaluatorContent());
    }

    /**
     * The callee name of a canonical zero-argument same-module call:
     * {@code {"k":"call","args":[],"callee":{"k":"identifier",
     * "name":N}}} — null for any other call shape.
     */
    private static String zeroArgIdentifierCallee(CanonicalJson.Obj obj) {
        CanonicalJson.Value args = null;
        CanonicalJson.Value callee = null;
        for (CanonicalJson.Entry member : obj.entries()) {
            if (member.key().equals("args")) {
                args = member.value();
            } else if (member.key().equals("callee")) {
                callee = member.value();
            }
        }
        if (!(args instanceof CanonicalJson.Arr arr) || !arr.items().isEmpty()
                || !(callee instanceof CanonicalJson.Obj calleeObj)) {
            return null;
        }
        String calleeKind = null;
        String name = null;
        for (CanonicalJson.Entry member : calleeObj.entries()) {
            if (member.key().equals("k")) {
                calleeKind = member.value() instanceof CanonicalJson.Str s
                    ? s.value() : null;
            } else if (member.key().equals("name")) {
                name = member.value() instanceof CanonicalJson.Str s
                    ? s.value() : null;
            }
        }
        if (!"identifier".equals(calleeKind) || name == null
                || name.isEmpty()) {
            return null;
        }
        return name;
    }

    private static String luaLiteral(CanonicalJson.Value value) {
        return switch (value) {
            case CanonicalJson.Int i -> String.valueOf(i.value());
            case CanonicalJson.Num n -> {
                double d = n.value();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new GateFailure("non-finite literal default");
                }
                yield Double.toString(d);
            }
            case CanonicalJson.Bool b -> b.value() ? "true" : "false";
            case CanonicalJson.Str s -> luaString(s.value());
            default -> throw new GateFailure(
                "unsupported literal default value: " + value);
        };
    }

    // =========================================================================
    // The pinned real-native assertion battery (gate-owned test wiring)
    // =========================================================================

    private void battery(StringBuilder sb, int index, String importSpecifier,
                         FfiGeneratedModule module) {
        switch (importSpecifier) {
            case "native/math" -> mathBattery(sb, index, module);
            case "native/dualA" -> dualBattery(sb, index, module, false);
            case "native/dualB" -> dualBattery(sb, index, module, true);
            default -> throw new GateFailure("no pinned assertion battery "
                + "for C FFI import specifier '" + importSpecifier + "'");
        }
    }

    private void mathBattery(StringBuilder sb, int index,
                             FfiGeneratedModule module) {
        String m = "M" + index;
        String exports = m + "_EXPORTS";
        String events = "arg[" + index + "]";
        // Zero import-time evaluation: no fixture call ran during load.
        valueCase(sb, exports, "fixture_call_count", List.of(), "0",
            "zero import-time evaluation: no fixture call ran during load");
        valueCase(sb, exports, "fixture_count_call", List.of(), "__rt.__NULL",
            "void return maps to the null sentinel");
        valueCase(sb, exports, "fixture_call_count", List.of(), "1",
            "the count call ran exactly once per wrapper invocation");
        // Single dlopen + handle retention through the first loads.
        sb.append("local o_, c_ = v12_count_lines(").append(events)
            .append(")\n")
            .append("assert(o_ == 1 and c_ == 0, \"exactly one dlopen and ")
            .append("no close while the ready module retains its handle, ")
            .append("got \" .. o_ .. \"/\" .. c_)\n");
        // Scalar/string/bytes ABI rows.
        valueCase(sb, exports, "fixture_add_int", List.of("20", "22"), "42",
            "int32 add through the private cast");
        valueCase(sb, exports, "fixture_add_number", List.of("1.5", "2.5"), "4.0",
            "double add through the private cast");
        valueCase(sb, exports, "fixture_not", List.of("false"), "true",
            "boolean zero -> 1 -> nonzero -> true");
        valueCase(sb, exports, "fixture_not", List.of("true"), "false",
            "boolean nonzero -> 0 -> false");
        valueCase(sb, exports, "fixture_echo_string",
            List.of(luaString("v12-ffi-probe")), luaString("v12-ffi-probe"),
            "NUL-terminated string roundtrip (C-owned copy)");
        valueCase(sb, exports, "fixture_bytes_sum",
            List.of("v12_mk_bytes({1, 2, 3})"), "6",
            "borrowed bytes pointer/length adjacency");
        valueCase(sb, exports, "fixture_bytes_sum2",
            List.of("v12_mk_bytes({1, 2})", "v12_mk_bytes({3, 4})"), "10",
            "two bytes parameters at distinct source positions");
        // Error rows: the six FFI codes observable through the fixture.
        errorCase(sb, exports, "fixture_null_string", List.of(), "FFI_NULL_STRING",
            "NULL C string return");
        errorCase(sb, exports, "fixture_bad_utf8", List.of(), "FFI_INVALID_UTF8",
            "malformed UTF-8 string return");
        errorCase(sb, exports, "fixture_null_pointer", List.of(),
            "FFI_NULL_POINTER", "NULL pointer return");
        // Int32 extremes and totals.
        valueCase(sb, exports, "fixture_identity_int", List.of("123"), "123",
            "int32 sign-preserving roundtrip");
        valueCase(sb, exports, "fixture_identity_int", List.of("-123"), "-123",
            "int32 sign-preserving roundtrip (negative)");
        valueCase(sb, exports, "fixture_extreme_int", List.of("0"), "-2147483648",
            "INT32_MIN inbound total");
        valueCase(sb, exports, "fixture_extreme_int", List.of("1"), "2147483647",
            "INT32_MAX inbound total");
        valueCase(sb, exports, "fixture_extreme_int", List.of("7"), "-1",
            "other inbound rows");
        // IEEE totals.
        nanCase(sb, exports, "fixture_number_special", List.of("0"), "NaN");
        infCase(sb, exports, "fixture_number_special", List.of("1"), true, "+Inf");
        infCase(sb, exports, "fixture_number_special", List.of("2"), false, "-Inf");
        valueCase(sb, exports, "fixture_number_special", List.of("3"), "0.0", "+0.0");
        negZeroCase(sb, exports, "fixture_number_special", List.of("4"), "-0.0");
        // Boolean totals.
        valueCase(sb, exports, "fixture_nonzero_bool", List.of(), "true",
            "nonzero int return -> true");
        valueCase(sb, exports, "fixture_zero_bool", List.of(), "false",
            "zero int return -> false");
        // Uncapped first-NUL strings.
        lengthCase(sb, exports, "fixture_long_string", List.of(), "8191",
            "valid long string return has no DEAL cap");
        valueCase(sb, exports, "fixture_echo_long",
            List.of("string.rep(\"x\", 4096)"),
            "string.rep(\"x\", 4096)",
            "long string parameter roundtrip");
        // By-value struct rows + ordinal C-keyword fields.
        classCase(sb, exports, "fixture_make_pair", List.of("3", "2.5"),
            m + "_ID_Pair",
            java.util.Arrays.<String[]>asList(new String[] {"x", "3"}, new String[] {"y", "2.5"}),
            "by-value struct return under source-order deal_fN");
        classCase(sb, exports, "fixture_echo_pair",
            List.of(pairExpr(m, "{x=1, y=2.5}")),
            m + "_ID_Pair",
            java.util.Arrays.<String[]>asList(new String[] {"x", "1"}, new String[] {"y", "2.5"}),
            "by-value struct parameter copy roundtrip");
        sb.append("-- by-value isolation: bump_pair mutates only the C copy\n")
            .append("local p1_ = ").append(pairExpr(m, "{x=1, y=2.5}"))
            .append("\n")
            .append("local ok_, v_ = pcall(function()\n")
            .append("  return ").append(m)
            .append("_EXPORTS[\"fixture_bump_pair\"].f(p1_, CF, CL, CC)\n")
            .append("end)\n")
            .append("assert(ok_, \"fixture_bump_pair failed: \" ")
            .append(".. tostring(v_))\n")
            .append("assert(type(v_) == \"table\" and v_.__classname == ")
            .append(m).append("_ID_Pair, \"bump_pair class tag\")\n")
            .append("assert(v_.x == 2 and v_.y == 3.5, \"bump_pair result\")\n")
            .append("assert(p1_.x == 1 and p1_.y == 2.5, \"by-value ")
            .append("isolation: the DEAL instance is untouched\")\n");
        valueCase(sb, exports, "fixture_sum_pair",
            List.of(pairExpr(m, "{x=2, y=1.5}")), "3",
            "struct parameter consumed by value");
        classCase(sb, exports, "fixture_make_kw", List.of("4", "5", "6"),
            m + "_ID_Kw",
            java.util.Arrays.<String[]>asList(new String[] {"register", "4"},
                new String[] {"unsigned", "5"},
                new String[] {"volatile", "6"}),
            "C-keyword DEAL field names stay behind deal_fN ordinals");
        // Pointer rows.
        sb.append("-- non-null pointer token capture\n")
            .append("local ok_, vh_ = pcall(function()\n")
            .append("  return ").append(m)
            .append("_EXPORTS[\"fixture_static_pointer\"].f(CF, CL, CC)\n")
            .append("end)\n")
            .append("assert(ok_, \"fixture_static_pointer failed: \" ")
            .append(".. tostring(vh_))\n")
            .append("assert(type(vh_) == \"table\" and vh_.__kind == \"class\" ")
            .append("and vh_.__classname == ").append(m)
            .append("_ID_Handle and vh_.__ptr ~= nil, \"pointer token ")
            .append("identity/NULL\")\n")
            .append("local vh_ = vh_\n");
        classCase(sb, exports, "fixture_make_ptr_box",
            List.<String>of("vh_"), m + "_ID_PtrBox",
            java.util.Collections.singletonList(new String[] {"p", "NONNULL_TOKEN"}),
            "pointer field roundtrip through a by-value box");
        sb.append("-- inbound NULL pointer field: native effects remain\n")
            .append("local okr_, rr_ = pcall(function()\n")
            .append("  return ").append(m)
            .append("_EXPORTS[\"fixture_reset_counter\"].f(CF, CL, CC)\n")
            .append("end)\n")
            .append("assert(okr_, \"fixture_reset_counter failed: \" ")
            .append(".. tostring(rr_))\n");
        errorCase(sb, exports, "fixture_make_null_ptr_box", List.of(),
            "FFI_NULL_POINTER", "NULL pointer field return");
        valueCase(sb, exports, "fixture_call_count", List.of(), "1",
            "the native call ran before the inbound conversion failed");
        valueCase(sb, exports, "fixture_ptrbox_nonnull",
            List.of(boxExpr(m, "vh_")), "true",
            "non-null pointer field parameter");
        // Callable default plans: omitted fields run the synthesized
        // literal evaluators exactly once per attempt; fresh instances.
        sb.append("-- callable defaults: plan-built instances with omitted ")
            .append("fields\n")
            .append("local ok_, d1_ = pcall(function()\n")
            .append("  return __rt.class_plan_(").append(m)
            .append("_ID_Pair, ").append(m)
            .append("_PLAN_Pair, {}, CF, CL, CC)\n")
            .append("end)\n")
            .append("assert(ok_, \"default construction failed: \" ")
            .append(".. tostring(d1_))\n")
            .append("assert(d1_.x == 0 and d1_.y == 0.0, \"literal defaults ")
            .append("x=0 y=0.0\")\n")
            .append("local ok_, d2_ = pcall(function()\n")
            .append("  return __rt.class_plan_(").append(m)
            .append("_ID_Pair, ").append(m)
            .append("_PLAN_Pair, {}, CF, CL, CC)\n")
            .append("end)\n")
            .append("assert(ok_ and d2_.x == 0 and d2_.y == 0.0, \"second ")
            .append("attempt re-runs the evaluators\")\n")
            .append("assert(d1_ ~= d2_, \"each attempt publishes a fresh ")
            .append("instance\")\n");
        // Pointer callable default: the omitted pointer field runs the
        // same-module cell exactly once (the adopted D6/D7 semantics).
        sb.append("-- pointer callable default: the omitted field runs the ")
            .append("same-module cell once\n")
            .append("local okr2_, rr2_ = pcall(function()\n")
            .append("  return ").append(m)
            .append("_EXPORTS[\"fixture_reset_counter\"].f(CF, CL, CC)\n")
            .append("end)\n")
            .append("assert(okr2_, \"fixture_reset_counter failed: \" ")
            .append(".. tostring(rr2_))\n")
            .append("local ok_, db_ = pcall(function()\n")
            .append("  return __rt.class_plan_(").append(m)
            .append("_ID_PtrBox, ").append(m)
            .append("_PLAN_PtrBox, {}, CF, CL, CC)\n")
            .append("end)\n")
            .append("assert(ok_, \"pointer default construction failed: \" ")
            .append(".. tostring(db_))\n")
            .append("assert(type(db_.p) == \"table\" and db_.p.__ptr ~= nil ")
            .append("and db_.p.__classname == ").append(m)
            .append("_ID_Handle, \"the pointer default must publish a ")
            .append("non-null declared token\")\n");
        valueCase(sb, exports, "fixture_call_count", List.of(), "1",
            "the pointer default invoked the native provider exactly once");
        // Exact ready replay: identical content, fresh UNBOUND bindings.
        sb.append("-- ===== exact ready replay =====\n")
            .append("local r1_ = ").append(m).append("_BINDINGS()\n")
            .append("local r2_ = __rt.load_ffi(").append(m)
            .append("_KEY, ").append(m).append("_BUNDLE, ").append(m)
            .append("_PLANS, r1_, F, FL, FC)\n")
            .append("assert(r2_ == ").append(m)
            .append("_EXPORTS, \"exact ready replay returns the cached ")
            .append("exports table\")\n")
            .append("assert(r1_.state == \"READY\", \"replayed bindings ")
            .append("must end READY\")\n")
            .append("local o2_, c2_ = v12_count_lines(").append(events)
            .append(")\n")
            .append("assert(o2_ == 1, \"ready replay must not re-open the ")
            .append("library\")\n");
        // Changed-identity failure: same key, changed bundle content.
        sb.append("-- ===== changed-identity failure =====\n")
            .append("local changed_ = {}\n")
            .append("for k_, v_ in pairs(").append(m)
            .append("_BUNDLE) do changed_[k_] = v_ end\n")
            .append("changed_.fullContent = ").append(m)
            .append("_BUNDLE.fullContent .. \" changed\"\n")
            .append("local cb_ = ").append(m).append("_BINDINGS()\n")
            .append("local ok_, e_ = pcall(function()\n")
            .append("  return __rt.load_ffi(").append(m)
            .append("_KEY, changed_, ").append(m)
            .append("_PLANS, cb_, F, FL, FC)\n")
            .append("end)\n")
            .append("assert(not ok_ and type(e_) == \"table\" and e_.code == ")
            .append("\"FFI_LIBRARY_LOAD\", \"changed content must fail ")
            .append("before mutation\")\n")
            .append("assert(cb_.state == \"UNBOUND\", \"the rejected fresh ")
            .append("bindings stay UNBOUND\")\n")
            .append("local o3_, c3_ = v12_count_lines(").append(events)
            .append(")\n")
            .append("assert(o3_ == 1, \"the identity gate fails before any ")
            .append("second open\")\n");
        // Cross-module cdef replay under a fabricated second module key:
        // registered entries replay and the handle-scoped resolver
        // re-opens the same library (dlopen refcounts, so the
        // constructor never re-runs — the event file stays at one
        // open) and publishes working wrappers.
        sb.append("-- ===== cross-module cdef replay (second handle) =====\n")
            .append("local replay_key_ = ").append(m)
            .append("_KEY .. \"#replay\"\n")
            .append("local rr_ = ").append(m).append("_BINDINGS()\n")
            .append("rr_.moduleKey = replay_key_\n")
            .append("local re_ = __rt.load_ffi(replay_key_, ").append(m)
            .append("_BUNDLE, ").append(m)
            .append("_PLANS, rr_, F, FL, FC)\n")
            .append("assert(type(re_) == \"table\" and re_[")
            .append(luaString("fixture_add_int"))
            .append("] ~= nil, \"cross-module replay publishes wrappers\")\n")
            .append("assert(rr_.state == \"READY\", \"cross-module replay ")
            .append("ends READY\")\n")
            .append("local ok_, v_ = pcall(function()\n")
            .append("  return re_[\"fixture_add_int\"].f(40, 2, CF, CL, CC)\n")
            .append("end)\n")
            .append("assert(ok_ and v_ == 42, \"the second handle calls the ")
            .append("same symbols through its own handle\")\n")
            .append("local o4_, c4_ = v12_count_lines(").append(events)
            .append(")\n")
            .append("assert(o4_ == 1, \"dlopen refcounting never re-runs ")
            .append("the constructor for the same library\")\n");
        // Cdef registration failure caching: a malformed private entry
        // fails, the exact failed bundle re-raises the cached error, and
        // previously published modules remain callable.
        sb.append("-- ===== cdef registration failure caching =====\n")
            .append("local bad_key_ = \"ffi:@$external/native/bad\"\n")
            .append("local bad_bundle_ = {\n")
            .append("  bundleDigest = \"b\", identityDigest = \"i\",\n")
            .append("  fullContent = \"bad-content\",\n")
            .append("  nativeLibrary = { kind = \"ABSOLUTE_PATH\", loaderText = ")
            .append("\"/nonexistent/v12-bad.so\" },\n")
            .append("  entries = { { entryDigest = \"e\",\n")
            .append("      fullText = \"typedef int32_t (*v12_bad_fn_)")
            .append("(no_such_type);\",\n")
            .append("      ownedNames = { \"v12_bad_fn_\" } } },\n")
            .append("  functions = { { dealName = \"v12_bad\", cSymbol = ")
            .append("\"v12_bad\", privateFunctionPointerType = \"v12_bad_fn_\",\n")
            .append("      orderedParams = {}, returnType = { kind = \"INT\", ")
            .append("canonicalDescriptor = \"int\" } } },\n")
            .append("  classes = {},\n")
            .append("}\n")
            .append("local bad_cells_ = { v12_bad = { state = \"UNBOUND\", ")
            .append("wrapper = nil, errorValue = nil } }\n")
            .append("local bad_b_ = { moduleKey = bad_key_, state = ")
            .append("\"UNBOUND\", cells = bad_cells_ }\n")
            .append("local ok_, e_ = pcall(function()\n")
            .append("  return __rt.load_ffi(bad_key_, bad_bundle_, {}, ")
            .append("bad_b_, F, FL, FC)\n")
            .append("end)\n")
            .append("assert(not ok_ and type(e_) == \"table\" and e_.code == ")
            .append("\"FFI_LIBRARY_LOAD\", \"a failing cdef entry raises ")
            .append("FFI_LIBRARY_LOAD\")\n")
            .append("assert(bad_b_.state == \"FAILED\", \"the bad bindings ")
            .append("end FAILED\")\n")
            .append("local bad_b2_ = { moduleKey = bad_key_, state = ")
            .append("\"UNBOUND\", cells = { v12_bad = { state = \"UNBOUND\", ")
            .append("wrapper = nil, errorValue = nil } } }\n")
            .append("local ok2_, e2_ = pcall(function()\n")
            .append("  return __rt.load_ffi(bad_key_, bad_bundle_, {}, ")
            .append("bad_b2_, F, FL, FC)\n")
            .append("end)\n")
            .append("assert(not ok2_ and type(e2_) == \"table\" and ")
            .append("e2_.code == \"FFI_LIBRARY_LOAD\" and e2_ == e_, ")
            .append("\"the exact failed bundle re-raises the cached error ")
            .append("value\")\n")
            .append("local ok3_, v3_ = pcall(function()\n")
            .append("  return ").append(m)
            .append("_EXPORTS[\"fixture_add_int\"].f(20, 22, CF, CL, CC)\n")
            .append("end)\n")
            .append("assert(ok3_ and v3_ == 42, \"previously published modules ")
            .append("stay callable after an unrelated cdef failure\")\n");
    }

    private void dualBattery(StringBuilder sb, int index,
                             FfiGeneratedModule module, boolean doubles) {
        String m = "M" + index;
        String exports = m + "_EXPORTS";
        String events = "arg[" + index + "]";
        sb.append("local o_, c_ = v12_count_lines(").append(events)
            .append(")\n")
            .append("assert(o_ == 1 and c_ == 0, \"one dlopen, no close, for ")
            .append("the dual library\")\n");
        if (doubles) {
            valueCase(sb, exports, "fixture_dual_combine",
                List.of("6.0", "7.0"), "42.0",
                "same symbol name, double signature, own handle");
        } else {
            valueCase(sb, exports, "fixture_dual_combine",
                List.of("20", "22"), "42",
                "same symbol name, int32 signature, own handle");
        }
    }

    // =========================================================================
    // Case emitters (generated Lua assertions)
    // =========================================================================

    private static void valueCase(StringBuilder sb, String exports, String fn,
                                  List<String> argExprs, String expect,
                                  String comment) {
        sb.append("-- ").append(comment).append("\n")
            .append("local ok_, v_ = pcall(function()\n")
            .append("  return ").append(callExpr(exports, fn, argExprs))
            .append("\nend)\n")
            .append("assert(ok_, \"").append(fn).append(" failed: \" ")
            .append(".. tostring(v_))\n")
            .append("assert(v_ == ").append(expect).append(", \"")
            .append(fn).append(" expected ").append(luaMessageSafe(expect))
            .append(", got \" .. tostring(v_))\n");
    }

    private static void errorCase(StringBuilder sb, String exports, String fn,
                                  List<String> argExprs, String code,
                                  String comment) {
        sb.append("-- ").append(comment).append("\n")
            .append("local ok_, e_ = pcall(function()\n")
            .append("  return ").append(callExpr(exports, fn, argExprs))
            .append("\nend)\n")
            .append("assert(not ok_, \"").append(fn)
            .append(" must raise a DEAL Error\")\n")
            .append("assert(type(e_) == \"table\" and e_.code == ")
            .append(luaString(code)).append(", \"").append(fn)
            .append(" expected ").append(code)
            .append(", got \" .. tostring(e_))\n");
    }

    private static void nanCase(StringBuilder sb, String exports, String fn,
                                List<String> argExprs, String comment) {
        sb.append("-- ").append(comment).append("\n")
            .append("local ok_, v_ = pcall(function()\n")
            .append("  return ").append(callExpr(exports, fn, argExprs))
            .append("\nend)\n")
            .append("assert(ok_, \"").append(fn).append(" failed: \" ")
            .append(".. tostring(v_))\n")
            .append("assert(v_ ~= v_, \"").append(fn)
            .append(" expected NaN\")\n");
    }

    private static void infCase(StringBuilder sb, String exports, String fn,
                                List<String> argExprs, boolean positive,
                                String comment) {
        String expect = positive ? "math.huge" : "-math.huge";
        sb.append("-- ").append(comment).append("\n")
            .append("local ok_, v_ = pcall(function()\n")
            .append("  return ").append(callExpr(exports, fn, argExprs))
            .append("\nend)\n")
            .append("assert(ok_, \"").append(fn).append(" failed: \" ")
            .append(".. tostring(v_))\n")
            .append("assert(v_ == ").append(expect).append(", \"")
            .append(fn).append(" expected ").append(expect)
            .append(", got \" .. tostring(v_))\n");
    }

    private static void negZeroCase(StringBuilder sb, String exports,
                                    String fn, List<String> argExprs,
                                    String comment) {
        sb.append("-- ").append(comment).append("\n")
            .append("local ok_, v_ = pcall(function()\n")
            .append("  return ").append(callExpr(exports, fn, argExprs))
            .append("\nend)\n")
            .append("assert(ok_, \"").append(fn).append(" failed: \" ")
            .append(".. tostring(v_))\n")
            .append("assert(v_ == 0 and 1 / v_ == -math.huge, \"")
            .append(fn).append(" expected -0.0\")\n");
    }

    private static void lengthCase(StringBuilder sb, String exports, String fn,
                                   List<String> argExprs, String length,
                                   String comment) {
        sb.append("-- ").append(comment).append("\n")
            .append("local ok_, v_ = pcall(function()\n")
            .append("  return ").append(callExpr(exports, fn, argExprs))
            .append("\nend)\n")
            .append("assert(ok_, \"").append(fn).append(" failed: \" ")
            .append(".. tostring(v_))\n")
            .append("assert(type(v_) == \"string\" and #v_ == ").append(length)
            .append(", \"").append(fn).append(" expected length ")
            .append(length).append("\")\n");
    }

    private static void classCase(StringBuilder sb, String exports, String fn,
                                  List<String> argExprs, String identity,
                                  List<String[]> fieldExpects, String comment) {
        sb.append("-- ").append(comment).append("\n")
            .append("local ok_, v_ = pcall(function()\n")
            .append("  return ").append(callExpr(exports, fn, argExprs))
            .append("\nend)\n")
            .append("assert(ok_, \"").append(fn).append(" failed: \" ")
            .append(".. tostring(v_))\n")
            .append("assert(type(v_) == \"table\" and v_.__classname == ")
            .append(identity).append(", \"").append(fn)
            .append(" class tag\")\n");
        for (String[] field : fieldExpects) {
            if (field[1].equals("NONNULL_TOKEN")) {
                sb.append("assert(type(v_.").append(field[0])
                    .append(") == \"table\" and v_.").append(field[0])
                    .append(".__ptr ~= nil, \"").append(fn).append(".")
                    .append(field[0]).append(" non-null token\")\n");
            } else {
                sb.append("assert(v_.").append(field[0]).append(" == ")
                    .append(field[1]).append(", \"").append(fn).append(".")
                    .append(field[0]).append(" expected ").append(field[1])
                    .append(", got \" .. tostring(v_.").append(field[0])
                    .append("))\n");
            }
        }
    }

    private static String callExpr(String exports, String fn,
                                   List<String> argExprs) {
        StringBuilder sb = new StringBuilder();
        sb.append(exports).append("[").append(luaString(fn))
            .append("].f(");
        boolean first = true;
        for (String arg : argExprs) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(arg);
        }
        if (!first) {
            sb.append(", ");
        }
        sb.append("CF, CL, CC)");
        return sb.toString();
    }

    private static String pairExpr(String prefix, String provided) {
        return "__rt.class_plan_(" + prefix + "_ID_Pair, " + prefix
            + "_PLAN_Pair, " + provided + ", CF, CL, CC)";
    }

    private static String boxExpr(String prefix, String pointerExpr) {
        return "__rt.class_plan_(" + prefix + "_ID_PtrBox, " + prefix
            + "_PLAN_PtrBox, {p=" + pointerExpr + "}, CF, CL, CC)";
    }

    /** A quote/backslash-escaped text for Lua message strings (no
     *  surrounding quotes — the text may be a Lua expression). */
    private static String luaMessageSafe(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** A Lua-safe local suffix for a DEAL class name. */
    private static String luaSafe(String name) {
        return name.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static String luaKey(String name) {
        if (name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return name;
        }
        return "[" + luaString(name) + "]";
    }

    private static String luaString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\%03d", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    // =========================================================================
    // Reporting and small utilities
    // =========================================================================
    // =========================================================================
    // Reporting and small utilities
    // =========================================================================

    private void record(String label, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS " + label);
        } else {
            failed++;
        }
    }

    /**
     * The pinned external digest tool (the same /usr/bin/sha256sum the
     * launcher release gate uses; no in-compiler SHA-256 site is added —
     * the compiler's SHA-256 facilities stay the pinned closed registry,
     * CanonicalJsonTest's structural pin).
     */
    private static String sha256Hex(Path file) {
        RunOutcome run = runDirect(
            List.of("/usr/bin/sha256sum", file.toString()),
            file.getParent().toString());
        if (run.exitCode != 0) {
            throw new GateFailure("/usr/bin/sha256sum failed for " + file
                + ": " + run.stdout + run.stderr);
        }
        String[] fields = run.stdout.trim().split("\\s+", 2);
        if (fields.length != 2 || fields[0].length() != 64
                || !fields[0].matches("[0-9a-f]{64}")) {
            throw new GateFailure("malformed sha256sum output for " + file
                + ": " + run.stdout);
        }
        return fields[0];
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    /** A hard gate failure: never a skip, never a retry, never a downgrade. */
    public static final class GateFailure extends RuntimeException {
        public GateFailure(String message) {
            super(message);
        }
    }
}
