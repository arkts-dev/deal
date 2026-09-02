package deal.semantic;

import deal.ast.ExpressionNode;
import deal.ast.FunctionExpr;
import deal.ast.IdentifierExpr;
import deal.checker.Symbol;
import deal.semantic.ir.BindingImmutabilityProof;
import deal.semantic.ir.CaptureMode;
import deal.semantic.ir.SemanticOpKind;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The closed {@code FUNCTION_ADAPT} mode shape map of the BINDINGS
 * capability (ISSUE-0450 shape-map child, sequencing item 7; design B7,
 * parent D15 "Lowering shape map"): every adapted source receives
 * exactly one mode from checker facts — never from target, route, or
 * emitter knowledge.
 *
 * <p><b>The closed map (B7/D15, exactly).</b></p>
 * <ul>
 *   <li><b>SHARED_CELL</b> — the source is a function-typed binding
 *       reference without a {@link BindingImmutabilityProof} (a
 *       reassignable local or parameter, a function name, or another
 *       identifier-resolved function-typed binding). This is the map's
 *       fallback arm for bindings: VALUE is admissible for a binding
 *       only with the recorded proof, so an unproven binding source is
 *       SHARED_CELL.</li>
 *   <li><b>VALUE</b> — (a) an already-materialized function-value
 *       operand: a function expression (the {@code CLOSURE_NEW} result)
 *       or an intrinsic function value ({@code int}/{@code number}
 *       first-class values); or (b) a binding with a recorded
 *       {@link BindingImmutabilityProof}.</li>
 *   <li><b>REEVALUATE_THUNK</b> — every other (non-identifier)
 *       expression: call results, member/field/index reads,
 *       conditionals, conversions, and any other composite source. A
 *       host/external import read such as
 *       {@code let h: (a:int,b:int)=>int = host.g;} is a non-identifier
 *       member-read expression and maps here; there is no VALUE
 *       carve-out for import reads — the host/external producer facts
 *       come from the registry child's materialization seam (T4) and
 *       the arm stays the same closed non-identifier arm, never a
 *       target-derived special case.</li>
 * </ul>
 *
 * <p><b>Determinism and purity.</b> The map is a pure function over its
 * facts: the same shape and proof facts always select the same mode, so
 * repeated lowering emits identical adapter modes (byte-identical
 * lowering's shape-map half). The selection surface accepts checker
 * facts only — no {@code Target}, no {@code ModuleRoutePlan}, and no
 * emitter input appears in any entry point of this class.</p>
 */
public final class AdapterShapeMap {

    /**
     * The closed source-shape classification of the map (B7): the
     * checker-fact shape that decides the arm before any payload is
     * built.
     */
    public enum SourceShape {

        /** A {@code FunctionExpr} — the {@code CLOSURE_NEW} result (materialized operand). */
        FUNCTION_EXPRESSION,

        /** A first-class {@code int}/{@code number} intrinsic function value. */
        INTRINSIC_FUNCTION_VALUE,

        /** An identifier-resolved function-typed binding reference. */
        BINDING_REFERENCE,

        /**
         * Every other (non-identifier) expression — call results,
         * member/field/index reads, conditionals, conversions, host/
         * external import reads, and any composite source.
         */
        NON_IDENTIFIER_EXPRESSION
    }

    private AdapterShapeMap() {
        // Static closed map; no instances.
    }

    // =========================================================================
    // The closed source-shape classification (checker facts only)
    // =========================================================================

    /**
     * The source-shape classification of one adapted source expression
     * (B7): a {@code FunctionExpr} → {@link SourceShape#FUNCTION_EXPRESSION};
     * an identifier resolving to the root {@code int}/{@code number}
     * intrinsic binding → {@link SourceShape#INTRINSIC_FUNCTION_VALUE}
     * (the checker removes the root intrinsic binding before a user
     * shadow, so the symbol fact decides — a shadowed name is a user
     * binding, never an intrinsic); any other identifier →
     * {@link SourceShape#BINDING_REFERENCE}; every other expression →
     * {@link SourceShape#NON_IDENTIFIER_EXPRESSION} (the closed
     * non-identifier arm owns member reads, so a host/external import
     * read classifies here — no VALUE carve-out exists).
     *
     * @param source  the adapted source expression; non-null
     * @param resolve the checker symbol resolver (name → root symbol);
     *                non-null — the intrinsic fact is the checker's
     *                {@link Symbol.IntrinsicSymbol} binding, never a
     *                spelling match
     * @return the closed source shape
     */
    public static SourceShape sourceShapeOf(ExpressionNode source,
                                            Function<String, Symbol> resolve) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(resolve, "resolve must not be null");
        if (source instanceof FunctionExpr) {
            return SourceShape.FUNCTION_EXPRESSION;
        }
        if (source instanceof IdentifierExpr identifier) {
            Symbol symbol = resolve.apply(identifier.name());
            if (symbol instanceof Symbol.IntrinsicSymbol intrinsic
                    && ("int".equals(intrinsic.name())
                        || "number".equals(intrinsic.name()))) {
                return SourceShape.INTRINSIC_FUNCTION_VALUE;
            }
            return SourceShape.BINDING_REFERENCE;
        }
        return SourceShape.NON_IDENTIFIER_EXPRESSION;
    }

    // =========================================================================
    // The closed mode map
    // =========================================================================

    /**
     * The closed map (B7/D15): exactly one mode per source shape, from
     * checker facts only.
     *
     * <ul>
     *   <li>{@link SourceShape#FUNCTION_EXPRESSION} → VALUE (the
     *       {@code CLOSURE_NEW} result is a materialized function-value
     *       operand);</li>
     *   <li>{@link SourceShape#INTRINSIC_FUNCTION_VALUE} → VALUE (a
     *       first-class intrinsic function value is a materialized
     *       operand; the intrinsic binding's proof fact — builtin,
     *       unassignable, always recorded — is not consulted for the
     *       mode and is not carried in the payload: the payload's
     *       {@code proof} is present iff VALUE's operand is a binding
     *       load, and the intrinsic operand is the intrinsic function
     *       value identity, not a load — B8);</li>
     *   <li>{@link SourceShape#BINDING_REFERENCE} → VALUE with the
     *       recorded {@link BindingImmutabilityProof}, SHARED_CELL
     *       without it (VALUE is admissible for a binding only with the
     *       proof — the map's fallback arm for bindings);</li>
     *   <li>{@link SourceShape#NON_IDENTIFIER_EXPRESSION} →
     *       REEVALUATE_THUNK (call results, member/field/index reads,
     *       conditionals, conversions, host/external import reads — no
     *       VALUE carve-out for import reads).</li>
     * </ul>
     *
     * <p>A proof is admissible exactly for an identifier source: for a
     * binding reference the proof selects the arm (VALUE with, SHARED_CELL
     * without); for an intrinsic function value the recorded proof is a
     * checker fact that never changes the arm and never enters the
     * payload (the operand is the intrinsic identity, not a binding
     * load). A proof supplied for a non-identifier shape is a producer
     * defect and fails closed — never silently ignored and never
     * reclassified (the payload's {@code proof} is present iff VALUE's
     * operand is a binding load, B8).</p>
     *
     * @param shape the closed source shape; non-null
     * @param proof the recorded proof fact of a binding source (absent
     *              for every non-binding shape); non-null
     * @return exactly one closed {@link CaptureMode}
     * @throws IllegalArgumentException if a proof is supplied for a
     *                                  non-binding shape (producer
     *                                  defect)
     */
    public static CaptureMode selectMode(SourceShape shape,
                                         Optional<BindingImmutabilityProof> proof) {
        Objects.requireNonNull(shape, "shape must not be null");
        Objects.requireNonNull(proof, "proof must not be null");
        return switch (shape) {
            case FUNCTION_EXPRESSION -> {
                requireNoProof(proof, shape);
                yield CaptureMode.VALUE;
            }
            case INTRINSIC_FUNCTION_VALUE -> CaptureMode.VALUE;
            case BINDING_REFERENCE -> proof.isPresent()
                ? CaptureMode.VALUE : CaptureMode.SHARED_CELL;
            case NON_IDENTIFIER_EXPRESSION -> {
                requireNoProof(proof, shape);
                yield CaptureMode.REEVALUATE_THUNK;
            }
        };
    }

    /** The proof-admissibility guard: VALUE's proof exists only for a binding source. */
    private static void requireNoProof(Optional<BindingImmutabilityProof> proof,
                                       SourceShape shape) {
        if (proof.isPresent()) {
            throw new IllegalArgumentException("the closed shape map records a "
                + "BindingImmutabilityProof only for a binding source; got a proof for "
                + shape + " (producer defect — the payload's proof is present iff "
                + "VALUE's operand is a binding load)");
        }
    }

    /**
     * The map's binding-load arm over the IR-level proof fact (the
     * produced-source surface): a proved load → VALUE, an unproven load
     * → SHARED_CELL — the same closed fallback arm as the checker-fact
     * surface, so the two surfaces never disagree.
     *
     * @param proof the recorded proof fact of the loaded binding (absent
     *              for an unproven binding); non-null
     * @return VALUE with the proof, SHARED_CELL without it
     */
    public static CaptureMode selectModeOverBindingLoad(
            Optional<BindingImmutabilityProof> proof) {
        Objects.requireNonNull(proof, "proof must not be null");
        return proof.isPresent() ? CaptureMode.VALUE : CaptureMode.SHARED_CELL;
    }

    /**
     * The map's produced-source arm at the IR level (the registry
     * child's T4 materialization seam feeds the import-read arm
     * through this surface): a {@code CLOSURE_NEW} result → VALUE (a
     * materialized function-expression operand); every composite
     * function-value producer — {@code MEMBER_READ}/{@code EXPORT_READ}
     * (member/import reads), {@code INDEX_READ}/{@code FIELD_READ}
     * (index/field reads), {@code CALL} (call results), and
     * {@code BRANCH} (conditionals) — → REEVALUATE_THUNK, regardless
     * of any recorded producer facts: a host/external import read is
     * the closed non-identifier member-read arm and there is no VALUE
     * carve-out for import reads. The producer facts name the
     * materialization site; they never reclassify the arm and never
     * select the mode.
     *
     * @param producingKind the source value's producing op kind (the
     *                      source ops the values/calls epics' producers
     *                      emit); non-null
     * @param producerFacts the registry child's materialization facts of
     *                      a host/external function-value source (absent
     *                      otherwise); non-null
     * @return exactly one closed {@link CaptureMode}
     * @throws IllegalArgumentException for a producing kind outside the
     *                                  closed function-value producer set
     *                                  (producer defect)
     */
    public static CaptureMode selectModeOverProducedSource(
            SemanticOpKind producingKind,
            Optional<FunctionBindingRegistry.FunctionValueMaterialization> producerFacts) {
        Objects.requireNonNull(producingKind, "producingKind must not be null");
        Objects.requireNonNull(producerFacts, "producerFacts must not be null");
        return switch (producingKind) {
            case CLOSURE_NEW -> CaptureMode.VALUE;
            case MEMBER_READ, EXPORT_READ, INDEX_READ, FIELD_READ, CALL, BINARY, BRANCH ->
                CaptureMode.REEVALUATE_THUNK;
            default -> throw new IllegalArgumentException("producing kind " + producingKind
                + " is not a closed function-value producing op of the produced-source "
                + "map (CLOSURE_NEW | MEMBER_READ | EXPORT_READ | INDEX_READ | "
                + "FIELD_READ | CALL | BINARY | BRANCH)");
        };
    }
}
