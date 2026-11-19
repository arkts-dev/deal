package deal.test;

import deal.codegen.Backend;
import deal.semantic.ir.*;
import java.nio.file.*;
import java.util.*;

/** Result-only never: flow, exact signatures, completion rejection and catchability. */
public final class NeverProductionTest {
    private static final String SOURCE = """
        function reject():never{throw {name:"STOP",message:"reject"};}
        function text(x:unknown):string{if(typeof x!=="string"){reject();}return x;}
        function viaReturn():number{return reject();}
        function viaTail():number{reject();}
        function chained():never{reject();}
        export async function run():Promise<number>{
          try{await asyncReject();}catch(e){let error=e as Error;if(error.name!=="STOP"){throw error;}}
          return 17;
        }
        async function asyncReject():Promise<never>{throw {name:"STOP",message:"async"};}
        async function asyncTail():Promise<number>{await asyncReject();}
        export function main():void{
          if(text("ok")!=="ok"){throw {name:"TEST",message:"text"};}
          let destination=7;
          try{destination=viaReturn();}catch(e){let error=e as Error;if(error.name!=="STOP"){throw error;}}
          if(destination!==7){throw {name:"TEST",message:"rollback"};}
          try{viaTail();}catch(e){let error=e as Error;if(error.name!=="STOP"){throw error;}}
          try{chained();}catch(e){let error=e as Error;if(error.name!=="STOP"){throw error;}}
          let invoke=(optional:(()=>never)|undefined):void=>{let skipped:undefined=optional?.();};
          invoke(undefined);
          try{invoke(reject);}catch(e){let error=e as Error;if(error.name!=="STOP"){throw error;}}
          let adapted:(n:number)=>never=reject;
          try{adapted(1);}catch(e){let error=e as Error;if(error.name!=="STOP"){throw error;}}
          let funcs:(()=>never)[]=[reject];
          try{funcs[0]();}catch(e){let error=e as Error;if(error.name!=="STOP"){throw error;}}
          let arrow=():number=>reject();
          try{arrow();}catch(e){let error=e as Error;if(error.name!=="STOP"){throw error;}}
        }
        """;

    private static void frontend() {
        String prefix="function reject():never{throw {name:\"STOP\",message:\"reject\"};}";
        for(String body:List.of("let x=reject();", "let x:unknown=reject();", "let x=[reject()];",
                "let x:Record<string,unknown>={x:reject()};", "if(reject()){}", "let x=1+reject();",
                "let x=reject() as number;", "let x=true?reject():reject();", "throw reject();"))
            TypeLegalityFlowTest.check(prefix+"function f():void{"+body+"}","E3001");
        for(String source:List.of("function f(x:never):void{}", "function f():void{let x:never=1;}",
                "class C{x:never;}", "function f(x:never[]):void{}", "function f():never|null{throw {name:\"X\",message:\"x\"};}"))
            TypeLegalityFlowTest.check(source,"E3005");
        TypeLegalityFlowTest.check("function f():never{}","E5002");
        TypeLegalityFlowTest.check("function f():never{return;}","E5003");
        TypeLegalityFlowTest.check("function f():never{return 1;}","E5003");
        TypeLegalityFlowTest.check(prefix+"function f():number{try{reject();}catch(e){}}","E5002");
        TypeLegalityFlowTest.check(prefix+"function f():void{let g:()=>number=reject;}","E3001");
        TypeLegalityFlowTest.check(prefix+"function f():void{let g:()=>void=reject;}","E3001");
        TypeLegalityFlowTest.check(prefix+"function f():void{let x=[1].map(():never=>reject());}","E5001");
        TypeLegalityFlowTest.check(prefix+"function f():void{let x=Array.from([1],():never=>reject());}","E5001");
        TypeLegalityFlowTest.check(prefix+"class C{x:string|null;}function f(c:C):string{if(c.x===null){reject();}return c.x;}",null);
        TypeLegalityFlowTest.check(prefix+"function f(x:unknown):string{if(typeof x!==\"string\"){try{reject();}catch(e){}}return x;}","E5003");
        TypeLegalityFlowTest.check("async function reject():Promise<never>{throw {name:\"X\",message:\"x\"};}async function f(x:string|null):Promise<string>{try{await reject();}catch(e){}return x;}","E5003");
        TypeLegalityFlowTest.check(SOURCE,null);
        for(String descriptor:List.of("never", "never[]", "!never", "(never)->number")) {
            try { RuntimeDescriptor.parseCanonicalText(descriptor); throw new AssertionError(descriptor); }
            catch(SemanticIrTextDecodeException expected) { }
        }
        if(!RuntimeDescriptor.parseCanonicalText("()->never").canonicalSpecText().equals("()->never")) throw new AssertionError();
        for(FailurePolicyId boundary:List.of(FailurePolicyId.TYPE_DESCRIPTOR,FailurePolicyId.HOST_SYNC_RETURN,FailurePolicyId.ASYNC_COMPLETION)) {
            var result=BoundaryExecutor.check(boundary,RuntimeDescriptor.Never.INSTANCE,BoundaryValueView.of(ActualKind.UNDEFINED),BoundaryContext.none());
            if(!(result instanceof BoundaryOutcome.Fail fail) || !fail.failure().code().toString().equals("E8001"))
                throw new AssertionError(result);
        }
    }

    private static void oracle(String source, Path root) throws Exception {
        Path entry=Files.createDirectories(root.resolve("oracle/src")).resolve("main.deal");
        Files.writeString(entry,source.replace("export function main()","export function test()")+"\nexport function main():void{}\n");
        Files.writeString(root.resolve("oracle/deal.json"),"{\"languageVersion\":\"1.6\",\"stdlib\":\"1.6\",\"moduleRoots\":[\"src\"]}");
        var profile=deal.semantic.CompilerProfileProvider.resolve(deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
            deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
        var compiler=new deal.module.CompilationOrchestrator(entry,root.resolve("oracle/out"),false,false,false,false,Backend.JS,null,
            List.of(entry.getParent()),Path.of("").toAbsolutePath(),null,profile);
        if(!compiler.compile())throw new AssertionError(compiler.diagnostics());
        var project=compiler.producedProject();
        for (var unit : project.modules().values()) admission(unit, profile.capabilityRegistryHash());
        var tables=new LinkedHashMap<ModuleId,StructuredBodyTable>();
        var factories=new LinkedHashMap<ModuleId,ClassFactoryRegistry>();
        project.modules().forEach((id,unit)->{tables.put(id,unit.bodyTable());factories.put(id,unit.classFactories());});
        var result=deal.semantic.SemanticOracle.invokeSyncEntry(project,tables,factories,
            new deal.semantic.SemanticOracle.HostResponder(){},project.entryModule(),"test",List.of());
        if(!(result.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success))throw new AssertionError(result.comparisonReport());
        var async=deal.semantic.SemanticOracle.invokeAsyncEntry(project,tables,new deal.semantic.SemanticOracle.HostResponder(){},
            project.entryModule(),"run",List.of());
        if(!(async.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success))throw new AssertionError(async.comparisonReport());
    }

    private static void admission(LoweredModuleUnit unit, String registryHash) {
        var facts=new SemanticIrValidator.ComparisonFacts(unit.interfaceHash(),unit.semanticProfile(),registryHash);
        var raw=RawUnit.fromTyped(unit);
        if(SemanticIrValidator.validateText(SemanticIrValidator.toUnitText(unit),facts).isPresent())throw new AssertionError("valid never transport");
        RawOp subject=raw.ops().stream().filter(op->op.kind().equals("UNREACHABLE")).findFirst().orElseThrow();
        var payload=(CanonicalJson.Obj)ContractSnapshotCanonicalizer.payloadJson(new KindPayload.UnreachablePayload(new ValueId(999999)));
        var entries=subject.snapshot().entries().stream().map(e->e.key().equals("payload")?CanonicalJson.e("payload",payload):e).toList();
        var snapshot=CanonicalJson.obj(entries);
        var bad=new RawOp(subject.opId(),subject.kind(),subject.failurePolicy(),subject.parentOpId(),subject.resultValue(),subject.resultToken(),
            subject.resultType(),subject.operands(),subject.operandTypes(),payload,subject.selector(),
            ContractSnapshotCanonicalizer.recomputeSnapshotDigest(snapshot),snapshot,subject.origin());
        var changed=new RawUnit(raw.modulePath(),raw.semanticProfile(),raw.interfaceHash(),raw.loweringContextHash(),raw.requiredCapabilities(),
            raw.coverage(),raw.classLayouts(),raw.functions(),raw.moduleInit(),raw.exportPlan(),raw.bindings(),
            raw.ops().stream().map(op->op==subject?bad:op).toList(),raw.bodyTable(),raw.classFactories(),raw.classBindingFacts());
        var typed=SemanticIrValidator.validate(RawUnitMaterializer.materialize(changed),facts);
        var text=SemanticIrValidator.validateText(ContractSnapshotCanonicalizer.serializeText(ContractSnapshotCanonicalizer.toJson(changed)),facts);
        if(typed.isEmpty()||!typed.equals(text)||!typed.get().message().contains("UNREACHABLE requires"))throw new AssertionError(typed+" / "+text);
    }

    public static void main(String[] args)throws Exception {
        frontend();
        Path root=Files.createTempDirectory("deal-never-");
        try {
            oracle(SOURCE,root);
            CompositionProductionTest.execute(SOURCE,"",root,null,List.of(Backend.JS,Backend.LUAJIT,Backend.JVM,Backend.LLVM));
            Path host=Files.createDirectories(root.resolve("host"));
            Files.writeString(host.resolve("probe.d.deal"),"export function bad():never;export function nothing():never;export function badAsync():Promise<never>;");
            Files.writeString(host.resolve("probe.js"),"module.exports={bad:()=>42,nothing:()=>{},badAsync:()=>Promise.resolve(42)};");
            Files.writeString(host.resolve("probe.lua"),"local rt=require('deal.runtime');return {bad=function() return 42 end,nothing=function() end,badAsync=function() return rt.async_start(function() return 42 end) end}");
            Files.writeString(host.resolve("probe.java"),"final class HostProbe{public static Object bad(){return 42.0;}public static void nothing(){}public static Object badAsync(){return java.util.concurrent.CompletableFuture.completedFuture(42.0);}}");
            Files.writeString(host.resolve("probe.c"),"""
                #include "host/probe.h"
                #include "native_helpers.h"
                static deal_host_result bad(const deal_value *args,size_t argc){(void)args;(void)argc;return result(number(42));}
                static deal_host_result nothing(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value v;checked(deal_nothing(&v));return result(v);}
                static deal_host_result bad_async(deal_async_token token,const deal_value *args,size_t argc){(void)args;(void)argc;return complete(token,number(42));}
                const deal_host_export deal_host_exports_probe[]={
                  {"bad","()->never",DEAL_HOST_EXPORT_FUNCTION,0,bad,NULL,NULL},
                  {"nothing","()->never",DEAL_HOST_EXPORT_FUNCTION,0,nothing,NULL,NULL},
                  {"badAsync","async()->never",DEAL_HOST_EXPORT_ASYNC_FUNCTION,1,NULL,bad_async,NULL}};
                const deal_host_module deal_host_module_probe={DEAL_HOST_SPECIFIER_PROBE,deal_host_exports_probe,3u,NULL,0u,NULL};
                """);
            CompositionProductionTest.execute("""
                import * as host from "host/probe";
                export async function run():Promise<number>{
                  try{await host.badAsync();}catch(e){let error=e as Error;if(error.name!=="E8001"){throw error;}return 19;}
                }
                export function main():void{
                  try{host.bad();}catch(e){let error=e as Error;if(error.name!=="E8001"||error.message!=="expected never"){throw error;}}
                  try{host.nothing();}catch(e){let error=e as Error;if(error.name!=="E8001"||error.message!=="expected never"){throw error;}}
                  let fields:Record<string,unknown>={f:host.bad};let dynamic=fields.f as ()=>never;
                  try{dynamic();}catch(e){let error=e as Error;if(error.name!=="E8001"||error.message!=="expected never"){throw error;}}
                  let adapted:(n:number)=>never=host.bad;
                  try{adapted(1);}catch(e){let error=e as Error;if(error.name!=="E8001"||error.message!=="expected never"){throw error;}}
                }
                ""","",root.resolve("host-completion"),host,List.of(Backend.JS,Backend.LUAJIT,Backend.JVM,Backend.LLVM));
        } finally {CompositionProductionTest.remove(root);}
        System.out.println("NeverProductionTest: passed");
    }
}
