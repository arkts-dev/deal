package deal.test;

import deal.checker.*;
import deal.descriptors.*;
import deal.lexer.Lexer;
import deal.module.ModuleShapeValidator;
import deal.parser.Parser;
import deal.types.*;
import deal.codegen.Backend;
import java.nio.file.*;
import java.util.*;

/** Independent source contracts for legal data types and compositional flow. */
public final class TypeLegalityFlowTest {
    static void check(String source, String code) {
        var lex = new Lexer(source, "flow.deal").tokenize();
        var parse = new Parser(lex.tokens(), "flow.deal").parse();
        var errors = new ArrayList<>(lex.diagnostics());
        errors.addAll(parse.diagnostics());
        errors.addAll(ModuleShapeValidator.validate(parse.program(), "flow.deal", false));
        if (errors.isEmpty()) {
            var resolver = new NameResolver("flow.deal", new StubModuleResolver());
            var symbols = resolver.resolve(parse.program());
            errors.addAll(resolver.diagnostics());
            if (errors.isEmpty()) errors.addAll(TypeChecker.check("flow.deal", symbols, resolver, parse.program()).diagnostics());
        }
        if (code == null ? !errors.isEmpty() : errors.stream().noneMatch(e -> e.code().equals(code)))
            throw new AssertionError("expected " + code + " for " + source + "\n" + errors);
        if (errors.stream().anyMatch(e -> e.code().equals("E6005"))) throw new AssertionError(errors);
    }
    private static void productionReject(String statement, Path root) throws Exception {
        for (Backend backend : Backend.values()) {
            Path project = root.resolve("reject-" + backend);
            Files.createDirectories(project);
            Path entry = project.resolve("main.deal");
            Files.writeString(entry, "function v():void{}export function main():void{" + statement + "}");
            var invocation = deal.semantic.CompilerProfileProvider.resolve(
                deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
            var compiler = new deal.module.CompilationOrchestrator(entry.toAbsolutePath(),
                project.resolve("out"), false, false, false, false, backend, null,
                List.of(project.toAbsolutePath()), Path.of("").toAbsolutePath(), null, invocation);
            compiler.compile();
            if (compiler.semanticEmissionCount() != 0 || compiler.diagnostics().isEmpty()
                    || compiler.diagnostics().stream().anyMatch(e -> !e.code().equals("E3001")))
                throw new AssertionError("frontend rejection " + backend + ": " + compiler.diagnostics());
        }
    }
    private static void illegal(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("illegal data type constructed");
    }
    private static void bindingOwnership() {
        for (String inner : List.of(
                "{let x:string|null=\"s\";if(x===null){return \"\";}}",
                "{{let x:string|null=\"s\";if(x===null){return \"\";}}}",
                "try{let x:string|null=\"s\";if(x===null){return \"\";}}catch(e){}",
                "try{}catch(e){let x:string|null=\"s\";if(x===null){return \"\";}}")) {
            check("function f(x:string|null):string{" + inner + "return x;}", "E5003");
            check("function f(x:number):number{" + inner.replace("return \"\"", "return 0") + "return x;}", null);
        }
        check("function f(x:unknown):string{{let x:unknown=\"s\";if(typeof x!==\"string\"){return \"\";}}return x;}", "E5003");
        check("function f(x:string|undefined):string{{let x:string|undefined=\"s\";if(x===undefined){return \"\";}}return x;}", "E5003");
        check("class C{n:number;}function f(x:unknown):C{{let x:unknown=null;if(!(x instanceof C)){throw {name:\"X\",message:\"x\"};}}return x;}", "E5003");
        check("function f(x:unknown):Error{try{throw {name:\"X\",message:\"x\"};}catch(x){if(!(x instanceof Error)){throw {name:\"X\",message:\"x\"};}}return x;}", "E5003");
        check("function f(x:string|null):string{if(x!==null){{let x:string|null=null;if(x!==null){return \"\";}}return x;}return \"\";}", null);
        check("async function tick():Promise<void>{}async function f(x:string|null):Promise<string>{if(x!==null){{let x:string|null=\"s\";await tick();if(x===null){return \"\";}}return x;}return \"\";}", "E5003");
        // Symbol records may be value-equal; refinement ownership still uses identity.
        var first = new Symbol.VariableSymbol("x", Type.Unknown.INSTANCE, false);
        var second = new Symbol.VariableSymbol("x", Type.Unknown.INSTANCE, false);
        var facts = new NullNarrowing();
        facts.narrow(first, Type.String.INSTANCE);
        facts.narrow(second, Type.Number.INSTANCE);
        if (facts.getNarrowedType(first) != Type.String.INSTANCE) throw new AssertionError("identity ownership");
        facts.onAssignment(second);
        if (facts.getNarrowedType(first) != Type.String.INSTANCE || facts.getNarrowedType(second) != null)
            throw new AssertionError("identity invalidation");
        var sibling = new NullNarrowing();
        sibling.narrow(second, Type.String.INSTANCE);
        facts.merge(sibling);
        if (!facts.isEmpty()) throw new AssertionError("identity join");
    }

    public static void main(String[] args) throws Exception {
        bindingOwnership();
        for (String expression : List.of("x!==null && x>0", "x===null || x>0", "!(x===null) && x>0"))
            check("function f(x:number|null):boolean{return " + expression + ";}", null);
        for (String condition : List.of("x!==null && x!==undefined", "!(x===null || x===undefined)"))
            check("function f(x:string|null|undefined):string{if(" + condition + "){return x;}return \"\";}", null);
        check("function f(x:string|null|undefined):string{if(x===null || x===undefined){return \"\";}return x;}", null);
        check("function f(x:string|null):string{if(x===null){throw {name:\"X\",message:\"x\"};}return x;}", null);
        check("function f(x:string|null):string{if(x!==null){}else{return \"\";}return x;}", null);
        check("function f(x:string|null):string{if(x===null){return \"\";x=null;}return x;}", null);
        check("function f(x:string|null):string{return x!==null && true?x:\"\";}", null);
        check("function f(x:unknown):number{if(typeof x===\"number\" && x>0){return x;}return 0;}", null);
        check("class C{n:number;}function f(x:unknown):number{if(x instanceof C && x.n>0){return x.n;}return 0;}", null);
        check("function f(x:string|null):string{if(x!==null){}return x;}", "E5003");
        check("function f(x:string|null):boolean{if(x===null){return false;}return x===null;}", null);
        check("function f(x:number[]|null,y:number[]|null):boolean{if(x===null){return false;}return x===y;}", null);
        check("function f(x:number,y:string):boolean{return x===y;}", "E3006");
        check("function f(x:string|null,b:boolean):string{if(x===null && b){return \"\";}return x;}", "E5003");
        check("function f(x:string|null):string{if(x!==null){x=null;return x;}return \"\";}", "E5003");
        check("function f(x:string|null):string{if(x!==null && ((x=null)===null)){return x;}return \"\";}", "E5003");
        check("function f(x:string|null):string{if(x!==null){let x=null;return x;}return \"\";}", "E5003");
        check("function f(x:string|null):void{if(x!==null){let g=():string=>x;}}", "E5003");
        check("async function tick():Promise<void>{}async function f(x:string|null):Promise<string>{if(x!==null){await tick();return x;}return \"\";}", "E5003");
        check("function f(x:string|null):void{while(x!==null){if(x===\"\"){break;}let s:string=x;x=null;continue;}}", null);
        check("function f(x:string|null):void{while(x!==null){x=null;let s:string=x;}}", "E3001");
        check("function f(x:boolean|null):void{if(x!==null){while(x){break;}}}", null);
        check("function f(x:number|null):void{while(x!==null && x>0){let n:number=x;break;}}", null);
        check("function f(x:string|null):void{while(x!==null && ((x=null)===null)){let s:string=x;}}", "E3001");
        check("async function tick():Promise<boolean>{return true;}async function f(x:string|null):Promise<void>{while(x!==null && await tick()){let s:string=x;}}", "E3001");
        check("function f(x:string|null):void{if(x!==null){while(true){let x=\"inner\";break;}let s:string=x;}}", null);
        check("function f(x:string|null):void{if(x!==null){while(true){let s:string=x;break;}}}", "E3001");
        check("function f(x:string|null):string{if(x!==null){while((x=null)===null){break;}return x;}return \"\";}", "E5003");
        check("function f(x:string|null):string{try{if(x===null){throw {name:\"X\",message:\"x\"};}}catch(e){}return x;}", "E5003");
        check("function f(x:string|null):string{try{if(x===null){throw {name:\"X\",message:\"x\"};}}catch(e){return \"\";}return x;}", null);
        check("function f(x:string|null):string{try{x=\"s\";}catch(e){}return x;}", "E5003");
        for (String statement : List.of("[v()];", "let xs=[v()];", "let xs=[[v()]];", "let xs:unknown=[v()];", "let xs=[true?v():v()];", "for(let x of [v()]){}"))
            check("function v():void{}function f():void{" + statement + "}", "E3001");
        check("function v():void{}function f():unknown{return [v()];}", "E3001");
        check("function f():void{let fs:(()=>void)[]=[():void=>{}];fs[0]();}", null);
        check("function f():void{let g:()=>void=function():void{JSON.parse(\"1\");};g();}", null);
        check("function take(g:()=>void):void{}function f():void{take(function():void{true?JSON.parse(\"1\"):JSON.parse(\"2\");});}", null);
        check("function f():void{let g:()=>Promise<void>=async function():Promise<void>{JSON.parse(\"1\");};}", null);
        check("function f():void{let g:()=>void=():void=>{JSON.parse(\"1\");};}", null);
        illegal(() -> new Type.Array(Type.Void.INSTANCE));
        illegal(() -> Types.array(Type.Void.INSTANCE));
        illegal(() -> new Type.Func(List.of(Type.Void.INSTANCE), Type.Number.INSTANCE));
        var signature = new Type.Func(List.of(), Type.Void.INSTANCE);
        Types.array(signature);
        for (String text : List.of("void", "[void]", "[[void]]", "(void)->number", "([void])->void", "?void")) {
            if (!(CanonicalRuntimeTypeDescriptor.parse(text) instanceof DescriptorSyntaxError)) throw new AssertionError(text);
        }
        for (String text : List.of("()->void", "[()->void]", "async()->void", "(()->void)->void")) {
            if (!(CanonicalRuntimeTypeDescriptor.parse(text) instanceof DescriptorAst)) throw new AssertionError(text);
        }
        if (!(CanonicalRuntimeTypeDescriptor.parseResult("void") instanceof DescriptorAst)) throw new AssertionError("void result");
        Path root = Files.createTempDirectory("type-legality-flow-");
        try {
            for (String statement : List.of("[v()];", "let xs=[[v()]];", "let xs:unknown=[v()];", "let xs=[true?v():v()];"))
                productionReject(statement, root);
            for (String fixture : List.of("arrays/reference-array-identity-equality", "variables/block-shadowing-no-narrowing", "variables/parameter-shadowing-no-narrowing")) {
                String original = Files.readString(Path.of("test/conformance/backend-runtime/" + fixture + ".deal"));
                check(original, null);
                String testName = switch (fixture) {
                    case "arrays/reference-array-identity-equality" -> "test_array_identity_equality";
                    case "variables/block-shadowing-no-narrowing" -> "test_block_shadow";
                    default -> "test_parameter_shadow";
                };
                String executed = original.replace("export function main(): void {", "export function main(): void {" + testName + "();");
                CompositionProductionTest.execute(executed, "", root.resolve(fixture), null,
                    List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            }
            String source = "function positive(x:number|null):boolean{return x!==null && x>0;}"
                + "function text(x:string|null|undefined):string{if(x===null || x===undefined){return \"empty\";}return x;}"
                + "class C{n:number;}function value(x:unknown):number{if(x instanceof C && x.n>0){return x.n;}return 0;}"
                + "function shadow(x:number):number{{let x:string|null=\"s\";if(x===null){return 0;}}return x;}"
                + "function restored(x:string|null):string{if(x!==null){{let x:string|null=null;if(x!==null){return \"bad\";}}return x;}return \"empty\";}"
                + "export function main():void{"
                + "if(shadow(7)!==7||restored(\"yes\")!==\"yes\"||restored(null)!==\"empty\"){throw {name:\"FAIL\",message:\"binding ownership\"};}"
                + "if(!positive(2)||positive(null)||positive(-1)||text(null)!==\"empty\"||text(undefined)!==\"empty\"||text(\"yes\")!==\"yes\"){throw {name:\"FAIL\",message:\"flow\"};}"
                + "let c:C={n:7};if(value(c)!==7||value(null)!==0){throw {name:\"FAIL\",message:\"nominal\"};}"
                + "let calls=0;let fs:(()=>void)[]=[():void=>{calls=calls+1;}];fs[0]();"
                + "let g:()=>void=function():void{JSON.parse(\"1\");};g();"
                + "let x:number|null=1;let clear=():boolean=>{x=null;return true;};let rejected=false;"
                + "try{if(x!==null && clear() && x>0){}}catch(e){rejected=true;}"
                + "if(!rejected||calls!==1){throw {name:\"FAIL\",message:\"stale or void function\"};}}";
            CompositionProductionTest.execute(source, "", root, null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
        } finally { CompositionProductionTest.remove(root); }
        System.out.println("Type legality and flow: frontend contracts and all four executed backends passed");
    }
}
