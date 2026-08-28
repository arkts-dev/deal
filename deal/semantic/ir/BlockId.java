package deal.semantic.ir;

/**
 * A stable structured-block identity, globally unique within one
 * {@link ExecutableLoweredProject} (schema-referenced; parent D4/D13).
 *
 * <p>Immutable value record over a non-negative numeric id. Blocks are the
 * body units of the structured IR ({@code LoweredBody},
 * {@code BRANCH}/{@code LOOP} child blocks, adapter thunks, try/catch
 * blocks); block IDs are behavior-referenced semantic IDs and therefore
 * live inside operation-contract snapshot digests.</p>
 *
 * @param id the numeric identity; non-negative
 */
public record BlockId(long id) implements SemanticId {

    public BlockId {
        if (id < 0) {
            throw new IllegalArgumentException("id must be >= 0, got " + id);
        }
    }

    @Override
    public String toString() {
        return "BlockId(" + id + ")";
    }
}
