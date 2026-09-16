package deal.test;

import deal.Main;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ir.InvocationPurpose;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticProfile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class InvocationProfileRegistryTest {

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

    static void testInvocationValidation() {
        System.out.println("-- CompilerInvocation validation --");

        CapabilityRegistry registry = CapabilityRegistry.releaseRegistry();
        String registryHash = registry.capabilityRegistryHash();
        String releaseStateHash = CompilerProfileProvider.deriveReleaseStateHash(
            ReleaseState.PRE_ACTIVATION, registryHash);

        checkConstructorRejectsNull(null, SemanticProfile.LEGACY_SAFE_INT,
            ReleaseState.PRE_ACTIVATION, registryHash, releaseStateHash);
        checkConstructorRejectsNull(InvocationPurpose.PUBLIC_BUILD, null,
            ReleaseState.PRE_ACTIVATION, registryHash, releaseStateHash);
        checkConstructorRejectsNull(InvocationPurpose.PUBLIC_BUILD,
            SemanticProfile.LEGACY_SAFE_INT, null, registryHash, releaseStateHash);
        checkConstructorRejectsNull(InvocationPurpose.PUBLIC_BUILD,
            SemanticProfile.LEGACY_SAFE_INT, ReleaseState.PRE_ACTIVATION, null,
            releaseStateHash);
        checkConstructorRejectsNull(InvocationPurpose.PUBLIC_BUILD,
            SemanticProfile.LEGACY_SAFE_INT, ReleaseState.PRE_ACTIVATION, registryHash,
            null);
    }

    private static void checkConstructorRejectsNull(InvocationPurpose purpose,
                                                     SemanticProfile profile,
                                                     ReleaseState state,
                                                     String registryHash,
                                                     String releaseStateHash) {
        try {
            new CompilerInvocation(purpose, profile, state, registryHash, releaseStateHash);
            fail("CompilerInvocation must reject a null component");
        } catch (NullPointerException expected) {
            check(true, "CompilerInvocation rejects a null component");
        }
    }

    static void testResolutionMatrix() {
        System.out.println("-- Purpose, profile, and release-state resolution --");

        CapabilityRegistry registry = CapabilityRegistry.releaseRegistry();
        String registryHash = registry.capabilityRegistryHash();
        String preHash = CompilerProfileProvider.deriveReleaseStateHash(
            ReleaseState.PRE_ACTIVATION, registryHash);
        String activeHash = CompilerProfileProvider.deriveReleaseStateHash(
            ReleaseState.V1_2_ACTIVE, registryHash);

        check(CompilerProfileProvider.publicProfile(ReleaseState.PRE_ACTIVATION)
                == SemanticProfile.LEGACY_SAFE_INT,
            "PRE_ACTIVATION derives LEGACY_SAFE_INT");
        check(CompilerProfileProvider.publicProfile(ReleaseState.V1_2_ACTIVE)
                == SemanticProfile.DEAL_V1_2_INT32,
            "V1_2_ACTIVE derives DEAL_V1_2_INT32");

        CompilerInvocation publicPre = CompilerProfileProvider.resolve(
            ReleaseState.PRE_ACTIVATION, registry);
        checkInvocation(publicPre, InvocationPurpose.PUBLIC_BUILD,
            SemanticProfile.LEGACY_SAFE_INT, ReleaseState.PRE_ACTIVATION,
            registryHash, preHash);

        CompilerInvocation publicActive = CompilerProfileProvider.resolve(
            ReleaseState.V1_2_ACTIVE, registry);
        checkInvocation(publicActive, InvocationPurpose.PUBLIC_BUILD,
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE,
            registryHash, activeHash);

        CompilerInvocation shadowPre = CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.PRE_ACTIVATION, registry);
        checkInvocation(shadowPre, InvocationPurpose.COMMON_SHADOW,
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.PRE_ACTIVATION,
            registryHash, preHash);

        CompilerInvocation shadowActive = CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE, registry);
        checkInvocation(shadowActive, InvocationPurpose.COMMON_SHADOW,
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE,
            registryHash, activeHash);

        checkResolutionRejected(
            () -> CompilerProfileProvider.resolveCommonShadow(
                SemanticProfile.LEGACY_SAFE_INT, ReleaseState.PRE_ACTIVATION, registry),
            "COMMON_SHADOW requires DEAL_V1_2_INT32");
        checkResolutionRejected(
            () -> CompilerProfileProvider.resolveCommonShadow(
                SemanticProfile.LEGACY_SAFE_INT, ReleaseState.V1_2_ACTIVE, registry),
            "COMMON_SHADOW requires DEAL_V1_2_INT32");

        CompilerInvocation legacyPre = CompilerProfileProvider.resolveLegacyRegression(
            SemanticProfile.LEGACY_SAFE_INT, ReleaseState.PRE_ACTIVATION, registry);
        checkInvocation(legacyPre, InvocationPurpose.LEGACY_REGRESSION,
            SemanticProfile.LEGACY_SAFE_INT, ReleaseState.PRE_ACTIVATION,
            registryHash, preHash);

        CompilerInvocation legacyActive = CompilerProfileProvider.resolveLegacyRegression(
            SemanticProfile.LEGACY_SAFE_INT, ReleaseState.V1_2_ACTIVE, registry);
        checkInvocation(legacyActive, InvocationPurpose.LEGACY_REGRESSION,
            SemanticProfile.LEGACY_SAFE_INT, ReleaseState.V1_2_ACTIVE,
            registryHash, activeHash);

        checkResolutionRejected(
            () -> CompilerProfileProvider.resolveLegacyRegression(
                SemanticProfile.DEAL_V1_2_INT32, ReleaseState.PRE_ACTIVATION, registry),
            "LEGACY_REGRESSION requires LEGACY_SAFE_INT");
        checkResolutionRejected(
            () -> CompilerProfileProvider.resolveLegacyRegression(
                SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE, registry),
            "LEGACY_REGRESSION requires LEGACY_SAFE_INT");

        checkResolutionRejected(
            () -> new CompilerInvocation(InvocationPurpose.COMMON_SHADOW,
                SemanticProfile.LEGACY_SAFE_INT, ReleaseState.PRE_ACTIVATION,
                registryHash, preHash),
            "COMMON_SHADOW requires DEAL_V1_2_INT32");
        checkResolutionRejected(
            () -> new CompilerInvocation(InvocationPurpose.LEGACY_REGRESSION,
                SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE,
                registryHash, activeHash),
            "LEGACY_REGRESSION requires LEGACY_SAFE_INT");
        checkResolutionRejected(
            () -> new CompilerInvocation(InvocationPurpose.PUBLIC_BUILD,
                SemanticProfile.DEAL_V1_2_INT32, ReleaseState.PRE_ACTIVATION,
                registryHash, preHash),
            "PUBLIC_BUILD");
        checkResolutionRejected(
            () -> new CompilerInvocation(InvocationPurpose.PUBLIC_BUILD,
                SemanticProfile.LEGACY_SAFE_INT, ReleaseState.PRE_ACTIVATION,
                registryHash,
                "0000000000000000000000000000000000000000000000000000000000000000"),
            "releaseStateHash");

        CompilerInvocation repeated = CompilerProfileProvider.resolve(
            ReleaseState.PRE_ACTIVATION, registry);
        check(repeated.equals(publicPre), "identical resolution inputs produce equal invocations");
    }

    private static void checkInvocation(CompilerInvocation invocation,
                                        InvocationPurpose purpose,
                                        SemanticProfile profile,
                                        ReleaseState state,
                                        String registryHash,
                                        String releaseStateHash) {
        check(invocation.purpose() == purpose
                && invocation.semanticProfile() == profile
                && invocation.releaseState() == state
                && invocation.capabilityRegistryHash().equals(registryHash)
                && invocation.releaseStateHash().equals(releaseStateHash),
            purpose + " resolves " + profile + " under " + state);
    }

    private static void checkResolutionRejected(Runnable resolution,
                                                String expectedMessage) {
        try {
            resolution.run();
            fail("invalid invocation/profile resolution must be rejected");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(expectedMessage),
                "invalid resolution reports " + expectedMessage);
        }
    }

    static void testCliBehavior() throws Exception {
        System.out.println("-- CLI behavior --");

        Path dir = Files.createTempDirectory("deal-invocation-cli");
        Path src = dir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("cli.deal"),
            "export function main(): null { return null; }\n");
        Files.writeString(dir.resolve("deal.json"),
            "{\"languageVersion\": \"1.2\", \"moduleRoots\": [\"src\"]}\n");
        Path entry = src.resolve("cli.deal").toAbsolutePath();
        Path out = dir.resolve("build/cli");

        String[] profile = runCliCapturingErr(new String[]{"compile", entry.toString(),
            "--output", out.toString(), "--profile", "DEAL_V1_2_INT32"});
        check("1".equals(profile[0]), "--profile exits 1");
        check(profile[1].contains("unknown option '--profile'"),
            "--profile is rejected as an unknown option");

        String[] purpose = runCliCapturingErr(new String[]{"compile", entry.toString(),
            "--output", out.toString(), "--purpose", "COMMON_SHADOW"});
        check("1".equals(purpose[0]), "--purpose exits 1");
        check(purpose[1].contains("unknown option '--purpose'"),
            "--purpose is rejected as an unknown option");

        check("1".equals(runCliCapturingErr(new String[]{})[0]),
            "no arguments exits 1");
        check("1".equals(runCliCapturingErr(new String[]{"unknown"})[0]),
            "unknown command exits 1");
        check("1".equals(runCliCapturingErr(new String[]{"compile"})[0]),
            "missing entry exits 1");

        int valid = Main.run(new String[]{"compile", entry.toString(),
            "--output", out.toString()});
        check(valid == 0 && Files.exists(out.resolve("cli.lua")),
            "valid compile exits 0 and emits the artifact");

        Path verboseOut = dir.resolve("build/verbose");
        int verbose = Main.run(new String[]{"compile", entry.toString(),
            "--output", verboseOut.toString(), "--verbose"});
        check(verbose == 0 && Files.exists(verboseOut.resolve("cli.lua")),
            "verbose compile exits 0 and emits the artifact");
    }

    private static String[] runCliCapturingErr(String[] args) throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exitCode = Main.run(args);
            System.err.flush();
        } finally {
            System.setErr(originalErr);
        }
        return new String[]{String.valueOf(exitCode),
            err.toString(StandardCharsets.UTF_8)};
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Invocation / Profile / CLI Test ===\n");

        testInvocationValidation();
        testResolutionMatrix();
        testCliBehavior();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
