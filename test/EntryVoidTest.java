package deal.test;

import deal.codegen.Backend;
import deal.module.CompilationOrchestrator;
import deal.semantic.*;
import deal.semantic.ir.*;
import java.nio.file.*;
import java.util.*;

/** Selected-entry admission, resultless delegation and unchanged rejected publication. */
public final class EntryVoidTest {
    private record EntryCase(String source, String error) {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("deal-entry-void-");
        try {
            Path input = Files.createDirectories(root.resolve("src")).resolve("main.deal");
            Files.writeString(root.resolve("deal.json"),
                "{\"languageVersion\":\"1.6\",\"moduleRoots\":[\"src\"]}");
            var invocation = CompilerProfileProvider.resolve(ReleaseConfiguration.CURRENT_RELEASE_STATE,
                ReleaseConfiguration.releaseCapabilityRegistry());
            var cases = List.of(
                new EntryCase("export function main():void {}", null),
                new EntryCase("export function main():void {return;}", null),
                new EntryCase("export function main():null {return null;}", "E2011"),
                new EntryCase("export function main():number {return 1;}", "E2011"),
                new EntryCase("export async function main():Promise<void> {}", "E2011"),
                new EntryCase("export function main(x:number):void {}", "E2011"),
                new EntryCase("function main():void {}", "E2012"),
                new EntryCase("export function other():void {}", "E2012"),
                new EntryCase("export function main():void {return null;}", "E5003"),
                new EntryCase("function f():void {} export function main():void {return f();}", "E5003"),
                new EntryCase("export class main {value:number;}", "E2011"));
            for (int i = 0; i < cases.size(); i++) {
                var subject = cases.get(i);
                Path out = Files.createDirectories(root.resolve("out-" + i));
                Files.writeString(out.resolve("preserved"), "original");
                Files.writeString(input, subject.source());
                var compiler = new CompilationOrchestrator(input, out, false, false, false, false, Backend.JS,
                    Map.of(), List.of(input.getParent()), null, null, invocation);
                boolean success = compiler.compile();
                if (success != (subject.error() == null))
                    throw new AssertionError("entry " + i + ": " + compiler.diagnostics());
                if (!success) {
                    if (compiler.diagnostics().stream().noneMatch(d -> d.code().equals(subject.error())))
                        throw new AssertionError("entry " + i + ": missing " + subject.error() + " " + compiler.diagnostics());
                    if (!Files.readString(out.resolve("preserved")).equals("original")
                            || Files.exists(out.resolve("main.js")))
                        throw new AssertionError("rejected publication");
                } else {
                    var project = Objects.requireNonNull(compiler.producedProject());
                    var facts = new SemanticIrValidator.ComparisonFacts(project.interfaceIndex().interfaceIndexDigest(),
                        project.semanticProfile(), invocation.capabilityRegistryHash());
                    var parsed = ContractSnapshotCanonicalizer.parseProject((CanonicalJson.Obj) CanonicalJson.parse(
                        SemanticIrValidator.toProjectText(project)));
                    var modules = new LinkedHashMap<ModuleId, LoweredModuleUnit>();
                    parsed.modules().forEach(raw -> {
                        var unit = RawUnitMaterializer.materialize(raw);
                        modules.put(unit.moduleId(), unit);
                    });
                    var transported = new ExecutableLoweredProject(project.semanticProfile(), parsed.interfaceIndex(),
                        modules, project.entryModule(), parsed.classSeeds());
                    for (var executable : List.of(project, transported)) {
                        if (SemanticIrValidator.validate(executable, facts).isPresent())
                            throw new AssertionError("Void entry project admission");
                        var entry = executable.modules().get(executable.entryModule());
                        var owners = entry.ops().stream().filter(op -> op.kind() == SemanticOpKind.ENTRY_INVOKE).toList();
                        if (owners.size() != 1) throw new AssertionError("entry delegation population");
                        var calls = entry.ops().stream().filter(op -> op.kind() == SemanticOpKind.CALL
                            && owners.getFirst().opId().equals(op.origin().parentOpId())).toList();
                        if (calls.size() != 1 || calls.getFirst().result() == null
                                || !(calls.getFirst().resultType() instanceof RuntimeDescriptor.Void))
                            throw new AssertionError("Void delegation carrier");
                        if (entry.ops().stream().anyMatch(op -> op.kind() == SemanticOpKind.DISCARD
                                && op.operands().contains(calls.getFirst().result())))
                            throw new AssertionError("Void entry fabricated a data discard");
                        var tables = new LinkedHashMap<ModuleId, StructuredBodyTable>();
                        var registries = new LinkedHashMap<ModuleId, ClassFactoryRegistry>();
                        executable.modules().forEach((id, unit) -> {
                            tables.put(id, unit.bodyTable());
                            registries.put(id, unit.classFactories());
                        });
                        var result = SemanticOracle.executeProjectInits(executable, tables, registries,
                            new SemanticOracle.HostResponder() {});
                        if (!(result.terminal() instanceof SemanticRuntimeModel.Terminal.Success s)
                                || !s.resultAtom().equals("undefined"))
                            throw new AssertionError(result.comparisonReport());
                    }
                }
            }
            for (String tail : List.of("", "return;")) {
                String source = "import * as output from \"std/console\";import * as lib from \"./lib\";"
                    + "export function main():void {let value:null=lib.tick();"
                    + "if(value!==null){throw {name:\"ENTRY_FAILED\",message:\"ordinary null result\"};}"
                    + "output.log(\"main\");" + tail + "}";
                CompositionProductionTest.execute(source, "tick\nmain\n", "", root.resolve("production-" + tail.length()), null,
                    List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM),
                    Map.of("lib.deal", "import * as output from \"std/console\";"
                        + "export function tick():null {output.log(\"tick\");return null;}"));
            }
            System.out.println("Void entry: signature/publication/transported admission/Oracle and all four production backends passed");
        } finally {
            CompositionProductionTest.remove(root);
        }
    }
}
