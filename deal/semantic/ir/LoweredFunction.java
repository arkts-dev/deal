package deal.semantic.ir;

import java.util.List;
import java.util.Objects;

/**
 * A lowered function record of a {@link LoweredModuleUnit} (parent source-
 * construct coverage: "function declaration/expression covers
 * {@code CLOSURE_NEW}/{@code RECURSIVE_GROUP_INIT} and a
 * {@code LoweredFunction}"). The unit's {@code functions} map records one
 * entry per lowered function; execution semantics of the body are owned by
 * the construct epics (ISSUE-0234..0239) — this is the immutable schema
 * shape only.
 *
 * @param functionId the function identity; non-null
 * @param descriptor the exact function signature descriptor; non-null
 * @param captures   the ordered captured binding identities (closures
 *                   capture bindings, not values); non-null
 * @param body       the body block identity; non-null
 */
public record LoweredFunction(
    FunctionId functionId,
    RuntimeDescriptor.Func descriptor,
    List<BindingId> captures,
    BlockId body
) {

    public LoweredFunction(FunctionId functionId, RuntimeDescriptor.Func descriptor,
                           List<BindingId> captures, BlockId body) {
        this.functionId = Objects.requireNonNull(functionId, "functionId must not be null");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        this.captures = List.copyOf(captures);
        this.body = Objects.requireNonNull(body, "body must not be null");
    }
}
