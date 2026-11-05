package deal.test;

import deal.codegen.llvm.*;
import deal.semantic.ir.*;
import deal.test.conformance.*;
import deal.toolchain.NativeBuildRequest;
import java.nio.file.*;
import java.util.*;

/** Executed emitter-level controls for IR shapes not expressible by v1.5 Record syntax. */
final class NativeRequirementCases {
    static void run(NativeCorpusAccounting accounting) throws Exception {
        Path corpus = NativeCompiler.executableScratch("native-requirements");
        try {
            Files.createDirectories(corpus.resolve("host-fixtures"));
            Path source = corpus.resolve("backend-runtime/requirements.deal");
            Files.createDirectories(source.getParent());
            String body = """
                import * as math from "std/math";
                // @jsonable
                export class Cell { value: number; }
                function check(ok: boolean, site: string): void {
                  if (!ok) { throw {code: "REQUIREMENT_TEST", message: site}; }
                }
                export function main(): null {
                  let record: Record<string, unknown> = {present: 7, explicit: undefined, wrong: "bad"};
                  let first = record.present as number | undefined;
                  check(first !== undefined, "present");
                  if (first !== undefined) { check(first === 7, "present value"); }
                  check((record.absent as number | undefined) === undefined, "absent");
                  check((record.explicit as number | undefined) === undefined, "explicit undefined");
                  let again = record.present as number | undefined;
                  check(again !== undefined, "again");
                  if (again !== undefined) { check(again === 7, "again value"); }
                  let rejected: boolean = false;
                  try { let bad = record.wrong as number | undefined; }
                  catch (failure) {
                    if (failure instanceof Error) {
                      rejected = failure.code === "E8001"
                        && failure.message === "expected number";
                      if (!rejected) { throw failure; }
                    }
                  }
                  check(rejected, "wrong optional kind");
                  let cell = Cell$fromJson("{\"value\":7}");
                  check(cell !== null, "decoded");
                  if (cell !== null) {
                    let roundtrip = Cell$fromJson(Cell$toJson(cell));
                    check(roundtrip !== null, "encoded object");
                    if (roundtrip !== null) { check(roundtrip.value === 7, "encoded field"); }
                  }
                  check(Cell$fromJson("{\"value\":\"bad\"}") === null, "invalid decoded");
                  let imported = math.sqrt as (x: number) => number;
                  check(imported(9) === 3, "imported function");
                  check(math.sqrt(4) === 2, "imported");
                  return null;
                }
                """;
            // JSON quotes must survive the DEAL source string literal.
            body = body.replace("\"{\"value\":7}\"", "\"{\\\"value\\\":7}\"")
                .replace("\"{\"value\":\"bad\"}\"", "\"{\\\"value\\\":\\\"bad\\\"}\"");
            Files.writeString(source, body);
            var fixture = new CorpusDiscovery.Fixture(source, "backend-runtime/requirements.deal", "backend-runtime",
                "Runtime execution model", "native requirement controls", "runtime-ok", "", "", body, body, 0,
                new CorpusDiscovery.Classification(CorpusDiscovery.Kind.RUNTIME_OK, null));
            try (var project = new NativeCorpusProject(corpus, fixture, null)) {
                if (!project.compile()) throw new AssertionError("requirement source rejected: " + project.compiler.diagnostics());
                ExecutableLoweredProject lowered = optionalEnvelopes(project.compiler.producedProject());
                var invocation = deal.semantic.CompilerProfileProvider.resolve(deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                    deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
                var admission = SemanticIrValidator.validate(lowered, new SemanticIrValidator.ComparisonFacts(
                    lowered.interfaceIndex().interfaceIndexDigest(), lowered.semanticProfile(), invocation.capabilityRegistryHash()));
                if (admission.isPresent()) throw new AssertionError("requirement IR rejected: " + admission);
                Map<ModuleId, StructuredBodyTable> tables = new LinkedHashMap<>();
                Map<ModuleId, ClassFactoryRegistry> registries = new LinkedHashMap<>();
                lowered.modules().forEach((id, unit) -> { tables.put(id, unit.bodyTable()); registries.put(id, unit.classFactories()); });
                var emitted = LlvmSemanticEmitter.emitProductionProject(new LlvmEmissionInput(lowered, tables, registries,
                    Map.of(), project.compiler.hostDeclarationSurface()));
                Files.writeString(project.output.resolve(emitted.artifactRelativePath()), emitted.source());
                var probes = CorpusExecution.probes(project.program(), false);
                for (int level : new int[]{0, 2}) {
                    Path product = project.scratch.resolve("requirements-O" + level);
                    NativeCompiler.build(new NativeBuildRequest(project.output, product, level, List.of(),
                        LlvmConformanceTest.driver(project.module(), probes)));
                    for (boolean stress : new boolean[]{false, true}) {
                        Path work = Files.createDirectories(project.scratch.resolve("run-" + level + "-" + stress));
                        var actual = BoundedProcess.runCaptured(List.of(product.toString()), work,
                            stress ? Map.of("DEAL_GC_STRESS", "1") : Map.of());
                        var receipt = CorpusExecution.receiptVerdict(work, probes, false, false);
                        if (actual.exitCode() != 0 || !actual.stdout().isEmpty() || !actual.stderr().isEmpty()
                                || actual.stdoutTruncated() || actual.stderrTruncated() || receipt.isPresent())
                            throw new AssertionError("requirement O" + level + " stress=" + stress + ": " + actual + "; " + receipt);
                    }
                }
                accounting.executed(lowered, null);
                // Both actual class JSON services ran above: successful field roundtrip
                // and wrong-field decode returning null, not fabricated semantic ops.
                accounting.runtimeBoundary(BoundaryKind.JSON_FROM_FIELD);
                accounting.runtimeBoundary(BoundaryKind.JSON_TO_FIELD);
                System.out.println("Native requirement controls: admitted optional envelopes, imported function, class JSON O0/O2 normal/stress");
            }
            runtimeContracts(corpus, accounting);
        } finally {
            try (var paths = Files.walk(corpus)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void runtimeContracts(Path scratch, NativeCorpusAccounting accounting) throws Exception {
        Path published = Files.createDirectories(scratch.resolve("runtime"));
        NativeCompiler.deployDeploymentSet(published);
        for (int level : new int[]{0, 2}) {
            Path product = scratch.resolve("contracts-O" + level);
            var sources = NativeCompiler.driverSources(published,
                List.of(NativeCompiler.fixtures().resolve("module_stub.c"), NativeCompiler.fixtures().resolve("registry_empty.c")),
                NativeCompiler.fixtures().resolve("boundary_driver.c"));
            var link = NativeCompiler.link(published, level, sources, product);
            if (!link.succeeded()) throw new AssertionError("native contract link: " + link.describe());
            for (boolean stress : new boolean[]{false, true}) {
                var actual = BoundedProcess.runCaptured(List.of(product.toString()), scratch,
                    stress ? Map.of("DEAL_GC_STRESS", "1") : Map.of());
                if (actual.exitCode() != 0 || actual.stdoutTruncated() || actual.stderrTruncated()
                        || !actual.stderr().isEmpty() || !actual.stdout().endsWith("boundary ok\n"))
                    throw new AssertionError("native runtime contracts: " + actual);
                Set<FailureArmId> seen = EnumSet.noneOf(FailureArmId.class);
                for (String line : actual.stdout().split("\n")) {
                    if (!line.startsWith("ARM\t")) continue;
                    String[] fields = line.split("\t", -1);
                    FailureArmId id = FailureArmId.values()[Integer.parseInt(fields[1])];
                    int count = Integer.parseInt(fields[2]);
                    if (fields.length != 7 + 2 * count) throw new AssertionError("truncated arm: " + line);
                    Map<String, String> parameters = new LinkedHashMap<>();
                    for (int index = 0; index < count; index++) parameters.put(fields[3 + 2 * index], fields[4 + 2 * index]);
                    if (id == FailureArmId.SQRT_NEGATIVE && (!parameters.isEmpty()
                            || !fields[3].equals("E8001") || !fields[4].equals("-")
                            || !fields[5].equals("-0x1.0p2") || !fields[6].equals("sqrt of negative number")))
                        throw new AssertionError("retained sqrt ABI tuple changed: " + line);
                    accounting.materializedArm(id, parameters, fields[3 + 2 * count], fields[6 + 2 * count],
                        fields[4 + 2 * count].equals("-") ? null : fields[4 + 2 * count],
                        fields[5 + 2 * count].equals("-") ? null : fields[5 + 2 * count]);
                    seen.add(id);
                }
                if (!seen.containsAll(Set.of(FailureArmId.SQRT_NEGATIVE, FailureArmId.HOST_LOAD_RAW_SHAPE,
                        FailureArmId.HOST_LOAD_NOT_A_MODULE, FailureArmId.ARRAY_DELETE_BOUNDS)))
                    throw new AssertionError("missing retained ABI controls");
                accounting.runtimeBoundary(BoundaryKind.ARRAY_ELEMENT_DELETE);
            }
        }
        System.out.println("Native runtime contracts: actual arm materialization O0/O2 normal/stress (not source sqrt behavior)");
    }

    /** Preserve the source assertions while spelling their equivalent raw-read/optional IR envelope.
     * v1.5 source Record reads are unknown; only this emitter control constructs the legacy envelope. */
    private static ExecutableLoweredProject optionalEnvelopes(ExecutableLoweredProject project) {
        Map<ModuleId, LoweredModuleUnit> modules = new LinkedHashMap<>();
        int changed = 0;
        for (var unit : project.modules().values()) {
            Set<ValueId> raw = new HashSet<>();
            List<SemanticOp> ops = new ArrayList<>();
            for (var op : unit.ops()) {
                if (op.payload() instanceof KindPayload.BoundaryPayload boundary
                        && boundary.kind() == BoundaryKind.ASSERTION
                        && boundary.descriptor() instanceof RuntimeDescriptor.Nullable nullable
                        && nullable.inner() instanceof RuntimeDescriptor.Number) {
                    raw.add(boundary.input());
                    ops.add(rebuild(op, SemanticOpKind.OPTIONAL_READ,
                        new KindPayload.OptionalReadPayload(boundary.input(), true, nullable.inner()),
                        op.resultType(), FailurePolicyId.NO_DEAL_FAILURE));
                    changed++;
                } else if (op.payload() instanceof KindPayload.BoundaryPayload boundary
                        && boundary.kind() == BoundaryKind.ASSERTION
                        && boundary.descriptor() instanceof RuntimeDescriptor.Func) {
                    ops.add(rebuild(op, op.kind(), new KindPayload.BoundaryPayload(BoundaryKind.IMPORTED_MEMBER_READ,
                        boundary.descriptor(), boundary.input(), boundary.realization()), op.resultType(), FailurePolicyId.FUNCTION_SIGNATURE));
                } else ops.add(op);
            }
            ops.replaceAll(op -> op.result() instanceof ValueId value && raw.contains(value)
                ? rebuild(op, op.kind(), op.payload(), InternalResultType.INTERNAL_MISSING, op.failurePolicy()) : op);
            modules.put(unit.moduleId(), new LoweredModuleUnit(unit.formatVersion(), unit.semanticProfile(), unit.moduleId(),
                unit.interfaceHash(), unit.loweringContextHash(), unit.requiredCapabilities(), unit.constructCoverage(),
                unit.classLayouts(), unit.functions(), unit.moduleInit(), unit.exportPlan(), unit.functionBindings(), ops,
                unit.bodyTable(), unit.classFactories(), unit.classBindingFacts()));
        }
        if (changed != 5) throw new AssertionError("optional controls not all wired: " + changed);
        return new ExecutableLoweredProject(project.semanticProfile(), project.interfaceIndex(), modules, project.entryModule(), project.classSeeds());
    }

    private static SemanticOp rebuild(SemanticOp op, SemanticOpKind kind, KindPayload payload, OpResultType result, FailurePolicyId policy) {
        var draft = new OperationContractSnapshot(OperationContractSnapshot.VERSION, kind, result, op.operandTypes(), null,
            payload, policy, op.contract().referencedSemanticIds(), "");
        var stamped = new OperationContractSnapshot(draft.version(), kind, result, draft.operandTypes(), null,
            payload, policy, draft.referencedSemanticIds(), ContractSnapshotCanonicalizer.digest(draft));
        return new SemanticOp(op.opId(), kind, op.origin(), op.result(), result, op.operands(), op.operandTypes(), payload, policy, stamped);
    }
}
