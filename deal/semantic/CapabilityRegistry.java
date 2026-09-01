package deal.semantic;

import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.SemanticCapability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The closed release-owned capability registry (foundation F7): an
 * immutable capability × target state table over exactly the closed
 * cross product — one entry per {@link SemanticCapability} ×
 * {@link Target} pair, 24 entries — with the exact canonical JSON digest
 *
 * <pre>{@code capabilityRegistryHash = SHA-256(canonical JSON
 * {version: 1, entries: [{capability, target, state}]})}</pre>
 *
 * with the entries ordered by the S4 capability order (the
 * {@link SemanticCapability} declaration order) and, within a
 * capability, {@code LUAJIT} before {@code JVM}. The serialization flows
 * through the single {@link CanonicalJson} facility (S2; foundation F8),
 * so the digest is fully determined and byte-identical across builds.
 *
 * <p>Exactly two axes exist: capability × target. Per-consumer promotion
 * evidence — the conformance harness's {@code capabilityEvidence} map —
 * belongs to the conformance harness, never the production registry, and
 * no consumer axis or consumer-typed member exists here. The release
 * default ({@link #releaseRegistry()}) carries every entry
 * {@link State#SHADOW}: nothing is {@code PROMOTED} in the release
 * registry, and the promotion/demotion transitions are ISSUE-0241's
 * single closed transition surface — {@link #withState}, a pure state
 * derivation that carries no gate policy. The registry is release-owned,
 * closed, and immutable: {@link #releaseRegistry()} is the only
 * release-owned default, no mutator exists, and every transition is a
 * pure derivation returning a new validated instance that leaves the
 * source byte-unchanged.</p>
 */
public final class CapabilityRegistry {

    /**
     * The capability state axis: exactly {@code SHADOW | PROMOTED}
     * (foundation F7). {@code SHADOW} permits testing but never changes
     * a production route; {@code PROMOTED} is unreachable in the release
     * registry and becomes reachable only through the ISSUE-0241
     * transition surface ({@link #withState}).
     */
    public enum State {

        /** Capability exists for internal shadow testing only. */
        SHADOW,

        /** Capability promoted for production routing (ISSUE-0241). */
        PROMOTED
    }

    /**
     * One closed cross-product entry: {@code {capability, target, state}}.
     * Exactly these three components — no consumer axis exists.
     */
    public record Entry(SemanticCapability capability, Target target, State state) {

        public Entry {
            Objects.requireNonNull(capability, "capability must not be null");
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(state, "state must not be null");
        }
    }

    /** The pinned canonical JSON shape version of the registry. */
    public static final int VERSION = 1;

    /** The entry count of the closed cross product: 12 capabilities × 2 targets. */
    public static final int ENTRY_COUNT =
        SemanticCapability.values().length * Target.values().length;

    /** The single release-owned registry of this epic: all 24 entries SHADOW. */
    private static final CapabilityRegistry RELEASE_DEFAULT = buildReleaseDefault();

    private final List<Entry> entries;
    private final CanonicalJson.Value canonicalJson;
    private final String capabilityRegistryHash;

    private CapabilityRegistry(List<Entry> entries) {
        List<Entry> copy = List.copyOf(entries);
        validateClosedCrossProduct(copy);
        this.entries = copy;
        this.canonicalJson = canonicalValue(copy);
        this.capabilityRegistryHash =
            CanonicalJson.sha256Hex(CanonicalJson.serializeBytes(this.canonicalJson));
    }

    /** Builds the release-owned default: the full cross product, all SHADOW. */
    private static CapabilityRegistry buildReleaseDefault() {
        List<Entry> entries = new ArrayList<>(ENTRY_COUNT);
        for (SemanticCapability capability : SemanticCapability.values()) {
            entries.add(new Entry(capability, Target.LUAJIT, State.SHADOW));
            entries.add(new Entry(capability, Target.JVM, State.SHADOW));
        }
        return new CapabilityRegistry(entries);
    }

    /**
     * Enforces the closed cross-product shape and the pinned ordering:
     * exactly one entry per capability × target pair, in the S4
     * capability order with {@code LUAJIT} before {@code JVM}. Any
     * deviation is a producer defect — the canonical digest depends on
     * exactly this order (F7).
     */
    private static void validateClosedCrossProduct(List<Entry> entries) {
        if (entries.size() != ENTRY_COUNT) {
            throw new IllegalArgumentException("the capability registry must cover exactly "
                + ENTRY_COUNT + " closed cross-product entries; got " + entries.size());
        }
        int index = 0;
        for (SemanticCapability capability : SemanticCapability.values()) {
            for (Target target : Target.values()) {
                Entry entry = entries.get(index);
                if (entry.capability() != capability || entry.target() != target) {
                    throw new IllegalArgumentException(
                        "registry entry " + index + " must be " + capability + " × " + target
                            + " in the pinned order (S4 capability order, LUAJIT before "
                            + "JVM); got " + entry.capability() + " × " + entry.target());
                }
                index++;
            }
        }
    }

    /** Maps the ordered entries onto the single canonical JSON value model (S2). */
    private static CanonicalJson.Value canonicalValue(List<Entry> entries) {
        List<CanonicalJson.Value> entryValues = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            entryValues.add(CanonicalJson.obj(
                CanonicalJson.e("capability", CanonicalJson.str(entry.capability().name())),
                CanonicalJson.e("state", CanonicalJson.str(entry.state().name())),
                CanonicalJson.e("target", CanonicalJson.str(entry.target().name()))));
        }
        return CanonicalJson.obj(
            CanonicalJson.e("entries", CanonicalJson.arr(entryValues)),
            CanonicalJson.e("version", CanonicalJson.intValue(VERSION)));
    }

    /**
     * The single release-owned registry of this epic: the closed
     * capability × target cross product, every entry {@link State#SHADOW},
     * with the pinned canonical JSON digest.
     *
     * @return the immutable release registry
     */
    public static CapabilityRegistry releaseRegistry() {
        return RELEASE_DEFAULT;
    }

    /**
     * The 24 ordered entries: the S4 capability order, {@code LUAJIT}
     * before {@code JVM} within each capability. The list is immutable.
     *
     * @return the immutable ordered entry list
     */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * The exact capability × target state lookup (F4 rule 4 consumes this
     * lookup for the target).
     *
     * @param capability the closed capability; non-null
     * @param target     the closed target; non-null
     * @return the entry's state ({@link State#SHADOW} for the release
     *         default; {@link State#PROMOTED} only on instances derived
     *         through {@link #withState})
     */
    public State state(SemanticCapability capability, Target target) {
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(target, "target must not be null");
        for (Entry entry : entries) {
            if (entry.capability() == capability && entry.target() == target) {
                return entry.state();
            }
        }
        // Unreachable: the constructor enforces the closed cross product.
        throw new IllegalArgumentException("no registry entry for " + capability
            + " × " + target + " (the closed cross product is complete)");
    }

    /**
     * The single closed promotion/demotion transition surface (ISSUE-0241
     * D3; promotion and demotion are one surface): derives a new
     * immutable registry whose entries equal the source's except the
     * single {@code (capability × target)} entry, which carries the
     * requested {@code state}.
     *
     * <p>Contract (D3, pinned):</p>
     * <ol>
     *   <li>pure and total over the closed axes — any
     *       {@code (capability × target × state)} combination is
     *       accepted; null components are rejected with the registry's
     *       existing null policy ({@code Objects.requireNonNull},
     *       matching {@link Entry}'s compact constructor);</li>
     *   <li>immutable-producing — the source instance (including
     *       {@link #releaseRegistry()}/{@code RELEASE_DEFAULT}) is never
     *       mutated; the result is a fresh instance whose entries equal
     *       the source's except the single entry carrying the requested
     *       state;</li>
     *   <li>closed-shape-preserving — the result passes the existing
     *       closed cross-product validation and pinned ordering (24
     *       entries, S4 capability order, {@code LUAJIT} before
     *       {@code JVM}) through the existing private
     *       constructor/validation path;</li>
     *   <li>digest-recomputing — {@code capabilityRegistryHash()} is the
     *       SHA-256 canonical JSON digest over the result's own entries,
     *       the existing derivation, never hand-rolled;</li>
     *   <li>no-op idempotent — {@code r.withState(c, t, r.state(c, t))}
     *       yields byte-identical entries and an equal digest;</li>
     *   <li>free of gate policy — this is a pure state derivation;
     *       promotion-gate enforcement lives in ISSUE-0241's
     *       release-action/flip unit, which composes this surface.</li>
     * </ol>
     *
     * @param capability the closed capability; non-null
     * @param target     the closed target; non-null
     * @param state      the requested entry state; non-null
     * @return a new immutable registry carrying {@code state} on the
     *         single {@code (capability × target)} entry
     */
    public CapabilityRegistry withState(SemanticCapability capability,
                                        Target target, State state) {
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(state, "state must not be null");
        List<Entry> updated = new ArrayList<>(ENTRY_COUNT);
        for (Entry entry : entries) {
            updated.add(entry.capability() == capability && entry.target() == target
                ? new Entry(capability, target, state)
                : entry);
        }
        return new CapabilityRegistry(updated);
    }

    /**
     * The registry's canonical JSON value, serialized through the single
     * {@link CanonicalJson} facility — {@code {version: 1, entries:
     * [{capability, target, state}]}} with the pinned ordering.
     *
     * @return the canonical JSON value
     */
    public CanonicalJson.Value canonicalJson() {
        return canonicalJson;
    }

    /**
     * The exact canonical JSON digest: {@code SHA-256(canonical JSON
     * {version: 1, entries: [{capability, target, state}]})} — the
     * invocation's {@code capabilityRegistryHash} (F1/F7), byte-identical
     * across builds and recomputed over the instance's own entries.
     *
     * @return the lowercase 64-character hex digest
     */
    public String capabilityRegistryHash() {
        return capabilityRegistryHash;
    }
}
