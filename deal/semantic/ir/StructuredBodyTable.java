package deal.semantic.ir;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record StructuredBodyTable(
    Map<BlockId, List<OpId>> blockOps,
    Map<OpId, BlockId> opBlocks
) {

    public StructuredBodyTable {
        Objects.requireNonNull(blockOps, "blockOps must not be null");
        Objects.requireNonNull(opBlocks, "opBlocks must not be null");
        Map<BlockId, List<OpId>> orderedOps = new LinkedHashMap<>();
        for (Map.Entry<BlockId, List<OpId>> entry : blockOps.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "blockOps keys must not be null");
            orderedOps.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        blockOps = Collections.unmodifiableMap(orderedOps);
        Map<OpId, BlockId> inverse = new LinkedHashMap<>();
        for (Map.Entry<OpId, BlockId> entry : opBlocks.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "opBlocks keys must not be null");
            Objects.requireNonNull(entry.getValue(), "opBlocks values must not be null");
            inverse.put(entry.getKey(), entry.getValue());
        }
        opBlocks = Collections.unmodifiableMap(inverse);
    }
}
