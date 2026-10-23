package deal.test;

import deal.diagnostics.CompilerDiagnostic;
import deal.module.CompilationOrchestrator;
import deal.module.CompilerClassDefaultEntry;
import deal.module.CompilerClassDefaultPlan;
import deal.module.PlannedDefaultClass;
import deal.project.ProjectLocator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Structural class planning through the production orchestration path. */
public final class DefaultSemanticPlannerTest {
    private static int passed;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }

    private static final String MANIFEST = """
        {"languageVersion":"1.3","moduleRoots":["src"],
         "backend":"luajit","output":"build"}
        """;

    private record Compile(Path root, CompilationOrchestrator orchestrator, boolean success) {
        List<CompilerDiagnostic> diagnostics() { return orchestrator.diagnostics(); }
        CompilerDiagnostic error(String code) {
            return diagnostics().stream().filter(d -> code.equals(d.code()))
                .findFirst().orElse(null);
        }
        PlannedDefaultClass planned(String suffix, String name) {
            return orchestrator.plannedDefaultClasses().entrySet().stream()
                .filter(e -> e.getKey().endsWith(suffix)).flatMap(e -> e.getValue().stream())
                .filter(p -> p.plan().classIdentity().className().equals(name))
                .findFirst().orElse(null);
        }
    }

    private static Compile compile(Map<String, String> files) throws Exception {
        Path root = Files.createTempDirectory("deal-structural-plan-");
        Files.writeString(root.resolve("deal.json"), MANIFEST);
        for (var file : files.entrySet()) {
            Path target = root.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
        return compile(root);
    }

    private static Compile compile(Path root) throws Exception {
        Path entry = root.resolve("src/main.deal");
        var located = ProjectLocator.locate(entry.toString(), null);
        check(located.context() != null, "project location: " + located.e2010());
        var owner = new CompilationOrchestrator(located.context(), entry,
            false, false, false, false, null, ConformanceHarnessMetadata.invocation());
        return new Compile(root, owner, owner.compile());
    }

    private static void structuralPlan() throws Exception {
        Compile run = compile(Map.of(
            "src/main.deal", """
                import * as L from "./lib";
                import * as S from "../shared";
                class Outer {
                  a: number;
                  b: L.Rec;
                  c: L.Rec[];
                  d?: L.Rec[];
                  e: L.Rec | null;
                  f?: number | null;
                }
                function localHelper(): number { return 7; }
                export function main(): null {
                  let rec: L.Rec = {tag: 5};
                  let outer: Outer = {a: localHelper(), b: L.makeRec(rec),
                    c: [rec], e: null};
                  let value: number = L.getTag(outer.b) + S.scale(2);
                  return null;
                }
                """,
            "src/lib.deal", """
                export class Rec { tag: number; inner?: Rec; }
                export function getTag(r: Rec): number { return r.tag; }
                export function makeRec(r: Rec): Rec { return r; }
                """,
            "shared.deal", "export function scale(v: number): number { return v * 2; }"));
        check(run.success(), "structural project: " + run.diagnostics());
        PlannedDefaultClass outer = run.planned("main.deal", "Outer");
        check(outer != null, "Outer structural plan exists");
        CompilerClassDefaultPlan plan = outer.plan();
        check(plan.orderedFields().stream().map(CompilerClassDefaultEntry::name).toList()
            .equals(List.of("a", "b", "c", "d", "e", "f")), "declaration order");
        check(plan.orderedFields().get(0).resolvedFieldType() == deal.types.Type.Number.INSTANCE,
            "number type retained");
        check(plan.orderedFields().stream().map(CompilerClassDefaultEntry::runtimeTypeDescriptor)
            .toList().equals(List.of("number", "@src/lib.deal/Rec", "[@src/lib.deal/Rec]",
                "[@src/lib.deal/Rec]", "?@src/lib.deal/Rec", "?number")),
            "canonical imported, array and nullable descriptors: " + plan.orderedFields());
        check(plan.orderedFields().stream().map(CompilerClassDefaultEntry::optional).toList()
            .equals(List.of(false, false, false, true, false, true)),
            "optional presence independent of nullable type");
        check(plan.declaringSemanticModuleIdentity().canonicalResolvedSourceUri()
            .endsWith("src/main.deal"), "declaring module identity");
        check(plan.runtimeDependencies().isEmpty() && outer.occurrences().isEmpty(),
            "structural annotation planning creates no runtime use");
        PlannedDefaultClass rec = run.planned("lib.deal", "Rec");
        check(rec != null && rec.plan().orderedFields().stream()
            .map(CompilerClassDefaultEntry::name).toList().equals(List.of("tag", "inner")),
            "provider plan retains field order");
        check(run.orchestrator().plannedDefaultClasses().entrySet().stream()
            .filter(e -> e.getKey().endsWith("shared.deal")).allMatch(e -> e.getValue().isEmpty()),
            "class-free out-of-root provider has no class plan");
        Compile again = compile(run.root());
        check(again.success(), "repeat production compile: " + again.diagnostics());
        check(again.planned("main.deal", "Outer").plan().equals(plan),
            "identical inputs produce identical structural plans");
        check(again.planned("main.deal", "Outer").occurrences().equals(outer.occurrences()),
            "identical structural occurrence facts");
    }

    private static void requiredPresence() throws Exception {
        Compile declaration = compile(Map.of("src/main.deal", """
            class Record { value: number; nullable: number | null; }
            export function main(): null { return null; }
            """));
        check(declaration.success(), "required declarations need no initializer: "
            + declaration.diagnostics());
        Compile omitted = compile(Map.of("src/main.deal", """
            class Record { value: number; nullable: number | null; }
            function make(): Record { return {value: 1}; }
            export function main(): null { return null; }
            """));
        check(!omitted.success() && omitted.error("E4001") != null,
            "required nullable omission fails: " + omitted.diagnostics());
        check(omitted.error("E4001").message()
            .equals("Missing required field 'nullable' in class literal for 'Record'"),
            "missing field diagnostic");
        check(omitted.orchestrator().plannedDefaultClasses().isEmpty(),
            "failed checking publishes no plan");
    }

    private static void hostDeclarations() throws Exception {
        Compile run = compile(Map.of(
            "deal.json", """
                {"languageVersion":"1.3","moduleRoots":["src"],"backend":"luajit",
                 "output":"build","externals":{"host/cfg":
                   {"declaration":"bindings/cfg.d.deal"}}}
                """,
            "src/main.deal", """
                import * as cfg from "host/cfg";
                export function main(): null {
                  let endpoint: cfg.Endpoint = {path: "/"};
                  let server: cfg.ServerConfig = {port: 80, endpoint: endpoint};
                  return null;
                }
                """,
            "bindings/cfg.d.deal", """
                export class Endpoint { path: string; }
                export class ServerConfig { port: number; endpoint: Endpoint; }
                export function describe(c: ServerConfig): string;
                """));
        check(run.success(), "host structural construction: " + run.diagnostics());
        check(run.planned("cfg.d.deal", "Endpoint") == null,
            "host declarations contribute no implementation plan");
    }

    private static void initializerRejection() throws Exception {
        for (String expression : List.of("1", "\"wrong\"", "await source()",
                "async function(): number { return await source(); }")) {
            Compile run = compile(Map.of("src/main.deal", """
                class Bad {
                  value: number = %s;
                }
                async function source(): number { return 3; }
                export function main(): null { return null; }
                """.formatted(expression)));
            CompilerDiagnostic rejected = run.error("E1007");
            check(!run.success() && rejected != null,
                "initializer rejected before planning: " + expression + ": " + run.diagnostics());
            check(rejected.range().startLine() == 2 && rejected.range().startColumn() == 17,
                "initializer error anchors '=': " + rejected.range());
            check(run.orchestrator().plannedDefaultClasses().isEmpty(),
                "invalid syntax creates no structural plan");
        }
        Compile local = compile(Map.of("src/main.deal", """
            async function build(): null {
              class Inner { value: number; }
              return null;
            }
            export function main(): null { return null; }
            """));
        check(!local.success() && local.error("E1057") != null,
            "local class placement remains rejected");
        check(local.error("E1057").range().startLine() == 2
            && local.error("E1057").range().startColumn() == 3,
            "local placement error retains declaration range");
        Compile async = compile(Map.of("src/main.deal", """
            class Holder { f: async () => number; }
            async function source(): number { return 3; }
            export function main(): null {
              let holder: Holder = {f: async function(): number { return await source(); }};
              return null;
            }
            """));
        check(async.success(), "ordinary provided async closure survives: " + async.diagnostics());
    }

    public static void main(String[] args) throws Exception {
        structuralPlan();
        requiredPresence();
        hostDeclarations();
        initializerRejection();
        System.out.println("Structural class planner: " + passed + " checks passed");
    }
}
