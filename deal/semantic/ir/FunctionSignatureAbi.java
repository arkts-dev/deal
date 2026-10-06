package deal.semantic.ir;

import java.util.Objects;

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
     */
    public CanonicalJson.Value toCanonicalJson() {
        return CanonicalJson.obj(
            CanonicalJson.e("signature", CanonicalJson.str(signature)),
            CanonicalJson.e("wrapperEntry", CanonicalJson.str(wrapperEntry)));
    }
}
