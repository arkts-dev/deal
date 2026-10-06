package deal.semantic;

import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.SemanticCapability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CapabilityRegistry {

    public enum State {

        /** Capability exists for internal shadow testing only. */
        SHADOW,

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

    public static CapabilityRegistry releaseRegistry() {
        return RELEASE_DEFAULT;
    }

    public CapabilityRegistry withState(
            SemanticCapability capability, Target target, State state) {
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(state, "state must not be null");
        List<Entry> next = new ArrayList<>(ENTRY_COUNT);
        for (Entry entry : entries) {
            if (entry.capability() == capability && entry.target() == target) {
                next.add(new Entry(capability, target, state));
            } else {
                next.add(entry);
            }
        }
        return new CapabilityRegistry(next);
    }

    /**
     * The 24 ordered entries: the S4 capability order, {@code LUAJIT}
     * before {@code JVM} within each capability. The list is immutable.
     *
     */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * The exact capability × target state lookup (F4 rule 4 consumes this
     * lookup for the target).
     *
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
     * The registry's canonical JSON value, serialized through the single
     * {@link CanonicalJson} facility — {@code {version: 1, entries:
     * [{capability, target, state}]}} with the pinned ordering.
     *
     */
    public CanonicalJson.Value canonicalJson() {
        return canonicalJson;
    }

    /**
     * The exact canonical JSON digest: {@code SHA-256(canonical JSON
     * {version: 1, entries: [{capability, target, state}]})} — the
     * invocation's {@code capabilityRegistryHash} (F1/F7), byte-identical
     * across builds.
     *
     */
    public String capabilityRegistryHash() {
        return capabilityRegistryHash;
    }
}
