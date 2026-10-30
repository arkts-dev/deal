package deal.test;

import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.checker.NameResolver;
import deal.checker.TypeChecker;
import deal.lexer.Lexer;
import deal.parser.Parser;
import deal.semantic.AdapterShapeMap;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedModuleKind;
import deal.semantic.CapabilityRegistry;
import deal.semantic.SemanticLowerer;
import deal.semantic.ir.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Adaptation consumes an evaluated function value, regardless of source shape. */
public class AdapterShapeMapPayloadTest {
    private static int passed;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }

    private static void checkSource(String expression, String setup) {
        String source = "function one(x:number):void{} "
            + "function main():void{" + setup
            + "let saved:(x:number,y:number)=>void=" + expression + ";}";
        var lex = new Lexer(source, "test.deal").tokenize();
        var parse = new Parser(lex.tokens(), "test.deal", lex.directiveEvents()).parse();
        check(parse.diagnostics().isEmpty(), "parse: " + parse.diagnostics());
        ProgramNode program = parse.program();
        var resolver = new NameResolver("test.deal", null);
        var symbols = resolver.resolve(program);
        check(resolver.diagnostics().isEmpty(), "resolve: " + resolver.diagnostics());
        CheckResult checks = TypeChecker.check("test.deal", symbols, resolver, program);
        check(!checks.hasErrors(), "check: " + checks.diagnostics());
        ModuleId module = new ModuleId("main");
        var input = new CheckedModuleInput(module, "test.deal", Path.of("test.deal"),
            program, checks, List.of(), List.of(), CheckedModuleKind.IMPLEMENTATION);
        String digest = new ProjectInterfaceIndex(ProjectInterfaceIndex.FORMAT_VERSION,
            Map.of(module, new ExternalModuleInterface(module,
                ExternalModuleKind.IMPLEMENTATION, List.of(), List.of(), List.of(),
                InitializationMode.ONCE_AFTER_DEPENDENCIES))).interfaceIndexDigest();
        var result = SemanticLowerer.lowerModuleShapeMapCore(input,
            SemanticProfile.DEAL_V1_5_BINARY64, Map.of(), digest,
            CapabilityRegistry.releaseRegistry().capabilityRegistryHash(),
            SemanticIdAllocator.over(List.of(module)));
        check(!result.lowering().hasErrors(), "lower: " + result.lowering().diagnostics());
        var unit = result.lowering().unit();
        var adapters = unit.ops().stream().filter(op ->
            op.kind() == SemanticOpKind.FUNCTION_ADAPT).toList();
        check(adapters.size() == 1, "one adapter for " + expression);
        var op = adapters.getFirst();
        var payload = (KindPayload.FunctionAdaptPayload) op.payload();
        check(payload.mode() == CaptureMode.VALUE, "value capture");
        check(op.operands().size() == 1, "one evaluated operand");
        ValueId operand = op.operands().getFirst();
        check(payload.source() instanceof AdaptSourceRef.Value value
            && value.value().equals(operand), "source names operand");
        int position = unit.ops().indexOf(op);
        check(unit.ops().subList(0, position).stream().anyMatch(producer ->
            operand.equals(producer.result())), "operand produced before adapter");
        check(unit.functionBindings().get(new FunctionAllocationIdentity(
            ((ValueId) op.result()).id())) instanceof FunctionExecutionBinding.AdapterBinding binding
            && binding.sourceRef().equals(payload.source()), "registry retains source value");
        check(unit.ops().stream().anyMatch(consumer -> consumer.payload()
            instanceof KindPayload.BindingInitPayload init
            && init.value().equals(op.result())), "declaration commits adapter identity");
        var repeated = SemanticLowerer.lowerModuleShapeMapCore(input,
            SemanticProfile.DEAL_V1_5_BINARY64, Map.of(), digest,
            CapabilityRegistry.releaseRegistry().capabilityRegistryHash(),
            SemanticIdAllocator.over(List.of(module)));
        check(ContractSnapshotCanonicalizer.serializeText(
            ContractSnapshotCanonicalizer.toJson(RawUnit.fromTyped(unit))).equals(
            ContractSnapshotCanonicalizer.serializeText(
                ContractSnapshotCanonicalizer.toJson(RawUnit.fromTyped(repeated.lowering().unit())))),
            "repeated lowering is byte identical");
    }

    private static void checkModeMap() {
        var proof = Optional.of(new BindingImmutabilityProof(new BindingId(1), 0));
        for (var shape : AdapterShapeMap.SourceShape.values()) {
            check(AdapterShapeMap.selectMode(shape, Optional.empty()) == CaptureMode.VALUE,
                "declaration source retains value: " + shape);
            check(AdapterShapeMap.selectPositionMode(shape, Optional.empty()) == CaptureMode.VALUE,
                "position source retains value: " + shape);
            if (shape == AdapterShapeMap.SourceShape.BINDING_REFERENCE) {
                check(AdapterShapeMap.selectMode(shape, proof) == CaptureMode.VALUE,
                    "proof does not select different capture semantics");
            } else {
                boolean rejected = false;
                try { AdapterShapeMap.selectMode(shape, proof); }
                catch (IllegalArgumentException expected) { rejected = true; }
                check(rejected, "nonbinding source cannot claim binding proof: " + shape);
            }
        }
        check(AdapterShapeMap.selectModeOverBindingLoad(Optional.empty()) == CaptureMode.VALUE,
            "mutable binding load retains value");
        for (var producer : List.of(SemanticOpKind.CLOSURE_NEW, SemanticOpKind.MEMBER_READ,
                SemanticOpKind.EXPORT_READ, SemanticOpKind.INDEX_READ, SemanticOpKind.FIELD_READ,
                SemanticOpKind.CALL, SemanticOpKind.BINARY, SemanticOpKind.BRANCH)) {
            check(AdapterShapeMap.selectModeOverProducedSource(producer, Optional.empty())
                == CaptureMode.VALUE, "produced source retains value: " + producer);
        }
    }

    public static void main(String[] args) {
        checkModeMap();
        checkSource("one", "");
        checkSource("source", "let source:(x:number)=>void=one;");
        checkSource("source", "let source:(x:number)=>void=one;source=source;");
        checkSource("function(x:number):void{}", "");
        System.out.println(passed + " passed, 0 failed");
    }
}
