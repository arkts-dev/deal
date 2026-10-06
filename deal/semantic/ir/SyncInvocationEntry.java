package deal.semantic.ir;

import java.util.Objects;

public sealed interface SyncInvocationEntry
    permits SyncInvocationEntry.ExternalEntry, SyncInvocationEntry.AbiWrapper {

    /**
     * The callee unit's {@code EXTERNAL_ENTRY} op — a shared-body
     * callee invoked across the shared edge.
     *
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
     */
    CanonicalJson.Value toCanonicalJson();
}
