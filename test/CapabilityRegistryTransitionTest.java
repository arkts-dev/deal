package deal.test;

import deal.semantic.CapabilityRegistry;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.Target;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticCapability;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ISSUE-0485: the single release-owned promotion/demotion transition
 * surface {@link CapabilityRegistry#withState(SemanticCapability, Target,
 * CapabilityRegistry.State)} — the D3 boundary contract's six pinned
 * invariants, proven over every closed (capability × target × state)
 * transition:
 *
 * <ol>
 *   <li>Pure and total over the closed axes: all 48 closed transitions
 *       are accepted; null components are rejected with the registry's
 *       null policy.</li>
 *   <li>Immutable-producing: the source instance (including
 *       {@code releaseRegistry()}/{@code RELEASE_DEFAULT}) is never
 *       mutated; the result is a fresh instance.</li>
 *   <li>Closed-shape preservation: every result passes the closed
 *       cross-product validation and pinned ordering (24 entries, S4
 *       capability order, LUAJIT before JVM) through the existing
 *       private constructor; no new entry shape and no consumer axis.</li>
 *   <li>Digest recomputation: the result's digest is the existing
 *       canonical JSON SHA-256 over the result's own entries.</li>
 *   <li>No-op idempotence: {@code r.withState(c, t, r.state(c, t))}
 *       yields byte-identical entries and an equal digest.</li>
 *   <li>No gate policy inside the surface: the transition is a pure
 *       state derivation; promotion-gate enforcement lives in the
 *       release action, never here.</li>
 * </ol>
 *
 * <p>Applying {@code PROMOTED} is promotion and {@code SHADOW} is
 * demotion — one surface. The release default stays all-SHADOW and
 * byte-unchanged after any derivation, and the armed PRE_ACTIVATION
 * gate is untouched. ISSUE-0412's planner-only rollback observation
 * execution consumes this surface and is out of this task's scope.</p>
 */
public class CapabilityRegistryTransitionTest {

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

    private static boolean sameEntry(CapabilityRegistry.Entry a, CapabilityRegistry.Entry b) {
        return a.capability() == b.capability()
            && a.target() == b.target()
            && a.state() == b.state();
    }

    private static boolean sameEntries(List<CapabilityRegistry.Entry> a,
            List<CapabilityRegistry.Entry> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!sameEntry(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static int differingEntryCount(List<CapabilityRegistry.Entry> a,
            List<CapabilityRegistry.Entry> b) {
        int diffs = 0;
        for (int i = 0; i < a.size() && i < b.size(); i++) {
            if (!sameEntry(a.get(i), b.get(i))) {
                diffs++;
            }
        }
        return diffs + Math.abs(a.size() - b.size());
    }

    private static void checkPinnedOrdering(List<CapabilityRegistry.Entry> entries,
            String what) {
        SemanticCapability[] capabilities = SemanticCapability.values();
        boolean ordered = entries.size() == CapabilityRegistry.ENTRY_COUNT;
        for (int i = 0; i < entries.size() && ordered; i++) {
            CapabilityRegistry.Entry entry = entries.get(i);
            SemanticCapability expectedCapability = capabilities[i / 2];
            Target expectedTarget = (i % 2 == 0) ? Target.LUAJIT : Target.JVM;
            ordered = entry.capability() == expectedCapability
                && entry.target() == expectedTarget;
        }
        check(ordered,
            what + ": 24 entries in the pinned S4 capability order with LUAJIT "
                + "before JVM within each capability");
    }

    // =========================================================================
    // 1. Pure and total over the closed axes; promotion and demotion are
    //    one surface
    // =========================================================================

    static void testTotalityOverClosedAxes() {
        System.out.println("-- Totality over the closed capability × target × state axes --");

        int transitions = 0;
        for (SemanticCapability capability : SemanticCapability.values()) {
            for (Target target : Target.values()) {
                for (CapabilityRegistry.State state : CapabilityRegistry.State.values()) {
                    CapabilityRegistry derived = CapabilityRegistry.releaseRegistry()
                        .withState(capability, target, state);
                    check(derived != null
                            && derived.state(capability, target) == state,
                        capability + " × " + target + " → " + state
                            + " is accepted and carries the requested state");
                    transitions++;
                }
            }
        }
        check(transitions == CapabilityRegistry.ENTRY_COUNT * 2,
            "all " + transitions + " closed transitions (24 pairs × 2 states) run");

        // Promotion and demotion through one surface.
        CapabilityRegistry promoted = CapabilityRegistry.releaseRegistry()
            .withState(SemanticCapability.SIGNED_INT32, Target.LUAJIT,
                CapabilityRegistry.State.PROMOTED);
        check(promoted.state(SemanticCapability.SIGNED_INT32, Target.LUAJIT)
                == CapabilityRegistry.State.PROMOTED,
            "applying PROMOTED is promotion through withState");
        CapabilityRegistry demoted = promoted.withState(
            SemanticCapability.SIGNED_INT32, Target.LUAJIT, CapabilityRegistry.State.SHADOW);
        check(demoted.state(SemanticCapability.SIGNED_INT32, Target.LUAJIT)
                == CapabilityRegistry.State.SHADOW,
            "applying SHADOW is demotion through the same withState surface");
        check(sameEntries(demoted.entries(), CapabilityRegistry.releaseRegistry().entries())
                && demoted.capabilityRegistryHash()
                    .equals(CapabilityRegistry.releaseRegistry().capabilityRegistryHash()),
            "promotion then demotion round-trips to the release default's entries and "
                + "digest (one surface, both directions)");
    }

    // =========================================================================
    // 2. Null rejection with the registry's existing null policy
    // =========================================================================

    static void testNullRejection() {
        System.out.println("-- Null rejection (Objects.requireNonNull, matching Entry) --");

        CapabilityRegistry registry = CapabilityRegistry.releaseRegistry();
        try {
            registry.withState(null, Target.LUAJIT, CapabilityRegistry.State.PROMOTED);
            fail("a null capability must be rejected");
        } catch (NullPointerException expected) {
            check(true, "withState rejects a null capability");
        }
        try {
            registry.withState(SemanticCapability.MODULES, null,
                CapabilityRegistry.State.PROMOTED);
            fail("a null target must be rejected");
        } catch (NullPointerException expected) {
            check(true, "withState rejects a null target");
        }
        try {
            registry.withState(SemanticCapability.MODULES, Target.JVM, null);
            fail("a null state must be rejected");
        } catch (NullPointerException expected) {
            check(true, "withState rejects a null state");
        }
        // The null policy matches Entry's compact constructor (the same
        // Objects.requireNonNull mechanism, the same message shape).
        try {
            new CapabilityRegistry.Entry(null, Target.LUAJIT, CapabilityRegistry.State.SHADOW);
            fail("Entry must reject a null capability");
        } catch (NullPointerException expected) {
            check(expected.getMessage() != null
                    && expected.getMessage().equals("capability must not be null"),
                "Entry rejects a null capability with the pinned message");
        }
    }

    // =========================================================================
    // 3. Immutable-producing: source (incl. the release default) never
    //    mutated; result is fresh with exactly the single entry changed
    // =========================================================================

    static void testSourceImmutabilityIncludingReleaseDefault() {
        System.out.println("-- Source immutability incl. the release default; "
            + "fresh result --");

        CapabilityRegistry source = CapabilityRegistry.releaseRegistry();
        String sourceHash = source.capabilityRegistryHash();
        List<CapabilityRegistry.Entry> before = List.copyOf(source.entries());

        CapabilityRegistry result = source.withState(SemanticCapability.BINDINGS,
            Target.JVM, CapabilityRegistry.State.PROMOTED);
        check(result != source, "the result is a fresh instance, not the source");
        check(source == CapabilityRegistry.releaseRegistry(),
            "releaseRegistry() still returns the same release-owned default instance");
        check(sameEntries(source.entries(), before),
            "the source instance's entries are byte-unchanged after the derivation");
        check(source.capabilityRegistryHash().equals(sourceHash),
            "the source instance's digest is unchanged");
        check(source.entries().stream()
                .allMatch(e -> e.state() == CapabilityRegistry.State.SHADOW),
            "the source instance stays all-SHADOW");

        // The release default stays byte-unchanged (entries and digest).
        check(sameEntries(CapabilityRegistry.releaseRegistry().entries(), before),
            "releaseRegistry() entries are byte-unchanged after the derivation");
        check(CapabilityRegistry.releaseRegistry().capabilityRegistryHash()
                .equals(sourceHash),
            "releaseRegistry() digest is byte-unchanged after the derivation");

        // The result differs in exactly the single requested entry.
        check(differingEntryCount(result.entries(), before) == 1,
            "the result differs from the source in exactly one entry");
        check(result.state(SemanticCapability.BINDINGS, Target.JVM)
                == CapabilityRegistry.State.PROMOTED,
            "the single differing entry is BINDINGS × JVM carrying PROMOTED");

        // Derived registries are immutable too (the rebuild reuses the
        // private constructor's List.copyOf path).
        try {
            result.entries().add(new CapabilityRegistry.Entry(
                SemanticCapability.MODULES, Target.LUAJIT,
                CapabilityRegistry.State.PROMOTED));
            fail("a derived registry's entries() must be immutable");
        } catch (UnsupportedOperationException expected) {
            check(true, "a derived registry's entries() is immutable");
        }

        // A second derivation from the result leaves the result and the
        // release default untouched.
        CapabilityRegistry second = result.withState(SemanticCapability.BINDINGS,
            Target.JVM, CapabilityRegistry.State.SHADOW);
        check(second != result && second != source
                && result.state(SemanticCapability.BINDINGS, Target.JVM)
                    == CapabilityRegistry.State.PROMOTED
                && result.capabilityRegistryHash()
                    .equals(result.withState(SemanticCapability.BINDINGS, Target.JVM,
                        CapabilityRegistry.State.PROMOTED).capabilityRegistryHash()),
            "composing transitions leaves every earlier instance unchanged");
        check(sameEntries(second.entries(), before)
                && second.capabilityRegistryHash().equals(sourceHash),
            "demoting the single promoted entry restores the release default's "
                + "entries and digest");
        check(CapabilityRegistry.releaseRegistry().capabilityRegistryHash()
                .equals(sourceHash),
            "releaseRegistry() stays byte-unchanged after composed transitions");
    }

    // =========================================================================
    // 4. Exactly one entry changed, for every closed pair in both directions
    // =========================================================================

    static void testExactlyOneEntryChanged() {
        System.out.println("-- Exactly one entry changed per transition (all 24 pairs, "
            + "both directions) --");

        CapabilityRegistry release = CapabilityRegistry.releaseRegistry();
        for (SemanticCapability capability : SemanticCapability.values()) {
            for (Target target : Target.values()) {
                CapabilityRegistry promoted = release.withState(capability, target,
                    CapabilityRegistry.State.PROMOTED);
                check(differingEntryCount(promoted.entries(), release.entries()) == 1
                        && promoted.state(capability, target)
                            == CapabilityRegistry.State.PROMOTED,
                    capability + " × " + target + " promotion changes exactly one entry");
                CapabilityRegistry demoted = promoted.withState(capability, target,
                    CapabilityRegistry.State.SHADOW);
                check(differingEntryCount(demoted.entries(), promoted.entries()) == 1
                        && demoted.state(capability, target)
                            == CapabilityRegistry.State.SHADOW,
                    capability + " × " + target + " demotion changes exactly one entry");
            }
        }
    }

    // =========================================================================
    // 5. Closed-shape preservation after every transition; no new entry
    //    shape, no consumer axis, no public construction path
    // =========================================================================

    static void testClosedShapeAndPinnedOrderingAfterEveryTransition() {
        System.out.println("-- Closed shape + pinned ordering after every transition; "
            + "closed entry shape --");

        int transitions = 0;
        for (SemanticCapability capability : SemanticCapability.values()) {
            for (Target target : Target.values()) {
                for (CapabilityRegistry.State state : CapabilityRegistry.State.values()) {
                    CapabilityRegistry derived = CapabilityRegistry.releaseRegistry()
                        .withState(capability, target, state);
                    checkPinnedOrdering(derived.entries(),
                        capability + " × " + target + " → " + state);
                    transitions++;
                }
            }
        }
        check(transitions == CapabilityRegistry.ENTRY_COUNT * 2,
            "closed shape and pinned ordering hold after all " + transitions
                + " closed transitions");

        // Entry shape: exactly {capability, target, state} — no consumer
        // axis exists (F7's two-axis pin).
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

        // No public construction path: every constructor stays private, so
        // the release default and the withState derivation are the only
        // ways to obtain a registry.
        boolean constructorsPrivate = true;
        for (Constructor<?> ctor : CapabilityRegistry.class.getDeclaredConstructors()) {
            constructorsPrivate &= Modifier.isPrivate(ctor.getModifiers());
        }
        check(constructorsPrivate,
            "every CapabilityRegistry constructor stays private (the rebuild reuses "
                + "the existing private constructor/validation path)");
    }

    // =========================================================================
    // 6. Digest recomputation: the result's digest is the existing canonical
    //    JSON SHA-256 over the result's own entries
    // =========================================================================

    static void testDigestRecomputation() {
        System.out.println("-- Digest recomputation over the result's own entries --");

        CapabilityRegistry release = CapabilityRegistry.releaseRegistry();
        String releaseHash = release.capabilityRegistryHash();
        int transitions = 0;
        for (SemanticCapability capability : SemanticCapability.values()) {
            for (Target target : Target.values()) {
                for (CapabilityRegistry.State state : CapabilityRegistry.State.values()) {
                    CapabilityRegistry derived = release.withState(capability, target, state);
                    String recomputed = CanonicalJson.sha256Hex(
                        CanonicalJson.serializeBytes(derived.canonicalJson()));
                    check(derived.capabilityRegistryHash().equals(recomputed),
                        capability + " × " + target + " → " + state
                            + ": the digest is SHA-256 of the result's own canonical "
                            + "JSON (the existing derivation, never hand-rolled)");
                    if (state != CapabilityRegistry.State.SHADOW) {
                        check(!derived.capabilityRegistryHash().equals(releaseHash),
                            capability + " × " + target + " → " + state
                                + ": the digest differs from the all-SHADOW release "
                                + "digest");
                    } else {
                        check(derived.capabilityRegistryHash().equals(releaseHash),
                            capability + " × " + target + " → SHADOW (a no-op): the "
                                + "digest equals the release digest");
                    }
                    transitions++;
                }
            }
        }
        check(transitions == CapabilityRegistry.ENTRY_COUNT * 2,
            "digest recomputation holds after all " + transitions + " transitions");
    }

    // =========================================================================
    // 7. No-op idempotence: withState(c, t, state(c, t)) yields
    //    byte-identical entries and an equal digest
    // =========================================================================

    static void testNoOpIdempotence() {
        System.out.println("-- No-op idempotence --");

        CapabilityRegistry release = CapabilityRegistry.releaseRegistry();
        for (SemanticCapability capability : SemanticCapability.values()) {
            for (Target target : Target.values()) {
                CapabilityRegistry noOp = release.withState(capability, target,
                    release.state(capability, target));
                check(sameEntries(noOp.entries(), release.entries())
                        && noOp.capabilityRegistryHash()
                            .equals(release.capabilityRegistryHash()),
                    capability + " × " + target + " no-op on the release default: "
                        + "byte-identical entries and equal digest");
            }
        }

        // A partially promoted registry (the ISSUE-0412 observation's
        // construction shape): no-ops over every pair stay byte-identical.
        CapabilityRegistry promoted = CapabilityRegistry.releaseRegistry()
            .withState(SemanticCapability.FOUNDATION_VALUES, Target.LUAJIT,
                CapabilityRegistry.State.PROMOTED)
            .withState(SemanticCapability.SIGNED_INT32, Target.LUAJIT,
                CapabilityRegistry.State.PROMOTED);
        String promotedHash = promoted.capabilityRegistryHash();
        for (SemanticCapability capability : SemanticCapability.values()) {
            for (Target target : Target.values()) {
                CapabilityRegistry noOp = promoted.withState(capability, target,
                    promoted.state(capability, target));
                check(sameEntries(noOp.entries(), promoted.entries())
                        && noOp.capabilityRegistryHash().equals(promotedHash),
                    capability + " × " + target + " no-op on the partially promoted "
                        + "registry: byte-identical entries and equal digest");
            }
        }
    }

    // =========================================================================
    // 8. No gate policy inside the surface: pure state derivation
    // =========================================================================

    static void testPolicyFreeness() throws Exception {
        System.out.println("-- Policy-freeness: pure state derivation, no gate policy --");

        // The exact D3 signature: (SemanticCapability, Target, State) ->
        // CapabilityRegistry — the single transition primitive.
        Method withState = CapabilityRegistry.class.getDeclaredMethod("withState",
            SemanticCapability.class, Target.class, CapabilityRegistry.State.class);
        check(Modifier.isPublic(withState.getModifiers())
                && withState.getReturnType() == CapabilityRegistry.class
                && withState.getParameterCount() == 3,
            "withState is the public three-argument (capability, target, state) -> "
                + "CapabilityRegistry derivation");

        // The complete public surface: the five pinned accessors plus the
        // single withState transition — no gate-policy or mutation method.
        Set<String> publicMethods = new LinkedHashSet<>();
        for (Method m : CapabilityRegistry.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())) {
                publicMethods.add(m.getName());
            }
        }
        check(publicMethods.equals(Set.of("releaseRegistry", "entries", "state",
                "canonicalJson", "capabilityRegistryHash", "withState")),
            "the registry public surface is exactly the five pinned accessors plus "
                + "withState; got " + publicMethods);

        // The STDLIB_TIME_CONFLICT lock is release-action policy (parent
        // D8), not surface policy: the pure derivation accepts the pair.
        CapabilityRegistry locked = CapabilityRegistry.releaseRegistry().withState(
            SemanticCapability.STDLIB_TIME_CONFLICT, Target.LUAJIT,
            CapabilityRegistry.State.PROMOTED);
        check(locked.state(SemanticCapability.STDLIB_TIME_CONFLICT, Target.LUAJIT)
                == CapabilityRegistry.State.PROMOTED,
            "withState derives STDLIB_TIME_CONFLICT → PROMOTED (the lock lives in "
                + "the release action, never in the surface)");

        // The production source carries no gate-policy vocabulary: no
        // rejection/lock/order/precondition strings, no gate records.
        String source = Files.readString(Path.of("deal/semantic/CapabilityRegistry.java"));
        for (String forbidden : List.of("PROMOTION_GATE_REJECTED", "PROMOTION_LOCKED",
                "PROMOTION_ORDER_VIOLATED", "ACTIVATION_PRECONDITION_FAILED",
                "RETENTION_GATE_UNSATISFIED", "PromotionGateRecord",
                "PromotionGateItem", "RetentionGateRecord")) {
            check(!source.contains(forbidden),
                "CapabilityRegistry.java carries no gate-policy vocabulary ("
                    + forbidden + ")");
        }
    }

    // =========================================================================
    // 9. Release default and armed gate: unchanged after every derivation
    // =========================================================================

    static void testArmedGateAndReleaseDefaultUnchanged() {
        System.out.println("-- Release default all-SHADOW + armed PRE_ACTIVATION gate "
            + "unchanged --");

        check(ReleaseConfiguration.CURRENT_RELEASE_STATE == ReleaseState.PRE_ACTIVATION,
            "the release state is PRE_ACTIVATION before any derivation (armed gate)");

        CapabilityRegistry release = CapabilityRegistry.releaseRegistry();
        String initialHash = release.capabilityRegistryHash();
        List<CapabilityRegistry.Entry> initialEntries = List.copyOf(release.entries());

        // Derive heavily — every closed pair promoted for both targets, and
        // demotions back — then re-assert the release default and the gate.
        for (SemanticCapability capability : SemanticCapability.values()) {
            for (Target target : Target.values()) {
                release.withState(capability, target, CapabilityRegistry.State.PROMOTED);
            }
        }
        CapabilityRegistry promoted = release;
        for (SemanticCapability capability : SemanticCapability.values()) {
            promoted = promoted.withState(capability, Target.LUAJIT,
                CapabilityRegistry.State.PROMOTED);
        }
        for (SemanticCapability capability : SemanticCapability.values()) {
            promoted.withState(capability, Target.JVM, CapabilityRegistry.State.SHADOW);
        }

        check(sameEntries(CapabilityRegistry.releaseRegistry().entries(), initialEntries),
            "releaseRegistry() entries stay byte-unchanged after every derivation");
        check(CapabilityRegistry.releaseRegistry().capabilityRegistryHash()
                .equals(initialHash),
            "releaseRegistry() digest stays byte-unchanged after every derivation");
        check(CapabilityRegistry.releaseRegistry().entries().stream()
                .allMatch(e -> e.state() == CapabilityRegistry.State.SHADOW),
            "releaseRegistry() stays all-SHADOW after every derivation");
        check(ReleaseConfiguration.CURRENT_RELEASE_STATE == ReleaseState.PRE_ACTIVATION,
            "the release state is still PRE_ACTIVATION after every derivation "
                + "(the armed gate stays green; the public flip is E12's action)");
    }

    public static void main(String[] args) {
        try {
            testTotalityOverClosedAxes();
            testNullRejection();
            testSourceImmutabilityIncludingReleaseDefault();
            testExactlyOneEntryChanged();
            testClosedShapeAndPinnedOrderingAfterEveryTransition();
            testDigestRecomputation();
            testNoOpIdempotence();
            testPolicyFreeness();
            testArmedGateAndReleaseDefaultUnchanged();
        } catch (Throwable t) {
            failed++;
            System.err.println("FAIL: unexpected " + t);
            t.printStackTrace();
        }
        System.out.println();
        System.out.println("CapabilityRegistryTransitionTest: " + passed + " passed, "
            + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }
}
