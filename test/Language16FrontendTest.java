package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.lexer.*;
import deal.parser.*;
import deal.types.*;
import java.util.*;

/** Focused v1.6 frontend contracts, independent of backend execution. */
public final class Language16FrontendTest {
    private static void check(String source, boolean accepted) {
        var diagnostics = diagnostics(source);
        if (diagnostics.isEmpty() != accepted) throw new AssertionError(source + "\n" + diagnostics);
    }
    private static List<deal.diagnostics.CompilerDiagnostic> diagnostics(String source) {
        return diagnostics(source, new StubModuleResolver());
    }
    private static List<deal.diagnostics.CompilerDiagnostic> diagnostics(String source, StubModuleResolver modules) {
        var lex = new Lexer(source, "test.deal").tokenize();
        var parsed = new Parser(lex.tokens(), "test.deal").parse();
        var diagnostics = new ArrayList<>(lex.diagnostics());
        diagnostics.addAll(parsed.diagnostics());
        if (diagnostics.isEmpty()) {
            var resolver = new NameResolver("test.deal", modules);
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
        for (String fields : List.of("x:number;x:number;", "x?:number;x:number;", "x:number;x?:string;")) {
            var duplicate = diagnostics("class C{" + fields + "}");
            if (duplicate.size() != 1 || !duplicate.getFirst().code().equals("E2002")
                    || !duplicate.getFirst().message().equals("Redeclaration of field 'x'"))
                throw new AssertionError(duplicate);
        }
        check("class A{x:number;}class B{x:string;}", true);
        check("function f():Error{return {name:\"Custom\",message:\"m\"};}",true);
        check("function f():Error{return {message:\"m\",name:\"Custom\"};}",true);
        for(String fields:List.of("", "name:\"X\"", "message:\"m\"", "code:\"X\",message:\"m\"",
                "name:1,message:\"m\"", "name:\"X\",message:false", "name:\"X\",message:\"m\",extra:1"))
            check("function f():Error{return {"+fields+"};}",false);
        check("function f(e:Error):string{return e.name;}",true);
        check("function f(e:Error):string{return e.code;}",false);
        check("function f():Error{return Error(\"m\");}",false);
        check("function f():Error{return new Error(\"m\");}",false);
        check("function f():Error{let r:Record<string,unknown>={name:\"X\",message:\"m\"};return r;}",false);
        check("function f(e:Error):void{delete e.name;}",false);
        check("function f(e:Error):void{e.name=undefined;}",false);
        var optionalTokens = new Lexer("a?.x ?? a?.[0] ?? f?.() ? .3 : .4", "test.deal").tokenize();
        if (optionalTokens.hasErrors()) throw new AssertionError(optionalTokens.diagnostics());
        var guards = optionalTokens.tokens().stream().filter(t -> t.type()==TokenType.OPTIONAL_CHAIN).toList();
        if (guards.size()!=3 || guards.get(0).column()!=2 || guards.get(1).column()!=10
                || guards.get(2).column()!=20 || guards.stream().anyMatch(t -> !t.lexeme().equals("?.")))
            throw new AssertionError(optionalTokens.tokens());
        var digitTokens = new Lexer("true?.3:4", "test.deal").tokenize();
        if (digitTokens.hasErrors() || digitTokens.tokens().get(1).type()!=TokenType.QUESTION
                || digitTokens.tokens().get(2).type()!=TokenType.NUMBER_LITERAL)
            throw new AssertionError(digitTokens);
        rejectsAt("function f(fn:(()=>Promise<number>)|null):void{fn?.();}","fn?.()","E3014");
        rejectsAt("class Item{x:number;}function f(a:Item|null):void{a?.x=1;}","a?.x","E1033");
        check("function f(a:unknown):void{a?.x;}",false);
        check("function f(a:null):void{a?.x;}",false);
        check("function f(a:undefined):void{a?.();}",false);
        check("function f():number|undefined{return \"abc\"?.length;}",true);
        check("function f(s:string|null):void{let method=s?.slice;}",false);
        check("function f(s:string|null):void{s.slice?.(1);}",false);
        check("function f():void{JSON?.parse(\"1\");}",false);
        for (String expression : List.of("a?.child.x", "a?.child?.x", "a?.f(1)", "(a?.child)?.x"))
            check("class Item{x:number;child:Item;f:(n:number)=>number;}function f(a:Item|null):number|undefined{return "+expression+";}",true);
        check("function f(xs:number[]|null):number|undefined{return xs?.[0];}",true);
        check("function f(fn:((x:number)=>number)|undefined):number|undefined{return fn?.(1);}",true);
        check("function f(fn:(()=>void)|null):void{fn?.();}",true);
        check("function f(fn:(()=>void)|null):void{return fn?.();}",false);
        check("function f(fn:(()=>void)|null):void{let x=fn?.();}",false);
        check("class Item{x:number;}function f(a:Item|null):number{return a?.x;}",false);
        check("class Item{x:number;}function f(a:Item|null):void{a?.x=1;}",false);
        check("class Item{x:number;}function f(a:Item|null):number{return (a?.x)+1;}",false);
        check("class Item{x:number;child:Item;}function f(a:Item|null):number{return (a?.child).x;}",false);
        check("class Item{x:number;child:Item|null;}function f(a:Item|null):number|undefined{return a?.child.x;}",false);
        check("function f(fn:(()=>Promise<number>)|null):void{fn?.();}",false);
        check("class Item{f:()=>Promise<number>;}function f(a:Item|null):void{a?.f();}",false);
        check("class Item{x?:number;}function f(a:Item|null):void{delete a?.x;}",true);
        check("class Item{x:number;}function f(a:Item|null):void{delete a?.x;}",false);
        check("function f(xs:number[]|null):void{delete xs?.[0];}",false);
        check("function f(s:string|null):void{delete s?.length;}",false);
        check("function f(a:Record<string,unknown>|null):void{delete a?.[1];}",false);
        check("function f(s:string|null):number|undefined{return s?.slice(1).length;}",true);
        check("function f(xs:number[]|null):number[]|undefined{return xs?.map((x:number):number=>x+1);}",true);
        check("function f():number{return true?.3:4;}",true);
        for(String source:List.of("function f(a:number):void{a?.;}","function f(a:number):void{a?.[ ];}",
                "function f(a:number):void{a?.(,);}","function f(a:number):void{a?..x;}")) check(source,false);
        for (String expression : List.of("a??b", "a??b??true", "(a??b)||true", "a??(b&&true)",
                "(true||false)??true", "true||false&&false", "false&&true||true", "a??b?true:false"))
            check("function f(a:boolean|null,b:boolean):boolean{return " + expression + ";}", true);
        for (String expression : List.of("a??b||true", "a??b&&true", "a||b??true", "a&&b??true",
                "a??b??true||false", "a??"))
            check("function f(a:boolean,b:boolean):boolean{return " + expression + ";}", false);
        for(String expression:List.of("a??b||true","a??b&&true","a||b??true","a&&b??true")) {
            String source="function f(a:boolean,b:boolean):boolean{return "+expression+";}";
            String token=expression.startsWith("a??")?expression.contains("||")?"||":"&&":"??";
            var first=diagnostics(source).getFirst();
            if(!first.code().equals("E1053") || !first.toString().contains(":1:"+(source.indexOf(token)+1)+"-"))
                throw new AssertionError(first);
        }
        for (String singleton : List.of("null", "undefined", "(true?null:undefined)")) {
            check("function f():number{return " + singleton + "??7;}", true);
            check("function noop():void{}function f():void{" + singleton + "??noop();}", false);
        }
        check("function noop():void{}function f():void{noop()??1;}", false);
        check("function f(a:number|null|undefined):number{return a??7;}", true);
        check("function f(a:number|undefined):number|null{return a??null;}", true);
        check("function f(a:number|null):number|undefined{return a??undefined;}", true);
        check("function f(a:unknown):unknown{return a??7;}", true);
        check("function f(a:unknown):number{return a??7;}", false);
        check("function f(a:number|null):string{return a??\"x\";}", false);
        check("class Item{x:number;}function f(a:Item|null):Item{return a??{x:1};}", true);
        check("class Item{x:number;}function f():Item{return {x:1}??{x:2};}", false);
        check("class Item{x:number;}function f(a:Item|null):Item{return a??JSON.parse(\"{}\");}", true);
        check("class Item{x:number;}function f(a:Item[]|null):Item[]{return a??[{x:1}];}", true);
        check("function f(a:(()=>number)|null):(x:number)=>number{return a??((x:number):number=>x);}", false);
        check("function f(a:(()=>number)|null):(x:number)=>number{return a??(():number=>1);}", true);
        check("function f(a:number|null):number{let x=0;return x=a??2;}", true);
        for (int count = 0; count < 6; count++) {
            String slashes = "\\".repeat(count);
            for (String template : List.of("`🌍" + slashes + "`", "`${ `🌍" + slashes + "` }`")) {
                check("function f():void{let text=" + template + ";}", count % 2 == 0);
            }
            check("function f():void{let text=`🌍" + slashes + "${\"x\"}`;}", true);
        }
        check("function f(): void { const x = 1; let y: number = x; }", true);
        check("function f(): void { const x: number = 1; x = 2; }", false);
        check("function f(): void { const x = 1; x = 2; }", false);
        check("function f(): void { const x = 1; let g = function():void{x = 2;}; }", false);
        check("function f(): void { const x = 1; {let x = 2; x = 3;} }", true);
        check("function f(): void { const x: number; }", false);
        check("function f(): void { const xs: number[] = [1]; xs[0] = 2; }", true);
        check("function f(): void { for(const x of [1]) { x = 2; } }", false);
        check("function f(): void { for(const x of [1]) { let g = function():void{x = 2;}; } }", false);
        check("function f(): void { for(const x of [1]) { const y = x; } }", true);
        check("function f(): void { const r:Record<string,unknown> = {const:1}; r.const = 2; }", true);
        for (String name : List.of("Record", "Promise", "Object", "Array", "String", "JSON", "Number", "Math", "Date", "console")) {
            check("function f(" + name + ":number):void{}", false);
            check("function f():void{let " + name + "=1;}", false);
            check("class " + name + "{}", false);
            check("import * as " + name + " from \"host/test\";", false);
        }
        check("function f():number{return Math.floor(1.5)+Math.ceil(1.5)+Math.abs(-2)+Math.sqrt(4)+Date.now();}", true);
        check("function f():void{console.log(\"x\");console.error(\"y\");}", true);
        check("function f(x:unknown):boolean{return Number.isFinite(x)&&Number.isNaN(x);}", true);
        check("function f(x:unknown):boolean{return Array.isArray(x);}", true);
        check("function f(xs:readonly number[]):boolean{return Array.isArray(xs)&&Array.isArray(JSON.parse(\"[]\"));}", true);
        for (String expression : List.of("Array.isArray", "Array.isArray()", "Array.isArray(1,2)", "Array.isArray(console.log(\"x\"))"))
            check("function f():void{" + expression + ";}", false);
        check("function f(x:unknown):number{if(Array.isArray(x)){return x.length;}return 0;}", false);
        check("function f(x:unknown):unknown[]{if(Array.isArray(x)){return x;}return [];}", false);
        check("function f():number{return Array.isArray([1]);}", false);
        check("function f():boolean{return Array.isArray({x:1});}", true);
        check("function f():string{return (1.25).toFixed(2);}", true);
        for (String expression : List.of("(1).toFixed()", "(1).toFixed(1,2)", "(1).toFixed(true)", "(1).toFixed", "\"1\".toFixed(2)"))
            check("function f():void{" + expression + ";}", false);
        check("function f():number{return Math.pow(2,3)+Math.min(1,2)+Math.max(1,2)+Math.round(-0.5);}", true);
        for (String expression : List.of("Number", "Number.isFinite", "Number.isNaN", "Number.isFinite()",
                "Math.pow(1)", "Math.pow(1,2,3)", "Math.pow(true,2)", "Math.pow(2,\"3\")", "Math.pow", "Number.isNaN(1,2)", "Math.min(1)", "Math.max(1,2,3)", "Math.round(true)", "Math.min", "Math.round"))
            check("function f():void{" + expression + ";}", false);
        check("function f(x:unknown):number{if(Number.isFinite(x)){return x;}return 0;}", false);
        check("function f():boolean{return Number.isFinite(\"1\")||Number.isNaN(null)||Number.isNaN(undefined);}", true);
        for (String member : List.of("min", "max", "round", "isFinite", "isNaN"))
            check("import * as numeric from \"std/math\"; function f():void{numeric." + member + ";}", false);
        check("import * as numeric from \"std/number\"; function f():void{}", false);
        for (String expression : List.of("Math.floor", "Date.now", "console.log", "Math.abs()", "Date.now(1)", "console.log(1)", "Math.sqrt(true)")) {
            check("function f():void{let value=" + expression + ";}", false);
        }
        check("function f():unknown{return JSON.parse(\"1\");}", true);
        check("function f():number{return JSON.parse(\"1\");}", true);
        check("function f():number[]{return (JSON.parse(\"[]\"));}", true);
        check("class Node{value:number;next?:Node;} function f():Node{return JSON.parse(\"{}\");}", true);
        check("function f():Error{return JSON.parse(\"{}\");}", true);
        for (String target : List.of("node.child.value", "node.items[0]", "xs[node.key]"))
            check("class Node{value:number;child?:Node;items?:number[];key?:number;}"
                + "function f(node:Node,xs:number[]):void{" + target + "=1;}", false);
        for (String target : List.of("node.child.child", "record[node.key]"))
            check("class Node{child?:Node;key?:string;}"
                + "function f(node:Node,record:Record<string,unknown>):void{delete " + target + ";}", false);
        check("class Node{value:number;child?:Node;items?:number[];key?:string;index?:number;}"
            + "function f(node:Node,record:Record<string,unknown>):void{"
            + "(node.child as Node).value=1;(node.items as number[])[node.index as number]=2;"
            + "record[node.key as string]=3;delete record[node.key as string];"
            + "delete (node.child as Node).child;node.child=node;delete node.child;}", true);
        check("function f(record:Record<string,unknown>):void{record.child.value=1;}", false);
        check("class Handler{run?:()=>void;} function f():Handler{return JSON.parse(\"{}\");}", false);
        check("class Opaque{payload:unknown;} function f():Opaque{return JSON.parse(\"{}\");}", true);
        check("function f():()=>void{return JSON.parse(\"null\");}", false);
        for (String expression : List.of("JSON.parse", "JSON.parse()", "JSON.parse(1)", "JSON.parse(\"1\",\"2\")"))
            check("function f():void{let value=" + expression + ";}", false);
        check("class Handler{run?:()=>void;} function f():Handler{return JSON.parse(\"{}\") as Handler;}", true);
        check("function f(x:unknown):string|undefined{return JSON.stringify(x);}", true);
        check("function f():string|undefined{return JSON.stringify(undefined);}", true);
        check("function f():string|undefined{return JSON.stringify(()=>1);}", false);
        check("function f():string|undefined{return JSON.stringify(():number=>1);}", true);
        for (String expression : List.of("JSON", "JSON.stringify", "JSON.stringify()", "JSON.stringify(1,2)")) {
            check("function f():void{let value=" + expression + ";}", false);
        }
        check("function f(r:Record<string,unknown>):string[]{return Object.keys(r);}", true);
        check("class C{x:number;} function f(c:C):string[]{return Object.keys(c);}", true);
        for (String expression : List.of("Object.keys", "Object.keys()", "Object.keys({}, {})",
                "Object.keys(1)", "Object.keys(null)", "Object.keys(undefined)", "Object.keys([] as number[])", "Object.keys(x)"))
            check("function f(x:unknown):void{" + expression + ";}", false);
        check("class C{x:number;} function f(c:C|null):void{Object.keys(c);}", false);
        check("function f(xs:number[]):number[]{return Array.from(xs,(x:number,i:number):number=>x+i);}", true);
        check("function f(xs:number[]):string[]{return Array.from(xs,(x:number):string=>String(x));}", true);
        check("function f(xs:number[]):number[]{return Array.from(xs,():number=>9);}", true);
        for (String expression : List.of("Array.from", "Array.from()", "Array.from([1])", "Array.from(1,():number=>1)",
                "Array.from([1],(x:string):number=>1)", "Array.from([1],async(x:number):Promise<number>=>x)",
                "Array.from([1],(x:number):void=>console.log(String(x)))", "Array.from([1],(x:number,i:string):number=>x)",
                "Array.from([1],(x:number,i:number,z:number):number=>x)", "Array.from(xs,(x:number):number=>x)"))
            check("function f(xs:number[]|null):void{" + expression + ";}", false);
        check("function f(xs:number[]):string[]{return Array.from(xs,(x:number):number=>x);}", false);
        check("function f(xs:number[]):string[]{return xs.map((x:number,i:number,a:number[]):string=>String(x+i+a.length));}", true);
        check("function f(xs:number[]):number[]{return xs.map(():number=>7);}", true);
        check("class Item{x:number;} function f(xs:number[]):Item[]{return xs.map((x:number):Item=>({x:x}));}", true);
        check("function f(xs:number[]):number[]{return xs.map((x:unknown):number=>1);}", false);
        for (String expression : List.of("xs.map", "xs.map()", "xs.map(1)", "xs.map((x:number):number=>x,1)",
                "xs.map((x:string):number=>1)", "xs.map((x:number,i:string):number=>x)",
                "xs.map((x:number,i:number,a:string[]):number=>x)",
                "xs.map((x:number,i:number,a:number[],fourth:number):number=>x)",
                "xs.map(async(x:number):Promise<number>=>x)", "xs.map((x:number):void=>console.log(String(x)))"))
            check("function f(xs:number[]):void{" + expression + ";}", false);
        check("function f(xs:number[]|null):void{xs.map((x:number):number=>x);}", false);
        check("function f(xs:number[]):string[]{return xs.map((x:number):number=>x);}", false);
        check("class C{map:()=>number;}function f(c:C):number{return c.map();}", true);
        for (String method : List.of("find", "findIndex", "filter", "some", "every")) {
            check("function f(xs:number[]):void{xs."+method+"((x:number,i:number,a:number[]):boolean=>x+i===a.length);}", true);
            check("function f(xs:number[]):void{xs."+method+"(():boolean=>true);}", true);
            for (String parameters : List.of("", "1", "():number=>1", "(x:string):boolean=>true",
                    "(x:number,i:string):boolean=>true", "(x:number,i:number,a:string[]):boolean=>true",
                    "(x:number,i:number,a:number[],fourth:number):boolean=>true", "async(x:number):Promise<boolean>=>true",
                    "(x:number):void=>console.log(String(x))", "():boolean=>true,1"))
                check("function f(xs:number[]):void{xs."+method+"("+parameters+");}", false);
            check("function f(xs:number[]):void{let detached=xs."+method+";}", false);
            check("function f(xs:number[]|null):void{xs."+method+"(():boolean=>true);}", false);
            check("class C{"+method+":()=>number;}function f(c:C):number{return c."+method+"();}", true);
        }
        var modules = new StubModuleResolver();
        for (String method : List.of("map", "find", "findIndex", "filter", "some", "every")) {
            modules.register("host/predicates", Map.of(method, new Type.Func(List.of(Type.String.INSTANCE), Type.Number.INSTANCE)));
            String source = "import * as host from \"host/predicates\";function f():number{return host."+method+"(\"ordinary\");}";
            var errors = diagnostics(source, modules);
            if (!errors.isEmpty()) throw new AssertionError(source + "\n" + errors);
        }
        check("function f(xs:number[]):number|undefined{return xs.find(():boolean=>true);}", true);
        check("function f(xs:number[]):number{return xs.find(():boolean=>true);}", false);
        check("function f(xs:number[]):number{return xs.findIndex(():boolean=>true);}", true);
        check("function f(xs:number[]):number[]{return xs.filter(():boolean=>true);}", true);
        check("function f(xs:(number|null|undefined)[]):(number|null|undefined)[]{return xs.filter(():boolean=>true);}", true);
        check("function f(xs:number[]):string[]{return xs.filter(():boolean=>true);}", false);
        for (String method : List.of("some", "every")) {
            check("function f(xs:number[]):boolean{return xs."+method+"(():boolean=>true);}", true);
            check("function f(xs:number[]):number{return xs."+method+"(():boolean=>true);}", false);
        }
        check("function f(xs:number[]):number[]{return xs.splice(1,2);}", true);
        check("function f(xs:(number|null)[]):(number|null)[]{return xs.splice(1,2);}", true);
        check("function f(xs:unknown[]):unknown[]{return xs.splice(1,2);}", true);
        for (String expression : List.of("xs.splice", "xs.splice()", "xs.splice(1)", "xs.splice(1,2,3)",
                "xs.splice(\"a\",2)", "xs.splice(1,true)"))
            check("function f(xs:number[]):void{" + expression + ";}", false);
        check("function f(xs:number[]|null):void{xs.splice(0,1);}", false);
        check("function f():number{let r:Record<string,unknown>={splice:():number=>4};return (r.splice as ()=>number)();}", true);
        check("function f(xs:string[]):string{return xs.join(\"-\");}", true);
        for (String expression : List.of("xs.join", "xs.join()", "xs.join(1)", "xs.join(undefined)", "xs.join(\"\",\"\")"))
            check("function f(xs:string[]):void{" + expression + ";}", false);
        check("function f(xs:number[]):void{xs.join(\"\");}", false);
        check("function f(xs:(string|undefined)[]):void{xs.join(\"\");}", false);
        check("function f(xs:string[]|null):void{xs.join(\"\");}", false);
        check("function f(xs:number[]):number[]{return xs.slice(0,1);}", true);
        check("function f(xs:(number|null)[]):(number|null)[]{return xs.slice(0,1);}", true);
        check("function f(xs:number[]):number[]{return xs.slice();}", true);
        check("function f(xs:number[]):number[]{return xs.slice(1);}", true);
        for (String expression : List.of("xs.slice", "xs.slice(1,2,3)", "xs.slice(undefined)", "xs.slice(0,undefined)", "xs.slice(\"bad\",2)"))
            check("function f(xs:number[]):void{" + expression + ";}", false);
        check("function f(xs:number[]|null):void{xs.slice(0,1);}", false);
        check("function f(xs:number[]):number{return xs.push(1);}", true);
        check("function f(xs:(number|null)[]):number{return xs.push(null);}", true);
        check("function f(xs:unknown[]):number{return xs.push(\"value\");}", true);
        check("class Item{x:number;} function f(xs:Item[]):number{return xs.push({x:1});}", true);
        check("function f(xs:((x:number)=>number)[]):number{return xs.push(():number=>7);}", true);
        for (String expression : List.of("xs.push", "xs.push()", "xs.push(1,2)", "xs.push(\"bad\")", "xs.push(undefined)"))
            check("function f(xs:number[]):void{" + expression + ";}", false);
        check("function f(xs:number[]|null):void{xs.push(1);}", false);
        for (String method : List.of("includes", "indexOf")) {
            check("function f(xs:number[]):void{xs."+method+"(1);xs."+method+"(1,-0.9);}", true);
            check("class Item{x:number;} function f(xs:Item[]):void{xs."+method+"({x:1});}", true);
            check("function f(xs:((x:number)=>number)[]):void{xs."+method+"(():number=>7);}", true);
            for (String arguments : List.of("", "1,2,3", "\"bad\"", "undefined", "1,undefined", "1,\"bad\""))
                check("function f(xs:number[]):void{xs."+method+"("+arguments+");}", false);
            check("function f(xs:number[]):void{xs."+method+";}", false);
            check("function f(xs:number[]|null):void{xs."+method+"(1);}", false);
        }
        check("function f(xs:number[]):number|undefined{return xs.pop();}", true);
        check("function f(xs:unknown[]):unknown{return xs.pop();}", true);
        check("function f(xs:(number|null)[]):number|null|undefined{return xs.pop();}", true);
        check("function f(xs:number[]):number{return xs.pop();}", false);
        check("function f(xs:number[]):void{let method=xs.pop;}", false);
        check("function f(xs:number[]):void{xs.pop(1);}", false);
        check("function f(xs:number[]|null):void{xs.pop();}", false);
        check("function f():number{let r:Record<string,unknown>={pop:():number=>4};return (r.pop as ()=>number)();}", true);
        check("function f(s:string):number{return s.length;}", true);
        check("function f(s:string):string{return s.substring(1,2).trim().replace(\"a\",\"b\");}", true);
        check("function f(s:string):boolean{return s.includes(\"a\") && s.startsWith(\"a\") && s.endsWith(\"a\");}", true);
        check("function f(s:string):string[]{return s.split(\"\");}", true);
        check("function f(s:string):string{return s.substring(1);}", true);
        check("function f(s:string):string{return s.slice(-2).slice(0,1);}", true);
        check("function f(s:string):string{return s.toLowerCase().toUpperCase();}", true);
        for (String method : List.of("toLowerCase", "toUpperCase")) {
            check("function f(s:string):void{s." + method + ";}", false);
            check("function f(s:string):void{s." + method + "(undefined);}", false);
            check("function f(s:string):void{s." + method + "(1);}", false);
            check("function f(s:string|null):void{s." + method + "();}", false);
            check("import * as text from \"std/string\"; function f():void{text." + method + ";}", false);
        }
        for (String method : List.of("includes", "startsWith", "endsWith", "indexOf", "lastIndexOf")) {
            check("function f(s:string):void{s." + method + "(\"a\");s." + method + "(\"a\",1.9);}", true);
            for (String arguments : List.of("", "1", "\"a\",undefined", "\"a\",null", "\"a\",true", "\"a\",1,2"))
                check("function f(s:string):void{s." + method + "(" + arguments + ");}", false);
            check("function f(s:string):void{s." + method + ";}", false);
        }
        check("import * as text from \"std/string\"; function f():void{text.indexOf;}", false);
        for (String expression : List.of("s.slice()", "s.slice(1,2,3)", "s.slice(true)",
                "s.slice(undefined)", "s.slice(1,undefined)", "s.slice"))
            check("function f(s:string):void{" + expression + ";}", false);
        check("import * as text from \"std/string\"; function f():void{text.slice;}", false);
        for (String expression : List.of("s.substring()", "s.substring(1,2,3)", "s.substring(true)",
                "s.substring(undefined)", "s.substring(1,undefined)", "s.substring", "s.trim", "s.includes", "s.length=1",
                "s.trim(1)", "s.includes(1)", "s.contains(\"a\")")) {
            check("function f(s:string):void{" + expression + ";}", false);
        }
        check("function f(s:string|null):string{return s.trim();}", false);
        check("function f(s:string|null):string{if(s!==null){return s.trim();}return \"\";}", true);
        check("function f():string{return String(1);}", true);
        check("function f(x:unknown):string{if(typeof x===\"number\"){return String(x);}return \"\";}", true);
        for(String expression : List.of("String()", "String(1,2)", "String(\"1\")", "String(true)", "String(null)", "String(undefined)", "String")) {
            check("function f():void{let value=" + expression + ";}", false);
        }
        check("function f(x:unknown):string{return String(x);}", false);
        check("function f():void{let String=1;}", false);
        check("function f(String:number):void{}", false);
        check("function String():void{}", false);
        check("function f():string{return `${1}|${-0}|${1/0}`;}", true);
        check("function f(x:unknown):string{if(typeof x===\"number\"){return `${x}`;}return \"\";}", true);
        for (String type : List.of("boolean", "unknown", "number|null", "number|undefined", "number[]", "()=>number")) {
            check("function f(x:" + type + "):string{return `${x}`;}", false);
        }
        check("function f():number{return 5 % 2;}", true);
        check("function f():number{return \"5\" % 2;}", false);
        check("function f(x:unknown):number{return x % 2;}", false);
        check("function f(x:unknown):number{if(typeof x===\"number\"){return x % 2;}return 0;}", true);
        check("function f():string{return \"\\r\\x41\\u0042\\u{1f600}\\uD800\";}", true);
        check("function f():void{const twice=(x:number,):number=>x*2;}", true);
        check("function f():void{const twice=(x:number):number=>{return x*2;};}", true);
        check("function f():void{const twice=async (x:number):Promise<number>=>x*2;}", true);
        check("function v():void{} function f():void{const g=():void=>v();}", true);
        check("function f():void{const g=():void=>1;}", false);
        check("function f():void{const g=(x:number)=>x;}", false);
        check("function f():void{const g=(x):number=>x;}", false);
        check("function f():void{const g=x=>x;}", false);
        check("function f():void{const g=():number\n=>1;}", false);
        check("function f():void{const g=async\n():Promise<number>=>1;}", false);
        check("function f():void{const g=():number/*\n*/=>1;}", false);
        check("function f():void{const g=async/*\n*/():Promise<number>=>1;}", false);
        check("function f():void{const g=():number /*same line*/ =>1;}", true);
        check("function f():void{const g=async ():Promise<number>=>1;}", true);
        check("function f():void{const g=(x:number):number=>\"bad\";}", false);
        check("function f():void{const g=async ():number=>1;}", false);
        check("function f():void{const g=():Promise<number>=>1;}", false);
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
        check("function f(): void { try { throw { name: \"E\", message: \"x\" }; } catch(e) { if(e instanceof Error) { let m: string = e.message; } } }", true);
        check("function f(): null { let x = 0123; return null; }", false);
        check("function f(): null { return\nnull; }", false);
        check("function f(): null { throw/*\n*/{ code: \"E\", message: \"x\" }; }", false);
        check("async\nfunction f(): Promise<void> {}", false);
        check("function f(): null { let switch = 1; return null; }", false);
        check("function f(): null { let Object = 1; return null; }", false);
        check("function f(): null { let t: Record<string, unknown> = {}; let k = \"x\"; let b = Object.hasOwn(t,k); return null; }", true);
        check("function f(flag:boolean):number{return flag?1:2;}",true);
        check("function f(flag:boolean):void{let g:(n:number)=>number=flag?():number=>1:(n:number):number=>n;}",false);
        check("function f(flag:boolean):void{let g:(n:number)=>number=flag?():number=>1:():number=>2;}",true);
        check("function f(flag:boolean):void{let g=flag?():number=>1:(n:number):number=>n;}",false);
        check("function f(flag:boolean):void{let g:(n:number)=>number=flag?():string=>\"s\":(n:number):number=>n;}",false);
        check("function f(flag:boolean,r:Record<string,unknown>):number{return flag?r.x:0;}",false);
        check("class C{x:number;} class D{x:number;} function f(flag:boolean,c:C,d:D):void{let v=flag?c:d;}",false);
        check("function f(flag:boolean):void{let x:unknown=flag?1:\"s\";}",false);
        check("function v():void{} function f(flag:boolean):void{flag?v():null;}",false);
        check("function v():void{} function f(flag:boolean):void{flag?v():v();}",true);
        check("function f(flag:boolean,x:number|null):void{if(x!==null){flag?(x=null):0;let y:number=x;}}",false);
        check("function f():number{return 1?1:2;}",false);
        check("function f(flag:boolean):void{let x=flag?1:\"s\";}",false);
        check("function f(flag:boolean):void{let x=flag?1:null;let y=flag?undefined:2;}",true);
        check("class C{x:number;} function f(flag:boolean):C{return flag?{x:1}:{x:2};}",true);
        check("class C{x:number;} function f(flag:boolean):C{return flag?{x:1}:{x:\"bad\"};}",false);
        check("function f(flag:boolean):void{let x=flag?[]:[];}",false);
        check("function f(flag:boolean):void{let x:number[]=flag?[]:[1];}",true);
        check("function f(flag:boolean):number{return flag?1;}",false);
        check("function f(flag:boolean):number{return flag?:2;}",false);
        check("function f(x:number|null):number{return x!==null?x:0;}",true);
        check("class C {x?:number;} function f(c:C,k:string):void{Object.hasOwn(c,k);}",false);
        check("class C {x?:number;} function f(c:C):void{Object.hasOwn(c,\"missing\");}",false);
        check("class C {x?:number;} function f(c:C):void{if(Object.hasOwn(c,\"x\")){let n:number=c.x;}}",false);
        check("function f(r:Record<string,unknown>):void{const detached=Object.hasOwn;}",false);
        check("function f(r:Record<string,unknown>):void{Object.hasOwn(r);}",false);
        check("function f(r:Record<string,unknown>):void{Object.hasOwn(r,\"x\",\"y\");}",false);
        for(String type:java.util.List.of("number","unknown","number[]","null","undefined"))
            check("function f(x:"+type+"):void{Object.hasOwn(x,\"x\");}",false);
        check("class C { x?: string; } function f(): null { let c: C = {}; let x: string | undefined = c.x; return null; }", true);
        check("function f(x: unknown): void { if (!(x instanceof Error)) {} else { let name: string = x.name; } }", true);
        check("function f(x: unknown): void { if (!(x instanceof Error)) { let name: string = x.name; } }", false);
        check("function f(x: unknown): void { if (!!(x instanceof Error)) { let name: string = x.name; } }", true);
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
        check("function f(x: unknown): void { while (x instanceof Error) { let y: string = x.name; x = null; } }", true);
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
        rejectsAt("function f():void {late();function late():void{}}", "function late", "E1042");
        rejectsAt("class C{x:number;} function f():void{let u:unknown=C;}", "C;", "E3001");
        rejectsAt("function f():void{let a:unknown[]=[1];delete a[0];}", "delete", "E3017");
        check("import * as from from \"std/math\"; function f():number{return from.floor(1.5);}", true);
        check("import * as as from \"std/math\"; function f():void{let value:unknown=as;}", false);
        check("function f():void {let r:Record<string,unknown>={};}", true);
        check("function f(k:string):void {let r:Record<string,string>={a:\"x\",[k]:\"y\"};r.a=\"z\";r[k]=\"w\";let s:string|undefined=r.a;delete r[k];}", true);
        check("function f():void {let r:Record<string,string>={a:1};}", false);
        check("function f():void {let r:Record<string,string>={a:undefined};}", false);
        check("function f():void {let r:Record<string,string>={};r.x=1;}", false);
        check("function f(k:string):void {let r:Record<string,string>={};r[k]=undefined;}", false);
        check("function f():void {let r:Record<string,string>={};let s:string=r.x;}", false);
        check("function f():void {let r:Record<string,string>={};let u:Record<string,unknown>=r;}", false);
        check("function f():void {let r:Record<string,unknown>={};let u:Record<string,string>=r;}", false);
        check("function f():void {let r:Record<string,number>={};}", false);
        check("function f():void {let r:Record<number,string>={};}", false);
        check("function f(key:string):void {let r:Record<string,unknown>={\"content-type\":1,[key]:2};r={[key]:3};}", true);
        check("class C{x:number;} function f():void {let c:C={\"x\":1};}", true);
        check("function f():void {let r:Record<string,unknown>={a:1,\"\\u0061\":2};}", false);
        check("function f():void {let r:Record<string,unknown>={[\"\\u0061\"]:1,\"a\":2};}", false);
        rejectsAt("function f():void {let r:Record<string,unknown>={a:1,\"a\":2};}", "\"a\":2", "E3001");
        rejectsAt("function f():void {let r:Record<string,unknown>={a:1,[\"a\"]:2};}", "[\"a\"]", "E3001");
        rejectsAt("function f():void {let r:Record<string,unknown>={[1]:2};}", "1]", "E3018");
        check("function f(k:unknown):void {let r:Record<string,unknown>={[k]:2};}", false);
        check("function f():void {let r:Record<string,unknown>={[missing]:2};}", false);
        check("class C{x:number;} function f():void {let c:C={[\"x\"]:1};}", false);
        for (String expression : List.of("{a:1}", "[{a:1}]", "[[{a:1}]]", "true?{a:1}:{a:2}")) {
            check("function f():void {let r=" + expression + ";}", false);
        }
        check("function f():void {let r:unknown={a:1};let a:Record<string,unknown>[]=[{a:1}];}", true);
        check("function f():void {let r:Record<string,unknown>={nested:{a:1}};}", true);
        check("function f():void {let r:Record<string,unknown>={};}\n// @jsonable\nexport class C{x:number;}", true);
        check("function f():void {let r:Record<string,unknown> = {a:1,a:2};}", false);
        check("class C{x:number;} function f():void {let c:C={x:1,x:2};}", false);
        check("function f():void {let a:number[]=[1];delete a[0];}", false);
        check("function f():void {let a:unknown[]=[1];delete a[0];}", false);
        check("function f():void {g();function g():void{}}", false);
        check("function f():void {let h:()=>void=function():void{g();};function g():void{}h();}", false);
        check("function f():void {function g():void{g();}g();}", false);
        check("function f():void {let g:()=>void=function():void{g();};g();}", true);
        check("function f():void {let g=function():number{return g();};}", true);
        check("function f():void {let g=(function():number{return g();});}", true);
        check("function f():void {let g=async function():Promise<number>{return await g();};}", true);
        check("function f():void {let g:(ignored:number)=>number=function():number{return g(1);};}", true);
        check("function f():void {let g=g;}", false);
        check("function f():void {let g:number=(function():number{return g;})();}", false);
        check("function f():void {let g:()=>number=[function():number{return g();}][0];}", false);
        check("function f():void {let g=function():number{return later;};let later=1;}", false);
        check("function g():number{return 1;} function f():void {let x=g();function g():number{return 2;}let y=g();}", false);
        check("function g():number{return 1;} function f():void {let x=g();let g=function():number{return 2;};let y=g();}", true);
        check("class C{x:number;} function f():void{let x:unknown=C;}", false);
        check("class C{x:number;} function f():void{let x=typeof C;}", false);
        rejectsAt("function f():number{return 1;} function g():void{f=f;}", "f=f", "E3017");
        rejectsAt("function g():void{function f():void{}f=f;}", "function f", "E1042");
        rejectsAt("import * as math from \"std/math\"; function g():void{math.floor=math.floor;}", "math.floor=", "E3017");
        check("function f(f:number):number{return f;}", true);
        check("function g():void{function f(f:number):number{return f;}let x=f(1);}", false);
        check("function g():void{let f=function(f:number):number{return f;};let x=f(1);}", true);
        check("function f():number{return 1;} function g():void{let h:()=>number=f;h=f;}", true);
        System.out.println("Language16FrontendTest: passed");
    }
}
