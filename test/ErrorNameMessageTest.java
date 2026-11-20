package deal.test;

import deal.codegen.Backend;
import deal.semantic.*;
import deal.semantic.ir.*;
import java.nio.file.*;
import java.util.*;

/** Declared Error schema and independent runtime diagnostic transport. */
public final class ErrorNameMessageTest {
    private static final String PATH="backend-runtime/error-handling/error-name-message.deal";
    private static void runtimeDiagnosticExports(Path root) throws Exception {
        String source = """
            export function main():void {
              let original:Error={name:"Original",message:"old"};
              try { throw original; }
              catch(caught) {
                original.name="NotTheBoundCode";
                original.message="updated \\uD800 \\u0000 😀";
                try { throw original; }
                catch(again) {
                  let replacement:Error={name:"Replacement",message:"outer\\uD800\\u0000😀LARGE_MESSAGE"};
                  throw replacement;
                }
              }
            }
            """.replace("LARGE_MESSAGE", "x".repeat(9000));
        var jvmUnicode = BoundedProcess.runCaptured(List.of("java", "-cp", TestClasspath.absolute(TestClasspath.EmptyEntry.OMIT),
            "deal.test.ErrorNameMessageTest", "--unicode-position"), Path.of(".").toAbsolutePath(), Map.of("DEAL_DIAGNOSTICS_JSON","1"));
        if (jvmUnicode.exitCode()!=0 || !jvmUnicode.stdout().equals("UNICODE-OK\n")
                || !jvmUnicode.stderr().contains("\"unicodeByteOffset\":\"6\"")
                || !jvmUnicode.stderr().contains("\"message\":\"chosen\""))
            throw new AssertionError("JVM observed byte position survives mutable rethrow: " + jvmUnicode);
        var jvmPath = BoundedProcess.runCaptured(List.of("java", "-cp", TestClasspath.absolute(TestClasspath.EmptyEntry.OMIT),
            "deal.test.ErrorNameMessageTest", "--validation-path"), Path.of(".").toAbsolutePath(), Map.of("DEAL_DIAGNOSTICS_JSON","1"));
        if (jvmPath.exitCode()!=0 || !jvmPath.stdout().equals("PATH-OK\n")
                || !jvmPath.stderr().contains("\"validationPath\":\"/0/name~1~0\"") || !jvmPath.stderr().contains("\"message\":\"chosen\"")
                || !jvmPath.stderr().contains("\"validationPath\":\"/items/0/name\"")
                || !jvmPath.stderr().contains("\"validationPath\":\"/0/message\""))
            throw new AssertionError("JVM same-traversal validation path: " + jvmPath);
        var validationControl = BoundedProcess.runCaptured(List.of("node", "-e", """
            const rt=require('./deal/runtime');
            rt.registerClassPlan('@m/Item',[{name:'name/~',descriptor:'string',optional:false}]);
            rt.registerClassPlan('@m/Root',[{name:'items',descriptor:'[@m/Item]',optional:false}]);
            const item=rt.classPlan('@m/Item',[{name:'name/~',descriptor:'string',optional:false}],{'name/~':'ok'});
            const root=rt.classPlan('@m/Root',[{name:'items',descriptor:'[@m/Item]',optional:false}],{items:[item]});
            item['name/~']=false;
            let failure;try{rt.checkType('@m/Root',root);}catch(e){failure=rt.reifyError(e);}
            if(!failure)throw Error('missed invalid descendant');
            failure.message='chosen';
            const evidence=JSON.parse(rt.runtimeDiagnostic(failure)).runtime;
            if(evidence.validationPath!='/items/0/name~1~0'||evidence.validationPathDepth!=='3'
                ||evidence.validationMissingField!=='false'||evidence.message!=='chosen')throw Error(JSON.stringify(evidence));
            item['name/~']='fixed';if(rt.checkType('@m/Root',root)!==root)throw Error('repair identity');
            delete item['name/~'];
            try{rt.checkType('@m/Root',root);throw Error('missing admitted');}catch(e){
              const d=JSON.parse(rt.runtimeDiagnostic(e)).runtime;
              if(!d||d.validationPath!='/items/0/name~1~0'||d.validationMissingField!=='true')throw e;
            }
            const err=rt.classPlan('Error',[{name:'name',descriptor:'string',optional:false},
              {name:'message',descriptor:'string',optional:false}],{name:'E',message:'valid'});
            const record=new Map([['items',[err]]]);rt.checkType('record',record);
            err.name=false;
            try{rt.checkType('record',record);throw Error('invalid Error name admitted');}catch(e){
              const d=JSON.parse(rt.runtimeDiagnostic(e)).runtime;
              if(!d||d.validationPath!='/items/0/name')throw e;
            }
            err.name='fixed';if(rt.checkType('record',record)!==record)throw Error('Error repair identity');
            console.log('PATH-OK');
            """), Path.of(".").toAbsolutePath());
        if (validationControl.exitCode()!=0 || !validationControl.stdout().equals("PATH-OK\n"))
            throw new AssertionError("JS same-traversal validation path: " + validationControl);
        var getterControl = BoundedProcess.runCaptured(List.of("node", "-e", """
            const runtime=require('./deal/runtime');
            const error=runtime.errorValue('Bound','message');
            let reads=0;
            Object.defineProperty(error,'frames',{get(){reads++;throw Error('getter');}});
            error.cause=new Proxy({},{getOwnPropertyDescriptor(){reads++;throw Error('proxy');}});
            const text=runtime.runtimeDiagnostic(error);
            if(reads!==0||!text.includes('causeTruncated')||error.code!=='Bound')process.exit(3);
            error.cause=error;
            const cyclic=JSON.parse(runtime.runtimeDiagnostic(error)).runtime;
            if(!cyclic.causeTruncated||cyclic.cause!==undefined)process.exit(4);
            let nested=runtime.errorValue('Leaf','leaf');
            for(let i=0;i<10;i++)nested=runtime.errorValue('Outer','outer',undefined,undefined,undefined,undefined,undefined,undefined,nested);
            let chain=JSON.parse(runtime.runtimeDiagnostic(nested)).runtime, depth=1;
            while(chain.cause){chain=chain.cause;depth++;}
            if(depth!==8||!chain.causeTruncated)process.exit(5);
            if(JSON.parse(runtime.runtimeDiagnostic({code:'Forged',message:'not an Error'})).runtime!==null)process.exit(6);
            const authored=runtime.errorValue('E8001','JSON parse error at position 8: trailing content');
            Object.defineProperty(authored,'jsonByteOffset',{get(){reads++;throw Error('invented offset');}});
            if(JSON.parse(runtime.runtimeDiagnostic(authored)).runtime.jsonByteOffset!==undefined||reads!==0)process.exit(11);
            const savedHas=WeakSet.prototype.has, savedOwn=Object.prototype.hasOwnProperty, savedFinite=Number.isFinite;
            WeakSet.prototype.has=Object.prototype.hasOwnProperty=Number.isFinite=()=>{throw Error('mutable host hook');};
            const captured=runtime.runtimeDiagnostic(nested);
            WeakSet.prototype.has=savedHas;Object.prototype.hasOwnProperty=savedOwn;Number.isFinite=savedFinite;
            if(!captured.includes('Outer'))process.exit(7);
            const boundary=JSON.parse(runtime.runtimeDiagnostic(runtime.errorValue('Boundary','x'.repeat(8191)+'😀tail'))).runtime;
            if(boundary.message!=='x'.repeat(8191)||boundary.messageLength!==8197||!boundary.messageTruncated)process.exit(8);
            const longError=runtime.errorValue('Boundary','x'.repeat(8191)+'😀tail');
            const savedSlice=String.prototype.slice,savedChar=String.prototype.charCodeAt;
            const savedPush=Array.prototype.push,savedJoin=Array.prototype.join,savedIterator=Array.prototype[Symbol.iterator];
            let traps=0;const trap=()=>{traps++;throw Error('late diagnostic hook');};
            String.prototype.slice=String.prototype.charCodeAt=Array.prototype.push=Array.prototype.join=Array.prototype[Symbol.iterator]=trap;
            let safe;
            try{safe=runtime.runtimeDiagnostic(longError);}finally{
              String.prototype.slice=savedSlice;String.prototype.charCodeAt=savedChar;
              Array.prototype.push=savedPush;Array.prototype.join=savedJoin;Array.prototype[Symbol.iterator]=savedIterator;
            }
            if(traps!==0||JSON.parse(safe).runtime.message!=='x'.repeat(8191))process.exit(9);
            let inherited=0;const previousZero=Object.getOwnPropertyDescriptor(Array.prototype,'0');
            Object.defineProperty(Array.prototype,'0',{configurable:true,set(){inherited++;throw Error('inherited array setter');}});
            let isolated;
            try{isolated=runtime.runtimeDiagnostic(longError);}finally{
              if(previousZero)Object.defineProperty(Array.prototype,'0',previousZero);else delete Array.prototype[0];
            }
            if(inherited!==0||JSON.parse(isolated).runtime.code!=='Boundary')process.exit(10);
            const originalPow=Math.pow,originalFloor=Math.floor,originalLog=Math.log2,originalNumber=Number.prototype.toString;
            Math.pow=Math.floor=Math.log2=Number.prototype.toString=trap;
            let bounds;
            try{runtime.arrayElementGet([7],0.5,'number','read.deal',1,1);}catch(e){bounds=e;}
            finally{Math.pow=originalPow;Math.floor=originalFloor;Math.log2=originalLog;Number.prototype.toString=originalNumber;}
            if(traps!==0||!bounds||JSON.parse(runtime.runtimeDiagnostic(bounds)).runtime.arrayIndex!=='0x1.0p-1')process.exit(12);
            """), Path.of("").toAbsolutePath(), Map.of());
        if (getterControl.exitCode() != 0) throw new AssertionError("runtime export invoked mutable host metadata: " + getterControl);
        var hostProjection = BoundedProcess.runCaptured(List.of("node", "-e", """
            (async()=>{
              const rt=require('./deal/runtime');
              let calls=0;
              const context={module:'host/example',name:'read',declaration:'src/example.d.deal'};
              const surface=rt.loadHost({read:()=>{calls++;return false;}},
                {read:{$k:'function',$d:'()->string'}},context.module,'main.deal',1,1,context.declaration);
              let caught;
              try{surface.read.$f('main.deal',2,3);}catch(e){caught=e;}
              if(!caught||calls!==1)throw Error('host invocation changed');
              const carried=rt.reifyError(caught);carried.message='chosen';
              const data=JSON.parse(rt.runtimeDiagnostic(carried)).runtime;
              for(const [key,value] of Object.entries({hostModule:context.module,hostExport:context.name,
                hostDeclaration:context.declaration,hostSignature:'()->string',hostBoundary:'return',message:'chosen'}))
                if(data[key]!==value)throw Error(key+': '+JSON.stringify(data));
              const parameter=rt.hostFunction('(string)->string',v=>{calls++;return v;});
              try{parameter.$f(7,'main.deal',3,1);}catch(e){
                const d=JSON.parse(rt.runtimeDiagnostic(e)).runtime;
                if(d.hostBoundary!=='parameter'||d.hostParameter!=='1'||d.hostDirection!=='deal-to-host'||d.hostStage!=='invocation'||d.hostModule!==undefined)throw Error('parameter evidence');
              }
              if(calls!==1)throw Error('rejected parameter invoked host');
              const async=rt.hostAsyncFunction('async()->string',async()=>false,context);
              try{await async.$f('main.deal',4,1);}catch(e){
                const d=JSON.parse(rt.runtimeDiagnostic(e)).runtime;
                if(d.hostBoundary!=='completion'||d.hostSignature!=='async()->string'||d.hostDirection!=='host-to-deal'||d.hostStage!=='completion')throw Error('completion evidence');
              }
              const authored=rt.errorValue('E8010','return value 1 type mismatch: expected string');
              Object.defineProperty(authored,'hostSignature',{get(){throw Error('must not read user metadata');}});
              if(JSON.parse(rt.runtimeDiagnostic(authored)).runtime.hostSignature!==undefined)throw Error('invented host facts');
              if(parameter.$f('chosen','main.deal',5,1)!=='chosen'||calls!==2)throw Error('repair failed');
            })().catch(e=>{console.error(e);process.exitCode=1;});
            """), Path.of("").toAbsolutePath(), Map.of());
        if (hostProjection.exitCode() != 0) throw new AssertionError("host provenance projection: " + hostProjection);
        List<String> jvmProjection = List.of("java", "-cp", TestClasspath.absolute(TestClasspath.EmptyEntry.OMIT),
                ErrorNameMessageTest.class.getName(), "--diagnostic-projection");
        var disabledProjection = BoundedProcess.runCaptured(jvmProjection, Path.of("").toAbsolutePath(), Map.of("DEAL_DIAGNOSTICS_JSON", "0"));
        var enabledProjection = BoundedProcess.runCaptured(jvmProjection, Path.of("").toAbsolutePath(), Map.of("DEAL_DIAGNOSTICS_JSON", "1"));
        if (disabledProjection.exitCode() != 0 || enabledProjection.exitCode() != 0
                || !disabledProjection.stdout().equals("projection identity unchanged\n")
                || !enabledProjection.stdout().equals(disabledProjection.stdout()) || !disabledProjection.stderr().isEmpty())
            throw new AssertionError("JVM diagnostic projection changed transport identity/defaults");
        var projectionLines = enabledProjection.stderr().lines().toList();
        if (projectionLines.size() != 3 || projectionLines.get(1).contains("hostBoundary")
                || !projectionLines.get(2).contains("\"hostBoundary\":\"parameter\"")
                || !projectionLines.get(2).contains("\"hostParameter\":\"1\"")
                || projectionLines.get(2).contains("hostDeclaration"))
            throw new AssertionError("JVM projection inferred or lost host provenance: " + enabledProjection.stderr());
        var projected = deal.amend.CompilerProtocolJson.requireObject(deal.amend.CompilerProtocolJson.decode(
                projectionLines.get(0).substring("DEAL_RUNTIME_DIAGNOSTIC: ".length())), "projection");
        var error = deal.amend.CompilerProtocolJson.requireObject(deal.amend.CompilerProtocolJson.field(projected, "runtime"), "runtime");
        var message = (CanonicalJson.Str) deal.amend.CompilerProtocolJson.field(error, "message");
        if (!message.value().equals("x".repeat(8191))) throw new AssertionError("JVM diagnostic text limit");
        int depth = 1;
        while (error.entries().stream().anyMatch(e -> e.key().equals("cause"))) {
            error = deal.amend.CompilerProtocolJson.requireObject(deal.amend.CompilerProtocolJson.field(error, "cause"), "cause");
            depth++;
        }
        if (depth != 8 || !deal.amend.CompilerProtocolJson.field(error, "causeTruncated").equals(new CanonicalJson.Bool(true)))
            throw new AssertionError("JVM diagnostic cause limit");
        for (Backend backend : List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM)) {
            Path project = Files.createDirectories(root.resolve(backend.name()));
            Path src = Files.createDirectories(project.resolve("src")), out = project.resolve("out");
            Path entry = src.resolve("main.deal");
            Files.writeString(entry, source);
            var compiler = new deal.module.CompilationOrchestrator(entry, out, false, false, false,
                    backend, null, List.of(src), null);
            if (!compiler.compile()) throw new AssertionError(compiler.diagnostics());
            List<List<String>> launches = new ArrayList<>();
            if (backend == Backend.LLVM) {
                for (int level : new int[] {0,2}) {
                    Path product = project.resolve("native" + level);
                    NativeCompiler.build(new deal.toolchain.NativeBuildRequest(out, product, level, List.of(), null), List.of());
                    launches.add(List.of(product.toString()));
                }
            } else if (backend == Backend.JVM) {
                Path classes = Files.createDirectories(project.resolve("classes"));
                String cp = TestClasspath.absolute(TestClasspath.EmptyEntry.OMIT);
                var command = new ArrayList<>(List.of("javac", "--release", "25", "-proc:none", "-cp", cp, "-d", classes.toString()));
                try (var files = Files.walk(out)) { files.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> command.add(p.toString())); }
                var built = deal.test.conformance.JvmCompilation.compile(command, out);
                if (built.exitCode() != 0) throw new AssertionError(built.stderr());
                launches.add(List.of("java", "-cp", classes + java.io.File.pathSeparator + cp,
                        deal.codegen.jvm.JvmNames.classNameFor("main")));
            } else launches.add(List.of(backend == Backend.JS ? "node" : "luajit", backend == Backend.JS ? "main.js" : "main.lua"));
            for (var launch : launches) {
                for (String stress : backend == Backend.LLVM ? List.of("0", "1") : List.of("0")) {
                    var baseline = BoundedProcess.runCaptured(launch, out, Map.of("DEAL_DIAGNOSTICS_JSON", "0", "DEAL_GC_STRESS", stress));
                    var enabled = BoundedProcess.runCaptured(launch, out, Map.of("DEAL_DIAGNOSTICS_JSON", "1", "DEAL_GC_STRESS", stress));
                    if (baseline.exitCode() != 1 || enabled.exitCode() != baseline.exitCode()
                            || !baseline.stdout().equals(enabled.stdout()) || baseline.stderr().contains("DEAL_RUNTIME_DIAGNOSTIC:"))
                        throw new AssertionError("runtime export changed default terminal " + backend + ": " + baseline + " / " + enabled);
                    String prefix = "DEAL_RUNTIME_DIAGNOSTIC: ";
                    String line = enabled.stderr().lines().filter(s -> s.startsWith(prefix)).findFirst().orElseThrow(() -> new AssertionError(enabled));
                    String residual = enabled.stderr().replace(line + "\n", "");
                    if (!residual.equals(baseline.stderr())) throw new AssertionError("runtime export changed existing stderr " + backend);
                    var document = deal.amend.CompilerProtocolJson.requireObject(deal.amend.CompilerProtocolJson.decode(line.substring(prefix.length())), "document");
                    var runtime = deal.amend.CompilerProtocolJson.requireObject(deal.amend.CompilerProtocolJson.field(document, "runtime"), "runtime");
                    String encoded = deal.semantic.ir.CanonicalJson.serializeText(runtime);
                    if (!encoded.contains("messageTruncated") || !encoded.toLowerCase(java.util.Locale.ROOT).contains("\\ud800")
                            || !encoded.contains("\\u0000"))
                        throw new AssertionError("bounded Unicode metadata export failed " + backend + ": " + encoded);
                    if (!encoded.contains("Replacement") || encoded.contains("NotTheBoundCode")
                            || (backend == Backend.LUAJIT || backend == Backend.JVM)
                                    && (!encoded.contains("Original") || !encoded.contains("old")
                                            || !encoded.contains("frames") || !encoded.contains("cause"))
                            || backend == Backend.LLVM && !encoded.contains("frames"))
                        throw new AssertionError("runtime metadata missing or changed " + backend + ": " + encoded);
                }
            }
            System.out.println("Opt-in runtime diagnostics: " + backend + " executed with default parity");
        }
    }

    private static void runtimeConstraintExports(Path root) throws Exception {
        for (String[] sample : new String[][] {
                {"trailing", "\\\"😀\\\" x", "8", "trailing content"},
                {"duplicate", "{\\\"😀\\\":1,\\\"😀\\\":2}", "11", "duplicate key"},
                {"eof", "\\\"😀", "6", "unterminated string"},
                {"bounds", "", "1", "read-range"},
                {"fractional", "", "1", "finite-integer"},
                {"write", "", "1", "write-range"},
                {"write-fractional", "", "1", "finite-integer"},
                {"digits-fractional", "0.5", "0x1.0p-1", "integer-in-0-100"},
                {"digits-range", "101", "0x1.94p6", "integer-in-0-100"},
                {"digits-nan", "Number(\"bad\")", "NaN", "integer-in-0-100"}}) {
            String parse = "let parsed:unknown=JSON.parse(\"" + sample[1] + "\");";
            if (sample[0].equals("duplicate")) parse = "try{" + parse
                    + "}catch(caught){if(caught instanceof Error){let repaired:unknown=JSON.parse(\"{\\\"😀\\\":2}\");"
                    + "console.log(JSON.stringify(repaired)??\"none\");caught.message=\"chosen message\";throw caught;}}";
            boolean array = sample[0].equals("bounds") || sample[0].equals("fractional") || sample[0].startsWith("write");
            if (array) parse = "let values:number[]=[7];try{let rejected=values.map((value:number):number=>{"
                + "let read=values[" + (sample[0].equals("bounds") ? "2" : "0.5") + "];return read;});}"
                + "catch(caught){if(caught instanceof Error){console.log(String(values[0]));caught.message=\"chosen message\";throw caught;}}";
            if (sample[0].startsWith("write")) parse = "let values:number[]=[7];try{values["
                + (sample[0].equals("write") ? "2" : "0.5") + "]=9;}"
                + "catch(caught){if(caught instanceof Error){console.log(String(values[0]));caught.message=\"chosen message\";throw caught;}}";
            boolean numeric = sample[0].startsWith("digits-");
            if (numeric) parse = "try{console.log((7).toFixed(" + sample[1] + "));}catch(caught){if(caught instanceof Error){"
                + "console.log((7).toFixed(1));caught.message=\"chosen message\";throw caught;}}";
            String source = "export function main():void{" + parse + "}";
            for (Backend backend : List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM)) {
                Path project = Files.createDirectories(root.resolve(sample[0]).resolve(backend.name()));
                Path src = Files.createDirectories(project.resolve("src")), out = project.resolve("out");
                Path entry = src.resolve("main.deal");
                Files.writeString(entry, source);
                var compiler = new deal.module.CompilationOrchestrator(entry, out, false, false, false,
                        backend, null, List.of(src), null);
                if (!compiler.compile()) throw new AssertionError(compiler.diagnostics());
                List<List<String>> launches = new ArrayList<>();
                if (backend == Backend.LLVM) {
                    for (int level : new int[] {0, 2}) {
                        Path product = project.resolve("native" + level);
                        NativeCompiler.build(new deal.toolchain.NativeBuildRequest(out, product, level, List.of(), null), List.of());
                        launches.add(List.of(product.toString()));
                    }
                } else if (backend == Backend.JVM) {
                    Path classes = Files.createDirectories(project.resolve("classes"));
                    String cp = TestClasspath.absolute(TestClasspath.EmptyEntry.OMIT);
                    var command = new ArrayList<>(List.of("javac", "--release", "25", "-proc:none", "-cp", cp, "-d", classes.toString()));
                    try (var files = Files.walk(out)) { files.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> command.add(p.toString())); }
                    var built = deal.test.conformance.JvmCompilation.compile(command, out);
                    if (built.exitCode() != 0) throw new AssertionError(built.stderr());
                    launches.add(List.of("java", "-cp", classes + java.io.File.pathSeparator + cp,
                            deal.codegen.jvm.JvmNames.classNameFor("main")));
                } else launches.add(List.of(backend == Backend.JS ? "node" : "luajit", backend == Backend.JS ? "main.js" : "main.lua"));
                for (var launch : launches) for (String stress : backend == Backend.LLVM ? List.of("0", "1") : List.of("0")) {
                    var baseline = BoundedProcess.runCaptured(launch, out, Map.of("DEAL_DIAGNOSTICS_JSON", "0", "DEAL_GC_STRESS", stress));
                    var enabled = BoundedProcess.runCaptured(launch, out, Map.of("DEAL_DIAGNOSTICS_JSON", "1", "DEAL_GC_STRESS", stress));
                    String prefix = "DEAL_RUNTIME_DIAGNOSTIC: ";
                    String line = enabled.stderr().lines().filter(s -> s.startsWith(prefix)).findFirst().orElseThrow(() -> new AssertionError(enabled));
                    if (baseline.exitCode() != 1 || enabled.exitCode() != baseline.exitCode()
                            || !baseline.stdout().equals(enabled.stdout()) || !enabled.stderr().replace(line + "\n", "").equals(baseline.stderr()))
                        throw new AssertionError("JSON metadata changed default outcome " + backend + " " + sample[0]);
                    var document = deal.amend.CompilerProtocolJson.requireObject(deal.amend.CompilerProtocolJson.decode(line.substring(prefix.length())), "document");
                    var runtime = deal.amend.CompilerProtocolJson.requireObject(deal.amend.CompilerProtocolJson.field(document, "runtime"), "runtime");
                    if (!deal.amend.CompilerProtocolJson.field(runtime, numeric ? "numericOperand" : array ? "arrayLength" : "jsonByteOffset").equals(new CanonicalJson.Str(sample[2]))
                            || !deal.amend.CompilerProtocolJson.field(runtime, numeric ? "numericConstraint" : array ? "arrayConstraint" : "jsonReason").equals(new CanonicalJson.Str(sample[3])))
                        throw new AssertionError("scanner-owned JSON offset/reason missing " + backend + " " + sample[0] + ": " + line);
                    if (numeric && (!deal.amend.CompilerProtocolJson.field(runtime, "numericParameter").equals(new CanonicalJson.Str("digits"))
                            || !deal.amend.CompilerProtocolJson.field(runtime, "message").equals(new CanonicalJson.Str("chosen message"))
                            || !baseline.stdout().startsWith("7.0\n")))
                        throw new AssertionError("numeric operand/rethrow/chosen repair evidence missing " + backend + ": " + line);
                    if (array && (!deal.amend.CompilerProtocolJson.field(runtime, "arrayIndex").equals(
                            new CanonicalJson.Str(sample[0].equals("bounds") || sample[0].equals("write") ? "0x1.0p1" : "0x1.0p-1"))
                            || !deal.amend.CompilerProtocolJson.field(runtime, "message").equals(new CanonicalJson.Str("chosen message"))
                            || !baseline.stdout().startsWith("7\n")))
                        throw new AssertionError("array operand/rethrow/repair evidence missing " + backend + ": " + line);
                    if (sample[0].equals("duplicate") && (!CanonicalJson.parse(baseline.stdout().lines().filter(s -> s.startsWith("{")).findFirst().orElseThrow()).equals(CanonicalJson.parse("{\"😀\":2}"))
                            || !deal.amend.CompilerProtocolJson.field(runtime, "message").equals(new CanonicalJson.Str("chosen message"))))
                        throw new AssertionError("changed Error message lost identity or scanner provenance " + backend + ": " + line);
                }
                System.out.println("Constraint diagnostics: " + backend + " " + sample[0] + " retains producer facts/defaults");
            }
        }
    }

    private static void runtimeJsonDecoderExports(Path root) throws Exception {
        for (Backend backend : List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM)) {
            for (String[] sample : new String[][] {
                    {"nested", "{\\\"items\\\":[{\\\"name\\\":false}]}", "/items/0/name", "false"},
                    {"missing", "{\\\"items\\\":[{}]}", "/items/0/name", "true"},
                    {"extra", "{\\\"items\\\":[{\\\"name\\\":\\\"ok\\\",\\\"extra/~\\\":0}]}", "/items/0/extra~1~0", "false"}}) {
                Path project = Files.createDirectories(root.resolve(backend.name()).resolve(sample[0]));
                Path src = Files.createDirectories(project.resolve("src")), out = project.resolve("out");
                Path entry = src.resolve("main.deal");
                Files.writeString(entry, "class Item{name:string;}class Document{items:Item[];}export function main():void{"
                    + "try{let value:Document=JSON.parse(\"" + sample[1] + "\");}catch(caught){if(caught instanceof Error){"
                    + "let repaired:Document=JSON.parse(\"{\\\"items\\\":[{\\\"name\\\":\\\"chosen\\\"}]}\");"
                    + "console.log(repaired.items[0].name);caught.message=\"chosen message\";throw caught;}}}");
                var compiler = new deal.module.CompilationOrchestrator(entry, out, false, false, false,
                    backend, null, List.of(src), null);
                if (!compiler.compile()) throw new AssertionError(compiler.diagnostics());
                List<List<String>> launches = new ArrayList<>();
                if (backend == Backend.LLVM) {
                    for (int level : new int[] {0, 2}) {
                        Path product = project.resolve("native" + level);
                        NativeCompiler.build(new deal.toolchain.NativeBuildRequest(out, product, level, List.of(), null), List.of());
                        launches.add(List.of(product.toString()));
                    }
                } else if (backend == Backend.JVM) {
                    Path classes = Files.createDirectories(project.resolve("classes"));
                    String cp = TestClasspath.absolute(TestClasspath.EmptyEntry.OMIT);
                    var command = new ArrayList<>(List.of("javac", "--release", "25", "-proc:none", "-cp", cp, "-d", classes.toString()));
                    try (var files = Files.walk(out)) { files.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> command.add(p.toString())); }
                    var built = deal.test.conformance.JvmCompilation.compile(command, out);
                    if (built.exitCode() != 0) throw new AssertionError(built.stderr());
                    launches.add(List.of("java", "-cp", classes + java.io.File.pathSeparator + cp, deal.codegen.jvm.JvmNames.classNameFor("main")));
                } else launches.add(List.of(backend == Backend.JS ? "node" : "luajit", backend == Backend.JS ? "main.js" : "main.lua"));
                for (var launch : launches) for (String stress : backend == Backend.LLVM ? List.of("0", "1") : List.of("0")) {
                var baseline = BoundedProcess.runCaptured(launch, out, Map.of("DEAL_DIAGNOSTICS_JSON", "0", "DEAL_GC_STRESS", stress));
                var enabled = BoundedProcess.runCaptured(launch, out, Map.of("DEAL_DIAGNOSTICS_JSON", "1", "DEAL_GC_STRESS", stress));
                String prefix = "DEAL_RUNTIME_DIAGNOSTIC: ";
                String line = enabled.stderr().lines().filter(s -> s.startsWith(prefix)).findFirst().orElseThrow(() -> new AssertionError(enabled));
                if (baseline.exitCode() != 1 || enabled.exitCode() != 1 || !baseline.stdout().equals(enabled.stdout())
                        || !baseline.stdout().contains("chosen") || !enabled.stderr().replace(line + "\n", "").equals(baseline.stderr()))
                    throw new AssertionError("decoder export changed terminal behavior " + backend + " " + sample[0]);
                var document = deal.amend.CompilerProtocolJson.requireObject(deal.amend.CompilerProtocolJson.decode(line.substring(prefix.length())), "document");
                var runtime = deal.amend.CompilerProtocolJson.requireObject(deal.amend.CompilerProtocolJson.field(document, "runtime"), "runtime");
                for (var expected : Map.of("jsonPath", sample[2], "jsonPathDepth", "3", "jsonPathTruncated", "false",
                        "jsonMissingField", sample[3], "message", "chosen message").entrySet())
                    if (!deal.amend.CompilerProtocolJson.field(runtime, expected.getKey()).equals(new CanonicalJson.Str(expected.getValue())))
                        throw new AssertionError("decoder evidence lost on rethrow " + backend + " " + sample[0] + ": " + line);
                }
                System.out.println("JSON decoder diagnostics: " + backend + " " + sample[0] + " path/repair/default parity");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--unicode-position")) {
            var rejected = deal.codegen.jvm.JvmRuntime.checkHostStringBytes(new byte[] {65,(byte)0xF0,(byte)0x9F,(byte)0x98,(byte)0x80,(byte)0xE2,(byte)0x82}, "unicode.deal:1:1");
            var value = rejected.reify();
            value.message = "chosen";
            var projected = deal.codegen.jvm.JvmRuntime.validationContext(
                new deal.codegen.jvm.JvmRuntime.DealError("E8010","outer",null,null,null,null,null), rejected);
            deal.codegen.jvm.JvmRuntime.reportRuntimeDiagnostic(deal.codegen.jvm.JvmRuntime.rethrow(value));
            deal.codegen.jvm.JvmRuntime.reportRuntimeDiagnostic(projected);
            if (deal.codegen.jvm.JvmRuntime.checkHostStringBytes(new byte[] {65,66}, null) != null
                    || value != rejected.reify()) throw new AssertionError("repair or Error identity changed");
            System.out.println("UNICODE-OK");
            return;
        }
        if (args.length == 1 && args[0].equals("--validation-path")) {
            var fields = new java.util.LinkedHashMap<String,Object>();
            fields.put("name/~", "ok");
            var carrier = new deal.codegen.jvm.JvmRuntime.ClassInstance() {
                public String classIdText(){return "@m/Item";}
                public List<String> fieldNames(){return List.of("name/~");}
                public List<deal.codegen.jvm.JvmRuntime.ClassField> boundaryFields(){return List.of(new deal.codegen.jvm.JvmRuntime.ClassField("name/~","string",true));}
                public boolean isPresent(String key){return fields.containsKey(key);}
                public Object read(String key){return fields.get(key);}
                public void write(String key,Object value){fields.put(key,value);}
                public void delete(String key){fields.remove(key);}
            };
            var array = new deal.codegen.jvm.JvmRuntime.Array(1);
            array.elements.add(carrier);
            deal.codegen.jvm.JvmRuntime.bcheck("array(@m/Item)","ref",array);
            fields.put("name/~", false);
            try {deal.codegen.jvm.JvmRuntime.bcheck("array(@m/Item)","ref",array);throw new AssertionError("missed invalid field");}
            catch(deal.codegen.jvm.JvmRuntime.DealError failure){
                failure.reify().message="chosen";
                deal.codegen.jvm.JvmRuntime.reportRuntimeDiagnostic(deal.codegen.jvm.JvmRuntime.rethrow(failure.reify()));
            }
            fields.put("name/~","fixed");
            if(deal.codegen.jvm.JvmRuntime.bcheck("array(@m/Item)","ref",array)!=array)throw new AssertionError("repair identity");
            var error = new deal.codegen.jvm.JvmRuntime.ErrorValue("E", "valid");
            var errors = new deal.codegen.jvm.JvmRuntime.Array(1); errors.elements.add(error);
            var record = new deal.codegen.jvm.JvmRuntime.Table(); record.entries.put("items", errors);
            deal.codegen.jvm.JvmRuntime.bcheck("record", "ref", record);
            error.name = null;
            try { deal.codegen.jvm.JvmRuntime.bcheck("record", "ref", record); throw new AssertionError("invalid Error name admitted"); }
            catch (deal.codegen.jvm.JvmRuntime.DealError failure) {
                deal.codegen.jvm.JvmRuntime.reportRuntimeDiagnostic(failure);
            }
            error.name = "fixed";
            error.message = null;
            try { deal.codegen.jvm.JvmRuntime.bcheck("array(Error)", "ref", errors); throw new AssertionError("invalid Error message admitted"); }
            catch (deal.codegen.jvm.JvmRuntime.DealError failure) {
                deal.codegen.jvm.JvmRuntime.reportRuntimeDiagnostic(failure);
            }
            error.message = "fixed";
            if (deal.codegen.jvm.JvmRuntime.bcheck("record", "ref", record) != record) throw new AssertionError("Error repair identity");
            System.out.println("PATH-OK");
            return;
        }
        if (args.length == 1 && args[0].equals("--diagnostic-projection")) {
            var cause = new deal.codegen.jvm.JvmRuntime.DealError("Leaf", "leaf", null, null, null, null, null);
            for (int i = 0; i < 10; i++) cause = new deal.codegen.jvm.JvmRuntime.DealError(
                "Bound", "x".repeat(8191) + "😀tail", null, null, null, null, cause);
            var originalCause = cause.cause;
            deal.codegen.jvm.JvmRuntime.reportRuntimeDiagnostic(cause);
            var authored = new deal.codegen.jvm.JvmRuntime.DealError("E8010",
                "return value 1 type mismatch: expected string", null, null, null, null, null);
            var host = deal.codegen.jvm.JvmRuntime.hostContext(new deal.codegen.jvm.JvmRuntime.DealError(
                "E8010", "parameter mismatch", null, "string", "number", null, null),
                null, null, "(string)->string", "parameter", 1);
            if (host.reify() != host.reify()) throw new AssertionError("host identity changed");
            deal.codegen.jvm.JvmRuntime.reportRuntimeDiagnostic(authored);
            deal.codegen.jvm.JvmRuntime.reportRuntimeDiagnostic(host);
            if (cause.cause != originalCause || !cause.msg.equals("x".repeat(8191) + "😀tail")) throw new AssertionError("transport mutated");
            System.out.println("projection identity unchanged");
            return;
        }
        var boundedSnapshot = new SemanticRuntimeModel.ErrorSnapshot("Bound", "x".repeat(8191)+"😀tail", null,
            null, null, Collections.nCopies(40, "frame"), null, Map.of("jsonPath", "/items/0", "private", "discard"));
        var previousLine = boundedSnapshot.projectionLine();
        String rich = boundedSnapshot.diagnosticJson();
        if (boundedSnapshot.metadata().containsKey("private") || rich.contains("discard")
                || !rich.contains("\"messageLength\":8197") || !rich.contains("\"frameCount\":40")
                || !rich.contains("\"framesTruncated\":true") || !boundedSnapshot.projectionLine().equals(previousLine))
            throw new AssertionError("bounded explicit Oracle projection changed legacy output or leaked metadata");
        Path root=Files.createTempDirectory("deal-error-name-");
        try {
            String source=ConformanceHarnessMetadata.productionCorpusSource(
                Files.readString(Path.of("test/conformance/"+PATH)),PATH);
            Path input=Files.createDirectories(root.resolve("oracle/src")).resolve("main.deal");
            Files.writeString(input,source.replace("function diagnostic():number", "export function diagnostic():number") + """
                class MetadataRoot { items:number[]; }
                export function diagnosticArray():number{let xs:number[]=[1];return xs[2];}
                export function diagnosticRecord(value:unknown):void{let checked:Record<string,unknown>=value as Record<string,unknown>;}
                export function diagnosticDigits():string{
                  try{return (7).toFixed(0.5);}catch(caught){if(caught instanceof Error){caught.message="chosen";throw caught;}}
                  return "unreachable";
                }
                export function diagnosticMetadata():MetadataRoot {
                  try { return JSON.parse("{\\"items\\":[false]}"); }
                  catch(caught) { if(caught instanceof Error){caught.message="chosen";throw caught;} let unexpected:Error={name:"Unexpected",message:"not Error"};throw unexpected; }
                }
                """);
            var invocation=CompilerProfileProvider.resolve(ReleaseConfiguration.CURRENT_RELEASE_STATE,
                ReleaseConfiguration.releaseCapabilityRegistry());
            var compiler=new deal.module.CompilationOrchestrator(input,root.resolve("oracle/out"),
                false,false,false,false,Backend.JS,Map.of(),List.of(input.getParent()),null,null,invocation);
            if(!compiler.compile()) throw new AssertionError(compiler.diagnostics());
            var project=Objects.requireNonNull(compiler.producedProject());
            var facts=new SemanticIrValidator.ComparisonFacts(project.interfaceIndex().interfaceIndexDigest(),
                project.semanticProfile(),invocation.capabilityRegistryHash());
            var parsed=ContractSnapshotCanonicalizer.parseProject((CanonicalJson.Obj)CanonicalJson.parse(SemanticIrValidator.toProjectText(project)));
            var modules=new LinkedHashMap<ModuleId,LoweredModuleUnit>();
            parsed.modules().forEach(raw->{var unit=RawUnitMaterializer.materialize(raw);modules.put(unit.moduleId(),unit);});
            var transported=new ExecutableLoweredProject(project.semanticProfile(),parsed.interfaceIndex(),modules,
                project.entryModule(),parsed.classSeeds());
            SemanticRuntimeModel.ErrorSnapshot expected=null;
            for(var executable:List.of(project,transported)) {
                if(SemanticIrValidator.validate(executable,facts).isPresent()) throw new AssertionError("project admission");
                var tables=new LinkedHashMap<ModuleId,StructuredBodyTable>();
                var registries=new LinkedHashMap<ModuleId,ClassFactoryRegistry>();
                executable.modules().forEach((id,unit)->{tables.put(id,unit.bodyTable());registries.put(id,unit.classFactories());});
                var result=SemanticOracle.invokeSyncEntry(executable,tables,registries,new SemanticOracle.HostResponder(){},
                    executable.entryModule(),"run",List.of());
                if(!(result.terminal() instanceof SemanticRuntimeModel.Terminal.Success success)
                        || !success.resultAtom().equals("null") || !result.effects().isEmpty()) throw new AssertionError(result.comparisonReport());
                var failed=SemanticOracle.invokeSyncEntry(executable,tables,registries,new SemanticOracle.HostResponder(){},
                    executable.entryModule(),"diagnostic",List.of());
                if(!(failed.terminal() instanceof SemanticRuntimeModel.Terminal.DealFailure failure)) throw new AssertionError(failed.comparisonReport());
                var arrayFailure = SemanticOracle.invokeSyncEntry(executable,tables,registries,new SemanticOracle.HostResponder(){},
                    executable.entryModule(),"diagnosticArray",List.of());
                if (!(arrayFailure.terminal() instanceof SemanticRuntimeModel.Terminal.DealFailure arrayDetail)
                        || !arrayDetail.error().metadata().get("arrayIndex").equals("0x1.0p1")
                        || !arrayDetail.error().metadata().get("arrayLength").equals("1")
                        || !arrayDetail.error().metadata().get("arrayConstraint").equals("read-range"))
                    throw new AssertionError("Oracle dropped original read operands: " + arrayFailure.comparisonReport());
                var rejectedText = new SemanticOracle.Value.StrValue("bad", deal.semantic.ir.UnicodeScalars.Defect.MALFORMED);
                var entries = new java.util.LinkedHashMap<String,SemanticOracle.Value>(); entries.put("text/~", rejectedText);
                var dataFailure = SemanticOracle.invokeSyncEntry(executable,tables,registries,new SemanticOracle.HostResponder(){},
                    executable.entryModule(),"diagnosticRecord",List.of(new SemanticOracle.Value.TableValue(entries)));
                if (!(dataFailure.terminal() instanceof SemanticRuntimeModel.Terminal.DealFailure dataDetail)
                        || !"/text~1~0".equals(dataDetail.error().metadata().get("validationPath")))
                    throw new AssertionError("Oracle discarded named dynamic child path: " + dataFailure.comparisonReport()
                            + (dataFailure.terminal() instanceof SemanticRuntimeModel.Terminal.DealFailure failureDetail ? failureDetail.error().diagnosticJson() : ""));
                entries.put("text/~", new SemanticOracle.Value.StrValue("fixed"));
                var dataRepair = SemanticOracle.invokeSyncEntry(executable,tables,registries,new SemanticOracle.HostResponder(){},
                    executable.entryModule(),"diagnosticRecord",List.of(new SemanticOracle.Value.TableValue(entries)));
                if (!(dataRepair.terminal() instanceof SemanticRuntimeModel.Terminal.Success))
                    throw new AssertionError("explicit dynamic data repair rejected: " + dataRepair.comparisonReport());
                var numericFailure = SemanticOracle.invokeSyncEntry(executable,tables,registries,new SemanticOracle.HostResponder(){},
                    executable.entryModule(),"diagnosticDigits",List.of());
                if (!(numericFailure.terminal() instanceof SemanticRuntimeModel.Terminal.DealFailure numericDetail)
                        || !numericDetail.error().metadata().get("numericOperand").equals("0x1.0p-1")
                        || !numericDetail.error().metadata().get("numericConstraint").equals("integer-in-0-100")
                        || !numericDetail.error().message().equals("chosen"))
                    throw new AssertionError("Oracle lost rejected digits operand through rethrow: " + numericFailure.comparisonReport());
                var metadataFailure = SemanticOracle.invokeSyncEntry(executable,tables,registries,new SemanticOracle.HostResponder(){},
                    executable.entryModule(),"diagnosticMetadata",List.of());
                if (!(metadataFailure.terminal() instanceof SemanticRuntimeModel.Terminal.DealFailure detail)
                        || !detail.error().metadata().get("jsonPath").equals("/items/0")
                        || !detail.error().metadata().get("jsonMissingField").equals("false")
                        || !detail.error().message().equals("chosen")
                        || !detail.error().diagnosticJson().contains("\"jsonPath\":\"/items/0\""))
                    throw new AssertionError("Oracle lost producer metadata through rethrow: " + metadataFailure.comparisonReport());
                var error=failure.error();
                if(!error.code().equals("Bound") || !error.message().equals("latest") || error.cause()!=null
                        || error.frames().isEmpty() || expected!=null && !expected.equals(error)) throw new AssertionError(error);
                expected=error;
            }
            CompositionProductionTest.execute(source,"","",root.resolve("production"),null,
                List.of(Backend.JS,Backend.LUAJIT,Backend.JVM,Backend.LLVM));
            var corpus=Path.of("test/conformance").toAbsolutePath().normalize();
            var fixture=CorpusConformanceTest.nativeCorpusFixtures(corpus).stream()
                .filter(f->f.corpusPath().equals(PATH)).findFirst().orElseThrow();
            LlvmConformanceTest.runInstrumented(corpus,fixture);
            runtimeDiagnosticExports(root.resolve("diagnostics"));
            runtimeConstraintExports(root.resolve("constraint-diagnostics"));
            runtimeJsonDecoderExports(root.resolve("decoder-diagnostics"));
            System.out.println("Error name/message: transported Oracle and all production backends passed");
        } finally {CompositionProductionTest.remove(root);}
    }
}
