package deal.test;

import deal.Main;
import deal.codegen.Backend;
import deal.module.CompilationOrchestrator;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.Target;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.InvocationPurpose;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticProfile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies the ISSUE-0284 foundation surface: the release-owned
 * {@link CompilerInvocation} record, {@link CompilerProfileProvider}
 * resolution (including the derived, recorded {@code releaseStateHash}),
 * the closed {@link CapabilityRegistry} with its exact canonical JSON
 * digest, the unchanged public CLI surface, and the combined T1/T3
 * coupling (the closed enums of ISSUE-0281 and the single canonical JSON
 * facility of ISSUE-0283).
 *
 * <p>Tests:
 * <ol>
 *   <li>The invocation record: exactly the five pinned components in the
 *       pinned order with the pinned types, immutable, and the derived
 *       release-state hash enforced at construction (never selectable).</li>
 *   <li>The full A1 purpose × profile × release-state matrix:
 *       PUBLIC_BUILD derives the profile from the release state through
 *       the derivation-only {@code resolve(releaseState, registry)}
 *       entry; COMMON_SHADOW takes the profile explicitly and requires
 *       DEAL_V1_2_INT32 under any release state; LEGACY_REGRESSION takes
 *       the profile explicitly and requires LEGACY_SAFE_INT under any
 *       release state. Every wrong-profile combination is rejected at
 *       invocation resolution and at the record guard, before checking
 *       or lowering.</li>
 *   <li>The release-state hash equals the SHA-256 of the pinned canonical
 *       JSON golden and is asserted on the invocation record for verbose
 *       and non-verbose paths alike.</li>
 *   <li>The registry: 24 entries, all SHADOW, in the S4 capability order
 *       with LUAJIT before JVM; the digest equals a stored golden
 *       recomputed in a fresh JVM invocation byte-identically; no
 *       consumer axis and no promotion transition logic.</li>
 *   <li>The CLI: inventing {@code --profile}/{@code --purpose} fails with
 *       "unknown option"; existing CLI behaviors are unchanged;
 *       {@code deal/Main.java} constructs PUBLIC_BUILD through the
 *       provider and gains no option string.</li>
 *   <li>Combined T1/T3: registry serialization and the release-state-hash
 *       computation flow exclusively through T3's single canonicalizer
 *       (pinned sorted-key/decimal-int goldens, parser round-trip, no
 *       second serializer in the invocation/registry code) and T1's
 *       closed enums are the only profile/purpose/capability values the
 *       records admit (compile-time closedness).</li>
 * </ol>
 */
public class InvocationProfileRegistryTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // Stored goldens (pinned; the digest goldens were recomputed independently
    // with the canonical JSON rules — sorted keys, decimal integers — from the
    // exact pinned entry order, and the Java implementation must reproduce
    // them byte-identically)
    // =========================================================================

    /** Stored golden: the exact canonical JSON text of the release registry. */
    private static final String PINNED_REGISTRY_JSON =
            "{\"entries\":[{\"capability\":\"FOUNDATION_VALUES\",\"state\":\"SHADOW\",\"target\":\"LUAJIT\"}," +
            "{\"capability\":\"FOUNDATION_VALUES\",\"state\":\"SHADOW\",\"target\":\"JVM\"},{\"capability\":" +
            "\"SIGNED_INT32\",\"state\":\"SHADOW\",\"target\":\"LUAJIT\"},{\"capability\":\"SIGNED_INT32\",\"" +
            "state\":\"SHADOW\",\"target\":\"JVM\"},{\"capability\":\"CONTAINERS_AND_STRINGS\",\"state\":\"SH" +
            "ADOW\",\"target\":\"LUAJIT\"},{\"capability\":\"CONTAINERS_AND_STRINGS\",\"state\":\"SHADOW\",\"" +
            "target\":\"JVM\"},{\"capability\":\"DESCRIPTORS\",\"state\":\"SHADOW\",\"target\":\"LUAJIT\"},{" +
            "\"capability\":\"DESCRIPTORS\",\"state\":\"SHADOW\",\"target\":\"JVM\"},{\"capability\":\"BOUNDA" +
            "RIES\",\"state\":\"SHADOW\",\"target\":\"LUAJIT\"},{\"capability\":\"BOUNDARIES\",\"state\":\"SH" +
            "ADOW\",\"target\":\"JVM\"},{\"capability\":\"EVALUATION_ORDER\",\"state\":\"SHADOW\",\"target\":" +
            "\"LUAJIT\"},{\"capability\":\"EVALUATION_ORDER\",\"state\":\"SHADOW\",\"target\":\"JVM\"},{\"cap" +
            "ability\":\"BINDINGS\",\"state\":\"SHADOW\",\"target\":\"LUAJIT\"},{\"capability\":\"BINDINGS\"," +
            "\"state\":\"SHADOW\",\"target\":\"JVM\"},{\"capability\":\"CALLS\",\"state\":\"SHADOW\",\"target" +
            "\":\"LUAJIT\"},{\"capability\":\"CALLS\",\"state\":\"SHADOW\",\"target\":\"JVM\"},{\"capability" +
            "\":\"STDLIB_SEMANTICS\",\"state\":\"SHADOW\",\"target\":\"LUAJIT\"},{\"capability\":\"STDLIB_SEM" +
            "ANTICS\",\"state\":\"SHADOW\",\"target\":\"JVM\"},{\"capability\":\"STDLIB_TIME_CONFLICT\",\"sta" +
            "te\":\"SHADOW\",\"target\":\"LUAJIT\"},{\"capability\":\"STDLIB_TIME_CONFLICT\",\"state\":\"SHAD" +
            "OW\",\"target\":\"JVM\"},{\"capability\":\"CLASSES\",\"state\":\"SHADOW\",\"target\":\"LUAJIT\"}" +
            ",{\"capability\":\"CLASSES\",\"state\":\"SHADOW\",\"target\":\"JVM\"},{\"capability\":\"MODULES" +
            "\",\"state\":\"SHADOW\",\"target\":\"LUAJIT\"},{\"capability\":\"MODULES\",\"state\":\"SHADOW\"," +
            "\"target\":\"JVM\"}],\"version\":1}";



    /** Stored golden: SHA-256 of {@link #PINNED_REGISTRY_JSON}. */
    private static final String PINNED_REGISTRY_HASH =
        "dd6c2dcbf562ba1cc5af2b64d063f7464f6145daa0d87f52c667ef5580650433";

    /** Stored golden: releaseStateHash for PRE_ACTIVATION over the registry hash. */
    private static final String PINNED_RELEASE_STATE_HASH_PRE =
        "e0169205afb7d0b0e9133d9a9a3823a9c8c3a02c7b296a909b59147ae107b276";

    /** Stored golden: releaseStateHash for V1_2_ACTIVE over the registry hash. */
    private static final String PINNED_RELEASE_STATE_HASH_ACTIVE =
        "dc4c3fe0ae1356816a9454644af201fbf8289c03de1603a2c1e671f99441aa36";

    private static final List<String> CAPABILITY_ORDER = List.of(
        "FOUNDATION_VALUES", "SIGNED_INT32", "CONTAINERS_AND_STRINGS", "DESCRIPTORS",
        "BOUNDARIES", "EVALUATION_ORDER", "BINDINGS", "CALLS", "STDLIB_SEMANTICS",
        "STDLIB_TIME_CONFLICT", "CLASSES", "MODULES");

    // =========================================================================
    // 1. CompilerInvocation record shape and derived-field enforcement
    // =========================================================================

    static void testInvocationRecordShape() {
        System.out.println("-- CompilerInvocation record shape --");

        Class<CompilerInvocation> type = CompilerInvocation.class;
        check(type.isRecord(), "CompilerInvocation is an immutable record");

        RecordComponent[] components = type.getRecordComponents();
        String[] pinnedNames = {"purpose", "semanticProfile", "releaseState",
            "capabilityRegistryHash", "releaseStateHash"};
        Class<?>[] pinnedTypes = {InvocationPurpose.class, SemanticProfile.class,
            ReleaseState.class, String.class, String.class};
        check(components.length == 5,
            "CompilerInvocation has exactly 5 components; got "
                + components.length + " " + Arrays.toString(components));
        for (int i = 0; i < Math.min(components.length, pinnedNames.length); i++) {
            check(pinnedNames[i].equals(components[i].getName()),
                "component " + i + " is named " + pinnedNames[i]
                    + "; got " + components[i].getName());
            check(components[i].getType() == pinnedTypes[i],
                "component " + i + " has type " + pinnedTypes[i].getSimpleName()
                    + "; got " + components[i].getType().getSimpleName());
        }

        Field[] fields = type.getDeclaredFields();
        check(fields.length == 5,
            "CompilerInvocation declares exactly 5 fields (no extras); got "
                + fields.length);
        for (Field f : fields) {
            check(Modifier.isPrivate(f.getModifiers()) && Modifier.isFinal(f.getModifiers()),
                "field " + f.getName() + " is private final (immutable record)");
        }

        // One profile per invocation by construction: the profile component
        // is a single closed enum value — no list, map, or set component
        // can ever carry mixed profiles.
        check(components[1].getType() == SemanticProfile.class
                && components[1].getType().isEnum(),
            "semanticProfile is a single closed enum component (profile mixing impossible)");

        // Non-null enforcement on every component.
        String hash = CapabilityRegistry.releaseRegistry().capabilityRegistryHash();
        String derived = CompilerProfileProvider.deriveReleaseStateHash(
            ReleaseState.PRE_ACTIVATION, hash);
        checkConstructorRejectsNull(null, SemanticProfile.LEGACY_SAFE_INT,
            ReleaseState.PRE_ACTIVATION, hash, derived);
        checkConstructorRejectsNull(InvocationPurpose.PUBLIC_BUILD, null,
            ReleaseState.PRE_ACTIVATION, hash, derived);
        checkConstructorRejectsNull(InvocationPurpose.PUBLIC_BUILD,
            SemanticProfile.LEGACY_SAFE_INT, null, hash, derived);
        checkConstructorRejectsNull(InvocationPurpose.PUBLIC_BUILD,
            SemanticProfile.LEGACY_SAFE_INT, ReleaseState.PRE_ACTIVATION, null, derived);
        checkConstructorRejectsNull(InvocationPurpose.PUBLIC_BUILD,
            SemanticProfile.LEGACY_SAFE_INT, ReleaseState.PRE_ACTIVATION, hash, null);
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

    // =========================================================================
    // 2. Full release-state × purpose resolution matrix
    // =========================================================================

    static void testResolutionMatrix() {
        System.out.println("-- A1 purpose × profile × release-state matrix --");

        CapabilityRegistry registry = CapabilityRegistry.releaseRegistry();
        String registryHash = registry.capabilityRegistryHash();

        // Derivation rule (F1): PRE_ACTIVATION → LEGACY_SAFE_INT,
        // V1_2_ACTIVE → DEAL_V1_2_INT32.
        check(CompilerProfileProvider.publicProfile(ReleaseState.PRE_ACTIVATION)
                == SemanticProfile.LEGACY_SAFE_INT,
            "PRE_ACTIVATION derives LEGACY_SAFE_INT");
        check(CompilerProfileProvider.publicProfile(ReleaseState.V1_2_ACTIVE)
                == SemanticProfile.DEAL_V1_2_INT32,
            "V1_2_ACTIVE derives DEAL_V1_2_INT32");

        // PUBLIC_BUILD × both release states: strictly derived through the
        // derivation-only release-state entry — the only PUBLIC_BUILD
        // constructor.
        CompilerInvocation publicPre = CompilerProfileProvider.resolve(
            ReleaseState.PRE_ACTIVATION, registry);
        check(publicPre.purpose() == InvocationPurpose.PUBLIC_BUILD
                && publicPre.semanticProfile() == SemanticProfile.LEGACY_SAFE_INT
                && publicPre.releaseState() == ReleaseState.PRE_ACTIVATION
                && registryHash.equals(publicPre.capabilityRegistryHash())
                && PINNED_RELEASE_STATE_HASH_PRE.equals(publicPre.releaseStateHash()),
            "PUBLIC_BUILD + PRE_ACTIVATION resolves LEGACY_SAFE_INT with the recorded hash");

        CompilerInvocation publicActive = CompilerProfileProvider.resolve(
            ReleaseState.V1_2_ACTIVE, registry);
        check(publicActive.purpose() == InvocationPurpose.PUBLIC_BUILD
                && publicActive.semanticProfile() == SemanticProfile.DEAL_V1_2_INT32
                && publicActive.releaseState() == ReleaseState.V1_2_ACTIVE
                && registryHash.equals(publicActive.capabilityRegistryHash())
                && PINNED_RELEASE_STATE_HASH_ACTIVE.equals(publicActive.releaseStateHash()),
            "PUBLIC_BUILD + V1_2_ACTIVE resolves DEAL_V1_2_INT32 with the recorded hash");

        // COMMON_SHADOW + DEAL_V1_2_INT32: admitted under both release
        // states (A1 — pre- and post-activation shadowing; the E11
        // pre-activation common-closure evidence path).
        CompilerInvocation shadowPre = CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.PRE_ACTIVATION, registry);
        check(shadowPre.purpose() == InvocationPurpose.COMMON_SHADOW
                && shadowPre.semanticProfile() == SemanticProfile.DEAL_V1_2_INT32
                && shadowPre.releaseState() == ReleaseState.PRE_ACTIVATION
                && registryHash.equals(shadowPre.capabilityRegistryHash())
                && PINNED_RELEASE_STATE_HASH_PRE.equals(shadowPre.releaseStateHash()),
            "COMMON_SHADOW + DEAL_V1_2_INT32 resolves under PRE_ACTIVATION");

        CompilerInvocation shadowActive = CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE, registry);
        check(shadowActive.purpose() == InvocationPurpose.COMMON_SHADOW
                && shadowActive.semanticProfile() == SemanticProfile.DEAL_V1_2_INT32
                && shadowActive.releaseState() == ReleaseState.V1_2_ACTIVE
                && registryHash.equals(shadowActive.capabilityRegistryHash())
                && PINNED_RELEASE_STATE_HASH_ACTIVE.equals(shadowActive.releaseStateHash()),
            "COMMON_SHADOW + DEAL_V1_2_INT32 resolves under V1_2_ACTIVE");

        // COMMON_SHADOW + LEGACY_SAFE_INT: rejected under both release
        // states — a plain resolution rejection, not a diagnostic.
        try {
            CompilerProfileProvider.resolveCommonShadow(SemanticProfile.LEGACY_SAFE_INT,
                ReleaseState.PRE_ACTIVATION, registry);
            fail("COMMON_SHADOW + LEGACY_SAFE_INT (PRE_ACTIVATION) must be rejected");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("COMMON_SHADOW requires DEAL_V1_2_INT32"),
                "COMMON_SHADOW + LEGACY_SAFE_INT (PRE_ACTIVATION) rejected before "
                    + "checking/lowering: " + expected.getMessage());
        }
        try {
            CompilerProfileProvider.resolveCommonShadow(SemanticProfile.LEGACY_SAFE_INT,
                ReleaseState.V1_2_ACTIVE, registry);
            fail("COMMON_SHADOW + LEGACY_SAFE_INT (V1_2_ACTIVE) must be rejected");
        } catch (IllegalArgumentException expected) {
            check(true, "COMMON_SHADOW + LEGACY_SAFE_INT (V1_2_ACTIVE) rejected before "
                + "checking/lowering");
        }

        // LEGACY_REGRESSION + LEGACY_SAFE_INT: admitted under both release
        // states (A1 — the retention window extends past activation).
        CompilerInvocation legacyPre = CompilerProfileProvider.resolveLegacyRegression(
            SemanticProfile.LEGACY_SAFE_INT, ReleaseState.PRE_ACTIVATION, registry);
        check(legacyPre.purpose() == InvocationPurpose.LEGACY_REGRESSION
                && legacyPre.semanticProfile() == SemanticProfile.LEGACY_SAFE_INT
                && legacyPre.releaseState() == ReleaseState.PRE_ACTIVATION
                && registryHash.equals(legacyPre.capabilityRegistryHash())
                && PINNED_RELEASE_STATE_HASH_PRE.equals(legacyPre.releaseStateHash()),
            "LEGACY_REGRESSION + LEGACY_SAFE_INT resolves under PRE_ACTIVATION");

        CompilerInvocation legacyActive =
            CompilerProfileProvider.resolveLegacyRegression(
                SemanticProfile.LEGACY_SAFE_INT, ReleaseState.V1_2_ACTIVE, registry);
        check(legacyActive.purpose() == InvocationPurpose.LEGACY_REGRESSION
                && legacyActive.semanticProfile() == SemanticProfile.LEGACY_SAFE_INT
                && legacyActive.releaseState() == ReleaseState.V1_2_ACTIVE
                && registryHash.equals(legacyActive.capabilityRegistryHash())
                && PINNED_RELEASE_STATE_HASH_ACTIVE.equals(legacyActive.releaseStateHash()),
            "LEGACY_REGRESSION + LEGACY_SAFE_INT resolves under V1_2_ACTIVE");

        // LEGACY_REGRESSION + DEAL_V1_2_INT32: rejected under both release
        // states.
        try {
            CompilerProfileProvider.resolveLegacyRegression(
                SemanticProfile.DEAL_V1_2_INT32, ReleaseState.PRE_ACTIVATION, registry);
            fail("LEGACY_REGRESSION + DEAL_V1_2_INT32 (PRE_ACTIVATION) must be rejected");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("LEGACY_REGRESSION requires LEGACY_SAFE_INT"),
                "LEGACY_REGRESSION + DEAL_V1_2_INT32 (PRE_ACTIVATION) rejected before "
                    + "checking/lowering: " + expected.getMessage());
        }
        try {
            CompilerProfileProvider.resolveLegacyRegression(
                SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE, registry);
            fail("LEGACY_REGRESSION + DEAL_V1_2_INT32 (V1_2_ACTIVE) must be rejected");
        } catch (IllegalArgumentException expected) {
            check(true, "LEGACY_REGRESSION + DEAL_V1_2_INT32 (V1_2_ACTIVE) rejected before "
                + "checking/lowering");
        }

        // The record guard: the A1 matrix holds at the record, not only at
        // the factories.
        try {
            new CompilerInvocation(InvocationPurpose.COMMON_SHADOW,
                SemanticProfile.LEGACY_SAFE_INT, ReleaseState.PRE_ACTIVATION,
                registryHash, PINNED_RELEASE_STATE_HASH_PRE);
            fail("the record must reject COMMON_SHADOW + LEGACY_SAFE_INT");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("COMMON_SHADOW requires DEAL_V1_2_INT32"),
                "CompilerInvocation rejects COMMON_SHADOW + LEGACY_SAFE_INT at "
                    + "construction: " + expected.getMessage());
        }
        try {
            new CompilerInvocation(InvocationPurpose.LEGACY_REGRESSION,
                SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE,
                registryHash, PINNED_RELEASE_STATE_HASH_ACTIVE);
            fail("the record must reject LEGACY_REGRESSION + DEAL_V1_2_INT32");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("LEGACY_REGRESSION requires LEGACY_SAFE_INT"),
                "CompilerInvocation rejects LEGACY_REGRESSION + DEAL_V1_2_INT32 at "
                    + "construction: " + expected.getMessage());
        }
        try {
            new CompilerInvocation(InvocationPurpose.PUBLIC_BUILD,
                SemanticProfile.DEAL_V1_2_INT32, ReleaseState.PRE_ACTIVATION,
                registryHash, PINNED_RELEASE_STATE_HASH_PRE);
            fail("the record must reject a non-derived PUBLIC_BUILD profile");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("PUBLIC_BUILD"),
                "CompilerInvocation rejects a PUBLIC_BUILD profile that is not the "
                    + "release-state derivation");
        }

        // Determinism: identical inputs produce identical records.
        CompilerInvocation again = CompilerProfileProvider.resolve(
            ReleaseState.PRE_ACTIVATION, registry);
        check(again.equals(publicPre) && again.releaseStateHash().equals(publicPre.releaseStateHash()),
            "repeated resolution is byte-identical (one immutable profile per invocation)");

        // The derived hash is never selectable: the record rejects a recorded
        // value that differs from the recomputed derivation.
        try {
            new CompilerInvocation(InvocationPurpose.PUBLIC_BUILD,
                SemanticProfile.LEGACY_SAFE_INT, ReleaseState.PRE_ACTIVATION,
                registryHash, "0000000000000000000000000000000000000000000000000000000000000000");
            fail("a mismatched releaseStateHash must be rejected (derived, never selectable)");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("releaseStateHash"),
                "the record rejects a non-derived releaseStateHash");
        }
        new CompilerInvocation(InvocationPurpose.PUBLIC_BUILD,
            SemanticProfile.LEGACY_SAFE_INT, ReleaseState.PRE_ACTIVATION,
            registryHash, PINNED_RELEASE_STATE_HASH_PRE);
        check(true, "the provider-derived hash is admitted by the record");
    }

    // =========================================================================
    // 3. releaseStateHash golden and verbose/non-verbose recording
    // =========================================================================

    static void testReleaseStateHashGolden() {
        System.out.println("-- releaseStateHash derivation golden --");

        CapabilityRegistry registry = CapabilityRegistry.releaseRegistry();
        String registryHash = registry.capabilityRegistryHash();
        check(PINNED_REGISTRY_HASH.equals(registryHash),
            "the capabilityRegistryHash equals the stored golden");

        // The digest input is exactly the pinned canonical JSON: sorted keys
        // (capabilityRegistryHash before releaseState), the release state as
        // its closed enum name, the registry hash verbatim.
        String expectedPre = "{\"capabilityRegistryHash\":\"" + registryHash
            + "\",\"releaseState\":\"PRE_ACTIVATION\"}";
        CanonicalJson.Value jsonPre = CanonicalJson.obj(
            CanonicalJson.e("releaseState", CanonicalJson.str("PRE_ACTIVATION")),
            CanonicalJson.e("capabilityRegistryHash", CanonicalJson.str(registryHash)));
        check(expectedPre.equals(CanonicalJson.serializeText(jsonPre)),
            "the PRE_ACTIVATION digest input serializes as " + expectedPre);

        String hashPre = CompilerProfileProvider.deriveReleaseStateHash(
            ReleaseState.PRE_ACTIVATION, registryHash);
        check(PINNED_RELEASE_STATE_HASH_PRE.equals(hashPre),
            "releaseStateHash(PRE_ACTIVATION) equals the stored SHA-256 golden; got " + hashPre);
        check(hashPre.equals(CanonicalJson.sha256Hex(CanonicalJson.serializeBytes(jsonPre))),
            "the helper computes SHA-256 of exactly that canonical JSON");

        String hashActive = CompilerProfileProvider.deriveReleaseStateHash(
            ReleaseState.V1_2_ACTIVE, registryHash);
        check(PINNED_RELEASE_STATE_HASH_ACTIVE.equals(hashActive),
            "releaseStateHash(V1_2_ACTIVE) equals the stored SHA-256 golden; got " + hashActive);

        check(hashPre.length() == 64 && hashPre.matches("[0-9a-f]{64}")
                && hashActive.length() == 64 && hashActive.matches("[0-9a-f]{64}"),
            "both hashes are lowercase 64-character hex");

        // The two release states cannot be confused: different enum names,
        // different digests.
        check(!hashPre.equals(hashActive),
            "PRE_ACTIVATION and V1_2_ACTIVE release-state hashes differ");

        // The hash is recorded on every invocation the provider resolves —
        // all six admitted A1 matrix cells carry the derived golden.
        CompilerInvocation[] resolved = {
            CompilerProfileProvider.resolve(ReleaseState.PRE_ACTIVATION, registry),
            CompilerProfileProvider.resolve(ReleaseState.V1_2_ACTIVE, registry),
            CompilerProfileProvider.resolveCommonShadow(
                SemanticProfile.DEAL_V1_2_INT32, ReleaseState.PRE_ACTIVATION,
                registry),
            CompilerProfileProvider.resolveCommonShadow(
                SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE,
                registry),
            CompilerProfileProvider.resolveLegacyRegression(
                SemanticProfile.LEGACY_SAFE_INT, ReleaseState.PRE_ACTIVATION,
                registry),
            CompilerProfileProvider.resolveLegacyRegression(
                SemanticProfile.LEGACY_SAFE_INT, ReleaseState.V1_2_ACTIVE,
                registry)};
        String[] goldens = {PINNED_RELEASE_STATE_HASH_PRE,
            PINNED_RELEASE_STATE_HASH_ACTIVE, PINNED_RELEASE_STATE_HASH_PRE,
            PINNED_RELEASE_STATE_HASH_ACTIVE, PINNED_RELEASE_STATE_HASH_PRE,
            PINNED_RELEASE_STATE_HASH_ACTIVE};
        for (int i = 0; i < resolved.length; i++) {
            check(goldens[i].equals(resolved[i].releaseStateHash()),
                "invocation " + resolved[i].purpose() + " + "
                    + resolved[i].releaseState() + " records the derived golden hash");
        }
    }

    static void testVerboseAndNonVerbosePaths() throws Exception {
        System.out.println("-- Verbose and non-verbose invocation recording --");

        Path dir = Files.createTempDirectory("deal-invocation-verbose");
        Path src = dir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("trivial.deal"),
            "export function main(): null { return null; }\n");
        Path entry = src.resolve("trivial.deal").toAbsolutePath();
        List<Path> roots = List.of(src.toAbsolutePath());

        CompilerInvocation invocation = CompilerProfileProvider.resolve(
            ReleaseState.PRE_ACTIVATION, CapabilityRegistry.releaseRegistry());

        // Non-verbose orchestrator path: the record carries the derived hash.
        Path outQuiet = dir.resolve("build/quiet");
        CompilationOrchestrator quiet = new CompilationOrchestrator(
            entry, outQuiet, false, false, false, false, Backend.LUAJIT, null,
            roots, null, null, invocation);
        ByteArrayOutputStream quietOut = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(quietOut, true, StandardCharsets.UTF_8));
        boolean quietSuccess;
        try {
            quietSuccess = quiet.compile();
        } finally {
            System.setOut(originalOut);
        }
        check(quietSuccess, "non-verbose compile succeeds");
        check(quiet.invocation().equals(invocation)
                && PINNED_RELEASE_STATE_HASH_PRE.equals(quiet.invocation().releaseStateHash()),
            "the non-verbose path records the derived hash on the invocation record");
        check(!quietOut.toString(StandardCharsets.UTF_8).contains("Purpose:"),
            "the non-verbose report prints no invocation facts (display-only surface)");

        // Verbose orchestrator path: same recorded hash; the verbose report
        // prints the facts but is not the recording location (F1).
        Path outVerbose = dir.resolve("build/verbose");
        CompilationOrchestrator verbose = new CompilationOrchestrator(
            entry, outVerbose, true, false, false, false, Backend.LUAJIT, null,
            roots, null, null, invocation);
        ByteArrayOutputStream verboseOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(verboseOut, true, StandardCharsets.UTF_8));
        boolean verboseSuccess;
        try {
            verboseSuccess = verbose.compile();
        } finally {
            System.setOut(originalOut);
        }
        check(verboseSuccess, "verbose compile succeeds");
        check(verbose.invocation().equals(invocation)
                && PINNED_RELEASE_STATE_HASH_PRE.equals(verbose.invocation().releaseStateHash()),
            "the verbose path records the same derived hash on the invocation record");
        String verboseText = verboseOut.toString(StandardCharsets.UTF_8);
        check(verboseText.contains("Purpose: PUBLIC_BUILD"),
            "the verbose report prints the purpose");
        check(verboseText.contains("Semantic profile: LEGACY_SAFE_INT"),
            "the verbose report prints the profile");
        check(verboseText.contains("Release state: PRE_ACTIVATION"),
            "the verbose report prints the release state");
        check(verboseText.contains("Release-state hash: " + PINNED_RELEASE_STATE_HASH_PRE),
            "the verbose report prints the release-state hash");

        // CLI paths (Main constructs PUBLIC_BUILD through the provider):
        // verbose and non-verbose alike exit 0 and emit the artifact.
        Path cliOut = dir.resolve("build/cli");
        ByteArrayOutputStream cliOutStream = new ByteArrayOutputStream();
        System.setOut(new PrintStream(cliOutStream, true, StandardCharsets.UTF_8));
        int rcQuiet;
        try {
            rcQuiet = Main.run(new String[]{"compile", entry.toString(),
                "--output", cliOut.toString()});
        } finally {
            System.setOut(originalOut);
        }
        check(rcQuiet == 0, "CLI compile without --verbose exits 0");
        check(Files.exists(cliOut.resolve("trivial.lua")),
            "CLI compile without --verbose emits the artifact");

        Path cliOutVerbose = dir.resolve("build/cli_verbose");
        ByteArrayOutputStream cliVerboseStream = new ByteArrayOutputStream();
        System.setOut(new PrintStream(cliVerboseStream, true, StandardCharsets.UTF_8));
        int rcVerbose;
        try {
            rcVerbose = Main.run(new String[]{"compile", entry.toString(),
                "--output", cliOutVerbose.toString(), "--verbose"});
        } finally {
            System.setOut(originalOut);
        }
        check(rcVerbose == 0, "CLI compile with --verbose exits 0");
        check(Files.exists(cliOutVerbose.resolve("trivial.lua")),
            "CLI compile with --verbose emits the artifact");
        check(cliVerboseStream.toString(StandardCharsets.UTF_8)
                .contains("Release-state hash: " + PINNED_RELEASE_STATE_HASH_PRE),
            "the CLI verbose path prints the recorded release-state hash");
    }

    // =========================================================================
    // 4. CapabilityRegistry: closed cross product, digest, no consumer axis
    // =========================================================================

    static void testCapabilityRegistry() throws Exception {
        System.out.println("-- CapabilityRegistry (closed, release-owned) --");

        CapabilityRegistry registry = CapabilityRegistry.releaseRegistry();
        List<CapabilityRegistry.Entry> entries = registry.entries();

        check(entries.size() == 24,
            "the registry has exactly 24 entries (12 capabilities × 2 targets); got "
                + entries.size());
        check(CapabilityRegistry.ENTRY_COUNT == 24 && CapabilityRegistry.VERSION == 1,
            "pinned constants: ENTRY_COUNT == 24, VERSION == 1");

        // Ordering assertion: S4 capability order, LUAJIT before JVM.
        for (int i = 0; i < entries.size(); i++) {
            CapabilityRegistry.Entry entry = entries.get(i);
            check(entry.capability().name().equals(CAPABILITY_ORDER.get(i / 2)),
                "entry " + i + " capability is " + CAPABILITY_ORDER.get(i / 2)
                    + "; got " + entry.capability());
            Target expectedTarget = (i % 2 == 0) ? Target.LUAJIT : Target.JVM;
            check(entry.target() == expectedTarget,
                "entry " + i + " target is " + expectedTarget + "; got " + entry.target());
            check(entry.state() == CapabilityRegistry.State.SHADOW,
                "entry " + i + " is SHADOW (nothing is PROMOTED in this epic)");
        }

        // Every capability × target pair resolves to SHADOW.
        for (SemanticCapability capability : SemanticCapability.values()) {
            check(registry.state(capability, Target.LUAJIT)
                    == CapabilityRegistry.State.SHADOW
                    && registry.state(capability, Target.JVM)
                    == CapabilityRegistry.State.SHADOW,
                capability + " × {LUAJIT, JVM} resolves to SHADOW");
        }

        // Immutability: the entry list admits no mutation.
        try {
            registry.entries().add(new CapabilityRegistry.Entry(
                SemanticCapability.MODULES, Target.LUAJIT, CapabilityRegistry.State.PROMOTED));
            fail("entries() must return an immutable list");
        } catch (UnsupportedOperationException expected) {
            check(true, "entries() is immutable (no entry mutation)");
        }
        try {
            registry.entries().clear();
            fail("entries() must reject clear()");
        } catch (UnsupportedOperationException expected) {
            check(true, "entries() rejects clear()");
        }

        // No promotion transition logic: the class declares no mutation or
        // transition method — all public methods are pinned accessors.
        Set<String> publicMethods = new LinkedHashSet<>();
        for (Method m : CapabilityRegistry.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())) {
                publicMethods.add(m.getName());
            }
        }
        check(publicMethods.equals(Set.of("releaseRegistry", "entries", "state",
                "canonicalJson", "capabilityRegistryHash")),
            "the registry public surface is exactly the five pinned accessors; got "
                + publicMethods);
        for (Field f : CapabilityRegistry.class.getDeclaredFields()) {
            check(Modifier.isFinal(f.getModifiers()),
                "registry field " + f.getName() + " is final (no in-place transition)");
        }

        // Entry record shape: exactly {capability, target, state} — no
        // consumer axis exists (per-consumer evidence is the conformance
        // harness's, never the production registry's).
        Class<CapabilityRegistry.Entry> entryType = CapabilityRegistry.Entry.class;
        RecordComponent[] entryComponents = entryType.getRecordComponents();
        check(entryComponents.length == 3
                && "capability".equals(entryComponents[0].getName())
                && "target".equals(entryComponents[1].getName())
                && "state".equals(entryComponents[2].getName())
                && entryComponents[0].getType() == SemanticCapability.class
                && entryComponents[1].getType() == Target.class
                && entryComponents[2].getType() == CapabilityRegistry.State.class,
            "Entry is exactly {capability, target, state} (no consumer axis)");

        // State and Target are closed: exactly the pinned constants.
        check(Arrays.equals(new String[]{"SHADOW", "PROMOTED"},
                Arrays.stream(CapabilityRegistry.State.values()).map(Enum::name).toArray()),
            "State is exactly SHADOW | PROMOTED");
        check(Arrays.equals(new String[]{"LUAJIT", "JVM"},
                Arrays.stream(Target.values()).map(Enum::name).toArray()),
            "Target is exactly LUAJIT | JVM in the pinned order");

        // Canonical JSON: the exact pinned golden — sorted keys, decimal
        // integers, one entry per closed cross-product pair.
        String jsonText = CanonicalJson.serializeText(registry.canonicalJson());
        check(PINNED_REGISTRY_JSON.equals(jsonText),
            "the registry canonical JSON equals the stored golden; got " + jsonText);
        check(jsonText.startsWith("{\"entries\":[") && jsonText.endsWith("],\"version\":1}"),
            "the canonical JSON shape is {entries: [...], version: 1} with sorted keys");

        // Digest: the stored SHA-256 golden, recomputed in-process.
        String hash = registry.capabilityRegistryHash();
        check(PINNED_REGISTRY_HASH.equals(hash),
            "capabilityRegistryHash equals the stored golden; got " + hash);
        check(hash.equals(CanonicalJson.sha256Hex(
                CanonicalJson.serializeBytes(registry.canonicalJson()))),
            "the digest is SHA-256 of exactly the canonical JSON");

        // Fresh-JVM recomputation: byte-identical across processes.
        ProcessBuilder pb = new ProcessBuilder("java", "-cp",
            Path.of("build").toAbsolutePath().toString(),
            "deal.test.InvocationProfileRegistryTest", "--print-registry-digest");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String freshOutput = new String(p.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int freshExit = p.waitFor();
        check(freshExit == 0 && freshOutput.trim().equals(PINNED_REGISTRY_HASH),
            "a fresh JVM invocation recomputes the byte-identical digest; got exit "
                + freshExit + " output " + freshOutput.trim());

        // Round-trip through T3's parser: re-serializing the parsed tree
        // reproduces the canonical bytes exactly.
        byte[] bytes = CanonicalJson.serializeBytes(registry.canonicalJson());
        CanonicalJson.Value parsed = CanonicalJson.parse(bytes);
        check(java.util.Arrays.equals(bytes,
                CanonicalJson.serializeBytes(parsed)),
            "the registry canonical JSON parses and re-serializes byte-exactly");
    }

    // =========================================================================
    // 5. CLI: no profile/purpose surface, Main constructs PUBLIC_BUILD
    // =========================================================================

    static void testCliSurface() throws Exception {
        System.out.println("-- CLI surface unchanged --");

        Path dir = Files.createTempDirectory("deal-invocation-cli");
        Path src = dir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("cli.deal"),
            "export function main(): null { return null; }\n");
        Path entry = src.resolve("cli.deal").toAbsolutePath();
        Path out = dir.resolve("build/cli");

        // Invented --profile / --purpose flags fail with "unknown option".
        String[] profile = runCliCapturingErr(new String[]{"compile", entry.toString(),
            "--output", out.toString(), "--profile", "DEAL_V1_2_INT32"});
        check("1".equals(profile[0]), "--profile exits 1");
        check(profile[1].contains("unknown option '--profile'"),
            "--profile fails with \"unknown option\": " + profile[1]);

        String[] purpose = runCliCapturingErr(new String[]{"compile", entry.toString(),
            "--output", out.toString(), "--purpose", "COMMON_SHADOW"});
        check("1".equals(purpose[0]), "--purpose exits 1");
        check(purpose[1].contains("unknown option '--purpose'"),
            "--purpose fails with \"unknown option\": " + purpose[1]);

        // Existing CLI behaviors unchanged.
        String[] noArgs = runCliCapturingErr(new String[]{});
        check("1".equals(noArgs[0]), "no args still exits 1");
        String[] unknownCommand = runCliCapturingErr(new String[]{"unknown"});
        check("1".equals(unknownCommand[0]), "unknown command still exits 1");
        String[] missingEntry = runCliCapturingErr(new String[]{"compile"});
        check("1".equals(missingEntry[0]), "missing entry still exits 1");
        int valid = Main.run(new String[]{"compile", entry.toString(),
            "--output", out.toString()});
        check(valid == 0 && Files.exists(out.resolve("cli.lua")),
            "a valid compile still exits 0 and emits the artifact");

        // Source assertion: no new option string was added to deal/Main.java,
        // and Main constructs PUBLIC_BUILD through the provider.
        String mainSource = Files.readString(Path.of("deal/Main.java"));
        check(!mainSource.contains("--profile") && !mainSource.contains("--purpose"),
            "deal/Main.java gains no --profile/--purpose option string");
        check(mainSource.contains("CompilerProfileProvider.resolve")
                && mainSource.contains("ReleaseConfiguration.CURRENT_RELEASE_STATE")
                && mainSource.contains("ReleaseConfiguration.releaseCapabilityRegistry()"),
            "deal/Main.java constructs PUBLIC_BUILD through the provider from "
                + "ReleaseConfiguration (the single release-owned selection point)");

        Set<String> options = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("--[a-z][a-z-]*").matcher(mainSource);
        while (matcher.find()) {
            options.add(matcher.group());
        }
        check(options.equals(Set.of("--output", "--backend", "--diagnostics-json",
                "--verbose", "--dump-ir", "--source-map")),
            "the option strings in deal/Main.java are exactly the existing set "
                + "(no new option added); got " + options);
    }

    /** Runs the CLI with System.err captured; returns {exitCode, stderr}. */
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

    // =========================================================================
    // 6. Combined T1/T3: single canonicalizer, closed enums only
    // =========================================================================

    static void testCombinedT1T3() throws Exception {
        System.out.println("-- Combined T1/T3 coupling --");

        // T3 flow: registry serialization and the release-state-hash
        // computation go exclusively through the single CanonicalJson
        // facility — the pinned goldens match T3's rules (sorted keys,
        // decimal integers, no spaces) and the parser round-trips them.
        CapabilityRegistry registry = CapabilityRegistry.releaseRegistry();
        String jsonText = CanonicalJson.serializeText(registry.canonicalJson());
        check(!jsonText.contains(" ") && !jsonText.contains("\n"),
            "the registry canonical JSON has no whitespace (T3 rules)");
        check(jsonText.contains("\"version\":1"),
            "the version renders as a decimal integer (T3 rules)");

        CanonicalJson.Value releaseStateJson = CanonicalJson.obj(
            CanonicalJson.e("releaseState", CanonicalJson.str("PRE_ACTIVATION")),
            CanonicalJson.e("capabilityRegistryHash",
                CanonicalJson.str(registry.capabilityRegistryHash())));
        String releaseStateText = CanonicalJson.serializeText(releaseStateJson);
        check(releaseStateText.startsWith("{\"capabilityRegistryHash\":\"")
                && releaseStateText.endsWith("\",\"releaseState\":\"PRE_ACTIVATION\"}"),
            "the release-state-hash input obeys T3 sorted-key rules: " + releaseStateText);

        // No second serializer exists in the invocation/registry code: each
        // production file flows through CanonicalJson only and declares no
        // serialization, JSON-text, digest, or hand-built-JSON machinery.
        for (String file : List.of("deal/semantic/CompilerInvocation.java",
                "deal/semantic/CompilerProfileProvider.java",
                "deal/semantic/CapabilityRegistry.java")) {
            String text = Files.readString(Path.of(file));
            check(text.contains("deal.semantic.ir.CanonicalJson")
                    || text.contains("CompilerProfileProvider.deriveReleaseStateHash"),
                file + " flows through T3's single canonicalizer "
                    + "(directly or via the provider's derivation)");
            check(!text.contains("MessageDigest") && !text.contains("HexFormat")
                    && !text.contains("getInstance(\"SHA"),
                file + " implements no digest of its own (T3's helper is the single one)");
            check(!text.contains("StringBuilder") && !text.contains("\\\"{\\\""),
                file + " implements no hand-built JSON serialization");
            check(!text.contains("org.json") && !text.contains("Gson")
                    && !text.contains("Jackson") && !text.contains("ObjectMapper"),
                file + " imports no second JSON serializer library");
        }
        // The registry JSON mapping is a record→value-model mapping only;
        // the files carry no serializer class of their own.
        for (String file : List.of("deal/semantic/CompilerInvocation.java",
                "deal/semantic/CompilerProfileProvider.java",
                "deal/semantic/CapabilityRegistry.java")) {
            String text = Files.readString(Path.of(file));
            check(!text.matches("(?s).*\\bclass\\s+\\w*Serial\\w*\\s+.*"),
                file + " declares no serializer class");
            check(!text.contains("static String serialize")
                    && !text.contains("static byte[] serialize"),
                file + " declares no serialize method");
        }

        // T1 closedness: the records admit only the T1 closed enums
        // (compile-time closedness — parameter/component types are the
        // closed enum types, so no open value can compile).
        Method resolve = CompilerProfileProvider.class.getDeclaredMethod("resolve",
            ReleaseState.class, CapabilityRegistry.class);
        check(Arrays.equals(resolve.getParameterTypes(),
                new Class<?>[]{ReleaseState.class, CapabilityRegistry.class}),
            "resolve(releaseState, registry) is the derivation-only PUBLIC_BUILD entry");
        Method resolveShadow = CompilerProfileProvider.class.getDeclaredMethod(
            "resolveCommonShadow", SemanticProfile.class, ReleaseState.class,
            CapabilityRegistry.class);
        check(Arrays.equals(resolveShadow.getParameterTypes(),
                new Class<?>[]{SemanticProfile.class, ReleaseState.class,
                    CapabilityRegistry.class}),
            "resolveCommonShadow takes the explicit profile plus release state and "
                + "registry");
        Method resolveLegacy = CompilerProfileProvider.class.getDeclaredMethod(
            "resolveLegacyRegression", SemanticProfile.class, ReleaseState.class,
            CapabilityRegistry.class);
        check(Arrays.equals(resolveLegacy.getParameterTypes(),
                new Class<?>[]{SemanticProfile.class, ReleaseState.class,
                    CapabilityRegistry.class}),
            "resolveLegacyRegression takes the explicit profile plus release state "
                + "and registry");
        try {
            CompilerProfileProvider.class.getDeclaredMethod("resolve",
                InvocationPurpose.class, ReleaseState.class, CapabilityRegistry.class);
            fail("the derivation-based purpose overload must be superseded (A1)");
        } catch (NoSuchMethodException expected) {
            check(true, "no factory derives a non-PUBLIC_BUILD profile from the "
                + "release state alone (the derivation-based purpose overload is "
                + "superseded)");
        }
        check(Arrays.equals(new String[]{"PUBLIC_BUILD", "COMMON_SHADOW", "LEGACY_REGRESSION"},
                Arrays.stream(InvocationPurpose.values()).map(Enum::name).toArray()),
            "InvocationPurpose is exactly the T1 closed set");
        check(Arrays.equals(new String[]{"LEGACY_SAFE_INT", "DEAL_V1_2_INT32"},
                Arrays.stream(SemanticProfile.values()).map(Enum::name).toArray()),
            "SemanticProfile is exactly the T1 closed set");
        check(Arrays.equals(new String[]{"PRE_ACTIVATION", "V1_2_ACTIVE"},
                Arrays.stream(ReleaseState.values()).map(Enum::name).toArray()),
            "ReleaseState is exactly the T1 closed set");
        check(CAPABILITY_ORDER.equals(
                Arrays.stream(SemanticCapability.values()).map(Enum::name).toList()),
            "SemanticCapability is exactly the T1 closed set in the S4 order");

        // Broken-T1 coupling: the registry JSON renders every capability as
        // its closed enum name and the goldens depend on exactly those names
        // and that order — renaming, reordering, or adding a T1 constant
        // changes the JSON and fails the pinned digest golden.
        for (int i = 0; i < CAPABILITY_ORDER.size(); i++) {
            SemanticCapability capability = SemanticCapability.values()[i];
            check(SemanticCapability.valueOf(CAPABILITY_ORDER.get(i)) == capability,
                "T1 valueOf round-trip for " + CAPABILITY_ORDER.get(i));
            check(PINNED_REGISTRY_JSON.contains(
                    "\"capability\":\"" + capability.name() + "\""),
                "the registry golden carries the closed enum name " + capability.name());
        }
        try {
            SemanticCapability.valueOf("OUT_OF_SET");
            fail("an out-of-set SemanticCapability name must not resolve (broken-T1 fault)");
        } catch (IllegalArgumentException expected) {
            check(true, "SemanticCapability rejects an out-of-set name");
        }

        // Broken-T3 coupling: the pinned digests were recomputed over the
        // exact canonical text; any serializer deviation (key order, spacing,
        // escaping) changes the SHA-256 and fails the stored goldens.
        check(PINNED_REGISTRY_HASH.equals(
                CanonicalJson.sha256Hex(PINNED_REGISTRY_JSON.getBytes(StandardCharsets.UTF_8))),
            "the stored registry-hash golden is SHA-256 of the stored JSON golden (T3 rules)");
        check(PINNED_RELEASE_STATE_HASH_PRE.equals(CanonicalJson.sha256Hex(
                ("{\"capabilityRegistryHash\":\"" + PINNED_REGISTRY_HASH
                    + "\",\"releaseState\":\"PRE_ACTIVATION\"}").getBytes(StandardCharsets.UTF_8))),
            "the stored release-state-hash golden is SHA-256 of the pinned canonical JSON");
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        // Fresh-JVM digest recomputation mode: prints the registry digest and
        // nothing else (the byte-identical-across-builds proof).
        if (args.length > 0 && "--print-registry-digest".equals(args[0])) {
            System.out.print(CapabilityRegistry.releaseRegistry().capabilityRegistryHash());
            return;
        }

        System.out.println("=== Invocation / Profile / Capability Registry Test (ISSUE-0284) ===\n");

        testInvocationRecordShape();
        testResolutionMatrix();
        testReleaseStateHashGolden();
        testVerboseAndNonVerbosePaths();
        testCapabilityRegistry();
        testCliSurface();
        testCombinedT1T3();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
