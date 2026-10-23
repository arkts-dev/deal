package deal.semantic;

import deal.semantic.ir.ActualKind;
import deal.semantic.ir.BoundaryFailure;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.FailureArm;
import deal.semantic.ir.FailureArmId;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FailureProjections;
import deal.semantic.ir.JsonNumberClassifier;
import deal.semantic.ir.JsonScan;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.SemanticArray;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticTable;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.StdlibFunctionId;
import deal.semantic.ir.UnicodeScalars;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SharedStdlibSemantics {

    private SharedStdlibSemantics() {
        // Static surface only; pure and stateless.
    }

    // =========================================================================
    // Producer defects (fail closed, never a DEAL projection)
    // =========================================================================

    /**
     * An executor producer defect: a shape outside the closed stdlib
     * contracts reached the primitive. Internal control flow — fail
     * closed, never a DEAL projection and never a crash.
     */
    public static final class Defect extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Defect(String message) {
            super(message);
        }
    }

    // =========================================================================
    // The closed value view of the 18 operations
    // =========================================================================

    /**
     * The closed value view the executor consumes and produces over the
     * semantic value model: exactly the values the 18 operations resolve,
     * compare, store, encode, and publish — language null, boolean,
     * IEEE-754 number, string (carrying the closed
     * {@link UnicodeScalars.ScalarString} classification), table
     * ({@link SemanticTable}, first-insertion order), array
     * ({@link SemanticArray}, index order), and the unsupported-value
     * carrier {@link Other} (an actual kind outside the JSON-shaped set,
     * reported through its canonical {@link ActualKind} token by
     * {@code JSON_STRINGIFY}). Null references never appear: language
     * null is the explicit {@link Null} variant, and every variant
     * renders its canonical {@link ActualKind}. The view is realized by
     * the oracle's heap and by test fixtures; the executor interprets
     * classifications and carriers only, never target representations.
     */
    public sealed interface Value
        permits Value.Null, Value.Bool, Value.Number, Value.String,
                Value.Table, Value.Array, Value.Other {

        /** The canonical actual kind this value renders as. */
        ActualKind actualKind();

        /** A string view carrying the validated classification of {@code carrier}. */
        static Value string(java.lang.String carrier) {
            return new String(UnicodeScalars.validate(carrier));
        }

        /** A string view carrying an explicit scalar classification. */
        static Value string(UnicodeScalars.ScalarString scalar) {
            return new String(Objects.requireNonNull(scalar, "scalar must not be null"));
        }

        /** An array view over the ordered elements. */
        static Value array(List<Value> elements) {
            return new Array(SemanticArray.of(elements));
        }

        /** An array view over the ordered elements. */
        static Value array(Value... elements) {
            return new Array(SemanticArray.of(elements));
        }

        /** An empty table view with a fresh identity. */
        static Value table() {
            return new Table(new SemanticTable<>());
        }

        /** Language null (the explicit {@code ActualKind#NULL}). */
        enum Null implements Value {
            INSTANCE;

            @Override
            public ActualKind actualKind() {
                return ActualKind.NULL;
            }
        }

        /** A boolean value. */
        record Bool(boolean value) implements Value {

            @Override
            public ActualKind actualKind() {
                return ActualKind.BOOLEAN;
            }
        }


/** An IEEE-754 number value (NaN and infinity included). */
        record Number(double value) implements Value {

            @Override
            public ActualKind actualKind() {
                return ActualKind.NUMBER;
            }
        }

        /**
         * A string value carrying the closed scalar classification
         * ({@link UnicodeScalars.ScalarString}): {@code Valid} renders
         * {@link ActualKind#STRING}, {@code Invalid} renders
         * {@link ActualKind#INVALID_UNICODE}. The classification is
         * never normalized away.
         */
        record String(UnicodeScalars.ScalarString scalar) implements Value {

            public String {
                Objects.requireNonNull(scalar, "scalar must not be null");
            }

            @Override
            public ActualKind actualKind() {
                return scalar.actualKind();
            }
        }

        /** A table value carrying its first-insertion-order model. */
        record Table(SemanticTable<Value> table) implements Value {

            public Table {
                Objects.requireNonNull(table, "table must not be null");
            }

            @Override
            public ActualKind actualKind() {
                return ActualKind.TABLE;
            }
        }

        /** An array value carrying its ordered element model. */
        record Array(SemanticArray<Value> elements) implements Value {

            public Array {
                Objects.requireNonNull(elements, "elements must not be null");
            }

            @Override
            public ActualKind actualKind() {
                return ActualKind.ARRAY;
            }
        }

        /**
         * A value outside the JSON-shaped set: exactly the actual kinds
         * {@code missing}, {@code function}, {@code async-operation},
         * {@code class} (with the canonical class-id atom text).
         * {@code JSON_STRINGIFY} rejects it through the
         * {@code JSON_STRINGIFY_UNSUPPORTED} arm's carrier-kind projection
         * ({@code nil}, {@code table}, {@code function}),
         * never the {@code class:} IR/trace spelling; no other algorithm
         * consumes an {@code Other} value.
         */
        record Other(ActualKind kind, java.lang.String classId) implements Value {

            public Other {
                Objects.requireNonNull(kind, "kind must not be null");
                switch (kind) {
                    case CLASS -> {
                        if (classId == null || classId.isEmpty()) {
                            throw new IllegalArgumentException(
                                "a CLASS Other view carries the canonical class-id atom "
                                    + "text (@modulePath/ClassName); classId must be "
                                    + "non-null and non-empty");
                        }
                    }
                    case MISSING, FUNCTION, ASYNC_OPERATION -> {
                        if (classId != null) {
                            throw new IllegalArgumentException(
                                "only a CLASS Other view carries a class id");
                        }
                    }
                    default -> throw new IllegalArgumentException(
                        "an Other view carries a value outside the JSON-shaped set "
                            + "(missing, function, async-operation, class); got "
                            + kind);
                }
            }

            @Override
            public ActualKind actualKind() {
                return kind;
            }
        }
    }

    // =========================================================================
    // Console sink (D5)
    // =========================================================================

    /** The named console channel of {@code STDLIB_CALL(CONSOLE_*)}. */
    public enum Channel { STDOUT, STDERR }

    /**
     * The injected console sink (D5): the executor supplies it, the
     * primitive performs no process I/O. {@link #write(byte[])} receives
     * exactly one byte sequence per call — the argument's exact scalar
     * UTF-8 bytes plus one {@code \n}. A sink failure (any exception
     * thrown by {@code write}) is infrastructure failure
     * ({@code INFRASTRUCTURE_ONLY}): not a DEAL error, no DEAL
     * diagnostic, not catchable — the primitive catches nothing and the
     * exception propagates to the executor, which aborts the run.
     */
    public interface ConsoleSink {

        /** The channel this sink writes to ({@code STDOUT} or {@code STDERR}). */
        Channel channel();

        /**
         * Writes the exact effect bytes (scalar UTF-8 + {@code \n}).
         *
         */
        void write(byte[] bytes);
    }

    /**
     * The injected clock seam of {@code STDLIB_CALL(TIME_NOW_MILLIS)}
     * (K7 item 4): the executor supplies the target-clock reading the
     * shared algorithm returns, so the primitive stays pure/deterministic
     * and performs no process I/O — the LuaJIT artifact reads
     * {@code os.time() * 1000} and the JVM artifact
     * {@code System.currentTimeMillis()} in their own closed realization
     * tables, while the oracle injects its pinned deterministic reading.
     */
    public interface Clock {

        /** The current epoch-millisecond reading of the target clock. */
        long nowMillis();
    }

    /**
     * The production default clock: the wall clock of the executing
     * process. The oracle never uses it — the oracle injects its own
     * deterministic reading through the four-argument
     * {@code execute} surface.
     */
    public static final Clock SYSTEM_CLOCK = System::currentTimeMillis;

    // =========================================================================
    // Sealed outcome
    // =========================================================================

    /**
     * One algorithm failure: the structured registry-arm projection
     * (rendered through {@link FailureContractRegistry#render} — the
     * primitive never selects message text) paired with the operation
     * origin of the closed table's rule — the {@code STDLIB_CALL} call
     * origin for every stdlib row ({@code JSON_PARSE_SYNTAX},
     * {@code JSON_TO_ERROR}, {@code SQRT_NEGATIVE} operation origin).
     */
    public record StdlibFailure(BoundaryFailure failure, SourceOrigin origin) {

        public StdlibFailure {
            Objects.requireNonNull(failure, "failure must not be null");
            Objects.requireNonNull(origin, "origin must not be null");
        }
    }

    /**
     * The closed terminal of one executor call:
     * {@code Success(value) | Failure(StdlibFailure)}. A {@code Failure}
     * is exactly one pinned projection — never a partial value and never
     * a retry. A console sink exception is not a {@code Failure}: it
     * propagates out of the primitive as infrastructure failure.
     *
     */
    public sealed interface Outcome<V> permits Outcome.Success, Outcome.Failure {

        /** The algorithm succeeded; {@code value} is the published result. */
        record Success<V>(V value) implements Outcome<V> {

            public Success {
                Objects.requireNonNull(value, "value must not be null");
            }
        }

        /** The algorithm failed; {@code failure} is the registry-row projection. */
        record Failure<V>(StdlibFailure failure) implements Outcome<V> {

            public Failure {
                Objects.requireNonNull(failure, "failure must not be null");
            }
        }
    }

    // =========================================================================
    // The single execution dispatch over a validated STDLIB_CALL op
    // =========================================================================

    /**
     * Executes one validated {@code STDLIB_CALL} op with its resolved
     * argument values in left-to-right order (the declared-descriptor
     * inputs whose {@code STDLIB_PARAMETER} boundaries passed), through
     * the per-family algorithm of the closed stdlib table, and returns
     * the sealed outcome. The op's stamped {@code failurePolicy} is
     * cross-checked against the single
     * {@link SemanticIrValidator#stdlibPolicy} table (the closed
     * algorithm→policy assignment stays single-sourced), and the
     * payload's {@code effectCapability} must be
     * {@code STDLIB_SEMANTICS} — any deviation is a producer defect,
     * never executed.
     *
     * <p>Argument carriers follow the boundary admission rule exactly:
     * a string parameter is a {@link Value.String} whose scalar is
     * {@link UnicodeScalars.Valid} (an {@code Invalid} carrier is a
     * {@link Defect} — the parameter boundary rejects it first, so the
     * primitive is never reached with an invalid carrier); a number
     * parameter is {@link Value.Number}, preserving its binary64 value;
     * a table parameter is {@link Value.Table}. Any other
     * carrier fails closed. Console calls require the injected
     * {@link ConsoleSink} whose {@code channel()} matches the call's
     * channel; non-console calls ignore the sink.</p>
     *
     */
    public static Outcome<Value> execute(SemanticOp op, List<Value> args,
                                         ConsoleSink sink) {
        return execute(op, args, sink, SYSTEM_CLOCK);
    }

    /**
     * Executes one validated {@code STDLIB_CALL} op with an injected
     * {@link Clock} (the {@code TIME_NOW_MILLIS} seam, K7 item 4): the
     * full surface of {@link #execute(SemanticOp, List, ConsoleSink)};
     * the clock is consulted only by {@code TIME_NOW_MILLIS} and every
     * other id ignores it.
     *
     */
    public static Outcome<Value> execute(SemanticOp op, List<Value> args,
                                         ConsoleSink sink, Clock clock) {
        Objects.requireNonNull(op, "op must not be null");
        Objects.requireNonNull(args, "args must not be null");
        if (op.kind() != SemanticOpKind.STDLIB_CALL) {
            throw new Defect("execute receives only STDLIB_CALL ops, got " + op.kind()
                + " — a producer defect, never executed");
        }
        if (!(op.payload() instanceof KindPayload.StdlibCallPayload payload)) {
            throw new Defect("a STDLIB_CALL op carries a StdlibCallPayload; got "
                + op.payload().getClass().getSimpleName());
        }
        if (payload.effectCapability() != SemanticCapability.STDLIB_SEMANTICS) {
            throw new Defect("the STDLIB_CALL effect capability is STDLIB_SEMANTICS; got "
                + payload.effectCapability());
        }
        StdlibFunctionId function = payload.function();
        FailurePolicyId stamped = op.failurePolicy();
        FailurePolicyId pinned = SemanticIrValidator.stdlibPolicy(function);
        if (stamped != pinned) {
            throw new Defect("STDLIB_CALL(" + function + ") stamps failurePolicy " + pinned
                + " from the single stdlibPolicy table; got " + stamped
                + " — the closed algorithm→policy assignment is single-sourced");
        }
        if (args.size() != payload.args().size() || args.size() != op.operands().size()
                || args.size() != op.operandTypes().size()) {
            throw new Defect("STDLIB_CALL(" + function + ") carries " + payload.args().size()
                + " payload args, " + op.operands().size() + " operands, "
                + op.operandTypes().size() + " operand types, but " + args.size()
                + " resolved argument values were supplied — a count mismatch is a "
                + "producer defect, never executed");
        }
        List<Value> argv = List.copyOf(args); // rejects null elements
        return dispatch(function, op.origin(), argv, sink, clock);
    }

    /**
     * Executes one cataloged row's algorithm at a caller-supplied origin
     * (the cataloged-callable seam): the same closed algorithm table, the
     * same arity authority, and the same failure projections the direct
     * {@code STDLIB_CALL} arm's {@link #execute(SemanticOp, List,
     * ConsoleSink, Clock)} runs — one authority, never a second copy. The
     * dynamic HOST sub-class of the function-typed-value dispatch calls
     * this seam with the invoking call op's own origin, so the cataloged
     * callable's failures keep the call site's pinned projection.
     *
     */
    public static Outcome<Value> dispatch(StdlibFunctionId function, SourceOrigin origin,
                                          List<Value> args, ConsoleSink sink, Clock clock) {
        Objects.requireNonNull(function, "function must not be null");
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(args, "args must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        List<Value> argv = List.copyOf(args); // rejects null elements
        requireArity(function, argv.size());
        return switch (function) {
            case CONSOLE_LOG ->
                consoleLog(origin, validTextOf(argv, 0, function), sink);
            case CONSOLE_ERROR ->
                consoleError(origin, validTextOf(argv, 0, function), sink);
            case STRING_LENGTH ->
                stringLength(origin, validTextOf(argv, 0, function));
            case STRING_SUBSTRING -> stringSubstring(origin,
                validTextOf(argv, 0, function), numberOf(argv, 1, function),
                numberOf(argv, 2, function));
            case STRING_CONTAINS -> stringContains(origin,
                validTextOf(argv, 0, function), validTextOf(argv, 1, function));
            case STRING_STARTS_WITH -> stringStartsWith(origin,
                validTextOf(argv, 0, function), validTextOf(argv, 1, function));
            case STRING_ENDS_WITH -> stringEndsWith(origin,
                validTextOf(argv, 0, function), validTextOf(argv, 1, function));
            case STRING_REPLACE -> stringReplace(origin,
                validTextOf(argv, 0, function), validTextOf(argv, 1, function),
                validTextOf(argv, 2, function));
            case STRING_SPLIT -> stringSplit(origin,
                validTextOf(argv, 0, function), validTextOf(argv, 1, function));
            case STRING_TRIM -> stringTrim(origin, validTextOf(argv, 0, function));
            case TABLE_KEYS -> tableKeys(origin, tableOf(argv, 0, function));
            case JSON_PARSE -> jsonParse(origin, validTextOf(argv, 0, function));
            case JSON_STRINGIFY -> jsonStringify(origin, tableOf(argv, 0, function));
            case MATH_FLOOR -> mathFloor(origin, numberOf(argv, 0, function));
            case MATH_CEIL -> mathCeil(origin, numberOf(argv, 0, function));
            case MATH_SQRT -> mathSqrt(origin, numberOf(argv, 0, function));

            case MATH_ABS_NUMBER ->
                mathAbsNumber(origin, numberOf(argv, 0, function));


            case TIME_NOW_MILLIS -> timeNowMillis(origin, clock);
        };
    }

    /**
     * The declared parameter arity of one closed stdlib id (D1 table plus
     * the K7 {@code std.time} row, arity 0).
     */
    private static int declaredArity(StdlibFunctionId function) {
        return switch (function) {
            case TIME_NOW_MILLIS -> 0;
            case CONSOLE_LOG, CONSOLE_ERROR, STRING_LENGTH, STRING_TRIM, TABLE_KEYS, JSON_PARSE, JSON_STRINGIFY, MATH_FLOOR, MATH_CEIL, MATH_SQRT, MATH_ABS_NUMBER -> 1;
            case STRING_CONTAINS, STRING_STARTS_WITH, STRING_ENDS_WITH, STRING_SPLIT -> 2;
            case STRING_SUBSTRING, STRING_REPLACE -> 3;
        };
    }

    private static void requireArity(StdlibFunctionId function, int count) {
        int declared = declaredArity(function);
        if (count != declared) {
            throw new Defect("STDLIB_CALL(" + function + ") declares " + declared
                + " parameter(s); got " + count + " resolved arguments — a producer "
                + "defect, never executed");
        }
    }

    /** A string parameter carrier: {@link Value.String} with a {@code Valid} scalar. */
    private static UnicodeScalars.Valid validTextOf(List<Value> args, int index,
                                                    StdlibFunctionId function) {
        Value arg = args.get(index);
        if (arg instanceof Value.String string
                && string.scalar() instanceof UnicodeScalars.Valid valid) {
            return valid;
        }
        throw new Defect("STDLIB_CALL(" + function + ") argument " + (index + 1)
            + " must be a Valid string carrier (the STDLIB_PARAMETER boundary rejects "
            + "invalid scalar encodings first); got " + describe(arg)
            + " — the primitive is never reached with an invalid carrier");
    }


/** A number parameter carrier: {@link Value.Number} (binary64). */
    private static double numberOf(List<Value> args, int index, StdlibFunctionId function) {
        Value arg = args.get(index);
        return switch (arg) {
            case Value.Number number -> number.value();

            default -> throw new Defect("STDLIB_CALL(" + function + ") argument "
                + (index + 1) + " must be a number carrier; got " + describe(arg));
        };
    }

    /** A table parameter carrier: {@link Value.Table}. */
    private static SemanticTable<Value> tableOf(List<Value> args, int index,
                                                StdlibFunctionId function) {
        Value arg = args.get(index);
        if (arg instanceof Value.Table table) {
            return table.table();
        }
        throw new Defect("STDLIB_CALL(" + function + ") argument " + (index + 1)
            + " must be a table carrier; got " + describe(arg));
    }

    /** The closed variant name for defect reporting. */
    private static String describe(Value value) {
        return value.getClass().getSimpleName() + "/" + value.actualKind();
    }

    // =========================================================================
    // Registry-row projection (codes/templates stay the registry's single source)
    // =========================================================================

    /**
     * Instantiates one pinned registry-row template into an outcome
     * failure at {@code origin} — the only failure-construction surface.
     *
     */
    private static Outcome.Failure<Value> failOf(FailurePolicyId policy, int templateIndex,
                                                 String expected, String actual,
                                                 Map<String, String> metadata,
                                                 SourceOrigin origin) {
        // The arm bound to the row's template position renders its own
        // template, its own code, and its declared field contract; the
        // named parameters come from the pinned metadata (the two field
        // texts from the expected/actual fields) and are never composed.
        FailureArm arm = FailureContractRegistry.armForTemplate(policy, templateIndex);
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String parameter : arm.parameters()) {
            switch (parameter) {
                case "expected" -> parameters.put(parameter, expected);
                case "actual" -> parameters.put(parameter, actual);
                default -> {
                    String value = metadata.get(parameter);
                    if (value != null) {
                        parameters.put(parameter, value);
                    }
                }
            }
        }
        BoundaryFailure failure = FailureContractRegistry.render(arm.id(), parameters,
            expected, actual, null);
        return new Outcome.Failure<>(new StdlibFailure(failure, origin));
    }

    /** The pinned two-key metadata map of the {@code JSON_PARSE_SYNTAX} row. */
    private static Map<String, String> parseMetadata(long oneBasedByteOffset, String reason) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("oneBasedByteOffset", Long.toString(oneBasedByteOffset));
        metadata.put("reason", reason);
        return metadata;
    }

    /**
     * Attaches the walk's internal position metadata to one rendered arm
     * failure: the {@code JSON_TO_ERROR} row's {@code fieldPath} key with
     * the walk's own spelling (owned here) beside the arm's actual token.
     * The visible tuple stays exactly the arm's own render — the metadata
     * is internal and the emitted runtimes carry no such field; the
     * failure's own fields are never re-derived here.
     */
    private static BoundaryFailure withWalkMetadata(BoundaryFailure rendered,
                                                    String fieldPath) {
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String key : FailureContractRegistry.row(rendered.policy()).metadataKeys()) {
            switch (key) {
                case "fieldPath" -> metadata.put(key, fieldPath);
                case "actual" -> metadata.put(key, rendered.actual());
                default -> { }
            }
        }
        return new BoundaryFailure(rendered.policy(), rendered.code(), rendered.message(),
            rendered.expected(), rendered.actual(), metadata, rendered.cause());
    }

    /**
     * The std/json rejection arm's carrier classification of one semantic
     * value (P2 item 2 with the arm's two pinned members): the absent
     * marker is {@code nil}, a DEAL function value is {@code function},
     * and every other table-carried value
     * — tables, arrays, class instances, Error values, async-operation
     * handles — is {@code table}. The token table itself is
     * {@link FailureProjections#carrierKindToken(FailureProjections.CarrierKind)}.
     */
    private static FailureProjections.CarrierKind stringifyCarrierKind(Value value) {
        return switch (value) {
            case Value.Null ignored -> FailureProjections.CarrierKind.LANGUAGE_NULL;
            case Value.Bool ignored -> FailureProjections.CarrierKind.BOOLEAN;

            case Value.Number ignored -> FailureProjections.CarrierKind.NUMBER;
            case Value.String ignored -> FailureProjections.CarrierKind.STRING;
            case Value.Table ignored -> FailureProjections.CarrierKind.TABLE;
            case Value.Array ignored -> FailureProjections.CarrierKind.TABLE;
            case Value.Other other -> switch (other.kind()) {
                case MISSING -> FailureProjections.CarrierKind.ABSENT;
                case FUNCTION -> FailureProjections.CarrierKind.DEAL_FUNCTION;
                case CLASS, ASYNC_OPERATION -> FailureProjections.CarrierKind.TABLE;
                default -> throw new Defect("the std/json rejection arm carries no "
                    + "carrier kind for the actual kind " + other.kind()
                    + " (producer defect)");
            };
        };
    }

    // =========================================================================
    // Console (D5): byte-exact one-effect contract over the injected sink
    // =========================================================================

    /**
     * {@code CONSOLE_LOG}: appends the argument's exact scalar UTF-8
     * bytes plus one {@code \n} to {@code STDOUT} as exactly one ordered
     * effect through the injected {@link ConsoleSink}, then publishes
     * the null result. The effect is byte-exact, the {@code \n} suffix
     * is ordered, and the write happens exactly once before terminal
     * SUCCESS. A sink failure is infrastructure failure
     * ({@code INFRASTRUCTURE_ONLY}): the primitive catches nothing — the
     * sink exception propagates to the executor, which aborts the run,
     * and no DEAL failure is ever reported.
     *
     */
    public static Outcome<Value> consoleLog(SourceOrigin origin, UnicodeScalars.Valid text,
                                            ConsoleSink sink) {
        return consoleEffect(origin, Channel.STDOUT, text, sink);
    }

    /**
     * {@code CONSOLE_ERROR}: appends the argument's exact scalar UTF-8
     * bytes plus one {@code \n} to {@code STDERR} as exactly one ordered
     * effect through the injected {@link ConsoleSink}, then publishes
     * the null result. The same one-effect, byte-exact,
     * infrastructure-only contract as {@link #consoleLog} on the
     * {@code STDERR} channel.
     *
     */
    public static Outcome<Value> consoleError(SourceOrigin origin, UnicodeScalars.Valid text,
                                              ConsoleSink sink) {
        return consoleEffect(origin, Channel.STDERR, text, sink);
    }

    /** The shared one-effect console realization (D5). */
    private static Outcome<Value> consoleEffect(SourceOrigin origin, Channel channel,
                                                UnicodeScalars.Valid text,
                                                ConsoleSink sink) {
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(sink, "sink must not be null");
        if (sink.channel() != channel) {
            throw new Defect("the console call writes " + channel + ", but the injected "
                + "sink carries channel " + sink.channel()
                + " — the channel identity is part of the operation-level effect "
                + "contract");
        }
        byte[] scalarUtf8 = text.carrier().getBytes(StandardCharsets.UTF_8);
        byte[] effectBytes = Arrays.copyOf(scalarUtf8, scalarUtf8.length + 1);
        effectBytes[effectBytes.length - 1] = (byte) '\n';
        // Exactly one ordered effect before terminal SUCCESS. The
        // primitive catches nothing: a sink exception is infrastructure
        // failure (INFRASTRUCTURE_ONLY) and propagates to the executor,
        // which aborts the run — never a DEAL failure.
        sink.write(effectBytes);
        return new Outcome.Success<>(Value.Null.INSTANCE);
    }

    // =========================================================================
    // String family (Unicode scalar domain)
    // =========================================================================

    /** {@code STRING_LENGTH}: Unicode scalar count as a binary64 number. */
    public static Outcome<Value> stringLength(SourceOrigin origin,
                                              UnicodeScalars.Valid input) {
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(input, "input must not be null");
        return new Outcome.Success<>(new Value.Number(
            input.carrier().codePointCount(0, input.carrier().length())));
    }

    /**
     * {@code STRING_SUBSTRING}: scalar indices;
     * {@code lo=max(0,start)}, {@code hi=min(max(0,end),length)}; empty
     * when {@code lo>=hi}; the result is the scalar slice
     * {@code [lo,hi)}. Policy {@code NO_DEAL_FAILURE} — never fails.
     *
     */
    public static Outcome<Value> stringSubstring(SourceOrigin origin,
                                                 UnicodeScalars.Valid input,
                                                 double start, double end) {
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(input, "input must not be null");
        if (!Double.isFinite(start) || start != Math.rint(start)
                || !Double.isFinite(end) || end != Math.rint(end)) {
            return failOf(FailurePolicyId.SUBSTRING_BOUNDS, 0, null, null,
                new LinkedHashMap<>(), origin);
        }
        int[] codePoints = input.carrier().codePoints().toArray();
        int lo = (int) Math.min(Math.max(0, start), codePoints.length);
        int hi = (int) Math.min(Math.max(0, end), codePoints.length);
        if (lo >= hi) {
            return new Outcome.Success<>(Value.string(""));
        }
        return new Outcome.Success<>(
            Value.string(new java.lang.String(codePoints, lo, hi - lo)));
    }

    /**
     * {@code STRING_CONTAINS}: literal scalar-subsequence comparison —
     * true iff {@code part} occurs as a scalar subsequence of
     * {@code input}; an empty part is contained. Policy
     * {@code NO_DEAL_FAILURE} — never fails.
     *
     */
    public static Outcome<Value> stringContains(SourceOrigin origin,
                                                UnicodeScalars.Valid input,
                                                UnicodeScalars.Valid part) {
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(part, "part must not be null");
        return new Outcome.Success<>(new Value.Bool(indexOfSubsequence(
            codePointsOf(input), codePointsOf(part), 0) >= 0));
    }

    /**
     * {@code STRING_STARTS_WITH}: literal scalar-subsequence comparison —
     * true iff {@code input} starts with the scalar sequence
     * {@code part}; an empty part is a prefix. Policy
     * {@code NO_DEAL_FAILURE} — never fails.
     *
     */
    public static Outcome<Value> stringStartsWith(SourceOrigin origin,
                                                  UnicodeScalars.Valid input,
                                                  UnicodeScalars.Valid part) {
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(part, "part must not be null");
        int[] inputCodePoints = codePointsOf(input);
        int[] partCodePoints = codePointsOf(part);
        return new Outcome.Success<>(new Value.Bool(
            partCodePoints.length <= inputCodePoints.length
                && matchAt(inputCodePoints, partCodePoints, 0)));
    }

    /**
     * {@code STRING_ENDS_WITH}: literal scalar-subsequence comparison —
     * true iff {@code input} ends with the scalar sequence
     * {@code part}; an empty part is a suffix. Policy
     * {@code NO_DEAL_FAILURE} — never fails.
     *
     */
    public static Outcome<Value> stringEndsWith(SourceOrigin origin,
                                                UnicodeScalars.Valid input,
                                                UnicodeScalars.Valid part) {
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(part, "part must not be null");
        int[] inputCodePoints = codePointsOf(input);
        int[] partCodePoints = codePointsOf(part);
        return new Outcome.Success<>(new Value.Bool(
            partCodePoints.length <= inputCodePoints.length
                && matchAt(inputCodePoints, partCodePoints,
                    inputCodePoints.length - partCodePoints.length)));
    }

    /**
     * {@code STRING_REPLACE}: replace all non-overlapping occurrences of
     * {@code from} in {@code input}, left-to-right; an empty
     * {@code from} returns the input unchanged; the replacement
     * {@code to} is literal — never pattern-interpreted. Policy
     * {@code NO_DEAL_FAILURE} — never fails.
     *
     */
    public static Outcome<Value> stringReplace(SourceOrigin origin,
                                               UnicodeScalars.Valid input,
                                               UnicodeScalars.Valid from,
                                               UnicodeScalars.Valid to) {
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        int[] inputCodePoints = codePointsOf(input);
        int[] fromCodePoints = codePointsOf(from);
        int[] toCodePoints = codePointsOf(to);
        if (fromCodePoints.length == 0) {
            return new Outcome.Success<>(Value.string(input));
        }
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        while (cursor <= inputCodePoints.length - fromCodePoints.length) {
            if (matchAt(inputCodePoints, fromCodePoints, cursor)) {
                appendCodePoints(result, toCodePoints);
                cursor += fromCodePoints.length;
            } else {
                result.appendCodePoint(inputCodePoints[cursor]);
                cursor++;
            }
        }
        for (int i = cursor; i < inputCodePoints.length; i++) {
            result.appendCodePoint(inputCodePoints[i]);
        }
        return new Outcome.Success<>(Value.string(result.toString()));
    }

    /**
     * {@code STRING_SPLIT}: empty input → {@code []} (regardless of the
     * separator); empty separator → one single-scalar string per
     * element; otherwise split at every non-overlapping literal
     * separator occurrence with leading/internal/trailing empty parts
     * preserved. Policy {@code NO_DEAL_FAILURE} — never fails.
     *
     */
    public static Outcome<Value> stringSplit(SourceOrigin origin,
                                             UnicodeScalars.Valid input,
                                             UnicodeScalars.Valid separator) {
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(separator, "separator must not be null");
        int[] inputCodePoints = codePointsOf(input);
        int[] separatorCodePoints = codePointsOf(separator);
        if (inputCodePoints.length == 0) {
            return new Outcome.Success<>(Value.array());
        }
        if (separatorCodePoints.length == 0) {
            List<Value> singles = new ArrayList<>(inputCodePoints.length);
            for (int codePoint : inputCodePoints) {
                singles.add(Value.string(UnicodeScalars.scalarString(codePoint)));
            }
            return new Outcome.Success<>(Value.array(singles));
        }
        List<Value> parts = new ArrayList<>();
        int cursor = 0;
        int occurrence;
        while ((occurrence = indexOfSubsequence(inputCodePoints, separatorCodePoints,
                cursor)) >= 0) {
            parts.add(Value.string(
                new java.lang.String(inputCodePoints, cursor, occurrence - cursor)));
            cursor = occurrence + separatorCodePoints.length;
        }
        // The trailing part is always appended — an empty remainder after
        // a separator at the end is preserved exactly.
        parts.add(Value.string(new java.lang.String(inputCodePoints, cursor,
            inputCodePoints.length - cursor)));
        return new Outcome.Success<>(Value.array(parts));
    }

    /**
     * {@code STRING_TRIM}: remove leading/trailing characters in exactly
     * the closed set U+0009–U+000D and U+0020 (tab, LF, VT, FF, CR, and
     * space — U+000B/U+000C are trimmed; U+00A0 is not). Interior
     * characters are never removed. Policy {@code NO_DEAL_FAILURE} —
     * never fails.
     *
     */
    public static Outcome<Value> stringTrim(SourceOrigin origin,
                                            UnicodeScalars.Valid input) {
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(input, "input must not be null");
        int[] codePoints = codePointsOf(input);
        int first = 0;
        while (first < codePoints.length && isTrimScalar(codePoints[first])) {
            first++;
        }
        int last = codePoints.length;
        while (last > first && isTrimScalar(codePoints[last - 1])) {
            last--;
        }
        return new Outcome.Success<>(Value.string(
            new java.lang.String(codePoints, first, last - first)));
    }

    /** The closed trim set: U+0009–U+000D and U+0020, exactly. */
    private static boolean isTrimScalar(int codePoint) {
        return (codePoint >= 0x09 && codePoint <= 0x0D) || codePoint == 0x20;
    }

    // =========================================================================
    // Table
    // =========================================================================

    /**
     * {@code TABLE_KEYS}: the string keys of the semantic table in
     * first-insertion order (delete removes the order slot and
     * reinsertion appends it — the {@link SemanticTable} order
     * contract); non-string keys never exist on a semantic table. Policy
     * {@code NO_DEAL_FAILURE} — never fails.
     *
     */
    public static Outcome<Value> tableKeys(SourceOrigin origin,
                                           SemanticTable<Value> table) {
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(table, "table must not be null");
        List<Value> keys = new ArrayList<>();
        for (String key : table.keys()) {
            keys.add(Value.string(key));
        }
        return new Outcome.Success<>(Value.array(keys));
    }

    // =========================================================================
    // JSON
    // =========================================================================

    // Stable JSON_PARSE_SYNTAX defect-classification texts owned by this
    // component ({reason} metadata). The set is closed: every parse
    // defect classifies into exactly one of these texts.
    /** The defect class: a character that cannot appear at its position (value start, raw control). */
    public static final String REASON_UNEXPECTED_CHARACTER = "unexpected character";
    /** The defect class: a string reaching the end of input without its closing quote. */
    public static final String REASON_UNTERMINATED_STRING = "unterminated string";
    /** The defect class: an object reaching the end of input after a complete member. */
    public static final String REASON_UNTERMINATED_OBJECT = "unterminated object";
    /** The defect class: an array reaching the end of input after a complete element. */
    public static final String REASON_UNTERMINATED_ARRAY = "unterminated array";
    /** The defect class: an unknown escape or a malformed {@code \\uXXXX} escape. */
    public static final String REASON_INVALID_ESCAPE = "invalid escape";
    /** The defect class: a {@code \\uXXXX} surrogate escape not paired with its counterpart. */
    public static final String REASON_UNPAIRED_SURROGATE_ESCAPE = "unpaired surrogate escape";
    /** The defect class: a number violating the RFC-8259 grammar (not a leading-zero defect). */
    public static final String REASON_INVALID_NUMBER = "invalid number";
    /** The defect class: an integer part with a leading zero followed by another digit. */
    public static final String REASON_LEADING_ZERO = "leading zero";
    /** The defect class: an object member not starting with a string key. */
    public static final String REASON_MISSING_KEY = "missing key";
    /** The defect class: a member key not followed by {@code :}. */
    public static final String REASON_MISSING_COLON = "missing colon";
    /** The defect class: a member/element not followed by {@code ,} or its closer. */
    public static final String REASON_MISSING_COMMA = "missing comma";
    /** The defect class: content after the top-level value. */
    public static final String REASON_TRAILING_CONTENT = "trailing content";
    /** The defect class: the end of input where a value was required. */
    public static final String REASON_UNEXPECTED_END = "unexpected end of input";
    /**
     * The defect class: a member key whose decoded scalar text equals an
     * earlier member key of the same object (an escaped spelling of the
     * same decoded key included). The reported position is the second
     * key's opening quote.
     */
    public static final String REASON_DUPLICATE_KEY = "duplicate key";

    /**
     * The {@code STDLIB_CALL(JSON_STRINGIFY)} rejection's expected text —
     * the {@code JSON_STRINGIFY_UNSUPPORTED} arm's own pinned text (the
     * single source): the value kinds JSON encoding admits. The
     * primitive's projection and the two target runtimes' stdlib
     * realizations render the arm's own field, so the three consumers are
     * compared event-for-event.
     */
    public static final String JSON_STRINGIFY_EXPECTED =
        FailureContractRegistry.arm(FailureArmId.JSON_STRINGIFY_UNSUPPORTED)
            .pinnedExpectedText();

    /** The internal parse failure: the defect class and its 1-based UTF-8 byte offset. */
    private static final class JsonParseFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        final String reason;
        final long oneBasedByteOffset;

        JsonParseFailure(String reason, long oneBasedByteOffset) {
            super(reason + " at byte " + oneBasedByteOffset);
            this.reason = reason;
            this.oneBasedByteOffset = oneBasedByteOffset;
        }
    }

    /**
     * The internal stringify cycle failure: a path-local container
     * re-entry, selecting the walk family's closed cycle arm (no
     * parameters, no expected/actual).
     */
    private static final class JsonStringifyCycle extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }

    /**
     * The internal stringify failure: the first declaration-order path and
     * the failing value's carrier kind (the arm's actual projection).
     */
    private static final class JsonStringifyFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        final String fieldPath;
        final FailureProjections.CarrierKind carrierKind;

        JsonStringifyFailure(String fieldPath, FailureProjections.CarrierKind carrierKind) {
            super("value at " + fieldPath + " is not JSON serializable: "
                + FailureProjections.carrierKindToken(carrierKind));
            this.fieldPath = fieldPath;
            this.carrierKind = carrierKind;
        }
    }

    /**
     * {@code JSON_PARSE}: RFC-8259 scalar-valid parse of the (already
     * scalar-valid) input; object order follows text; a member key whose
     * decoded scalar text equals an earlier member key of the same object
     * is a syntax defect ({@code duplicate key}) at the second key's
     * opening quote; every numeric lexeme becomes {@link Value.Number},
     * rounded to binary64 with negative zero preserved; a syntax defect fails
     * with policy {@code JSON_PARSE_SYNTAX} — E8001
     * {@code JSON parse error at position {oneBasedByteOffset}: {reason}}
     * with metadata {@code {oneBasedByteOffset}} (the defect's 1-based
     * UTF-8 byte offset) and {@code {reason}} (one of the
     * {@code REASON_*} defect-classification texts), origin = the
     * {@code STDLIB_CALL(JSON_PARSE)} call origin, no cause, active
     * frames. A successful parse whose top-level value is not a table
     * fails the {@code STDLIB_RETURN} boundary afterwards — that
     * boundary is the call machine's, never this primitive's.
     *
     * <p>Offset rule (owned here): the 1-based UTF-8 byte offset of the
     * defect position — the offending character for a character-class
     * defect (unexpected character, invalid escape, unpaired surrogate
     * escape, invalid number, leading zero, missing key/colon/comma,
     * trailing content), and one past the last input byte for an
     * end-of-input defect (unterminated string/object/array, unexpected
     * end of input). Multi-byte scalars count their full UTF-8 length.</p>
     *
     */
    public static Outcome<Value> jsonParse(SourceOrigin origin,
                                           UnicodeScalars.Valid input) {
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(input, "input must not be null");
        JsonReader reader = new JsonReader(input.carrier().codePoints().toArray());
        try {
            // RFC-8259: whitespace may precede and follow the top-level value.
            reader.skipWs();
            Value value = reader.parseValue();
            reader.skipWs();
            if (!reader.atEnd()) {
                throw reader.failure(REASON_TRAILING_CONTENT);
            }
            return new Outcome.Success<>(value);
        } catch (JsonParseFailure parseFailure) {
            return failOf(FailurePolicyId.JSON_PARSE_SYNTAX, 0, null, null,
                parseMetadata(parseFailure.oneBasedByteOffset, parseFailure.reason),
                origin);
        }
    }

    /**
     * {@code JSON_STRINGIFY}: finite acyclic JSON-shaped data
     * (string-keyed objects, arrays, and null/boolean/int/number/string
     * leaves); object fields in first-insertion order, arrays in index
     * order; RFC-8259 escaping (control characters U+0000–U+001F as the
     * named short escapes {@code \b \f \n \r \t} or {@code \\u00XX},
     * quote, backslash; surrogate pairs emitted as raw scalar UTF-8);
     * shortest round-trippable decimal number formatting
     * ({@code Double.toString}); the first unsupported value in
     * declaration order fails through the
     * {@code JSON_STRINGIFY_UNSUPPORTED} arm — E8001
     * {@code unsupported type for JSON encoding: {actual}} with the
     * arm's own pinned expected text and actual = the failing value's
     * carrier-kind projection ({@code nil}, {@code table},
     * {@code function}, {@code number}, {@code string}),
     * origin = the call origin. The walker's field path stays internal
     * and never surfaces. Unsupported values and nonfinite numbers fail
     * through that arm; a path-local container re-entry (a true cycle)
     * fails through the walk family's closed cycle arm — E8001
     * {@code cyclic value cannot be encoded as JSON} with no parameters
     * and no expected/actual; acyclic finite data never fails, shared
     * acyclic children are duplicated rather than rejected.
     *
     * <p>{@code {fieldPath}} spelling (owned here): a dot-separated
     * pre-order traversal from the root value — table fields append the
     * field name, array elements append the 0-based element index, and
     * the root itself has the empty path ({@code {"a": [1, fn]}} fails
     * at {@code a.1}). The first failure in declaration order wins:
     * table keys in first-insertion order, array elements in index
     * order, pre-order depth-first.</p>
     *
     */
    public static Outcome<Value> jsonStringify(SourceOrigin origin,
                                               SemanticTable<Value> table) {
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(table, "table must not be null");
        StringBuilder out = new StringBuilder();
        Set<Object> path = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            stringifyRoot(out, table, path);
        } catch (JsonStringifyCycle cycle) {
            // The walk family's closed cycle arm: the needle's own fact
            // selects the arm at the call origin, with no parameters and
            // no expected/actual fields.
            return failOf(FailurePolicyId.JSON_TO_ERROR, 2, null, null,
                new LinkedHashMap<>(), origin);
        } catch (JsonStringifyFailure stringifyFailure) {
            // The STDLIB_CALL(JSON_STRINGIFY) rejection: the
            // JSON_STRINGIFY_UNSUPPORTED arm's own render — its pinned
            // expected text and the carrier-kind projection of the
            // failing value. The walk's own position (the {fieldPath}
            // spelling owned here) stays internal metadata and is never
            // part of the visible projection the three consumers compare.
            return new Outcome.Failure<>(new StdlibFailure(
                withWalkMetadata(
                    FailureProjections.stringifyUnsupported(
                        stringifyFailure.carrierKind),
                    stringifyFailure.fieldPath),
                origin));
        }
        return new Outcome.Success<>(Value.string(out.toString()));
    }

    /**
     * One node of the walk's lazy field path: the parent chain plus this
     * node's segment. The dot-separated text is rendered only when a
     * failure projects it, so a deep chain never builds a quadratic path
     * prefix.
     */
    private static final class PathNode {

        final PathNode parent;
        final String segment;

        PathNode(PathNode parent, String segment) {
            this.parent = parent;
            this.segment = segment;
        }
    }

    /** Renders one child position's dot-separated path (the root path is empty). */
    private static String pathText(PathNode parent, String segment) {
        if (parent == null) {
            return segment;
        }
        ArrayDeque<String> segments = new ArrayDeque<>();
        for (PathNode node = parent; node != null; node = node.parent) {
            segments.push(node.segment);
        }
        StringBuilder text = new StringBuilder();
        for (String part : segments) {
            text.append(part).append('.');
        }
        return text.append(segment).toString();
    }

    /**
     * One open container of the stack-safe stringify walk: the explicit
     * work stack replaces recursion, so a finite nested input is never
     * limited by the JVM stack. Each frame emits its entries in
     * first-insertion/index order from {@link #index}.
     */
    private static final class StringifyFrame {

        final SemanticTable<Value> table;
        final SemanticArray<Value> array;
        final List<String> keys;
        final PathNode path;
        int index;

        StringifyFrame(SemanticTable<Value> table, List<String> keys,
                       PathNode path) {
            this.table = table;
            this.array = null;
            this.keys = keys;
            this.path = path;
        }

        StringifyFrame(SemanticArray<Value> array, PathNode path) {
            this.table = null;
            this.array = array;
            this.keys = null;
            this.path = path;
        }
    }

    /**
     * The stack-safe container walk: an explicit work stack replaces the
     * recursive descent ({@code stringifyTable}/{@code stringifyArray}/
     * {@code stringifyValue}), so a finite nested input has no stack
     * bound. The identity set keeps path-local cycle detection (entered
     * on open, removed on close); entries emit in first-insertion/index
     * order and the first failure in that order wins unchanged.
     */
    private static void stringifyRoot(StringBuilder out, SemanticTable<Value> root,
                                      Set<Object> path) {
        Deque<StringifyFrame> stack = new ArrayDeque<>();
        openTable(out, root, null, path, stack);
        while (!stack.isEmpty()) {
            StringifyFrame frame = stack.peek();
            if (frame.table == null) {
                if (frame.index < frame.array.size()) {
                    int i = frame.index++;
                    if (i > 0) {
                        out.append(',');
                    }
                    stringifyChild(out, frame.array.elementAt(i), frame.path,
                        Integer.toString(i), path, stack);
                } else {
                    out.append(']');
                    path.remove(frame.array);
                    stack.pop();
                }
                continue;
            }
            if (frame.index < frame.keys.size()) {
                int i = frame.index++;
                String key = frame.keys.get(i);
                if (!(UnicodeScalars.validate(key) instanceof UnicodeScalars.Valid)) {
                    throw new Defect("a semantic-table key must be a valid scalar sequence "
                        + "(keys are identifier-derived); the key at slot " + i
                        + " is not — a producer defect, never a projection");
                }
                if (i > 0) {
                    out.append(',');
                }
                appendJsonString(out, key);
                out.append(':');
                SemanticTable.Lookup<Value> lookup = frame.table.get(key);
                if (!(lookup instanceof SemanticTable.Lookup.Present<Value> present)) {
                    throw new Defect("a table key returned by keys() is always present; got "
                        + "Missing for '" + key + "' — a producer defect, never a projection");
                }
                stringifyChild(out, present.value(), frame.path, key, path, stack);
            } else {
                out.append('}');
                path.remove(frame.table);
                stack.pop();
            }
        }
    }

    /** Opens one table on the walk: the path-local cycle check, then the pending frame. */
    private static void openTable(StringBuilder out, SemanticTable<Value> table,
                                  PathNode pathNode, Set<Object> path,
                                  Deque<StringifyFrame> stack) {
        if (!path.add(table)) {
            throw new JsonStringifyCycle();
        }
        out.append('{');
        stack.push(new StringifyFrame(table, table.keys(), pathNode));
    }

    /** Opens one array on the walk: the path-local cycle check, then the pending frame. */
    private static void openArray(StringBuilder out, SemanticArray<Value> array,
                                  PathNode pathNode, Set<Object> path,
                                  Deque<StringifyFrame> stack) {
        if (!path.add(array)) {
            throw new JsonStringifyCycle();
        }
        out.append('[');
        stack.push(new StringifyFrame(array, pathNode));
    }

    /**
     * Appends one child value: a leaf emits its text, a container checks
     * the path-local identity and opens a frame. The first
     * declaration-order failure wins (pre-order), exactly as the
     * recursive walk selected it.
     */
    private static void stringifyChild(StringBuilder out, Value value, PathNode parentPath,
                                       String segment, Set<Object> path,
                                       Deque<StringifyFrame> stack) {
        switch (value) {
            case Value.Null ignored -> out.append("null");
            case Value.Bool bool -> out.append(bool.value() ? "true" : "false");

            case Value.Number number -> {
                if (!Double.isFinite(number.value())) {
                    throw new JsonStringifyFailure(pathText(parentPath, segment),
                        FailureProjections.CarrierKind.NUMBER);
                }
                out.append((number.value() == 0 ? "0" : Double.toString(number.value())));
            }
            case Value.String string -> {
                if (string.scalar() instanceof UnicodeScalars.Invalid) {
                    // An invalid scalar sequence is still a string carrier
                    // on the carrier-kind projection.
                    throw new JsonStringifyFailure(pathText(parentPath, segment),
                        FailureProjections.CarrierKind.STRING);
                }
                appendJsonString(out, ((UnicodeScalars.Valid) string.scalar()).carrier());
            }
            case Value.Table table -> openTable(out, table.table(),
                new PathNode(parentPath, segment), path, stack);
            case Value.Array array -> openArray(out, array.elements(),
                new PathNode(parentPath, segment), path, stack);
            case Value.Other other -> throw new JsonStringifyFailure(
                pathText(parentPath, segment), stringifyCarrierKind(other));
        }
    }

    /**
     * RFC-8259 string escaping: quote and backslash escaped; the named
     * short escapes {@code \b \f \n \r \t}; every other control scalar
     * (U+0000–U+001F) as {@code \\u00XX} (lowercase hex); every other
     * scalar — surrogate pairs included — emitted as raw scalar UTF-8.
     */
    private static void appendJsonString(StringBuilder out, String carrier) {
        out.append('"');
        for (int i = 0; i < carrier.length(); ) {
            int codePoint = carrier.codePointAt(i);
            i += Character.charCount(codePoint);
            switch (codePoint) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (codePoint < 0x20) {
                        out.append(String.format("\\u%04x", codePoint));
                    } else {
                        out.appendCodePoint(codePoint);
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * The RFC-8259 recursive-descent reader over the input's code points,
     * tracking the 1-based UTF-8 byte offset of the current scan position.
     */
    private static final class JsonReader extends JsonScan.CodePointCursor {

        JsonReader(int[] codePoints) {
            super(codePoints);
        }

        @Override
        public JsonParseFailure failure(String reason) {
            return new JsonParseFailure(reason, offsetOfCurrent());
        }

        /**
         * One open container of the stack-safe parser. The frame's
         * {@code state} drives its member loop and {@code pendingKey}
         * carries the object member awaiting its value.
         */
        private static final class Frame {

            static final int OBJECT_FIRST = 0;
            static final int OBJECT_KEY = 1;
            static final int VALUE = 2;
            static final int OBJECT_AFTER = 3;
            static final int ARRAY_FIRST = 4;
            static final int ARRAY_AFTER = 5;

            final boolean array;
            final SemanticTable<Value> table;
            final List<Value> elements;
            final Set<String> decodedKeys;
            String pendingKey;
            int state;

            private Frame(boolean array, SemanticTable<Value> table,
                          List<Value> elements, Set<String> decodedKeys, int state) {
                this.array = array;
                this.table = table;
                this.elements = elements;
                this.decodedKeys = decodedKeys;
                this.state = state;
            }

            static Frame object() {
                return new Frame(false, new SemanticTable<>(), null, new HashSet<>(),
                    OBJECT_FIRST);
            }

            static Frame array() {
                return new Frame(true, null, new ArrayList<>(), null, ARRAY_FIRST);
            }
        }

        /**
         * Parses one value at the cursor: a scalar returns directly, a
         * container pushes its frame on {@code stack} and returns
         * {@code null}. The frame loop of {@link #parseValue()} then
         * drives the open container.
         */
        private Value parseValueAtCursor(Deque<Frame> stack) {
            if (atEnd()) {
                throw failure(REASON_UNEXPECTED_END);
            }
            int c = peek();
            switch (c) {
                case '{' -> {
                    advance(); // '{'
                    stack.push(Frame.object());
                    return null;
                }
                case '[' -> {
                    advance(); // '['
                    stack.push(Frame.array());
                    return null;
                }
                case '"' -> {
                    return Value.string(parseString());
                }
                case 't' -> {
                    return parseLiteral("true", new Value.Bool(true));
                }
                case 'f' -> {
                    return parseLiteral("false", new Value.Bool(false));
                }
                case 'n' -> {
                    return parseLiteral("null", Value.Null.INSTANCE);
                }
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw failure(REASON_UNEXPECTED_CHARACTER);
                }
            }
        }

        /**
         * The stack-safe parse of one value: an explicit frame stack
         * replaces the recursive descent, so a finite nested document
         * has no stack bound. Every transition, defect class, and
         * 1-based UTF-8 byte offset is the recursive reader's.
         */
        Value parseValue() {
            Deque<Frame> stack = new ArrayDeque<>();
            Value completed = null;
            while (true) {
                if (completed != null) {
                    if (stack.isEmpty()) {
                        return completed;
                    }
                    Frame parent = stack.peek();
                    if (parent.array) {
                        parent.elements.add(completed);
                        parent.state = Frame.ARRAY_AFTER;
                    } else {
                        parent.table.put(parent.pendingKey, completed);
                        parent.state = Frame.OBJECT_AFTER;
                    }
                    completed = null;
                    continue;
                }
                if (stack.isEmpty()) {
                    completed = parseValueAtCursor(stack);
                    continue;
                }
                Frame frame = stack.peek();
                switch (frame.state) {
                    case Frame.OBJECT_FIRST -> {
                        skipWs();
                        if (!atEnd() && peek() == '}') {
                            advance();
                            completed = new Value.Table(frame.table);
                            stack.pop();
                        } else {
                            frame.state = Frame.OBJECT_KEY;
                        }
                    }
                    case Frame.OBJECT_KEY -> {
                        skipWs();
                        if (atEnd()) {
                            throw failure(REASON_UNEXPECTED_END);
                        }
                        if (peek() != '"') {
                            throw failure(REASON_MISSING_KEY);
                        }
                        // The duplicate-key defect's position is the
                        // second key's opening quote (one-based UTF-8
                        // byte offset); decoded equality makes an
                        // escaped spelling of the same key a duplicate
                        // too.
                        long keyOffset = offsetOfCurrent();
                        String key = parseString();
                        if (!frame.decodedKeys.add(key)) {
                            throw new JsonParseFailure(REASON_DUPLICATE_KEY, keyOffset);
                        }
                        skipWs();
                        if (atEnd() || peek() != ':') {
                            throw failure(REASON_MISSING_COLON);
                        }
                        advance(); // ':'
                        frame.pendingKey = key;
                        frame.state = Frame.VALUE;
                    }
                    case Frame.VALUE -> {
                        skipWs();
                        if (atEnd()) {
                            throw failure(REASON_UNEXPECTED_END);
                        }
                        completed = parseValueAtCursor(stack);
                    }
                    case Frame.OBJECT_AFTER -> {
                        skipWs();
                        if (atEnd()) {
                            throw failure(REASON_UNTERMINATED_OBJECT);
                        }
                        int c = peek();
                        if (c == ',') {
                            advance();
                            frame.state = Frame.OBJECT_KEY;
                        } else if (c == '}') {
                            advance();
                            completed = new Value.Table(frame.table);
                            stack.pop();
                        } else {
                            throw failure(REASON_MISSING_COMMA);
                        }
                    }
                    case Frame.ARRAY_FIRST -> {
                        skipWs();
                        if (!atEnd() && peek() == ']') {
                            advance();
                            completed = Value.array(frame.elements);
                            stack.pop();
                        } else {
                            frame.state = Frame.VALUE;
                        }
                    }
                    case Frame.ARRAY_AFTER -> {
                        skipWs();
                        if (atEnd()) {
                            throw failure(REASON_UNTERMINATED_ARRAY);
                        }
                        int c = peek();
                        if (c == ',') {
                            advance();
                            frame.state = Frame.VALUE;
                        } else if (c == ']') {
                            advance();
                            completed = Value.array(frame.elements);
                            stack.pop();
                        } else {
                            throw failure(REASON_MISSING_COMMA);
                        }
                    }
                    default -> throw new Defect("unknown JSON reader frame state "
                        + frame.state);
                }
            }
        }

        Value parseNumber() {
            // The exact decimal classification (DEAL v1.4): the lexeme
            // denotes an Int exactly when its mathematical value is
            // rounded to binary64; every numeric lexeme denotes
            // the binary64 value of the standard conversion.
            JsonScan.CodePointCursor.NumberScan scan = parseNumberText();
            return new Value.Number(JsonNumberClassifier.toDouble(scan.text()));
        }
    }

    // =========================================================================
    // Math
    // =========================================================================

    /**
     * {@code MATH_FLOOR}: IEEE floor, returned as Number. Policy
     * {@code NO_DEAL_FAILURE} — never fails ({@code NaN} → {@code NaN},
     * {@code ±Infinity} → {@code ±Infinity}).
     *
     */
    public static Outcome<Value> mathFloor(SourceOrigin origin, double value) {
        Objects.requireNonNull(origin, "origin must not be null");
        return new Outcome.Success<>(new Value.Number(Math.floor(value)));
    }

    /**
     * {@code MATH_CEIL}: IEEE ceiling, returned as Number. Policy
     * {@code NO_DEAL_FAILURE} — never fails.
     *
     */
    public static Outcome<Value> mathCeil(SourceOrigin origin, double value) {
        Objects.requireNonNull(origin, "origin must not be null");
        return new Outcome.Success<>(new Value.Number(Math.ceil(value)));
    }

    /**
     * {@code MATH_SQRT}: IEEE sqrt; a negative non-NaN input fails
     * {@code SQRT_NEGATIVE} (E8001 {@code sqrt of negative number} at
     * the operation origin); NaN returns NaN and {@code -0.0} returns
     * {@code -0.0} (IEEE). The failure carries the negative operand in
     * its {@code actual} field as the canonical IEEE-754 hex-float
     * spelling ({@link CanonicalJson#numberHex}).
     *
     */
    public static Outcome<Value> mathSqrt(SourceOrigin origin, double value) {
        Objects.requireNonNull(origin, "origin must not be null");
        if (value < 0) {
            return failOf(FailurePolicyId.SQRT_NEGATIVE, 0, null,
                CanonicalJson.numberHex(value), new LinkedHashMap<>(), origin);
        }
        return new Outcome.Success<>(new Value.Number(Math.sqrt(value)));
    }


/**
     * {@code MATH_ABS_NUMBER}: IEEE absolute value ({@code -0.0} →
     * {@code +0.0}, {@code NaN} → {@code NaN},
     * {@code -Infinity} → {@code +Infinity}). Policy
     * {@code NO_DEAL_FAILURE} — never fails.
     *
     */
    public static Outcome<Value> mathAbsNumber(SourceOrigin origin, double value) {
        Objects.requireNonNull(origin, "origin must not be null");
        return new Outcome.Success<>(new Value.Number(Math.abs(value)));
    }


/**
     * {@code TIME_NOW_MILLIS} — the K7 {@code std.time}/{@code nowMillis}
     * operation: the target-clock reading returned as a
     * {@link Value.Number} (epoch milliseconds — an exact double for
     * contemporary readings). The operation has no parameter boundary and
     * its single terminal is the declared {@code number}
     * {@code STDLIB_RETURN} boundary under the descriptor-kind rule: a
     * contemporary epoch-millisecond reading (above the signed-32 range)
     * crosses unchanged, and no int terminal exists (v1.4). Policy
     * {@code NO_DEAL_FAILURE}; the reading itself never fails.
     *
     */
    public static Outcome<Value> timeNowMillis(SourceOrigin origin, Clock clock) {
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new Outcome.Success<>(new Value.Number((double) clock.nowMillis()));
    }

    // =========================================================================
    // Scalar-sequence helpers
    // =========================================================================

    /** The code points of a valid scalar sequence in scalar order. */
    private static int[] codePointsOf(UnicodeScalars.Valid valid) {
        return valid.carrier().codePoints().toArray();
    }

    /** True iff {@code needle} occurs at {@code offset} of {@code haystack}. */
    private static boolean matchAt(int[] haystack, int[] needle, int offset) {
        for (int i = 0; i < needle.length; i++) {
            if (haystack[offset + i] != needle[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The first occurrence of {@code needle} in {@code haystack} at or
     * after {@code fromIndex}, or {@code -1} — a literal scalar
     * subsequence search.
     */
    private static int indexOfSubsequence(int[] haystack, int[] needle, int fromIndex) {
        if (needle.length == 0) {
            return fromIndex <= haystack.length ? fromIndex : -1;
        }
        for (int i = Math.max(0, fromIndex); i + needle.length <= haystack.length; i++) {
            if (matchAt(haystack, needle, i)) {
                return i;
            }
        }
        return -1;
    }

    /** Appends code points to the builder in order. */
    private static void appendCodePoints(StringBuilder builder, int[] codePoints) {
        for (int codePoint : codePoints) {
            builder.appendCodePoint(codePoint);
        }
    }

}
