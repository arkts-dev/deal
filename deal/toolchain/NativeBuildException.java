package deal.toolchain;

import java.util.Objects;

/**
 * The one native build failure ({@code llvm-native-backend-integration}
 * {@code NativeBuild}, {@code ToolchainPreflight}).
 *
 * <p>Every failed preflight condition, every toolchain invocation fault and the
 * link failure itself raise exactly this checked exception, so one authority
 * owns the diagnostic spellings: the {@link Kind}'s fixed prefix, the condition
 * detail, and the caller's single reporting path. No second spelling exists for
 * a condition the seam already classified.</p>
 *
 * <p>The failure never mentions the fixed {@code .partial} suffix
 * ({@code llvm-native-backend-integration} D4): the suffix is reused on repeated
 * builds and never appears in diagnostics.</p>
 */
public final class NativeBuildException extends Exception {

    private static final long serialVersionUID = 1L;

    /** The closed named failure kinds, each with its one pinned prefix. */
    public enum Kind {
        /** No accepted {@code clang} spelling resolves on {@code PATH}. */
        TOOLCHAIN_MISSING("deal: native toolchain missing: "),
        /** A resolved compiler cannot state a version major. */
        TOOLCHAIN_UNVERSIONED("deal: native toolchain unversioned: "),
        /** The resolved compiler's major is outside the accepted 14-16 window. */
        TOOLCHAIN_UNSUPPORTED("deal: native toolchain unsupported: "),
        /** A published IR module the toolchain cannot parse. */
        IR_UNPARSABLE("deal: native IR unparsable: "),
        /** A deployment file of the closed D6 runtime set is absent. */
        RUNTIME_MISSING("deal: native runtime missing: "),
        /** A supplied host unit's stem matches no published host declaration. */
        HOST_UNIT_UNMATCHED("deal: native host unit unmatched: "),
        /** Two supplied host units share one stem. */
        HOST_UNIT_DUPLICATE("deal: native host unit duplicate: "),
        /** Two published host declaration headers share one stem. */
        HOST_DECLARATION_AMBIGUOUS("deal: native host declaration ambiguous: "),
        /** The caller-owned product path resolves inside the publication root. */
        PRODUCT_INSIDE_PUBLICATION("deal: native product inside publication: "),
        /** The publication root does not carry the build's input set. */
        BUILD_INPUTS_INCOMPLETE("deal: native build inputs incomplete: "),
        /** The pinned compiler rejected the build (first diagnostic carried). */
        LINK_FAILED("deal: native link failed: "),
        /** Spawning, waiting for or reaping the pinned compiler failed. */
        TOOL_FAILURE("deal: native tool failure: "),
        /** A filesystem step of the build failed. */
        IO_FAILURE("deal: native build I/O failed: ");

        private final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }

        /** The kind's fixed diagnostic prefix. */
        public String prefix() {
            return prefix;
        }
    }

    private final Kind kind;
    private final String detail;

    /**
     * @param kind the named condition; non-null
     * @param detail the condition's own detail text; non-null and non-blank
     */
    public NativeBuildException(Kind kind, String detail) {
        super(Objects.requireNonNull(kind, "kind must not be null").prefix()
            + Objects.requireNonNull(detail, "detail must not be null"));
        this.kind = kind;
        this.detail = detail;
        if (detail.isBlank()) {
            throw new IllegalArgumentException("a native build failure names its"
                + " condition detail");
        }
    }

    /** The named condition. */
    public Kind kind() {
        return kind;
    }

    /** The condition's own detail text, without the kind's prefix. */
    public String detail() {
        return detail;
    }

    /** The one rendered diagnostic line of this failure. */
    public String diagnostic() {
        return getMessage();
    }
}
