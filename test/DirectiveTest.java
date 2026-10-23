package deal.test;

import deal.ast.*;
import deal.codegen.Backend;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.RangeOrigin;
import deal.lexer.*;
import deal.module.CompilationOrchestrator;
import deal.module.ExportExtractor;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.project.ProjectLocator;
import deal.semantic.CompilerInvocation;
import deal.types.Type;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class DirectiveTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static LexResult lex(String source) {
        return new Lexer(source, "test.deal").tokenize();
    }

    private static LexResult lexFile(String source, String filename) {
        return new Lexer(source, filename).tokenize();
    }

    private static ParseResult parse(String source) {
        LexResult lex = lex(source);
        return new Parser(lex.tokens(), "test.deal",
            lex.directiveEvents()).parse();
    }

    private static ParseResult parseFile(String source, String filename) {
        LexResult lex = lexFile(source, filename);
        return new Parser(lex.tokens(), filename,
            lex.directiveEvents()).parse();
    }

    /** Full-pipeline diagnostics: lexer diagnostics plus parse result. */
    private static List<CompilerDiagnostic> fullDiags(String source) {
        LexResult lex = lex(source);
        ParseResult pr = new Parser(lex.tokens(), "test.deal",
            lex.directiveEvents()).parse();
        List<CompilerDiagnostic> all = new java.util.ArrayList<>(lex.diagnostics());
        all.addAll(pr.diagnostics());
        return all;
    }

    private static CompilerDiagnostic diagOf(List<CompilerDiagnostic> diags,
                                              String code) {
        return diags.stream().filter(d -> d.code().equals(code))
            .findFirst().orElse(null);
    }

    private static CompilerDiagnostic diagOf(ParseResult r, String code) {
        return r.diagnostics().stream()
            .filter(d -> d.code().equals(code))
            .findFirst().orElse(null);
    }

    private static boolean hasError(ParseResult r, String code) {
        return r.diagnostics().stream().anyMatch(
            d -> "error".equals(d.severity()) && d.code().equals(code));
    }

    // =========================================================================
    // Verification 1: lexical forms
    // =========================================================================

    static void testLexicalForms() {
        System.out.println("-- Lexical forms: five names under LF/CRLF/CR/EOF --");

        for (String terminator : new String[]{"\n", "\r\n", "\r", ""}) {
            String suffix = terminator.isEmpty() ? "" : terminator + "export class C {}";
            LexResult r = lex("// @jsonable" + suffix);
            check(r.directiveEvents().size() == 1,
                "jsonable under " + esc(terminator) + ": one event, got "
                    + r.directiveEvents().size());
            if (!r.directiveEvents().isEmpty()) {
                CompilerDirective e = r.directiveEvents().get(0);
                check(e.name() == DirectiveName.JSONABLE
                        && e.trimmedArgument().equals("")
                        && e.rawArgument().equals(""),
                    "jsonable shape under " + esc(terminator));
            }
            for (String name : new String[]{"extern-c", "c-struct", "c-pointer"}) {
                LexResult removed = lex("// @" + name + suffix);
                CompilerDiagnostic rejected = diagOf(removed.diagnostics(), "E1044");
                check(rejected != null, "removed @" + name + " rejected under " + esc(terminator));
                if (rejected != null) {
                    check(rejected.range().startScalarOffset() == 4
                        && rejected.range().endScalarOffset() == 4 + name.length(),
                        "removed directive range covers its exact name");
                }
            }
            r = lex("// @deal-version 1.4" + suffix);
            check(diagOf(r.diagnostics(), "E1044") != null,
                "removed deal-version directive rejected under " + esc(terminator));
        }

        LexResult r;
        // Recognized prefixes are never reclassified as unknown: the four
        // forbidden-argument forms parse to jsonable + argument, and the
        // parser's argument sweep emits E1045 at the complete comment.
        for (String form : new String[]{"@jsonablex", "@jsonable:foo",
                "@jsonable-foo", "@jsonable foo"}) {
            ParseResult pr = parse("// " + form + "\nexport class C {}");
            check(hasError(pr, "E1045"),
                form + " → E1045, got " + pr.diagnostics());
            CompilerDiagnostic d = diagOf(pr, "E1045");
            if (d != null) {
                check(d.range().origin() == RangeOrigin.SOURCE
                        && d.range().startScalarOffset() == 0,
                    form + ": E1045 at the complete comment");
            }
        }

        // Unknown names: E1044 over the recovered name range plus the
        // complete-comment note (E1044 is a lexer diagnostic).
        CompilerDiagnostic d = diagOf(fullDiags("// @foo bar\nexport class C {}"),
            "E1044");
        check(d != null, "@foo bar → E1044");
        if (d != null) {
            // 'foo' runs 4..7; the complete comment is 0..11.
            check(d.range().startScalarOffset() == 4
                    && d.range().endScalarOffset() == 7,
                "@foo bar: E1044 over the recovered name (4,7), got ("
                    + d.range().startScalarOffset() + ","
                    + d.range().endScalarOffset() + ")");
            check(d.notes().size() == 1 && d.notes().get(0).range() != null
                    && d.notes().get(0).range().startScalarOffset() == 0
                    && d.notes().get(0).range().endScalarOffset() == 11,
                "@foo bar: complete-comment note (0,11), got " + d.notes());
        }

        // Prefix of a recognized name is unknown (never silently matched).
        check(diagOf(fullDiags("// @json\nexport class C {}"), "E1044") != null,
            "@json → E1044");

        // Punctuation/empty forms.
        CompilerDiagnostic empty = diagOf(
            fullDiags("// @\nexport class C {}"), "E1044");
        check(empty != null, "// @ → E1044");
        if (empty != null) {
            check(empty.range().startScalarOffset() == 3
                    && empty.range().endScalarOffset() == 4,
                "// @: E1044 over '@' (3,4), got ("
                    + empty.range().startScalarOffset() + ","
                    + empty.range().endScalarOffset() + ")");
        }
        CompilerDiagnostic punct = diagOf(
            fullDiags("// @!-x\nexport class C {}"), "E1044");
        check(punct != null, "// @!-x → E1044");
        if (punct != null) {
            check(punct.range().startScalarOffset() == 3
                    && punct.range().endScalarOffset() == 7,
                "// @!-x: E1044 over '@!-x' (3,7), got ("
                    + punct.range().startScalarOffset() + ","
                    + punct.range().endScalarOffset() + ")");
        }

        // LF/CRLF/CR/EOF pins: the E1044 recovered range and note hold
        // under every terminator.
        for (String term : new String[]{"\n", "\r\n", "\r", ""}) {
            String src = "// @nope" + term;
            CompilerDiagnostic dd = diagOf(fullDiags(src), "E1044");
            check(dd != null, "E1044 under " + esc(term));
            if (dd != null) {
                check(dd.range().startScalarOffset() == 4
                        && dd.range().endScalarOffset() == 8
                        && dd.notes().size() == 1
                        && dd.notes().get(0).range() != null
                        && dd.notes().get(0).range().endScalarOffset() == 8,
                    "E1044 range/note under " + esc(term) + ": "
                        + dd.range() + " notes " + dd.notes());
            }
        }
    }

    private static String esc(String s) {
        return s.replace("\r", "\\r").replace("\n", "\\n");
    }

    // =========================================================================
    // Verification 2: transitions
    // =========================================================================

    static void testTransitions() {
        System.out.println("-- Transitions: anchoring machine (D3) --");

        // Immediate attachment: anchor == emitted token index ==
        // preceding count (the anchor-before-clear-before-emission
        // invariant).
        LexResult r = lex("// @jsonable\nexport class C {}");
        check(!r.directiveEvents().isEmpty()
                && r.directiveEvents().get(0).declarationAnchorTokenIndex() != null
                && r.directiveEvents().get(0).declarationAnchorTokenIndex() == 0
                && r.directiveEvents().get(0).precedingNonCommentTokenCount() == 0
                && r.directiveEvents().get(0).declarationAnchorTokenIndex()
                    == r.directiveEvents().get(0).precedingNonCommentTokenCount(),
            "immediate attachment: anchor == emitted index == preceding count");

        // Whitespace/blank-line preservation.
        r = lex("// @jsonable\n\n\t \nexport class C {}");
        check(!r.directiveEvents().isEmpty()
                && r.directiveEvents().get(0).declarationAnchorTokenIndex() != null
                && r.directiveEvents().get(0).declarationAnchorTokenIndex() == 0,
            "whitespace/blank lines preserve the run");

        // Ordinary comment breaks without anchoring.
        r = lex("// @jsonable\n// hello\nexport class C {}");
        check(r.directiveEvents().size() == 1
                && r.directiveEvents().get(0).declarationAnchorTokenIndex() == null,
            "ordinary comment breaks the run without anchoring");

        // File directive breaks without anchoring (and stays unanchored).
        r = lex("// @jsonable\n// @deal-version 1.3\nexport class C {}");
        check(r.directiveEvents().size() == 2
                && r.directiveEvents().get(0).declarationAnchorTokenIndex() == null
                && r.directiveEvents().get(1).declarationAnchorTokenIndex() == null,
            "file directive breaks the run without anchoring");

        // Unknown directive breaks without anchoring.
        r = lex("// @jsonable\n// @nope\nexport class C {}");
        check(r.directiveEvents().size() == 2
                && r.directiveEvents().get(0).declarationAnchorTokenIndex() == null
                && r.directiveEvents().get(1).declarationAnchorTokenIndex() == null,
            "unknown directive breaks the run without anchoring");

        // Non-declaration anchors: let, import, field, expression tokens.
        r = lex("// @jsonable\nlet x = 1;");
        check(!r.directiveEvents().isEmpty()
                && r.directiveEvents().get(0).declarationAnchorTokenIndex() != null
                && r.directiveEvents().get(0).declarationAnchorTokenIndex() == 0,
            "let is a non-declaration anchor (index 0)");
        ParseResult pr = parse("// @jsonable\nlet x = 1;");
        check(pr.diagnostics().stream().anyMatch(
                d -> d.code().equals("E1043") && d.severity().equals("error")),
            "non-declaration anchor produces the finalize E1043 error");

        // EOF retention as unanchored.
        r = lex("// @jsonable");
        check(r.directiveEvents().size() == 1
                && r.directiveEvents().get(0).declarationAnchorTokenIndex() == null,
            "EOF retains the event unanchored");

        // A second declaration directive extends the run: one anchor for
        // both.
        r = lex("// @jsonable\n// @jsonable\nexport class C {}");
        check(r.directiveEvents().size() == 2
                && r.directiveEvents().get(0).declarationAnchorTokenIndex() != null
                && r.directiveEvents().get(0).declarationAnchorTokenIndex() == 0
                && r.directiveEvents().get(1).declarationAnchorTokenIndex() != null
                && r.directiveEvents().get(1).declarationAnchorTokenIndex() == 0,
            "a second declaration directive extends the run to one anchor");
    }

    // =========================================================================
    // Verification 3: binding
    // =========================================================================

    static void testBinding() {
        System.out.println("-- Binding (D4) --");

        // Exported class + @jsonable: JSONABLE metadata, no diagnostics.
        ParseResult r = parse("// @jsonable\nexport class C { x: number; }");
        boolean clean = r.diagnostics().isEmpty();
        check(clean, clean ? "" : "export class binding clean: " + r.diagnostics());
        ExportDeclaration ed = (ExportDeclaration) r.program().statements().get(0);
        ClassDeclaration cd = (ClassDeclaration) ed.declaration();
        check(cd.isJsonable(), "JSONABLE metadata applied");
        check(r.program().fileDirectives() != null,
            "ProgramNode carries fileDirectives");

        // Export-wrapped function: E1043 error, no metadata.
        r = parse("// @jsonable\nexport function f(): int { return 0; }");
        check(r.hasErrors(), "E1043 is a compile error");
        check(diagOf(r, "E1043") != null, "E1043 for export function");

        // Non-exported class: E1043 error, no metadata.
        r = parse("// @jsonable\nclass C { x: number; }");
        check(diagOf(r, "E1043") != null, "E1043 for standalone class");
        ClassDeclaration standalone = (ClassDeclaration) r.program()
            .statements().get(0);
        check(!standalone.isJsonable(),
            "standalone class carries no JSONABLE metadata");

        // Non-exported function.
        r = parse("// @jsonable\nfunction f(): int { return 0; }");
        check(diagOf(r, "E1043") != null, "E1043 for standalone function");

        // Non-exported async function (start token ASYNC).
        r = parse("// @jsonable\nasync function f(): int { return 0; }");
        check(diagOf(r, "E1043") != null, "E1043 for standalone async function");

        // E1043 incompatible-attachment error: no synthetic C$fromJson/C$toJson
        // symbols and no metadata (ExportExtractor never sees a
        // jsonable class).
        r = parse("// @jsonable\nfunction f(): int { return 0; }");
        Map<String, Type> exports = new ExportExtractor("m", false)
            .extract(r.program());
        check(!exports.containsKey("C$fromJson"),
            "no synthetic C$fromJson for an invalid binding");
        check(!exports.containsKey("C$toJson"),
            "no synthetic C$toJson for an invalid binding");

        // Invalid metadata never reaches generation: the only jsonable
        // classes are the valid export-class bindings.
        r = parse("// @jsonable\nclass Bad { x: number; }");
        check(!((ClassDeclaration) r.program().statements().get(0))
                .isJsonable(),
            "invalid metadata never applied");
    }

    // =========================================================================
    // Declaration-file binding
    // =========================================================================

    static void testDeclarationFileBinding() {
        System.out.println("-- Declaration-file binding (D4) --");

        // @jsonable on an export class in a .d.deal file: no metadata,
        // no E1043, no synthetic C$fromJson/C$toJson; explicit JSON
        // signatures preserved.
        String decl = "// @jsonable\nexport class C { x: number; }\n"
            + "export function C$fromJson(s: string): C | null;\n"
            + "export function C$toJson(c: C): string;";
        ParseResult r = parseFile(decl, "lib.d.deal");
        boolean clean = r.diagnostics().isEmpty();
        check(clean, clean ? "" : "declaration-file binding clean: " + r.diagnostics());
        ExportDeclaration ed = (ExportDeclaration) r.program().statements().get(0);
        ClassDeclaration cd = (ClassDeclaration) ed.declaration();
        check(!cd.isJsonable(), "no JSONABLE metadata in a .d.deal file");
        Map<String, Type> exports = new ExportExtractor("lib", true)
            .extract(r.program());
        check(exports.containsKey("C$fromJson")
                && exports.containsKey("C$toJson"),
            "explicit JSON signatures preserved in the export map: "
                + exports.keySet());

        // Unattached @jsonable in a .d.deal file still errors E1043.
        r = parseFile("// @jsonable\nlet x = 1;", "lib.d.deal");
        check(diagOf(r, "E1043") != null,
            "unattached @jsonable in a .d.deal file errors E1043");

        // Non-class attachment errors E1043 with no metadata.
        r = parseFile("// @jsonable\nexport function f(): int { return 0; }",
            "lib.d.deal");
        check(diagOf(r, "E1043") != null,
            "non-class attachment in a .d.deal file warns E1043");

        // The E1045 argument rule still fires in .d.deal files.
        r = parseFile("// @jsonable:foo\nexport class C { x: number; }",
            "lib.d.deal");
        check(hasError(r, "E1045"),
            "@jsonable:foo in a .d.deal file still produces E1045");
    }

    // =========================================================================
    // Version contract (D7)
    // =========================================================================

    static void testVersionContract() {
        System.out.println("-- Declaration-version contract (D7) --");

        for (String value : new String[]{"", "1.4", "1.4", "01.04", "2.0", "bad"}) {
            CompilerDiagnostic d = diagOf(fullDiags("// @deal-version " + value
                + "\nexport class C {}"), "E1044");
            check(d != null, "removed @deal-version rejected regardless of argument");
            if (d != null) {
                check(d.range().startScalarOffset() == 4
                    && d.range().endScalarOffset() == 16,
                    "removed directive reports its name, not its former argument");
            }
        }


    }

    // =========================================================================
    // Template-embedded events (D10)
    // =========================================================================

    static void testTemplateEmbedded() {
        System.out.println("-- Template-embedded events (D10) --");

        // Recognized jsonable inside an interpolation: one E1043 error
        // at the rebased complete-comment range, no metadata.
        String src = "let s = `${ // @jsonable\n x}`;";
        ParseResult r = parse(src);
        long e1043 = r.diagnostics().stream()
            .filter(d -> d.code().equals("E1043")).count();
        boolean oneWarning = e1043 == 1;
        check(oneWarning, oneWarning ? "" :
            "exactly one E1043, got " + e1043 + ": " + r.diagnostics());
        CompilerDiagnostic w = diagOf(r, "E1043");
        if (w != null) {
            check(w.severity().equals("error"),
                "E1043 severity error, got " + w.severity());
            check(w.range().origin() == RangeOrigin.SOURCE
                    && w.range().startScalarOffset() == 12
                    && w.range().endScalarOffset() == 24,
                "E1043 at the rebased complete comment (12,24), got ("
                    + w.range().startScalarOffset() + ","
                    + w.range().endScalarOffset() + ")");
        }

        // Unknown name inside an interpolation: E1044 primary AND note
        // ranges in original-source coordinates (D10.8).
        String src2 = "let s = `${ // @nope\n x}`;";
        ParseResult r2 = parse(src2);
        CompilerDiagnostic e1044 = diagOf(r2, "E1044");
        check(e1044 != null, "embedded E1044 present");
        if (e1044 != null) {
            check(e1044.range().origin() == RangeOrigin.SOURCE
                    && e1044.range().startScalarOffset() == 16
                    && e1044.range().endScalarOffset() == 20,
                "E1044 primary at the rebased recovered name (16,20), got ("
                    + e1044.range().startScalarOffset() + ","
                    + e1044.range().endScalarOffset() + ")");
            check(e1044.notes().size() == 1
                    && e1044.notes().get(0).range() != null
                    && e1044.notes().get(0).range().origin()
                        == RangeOrigin.SOURCE
                    && e1044.notes().get(0).range().startScalarOffset() == 12
                    && e1044.notes().get(0).range().endScalarOffset() == 20,
                "E1044 note in original-source coordinates (12,20): "
                    + e1044.notes());
        }

        // @deal-version inside an interpolation: E1044 placement with a
        // truthful preceding count.
        ParseResult r3 = parse("let s = `${ // @deal-version 1.3\n x}`;");
        check(hasError(r3, "E1044"),
            "embedded removed @deal-version → E1044");

        // A C marker inside an interpolation: E1044 (no extern-C).
        ParseResult r4 = parse("let s = `${ // @c-struct\n x}`;");
        CompilerDiagnostic e1046c = diagOf(r4, "E1044");
        check(e1046c != null, "embedded C marker → E1044");
        if (e1046c != null) {
            check(e1046c.range().startScalarOffset() == 16
                    && e1046c.range().endScalarOffset() == 24,
                "embedded C-marker E1044 at the rebased range (12,24)");
        }

        // Nested templates bubble their events up.
        ParseResult r5 = parse("let s = `${ `${ // @jsonable\n x}` }`;");
        long nested1043 = r5.diagnostics().stream()
            .filter(d -> d.code().equals("E1043")).count();
        boolean oneNestedWarning = nested1043 == 1;
        check(oneNestedWarning, oneNestedWarning ? "" :
            "nested template bubbles exactly one E1043, got " + nested1043 + ": " + r5.diagnostics());

        // No sub-event ever binds to a declaration (no metadata).
        boolean jsonable = false;
        for (StatementNode stmt : r.program().statements()) {
            if (stmt instanceof ClassDeclaration c && c.isJsonable()) {
                jsonable = true;
            }
        }
        check(!jsonable, "no merged event produces metadata");
    }

    // =========================================================================
    // Production @spec pin (D8/D12): metadata is not stripped in production
    // =========================================================================

    static void testProductionSpecHeaderE1044() throws Exception {
        System.out.println("-- Production // @spec: → E1044 --");

        Path tmp = Files.createTempDirectory("deal_directive_prod_");
        try {
            Path srcDir = tmp.resolve("src");
            Files.createDirectories(srcDir);
            Files.writeString(tmp.resolve("deal.json"),
                "{\"languageVersion\": \"1.4\"}\n");
            Files.writeString(srcDir.resolve("main.deal"),
                "// @spec: Lexical elements — comment semantics\n"
                    + "export function main(): null { return null; }\n");

            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                srcDir.resolve("main.deal"), tmp.resolve("build"),
                false, false, false, Backend.LUAJIT,
                Map.of(),
                List.of(srcDir), Path.of(".").toAbsolutePath().normalize());
            boolean success = orchestrator.compile();
            check(!success, "the production compile fails on // @spec:");
            List<CompilerDiagnostic> diags = orchestrator.diagnostics();
            long e1044 = diags.stream()
                .filter(d -> d.code().equals("E1044")).count();
            check(e1044 == 1,
                "production // @spec: emits exactly one E1044, got "
                    + e1044 + ": " + diags);
            CompilerDiagnostic d = diags.stream()
                .filter(x -> x.code().equals("E1044"))
                .findFirst().orElse(null);
            if (d != null) {
                // '@spec:' — the recovered unknown name runs from offset
                // 3+... the comment starts at 0: "// @spec: ..." — '@' at
                // 3, 'spec' at 4..8.
                check(d.range().startScalarOffset() == 4
                        && d.range().endScalarOffset() == 8,
                    "E1044 at the recovered name (4,8), got ("
                        + d.range().startScalarOffset() + ","
                        + d.range().endScalarOffset() + ")");
            }
        } finally {
            try {
                Files.walk(tmp).sorted(Comparator.reverseOrder())
                    .forEach(f -> { try { Files.deleteIfExists(f); }
                        catch (IOException ignored) { } });
            } catch (IOException ignored) { }
        }
    }

    // =========================================================================
    // Manifest admission
    // =========================================================================

    private static CompilationOrchestrator locateOrchestrator(Path entry) {
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            entry.toString(), null);
        check(located.context() != null && located.e2010() == null
                && located.cliDiagnostic() == null,
            "the manifest locates a context: "
                + (located.e2010() != null
                    ? located.e2010() : located.cliDiagnostic()));
        if (located.context() == null) {
            return null;
        }
        CompilerInvocation invocation = ConformanceHarnessMetadata.invocation();
        return new CompilationOrchestrator(located.context(),
            entry.toAbsolutePath().normalize(), false, false, false, false,
            null, invocation);
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Directive Test Battery (ISSUE-0273) ===\n");

        testLexicalForms();
        testTransitions();
        testBinding();
        testDeclarationFileBinding();
        testVersionContract();
        testTemplateEmbedded();
        testProductionSpecHeaderE1044();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
