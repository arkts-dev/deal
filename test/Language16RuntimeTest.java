package deal.test;

import deal.Main;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real production compilation and execution; no backend may be skipped. */
public final class Language16RuntimeTest {
    private static void run(Path cwd, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(cwd.toFile())
            .redirectErrorStream(true).start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Timed out: " + Arrays.toString(command));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0 || !output.isBlank()) {
            throw new AssertionError(Arrays.toString(command) + " exit=" + process.exitValue() + "\n" + output);
        }
    }

    private static void rejectsRaw(String text, String needle, String replacement,
            deal.semantic.ir.SemanticIrValidator.ComparisonFacts facts) {
        if (!text.contains(needle)) throw new AssertionError("Missing raw fixture: " + needle);
        var failure = deal.semantic.ir.SemanticIrValidator.validateText(
            text.replace(needle, replacement), facts);
        if (failure.isEmpty() || !failure.get().message().contains("R-ENUM")) {
            throw new AssertionError("Expected structural R-ENUM rejection: " + failure);
        }
    }

    private static deal.semantic.ir.CanonicalJson.Obj field(deal.semantic.ir.CanonicalJson.Obj object,
            String key, deal.semantic.ir.CanonicalJson.Value value) {
        return new deal.semantic.ir.CanonicalJson.Obj(object.entries().stream()
            .map(e -> e.key().equals(key) ? new deal.semantic.ir.CanonicalJson.Entry(key, value) : e).toList());
    }

    private static deal.semantic.ir.CanonicalJson.Value mutateOp(deal.semantic.ir.CanonicalJson.Value value,
            String kind, java.util.function.UnaryOperator<deal.semantic.ir.CanonicalJson.Obj> mutation,
            boolean[] changed) {
        if (value instanceof deal.semantic.ir.CanonicalJson.Obj object) {
            boolean matches = object.entries().stream().anyMatch(e -> e.key().equals("kind")
                && e.value() instanceof deal.semantic.ir.CanonicalJson.Str s
                && (s.value().equals(kind) || (kind.equals("CONDITIONAL") && s.value().equals("BRANCH")
                    && payload(object).entries().stream().anyMatch(p -> p.key().equals("selector")
                        && p.value().equals(new deal.semantic.ir.CanonicalJson.Str("CONDITIONAL"))))));
            if (matches && !changed[0]) { changed[0] = true; return mutation.apply(object); }
            return new deal.semantic.ir.CanonicalJson.Obj(object.entries().stream()
                .map(e -> new deal.semantic.ir.CanonicalJson.Entry(e.key(), mutateOp(e.value(), kind, mutation, changed))).toList());
        }
        if (value instanceof deal.semantic.ir.CanonicalJson.Arr array)
            return new deal.semantic.ir.CanonicalJson.Arr(array.items().stream()
                .map(v -> mutateOp(v, kind, mutation, changed)).toList());
        return value;
    }

    private static void rejectsOp(String raw, String kind,
            java.util.function.UnaryOperator<deal.semantic.ir.CanonicalJson.Obj> mutation,
            deal.semantic.ir.SemanticIrValidator.ComparisonFacts facts) {
        boolean[] changed = {false};
        String text = deal.semantic.ir.CanonicalJson.serializeText(mutateOp(
            deal.semantic.ir.CanonicalJson.parse(raw), kind, mutation, changed));
        if (!changed[0]) throw new AssertionError("Missing operation " + kind);
        var failure = deal.semantic.ir.SemanticIrValidator.validateText(text, facts);
        if (failure.isEmpty() || !failure.get().message().contains("validatorRule R-ENUM,"))
            throw new AssertionError("Malformed " + kind + " must fail structural admission: " + failure);
    }

    private static deal.semantic.ir.CanonicalJson.Obj payload(deal.semantic.ir.CanonicalJson.Obj op) {
        return (deal.semantic.ir.CanonicalJson.Obj) op.entries().stream()
            .filter(e -> e.key().equals("payload")).findFirst().orElseThrow().value();
    }

    private static void arrayIsArray() throws Exception {
        Path root = Files.createTempDirectory("deal-array-is-array-");
        String source = Files.readString(Path.of("test/conformance/backend-runtime/arrays/is-array.deal"));
        CompositionProductionTest.execute(source, "", "", root, null,
            List.of(deal.codegen.Backend.JS, deal.codegen.Backend.LUAJIT, deal.codegen.Backend.JVM, deal.codegen.Backend.LLVM), Map.of(), true);
        Path entry = root.resolve("JS/src/main.deal");
        var invocation = deal.semantic.CompilerProfileProvider.resolve(deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
            deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
        var compiler = new deal.module.CompilationOrchestrator(entry, root.resolve("oracle"), false, false, false, false,
            deal.codegen.Backend.JS, null, List.of(entry.getParent()), Path.of("").toAbsolutePath(), null, invocation);
        if (!compiler.compile()) throw new AssertionError(compiler.diagnostics());
        var project = compiler.producedProject();
        var tables = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.StructuredBodyTable>();
        var factories = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.ClassFactoryRegistry>();
        project.modules().forEach((id, unit) -> { tables.put(id, unit.bodyTable()); factories.put(id, unit.classFactories()); });
        var result = deal.semantic.SemanticOracle.invokeSyncEntry(project, tables, factories,
            new deal.semantic.SemanticOracle.HostResponder() {}, project.entryModule(), "run", List.of());
        if (!(result.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success))
            throw new AssertionError("Array.isArray Oracle: " + result.comparisonReport());

        Path host = Files.createTempDirectory("deal-is-array-host-");
        for (String extension : List.of("d.deal", "js", "lua", "java", "c"))
            Files.copy(Path.of("test/conformance/host-fixtures/array_pop." + extension), host.resolve("probe." + extension));
        CompositionProductionTest.execute("""
            import * as host from "host/probe";
            function check(ok:boolean,label:string):void {if(!ok){throw {name:"FAIL",message:label};}}
            export function main():void {
              let xs=host.make();
              let alias:readonly unknown[]=xs;
              check(Array.isArray(xs) && Array.isArray(host.echo(xs)) && Array.isArray(alias),"host array kinds");
              let callbacks:(()=>number)[]=[():number=>7];
              host.poison(callbacks[0]);
              let destination=false;
              let failed=false;
              try {destination=Array.isArray(callbacks);}catch(e){failed=true;}
              check(failed && !destination,"unknown admission rejects corrupt callable");
              host.restoreFunction();
              check(Array.isArray(callbacks) && callbacks[0]()===7,"repair");
              check(Array.isArray(xs) && Array.isArray(alias) && xs[1]===9,"array aliases");
              let boxed=host.echo(xs);
              check(Array.isArray(boxed),"unknown host result");
              let recovered=boxed as number[];
              check(recovered===xs,"identity");
              failed=false;
              try {let wrong=boxed as string[];}catch(e){failed=true;}
              check(failed,"descriptor preservation");
            }
            """, "", "", Files.createTempDirectory("deal-is-array-hosts-"), host,
            List.of(deal.codegen.Backend.JS, deal.codegen.Backend.LUAJIT, deal.codegen.Backend.JVM, deal.codegen.Backend.LLVM), Map.of(), true);
    }

    private static void stringRecords() throws Exception {
        Path root=Files.createTempDirectory("deal-string-records-");
        String source="""
            export function run():void {
              let hide:(v:unknown)=>unknown=function(v:unknown):unknown{return v;};
              let r:Record<string,string>={name:"Ada"};
              let alias:Record<string,string>=r;
              let boxed:unknown=r;
              let recovered:Record<string,string>=boxed as Record<string,string>;
              if(recovered!==r){throw {name:"FAIL",message:"identity"};}
              let key="dynamic";
              r[key]="value";
              if((alias[key]??"")!=="value" || r.absent!==undefined){throw {name:"FAIL",message:"reads"};}
              delete r.name;
              if(Object.hasOwn(alias,"name") || alias.name!==undefined){throw {name:"FAIL",message:"delete"};}
              let parsed:Record<string,string>=JSON.parse(`{"a":"one","b":"two"}`);
              if((parsed.a??"")!=="one" || (JSON.stringify(parsed)??"")!==`{"a":"one","b":"two"}`){throw {name:"FAIL",message:"json"};}
              let caught=false;
              try {let bad:Record<string,string>=JSON.parse(`{"a":1}`);} catch(e){caught=true;}
              if(!caught){throw {name:"FAIL",message:"json rejection"};}
              caught=false;
              try {let bad:Record<string,string>=JSON.parse(`[]`);} catch(e){caught=true;}
              if(!caught){throw {name:"FAIL",message:"json object required"};}
              let nested:Record<string,string>[]=JSON.parse(`[{"a":"nested"},{}]`);
              if((nested[0].a??"")!=="nested" || Object.keys(nested[1]).length!==0){throw {name:"FAIL",message:"nested json"};}
              let unknownRecord:Record<string,unknown>={a:"one"};
              let old:unknown=hide(unknownRecord);
              caught=false;
              try {let bad:Record<string,string>=old as Record<string,string>;} catch(e){caught=true;}
              if(!caught){throw {name:"FAIL",message:"descriptor upgrade"};}
              let empty:Record<string,string>={};
              let emptyBox:unknown=hide(empty);
              caught=false;
              try {let bad:Record<string,unknown>=emptyBox as Record<string,unknown>;} catch(e){caught=true;}
              if(!caught){throw {name:"FAIL",message:"descriptor erasure"};}
              let computed:Record<string,string>={[key]:"ok"};
              if((computed[key]??"")!=="ok"){throw {name:"FAIL",message:"computed"};}
              console.log("string-records-ok");
            }
            export function main():void {run();}
            """;
        CompositionProductionTest.execute(source, "string-records-ok\n", "", root, null,
            List.of(deal.codegen.Backend.JS,deal.codegen.Backend.LUAJIT,deal.codegen.Backend.JVM,deal.codegen.Backend.LLVM), Map.of(), true);
        Path entry=root.resolve("JS/src/main.deal");
        var invocation=deal.semantic.CompilerProfileProvider.resolve(deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
            deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
        var compiler=new deal.module.CompilationOrchestrator(entry,root.resolve("oracle"),false,false,false,false,
            deal.codegen.Backend.JS,null,List.of(entry.getParent()),Path.of("").toAbsolutePath(),null,invocation);
        if(!compiler.compile())throw new AssertionError(compiler.diagnostics());
        var project=compiler.producedProject();
        var tables=new LinkedHashMap<deal.semantic.ir.ModuleId,deal.semantic.ir.StructuredBodyTable>();
        var factories=new LinkedHashMap<deal.semantic.ir.ModuleId,deal.semantic.ir.ClassFactoryRegistry>();
        project.modules().forEach((id,unit)->{tables.put(id,unit.bodyTable());factories.put(id,unit.classFactories());});
        var result=deal.semantic.SemanticOracle.invokeSyncEntry(project,tables,factories,
            new deal.semantic.SemanticOracle.HostResponder(){},project.entryModule(),"run",List.of());
        if(!(result.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success))
            throw new AssertionError("String Record Oracle: "+result.comparisonReport());
    }

    private static void stringRecordHosts() throws Exception {
        Path host = Files.createTempDirectory("deal-string-record-host-");
        Files.writeString(host.resolve("probe.d.deal"), """
            export function fresh():Record<string,string>;
            export function bad():Record<string,string>;
            export function freshUnknown():unknown;
            export function shared():Record<string,string>[];
            export function retry():Record<string,unknown>;
            export function keep(v:unknown):void;
            export function damage():void;
            export function repair():void;
            """);
        Files.writeString(host.resolve("probe.js"), """
            let saved, retry;
            exports.shared=()=>{retry=new Map([["a","ok"]]); return [retry,new Map([["a",1]])];};
            exports.retry=()=>retry;
            exports.fresh=()=>new Map([["a","ok"]]);
            exports.bad=()=>new Map([["a",1]]);
            exports.freshUnknown=exports.fresh;
            exports.keep=v=>{saved=v;};
            exports.damage=()=>{saved.set("bad",undefined);};
            exports.repair=()=>{saved.delete("bad");};
            """);
        Files.writeString(host.resolve("probe.lua"), """
            local saved,retry
            return {shared=function() retry={a="ok"};return {retry,{a=1}} end,
              retry=function() return retry end, fresh=function() return {a="ok"} end,
              bad=function() return {a=1} end,
              freshUnknown=function() return {a="ok"} end,
              keep=function(v) saved=v end,
              damage=function() saved.bad=require("deal.runtime").__UNDEFINED end,
              repair=function() saved.bad=nil end}
            """);
        Files.writeString(host.resolve("probe.java"), """
            final class HostProbe {
              static deal.codegen.jvm.JvmRuntime.Table saved,retry;
              public static Object shared(){retry=(deal.codegen.jvm.JvmRuntime.Table)fresh();var a=new deal.codegen.jvm.JvmRuntime.Array(2);a.elements.add(retry);a.elements.add(bad());return a;}
              public static Object retry(){return retry;}
              public static Object fresh(){var t=new deal.codegen.jvm.JvmRuntime.Table();t.write("a","ok");return t;}
              public static Object bad(){var t=new deal.codegen.jvm.JvmRuntime.Table();t.write("a",1.0);return t;}
              public static Object freshUnknown(){return fresh();}
              public static void keep(Object v){saved=(deal.codegen.jvm.JvmRuntime.Table)v;}
              public static void damage(){saved.entries.put("bad",deal.codegen.jvm.JvmRuntime.UNDEFINED);saved.keys.add("bad");}
              public static void repair(){saved.remove("bad");}
            }
            """);
        Files.writeString(host.resolve("probe.c"), """
            #include "host/probe.h"
            #include "native_helpers.h"
            static deal_handle saved,retry_handle;
            static deal_host_result fresh(const deal_value *a,size_t n){
              (void)a;(void)n;deal_value t;checked(deal_table_new(&t));set(t,"a",text("ok"));return result(t);}
            static deal_host_result bad(const deal_value *a,size_t n){
              (void)a;(void)n;deal_value t;checked(deal_table_new(&t));set(t,"a",number(1));return result(t);}
            static deal_host_result shared(const deal_value *a,size_t n){
              deal_value first=fresh(a,n).value;checked(deal_retain(first,&retry_handle));
              deal_value values=array("record[string]",2);checked(deal_array_set(values,0,first));
              checked(deal_array_set(values,1,bad(a,n).value));return result(values);}
            static deal_host_result retry(const deal_value *a,size_t n){(void)a;(void)n;return result(retained(retry_handle,NULL));}
            static deal_host_result keep(const deal_value *a,size_t n){
              (void)n;checked(deal_retain(a[0],&saved));return result(undefined_value());}
            static deal_host_result damage(const deal_value *a,size_t n){
              (void)a;(void)n;deal_check_input input;deal_error *cause=NULL;
              checked(deal_host_value_presentation(retained(saved,NULL),&input,&cause));if(cause)abort();
              deal_string *key=deal_text_new("bad",3);deal_data value=deal_data_of_undefined();
              deal_container_table_write(input.datum.reference,key,&value);return result(undefined_value());}
            static deal_host_result repair(const deal_value *a,size_t n){
              (void)a;(void)n;deal_check_input input;deal_error *cause=NULL;
              checked(deal_host_value_presentation(retained(saved,NULL),&input,&cause));if(cause)abort();
              deal_container_table_delete(input.datum.reference,deal_text_new("bad",3));return result(undefined_value());}
            const deal_host_export deal_host_exports_probe[]={
              {"fresh","()->record[string]",DEAL_HOST_EXPORT_FUNCTION,0,fresh,NULL,NULL},
              {"bad","()->record[string]",DEAL_HOST_EXPORT_FUNCTION,0,bad,NULL,NULL},
              {"freshUnknown","()->unknown",DEAL_HOST_EXPORT_FUNCTION,0,fresh,NULL,NULL},
              {"shared","()->[record[string]]",DEAL_HOST_EXPORT_FUNCTION,0,shared,NULL,NULL},
              {"retry","()->record",DEAL_HOST_EXPORT_FUNCTION,0,retry,NULL,NULL},
              {"keep","(unknown)->void",DEAL_HOST_EXPORT_FUNCTION,0,keep,NULL,NULL},
              {"damage","()->void",DEAL_HOST_EXPORT_FUNCTION,0,damage,NULL,NULL},
              {"repair","()->void",DEAL_HOST_EXPORT_FUNCTION,0,repair,NULL,NULL}};
            const deal_host_module deal_host_module_probe={DEAL_HOST_SPECIFIER_PROBE,deal_host_exports_probe,8u,NULL,0u,NULL};
            """);
        CompositionProductionTest.execute("""
            import * as host from "host/probe";
            export function main():void {
              let failed=false;
              try {host.shared();}catch(e){failed=true;}
              if(!failed){throw {name:"FAIL",message:"nested invalid host"};}
              let retried=host.retry();
              if((retried.a as string)!=="ok"){throw {name:"FAIL",message:"nested retry"};}
              let r:Record<string,string>=host.fresh();
              if((r.a??"")!=="ok"){throw {name:"FAIL",message:"host admission"};}
              let alias=r;
              host.keep(r);
              host.damage();
              if((r.a??"")!=="ok" || !Object.hasOwn(r,"bad")){throw {name:"FAIL",message:"reached read"};}
              let caught=false;
              try {let bad=r.bad;}catch(e){caught=true;}
              if(!caught){throw {name:"FAIL",message:"present undefined"};}
              caught=false;
              let key="bad";
              try {let bad=r[key];}catch(e){caught=true;}
              if(!caught){throw {name:"FAIL",message:"dynamic present undefined"};}
              caught=false;
              try {JSON.stringify(r);}catch(e){caught=true;}
              if(!caught){throw {name:"FAIL",message:"stringify stored descriptor"};}
              host.repair();
              if((JSON.stringify(r)??"")!=="{\\"a\\":\\"ok\\"}"){throw {name:"FAIL",message:"stringify repair"};}
              if(alias!==r || Object.hasOwn(r,"bad")){throw {name:"FAIL",message:"repair"};}
              caught=false;
              try {let bad=host.bad();}catch(e){caught=true;}
              if(!caught){throw {name:"FAIL",message:"host wrong value"};}
              let old=host.freshUnknown();
              caught=false;
              try {let bad=old as Record<string,string>;}catch(e){caught=true;}
              if(!caught){throw {name:"FAIL",message:"unknown host upgrade"};}
              console.log("string-record-hosts-ok");
            }
            """, "string-record-hosts-ok\n", "", Files.createTempDirectory("deal-string-record-hosts-"), host,
            List.of(deal.codegen.Backend.JS,deal.codegen.Backend.LUAJIT,deal.codegen.Backend.JVM,deal.codegen.Backend.LLVM), Map.of());
    }

    private static void stringRecordRuntimeAdmission() throws Exception {
        Path repository=Path.of("").toAbsolutePath();
        run(repository,"node","-e","""
            const rt=require('./deal/runtime');
            const r=rt.jsonDecode('{"a":"ok"}','record[string]','record.deal',3,4);
            if(rt.checkType('record[string]',r)!==r || rt.tableGet(r,'missing')!==undefined) throw Error('storage/absence');
            r.set('bad',undefined);
            if(rt.tableGet(r,'a')!=='ok') throw Error('read locality');
            for(const action of [()=>rt.tableGet(r,'bad'),()=>rt.tableSet(r,'a',1),()=>rt.jsonStringifyText(r,[])]) {
              let rejected=false;try{action()}catch(e){rejected=e.code==='E8001'}
              if(!rejected)throw Error('stored descriptor bypass');
            }
            if(r.get('a')!=='ok')throw Error('write rollback');
            r.delete('bad');
            if(rt.jsonStringifyText(r,[])!=='{"a":"ok"}')throw Error('repair');
            for(const text of ['[]','{"a":1}']) {
              let rejected=false;try{rt.jsonDecode(text,'record[string]')}catch(e){rejected=e.code==='E8001'}
              if(!rejected)throw Error('JSON shape/value');
            }
            """);
        run(repository,"luajit","-e","""
            local rt=require('deal.runtime');local json=require('deal.json')
            local r=json.decode('{"a":"ok"}','record[string]',{},'record.deal',3,4)
            assert(rt.check_canonical_type('record[string]',r)==r)
            r.bad=rt.__UNDEFINED
            local ok,e=pcall(json.encode,r);assert(not ok and e.code=='E8001')
            r.bad=nil;assert(json.encode(r)=='{"a":"ok"}')
            assert(not pcall(rt.check_canonical_type,'record',r))
            local ordered=json.decode('{"b":"ok","a":"ok"}','record[string]',{})
            ordered.b=true;ordered.a=1
            local admitted,failure=pcall(rt.check_canonical_type,'record[string]',ordered)
            assert(not admitted and failure.actual=='boolean')
            for _,text in ipairs({'[]','{"a":1}'}) do
              local accepted,err=pcall(json.decode,text,'record[string]',{})
              assert(not accepted and err.code=='E8001')
            end
            """);
        var table = new deal.codegen.jvm.JvmRuntime.Table("string");
        table.write("a", "ok");
        try {table.write("a", 1.0); throw new AssertionError("invalid write accepted");}
        catch (deal.codegen.jvm.JvmRuntime.DealError expected) {
            if (!table.read("a").equals("ok")) throw new AssertionError("write was not atomic");
        }
        table.entries.put("bad", deal.codegen.jvm.JvmRuntime.UNDEFINED);
        table.keys.add("bad");
        try {table.read("bad"); throw new AssertionError("present undefined accepted");}
        catch (deal.codegen.jvm.JvmRuntime.DealError expected) {}
        if (!table.read("a").equals("ok")) throw new AssertionError("read rescanned unrelated fields");
        table.remove("bad");
        if (deal.codegen.jvm.JvmRuntime.bcheck("record[string]", "ref", table) != table)
            throw new AssertionError("Record identity changed");
        var host = new deal.codegen.jvm.JvmRuntime.Table();
        host.write("a", 1.0);
        try {deal.codegen.jvm.JvmRuntime.bcheck("record[string]", "ref", host);
            throw new AssertionError("bad host admitted");}
        catch (deal.codegen.jvm.JvmRuntime.DealError expected) {}
        host.write("a", "fixed");
        deal.codegen.jvm.JvmRuntime.bcheck("record[string]", "ref", host);
        try {deal.codegen.jvm.JvmRuntime.bcheck("record", "ref", host);
            throw new AssertionError("host descriptor erased");}
        catch (deal.codegen.jvm.JvmRuntime.DealError expected) {}
        var pendingHost = new deal.codegen.jvm.JvmRuntime.Table();
        pendingHost.write("a", "ok");
        var transaction = new deal.codegen.jvm.JvmRuntime.RecordAdmission();
        transaction.check("unknown", pendingHost);
        try {transaction.check("record[string]", pendingHost);
            throw new AssertionError("staged unknown descriptor upgraded");}
        catch (deal.codegen.jvm.JvmRuntime.DealError expected) {}
        // Rejection never commits the earlier unknown admission to live storage.
        deal.codegen.jvm.JvmRuntime.bcheck("record[string]", "ref", pendingHost);
        var unknown = new deal.codegen.jvm.JvmRuntime.Table();
        unknown.write("a", "ok");
        deal.codegen.jvm.JvmRuntime.bcheck("unknown", "ref", unknown);
        try {deal.codegen.jvm.JvmRuntime.bcheck("record[string]", "ref", unknown);
            throw new AssertionError("unknown admission upgraded");}
        catch (deal.codegen.jvm.JvmRuntime.DealError expected) {}
    }

    private static void computedRecordKeys() throws Exception {
        String source = """
            export function main():void {
              let events:string="";
              let key:(s:string)=>string=function(s:string):string{events=events+s;return s;};
              let value:(s:string)=>number=function(s:string):number{events=events+s;return 1;};
              let r:Record<string,unknown>={"content-type":0,[key("a")]:value("A"),[key("b")]:value("B")};
              let a:number=r.a as number;let content:number=r["content-type"] as number;
              if(events!=="aAbB" || a!==1 || content!==0){throw {name:"FAIL",message:"order"};}
              let original:Record<string,unknown>=r;
              let duplicate:string="a";
              events="";
              let caught:boolean=false;
              try {r={[key("a")]:value("A"),[duplicate]:value("B"),[key("c")]:value("C")};}
              catch(e){caught=e instanceof Error;}
              if(!caught || events!=="aAB" || r!==original){throw {name:"FAIL",message:"duplicate rollback"};}
              let f:()=>number=function():number{return 7;};
              let functions:Record<string,unknown>={[key("fn")]:f};
              let recovered:()=>number=functions.fn as ()=>number;
              if(recovered!==f || recovered()!==7){throw {name:"FAIL",message:"identity"};}
              let quoted:Record<string,unknown>={"content-type":1,"__proto__":2};
              let quotedValue:number=quoted["__proto__"] as number;
              if(quotedValue!==2 || !Object.hasOwn(quoted,"content-type")){throw {name:"FAIL",message:"quoted static"};}
              let strange:Record<string,unknown>={"\\u0061":1,["nul\\0key"]:2,["\\ud800"]:3,["🌍"]:4,"__proto__":5};
              let nul:number=strange["nul\\0key"] as number;
              let lone:number=strange["\\ud800"] as number;
              let proto:number=strange["__proto__"] as number;
              if(nul!==2 || lone!==3 || proto!==5 || Object.keys(strange).length!==5){throw {name:"FAIL",message:"decoded keys"};}
              let closure:()=>Record<string,unknown>=function():Record<string,unknown>{return {[duplicate="changed"]:1};};
              let changed:Record<string,unknown>=closure();
              if(duplicate!=="changed" || !Object.hasOwn(changed,"changed")){throw {name:"FAIL",message:"key capture"};}
              let failure:Error={name:"KEY",message:"stop"};
              let bad:()=>string=function():string{throw failure;};
              events="";
              caught=false;
              try {r={[bad()]:value("X"),[key("z")]:value("Z")};}
              catch(e){if(e instanceof Error){caught=e===failure;}}
              if(!caught || events!=="" || r!==original){throw {name:"FAIL",message:"key abrupt"};}
              caught=false;
              try {r={[key("v")]:bad(),[key("z")]:value("Z")};}
              catch(e){if(e instanceof Error){caught=e===failure;}}
              if(!caught || events!=="v" || r!==original){throw {name:"FAIL",message:"value abrupt"};}
              console.log("records-ok");
            }
            """;
        Path root = Files.createTempDirectory("deal-record-keys-");
        CompositionProductionTest.execute(source, "records-ok\n", "", root, null,
            List.of(deal.codegen.Backend.JS,deal.codegen.Backend.LUAJIT,deal.codegen.Backend.JVM,deal.codegen.Backend.LLVM), Map.of());
        var invocation = deal.semantic.CompilerProfileProvider.resolve(
            deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
            deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
        Path oracleSource = root.resolve("JS/src/oracle.deal");
        Files.writeString(oracleSource, source.replace("export function main():void", "export function run():void")
            .replace("console.log(\"records-ok\");", "") + "\nexport function main():void{}\n");
        var compiler = new deal.module.CompilationOrchestrator(oracleSource, root.resolve("oracle"),
            false, false, false, false, deal.codegen.Backend.JS, Map.of(), List.of(root.resolve("JS/src")), null, null, invocation);
        if (!compiler.compile()) throw new AssertionError(compiler.diagnostics());
        var project = Objects.requireNonNull(compiler.producedProject());
        String text = deal.semantic.ir.SemanticIrValidator.toProjectText(project);
        var parsed = deal.semantic.ir.ContractSnapshotCanonicalizer.parseProject(
            (deal.semantic.ir.CanonicalJson.Obj)deal.semantic.ir.CanonicalJson.parse(text));
        var modules = new LinkedHashMap<deal.semantic.ir.ModuleId,deal.semantic.ir.LoweredModuleUnit>();
        parsed.modules().forEach(raw->{var unit=deal.semantic.ir.RawUnitMaterializer.materialize(raw);modules.put(unit.moduleId(),unit);});
        var transported = new deal.semantic.ir.ExecutableLoweredProject(project.semanticProfile(),parsed.interfaceIndex(),
            modules,project.entryModule(),parsed.classSeeds());
        var facts = new deal.semantic.ir.SemanticIrValidator.ComparisonFacts(project.interfaceIndex().interfaceIndexDigest(),
            project.semanticProfile(),invocation.capabilityRegistryHash());
        for (var executable : List.of(project,transported)) {
            var failure = deal.semantic.ir.SemanticIrValidator.validate(executable,facts);
            if (failure.isPresent()) throw new AssertionError(failure);
            var tables = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.StructuredBodyTable>();
            var registries = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.ClassFactoryRegistry>();
            executable.modules().forEach((id, unit) -> {tables.put(id, unit.bodyTable());registries.put(id, unit.classFactories());});
            var result = deal.semantic.SemanticOracle.invokeSyncEntry(executable, tables, registries,
                new deal.semantic.SemanticOracle.HostResponder(){}, executable.entryModule(), "run", List.of());
            if (!(result.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success success)
                    || !success.resultAtom().equals("undefined")) throw new AssertionError(result.comparisonReport());
        }
    }

    public static void main(String[] args) throws Exception {
        var invalid = deal.semantic.ir.BoundaryValueView.ofInvalidUnicode(deal.semantic.ir.UnicodeScalars.Defect.SURROGATE);
        var unknown = deal.semantic.ir.BoundaryExecutor.check(deal.semantic.ir.FailurePolicyId.TYPE_DESCRIPTOR,
            deal.semantic.ir.RuntimeDescriptor.Unknown.INSTANCE, invalid, deal.semantic.ir.BoundaryContext.none());
        if (!(unknown instanceof deal.semantic.ir.BoundaryOutcome.Fail))
            throw new AssertionError("unknown accepted invalid Unicode");
        String validText = "valid 🌍";
        if (deal.codegen.jvm.JvmRuntime.bcheck("unknown", "ref", validText) != validText)
            throw new AssertionError("unknown changed valid string identity");
        if (!deal.codegen.jvm.JvmRuntime.bcheck("unknown", "ref", "\ud800").equals("\ud800"))
            throw new AssertionError("lone unit changed at JVM ingress");
        for (Object unsupported : List.of(new Object())) {
            try { deal.codegen.jvm.JvmRuntime.bcheck("unknown", "ref", unsupported);
                throw new AssertionError("unknown accepted unsupported JVM value");
            } catch (deal.codegen.jvm.JvmRuntime.DealError expected) {
                if (!expected.code.equals("E8001")) throw new AssertionError(expected);
            }
        }
        arrayIsArray();
        stringRecords();
        stringRecordRuntimeAdmission();
        stringRecordHosts();
        computedRecordKeys();
        arraySliceMissingPositions();
        arraySearchMissingPositions();
        arrayJoinMissingPositions();
        arrayElementGetMissingPositions();
        objectKeysAdmission(false);
        objectKeysAdmission(true);
        fixtureAdmission("tables/builtin-has-own.deal", deal.semantic.ir.SemanticOpKind.HAS_FIELD, 17);
        fixtureAdmission("control-flow/conditional-expression.deal", deal.semantic.ir.SemanticOpKind.BRANCH, 102);
        fixtureAdmission("arrays/from-live.deal", deal.semantic.ir.SemanticOpKind.FOR_EACH, 17);
        fixtureAdmission("arrays/push.deal", deal.semantic.ir.SemanticOpKind.INDEX_WRITE, -1);
        fixtureAdmission("arrays/slice.deal", deal.semantic.ir.SemanticOpKind.ARRAY_SLICE, -1);
        fixtureAdmission("arrays/search.deal", deal.semantic.ir.SemanticOpKind.ARRAY_SEARCH, -1);
        fixtureAdmission("arrays/join.deal", deal.semantic.ir.SemanticOpKind.ARRAY_JOIN, -1);
        fixtureAdmission("arrays/map.deal", deal.semantic.ir.SemanticOpKind.ARRAY_ELEMENT_GET, -1);
        fixtureAdmission("stdlib/json/contextual-parse.deal", deal.semantic.ir.SemanticOpKind.JSON_PARSE, -1);
        fixtureAdmission("stdlib/string/substring-omitted-end.deal", deal.semantic.ir.SemanticOpKind.STDLIB_CALL, 12);
        fixtureAdmission("stdlib/string/slice.deal", deal.semantic.ir.SemanticOpKind.STDLIB_CALL, 26);
        fixtureAdmission("stdlib/string/search-positions.deal", deal.semantic.ir.SemanticOpKind.STDLIB_CALL, 94);
        fixtureAdmission("stdlib/string/unicode-case.deal", deal.semantic.ir.SemanticOpKind.STDLIB_CALL, 14);
        stringifyDeclaredFields();
        stringifyOracleDeclaredFields();
        jsonOperationOriginsAcrossCalls();
        jsonParseTraces();
        jsonParsePlanIsolation();
        jsonParseGeneratedArtifactIsolation();
        jsonParsePrivateProvider();
        jsonParseDeclarationProvider();
        arrayRemovalAdmission("pop-live-retry");
        arrayRemovalAdmission("splice-live-retry");
        arrayRemovalAdmission("slice-live-retry");
        arrayRemovalAdmission("search-live-retry");
        arrayRemovalAdmission("join-live-retry");
        arrayRemovalAdmission("map-live-retry");
        arrayRemovalAdmission("find-live-retry");
        arrayRemovalAdmission("predicates-live-retry");
        fixtureAdmission("arrays/predicates.deal", deal.semantic.ir.SemanticOpKind.ARRAY_HAS, -1);
        fixtureAdmission("arrays/find.deal", deal.semantic.ir.SemanticOpKind.ARRAY_ELEMENT_GET, -1);
        callableBodyAdmission();
        errorGraphAdmission();
        for (String fixture : List.of("core", "refinement", "assertion", "failures", "equality-loops", "json-values", "audit-scalars")) executeFixture(fixture);
        System.out.println("Language16RuntimeTest: passed (Oracle, JS, LuaJIT, JVM; deployed array descriptors)");
    }

    private static void arrayElementGetMissingPositions() {
        var source = new deal.codegen.jvm.JvmRuntime.Array(3);
        source.elementDescriptor = "!number";
        source.elements.add(7.0);source.elements.add(deal.codegen.jvm.JvmRuntime.MISSING);
        source.elements.add(deal.codegen.jvm.JvmRuntime.UNDEFINED);
        for (double index : new double[]{1, -1, 3, 0.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            try {
                deal.codegen.jvm.JvmRuntime.arrayElementGet(source,index,"!number","!number","map.deal:2:3");
                throw new AssertionError("strict missing/nonintegral/out-of-bounds read accepted: " + index);
            } catch (deal.codegen.jvm.JvmRuntime.DealError error) {
                if (!error.code.equals("E8002") || !error.msg.equals("array index out of bounds")) throw new AssertionError(error);
            }
        }
        if (deal.codegen.jvm.JvmRuntime.arrayElementGet(source,2,"!number","!number","map.deal:2:3") != deal.codegen.jvm.JvmRuntime.UNDEFINED
                || !deal.codegen.jvm.JvmRuntime.arrayElementGet(source,-0.0,"!number","!number","map.deal:2:3").equals(7.0))
            throw new AssertionError("strict read confused present undefined or negative zero");
        try {
            deal.codegen.jvm.JvmRuntime.arrayElementGet(source,2,"number","number","map.deal:2:3");
            throw new AssertionError("strict stored undefined accepted as number");
        } catch (deal.codegen.jvm.JvmRuntime.DealError error) {
            if (!error.code.equals("E8003") || !error.msg.equals("array element 3 type mismatch")
                    || !error.expected.equals("number") || !error.actual.equals("undefined") || error.cause == null)
                throw new AssertionError(error);
        }
        for (double index : new double[]{1,-1,3,.5,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
            if (deal.codegen.jvm.JvmRuntime.arrayHas(source,index)) throw new AssertionError("presence admitted missing/invalid position " + index);
        if (!deal.codegen.jvm.JvmRuntime.arrayHas(source,-0.0) || !deal.codegen.jvm.JvmRuntime.arrayHas(source,2))
            throw new AssertionError("presence rejected stored undefined/negative zero");
        if(source.length()!=3 || source.elements.get(1)!=deal.codegen.jvm.JvmRuntime.MISSING)
            throw new AssertionError("strict read mutated source");
    }

    private static void arrayJoinMissingPositions() {
        var source = new deal.codegen.jvm.JvmRuntime.Array(3);
        source.elementDescriptor = "string";
        source.elements.add("a"); source.elements.add(deal.codegen.jvm.JvmRuntime.MISSING); source.elements.add("b");
        if (!deal.codegen.jvm.JvmRuntime.arrayJoin(source, "|", "join.deal:2:3").equals("a||b"))
            throw new AssertionError("join holes contribute empty strings with separators");
        source.elements.set(1, deal.codegen.jvm.JvmRuntime.UNDEFINED);
        try {
            deal.codegen.jvm.JvmRuntime.arrayJoin(source, "|", "join.deal:2:3");
            throw new AssertionError("join stored undefined must reject");
        } catch (deal.codegen.jvm.JvmRuntime.DealError error) {
            if (!error.code.equals("E8003") || !error.msg.equals("array element 2 type mismatch")
                    || !error.expected.equals("string") || !error.actual.equals("undefined") || error.cause == null)
                throw new AssertionError(error);
        }
        if (source.length() != 3 || source.elements.get(1) != deal.codegen.jvm.JvmRuntime.UNDEFINED)
            throw new AssertionError("join failure changed source presence");
    }

    private static void arraySearchMissingPositions() {
        var source = new deal.codegen.jvm.JvmRuntime.Array(0);
        source.elements.add(deal.codegen.jvm.JvmRuntime.MISSING);
        source.elements.add(deal.codegen.jvm.JvmRuntime.UNDEFINED);
        source.elements.add("bad");
        source.length = 3;
        source.elementDescriptor = "!number";
        if (!Boolean.TRUE.equals(deal.codegen.jvm.JvmRuntime.arraySearch(source,
                deal.codegen.jvm.JvmRuntime.UNDEFINED, 0, true, "!number", "!number", "search.deal:2:3"))
                || !Double.valueOf(1).equals(deal.codegen.jvm.JvmRuntime.arraySearch(source,
                    deal.codegen.jvm.JvmRuntime.UNDEFINED, 0, false, "!number", "!number", "search.deal:2:3")))
            throw new AssertionError("includes reads holes as undefined; indexOf skips holes and stops at stored undefined");
        source.elements.set(1, 7.0);
        source.elementDescriptor = "number";
        if (!Double.valueOf(1).equals(deal.codegen.jvm.JvmRuntime.arraySearch(source,
                7.0, 0, false, "number", "number", "search.deal:2:3")))
            throw new AssertionError("indexOf must skip absent positions before admission");
        for (boolean includes : List.of(false, true)) {
            try {
                deal.codegen.jvm.JvmRuntime.arraySearch(source, 8.0, 0, includes,
                    "number", "number", "search.deal:2:3");
                throw new AssertionError("reached bad source element must reject");
            } catch (deal.codegen.jvm.JvmRuntime.DealError error) {
                if (!error.code.equals("E8003") || !error.msg.equals("array element 3 type mismatch")
                        || !error.actual.equals("string") || error.cause == null)
                    throw new AssertionError(error);
            }
        }
        if (source.length() != 3 || !source.elements.get(2).equals("bad"))
            throw new AssertionError("search failure mutated source");
    }

    private static void arraySliceMissingPositions() {
        var source = new deal.codegen.jvm.JvmRuntime.Array(0);
        source.elementDescriptor = "!number";
        source.elements.add(1.0);
        source.elements.add(deal.codegen.jvm.JvmRuntime.MISSING);
        source.elements.add(deal.codegen.jvm.JvmRuntime.UNDEFINED);
        source.length = 3;
        var result = deal.codegen.jvm.JvmRuntime.arraySlice(source, 1, 3, "!number", "!number", "slice.deal:1:1");
        if (result == source || result.length() != 2 || result.elements.get(0) != deal.codegen.jvm.JvmRuntime.MISSING
                || result.elements.get(1) != deal.codegen.jvm.JvmRuntime.UNDEFINED || source.length() != 3)
            throw new AssertionError("slice must preserve missing separately from present undefined");
        source.elementDescriptor = "number";
        if (deal.codegen.jvm.JvmRuntime.arraySlice(source, 1, 2, "number", "number", "slice.deal:1:1")
                .elements.getFirst() != deal.codegen.jvm.JvmRuntime.MISSING)
            throw new AssertionError("missing slice position must skip element admission");
        try {
            deal.codegen.jvm.JvmRuntime.arraySlice(source, 2, 3, "number", "number", "slice.deal:1:1");
            throw new AssertionError("stored undefined must be checked");
        } catch (deal.codegen.jvm.JvmRuntime.DealError error) {
            if (!error.code.equals("E8003") || !error.msg.equals("array element 3 type mismatch")
                    || !error.actual.equals("undefined") || error.cause == null)
                throw new AssertionError(error);
        }
    }

    private static void stringifyDeclaredFields() {
        var rt = new Object() {
            Object encode(Object value) {
                return deal.codegen.jvm.JvmRuntime.stdlibInvoke("STDLIB_CALL", "JSON_STRINGIFY",
                    "audit", "-", "-", "audit.deal:9:3", new Object[]{value});
            }
        };
        class Instance implements deal.codegen.jvm.JvmRuntime.ClassInstance {
            final String identity;
            final List<deal.codegen.jvm.JvmRuntime.ClassField> plan;
            final Map<String,Object> values = new LinkedHashMap<>();
            Instance(String identity, List<deal.codegen.jvm.JvmRuntime.ClassField> plan) {
                this.identity = identity; this.plan = plan;
            }
            public String classIdText() { return identity; }
            public List<String> fieldNames() { return plan.stream().map(deal.codegen.jvm.JvmRuntime.ClassField::name).toList(); }
            public List<deal.codegen.jvm.JvmRuntime.ClassField> boundaryFields() { return plan; }
            public boolean isPresent(String field) { return values.containsKey(field); }
            public Object read(String field) { return values.getOrDefault(field, deal.codegen.jvm.JvmRuntime.MISSING); }
            public void write(String field, Object value) { values.put(field, value); }
            public void delete(String field) { values.remove(field); }
        }
        var leafPlan = List.of(new deal.codegen.jvm.JvmRuntime.ClassField("value", "number", true));
        var leaf = new Instance("@serialization/Leaf", leafPlan);
        leaf.write("value", 7.0);
        var owner = new Instance("@serialization/Owner", List.of(
            new deal.codegen.jvm.JvmRuntime.ClassField("name", "string", true),
            new deal.codegen.jvm.JvmRuntime.ClassField("child", leaf.identity, true),
            new deal.codegen.jvm.JvmRuntime.ClassField("items", "array(number)", true),
            new deal.codegen.jvm.JvmRuntime.ClassField("note", "undefinedable(string)", false),
            new deal.codegen.jvm.JvmRuntime.ClassField("required", "undefinedable(string)", true)));
        var items = new deal.codegen.jvm.JvmRuntime.Array(2);
        items.elements.add(1.0); items.elements.add(2.0); items.elementDescriptor = "number";
        owner.write("name", "kept"); owner.write("child", leaf); owner.write("items", items);
        owner.write("required", deal.codegen.jvm.JvmRuntime.UNDEFINED);
        deal.codegen.jvm.JvmRuntime.bcheck(owner.identity, "ref", owner);
        String expected = "{\"name\":\"kept\",\"child\":{\"value\":7},\"items\":[1,2]}";
        if (!rt.encode(owner).equals(expected)) throw new AssertionError("valid class stringify");
        java.util.function.Consumer<String> rejects = code -> {
            try { rt.encode(owner); throw new AssertionError("corrupted class serialized"); }
            catch (deal.codegen.jvm.JvmRuntime.DealError error) {
                if (!error.code.equals(code) || !error.origin.equals("audit.deal:9:3"))
                    throw new AssertionError("serialization boundary/origin", error);
                if (code.equals("E8003") && (error.cause == null || !error.cause.origin.equals(error.origin)))
                    throw new AssertionError("element cause origin", error);
            }
        };
        owner.write("name", 42.0); rejects.accept("E8001"); owner.write("name", "kept");
        owner.write("note", false); rejects.accept("E8001"); owner.write("note", deal.codegen.jvm.JvmRuntime.UNDEFINED);
        if (!rt.encode(owner).equals(expected)) throw new AssertionError("undefined omission");
        owner.delete("required"); rejects.accept("E8001"); owner.write("required", deal.codegen.jvm.JvmRuntime.UNDEFINED);
        leaf.write("value", "bad"); rejects.accept("E8001"); leaf.write("value", 7.0);
        var sibling = new Instance("@serialization/Sibling", leafPlan); sibling.write("value", 7.0);
        owner.write("child", sibling); rejects.accept("E8001"); owner.write("child", leaf);
        items.elements.set(1, "bad"); rejects.accept("E8003"); items.elements.set(1, 2.0);
        if (owner.read("child") != leaf || owner.read("items") != items || !rt.encode(owner).equals(expected))
            throw new AssertionError("same graph repair/identity");
        items.elementDescriptor = "string";
        try { rt.encode(owner); throw new AssertionError("persistent descriptor mismatch serialized"); }
        catch (deal.codegen.jvm.JvmRuntime.DealError error) {
            if (!error.code.equals("E8001") || !error.msg.equals("expected array")
                    || !error.expected.equals("array") || !error.actual.equals("array"))
                throw new AssertionError("persistent array projection", error);
        }
        items.elementDescriptor = "number";
        var ordered = new Instance("@serialization/Ordered", List.of(
            new deal.codegen.jvm.JvmRuntime.ClassField("first", "unknown", true),
            new deal.codegen.jvm.JvmRuntime.ClassField("name", "string", true),
            new deal.codegen.jvm.JvmRuntime.ClassField("grid", "array(array(number))", true)));
        var row = new deal.codegen.jvm.JvmRuntime.Array(1);
        row.elements.add(false); row.elementDescriptor = "number";
        var grid = new deal.codegen.jvm.JvmRuntime.Array(1);
        grid.elements.add(row); grid.elementDescriptor = "array(number)";
        ordered.write("first", ordered); ordered.write("name", 42.0); ordered.write("grid", grid);
        try { rt.encode(ordered); throw new AssertionError("earlier cycle serialized"); }
        catch (deal.codegen.jvm.JvmRuntime.DealError error) {
            if (!error.msg.equals("cyclic value cannot be encoded as JSON") || error.cause != null)
                throw new AssertionError("earlier cycle ownership", error);
        }
        ordered.write("first", null);
        try { rt.encode(ordered); throw new AssertionError("later string mismatch serialized"); }
        catch (deal.codegen.jvm.JvmRuntime.DealError error) {
            if (!error.msg.equals("expected string")) throw new AssertionError("reached field order", error);
        }
        ordered.write("name", "kept");
        try { rt.encode(ordered); throw new AssertionError("nested array mismatch serialized"); }
        catch (deal.codegen.jvm.JvmRuntime.DealError error) {
            if (!error.code.equals("E8003") || !error.expected.equals("[number]")
                    || !error.actual.equals("array") || error.cause == null
                    || !error.cause.code.equals("E8001") || !error.cause.msg.equals("expected number")
                    || !error.origin.equals("audit.deal:9:3") || !error.cause.origin.equals(error.origin))
                throw new AssertionError("nested array unwrapped leaf", error);
        }
        row.elements.set(0, 1.0);
        grid.elements.add(row);
        if (!rt.encode(ordered).equals("{\"first\":null,\"name\":\"kept\",\"grid\":[[1],[1]]}"))
            throw new AssertionError("shared child repair must not be a cycle");
    }

    private static void stringifyOracleDeclaredFields() throws Exception {
        Path work = Files.createTempDirectory(Path.of("build"), "stringify-live-oracle-").toAbsolutePath();
        try {
            Path src = Files.createDirectories(work.resolve("src"));
            Path entry = src.resolve("main.deal"), declaration = src.resolve("probe.d.deal");
            Files.writeString(declaration, "export function keep(v:unknown):void;\nexport function change(mode:number):void;\n");
            Files.writeString(entry, """
                import * as probe from "host/probe";
                class Place { city:string; }
                class Other { city:string; }
                class Item { first:unknown; name:string; grid:number[][]; required:string|undefined; home:Place; }
                export function run():null {
                  let item:Item={first:null,name:"kept",grid:[[1]],required:undefined,home:{city:"kept"}};
                  probe.keep(item);
                  probe.change(1);
                  try { JSON.stringify(item); throw {name:"TEST",message:"missed cycle"}; }
                  catch(e) { let error=e as Error; if(error.message !== "cyclic value cannot be encoded as JSON") {throw error;} }
                  probe.change(2);
                  try { JSON.stringify(item); throw {name:"TEST",message:"missed kind"}; }
                  catch(e) { let error=e as Error; if(error.message !== "expected string") {throw error;} }
                  probe.change(3);
                  try { JSON.stringify(item); throw {name:"TEST",message:"missed element"}; }
                  catch(e) { let error=e as Error; if(error.name !== "E8003") {throw error;} }
                  probe.change(4);
                  if ((JSON.stringify(item) as string) !== '{"first":null,"name":"kept","grid":[[1]],"home":{"city":"kept"}}') {throw {name:"TEST",message:"repair"};}
                  probe.change(5);
                  try { JSON.stringify(item); throw {name:"TEST",message:"missed nominal"}; }
                  catch(e) { let error=e as Error; if(error.name !== "E8001") {throw error;} }
                  probe.change(6);
                  if ((JSON.stringify(item) as string) !== '{"first":null,"name":"kept","grid":[[1]],"home":{"city":"kept"}}') {throw {name:"TEST",message:"nominal repair"};}
                  probe.change(7);
                  try { JSON.stringify(item); throw {name:"TEST",message:"missed malformed transport"}; }
                  catch(e) { let error=e as Error; if(error.message !== "expected string, got invalid Unicode scalar encoding") {throw error;} }
                  probe.change(8);
                  try { JSON.stringify(item); throw {name:"TEST",message:"missed later cycle"}; }
                  catch(e) { let error=e as Error; if(error.message !== "cyclic value cannot be encoded as JSON") {throw error;} }
                  probe.change(9);
                  if ((JSON.stringify(item) as string) !== '{"first":{"bad":"kept"},"name":"kept","grid":[[1]],"home":{"city":"kept"}}') {throw {name:"TEST",message:"transport repair"};}
                  return null;
                }
                export function main():void {return ;}
                """);
            Files.writeString(work.resolve("deal.json"),
                "{\"languageVersion\":\"1.6\",\"stdlib\":\"1.6\",\"moduleRoots\":[\"src\"]}");
            var invocation = deal.semantic.CompilerProfileProvider.resolve(
                deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
            var compiler = new deal.module.CompilationOrchestrator(entry, work.resolve("out"),
                false,false,false,false,deal.codegen.Backend.JS, Map.of("host/probe",declaration.toString()),
                List.of(src),null,null,invocation);
            if (!compiler.compile()) throw new AssertionError("stringify Oracle compile: " + compiler.diagnostics());
            var project = Objects.requireNonNull(compiler.producedProject());
            var tables = new LinkedHashMap<deal.semantic.ir.ModuleId,deal.semantic.ir.StructuredBodyTable>();
            var registries = new LinkedHashMap<deal.semantic.ir.ModuleId,deal.semantic.ir.ClassFactoryRegistry>();
            project.modules().forEach((id,unit)->{tables.put(id,unit.bodyTable());registries.put(id,unit.classFactories());});
            var responder = new deal.semantic.SemanticOracle.HostResponder() {
                deal.semantic.SemanticOracle.Value.ClassValue saved;
                deal.semantic.SemanticOracle.Value.ArrayValue row;
                deal.semantic.SemanticOracle.Value.ClassValue home;
                deal.semantic.SemanticOracle.Value.TableValue malformed;
                public SyncOutcome call(deal.semantic.ir.ModuleId module,String name,
                        deal.semantic.ir.RuntimeDescriptor.Func descriptor,List<deal.semantic.SemanticOracle.Value> args) {
                    if (name.equals("keep")) {
                        saved=(deal.semantic.SemanticOracle.Value.ClassValue)args.getFirst();
                        var grid=(deal.semantic.SemanticOracle.Value.ArrayValue)((deal.semantic.SemanticOracle.Value.ClassFieldState.Present)saved.fields().get(2)).value();
                        row=(deal.semantic.SemanticOracle.Value.ArrayValue)grid.elements().getFirst();
                        home=(deal.semantic.SemanticOracle.Value.ClassValue)((deal.semantic.SemanticOracle.Value.ClassFieldState.Present)saved.fields().get(4)).value();
                    } else {
                        int mode=(int)((deal.semantic.SemanticOracle.Value.NumValue)args.getFirst()).value();
                        saved.fields().set(0,new deal.semantic.SemanticOracle.Value.ClassFieldState.Present(mode==1
                            ? saved : deal.semantic.SemanticOracle.Value.NullValue.INSTANCE));
                        saved.fields().set(1,new deal.semantic.SemanticOracle.Value.ClassFieldState.Present(mode<=2
                            ? new deal.semantic.SemanticOracle.Value.NumValue(42) : new deal.semantic.SemanticOracle.Value.StrValue("kept")));
                        row.elements().set(0,mode==3 ? new deal.semantic.SemanticOracle.Value.BoolValue(false)
                            : new deal.semantic.SemanticOracle.Value.NumValue(1));
                        var sibling=project.modules().get(project.entryModule()).classLayouts().keySet().stream()
                            .filter(id -> id.name().equals("Other")).findFirst().orElseThrow();
                        saved.fields().set(4,new deal.semantic.SemanticOracle.Value.ClassFieldState.Present(mode==5
                            ? new deal.semantic.SemanticOracle.Value.ClassValue(sibling,home.fields()) : home));
                        if (mode==7) {
                            malformed=new deal.semantic.SemanticOracle.Value.TableValue(new LinkedHashMap<>());
                            malformed.entries().put("bad",new deal.semantic.SemanticOracle.Value.StrValue("placeholder",
                                deal.semantic.ir.UnicodeScalars.Defect.MALFORMED));
                            malformed.entries().put("later",malformed);
                            saved.fields().set(0,new deal.semantic.SemanticOracle.Value.ClassFieldState.Present(malformed));
                        } else if (mode==8 || mode==9) {
                            malformed.entries().put("bad",new deal.semantic.SemanticOracle.Value.StrValue("kept"));
                            if (mode==9) malformed.entries().remove("later");
                            saved.fields().set(0,new deal.semantic.SemanticOracle.Value.ClassFieldState.Present(malformed));
                        }
                    }
                    return new SyncOutcome.Returned(deal.semantic.SemanticOracle.Value.UndefinedValue.INSTANCE);
                }
            };
            var result=deal.semantic.SemanticOracle.invokeSyncEntry(project,tables,registries,responder,
                project.entryModule(),"run",List.of());
            if (!(result.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success))
                throw new AssertionError("stringify live Oracle: " + result.comparisonReport());
            var failures=result.trace().stream().filter(e->e.kind()==deal.semantic.ir.SemanticOpKind.STDLIB_CALL
                && e.phase()==deal.semantic.SemanticRuntimeModel.Phase.FAILURE).toList();
            if (failures.size()!=6) throw new AssertionError("stringify Oracle failure population: " + failures);
            var laterCycle=failures.get(5).error();
            if (!laterCycle.code().equals("E8001")
                    || !laterCycle.message().equals("cyclic value cannot be encoded as JSON")
                    || laterCycle.expected()!=null || laterCycle.actual()!=null || laterCycle.cause()!=null)
                throw new AssertionError("stringify Oracle repaired transport exposes later cycle: " + laterCycle);
            var unsupported=failures.get(4).error();
            if (!unsupported.code().equals("E8001")
                    || !unsupported.message().equals("expected string, got invalid Unicode scalar encoding")
                    || !"string".equals(unsupported.expected())
                    || !"invalid-unicode".equals(unsupported.actual()) || unsupported.cause()!=null)
                throw new AssertionError("stringify Oracle malformed transport before cycle: " + unsupported);
            var homeType=project.modules().get(project.entryModule()).classLayouts().keySet().stream()
                .filter(id -> id.name().equals("Place")).findFirst().orElseThrow();
            var siblingType=project.modules().get(project.entryModule()).classLayouts().keySet().stream()
                .filter(id -> id.name().equals("Other")).findFirst().orElseThrow();
            var nominal=failures.get(3).error();
            if (!nominal.code().equals("E8001")
                    || !nominal.message().equals("expected instance of "+homeType.text()+", got "+siblingType.text())
                    || !homeType.text().equals(nominal.expected()) || !siblingType.text().equals(nominal.actual())
                    || nominal.cause()!=null)
                throw new AssertionError("stringify Oracle nominal projection: " + nominal);
            var cycle=failures.get(0).error();
            if (!cycle.code().equals("E8001") || !cycle.message().equals("cyclic value cannot be encoded as JSON")
                    || cycle.expected()!=null || cycle.actual()!=null || cycle.cause()!=null)
                throw new AssertionError("stringify Oracle cycle projection: " + cycle);
            var kind=failures.get(1).error();
            if (!kind.code().equals("E8001") || !kind.message().equals("expected string")
                    || !"string".equals(kind.expected()) || !"number".equals(kind.actual()) || kind.cause()!=null)
                throw new AssertionError("stringify Oracle kind projection: " + kind);
            var nested=failures.get(2).error();
            if (!nested.code().equals("E8003") || !nested.message().equals("array element 1 type mismatch")
                    || !"[number]".equals(nested.expected()) || !"array".equals(nested.actual())
                    || nested.cause()==null || !nested.cause().code().equals("E8001")
                    || !nested.cause().message().equals("expected number")
                    || !"number".equals(nested.cause().expected()) || !"boolean".equals(nested.cause().actual())
                    || nested.cause().cause()!=null)
                throw new AssertionError("stringify Oracle element projection: " + nested);
            for (var event : failures) {
                var operation=project.modules().get(event.op().module()).ops().stream()
                    .filter(op -> op.opId().equals(event.op())).findFirst().orElseThrow();
                String expectedOrigin=operation.origin().sourceId()+":"+operation.origin().span().startLine()
                    +":"+operation.origin().span().startColumn();
                long starts=result.trace().stream().filter(e -> e.op().equals(event.op())
                    && e.phase()==deal.semantic.SemanticRuntimeModel.Phase.START).count();
                long terminals=result.trace().stream().filter(e -> e.op().equals(event.op())
                    && e.phase()!=deal.semantic.SemanticRuntimeModel.Phase.START).count();
                for (var error=event.error(); error!=null; error=error.cause()) {
                    if (!expectedOrigin.equals(error.origin()) || error.frames().isEmpty())
                        throw new AssertionError("stringify Oracle operation origin/frames: " + event);
                }
                if (starts!=1 || terminals!=1)
                    throw new AssertionError("stringify Oracle selected operation event population: " + event);
            }
        } finally { CompositionProductionTest.remove(work); }
    }

    private static void fixtureAdmission(String fixture, deal.semantic.ir.SemanticOpKind kind, long expected) throws Exception {
        Path work = Files.createTempDirectory(Path.of("build"), "has-admission-").toAbsolutePath();
        try {
            Path source = Files.createDirectories(work.resolve("src")).resolve("main.deal");
            Files.writeString(source, ConformanceHarnessMetadata.stripClassificationHeaders(Files.readString(
                Path.of("test/conformance/backend-runtime/" + fixture)))
                .replace("export function main():void", "export function run():void")
                + "\nexport function main():void{return ;}\n");
            Files.writeString(work.resolve("deal.json"),
                "{\"languageVersion\":\"1.6\",\"stdlib\":\"1.6\",\"moduleRoots\":[\"src\"]}");
            var invocation = deal.semantic.CompilerProfileProvider.resolve(
                deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
            var compiler = new deal.module.CompilationOrchestrator(source, work.resolve("out"),
                false, false, false, false, deal.codegen.Backend.JS, Map.of(), List.of(source.getParent()), null, null, invocation);
            if (!compiler.compile()) throw new AssertionError(fixture + " Oracle compilation: " + compiler.diagnostics());
            var project = Objects.requireNonNull(compiler.producedProject());
            if (fixture.contains("conditional")) conditionalAdmission(project, invocation);
            if (fixture.contains("from-live")) arrayFromAdmission(project, invocation);
            if (fixture.equals("arrays/push.deal")) arrayPushAdmission(project, invocation);
            if (fixture.contains("contextual-parse")) jsonParseAdmission(project, invocation);
            if (kind == deal.semantic.ir.SemanticOpKind.ARRAY_HAS) {
                var facts = new deal.semantic.ir.SemanticIrValidator.ComparisonFacts(
                    project.interfaceIndex().interfaceIndexDigest(), project.semanticProfile(), invocation.capabilityRegistryHash());
                String raw = deal.semantic.ir.SemanticIrValidator.toProjectText(project);
                for (String key : List.of("arrayValue", "index"))
                    rejectsOp(raw, "ARRAY_HAS", op -> field(op, "payload", field(payload(op), key,
                        deal.semantic.ir.ContractSnapshotCanonicalizer.semanticIdJson(new deal.semantic.ir.ValueId(Integer.MAX_VALUE)))), facts);
                rejectsOp(raw, "ARRAY_HAS", op -> field(op, "resultType", new deal.semantic.ir.CanonicalJson.Str("number")), facts);
            }
            var tables = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.StructuredBodyTable>();
            var registries = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.ClassFactoryRegistry>();
            project.modules().forEach((id, unit) -> { tables.put(id, unit.bodyTable()); registries.put(id, unit.classFactories()); });
            var result = deal.semantic.SemanticOracle.invokeSyncEntry(project, tables, registries,
                new deal.semantic.SemanticOracle.HostResponder() {}, compiler.checkedProject().input().entryModule(), "run", List.of());
            if (!(result.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success success)
                    || !success.resultAtom().equals("undefined")) throw new AssertionError(result.comparisonReport());
            long tests = result.trace().stream().filter(e -> e.kind()==kind
                && e.phase()==deal.semantic.SemanticRuntimeModel.Phase.SUCCESS).count();
            if (expected >= 0 && tests!=expected) throw new AssertionError(fixture + " Oracle population: " + tests);
            if (fixture.equals("arrays/map.deal") || fixture.equals("arrays/find.deal")) {
                var failures = result.trace().stream().filter(e -> e.kind()==deal.semantic.ir.SemanticOpKind.ARRAY_ELEMENT_GET
                    && e.phase()==deal.semantic.SemanticRuntimeModel.Phase.FAILURE).toList();
                if(failures.size()!=(fixture.equals("arrays/find.deal") ? 2 : 1)) throw new AssertionError("strict-read shrink failure population: "+failures);
                for (var event : failures) {
                    var error=event.error();
                    var op=project.modules().get(event.op().module()).ops().stream().filter(o->o.opId().equals(event.op())).findFirst().orElseThrow();
                    String origin=op.origin().sourceId()+":"+op.origin().span().startLine()+":"+op.origin().span().startColumn();
                    if(!error.code().equals("E8002") || !error.message().equals("array index out of bounds")
                            || error.expected()!=null || error.actual()!=null || error.cause()!=null
                            || !error.origin().equals(origin) || error.frames().isEmpty()) throw new AssertionError(error);
                }
            }
        } finally { CompositionProductionTest.remove(work); }
    }

    private static void jsonOperationOriginsAcrossCalls() throws Exception {
        Path work = Files.createTempDirectory(Path.of("build"), "json-operation-origins-").toAbsolutePath();
        try {
            Path src = Files.createDirectories(work.resolve("src"));
            String failures = """
                export function fail():null {
                  try { JSON.parse("bad"); } catch(e) {}
                  let record:Record<string,unknown>={};
                  record["self"]=record;
                  try { JSON.stringify(record); } catch(e) {}
                  return null;
                }
                """;
            Files.writeString(src.resolve("lib.deal"), failures);
            Path entry = src.resolve("main.deal");
            Files.writeString(entry, "import * as lib from \"./lib\";\n" + failures + """
                function choose(flag:boolean):()=>null { return flag ? fail : lib.fail; }
                export function main():void {
                  fail();
                  let dynamic:()=>null=choose(true);
                  dynamic();
                  let adapted:(ignored:number)=>null=dynamic;
                  adapted(7);
                  lib.fail();
                  return ;
                }
                """);
            var invocation = deal.semantic.CompilerProfileProvider.resolve(
                deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
            var compiler = new deal.module.CompilationOrchestrator(entry, work.resolve("out"), false, false,
                false, false, deal.codegen.Backend.JS, Map.of(), List.of(src), null, null, invocation);
            if (!compiler.compile()) throw new AssertionError("JSON operation origin compile: " + compiler.diagnostics());
            var project = Objects.requireNonNull(compiler.producedProject());
            var tables = new LinkedHashMap<deal.semantic.ir.ModuleId,deal.semantic.ir.StructuredBodyTable>();
            var registries = new LinkedHashMap<deal.semantic.ir.ModuleId,deal.semantic.ir.ClassFactoryRegistry>();
            project.modules().forEach((id,unit)->{tables.put(id,unit.bodyTable());registries.put(id,unit.classFactories());});
            var verdict = SemanticDifferentialHarness.runProject(project,tables,registries,
                SemanticDifferentialHarness.Expectation.success("JSON operation origins across direct/dynamic/adapted/imported calls",
                    List.of(),"undefined"),work.resolve("trace"));
            if (!verdict.pass() || verdict.runs().size()!=3) throw new AssertionError(verdict.report());
            for (var run : verdict.runs()) {
                var selected = run.trace().stream().filter(event -> event.phase()==deal.semantic.SemanticRuntimeModel.Phase.FAILURE
                    && (event.kind()==deal.semantic.ir.SemanticOpKind.JSON_PARSE
                        || event.kind()==deal.semantic.ir.SemanticOpKind.STDLIB_CALL)).toList();
                if (selected.size()!=8) throw new AssertionError(run.consumer()+" JSON call failure population: "+selected);
                for (var event : selected) {
                    var op = project.modules().get(event.op().module()).ops().stream()
                        .filter(candidate -> candidate.opId().equals(event.op())).findFirst().orElseThrow();
                    String origin=op.origin().sourceId()+":"+op.origin().span().startLine()+":"+op.origin().span().startColumn();
                    String message=event.kind()==deal.semantic.ir.SemanticOpKind.JSON_PARSE
                        ? "JSON parse error at position 1: unexpected character" : "cyclic value cannot be encoded as JSON";
                    var error=event.error();
                    if (!error.code().equals("E8001") || !error.message().equals(message)
                            || error.expected()!=null || error.actual()!=null || error.cause()!=null
                            || !origin.equals(error.origin()) || error.frames().isEmpty())
                        throw new AssertionError(run.consumer()+" JSON call operation tuple: "+event);
                    long starts=run.trace().stream().filter(e -> e.op().equals(event.op())
                        && e.phase()==deal.semantic.SemanticRuntimeModel.Phase.START).count();
                    long terminals=run.trace().stream().filter(e -> e.op().equals(event.op())
                        && e.phase()!=deal.semantic.SemanticRuntimeModel.Phase.START).count();
                    long expected=event.op().module().equals(project.entryModule()) ? 3 : 1;
                    if (starts!=expected || terminals!=expected)
                        throw new AssertionError(run.consumer()+" JSON call event population: "+event);
                }
            }
        } finally { CompositionProductionTest.remove(work); }
    }

    private static void jsonParseTraces() throws Exception {
        Path work = Files.createTempDirectory(Path.of("build"), "json-parse-traces-").toAbsolutePath();
        try {
            Path src = Files.createDirectories(work.resolve("src"));
            Path entry = src.resolve("main.deal");
            Files.writeString(entry, """
                class Item { value:number; }
                class Nullable { value:number|null; }
                export function main():void {
                  try { let n:number=JSON.parse(`"bad"`); } catch(e) {}
                  try { JSON.parse(`bad`); } catch(e) {}
                  try { let item:Item=JSON.parse(`{"extra":1}`); } catch(e) {}
                  try { let record:Record<string,unknown>=JSON.parse(`[1,2]`); } catch(e) {}
                  try { let item:Item=JSON.parse(`{"{actual}":1}`); } catch(e) {}
                  try { let item:Item=JSON.parse(`{"x\\\\u0000y":1}`); } catch(e) {}
                  try { let item:Item=JSON.parse(`{"\\\\uD800":1}`); } catch(e) {}
                  try { let item:Item=JSON.parse(`{"a'b":1}`); } catch(e) {}
                  try { let item:Item=JSON.parse(`{"":1}`); } catch(e) {}
                  try { let item:Nullable=JSON.parse(`{}`); } catch(e) {}
                  try { let n:number|null=JSON.parse(`[]`); } catch(e) {}
                  try { let item:Item=JSON.parse(`[]`); } catch(e) {}
                  let n:number=JSON.parse(`2`);
                  if(n!==2) { throw {name:"TEST",message:"parse value"}; }
                  return ;
                }
                """);
            var invocation = deal.semantic.CompilerProfileProvider.resolve(
                deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
            var compiler = new deal.module.CompilationOrchestrator(entry, work.resolve("out"), false, false,
                false, false, deal.codegen.Backend.JS, Map.of(), List.of(src), null, null, invocation);
            if (!compiler.compile()) throw new AssertionError(compiler.diagnostics());
            var project = Objects.requireNonNull(compiler.producedProject());
            var tables = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.StructuredBodyTable>();
            var registries = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.ClassFactoryRegistry>();
            project.modules().forEach((id, unit) -> { tables.put(id, unit.bodyTable()); registries.put(id, unit.classFactories()); });
            var verdict = SemanticDifferentialHarness.runProject(project, tables, registries,
                SemanticDifferentialHarness.Expectation.success("JSON.parse syntax/target/extra failure events", List.of(), "undefined"),
                work.resolve("trace"));
            if (!verdict.pass() || verdict.runs().size() != 3) throw new AssertionError(verdict.report());
            for (var run : verdict.runs()) {
                var failures = run.trace().stream().filter(event -> event.kind()==deal.semantic.ir.SemanticOpKind.JSON_PARSE
                    && event.phase()==deal.semantic.SemanticRuntimeModel.Phase.FAILURE).toList();
                if (failures.size()!=12) throw new AssertionError(run.consumer()+" JSON.parse failure population: "+failures);
                if (!failures.get(0).error().code().equals("E8001")
                        || !failures.get(0).error().message().equals("expected number")
                        || !failures.get(0).error().expected().equals("number")
                        || !failures.get(0).error().actual().equals("string")
                        || !failures.get(1).error().code().equals("E8001")
                        || !failures.get(1).error().message().equals("JSON parse error at position 1: unexpected character")
                        || failures.get(1).error().expected()!=null || failures.get(1).error().actual()!=null
                        || !failures.get(2).error().code().equals("E8007")
                        || !failures.get(2).error().message().equals("extra field 'extra' in class '@src/main.deal/Item'")
                        || failures.get(2).error().expected()!=null || failures.get(2).error().actual()!=null
                        || !failures.get(3).error().code().equals("E8001")
                        || !failures.get(3).error().message().equals("expected table")
                        || !failures.get(3).error().expected().equals("table")
                        || !failures.get(3).error().actual().equals("array"))
                    throw new AssertionError(run.consumer()+" JSON.parse failure projections: "+failures);
                // User key text is opaque template data, including placeholder
                // spellings, embedded NUL and lone UTF-16 units. These are
                // reconstruction extras, never scanner or template defects.
                var keys = List.of("{actual}", "x\u0000y", "\uD800", "a'b", "");
                for (int i=0; i<keys.size(); ++i) {
                    var error = failures.get(4+i).error();
                    String message = "extra field '"+keys.get(i)+"' in class '@src/main.deal/Item'";
                    if (!error.code().equals("E8007") || !error.message().equals(message)
                            || error.expected()!=null || error.actual()!=null)
                        throw new AssertionError(run.consumer()+" opaque extra key "+i+": "+error);
                }
                var kinds = List.of("number", "number", "class");
                var actuals = List.of("undefined", "array", "array");
                for (int i=0; i<kinds.size(); ++i) {
                    var error = failures.get(9+i).error();
                    String message = "expected "+(kinds.get(i).equals("class") ? "class instance" : kinds.get(i));
                    if (!error.code().equals("E8001") || !error.message().equals(message)
                            || !kinds.get(i).equals(error.expected()) || !actuals.get(i).equals(error.actual()))
                        throw new AssertionError(run.consumer()+" contextual kind "+i+": "+error);
                }
                for (var event : failures) {
                    var op = project.modules().get(event.op().module()).ops().stream()
                        .filter(candidate -> candidate.opId().equals(event.op())).findFirst().orElseThrow();
                    var span = op.origin().span();
                    String origin = op.origin().sourceId()+":"+span.startLine()+":"+span.startColumn();
                    if (!origin.equals(event.error().origin()) || event.error().cause()!=null || event.error().frames().isEmpty())
                        throw new AssertionError(run.consumer()+" JSON.parse origin/cause/frames: "+event);
                }
                long successes = run.trace().stream().filter(event -> event.kind()==deal.semantic.ir.SemanticOpKind.JSON_PARSE
                    && event.phase()==deal.semantic.SemanticRuntimeModel.Phase.SUCCESS).count();
                if (successes!=1) throw new AssertionError(run.consumer()+" JSON.parse success population: "+successes);
            }
        } finally { CompositionProductionTest.remove(work); }
    }

    private static void jsonParsePlanIsolation() {
        String identity = "@src/model.deal/Item";
        var plan = new deal.codegen.jvm.JvmJson.Plan(identity,
            new deal.codegen.jvm.JvmJson.Field[] {
                new deal.codegen.jvm.JvmJson.Field("value", "number", false, "number", "number")
            }, () -> new deal.codegen.jvm.JvmRuntime.ClassInstance() {
                private Object value;
                private boolean present;
                public String classIdText() { return identity; }
                public List<String> fieldNames() { return List.of("value"); }
                public boolean isPresent(String field) { return present && field.equals("value"); }
                public Object read(String field) { return value; }
                public void write(String field, Object next) { value=next; present=true; }
                public void delete(String field) { value=null; present=false; }
            });
        // Another generated project's static initialization must not replace
        // this project's schema or constructor through the legacy helper map.
        new deal.codegen.jvm.JvmJson.Plan(identity,
            new deal.codegen.jvm.JvmJson.Field[] {
                new deal.codegen.jvm.JvmJson.Field("run", "()->number", true, "ref", "()->number")
            }, () -> { throw new AssertionError("foreign class constructor"); });
        var origin = new deal.semantic.ir.SourceOrigin("parse", new deal.semantic.ir.SourceSpan(
            "main.deal", 1, 1, 1, 2, 0, 1), deal.semantic.ir.SourceOriginKind.USER,
            new deal.semantic.ir.AnchorId(1), null);
        var decoded = (deal.codegen.jvm.JvmRuntime.ClassInstance) deal.codegen.jvm.JvmJson.decode(
            "{\"value\":2}", identity, origin, "parse:1:1", Map.of(identity, plan));
        if (!decoded.classIdText().equals(identity) || !decoded.read("value").equals(2.0))
            throw new AssertionError("JSON.parse borrowed a foreign project plan");
        try {
            deal.codegen.jvm.JvmJson.decode("{}", identity, origin, "parse:1:1", Map.of());
            throw new AssertionError("JSON.parse resolved an unavailable project plan");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("no exact class layout")) throw expected;
        }
    }

    private static void jsonParseGeneratedArtifactIsolation() throws Exception {
        Path work = Files.createTempDirectory(Path.of("build"), "json-artifact-isolation-").toAbsolutePath();
        try {
            var invocation = deal.semantic.CompilerProfileProvider.resolve(
                deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
            var products = new ArrayList<Path>();
            for (boolean numeric : List.of(true, false)) {
                Path project = Files.createDirectories(work.resolve(numeric ? "numeric" : "text"));
                Path src = Files.createDirectories(project.resolve("src"));
                Path source = src.resolve("main.deal");
                String type = numeric ? "number" : "string";
                String literal = numeric ? "7" : "\"foreign\"";
                Files.writeString(source, "class Item{value:" + type + ";}export function main():void{"
                    + "let item:Item=JSON.parse(`{\"value\":" + literal + "}`);"
                    + "if(item.value!==" + literal + "){throw {name:\"TEST\",message:\"foreign schema\"};}"
                    + "return ;}");
                Path out = project.resolve("out");
                var compiler = new deal.module.CompilationOrchestrator(source, out,
                    false, false, false, false, deal.codegen.Backend.JVM, Map.of(), List.of(src), null, null, invocation);
                if (!compiler.compile()) throw new AssertionError("artifact isolation: " + compiler.diagnostics());
                Path classes = Files.createDirectories(project.resolve("classes"));
                var command = new ArrayList<>(List.of("javac", "--release", "25", "-proc:none", "-cp",
                    TestClasspath.absolute(TestClasspath.EmptyEntry.OMIT), "-d", classes.toString()));
                try (var files = Files.walk(out)) {
                    files.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> command.add(p.toString()));
                }
                var built = deal.test.conformance.JvmCompilation.compile(command, project);
                if (built.exitCode()!=0) throw new AssertionError("artifact isolation compilation: " + built.stderr());
                products.add(classes);
            }
            // Both artifacts use @src/main.deal/Item and share the same runtime
            // classes. Initialize both before invoking either, then interleave
            // real generated entries rather than constructing stand-in plans.
            try (var numeric = new java.net.URLClassLoader(new java.net.URL[]{products.get(0).toUri().toURL()},
                     Language16RuntimeTest.class.getClassLoader());
                 var text = new java.net.URLClassLoader(new java.net.URL[]{products.get(1).toUri().toURL()},
                     Language16RuntimeTest.class.getClassLoader())) {
                var first = Class.forName("Main", true, numeric);
                var second = Class.forName("Main", true, text);
                if (first==second || first.getClassLoader()!=numeric || second.getClassLoader()!=text)
                    throw new AssertionError("generated artifacts were not independently loaded");
                for (var artifact : List.of(first, second, first, second)) {
                    try { artifact.getMethod("dealMain").invoke(null); }
                    catch (java.lang.reflect.InvocationTargetException failure) {
                        throw new AssertionError("generated artifact borrowed a foreign schema", failure.getCause());
                    }
                }
            }
        } finally { CompositionProductionTest.remove(work); }
    }

    private static void jsonParsePrivateProvider() throws Exception {
        Path work = Files.createTempDirectory(Path.of("build"), "json-private-provider-").toAbsolutePath();
        try {
            Path src = Files.createDirectories(work.resolve("src"));
            Path source = src.resolve("main.deal");
            Files.writeString(source, "import * as unrelated from \"./other\";import * as model from \"./model\";class Local{box:model.Box;}"
                + "export function run():number{let marker:unrelated.Marker={};let box:model.Box=JSON.parse(`{\"item\":{\"value\":7}}`);"
                + "let local:Local=JSON.parse(`{\"box\":{\"item\":{\"value\":8}}}`);"
                + "if(model.read(local.box)!==8){throw {name:\"TEST\",message:\"local-to-private schema\"};}return model.read(box);}"
                + "export function main():void{return ;}");
            Files.writeString(src.resolve("other.deal"), "export class Marker{}");
            Files.writeString(src.resolve("model.deal"), "class Item{value:number;next?:Item;}export class Box{item:Item;}"
                + "export function read(box:Box):number{return box.item.value;}");
            var invocation = deal.semantic.CompilerProfileProvider.resolve(
                deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
            var compiler = new deal.module.CompilationOrchestrator(source, work.resolve("out"),
                false, false, false, false, deal.codegen.Backend.JS, Map.of(), List.of(src), null, null, invocation);
            if (!compiler.compile()) throw new AssertionError("private schema: " + compiler.diagnostics());
            var project = Objects.requireNonNull(compiler.producedProject());
            var facts = new deal.semantic.ir.SemanticIrValidator.ComparisonFacts(
                project.interfaceIndex().interfaceIndexDigest(), project.semanticProfile(), invocation.capabilityRegistryHash());
            var typed = deal.semantic.ir.SemanticIrValidator.validate(project, facts);
            var text = deal.semantic.ir.SemanticIrValidator.validateText(
                deal.semantic.ir.SemanticIrValidator.toProjectText(project), facts);
            if (typed.isPresent() || !typed.equals(text))
                throw new AssertionError("private schema project transport: " + typed + " / " + text);
            var tables = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.StructuredBodyTable>();
            var registries = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.ClassFactoryRegistry>();
            project.modules().forEach((id, unit) -> { tables.put(id, unit.bodyTable()); registries.put(id, unit.classFactories()); });
            var result = deal.semantic.SemanticOracle.invokeSyncEntry(project, tables, registries,
                new deal.semantic.SemanticOracle.HostResponder() {}, project.entryModule(), "run", List.of());
            if (!(result.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success success)
                    || !success.resultAtom().equals("num:0x1.cp2")) throw new AssertionError(result.comparisonReport());
            var consumer = project.modules().get(project.entryModule());
            var standalone = deal.semantic.ir.SemanticIrValidator.validate(consumer, facts);
            var standaloneText = deal.semantic.ir.SemanticIrValidator.validateText(
                deal.semantic.ir.SemanticIrValidator.toUnitText(consumer), facts);
            if (standalone.isEmpty() || !standalone.equals(standaloneText))
                throw new AssertionError("standalone private schema link: " + standalone + " / " + standaloneText);
            // A consumer cannot manufacture construction authority by copying
            // dependency layouts into its transported local declaration map.
            var copiedLayouts = new LinkedHashMap<>(consumer.classLayouts());
            project.modules().values().forEach(unit -> copiedLayouts.putAll(unit.classLayouts()));
            var fabricated = new deal.semantic.ir.LoweredModuleUnit(consumer.formatVersion(), consumer.semanticProfile(),
                consumer.moduleId(), consumer.interfaceHash(), consumer.loweringContextHash(), consumer.requiredCapabilities(),
                consumer.constructCoverage(), copiedLayouts, consumer.functions(), consumer.moduleInit(), consumer.exportPlan(),
                consumer.functionBindings(), consumer.ops(), consumer.bodyTable(), consumer.classFactories(), consumer.classBindingFacts());
            var fabricatedStandalone = deal.semantic.ir.SemanticIrValidator.validate(fabricated, facts);
            var fabricatedText = deal.semantic.ir.SemanticIrValidator.validateText(
                deal.semantic.ir.SemanticIrValidator.toUnitText(fabricated), facts);
            if (fabricatedStandalone.isEmpty() || !fabricatedStandalone.equals(fabricatedText))
                throw new AssertionError("fabricated local schema authority: " + fabricatedStandalone + " / " + fabricatedText);
            var changedModules = new LinkedHashMap<>(project.modules());
            changedModules.put(project.entryModule(), fabricated);
            var forgedProject = new deal.semantic.ir.ExecutableLoweredProject(project.semanticProfile(), project.interfaceIndex(),
                changedModules, project.entryModule(), project.classSeeds());
            var forgedTyped = deal.semantic.ir.SemanticIrValidator.validate(forgedProject, facts);
            var forgedText = deal.semantic.ir.SemanticIrValidator.validateText(
                deal.semantic.ir.SemanticIrValidator.toProjectText(forgedProject), facts);
            if (forgedTyped.isEmpty() || !forgedTyped.equals(forgedText))
                throw new AssertionError("fabricated project schema authority: " + forgedTyped + " / " + forgedText);
            var provider = project.modules().values().stream()
                .filter(unit -> unit.classLayouts().keySet().stream().anyMatch(id -> id.name().equals("Item")))
                .findFirst().orElseThrow();
            var unrelated = project.modules().values().stream()
                .filter(unit -> unit.classLayouts().keySet().stream().anyMatch(id -> id.name().equals("Marker")))
                .findFirst().orElseThrow();
            var privateId = provider.classLayouts().keySet().stream()
                .filter(id -> id.name().equals("Item")).findFirst().orElseThrow();
            var privateLayout = provider.classLayouts().get(privateId);
            for (String defect : List.of("missing", "sibling", "external", "forbidden", "relocated", "duplicate", "shadowed", "unrelated")) {
                boolean collision = defect.equals("duplicate") || defect.equals("shadowed");
                var layouts = new LinkedHashMap<>(provider.classLayouts());
                layouts.remove(privateId);
                if (!defect.equals("missing") && !defect.equals("relocated") && !defect.equals("unrelated")) {
                    var id = defect.equals("sibling") ? new deal.semantic.ir.ClassId("src/other.deal", "Item")
                        : defect.equals("external") ? new deal.semantic.ir.ClassId("$external/host/model", "Item") : privateId;
                    var fields = new ArrayList<>(privateLayout.fields());
                    if (defect.equals("forbidden")) fields.add(new deal.semantic.ir.ClassLayout.FieldLayout("run",
                        deal.semantic.ir.RuntimeDescriptor.parseCanonicalText("()->number"), false,
                        deal.semantic.ir.ConstructionProvider.LOCAL));
                    layouts.put(id, new deal.semantic.ir.ClassLayout(id, fields));
                }
                var changedProvider = new deal.semantic.ir.LoweredModuleUnit(provider.formatVersion(), provider.semanticProfile(),
                    provider.moduleId(), provider.interfaceHash(), provider.loweringContextHash(), provider.requiredCapabilities(),
                    provider.constructCoverage(), layouts, provider.functions(), provider.moduleInit(), provider.exportPlan(),
                    provider.functionBindings(), provider.ops(), provider.bodyTable(), provider.classFactories(), provider.classBindingFacts());
                var modules = new LinkedHashMap<>(project.modules());
                modules.put(provider.moduleId(), changedProvider);
                if (defect.equals("relocated") || collision) {
                    var movedLayouts = new LinkedHashMap<>(consumer.classLayouts());
                    movedLayouts.put(privateId, defect.equals("shadowed")
                        ? new deal.semantic.ir.ClassLayout(privateId, List.of(new deal.semantic.ir.ClassLayout.FieldLayout(
                            "value", deal.semantic.ir.RuntimeDescriptor.String.INSTANCE, true, deal.semantic.ir.ConstructionProvider.LOCAL)))
                        : privateLayout);
                    modules.put(consumer.moduleId(), new deal.semantic.ir.LoweredModuleUnit(
                        consumer.formatVersion(), consumer.semanticProfile(), consumer.moduleId(), consumer.interfaceHash(),
                        consumer.loweringContextHash(), consumer.requiredCapabilities(), consumer.constructCoverage(), movedLayouts,
                        consumer.functions(), consumer.moduleInit(), consumer.exportPlan(), consumer.functionBindings(),
                        consumer.ops(), consumer.bodyTable(), consumer.classFactories(), consumer.classBindingFacts()));
                }
                if (defect.equals("unrelated")) {
                    var movedLayouts = new LinkedHashMap<>(unrelated.classLayouts());
                    movedLayouts.put(privateId, privateLayout);
                    modules.put(unrelated.moduleId(), new deal.semantic.ir.LoweredModuleUnit(
                        unrelated.formatVersion(), unrelated.semanticProfile(), unrelated.moduleId(), unrelated.interfaceHash(),
                        unrelated.loweringContextHash(), unrelated.requiredCapabilities(), unrelated.constructCoverage(), movedLayouts,
                        unrelated.functions(), unrelated.moduleInit(), unrelated.exportPlan(), unrelated.functionBindings(),
                        unrelated.ops(), unrelated.bodyTable(), unrelated.classFactories(), unrelated.classBindingFacts()));
                }
                var changed = new deal.semantic.ir.ExecutableLoweredProject(project.semanticProfile(), project.interfaceIndex(),
                    modules, project.entryModule(), project.classSeeds());
                var rawModules = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.LoweredModuleUnit>();
                modules.forEach((id, unit) -> rawModules.put(id, deal.semantic.ir.RawUnitMaterializer.materialize(
                    deal.semantic.ir.RawUnit.fromTyped(unit))));
                var rawProject = new deal.semantic.ir.ExecutableLoweredProject(project.semanticProfile(), project.interfaceIndex(),
                    rawModules, project.entryModule(), project.classSeeds());
                var rejected = deal.semantic.ir.SemanticIrValidator.validate(changed, facts);
                var rejectedRaw = deal.semantic.ir.SemanticIrValidator.validate(rawProject, facts);
                var rejectedText = deal.semantic.ir.SemanticIrValidator.validateText(
                    deal.semantic.ir.SemanticIrValidator.toProjectText(changed), facts);
                if (rejected.isEmpty() || !rejected.equals(rejectedRaw) || !rejected.equals(rejectedText)
                        || !rejected.orElseThrow().message().contains("JSON_PARSE"))
                    throw new AssertionError("private provider " + defect + " (" + provider.moduleId() + " owns " + privateId
                        + "): " + rejected + " / " + rejectedRaw + " / " + rejectedText);
                var firstParse = consumer.ops().stream().filter(op -> op.kind() == deal.semantic.ir.SemanticOpKind.JSON_PARSE)
                    .findFirst().orElseThrow();
                if (!rejected.orElseThrow().range().equals(firstParse.origin().span().range()))
                    throw new AssertionError("private provider failure lost first parse origin: " + rejected);
                if (collision && !rejected.orElseThrow().message().contains(
                        "JSON_PARSE target has ambiguous declaring providers: " + privateId))
                    throw new AssertionError("duplicate private schema chose an owner: " + rejected);
                if (defect.equals("relocated") && !rejected.orElseThrow().message().contains(
                        "JSON_PARSE dependency schema refers to a consumer-local layout: " + privateId))
                    throw new AssertionError("relocated private provider used an unrelated rejection: " + rejected);
                if (defect.equals("unrelated") && !rejected.orElseThrow().message().contains(
                        "JSON_PARSE schema refers to a layout outside its defining provider's dependencies: " + privateId))
                    throw new AssertionError("unrelated private provider used an unrelated rejection: " + rejected);
            }
            // Reachability follows the private declaration, even when its
            // function field is optional and the JSON never mentions it.
            Files.writeString(src.resolve("model.deal"), "class Item{value:number;run?:()=>number;}"
                + "export class Box{item:Item;}export function read(box:Box):number{return box.item.value;}");
            var forbidden = new deal.module.CompilationOrchestrator(source, work.resolve("forbidden-out"),
                false, false, false, false, deal.codegen.Backend.JS, Map.of(), List.of(src), null, null, invocation);
            if (forbidden.compile() || forbidden.diagnostics().stream().noneMatch(diagnostic ->
                    diagnostic.message().equals("JSON.parse target must not contain function or void types")))
                throw new AssertionError("unread private optional Func schema was not rejected: " + forbidden.diagnostics());
        } finally { CompositionProductionTest.remove(work); }
    }

    private static void jsonParseDeclarationProvider() throws Exception {
        Path work = Files.createTempDirectory(Path.of("build"), "json-declaration-provider-").toAbsolutePath();
        try {
            Path src = Files.createDirectories(work.resolve("src"));
            Path source = src.resolve("main.deal");
            Path host = work.resolve("probe.d.deal");
            Files.writeString(host, "class Inner{value:number;next?:Inner;}export class Leaf{value:number;child?:Inner;}");
            Files.writeString(source, "import * as host from \"host/probe\";import * as model from \"./model\";"
                + "export function main():void{let box:model.Box=JSON.parse(`{\"item\":{\"value\":7}}`);"
                + "let leaf:host.Leaf=JSON.parse(`{\"value\":8}`);return ;}");
            Files.writeString(src.resolve("model.deal"), "class Item{value:number;}export class Box{item:Item;}");
            var invocation = deal.semantic.CompilerProfileProvider.resolve(
                deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
            var compiler = new deal.module.CompilationOrchestrator(source, work.resolve("out"),
                false, false, false, false, deal.codegen.Backend.JS, Map.of("host/probe", host.toString()),
                List.of(src), null, null, invocation);
            if (!compiler.compile()) throw new AssertionError("declaration schema: " + compiler.diagnostics());
            var project = Objects.requireNonNull(compiler.producedProject());
            var facts = new deal.semantic.ir.SemanticIrValidator.ComparisonFacts(
                project.interfaceIndex().interfaceIndexDigest(), project.semanticProfile(), invocation.capabilityRegistryHash());
            if (deal.semantic.ir.SemanticIrValidator.validate(project, facts).isPresent()
                    || deal.semantic.ir.SemanticIrValidator.validateText(
                        deal.semantic.ir.SemanticIrValidator.toProjectText(project), facts).isPresent())
                throw new AssertionError("valid declaration schema did not survive transport");
            var leafId = project.classSeeds().registrations().keySet().stream()
                .filter(id -> id.name().equals("Leaf")).findFirst().orElseThrow();
            var provider = project.modules().values().stream().filter(unit ->
                unit.classLayouts().keySet().stream().anyMatch(id -> id.name().equals("Item"))).findFirst().orElseThrow();
            var layouts = new LinkedHashMap<>(provider.classLayouts());
            var itemId = layouts.keySet().stream().filter(id -> id.name().equals("Item")).findFirst().orElseThrow();
            var fields = new ArrayList<>(layouts.get(itemId).fields());
            fields.add(new deal.semantic.ir.ClassLayout.FieldLayout("foreign", new deal.semantic.ir.RuntimeDescriptor.Class(leafId),
                false, deal.semantic.ir.ConstructionProvider.LOCAL));
            layouts.put(itemId, new deal.semantic.ir.ClassLayout(itemId, fields));
            var modules = new LinkedHashMap<>(project.modules());
            modules.put(provider.moduleId(), new deal.semantic.ir.LoweredModuleUnit(provider.formatVersion(), provider.semanticProfile(),
                provider.moduleId(), provider.interfaceHash(), provider.loweringContextHash(), provider.requiredCapabilities(),
                provider.constructCoverage(), layouts, provider.functions(), provider.moduleInit(), provider.exportPlan(),
                provider.functionBindings(), provider.ops(), provider.bodyTable(), provider.classFactories(), provider.classBindingFacts()));
            var changed = new deal.semantic.ir.ExecutableLoweredProject(project.semanticProfile(), project.interfaceIndex(),
                modules, project.entryModule(), project.classSeeds());
            var rawModules = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.LoweredModuleUnit>();
            modules.forEach((id, unit) -> rawModules.put(id, deal.semantic.ir.RawUnitMaterializer.materialize(
                deal.semantic.ir.RawUnit.fromTyped(unit))));
            var rawProject = new deal.semantic.ir.ExecutableLoweredProject(project.semanticProfile(), project.interfaceIndex(),
                rawModules, project.entryModule(), project.classSeeds());
            var typed = deal.semantic.ir.SemanticIrValidator.validate(changed, facts);
            var raw = deal.semantic.ir.SemanticIrValidator.validate(rawProject, facts);
            var text = deal.semantic.ir.SemanticIrValidator.validateText(
                deal.semantic.ir.SemanticIrValidator.toProjectText(changed), facts);
            var firstParse = modules.get(project.entryModule()).ops().stream()
                .filter(op -> op.kind() == deal.semantic.ir.SemanticOpKind.JSON_PARSE).findFirst().orElseThrow();
            if (typed.isEmpty() || !typed.equals(raw) || !typed.equals(text)
                    || !typed.orElseThrow().message().contains(
                        "JSON_PARSE schema refers to a layout outside its defining provider's dependencies: " + leafId)
                    || !typed.orElseThrow().range().equals(firstParse.origin().span().range()))
                throw new AssertionError("unrelated declaration schema: " + typed + " / " + raw + " / " + text);
            // The same registered host schema is valid when the defining
            // implementation actually imports it. Optional omission must
            // still traverse its schema without inventing a value.
            Files.writeString(src.resolve("model.deal"), "import * as host from \"host/probe\";"
                + "class Item{value:number;foreign?:host.Leaf;}export class Box{item:Item;}");
            var valid = new deal.module.CompilationOrchestrator(source, work.resolve("linked-out"),
                false, false, false, false, deal.codegen.Backend.JS, Map.of("host/probe", host.toString()),
                List.of(src), null, null, invocation);
            if (!valid.compile()) throw new AssertionError("reachable declaration schema: " + valid.diagnostics());
            var linked = Objects.requireNonNull(valid.producedProject());
            var linkedFacts = new deal.semantic.ir.SemanticIrValidator.ComparisonFacts(
                linked.interfaceIndex().interfaceIndexDigest(), linked.semanticProfile(), invocation.capabilityRegistryHash());
            if (deal.semantic.ir.SemanticIrValidator.validate(linked, linkedFacts).isPresent()
                    || deal.semantic.ir.SemanticIrValidator.validateText(
                        deal.semantic.ir.SemanticIrValidator.toProjectText(linked), linkedFacts).isPresent())
                throw new AssertionError("reachable declaration schema transport");
            // Fresh declaration wrappers must terminate by nominal/provider
            // identity, yet omitted optional recursive fields still constrain
            // the complete target schema.
            Files.writeString(host, "class Inner{value:number;next?:Inner;run?:()=>number;}"
                + "export class Leaf{value:number;child?:Inner;}");
            var forbidden = new deal.module.CompilationOrchestrator(source, work.resolve("forbidden-out"),
                false, false, false, false, deal.codegen.Backend.JS, Map.of("host/probe", host.toString()),
                List.of(src), null, null, invocation);
            if (forbidden.compile() || forbidden.diagnostics().stream().noneMatch(d ->
                    d.message().contains("JSON.parse target must not contain function or void types")))
                throw new AssertionError("private recursive declaration function schema: " + forbidden.diagnostics());
        } finally { CompositionProductionTest.remove(work); }
    }

    private static void jsonParseAdmission(deal.semantic.ir.ExecutableLoweredProject project,
            deal.semantic.CompilerInvocation invocation) {
        var facts = new deal.semantic.ir.SemanticIrValidator.ComparisonFacts(
            project.interfaceIndex().interfaceIndexDigest(), project.semanticProfile(), invocation.capabilityRegistryHash());
        var admitted = deal.semantic.ir.SemanticIrValidator.validateText(
            deal.semantic.ir.SemanticIrValidator.toProjectText(project), facts);
        if (admitted.isPresent()) throw new AssertionError("JSON.parse project transport: " + admitted);
        int mutations = 0;
        int parses = 0;
        var defects = List.of("input", "forward", "target", "result", "operand", "missing-operand",
            "extra-operand", "type", "policy", "schema-root", "schema", "layout");
        for (var unit : project.modules().values()) {
            var rebuilt = deal.semantic.ir.RawUnitMaterializer.materialize(deal.semantic.ir.RawUnit.fromTyped(unit));
            if (!deal.semantic.ir.SemanticIrValidator.toUnitText(unit).equals(deal.semantic.ir.SemanticIrValidator.toUnitText(rebuilt)))
                throw new AssertionError("JSON.parse raw round trip");
            for (var op : unit.ops()) {
                if (!(op.payload() instanceof deal.semantic.ir.KindPayload.JsonParsePayload parse)) continue;
                parses++;
                for (String defect : defects) {
                    var target = defect.equals("target") ? deal.semantic.ir.RuntimeDescriptor.Boolean.INSTANCE
                        : defect.equals("schema-root") ? deal.semantic.ir.RuntimeDescriptor.parseCanonicalText("()->number")
                        : defect.equals("schema") ? deal.semantic.ir.RuntimeDescriptor.parseCanonicalText("[()->number]")
                        : defect.equals("layout") ? new deal.semantic.ir.RuntimeDescriptor.Class(new deal.semantic.ir.ClassId("src/absent.deal", "Absent"))
                        : parse.target();
                    // Use a different target even when this particular parse already returns boolean.
                    if (defect.equals("target") && target.equals(parse.target())) target = deal.semantic.ir.RuntimeDescriptor.String.INSTANCE;
                    var input = defect.equals("input") ? new deal.semantic.ir.ValueId(Integer.MAX_VALUE)
                        : defect.equals("forward") ? (deal.semantic.ir.ValueId) op.result() : parse.jsonString();
                    var payload = new deal.semantic.ir.KindPayload.JsonParsePayload(input, target);
                    var type = defect.equals("result") ? deal.semantic.ir.RuntimeDescriptor.Void.INSTANCE
                        : List.of("schema-root", "schema", "layout").contains(defect) ? target : op.resultType();
                    var operands = defect.equals("operand") ? List.of((deal.semantic.ir.ValueId) op.result())
                        : defect.equals("missing-operand") ? List.<deal.semantic.ir.ValueId>of()
                        : defect.equals("extra-operand") ? List.of(input, input) : List.of(input);
                    var types = defect.equals("type") ? List.<deal.semantic.ir.RuntimeDescriptor>of(deal.semantic.ir.RuntimeDescriptor.Number.INSTANCE)
                        : defect.equals("missing-operand") ? List.<deal.semantic.ir.RuntimeDescriptor>of()
                        : defect.equals("extra-operand") ? List.of(op.operandTypes().getFirst(), op.operandTypes().getFirst())
                        : op.operandTypes();
                    var policy = defect.equals("policy") ? deal.semantic.ir.FailurePolicyId.NO_DEAL_FAILURE : op.failurePolicy();
                    var old = op.contract();
                    var provisional = new deal.semantic.ir.OperationContractSnapshot(old.version(), old.opKind(), type, types,
                        old.selector(), payload, policy, old.referencedSemanticIds(), "pending");
                    var contract = new deal.semantic.ir.OperationContractSnapshot(old.version(), old.opKind(), type, types,
                        old.selector(), payload, policy, old.referencedSemanticIds(), deal.semantic.ir.ContractSnapshotCanonicalizer.digest(provisional));
                    var changed = new deal.semantic.ir.SemanticOp(op.opId(), op.kind(), op.origin(), op.result(), type,
                        operands, types, payload, policy, contract);
                    var ops = unit.ops().stream().map(cell -> cell == op ? changed : cell).toList();
                    var malformed = new deal.semantic.ir.LoweredModuleUnit(unit.formatVersion(), unit.semanticProfile(), unit.moduleId(),
                        unit.interfaceHash(), unit.loweringContextHash(), unit.requiredCapabilities(), unit.constructCoverage(), unit.classLayouts(),
                        unit.functions(), unit.moduleInit(), unit.exportPlan(), unit.functionBindings(), ops, unit.bodyTable(), unit.classFactories(), unit.classBindingFacts());
                    var typed = deal.semantic.ir.SemanticIrValidator.validate(malformed, facts);
                    var raw = deal.semantic.ir.SemanticIrValidator.validate(deal.semantic.ir.RawUnitMaterializer.materialize(
                        deal.semantic.ir.RawUnit.fromTyped(malformed)), facts);
                    var text = deal.semantic.ir.SemanticIrValidator.validateText(deal.semantic.ir.SemanticIrValidator.toUnitText(malformed), facts);
                    if (typed.isEmpty() || !typed.equals(raw) || !typed.equals(text))
                        throw new AssertionError("JSON.parse " + defect + ": " + typed + " / " + raw + " / " + text);
                    // Result corruption in a conditional arm first violates the
                    // enclosing conditional's join, which is recorded before
                    // its arm operations. Pin that owner rather than accepting
                    // any rejection from the three admission surfaces.
                    var firstSubject = op;
                    if (List.of("result", "schema-root", "schema", "layout").contains(defect)) {
                        for (var candidate : unit.ops()) {
                            if (candidate.payload() instanceof deal.semantic.ir.KindPayload.BranchPayload branch
                                    && branch.selector() == deal.semantic.ir.ControlSelector.CONDITIONAL
                                    && (Objects.equals(branch.selectedValue(), op.result())
                                        || Objects.equals(branch.alternateValue(), op.result()))) {
                                firstSubject = candidate;
                                break;
                            }
                        }
                    }
                    String detail = firstSubject != op
                        ? "CONDITIONAL requires a preceding boolean condition, two owned value-producing branches and a compatible result"
                        : defect.equals("schema-root")
                        ? "function-typed result ValueId " + ((deal.semantic.ir.ValueId) op.result()).id()
                            + " without exactly one FunctionExecutionBinding"
                        : defect.equals("policy")
                        ? "policy NO_DEAL_FAILURE not allowed for JSON_PARSE (expected JSON_RECONSTRUCTION)"
                        : defect.equals("schema") ? "JSON_PARSE JSON decoding target contains a function type"
                        : defect.equals("layout") ? "JSON_PARSE JSON decoding target has no exact class layout: @src/absent.deal/Absent"
                        : "JSON_PARSE requires one preceding string operand and its explicit target as result descriptor";
                    String rule = firstSubject != op ? "R-ENUM"
                        : defect.equals("schema-root") ? "R-FUNCTION-BINDING"
                        : defect.equals("policy") ? "R-POLICY-KIND"
                        : List.of("schema", "layout").contains(defect) ? "JSON_LAYOUT_COHERENCE" : "R-ENUM";
                    var diagnostic = typed.orElseThrow();
                    if (!diagnostic.code().equals("E6005")
                            || !diagnostic.message().contains("validatorRule " + rule + ",")
                            || !diagnostic.message().contains(detail)
                            || !diagnostic.range().equals(firstSubject.origin().span().range()))
                        throw new AssertionError("JSON.parse " + defect + " first failure/origin: " + diagnostic);
                    mutations++;
                }
            }
        }
        if (parses == 0 || mutations != parses * defects.size())
            throw new AssertionError("JSON.parse mutation population: " + mutations + " for " + parses + " parses");
    }

    private static void arrayPushAdmission(deal.semantic.ir.ExecutableLoweredProject project,
            deal.semantic.CompilerInvocation invocation) {
        var facts = new deal.semantic.ir.SemanticIrValidator.ComparisonFacts(
            project.interfaceIndex().interfaceIndexDigest(), project.semanticProfile(), invocation.capabilityRegistryHash());
        if (deal.semantic.ir.SemanticIrValidator.validateText(
                deal.semantic.ir.SemanticIrValidator.toProjectText(project), facts).isPresent())
            throw new AssertionError("push project transport");
        int mutations = 0;
        for (var unit : project.modules().values()) {
            var rebuilt = deal.semantic.ir.RawUnitMaterializer.materialize(deal.semantic.ir.RawUnit.fromTyped(unit));
            if (!deal.semantic.ir.SemanticIrValidator.toUnitText(unit).equals(deal.semantic.ir.SemanticIrValidator.toUnitText(rebuilt)))
                throw new AssertionError("push raw round trip");
            for (int arity : List.of(7, 8)) {
                var chain = unit.ops().stream().filter(op -> op.payload() instanceof deal.semantic.ir.KindPayload.AssignPayload p
                    && p.targetKind() == deal.semantic.ir.AssignTargetKind.ARRAY_SLOT && p.childOps().size() == arity)
                    .findFirst().orElseThrow();
                var original = (deal.semantic.ir.KindPayload.AssignPayload) chain.payload();
                for (String defect : List.of("length", "order", "commit")) {
                    var children = new ArrayList<>(original.childOps());
                    if (defect.equals("length")) children.remove(arity - 4);
                    if (defect.equals("order")) java.util.Collections.swap(children, arity - 3, arity - 2);
                    if (defect.equals("commit")) children.set(arity - 1, children.getFirst());
                    var payload = new deal.semantic.ir.KindPayload.AssignPayload(original.targetKind(), children);
                    var old = chain.contract();
                    var provisional = new deal.semantic.ir.OperationContractSnapshot(old.version(), old.opKind(), chain.resultType(),
                        chain.operandTypes(), old.selector(), payload, chain.failurePolicy(), old.referencedSemanticIds(), "pending");
                    var contract = new deal.semantic.ir.OperationContractSnapshot(old.version(), old.opKind(), chain.resultType(),
                        chain.operandTypes(), old.selector(), payload, chain.failurePolicy(), old.referencedSemanticIds(),
                        deal.semantic.ir.ContractSnapshotCanonicalizer.digest(provisional));
                    var changed = new deal.semantic.ir.SemanticOp(chain.opId(), chain.kind(), chain.origin(), chain.result(),
                        chain.resultType(), chain.operands(), chain.operandTypes(), payload, chain.failurePolicy(), contract);
                    var ops = unit.ops().stream().map(op -> op == chain ? changed : op).toList();
                    var malformed = new deal.semantic.ir.LoweredModuleUnit(unit.formatVersion(),unit.semanticProfile(),unit.moduleId(),
                        unit.interfaceHash(),unit.loweringContextHash(),unit.requiredCapabilities(),unit.constructCoverage(),unit.classLayouts(),
                        unit.functions(),unit.moduleInit(),unit.exportPlan(),unit.functionBindings(),ops,unit.bodyTable(),unit.classFactories(),unit.classBindingFacts());
                    var typed = deal.semantic.ir.AddressChainProtocol.validate(malformed);
                    var raw = deal.semantic.ir.AddressChainProtocol.validate(deal.semantic.ir.RawUnitMaterializer.materialize(
                        deal.semantic.ir.RawUnit.fromTyped(malformed)));
                    var transported = deal.semantic.ir.ContractSnapshotCanonicalizer.parseUnit((deal.semantic.ir.CanonicalJson.Obj)
                        deal.semantic.ir.ContractSnapshotCanonicalizer.parseValidationText(
                            deal.semantic.ir.SemanticIrValidator.toUnitText(malformed)));
                    var text = deal.semantic.ir.AddressChainProtocol.validate(deal.semantic.ir.RawUnitMaterializer.materialize(transported));
                    String rule = defect.equals("commit") ? "SINGLE_EVALUATION" : "ADDRESS_CHAIN_SHAPE";
                    String detail = defect.equals("commit") ? "listed more than once in chain"
                        : defect.equals("order") ? "must be INDEX_NORMALIZE, got BOUNDARY"
                        : arity == 7 ? "no closed ASSIGN ARRAY_SLOT chain shape with 6 children"
                        : "the length child at position 3 must be ARRAY_LENGTH, got FUNCTION_ADAPT";
                    var subject = defect.equals("order") ? unit.ops().stream().filter(op ->
                        op.opId().equals(original.childOps().get(arity - 2))).findFirst().orElseThrow()
                        : defect.equals("length") && arity == 8 ? unit.ops().stream().filter(op ->
                            op.opId().equals(original.childOps().get(3))).findFirst().orElseThrow() : chain;
                    if (typed.isEmpty() || !typed.equals(raw) || !typed.equals(text)
                            || !typed.get().message().contains("validatorRule " + rule + ",")
                            || !typed.get().message().contains(detail)
                            || !typed.get().range().equals(subject.origin().span().range()))
                        throw new AssertionError("push " + arity + "/" + defect + ": " + typed + " / " + raw + " / " + text);
                    mutations++;
                }
            }
        }
        if (mutations != 6) throw new AssertionError("push mutation population " + mutations);
    }

    private static void arrayFromAdmission(deal.semantic.ir.ExecutableLoweredProject project,
            deal.semantic.CompilerInvocation invocation) {
        var facts = new deal.semantic.ir.SemanticIrValidator.ComparisonFacts(
            project.interfaceIndex().interfaceIndexDigest(), project.semanticProfile(), invocation.capabilityRegistryHash());
        if (deal.semantic.ir.SemanticIrValidator.validateText(
                deal.semantic.ir.SemanticIrValidator.toProjectText(project), facts).isPresent())
            throw new AssertionError("Array.from project transport");
        for (var unit : project.modules().values()) {
            var rebuilt = deal.semantic.ir.RawUnitMaterializer.materialize(deal.semantic.ir.RawUnit.fromTyped(unit));
            if (!deal.semantic.ir.SemanticIrValidator.toUnitText(unit).equals(deal.semantic.ir.SemanticIrValidator.toUnitText(rebuilt)))
                throw new AssertionError("Array.from raw round trip");
            int mutations=0;
            for (var op : unit.ops()) {
                if (!(op.payload() instanceof deal.semantic.ir.KindPayload.ForEachPayload loop)) continue;
                for (String defect : List.of("iterable", "body", "policy", "mode", "binding", "generation", "result", "operand")) {
                    var payload = new deal.semantic.ir.KindPayload.ForEachPayload(
                        defect.equals("mode") ? deal.semantic.ir.IterationMode.STRING_SCALARS : loop.mode(),
                        defect.equals("iterable") ? new deal.semantic.ir.ValueId(Integer.MAX_VALUE) : loop.iterable(),
                        defect.equals("binding") ? new deal.semantic.ir.BindingId(Integer.MAX_VALUE) : loop.binding(),
                        defect.equals("generation") ? Integer.MAX_VALUE : loop.generation(),
                        defect.equals("body") ? new deal.semantic.ir.BlockId(Integer.MAX_VALUE) : loop.body());
                    var policy = defect.equals("policy") ? deal.semantic.ir.FailurePolicyId.NO_DEAL_FAILURE : op.failurePolicy();
                    var result = defect.equals("result") ? loop.iterable() : op.result();
                    var resultType = defect.equals("result") ? deal.semantic.ir.RuntimeDescriptor.Number.INSTANCE : op.resultType();
                    var operands = defect.equals("operand") ? List.of(loop.iterable()) : op.operands();
                    var operandTypes = defect.equals("operand") ? List.<deal.semantic.ir.RuntimeDescriptor>of(deal.semantic.ir.RuntimeDescriptor.Number.INSTANCE) : op.operandTypes();
                    var old = op.contract();
                    var provisional = new deal.semantic.ir.OperationContractSnapshot(old.version(), old.opKind(), resultType, operandTypes,
                        old.selector(), payload, policy, old.referencedSemanticIds(), "pending");
                    var contract = new deal.semantic.ir.OperationContractSnapshot(old.version(), old.opKind(), resultType, operandTypes,
                        old.selector(), payload, policy, old.referencedSemanticIds(), deal.semantic.ir.ContractSnapshotCanonicalizer.digest(provisional));
                    var changed = new deal.semantic.ir.SemanticOp(op.opId(), op.kind(), op.origin(), result, resultType,
                        operands, operandTypes, payload, policy, contract);
                    var ops = unit.ops().stream().map(cell -> cell==op ? changed : cell).toList();
                    var malformed = new deal.semantic.ir.LoweredModuleUnit(unit.formatVersion(),unit.semanticProfile(),unit.moduleId(),
                        unit.interfaceHash(),unit.loweringContextHash(),unit.requiredCapabilities(),unit.constructCoverage(),unit.classLayouts(),
                        unit.functions(),unit.moduleInit(),unit.exportPlan(),unit.functionBindings(),ops,unit.bodyTable(),unit.classFactories(),unit.classBindingFacts());
                    var typed = deal.semantic.ir.SemanticIrValidator.validate(malformed,facts);
                    var raw = deal.semantic.ir.SemanticIrValidator.validate(deal.semantic.ir.RawUnitMaterializer.materialize(
                        deal.semantic.ir.RawUnit.fromTyped(malformed)),facts);
                    var text = deal.semantic.ir.SemanticIrValidator.validateText(deal.semantic.ir.SemanticIrValidator.toUnitText(malformed),facts);
                    if (typed.isEmpty() || !typed.equals(raw) || !typed.equals(text))
                        throw new AssertionError("Array.from " + defect + ": " + typed + " / " + raw + " / " + text);
                    mutations++;
                }
            }
            if (mutations==0) throw new AssertionError("Array.from no iteration mutations");
        }
    }

    private static void conditionalAdmission(deal.semantic.ir.ExecutableLoweredProject project,
            deal.semantic.CompilerInvocation invocation) {
        var facts = new deal.semantic.ir.SemanticIrValidator.ComparisonFacts(
            project.interfaceIndex().interfaceIndexDigest(), project.semanticProfile(), invocation.capabilityRegistryHash());
        String text = deal.semantic.ir.SemanticIrValidator.toProjectText(project);
        if (deal.semantic.ir.SemanticIrValidator.validateText(text, facts).isPresent()) throw new AssertionError("conditional text admission");
        for (var unit : project.modules().values()) {
            var raw = deal.semantic.ir.RawUnit.fromTyped(unit);
            var rebuilt = deal.semantic.ir.RawUnitMaterializer.materialize(raw);
            if (!deal.semantic.ir.SemanticIrValidator.toUnitText(unit).equals(deal.semantic.ir.SemanticIrValidator.toUnitText(rebuilt)))
                throw new AssertionError("conditional raw round trip");
            for (var cell : unit.ops()) {
                if (!(cell.payload() instanceof deal.semantic.ir.KindPayload.BoundaryPayload boundary)
                        || boundary.kind() != deal.semantic.ir.BoundaryKind.CONDITIONAL_BRANCH) continue;
                for (String defect : List.of("owner", "input", "descriptor", "policy")) {
                    var descriptor = defect.equals("descriptor") ? deal.semantic.ir.RuntimeDescriptor.String.INSTANCE : boundary.descriptor();
                    var payload = new deal.semantic.ir.KindPayload.BoundaryPayload(
                        boundary.kind(), descriptor,
                        defect.equals("input") ? pValue(unit, cell.origin().parentOpId()) : boundary.input(), boundary.realization());
                    var origin = cell.origin();
                    if (defect.equals("owner")) origin = new deal.semantic.ir.SourceOrigin(origin.sourceId(), origin.span(), origin.kind(), origin.anchorId(), cell.opId());
                    var old = cell.contract();
                    var policy = defect.equals("policy") ? deal.semantic.ir.FailurePolicyId.NO_DEAL_FAILURE : cell.failurePolicy();
                    var provisional = new deal.semantic.ir.OperationContractSnapshot(old.version(), old.opKind(), cell.resultType(), cell.operandTypes(),
                        old.selector(), payload, policy, old.referencedSemanticIds(), "pending");
                    var contract = new deal.semantic.ir.OperationContractSnapshot(old.version(), old.opKind(), cell.resultType(), cell.operandTypes(),
                        old.selector(), payload, policy, old.referencedSemanticIds(), deal.semantic.ir.ContractSnapshotCanonicalizer.digest(provisional));
                    var changed = new deal.semantic.ir.SemanticOp(cell.opId(), cell.kind(), origin, cell.result(), cell.resultType(), cell.operands(), cell.operandTypes(), payload, policy, contract);
                    var ops = unit.ops().stream().map(op -> op == cell ? changed : op).toList();
                    var malformed = new deal.semantic.ir.LoweredModuleUnit(unit.formatVersion(),unit.semanticProfile(),unit.moduleId(),
                        unit.interfaceHash(),unit.loweringContextHash(),unit.requiredCapabilities(),unit.constructCoverage(),unit.classLayouts(),
                        unit.functions(),unit.moduleInit(),unit.exportPlan(),unit.functionBindings(),ops,unit.bodyTable(),unit.classFactories(),unit.classBindingFacts());
                    var typed = deal.semantic.ir.SemanticIrValidator.validate(malformed,facts);
                    var materialized = deal.semantic.ir.SemanticIrValidator.validate(deal.semantic.ir.RawUnitMaterializer.materialize(deal.semantic.ir.RawUnit.fromTyped(malformed)),facts);
                    var transported = deal.semantic.ir.SemanticIrValidator.validateText(deal.semantic.ir.SemanticIrValidator.toUnitText(malformed),facts);
                    if (typed.isEmpty() || !typed.equals(materialized) || !typed.equals(transported))
                        throw new AssertionError("conditional adapter " + defect + ": " + typed + " / " + materialized + " / " + transported);
                }
            }
            var branch = unit.ops().stream().filter(op -> op.payload() instanceof deal.semantic.ir.KindPayload.BranchPayload p
                && p.selector()==deal.semantic.ir.ControlSelector.CONDITIONAL).findFirst().orElse(null);
            if (branch == null) continue;
            var p = (deal.semantic.ir.KindPayload.BranchPayload) branch.payload();
            for (String key : List.of("condition", "selectedBlock", "alternateBlock", "selectedValue", "alternateValue")) {
                rejectsOp(deal.semantic.ir.SemanticIrValidator.toUnitText(unit), "CONDITIONAL", op -> field(op, "payload",
                    field(payload(op), key, deal.semantic.ir.CanonicalJson.Null.INSTANCE)), facts);
                rejectsOp(deal.semantic.ir.SemanticIrValidator.toUnitText(unit), "CONDITIONAL", op -> field(op, "payload",
                    new deal.semantic.ir.CanonicalJson.Obj(payload(op).entries().stream().filter(e -> !e.key().equals(key)).toList())), facts);
            }
            rejectsOp(deal.semantic.ir.SemanticIrValidator.toUnitText(unit), "CONDITIONAL", op -> field(op, "payload",
                field(payload(op), "selector", new deal.semantic.ir.CanonicalJson.Str("IF"))), facts);
            for (String defect : List.of("condition", "selected", "alternate", "block", "missingBlock", "result", "operand", "unavailable", "forward", "policy")) {
                var payload = new deal.semantic.ir.KindPayload.BranchPayload(p.selector(),
                    defect.equals("condition") ? p.selectedValue() : defect.equals("forward") ? (deal.semantic.ir.ValueId) branch.result() : p.condition(), p.selectedBlock(),
                    defect.equals("block") ? p.selectedBlock() : defect.equals("missingBlock") ? new deal.semantic.ir.BlockId(Integer.MAX_VALUE) : p.alternateBlock(),
                    defect.equals("selected") ? p.condition() : defect.equals("unavailable") ? new deal.semantic.ir.ValueId(Integer.MAX_VALUE) : p.selectedValue(),
                    defect.equals("alternate") ? p.condition() : p.alternateValue());
                var type = defect.equals("result") ? deal.semantic.ir.RuntimeDescriptor.String.INSTANCE : branch.resultType();
                var operands = defect.equals("operand") ? List.of(p.condition()) : branch.operands();
                var types = defect.equals("operand") ? List.<deal.semantic.ir.RuntimeDescriptor>of(deal.semantic.ir.RuntimeDescriptor.Boolean.INSTANCE) : branch.operandTypes();
                var policy = defect.equals("policy") ? deal.semantic.ir.FailurePolicyId.TYPE_DESCRIPTOR : branch.failurePolicy();
                var old = branch.contract();
                var provisional = new deal.semantic.ir.OperationContractSnapshot(old.version(), old.opKind(), type, types,
                    old.selector(), payload, policy, old.referencedSemanticIds(), "pending");
                var contract = new deal.semantic.ir.OperationContractSnapshot(old.version(), old.opKind(), type, types,
                    old.selector(), payload, policy, old.referencedSemanticIds(), deal.semantic.ir.ContractSnapshotCanonicalizer.digest(provisional));
                var changed = new deal.semantic.ir.SemanticOp(branch.opId(), branch.kind(), branch.origin(), branch.result(), type,
                    operands, types, payload, policy, contract);
                var ops = unit.ops().stream().map(op -> op==branch ? changed : op).toList();
                var malformed = new deal.semantic.ir.LoweredModuleUnit(unit.formatVersion(),unit.semanticProfile(),unit.moduleId(),
                    unit.interfaceHash(),unit.loweringContextHash(),unit.requiredCapabilities(),unit.constructCoverage(),unit.classLayouts(),
                    unit.functions(),unit.moduleInit(),unit.exportPlan(),unit.functionBindings(),ops,unit.bodyTable(),unit.classFactories(),unit.classBindingFacts());
                var typed = deal.semantic.ir.SemanticIrValidator.validate(malformed,facts);
                var materialized = deal.semantic.ir.SemanticIrValidator.validate(deal.semantic.ir.RawUnitMaterializer.materialize(
                    deal.semantic.ir.RawUnit.fromTyped(malformed)),facts);
                var transported = deal.semantic.ir.SemanticIrValidator.validateText(deal.semantic.ir.SemanticIrValidator.toUnitText(malformed),facts);
                if (typed.isEmpty() || !typed.equals(materialized) || !typed.equals(transported)
                        || (!typed.get().message().contains("R-ENUM") && !defect.equals("policy"))) throw new AssertionError("conditional " + defect + ": " + typed + " / " + materialized + " / " + transported);
            }
        }
    }

    private static deal.semantic.ir.ValueId pValue(deal.semantic.ir.LoweredModuleUnit unit, deal.semantic.ir.OpId owner) {
        return ((deal.semantic.ir.KindPayload.BranchPayload) unit.ops().stream().filter(op -> op.opId().equals(owner))
            .findFirst().orElseThrow().payload()).condition();
    }

    private static void objectKeysAdmission(boolean hostile) throws Exception {
        Path work = Files.createTempDirectory(Path.of("build"), "keys-admission-").toAbsolutePath();
        try {
            Path source = Files.createDirectories(work.resolve("src")).resolve("main.deal");
            Files.writeString(source, hostile ? """
                import * as host from "host/probe";
                class Item{x:number;stable:number;}
                export function main():void{return ;}
                export function run():null{
                  const item:Item={x:9,stable:4};const r:Record<string,unknown>={f:():number=>9,child:item};
                  r.self=r;host.keep(r);host.corrupt();host.damage(item,false);
                  const a=Object.keys(r);const b=Object.keys(item);
                  if(a.length!==3 || a[0]!=="f" || a[1]!=="child" || a[2]!=="self"
                     || b.length!==2 || b[0]!=="x" || b[1]!=="stable"){
                    throw {name:"KEYS",message:"hostile values consumed"};}
                  return null;
                }
                """ : ConformanceHarnessMetadata.stripClassificationHeaders(Files.readString(
                    Path.of("test/conformance/backend-runtime/tables/builtin-object-keys.deal")))
                    .replace("export function main():void", "export function run():void")
                    + "\nexport function main():void{return ;}\n");
            Path declaration = source.resolveSibling("probe.d.deal");
            Files.writeString(declaration, Files.readString(Path.of("test/conformance/host-fixtures/array_pop.d.deal")));
            Files.writeString(work.resolve("deal.json"),
                "{\"languageVersion\":\"1.6\",\"stdlib\":\"1.6\",\"moduleRoots\":[\"src\"]}");
            var invocation = deal.semantic.CompilerProfileProvider.resolve(
                deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
            var compiler = new deal.module.CompilationOrchestrator(source, work.resolve("out"),
                false, false, false, false, deal.codegen.Backend.JS,
                hostile ? Map.of("host/probe", declaration.toString()) : Map.of(), List.of(source.getParent()), null, null, invocation);
            if (!compiler.compile()) throw new AssertionError("keys Oracle compilation: " + compiler.diagnostics());
            var project = Objects.requireNonNull(compiler.producedProject());
            var facts = new deal.semantic.ir.SemanticIrValidator.ComparisonFacts(
                project.interfaceIndex().interfaceIndexDigest(), project.semanticProfile(), invocation.capabilityRegistryHash());
            String raw = deal.semantic.ir.SemanticIrValidator.toProjectText(project);
            if (deal.semantic.ir.SemanticIrValidator.validateText(raw, facts).isPresent())
                throw new AssertionError("keys project transport");
            rejectsOp(raw, "OBJECT_KEYS", op -> field(op, "payload", field(payload(op), "receiver",
                deal.semantic.ir.CanonicalJson.Null.INSTANCE)), facts);
            rejectsOp(raw, "OBJECT_KEYS", op -> field(op, "payload", new deal.semantic.ir.CanonicalJson.Obj(List.of())), facts);
            var tables = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.StructuredBodyTable>();
            var registries = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.ClassFactoryRegistry>();
            project.modules().forEach((id, unit) -> { tables.put(id, unit.bodyTable()); registries.put(id, unit.classFactories()); });
            var responder = new deal.semantic.SemanticOracle.HostResponder() {
                private deal.semantic.SemanticOracle.Value.TableValue saved;
                public SyncOutcome call(deal.semantic.ir.ModuleId module, String export,
                        deal.semantic.ir.RuntimeDescriptor.Func descriptor,
                        List<deal.semantic.SemanticOracle.Value> arguments) {
                    switch(export) {
                        case "keep" -> saved=(deal.semantic.SemanticOracle.Value.TableValue)arguments.getFirst();
                        case "corrupt" -> saved.entries().put("f", new deal.semantic.SemanticOracle.Value.FuncValue(null,
                            (deal.semantic.ir.RuntimeDescriptor.Func)deal.semantic.ir.RuntimeDescriptor.parseCanonicalText("()->number"), Map.of()));
                        case "damage" -> ((deal.semantic.SemanticOracle.Value.ClassValue)arguments.getFirst()).fields().set(0,
                            new deal.semantic.SemanticOracle.Value.ClassFieldState.Present(new deal.semantic.SemanticOracle.Value.StrValue("bad")));
                        default -> throw new AssertionError(export);
                    }
                    return new SyncOutcome.Returned(deal.semantic.SemanticOracle.Value.UndefinedValue.INSTANCE);
                }
            };
            var result = deal.semantic.SemanticOracle.invokeSyncEntry(project, tables, registries,
                responder, compiler.checkedProject().input().entryModule(), "run", List.of());
            if (!(result.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success success)
                    || !success.resultAtom().equals(hostile ? "null" : "undefined"))
                throw new AssertionError("keys Oracle: " + result.comparisonReport());
            long snapshots = result.trace().stream().filter(e -> e.kind()==deal.semantic.ir.SemanticOpKind.OBJECT_KEYS
                && e.phase()==deal.semantic.SemanticRuntimeModel.Phase.SUCCESS).count();
            if (snapshots!=(hostile ? 2 : 11)) throw new AssertionError("keys Oracle execution population: " + snapshots);
        } finally { CompositionProductionTest.remove(work); }
    }

    /** Execute the same retained-mutation contract in the Oracle with genuine lowered bodies. */
    private static void arrayRemovalAdmission(String fixture) throws Exception {
        boolean splice = fixture.startsWith("splice"), slice = fixture.startsWith("slice"), search = fixture.startsWith("search"), join = fixture.startsWith("join"), map = fixture.startsWith("map") || fixture.startsWith("find") || fixture.startsWith("predicates");
        boolean skipMissing = fixture.startsWith("predicates");
        String operation = map ? "ARRAY_ELEMENT_GET" : join ? "ARRAY_JOIN" : search ? "ARRAY_SEARCH" : slice ? "ARRAY_SLICE" : splice ? "ARRAY_SPLICE" : "ARRAY_POP";
        Path work = Files.createTempDirectory(Path.of("build"), "pop-admission-").toAbsolutePath();
        try {
            Path source = Files.createDirectories(work.resolve("src")).resolve("main.deal");
            Files.writeString(source, ConformanceHarnessMetadata.stripClassificationHeaders(Files.readString(
                Path.of("test/conformance/backend-runtime/arrays/" + fixture + ".deal")))
                .replace("export function main():void", "export function run():void")
                + "\nexport function main():void{return ;}\n"
                + (map ? "export function hole():number{let xs:unknown[]=[undefined];host.keep(xs);host.corrupt();return "
                    + (skipMissing ? "holePredicates(xs);"
                        : fixture.startsWith("find") ? "xs.findIndex((value:unknown):boolean=>true);" : "xs.map((value:unknown):unknown=>value).length;") + "}\n"
                    + (skipMissing ? "function holePredicates(xs:unknown[]):number{let calls=0;"
                        + "let selected=xs.filter((value:unknown):boolean=>{calls=calls+1;return true;});"
                        + "let some=xs.some((value:unknown):boolean=>{calls=calls+1;return true;});"
                        + "let every=xs.every((value:unknown):boolean=>{calls=calls+1;return false;});"
                        + "check(calls===selected.length*3 && some===(selected.length===1) && every===(selected.length===0));"
                        + "return selected.length;}\n" : "") : ""));
            Path declaration = source.resolveSibling("probe.d.deal");
            Files.copy(Path.of("test/conformance/host-fixtures/" + (join ? "array_join" : "array_pop") + ".d.deal"), declaration);
            Files.writeString(work.resolve("deal.json"),
                "{\"languageVersion\":\"1.6\",\"stdlib\":\"1.6\",\"moduleRoots\":[\"src\"]}");
            var invocation = deal.semantic.CompilerProfileProvider.resolve(
                deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
            var compiler = new deal.module.CompilationOrchestrator(source, work.resolve("out"),
                false, false, false, false, deal.codegen.Backend.JS,
                Map.of("host/probe", declaration.toString()), List.of(source.getParent()), null, null, invocation);
            if (!compiler.compile()) throw new AssertionError("pop Oracle compile failed: " + compiler.diagnostics());
            var project = Objects.requireNonNull(compiler.producedProject());
            var facts = new deal.semantic.ir.SemanticIrValidator.ComparisonFacts(
                project.interfaceIndex().interfaceIndexDigest(), project.semanticProfile(), invocation.capabilityRegistryHash());
            String raw = deal.semantic.ir.SemanticIrValidator.toProjectText(project);
            if (deal.semantic.ir.SemanticIrValidator.validate(project, facts).isPresent()
                    || deal.semantic.ir.SemanticIrValidator.validateText(raw, facts).isPresent())
                throw new AssertionError(operation + " project transport rejected: "
                    + deal.semantic.ir.SemanticIrValidator.validate(project, facts) + " / "
                    + deal.semantic.ir.SemanticIrValidator.validateText(raw, facts));
            for (var unit : project.modules().values()) {
                var rebuilt = deal.semantic.ir.RawUnitMaterializer.materialize(deal.semantic.ir.RawUnit.fromTyped(unit));
                if (!deal.semantic.ir.SemanticIrValidator.toUnitText(unit).equals(
                        deal.semantic.ir.SemanticIrValidator.toUnitText(rebuilt)))
                    throw new AssertionError("pop typed/raw roundtrip changed content");
            }
            var empty = new deal.semantic.ir.CanonicalJson.Arr(List.of());
            var missing = deal.semantic.ir.ContractSnapshotCanonicalizer.semanticIdJson(
                new deal.semantic.ir.ValueId(Integer.MAX_VALUE));
            rejectsOp(raw, operation, op -> field(op, "payload", field(payload(op), "arrayValue", missing)), facts);
            rejectsOp(raw, operation, op -> field(op, "payload", field(payload(op), "arrayValue",
                deal.semantic.ir.CanonicalJson.Null.INSTANCE)), facts);
            rejectsOp(raw, operation, op -> field(field(op, "operands", empty), "operandTypes", empty), facts);
            if (join) for (var bad : List.of(missing, deal.semantic.ir.CanonicalJson.Null.INSTANCE))
                rejectsOp(raw, operation, op -> field(op, "payload", field(payload(op), "separator", bad)), facts);
            if (!splice && !slice && !search && !join && !map) rejectsOp(raw, operation, op -> field(op, "operandTypes", new deal.semantic.ir.CanonicalJson.Arr(
                List.of(new deal.semantic.ir.CanonicalJson.Str("[string]")))), facts);
            if (splice || slice) {
                for (String key : List.of("start", slice ? "end" : "deleteCount")) {
                    rejectsOp(raw, operation, op -> field(op, "payload", field(payload(op), key, missing)), facts);
                    rejectsOp(raw, operation, op -> field(op, "payload", field(payload(op), key,
                        deal.semantic.ir.CanonicalJson.Null.INSTANCE)), facts);
                }
            }
            if (search) for (String key : List.of("value", "fromIndex", "includes")) {
                rejectsOp(raw, operation, op -> field(op, "payload", field(payload(op), key,
                    deal.semantic.ir.CanonicalJson.Null.INSTANCE)), facts);
            }
            rejectsOp(raw, operation, op -> field(op, "resultType", new deal.semantic.ir.CanonicalJson.Str(search || map ? "string" : "number")), facts);
            var tables = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.StructuredBodyTable>();
            var registries = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.ClassFactoryRegistry>();
            project.modules().forEach((id, unit) -> { tables.put(id, unit.bodyTable()); registries.put(id, unit.classFactories()); });
            var responder = new deal.semantic.SemanticOracle.HostResponder() {
                private deal.semantic.SemanticOracle.Value.ArrayValue saved;
                private deal.semantic.SemanticOracle.Value.ClassValue damaged;
                private deal.semantic.SemanticOracle.Value originalFunction;
                int holeMode;
                public SyncOutcome call(deal.semantic.ir.ModuleId module, String export,
                        deal.semantic.ir.RuntimeDescriptor.Func descriptor,
                        List<deal.semantic.SemanticOracle.Value> arguments) {
                    deal.semantic.SemanticOracle.Value result = deal.semantic.SemanticOracle.Value.UndefinedValue.INSTANCE;
                    switch (export) {
                        case "keep" -> saved = (deal.semantic.SemanticOracle.Value.ArrayValue)arguments.getFirst();
                        case "replace" -> saved.elements().set((int)((deal.semantic.SemanticOracle.Value.NumValue)arguments.getFirst()).value(), arguments.get(1));
                        case "append" -> saved.elements().add(arguments.getFirst());
                        case "poison", "poisonSignature" -> {
                            originalFunction = saved.elements().getFirst();
                            var function = (deal.semantic.SemanticOracle.Value.FuncValue) originalFunction;
                            saved.elements().set(0, new deal.semantic.SemanticOracle.Value.FuncValue(
                                export.equals("poison") ? null : function.function(),
                                export.equals("poisonSignature") ? (deal.semantic.ir.RuntimeDescriptor.Func)
                                    deal.semantic.ir.RuntimeDescriptor.parseCanonicalText("()->string") : function.signature(),
                                function.captures()));
                        }
                        case "restoreFunction" -> saved.elements().set(0, originalFunction);
                        case "corrupt" -> saved.elements().set(saved.elements().size()-1,
                            holeMode==1 ? deal.semantic.SemanticOracle.Value.MissingValue.INSTANCE
                            : holeMode==2 ? deal.semantic.SemanticOracle.Value.UndefinedValue.INSTANCE
                            : new deal.semantic.SemanticOracle.Value.StrValue("bad"));
                        case "repair" -> saved.elements().set(saved.elements().size()-1,
                            new deal.semantic.SemanticOracle.Value.NumValue(9));
                        case "size" -> result = new deal.semantic.SemanticOracle.Value.NumValue(saved.elements().size());
                        case "echo" -> result = arguments.getFirst();
                        case "make" -> {
                            saved = new deal.semantic.SemanticOracle.Value.ArrayValue(new ArrayList<>(List.of(
                                new deal.semantic.SemanticOracle.Value.NumValue(4),
                                new deal.semantic.SemanticOracle.Value.NumValue(9))),
                                deal.semantic.ir.RuntimeDescriptor.Number.INSTANCE, false);
                            result = saved;
                        }
                        case "grow" -> saved.elements().add(new deal.semantic.SemanticOracle.Value.NumValue(11));
                        case "damage" -> {
                            boolean restore=((deal.semantic.SemanticOracle.Value.BoolValue)arguments.get(1)).value();
                            if (!restore) damaged=(deal.semantic.SemanticOracle.Value.ClassValue)arguments.getFirst();
                            damaged.fields().set(0,new deal.semantic.SemanticOracle.Value.ClassFieldState.Present(restore
                                ? new deal.semantic.SemanticOracle.Value.NumValue(9)
                                : new deal.semantic.SemanticOracle.Value.StrValue("bad")));
                        }
                        default -> throw new AssertionError(export);
                    }
                    return new SyncOutcome.Returned(result);
                }
            };
            var result = deal.semantic.SemanticOracle.invokeSyncEntry(project, tables, registries,
                responder, compiler.checkedProject().input().entryModule(), "run", List.of());
            if (!(result.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success success)
                    || !success.resultAtom().equals("undefined"))
                throw new AssertionError("pop Oracle: " + result.comparisonReport() + "\n" + result.trace().stream()
                    .filter(e -> e.phase()==deal.semantic.SemanticRuntimeModel.Phase.FAILURE).toList());
            var failures = result.trace().stream().filter(e -> e.kind()==deal.semantic.ir.SemanticOpKind.valueOf(operation)
                && e.phase()==deal.semantic.SemanticRuntimeModel.Phase.FAILURE).toList();
            if(map) for(int mode:List.of(1,2)) {
                responder.holeMode=mode;
                var hole=deal.semantic.SemanticOracle.invokeSyncEntry(project,tables,registries,responder,
                    compiler.checkedProject().input().entryModule(),"hole",List.of());
                var events=hole.trace().stream().filter(e->e.kind()==deal.semantic.ir.SemanticOpKind.ARRAY_ELEMENT_GET
                    && e.phase()!=deal.semantic.SemanticRuntimeModel.Phase.START).toList();
                if(events.size()!=(skipMissing ? mode==1 ? 0 : 3 : 1) || responder.saved.elements().size()!=1
                        || responder.saved.elements().getFirst()!=(mode==1 ? deal.semantic.SemanticOracle.Value.MissingValue.INSTANCE
                            : deal.semantic.SemanticOracle.Value.UndefinedValue.INSTANCE)) throw new AssertionError(hole.comparisonReport());
                if(mode==1 && !skipMissing) {
                    var event=events.getFirst();var error=event.error();
                    var op=project.modules().get(event.op().module()).ops().stream().filter(o->o.opId().equals(event.op())).findFirst().orElseThrow();
                    String origin=op.origin().sourceId()+":"+op.origin().span().startLine()+":"+op.origin().span().startColumn();
                    if(error==null || !error.code().equals("E8002") || !error.message().equals("array index out of bounds")
                            || error.expected()!=null || error.actual()!=null || error.cause()!=null
                            || !error.origin().equals(origin) || error.frames().isEmpty()) throw new AssertionError(event);
                } else if(!(hole.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success done)
                        || !done.resultAtom().equals(fixture.startsWith("find") || skipMissing && mode==1 ? "num:0x0.0p0" : "num:0x1.0p0")) throw new AssertionError(hole.comparisonReport());
            }
            int population = map || join ? 4 : slice || search ? 6 : 3;
            if (failures.size()!=population) throw new AssertionError(operation + " failure trace population: " + failures);
            for (int i=0; i<population; i++) {
                var error = failures.get(i).error();
                String expected = map ? (i==0 ? "number" : i==1 ? "@src/main.deal/Item" : "()->number") : join ? "string" : slice || search ? (i==0 || i==5 ? "number" : i==1 ? "[number]" : i==2
                    ? "@src/main.deal/Item" : "()->number")
                    : i==0 ? "[number]" : i==1 ? "number" : "@src/main.deal/Item";
                String actual = map ? (i==0 ? "string" : i==1 ? expected : "function") : join ? (i==1 ? "undefined" : "null") : slice || search ? (i==0 || i==5 ? "string" : i==1 ? "array" : i>=3 ? "function" : expected)
                    : i==0 ? "array" : i==1 ? "string" : expected;
                int position = map ? (i==0 ? 3 : 1) : join ? (i==0 || i==3 ? 2 : i==1 ? 3 : 1) : slice || search ? (i==0 || i==5 ? 3 : i==1 || i==2 ? 2 : 1)
                    : splice ? (i==0 ? 2 : i==1 ? 3 : 1) : (i==1 ? 2 : 1);
                String leafExpected = map ? (i==3 ? "()->number" : i==2 ? "function" : "number") : join ? "string" : (slice || search) && i==4 ? "()->number" : (slice || search) && i==3 ? "function" : "number";
                String leafActual = map ? (i==3 ? "()->string" : i==2 ? "function" : "string") : join ? actual : (slice || search) && i==4 ? "()->string" : (slice || search) && i==3 ? "function" : "string";
                String leafCode = map && i==3 || (slice || search) && i==4 ? "E8010" : "E8001";
                if (!error.code().equals("E8003") || !error.expected().equals(expected)
                        || !error.actual().equals(actual)
                        || !error.message().equals("array element " + position + " type mismatch") || error.cause()==null
                        || !error.cause().code().equals(leafCode) || !error.cause().expected().equals(leafExpected)
                        || !error.cause().actual().equals(leafActual) || error.frames().isEmpty())
                    throw new AssertionError("pop projection: " + error);
                var failedId=failures.get(i).op();
                var op=project.modules().get(failedId.module()).ops().stream()
                    .filter(o -> o.opId().equals(failedId)).findFirst().orElseThrow();
                String expectedOrigin=op.origin().sourceId()+":"+op.origin().span().startLine()+":"+op.origin().span().startColumn();
                if (!error.origin().equals(expectedOrigin) || !error.cause().origin().equals(expectedOrigin)
                        || !error.cause().frames().equals(error.frames()))
                    throw new AssertionError("pop origin/frames " + error);
            }
        } finally { CompositionProductionTest.remove(work); }
    }

    /** Exercise the evaluator adapter, not just a hand-built BoundaryValueView. */
    private static void callableBodyAdmission() throws Exception {
        Path work = Files.createTempDirectory(Path.of("build"), "callable-admission-").toAbsolutePath();
        try {
            Path source = Files.createDirectories(work.resolve("src")).resolve("main.deal");
            Files.writeString(source, """
                import * as host from "host/probe";
                export function main():void{return ;}
                export function run():number {
                    try { let value=host.value(); return 0; } catch(e) { return 1; }
                }
                """);
            Path declaration = source.resolveSibling("probe.d.deal");
            Files.writeString(declaration, "export function value():unknown;");
            Files.writeString(work.resolve("deal.json"),
                "{\"languageVersion\":\"1.6\",\"stdlib\":\"1.6\",\"moduleRoots\":[\"src\"]}");
            var invocation = deal.semantic.CompilerProfileProvider.resolve(
                deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
            var compiler = new deal.module.CompilationOrchestrator(source, work.resolve("out"),
                false, false, false, false, deal.codegen.Backend.JS,
                Map.of("host/probe", declaration.toString()), List.of(source.getParent()), null, null, invocation);
            if (!compiler.compile()) throw new AssertionError("callable admission compilation failed");
            var project = Objects.requireNonNull(compiler.producedProject());
            var tables = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.StructuredBodyTable>();
            var registries = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.ClassFactoryRegistry>();
            project.modules().forEach((id, unit) -> { tables.put(id, unit.bodyTable()); registries.put(id, unit.classFactories()); });
            for (boolean nested : List.of(false, true)) {
                int[] calls = {0};
                var responder = new deal.semantic.SemanticOracle.HostResponder() {
                    public SyncOutcome call(deal.semantic.ir.ModuleId module, String export,
                            deal.semantic.ir.RuntimeDescriptor.Func descriptor,
                            List<deal.semantic.SemanticOracle.Value> arguments) {
                        calls[0]++;
                        var signature = (deal.semantic.ir.RuntimeDescriptor.Func)
                            deal.semantic.ir.RuntimeDescriptor.parseCanonicalText("()->number");
                        deal.semantic.SemanticOracle.Value value = new deal.semantic.SemanticOracle.Value.FuncValue(
                            null, signature, Map.of());
                        if (nested) {
                            var entries = new LinkedHashMap<String, deal.semantic.SemanticOracle.Value>();
                            var record = new deal.semantic.SemanticOracle.Value.TableValue(entries);
                            entries.put("self", record);
                            entries.put("unread", value);
                            value = record;
                        }
                        return new SyncOutcome.Returned(value);
                    }
                };
                var result = deal.semantic.SemanticOracle.invokeSyncEntry(project, tables, registries,
                    responder, compiler.checkedProject().input().entryModule(), "run", List.of());
                if (calls[0] != 1 || !(result.terminal() instanceof
                        deal.semantic.SemanticRuntimeModel.Terminal.Success success)
                        || !success.resultAtom().equals("num:0x1.0p0"))
                    throw new AssertionError("bodyless callable admitted (nested=" + nested + "): " + result.comparisonReport());
            }
        } finally {
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    /** Exercise live builtin Error schema/descendant views through real lowering. */
    private static void errorGraphAdmission() throws Exception {
        Path work = Files.createTempDirectory(Path.of("build"), "error-admission-").toAbsolutePath();
        try {
            Path source = Files.createDirectories(work.resolve("src")).resolve("main.deal");
            Path declaration = source.resolveSibling("probe.d.deal");
            Files.writeString(declaration, "export function edit(e:Error):void;");
            Files.writeString(work.resolve("deal.json"),
                "{\"languageVersion\":\"1.6\",\"stdlib\":\"1.6\",\"moduleRoots\":[\"src\"]}");
            for (String boundary : List.of("Error", "unknown", "Record<string,unknown>")) {
                String value = boundary.equals("Record<string,unknown>") ? "{unread:e}" : "e";
                Files.writeString(source, "import * as host from \"host/probe\";"
                    + "export function main():void{return ;}"
                    + "export function run():number{let e:Error={name:\"X\",message:\"original\"};"
                    + "host.edit(e);try{let v:" + boundary + "=" + value
                    + ";return 0;}catch(x){return 1;}}");
                var invocation = deal.semantic.CompilerProfileProvider.resolve(
                    deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                    deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
                var compiler = new deal.module.CompilationOrchestrator(source, work.resolve("out"),
                    false, false, false, false, deal.codegen.Backend.JS,
                    Map.of("host/probe", declaration.toString()), List.of(source.getParent()), null, null, invocation);
                if (!compiler.compile()) throw new AssertionError("Error admission compile failed");
                var project = Objects.requireNonNull(compiler.producedProject());
                var tables = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.StructuredBodyTable>();
                var registries = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.ClassFactoryRegistry>();
                project.modules().forEach((id, unit) -> { tables.put(id, unit.bodyTable()); registries.put(id, unit.classFactories()); });
                for (String field : List.of("name", "message")) for (boolean invalid : List.of(false, true)) {
                    int[] calls = {0};
                    var responder = new deal.semantic.SemanticOracle.HostResponder() {
                        @Override public SyncOutcome call(deal.semantic.ir.ModuleId module, String export,
                                deal.semantic.ir.RuntimeDescriptor.Func descriptor,
                                List<deal.semantic.SemanticOracle.Value> arguments) {
                            calls[0]++;
                            try {
                                var write = deal.semantic.SemanticOracle.Value.ErrorValue.class
                                    .getDeclaredMethod("writeField", String.class, String.class);
                                write.setAccessible(true);
                                write.invoke(arguments.getFirst(), field, invalid ? "\ud800" : "changed 🌍");
                            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                            return new SyncOutcome.Returned(deal.semantic.SemanticOracle.Value.UndefinedValue.INSTANCE);
                        }
                    };
                    var result = deal.semantic.SemanticOracle.invokeSyncEntry(project, tables, registries,
                        responder, compiler.checkedProject().input().entryModule(), "run", List.of());
                    String expected = "num:0x0.0p0";
                    if (calls[0] != 1 || !(result.terminal() instanceof
                            deal.semantic.SemanticRuntimeModel.Terminal.Success success)
                            || !success.resultAtom().equals(expected))
                        throw new AssertionError(boundary + "/" + field + "/" + invalid + ": " + result.comparisonReport());
                }
            }
        } finally {
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void executeFixture(String fixture) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path work = Files.createTempDirectory(root.resolve("build"), "language16-");
        try {
            Path source = Files.createDirectories(work.resolve("src")).resolve("main.deal");
            Path fixtures = root.resolve("test/conformance/backend-runtime/language16");
            Files.writeString(source, ConformanceHarnessMetadata.stripClassificationHeaders(
                Files.readString(fixtures.resolve(fixture + ".deal"))));
            Files.writeString(source.resolveSibling("nominal.deal"),
                ConformanceHarnessMetadata.stripClassificationHeaders(
                    Files.readString(fixtures.resolve("nominal.deal"))));
            Files.writeString(work.resolve("deal.json"),
                "{\"languageVersion\":\"1.6\",\"stdlib\":\"1.6\",\"moduleRoots\":[\"src\"]}\n");
            String cp = Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(p -> root.resolve(p).normalize().toString()).reduce((a,b) -> a + java.io.File.pathSeparator + b).orElseThrow();
            var invocation = deal.semantic.CompilerProfileProvider.resolve(
                deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
            var orchestrator = new deal.module.CompilationOrchestrator(source, work.resolve("oracle"),
                false, false, false, false, deal.codegen.Backend.JS, Map.of(),
                List.of(source.getParent()), null, null, invocation);
            if (!orchestrator.compile()) throw new AssertionError("Oracle fixture compile rejected");
            var project = Objects.requireNonNull(orchestrator.producedProject());
            var facts = new deal.semantic.ir.SemanticIrValidator.ComparisonFacts(
                project.interfaceIndex().interfaceIndexDigest(), project.semanticProfile(),
                invocation.capabilityRegistryHash());
            var textFailure = deal.semantic.ir.SemanticIrValidator.validateText(
                deal.semantic.ir.SemanticIrValidator.toProjectText(project), facts);
            if (textFailure.isPresent()) throw new AssertionError(textFailure.get());
            String raw = deal.semantic.ir.SemanticIrValidator.toProjectText(project);
            if (fixture.equals("core")) {
            rejectsRaw(raw, "\"target\":null", "\"target\":42", facts);
            rejectsRaw(raw, "\"selector\":\"INSTANCEOF\",\"target\":\"Error\"",
                "\"selector\":\"INSTANCEOF\",\"target\":null", facts);
            rejectsRaw(raw, "\"selector\":\"INSTANCEOF\",\"target\":\"Error\"",
                "\"selector\":\"INSTANCEOF\",\"target\":\"number\"", facts);
            rejectsRaw(raw, "\"dynamicKey\":null,\"key\":\"missing\"",
                "\"dynamicKey\":null,\"key\":null", facts);
            rejectsRaw(raw, "\"dynamicKey\":null,\"key\":\"missing\"",
                "\"dynamicKey\":42,\"key\":\"missing\"", facts);
            var noValues = new deal.semantic.ir.CanonicalJson.Arr(List.of());
            var missingValue = deal.semantic.ir.ContractSnapshotCanonicalizer.semanticIdJson(
                new deal.semantic.ir.ValueId(Integer.MAX_VALUE));
            rejectsOp(raw, "UNARY", op -> field(field(op, "operands", noValues), "operandTypes", noValues), facts);
            rejectsOp(raw, "UNARY", op -> field(op, "operands",
                new deal.semantic.ir.CanonicalJson.Arr(List.of(missingValue))), facts);
            rejectsOp(raw, "UNARY", op -> field(op, "operandTypes",
                new deal.semantic.ir.CanonicalJson.Arr(List.of(new deal.semantic.ir.CanonicalJson.Str("string")))), facts);
            rejectsOp(raw, "UNARY", op -> field(op, "resultType",
                new deal.semantic.ir.CanonicalJson.Str("unknown")), facts);
            rejectsOp(raw, "HAS_FIELD", op -> field(op, "payload",
                field(field(payload(op), "key", new deal.semantic.ir.CanonicalJson.Str("both")),
                    "dynamicKey", missingValue)), facts);
            rejectsOp(raw, "HAS_FIELD", op -> field(op, "payload",
                field(payload(op), "receiver", missingValue)), facts);
            rejectsOp(raw, "HAS_FIELD", op -> field(op, "payload",
                field(field(payload(op), "key", deal.semantic.ir.CanonicalJson.Null.INSTANCE),
                    "dynamicKey", missingValue)), facts);
            }
            var tables = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.StructuredBodyTable>();
            var registries = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.ClassFactoryRegistry>();
            project.modules().forEach((id, unit) -> { tables.put(id, unit.bodyTable()); registries.put(id, unit.classFactories()); });
            var oracle = deal.semantic.SemanticOracle.executeProjectInits(project, tables, registries,
                new deal.semantic.SemanticOracle.HostResponder() {});
            if (!(oracle.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success) || !oracle.effects().isEmpty())
                throw new AssertionError(oracle.comparisonReport());
            for (String backend : List.of("js", "lua", "jvm")) {
                Path out = work.resolve(backend);
                Process compile = new ProcessBuilder("java", "-cp", cp, Main.class.getName(), "compile",
                    source.toString(), "--backend", backend, "--output", out.toString())
                    .directory(root.toFile()).redirectErrorStream(true).start();
                if (!compile.waitFor(90, TimeUnit.SECONDS)) { compile.destroyForcibly(); throw new AssertionError("Compiler timeout"); }
                String output = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (compile.exitValue() != 0 || !output.matches("(?s)Compilation successful: [1-9][0-9]* module\\(s\\)\\s*")) throw new AssertionError(output);
                switch (backend) {
                    case "js" -> {
                        run(out, "node", "main.js");
                        if (fixture.equals("core")) {
                        run(out, "node", "-e", """
                            const rt=require('./deal/runtime'),entry=require('./main');
                            rt.invokeAsyncExport(entry,'exportedNumbers','[number]').then(result=>{
                              if(!result.$ok) throw Error('completion');
                              const a=result.$value;
                              if(rt.checkType('[number]',a)!==a) throw Error('identity');
                              let rejected=false;
                              try {rt.checkType('[unknown]',a)} catch(e) {rejected=e.code==='E8001'}
                              if(!rejected) throw Error('projected array lost descriptor');
                            }).catch(e=>{console.error(e);process.exit(1)});
                            """);
                        run(out, "node", "-e", "const rt=require('./deal/runtime'); const entry=require('./main'); rt.invokeAsyncExport(entry,'run','number').then(v=>{if(!v.$ok||v.$value!==7){console.error(v);process.exit(1)}},e=>{console.error(e);process.exit(1)});");
                        }
                    }
                    case "lua" -> {
                        run(out, "luajit", "main.lua");
                        if (fixture.equals("core")) {
                        for (String loadRuntime : List.of("", "require('deal.runtime');")) {
                            run(out, "luajit", "-e", loadRuntime + """
                                local entry=dofile('main.lua')
                                local json=require('deal.json')
                                local instance=entry.exportedNode.f()
                                assert(json.encode(instance)=='{"first":true,"tail":null}')
                                local rt=require('deal.runtime'); local k=rt.__keys
                                instance[k.f].next=instance; instance[k.p].next=true
                                local ok,e=pcall(json.encode,instance)
                                assert(not ok and e.code=='E8001')
                                instance[k.p].next=nil
                                assert(json.encode(instance)=='{"first":true,"tail":null}')
                                """);
                        }
                        var result = new deal.codegen.lua.LuaJitAsyncExportInvoker().invoke(
                            new deal.codegen.lua.AsyncExportInvocationRequest(out.resolve("main.lua"), "run", "number"));
                        if (!(result instanceof deal.codegen.lua.LuaJitAsyncExportInvoker.Result.Value value)
                                || Double.parseDouble(value.valueJson()) != 7) throw new AssertionError(result);
                        }
                    }
                    case "jvm" -> {
                        run(root, "javac", "--release", "25", "-proc:none", "-cp", cp, "-d", out.toString(), out.resolve("Main.java").toString());
                        run(root, "java", "-cp", out + java.io.File.pathSeparator + cp, "Main");
                        if (fixture.equals("core")) {
                        var result = new deal.codegen.jvm.JvmAsyncExportInvoker().invoke(
                            new deal.codegen.lua.AsyncExportInvocationRequest(out.resolve("Main.class"), "run", "number"));
                        if (!(result instanceof deal.codegen.jvm.JvmAsyncExportInvoker.Result.Value value)
                                || Double.parseDouble(value.valueJson()) != 7) throw new AssertionError(result);
                        }
                    }
                    default -> throw new AssertionError(backend);
                }
            }
            // Exercise shipped modules directly too: generated Lua/JVM
            // stdlib realizations cannot stand in for the deployed wrappers.
            run(root, "node", "-e", """
                const rt=require('./deal/runtime'),s=require('./std/string'),t=require('./std/table');
                const strings=rt.tagArray(['a',,'b'],'string');
                if(rt.arrayJoin(strings,'|','join.deal',2,3)!=='a||b') throw Error('join hole separators');
                strings[1]=undefined;
                let rejectedJoin=false;
                try{rt.arrayJoin(strings,'|','join.deal',2,3)}
                catch(e){rejectedJoin=e.code==='E8003' && e.message==='array element 2 type mismatch'
                  && e.expected==='string' && e.actual==='undefined' && e.cause.code==='E8001'}
                if(!rejectedJoin || strings.length!==3 || !(1 in strings)) throw Error('join present undefined/rollback');
                const sparse=rt.tagArray([1,,undefined],'!number');
                for(const index of [1,-1,3,.5,NaN,Infinity,-Infinity]) {
                  let rejected=false;try{rt.arrayElementGet(sparse,index,'!number','map.deal',2,3)}
                  catch(e){rejected=e.code==='E8002' && e.message==='array index out of bounds'}
                  if(!rejected) throw Error('strict read missing/position '+index);
                }
                for(const index of [1,-1,3,.5,NaN,Infinity,-Infinity])
                  if(rt.arrayPositionPresent(sparse,index)) throw Error('presence admitted missing/invalid position');
                if(!rt.arrayPositionPresent(sparse,-0) || !rt.arrayPositionPresent(sparse,2)) throw Error('present undefined/negative zero');
                if(rt.arrayElementGet(sparse,2,'!number','map.deal',2,3)!==undefined
                    || rt.arrayElementGet(sparse,-0,'!number','map.deal',2,3)!==1) throw Error('strict present undefined/negative zero');
                let rejectedElement=false;try{rt.arrayElementGet(sparse,2,'number','map.deal',2,3)}
                catch(e){rejectedElement=e.code==='E8003' && e.message==='array element 3 type mismatch'
                  && e.expected==='number' && e.actual==='undefined' && e.cause.code==='E8001'}
                if(!rejectedElement || sparse.length!==3 || 1 in sparse) throw Error('strict read descriptor/rollback');
                if(!rt.arraySearch(sparse,undefined,1,true,'!number','search.deal',2,3)
                    || rt.arraySearch(sparse,undefined,1,false,'!number','search.deal',2,3)!==2)
                  throw Error('search must distinguish holes from stored undefined');
                const slice=rt.arraySlice(sparse,1,3,'!number','slice.deal',1,1);
                if(slice===sparse || slice.length!==2 || 0 in slice || !(1 in slice) || slice[1]!==undefined)
                  throw Error('slice must preserve holes separately from undefined');
                rt.tagArray(sparse,'number');
                if(0 in rt.arraySlice(sparse,1,2,'number','slice.deal',1,1)) throw Error('slice materialized hole');
                let rejectedUndefined=false;
                try {rt.arraySlice(sparse,2,3,'number','slice.deal',1,1)}
                catch(e){rejectedUndefined=e.code==='E8003' && e.message==='array element 3 type mismatch'}
                if(!rejectedUndefined) throw Error('slice skipped stored undefined');
                if(!rt.arraySearch(sparse,undefined,1,true,'number','search.deal',2,3))
                  throw Error('includes hole must not admit a missing element');
                let rejectedSearch=false;
                try {rt.arraySearch(sparse,undefined,1,false,'number','search.deal',2,3)}
                catch(e){rejectedSearch=e.code==='E8003' && e.message==='array element 3 type mismatch'}
                if(!rejectedSearch || sparse.length!==3 || 1 in sparse) throw Error('search reached undefined/rollback');
                if(rt.checkType('unknown','\\uD800')!=='\\uD800') throw Error('lone unit ingress');
                for (const bad of [()=>{}, Promise.resolve(1), {}, Symbol('foreign')]) {
                  let rejected=false;
                  try {rt.checkType('unknown',bad)} catch(e) {rejected=e.code==='E8001'}
                  if(!rejected) throw Error('unsupported unknown ingress');
                }
                const json={parse:{$f:text=>rt.jsonDecode(text,'unknown')},
                  stringify:{$f:value=>rt.jsonStringifyText(value,[])}};
                const untouched=[];
                const invalid=new Map([['bad',{$kind:'function',$sig:'()->number',$f:null}],['array',untouched]]);
                let invalidRejected=false;
                try {rt.checkType('unknown',invalid)} catch(e) {invalidRejected=e.code==='E8001'}
                if(!invalidRejected) throw Error('malformed unread callable');
                // Failed graph validation must not publish an unknown[] descriptor.
                const typed=rt.hostFunction('()->[number]',()=>untouched).$f();
                if(typed!==untouched || rt.checkType('[number]',typed)!==typed) throw Error('failed ingress published descriptor');
                const parsed=json.parse.$f('{"a":[1]}').get('a');
                rt.hostAsyncFunction('async([unknown])->record',async a=>new Map([['child',a]])).$f(parsed)
                  .then(result=>{if(result.get('child')!==parsed) throw Error('async recursive reverse identity')})
                  .catch(error=>{console.error(error);process.exitCode=1;});
                const instance=rt.classPlan('@m/Node',[
                  {name:'first',descriptor:'boolean',optional:false},
                  {name:'next',descriptor:'unknown',optional:true},
                  {name:'tail',descriptor:'?string',optional:false}],{tail:null,first:true});
                if(json.stringify.$f(instance)!=='{"first":true,"tail":null}') throw Error('class field order');
                instance.next=instance;
                let cycle=false; try {json.stringify.$f(instance)} catch(e) {cycle=e.code==='E8001'}
                if(!cycle) throw Error('class cycle');
                delete instance.next;
                const fields=[{name:'first',descriptor:'boolean',optional:false},
                  {name:'tail',descriptor:'?string',optional:false}];
                rt.registerClassPlan('@m/Decoded',fields);
                const decoded=rt.jsonDecode('{"tail":null,"first":true}','@m/Decoded');
                if(decoded===null || json.stringify.$f(decoded)!=='{"first":true,"tail":null}') throw Error('decoded class metadata');
                rt.hostFunction('(@m/Decoded)->void',value=>{
                  if(json.stringify.$f(value)!=='{"first":true,"tail":null}') throw Error('projected class metadata');
                }).$f(decoded);
                if(json.stringify.$f(new Map([['a',instance],['b',instance]]))!==
                    '{"a":{"first":true,"tail":null},"b":{"first":true,"tail":null}}') throw Error('class DAG');
                let deep=instance;
                for(let i=0;i<600;i++) deep=rt.classPlan('@m/Node',[
                  {name:'first',descriptor:'boolean',optional:false},
                  {name:'next',descriptor:'unknown',optional:true},
                  {name:'tail',descriptor:'?string',optional:false}],{tail:null,first:true,next:deep});
                if((json.stringify.$f(deep).match(/"first":true/g)||[]).length!==601) throw Error('deep class frames');
                if(json.stringify.$f(rt.errorValue('C','m'))!=='{"name":"C","message":"m"}') throw Error('Error fields');
                const callable=rt.checkType('unknown',s.split);
                if(callable!==s.split) throw Error('function identity');
                for (const a of [s.split.$f('a,b',','),s.split.$f('',','),
                    s.split.$f('ab',''),t.keys.$f(new Map([['a',true]])),t.keys.$f(new Map())]) {
                  if (rt.checkType('[string]',a)!==a) throw Error('identity');
                  let rejected=false;
                  try { rt.checkType('[unknown]',a); } catch(e) { rejected=e.code==='E8001'; }
                  if (!rejected) throw Error('persistent descriptor');
                }
                """);
            run(root, "luajit", "-e", """
                package.path='./?.lua;'..package.path
                local rt=require('deal.runtime'); local s=require('std.string'); local t=require('std.table')
                local ffi=require('ffi')
                assert(rt.check_type('unknown',string.char(0xed,0xa0,0x80))==string.char(0xed,0xa0,0x80))
                for _,bad in ipairs({function() end, coroutine.create(function() end), ffi.new('int',1), string.char(0xe2,0x82)}) do
                  local ok,e=pcall(rt.check_type,'unknown',bad)
                  assert(not ok and e.code=='E8001')
                end
                local json=require('deal.json')
                local instance=rt.class_plan_('@m/Node',{
                  {name='first',descriptor='boolean',optional=false},
                  {name='next',descriptor='unknown',optional=true},
                  {name='tail',descriptor='?string',optional=false}},{tail=rt.__NULL,first=true})
                assert(json.encode(instance)=='{"first":true,"tail":null}')
                instance.next=instance
                local ok,e=pcall(json.encode,instance)
                assert(not ok and e.code=='E8001')
                instance.next=nil
                setmetatable(instance,{__index=function() error('JSON invoked user hook') end})
                assert(json.encode(instance)=='{"first":true,"tail":null}')
                local fields={{name='first',descriptor='boolean',optional=false},
                  {name='tail',descriptor='?string',optional=false}}
                local decoded=json.decode('{"tail":null,"first":true}', '@m/Decoded', {['@m/Decoded']=fields})
                assert(decoded and json.encode(decoded)=='{"first":true,"tail":null}')
                local plan={{name='first',descriptor='boolean',optional=false},
                  {name='tail',descriptor='?string',optional=false}}
                local planned=json.decode('{"tail":null,"first":true}', '@m/Planned', {['@m/Planned']=plan})
                assert(planned and json.encode(planned)=='{"first":true,"tail":null}')
                assert(json.encode({a=instance,b=instance})==
                  '{"a":{"first":true,"tail":null},"b":{"first":true,"tail":null}}')
                local deep=instance
                for i=1,600 do deep=rt.class_plan_('@m/Node',{
                  {name='first',descriptor='boolean',optional=false},
                  {name='next',descriptor='unknown',optional=true},
                  {name='tail',descriptor='?string',optional=false}},{tail=rt.__NULL,first=true,next=deep}) end
                local _,count=string.gsub(json.encode(deep),'"first":true','')
                assert(count==601)
                assert(json.encode(rt.error_value('C','m'))=='{"name":"C","message":"m"}')
                assert(rt.check_type('unknown',s.split)==s.split)
                for _,a in ipairs({s.split.f('a,b',','),s.split.f('',','),
                    s.split.f('ab',''),t.keys.f({a=true}),t.keys.f({})}) do
                  assert(rt.check_type('[string]',a)==a)
                  local ok,e=pcall(rt.check_type,'[unknown]',a)
                  assert(not ok and e.code=='E8001')
                end
                """);
        } finally {
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
