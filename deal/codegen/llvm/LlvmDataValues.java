package deal.codegen.llvm;

import deal.semantic.ir.ClassId;
import deal.semantic.ir.RuntimeDescriptor;

import java.util.Objects;

/**
 * The one native&harr;{@code deal_data} conversion of the native backend
 * ({@code llvm-native-codegen-cfg-ssa} C3, {@code llvm-native-runtime-value-
 * model} R2/R3/R4).
 *
 * <p>{@link #box} builds the persistent tagged datum of one {@code unknown}
 * position from a value in its C3 representation, and {@link #unbox}
 * materializes one declared representation back out of a datum. Both are
 * branchless ({@code select} over the datum's own tag), so a conversion never
 * opens a basic block: the emitted op group keeps its single segment and no
 * conversion can invent a CFG predecessor the IR does not record.</p>
 *
 * <p>The conversions are one authority: the emitted class construction, class
 * field access, array element access and the generated JSON walks reach the
 * runtime's boxed surface through them, so no two emission sites can disagree
 * about a tag, a payload member or the singleton a nullish arm yields. A
 * function value has no datum form (C3: it is the managed {@code deal_fn*} of
 * C5, never a code pointer under a managed tag), and {@code void} has no data
 * position at all.</p>
 */
final class LlvmDataValues {

    private LlvmDataValues() {
        // Static conversions only; no instances.
    }

    /** The minimal IR sink of one generated function. */
    interface Sink {

        /** Appends one instruction line to the current block. */
        void line(String text);

        /** Emits one unnamed temporary and answers its SSA name. */
        String temp(String instruction);
    }

    /**
     * Whether one descriptor has a persistent datum form (C3/R3): every
     * supported DEAL datum except a function value, which the managed
     * {@code deal_fn*} carrier of C5 owns, and {@code void}.
     */
    static boolean hasDatumForm(RuntimeDescriptor descriptor) {
        return LlvmTypeMapper.hasDatumForm(descriptor);
    }

    /** The distinct null or undefined singleton pointer (never C {@code NULL}). */
    static String singleton(LlvmSession session, Sink ir, boolean nullish) {
        String global = nullish
            ? "deal_null_singleton" : "deal_undefined_singleton";
        session.referenceExternalGlobal(global);
        return ir.temp("load i8*, i8** @" + global);
    }

    /**
     * The absent-but-declared value of one optional read: {@code undefined}
     * when the declared read type admits it, {@code null} otherwise (R2:
     * absence is presence metadata, never a stored data value).
     */
    static String absent(LlvmSession session, Sink ir,
                         RuntimeDescriptor.Nullable target) {
        boolean undefined = LlvmTypeMapper.admitsUndefined(target);
        String representation = LlvmTypeMapper.representationOf(target)
            .orElseThrow(() -> new IllegalStateException(
                "the nullish descriptor '" + target.canonicalSpecText()
                    + "' has no representation"));
        if (representation.equals(LlvmTypeMapper.REFERENCE_NULLISH)) {
            return singleton(session, ir, !undefined);
        }
        return tagged(ir, representation, Integer.toString(undefined
            ? LlvmTypeMapper.NULLISH_UNDEFINED : LlvmTypeMapper.NULLISH_NULL),
            null);
    }

    /** One {@code {tag, value}} scalar nullish value. */
    private static String tagged(Sink ir, String representation, String tag,
                                 String base) {
        boolean number = representation.equals(LlvmTypeMapper.NULLISH_NUMBER);
        String zero = "{ i32 " + tag + ", " + (number ? "double 0.0" : "i1 false")
            + " }";
        if (base == null) {
            return zero;
        }
        return ir.temp("insertvalue " + representation + " " + zero + ", "
            + (number ? "double " : "i1 ") + base + ", 1");
    }

    /**
     * Boxes one value of {@code from} into the persistent tagged datum of an
     * {@code unknown} position (C3). A datum input is returned unchanged.
     */
    static String box(LlvmSession session, Sink ir, RuntimeDescriptor from,
                      String operand) {
        Objects.requireNonNull(from, "from must not be null");
        if (from instanceof RuntimeDescriptor.Unknown) {
            return operand;
        }
        if (from instanceof RuntimeDescriptor.Null) {
            return datum(ir, Integer.toString(LlvmTypeMapper.DATA_NULL),
                null, null);
        }
        if (from instanceof RuntimeDescriptor.Undefined) {
            return datum(ir, Integer.toString(LlvmTypeMapper.DATA_UNDEFINED),
                null, null);
        }
        if (from instanceof RuntimeDescriptor.Number) {
            return datum(ir, Integer.toString(LlvmTypeMapper.DATA_NUMBER),
                operand, null);
        }
        if (from instanceof RuntimeDescriptor.Boolean) {
            String widened = ir.temp("zext i1 " + operand + " to i32");
            String folded = ir.temp("uitofp i32 " + widened + " to double");
            return datum(ir, Integer.toString(LlvmTypeMapper.DATA_BOOLEAN),
                folded, null);
        }
        if (from instanceof RuntimeDescriptor.Nullable nullable) {
            return boxNullish(session, ir, nullable, operand);
        }
        if (from instanceof RuntimeDescriptor.String) {
            return referenceDatum(ir, LlvmTypeMapper.DATA_STRING,
                LlvmTypeMapper.STRING, operand);
        }
        if (from instanceof RuntimeDescriptor.Array) {
            return referenceDatum(ir, LlvmTypeMapper.DATA_ARRAY,
                LlvmTypeMapper.ARRAY, operand);
        }
        if (from instanceof RuntimeDescriptor.Table) {
            return referenceDatum(ir, LlvmTypeMapper.DATA_TABLE,
                LlvmTypeMapper.TABLE, operand);
        }
        if (from instanceof RuntimeDescriptor.Class classType) {
            boolean builtin = classType.classId().equals(ClassId.ERROR);
            // C3 fixes the builtin Error instance to its own runtime record:
            // the emitted module defines no per-layout struct for it
            // (LlvmClassPlans.definitions), so its boxed payload is the
            // `deal_error*` the value already holds and never a pointer of a
            // layout type the module does not define.
            return referenceDatum(ir, builtin ? LlvmTypeMapper.DATA_ERROR
                    : LlvmTypeMapper.DATA_CLASS,
                builtin ? LlvmTypeMapper.ERROR_POINTER
                    : LlvmTypeMapper.classRepresentation(classType.classId()),
                operand);
        }
        if (from instanceof RuntimeDescriptor.Func) {
            // The function tag alone classifies the value: the managed
            // `reference` member stays NULL, because a code pointer is not the
            // managed `deal_fn*` of C5 and a managed tag over a `.text` address
            // is a collector defect (C3, R2).
            return datum(ir, Integer.toString(LlvmTypeMapper.DATA_FUNCTION),
                null, null);
        }
        throw new IllegalStateException("the value of type '"
            + from.canonicalSpecText() + "' has no persistent data form");
    }

    /**
     * One {@code deal_data} value built as an insertvalue chain: a tag, the
     * binary64 payload of a number or a folded boolean, and the managed payload
     * of a reference tag. A non-constant tag and a non-constant payload are
     * both SSA values, so the datum is never spelled as an aggregate constant
     * with a variable member.
     */
    private static String datum(Sink ir, String tag, String number,
                                String reference) {
        String value = ir.temp("insertvalue " + LlvmTypeMapper.DATA
            + " zeroinitializer, i32 " + tag + ", 0");
        if (number != null) {
            value = ir.temp("insertvalue " + LlvmTypeMapper.DATA + " " + value
                + ", double " + number + ", 2");
        }
        if (reference != null) {
            value = ir.temp("insertvalue " + LlvmTypeMapper.DATA + " " + value
                + ", i8* " + reference + ", 3");
        }
        return value;
    }

    /** One reference datum of the given tag. */
    private static String referenceDatum(Sink ir, int tag, String representation,
                                         String operand) {
        String reduced = representation.equals("i8*") ? operand
            : ir.temp("bitcast " + representation + " " + operand + " to i8*");
        return datum(ir, Integer.toString(tag), null, reduced);
    }

    /** The tag-aware datum of one nullish operand (branchless, R2). */
    private static String boxNullish(LlvmSession session, Sink ir,
                                     RuntimeDescriptor.Nullable nullable,
                                     String operand) {
        String representation = LlvmTypeMapper.representationOf(nullable)
            .orElseThrow(() -> new IllegalStateException(
                "the nullish descriptor '" + nullable.canonicalSpecText()
                    + "' has no representation"));
        RuntimeDescriptor inner = nullable.inner();
        if (representation.equals(LlvmTypeMapper.REFERENCE_NULLISH)) {
            session.referenceRuntimeEntry("deal_value_is_null");
            session.referenceRuntimeEntry("deal_value_is_undefined");
            String isNull = ir.temp("call i32 @deal_value_is_null(i8* "
                + operand + ")");
            String isUndefined = ir.temp("call i32 @deal_value_is_undefined(i8* "
                + operand + ")");
            String nullFlag = ir.temp("icmp ne i32 " + isNull + ", 0");
            String undefinedFlag = ir.temp("icmp ne i32 " + isUndefined + ", 0");
            String baseTag = ir.temp("select i1 " + nullFlag + ", i32 "
                + LlvmTypeMapper.DATA_NULL + ", i32 " + tagOf(inner));
            String tag = ir.temp("select i1 " + undefinedFlag + ", i32 "
                + LlvmTypeMapper.DATA_UNDEFINED + ", i32 " + baseTag);
            return datum(ir, tag, null, operand);
        }
        boolean number = representation.equals(LlvmTypeMapper.NULLISH_NUMBER);
        String tag = ir.temp("extractvalue " + representation + " " + operand
            + ", 0");
        String base = ir.temp("extractvalue " + representation + " " + operand
            + ", 1");
        String isNull = ir.temp("icmp eq i32 " + tag + ", "
            + LlvmTypeMapper.NULLISH_NULL);
        String isUndefined = ir.temp("icmp eq i32 " + tag + ", "
            + LlvmTypeMapper.NULLISH_UNDEFINED);
        String baseTag = ir.temp("select i1 " + isNull + ", i32 "
            + LlvmTypeMapper.DATA_NULL + ", i32 " + tagOf(inner));
        String finalTag = ir.temp("select i1 " + isUndefined + ", i32 "
            + LlvmTypeMapper.DATA_UNDEFINED + ", i32 " + baseTag);
        String folded = number ? base
            : ir.temp("uitofp i32 " + ir.temp("zext i1 " + base
                + " to i32") + " to double");
        return datum(ir, finalTag, folded, null);
    }

    /** The datum tag of one non-nullish base descriptor. */
    private static int tagOf(RuntimeDescriptor inner) {
        return switch (inner) {
            // A `null|undefined` union names the undefined atom as its base and
            // admits no other value, so this arm is the tag of the union's own
            // singleton (the two nullish selects above decide every actual
            // member); a declared `null` base is never reachable for the same
            // reason.
            case RuntimeDescriptor.Undefined ignored -> LlvmTypeMapper.DATA_UNDEFINED;
            case RuntimeDescriptor.Null ignored -> LlvmTypeMapper.DATA_NULL;
            case RuntimeDescriptor.String ignored -> LlvmTypeMapper.DATA_STRING;
            case RuntimeDescriptor.Array ignored -> LlvmTypeMapper.DATA_ARRAY;
            case RuntimeDescriptor.Table ignored -> LlvmTypeMapper.DATA_TABLE;
            case RuntimeDescriptor.Class classType
                when classType.classId().equals(ClassId.ERROR) ->
                LlvmTypeMapper.DATA_ERROR;
            case RuntimeDescriptor.Class ignored -> LlvmTypeMapper.DATA_CLASS;
            case RuntimeDescriptor.Func ignored -> LlvmTypeMapper.DATA_FUNCTION;
            case RuntimeDescriptor.Number ignored -> LlvmTypeMapper.DATA_NUMBER;
            case RuntimeDescriptor.Boolean ignored -> LlvmTypeMapper.DATA_BOOLEAN;
            default -> throw new IllegalStateException(
                "the nullish base '" + inner.canonicalSpecText()
                    + "' has no datum tag");
        };
    }

    /**
     * The carried canonical class atom of one datum (R3/R4): the instance's own
     * emitted atom when the datum is a class instance, the static canonical
     * atom of a statically class-typed value, and {@code null} for every other
     * datum. A datum of an untyped position carries no atom member, so the atom
     * is read from the instance's own layout — through a null-pointer slot when
     * the datum is of another tag, so no emitted load can dereference null.
     */
    static String classAtomOf(LlvmSession session, Sink ir,
                              RuntimeDescriptor actual, String datum) {
        RuntimeDescriptor base = actual instanceof RuntimeDescriptor.Nullable nullable
            ? nullable.inner() : actual;
        if (base instanceof RuntimeDescriptor.Class classType) {
            return session.data.operand(
                session.data.intern(classType.classId().text()));
        }
        if (!(base instanceof RuntimeDescriptor.Unknown)) {
            return "i8* null";
        }
        String tag = ir.temp("extractvalue " + LlvmTypeMapper.DATA + " " + datum
            + ", 0");
        String isClass = ir.temp("icmp eq i32 " + tag + ", "
            + LlvmTypeMapper.DATA_CLASS);
        String reference = ir.temp("extractvalue " + LlvmTypeMapper.DATA + " "
            + datum + ", 3");
        String slot = "bitcast (i8** @" + session.data.nullPointerSlot()
            + " to i8*)";
        String safe = ir.temp("select i1 " + isClass + ", i8* " + reference
            + ", i8* " + slot);
        String atom = ir.temp("load i8*, i8** "
            + ir.temp("bitcast i8* " + safe + " to i8**"));
        return "i8* " + ir.temp("select i1 " + isClass + ", i8* " + atom
            + ", i8* null");
    }

    /**
     * Materializes one declared representation out of a persistent tagged
     * datum (C3/R4): the declared position is the authority, so a datum of
     * another kind is a producer defect this conversion never coerces.
     */
    static String unbox(LlvmSession session, Sink ir, RuntimeDescriptor to,
                        String datum) {
        Objects.requireNonNull(to, "to must not be null");
        if (to instanceof RuntimeDescriptor.Unknown) {
            return datum;
        }
        // A declared `null`/`undefined` position is always its own distinct
        // singleton (C3): the value carries no payload to project, and the
        // validated datum of such a position is that singleton.
        if (to instanceof RuntimeDescriptor.Null) {
            return singleton(session, ir, true);
        }
        if (to instanceof RuntimeDescriptor.Undefined) {
            return singleton(session, ir, false);
        }
        if (to instanceof RuntimeDescriptor.Number) {
            return ir.temp("extractvalue " + LlvmTypeMapper.DATA + " " + datum
                + ", 2");
        }
        if (to instanceof RuntimeDescriptor.Boolean) {
            String number = ir.temp("extractvalue " + LlvmTypeMapper.DATA + " "
                + datum + ", 2");
            return ir.temp("fcmp une double " + number + ", 0.0");
        }
        if (to instanceof RuntimeDescriptor.Nullable nullable) {
            return unboxNullish(session, ir, nullable, datum);
        }
        if (LlvmTypeMapper.isManagedPointer(to)) {
            String representation = LlvmTypeMapper.representationOf(to)
                .orElseThrow();
            String raw = ir.temp("extractvalue " + LlvmTypeMapper.DATA + " "
                + datum + ", 3");
            if (representation.equals("i8*")) {
                return raw;
            }
            return ir.temp("bitcast i8* " + raw + " to " + representation);
        }
        throw new IllegalStateException("the declared type '"
            + to.canonicalSpecText() + "' has no datum projection");
    }

    /** The nullish representation of one datum (branchless, R2). */
    private static String unboxNullish(LlvmSession session, Sink ir,
                                       RuntimeDescriptor.Nullable nullable,
                                       String datum) {
        String representation = LlvmTypeMapper.representationOf(nullable)
            .orElseThrow(() -> new IllegalStateException(
                "the nullish descriptor '" + nullable.canonicalSpecText()
                    + "' has no representation"));
        String tag = ir.temp("extractvalue " + LlvmTypeMapper.DATA + " "
            + datum + ", 0");
        String isNull = ir.temp("icmp eq i32 " + tag + ", "
            + LlvmTypeMapper.DATA_NULL);
        String isUndefined = ir.temp("icmp eq i32 " + tag + ", "
            + LlvmTypeMapper.DATA_UNDEFINED);
        if (representation.equals(LlvmTypeMapper.REFERENCE_NULLISH)) {
            String raw = ir.temp("extractvalue " + LlvmTypeMapper.DATA + " "
                + datum + ", 3");
            String nullArm = ir.temp("select i1 " + isNull + ", i8* "
                + singleton(session, ir, true) + ", i8* " + raw);
            return ir.temp("select i1 " + isUndefined + ", i8* "
                + singleton(session, ir, false) + ", i8* " + nullArm);
        }
        boolean number = representation.equals(LlvmTypeMapper.NULLISH_NUMBER);
        String payload = ir.temp("extractvalue " + LlvmTypeMapper.DATA + " "
            + datum + ", 2");
        String base = number ? payload
            : ir.temp("fcmp une double " + payload + ", 0.0");
        String baseTag = ir.temp("select i1 " + isNull + ", i32 "
            + LlvmTypeMapper.NULLISH_NULL + ", i32 "
            + LlvmTypeMapper.NULLISH_BASE);
        String finalTag = ir.temp("select i1 " + isUndefined + ", i32 "
            + LlvmTypeMapper.NULLISH_UNDEFINED + ", i32 " + baseTag);
        String tagged = ir.temp("insertvalue " + representation
            + " zeroinitializer, i32 " + finalTag + ", 0");
        return ir.temp("insertvalue " + representation + " " + tagged + ", "
            + (number ? "double " : "i1 ") + base + ", 1");
    }
}
