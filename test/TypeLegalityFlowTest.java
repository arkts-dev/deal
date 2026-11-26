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
    private static void nullishArrayFlow() {
        for (String literal : List.of("[1,null]", "[null,1]", "[undefined,1,null]", "[null,undefined,1]", "[1,undefined,null]"))
            check("function f():void{let a=" + literal + ";let b:(number|null|undefined)[]=a;}", literal.contains("undefined") ? null : "E3001");
        check("function f():void{let a=[1,null];let b:(number|null)[]=a;}", null);
        check("function f():void{let a=[null,undefined];let b:(null|undefined)[]=a;}", null);
        check("function f():void{let a=[null];let b:null[]=a;let c=[undefined];let d:undefined[]=c;}", null);
        check("function f(x:number|null,y:number|undefined):void{let a=[x,y];let b:(number|null|undefined)[]=a;}", null);
        check("function f():void{let a=[\"x\",null];let b:(string|null)[]=a;let c=[true,undefined];let d:(boolean|undefined)[]=c;}", null);
        check("function f(a:readonly number[]):void{let x=[a,null];let y:((readonly number[])|null)[]=x;}", null);
        for (String literal : List.of("[1,\"x\"]", "[1,null,\"x\"]", "[[1],[null]]", "[true,null,1]"))
            check("function f():void{let x=" + literal + ";}", "E3011");
        check("function f():void{let x:unknown=[1,null];let a:(number|null)[]=x;}", null);
        check("function f():void{let x:unknown=[1,null,\"x\"];let a:unknown[]=x;}", null);
        check("function f():void{let x:number[]=[1,null];}", "E3011");
    }

    private static void nullishArrayRuntime(Path root) throws Exception {
        CompositionProductionTest.execute("""
            function opaque(x:unknown):unknown{return x;}
            export function main():void{
              let a=[null,undefined,1];let b:(number|null|undefined)[]=a;b.push(2);b.push(null);
              let c=[null,undefined];let d:(null|undefined)[]=c;d.push(undefined);
              let e=["yes",null];let f:(string|null)[]=e;f.push("next");
              if(a.length!==5||(a[2] as number)!==1||c.length!==3||(f[2] as string)!=="next"){throw {name:"FAIL",message:"nullish array inference"};}
              let caught=false;try{let wrong:number[]=opaque(a) as number[];}catch(err){caught=true;}
              if(!caught||a.length!==5){throw {name:"FAIL",message:"invariant nullish storage"};}
              caught=false;try{let erased:unknown[]=opaque(a) as unknown[];}catch(err){caught=true;}
              if(!caught){throw {name:"FAIL",message:"no storage erasure"};}
              let view:readonly unknown[]=a;if(view.length!==5){throw {name:"FAIL",message:"readonly view"};}
              let heterogeneous:unknown=[1,null,"x"];let items:unknown[]=heterogeneous;items.push(false);
              if(items.length!==4){throw {name:"FAIL",message:"unknown fallback"};}
            }
            """, "", "", root, null, List.of(Backend.values()), Map.of(), true);
    }

    private static void guardAliasFlow() {
        for (String guard : List.of("x!==null", "!(x===null)", "x!==null&&true", "x!==null||x!==null"))
            check("function f(x:string|null):string{const ok=" + guard + ";const alias=ok;if(!alias){return \"\";}return x;}", null);
        check("function f(x:unknown):string{const text=typeof x===\"string\";if(text){return x;}return \"\";}", null);
        check("class C{n:number;}function f(x:unknown):number{const yes=x instanceof C;if(yes){return x.n;}return 0;}", null);
        check("function f(x:string|null):void{let ok=x!==null;if(ok){let s:string=x;}}", "E3001");
        check("function f(x:string|null):void{const ok=x!==null;x=null;if(ok){let s:string=x;}}", "E3001");
        check("function f(x:string|null,b:boolean):void{const ok=x!==null;const alias=ok;if(b){x=null;}if(alias){let s:string=x;}}", "E3001");
        check("function f(x:string|null):void{const ok=x!==null;x=null;const alias=ok;x=\"new\";x=null;if(alias){let s:string=x;}}", "E3001");
        check("function f(r:Record<string,unknown>,k:string):void{const ok=typeof r[k]===\"string\";const alias=ok;if(alias){let s:string=r[k];}}", null);
        check("function f(r:Record<string,unknown>,k:string):void{const ok=typeof r[k]===\"string\";k=\"new\";if(ok){let s:string=r[k];}}", "E3001");
        check("function f(r:Record<string,unknown>,k:string):void{const ok=typeof r[k]===\"string\";const alias=ok;r.x=1;if(alias){let s:string=r[k];}}", "E3001");
        check("function f(x:string|null):void{const ok=x!==null;let g=():void=>{if(ok){let s:string=x;}};}", "E3001");
        check("async function tick():Promise<void>{}async function f(x:string|null):Promise<void>{const ok=x!==null;await tick();if(ok){let s:string=x;}}", "E3001");
        check("function f(x:string|null):void{const ok=x!==null;for(let i of [1]){x=null;}if(ok){let s:string=x;}}", "E3001");
    }

    private static void guardAliasRuntime(Path root) throws Exception {
        CompositionProductionTest.execute("""
            class C{n:number;}
            function text(x:string|null|undefined):string{
              const present=x!==null&&x!==undefined;const missing=!present;
              if(missing){return "missing";}return x;
            }
            function nominal(x:unknown):number{const yes=x instanceof C;const again=yes;if(again){return x.n;}return 0;}
            function record(r:Record<string,unknown>,k:string):number{const text=typeof r[k]==="string";const yes=text&&text;if(yes){return r[k].length;}return 0;}
            export function main():void{
              let c:C={n:7};if(text("yes")!=="yes"||text(null)!=="missing"||text(undefined)!=="missing"||nominal(c)!==7||nominal(null)!==0||record({x:"abc"},"x")!==3||record({},"x")!==0){throw {name:"FAIL",message:"aliases"};}
              let x:string|null="live";const ok=x!==null;let clear=():void=>{x=null;};clear();let caught=false;
              try{if(ok){let n=x.length;}}catch(e){caught=(e as Error).name==="E8001";}
              if(!caught){throw {name:"FAIL",message:"alias refined read validation"};}
            }
            """, "", "", root, null, List.of(Backend.values()), Map.of(), true);
    }

    private static void loopExitFlow() {
        check("function f(x:string|null):string{while(x===null){x=\"ready\";}return x;}", null);
        check("function f(x:string|null):string{while(true){if(x===null){x=\"ready\";}break;}return x;}", null);
        check("function f(x:string|null,b:boolean):string{while(true){if(b){break;}x=\"ready\";break;}return x;}", "E5003");
        check("function f(x:string|null):void{while(true){x=\"ready\";break;x=null;}let s:string=x;}", null);
        check("function f(x:string|null):void{while(true){while(true){break;}x=\"ready\";break;}let s:string=x;}", null);
        check("function f(x:string|null):void{for(let i of [1]){x=\"ready\";}let s:string=x;}", "E3001");
        check("function f():void{let x:string|null=\"ready\";for(let i of [1]){x=\"again\";continue;}let s:string=x;}", null);
        check("function f():void{let x:string|null=\"ready\";for(let i of [1]){x=null;continue;}let s:string=x;}", "E3001");
        check("function f(x:string|null):string{try{x=\"ready\";}catch(e){return \"caught\";}return x;}", null);
        check("function f(x:string|null):string{try{x=\"ready\";}catch(e){throw e as Error;}return x;}", null);
        check("function f(x:string|null):string{try{x=\"ready\";}catch(e){}return x;}", "E5003");
        check("function f(x:string|null):void{while(true){try{x=\"ready\";}catch(e){continue;}break;}let s:string=x;}", null);
        check("function f(x:string|null):void{while(true){let g=():void=>{while(true){break;}};x=\"ready\";break;}let s:string=x;}", null);
    }

    private static void loopExitRuntime(Path root) throws Exception {
        CompositionProductionTest.execute("""
            function ready(x:string|null):string{while(x===null){x="ready";}return x;}
            function breaks(x:string|null):string{while(true){if(x===null){x="break";}break;}return x;}
            function tried(x:string|null,fail:boolean):string{
              try{if(fail){throw {name:"X",message:"x"};}x="try";}catch(e){return "caught";}return x;
            }
            export function main():void{
              if(ready(null)!=="ready"||ready("live")!=="live"||breaks(null)!=="break"||tried(null,false)!=="try"||tried(null,true)!=="caught"){throw {name:"FAIL",message:"exit flow"};}
              let x:string|null="entry";for(let i of [1,2]){x="body";continue;}let s:string=x;
              let empty:number[]=[];let y:string|null="zero";for(let i of empty){y="body";}let t:string=y;
              if(s!=="body"||t!=="zero"){throw {name:"FAIL",message:"for exits"};}
            }
            """, "", "", root, null, List.of(Backend.values()), Map.of(), true);
    }

    private static void exactIndexReads() {
        for (String read : List.of("r.x", "r[\"x\"]", "r[k]")) {
            check("function f(r:Record<string,unknown>,k:string):void{if(typeof " + read
                + "===\"string\"){let s:string=" + read + ";}}", null);
            for (String effect : List.of("k=\"other\";", "r={};", "r.y=1;", "delete r.y;", "ping();"))
                check("function ping():void{}function f(r:Record<string,unknown>,k:string):void{if(typeof "
                    + read + "===\"string\"){" + effect + "let s:string=" + read + ";}}", "E3001");
        }
        check("function f(r:Record<string,unknown>):void{if(typeof r.x===\"string\"){let s:string=r[\"x\"];}}", null);
        check("function f(r:Record<string,string>,k:string):void{if(r[k]!==undefined){let s:string=r[k];}}", null);
        check("function f(a:(number|null)[],i:number):void{if(a[i]!==null){let n:number=a[i];}}", null);
        check("function f(a:(number|null)[]):void{if(a[0]!==null){let n:number=a[-0];}}", "E3001");
        check("function f(a:unknown[],i:number):void{if(typeof a[i]===\"number\"){let n:number=a[i];}}", null);
        for (String suffix : List.of("let k=\"x\";let s:string=r[k];", "let q=r;let s:string=q[k];", "{let k=\"x\";let s:string=r[k];}"))
            check("function f(r:Record<string,unknown>,k:string):void{if(typeof r[k]===\"string\"){" + suffix + "}}", "E3001");
        check("function f(r:Record<string,unknown>,k:string):void{if(typeof r[k]===\"string\"){let g=():string=>r[k];}}", "E5003");
        check("async function tick():Promise<void>{}async function f(r:Record<string,unknown>,k:string):Promise<void>{if(typeof r[k]===\"string\"){await tick();let s:string=r[k];}}", "E3001");
        check("function f(r:Record<string,unknown>,k:string):void{if(typeof r[(k=\"x\")]===\"string\"){let s:string=r[k];}}", "E3001");
    }

    private static void exactIndexRuntime(Path root) throws Exception {
        CompositionProductionTest.execute("""
            class C{n:number;}
            function opaque(value:unknown):unknown{return value;}
            function record(r:Record<string,unknown>,k:string):number{
              if(typeof r[k]==="string"){return r[k].length;}return 0;
            }
            function strings(r:Record<string,string>,k:string):string{
              if(r[k]!==undefined){return r[k];}return "missing";
            }
            function array(a:(number|null)[],i:number):number{
              if(a[i]!==null){return a[i]+1;}return 0;
            }
            function nominal(r:Record<string,unknown>):number{
              if(r.child instanceof C){return r["child"].n;}return 0;
            }
            export function main():void{
              let c:C={n:7};let r:Record<string,unknown>={x:"abc",child:c};
              let a:(number|null)[]=[2,null];
              if(record(r,"x")!==3||record(r,"missing")!==0||strings({x:"yes"},"x")!=="yes"||strings({},"x")!=="missing"||array(a,0)!==3||array(a,1)!==0||nominal(r)!==7){throw {name:"FAIL",message:"exact reads"};}
              if(typeof r.x==="string"){let s:string=r["x"];if(s!=="abc"){throw {name:"FAIL",message:"key equivalence"};}}
              let caught=false;try{let wrong:Record<string,string>=opaque(r) as Record<string,string>;}catch(e){caught=true;}
              if(!caught){throw {name:"FAIL",message:"record descriptor preserved"};}
              caught=false;try{array(a,3);}catch(e){caught=(e as Error).name==="E8002";}
              if(!caught){throw {name:"FAIL",message:"bounds retained"};}
              let xs:unknown[]=["text"];if(typeof xs[0]==="string"){let n=xs[0].length;if(n!==4){throw {name:"FAIL",message:"unknown element"};}}
            }
            """, "", "", root, null, List.of(Backend.values()), Map.of(), true);
    }

    private static void primitiveTypeof() {
        for (String type : List.of("boolean", "number", "string")) {
            for (String flags : List.of("|null", "|undefined", "|null|undefined")) {
                String complement = flags.substring(1);
                check("function f(x:" + type + flags + "):void{if(typeof x===\"" + type
                    + "\"){let yes:" + type + "=x;}else{let no:" + complement + "=x;}}", null);
                check("function f(x:" + type + flags + "):void{if(\"" + type + "\"!==typeof x){let no:"
                    + complement + "=x;}else{let yes:" + type + "=x;}}", null);
                check("function f(x:" + type + flags + "):void{if(typeof x!==\"" + type
                    + "\"){return;}let yes:" + type + "=x;}", null);
            }
        }
        check("function f(x:null|undefined):void{if(typeof x===\"undefined\"){let a:undefined=x;}else{let b:null=x;}}", null);
        check("function f(x:string|null|undefined):void{if(typeof x!==\"undefined\"){let a:string|null=x;}else{let b:undefined=x;}}", null);
        check("class P{s?:string|null;n?:number;b?:boolean;}function f(p:P):void{if(typeof p.s===\"string\"){let s:string=p.s;}if(typeof p.n===\"number\"){let n:number=p.n;}if(typeof p.b===\"boolean\"){let b:boolean=p.b;}}", null);
        check("function f(x:unknown):void{if(typeof x!==\"string\"){let s:string=x;}}", "E3001");
        check("function f(x:string|null):void{if(typeof x===\"object\"){let n:null=x;}}", "E3001");
        check("function f(x:string|null):void{if(typeof x===\"string\"){x=null;let s:string=x;}}", "E3001");
    }

    private static void primitiveTypeofRuntime(Path root) throws Exception {
        CompositionProductionTest.execute("""
            class P{s?:string|null;n?:number;b?:boolean;}
            function text(x:string|null|undefined):string{
              if(typeof x==="string"){return x;}
              if(typeof x==="undefined"){let u:undefined=x;return "absent";}
              let n:null=x;return "null";
            }
            function number(x:number|null):number{if(typeof x!=="number"){return 0;}return x+1;}
            function boolean(x:boolean|undefined):boolean{if(typeof x==="undefined"){return false;}return x;}
            function fields(p:P):number{
              let n=0;if(typeof p.s==="string"){n=n+p.s.length;}
              if(typeof p.n==="number"){n=n+p.n;}if(typeof p.b==="boolean"){if(p.b){n++;}}return n;
            }
            export function main():void{
              if(text("yes")!=="yes"||text(null)!=="null"||text(undefined)!=="absent"||number(null)!==0||number(2)!==3||boolean(undefined)||!boolean(true)||fields({})!==0||fields({s:"abc",n:2,b:true})!==6){throw {name:"FAIL",message:"primitive typeof"};}
              let x:string|null="live";let clear=():boolean=>{x=null;return true;};let caught=false;
              try{if(typeof x==="string"&&clear()){let n=x.length;}}catch(e){caught=(e as Error).name==="E8001";}
              if(!caught){throw {name:"FAIL",message:"refined read must validate"};}
            }
            """, "", "", root, null, List.of(Backend.values()), Map.of(), true);
    }

    private static void stableLoopRefinements() {
        for (String loop : List.of("while(true)", "for(let item of [1,2])")) {
            String prefix = "function f(x:string|null):void{if(x!==null){";
            check(prefix + loop + "{let s:string=x;break;}}}", null);
            check(prefix + loop + "{let s:string=x;x=null;}}}", "E3001");
            check(prefix + loop + "{{let x:number=1;x=2;}let s:string=x;break;}}}", null);
            check(prefix + loop + "{let s:string=x;let x:number=(x=null);}}}", "E3001");
            check(prefix + loop + "{let clear=():void=>{x=null;};let s:string=x;break;}}}", null);
            check(prefix + loop + "{let task=async():Promise<void>=>{await tick();};let s:string=x;break;}}}"
                + "async function tick():Promise<void>{}", null);
            check("async function tick():Promise<void>{}async function f(x:string|null):Promise<void>{if(x!==null){"
                + loop + "{let s:string=x;await tick();}}}", "E3001");
            check(prefix + loop + "{for(let i of [1]){x=null;}let s:string=x;}}}", "E3001");
            check(prefix + loop + "{for(let x of [1]){x=2;}let s:string=x;break;}}}", null);
            check(prefix + loop + "{for(let i of (x=null)===null?[1]:[2]){}let s:string=x;}}}", "E3001");
            check(prefix + loop + "{try{x=null;}catch(e){}let s:string=x;}}}", "E3001");
        }
        check("function f(x:boolean|null):void{if(x!==null){while(x){x=null;}}}", "E3007");
        check("function f(x:string|null):void{if(x!==null){while(true){let s:string=x;let x:string=(x=\"new\");break;}}}", "E3001");
        check("function f(x:unknown):void{if(typeof x===\"number\"){for(let i of [1]){let n:number=x;x++;}}}", "E3001");
        check("function f(x:string|null):void{if(x!==null){while(((x=null)===null)){let s:string=x;}}}", "E3001");
        check("function f(x:string|null):void{while((x=\"new\")===\"new\"){let s:string=x;break;}}", null);
        check("function f(x:string|null):void{if(x!==null){for(let i of (x=\"new\")===\"new\"?[1]:[2]){let s:string=x;}}}", null);
        check("async function values():Promise<number[]>{return [1];}"
            + "async function f(x:string|null):Promise<void>{if(x!==null){for(let i of await values()){let s:string=x;}}}", "E3001");
        check("async function values():Promise<number[]>{return [1];}"
            + "async function f():Promise<void>{let x:string|null=null;for(let i of (x=\"new\")===\"new\"?await values():[2]){let s:string=x;}}", "E3001");
        check("class P{v:string|null;}function f(p:P):void{if(p.v!==null){for(let i of [1]){let s:string=p.v;}}}", "E3001");
        check("class P{v:string|null;}function f(p:P):void{while(p.v!==null){let s:string=p.v;break;}}", null);
        check("async function values():Promise<number[]>{return [1];}"
            + "async function f():Promise<void>{let x:string|null=null;for(let i of await values()){if(x===null){x=\"new\";}let s:string=x;}}", null);
    }

    private static void unknownLiteralContexts() {
        for (String expression : List.of("{nested:{x:1},items:[]}", "[]", "[1,\"x\",null]", "[{x:1},{x:2}]", "[[],[1]]"))
            check("function f():unknown{return " + expression + ";}", null);
        check("function f():void{let r:Record<string,unknown>={nested:{x:1},items:[]};}", null);
        check("function f():void{let x:unknown=[1,2];let a:number[]=x;}", null);
        check("function f():void{let x:unknown=[{x:1}];let a:Record<string,unknown>[]=x;}", null);
        check("function f():void{let x:unknown=[];let a:unknown[]=x;}", null);
        check("function f():void{let x:unknown=[1,\"x\"];let a:unknown[]=x;}", null);
        for (String expression : List.of("[]", "[1,\"x\"]", "{}", "[{x:1}]"))
            check("function f():void{let x=" + expression + ";}", expression.equals("[1,\"x\"]") ? "E3011" : "E3002");
        check("function f():void{let a:number[]=[1,\"x\"];}", "E3011");
        check("function f():void{let r:Record<string,string>={nested:{x:1}};}", "E3001");
        for (String expression : List.of("{x:v()}", "[1,v()]", "[v(),v()]", "[[],{x:v()}]"))
            check("function v():void{}function f():unknown{return " + expression + ";}", "E3001");
        check("function f():unknown{return {[1]:{}};}", "E3018");
        check("function f():unknown{return {x:1,x:2};}", "E3001");
    }

    private static void loopAndLiteralRuntime(Path root) throws Exception {
        String source = """
            class Payload{raw:unknown;}
            function take(value:unknown):unknown{return value;}
            function contains(ids:string[],value:unknown):boolean{
              if(typeof value!=="string"){return false;}
              for(let id of ids){if(id===value){return true;}}
              return false;
            }
            function stable(value:string|null):number{
              if(value===null){return 0;}
              let n=0;
              while(n<3){let text:string=value;for(let i of [1,2]){if(text!==value){return -1;}}n++;continue;}
              return n;
            }
            export function run():void{
              if(!contains(["a","b"],"b")||contains(["a"],3)||stable("yes")!==3){throw {name:"FAIL",message:"stable loops"};}
              let repeated:unknown=2;
              while(typeof repeated==="number"){let n:number=repeated;if(n===0){repeated="done";}else{repeated=n - 1;continue;}}
              if((repeated as string)!=="done"){throw {name:"FAIL",message:"fresh guard"};}
              let value:string|null=null;
              for(let i of (value="iterable")==="iterable"?[1,2]:[]){let text:string=value;if(text!=="iterable"){throw {name:"FAIL",message:"iterable effects"};}}
              let state:string|null="live";
              let change=():void=>{state=null;};let caught=false;
              try{for(let i of [1]){change();let stale:string=state;}}catch(e){caught=(e as Error).name==="E8001";}
              if(!caught){throw {name:"FAIL",message:"captured boundary"};}
              let trace="";
              let mark=(text:string):number=>{trace=trace+text;return trace.length;};
              let data:Record<string,unknown>={nested:{x:mark("a")},items:[],mixed:[mark("b"),"x"],rows:[{x:mark("c")}]};
              let nested:Record<string,unknown>=data.nested as Record<string,unknown>;
              let items:unknown[]=data.items as unknown[];items.push("item");
              let mixed:unknown[]=data.mixed as unknown[];
              let rows:Record<string,unknown>[]=data.rows as Record<string,unknown>[];
              if(trace!=="abc"||(nested.x as number)!==1||items.length!==1||(mixed[0] as number)!==2||(rows[0].x as number)!==3){throw {name:"FAIL",message:"recursive literals/order"};}
              let opaque:unknown=[1,2];let nums:number[]=opaque;nums.push(3);
              caught=false;try{let erased:unknown[]=take(opaque) as unknown[];}catch(e){caught=true;}
              if(!caught||nums.length!==3){throw {name:"FAIL",message:"homogeneous descriptor"};}
              let empty:unknown=[];let same:unknown[]=empty;same.push(nums);
              let hetero:unknown=[1,"s",null];let values:unknown[]=hetero;values.push(false);
              caught=false;try{let upgraded:number[]=take(empty) as number[];}catch(e){caught=true;}
              if(!caught||same.length!==1||values.length!==4){throw {name:"FAIL",message:"unknown descriptor"};}
              let readonlyNums:readonly unknown[]=[1,"s"];
              let boxed:Payload={raw:{inner:[[],[1]]}};
              let passed:unknown=take({items:[],record:{x:1}});
              let chosen:unknown=true?{x:1,items:[]}:{};
              let fallback:unknown=null??{items:[]};
              if(readonlyNums.length!==2||!Object.hasOwn(boxed,"raw")||!Object.hasOwn(passed as Record<string,unknown>,"items")||!Object.hasOwn(chosen as Record<string,unknown>,"x")||!Object.hasOwn(fallback as Record<string,unknown>,"items")){throw {name:"FAIL",message:"contexts"};}
            }
            export function main():void{run();}
            """;
        CompositionProductionTest.execute(source, "", "", root, null,
            List.of(Backend.values()), Map.of(), true);
        Path entry = root.resolve("JS/src/main.deal");
        var invocation = deal.semantic.CompilerProfileProvider.resolve(
            deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
            deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
        var compiler = new deal.module.CompilationOrchestrator(entry, root.resolve("oracle"),
            false, false, false, false, Backend.JS, null, List.of(entry.getParent()),
            Path.of("").toAbsolutePath(), null, invocation);
        if (!compiler.compile()) throw new AssertionError(compiler.diagnostics());
        var project = compiler.producedProject();
        var parsed = deal.semantic.ir.ContractSnapshotCanonicalizer.parseProject(
            (deal.semantic.ir.CanonicalJson.Obj) deal.semantic.ir.CanonicalJson.parse(
                deal.semantic.ir.SemanticIrValidator.toProjectText(project)));
        var modules = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.LoweredModuleUnit>();
        parsed.modules().forEach(raw -> {var unit=deal.semantic.ir.RawUnitMaterializer.materialize(raw);modules.put(unit.moduleId(),unit);});
        var transported = new deal.semantic.ir.ExecutableLoweredProject(project.semanticProfile(),
            parsed.interfaceIndex(), modules, project.entryModule(), parsed.classSeeds());
        var facts = new deal.semantic.ir.SemanticIrValidator.ComparisonFacts(
            project.interfaceIndex().interfaceIndexDigest(), project.semanticProfile(), invocation.capabilityRegistryHash());
        for (var executable : List.of(project, transported)) {
            var failure=deal.semantic.ir.SemanticIrValidator.validate(executable,facts);
            if(failure.isPresent())throw new AssertionError(failure);
            var tables=new LinkedHashMap<deal.semantic.ir.ModuleId,deal.semantic.ir.StructuredBodyTable>();
            var registries=new LinkedHashMap<deal.semantic.ir.ModuleId,deal.semantic.ir.ClassFactoryRegistry>();
            executable.modules().forEach((id,unit)->{tables.put(id,unit.bodyTable());registries.put(id,unit.classFactories());});
            var result=deal.semantic.SemanticOracle.invokeSyncEntry(executable,tables,registries,
                new deal.semantic.SemanticOracle.HostResponder(){},executable.entryModule(),"run",List.of());
            if(!(result.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success))
                throw new AssertionError("Loop/literal Oracle: "+result.comparisonReport());
        }
    }

    private static void constructionOrder(Path root) throws Exception {
        String prefix = "class C{first:number;second:number;}function f():void{let x:number|null=null;";
        check(prefix + "let c:C={second:(x=1),first:x};}", null);
        check(prefix + "let c:C={second:x,first:(x=1)};}", "E4003");
        String fields = "class P{n:number|null;}class C{first:number;second:number;}function ping():number{return 1;}function f(p:P):void{if(p.n!==null){";
        check(fields + "let c:C={second:ping(),first:p.n};}}", "E4003");
        check(fields + "let c:C={second:p.n,first:ping()};}}", null);
        check(fields + "let x=0;let c:C={second:(x=1),first:p.n};}}", "E4003");
        String source = """
            class C{first:number;second:number;}
            class Nested{a:C;b:number;}
            export function main():void{
              let x:number|null=null;let c:C={second:(x=1),first:x};
              let trace="";
              let read=(s:string,n:number):number=>{trace=trace+s;return n;};
              let nested:Nested={b:read("b",2),a:{second:read("s",3),first:read("f",4)}};
              let caught=false;let untouched:number=0;
              let fail=():number=>{trace=trace+"x";throw {name:"STOP",message:"stop"};};
              try{let bad:C={second:fail(),first:(untouched=1)};}catch(e){caught=(e as Error).name==="STOP";}
              if(c.first!==1||c.second!==1||nested.a.first!==4||trace!=="bsfx"||!caught||untouched!==0){
                throw {name:"FAIL",message:"literal evaluation order"};
              }
            }
            """;
        CompositionProductionTest.execute(source, "", root, null, List.of(Backend.values()));
    }

    private static void contextualInvariants(Path root) throws Exception {
        for (String expression : List.of("b?1:\"s\"", "n??\"s\"", "b?a:s"))
            check("function f(b:boolean,n:number,a:number[],s:string[]):unknown{return " + expression + ";}", "E3006");
        for (String expression : List.of("`${JSON.parse(\"1\")}`", "`${JSON.parse(\"\\\"ok\\\"\")}`"))
            check("function f():void{let s:string=" + expression + ";}", "E3016");
        for (String statement : List.of("let r:Record<string,unknown>={};r.a=v();",
                "let r:Record<string,unknown>={};r[\"a\"]=v();",
                "let xs:Record<string,unknown>[]=[{}];xs[0]=v();",
                "let xs:Record<string,unknown>[]=[{}];xs[0]=1;",
                "let xs:Record<string,unknown>[]=[];xs[xs.length]=1;",
                "let xs:Record<string,unknown>[]=[{}];let x:unknown=1;xs[0]=x;")) {
            check("function v():void{}function f():void{" + statement + "}", "E3001");
            productionReject(statement, root.resolve("reject-" + Integer.toUnsignedString(statement.hashCode())));
        }
        for (String field : List.of("r:Record<string,unknown>", "r?:Record<string,unknown>")) {
            check("class C{" + field + ";}function f(c:C):void{c.r=1;}", "E3001");
            check("class C{" + field + ";}function f(c:C):void{c.r={x:1};}", null);
        }
        check("function n():never{throw {name:\"X\",message:\"x\"};}function f():void{let r:Record<string,unknown>={};r.a=n();}", "E3001");
        // Destination acceptance must not erase the expression's actual type.
        for (String[] row : new String[][] {
                {"function f(b:boolean):unknown{return b?1:2;}", "number"},
                {"function f(b:boolean):number|null|undefined{return b?1:undefined;}", "!number"},
                {"function f(n:number|null):unknown{return n??1;}", "number"},
                {"function f(n:number|null):number|null{return n??1;}", "number"},
                {"function f(b:boolean,a:number[]):readonly unknown[]|null{return b?a:[];}", "readonly[unknown]"}}) {
            var lex = new Lexer(row[0], "context.deal").tokenize();
            var parsed = new Parser(lex.tokens(), "context.deal").parse();
            var resolver = new NameResolver("context.deal", new StubModuleResolver());
            var symbols = resolver.resolve(parsed.program());
            var checked = TypeChecker.check("context.deal", symbols, resolver, parsed.program());
            if (!lex.diagnostics().isEmpty() || !parsed.diagnostics().isEmpty()
                    || !resolver.diagnostics().isEmpty() || !checked.diagnostics().isEmpty())
                throw new AssertionError(row[0] + checked.diagnostics());
            var types = checked.typeMap().entrySet().stream()
                .filter(e -> e.getKey() instanceof deal.ast.ConditionalExpr
                    || e.getKey() instanceof deal.ast.BinaryExpr b && b.op() == deal.ast.BinaryOp.COALESCE)
                .map(e -> deal.semantic.DescriptorService.describe(e.getValue()).canonicalSpecText()).toList();
            if (!types.equals(List.of(row[1]))) throw new AssertionError(row[0] + ": " + types);
        }
        String source = """
            class C{x:number; r:Record<string,unknown>; opt?:Record<string,unknown>;}
            function same(b:boolean):unknown{let x:unknown=b?1:2;let n:number=x;return n;}
            function fallback(n:number|null):unknown{let x:unknown=n??3;let v:number=x;return v;}
            export function main():void{
              let a:boolean=JSON.parse("1") instanceof C;
              let b=JSON.parse("1") instanceof C;
              let c:boolean=JSON.parse("null") instanceof C;
              let s:string=`${1}:${"ok"}`;
              let decoded:string=JSON.parse("\\"ok\\"");
              let record:Record<string,unknown>={};record.n=1;record["u"]=undefined;
              let xs:Record<string,unknown>[]=[{}];xs[0]={n:2};xs[xs.length]=record;
              let box:C={x:1,r:{}};box.r={n:3};box.opt=record;
              if(a||b||c||s!=="1:ok"||decoded!=="ok"||(same(true) as number)!==1
                  ||(fallback(null) as number)!==3||(xs[0].n as number)!==2
                  ||(box.r.n as number)!==3||!Object.hasOwn(record,"u")){
                throw {name:"FAIL",message:"context/storage invariants"};
              }
            }
            """;
        CompositionProductionTest.execute(source, "", root.resolve("positive"), null, List.of(Backend.values()));
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
                body.startsWith("let x:Other") || body.equals("let x:unknown=c??{};") ? "E3006" : null);
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
        check("function f(a:number[]|null):void{let x:unknown=a??[\"s\"];}", "E3006");
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
        nullishArrayFlow();
        guardAliasFlow();
        loopExitFlow();
        exactIndexReads();
        primitiveTypeof();
        bindingOwnership();
        coalescingLiteralContext();
        ternaryLiteralContext();
        contextualParameters();
        check("function f(s:string):number{return Number(s);}", null);
        for (String value : List.of("1", "true", "null", "undefined", "[1]"))
            check("function f():void{let n=Number("+value+");}", "E5001");
        check("function f(x:unknown):number{return Number(x);}", "E5001");
        check("function f(x:Record<string,unknown>):number{return Number(x);}", "E5001");
        check("function f():void{let n=Number();}", "E5001");
        check("function f():void{let n=Number(\"1\",\"2\");}", "E5001");
        check("function f():void{let n=Number;}", "E3001");
        check("function f(xs:number[]):void{let a=xs.slice();let b=xs.slice(1);}", null);
        check("function f(xs:number[]):void{let a=xs.slice(undefined);}", "E5001");
        check("function f(xs:number[]):void{let a=xs.slice(1,2,3);}", "E5001");
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
        check("function f(x:string|null):void{if(x!==null){while(true){let s:string=x;break;}}}", null);
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
            nullishArrayRuntime(root.resolve("nullish-arrays"));
            guardAliasRuntime(root.resolve("guard-aliases"));
            loopExitRuntime(root.resolve("loop-exits"));
            exactIndexRuntime(root.resolve("exact-index"));
            primitiveTypeofRuntime(root.resolve("primitive-typeof"));
            stableLoopRefinements();
            unknownLiteralContexts();
            contextualInvariants(root.resolve("context"));
            constructionOrder(root.resolve("construction-order"));
            loopAndLiteralRuntime(root.resolve("loop-literals"));
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
