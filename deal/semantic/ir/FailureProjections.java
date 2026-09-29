package deal.semantic.ir;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The closed failure projections of the canonical failure-projection
 * authority (P2): the typed-boundary projection, the carrier-kind
 * projection, the completion variant, and the host inner-reason render.
 *
 * <p>The projections are total over the closed value kinds (a value
 * outside them is a producer defect, never a fabricated token), pure, and
 * identical in the three consumers: {@code SemanticOracle} (through
 * {@link BoundaryExecutor}), the emitted Lua prelude, and {@code JvmRuntime}
 * with the emitted host-ABI surface. The only permitted difference between
 * consumers is the target's value carrier, never the token table; target
 * class names never appear — a class instance projects its carried
 * canonical class atom.</p>
 */
public final class FailureProjections {

    private FailureProjections() {
        // Static surface only; pure and stateless.
    }

    /** The closed carrier kinds of the carrier-kind projection (P2 item 2). */
    public enum CarrierKind {
        /** The absent marker (Lua {@code nil}, the JVM missing sentinel). */
        ABSENT,
        /** The DEAL language-null sentinel. */
        LANGUAGE_NULL,
        /** A boolean carrier. */
        BOOLEAN,
        /**
         * A string carrier: a valid Unicode scalar sequence and an invalid
         * one alike project the Lua {@code type()} spelling {@code string}.
         */
        STRING,
        /** Every numeric carrier. */
        NUMBER,
        /**
         * The bytes carrier at the {@code std/json} rejection arm's pinned
         * member (the corpus pins {@code bytes}).
         */
        BYTES,
        /** A raw host-facing function. */
        HOST_FUNCTION,
        /**
         * A DEAL function-shaped carrier at the {@code std/json} rejection
         * arm's pinned member (the corpus pins {@code function} for a DEAL
         * function value; the host arms keep the table spelling).
         */
        DEAL_FUNCTION,
        /** Every other table-carried value. */
        TABLE
    }

    /**
     * The typed-boundary projection's token (P2 item 1): the absent marker
     * is {@code nil}, the language-null sentinel {@code null}, the closed
     * kinds their own tokens, and a class instance its carried canonical
     * class atom (never a target class name, never the {@code class:}
     * IR/trace spelling).
     *
     * @param kind    the closed actual kind; must not be null
     * @param classId the carried canonical class atom for
     *                {@link ActualKind#CLASS}, otherwise {@code null}
     * @return the canonical token
     */
    public static String typedBoundaryToken(ActualKind kind, String classId) {
        Objects.requireNonNull(kind, "kind must not be null");
        return switch (kind) {
            case MISSING -> "nil";
            case NULL -> "null";
            case BOOLEAN -> "boolean";
            case INT -> "int";
            case NUMBER -> "number";
            case STRING -> "string";
            case INVALID_UNICODE -> "invalid-unicode";
            case TABLE -> "table";
            case ARRAY -> "array";
            case BYTES -> "bytes";
            case FUNCTION -> "function";
            case ASYNC_OPERATION -> "async-operation";
            case NOTHING -> "nothing";
            case CLASS -> {
                if (classId == null || classId.isEmpty()) {
                    throw new BoundaryExecutor.Defect(
                        "a class instance without its carried canonical class atom has no "
                            + "typed-boundary token (a target class name never appears)");
                }
                yield classId;
            }
        };
    }

    /**
     * The completion variant (P2 item 4): the typed-boundary projection with
     * every numeric carrier projecting the single {@code number} kind.
     */
    public static String completionToken(ActualKind kind, String classId) {
        if (kind == ActualKind.INT || kind == ActualKind.NUMBER) {
            return "number";
        }
        return typedBoundaryToken(kind, classId);
    }

    /**
     * The carrier-kind projection's token (P2 item 2): the Lua-{@code type()}
     * shape of the unchanged runtimes — the absent marker is {@code nil},
     * the DEAL-null sentinel is {@code table}, a string carrier (a valid or
     * invalid Unicode scalar sequence) is {@code string}, and every other
     * table-carried value is {@code table} while a raw host-facing function
     * stays {@code function}. The {@code std/json} rejection arm's two
     * pinned members ({@link CarrierKind#BYTES},
     * {@link CarrierKind#DEAL_FUNCTION}) project their own tokens; the host
     * arms never classify a value as either member.
     */
    public static String carrierKindToken(CarrierKind kind) {
        Objects.requireNonNull(kind, "kind must not be null");
        return switch (kind) {
            case ABSENT -> "nil";
            case LANGUAGE_NULL -> "table";
            case BOOLEAN -> "boolean";
            case STRING -> "string";
            case NUMBER -> "number";
            case BYTES -> "bytes";
            case HOST_FUNCTION, DEAL_FUNCTION -> "function";
            case TABLE -> "table";
        };
    }

    /**
     * The closed kind text of one checked descriptor (the
     * {@code {kind}} parameter of the typed-boundary kind arm): a nullable
     * descriptor projects its inner descriptor's text; a class descriptor
     * projects {@code class instance}.
     */
    public static String kindText(RuntimeDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        return switch (descriptor) {
            case RuntimeDescriptor.Null ignored -> "null";
            case RuntimeDescriptor.Boolean ignored -> "boolean";
            case RuntimeDescriptor.Int ignored -> "int";
            case RuntimeDescriptor.Number ignored -> "number";
            case RuntimeDescriptor.String ignored -> "string";
            case RuntimeDescriptor.Table ignored -> "table";
            case RuntimeDescriptor.Bytes ignored -> "bytes";
            case RuntimeDescriptor.Array ignored -> "array";
            case RuntimeDescriptor.Func ignored -> "function";
            case RuntimeDescriptor.Class ignored -> "class instance";
            case RuntimeDescriptor.Nullable nullable -> kindText(nullable.inner());
        };
    }

    /** The closed kind token of one checked descriptor (a class projects {@code class}). */
    public static String kindToken(RuntimeDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        return switch (descriptor) {
            case RuntimeDescriptor.Nullable nullable -> kindToken(nullable.inner());
            case RuntimeDescriptor.Class ignored -> "class";
            default -> kindText(descriptor);
        };
    }

    /**
     * The descriptor-kind inner reason: the typed-boundary kind arm's own
     * template instantiated with the descriptor's closed kind text
     * ({@code expected null|boolean|int|…|class instance}).
     */
    public static String kindReason(RuntimeDescriptor descriptor) {
        FailureArm arm = FailureContractRegistry.arm(FailureArmId.TYPED_BOUNDARY_KIND);
        return arm.template().replace("{kind}", kindText(descriptor));
    }

    /**
     * The int path's refinement reason ({@code expected int, got NaN} /
     * {@code … infinity} / {@code … non-integer number}): the
     * {@code INT_CONVERSION} row's refinement arms, instantiated with the
     * final actual token.
     */
    public static String refinementReason(String expected, String actualToken) {
        FailureArmId id = switch (actualToken) {
            case "NaN" -> FailureArmId.INT_CONVERSION_NAN;
            case "infinity" -> FailureArmId.INT_CONVERSION_INFINITY;
            case "number" -> FailureArmId.INT_CONVERSION_FRACTIONAL;
            default -> throw new BoundaryExecutor.Defect(
                "the int refinement " + actualToken + " has no closed arm");
        };
        FailureArm arm = FailureContractRegistry.arm(id);
        return arm.template();
    }

    /**
     * An inner-only host string-carrier reason: the two INNER_ONLY
     * {@code TYPE_DESCRIPTOR} arms' own texts.
     */
    public static String stringCarrierReason(boolean surrogate) {
        FailureArmId id = surrogate
            ? FailureArmId.HOST_STRING_SURROGATE : FailureArmId.HOST_STRING_INVALID_UTF8;
        return FailureContractRegistry.renderInner(id, Map.of());
    }

    /**
     * The {@code std/json} rejection arm's render
     * ({@code JSON_STRINGIFY_UNSUPPORTED}): the arm's own pinned expected
     * text and the carrier-kind projection of the failing value as its
     * actual field (P2 item 2 with the arm's two pinned members). One
     * render for the oracle's executor family and the JVM runtime; the
     * emitted Lua prelude renders the same arm through its serialized
     * table.
     *
     * @param carrierKind the failing value's closed carrier kind; must not
     *                    be null
     * @return the rendered arm tuple
     */
    public static BoundaryFailure stringifyUnsupported(CarrierKind carrierKind) {
        FailureArm arm = FailureContractRegistry.arm(
            FailureArmId.JSON_STRINGIFY_UNSUPPORTED);
        String actual = carrierKindToken(carrierKind);
        return FailureContractRegistry.render(FailureArmId.JSON_STRINGIFY_UNSUPPORTED,
            Map.of("actual", actual),
            expectedFor(arm, null, null),
            actualFor(arm, null, actual, null, null, null), null);
    }

    /** The array-element inner reason: the element arm's own text. */
    public static String elementReason(int oneBasedIndex) {
        FailureArm arm = FailureContractRegistry.arm(FailureArmId.ARRAY_ELEMENT_KIND);
        return arm.template().replace("{oneBasedIndex}", Integer.toString(oneBasedIndex));
    }

    /**
     * The typed-boundary kind arm's expected failure for one canonical
     * descriptor spelling and one closed actual token — the shape the
     * boundary suites and the consumers' focused drives assert against.
     *
     * @param descriptorText the canonical descriptor text; must parse
     * @param actualToken    the closed typed-boundary actual token
     * @return the rendered kind-arm failure
     */
    public static BoundaryFailure kindFailure(String descriptorText, String actualToken) {
        RuntimeDescriptor descriptor = RuntimeDescriptor.parseCanonicalText(descriptorText);
        return FailureContractRegistry.render(FailureArmId.TYPED_BOUNDARY_KIND,
            Map.of("kind", kindText(descriptor)), kindToken(descriptor), actualToken, null);
    }

    /**
     * The arm's expected field, selected by the arm's declared expected-token
     * source: the closed token vocabulary of P2, never a composed text.
     *
     * @param arm     the arm whose expected source is resolved
     * @param cell    the failing cell's checked descriptor (the element
     *                descriptor for the element arm)
     * @param element the array's element descriptor for the element arm, else
     *                {@code null}
     * @return the expected token, or {@code null} when the arm declares none
     */
    public static String expectedFor(FailureArm arm, RuntimeDescriptor cell,
                                     RuntimeDescriptor element) {
        return switch (arm.expectedSource()) {
            case NONE -> null;
            case CELL_DESCRIPTOR, SIGNATURE -> cell.canonicalSpecText();
            case KIND_TOKEN -> kindToken(cell);
            case ELEMENT_DESCRIPTOR -> element.canonicalSpecText();
            case CLASS_ATOM -> ((RuntimeDescriptor.Class) cell).classId().text();
            case PINNED_TEXT -> arm.pinnedExpectedText();
            case ASYNC_OPERATION_TOKEN -> "async operation";
        };
    }

    /**
     * The arm's actual field, selected by the arm's declared actual
     * projection: the typed-boundary or carrier-kind token, the completion
     * variant, or the carried atom/signature the arm names. A
     * {@code SIBLING_OWNED} binding is not renderable by a production
     * consumer.
     */
    public static String actualFor(FailureArm arm, String typedToken, String carrierToken,
                                   String carriedAtom, String carriedSignature,
                                   String canonicalValueText) {
        return switch (arm.actualProjection()) {
            case NONE -> null;
            case TYPED_BOUNDARY, COMPLETION -> typedToken;
            case CARRIER_KIND -> carrierToken;
            case NOTHING_TOKEN -> "nothing";
            case CARRIED_CLASS_ATOM -> carriedAtom;
            case CARRIED_SIGNATURE -> carriedSignature;
            case CANONICAL_VALUE_TEXT -> canonicalValueText;
            case SIBLING_OWNED -> throw new BoundaryExecutor.Defect("arm " + arm.id()
                + " has a SIBLING_OWNED projection binding and no production consumer "
                + "may render it");
        };
    }
}
