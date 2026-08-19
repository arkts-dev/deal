package deal.ast;

import java.util.List;

/**
 * {@code (p1: T1, ..., pN: TN) => R}
 * Function type annotation with parameters and return type.
 *
 * <p>DEAL v1.2: function types carry no rest parameter arm.</p>
 */
public record FunctionType(
    Span span,
    List<FunctionTypeParam> params,
    TypeNode returnType,
    boolean isAsync
) implements TypeNode {}
