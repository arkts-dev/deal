package deal.semantic.ir;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record JsonDefaultChildTable(Map<OpId, List<OpId>> defaultChildren) {

    public JsonDefaultChildTable {
        Objects.requireNonNull(defaultChildren, "defaultChildren must not be null");
        Map<OpId, List<OpId>> copied = new LinkedHashMap<>();
        for (Map.Entry<OpId, List<OpId>> entry : defaultChildren.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "defaultChildren keys must not be null");
            Objects.requireNonNull(entry.getValue(),
                "defaultChildren values must not be null");
            copied.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        defaultChildren = Collections.unmodifiableMap(copied);
    }

    /**
     * The recorded per-site {@code CLASS_DEFAULT} child op ids of the
     * given {@code JSON_FROM_CLASS} op in declaration order, or
     * {@code null} when the op has no recorded children (the op is not
     * a {@code @jsonable} class's {@code JSON_FROM_CLASS} op, or the
     * class has no required-present defaulted fields).
     *
     */
    public List<OpId> childrenOf(OpId jsonFromClassOpId) {
        return defaultChildren.get(Objects.requireNonNull(jsonFromClassOpId,
            "jsonFromClassOpId must not be null"));
    }
}
