package deal.ast;

/**
 * All token types recognized by the DEAL lexer.
 * Used by both the lexer and parser.
 */
public enum TokenType {

    // -- Keywords (spec lines 12-13, 76-81) --
    LET,
    CLASS,
    FUNCTION,
    RETURN,
    IF,
    ELSE,
    WHILE,
    FOR,
    BREAK,
    CONTINUE,
    NULL,       // keyword + null literal
    TRUE,       // keyword + boolean literal
    FALSE,      // keyword + boolean literal
    IMPORT,
    EXPORT,
    FROM,
    DELETE,
    HAS,
    TRY,
    CATCH,
    THROW,
    AS,         // implicit keyword used in import declaration
    IN,         // reserved for future use

    // -- Literals --
    INT_LITERAL,
    NUMBER_LITERAL,
    STRING_LITERAL,
    TEMPLATE_LITERAL,

    // -- Identifier --
    IDENTIFIER,

    // -- Operators --
    PLUS,        // +
    MINUS,       // -
    STAR,        // *
    SLASH,       // /
    PERCENT,     // %
    STAR_STAR,   // **

    EQ_STRICT,   // ===
    NEQ_STRICT,  // !==
    EQ,          // ==
    NEQ,         // !=

    LT,          // <
    LTE,         // <=
    GT,          // >
    GTE,         // >=

    AND,         // &&
    OR,          // ||
    PIPE,        // |
    BANG,        // !

    EQ_SIGN,     // =
    ARROW,       // =>

    DOT,         // .
    QUESTION,    // ?
    COLON,       // :
    SEMICOLON,   // ;
    COMMA,       // ,

    ELLIPSIS,    // ...

    AMPERSAND,   // &

    // -- Punctuation --
    LBRACE,      // {
    RBRACE,      // }
    LPAREN,      // (
    RPAREN,      // )
    LBRACKET,    // [
    RBRACKET,    // ]

    // -- Special --
    EOF
}
