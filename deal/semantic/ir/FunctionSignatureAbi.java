package deal.semantic.ir;

import java.util.Objects;

/**
 * The emission-owned wrapper/signature ABI of one imported function
 * (foundation F5; parent D1):
 *
 * <pre>{@code FunctionSignatureAbi {wrapperEntry, signature}}</pre>
 *
 * <p>{@code wrapperEntry} names the retained/shared artifact's wrapper
 * entry; {@code signature} is the canonical function signature text of
 * the declared type. Both facts are emission-owned: the planner never
 * invents retained wrapper names or signature spellings — they do not
 * exist before emission and have no emission-independent derivation
 * (foundation F5). The records are completed during staging
 * (ISSUE-0239) and {@code TargetAbiValidator} validates them against
 * the interface index at stage time (foundation F6); at plan time the
 * {@code functionWrapperAbi} map of a {@code TargetModuleAbi} record is
 * absent.</p>
 *
 * @param wrapperEntry the artifact wrapper entry name; non-null
 * @param signature    the canonical function signature text; non-null
 */
public record FunctionSignatureAbi(String wrapperEntry, String signature) {

    public FunctionSignatureAbi {
        Objects.requireNonNull(wrapperEntry, "wrapperEntry must not be null");
        Objects.requireNonNull(signature, "signature must not be null");
    }

    /**
     * The canonical JSON object of this ABI record (sorted keys) — the
     * single mapping {@code TargetModuleAbi}'s canonical serialization
     * uses for completed records.
     *
     * @return the canonical JSON object
     */
    public CanonicalJson.Value toCanonicalJson() {
        return CanonicalJson.obj(
            CanonicalJson.e("signature", CanonicalJson.str(signature)),
            CanonicalJson.e("wrapperEntry", CanonicalJson.str(wrapperEntry)));
    }
}
