package deal.module;

import deal.diagnostics.DiagnosticRange;
import deal.semantic.ir.CanonicalJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record CanonicalNode(
    String kind,
    String operatorKind,
    String resultDescriptor,
    List<String> contextualDescriptors,
    List<CanonicalChild> children,
    CanonicalJson.Value literalValue,
    Target target,
    DiagnosticRange sourceRange
) implements CanonicalChild {

    public CanonicalNode {
        Objects.requireNonNull(kind, "kind");
        if (kind.isEmpty()) {
            throw new IllegalArgumentException("kind must not be empty");
        }
        contextualDescriptors = List.copyOf(Objects.requireNonNull(
            contextualDescriptors, "contextualDescriptors"));
        children = List.copyOf(Objects.requireNonNull(children,
            "children"));
    }

    /**
     * The resolved target of a node: a local/lexical binding marker, a
     * same-module resource identity, an intrinsic, an import-alias
     * module marker, or an imported resource.
     *
     */
    public record Target(String kind, String name,
                         String semanticResourceIdentityDigest,
                         String providerContractDigest) {

        public Target {
            Objects.requireNonNull(kind, "kind");
            if (kind.isEmpty()) {
                throw new IllegalArgumentException("kind must not be empty");
            }
            Objects.requireNonNull(name, "name");
        }

        public CanonicalJson.Value canonicalJson() {
            List<CanonicalJson.Entry> entries = new ArrayList<>();
            entries.add(CanonicalJson.e("kind", CanonicalJson.str(kind)));
            entries.add(CanonicalJson.e("name", CanonicalJson.str(name)));
            if (semanticResourceIdentityDigest != null) {
                entries.add(CanonicalJson.e(
                    "semanticResourceIdentityDigest",
                    CanonicalJson.str(semanticResourceIdentityDigest)));
            }
            if (providerContractDigest != null) {
                entries.add(CanonicalJson.e("providerContractDigest",
                    CanonicalJson.str(providerContractDigest)));
            }
            return CanonicalJson.obj(entries);
        }
    }

    /**
     * The canonical JSON value of a behavior-affecting range: positions
     * and decoded-Unicode-scalar offsets with the range origin — the
     * file path is never serialized (privacy projection: canonical
     * content that later flows into artifacts never exposes deployment
     * paths).
     */
    static CanonicalJson.Value rangeJson(DiagnosticRange range) {
        Objects.requireNonNull(range, "range");
        return CanonicalJson.obj(
            CanonicalJson.e("startLine",
                CanonicalJson.intValue(range.startLine())),
            CanonicalJson.e("startColumn",
                CanonicalJson.intValue(range.startColumn())),
            CanonicalJson.e("endLine",
                CanonicalJson.intValue(range.endLine())),
            CanonicalJson.e("endColumn",
                CanonicalJson.intValue(range.endColumn())),
            CanonicalJson.e("startScalarOffset",
                CanonicalJson.intValue(range.startScalarOffset())),
            CanonicalJson.e("endScalarOffset",
                CanonicalJson.intValue(range.endScalarOffset())),
            CanonicalJson.e("scalarLength",
                CanonicalJson.intValue(range.scalarLength())),
            CanonicalJson.e("origin",
                CanonicalJson.str(range.origin().name())));
    }

    @Override
    public CanonicalJson.Value canonicalJson() {
        List<CanonicalJson.Entry> entries = new ArrayList<>();
        entries.add(CanonicalJson.e("kind", CanonicalJson.str(kind)));
        if (operatorKind != null) {
            entries.add(CanonicalJson.e("operatorKind",
                CanonicalJson.str(operatorKind)));
        }
        if (resultDescriptor != null) {
            entries.add(CanonicalJson.e("resultDescriptor",
                CanonicalJson.str(resultDescriptor)));
        }
        List<CanonicalJson.Value> contextValues = new ArrayList<>();
        for (String descriptor : contextualDescriptors) {
            contextValues.add(CanonicalJson.str(descriptor));
        }
        entries.add(CanonicalJson.e("contextualDescriptors",
            CanonicalJson.arr(contextValues)));
        List<CanonicalJson.Value> childValues = new ArrayList<>();
        for (CanonicalChild child : children) {
            childValues.add(child.canonicalJson());
        }
        entries.add(CanonicalJson.e("children",
            CanonicalJson.arr(childValues)));
        if (literalValue != null) {
            entries.add(CanonicalJson.e("literalValue", literalValue));
        }
        if (target != null) {
            entries.add(CanonicalJson.e("target", target.canonicalJson()));
        }
        if (sourceRange != null) {
            entries.add(CanonicalJson.e("sourceRange",
                rangeJson(sourceRange)));
        }
        return CanonicalJson.obj(entries);
    }
}
