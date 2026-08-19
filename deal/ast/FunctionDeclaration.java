package deal.ast;

import java.util.List;

/**
 * {@code function name(p1: T1, ..., pN: TN): R { body }}.
 *
 * <p>DEAL v1.2: no rest parameters.  An {@code isExternal} declaration ends
 * with {@code ;} and has no body; external function declarations are only
 * valid in {@code .d.deal} declaration files (enforced after parsing).</p>
 */
public record FunctionDeclaration(
    Span span,
    String name,
    List<Parameter> params,
    TypeNode returnType,
    Block body,
    boolean isAsync,
    boolean isExternal
) implements StatementNode {}
