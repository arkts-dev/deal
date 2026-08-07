package deal.checker;

import deal.ast.CallExpr;
import deal.types.Type;

import java.util.List;

/**
 * Custom type-checking hook for intrinsic / built-in functions.
 * Intrinsics like {@code int()}, {@code number()}, and {@code has()} have
 * type rules that do not fit the standard function-call model, so they
 * provide their own resolver.
 */
public interface IntrinsicResolver {

    /**
     * Type-check a call to this intrinsic.
     *
     * @param call   the call expression AST node
     * @param argTypes the already-resolved types of each argument
     * @param ctx    the type-checker context (for producing diagnostics, resolving
     *               contextual types, etc.)
     * @return the result type of the call, or {@code null} if a type error
     *         occurred (the diagnostic has already been emitted)
     */
    Type checkCall(CallExpr call, List<Type> argTypes, TypeChecker.Context ctx);
}
