package deal.test;

import deal.distribution.DistributionHome;
import deal.toolchain.NativeRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The C11 runtime deployment set ({@code llvm-native-backend-integration} D6).
 *
 * <p>Asserts the closed ordered authority and both consumers of it: the
 * checkout/classpath resolution every deployment copy goes through, and the
 * packaging script that ships the same set. Evidence is the published artifact
 * set read back and byte-compared — never a hand-assembled directory:</p>
 *
 * <ol>
 *   <li>{@link NativeRuntime#FILES} is exactly the D6 list in the pinned order,
 *       every file exists at its exact relative path, the {@code main.c}
 *       translation unit holds exactly the process entry, and the translation
 *       unit list is the set's {@code .c} files in that order;</li>
 *   <li>every name resolves through
 *       {@link DistributionHome#resolveRuntimeSource(String)} with no
 *       project-local tier, classpath-first, and the deployment path writes all
 *       of them at the pinned paths with checkout-equal bytes;</li>
 *   <li>a distribution missing a runtime file is the pinned E6000
 *       {@code Runtime library not found: <path>} result and publishes
 *       nothing;</li>
 *   <li>{@code tools/build-release.sh} requires and copies exactly this set and
 *       still ships the existing runtime, driver and stdlib assets.</li>
 * </ol>
 */
public final class NativeRuntimeDeploymentTest {

    private static final Path REPO = NativeCompiler.repository();

    /** The D6 set, restated in the suite as an independent expectation. */
    private static final List<String> PINNED_SET = List.of(
        "deal/native/deal_rt.h",
        "deal/native/platform.c", "deal/native/platform.h",
        "deal/native/value.c", "deal/native/value.h",
        "deal/native/memory.c", "deal/native/memory.h",
        "deal/native/text.c", "deal/native/text.h",
        "deal/native/container.c", "deal/native/container.h",
        "deal/native/descriptor.c", "deal/native/descriptor.h",
        "deal/native/error.c", "deal/native/error.h",
        "deal/native/json.c", "deal/native/json.h",
        "deal/native/std.c", "deal/native/std.h",
        "deal/native/host.c", "deal/native/host.h",
        "deal/native/async.c", "deal/native/async.h",
        "deal/native/entry.c", "deal/native/entry.h",
        "deal/native/main.c"
    );

    /** The runtime, driver and stdlib assets every distribution still carries. */
    private static final List<String> EXISTING_ASSETS = List.of(
        "deal/runtime.lua", "deal/runtime.js",
        "deal/lua_async_export_driver.lua",
        "std/console.d.deal", "std/console.lua", "std/console.js",
        "std/string.d.deal", "std/string.lua", "std/string.js",
        "std/table.d.deal", "std/table.lua", "std/table.js",
        "std/json.d.deal", "std/json.lua", "std/json.js",
        "std/math.d.deal", "std/math.lua", "std/math.js",
        "std/time.d.deal", "std/time.lua", "std/time.js"
    );

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--resolve-missing")) {
            resolveMissingProbe();
            return;
        }
        System.out.println("=== C11 Runtime Deployment Set Tests (ISSUE-0902 D6) ===\n");
        Path scratch = Files.createTempDirectory("deal-native-deployment-");
        try {
            testPinnedSetAndOrder();
            testProcessEntryUnit();
            testEveryDeploymentFileResolvesClasspathFirst(scratch);
            testDeploymentPathWritesTheSetByteForByte(scratch);
            testMissingDeploymentFileFailsClosed(scratch);
            testIncompletePublishedTreeFailsTheBuild(scratch);
            testBuildReleaseRequiresAndCopiesTheSet();
        } finally {
            deleteRecursively(scratch);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== C11 Runtime Deployment Set Tests Passed ===");
    }

    // =========================================================================
    // The closed ordered authority
    // =========================================================================

    private static void testPinnedSetAndOrder() {
        System.out.println("-- the closed ordered runtime set (D6) --");
        checkEq(PINNED_SET, NativeRuntime.FILES,
            "NativeRuntime.FILES is the D6 set in the pinned order");
        checkEq(PINNED_SET.size(), new TreeSet<>(NativeRuntime.FILES).size(),
            "the pinned set carries no duplicate name");
        for (String relativeName : PINNED_SET) {
            Path file = REPO.resolve(relativeName);
            check(Files.isRegularFile(file),
                "the deployment file exists at its exact path: " + relativeName);
        }
        List<String> units = NativeRuntime.TRANSLATION_UNITS;
        List<String> expectedUnits = PINNED_SET.stream()
            .filter(name -> name.endsWith(".c")).toList();
        checkEq(expectedUnits, units,
            "the translation-unit list is the set's .c files in the pinned order");
        check(NativeRuntime.isProcessEntry("deal/native/main.c"),
            "the process entry is a deployment file");
        check(!NativeRuntime.isProcessEntry("deal/native/entry.c"),
            "entry.c is not the process entry");
        check(NativeRuntime.isDeploymentFile("deal/native/deal_rt.h"),
            "the ABI header belongs to the deployment set");
        check(!NativeRuntime.isDeploymentFile("deal/toolchain/NativeRuntime.java"),
            "the Java file-list authority is not a deployment file");
        check(!NativeRuntime.isDeploymentFile("hosts_registry.c"),
            "the generated registry unit is never part of the published set");
        check(!NativeRuntime.isDeploymentFile("driver.c"),
            "a generated driver unit is never part of the published set");
    }

    private static void testProcessEntryUnit() throws IOException {
        System.out.println("-- main.c holds exactly the process entry --");
        List<String> statements = new ArrayList<>();
        for (String line : Files.readAllLines(REPO.resolve(
                NativeRuntime.PROCESS_ENTRY_FILE), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("/*")
                    || trimmed.startsWith("*") || trimmed.startsWith("//")) {
                continue;
            }
            statements.add(trimmed);
        }
        checkEq(List.of("#include \"deal/native/entry.h\"",
                "int main(void) { return deal_project_main(); }"), statements,
            "main.c is exactly the include plus the one process entry");
    }

    // =========================================================================
    // Resolution and the deployment path
    // =========================================================================

    private static void testEveryDeploymentFileResolvesClasspathFirst(Path scratch)
            throws Exception {
        System.out.println("-- every runtime name resolves classpath-first --");
        Path project = Files.createDirectories(scratch.resolve("project"));
        DistributionHome checkoutHome =
            DistributionHome.forManifestDirectory(project.toString());
        for (String relativeName : PINNED_SET) {
            Optional<DistributionHome.ResolvedSource> resolved =
                checkoutHome.resolveRuntimeSource(relativeName);
            check(resolved.isPresent(),
                "the runtime name resolves at a documented tier: " + relativeName);
            if (resolved.isEmpty()) {
                continue;
            }
            check(!resolved.get().tier().equals(DistributionHome.TIER_PROJECT_LOCAL),
                "no runtime name uses the project-local tier: " + relativeName);
        }

        // Classpath-first: a staged resource distribution wins over the checkout.
        Path staged = Files.createDirectories(
            scratch.resolve("staged-classpath/deal/native"));
        byte[] marker = "/* staged runtime copy */\n".getBytes(StandardCharsets.UTF_8);
        Files.write(staged.resolve("memory.c"), marker);
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {scratch.resolve("staged-classpath").toUri().toURL()},
                NativeRuntimeDeploymentTest.class.getClassLoader())) {
            DistributionHome classpathHome = DistributionHome.forManifestDirectory(
                project.toString(), loader);
            Optional<DistributionHome.ResolvedSource> resolved =
                classpathHome.resolveRuntimeSource("deal/native/memory.c");
            check(resolved.isPresent(), "the staged resource resolves");
            if (resolved.isPresent()) {
                checkEq(DistributionHome.TIER_CLASSPATH_RESOURCES,
                    resolved.get().tier(),
                    "the classpath resources tier wins for a runtime name");
                try (InputStream stream = resolved.get().open()) {
                    NativeCompiler.requireSameBytes(marker, stream.readAllBytes(),
                        "the classpath resource bytes are the resolved bytes");
                }
            }
        }

        // A project-local copy never shadows a runtime file (no such tier).
        Path shadow = Files.createDirectories(project.resolve("deal/native"));
        Files.write(shadow.resolve("memory.c"),
            "/* project-local copy */\n".getBytes(StandardCharsets.UTF_8));
        DistributionHome shadowHome =
            DistributionHome.forManifestDirectory(project.toString());
        Optional<DistributionHome.ResolvedSource> shadowed =
            shadowHome.resolveRuntimeSource("deal/native/memory.c");
        check(shadowed.isPresent()
                && !shadowed.get().tier().equals(DistributionHome.TIER_PROJECT_LOCAL),
            "a project-local runtime copy is outside the pinned tier order");
    }

    private static void testDeploymentPathWritesTheSetByteForByte(Path scratch)
            throws Exception {
        System.out.println("-- the deployment path writes the set byte for byte --");
        Path root = Files.createDirectories(scratch.resolve("published"));
        int written = NativeCompiler.deployDeploymentSet(root);
        checkEq(PINNED_SET.size(), written,
            "the deployment path writes every pinned file");
        checkEq(new TreeSet<>(PINNED_SET), new TreeSet<>(NativeCompiler.filesUnder(root)),
            "the deployed tree carries exactly the pinned set");
        for (String relativeName : PINNED_SET) {
            byte[] checkout = Files.readAllBytes(REPO.resolve(relativeName));
            byte[] deployed = Files.readAllBytes(root.resolve(relativeName));
            NativeCompiler.requireSameBytes(checkout, deployed,
                "the deployed copy equals the checkout file: " + relativeName);
            check(checkout.length > 0,
                "the deployment file is not empty: " + relativeName);
        }

        // The published tree is the include root every build uses: the ABI
        // header resolves from it alone.
        Path probe = Files.createTempDirectory(scratch, "include-probe-");
        Path unit = probe.resolve("probe.c");
        Files.writeString(unit,
            "#include \"deal/native/deal_rt.h\"\nint main(void) { return 0; }\n",
            StandardCharsets.UTF_8);
        NativeCompiler.Result compile = NativeCompiler.compileOnly(root, 0, unit,
            probe.resolve("probe.o"));
        check(compile.succeeded(),
            "the deployed tree is a self-sufficient include root: "
                + compile.describe());
    }

    /**
     * The child half of the fail-closed probe: with no tier carrying the
     * runtime (an empty working directory, an empty DEAL_HOME and a classpath of
     * compiled classes only), resolving the set is the pinned E6000 result.
     */
    private static void resolveMissingProbe() {
        DistributionHome home = DistributionHome.forManifestDirectory(".");
        try {
            NativeRuntime.resolve(home);
            System.out.println("resolved");
            System.exit(3);
        } catch (IllegalStateException expected) {
            System.out.println(expected.getMessage());
            System.exit(0);
        }
    }

    private static void testMissingDeploymentFileFailsClosed(Path scratch)
            throws Exception {
        System.out.println("-- a missing runtime file is the pinned E6000 result --");
        Path empty = Files.createDirectories(scratch.resolve("empty-checkout"));
        List<String> command = List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Ddeal.home=",
            "-cp", absoluteClasspath(),
            NativeRuntimeDeploymentTest.class.getName(),
            "--resolve-missing");
        NativeCompiler.Result probe = NativeCompiler.run(command, empty);
        checkEq(0, probe.exitCode(),
            "the fail-closed probe runs to completion: " + probe.describe());
        checkEq("Runtime library not found: deal/native/deal_rt.h\n",
            probe.stdout(),
            "a runtime file absent at every tier is the pinned E6000 diagnostic");
    }

    private static void testIncompletePublishedTreeFailsTheBuild(Path scratch)
            throws Exception {
        System.out.println("-- an incomplete published tree fails the build --");
        Path root = Files.createDirectories(scratch.resolve("published-incomplete"));
        NativeCompiler.deployDeploymentSet(root);
        Files.delete(root.resolve("deal/native/platform.c"));
        Path product = scratch.resolve("product-incomplete");
        NativeCompiler.Result link = NativeCompiler.link(root, 0,
            NativeCompiler.productSources(root, List.of(
                NativeCompiler.fixtures().resolve("module_stub.c"),
                NativeCompiler.fixtures().resolve("registry_empty.c"))), product);
        check(!link.succeeded(),
            "a link against an incomplete published tree fails: " + link.describe());
        check(link.stderr().contains("platform.c"),
            "the failure names the missing deployment unit: " + link.stderr());
        check(!Files.exists(product),
            "a failed link leaves no build product");
    }

    /** The classpath with every element absolute (the probe changes cwd). */
    private static String absoluteClasspath() {
        List<String> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path")
                .split(Pattern.quote(java.io.File.pathSeparator))) {
            if (!entry.isEmpty()) {
                entries.add(Path.of(entry).toAbsolutePath().normalize().toString());
            }
        }
        return String.join(java.io.File.pathSeparator, entries);
    }

    // =========================================================================
    // The packaging script
    // =========================================================================

    private static void testBuildReleaseRequiresAndCopiesTheSet()
            throws IOException {
        System.out.println("-- build-release.sh requires and copies exactly the set --");
        String script = Files.readString(REPO.resolve("tools/build-release.sh"),
            StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile(
                "NATIVE_RUNTIME_FILES=\\(([^)]*)\\)", Pattern.DOTALL).matcher(script);
        check(matcher.find(), "build-release.sh declares the runtime file list");
        if (!matcher.find(0)) {
            return;
        }
        List<String> declared = new ArrayList<>();
        for (String token : matcher.group(1).trim().split("\\s+")) {
            if (!token.isEmpty()) {
                declared.add(token);
            }
        }
        checkEq(PINNED_SET, declared,
            "the packaging list equals NativeRuntime.FILES in the pinned order");
        checkEq(PINNED_SET.size(), new TreeSet<>(declared).size(),
            "the packaging list carries no duplicate name");

        check(script.contains("\"${NATIVE_RUNTIME_FILES[@]}\""),
            "the required-asset set includes the runtime file list");
        check(Pattern.compile("for file in \"\\$\\{NATIVE_RUNTIME_FILES\\[@\\]\\}\"; do\\s*\\n\\s*cp \"\\$file\" \"dist/\\$file\"")
                .matcher(script).find(),
            "the packaging step copies every runtime file to the same relative path");
        check(script.contains("dist/deal/native"),
            "the packaging completeness check covers the runtime directory");
        check(Pattern.compile(
                "if ! cmp -s \"\\$file\" \"dist/\\$file\"; then").matcher(script).find(),
            "the packaging step byte-compares every shipped runtime copy with "
                + "its checkout file");
        for (String asset : EXISTING_ASSETS) {
            boolean required = asset.startsWith("std/")
                ? script.contains("STDLIB_MODULES=(console string table json math time)")
                    && script.contains("std/$module.d.deal")
                    && script.contains("std/$module.lua")
                    && script.contains("std/$module.js")
                : script.contains(asset);
            check(required, "the existing runtime/stdlib asset set is still "
                + "required: " + asset);
        }
        check(script.contains("install -m 755 tools/deal-launcher.sh dist/bin/deal"),
            "the launcher is still installed");
        check(script.contains("javac --release 25"),
            "the distribution is still compiled from the production classes");
    }

    // =========================================================================
    // Harness
    // =========================================================================

    private static void check(boolean condition, String what) {
        if (condition) {
            passed += 1;
        } else {
            failed += 1;
            System.out.println("FAIL: " + what);
        }
    }

    private static void checkEq(Object expected, Object actual, String what) {
        if (expected.equals(actual)) {
            passed += 1;
        } else {
            failed += 1;
            System.out.println("FAIL: " + what + " (expected " + expected
                + ", got " + actual + ")");
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
