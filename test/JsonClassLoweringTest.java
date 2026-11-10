package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.diagnostics.*;
import deal.lexer.Lexer;
import deal.parser.Parser;
import deal.semantic.*;
import deal.semantic.ir.*;
import deal.types.Type;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Structural JSON schemas, ordinary provider bodies, origins and exact dependencies. */
public final class JsonClassLoweringTest {
    private static int passed;
    private static int failed;
    private static final ModuleId MAIN = new ModuleId("main");
    private static final ModuleId OWNER = new ModuleId("owner");
    private static final CompilerInvocation INVOCATION = CompilerProfileProvider.resolve(
        ReleaseState.V1_6_ACTIVE, CapabilityRegistry.releaseRegistry());
    private static final String SOURCE = """
        // @jsonable: arbitrary comment arguments
        export class Point { x: number; tag: string; note?: string; }
        export function decode(s: string): Point { return JSON.parse(s); }
        export function encode(p: Point): string { return JSON.stringify(p) as string; }
        export function main(): null { let p: Point = decode(`{"x":1,"tag":"ok"}`); encode(p); return null; }
        """;

    private static void check(boolean condition, String message) {
        if (condition) passed++;
        else { failed++; System.err.println("FAIL: " + message); }
    }
    private record Slice(ProgramNode program, SymbolTable symbols, CheckResult checks,
                         Map<String, Type> exports) {}
    private record Pipeline(CheckedProjectBuildResult built, RequirementManifestResult manifests) {}

    private static Slice checked(String source, ModuleId id, ModuleResolver resolver) {
        var lex = new Lexer(source, id.path() + ".deal").tokenize();
        check(lex.diagnostics().isEmpty(), "clean lexing: " + lex.diagnostics());
        var parse = new Parser(lex.tokens(), id.path() + ".deal").parse();
        check(parse.diagnostics().isEmpty(), "clean parsing: " + parse.diagnostics());
        if (!parse.diagnostics().isEmpty()) return null;
        var names = new NameResolver(id.path(), resolver);
        var symbols = names.resolve(parse.program());
        var result = TypeChecker.check(id.path(), symbols, names, parse.program());
        check(names.diagnostics().isEmpty(), "all annotation resolution: " + names.diagnostics());
        check(result.diagnostics().isEmpty(), "clean checking: " + result.diagnostics());
        if (!names.diagnostics().isEmpty() || result.hasErrors()) return null;
        Map<String, Type> exports = new LinkedHashMap<>();
        for (StatementNode statement : parse.program().statements()) {
            if (!(statement instanceof ExportDeclaration export)) continue;
            if (export.declaration() instanceof ClassDeclaration cls) {
                var symbol = (Symbol.ClassSymbol) symbols.resolve(cls.name());
                exports.put(cls.name(), deal.types.Types.classType(symbol.name(), symbol.identity(),
                    deal.types.Types.providerModule(symbol.modulePath())));
            } else if (export.declaration() instanceof FunctionDeclaration function) {
                exports.put(function.name(), ((Symbol.FunctionSymbol) symbols.resolve(function.name())).funcType());
            }
        }
        return new Slice(parse.program(), symbols, result, exports);
    }
    private static Pipeline pipeline(LinkedHashMap<ModuleId, Slice> slices) {
        List<ModuleFact> facts = new ArrayList<>();
        for (var entry : slices.entrySet()) {
            ModuleId id = entry.getKey();
            Slice slice = entry.getValue();
            facts.add(new ModuleFact(id.path() + ".deal", id, false, false, slice.program(),
                slice.exports(), slice.symbols(), slice.checks(),
                id.equals(MAIN) && slices.containsKey(OWNER)
                    ? List.of(new ModuleFact.ImportFact("Owner", "owner", "owner.deal")) : List.of()));
        }
        var built = CheckedProjectBuilder.build(INVOCATION, MAIN, facts);
        check(!built.hasErrors() && built.input() != null && built.index() != null,
            "checked project: " + built.diagnostics());
        if (built.hasErrors() || built.input() == null) return null;
        var manifests = LoweringSupport.computeManifests(INVOCATION, built.input(), built.index());
        check(manifests.diagnostics().isEmpty() && manifests.manifests().size() == slices.size(),
            "complete manifest population: " + manifests.diagnostics());
        return manifests.diagnostics().isEmpty() ? new Pipeline(built, manifests) : null;
    }
    private static SemanticLowerer.ProjectLoweringResult lower(Pipeline pipeline) {
        var result = SemanticLowerer.lowerProject(INVOCATION, pipeline.built().input(),
            pipeline.built().index(), pipeline.manifests().manifests(),
            new HostDeclarationSurface(Map.of()), Map.of(),
            BuiltinErrorDeclaration.synthesized(pipeline.built().input().modules().getFirst().ast().span()), Set.of());
        check(result.project() != null && result.diagnostics().isEmpty(),
            "validated project lowering: " + result.diagnostics());
        return result;
    }
    private static Pipeline single(Slice slice) {
        var slices = new LinkedHashMap<ModuleId, Slice>();
        slices.put(MAIN, slice);
        return pipeline(slices);
    }
    private static List<SemanticOp> kind(LoweredModuleUnit unit, SemanticOpKind kind) {
        return unit.ops().stream().filter(op -> op.kind() == kind).toList();
    }
    private static SemanticOp op(LoweredModuleUnit unit, OpId id) {
        return unit.ops().stream().filter(candidate -> candidate.opId().equals(id)).findFirst().orElse(null);
    }
    private static void testBodiesAndSchemas() {
        Slice slice = checked(SOURCE, MAIN, null);
        if (slice == null) return;
        Pipeline pipeline = single(slice);
        if (pipeline == null) return;
        var result = lower(pipeline);
        if (result.project() == null) return;
        var unit = result.project().modules().get(MAIN);
        var point = new ClassId("main", "Point");
        var layout = unit.classLayouts().get(point);
        check(layout != null, "explicit Point layout exists");
        if (layout == null) return;
        check(layout.fields().stream().map(ClassLayout.FieldLayout::name).toList()
            .equals(List.of("x", "tag", "note")), "schema declaration order");
        check(layout.fields().get(0).descriptor() == RuntimeDescriptor.Number.INSTANCE, "number field descriptor");
        check(layout.fields().get(1).descriptor() == RuntimeDescriptor.String.INSTANCE, "string field descriptor");
        check(layout.fields().get(0).required() && layout.fields().get(1).required(), "required fields");
        check(!layout.fields().get(2).required(), "optional presence is separate from descriptor");
        check(layout.fields().get(2).descriptor() == RuntimeDescriptor.String.INSTANCE, "optional storage descriptor");
        check(slice.symbols().resolve("Point$fromJson") == null, "no decoder symbol synthesis");
        check(slice.symbols().resolve("Point$toJson") == null, "no encoder symbol synthesis");
        check(pipeline.built().index().modules().get(MAIN).exports().stream().map(ExportInterface::name).collect(java.util.stream.Collectors.toSet())
            .equals(Set.of("Point", "decode", "encode", "main")), "only explicit exports");
        check(unit.ops().stream().noneMatch(op -> op.kind().name().equals("JSON_FROM_CLASS")
            || op.kind().name().equals("JSON_TO_CLASS")), "no legacy class JSON operations");
        var parses = kind(unit, SemanticOpKind.JSON_PARSE);
        check(parses.size() == 1, "one explicit contextual parse");
        if (parses.size() != 1) return;
        SemanticOp parse = parses.getFirst();
        var payload = (KindPayload.JsonParsePayload) parse.payload();
        check(payload.target().equals(new RuntimeDescriptor.Class(point)), "return annotation owns target");
        check(parse.resultType().equals(payload.target()), "target is exact result descriptor, not nullable helper result");
        check(parse.failurePolicy() == FailurePolicyId.JSON_RECONSTRUCTION, "parse throws reconstruction failure");
        check(parse.origin().kind() == SourceOriginKind.USER, "parse is a user expression");
        check(parse.origin().span().startLine() == 3 && parse.origin().span().startColumn() == 51,
            "parse origin independently pinned to expression 3:51");
        var producers = unit.ops().stream().filter(candidate -> payload.jsonString().equals(candidate.result())).toList();
        check(producers.size() == 1, "one parse operand producer");
        if (producers.size() == 1) {
            SemanticOp producer = producers.getFirst();
            check(producer.resultType() == RuntimeDescriptor.String.INSTANCE, "string input descriptor");
            check(unit.ops().indexOf(producer) < unit.ops().indexOf(parse), "input precedes parse");
            check(producer.kind() == SemanticOpKind.BINDING_LOAD, "input comes from ordinary parameter");
        }
        var stringify = kind(unit, SemanticOpKind.STDLIB_CALL).stream().filter(candidate ->
            candidate.payload() instanceof KindPayload.StdlibCallPayload call
                && call.function() == StdlibFunctionId.JSON_STRINGIFY).toList();
        check(stringify.size() == 1, "one builtin stringify, no imported JSON namespace");
        if (stringify.size() == 1) {
            SemanticOp encode = stringify.getFirst();
            var call = (KindPayload.StdlibCallPayload) encode.payload();
            check(encode.failurePolicy() == FailurePolicyId.JSON_TO_ERROR,
                "stringify retains its throwing encoding policy");
            check(encode.resultType().equals(new RuntimeDescriptor.Nullable(RuntimeDescriptor.String.INSTANCE, false, true)),
                "stringify result is string|undefined before the explicit assertion");
            check(encode.origin().kind() == SourceOriginKind.USER
                && encode.origin().span().startLine() == 4 && encode.origin().span().startColumn() == 51,
                "stringify origin independently pinned to expression 4:51");
            check(call.args().size() == 1, "stringify has exactly one value operand");
            if (call.args().size() == 1) {
                var inputs = unit.ops().stream().filter(candidate -> call.args().getFirst().equals(candidate.result())).toList();
                check(inputs.size() == 1, "stringify operand has exactly one producer");
                if (inputs.size() == 1) {
                    SemanticOp input = inputs.getFirst();
                    check(input.kind() == SemanticOpKind.BINDING_LOAD
                        && input.resultType().equals(new RuntimeDescriptor.Class(point)),
                        "stringify consumes the exact nominal parameter, not a projected copy");
                    check(unit.ops().indexOf(input) < unit.ops().indexOf(encode),
                        "stringify parameter read precedes encoding");
                }
            }
        }
        check(unit.functions().values().stream().map(LoweredFunction::sourceName).collect(java.util.stream.Collectors.toSet())
            .containsAll(Set.of("decode", "encode", "main")), "ordinary provider bodies recorded");
        check(unit.functions().values().stream().noneMatch(function -> function.sourceName() != null && function.sourceName().contains("$")),
            "no manufactured helper body");
        for (String name : List.of("decode", "encode")) {
            var function = unit.functions().values().stream().filter(candidate -> name.equals(candidate.sourceName())).findFirst().orElse(null);
            check(function != null, name + " function record");
            if (function == null) continue;
            check(function.captures().isEmpty(), name + " has no captures");
            RuntimeDescriptor parameter = name.equals("decode") ? RuntimeDescriptor.String.INSTANCE
                : new RuntimeDescriptor.Class(point);
            RuntimeDescriptor returned = name.equals("decode") ? new RuntimeDescriptor.Class(point)
                : RuntimeDescriptor.String.INSTANCE;
            var signature = new RuntimeDescriptor.Func(List.of(parameter), returned, false);
            check(function.descriptor().equals(signature), name + " exact ordinary synchronous signature");
            var closures = kind(unit, SemanticOpKind.CLOSURE_NEW).stream().filter(candidate ->
                candidate.payload() instanceof KindPayload.ClosureNewPayload closure
                    && closure.function().equals(function.functionId())).toList();
            check(closures.size() == 1, name + " exactly one closure producer");
            if (closures.size() == 1) {
                SemanticOp closure = closures.getFirst();
                var creation = (KindPayload.ClosureNewPayload) closure.payload();
                check(creation.signature().equals(signature) && closure.resultType().equals(signature)
                    && creation.captures().isEmpty(), name + " closure signature and empty captures");
                check(closure.failurePolicy() == FailurePolicyId.NO_DEAL_FAILURE,
                    name + " closure allocation policy");
                check(creation.binding().functionId().equals(function.functionId())
                    && creation.binding().blockId().equals(function.body()), name + " exact creation body binding");
                check(unit.functionBindings().get(new FunctionAllocationIdentity(((ValueId) closure.result()).id()))
                    .equals(creation.binding()), name + " own allocation identity registers that same body");
            }
            var ids = unit.bodyTable().blockOps().get(function.body());
            check(ids != null && !ids.isEmpty(), name + " owned body block");
            if (ids == null) continue;
            var body = ids.stream().map(id -> op(unit, id)).toList();
            check(body.stream().anyMatch(candidate -> candidate.kind() == SemanticOpKind.BINDING_ALLOC), name + " parameter allocation");
            check(body.stream().anyMatch(candidate -> candidate.kind() == SemanticOpKind.BINDING_LOAD), name + " parameter read");
            var reads = body.stream().filter(candidate -> candidate.kind() == SemanticOpKind.BINDING_LOAD).toList();
            check(reads.size() == 1, name + " exactly one parameter read");
            if (reads.size() == 1) {
                SemanticOp read = reads.getFirst();
                var load = (KindPayload.BindingLoadPayload) read.payload();
                check(read.resultType().equals(parameter) && load.generation() == 0,
                    name + " exact parameter descriptor and generation");
                var allocations = body.stream().filter(candidate -> candidate.payload() instanceof KindPayload.BindingAllocPayload alloc
                    && alloc.binding().equals(load.binding())).toList();
                check(allocations.size() == 1, name + " parameter read resolves its own allocation");
                if (allocations.size() == 1) {
                    SemanticOp allocation = allocations.getFirst();
                    var alloc = (KindPayload.BindingAllocPayload) allocation.payload();
                    check(alloc.mutable() && alloc.generation() == 0
                        && body.indexOf(allocation) < body.indexOf(read), name + " mutable generation-zero parameter allocated before read");
                }
            }
            check(body.stream().anyMatch(candidate -> candidate.kind() == SemanticOpKind.RETURN), name + " explicit return");
            check(body.stream().anyMatch(candidate -> candidate.kind() == (name.equals("decode")
                ? SemanticOpKind.JSON_PARSE : SemanticOpKind.STDLIB_CALL)), name + " actual algorithm in body");
            check(unit.functionBindings().values().stream().anyMatch(binding ->
                binding instanceof FunctionExecutionBinding.LoweredBody bodyBinding
                    && bodyBinding.functionId().equals(function.functionId())
                    && bodyBinding.blockId().equals(function.body())),
                name + " closure registers its exact ordinary body");
        }
        check(unit.ops().stream().noneMatch(candidate -> candidate.kind().name().contains("DEFAULT")), "no initializer operations");
        var again = lower(single(checked(SOURCE, MAIN, null)));
        check(again.project() != null && Arrays.equals(SemanticIrDumper.dumpModule(unit),
            SemanticIrDumper.dumpModule(again.project().modules().get(MAIN))), "single-module deterministic bytes");
    }
    private static void testCommentOnlyClasses() {
        for (String comment : List.of("", "// @jsonable\n", "/* @jsonable: x */\n", "// @deal-version: nonsense\n")) {
            Slice slice = checked(comment + "export class Plain { x:number; }", MAIN, null);
            if (slice == null) continue;
            Pipeline pipeline = single(slice);
            if (pipeline == null) continue;
            var result = lower(pipeline);
            if (result.project() == null) continue;
            var unit = result.project().modules().get(MAIN);
            check(unit.classLayouts().size() == 1, "comment-only class has structural schema");
            check(unit.functions().isEmpty(), "class comment generates no functions");
            check(kind(unit, SemanticOpKind.CLOSURE_NEW).isEmpty(), "class comment generates no closures");
            check(kind(unit, SemanticOpKind.JSON_PARSE).isEmpty(), "class comment generates no parse");
            check(unit.ops().stream().noneMatch(op -> op.kind().name().equals("JSON_FROM_CLASS")
                || op.kind().name().equals("JSON_TO_CLASS")), "class comment generates no legacy operations");
            check(pipeline.built().index().modules().get(MAIN).exports().stream().map(ExportInterface::name).toList().equals(List.of("Plain")), "explicit class export only");
        }
    }
    private static void testAnnotationOrigins() {
        String source = "// @jsonable\nclass Leaf { n:number; }\nexport class Widget { child:Leaf; }\n";
        for (boolean offsetless : List.of(false, true)) {
            for (String name : List.of("MissingLeaf", "Absent")) {
                Slice slice = checked(source, MAIN, null);
                if (slice == null) continue;
                Pipeline pipeline = single(slice);
                if (pipeline == null) continue;
                ClassDeclaration widget = (ClassDeclaration) ((ExportDeclaration) slice.program().statements().get(1)).declaration();
                ClassField field = widget.fields().getFirst();
                Span span = offsetless ? new Span("writer.deal", 3, 29, 3, 33) : field.type().span();
                var annotation = new NamedType(span, name);
                var replacement = new ClassDeclaration(widget.span(), widget.name(), List.of(
                    new ClassField(field.span(), field.name(), field.optional(), field.nullable(), annotation)));
                var program = new ProgramNode(slice.program().span(), List.of(slice.program().statements().getFirst(),
                    new ExportDeclaration(widget.span(), replacement)));
                var coverage = new LinkedHashMap<>(pipeline.manifests().manifests().getFirst().constructCoverage());
                coverage.remove(ConstructKind.IMPORT_EXPORT_ENTRY);
                var result = SemanticLowerer.lowerModuleClassCore(new CheckedModuleInput(MAIN, "main.deal", Path.of("main.deal"),
                    program, slice.checks(), List.of(), List.of(), CheckedModuleKind.IMPLEMENTATION),
                    INVOCATION.semanticProfile(), coverage, pipeline.built().index().interfaceIndexDigest(),
                    INVOCATION.capabilityRegistryHash(), pipeline.built().index().modules().get(MAIN),
                    SemanticIdAllocator.over(List.of(MAIN)));
                check(result.lowering().hasErrors() && result.lowering().unit() == null, "consumed missing annotation fact rejects without unit");
                if (!result.lowering().hasErrors()) continue;
                var diagnostic = result.lowering().diagnostics().getFirst();
                check(diagnostic.code().equals("E6005"), "annotation fact defect is E6005");
                check(diagnostic.message().contains("unknown type '" + name + "'"), "exact missing annotation fact named: " + diagnostic);
                check(diagnostic.range().equals(span.range()), "annotation's own range, not helper/class/call range");
                var range = diagnostic.range();
                if (offsetless) {
                    check(range.origin() == RangeOrigin.SYNTHETIC && range.file().equals("writer.deal"), "known offsetless file retained");
                    check(range.startLine() == 1 && range.startColumn() == 1 && range.endLine() == 1 && range.endColumn() == 1,
                        "offsetless canonical anchor");
                    check(range.startScalarOffset() == 0 && range.endScalarOffset() == 0 && range.scalarLength() == 0,
                        "offsetless zero range");
                } else {
                    check(range.origin() == RangeOrigin.SOURCE && range.file().equals("main.deal"), "source annotation file retained");
                    check(range.startLine() == 3 && range.startColumn() == 29 && range.endColumn() == 33, "independent annotation coordinates");
                    check(range.startScalarOffset() == source.lastIndexOf("Leaf") && range.scalarLength() == 4,
                        "independent annotation offsets");
                }
            }
        }
    }
    private static void testDependencies() {
        Slice owner = checked("export class Address { city:string; zip:number; }", OWNER, null);
        if (owner == null) return;
        var ownerClass = (Symbol.ClassSymbol) owner.symbols().resolve("Address");
        ModuleResolver resolver = new ModuleResolver() {
            public Map<String, Type> resolveModule(String path, String importing, Set<String> active) throws ModuleNotFoundException {
                if (path.equals("owner")) return owner.exports();
                throw new ModuleNotFoundException(path);
            }
            public Symbol.ClassSymbol resolveClassSymbol(String name, String path, String importing) {
                return name.equals("Address") ? ownerClass : null;
            }
            public Symbol.ClassSymbol resolveClassSymbol(String name, deal.identity.CanonicalModuleIdentity identity, String importing) {
                return name.equals("Address") && identity.equals(ownerClass.identity().moduleIdentity()) ? ownerClass : null;
            }
        };
        String source = """
            import * as Owner from "owner";
            // @jsonable
            export class Person { name:string; home:Owner.Address; }
            export class Strict { tag:string; }
            export function person(s:string):Person { return JSON.parse(s); }
            export function strict(s:string):Strict { return JSON.parse(s); }
            export function main():null { return null; }
            """;
        Slice main = checked(source, MAIN, resolver);
        if (main == null) return;
        var slices = new LinkedHashMap<ModuleId, Slice>();
        slices.put(OWNER, owner); slices.put(MAIN, main);
        Pipeline pipeline = pipeline(slices);
        if (pipeline == null) return;
        var first = lower(pipeline);
        var second = lower(pipeline);
        if (first.project() == null || second.project() == null) return;
        var caller = first.project().modules().get(MAIN);
        check(kind(caller, SemanticOpKind.JSON_PARSE).size() == 2, "Person/Strict each own an explicit parse");
        check(!caller.classLayouts().containsKey(new ClassId("owner", "Address")), "dependency schema not relocated into caller");
        var person = caller.classLayouts().get(new ClassId("main", "Person"));
        check(person != null && person.fields().get(1).descriptor().equals(new RuntimeDescriptor.Class(new ClassId("owner", "Address"))),
            "exact dependency nominal descriptor");
        var ownerUnit = first.project().modules().get(OWNER);
        for (var cls : pipeline.built().index().modules().get(OWNER).classes()) {
            OpId factoryId = ownerUnit.classFactories().factoryFor(cls.constructionEntry());
            check(factoryId != null && op(ownerUnit, factoryId).kind() == SemanticOpKind.CLASS_FACTORY, "construction entry still resolves owner factory");
        }
        for (ModuleId id : List.of(OWNER, MAIN)) {
            byte[] bytes = SemanticIrDumper.dumpModule(first.project().modules().get(id));
            check(Arrays.equals(bytes, SemanticIrDumper.dumpModule(second.project().modules().get(id))), id + " dependency-order deterministic bytes");
            String dump = new String(bytes, StandardCharsets.UTF_8);
            check(!dump.contains("jsonDefaults") && !dump.contains("$fromJson") && !dump.contains("$toJson"), id + " no retired helper/default transport");
        }

        // Remove a checked fact the existing qualified field actually consumes.
        // Keep the AST, exports, import records and all executable calls unchanged.
        check(main.symbols().resolve("Owner") instanceof Symbol.ModuleSymbol,
            "qualified annotation baseline has its actual module symbol");
        main.symbols().remove("Owner");
        var coverage = new LinkedHashMap<>(pipeline.manifests().manifests().getLast().constructCoverage());
        coverage.remove(ConstructKind.IMPORT_EXPORT_ENTRY);
        var broken = SemanticLowerer.lowerModuleClassCore(pipeline.built().input().modules().getLast(),
            INVOCATION.semanticProfile(), coverage, pipeline.built().index().interfaceIndexDigest(),
            INVOCATION.capabilityRegistryHash(), pipeline.built().index().modules().get(MAIN),
            SemanticIdAllocator.over(List.of(OWNER, MAIN)));
        check(broken.lowering().unit() == null && broken.lowering().hasErrors(),
            "removed consumed module annotation fact rejects without a partial unit");
        if (broken.lowering().hasErrors()) {
            var diagnostic = broken.lowering().diagnostics().getFirst();
            var range = diagnostic.range();
            check(diagnostic.code().equals("E6005") && diagnostic.message().contains("unknown module 'Owner'"),
                "removed module fact names the qualified annotation: " + diagnostic);
            check(range.origin() == RangeOrigin.SOURCE && range.file().equals("main.deal")
                && range.startLine() == 3 && range.startColumn() == 41 && range.endColumn() == 54
                && range.startScalarOffset() == source.indexOf("Owner.Address") && range.scalarLength() == 13,
                "removed fact keeps independently pinned qualified annotation range: " + range);
        }
    }
    public static void main(String[] args) {
        testCommentOnlyClasses();
        testBodiesAndSchemas();
        testAnnotationOrigins();
        testDependencies();
        System.out.println("JsonClassLoweringTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }
}
