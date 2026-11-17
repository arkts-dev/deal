package deal.test;

import deal.codegen.Backend;
import deal.semantic.*;
import deal.semantic.ir.*;
import java.nio.file.*;
import java.util.*;

/** Nullish selection: shared proof admission, Oracle transport and production execution. */
public final class NullishCoalescingTest {
    private static final String PATH = "backend-runtime/control-flow/nullish-coalescing.deal";
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("deal-coalescing-");
        try {
            String source = ConformanceHarnessMetadata.productionCorpusSource(
                Files.readString(Path.of("test/conformance/" + PATH)), PATH);
            Path input = Files.createDirectories(root.resolve("oracle/src")).resolve("main.deal");
            Files.writeString(input, source.replace("export function main():void", "export function run():void")
                + "\nexport function main():void{return ;}\nexport function oracleFailure():number{return badSelection();}\n");
            Files.writeString(input.getParent().getParent().resolve("deal.json"),
                "{\"languageVersion\":\"1.6\",\"stdlib\":\"1.6\",\"moduleRoots\":[\"src\"]}");
            var invocation = CompilerProfileProvider.resolve(ReleaseConfiguration.CURRENT_RELEASE_STATE,
                ReleaseConfiguration.releaseCapabilityRegistry());
            var compiler = new deal.module.CompilationOrchestrator(input, root.resolve("oracle/out"),
                false, false, false, false, Backend.JS, Map.of(), List.of(input.getParent()), null, null, invocation);
            if (!compiler.compile()) throw new AssertionError(compiler.diagnostics());
            var project = Objects.requireNonNull(compiler.producedProject());
            var facts = new SemanticIrValidator.ComparisonFacts(project.interfaceIndex().interfaceIndexDigest(),
                project.semanticProfile(), invocation.capabilityRegistryHash());
            if (SemanticIrValidator.validate(project, facts).isPresent()) throw new AssertionError("typed project admission");
            var parsed = ContractSnapshotCanonicalizer.parseProject((CanonicalJson.Obj)CanonicalJson.parse(SemanticIrValidator.toProjectText(project)));
            var modules = new LinkedHashMap<ModuleId,LoweredModuleUnit>();
            parsed.modules().forEach(raw->{var unit=RawUnitMaterializer.materialize(raw);modules.put(unit.moduleId(),unit);});
            var transported = new ExecutableLoweredProject(project.semanticProfile(),parsed.interfaceIndex(),modules,
                project.entryModule(),parsed.classSeeds());
            if (SemanticIrValidator.validate(transported, facts).isPresent()) throw new AssertionError("text project admission");
            SemanticRuntimeModel.ErrorSnapshot expectedFailure=null;
            for (var executable : List.of(project, transported)) {
                var tables = new LinkedHashMap<ModuleId, StructuredBodyTable>();
                var registries = new LinkedHashMap<ModuleId, ClassFactoryRegistry>();
                executable.modules().forEach((id, unit) -> {tables.put(id, unit.bodyTable());registries.put(id, unit.classFactories());});
                var result = SemanticOracle.invokeSyncEntry(executable, tables, registries,
                    new SemanticOracle.HostResponder(){}, compiler.checkedProject().input().entryModule(), "run", List.of());
                if (!(result.terminal() instanceof SemanticRuntimeModel.Terminal.Success success)
                        || !success.resultAtom().equals("undefined") || !result.effects().isEmpty())
                    throw new AssertionError(result.comparisonReport());
                long tests = result.trace().stream().filter(e -> e.kind()==SemanticOpKind.UNARY
                    && e.phase()==SemanticRuntimeModel.Phase.SUCCESS).count();
                if(tests<40) throw new AssertionError("nullish Oracle did not traverse selection controls: "+tests);
                var async=SemanticOracle.invokeAsyncEntry(executable,tables,new SemanticOracle.HostResponder(){},
                    executable.entryModule(),"asyncCoalescing",List.of());
                if(!(async.terminal() instanceof SemanticRuntimeModel.Terminal.Success completion)
                        || !completion.resultAtom().equals("num:0x1.8p3")) throw new AssertionError(async.comparisonReport());
                var failed=SemanticOracle.invokeSyncEntry(executable,tables,registries,new SemanticOracle.HostResponder(){},
                    executable.entryModule(),"oracleFailure",List.of());
                if(!(failed.terminal() instanceof SemanticRuntimeModel.Terminal.DealFailure failure))
                    throw new AssertionError(failed.comparisonReport());
                var error=failure.error();
                var assertion=executable.modules().values().stream().flatMap(unit->unit.ops().stream())
                    .filter(op->op.kind()==SemanticOpKind.BOUNDARY)
                    .filter(op->op.payload() instanceof KindPayload.BoundaryPayload p && p.kind()==BoundaryKind.ASSERTION
                        && p.descriptor().equals(RuntimeDescriptor.Number.INSTANCE))
                    .findFirst().orElseThrow();
                String origin=assertion.origin().sourceId()+":"+assertion.origin().span().startLine()+":"+assertion.origin().span().startColumn();
                if(!error.code().equals("E8001") || !error.expected().equals("number") || !error.actual().equals("string")
                        || !origin.equals(error.origin()) || error.frames().isEmpty() || error.cause()!=null
                        || expectedFailure!=null && !expectedFailure.equals(error))throw new AssertionError(error);
                expectedFailure=error;
            }
            for (var unit : project.modules().values()) mutations(unit, facts);
            CompositionProductionTest.execute(source, "", root.resolve("production"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            asyncProduction(source,root.resolve("async"),invocation);
            var corpus = Path.of("test/conformance").toAbsolutePath().normalize();
            var fixture = CorpusConformanceTest.nativeCorpusFixtures(corpus).stream()
                .filter(f -> f.corpusPath().equals(PATH)).findFirst().orElseThrow();
            LlvmConformanceTest.runInstrumented(corpus, fixture);
            System.out.println("Nullish coalescing: admission, transported Oracle and all production backends passed");
        } finally {CompositionProductionTest.remove(root);}
    }

    private static void mutations(LoweredModuleUnit unit, SemanticIrValidator.ComparisonFacts facts) {
        var projection = unit.ops().stream().filter(op -> op.payload() instanceof KindPayload.UnaryPayload p
            && p.selector()==UnarySelector.NON_NULLISH && op.operandTypes().getFirst() instanceof RuntimeDescriptor.Nullable)
            .findFirst().orElseThrow();
        var owner = unit.ops().stream().filter(op -> op.payload() instanceof KindPayload.BranchPayload p
            && p.selector()==ControlSelector.CONDITIONAL && p.alternateValue().equals(projection.result()))
            .findFirst().orElseThrow();
        var branch = (KindPayload.BranchPayload)owner.payload();
        var guard = unit.ops().stream().filter(op -> op.result()!=null && op.result().equals(branch.condition()))
            .findFirst().orElseThrow();
        for (String defect : List.of("guard-selector", "guard-operand", "projection-operand", "projection-result",
                "policy", "unavailable", "missing-operand", "wrong-arm", "late-guard", "late-projection", "parent", "missing-parent", "empty-membership", "singleton", "void")) {
            var ops = new ArrayList<>(unit.ops());
            var body = unit.bodyTable();
            SemanticOp changed = projection;
            switch(defect) {
                case "guard-selector" -> ops.set(ops.indexOf(guard), change(guard, guard.resultType(), guard.operands(),
                    guard.operandTypes(), new KindPayload.UnaryPayload(UnarySelector.BOOL_NOT), guard.failurePolicy()));
                case "guard-operand" -> {
                    // Keep a descriptor-valid nullish test but break guard/projection identity.
                    var producer=unit.ops().stream().filter(op -> op.result() instanceof ValueId && !op.result().equals(guard.operands().getFirst())
                        && op.resultType() instanceof RuntimeDescriptor && !(op.resultType() instanceof RuntimeDescriptor.Void)
                        && unit.ops().indexOf(op)<unit.ops().indexOf(guard)).findFirst().orElseThrow();
                    ops.set(ops.indexOf(guard), change(guard,guard.resultType(),List.of((ValueId)producer.result()),
                        List.of((RuntimeDescriptor)producer.resultType()),guard.payload(),guard.failurePolicy()));
                }
                case "projection-operand" -> changed=change(projection,projection.resultType(),List.of(branch.condition()),
                    List.of(RuntimeDescriptor.Boolean.INSTANCE),projection.payload(),projection.failurePolicy());
                case "projection-result" -> changed=change(projection,RuntimeDescriptor.String.INSTANCE,projection.operands(),
                    projection.operandTypes(),projection.payload(),projection.failurePolicy());
                case "policy" -> changed=change(projection,projection.resultType(),projection.operands(),projection.operandTypes(),
                    projection.payload(),FailurePolicyId.TYPE_DESCRIPTOR);
                case "unavailable" -> changed=change(projection,projection.resultType(),List.of(new ValueId(Integer.MAX_VALUE)),
                    projection.operandTypes(),projection.payload(),projection.failurePolicy());
                case "missing-operand" -> changed=change(projection,projection.resultType(),List.of(),List.of(),
                    projection.payload(),projection.failurePolicy());
                case "wrong-arm" -> body=move(body, projection.opId(), branch.selectedBlock());
                case "late-projection" -> body=move(body,
                    body.blockOps().get(branch.selectedBlock()).getFirst(),branch.alternateBlock());
                case "late-guard" -> {
                    var id=guard.opId();
                    var blocks=new LinkedHashMap<>(body.blockOps());
                    var members=new ArrayList<>(blocks.get(body.opBlocks().get(id)));
                    members.remove(id);members.add(id);blocks.put(body.opBlocks().get(id),members);
                    body=new StructuredBodyTable(blocks,body.opBlocks());
                }
                case "empty-membership" -> body=new StructuredBodyTable(Map.of(),Map.of());
                case "singleton", "void" -> {
                    var producer=unit.ops().stream().filter(op->Objects.equals(op.result(),projection.operands().getFirst())).findFirst().orElseThrow();
                    RuntimeDescriptor type=defect.equals("singleton")?RuntimeDescriptor.Null.INSTANCE:RuntimeDescriptor.Void.INSTANCE;
                    ops.set(ops.indexOf(producer),change(producer,type,producer.operands(),producer.operandTypes(),producer.payload(),producer.failurePolicy()));
                    ops.set(ops.indexOf(guard),change(guard,guard.resultType(),List.of((ValueId)producer.result()),
                        List.of(type),guard.payload(),guard.failurePolicy()));
                    changed=change(projection,type,List.of((ValueId)producer.result()),List.of(type),projection.payload(),projection.failurePolicy());
                }
                case "parent", "missing-parent" -> {
                    // A projection's parent must name the conditional which owns its alternate block.
                    var origin=projection.origin();
                    changed=new SemanticOp(projection.opId(),projection.kind(),new SourceOrigin(origin.sourceId(),origin.span(),
                        origin.kind(),origin.anchorId(),defect.equals("parent")?projection.opId():null),projection.result(),projection.resultType(),
                        projection.operands(),projection.operandTypes(),projection.payload(),projection.failurePolicy(),projection.contract());
                }
                default -> throw new AssertionError(defect);
            }
            if(changed!=projection) ops.set(ops.indexOf(projection),changed);
            var malformed = new LoweredModuleUnit(unit.formatVersion(),unit.semanticProfile(),unit.moduleId(),unit.interfaceHash(),
                unit.loweringContextHash(),unit.requiredCapabilities(),unit.constructCoverage(),unit.classLayouts(),unit.functions(),
                unit.moduleInit(),unit.exportPlan(),unit.functionBindings(),ops,body,unit.classFactories(),unit.classBindingFacts());
            var typed=SemanticIrValidator.validate(malformed,facts);
            var text=SemanticIrValidator.validateText(SemanticIrValidator.toUnitText(malformed),facts);
            if (defect.equals("void")) {
                if (typed.isEmpty() || !typed.equals(text) || !typed.get().message().contains("R-ENUM"))
                    throw new AssertionError("void operand positional rejection: " + typed + " / " + text);
                try {
                    RawUnitMaterializer.materialize(RawUnit.fromTyped(malformed));
                    throw new AssertionError("void data operand materialized");
                } catch (RawUnitMaterializer.Defect expected) {
                    if (!expected.getMessage().contains("void outside a function result")) throw expected;
                }
                continue;
            }
            var raw=SemanticIrValidator.validate(RawUnitMaterializer.materialize(RawUnit.fromTyped(malformed)),facts);
            if(typed.isEmpty() || !typed.equals(raw) || !typed.equals(text))
                throw new AssertionError(defect+": "+typed+" / "+raw+" / "+text);
        }
        for(String selector:List.of("IS_NULLISH","NON_NULLISH")) {
            var subject=unit.ops().stream().filter(op->op.payload() instanceof KindPayload.UnaryPayload p
                && p.selector().name().equals(selector)).findFirst().orElseThrow();
            for(var target:List.of(CanonicalJson.str("string"),CanonicalJson.number(42))) {
                var json=(CanonicalJson.Obj)ContractSnapshotCanonicalizer.toJson(RawUnit.fromTyped(unit));
                var ops=(CanonicalJson.Arr)value(json,"ops");
                var changedOps=new ArrayList<CanonicalJson.Value>();
                for(var item:ops.items()) {
                    var op=(CanonicalJson.Obj)item;
                    if(value(op,"opId").equals(ContractSnapshotCanonicalizer.semanticIdJson(subject.opId()))) {
                        var payload=field((CanonicalJson.Obj)value(op,"payload"),"target",target);
                        var contract=field((CanonicalJson.Obj)value(op,"contract"),"payload",payload);
                        var snapshot=new CanonicalJson.Obj(contract.entries().stream().filter(e->!e.key().equals("canonicalDigest")).toList());
                        contract=field(contract,"canonicalDigest",CanonicalJson.str(ContractSnapshotCanonicalizer.recomputeSnapshotDigest(snapshot)));
                        op=field(field(op,"payload",payload),"contract",contract);
                    }
                    changedOps.add(op);
                }
                var failure=SemanticIrValidator.validateText(CanonicalJson.serializeText(field(json,"ops",new CanonicalJson.Arr(changedOps))),facts);
                if(failure.isEmpty() || !failure.get().message().contains("R-ENUM")
                        || !failure.get().range().equals(subject.origin().span().range()))throw new AssertionError(failure);
            }
        }
    }
    private static CanonicalJson.Value value(CanonicalJson.Obj object,String key) {
        return object.entries().stream().filter(e->e.key().equals(key)).findFirst().orElseThrow().value();
    }
    private static CanonicalJson.Obj field(CanonicalJson.Obj object,String key,CanonicalJson.Value value) {
        return new CanonicalJson.Obj(object.entries().stream().map(e->e.key().equals(key)?new CanonicalJson.Entry(key,value):e).toList());
    }
    private static void asyncProduction(String source,Path root,CompilerInvocation invocation) throws Exception {
        Path input=Files.createDirectories(root.resolve("src")).resolve("main.deal");
        Files.writeString(input,source);
        Files.writeString(root.resolve("deal.json"),"{\"languageVersion\":\"1.6\",\"stdlib\":\"1.6\",\"moduleRoots\":[\"src\"]}");
        var pins=Map.of("asyncCoalescing",new deal.test.conformance.SidecarExpectations.CompletionPin("number","12"));
        for(var backend:List.of(Backend.JS,Backend.LUAJIT,Backend.JVM)) {
            Path out=root.resolve(backend.name());
            var compiler=new deal.module.CompilationOrchestrator(input,out,false,false,false,false,backend,Map.of(),
                List.of(input.getParent()),null,null,invocation);
            if(!compiler.compile())throw new AssertionError(compiler.diagnostics());
            if(backend==Backend.JS) {
                var result=BoundedProcess.runCaptured(List.of("node","-e",
                    "const rt=require('./deal/runtime');const entry=require('./main');rt.invokeAsyncExport(entry,'asyncCoalescing','number').then(v=>{if(!v.$ok||v.$value!==12){console.error(v);process.exit(1)}},e=>{console.error(e);process.exit(1)});"),out,Map.of("DEAL_DEFER_MAIN","1"));
                if(result.exitCode()!=0 || !result.stdout().isEmpty() || !result.stderr().isEmpty())throw new AssertionError(result);
            } else {
                var probes=List.of(new CorpusExecution.Probe("asyncCoalescing",true));
                var result=CorpusExecution.run(backend,out,"main",probes,Map.of(),Map.of(),List.of(),Set.of(),root,pins);
                if(result.exitCode()!=0 || !result.stdout().isEmpty() || !result.stderr().isEmpty())throw new AssertionError(result);
                CorpusExecution.completionVerdict(out,probes,pins);
            }
        }
    }
    private static StructuredBodyTable move(StructuredBodyTable body, OpId op, BlockId target) {
        var blocks=new LinkedHashMap<BlockId,List<OpId>>();
        body.blockOps().forEach((id,old)->{var members=new ArrayList<>(old);members.remove(op);if(id.equals(target))members.addFirst(op);blocks.put(id,members);});
        var inverse=new LinkedHashMap<>(body.opBlocks());inverse.put(op,target);
        return new StructuredBodyTable(blocks,inverse);
    }
    private static SemanticOp change(SemanticOp op, OpResultType type, List<ValueId> operands,
            List<RuntimeDescriptor> types, KindPayload payload, FailurePolicyId policy) {
        var old=op.contract();
        ClosedSelector selector=payload instanceof KindPayload.UnaryPayload p?p.selector():old.selector();
        var draft=new OperationContractSnapshot(old.version(),old.opKind(),type,types,selector,payload,policy,old.referencedSemanticIds(),"pending");
        var contract=new OperationContractSnapshot(old.version(),old.opKind(),type,types,selector,payload,policy,
            old.referencedSemanticIds(),ContractSnapshotCanonicalizer.digest(draft));
        return new SemanticOp(op.opId(),op.kind(),op.origin(),op.result(),type,operands,types,payload,policy,contract);
    }
}
