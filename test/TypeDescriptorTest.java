package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.identity.CanonicalClassIdentityIndex;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.ir.IrDumper;
import deal.lexer.*;
import deal.module.ModuleIdentityResolver;
import deal.parser.*;
import deal.types.Type;

import java.util.*;

public class TypeDescriptorTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Running TypeDescriptorTest (compiler IR descriptors) ===\n");

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
