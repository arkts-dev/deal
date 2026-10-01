package deal.test.conformance;

import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.OpId;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The lane-level dangling-identity guard (ISSUE-0694; design source
 * {@code conformance-cutover-guards-pins-and-production-source-removal}
 * HR4(b) and the lane project identity audit contract;
 * {@code luajit-jvm-single-lowering-production-cutover} C10 and
 * {@code semantic-ir-construct-coverage-cutover} K12).
 *
 * <p>Every lowered project a lane produces is audited here before the lane
 * executes its artifact: for every {@code RETURN} op of every unit of the
 * project closure, the {@code enclosingInvocationOpId} must resolve to an
 * emitted op of the project closure and the named return boundary must
 * resolve to an emitted op of the project closure; a lowered body names
 * exactly one return boundary, and its identity owner is the creation op of
 * the very body when no statically materialized invocation took the reserved
 * identity over. An op identity is materialized exactly once in the
 * closure.</p>
 *
 * <p>The validator's closed body-local return cell rules — the
 * {@code FUNCTION_RETURN} kind, the parent {@code RETURN} of the returning
 * body, and the creation-op ownership —
 * are asserted exactly where the closed table scopes them: on the
 * <em>body-local cell</em>, the {@code RETURN} whose identity is the body's
 * own function-value creation op ({@code CLOSURE_NEW} or
 * {@code RECURSIVE_GROUP_INIT}, {@code semantic-ir-construct-coverage-cutover}
 * K12). A {@code RETURN} of any other shape names the boundary of its
 * enclosing invocation — a static {@code CALL}'s or an {@code ASYNC_START}'s
 * {@code FUNCTION_RETURN}, a sync {@code EXTERNAL_ENTRY}'s
 * {@code EXTERNAL_RETURN} (an async one's {@code FUNCTION_RETURN}) — which
 * the closed table does not require to be parented to the {@code RETURN},
 * so for those shapes only the resolution of the identity and of the named
 * boundary is asserted. Scoping the cell rules this way keeps the guard
 * exact: it accepts every validator-approved project shape while still
 * failing a never-materialized reserved identity by name.</p>
 *
 * <p>The guard reads the produced project the lane's own compile produced —
 * the orchestrator's read-only {@code producedProject()} reference — and
 * never a re-lowering, so it observes exactly the object the lane emits and
 * executes. It is deliberately independent of the validator run inside the
 * production arm: a regression that restores a never-called guard (so a
 * never-invoked body's reserved identity is never materialized) or leaves a
 * reserved identity dangling fails this audit by name — the module path, the
 * function, the unresolved op id, and the rule — instead of surfacing as an
 * artifact defect or a mis-executed body.</p>
 *
 * <p>The guard is read-only: it stages nothing, executes nothing, and
 * mutates nothing. A project that the production arm's validator rejected
 * never reaches this guard (a failed run carries no produced project).</p>
 */
public final class ProducedProjectIdentityAudit {

    /** The rule: the {@code RETURN}'s enclosing invocation is not emitted. */
    public static final String ENCLOSING_INVOCATION_NOT_EMITTED =
        "ENCLOSING_INVOCATION_NOT_EMITTED";

    /** The rule: the enclosing invocation identity is emitted more than once. */
    public static final String ENCLOSING_INVOCATION_DUPLICATED =
        "ENCLOSING_INVOCATION_DUPLICATED";

    /** The rule: the named return boundary is not emitted. */
    public static final String RETURN_BOUNDARY_NOT_EMITTED =
        "RETURN_BOUNDARY_NOT_EMITTED";

    /**
     * The rule: the body-local cell's named return boundary is not a
     * {@code FUNCTION_RETURN} boundary.
     */
    public static final String RETURN_BOUNDARY_NOT_FUNCTION_RETURN =
        "RETURN_BOUNDARY_NOT_FUNCTION_RETURN";

    /**
     * The rule: the body-local cell's return boundary is not parented to a
     * {@code RETURN} of the returning body that names it.
     */
    public static final String RETURN_BOUNDARY_PARENT_MISMATCH =
        "RETURN_BOUNDARY_PARENT_MISMATCH";

    /** The rule: a lowered body names more than one return boundary. */
    public static final String RETURN_BOUNDARY_NOT_SINGLE =
        "RETURN_BOUNDARY_NOT_SINGLE";

    /** The rule: the body-local identity is not the returning body's creation op. */
    public static final String BODY_LOCAL_IDENTITY_OWNER_MISMATCH =
        "BODY_LOCAL_IDENTITY_OWNER_MISMATCH";

    private ProducedProjectIdentityAudit() {
        // Static audit surface only; no instances.
    }

    /**
     * One named violation of the identity audit: the module path, the
     * lowered function, the op id the assertion is about (the unresolved
     * enclosing identity or return boundary, or the mis-owned identity),
     * and the closed rule identifier.
     *
     * @param modulePath the module path of the unit that carries the
     *                   {@code RETURN}; non-null
     * @param function   the {@code RETURN}'s function identity; non-null
     * @param opId       the offending op identity; non-null
     * @param rule       the closed rule identifier; non-null
     */
    public record Violation(String modulePath, String function, String opId,
                            String rule) {

        public Violation {
            Objects.requireNonNull(modulePath, "modulePath must not be null");
            Objects.requireNonNull(function, "function must not be null");
            Objects.requireNonNull(opId, "opId must not be null");
            Objects.requireNonNull(rule, "rule must not be null");
        }

        /** The nameable failure text: module path, function, op id, and rule. */
        public String describe() {
            return "module " + modulePath + ", function " + function
                + ", op " + opId + ": " + rule;
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /**
     * Audits one produced project and returns every violation in unit
     * (dependency) order and op order; an empty list is the passing
     * verdict.
     *
     * @param project the project the lane's compile produced; non-null
     * @return the violations, empty exactly when the audit passes
     */
    public static List<Violation> audit(ExecutableLoweredProject project) {
        Objects.requireNonNull(project, "project must not be null");
        List<Violation> violations = new ArrayList<>();
        Closure closure = Closure.of(project);
        for (LoweredModuleUnit unit : project.modules().values()) {
            auditUnit(unit, closure, violations);
        }
        return List.copyOf(violations);
    }

    /**
     * The first violation of {@link #audit(ExecutableLoweredProject)}, or
     * empty when the audit passes.
     *
     * @param project the project the lane's compile produced; non-null
     * @return the first violation, or empty
     */
    public static Optional<Violation> firstViolation(ExecutableLoweredProject project) {
        List<Violation> violations = audit(project);
        return violations.isEmpty() ? Optional.empty()
            : Optional.of(violations.get(0));
    }

    private static void auditUnit(LoweredModuleUnit unit, Closure closure,
            List<Violation> violations) {
        String modulePath = unit.moduleId().path();
        Map<FunctionId, OpId> bodyBoundaries = new LinkedHashMap<>();
        for (SemanticOp returned : unit.ops()) {
            if (returned.kind() != SemanticOpKind.RETURN
                    || !(returned.payload()
                        instanceof KindPayload.ReturnPayload payload)) {
                continue;
            }
            String function = payload.function().toString();
            OpId enclosing = payload.enclosingInvocationOpId();
            SemanticOp identity = closure.byId.get(enclosing);
            if (identity == null) {
                violations.add(new Violation(modulePath, function,
                    enclosing.toString(), ENCLOSING_INVOCATION_NOT_EMITTED));
            } else if (closure.emitted.getOrDefault(enclosing, 0) > 1) {
                violations.add(new Violation(modulePath, function,
                    enclosing.toString(), ENCLOSING_INVOCATION_DUPLICATED));
            }

            OpId boundaryId = payload.returnBoundaryOpId();
            SemanticOp boundary = closure.byId.get(boundaryId);
            if (boundary == null) {
                violations.add(new Violation(modulePath, function,
                    boundaryId.toString(), RETURN_BOUNDARY_NOT_EMITTED));
            }

            // The validator's closed body-local return cell
            // (SemanticIrValidator.bodyLocalReturnCell): the cell's rules
            // apply to a RETURN whose enclosing invocation identity is the
            // body's own creation op, never to a RETURN of another
            // invocation shape.
            boolean bodyLocal = identity != null
                && (identity.kind() == SemanticOpKind.CLOSURE_NEW
                    || identity.kind() == SemanticOpKind.RECURSIVE_GROUP_INIT);
            if (bodyLocal && boundary != null) {
                if (boundary.kind() != SemanticOpKind.BOUNDARY
                        || !(boundary.payload()
                            instanceof KindPayload.BoundaryPayload boundaryPayload)
                        || boundaryPayload.kind() != BoundaryKind.FUNCTION_RETURN) {
                    violations.add(new Violation(modulePath, function,
                        boundaryId.toString(), RETURN_BOUNDARY_NOT_FUNCTION_RETURN));
                } else if (!isBodyReturnBoundary(boundary, boundaryId,
                        payload.function(), closure)) {
                    violations.add(new Violation(modulePath, function,
                        boundaryId.toString(), RETURN_BOUNDARY_PARENT_MISMATCH));
                }
            }
            if (bodyLocal) {
                if (identity.kind() == SemanticOpKind.CLOSURE_NEW) {
                    if (!(identity.payload()
                            instanceof KindPayload.ClosureNewPayload closureNew)
                            || !closureNew.function().equals(payload.function())) {
                        violations.add(new Violation(modulePath, function,
                            enclosing.toString(),
                            BODY_LOCAL_IDENTITY_OWNER_MISMATCH));
                    }
                } else if (!(identity.payload()
                        instanceof KindPayload.RecursiveGroupInitPayload group)
                        || !group.functions().contains(payload.function())) {
                    violations.add(new Violation(modulePath, function,
                        enclosing.toString(),
                        BODY_LOCAL_IDENTITY_OWNER_MISMATCH));
                }
            }

            if (!unit.functions().containsKey(payload.function())) {
                // A recorded cell parented to an unattached RETURN (the
                // call-owned dynamic record shape): its boundary and
                // identity resolution are asserted above; the lowered-body
                // clauses do not apply.
                continue;
            }
            OpId previousBoundary = bodyBoundaries.putIfAbsent(payload.function(),
                boundaryId);
            if (previousBoundary != null && !previousBoundary.equals(boundaryId)) {
                violations.add(new Violation(modulePath, function,
                    boundaryId.toString(), RETURN_BOUNDARY_NOT_SINGLE));
            }
        }
    }

    /**
     * The closed body-local cell's boundary rule
     * ({@code SemanticIrValidator.bodyLocalReturnCell}): the boundary is
     * parented to a {@code RETURN} of the returning body, and that
     * {@code RETURN} names the boundary as its
     * {@code returnBoundaryOpId}. A body emits one return boundary and may
     * carry more than one {@code RETURN}; every one of them names the same
     * boundary, while only the emitter's first one is its parent, so the
     * rule is asserted against the parent {@code RETURN}, never against the
     * {@code RETURN} the audit is currently walking.
     *
     * @param boundary   the emitted boundary op; non-null
     * @param boundaryId the boundary's op identity; non-null
     * @param function   the returning body's function identity; non-null
     * @param closure    the project closure index; non-null
     * @return true when the parent {@code RETURN} of the function names this
     *         boundary
     */
    private static boolean isBodyReturnBoundary(SemanticOp boundary,
            OpId boundaryId, FunctionId function, Closure closure) {
        OpId parentId = boundary.origin().parentOpId();
        if (parentId == null) {
            return false;
        }
        SemanticOp parent = closure.byId.get(parentId);
        return parent != null
            && parent.kind() == SemanticOpKind.RETURN
            && parent.payload() instanceof KindPayload.ReturnPayload parentPayload
            && parentPayload.function().equals(function)
            && boundaryId.equals(parentPayload.returnBoundaryOpId());
    }

    /**
     * The project closure index the audit resolves against: the first op of
     * every emitted op identity and the identity's occurrence count over
     * the whole closure (an identity is materialized exactly once).
     */
    private static final class Closure {

        private final Map<OpId, SemanticOp> byId = new LinkedHashMap<>();
        private final Map<OpId, Integer> emitted = new LinkedHashMap<>();

        private static Closure of(ExecutableLoweredProject project) {
            Closure closure = new Closure();
            for (LoweredModuleUnit unit : project.modules().values()) {
                for (SemanticOp op : unit.ops()) {
                    closure.byId.putIfAbsent(op.opId(), op);
                    closure.emitted.merge(op.opId(), 1, Integer::sum);
                }
            }
            return closure;
        }
    }
}
