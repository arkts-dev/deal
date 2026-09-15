package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.descriptors.DescriptorAst;
import deal.descriptors.DescriptorParseResult;
import deal.descriptors.DescriptorSyntaxError;
import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalClassIdentityIndex;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.ir.IrDumper;
import deal.lexer.*;
import deal.module.ModuleIdentityResolver;
import deal.parser.*;
import deal.types.Type;
import deal.types.Types;

import java.util.*;

/**
 * The canonical descriptor service corpus
 * (ISSUE-0315 / canonical-type-system-and-runtime-descriptors D2-D3,
 * descriptor-identity-propagation D6): this suite replaces the retired
 * v1.1 mapping-table consumption ({@code fs/docs/type-descriptor-mapping.md}
 * stays the untouched historical v1.1 reference — never read here) with
 * the canonical service corpus:
 *
 * <ul>
 *   <li>an accept corpus — primitives (including {@code bytes}), arrays,
 *       nullables, exact sync/async functions, and text-opaque class
 *       atoms — round-tripping byte-for-byte through
 *       {@code render(parse(text))}, plus {@code render(parse(encode(T))) == encode(T)}
 *       for a type-built corpus over the identity index;</li>
 *   <li>a legacy-rejection corpus — {@code T[]}, {@code T|null}, rest
 *       sigs, bare class names, dotted class-name-position text, nested
 *       nullables, {@code ?null} — each pinned to its exact
 *       {@link DescriptorSyntaxError.Kind} and never a partial AST;</li>
 *   <li>opaque class-atom rows — dots legal in non-final components,
 *       byte-for-byte atom equality, atoms ending exactly at their
 *       enclosing delimiters;</li>
 *   <li>IR dump rows — every descriptor the dumper emits is the
 *       canonical spelling and never a legacy one.</li>
 * </ul>
 */
public class TypeDescriptorTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Running TypeDescriptorTest (canonical service corpus) ===\n");

        testCanonicalAcceptCorpus();
        testEncodeRoundTrips();
        testLegacyRejectionCorpus();
        testOpaqueClassAtoms();
        testIrSpecFormatNotLegacy();
        testIrArrayDescriptors();
        testIrNullableDescriptorsOnParams();
        testIrClassDescriptors();
        testIrFunctionDescriptorsOnIdents();
        testIrNestedArrayDescriptors();
        testIrNullableArrayParam();
        testIrArrayOfNullableInt();
        testIrNullReturnFunctionDescriptor();
        testIrNullableReturnFunctionDescriptor();
        testIrAsyncFuncDescriptor();
        testIrZeroParamDescriptor();
        testIrFixedParamsDescriptor();
        testIrArrayParamDescriptor();
        testIrBytesRows();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void assertTrue(boolean cond, String label) {
        if (cond) { passed++; }
        else { failed++; System.out.println("FAIL [" + label + "]"); }
    }

    private static void assertContains(String haystack, String needle, String label) {
        if (haystack.contains(needle)) { passed++; }
        else {
            failed++;
            System.out.println("FAIL [" + label + "]: expected to contain '" + needle + "'");
            System.out.println("  Text:\n" + haystack);
        }
    }

    private static void assertNotContains(String haystack, String needle, String label) {
        if (!haystack.contains(needle)) { passed++; }
        else {
            failed++;
            System.out.println("FAIL [" + label + "]: should NOT contain '" + needle + "'");
            System.out.println("  Text:\n" + haystack);
        }
    }

    /** The compilation's identity index over the single-module standalone
     * classification (empty path &rarr; builtin; every other path &rarr;
     * a project module whose configured root text is the path itself). */
    private static CanonicalClassIdentityIndex testIndex(
            Map<String, CanonicalModuleIdentity> extra) {
        Map<String, CanonicalModuleIdentity> classification =
            new LinkedHashMap<>();
        classification.put("",
            CanonicalModuleIdentity.BuiltinModule.INSTANCE);
        for (String path : List.of("test", "lib/utils", "src.models")) {
            classification.put(path,
                new CanonicalModuleIdentity.ProjectModule(
                    new ProjectModuleIdentity(path, path, List.of())));
        }
        classification.putAll(extra);
        return ModuleIdentityResolver.buildIndex(classification);
    }

    private static Type.Class classType(String name,
                                        CanonicalModuleIdentity module) {
        return Types.classType(name,
            new CanonicalClassIdentity(module, name));
    }

    // =========================================================================
    // Test 1: canonical accept corpus — byte-identical parse/render round trips
    // =========================================================================

    static void testCanonicalAcceptCorpus() {
        System.out.println("  testCanonicalAcceptCorpus... ");
        List<String> accept = List.of(
            // primitives
            "null", "boolean", "int", "number", "string", "bytes", "table",
            // arrays / nullables / nesting at depth
            "[int]", "[bytes]", "?int", "?bytes", "[[int]]", "[?int]",
            "?[int]", "[[[bytes]]]", "[?[bytes]]", "?[[int]]",
            // functions — exact sync/async forms
            "()->null", "(int)->int", "(int,string)->?int", "(string,[int])->null",
            "async()->int", "async(int)->string", "async(bytes)->bytes",
            "async([bytes])->?bytes", "((int)->int)->int", "[(int)->int]",
            "[?(int)->int]", "?((int)->int)->int",
            // class atoms — opaque project/external/builtin projections
            "@test/User", "@lib/utils/User", "@lib.utils/User",
            "@$external/host.cfg/ServerConfig", "@$builtin/Error",
            "?@test/User", "[@test/User]", "[?@test/User]",
            "(?@test/User)->@$builtin/Error",
            // delimited atoms end exactly at their enclosing delimiter
            "[@a/b/C]", "(?@a/b/C)->null", "(int,@a/b/C)->null");
        int ok = 0;
        for (String text : accept) {
            DescriptorParseResult parsed =
                CanonicalRuntimeTypeDescriptor.parse(text);
            if (!(parsed instanceof DescriptorAst ast)) {
                failed++;
                System.out.println("FAIL [accept corpus]: '" + text
                    + "' did not parse: " + parsed);
                continue;
            }
            String rendered = CanonicalRuntimeTypeDescriptor.render(ast);
            if (!rendered.equals(text)) {
                failed++;
                System.out.println("FAIL [accept corpus]: '" + text
                    + "' rendered as '" + rendered + "'");
                continue;
            }
            DescriptorParseResult again =
                CanonicalRuntimeTypeDescriptor.parse(rendered);
            if (!(again instanceof DescriptorAst ast2)
                    || !CanonicalRuntimeTypeDescriptor.render(ast2)
                        .equals(text)) {
                failed++;
                System.out.println("FAIL [accept corpus]: '" + text
                    + "' does not round-trip through parse(render(ast))");
                continue;
            }
            ok++;
        }
        assertTrue(ok == accept.size(),
            "every canonical accept-corpus row parses and round-trips "
            + "byte-identically (" + ok + "/" + accept.size() + ")");
        System.out.println("OK (" + ok + " rows)");
    }

    // =========================================================================
    // Test 2: render(parse(encode(T))) == encode(T) for a type-built corpus
    // =========================================================================

    static void testEncodeRoundTrips() {
        System.out.println("  testEncodeRoundTrips... ");
        CanonicalRuntimeTypeDescriptor descriptors =
            new CanonicalRuntimeTypeDescriptor(testIndex(Map.of(
                "host.cfg", new CanonicalModuleIdentity.ExternalModule(
                    "host.cfg"))));
        CanonicalModuleIdentity project =
            new CanonicalModuleIdentity.ProjectModule(
                new ProjectModuleIdentity("test", "test", List.of()));
        List<Type> corpus = new ArrayList<>(List.of(
            Type.Null.INSTANCE, Type.Boolean.INSTANCE, Type.Int.INSTANCE,
            Type.Number.INSTANCE, Type.String.INSTANCE, Type.Bytes.INSTANCE,
            Type.Table.INSTANCE,
            Types.array(Type.Int.INSTANCE),
            Types.array(Type.Bytes.INSTANCE),
            Types.nullable(Type.Int.INSTANCE),
            Types.nullable(Type.Bytes.INSTANCE),
            Types.array(Types.array(Types.array(Type.Bytes.INSTANCE))),
            Types.array(Types.nullable(Type.Bytes.INSTANCE)),
            Types.nullable(Types.array(Type.Int.INSTANCE)),
            Types.func(List.of(), Type.Null.INSTANCE),
            Types.func(List.of(Type.Int.INSTANCE), Type.Int.INSTANCE),
            Types.func(List.of(Type.Int.INSTANCE), Type.String.INSTANCE, true),
            Types.func(List.of(Type.Bytes.INSTANCE), Type.Bytes.INSTANCE, true),
            Types.func(List.of(Types.array(Type.Bytes.INSTANCE)),
                Types.nullable(Type.Bytes.INSTANCE), true),
            Types.func(List.of(Types.func(List.of(), Type.Int.INSTANCE)),
                Type.Int.INSTANCE),
            Types.array(Types.func(List.of(), Type.Int.INSTANCE)),
            classType("User", project),
            classType("Error", CanonicalModuleIdentity.BuiltinModule.INSTANCE),
            classType("ServerConfig",
                new CanonicalModuleIdentity.ExternalModule("host.cfg")),
            Types.nullable(classType("User", project)),
            Types.array(classType("User", project)),
            Types.array(Types.nullable(classType("User", project)))));
        int ok = 0;
        for (Type t : corpus) {
            String encoded = descriptors.encode(t);
            DescriptorParseResult parsed =
                CanonicalRuntimeTypeDescriptor.parse(encoded);
            if (!(parsed instanceof DescriptorAst ast)) {
                failed++;
                System.out.println("FAIL [encode round trip]: encode(" + t
                    + ") = '" + encoded + "' does not parse: " + parsed);
                continue;
            }
            String rendered = CanonicalRuntimeTypeDescriptor.render(ast);
            if (!rendered.equals(encoded)) {
                failed++;
                System.out.println("FAIL [encode round trip]: encode(" + t
                    + ") = '" + encoded + "' rendered as '" + rendered + "'");
                continue;
            }
            ok++;
        }
        assertTrue(ok == corpus.size(),
            "render(parse(encode(T))) == encode(T) for every corpus type ("
            + ok + "/" + corpus.size() + ")");
        assertTrue(descriptors.encode(Type.Bytes.INSTANCE).equals("bytes"),
            "encode(Type.Bytes.INSTANCE) == 'bytes'");
        System.out.println("OK (" + ok + " types)");
    }

    // =========================================================================
    // Test 3: legacy-rejection corpus — pinned kinds, never a partial AST
    // =========================================================================

    static void testLegacyRejectionCorpus() {
        System.out.println("  testLegacyRejectionCorpus... ");
        record Row(String text, DescriptorSyntaxError.Kind kind) {}
        List<Row> rejected = List.of(
            new Row("int[]", DescriptorSyntaxError.Kind.TRAILING_CONTENT),
            new Row("string|null", DescriptorSyntaxError.Kind.TRAILING_CONTENT),
            new Row("T[]", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER),
            new Row("(string,...int[])", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER),
            new Row("(string,...string)->string", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER),
            new Row("User", DescriptorSyntaxError.Kind.BARE_CLASS_NAME),
            new Row("Error", DescriptorSyntaxError.Kind.BARE_CLASS_NAME),
            new Row("@src.models.User", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM),
            new Row("@a/b.C", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM),
            new Row("@host.cfg.ServerConfig", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM),
            new Row("??int", DescriptorSyntaxError.Kind.NESTED_NULLABLE),
            new Row("?null", DescriptorSyntaxError.Kind.NULL_INNER),
            new Row("?[]", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER),
            new Row("?int[]", DescriptorSyntaxError.Kind.TRAILING_CONTENT),
            new Row("?", DescriptorSyntaxError.Kind.UNEXPECTED_END),
            new Row("[]", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER),
            new Row("(int)int", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER),
            new Row("async[int]", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER),
            new Row("Async(int)->int", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER),
            new Row("@", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM),
            new Row("@Foo", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM),
            new Row("@a/", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM),
            new Row("@a//b", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM),
            new Row("@.", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM),
            new Row("@a->b", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM),
            new Row("", DescriptorSyntaxError.Kind.UNEXPECTED_END));
        int ok = 0;
        for (Row row : rejected) {
            DescriptorParseResult parsed =
                CanonicalRuntimeTypeDescriptor.parse(row.text());
            if (!(parsed instanceof DescriptorSyntaxError err)) {
                failed++;
                System.out.println("FAIL [legacy rejection]: '" + row.text()
                    + "' unexpectedly parsed: " + parsed);
                continue;
            }
            if (err.kind() != row.kind()) {
                failed++;
                System.out.println("FAIL [legacy rejection]: '" + row.text()
                    + "' kind " + err.kind() + " != pinned " + row.kind()
                    + " (" + err.message() + ")");
                continue;
            }
            ok++;
        }
        assertTrue(ok == rejected.size(),
            "every legacy-rejection row fails with its pinned kind ("
            + ok + "/" + rejected.size() + ")");
        System.out.println("OK (" + ok + " rows)");
    }

    // =========================================================================
    // Test 4: opaque class atoms — byte-for-byte equality, delimiter ends
    // =========================================================================

    static void testOpaqueClassAtoms() {
        System.out.println("  testOpaqueClassAtoms... ");
        DescriptorParseResult p1 = CanonicalRuntimeTypeDescriptor.parse(
            "@lib/utils/User");
        DescriptorParseResult p2 = CanonicalRuntimeTypeDescriptor.parse(
            "@lib.utils/User");
        assertTrue(p1 instanceof DescriptorAst.ClassAtom a
                && a.fullDescriptorText().equals("@lib/utils/User"),
            "@lib/utils/User parses as an opaque atom");
        assertTrue(p2 instanceof DescriptorAst.ClassAtom b
                && b.fullDescriptorText().equals("@lib.utils/User"),
            "@lib.utils/User parses as an opaque atom (dot in a "
            + "non-final component is legal)");
        assertTrue(p1 instanceof DescriptorAst.ClassAtom a
                && p2 instanceof DescriptorAst.ClassAtom b
                && !a.fullDescriptorText().equals(b.fullDescriptorText()),
            "atom text equality never crosses '/' vs '.' spellings");
        DescriptorParseResult pe = CanonicalRuntimeTypeDescriptor.parse(
            "@$external/host.cfg/ServerConfig");
        assertTrue(pe instanceof DescriptorAst.ClassAtom ea
                && ea.fullDescriptorText()
                    .equals("@$external/host.cfg/ServerConfig"),
            "@$external/host.cfg/ServerConfig parses (dotted externals "
            + "specifier is a legal non-final component)");
        DescriptorParseResult pb = CanonicalRuntimeTypeDescriptor.parse(
            "@$builtin/Error");
        assertTrue(pb instanceof DescriptorAst.ClassAtom ba
                && ba.fullDescriptorText().equals("@$builtin/Error"),
            "@$builtin/Error parses (the canonical builtin projection)");
        // Atoms end exactly at their enclosing delimiters.
        for (String delimited : List.of(
                "[@a/b/C]", "(?@a/b/C)->null", "(int,@a/b/C)->null")) {
            DescriptorParseResult parsed =
                CanonicalRuntimeTypeDescriptor.parse(delimited);
            assertTrue(parsed instanceof DescriptorAst,
                "delimited class atom '" + delimited
                    + "' parses completely: " + parsed);
        }
        System.out.println("OK");
    }

    // =========================================================================
    // IR dump rows — canonical spellings, never legacy
    // =========================================================================

    private record CompileResult(ProgramNode program,
                                 CheckResult checkResult,
                                 SymbolTable symbolTable) {}

    private static CompileResult compile(String source) {
        return compile(source, "test.deal");
    }

    private static CompileResult compile(String source, String filename) {
        LexResult lex = new Lexer(source, filename).tokenize();
        if (lex.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            throw new RuntimeException("Lex error: " + lex.diagnostics());
        }
        Parser parser = new Parser(lex.tokens(), filename, lex.directiveEvents());
        ParseResult parseResult = parser.parse();
        if (parseResult.hasErrors()) {
            throw new RuntimeException("Parse error: " + parseResult.diagnostics());
        }
        ProgramNode program = parseResult.program();

        ModuleResolverStub resolver = new ModuleResolverStub();
        NameResolver nr = new NameResolver("test", resolver);
        SymbolTable symTable = nr.resolve(program);
        CheckResult result = TypeChecker.check("test", symTable, nr, program);

        return new CompileResult(program, result, symTable);
    }

    private static String dumpIR(CompileResult cr) {
        return IrDumper.dump(cr.program, cr.checkResult, "test",
            testIndex(Map.of()));
    }

    static void testIrSpecFormatNotLegacy() {
        System.out.print("  testIrSpecFormatNotLegacy... ");
        CompileResult cr = compile("""
            function f(a: int[], b: int | null, c: string | null): null { return; }
            """);
        String ir = dumpIR(cr);
        assertContains(ir, "[int]", "array uses spec [T] format");
        assertContains(ir, "?int", "nullable uses spec ?T format");
        assertContains(ir, "?string", "second nullable uses spec ?T format");
        assertNotContains(ir, "int[]", "no legacy int[] format");
        assertNotContains(ir, "|null", "no legacy |null format");
        System.out.println("OK");
    }

    static void testIrArrayDescriptors() {
        System.out.print("  testIrArrayDescriptors... ");
        CompileResult cr = compile("""
            function f(a: int[], b: string[]): null { return; }
            """);
        String ir = dumpIR(cr);
        assertContains(ir, "param a: [int]", "array param of int uses [int]");
        assertContains(ir, "param b: [string]", "array param of string uses [string]");
        assertNotContains(ir, "int[]", "no legacy array format");
        assertNotContains(ir, "string[]", "no legacy array format");
        System.out.println("OK");
    }

    static void testIrNullableDescriptorsOnParams() {
        System.out.print("  testIrNullableDescriptorsOnParams... ");
        CompileResult cr = compile("""
            function f(a: int | null, b: string | null): null { return; }
            """);
        String ir = dumpIR(cr);
        assertContains(ir, "param a: ?int", "nullable int param uses ?int");
        assertContains(ir, "param b: ?string", "nullable string param uses ?string");
        assertNotContains(ir, "|null", "no legacy |null format");
        System.out.println("OK");
    }

    static void testIrClassDescriptors() {
        System.out.print("  testIrClassDescriptors... ");
        CompileResult cr = compile("""
            class User { name: string = ""; }
            function makeUser(): User { return { name: "Ada" }; }
            let f: User = makeUser();
            """);
        String ir = dumpIR(cr);
        assertContains(ir, "@test/User", "class uses @module/Class format");
        assertContains(ir, "object : @test/User", "object literal shows class descriptor");
        System.out.println("OK");
    }

    static void testIrFunctionDescriptorsOnIdents() {
        System.out.print("  testIrFunctionDescriptorsOnIdents... ");
        CompileResult cr = compile("""
            function add(a: int, b: int): int { return a + b; }
            function process(x: int, s: string): boolean { return true; }
            let f: (a: int, b: int) => int = add;
            let g: (x: int, s: string) => boolean = process;
            """);
        String ir = dumpIR(cr);
        assertContains(ir, "(int,int)->int", "function type descriptor (int,int)->int");
        assertContains(ir, "(int,string)->boolean", "function type descriptor (int,string)->boolean");
        System.out.println("OK");
    }

    static void testIrNestedArrayDescriptors() {
        System.out.print("  testIrNestedArrayDescriptors... ");
        CompileResult cr = compile("""
            function f(a: int[][]): null { return; }
            """);
        String ir = dumpIR(cr);
        assertContains(ir, "[[int]]", "nested array uses [[int]]");
        assertNotContains(ir, "int[][]", "no legacy nested array format");
        System.out.println("OK");
    }

    static void testIrNullableArrayParam() {
        System.out.print("  testIrNullableArrayParam... ");
        CompileResult cr = compile("""
            function f(a: int[] | null): null { return; }
            """);
        String ir = dumpIR(cr);
        assertContains(ir, "?[int]", "nullable array uses ?[int]");
        assertNotContains(ir, "int[]|null", "no legacy format");
        System.out.println("OK");
    }

    static void testIrArrayOfNullableInt() {
        System.out.print("  testIrArrayOfNullableInt... ");
        CompileResult cr = compile("""
            function f(a: (int | null)[]): null { return; }
            """);
        String ir = dumpIR(cr);
        assertContains(ir, "[?int]", "array of nullable int uses [?int]");
        System.out.println("OK");
    }

    static void testIrNullReturnFunctionDescriptor() {
        System.out.print("  testIrNullReturnFunctionDescriptor... ");
        CompileResult cr = compile("""
            function noop(): null { return; }
            let f: () => null = noop;
            """);
        String ir = dumpIR(cr);
        assertContains(ir, "()->null", "null-return function uses ()->null");
        System.out.println("OK");
    }

    static void testIrNullableReturnFunctionDescriptor() {
        System.out.print("  testIrNullableReturnFunctionDescriptor... ");
        CompileResult cr = compile("""
            function maybe(x: int): int | null { if (x === 0) { return null; } return x; }
            let f: (x: int) => int | null = maybe;
            """);
        String ir = dumpIR(cr);
        assertContains(ir, "(int)->?int", "nullable-return function uses (int)->?int");
        assertNotContains(ir, "|null", "no legacy |null format in function descriptor");
        System.out.println("OK");
    }

    static void testIrAsyncFuncDescriptor() {
        System.out.print("  testIrAsyncFuncDescriptor... ");
        CompileResult cr = compile("""
            async function fetch(x: int): string { return ""; }
            export function probe(): null {
              let f: async (x: int) => string = fetch;
              return;
            }
            """);
        String ir = dumpIR(cr);
        assertContains(ir, "ident fetch : async(int)->string", "async function type uses async prefix");
        System.out.println("OK");
    }

    static void testIrZeroParamDescriptor() {
        System.out.print("  testIrZeroParamDescriptor... ");
        CompileResult cr = compile("""
            function sum2(): null { return; }
            let g: () => null = sum2;
            """);
        String ir = dumpIR(cr);
        assertContains(ir, "()->null", "zero-param function uses ()->null");
        System.out.println("OK");
    }

    static void testIrFixedParamsDescriptor() {
        System.out.print("  testIrFixedParamsDescriptor... ");
        CompileResult cr = compile("""
            function describe(sep: string, values: int[]): null { return; }
            let d: (sep: string, values: int[]) => null = describe;
            """);
        String ir = dumpIR(cr);
        assertContains(ir, "(string,[int])->null", "fixed params descriptor (string,[int])->null");
        assertNotContains(ir, "...", "no rest arm in v1.2 descriptors");
        System.out.println("OK");
    }

    static void testIrArrayParamDescriptor() {
        System.out.print("  testIrArrayParamDescriptor... ");
        CompileResult cr = compile("""
            function sum(values: int[]): int { return 0; }
            let f: (values: int[]) => int = sum;
            """);
        String ir = dumpIR(cr);
        assertContains(ir, "([int])->int", "array param uses [int] descriptor");
        assertNotContains(ir, "...", "no rest arm in v1.2 descriptors");
        System.out.println("OK");
    }

    static void testIrBytesRows() {
        System.out.print("  testIrBytesRows... ");
        CompileResult cr = compile("""
            function f(b: bytes): bytes { return b; }
            function g(a: bytes[], m: bytes | null): null { return; }
            """);
        String ir = dumpIR(cr);
        assertContains(ir, "param b: bytes", "bytes param uses the bytes primitive");
        assertContains(ir, "function f: bytes", "bytes return row uses the bytes primitive");
        assertContains(ir, "[bytes]", "bytes array uses [bytes]");
        assertContains(ir, "?bytes", "nullable bytes uses ?bytes");
        System.out.println("OK");
    }

    // =========================================================================
    // Stub module resolver
    // =========================================================================

    static class ModuleResolverStub implements ModuleResolver {
        @Override
        public Map<String, Type> resolveModule(String modulePath, String importingModule,
                                                Set<String> modulesInProgress)
                throws ModuleNotFoundException {
            return Map.of();
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className, String modulePath,
                                                      String importingModule)
                throws ModuleNotFoundException {
            return null;
        }
    }
}
