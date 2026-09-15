package deal.module;

import deal.diagnostics.DiagnosticRange;
import deal.semantic.ir.CanonicalJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One node of the serializer-owned canonical expression grammar
 * (ISSUE-0542, design source
 * {@code provider-versioned-default-plans} D6):
 *
 * <pre>
 * CanonicalNode(kind, operatorKind?, resultDescriptor,
 *   contextualDescriptors: ordered, children: ordered,
 *   literalValue?, target?, sourceRange?)
 * </pre>
 *
 * <ul>
 *   <li>{@code kind} — the node kind over the complete checked
 *       expression construct set (the {@link DefaultIrNode.NodeKind}
 *       name).</li>
 *   <li>{@code operatorKind} — the binary/unary operator name for
 *       {@code BINARY}/{@code UNARY} nodes, absent elsewhere.</li>
 *   <li>{@code resultDescriptor} — the E4 canonical descriptor text of
 *       the node's resolved type, absent for statement-position nodes
 *       (statements carry no type).</li>
 *   <li>{@code contextualDescriptors} — the ordered expected-type
 *       contexts (class-literal context, conversion context), never
 *       null, possibly empty.</li>
 *   <li>{@code children} — the ordered children: evaluation order for
 *       expression children; a {@code FUNCTION_EXPRESSION} node's
 *       children are its body's {@link CanonicalStatement}s (D6:
 *       function-expression bodies serialize through the statement
 *       grammar).</li>
 *   <li>{@code literalValue} — the exact lossless scalar of
 *       {@code LITERAL} nodes (canonical JSON value: signed-int32
 *       decimal, unique IEEE-754 hex float, full Unicode scalar string,
 *       boolean, null), absent elsewhere; never folded, truncated, or
 *       reformatted.</li>
 *   <li>{@code target} — the resolved target of identifier, member
 *       access, call, and contextual class-literal nodes
 *       ({@link Target}), absent when the node has no named
 *       target.</li>
 *   <li>{@code sourceRange} — present exactly when the range is
 *       behavior-affecting: the default expression range (the E3001
 *       anchor, on the root) and each imported-resource reference
 *       range (the planner occurrence range, on the reference node).
 *       All other nodes carry no range.</li>
 * </ul>
 *
 * <p><b>Privacy projection (pinned here):</b> resource targets embed
 * the private semantic identity only through its deterministic
 * {@code semanticResourceIdentityDigest} (SHA-256 over the canonical
 * length-separated identity components) — never the raw URI — and
 * imported resources embed {@code providerContractDigest}. Ranges
 * serialize positions and scalar offsets without the file path, so
 * canonical content that later flows into artifacts never exposes
 * deployment paths.</p>
 */
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
     * @param kind                          the closed target kind (the
     *                                      {@link DefaultIrNode.TargetKind}
     *                                      name)
     * @param name                          the binding or resource name
     * @param semanticResourceIdentityDigest the deterministic digest of
     *                                      the resource's private
     *                                      semantic identity, present
     *                                      exactly on resource targets
     *                                      (never the raw identity)
     * @param providerContractDigest        the provider's contract
     *                                      digest, present exactly on
     *                                      imported resources whose
     *                                      provider has canonical
     *                                      contract content; absent for
     *                                      host-declared imported
     *                                      classes (no plan exists) and
     *                                      for identity-only targets —
     *                                      never a placeholder
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
