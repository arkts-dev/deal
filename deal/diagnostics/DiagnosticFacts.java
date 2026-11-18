package deal.diagnostics;

import java.util.List;
import java.util.Objects;

/** Producer-owned repair facts. Empty values mean unavailable, never inferred from prose. */
public record DiagnosticFacts(
        String reason,
        String operation,
        String expected,
        String actual,
        List<String> guidance,
        List<PathSegment> path,
        CallContext call,
        OperatorContext operator) {
    /** Operand roles are producer-selected; ranges identify checked expressions, not guessed fixes. */
    public record Operand(String role, String type, DiagnosticRange range) {
        public Operand {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(range, "range");
        }
    }
    public record OperatorContext(String token, List<Operand> operands, List<String> acceptedCombinations) {
        public OperatorContext {
            Objects.requireNonNull(token, "token");
            operands = List.copyOf(operands);
            acceptedCombinations = List.copyOf(acceptedCombinations);
        }
    }
    /** Argument indices are zero-based; -1 means the failure concerns the whole call. */
    public record CallContext(String callee, String signature, DiagnosticRange declarationRange,
                              int argumentIndex, String parameterName, int expectedCount,
                              int actualCount, DiagnosticRange parameterRange) {
        public CallContext {
            Objects.requireNonNull(callee, "callee");
            Objects.requireNonNull(signature, "signature");
            Objects.requireNonNull(parameterName, "parameterName");
            if (argumentIndex < -1 || expectedCount < 0 || actualCount < 0 || argumentIndex >= actualCount)
                throw new IllegalArgumentException("Invalid call diagnostic geometry");
        }
    }
    public enum PathKind { FIELD, ARRAY_ELEMENT }

    /** Declared-field/schema routes describe types, not a guessed runtime array index. */
    public record PathSegment(PathKind kind, String name) {
        public PathSegment {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(name, "name");
            if (kind == PathKind.ARRAY_ELEMENT && !name.isEmpty())
                throw new IllegalArgumentException("Schema array elements have no concrete index");
        }
    }

    public DiagnosticFacts(String reason, String operation, String expected, String actual, List<String> guidance) {
        this(reason, operation, expected, actual, guidance, List.of(), null);
    }

    public DiagnosticFacts(String reason, String operation, String expected, String actual,
                           List<String> guidance, List<PathSegment> path) {
        this(reason, operation, expected, actual, guidance, path, null);
    }

    public DiagnosticFacts(String reason, String operation, String expected, String actual,
                           List<String> guidance, List<PathSegment> path, CallContext call) {
        this(reason, operation, expected, actual, guidance, path, call, null);
    }

    public DiagnosticFacts withCall(CallContext context) {
        return new DiagnosticFacts(reason, operation, expected, actual, guidance, path, context, operator);
    }

    public DiagnosticFacts withOperator(OperatorContext context) {
        return new DiagnosticFacts(reason, operation, expected, actual, guidance, path, call, context);
    }

    public static final DiagnosticFacts EMPTY = new DiagnosticFacts("", "", "", "", List.of());

    public DiagnosticFacts {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        guidance = List.copyOf(guidance);
        path = List.copyOf(path);
    }
}
