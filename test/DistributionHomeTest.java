package deal.test;

import deal.distribution.DistributionHome;
import deal.module.StdlibModuleResolver;
import deal.types.Type;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * DistributionHome tier-selection proofs (ISSUE-0457,
 * {@code release-distribution-packaging-and-discovery} D3): each
 * requested surface — stdlib declarations, stdlib {@code .lua}/{@code
 * .js} implementations, and the runtime sources — resolves to exactly
 * one source per request in the pinned order
 * <ol>
 *   <li>project-local surface ({@code <manifestDir>/std}) — project
 *       bytes win over every distribution tier;</li>
 *   <li>language distribution — classpath-resource bytes (the
 *       {@code std/} prefix on the classpath), then the
 *       {@code DEAL_HOME}/{@code deal.home} filesystem layout when the
 *       resources lack the {@code std/} prefix;</li>
 *   <li>checkout CWD dev fallback ({@code <cwd>/std},
 *       {@code <cwd>/deal/runtime.*}) — the unchanged developer
 *       loop.</li>
 * </ol>
 *
 * <p>Every assertion is a real resolution: the suite reads marker bytes
 * staged in each tier and fails on any tier-selection divergence, so
 * the {@code run_tests.sh} registration executes the proofs (never dead
 * code). The suite runs from the repository root — the CWD tier's
 * {@code std/} and {@code deal/runtime.*} are the committed checkout
 * files — and stages its classpath/DEAL_HOME/project tiers in temporary
 * directories.</p>
 */
public class DistributionHomeTest {

    private static int passed = 0;
    private static int failed = 0;
    private static int skipped = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void checkBytes(String expected, String actual,
                                   String message) {
        check(expected.equals(actual), message + " (expected <"
            + expected + ">, actual <" + actual + ">)");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Distribution Home Tier-Selection Tests (ISSUE-0457) ===\n");

        testProjectLocalSurfacePrecedence();
        testClasspathResourceTier();
        testDealHomeFilesystemTier();
        testCwdFallbackTier();
        testAllSixModulesResolverDriven();
        testFileAtStdIsNotASurface();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed
            + ", Skipped: " + skipped);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Tier 1: the project-local surface wins over every distribution tier
    // =========================================================================

    private static void testProjectLocalSurfacePrecedence() throws Exception {
        System.out.println("-- Project-local surface precedence");

        Path project = tempDir("project-local");
        Path classpathRoot = tempDir("project-local-cp");
        Path dealHome = tempDir("project-local-home");

        writeText(project.resolve("std/console.d.deal"),
            "export function projectConsole(): null;\n");
        writeText(project.resolve("std/console.lua"),
            "project console lua\n");

        writeText(classpathRoot.resolve("std/console.d.deal"),
            "export function resourceConsole(): null;\n");
        writeText(classpathRoot.resolve("std/console.lua"),
            "resource console lua\n");
        writeText(classpathRoot.resolve("deal/runtime.lua"),
            "resource runtime\n");

        writeText(dealHome.resolve("std/console.d.deal"),
            "export function homeConsole(): null;\n");
        writeText(dealHome.resolve("std/console.lua"),
            "home console lua\n");
        writeText(dealHome.resolve("deal/runtime.lua"),
            "home runtime\n");

        System.setProperty(DistributionHome.DEAL_HOME_PROPERTY,
            dealHome.toString());
        try {
            DistributionHome home = DistributionHome.forManifestDirectory(
                project.toString(), loaderOver(classpathRoot));

            // All four tiers present: the project-local surface wins.
            Optional<DistributionHome.ResolvedSurface> surface =
                home.resolveStdlibSurface();
            check(surface.isPresent()
                    && DistributionHome.TIER_PROJECT_LOCAL.equals(
                        surface.get().tier()),
                "project-local surface wins when all tiers are present");
            if (surface.isPresent()) {
                check(realText(project.resolve("std")).equals(
                        realText(Path.of(surface.get().pathText()))),
                    "surface path is <manifestDir>/std: "
                        + surface.get().pathText());
            }

            Optional<DistributionHome.ResolvedSource> decl =
                home.resolveStdlibDeclaration("console");
            check(decl.isPresent()
                    && DistributionHome.TIER_PROJECT_LOCAL.equals(
                        decl.get().tier()),
                "declaration read wins the project-local tier");
            checkBytes("export function projectConsole(): null;\n",
                readAll(decl),
                "declaration bytes are the project's bytes");

            Optional<DistributionHome.ResolvedSource> impl =
                home.resolveStdlibImplementation("console", "lua");
            check(impl.isPresent()
                    && DistributionHome.TIER_PROJECT_LOCAL.equals(
                        impl.get().tier()),
                "stdlib .lua read wins the project-local tier");
            checkBytes("project console lua\n", readAll(impl),
                "stdlib .lua bytes are the project's bytes");

            // No project-local location is pinned for the runtime: the
            // classpath-resource tier provides it and beats DEAL_HOME.
            Optional<DistributionHome.ResolvedSource> runtime =
                home.resolveRuntimeSource("deal/runtime.lua");
            check(runtime.isPresent()
                    && DistributionHome.TIER_CLASSPATH_RESOURCES.equals(
                        runtime.get().tier()),
                "runtime resolves from classpath resources (beats DEAL_HOME)");
            checkBytes("resource runtime\n", readAll(runtime),
                "runtime bytes are the classpath-resource bytes");

            Map<String, Map<String, Type>> exports =
                StdlibModuleResolver.stdlibExports(home);
            check(exports.containsKey("std/console")
                    && exports.get("std/console") != null
                    && exports.get("std/console")
                        .containsKey("projectConsole"),
                "resolver-driven stdlib exports read the project's declaration");
        } finally {
            System.clearProperty(DistributionHome.DEAL_HOME_PROPERTY);
        }

        deleteTree(project);
        deleteTree(classpathRoot);
        deleteTree(dealHome);
    }

    // =========================================================================
    // Tier 2a: classpath-resource bytes with no project-local surface
    // =========================================================================

    private static void testClasspathResourceTier() throws Exception {
        System.out.println("-- Classpath-resource distribution tier");

        Path manifestDir = tempDir("classpath-manifest");
        Path classpathRoot = tempDir("classpath-root");
        Path dealHome = tempDir("classpath-home");

        writeText(classpathRoot.resolve("std/console.d.deal"),
            "export function resourceConsole(): null;\n");
        writeText(classpathRoot.resolve("std/console.lua"),
            "resource console lua\n");
        writeText(classpathRoot.resolve("deal/runtime.lua"),
            "resource runtime\n");

        writeText(dealHome.resolve("std/console.d.deal"),
            "export function homeConsole(): null;\n");
        writeText(dealHome.resolve("deal/runtime.lua"),
            "home runtime\n");

        System.setProperty(DistributionHome.DEAL_HOME_PROPERTY,
            dealHome.toString());
        try {
            DistributionHome home = DistributionHome.forManifestDirectory(
                manifestDir.toString(), loaderOver(classpathRoot));

            // No project-local std/, resources carry the std/ prefix:
            // the materialized classpath surface is selected (and wins
            // over the DEAL_HOME filesystem tier).
            Optional<DistributionHome.ResolvedSurface> surface =
                home.resolveStdlibSurface();
            check(surface.isPresent()
                    && DistributionHome.TIER_CLASSPATH_RESOURCES.equals(
                        surface.get().tier()),
                "classpath-resource surface wins with no project-local std/");
            if (surface.isPresent()) {
                check(realText(classpathRoot.resolve("std")).equals(
                        realText(Path.of(surface.get().pathText()))),
                    "surface path materializes to the resource std/ directory: "
                        + surface.get().pathText());
            }

            Optional<DistributionHome.ResolvedSource> decl =
                home.resolveStdlibDeclaration("console");
            check(decl.isPresent()
                    && DistributionHome.TIER_CLASSPATH_RESOURCES.equals(
                        decl.get().tier()),
                "declaration read wins the classpath-resource tier");
            checkBytes("export function resourceConsole(): null;\n",
                readAll(decl),
                "declaration bytes are the classpath-resource bytes");

            Optional<DistributionHome.ResolvedSource> impl =
                home.resolveStdlibImplementation("console", "lua");
            check(impl.isPresent()
                    && DistributionHome.TIER_CLASSPATH_RESOURCES.equals(
                        impl.get().tier()),
                "stdlib .lua read wins the classpath-resource tier");
            checkBytes("resource console lua\n", readAll(impl),
                "stdlib .lua bytes are the classpath-resource bytes");

            Optional<DistributionHome.ResolvedSource> runtime =
                home.resolveRuntimeSource("deal/runtime.lua");
            check(runtime.isPresent()
                    && DistributionHome.TIER_CLASSPATH_RESOURCES.equals(
                        runtime.get().tier()),
                "runtime wins the classpath-resource tier (beats DEAL_HOME)");
            checkBytes("resource runtime\n", readAll(runtime),
                "runtime bytes are the classpath-resource bytes");

            Map<String, Map<String, Type>> exports =
                StdlibModuleResolver.stdlibExports(home);
            check(exports.containsKey("std/console")
                    && exports.get("std/console") != null
                    && exports.get("std/console")
                        .containsKey("resourceConsole"),
                "resolver-driven stdlib exports read the resource declaration");
        } finally {
            System.clearProperty(DistributionHome.DEAL_HOME_PROPERTY);
        }

        deleteTree(manifestDir);
        deleteTree(classpathRoot);
        deleteTree(dealHome);
    }

    // =========================================================================
    // Tier 2b: the DEAL_HOME filesystem tier when resources lack the
    // std/ prefix
    // =========================================================================

    private static void testDealHomeFilesystemTier() throws Exception {
        System.out.println("-- DEAL_HOME filesystem distribution tier");

        Path manifestDir = tempDir("home-manifest");
        Path classpathRoot = tempDir("home-classpath");
        Path dealHome = tempDir("home-root");

        // The classpath carries resources but lacks the std/ prefix.
        writeText(classpathRoot.resolve("unrelated.txt"),
            "not a distribution surface\n");

        writeText(dealHome.resolve("std/console.d.deal"),
            "export function homeConsole(): null;\n");
        writeText(dealHome.resolve("std/console.lua"),
            "home console lua\n");
        writeText(dealHome.resolve("deal/runtime.lua"),
            "home runtime\n");

        System.setProperty(DistributionHome.DEAL_HOME_PROPERTY,
            dealHome.toString());
        try {
            DistributionHome home = DistributionHome.forManifestDirectory(
                manifestDir.toString(), loaderOver(classpathRoot));

            // Resources lack the std/ prefix: the DEAL_HOME filesystem
            // layout is used (and wins over the checkout CWD tier).
            Optional<DistributionHome.ResolvedSurface> surface =
                home.resolveStdlibSurface();
            check(surface.isPresent()
                    && DistributionHome.TIER_DISTRIBUTION_HOME.equals(
                        surface.get().tier()),
                "DEAL_HOME filesystem surface wins when resources lack"
                    + " the std/ prefix");
            if (surface.isPresent()) {
                check(realText(dealHome.resolve("std")).equals(
                        realText(Path.of(surface.get().pathText()))),
                    "surface path is $DEAL_HOME/std: "
                        + surface.get().pathText());
            }

            Optional<DistributionHome.ResolvedSource> decl =
                home.resolveStdlibDeclaration("console");
            check(decl.isPresent()
                    && DistributionHome.TIER_DISTRIBUTION_HOME.equals(
                        decl.get().tier()),
                "declaration read wins the DEAL_HOME filesystem tier");
            checkBytes("export function homeConsole(): null;\n",
                readAll(decl),
                "declaration bytes are the DEAL_HOME bytes");

            Optional<DistributionHome.ResolvedSource> impl =
                home.resolveStdlibImplementation("console", "lua");
            check(impl.isPresent()
                    && DistributionHome.TIER_DISTRIBUTION_HOME.equals(
                        impl.get().tier()),
                "stdlib .lua read wins the DEAL_HOME filesystem tier");
            checkBytes("home console lua\n", readAll(impl),
                "stdlib .lua bytes are the DEAL_HOME bytes");

            Optional<DistributionHome.ResolvedSource> runtime =
                home.resolveRuntimeSource("deal/runtime.lua");
            check(runtime.isPresent()
                    && DistributionHome.TIER_DISTRIBUTION_HOME.equals(
                        runtime.get().tier()),
                "runtime wins the DEAL_HOME filesystem tier"
                    + " (beats the checkout CWD)");
            checkBytes("home runtime\n", readAll(runtime),
                "runtime bytes are the DEAL_HOME bytes");

            Map<String, Map<String, Type>> exports =
                StdlibModuleResolver.stdlibExports(home);
            check(exports.containsKey("std/console")
                    && exports.get("std/console") != null
                    && exports.get("std/console")
                        .containsKey("homeConsole"),
                "resolver-driven stdlib exports read the DEAL_HOME declaration");

            // A blank deal.home value is ignored: the resolution falls
            // through to the CWD dev tier (the checkout std/ exists).
            System.setProperty(DistributionHome.DEAL_HOME_PROPERTY, "  ");
            DistributionHome blankHome = DistributionHome.forManifestDirectory(
                manifestDir.toString(), loaderOver(classpathRoot));
            Optional<DistributionHome.ResolvedSurface> blankSurface =
                blankHome.resolveStdlibSurface();
            check(blankSurface.isPresent()
                    && DistributionHome.TIER_CWD_FALLBACK.equals(
                        blankSurface.get().tier()),
                "a blank deal.home property is ignored (CWD fallback wins)");
        } finally {
            System.clearProperty(DistributionHome.DEAL_HOME_PROPERTY);
        }

        deleteTree(manifestDir);
        deleteTree(classpathRoot);
        deleteTree(dealHome);
    }

    // =========================================================================
    // Tier 3: the checkout CWD dev fallback with no distribution present
    // =========================================================================

    private static void testCwdFallbackTier() throws Exception {
        System.out.println("-- CWD dev fallback tier");

        if (System.getenv(DistributionHome.DEAL_HOME_ENV) != null
                && !System.getenv(DistributionHome.DEAL_HOME_ENV).isBlank()) {
            fail("DEAL_HOME is set in the environment; the CWD-fallback"
                + " tier test requires an unset DEAL_HOME");
            return;
        }
        if (System.getProperty(DistributionHome.DEAL_HOME_PROPERTY) != null) {
            fail("deal.home is set; the CWD-fallback tier test requires it"
                + " unset");
            return;
        }

        Path manifestDir = tempDir("cwd-manifest");
        Path emptyClasspath = tempDir("cwd-classpath");

        DistributionHome home = DistributionHome.forManifestDirectory(
            manifestDir.toString(), loaderOver(emptyClasspath));

        Path checkoutStd = Path.of("std").toAbsolutePath().normalize();

        Optional<DistributionHome.ResolvedSurface> surface =
            home.resolveStdlibSurface();
        check(surface.isPresent()
                && DistributionHome.TIER_CWD_FALLBACK.equals(
                    surface.get().tier()),
            "with no distribution present the CWD surface wins");
        if (surface.isPresent()) {
            check(realText(checkoutStd).equals(
                    realText(Path.of(surface.get().pathText()))),
                "surface path is the checkout <cwd>/std: "
                    + surface.get().pathText());
        }

        Optional<DistributionHome.ResolvedSource> decl =
            home.resolveStdlibDeclaration("console");
        check(decl.isPresent()
                && DistributionHome.TIER_CWD_FALLBACK.equals(
                    decl.get().tier()),
            "declaration read wins the CWD fallback tier");
        checkBytes(readRepoFile("std/console.d.deal"), readAll(decl),
            "declaration bytes are the checkout std/console.d.deal bytes");

        Optional<DistributionHome.ResolvedSource> impl =
            home.resolveStdlibImplementation("console", "lua");
        check(impl.isPresent()
                && DistributionHome.TIER_CWD_FALLBACK.equals(
                    impl.get().tier()),
            "stdlib .lua read wins the CWD fallback tier");
        checkBytes(readRepoFile("std/console.lua"), readAll(impl),
            "stdlib .lua bytes are the checkout std/console.lua bytes");

        Optional<DistributionHome.ResolvedSource> runtime =
            home.resolveRuntimeSource("deal/runtime.lua");
        check(runtime.isPresent()
                && DistributionHome.TIER_CWD_FALLBACK.equals(
                    runtime.get().tier()),
            "runtime read wins the CWD fallback tier");
        checkBytes(readRepoFile("deal/runtime.lua"), readAll(runtime),
            "runtime bytes are the checkout deal/runtime.lua bytes");

        Optional<DistributionHome.ResolvedSource> jsRuntime =
            home.resolveRuntimeSource("deal/runtime.js");
        check(jsRuntime.isPresent()
                && DistributionHome.TIER_CWD_FALLBACK.equals(
                    jsRuntime.get().tier()),
            "JS runtime read wins the CWD fallback tier");
        checkBytes(readRepoFile("deal/runtime.js"), readAll(jsRuntime),
            "JS runtime bytes are the checkout deal/runtime.js bytes");

        deleteTree(manifestDir);
        deleteTree(emptyClasspath);
    }

    // =========================================================================
    // All six spec-listed modules resolve in each environment
    // =========================================================================

    private static void testAllSixModulesResolverDriven() throws Exception {
        System.out.println("-- All six spec-listed modules in each environment");

        // Classpath-resource environment: the six real declarations with
        // per-module marker exports.
        Path classpathRoot = tempDir("six-classpath");
        for (String name : StdlibModuleResolver.SPEC_STDLIB_MODULES
                .stream().map(m -> m.substring("std/".length())).toList()) {
            String marker = "export function marker_" + name
                + "_resource(): null;\n";
            writeText(classpathRoot.resolve("std/" + name + ".d.deal"),
                marker + readRepoFile("std/" + name + ".d.deal"));
        }
        Path manifestDir = tempDir("six-manifest");
        DistributionHome resourceHome = DistributionHome.forManifestDirectory(
            manifestDir.toString(), loaderOver(classpathRoot));
        for (String name : DistributionHome.SPEC_STDLIB_MODULE_NAMES) {
            Optional<DistributionHome.ResolvedSource> decl =
                resourceHome.resolveStdlibDeclaration(name);
            check(decl.isPresent()
                    && DistributionHome.TIER_CLASSPATH_RESOURCES.equals(
                        decl.get().tier()),
                "classpath environment: std/" + name
                    + ".d.deal resolves from the resource tier");
            checkBytes("export function marker_" + name
                    + "_resource(): null;\n" + readRepoFile(
                        "std/" + name + ".d.deal"),
                readAll(decl),
                "classpath environment: std/" + name
                    + ".d.deal bytes are the staged resource bytes");
        }
        Map<String, Map<String, Type>> resourceExports =
            StdlibModuleResolver.stdlibExports(resourceHome);
        for (String name : DistributionHome.SPEC_STDLIB_MODULE_NAMES) {
            check(resourceExports.containsKey("std/" + name)
                    && resourceExports.get("std/" + name) != null
                    && resourceExports.get("std/" + name)
                        .containsKey("marker_" + name + "_resource"),
                "classpath environment: std/" + name
                    + " parses through the resolver with its marker export");
        }

        // DEAL_HOME environment: resources lack the std/ prefix, all six
        // declarations live under $DEAL_HOME/std.
        Path dealHome = tempDir("six-home");
        for (String name : StdlibModuleResolver.SPEC_STDLIB_MODULES
                .stream().map(m -> m.substring("std/".length())).toList()) {
            String marker = "export function marker_" + name
                + "_home(): null;\n";
            writeText(dealHome.resolve("std/" + name + ".d.deal"),
                marker + readRepoFile("std/" + name + ".d.deal"));
        }
        Path emptyClasspath = tempDir("six-empty-cp");
        System.setProperty(DistributionHome.DEAL_HOME_PROPERTY,
            dealHome.toString());
        try {
            DistributionHome homeHome = DistributionHome.forManifestDirectory(
                manifestDir.toString(), loaderOver(emptyClasspath));
            for (String name : DistributionHome.SPEC_STDLIB_MODULE_NAMES) {
                Optional<DistributionHome.ResolvedSource> decl =
                    homeHome.resolveStdlibDeclaration(name);
                check(decl.isPresent()
                        && DistributionHome.TIER_DISTRIBUTION_HOME.equals(
                            decl.get().tier()),
                    "DEAL_HOME environment: std/" + name
                        + ".d.deal resolves from the filesystem tier");
                checkBytes("export function marker_" + name
                        + "_home(): null;\n" + readRepoFile(
                            "std/" + name + ".d.deal"),
                    readAll(decl),
                    "DEAL_HOME environment: std/" + name
                        + ".d.deal bytes are the staged home bytes");
            }
            Map<String, Map<String, Type>> homeExports =
                StdlibModuleResolver.stdlibExports(homeHome);
            for (String name : DistributionHome.SPEC_STDLIB_MODULE_NAMES) {
                check(homeExports.containsKey("std/" + name)
                        && homeExports.get("std/" + name) != null
                        && homeExports.get("std/" + name)
                            .containsKey("marker_" + name + "_home"),
                    "DEAL_HOME environment: std/" + name
                        + " parses through the resolver with its marker export");
            }
        } finally {
            System.clearProperty(DistributionHome.DEAL_HOME_PROPERTY);
        }

        // CWD environment: all six resolve from the checkout.
        DistributionHome cwdHome = DistributionHome.forManifestDirectory(
            manifestDir.toString(), loaderOver(emptyClasspath));
        for (String name : DistributionHome.SPEC_STDLIB_MODULE_NAMES) {
            Optional<DistributionHome.ResolvedSource> decl =
                cwdHome.resolveStdlibDeclaration(name);
            check(decl.isPresent()
                    && DistributionHome.TIER_CWD_FALLBACK.equals(
                        decl.get().tier()),
                "CWD environment: std/" + name
                    + ".d.deal resolves from the dev fallback tier");
            checkBytes(readRepoFile("std/" + name + ".d.deal"),
                readAll(decl),
                "CWD environment: std/" + name
                    + ".d.deal bytes are the checkout bytes");
        }
        Map<String, Map<String, Type>> cwdExports =
            StdlibModuleResolver.stdlibExports(cwdHome);
        for (String name : DistributionHome.SPEC_STDLIB_MODULE_NAMES) {
            check(cwdExports.containsKey("std/" + name)
                    && cwdExports.get("std/" + name) != null,
                "CWD environment: std/" + name
                    + " is present in the resolver-driven export map");
        }

        deleteTree(classpathRoot);
        deleteTree(manifestDir);
        deleteTree(dealHome);
        deleteTree(emptyClasspath);
    }

    // =========================================================================
    // A file at <manifestDir>/std is not a directory surface
    // =========================================================================

    private static void testFileAtStdIsNotASurface() throws Exception {
        System.out.println("-- A file at <manifestDir>/std is not a surface");

        Path manifestDir = tempDir("file-std-manifest");
        writeText(manifestDir.resolve("std"), "not a directory\n");
        Path emptyClasspath = tempDir("file-std-cp");

        DistributionHome home = DistributionHome.forManifestDirectory(
            manifestDir.toString(), loaderOver(emptyClasspath));

        Optional<DistributionHome.ResolvedSurface> surface =
            home.resolveStdlibSurface();
        check(surface.isEmpty()
                || !DistributionHome.TIER_PROJECT_LOCAL.equals(
                    surface.get().tier()),
            "a file at <manifestDir>/std never yields the project-local"
                + " surface");

        // The declaration read falls through to the CWD dev tier (the
        // checkout std/ is present).
        Optional<DistributionHome.ResolvedSource> decl =
            home.resolveStdlibDeclaration("console");
        check(decl.isPresent()
                && DistributionHome.TIER_CWD_FALLBACK.equals(
                    decl.get().tier()),
            "declaration read falls through a file-at-std to the CWD tier");

        deleteTree(manifestDir);
        deleteTree(emptyClasspath);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Path tempDir(String label) throws IOException {
        return Files.createTempDirectory("distribution-home-" + label + "-");
    }

    private static void writeText(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static String readRepoFile(String relative) throws IOException {
        return Files.readString(Path.of(relative).toAbsolutePath().normalize());
    }

    private static String readAll(
            Optional<DistributionHome.ResolvedSource> source)
            throws IOException {
        if (source.isEmpty()) {
            return null;
        }
        try (InputStream in = source.get().open()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ClassLoader loaderOver(Path root) throws IOException {
        return new URLClassLoader(new URL[]{root.toUri().toURL()}, null);
    }

    private static String realText(Path path) {
        try {
            return path.toRealPath().toString();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize().toString();
        }
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // Best-effort cleanup only.
                    }
                });
        } catch (IOException ignored) {
            // Best-effort cleanup only.
        }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }
}
