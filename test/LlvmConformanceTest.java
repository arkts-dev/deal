package deal.test;

import deal.test.conformance.*;
import deal.semantic.ir.CanonicalJson;
import deal.toolchain.NativeBuildRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Additive native corpus lane. Membership never depends on compilation success. */
public final class LlvmConformanceTest {
    private static NativeCorpusAccounting accounting;

    public static void main(String[] args) throws Exception {
        Path corpus = Path.of("test/conformance").toAbsolutePath().normalize();
        var fixtures = CorpusConformanceTest.nativeCorpusFixtures(corpus);
        accounting = args.length == 0 ? new NativeCorpusAccounting() : null;
        CorpusExecution.controls();
        NativeCorpusAccounting.controls();
        expectationControls();
        if (args.length == 0) {
            nativeControls();
            nativeLoadCases();
            nativeTransportCase();
            nativeCallbackCase();
            nativeNumericCases();
        }
        int passed = 0, failed = 0;
        for (var fixture : fixtures) {
            if (fixture.classification().kind() == CorpusDiscovery.Kind.COMPANION) continue;
            // An explicit developer filter is diagnostic only; the manifest invokes the complete population.
            if (args.length != 0 && !fixture.corpusPath().contains(args[0])) continue;
            try {
                if (fixture.runtimeClassified()) run(corpus, fixture);
                else runFrontend(fixture);
                passed++;
                System.out.println("PASS " + fixture.corpusPath());
            } catch (Exception | AssertionError e) {
                failed++;
                System.out.println("FAIL " + fixture.corpusPath() + ": " + e);
            }
        }
        System.out.println("Native corpus: " + passed + " passed, " + failed
            + " failed (runtime O0/O2; frontend diagnostics only)");
        if (args.length == 0) {
            List<String> defects = new ArrayList<>();
            try { validateNativeHosts(corpus.resolve("host-fixtures")); }
            catch (AssertionError failure) { defects.add(failure.getMessage()); }
            try { accounting.verify(); }
            catch (AssertionError failure) { defects.add(failure.getMessage()); }
            if (!defects.isEmpty()) throw new AssertionError(String.join("; ", defects));
        }
        if (passed + failed == 0 || failed != 0) throw new AssertionError("native corpus incomplete or failing");
    }

    /** Native artifacts must not turn a lost consumer or fabricated completion into success. */
    private static void nativeControls() throws Exception {
        Path corpus = NativeCompiler.executableScratch("native-corpus-controls");
        try {
            Path source = corpus.resolve("backend-runtime/control.deal");
            Files.createDirectories(source.getParent());
            Path hostControls = Files.createDirectories(corpus.resolve("host-fixtures"));
            Files.writeString(hostControls.resolve("probe.d.deal"), "export function f(): void;\n");
            for (String defect : List.of("missing", "unmatched", "duplicate", "header")) {
                String unit = "#include \"host/probe.h\"\nconst deal_host_module deal_host_module_probe = {0};\n";
                Path implementation = hostControls.resolve(defect.equals("unmatched") ? "other.c" : "probe.c");
                if (!defect.equals("missing")) Files.writeString(implementation,
                    defect.equals("duplicate") ? unit + "const deal_host_module deal_host_module_probe = {0};\n"
                        : defect.equals("header") ? unit.replace("host/probe.h", "wrong.h") : unit);
                boolean rejected = false;
                try { validateNativeHosts(hostControls); } catch (AssertionError expected) { rejected = true; }
                if (!rejected) throw new AssertionError("native host reconciliation accepted " + defect);
                Files.deleteIfExists(implementation);
            }
            Files.delete(hostControls.resolve("probe.d.deal"));
            String body = "export function main(): null { return null; }\n"
                + "export async function answer(): Promise<number> { return 42; }\n";
            Files.writeString(source, body);
            var fixture = new CorpusDiscovery.Fixture(source, "backend-runtime/control.deal",
                "backend-runtime", "Runtime execution model", "native harness control", "runtime-ok",
                "", "", body, body, 0, new CorpusDiscovery.Classification(CorpusDiscovery.Kind.RUNTIME_OK, null));
            var pins = Map.of("answer", new SidecarExpectations.CompletionPin("number", "42"));
            try (var project = new NativeCorpusProject(corpus, fixture, null)) {
                if (!project.compile()) throw new AssertionError("native control rejected: " + project.compiler.diagnostics());
                var probes = CorpusExecution.probes(project.program(), false);
                for (int level : new int[]{0, 2}) {
                    for (String variant : List.of("valid", "lost-probe", "missing-export", "lost-completion", "wrong-completion")) {
                        Path work = Files.createDirectories(project.scratch.resolve("control-O" + level + "-" + variant));
                        Path product = work.resolve("driver");
                        String driver = driver(project.module(),
                            variant.equals("lost-probe") ? probes.subList(0, 1) : probes,
                            variant.equals("lost-probe") ? Map.of() : pins);
                        if (variant.equals("missing-export"))
                            driver = driver.replace("deal_export_lookup(module,\"answer\")",
                                "deal_export_lookup(module,\"absent\")");
                        if (variant.equals("lost-completion"))
                            driver = driver.replace("completion(\"answer\",value);", "(void)value;");
                        if (variant.equals("wrong-completion"))
                            driver = driver.replace("completion(\"answer\",value);", "value.number += 1; completion(\"answer\",value);");
                        NativeCompiler.build(new NativeBuildRequest(project.output, product, level, List.of(), driver));
                        var actual = BoundedProcess.runCaptured(List.of(product.toString()), work, Map.of());
                        if (actual.stdoutTruncated() || actual.stderrTruncated())
                            throw new AssertionError("native control capture truncated");
                        var receipt = CorpusExecution.receiptVerdict(work, probes, false, false);
                        if (variant.equals("lost-probe") || variant.equals("missing-export")) {
                            if (receipt.isEmpty()) throw new AssertionError("native control accepted " + variant);
                            if (variant.equals("missing-export") && (actual.exitCode() != 2
                                    || !actual.stderr().equals("missing export\n")))
                                throw new AssertionError("missing export control failed for another reason: " + actual);
                            continue;
                        }
                        if (receipt.isPresent() || actual.exitCode() != 0
                                || !actual.stdout().isEmpty() || !actual.stderr().isEmpty())
                            throw new AssertionError("valid native control failed: " + actual + "; " + receipt);
                        if (variant.equals("lost-completion") || variant.equals("wrong-completion")) {
                            boolean rejected = false;
                            try { CorpusExecution.completionVerdict(work, probes, pins); }
                            catch (IllegalStateException expected) { rejected = true; }
                            if (!rejected) throw new AssertionError("native control accepted " + variant);
                            continue;
                        }
                        CorpusExecution.completionVerdict(work, probes, pins);
                        boolean rejected = false;
                        try {
                            CorpusExecution.completionVerdict(work, probes,
                                Map.of("answer", new SidecarExpectations.CompletionPin("number", "43")));
                        } catch (IllegalStateException expected) { rejected = true; }
                        if (!rejected) throw new AssertionError("native completion value was manufactured from its pin");
                    }
                    System.out.println("Native harness controls O" + level
                        + ": valid, lost probe/export/completion, wrong completion observation/pin");
                }
            }
        } finally {
            try (var paths = Files.walk(corpus)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    /** G9 load/value/JSON tuples come from registry parameters before execution. */
    private static void nativeLoadCases() throws Exception {
        String atom = "@$external/host/probe/C";
        String other = "@$external/host/probe/Other";
        for (String variant : List.of("failed", "signature", "identity", "metadata", "invalid-utf8", "string-surrogate",
                "typed-unicode", "extra-field", "json-walk", "json-unsupported")) {
            Path corpus = NativeCompiler.executableScratch("native-load-case");
            try {
                Path source = corpus.resolve("backend-runtime/load-" + variant + ".deal");
                Path hosts = Files.createDirectories(corpus.resolve("host-fixtures"));
                Files.createDirectories(source.getParent());
                boolean invalidText = variant.equals("invalid-utf8") || variant.equals("string-surrogate");
                boolean runtimeCheck = variant.equals("typed-unicode") || variant.equals("extra-field")
                    || variant.equals("json-walk") || variant.equals("json-unsupported");
                boolean atCall = invalidText || runtimeCheck;
                String body = "import * as host from \"host/probe\";\n"
                    + (atCall ? "export function main(): null {\n  host.f();\n  return null;\n}\n"
                        : "export function main(): null { return null; }\n");
                Files.writeString(source, body);
                Files.writeString(hosts.resolve("probe.d.deal"), atCall ? "export function f(): string;\n"
                    : variant.equals("signature") ? "export function f(): number;\n" : "export class C { x: number; }\n");
                deal.semantic.ir.FailureArmId arm;
                Map<String, String> parameters;
                if (variant.equals("typed-unicode")) {
                    arm = deal.semantic.ir.FailureArmId.TYPED_BOUNDARY_INVALID_UNICODE;
                    parameters = Map.of();
                } else if (variant.equals("extra-field")) {
                    arm = deal.semantic.ir.FailureArmId.CLASS_EXTRA_FIELD;
                    parameters = Map.of("field", "extra", "classId", "@native/C");
                } else if (variant.equals("json-unsupported")) {
                    arm = deal.semantic.ir.FailureArmId.JSON_STRINGIFY_UNSUPPORTED;
                    parameters = Map.of("actual", "table");
                } else if (variant.equals("json-walk")) {
                    arm = deal.semantic.ir.FailureArmId.JSON_TO_WALK;
                    parameters = Map.of("fieldPath", "x", "actual", "number");
                } else if (invalidText) {
                    arm = deal.semantic.ir.FailureArmId.HOST_SYNC_RETURN_CELL;
                    String inner = deal.semantic.ir.FailureContractRegistry.renderInner(variant.equals("invalid-utf8")
                        ? deal.semantic.ir.FailureArmId.HOST_STRING_INVALID_UTF8
                        : deal.semantic.ir.FailureArmId.HOST_STRING_SURROGATE, Map.of());
                    parameters = Map.of("inner", inner);
                } else if (variant.equals("failed")) {
                    arm = deal.semantic.ir.FailureArmId.HOST_LOAD_FAILED;
                    parameters = Map.of("module", "host/probe", "reason", "no registered native implementation");
                } else if (variant.equals("signature")) {
                    arm = deal.semantic.ir.FailureArmId.HOST_LOAD_SIGNATURE_MISMATCH;
                    parameters = Map.of("module", "host/probe", "name", "f", "expected", "()->number", "actual", "()->string");
                } else if (variant.equals("identity")) {
                    arm = deal.semantic.ir.FailureArmId.HOST_LOAD_IDENTITY_MISMATCH;
                    parameters = Map.of("module", "host/probe", "name", "C", "expected", atom, "actual", other);
                } else {
                    arm = deal.semantic.ir.FailureArmId.HOST_LOAD_INVALID_METADATA;
                    parameters = Map.of("module", "host/probe", "name", "C", "defaults|fields", "fields");
                }
                var failure = deal.semantic.ir.FailureContractRegistry.render(arm, parameters,
                    invalidText || variant.equals("typed-unicode") ? "string"
                        : variant.equals("json-unsupported") ? "string, number, boolean, or table" : null,
                    invalidText ? "string" : variant.equals("typed-unicode") ? "invalid-unicode"
                        : variant.equals("json-walk") ? "number"
                        : variant.equals("json-unsupported") ? "table" : null, null);
                String coordinate = "backend-runtime/load-" + variant + ".deal";
                var expectedError = new SidecarExpectations.ErrorExpectation(failure.code().name(), failure.message(),
                    coordinate, atCall ? 3 : 1, atCall ? 3 : 1,
                    Optional.ofNullable(failure.expected()), Optional.ofNullable(failure.actual()), Optional.empty(), Optional.empty());
                String transcript = ErrorSnapshot.CODE_LINE_PREFIX + failure.code().name() + "\n"
                    + ErrorSnapshot.SNAPSHOT_LINE_PREFIX + ErrorSnapshot.canonicalJson(expectedError) + "\n";
                var expected = new SidecarExpectations.RuntimeExpectation.Executed("runtime-error",
                    transcript.getBytes(StandardCharsets.UTF_8), new byte[0], 1, expectedError);
                var fixture = new CorpusDiscovery.Fixture(source, coordinate, "backend-runtime",
                    "Runtime execution model", "registry-derived boundary " + variant, "runtime-error " + failure.code().name(), "", "",
                    body, body, 0, new CorpusDiscovery.Classification(CorpusDiscovery.Kind.RUNTIME_ERROR, failure.code().name()));
                try (var project = new NativeCorpusProject(corpus, fixture, null)) {
                    if (!project.compile()) throw new AssertionError("native load case rejected: " + project.compiler.diagnostics());
                    List<Path> units = new ArrayList<>();
                    if (!variant.equals("failed")) {
                        Path unit = Files.createDirectories(project.scratch.resolve("host-units")).resolve("probe.c");
                        String registration;
                        if (runtimeCheck) {
                            String check = variant.equals("typed-unicode") ? """
                                deal_value carrier;
                                if (deal_string_new("\\xed\\xa0\\x80", 3u, &carrier) != DEAL_OK) abort();
                                deal_check_input input = {0};
                                deal_error *cause = NULL;
                                if (deal_host_value_presentation(carrier, &input, &cause) != DEAL_OK || cause != NULL) abort();
                                deal_check_context context = {&origin, 0};
                                deal_outcome checked = deal_check_value(deal_descriptor_intern("string"), input,
                                    DEAL_BOUNDARY_TYPE_DESCRIPTOR, &context);
                                """ : variant.equals("json-unsupported") ? """
                                /* Deliberately unplanned test-owned class; ordinary emitted classes have plans. */
                                deal_root_scope_push();
                                deal_table *stand_in = deal_container_table_new();
                                (void)deal_root_scope_hold(stand_in);
                                deal_data datum = deal_data_of_reference(DEAL_DATA_CLASS, stand_in);
                                deal_outcome_data encoded = deal_json_stringify(datum, &origin);
                                deal_outcome checked = {encoded.error};
                                deal_root_scope_pop();
                                """ : variant.equals("json-walk") ? """
                                deal_root_scope_push();
                                deal_table *instance = deal_container_table_new();
                                (void)deal_root_scope_hold(instance);
                                const deal_class_field field = {"x", deal_descriptor_intern("string"), 0};
                                const deal_class_layout layout = {"@native/C", &field, 1u};
                                const deal_json_class_plan plan = {&layout, NULL, read_field, NULL};
                                deal_data datum = deal_data_of_reference(DEAL_DATA_CLASS, instance);
                                deal_outcome_string encoded = deal_json_to_class(&plan, &datum, lookup, (void *)&plan, &origin);
                                deal_outcome checked = {encoded.error};
                                deal_root_scope_pop();
                                """ : """
                                const deal_class_layout layout = {"@native/C", NULL, 0u};
                                deal_class_supplied supplied = {0};
                                supplied.name = "extra";
                                supplied.value.presentation = DEAL_PRESENT_DATUM;
                                supplied.value.datum = deal_data_of_number(1);
                                supplied.origin = origin;
                                const char *published = "unpublished";
                                deal_outcome checked = deal_class_construct(&layout, &supplied, 1u, &origin, &published);
                                if (strcmp(published, "unpublished") != 0) abort();
                                """;
                            String seams = variant.equals("json-walk") ? """
                                /* Test-owned class plan: a hostile field disagrees with its declared descriptor. */
                                static int read_field(const deal_data *instance, size_t index,
                                        deal_check_input *out, uint8_t *present, void *context) {
                                    (void)instance; (void)context;
                                    memset(out, 0, sizeof(*out));
                                    out->presentation = DEAL_PRESENT_DATUM;
                                    out->datum = deal_data_of_number(1);
                                    *present = index == 0u;
                                    return 1;
                                }
                                static const deal_json_class_plan *lookup(const char *atom, void *context) {
                                    return strcmp(atom, "@native/C") == 0 ? context : NULL;
                                }
                                """ : "";
                            registration = seams + "static deal_host_result f(const deal_value *args, size_t argc) {\n"
                                + "(void)args; (void)argc;\nconst deal_origin origin = {\"" + coordinate + "\",3,3};\n"
                                + check + "deal_host_result r = {0};\n"
                                + "if (checked.error == NULL || deal_host_relay_error(checked.error, &r) != DEAL_OK) abort();\nreturn r;\n}\n"
                                + "static const deal_host_export exports[] = {{\"f\",\"()->string\",DEAL_HOST_EXPORT_FUNCTION,0,f,NULL,NULL}};\n"
                                + "const deal_host_module deal_host_module_probe = {\"host/probe\",exports,1u,NULL,0u,NULL};\n";
                        } else if (invalidText) registration = """
                            static deal_host_result f(const deal_value *args, size_t argc) {
                                (void)args; (void)argc; deal_host_result r = {0};
                                if (deal_string_new(BAD_BYTES, sizeof(BAD_BYTES) - 1u, &r.value) != DEAL_OK) abort();
                                return r;
                            }
                            static const deal_host_export exports[] = {
                                {"f", "()->string", DEAL_HOST_EXPORT_FUNCTION, 0, f, NULL, NULL}
                            };
                            const deal_host_module deal_host_module_probe = {
                                "host/probe", exports, 1u, NULL, 0u, NULL
                            };
                            """.replace("BAD_BYTES", variant.equals("invalid-utf8") ? "\"\\xff\"" : "\"\\xed\\xa0\\x80\"");
                        else if (variant.equals("signature")) registration = """
                            static deal_host_result f(const deal_value *args, size_t argc) {
                                (void)args; (void)argc; deal_host_result r = {0};
                                if (deal_string_new("x", 1u, &r.value) != DEAL_OK) return r;
                                return r;
                            }
                            static const deal_host_export exports[] = {
                                {"f", "()->string", DEAL_HOST_EXPORT_FUNCTION, 0, f, NULL, NULL}
                            };
                            const deal_host_module deal_host_module_probe = {
                                "host/probe", exports, 1u, NULL, 0u, NULL
                            };
                            """;
                        else {
                            String identity = variant.equals("identity") ? other : atom;
                            registration = "static const deal_host_field fields[] = {{\"x\",\""
                                + (variant.equals("metadata") ? "garbage" : "number") + "\",0}};\n"
                                + "static const deal_host_class classes[] = {{\"" + identity + "\",fields,1u}};\n"
                                + "static const deal_host_export exports[] = {{\"C\",\"" + identity
                                + "\",DEAL_HOST_EXPORT_CLASS,0,NULL,NULL,&classes[0]}};\n"
                                + "const deal_host_module deal_host_module_probe = {\"host/probe\",exports,1u,classes,1u,NULL};\n";
                        }
                        Files.writeString(unit, "#include \"host/probe.h\"\n#include <stdlib.h>\n#include <string.h>\n" + registration);
                        units.add(unit);
                    }
                    var probes = CorpusExecution.probes(project.program(), true);
                    for (int level : new int[]{0, 2}) {
                        Path work = Files.createDirectories(project.scratch.resolve("run-O" + level));
                        Path product = work.resolve("driver");
                        NativeCompiler.build(new NativeBuildRequest(project.output, product, level, units,
                            driver(project.module(), probes)));
                        for (boolean stress : new boolean[]{false, true}) {
                            Path execution = Files.createDirectories(work.resolve("stress-" + stress));
                            var actual = BoundedProcess.runCaptured(List.of(product.toString()), execution,
                                stress ? Map.of("DEAL_GC_STRESS", "1") : Map.of());
                            if (actual.stdoutTruncated() || actual.stderrTruncated()) throw new AssertionError("load capture truncated");
                            var receipt = CorpusExecution.receiptVerdict(execution, probes, true, !atCall);
                            if (receipt.isPresent()) throw new AssertionError(receipt.get());
                            var mismatch = StructuredExpectationComparator.compare("native-load-" + variant + "-O" + level, expected,
                                new LaneExecution.Executed(projectTranscript(actual.stdout(), project, expected).getBytes(StandardCharsets.UTF_8),
                                    actual.stderr().getBytes(StandardCharsets.UTF_8), actual.exitCode()));
                            if (mismatch.isPresent()) throw new AssertionError(mismatch.get() + "; stress=" + stress + "; " + actual);
                            System.out.println("Native registry case O" + level + " stress=" + stress + ": " + variant + " " + arm);
                        }
                    }
                    if (accounting != null) accounting.executed(project.compiler.producedProject(), expectedError);
                }
            } finally {
                try (var paths = Files.walk(corpus)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }

    /** G9: relay/discard retain Error identity, first-throw facts and checked value. */
    private static void nativeTransportCase() throws Exception {
        Path corpus = NativeCompiler.executableScratch("native-error-transport");
        try {
            Path source = corpus.resolve("backend-runtime/error-transport.deal");
            Path hosts = Files.createDirectories(corpus.resolve("host-fixtures"));
            Files.createDirectories(source.getParent());
            String body = """
                import * as host from "host/probe";
                function check(value: boolean): void {
                  if (!value) { throw {code: "TRANSPORT_TEST", message: "transport contract"}; }
                }
                export function main(): null {
                  let held: Error = {code: "ORIGINAL", message: "unchanged"};
                  try { throw held; } catch (e) { check((e as Error) === held); }
                  host.capture(held);
                  let fail: () => number = function(): number { throw held; };
                  check(host.discard(fail) === 7);
                  check(host.sameFacts(held));
                  let caught: boolean = false;
                  try { host.relay(fail); } catch (e) {
                    let error = e as Error;
                    check(error === held);
                    check(error.code === "ORIGINAL" && error.message === "unchanged");
                    check(host.sameFacts(error));
                    caught = true;
                  }
                  check(caught);
                  return null;
                }
                """;
            Files.writeString(source, body);
            Files.writeString(hosts.resolve("probe.d.deal"), """
                export function capture(error: Error): void;
                export function sameFacts(error: Error): boolean;
                export function relay(f: () => number): number;
                export function discard(f: () => number): number;
                """);
            var fixture = new CorpusDiscovery.Fixture(source, "backend-runtime/error-transport.deal", "backend-runtime",
                "Runtime execution model", "native Error transport", "runtime-ok", "", "", body, body, 0,
                new CorpusDiscovery.Classification(CorpusDiscovery.Kind.RUNTIME_OK, null));
            try (var project = new NativeCorpusProject(corpus, fixture, null)) {
                if (!project.compile()) throw new AssertionError("transport case rejected: " + project.compiler.diagnostics());
                Path unit = Files.createDirectories(project.scratch.resolve("hosts")).resolve("probe.c");
                Files.writeString(unit, """
                    #include "host/probe.h"
                    #include "deal/native/error.h"
                    #include <stdlib.h>
                    #include <string.h>
                    static char *original;
                    static deal_host_result value(deal_value v) { deal_host_result r = {0}; r.value = v; return r; }
                    static void ok(deal_status s) { if (s != DEAL_OK) abort(); }
                    static deal_error *errorOf(deal_value v) {
                        deal_check_input input; deal_error *cause = NULL;
                        ok(deal_host_value_presentation(v, &input, &cause));
                        if (input.datum.tag != DEAL_DATA_ERROR || cause != NULL) abort();
                        return input.datum.reference;
                    }
                    static deal_host_result capture(const deal_value *args, size_t argc) {
                        (void)argc;
                        if (original != NULL) deal_error_snapshot_release(original);
                        original = deal_error_snapshot(errorOf(args[0]));
                        deal_value v; ok(deal_undefined(&v)); return value(v);
                    }
                    static deal_host_result sameFacts(const deal_value *args, size_t argc) {
                        (void)argc;
                        char *current = deal_error_snapshot(errorOf(args[0]));
                        int same = original != NULL && strcmp(original, current) == 0;
                        deal_error_snapshot_release(current);
                        deal_value v; ok(deal_boolean(same, &v)); return value(v);
                    }
                    static deal_host_result relay(const deal_value *args, size_t argc) {
                        (void)argc; deal_host_result r;
                        ok(deal_apply(args[0], NULL, 0u, &r));
                        if (!r.failed || r.failure.kind != DEAL_FAILURE_DEAL_ERROR) abort();
                        deal_gc_collect_full();
                        return r;
                    }
                    static deal_host_result discard(const deal_value *args, size_t argc) {
                        deal_host_result r = relay(args, argc);
                        ok(deal_failure_discard(r.failure.error));
                        deal_gc_collect_full();
                        deal_value v; ok(deal_number(7.0, &v)); return value(v);
                    }
                    static const deal_host_export exports[] = {
                        {"capture", "(Error)->void", DEAL_HOST_EXPORT_FUNCTION, 0, capture, NULL, NULL},
                        {"sameFacts", "(Error)->boolean", DEAL_HOST_EXPORT_FUNCTION, 0, sameFacts, NULL, NULL},
                        {"relay", "(()->number)->number", DEAL_HOST_EXPORT_FUNCTION, 0, relay, NULL, NULL},
                        {"discard", "(()->number)->number", DEAL_HOST_EXPORT_FUNCTION, 0, discard, NULL, NULL},
                    };
                    const deal_host_module deal_host_module_probe = {
                        "host/probe", exports, sizeof exports / sizeof exports[0], NULL, 0u, NULL
                    };
                    """);
                var probes = CorpusExecution.probes(project.program(), false);
                for (int level : new int[]{0, 2}) {
                    Path product = Files.createDirectories(project.scratch.resolve("product-O" + level)).resolve("driver");
                    NativeCompiler.build(new NativeBuildRequest(project.output, product, level, List.of(unit), driver(project.module(), probes)));
                    for (boolean stress : new boolean[]{false, true}) {
                        Path work = Files.createDirectories(project.scratch.resolve("run-O" + level + "-" + stress));
                        var actual = BoundedProcess.runCaptured(List.of(product.toString()), work,
                            stress ? Map.of("DEAL_GC_STRESS", "1") : Map.of());
                        var receipt = CorpusExecution.receiptVerdict(work, probes, false, false);
                        if (actual.exitCode() != 0 || actual.stdoutTruncated() || actual.stderrTruncated()
                                || !actual.stdout().isEmpty() || !actual.stderr().isEmpty() || receipt.isPresent())
                            throw new AssertionError("native Error transport O" + level + " stress=" + stress + ": " + actual + "; " + receipt);
                        System.out.println("Native transport O" + level + " stress=" + stress + ": relay/discard identity and unchanged facts");
                    }
                }
                if (accounting != null) accounting.executed(project.compiler.producedProject(), null);
            }
        } finally {
            try (var paths = Files.walk(corpus)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    /** Host-driven callback selection uses the production lowering seam, not a fabricated op. */
    private static void nativeCallbackCase() throws Exception {
        Path corpus = NativeCompiler.executableScratch("native-callback-case");
        try {
            Path source = corpus.resolve("backend-runtime/callback.deal");
            Files.createDirectories(source.getParent());
            Path hosts = Files.createDirectories(corpus.resolve("host-fixtures"));
            Files.writeString(hosts.resolve("probe.d.deal"), "export function invoke(n: number, effects: number[], failure: Error): number;\n");
            String body = """
                import * as host from "host/probe";
                export function handler(n: number, effects: number[], failure: Error): number {
                  effects[0] = effects[0] + 1;
                  if (n < 0) { throw failure; }
                  return n + 1;
                }
                export function main(): null {
                  let effects: number[] = [0];
                  let failure: Error = {code: "CALLBACK_ORIGINAL", message: "retain identity"};
                  let answer = host.invoke(41, effects, failure);
                  if (answer !== 42 || effects[0] !== 1) {
                    throw {code: "CALLBACK_TEST", message: "callback result or effects"};
                  }
                  let caught: boolean = false;
                  try { host.invoke(-1, effects, failure); }
                  catch (e) {
                    if (e instanceof Error) {
                      caught = e === failure
                        && e.code === "CALLBACK_ORIGINAL" && e.message === "retain identity";
                    }
                  }
                  if (!caught || effects[0] !== 2) {
                    throw {code: "CALLBACK_TEST", message: "callback failure identity or effects"};
                  }
                  return null;
                }
                """;
            Files.writeString(source, body);
            var fixture = new CorpusDiscovery.Fixture(source, "backend-runtime/callback.deal",
                "backend-runtime", "Runtime execution model", "native host-driven callback", "runtime-ok",
                "", "", body, body, 0, new CorpusDiscovery.Classification(CorpusDiscovery.Kind.RUNTIME_OK, null));
            try (var project = new NativeCorpusProject(corpus, fixture, null)) {
                if (!project.compile()) throw new AssertionError("callback case rejected: " + project.compiler.diagnostics());
                var built = project.compiler.checkedProject();
                var context = deal.project.ProjectLocator.locate(project.entry.toString(),
                    new deal.project.CliOverrides("llvm", project.output.toString())).context();
                var identities = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.identity.CanonicalModuleIdentity>();
                for (var declaration : project.compiler.hostDeclarationSurface().moduleIds()) {
                    var external = context.externals().entrySet().stream()
                        .filter(row -> row.getKey().replace('/', '.').equals(declaration.path()))
                        .map(Map.Entry::getValue).findFirst()
                        .orElseThrow(() -> new AssertionError("callback declaration has no external mapping: " + declaration));
                    var identity = deal.module.ModuleIdentityResolver.classify(context,
                        Path.of(external.declarationPath().absoluteNormalizedPath()).toUri().toString()).moduleIdentity();
                    if (identity == null) throw new AssertionError("callback declaration has no canonical identity");
                    identities.put(declaration, identity);
                }
                var invocation = deal.semantic.CompilerProfileProvider.resolve(
                    deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                    deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
                // Callback selection is an embedding input, absent from CLI manifests.
                // Re-enter the same production lowering/emission seam with that input.
                deal.module.ProductionProjectEmission.Result emitted;
                var staging = deal.publication.PublicationStager.forRoot(project.output);
                try {
                    emitted = deal.module.ProductionProjectEmission.run(invocation,
                        built.input(), built.index(), project.compiler.requirementManifests().manifests(),
                        project.compiler.hostDeclarationSurface(), identities,
                        deal.checker.BuiltinErrorDeclaration.synthesized(built.input().modules().get(0).ast().span()),
                        Set.of("handler"), deal.codegen.Backend.LLVM, false, project.scratch.toString(),
                        deal.distribution.DistributionHome.forManifestDirectory(project.scratch.toString()), staging);
                    if (!emitted.emitted()) throw new AssertionError("callback emission rejected: " + emitted.diagnostics());
                    staging.publish();
                } finally { staging.discard(); }
                var entry = java.util.regex.Pattern.compile("(?m)^define i32 @(deal_callback_[0-9]+_[0-9]+)\\(")
                    .matcher(Files.readString(project.output.resolve((fixture.corpusPath() + "/main").replace('.', '/') + ".ll")));
                if (!entry.find()) throw new AssertionError("callback entry missing");
                String symbol = entry.group(1);
                if (entry.find()) throw new AssertionError("ambiguous callback entry");
                Path unit = project.scratch.resolve("probe.c");
                Files.writeString(unit, """
                    #include "host/probe.h"
                    #include <stdlib.h>
                    extern deal_status %s(deal_host_result *, const deal_value *, size_t);
                    static deal_host_result invoke(const deal_value *args, size_t count) {
                        deal_host_result returned;
                        if (%s(&returned, args, count) != DEAL_OK) abort();
                        return returned;
                    }
                    static const deal_host_export exports[] = {
                        {"invoke", "(number,[number],Error)->number", DEAL_HOST_EXPORT_FUNCTION, 0, invoke, NULL, NULL},
                    };
                    const deal_host_module deal_host_module_probe = {
                        "host/probe", exports, 1u, NULL, 0u, NULL
                    };
                    """.formatted(symbol, symbol));
                // handler has a parameter: only main is an independent public consumer.
                var probes = CorpusExecution.probes(project.program(), false);
                for (int level : new int[]{0, 2}) {
                    Path product = project.scratch.resolve("callback-O" + level);
                    NativeCompiler.build(new NativeBuildRequest(project.output, product, level, List.of(unit),
                        driver(project.module(), probes)));
                    for (boolean stress : new boolean[]{false, true}) {
                        Path work = Files.createDirectories(project.scratch.resolve("run-O" + level + "-" + stress));
                        var actual = BoundedProcess.runCaptured(List.of(product.toString()), work,
                            stress ? Map.of("DEAL_GC_STRESS", "1") : Map.of());
                        var receipt = CorpusExecution.receiptVerdict(work, probes, false, false);
                        if (actual.exitCode() != 0 || actual.stdoutTruncated() || actual.stderrTruncated()
                                || !actual.stdout().isEmpty() || !actual.stderr().isEmpty() || receipt.isPresent())
                            throw new AssertionError("native callback O" + level + " stress=" + stress + ": " + actual + "; " + receipt);
                        System.out.println("Native callback O" + level + " stress=" + stress + ": result 42, relayed Error identity, once-only bodies");
                    }
                }
                boolean recorded = emitted.producedProject().modules().values().stream()
                    .flatMap(module -> module.ops().stream())
                    .anyMatch(op -> op.kind() == deal.semantic.ir.SemanticOpKind.CALLBACK_INVOKE);
                if (!recorded) throw new AssertionError("executed callback has no lowered CALLBACK_INVOKE fact");
                if (accounting != null) accounting.executed(emitted.producedProject(), null);
            }
        } finally {
            try (var paths = Files.walk(corpus)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    /** Spec numeric contracts override obsolete failure inventory entries. */
    private static void nativeNumericCases() throws Exception {
        Path corpus = NativeCompiler.executableScratch("native-numeric-cases");
        try {
            Files.createDirectories(corpus.resolve("host-fixtures"));
            String success = """
                import * as math from "std/math";
                function check(value: boolean): void {
                  if (!value) { throw {code: "NUMERIC_TEST", message: "binary64 contract"}; }
                }
                export function main(): null {
                  let negative = math.sqrt(-1);
                  check(negative !== negative);
                  let nan = 0 / 0;
                  check(math.sqrt(nan) !== math.sqrt(nan));
                  check(math.sqrt(1 / 0) === 1 / 0);
                  check(1 / math.sqrt(-0) === -1 / 0);
                  return null;
                }
                """;
            String rejected = """
                import * as strings from "std/string";
                export function main(): null {
                  strings.substring("abc", 0.5, 2);
                  return null;
                }
                """;
            for (boolean bounds : new boolean[]{false, true}) {
                String coordinate = "backend-runtime/native-" + (bounds ? "substring-bounds" : "sqrt-values") + ".deal";
                Path source = corpus.resolve(coordinate);
                Files.createDirectories(source.getParent()); Files.writeString(source, bounds ? rejected : success);
                SidecarExpectations.ErrorExpectation error = null;
                String stdout = "";
                if (bounds) {
                    var failure = deal.semantic.ir.FailureContractRegistry.render(
                        deal.semantic.ir.FailureArmId.SUBSTRING_BOUNDS, Map.of(), null, null, null);
                    error = new SidecarExpectations.ErrorExpectation(failure.code().name(), failure.message(), coordinate, 3, 3,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
                    stdout = ErrorSnapshot.CODE_LINE_PREFIX + failure.code().name() + "\n"
                        + ErrorSnapshot.SNAPSHOT_LINE_PREFIX + ErrorSnapshot.canonicalJson(error) + "\n";
                }
                var expected = new SidecarExpectations.RuntimeExpectation.Executed(bounds ? "runtime-error" : "runtime-ok",
                    stdout.getBytes(StandardCharsets.UTF_8), new byte[0], bounds ? 1 : 0, error);
                var fixture = new CorpusDiscovery.Fixture(source, coordinate, "backend-runtime", "Runtime execution model",
                    "native binary64", bounds ? "runtime-error E8001" : "runtime-ok", "", "", bounds ? rejected : success,
                    bounds ? rejected : success, 0, new CorpusDiscovery.Classification(bounds
                        ? CorpusDiscovery.Kind.RUNTIME_ERROR : CorpusDiscovery.Kind.RUNTIME_OK, bounds ? "E8001" : null));
                try (var project = new NativeCorpusProject(corpus, fixture, null)) {
                    if (!project.compile()) throw new AssertionError("numeric case rejected: " + project.compiler.diagnostics());
                    var probes = CorpusExecution.probes(project.program(), bounds);
                    for (int level : new int[]{0, 2}) {
                        Path work = Files.createDirectories(project.scratch.resolve("run-O" + level));
                        Path product = work.resolve("driver");
                        NativeCompiler.build(new NativeBuildRequest(project.output, product, level, List.of(), driver(project.module(), probes)));
                        for (boolean stress : new boolean[]{false, true}) {
                            Path execution = Files.createDirectories(work.resolve("stress-" + stress));
                            var actual = BoundedProcess.runCaptured(List.of(product.toString()), execution,
                                stress ? Map.of("DEAL_GC_STRESS", "1") : Map.of());
                            var receipt = CorpusExecution.receiptVerdict(execution, probes, bounds, false);
                            if (actual.stdoutTruncated() || actual.stderrTruncated() || receipt.isPresent())
                                throw new AssertionError("numeric capture/receipt: " + actual + "; " + receipt);
                            var mismatch = StructuredExpectationComparator.compare("native-numeric-O" + level, expected,
                                new LaneExecution.Executed(projectTranscript(actual.stdout(), project, expected).getBytes(StandardCharsets.UTF_8),
                                    actual.stderr().getBytes(StandardCharsets.UTF_8), actual.exitCode()));
                            if (mismatch.isPresent()) throw new AssertionError(mismatch.get() + "; stress=" + stress + "; " + actual);
                            System.out.println("Native numeric O" + level + " stress=" + stress + ": " + coordinate);
                        }
                    }
                    if (accounting != null) accounting.executed(project.compiler.producedProject(), error);
                }
            }
        } finally {
            try (var paths = Files.walk(corpus)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    static void validateNativeHosts(Path root) throws Exception {
        Set<String> declarations = new TreeSet<>(), implementations = new TreeSet<>();
        List<String> malformed = new ArrayList<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String path = root.relativize(file).toString().replace('\\', '/');
                if (path.endsWith(".d.deal")) declarations.add(path.substring(0, path.length() - 7));
                if (!path.endsWith(".c")) continue;
                String stem = path.substring(0, path.length() - 2);
                implementations.add(stem);
                String module = stem.startsWith("boundaries/") ? "probe" : stem;
                String source = Files.readString(file);
                var registration = java.util.regex.Pattern.compile(
                    "\\bconst\\s+deal_host_module\\s+deal_host_module_"
                    + java.util.regex.Pattern.quote(module) + "\\s*=").matcher(source);
                int count = 0;
                while (registration.find()) count++;
                if (count != 1 || !source.contains("#include \"host/" + module + ".h\""))
                    malformed.add(stem);
            }
        }
        Set<String> missing = new TreeSet<>(declarations); missing.removeAll(implementations);
        Set<String> extra = new TreeSet<>(implementations); extra.removeAll(declarations);
        System.out.println("Native hosts: " + declarations.size() + " declarations, "
            + implementations.size() + " companions (file presence is not execution coverage)");
        if (!missing.isEmpty() || !extra.isEmpty() || !malformed.isEmpty())
            throw new AssertionError("HARNESS_DEFECT: native hosts missing=" + missing
                + "; unmatched=" + extra + "; malformed=" + malformed);
    }

    private static void runFrontend(CorpusDiscovery.Fixture fixture) throws Exception {
        // The shared frontend owns rejection; no native build or executable is
        // produced for an expected frontend diagnostic.
        var diagnostics = CorpusConformanceTest.frontendDiagnostics(fixture.file());
        var errors = diagnostics.stream().filter(d -> d.severity().equals("error")).toList();
        var classification = fixture.classification();
        if (classification.kind() == CorpusDiscovery.Kind.COMPILE_OK) {
            if (!errors.isEmpty()) throw new AssertionError("unexpected frontend errors: " + errors);
        } else if (classification.kind() == CorpusDiscovery.Kind.COMPILE_ERROR) {
            Path sidecar = fixture.file().resolveSibling(fixture.file().getFileName()
                .toString().replaceFirst("\\.deal$", ".expect.json"));
            var pin = Files.isRegularFile(sidecar)
                ? SidecarExpectations.CompileDiagnosticPin.parse(Files.readString(sidecar)) : null;
            if (pin != null && !pin.code().equals(classification.code()))
                throw new AssertionError("HARNESS_DEFECT: conflicting frontend code");
            if (errors.stream().noneMatch(d -> classification.code().equals(d.code())
                    && d.file().equals(fixture.file().toString())
                    && (pin == null || (pin.line().isEmpty()
                        || pin.line().getAsInt() == d.line() + fixture.headerLinesStripped())
                        && (pin.column().isEmpty() || pin.column().getAsInt() == d.column())
                        && pin.message().map(m -> m.equals(d.message())).orElse(true))))
                throw new AssertionError("expected pinned " + classification.code() + ": " + errors);
        } else throw new IllegalArgumentException("not a frontend fixture: " + fixture.corpusPath());
    }

    private static void run(Path corpus, CorpusDiscovery.Fixture fixture) throws Exception {
        String json = Files.readString(fixture.sidecarPath(corpus));
        var sidecar = SidecarExpectations.StructuredExpectationSidecar.parse(json);
        var expected = sharedNativeExpectation(sidecar);
        try (var project = new NativeCorpusProject(corpus, fixture, sidecar)) {
            if (!project.compile()) throw new AssertionError("production compile rejected: " + project.compiler.diagnostics());
            var probes = CorpusExecution.probes(project.program(), expected.isRuntimeError());
            List<Path> hosts = project.hostUnits();
            List<String> failures = new ArrayList<>();
            for (int level : new int[]{0, 2}) {
                try {
                    Path work = project.scratch.resolve("run-" + level); Files.createDirectories(work);
                    Path product = work.resolve("driver");
                    NativeCompiler.build(new NativeBuildRequest(project.output, product, level, hosts,
                        driver(project.module(), probes, sidecar.completions())));
                    if (!Files.isExecutable(product)) throw new IllegalStateException("ARTIFACT_MISSING: executable");
                    var result = BoundedProcess.runCaptured(List.of(product.toString()), work, Map.of());
                    if (result.stdoutTruncated() || result.stderrTruncated()) throw new IllegalStateException("HARNESS_DEFECT: truncated capture");
                    boolean loadFailure = expected.isRuntimeError() && expected.error().code().equals("E8011");
                    var receipt = CorpusExecution.receiptVerdict(work, probes, expected.isRuntimeError(), loadFailure);
                    if (receipt.isPresent()) throw new AssertionError(receipt.get() + "; " + result.stdout() + result.stderr());
                    CorpusExecution.completionVerdict(work, probes, sidecar.completions());
                    String stdout = projectTranscript(result.stdout(), project, expected);
                    var mismatch = StructuredExpectationComparator.compare("llvm-O" + level, expected,
                        new LaneExecution.Executed(stdout.getBytes(StandardCharsets.UTF_8),
                            result.stderr().getBytes(StandardCharsets.UTF_8), result.exitCode()));
                    if (mismatch.isPresent()) throw new AssertionError(mismatch.get() + "; exit=" + result.exitCode() + "; stderr=" + result.stderr() + "; stdout=" + result.stdout());
                    System.out.println("  O" + level + " executed: " + fixture.corpusPath());
                } catch (Exception | AssertionError failure) {
                    failures.add("O" + level + ": " + failure);
                }
            }
            if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
            if (accounting != null) accounting.executed(project.compiler.producedProject(), expected.error());
        }
    }

    private static SidecarExpectations.RuntimeExpectation.Executed sharedNativeExpectation(
            SidecarExpectations.StructuredExpectationSidecar sidecar) {
        SidecarExpectations.RuntimeExpectation first = sidecar.byBackend().values().iterator().next();
        if (!(first instanceof SidecarExpectations.RuntimeExpectation.Executed expected))
            throw new IllegalStateException("HARNESS_DEFECT: native execution assertion required for compile-reject expectation");
        for (var value : sidecar.byBackend().values()) {
            if (!(value instanceof SidecarExpectations.RuntimeExpectation.Executed other)
                    || !expected.mode().equals(other.mode()) || expected.exitCode() != other.exitCode()
                    || !Arrays.equals(expected.stdout(), other.stdout()) || !Arrays.equals(expected.stderr(), other.stderr())
                    || !Objects.equals(expected.error(), other.error()))
                throw new IllegalStateException("HARNESS_DEFECT: native assertion required for divergent expectations");
        }
        return expected;
    }

    private static void expectationControls() {
        var first = new SidecarExpectations.RuntimeExpectation.Executed("runtime-ok", new byte[]{65}, new byte[0], 0, null);
        var equal = new SidecarExpectations.RuntimeExpectation.Executed("runtime-ok", new byte[]{65}, new byte[0], 0, null);
        sharedNativeExpectation(new SidecarExpectations.StructuredExpectationSidecar(Map.of("js", first, "jvm", equal)));
        var error = new SidecarExpectations.ErrorExpectation("E8001", "expected number", "control.deal", 1, 1,
            Optional.of("number"), Optional.of("string"), Optional.empty(), Optional.empty());
        for (var different : List.<SidecarExpectations.RuntimeExpectation>of(
                new SidecarExpectations.RuntimeExpectation.Executed("runtime-ok", new byte[]{66}, new byte[0], 0, null),
                new SidecarExpectations.RuntimeExpectation.Executed("runtime-ok", new byte[]{65}, new byte[]{66}, 0, null),
                new SidecarExpectations.RuntimeExpectation.Executed("runtime-ok", new byte[]{65}, new byte[0], 1, null),
                new SidecarExpectations.RuntimeExpectation.Executed("runtime-error", new byte[]{65}, new byte[0], 0, null),
                new SidecarExpectations.RuntimeExpectation.Executed("runtime-ok", new byte[]{65}, new byte[0], 0, error),
                new SidecarExpectations.RuntimeExpectation.Rejected("compile-reject", "E6005",
                    OptionalInt.empty(), OptionalInt.empty()))) {
            boolean rejected = false;
            try { sharedNativeExpectation(new SidecarExpectations.StructuredExpectationSidecar(Map.of("js", first, "jvm", different))); }
            catch (IllegalStateException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("native expectation control accepted divergence: " + different);
        }
        boolean rejected = false;
        try { sharedNativeExpectation(new SidecarExpectations.StructuredExpectationSidecar(Map.of("js",
            new SidecarExpectations.RuntimeExpectation.Rejected("compile-reject", "E6005", OptionalInt.empty(), OptionalInt.empty())))); }
        catch (IllegalStateException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("native expectation control treated rejection as execution");
    }

    private static String normalize(String value, NativeCorpusProject project) {
        if (value == null) return null;
        for (var token : project.tokens.entrySet().stream().sorted((a,b) -> Integer.compare(b.getKey().length(),a.getKey().length())).toList())
            value = value.replace("@conformance/" + token.getKey() + "/", "@conformance/" + token.getValue() + "/");
        return value;
    }

    static String projectTranscript(String stdout, NativeCorpusProject project,
            SidecarExpectations.RuntimeExpectation.Executed expected) {
        if (!expected.isRuntimeError()) return stdout;
        int start = stdout.lastIndexOf(ErrorSnapshot.SNAPSHOT_LINE_PREFIX);
        if (start < 0) return stdout;
        String raw = stdout.substring(start + ErrorSnapshot.SNAPSHOT_LINE_PREFIX.length()).stripTrailing();
        var fields = ErrorSnapshot.extractFields((CanonicalJson.Obj)CanonicalJson.parse(raw));
        String file = fields.sourceFile(); Integer line = fields.line(), column = fields.column();
        var source = project.sources.get(file);
        if (source != null) { file = source.corpusPath(); if (line != null) line += source.headers(); }
        var pins = expected.error();
        var projected = new SidecarExpectations.ErrorExpectation(fields.code(), normalize(fields.message(), project),
            pins.pinsSpan() ? file : null, pins.pinsSpan() ? line : null, pins.pinsSpan() ? column : null,
            pins.pinsOptional("expected") ? fields.expected().map(v -> normalize(v, project)) : Optional.empty(),
            pins.pinsOptional("actual") ? fields.actual().map(v -> normalize(v, project)) : Optional.empty(),
            pins.pinsOptional("frames") ? fields.frames() : Optional.empty(),
            pins.pinsOptional("cause") ? fields.cause() : Optional.empty());
        return stdout.substring(0, start) + ErrorSnapshot.SNAPSHOT_LINE_PREFIX + ErrorSnapshot.canonicalJson(projected) + "\n";
    }

    static String driver(String module, List<CorpusExecution.Probe> probes) {
        return driver(module, probes, Map.of());
    }

    static String driver(String module, List<CorpusExecution.Probe> probes,
            Map<String, SidecarExpectations.CompletionPin> completions) {
        StringBuilder source = new StringBuilder("""
            #include "deal/native/entry.h"
            #include "deal/native/error.h"
            #include <stdio.h>
            #include <stdlib.h>
            static void mark(const char *text) {
                FILE *file = fopen("__corpus_calls.txt", "a");
                if (file == NULL) { fputs("receipt unavailable\\n", stderr); exit(2); }
                fprintf(file, "%s\\n", text); fclose(file);
            }
            static void completion(const char *name, deal_data value) {
                if (value.tag != DEAL_DATA_NUMBER) { fputs("wrong completion kind\\n", stderr); exit(2); }
                FILE *file = fopen("__corpus_completions.txt", "a");
                if (file == NULL) { fputs("completion receipt unavailable\\n", stderr); exit(2); }
                if (fprintf(file, "%s=%.17g\\n", name, value.number) < 0 || fclose(file) != 0) exit(2);
            }
            static int report(deal_error *error) {
                const char *code = NULL;
                if (!deal_error_code(error, &code)) return 2;
                char *snapshot = deal_error_snapshot(error);
                deal_platform_flush_stdout(); deal_platform_flush_stderr();
                printf("DEAL_ERROR_CODE: %s\\nDEAL_ERROR_SNAPSHOT: %s\\n", code, snapshot);
                deal_error_snapshot_release(snapshot); return 1;
            }
            int main(void) {
                (void)completion;
                mark("load:start");
                deal_outcome outcome = deal_project_start();
                if (outcome.error != NULL) return report(outcome.error);
                mark("load:done");
            """);
        source.append("deal_module *module = deal_project_exports(").append(CorpusExecution.quote(module)).append(");\n")
            .append("if (module == NULL) { fputs(\"missing module\\n\",stderr); return 2; }\n");
        for (var probe : probes) {
            source.append("mark(").append(CorpusExecution.quote(probe.name() + ":start")).append(");\n{\n")
                .append("deal_export_entry *entry = deal_export_lookup(module,").append(CorpusExecution.quote(probe.name())).append(");\n")
                .append("if (entry == NULL) { fputs(\"missing export\\n\",stderr); return 2; }\n")
                .append("deal_data value;\n");
            if (completions.containsKey(probe.name())) {
                if (!probe.async()) throw new IllegalArgumentException("completion pin requires async export");
                source.append("outcome = deal_async_export_invoke_value(entry, &value);\n")
                    .append("if (outcome.error != NULL) return report(outcome.error);\ncompletion(")
                    .append(CorpusExecution.quote(probe.name())).append(",value);\n");
            } else if (probe.name().equals("main")) {
                source.append("(void)value; outcome = deal_project_run_main();\n")
                    .append("if (outcome.error != NULL) return report(outcome.error);\n");
            } else source.append("(void)value; outcome = ")
                .append(probe.async() ? "deal_async_export_invoke" : "deal_export_invoke")
                .append("(entry); if (outcome.error != NULL) return report(outcome.error);\n");
            source.append("}\nmark(").append(CorpusExecution.quote(probe.name() + ":done")).append(");\n");
        }
        return source.append("deal_platform_flush_stdout(); deal_platform_flush_stderr(); return 0; }\n").toString();
    }
}
