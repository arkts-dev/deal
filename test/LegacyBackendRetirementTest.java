package deal.test;

import deal.codegen.HarnessModuleCodegen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The legacy-backend retirement audit: the production source set
 * ({@code deal/**}) holds no {@code LuaBackend}/{@code JvmBackend}
 * reference of any kind, and the retained AST emitters live only in the
 * test source set, reached by the production harness arm through the
 * {@link HarnessModuleCodegen} service the test classpath registers.
 *
 * <p>The scan is source-level and deterministic; a violation names the
 * file and the offending token. A synthetic negative control proves the
 * scan is load-bearing.</p>
 */
public final class LegacyBackendRetirementTest {

    /** The retired production-source tokens. */
    private static final List<String> RETIRED_TOKENS =
        List.of("LuaBackend", "JvmBackend");

    /** The production source set root. */
    private static final Path PRODUCTION_ROOT = Path.of("deal");

    /** The retained test-scope emitters (their packages are unchanged so
     * the retained unit tests keep addressing them by name). */
    private static final List<Path> RETAINED_SOURCES = List.of(
        Path.of("test", "legacy", "deal", "codegen", "lua", "LuaBackend.java"),
        Path.of("test", "legacy", "deal", "codegen", "jvm", "JvmBackend.java"));

    /** The test-scope service registration the harness arm consumes. */
    private static final Path SERVICE_REGISTRATION =
        Path.of("test", "META-INF", "services",
            "deal.codegen.HarnessModuleCodegen");

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

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    public static void main(String[] args) throws Exception {
        testProductionSourceIsRetired();
        testRetainedEmittersAreTestScoped();
        testHarnessServiceIsRegistered();
        testNegativeControl();
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // 1. Zero retired tokens in the production source set
    // =========================================================================

    private static void testProductionSourceIsRetired() throws IOException {
        System.out.println("-- the production source set (deal/**) carries "
            + "no LuaBackend/JvmBackend reference --");

        List<String> violations = scan(PRODUCTION_ROOT, RETIRED_TOKENS);
        check(violations.isEmpty(),
            "deal/** holds no retired token: " + violations);

        // The scan is exhaustive over the source extension in the
        // production set (no extension filter hides a reference).
        long files = 0;
        try (Stream<Path> walk = Files.walk(PRODUCTION_ROOT)) {
            files = walk.filter(Files::isRegularFile).count();
        }
        check(files > 0, "the production source set is non-empty ("
            + files + " files)");

        // The class files carry no reference either: the production
        // compile cannot have resolved the retained classes (the
        // production set is compiled on its own).
        Path productionClasses = Path.of("build", "deal", "codegen", "jvm",
            "JvmNames.class");
        check(Files.isRegularFile(productionClasses),
            "the shared naming surface is compiled into the production "
                + "class tree: " + productionClasses);
        check(!Files.exists(Path.of("build", "deal", "codegen", "jvm",
                "JvmBackend.class")),
            "the production class tree holds no retained JVM emitter");
        check(!Files.exists(Path.of("build", "deal", "codegen", "lua",
                "LuaBackend.class")),
            "the production class tree holds no retained LuaJIT emitter");
    }

    // =========================================================================
    // 2. The retained emitters live in the test source set
    // =========================================================================

    private static void testRetainedEmittersAreTestScoped() throws IOException {
        System.out.println("-- the retained AST emitters are test-scope "
            + "sources --");

        for (Path retained : RETAINED_SOURCES) {
            check(Files.isRegularFile(retained),
                "the retained emitter source exists: " + retained);
        }
        // Their packages are unchanged, so the retained unit tests and the
        // conformance harnesses address them by their canonical names.
        String lua = Files.readString(RETAINED_SOURCES.get(0),
            StandardCharsets.UTF_8);
        check(lua.startsWith("package deal.codegen.lua;"),
            "the retained LuaJIT emitter keeps its canonical package");
        String jvm = Files.readString(RETAINED_SOURCES.get(1),
            StandardCharsets.UTF_8);
        check(jvm.startsWith("package deal.codegen.jvm;"),
            "the retained JVM emitter keeps its canonical package");
    }

    // =========================================================================
    // 3. The harness arm resolves the test-scope service
    // =========================================================================

    private static void testHarnessServiceIsRegistered() throws IOException {
        System.out.println("-- the production harness arm resolves the "
            + "test-scope service --");

        check(Files.isRegularFile(SERVICE_REGISTRATION),
            "the test-scope service registration exists: "
                + SERVICE_REGISTRATION);
        if (Files.isRegularFile(SERVICE_REGISTRATION)) {
            String registration = Files.readString(SERVICE_REGISTRATION,
                StandardCharsets.UTF_8).trim();
            check(registration.equals(
                    "deal.codegen.HarnessModuleCodegenProvider"),
                "the registration names the test-scope provider: "
                    + registration);
        }

        HarnessModuleCodegen codegen = HarnessModuleCodegen.current();
        check(codegen != null, "the harness codegen resolves");
        if (codegen != null) {
            check("deal.codegen.HarnessModuleCodegenProvider".equals(
                    codegen.getClass().getName()),
                "the resolved codegen is the test-scope provider: "
                    + codegen.getClass().getName());
        }

        // The retained emitters and the production naming surface agree on
        // the shared name derivations (one derivation per name class).
        check("Main".equals(codegenClassName("main")),
            "the shared classNameFor derivation resolves a module path");
        check(deal.codegen.jvm.JvmNames.classNameFor("main")
                .equals(deal.codegen.jvm.JvmBackend.classNameFor("main")),
            "the production and retained name derivations agree");
        check(deal.codegen.jvm.JvmNames.javaName("class")
                .equals(deal.codegen.jvm.JvmBackend.javaName("class")),
            "the production and retained identifier translations agree");
        check(deal.codegen.jvm.JvmNames.escapedIdentifier("a_b")
                .equals(deal.codegen.jvm.JvmBackend.escapedIdentifier("a_b")),
            "the production and retained identifier escapes agree");
    }

    private static String codegenClassName(String modulePath) {
        return deal.codegen.jvm.JvmNames.classNameFor(modulePath);
    }

    // =========================================================================
    // 4. The negative control: the scan detects a seeded violation
    // =========================================================================

    private static void testNegativeControl() throws IOException {
        System.out.println("-- the scan detects a seeded production-source "
            + "violation --");

        Path scratch = Files.createTempDirectory("legacy-retirement-");
        try {
            Path seeded = scratch.resolve("Seeded.java");
            Files.writeString(seeded, "import deal.codegen.jvm.JvmBackend;\n",
                StandardCharsets.UTF_8);
            List<String> violations = scan(scratch, RETIRED_TOKENS);
            check(violations.size() == 1,
                "the seeded reference is reported exactly once: " + violations);
            check(!violations.isEmpty()
                    && violations.get(0).contains("Seeded.java")
                    && violations.get(0).contains("JvmBackend"),
                "the violation names the file and the token: " + violations);
        } finally {
            deleteRecursively(scratch);
        }
    }

    /** One violation per file line holding a retired token. */
    private static List<String> scan(Path root, List<String> tokens)
            throws IOException {
        List<String> violations = new ArrayList<>();
        if (!Files.exists(root)) {
            return violations;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = new ArrayList<>();
            walk.filter(Files::isRegularFile).sorted().forEach(files::add);
            for (Path file : files) {
                List<String> lines = Files.readAllLines(file,
                    StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    for (String token : tokens) {
                        if (lines.get(i).contains(token)) {
                            violations.add(file + ":" + (i + 1)
                                + ": " + token);
                        }
                    }
                }
            }
        }
        return violations;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> entries = walk.sorted(
                java.util.Comparator.reverseOrder()).toList();
            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
