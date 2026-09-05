package deal.test.conformance;

import deal.test.conformance.V12FeatureCatalog.CatalogResult;
import deal.test.conformance.V12FeatureCatalog.InvocationInfo;
import deal.test.conformance.V12FeatureCatalog.ValidatedRecord;
import deal.test.conformance.V12FeatureMetadata.ExpectedOutcome;
import deal.test.conformance.V12FeatureMetadata.FeatureId;
import deal.test.conformance.V12FeatureMetadata.Invocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The real-catalog corpus gate of the strict v1.2 feature catalog
 * (ISSUE-0157): loads the on-disk feature catalog
 * ({@code test/features}) through {@link V12FeatureCatalog} and pins
 * the complete matrix coverage — every {@link FeatureId} has its
 * mandatory records, the C_FFI linked pair resolves, the async record
 * is async-export, support-only and explicit dual-role ownership close,
 * and the catalog's fixture bytes stay unchanged by the load.
 */
public class V12FeatureCatalogCorpusTest {

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

    public static void main(String[] args) throws IOException {
        Path catalogRoot = Path.of("test", "features").toAbsolutePath().normalize();
        check(Files.isDirectory(catalogRoot),
            "the feature catalog root exists at " + catalogRoot);

        Map<String, String> before = snapshot(catalogRoot);
        CatalogResult result = V12FeatureCatalog.load(catalogRoot);
        check(result.valid(), "the on-disk feature catalog validates clean, got "
            + result.failures());
        Map<String, String> after = snapshot(catalogRoot);
        check(before.equals(after),
            "unchanged fixture-source handling: the catalog load changed no file");

        List<ValidatedRecord> records = result.records();
        check(records.size() == 16, "16 feature records, got " + records.size());

        // Complete matrix coverage: every FeatureId has at least one record.
        Set<FeatureId> covered = EnumSet.noneOf(FeatureId.class);
        for (ValidatedRecord record : records) {
            covered.add(record.metadata().feature());
        }
        EnumSet<FeatureId> missing = EnumSet.allOf(FeatureId.class);
        missing.removeAll(covered);
        check(covered.equals(EnumSet.allOf(FeatureId.class)),
            "every matrix feature has at least one record, missing: "
                + missing);

        // The mandatory int/bytes records run on both backends.
        for (String id : List.of("signed-int32/overflow-runtime",
                "signed-int32/boundary-runtime",
                "bytes-core/buffer-ops-runtime",
                "bytes-defaults/defaults-synthetic",
                "bytes-descriptors/descriptor-runtime",
                "bytes-sync-function/sync-function-synthetic")) {
            ValidatedRecord record = byId(records, id);
            check(record != null, "record " + id + " exists");
            if (record != null) {
                check(record.metadata().backends().equals(
                        List.of("luajit", "jvm")),
                    id + " runs on exactly luajit and jvm");
                check(record.metadata().expected().isRuntimeOutcome(),
                    id + " is a runtime record");
                check(record.invocationInfo().mode()
                        == Invocation.DIRECT_MAIN
                        || record.invocationInfo().mode()
                        == Invocation.SYNTHETIC_MAIN,
                    id + " uses direct-main or synthetic-main");
            }
        }

        // The async bytes record: async-export on both backends with the
        // async()->null oracle.
        ValidatedRecord asyncRecord =
            byId(records, "bytes-async-function/async-oracle-export");
        check(asyncRecord != null, "async record exists");
        if (asyncRecord != null) {
            check(asyncRecord.metadata().invocation() == Invocation.ASYNC_EXPORT,
                "async record uses async-export");
            check(asyncRecord.metadata().backends().equals(
                    List.of("luajit", "jvm")),
                "async record runs on both backends");
            check(asyncRecord.invocationInfo().oracle().isPresent()
                    && asyncRecord.invocationInfo().oracle().orElseThrow()
                        .functionAtom().isAsync(),
                "async record oracle is async");
            check("null".equals(asyncRecord.invocationInfo().oracle()
                    .orElseThrow().returnDescriptor()),
                "async record oracle returns null");
            check(asyncRecord.invocationInfo().requiresMain(),
                "async record requires the non-async main");
        }

        // The C_FFI pair: LuaJIT runtime plus the linked JVM E6003 record.
        ValidatedRecord luajit = byId(records, "c-ffi/luajit-runtime");
        ValidatedRecord jvm = byId(records, "c-ffi/jvm-unsupported");
        check(luajit != null && jvm != null, "the C_FFI pair exists");
        if (luajit != null && jvm != null) {
            check(luajit.metadata().backends().equals(List.of("luajit")),
                "C_FFI runtime runs exactly on LuaJIT");
            check(luajit.invocationInfo().linkedRecordTarget().isPresent()
                    && "c-ffi/jvm-unsupported".equals(
                        luajit.invocationInfo().linkedRecordTarget().get()),
                "C_FFI runtime links the JVM rejection record");
            check(jvm.metadata().expected()
                    instanceof ExpectedOutcome.CompileError e
                    && FeatureBackendMatrix.FFI_UNSUPPORTED_BACKEND_CODE
                        .equals(e.code()),
                "the linked JVM record pins "
                    + FeatureBackendMatrix.FFI_UNSUPPORTED_BACKEND_CODE);
            check(jvm.metadata().backends().equals(List.of("jvm")),
                "the linked JVM record runs exactly on JVM");
        }

        // Declaration-error records: compile-error, generated-entry roots.
        ValidatedRecord dup = byId(records,
            "c-ffi-declaration-error/duplicate-marker-rejected");
        ValidatedRecord backendSpecific = byId(records,
            "c-ffi-declaration-error/backend-specific");
        check(dup != null && backendSpecific != null,
            "the declaration-error records exist");
        if (dup != null) {
            check(dup.invocationInfo().needsGeneratedEntry(),
                "the .d.deal compile-error root needs a generated entry");
            check(dup.metadata().expected()
                    instanceof ExpectedOutcome.CompileError e
                    && "E7002".equals(e.code()),
                "the declaration-error record pins E7002");
        }
        if (backendSpecific != null) {
            check(backendSpecific.metadata().backends().equals(List.of("jvm")),
                "the backend-specific declaration-error record names jvm");
        }

        // Compiler-feature records run on each applicable pipeline.
        for (String id : List.of("directives/jsonable-argument-rejected",
                "directives/jsonable-warning-ok",
                "diagnostic-range/int-literal-range",
                "project-config/manifest-ok",
                "project-config/out-of-root-class-rejected")) {
            ValidatedRecord record = byId(records, id);
            check(record != null, "record " + id + " exists");
            if (record != null) {
                check(record.metadata().backends().equals(
                        List.of("luajit", "jvm")),
                    id + " runs on each applicable backend pipeline");
                check(record.metadata().invocation()
                        == Invocation.COMPILE_ONLY,
                    id + " is compile-only");
            }
        }

        // Support-only and dual-role ownership.
        ValidatedRecord ops = byId(records, "bytes-core/buffer-ops-runtime");
        ValidatedRecord defaults =
            byId(records, "bytes-defaults/defaults-synthetic");
        ValidatedRecord sync =
            byId(records, "bytes-sync-function/sync-function-synthetic");
        check(ops != null && defaults != null && sync != null,
            "the support-graph records exist");
        if (ops != null) {
            check(ops.metadata().support().equals(
                    List.of("bytes-core/helper/bytes-ops.deal")),
                "the support-only source is reachable through the root's "
                    + "support graph");
            check(byId(records, "bytes-core/helper/bytes-ops") == null,
                "the support-only source has no record of its own");
        }
        if (defaults != null) {
            check(defaults.metadata().support().equals(
                    List.of("bytes-core/helper/bytes-ops.deal")),
                "the defaults record lists its transitive support");
        }
        if (sync != null) {
            check(sync.metadata().support().equals(List.of(
                    "bytes-defaults/defaults-synthetic.deal",
                    "bytes-core/helper/bytes-ops.deal")),
                "explicit dual-role ownership: the sync record lists the "
                    + "rooted defaults module it imports plus its "
                    + "transitive support");
        }

        // Invocation info is complete for later execution: no record
        // leaves the oracle or mode undetermined.
        for (ValidatedRecord record : records) {
            InvocationInfo info = record.invocationInfo();
            check(info.mode() != null && !info.backends().isEmpty(),
                "record " + record.id() + " carries a complete invocation plan");
            if (record.metadata().invocation() == Invocation.SYNTHETIC_MAIN
                    || record.metadata().invocation()
                        == Invocation.ASYNC_EXPORT) {
                check(info.oracle().isPresent()
                        && info.oracle().orElseThrow().returnDescriptor()
                            != null,
                    "record " + record.id() + " carries its oracle plan");
            }
            if (record.metadata().feature() == FeatureId.C_FFI
                    && record.metadata().expected().isRuntimeOutcome()) {
                check(info.linkedRecordTarget().isPresent(),
                    "record " + record.id() + " carries its resolved link");
            }
        }

        System.out.println();
        if (failed > 0) {
            System.err.println("V12FeatureCatalogCorpusTest: " + passed
                + " passed, " + failed + " failed");
            System.exit(1);
        }
        System.out.println("V12FeatureCatalogCorpusTest: " + passed
            + " passed, 0 failed");
    }

    private static ValidatedRecord byId(List<ValidatedRecord> records, String id) {
        for (ValidatedRecord record : records) {
            if (id.equals(record.id())) {
                return record;
            }
        }
        return null;
    }

    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .sorted(Comparator.naturalOrder())
                .forEach(p -> {
                    try {
                        files.put(p.toString(), Files.readString(p));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
        }
        return files;
    }
}
