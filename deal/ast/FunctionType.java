package deal.ast;

import java.util.List;
import java.util.Optional;

/**
 * (p1: T1, ..., pN: TN, ...rest: T[]) => R
 * Function type annotation with parameters, optional rest param, and return type.
 */
public record FunctionType(
    Span span,
    List<FunctionTypeParam> params,
    Optional<FunctionTypeParam> rest,
    TypeNode returnType,
    boolean isAsync
) implements TypeNode {}
