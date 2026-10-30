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
        var diagnostics = diagnostics(source);
        if (diagnostics.isEmpty() != accepted) throw new AssertionError(source + "\n" + diagnostics);
    }
    private static List<deal.diagnostics.CompilerDiagnostic> diagnostics(String source) {
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
        return diagnostics;
    }
    private static void rejectsAt(String source, String token, String code) {
        var diagnostics = diagnostics(source);
        int column = source.indexOf(token) + 1;
        if (diagnostics.size() != 1 || !diagnostics.getFirst().toString().contains("ERROR " + code + ":")
                || !diagnostics.getFirst().toString().contains(":1:" + column + "-"))
            throw new AssertionError(source + "\n" + diagnostics);
    }
    public static void main(String[] args) {
        check("function f(): void { return; }", true);
        check("function f(): void {}", true);
        check("function f(): null { return; }", false);
        check("function f(): null {}", false);
        check("function f(): void { return null; }", false);
        check("function v():void{} function f():void{return v();}", false);
        check("async function v():Promise<void>{} async function f():Promise<void>{return await v();}", false);
        check("function f():(void){}", false);
        check("async function f():Promise<(void)>{}", false);
        check("function f():()=>void{return function():void{};}", true);
        check("function v():void{} function f():void{v();return;}", true);
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
        check("function f(a: unknown, b: unknown): boolean { return a === b; }", true);
        check("function f(x: unknown): void { while (typeof x === \"number\") { let y: number = x; x = \"done\"; } }", true);
        check("function f(x: number | null): void { while (x !== null) { let y: number = x; x = null; } }", true);
        check("function f(x: unknown): void { while (x instanceof Error) { let y: string = x.code; x = null; } }", true);
        check("function f(x: unknown): void { while (typeof x === \"number\") { x = \"done\"; let y: number = x; } }", false);
        check("async function tick(): Promise<void> {} async function f(x: unknown): Promise<void> { while (typeof x === \"number\") { await tick(); let y: number = x; } }", false);
        check("function f(): void { let x: unknown | null | null = 1; }", false);
        check("function f(): void { let x: (unknown | undefined) | undefined = 1; }", false);
        check("function f(): void { let x: null | null | unknown = 1; }", false);
        check("function f(): void { let x: (switch: number) => number; }", false);
        check("function f(): void { let x: (undefined: number) => number; }", false);
        check("function f(): void { let x: (x$y: number) => number; }", false);
        check("function eval(): void {}", false);
        check("function f(arguments: number): void {}", false);
        check("class C { return: number; Object: number; undefined: number; switch: number; } function f(): void { let c: C = { return: 1, Object: 2, undefined: 3, switch: 4 }; let x: number = c.return; }", true);
        check("function f(): void { let r: Record<string, unknown> = { delete: 1, as: 2, typeof: 3 }; let x: unknown = r.delete; }", true);
        for (String name : List.of("async", "as", "from", "of")) {
            check("function f(): void { let " + name + " = 1; let x = " + name + "; }", true);
            check("function " + name + "(" + name + ":number):number{return " + name + ";}", true);
        }
        check("function f():void {for(let of of [1]){let from=of;}}", true);
        check("function f():void {let from:unknown=1;let as=from as number;as;let async=as;async;}", true);
        rejectsAt("function f():void {late();function late():void{}}", "late()", "E2001");
        rejectsAt("class C{x:number;} function f():void{let u:unknown=C;}", "C;", "E3001");
        rejectsAt("function f():void{let a:unknown[]=[1];delete a[0];}", "delete", "E3017");
        check("import * as from from \"std/math\"; function f():number{return from.floor(1.5);}", true);
        check("import * as as from \"std/math\"; function f():void{let value:unknown=as;}", false);
        check("function f():void {let r:Record<string,unknown>={};}", true);
        check("function f():void {let r:Record<string,unknown>={};}\n// @jsonable\nexport class C{x:number;}", true);
        check("function f():void {let r:Record<string,unknown> = {a:1,a:2};}", false);
        check("class C{x:number;} function f():void {let c:C={x:1,x:2};}", false);
        check("function f():void {let a:number[]=[1];delete a[0];}", false);
        check("function f():void {let a:unknown[]=[1];delete a[0];}", false);
        check("function f():void {g();function g():void{}}", false);
        check("function f():void {let h:()=>void=function():void{g();};function g():void{}h();}", false);
        check("function f():void {function g():void{g();}g();}", true);
        check("function g():number{return 1;} function f():void {let x=g();function g():number{return 2;}let y=g();}", true);
        check("class C{x:number;} function f():void{let x:unknown=C;}", false);
        check("class C{x:number;} function f():void{let x=typeof C;}", false);
        rejectsAt("function f():number{return 1;} function g():void{f=f;}", "f=f", "E3017");
        rejectsAt("function g():void{function f():void{}f=f;}", "f=f", "E3017");
        rejectsAt("import * as math from \"std/math\"; function g():void{math.floor=math.floor;}", "math.floor=", "E3017");
        check("function f(f:number):number{return f;}", true);
        check("function g():void{function f(f:number):number{return f;}let x=f(1);}", true);
        check("function f():number{return 1;} function g():void{let h:()=>number=f;h=f;}", true);
        System.out.println("Language15FrontendTest: passed");
    }
}
