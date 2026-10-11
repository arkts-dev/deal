package deal.semantic.ir;

import java.util.List;
import java.util.Objects;

/**
 * One lowered function body: the function identity, its declared
 * signature, the ordered generation-pinned captures, the body block, and
 * the function's original source name.
 *
 * <p>{@code sourceName} is the DEAL declaration name of the lowered
 * function (the module-level, nested, group-member, or {@code @jsonable}
 * helper name); it is {@code null} for a name-less function expression.
 * The name is the source half of a stack frame entry: the generated half
 * is the target's emitted function name ({@code F<functionId>}), so a
 * host-visible error frame names both the original source function and
 * the generated function ({@link FrameLabels}). The component is pure
 * copied data: it takes no part in the canonical operation snapshot, in
 * validation, or in any digest.</p>
 *
 */
public record LoweredFunction(
    FunctionId functionId,
    RuntimeDescriptor.Func descriptor,
    List<BindingGeneration> captures,
    BlockId body,
    String sourceName
) {

    public LoweredFunction {
        functionId = Objects.requireNonNull(functionId, "functionId must not be null");
        descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        captures = List.copyOf(captures);
        body = Objects.requireNonNull(body, "body must not be null");
    }

    /**
     * The name-less convenience constructor (synthetic units and the
     * pre-name call sites): the function carries no source name, so its
     * frame entry is the anonymous form.
     */
    public LoweredFunction(FunctionId functionId, RuntimeDescriptor.Func descriptor,
                           List<BindingGeneration> captures, BlockId body) {
        this(functionId, descriptor, captures, body, null);
    }

    /** The captured binding identities in capture order (the op payload's keys). */
    public List<BindingId> captureBindings() {
        return captures.stream().map(BindingGeneration::binding).toList();
    }

    /** The generation-pinned capture of one binding, or {@code null}. */
    public BindingGeneration captureOf(BindingId binding) {
        for (BindingGeneration capture : captures) {
            if (capture.binding().equals(binding)) {
                return capture;
            }
        }
        return null;
    }
}
