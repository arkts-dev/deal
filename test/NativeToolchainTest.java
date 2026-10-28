package deal.test;

import deal.Main;
import deal.toolchain.HostsRegistry;
import deal.toolchain.NativeBuildException;
import deal.toolchain.NativeBuildPlan;
import deal.toolchain.NativeBuildRequest;
import deal.toolchain.NativeBuildResult;
import deal.toolchain.NativeRuntime;
import deal.toolchain.NativeToolchain;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The native toolchain seam
 * ({@code llvm-native-backend-integration} D3/D4/D5, {@code NativeBuild},
 * {@code ToolchainPreflight}; {@code llvm-native-host-abi-and-async} H1/H2/H6,
 * {@code llvm-native-conformance-gate} G8).
 *
 * <p>Every case drives production surfaces only: the real publication of the
 * native arm (compiled through {@code deal.Main}'s own resolution and compile
 * path) and the real {@link NativeToolchain} invocation of the pinned
 * compiler. No case mocks the argument vector or a build product; a
 * filesystem/process fault is created (an absent compiler directory, a
 * replacing wrapper, a deleted runtime copy, a corrupt module, a defective
 * host unit) and the seam's named failure and transactional postconditions are
 * asserted against it.</p>
 *
 * <p>Covered: the exact D4 argument vector and both translation-unit sets, the
 * empty and nonempty generated registries, the driver shape's process-entry
 * ownership, the product lifetime (one rename, prior product preserved on
 * failure, unrelated products untouched, scratch and partial removed), the
 * fail-closed preflight conditions, the CLI's defaults/options/usage/`--host`
 * and failure reporting, and the absence of publication mutation.</p>
 */
public final class NativeToolchainTest {

    private static final Path REPO = NativeCompiler.repository();

    private static final String APP_SOURCE = """
        import * as lib from "./lib";

        export function main(): null {
          let ignored: null = lib.ping();
          return ignored;
        }
        """;

    private static final String LIB_SOURCE = """
        function twice(a: number): number {
          return a + a;
        }

        export function ping(): null {
          let total: number = twice(2);
          let i: number = 0;
          while (i < total) {
            i = i + 1;
          }
          if (i > 3) {
            i = i - 1;
          }
          return null;
        }
        """;

    /** A driver that owns the process entry and the ordered receipts (H6/G3). */
    private static final String DRIVER_SOURCE = """
        #include "deal/native/entry.h"
        #include "deal/native/platform.h"

        #include <string.h>

        int main(void) {
            deal_platform_write_stdout("load:start\\n", strlen("load:start\\n"));
            deal_outcome started = deal_project_start();
            if (started.error != NULL) {
                return 1;
            }
            deal_platform_write_stdout("load:done\\n", strlen("load:done\\n"));
            deal_platform_write_stdout("main:start\\n", strlen("main:start\\n"));
            deal_outcome invoked = deal_project_run_main();
            if (invoked.error != NULL) {
                return 1;
            }
            deal_platform_write_stdout("main:done\\n", strlen("main:done\\n"));
            deal_platform_flush_stdout();
            return 0;
        }
        """;

    /** The synthetic published host declaration header of this task's tests. */
    private static final String HTTP_HEADER = """
        #ifndef DEAL_HOST_HTTP_H
        #define DEAL_HOST_HTTP_H

        #include "deal/native/deal_rt.h"

        extern const deal_host_module deal_host_module_http;

        #endif /* DEAL_HOST_HTTP_H */
        """;

    /** The synthetic host unit that defines that registration record once. */
    private static final String HTTP_UNIT = """
        #include "host/http.h"

        const deal_host_module deal_host_module_http = {
            "host/http", NULL, 0, NULL, 0, NULL
        };
        """;

    private static final String SOCKET_HEADER = """
        #ifndef DEAL_HOST_SOCKET_H
        #define DEAL_HOST_SOCKET_H

        #include "deal/native/deal_rt.h"

        extern const deal_host_module deal_host_module_socket;

        #endif /* DEAL_HOST_SOCKET_H */
        """;

    private static final String SOCKET_UNIT = """
        #include "net/socket.h"

        const deal_host_module deal_host_module_socket = {
            "net/socket", NULL, 0, NULL, 0, NULL
        };
        """;

    private static int passed;
    private static int failed;

    /**
     * The {@code deal-native-*} entries of {@code java.io.tmpdir} before the
     * suite's first build: every case's cleanup is asserted against it, so no
     * build scratch survives any path.
     */
    private static Set<String> scratchBaseline = new LinkedHashSet<>();

    public static void main(String[] args) throws Exception {
        ToolchainPreflight.require("clang", "--version",
            "the native toolchain seam passes");
        ToolchainPreflight.require("llvm-dwarfdump", "--version",
            "the native product debug check passes");
        System.out.println("=== Native Toolchain Seam Tests (ISSUE-0904) ===\n");
        scratchBaseline = buildScratchEntries();
        Path scratch = NativeCompiler.executableScratch("native-toolchain");
        try {
            Path projectRoot = scratch.resolve("project");
            Path published = compileProject(projectRoot);
            testPinnedArgumentVectorProductShape(scratch, published);
            testDriverShape(scratch, published);
            testRegistryShapes(scratch, published);
            testPreflightRejections(scratch, published);
            testProductLifetime(scratch, published);
            testStagingPathNeverDiagnosed(scratch, published);
            testPublicationIsReadOnly(scratch, published);
            testCliDefaultsAndOptions(scratch);
            testCliRejections(scratch);
            testCliMissingTool(scratch);
            testCliUnsupportedMajor(scratch);
            testUsageText();
            testValueShapes(scratch, published);
        } finally {
            deleteRecursively(scratch);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== Native Toolchain Seam Tests Passed ===");
    }

    // =========================================================================
    // 1. The exact D4 argument vector and the product translation-unit set
    // =========================================================================

    private static void testPinnedArgumentVectorProductShape(Path scratch,
            Path published) throws Exception {
        System.out.println("-- the product shape's exact D4 argument vector --");
        Path product = scratch.resolve("product-a/native/main");
        NativeBuildRequest request = new NativeBuildRequest(published, product, 2,
            List.of(), null);
        NativeBuildPlan plan = NativeToolchain.plan(request);

        // The plan is a value: it allocates nothing.
        check(!Files.exists(plan.scratchDirectory()),
            "planning allocates no build scratch directory");
        checkEq(Path.of(System.getProperty("java.io.tmpdir"))
                .toAbsolutePath().normalize(),
            plan.scratchDirectory().getParent(),
            "the build scratch lives directly under java.io.tmpdir (D4)");
        check(plan.scratchDirectory().getFileName().toString().matches(
                "deal-native-" + ProcessHandle.current().pid() + "-[0-9a-f]+"),
            "the build scratch is deal-native-<pid>-<nonce>: "
                + plan.scratchDirectory().getFileName());
        check(Files.notExists(plan.registryUnit()),
            "the generated registry exists only once the build runs");

        // The pinned flag list is one constant, in D4 order.
        checkEq(List.of("--target=x86_64-pc-linux-gnu", "-std=c11", "-Wall",
                "-Werror", "-Wextra", "-pedantic", "-g", "-ffp-contract=off",
                "-fno-fast-math", "-fdebug-compilation-dir=.",
                "-Wl,--build-id=none"),
            NativeToolchain.PINNED_FLAGS,
            "PINNED_FLAGS is the D4 flag list verbatim and in order");

        // The exact vector: compiler, pinned flags, root include, level, the
        // one module, every runtime translation unit with the process entry,
        // no host unit, no driver unit, the generated registry, output.
        List<String> expected = new ArrayList<>();
        expected.add(expectedCompiler().toString());
        expected.addAll(NativeToolchain.PINNED_FLAGS);
        expected.add("-I" + published);
        expected.add("-O2");
        expected.add(published.resolve("main.ll").toString());
        for (String unit : NativeRuntime.TRANSLATION_UNITS) {
            expected.add(published.resolve(unit).toString());
        }
        expected.add(plan.registryUnit().toString());
        expected.add("-o");
        expected.add(product + NativeToolchain.PARTIAL_SUFFIX);
        checkEq(expected, plan.command(),
            "the product shape's argument vector is exactly D4's");
        checkEq(NativeRuntime.TRANSLATION_UNITS.size(),
            plan.runtimeUnits().size(),
            "the product shape links every runtime translation unit");
        check(plan.runtimeUnits().contains(
                published.resolve(NativeRuntime.PROCESS_ENTRY_FILE)),
            "the product shape includes deal/native/main.c (the process entry)");
        checkEq(null, plan.driverUnit(),
            "the product shape has no driver translation unit");
        checkEq(null, plan.driverSource(), "the product shape has no driver text");
        check(plan.request().productShape(),
            "a null driver source selects the product shape");

        // The empty registry shape (H2's count-zero sentinel).
        checkEq(EMPTY_REGISTRY, plan.registrySource(),
            "no supplied host unit renders the count-zero sentinel");

        // Build it: one product at the caller's path, nothing else.
        NativeBuildResult result = NativeToolchain.build(plan);
        checkEq(product, result.productPath(),
            "the product lives at the caller-supplied path");
        checkEq(plan.command(), result.command(),
            "the build ran the planned argument vector");
        checkEq(plan.registrySource(), result.registrySource(),
            "the build compiled the planned registry text");
        check(Files.isRegularFile(product), "the product is a regular file");
        check(Files.isExecutable(product), "the product is executable");
        check(Files.notExists(plan.scratchDirectory()),
            "the build scratch is removed after a successful build");
        check(Files.notExists(partialOf(product)),
            "no .partial file remains after a successful build");
        check(!product.startsWith(published),
            "the product is outside the publication root");
        check(!product.startsWith(plan.scratchDirectory()),
            "the product is outside the build scratch");
        NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
            Map.of());
        checkEq(0, run.exitCode(), "the product runs: " + run.describe());
        checkEq("", run.stdout(), "the product prints no terminal output");

        /* The published tree carries the whole C11 runtime, so the query is the
         * smoke project's own subprogram rather than every runtime DIE. */
        NativeCompiler.Result dwarf = NativeCompiler.run(
            List.of("llvm-dwarfdump", "--name=main", "--name=ping",
                product.toString()), scratch);
        checkEq(0, dwarf.exitCode(), "llvm-dwarfdump reads the product");
        check(dwarf.stdout().contains("main.deal"),
            "the product carries the smoke source's DWARF: " + dwarf.describe());
    }

    // =========================================================================
    // 2. The driver shape: one main, the generated driver unit
    // =========================================================================

    private static void testDriverShape(Path scratch, Path published)
            throws Exception {
        System.out.println("-- the driver shape omits exactly main.c --");
        Path product = scratch.resolve("driver-product/probe");
        NativeBuildRequest request = new NativeBuildRequest(published, product, 0,
            List.of(), DRIVER_SOURCE);
        check(request.driverShape(),
            "a driver source selects the driver shape");
        NativeBuildPlan plan = NativeToolchain.plan(request);

        checkEq(DRIVER_SOURCE, plan.driverSource(),
            "the plan carries the request's driver text verbatim");
        checkEq(plan.scratchDirectory().resolve(
                NativeToolchain.DRIVER_UNIT_NAME), plan.driverUnit(),
            "the driver unit is <scratch>/driver.c");
        checkEq(plan.scratchDirectory().resolve(HostsRegistry.UNIT_NAME),
            plan.registryUnit(),
            "the registry unit is <scratch>/hosts_registry.c");

        List<Path> expectedUnits = new ArrayList<>();
        for (String unit : NativeRuntime.TRANSLATION_UNITS) {
            if (!NativeRuntime.isProcessEntry(unit)) {
                expectedUnits.add(published.resolve(unit));
            }
        }
        checkEq(expectedUnits, plan.runtimeUnits(),
            "the driver shape links the runtime units in the closed order");
        checkEq(NativeRuntime.TRANSLATION_UNITS.size() - 1,
            plan.runtimeUnits().size(),
            "the driver shape omits exactly one runtime unit");
        check(!plan.runtimeUnits().contains(
                published.resolve(NativeRuntime.PROCESS_ENTRY_FILE)),
            "the driver shape omits deal/native/main.c");

        List<String> command = plan.command();
        check(!command.contains(published.resolve(
                NativeRuntime.PROCESS_ENTRY_FILE).toString()),
            "the argument vector carries no main.c");
        int driverAt = command.indexOf(plan.driverUnit().toString());
        int registryAt = command.indexOf(plan.registryUnit().toString());
        check(driverAt > 0, "the argument vector carries the driver unit");
        checkEq(driverAt + 1, registryAt,
            "the registry follows the driver unit (D4 order)");
        checkEq("-o", command.get(registryAt + 1),
            "the output flag follows the registry unit");
        checkEq(product + NativeToolchain.PARTIAL_SUFFIX,
            command.get(registryAt + 2), "the output is the fixed partial path");
        checkEq(registryAt + 3, command.size(),
            "no translation unit follows the registry unit");

        NativeBuildResult result = NativeToolchain.build(plan);
        checkEq(product, result.productPath(),
            "the driver product lives at the caller's path");
        check(Files.isRegularFile(product) && Files.isExecutable(product),
            "the driver product is an executable regular file");
        check(Files.notExists(plan.scratchDirectory()),
            "the driver build removes its scratch");
        check(Files.notExists(partialOf(product)),
            "the driver build leaves no .partial file");
        // Linking both main.c and the driver would fail, so a successful link
        // plus the ordered receipts prove exactly one process entry.
        NativeCompiler.Result run = NativeCompiler.execute(product, scratch,
            Map.of());
        checkEq(0, run.exitCode(), "the driver product runs: " + run.describe());
        checkEq("load:start\nload:done\nmain:start\nmain:done\n", run.stdout(),
            "the driver writes the ordered load/probe receipts (H6/G3)");
    }

    // =========================================================================
    // 3. The generated registry in both shapes
    // =========================================================================

    private static void testRegistryShapes(Path scratch, Path published)
            throws Exception {
        System.out.println("-- the generated registry's two shapes (H1/H2) --");
        Path root = copyTree(published, scratch.resolve("registry-pub"));
        writeFile(root.resolve("host/http.h"), HTTP_HEADER);
        Path unit = scratch.resolve("registry-hosts/http.c");
        writeFile(unit, HTTP_UNIT);

        // (a) A declared header with no supplied unit keeps no entry.
        NativeBuildPlan unsupplied = NativeToolchain.plan(
            new NativeBuildRequest(root, scratch.resolve("registry-out/a"), 2,
                List.of(), null));
        checkEq(EMPTY_REGISTRY, unsupplied.registrySource(),
            "an unsupplied host declaration contributes no registry entry");
        NativeToolchain.build(unsupplied);

        // (b) The matched unit's entry, with the raw specifier and symbol.
        NativeBuildPlan supplied = NativeToolchain.plan(
            new NativeBuildRequest(root, scratch.resolve("registry-out/b"), 2,
                List.of(unit), null));
        String expected = """
            /*
             * hosts_registry.c - the generated host registry unit of one
             * native build. Generated by deal.toolchain.NativeToolchain;
             * never published and removed with the build scratch.
             */
            #include "deal/native/deal_rt.h"
            #include "host/http.h"

            const deal_host_registry_entry deal_host_registry[1] = {
              {"host/http", &deal_host_module_http},
            };
            const size_t deal_host_registry_count = 1;
            """;
        checkEq(expected, supplied.registrySource(),
            "the registry pairs the header's specifier with the unit's symbol");
        NativeBuildResult result = NativeToolchain.build(supplied);
        NativeCompiler.Result run = NativeCompiler.execute(result.productPath(),
            scratch, Map.of());
        checkEq(0, run.exitCode(),
            "the nonempty registry links and runs: " + run.describe());

        // (c) Two entries in deterministic imported-module order, independent
        // of the order the caller lists the units in.
        writeFile(root.resolve("net/socket.h"), SOCKET_HEADER);
        Path socketUnit = scratch.resolve("registry-hosts-net/socket.c");
        writeFile(socketUnit, SOCKET_UNIT);
        NativeBuildPlan forward = NativeToolchain.plan(
            new NativeBuildRequest(root, scratch.resolve("registry-out/c"), 2,
                List.of(unit, socketUnit), null));
        NativeBuildPlan backward = NativeToolchain.plan(
            new NativeBuildRequest(root, scratch.resolve("registry-out/d"), 2,
                List.of(socketUnit, unit), null));
        checkEq(forward.registrySource(), backward.registrySource(),
            "the registry order is deterministic, not the unit order");
        check(forward.registrySource().contains(
                "  {\"host/http\", &deal_host_module_http},\n"
                    + "  {\"net/socket\", &deal_host_module_socket},\n"),
            "the entries follow the published header path order: "
                + forward.registrySource());
        check(forward.registrySource().contains(
                "const deal_host_registry_entry deal_host_registry[2] = {"),
            "the registry array length is the entry count");
        check(forward.registrySource().contains(
                "const size_t deal_host_registry_count = 2;"),
            "the registry count is the entry count");
        checkEq(3, countOccurrences(forward.registrySource(), "#include \""),
            "the runtime header and both declaration headers are included");
        NativeBuildResult both = NativeToolchain.build(forward);
        NativeCompiler.Result bothRun = NativeCompiler.execute(
            both.productPath(), scratch, Map.of());
        checkEq(0, bothRun.exitCode(),
            "the two-entry registry links and runs: " + bothRun.describe());
    }

    // =========================================================================
    // 4. The fail-closed preflight conditions
    // =========================================================================

    private static void testPreflightRejections(Path scratch, Path published)
            throws Exception {
        System.out.println("-- every preflight condition fails closed --");

        // (a) clang absent from PATH.
        Path emptyPath = Files.createDirectories(scratch.resolve("path-empty"));
        NativeBuildException missing = expectFailure(
            new NativeBuildRequest(published, scratch.resolve("reject/a"), 2,
                List.of(), null), Map.of("PATH", emptyPath.toString()));
        checkEq(NativeBuildException.Kind.TOOLCHAIN_MISSING, missing.kind(),
            "an absent clang is the named toolchain-missing preflight");
        check(missing.diagnostic().startsWith(
                "deal: native toolchain missing: "),
            "the absent-clang diagnostic carries its one prefix: "
                + missing.diagnostic());

        // (b) A major outside the accepted 14-16 window, on both sides.
        for (int major : List.of(13, 17)) {
            Path directory = Files.createDirectories(
                scratch.resolve("path-major-" + major));
            writeWrapper(directory.resolve("clang"), major);
            NativeBuildException unsupported = expectFailure(
                new NativeBuildRequest(published, scratch.resolve(
                    "reject/major-" + major), 2, List.of(), null),
                Map.of("PATH", directory.toString()));
            checkEq(NativeBuildException.Kind.TOOLCHAIN_UNSUPPORTED,
                unsupported.kind(),
                "clang major " + major + " is outside the accepted window");
            check(unsupported.diagnostic().contains("major " + major),
                "the version diagnostic names the observed major: "
                    + unsupported.diagnostic());
        }
        // (b2) A compiler that cannot state a version at all.
        Path unversioned = Files.createDirectories(
            scratch.resolve("path-unversioned"));
        writeWrapper(unversioned.resolve("clang"), "clang", 0);
        NativeBuildException noVersion = expectFailure(
            new NativeBuildRequest(published,
                scratch.resolve("reject/unversioned"), 2, List.of(), null),
            Map.of("PATH", unversioned.toString()));
        checkEq(NativeBuildException.Kind.TOOLCHAIN_UNVERSIONED,
            noVersion.kind(),
            "a compiler that states no version is the named unversioned"
                + " preflight");
        Path brokenVersion = Files.createDirectories(
            scratch.resolve("path-broken-version"));
        writeWrapper(brokenVersion.resolve("clang"), "clang version 14.0.0", 3);
        NativeBuildException failedVersion = expectFailure(
            new NativeBuildRequest(published,
                scratch.resolve("reject/broken-version"), 2, List.of(), null),
            Map.of("PATH", brokenVersion.toString()));
        checkEq(NativeBuildException.Kind.TOOLCHAIN_UNVERSIONED,
            failedVersion.kind(),
            "a failing version probe is the named unversioned preflight");
        check(failedVersion.detail().contains("--version"),
            "the unversioned diagnostic names the probe: "
                + failedVersion.detail());

        // ... and an accepted window member still builds through the same seam.
        for (int major : List.of(14, 15, 16)) {
            Path directory = Files.createDirectories(
                scratch.resolve("path-major-accepted-" + major));
            writeWrapper(directory.resolve("clang"), major);
            Path product = scratch.resolve("accepted-" + major + "/native/main");
            NativeBuildPlan plan = NativeToolchain.plan(
                new NativeBuildRequest(published, product, 0, List.of(), null),
                Map.of("PATH", directory.toString()));
            checkEq(directory.resolve("clang").toAbsolutePath().normalize()
                    .toString(), plan.command().get(0),
                "the wrapper's clang is the resolved compiler at major " + major);
            NativeToolchain.build(plan);
            check(Files.isExecutable(product),
                "major " + major + " is inside the accepted window and builds");
        }

        // (c) An unparsable published module.
        Path corrupt = copyTree(published, scratch.resolve("ir-pub"));
        writeFile(corrupt.resolve("main.ll"), "this is not an LLVM module\n");
        NativeBuildException unparsable = expectFailure(
            new NativeBuildRequest(corrupt, scratch.resolve("reject/ir"), 2,
                List.of(), null), Map.of());
        checkEq(NativeBuildException.Kind.IR_UNPARSABLE, unparsable.kind(),
            "a published module the compiler cannot parse is the named"
                + " IR preflight");
        check(unparsable.detail().startsWith("main.ll: "),
            "the IR diagnostic names the module: " + unparsable.detail());
        check(unparsable.detail().contains("error"),
            "the IR diagnostic carries the compiler's first diagnostic: "
                + unparsable.detail());

        // (d) A missing runtime deployment file.
        Path incomplete = copyTree(published, scratch.resolve("runtime-pub"));
        Files.delete(incomplete.resolve("deal/native/std.c"));
        NativeBuildException runtimeMissing = expectFailure(
            new NativeBuildRequest(incomplete, scratch.resolve("reject/runtime"),
                2, List.of(), null), Map.of());
        checkEq(NativeBuildException.Kind.RUNTIME_MISSING, runtimeMissing.kind(),
            "a missing D6 runtime file is the named runtime preflight");
        checkEq("deal/native/std.c", runtimeMissing.detail(),
            "the runtime diagnostic names the missing file");

        // (e) A host unit whose stem matches no imported declaration module.
        Path unmatched = scratch.resolve("reject-hosts/nope.c");
        writeFile(unmatched, HTTP_UNIT);
        NativeBuildException unmatchedFailure = expectFailure(
            new NativeBuildRequest(published, scratch.resolve("reject/host"), 2,
                List.of(unmatched), null), Map.of());
        checkEq(NativeBuildException.Kind.HOST_UNIT_UNMATCHED,
            unmatchedFailure.kind(),
            "an unmatched host unit stem is the named host-unit preflight");
        check(unmatchedFailure.detail().contains(unmatched.toString()),
            "the host-unit diagnostic names the unit: "
                + unmatchedFailure.detail());

        // (f) Duplicate host unit stems.
        Path declarationRoot = copyTree(published,
            scratch.resolve("host-decl-pub"));
        writeFile(declarationRoot.resolve("host/http.h"), HTTP_HEADER);
        Path firstUnit = scratch.resolve("dup-a/http.c");
        Path secondUnit = scratch.resolve("dup-b/http.c");
        writeFile(firstUnit, HTTP_UNIT);
        writeFile(secondUnit, HTTP_UNIT);
        NativeBuildException duplicate = expectFailure(
            new NativeBuildRequest(declarationRoot, scratch.resolve("reject/dup"),
                2, List.of(firstUnit, secondUnit), null), Map.of());
        checkEq(NativeBuildException.Kind.HOST_UNIT_DUPLICATE,
            duplicate.kind(),
            "two host units with one stem are the named duplicate preflight");
        check(duplicate.detail().contains("'http'"),
            "the duplicate diagnostic names the stem: " + duplicate.detail());

        // (g) Two published host declarations that share one stem.
        Path ambiguous = copyTree(published, scratch.resolve("ambiguous-pub"));
        writeFile(ambiguous.resolve("host/http.h"), HTTP_HEADER);
        writeFile(ambiguous.resolve("net/http.h"), HTTP_HEADER);
        NativeBuildException ambiguousFailure = expectFailure(
            new NativeBuildRequest(ambiguous, scratch.resolve("reject/amb"), 2,
                List.of(firstUnit), null), Map.of());
        checkEq(NativeBuildException.Kind.HOST_DECLARATION_AMBIGUOUS,
            ambiguousFailure.kind(),
            "two headers with one stem are the named declaration ambiguity");

        // (h) A product path inside the publication root.
        for (Path inside : List.of(published.resolve("native/main"), published)) {
            NativeBuildException insideFailure = expectFailure(
                new NativeBuildRequest(published, inside, 2, List.of(), null),
                Map.of());
            checkEq(NativeBuildException.Kind.PRODUCT_INSIDE_PUBLICATION,
                insideFailure.kind(),
                "a product path inside the publication root is rejected: "
                    + inside);
            check(insideFailure.detail().contains(published.toString()),
                "the inside-publication diagnostic names the root");
        }

        // (i) A publication root that carries no module, or more than one.
        Path noModule = copyTree(published, scratch.resolve("nomodule-pub"));
        Files.delete(noModule.resolve("main.ll"));
        checkEq(NativeBuildException.Kind.BUILD_INPUTS_INCOMPLETE,
            expectFailure(new NativeBuildRequest(noModule,
                scratch.resolve("reject/nomodule"), 2, List.of(), null),
                Map.of()).kind(),
            "a publication root with no module is incomplete build input");
        Path twoModules = copyTree(published, scratch.resolve("twomodule-pub"));
        writeFile(twoModules.resolve("other.ll"), "; second module\n");
        checkEq(NativeBuildException.Kind.BUILD_INPUTS_INCOMPLETE,
            expectFailure(new NativeBuildRequest(twoModules,
                scratch.resolve("reject/twomodule"), 2, List.of(), null),
                Map.of()).kind(),
            "two published modules are incomplete build input");
        checkEq(NativeBuildException.Kind.BUILD_INPUTS_INCOMPLETE,
            expectFailure(new NativeBuildRequest(
                scratch.resolve("absent-publication"),
                scratch.resolve("reject/absent"), 2, List.of(), null),
                Map.of()).kind(),
            "an absent publication root is incomplete build input");

        // No rejected preflight creates a scratch directory or a product.
        checkEq(scratchBaseline, buildScratchEntries(),
            "no preflight rejection leaves a build scratch directory");
    }

    // =========================================================================
    // 5. The transactional product lifetime
    // =========================================================================

    private static void testProductLifetime(Path scratch, Path published)
            throws Exception {
        System.out.println("-- the product lifetime is transactional --");
        Path productRoot = scratch.resolve("lifetime");
        Path product = productRoot.resolve("native/main");
        Path other = scratch.resolve("lifetime-other/native/main");

        // (a) A failed first build leaves no product and no partial.
        Path failingRoot = copyTree(published, scratch.resolve("failing-pub"));
        writeFile(failingRoot.resolve("host/http.h"), HTTP_HEADER);
        Path brokenUnit = scratch.resolve("broken-hosts/http.c");
        writeFile(brokenUnit, """
            #include "host/http.h"

            int broken(void) { return missing_expression + ; }
            """);
        NativeBuildException firstFailure = expectFailure(
            new NativeBuildRequest(failingRoot, product, 2,
                List.of(brokenUnit), null), Map.of());
        checkEq(NativeBuildException.Kind.LINK_FAILED, firstFailure.kind(),
            "a defective host unit is the named link failure");
        check(firstFailure.diagnostic().startsWith("deal: native link failed: "),
            "the link failure carries the pinned prefix: "
                + firstFailure.diagnostic());
        check(firstFailure.detail().contains("error:"),
            "the link failure carries the first compiler diagnostic: "
                + firstFailure.detail());
        check(Files.notExists(product),
            "a failed first build leaves no product");
        check(Files.notExists(partialOf(product)),
            "a failed first build leaves no .partial file");
        check(!firstFailure.diagnostic().contains(".partial"),
            "the fixed partial suffix never appears in diagnostics");

        // (b) A successful build, then a failed rebuild: the earlier product
        //     stays byte-identical, executable and runnable.
        NativeBuildPlan good = NativeToolchain.plan(new NativeBuildRequest(
            published, product, 2, List.of(), null));
        NativeToolchain.build(good);
        byte[] earlier = Files.readAllBytes(product);
        Set<PosixFilePermission> earlierPermissions =
            Files.getPosixFilePermissions(product);
        NativeBuildException rebuildFailure = expectFailure(
            new NativeBuildRequest(failingRoot, product, 0,
                List.of(brokenUnit), null), Map.of());
        checkEq(NativeBuildException.Kind.LINK_FAILED, rebuildFailure.kind(),
            "a failed rebuild reports the link failure");
        checkEq(hex(earlier), hex(Files.readAllBytes(product)),
            "a failed rebuild preserves the earlier product byte for byte");
        checkEq(earlierPermissions, Files.getPosixFilePermissions(product),
            "a failed rebuild preserves the earlier product's permissions");
        check(Files.isExecutable(product),
            "the preserved product is still executable");
        NativeCompiler.Result runPreserved = NativeCompiler.execute(product,
            scratch, Map.of());
        checkEq(0, runPreserved.exitCode(),
            "the preserved product still runs: " + runPreserved.describe());
        check(Files.notExists(partialOf(product)),
            "the failed rebuild removes its own .partial file");

        // (c) An unrelated product is never disturbed.
        NativeToolchain.build(NativeToolchain.plan(new NativeBuildRequest(
            published, other, 0, List.of(), null)));
        byte[] otherBytes = Files.readAllBytes(other);
        NativeToolchain.build(NativeToolchain.plan(new NativeBuildRequest(
            published, product, 2, List.of(), null)));
        checkEq(hex(otherBytes), hex(Files.readAllBytes(other)),
            "a build of one path never disturbs a product at another path");

        // (d) A successful rebuild replaces the product by one rename: the new
        //     bytes are in place and the earlier bytes are gone.
        Path changed = compileProject(scratch.resolve("changed-project"),
            Map.of("src/main.deal", """
                export function main(): null {
                  let counter: number = 0;
                  while (counter < 3) {
                    counter = counter + 1;
                  }
                  return null;
                }
                """));
        NativeToolchain.build(NativeToolchain.plan(new NativeBuildRequest(
            changed, product, 2, List.of(), null)));
        byte[] replaced = Files.readAllBytes(product);
        check(!Arrays.equals(earlier, replaced),
            "a successful rebuild replaces the earlier product's bytes");
        NativeCompiler.Result runReplaced = NativeCompiler.execute(product,
            scratch, Map.of());
        checkEq(0, runReplaced.exitCode(),
            "the replaced product runs: " + runReplaced.describe());
        check(Files.notExists(partialOf(product)),
            "a successful rebuild leaves no .partial file");

        // (e) The scratch of every build above is gone.
        check(!Files.exists(good.scratchDirectory()),
            "the first successful build removed its scratch");
        checkEq(scratchBaseline, buildScratchEntries(),
            "no build leaves a java.io.tmpdir scratch directory behind");
    }

    // =========================================================================
    // 5b. The fixed staging suffix never appears in a diagnostic
    // =========================================================================

    /**
     * Every build failure whose cause is the pre-rename staging path
     * ({@code <productPath>.partial}) reports the caller's product path
     * instead: the fixed suffix names no file the caller created, so it is
     * internal and never surfaces ({@code llvm-native-backend-integration}
     * D4, {@code NativeBuild}).
     */
    private static void testStagingPathNeverDiagnosed(Path scratch,
            Path published) throws Exception {
        System.out.println("-- the staging suffix never appears in diagnostics --");

        // (a) The linker's own output-file diagnostic: a compiler that fails
        //     the link the way an unwritable product directory does.
        Path wrapperDirectory = Files.createDirectories(
            scratch.resolve("diagnostic-path"));
        Path wrapper = wrapperDirectory.resolve("clang");
        writeWrapper(wrapper, "clang version " + NativeToolchain.MINIMUM_MAJOR
            + ".0.0", 0, "for argument in \"$@\"; do\n"
            + "  if [ \"$argument\" = \"-x\" ]; then\n"
            + "    exec " + NativeCompiler.compiler().path() + " \"$@\"\n"
            + "  fi\n"
            + "done\n"
            + "output=\"\"\n"
            + "previous=\"\"\n"
            + "for argument in \"$@\"; do\n"
            + "  if [ \"$previous\" = \"-o\" ]; then output=\"$argument\"; fi\n"
            + "  previous=\"$argument\"\n"
            + "done\n"
            + "echo \"ld: cannot open output file $output: Permission denied\""
            + " >&2\n"
            + "exit 1\n");
        Path outputProduct = scratch.resolve("diagnostic-output/native/main");
        NativeBuildException outputFailure = expectFailure(
            new NativeBuildRequest(published, outputProduct, 2, List.of(),
                null), Map.of("PATH", wrapperDirectory.toString()));
        checkEq(NativeBuildException.Kind.LINK_FAILED, outputFailure.kind(),
            "a linker that cannot open its output file is the named link"
                + " failure");
        check(outputFailure.detail().contains(
                "cannot open output file " + outputProduct + ":"),
            "the carried diagnostic names the product, not the staging path: "
                + outputFailure.detail());
        checkHidesStagingPath(outputFailure, outputProduct,
            "an output-file failure");

        // (b) The rename itself: the caller's product path is an existing
        //     directory, so the one same-directory rename cannot replace it.
        Path directoryProduct = scratch.resolve("diagnostic-rename/native/main");
        Files.createDirectories(directoryProduct);
        NativeBuildException renameFailure = expectFailure(
            new NativeBuildRequest(published, directoryProduct, 2, List.of(),
                null), Map.of());
        checkEq(NativeBuildException.Kind.IO_FAILURE, renameFailure.kind(),
            "a rename onto an existing directory is the named I/O failure");
        check(renameFailure.detail().startsWith(
                "cannot replace '" + directoryProduct + "':"),
            "the rename diagnostic names the product it replaces: "
                + renameFailure.detail());
        check(Files.isDirectory(directoryProduct),
            "the caller's directory is untouched by the failed rename");
        checkHidesStagingPath(renameFailure, directoryProduct,
            "a rename failure");

        // (c) The pre-rename path itself blocked: a non-empty directory left
        //     at the staging path by an interrupted build.
        Path staleProduct = scratch.resolve("diagnostic-stale/native/main");
        Path stalePartial = partialOf(staleProduct);
        writeFile(stalePartial.resolve("blocker"), "stale\n");
        NativeBuildException staleFailure;
        try {
            NativeToolchain.build(new NativeBuildRequest(published, staleProduct,
                2, List.of(), null));
            staleFailure = null;
        } catch (NativeBuildException failure) {
            staleFailure = failure;
        }
        check(staleFailure != null,
            "a blocked pre-rename path fails the build");
        if (staleFailure != null) {
            checkEq(NativeBuildException.Kind.IO_FAILURE, staleFailure.kind(),
                "a blocked pre-rename path is the named I/O failure");
            checkEq("cannot clear the staging path of '" + staleProduct
                    + "': a non-empty directory occupies it",
                staleFailure.detail(),
                "the blocked pre-rename diagnostic names the product and the"
                    + " cause");
            check(Files.notExists(staleProduct),
                "a blocked pre-rename path creates no product");
            checkEq(scratchBaseline, buildScratchEntries(),
                "a blocked pre-rename path leaves no build scratch");
            checkHidesStagingPath(staleFailure, staleProduct,
                "a blocked pre-rename path");
        }
        deleteRecursively(scratch.resolve("diagnostic-stale"));

        // (d) A staging path the filesystem cannot address at all: the product
        //     file name is one byte short of NAME_MAX, so the seam's suffix
        //     overflows the name and the tool's own message names the staging
        //     path the build asked for.
        Path longProduct = scratch.resolve("diagnostic-long/native/"
            + "m".repeat(250));
        NativeBuildException longFailure;
        try {
            NativeToolchain.build(new NativeBuildRequest(published, longProduct,
                2, List.of(), null));
            longFailure = null;
        } catch (NativeBuildException failure) {
            longFailure = failure;
        }
        check(longFailure != null,
            "an unaddressable staging name fails the build");
        if (longFailure != null) {
            checkEq(NativeBuildException.Kind.IO_FAILURE, longFailure.kind(),
                "an unaddressable staging name is the named I/O failure");
            check(longFailure.detail().contains(longProduct.toString()),
                "the diagnostic names the product path the caller supplied: "
                    + longFailure.detail());
            check(Files.notExists(longProduct),
                "an unaddressable staging name creates no product");
            checkEq(scratchBaseline, buildScratchEntries(),
                "an unaddressable staging name leaves no build scratch");
            checkHidesStagingPath(longFailure, longProduct,
                "an unaddressable staging name");
        }
    }

    /**
     * Asserts one build diagnostic names neither the absolute staging path nor
     * the fixed suffix of one product.
     */
    private static void checkHidesStagingPath(NativeBuildException failure,
            Path product, String what) {
        String diagnostic = failure.diagnostic();
        check(!diagnostic.contains(partialOf(product).toString()),
            what + " never names the staging path: " + diagnostic);
        check(!diagnostic.contains(NativeToolchain.PARTIAL_SUFFIX),
            what + " never names the fixed staging suffix: " + diagnostic);
    }

    // =========================================================================
    // 6. Builds never write the publication root
    // =========================================================================

    private static void testPublicationIsReadOnly(Path scratch, Path published)
            throws Exception {
        System.out.println("-- a build never writes the publication root --");
        Map<String, String> before = snapshot(published);
        NativeBuildRequest productRequest = new NativeBuildRequest(published,
            scratch.resolve("readonly/a/native/main"), 2, List.of(), null);
        NativeToolchain.build(productRequest);
        NativeBuildRequest driverRequest = new NativeBuildRequest(published,
            scratch.resolve("readonly/b/driver"), 0, List.of(), DRIVER_SOURCE);
        NativeToolchain.build(driverRequest);
        checkEq(before, snapshot(published),
            "both build shapes leave the published set byte-identical");
        check(!Files.exists(published.resolve("hosts_registry.c")),
            "no generated registry is published");
        check(!Files.exists(published.resolve("driver.c")),
            "no generated driver unit is published");
        check(!Files.exists(published.resolve("native")),
            "no build product is placed inside the publication root");
    }

    // =========================================================================
    // 7. The CLI verb's defaults and options
    // =========================================================================

    private static void testCliDefaultsAndOptions(Path scratch) throws Exception {
        System.out.println("-- deal native build|run defaults and options --");
        Path root = scratch.resolve("cli-project");
        writeProject(root, Map.of("src/main.deal", """
            export function main(): null {
              let value: number = 2;
              return null;
            }
            """), Map.of("backend", "luajit"));
        Path entry = root.resolve("src/main.deal");

        // The verb selects the native backend and both defaults.
        Captured defaults = capture(() -> Main.run(new String[] {
            "native", "build", entry.toString()}));
        checkEq(0, defaults.exitCode(),
            "deal native build succeeds with defaults: " + defaults.stderr());
        Path output = root.resolve("build/llvm");
        check(Files.isRegularFile(output.resolve("main.ll")),
            "the default publication root is <manifestDir>/build/llvm");
        check(Files.isRegularFile(output.resolve("deal/native/main.c")),
            "the default publication carries the D6 runtime set");
        check(!Files.exists(root.resolve("build/lua")),
            "the verb compiles the native arm, not the manifest's backend");
        Path product = root.resolve("build/native/native/main");
        check(Files.isRegularFile(product),
            "the default product is <manifestDir>/build/native/native/"
                + "<entry-stem>");
        check(Files.isExecutable(product), "the default product is executable");
        NativeCompiler.Result runDefault = NativeCompiler.execute(product, root,
            Map.of());
        checkEq(0, runDefault.exitCode(),
            "the default product runs: " + runDefault.describe());

        // The options: --output, --product and --opt-level, with --verbose
        // printing the exact clang argument vector.
        Path customOutput = root.resolve("artifacts");
        Path customProduct = scratch.resolve("cli-product-root");
        Captured options = capture(() -> Main.run(new String[] {
            "native", "build", entry.toString(),
            "--output", customOutput.toString(),
            "--product", customProduct.toString(),
            "--opt-level", "0", "--verbose"}));
        checkEq(0, options.exitCode(),
            "deal native build accepts its options: " + options.stderr());
        check(Files.isRegularFile(customOutput.resolve("main.ll")),
            "--output selects the publication root");
        Path customPath = customProduct.resolve("native/main");
        check(Files.isRegularFile(customPath),
            "--product selects the product root and the product is"
                + " <productRoot>/native/<entry-stem>");
        check(Files.notExists(customOutput.resolve("native")),
            "the product stays outside the publication root");
        List<String> printed = clangLine(options.stdout());
        check(!printed.isEmpty(),
            "--verbose prints the clang argument vector: " + options.stdout());
        if (!printed.isEmpty()) {
            List<String> expected = new ArrayList<>();
            expected.add(expectedCompiler().toString());
            expected.addAll(NativeToolchain.PINNED_FLAGS);
            expected.add("-I" + customOutput);
            expected.add("-O0");
            expected.add(customOutput.resolve("main.ll").toString());
            for (String unit : NativeRuntime.TRANSLATION_UNITS) {
                expected.add(customOutput.resolve(unit).toString());
            }
            expected.add("<scratch>/" + HostsRegistry.UNIT_NAME);
            expected.add("-o");
            expected.add(customPath + NativeToolchain.PARTIAL_SUFFIX);
            checkEq(expected, maskScratchRegistry(printed),
                "the printed vector is exactly D4's");
        }

        // The default optimization level is 2.
        Captured verboseDefault = capture(() -> Main.run(new String[] {
            "native", "build", entry.toString(),
            "--product", scratch.resolve("cli-default-level").toString(),
            "--verbose"}));
        checkEq(0, verboseDefault.exitCode(),
            "the default-level build succeeds: " + verboseDefault.stderr());
        check(clangLine(verboseDefault.stdout()).contains("-O2"),
            "the default optimization level is 2");

        // deal native run builds and executes the product.
        Path runRoot = scratch.resolve("cli-run-project");
        writeProject(runRoot, Map.of("src/main.deal", """
            export function main(): null {
              return null;
            }
            """), Map.of());
        Captured run = capture(() -> Main.run(new String[] {
            "native", "run", runRoot.resolve("src/main.deal").toString()}));
        checkEq(0, run.exitCode(),
            "deal native run returns the product's status: " + run.stderr());
        Path runProduct = runRoot.resolve("build/native/native/main");
        check(Files.isExecutable(runProduct),
            "deal native run leaves the built product in place");
        NativeCompiler.Result direct = NativeCompiler.execute(runProduct,
            runRoot, Map.of());
        checkEq(direct.exitCode(), run.exitCode(),
            "deal native run returns exactly the product's exit status");

        // A plain compile still executes no tool and creates no product.
        Captured compile = capture(() -> Main.run(new String[] {
            "compile", runRoot.resolve("src/main.deal").toString(),
            "--backend", "llvm"}));
        checkEq(0, compile.exitCode(),
            "deal compile --backend llvm still succeeds: " + compile.stderr());
        check(Files.notExists(runRoot.resolve("build/lua")),
            "the plain compile selects the llvm arm");
    }

    // =========================================================================
    // 8. The CLI's rejected options and reporting
    // =========================================================================

    private static void testCliRejections(Path scratch) throws Exception {
        System.out.println("-- the CLI rejects and reports each condition --");
        Path root = scratch.resolve("cli-reject-project");
        writeProject(root, Map.of("src/main.deal", """
            export function main(): null {
              return null;
            }
            """), Map.of());
        Path entry = root.resolve("src/main.deal");

        Captured noOperation = capture(() -> Main.run(
            new String[] {"native"}));
        checkEq(1, noOperation.exitCode(),
            "deal native without an operation fails");
        check(noOperation.stderr().contains(
                "deal: native requires an operation (build|run)"),
            "the missing operation is named: " + noOperation.stderr());
        check(noOperation.stderr().contains(
                "deal native build <entry.deal>"),
            "the native usage names the build spelling");

        Captured unknownOperation = capture(() -> Main.run(
            new String[] {"native", "link", entry.toString()}));
        checkEq(1, unknownOperation.exitCode(),
            "an unknown native operation fails");
        check(unknownOperation.stderr().contains(
                "deal: unknown native operation 'link'"),
            "the unknown operation is named: " + unknownOperation.stderr());

        for (String level : List.of("1", "3", "-O2", "fast")) {
            Captured badLevel = capture(() -> Main.run(new String[] {
                "native", "build", entry.toString(), "--opt-level", level}));
            checkEq(1, badLevel.exitCode(),
                "--opt-level " + level + " is rejected");
            check(badLevel.stderr().contains(
                    "deal: --opt-level accepts only 0 or 2"),
                "the rejected level is named: " + badLevel.stderr());
        }
        check(!Files.exists(root.resolve("build/llvm")),
            "a rejected option publishes nothing");

        Captured missingEntry = capture(() -> Main.run(
            new String[] {"native", "build"}));
        checkEq(1, missingEntry.exitCode(), "a missing entry file fails");
        check(missingEntry.stderr().contains("deal: missing entry file"),
            "the missing entry is named: " + missingEntry.stderr());

        // An unmatched --host unit is reported with the seam's own diagnostic
        // and creates no product.
        Path unit = scratch.resolve("cli-hosts/nowhere.c");
        writeFile(unit, "int nowhere(void) { return 0; }\n");
        Captured unmatchedHost = capture(() -> Main.run(new String[] {
            "native", "build", entry.toString(), "--host", unit.toString()}));
        checkEq(1, unmatchedHost.exitCode(),
            "an unmatched --host unit fails the build");
        check(unmatchedHost.stderr().contains(
                "deal: native host unit unmatched: "),
            "the CLI reports the seam's named diagnostic: "
                + unmatchedHost.stderr());
        check(unmatchedHost.stderr().contains(unit.toString()),
            "the CLI diagnostic names the unit");
        check(Files.notExists(root.resolve("build/native/native/main")),
            "a failed build creates no product");

        // A product path inside the publication root is rejected by the seam
        // and places nothing there.
        Path insideRoot = scratch.resolve("cli-inside-project");
        writeProject(insideRoot, Map.of("src/main.deal", """
            export function main(): null {
              return null;
            }
            """), Map.of());
        Captured insideProduct = capture(() -> Main.run(new String[] {
            "native", "build", insideRoot.resolve("src/main.deal").toString(),
            "--product", insideRoot.resolve("build/llvm").toString()}));
        checkEq(1, insideProduct.exitCode(),
            "a product root inside the publication root fails the build");
        check(insideProduct.stderr().contains(
                "deal: native product inside publication: "),
            "the CLI reports the inside-publication diagnostic: "
                + insideProduct.stderr());
        check(Files.notExists(
                insideRoot.resolve("build/llvm/native/main")),
            "no product is created inside the publication root");

        // A frontend error keeps the shared diagnostic and publishes nothing.
        Path broken = scratch.resolve("cli-frontend-project");
        writeProject(broken, Map.of("src/main.deal",
            "export function main(): number {\n  return 1;\n}\n"), Map.of());
        Captured frontend = capture(() -> Main.run(new String[] {
            "native", "build", broken.resolve("src/main.deal").toString()}));
        checkEq(1, frontend.exitCode(),
            "a frontend error fails the native build");
        check(frontend.stderr().contains("E2011"),
            "the shared frontend diagnostic is preserved: "
                + frontend.stderr());
        check(!Files.exists(broken.resolve("build/llvm")),
            "a frontend error publishes nothing");
        check(!Files.exists(broken.resolve("build/native")),
            "a frontend error creates no product");
    }

    // =========================================================================
    // 9. The CLI with no compiler on PATH (a real child process)
    // =========================================================================

    private static void testCliMissingTool(Path scratch) throws Exception {
        System.out.println("-- the CLI fails closed with no clang on PATH --");
        Path root = scratch.resolve("cli-no-tool-project");
        writeProject(root, Map.of("src/main.deal", """
            export function main(): null {
              return null;
            }
            """), Map.of());
        Path emptyPath = Files.createDirectories(
            scratch.resolve("cli-empty-path"));
        List<String> command = List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", absoluteClasspath(),
            Main.class.getName(),
            "native", "build",
            root.resolve("src/main.deal").toString());
        NativeCompiler.Result probe = NativeCompiler.run(command, REPO,
            Map.of("PATH", emptyPath.toString()));
        checkEq(1, probe.exitCode(),
            "the CLI fails with no compiler on PATH: " + probe.describe());
        check(probe.stderr().contains("deal: native toolchain missing: "),
            "the CLI reports the named toolchain failure: " + probe.stderr());
        check(Files.notExists(root.resolve("build/native/native/main")),
            "no product exists after the failed build");
    }

    // =========================================================================
    // 10. The CLI with an unsupported compiler major (a real child process)
    // =========================================================================

    private static void testCliUnsupportedMajor(Path scratch) throws Exception {
        System.out.println("-- the CLI fails closed outside the version window --");
        Path root = scratch.resolve("cli-major-project");
        writeProject(root, Map.of("src/main.deal", """
            export function main(): null {
              return null;
            }
            """), Map.of());
        Path directory = Files.createDirectories(
            scratch.resolve("cli-major-path"));
        writeWrapper(directory.resolve("clang"), 13);
        List<String> command = List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", absoluteClasspath(),
            Main.class.getName(),
            "native", "build",
            root.resolve("src/main.deal").toString());
        NativeCompiler.Result probe = NativeCompiler.run(command, REPO,
            Map.of("PATH", directory.toString()));
        checkEq(1, probe.exitCode(),
            "the CLI fails outside the accepted window: " + probe.describe());
        check(probe.stderr().contains("deal: native toolchain unsupported: "),
            "the CLI names the unsupported compiler: " + probe.stderr());
        check(probe.stderr().contains("major 13"),
            "the CLI diagnostic names the observed major: " + probe.stderr());
        check(Files.notExists(root.resolve("build/native/native/main")),
            "no product exists after the unsupported-compiler failure");
    }

    // =========================================================================
    // 11. The usage text
    // =========================================================================

    private static void testUsageText() throws Exception {
        System.out.println("-- the usage text names the native verb --");
        Captured usage = capture(() -> Main.run(new String[0]));
        checkEq(1, usage.exitCode(), "an empty command line fails");
        check(usage.stderr().contains(
                "deal native <build|run> <entry.deal>"),
            "the usage enumerates the native verb: " + usage.stderr());
        check(usage.stderr().contains("[--opt-level 0|2]")
                && usage.stderr().contains("[--host <file.c>]..."),
            "the usage names the native options: " + usage.stderr());
        check(usage.stderr().contains(
                "through the pinned clang window (LLVM 14-16)"),
            "the usage documents the pinned toolchain window");
    }

    // =========================================================================
    // 12. The closed value shapes of the seam
    // =========================================================================

    private static void testValueShapes(Path scratch, Path published)
            throws Exception {
        System.out.println("-- the seam's closed value shapes --");
        Path root = REPO.resolve("build/native-value-shapes");
        Path product = root.resolve("native/main");
        for (int level : List.of(-1, 1, 3, 4)) {
            try {
                new NativeBuildRequest(root, product, level, List.of(), null);
                check(false, "the optimization level " + level
                    + " must be rejected");
            } catch (IllegalArgumentException rejected) {
                check(rejected.getMessage().contains(
                        "accepts the optimization levels 0 and 2"),
                    "the optimization level " + level + " is rejected");
            }
        }
        try {
            new NativeBuildRequest(root, product, 2, List.of(), "  \n");
            check(false, "a blank driver text must be rejected");
        } catch (IllegalArgumentException rejected) {
            check(rejected.getMessage().contains("null for the product shape"),
                "a blank driver text is rejected, not silently treated as"
                    + " the product shape");
        }
        NativeBuildRequest normalized = new NativeBuildRequest(
            REPO.resolve("build/../build/native-value-shapes"),
            REPO.resolve("build/native-value-shapes/./native/main"), 0,
            List.of(REPO.resolve("build/native-value-shapes/hosts/../hosts/x.c")),
            null);
        checkEq(root.toAbsolutePath().normalize(),
            normalized.publicationRoot(),
            "the publication root is normalized to absolute");
        checkEq(product.toAbsolutePath().normalize(),
            normalized.productPath(),
            "the product path is normalized to absolute");
        checkEq(List.of(REPO.resolve(
                "build/native-value-shapes/hosts/x.c").toAbsolutePath()
                .normalize()),
            normalized.hostUnits(),
            "the host units are normalized to absolute");
        checkEq(List.of(0, 2), NativeBuildRequest.OPT_LEVELS,
            "the accepted optimization levels are exactly 0 and 2");

        HostsRegistry.Entry entry = new HostsRegistry.Entry("host/http",
            "host/http.h", "http");
        checkEq("deal_host_module_http", entry.symbol(),
            "the entry's registration symbol is H2's stem form");
        for (List<String> invalid : List.of(
                List.of("", "host/http.h", "http"),
                List.of("host/http", "http/http.txt", "http"),
                List.of("host\\http", "host/http.h", "http"),
                List.of("host/http", "host/http.h", ""))) {
            try {
                new HostsRegistry.Entry(invalid.get(0), invalid.get(1),
                    invalid.get(2));
                check(false, "the invalid registry entry " + invalid
                    + " must be rejected");
            } catch (IllegalArgumentException rejected) {
                check(rejected.getMessage() != null,
                    "the invalid registry entry " + invalid
                        + " is rejected with a message");
            }
        }

        try {
            new NativeBuildResult(product, List.of(), EMPTY_REGISTRY, null);
            check(false, "a result without an argument vector must be rejected");
        } catch (IllegalArgumentException rejected) {
            check(rejected.getMessage().contains("argument vector"),
                "a result without an argument vector is rejected");
        }
        check(!new NativeBuildResult(product, List.of("/usr/bin/clang"),
                EMPTY_REGISTRY, null).driverShape(),
            "a result without driver text is the product shape");
        check(new NativeBuildResult(product, List.of("/usr/bin/clang"),
                EMPTY_REGISTRY, "int main(void){return 0;}\n").driverShape(),
            "a result with driver text is the driver shape");

        for (NativeBuildException.Kind kind : NativeBuildException.Kind.values()) {
            NativeBuildException failure = new NativeBuildException(kind,
                "detail");
            checkEq(kind.prefix() + "detail", failure.diagnostic(),
                "every named kind renders its one prefix: " + kind);
            checkEq(kind, failure.kind(), "the failure retains its kind");
            checkEq("detail", failure.detail(),
                "the failure retains its detail");
        }
        try {
            new NativeBuildException(NativeBuildException.Kind.LINK_FAILED, " ");
            check(false, "a blank failure detail must be rejected");
        } catch (IllegalArgumentException rejected) {
            check(rejected.getMessage().contains("condition detail"),
                "a blank failure detail is rejected");
        }

        // The build plan's own consistency rule: the shape decides the units.
        NativeBuildPlan productPlan = NativeToolchain.plan(
            new NativeBuildRequest(published,
                scratch.resolve("value-shapes/native/main"), 0, List.of(),
                null));
        try {
            new NativeBuildPlan(productPlan.request(),
                productPlan.environment(), productPlan.scratchDirectory(),
                productPlan.registryUnit(), productPlan.driverUnit(),
                productPlan.registrySource(), "int main(void){return 0;}\n",
                productPlan.entryModule(), productPlan.runtimeUnits(),
                productPlan.hostUnits(), productPlan.command());
            check(false, "a product plan carrying driver text must be rejected");
        } catch (IllegalArgumentException rejected) {
            check(rejected.getMessage().contains("driver shape"),
                "the plan's shape rule rejects a product plan with driver"
                    + " text");
        }
    }

    // =========================================================================
    // The production compile path
    // =========================================================================

    private static Path compileProject(Path root) throws Exception {
        return compileProject(root, Map.of(
            "src/lib.deal", LIB_SOURCE, "src/main.deal", APP_SOURCE));
    }

    /** Writes the fixture and publishes it through the production compile. */
    private static Path compileProject(Path root, Map<String, String> sources)
            throws Exception {
        return compileProject(root, sources, Map.of());
    }

    /** Writes the fixture and publishes it through the production compile. */
    private static Path compileProject(Path root, Map<String, String> sources,
            Map<String, String> manifest) throws Exception {
        writeProject(root, sources, manifest);
        Path entry = root.resolve("src/main.deal");
        Captured compile = capture(() -> Main.run(new String[] {
            "compile", entry.toString(), "--backend", "llvm"}));
        if (compile.exitCode() != 0) {
            throw new IllegalStateException("the fixture compile failed: "
                + compile.stdout() + compile.stderr());
        }
        return root.resolve("build/llvm");
    }

    private static void writeProject(Path root, Map<String, String> sources,
            Map<String, String> manifest) throws IOException {
        Files.createDirectories(root.resolve("src"));
        StringBuilder json = new StringBuilder("{\"languageVersion\": \"1.5\","
            + " \"moduleRoots\": [\"src\"]");
        for (Map.Entry<String, String> entry : manifest.entrySet()) {
            json.append(", \"").append(entry.getKey()).append("\": \"")
                .append(entry.getValue()).append('"');
        }
        json.append('}');
        Files.writeString(root.resolve("deal.json"), json.toString(),
            StandardCharsets.UTF_8);
        for (Map.Entry<String, String> source : sources.entrySet()) {
            writeFile(root.resolve(source.getKey()), source.getValue());
        }
    }

    private static void writeFile(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /**
     * A {@code clang} wrapper that answers {@code --version} with the given
     * text and exit status and otherwise delegates to the real compiler: the
     * accepted window is exercised without needing a second installed LLVM.
     */
    private static void writeWrapper(Path file, String versionText,
            int versionStatus) throws IOException {
        writeWrapper(file, versionText, versionStatus,
            "exec " + NativeCompiler.compiler().path() + " \"$@\"\n");
    }

    /**
     * A {@code clang} wrapper that answers {@code --version} with the given
     * text and exit status and otherwise runs the given shell body: a process
     * fault of the pinned compiler without needing a second installed LLVM.
     */
    private static void writeWrapper(Path file, String versionText,
            int versionStatus, String afterVersion) throws IOException {
        writeFile(file, "#!/bin/sh\n"
            + "if [ \"$1\" = \"--version\" ]; then\n"
            + "  echo \"" + versionText + "\"\n"
            + "  exit " + versionStatus + "\n"
            + "fi\n"
            + afterVersion);
        Set<PosixFilePermission> permissions =
            new LinkedHashSet<>(Files.getPosixFilePermissions(file));
        permissions.add(PosixFilePermission.OWNER_EXECUTE);
        permissions.add(PosixFilePermission.GROUP_EXECUTE);
        permissions.add(PosixFilePermission.OTHERS_EXECUTE);
        Files.setPosixFilePermissions(file, permissions);
    }

    /** A wrapper that states one version major and exits zero. */
    private static void writeWrapper(Path file, int major) throws IOException {
        writeWrapper(file, "clang version " + major + ".0.0", 0);
    }

    // =========================================================================
    // The expected compiler (an independent PATH scan)
    // =========================================================================

    private static Path expectedCompiler() {
        String path = System.getenv("PATH");
        for (String candidate : NativeToolchain.COMPILER_CANDIDATES) {
            for (String directory : path.split(
                    Pattern.quote(File.pathSeparator))) {
                if (directory.isEmpty()) {
                    continue;
                }
                Path executable = Path.of(directory, candidate);
                if (Files.isRegularFile(executable)
                        && Files.isExecutable(executable)) {
                    return executable.toAbsolutePath().normalize();
                }
            }
        }
        throw new IllegalStateException("no clang on PATH");
    }

    // =========================================================================
    // Small helpers
    // =========================================================================

    private static NativeBuildException expectFailure(NativeBuildRequest request,
            Map<String, String> environment) throws Exception {
        Path product = request.productPath();
        boolean directory = Files.isDirectory(product);
        byte[] before = Files.isRegularFile(product)
            ? Files.readAllBytes(product) : null;
        try {
            NativeToolchain.build(request, environment);
        } catch (NativeBuildException failure) {
            if (directory) {
                // The caller named a directory (the publication root itself):
                // only the absence of a partial file can be asserted.
            } else if (before != null) {
                checkEq(hex(before), hex(Files.readAllBytes(product)),
                    "a rejected build preserves the product: " + product);
            } else {
                check(Files.notExists(product),
                    "a rejected build creates no product: " + product);
            }
            check(Files.notExists(partialOf(product)),
                "a rejected build leaves no .partial file: " + product);
            return failure;
        }
        throw new AssertionError("the build was expected to fail but"
            + " succeeded: " + request.productPath());
    }

    private static Path partialOf(Path product) {
        return NativeToolchain.partialPathOf(product);
    }

    /** The empty registry's exact text (H2's count-zero sentinel). */
    private static final String EMPTY_REGISTRY = """
        /*
         * hosts_registry.c - the generated host registry unit of one
         * native build. Generated by deal.toolchain.NativeToolchain;
         * never published and removed with the build scratch.
         */
        #include "deal/native/deal_rt.h"

        const deal_host_registry_entry deal_host_registry[1] = {{NULL, NULL}};
        const size_t deal_host_registry_count = 0;
        """;

    /** The clang argument vector of one verbose transcript, or empty. */
    private static List<String> clangLine(String stdout) {
        for (String line : stdout.split("\\R")) {
            if (line.startsWith("Clang: ")) {
                String vector = line.substring("Clang: ".length()).trim();
                if (vector.isEmpty()) {
                    return List.of();
                }
                return List.of(vector.split(" "));
            }
        }
        return List.of();
    }

    /**
     * Replaces the random scratch registry path with the stable
     * {@code <scratch>} spelling so the printed vector can be compared with the
     * planned one after asserting the scratch's shape.
     */
    private static List<String> maskScratchRegistry(List<String> argv) {
        List<String> masked = new ArrayList<>(argv);
        boolean found = false;
        for (int index = 0; index < masked.size(); index += 1) {
            String token = masked.get(index);
            if (!token.endsWith("/" + HostsRegistry.UNIT_NAME)) {
                continue;
            }
            found = true;
            Path unit = Path.of(token);
            check(unit.getFileName().toString().equals(HostsRegistry.UNIT_NAME),
                "the registry token is the generated unit");
            check(unit.getParent().getFileName().toString().matches(
                    "deal-native-" + ProcessHandle.current().pid()
                        + "-[0-9a-f]+"),
                "the registry lives in this build's scratch: " + token);
            checkEq(Path.of(System.getProperty("java.io.tmpdir"))
                    .toAbsolutePath().normalize(),
                unit.getParent().getParent(),
                "the scratch lives under java.io.tmpdir");
            check(Files.notExists(unit.getParent()),
                "the CLI removes the build scratch");
            masked.set(index, "<scratch>/" + HostsRegistry.UNIT_NAME);
        }
        check(found, "the printed vector carries the generated registry unit");
        return masked;
    }

    /** The {@code deal-native-*} names currently present in java.io.tmpdir. */
    private static Set<String> buildScratchEntries() throws IOException {
        Set<String> entries = new LinkedHashSet<>();
        Path temporary = Path.of(System.getProperty("java.io.tmpdir"))
            .toAbsolutePath().normalize();
        try (Stream<Path> listing = Files.list(temporary)) {
            listing.map(path -> path.getFileName().toString())
                .filter(name -> name.startsWith("deal-native-"))
                .sorted()
                .forEach(entries::add);
        }
        return entries;
    }

    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> snapshot = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                snapshot.put(root.relativize(file).toString(),
                    hex(Files.readAllBytes(file)));
            }
        }
        return snapshot;
    }

    private static Path copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path file : walk.toList()) {
                Path destination = target.resolve(source.relativize(file)
                    .toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.write(destination, Files.readAllBytes(file));
                }
            }
        }
        return target;
    }

    private static String hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException absent) {
            throw new IllegalStateException(absent);
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count += 1;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String absoluteClasspath() {
        List<String> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path")
                .split(Pattern.quote(File.pathSeparator))) {
            if (!entry.isEmpty()) {
                entries.add(Path.of(entry).toAbsolutePath().normalize()
                    .toString());
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    private record Captured(int exitCode, String stdout, String stderr) {
    }

    private interface Run {
        int run() throws Exception;
    }

    private static Captured capture(Run run) throws Exception {
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exitCode = run.run();
            return new Captured(exitCode, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(stdout);
            System.setErr(stderr);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            passed += 1;
        } else {
            failed += 1;
            System.out.println("  FAIL: " + message);
        }
    }

    private static void checkEq(Object expected, Object actual, String message) {
        if (java.util.Objects.equals(expected, actual)) {
            passed += 1;
        } else {
            failed += 1;
            System.out.println("  FAIL: " + message);
            System.out.println("    expected: " + expected);
            System.out.println("    actual:   " + actual);
        }
    }
}
