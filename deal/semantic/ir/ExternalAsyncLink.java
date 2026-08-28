package deal.semantic.ir;

import java.util.Objects;

/**
 * The external async-linkage record carried by an
 * {@code ASYNC_START(EXTERNAL)} (parent D13; canonical surfaces):
 * {@code ExternalAsyncLink{calleeModuleId, exportName, calleeTokenId}}.
 *
 * <p>The callee unit's async {@code EXTERNAL_ENTRY} creates the canonical
 * token and the body task; the caller-side token is an alias
 * ({@code AsyncLinkKind.EXTERNAL_LINK}) referencing the canonical token
 * through this record. The body task's {@code RETURN} runs exactly one
 * {@code FUNCTION_RETURN} boundary in the callee unit, the checked
 * value/error crosses the ABI unchanged, and the caller's single
 * {@code ASYNC_COMPLETION} boundary validates the crossed value at
 * {@code AWAIT}.</p>
 *
 * @param calleeModuleId the callee module identity; non-null
 * @param exportName     the invoked async export name; non-null
 * @param calleeTokenId  the callee unit's canonical token; non-null
 */
public record ExternalAsyncLink(ModuleId calleeModuleId, String exportName, AsyncTokenId calleeTokenId) {

    public ExternalAsyncLink {
        Objects.requireNonNull(calleeModuleId, "calleeModuleId must not be null");
        Objects.requireNonNull(exportName, "exportName must not be null");
        Objects.requireNonNull(calleeTokenId, "calleeTokenId must not be null");
    }
}
