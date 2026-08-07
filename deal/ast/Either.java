package deal.ast;

/**
 * General-purpose two-case tagged union.
 * Used for else-branches ({@code Either<IfStatement, Block>}) and other
 * two-case alternatives.
 *
 * @param <A> type of the left/primary alternative
 * @param <B> type of the right/secondary alternative
 */
public sealed interface Either<A, B> permits Either.Left, Either.Right {

    record Left<A, B>(A value) implements Either<A, B> {}

    record Right<A, B>(B value) implements Either<A, B> {}
}
