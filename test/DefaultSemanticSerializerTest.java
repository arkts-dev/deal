package deal.test;

import deal.ast.ClassDeclaration;
import deal.ast.LiteralValue;
import deal.ast.ProgramNode;
import deal.ast.StatementNode;
import deal.checker.CheckResult;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalClassIdentityIndex;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.module.CompilationOrchestrator;
import deal.module.CompilerClassDefaultEntry;
import deal.module.CompilerClassDefaultPlan;
import deal.module.DefaultIrNode;
import deal.module.DefaultResourceOccurrence;
import deal.module.DefaultSerializerModuleInput;
import deal.module.DefaultSemanticSerializer;
import deal.module.LexicalDeclarationIdentity;
import deal.module.PlannedDefaultClass;
import deal.module.ModuleDependencyGraph;
import deal.module.DefaultPlanImport;
import deal.module.RuntimeResourceReference;
import deal.module.SemanticModuleIdentity;
import deal.module.SemanticResourceIdentity;
import deal.module.SourceModuleLocation;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.project.ProjectDeploymentIdentity;
import deal.project.ProjectLocator;
import deal.semantic.CompilerInvocation;
import deal.types.Type;
import deal.types.Types;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DefaultSemanticSerializerTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void write(Path root, String rel, String content)
            throws Exception {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) {
                return;
            }
            Files.walk(dir).sorted(Comparator.reverseOrder())
                .forEach(f -> {
                    try {
                        Files.deleteIfExists(f);
                    } catch (Exception ignored) {
                    }
                });
        } catch (Exception ignored) {
        }
    }

    private static CompilerInvocation invocation() {
        return ConformanceHarnessMetadata.invocation();
    }

    /** One in-process production compile over a scratch project; a
     * recompile rewrites the same root and runs a fresh compile, so
     * the deployment identity (and therefore every derived identity
     * and digest) stays byte-stable across variants. */
    private static final class Compile {
        final Path root;
        final Path entry;
        final CompilationOrchestrator orchestrator;
        final boolean success;
        final List<CompilerDiagnostic> diagnostics;
        private DefaultSemanticSerializer.Result serializedSnapshot;

        Compile(Map<String, String> files, String entryRel)
                throws Exception {
            root = Files.createTempDirectory("deal_ser_");
            for (Map.Entry<String, String> file : files.entrySet()) {
                write(root, file.getKey(), file.getValue());
            }
            entry = root.resolve(entryRel);
            ProjectLocator.LocateResult located =
                ProjectLocator.locate(entry.toString(), null);
            if (located.context() == null) {
                throw new IllegalStateException(
                    "ProjectLocator failed: " + located.e2010());
            }
            orchestrator = new CompilationOrchestrator(
                located.context(), entry, false, false, false, false,
                null, invocation());
            success = orchestrator.compile();
            diagnostics = orchestrator.diagnostics();
        }

        private Compile(Path root, Path entry) throws Exception {
            this.root = root;
            this.entry = entry;
            ProjectLocator.LocateResult located =
                ProjectLocator.locate(entry.toString(), null);
            if (located.context() == null) {
                throw new IllegalStateException(
                    "ProjectLocator failed: " + located.e2010());
            }
            orchestrator = new CompilationOrchestrator(
                located.context(), entry, false, false, false, false,
                null, invocation());
            success = orchestrator.compile();
            diagnostics = orchestrator.diagnostics();
        }

        /** Rewrites the same scratch root and recompiles in place
         * (same deployment identity). */
        Compile recompile(Map<String, String> files) throws Exception {
            for (Map.Entry<String, String> file : files.entrySet()) {
                write(root, file.getKey(), file.getValue());
            }
            return new Compile(root, entry);
        }

        /** Serializes the compile's plans through the serializer
         * API. */
        DefaultSemanticSerializer.Result serialize() {
            Map<String, DefaultSerializerModuleInput> inputs = orchestrator.defaultSerializerModuleInputs();
            Map<String, ModuleDependencyGraph.ModuleInput> graphInputs = new LinkedHashMap<>();
            Map<String, DefaultPlanImport> providers = new LinkedHashMap<>();
            for (var input : inputs.values()) {
                graphInputs.put(input.sourcePath(), new ModuleDependencyGraph.ModuleInput(
                    input.sourcePath(), input.modulePath(), input.program(), input.location(),
                    input.checkResult(), input.nameResolver(), input.imports(), input.classification(),
                    input.isDeclarationFile()));
                providers.put(input.modulePath(), new DefaultPlanImport(input.sourcePath(), input.modulePath(),
                    input.program(), input.location(), input.exports(),
                    input.checkResult() == null ? null : input.checkResult().symbolTable(), input.isDeclarationFile()));
            }
            var free = ModuleDependencyGraph.digestFreePass(graphInputs, orchestrator.plannedDefaultClasses(),
                Map.of(), Map.of(), providers);
            if (free.failed()) throw new AssertionError(free.diagnostics());
            return DefaultSemanticSerializer.serialize(inputs, orchestrator.plannedDefaultClasses(),
                free.ordinaryOccurrences(), providers);
        }

        synchronized DefaultSemanticSerializer.Result serializationSnapshot() {
            if (serializedSnapshot == null) {
                serializedSnapshot = serialize();
            }
            return serializedSnapshot;
        }

        synchronized void forgetSerializationSnapshot() {
            serializedSnapshot = null;
        }

        /** The serialized class with the given simple name in the
         * given module (path suffix), or null. */
        DefaultSemanticSerializer.SerializedDefaultClass serializedOf(
                String sourceSuffix, String className) {
            DefaultSemanticSerializer.Result result = serializationSnapshot();
            for (Map.Entry<String, List<DefaultSemanticSerializer
                    .SerializedDefaultClass>> entry
                    : result.serializedClasses().entrySet()) {
                if (entry.getKey().endsWith(sourceSuffix)) {
                    for (DefaultSemanticSerializer.SerializedDefaultClass
                            cls : entry.getValue()) {
                        if (cls.completedPlan().classIdentity()
                                .className().equals(className)) {
                            return cls;
                        }
                    }
                }
            }
            return null;
        }

        /** The provider digest of the given declared name in the
         * module whose path ends with the given suffix, or null. */
        String providerDigestOf(String sourceSuffix, String declaredName) {
            DefaultSemanticSerializer.Result result = serializationSnapshot();
            for (Map.Entry<SemanticResourceIdentity, String> entry
                    : result.providerDigests().entrySet()) {
                if (entry.getKey().semanticModuleIdentity()
                        .canonicalResolvedSourceUri().endsWith(sourceSuffix)
                        && entry.getKey().lexicalDeclarationIdentity()
                            .declaredName().equals(declaredName)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        /** The provider canonical content of the given declared name
         * in the module whose path ends with the given suffix, or
         * null. */
        String providerContentOf(String sourceSuffix, String declaredName) {
            DefaultSemanticSerializer.Result result = serializationSnapshot();
            for (Map.Entry<SemanticResourceIdentity, String> entry
                    : result.providerContents().entrySet()) {
                if (entry.getKey().semanticModuleIdentity()
                        .canonicalResolvedSourceUri().endsWith(sourceSuffix)
                        && entry.getKey().lexicalDeclarationIdentity()
                            .declaredName().equals(declaredName)) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }

    private static final String MANIFEST = """
        {"languageVersion":"1.5","moduleRoots":["src"],"backend":"luajit","output":"build"}
        """;

    private static String digestOf(String content) {
        return RuntimeResourceReference.providerContractDigestOf(content);
    }

    private static void successful(Compile compile) {
        check(compile.success, "production compilation succeeds: " + compile.diagnostics);
        if (!compile.success) throw new AssertionError(compile.diagnostics);
        var result = compile.serializationSnapshot();
        check(!result.hasErrors(), "structural classes and ordinary providers serialize: " + result.diagnostics());
        for (var entry : result.providerContents().entrySet()) {
            check(digestOf(entry.getValue()).equals(result.providerDigests().get(entry.getKey())),
                "provider digest is derived from full canonical content");
            check(!entry.getValue().contains("file:"), "canonical content contains no deployment path");
        }
    }

    private static Compile project(String main, String lib) throws Exception {
        return new Compile(Map.of("deal.json", MANIFEST, "src/main.deal", main, "src/lib.deal", lib), "src/main.deal");
    }

    private static final String EXPRESSIONS = """
        import * as L from "./lib";
        export function main(): null { let n: number = L.expressions(); return null; }
        """;
    private static final String EXPRESSION_PROVIDER = """
        class HasBox { tag?: number; }
        function helper(v: number): number { return v; }
        function label(): string { return "v"; }
        function tableOf(): Record<string, unknown> { return {k: 5}; }
        function arrayOf(): number[] { return [1, 2]; }
        function box(): HasBox { return {tag: 1}; }
        async function source(): Promise<number> { return 3; }
        export function expressions(): number {
          let a: number = 1 + 2;
          let b: number = -3;
          let c: number[] = [1, 2];
          let d: Record<string, unknown> = {k: 5};
          let e: string = `v=${label()}`;
          let f: number = tableOf().k as number;
          let g: number = arrayOf()[0];
          let h: boolean = Object.hasOwn(box(), "tag");
          let i: number = helper(2);
          let j: (x: number) => number = function(x: number): number { return x; };
          let l: number = (function(x: number): number { return (x = 2); })(1);
          let m: () => Promise<number> = async function(): Promise<number> { return await source(); };
          return a;
        }
        """;

    private static void testExpressionAndStatementTotality() throws Exception {
        Compile compile = project(EXPRESSIONS, EXPRESSION_PROVIDER);
        try {
            successful(compile);
            String content = compile.providerContentOf("lib.deal", "expressions");
            for (String kind : List.of("ARRAY_LITERAL", "ASSIGNMENT", "AWAIT", "BINARY", "CALL",
                    "FUNCTION_EXPRESSION", "HAS", "IDENTIFIER", "INDEX", "LITERAL", "MEMBER_ACCESS",
                    "OBJECT_LITERAL", "TEMPLATE_LITERAL", "UNARY")) {
                check(content.contains("\"kind\":\"" + kind + "\""), "ordinary expression grammar covers " + kind);
            }
            String statements = """
                class LocalBox { n: number; }
                export function allStatements(n: number, arr: number[]): number {
                  let total: number = 0;
                  { let local: number = 1; total = total + local; }
                  total = total + 1;
                  if (n > 0) { total = total + 1; } else { total = total - 1; }
                  let i: number = 0;
                  while (i < 2) { i = i + 1; if (i === 1) { continue; } break; }
                  for (let value of arr) { total = total + value; }
                  let t: Record<string, unknown> = {k: 1};
                  delete t.k;
                  try { if (total < 0) { throw {code: "BAD", message: "bad"}; } }
                  catch (e) { total = 0; }
                  function innerFn(v: number): number { return v + 1; }
                  let nested: (x: number) => number = function(x: number): number {
                    let shadow: number = x; return shadow;
                  };
                  let box: LocalBox = {n: 1};
                  total = total + innerFn(1) + nested(box.n);
                  return total;
                }
                """;
            Compile body = compile.recompile(Map.of("src/lib.deal", statements, "src/main.deal", """
                import * as L from "./lib";
                export function main(): null { let n: number = L.allStatements(1, [1, 2]); return null; }
                """));
            successful(body);
            String all = body.providerContentOf("lib.deal", "allStatements");
            for (String kind : List.of("BLOCK", "BREAK", "CONTINUE", "DELETE", "EXPRESSION_STATEMENT",
                    "FOR_OF", "FUNCTION_DECLARATION", "IF", "RETURN", "THROW", "TRY", "VARIABLE_DECLARATION", "WHILE")) {
                check(all.contains("\"kind\":\"" + kind + "\""), "ordinary statement grammar covers " + kind);
            }
            for (String misplaced : List.of("function bad(): null { class Local { n: number; } return null; }",
                    "function bad(): null { import * as L from \"./lib\"; return null; }")) {
                LexResult lex = new Lexer(misplaced, "placement.deal").tokenize();
                ParseResult parsed = new Parser(lex.tokens(), "placement.deal", lex.directiveEvents()).parse();
                check(!deal.module.ModuleShapeValidator.validate(parsed.program(), "placement.deal", false).isEmpty(),
                    "invalid local declaration placement remains rejected");
            }
        } finally { deleteRecursively(compile.root); }
    }

    private static final String FUNCTION_MAIN = """
        import * as L from "./lib";
        class Consumer { n: number; }
        export function main(): null { let c: Consumer = {n: L.outer(1)}; return null; }
        """;
    private static final String FUNCTION_LIB = """
        import * as S from "./other";
        export function inner1(v: number): number { return v + 1; }
        export function inner2(v: number): number { return v + 2; }
        export function outer(x: number): number {
          let s = S.scale(x);
          return s + inner1(x) + inner2(x);
        }
        """;
    private static Map<String, String> functionFiles(String lib) {
        return Map.of("deal.json", MANIFEST, "src/main.deal", FUNCTION_MAIN, "src/lib.deal", lib,
            "src/other.deal", "export function scale(v: number): number { return v * 2; }");
    }

    private static void testProviderVersioningAndSnapshotIsolation() throws Exception {
        Compile first = new Compile(functionFiles(FUNCTION_LIB), "src/main.deal");
        try {
            successful(first);
            var snapshot = first.serializationSnapshot();
            String content = first.providerContentOf("lib.deal", "outer");
            String digest = first.providerDigestOf("lib.deal", "outer");
            check(digest.matches("[0-9a-f]{64}") && content.contains("\"serializerVersion\":\"1\""),
                "provider content has the canonical serializer version and SHA-256 digest");
            check(first.serializationSnapshot() == snapshot, "one compile reuses its lazy snapshot");
            var fresh = first.serialize();
            check(fresh != snapshot && fresh.equals(snapshot), "explicit serialization is fresh and content-equal");
            check(first.serializationSnapshot() == snapshot, "explicit serialization cannot replace the cached snapshot");
            first.forgetSerializationSnapshot();
            var refreshed = first.serializationSnapshot();
            check(refreshed != snapshot && refreshed != fresh && refreshed.equals(snapshot)
                    && first.serializationSnapshot() == refreshed, "cache invalidation rebuilds equal independent facts");
            Compile second = first.recompile(functionFiles(FUNCTION_LIB));
            successful(second);
            check(second.serializationSnapshot() != refreshed && first.serializationSnapshot() == refreshed,
                "same-root compilation owns an independent snapshot");
            check(second.serializationSnapshot().equals(snapshot), "unchanged deployment preserves every canonical plan and provider");
        } finally { deleteRecursively(first.root); }
    }

    private static void testFunctionProviderSensitivity() throws Exception {
        Compile base = new Compile(functionFiles(FUNCTION_LIB), "src/main.deal");
        try {
            successful(base);
            String content = base.providerContentOf("lib.deal", "outer");
            String digest = base.providerDigestOf("lib.deal", "outer");
            String classDigest = base.serializedOf("main.deal", "Consumer").planDigest();
            for (String variant : List.of(FUNCTION_LIB.replace("let s =", "let extra = 1; let s ="),
                    FUNCTION_LIB.replace("inner1(x)", "inner2(x + 1)"),
                    FUNCTION_LIB.replace("s + inner1(x)", "s - inner1(x)"),
                    FUNCTION_LIB.replace("inner1(x)", "inner1(9)"),
                    FUNCTION_LIB.replace("let s =", "let s: number ="))) {
                Compile changed = base.recompile(functionFiles(variant));
                successful(changed);
                check(!digest.equals(changed.providerDigestOf("lib.deal", "outer")),
                    "ordinary statement, literal, operator, annotation or call-target change changes provider digest");
                check(classDigest.equals(changed.serializedOf("main.deal", "Consumer").planDigest()),
                    "structural class metadata is independent of executable consumer expressions");
            }
            Map<String, String> embeddedFiles = new LinkedHashMap<>(functionFiles(FUNCTION_LIB));
            embeddedFiles.put("src/other.deal", "export function scale(v: number): number { return v * 3; }");
            Compile embedded = base.recompile(embeddedFiles);
            successful(embedded);
            check(!base.providerDigestOf("other.deal", "scale").equals(embedded.providerDigestOf("other.deal", "scale")),
                "imported provider body changes its own digest");
            check(!digest.equals(embedded.providerDigestOf("lib.deal", "outer")),
                "caller embeds imported provider digest");
            Compile movedReference = base.recompile(functionFiles(FUNCTION_LIB.replace("let s = S.scale(x);", "let s =\n    S.scale(x);")));
            successful(movedReference);
            check(!content.equals(movedReference.providerContentOf("lib.deal", "outer")),
                "imported behavior-affecting reference range is serialized");
            Compile movedLocal = base.recompile(functionFiles(FUNCTION_LIB.replace("+ inner2(x)", "\n    + inner2(x)")));
            successful(movedLocal);
            check(content.equals(movedLocal.providerContentOf("lib.deal", "outer")),
                "non-behavior-affecting same-module call range is ignored");
            Compile localBody = base.recompile(functionFiles(FUNCTION_LIB.replace("v + 2", "v + 3")));
            successful(localBody);
            check(digest.equals(localBody.providerDigestOf("lib.deal", "outer")),
                "same-module callee body is identity-only in its caller");
            check(localBody.providerDigestOf("lib.deal", "inner2") == null,
                "undemanded same-module callee has no standalone provider digest");
            Compile repeat = base.recompile(functionFiles(FUNCTION_LIB));
            successful(repeat);
            check(content.equals(repeat.providerContentOf("lib.deal", "outer"))
                    && digest.equals(repeat.providerDigestOf("lib.deal", "outer")),
                "same-deployment repeat has equal full content and digest");
        } finally { deleteRecursively(base.root); }
    }

    private static void testStructuralClassPlans() throws Exception {
        String main = """
            import * as L from "./lib";
            export class Row { a: number; b?: string; c: number | null; }
            export function main(): null { let r: Row = {a: L.pick(), c: null}; return null; }
            """;
        Compile base = project(main, "export function pick(): number { return 1; }");
        try {
            successful(base);
            var record = base.serializedOf("main.deal", "Row");
            check(record.completedPlan().orderedFields().stream().map(CompilerClassDefaultEntry::name).toList()
                    .equals(List.of("a", "b", "c")), "structural entries retain declaration order");
            check(record.completedPlan().orderedFields().get(1).optional()
                    && !record.completedPlan().orderedFields().get(2).optional(),
                "optional and required nullable remain distinct in metadata");
            check(record.completedPlan().runtimeDependencies().isEmpty(), "class annotations own no executable resources");
            check(!record.canonicalPlanContent().contains("canonicalDefaultContent")
                    && !record.semanticDefaultContents().contains("canonicalDefaultContent"),
                "structural metadata projections contain no retired initializer placeholders");
            check(record.planDigest().equals(digestOf(record.canonicalPlanContent())), "class digest covers full canonical content");
            for (String changed : List.of(main.replace("a: number; b?: string;", "b?: string; a: number;"),
                    main.replace("b?: string;", "b?: number;"), main.replace("b?: string;", "b: string;"))) {
                if (changed.contains("b: string;")) changed = changed.replace("c: null}", "c: null, b: \"x\"}");
                Compile variant = base.recompile(Map.of("src/main.deal", changed));
                successful(variant);
                check(!record.planDigest().equals(variant.serializedOf("main.deal", "Row").planDigest()),
                    "field order, descriptor and optionality independently change structural digest");
            }
            Compile moved = base.recompile(Map.of("src/main.deal", main.replace("export class", "\nexport class")));
            successful(moved);
            check(record.canonicalPlanContent().equals(moved.serializedOf("main.deal", "Row").canonicalPlanContent()),
                "annotation-only source movement does not serialize executable ranges");
            Compile provider = base.recompile(Map.of("src/main.deal", main, "src/lib.deal", "export function pick(): number { return 2; }"));
            successful(provider);
            check(record.planDigest().equals(provider.serializedOf("main.deal", "Row").planDigest()),
                "ordinary provider change never gives a structural class executable defaults");
            for (String initializer : List.of("1", "L.pick()", "function(): number { return 1; }")) {
                Compile rejected = base.recompile(Map.of("src/main.deal", main.replace("a: number;", "a: number = " + initializer + ";")));
                check(!rejected.success && rejected.diagnostics.stream().anyMatch(d -> d.code().equals("E1007")),
                    "initializer syntax never reaches canonical serialization");
            }
        } finally { deleteRecursively(base.root); }
    }

    private static void testOrdinaryResourceCompletion() throws Exception {
        Compile compile = new Compile(Map.of("deal.json", MANIFEST, "src/main.deal", """
            import * as C from "./consumer";
            export function main(): null {let n: number = C.run(); return null;}
            """, "src/consumer.deal", """
            import * as L from "./lib";
            import * as S from "../shared";
            function localHelper(): number {return 7;}
            export function run(): number {
              let a: number = localHelper();
              let b: L.Rec = {tag: 1};
              let c: L.Rec[] = [L.makeRec({tag: 5})];
              let d: L.Rec[] = [];
              let e: L.Rec | null = null;
              let f: number = L.getTag({tag: 3});
              let g: number = S.scale(2);
              let h: L.Rec = {tag: 8, inner: {tag: 9}};
              return a + b.tag + c[0].tag + f + g + h.tag;
            }
            """, "src/lib.deal", """
            export class Rec {tag: number; inner?: Rec;}
            export function getTag(r: Rec): number {return r.tag;}
            export function makeRec(r: Rec): Rec {return r;}
            """, "shared.deal", "export function scale(v: number): number {return v * 2;}"), "src/main.deal");
        try {
            successful(compile);
            var result = compile.serializationSnapshot();
            for (String[] provider : List.of(new String[]{"lib.deal", "Rec"}, new String[]{"lib.deal", "makeRec"},
                    new String[]{"lib.deal", "getTag"}, new String[]{"shared.deal", "scale"})) {
                String digest = compile.providerDigestOf(provider[0], provider[1]);
                check(digest != null && digest.matches("[0-9a-f]{64}"), "ordinary use demands exact provider " + provider[1]);
                check(compile.providerContentOf("consumer.deal", "run").contains(digest),
                    "caller content embeds the completed provider digest for " + provider[1]);
            }
            check(result.providerDigests().size() == 5, "exactly one caller and four distinct imported resources are demanded");
            check(compile.providerDigestOf("consumer.deal", "localHelper") == null,
                "same-module call remains identity-only without a standalone digest");
            check(compile.providerDigestOf("lib.deal", "Rec").equals(compile.serializedOf("lib.deal", "Rec").planDigest()),
                "imported construction embeds its exact structural provider digest");
            check(compile.serializedOf("lib.deal", "Rec").completedPlan().runtimeDependencies().isEmpty(),
                "recursive optional annotation creates no executable class dependency");
            List<String> resourceNames = new ArrayList<>();
            for (var dependency : compile.orchestrator.runtimeDependencies()) {
                if (dependency.fromSemanticModuleIdentity().canonicalResolvedSourceUri().endsWith("consumer.deal")) {
                    resourceNames.add(dependency.semanticResourceIdentity().lexicalDeclarationIdentity().declaredName());
                }
            }
            check(resourceNames.equals(List.of("Rec", "makeRec", "getTag", "scale")),
                "ordinary resource completion preserves first-occurrence order without duplicate nested class demands: " + resourceNames);
        } finally { deleteRecursively(compile.root); }
    }

    private static void testHostAndJsonWrapperProviders() throws Exception {
        String manifest = """
            {"languageVersion":"1.5","moduleRoots":["src"],"backend":"luajit","output":"build",
             "externals":{"host/cfg":{"declaration":"bindings/cfg.d.deal"}}}
            """;
        Compile compile = new Compile(Map.of("deal.json", manifest, "src/main.deal", """
            import * as cfg from "host/cfg";
            import * as L from "./lib";
            export function main(): null {
              let e: cfg.Endpoint = {};
              let d: string = cfg.describe(e);
              let p: L.Point | null = L.Point$fromJson("{\\\"x\\\":1}");
              let text: string = L.Point$toJson({x: 2});
              return null;
            }
            """, "src/lib.deal", "// @jsonable\nexport class Point {x: number;}",
            "bindings/cfg.d.deal", "export class Endpoint {path?: string;}\nexport function describe(e: Endpoint): string;"), "src/main.deal");
        try {
            successful(compile);
            for (String name : List.of("Point$fromJson", "Point$toJson")) {
                String content = compile.providerContentOf("lib.deal", name);
                check(content != null && content.contains("SYNTHETIC_JSONABLE_EXPORT_SIGNATURE"),
                    "synthetic JSON helper carries its exact generated signature: " + name);
            }
            String content = compile.providerContentOf("cfg.d.deal", "describe");
            check(content != null && content.contains("DECLARED_FUNCTION_SIGNATURE")
                    && content.contains("semanticResourceIdentityDigest"), "host wrapper carries exact declared identity");
            check(compile.providerDigestOf("cfg.d.deal", "Endpoint") == null,
                "host class construction uses identity, never executable provider content");
        } finally { deleteRecursively(compile.root); }
    }

    private static void testScopedAndImportedProviders() throws Exception {
        Compile compile = project("""
            import * as L from "./lib";
            class Row {text: string;}
            export function main(): null { let n: number = L.nested(2); return null; }
            """, """
            class Row {n: number;}
            export function nested(n: number): number {
              let value: number = n;
              function inner(value: number): number { return value + 1; }
              let f: (value: number) => number = function(value: number): number {
                let copy: number = value; return inner(copy);
              };
              let record: Row = {n: f(value)};
              return record.n;
            }
            """);
        try {
            successful(compile);
            String content = compile.providerContentOf("lib.deal", "nested");
            check(content.contains("FUNCTION_DECLARATION") && content.contains("FUNCTION_EXPRESSION"),
                "nested declarations and expression bodies serialize inline in defining scope");
            check(content.contains("@src/lib.deal/Row") && !content.contains("@src/main.deal/Row"),
                "same-spelled class target resolves the function provider's own declaration");
            var result = compile.serializationSnapshot();
            check(result.providerContents().keySet().stream().anyMatch(key -> key.lexicalDeclarationIdentity().declaredName().equals("nested")),
                "ordinary function is demanded despite no class initializer");
            check(compile.serialize().equals(result), "serializer repeat is deterministic and immutable");
            Compile moved = compile.recompile(Map.of("src/main.deal", """
                import * as L from "./other";
                export function main(): null { let n: number = L.pick(); return null; }
                """, "src/other.deal", "export function pick(): number {return 7;}"));
            successful(moved);
            String other = moved.providerContentOf("other.deal", "pick");
            Compile relocated = moved.recompile(Map.of("src/main.deal", """
                import * as L from "./relocated";
                export function main(): null { let n: number = L.pick(); return null; }
                """, "src/relocated.deal", "export function pick(): number {return 7;}"));
            successful(relocated);
            check(other.equals(relocated.providerContentOf("relocated.deal", "pick")),
                "identical path-free function bodies remain equal across provider addresses");
            check(!moved.serializationSnapshot().providerDigests().keySet().equals(relocated.serializationSnapshot().providerDigests().keySet()),
                "provider resource identity changes when its defining file moves");
        } finally { deleteRecursively(compile.root); }
    }

    private static void testOutOfRootAndBrokenOccurrences() throws Exception {
        Compile compile = new Compile(Map.of("deal.json", MANIFEST, "src/main.deal", """
            import * as L from "../outside";
            export class Outer {n: number;}
            export function main(): null {let n: number = L.pick(); return null;}
            """, "outside.deal", "export function pick(): number {return 7;}"), "src/main.deal");
        try {
            successful(compile);
            check(compile.providerContentOf("outside.deal", "pick") != null, "out-of-root ordinary provider has real content");
            var plans = compile.orchestrator.plannedDefaultClasses();
            String key = plans.keySet().stream().filter(path -> path.endsWith("main.deal")).findFirst().orElseThrow();
            PlannedDefaultClass planned = plans.get(key).get(0);
            var provider = compile.serializationSnapshot().providerContents().keySet().stream()
                .filter(identity -> identity.lexicalDeclarationIdentity().declaredName().equals("pick")).findFirst().orElseThrow();
            var range = planned.declaration().span().range();
            var occurrence = new DefaultResourceOccurrence(RuntimeResourceReference.Kind.IMPORTED_FUNCTION_WRAPPER,
                planned.plan().declaringSemanticModuleIdentity(), provider.semanticModuleIdentity(), "L", provider, range);
            // Structural declarations cannot acquire executable occurrence records of any kind, identity or range.
            for (DefaultResourceOccurrence bad : List.of(occurrence,
                    new DefaultResourceOccurrence(RuntimeResourceReference.Kind.IMPORTED_CLASS_DEFAULT_PLAN,
                        occurrence.fromSemanticModuleIdentity(), occurrence.toSemanticModuleIdentity(), "L", provider, range),
                    new DefaultResourceOccurrence(occurrence.kind(), occurrence.fromSemanticModuleIdentity(), occurrence.toSemanticModuleIdentity(),
                        "L", provider, DiagnosticRange.synthetic("elsewhere.deal")))) {
                Map<String, List<PlannedDefaultClass>> corrupted = new LinkedHashMap<>(plans);
                corrupted.put(key, List.of(new PlannedDefaultClass(planned.plan(), planned.declaration(), List.of(bad))));
                var failed = DefaultSemanticSerializer.serialize(compile.orchestrator.defaultSerializerModuleInputs(), corrupted);
                check(failed.hasErrors() && failed.serializedClasses().isEmpty() && failed.providerDigests().isEmpty()
                        && failed.providerContents().isEmpty() && failed.diagnostics().get(0).code().equals("E6005")
                        && failed.diagnostics().get(0).message().contains("broken occurrence data"),
                    "malformed structural occurrence fails transactionally with E6005");
            }
            Map<String, DefaultSerializerModuleInput> missing = new LinkedHashMap<>(compile.orchestrator.defaultSerializerModuleInputs());
            missing.remove(key);
            var failed = DefaultSemanticSerializer.serialize(missing, plans);
            check(failed.hasErrors() && failed.providerContents().isEmpty() && failed.serializedClasses().isEmpty(),
                "missing module input cannot publish partial canonical plans");
        } finally { deleteRecursively(compile.root); }
    }

    public static void main(String[] args) throws Exception {
        testExpressionAndStatementTotality();
        testProviderVersioningAndSnapshotIsolation();
        testFunctionProviderSensitivity();
        testStructuralClassPlans();
        testOrdinaryResourceCompletion();
        testHostAndJsonWrapperProviders();
        testScopedAndImportedProviders();
        testOutOfRootAndBrokenOccurrences();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed != 0) System.exit(1);
    }
}
