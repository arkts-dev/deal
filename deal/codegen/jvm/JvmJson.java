package deal.codegen.jvm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class JvmJson {

    private JvmJson() {
    }

    /** One field of a class plan in declaration order. */
    public static final class Field {
        public final String name;
        public final String descriptor;
        public final boolean optional;
        public final String kind;
        public final String canonicalDescriptor;
        public Field(String name, String descriptor, boolean optional, String kind) {
            this(name, descriptor, optional, kind, null);
        }
        public Field(String name, String descriptor, boolean optional, String kind, String canonicalDescriptor) {
            this.canonicalDescriptor = canonicalDescriptor;
            this.name = name;
            this.descriptor = descriptor;
            this.optional = optional;
            this.kind = kind;
        }
    }

    /** One class plan: the canonical class identity and its declaration-order fields. */
    public static final class Plan {
        public final String classIdText;
        public final Field[] fields;
        public final Supplier<JvmRuntime.ClassInstance> instanceSupplier;

        public Plan(String classIdText, Field[] fields,
                    Supplier<JvmRuntime.ClassInstance> instanceSupplier) {
            this.classIdText = classIdText;
            this.fields = fields;
            this.instanceSupplier = instanceSupplier;
        }
    }

    // =========================================================================
    // Canonical text
    // =========================================================================

    /** The RFC-8259 string body; lone UTF-16 units are escaped losslessly. */
    public static String escapeString(String text) {
        java.util.Objects.requireNonNull(text, "text");
        StringBuilder out = new StringBuilder();
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
                default -> {
                    boolean unpaired = Character.isHighSurrogate(c)
                        ? i + 1 == text.length() || !Character.isLowSurrogate(text.charAt(i + 1))
                        : Character.isLowSurrogate(c)
                            && (i == 0 || !Character.isHighSurrogate(text.charAt(i - 1)));
                    if (c < 0x20 || unpaired) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Reconstruct with the shared target walk, then materialize emitted native carriers. */
    public static Object decode(String text, String target, deal.semantic.ir.SourceOrigin origin, String originText,
                                Map<String, Plan> plans) {
        var layouts = new LinkedHashMap<deal.semantic.ir.ClassId, deal.semantic.ir.ClassLayout>();
        for (Plan plan : plans.values()) {
            var descriptor = (deal.semantic.ir.RuntimeDescriptor.Class)
                deal.semantic.ir.RuntimeDescriptor.parseCanonicalText(plan.classIdText);
            var fields = new ArrayList<deal.semantic.ir.ClassLayout.FieldLayout>();
            for (Field field : plan.fields) {
                if (field.canonicalDescriptor == null) throw new IllegalStateException("JSON parse plan lacks canonical field descriptor");
                fields.add(new deal.semantic.ir.ClassLayout.FieldLayout(field.name,
                    deal.semantic.ir.RuntimeDescriptor.parseCanonicalText(field.canonicalDescriptor),
                    !field.optional, deal.semantic.ir.ConstructionProvider.LOCAL));
            }
            layouts.put(descriptor.classId(), new deal.semantic.ir.ClassLayout(descriptor.classId(), fields));
        }
        var outcome = deal.semantic.JsonDecodingSemantics.parse(
            (deal.semantic.ir.UnicodeScalars.Valid) deal.semantic.ir.UnicodeScalars.validate(text),
            deal.semantic.ir.RuntimeDescriptor.parseCanonicalText(target), layouts, origin);
        if (outcome instanceof deal.semantic.ir.ClassOpsExecutor.Outcome.Failure<deal.semantic.ir.ClassOpsExecutor.Value> failed) {
            var failure = failed.failure().failure();
            throw new JvmRuntime.DealError(failure.code().name(), failure.message(), originText,
                failure.expected(), failure.actual(), JvmRuntime.framesText(), null);
        }
        var value = ((deal.semantic.ir.ClassOpsExecutor.Outcome.Success<deal.semantic.ir.ClassOpsExecutor.Value>) outcome).value();
        return materialize(value, plans);
    }

    private record DecodeFrame(deal.semantic.ir.ClassOpsExecutor.Value source, Object target, int index) {}

    private static Object materialize(deal.semantic.ir.ClassOpsExecutor.Value value, Map<String, Plan> plans) {
        var stack = new java.util.ArrayDeque<DecodeFrame>();
        Object root = decodeShell(value, plans);
        stack.push(new DecodeFrame(value, root, 0));
        while (!stack.isEmpty()) {
            var frame = stack.pop();
            var source = frame.source();
            deal.semantic.ir.ClassOpsExecutor.Value child;
            String key = null;
            if (source instanceof deal.semantic.ir.ClassOpsExecutor.Value.Array array) {
                if (frame.index() == array.array().size()) continue;
                child = array.array().elementAt(frame.index());
            } else if (source instanceof deal.semantic.ir.ClassOpsExecutor.Value.Table table) {
                var keys = table.table().keys();
                if (frame.index() == keys.size()) continue;
                key = keys.get(frame.index());
                child = ((deal.semantic.ir.SemanticTable.Lookup.Present<deal.semantic.ir.ClassOpsExecutor.Value>) table.table().get(key)).value();
            } else if (source instanceof deal.semantic.ir.ClassOpsExecutor.Value.Class instance) {
                if (frame.index() == instance.fields().size()) continue;
                var layout = instance.classId().equals(deal.semantic.ir.ClassId.ERROR)
                    ? List.of("code", "message") : java.util.Arrays.stream(plans.get(instance.classId().text()).fields).map(f -> f.name).toList();
                key = layout.get(frame.index());
                var field = instance.fields().get(frame.index());
                stack.push(new DecodeFrame(source, frame.target(), frame.index() + 1));
                if (field instanceof deal.semantic.ir.ClassOpsExecutor.FieldState.Missing) continue;
                child = ((deal.semantic.ir.ClassOpsExecutor.FieldState.Present) field).value();
                Object projected = decodeShell(child, plans);
                ((JvmRuntime.ClassInstance) frame.target()).write(key, projected);
                stack.push(new DecodeFrame(child, projected, 0));
                continue;
            } else continue;
            stack.push(new DecodeFrame(source, frame.target(), frame.index() + 1));
            Object projected = decodeShell(child, plans);
            if (frame.target() instanceof JvmRuntime.Array array) {
                array.elements.add(projected);
                array.length = array.elements.size();
            } else ((JvmRuntime.Table) frame.target()).write(key, projected);
            stack.push(new DecodeFrame(child, projected, 0));
        }
        return root;
    }

    private static Object decodeShell(deal.semantic.ir.ClassOpsExecutor.Value value, Map<String, Plan> plans) {
        return switch (value) {
            case deal.semantic.ir.ClassOpsExecutor.Value.Null ignored -> null;
            case deal.semantic.ir.ClassOpsExecutor.Value.Bool b -> b.value();
            case deal.semantic.ir.ClassOpsExecutor.Value.Number n -> n.value();
            case deal.semantic.ir.ClassOpsExecutor.Value.String s -> ((deal.semantic.ir.UnicodeScalars.Valid) s.scalar()).carrier();
            case deal.semantic.ir.ClassOpsExecutor.Value.Table ignored -> new JvmRuntime.Table();
            case deal.semantic.ir.ClassOpsExecutor.Value.Array source -> {
                var array = new JvmRuntime.Array(0);
                array.elementDescriptor = runtimeDescriptorText(source.elementDescriptor());
                yield array;
            }
            case deal.semantic.ir.ClassOpsExecutor.Value.Class source -> source.classId().equals(deal.semantic.ir.ClassId.ERROR)
                ? new JvmRuntime.ErrorValue("", "") : plans.get(source.classId().text()).instanceSupplier.get();
            default -> throw new IllegalStateException("non-JSON reconstructed datum");
        };
    }

    private static String runtimeDescriptorText(deal.semantic.ir.RuntimeDescriptor descriptor) {
        if (descriptor instanceof deal.semantic.ir.RuntimeDescriptor.Array array) return "array(" + runtimeDescriptorText(array.element()) + ")";
        if (descriptor instanceof deal.semantic.ir.RuntimeDescriptor.Nullable n)
            return (n.admitsNull() ? (n.admitsUndefined() ? "nullableundefinedable" : "nullable") : "undefinedable") + "(" + runtimeDescriptorText(n.inner()) + ")";
        return descriptor instanceof deal.semantic.ir.RuntimeDescriptor.Table ? "record" : descriptor.canonicalSpecText();
    }

}
