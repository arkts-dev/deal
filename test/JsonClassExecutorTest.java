package deal.test;

import deal.diagnostics.DiagnosticCode;
import deal.semantic.JsonDecodingSemantics;
import deal.semantic.SharedStdlibSemantics;
import deal.semantic.SharedStdlibSemantics.Value;
import deal.semantic.ir.*;
import java.util.*;

/** Contextual reconstruction and builtin serialization over exact live layouts. */
public final class JsonClassExecutorTest {
    private static int passed;
    private static final SourceOrigin ORIGIN = new SourceOrigin("corpus.deal",
        new SourceSpan("corpus.deal", 7, 11, 7, 29), SourceOriginKind.USER,
        new AnchorId(1), null);
    private static final ClassId POINT = new ClassId("corpus.json", "Point");
    private static final ClassId INNER = new ClassId("corpus.json", "Inner");
    private static final ClassId NESTED = new ClassId("owner.json", "Nested");
    private static final ClassId OTHER = new ClassId("corpus.json", "Other");
    private static final RuntimeDescriptor NUMBER = RuntimeDescriptor.Number.INSTANCE;
    private static final RuntimeDescriptor STRING = RuntimeDescriptor.String.INSTANCE;
    private static final RuntimeDescriptor RECORD = RuntimeDescriptor.Table.INSTANCE;

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        ++passed;
    }

    private static void rejects(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError("expected producer rejection: " + message);
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(message), "producer rejection: " + expected);
        }
    }

    private static ClassLayout.FieldLayout field(String name, RuntimeDescriptor type, boolean required) {
        return new ClassLayout.FieldLayout(name, type, required, ConstructionProvider.LOCAL);
    }

    private static ClassLayout layout(ClassId id, ClassLayout.FieldLayout... fields) {
        return new ClassLayout(id, List.of(fields));
    }

    private static Value.Table record(Map<String, Value> fields) {
        SemanticTable<Value> table = new SemanticTable<>();
        fields.forEach(table::put);
        return new Value.Table(table);
    }

    private static Value.Table instance(ClassLayout layout, Map<String, Value> fields) {
        SemanticTable<Value> table = new SemanticTable<>();
        fields.forEach(table::put);
        return new Value.Table(table, layout);
    }

    private static Value.Array array(Value... values) {
        return new Value.Array(SemanticArray.of(values));
    }

    private static Value.Array typedArray(RuntimeDescriptor element, Value... values) {
        return new Value.Array(SemanticArray.of(values), element);
    }

    private static Value invalid() { return new Value.String(UnicodeScalars.Invalid.INSTANCE); }

    private static Value function() {
        var signature = new RuntimeDescriptor.Func(List.of(), RuntimeDescriptor.Null.INSTANCE, false);
        return new Value.Other(ActualKind.FUNCTION, null, BoundaryValueView.ofFunction(signature));
    }

    private static ClassOpsExecutor.Outcome<ClassOpsExecutor.Value> parse(String text,
            RuntimeDescriptor target, Map<ClassId, ClassLayout> layouts) {
        return JsonDecodingSemantics.parse((UnicodeScalars.Valid) UnicodeScalars.validate(text),
            target, layouts, ORIGIN);
    }

    private static ClassOpsExecutor.Value decoded(String text, RuntimeDescriptor target,
            Map<ClassId, ClassLayout> layouts) {
        var outcome = parse(text, target, layouts);
        check(outcome instanceof ClassOpsExecutor.Outcome.Success<?>, "decode succeeds: " + text);
        return ((ClassOpsExecutor.Outcome.Success<ClassOpsExecutor.Value>) outcome).value();
    }

    private static ClassOpsExecutor.Value decoded(String text, ClassLayout root,
            Map<ClassId, ClassLayout> layouts) {
        return decoded(text, new RuntimeDescriptor.Class(root.classId()), layouts);
    }

    private static BoundaryFailure decodeFailure(String text, ClassLayout root,
            Map<ClassId, ClassLayout> layouts, DiagnosticCode code, String actual) {
        var outcome = parse(text, new RuntimeDescriptor.Class(root.classId()), layouts);
        check(outcome instanceof ClassOpsExecutor.Outcome.Failure<?>, "decode rejects: " + text);
        var failed = ((ClassOpsExecutor.Outcome.Failure<ClassOpsExecutor.Value>) outcome).failure();
        check(failed.origin().equals(ORIGIN), "parse operation owns failure");
        check(failed.failure().code() == code, "parse rejection code: " + text);
        check(Objects.equals(failed.failure().actual(), actual), "parse rejection actual: " + text);
        check(failed.failure().cause() == null, "reconstruction has no composed cause");
        return failed.failure();
    }

    private static ClassOpsExecutor.FieldState state(ClassOpsExecutor.Value value,
            ClassLayout layout, String name) {
        var instance = (ClassOpsExecutor.Value.Class) value;
        for (int i = 0; i < layout.fields().size(); ++i)
            if (layout.fields().get(i).name().equals(name)) return instance.fields().get(i);
        throw new AssertionError("unknown field " + name);
    }

    private static ClassOpsExecutor.Value member(ClassOpsExecutor.Value value,
            ClassLayout layout, String name) {
        return ((ClassOpsExecutor.FieldState.Present) state(value, layout, name)).value();
    }

    private static String text(ClassOpsExecutor.Value value) {
        return ((UnicodeScalars.Valid) ((ClassOpsExecutor.Value.String) value).scalar()).carrier();
    }

    private static String encoded(Value value) {
        var outcome = SharedStdlibSemantics.jsonStringify(ORIGIN, value);
        check(outcome instanceof SharedStdlibSemantics.Outcome.Success<?>, "stringify succeeds");
        var result = ((SharedStdlibSemantics.Outcome.Success<Value>) outcome).value();
        return ((UnicodeScalars.Valid) ((Value.String) result).scalar()).carrier();
    }

    private static BoundaryFailure encodeFailure(Value value, DiagnosticCode code,
            String message, String expected, String actual) {
        var outcome = SharedStdlibSemantics.jsonStringify(ORIGIN, value);
        check(outcome instanceof SharedStdlibSemantics.Outcome.Failure<?>, "stringify rejects");
        var failure = ((SharedStdlibSemantics.Outcome.Failure<Value>) outcome).failure();
        check(failure.origin().equals(ORIGIN), "stringify operation owns failure, not a caller anchor");
        check(failure.failure().code() == code, "stringify code");
        check(failure.failure().message().equals(message), "stringify message: " + failure.failure());
        check(Objects.equals(failure.failure().expected(), expected), "stringify expected");
        check(Objects.equals(failure.failure().actual(), actual), "stringify actual");
        return failure.failure();
    }

    private static void unsupported(Value value) {
        var failure = encodeFailure(value, DiagnosticCode.E8001,
            "unsupported type for JSON encoding: string", "string, number, boolean, or table", "string");
        check(failure.cause() == null, "unsupported is not an array typed-position failure");
    }

    private static void cycle(Value value) {
        var failure = encodeFailure(value, DiagnosticCode.E8001,
            "cyclic value cannot be encoded as JSON", null, null);
        check(failure.cause() == null && failure.metadata().isEmpty(), "cycle has no typed cause or metadata");
    }

    private static void algorithms() {
        String[] lexemes = {"2147483647", "-2147483648", "-0", "2147483648", "1.5",
            "1e0", "1.0", "100e-2", "2147483647.00000001", "-2147483649", "-9999999999",
            "1e0000000000000000001", "100e-000000000000000000002", "-1e0000000000000000001"};
        double[] numbers = {2147483647, -2147483648, -0.0, 2147483648.0, 1.5, 1, 1, 1,
            2147483647.0, -2147483649.0, -9999999999.0, 10, 1, -10};
        for (int i = 0; i < lexemes.length; ++i) {
            var value = (ClassOpsExecutor.Value.Number) decoded(lexemes[i], NUMBER, Map.of());
            check(Double.doubleToRawLongBits(value.value()) == Double.doubleToRawLongBits(numbers[i]),
                "binary64 parse including signed zero: " + lexemes[i]);
        }
        for (String malformed : List.of("{x", "{\"a\":1,\"b\":2,\"a\":3}", "{\"a\":null,\"\\u0061\":1}")) {
            var result = parse(malformed, RuntimeDescriptor.Unknown.INSTANCE, Map.of());
            check(result instanceof ClassOpsExecutor.Outcome.Failure<?> failure
                && failure.failure().failure().policy() == FailurePolicyId.JSON_PARSE_SYNTAX
                && failure.failure().origin().equals(ORIGIN), "strict syntax and decoded-key uniqueness");
        }
        check(text(decoded("\"text\"", STRING, Map.of())).equals("text"), "string literal");
        check(((ClassOpsExecutor.Value.Array) decoded("[]", RuntimeDescriptor.Unknown.INSTANCE, Map.of())).array().size() == 0,
            "empty array keeps its variant");
        var escaped = new SemanticTable<Value>();
        escaped.put("q", Value.string("a\"b\n"));
        escaped.put("z", new Value.Number(2));
        check(encoded(new Value.Table(escaped)).equals("{\"q\":\"a\\\"b\\n\",\"z\":2}"), "escaping and insertion order");
        check(encoded(new Value.Number(Double.NaN)).equals("null"), "nonfinite root substitutes null");
        for (Value omitted : List.of(function(), Value.Undefined.INSTANCE, new Value.Other(ActualKind.MISSING, null))) {
            check(encoded(record(Map.of("k", omitted))).equals("{}"), "object omission");
            check(encoded(array(omitted)).equals("[null]"), "array substitution");
        }
        var point = layout(POINT, field("x", NUMBER, true));
        check(encoded(record(Map.of("k", instance(point, Map.of("x", new Value.Number(1)))))).equals("{\"k\":{\"x\":1}}"),
            "class inside Record uses its own plan");
        unsupported(record(Map.of("k", invalid())));
        var cyclic = self();
        cycle(cyclic);
        var mixed = new SemanticTable<Value>();
        var mixedValue = new Value.Table(mixed);
        mixed.put("a", array(mixedValue));
        cycle(mixedValue);
    }

    private static void reconstruction() {
        var point = layout(POINT, field("x", NUMBER, true), field("tag", STRING, true),
            field("note", RuntimeDescriptor.nullable(STRING), false));
        var layouts = Map.of(POINT, point);
        for (String document : List.of("null", "42", "true", "\"text\"", "[1]", "[]")) {
            String actual = document.startsWith("[") ? "array" : document.equals("null") ? "null"
                : document.equals("true") ? "boolean" : document.startsWith("\"") ? "string" : "number";
            decodeFailure(document, point, layouts, DiagnosticCode.E8001, actual);
        }
        for (String document : List.of("{}", "{\"x\":1}", "{\"tag\":\"t\"}"))
            decodeFailure(document, point, layouts, DiagnosticCode.E8001, "undefined");
        decodeFailure("{oops", point, layouts, DiagnosticCode.E8001, null);
        decodeFailure("{\"x\":1,\"alien\":true,\"tag\":\"t\"}", point, layouts, DiagnosticCode.E8007, null);
        decodeFailure("{\"x\":\"bad\",\"tag\":\"t\"}", point, layouts, DiagnosticCode.E8001, "string");
        decodeFailure("{\"x\":null,\"tag\":\"t\"}", point, layouts, DiagnosticCode.E8001, "null");
        var provided = decoded("{\"tag\":\"pt\",\"x\":40}", point, layouts);
        check(((ClassOpsExecutor.Value.Number) member(provided, point, "x")).value() == 40, "provided fields reconstruct");
        check(state(provided, point, "note") == ClassOpsExecutor.FieldState.Missing.INSTANCE, "optional stays absent");
        var present = decoded("{\"x\":1,\"tag\":\"t\",\"note\":null}", point, layouts);
        check(member(present, point, "note") == ClassOpsExecutor.Value.Null.INSTANCE, "explicit nullable null stays present");
        var optional = layout(POINT, field("x", NUMBER, false));
        check(state(decoded("{}", optional, Map.of(POINT, optional)), optional, "x") == ClassOpsExecutor.FieldState.Missing.INSTANCE,
            "all-optional class accepts empty object");
        decodeFailure("[]", optional, Map.of(POINT, optional), DiagnosticCode.E8001, "array");
        var nullable = layout(POINT, field("note", RuntimeDescriptor.nullable(STRING), true));
        decodeFailure("{}", nullable, Map.of(POINT, nullable), DiagnosticCode.E8001, "undefined");
        check(member(decoded("{\"note\":null}", nullable, Map.of(POINT, nullable)), nullable, "note") == ClassOpsExecutor.Value.Null.INSTANCE,
            "required nullable accepts present null only");
        var record = layout(POINT, field("data", RECORD, true));
        for (String document : List.of("{\"data\":[1,2]}", "{\"data\":[]}"))
            decodeFailure(document, record, Map.of(POINT, record), DiagnosticCode.E8001, "array");
        check(((ClassOpsExecutor.Value.Table) member(decoded("{\"data\":{}}", record, Map.of(POINT, record)), record, "data")).table().keys().isEmpty(),
            "empty object remains Record");

        var nested = layout(NESTED, field("city", STRING, true), field("zip", NUMBER, true),
            field("note", RuntimeDescriptor.nullable(STRING), false));
        var person = layout(POINT, field("name", STRING, true), field("home", new RuntimeDescriptor.Class(NESTED), true));
        var both = Map.of(POINT, person, NESTED, nested);
        var decoded = decoded("{\"home\":{\"zip\":10115,\"city\":\"berlin\"},\"name\":\"anon\"}", person, both);
        var home = (ClassOpsExecutor.Value.Class) member(decoded, person, "home");
        check(home.classId().equals(NESTED), "private nested defining identity");
        check(text(member(home, nested, "city")).equals("berlin"), "nested city");
        check(((ClassOpsExecutor.Value.Number) member(home, nested, "zip")).value() == 10115, "nested number");
        check(state(home, nested, "note") == ClassOpsExecutor.FieldState.Missing.INSTANCE, "nested absence");
        check(text(member(decoded, person, "name")).equals("anon"), "outer field");
        for (String inner : List.of("{}", "{\"city\":\"b\"}", "{\"zip\":1}"))
            decodeFailure("{\"name\":\"n\",\"home\":" + inner + "}", person, both, DiagnosticCode.E8001, "undefined");
        decodeFailure("{\"name\":\"n\",\"home\":{\"city\":\"b\",\"zip\":1,\"alien\":1}}", person, both, DiagnosticCode.E8007, null);
        decodeFailure("{\"name\":\"n\",\"home\":[]}", person, both, DiagnosticCode.E8001, "array");
        decodeFailure("{\"name\":\"n\",\"home\":null}", person, both, DiagnosticCode.E8001, "null");
        decodeFailure("{\"name\":\"n\",\"home\":{\"city\":\"b\",\"zip\":\"bad\"}}", person, both, DiagnosticCode.E8001, "string");
        rejects(() -> parse("{}", new RuntimeDescriptor.Class(POINT), Map.of(POINT, person)), "no exact class layout");
    }

    private static Value chain(int depth, boolean mixed) {
        Value current = record(Map.of());
        for (int i = depth - 1; i >= 0; --i)
            current = mixed && i % 2 == 1 ? array(current) : record(Map.of("k", current));
        return current;
    }

    private static Value.Table self() {
        var table = new SemanticTable<Value>();
        var value = new Value.Table(table);
        table.put("self", value);
        return value;
    }

    private static void reconstructionDepth() {
        var node = layout(INNER, field("next", RuntimeDescriptor.nullable(new RuntimeDescriptor.Class(INNER)), false));
        for (int depth : List.of(513, 1200)) {
            Value raw = record(Map.of());
            for (int i = 0; i < depth; ++i) raw = record(Map.of("next", raw));
            var result = JsonDecodingSemantics.decode(raw, new RuntimeDescriptor.Class(INNER), Map.of(INNER, node), ORIGIN);
            check(result instanceof ClassOpsExecutor.Outcome.Success<?>, "deep raw class reconstruction " + depth);
            var cursor = ((ClassOpsExecutor.Outcome.Success<ClassOpsExecutor.Value>) result).value();
            for (int i = 0; i < depth; ++i) {
                check(((ClassOpsExecutor.Value.Class) cursor).classId().equals(INNER), "deep identity");
                cursor = member(cursor, node, "next");
            }
            check(state(cursor, node, "next") == ClassOpsExecutor.FieldState.Missing.INSTANCE, "deep terminal absence");
            decoded("{\"next\":".repeat(depth) + "{}" + "}".repeat(depth), node, Map.of(INNER, node));
        }
        var box = layout(POINT, field("data", RECORD, true));
        for (boolean mixed : List.of(false, true)) {
            var result = JsonDecodingSemantics.decode(record(Map.of("data", chain(600, mixed))),
                new RuntimeDescriptor.Class(POINT), Map.of(POINT, box), ORIGIN);
            check(result instanceof ClassOpsExecutor.Outcome.Success<?>, "600-deep Record/mixed reconstruction");
            var instance = ((ClassOpsExecutor.Outcome.Success<ClassOpsExecutor.Value>) result).value();
            check(member(instance, box, "data") instanceof ClassOpsExecutor.Value.Table, "deep reconstructed Record");
        }
    }

    private static void serialization() {
        var nested = layout(NESTED, field("city", STRING, true));
        var other = layout(OTHER, field("city", STRING, true));
        var point = layout(POINT, field("name", STRING, true), field("age", NUMBER, true),
            field("home", new RuntimeDescriptor.Class(NESTED), true),
            field("tags", new RuntimeDescriptor.Array(NUMBER), true), field("data", RECORD, true),
            field("note", RuntimeDescriptor.nullable(STRING), false));
        Map<String, Value> fields = new HashMap<>();
        fields.put("name", Value.string("a\"b")); fields.put("age", new Value.Number(42));
        fields.put("home", instance(nested, Map.of("city", Value.string("c"))));
        fields.put("tags", typedArray(NUMBER, new Value.Number(0))); fields.put("data", record(Map.of()));
        fields.put("note", Value.Null.INSTANCE);
        var owner = instance(point, fields);
        String expected = "{\"name\":\"a\\\"b\",\"age\":42,\"home\":{\"city\":\"c\"},\"tags\":[0],\"data\":{},\"note\":null}";
        check(encoded(owner).equals(expected), "declaration order, escaping and present null");
        check(encoded(owner).equals(expected), "stateless repeated serialization");
        owner.table().remove("note");
        check(!encoded(owner).contains("note"), "optional omission");
        owner.table().put("home", instance(other, Map.of("city", Value.string("x"))));
        encodeFailure(owner, DiagnosticCode.E8001,
            "expected instance of " + NESTED.text() + ", got " + OTHER.text(), NESTED.text(), OTHER.text());
        owner.table().put("age", Value.string("bad"));
        encodeFailure(owner, DiagnosticCode.E8001, "expected number", "number", "string");
        owner.table().put("age", new Value.Number(42));
        owner.table().put("home", fields.get("home"));
        owner.table().put("tags", array(new Value.Number(0), Value.string("x")));
        var arrayFailure = SharedStdlibSemantics.jsonStringify(ORIGIN, owner);
        check(arrayFailure instanceof SharedStdlibSemantics.Outcome.Failure<?>, "declared array rejects bad element");
        var wrapped = ((SharedStdlibSemantics.Outcome.Failure<Value>) arrayFailure).failure().failure();
        check(((SharedStdlibSemantics.Outcome.Failure<Value>) arrayFailure).failure().origin().equals(ORIGIN),
            "declared-array rejection retains stringify operation origin");
        check(wrapped.policy() == FailurePolicyId.ARRAY_ELEMENT_DESCRIPTOR
            && wrapped.code() == DiagnosticCode.E8003 && wrapped.message().equals("array element 2 type mismatch")
            && wrapped.expected().equals("number") && wrapped.actual().equals("string")
            && wrapped.metadata().equals(Map.of("oneBasedIndex", "2")), "complete array wrapper tuple");
        check(wrapped.cause() != null && wrapped.cause().policy() == FailurePolicyId.TYPE_DESCRIPTOR
            && wrapped.cause().code() == DiagnosticCode.E8001 && wrapped.cause().message().equals("expected number")
            && wrapped.cause().expected().equals("number") && wrapped.cause().actual().equals("string")
            && wrapped.cause().metadata().isEmpty() && wrapped.cause().cause() == null, "complete original typed leaf");
        owner.table().put("tags", fields.get("tags"));
        owner.table().put("data", record(Map.of("k", function())));
        check(encoded(owner).contains("\"data\":{}"), "Record function omitted");
        owner.table().put("data", record(Map.of("k", instance(other, Map.of("city", Value.string("x"))))));
        check(encoded(owner).contains("\"data\":{\"k\":{\"city\":\"x\"}}"), "dynamic nominal uses its own layout");
        for (String key : List.of("k", "a.0", "{bad}", "{actual}", "{fieldPath}", "a}b", "a{b")) {
            var data = new SemanticTable<Value>();
            data.put("a", array(new Value.Number(1))); data.put(key, invalid());
            owner.table().put("data", new Value.Table(data));
            unsupported(owner);
            data.put(key, Value.string(key));
            check(encoded(owner).contains("\"" + key + "\":\"" + key + "\""), "literal key survives repair: " + key);
        }
        var dotted = new SemanticTable<Value>();
        dotted.put("a", array(invalid())); dotted.put("a.0", new Value.Number(1));
        owner.table().put("data", new Value.Table(dotted)); unsupported(owner);
        owner.table().put("data", fields.get("data"));
        owner.table().put("home", instance(nested, Map.of("city", new Value.Number(9))));
        encodeFailure(owner, DiagnosticCode.E8001, "expected string", "string", "number");
        owner.table().put("home", fields.get("home"));
        check(encoded(instance(other, Map.of("city", Value.string("x")))).equals("{\"city\":\"x\"}"), "foreign root is valid builtin input");
        check(encoded(new Value.Number(3.5)).equals("3.5"), "scalar root is valid builtin input");
        var nan = layout(POINT, field("x", NUMBER, true));
        check(encoded(instance(nan, Map.of("x", new Value.Number(Double.NaN)))).equals("{\"x\":null}"), "declared nonfinite substitutes null");
        owner.table().remove("name");
        encodeFailure(owner, DiagnosticCode.E8001, "expected class instance", "class", POINT.text());
        owner.table().put("name", Value.Null.INSTANCE);
        encodeFailure(owner, DiagnosticCode.E8001, "expected string", "string", "null");
        owner.table().put("name", fields.get("name"));
        check(encoded(owner).equals(expected.replace(",\"note\":null", "")), "same graph repaired completely");
    }

    private static void transportBeforeCycle() {
        var schema = layout(POINT, field("name", STRING, true), field("later", RuntimeDescriptor.Unknown.INSTANCE, true));
        var later = record(Map.of());
        var root = instance(schema, Map.of("name", Value.string("kept"), "later", later));
        later.table().put("back", root);
        var malformed = new Value.String(UnicodeScalars.Invalid.INSTANCE);
        root.table().put("name", malformed);
        var leaf = encodeFailure(root, DiagnosticCode.E8001,
            "expected string, got invalid Unicode scalar encoding", "string", "invalid-unicode");
        check(leaf.policy() == FailurePolicyId.TYPE_DESCRIPTOR
            && leaf.cause() == null && leaf.metadata().isEmpty(), "malformed position has typed policy and no fabricated cause or path");
        check(root.table().get("name") instanceof SemanticTable.Lookup.Present<Value> present
            && present.value() == malformed, "rejected malformed field keeps its original value");
        check(root.table().get("later") instanceof SemanticTable.Lookup.Present<Value> present
            && present.value() == later && later.table().get("back") instanceof SemanticTable.Lookup.Present<Value> back
            && back.value() == root, "later graph aliases and cycle remain untouched");
        root.table().put("name", Value.string("kept"));
        cycle(root);
        later.table().remove("back");
        check(encoded(root).equals("{\"name\":\"kept\",\"later\":{}}"),
            "repair only the selected positions on the same graph");
    }

    private static void cyclesAndDepth() {
        var node = layout(INNER, field("next", RuntimeDescriptor.nullable(new RuntimeDescriptor.Class(INNER)), false));
        var shared = instance(node, Map.of());
        var pair = layout(POINT, field("a", new RuntimeDescriptor.Class(INNER), true), field("b", new RuntimeDescriptor.Class(INNER), true));
        check(encoded(instance(pair, Map.of("a", shared, "b", shared))).equals("{\"a\":{},\"b\":{}}"), "shared nominal is not a cycle");
        var box = layout(POINT, field("data", RECORD, true));
        cycle(instance(box, Map.of("data", self())));
        cycle(self());
        for (int depth : List.of(513, 1200)) {
            Value current = shared;
            for (int i = 0; i < depth; ++i) current = instance(node, Map.of("next", current));
            check(encoded(current).equals("{\"next\":".repeat(depth) + "{}" + "}".repeat(depth)), "deep finite nominal stringify " + depth);
        }
        for (int depth : List.of(600, 602)) for (boolean mixed : List.of(false, true)) {
            var tail = chain(depth, mixed);
            check(encoded(instance(box, Map.of("data", tail))).startsWith("{\"data\":{\"k\":"), "deep finite Record/mixed serialization");
            var first = new SemanticTable<Value>();
            first.put("bad", invalid()); first.put("later", tail);
            unsupported(instance(box, Map.of("data", new Value.Table(first))));
            var later = new SemanticTable<Value>();
            later.put("k", tail); later.put("zbad", invalid());
            unsupported(instance(box, Map.of("data", new Value.Table(later))));
            var reentry = self(); reentry.table().put("later", tail);
            cycle(instance(box, Map.of("data", reentry)));
        }
        // Earlier unsupported leaves must not read later cyclic/deep subtrees.
        for (boolean mixed : List.of(false, true)) {
            var table = new SemanticTable<Value>();
            var root = new Value.Table(table);
            table.put("bad", invalid()); table.put("later", mixed ? array(root) : root);
            unsupported(root); unsupported(instance(box, Map.of("data", root)));
            var cycleFirst = new SemanticTable<Value>();
            var cycleRoot = new Value.Table(cycleFirst);
            cycleFirst.put("self", mixed ? array(cycleRoot) : cycleRoot); cycleFirst.put("zbad", invalid());
            cycle(cycleRoot); cycle(instance(box, Map.of("data", cycleRoot)));
        }
        var firstElement = record(Map.of("bad", invalid()));
        var back = record(Map.of());
        var elements = typedArray(RECORD, firstElement, back);
        back.table().put("x", elements);
        var arrayBox = layout(POINT, field("data", new RuntimeDescriptor.Array(RECORD), true));
        unsupported(instance(arrayBox, Map.of("data", elements)));
        var inner = record(Map.of());
        var nestedArray = array(invalid(), inner); inner.table().put("x", nestedArray);
        var nested = record(Map.of("a", nestedArray));
        unsupported(nested); unsupported(instance(box, Map.of("data", nested)));
        for (boolean nominal : List.of(false, true)) {
            Value tail = self();
            for (int i = 0; i < 50000; ++i) tail = record(Map.of("k", tail));
            var root = new SemanticTable<Value>(); root.put("bad", invalid()); root.put("later", tail);
            unsupported(nominal ? instance(box, Map.of("data", new Value.Table(root))) : new Value.Table(root));
        }
        int[] reads = {0};
        var later = new Value.Array(SemanticArray.live(() -> { ++reads[0]; throw new AssertionError("read unreachable tail"); },
            i -> { throw new AssertionError("read unreachable element"); }));
        var ordered = new SemanticTable<Value>(); ordered.put("bad", invalid()); ordered.put("later", later);
        unsupported(instance(box, Map.of("data", new Value.Table(ordered))));
        check(reads[0] == 0, "selected failure suppresses later reads, not merely later errors");
    }

    /** Lazy test view: memoize backing identities before any descendant reads. */
    private static Value view(ClassOpsExecutor.Value value, Map<ClassId, ClassLayout> layouts,
            IdentityHashMap<Object, Value> views) {
        Object identity = value instanceof ClassOpsExecutor.Value.Table table ? table.table()
            : value instanceof ClassOpsExecutor.Value.Array array ? array.array() : value;
        if (views.containsKey(identity)) return views.get(identity);
        Value result = switch (value) {
            case ClassOpsExecutor.Value.Null ignored -> Value.Null.INSTANCE;
            case ClassOpsExecutor.Value.Undefined ignored -> Value.Undefined.INSTANCE;
            case ClassOpsExecutor.Value.Missing ignored -> new Value.Other(ActualKind.MISSING, null);
            case ClassOpsExecutor.Value.Bool bool -> new Value.Bool(bool.value());
            case ClassOpsExecutor.Value.Number number -> new Value.Number(number.value());
            case ClassOpsExecutor.Value.String string -> new Value.String(string.scalar());
            case ClassOpsExecutor.Value.Function function -> new Value.Other(ActualKind.FUNCTION, null,
                BoundaryValueView.ofFunction(function.signature()));
            case ClassOpsExecutor.Value.Array array -> new Value.Array(SemanticArray.live(array.array()::size,
                i -> view(array.array().elementAt(i), layouts, views)), array.elementDescriptor());
            case ClassOpsExecutor.Value.Table table -> new Value.Table(SemanticTable.live(table.table()::keys, key -> {
                var slot = table.table().get(key);
                return slot instanceof SemanticTable.Lookup.Present<ClassOpsExecutor.Value> present
                    ? new SemanticTable.Lookup.Present<>(view(present.value(), layouts, views)) : new SemanticTable.Lookup.Missing<>();
            }));
            case ClassOpsExecutor.Value.Class instance -> {
                var layout = layouts.get(instance.classId());
                if (layout == null) throw new AssertionError("missing exact test layout");
                List<String> names = layout.fields().stream().map(ClassLayout.FieldLayout::name).toList();
                yield new Value.Table(SemanticTable.live(() -> names, key -> {
                    int i = names.indexOf(key);
                    return i >= 0 && instance.fields().get(i) instanceof ClassOpsExecutor.FieldState.Present present
                        ? new SemanticTable.Lookup.Present<>(view(present.value(), layouts, views)) : new SemanticTable.Lookup.Missing<>();
                }), layout);
            }
        };
        views.put(identity, result);
        return result;
    }

    private static void roundtripAndDefects() {
        var point = layout(POINT, field("name", STRING, true), field("age", NUMBER, true),
            field("note", RuntimeDescriptor.nullable(STRING), false), field("tags", new RuntimeDescriptor.Array(NUMBER), true));
        var layouts = Map.of(POINT, point);
        String text = "{\"name\":\"anon\",\"age\":42,\"tags\":[1,2]}";
        var first = decoded(text, point, layouts);
        check(encoded(view(first, layouts, new IdentityHashMap<>())).equals(text), "contextual parse/builtin stringify roundtrip");
        var second = decoded(text, point, layouts);
        check(first != second && member(first, point, "tags") != member(second, point, "tags"), "fresh class and descendants per parse");
        var target = new RuntimeDescriptor.Class(POINT);
        rejects(() -> parse("{}", target, Map.of()), "no exact class layout");
        rejects(() -> parse("{}", target, Map.of(POINT, layout(OTHER))), "no exact class layout");
        rejects(() -> parse("{}", target, Map.of(POINT, layout(POINT, field("x", NUMBER, true), field("x", STRING, false)))), "duplicate field");
        var function = new RuntimeDescriptor.Func(List.of(), RuntimeDescriptor.Null.INSTANCE, false);
        rejects(() -> parse("", function, Map.of()), "function type");
        rejects(() -> parse("", RuntimeDescriptor.nullable(function), Map.of()), "function type");
        rejects(() -> parse("", new RuntimeDescriptor.Array(function), Map.of()), "function type");
        rejects(() -> parse("", RuntimeDescriptor.Void.INSTANCE, Map.of()), "not a data type");
        rejects(() -> parse("{}", target, Map.of(POINT, layout(POINT, field("run", function, false)))), "function type");
        rejects(() -> parse("{}", target, Map.of(POINT, layout(POINT, field("next", target, false), field("run", function, false)))), "function type");
        rejects(() -> JsonDecodingSemantics.decode(self(), RuntimeDescriptor.Unknown.INSTANCE, Map.of(), ORIGIN), "acyclic parsed JSON data");
        rejects(() -> JsonDecodingSemantics.decode(function(), RuntimeDescriptor.Unknown.INSTANCE, Map.of(), ORIGIN), "parsed JSON data");
        var mismatched = layout(POINT, field("wrong", STRING, true));
        // A valid explicit layout is authoritative; there is no second helper payload to select another schema.
        decodeFailure(text, mismatched, Map.of(POINT, mismatched), DiagnosticCode.E8007, null);
        try {
            JsonDecodingSemantics.requireSchema(null, Map.of());
            throw new AssertionError("null target admitted");
        } catch (NullPointerException expected) { ++passed; }
        try {
            JsonDecodingSemantics.requireSchema(target, null);
            throw new AssertionError("null layouts admitted");
        } catch (NullPointerException expected) { ++passed; }
        try {
            SharedStdlibSemantics.jsonStringify(null, Value.Null.INSTANCE);
            throw new AssertionError("null origin admitted");
        } catch (NullPointerException expected) { ++passed; }
    }

    public static void main(String[] args) {
        algorithms();
        reconstruction();
        reconstructionDepth();
        serialization();
        transportBeforeCycle();
        cyclesAndDepth();
        roundtripAndDefects();
        System.out.println("JsonClassExecutorTest: " + passed + " passed, 0 failed");
    }
}
