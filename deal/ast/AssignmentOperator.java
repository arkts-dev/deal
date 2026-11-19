package deal.ast;

/** Assignment/update syntax; arithmetic retains ordinary binary operand rules. */
public enum AssignmentOperator {
    ASSIGN("=", null),
    ADD("+=", BinaryOp.ADD),
    SUB("-=", BinaryOp.SUB),
    MUL("*=", BinaryOp.MUL),
    DIV("/=", BinaryOp.DIV),
    REM("%=", BinaryOp.REM),
    PRE_INCREMENT("++", BinaryOp.ADD),
    PRE_DECREMENT("--", BinaryOp.SUB),
    POST_INCREMENT("++", BinaryOp.ADD),
    POST_DECREMENT("--", BinaryOp.SUB);

    private final String text;
    private final BinaryOp binary;

    AssignmentOperator(String text, BinaryOp binary) {
        this.text = text;
        this.binary = binary;
    }

    public String text() { return text; }
    public BinaryOp binary() { return binary; }
    public boolean update() { return this == PRE_INCREMENT || this == PRE_DECREMENT || postfix(); }
    public boolean postfix() { return this == POST_INCREMENT || this == POST_DECREMENT; }
}
