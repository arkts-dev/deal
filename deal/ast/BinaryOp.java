package deal.ast;

/** Binary operators used in {@link BinaryExpr}. */
public enum BinaryOp {
    ADD,   // +
    SUB,
    MUL,
    DIV,
    REM,

    POW,
    EQ,
    NEQ,   // !==
    LT,    // <
    LTE,   // <=
    GT,    // >
    GTE,   // >=
    AND,   // &&
    OR     // ||
}
