package deal.test;

import deal.semantic.JsonDecodingSemantics;
import deal.semantic.SharedStdlibSemantics;
import deal.semantic.ir.*;
import java.util.*;

/** Independent target reconstruction controls; assertions never invoke this walk. */
public final class JsonDecodingSemanticsTest {
    private static final SourceOrigin ORIGIN = new SourceOrigin("json-call",
        new SourceSpan("json.deal", 7, 3, 7, 25, 80, 102), SourceOriginKind.USER,
        new AnchorId(1), null);
    private static int checks;
    private static void check(boolean condition) {
        ++checks;
        if (!condition) throw new AssertionError("JSON decoding control " + checks);
    }
    private static ClassOpsExecutor.Outcome<ClassOpsExecutor.Value> parse(String text,
            RuntimeDescriptor target, Map<ClassId, ClassLayout> layouts) {
        return JsonDecodingSemantics.parse((UnicodeScalars.Valid) UnicodeScalars.validate(text),
            target, layouts, ORIGIN);
    }
    private static ClassOpsExecutor.Value value(String text, RuntimeDescriptor target,
            Map<ClassId, ClassLayout> layouts) {
        var outcome = parse(text, target, layouts);
        check(outcome instanceof ClassOpsExecutor.Outcome.Success<?>);
        return ((ClassOpsExecutor.Outcome.Success<ClassOpsExecutor.Value>) outcome).value();
    }
    private static BoundaryFailure failure(String text, RuntimeDescriptor target,
            Map<ClassId, ClassLayout> layouts) {
        var outcome = parse(text, target, layouts);
        check(outcome instanceof ClassOpsExecutor.Outcome.Failure<?>);
        var failure = ((ClassOpsExecutor.Outcome.Failure<ClassOpsExecutor.Value>) outcome).failure();
        check(failure.origin().equals(ORIGIN));
        return failure.failure();
    }
    private static ClassLayout.FieldLayout field(String name, RuntimeDescriptor descriptor, boolean required) {
        return new ClassLayout.FieldLayout(name, descriptor, required, ConstructionProvider.LOCAL);
    }
    private static void rejectsSchema(RuntimeDescriptor target, Map<ClassId, ClassLayout> layouts) {
        try {
            // An empty/syntax-invalid document cannot bypass schema rejection.
            parse("", target, layouts);
            throw new AssertionError("admitted invalid JSON schema");
        } catch (IllegalArgumentException expected) { ++checks; }
    }
    private static void rejectsRawCycle(SharedStdlibSemantics.Value raw) {
        try {
            JsonDecodingSemantics.decode(raw, RuntimeDescriptor.Unknown.INSTANCE, Map.of(), ORIGIN);
            throw new AssertionError("admitted cyclic parsed graph");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().equals("JSON decoding requires acyclic parsed JSON data"));
        }
    }
    public static void main(String[] args) {
        var unknown = RuntimeDescriptor.Unknown.INSTANCE;
        var number = RuntimeDescriptor.Number.INSTANCE;
        var string = RuntimeDescriptor.String.INSTANCE;
        var record = RuntimeDescriptor.Table.INSTANCE;
        for (String root : List.of("null", "true", "false", "3", "-0", "1e400", "1e-400", "\"text\"", "[]", "{}"))
            value(root, unknown, Map.of());
        check(value("true", RuntimeDescriptor.Boolean.INSTANCE, Map.of()) instanceof ClassOpsExecutor.Value.Bool);
        check(((ClassOpsExecutor.Value.Number) value("-0", number, Map.of())).value() == 0.0);
        check(Double.doubleToRawLongBits(((ClassOpsExecutor.Value.Number) value("-0", number, Map.of())).value()) == Long.MIN_VALUE);
        check(Double.isInfinite(((ClassOpsExecutor.Value.Number) value("1e400", number, Map.of())).value()));
        var units = (ClassOpsExecutor.Value.String) value("\"\\ud800\\u0000\\udfff\"", string, Map.of());
        check(((UnicodeScalars.Valid) units.scalar()).carrier().equals("\ud800\0\udfff"));
        var untyped = (ClassOpsExecutor.Value.Array) value("[1,[2],{}]", unknown, Map.of());
        check(untyped.elementDescriptor() == unknown);
        check(((ClassOpsExecutor.Value.Array) untyped.array().elementAt(1)).elementDescriptor() == unknown);
        var typed = new RuntimeDescriptor.Array(new RuntimeDescriptor.Array(number));
        var nested = (ClassOpsExecutor.Value.Array) value("[[],[1,-0]]", typed, Map.of());
        check(nested.elementDescriptor().equals(new RuntimeDescriptor.Array(number)));
        check(((ClassOpsExecutor.Value.Array) nested.array().elementAt(0)).elementDescriptor() == number);
        check(nested.array() != ((ClassOpsExecutor.Value.Array) value("[[],[1,-0]]", typed, Map.of())).array());
        var nullable = new RuntimeDescriptor.Nullable(number, true, true);
        value("null", nullable, Map.of());
        value("2", nullable, Map.of());
        check(failure("null", new RuntimeDescriptor.Nullable(number, false, true), Map.of()).actual().equals("null"));
        check(failure("null", RuntimeDescriptor.Undefined.INSTANCE, Map.of()).expected().equals("undefined"));
        for (String document : List.of("[]", "null", "3", "true", "\"x\"")) {
            var rejected = failure(document, record, Map.of());
            check(rejected.code().name().equals("E8001"));
            check(rejected.message().equals("expected table"));
        }
        check(failure("[1,\"wrong\"]", new RuntimeDescriptor.Array(number), Map.of()).message().equals("expected number"));
        var nodeId = new ClassId("src/model.deal", "Node");
        var node = new RuntimeDescriptor.Class(nodeId);
        var layout = new ClassLayout(nodeId, List.of(field("value", number, true),
            field("next", new RuntimeDescriptor.Nullable(node), false), field("payload", unknown, false)));
        var layouts = Map.of(nodeId, layout);
        var decoded = (ClassOpsExecutor.Value.Class) value("{\"next\":{\"value\":2},\"value\":1,\"payload\":[{}]}", node, layouts);
        check(decoded.classId().equals(nodeId));
        check(((ClassOpsExecutor.Value.Number) ((ClassOpsExecutor.FieldState.Present) decoded.fields().get(0)).value()).value() == 1);
        var child = (ClassOpsExecutor.Value.Class) ((ClassOpsExecutor.FieldState.Present) decoded.fields().get(1)).value();
        check(child.classId().equals(nodeId));
        check(child.fields().get(1) == ClassOpsExecutor.FieldState.Missing.INSTANCE);
        check(child.fields().get(2) == ClassOpsExecutor.FieldState.Missing.INSTANCE);
        check(failure("{}", node, layouts).actual().equals("undefined"));
        check(failure("{\"value\":1,\"extra\":0}", node, layouts).message().equals("extra field 'extra' in class '@src/model.deal/Node'"));
        check(failure("{\"value\":\"bad\",\"extra\":0}", node, layouts).code().name().equals("E8007"));
        var requiredUndefined = new ClassLayout(nodeId, List.of(field("value", RuntimeDescriptor.Undefined.INSTANCE, true)));
        check(failure("{}", node, Map.of(nodeId, requiredUndefined)).message().equals("expected undefined"));
        var requiredNullable = new ClassLayout(nodeId, List.of(field("value", new RuntimeDescriptor.Nullable(number), true)));
        check(failure("{}", node, Map.of(nodeId, requiredNullable)).actual().equals("undefined"));
        value("{\"value\":null}", node, Map.of(nodeId, requiredNullable));
        // Earlier nested defects precede a later missing required field.
        var ordered = new ClassLayout(nodeId, List.of(field("first", new RuntimeDescriptor.Array(number), true), field("later", string, true)));
        check(failure("{\"first\":[false]}", node, Map.of(nodeId, ordered)).message().equals("expected number"));
        var siblingId = new ClassId("src/other.deal", "Node");
        var sibling = new ClassLayout(siblingId, List.of(field("name", string, true)));
        var both = Map.of(nodeId, layout, siblingId, sibling);
        check(((ClassOpsExecutor.Value.Class) value("{\"name\":\"s\"}", new RuntimeDescriptor.Class(siblingId), both)).classId().equals(siblingId));
        check(failure("{\"name\":\"s\"}", node, both).code().name().equals("E8007"));
        var fn = new RuntimeDescriptor.Func(List.of(), RuntimeDescriptor.Void.INSTANCE, false);
        rejectsSchema(fn, Map.of());
        rejectsSchema(new RuntimeDescriptor.Nullable(fn), Map.of());
        rejectsSchema(new RuntimeDescriptor.Array(fn), Map.of());
        rejectsSchema(RuntimeDescriptor.Void.INSTANCE, Map.of());
        rejectsSchema(node, Map.of());
        rejectsSchema(node, Map.of(nodeId, sibling));
        rejectsSchema(node, Map.of(nodeId, new ClassLayout(nodeId,
            List.of(field("value", number, true), field("value", string, false)))));
        rejectsSchema(node, Map.of(nodeId, new ClassLayout(nodeId, List.of(field("optional", fn, false)))));
        rejectsSchema(node, Map.of(nodeId, new ClassLayout(nodeId, List.of(field("next", node, false), field("later", fn, false)))));
        value("{\"payload\":{\"run\":{},\"next\":[]},\"value\":1}", node, layouts);
        value("{\"code\":\"X\",\"message\":\"message\"}", new RuntimeDescriptor.Class(ClassId.ERROR), Map.of());
        for (String document : List.of("{\"x\":1,\"\\u0078\":2}", "{\"child\":{\"x\":1,\"x\":2}}", "[", "null garbage")) {
            var rejected = failure(document, unknown, Map.of());
            check(rejected.policy() == FailurePolicyId.JSON_PARSE_SYNTAX);
            check(rejected.metadata().containsKey("oneBasedByteOffset"));
            check(rejected.cause() == null);
        }
        // Hostile direct callers cannot pass a cyclic raw graph as parsed
        // JSON. Path-local guards still allow sharing and rebuild each edge.
        SemanticTable<SharedStdlibSemantics.Value> cycle = new SemanticTable<>();
        var cyclic = new SharedStdlibSemantics.Value.Table(cycle);
        cycle.put("self", cyclic);
        rejectsRawCycle(cyclic);
        SharedStdlibSemantics.Value[] cell = new SharedStdlibSemantics.Value[1];
        var cyclicArray = new SharedStdlibSemantics.Value.Array(
            SemanticArray.live(() -> 1, index -> cell[0]));
        cell[0] = cyclicArray;
        rejectsRawCycle(cyclicArray);
        // Mixed backing identities cannot evade path-local cycle detection.
        var mixedTable = new SemanticTable<SharedStdlibSemantics.Value>();
        var mixed = new SharedStdlibSemantics.Value.Table(mixedTable);
        mixedTable.put("child", new SharedStdlibSemantics.Value.Array(SemanticArray.of(mixed)));
        rejectsRawCycle(mixed);
        var typedCycle = new SemanticTable<SharedStdlibSemantics.Value>();
        var typedRaw = new SharedStdlibSemantics.Value.Table(typedCycle);
        typedCycle.put("value", new SharedStdlibSemantics.Value.Number(1));
        typedCycle.put("next", typedRaw);
        try {
            JsonDecodingSemantics.decode(typedRaw, node, layouts, ORIGIN);
            throw new AssertionError("admitted cyclic raw class input");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().equals("JSON decoding requires acyclic parsed JSON data"));
        }
        SemanticTable<SharedStdlibSemantics.Value> childTable = new SemanticTable<>();
        childTable.put("value", new SharedStdlibSemantics.Value.Number(3));
        var shared = new SharedStdlibSemantics.Value.Table(childTable);
        var sharedRoot = new SharedStdlibSemantics.Value.Array(SemanticArray.of(shared, shared));
        var sharedDecoded = JsonDecodingSemantics.decode(sharedRoot, unknown, Map.of(), ORIGIN);
        check(sharedDecoded instanceof ClassOpsExecutor.Outcome.Success<?>);
        var fresh = (ClassOpsExecutor.Value.Array)
            ((ClassOpsExecutor.Outcome.Success<ClassOpsExecutor.Value>) sharedDecoded).value();
        check(((ClassOpsExecutor.Value.Table) fresh.array().elementAt(0)).table()
            != ((ClassOpsExecutor.Value.Table) fresh.array().elementAt(1)).table());
        var typedShared = JsonDecodingSemantics.decode(sharedRoot, new RuntimeDescriptor.Array(node), layouts, ORIGIN);
        check(typedShared instanceof ClassOpsExecutor.Outcome.Success<?>);
        var freshTyped = (ClassOpsExecutor.Value.Array)
            ((ClassOpsExecutor.Outcome.Success<ClassOpsExecutor.Value>) typedShared).value();
        check(freshTyped.elementDescriptor().equals(node));
        var firstClass = (ClassOpsExecutor.Value.Class) freshTyped.array().elementAt(0);
        var secondClass = (ClassOpsExecutor.Value.Class) freshTyped.array().elementAt(1);
        check(firstClass != secondClass && firstClass.classId().equals(nodeId) && secondClass.classId().equals(nodeId));
        check(firstClass.fields().get(1) == ClassOpsExecutor.FieldState.Missing.INSTANCE
            && secondClass.fields().get(1) == ClassOpsExecutor.FieldState.Missing.INSTANCE);
        // Neither scanner conversion nor reconstruction uses the Java stack.
        int depth = 20_000;
        String document = "[".repeat(depth) + "-0" + "]".repeat(depth);
        ClassOpsExecutor.Value deep = value(document, unknown, Map.of());
        for (int i = 0; i < depth; ++i) {
            var array = (ClassOpsExecutor.Value.Array) deep;
            check(array.elementDescriptor() == unknown);
            deep = array.array().elementAt(0);
        }
        check(Double.doubleToRawLongBits(((ClassOpsExecutor.Value.Number) deep).value()) == Long.MIN_VALUE);
        int classDepth = 5_000;
        document = "{\"value\":1,\"next\":".repeat(classDepth) + "null" + "}".repeat(classDepth);
        deep = value(document, new RuntimeDescriptor.Nullable(node), layouts);
        for (int i = 0; i < classDepth; ++i) {
            var instance = (ClassOpsExecutor.Value.Class) deep;
            check(instance.classId().equals(nodeId));
            deep = ((ClassOpsExecutor.FieldState.Present) instance.fields().get(1)).value();
        }
        check(deep == ClassOpsExecutor.Value.Null.INSTANCE);
        System.out.println("JsonDecodingSemanticsTest: " + checks + " passed, 0 failed");
    }
}
