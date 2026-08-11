package deal.ast;

import java.util.List;
import java.util.Optional;

/** function name(params...): R { body } */
public record FunctionDeclaration(
    Span span,
    String name,
    List<Parameter> params,
    Optional<Parameter> restParam,
    TypeNode returnType,
    Block body,
    boolean isAsync
) implements StatementNode {}
