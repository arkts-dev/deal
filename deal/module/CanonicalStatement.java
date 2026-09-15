package deal.module;

import deal.diagnostics.DiagnosticRange;
import deal.semantic.ir.CanonicalJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One statement of the serializer-owned canonical statement grammar
 * (ISSUE-0542, design source
 * {@code provider-versioned-default-plans} D9):
 *
 * <pre>
 * CanonicalStatement(kind, condition?, value?, bindingMarker?,
 *   typeDescriptor?, nested?,
 *   children: ordered CanonicalStatement | CanonicalNode,
 *   sourceRange?)
 * </pre>
 *
 * <ul>
 *   <li>{@code kind} — the closed body statement set (the
 *       {@link DefaultIrNode.NodeKind} statement names:
 *       VARIABLE_DECLARATION, EXPRESSION_STATEMENT, IF, WHILE, FOR,
 *       FOR_OF, RETURN, THROW, TRY, BREAK, CONTINUE, DELETE, BLOCK,
 *       FUNCTION_DECLARATION, CLASS_DECLARATION).</li>
 *   <li>{@code condition} — the condition expression of IF/WHILE/FOR,
 *       absent elsewhere.</li>
 *   <li>{@code value} — the returned/thrown value of RETURN/THROW,
 *       absent elsewhere.</li>
 *   <li>{@code bindingMarker} — the declared binding name of
 *       VARIABLE_DECLARATION, FUNCTION_DECLARATION, CLASS_DECLARATION,
 *       and FOR_OF, absent elsewhere.</li>
 *   <li>{@code typeDescriptor} — the declared binding's E4 canonical
 *       descriptor text for an annotated VARIABLE_DECLARATION, absent
 *       for inferred declarations and everywhere else.</li>
 *   <li>{@code nested} — the inline nested content of
 *       FUNCTION_DECLARATION ({@link CanonicalNested.FunctionSemantics})
 *       and CLASS_DECLARATION ({@link CanonicalNested.PlanContent}),
 *       absent elsewhere — syntactic nesting only.</li>
 *   <li>{@code children} — the fixed-role ordered children: loop
 *       init/update, bodies, catch/finally blocks, delete targets,
 *       expression children; block children preserve source order and
 *       expression children preserve evaluation order.</li>
 *   <li>{@code sourceRange} — reserved for imported-resource reference
 *       sites. Reference sites are expression nodes (calls and
 *       contextual class literals), so a statement itself never
 *       carries a range; the field exists for the closed grammar
 *       shape.</li>
 * </ul>
 */
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
