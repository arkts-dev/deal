package deal.module;

import deal.identity.CanonicalClassIdentity;

import java.util.List;
import java.util.Objects;

public record RuntimeClassDefaultPlan(
    CanonicalClassIdentity classIdentity,
    List<RuntimeClassDefaultEntry> orderedFields
) {

    public RuntimeClassDefaultPlan {
        Objects.requireNonNull(classIdentity, "classIdentity");
        orderedFields =
            CarrierCollections.orderedListCopy(orderedFields, "orderedFields");
        CarrierCollections.rejectDuplicateNames(
            orderedFields.stream().map(RuntimeClassDefaultEntry::name).toList());
    }
}
