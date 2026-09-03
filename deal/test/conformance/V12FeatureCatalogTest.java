package deal.test.conformance;

import deal.test.conformance.V12FeatureCatalog.CatalogFailure;
import deal.test.conformance.V12FeatureCatalog.CatalogResult;
import deal.test.conformance.V12FeatureCatalog.InvocationInfo;
import deal.test.conformance.V12FeatureCatalog.ValidatedRecord;
import deal.test.conformance.V12FeatureMetadata.Invocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The dedicated unit battery of the strict v1.2 feature catalog loader
 * (ISSUE-0157, {@link V12FeatureCatalog}): a self-contained synthetic
 * catalog tree covering all eleven matrix features plus support-only
 * and explicit dual-role ownership, and a negative matrix for every
 * closure/ownership/matrix/metadata-rejection rule.
 *
 * <p>The suite is standalone: it builds its synthetic catalogs under a
 * fresh temporary directory, runs from any working directory, performs
 * read-only I/O over the loaded catalogs (unchanged fixture-source
 * handling is asserted), and deletes the tree at the end.</p>
 */
public class V12FeatureCatalogTest {

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

    private static Path tempRoot;

    private static void write(Path root, String rel, String content) {
        try {
            Path target = root.resolve(rel);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write synthetic file: " + e, e);
        }
    }

    private static String sidecar(String feature, String expected,
            String backends, String invocation, String oracle,
            List<String> support, String linked) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"version\": 1, \"feature\": \"").append(feature)
            .append("\", \"spec\": \"spec-v1.2.md §synthetic\", ")
            .append("\"description\": \"synthetic ").append(feature)
            .append("\", ");
        if (expected.startsWith("compile-error ")
                || expected.startsWith("runtime-error ")) {
            int space = expected.indexOf(' ');
            sb.append("\"expected\": {\"").append(expected, 0, space)
                .append("\": \"").append(expected.substring(space + 1))
                .append("\"}, ");
        } else {
            sb.append("\"expected\": \"").append(expected).append("\", ");
        }
        sb.append("\"backends\": ").append(backends)
            .append(", \"invocation\": \"").append(invocation).append("\", ");
        if (oracle != null) {
            sb.append("\"oracle\": {\"exportName\": \"")
                .append(oracle.substring(0, oracle.indexOf('#')))
                .append("\", \"functionDescriptor\": \"")
                .append(oracle.substring(oracle.indexOf('#') + 1))
                .append("\"}, ");
        }
        sb.append("\"support\": [");
        for (int i = 0; i < support.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('"').append(support.get(i)).append('"');
        }
        sb.append(']');
        if (linked != null) {
            sb.append(", \"linkedRecord\": \"").append(linked).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private static final String MAIN_ONLY = """
        export function main(): null {
          return null;
        }
        """;

    private static final String INT_OVERFLOW = """
        export function main(): null {
          let x: int = 2147483647 + 1;
          return null;
        }
        """;

    private static final String BYTES_OK = """
        export function main(): null {
          let b: bytes = bytes(2);
          b[0] = 7;
          if (b[0] !== 7) {
            throw { code: "TEST_FAIL", message: "mismatch" };
          }
          return null;
        }
        """;

    private static final String DEFAULTS_ORACLE = """
        class Box {
          data: bytes = bytes(1);
        }

        export function defaultsOracle(): int {
          let a: Box = {};
          return a.data.length;
        }
        """;

    private static final String DESCRIPTOR_OK = """
        export function main(): null {
          let b: bytes = bytes(3);
          if (b.length !== 3) {
            throw { code: "TEST_FAIL", message: "length" };
          }
          return null;
        }
        """;

    private static final String SYNC_ORACLE = """
        import * as Defaults from "../bytes-defaults/defaults"

        export function syncFunctionOracle(): int {
          let f: (x: bytes) => bytes = function(x: bytes): bytes {
            x[0] = 1;
            return x;
          };
          let b: bytes = bytes(1);
          let out: bytes = f(b);
          return out[0] + Defaults.defaultsOracle();
        }
        """;

    private static final String ASYNC_ORACLE = """
        export function main(): null {
          return null;
        }

        export async function oracle(): null {
          let f: async (b: bytes) => bytes = async function(b: bytes): bytes {
            b[0] = 9;
            return b;
          };
          let b: bytes = bytes(1);
          let out: bytes = await f(b);
          if (out[0] !== 9) {
            throw { code: "TEST_FAIL", message: "async bytes" };
          }
          return null;
        }
        """;

    private static final String INT_RANGE = """
        export function main(): null {
          let x: int = 2147483648;
          return null;
        }
        """;

    private static final String FFI_RUNTIME = """
        import * as ffi from "./add"

        export function main(): null {
          let s: int = ffi.native_add(1, 2);
          return null;
        }
        """;

    private static final String FFI_DECL = """
        // @extern-c
        export function native_add(a: int, b: int): int;
        """;

    private static final String DUP_MARKER = """
        // @extern-c

        // @c-struct
        // @c-pointer
        export class DoubleMarked {
          x: number = 0.0;
        }
        """;

    private static final String FN_MARKER = """
        // @extern-c

        // @c-struct
        export function marked(a: int): int;
        """;

    private static final String BYTES_HELPER = """
        export function firstByte(b: bytes): int {
          return b[0];
        }
        """;

    /**
     * Builds the valid synthetic catalog: all eleven matrix features,
     * a support-only source (bytes-core/helper/ops.deal), and explicit
     * dual-role ownership (bytes-sync-function lists the rooted
     * bytes-defaults module it imports).
     */
    private static void buildValidCatalog(Path root) {
        write(root, "signed-int32/overflow.deal", INT_OVERFLOW);
        write(root, "signed-int32/overflow.feature.json", sidecar("SIGNED_INT32",
            "runtime-error E8004", "[\"luajit\", \"jvm\"]", "direct-main",
            null, List.of(), null));
        write(root, "bytes-core/ops.deal", """
            import * as Helper from "./helper/ops"

            export function main(): null {
              let b: bytes = bytes(1);
              b[0] = 5;
              if (Helper.firstByte(b) !== 5) {
                throw { code: "TEST_FAIL", message: "helper" };
              }
              return null;
            }
            """);
        write(root, "bytes-core/ops.feature.json", sidecar("BYTES_CORE",
            "runtime-ok", "[\"luajit\", \"jvm\"]", "direct-main", null,
            List.of("bytes-core/helper/ops.deal"), null));
        write(root, "bytes-core/helper/ops.deal", BYTES_HELPER);
        write(root, "bytes-defaults/defaults.deal", DEFAULTS_ORACLE);
        write(root, "bytes-defaults/defaults.feature.json", sidecar(
            "BYTES_DEFAULTS", "runtime-ok", "[\"luajit\", \"jvm\"]",
            "synthetic-main", "defaultsOracle#()->int", List.of(), null));
        write(root, "bytes-descriptors/descriptor.deal", DESCRIPTOR_OK);
        write(root, "bytes-descriptors/descriptor.feature.json", sidecar(
            "BYTES_DESCRIPTORS", "runtime-ok", "[\"luajit\", \"jvm\"]",
            "direct-main", null, List.of(), null));
        write(root, "bytes-sync-function/sync.deal", SYNC_ORACLE);
        write(root, "bytes-sync-function/sync.feature.json", sidecar(
            "BYTES_SYNC_FUNCTION", "runtime-ok", "[\"luajit\", \"jvm\"]",
            "synthetic-main", "syncFunctionOracle#()->int",
            List.of("bytes-defaults/defaults.deal"), null));
        write(root, "bytes-async-function/async.deal", ASYNC_ORACLE);
        write(root, "bytes-async-function/async.feature.json", sidecar(
            "BYTES_ASYNC_FUNCTION", "runtime-ok", "[\"luajit\", \"jvm\"]",
            "async-export", "oracle#async()->null", List.of(), null));
        write(root, "directives/warning-ok.deal", MAIN_ONLY);
        write(root, "directives/warning-ok.feature.json", sidecar(
            "DIRECTIVES", "compile-ok", "[\"luajit\", \"jvm\"]",
            "compile-only", null, List.of(), null));
        write(root, "diagnostic-range/range.deal", INT_RANGE);
        write(root, "diagnostic-range/range.feature.json", sidecar(
            "DIAGNOSTIC_RANGE", "compile-error E1036", "[\"luajit\", \"jvm\"]",
            "compile-only", null, List.of(), null));
        write(root, "project-config/ok.deal", MAIN_ONLY);
        write(root, "project-config/ok.feature.json", sidecar(
            "PROJECT_CONFIG", "compile-ok", "[\"luajit\", \"jvm\"]",
            "compile-only", null, List.of(), null));
        write(root, "c-ffi/add.d.deal", FFI_DECL);
        write(root, "c-ffi/luajit.deal", FFI_RUNTIME);
        write(root, "c-ffi/luajit.feature.json", sidecar("C_FFI",
            "runtime-ok", "[\"luajit\"]", "direct-main", null,
            List.of("c-ffi/add.d.deal"), "c-ffi/jvm"));
        write(root, "c-ffi/jvm.deal", FFI_RUNTIME);
        write(root, "c-ffi/jvm.feature.json", sidecar("C_FFI",
            "compile-error E6006", "[\"jvm\"]", "compile-only", null,
            List.of("c-ffi/add.d.deal"), null));
        write(root, "c-ffi-declaration-error/dup.d.deal", DUP_MARKER);
        write(root, "c-ffi-declaration-error/dup.feature.json", sidecar(
            "C_FFI_DECLARATION_ERROR", "compile-error E7002",
            "[\"luajit\", \"jvm\"]", "compile-only", null, List.of(), null));
        write(root, "c-ffi-declaration-error/jvm-specific.d.deal", FN_MARKER);
        write(root, "c-ffi-declaration-error/jvm-specific.feature.json",
            sidecar("C_FFI_DECLARATION_ERROR", "compile-error E7002",
                "[\"jvm\"]", "compile-only", null, List.of(), null));
    }

    /** Snapshot of every file under the catalog (content bytes). */
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

    private static Optional<CatalogFailure> failure(CatalogResult result,
            String recordIdFragment, String field, String reasonFragment) {
        for (CatalogFailure failure : result.failures()) {
            if (failure.recordId().contains(recordIdFragment)
                    && field.equals(failure.field())
                    && failure.reason().contains(reasonFragment)) {
                return Optional.of(failure);
            }
        }
        return Optional.empty();
    }

    public static void main(String[] args) throws IOException {
        System.out.println("-- V12FeatureCatalog: valid synthetic catalog --");
        tempRoot = Files.createTempDirectory("deal-feature-catalog-test");
        Path valid = tempRoot.resolve("valid");
        buildValidCatalog(valid);
        Map<String, String> before = snapshot(valid);
        CatalogResult result = V12FeatureCatalog.load(valid);
        check(result.valid(), "valid synthetic catalog validates clean, got "
            + result.failures());
        check(result.records().size() == 13,
            "13 validated records, got " + result.records().size());
        Map<String, String> after = snapshot(valid);
        check(before.equals(after),
            "unchanged fixture-source handling: no catalog file changed");

        for (ValidatedRecord record : result.records()) {
            InvocationInfo info = record.invocationInfo();
            switch (record.id()) {
                case "bytes-async-function/async" -> {
                    check(info.mode() == Invocation.ASYNC_EXPORT,
                        "async record mode");
                    check(info.oracle().isPresent()
                            && "oracle".equals(info.oracle().get().exportName())
                            && "null".equals(info.oracle().get()
                                .returnDescriptor()),
                        "async record oracle plan");
                    check(info.requiresMain(), "async record requires main");
                    check(!info.needsGeneratedEntry(),
                        "async record needs no generated entry");
                }
                case "bytes-defaults/defaults" -> {
                    check(info.mode() == Invocation.SYNTHETIC_MAIN,
                        "synthetic record mode");
                    check("defaultsOracle".equals(info.oracle().orElseThrow()
                            .exportName())
                            && "int".equals(info.oracle().orElseThrow()
                                .returnDescriptor()),
                        "synthetic record oracle plan");
                    check(!info.requiresMain(), "synthetic record needs no main");
                }
                case "bytes-sync-function/sync" -> {
                    check(record.supportSources().size() == 1,
                        "dual-role support resolved: "
                            + record.supportSources());
                    check("int".equals(info.oracle().orElseThrow()
                        .returnDescriptor()),
                        "sync oracle return descriptor");
                }
                case "c-ffi/luajit" -> {
                    check(info.linkedRecordTarget().isPresent()
                            && "c-ffi/jvm".equals(
                                info.linkedRecordTarget().get()),
                        "C_FFI runtime link target");
                    check(info.backends().equals(List.of("luajit")),
                        "C_FFI runtime backend set");
                    check(info.requiresMain(), "C_FFI runtime requires main");
                }
                case "c-ffi/jvm" -> {
                    check(info.mode() == Invocation.COMPILE_ONLY,
                        "C_FFI rejection compile-only");
                    check(!info.needsGeneratedEntry(),
                        "C_FFI rejection root is a .deal entry");
                }
                case "c-ffi-declaration-error/dup",
                     "c-ffi-declaration-error/jvm-specific" -> {
                    check(info.needsGeneratedEntry(),
                        record.id() + " needs a generated entry (.d.deal root)");
                    check(info.mode() == Invocation.COMPILE_ONLY,
                        record.id() + " compile-only");
                }
                default -> { }
            }
        }
        check(result.records().stream()
                .anyMatch(r -> r.id().equals("bytes-core/ops")
                    && r.supportSources().size() == 1),
            "support-only source resolved through the root's support graph");
        check(result.records().stream().noneMatch(r ->
                r.id().equals("bytes-core/helper/ops")),
            "support-only source has no record of its own");

        // =========================================================================
        System.out.println("-- V12FeatureCatalog: closure negatives --");

        // 1. Sidecar without source.
        Path noSource = tempRoot.resolve("no-source");
        buildValidCatalog(noSource);
        try {
            Files.delete(noSource.resolve("bytes-core/ops.deal"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        result = V12FeatureCatalog.load(noSource);
        check(!result.valid(), "sidecar without source fails");
        check(failure(result, "bytes-core/ops", "<source>",
            "does not exist").isPresent(),
            "sidecar-without-source failure names the record: "
                + result.failures());

        // 2. Unlisted source.
        Path unlisted = tempRoot.resolve("unlisted");
        buildValidCatalog(unlisted);
        write(unlisted, "stray/stray.deal", MAIN_ONLY);
        result = V12FeatureCatalog.load(unlisted);
        check(!result.valid(), "unlisted source fails");
        check(failure(result, "<catalog>", "<source>", "unlisted source")
            .isPresent(), "unlisted-source failure names the path: "
                + result.failures());

        // 3. Unresolved support edge.
        Path unresolved = tempRoot.resolve("unresolved");
        buildValidCatalog(unresolved);
        write(unresolved, "bytes-core/ops.feature.json", sidecar("BYTES_CORE",
            "runtime-ok", "[\"luajit\", \"jvm\"]", "direct-main", null,
            List.of("bytes-core/missing.deal"), null));
        result = V12FeatureCatalog.load(unresolved);
        check(!result.valid(), "unresolved support edge fails");
        check(failure(result, "bytes-core/ops", "<source>",
            "unresolved support edge").isPresent(),
            "unresolved-support failure: " + result.failures());

        // 4. Unused support entry.
        Path unused = tempRoot.resolve("unused");
        buildValidCatalog(unused);
        write(unused, "bytes-descriptors/descriptor.feature.json", sidecar(
            "BYTES_DESCRIPTORS", "runtime-ok", "[\"luajit\", \"jvm\"]",
            "direct-main", null,
            List.of("bytes-core/helper/ops.deal"), null));
        result = V12FeatureCatalog.load(unused);
        check(!result.valid(), "unused support entry fails");
        check(failure(result, "bytes-descriptors/descriptor", "<source>",
            "unused support").isPresent(),
            "unused-support failure: " + result.failures());

        // 5. Unlisted import (a module imported but not in support).
        Path unlistedImport = tempRoot.resolve("unlisted-import");
        buildValidCatalog(unlistedImport);
        write(unlistedImport, "bytes-core/ops.deal", """
            import * as Helper from "./helper/ops"
            import * as Other from "./helper/other"

            export function main(): null {
              return null;
            }
            """);
        write(unlistedImport, "bytes-core/helper/other.deal", BYTES_HELPER);
        write(unlistedImport, "bytes-core/ops.feature.json", sidecar(
            "BYTES_CORE", "runtime-ok", "[\"luajit\", \"jvm\"]",
            "direct-main", null, List.of("bytes-core/helper/ops.deal"),
            null));
        result = V12FeatureCatalog.load(unlistedImport);
        check(!result.valid(), "unlisted import fails");
        check(failure(result, "bytes-core/ops", "<source>",
            "unlisted support").isPresent(),
            "unlisted-import failure: " + result.failures());

        // 6. Support entry naming the record's own root source.
        Path ownRoot = tempRoot.resolve("own-root");
        buildValidCatalog(ownRoot);
        write(ownRoot, "bytes-core/ops.feature.json", sidecar("BYTES_CORE",
            "runtime-ok", "[\"luajit\", \"jvm\"]", "direct-main", null,
            List.of("bytes-core/ops.deal"), null));
        result = V12FeatureCatalog.load(ownRoot);
        check(!result.valid(), "support naming own root fails");
        check(failure(result, "bytes-core/ops", "<source>",
            "own root source").isPresent(),
            "own-root support failure: " + result.failures());

        // 7. Duplicate canonical alias: a symlinked source claimed twice.
        Path alias = tempRoot.resolve("alias");
        buildValidCatalog(alias);
        write(alias, "alias/extra.deal", MAIN_ONLY);
        write(alias, "alias/extra.feature.json", sidecar("SIGNED_INT32",
            "runtime-ok", "[\"luajit\", \"jvm\"]", "direct-main", null,
            List.of(), null));
        try {
            Files.createSymbolicLink(alias.resolve("alias/twin.deal"),
                alias.resolve("alias/extra.deal").getFileName());
        } catch (IOException | UnsupportedOperationException e) {
            check(false, "symlink creation failed: " + e);
        }
        write(alias, "alias/twin.feature.json", sidecar("SIGNED_INT32",
            "runtime-ok", "[\"luajit\", \"jvm\"]", "direct-main", null,
            List.of(), null));
        result = V12FeatureCatalog.load(alias);
        check(!result.valid(), "duplicate canonical alias fails");
        check(failure(result, "alias/", "<source>",
            "duplicate canonical alias").isPresent(),
            "duplicate-alias failure: " + result.failures());

        // =========================================================================
        System.out.println("-- V12FeatureCatalog: matrix/expectation negatives --");

        // 8. Backend omission for a parity feature.
        Path omitJvm = tempRoot.resolve("omit-jvm");
        buildValidCatalog(omitJvm);
        write(omitJvm, "bytes-core/ops.feature.json", sidecar("BYTES_CORE",
            "runtime-ok", "[\"luajit\"]", "direct-main", null,
            List.of("bytes-core/helper/ops.deal"), null));
        result = V12FeatureCatalog.load(omitJvm);
        check(!result.valid(), "backend omission fails");
        check(failure(result, "bytes-core/ops", "backends",
            "exactly LuaJIT and JVM").isPresent(),
            "backend-omission failure: " + result.failures());

        // 9. Backend addition for C_FFI (JVM runtime record).
        Path addJvm = tempRoot.resolve("add-jvm");
        buildValidCatalog(addJvm);
        write(addJvm, "c-ffi/luajit.feature.json", sidecar("C_FFI",
            "runtime-ok", "[\"luajit\", \"jvm\"]", "direct-main", null,
            List.of("c-ffi/add.d.deal"), "c-ffi/jvm"));
        result = V12FeatureCatalog.load(addJvm);
        check(!result.valid(), "backend addition fails");
        check(failure(result, "c-ffi/luajit", "backends",
            "runs exactly on LuaJIT").isPresent(),
            "backend-addition failure: " + result.failures());

        // 10. Missing linked E6006 record (link to a nonexistent id).
        Path missingLink = tempRoot.resolve("missing-link");
        buildValidCatalog(missingLink);
        write(missingLink, "c-ffi/luajit.feature.json", sidecar("C_FFI",
            "runtime-ok", "[\"luajit\"]", "direct-main", null,
            List.of("c-ffi/add.d.deal"), "c-ffi/nonexistent"));
        result = V12FeatureCatalog.load(missingLink);
        check(!result.valid(), "missing linked record fails");
        check(failure(result, "c-ffi/luajit", "linkedRecord",
            "does not exist").isPresent(),
            "missing-link failure: " + result.failures());

        // 11. Async bytes mislabelled as direct-main.
        Path asyncDirect = tempRoot.resolve("async-direct");
        buildValidCatalog(asyncDirect);
        write(asyncDirect, "bytes-async-function/async.feature.json", sidecar(
            "BYTES_ASYNC_FUNCTION", "runtime-ok", "[\"luajit\", \"jvm\"]",
            "direct-main", null, List.of(), null));
        result = V12FeatureCatalog.load(asyncDirect);
        check(!result.valid(), "async-as-direct-main fails");
        check(failure(result, "bytes-async-function/async", "invocation",
            "requires exactly async-export").isPresent(),
            "async-direct failure: " + result.failures());

        // 12. Compile-only runtime record.
        Path compileRuntime = tempRoot.resolve("compile-runtime");
        buildValidCatalog(compileRuntime);
        write(compileRuntime, "bytes-core/ops.feature.json", sidecar(
            "BYTES_CORE", "runtime-ok", "[\"luajit\", \"jvm\"]",
            "compile-only", null, List.of("bytes-core/helper/ops.deal"),
            null));
        result = V12FeatureCatalog.load(compileRuntime);
        check(!result.valid(), "compile-only runtime record fails");
        check(failure(result, "bytes-core/ops", "invocation",
            "forbids compile-only").isPresent(),
            "compile-only-runtime failure: " + result.failures());

        // 13. Illegal oracle shape: async descriptor on synthetic-main.
        Path badOracle = tempRoot.resolve("bad-oracle");
        buildValidCatalog(badOracle);
        write(badOracle, "bytes-defaults/defaults.feature.json", sidecar(
            "BYTES_DEFAULTS", "runtime-ok", "[\"luajit\", \"jvm\"]",
            "synthetic-main", "defaultsOracle#async()->int", List.of(),
            null));
        result = V12FeatureCatalog.load(badOracle);
        check(!result.valid(), "illegal oracle shape fails");
        check(failure(result, "bytes-defaults/defaults",
            "oracle.functionDescriptor", "exact ()->R").isPresent(),
            "oracle-shape failure: " + result.failures());

        // 14. Production // @spec: metadata yields E1044 (no stripping).
        Path specFixture = tempRoot.resolve("spec-fixture");
        buildValidCatalog(specFixture);
        write(specFixture, "bytes-descriptors/descriptor.deal",
            "// @spec: Spec section\n" + DESCRIPTOR_OK);
        result = V12FeatureCatalog.load(specFixture);
        check(!result.valid(), "production // @spec: metadata fails");
        check(failure(result, "bytes-descriptors/descriptor", "<source>",
            "E1044").isPresent(),
            "spec-rejection failure: " + result.failures());

        // 15. Direct-main record without a non-async main(): null export.
        Path noMain = tempRoot.resolve("no-main");
        buildValidCatalog(noMain);
        write(noMain, "bytes-core/ops.deal", """
            import * as Helper from "./helper/ops"

            export function notMain(): null {
              return null;
            }
            """);
        result = V12FeatureCatalog.load(noMain);
        check(!result.valid(), "missing main fails");
        check(failure(result, "bytes-core/ops", "<source>",
            "non-async zero-parameter main(): null").isPresent(),
            "missing-main failure: " + result.failures());

        // 16. Synthetic-main record whose oracle export does not exist.
        Path noOracle = tempRoot.resolve("no-oracle");
        buildValidCatalog(noOracle);
        write(noOracle, "bytes-defaults/defaults.deal",
            "export function other(): int { return 1; }\n");
        result = V12FeatureCatalog.load(noOracle);
        check(!result.valid(), "missing oracle export fails");
        check(failure(result, "bytes-defaults/defaults", "<source>",
            "does not exist in the root source").isPresent(),
            "missing-oracle failure: " + result.failures());

        // 17. Unlinked C_FFI rejection record (no runtime record links it).
        Path unlinkedReject = tempRoot.resolve("unlinked-reject");
        buildValidCatalog(unlinkedReject);
        write(unlinkedReject, "c-ffi/luajit.feature.json", sidecar("C_FFI",
            "runtime-ok", "[\"luajit\"]", "direct-main", null,
            List.of("c-ffi/add.d.deal"), null));
        result = V12FeatureCatalog.load(unlinkedReject);
        check(!result.valid(), "unlinked rejection record fails");
        check(failure(result, "c-ffi/luajit", "linkedRecord",
            "must link the JVM").isPresent()
                || failure(result, "c-ffi/jvm", "linkedRecord",
                    "not the target").isPresent(),
            "unlinked-rejection failures: " + result.failures());

        // 18. A stray sidecar is discovered and fails for its missing source.
        Path straySidecar = tempRoot.resolve("stray-sidecar");
        buildValidCatalog(straySidecar);
        write(straySidecar, "stray/nothing.feature.json", sidecar(
            "SIGNED_INT32", "runtime-ok", "[\"luajit\", \"jvm\"]",
            "direct-main", null, List.of(), null));
        result = V12FeatureCatalog.load(straySidecar);
        check(!result.valid(), "stray sidecar without source fails");
        check(failure(result, "stray/nothing", "<source>",
            "does not exist").isPresent(),
            "stray-sidecar failure: " + result.failures());

        // =========================================================================
        // Cleanup
        // =========================================================================
        try (var walk = Files.walk(tempRoot)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        System.out.println();
        if (failed > 0) {
            System.err.println("V12FeatureCatalogTest: " + passed + " passed, "
                + failed + " failed");
            System.exit(1);
        }
        System.out.println("V12FeatureCatalogTest: " + passed + " passed, 0 failed");
    }
}
