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

    private static void boundedNarrowing() {
        for (String body : List.of(
                "let x:number|null=1;let n:number=x;x=null;let z:null=x;x=2;n=x;",
                "let x:unknown=1;let n:number=x;x=\"s\";let s:string=x;",
                "let x:number|null=null;x=1;let n:number=x;",
                "let f:(n:number)=>number=():number=>7;let n:number=f(1);",
                "let f:((n:number)=>number)|null=(n:number):number=>7;let n:number=f(1);f=null;f=(n:number):number=>8;n=f(2);",

                "let x:number|null=1;let g=():void=>{x=null;};g();let n:number=x;"))
            check("function f0():void{" + body + "}", null);
        check("function f(x:number|null):number{x=1;return x;}", null);
        check("function f():void{let x:number|null=1;x=\"s\";}", "E3001");
        check("function f():void{let x:unknown=\"bad\";let n=x as number;}", "E3001");
        check("function f():void{let x:number|null=1;let g=():number=>x;}", "E5003");
        String classes = "class C{n:number;}class P{value:string|null;opt?:string|null;data:unknown;child:C|null;fn:((n:number)=>number)|null;nums:number[]|null;next:P|null;}";
        for (String body : List.of(
                "if(p.value!==null){return p.value;}return \"\";",
                "if(p.value===null){return \"\";}return p.value;",
                "if(null!==p.value){return p.value;}return \"\";",
                "if(!(p.value===null)){return p.value;}return \"\";",
                "if(p.opt!==null && p.opt!==undefined){return p.opt;}return \"\";",
                "if(p.opt===null || p.opt===undefined){return \"\";}return p.opt;",
                "if(typeof p.data===\"string\"){return p.data;}return \"\";",
                "if(p.value!==null){if(p.value===null){return \"\";}return p.value;}return \"\";",
                "return p.value!==null?p.value:\"\";"))
            check(classes + "function f(p:P):string{" + body + "}", null);
        for (String body : List.of(
                "if(p.value!==null){q.value=null;return p.value;}",
                "if(p.value!==null){let alias=p;alias.value=null;return p.value;}",
                "if(p.value!==null){let n=0;n=1;return p.value;}",
                "if(p.value!==null){ping();return p.value;}",
                "if(p.value!==null){Object.keys(p);return p.value;}",
                "if(p.value!==null){Object.hasOwn(p,\"value\");return p.value;}",
                "if(p.value!==null){delete p.opt;return p.value;}",
                "if(p.value!==null){p=q;return p.value;}",
                "if(p.value!==null){return q.value;}",
                "if(p.value!==null){let g=():string=>p.value;}",
                "if(p.value!==null){while(true){return p.value;}}",
                "if(p.value!==null){for(let n of [1]){return p.value;}}",
                "if(p.value!==null && ping()){return p.value;}",
                "if(p.value!==null && ((q.value=null)===null)){return p.value;}",
                "if(p.value!==null){try{ping();}catch(e){}return p.value;}",
                "if(p.value!==null){while(ping()){break;}return p.value;}",
                "if(p.value!==null){try{ping();}catch(e){return p.value;}}",
                "if(p.next!==null && p.next.value!==null){return p.next.value;}"))
            check(classes + "function ping():boolean{return true;}function f(p:P,q:P):string{" + body + "return \"\";}", "E5003");
        check(classes + "function f(p:P):void{if(p.value!==null){p.value=null;let s:string=p.value;}}", "E3001");
        check(classes + "function f(p:P):void{if(p.data instanceof C){let n:number=p.data.n;}if(p.fn!==null){let n:number=p.fn(1);}if(p.nums!==null){let n:number=p.nums.length;}}", null);
        check(classes + "function f(p:P):void{while(p.value!==null){let s:string=p.value;break;}}", null);
        check(classes + "function f(p:P):void{if(p.value!==null){let s:string=p.value;let alias=p;let t:string=alias.value;}}", "E3001");
        check(classes + "async function tick():Promise<void>{}async function f(p:P):Promise<void>{if(p.value!==null){await tick();let s:string=p.value;}}", "E3001");
        for (String expression : List.of("b?u:1", "b?1:u", "u??1", "n??u"))
            check("function f(b:boolean,u:unknown,n:number|null):void{let x=" + expression + ";let y:unknown=x;}", null);
        check("function f(b:boolean):void{let x=b?1:\"s\";}", "E3006");
        check("function f(n:number|null):void{let x=n??\"s\";}", "E3006");
        check("function v():void{}function f(b:boolean,u:unknown):void{let x=b?u:v();}", "E3006");
    }

    private static void coalescingLiteralContext() {
        String classes = "class Item{value:number;child?:Item;}class Other{value:number;}";
        for (String body : List.of(
                "let x=a??[];let n:number[]=x;",
                "let x=a??([]);let n:number[]=x;",
                "let x=a??[1];let n:number[]=x;",
                "let x=c??{value:1};let n:Item=x;",
                "let x=cs??[{value:1}];let n:Item[]=x;",
                "let x=ns??[[]];let n:number[][]=x;",
                "let x=r??{};let n:Record<string,unknown>=x;",
                "let x=r??{value:1};let n:Record<string,unknown>=x;",
                "let x:unknown=c??{};",
                "let x:Other=c??{value:1};")) {
            // The last case must not retag the existing left value as the outer target.
            check(classes + "function f(a:number[]|null|undefined,c:Item|null,cs:Item[]|undefined,ns:number[][]|null,r:Record<string,unknown>|undefined):void{" + body + "}",
                body.startsWith("let x:Other") ? "E3006" : null);
        }
        for (String body : List.of(
                "let x=a??[\"bad\"];",
                "let x=c??{value:\"bad\"};",
                "let x=c??{};",
                "let x=c??{value:1,extra:2};",
                "let strings:string[]=[];let x=a??strings;",
                "let other:Other={value:1};let x=c??other;"))
            check(classes + "function f(a:number[]|null,c:Item|null):void{" + body + "}",
                body.contains("extra:") ? "E4002" : body.equals("let x=c??{};") ? "E4001"
                : body.contains("value:\"bad\"") ? "E4003"
                : body.contains("??[\"bad\"]") ? "E3011" : "E3006");
        for (String expression : List.of("null??[]", "undefined??{}", "u??[]", "n??[]", "b?a:[]", "a??(b?[]:[])", "[[1],[]]"))
            check("function f(a:number[]|null,u:unknown,n:number,b:boolean):void{let x=" + expression + ";}", "E3002");
        check("function f(a:number[]|null):void{let x=a??JSON.parse(\"[]\");let y:number[]=x;}", "E3001");
        check("function f(a:number[]|null):void{let x:unknown=a??[\"s\"];}", null);
    }

    private static void ternaryLiteralContext() {
        String prefix = "class Item{value:number;}function f(b:boolean,a:number[],c:Item,r:Record<string,unknown>):void{";
        for (String expression : List.of("b?a:[]", "b?[]:a", "b?c:{value:1}", "b?{value:1}:c",
                "b?r:{}", "b?{}:r", "b?[[1]]:[[]]", "b?[[]]:[[1]]", "b?[1]:[]", "b?[]:[1]"))
            check(prefix + "let x=" + expression + ";}", null);
        check(prefix + "let x=b?a:[\"bad\"];}", "E3011");
        check(prefix + "let x=b?[\"bad\"]:a;}", "E3011");
        check(prefix + "let x=b?{}:c;}", "E4001");
        for (String expression : List.of("b?[]:[]", "b?{}:{}", "b?null:[]", "b?JSON.parse(\"[]\"):[]"))
            check(prefix + "let x=" + expression + ";}", "E3002");
        check(prefix + "let x=[[1],[]];}", "E3002");
    }

    private static void contextualParameters() {
        check("function f(b:boolean,a:number,c:number):number{return b ? (a) : c;}", null);
        check("function f(b:boolean,a:number,c:number):number{return b ? (a) : (c);}", null);
        for (String body : List.of(
                "let g:(x:number)=>number=(x):number=>x+1;",
                "let g:(x:number)=>number=function(x):number{return x+1;};",
                "let g:(x:number,y:string)=>number=(x):number=>x;",
                "let g:((x:number)=>number)|null=(x):number=>x;",
                "let ys=xs.map((x,i,a):number=>x+i+a.length);",
                "let ys=xs.filter((x):boolean=>x>0);",
                "let ys=xs.find((x):boolean=>x>0);",
                "let ys=xs.some((x):boolean=>x>0);",
                "let ys=Array.from(xs,(x,i):number=>x+i);",
                "let g:(x:number)=>number=(x):number=>{let h=():number=>x;return h();};"))
            check("function f(xs:number[]):void{" + body + "}", null);
        check("function f():void{let g=(x):number=>1;}", "E3002");
        check("function f():void{let g:(x:number)=>number=(x,y):number=>x;}", "E3002");
        check("function f(xs:number[]):void{let g=xs.filter((x:string):boolean=>true);}", "E5001");
    }

    public static void main(String[] args) throws Exception {
        bindingOwnership();
        coalescingLiteralContext();
        ternaryLiteralContext();
        contextualParameters();
        check("function f(s:string):number{return Number(s);}", null);
        for (String value : List.of("1", "true", "null", "undefined", "[1]"))
            check("function f():void{let n=Number("+value+");}", "E5001");
        check("function f(x:unknown):number{return Number(x);}", "E5001");
        check("function f(x:Record<string,unknown>):number{return Number(x);}", "E5001");
        check("function f():void{let n=Number();}", "E3009");
        check("function f():void{let n=Number(\"1\",\"2\");}", "E3009");
        check("function f():void{let n=Number;}", "E3001");
        check("function f(xs:number[]):void{let a=xs.slice();let b=xs.slice(1);}", null);
        check("function f(xs:number[]):void{let a=xs.slice(undefined);}", "E5001");
        check("function f(xs:number[]):void{let a=xs.slice(1,2,3);}", "E3009");
        boundedNarrowing();
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
            String bounded = """
                class C { n:number; }
                class P { value:string|null; opt?:string|null; data:unknown; child:C|null; fn:((n:number)=>number)|null; nums:number[]|null; }
                function choose(b:boolean,u:unknown,n:number|null):unknown {let x=b?u:1;let y=n??u;return b?x:y;}
                function read(p:P):string {
                  if(p.value!==null){let v:string=p.value;if(p.value===null){return "bad";}return v;}
                  return "none";
                }
                function extract(p:P):number {
                  if(p.data instanceof C){return p.data.n;}return 0;
                }
                function length(p:P):number {if(p.nums!==null){return p.nums.length;}return 0;}
                function invoke(p:P):number {if(p.fn!==null){return p.fn(2);}return 0;}
                function optional(p:P):string {if(p.opt!==null && p.opt!==undefined){return p.opt;}return "none";}
                export function main():void {
                  let repeated:unknown=2;
                  while(typeof repeated==="number"){let n:number=repeated;if(n>0){repeated=n - 1;continue;}repeated="done";}
                  let state:number|null=2;while(state!==null){let n:number=state;state=null;}
                  let x:number|null=1;let first:number=x;x=null;let absent:null=x;x=3;
                  let count:number=x;let opaque:unknown=4;let known:number=opaque;
                  let adapted:(n:number)=>number=():number=>7;
                  let maybe:((n:number)=>number)|null=(n:number):number=>9;
                  if(maybe(3)!==9){throw {name:"FAIL",message:"nullable function"};}
                  let stale:number|null=1;let clear=():void=>{stale=null;};clear();let caught=false;
                  try{let rejected:number=stale;}catch(e){caught=(e as Error).name==="E8001";}
                  if(!caught){throw {name:"FAIL",message:"assignment read boundary"};}
                  let c:C={n:8};let p:P={value:"yes",data:c,child:c,fn:(n:number):number=>n+1,nums:[1,2]};
                  if(read(p)!=="yes"||optional(p)!=="none"||extract(p)!==8||length(p)!==2||invoke(p)!==3){throw {name:"FAIL",message:"property"};}
                  p.opt="present";if(optional(p)!=="present"){throw {name:"FAIL",message:"optional"};}
                  while(p.value!==null){let s:string=p.value;if(s!=="yes"){throw {name:"FAIL",message:"loop"};}break;}
                  let any:unknown=choose(true,"chosen",null);let selected:string=any as string;
                  if(selected!=="chosen"||first!==1||count!==3||known!==4||adapted(99)!==7){throw {name:"FAIL",message:"binding/join"};}
                  let target:unknown=choose(false,"fallback",null);if((target as string)!=="fallback"){throw {name:"FAIL",message:"coalesce"};}
                  let fresh:unknown=choose(false,"unused",6);if((fresh as number)!==6){throw {name:"FAIL",message:"coalesce selected"};}
                }
                """;
            CompositionProductionTest.execute(bounded, "", root.resolve("bounded"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
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
