package deal.semantic;

import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.SemanticCapability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The closed capability × target registry (foundation F7) retained as an
 * executable consistency mechanism: the ordered cross product of the
 * closed {@link SemanticCapability} set with the {@link Target} set and
 * its deterministic canonical-JSON digest. The registry records which
 * capabilities exist for which targets — it selects no semantics, no
 * promotion and no compatibility arm; the sole
 * {@code DEAL_V1_5_BINARY64} profile executes every capability.
 */
public final class CapabilityRegistry {

    /**
     * One closed cross-product entry: {@code {capability, target}}. Exactly
     * these two components — no consumer or state axis exists.
     */
    public record Entry(SemanticCapability capability, Target target) {

        public Entry {
            Objects.requireNonNull(capability, "capability must not be null");
            Objects.requireNonNull(target, "target must not be null");
        }
    }

    /** The pinned canonical JSON shape version of the registry. */
    public static final int VERSION = 1;

    /** The entry count of the closed cross product: capabilities × targets. */
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

    /** Builds the release-owned default: the full cross product. */
    private static CapabilityRegistry buildReleaseDefault() {
        List<Entry> entries = new ArrayList<>(ENTRY_COUNT);
        for (SemanticCapability capability : SemanticCapability.values()) {
            for (Target target : Target.values()) {
                entries.add(new Entry(capability, target));
            }
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
                CanonicalJson.e("target", CanonicalJson.str(entry.target().name()))));
        }
        return CanonicalJson.obj(
            CanonicalJson.e("entries", CanonicalJson.arr(entryValues)),
            CanonicalJson.e("version", CanonicalJson.intValue(VERSION)));
    }

    public static CapabilityRegistry releaseRegistry() {
        return RELEASE_DEFAULT;
    }

    /**
     * The ordered entries: the S4 capability order, {@code LUAJIT}
     * before {@code JVM} within each capability. The list is immutable.
     *
     */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * The registry's canonical JSON value, serialized through the single
     * {@link CanonicalJson} facility — {@code {version: 1, entries:
     * [{capability, target}]}} with the pinned ordering.
     *
     */
    public CanonicalJson.Value canonicalJson() {
        return canonicalJson;
    }

    /**
     * The exact canonical JSON digest: {@code SHA-256(canonical JSON
     * {version: 1, entries: [{capability, target}]})} — the invocation's
     * {@code capabilityRegistryHash} (F1/F7), byte-identical across builds.
     *
     */
    public String capabilityRegistryHash() {
        return capabilityRegistryHash;
    }
}
