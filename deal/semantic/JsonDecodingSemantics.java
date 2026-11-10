package deal.semantic;

import deal.semantic.ir.*;
import java.util.*;

/**
 * Target-directed JSON reconstruction, separate from descriptor assertions and
 * from the retired null-returning class conversion. Class references are edges
 * in a finite layout graph, not recursively expanded schemas. The walk keeps
 * unpublished intermediates on an explicit stack and never invokes factories.
 */
public final class JsonDecodingSemantics {
    private JsonDecodingSemantics() {}

    /**
     * Checks the complete reachable schema, including absent optional fields.
     * Unknown and Record are data positions, not hidden structural schemas.
     * A missing class layout is a producer defect, not a document mismatch.
     */
    public static void requireSchema(RuntimeDescriptor target, Map<ClassId, ClassLayout> layouts) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(layouts, "layouts");
        Deque<RuntimeDescriptor> pending = new ArrayDeque<>();
        Set<ClassId> visited = new HashSet<>();
        pending.push(target);
        while (!pending.isEmpty()) {
            RuntimeDescriptor next = pending.pop();
            switch (next) {
                case RuntimeDescriptor.Func ignored ->
                    throw new IllegalArgumentException("JSON decoding target contains a function type");
                case RuntimeDescriptor.Void ignored ->
                    throw new IllegalArgumentException("JSON decoding target is not a data type");
                case RuntimeDescriptor.Nullable nullable -> pending.push(nullable.inner());
                case RuntimeDescriptor.Array array -> pending.push(array.element());
                case RuntimeDescriptor.Class cls -> {
                    if (visited.add(cls.classId())) {
                        ClassLayout layout = layoutOf(cls.classId(), layouts);
                        for (int i = layout.fields().size() - 1; i >= 0; --i)
                            pending.push(layout.fields().get(i).descriptor());
                    }
                }
                default -> { /* Scalar, unknown and Record leaves. */ }
            }
        }
    }

    /** Strict syntax scanning owns its original byte offset and call origin. */
    public static ClassOpsExecutor.Outcome<ClassOpsExecutor.Value> parse(
            UnicodeScalars.Valid text, RuntimeDescriptor target,
            Map<ClassId, ClassLayout> layouts, SourceOrigin origin) {
        requireSchema(target, layouts);
        var parsed = SharedStdlibSemantics.jsonParse(origin, text);
        if (parsed instanceof SharedStdlibSemantics.Outcome.Failure<SharedStdlibSemantics.Value> failure)
            return new ClassOpsExecutor.Outcome.Failure<>(new ClassOpsExecutor.OpFailure(
                failure.failure().failure(), failure.failure().origin()));
        return decode(((SharedStdlibSemantics.Outcome.Success<SharedStdlibSemantics.Value>) parsed).value(),
            target, layouts, origin);
    }

    private record Child(SharedStdlibSemantics.Value raw, RuntimeDescriptor target) {}

    private static final class Frame {
        final SharedStdlibSemantics.Value raw;
        final RuntimeDescriptor target;
        final List<Child> children = new ArrayList<>();
        final List<ClassOpsExecutor.Value> values = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        ClassLayout layout;
        int index;

        Frame(SharedStdlibSemantics.Value raw, RuntimeDescriptor target) {
            this.raw = raw;
            this.target = target;
        }
    }

    /**
     * Rebuilds a parsed document without coercion. Fields decode in declaration
     * order; extra keys reject in document order before field consumption, as
     * ordinary class construction does. A required field must be present even
     * when its descriptor admits null or undefined.
     */
    public static ClassOpsExecutor.Outcome<ClassOpsExecutor.Value> decode(
            SharedStdlibSemantics.Value raw, RuntimeDescriptor target,
            Map<ClassId, ClassLayout> layouts, SourceOrigin origin) {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(origin, "origin");
        requireSchema(target, layouts);
        Deque<Frame> stack = new ArrayDeque<>();
        Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());
        stack.push(new Frame(raw, target));
        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            if (frame.index == 0 && frame.values.isEmpty()) {
                Object identity = containerIdentity(frame.raw);
                if (identity != null && !active.add(identity))
                    throw new IllegalArgumentException("JSON decoding requires acyclic parsed JSON data");
                BoundaryFailure failure = open(frame, layouts);
                if (failure != null)
                    return new ClassOpsExecutor.Outcome.Failure<>(new ClassOpsExecutor.OpFailure(failure, origin));
            }
            if (frame.index < frame.children.size()) {
                Child child = frame.children.get(frame.index++);
                stack.push(new Frame(child.raw(), child.target()));
                continue;
            }
            ClassOpsExecutor.Value completed = finish(frame);
            Object identity = containerIdentity(frame.raw);
            if (identity != null) active.remove(identity);
            stack.pop();
            if (stack.isEmpty()) return new ClassOpsExecutor.Outcome.Success<>(completed);
            stack.peek().values.add(completed);
        }
        throw new IllegalStateException("JSON decoding walk without a result");
    }

    private static Object containerIdentity(SharedStdlibSemantics.Value value) {
        return switch (value) {
            case SharedStdlibSemantics.Value.Table table -> table.table();
            case SharedStdlibSemantics.Value.Array array -> array.elements();
            case null, default -> null;
        };
    }

    private static ClassLayout layoutOf(ClassId id, Map<ClassId, ClassLayout> layouts) {
        ClassLayout layout = id.equals(ClassId.ERROR) ? ClassLayout.BUILTIN_ERROR : layouts.get(id);
        if (layout == null || !layout.classId().equals(id))
            throw new IllegalArgumentException("JSON decoding target has no exact class layout: " + id);
        Set<String> names = new HashSet<>();
        for (var field : layout.fields())
            if (!names.add(field.name()))
                throw new IllegalArgumentException("JSON decoding layout has duplicate field: " + field.name());
        return layout;
    }

    private static RuntimeDescriptor base(RuntimeDescriptor descriptor) {
        return descriptor instanceof RuntimeDescriptor.Nullable nullable ? nullable.inner() : descriptor;
    }

    private static BoundaryFailure mismatch(RuntimeDescriptor descriptor, ActualKind actual) {
        return FailureContractRegistry.render(FailureArmId.TYPED_BOUNDARY_KIND,
            Map.of("kind", FailureProjections.kindText(descriptor)),
            FailureProjections.kindToken(descriptor), FailureProjections.typedBoundaryToken(actual, null), null);
    }

    private static BoundaryFailure open(Frame frame, Map<ClassId, ClassLayout> layouts) {
        var raw = frame.raw;
        RuntimeDescriptor target = base(frame.target);
        if (raw == null) return mismatch(target, ActualKind.MISSING);
        if (raw instanceof SharedStdlibSemantics.Value.Null
                && (target instanceof RuntimeDescriptor.Unknown || target instanceof RuntimeDescriptor.Null
                    || frame.target instanceof RuntimeDescriptor.Nullable nullable && nullable.admitsNull()))
            return null;
        if (target instanceof RuntimeDescriptor.Class cls) {
            if (!(raw instanceof SharedStdlibSemantics.Value.Table table)) return mismatch(target, raw.actualKind());
            frame.layout = layoutOf(cls.classId(), layouts);
            Set<String> declared = new HashSet<>();
            for (var field : frame.layout.fields()) declared.add(field.name());
            for (String key : table.table().keys()) {
                if (!declared.contains(key))
                    return FailureContractRegistry.render(FailureArmId.CLASS_EXTRA_FIELD,
                        Map.of("field", key, "classId", cls.classId().text()), null, null, null);
            }
            for (var field : frame.layout.fields()) {
                var found = table.table().get(field.name());
                if (found instanceof SemanticTable.Lookup.Present<SharedStdlibSemantics.Value> present) {
                    frame.keys.add(field.name());
                    frame.children.add(new Child(present.value(), field.descriptor()));
                } else if (field.required()) {
                    // Defer missing-field failure to this declaration-order
                    // child, after all earlier provided fields have decoded.
                    frame.keys.add(field.name());
                    frame.children.add(new Child(null, field.descriptor()));
                }
            }
            return null;
        }
        if (raw instanceof SharedStdlibSemantics.Value.Table table
                && (target instanceof RuntimeDescriptor.Unknown || target instanceof RuntimeDescriptor.Table)) {
            for (String key : table.table().keys()) {
                frame.keys.add(key);
                var found = (SemanticTable.Lookup.Present<SharedStdlibSemantics.Value>) table.table().get(key);
                frame.children.add(new Child(found.value(), RuntimeDescriptor.Unknown.INSTANCE));
            }
            return null;
        }
        if (raw instanceof SharedStdlibSemantics.Value.Array array
                && (target instanceof RuntimeDescriptor.Unknown || target instanceof RuntimeDescriptor.Array)) {
            RuntimeDescriptor element = target instanceof RuntimeDescriptor.Array typed
                ? typed.element() : RuntimeDescriptor.Unknown.INSTANCE;
            for (var value : array.elements().elements()) frame.children.add(new Child(value, element));
            return null;
        }
        boolean matches = target instanceof RuntimeDescriptor.Unknown
            || target instanceof RuntimeDescriptor.Boolean && raw instanceof SharedStdlibSemantics.Value.Bool
            || target instanceof RuntimeDescriptor.Number && raw instanceof SharedStdlibSemantics.Value.Number
            || target instanceof RuntimeDescriptor.String && raw instanceof SharedStdlibSemantics.Value.String;
        // These cannot be produced by the strict JSON scanner, even at unknown.
        if (raw instanceof SharedStdlibSemantics.Value.Undefined || raw instanceof SharedStdlibSemantics.Value.Other)
            throw new IllegalArgumentException("JSON decoding requires parsed JSON data");
        return matches ? null : mismatch(target, raw.actualKind());
    }

    private static ClassOpsExecutor.Value finish(Frame frame) {
        if (frame.raw instanceof SharedStdlibSemantics.Value.Null) return ClassOpsExecutor.Value.Null.INSTANCE;
        if (frame.layout != null) {
            Map<String, ClassOpsExecutor.Value> fields = new HashMap<>();
            for (int i = 0; i < frame.keys.size(); ++i) fields.put(frame.keys.get(i), frame.values.get(i));
            List<ClassOpsExecutor.FieldState> states = new ArrayList<>();
            for (var field : frame.layout.fields()) {
                var value = fields.get(field.name());
                states.add(value == null ? ClassOpsExecutor.FieldState.Missing.INSTANCE
                    : new ClassOpsExecutor.FieldState.Present(value));
            }
            return new ClassOpsExecutor.Value.Class(frame.layout.classId(), states);
        }
        return switch (frame.raw) {
            case SharedStdlibSemantics.Value.Null ignored -> ClassOpsExecutor.Value.Null.INSTANCE;
            case SharedStdlibSemantics.Value.Bool bool -> new ClassOpsExecutor.Value.Bool(bool.value());
            case SharedStdlibSemantics.Value.Number number -> new ClassOpsExecutor.Value.Number(number.value());
            case SharedStdlibSemantics.Value.String string -> new ClassOpsExecutor.Value.String(string.scalar());
            case SharedStdlibSemantics.Value.Table ignored -> {
                SemanticTable<ClassOpsExecutor.Value> table = new SemanticTable<>();
                for (int i = 0; i < frame.keys.size(); ++i) table.put(frame.keys.get(i), frame.values.get(i));
                yield new ClassOpsExecutor.Value.Table(table);
            }
            case SharedStdlibSemantics.Value.Array ignored -> new ClassOpsExecutor.Value.Array(
                SemanticArray.of(frame.values), base(frame.target) instanceof RuntimeDescriptor.Array array
                    ? array.element() : RuntimeDescriptor.Unknown.INSTANCE);
            default -> throw new IllegalStateException("JSON decoding encountered non-JSON data");
        };
    }
}
