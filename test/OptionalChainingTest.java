package deal.test;

import deal.codegen.Backend;
import deal.semantic.*;
import deal.semantic.ir.*;
import java.nio.file.*;
import java.util.*;

/** Whole-chain lazy selection through admitted IR, transported Oracle and artifacts. */
public final class OptionalChainingTest {
    private static final String PATH = "backend-runtime/classes/optional-chaining.deal";

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("deal-optional-");
        try {
            String source = ConformanceHarnessMetadata.productionCorpusSource(
                Files.readString(Path.of("test/conformance/" + PATH)), PATH);
            var companions = new LinkedHashMap<String,String>();
            for (String name : List.of("same_name_sibling_a", "same_name_sibling_b"))
                companions.put(name+".deal", Files.readString(Path.of("test/conformance/backend-runtime/classes/"+name+".deal")));
            Path input = Files.createDirectories(root.resolve("oracle/src")).resolve("main.deal");
            Files.writeString(input, source.replace("export function main():void", "export function run():void")
                + "\nexport function main():void{return ;}\nexport function oracleFailure():number|undefined{return failure();}\n");
            for (var companion : companions.entrySet())
                Files.writeString(input.getParent().resolve(companion.getKey()),companion.getValue());
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
            parsed.modules().forEach(raw -> {var unit=RawUnitMaterializer.materialize(raw);modules.put(unit.moduleId(),unit);});
            var transported = new ExecutableLoweredProject(project.semanticProfile(), parsed.interfaceIndex(), modules,
                project.entryModule(), parsed.classSeeds());
            if (SemanticIrValidator.validate(transported, facts).isPresent()) throw new AssertionError("transported project admission");
            SemanticRuntimeModel.ErrorSnapshot expectedFailure = null;
            for (var executable : List.of(project, transported)) {
                var tables = new LinkedHashMap<ModuleId,StructuredBodyTable>();
                var registries = new LinkedHashMap<ModuleId,ClassFactoryRegistry>();
                executable.modules().forEach((id,unit) -> {tables.put(id,unit.bodyTable());registries.put(id,unit.classFactories());});
                var result = SemanticOracle.invokeSyncEntry(executable,tables,registries,new SemanticOracle.HostResponder(){},
                    executable.entryModule(),"run",List.of());
                if (!(result.terminal() instanceof SemanticRuntimeModel.Terminal.Success success)
                        || !success.resultAtom().equals("undefined") || !result.effects().isEmpty())
                    throw new AssertionError(result.comparisonReport());
                if (result.trace().stream().filter(e -> e.kind()==SemanticOpKind.UNARY
                        && e.phase()==SemanticRuntimeModel.Phase.SUCCESS).count()<35)
                    throw new AssertionError("optional guards were not exercised");
                var failed = SemanticOracle.invokeSyncEntry(executable,tables,registries,new SemanticOracle.HostResponder(){},
                    executable.entryModule(),"oracleFailure",List.of());
                if (!(failed.terminal() instanceof SemanticRuntimeModel.Terminal.DealFailure failure))
                    throw new AssertionError(failed.comparisonReport());
                var error = failure.error();
                var assertion = executable.modules().values().stream().flatMap(unit -> unit.ops().stream())
                    .filter(op -> op.payload() instanceof KindPayload.BoundaryPayload p && p.kind()==BoundaryKind.ASSERTION
                        && p.descriptor().equals(RuntimeDescriptor.Number.INSTANCE))
                    .filter(op -> op.origin().span().startLine()==source.substring(0,source.indexOf("bad as number")).split("\n",-1).length)
                    .findFirst().orElseThrow();
                String origin = assertion.origin().sourceId()+":"+assertion.origin().span().startLine()+":"+assertion.origin().span().startColumn();
                if (!error.code().equals("E8001") || !error.expected().equals("number") || !error.actual().equals("string")
                        || !origin.equals(error.origin()) || error.frames().isEmpty() || error.cause()!=null
                        || expectedFailure!=null && !expectedFailure.equals(error)) throw new AssertionError(error);
                expectedFailure = error;
            }
            mutations(project.modules().get(project.entryModule()),facts);
            CompositionProductionTest.execute(source,"","",root.resolve("production"),null,
                List.of(Backend.JS,Backend.LUAJIT,Backend.JVM,Backend.LLVM),companions);
            var corpus = Path.of("test/conformance").toAbsolutePath().normalize();
            var fixture = CorpusConformanceTest.nativeCorpusFixtures(corpus).stream()
                .filter(f -> f.corpusPath().equals(PATH)).findFirst().orElseThrow();
            LlvmConformanceTest.runInstrumented(corpus,fixture);
            System.out.println("Optional chaining: admission, transported Oracle and all production backends passed");
        } finally {CompositionProductionTest.remove(root);}
    }

    private static void mutations(LoweredModuleUnit unit,SemanticIrValidator.ComparisonFacts facts) {
        var projection = unit.ops().stream().filter(op -> op.payload() instanceof KindPayload.UnaryPayload p
            && p.selector()==UnarySelector.NON_NULLISH && op.operandTypes().getFirst() instanceof RuntimeDescriptor.Nullable)
            .filter(op -> unit.ops().stream().anyMatch(owner -> owner.opId().equals(op.origin().parentOpId())
                && owner.payload() instanceof KindPayload.BranchPayload branch
                && !Objects.equals(branch.alternateValue(),op.result())))
            .findFirst().orElseThrow();
        var owner = unit.ops().stream().filter(op -> op.opId().equals(projection.origin().parentOpId())).findFirst().orElseThrow();
        var branch = (KindPayload.BranchPayload)owner.payload();
        var guard = unit.ops().stream().filter(op -> Objects.equals(op.result(),branch.condition())).findFirst().orElseThrow();
        for (String defect : List.of("guard-selector","projection-operand","projection-result","policy","missing-parent","wrong-arm","late-projection")) {
            var ops = new ArrayList<>(unit.ops());
            var body = unit.bodyTable();
            switch (defect) {
                case "guard-selector" -> ops.set(ops.indexOf(guard),change(guard,guard.resultType(),guard.operands(),guard.operandTypes(),
                    new KindPayload.UnaryPayload(UnarySelector.BOOL_NOT),guard.failurePolicy()));
                case "projection-operand" -> ops.set(ops.indexOf(projection),change(projection,projection.resultType(),List.of(branch.condition()),
                    List.of(RuntimeDescriptor.Boolean.INSTANCE),projection.payload(),projection.failurePolicy()));
                case "projection-result" -> ops.set(ops.indexOf(projection),change(projection,RuntimeDescriptor.String.INSTANCE,projection.operands(),
                    projection.operandTypes(),projection.payload(),projection.failurePolicy()));
                case "policy" -> ops.set(ops.indexOf(projection),change(projection,projection.resultType(),projection.operands(),
                    projection.operandTypes(),projection.payload(),FailurePolicyId.TYPE_DESCRIPTOR));
                case "missing-parent" -> {
                    var o=projection.origin();
                    ops.set(ops.indexOf(projection),new SemanticOp(projection.opId(),projection.kind(),new SourceOrigin(o.sourceId(),o.span(),o.kind(),o.anchorId(),null),
                        projection.result(),projection.resultType(),projection.operands(),projection.operandTypes(),projection.payload(),projection.failurePolicy(),projection.contract()));
                }
                case "wrong-arm" -> body=move(body,projection.opId(),branch.selectedBlock());
                case "late-projection" -> {
                    var blocks=new LinkedHashMap<>(body.blockOps());
                    var members=new ArrayList<>(blocks.get(branch.alternateBlock()));
                    members.remove(projection.opId());members.add(1,projection.opId());
                    blocks.put(branch.alternateBlock(),members);
                    body=new StructuredBodyTable(blocks,body.opBlocks());
                }
                default -> throw new AssertionError(defect);
            }
            var malformed = new LoweredModuleUnit(unit.formatVersion(),unit.semanticProfile(),unit.moduleId(),unit.interfaceHash(),
                unit.loweringContextHash(),unit.requiredCapabilities(),unit.constructCoverage(),unit.classLayouts(),unit.functions(),unit.moduleInit(),
                unit.exportPlan(),unit.functionBindings(),ops,body,unit.classFactories(),unit.classBindingFacts());
            var typed = SemanticIrValidator.validate(malformed,facts);
            var raw = SemanticIrValidator.validate(RawUnitMaterializer.materialize(RawUnit.fromTyped(malformed)),facts);
            var text = SemanticIrValidator.validateText(SemanticIrValidator.toUnitText(malformed),facts);
            if (typed.isEmpty() || !typed.equals(raw) || !typed.equals(text)
                    || !typed.get().message().contains(defect.equals("policy")?"R-POLICY-KIND":"R-ENUM")
                    || !typed.get().range().equals(projection.origin().span().range()))
                throw new AssertionError(defect+": "+typed+" / "+raw+" / "+text);
        }
    }

    private static StructuredBodyTable move(StructuredBodyTable body,OpId op,BlockId target) {
        var blocks = new LinkedHashMap<BlockId,List<OpId>>();
        body.blockOps().forEach((id,old) -> {var members=new ArrayList<>(old);members.remove(op);if(id.equals(target))members.addFirst(op);blocks.put(id,members);});
        var inverse = new LinkedHashMap<>(body.opBlocks());inverse.put(op,target);
        return new StructuredBodyTable(blocks,inverse);
    }

    private static SemanticOp change(SemanticOp op,OpResultType type,List<ValueId> operands,List<RuntimeDescriptor> types,KindPayload payload,FailurePolicyId policy) {
        var old = op.contract();
        ClosedSelector selector = payload instanceof KindPayload.UnaryPayload p?p.selector():old.selector();
        var draft = new OperationContractSnapshot(old.version(),old.opKind(),type,types,selector,payload,policy,old.referencedSemanticIds(),"pending");
        var contract = new OperationContractSnapshot(old.version(),old.opKind(),type,types,selector,payload,policy,old.referencedSemanticIds(),ContractSnapshotCanonicalizer.digest(draft));
        return new SemanticOp(op.opId(),op.kind(),op.origin(),op.result(),type,operands,types,payload,policy,contract);
    }
}
