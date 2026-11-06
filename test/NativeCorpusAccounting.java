package deal.test;

import deal.semantic.ir.*;
import deal.test.conformance.SidecarExpectations;
import java.util.*;
import java.util.regex.Pattern;

/** Only verdict-checked O0/O2 cases contribute lowered facts or pinned failures. */
final class NativeCorpusAccounting {
    private final Set<SemanticOpKind> operations = EnumSet.noneOf(SemanticOpKind.class);
    private final Set<BoundaryKind> boundaries = EnumSet.noneOf(BoundaryKind.class);
    private final Set<StdlibFunctionId> stdlib = EnumSet.noneOf(StdlibFunctionId.class);
    private final Set<BinarySelector> binary = EnumSet.noneOf(BinarySelector.class);
    private final Set<UnarySelector> unary = EnumSet.noneOf(UnarySelector.class);
    private final Set<FailureArmId> arms = EnumSet.noneOf(FailureArmId.class);
    private final Set<BoundaryKind> runtimeBoundaries = EnumSet.noneOf(BoundaryKind.class);
    private final Set<FailureArmId> materializedArms = EnumSet.noneOf(FailureArmId.class);
    private int cases;

    /** Physical runtime services do not publish semantic BOUNDARY ops. */
    void runtimeBoundary(BoundaryKind boundary) { runtimeBoundaries.add(boundary); }

    /** Compare every field of a real C arm materialization with its independent registry tuple.
     * This is ABI contract coverage, never a claim that C loads JS tables. */
    void materializedArm(FailureArmId id, Map<String, String> parameters,
            String code, String message, String expected, String actual) {
        var tuple = FailureContractRegistry.render(id, parameters, expected, actual, null);
        if (!tuple.code().name().equals(code) || !tuple.message().equals(message)
                || !Objects.equals(tuple.expected(), expected) || !Objects.equals(tuple.actual(), actual))
            throw new AssertionError("native materialized tuple mismatch: " + id);
        // Only retained, source-inapplicable ABI contracts can use this channel.
        // Every ordinary failure arm still requires its case-local execution pin.
        if (id == FailureArmId.HOST_LOAD_RAW_SHAPE
                || id == FailureArmId.HOST_LOAD_NOT_A_MODULE) materializedArms.add(id);
    }

    void executed(ExecutableLoweredProject project, SidecarExpectations.ErrorExpectation pin) {
        cases++;
        for (var module : project.modules().values()) for (var op : module.ops()) {
            operations.add(op.kind());
            if (op.payload() instanceof KindPayload.BoundaryPayload boundary) boundaries.add(boundary.kind());
            if (op.payload() instanceof KindPayload.StdlibCallPayload call) stdlib.add(call.function());
            if (op.payload() instanceof KindPayload.BinaryPayload expression) binary.add(expression.selector());
            if (op.payload() instanceof KindPayload.UnaryPayload expression) unary.add(expression.selector());
        }
        recordFailure(pin);
    }

    private void recordFailure(SidecarExpectations.ErrorExpectation pin) {
        if (pin == null) return;
        Set<FailureArmId> matched = EnumSet.noneOf(FailureArmId.class);
        for (var arm : FailureContractRegistry.arms().values()) {
            if (!arm.isInnerOnly() && matches(arm, pin)) matched.add(arm.id());
        }
        arms.addAll(matched);
        if (matched.contains(FailureArmId.HOST_SYNC_RETURN_CELL)) {
            for (var arm : FailureContractRegistry.arms().values()) if (arm.isInnerOnly()) {
                // INNER_ONLY arms cannot render top-level tuples. Their fixed
                // strings must occur in this pinned consuming arm's own render.
                if (!arm.parameters().isEmpty()) continue;
                String inner = FailureContractRegistry.renderInner(arm.id(), Map.of());
                FailureContractRegistry.requireClosedHostInnerReason(inner);
                if (pin.code().equals(FailureContractRegistry.codeOf(
                        FailureContractRegistry.arm(FailureArmId.HOST_SYNC_RETURN_CELL)))
                        && pin.message().equals(FailureContractRegistry.arm(FailureArmId.HOST_SYNC_RETURN_CELL)
                            .template().replace("{inner}", inner))) arms.add(arm.id());
            }
        }
    }

    static boolean matches(FailureArm arm, SidecarExpectations.ErrorExpectation pin) {
        if (!pin.code().equals(FailureContractRegistry.codeOf(arm))) return false;
        String template = arm.template();
        var placeholders = Pattern.compile("\\{([^{}]+)}").matcher(template);
        StringBuilder expression = new StringBuilder("\\A");
        List<String> names = new ArrayList<>();
        int end = 0;
        while (placeholders.find()) {
            expression.append(Pattern.quote(template.substring(end, placeholders.start()))).append("(.*?)");
            names.add(placeholders.group(1)); end = placeholders.end();
        }
        expression.append(Pattern.quote(template.substring(end))).append("\\z");
        var matched = Pattern.compile(expression.toString(), Pattern.DOTALL).matcher(pin.message());
        if (!matched.matches()) return false;
        Map<String, String> parameters = new LinkedHashMap<>();
        for (int index = 0; index < names.size(); index++) {
            String previous = parameters.put(names.get(index), matched.group(index + 1));
            if (previous != null && !previous.equals(matched.group(index + 1))) return false;
        }
        if (!parameters.keySet().equals(new LinkedHashSet<>(arm.parameters()))) return false;
        // Full tuple validation uses the registry's parameter/field/inner-reason
        // predicates, not a permissive regex as an independent failure oracle.
        try {
            return FailureContractRegistry.render(arm.id(), parameters,
                pin.expected().orElse(null), pin.actual().orElse(null), null).message().equals(pin.message());
        } catch (RuntimeException unpinnedOrInvalidTuple) { return false; }
    }

    static void controls() {
        FailureArm arm = FailureContractRegistry.arm(FailureArmId.HOST_PARAMETER_CELL);
        String inner = FailureContractRegistry.descriptorKindReason("number");
        BoundaryFailure failure = FailureContractRegistry.render(arm.id(),
            Map.of("index", "1", "inner", inner), "number", "string", null);
        var valid = new SidecarExpectations.ErrorExpectation(failure.code().name(), failure.message(), null, null, null,
            Optional.of("number"), Optional.of("string"), Optional.empty(), Optional.empty());
        if (!matches(arm, valid)) throw new AssertionError("accounting refused an independently rendered pin");
        var foreign = new SidecarExpectations.ErrorExpectation(valid.code(), valid.message().replace(inner, "invented reason"),
            null, null, null, valid.expected(), valid.actual(), valid.frames(), valid.cause());
        if (matches(arm, foreign)) throw new AssertionError("accounting accepted foreign inner text");
        var missing = new SidecarExpectations.ErrorExpectation(valid.code(), valid.message(), null, null, null,
            Optional.empty(), valid.actual(), valid.frames(), valid.cause());
        if (matches(arm, missing)) throw new AssertionError("accounting accepted an unpinned arm field");
        if (matches(FailureContractRegistry.arm(FailureArmId.HOST_SYNC_RETURN_CELL), valid))
            throw new AssertionError("accounting accepted another arm's template");
        var accounting = new NativeCorpusAccounting();
        BoundaryFailure returned = FailureContractRegistry.render(FailureArmId.HOST_SYNC_RETURN_CELL,
            Map.of("inner", inner), "number", "string", null);
        accounting.recordFailure(new SidecarExpectations.ErrorExpectation(returned.code().name(), returned.message(),
            null, null, null, Optional.of("number"), Optional.of("string"), Optional.empty(), Optional.empty()));
        if (!accounting.arms.equals(EnumSet.of(FailureArmId.HOST_SYNC_RETURN_CELL)))
            throw new AssertionError("accounting failed to credit a valid return pin");
        String unicode = FailureContractRegistry.renderInner(FailureArmId.HOST_STRING_SURROGATE, Map.of());
        BoundaryFailure malformed = FailureContractRegistry.render(FailureArmId.HOST_SYNC_RETURN_CELL,
            Map.of("inner", unicode), "string", "string", null);
        var unpinned = new SidecarExpectations.ErrorExpectation(malformed.code().name(), malformed.message(),
            null, null, null, Optional.empty(), Optional.of("string"), Optional.empty(), Optional.empty());
        accounting.recordFailure(unpinned);
        if (accounting.arms.contains(FailureArmId.HOST_STRING_SURROGATE))
            throw new AssertionError("an earlier verdict credited an unpinned inner failure");
        accounting.recordFailure(new SidecarExpectations.ErrorExpectation(unpinned.code(), unpinned.message(),
            null, null, null, Optional.of("string"), unpinned.actual(), unpinned.frames(), unpinned.cause()));
        if (!accounting.arms.contains(FailureArmId.HOST_STRING_SURROGATE))
            throw new AssertionError("accounting refused a fully pinned inner failure");
        accounting.materializedArm(FailureArmId.HOST_PARAMETER_CELL,
            Map.of("index", "1", "inner", inner), failure.code().name(), failure.message(), "number", "string");
        if (accounting.materializedArms.contains(FailureArmId.HOST_PARAMETER_CELL))
            throw new AssertionError("ABI materialization substituted for a source failure-site pin");
    }

    void verify(NativeKnownSkips skips) {
        Set<SemanticOpKind> missingOps = EnumSet.allOf(SemanticOpKind.class); missingOps.removeAll(operations);
        Set<BoundaryKind> missingBoundaries = EnumSet.allOf(BoundaryKind.class);
        missingBoundaries.removeAll(boundaries); missingBoundaries.removeAll(runtimeBoundaries);
        Set<StdlibFunctionId> missingStdlib = EnumSet.allOf(StdlibFunctionId.class); missingStdlib.removeAll(stdlib);
        Set<BinarySelector> missingBinary = EnumSet.allOf(BinarySelector.class); missingBinary.removeAll(binary);
        Set<UnarySelector> missingUnary = EnumSet.allOf(UnarySelector.class); missingUnary.removeAll(unary);
        Set<FailureArmId> missingArms = EnumSet.allOf(FailureArmId.class);
        missingArms.removeAll(arms); missingArms.removeAll(materializedArms);
        System.out.println("Native accounting: " + cases + " verdict-checked cases; ops=" + operations.size()
            + "/" + SemanticOpKind.values().length + ", boundaries=" + boundaries.size() + "/" + BoundaryKind.values().length
            + ", stdlib=" + stdlib.size() + "/" + StdlibFunctionId.values().length
            + ", failure-site arms=" + arms.size() + "/" + FailureArmId.values().length);
        System.out.println("Native runtime contracts: boundaries=" + runtimeBoundaries
            + "; materialized ABI arms=" + materializedArms + "; exclusions=[]");
        System.out.println("Native accounting evidenced arms: " + arms);
        System.out.println("Native accounting missing: ops=" + missingOps + "; boundaries=" + missingBoundaries
            + "; stdlib=" + missingStdlib + "; binary=" + missingBinary + "; unary=" + missingUnary + "; arms=" + missingArms);
        System.out.println("Native accounting selectors: binary=" + binary + "; unary=" + unary);
        Set<SemanticOpKind> unexpectedOps = skips.remaining("operation", missingOps);
        Set<BoundaryKind> unexpectedBoundaries = skips.remaining("boundary", missingBoundaries);
        if (cases == 0 || !unexpectedOps.isEmpty() || !unexpectedBoundaries.isEmpty()
                || !missingStdlib.isEmpty() || !missingBinary.isEmpty() || !missingUnary.isEmpty() || !missingArms.isEmpty())
            throw new AssertionError("HARNESS_DEFECT: unevidenced native runtime requirements");
    }
}
