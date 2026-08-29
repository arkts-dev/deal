package deal.semantic.ir;

import java.util.Objects;

/**
 * The closed sync-invocation entry of a {@code TargetModuleAbi} record
 * (foundation F5; parent D1): exactly one entry per imported export —
 * either the callee unit's {@code EXTERNAL_ENTRY} op (a shared-body
 * callee, identified by its {@link OpId}) or the retained ABI wrapper
 * entry (a legacy callee, identified by the wrapper entry name).
 *
 * <p>Closed sealed family: exactly the two variants below; no other
 * entry shape exists. Emission-owned — the planner never invents
 * sync-invocation entry names (F5); the records are completed during
 * staging (ISSUE-0239) and validated by {@code TargetAbiValidator} at
 * stage time (F6). At plan time the {@code syncInvocationEntries} map of
 * a {@code TargetModuleAbi} record is absent.</p>
 */
public sealed interface SyncInvocationEntry
    permits SyncInvocationEntry.ExternalEntry, SyncInvocationEntry.AbiWrapper {

    /**
     * The callee unit's {@code EXTERNAL_ENTRY} op — a shared-body
     * callee invoked across the shared edge.
     *
     * @param externalEntryOpId the recorded {@code EXTERNAL_ENTRY} op id
     *                          of the callee unit; non-null
     */
    record ExternalEntry(OpId externalEntryOpId) implements SyncInvocationEntry {

        public ExternalEntry {
            Objects.requireNonNull(externalEntryOpId, "externalEntryOpId must not be null");
        }

        @Override
        public CanonicalJson.Value toCanonicalJson() {
            return CanonicalJson.obj(
                CanonicalJson.e("kind", CanonicalJson.str("EXTERNAL_ENTRY")),
                CanonicalJson.e("externalEntryOpId",
                    ContractSnapshotCanonicalizer.semanticIdJson(externalEntryOpId)));
        }
    }

    /**
     * The retained target ABI wrapper entry — a legacy callee invoked
     * through its retained artifact.
     *
     * @param wrapperEntry the retained ABI wrapper entry name; non-null
     */
    record AbiWrapper(String wrapperEntry) implements SyncInvocationEntry {

        public AbiWrapper {
            Objects.requireNonNull(wrapperEntry, "wrapperEntry must not be null");
        }

        @Override
        public CanonicalJson.Value toCanonicalJson() {
            return CanonicalJson.obj(
                CanonicalJson.e("kind", CanonicalJson.str("ABI_WRAPPER")),
                CanonicalJson.e("wrapperEntry", CanonicalJson.str(wrapperEntry)));
        }
    }

    /**
     * The canonical JSON object of this entry (kind-tagged, sorted keys).
     *
     * @return the canonical JSON object
     */
    CanonicalJson.Value toCanonicalJson();
}
