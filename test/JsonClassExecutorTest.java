package deal.test;

import deal.diagnostics.DiagnosticCode;
import deal.semantic.JsonClassAlgorithmAdapter;
import deal.semantic.ir.ActualKind;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.ClassFactoryId;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.ClassOpsExecutor;
import deal.semantic.ir.ClassOpsExecutor.BodyRunner;
import deal.semantic.ir.ClassOpsExecutor.Defect;
import deal.semantic.ir.ClassOpsExecutor.FieldState;
import deal.semantic.ir.ClassOpsExecutor.JsonParse;
import deal.semantic.ir.ClassOpsExecutor.JsonStringify;
import deal.semantic.ir.ClassOpsExecutor.NestedClassFactory;
import deal.semantic.ir.ClassOpsExecutor.Outcome;
import deal.semantic.ir.ClassOpsExecutor.Value;
import deal.semantic.ir.ClosedSelector;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.DefaultOwner;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.OpResultType;
import deal.semantic.ir.OperationContractSnapshot;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticArray;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticTable;
import deal.semantic.ir.SemanticValue;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.UnicodeScalars;
import deal.semantic.ir.ValueId;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JsonClassExecutorTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    private static void expectDefect(Runnable runnable, String what) {
        try {
            runnable.run();
            fail("expected ClassOpsExecutor.Defect for " + what
                + ", but no exception was raised");
        } catch (Defect expected) {
            passed++;
        } catch (Throwable other) {
            fail("expected ClassOpsExecutor.Defect for " + what + ", got "
                + other.getClass().getSimpleName() + ": " + other.getMessage());
        }
    }

    private static void expectNpe(Runnable runnable, String what) {
        try {
            runnable.run();
            fail("expected NullPointerException for " + what + ", but no exception was raised");
        } catch (NullPointerException expected) {
            passed++;
        } catch (Throwable other) {
            fail("expected NullPointerException for " + what + ", got "
                + other.getClass().getSimpleName() + ": " + other.getMessage());
        }
    }

    // =========================================================================
    // Semantic-id and op builders (the ClassOpsExecutorTest discipline)
    // =========================================================================

    private static final ModuleId MOD = new ModuleId("corpus.json");
    private static final ModuleId OWNER = new ModuleId("owner.json");
    private static final ClassId POINT = new ClassId(MOD.path(), "Point");
    private static final ClassId INNER = new ClassId(MOD.path(), "Inner");
    private static final ClassId NESTED = new ClassId(OWNER.path(), "Nested");
    private static final ClassId OTHER = new ClassId(MOD.path(), "Other");
    private static final RuntimeDescriptor INT = RuntimeDescriptor.Number.INSTANCE;
    private static final RuntimeDescriptor NUMBER = RuntimeDescriptor.Number.INSTANCE;
    private static final RuntimeDescriptor STRING = RuntimeDescriptor.String.INSTANCE;
    private static final RuntimeDescriptor BOOL = RuntimeDescriptor.Boolean.INSTANCE;
    private static final RuntimeDescriptor TABLE = RuntimeDescriptor.Table.INSTANCE;

    private static int nextOpId = 1;
    private static int nextValueId = 1;
    private static int nextColumn = 1;

    private static OpId nextOpId() {
        return new OpId(MOD, nextOpId++);
    }

    private static ValueId nextValue() {
        return new ValueId(nextValueId++);
    }

    /** A distinct USER origin per op so origin identity is provable. */
    private static SourceOrigin originAt(OpId parent, int startColumn, SourceOriginKind kind) {
        return new SourceOrigin("corpus.deal",
            new SourceSpan("corpus.deal", 1, startColumn, 1, startColumn + 3),
            kind, new AnchorId(0), parent);
    }

    private static SourceOrigin nextOrigin(OpId parent) {
        int column = nextColumn;
        nextColumn += 10;
        return originAt(parent, column, SourceOriginKind.USER);
    }

    /** The generated-body synthetic anchor (never the projection origin). */
    private static SourceOrigin syntheticAnchor() {
        return originAt(null, 777, SourceOriginKind.SYNTHETIC);
    }

    private static OperationContractSnapshot contractFor(SemanticOpKind kind, KindPayload payload,
            OpResultType resultType, List<RuntimeDescriptor> operandTypes,
            FailurePolicyId policy, String digest) {
        ClosedSelector selector = payload instanceof KindPayload.SelectorCarrying carrying
            ? carrying.selector() : null;
        return new OperationContractSnapshot(OperationContractSnapshot.VERSION, kind, resultType,
            operandTypes, selector, payload, policy, List.of(), digest);
    }

    private static SemanticOp opWithId(OpId id, SemanticOpKind kind, KindPayload payload,
            SemanticValue result, OpResultType resultType, FailurePolicyId policy, OpId parent,
            SourceOrigin origin) {
        OperationContractSnapshot contract =
            contractFor(kind, payload, resultType, List.of(), policy, "placeholder");
        String digest = ContractSnapshotCanonicalizer.digest(contract);
        contract = contractFor(kind, payload, resultType, List.of(), policy, digest);
        return new SemanticOp(id, kind, origin, result, resultType, List.of(),
            List.of(), payload, policy, contract);
    }

    private static SemanticOp op(SemanticOpKind kind, KindPayload payload, SemanticValue result,
            OpResultType resultType, FailurePolicyId policy, OpId parent) {
        return opWithId(nextOpId(), kind, payload, result, resultType, policy, parent,
            nextOrigin(parent));
    }

    private static ClassLayout.FieldLayout field(String name, RuntimeDescriptor descriptor,
                                                 boolean required) {
        return new ClassLayout.FieldLayout(name, descriptor, required, DefaultOwner.LOCAL);
    }

    private static ClassLayout layoutOf(ClassId classId, ClassLayout.FieldLayout... fields) {
        return new ClassLayout(classId, List.of(fields));
    }

    private static SemanticOp defaultOp(ClassId classId, String field, SemanticValue result,
                                        RuntimeDescriptor resultType) {
        return op(SemanticOpKind.CLASS_DEFAULT,
            new KindPayload.ClassDefaultPayload(classId, field, new BlockId(1)),
            result, resultType, FailurePolicyId.NO_DEAL_FAILURE, null);
    }

    /** The JSON_FROM_CLASS op of one layout. */
    private static SemanticOp jsonFromOp(ClassLayout layout, ValueId jsonString) {
        return op(SemanticOpKind.JSON_FROM_CLASS,
            new KindPayload.JsonFromClassPayload(layout, jsonString),
            nextValue(), new RuntimeDescriptor.Nullable(new RuntimeDescriptor.Class(
                layout.classId())),
            FailurePolicyId.JSON_FROM_NULL, null);
    }

    /** The JSON_TO_CLASS op of one layout with a synthetic body anchor. */
    private static SemanticOp jsonToOp(ClassLayout layout, ValueId classValue) {
        OpId id = nextOpId();
        return opWithId(id, SemanticOpKind.JSON_TO_CLASS,
            new KindPayload.JsonToClassPayload(classValue, layout),
            nextValue(), RuntimeDescriptor.String.INSTANCE, FailurePolicyId.JSON_TO_ERROR,
            null, syntheticAnchor());
    }

    /** The field state of the declared field {@code name} of one instance. */
    private static FieldState fieldState(Value.Class instance, ClassLayout layout, String name) {
        for (int i = 0; i < layout.fields().size(); i++) {
            if (layout.fields().get(i).name().equals(name)) {
                return instance.fields().get(i);
            }
        }
        return null;
    }

    /** The present value of the declared field {@code name}, or null when absent. */
    private static Value fieldValue(Value.Class instance, ClassLayout layout, String name) {
        FieldState state = fieldState(instance, layout, name);
        return state instanceof FieldState.Present present ? present.value() : null;
    }

    private static Value.Class instanceOf(ClassLayout layout, Map<String, Value> present) {
        List<FieldState> states = new ArrayList<>(layout.fields().size());
        for (ClassLayout.FieldLayout field : layout.fields()) {
            Value value = present.get(field.name());
            states.add(value == null ? FieldState.Missing.INSTANCE
                : new FieldState.Present(value));
        }
        return new Value.Class(layout.classId(), List.copyOf(states));
    }

    private static Value.Table rawTable(Map<String, Value> entries) {
        SemanticTable<Value> table = new SemanticTable<>();
        for (Map.Entry<String, Value> entry : entries.entrySet()) {
            table.put(entry.getKey(), entry.getValue());
        }
        return new Value.Table(table);
    }

    // =========================================================================
    // Fixture seams: the recording body runner and the nested-factory trigger
    // =========================================================================

    /**
     * A recording body-runner fixture: every invocation of a
     * {@code CLASS_DEFAULT} default block is recorded (the log proves
     * the declaration order, the skip-provided rule, and that no
     * default runs after a provided-field decode failure) and returns
     * the scripted produced value of the op (or throws at the scripted
     * failing op).
     */
    private static final class ScriptedBodyRunner implements BodyRunner {

        private final List<String> log;
        private final Map<OpId, Value> produced;
        private final OpId failingOp;
        private int calls = 0;

        ScriptedBodyRunner(List<String> log, Map<OpId, Value> produced, OpId failingOp) {
            this.log = log;
            this.produced = produced;
            this.failingOp = failingOp;
        }

        int calls() {
            return calls;
        }

        @Override
        public Value runDefault(SemanticOp defaultOp) {
            calls++;
            log.add("default " + defaultOp.opId());
            if (defaultOp.opId().equals(failingOp)) {
                throw new IllegalStateException("scripted default-block failure of "
                    + defaultOp.opId());
            }
            return produced.get(defaultOp.opId());
        }
    }

    private static final class FactorySeam implements NestedClassFactory {

        private final List<String> log;
        private final SemanticOp triggeringJsonOp;
        private final Map<ClassId, SemanticOp> factoryOps;
        private final Map<OpId, SemanticOp> ownerDefaultOps;
        private final Map<ClassId, ClassLayout> layouts;
        private final Map<OpId, Value> produced;

        FactorySeam(List<String> log, SemanticOp triggeringJsonOp,
                    Map<ClassId, SemanticOp> factoryOps,
                    Map<OpId, SemanticOp> ownerDefaultOps,
                    Map<ClassId, ClassLayout> layouts,
                    Map<OpId, Value> produced) {
            this.log = log;
            this.triggeringJsonOp = triggeringJsonOp;
            this.factoryOps = factoryOps;
            this.ownerDefaultOps = ownerDefaultOps;
            this.layouts = layouts;
            this.produced = produced;
        }

        @Override
        public Value fillDefaults(ClassId classId, Set<String> providedFields) {
            SemanticOp factoryOp = factoryOps.get(classId);
            if (factoryOp == null) {
                throw new Defect("no CLASS_FACTORY resolves for " + classId
                    + " in the seam context (a producer defect, never executed)");
            }
            Outcome<Value> outcome = ClassOpsExecutor.executeClassFactory(factoryOp,
                triggeringJsonOp, ownerDefaultOps, layouts, providedFields,
                defaultOp -> {
                    log.add("nested-default " + defaultOp.opId());
                    return produced.get(defaultOp.opId());
                });
            if (!(outcome instanceof Outcome.Success<Value> success)) {
                throw new Defect("the nested factory returned a failure terminal");
            }
            return success.value();
        }
    }

    private static void testAlgorithmObservations() {
        ClassOpsExecutor.JsonParser parser = JsonClassAlgorithmAdapter.parser();
        String[] numbers = {"2147483647", "-2147483648", "-0", "2147483648", "1.5",
            "1e0", "1.0", "100e-2", "2147483647.00000001",
            "-2147483649", "-9999999999", "1e0000000000000000001",
            "100e-000000000000000000002", "-1e0000000000000000001"};
        double[] expected = {2147483647, -2147483648, 0, 2147483648.0, 1.5, 1, 1, 1,
            2147483647.0, -2147483649.0, -9999999999.0, 10, 1, -10};
        for (int i = 0; i < numbers.length; i++) {
            JsonParse parsed = parser.parse((UnicodeScalars.Valid) UnicodeScalars.validate(numbers[i]));
            Value value = ((JsonParse.Success) parsed).value();
            check(value instanceof Value.Number number && number.value() == expected[i],
                "binary64 numeric value: " + numbers[i]);
            if (numbers[i].equals("-0")) {
                check(value instanceof Value.Number number
                        && Double.doubleToRawLongBits(number.value())
                            == Double.doubleToRawLongBits(-0.0),
                    "JSON parsing preserves negative zero");
            }
        }
        check(parser.parse((UnicodeScalars.Valid) UnicodeScalars.validate("{x"))
            instanceof JsonParse.SyntaxFailure, "invalid JSON syntax is rejected");
        check(parser.parse((UnicodeScalars.Valid) UnicodeScalars.validate(
                "{\"a\":1,\"b\":2,\"a\":3}")) instanceof JsonParse.SyntaxFailure,
            "a decoded-key duplicate is a syntax failure");
        check(parser.parse((UnicodeScalars.Valid) UnicodeScalars.validate(
                "{\"a\":null,\"\\u0061\":1}")) instanceof JsonParse.SyntaxFailure,
            "an escaped spelling of the same decoded key is a duplicate");
        check(parser.parse((UnicodeScalars.Valid) UnicodeScalars.validate("\"text\""))
                instanceof JsonParse.Success p && p.value() instanceof Value.String s
                && carrier(s).equals("text"), "string parse retains literal text");
        check(parser.parse((UnicodeScalars.Valid) UnicodeScalars.validate("[]"))
                instanceof JsonParse.Success p && p.value() instanceof Value.Array a
                && a.array().size() == 0, "empty array parse retains its variant");
        SemanticTable<Value> escaped = new SemanticTable<>();
        escaped.put("q", Value.string("a\"b\n"));
        escaped.put("z", new Value.Number(2));
        check(JsonClassAlgorithmAdapter.stringifier().stringify(new Value.Table(escaped), "")
                instanceof JsonStringify.Success s
                && s.text().carrier().equals("{\"q\":\"a\\\"b\\n\",\"z\":2.0}"),
            "escaping and first-insertion order are literal-exact");
        assertStringifyFailure(new Value.Number(Double.NaN), "f", "f", "number", false);
        Value function = new Value.Function(new RuntimeDescriptor.Func(List.of(),
            RuntimeDescriptor.Null.INSTANCE, false));
        List<Value> unsupported = List.of(function,
            instanceOf(layoutOf(POINT, field("x", INT, true)), Map.of("x", new Value.Number(1))),
            Value.string("\uD800"), Value.Missing.INSTANCE);
        List<String> tokens = List.of("function", "@corpus.json/Point", "invalid-unicode", "nil");
        for (int i = 0; i < unsupported.size(); i++) {
            assertStringifyFailure(rawTable(Map.of("k", unsupported.get(i))),
                "data", "data.k", tokens.get(i), false);
        }
        SemanticTable<Value> dotted = new SemanticTable<>();
        dotted.put("a", new Value.Array(SemanticArray.of(new Value.Number(1))));
        dotted.put("a.0", function);
        assertStringifyFailure(new Value.Table(dotted), "data", "data.a.0", "function", false);
        dotted.put("a", new Value.Array(SemanticArray.of(function)));
        dotted.put("a.0", new Value.Number(1));
        assertStringifyFailure(new Value.Table(dotted), "data", "data.a[0]", "function", false);
        assertStringifyFailure(rawTable(Map.of("a.0", new Value.Array(SemanticArray.of(function)))),
            "data", "data.a.0[0]", "function", false);
        SemanticTable<Value> cycle = new SemanticTable<>();
        cycle.put("self", new Value.Table(cycle));
        assertStringifyFailure(new Value.Table(cycle), "data", "data.self", "table", true);
        cycle = new SemanticTable<>();
        cycle.put("a", new Value.Array(SemanticArray.of(new Value.Table(cycle))));
        assertStringifyFailure(new Value.Table(cycle), "data", "data.a[0]", "table", true);
    }

    private static void assertStringifyFailure(Value value, String prefix,
            String path, String actual, boolean cycle) {
        JsonStringify result = JsonClassAlgorithmAdapter.stringifier().stringify(value, prefix);
        check(result instanceof JsonStringify.Failure f && f.fieldPath().equals(path)
                && f.actual().equals(actual) && f.cycle() == cycle,
            "literal stringify failure " + path + " / " + actual + " / " + cycle + ": " + result);
    }

    /** The carrier text of one valid-scalar string view (test-local). */
    private static String carrier(Value.String string) {
        return ((UnicodeScalars.Valid) string.scalar()).carrier();
    }

    // =========================================================================
    // (b) JSON_FROM_CLASS: the top-level object gate
    // =========================================================================

    private static void testFromJsonTopLevelGate() {
        System.out.println("-- fromJson: the top-level object gate --");

        ClassLayout point = layoutOf(POINT,
            field("x", INT, true),
            field("tag", STRING, true));
        ValueId jsonId = nextValue();
        SemanticOp op = jsonFromOp(point, jsonId);
        Map<OpId, SemanticOp> defaultOps = Map.of();
        Map<ClassId, ClassLayout> layouts = Map.of(POINT, point);
        List<String> log = new ArrayList<>();
        ScriptedBodyRunner runner = new ScriptedBodyRunner(log, Map.of(), null);

        // A JSON null returns language null.
        Value nullResult = ClassOpsExecutor.executeJsonFromClass(op,
            Map.of(jsonId, Value.string("null")), List.of(), defaultOps, layouts,
            JsonClassAlgorithmAdapter.parser(), missingFactorySeam(), runner);
        check(nullResult instanceof Value.Null, "a JSON null top level returns null");

        // A non-object scalar returns language null.
        Value scalarResult = ClassOpsExecutor.executeJsonFromClass(op,
            Map.of(jsonId, Value.string("42")), List.of(), defaultOps, layouts,
            JsonClassAlgorithmAdapter.parser(), missingFactorySeam(), runner);
        check(scalarResult instanceof Value.Null, "a scalar top level returns null");

        // A non-empty array returns language null.
        Value nonEmpty = ClassOpsExecutor.executeJsonFromClass(op,
            Map.of(jsonId, Value.string("[1]")), List.of(), defaultOps, layouts,
            JsonClassAlgorithmAdapter.parser(), missingFactorySeam(), runner);
        check(nonEmpty instanceof Value.Null, "a non-empty array top level returns null");

        Value emptyObject = ClassOpsExecutor.executeJsonFromClass(op,
            Map.of(jsonId, Value.string("{}")), List.of(), defaultOps, layouts,
            JsonClassAlgorithmAdapter.parser(), missingFactorySeam(), runner);
        check(emptyObject instanceof Value.Null,
            "{} with absent required no-default fields returns null (K-D9)");

        // The empty object document decodes through the omitted defaults:
        // with defaults, {} publishes the defaulted instance.
        ClassLayout defaulted = layoutOf(POINT,
            field("x", INT, true),
            field("tag", STRING, true));
        ValueId defaultJsonId = nextValue();
        SemanticOp defaultedOp = jsonFromOp(defaulted, defaultJsonId);
        SemanticOp xDefault = defaultOp(POINT, "x", nextValue(), INT);
        SemanticOp tagDefault = defaultOp(POINT, "tag", nextValue(), STRING);
        Map<OpId, Value> produced = new LinkedHashMap<>();
        produced.put(xDefault.opId(), new Value.Number(40));
        produced.put(tagDefault.opId(), Value.string("pt"));
        Map<OpId, SemanticOp> defaultedOps = Map.of(xDefault.opId(), xDefault,
            tagDefault.opId(), tagDefault);
        List<String> logDefaults = new ArrayList<>();
        ScriptedBodyRunner defaultsRunner = new ScriptedBodyRunner(logDefaults, produced,
            null);
        Value defaultedObject = ClassOpsExecutor.executeJsonFromClass(defaultedOp,
            Map.of(defaultJsonId, Value.string("{}")), List.of(xDefault.opId(),
                tagDefault.opId()), defaultedOps, Map.of(POINT, defaulted),
            JsonClassAlgorithmAdapter.parser(), missingFactorySeam(), defaultsRunner);
        check(defaultedObject instanceof Value.Class instance
                && fieldValue(instance, defaulted, "x") instanceof Value.Number intValue
                && intValue.value() == 40,
            "{} decodes as the defaulted instance (default x = 40)");
        // The [] document is not an object: an array of any length — the
        // empty array included — is a decode failure, never the defaulted
        // instance.
        List<String> logRejected = new ArrayList<>();
        ScriptedBodyRunner rejectedRunner = new ScriptedBodyRunner(logRejected, produced,
            null);
        Value rejectedArray = ClassOpsExecutor.executeJsonFromClass(defaultedOp,
            Map.of(defaultJsonId, Value.string("[]")), List.of(xDefault.opId(),
                tagDefault.opId()), defaultedOps, Map.of(POINT, defaulted),
            JsonClassAlgorithmAdapter.parser(), missingFactorySeam(), rejectedRunner);
        check(rejectedArray instanceof Value.Null,
            "[] returns null: the class helper accepts only a JSON object");
        check(logRejected.isEmpty(),
            "the rejected [] document runs no default: " + logRejected);
    }

    private static NestedClassFactory missingFactorySeam() {
        return (classId, providedFields) -> {
            throw new Defect("the missing-factory seam never resolves " + classId);
        };
    }

    // =========================================================================
    // (c) JSON_FROM_CLASS: syntax, extra-key, and decode failures
    // =========================================================================

    private static void testFromJsonFailures() {
        System.out.println("-- fromJson: null on every listed failure with no partial "
            + "instance --");

        ClassLayout point = layoutOf(POINT,
            field("x", INT, true),
            field("tag", STRING, true),
            field("note", new RuntimeDescriptor.Nullable(STRING), false));
        ValueId jsonId = nextValue();
        SemanticOp op = jsonFromOp(point, jsonId);
        SemanticOp xDefault = defaultOp(POINT, "x", nextValue(), INT);
        SemanticOp tagDefault = defaultOp(POINT, "tag", nextValue(), STRING);
        Map<OpId, Value> produced = new LinkedHashMap<>();
        produced.put(xDefault.opId(), new Value.Number(40));
        produced.put(tagDefault.opId(), Value.string("pt"));
        Map<OpId, SemanticOp> defaultOps = Map.of(xDefault.opId(), xDefault,
            tagDefault.opId(), tagDefault);
        List<OpId> childIds = List.of(xDefault.opId(), tagDefault.opId());
        Map<ClassId, ClassLayout> layouts = Map.of(POINT, point);

        // A syntax defect returns null (no DEAL failure).
        List<String> logSyntax = new ArrayList<>();
        ScriptedBodyRunner syntaxRunner = new ScriptedBodyRunner(logSyntax, produced, null);
        Value syntax = ClassOpsExecutor.executeJsonFromClass(op,
            Map.of(jsonId, Value.string("{oops")), childIds, defaultOps, layouts,
            JsonClassAlgorithmAdapter.parser(), missingFactorySeam(), syntaxRunner);
        check(syntax instanceof Value.Null, "a syntax defect returns null");
        check(syntaxRunner.calls() == 0, "no default runs after a syntax defect");

        // The extra-key gate: the first unknown key in document order
        // fails before any field decode or default runs.
        List<String> logKey = new ArrayList<>();
        ScriptedBodyRunner keyRunner = new ScriptedBodyRunner(logKey, produced, null);
        Value extraKey = ClassOpsExecutor.executeJsonFromClass(op,
            Map.of(jsonId, Value.string("{\"x\":1,\"alien\":true,\"tag\":\"t\"}")),
            childIds, defaultOps, layouts, JsonClassAlgorithmAdapter.parser(), missingFactorySeam(),
            keyRunner);
        check(extraKey instanceof Value.Null, "an unknown key returns null");
        check(keyRunner.calls() == 0, "no default runs after the extra-key gate");

        // A wrong-kind provided field fails without running defaults.
        List<String> logInt = new ArrayList<>();
        ScriptedBodyRunner intRunner = new ScriptedBodyRunner(logInt, produced, null);
        Value intFailure = ClassOpsExecutor.executeJsonFromClass(op,
            Map.of(jsonId, Value.string("{\"x\":\"bad\",\"tag\":\"t\"}")), childIds,
            defaultOps, layouts, JsonClassAlgorithmAdapter.parser(), missingFactorySeam(), intRunner);
        check(intFailure instanceof Value.Null,
            "a number field with a string carrier returns null");
        check(intRunner.calls() == 0,
            "a provided-field decode failure runs no defaults (parent D5)");

        // A JSON null on a non-nullable field fails.
        Value nullFailure = ClassOpsExecutor.executeJsonFromClass(op,
            Map.of(jsonId, Value.string("{\"x\":null,\"tag\":\"t\"}")), childIds,
            defaultOps, layouts, JsonClassAlgorithmAdapter.parser(), missingFactorySeam(),
            new ScriptedBodyRunner(new ArrayList<>(), produced, null));
        check(nullFailure instanceof Value.Null,
            "a JSON null on a non-nullable field returns null");

        // A nullable field accepts JSON null (the three-state present
        // null).
        Value presentNull = ClassOpsExecutor.executeJsonFromClass(op,
            Map.of(jsonId, Value.string("{\"x\":1,\"tag\":\"t\",\"note\":null}")),
            childIds, defaultOps, layouts, JsonClassAlgorithmAdapter.parser(), missingFactorySeam(),
            new ScriptedBodyRunner(new ArrayList<>(), produced, null));
        check(presentNull instanceof Value.Class instance
                && fieldState(instance, point, "note") instanceof FieldState.Present
                    present && present.value() instanceof Value.Null,
            "a nullable field decodes JSON null as present null");

        // An omitted optional field stays missing (the three-state
        // missing).
        Value missingNote = ClassOpsExecutor.executeJsonFromClass(op,
            Map.of(jsonId, Value.string("{\"x\":1,\"tag\":\"t\"}")), childIds,
            defaultOps, layouts, JsonClassAlgorithmAdapter.parser(), missingFactorySeam(),
            new ScriptedBodyRunner(new ArrayList<>(), produced, null));
        check(missingNote instanceof Value.Class instance
                && fieldState(instance, point, "note") instanceof FieldState.Missing,
            "an omitted optional field stays missing");

        // A table field accepts only a JSON object (docs/spec-v1.3.md:695):
        // an array-shaped value of any length fails, the empty array
        // included; the empty object is the positive control.
        ClassLayout withTable = layoutOf(POINT,
            field("data", TABLE, true));
        ValueId tableJsonId = nextValue();
        SemanticOp tableOp = jsonFromOp(withTable, tableJsonId);
        Value nonEmptyArray = ClassOpsExecutor.executeJsonFromClass(tableOp,
            Map.of(tableJsonId, Value.string("{\"data\":[1,2]}")), List.of(), Map.of(),
            Map.of(POINT, withTable), JsonClassAlgorithmAdapter.parser(), missingFactorySeam(),
            new ScriptedBodyRunner(new ArrayList<>(), Map.of(), null));
        check(nonEmptyArray instanceof Value.Null,
            "a non-empty array-shaped table field value returns null");
        Value emptyArrayTable = ClassOpsExecutor.executeJsonFromClass(tableOp,
            Map.of(tableJsonId, Value.string("{\"data\":[]}")), List.of(), Map.of(),
            Map.of(POINT, withTable), JsonClassAlgorithmAdapter.parser(), missingFactorySeam(),
            new ScriptedBodyRunner(new ArrayList<>(), Map.of(), null));
        check(emptyArrayTable instanceof Value.Null,
            "an empty array-shaped table field value returns null: a table"
                + " field's fromJson admits only a JSON object");
        Value emptyObjectTable = ClassOpsExecutor.executeJsonFromClass(tableOp,
            Map.of(tableJsonId, Value.string("{\"data\":{}}")), List.of(), Map.of(),
            Map.of(POINT, withTable), JsonClassAlgorithmAdapter.parser(), missingFactorySeam(),
            new ScriptedBodyRunner(new ArrayList<>(), Map.of(), null));
        check(emptyObjectTable instanceof Value.Class decodedObject
                && fieldValue(decodedObject, withTable, "data")
                    instanceof Value.Table emptyTable
                && emptyTable.table().keys().isEmpty(),
            "an empty JSON object decodes an empty table field");

        ClassLayout strict = layoutOf(POINT, field("x", INT, true));
        ValueId strictJsonId = nextValue();
        SemanticOp strictOp = jsonFromOp(strict, strictJsonId);
        Value noDefault = ClassOpsExecutor.executeJsonFromClass(strictOp,
            Map.of(strictJsonId, Value.string("{}")), List.of(), Map.of(),
            Map.of(POINT, strict), JsonClassAlgorithmAdapter.parser(), missingFactorySeam(),
            new ScriptedBodyRunner(new ArrayList<>(), Map.of(), null));
        check(noDefault instanceof Value.Null,
            "an absent required-present no-default key returns null (K-D9)");

        // A failing default child returns null with the completed
        // children's effects remaining (the log shows the earlier child
        // ran).
        List<String> logFailing = new ArrayList<>();
        ScriptedBodyRunner failingRunner = new ScriptedBodyRunner(logFailing, produced,
            tagDefault.opId());
        Value failingDefault = ClassOpsExecutor.executeJsonFromClass(op,
            Map.of(jsonId, Value.string("{}")), childIds, defaultOps, layouts,
            JsonClassAlgorithmAdapter.parser(), missingFactorySeam(), failingRunner);
        check(failingDefault instanceof Value.Null, "a failing default child returns null");
        check(logFailing.equals(List.of("default " + xDefault.opId(),
                "default " + tagDefault.opId())),
            "completed children's effects remain (x ran; tag ran and failed)");

        BodyRunner defectiveRunner = defaultOp -> {
            throw new Defect("a producer defect inside the default block of "
                + defaultOp.opId() + " — fail closed, never language null");
        };
        expectDefect(() -> ClassOpsExecutor.executeJsonFromClass(op,
                Map.of(jsonId, Value.string("{}")), childIds, defaultOps, layouts,
                JsonClassAlgorithmAdapter.parser(), missingFactorySeam(), defectiveRunner),
            "a body-runner Defect during a local default child fails closed "
                + "(never language null)");
    }

    // =========================================================================
    // (d) JSON_FROM_CLASS: the nested-class factory trigger
    // =========================================================================

    private static void testFromJsonNestedFactory() {
        System.out.println("-- fromJson: nested defaults through the nested class's "
            + "CLASS_FACTORY (K-D5 trigger (b)) --");

        // The owner module's nested class: an exported class with
        // defaulted required-present fields and a CLASS_FACTORY op.
        ClassLayout nested = layoutOf(NESTED,
            field("city", STRING, true),
            field("zip", INT, true));
        ValueId nestedFactoryResult = nextValue();
        OpId nestedCallerRef = nextOpId();
        SemanticOp nestedFactory = opWithId(nextOpId(), SemanticOpKind.CLASS_FACTORY,
            new KindPayload.ClassFactoryPayload(NESTED, List.of(), nestedCallerRef),
            nestedFactoryResult, new RuntimeDescriptor.Class(NESTED),
            FailurePolicyId.CLASS_CONSTRUCTION, null, nextOrigin(null));
        SemanticOp cityDefault = defaultOp(NESTED, "city", nextValue(), STRING);
        SemanticOp zipDefault = defaultOp(NESTED, "zip", nextValue(), INT);
        // Rebuild the factory payload with the real child list.
        nestedFactory = opWithId(nestedFactory.opId(), SemanticOpKind.CLASS_FACTORY,
            new KindPayload.ClassFactoryPayload(NESTED,
                List.of(cityDefault.opId(), zipDefault.opId()), nestedCallerRef),
            nestedFactoryResult, new RuntimeDescriptor.Class(NESTED),
            FailurePolicyId.CLASS_CONSTRUCTION, null, nestedFactory.origin());
        final SemanticOp rebuiltFactory = nestedFactory;
        Map<OpId, Value> ownerProduced = new LinkedHashMap<>();
        ownerProduced.put(cityDefault.opId(), Value.string("berlin"));
        ownerProduced.put(zipDefault.opId(), new Value.Number(10115));

        // The caller module's @jsonable class with a nested class field.
        ClassLayout person = layoutOf(POINT,
            field("name", STRING, true),
            field("home", new RuntimeDescriptor.Class(NESTED), true));
        ValueId jsonId = nextValue();
        SemanticOp op = jsonFromOp(person, jsonId);
        SemanticOp nameDefault = defaultOp(POINT, "name", nextValue(), STRING);
        Map<OpId, Value> produced = new LinkedHashMap<>();
        produced.put(nameDefault.opId(), Value.string("anon"));
        Map<OpId, SemanticOp> defaultOps = Map.of(nameDefault.opId(), nameDefault);
        Map<ClassId, ClassLayout> layouts = Map.of(POINT, person, NESTED, nested);

        List<String> log = new ArrayList<>();
        FactorySeam seam = new FactorySeam(log, op, Map.of(NESTED, nestedFactory),
            Map.of(cityDefault.opId(), cityDefault, zipDefault.opId(), zipDefault),
            layouts, ownerProduced);
        ScriptedBodyRunner runner = new ScriptedBodyRunner(log, produced, null);
        Value decoded = ClassOpsExecutor.executeJsonFromClass(op,
            Map.of(jsonId, Value.string("{\"home\":{}}")), List.of(nameDefault.opId()),
            defaultOps, layouts, JsonClassAlgorithmAdapter.parser(), seam, runner);

        check(decoded instanceof Value.Class instance
                && instance.classId().equals(POINT),
            "the nested-decode walk publishes the tagged caller instance");
        if (!(decoded instanceof Value.Class instance)) {
            return;
        }
        Value home = fieldValue(instance, person, "home");
        check(home instanceof Value.Class homeInstance
                && homeInstance.classId().equals(NESTED),
            "the nested field carries the tagged nested instance");
        if (!(home instanceof Value.Class homeInstance)) {
            return;
        }
        check(fieldValue(homeInstance, nested, "city") instanceof Value.String city
                && carrier(city).equals("berlin"),
            "the nested defaults evaluated in the declaring module's scope (city = "
                + "berlin)");
        check(fieldValue(homeInstance, nested, "zip") instanceof Value.Number zip
                && zip.value() == 10115,
            "the nested defaults evaluated in the declaring module's scope (zip = 10115)");
        check(fieldValue(instance, person, "name") instanceof Value.String name
                && carrier(name).equals("anon"),
            "the caller's own default child ran for the omitted name field");
        check(log.equals(List.of("nested-default " + cityDefault.opId(),
                "nested-default " + zipDefault.opId(), "default " + nameDefault.opId())),
            "nested owner children run once in declaration order before the caller default");

        // A nested decode failure returns null end-to-end.
        Value nestedFailure = ClassOpsExecutor.executeJsonFromClass(op,
            Map.of(jsonId, Value.string("{\"home\":{\"alien\":1}}")),
            List.of(nameDefault.opId()), defaultOps, layouts, JsonClassAlgorithmAdapter.parser(), seam,
            new ScriptedBodyRunner(new ArrayList<>(), produced, null));
        check(nestedFailure instanceof Value.Null,
            "a nested extra-key failure returns null end-to-end");

        // A nested class value is a JSON object, exactly the root gate's
        // own shape: an empty array is a validation failure returning
        // null, never the nested defaulted instance.
        List<String> logNestedArray = new ArrayList<>();
        Value nestedEmptyArray = ClassOpsExecutor.executeJsonFromClass(op,
            Map.of(jsonId, Value.string("{\"home\":[]}")),
            List.of(nameDefault.opId()), defaultOps, layouts, JsonClassAlgorithmAdapter.parser(), seam,
            new ScriptedBodyRunner(logNestedArray, produced, null));
        check(nestedEmptyArray instanceof Value.Null,
            "a nested [] class value returns null end-to-end: the nested walk accepts "
                + "only a JSON object");
        check(logNestedArray.isEmpty(),
            "the rejected nested [] value runs no nested default: " + logNestedArray);

        List<String> failingLog = new ArrayList<>();
        NestedClassFactory failingSeam = (classId, providedFields) -> {
            Outcome<Value> outcome = ClassOpsExecutor.executeClassFactory(rebuiltFactory,
                op, Map.of(cityDefault.opId(), cityDefault, zipDefault.opId(), zipDefault),
                layouts, providedFields, defaultOp -> {
                    failingLog.add("nested-default " + defaultOp.opId());
                    throw new IllegalStateException("scripted owner-side default-block "
                        + "failure of " + defaultOp.opId());
                });
            if (!(outcome instanceof Outcome.Success<Value> success)) {
                throw new IllegalStateException("unexpected factory failure terminal");
            }
            return success.value();
        };
        Value nestedDefaultFailure = ClassOpsExecutor.executeJsonFromClass(op,
            Map.of(jsonId, Value.string("{\"home\":{}}")), List.of(nameDefault.opId()),
            defaultOps, layouts, JsonClassAlgorithmAdapter.parser(), failingSeam, runner);
        check(nestedDefaultFailure instanceof Value.Null,
            "a failing nested-factory child returns null end-to-end (K-D8 step 6)");
        check(failingLog.equals(List.of("nested-default " + cityDefault.opId())),
            "the failing nested factory ran its first child before the failure "
                + "(completed children's effects remain)");

        NestedClassFactory defectiveSeam = (classId, providedFields) -> {
            throw new Defect("a producer defect of the nested factory seam — never a "
                + "walk failure");
        };
        expectDefect(() -> ClassOpsExecutor.executeJsonFromClass(op,
                Map.of(jsonId, Value.string("{\"home\":{}}")),
                List.of(nameDefault.opId()), defaultOps, layouts, JsonClassAlgorithmAdapter.parser(),
                defectiveSeam, new ScriptedBodyRunner(new ArrayList<>(), produced, null)),
            "a nested-factory producer Defect fails closed (never language null)");
    }

    // =========================================================================
    // (e) JSON_FROM_CLASS: no fixed nesting-depth bound
    // =========================================================================

    private static void testFromJsonDepthBound() {
        System.out.println("-- fromJson: deep finite documents have no fixed cap --");

        // A self-nested nullable class chain: 513 and 1200 nesting levels
        // both decode (resource exhaustion is never reported as a JSON
        // value failure).
        ClassLayout node = layoutOf(INNER,
            field("next", new RuntimeDescriptor.Nullable(new RuntimeDescriptor.Class(
                INNER)), false));
        Map<ClassId, ClassLayout> layouts = Map.of(INNER, node);
        ValueId jsonId = nextValue();
        SemanticOp op = jsonFromOp(node, jsonId);

        NestedClassFactory emptyFactory = (classId, providedFields) ->
            new Value.Class(classId, List.of(FieldState.Missing.INSTANCE));
        Value deep = nestedTable(513);
        Value decoded = ClassOpsExecutor.executeJsonFromClass(op,
            Map.of(jsonId, Value.string("unused")), List.of(), Map.of(), layouts,
            scriptedParser(deep), emptyFactory,
            new ScriptedBodyRunner(new ArrayList<>(), Map.of(), null));
        check(decoded instanceof Value.Class,
            "a 513-deep nested document decodes (the old bound is gone)");

        Value deeper = nestedTable(1200);
        Value deeperDecoded = ClassOpsExecutor.executeJsonFromClass(op,
            Map.of(jsonId, Value.string("unused")), List.of(), Map.of(), layouts,
            scriptedParser(deeper), emptyFactory,
            new ScriptedBodyRunner(new ArrayList<>(), Map.of(), null));
        check(deeperDecoded instanceof Value.Class,
            "a 1200-deep nested document decodes");

        // Table-field contents are unbounded too: a 600-deep table
        // subtree inside a table field decodes.
        ClassLayout withTable = layoutOf(POINT, field("data", TABLE, true));
        ValueId tableJsonId = nextValue();
        SemanticOp tableOp = jsonFromOp(withTable, tableJsonId);
        Map<ClassId, ClassLayout> tableLayouts = Map.of(POINT, withTable);
        NestedClassFactory noNested = (classId, providedFields) -> {
            throw new Defect("the table-content walk never resolves a nested "
                + "class");
        };
        Value tableDeep = ClassOpsExecutor.executeJsonFromClass(tableOp,
            Map.of(tableJsonId, Value.string("unused")), List.of(), Map.of(),
            tableLayouts, scriptedParser(rawTable(Map.of("data", nestedTableChain(600)))),
            noNested, new ScriptedBodyRunner(new ArrayList<>(), Map.of(), null));
        check(tableDeep instanceof Value.Class instance
                && fieldValue(instance, withTable, "data") instanceof Value.Table,
            "a 600-deep table subtree inside a table field decodes");
        Value mixedDeep = ClassOpsExecutor.executeJsonFromClass(tableOp,
            Map.of(tableJsonId, Value.string("unused")), List.of(), Map.of(),
            tableLayouts, scriptedParser(rawTable(Map.of("data", nestedMixedChain(600)))),
            noNested, new ScriptedBodyRunner(new ArrayList<>(), Map.of(), null));
        check(mixedDeep instanceof Value.Class,
            "a 600-deep mixed table/array subtree inside a table field decodes");
    }

    /** A parsed nested document of the given depth: {"next":{"next":...{}}}. */
    private static Value nestedTable(int depth) {
        Value current = rawTable(Map.of());
        for (int i = 0; i < depth; i++) {
            current = rawTable(Map.of("next", current));
        }
        return current;
    }

    /**
     * A chain of nested single-key tables of the given nesting depth:
     * {@code {"k": {"k": ... {}}}} — the innermost table is empty.
     */
    private static Value nestedTableChain(int depth) {
        Value current = rawTable(Map.of());
        for (int i = 0; i < depth; i++) {
            current = rawTable(Map.of("k", current));
        }
        return current;
    }

    /**
     * A chain of alternating table/array containers of the given
     * nesting depth: {@code {"k": [{"k": [...]}]}} — the outermost
     * container is a table, then an array, alternating inward.
     */
    private static Value nestedMixedChain(int depth) {
        Value current = rawTable(Map.of());
        for (int i = depth - 1; i >= 0; i--) {
            current = (i % 2 == 0)
                ? rawTable(Map.of("k", current))
                : new Value.Array(SemanticArray.of(current));
        }
        return current;
    }

    /** A scripted parse seam returning the fixed parsed value. */
    private static ClassOpsExecutor.JsonParser scriptedParser(Value parsed) {
        return text -> new JsonParse.Success(parsed);
    }

    // =========================================================================
    // (f) JSON_TO_CLASS: deterministic text and the three-state roundtrip
    // =========================================================================

    private static void testToJsonDeterministicText() {
        System.out.println("-- toJson: deterministic text, declared-field order, the "
            + "three-state roundtrip --");

        ClassLayout point = layoutOf(POINT,
            field("name", STRING, true),
            field("age", INT, true),
            field("note", new RuntimeDescriptor.Nullable(STRING), false));
        ValueId classId = nextValue();
        SemanticOp op = jsonToOp(point, classId);
        Map<ClassId, ClassLayout> layouts = Map.of(POINT, point);

        // A full instance: present null stays present null (JSON null),
        // omitted optionals stay missing (skipped).
        Value.Class instance = instanceOf(point, Map.of(
            "name", Value.string("a\"b"),
            "age", new Value.Number(42),
            "note", Value.Null.INSTANCE));
        Outcome<Value> outcome = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, instance), layouts, JsonClassAlgorithmAdapter.stringifier(),
            nextOrigin(null));
        check(outcome instanceof Outcome.Success<Value> success
                && success.value() instanceof Value.String text
                && carrier(text).equals(
                    "{\"name\":\"a\\\"b\",\"age\":42.0,\"note\":null}"),
            "the deterministic text carries declared fields in declaration order with "
                + "RFC-8259 escaping");
        check(!(outcome instanceof Outcome.Failure<?>), "no failure terminal");

        // An omitted optional field is skipped from the text.
        Value.Class missingNote = instanceOf(point, Map.of(
            "name", Value.string("n"),
            "age", new Value.Number(1)));
        Outcome<Value> skipped = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, missingNote), layouts, JsonClassAlgorithmAdapter.stringifier(),
            nextOrigin(null));
        check(skipped instanceof Outcome.Success<Value> success
                && success.value() instanceof Value.String text
                && carrier(text).equals("{\"name\":\"n\",\"age\":1.0}"),
            "an omitted optional field is skipped");

        // Determinism: repeated executions produce identical bytes.
        Outcome<Value> again = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, instance), layouts, JsonClassAlgorithmAdapter.stringifier(),
            nextOrigin(null));
        check(again instanceof Outcome.Success<Value> success
                && carrier((Value.String) success.value()).equals(
                    carrier((Value.String) ((Outcome.Success<Value>) outcome).value())),
            "repeated executions produce identical text (stateless determinism)");
    }

    // =========================================================================
    // (g) JSON_TO_CLASS: the exact template, the fieldPath convention, and
    //     the call-origin anchoring
    // =========================================================================

    private static void testToJsonFailureTemplateAndOrigin() {
        System.out.println("-- toJson: the exact template, the pinned fieldPath "
            + "convention, and the call-origin anchoring --");

        // The layout: name (string, required), age (int, required),
        // home (nested class), tags (array of int), data (table),
        // note (nullable string, optional).
        ClassLayout nested = layoutOf(NESTED,
            field("city", STRING, true));
        ClassLayout point = layoutOf(POINT,
            field("name", STRING, true),
            field("age", INT, true),
            field("home", new RuntimeDescriptor.Class(NESTED), true),
            field("tags", new RuntimeDescriptor.Array(INT), true),
            field("data", TABLE, true),
            field("note", new RuntimeDescriptor.Nullable(STRING), false));
        ValueId classId = nextValue();
        SemanticOp op = jsonToOp(point, classId);
        Map<ClassId, ClassLayout> layouts = Map.of(POINT, point, NESTED, nested);
        SourceOrigin callOrigin = nextOrigin(null);

        // A nested wrong identity: path "home", actual the carried
        // canonical class atom. The oracle walk is driven through the
        // production stringify adapter (the generated C$toJson seam) and
        // asserts the complete rendered walk tuple: E8001, the arm's own
        // template at path 'home', the supplied call origin (never the
        // generated body's synthetic anchor), no expected field, and the
        // carried canonical atom as the actual field.
        Value.Class wrongNested = instanceOf(point, Map.of(
            "name", Value.string("n"),
            "age", new Value.Number(1),
            "home", instanceOf(layoutOf(OTHER, field("city", STRING, true)),
                Map.of("city", Value.string("x"))),
            "tags", new Value.Array(SemanticArray.of(new Value.Number(0))),
            "data", new Value.Table(new SemanticTable<>())));
        Outcome<Value> nestedFailure = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, wrongNested), layouts,
            JsonClassAlgorithmAdapter.stringifier(), callOrigin);
        check(nestedFailure instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().code() == DiagnosticCode.E8001
                && failure.failure().failure().message().equals(
                    "value at home is not JSON serializable: @" + MOD.path()
                        + "/Other")
                && failure.failure().origin().equals(callOrigin)
                && !failure.failure().origin().equals(op.origin())
                && failure.failure().failure().expected() == null
                && failure.failure().failure().actual().equals(
                    "@" + MOD.path() + "/Other"),
            "a nested wrong identity fails through the production walk with the complete "
                + "walk tuple (E8001, the exact template at path 'home', the carried "
                + "canonical class atom, no expected field, and the supplied call origin, "
                + "never the generated body's synthetic anchor)");

        // An array element failure: path "tags[1]", actual "string".
        Value.Class badArray = instanceOf(point, Map.of(
            "name", Value.string("n"),
            "age", new Value.Number(1),
            "home", instanceOf(nested, Map.of("city", Value.string("c"))),
            "tags", new Value.Array(SemanticArray.of(new Value.Number(0), Value.string("x"))),
            "data", new Value.Table(new SemanticTable<>())));
        Outcome<Value> arrayFailure = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, badArray), layouts, JsonClassAlgorithmAdapter.stringifier(), callOrigin);
        check(arrayFailure instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().message().equals(
                    "value at tags[1] is not JSON serializable: string"),
            "an array element failure pins the f[0]-style path");

        // A table-key failure: path "data.k", actual "function".
        SemanticTable<Value> badData = new SemanticTable<>();
        badData.put("k", new Value.Function(new RuntimeDescriptor.Func(List.of(),
            RuntimeDescriptor.Null.INSTANCE, false)));
        Value.Class badTable = instanceOf(point, Map.of(
            "name", Value.string("n"),
            "age", new Value.Number(1),
            "home", instanceOf(nested, Map.of("city", Value.string("c"))),
            "tags", new Value.Array(SemanticArray.of(new Value.Number(0))),
            "data", new Value.Table(badData)));
        Outcome<Value> productionTableFailure = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, badTable), layouts, JsonClassAlgorithmAdapter.stringifier(),
            callOrigin);
        check(productionTableFailure instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().code() == DiagnosticCode.E8001
                && failure.failure().failure().message().equals(
                    "value at data.k is not JSON serializable: function")
                && failure.failure().origin().equals(callOrigin)
                && !failure.failure().origin().equals(op.origin())
                && failure.failure().failure().expected() == null
                && failure.failure().failure().actual().equals("function"),
            "the production adapter's walk projects the function-in-table failure at "
                + "data.k as the complete walk tuple (E8001, the function token as actual, "
                + "no expected field, and the call origin)");

        SemanticTable<Value> badClassData = new SemanticTable<>();
        badClassData.put("k", instanceOf(layoutOf(OTHER, field("x", INT, true)),
            Map.of("x", new Value.Number(1))));
        Value.Class classInTable = instanceOf(point, Map.of(
            "name", Value.string("n"),
            "age", new Value.Number(1),
            "home", instanceOf(nested, Map.of("city", Value.string("c"))),
            "tags", new Value.Array(SemanticArray.of(new Value.Number(0))),
            "data", new Value.Table(badClassData)));
        Outcome<Value> productionClassFailure = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, classInTable), layouts,
            JsonClassAlgorithmAdapter.stringifier(), callOrigin);
        check(productionClassFailure instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().code() == DiagnosticCode.E8001
                && failure.failure().failure().message().equals(
                    "value at data.k is not JSON serializable: @" + MOD.path()
                        + "/Other")
                && failure.failure().origin().equals(callOrigin)
                && !failure.failure().origin().equals(op.origin())
                && failure.failure().failure().expected() == null
                && failure.failure().failure().actual().equals(
                    "@" + MOD.path() + "/Other"),
            "the production adapter's walk projects the class-in-table failure at data.k "
                + "as the complete walk tuple (E8001, the carried canonical class atom as "
                + "actual, no expected field, and the call origin)");

        SemanticTable<Value> badInvalidData = new SemanticTable<>();
        badInvalidData.put("k", Value.string("\uD800"));
        Value.Class invalidInTable = instanceOf(point, Map.of(
            "name", Value.string("n"),
            "age", new Value.Number(1),
            "home", instanceOf(nested, Map.of("city", Value.string("c"))),
            "tags", new Value.Array(SemanticArray.of(new Value.Number(0))),
            "data", new Value.Table(badInvalidData)));
        Outcome<Value> productionInvalidFailure = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, invalidInTable), layouts,
            JsonClassAlgorithmAdapter.stringifier(), callOrigin);
        check(productionInvalidFailure instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().code() == DiagnosticCode.E8001
                && failure.failure().failure().message().equals(
                    "value at data.k is not JSON serializable: invalid-unicode")
                && failure.failure().origin().equals(callOrigin)
                && !failure.failure().origin().equals(op.origin())
                && failure.failure().failure().expected() == null
                && failure.failure().failure().actual().equals("invalid-unicode"),
            "the production adapter's walk projects the invalid-string-in-table failure "
                + "at data.k as the complete walk tuple (E8001, the invalid-unicode token "
                + "as actual, no expected field, and the call origin)");

        SemanticTable<Value> badMissingData = new SemanticTable<>();
        badMissingData.put("k", Value.Missing.INSTANCE);
        Value.Class missingInTable = instanceOf(point, Map.of(
            "name", Value.string("n"),
            "age", new Value.Number(1),
            "home", instanceOf(nested, Map.of("city", Value.string("c"))),
            "tags", new Value.Array(SemanticArray.of(new Value.Number(0))),
            "data", new Value.Table(badMissingData)));
        Outcome<Value> productionMissingFailure = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, missingInTable), layouts,
            JsonClassAlgorithmAdapter.stringifier(), callOrigin);
        check(productionMissingFailure instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().code() == DiagnosticCode.E8001
                && failure.failure().failure().message().equals(
                    "value at data.k is not JSON serializable: nil")
                && failure.failure().origin().equals(callOrigin)
                && !failure.failure().origin().equals(op.origin())
                && failure.failure().failure().expected() == null
                && failure.failure().failure().actual().equals("nil"),
            "the production adapter's walk projects the missing-in-table failure at "
                + "data.k as the complete walk tuple (E8001, the typed-boundary nil token "
                + "as actual, no expected field, and the call origin)");

        // A dotted table key: E8's dot-joined failure path is ambiguous
        // ({"a": [1], "a.0": <function>} reports "a.0", which is the
        // key "a.0", not the array element 0 under key "a"), so the
        // pinned convention resolves the failure against the actual
        // structure — the generated C$toJson production walk pins
        // data.a.0 (a table key always appends .key) at the call origin.
        SemanticTable<Value> dottedData = new SemanticTable<>();
        dottedData.put("a", new Value.Array(SemanticArray.of(new Value.Number(1))));
        dottedData.put("a.0", new Value.Function(new RuntimeDescriptor.Func(List.of(),
            RuntimeDescriptor.Null.INSTANCE, false)));
        Value.Class dottedInTable = instanceOf(point, Map.of(
            "name", Value.string("n"),
            "age", new Value.Number(1),
            "home", instanceOf(nested, Map.of("city", Value.string("c"))),
            "tags", new Value.Array(SemanticArray.of(new Value.Number(0))),
            "data", new Value.Table(dottedData)));
        Outcome<Value> dottedFailure = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, dottedInTable), layouts, JsonClassAlgorithmAdapter.stringifier(),
            callOrigin);
        check(dottedFailure instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().message().equals(
                    "value at data.a.0 is not JSON serializable: function")
                && failure.failure().origin().equals(callOrigin),
            "the production adapter's walk projects the dotted-key failure at data.a.0 "
                + "(a table key always appends .key, never [0]) at the call origin");

        // The mirror ambiguity through the same walk: when the first
        // failure is the array element under key "a" and a dotted
        // sibling key "a.0" also exists, the pinned path stays data.a[0]
        // (the segment spelling is the parent container's kind).
        SemanticTable<Value> dottedArrayWalkData = new SemanticTable<>();
        dottedArrayWalkData.put("a", new Value.Array(SemanticArray.of(
            new Value.Function(new RuntimeDescriptor.Func(List.of(),
                RuntimeDescriptor.Null.INSTANCE, false)))));
        dottedArrayWalkData.put("a.0", new Value.Number(1));
        Value.Class dottedArrayInTable = instanceOf(point, Map.of(
            "name", Value.string("n"),
            "age", new Value.Number(1),
            "home", instanceOf(nested, Map.of("city", Value.string("c"))),
            "tags", new Value.Array(SemanticArray.of(new Value.Number(0))),
            "data", new Value.Table(dottedArrayWalkData)));
        Outcome<Value> dottedArrayFailure = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, dottedArrayInTable), layouts,
            JsonClassAlgorithmAdapter.stringifier(), callOrigin);
        check(dottedArrayFailure instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().message().equals(
                    "value at data.a[0] is not JSON serializable: function")
                && failure.failure().origin().equals(callOrigin),
            "the production adapter's walk projects the array-element failure at "
                + "data.a[0] even with a dotted sibling key present, at the call origin");

        // A table key containing braces or the literal spelling of a
        // placeholder: string keys are unrestricted, so the pinned fieldPath
        // carries the key verbatim and the arm's parameter substitution
        // publishes it byte-for-byte — never a producer defect and never a
        // placeholder reinterpretation. The failure's fieldPath metadata is
        // the literal key.
        for (String key : List.of("{bad}", "{actual}", "{fieldPath}", "a}b", "a{b")) {
            String bracePath = "data." + key;
            SemanticTable<Value> braceData = new SemanticTable<>();
            braceData.put(key, new Value.Function(new RuntimeDescriptor.Func(List.of(),
                RuntimeDescriptor.Null.INSTANCE, false)));
            Value.Class braceInTable = instanceOf(point, Map.of(
                "name", Value.string("n"),
                "age", new Value.Number(1),
                "home", instanceOf(nested, Map.of("city", Value.string("c"))),
                "tags", new Value.Array(SemanticArray.of(new Value.Number(0))),
                "data", new Value.Table(braceData)));
            Outcome<Value> braceFailure = ClassOpsExecutor.executeJsonToClass(op,
                Map.of(classId, braceInTable), layouts,
                JsonClassAlgorithmAdapter.stringifier(), callOrigin);
            check(braceFailure instanceof Outcome.Failure<Value> failure
                    && failure.failure().failure().code() == DiagnosticCode.E8001
                    && failure.failure().failure().message().equals(
                        "value at " + bracePath + " is not JSON serializable: function")
                    && failure.failure().origin().equals(callOrigin)
                    && failure.failure().failure().expected() == null
                    && failure.failure().failure().actual().equals("function")
                    && bracePath.equals(
                        failure.failure().failure().metadata().get("fieldPath")),
                "the production adapter's walk publishes the literal fieldPath '" + key
                    + "' (E8001, the exact message, the call origin, no expected "
                    + "field, the function token, and the literal fieldPath metadata)");
        }

        // A nested-class-field failure under a path: path "home.city".
        Value.Class badNestedField = instanceOf(point, Map.of(
            "name", Value.string("n"),
            "age", new Value.Number(1),
            "home", instanceOf(nested, Map.of("city", new Value.Number(9))),
            "tags", new Value.Array(SemanticArray.of(new Value.Number(0))),
            "data", new Value.Table(new SemanticTable<>())));
        Outcome<Value> deepPath = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, badNestedField), layouts, JsonClassAlgorithmAdapter.stringifier(),
            callOrigin);
        check(deepPath instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().message().equals(
                    "value at home.city is not JSON serializable: number"),
            "a nested class field failure pins the f.g path");

        // The first failure wins in declaration order (age before home).
        Value.Class twoFailures = instanceOf(point, Map.of(
            "name", Value.string("n"),
            "age", Value.string("bad"),
            "home", instanceOf(layoutOf(OTHER, field("city", STRING, true)),
                Map.of("city", Value.string("x"))),
            "tags", new Value.Array(SemanticArray.of(new Value.Number(0))),
            "data", new Value.Table(new SemanticTable<>())));
        Outcome<Value> firstWins = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, twoFailures), layouts, JsonClassAlgorithmAdapter.stringifier(), callOrigin);
        check(firstWins instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().message().equals(
                    "value at age is not JSON serializable: string"),
            "the first declaration-order failure wins (age before home)");

        // The root identity check: path "", actual class:<other>.
        Value.Class foreignRoot = instanceOf(layoutOf(OTHER, field("x", INT, true)),
            Map.of("x", new Value.Number(1)));
        Outcome<Value> rootFailure = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, foreignRoot), layouts, JsonClassAlgorithmAdapter.stringifier(), callOrigin);
        check(rootFailure instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().message().equals(
                    "value at  is not JSON serializable: @" + MOD.path()
                        + "/Other")
                && failure.failure().origin().equals(callOrigin)
                && !failure.failure().origin().equals(op.origin()),
            "a wrong root identity fails at the empty root path, at the call origin "
                + "(never the generated body's synthetic anchor)");

        // A non-class root: actual number.
        Outcome<Value> scalarRoot = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, new Value.Number(3.5)), layouts, JsonClassAlgorithmAdapter.stringifier(),
            callOrigin);
        check(scalarRoot instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().message().equals(
                    "value at  is not JSON serializable: number"),
            "a non-class root fails with its actual-kind token");

        // A nonfinite number fails with the number token.
        ClassLayout nanLayout = layoutOf(POINT, field("x", NUMBER, true));
        ValueId nanClassId = nextValue();
        SemanticOp nanOp = jsonToOp(nanLayout, nanClassId);
        Value.Class nanField = instanceOf(nanLayout,
            Map.of("x", new Value.Number(Double.NaN)));
        Outcome<Value> nanFailure = ClassOpsExecutor.executeJsonToClass(nanOp,
            Map.of(nanClassId, nanField), Map.of(POINT, nanLayout),
            JsonClassAlgorithmAdapter.stringifier(), callOrigin);
        check(nanFailure instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().message().equals(
                    "value at x is not JSON serializable: number"),
            "a nonfinite number fails with the number token");

        // A missing required field: path "name", actual "missing".
        Value.Class missingRequired = instanceOf(point, Map.of(
            "age", new Value.Number(1),
            "home", instanceOf(nested, Map.of("city", Value.string("c"))),
            "tags", new Value.Array(SemanticArray.of(new Value.Number(0))),
            "data", new Value.Table(new SemanticTable<>())));
        Outcome<Value> missingFailure = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, missingRequired), layouts, JsonClassAlgorithmAdapter.stringifier(),
            callOrigin);
        check(missingFailure instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().message().equals(
                    "value at name is not JSON serializable: nil"),
            "a missing required field fails with the typed-boundary nil token");

        // A null on a non-nullable field: actual null.
        Value.Class nullRequired = instanceOf(point, Map.of(
            "name", Value.Null.INSTANCE,
            "age", new Value.Number(1),
            "home", instanceOf(nested, Map.of("city", Value.string("c"))),
            "tags", new Value.Array(SemanticArray.of(new Value.Number(0))),
            "data", new Value.Table(new SemanticTable<>())));
        Outcome<Value> nullFailure = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, nullRequired), layouts, JsonClassAlgorithmAdapter.stringifier(), callOrigin);
        check(nullFailure instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().message().equals(
                    "value at name is not JSON serializable: null"),
            "a null on a non-nullable field fails with the null token");
    }

    // =========================================================================
    // (h) JSON_TO_CLASS: cycles and deep finite nesting
    // =========================================================================

    private static void testToJsonCyclesAndDepth() {
        System.out.println("-- toJson: cycles (class and table) and deep "
            + "finite nesting --");

        ClassLayout node = layoutOf(INNER,
            field("next", new RuntimeDescriptor.Nullable(new RuntimeDescriptor.Class(
                INNER)), false));
        Map<ClassId, ClassLayout> layouts = Map.of(INNER, node);

        // The class cycle seen-set discipline is defensive (the strict
        // immutable Value.Class constructor cannot build a cyclic class
        // graph — the seen-set guards oracle/host-produced cyclic heaps),
        // so the observable pin is the path-local discipline: a shared
        // nested instance under two fields serializes twice (the set
        // removes on exit; sharing is not a cycle).
        Value.Class sharedInner = new Value.Class(INNER,
            List.of(FieldState.Missing.INSTANCE));
        ClassLayout twoFields = layoutOf(POINT,
            field("a", new RuntimeDescriptor.Class(INNER), true),
            field("b", new RuntimeDescriptor.Class(INNER), true));
        Value.Class shared = instanceOf(twoFields, Map.of(
            "a", sharedInner, "b", sharedInner));
        ValueId sharedClassId = nextValue();
        SemanticOp sharedOp = jsonToOp(twoFields, sharedClassId);
        Outcome<Value> sharedOutcome = ClassOpsExecutor.executeJsonToClass(sharedOp,
            Map.of(sharedClassId, shared), Map.of(POINT, twoFields, INNER, node),
            JsonClassAlgorithmAdapter.stringifier(), nextOrigin(null));
        check(sharedOutcome instanceof Outcome.Success<Value> success
                && success.value() instanceof Value.String text
                && carrier(text).equals("{\"a\":{},\"b\":{}}"),
            "a shared nested instance serializes at both positions (the path-local "
                + "seen-set removes on exit)");

        // A table cycle: the table holds itself under a key (the seam's
        // path-local cycle detection).
        ClassLayout withTable = layoutOf(POINT, field("data", TABLE, true));
        SemanticTable<Value> cyclicTable = new SemanticTable<>();
        Value.Table tableValue = new Value.Table(cyclicTable);
        cyclicTable.put("self", tableValue);
        Value.Class tableCycle = instanceOf(withTable, Map.of("data", tableValue));
        ValueId tableClassId = nextValue();
        SemanticOp tableCycleOp = jsonToOp(withTable, tableClassId);
        Outcome<Value> tableCycleFailure = ClassOpsExecutor.executeJsonToClass(
            tableCycleOp, Map.of(tableClassId, tableCycle), Map.of(POINT, withTable),
            JsonClassAlgorithmAdapter.stringifier(), nextOrigin(null));
        check(tableCycleFailure instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().message().equals(
                    "cyclic value cannot be encoded as JSON")
                && failure.failure().failure().code() == DiagnosticCode.E8001
                && failure.failure().failure().expected() == null
                && failure.failure().failure().actual() == null,
            "a cyclic table fails the walk family's cycle arm (E8001, the pinned cycle "
                + "text, no expected/actual)");

        // The production adapter over the same cyclic table: the
        // pre-walk short-circuits the re-entry before any mapping, so
        // the generated C$toJson production walk selects the same cycle
        // arm (never a producer crash), and the walk's internal position
        // stays its own metadata.
        Outcome<Value> tableCycleAdapter = ClassOpsExecutor.executeJsonToClass(
            tableCycleOp, Map.of(tableClassId, tableCycle), Map.of(POINT, withTable),
            JsonClassAlgorithmAdapter.stringifier(), nextOrigin(null));
        check(tableCycleAdapter instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().message().equals(
                    "cyclic value cannot be encoded as JSON")
                && failure.failure().failure().code() == DiagnosticCode.E8001
                && failure.failure().failure().expected() == null
                && failure.failure().failure().actual() == null,
            "the production adapter fails the cyclic table on the cycle arm (never a "
                + "producer crash)");

        // No fixed nesting cap: a 513-deep and a 1200-deep class nesting
        // both serialize (resource exhaustion is never reported as a JSON
        // value failure).
        ValueId deepClassId = nextValue();
        SemanticOp deepOp = jsonToOp(node, deepClassId);
        Value.Class deepInstance = emptyNode();
        for (int i = 0; i < 513; i++) {
            deepInstance = new Value.Class(INNER,
                List.of(new FieldState.Present(deepInstance)));
        }
        SourceOrigin deepCallOrigin = nextOrigin(null);
        Outcome<Value> deepOutcome = ClassOpsExecutor.executeJsonToClass(deepOp,
            Map.of(deepClassId, deepInstance), layouts, JsonClassAlgorithmAdapter.stringifier(),
            deepCallOrigin);
        check(deepOutcome instanceof Outcome.Success<Value> success
                && success.value() instanceof Value.String text
                && text.scalar() instanceof UnicodeScalars.Valid valid
                && valid.carrier().startsWith("{\"next\":")
                && valid.carrier().endsWith("}"),
            "a 513-deep class nesting serializes (no fixed cap)");
        Value.Class deeperInstance = emptyNode();
        for (int i = 0; i < 1200; i++) {
            deeperInstance = new Value.Class(INNER,
                List.of(new FieldState.Present(deeperInstance)));
        }
        Outcome<Value> deeperOutcome = ClassOpsExecutor.executeJsonToClass(deepOp,
            Map.of(deepClassId, deeperInstance), layouts,
            JsonClassAlgorithmAdapter.stringifier(), nextOrigin(null));
        check(deeperOutcome instanceof Outcome.Success<Value>,
            "a 1200-deep class nesting serializes");

        // Table-field contents have no cap either: a 600-deep table
        // subtree serializes, and a 600-deep mixed table/array subtree
        // serializes.
        ClassLayout tableLayout = layoutOf(POINT, field("data", TABLE, true));
        Value.Class deepTable = instanceOf(tableLayout,
            Map.of("data", nestedTableChain(600)));
        ValueId deepTableClassId = nextValue();
        SemanticOp deepTableOp = jsonToOp(tableLayout, deepTableClassId);
        SourceOrigin tableDepthOrigin = nextOrigin(null);
        Outcome<Value> tableDeep = ClassOpsExecutor.executeJsonToClass(deepTableOp,
            Map.of(deepTableClassId, deepTable), Map.of(POINT, tableLayout),
            JsonClassAlgorithmAdapter.stringifier(), tableDepthOrigin);
        check(tableDeep instanceof Outcome.Success<Value> success
                && success.value() instanceof Value.String,
            "a 600-deep table subtree serializes");
        Value.Class deepMixed = instanceOf(tableLayout,
            Map.of("data", nestedMixedChain(600)));
        ValueId deepMixedClassId = nextValue();
        SemanticOp deepMixedOp = jsonToOp(tableLayout, deepMixedClassId);
        Outcome<Value> mixedDeep = ClassOpsExecutor.executeJsonToClass(deepMixedOp,
            Map.of(deepMixedClassId, deepMixed), Map.of(POINT, tableLayout),
            JsonClassAlgorithmAdapter.stringifier(), nextOrigin(null));
        check(mixedDeep instanceof Outcome.Success<Value> success
                && success.value() instanceof Value.String,
            "a 600-deep mixed table/array subtree serializes");

        // The first failing position still decides the walk in walk order,
        // never a preflight: a table whose first key holds a nonfinite
        // number and whose second key holds a 600-deep chain fails at the
        // earlier nonfinite position.
        SemanticTable<Value> earlierFailureThenDeep = new SemanticTable<>();
        earlierFailureThenDeep.put("bad", new Value.Number(Double.NaN));
        earlierFailureThenDeep.put("k", nestedTableChain(600));
        Value.Class shallowFailure = instanceOf(tableLayout,
            Map.of("data", new Value.Table(earlierFailureThenDeep)));
        ValueId shallowFailureClassId = nextValue();
        SemanticOp shallowFailureOp = jsonToOp(tableLayout, shallowFailureClassId);
        SourceOrigin shallowFailureOrigin = nextOrigin(null);
        Outcome<Value> earlierPositionFailure = ClassOpsExecutor.executeJsonToClass(
            shallowFailureOp, Map.of(shallowFailureClassId, shallowFailure),
            Map.of(POINT, tableLayout), JsonClassAlgorithmAdapter.stringifier(),
            shallowFailureOrigin);
        check(earlierPositionFailure instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().code() == DiagnosticCode.E8001
                && failure.failure().failure().message().equals(
                    "value at data.bad is not JSON serializable: number")
                && failure.failure().failure().expected() == null
                && failure.failure().failure().actual().equals("number")
                && "data.bad".equals(
                    failure.failure().failure().metadata().get("fieldPath"))
                && failure.failure().origin().equals(shallowFailureOrigin)
                && !failure.failure().origin().equals(shallowFailureOp.origin()),
            "a nonfinite value before a 600-deep chain fails the walk at the earlier "
                + "position with the pinned walk tuple and the supplied call origin");

        // The same graph with the deep chain first now serializes: with no
        // fixed cap there is no depth failure to precede the later
        // nonfinite value.
        SemanticTable<Value> deepThenLaterFailure = new SemanticTable<>();
        deepThenLaterFailure.put("k", nestedTableChain(600));
        deepThenLaterFailure.put("zbad", new Value.Number(Double.NaN));
        Value.Class deepFirst = instanceOf(tableLayout,
            Map.of("data", new Value.Table(deepThenLaterFailure)));
        ValueId deepFirstClassId = nextValue();
        SemanticOp deepFirstOp = jsonToOp(tableLayout, deepFirstClassId);
        Outcome<Value> deepFirstFailure = ClassOpsExecutor.executeJsonToClass(
            deepFirstOp, Map.of(deepFirstClassId, deepFirst),
            Map.of(POINT, tableLayout), JsonClassAlgorithmAdapter.stringifier(),
            nextOrigin(null));
        check(deepFirstFailure instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().code() == DiagnosticCode.E8001
                && failure.failure().failure().message().equals(
                    "value at data.zbad is not JSON serializable: number")
                && failure.failure().failure().actual().equals("number"),
            "a 600-deep chain before a nonfinite value keeps walk order and fails at "
                + "the later nonfinite position (there is no depth bound to fire)");
    }

    // =========================================================================
    // (h2) JSON_TO_CLASS: a selected noncycle failure with a cycle behind it
    //      (the acceptance remediation of the eager mapping)
    // =========================================================================

    private static void testToJsonNoncycleFailureBeforeCycle() {
        System.out.println("-- toJson: a selected noncycle failure before a path-local "
            + "cycle (table, mixed table/array, and the cycle-first control) --");

        ClassLayout tableLayout = layoutOf(POINT, field("data", TABLE, true));
        ValueId classId = nextValue();
        SemanticOp op = jsonToOp(tableLayout, classId);
        Map<ClassId, ClassLayout> layouts = Map.of(POINT, tableLayout);

        // The table-typed field holds the unsupported function under 'bad'
        // and the table's own path-local re-entry under 'self': the first
        // failure precedes the cycle, so the walk (and the production
        // adapter's mapping of it) must select the walk arm at data.bad
        // without ever descending into the later cyclic contents.
        SemanticTable<Value> fnThenCycle = new SemanticTable<>();
        Value.Table fnThenCycleTable = new Value.Table(fnThenCycle);
        fnThenCycle.put("bad", functionValue());
        fnThenCycle.put("self", fnThenCycleTable);
        JsonStringify adapterCarrier = JsonClassAlgorithmAdapter.stringifier()
            .stringify(fnThenCycleTable, "data");
        check(adapterCarrier instanceof JsonStringify.Failure failure
                && !failure.cycle()
                && failure.fieldPath().equals("data.bad")
                && failure.actual().equals("function"),
            "the production adapter's mapping of an unsupported value before a table "
                + "cycle publishes the walk arm's failure carrier at data.bad with the "
                + "function token (never a mapping crash)");
        SourceOrigin tableCycleOrigin = nextOrigin(null);
        Value.Class fnThenCycleInstance = instanceOf(tableLayout,
            Map.of("data", fnThenCycleTable));
        Outcome<Value> tableCycleWalk = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, fnThenCycleInstance), layouts,
            JsonClassAlgorithmAdapter.stringifier(), tableCycleOrigin);
        check(tableCycleWalk instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().code() == DiagnosticCode.E8001
                && failure.failure().failure().message().equals(
                    "value at data.bad is not JSON serializable: function")
                && failure.failure().failure().expected() == null
                && failure.failure().failure().actual().equals("function")
                && "data.bad".equals(
                    failure.failure().failure().metadata().get("fieldPath"))
                && failure.failure().origin().equals(tableCycleOrigin)
                && !failure.failure().origin().equals(op.origin()),
            "the production adapter's class walk renders the complete walk tuple for "
                + "an unsupported value before a table cycle (E8001, the exact message "
                + "at data.bad, no expected field, the function token, the literal "
                + "fieldPath metadata, and the supplied call origin, never the "
                + "generated body's synthetic anchor)");

        // The same shape with the cycle behind the failure closed through a
        // mixed table/array path: 'mix' holds an array whose element is the
        // containing table again.
        SemanticTable<Value> fnThenMixed = new SemanticTable<>();
        Value.Table fnThenMixedTable = new Value.Table(fnThenMixed);
        fnThenMixed.put("bad", functionValue());
        fnThenMixed.put("mix", new Value.Array(
            SemanticArray.of(fnThenMixedTable)));
        JsonStringify mixedCarrier = JsonClassAlgorithmAdapter.stringifier()
            .stringify(fnThenMixedTable, "data");
        check(mixedCarrier instanceof JsonStringify.Failure failure
                && !failure.cycle()
                && failure.fieldPath().equals("data.bad")
                && failure.actual().equals("function"),
            "the production adapter's mapping of an unsupported value before a mixed "
                + "table/array cycle publishes the walk arm's failure carrier at "
                + "data.bad with the function token (never a mapping crash)");
        SourceOrigin mixedCycleOrigin = nextOrigin(null);
        Outcome<Value> mixedCycleWalk = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, instanceOf(tableLayout, Map.of("data", fnThenMixedTable))),
            layouts, JsonClassAlgorithmAdapter.stringifier(), mixedCycleOrigin);
        check(mixedCycleWalk instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().code() == DiagnosticCode.E8001
                && failure.failure().failure().message().equals(
                    "value at data.bad is not JSON serializable: function")
                && failure.failure().failure().expected() == null
                && failure.failure().failure().actual().equals("function")
                && "data.bad".equals(
                    failure.failure().failure().metadata().get("fieldPath"))
                && failure.failure().origin().equals(mixedCycleOrigin)
                && !failure.failure().origin().equals(op.origin()),
            "the production adapter's class walk renders the complete walk tuple for "
                + "an unsupported value before a mixed table/array cycle (E8001, the "
                + "exact message at data.bad, no expected field, the function token, "
                + "the literal fieldPath metadata, and the supplied call origin)");

        // The cycle-first control over the same graph shape: the re-entry
        // under 'self' precedes the unsupported value under 'zbad', so the
        // needle's own closed marker still selects the cycle arm with no
        // parameters, expected or actual — the arm selection is the walk's
        // first failure, never the presence of an unsupported value.
        SemanticTable<Value> cycleThenFn = new SemanticTable<>();
        Value.Table cycleThenFnTable = new Value.Table(cycleThenFn);
        cycleThenFn.put("self", cycleThenFnTable);
        cycleThenFn.put("zbad", functionValue());
        JsonStringify cycleCarrier = JsonClassAlgorithmAdapter.stringifier()
            .stringify(cycleThenFnTable, "data");
        check(cycleCarrier instanceof JsonStringify.Failure failure
                && failure.cycle()
                && failure.actual().equals("table"),
            "the production adapter keeps the cycle needle's own marker as the arm "
                + "selection when an unsupported value follows the re-entry");
        SourceOrigin cycleFirstOrigin = nextOrigin(null);
        Outcome<Value> cycleFirstWalk = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, instanceOf(tableLayout, Map.of("data", cycleThenFnTable))),
            layouts, JsonClassAlgorithmAdapter.stringifier(), cycleFirstOrigin);
        check(cycleFirstWalk instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().code() == DiagnosticCode.E8001
                && failure.failure().failure().message().equals(
                    "cyclic value cannot be encoded as JSON")
                && failure.failure().failure().expected() == null
                && failure.failure().failure().actual() == null
                && failure.failure().failure().metadata().isEmpty()
                && failure.failure().origin().equals(cycleFirstOrigin)
                && !failure.failure().origin().equals(op.origin()),
            "the production adapter's class walk keeps the cycle arm's parameterless "
                + "tuple when an unsupported value follows the re-entry (E8001, the "
                + "cycle text, no expected/actual/metadata, and the supplied call "
                + "origin)");

        // An array element failure before a cycle reached through a later
        // element: element 0 holds the unsupported function, and element 1's
        // table re-enters the containing array, so the first failure is the
        // element itself and the cyclic sibling behind it stays unreachable.
        ClassLayout arrayLayout = layoutOf(POINT,
            field("data", new RuntimeDescriptor.Array(TABLE), true));
        ValueId arrayClassId = nextValue();
        SemanticOp arrayOp = jsonToOp(arrayLayout, arrayClassId);
        SemanticTable<Value> firstElement = new SemanticTable<>();
        Value.Table firstElementTable = new Value.Table(firstElement);
        firstElement.put("bad", functionValue());
        SemanticTable<Value> backReference = new SemanticTable<>();
        Value.Table backReferenceTable = new Value.Table(backReference);
        Value.Array elements = new Value.Array(
            SemanticArray.of(firstElementTable, backReferenceTable));
        backReference.put("x", elements);
        SourceOrigin elementOrigin = nextOrigin(null);
        Outcome<Value> elementWalk = ClassOpsExecutor.executeJsonToClass(arrayOp,
            Map.of(arrayClassId, instanceOf(arrayLayout, Map.of("data", elements))),
            Map.of(POINT, arrayLayout), JsonClassAlgorithmAdapter.stringifier(),
            elementOrigin);
        check(elementWalk instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().message().equals(
                    "value at data[0].bad is not JSON serializable: function")
                && failure.failure().failure().expected() == null
                && failure.failure().failure().actual().equals("function")
                && "data[0].bad".equals(
                    failure.failure().failure().metadata().get("fieldPath"))
                && failure.failure().origin().equals(elementOrigin),
            "an array element's unsupported content before the same array's cyclic "
                + "element renders the walk arm's complete tuple at data[0].bad");

        // The same mixed shape nested one level deeper: the table's key 'a'
        // holds an array whose first element is the unsupported value and
        // whose second element's key 'x' holds that array again, so the
        // re-entered array container behind the first failure is never
        // traversed.
        SemanticTable<Value> nestedTable = new SemanticTable<>();
        Value.Table nestedTableValue = new Value.Table(nestedTable);
        SemanticTable<Value> innerTable = new SemanticTable<>();
        Value.Table innerTableValue = new Value.Table(innerTable);
        Value.Array nestedArray = new Value.Array(
            SemanticArray.of(functionValue(), innerTableValue));
        innerTable.put("x", nestedArray);
        nestedTable.put("a", nestedArray);
        JsonStringify nestedCarrier = JsonClassAlgorithmAdapter.stringifier()
            .stringify(nestedTableValue, "data");
        check(nestedCarrier instanceof JsonStringify.Failure failure
                && !failure.cycle()
                && failure.fieldPath().equals("data.a[0]")
                && failure.actual().equals("function"),
            "the production adapter's mapping of an unsupported array element before "
                + "a re-entered array container publishes the walk arm's failure "
                + "carrier at data.a[0] with the function token");
        SourceOrigin nestedOrigin = nextOrigin(null);
        Outcome<Value> nestedWalk = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, instanceOf(tableLayout, Map.of("data", nestedTableValue))),
            layouts, JsonClassAlgorithmAdapter.stringifier(), nestedOrigin);
        check(nestedWalk instanceof Outcome.Failure<Value> failure
                && failure.failure().failure().code() == DiagnosticCode.E8001
                && failure.failure().failure().message().equals(
                    "value at data.a[0] is not JSON serializable: function")
                && failure.failure().failure().expected() == null
                && failure.failure().failure().actual().equals("function")
                && "data.a[0]".equals(
                    failure.failure().failure().metadata().get("fieldPath"))
                && failure.failure().origin().equals(nestedOrigin),
            "the production adapter's class walk renders the complete walk tuple for an "
                + "unsupported array element before a re-entered array container (E8001, "
                + "the exact message at data.a[0], no expected field, the function "
                + "token, the literal fieldPath metadata, and the supplied call "
                + "origin)");

        // The deep-tail regression (the acceptance finding's reproducer): a
        // selected noncycle failure followed by a 50,000-container chain that
        // ends in a self-referential table. The chain is far deeper than any
        // recursion the mapping could survive, so the adapter must return the
        // already-selected failure at data.bad without descending into it at
        // all — the pre-remediation eager toStdlib mapping exhausted the stack
        // long before it reached the re-entry.
        Value.Table deepTail = selfReferentialTable();
        for (int i = 0; i < 50000; i++) {
            SemanticTable<Value> level = new SemanticTable<>();
            Value.Table levelValue = new Value.Table(level);
            level.put("k", deepTail);
            deepTail = levelValue;
        }
        SemanticTable<Value> deepTailRoot = new SemanticTable<>();
        Value.Table deepTailRootValue = new Value.Table(deepTailRoot);
        deepTailRoot.put("bad", functionValue());
        deepTailRoot.put("later", deepTail);
        JsonStringify deepTailCarrier = JsonClassAlgorithmAdapter.stringifier()
            .stringify(deepTailRootValue, "data");
        check(deepTailCarrier instanceof JsonStringify.Failure failure
                && !failure.cycle()
                && failure.fieldPath().equals("data.bad")
                && failure.actual().equals("function"),
            "the production adapter returns the selected walk-arm failure carrier at "
                + "data.bad without traversing the 50,000-container cyclic tail "
                + "behind it (never a mapping crash)");
    }

    // =========================================================================
    // (h3) JSON_TO_CLASS: the selected failure before a deep finite tail
    //      (the integrated class walk)
    // =========================================================================

    /**
     * The selected first failing position decides the walk arm and its
     * literal fieldPath, so a deep finite tail behind an unsupported value or
     * a path-local re-entry never overrides it; a clean deep tail serializes
     * (there is no fixed nesting bound). Every case drives the integrated
     * class walk with the production adapter (never the seam alone).
     */
    private static void testToJsonSelectedFailureBeforeDeepTail() {
        System.out.println("-- toJson: a selected failure before a deep finite tail "
            + "(the integrated class walk) --");

        ClassLayout tableLayout = layoutOf(POINT, field("data", TABLE, true));
        Map<ClassId, ClassLayout> layouts = Map.of(POINT, tableLayout);
        ValueId classId = nextValue();
        SemanticOp op = jsonToOp(tableLayout, classId);

        // An unsupported value under the first inserted key, a 600-deep
        // table chain under the later key: the selected first failing
        // position is data.bad (the walk arm's function token), never the
        // later container.
        SemanticTable<Value> badThenDeep = new SemanticTable<>();
        Value.Table badThenDeepValue = new Value.Table(badThenDeep);
        badThenDeep.put("bad", functionValue());
        badThenDeep.put("later", nestedTableChain(600));
        checkIntegratedWalkFailure(op, layouts, classId,
            instanceOf(tableLayout, Map.of("data", badThenDeepValue)), nextOrigin(null),
            "value at data.bad is not JSON serializable: function", "function",
            "data.bad",
            "an unsupported value before a deep finite table tail renders the walk arm "
                + "at data.bad");

        // The same shape with the deep tail closed through a mixed
        // table/array chain.
        SemanticTable<Value> badThenMixed = new SemanticTable<>();
        Value.Table badThenMixedValue = new Value.Table(badThenMixed);
        badThenMixed.put("bad", functionValue());
        badThenMixed.put("later", nestedMixedChain(602));
        checkIntegratedWalkFailure(op, layouts, classId,
            instanceOf(tableLayout, Map.of("data", badThenMixedValue)),
            nextOrigin(null),
            "value at data.bad is not JSON serializable: function", "function",
            "data.bad",
            "an unsupported value before a deep finite mixed table/array tail renders "
                + "the walk arm at data.bad");

        // Cycle-first: the table field's own path-local re-entry under the
        // first inserted key, the deep chain under the later key. The
        // needle's own closed marker selects the cycle arm with no
        // parameters, expected, actual or metadata — the later container
        // never overrides it.
        SemanticTable<Value> cycleThenDeep = new SemanticTable<>();
        Value.Table cycleThenDeepValue = new Value.Table(cycleThenDeep);
        cycleThenDeep.put("self", cycleThenDeepValue);
        cycleThenDeep.put("later", nestedTableChain(600));
        checkIntegratedCycleFailure(op, layouts, classId,
            instanceOf(tableLayout, Map.of("data", cycleThenDeepValue)),
            nextOrigin(null),
            "a path-local table re-entry before a deep finite table tail keeps the "
                + "cycle arm's parameterless tuple");

        SemanticTable<Value> cycleThenMixed = new SemanticTable<>();
        Value.Table cycleThenMixedValue = new Value.Table(cycleThenMixed);
        cycleThenMixed.put("self", cycleThenMixedValue);
        cycleThenMixed.put("later", nestedMixedChain(602));
        checkIntegratedCycleFailure(op, layouts, classId,
            instanceOf(tableLayout, Map.of("data", cycleThenMixedValue)),
            nextOrigin(null),
            "a path-local table re-entry before a deep finite mixed table/array tail "
                + "keeps the cycle arm's parameterless tuple");

        // The acceptance reproducer through the integrated class walk (not
        // only the seam): the 50,000-container chain that ends in a
        // self-referential table behind the selected data.bad failure is never
        // traversed, so the class walk returns the already-selected failure
        // instead of exhausting the stack.
        Value.Table deepTail = selfReferentialTable();
        for (int i = 0; i < 50000; i++) {
            SemanticTable<Value> level = new SemanticTable<>();
            Value.Table levelValue = new Value.Table(level);
            level.put("k", deepTail);
            deepTail = levelValue;
        }
        SemanticTable<Value> deepTailRoot = new SemanticTable<>();
        Value.Table deepTailRootValue = new Value.Table(deepTailRoot);
        deepTailRoot.put("bad", functionValue());
        deepTailRoot.put("later", deepTail);
        checkIntegratedWalkFailure(op, layouts, classId,
            instanceOf(tableLayout, Map.of("data", deepTailRootValue)),
            nextOrigin(null),
            "value at data.bad is not JSON serializable: function", "function",
            "data.bad",
            "the integrated class walk returns the selected data.bad failure without "
                + "traversing the 50,000-container cyclic tail behind it");

        // The control: with no earlier failure, a clean 602-deep mixed
        // table/array tail serializes (there is no depth bound to fire).
        Outcome<Value> cleanDeepTail = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classId, instanceOf(tableLayout,
                Map.of("data", nestedMixedChain(602)))),
            layouts, JsonClassAlgorithmAdapter.stringifier(), nextOrigin(null));
        check(cleanDeepTail instanceof Outcome.Success<Value> success
                && success.value() instanceof Value.String,
            "a clean 602-deep mixed tail serializes (no fixed cap)");
    }

    /**
     * One integrated class-walk failure's complete tuple: E8001, the exact
     * message, the absent expected field, the walk token, the literal single
     * {@code fieldPath} metadata entry, and the supplied call origin (never
     * the op's synthetic anchor).
     */
    private static void checkIntegratedWalkFailure(
            SemanticOp op, Map<ClassId, ClassLayout> layouts, ValueId classValue,
            Value.Class instance, SourceOrigin callOrigin, String expectedMessage,
            String expectedActual, String expectedFieldPath, String label) {
        Outcome<Value> outcome = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classValue, instance), layouts,
            JsonClassAlgorithmAdapter.stringifier(), callOrigin);
        check(outcome instanceof Outcome.Failure<Value> failure,
            label + " (the class walk fails the value)");
        if (!(outcome instanceof Outcome.Failure<Value> failure)) {
            return;
        }
        check(failure.failure().failure().code() == DiagnosticCode.E8001
                && failure.failure().failure().message().equals(expectedMessage)
                && failure.failure().failure().expected() == null
                && failure.failure().failure().actual().equals(expectedActual)
                && failure.failure().failure().metadata().equals(
                    Map.of("fieldPath", expectedFieldPath))
                && failure.failure().origin().equals(callOrigin)
                && !failure.failure().origin().equals(op.origin()),
            label + " (E8001, the exact message, the absent expected field, the "
                + expectedActual + " token, the literal fieldPath metadata, and the "
                + "supplied call origin)");
    }

    /**
     * One integrated class-walk cycle-arm failure's complete tuple: E8001,
     * the pinned cycle text, no expected/actual/metadata, and the supplied
     * call origin (never the op's synthetic anchor).
     */
    private static void checkIntegratedCycleFailure(
            SemanticOp op, Map<ClassId, ClassLayout> layouts, ValueId classValue,
            Value.Class instance, SourceOrigin callOrigin, String label) {
        Outcome<Value> outcome = ClassOpsExecutor.executeJsonToClass(op,
            Map.of(classValue, instance), layouts,
            JsonClassAlgorithmAdapter.stringifier(), callOrigin);
        check(outcome instanceof Outcome.Failure<Value> failure,
            label + " (the class walk fails the value)");
        if (!(outcome instanceof Outcome.Failure<Value> failure)) {
            return;
        }
        check(failure.failure().failure().code() == DiagnosticCode.E8001
                && failure.failure().failure().message().equals(
                    "cyclic value cannot be encoded as JSON")
                && failure.failure().failure().expected() == null
                && failure.failure().failure().actual() == null
                && failure.failure().failure().metadata().equals(Map.of())
                && failure.failure().origin().equals(callOrigin)
                && !failure.failure().origin().equals(op.origin()),
            label + " (E8001, the pinned cycle text, no expected/actual/metadata, "
                + "and the supplied call origin)");
    }

    /** A table holding itself under the key {@code self}. */
    private static Value.Table selfReferentialTable() {
        SemanticTable<Value> table = new SemanticTable<>();
        Value.Table carrier = new Value.Table(table);
        table.put("self", carrier);
        return carrier;
    }

    /** The closed function carrier of the walk's unsupported-value positions. */
    private static Value functionValue() {
        return new Value.Function(new RuntimeDescriptor.Func(List.of(),
            RuntimeDescriptor.Null.INSTANCE, false));
    }

    private static Value.Class emptyNode() {
        return new Value.Class(INNER, List.of(FieldState.Missing.INSTANCE));
    }

    // =========================================================================
    // (i) the full roundtrip and the fail-closed discipline
    // =========================================================================

    private static void testRoundtripAndDefects() {
        System.out.println("-- the fromJson/toJson roundtrip and the fail-closed "
            + "discipline --");

        ClassLayout point = layoutOf(POINT,
            field("name", STRING, true),
            field("age", INT, true),
            field("note", new RuntimeDescriptor.Nullable(STRING), false),
            field("tags", new RuntimeDescriptor.Array(INT), true));
        ValueId fromId = nextValue();
        SemanticOp fromOp = jsonFromOp(point, fromId);
        SemanticOp nameDefault = defaultOp(POINT, "name", nextValue(), STRING);
        SemanticOp ageDefault = defaultOp(POINT, "age", nextValue(), INT);
        SemanticOp tagsDefault = defaultOp(POINT, "tags", nextValue(),
            new RuntimeDescriptor.Array(INT));
        Map<OpId, Value> produced = new LinkedHashMap<>();
        produced.put(nameDefault.opId(), Value.string("anon"));
        produced.put(ageDefault.opId(), new Value.Number(0));
        produced.put(tagsDefault.opId(),
            new Value.Array(SemanticArray.of(new Value.Number(1), new Value.Number(2))));
        Map<OpId, SemanticOp> defaultOps = Map.of(nameDefault.opId(), nameDefault,
            ageDefault.opId(), ageDefault, tagsDefault.opId(), tagsDefault);
        Map<ClassId, ClassLayout> layouts = Map.of(POINT, point);
        Value decoded = ClassOpsExecutor.executeJsonFromClass(fromOp,
            Map.of(fromId, Value.string("{\"age\":42}")),
            List.of(nameDefault.opId(), ageDefault.opId(), tagsDefault.opId()),
            defaultOps, layouts, JsonClassAlgorithmAdapter.parser(), missingFactorySeam(),
            new ScriptedBodyRunner(new ArrayList<>(), produced, null));
        check(decoded instanceof Value.Class, "the fromJson walk publishes an instance");
        if (!(decoded instanceof Value.Class instance)) {
            return;
        }
        ValueId toId = nextValue();
        SemanticOp toOp = jsonToOp(point, toId);
        Outcome<Value> roundtrip = ClassOpsExecutor.executeJsonToClass(toOp,
            Map.of(toId, instance), layouts, JsonClassAlgorithmAdapter.stringifier(), nextOrigin(null));
        check(roundtrip instanceof Outcome.Success<Value> success
                && success.value() instanceof Value.String text
                && carrier(text).equals(
                    "{\"name\":\"anon\",\"age\":42.0,\"tags\":[1.0,2.0]}"),
            "the roundtrip emits the deterministic text (defaulted name, decoded age, "
                + "defaulted tags, omitted optional skipped)");

        // Fail-closed defects.
        ClassLayout strict = layoutOf(POINT, field("x", INT, true));
        SemanticOp fromStrict = jsonFromOp(strict, nextValue());
        expectDefect(() -> ClassOpsExecutor.executeJsonFromClass(
                op(SemanticOpKind.CLASS_NEW,
                    new KindPayload.ClassNewPayload(POINT, point, List.of(),
                        DefaultOwner.LOCAL, List.of(), null, List.of()),
                    nextValue(), new RuntimeDescriptor.Class(POINT),
                    FailurePolicyId.CLASS_CONSTRUCTION, null),
                Map.of(), List.of(), Map.of(), Map.of(), JsonClassAlgorithmAdapter.parser(),
                missingFactorySeam(), new ScriptedBodyRunner(new ArrayList<>(), Map.of(),
                    null)),
            "a non-JSON_FROM_CLASS op kind");
        expectDefect(() -> ClassOpsExecutor.executeJsonFromClass(
                op(SemanticOpKind.JSON_FROM_CLASS,
                    new KindPayload.JsonFromClassPayload(strict, nextValue()),
                    nextValue(), new RuntimeDescriptor.Nullable(new RuntimeDescriptor.Class(
                        POINT)),
                    FailurePolicyId.CLASS_CONSTRUCTION, null),
                Map.of(), List.of(), Map.of(), Map.of(), JsonClassAlgorithmAdapter.parser(),
                missingFactorySeam(), new ScriptedBodyRunner(new ArrayList<>(), Map.of(),
                    null)),
            "a non-JSON_FROM_NULL policy");
        expectDefect(() -> ClassOpsExecutor.executeJsonFromClass(fromStrict,
                Map.of(), List.of(), Map.of(), Map.of(), JsonClassAlgorithmAdapter.parser(),
                missingFactorySeam(), new ScriptedBodyRunner(new ArrayList<>(), Map.of(),
                    null)),
            "an unresolvable jsonString operand");
        expectDefect(() -> ClassOpsExecutor.executeJsonFromClass(fromStrict,
                Map.of(fromStrict.payload() instanceof KindPayload.JsonFromClassPayload p
                    ? p.jsonString() : nextValue(), new Value.Number(3)),
                List.of(), Map.of(), Map.of(), JsonClassAlgorithmAdapter.parser(), missingFactorySeam(),
                new ScriptedBodyRunner(new ArrayList<>(), Map.of(), null)),
            "a wrong-kind jsonString operand");
        expectDefect(() -> ClassOpsExecutor.executeJsonFromClass(fromStrict,
                Map.of(fromStrict.payload() instanceof KindPayload.JsonFromClassPayload p
                    ? p.jsonString() : nextValue(), Value.string("{}")),
                List.of(), Map.of(), Map.of(),
                JsonClassAlgorithmAdapter.parser(), missingFactorySeam(),
                new ScriptedBodyRunner(new ArrayList<>(), Map.of(), null)),
            "an unresolvable layout resolution (empty context)");
        // A duplicate default child is a producer defect.
        SemanticOp dupDefault = defaultOp(POINT, "name", nextValue(), STRING);
        expectDefect(() -> ClassOpsExecutor.executeJsonFromClass(fromOp,
                Map.of(fromOp.payload() instanceof KindPayload.JsonFromClassPayload p
                    ? p.jsonString() : nextValue(), Value.string("{}")),
                List.of(dupDefault.opId(), dupDefault.opId()),
                Map.of(dupDefault.opId(), dupDefault), layouts, JsonClassAlgorithmAdapter.parser(),
                missingFactorySeam(), new ScriptedBodyRunner(new ArrayList<>(), Map.of(),
                    null)),
            "duplicate CLASS_DEFAULT children");
        // An optional default child is a producer defect.
        ClassLayout optionalNote = layoutOf(POINT,
            field("note", new RuntimeDescriptor.Nullable(STRING), false));
        SemanticOp optionalDefault = defaultOp(POINT, "note", nextValue(),
            new RuntimeDescriptor.Nullable(STRING));
        expectDefect(() -> ClassOpsExecutor.executeJsonFromClass(
                jsonFromOp(optionalNote, nextValue()),
                Map.of(), List.of(optionalDefault.opId()),
                Map.of(optionalDefault.opId(), optionalDefault),
                Map.of(POINT, optionalNote), JsonClassAlgorithmAdapter.parser(), missingFactorySeam(),
                new ScriptedBodyRunner(new ArrayList<>(), Map.of(), null)),
            "an optional field's CLASS_DEFAULT child");
        // The toJson operand resolution and layout resolution fail closed.
        ValueId toStrictValue = nextValue();
        expectDefect(() -> ClassOpsExecutor.executeJsonToClass(
                jsonToOp(strict, toStrictValue),
                Map.of(), Map.of(POINT, strict), JsonClassAlgorithmAdapter.stringifier(),
                nextOrigin(null)),
            "an unresolvable classValue operand");
        expectDefect(() -> ClassOpsExecutor.executeJsonToClass(
                jsonToOp(strict, toStrictValue),
                Map.of(toStrictValue, new Value.Number(3)),
                Map.of(), JsonClassAlgorithmAdapter.stringifier(), nextOrigin(null)),
            "an unresolvable toJson layout resolution (empty context)");
        expectDefect(() -> ClassOpsExecutor.executeJsonToClass(
                op(SemanticOpKind.JSON_TO_CLASS,
                    new KindPayload.JsonToClassPayload(nextValue(), strict), nextValue(),
                    RuntimeDescriptor.String.INSTANCE, FailurePolicyId.JSON_FROM_NULL,
                    null),
                Map.of(), Map.of(POINT, strict), JsonClassAlgorithmAdapter.stringifier(),
                nextOrigin(null)),
            "a non-JSON_TO_ERROR policy");

        // Null-argument NPEs.
        SemanticOp fromNpe = jsonFromOp(strict, nextValue());
        expectNpe(() -> ClassOpsExecutor.executeJsonFromClass(null,
                Map.of(), List.of(), Map.of(), Map.of(), JsonClassAlgorithmAdapter.parser(),
                missingFactorySeam(), new ScriptedBodyRunner(new ArrayList<>(), Map.of(),
                    null)),
            "a null JSON_FROM_CLASS op");
        expectNpe(() -> ClassOpsExecutor.executeJsonFromClass(fromNpe,
                null, List.of(), Map.of(), Map.of(), JsonClassAlgorithmAdapter.parser(),
                missingFactorySeam(), new ScriptedBodyRunner(new ArrayList<>(), Map.of(),
                    null)),
            "null priorValues");
        expectNpe(() -> ClassOpsExecutor.executeJsonFromClass(fromNpe,
                Map.of(), null, Map.of(), Map.of(), JsonClassAlgorithmAdapter.parser(),
                missingFactorySeam(), new ScriptedBodyRunner(new ArrayList<>(), Map.of(),
                    null)),
            "null defaultChildIds");
        expectNpe(() -> ClassOpsExecutor.executeJsonToClass(null,
                Map.of(), Map.of(), JsonClassAlgorithmAdapter.stringifier(), nextOrigin(null)),
            "a null JSON_TO_CLASS op");
        expectNpe(() -> ClassOpsExecutor.executeJsonToClass(
                jsonToOp(strict, nextValue()), Map.of(), Map.of(), JsonClassAlgorithmAdapter.stringifier(),
                null),
            "a null call origin");
    }

    // =========================================================================
    // Runner
    // =========================================================================

    /**
     * The deep finite nesting checks descend the production encoder and the
     * production adapter's seam walk (about two thousand nested frames for
     * the 1200-deep class chain). The frame sizes depend on the JIT's
     * compilation state; C1 frames alone need over 1 MiB, so the JVM's
     * default main-thread stack (1 MiB) can overflow nondeterministically
     * before a deep document completes. The suite therefore runs on a
     * dedicated thread with a fixed, generous stack, so the deep-document
     * assertions measure the implemented walk instead of the default stack
     * size.
     */
    private static final long WALK_DEPTH_TEST_STACK_BYTES = 32L * 1024 * 1024;

    public static void main(String[] args) {
        System.out.println("=== Json Class Executor Tests (ISSUE-0515 K-D8/K-D9/K-D10/K-D11) ===\n");

        // A StackOverflowError escaping the depth checks would kill the
        // runner before it reports, so the worker's failure is rethrown on
        // the main thread (the counters are read after join).
        Throwable[] unexpected = { null };
        Thread runner = new Thread(null, () -> {
            try {
                runAll();
            } catch (Throwable failure) {
                unexpected[0] = failure;
            }
        }, "json-class-executor-tests", WALK_DEPTH_TEST_STACK_BYTES);
        runner.start();
        try {
            runner.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            unexpected[0] = interrupted;
        }
        if (unexpected[0] != null) {
            unexpected[0].printStackTrace();
            System.exit(1);
        }

        System.out.println("\nJsonClassExecutorTest: " + passed + " passed, "
            + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void runAll() {
        testAlgorithmObservations();
        testFromJsonTopLevelGate();
        testFromJsonFailures();
        testFromJsonNestedFactory();
        testFromJsonDepthBound();
        testToJsonDeterministicText();
        testToJsonFailureTemplateAndOrigin();
        testToJsonCyclesAndDepth();
        testToJsonNoncycleFailureBeforeCycle();
        testToJsonSelectedFailureBeforeDeepTail();
        testRoundtripAndDefects();
    }
}
