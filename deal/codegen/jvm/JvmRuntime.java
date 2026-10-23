package deal.codegen.jvm;

import deal.semantic.SemanticRuntimeModel;
import deal.semantic.SharedStdlibSemantics;
import deal.semantic.ir.ActualKind;
import deal.semantic.ir.JsonNumberClassifier;
import deal.semantic.ir.JsonScan;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class JvmRuntime {

    private JvmRuntime() {
    }

    // =========================================================================
    // Values
    // =========================================================================

    /** The internal missing sentinel (past-end array reads). */
    public static final Object MISSING = new Object() {
        @Override public String toString() {
            return "missing";
        }
    };

    /**
     * The program-scoped export-surface registry (M2): one {@link Table}
     * per module of the program, keyed by the module identity (the dotted
     * module path). The registry is hosted with the runtime's existing
     * program state ({@link #TASKS}, {@link #MODULE_STATES}), so every
     * generated class of one program resolves the same per-module
     * surfaces — a per-unit class resolves the surface its owner class
     * published in the same program, exactly like the LuaJIT chunk-global
     * registry. Written only by the owner's {@code EXPORT_PUBLISH}, a HOST
     * module's load (the loaded module table's entries), and the session's
     * per-module get-or-create; a read never writes it.
     */
    public static final LinkedHashMap<String, Table> EXPORT_SURFACES =
        new LinkedHashMap<>();

    /**
     * The idempotent per-module surface accessor of the program-scoped
     * registry: the existing surface of the module, or a freshly created
     * empty one (a repeated drive never wipes a published surface).
     *
     */
    public static Table exportSurface(String module) {
        Table surface = EXPORT_SURFACES.get(module);
        if (surface == null) {
            surface = new Table();
            EXPORT_SURFACES.put(module, surface);
        }
        return surface;
    }

    public static String[] surfaceNameOf(Object value) {
        if (value == null) {
            return null;
        }
        String[] found = null;
        for (Map.Entry<String, Table> module : EXPORT_SURFACES.entrySet()) {
            for (Map.Entry<String, Object> entry : module.getValue().entries.entrySet()) {
                if (entry.getValue() == value) {
                    if (found != null) {
                        return null;
                    }
                    found = new String[] { module.getKey(), entry.getKey() };
                }
            }
        }
        return found;
    }

    /**
     * The program-scoped cataloged-callable registry (M4): one memoized
     * carrier per catalog row per module per program, keyed by the module
     * identity and the export name. Hosted with the runtime's existing
     * program state ({@link #TASKS}, {@link #MODULE_STATES}, and
     * {@link #EXPORT_SURFACES}), so every generated class of one program
     * resolves the same callable object — a read before any surface
     * population and the later whole-surface population observe one
     * callable per row. Written only by the memoized accessor; never by a
     * read.
     */
    public static final LinkedHashMap<String, StdlibFunctionValue> STDLIB_CALLABLES =
        new LinkedHashMap<>();

    /**
     * The memoized accessor of the program-scoped cataloged callables (M4):
     * the existing carrier of one catalog row, or a freshly created one
     * (a repeated drive never replaces a resolved callable). The carrier
     * carries the row's declared signature text and canonical spec text —
     * never the reading site's descriptor.
     *
     */
    public static StdlibFunctionValue stdlibCallable(String module, String name,
                                                     String rowId, String signature,
                                                     String spec) {
        String key = module + '\u0001' + name;
        StdlibFunctionValue callable = STDLIB_CALLABLES.get(key);
        if (callable == null) {
            callable = new StdlibFunctionValue(rowId, signature, spec);
            STDLIB_CALLABLES.put(key, callable);
        }
        return callable;
    }

    public static final java.util.HashSet<Long> BODY_ACTIVE =
        new java.util.HashSet<>();

    /** A string-keyed table with explicit key presence. */
    public static final class Table {
        public final LinkedHashMap<String, Object> entries = new LinkedHashMap<>();
        public final java.util.HashSet<String> keys = new java.util.HashSet<>();

        public Object read(String key) {
            if (!keys.contains(key)) {
                return MISSING;
            }
            return entries.get(key);
        }

        public void write(String key, Object value) {
            entries.put(key, value);
            keys.add(key);
        }

        public void remove(String key) {
            entries.remove(key);
            keys.remove(key);
        }
    }

    /**
     * A generated class instance (the CLASSES family's presence
     * carrier, E5): the canonical class identity tag plus per-field
     * value slots and boolean presence flags — present null is
     * {@code value == null && present == true} and is never conflated
     * with a missing field. The emitter generates one carrier class per
     * {@code ClassId}; this interface is the runtime's closed surface
     * over it (presence lookups, reads, writes, deletes).
     */
    public interface ClassInstance {

        /** The canonical {@code @modulePath/ClassName} identity text. */
        String classIdText();

        /** Presence of one field: a present field (present null included) is present. */
        boolean isPresent(String field);

        /** The present field's value, or {@link #MISSING} for an absent field. */
        Object read(String field);

        /** Stores one field value and marks the field present. */
        void write(String field, Object value);

        /**
         * Clears one field: the value slot is emptied and the presence
         * flag is cleared, so the field is missing again (the
         * {@code FIELD_DELETE} commit). Deleting an already-missing
         * field is a no-op.
         */
        void delete(String field);
    }

    /**
     * HAS_FIELD presence over one checked receiver key (the
     * CONTAINERS_AND_STRINGS extras): present — present null included —
     * → true, absent → false. The table-presence half realizes through
     * the explicit key set; the class-instance half realizes through the
     * generated carrier's presence flags (the CLASSES family). A
     * receiver outside the realized carriers is a fail-closed producer
     * defect, never a silent presence value.
     */
    public static boolean hasField(Object receiver, String key) {
        if (receiver instanceof Table table) {
            return table.keys.contains(key);
        }
        if (receiver instanceof ClassInstance instance) {
            return instance.isPresent(key);
        }
        throw new IllegalStateException("HAS_FIELD receiver " + receiver
            + " is not a supported presence carrier in this domain (the "
            + "presence surface admits keyed tables and generated class "
            + "instances only)");
    }

    /** A dense array with the shared slot-space length. */
    public static final class Array {
        public final List<Object> elements = new ArrayList<>();
        public int length;
        /**
         * The decoded-array mark (B3): set only by the stdlib JSON reader
         * ({@link JsonReader#parseArray}) at every decoded nesting depth.
         * The mark renders the reference value-model kind {@code table} at
         * the array-element failure projection and is inert everywhere
         * else (admission, {@link #actualOf}, the container/table ops,
         * identity, length, indexing, and stringify are unchanged).
         */
        public boolean decodedArray;

        public Array(int length) {
            this.length = length;
        }
    }

    public static final class ErrorValue implements ClassInstance {
        public String code;
        public String message;
        /**
         * The failure this Error value reifies, or {@code null} for a
         * constructed Error (the builtin Error class literal). The
         * transport travels with the value: a rethrow of a caught error
         * preserves its origin, expected/actual, frames and cause — never
         * a synthesized replacement — and one failure reifies to exactly
         * one value identity, so a rethrown error caught again is the
         * identical Error value ({@code saved === again}). The field is
         * not a declared class field: the class read surface exposes
         * exactly code and message.
         */
        public JvmRuntime.DealError transport;

        public ErrorValue(String code, String message) {
            this(code, message, null);
        }

        public ErrorValue(String code, String message, JvmRuntime.DealError transport) {
            this.code = code;
            this.message = message;
            this.transport = transport;
        }

        @Override
        public String classIdText() {
            return deal.semantic.ir.ClassId.ERROR.text();
        }

        @Override
        public boolean isPresent(String field) {
            return "code".equals(field) || "message".equals(field);
        }

        @Override
        public Object read(String field) {
            return switch (field) {
                case "code" -> code;
                case "message" -> message;
                default -> MISSING;
            };
        }

        @Override
        public void write(String field, Object value) {
            switch (field) {
                case "code" -> code = (String) value;
                case "message" -> message = (String) value;
                default -> throw new IllegalStateException(
                    "the builtin Error carrier has no field '" + field
                        + "' (producer defect)");
            }
        }

        @Override
        public void delete(String field) {
            throw new IllegalStateException("FIELD_DELETE targets the builtin Error"
                + " field '" + field + "': every builtin Error field is"
                + " required-present and the checker rejects the delete with E4004"
                + " — a fail-closed producer defect, never executed");
        }
    }


/** The program's memoized intrinsic carriers (one per kind per program). */


    /**
     * The memoized intrinsic carrier of one kind (J2): the identical
     * object for every materialization of one intrinsic, carrying the
     * kind's declared signature texts (the caller's arguments are the
     * kind's declared descriptor text and canonical spec text).
     */


/**
     * The intrinsic carrier's generic invoker (J2): the conversion or
     * allocation ladder of the carrier's kind over the caller's first
     * argument, with the invoking call's context when one is supplied
     * (the DEAL convention packs the static kind, the op key, the digest,
     * the parent key, and the origin after the value) and the absent
     * context otherwise — total and deterministic for any non-DEAL caller
     * (the host bridge and any generic unwrap). DEAL call sites run the
     * ladder directly with the invoking op's own context; the carrier's
     * invoker never invents a call site's origin, and its
     * declared-parameter kind is the default static kind (the intrinsic's
     * declared signature is the only descriptor source).
     */


    public static final class StdlibFunctionValue extends FunctionValue {
        /** The catalog row tag ({@code StdlibFunctionId} name). */
        public final String rowId;

        StdlibFunctionValue(String rowId, String signature, String spec) {
            super(args -> stdlibInvoke((String) args[0], rowId, (String) args[1],
                (String) args[2], (String) args[3], (String) args[4],
                java.util.Arrays.copyOfRange(args, 5, args.length)), signature, spec,
                null);
            this.rowId = rowId;
        }
    }

    public static Object invokeStdlibCallable(StdlibFunctionValue callable, String kind,
                                              String opKey, String digest, String parent,
                                              String origin, Object[] args) {
        Object[] packed = new Object[args.length + 5];
        packed[0] = kind;
        packed[1] = opKey;
        packed[2] = digest;
        packed[3] = parent;
        packed[4] = origin;
        System.arraycopy(args, 0, packed, 5, args.length);
        return callable.fn.invoke(packed);
    }

    /** A function value: the invoker plus its carried signature text. */
    public interface Fn {
        Object invoke(Object[] args);
    }

    public static class FunctionValue {
        public final Fn fn;
        public final String signature;
        /** The canonical spec text (E8010 projections); null when unknown. */
        public final String spec;
        /**
         * The stack-frame label of the function (the generated and
         * original function names of the shared
         * {@code FrameLabels} form, e.g. {@code lib#add@F3}); null when
         * absent. The label is also the carrier's key in the artifact's
         * function-to-module resolution.
         */
        public final String fid;

        public FunctionValue(Fn fn, String signature) {
            this(fn, signature, null, null);
        }

        public FunctionValue(Fn fn, String signature, String spec, String fid) {
            if (fid == null) {
                this.fn = fn;
            } else {
                // A DEAL body carrier owns its body's stack frame: the
                // carrier is the single layer that pushes the label for
                // its own activation and pops it on the success and the
                // failure path alike. Every invocation — the in-project
                // call paths and the host invoking a published callable —
                // therefore contributes exactly one frame per active
                // activation, repeated identical labels included, and no
                // path can drop a frame or keep a stale one.
                this.fn = args -> {
                    pushFrame(fid);
                    try {
                        return fn.invoke(args);
                    } finally {
                        popFrame();
                    }
                };
            }
            this.signature = signature;
            this.spec = spec;
            this.fid = fid;
        }
    }

    /**
     * One declared target-signature parameter cell of an arity-extension
     * adapter: the parameter's runtime descriptor text, its static kind, and
     * the canonical spec text a function-carrying position projects
     * ({@code null} otherwise) — exactly the arguments the emission-time
     * type-boundary check emits for the same descriptor.
     */
    public record TargetCell(String descriptor, String staticKind, String canonical) {
    }

    /**
     * A {@code FUNCTION_ADAPT} adapter value (D15): the closed capture
     * mode state ({@code VALUE}: the retained source identity;
     * {@code SHARED_CELL}: the generation cell re-read per invocation;
     * {@code REEVALUATE_THUNK}: the thunk re-executor), the source
     * arity/spec, the target signature carried for boundary checks, and
     * the target signature's parameter cells the invocation protocol
     * admits (the ignored trailing parameters of an arity extension
     * included — docs/spec-v1.3.md Arity extension adapter).
     */
    public static final class AdapterValue extends FunctionValue {
        /** 0 = VALUE, 1 = SHARED_CELL, 2 = REEVALUATE_THUNK. */
        public final int mode;
        public final Object value;
        public final Object[] cell;
        public final Fn thunk;
        public final int arity;
        public final String sourceSpec;
        public final TargetCell[] targetCells;

        public AdapterValue(Fn fn, String signature, String spec, int mode, Object value,
                            Object[] cell, Fn thunk, int arity, String sourceSpec,
                            TargetCell[] targetCells) {
            super(fn, signature, spec, null);
            this.mode = mode;
            this.value = value;
            this.cell = cell;
            this.thunk = thunk;
            this.arity = arity;
            this.sourceSpec = sourceSpec;
            this.targetCells = targetCells == null ? new TargetCell[0] : targetCells;
        }
    }

    /** A BREAK/CONTINUE/RETURN transfer signal crossing a try boundary. */
    public static final class Transfer extends RuntimeException {
        public final String kind;
        public final long id;
        public final Object value;

        public Transfer(String kind, long id, Object value) {
            super(null, null, false, false);
            this.kind = kind;
            this.id = id;
            this.value = value;
        }
    }

    /** A DEAL failure carrying its exact projection facts. */
    public static final class DealError extends RuntimeException {
        public final String code;
        public final String msg;
        public final String origin;
        public final String expected;
        public final String actual;
        public final String frames;
        public final DealError cause;
        /**
         * Whether this failure was published by a rethrow of a
         * transported Error value (never by a fresh runtime check, a
         * constructed Error's THROW, or a boundary re-projection). The
         * try/catch arm propagates such a failure unchanged — identity
         * preserved, no invented cause — while a genuinely new failure
         * raised while handling the caught one links the caught failure
         * as its cause.
         */
        public boolean transported;
        /** The Error value identity this failure reifies (one per failure). */
        private JvmRuntime.ErrorValue reified;

        public DealError(String code, String msg, String origin, String expected,
                         String actual, String frames, DealError cause) {
            super(msg, cause);
            this.code = code;
            this.msg = msg;
            this.origin = origin;
            this.expected = expected;
            this.actual = actual;
            this.frames = frames;
            this.cause = cause;
        }

        /**
         * The Error value this failure reifies — one identity per
         * failure, so a rethrown error caught again is the identical
         * Error value and a mutable code/message rewrite stays visible
         * through every alias of that identity.
         */
        public JvmRuntime.ErrorValue reify() {
            JvmRuntime.ErrorValue value = reified;
            if (value == null) {
                value = new JvmRuntime.ErrorValue(code, msg, this);
                reified = value;
            }
            return value;
        }
    }

    /**
     * The failure one rethrow of an Error value publishes. The value's
     * own transport travels with the rethrow: when the value's current
     * code/message are unchanged the identical failure is re-published
     * (the host observes the identical DealError), and a rewritten
     * code/message publishes a failure carrying the transport's origin,
     * expected/actual, frames and cause with the value's current
     * code/message. Either way the failure is marked transported, so an
     * enclosing catch propagates it unchanged and never invents a cause,
     * and the value's reified identity survives the rethrow.
     */
    public static DealError rethrow(ErrorValue value) {
        DealError transport = value.transport;
        if (sameText(value.code, transport.code)
                && sameText(value.message, transport.msg)) {
            transport.transported = true;
            return transport;
        }
        DealError published = new DealError(value.code, value.message, transport.origin,
            transport.expected, transport.actual, transport.frames, transport.cause);
        published.transported = true;
        published.reified = value;
        value.transport = published;
        return published;
    }

    /**
     * The failure the THROW of a constructed Error value publishes. The
     * value carries no transport yet: the failure is synthesized at the
     * THROW site's own origin with no expected/actual and no cause
     * (absent fields stay absent) and the active frames, and the value
     * adopts it. Binding the pair here keeps the spec's
     * reference-identical class instance: the catch binding is the value
     * the program threw (so {@code caught === original} and every alias
     * mutation is visible through all of them), and the value's
     * conditional-transport field carries the origin/expected/actual/
     * frames/cause a rethrow must preserve.
     */
    public static DealError raise(ErrorValue value, String origin) {
        DealError published = new DealError(value.code, value.message, origin,
            null, null, framesText(), null);
        published.reified = value;
        value.transport = published;
        return published;
    }

    /**
     * The replacement failure a catch block publishes when a genuinely new
     * failure is raised while it handles the caught one: the same failure
     * facts, the caught failure linked as the cause, and — when the new
     * failure reifies a carried Error value (the constructed Error thrown
     * inside the handler) — that very value's identity adopted, so the
     * enclosing catch binds the identical Error instance and a later
     * rethrow keeps the linked cause. A failure with no carried identity
     * (a runtime check) wraps exactly as before.
     */
    public static DealError wrap(DealError previous, DealError cause) {
        DealError wrapped = new DealError(previous.code, previous.msg,
            previous.origin, previous.expected, previous.actual,
            previous.frames, cause);
        wrapped.reified = previous.reified;
        if (wrapped.reified != null) {
            wrapped.reified.transport = wrapped;
        }
        return wrapped;
    }

    private static boolean sameText(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    // =========================================================================
    // Protocol state
    // =========================================================================

    static final ThreadLocal<List<String>> FRAMES = ThreadLocal.withInitial(ArrayList::new);
    static long seq = 0;
    static final IdentityHashMap<Object, String> ALLOC_IDS = new IdentityHashMap<>();
    static long allocNext = 1;

    /**
     * Pushes/pops the innermost active frame (the stack-frame label) of
     * one DEAL body activation. The body carrier is the only pusher (see
     * {@link FunctionValue}), so every active invocation has exactly one
     * entry, repeated labels included, and every push has its balanced
     * pop on the success and the failure path.
     */
    public static void pushFrame(String functionId) {
        FRAMES.get().add(0, functionId);
    }

    public static void popFrame() {
        FRAMES.get().remove(0);
    }

    public static String framesText() {
        List<String> frames = FRAMES.get();
        return frames.isEmpty() ? "-" : String.join(",", frames);
    }

    // =========================================================================
    // Encoding
    // =========================================================================

    public static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                case ';' -> out.append("\\u003b");
                case '|' -> out.append("\\u007c");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** The closed atom encoding (first-observation allocation ids). */
    public static String atom(Object v, String kind) {
        if (v == MISSING) {
            return "missing";
        }
        if (kind == null) {
            return "missing";
        }
        // Every admitted host numeric box denotes one binary64 number.
        if (v instanceof Number numeric) {
            double d = numeric.doubleValue();
            if (Double.isNaN(d)) {
                return "num:nan";
            }
            return "num:" + Double.toHexString(d);
        }
        // The value's own scalar kind wins over a mismatched declared kind
        // (the oracle's atomOf): a deferred contextual read lets a
        // wrong-kind value reach the consuming op's START, and the trace
        // atoms must agree on the value's actual kind. Numeric carriers
        // keep the declared-kind rule (the variant is carried by the value).
        if (v == null) {
            return "null";
        }
        if (v instanceof String stringValue) {
            return "str:" + esc(stringValue);
        }
        if (v instanceof Boolean booleanValue) {
            return "bool:" + booleanValue;
        }
        switch (kind) {
            case "null" -> {
                return "null";
            }
            case "missing" -> {
                return "missing";
            }
            case "bool" -> {
                return "bool:" + v;
            }
            case "number" -> {
                double d = (Double) v;
                if (Double.isNaN(d)) {
                    return "num:nan";
                }
                return "num:" + Double.toHexString(d);
            }
            case "string" -> {
                return "str:" + esc((String) v);
            }
            case "err" -> {
                ErrorValue error = (ErrorValue) v;
                return "err:" + error.code + ":" + esc(error.message);
            }
            default -> {
                if (kind.startsWith("nullable:")) {
                    if (v == null) {
                        return "null";
                    }
                    return atom(v, kind.substring("nullable:".length()));
                }
                if (kind.equals("ref") || kind.equals("table") || kind.equals("array")
                        || kind.equals("function")
                        || kind.equals("class")) {
                    return "ref:" + allocId(v);
                }
                return "missing";
            }
        }
    }

    static String allocId(Object heap) {
        String id = ALLOC_IDS.get(heap);
        if (id == null) {
            id = String.valueOf(allocNext++);
            ALLOC_IDS.put(heap, id);
        }
        return id;
    }

    /**
     * The raw read atom of the OPTIONAL_READ envelope: the value's
     * actual runtime kind — missing → "missing", null → "null", else
     * the actual kind's atom (a wrong-kind present value atomizes as its
     * own kind, exactly the oracle's publish). Heap values carry the
     * shared allocation-id namespace.
     */
    public static String rawAtom(Object v, String kind) {
        if (v == MISSING) {
            return "missing";
        }
        if (v == null) {
            return "null";
        }
        if (v instanceof Boolean) {
            return "bool:" + v;
        }
        if (v instanceof Number) {
            return atom(v, "number");
        }
        if (v instanceof String) {
            return "str:" + esc((String) v);
        }
        if (v instanceof ErrorValue error) {
            return "err:" + error.code + ":" + esc(error.message);
        }
        if (v instanceof Table || v instanceof Array
                || v instanceof FunctionValue
                || v instanceof ClassInstance) {
            return "ref:" + allocId(v);
        }
        return atom(v, kind);
    }

    /** The nested error text form. */
    public static String errtext(Throwable e) {
        if (e instanceof DealError deal) {
            return deal.code + ";" + esc(deal.msg) + ";" + (deal.origin == null ? "-"
                : deal.origin) + ";" + (deal.expected == null ? "-" : deal.expected) + ";"
                + (deal.actual == null ? "-" : deal.actual) + ";"
                + (deal.frames == null || deal.frames.isEmpty() ? "-" : deal.frames) + ";"
                + (deal.cause == null ? "-" : errtext(deal.cause));
        }
        return "E9999;" + esc(String.valueOf(e)) + ";-;-;-;-;-";
    }

    private static volatile boolean traceEnabled = true;

    /**
     * Sets the protocol-channel gate. Production artifacts call this
     * with {@code false} before executing any operation; the conformance
     * harness never calls it, so its per-process default stays on.
     *
     */
    public static void setTraceEnabled(boolean enabled) {
        traceEnabled = enabled;
    }

    /** Emits one protocol trace line to the dedicated channel (stderr). */
    public static void ev(String module, String op, String phase, String kind,
                          String digest, String parent, List<String> inputs,
                          String output, String errtext) {
        if (!traceEnabled) {
            return;
        }
        emitLine(module, op, phase, kind, digest, parent, inputs, output, errtext);
        if (phase.equals("START")) {
            OPEN_OPS.add(new OpenOp(module, op, kind, digest, parent));
            return;
        }
        for (int i = OPEN_OPS.size() - 1; i >= 0; i--) {
            if (OPEN_OPS.get(i).op().equals(op)) {
                OPEN_OPS.remove(i);
                break;
            }
        }
        if (phase.equals("FAILURE")) {
            closeUnprotectedStructures(errtext);
        }
    }

    /**
     * The open-operation stack of the error unwind (the trace protocol's
     * exactly-one-terminal-per-START rule): every START records its event
     * fields, every terminal closes the newest record of its own op, and a
     * FAILURE additionally closes the still-open structure ops with no
     * failure arm of their own (BRANCH, LOOP, FOR_EACH) directly under the
     * failing op's record, innermost first, so an error escaping them
     * publishes each one's FAILURE terminal with the propagating error —
     * the oracle's enclosing-op unwind. A protected op (one whose own arm
     * publishes its terminal) stops the closure; its arm publishes its own.
     */
    private record OpenOp(String module, String op, String kind, String digest,
                          String parent) {
    }

    private static final List<OpenOp> OPEN_OPS = new ArrayList<>();

    private static void closeUnprotectedStructures(String errtext) {
        while (!OPEN_OPS.isEmpty()) {
            OpenOp record = OPEN_OPS.get(OPEN_OPS.size() - 1);
            if (!record.kind().equals("BRANCH") && !record.kind().equals("LOOP")
                    && !record.kind().equals("FOR_EACH")) {
                return;
            }
            OPEN_OPS.remove(OPEN_OPS.size() - 1);
            emitLine(record.module(), record.op(), "FAILURE", record.kind(),
                record.digest(), record.parent(), null, null, errtext);
        }
    }

    /** One protocol trace line (the shared renderer of {@link #ev}). */
    private static void emitLine(String module, String op, String phase, String kind,
                                 String digest, String parent, List<String> inputs,
                                 String output, String errtext) {
        StringBuilder line = new StringBuilder();
        line.append("T|").append(seq++).append('|').append(module).append('|').append(op)
            .append('|').append(phase).append('|').append(kind).append('|').append(digest)
            .append('|').append(parent == null ? "-" : parent);
        if (inputs != null) {
            for (String input : inputs) {
                line.append('|').append(input);
            }
        }
        if (output != null) {
            line.append("|=>").append(output);
        }
        if (errtext != null) {
            line.append("|!").append(errtext);
        }
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        err.println(line);
    }

    /** Records one console effect (real stdout bytes + the protocol record). */
    public static void console(String text) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        out.println(text);
        if (!traceEnabled) {
            return;
        }
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        err.println("F|CONSOLE_WRITE|STDOUT|" + esc(text));
        err.flush();
    }

    /**
     * Records one console error effect. {@code CONSOLE_ERROR} appends the
     * exact scalar bytes plus one {@code \n} to {@code STDERR} — the
     * channel identity is part of the closed one-effect contract. The
     * STDERR channel shares the trace stream, so the trace protocol stays
     * decode-clean: trace mode publishes only the protocol record (the
     * effect's exact scalar text plus its channel), production mode (no
     * protocol) publishes the exact effect bytes.
     */
    public static void consoleError(String text) {
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        if (!traceEnabled) {
            err.println(text);
            err.flush();
            return;
        }
        err.println("F|CONSOLE_WRITE|STDERR|" + esc(text));
        err.flush();
    }

    // =========================================================================
    // Boundary projections (the closed failure registry's exact templates)
    // =========================================================================

    public static DealError fail(String code, String msg, String origin, String expected,
                                 String actual) {
        return new DealError(code, msg, origin, expected, actual, framesText(), null);
    }

    /**
     * The origins of the active DEAL-body calls, innermost last (the
     * {@code JSON_TO_ERROR} projection renders the invoking call
     * expression: the generated {@code C$toJson} walk reads the origin of
     * the call that invoked it, never the generated body's synthetic
     * anchor).
     */
    public static final java.util.ArrayDeque<String> callOrigins =
        new java.util.ArrayDeque<>();

    /** The innermost active call's origin, or the fallback. */
    public static String callOrigin(String fallback) {
        String origin = callOrigins.peek();
        return origin != null ? origin : fallback;
    }

    /**
     * One closed failure arm rendered on the JVM target (canonical
     * failure-projection authority P4 item 3): the arm's own template with
     * its named parameters and the arm's declared expected/actual fields —
     * the same authority the oracle and the emitted Lua prelude render —
     * plus the executing op's {@code SourceOrigin}, the render's one origin
     * operand. A production render of a marked (INNER_ONLY / SIBLING_OWNED)
     * arm, a field that does not match the arm's declaration, or a render
     * with no origin operand (the walk arms included) fails closed as a
     * producer defect — the render never derives and never substitutes a
     * span.
     */
    public static DealError arm(deal.semantic.ir.FailureArmId id,
                                java.util.Map<String, String> parameters, String origin,
                                String expected, String actual) {
        if (origin == null) {
            // The origin is the consumer's one operand (the op's own
            // SourceOrigin): an absent operand would publish a DEAL-visible
            // failure tuple without its span, so it is a fail-closed
            // producer defect and never a fabricated origin.
            throw new deal.semantic.ir.BoundaryExecutor.Defect("the failure arm '"
                + id + "' is rendered with no origin operand: the render must carry"
                + " the executing op's SourceOrigin (a producer defect, never a"
                + " derived or substituted span)");
        }
        deal.semantic.ir.BoundaryFailure failure =
            deal.semantic.ir.FailureContractRegistry.render(id, parameters, expected, actual,
                null);
        return new DealError(failure.code().name(), failure.message(), origin,
            failure.expected(), failure.actual(), framesText(), null);
    }

    /** The typed-boundary kind text of one canonical descriptor text (P2 item 1). */
    public static String kindTextOf(String desc) {
        if (desc.startsWith("nullable(")) {
            return kindTextOf(desc.substring(9, desc.length() - 1));
        }
        if (desc.startsWith("array(")) {
            return "array";
        }
        if (desc.startsWith("function(")) {
            return "function";
        }
        if (desc.startsWith("@")
                || deal.semantic.ir.ClassId.ERROR.text().equals(desc)) {
            return "class instance";
        }
        return desc;
    }

    /**
     * The descriptor-kind inner reason of the host arms (P2 item 3): the
     * typed-boundary kind arm's own template instantiated with the closed
     * kind text — the single source of the host inner-reason vocabulary's
     * descriptor entries. The descriptor text is validated against the
     * closed descriptor grammar (canonical text or the emitters' internal
     * dialect) and its closed kind vocabulary before the arm is rendered:
     * an unknown descriptor is a fail-closed producer defect, never a
     * composed fallback reason.
     */
    public static String kindReason(String desc) {
        return deal.semantic.ir.FailureContractRegistry.descriptorKindReason(desc);
    }


/** One inner-only string-carrier reason (the two INNER_ONLY arms' texts). */
    public static String stringCarrierReason(boolean surrogate) {
        return deal.semantic.ir.FailureContractRegistry.renderInner(
            surrogate ? deal.semantic.ir.FailureArmId.HOST_STRING_SURROGATE
                : deal.semantic.ir.FailureArmId.HOST_STRING_INVALID_UTF8,
            java.util.Map.of());
    }

    /**
     * The host string-carrier reason of one UTF-16 host carrier, or
     * {@code null} when the carrier is a scalar sequence: a carrier with a
     * lone surrogate code unit selects the surrogate code point reason at
     * any position. The choice is the carrier's scalar validity, never its
     * length (the pinned {@code host-surrogate-utf8-e8010} selection).
     */
    public static String hostStringReason(String carrier) {
        if (carrier == null) {
            throw new IllegalArgumentException(
                "the host string carrier must not be null");
        }
        return deal.semantic.ir.UnicodeScalars.carrierDefect(carrier) == null
            ? null : stringCarrierReason(true);
    }

    /**
     * The host string-carrier reason of one raw UTF-8 host carrier (the
     * backend-native byte carrier), or {@code null} when the sequence is
     * scalar-valid: the closed defect class of the first malformed
     * sequence selects the surrogate code point reason for a surrogate
     * code point encoding ({@code ED A0..BF ...}) and the invalid UTF-8
     * encoding reason for every other malformed sequence. A scalar-valid
     * sequence decodes to its UTF-16 carrier unchanged.
     */
    public static String hostStringReason(byte[] utf8) {
        if (utf8 == null) {
            throw new IllegalArgumentException(
                "the host string carrier must not be null");
        }
        deal.semantic.ir.UnicodeScalars.Defect defect =
            deal.semantic.ir.UnicodeScalars.utf8Defect(utf8);
        return defect == null ? null : stringCarrierReason(
            defect == deal.semantic.ir.UnicodeScalars.Defect.SURROGATE);
    }

    /** The array-element inner reason (the element arm's own text). */
    public static String elementReason(int oneBasedIndex) {
        return deal.semantic.ir.FailureContractRegistry
            .arm(deal.semantic.ir.FailureArmId.ARRAY_ELEMENT_KIND).template()
            .replace("{oneBasedIndex}", Integer.toString(oneBasedIndex));
    }

    /** The class-identity inner reason (the identity arm's own template). */
    public static String identityReason(String declared, String carried) {
        return deal.semantic.ir.FailureContractRegistry
            .arm(deal.semantic.ir.FailureArmId.CLASS_IDENTITY).template()
            .replace("{expected}", declared).replace("{actual}", carried);
    }

    /** The typed-boundary kind token of one canonical descriptor text (P2 item 1). */
    public static String kindTokenOf(String desc) {
        if (desc.startsWith("nullable(")) {
            return kindTokenOf(desc.substring(9, desc.length() - 1));
        }
        if (desc.startsWith("@")
                || deal.semantic.ir.ClassId.ERROR.text().equals(desc)) {
            return "class";
        }
        if (desc.startsWith("array(")) {
            return "array";
        }
        if (desc.startsWith("function(")) {
            return "function";
        }
        return desc;
    }

    /**
     * The typed-boundary kind arm's render (the completion cell's own kind
     * arm when the check is the completion cell's): the arm's template is
     * the message — the descriptor's closed kind text — and the closed
     * projections are the fields. The completion kind arm renders the kind
     * text in its message and the kind token as its expected field (the
     * unchanged runtime matcher's own form "expected array"/"array" and
     * "expected class instance"/"class").
     */
    private static DealError kindFailure(String desc, String actual, boolean completion) {
        if (completion) {
            return arm(deal.semantic.ir.FailureArmId.ASYNC_COMPLETION_KIND,
                java.util.Map.of("expected", kindTextOf(desc)), "-",
                kindTokenOf(desc), actual);
        }
        return arm(deal.semantic.ir.FailureArmId.TYPED_BOUNDARY_KIND,
            java.util.Map.of("kind", kindTextOf(desc)), "-", kindTokenOf(desc), actual);
    }


/**
     * The actual runtime kind of one value for failure projections: the
     * value's own kind — the absent marker → "nil", null → "null", else
     * the runtime type's canonical kind text (a wrong-kind value projects as
     * its own kind, never the declared static kind; the declared kind is
     * the fallback for carriers outside the closed value kinds).
     */
    public static String actualOf(String staticKind, Object v) {
        if (v == MISSING) {
            return "nil";
        }
        if (v == null) {
            return "null";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof Number) {
            return "number";
        }
        if (v instanceof String string) {
            // The typed-boundary projection classifies a string carrier by
            // its contents: a sequence that is not valid Unicode scalar
            // text projects invalid-unicode, exactly as the oracle and the
            // emitted Lua prelude's projection do.
            return JvmJson.validScalars(string) ? "string" : "invalid-unicode";
        }
        if (v instanceof Table) {
            return "table";
        }
        if (v instanceof Array) {
            return "array";
        }
        if (v instanceof FunctionValue) {
            return "function";
        }
        if (v instanceof ClassInstance instance) {
            // A class instance — the builtin Error carrier included —
            // projects its carried canonical class atom (the Error
            // carrier's classIdText is the builtin atom {@code Error}); the
            // {@code class:<ClassId>} IR/trace spelling never appears in
            // the DEAL-visible projection.
            return instance.classIdText();
        }
        return staticKind;
    }

    /**
     * The array-element arm's reference-kind projection (the decoded-array
     * mark, B3): the reported element's reference kind — an element
     * carrying the std/json decode mark renders {@code table} (the
     * reference value model's kind for a decoded array), every other
     * element keeps the landed refined token ({@code array} for a real
     * array carrier). The mark is read here and nowhere else in the
     * runtime.
     */
    public static String elementActualOf(String staticKind, Object v) {
        if (v instanceof Array array && array.decodedArray) {
            return "table";
        }
        return actualOf(staticKind, v);
    }

    /**
     * The completion cell's actual kind (the {@code ASYNC_COMPLETION}
     * boundary at an {@code AWAIT}): the pinned corpus projection of the
     * cell has one numeric kind, so every numeric carrier — the shared int
     * carrier and the number carrier — projects as "number"; every other
     * carrier keeps the shared classification.
     */
    public static String completionActualOf(String staticKind, Object v) {
        if (v instanceof Long || v instanceof Integer || v instanceof Double) {
            return "number";
        }
        return actualOf(staticKind, v);
    }

    /**
     * The descriptor-kind boundary check over the closed descriptor texts
     * ({@code null|boolean|int|number|string|table|array(INNER)|
     * nullable(INNER)|function(PARAMS;RETURN)}): the pinned int ladder
     * (kind → NaN → infinity → fractional → E8004), the array element
     * cause chain (E8003), and the function-signature projection (E8010).
     */
    public static Object bcheck(String desc, String staticKind, Object v) {
        return bcheck(desc, staticKind, v, false, null);
    }

    /**
     * The boundary check of a descriptor carrying a function position: the
     * trailing argument is the descriptor's canonical spec text, so the
     * function row projects the pinned canonical signature texts
     * ({@code function signature mismatch: expected (int)->int, got
     * (int)->string}) instead of the DEAL carrier's internal spelling.
     */
    public static Object bcheck(String desc, String staticKind, Object v,
                                String canon) {
        return bcheck(desc, staticKind, v, false, canon);
    }

    /**
     * The completion cell's check (the {@code ASYNC_COMPLETION} boundary at
     * an {@code AWAIT}): the acceptance logic is {@link #bcheck} 's, while a
     * kind mismatch projects the cell's pinned transcript — message
     * {@code expected {expected}} (the corpus completion-cell text) with the
     * cell's actual kind ({@link #completionActualOf}).
     */
    public static Object bcheckCompletion(String desc, String staticKind, Object v) {
        return bcheck(desc, staticKind, v, true, null);
    }

    /**
     * The completion cell's check of a descriptor carrying a function
     * position (an awaited function-typed completion): the canonical
     * signature projection is the same as {@link #bcheck(String, String,
     * Object, String)} 's.
     */
    public static Object bcheckCompletion(String desc, String staticKind, Object v,
                                          String canon) {
        return bcheck(desc, staticKind, v, true, canon);
    }

    /**
     * The host asynchronous completion crossing (the {@code AWAIT} site's
     * host-operation arm): the raw value a host operation completed its
     * future with is validated for the declared completion cell before it
     * enters the DEAL value model. The closed host
     * numeric carriers — the boxed {@code int} (Integer), the signed32
     * {@code long}, and the {@code double} — are admitted in the pinned
     * int order (NaN, infinity, non-fractional, then the signed32 range)
     * at a declared {@code int}, and every admitted carrier keeps its own
     * variant: only the boxed Integer, the host-side spelling of the
     * production int carrier, settles onto that carrier (Long), while the
     * signed32 {@code long} and the admitted integral {@code double}
     * cross unchanged. A declared {@code number} cell follows the same
     * rule. The preserved variant is the one the oracle's {@code core}
     * Number case keeps (the admitted view passes through and
     * {@code valueOfView} publishes the original value) and the one the
     * emitted Lua completion crossing materializes (an integral in-range
     * raw number is the int carrier, every other numeric value the number
     * carrier, and a value-model carrier keeps its own); narrowing an
     * admitted long or double here would change every later typed
     * projection of the stored completion (a number carrier re-spelled as
     * the int carrier renders {@code int} where the oracle and the LuaJIT
     * artifact render {@code number}). A nullable cell keeps its language
     * null.
     *
     * <p>Every non-numeric value and every other descriptor crosses to the
     * completion cell's own check ({@link #bcheckCompletion}) unchanged, so
     * the failing arm's tuple is the same one the check renders; the
     * numeric non-admissions render the completion row's refinement arm
     * (NaN, infinity, non-fractional) and the shared signed32-range arm
     * (E8004), the same arms the oracle's completion cell and the emitted
     * Lua completion check select. The emitted site re-originates every
     * failure at the await boundary, so this seam renders the absence
     * origin.</p>
     *
     * <p>The completed array carrier's element positions follow the same
     * settling rule recursively: a boxed Integer element — including a
     * nested array's element — becomes the production int carrier at a
     * declared int and at a declared number position alike, a Long and an
     * admitted Double keep their own variants, and every other element
     * (the language null included) crosses unchanged. A wrong-kind element
     * therefore projects its own kind through the canonical element
     * failure arm, and an int element is admitted exactly like the
     * oracle's IntValue and the emitted Lua completion cell's integral
     * number. The complete completion is validated on the original
     * carriers first — the canonical first-failing-element rule decides
     * before any settlement traversal visits the raw host graph, so a
     * subtree the descriptor check would never inspect can never replace
     * the first failure with a raw host error — and only a successful
     * validation reaches the settlement commit. The original carrier
     * identities are preserved: the settlement is an identity-keyed
     * iterative traversal (one repeated inner carrier is settled once and
     * a reference-cyclic host carrier terminates), so the commit has no
     * recursive dependence on the raw host graph's depth, and the
     * admitted crossing returns the very carrier the host completed with,
     * so two awaits of the same completed array observe one array (the
     * reference-identity contract) and a write through one alias stays
     * visible through every other. A rejected completion commits nothing
     * and leaves the host's own carrier untouched.</p>
     */
    public static Object hostCompletion(String desc, String staticKind, Object v) {
        return hostCompletion(desc, staticKind, v, null);
    }

    /**
     * The host asynchronous completion crossing of a descriptor carrying a
     * function position (the canonical trailer's same projection).
     */
    public static Object hostCompletion(String desc, String staticKind, Object v,
                                        String canon) {
        String inner = desc.startsWith("nullable(")
            ? desc.substring(9, desc.length() - 1) : desc;
        if (v instanceof Integer || v instanceof Long || v instanceof Double) {

            if ("number".equals(inner)) {
                return ((Number) v).doubleValue();
            }
        }
        // The complete completion is validated first — the crossing's own
        // boxed-Integer admission included, so an int element admits the
        // boxed carrier and a wrong-kind element renders the canonical
        // element arm — and only a successful validation reaches the
        // settlement of the original carriers. The canonical
        // first-failing-element rule therefore decides before any
        // settlement traversal visits the raw host graph: a subtree the
        // descriptor check would never inspect can never replace the first
        // failure with a raw host error, and a rejected completion commits
        // nothing.
        Object admitted = bcheck(desc, staticKind, v, true, canon, true);
        if (v instanceof Array array) {
            settleHostNumberElements(array);
        }
        return admitted;
    }

    /**
     * The identity-keyed settlement of one validated host completion's
     * boxed-Integer element positions: every Integer element of every array
     * in the reachable graph — the nested arrays included, each carrier
     * visited once — commits onto the production int carrier (Long) in
     * place, while every other carrier (the language null included) stays
     * unchanged. The traversal carries an explicit work list, so the commit
     * has no recursive dependence on the raw host graph's depth: a deep
     * acyclic subtree settles without exhausting the stack and a
     * reference-cyclic host carrier terminates through the identity visit
     * set. Only a completion that passed the crossing's validation reaches
     * this commit.
     */
    private static void settleHostNumberElements(Array root) {
        IdentityHashMap<Array, Boolean> visited = new IdentityHashMap<>();
        ArrayDeque<Array> pending = new ArrayDeque<>();
        visited.put(root, Boolean.TRUE);
        pending.push(root);
        while (!pending.isEmpty()) {
            Array array = pending.pop();
            for (int i = 0; i < array.elements.size(); i++) {
                Object element = array.elements.get(i);
                if (element instanceof Number number) {
                    array.elements.set(i, number.doubleValue());
                } else if (element instanceof Array inner
                        && visited.put(inner, Boolean.TRUE) == null) {
                    pending.push(inner);
                }
            }
        }
    }


/**
     * The class arms: a carried atom projects the identity arm, every other
     * value the closed kind arm.
     */
    private static DealError classFailure(String desc, Object v, String actual,
                                          boolean completion) {
        if (v instanceof ClassInstance instance) {
            String carried = instance.classIdText();
            return arm(deal.semantic.ir.FailureArmId.CLASS_IDENTITY,
                java.util.Map.of("expected", desc, "actual", carried), "-", desc, carried);
        }
        return kindFailure(desc, actual, completion);
    }

    private static Object bcheck(String desc, String staticKind, Object v,
            boolean completion) {
        return bcheck(desc, staticKind, v, completion, null);
    }

    /**
     * The inner canonical spec text of one container position (the array's
     * element or the nullable's inner descriptor), or {@code null} when the
     * boundary carries no canonical text at all.
     */
    private static String innerCanon(String canon, String opener, String closer) {
        if (canon == null || !canon.startsWith(opener) || !canon.endsWith(closer)
                || canon.length() < opener.length() + closer.length()) {
            return null;
        }
        return canon.substring(opener.length(), canon.length() - closer.length());
    }

    private static Object bcheck(String desc, String staticKind, Object v,
            boolean completion, String canon) {
        return bcheck(desc, staticKind, v, completion, canon, false);
    }

    /**
     * The acceptance logic of {@link #bcheck(String, String, Object, boolean,
     * String)} with the host-completion crossing's boxed-Integer admission:
     * the complete completion is validated before any settlement commits, so
     * an {@code int} position — the completed array's element positions
     * recursively included — admits the boxed carrier exactly like the
     * crossing's top-level rule admits it, and the settlement applies the
     * conversions only after the whole completion passed. A non-crossing
     * check never admits the boxed carrier.
     */
    private static Object bcheck(String desc, String staticKind, Object v,
            boolean completion, String canon, boolean hostCompletionCrossing) {
        String actual = completion ? completionActualOf(staticKind, v)
            : actualOf(staticKind, v);
        if ("null".equals(desc)) {
            if (v == null) {
                return v;
            }
            throw kindFailure("null", actual, completion);
        }
        if ("boolean".equals(desc)) {
            if (v instanceof Boolean) {
                return v;
            }
            throw kindFailure("boolean", actual, completion);
        }

        if ("number".equals(desc)) {
            if (v instanceof Number number) {
                return number.doubleValue();
            }
            throw kindFailure("number", actual, completion);
        }
        if ("string".equals(desc)) {
            if (v instanceof String string) {
                // The DEAL string boundary's scalar validation (the
                // oracle's TYPED_BOUNDARY_INVALID_UNICODE selection): a
                // valid Unicode scalar sequence admits unchanged, while
                // an invalid one renders the declared invalid-Unicode arm
                // (the completion cell keeps its own pinned kind arm).
                // The classification is the same one {@link #actualOf}
                // and the oracle's value view use, so no consumer admits
                // an invalid scalar the others reject.
                if (!JvmJson.validScalars(string)) {
                    if (completion) {
                        throw kindFailure("string", actual, true);
                    }
                    throw arm(deal.semantic.ir.FailureArmId.TYPED_BOUNDARY_INVALID_UNICODE,
                        java.util.Map.of(), "-", "string", "invalid-unicode");
                }
                return v;
            }
            throw kindFailure("string", actual, completion);
        }
        if ("table".equals(desc)) {
            if (v instanceof Table) {
                return v;
            }
            throw kindFailure("table", actual, completion);
        }
        if (deal.semantic.ir.ClassId.ERROR.text().equals(desc)) {
            // The builtin Error class (the err carrier): an Error value
            // passes unchanged. The canonical builtin atom is exactly
            // "Error" (the one class text without the leading '@'); the
            // superseded v1.2 spellings never reach this arm.
            if (v instanceof ErrorValue) {
                return v;
            }
            throw classFailure(desc, v, actual, completion);
        }
        if (desc.startsWith("@")) {
            // A nominal class descriptor (E5): the canonical
            // @modulePath/ClassName identity text — the instance must
            // carry the identical tag.
            if (v instanceof ClassInstance instance
                    && desc.equals(instance.classIdText())) {
                return v;
            }
            throw classFailure(desc, v, actual, completion);
        }
        if (desc.startsWith("array(")) {
            if (v instanceof Array array) {
                String inner = desc.substring(6, desc.length() - 1);
                String innerCanonical = innerCanon(canon, "[", "]");
                if (innerCanonical == null) {
                    innerCanonical = canonicalDesc(inner);
                }
                for (int i = 0; i < array.length; i++) {
                    // A present null element stays the language-null carrier
                    // (the explicit null the JSON reader stores unchanged),
                    // distinct from the absent/deleted slot's MISSING marker:
                    // the element descriptor decides the null (array(null)
                    // and nullable elements admit it) and only the absent
                    // slot projects the typed-boundary nil token.
                    Object elem = i < array.elements.size() ? array.elements.get(i)
                        : MISSING;
                    try {
                        bcheck(inner, elem == MISSING ? "missing" : inner, elem,
                            false, innerCanonical, hostCompletionCrossing);
                    } catch (DealError leaf) {
                        // The canonical element text (the semantic oracle's
                        // closed canonical spelling), never the runtime's
                        // internal array(...) dialect.
                        throw arm(deal.semantic.ir.FailureArmId.ARRAY_ELEMENT_KIND,
                            java.util.Map.of("oneBasedIndex", Integer.toString(i + 1)),
                            "-", innerCanonical != null ? innerCanonical : inner,
                            elementActualOf(elem == MISSING ? "missing" : inner, elem));
                    }
                }
                return v;
            }
            throw kindFailure("array", actual, completion);
        }
        if (desc.startsWith("nullable(")) {
            if (v == null || v == MISSING) {
                return null;
            }
            return bcheck(desc.substring(9, desc.length() - 1), staticKind, v,
                completion, innerCanon(canon, "?", ""), hostCompletionCrossing);
        }
        if (desc.startsWith("function(")) {
            if (v instanceof FunctionValue function) {
                String wanted = canon != null ? canon : desc;
                String carried = function.spec != null ? function.spec
                    : (function.signature == null ? "" : function.signature);
                boolean matches = canon != null && function.spec != null
                    ? function.spec.equals(canon)
                    : (function.signature == null ? "" : function.signature)
                        .equals(desc);
                if (matches) {
                    return v;
                }
                throw arm(deal.semantic.ir.FailureArmId.FUNCTION_SIGNATURE_MISMATCH,
                    java.util.Map.of("expected", wanted, "actual", carried), "-", wanted,
                    carried);
            }
            throw kindFailure("function", actual, completion);
        }
        return v;
    }

    // =========================================================================
    // The D15 adapter invocation protocol (FUNCTION_ADAPT / CALLBACK_INVOKE)
    // =========================================================================

    /**
     * The adapter's source-signature check (D15): the resolved source
     * value's carried canonical spec text must equal the adapter's
     * recorded source signature; a mismatch is E8010
     * {@code FUNCTION_SIGNATURE} at the invoking op's origin with the
     * active frames (the check runs before any source-frame push).
     */
    public static Object fnCheck(Object v, String expected, String origin) {
        String carried = "";
        if (v instanceof FunctionValue function && function.spec != null) {
            carried = function.spec;
        }
        if (expected.equals(carried)) {
            return v;
        }
        throw arm(deal.semantic.ir.FailureArmId.FUNCTION_SIGNATURE_MISMATCH,
            java.util.Map.of("expected", expected, "actual", carried), origin, expected,
            carried);
    }

    /**
     * The D15 adapter invocation: the target signature's parameter cells
     * (the ignored trailing parameters of an arity extension included —
     * docs/spec-v1.3.md Arity extension adapter), the source resolution
     * per the closed capture mode (VALUE retains, SHARED_CELL re-reads
     * the generation cell, REEVALUATE_THUNK re-executes the thunk), the
     * source-signature check, then the source invocation with the
     * leading M arguments only. A DEAL-body source owns its own stack
     * frame (the carrier wrapper pushes and pops it); the identical
     * completion error propagates unchanged.
     *
     * <p>The parameter admission runs before the source resolution so an
     * invalid declared target argument fails closed before any source
     * effect: a DEAL call site's parameter cells already admitted the
     * same values (the repeated check is idempotent), while a host that
     * invokes the published adapter value directly — a host-declared
     * function parameter or a callback's adapted return — has no cell of
     * its own, so this wrapper is its only enforcement point.</p>
     */
    public static Object invokeAdapter(AdapterValue adapter, String origin, Object[] args) {
        Object[] admitted = admitTargetParameters(adapter, args);
        Object source = adapterSource(adapter);
        fnCheck(source, adapter.sourceSpec, origin);
        Object[] leading = new Object[adapter.arity];
        System.arraycopy(admitted, 0, leading, 0, adapter.arity);
        // An adapter-valued source is an ordinary function value of its
        // declared signature (docs/spec-v1.3.md Function type
        // compatibility): runtime provenance as an adapter is not part of
        // the type. Descend the nested adapter's own source resolution and
        // source-signature check, keeping its own leading-M projection, so
        // the invocation reaches the statically fixed body or host class at
        // the end of the chain — never the adapter guard's placeholder
        // invoker.
        while (source instanceof AdapterValue nested) {
            source = adapterSource(nested);
            fnCheck(source, nested.sourceSpec, origin);
            Object[] inner = new Object[nested.arity];
            System.arraycopy(leading, 0, inner, 0, nested.arity);
            leading = inner;
        }
        FunctionValue function = (FunctionValue) source;
        // The invoking call's origin: a failure inside the adapted source
        // whose arm renders the call origin (the @jsonable toJson walk)
        // reads the innermost active call — the adapter call expression,
        // never an enclosing static call. The host-facing bridge passes
        // "-" (no call expression), so only a real call origin is
        // published. The finally pops on every path.
        boolean pushedOrigin = origin != null && !"-".equals(origin);
        if (pushedOrigin) {
            callOrigins.push(origin);
        }
        try {
            // A cataloged stdlib callable carries the packed row-invoker
            // calling convention (kind/opKey/digest/parent/origin plus the
            // declared arguments), never a plain function-value invoker:
            // the D15 invocation dispatches it through the same catalog
            // entry the direct STDLIB_CALL and dynamic HOST arms use, with
            // the adapter's leading-M projection. The call has no DEAL call
            // op of its own (a host-facing bridge or an exported callable),
            // so the invocation context is the generic CALL label and the
            // absent project coordinates.
            if (function instanceof StdlibFunctionValue callable) {
                return invokeStdlibCallable(callable, "CALL", "-", "-", "-", origin,
                    leading);
            }
            return function.fn.invoke(leading);
        } finally {
            if (pushedOrigin) {
                callOrigins.pop();
            }
        }
    }

    /**
     * The adapter's target-signature parameter admission: every declared
     * target parameter is checked at its own descriptor before the
     * leading-M projection, the ignored trailing parameters of an arity
     * extension included (docs/spec-v1.3.md Arity extension adapter — the
     * wrapper checks the parameter it ignores). The admitted values replace
     * the received ones exactly like a parameter cell's admitted value, so
     * an {@code int} position's normalization reaches the projection. A
     * cell-less adapter (never produced by the lowering) returns the
     * received array unchanged, and a call that receives fewer arguments
     * than the target declares admits the absent position as the internal
     * missing value, so it fails closed with the closed kind row rather
     * than a raw index error.
     */
    private static Object[] admitTargetParameters(AdapterValue adapter, Object[] args) {
        TargetCell[] cells = adapter.targetCells;
        if (cells.length == 0) {
            return args;
        }
        Object[] admitted = new Object[Math.max(cells.length, args.length)];
        System.arraycopy(args, 0, admitted, 0, args.length);
        for (int i = 0; i < cells.length; i++) {
            TargetCell cell = cells[i];
            Object received = i < args.length ? args[i] : MISSING;
            admitted[i] = bcheck(cell.descriptor(), cell.staticKind(), received,
                false, cell.canonical());
        }
        return admitted;
    }

    /**
     * The actual-kind atom of one host-supplied argument (the callback
     * dispatch surface): null/boolean/int/number/string by the runtime
     * carrier — identical to the semantic oracle's scripted-argument
     * atomization.
     */
    public static String hostAtom(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Boolean bool) {
            return "bool:" + bool;
        }
        if (v instanceof Number numeric) {
            return atom(numeric, "number");
        }
        if (v instanceof String string) {
            return "str:" + esc(string);
        }
        return "ref:" + allocId(v);
    }

    // =========================================================================
    // The D13 async machine (ASYNC_START / AWAIT)
    // =========================================================================

    /**
     * One async task record per canonical token identity: the token's
     * {@link CompletableFuture}, the body supplier for DEAL body tasks
     * (null for host operations), the host operation label for host
     * operations (null for body tasks), and the production operation
     * handle of a host operation the loaded declared async export
     * returned (null for a DEAL body task and for a seam-registered host
     * operation, whose completion is scripted by the scenario host).
     */
    public static final class AsyncTask {
        public final long tokenId;
        public final String owner;
        public final CompletableFuture<Object> future;
        public final java.util.function.Supplier<Object> body;
        public final String hostLabel;
        /** The production host operation handle; null for every non-production task. */
        public final Object hostOperation;

        AsyncTask(long tokenId, String owner, CompletableFuture<Object> future,
                  java.util.function.Supplier<Object> body, String hostLabel) {
            this(tokenId, owner, future, body, hostLabel, null);
        }

        AsyncTask(long tokenId, String owner, CompletableFuture<Object> future,
                  java.util.function.Supplier<Object> body, String hostLabel,
                  Object hostOperation) {
            this.tokenId = tokenId;
            this.owner = owner;
            this.future = future;
            this.body = body;
            this.hostLabel = hostLabel;
            this.hostOperation = hostOperation;
        }
    }

    /** The run-local pending task queue (deterministic FIFO). */
    static final ArrayDeque<AsyncTask> PENDING = new ArrayDeque<>();

    /** The canonical-token task registry (one task per token identity). */
    static final Map<Long, AsyncTask> TASKS = new HashMap<>();

    /**
     * The run-local single-thread serial executor (E4): every DEAL body
     * task's future completes on this thread — never the JDK common pool
     * (FIFO determinism is pinned).
     */
    private static final ExecutorService SERIAL_EXECUTOR =
        Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(() -> {
                serialThread = Thread.currentThread();
                runnable.run();
            }, "deal-shared-async-serial");
            thread.setDaemon(true);
            return thread;
        });

    /** The serial executor's thread (set when it first runs). */
    private static volatile Thread serialThread;

    /**
     * The deterministic host seam of the async machine (E6/E7): the
     * scenario host adapter scripts every async host terminal — an async
     * host start (the bound operation label, or {@code null} for a bad
     * handle) and an async host completion (the returned value or the
     * thrown host error). Without a seam an async host operation is a
     * producer defect, never a silent projection.
     */
    public interface HostAsync {

        /** One async host completion terminal. */
        record HostCompletion(Object value, String thrownCode, String thrownMessage) {

            public HostCompletion {
                if ((value == null) == (thrownCode == null)) {
                    throw new IllegalArgumentException("exactly one of value/thrownCode "
                        + "must be present in an async host completion");
                }
            }
        }

        /**
         * One async host start: returns the operation label the returned
         * handle binds to, or {@code null} for a bad handle (the
         * {@code ASYNC_OPERATION_HANDLE} terminal check fails).
         *
         */
        String startAsync(String module, String export, String label, Object[] args);

        /**
         * One async host completion of the operation label.
         *
         */
        HostCompletion completeAsync(String label);
    }

    /** The scenario host adapter's scripted seam (null outside host drives). */
    public static volatile HostAsync HOST_ASYNC;

    /**
     * Registers one DEAL body task: its future completes on the serial
     * executor at drain. The token key is the caller's own (a leaf
     * intrinsic, an adapter-over-async runner, or an already allocated
     * entry instance).
     */
    public static void startBodyTask(long tokenId, String owner,
                                     java.util.function.Supplier<Object> body) {
        AsyncTask task = new AsyncTask(tokenId, owner, new CompletableFuture<>(), body, null);
        TASKS.put(tokenId, task);
        PENDING.add(task);
    }

    /**
     * Registers one local DEAL body task activation: a fresh instance id
     * (never colliding with an allocated token identity) is bound to the
     * start token's LIFO slot and the task record lives under the instance
     * id, so a re-entrant activation of the same source call site never
     * replaces a live task and the AWAIT takes its own activation's
     * completion back after the drain (and releases the instance record).
     * The canonical IR token identity, owner, and return cell stay
     * unchanged.
     */
    public static void startBodyTaskInstance(long tokenId, String owner,
                                             java.util.function.Supplier<Object> body) {
        long instanceId = newEntryTaskInstance();
        bindEntryTask(tokenId, instanceId);
        AsyncTask task = new AsyncTask(instanceId, owner, new CompletableFuture<>(),
            body, null);
        TASKS.put(instanceId, task);
        PENDING.add(task);
    }

    // =========================================================================
    // The per-activation local body and external async entry instances
    // =========================================================================

    /**
     * The per-activation task instances of the local DEAL body tasks and
     * the external async linkage, keyed by the awaited token: every body
     * start and every entry activation runs its own task record, so a
     * re-entrant activation (a recursive local await through the same
     * source call site, a recursive export alias, or a direct await of an
     * exported declaration) never replaces the live task of its starter.
     * The slots are LIFO: every nested activation completes under the
     * drain before its starter's AWAIT takes its own instance back.
     */
    static final Map<Long, ArrayDeque<Long>> ENTRY_INSTANCES = new HashMap<>();

    /**
     * The per-activation task instance sequence (negative: an instance id
     * never collides with an allocated token identity).
     */
    private static long entryTaskSequence = 0;

    /** One fresh task instance for one activation (a body task or an entry). */
    public static long newEntryTaskInstance() {
        return --entryTaskSequence;
    }

    /** Binds one activation's instance to the awaiting token (LIFO slot). */
    public static long bindEntryTask(long tokenId, long instanceId) {
        ENTRY_INSTANCES.computeIfAbsent(tokenId, key -> new ArrayDeque<>())
            .push(instanceId);
        return instanceId;
    }

    /** The awaiting activation's own entry task instance (fail closed). */
    public static long takeEntryTask(long tokenId) {
        ArrayDeque<Long> slot = ENTRY_INSTANCES.get(tokenId);
        if (slot == null || slot.isEmpty()) {
            throw new IllegalStateException("AWAIT consumes an unbound external "
                + "token " + tokenId + " (producer defect)");
        }
        return slot.pop();
    }

    /**
     * The adapter-over-async resolution: an ADAPTER_INNER token's canonical
     * referent is the nested source token, which is activation-bound for
     * every source that owns a per-activation instance (a local DEAL body
     * or an external entry); a source that owns none (a host operation or
     * an intrinsic) keeps its static task key.
     */
    public static long takeEntryTaskOr(long tokenId) {
        ArrayDeque<Long> slot = ENTRY_INSTANCES.get(tokenId);
        if (slot == null || slot.isEmpty()) {
            return tokenId;
        }
        return slot.pop();
    }

    /** Registers one async host operation: its future completes through the host seam. */
    public static void startHostTask(long tokenId, String label) {
        AsyncTask task = new AsyncTask(tokenId, "HOST_OPERATION",
            new CompletableFuture<>(), null, label);
        TASKS.put(tokenId, task);
        PENDING.add(task);
    }

    @SuppressWarnings("unchecked")
    public static void startHostTask(long tokenId, String label, Object operation) {
        if (!(operation instanceof CompletableFuture<?> future)) {
            throw new IllegalStateException("the async host operation of token "
                + tokenId + " is not a backend async operation (got "
                + (operation == null ? "nothing" : operation.getClass().getName())
                + ") — the emitted wrapper's declared-async shape check is the"
                + " single authority (a producer defect)");
        }
        AsyncTask task = new AsyncTask(tokenId, "HOST_OPERATION",
            (CompletableFuture<Object>) future, null, label, future);
        TASKS.put(tokenId, task);
        PENDING.add(task);
    }

    /**
     * The deterministic FIFO drain (the oracle's {@code drainReadyTasks}):
     * every pending DEAL body task completes on the run-local single
     * thread serial executor in submission order. A body that runs an
     * inner {@code AWAIT} already executes on the serial thread — its
     * inner drain runs the nested bodies inline on that same serial
     * thread (still FIFO, never a self-join deadlock, never the common
     * pool). A production host operation completes only at its own
     * {@code AWAIT} through the registered operation; a seam-registered
     * host operation completes only at its own {@code AWAIT} through the
     * deterministic host seam.
     */
    public static void drainTasks() {
        while (!PENDING.isEmpty()) {
            AsyncTask task = PENDING.poll();
            if (task.future.isDone() || task.body == null) {
                continue;
            }
            if (Thread.currentThread() == serialThread) {
                // A nested drain inside a task body: the serial thread
                // runs the nested body inline (serial FIFO preserved).
                completeTask(task);
                continue;
            }
            // The body runs on the serial executor with the drainer's
            // active frames (the failure snapshots inside the body carry
            // the awaiting context's frames exactly like the oracle).
            List<String> frames = new ArrayList<>(FRAMES.get());
            Future<?> job = SERIAL_EXECUTOR.submit(() -> {
                List<String> saved = FRAMES.get();
                FRAMES.set(new ArrayList<>(frames));
                try {
                    completeTask(task);
                } finally {
                    FRAMES.set(saved);
                }
            });
            try {
                job.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("the serial executor drain was interrupted",
                    interrupted);
            } catch (ExecutionException failed) {
                throw new IllegalStateException("the serial executor drain failed",
                    failed.getCause());
            }
        }
    }

    /** Completes one body task's future with its value or its identical error. */
    private static void completeTask(AsyncTask task) {
        try {
            task.future.complete(task.body.get());
        } catch (Throwable thrown) {
            task.future.completeExceptionally(thrown);
        }
    }

    /**
     * AWAIT — the completion position (D13 step 6): the deterministic
     * FIFO drain first, then the token's completion. A production host
     * operation (a task registered with the loaded declared async export's
     * operation handle) joins that operation and never reads the seam; a
     * seam-registered host operation completes through the seam (the
     * ordered {@code ASYNC_COMPLETE_RETURN}/{@code ASYNC_COMPLETE_THROW}
     * effects); a failed operation rethrows the identical error — never a
     * re-check or a synthesized copy — and a completed value is returned
     * for the single {@code ASYNC_COMPLETION} boundary at the await site.
     *
     */
    public static Object awaitTask(long tokenId, String awaitOrigin) {
        drainTasks();
        return completeAwait(tokenId, awaitOrigin);
    }

    /**
     * The external async linkage's AWAIT: the deterministic FIFO drain
     * first, then the awaiting activation's own task instance. The
     * instance is resolved after the drain because an adapter-over-async
     * source starts its nested entry inside the adapter task (during the
     * drain); the nested activation pushes and takes its own instance, so
     * the starter's slot top is its own completion.
     */
    public static Object awaitEntryTask(long tokenId, boolean externalLink,
                                        String awaitOrigin) {
        return awaitEntryTaskOwned(tokenId, externalLink, awaitOrigin).value();
    }

    /**
     * The ownership-carrying AWAIT resolution of one local or external
     * async activation: the awaited value and the effective ownership of
     * the task the await consumed. The ownership is read from the resolved
     * task record, never from the static token, because a dynamic async
     * start over a value-carried host entry registers the resolved host
     * operation under the start token while the canonical token owner
     * stays the DEAL body task: the completion crossing must follow the
     * host operation the token actually names, so its raw carriers are
     * validated and settled at the same crossing the synchronous host
     * return cell uses. The resolved task keeps the AWAIT's own release
     * rule (a fresh per-activation instance is removed here; a canonical
     * token keeps its single static record).
     */
    public static AwaitedCompletion awaitEntryTaskOwned(long tokenId,
            boolean externalLink, String awaitOrigin) {
        drainTasks();
        long resolved = externalLink ? takeEntryTask(tokenId)
            : takeEntryTaskOr(tokenId);
        return completeAwaitOwned(resolved, awaitOrigin);
    }

    /**
     * One awaited completion: the value and the effective task ownership
     * (a host operation or a DEAL body task).
     */
    public record AwaitedCompletion(Object value, boolean hostOwned) {
    }

    /**
     * The post-drain completion of one awaited task or host operation. A
     * fresh per-activation instance (a negative instance id: a local body
     * task or an external entry activation) is released here on the
     * success and failure paths — one AWAIT is its single consumer — so a
     * long-running sequential await loop retains no completed activation
     * record. A canonical token keeps its single static record (one key
     * per token identity, pre-existing behavior).
     */
    private static Object completeAwait(long tokenId, String awaitOrigin) {
        return completeAwaitOwned(tokenId, awaitOrigin).value();
    }

    /**
     * The post-drain completion of one awaited task or host operation with
     * its effective ownership. A fresh per-activation instance (a negative
     * instance id: a local body task or an external entry activation) is
     * released here on the success and failure paths — one AWAIT is its
     * single consumer — so a long-running sequential await loop retains no
     * completed activation record. A canonical token keeps its single
     * static record (one key per token identity, pre-existing behavior).
     */
    private static AwaitedCompletion completeAwaitOwned(long tokenId,
                                                        String awaitOrigin) {
        AsyncTask task = TASKS.get(tokenId);
        if (task == null) {
            throw new IllegalStateException("AWAIT consumes an unbound token " + tokenId
                + " (producer defect)");
        }
        try {
            return new AwaitedCompletion(completeAwaitTask(tokenId, task, awaitOrigin),
                task.hostLabel != null);
        } finally {
            if (tokenId < 0) {
                TASKS.remove(tokenId);
            }
        }
    }

    /** One awaited task's completion (the released instance's single read). */
    private static Object completeAwaitTask(long tokenId, AsyncTask task,
                                            String awaitOrigin) {
        if (task.hostLabel != null && task.hostOperation != null) {

            try {
                Object value = task.future.join();
                effect("ASYNC_COMPLETE_RETURN", task.hostLabel + "="
                    + hostAtom(value));
                return value;
            } catch (CompletionException completion) {
                Throwable cause = completion.getCause();
                if (cause instanceof DealError dealError) {
                    effect("ASYNC_COMPLETE_THROW", task.hostLabel + "!"
                        + dealError.code);
                    throw dealError; // the identical error
                }
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("the async host operation " + tokenId
                    + " failed with " + cause, cause);
            }
        }
        if (task.hostLabel != null && !task.future.isDone()) {
            if (HOST_ASYNC == null) {
                throw new IllegalStateException("an async host completion has no "
                    + "deterministic host seam (the scenario host drives it)");
            }
            HostAsync.HostCompletion completion = HOST_ASYNC.completeAsync(task.hostLabel);
            if (completion.thrownCode() == null) {
                effect("ASYNC_COMPLETE_RETURN", task.hostLabel + "="
                    + hostAtom(completion.value()));
                task.future.complete(completion.value());
            } else {
                effect("ASYNC_COMPLETE_THROW", task.hostLabel + "!"
                    + completion.thrownCode());
                task.future.completeExceptionally(new DealError(completion.thrownCode(),
                    completion.thrownMessage(), awaitOrigin, null, null, framesText(),
                    null));
            }
        }
        try {
            return task.future.join();
        } catch (CompletionException completion) {
            Throwable cause = completion.getCause();
            if (cause instanceof DealError dealError) {
                throw dealError; // the identical error — never re-checked or copied
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("the async task " + tokenId + " failed with "
                + cause, cause);
        }
    }

    /** One ordered effect record of the closed effect protocol (F| lines). */
    public static void effect(String kind, String text) {
        if (!traceEnabled) {
            return;
        }
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        err.println("F|" + kind + "|-|" + esc(text));
    }

    /**
     * The adapter's source resolution per the closed capture mode (the
     * async adapter task's D15 protocol half): VALUE retains, SHARED_CELL
     * re-reads the generation cell, REEVALUATE_THUNK re-executes the
     * thunk.
     */
    public static Object adapterSource(AdapterValue adapter) {
        if (adapter.mode == 0) {
            return adapter.value;
        }
        if (adapter.mode == 1) {
            return adapter.cell[0];
        }
        return adapter.thunk.invoke(new Object[0]);
    }

    // =========================================================================
    // The MODULE_INIT state machine (E8)
    // =========================================================================

    /**
     * The closed module-init lifecycle of one module: unrecorded is
     * {@code UNINITIALIZED}, {@code INITIALIZING} during the payload
     * walk, {@code INITIALIZED} after a completed walk, and
     * {@code FAILED} after a failing walk (no export publication).
     */
    public enum ModuleInitState { INITIALIZING, INITIALIZED, FAILED }

    /** The per-module lifecycle records of one run (keyed by module path). */
    static final Map<String, ModuleInitState> MODULE_STATES = new LinkedHashMap<>();

    /**
     * The MODULE_INIT envelope's state gate: {@code true} when the module
     * is still {@code UNINITIALIZED} (the walk runs), {@code false} when
     * it is already {@code INITIALIZED} (the once-after-dependencies
     * skip). A re-entrant ({@code INITIALIZING}) or post-failure
     * ({@code FAILED}) execution is a producer defect, never a silent
     * re-run.
     *
     */
    public static boolean moduleInitNeeded(String module) {
        ModuleInitState state = MODULE_STATES.get(module);
        if (state == null) {
            return true;
        }
        if (state == ModuleInitState.INITIALIZED) {
            return false;
        }
        throw new IllegalStateException("a second MODULE_INIT of module " + module
            + " after a re-entrant or failed init (the frontend rejects import "
            + "cycles with E2005) — a producer defect, never a silent re-run");
    }

    /** The {@code UNINITIALIZED -> INITIALIZING} transition. */
    public static void moduleInitBegin(String module) {
        MODULE_STATES.put(module, ModuleInitState.INITIALIZING);
    }

    /** The {@code INITIALIZING -> INITIALIZED} transition (completed walk). */
    public static void moduleInitComplete(String module) {
        MODULE_STATES.put(module, ModuleInitState.INITIALIZED);
    }

    /** The {@code INITIALIZING -> FAILED(error)} transition (no export publication). */
    public static void moduleInitFail(String module) {
        MODULE_STATES.put(module, ModuleInitState.FAILED);
    }

    // =========================================================================
    // The closed B-D2 comparison table (native operators)
    // =========================================================================

    public static boolean cmp(String selector, String kindL, Object l, String kindR,
                              Object r, String side) {
        boolean leftNullish = l == null || l == MISSING;
        boolean rightNullish = r == null || r == MISSING;
        if (leftNullish || rightNullish) {
            if ("NULLABLE_NULL_EQ".equals(selector)) {
                Object named = "LEFT".equals(side) ? l : r;
                return named == null || named == MISSING;
            }
            if ("NULLABLE_NULL_NE".equals(selector)) {
                Object named = "LEFT".equals(side) ? l : r;
                return !(named == null || named == MISSING);
            }
            boolean both = leftNullish && rightNullish;
            if (selector.endsWith("_EQ")) {
                return both;
            }
            if (selector.endsWith("_NE")) {
                return !both;
            }
            return false;
        }
        switch (selector) {


case "NUMBER_EQ" -> {
                return ((Number) l).doubleValue() == ((Number) r).doubleValue();
            }
            case "NUMBER_NE" -> {
                return ((Number) l).doubleValue() != ((Number) r).doubleValue();
            }
            case "NUMBER_LT" -> {
                return ((Number) l).doubleValue() < ((Number) r).doubleValue();
            }
            case "NUMBER_LE" -> {
                return ((Number) l).doubleValue() <= ((Number) r).doubleValue();
            }
            case "NUMBER_GT" -> {
                return ((Number) l).doubleValue() > ((Number) r).doubleValue();
            }
            case "NUMBER_GE" -> {
                return ((Number) l).doubleValue() >= ((Number) r).doubleValue();
            }
            case "STRING_EQ" -> {
                return ((String) l).equals((String) r);
            }
            case "STRING_NE" -> {
                return !((String) l).equals((String) r);
            }
            case "STRING_LT" -> {
                return codePointCompare((String) l, (String) r) < 0;
            }
            case "STRING_LE" -> {
                return codePointCompare((String) l, (String) r) <= 0;
            }
            case "STRING_GT" -> {
                return codePointCompare((String) l, (String) r) > 0;
            }
            case "STRING_GE" -> {
                return codePointCompare((String) l, (String) r) >= 0;
            }
            case "BOOLEAN_EQ" -> {
                return ((Boolean) l).equals((Boolean) r);
            }
            case "BOOLEAN_NE" -> {
                return !((Boolean) l).equals((Boolean) r);
            }
            case "NULL_EQ" -> {
                return true;
            }
            case "NULL_NE" -> {
                return false;
            }
            case "NULLABLE_EQ" -> {
                return nullableEquals(kindL, l, kindR, r);
            }
            case "NULLABLE_NE" -> {
                return !nullableEquals(kindL, l, kindR, r);
            }
            case "REFERENCE_EQ" -> {
                return l == r;
            }
            case "REFERENCE_NE" -> {
                return l != r;
            }
            default -> {
                return false;
            }
        }
    }

    /** Unicode-scalar (code point) order — never String.compareTo. */
    static int codePointCompare(String left, String right) {
        int[] leftPoints = left.codePoints().toArray();
        int[] rightPoints = right.codePoints().toArray();
        int n = Math.min(leftPoints.length, rightPoints.length);
        for (int i = 0; i < n; i++) {
            if (leftPoints[i] != rightPoints[i]) {
                return Integer.compare(leftPoints[i], rightPoints[i]);
            }
        }
        return Integer.compare(leftPoints.length, rightPoints.length);
    }

    static boolean nullableEquals(String kindL, Object l, String kindR, Object r) {
        // Inner equality by the value rules (both non-null here).
        if (l instanceof Long left && r instanceof Long right) {
            return left.longValue() == right.longValue();
        }
        if (l instanceof Number left && r instanceof Number right) {
            return left.doubleValue() == right.doubleValue();
        }
        if (l instanceof String left && r instanceof String right) {
            return left.equals(right);
        }
        if (l instanceof Boolean left && r instanceof Boolean right) {
            return left.equals(right);
        }
        return l == r; // reference identity for heap values
    }

    // =========================================================================
    // The closed int32/number arithmetic rows
    // =========================================================================

    public static Object unary(String selector, Object v, String opKey, String digest,
                               String parent, String origin) {
        if ("BOOL_NOT".equals(selector)) {
            return !((Boolean) v);
        }

        return -((Number) v).doubleValue();
    }

    public static Object arith(String selector, Object l, Object r, String opKey,
                               String digest, String parent, String origin) {
        switch (selector) {


case "NUMBER_ADD" -> {
                return ((Number) l).doubleValue() + ((Number) r).doubleValue();
            }
            case "NUMBER_SUB" -> {
                return ((Number) l).doubleValue() - ((Number) r).doubleValue();
            }
            case "NUMBER_MUL" -> {
                return ((Number) l).doubleValue() * ((Number) r).doubleValue();
            }
            case "NUMBER_DIV_IEEE" -> {
                return ((Number) l).doubleValue() / ((Number) r).doubleValue();
            }
            default -> throw new IllegalStateException("unknown arithmetic selector: " + selector);
        }
    }


/**
     * One closed arm's operation-site failure (canonical failure-projection
     * authority P4 item 3): the arm's own template with its named parameters
     * and its declared expected/actual fields, published as the invoking
     * op's FAILURE event. A site that holds the arm's text as a literal is a
     * producer defect; the parity battery reports the composed field by
     * name.
     */
    static void raiseArm(deal.semantic.ir.FailureArmId id,
                         java.util.Map<String, String> parameters, String expected,
                         String actual, String opKey, String digest, String parent,
                         String origin, String kind) {
        DealError e = arm(id, parameters, origin, expected, actual);
        ev(currentModule(), opKey, "FAILURE", kind, digest, parent, List.of(), null,
            errtext(e));
        throw e;
    }

    // =========================================================================
    // The closed stdlib algorithms (STDLIB_CALL realization)
    // =========================================================================

    /**
     * An internal stdlib algorithm failure: the exact registry-row
     * projection facts, converted by {@link #stdlib} into the op FAILURE
     * event plus the {@link DealError} at the call origin (the same
     * conversion the arithmetic helpers perform).
     */
    private static final class StdlibFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** The closed arm of an arm-rendered failure, or null for a producer defect. */
        final deal.semantic.ir.FailureArmId armId;
        final java.util.Map<String, String> parameters;
        final String code;
        final String msg;
        final String expected;
        final String actual;

        /** A table arm's failure: rendered through the arm at the op boundary. */
        StdlibFailure(deal.semantic.ir.FailureArmId armId,
                      java.util.Map<String, String> parameters, String expected,
                      String actual) {
            super(armId.name());
            this.armId = armId;
            this.parameters = parameters;
            this.expected = expected;
            this.actual = actual;
            this.code = null;
            this.msg = null;
        }

        /** A fail-closed producer defect outside the closed arm table. */
        StdlibFailure(String code, String msg, String expected, String actual) {
            super(msg);
            this.armId = null;
            this.parameters = null;
            this.code = code;
            this.msg = msg;
            this.expected = expected;
            this.actual = actual;
        }
    }

    /**
     * The single in-target realization of the closed 21-operation stdlib
     * table: the parameter boundaries already ran (the emitter emits the
     * {@code STDLIB_PARAMETER} children), so the algorithm receives the
     * boundary-admitted carriers — a string parameter is a valid scalar
     * {@link String}, an int parameter is a {@link Long} or an in-range
     * integral {@link Double}, a number parameter is a {@link Double} or
     * a {@link Long}, and a table parameter is a {@link Table}. A
     * failure raises the op FAILURE event and the {@link DealError}
     * through the closed arm render ({@link #raiseArm} or the stdlib
     * failure's own arm) with the exact closed projections —
     * {@code INT32_RESULT} E8004 {@code int out of safe range},
     * {@code SQRT_NEGATIVE} E8001 {@code sqrt of negative number} (actual
     * = the canonical hex float), {@code JSON_PARSE_SYNTAX} E8001
     * {@code JSON parse error at position {oneBasedByteOffset}:
     * {reason}}, and {@code JSON_TO_ERROR} E8001
     * {@code unsupported type for JSON encoding: {actual}} rendered through
     * the {@code JSON_STRINGIFY_UNSUPPORTED} arm (its own pinned expected
     * text and the failing value's carrier-kind projection) —
     * at the {@code STDLIB_CALL} call origin with the active frames.
     * {@code TIME_NOW_MILLIS} reads the target clock
     * ({@link System#currentTimeMillis()}) and its single terminal is the
     * declared number {@code STDLIB_RETURN} boundary under the
     * descriptor-kind rule — a contemporary epoch-millisecond reading
     * (above the signed-32 range) crosses unchanged, never an algorithm
     * failure and never an int terminal (v1.3). The two console ids are
     * the row invoker's
     * ({@link #stdlibInvoke}), never this surface's. The {@code kind}
     * parameter is the invoking op's own event kind label (the direct
     * {@code STDLIB_CALL} arm's own kind, or the dynamic dispatch's CALL
     * kind), so an algorithm failure publishes the invoking op's FAILURE
     * event under its own kind.
     */
    public static Object stdlib(String kind, String fn, String opKey, String digest,
                                String parent, String origin, Object[] args) {
        try {
            switch (fn) {
                case "STRING_LENGTH" -> {
                    String text = (String) args[0];
                    return Double.valueOf(text.codePointCount(0, text.length()));
                }
                case "STRING_SUBSTRING" -> {
                    int[] codePoints = ((String) args[0]).codePoints().toArray();
                    double start = ((Number) args[1]).doubleValue();
                    double end = ((Number) args[2]).doubleValue();
                    if (!Double.isFinite(start) || start != Math.rint(start)
                            || !Double.isFinite(end) || end != Math.rint(end)) {
                        throw new StdlibFailure(deal.semantic.ir.FailureArmId.SUBSTRING_BOUNDS,
                            java.util.Map.of(), null, null);
                    }
                    int lo = (int) Math.min(Math.max(0, start), codePoints.length);
                    int hi = (int) Math.min(Math.max(0, end), codePoints.length);
                    if (lo >= hi) {
                        return "";
                    }
                    return new String(codePoints, lo, hi - lo);
                }
                case "STRING_CONTAINS" -> {
                    return indexOfSubsequence(codePointsOf((String) args[0]),
                        codePointsOf((String) args[1]), 0) >= 0;
                }
                case "STRING_STARTS_WITH" -> {
                    int[] input = codePointsOf((String) args[0]);
                    int[] part = codePointsOf((String) args[1]);
                    return part.length <= input.length && matchAt(input, part, 0);
                }
                case "STRING_ENDS_WITH" -> {
                    int[] input = codePointsOf((String) args[0]);
                    int[] part = codePointsOf((String) args[1]);
                    return part.length <= input.length
                        && matchAt(input, part, input.length - part.length);
                }
                case "STRING_REPLACE" -> {
                    int[] input = codePointsOf((String) args[0]);
                    int[] from = codePointsOf((String) args[1]);
                    int[] to = codePointsOf((String) args[2]);
                    if (from.length == 0) {
                        return args[0];
                    }
                    StringBuilder result = new StringBuilder();
                    int cursor = 0;
                    while (cursor <= input.length - from.length) {
                        if (matchAt(input, from, cursor)) {
                            appendCodePoints(result, to);
                            cursor += from.length;
                        } else {
                            result.appendCodePoint(input[cursor]);
                            cursor++;
                        }
                    }
                    for (int i = cursor; i < input.length; i++) {
                        result.appendCodePoint(input[i]);
                    }
                    return result.toString();
                }
                case "STRING_SPLIT" -> {
                    int[] input = codePointsOf((String) args[0]);
                    int[] separator = codePointsOf((String) args[1]);
                    if (input.length == 0) {
                        return new Array(0);
                    }
                    if (separator.length == 0) {
                        Array singles = new Array(input.length);
                        for (int codePoint : input) {
                            singles.elements.add(new String(
                                new int[] {codePoint}, 0, 1));
                        }
                        return singles;
                    }
                    Array parts = new Array(0);
                    int cursor = 0;
                    int occurrence;
                    while ((occurrence = indexOfSubsequence(input, separator, cursor))
                            >= 0) {
                        parts.elements.add(new String(input, cursor, occurrence - cursor));
                        parts.length++;
                        cursor = occurrence + separator.length;
                    }
                    parts.elements.add(new String(input, cursor, input.length - cursor));
                    parts.length++;
                    return parts;
                }
                case "STRING_TRIM" -> {
                    int[] codePoints = codePointsOf((String) args[0]);
                    int first = 0;
                    while (first < codePoints.length && isTrimScalar(codePoints[first])) {
                        first++;
                    }
                    int last = codePoints.length;
                    while (last > first && isTrimScalar(codePoints[last - 1])) {
                        last--;
                    }
                    return new String(codePoints, first, last - first);
                }
                case "TABLE_KEYS" -> {
                    Table table = (Table) args[0];
                    Array keys = new Array(table.entries.size());
                    for (String key : table.entries.keySet()) {
                        keys.elements.add(key);
                    }
                    return keys;
                }
                case "JSON_PARSE" -> {
                    return jsonParse((String) args[0]);
                }
                case "JSON_STRINGIFY" -> {
                    Table root = (Table) args[0];
                    StringBuilder out = new StringBuilder();
                    java.util.Set<Object> path =
                        java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                    stringifyValue(out, root, path);
                    return out.toString();
                }
                case "MATH_FLOOR" -> {
                    return Math.floor(numberOf(args[0]));
                }
                case "MATH_CEIL" -> {
                    return Math.ceil(numberOf(args[0]));
                }
                case "MATH_SQRT" -> {
                    double value = numberOf(args[0]);
                    if (value < 0) {
                        throw new StdlibFailure(deal.semantic.ir.FailureArmId.SQRT_NEGATIVE,
                            java.util.Map.of(), null, Double.toHexString(value));
                    }
                    return Math.sqrt(value);
                }

                case "MATH_ABS_NUMBER" -> {
                    return Math.abs(numberOf(args[0]));
                }


                case "TIME_NOW_MILLIS" -> {
                    // The K7 target-clock read: the declared number
                    // STDLIB_RETURN boundary (descriptor-kind rule) admits
                    // the contemporary epoch-millisecond reading above the
                    // signed-32 range unchanged — no int terminal exists
                    // (v1.3). The same observable the LuaJIT artifact and
                    // the oracle produce.
                    return Double.valueOf(System.currentTimeMillis());
                }
                default -> throw new StdlibFailure("E8001",
                    "unknown stdlib call " + fn, null, null);
            }
        } catch (StdlibFailure failure) {
            DealError e = failure.armId != null
                ? arm(failure.armId, failure.parameters, origin, failure.expected,
                    failure.actual)
                : fail(failure.code, failure.msg, origin, failure.expected,
                    failure.actual);
            ev(currentModule(), opKey, "FAILURE", kind, digest, parent, List.of(), null,
                errtext(e));
            throw e;
        }
    }

    public static Object stdlibInvoke(String kind, String fn, String opKey, String digest,
                                      String parent, String origin, Object[] args) {
        switch (fn) {
            case "CONSOLE_LOG" -> {
                console(consoleText(args));
                return null;
            }
            case "CONSOLE_ERROR" -> {
                consoleError(consoleText(args));
                return null;
            }
            default -> {
                return stdlib(kind, fn, opKey, digest, parent, origin, args);
            }
        }
    }

    /**
     * The text projection of one console invocation: the boundary-admitted
     * argument texts in declared order joined by one space; zero arguments
     * project the empty string.
     */
    private static String consoleText(Object[] args) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                text.append(' ');
            }
            text.append((String) args[i]);
        }
        return text.toString();
    }

    /**
     * The int consumption unwrap of one runtime value: the production int
     * carrier (Long) and the value model's preserved integral number
     * carrier (Double — the variant an admitted typed-boundary or host
     * completion crossing keeps) unwrap to the same long, exactly like
     * the emitted Lua arithmetic's carrier unwrap, so the closed int
     * arithmetic/comparison rows consume a preserved number carrier
     * instead of casting it blind.
     */
    private static long longOf(Object value) {
        if (value instanceof Long longValue) {
            return longValue.longValue();
        }
        return ((Double) value).longValue();
    }

    /**
     * The normalized index/length view of one runtime value: a Long or an
     * integral Double (the closed value model's variant tolerance — an
     * index value admitted at an int-typed position keeps its boxed
     * Double, so the normalized slot never casts it blind).
     */
    public static double indexOf(Object value) {
        return ((Number) value).doubleValue();
    }

    /** A number parameter carrier: a Double or a Long (int → double is exact). */
    private static double numberOf(Object value) {
        return ((Number) value).doubleValue();
    }

    /** The closed trim set: U+0009-U+000D and U+0020, exactly. */
    private static boolean isTrimScalar(int codePoint) {
        return (codePoint >= 0x09 && codePoint <= 0x0D) || codePoint == 0x20;
    }

    /** The code points of a scalar-valid string in scalar order. */
    private static int[] codePointsOf(String text) {
        return text.codePoints().toArray();
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

    // =========================================================================
    // The RFC-8259 JSON reader (JSON_PARSE realization)
    // =========================================================================

    /** The stable parse defect classifications ({reason} texts). */
    private static final String REASON_UNEXPECTED_CHARACTER = "unexpected character";
    private static final String REASON_UNTERMINATED_STRING = "unterminated string";
    private static final String REASON_UNTERMINATED_OBJECT = "unterminated object";
    private static final String REASON_UNTERMINATED_ARRAY = "unterminated array";
    private static final String REASON_INVALID_ESCAPE = "invalid escape";
    private static final String REASON_UNPAIRED_SURROGATE_ESCAPE = "unpaired surrogate escape";
    private static final String REASON_INVALID_NUMBER = "invalid number";
    private static final String REASON_LEADING_ZERO = "leading zero";
    private static final String REASON_MISSING_KEY = "missing key";
    private static final String REASON_MISSING_COLON = "missing colon";
    private static final String REASON_MISSING_COMMA = "missing comma";
    private static final String REASON_TRAILING_CONTENT = "trailing content";
    private static final String REASON_UNEXPECTED_END = "unexpected end of input";
    private static final String REASON_DUPLICATE_KEY = "duplicate key";

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
     * The RFC-8259 recursive-descent reader over the input's code points,
     * tracking the 1-based UTF-8 byte offset of the current scan position:
     * object order follows text, a member key whose decoded scalar text
     * equals an earlier member key of the same object is a syntax defect
     * ({@code duplicate key}) at the second key's opening quote, and the
     * exact decimal classification ({@link JsonNumberClassifier}) makes a
     * numeric lexeme a {@link Long} exactly when its mathematical value is
     * integral and inside signed32 ({@code -0}/{@code -0.0} normalized to
     * {@code 0}); every other numeric lexeme becomes a {@link Double}.
     */
    private static final class JsonReader extends JsonScan.CodePointCursor {

        /** The sentinel a container open returns to the frame loop. */
        private static final Object OPENED = new Object();

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
            final Table table;
            final Array elements;
            final java.util.Set<String> decodedKeys;
            String pendingKey;
            int state;

            private Frame(boolean array, Table table, Array elements,
                          java.util.Set<String> decodedKeys, int state) {
                this.array = array;
                this.table = table;
                this.elements = elements;
                this.decodedKeys = decodedKeys;
                this.state = state;
            }

            static Frame object() {
                return new Frame(false, new Table(), null, new java.util.HashSet<>(),
                    OBJECT_FIRST);
            }

            static Frame array() {
                Array array = new Array(0);
                // The decoded-array mark (B3): the std/json parse
                // realization is the only producer of the mark; a nested
                // decoded array carries it too.
                array.decodedArray = true;
                return new Frame(true, null, array, null, ARRAY_FIRST);
            }
        }

        /**
         * Parses one value at the cursor: a scalar returns directly, a
         * container pushes its frame on {@code stack} and returns
         * {@code null}. The frame loop of {@link #parseValue()} then
         * drives the open container.
         */
        private Object parseValueAtCursor(java.util.Deque<Frame> stack) {
            if (atEnd()) {
                throw failure(REASON_UNEXPECTED_END);
            }
            int c = peek();
            switch (c) {
                case '{' -> {
                    advance(); // '{'
                    stack.push(Frame.object());
                    return OPENED;
                }
                case '[' -> {
                    advance(); // '['
                    stack.push(Frame.array());
                    return OPENED;
                }
                case '"' -> {
                    return parseString();
                }
                case 't' -> {
                    return parseLiteral("true", Boolean.TRUE);
                }
                case 'f' -> {
                    return parseLiteral("false", Boolean.FALSE);
                }
                case 'n' -> {
                    return parseLiteral("null", null);
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
        Object parseValue() {
            java.util.Deque<Frame> stack = new java.util.ArrayDeque<>();
            Object completed = null;
            boolean haveValue = false;
            while (true) {
                if (haveValue) {
                    if (stack.isEmpty()) {
                        return completed;
                    }
                    Frame parent = stack.peek();
                    if (parent.array) {
                        parent.elements.elements.add(completed);
                        parent.elements.length++;
                        parent.state = Frame.ARRAY_AFTER;
                    } else {
                        parent.table.write(parent.pendingKey, completed);
                        parent.state = Frame.OBJECT_AFTER;
                    }
                    completed = null;
                    haveValue = false;
                    continue;
                }
                if (stack.isEmpty()) {
                    Object parsed = parseValueAtCursor(stack);
                    if (parsed == OPENED) {
                        continue;
                    }
                    completed = parsed;
                    haveValue = true;
                    continue;
                }
                Frame frame = stack.peek();
                switch (frame.state) {
                    case Frame.OBJECT_FIRST -> {
                        skipWs();
                        if (!atEnd() && peek() == '}') {
                            advance();
                            completed = frame.table;
                            stack.pop();
                            haveValue = true;
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
                        Object parsed = parseValueAtCursor(stack);
                        if (parsed != OPENED) {
                            completed = parsed;
                            haveValue = true;
                        }
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
                            completed = frame.table;
                            stack.pop();
                            haveValue = true;
                        } else {
                            throw failure(REASON_MISSING_COMMA);
                        }
                    }
                    case Frame.ARRAY_FIRST -> {
                        skipWs();
                        if (!atEnd() && peek() == ']') {
                            advance();
                            completed = frame.elements;
                            stack.pop();
                            haveValue = true;
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
                            completed = frame.elements;
                            stack.pop();
                            haveValue = true;
                        } else {
                            throw failure(REASON_MISSING_COMMA);
                        }
                    }
                    default -> throw new IllegalStateException(
                        "unknown JSON reader frame state " + frame.state);
                }
            }
        }

        Object parseNumber() {
            JsonScan.CodePointCursor.NumberScan scan = parseNumberText();
            return Double.valueOf(JsonNumberClassifier.toDouble(scan.text()));
        }
    }

    /** {@code JSON_PARSE}: the parse entry with the pinned trailing-content check. */
    private static Object jsonParse(String text) {
        JsonReader reader = new JsonReader(text.codePoints().toArray());
        try {
            reader.skipWs();
            Object value = reader.parseValue();
            reader.skipWs();
            if (!reader.atEnd()) {
                throw reader.failure(REASON_TRAILING_CONTENT);
            }
            return value;
        } catch (JsonParseFailure parseFailure) {
            throw new StdlibFailure(deal.semantic.ir.FailureArmId.JSON_PARSE_ERROR,
                java.util.Map.of("oneBasedByteOffset",
                    Long.toString(parseFailure.oneBasedByteOffset),
                    "reason", parseFailure.reason), null, null);
        }
    }

    // =========================================================================
    // The RFC-8259 serializer (JSON_STRINGIFY realization)
    // =========================================================================

    /**
     * The pinned {@code STDLIB_CALL(JSON_STRINGIFY)} rejection: the
     * {@code JSON_STRINGIFY_UNSUPPORTED} arm's own render — its pinned
     * expected text and the carrier-kind projection of the failing value
     * (P2 item 2 with the arm's two pinned members), the same helper the
     * oracle's executor family renders. The walk's internal position never
     * surfaces — the first declaration-order failure is the only
     * observable fact.
     */
    private static StdlibFailure jsonEncodingFailure(Object value) {
        deal.semantic.ir.BoundaryFailure failure =
            deal.semantic.ir.FailureProjections.stringifyUnsupported(
                jsonCarrierKind(value));
        return new StdlibFailure(failure.code().name(), failure.message(),
            failure.expected(), failure.actual());
    }

    /**
     * The carrier-kind classification of one stringify-walk value (the
     * std/json member of the closed carrier-kind projection): the absent
     * marker is {@code nil}, the DEAL function-shaped carriers are
     * {@code function}, and every
     * other table-carried value — tables, arrays, class instances (the
     * builtin Error value included) — is {@code table}. The token table
     * itself is the shared {@code FailureProjections} helper.
     */
    private static deal.semantic.ir.FailureProjections.CarrierKind jsonCarrierKind(
            Object value) {
        if (value == MISSING) {
            return deal.semantic.ir.FailureProjections.CarrierKind.ABSENT;
        }
        if (value == null) {
            return deal.semantic.ir.FailureProjections.CarrierKind.LANGUAGE_NULL;
        }
        if (value instanceof Boolean) {
            return deal.semantic.ir.FailureProjections.CarrierKind.BOOLEAN;
        }
        if (value instanceof Long || value instanceof Double) {
            return deal.semantic.ir.FailureProjections.CarrierKind.NUMBER;
        }
        if (value instanceof String) {
            return deal.semantic.ir.FailureProjections.CarrierKind.STRING;
        }
        if (value instanceof FunctionValue) {
            return deal.semantic.ir.FailureProjections.CarrierKind.DEAL_FUNCTION;
        }
        return deal.semantic.ir.FailureProjections.CarrierKind.TABLE;
    }

    /**
     * The walk family's closed cycle arm: a path-local container
     * re-entry renders through the arm at the op boundary with no
     * parameters and no expected/actual fields.
     */
    private static StdlibFailure jsonCycleFailure() {
        return new StdlibFailure(deal.semantic.ir.FailureArmId.JSON_TO_WALK_CYCLE,
            java.util.Map.of(), null, null);
    }

    /**
     * One open container of the stack-safe stringify walk: the explicit
     * work stack replaces the recursive descent, so a finite nested
     * input has no stack bound. Each frame emits its entries in
     * first-insertion/index order from {@link #index}.
     */
    private static final class StringifyFrame {

        final Table table;
        final Array array;
        final List<String> keys;
        int index;

        StringifyFrame(Table table, List<String> keys) {
            this.table = table;
            this.array = null;
            this.keys = keys;
        }

        StringifyFrame(Array array) {
            this.table = null;
            this.array = array;
            this.keys = null;
        }
    }

    /**
     * The stack-safe stringify entry: an explicit work stack replaces
     * the recursion, and the identity set keeps the path-local cycle
     * detection (entered on open, removed on close). Entries emit in
     * first-insertion/index order and the first declaration-order
     * failure wins unchanged.
     */
    private static void stringifyValue(StringBuilder out, Object value,
                                       java.util.Set<Object> path) {
        Deque<StringifyFrame> stack = new ArrayDeque<>();
        appendJsonValue(out, value, path, stack);
        while (!stack.isEmpty()) {
            StringifyFrame frame = stack.peek();
            if (frame.table == null) {
                if (frame.index < frame.array.elements.size()) {
                    int i = frame.index++;
                    if (i > 0) {
                        out.append(',');
                    }
                    appendJsonValue(out, frame.array.elements.get(i), path, stack);
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
                // RFC 8259 output must be UTF-8: an invalid scalar
                // sequence is still a string carrier, so an invalid key
                // rejects through the closed JSON_STRINGIFY_UNSUPPORTED
                // arm (actual string) before any text is appended,
                // exactly like the shipped std/json and JS walks.
                if (!JvmJson.validScalars(key)) {
                    throw jsonEncodingFailure(key);
                }
                if (i > 0) {
                    out.append(',');
                }
                appendJsonString(out, key);
                out.append(':');
                appendJsonValue(out, frame.table.entries.get(key), path, stack);
            } else {
                out.append('}');
                path.remove(frame.table);
                stack.pop();
            }
        }
    }

    /**
     * Appends one value: a leaf emits its text, a container checks the
     * path-local identity and opens a frame. The first declaration-order
     * failure wins (pre-order), exactly as the recursive walk selected
     * it.
     */
    private static void appendJsonValue(StringBuilder out, Object value,
                                        java.util.Set<Object> path,
                                        Deque<StringifyFrame> stack) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean bool) {
            out.append(bool ? "true" : "false");
        } else if (value instanceof Number numeric) {
            double d = numeric.doubleValue();
            if (!Double.isFinite(d)) {
                throw jsonEncodingFailure(numeric);
            }
            out.append(d == 0 ? "0" : Double.toString(d));
        } else if (value instanceof String string) {
            // An invalid scalar sequence is still a string carrier: it
            // rejects through the closed JSON_STRINGIFY_UNSUPPORTED arm
            // (actual string), never as escaped invalid bytes that the
            // STDLIB_RETURN cell would reject after the walk.
            if (!JvmJson.validScalars(string)) {
                throw jsonEncodingFailure(string);
            }
            appendJsonString(out, string);
        } else if (value instanceof Table table) {
            if (!path.add(table)) {
                throw jsonCycleFailure();
            }
            out.append('{');
            stack.push(new StringifyFrame(table,
                new ArrayList<>(table.entries.keySet())));
        } else if (value instanceof Array array) {
            if (!path.add(array)) {
                throw jsonCycleFailure();
            }
            out.append('[');
            stack.push(new StringifyFrame(array));
        } else if (value == MISSING) {
            throw jsonEncodingFailure(value);
        } else if (value instanceof FunctionValue) {
            throw jsonEncodingFailure(value);
        } else if (value instanceof ClassInstance) {
            // A class instance (the builtin Error value included) is a
            // table-carried value on the carrier-kind projection.
            throw jsonEncodingFailure(value);
        } else {
            throw jsonEncodingFailure(value);
        }
    }

    /**
     * RFC-8259 string escaping: quote and backslash escaped; the named
     * short escapes \b \f \n \r \t; every other control scalar
     * (U+0000-U+001F) as the lowercase four-hex-digit escape; every other scalar —
     * surrogate pairs included — emitted as raw scalar UTF-8.
     */
    private static void appendJsonString(StringBuilder out, String carrier) {
        out.append('"');
        for (int i = 0; i < carrier.length();) {
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

    // =========================================================================
    // Array read/bounds cells (shared slot-space context)
    // =========================================================================

    /**
     * The ARRAY_ELEMENT_READ cell: the pinned E8002 bounds check
     * ({@code index < 0} or {@code index >= length}) before any storage
     * access, then the contextual decision. The read's own normalize-time
     * length operand is the current logical length, so the check matches
     * the shared bounds contract on every consumer.
     */
    public static Object arrayRead(String opKey, String digest, String parent,
                                   String bKey, String bDigest, String bParent,
                                   String desc, String inner, Array container,
                                   double index, double length, boolean nullable,
                                   String origin) {
        Object elem = MISSING;
        if (!Double.isFinite(index) || index != Math.rint(index) || index < 0 || index >= length) {
            ev(currentModule(), bKey, "START", "BOUNDARY", bDigest, bParent,
                List.of(atom(MISSING, "missing")), null, null);
            DealError e = arm(deal.semantic.ir.FailureArmId.ARRAY_READ_BOUNDS,
                java.util.Map.of(), origin, null, null);
            ev(currentModule(), bKey, "FAILURE", "BOUNDARY", bDigest, bParent,
                List.of(), null, errtext(e));
            ev(currentModule(), opKey, "FAILURE", "INDEX_READ", digest, parent,
                List.of(), null, errtext(e));
            throw e;
        }
        if (index < container.elements.size()) {
            elem = container.elements.get((int) index);
        }
        // A present null element stays null (the slot was stored with a
        // language null); only an absent/deleted slot reads as missing.
        String elemKind;
        if (elem == MISSING) {
            elemKind = "missing";
        } else if (elem == null) {
            elemKind = "null";
        } else {
            elemKind = inner;
        }
        ev(currentModule(), bKey, "START", "BOUNDARY", bDigest, bParent,
            List.of(atom(elem, elemKind)), null, null);
        ev(currentModule(), bKey, "SUCCESS", "BOUNDARY", bDigest, bParent, List.of(),
            atom(elem, elemKind), null);
        if (elem == MISSING) {
            if (nullable) {
                return null;
            }
            return MISSING;
        }
        return elem;
    }

    /**
     * The closed canonical spelling of one runtime-internal descriptor text
     * (the semantic oracle's {@code canonicalSpecText} over the same
     * descriptor): a boundary check emitted without the canonical trailer
     * still projects the canonical element text in its E8003 row — the
     * function positions included, so the internal
     * {@code function(params;return)} dialect converts to the canonical
     * {@code (params)->return} form recursively.
     */
    static String canonicalDesc(String desc) {
        if (desc.startsWith("array(") && desc.endsWith(")")) {
            return "[" + canonicalDesc(desc.substring(6, desc.length() - 1)) + "]";
        }
        if (desc.startsWith("nullable(") && desc.endsWith(")")) {
            return "?" + canonicalDesc(desc.substring(9, desc.length() - 1));
        }
        if (desc.startsWith("function(") && desc.endsWith(")")) {
            String inner = desc.substring(9, desc.length() - 1);
            int depth = 0;
            int semi = -1;
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == ';' && depth == 0) {
                    semi = i;
                    break;
                }
            }
            if (semi < 0) {
                return desc;
            }
            StringBuilder params = new StringBuilder();
            int pdepth = 0;
            int start = 0;
            for (int i = 0; i <= semi; i++) {
                boolean atEnd = i == semi;
                char c = atEnd ? ',' : inner.charAt(i);
                if (!atEnd && c == '(') {
                    pdepth++;
                } else if (!atEnd && c == ')') {
                    pdepth--;
                }
                if (c == ',' && pdepth == 0) {
                    if (params.length() > 0) {
                        params.append(',');
                    }
                    params.append(canonicalDesc(inner.substring(start, i)));
                    start = i + 1;
                }
            }
            return "(" + params + ")->"
                + canonicalDesc(inner.substring(semi + 1));
        }
        return desc;
    }

    /** The ARRAY_ELEMENT_ASSIGNMENT/ARRAY_ELEMENT_DELETE cells. */
    public static void arrayBounds(String bKey, String bDigest, String bParent,
                                   Object input, double index, double length, String desc,
                                   String staticKind, String canon, boolean isAssign,
                                   String origin) {
        ev(currentModule(), bKey, "START", "BOUNDARY", bDigest, bParent,
            List.of(atom(input, staticKind)), null, null);
        if (!Double.isFinite(index) || index != Math.rint(index) || index < 0 || index > length) {
            DealError e = arm(deal.semantic.ir.FailureArmId.ARRAY_WRITE_BOUNDS,
                java.util.Map.of(), origin, null, null);
            ev(currentModule(), bKey, "FAILURE", "BOUNDARY", bDigest, bParent, List.of(),
                null, errtext(e));
            throw e;
        }
        if (isAssign) {
            try {
                bcheck(desc, staticKind, input, canon);
            } catch (DealError leaf) {
                // The element cell's own origin is the assignment
                // expression (the closed projection table's
                // ARRAY_ELEMENT_ASSIGNMENT cell), never the runtime
                // matcher's internal placeholder.
                throw new DealError(leaf.code, leaf.msg, origin, leaf.expected,
                    leaf.actual, leaf.frames, leaf.cause);
            }
        }
        ev(currentModule(), bKey, "SUCCESS", "BOUNDARY", bDigest, bParent, List.of(),
            atom(input, staticKind), null);
    }

    /** The ARRAY_ELEMENT_DELETE cell whose input operand is the normalized slot. */
    public static void arrayBoundsSlot(String bKey, String bDigest, String bParent,
                                       double[] slot, double length, String origin) {
        double index = slot[0];
        String slotAtom = "slot:" + Double.toHexString(index) + "/" + (index < length) + "/"
            + (slot[2] == 1 && index == length);
        ev(currentModule(), bKey, "START", "BOUNDARY", bDigest, bParent,
            List.of(slotAtom), null, null);
        if (!Double.isFinite(index) || index != Math.rint(index) || index < 0 || index > length) {
            DealError e = arm(deal.semantic.ir.FailureArmId.ARRAY_WRITE_BOUNDS,
                java.util.Map.of(), origin, null, null);
            ev(currentModule(), bKey, "FAILURE", "BOUNDARY", bDigest, bParent, List.of(),
                null, errtext(e));
            throw e;
        }
        ev(currentModule(), bKey, "SUCCESS", "BOUNDARY", bDigest, bParent, List.of(),
            slotAtom, null);
    }

    /** The FOR_EACH op's own TYPE_DESCRIPTOR terminal check. */
    public static void foreachCheck(String opKey, String digest, String parent,
                                    String desc, String canon, Object elem,
                                    String origin) {
        try {
            bcheck(desc, desc, elem, canon);
        } catch (DealError e) {
            // The op's own origin is the failure's origin (the op-level
            // boundary cell), never the prelude's internal placeholder.
            DealError failure = new DealError(e.code, e.msg, origin, e.expected, e.actual,
                e.frames, e.cause);
            ev(currentModule(), opKey, "FAILURE", "FOR_EACH", digest, parent, List.of(),
                null, errtext(failure));
            throw failure;
        }
    }


// =========================================================================
    // Module context
    // =========================================================================

    private static String module = "";

    public static void setModule(String moduleName) {
        module = moduleName;
    }

    public static String currentModule() {
        return module;
    }


// =========================================================================
    // The reserved async-export host surface
    // =========================================================================

    /**
     * The shared stdout marker prefix of the async-export envelope
     * protocol: byte-equal to {@code AsyncExportEnvelope.RESULT_PREFIX},
     * so the emitted host surface, the LuaJIT driver, and both invokers
     * speak one frame.
     */
    public static final String ASYNC_EXPORT_RESULT_PREFIX =
        deal.codegen.AsyncExportEnvelope.RESULT_PREFIX;

    /**
     * The trailing {@code file:line:column} origin text the emitted
     * artifact threads into every {@link DealError} (the emitter's
     * {@code originOf} form; the same text the LuaJIT lane's closed
     * failure carrier holds in its {@code o} field). The pattern is
     * DOTALL: a source path is an arbitrary legal filesystem name, so a
     * line terminator inside it must not defeat the coordinate split (the
     * greedy {@code (.*)} still takes the trailing pair).
     */
    private static final java.util.regex.Pattern ASYNC_EXPORT_ERROR_ORIGIN =
        java.util.regex.Pattern.compile("^(.*):(\\d+):(\\d+)$",
            java.util.regex.Pattern.DOTALL);

    /**
     * One registry row of the reserved async-export host surface (the
     * emitted entry class's declared exports): the export name, the
     * declaration's canonical spec text, the row kind ({@code "f"} for a
     * function export, {@code "c"} for a class export), the selection
     * failure class ({@code "sync"}/{@code "param"}/{@code "mismatch"}),
     * the activation of the declaration's async {@code EXTERNAL_ENTRY}
     * (null when the export is not a zero-argument async function), and
     * the declared completion descriptor triple (the runtime-side
     * descriptor text, the static kind, and the canonical spec text when
     * the descriptor carries a function position).
     */
    public record AsyncExportRow(String name, String spec, String kind, String fail,
                                 Fn invoke, String completionDesc,
                                 String completionKind, String completionCanon) {
    }

    /**
     * The reserved async-export selection and invocation (the runtime
     * half's pinned host-failure reasons, byte-equal to the LuaJIT
     * runtime's {@code invoke_async_export}): exactly one export whose
     * carried canonical spec equals {@code "async()->" + returnDescriptor}
     * is activated once through its own async entry and its completion is
     * checked through the canonical matcher.
     *
     * <p>Returns {@code {reason}} when the export cannot be selected (it
     * never activates an unselected export), or {@code {null, completion}}
     * when the selected export completed and its completion passed the
     * declared descriptor's check. A DEAL failure of the operation or of
     * the completion matcher propagates as the identical
     * {@link DealError} — never a reason and never a re-projection.</p>
     */
    public static Object[] invokeAsyncExport(List<AsyncExportRow> rows,
                                             String exportName,
                                             String returnDescriptor) {
        if (exportName == null) {
            return new Object[] {"exportName must be a string, got nil"};
        }
        if (returnDescriptor == null) {
            return new Object[] {
                "return descriptor is not a canonical descriptor: nil"};
        }
        String expected = "async()->" + returnDescriptor;
        AsyncExportRow found = null;
        int occurrences = 0;
        for (AsyncExportRow row : rows) {
            if (row.name().equals(exportName)) {
                occurrences++;
                found = row;
            }
        }
        if (occurrences == 0) {
            return new Object[] {"missing export '" + exportName + "'"};
        }
        if (occurrences > 1) {
            return new Object[] {"duplicate export '" + exportName
                + "': the same wrapper appears under multiple export keys"};
        }
        if (!"f".equals(found.kind())) {
            return new Object[] {"export '" + exportName
                + "' is not a function wrapper"};
        }
        if (!expected.equals(found.spec())) {
            if ("sync".equals(found.fail())) {
                return new Object[] {"export '" + exportName
                    + "' is sync: expected '" + expected + "', got '"
                    + found.spec() + "'"};
            }
            if ("param".equals(found.fail())) {
                return new Object[] {"export '" + exportName
                    + "' is parameterized: expected '" + expected + "', got '"
                    + found.spec() + "'"};
            }
            return new Object[] {"export '" + exportName
                + "' signature mismatch: expected '" + expected + "', got '"
                + found.spec() + "'"};
        }
        if (found.invoke() == null) {
            return new Object[] {"export '" + exportName
                + "' is not a function wrapper"};
        }
        Object completion = found.invoke().invoke(new Object[] {});
        Object checked = found.completionCanon() == null
            ? bcheckCompletion(found.completionDesc(), found.completionKind(),
                completion)
            : bcheckCompletion(found.completionDesc(), found.completionKind(),
                completion, found.completionCanon());
        return new Object[] {null, checked};
    }

    /**
     * The marker-prefixed envelope line of one status (the shared
     * protocol of the LuaJIT driver and the JVM host surface): the status
     * followed by the ordered key/value pairs, every value JSON-quoted.
     */
    public static String asyncExportEnvelope(String status, String... fields) {
        StringBuilder sb = new StringBuilder(ASYNC_EXPORT_RESULT_PREFIX);
        sb.append("{\"status\":");
        appendJsonString(sb, String.valueOf(status));
        for (int i = 0; i + 1 < fields.length; i += 2) {
            sb.append(',');
            appendJsonString(sb, String.valueOf(fields[i]));
            sb.append(':');
            appendJsonString(sb, String.valueOf(fields[i + 1]));
        }
        return sb.append('}').toString();
    }

    /**
     * The {@code deal-error} envelope line of one propagated DEAL error
     * (the shared protocol's one projection of a {@link DealError}): the
     * runtime half's field set with the carried source origin preserved —
     * the origin text's {@code file:line:column} split into the envelope's
     * {@code file} string and the {@code line}/{@code column} JSON
     * integers, so a production caller can locate the operation, await,
     * or {@code main} failure. The location fields are omitted only for a
     * genuinely absent origin ({@code null} or the emitter's {@code "-"}
     * absence marker); an origin that does not carry the trailing
     * line/column pair still projects its file text, never nothing.
     */
    public static String asyncExportDealErrorEnvelope(String code, String message,
                                                      String origin) {
        return asyncExportDealErrorEnvelope(code, message, origin,
            null, null, null);
    }

    /**
     * The complete {@code deal-error} envelope projection: the carrier's
     * expected/actual pair and ordered frame text travel with the
     * code/message/origin (the ErrorDetail contract), each as its own
     * optional field. Absent optional metadata omits its field entirely.
     */
    public static String asyncExportDealErrorEnvelope(String code, String message,
                                                      String origin,
                                                      String expected, String actual,
                                                      String frames) {
        return asyncExportDealErrorEnvelope(code, message, origin,
            expected, actual, frames, null);
    }

    public static String asyncExportDealErrorEnvelope(DealError error) {
        return asyncExportDealErrorEnvelope(error.code, error.msg, error.origin,
            error.expected, error.actual, error.frames, error.cause);
    }

    private static String asyncExportDealErrorEnvelope(String code, String message,
            String origin, String expected, String actual, String frames, DealError cause) {
        StringBuilder sb = new StringBuilder(ASYNC_EXPORT_RESULT_PREFIX);
        sb.append("{\"status\":\"deal-error\",");
        appendErrorDetail(sb, code, message, origin, expected, actual, frames, cause);
        return sb.append('}').toString();
    }

    private static void appendErrorDetail(StringBuilder sb, String code, String message,
            String origin, String expected, String actual, String frames, DealError cause) {
        sb.append("\"code\":");
        appendJsonString(sb, String.valueOf(code));
        sb.append(",\"message\":");
        appendJsonString(sb, String.valueOf(message));
        if (origin != null && !origin.isEmpty() && !"-".equals(origin)) {
            java.util.regex.Matcher parsed = ASYNC_EXPORT_ERROR_ORIGIN.matcher(origin);
            if (parsed.matches()) {
                sb.append(",\"file\":");
                appendJsonString(sb, parsed.group(1));
                sb.append(",\"line\":").append(parsed.group(2));
                sb.append(",\"column\":").append(parsed.group(3));
            } else {
                sb.append(",\"file\":");
                appendJsonString(sb, origin);
            }
        }
        // The absent optional fields (null or the emitter's "-" marker)
        // omit their envelope field; every carried fact stays verbatim.
        appendOptionalEnvelopeField(sb, "expected", expected);
        appendOptionalEnvelopeField(sb, "actual", actual);
        appendOptionalEnvelopeField(sb, "frames", frames);
        if (cause != null) {
            sb.append(",\"cause\":{");
            appendErrorDetail(sb, cause.code, cause.msg, cause.origin,
                cause.expected, cause.actual, cause.frames, cause.cause);
            sb.append('}');
        }
    }

    private static void appendOptionalEnvelopeField(StringBuilder sb,
                                                     String name, String value) {
        if (value == null || "-".equals(value)) {
            return;
        }
        sb.append(',');
        appendJsonString(sb, name);
        sb.append(':');
        appendJsonString(sb, value);
    }

    /**
     * The JSON text of one checked async-export completion over the
     * JSON-encodable surface: the same production value walk the
     * {@code JSON_STRINGIFY} stdlib row uses, so the host surface encodes
     * with the artifact's one JSON algorithm. An unsupported value
     * (a function carrier, an absent slot, a class instance, a
     * cycle, a non-finite number) raises — the emitted host surface
     * classifies that raise as a representation failure.
     */
    public static String jsonText(Object value) {
        StringBuilder out = new StringBuilder();
        try {
            stringifyValue(out, value, java.util.Collections.newSetFromMap(
                new IdentityHashMap<>()));
        } catch (StdlibFailure failure) {
            // The host envelope renders the closed arm's own text (never
            // the arm id): an arm-carried failure renders through the
            // authority, a plain composed failure keeps its message.
            if (failure.armId != null) {
                throw new RuntimeException(deal.semantic.ir.FailureContractRegistry
                    .render(failure.armId, failure.parameters, failure.expected,
                        failure.actual, null)
                    .message());
            }
            throw failure;
        }
        return out.toString();
    }
}
