package deal.test;

import deal.codegen.Backend;
import deal.semantic.*;
import deal.semantic.ir.*;
import java.nio.file.*;
import java.util.*;

/** Declared Error schema and independent runtime diagnostic transport. */
public final class ErrorNameMessageTest {
    private static final String PATH="backend-runtime/error-handling/error-name-message.deal";
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("deal-error-name-");
        try {
            String source=ConformanceHarnessMetadata.productionCorpusSource(
                Files.readString(Path.of("test/conformance/"+PATH)),PATH);
            Path input=Files.createDirectories(root.resolve("oracle/src")).resolve("main.deal");
            Files.writeString(input,source.replace("function diagnostic():number", "export function diagnostic():number"));
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
            System.out.println("Error name/message: transported Oracle and all production backends passed");
        } finally {CompositionProductionTest.remove(root);}
    }
}
