package deal.test;

import deal.codegen.Backend;
import java.nio.file.*;
import java.util.List;

/** Stable sorting is one expanded shared algorithm on every backend. */
public final class ArrayToSortedTest {
    public static void main(String[] args) throws Exception {
        TypeLegalityFlowTest.check("function f():void{let a=[1];a.toSorted((x,y):number=>x-y);}",null);
        TypeLegalityFlowTest.check("function f():void{let a=[1];a.toSorted();}","E5001");
        TypeLegalityFlowTest.check("function f():void{let a=[1];a.toSorted((x,y):boolean=>true);}","E5001");
        TypeLegalityFlowTest.check("function f():void{let a=[1];a.toSorted(async(x,y):Promise<number>=>1);}","E5001");
        TypeLegalityFlowTest.check("function f():void{let a=[1];let c=a.toSorted;}","E3001");
        TypeLegalityFlowTest.check("function f():void{let a=[1];a.toSorted((x,y):number=>x-y,0);}","E5001");
        TypeLegalityFlowTest.check("function f():void{let a=[1];a.toSorted((x:string,y:string):number=>0);}","E5001");
        TypeLegalityFlowTest.check("function f():void{let a=[1];a.toSorted((x,y)=>x-y);}","E1015");
        Path root=Files.createTempDirectory("deal-sorted-");
        var corpus=Path.of("test/conformance").toAbsolutePath();
        try {
            for (String name:List.of("to-sorted", "to-sorted-live-retry")) {
                String path="backend-runtime/arrays/"+name+".deal";
                String source=ConformanceHarnessMetadata.productionCorpusSource(Files.readString(corpus.resolve(path)),path);
                Path host=null;
                if(name.endsWith("live-retry")) {
                    host=Files.createDirectories(root.resolve("host"));
                    for(String extension:List.of("d.deal","js","lua","java","c"))
                        Files.copy(corpus.resolve("host-fixtures/array_pop."+extension),host.resolve("probe."+extension));
                }
                CompositionProductionTest.execute(source,"",root.resolve(name),host,List.of(Backend.JS,Backend.LUAJIT,Backend.JVM,Backend.LLVM));
                var fixture=CorpusConformanceTest.nativeCorpusFixtures(corpus).stream().filter(f->f.corpusPath().equals(path)).findFirst().orElseThrow();
                LlvmConformanceTest.runInstrumented(corpus,fixture);
            }
            oracleAndSparse(root);
        } finally { CompositionProductionTest.remove(root); }
        System.out.println("toSorted: frontend and all production backends passed");
    }

    private static void oracleAndSparse(Path root) throws Exception {
        Path work=Files.createDirectories(root.resolve("sparse"));
        Path src=Files.createDirectories(work.resolve("src"));
        Path declaration=src.resolve("probe.d.deal");
        Files.writeString(declaration,"export function keep(xs:unknown):void;export function hole():void;");
        Path source=src.resolve("main.deal");
        Files.writeString(source,"""
            import * as host from "host/probe";
            function check(ok:boolean):void{if(!ok){throw {name:"SPARSE",message:"sort"};}}
            export function main():void{run();}
            export function run():void{
              let xs:(number|undefined)[]=[3,2,1];host.keep(xs);host.hole();let calls=0;
              let ys=xs.toSorted((a,b):number=>{calls++;return (a as number)-(b as number);});
              check(ys.length===3 && (ys[0] as number)===1 && (ys[1] as number)===3 && ys[2]===undefined && calls===1);
              let nums=[3,2,1];host.keep(nums);host.hole();let old=[7];let destination=old;let caught=false;
              try{destination=nums.toSorted((a,b):number=>a-b);}
              catch(e){if((e as Error).name!=="E8001"){throw (e as Error);}caught=true;}
              check(caught && destination===old);
            }
            """);
        Files.writeString(work.resolve("deal.json"),"{\"languageVersion\":\"1.6\",\"stdlib\":\"1.6\",\"moduleRoots\":[\"src\"]}");
        var invocation=deal.semantic.CompilerProfileProvider.resolve(deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
            deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
        var compiler=new deal.module.CompilationOrchestrator(source,work.resolve("out"),false,false,false,false,Backend.JS,
            java.util.Map.of("host/probe",declaration.toString()),List.of(src),null,null,invocation);
        if(!compiler.compile())throw new AssertionError(compiler.diagnostics());
        var project=compiler.producedProject();
        var facts=new deal.semantic.ir.SemanticIrValidator.ComparisonFacts(project.interfaceIndex().interfaceIndexDigest(),
            project.semanticProfile(),invocation.capabilityRegistryHash());
        if(deal.semantic.ir.SemanticIrValidator.validate(project,facts).isPresent()
                ||deal.semantic.ir.SemanticIrValidator.validateText(deal.semantic.ir.SemanticIrValidator.toProjectText(project),facts).isPresent())
            throw new AssertionError("sort transport validation: typed="
                + deal.semantic.ir.SemanticIrValidator.validate(project,facts) + "; text="
                + deal.semantic.ir.SemanticIrValidator.validateText(deal.semantic.ir.SemanticIrValidator.toProjectText(project),facts));
        var tables=new java.util.LinkedHashMap<deal.semantic.ir.ModuleId,deal.semantic.ir.StructuredBodyTable>();
        var registries=new java.util.LinkedHashMap<deal.semantic.ir.ModuleId,deal.semantic.ir.ClassFactoryRegistry>();
        project.modules().forEach((id,unit)->{
            tables.put(id,unit.bodyTable());registries.put(id,unit.classFactories());
            var rebuilt=deal.semantic.ir.RawUnitMaterializer.materialize(deal.semantic.ir.RawUnit.fromTyped(unit));
            if(!deal.semantic.ir.SemanticIrValidator.toUnitText(unit).equals(deal.semantic.ir.SemanticIrValidator.toUnitText(rebuilt)))
                throw new AssertionError("sort typed/raw roundtrip");
        });
        var responder=new deal.semantic.SemanticOracle.HostResponder(){
            deal.semantic.SemanticOracle.Value.ArrayValue saved;
            public SyncOutcome call(deal.semantic.ir.ModuleId module,String export,deal.semantic.ir.RuntimeDescriptor.Func signature,
                    List<deal.semantic.SemanticOracle.Value> args){
                if(export.equals("keep"))saved=(deal.semantic.SemanticOracle.Value.ArrayValue)args.getFirst();
                else if(export.equals("hole"))saved.elements().set(1,deal.semantic.SemanticOracle.Value.MissingValue.INSTANCE);
                else throw new AssertionError(export);
                return new SyncOutcome.Returned(deal.semantic.SemanticOracle.Value.UndefinedValue.INSTANCE);
            }
        };
        var result=deal.semantic.SemanticOracle.invokeSyncEntry(project,tables,registries,responder,
            compiler.checkedProject().input().entryModule(),"run",List.of());
        if(!(result.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success))
            throw new AssertionError(result.comparisonReport()+"\n"+result.trace().stream()
                .filter(e->e.phase()==deal.semantic.SemanticRuntimeModel.Phase.FAILURE).toList());
        Path host=Files.createDirectories(work.resolve("host"));
        Files.copy(declaration,host.resolve("probe.d.deal"));
        Files.writeString(host.resolve("probe.js"),"const rt=require('../deal/runtime.js');let saved;exports.keep=x=>{saved=rt.reverseHostGraph(x);};exports.hole=()=>{delete saved[1];};");
        Files.writeString(host.resolve("probe.lua"),"local saved;return {keep=function(x) saved=x end,hole=function() rawset(saved,2,nil) end}");
        CompositionProductionTest.execute(Files.readString(source),"",work.resolve("production"),host,List.of(Backend.JS,Backend.LUAJIT));
    }
}
