package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.Backend;
import deal.lexer.Lexer;
import deal.module.CompilationOrchestrator;
import deal.module.ExportExtractor;
import deal.parser.Parser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Comments are whitespace, including former directive spellings and harness-like headers. */
public final class DirectiveTest {
    private static int passed;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }
    private static final List<String> CONTENTS = List.of(
        "@jsonable", "@jsonablex", "@jsonable:foo", "@jsonable-foo", "@jsonable foo",
        "@deal-version", "@deal-version 1.6", "@deal-version bad", "@extern-c", "@c-struct",
        "@c-pointer", "@nope", "@", "@!🌍", "@spec: comment semantics", "ordinary text");

    private static void lexicalForms() {
        for (String ending : List.of("\n", "\r", "\r\n", "")) {
            for (String contents : CONTENTS) {
                String comment = "// " + contents;
                String source = comment + ending + (ending.isEmpty() ? "" : "export class C {}");
                var lex = new Lexer(source, "test.deal").tokenize();
                check(lex.diagnostics().isEmpty(), source + lex.diagnostics());
                check(lex.tokens().stream().noneMatch(t -> t.lexeme().contains("//")), "comments never become source tokens");
                var token = lex.tokens().getFirst();
                check(token.startScalarOffset() == comment.length() + ending.length(),
                    "following token or EOF retains exact scalar offset");
                check(token.type() == (ending.isEmpty() ? TokenType.EOF : TokenType.EXPORT), "comment consumes no source tokens");
                check(token.line() == (ending.isEmpty() ? 1 : 2), "comment ending retains line tracking");
            }
        }
    }

    private static void bindingAndTransitions() {
        for (String contents : CONTENTS) {
            for (String declaration : List.of("export class C { x:number; }", "class C { x:number; }",
                    "export function f():null{return null;}", "function f():null{return null;}",
                    "let x:number=1;")) {
                String source = "// " + contents + "\n/* ordinary */\n// " + contents + "\n\n" + declaration;
                var lex = new Lexer(source, "test.deal").tokenize();
                var parsed = new Parser(lex.tokens(), "test.deal").parse();
                check(parsed.diagnostics().isEmpty(), source + parsed.diagnostics());
                check(parsed.program().statements().size() == 1, "comment transitions retain declaration");
                var statement = parsed.program().statements().getFirst();
                check(statement.span().startLine() == 5, "declaration retains original line");
                if (statement instanceof ExportDeclaration export) statement = export.declaration();
                if (statement instanceof ClassDeclaration cls) {
                    check(cls.name().equals("C") && cls.fields().size() == 1, "comments retain only the explicit class schema");
                    var exports = new ExportExtractor("test", true).extract(parsed.program());
                    check(!exports.containsKey("C$fromJson") && !exports.containsKey("C$toJson"), "comments grant no exports");
                }
            }
        }
        for (String source : List.of("// @jsonable", "// @jsonable\n// @nope", "/* @jsonable */")) {
            var lex = new Lexer(source, "test.deal").tokenize();
            var parsed = new Parser(lex.tokens(), "test.deal").parse();
            check(lex.diagnostics().isEmpty() && parsed.diagnostics().isEmpty(), "comment-only file is valid");
            check(parsed.program().statements().isEmpty(), "comment-only file contains no declarations");
        }
    }

    private static void declarationIdentifiersAndSchemas() {
        for (String file : List.of("test.deal", "test.d.deal")) {
            for (String name : List.of("C$fromJson", "C$toJson", "$fromJson", "C$other", "C$$toJson")) {
                String body = file.endsWith(".d.deal") ? ";" : "{return 1;}";
                var lex = new Lexer("// @jsonable\nexport function " + name + "():number" + body, file).tokenize();
                var parsed = new Parser(lex.tokens(), file).parse();
                check(parsed.diagnostics().isEmpty(), "sigil rejection belongs to name resolution");
                var resolver = new NameResolver(file, new StubModuleResolver());
                resolver.resolve(parsed.program());
                var diagnostics = resolver.diagnostics();
                check(diagnostics.size() == 1 && diagnostics.getFirst().code().equals("E2008")
                    && diagnostics.getFirst().message().equals("Identifier '" + name + "' must not contain '$'"),
                    "no declaration-file sigil exemption: " + diagnostics);
            }
            String source = "// @jsonable\nexport class C { next?:C; payload:unknown; }";
            var lex = new Lexer(source, file).tokenize();
            var parsed = new Parser(lex.tokens(), file).parse();
            var resolver = new NameResolver(file, new StubModuleResolver());
            var scope = resolver.resolve(parsed.program());
            check(resolver.diagnostics().isEmpty(), "recursive declarations need no marker");
            check(scope.resolve("C$fromJson") == null && scope.resolve("C$toJson") == null, "no synthesized callable symbols");
            check(TypeChecker.check(file, scope, resolver, parsed.program()).diagnostics().isEmpty(), "recursive unknown schema remains legal");
        }
        for (String contents : CONTENTS) {
            var lex = new Lexer("// " + contents + "\nclass C{field:Missing;}", "test.deal").tokenize();
            var parsed = new Parser(lex.tokens(), "test.deal").parse();
            var resolver = new NameResolver("test.deal", new StubModuleResolver());
            var scope = resolver.resolve(parsed.program());
            var checked = TypeChecker.check("test.deal", scope, resolver, parsed.program());
            check(!resolver.diagnostics().isEmpty() || !checked.diagnostics().isEmpty(), "comments cannot suppress unused annotation diagnostics");
        }
    }

    private static void templatesAndPositions() {
        for (String contents : CONTENTS) {
            for (String ending : List.of("\n", "\r", "\r\n")) {
                for (String expression : List.of("`🌍${ // " + contents + ending + " 1}`",
                        "`🌍${ `nested${ // " + contents + ending + " 1}` }`")) {
                    var lex = new Lexer("let text=" + expression + ";", "test.deal").tokenize();
                    var parsed = new Parser(lex.tokens(), "test.deal").parse();
                    check(lex.diagnostics().isEmpty() && parsed.diagnostics().isEmpty(), "template comment remains whitespace: " + parsed.diagnostics());
                    check(parsed.program().statements().size() == 1, "nested template expression retained");
                }
            }
        }
        String source = "let text=`🌍${ // @nope\r\n @}`;";
        var lex = new Lexer(source, "test.deal").tokenize();
        var parsed = new Parser(lex.tokens(), "test.deal").parse();
        var error = parsed.diagnostics().stream().filter(d -> d.code().equals("E1001")).findFirst().orElseThrow();
        int index = source.lastIndexOf('@');
        check(error.range().startScalarOffset() == index
            && error.range().endScalarOffset() == index + 1,
            "real template lexical error retains original scalar offsets");
        check(error.line() == 2 && error.column() == 2, "real template lexical error retains original line/column");
    }

    private static void productionComments() throws Exception {
        Path root = Files.createTempDirectory("deal-comment-production-");
        try {
            Path source = root.resolve("src");
            Files.createDirectories(source);
            Files.writeString(root.resolve("deal.json"), "{\"languageVersion\":\"1.6\"}");
            Files.writeString(source.resolve("main.deal"), "// @spec: comments are whitespace\n// @jsonable:foo\n"
                + "class Node{value:number;next?:Node;}\n"
                + "export function main():null{let node:Node=JSON.parse(\"{\\\"value\\\":1}\");"
                + "let text=JSON.stringify(node);return null;}");
            for (Backend backend : List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM)) {
                var compiler = new CompilationOrchestrator(source.resolve("main.deal"), root.resolve(backend.name()),
                    false, false, false, backend, Map.of(), List.of(source), Path.of(".").toAbsolutePath().normalize());
                check(compiler.compile(), "public compile accepts comments on " + backend + ": " + compiler.diagnostics());
                check(compiler.diagnostics().isEmpty(), "public compile has no directive diagnostics");
            }
            Files.writeString(source.resolve("model.d.deal"), "// @jsonable\n"
                + "export function Model$fromJson(text:string):number;\n"
                + "export function Model$toJson(value:number):string;\n");
            Files.writeString(source.resolve("main.deal"), "import * as M from \"./model\";\n"
                + "export function main():null{return null;}");
            for (Backend backend : List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM)) {
                var compiler = new CompilationOrchestrator(source.resolve("main.deal"), root.resolve(backend.name()),
                    false, false, false, backend, Map.of(), List.of(source), Path.of(".").toAbsolutePath().normalize());
                check(!compiler.compile(), "public compile rejects declaration sigils on " + backend);
                var errors = compiler.diagnostics().stream().filter(d -> d.severity().equals("error")).toList();
                check(errors.size() == 2 && errors.stream().allMatch(d -> d.code().equals("E2008")),
                    "public declarations have no helper exemption: " + errors);
            }
            Files.writeString(source.resolve("main.deal"), "// @jsonable\nclass Unused{value:Missing;}\n"
                + "export function main():null{return null;}");
            for (Backend backend : List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM)) {
                var compiler = new CompilationOrchestrator(source.resolve("main.deal"), root.resolve(backend.name()),
                    false, false, false, backend, Map.of(), List.of(source), Path.of(".").toAbsolutePath().normalize());
                check(!compiler.compile(), "public compile checks unused annotations on " + backend);
                var errors = compiler.diagnostics().stream().filter(d -> d.severity().equals("error")).toList();
                check(errors.size() == 1 && errors.getFirst().code().equals("E3004")
                    && errors.getFirst().message().equals("Unknown type 'Missing'"),
                    "late resolver annotation errors are not lost: " + errors);
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        lexicalForms();
        bindingAndTransitions();
        declarationIdentifiersAndSchemas();
        templatesAndPositions();
        productionComments();
        System.out.println("Passed: " + passed + ", Failed: 0");
    }
}
