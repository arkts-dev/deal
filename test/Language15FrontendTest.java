package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.lexer.*;
import deal.parser.*;
import deal.types.*;
import java.util.*;

/** Focused v1.5 frontend contracts, independent of backend execution. */
public final class Language15FrontendTest {
    private static void check(String source, boolean accepted) {
        var lex = new Lexer(source, "test.deal").tokenize();
        var parsed = new Parser(lex.tokens(), "test.deal", lex.directiveEvents()).parse();
        var diagnostics = new ArrayList<>(lex.diagnostics());
        diagnostics.addAll(parsed.diagnostics());
        if (diagnostics.isEmpty()) {
            var resolver = new NameResolver("test.deal", new StubModuleResolver());
            var scope = resolver.resolve(parsed.program());
            diagnostics.addAll(resolver.diagnostics());
            if (diagnostics.isEmpty()) diagnostics.addAll(TypeChecker.check("test.deal", scope, resolver, parsed.program()).diagnostics());
        }
        if (diagnostics.isEmpty() != accepted) throw new AssertionError(source + "\n" + diagnostics);
    }
    public static void main(String[] args) {
        check("function f(): void { return; }", true);
        check("function f(): void {}", true);
        check("function f(): null { return; }", false);
        check("function f(): null {}", false);
        check("function f(): void { return null; }", false);
        check("function f(x: void): void {}", false);
        check("function f(): void { let x: void = undefined; }", false);
        check("class C { x: void; }", false);
        check("function f(): void { let x: void[] = []; }", false);
        check("function f(): void { let x: null | undefined = null; let y: null | undefined = x; }", true);
        check("function f(): void { let x: undefined | null = undefined; let y: null | undefined = x; }", true);
        check("function f(): void { let x: null | undefined | undefined = undefined; }", false);
        check("function f(): void { let x = has({}); }", false);
        check("function f(): void { let has: number = 1; }", true);
        check("function f(): void {} function g(): void { let x = f(); }", false);
        check("function f(): void {} function g(): void { let x: unknown = f(); }", false);
        check("function f(): void {} function g(): void { let x: unknown[] = [f()]; }", false);
        check("function f(): void {} function g(): void { let x: Record<string, unknown> = {value: f()}; }", false);
        check("function f(): void { let x: unknown = undefined; let y: unknown | null | undefined = x; }", true);
        check("function f(x: null | undefined): void { if (x !== undefined) { let y: null = x; } }", true);
        check("function f(x: null | undefined): void { if (!(undefined !== x)) { let y: undefined = x; } }", true);
        check("function f(x: string | null | undefined): void { if (!(x === null)) { let y: string = x; } }", false);
        check("function f(x: unknown): void { if (!(typeof x !== \"number\")) { let y: number = x; } }", true);
        check("async function f(): Promise<void> { return; }", true);
        check("async function f(): null { return null; }", false);
        check("function f(): Promise<null> { return null; }", false);
        check("function f(): null { let t: Record<string, unknown> = {}; let x = t.x; let y = x as string; return null; }", true);
        check("function f(): null { let t: Record<string, unknown> = {}; let x: string = t.x; return null; }", false);
        check("function f(): null { let t: table = {}; return null; }", false);
        check("function f(): null { for (let x of [1,2]) { let y: number = x; } return null; }", true);
        check("function f(): null { for (let x: number of [1,2]) {} return null; }", false);
        check("function f(): null { let x: number | undefined = undefined; if (x !== undefined) { let y: number = x; } return null; }", true);
        check("function f(x: unknown): void { if (typeof x === \"string\") { let y: string = x; } }", true);
        check("function f(): void { try { throw { code: \"E\", message: \"x\" }; } catch(e) { if(e instanceof Error) { let m: string = e.message; } } }", true);
        check("function f(): null { let x = 0123; return null; }", false);
        check("function f(): null { return\nnull; }", false);
        check("function f(): null { throw/*\n*/{ code: \"E\", message: \"x\" }; }", false);
        check("async\nfunction f(): Promise<void> {}", false);
        check("function f(): null { let switch = 1; return null; }", false);
        check("function f(): null { let Object = 1; return null; }", false);
        check("function f(): null { let t: Record<string, unknown> = {}; let k = \"x\"; let b = Object.hasOwn(t,k); return null; }", true);
        check("class C { x?: string; } function f(): null { let c: C = {}; let x: string | undefined = c.x; return null; }", true);
        check("function f(x: unknown): void { if (!(x instanceof Error)) {} else { let code: string = x.code; } }", true);
        check("function f(x: unknown): void { if (!(x instanceof Error)) { let code: string = x.code; } }", false);
        check("function f(x: unknown): void { if (!!(x instanceof Error)) { let code: string = x.code; } }", true);
        check("function f(x: unknown): void { if (typeof x === \"number\") { x = \"changed\"; let y: number = x; } }", false);
        check("function f(x: unknown): void { if (typeof x === \"number\") { let g: () => number = function(): number { return x; }; } }", false);
        check("async function tick(): Promise<void> {} async function f(x: unknown): Promise<void> { if (typeof x === \"number\") { await tick(); let y: number = x; } }", false);
        check("function tick(): void {} function f(x: unknown): void { if (typeof x === \"number\") { tick(); let y: number = x; } }", true);
        check("function f(x: unknown): void { if (typeof x === \"object\") { let y: Record<string, unknown> = x; } }", false);
        check("function f(x: unknown): void { if (typeof x === \"function\") { let y: () => void = x; } }", false);
        check("function f(): void { let r: Record<string, unknown> = {}; if (Object.hasOwn(r, \"x\")) { let value: number = r.x; } }", false);
        System.out.println("Language15FrontendTest: passed");
    }
}
