package deal.semantic.ir;

import java.util.List;
import java.util.Objects;

/**
 * The export plan of a {@link LoweredModuleUnit} (parent canonical
 * surfaces; {@code EXPORT_READ}/{@code EXPORT_PUBLISH} rows of the closed
 * operation table). Each entry records the published export: checked read,
 * atomic publication after the {@code MODULE_EXPORT} boundary, with the
 * declared descriptor.
 *
 * @param entries the export entries in declaration order; non-null
 */
public record ExportPlan(List<ExportPlanEntry> entries) {

    /**
     * One export entry: the export name, its declared descriptor, and the
     * producing/publishing op identity.
     */
    public record ExportPlanEntry(String name, RuntimeDescriptor descriptor, OpId publishOpId) {

        public ExportPlanEntry {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(descriptor, "descriptor must not be null");
            Objects.requireNonNull(publishOpId, "publishOpId must not be null");
        }
    }

    public ExportPlan(List<ExportPlanEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** Convenience: an empty export plan. */
    public static ExportPlan empty() {
        return new ExportPlan(List.of());
    }
}
