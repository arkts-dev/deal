package deal.ast;

/** Assignment/update syntax; arithmetic retains ordinary binary operand rules. */
public enum AssignmentOperator {
    ASSIGN("=", null),
    ADD("+=", BinaryOp.ADD),
    SUB("-=", BinaryOp.SUB),
    MUL("*=", BinaryOp.MUL),
    DIV("/=", BinaryOp.DIV),
    REM("%=", BinaryOp.REM);

    private final String text;
    private final BinaryOp binary;

    AssignmentOperator(String text, BinaryOp binary) {
        this.text = text;
        this.binary = binary;
    }

    public String text() { return text; }
    public BinaryOp binary() { return binary; }
}
