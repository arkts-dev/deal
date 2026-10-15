package deal.test;

import deal.ast.ArrayType;
import deal.ast.ClassDeclaration;
import deal.ast.ClassField;
import deal.ast.ExportDeclaration;
import deal.ast.FunctionDeclaration;
import deal.ast.FunctionType;
import deal.ast.FunctionTypeParam;
import deal.ast.ImportDeclaration;
import deal.ast.NamedType;
import deal.ast.NullableType;
import deal.ast.Parameter;
import deal.ast.ProgramNode;
import deal.ast.QualifiedType;
import deal.ast.Span;
import deal.ast.StatementNode;
import deal.ast.TypeNode;
import deal.checker.CheckResult;
import deal.checker.NameResolver;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.codegen.Backend;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.RangeOrigin;
import deal.ir.IrDumper;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.module.CompilationOrchestrator;
import deal.module.DefaultPlanImport;
import deal.module.ModuleDependencyGraph;
import deal.module.SemanticModuleIdentity;
import deal.module.SourceModuleLocation;
import deal.project.ProjectContext;
import deal.project.ProjectDeploymentIdentity;
import deal.project.ProjectLocator;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.semantic.CanonicalTypeText;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedModuleKind;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CheckedProjectBuilder;
import deal.semantic.CheckedProjectInput;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ModuleFact;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.ClassFactoryId;
import deal.semantic.ir.ClassInterface;
import deal.semantic.ir.ExportInterface;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.FieldInterface;
import deal.semantic.ir.InitializationMode;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.ResolvedImport;
import deal.types.Type;
import deal.types.Types;
import deal.test.IdentityTestFixtures;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public class CheckedProjectBuilderTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // Small helpers
    // =========================================================================

    private static Span span() {
        return new Span("test.d.deal", 1, 1, 1, 1);
    }

    private static TypeNode nt(String name) {
        return new NamedType(span(), name);
    }

    private static TypeNode arr(TypeNode element) {
        return new ArrayType(span(), element);
    }

    private static TypeNode nul(TypeNode inner) {
        return new NullableType(span(), inner);
    }

    private static TypeNode fn(List<TypeNode> params, TypeNode returnType, boolean isAsync) {
        List<FunctionTypeParam> fnParams = new ArrayList<>();
        for (TypeNode param : params) {
            fnParams.add(new FunctionTypeParam(span(), "p", param));
        }
        return new FunctionType(span(), fnParams, returnType, isAsync);
    }

    private static TypeNode qual(String alias, String name) {
        return new QualifiedType(span(), alias, name);
    }

    private static ProgramNode programOf(StatementNode... statements) {
        return new ProgramNode(span(), List.of(statements));
    }

    private static FunctionDeclaration functionDecl(String name, List<Parameter> params,
                                                    TypeNode returnType, boolean isAsync) {
        return new FunctionDeclaration(span(), name, params, returnType, null, isAsync, false);
    }

    private static CompilerInvocation invocation() {
        return CompilerProfileProvider.resolve(ReleaseState.PRE_ACTIVATION,
            CapabilityRegistry.releaseRegistry());
    }

    private static CheckResult emptyChecks() {
        return new CheckResult(Map.of(), new SymbolTable(), List.of());
    }

    private static ModuleFact implFact(String modulePath, ProgramNode ast,
                                       Map<String, Type> exports, SymbolTable table,
                                       CheckResult checks) {
        return new ModuleFact("src/" + modulePath + ".deal", new ModuleId(modulePath),
            false, false, ast, exports, table, checks, List.of());
    }

    private static ModuleFact declFact(String modulePath, ProgramNode ast,
                                       Map<String, Type> exports, ModuleFact.ImportFact... imports) {
        return new ModuleFact("src/" + modulePath + ".d.deal", new ModuleId(modulePath),
            true, false, ast, exports, null, null, List.of(imports));
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) {
                return;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (Exception e) {
            // Best-effort temp cleanup only; never part of a test result.
        }
    }

    // =========================================================================
    // 1. Host fixture: orchestrator phase 3 + builder
    // =========================================================================

    static void testHostFixtureIndexAndInput() throws Exception {
        System.out.println("-- Host declaration fixture: index entry + input population --");

        Path tmp = Files.createTempDirectory("deal-checked-project-host");
        try {
            Path modulesDir = Path.of("test/conformance/backend-runtime/modules")
                .toAbsolutePath().normalize();

            Path strippedDir = tmp.resolve("stripped");
            Files.createDirectories(strippedDir);
            for (String name : List.of("declaration-only-import-compile.deal",
                    "declaration_only_lib.d.deal")) {
                Path corpus = modulesDir.resolve(name);
                Files.writeString(strippedDir.resolve(name),
                    ConformanceHarnessMetadata.productionCorpusSource(
                        Files.readString(corpus),
                        "backend-runtime/modules/" + name));
            }
            Path entry = strippedDir.resolve("declaration-only-import-compile.deal");
            Path output = tmp.resolve("build");

            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                entry, output, false, false, false, false, Backend.LUAJIT,
                null, List.of(strippedDir),
                Path.of("std").toAbsolutePath().normalize(), null,
                CompilerProfileProvider.resolveCommonShadow(
                    deal.semantic.ir.SemanticProfile.DEAL_V1_2_INT32,
                    ReleaseState.V1_2_ACTIVE, CapabilityRegistry.releaseRegistry()));
            boolean ok = orchestrator.compile();
            check(ok, "declaration-only-import-compile.deal compiles through phase 3 + builder: "
                + orchestrator.diagnostics());

            Path prodOutput = tmp.resolve("prod-build");
            CompilationOrchestrator production = new CompilationOrchestrator(
                entry, prodOutput, false, false, false, false, Backend.LUAJIT,
                null, List.of(strippedDir),
                Path.of("std").toAbsolutePath().normalize(), null,
                CompilerProfileProvider.resolve(
                    ReleaseConfiguration.CURRENT_RELEASE_STATE,
                    ReleaseConfiguration.releaseCapabilityRegistry()));
            check(production.compile(),
                "the release-owned production invocation compiles the"
                    + " declaration-import fixture: " + production.diagnostics());
            List<CompilerDiagnostic> productionDiagnostics =
                production.diagnostics();
            check(productionDiagnostics.stream().noneMatch(diagnostic ->
                    "E6005".equals(diagnostic.code())),
                "the production compile reports no emitter gap: "
                    + productionDiagnostics);
            check(Files.exists(prodOutput.resolve(
                    "declaration-only-import-compile.lua")),
                "the production compile publishes its one project artifact"
                    + " under " + prodOutput);

            CheckedProjectBuildResult result = orchestrator.checkedProject();
            check(result != null && !result.hasErrors(),
                "the orchestrator built exactly one checked project result after phase 3");
            if (result == null) {
                return;
            }

            CheckedProjectInput input = result.input();
            ProjectInterfaceIndex index = result.index();
            check(input != null && index != null, "one input and one index per compile");

            // Input: implementation modules only; the declaration file never
            // populates the input; releaseStateHash recorded verbatim.
            check(input.releaseStateHash().equals(
                    orchestrator.invocation().releaseStateHash()),
                "the input records the invocation's derived releaseStateHash verbatim");
            List<CheckedModuleInput> modules = input.modules();
            check(modules.size() == 1,
                "the input covers exactly the one implementation module; got " + modules.size());
            CheckedModuleInput entryModule = modules.get(0);
            check(entryModule.moduleId().equals(new ModuleId("declaration-only-import-compile")),
                "the input entry carries the dotted module path");
            check(entryModule.kind() == CheckedModuleKind.IMPLEMENTATION
                    && entryModule.checks() != null,
                "the input entry is IMPLEMENTATION with checks present by construction");
            check(entryModule.imports().size() == 1, "the entry has one resolved import");
            ResolvedImport hostImport = entryModule.imports().get(0);
            check("Decl".equals(hostImport.alias())
                    && "./declaration_only_lib".equals(hostImport.modulePath())
                    && hostImport.kind() == ExternalModuleKind.HOST,
                "the importing module's ResolvedImport records alias/raw specifier/"
                    + "and kind = HOST; got " + hostImport);
            check(entryModule.exports().equals(List.of(
                    new ExportInterface("test_decl_type", "() => int"),
                    new ExportInterface("main", "() => null"))),
                "the implementation entry's exports render the Phase-3-corrected export "
                    + "map in declaration order; got " + entryModule.exports());

            Map<ModuleId, ExternalModuleInterface> indexModules = index.modules();
            List<ModuleId> indexKeys = new ArrayList<>(indexModules.keySet());
            check(indexKeys.size() == 2
                    && indexKeys.get(1).equals(
                        new ModuleId("declaration-only-import-compile")),
                "the index covers the closure in dependency order (implementation"
                    + " entry last); got " + indexKeys);
            ModuleId hostId = indexKeys.get(0);
            check(hostId.path().startsWith("m") && hostId.path().length() == 17,
                "the HOST entry's internal id is a deploymentModuleId"
                    + " (m + 16 hex); got " + hostId);
            check(hostImport.resolvedModuleId().equals(hostId),
                "the import's resolvedModuleId equals the HOST index key; got "
                    + hostImport.resolvedModuleId() + " vs " + hostId);
            ExternalModuleInterface host = indexModules.get(hostId);
            check(host != null && host.kind() == ExternalModuleKind.HOST,
                "the declaration module is a HOST index entry only (never an"
                    + " input entry)");
            check(host.exports().equals(List.of(
                    new ExportInterface("declaredAdd", "(int, int) => int"))),
                "the HOST entry carries exports = [declaredAdd: (int, int) => int] in "
                    + "declaration order; got " + host.exports());
            check(host.classes().isEmpty() && host.imports().isEmpty(),
                "the HOST entry carries classes = [] and imports = []");
            check(host.initialization() == InitializationMode.ONCE_AFTER_DEPENDENCIES,
                "the HOST entry initialization is ONCE_AFTER_DEPENDENCIES");
            ExternalModuleInterface impl = indexModules.get(
                new ModuleId("declaration-only-import-compile"));
            check(impl != null && impl.kind() == ExternalModuleKind.IMPLEMENTATION,
                "the importing module is an IMPLEMENTATION index entry");
            for (ExternalModuleInterface e : indexModules.values()) {
                check(e.kind() != ExternalModuleKind.DECLARATION,
                    "the parent-pinned DECLARATION index kind has no producer");
            }
        } finally {
            deleteRecursively(tmp);
        }
    }

    // =========================================================================
    // 2. Stdlib fixture: STDLIB index entry
    // =========================================================================

    static void testStdlibIndexEntry() throws Exception {
        System.out.println("-- Stdlib declaration fixture: STDLIB index entry --");

        Path tmp = Files.createTempDirectory("deal-checked-project-stdlib");
        try {
            Path src = tmp.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve("main.deal"),
                "import * as time from \"std/time\";\n"
                    + "export function main(): null { return null; }\n");
            Path entry = src.resolve("main.deal").toAbsolutePath();
            Path output = tmp.resolve("build");

            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                entry, output, false, null, List.of(src.toAbsolutePath()),
                Path.of("std").toAbsolutePath().normalize());
            boolean ok = orchestrator.compile();
            check(ok, "std/time-importing fixture compiles: " + orchestrator.diagnostics());
            CheckedProjectBuildResult result = orchestrator.checkedProject();
            check(result != null && !result.hasErrors(),
                "the builder succeeded on the std/time fixture");
            if (result == null) {
                return;
            }

            Map<ModuleId, ExternalModuleInterface> indexModules = result.index().modules();
            check(indexModules.containsKey(new ModuleId("std.time")),
                "the index covers the std/time declaration module");
            ExternalModuleInterface stdlib = indexModules.get(new ModuleId("std.time"));
            check(stdlib != null && stdlib.kind() == ExternalModuleKind.STDLIB,
                "std/time is a STDLIB index entry");
            check(stdlib.exports().equals(List.of(
                    new ExportInterface("nowMillis", "() => int"))),
                "the STDLIB entry carries exports = [nowMillis: () => int]; got "
                    + stdlib.exports());
            check(stdlib.imports().isEmpty() && stdlib.classes().isEmpty(),
                "the STDLIB entry carries imports = [] and classes = []");
            check(stdlib.initialization() == InitializationMode.ONCE_AFTER_DEPENDENCIES,
                "the STDLIB entry initialization is ONCE_AFTER_DEPENDENCIES");

            CheckedModuleInput entryModule = result.input().modules().get(0);
            ResolvedImport stdlibImport = entryModule.imports().get(0);
            check("time".equals(stdlibImport.alias())
                    && "std/time".equals(stdlibImport.modulePath())
                    && stdlibImport.resolvedModuleId().equals(new ModuleId("std.time"))
                    && stdlibImport.kind() == ExternalModuleKind.STDLIB,
                "the importing module's ResolvedImport records kind = STDLIB; got "
                    + stdlibImport);
        } finally {
            deleteRecursively(tmp);
        }
    }

    // =========================================================================
    // 2b. @jsonable synthetic exports in a declaration entry
    // =========================================================================

    static void testJsonableSyntheticExports() {
        System.out.println("-- @jsonable synthetic exports in a declaration entry --");

        // Synthetic declaration AST: export @jsonable class User { name: string; }.
        ClassDeclaration user = new ClassDeclaration(span(), "User",
            List.of(new ClassField(span(), "name", false, false, nt("string"),
                Optional.empty())),
            java.util.Set.of(deal.ast.DeclarationDirective.JSONABLE));
        ProgramNode ast = programOf(new ExportDeclaration(span(), user));
        Map<String, Type> exports = new LinkedHashMap<>();
        exports.put("User", IdentityTestFixtures.classType("User", "lib"));
        exports.put("User$fromJson", new Type.Func(List.of(Type.String.INSTANCE),
            Types.nullable(IdentityTestFixtures.classType("User", "lib"))));
        exports.put("User$toJson", new Type.Func(List.of(IdentityTestFixtures.classType("User", "lib")),
            Type.String.INSTANCE));
        ModuleFact fact = declFact("lib", ast, exports);

        CheckedProjectBuildResult result = CheckedProjectBuilder.build(invocation(),
            new ModuleId("lib"), List.of(fact));
        check(!result.hasErrors(), "the @jsonable declaration entry builds: "
            + result.diagnostics());
        if (result.hasErrors()) {
            return;
        }
        ExternalModuleInterface entry = result.index().modules().get(new ModuleId("lib"));
        check(entry.kind() == ExternalModuleKind.HOST,
            "the declaration entry is HOST");
        check(entry.exports().equals(List.of(
                new ExportInterface("User", "@lib/User"),
                new ExportInterface("User$fromJson", "(string) => @lib/User | null"),
                new ExportInterface("User$toJson", "(@lib/User) => string"))),
            "declared exports in declaration order, then the @jsonable synthetics where "
                + "the export map carries them; got " + entry.exports());
        check(entry.classes().size() == 1
                && entry.classes().get(0).classId().text().equals("@lib/User")
                && entry.classes().get(0).fields().equals(List.of(
                    new FieldInterface("name", "string", false, false, false)))
                && entry.classes().get(0).constructionEntry().equals(new ClassFactoryId(0)),
            "the exported class entry carries the derived constructionEntry and fields");
    }

    // =========================================================================
    // 3. Canonical type text grammar (all sealed TypeNode variants + parity)
    // =========================================================================

    static void testCanonicalTypeTextGrammar() {
        System.out.println("-- CanonicalTypeText: TypeNode grammar and checked-Type parity --");

        CanonicalTypeText.Context context = new CanonicalTypeText.Context(
            new ModuleId("m"), Set.of("C"), Map.of("V", "cc_class"));

        // Six primitives.
        check("null".equals(CanonicalTypeText.render(nt("null"), context)),
            "NamedType null renders as null");
        check("boolean".equals(CanonicalTypeText.render(nt("boolean"), context)),
            "NamedType boolean renders as boolean");
        check("int".equals(CanonicalTypeText.render(nt("int"), context)),
            "NamedType int renders as int");
        check("number".equals(CanonicalTypeText.render(nt("number"), context)),
            "NamedType number renders as number");
        check("string".equals(CanonicalTypeText.render(nt("string"), context)),
            "NamedType string renders as string");
        check("table".equals(CanonicalTypeText.render(nt("table"), context)),
            "NamedType table renders as table");
        check("bytes".equals(CanonicalTypeText.render(nt("bytes"), context)),
            "NamedType bytes renders as bytes (the v1.2 primitive joins "
                + "the declared-variant grammar)");

        // Builtin Error and module classes.
        check("@/Error".equals(CanonicalTypeText.render(nt("Error"), context)),
            "NamedType Error renders as @/Error");
        check("@m/C".equals(CanonicalTypeText.render(nt("C"), context)),
            "a class declared in the module renders as @<moduleId>/<n>");

        // Arrays: nested and parenthesized.
        check("int[]".equals(CanonicalTypeText.render(arr(nt("int")), context)),
            "ArrayType(int) renders as int[]");
        check("int[][]".equals(CanonicalTypeText.render(arr(arr(nt("int"))), context)),
            "nested arrays render as int[][]");
        check("(int | null)[]".equals(
                CanonicalTypeText.render(arr(nul(nt("int"))), context)),
            "a nullable element is parenthesized: (int | null)[]");
        check("(() => null)[]".equals(
                CanonicalTypeText.render(arr(fn(List.of(), nt("null"), false)), context)),
            "a function element is parenthesized: (() => null)[]");

        // Nullables.
        check("int[] | null".equals(
                CanonicalTypeText.render(nul(arr(nt("int"))), context)),
            "NullableType(int[]) renders as int[] | null (unwrapped array)");
        check("((int) => int) | null".equals(
                CanonicalTypeText.render(nul(fn(List.of(nt("int")), nt("int"), false)),
                    context)),
            "a function inner is parenthesized: ((int) => int) | null");

        // Function forms: sync/async, recursive positions.
        check("(int, string) => boolean".equals(
                CanonicalTypeText.render(fn(List.of(nt("int"), nt("string")),
                    nt("boolean"), false), context)),
            "the sync function form renders (T1, …, TN) => R");
        check("async () => null".equals(
                CanonicalTypeText.render(fn(List.of(), nt("null"), true), context)),
            "the async function form renders async (T1, …, TN) => R");
        check("((int) => int) => null".equals(
                CanonicalTypeText.render(fn(List.of(
                    fn(List.of(nt("int")), nt("int"), false)), nt("null"), false), context)),
            "a function-typed parameter renders unwrapped in parameter position");
        check("(((int) => int) | null)[]".equals(
                CanonicalTypeText.render(arr(nul(
                    fn(List.of(nt("int")), nt("int"), false))), context)),
            "the shared parenthesization rule applies at every wrapping position");

        // Qualified types joined through the module's import records.
        check("@cc_class/Vec".equals(
                CanonicalTypeText.render(qual("V", "Vec"), context)),
            "QualifiedType V.Vec renders as @<resolvedModulePath>/Vec");

        // Checked-Type rendering: the same shapes byte-identical (parity).
        Map<TypeNode, String> typeNodeForms = new LinkedHashMap<>();
        typeNodeForms.put(nt("null"), "null");
        typeNodeForms.put(nt("boolean"), "boolean");
        typeNodeForms.put(nt("int"), "int");
        typeNodeForms.put(nt("number"), "number");
        typeNodeForms.put(nt("string"), "string");
        typeNodeForms.put(nt("table"), "table");
        typeNodeForms.put(arr(nt("int")), "int[]");
        typeNodeForms.put(arr(arr(nt("int"))), "int[][]");
        typeNodeForms.put(arr(nul(nt("int"))), "(int | null)[]");
        typeNodeForms.put(arr(fn(List.of(), nt("null"), false)), "(() => null)[]");
        typeNodeForms.put(nul(arr(nt("int"))), "int[] | null");
        typeNodeForms.put(nul(fn(List.of(nt("int")), nt("int"), false)),
            "((int) => int) | null");
        typeNodeForms.put(fn(List.of(nt("int"), nt("string")), nt("boolean"), false),
            "(int, string) => boolean");
        typeNodeForms.put(fn(List.of(), nt("null"), true), "async () => null");
        for (Map.Entry<TypeNode, String> entry : typeNodeForms.entrySet()) {
            check(entry.getValue().equals(
                    CanonicalTypeText.render(entry.getKey(), context)),
                "TypeNode form renders exactly '" + entry.getValue() + "'");
        }
        check("@/Error".equals(CanonicalTypeText.render(IdentityTestFixtures.errorClassType())),
            "the checked builtin Error class renders as @/Error");
        check("@m/C".equals(CanonicalTypeText.render(IdentityTestFixtures.classType("C", "m"))),
            "the checked class type renders as @modulePath/Name");
        check("int[][]".equals(CanonicalTypeText.render(
                Types.array(Types.array(Type.Int.INSTANCE)))),
            "checked nested arrays render as int[][]");
        check("(int | null)[]".equals(CanonicalTypeText.render(
                Types.array(Types.nullable(Type.Int.INSTANCE)))),
            "checked (int | null)[] parity");
        check("(() => null)[]".equals(CanonicalTypeText.render(
                Types.array(new Type.Func(List.of(), Type.Null.INSTANCE)))),
            "checked (() => null)[] parity");
        check("int[] | null".equals(CanonicalTypeText.render(
                Types.nullable(Types.array(Type.Int.INSTANCE)))),
            "checked int[] | null parity");
        check("bytes".equals(CanonicalTypeText.render(Type.Bytes.INSTANCE)),
            "checked bytes renders as bytes (parity with the TypeNode arm)");
        check("bytes[]".equals(CanonicalTypeText.render(
                Types.array(Type.Bytes.INSTANCE))),
            "checked bytes[] renders unwrapped (primitive element)");
        check("bytes[] | null".equals(CanonicalTypeText.render(
                Types.nullable(Types.array(Type.Bytes.INSTANCE)))),
            "checked bytes[] | null renders the array unwrapped");
        check("(bytes) => bytes".equals(CanonicalTypeText.render(
                Types.func(List.of(Type.Bytes.INSTANCE), Type.Bytes.INSTANCE))),
            "checked sync bytes function form parity");
        check("async (bytes) => bytes".equals(CanonicalTypeText.render(
                new Type.Func(List.of(Type.Bytes.INSTANCE),
                    Type.Bytes.INSTANCE, true))),
            "checked async bytes function form parity");
        check("(bytes) => null".equals(CanonicalTypeText.render(
                new Type.Func(List.of(Type.Bytes.INSTANCE), Type.Null.INSTANCE))),
            "bytes at function-parameter depth renders recursively");
        check("((int) => int) | null".equals(CanonicalTypeText.render(
                Types.nullable(new Type.Func(List.of(Type.Int.INSTANCE), Type.Int.INSTANCE)))),
            "checked ((int) => int) | null parity");
        check("(int, string) => boolean".equals(CanonicalTypeText.render(
                Types.func(List.of(Type.Int.INSTANCE, Type.String.INSTANCE),
                    Type.Boolean.INSTANCE))),
            "checked sync function form parity");
        check("async () => null".equals(CanonicalTypeText.render(
                new Type.Func(List.of(), Type.Null.INSTANCE, true))),
            "checked async function form parity");
    }

    // =========================================================================
    // 4. Rendering defect guards (never an invented rendering, never a crash)
    // =========================================================================

    static void testRenderingDefects() {
        System.out.println("-- CanonicalTypeText defect guards --");

        CanonicalTypeText.Context context = new CanonicalTypeText.Context(
            new ModuleId("m"), Set.of("C"), Map.of("V", "cc_class"));

        expectDefect(() -> CanonicalTypeText.render(Type.Error.INSTANCE),
            "Type.Error has no rendering (defect)");
        expectDefect(() -> CanonicalTypeText.render(qual("Unknown", "Vec"), context),
            "an unresolvable qualified alias is a defect");
        expectDefect(() -> CanonicalTypeText.render(nul(nul(nt("int"))), context),
            "a chained T | null | null annotation is a defect");
        expectDefect(() -> CanonicalTypeText.render(nt("Missing"), context),
            "a NamedType that is neither a primitive nor a module class is a defect");
        // Same defects through the declaration-entry derivation of the builder
        // raise E6005 — asserted in testBuilderE6005Guards.
    }

    private static void expectDefect(Runnable runnable, String message) {
        try {
            runnable.run();
            fail(message + " — expected CanonicalTypeText.Defect");
        } catch (CanonicalTypeText.Defect defect) {
            check(defect.getMessage() != null && !defect.getMessage().isEmpty(),
                message + " — defect raised with a message");
        } catch (RuntimeException unexpected) {
            fail(message + " — expected CanonicalTypeText.Defect but got "
                + unexpected.getClass().getSimpleName() + ": " + unexpected.getMessage());
        }
    }

    // =========================================================================
    // 5. E6005 guards through the failure contract registry
    // =========================================================================

    static void testBuilderE6005Guards() {
        System.out.println("-- Builder E6005 guards (INDEX_INTERNAL_ERROR_SENTINEL) --");

        // (a) A synthetically injected Type.Error fact in a declared-type
        // position raises E6005 through the registry.
        Map<String, Type> errorExports = new LinkedHashMap<>();
        errorExports.put("main", Types.func(List.of(), Type.Null.INSTANCE));
        errorExports.put("bad", Type.Error.INSTANCE);
        ModuleFact typeErrorFact = implFact("main", programOf(), errorExports,
            new SymbolTable(), emptyChecks());
        assertE6005(typeErrorFact, new ModuleId("main"), "Type.Error in a declared-type position");

        Map<String, Type> bytesExports = new LinkedHashMap<>();
        bytesExports.put("main", Types.func(List.of(), Type.Null.INSTANCE));
        bytesExports.put("bad", Type.Bytes.INSTANCE);
        ModuleFact bytesFact = implFact("main", programOf(), bytesExports,
            new SymbolTable(), emptyChecks());
        CheckedProjectBuildResult bytesBuild = CheckedProjectBuilder.build(
            invocation(), new ModuleId("main"), List.of(bytesFact));
        check(!bytesBuild.hasErrors() && bytesBuild.index() != null,
            "a bytes-bearing export map builds the index cleanly: "
                + bytesBuild.diagnostics());
        if (bytesBuild.index() != null) {
            ExternalModuleInterface bytesEntry =
                bytesBuild.index().modules().get(new ModuleId("main"));
            check(bytesEntry != null && bytesEntry.exports().equals(List.of(
                    new ExportInterface("main", "() => null"),
                    new ExportInterface("bad", "bytes"))),
                "the implementation entry renders the bytes export as the "
                    + "primitive name; got "
                    + (bytesEntry == null ? "<null>" : bytesEntry.exports()));
        }

        // (b) An unresolvable qualified-type alias in a declaration entry.
        ProgramNode aliasProgram = programOf(new ExportDeclaration(span(),
            functionDecl("f", List.of(new Parameter(span(), "x", qual("V", "Vec"))),
                nt("null"), false)));
        ModuleFact aliasFact = declFact("lib", aliasProgram, Map.of());
        assertE6005(aliasFact, new ModuleId("lib"), "unresolvable qualified-type alias");

        // (c) A chained T | null | null annotation in a declaration entry.
        ProgramNode chainedProgram = programOf(new ExportDeclaration(span(),
            functionDecl("g", List.of(new Parameter(span(), "x", nul(nul(nt("int"))))),
                nt("null"), false)));
        ModuleFact chainedFact = declFact("lib", chainedProgram, Map.of());
        assertE6005(chainedFact, new ModuleId("lib"), "chained nullable annotation");

        // (d) An input entry with checks absent raises E6005 (producer-defect
        // guard: every input entry is IMPLEMENTATION and typeCheckAll fills
        // its CheckResult).
        ModuleFact missingChecks = new ModuleFact("src/main.deal", new ModuleId("main"),
            false, false, programOf(),
            Map.of("main", Types.func(List.of(), Type.Null.INSTANCE)),
            new SymbolTable(), null, List.of());
        assertE6005(missingChecks, new ModuleId("main"), "input entry without a CheckResult");

        // An imported target outside the closure is an inconsistent resolution
        // fact and also raises E6005 (never a silent drop).
        ModuleFact unresolvedImport = new ModuleFact("src/main.deal", new ModuleId("main"),
            false, false, programOf(),
            Map.of("main", Types.func(List.of(), Type.Null.INSTANCE)),
            new SymbolTable(), emptyChecks(),
            List.of(new ModuleFact.ImportFact("m", "./missing", "src/missing.deal")));
        assertE6005(unresolvedImport, new ModuleId("main"), "unresolved import target");
    }

    private static void assertE6005(ModuleFact fact, ModuleId entryModule, String what) {
        CheckedProjectBuildResult result = CheckedProjectBuilder.build(
            invocation(), entryModule, List.of(fact));
        check(result.input() == null && result.index() == null && result.hasErrors(),
            what + ": the build fails with both records null");
        check(result.diagnostics().size() == 1,
            what + ": exactly one diagnostic; got " + result.diagnostics().size());
        if (result.diagnostics().isEmpty()) {
            return;
        }
        CompilerDiagnostic diagnostic = result.diagnostics().get(0);
        check("E6005".equals(diagnostic.code()),
            what + ": the diagnostic code is E6005; got " + diagnostic.code());
        check("error".equals(diagnostic.severity()),
            what + ": the E6005 severity is error");
        check(DiagnosticCode.fromCode("E6005") != null
                && DiagnosticCode.fromCode("E6005").phase()
                    == DiagnosticCode.Phase.BACKEND_LOWERING,
            what + ": E6005 is BACKEND_LOWERING");
        String message = diagnostic.message();
        check(message.contains("Common semantic lowering failed"),
            what + ": the registry-owned message template is instantiated");
        check(message.contains("module '" + entryModule.path() + "'"),
            what + ": the payload names the failing module; got " + message);
        check(message.contains("capability FOUNDATION_VALUES"),
            what + ": the payload capability is FOUNDATION_VALUES; got " + message);
        check(message.contains("validatorRule INDEX_INTERNAL_ERROR_SENTINEL"),
            what + ": the payload validatorRule is INDEX_INTERNAL_ERROR_SENTINEL; got "
                + message);
        check(message.contains("semanticProfile LEGACY_SAFE_INT"),
            what + ": the payload carries the invocation profile; got " + message);
        check(message.contains("irVersion deal.semantic-ir/2"),
            what + ": the payload carries the pinned IR version; got " + message);
        check(message.contains("origin CheckedProjectBuilder INDEX_INTERNAL_ERROR_SENTINEL"),
            what + ": the payload names the builder as the origin; got " + message);
    }

    // =========================================================================
    // 6. No frontend mutation
    // =========================================================================

    static void testNoFrontendMutation() throws Exception {
        System.out.println("-- No frontend mutation: IrDumper output identical before/after --");

        Path modulesDir = Path.of("test/conformance/backend-runtime/modules")
            .toAbsolutePath().normalize();
        Path entryPath = modulesDir.resolve("declaration-only-import-compile.deal");
        Path declPath = modulesDir.resolve("declaration_only_lib.d.deal");

        String entrySource = ConformanceHarnessMetadata.productionCorpusSource(
            Files.readString(entryPath),
            "backend-runtime/modules/declaration-only-import-compile.deal");
        LexResult entryLex = new Lexer(entrySource, entryPath.toString()).tokenize();
        check(!entryLex.hasErrors(), "fixture lexes without errors");
        ParseResult entryParsed = new Parser(entryLex.tokens(), entryPath.toString(),
            entryLex.directiveEvents()).parse();
        ProgramNode entryProgram = entryParsed.program();
        StubModuleResolver resolver = new StubModuleResolver();
        resolver.register("./declaration_only_lib", Map.of("declaredAdd",
            Types.func(List.of(Type.Int.INSTANCE, Type.Int.INSTANCE), Type.Int.INSTANCE)));
        NameResolver nameResolver = new NameResolver("declaration-only-import-compile",
            resolver);
        SymbolTable symbolTable = nameResolver.resolve(entryProgram);
        CheckResult checkResult = TypeChecker.check("declaration-only-import-compile",
            symbolTable, nameResolver, entryProgram);
        check(!checkResult.hasErrors(), "the fixture passes phase 3: "
            + checkResult.diagnostics());

        // Phase-3-corrected export map in declaration order (the orchestrator's
        // correction pass).
        Map<String, Type> correctedExports = new LinkedHashMap<>();
        for (StatementNode stmt : entryProgram.statements()) {
            if (stmt instanceof ExportDeclaration exp
                    && exp.declaration() instanceof FunctionDeclaration fd) {
                Symbol symbol = symbolTable.resolve(fd.name());
                if (symbol instanceof Symbol.FunctionSymbol functionSymbol) {
                    correctedExports.put(fd.name(), functionSymbol.funcType());
                }
            }
        }

        // The declaration file's own AST.
        String declSource = ConformanceHarnessMetadata.productionCorpusSource(
            Files.readString(declPath),
            "backend-runtime/modules/declaration_only_lib.d.deal");
        LexResult declLex = new Lexer(declSource, declPath.toString()).tokenize();
        ParseResult declParsed = new Parser(declLex.tokens(), declPath.toString(),
            declLex.directiveEvents()).parse();
        ProgramNode declProgram = declParsed.program();

        String entryBefore = IrDumper.dump(entryProgram, checkResult,
            "declaration-only-import-compile");
        String declBefore = IrDumper.dump(declProgram, (SymbolTable) null, "declaration_only_lib");
        check(!entryBefore.isEmpty() && !declBefore.isEmpty(),
            "the pre-build IR dumps are non-empty");

        ModuleFact declFact = new ModuleFact(declPath.toString(),
            new ModuleId("declaration_only_lib"), true, false, declProgram, Map.of(),
            null, null, List.of());
        ModuleFact entryFact = new ModuleFact(entryPath.toString(),
            new ModuleId("declaration-only-import-compile"), false, false, entryProgram,
            correctedExports, symbolTable, checkResult,
            List.of(new ModuleFact.ImportFact("Decl", "./declaration_only_lib",
                declPath.toString())));
        CheckedProjectBuildResult result = CheckedProjectBuilder.build(invocation(),
            new ModuleId("declaration-only-import-compile"), List.of(declFact, entryFact));
        check(!result.hasErrors(), "the manual build succeeds: " + result.diagnostics());
        check(result.input().modules().get(0).ast() == entryProgram
                && result.input().modules().get(0).checks() == checkResult,
            "the input shares the checked AST/CheckResult instances (read-only inputs)");

        String entryAfter = IrDumper.dump(entryProgram, checkResult,
            "declaration-only-import-compile");
        String declAfter = IrDumper.dump(declProgram, (SymbolTable) null, "declaration_only_lib");
        check(entryBefore.equals(entryAfter),
            "IrDumper output for the entry module is byte-identical before/after the build");
        check(declBefore.equals(declAfter),
            "IrDumper output for the declaration file is byte-identical before/after the build");
        check(result.input().modules().get(0).checks().symbolTable() == symbolTable,
            "the symbol table instance is preserved (no re-resolution)");
    }

    // =========================================================================
    // 7. constructionEntry derivation (allocator ordering)
    // =========================================================================

    static void testConstructionEntryDerivation() throws Exception {
        System.out.println("-- constructionEntry derivation: allocator ordering --");

        Path tmp = Files.createTempDirectory("deal-checked-project-ctor");
        try {
            Path src = tmp.resolve("src");
            Files.createDirectories(src);

            Files.writeString(src.resolve("lib.d.deal"),
                "export class A { x: int; }\n"
                    + "export class B { y: string; opt?: int; note: string | null; "
                    + "count: int = 1; }\n");
            Files.writeString(src.resolve("main.deal"),
                "import * as lib from \"host/lib\";\n"
                    + "export class C { v: int = 0; }\n"
                    + "export class D { w: boolean = false; }\n"
                    + "export function main(): null { return null; }\n");
            Files.writeString(tmp.resolve("deal.json"),
                "{\n  \"languageVersion\": \"1.3\",\n"
                    + "  \"moduleRoots\": [\"src\"],\n"
                    + "  \"output\": \"build\",\n"
                    + "  \"backend\": \"luajit\",\n"
                    + "  \"externals\": {\n"
                    + "    \"host/lib\": { \"declaration\": \"src/lib.d.deal\" }\n"
                    + "  }\n}\n");
            Path entry = src.resolve("main.deal").toAbsolutePath();
            Path output = tmp.resolve("build");
            ProjectLocator.LocateResult located =
                ProjectLocator.locate(entry.toString(), null);
            check(located.context() != null,
                "the class fixture locates strictly: " + located.e2010());
            if (located.context() == null) {
                return;
            }

            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                located.context(), entry, false, false, false, false, null,
                CompilerProfileProvider.resolve(
                    ReleaseConfiguration.CURRENT_RELEASE_STATE,
                    ReleaseConfiguration.releaseCapabilityRegistry()));
            boolean ok = orchestrator.compile();
            check(ok, "the class fixture compiles: " + orchestrator.diagnostics());
            CheckedProjectBuildResult result = orchestrator.checkedProject();
            check(result != null && !result.hasErrors(),
                "the builder succeeds on the class fixture");
            if (result == null) {
                return;
            }

            Map<ModuleId, ExternalModuleInterface> indexModules = result.index().modules();
            check(new ArrayList<>(indexModules.keySet()).equals(List.of(
                    new ModuleId("host.lib"), new ModuleId("main"))),
                "dependency order: host.lib before main; got " + indexModules.keySet());

            ExternalModuleInterface lib = indexModules.get(new ModuleId("host.lib"));
            ExternalModuleInterface main = indexModules.get(new ModuleId("main"));
            check(lib.kind() == ExternalModuleKind.HOST
                    && main.kind() == ExternalModuleKind.IMPLEMENTATION,
                "the declaration class module is HOST, the implementation module IMPLEMENTATION");

            // Allocator ordering: dependency order, exported classes in
            // declaration (source) order, role CLASS_FACTORY, ordinal 0.
            ClassInterface a = lib.classes().get(0);
            ClassInterface b = lib.classes().get(1);
            ClassInterface c = main.classes().get(0);
            ClassInterface d = main.classes().get(1);
            check(a.classId().text().equals("@host.lib/A") && a.constructionEntry().equals(
                    new ClassFactoryId(0)),
                "lib.A gets constructionEntry 0 with classId @host.lib/A");
            check(b.classId().text().equals("@host.lib/B") && b.constructionEntry().equals(
                    new ClassFactoryId(1)),
                "lib.B gets constructionEntry 1 with classId @host.lib/B");
            check(c.classId().text().equals("@src/C") && c.constructionEntry().equals(
                    new ClassFactoryId(2)),
                "main.C gets constructionEntry 2 with the checker-resolved class "
                    + "identity @src/C (the root-relative source directory, never the "
                    + "dotted module path)");
            check(d.classId().text().equals("@src/D") && d.constructionEntry().equals(
                    new ClassFactoryId(3)),
                "main.D gets constructionEntry 3 with the checker-resolved class "
                    + "identity @src/D");

            // FieldInterface facts from the ClassField records (declaration
            // entry via the pinned TypeNode grammar; implementation entry via
            // the checked ClassSymbol records).
            check(a.fields().equals(List.of(
                    new FieldInterface("x", "int", false, false, false))),
                "lib.A fields = [x: int, optional=false, nullable=false, hasDefault=false]");
            check(b.fields().equals(List.of(
                    new FieldInterface("y", "string", false, false, false),
                    new FieldInterface("opt", "int", true, false, false),
                    new FieldInterface("note", "string | null", false, true, false),
                    new FieldInterface("count", "int", false, false, true))),
                "lib.B fields carry optional/nullable/hasDefault from the record; got "
                    + b.fields());
            check(c.fields().equals(List.of(
                    new FieldInterface("v", "int", false, false, true))),
                "main.C fields derive from the checked ClassSymbol records; got " + c.fields());

            // The index never records a route: no route-shaped component exists
            // on any entry, and the constructionEntry is a plain ClassFactoryId.
            check(a.constructionEntry() instanceof ClassFactoryId
                    && a.constructionEntry().id() == 0,
                "the constructionEntry is a deterministic route-independent ClassFactoryId");
        } finally {
            deleteRecursively(tmp);
        }
    }

    // =========================================================================
    // 8. Determinism: byte-identical canonical JSON + digest golden
    // =========================================================================

    /** The pinned canonical JSON of the combined fixture index (golden). */
    private static final String PINNED_INDEX_JSON =
        "{\"formatVersion\":\"deal.semantic-interface/1\",\"modules\":[{\"classes\":[],\"exports\":[{\"declaredType\":\"(int, int) => int\",\"name\":\"declaredAdd\"}],\"imports\":[],\"initialization\":\"ONCE_AFTER_DEPENDENCIES\",\"kind\":\"HOST\",\"moduleId\":{\"path\":\"host.lib\",\"type\":\"module\"}},{\"classes\":[],\"exports\":[{\"declaredType\":\"() => int\",\"name\":\"nowMillis\"}],\"imports\":[],\"initialization\":\"ONCE_AFTER_DEPENDENCIES\",\"kind\":\"STDLIB\",\"moduleId\":{\"path\":\"std.time\",\"type\":\"module\"}},{\"classes\":[],\"exports\":[{\"declaredType\":\"() => null\",\"name\":\"main\"}],\"imports\":[{\"alias\":\"lib\",\"kind\":\"HOST\",\"modulePath\":\"host/lib\",\"resolvedModuleId\":{\"path\":\"host.lib\",\"type\":\"module\"}},{\"alias\":\"time\",\"kind\":\"STDLIB\",\"modulePath\":\"std/time\",\"resolvedModuleId\":{\"path\":\"std.time\",\"type\":\"module\"}}],\"initialization\":\"ONCE_AFTER_DEPENDENCIES\",\"kind\":\"IMPLEMENTATION\",\"moduleId\":{\"path\":\"main\",\"type\":\"module\"}}]}";

    /** The pinned interface index digest of the combined fixture (golden). */
    private static final String PINNED_INDEX_DIGEST =
        "25b6e4816b1e2ade780ce9c0582a547756d07a285e61ed9ef9257b69b5bac336";

    private static Path writeCombinedFixture(Path dir) throws Exception {
        Path src = dir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("lib.d.deal"),
            "export function declaredAdd(a: int, b: int): int;\n");
        Files.writeString(src.resolve("main.deal"),
            "import * as lib from \"host/lib\";\n"
                + "import * as time from \"std/time\";\n"
                + "export function main(): null { return null; }\n");
        Files.writeString(dir.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.3\",\n"
                + "  \"moduleRoots\": [\"src\"],\n"
                + "  \"output\": \"build\",\n"
                + "  \"backend\": \"luajit\",\n"
                + "  \"externals\": {\n"
                + "    \"host/lib\": { \"declaration\": \"src/lib.d.deal\" }\n"
                + "  }\n}\n");
        return src.resolve("main.deal").toAbsolutePath();
    }

    private static ProjectContext locateCombinedFixture(Path entry) {
        ProjectLocator.LocateResult located =
            ProjectLocator.locate(entry.toString(), null);
        check(located.context() != null,
            "the combined fixture locates strictly: " + located.e2010());
        return located.context();
    }

    static void testDeterminismAndDigestGolden() throws Exception {
        System.out.println("-- Determinism: byte-identical index JSON + digest golden --");

        Path tmp = Files.createTempDirectory("deal-checked-project-det");
        try {
            Path entry = writeCombinedFixture(tmp);
            ProjectContext context = locateCombinedFixture(entry);

            CompilerInvocation explicitInvocation = CompilerProfileProvider.resolve(
                ReleaseConfiguration.CURRENT_RELEASE_STATE,
                ReleaseConfiguration.releaseCapabilityRegistry());
            CompilationOrchestrator first = new CompilationOrchestrator(
                context, entry, false, false, false, false, null, explicitInvocation);
            boolean firstOk = first.compile();
            check(firstOk, "first build compiles: " + first.diagnostics());
            CheckedProjectBuildResult firstResult = first.checkedProject();
            check(firstResult != null && !firstResult.hasErrors(),
                "first build succeeds");

            CompilationOrchestrator second = new CompilationOrchestrator(
                context, entry, false, false, false, false, null,
                CompilerProfileProvider.resolve(
                    ReleaseConfiguration.CURRENT_RELEASE_STATE,
                    ReleaseConfiguration.releaseCapabilityRegistry()));
            check(second.compile(), "second build compiles: " + second.diagnostics());
            CheckedProjectBuildResult secondResult = second.checkedProject();
            check(secondResult != null && !secondResult.hasErrors(),
                "second build succeeds");

            String firstJson = CanonicalJson.serializeText(
                firstResult.index().toCanonicalJson());
            String secondJson = CanonicalJson.serializeText(
                secondResult.index().toCanonicalJson());
            check(firstJson.equals(secondJson),
                "repeated builds produce byte-identical index canonical JSON");
            check(PINNED_INDEX_JSON.equals(firstJson),
                "the canonical index JSON equals the stored golden (STDLIB/HOST "
                    + "declaration entries included)");
            check(firstResult.index().interfaceIndexDigest().equals(
                    secondResult.index().interfaceIndexDigest()),
                "repeated builds produce equal interfaceIndexDigest values");
            check(firstResult.index().interfaceIndexDigest().equals(
                    CanonicalJson.sha256Hex(
                        CanonicalJson.serializeBytes(firstResult.index().toCanonicalJson()))),
                "interfaceIndexDigest = SHA-256(canonical JSON of the index) through the "
                    + "single canonical facility");
            check(PINNED_INDEX_DIGEST.equals(firstResult.index().interfaceIndexDigest()),
                "the interface index digest equals the stored golden; got "
                    + firstResult.index().interfaceIndexDigest());
            testCombinedDependencies(tmp, first, explicitInvocation, firstOk);
        } finally {
            deleteRecursively(tmp);
        }
    }

    // =========================================================================
    // 9. Combined dependencies (T1/T2/T3/T4/T5/T7)
    // =========================================================================

    static void testCombinedDependencies(Path tmp, CompilationOrchestrator orchestrator,
                                         CompilerInvocation explicitInvocation, boolean ok)
            throws Exception {
        System.out.println("-- Combined T1/T2/T3/T4/T5/T7 end-to-end flow --");

        check(ok, "the combined fixture compiles end-to-end: "
            + orchestrator.diagnostics());
        CheckedProjectBuildResult result = orchestrator.checkedProject();
        check(result != null && !result.hasErrors(), "the combined build succeeds");
        if (result == null) {
            return;
        }

        // The builder records the invocation's releaseStateHash verbatim
        // (T4 dependency): asserted equal on the record.
        check(result.input().invocation() == explicitInvocation,
            "the input carries the exact resolved invocation record");
        check(result.input().releaseStateHash().equals(
                explicitInvocation.releaseStateHash()),
            "the input records the derived releaseStateHash verbatim (equal to T4's "
                + "recorded field)");
        // A wrong recorded hash is a broken T4 dependency and fails at
        // record construction (never silently accepted).
        try {
            new CheckedProjectInput(explicitInvocation, new ModuleId("main"), List.of(),
                "wrong-hash");
            fail("a CheckedProjectInput with a wrong releaseStateHash must be rejected");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("verbatim"),
                "a wrong recorded release-state hash is rejected at construction");
        }

        // T3: the digest golden (canonical serialization of the index).
        check(PINNED_INDEX_DIGEST.equals(result.index().interfaceIndexDigest()),
            "the combined flow's interface index digest equals the stored golden");

        // T2: ID types — ClassId text @modulePath/ClassName; the
        // constructionEntry is a ClassFactoryId (asserted in the dedicated
        // derivation test; re-assert the type here for the combined flow).
        check(result.index().modules().get(new ModuleId("main")).imports().get(1)
                .resolvedModuleId() instanceof ModuleId,
            "resolvedModuleId is the ModuleId ID type");

        // T5/T1: a fault injected into the flow (an input entry whose
        // CheckResult is missing — a broken checked-facts dependency)
        // fails the suite through the registry-owned E6005 payload.
        ModuleFact faulted = new ModuleFact("src/main.deal", new ModuleId("main"),
            false, false, programOf(),
            Map.of("main", Types.func(List.of(), Type.Null.INSTANCE)),
            new SymbolTable(), null, List.of());
        CheckedProjectBuildResult faultedResult = CheckedProjectBuilder.build(
            explicitInvocation, new ModuleId("main"), List.of(faulted));
        check(faultedResult.hasErrors() && faultedResult.input() == null,
            "a faulted dependency fails the build");
        CompilerDiagnostic e6005 = faultedResult.diagnostics().get(0);
        check("E6005".equals(e6005.code())
                && e6005.message().contains("capability FOUNDATION_VALUES")
                && e6005.message().contains(
                    "validatorRule INDEX_INTERNAL_ERROR_SENTINEL")
                && e6005.message().contains("irVersion deal.semantic-ir/2"),
            "the faulted build's E6005 carries the pinned payload: " + e6005.message());

        // T7: allocator ordering asserted through the constructionEntry
        // derivation on a class fixture (wrong allocator order fails the
        // dedicated derivation test).
        check(allocatorOrderingWorks(tmp),
            "T7's allocator ordering holds over the end-to-end flow");
    }

    private static boolean allocatorOrderingWorks(Path tmp) throws Exception {
        Path src = tmp.resolve("src2");
        Files.createDirectories(src);

        Files.writeString(src.resolve("lib.d.deal"),
            "export class A { x: int; }\n"
                + "export class B { y: string; }\n");
        Files.writeString(src.resolve("entry.deal"),
            "import * as lib from \"host/lib\";\n"
                + "export class C { v: int = 0; }\n"
                + "export function main(): null { return null; }\n");
        Files.writeString(tmp.resolve("deal.json"),
            "{\n  \"languageVersion\": \"1.3\",\n"
                + "  \"moduleRoots\": [\"src2\"],\n"
                + "  \"output\": \"build2\",\n"
                + "  \"backend\": \"luajit\",\n"
                + "  \"externals\": {\n"
                + "    \"host/lib\": { \"declaration\": \"src2/lib.d.deal\" }\n"
                + "  }\n}\n");
        Path entry = src.resolve("entry.deal").toAbsolutePath();
        ProjectLocator.LocateResult located =
            ProjectLocator.locate(entry.toString(), null);
        if (located.context() == null) {
            return false;
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), entry, false, false, false, false, null,
            CompilerProfileProvider.resolve(
                ReleaseConfiguration.CURRENT_RELEASE_STATE,
                ReleaseConfiguration.releaseCapabilityRegistry()));
        if (!orchestrator.compile()) {
            return false;
        }
        CheckedProjectBuildResult result = orchestrator.checkedProject();
        if (result == null || result.hasErrors()) {
            return false;
        }
        ExternalModuleInterface lib = result.index().modules().get(new ModuleId("host.lib"));
        ExternalModuleInterface main = result.index().modules().get(new ModuleId("entry"));
        return lib != null && main != null
            && lib.classes().get(0).constructionEntry().equals(new ClassFactoryId(0))
            && lib.classes().get(1).constructionEntry().equals(new ClassFactoryId(1))
            && main.classes().get(0).constructionEntry().equals(new ClassFactoryId(2));
    }

    // =========================================================================
    // 10. Complete import-graph cycle rejection (ISSUE-0784)
    // =========================================================================

    /** Silences the orchestrator's stderr report around one compile. */
    private static boolean compileQuietly(CompilationOrchestrator orchestrator)
            throws IOException {
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8));
            return orchestrator.compile();
        } finally {
            System.setErr(originalErr);
        }
    }

    /** Writes one scratch project source file and returns its path. */
    private static Path writeSource(Path src, String name, String text)
            throws IOException {
        Files.createDirectories(src);
        Path file = src.resolve(name);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file.toAbsolutePath().normalize();
    }

    /** The first import declaration's span of one scratch source file. */
    private static Span firstImportSpan(Path file) throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        LexResult lex = new Lexer(source, file.toString()).tokenize();
        ParseResult parsed = new Parser(lex.tokens(), file.toString(),
            lex.directiveEvents()).parse();
        return ((ImportDeclaration) parsed.program().statements().get(0))
            .span();
    }

    /** The single E2005 diagnostic of one rejected compile, or null. */
    private static CompilerDiagnostic onlyCycleDiagnostic(
            List<CompilerDiagnostic> diagnostics, String what) {
        check(diagnostics.size() == 1,
            what + ": exactly one diagnostic, got " + diagnostics);
        if (diagnostics.size() != 1) {
            return null;
        }
        CompilerDiagnostic diagnostic = diagnostics.get(0);
        check("E2005".equals(diagnostic.code())
                && "error".equals(diagnostic.severity()),
            what + ": error E2005, got " + diagnostic);
        return diagnostic;
    }

    /**
     * The complete resolved import graph admission: type-only, mixed and
     * declaration-only cycles plus self-imports reject exactly before
     * checking/lowering with no publication, while the acyclic diamond
     * passes (ISSUE-0784).
     */
    static void testImportCycleRejection() throws Exception {
        System.out.println("-- Complete import-graph cycle rejection (E2005) --");

        // 1. Acyclic diamond: entry -> a -> d and entry -> b -> d. The
        //    complete resolved graph is acyclic, so the compile succeeds
        //    and publishes the artifact set — the positive control of the
        //    cycle admission.
        Path diamond = Files.createTempDirectory("deal-graph-diamond");
        try {
            Path src = diamond.resolve("src");
            writeSource(src, "main.deal",
                "import * as A from \"./a\";\n"
                    + "import * as B from \"./b\";\n"
                    + "export function main(): null { return null; }\n");
            writeSource(src, "a.deal",
                "import * as D from \"./d\";\n"
                    + "export class Alpha { d: D.Delta | null = null; }\n");
            writeSource(src, "b.deal",
                "import * as D from \"./d\";\n"
                    + "export class Beta { d: D.Delta | null = null; }\n");
            writeSource(src, "d.deal",
                "export class Delta { n: int = 0; }\n");
            Path output = diamond.resolve("out");
            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                src.resolve("main.deal").toAbsolutePath(), output, false,
                null, List.of(src.toAbsolutePath()), null);
            check(compileQuietly(orchestrator),
                "an acyclic diamond compiles: " + orchestrator.diagnostics());
            check(orchestrator.checkedProject() != null
                    && !orchestrator.checkedProject().hasErrors(),
                "the diamond build produces the checked project");
            check(Files.exists(output.resolve("main.lua")),
                "the diamond compile publishes its artifact set");
        } finally {
            deleteRecursively(diamond);
        }

        // 2. Type-only implementation cycle: the class-only imports carry
        //    no runtime edge. The complete-graph admission rejects before
        //    checking/lowering, anchors at the actual import declaration
        //    and names the reproducible cycle path; nothing is published.
        Path typeOnly = Files.createTempDirectory("deal-graph-type-only");
        try {
            Path src = typeOnly.resolve("src");
            writeSource(src, "main.deal",
                "import * as A from \"./a\";\n"
                    + "export function main(): null { return null; }\n");
            Path aFile = writeSource(src, "a.deal",
                "import * as B from \"./b\";\n"
                    + "export class Alpha { b: B.Beta | null = null; }\n");
            Path bFile = writeSource(src, "b.deal",
                "import * as A from \"./a\";\n"
                    + "export class Beta { a: A.Alpha | null = null; }\n");
            Path output = typeOnly.resolve("out");
            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                src.resolve("main.deal").toAbsolutePath(), output, false,
                null, List.of(src.toAbsolutePath()), null);
            check(!compileQuietly(orchestrator),
                "a type-only implementation cycle fails the compile");
            CompilerDiagnostic e2005 = onlyCycleDiagnostic(
                orchestrator.diagnostics(), "type-only cycle");
            if (e2005 != null) {
                Span importSpan = firstImportSpan(aFile);
                check(e2005.range().origin() == RangeOrigin.SOURCE
                        && e2005.range().equals(importSpan.range()),
                    "the E2005 anchor equals a.deal's import declaration"
                        + " range: " + e2005.range() + " vs "
                        + importSpan.range());
                String a = aFile.toString();
                String b = bFile.toString();
                check(e2005.message().equals("Circular import: " + a + " -> "
                        + b + " -> " + a),
                    "the cycle path is reproducible and ordered by the real"
                        + " edges: " + e2005.message());
            }
            check(orchestrator.producedProject() == null,
                "a rejected graph produces no lowered project");
            check(orchestrator.checkedProject() == null,
                "a rejected graph produces no checked project");
            check(!Files.exists(output),
                "a rejected graph publishes no artifact set");
        } finally {
            deleteRecursively(typeOnly);
        }

        // 3. Self-import: the module's own import edge is a one-member
        //    cyclic component and rejects exactly like every other cycle.
        Path selfImport = Files.createTempDirectory("deal-graph-self");
        try {
            Path src = selfImport.resolve("src");
            Path mainFile = writeSource(src, "main.deal",
                "import * as S from \"./main\";\n"
                    + "export function main(): null { return null; }\n");
            Path output = selfImport.resolve("out");
            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                mainFile, output, false, null,
                List.of(src.toAbsolutePath()), null);
            check(!compileQuietly(orchestrator),
                "a self-import fails the compile");
            CompilerDiagnostic e2005 = onlyCycleDiagnostic(
                orchestrator.diagnostics(), "self-import");
            if (e2005 != null) {
                check(e2005.range().origin() == RangeOrigin.SOURCE
                        && e2005.range().equals(
                            firstImportSpan(mainFile).range()),
                    "the self-import E2005 anchors at the import"
                        + " declaration: " + e2005.range());
                check(e2005.message().equals("Circular import: "
                        + mainFile + " -> " + mainFile),
                    "the self-import cycle path closes on its own module: "
                        + e2005.message());
            }
            check(!Files.exists(output),
                "the self-import publishes no artifact set");
        } finally {
            deleteRecursively(selfImport);
        }

        // 4. Mixed generated-jsonable implementation cycle: the class
        //    defaults carry runtime edges and the same two modules form
        //    the type-level SCC — exactly one E2005 for the one cyclic
        //    component (runtime edges are additional facts, never a
        //    second rejection).
        Path mixed = Files.createTempDirectory("deal-graph-mixed");
        try {
            Path src = mixed.resolve("src");
            writeSource(src, "main.deal",
                "import * as L from \"./lib\";\n"
                    + "// @jsonable\n"
                    + "export class A { b: L.B = {}; }\n"
                    + "export function main(): null { return null; }\n");
            writeSource(src, "lib.deal",
                "import * as M from \"./main\";\n"
                    + "// @jsonable\n"
                    + "export class B { a: M.A = {}; }\n");
            Path output = mixed.resolve("out");
            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                src.resolve("main.deal").toAbsolutePath(), output, false,
                null, List.of(src.toAbsolutePath()), null);
            check(!compileQuietly(orchestrator),
                "a mixed type/runtime cycle fails the compile");
            CompilerDiagnostic e2005 = onlyCycleDiagnostic(
                orchestrator.diagnostics(), "mixed cycle");
            check(e2005 != null
                    && e2005.message().contains(" -> "),
                "the mixed cycle E2005 carries the cycle path");
            check(!Files.exists(output),
                "the mixed cycle publishes no artifact set");
        } finally {
            deleteRecursively(mixed);
        }

        // 5. Declaration-only cycle: two externals-declared .d.deal files
        //    import each other. The former tolerated declaration-only
        //    exception is removed — the complete graph rejects exactly
        //    like the implementation case, before any checking.
        Path declarations = Files.createTempDirectory("deal-graph-decl");
        try {
            Path root = declarations;
            writeSource(root, "main.deal",
                "import * as A from \"./support/decl_two_a\";\n"
                    + "export function probe(x: A.A): A.A { return x; }\n"
                    + "export function main(): null { return null; }\n");
            Path support = root.resolve("support");
            Path declA = writeSource(support, "decl_two_a.d.deal",
                "import * as B from \"./decl_two_b\";\n"
                    + "export class A { value: int = 1; }\n"
                    + "export function accept(x: B.B): B.B;\n");
            Path declB = writeSource(support, "decl_two_b.d.deal",
                "import * as A from \"./decl_two_a\";\n"
                    + "export class B { value: int = 2; }\n"
                    + "export function accept(x: A.A): A.A;\n");
            Map<String, String> externals = new LinkedHashMap<>();
            externals.put("decl_two_a", "support/decl_two_a.d.deal");
            externals.put("decl_two_b", "support/decl_two_b.d.deal");
            Path output = declarations.resolve("out");
            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                root.resolve("main.deal").toAbsolutePath(), output, false,
                externals, List.of(root.toAbsolutePath()), null);
            check(!compileQuietly(orchestrator),
                "a declaration-only cycle fails the compile");
            CompilerDiagnostic e2005 = onlyCycleDiagnostic(
                orchestrator.diagnostics(), "declaration-only cycle");
            if (e2005 != null) {
                check(e2005.range().origin() == RangeOrigin.SOURCE
                        && e2005.range().equals(
                            firstImportSpan(declA).range()),
                    "the declaration-cycle E2005 anchors at decl_two_a's"
                        + " import declaration: " + e2005.range());
                String a = declA.toString();
                String b = declB.toString();
                check(e2005.message().equals("Circular import: " + a + " -> "
                        + b + " -> " + a),
                    "the declaration cycle path is reproducible: "
                        + e2005.message());
            }
            check(orchestrator.checkedProject() == null,
                "a declaration-only cycle produces no checked project");
            check(!Files.exists(output),
                "a declaration-only cycle publishes no artifact set");
        } finally {
            deleteRecursively(declarations);
        }

        // 6. Two disjoint cycles: one E2005 per cyclic component, in
        //    deterministic discovery order of each component's first
        //    member.
        Path disjoint = Files.createTempDirectory("deal-graph-disjoint");
        try {
            Path src = disjoint.resolve("src");
            writeSource(src, "main.deal",
                "import * as A from \"./a\";\n"
                    + "import * as X from \"./x\";\n"
                    + "export function main(): null { return null; }\n");
            writeSource(src, "a.deal",
                "import * as B from \"./b\";\n"
                    + "export class Alpha { b: B.Beta | null = null; }\n");
            writeSource(src, "b.deal",
                "import * as A from \"./a\";\n"
                    + "export class Beta { a: A.Alpha | null = null; }\n");
            writeSource(src, "x.deal",
                "import * as Y from \"./y\";\n"
                    + "export class Chi { y: Y.Psi | null = null; }\n");
            writeSource(src, "y.deal",
                "import * as X from \"./x\";\n"
                    + "export class Psi { x: X.Chi | null = null; }\n");
            Path output = disjoint.resolve("out");
            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                src.resolve("main.deal").toAbsolutePath(), output, false,
                null, List.of(src.toAbsolutePath()), null);
            check(!compileQuietly(orchestrator),
                "two disjoint cycles fail the compile");
            List<CompilerDiagnostic> diagnostics = orchestrator.diagnostics();
            check(diagnostics.size() == 2
                    && diagnostics.stream().allMatch(d -> "E2005".equals(
                        d.code()) && "error".equals(d.severity())),
                "exactly two error E2005 diagnostics (one per component),"
                    + " got " + diagnostics);
            if (diagnostics.size() == 2) {
                check(diagnostics.get(0).message().contains(
                        "a.deal -> ")
                        && diagnostics.get(1).message().contains(
                            "x.deal -> "),
                    "the components report in deterministic discovery"
                        + " order: " + diagnostics);
            }
        } finally {
            deleteRecursively(disjoint);
        }

        // 7. Reproducible path versus node order: the entry discovers a
        //    and b before a's own import discovers c, so the SCC's node
        //    order (a, b, c) is not a path. The witness path follows the
        //    real edges (a -> c -> b -> a), and the anchor is a's actual
        //    import declaration of c.
        Path outOfOrder = Files.createTempDirectory("deal-graph-path");
        try {
            Path src = outOfOrder.resolve("src");
            writeSource(src, "main.deal",
                "import * as A from \"./a\";\n"
                    + "import * as B from \"./b\";\n"
                    + "export function main(): null { return null; }\n");
            Path aFile = writeSource(src, "a.deal",
                "import * as C from \"./c\";\n"
                    + "export class Alpha { c: C.Gamma | null = null; }\n");
            Path bFile = writeSource(src, "b.deal",
                "import * as A from \"./a\";\n"
                    + "export class Beta { a: A.Alpha | null = null; }\n");
            Path cFile = writeSource(src, "c.deal",
                "import * as B from \"./b\";\n"
                    + "export class Gamma { b: B.Beta | null = null; }\n");
            Path output = outOfOrder.resolve("out");
            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                src.resolve("main.deal").toAbsolutePath(), output, false,
                null, List.of(src.toAbsolutePath()), null);
            check(!compileQuietly(orchestrator),
                "the out-of-node-order cycle fails the compile");
            CompilerDiagnostic e2005 = onlyCycleDiagnostic(
                orchestrator.diagnostics(), "out-of-node-order cycle");
            if (e2005 != null) {
                check(e2005.message().equals("Circular import: "
                        + aFile + " -> " + cFile + " -> " + bFile
                        + " -> " + aFile),
                    "the witness path follows the real edges, not the"
                        + " node order: " + e2005.message());
                check(e2005.range().origin() == RangeOrigin.SOURCE
                        && e2005.range().equals(
                            firstImportSpan(aFile).range()),
                    "the witness anchors at a.deal's import of c: "
                        + e2005.range());
            }
        } finally {
            deleteRecursively(outOfOrder);
        }
    }

    // =========================================================================
    // 11. Digest-free cycle-witness determinism (ISSUE-0784 review)
    // =========================================================================

    /**
     * The probe module paths: a imports b then c (or in the given
     * order); b and c both import a, so a's component has two competing
     * witnesses and the declaration order alone decides which one the
     * E2005 path names.
     */
    private static final String WITNESS_A = "/probe/a.deal";
    private static final String WITNESS_B = "/probe/b.deal";
    private static final String WITNESS_C = "/probe/c.deal";

    /** One synthetic declaration-module input of the witness probe. */
    private static ModuleDependencyGraph.ModuleInput witnessProbeInput(
            String sourcePath, List<String> importTargets) {
        Map<String, DefaultPlanImport> imports = new LinkedHashMap<>();
        for (int i = 0; i < importTargets.size(); i++) {
            String target = importTargets.get(i);
            imports.put("t" + i, new DefaultPlanImport(target,
                target.substring("/probe/".length()),
                new ProgramNode(null, List.of()),
                witnessProbeLocation(target), Map.of(), null, true, false));
        }
        return new ModuleDependencyGraph.ModuleInput(sourcePath,
            sourcePath.substring("/probe/".length()),
            new ProgramNode(null, List.of()),
            witnessProbeLocation(sourcePath), null, null, imports, Map.of(),
            true);
    }

    private static SourceModuleLocation witnessProbeLocation(String path) {
        return new SourceModuleLocation(path, new SemanticModuleIdentity(
            new ProjectDeploymentIdentity("file:///probe/deal.json",
                "0".repeat(64)), "file://" + path),
            "m0000000000000000", null, null);
    }

    /** The identical multi-witness module set with a's import targets in
     * the given declaration order. */
    private static Map<String, ModuleDependencyGraph.ModuleInput>
            witnessProbeModules(List<String> firstImports) {
        Map<String, ModuleDependencyGraph.ModuleInput> modules =
            new LinkedHashMap<>();
        modules.put(WITNESS_A, witnessProbeInput(WITNESS_A, firstImports));
        modules.put(WITNESS_B,
            witnessProbeInput(WITNESS_B, List.of(WITNESS_A)));
        modules.put(WITNESS_C,
            witnessProbeInput(WITNESS_C, List.of(WITNESS_A)));
        return modules;
    }

    /** The first digest-free diagnostic message of the probe graph (no
     * assertions: shared by the in-process checks and the fresh-JVM
     * {@code --digest-free-witness} mode). */
    private static String witnessProbeMessage(List<String> firstImports) {
        ModuleDependencyGraph.DigestFreeResult result =
            ModuleDependencyGraph.digestFreePass(
                witnessProbeModules(firstImports), Map.of(), Map.of(),
                Map.of());
        return result.diagnostics().isEmpty() ? "<no diagnostic>"
            : result.diagnostics().get(0).message();
    }

    /**
     * The digest-free backstop's cycle witness is deterministic: the
     * module input preserves the import-declaration order of its import
     * surface, so a component with two competing witnesses always
     * selects the declaration-order path, in-process and across fresh
     * JVM starts. A hash-ordered import copy let the E2005 message vary
     * with the JVM's iteration salt (ISSUE-0784 review).
     */
    static void testDigestFreeWitnessDeterminism() throws Exception {
        System.out.println("-- Digest-free cycle-witness determinism"
            + " (ISSUE-0784) --");

        // The two competing witnesses: a -> b -> a (b is a's
        // first-declared import) and a -> c -> a. The declaration order
        // decides, so both insertion directions select their own path.
        ModuleDependencyGraph.DigestFreeResult forwardResult =
            ModuleDependencyGraph.digestFreePass(
                witnessProbeModules(List.of(WITNESS_B, WITNESS_C)),
                Map.of(), Map.of(), Map.of());
        check(forwardResult.failed()
                && forwardResult.diagnostics().size() == 1,
            "the multi-witness graph rejects with exactly one E2005: "
                + forwardResult.diagnostics());
        String forward = witnessProbeMessage(List.of(WITNESS_B, WITNESS_C));
        check(forward.equals("Circular import: " + WITNESS_A + " -> "
                + WITNESS_B + " -> " + WITNESS_A),
            "the first-declared import selects the witness: " + forward);
        String reverse = witnessProbeMessage(List.of(WITNESS_C, WITNESS_B));
        check(reverse.equals("Circular import: " + WITNESS_A + " -> "
                + WITNESS_C + " -> " + WITNESS_A),
            "the reversed declaration order selects the other witness: "
                + reverse);

        // The structural half of the same contract: the module input's
        // import surface iterates in insertion order, never a hash
        // order (the adjacency the witness walk consumes).
        List<String> adjacency = new ArrayList<>();
        for (DefaultPlanImport provider : witnessProbeInput(WITNESS_A,
                List.of(WITNESS_B, WITNESS_C)).imports().values()) {
            adjacency.add(provider.sourcePath());
        }
        check(adjacency.equals(List.of(WITNESS_B, WITNESS_C)),
            "the import surface preserves its insertion order: "
                + adjacency);

        // Across JVM starts: six fresh JVM probes of the identical
        // graph must print the identical declaration-order witness. The
        // digest-free rejection is the fail-closed backstop (the
        // production compile rejects at the phase-2 complete-graph
        // admission first), so it is exercised directly.
        String expected = "WITNESS:Circular import: " + WITNESS_A + " -> "
            + WITNESS_B + " -> " + WITNESS_A;
        String classpath = System.getProperty("java.class.path", "");
        for (int run = 1; run <= 6; run++) {
            BoundedProcess.BoundedResult child = BoundedProcess.runCaptured(
                List.of(Path.of(System.getProperty("java.home"), "bin",
                        "java").toString(), "-cp", classpath,
                    CheckedProjectBuilderTest.class.getName(),
                    "--digest-free-witness"));
            check(child.exitCode() == 0,
                "fresh-JVM witness probe " + run + " exits 0: "
                    + child.stderr());
            check(child.stdout().strip().equals(expected),
                "fresh-JVM witness probe " + run + " prints the"
                    + " declaration-order witness: " + child.stdout());
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--digest-free-witness")) {
            // The fresh-JVM determinism probe of
            // testDigestFreeWitnessDeterminism: the identical
            // multi-witness graph must print the identical
            // declaration-order E2005 message in every JVM start.
            System.out.println("WITNESS:" + witnessProbeMessage(
                List.of(WITNESS_B, WITNESS_C)));
            return;
        }
        System.out.println("=== Checked Project Builder Test (ISSUE-0288) ===\n");

        testHostFixtureIndexAndInput();
        testStdlibIndexEntry();
        testJsonableSyntheticExports();
        testCanonicalTypeTextGrammar();
        testRenderingDefects();
        testBuilderE6005Guards();
        testNoFrontendMutation();
        testConstructionEntryDerivation();
        testDeterminismAndDigestGolden();
        testImportCycleRejection();
        testDigestFreeWitnessDeterminism();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
