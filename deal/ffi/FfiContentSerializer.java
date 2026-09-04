package deal.ffi;

import deal.ast.ArrayLiteralExpr;
import deal.ast.AssignmentExpr;
import deal.ast.AwaitExpression;
import deal.ast.BinaryExpr;
import deal.ast.Block;
import deal.ast.BreakStatement;
import deal.ast.CallExpr;
import deal.ast.ClassDeclaration;
import deal.ast.ContinueStatement;
import deal.ast.DeleteStatement;
import deal.ast.Either;
import deal.ast.ExpressionNode;
import deal.ast.ExpressionStatement;
import deal.ast.ForInit;
import deal.ast.ForOfStatement;
import deal.ast.ForStatement;
import deal.ast.FunctionExpr;
import deal.ast.HasExpr;
import deal.ast.IdentifierExpr;
import deal.ast.IfStatement;
import deal.ast.IndexExpr;
import deal.ast.LiteralExpr;
import deal.ast.LiteralValue;
import deal.ast.MemberAccessExpr;
import deal.ast.ObjectLiteralExpr;
import deal.ast.Property;
import deal.ast.ReturnStatement;
import deal.ast.StatementNode;
import deal.ast.TemplateLiteralExpr;
import deal.ast.ThrowStatement;
import deal.ast.TryStatement;
import deal.ast.TypeNode;
import deal.ast.UnaryExpr;
import deal.ast.VariableDeclaration;
import deal.ast.WhileStatement;
import deal.semantic.ir.CanonicalJson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The canonical content serializer of the extern-C metadata seam
 * (design source {@code deal-v1.2-directives-and-c-ffi-declarations}
 * D4/D7): one deterministic CanonicalJson-based producer shared by the
 * validator and the generator, so every digest and content string is
 * derived from identical inputs.
 *
 * <p>The expression/statement serialization is structural — node and
 * operator kinds, lossless literals, identifiers, and every node's
 * behavior-affecting scalar range — never source spelling, never
 * addresses, never process state. Any behavior-bearing change (literal,
 * operator, target, provider, range) changes the produced content and
 * therefore every digest derived from it.</p>
 *
 * <p>SHA-256 derivations use the canonical serializer's digest helper
 * over UTF-8 bytes; digests are indexes only.</p>
 */
public final class FfiContentSerializer {

    /** The pinned serializer version of every produced document. */
    public static final int SERIALIZER_VERSION = 1;

    private FfiContentSerializer() {
        // Static utility; no instances.
    }

    // =========================================================================
    // Digest helpers
    // =========================================================================

    /**
     * SHA-256 over the UTF-8 bytes of {@code text}, rendered as 64
     * lowercase hex chars. Deterministic for identical inputs.
     */
    public static String sha256(String text) {
        Objects.requireNonNull(text, "text");
        return CanonicalJson.sha256Hex(
            text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * SHA-256 over the canonical serialization of {@code value}.
     */
    public static String sha256(CanonicalJson.Value value) {
        Objects.requireNonNull(value, "value");
        return CanonicalJson.sha256Hex(CanonicalJson.serializeBytes(value));
    }

    /** The canonical text of one JSON value. */
    public static String text(CanonicalJson.Value value) {
        return CanonicalJson.serializeText(Objects.requireNonNull(value,
            "value"));
    }

    /** A canonical entry of the two half-open scalar offsets. */
    public static CanonicalJson.Entry rangeOf(deal.ast.Span span) {
        Objects.requireNonNull(span, "span");
        return CanonicalJson.e("range", CanonicalJson.obj(
            CanonicalJson.e("start", CanonicalJson.intValue(
                span.startScalarOffset())),
            CanonicalJson.e("end", CanonicalJson.intValue(
                span.endScalarOffset()))));
    }

    // =========================================================================
    // Expression serialization
    // =========================================================================

    /**
     * The canonical structural serialization of one expression.
     */
    public static CanonicalJson.Value expression(ExpressionNode expr) {
        Objects.requireNonNull(expr, "expr");
        return switch (expr) {
            case LiteralExpr le -> node("literal", le.span(),
                CanonicalJson.e("value", literalValue(le.value())));
            case IdentifierExpr ie -> node("identifier", ie.span(),
                CanonicalJson.e("name", CanonicalJson.str(ie.name())));
            case BinaryExpr be -> node("binary", be.span(),
                CanonicalJson.e("op", CanonicalJson.str(be.op().name())),
                CanonicalJson.e("left", expression(be.left())),
                CanonicalJson.e("right", expression(be.right())));
            case UnaryExpr ue -> node("unary", ue.span(),
                CanonicalJson.e("op", CanonicalJson.str(ue.op().name())),
                CanonicalJson.e("operand", expression(ue.expr())));
            case CallExpr ce -> {
                List<CanonicalJson.Value> args = new ArrayList<>();
                for (ExpressionNode arg : ce.args()) {
                    args.add(expression(arg));
                }
                yield node("call", ce.span(),
                    CanonicalJson.e("callee", expression(ce.callee())),
                    CanonicalJson.e("args", CanonicalJson.arr(args)));
            }
            case MemberAccessExpr mae -> node("member", mae.span(),
                CanonicalJson.e("object", expression(mae.object())),
                CanonicalJson.e("field", CanonicalJson.str(mae.field())));
            case IndexExpr ie -> node("index", ie.span(),
                CanonicalJson.e("array", expression(ie.array())),
                CanonicalJson.e("index", expression(ie.index())));
            case ArrayLiteralExpr ale -> {
                List<CanonicalJson.Value> elements = new ArrayList<>();
                for (ExpressionNode element : ale.elements()) {
                    elements.add(expression(element));
                }
                yield node("array-literal", ale.span(),
                    CanonicalJson.e("elements", CanonicalJson.arr(elements)));
            }
            case ObjectLiteralExpr ole -> {
                List<CanonicalJson.Value> properties = new ArrayList<>();
                for (Property property : ole.properties()) {
                    properties.add(CanonicalJson.obj(
                        CanonicalJson.e("name", CanonicalJson.str(
                            property.name())),
                        CanonicalJson.e("value", expression(
                            property.value()))));
                }
                yield node("object-literal", ole.span(),
                    CanonicalJson.e("properties",
                        CanonicalJson.arr(properties)));
            }
            case FunctionExpr fe -> {
                List<CanonicalJson.Value> params = new ArrayList<>();
                for (deal.ast.Parameter p : fe.params()) {
                    params.add(CanonicalJson.obj(
                        CanonicalJson.e("name", CanonicalJson.str(p.name())),
                        CanonicalJson.e("type", typeNode(p.type()))));
                }
                yield node("function", fe.span(),
                    CanonicalJson.e("async", CanonicalJson.bool(fe.isAsync())),
                    CanonicalJson.e("params", CanonicalJson.arr(params)),
                    CanonicalJson.e("returnType", typeNode(fe.returnType())),
                    CanonicalJson.e("body", blockContent(fe.body())));
            }
            case HasExpr he -> node("has", he.span(),
                CanonicalJson.e("object", expression(he.object())),
                CanonicalJson.e("field", CanonicalJson.str(he.field())));
            case AssignmentExpr ae -> node("assignment", ae.span(),
                CanonicalJson.e("target", expression(ae.target())),
                CanonicalJson.e("value", expression(ae.value())));
            case TemplateLiteralExpr tle -> {
                List<CanonicalJson.Value> parts = new ArrayList<>();
                for (ExpressionNode part : tle.parts()) {
                    parts.add(expression(part));
                }
                yield node("template", tle.span(),
                    CanonicalJson.e("parts", CanonicalJson.arr(parts)));
            }
            case AwaitExpression aw -> node("await", aw.span(),
                CanonicalJson.e("callee", expression(aw.callee())));
        };
    }

    /**
     * The canonical serialization of one type annotation node (shape
     * only: named/qualified names, array/nullable/function recursion,
     * function async markers and parameter names).
     */
    public static CanonicalJson.Value typeNode(TypeNode typeNode) {
        Objects.requireNonNull(typeNode, "typeNode");
        return switch (typeNode) {
            case deal.ast.NamedType nt -> node("named-type", nt.span(),
                CanonicalJson.e("name", CanonicalJson.str(nt.name())));
            case deal.ast.QualifiedType qt -> node("qualified-type",
                qt.span(),
                CanonicalJson.e("module", CanonicalJson.str(qt.moduleName())),
                CanonicalJson.e("name", CanonicalJson.str(qt.typeName())));
            case deal.ast.ArrayType at -> node("array-type", at.span(),
                CanonicalJson.e("element", typeNode(at.elementType())));
            case deal.ast.NullableType nt2 -> node("nullable-type",
                nt2.span(),
                CanonicalJson.e("inner", typeNode(nt2.innerType())));
            case deal.ast.FunctionType ft -> {
                List<CanonicalJson.Value> params = new ArrayList<>();
                for (deal.ast.FunctionTypeParam ftp : ft.params()) {
                    params.add(CanonicalJson.obj(
                        CanonicalJson.e("name", CanonicalJson.str(
                            ftp.name())),
                        CanonicalJson.e("type", typeNode(ftp.type()))));
                }
                yield node("function-type", ft.span(),
                    CanonicalJson.e("async", CanonicalJson.bool(ft.isAsync())),
                    CanonicalJson.e("params", CanonicalJson.arr(params)),
                    CanonicalJson.e("returnType", typeNode(ft.returnType())));
            }
        };
    }

    /**
     * The canonical serialization of one statement list (used for
     * function-expression bodies inside default expressions).
     */
    public static CanonicalJson.Value blockContent(Block block) {
        if (block == null) {
            return CanonicalJson.nullValue();
        }
        List<CanonicalJson.Value> statements = new ArrayList<>();
        for (StatementNode stmt : block.statements()) {
            statements.add(statement(stmt));
        }
        return CanonicalJson.arr(statements);
    }

    /**
     * The canonical structural serialization of one statement.
     */
    public static CanonicalJson.Value statement(StatementNode stmt) {
        Objects.requireNonNull(stmt, "stmt");
        return switch (stmt) {
            case Block b -> node("block", b.span(),
                CanonicalJson.e("statements", blockContent(b)));
            case VariableDeclaration vd -> node("variable", vd.span(),
                CanonicalJson.e("name", CanonicalJson.str(vd.name())),
                CanonicalJson.e("type", vd.typeAnnotation().isPresent()
                    ? typeNode(vd.typeAnnotation().get())
                    : CanonicalJson.nullValue()),
                CanonicalJson.e("value", expression(vd.initializer())));
            case ExpressionStatement es -> node("expression-statement",
                es.span(),
                CanonicalJson.e("value", expression(es.expr())));
            case ReturnStatement rs -> node("return", rs.span(),
                CanonicalJson.e("value", rs.expr().isPresent()
                    ? expression(rs.expr().get())
                    : CanonicalJson.nullValue()));
            case ThrowStatement ts -> node("throw", ts.span(),
                CanonicalJson.e("value", expression(ts.expr())));
            case DeleteStatement ds -> node("delete", ds.span(),
                CanonicalJson.e("target", expression(ds.target())));
            case IfStatement is -> {
                CanonicalJson.Value elseValue;
                if (is.elseBranch().isEmpty()) {
                    elseValue = CanonicalJson.nullValue();
                } else if (is.elseBranch().get()
                        instanceof Either.Left<IfStatement, Block> left) {
                    elseValue = statement(left.value());
                } else {
                    elseValue = statement(
                        ((Either.Right<IfStatement, Block>)
                            is.elseBranch().get()).value());
                }
                yield node("if", is.span(),
                    CanonicalJson.e("condition", expression(is.condition())),
                    CanonicalJson.e("then", statement(is.thenBlock())),
                    CanonicalJson.e("else", elseValue));
            }
            case WhileStatement ws -> node("while", ws.span(),
                CanonicalJson.e("condition", expression(ws.condition())),
                CanonicalJson.e("body", statement(ws.body())));
            case ForStatement fs -> {
                CanonicalJson.Value initValue;
                if (fs.init().isEmpty()) {
                    initValue = CanonicalJson.nullValue();
                } else if (fs.init().get() instanceof ForInit.VarDecl vd) {
                    initValue = statement(vd.decl());
                } else {
                    initValue = expression(
                        ((ForInit.AssignExpr) fs.init().get()).expr());
                }
                yield node("for", fs.span(),
                    CanonicalJson.e("init", initValue),
                    CanonicalJson.e("condition", fs.condition().isPresent()
                        ? expression(fs.condition().get())
                        : CanonicalJson.nullValue()),
                    CanonicalJson.e("update", fs.update().isPresent()
                        ? expression(fs.update().get())
                        : CanonicalJson.nullValue()),
                    CanonicalJson.e("body", statement(fs.body())));
            }
            case ForOfStatement fos -> node("for-of", fos.span(),
                CanonicalJson.e("variable", CanonicalJson.str(fos.varName())),
                CanonicalJson.e("varType", typeNode(fos.varType())),
                CanonicalJson.e("iterable", expression(fos.iterable())),
                CanonicalJson.e("body", statement(fos.body())));
            case TryStatement ts -> node("try", ts.span(),
                CanonicalJson.e("try", statement(ts.tryBlock())),
                CanonicalJson.e("catch", statement(ts.catchBlock())));
            case BreakStatement bs -> node("break", bs.span());
            case ContinueStatement cs -> node("continue", cs.span());
            case deal.ast.FunctionDeclaration fd -> node(
                "function-declaration", fd.span(),
                CanonicalJson.e("name", CanonicalJson.str(fd.name())),
                CanonicalJson.e("body", blockContent(fd.body())));
            case ClassDeclaration cd -> node("class", cd.span(),
                CanonicalJson.e("name", CanonicalJson.str(cd.name())));
            case deal.ast.ImportDeclaration imp -> node("import", imp.span(),
                CanonicalJson.e("alias", CanonicalJson.str(imp.alias())),
                CanonicalJson.e("module", CanonicalJson.str(imp.modulePath())));
            case deal.ast.ExportDeclaration exp -> node("export", exp.span(),
                CanonicalJson.e("declaration", statement(exp.declaration())));
        };
    }

    /** The canonical literal value of one literal node. */
    private static CanonicalJson.Value literalValue(LiteralValue value) {
        return switch (value) {
            case LiteralValue.NullLiteral ignored -> CanonicalJson.nullValue();
            case LiteralValue.BooleanLiteral b ->
                CanonicalJson.bool(b.value());
            case LiteralValue.IntLiteral i ->
                CanonicalJson.intValue(Math.toIntExact(i.value()));
            case LiteralValue.NumberLiteral n ->
                CanonicalJson.number(n.value());
            case LiteralValue.StringLiteral s ->
                CanonicalJson.str(s.value());
        };
    }

    /** A kinded canonical node: {@code {"k": kind, ...fields}}. */
    private static CanonicalJson.Value node(String kind, deal.ast.Span span,
                                            CanonicalJson.Entry... fields) {
        List<CanonicalJson.Entry> entries = new ArrayList<>();
        entries.add(CanonicalJson.e("k", CanonicalJson.str(kind)));
        entries.add(rangeOf(span));
        entries.addAll(List.of(fields));
        return CanonicalJson.obj(entries);
    }
}
