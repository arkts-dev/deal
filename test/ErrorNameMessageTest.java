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
            const savedHas=WeakSet.prototype.has, savedOwn=Object.prototype.hasOwnProperty, savedFinite=Number.isFinite;
            WeakSet.prototype.has=Object.prototype.hasOwnProperty=Number.isFinite=()=>{throw Error('mutable host hook');};
            const captured=runtime.runtimeDiagnostic(nested);
            WeakSet.prototype.has=savedHas;Object.prototype.hasOwnProperty=savedOwn;Number.isFinite=savedFinite;
            if(!captured.includes('Outer'))process.exit(7);
            """), Path.of("").toAbsolutePath(), Map.of());
        if (getterControl.exitCode() != 0) throw new AssertionError("runtime export invoked mutable host metadata: " + getterControl);
        List<String> jvmProjection = List.of("java", "-cp", TestClasspath.absolute(TestClasspath.EmptyEntry.OMIT),
                ErrorNameMessageTest.class.getName(), "--diagnostic-projection");
        var disabledProjection = BoundedProcess.runCaptured(jvmProjection, Path.of("").toAbsolutePath(), Map.of("DEAL_DIAGNOSTICS_JSON", "0"));
        var enabledProjection = BoundedProcess.runCaptured(jvmProjection, Path.of("").toAbsolutePath(), Map.of("DEAL_DIAGNOSTICS_JSON", "1"));
        if (disabledProjection.exitCode() != 0 || enabledProjection.exitCode() != 0
                || !disabledProjection.stdout().equals("projection identity unchanged\n")
                || !enabledProjection.stdout().equals(disabledProjection.stdout()) || !disabledProjection.stderr().isEmpty())
            throw new AssertionError("JVM diagnostic projection changed transport identity/defaults");
        var projected = deal.amend.CompilerProtocolJson.requireObject(deal.amend.CompilerProtocolJson.decode(
                enabledProjection.stderr().strip().substring("DEAL_RUNTIME_DIAGNOSTIC: ".length())), "projection");
        var error = deal.amend.CompilerProtocolJson.requireObject(deal.amend.CompilerProtocolJson.field(projected, "runtime"), "runtime");
        var message = (CanonicalJson.Str) deal.amend.CompilerProtocolJson.field(error, "message");
        if (!message.value().equals("x".repeat(8192))) throw new AssertionError("JVM diagnostic text limit");
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

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--diagnostic-projection")) {
            var cause = new deal.codegen.jvm.JvmRuntime.DealError("Leaf", "leaf", null, null, null, null, null);
            for (int i = 0; i < 10; i++) cause = new deal.codegen.jvm.JvmRuntime.DealError(
                "Bound", "x".repeat(9000), null, null, null, null, cause);
            var originalCause = cause.cause;
            deal.codegen.jvm.JvmRuntime.reportRuntimeDiagnostic(cause);
            if (cause.cause != originalCause || !cause.msg.equals("x".repeat(9000))) throw new AssertionError("transport mutated");
            System.out.println("projection identity unchanged");
            return;
        }
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
            runtimeDiagnosticExports(root.resolve("diagnostics"));
            System.out.println("Error name/message: transported Oracle and all production backends passed");
        } finally {CompositionProductionTest.remove(root);}
    }
}
