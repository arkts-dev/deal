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
        OperatorContext operator,
        TypeMismatch mismatch,
        SyntaxContext syntax,
        NameContext names,
        ConstructionContext construction,
        ExportContext exports,
        ResolutionContext resolution,
        CycleContext cycle,
        ToolContext tool,
        LoweringContext lowering) {
    /** Already classified common-lowering failure, not reconstructed from its message. */
    public record LoweringContext(String module, String capability, String validatorRule,
                                   String semanticProfile, String irVersion, String origin) {
        public LoweringContext {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(validatorRule, "validatorRule");
            Objects.requireNonNull(semanticProfile, "semanticProfile");
            Objects.requireNonNull(irVersion, "irVersion");
            Objects.requireNonNull(origin, "origin");
        }
    }

    /** A bounded UTF-8-decoded prefix from one drained child stream, with exact observed bytes. */
    public record ToolOutput(String text, long byteCount, int retainedByteCount, boolean truncated) {
        public ToolOutput {
            Objects.requireNonNull(text, "text");
            if (retainedByteCount < 0 || byteCount < retainedByteCount || truncated != (byteCount > retainedByteCount))
                throw new IllegalArgumentException("Invalid tool output count");
        }
    }

    public enum ToolOutcome { EXITED, START_FAILED, INTERRUPTED, TIMED_OUT }

    /** Only observed process outcomes; no guessed POSIX signal from an exit code. */
    public record ToolContext(String operation, String executable, Integer exitCode,
                              ToolOutcome outcome, long deadlineSeconds, ToolOutput stdout, ToolOutput stderr) {
        public ToolContext {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(executable, "executable");
            Objects.requireNonNull(outcome, "outcome");
            if (deadlineSeconds < 0 || (outcome == ToolOutcome.EXITED != (exitCode != null)))
                throw new IllegalArgumentException("Invalid tool outcome");
        }
    }

    /** One concrete closed import witness, not an inferred source rewrite. */
    public record CycleEdge(String from, String to, DiagnosticRange importRange) {
        public CycleEdge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }
    public record CycleContext(List<CycleEdge> edges, int edgeCount, boolean truncated) {
        public CycleContext {
            edges = List.copyOf(edges);
            if (edgeCount < edges.size() || truncated != (edgeCount > edges.size()))
                throw new IllegalArgumentException("Invalid cycle edge count");
            for (int i = 1; i < edges.size(); i++)
                if (!edges.get(i - 1).to().equals(edges.get(i).from()))
                    throw new IllegalArgumentException("Disconnected cycle witness");
            if (!truncated && !edges.isEmpty() && !edges.getLast().to().equals(edges.getFirst().from()))
                throw new IllegalArgumentException("Unclosed cycle witness");
        }
    }

    /** Candidate paths actually visited, in search order; no filesystem re-probing. */
    public record ResolutionContext(String specifier, String mode, List<String> attempted,
                                    int attemptedCount, boolean truncated) {
        public ResolutionContext {
            Objects.requireNonNull(specifier, "specifier");
            Objects.requireNonNull(mode, "mode");
            attempted = List.copyOf(attempted);
            if (attemptedCount < attempted.size() || truncated != (attemptedCount > attempted.size()))
                throw new IllegalArgumentException("Invalid resolution attempt count");
        }
    }

    /** Observed export names need not be suitable replacements for the rejected use. */
    public record ExportContext(String alias, String requested, List<String> available,
                                int availableCount, boolean truncated, DiagnosticRange importRange) {
        public ExportContext {
            Objects.requireNonNull(alias, "alias");
            Objects.requireNonNull(requested, "requested");
            available = List.copyOf(available);
            if (availableCount < available.size() || truncated != (availableCount > available.size()))
                throw new IllegalArgumentException("Invalid export count");
        }
    }

    /** Selected construction provider; null optionality means no selected declared field. */
    public record ConstructionContext(String className, String provider, DiagnosticRange declarationRange,
                                      String fieldName, Boolean optional, DiagnosticRange fieldRange,
                                      DiagnosticRange annotationRange) {
        public ConstructionContext {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(fieldName, "fieldName");
        }
    }
    /** Alternatives are observed eligible bindings, not authorized edits or inferred imports. */
    public record NameCandidate(String name, String kind, String type, DiagnosticRange declarationRange,
                                int distance, int scopeDepth) {
        public NameCandidate {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(type, "type");
            if (distance < 0 || scopeDepth < 0)
                throw new IllegalArgumentException("Invalid name candidate rank");
        }
    }

    public enum LookupStatus { NOT_FOUND_IN_LEXICAL_OR_IMPORTED_SURFACES, DECLARED_LATER, OUT_OF_SCOPE, IMPORTED_MEMBER, WRONG_NAMESPACE }
    public record NameObservation(String name, String kind, LookupStatus status, DiagnosticRange declarationRange) {
        public NameObservation {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(status, "status");
        }
    }
    public record NameContext(List<NameCandidate> candidates, int matchingCount, boolean truncated,
                              List<NameObservation> observations, int observationCount) {
        public NameContext(List<NameCandidate> candidates, int matchingCount, boolean truncated) {
            this(candidates, matchingCount, truncated, List.of(), 0);
        }
        public NameContext {
            candidates = List.copyOf(candidates);
            observations = List.copyOf(observations);
            if (observationCount < observations.size()) throw new IllegalArgumentException("Invalid lookup observation count");
            if (matchingCount < candidates.size() || truncated != (matchingCount > candidates.size()))
                throw new IllegalArgumentException("Invalid name candidate count");
        }
    }

    /** Token spelling is a bounded raw-lexeme prefix in UTF-16 units, escaped only by transport. */
    /** Parser-owned insertion only; no inferred expression, declaration or semantic repair. */
    public record SyntaxInsertion(int utf16Offset, String text) {
        public SyntaxInsertion {
            if (utf16Offset < 0 || !";".equals(text)) throw new IllegalArgumentException("Unsupported syntax insertion");
        }
    }

    public record SyntaxContext(String context, List<String> expectedKinds, String foundKind,
                                String lexeme, int lexemeLength, boolean truncated, SyntaxInsertion insertion) {
        public SyntaxContext(String context, List<String> expectedKinds, String foundKind,
                             String lexeme, int lexemeLength, boolean truncated) {
            this(context, expectedKinds, foundKind, lexeme, lexemeLength, truncated, null);
        }
        public SyntaxContext withInsertion(SyntaxInsertion value) {
            return new SyntaxContext(context, expectedKinds, foundKind, lexeme, lexemeLength, truncated, value);
        }
        public SyntaxContext {
            Objects.requireNonNull(context, "context");
            expectedKinds = List.copyOf(expectedKinds);
            Objects.requireNonNull(foundKind, "foundKind");
            Objects.requireNonNull(lexeme, "lexeme");
            if (lexemeLength < lexeme.length() || truncated != (lexemeLength > lexeme.length()))
                throw new IllegalArgumentException("Invalid syntax lexeme geometry");
        }
    }

    public enum TypeComponentKind { ARRAY_ELEMENT, NULLABLE_INNER, FUNCTION_RESULT, FUNCTION_PARAMETER }

    /** Only parameter components carry a zero-based index; other components use -1. */
    public record TypeComponent(TypeComponentKind kind, int index) {
        public TypeComponent {
            Objects.requireNonNull(kind, "kind");
            if (kind == TypeComponentKind.FUNCTION_PARAMETER ? index < 0 : index != -1)
                throw new IllegalArgumentException("Invalid type component index");
        }
    }

    /** A bounded route through type constraints, independent of field/schema and runtime paths. */
    public record TypeMismatch(String rule, List<TypeComponent> path, String expected,
                               String actual, boolean truncated) {
        public TypeMismatch {
            Objects.requireNonNull(rule, "rule");
            path = List.copyOf(path);
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(actual, "actual");
        }
    }
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

    public DiagnosticFacts(String reason, String operation, String expected, String actual,
                           List<String> guidance, List<PathSegment> path, CallContext call,
                           OperatorContext operator) {
        this(reason, operation, expected, actual, guidance, path, call, operator, null);
    }

    public DiagnosticFacts(String reason, String operation, String expected, String actual,
                           List<String> guidance, List<PathSegment> path, CallContext call,
                           OperatorContext operator, TypeMismatch mismatch) {
        this(reason, operation, expected, actual, guidance, path, call, operator, mismatch, null);
    }

    public DiagnosticFacts(String reason, String operation, String expected, String actual,
                           List<String> guidance, List<PathSegment> path, CallContext call,
                           OperatorContext operator, TypeMismatch mismatch, SyntaxContext syntax) {
        this(reason, operation, expected, actual, guidance, path, call, operator, mismatch, syntax, null);
    }

    public DiagnosticFacts(String reason, String operation, String expected, String actual,
                           List<String> guidance, List<PathSegment> path, CallContext call,
                           OperatorContext operator, TypeMismatch mismatch, SyntaxContext syntax, NameContext names) {
        this(reason, operation, expected, actual, guidance, path, call, operator, mismatch, syntax, names, null);
    }

    public DiagnosticFacts(String reason, String operation, String expected, String actual,
                           List<String> guidance, List<PathSegment> path, CallContext call,
                           OperatorContext operator, TypeMismatch mismatch, SyntaxContext syntax,
                           NameContext names, ConstructionContext construction) {
        this(reason, operation, expected, actual, guidance, path, call, operator, mismatch, syntax, names, construction, null);
    }

    public DiagnosticFacts(String reason, String operation, String expected, String actual,
                           List<String> guidance, List<PathSegment> path, CallContext call,
                           OperatorContext operator, TypeMismatch mismatch, SyntaxContext syntax,
                           NameContext names, ConstructionContext construction, ExportContext exports) {
        this(reason, operation, expected, actual, guidance, path, call, operator, mismatch, syntax, names, construction, exports, null);
    }

    public DiagnosticFacts(String reason, String operation, String expected, String actual,
                           List<String> guidance, List<PathSegment> path, CallContext call,
                           OperatorContext operator, TypeMismatch mismatch, SyntaxContext syntax,
                           NameContext names, ConstructionContext construction, ExportContext exports,
                           ResolutionContext resolution) {
        this(reason, operation, expected, actual, guidance, path, call, operator, mismatch, syntax, names,
                construction, exports, resolution, null);
    }

    public DiagnosticFacts(String reason, String operation, String expected, String actual,
                           List<String> guidance, List<PathSegment> path, CallContext call,
                           OperatorContext operator, TypeMismatch mismatch, SyntaxContext syntax,
                           NameContext names, ConstructionContext construction, ExportContext exports,
                           ResolutionContext resolution, CycleContext cycle) {
        this(reason, operation, expected, actual, guidance, path, call, operator, mismatch, syntax, names,
                construction, exports, resolution, cycle, null);
    }

    public DiagnosticFacts(String reason, String operation, String expected, String actual,
                           List<String> guidance, List<PathSegment> path, CallContext call,
                           OperatorContext operator, TypeMismatch mismatch, SyntaxContext syntax,
                           NameContext names, ConstructionContext construction, ExportContext exports,
                           ResolutionContext resolution, CycleContext cycle, ToolContext tool) {
        this(reason, operation, expected, actual, guidance, path, call, operator, mismatch, syntax, names,
                construction, exports, resolution, cycle, tool, null);
    }

    public DiagnosticFacts withGuidance(List<String> value) {
        return new DiagnosticFacts(reason, operation, expected, actual, value, path, call, operator, mismatch, syntax, names, construction, exports, resolution, cycle, tool, lowering);
    }

    public DiagnosticFacts withLowering(LoweringContext context) {
        return new DiagnosticFacts(reason, operation, expected, actual, guidance, path, call, operator, mismatch, syntax, names, construction, exports, resolution, cycle, tool, context);
    }

    public DiagnosticFacts withTool(ToolContext context) {
        return new DiagnosticFacts(reason, operation, expected, actual, guidance, path, call, operator, mismatch, syntax, names, construction, exports, resolution, cycle, context, lowering);
    }

    public DiagnosticFacts withCycle(CycleContext context) {
        return new DiagnosticFacts(reason, operation, expected, actual, guidance, path, call, operator, mismatch, syntax, names, construction, exports, resolution, context, tool, lowering);
    }

    public DiagnosticFacts withResolution(ResolutionContext context) {
        return new DiagnosticFacts(reason, operation, expected, actual, guidance, path, call, operator, mismatch, syntax, names, construction, exports, context, cycle, tool, lowering);
    }

    public DiagnosticFacts withExports(ExportContext context) {
        return new DiagnosticFacts(reason, operation, expected, actual, guidance, path, call, operator, mismatch, syntax, names, construction, context, resolution, cycle, tool, lowering);
    }

    public DiagnosticFacts withConstruction(ConstructionContext context) {
        return new DiagnosticFacts(reason, operation, expected, actual, guidance, path, call, operator, mismatch, syntax, names, context, exports, resolution, cycle, tool, lowering);
    }

    public DiagnosticFacts withNames(NameContext context) {
        return new DiagnosticFacts(reason, operation, expected, actual, guidance, path, call, operator, mismatch, syntax, context, construction, exports, resolution, cycle, tool, lowering);
    }

    public DiagnosticFacts withCall(CallContext context) {
        return new DiagnosticFacts(reason, operation, expected, actual, guidance, path, context, operator, mismatch, syntax, names, construction, exports, resolution, cycle, tool, lowering);
    }

    public DiagnosticFacts withOperator(OperatorContext context) {
        return new DiagnosticFacts(reason, operation, expected, actual, guidance, path, call, context, mismatch, syntax, names, construction, exports, resolution, cycle, tool, lowering);
    }

    public DiagnosticFacts withMismatch(TypeMismatch context) {
        return new DiagnosticFacts(reason, operation, expected, actual, guidance, path, call, operator, context, syntax, names, construction, exports, resolution, cycle, tool, lowering);
    }

    public DiagnosticFacts withSyntax(SyntaxContext context) {
        return new DiagnosticFacts(reason, operation, expected, actual, guidance, path, call, operator, mismatch, context, names, construction, exports, resolution, cycle, tool, lowering);
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
