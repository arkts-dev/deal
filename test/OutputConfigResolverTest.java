package deal.project;

import deal.source.SourceScalarRange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The test battery for {@link OutputConfigResolver} (ISSUE-0264 T3,
 * design source {@code strict-project-context-resolution-identity} D3,
 * verification item 4): backend precedence for every combination (CLI
 * alias {@code lua|luajit|jvm|js} over a valid manifest; manifest
 * {@code luajit}/{@code jvm}/{@code js}; absence → {@code luajit});
 * output precedence (CLI &gt; manifest &gt; backend-dependent default);
 * classification of absolute, relative, bare, {@code ./}, {@code ../},
 * and NUL-containing values under both sources; default paths resolving
 * from the manifest directory; filesystem behavior with real temp
 * directories (a non-existent output path classifies successfully with
 * its existing prefix resolved; a symlinked existing prefix resolves on
 * the longest existing directory prefix; the module performs no writes
 * and creates no directory); the conversion-failure shape
 * ({@code source}, {@code reason}, {@code sourceRange} for NUL-bearing
 * and unrepresentable winning values); purity/determinism (identical
 * inputs → identical results, no state); and the combined T1+T2+T3
 * dependency gate (parse a valid manifest through T2, feed its validated
 * backend/output values into T3 with a real temp manifest directory and
 * process CWD, assert the effective backend and the
 * T1-prefix-resolved {@code absoluteNormalizedPath}).
 *
 * <p>Runs via main() using the repository's plain check()-helper
 * convention; exits non-zero on failure.</p>
 */
public final class OutputConfigResolverTest {

    private OutputConfigResolverTest() {
    }

    private static int passed = 0;
    private static int failed = 0;
    private static Path tmpDir;
    private static Path realTmp;
    /** Shared manifest-directory fixture (a real directory). */
    private static Path projectDir;
    /** Shared process-CWD fixture (a real directory). */
    private static Path cwdDir;

    // =========================================================================
    // Test runner
    // =========================================================================

    public static void main(String[] args) throws Exception {
        tmpDir = Files.createTempDirectory("deal-output-resolver-");
        try {
            realTmp = tmpDir.toRealPath();
            projectDir = Files.createDirectories(tmpDir.resolve("project"));
            cwdDir = Files.createDirectories(tmpDir.resolve("cwd"));
            testBackendPrecedence();
            testOutputPrecedence();
            testClassification();
            testDotForms();
            testDefaultPathsResolveFromManifestDirectory();
            testNonexistentOutputWithExistingPrefix();
            testSymlinkedPrefixResolution();
            testNoWritesAndNoCreation();
            testConversionFailureShapes();
            testPurityAndDeterminism();
            testPreconditionErrors();
            testCombinedT1T2();
        } finally {
            cleanup();
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.err.println("OutputConfigResolverTest FAILED: " + failed + " failure(s)");
            System.exit(1);
        }
    }

    // =========================================================================
    // Assertion helpers
    // =========================================================================

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

    /** A manifest output value with a distinguishable range. */
    private static ManifestString ms(String value) {
        return new ManifestString(value, new SourceScalarRange(2, 3, 2, 15, 11, 23));
    }

    private static OutputConfigResolver.OutputRef outputOrFail(
            OutputConfigResolver.Resolution r, String message) {
        if (r.output() != null) {
            passed++;
            return r.output();
        }
        failed++;
        System.err.println("FAIL: " + message + " (expected OutputRef, got failure "
            + r.failure() + ")");
        return null;
    }

    private static OutputConfigResolver.OutputPathFailure failureOrFail(
            OutputConfigResolver.Resolution r, String message) {
        if (r.failure() != null) {
            passed++;
            return r.failure();
        }
        failed++;
        System.err.println("FAIL: " + message + " (expected OutputPathFailure, got "
            + r.output() + ")");
        return null;
    }

    /**
     * Resolves backend-only inputs (no output inputs, so the conversion
     * uses the backend-dependent default over a non-existent absolute
     * directory — which is never a failure).
     */
    private static OutputConfigResolver.Resolution resolveBackend(String manifestBackend,
                                                                  String cliAlias) {
        return OutputConfigResolver.resolve(manifestBackend, null, cliAlias, null,
            Path.of("/nonexistent/project"), Path.of("/nonexistent/cwd"));
    }

    /** True iff the platform supports symbolic-link creation here. */
    private static boolean symlinksSupported() {
        Path target = tmpDir.resolve("symlink-probe-target");
        Path link = tmpDir.resolve("symlink-probe-link");
        try {
            Files.write(target, new byte[] {1});
            Files.createSymbolicLink(link, Path.of("symlink-probe-target"));
            Files.deleteIfExists(link);
            Files.deleteIfExists(target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    /** A sorted, deterministic snapshot of one directory tree's entries. */
    private static List<String> snapshot(Path root) throws IOException {
        List<String> entries = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.forEach(p -> {
                String rel = root.relativize(p).toString();
                entries.add((Files.isDirectory(p) ? "d:" : "f:") + rel);
            });
        }
        entries.sort(Comparator.naturalOrder());
        return entries;
    }

    private static void cleanup() {
        try {
            Files.walk(tmpDir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort temp cleanup
                    }
                });
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }

    // =========================================================================
    // Backend precedence
    // =========================================================================

    private static void testBackendPrecedence() {
        System.out.println("-- Backend precedence: CLI alias > manifest > default luajit --");

        check(resolveBackend(null, null).effectiveBackend().equals("luajit"),
            "absence everywhere -> default 'luajit'");
        check(resolveBackend(null, "lua").effectiveBackend().equals("luajit"),
            "CLI alias 'lua' alone -> 'luajit'");
        check(resolveBackend(null, "luajit").effectiveBackend().equals("luajit"),
            "CLI alias 'luajit' alone -> 'luajit'");
        check(resolveBackend(null, "jvm").effectiveBackend().equals("jvm"),
            "CLI alias 'jvm' alone -> 'jvm'");
        check(resolveBackend(null, "js").effectiveBackend().equals("js"),
            "CLI alias 'js' alone -> 'js' (ISSUE-0169 remediation, ISSUE-0471)");
        check(resolveBackend("luajit", null).effectiveBackend().equals("luajit"),
            "manifest 'luajit' alone -> 'luajit'");
        check(resolveBackend("jvm", null).effectiveBackend().equals("jvm"),
            "manifest 'jvm' alone -> 'jvm'");
        check(resolveBackend("js", null).effectiveBackend().equals("js"),
            "manifest 'js' alone -> 'js'");
        check(resolveBackend(null, null).failure() == null,
            "backend-only resolution always converts the default output (never fails)");

        // Every CLI-alias-over-valid-manifest combination:
        check(resolveBackend("jvm", "lua").effectiveBackend().equals("luajit"),
            "CLI 'lua' over manifest 'jvm' -> 'luajit'");
        check(resolveBackend("jvm", "luajit").effectiveBackend().equals("luajit"),
            "CLI 'luajit' over manifest 'jvm' -> 'luajit'");
        check(resolveBackend("jvm", "jvm").effectiveBackend().equals("jvm"),
            "CLI 'jvm' over manifest 'jvm' -> 'jvm'");
        check(resolveBackend("luajit", "lua").effectiveBackend().equals("luajit"),
            "CLI 'lua' over manifest 'luajit' -> 'luajit'");
        check(resolveBackend("luajit", "luajit").effectiveBackend().equals("luajit"),
            "CLI 'luajit' over manifest 'luajit' -> 'luajit'");
        check(resolveBackend("luajit", "jvm").effectiveBackend().equals("jvm"),
            "CLI 'jvm' over manifest 'luajit' -> 'jvm'");
        check(resolveBackend("luajit", "js").effectiveBackend().equals("js"),
            "CLI 'js' over manifest 'luajit' -> 'js'");
        check(resolveBackend("js", "lua").effectiveBackend().equals("luajit"),
            "CLI 'lua' over manifest 'js' -> 'luajit'");
    }

    // =========================================================================
    // Output precedence
    // =========================================================================

    private static void testOutputPrecedence() {
        System.out.println("-- Output precedence: CLI > manifest > backend-dependent default --");

        OutputConfigResolver.Resolution cliWins = OutputConfigResolver.resolve(
            "luajit", ms("manifest-out"), null, "cli-out", projectDir, cwdDir);
        OutputConfigResolver.OutputRef cliRef = outputOrFail(cliWins, "CLI output wins");
        check(cliWins.failure() == null
                && cliRef.source() == OutputConfigResolver.Source.CLI
                && cliRef.decodedText().equals("cli-out"),
            "validated CLI output wins over a valid manifest output");

        OutputConfigResolver.Resolution cliBeatsAbsolute = OutputConfigResolver.resolve(
            "luajit", ms("/abs/manifest"), null, "cli-out", projectDir, cwdDir);
        check(cliBeatsAbsolute.output() != null
                && cliBeatsAbsolute.output().source() == OutputConfigResolver.Source.CLI,
            "validated CLI output wins even over an absolute manifest output");

        OutputConfigResolver.Resolution manifestWins = OutputConfigResolver.resolve(
            "jvm", ms("manifest-out"), null, null, projectDir, cwdDir);
        check(manifestWins.failure() == null
                && manifestWins.output().source() == OutputConfigResolver.Source.MANIFEST
                && manifestWins.output().decodedText().equals("manifest-out"),
            "validated manifest output wins over the default");

        OutputConfigResolver.Resolution defaultLua = OutputConfigResolver.resolve(
            null, null, null, null, projectDir, cwdDir);
        check(defaultLua.effectiveBackend().equals("luajit")
                && defaultLua.output().decodedText().equals("build/lua"),
            "no output anywhere -> default 'build/lua' for the luajit backend");

        OutputConfigResolver.Resolution defaultJvm = OutputConfigResolver.resolve(
            "jvm", null, null, null, projectDir, cwdDir);
        check(defaultJvm.output().decodedText().equals("build/jvm"),
            "no output anywhere -> default 'build/jvm' for the jvm backend");

        OutputConfigResolver.Resolution defaultJs = OutputConfigResolver.resolve(
            "js", null, null, null, projectDir, cwdDir);
        check(defaultJs.effectiveBackend().equals("js")
                && defaultJs.output().decodedText().equals("build/js"),
            "no output anywhere -> default 'build/js' for the js backend");

        OutputConfigResolver.Resolution aliasDrivesDefault = OutputConfigResolver.resolve(
            "jvm", null, "lua", null, projectDir, cwdDir);
        check(aliasDrivesDefault.effectiveBackend().equals("luajit")
                && aliasDrivesDefault.output().decodedText().equals("build/lua"),
            "the default follows the effective backend after a CLI alias override");
    }

    // =========================================================================
    // Classification: absolute / relative / bare under both sources
    // =========================================================================

    private static void testClassification() {
        System.out.println("-- Classification: absolute and relative under both sources --");

        SourceScalarRange range = new SourceScalarRange(2, 3, 2, 15, 11, 23);

        OutputConfigResolver.OutputRef manifestAbs = outputOrFail(OutputConfigResolver.resolve(
            "luajit", new ManifestString("/abs/out", range), null, null, projectDir, cwdDir),
            "manifest absolute output");
        check(manifestAbs.source() == OutputConfigResolver.Source.MANIFEST
                && manifestAbs.kind() == OutputConfigResolver.Kind.ABSOLUTE_PATH,
            "manifest leading-/ -> MANIFEST source, ABSOLUTE_PATH kind");
        check(manifestAbs.absoluteNormalizedPath().equals("/abs/out"),
            "manifest absolute converts to itself (prefix-resolved)");
        check(manifestAbs.sourceRange().equals(range),
            "manifest OutputRef carries the manifest value range");

        OutputConfigResolver.OutputRef manifestRel = outputOrFail(OutputConfigResolver.resolve(
            "luajit", new ManifestString("rel/out", range), null, null, projectDir, cwdDir),
            "manifest relative output");
        check(manifestRel.source() == OutputConfigResolver.Source.MANIFEST
                && manifestRel.kind() == OutputConfigResolver.Kind.MANIFEST_RELATIVE_PATH,
            "manifest non-leading-/ -> MANIFEST source, MANIFEST_RELATIVE_PATH kind");
        check(manifestRel.absoluteNormalizedPath().equals(
                realTmp.resolve("project").resolve("rel").resolve("out").toString()),
            "manifest relative resolves from the manifest directory");

        OutputConfigResolver.OutputRef cliRel = outputOrFail(OutputConfigResolver.resolve(
            "luajit", null, null, "cli-rel", projectDir, cwdDir),
            "CLI relative output");
        check(cliRel.source() == OutputConfigResolver.Source.CLI
                && cliRel.kind() == OutputConfigResolver.Kind.MANIFEST_RELATIVE_PATH,
            "CLI non-leading-/ -> CLI source, MANIFEST_RELATIVE_PATH kind (pinned D11 names)");
        check(cliRel.absoluteNormalizedPath().equals(
                realTmp.resolve("cwd").resolve("cli-rel").toString()),
            "CLI relative resolves from the process CWD, not the manifest directory");
        check(cliRel.sourceRange() == null,
            "CLI-source OutputRef has no source scalar range");

        OutputConfigResolver.OutputRef cliAbs = outputOrFail(OutputConfigResolver.resolve(
            "luajit", null, null, "/abs/cli", projectDir, cwdDir),
            "CLI absolute output");
        check(cliAbs.kind() == OutputConfigResolver.Kind.ABSOLUTE_PATH
                && cliAbs.absoluteNormalizedPath().equals("/abs/cli"),
            "CLI leading-/ -> ABSOLUTE_PATH (the same rule under both sources)");
    }

    // =========================================================================
    // Classification: bare, ./, ../ under both sources
    // =========================================================================

    private static void testDotForms() {
        System.out.println("-- Classification: bare, ./, ../ under both sources --");

        OutputConfigResolver.OutputRef bare = outputOrFail(OutputConfigResolver.resolve(
            "luajit", ms("build"), null, null, projectDir, cwdDir), "manifest bare output");
        check(bare.kind() == OutputConfigResolver.Kind.MANIFEST_RELATIVE_PATH
                && bare.absoluteNormalizedPath().equals(
                    realTmp.resolve("project").resolve("build").toString()),
            "bare manifest output resolves from the manifest directory");

        OutputConfigResolver.OutputRef dot = outputOrFail(OutputConfigResolver.resolve(
            "luajit", ms("./out"), null, null, projectDir, cwdDir), "manifest ./ output");
        check(dot.kind() == OutputConfigResolver.Kind.MANIFEST_RELATIVE_PATH
                && dot.absoluteNormalizedPath().equals(
                    realTmp.resolve("project").resolve("out").toString()),
            "./ manifest output resolves from the manifest directory");

        OutputConfigResolver.OutputRef dotdot = outputOrFail(OutputConfigResolver.resolve(
            "luajit", ms("../out"), null, null, projectDir, cwdDir), "manifest ../ output");
        check(dotdot.kind() == OutputConfigResolver.Kind.MANIFEST_RELATIVE_PATH
                && dotdot.absoluteNormalizedPath().equals(
                    realTmp.resolve("out").toString()),
            "../ manifest output resolves to the manifest directory's parent");

        OutputConfigResolver.OutputRef cliBare = outputOrFail(OutputConfigResolver.resolve(
            "luajit", null, null, "bareout", projectDir, cwdDir), "CLI bare output");
        check(cliBare.kind() == OutputConfigResolver.Kind.MANIFEST_RELATIVE_PATH
                && cliBare.absoluteNormalizedPath().equals(
                    realTmp.resolve("cwd").resolve("bareout").toString()),
            "bare CLI output resolves from the process CWD");

        OutputConfigResolver.OutputRef cliDot = outputOrFail(OutputConfigResolver.resolve(
            "luajit", null, null, "./x", projectDir, cwdDir), "CLI ./ output");
        check(cliDot.absoluteNormalizedPath().equals(
                realTmp.resolve("cwd").resolve("x").toString()),
            "./ CLI output resolves from the process CWD");

        OutputConfigResolver.OutputRef cliDotdot = outputOrFail(OutputConfigResolver.resolve(
            "luajit", null, null, "../x", projectDir, cwdDir), "CLI ../ output");
        check(cliDotdot.absoluteNormalizedPath().equals(
                realTmp.resolve("x").toString()),
            "../ CLI output resolves to the process CWD's parent");
    }

    // =========================================================================
    // Default paths resolve from the manifest directory
    // =========================================================================

    private static void testDefaultPathsResolveFromManifestDirectory() {
        System.out.println("-- Default paths resolve from the manifest directory --");

        OutputConfigResolver.OutputRef luaDefault = outputOrFail(OutputConfigResolver.resolve(
            "luajit", null, null, null, projectDir, cwdDir), "luajit default");
        check(luaDefault.source() == OutputConfigResolver.Source.MANIFEST
                && luaDefault.kind() == OutputConfigResolver.Kind.MANIFEST_RELATIVE_PATH
                && luaDefault.decodedText().equals("build/lua")
                && luaDefault.sourceRange() == null,
            "luajit default shape: MANIFEST source, relative kind, 'build/lua', no range");
        check(luaDefault.absoluteNormalizedPath().equals(
                realTmp.resolve("project").resolve("build").resolve("lua").toString()),
            "luajit default path resolves from the manifest directory");

        OutputConfigResolver.OutputRef jvmDefault = outputOrFail(OutputConfigResolver.resolve(
            "jvm", null, null, null, projectDir, cwdDir), "jvm default");
        check(jvmDefault.absoluteNormalizedPath().equals(
                realTmp.resolve("project").resolve("build").resolve("jvm").toString()),
            "jvm default path resolves from the manifest directory");

        // A CLI backend override moves the default with it (still manifest-based).
        OutputConfigResolver.OutputRef aliasedDefault = outputOrFail(
            OutputConfigResolver.resolve("jvm", null, "lua", null, projectDir, cwdDir),
            "alias-driven default");
        check(aliasedDefault.absoluteNormalizedPath().equals(
                realTmp.resolve("project").resolve("build").resolve("lua").toString()),
            "CLI-alias-driven default still resolves from the manifest directory");
    }

    // =========================================================================
    // Filesystem: non-existent output with an existing prefix
    // =========================================================================

    private static void testNonexistentOutputWithExistingPrefix() throws Exception {
        System.out.println("-- Filesystem: non-existent output classifies with its existing"
            + " prefix resolved --");

        Path proj = Files.createDirectories(tmpDir.resolve("prefix-proj"));
        Path existing = Files.createDirectories(proj.resolve("existing"));

        OutputConfigResolver.OutputRef ref = outputOrFail(OutputConfigResolver.resolve(
            "luajit", ms("existing/out"), null, null, proj, cwdDir),
            "non-existent output under an existing prefix");
        check(ref.absoluteNormalizedPath().equals(
                realTmp.resolve("prefix-proj").resolve("existing").resolve("out").toString()),
            "the existing prefix is resolved and the non-existent suffix appended");
        check(!Files.exists(proj.resolve("existing").resolve("out")),
            "the non-existent output path is not created by the resolver");

        // CLI source with an existing CWD prefix:
        Path cliExisting = Files.createDirectories(cwdDir.resolve("cli-existing"));
        OutputConfigResolver.OutputRef cliRef = outputOrFail(OutputConfigResolver.resolve(
            "luajit", null, null, "cli-existing/out", proj, cwdDir),
            "non-existent CLI output under an existing CWD prefix");
        check(cliRef.absoluteNormalizedPath().equals(
                realTmp.resolve("cwd").resolve("cli-existing").resolve("out").toString()),
            "the CLI output's existing prefix resolves from the process CWD");
        check(!Files.exists(cwdDir.resolve("cli-existing").resolve("out")),
            "the non-existent CLI output path is not created by the resolver");

        // An output path that names an existing directory resolves fully.
        OutputConfigResolver.OutputRef dirRef = outputOrFail(OutputConfigResolver.resolve(
            "luajit", ms("existing"), null, null, proj, cwdDir),
            "output naming an existing directory");
        check(dirRef.absoluteNormalizedPath().equals(
                realTmp.resolve("prefix-proj").resolve("existing").toString()),
            "an existing output directory resolves fully (empty suffix)");
    }

    // =========================================================================
    // Filesystem: symlinked prefixes
    // =========================================================================

    private static void testSymlinkedPrefixResolution() {
        System.out.println("-- Filesystem: symlinked existing prefixes --");
        if (!symlinksSupported()) {
            System.out.println("  (symlinks unsupported on this platform; skipping)");
            return;
        }
        try {
            Path proj = Files.createDirectories(tmpDir.resolve("symlink-proj"));
            Path realBuild = Files.createDirectories(tmpDir.resolve("real-build"));
            Files.createSymbolicLink(proj.resolve("link"), Path.of("../real-build"));

            OutputConfigResolver.OutputRef oneLevel = outputOrFail(OutputConfigResolver.resolve(
                "luajit", ms("link/out"), null, null, proj, cwdDir),
                "output under a symlinked existing prefix");
            check(oneLevel.absoluteNormalizedPath().equals(
                    realTmp.resolve("real-build").resolve("out").toString()),
                "a symlinked existing prefix resolves on the longest existing"
                    + " directory prefix");

            Files.createDirectories(realBuild.resolve("sub"));
            OutputConfigResolver.OutputRef chained = outputOrFail(OutputConfigResolver.resolve(
                "luajit", ms("link/sub/out"), null, null, proj, cwdDir),
                "output through a symlink chain");
            check(chained.absoluteNormalizedPath().equals(
                    realTmp.resolve("real-build").resolve("sub").resolve("out").toString()),
                "a symlink chain resolves through every existing component");

            OutputConfigResolver.OutputRef stopped = outputOrFail(OutputConfigResolver.resolve(
                "luajit", ms("link/missing/out"), null, null, proj, cwdDir),
                "output whose suffix does not exist past the symlink");
            check(stopped.absoluteNormalizedPath().equals(
                    realTmp.resolve("real-build").resolve("missing").resolve("out").toString()),
                "resolution stops at the first non-existent component and appends"
                    + " the suffix lexically");

            Files.createSymbolicLink(proj.resolve("dang"), Path.of("nowhere"));
            OutputConfigResolver.OutputRef dangling = outputOrFail(OutputConfigResolver.resolve(
                "luajit", ms("dang/out"), null, null, proj, cwdDir),
                "output under a dangling-symlink prefix");
            check(dangling.absoluteNormalizedPath().equals(
                    realTmp.resolve("symlink-proj").resolve("dang").resolve("out").toString()),
                "a dangling-symlink prefix does not resolve; the suffix continues"
                    + " lexically");

            Files.write(proj.resolve("afile"), new byte[] {1});
            Files.createSymbolicLink(proj.resolve("flink"), Path.of("afile"));
            OutputConfigResolver.OutputRef fileLink = outputOrFail(OutputConfigResolver.resolve(
                "luajit", ms("flink/out"), null, null, proj, cwdDir),
                "output under a symlink-to-file prefix");
            check(fileLink.absoluteNormalizedPath().equals(
                    realTmp.resolve("symlink-proj").resolve("flink").resolve("out").toString()),
                "a symlink-to-file prefix is not a directory; resolution stops"
                    + " before it");

            Files.createSymbolicLink(cwdDir.resolve("clink"), Path.of("../real-build"));
            OutputConfigResolver.OutputRef cliLinked = outputOrFail(OutputConfigResolver.resolve(
                "luajit", null, null, "clink/out", proj, cwdDir),
                "CLI output under a symlinked CWD prefix");
            check(cliLinked.absoluteNormalizedPath().equals(
                    realTmp.resolve("real-build").resolve("out").toString()),
                "a CLI output resolves its symlinked prefix from the process CWD");
        } catch (IOException e) {
            fail("symlink fixture creation failed: " + e);
        }
    }

    // =========================================================================
    // Filesystem: the module performs no writes and creates no directory
    // =========================================================================

    private static void testNoWritesAndNoCreation() {
        System.out.println("-- Filesystem: no writes, no directory creation --");
        try {
            Path proj = Files.createDirectories(tmpDir.resolve("nowrite-proj"));
            List<String> before = snapshot(proj);
            List<String> cwdBefore = snapshot(cwdDir);

            OutputConfigResolver.resolve("luajit", null, null, null, proj, cwdDir);
            OutputConfigResolver.resolve("luajit", ms("x/y"), null, null, proj, cwdDir);
            OutputConfigResolver.resolve("jvm", null, "lua", "z/w", proj, cwdDir);
            OutputConfigResolver.resolve("luajit", ms("/tmp/definitely/absent"), null, null,
                proj, cwdDir);
            OutputConfigResolver.resolve("luajit", ms("bad\u0000dir"), null, null, proj,
                cwdDir);

            check(snapshot(proj).equals(before),
                "the manifest-directory tree is byte-identical after five resolves"
                    + " (including a failing one)");
            check(snapshot(cwdDir).equals(cwdBefore),
                "the process-CWD tree is byte-identical after the resolves");
            check(!Files.exists(proj.resolve("build")),
                "no build directory was created for the default path");
            check(!Files.exists(proj.resolve("x")),
                "no manifest output directory was created");
            check(!Files.exists(proj.resolve("z")),
                "no CLI output directory was created");
        } catch (IOException e) {
            fail("no-writes fixture failed: " + e);
        }
    }

    // =========================================================================
    // Conversion-failure shapes
    // =========================================================================

    private static void testConversionFailureShapes() {
        System.out.println("-- Conversion failure shape: (source, reason, sourceRange) --");

        SourceScalarRange range = new SourceScalarRange(2, 3, 2, 15, 11, 23);

        OutputConfigResolver.Resolution manifestNul = OutputConfigResolver.resolve(
            "luajit", new ManifestString("bad\u0000dir", range), null, null, projectDir,
            cwdDir);
        OutputConfigResolver.OutputPathFailure f = failureOrFail(manifestNul,
            "NUL-bearing manifest output is a conversion failure");
        check(f.source() == OutputConfigResolver.Source.MANIFEST,
            "manifest failure carries source MANIFEST");
        check(f.reason().contains("NUL"), "manifest failure reason names NUL: " + f.reason());
        check(f.sourceRange().equals(range),
            "manifest failure carries the manifest value range");
        check(manifestNul.effectiveBackend().equals("luajit"),
            "the effective backend is still selected on a failure resolution");

        OutputConfigResolver.Resolution manifestSurrogate = OutputConfigResolver.resolve(
            "luajit", new ManifestString("out\uD83D", range), null, null, projectDir, cwdDir);
        check(failureOrFail(manifestSurrogate, "unpaired-surrogate manifest output fails")
                .reason().contains("surrogate"),
            "manifest unpaired-surrogate failure reason names the surrogate");

        OutputConfigResolver.Resolution cliNul = OutputConfigResolver.resolve(
            "luajit", null, null, "bad\u0000cli", projectDir, cwdDir);
        OutputConfigResolver.OutputPathFailure cf = failureOrFail(cliNul,
            "NUL-bearing CLI output is a conversion failure");
        check(cf.source() == OutputConfigResolver.Source.CLI,
            "CLI failure carries source CLI");
        check(cf.reason().contains("NUL"), "CLI failure reason names NUL: " + cf.reason());
        check(cf.sourceRange() == null,
            "CLI failure carries no source scalar range");

        OutputConfigResolver.Resolution cliSurrogate = OutputConfigResolver.resolve(
            "luajit", null, null, "\uDC00", projectDir, cwdDir);
        check(failureOrFail(cliSurrogate, "unpaired-surrogate CLI output fails")
                .reason().contains("surrogate"),
            "CLI unpaired-surrogate failure reason names the surrogate");

        OutputConfigResolver.Resolution cliAbsNul = OutputConfigResolver.resolve(
            "luajit", null, null, "/abs\u0000out", projectDir, cwdDir);
        check(failureOrFail(cliAbsNul, "NUL-bearing CLI absolute output fails")
                .source() == OutputConfigResolver.Source.CLI,
            "a NUL-bearing CLI absolute value is still a CLI-source failure");

        // Precedence applies before conversion: a failing CLI override wins
        // over a valid manifest output and the failure stays CLI-source.
        OutputConfigResolver.Resolution precedence = OutputConfigResolver.resolve(
            "luajit", ms("valid-out"), null, "bad\u0000cli", projectDir, cwdDir);
        check(failureOrFail(precedence, "failing CLI override wins over a valid manifest output")
                .source() == OutputConfigResolver.Source.CLI,
            "the precedence winner's source decides the failure mapping (T4 duty)");
    }

    // =========================================================================
    // Purity and determinism
    // =========================================================================

    private static void testPurityAndDeterminism() {
        System.out.println("-- Purity: identical inputs -> identical results, no state --");

        OutputConfigResolver.Resolution a = OutputConfigResolver.resolve(
            "luajit", ms("out/x"), null, null, projectDir, cwdDir);
        OutputConfigResolver.Resolution b = OutputConfigResolver.resolve(
            "luajit", ms("out/x"), null, null, projectDir, cwdDir);
        check(a.equals(b), "identical inputs produce equal resolutions");
        check(a.output().absoluteNormalizedPath().equals(b.output().absoluteNormalizedPath()),
            "identical inputs produce identical converted paths");

        OutputConfigResolver.Resolution fa = OutputConfigResolver.resolve(
            "luajit", ms("bad\u0000"), null, null, projectDir, cwdDir);
        OutputConfigResolver.Resolution fb = OutputConfigResolver.resolve(
            "luajit", ms("bad\u0000"), null, null, projectDir, cwdDir);
        check(fa.equals(fb), "identical failing inputs produce equal failures");

        OutputConfigResolver.Resolution ca = OutputConfigResolver.resolve(
            "jvm", null, "lua", "cli/x", projectDir, cwdDir);
        OutputConfigResolver.Resolution cb = OutputConfigResolver.resolve(
            "jvm", null, "lua", "cli/x", projectDir, cwdDir);
        check(ca.equals(cb), "identical mixed inputs produce equal resolutions");
    }

    // =========================================================================
    // Precondition errors (validated-only surface)
    // =========================================================================

    private static void testPreconditionErrors() {
        System.out.println("-- Precondition errors (present-but-invalid values are"
            + " programming errors) --");

        try {
            OutputConfigResolver.resolve("jvm", null, "wasm", null, projectDir, cwdDir);
            fail("invalid CLI backend alias should throw");
        } catch (IllegalArgumentException expected) {
            passed++;
        }
        try {
            OutputConfigResolver.resolve("wasm", null, null, null, projectDir, cwdDir);
            fail("invalid manifest backend should throw");
        } catch (IllegalArgumentException expected) {
            passed++;
        }
        try {
            OutputConfigResolver.resolve("luajit", null, null, null, null, cwdDir);
            fail("null manifestDirectory should throw");
        } catch (NullPointerException expected) {
            passed++;
        }
        try {
            OutputConfigResolver.resolve("luajit", null, null, null, projectDir, null);
            fail("null processCwd should throw");
        } catch (NullPointerException expected) {
            passed++;
        }
    }

    // =========================================================================
    // Combined T1 + T2 + T3 gate
    // =========================================================================

    private static void testCombinedT1T2() {
        System.out.println("-- Combined T1+T2+T3: parse a valid manifest through T2,"
            + " resolve its validated values through T3 --");
        try {
            Path proj = Files.createDirectories(tmpDir.resolve("combined-proj"));
            Files.createDirectories(proj.resolve("out"));
            Path manifestFile = proj.resolve("deal.json");
            String json = "{\"languageVersion\":\"1.2\",\"backend\":\"jvm\","
                + "\"output\":\"out/dir\"}";
            Files.write(manifestFile, json.getBytes(StandardCharsets.UTF_8));

            StrictManifestParser.StrictManifestParseResult parsed =
                StrictManifestParser.parse(manifestFile.toString(), json);
            check(parsed.failure() == null,
                "T2 parses the valid manifest without a diagnostic");
            ProjectManifest manifest = parsed.manifest();
            check(manifest != null && manifest.backend().equals("jvm")
                    && manifest.output() != null
                    && manifest.output().value().equals("out/dir"),
                "T2 publishes the validated backend/output values");

            OutputConfigResolver.Resolution r = OutputConfigResolver.resolve(
                manifest.backend(), manifest.output(), null, null, proj, cwdDir);
            check(r.failure() == null, "T3 resolves the T2 values without a failure");
            check(r.effectiveBackend().equals("jvm"),
                "effective backend = the validated manifest backend");
            OutputConfigResolver.OutputRef ref = outputOrFail(r, "T3 classifies the output");
            check(ref.source() == OutputConfigResolver.Source.MANIFEST
                    && ref.kind() == OutputConfigResolver.Kind.MANIFEST_RELATIVE_PATH
                    && ref.decodedText().equals("out/dir"),
                "T3 classification: MANIFEST source, relative kind, decoded text");
            check(ref.sourceRange().equals(manifest.output().sourceRange()),
                "the manifest value range round-trips T2 -> T3");
            check(ref.absoluteNormalizedPath().equals(
                    realTmp.resolve("combined-proj").resolve("out").resolve("dir").toString()),
                "T1 prefix resolution: existing 'out' prefix resolved, 'dir' appended");

            // Validated CLI alias + CLI output over the parsed manifest values.
            Files.createDirectories(cwdDir.resolve("cli"));
            OutputConfigResolver.Resolution r2 = OutputConfigResolver.resolve(
                manifest.backend(), manifest.output(), "lua", "cli/out", proj, cwdDir);
            check(r2.effectiveBackend().equals("luajit"),
                "validated CLI alias 'lua' overrides the parsed manifest backend");
            check(r2.output().source() == OutputConfigResolver.Source.CLI
                    && r2.output().decodedText().equals("cli/out"),
                "validated CLI output wins over the parsed manifest output");
            check(r2.output().absoluteNormalizedPath().equals(
                    realTmp.resolve("cwd").resolve("cli").resolve("out").toString()),
                "T1 prefix-resolves the CLI output from the process CWD");

            // Minimal manifest (absent backend/output) -> pinned defaults.
            StrictManifestParser.StrictManifestParseResult minimal =
                StrictManifestParser.parse(manifestFile.toString(),
                    "{\"languageVersion\":\"1.2\"}");
            check(minimal.failure() == null, "T2 parses the minimal manifest");
            ProjectManifest minimalManifest = minimal.manifest();
            OutputConfigResolver.Resolution r3 = OutputConfigResolver.resolve(
                minimalManifest.backend(), minimalManifest.output(), null, null, proj,
                cwdDir);
            check(r3.effectiveBackend().equals("luajit"),
                "absent backend defaults to luajit through the validated values");
            check(r3.output().decodedText().equals("build/lua")
                    && r3.output().source() == OutputConfigResolver.Source.MANIFEST,
                "absent output defaults to build/lua");
            check(r3.output().absoluteNormalizedPath().equals(
                    realTmp.resolve("combined-proj").resolve("build").resolve("lua").toString()),
                "the default resolves from the manifest directory via T1");
        } catch (IOException e) {
            fail("combined fixture failed: " + e);
        }
    }
}
