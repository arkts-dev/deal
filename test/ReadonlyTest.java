package deal.test;

import deal.codegen.Backend;
import java.nio.file.Files;
import java.util.List;

/** Read authority, shallow aliases, storage invariance and checked recovery. */
public final class ReadonlyTest {
    public static void main(String[] args) throws Exception {
        String[] accepted = {
            "function f(a:number[]):void{let v:readonly unknown[]=a;}",
            "function f(a:number[]|null):void{let v:readonly number[]|null=a??[];let n=v.length;}",
            "function f(a:number[]):void{let v:readonly number[]|null=a;v=null;let n:null=v;}",
            "function f(a:number[],b:boolean):void{let v:readonly unknown[]|null=b?a:[];let n=v.length;}",
            "function f():void{let a:readonly number[]=[];let b=a.slice();b.push(1);}",
            "function f():void{let a:readonly number[][]=[[1]];a[0].push(2);}",
            "class C{readonly items:number[];}function f(c:C):void{c.items.push(1);}",
            "class C{items:readonly number[];}function f(c:C):void{c.items=[1];}",
            "class C{readonly item?:number;}function f():void{let c:C={};}",
            "class readonly{}function f(a:readonly[]):void{}",
            "class readonly{}function f(a:readonly):readonly{return a;}",
            "function f(a:number[]):void{let v:readonly unknown[]|null=null;v=a;let n=v.length;}",
            "function f(a:readonly number[]):void{a.map((v:number,i:number,xs:readonly number[]):number=>v);}",
            "function f(a:readonly number[]):void{let b=a.filter((v:number):boolean=>true);b.push(2);}",
            "function f(a:readonly number[]):void{let b=a.concat([1]);b.push(2);}"
        };
        for (String source : accepted) TypeLegalityFlowTest.check(source, null);
        String[] rejected = {
            "function f(a:readonly number[]):void{a[0]=1;}",
            "function f(a:readonly number[]):void{a.push(1);}",
            "function f(a:readonly number[]):void{a.pop();}",
            "function f(a:readonly number[]):void{a.splice(0,1);}",
            "function f(a:readonly number[]):void{a[0]++;}",
            "function f(a:readonly number[]):void{a[0]+=1;}",
            "class C{readonly item:number;}function f(c:C):void{c.item=1;}"
        };
        for (String source : rejected) TypeLegalityFlowTest.check(source, "E3017");
        TypeLegalityFlowTest.check("class C{readonly item?:number;}function f(c:C):void{delete c.item;}", "E3017");
        TypeLegalityFlowTest.check("function f(a:number[]):void{let v:unknown[]=a;}", "E3001");
        TypeLegalityFlowTest.check("function f(a:readonly number[]):void{let v:number[]=a;}", "E3001");
        TypeLegalityFlowTest.check("function f(a:(()=>number)[]):void{let v:readonly ((x:number)=>number)[]=a;}", "E3001");
        TypeLegalityFlowTest.check("function f(a:number[]|null):void{let v:readonly number[]=a;}", "E3001");
        TypeLegalityFlowTest.check("function f(a:number[]|undefined):void{let v:readonly number[]|null=a;}", "E3001");
        descriptorCompatibility();
        nativeViewAdmission();
        matcherStorage();
        var root = Files.createTempDirectory("deal-readonly-");
        try {
            String source = """
                class Box { readonly items: number[]; itemsView: readonly number[]; }
                export function main(): void {
                  let source: string[] = ["one", "two"];
                  let view: readonly unknown[] = source;
                  let dynamic: unknown = view;
                  let checked = dynamic as readonly unknown[];
                  let copy = Array.from(checked, (item: unknown): string => item as string);
                  source.push("three");
                  if (view.length !== 3 || copy.length !== 2) { throw {name:"FAIL",message:"alias/copy"}; }
                  let recovered = dynamic as string[];
                  recovered.push("four");
                  let bad = false;
                  try { let wrong = dynamic as unknown[]; } catch (error) { bad = true; }
                  if (!bad || source.length !== 4) { throw {name:"FAIL",message:"invariance"}; }
                  let nested: readonly number[][] = [[1]];
                  nested[0].push(2);
                  let box: Box = {items:[1], itemsView:[2]};
                  box.items.push(3);
                  box.itemsView = [4];
                  if (nested[0].length !== 2 || box.items.length !== 2) { throw {name:"FAIL",message:"shallow"}; }
                  let json: readonly number[] = JSON.parse("[5,6]");
                  if (json[1] !== 6 || (JSON.stringify(json) as string) !== "[5,6]") { throw {name:"FAIL",message:"json"}; }
                  let sorted = (json as readonly number[]).toSorted((a:number,b:number):number=>a-b);
                  sorted.push(7);
                  let sliced = json.slice(0,1); sliced.push(8);
                  let concatenated = json.concat(sliced); concatenated.push(9);
                  if (concatenated.length !== 5) { throw {name:"FAIL",message:"slice/concat"}; }
                  let mapped = json.map((v:number,i:number,a:readonly number[]):number=>v+i);
                  if (mapped[1] !== 7 || sorted.length !== 3) { throw {name:"FAIL",message:"copy intrinsics"}; }
                  let broad: readonly unknown[] = source;
                  if ((broad[0] as string) !== "one") { throw {name:"FAIL",message:"widened index"}; }
                  let numeric=[1,2];let optional:readonly (number|null)[]=numeric;
                  if ((optional[0] as number) !== 1 || (optional.slice()[1] as number) !== 2) { throw {name:"FAIL",message:"widened numeric"}; }
                  let combined = broad.concat([12]);
                  combined.push(false);
                  if (combined.length !== 6 || (combined[4] as number) !== 12 || (combined[5] as boolean) !== false) { throw {name:"FAIL",message:"widened concat"}; }
                  let broadSlice = broad.slice(1,2); broadSlice.push(false);
                  if ((broadSlice[0] as string) !== "two" || (broadSlice[1] as boolean) !== false) { throw {name:"FAIL",message:"widened slice"}; }
                  let absent:undefined[]=[undefined];
                  let union:readonly (null|undefined)[]=absent;
                  let unionDynamic:unknown=absent;
                  let unionChecked=unionDynamic as readonly (null|undefined)[];
                  if(union[0]!==undefined || unionChecked[0]!==undefined){throw {name:"FAIL",message:"null union covariance"};}
                  let nulls: (number|null|undefined)[] = [1,null,undefined];
                  let nullView: readonly unknown[] = nulls;
                  let nullCopy = Array.from(nullView,(item:unknown):unknown=>item);
                  if (nullCopy[1] !== null || nullCopy[2] !== undefined) { throw {name:"FAIL",message:"nullish elements"}; }
                  let fieldJson: Box = JSON.parse("{\\"items\\":[1],\\"itemsView\\":[2]}");
                  fieldJson.items.push(3);
                  if ((JSON.stringify(fieldJson) as string) !== "{\\"items\\":[1,3],\\"itemsView\\":[2]}") { throw {name:"FAIL",message:"field json"}; }
                  console.log(copy.join(","));
                }
                """;
            oracleAndTransport(root.resolve("oracle"), source);
            CompositionProductionTest.execute(source, "one,two\n", root, null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            hostAccess(root.resolve("host-access"));
            readonlyFieldReads(root.resolve("field-reads"));
        } finally { CompositionProductionTest.remove(root); }
        System.out.println("Readonly: frontend and production backends passed");
    }

    private static void nativeViewAdmission() {
        for (String[] pair : new String[][] {
                {"[number]", "readonly[number]"},
                {"[number]", "?readonly[number]"},
                {"[number]", "!readonly[unknown]"},
                {"?[number]", "?readonly[unknown]"},
                {"[[number]]", "readonly[readonly[unknown]]"}}) {
            var actual = deal.semantic.ir.RuntimeDescriptor.parseCanonicalText(pair[0]);
            var target = deal.semantic.ir.RuntimeDescriptor.parseCanonicalText(pair[1]);
            if (!deal.codegen.llvm.LlvmTypeMapper.admits(target, actual)
                    || !deal.codegen.llvm.LlvmTypeMapper.convertible(target, actual))
                throw new AssertionError("native readonly admission: " + java.util.Arrays.toString(pair));
        }
        for (String[] pair : new String[][] {
                {"[number]", "[unknown]"},
                {"readonly[number]", "?[number]"},
                {"?[number]", "!readonly[number]"},
                {"[()->number]", "readonly[(number)->number]"}}) {
            var actual = deal.semantic.ir.RuntimeDescriptor.parseCanonicalText(pair[0]);
            var target = deal.semantic.ir.RuntimeDescriptor.parseCanonicalText(pair[1]);
            if (deal.codegen.llvm.LlvmTypeMapper.admits(target, actual)
                    || deal.codegen.llvm.LlvmTypeMapper.convertible(target, actual))
                throw new AssertionError("native invalid view admission: " + java.util.Arrays.toString(pair));
        }
    }

    static void readonlyFieldReads(java.nio.file.Path root) throws Exception {
        String minimal = """
            class C { values: readonly number[]; }
            export function main(): void {
              let c: C = { values: [1] };
              if (c.values.length !== 1) {
                throw { name: "FAIL", message: "field" };
              }
            }
            """;
        var backends = List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM);
        oracleAndTransport(root.resolve("minimal-oracle"), minimal);
        CompositionProductionTest.execute(minimal, "", root.resolve("minimal"), null, backends);
        String source = """
            import * as model from "./model";
            class Fields {
              values: readonly number[];
              optional?: readonly number[];
              nullable: readonly number[] | null;
              nested: readonly (readonly number[])[];
              callbacks: (() => readonly number[])[];
            }
            export function main(): void {
              let storage = [1];
              let c:Fields={values:storage,nullable:null,nested:[storage],
                callbacks:[():readonly number[]=>storage]};
              if(c.values.length!==1 || c.optional!==undefined || c.nullable!==null){
                throw {name:"FAIL",message:"required/optional/nullish read"};
              }
              storage.push(2);
              if(c.values[1]!==2 || c.nested[0][1]!==2 || c.callbacks[0]()[1]!==2){
                throw {name:"FAIL",message:"nested/function descriptors and live aliases"};
              }
              c.optional=storage;c.nullable=storage;
              let optional=c.optional;let nullable=c.nullable;
              if(optional===undefined || nullable===null){throw {name:"FAIL",message:"present"};}
              if(optional[1]!==2 || nullable[1]!==2){throw {name:"FAIL",message:"wrapped views"};}
              delete c.optional;
              let imported:model.Imported={values:storage};
              if(model.read(imported)!==2 || imported.values[1]!==2){
                throw {name:"FAIL",message:"imported provider"};
              }
              let decoded:model.Imported=JSON.parse(`{"values":[3]}`);
              if(decoded.values[0]!==3 || (JSON.stringify(decoded)??"")!==`{"values":[3]}`){
                throw {name:"FAIL",message:"JSON readonly field"};
              }
              let copy=c.values.slice();copy.push(9);
              if(copy.length!==3 || storage.length!==2){throw {name:"FAIL",message:"mutable copy"};}
            }
            """;
        var modules = java.util.Map.of("model.deal", """
            export class Imported { readonly values: readonly number[]; }
            export function read(value:Imported):number {return value.values.length;}
            """);
        oracleAndTransport(root.resolve("oracle"), source, modules, java.util.Map.of(
            "Fields.values", "readonly[number]", "Fields.optional", "readonly[number]",
            "Fields.nullable", "?readonly[number]", "Fields.nested", "readonly[readonly[number]]",
            "Fields.callbacks", "[()->readonly[number]]", "Imported.values", "readonly[number]"));
        var production = root.resolve("production");
        CompositionProductionTest.execute(source, "", "", production, null, backends, modules);
        for (int level : new int[]{0,2}) {
            var product=root.resolve("sanitized-o"+level);
            NativeCompiler.build(new deal.toolchain.NativeBuildRequest(production.resolve("LLVM/out"),
                product,level,List.of(),null),NativeCompiler.SANITIZER_FLAGS);
            for(boolean stress:new boolean[]{false,true}) {
                var environment=new java.util.HashMap<>(NativeCompiler.sanitizerEnvironment());
                environment.put("DEAL_GC_STRESS",stress?"1":"0");
                System.out.println("Readonly fields LLVM O"+level+" sanitized=true stress="+stress);
                var result=NativeCompiler.runSanitized(product,root,environment).result();
                if(result.exitCode()!=0 || !result.stdout().isEmpty() || !result.stderr().isEmpty())
                    throw new AssertionError("readonly fields O"+level+" stress="+stress+": "+result.describe());
            }
        }
    }

    private static void matcherStorage() {
        var model = new RuntimeTypeMatcherTest.TestModel() {
            @Override public String arrayElementDescriptor(Object value) { return "number"; }
        };
        var matcher = new deal.descriptors.RuntimeTypeMatcher(model);
        var value = new RuntimeTypeMatcherTest.ArrayValue(1.0);
        var location = new deal.descriptors.RuntimeSourceLocation("readonly.deal", 1, 1);
        if (matcher.check("readonly[unknown]", value, location) != value
                || !(matcher.check("[unknown]", value, location) instanceof deal.descriptors.RuntimeCheckFailure))
            throw new AssertionError("matcher storage compatibility");
        value.elements[0] = "corrupt";
        if (!(matcher.check("readonly[unknown]", value, location) instanceof deal.descriptors.RuntimeCheckFailure failure)
                || !failure.code().equals("E8003")) throw new AssertionError("matcher stored element check");
        value.elements[0] = 2.0;
        if (matcher.check("[number]", value, location) != value) throw new AssertionError("matcher repair");
    }

    private static void descriptorCompatibility() {
        String[][] accepted={{"[string]","readonly[unknown]"},
            {"[number]","readonly[?number]"},{"[?!number]","readonly[unknown]"},
            {"[undefined]","readonly[null|undefined]"},{"[null]","readonly[null|undefined]"},
            {"[[number]]","readonly[readonly[unknown]]"},
            {"?readonly[number]","?readonly[unknown]"}};
        String[][] rejected={{"[string]","[unknown]"},
            {"readonly[number]","[number]"},{"?[number]","readonly[number]"},
            {"![number]","?readonly[number]"},
            {"[()->number]","readonly[(number)->number]"}};
        for (String[][] matrix : List.of(accepted,rejected)) {
            boolean expected=matrix==accepted;
            for(String[] pair:matrix){
                for(String text:pair){
                    var parsed=deal.descriptors.CanonicalRuntimeTypeDescriptor.parse(text);
                    if(!(parsed instanceof deal.descriptors.DescriptorAst ast)
                        || !deal.descriptors.CanonicalRuntimeTypeDescriptor.render(ast).equals(text))
                        throw new AssertionError("descriptor roundtrip: "+text);
                }
                boolean actual=deal.semantic.ir.RuntimeDescriptor.assignable(
                    deal.semantic.ir.RuntimeDescriptor.parseCanonicalText(pair[0]),
                    deal.semantic.ir.RuntimeDescriptor.parseCanonicalText(pair[1]));
                if(actual!=expected)throw new AssertionError(java.util.Arrays.toString(pair));
            }
        }
    }

    private static void oracleAndTransport(java.nio.file.Path root, String source) throws Exception {
        oracleAndTransport(root,source,java.util.Map.of(),java.util.Map.of());
    }

    private static void oracleAndTransport(java.nio.file.Path root, String source,
            java.util.Map<String,String> modules, java.util.Map<String,String> expectedFields) throws Exception {
        Files.createDirectories(root);
        for(var module:modules.entrySet()) Files.writeString(root.resolve(module.getKey()),module.getValue());
        var entry = root.resolve("main.deal");
        Files.writeString(entry, source.replace("export function main(): void {",
            "export function main():void{run();} export function run(): void {"));
        var invocation = deal.semantic.CompilerProfileProvider.resolve(
            deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
            deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
        var compiler = new deal.module.CompilationOrchestrator(entry, root.resolve("out"),
            false,false,false,false,Backend.JS,null,List.of(root),null,null,invocation);
        if (!compiler.compile()) throw new AssertionError(compiler.diagnostics());
        var project = compiler.producedProject();
        var observedFields=new java.util.HashMap<String,String>();
        for(var unit:project.modules().values()) for(var layout:unit.classLayouts().values())
            for(var field:layout.fields()) observedFields.put(layout.classId().name()+"."+field.name(),
                field.descriptor().canonicalSpecText());
        for(var field:expectedFields.entrySet()) if(!field.getValue().equals(observedFields.get(field.getKey())))
            throw new AssertionError("declared readonly field layout: "+field+" observed="+observedFields);
        var facts = new deal.semantic.ir.SemanticIrValidator.ComparisonFacts(
            project.interfaceIndex().interfaceIndexDigest(),project.semanticProfile(),invocation.capabilityRegistryHash());
        var typed = deal.semantic.ir.SemanticIrValidator.validate(project,facts);
        var text = deal.semantic.ir.SemanticIrValidator.validateText(
            deal.semantic.ir.SemanticIrValidator.toProjectText(project),facts);
        if (typed.isPresent() || text.isPresent()) throw new AssertionError("readonly transport: "+typed+" / "+text);
        var tables = new java.util.LinkedHashMap<deal.semantic.ir.ModuleId,deal.semantic.ir.StructuredBodyTable>();
        var registries = new java.util.LinkedHashMap<deal.semantic.ir.ModuleId,deal.semantic.ir.ClassFactoryRegistry>();
        project.modules().forEach((id,unit)->{
            tables.put(id,unit.bodyTable());registries.put(id,unit.classFactories());
            var rebuilt=deal.semantic.ir.RawUnitMaterializer.materialize(deal.semantic.ir.RawUnit.fromTyped(unit));
            if(!deal.semantic.ir.SemanticIrValidator.toUnitText(unit).equals(deal.semantic.ir.SemanticIrValidator.toUnitText(rebuilt)))
                throw new AssertionError("readonly raw roundtrip");
        });
        var result = deal.semantic.SemanticOracle.invokeSyncEntry(project,tables,registries,null,
            compiler.checkedProject().input().entryModule(),"run",List.of());
        if (!(result.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success))
            throw new AssertionError(result.comparisonReport());
    }

    private static void hostAccess(java.nio.file.Path root) throws Exception {
        var host = Files.createDirectories(root.resolve("host"));
        Files.writeString(host.resolve("probe.d.deal"),
            "export function exercise(view:readonly unknown[],write:number[],nested:readonly (readonly number[])[],box:unknown):void;");
        Files.writeString(host.resolve("probe.js"), """
            exports.exercise=(view,write,nested,box)=>{
              if(view!==write || nested[0]!==view)throw Error("alias identity");
              view[0]=3;
              Object.getOwnPropertyDescriptor(nested,"0").value[0]=4;
              view.push(9);view.length=2;view.splice(0,0);view.sort();view.fill(0,0,0);
              Object.defineProperty(view,"0",{value:5,writable:true,configurable:true,enumerable:true});
              write[0]=7;if(view[0]!==7 || nested[0][0]!==7)throw Error("alias detached");
              box.item=8;box.optional=4;delete box.optional;box.next=box;
              box.items=write;
            };
            """);
        Files.writeString(host.resolve("probe.lua"), """
            return {exercise=function(view,write,nested,box)
              if view~=write or nested[1]~=view then error("alias identity") end
              view[1]=3;nested[1][1]=4
              write[1]=7
              if view[1]~=7 or nested[1][1]~=7 then error("alias detached") end
              box.item=8;box.optional=4;box.optional=nil;box.next=box
              box.items=write
            end}
            """);
        Files.writeString(host.resolve("probe.java"), """
            final class HostProbe {
              public static void exercise(deal.codegen.jvm.JvmRuntime.Array view,$DealRt.__NumberArray write,deal.codegen.jvm.JvmRuntime.Array nested,Object value){
                deal.codegen.jvm.JvmRuntime.Array child=(deal.codegen.jvm.JvmRuntime.Array)nested.elements.get(0);
                if(child!=view)throw new AssertionError("alias identity");
                view.elements.set(0,3.0);child.elements.set(0,4.0);write.data[0]=7;
                if(((Number)view.elements.get(0)).doubleValue()!=7 || ((Number)child.elements.get(0)).doubleValue()!=7)throw new AssertionError("alias detached");
                deal.codegen.jvm.JvmRuntime.ClassInstance box=(deal.codegen.jvm.JvmRuntime.ClassInstance)value;
                box.write("item",8.0);box.write("optional",4.0);box.delete("optional");box.write("next",box);
                deal.codegen.jvm.JvmRuntime.Array items=new deal.codegen.jvm.JvmRuntime.Array(0);
                items.elementDescriptor="number";items.elements.add(7.0);items.length=1;box.write("items",items);
              }
            }
            """);
        Files.writeString(host.resolve("probe.c"), """
            #include "host/probe.h"
            #include "native_helpers.h"
            static deal_host_result exercise(const deal_value *args,size_t count){
              (void)count;
              deal_value child;checked(deal_array_get(args[2],0,&child));
              checked(deal_array_set(child,0,number(3)));
              checked(deal_array_set(args[0],0,number(4)));
              deal_handle handle;deal_value retained;
              checked(deal_retain(args[0],&handle));
              checked(deal_handle_value(handle,NULL,&retained));
              checked(deal_array_set(retained,0,number(5)));
              checked(deal_release(handle));
              checked(deal_array_set(args[1],0,number(7)));
              deal_value observed;double value;
              checked(deal_array_get(args[0],0,&observed));checked(deal_as_number(observed,&value));
              if(value!=7)abort();
              checked(deal_field_set(args[3],"item",number(8)));
              checked(deal_field_set(args[3],"optional",number(4)));
              checked(deal_field_delete(args[3],"optional"));
              checked(deal_field_set(args[3],"next",args[3]));
              deal_value items;checked(deal_array_new_typed(1,"number",&items));
              checked(deal_array_set(items,0,number(7)));
              checked(deal_field_set(args[3],"items",items));
              return result(undefined_value());
            }
            const deal_host_export deal_host_exports_probe[]={
              {"exercise","(readonly[unknown],[number],readonly[readonly[number]],unknown)->void",DEAL_HOST_EXPORT_FUNCTION,0,exercise,NULL,NULL}
            };
            const deal_host_module deal_host_module_probe={DEAL_HOST_SPECIFIER_PROBE,deal_host_exports_probe,1u,NULL,0u,NULL};
            """);
        CompositionProductionTest.execute("""
            import * as host from "host/probe";
            class Box{readonly item:number;readonly optional?:number;readonly items:number[];next:Box|null;}
            export function main():void{
              let box:Box={item:1,items:[1],next:null};
              let source=[1,2];host.exercise(source,source,[source],box);
              if(source[0]!==7 || source.length!==2){throw {name:"FAIL",message:"host arrays"};}
              if(box.item!==8 || Object.hasOwn(box,"optional") || (box.next as Box)!==box || box.items[0]!==7){
                throw {name:"FAIL",message:"host fields"};
              }
            }
            """, "", root.resolve("production"), host,
            List.of(Backend.JS,Backend.LUAJIT,Backend.JVM,Backend.LLVM));
    }
}
