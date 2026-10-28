package deal.test;

import deal.codegen.Backend;
import deal.codegen.SharedEmissionFailure;
import deal.codegen.llvm.LlvmEmissionInput;
import deal.codegen.llvm.LlvmSemanticEmitter;
import deal.codegen.llvm.LlvmStageClassifier;
import deal.codegen.llvm.LlvmStageClassifier.StageSupport;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
import deal.module.CompilationOrchestrator;
import deal.project.CliOverrides;
import deal.project.ProjectContext;
import deal.project.ProjectLocator;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.ir.BinarySelector;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.CallMode;
import deal.semantic.ir.ControlSelector;
import deal.semantic.ir.DeleteTargetKind;
import deal.semantic.ir.AssignTargetKind;
import deal.semantic.ir.FailureArmId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.ModuleImportKind;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.StdlibFunctionId;
import deal.semantic.ir.UnarySelector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The closed-set stage classifier of the native backend
 * ({@code llvm-native-codegen-cfg-ssa} C1/C9, task "Exhaustively classify
 * every merged op/payload/selector/boundary/stdlib/failure/host-import fact").
 *
 * <ol>
 *   <li>every member of every closed family — operation kind, payload
 *       variant, binary and unary selector, boundary kind, stdlib operation,
 *       failure arm, control selector, call mode, assignment and delete
 *       target kind, module-import kind — carries exactly one decision, and
 *       the staged sets are the documented ones;</li>
 *   <li>every fact outside the staged skeleton raises one E6005 through the
 *       production arm, carrying the offending op's own origin;</li>
 *   <li>the staged skeleton renders a scalar project without a single
 *       rejection (the emission suite drives the bytes; this one asserts the
 *       classifier's answer over the same project).</li>
 * </ol>
 */
public final class LlvmStageClassifierTest {

    private static final Path REPO = NativeCompiler.repository();

    private static final Map<String, String> SUPPORTED_SOURCE = Map.of(
        "src/lib.deal", """
            function twice(a: number): number {
              return a + a;
            }

            export function ping(): null {
              let total: number = twice(2);
              let i: number = 0;
              while (i < total) {
                i = i + 1;
              }
              if (i > 3) {
                i = i - 1;
              }
              return null;
            }
            """,
        "src/main.deal", """
            import * as lib from "./lib";

            export function main(): null {
              let ignored: null = lib.ping();
              return ignored;
            }
            """);

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("=== LLVM Stage Classifier Tests (ISSUE-0903) ===\n");
        testEveryClosedFamilyIsClassified();
        testStagedSets();
        testStatementsAndSelectors();
        testUnsupportedFactsCarryOrigins();
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== LLVM Stage Classifier Tests Passed ===");
    }

    // =========================================================================
    // 1. Every closed fact carries one decision
    // =========================================================================

    private static void testEveryClosedFamilyIsClassified() {
        System.out.println("-- every closed fact carries one decision --");
        List<LlvmStageClassifier.Fact> facts = LlvmStageClassifier.facts();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (LlvmStageClassifier.Fact fact : facts) {
            counts.merge(fact.family(), 1, Integer::sum);
            check(fact.support() != null,
                "the fact " + fact.family() + "." + fact.member()
                    + " carries a decision");
        }
        checkEq(SemanticOpKind.values().length,
            counts.getOrDefault("SemanticOpKind", 0),
            "every SemanticOpKind is classified");
        checkEq(KindPayload.class.getPermittedSubclasses().length,
            counts.getOrDefault("KindPayload", 0),
            "every payload variant is classified");
        checkEq(BinarySelector.values().length,
            counts.getOrDefault("BinarySelector", 0),
            "every binary selector is classified");
        checkEq(UnarySelector.values().length,
            counts.getOrDefault("UnarySelector", 0),
            "every unary selector is classified");
        checkEq(BoundaryKind.values().length,
            counts.getOrDefault("BoundaryKind", 0),
            "every boundary kind is classified");
        checkEq(StdlibFunctionId.values().length,
            counts.getOrDefault("StdlibFunctionId", 0),
            "every stdlib operation is classified");
        checkEq(FailureArmId.values().length,
            counts.getOrDefault("FailureArmId", 0),
            "every failure arm is classified");
        checkEq(ControlSelector.values().length,
            counts.getOrDefault("ControlSelector", 0),
            "every control selector is classified");
        checkEq(CallMode.values().length,
            counts.getOrDefault("CallMode", 0),
            "every call mode is classified");
        checkEq(AssignTargetKind.values().length,
            counts.getOrDefault("AssignTargetKind", 0),
            "every assignment target kind is classified");
        checkEq(DeleteTargetKind.values().length,
            counts.getOrDefault("DeleteTargetKind", 0),
            "every delete target kind is classified");
        checkEq(ModuleImportKind.values().length,
            counts.getOrDefault("ModuleImportKind", 0),
            "every import kind (the host-import fact) is classified");
        check(counts.containsKey("ModuleImportKind"),
            "the host-import fact is part of the classification");
    }

    // =========================================================================
    // 2. The staged sets are the documented ones
    // =========================================================================

    private static void testStagedSets() {
        System.out.println("-- the staged sets are the documented ones --");
        Set<String> stagedKinds = new LinkedHashSet<>();
        Set<String> rejectedKinds = new LinkedHashSet<>();
        Set<String> staticKinds = new LinkedHashSet<>();
        for (SemanticOpKind kind : SemanticOpKind.values()) {
            switch (LlvmStageClassifier.opKind(kind)) {
                case STAGED -> stagedKinds.add(kind.name());
                case STATIC_ONLY -> staticKinds.add(kind.name());
                case REJECTED -> rejectedKinds.add(kind.name());
            }
        }
        checkEq(Set.of("CONST", "UNARY", "BINARY", "BINDING_ALLOC",
                "BINDING_INIT", "BINDING_LOAD", "BINDING_STORE", "ASSIGN",
                "CALL", "CLOSURE_NEW", "BRANCH", "LOOP", "TRY_CATCH", "THROW",
                "RETURN", "BREAK", "CONTINUE", "DISCARD", "MODULE_INIT",
                "MODULE_IMPORT", "EXPORT_READ", "EXPORT_PUBLISH",
                "ENTRY_INVOKE"),
            stagedKinds, "the staged operation kinds");
        checkEq(Set.of("BOUNDARY"), staticKinds,
            "the only statically-proven operation kind is the typed boundary");
        check(rejectedKinds.contains("ARRAY_NEW")
                && rejectedKinds.contains("TABLE_NEW")
                && rejectedKinds.contains("CLASS_NEW")
                && rejectedKinds.contains("STDLIB_CALL")
                && rejectedKinds.contains("ASYNC_START")
                && rejectedKinds.contains("FOR_EACH")
                && rejectedKinds.contains("STRING_CONCAT"),
            "the runtime-service op families are rejected: " + rejectedKinds);
        Set<String> stagedBinary = new LinkedHashSet<>();
        for (BinarySelector selector : BinarySelector.values()) {
            if (LlvmStageClassifier.binarySelector(selector) == StageSupport.STAGED) {
                stagedBinary.add(selector.name());
            }
        }
        Set<String> everyBinary = new LinkedHashSet<>();
        for (BinarySelector selector : BinarySelector.values()) {
            everyBinary.add(selector.name());
        }
        checkEq(everyBinary, stagedBinary,
            "every merged binary selector is rendered by exactly one arm");
        for (UnarySelector selector : UnarySelector.values()) {
            checkEq(StageSupport.STAGED,
                LlvmStageClassifier.unarySelector(selector),
                "the unary selector " + selector + " is rendered by one arm");
        }
        checkEq(StageSupport.STAGED,
            LlvmStageClassifier.importKind(ModuleImportKind.COMPILED),
            "a compiled import is staged");
        checkEq(StageSupport.REJECTED,
            LlvmStageClassifier.importKind(ModuleImportKind.HOST),
            "a host import is rejected here and belongs to host-sync");
        checkEq(StageSupport.REJECTED,
            LlvmStageClassifier.importKind(ModuleImportKind.STDLIB),
            "a stdlib import needs the deployed stdlib surface");
        for (FailureArmId arm : FailureArmId.values()) {
            checkEq(StageSupport.STAGED, LlvmStageClassifier.failureArm(arm),
                "every failure arm is emitted as data: " + arm);
        }
        checkEq(StageSupport.STAGED,
            LlvmStageClassifier.controlSelector(ControlSelector.TRY_CATCH),
            "TRY_CATCH pushes its handler on the failure-route stack");
        checkEq(StageSupport.STAGED,
            LlvmStageClassifier.controlSelector(ControlSelector.WHILE),
            "WHILE is a staged loop shape");
        checkEq(StageSupport.REJECTED,
            LlvmStageClassifier.deleteTarget(DeleteTargetKind.TABLE_SLOT),
            "a table delete needs the container group");
        checkEq(StageSupport.STAGED,
            LlvmStageClassifier.assignTarget(AssignTargetKind.VARIABLE),
            "a binding assignment is staged");
        checkEq(StageSupport.STAGED,
            LlvmStageClassifier.payload(KindPayload.AssignPayload.class),
            "the assignment payload variant is staged");
        checkEq(StageSupport.STAGED,
            LlvmStageClassifier.payload(KindPayload.LoopPayload.class),
            "the loop payload variant is staged");
        checkEq(StageSupport.STAGED,
            LlvmStageClassifier.payload(KindPayload.TryCatchPayload.class),
            "the try/catch payload variant is staged");
        checkEq(StageSupport.STAGED,
            LlvmStageClassifier.payload(KindPayload.ThrowPayload.class),
            "the throw payload variant is staged");
        checkEq(StageSupport.STAGED,
            LlvmStageClassifier.payload(KindPayload.ConstPayload.class),
            "the constant payload variant is staged");
    }

    // =========================================================================
    // 3. Statements of the static-only facts
    // =========================================================================

    private static void testStatementsAndSelectors() {
        System.out.println("-- the statically-proven families --");
        for (BoundaryKind kind : BoundaryKind.values()) {
            StageSupport support = LlvmStageClassifier.boundaryKind(kind);
            check(support == StageSupport.STATIC_ONLY
                    || support == StageSupport.REJECTED,
                "the boundary kind " + kind + " is never unconditionally staged");
        }
        checkEq(StageSupport.STATIC_ONLY,
            LlvmStageClassifier.boundaryKind(BoundaryKind.VARIABLE_DECLARATION),
            "a variable-declaration boundary is statically proven");
        checkEq(StageSupport.REJECTED,
            LlvmStageClassifier.boundaryKind(BoundaryKind.HOST_TO_DEAL),
            "a host crossing boundary is outside the stage");
        checkEq(StageSupport.REJECTED,
            LlvmStageClassifier.boundaryKind(BoundaryKind.ASYNC_COMPLETION),
            "an async completion boundary is outside the stage");
        checkEq(StageSupport.REJECTED,
            LlvmStageClassifier.boundaryKind(BoundaryKind.STDLIB_PARAMETER),
            "a stdlib boundary is outside the stage");
        checkEq(StageSupport.STATIC_ONLY,
            LlvmStageClassifier.callMode(CallMode.DIRECT),
            "a direct call is statically resolved");
        checkEq(StageSupport.REJECTED,
            LlvmStageClassifier.callMode(CallMode.HOST),
            "a host call needs the crossing machinery");
        checkEq(StageSupport.REJECTED,
            LlvmStageClassifier.callMode(CallMode.INDIRECT),
            "an indirect call needs the function-value carrier");
    }

    // =========================================================================
    // 4. Rejections through the production arm carry the op origin
    // =========================================================================

    private static void testUnsupportedFactsCarryOrigins() throws Exception {
        System.out.println("-- a rejected fact is one origin-bearing E6005 --");
        Map<String, String> fixtures = new LinkedHashMap<>();
        fixtures.put("an array literal", """
            export function main(): null {
              let values: number[] = [1, 2];
              return null;
            }
            """);
        fixtures.put("a record literal", """
            export function main(): null {
              let record: Record<string, unknown> = { a: 1 };
              return null;
            }
            """);
        fixtures.put("a class instance", """
            class Point {
              x: number;
            }
            export function main(): null {
              let point: Point = { x: 1 };
              return null;
            }
            """);
        fixtures.put("a string concatenation", """
            export function main(): null {
              let text: string = "a" + "b";
              return null;
            }
            """);
        fixtures.put("a stdlib call", """
            import * as c from "std/console";
            export function main(): null {
              c.log("hello");
              return null;
            }
            """);
        fixtures.put("an async function", """
            async function value(): Promise<number> {
              return 1;
            }
            export function main(): null {
              return null;
            }
            """);
        fixtures.put("a thrown error", """
            export function main(): null {
              throw { code: "E_X", message: "boom" };
            }
            """);
        fixtures.put("a for-of loop", """
            export function main(): null {
              let values: number[] = [1];
              for (let value of values) {
                let copy: number = value;
              }
              return null;
            }
            """);
        Path scratch = NativeCompiler.executableScratch("llvm-classifier");
        try {
            for (Map.Entry<String, String> fixture : fixtures.entrySet()) {
                Path root = scratch.resolve(fixture.getKey()
                    .replaceAll("[^a-z]+", "-"));
                writeProject(root, fixture.getValue());
                CompileOutcome outcome = compile(root);
                check(!outcome.success(), "the " + fixture.getKey()
                    + " project fails closed: "
                    + (!outcome.success() ? "" : outcome.stdout()));
                if (outcome.diagnostics().isEmpty()) {
                    check(false, "the " + fixture.getKey()
                        + " failure carries a diagnostic");
                    continue;
                }
                CompilerDiagnostic diagnostic = outcome.diagnostics().get(0);
                checkEq("E6005", diagnostic.code(), "the " + fixture.getKey()
                    + " rejection is one E6005");
                DiagnosticRange range = diagnostic.range();
                // The frontend rejects some fixtures before emission; then the
                // diagnostic is the preserved frontend error, which is equally
                // fail-closed and never a native-only acceptance.
                if ("E6005".equals(diagnostic.code())) {
                    check(range != null && range.file().contains("main.deal"),
                        "the " + fixture.getKey()
                            + " rejection names the offending source file");
                    check(range != null && range.startLine() >= 1,
                        "the " + fixture.getKey()
                            + " rejection carries a one-based line");
                }
            }
            // The scalar project's own facts are all staged.
            Path supported = scratch.resolve("supported");
            Files.createDirectories(supported.resolve("src"));
            Files.writeString(supported.resolve("deal.json"),
                "{\"languageVersion\": \"1.5\", \"moduleRoots\": [\"src\"],"
                    + " \"backend\": \"llvm\"}",
                StandardCharsets.UTF_8);
            for (Map.Entry<String, String> source : SUPPORTED_SOURCE.entrySet()) {
                Files.writeString(supported.resolve(source.getKey()),
                    source.getValue(), StandardCharsets.UTF_8);
            }
            CompileOutcome outcome = compile(supported);
            check(outcome.success(), "the scalar project is stage-compatible: "
                + outcome.diagnostics());
        } finally {
            deleteRecursively(scratch);
        }
    }

    // =========================================================================
    // The compile path and fixtures
    // =========================================================================

    private record CompileOutcome(boolean success,
                                  List<CompilerDiagnostic> diagnostics,
                                  String stdout, String stderr) {
    }

    private static CompileOutcome compile(Path root) throws IOException {
        Path entry = root.resolve("src/main.deal");
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            entry.toAbsolutePath().toString(),
            new CliOverrides("llvm", root.resolve("build/llvm").toString()));
        if (located.context() == null) {
            return new CompileOutcome(false, List.of(), "",
                String.valueOf(located.cliDiagnostic()));
        }
        ProjectContext context = located.context();
        CompilerInvocation invocation = CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            context, entry.toAbsolutePath().normalize(), false, false, false,
            false, null, invocation);
        boolean success = orchestrator.compile();
        return new CompileOutcome(success, orchestrator.diagnostics(), "",
            "");
    }

    private static void writeProject(Path root, String source) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("deal.json"),
            "{\"languageVersion\": \"1.5\", \"moduleRoots\": [\"src\"],"
                + " \"backend\": \"llvm\"}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/main.deal"), source,
            StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void checkEq(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual),
            message + " (expected " + expected + ", got " + actual + ")");
    }
}
