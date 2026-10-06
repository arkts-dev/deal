package deal.module;

import deal.diagnostics.DiagnosticRange;
import deal.semantic.ir.CanonicalJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record CanonicalStatement(
    String kind,
    CanonicalNode condition,
    CanonicalNode value,
    String bindingMarker,
    String typeDescriptor,
    CanonicalNested nested,
    List<CanonicalChild> children,
    DiagnosticRange sourceRange
) implements CanonicalChild {

    public CanonicalStatement {
        Objects.requireNonNull(kind, "kind");
        if (kind.isEmpty()) {
            throw new IllegalArgumentException("kind must not be empty");
        }
        children = List.copyOf(Objects.requireNonNull(children,
            "children"));
    }

    @Override
    public CanonicalJson.Value canonicalJson() {
        List<CanonicalJson.Entry> entries = new ArrayList<>();
        entries.add(CanonicalJson.e("kind", CanonicalJson.str(kind)));
        if (condition != null) {
            entries.add(CanonicalJson.e("condition",
                condition.canonicalJson()));
        }
        if (value != null) {
            entries.add(CanonicalJson.e("value", value.canonicalJson()));
        }
        if (bindingMarker != null) {
            entries.add(CanonicalJson.e("bindingMarker",
                CanonicalJson.str(bindingMarker)));
        }
        if (typeDescriptor != null) {
            entries.add(CanonicalJson.e("typeDescriptor",
                CanonicalJson.str(typeDescriptor)));
        }
        if (nested != null) {
            entries.add(CanonicalJson.e("nested", nested.canonicalJson()));
        }
        List<CanonicalJson.Value> childValues = new ArrayList<>();
        for (CanonicalChild child : children) {
            childValues.add(child.canonicalJson());
        }
        entries.add(CanonicalJson.e("children",
            CanonicalJson.arr(childValues)));
        if (sourceRange != null) {
            entries.add(CanonicalJson.e("sourceRange",
                CanonicalNode.rangeJson(sourceRange)));
        }
        return CanonicalJson.obj(entries);
    }
}
