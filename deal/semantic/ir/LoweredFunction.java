package deal.semantic.ir;

import java.util.List;
import java.util.Objects;

public record LoweredFunction(
    FunctionId functionId,
    RuntimeDescriptor.Func descriptor,
    List<BindingGeneration> captures,
    BlockId body
) {

    public LoweredFunction(FunctionId functionId, RuntimeDescriptor.Func descriptor,
                           List<BindingGeneration> captures, BlockId body) {
        this.functionId = Objects.requireNonNull(functionId, "functionId must not be null");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        this.captures = List.copyOf(captures);
        this.body = Objects.requireNonNull(body, "body must not be null");
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
