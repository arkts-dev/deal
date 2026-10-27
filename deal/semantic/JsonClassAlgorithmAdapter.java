package deal.semantic;

import deal.semantic.ir.*;
import java.util.*;

/** Production JSON algorithms over the shared class value view. */
public final class JsonClassAlgorithmAdapter {
    private JsonClassAlgorithmAdapter() {}

    public static ClassOpsExecutor.JsonParser parser() {
        return JsonClassAlgorithmAdapter::parse;
    }

    public static ClassOpsExecutor.JsonStringifier stringifier() {
        return stringifier(Map.of(ClassId.ERROR, ClassLayout.BUILTIN_ERROR));
    }

    public static ClassOpsExecutor.JsonStringifier stringifier(Map<ClassId, ClassLayout> layouts) {
        return (value, prefix, entered) -> stringify(value, prefix, entered, layouts);
    }

    private static final SourceOrigin SEAM_ORIGIN = new SourceOrigin(
        "deal.semantic.JsonClassAlgorithmAdapter",
        new SourceSpan("deal.semantic.JsonClassAlgorithmAdapter", 1, 1, 1, 1, 0, 0),
        SourceOriginKind.SYNTHETIC, new AnchorId(0), null);

    private static ClassOpsExecutor.JsonParse parse(UnicodeScalars.Valid text) {
        var outcome = SharedStdlibSemantics.jsonParse(SEAM_ORIGIN, text);
        if (outcome instanceof SharedStdlibSemantics.Outcome.Failure<?>)
            return new ClassOpsExecutor.JsonParse.SyntaxFailure();
        return new ClassOpsExecutor.JsonParse.Success(fromStdlib(
            ((SharedStdlibSemantics.Outcome.Success<SharedStdlibSemantics.Value>) outcome).value()));
    }

    private static ClassOpsExecutor.Value fromStdlib(SharedStdlibSemantics.Value value) {
        return switch (value) {
            case SharedStdlibSemantics.Value.Undefined ignored -> ClassOpsExecutor.Value.Undefined.INSTANCE;
            case SharedStdlibSemantics.Value.Null ignored -> ClassOpsExecutor.Value.Null.INSTANCE;
            case SharedStdlibSemantics.Value.Bool b -> new ClassOpsExecutor.Value.Bool(b.value());
            case SharedStdlibSemantics.Value.Number n -> new ClassOpsExecutor.Value.Number(n.value());
            case SharedStdlibSemantics.Value.String s -> new ClassOpsExecutor.Value.String(s.scalar());
            case SharedStdlibSemantics.Value.Table t -> {
                SemanticTable<ClassOpsExecutor.Value> result = new SemanticTable<>();
                for (String key : t.table().keys()) {
                    var present = (SemanticTable.Lookup.Present<SharedStdlibSemantics.Value>) t.table().get(key);
                    result.put(key, fromStdlib(present.value()));
                }
                yield new ClassOpsExecutor.Value.Table(result);
            }
            case SharedStdlibSemantics.Value.Array a -> new ClassOpsExecutor.Value.Array(
                SemanticArray.of(a.elements().elements().stream().map(JsonClassAlgorithmAdapter::fromStdlib).toList()));
            case SharedStdlibSemantics.Value.Other other -> throw new IllegalStateException("Unsupported parsed value " + other);
        };
    }

    private record Step(ClassOpsExecutor.Value value, String path, String text,
                        Object leave, boolean key) {}
    private record Member(String key, ClassOpsExecutor.Value value) {}

    private static boolean omitted(ClassOpsExecutor.Value value) {
        return value instanceof ClassOpsExecutor.Value.Undefined
            || value instanceof ClassOpsExecutor.Value.Missing
            || value instanceof ClassOpsExecutor.Value.Function;
    }

    /** Stack-safe walk; active identities belong to the whole enclosing class walk. */
    private static ClassOpsExecutor.JsonStringify stringify(ClassOpsExecutor.Value root,
            String prefix, Set<Object> entered, Map<ClassId, ClassLayout> layouts) {
        Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());
        active.addAll(entered);
        Deque<Step> work = new ArrayDeque<>();
        work.push(new Step(root, prefix, null, null, false));
        StringBuilder out = new StringBuilder();
        while (!work.isEmpty()) {
            Step step = work.pop();
            if (step.leave() != null) { active.remove(step.leave()); out.append(step.text()); continue; }
            if (step.text() != null) {
                if (step.key()) {
                    if (!(UnicodeScalars.validate(step.text()) instanceof UnicodeScalars.Valid))
                        return failure(step.path(), ActualKind.INVALID_UNICODE);
                    out.append(quote(step.text())).append(':');
                } else out.append(step.text());
                continue;
            }
            var value = step.value();
            if (omitted(value) || value instanceof ClassOpsExecutor.Value.Null) { out.append("null"); continue; }
            if (value instanceof ClassOpsExecutor.Value.Bool b) { out.append(b.value()); continue; }
            if (value instanceof ClassOpsExecutor.Value.Number n) {
                out.append(!Double.isFinite(n.value()) ? "null" : n.value() == 0 ? "0" : Double.toString(n.value()));
                continue;
            }
            if (value instanceof ClassOpsExecutor.Value.String s) {
                if (!(s.scalar() instanceof UnicodeScalars.Valid valid)) return failure(step.path(), ActualKind.INVALID_UNICODE);
                out.append(quote(valid.carrier())); continue;
            }
            Object identity = switch (value) {
                case ClassOpsExecutor.Value.Table t -> t.table();
                case ClassOpsExecutor.Value.Array a -> a.array();
                default -> value;
            };
            if (!active.add(identity)) return new ClassOpsExecutor.JsonStringify.Failure(step.path(), "table", true);
            if (value instanceof ClassOpsExecutor.Value.Array a) {
                out.append('[');
                work.push(new Step(null, step.path(), "]", identity, false));
                for (int i = a.array().size() - 1; i >= 0; i--) {
                    work.push(new Step(a.array().elementAt(i), step.path() + "[" + i + "]", null, null, false));
                    if (i > 0) work.push(new Step(null, step.path(), ",", null, false));
                }
                continue;
            }
            List<Member> members = new ArrayList<>();
            if (value instanceof ClassOpsExecutor.Value.Table t) {
                for (String key : SemanticTable.ownKeyOrder(t.table().keys())) {
                    var present = (SemanticTable.Lookup.Present<ClassOpsExecutor.Value>) t.table().get(key);
                    if (!omitted(present.value())) members.add(new Member(key, present.value()));
                }
            } else if (value instanceof ClassOpsExecutor.Value.Class c) {
                ClassLayout layout = layouts.get(c.classId());
                if (layout == null) throw new IllegalStateException("Missing JSON class layout " + c.classId());
                for (int i = 0; i < layout.fields().size(); i++) {
                    if (c.fields().get(i) instanceof ClassOpsExecutor.FieldState.Present p && !omitted(p.value()))
                        members.add(new Member(layout.fields().get(i).name(), p.value()));
                }
            }
            out.append('{');
            work.push(new Step(null, step.path(), "}", identity, false));
            for (int i = members.size() - 1; i >= 0; i--) {
                Member member = members.get(i);
                String path = step.path().isEmpty() ? member.key() : step.path() + "." + member.key();
                work.push(new Step(member.value(), path, null, null, false));
                work.push(new Step(null, step.path(), member.key(), null, true));
                if (i > 0) work.push(new Step(null, step.path(), ",", null, false));
            }
        }
        return new ClassOpsExecutor.JsonStringify.Success((UnicodeScalars.Valid) UnicodeScalars.validate(out.toString()));
    }

    private static ClassOpsExecutor.JsonStringify failure(String path, ActualKind kind) {
        return new ClassOpsExecutor.JsonStringify.Failure(path, FailureProjections.typedBoundaryToken(kind, null), false);
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
}
