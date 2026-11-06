package deal.test;

import deal.Main;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real production compilation and execution; no backend may be skipped. */
public final class Language16RuntimeTest {
    private static void run(Path cwd, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(cwd.toFile())
            .redirectErrorStream(true).start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Timed out: " + Arrays.toString(command));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0 || !output.isBlank()) {
            throw new AssertionError(Arrays.toString(command) + " exit=" + process.exitValue() + "\n" + output);
        }
    }

    private static void rejectsRaw(String text, String needle, String replacement,
            deal.semantic.ir.SemanticIrValidator.ComparisonFacts facts) {
        if (!text.contains(needle)) throw new AssertionError("Missing raw fixture: " + needle);
        var failure = deal.semantic.ir.SemanticIrValidator.validateText(
            text.replace(needle, replacement), facts);
        if (failure.isEmpty() || !failure.get().message().contains("R-ENUM")) {
            throw new AssertionError("Expected structural R-ENUM rejection: " + failure);
        }
    }

    private static deal.semantic.ir.CanonicalJson.Obj field(deal.semantic.ir.CanonicalJson.Obj object,
            String key, deal.semantic.ir.CanonicalJson.Value value) {
        return new deal.semantic.ir.CanonicalJson.Obj(object.entries().stream()
            .map(e -> e.key().equals(key) ? new deal.semantic.ir.CanonicalJson.Entry(key, value) : e).toList());
    }

    private static deal.semantic.ir.CanonicalJson.Value mutateOp(deal.semantic.ir.CanonicalJson.Value value,
            String kind, java.util.function.UnaryOperator<deal.semantic.ir.CanonicalJson.Obj> mutation,
            boolean[] changed) {
        if (value instanceof deal.semantic.ir.CanonicalJson.Obj object) {
            boolean matches = object.entries().stream().anyMatch(e -> e.key().equals("kind")
                && e.value() instanceof deal.semantic.ir.CanonicalJson.Str s && s.value().equals(kind));
            if (matches && !changed[0]) { changed[0] = true; return mutation.apply(object); }
            return new deal.semantic.ir.CanonicalJson.Obj(object.entries().stream()
                .map(e -> new deal.semantic.ir.CanonicalJson.Entry(e.key(), mutateOp(e.value(), kind, mutation, changed))).toList());
        }
        if (value instanceof deal.semantic.ir.CanonicalJson.Arr array)
            return new deal.semantic.ir.CanonicalJson.Arr(array.items().stream()
                .map(v -> mutateOp(v, kind, mutation, changed)).toList());
        return value;
    }

    private static void rejectsOp(String raw, String kind,
            java.util.function.UnaryOperator<deal.semantic.ir.CanonicalJson.Obj> mutation,
            deal.semantic.ir.SemanticIrValidator.ComparisonFacts facts) {
        boolean[] changed = {false};
        String text = deal.semantic.ir.CanonicalJson.serializeText(mutateOp(
            deal.semantic.ir.CanonicalJson.parse(raw), kind, mutation, changed));
        if (!changed[0]) throw new AssertionError("Missing operation " + kind);
        var failure = deal.semantic.ir.SemanticIrValidator.validateText(text, facts);
        if (failure.isEmpty() || !failure.get().message().contains("validatorRule R-ENUM,"))
            throw new AssertionError("Malformed " + kind + " must fail structural admission: " + failure);
    }

    private static deal.semantic.ir.CanonicalJson.Obj payload(deal.semantic.ir.CanonicalJson.Obj op) {
        return (deal.semantic.ir.CanonicalJson.Obj) op.entries().stream()
            .filter(e -> e.key().equals("payload")).findFirst().orElseThrow().value();
    }

    public static void main(String[] args) throws Exception {
        var invalid = deal.semantic.ir.BoundaryValueView.ofInvalidUnicode(deal.semantic.ir.UnicodeScalars.Defect.SURROGATE);
        var unknown = deal.semantic.ir.BoundaryExecutor.check(deal.semantic.ir.FailurePolicyId.TYPE_DESCRIPTOR,
            deal.semantic.ir.RuntimeDescriptor.Unknown.INSTANCE, invalid, deal.semantic.ir.BoundaryContext.none());
        if (!(unknown instanceof deal.semantic.ir.BoundaryOutcome.Fail))
            throw new AssertionError("unknown accepted invalid Unicode");
        String validText = "valid 🌍";
        if (deal.codegen.jvm.JvmRuntime.bcheck("unknown", "ref", validText) != validText)
            throw new AssertionError("unknown changed valid string identity");
        if (!deal.codegen.jvm.JvmRuntime.bcheck("unknown", "ref", "\ud800").equals("\ud800"))
            throw new AssertionError("lone unit changed at JVM ingress");
        for (Object unsupported : List.of(new Object())) {
            try { deal.codegen.jvm.JvmRuntime.bcheck("unknown", "ref", unsupported);
                throw new AssertionError("unknown accepted unsupported JVM value");
            } catch (deal.codegen.jvm.JvmRuntime.DealError expected) {
                if (!expected.code.equals("E8001")) throw new AssertionError(expected);
            }
        }
        objectKeysAdmission(false);
        objectKeysAdmission(true);
        arrayRemovalAdmission(false);
        arrayRemovalAdmission(true);
        callableBodyAdmission();
        errorGraphAdmission();
        for (String fixture : List.of("core", "refinement", "assertion", "failures", "equality-loops", "json-values", "audit-scalars")) executeFixture(fixture);
        System.out.println("Language16RuntimeTest: passed (Oracle, JS, LuaJIT, JVM; deployed array descriptors)");
    }

    private static void objectKeysAdmission(boolean hostile) throws Exception {
        Path work = Files.createTempDirectory(Path.of("build"), "keys-admission-").toAbsolutePath();
        try {
            Path source = Files.createDirectories(work.resolve("src")).resolve("main.deal");
            Files.writeString(source, hostile ? """
                import * as host from "host/probe";
                class Item{x:number;stable:number;}
                export function main():null{return null;}
                export function run():null{
                  const item:Item={x:9,stable:4};const r:Record<string,unknown>={f:():number=>9,child:item};
                  r.self=r;host.keep(r);host.corrupt();host.damage(item,false);
                  const a=Object.keys(r);const b=Object.keys(item);
                  if(a.length!==3 || a[0]!=="f" || a[1]!=="child" || a[2]!=="self"
                     || b.length!==2 || b[0]!=="x" || b[1]!=="stable"){
                    throw {code:"KEYS",message:"hostile values consumed"};}
                  return null;
                }
                """ : ConformanceHarnessMetadata.stripClassificationHeaders(Files.readString(
                    Path.of("test/conformance/backend-runtime/tables/builtin-object-keys.deal")))
                    .replace("export function main():null", "export function run():null")
                    + "\nexport function main():null{return null;}\n");
            Path declaration = source.resolveSibling("probe.d.deal");
            Files.writeString(declaration, Files.readString(Path.of("test/conformance/host-fixtures/array_pop.d.deal")));
            Files.writeString(work.resolve("deal.json"),
                "{\"languageVersion\":\"1.6\",\"stdlib\":\"1.6\",\"moduleRoots\":[\"src\"]}");
            var invocation = deal.semantic.CompilerProfileProvider.resolve(
                deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
            var compiler = new deal.module.CompilationOrchestrator(source, work.resolve("out"),
                false, false, false, false, deal.codegen.Backend.JS,
                hostile ? Map.of("host/probe", declaration.toString()) : Map.of(), List.of(source.getParent()), null, null, invocation);
            if (!compiler.compile()) throw new AssertionError("keys Oracle compilation: " + compiler.diagnostics());
            var project = Objects.requireNonNull(compiler.producedProject());
            var facts = new deal.semantic.ir.SemanticIrValidator.ComparisonFacts(
                project.interfaceIndex().interfaceIndexDigest(), project.semanticProfile(), invocation.capabilityRegistryHash());
            String raw = deal.semantic.ir.SemanticIrValidator.toProjectText(project);
            if (deal.semantic.ir.SemanticIrValidator.validateText(raw, facts).isPresent())
                throw new AssertionError("keys project transport");
            rejectsOp(raw, "OBJECT_KEYS", op -> field(op, "payload", field(payload(op), "receiver",
                deal.semantic.ir.CanonicalJson.Null.INSTANCE)), facts);
            rejectsOp(raw, "OBJECT_KEYS", op -> field(op, "payload", new deal.semantic.ir.CanonicalJson.Obj(List.of())), facts);
            var tables = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.StructuredBodyTable>();
            var registries = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.ClassFactoryRegistry>();
            project.modules().forEach((id, unit) -> { tables.put(id, unit.bodyTable()); registries.put(id, unit.classFactories()); });
            var responder = new deal.semantic.SemanticOracle.HostResponder() {
                private deal.semantic.SemanticOracle.Value.TableValue saved;
                public SyncOutcome call(deal.semantic.ir.ModuleId module, String export,
                        deal.semantic.ir.RuntimeDescriptor.Func descriptor,
                        List<deal.semantic.SemanticOracle.Value> arguments) {
                    switch(export) {
                        case "keep" -> saved=(deal.semantic.SemanticOracle.Value.TableValue)arguments.getFirst();
                        case "corrupt" -> saved.entries().put("f", new deal.semantic.SemanticOracle.Value.FuncValue(null,
                            (deal.semantic.ir.RuntimeDescriptor.Func)deal.semantic.ir.RuntimeDescriptor.parseCanonicalText("()->number"), Map.of()));
                        case "damage" -> ((deal.semantic.SemanticOracle.Value.ClassValue)arguments.getFirst()).fields().set(0,
                            new deal.semantic.SemanticOracle.Value.ClassFieldState.Present(new deal.semantic.SemanticOracle.Value.StrValue("bad")));
                        default -> throw new AssertionError(export);
                    }
                    return new SyncOutcome.Returned(deal.semantic.SemanticOracle.Value.UndefinedValue.INSTANCE);
                }
            };
            var result = deal.semantic.SemanticOracle.invokeSyncEntry(project, tables, registries,
                responder, compiler.checkedProject().input().entryModule(), "run", List.of());
            if (!(result.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success success)
                    || !success.resultAtom().equals("null"))
                throw new AssertionError("keys Oracle: " + result.comparisonReport());
            long snapshots = result.trace().stream().filter(e -> e.kind()==deal.semantic.ir.SemanticOpKind.OBJECT_KEYS
                && e.phase()==deal.semantic.SemanticRuntimeModel.Phase.SUCCESS).count();
            if (snapshots!=(hostile ? 2 : 11)) throw new AssertionError("keys Oracle execution population: " + snapshots);
        } finally { CompositionProductionTest.remove(work); }
    }

    /** Execute the same retained-mutation contract in the Oracle with genuine lowered bodies. */
    private static void arrayRemovalAdmission(boolean splice) throws Exception {
        String fixture = splice ? "splice-live-retry" : "pop-live-retry";
        String operation = splice ? "ARRAY_SPLICE" : "ARRAY_POP";
        Path work = Files.createTempDirectory(Path.of("build"), "pop-admission-").toAbsolutePath();
        try {
            Path source = Files.createDirectories(work.resolve("src")).resolve("main.deal");
            Files.writeString(source, ConformanceHarnessMetadata.stripClassificationHeaders(Files.readString(
                Path.of("test/conformance/backend-runtime/arrays/" + fixture + ".deal")))
                .replace("export function main():null", "export function run():null")
                + "\nexport function main():null{return null;}\n");
            Path declaration = source.resolveSibling("probe.d.deal");
            Files.copy(Path.of("test/conformance/host-fixtures/array_pop.d.deal"), declaration);
            Files.writeString(work.resolve("deal.json"),
                "{\"languageVersion\":\"1.6\",\"stdlib\":\"1.6\",\"moduleRoots\":[\"src\"]}");
            var invocation = deal.semantic.CompilerProfileProvider.resolve(
                deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
            var compiler = new deal.module.CompilationOrchestrator(source, work.resolve("out"),
                false, false, false, false, deal.codegen.Backend.JS,
                Map.of("host/probe", declaration.toString()), List.of(source.getParent()), null, null, invocation);
            if (!compiler.compile()) throw new AssertionError("pop Oracle compile failed: " + compiler.diagnostics());
            var project = Objects.requireNonNull(compiler.producedProject());
            var facts = new deal.semantic.ir.SemanticIrValidator.ComparisonFacts(
                project.interfaceIndex().interfaceIndexDigest(), project.semanticProfile(), invocation.capabilityRegistryHash());
            String raw = deal.semantic.ir.SemanticIrValidator.toProjectText(project);
            if (deal.semantic.ir.SemanticIrValidator.validate(project, facts).isPresent()
                    || deal.semantic.ir.SemanticIrValidator.validateText(raw, facts).isPresent())
                throw new AssertionError(operation + " project transport rejected: "
                    + deal.semantic.ir.SemanticIrValidator.validate(project, facts) + " / "
                    + deal.semantic.ir.SemanticIrValidator.validateText(raw, facts));
            for (var unit : project.modules().values()) {
                var rebuilt = deal.semantic.ir.RawUnitMaterializer.materialize(deal.semantic.ir.RawUnit.fromTyped(unit));
                if (!deal.semantic.ir.SemanticIrValidator.toUnitText(unit).equals(
                        deal.semantic.ir.SemanticIrValidator.toUnitText(rebuilt)))
                    throw new AssertionError("pop typed/raw roundtrip changed content");
            }
            var empty = new deal.semantic.ir.CanonicalJson.Arr(List.of());
            var missing = deal.semantic.ir.ContractSnapshotCanonicalizer.semanticIdJson(
                new deal.semantic.ir.ValueId(Integer.MAX_VALUE));
            rejectsOp(raw, operation, op -> field(op, "payload", field(payload(op), "arrayValue", missing)), facts);
            rejectsOp(raw, operation, op -> field(op, "payload", field(payload(op), "arrayValue",
                deal.semantic.ir.CanonicalJson.Null.INSTANCE)), facts);
            rejectsOp(raw, operation, op -> field(field(op, "operands", empty), "operandTypes", empty), facts);
            if (!splice) rejectsOp(raw, operation, op -> field(op, "operandTypes", new deal.semantic.ir.CanonicalJson.Arr(
                List.of(new deal.semantic.ir.CanonicalJson.Str("[string]")))), facts);
            if (splice) {
                for (String key : List.of("start", "deleteCount")) {
                    rejectsOp(raw, operation, op -> field(op, "payload", field(payload(op), key, missing)), facts);
                    rejectsOp(raw, operation, op -> field(op, "payload", field(payload(op), key,
                        deal.semantic.ir.CanonicalJson.Null.INSTANCE)), facts);
                }
            }
            rejectsOp(raw, operation, op -> field(op, "resultType", new deal.semantic.ir.CanonicalJson.Str("number")), facts);
            var tables = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.StructuredBodyTable>();
            var registries = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.ClassFactoryRegistry>();
            project.modules().forEach((id, unit) -> { tables.put(id, unit.bodyTable()); registries.put(id, unit.classFactories()); });
            var responder = new deal.semantic.SemanticOracle.HostResponder() {
                private deal.semantic.SemanticOracle.Value.ArrayValue saved;
                private deal.semantic.SemanticOracle.Value.ClassValue damaged;
                public SyncOutcome call(deal.semantic.ir.ModuleId module, String export,
                        deal.semantic.ir.RuntimeDescriptor.Func descriptor,
                        List<deal.semantic.SemanticOracle.Value> arguments) {
                    deal.semantic.SemanticOracle.Value result = deal.semantic.SemanticOracle.Value.UndefinedValue.INSTANCE;
                    switch (export) {
                        case "keep" -> saved = (deal.semantic.SemanticOracle.Value.ArrayValue)arguments.getFirst();
                        case "corrupt" -> saved.elements().set(saved.elements().size()-1,
                            new deal.semantic.SemanticOracle.Value.StrValue("bad"));
                        case "repair" -> saved.elements().set(saved.elements().size()-1,
                            new deal.semantic.SemanticOracle.Value.NumValue(9));
                        case "size" -> result = new deal.semantic.SemanticOracle.Value.NumValue(saved.elements().size());
                        case "echo" -> result = arguments.getFirst();
                        case "make" -> {
                            saved = new deal.semantic.SemanticOracle.Value.ArrayValue(new ArrayList<>(List.of(
                                new deal.semantic.SemanticOracle.Value.NumValue(4),
                                new deal.semantic.SemanticOracle.Value.NumValue(9))),
                                deal.semantic.ir.RuntimeDescriptor.Number.INSTANCE, false);
                            result = saved;
                        }
                        case "grow" -> saved.elements().add(new deal.semantic.SemanticOracle.Value.NumValue(11));
                        case "damage" -> {
                            boolean restore=((deal.semantic.SemanticOracle.Value.BoolValue)arguments.get(1)).value();
                            if (!restore) damaged=(deal.semantic.SemanticOracle.Value.ClassValue)arguments.getFirst();
                            damaged.fields().set(0,new deal.semantic.SemanticOracle.Value.ClassFieldState.Present(restore
                                ? new deal.semantic.SemanticOracle.Value.NumValue(9)
                                : new deal.semantic.SemanticOracle.Value.StrValue("bad")));
                        }
                        default -> throw new AssertionError(export);
                    }
                    return new SyncOutcome.Returned(result);
                }
            };
            var result = deal.semantic.SemanticOracle.invokeSyncEntry(project, tables, registries,
                responder, compiler.checkedProject().input().entryModule(), "run", List.of());
            if (!(result.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success success)
                    || !success.resultAtom().equals("null"))
                throw new AssertionError("pop Oracle: " + result.comparisonReport() + "\n" + result.trace().stream()
                    .filter(e -> e.phase()==deal.semantic.SemanticRuntimeModel.Phase.FAILURE).toList());
            var failures = result.trace().stream().filter(e -> e.kind()==deal.semantic.ir.SemanticOpKind.valueOf(operation)
                && e.phase()==deal.semantic.SemanticRuntimeModel.Phase.FAILURE).toList();
            int population = 3;
            if (failures.size()!=population) throw new AssertionError(operation + " failure trace population: " + failures);
            for (int i=0; i<population; i++) {
                var error = failures.get(i).error();
                String expected=i==0 ? "[number]" : i==1 ? "number" : "@src/main.deal/Item";
                String actual=i==0 ? "array" : i==1 ? "string" : expected;
                if (!error.code().equals("E8003") || !error.expected().equals(expected)
                        || !error.actual().equals(actual)
                        || !error.message().equals("array element " + (splice ? (i==0 ? 2 : i==1 ? 3 : 1) : (i==1 ? 2 : 1)) + " type mismatch") || error.cause()==null
                        || !error.cause().code().equals("E8001") || !error.cause().expected().equals("number")
                        || !error.cause().actual().equals("string") || error.frames().isEmpty())
                    throw new AssertionError("pop projection: " + error);
                var failedId=failures.get(i).op();
                var op=project.modules().get(failedId.module()).ops().stream()
                    .filter(o -> o.opId().equals(failedId)).findFirst().orElseThrow();
                String expectedOrigin=op.origin().sourceId()+":"+op.origin().span().startLine()+":"+op.origin().span().startColumn();
                if (!error.origin().equals(expectedOrigin) || !error.cause().origin().equals(expectedOrigin)
                        || !error.cause().frames().equals(error.frames()))
                    throw new AssertionError("pop origin/frames " + error);
            }
        } finally { CompositionProductionTest.remove(work); }
    }

    /** Exercise the evaluator adapter, not just a hand-built BoundaryValueView. */
    private static void callableBodyAdmission() throws Exception {
        Path work = Files.createTempDirectory(Path.of("build"), "callable-admission-").toAbsolutePath();
        try {
            Path source = Files.createDirectories(work.resolve("src")).resolve("main.deal");
            Files.writeString(source, """
                import * as host from "host/probe";
                export function main():null{return null;}
                export function run():number {
                    try { let value=host.value(); return 0; } catch(e) { return 1; }
                }
                """);
            Path declaration = source.resolveSibling("probe.d.deal");
            Files.writeString(declaration, "export function value():unknown;");
            Files.writeString(work.resolve("deal.json"),
                "{\"languageVersion\":\"1.6\",\"stdlib\":\"1.6\",\"moduleRoots\":[\"src\"]}");
            var invocation = deal.semantic.CompilerProfileProvider.resolve(
                deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
            var compiler = new deal.module.CompilationOrchestrator(source, work.resolve("out"),
                false, false, false, false, deal.codegen.Backend.JS,
                Map.of("host/probe", declaration.toString()), List.of(source.getParent()), null, null, invocation);
            if (!compiler.compile()) throw new AssertionError("callable admission compilation failed");
            var project = Objects.requireNonNull(compiler.producedProject());
            var tables = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.StructuredBodyTable>();
            var registries = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.ClassFactoryRegistry>();
            project.modules().forEach((id, unit) -> { tables.put(id, unit.bodyTable()); registries.put(id, unit.classFactories()); });
            for (boolean nested : List.of(false, true)) {
                int[] calls = {0};
                var responder = new deal.semantic.SemanticOracle.HostResponder() {
                    public SyncOutcome call(deal.semantic.ir.ModuleId module, String export,
                            deal.semantic.ir.RuntimeDescriptor.Func descriptor,
                            List<deal.semantic.SemanticOracle.Value> arguments) {
                        calls[0]++;
                        var signature = (deal.semantic.ir.RuntimeDescriptor.Func)
                            deal.semantic.ir.RuntimeDescriptor.parseCanonicalText("()->number");
                        deal.semantic.SemanticOracle.Value value = new deal.semantic.SemanticOracle.Value.FuncValue(
                            null, signature, Map.of());
                        if (nested) {
                            var entries = new LinkedHashMap<String, deal.semantic.SemanticOracle.Value>();
                            var record = new deal.semantic.SemanticOracle.Value.TableValue(entries);
                            entries.put("self", record);
                            entries.put("unread", value);
                            value = record;
                        }
                        return new SyncOutcome.Returned(value);
                    }
                };
                var result = deal.semantic.SemanticOracle.invokeSyncEntry(project, tables, registries,
                    responder, compiler.checkedProject().input().entryModule(), "run", List.of());
                if (calls[0] != 1 || !(result.terminal() instanceof
                        deal.semantic.SemanticRuntimeModel.Terminal.Success success)
                        || !success.resultAtom().equals("num:0x1.0p0"))
                    throw new AssertionError("bodyless callable admitted (nested=" + nested + "): " + result.comparisonReport());
            }
        } finally {
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    /** Exercise live builtin Error schema/descendant views through real lowering. */
    private static void errorGraphAdmission() throws Exception {
        Path work = Files.createTempDirectory(Path.of("build"), "error-admission-").toAbsolutePath();
        try {
            Path source = Files.createDirectories(work.resolve("src")).resolve("main.deal");
            Path declaration = source.resolveSibling("probe.d.deal");
            Files.writeString(declaration, "export function edit(e:Error):void;");
            Files.writeString(work.resolve("deal.json"),
                "{\"languageVersion\":\"1.6\",\"stdlib\":\"1.6\",\"moduleRoots\":[\"src\"]}");
            for (String boundary : List.of("Error", "unknown", "Record<string,unknown>")) {
                String value = boundary.equals("Record<string,unknown>") ? "{unread:e}" : "e";
                Files.writeString(source, "import * as host from \"host/probe\";"
                    + "export function main():null{return null;}"
                    + "export function run():number{let e:Error={code:\"X\",message:\"original\"};"
                    + "host.edit(e);try{let v:" + boundary + "=" + value
                    + ";return 0;}catch(x){return 1;}}");
                var invocation = deal.semantic.CompilerProfileProvider.resolve(
                    deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                    deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
                var compiler = new deal.module.CompilationOrchestrator(source, work.resolve("out"),
                    false, false, false, false, deal.codegen.Backend.JS,
                    Map.of("host/probe", declaration.toString()), List.of(source.getParent()), null, null, invocation);
                if (!compiler.compile()) throw new AssertionError("Error admission compile failed");
                var project = Objects.requireNonNull(compiler.producedProject());
                var tables = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.StructuredBodyTable>();
                var registries = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.ClassFactoryRegistry>();
                project.modules().forEach((id, unit) -> { tables.put(id, unit.bodyTable()); registries.put(id, unit.classFactories()); });
                for (String field : List.of("code", "message")) for (boolean invalid : List.of(false, true)) {
                    int[] calls = {0};
                    var responder = new deal.semantic.SemanticOracle.HostResponder() {
                        @Override public SyncOutcome call(deal.semantic.ir.ModuleId module, String export,
                                deal.semantic.ir.RuntimeDescriptor.Func descriptor,
                                List<deal.semantic.SemanticOracle.Value> arguments) {
                            calls[0]++;
                            try {
                                var write = deal.semantic.SemanticOracle.Value.ErrorValue.class
                                    .getDeclaredMethod("writeField", String.class, String.class);
                                write.setAccessible(true);
                                write.invoke(arguments.getFirst(), field, invalid ? "\ud800" : "changed 🌍");
                            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                            return new SyncOutcome.Returned(deal.semantic.SemanticOracle.Value.UndefinedValue.INSTANCE);
                        }
                    };
                    var result = deal.semantic.SemanticOracle.invokeSyncEntry(project, tables, registries,
                        responder, compiler.checkedProject().input().entryModule(), "run", List.of());
                    String expected = "num:0x0.0p0";
                    if (calls[0] != 1 || !(result.terminal() instanceof
                            deal.semantic.SemanticRuntimeModel.Terminal.Success success)
                            || !success.resultAtom().equals(expected))
                        throw new AssertionError(boundary + "/" + field + "/" + invalid + ": " + result.comparisonReport());
                }
            }
        } finally {
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void executeFixture(String fixture) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path work = Files.createTempDirectory(root.resolve("build"), "language16-");
        try {
            Path source = Files.createDirectories(work.resolve("src")).resolve("main.deal");
            Path fixtures = root.resolve("test/conformance/backend-runtime/language16");
            Files.writeString(source, ConformanceHarnessMetadata.stripClassificationHeaders(
                Files.readString(fixtures.resolve(fixture + ".deal"))));
            Files.writeString(source.resolveSibling("nominal.deal"),
                ConformanceHarnessMetadata.stripClassificationHeaders(
                    Files.readString(fixtures.resolve("nominal.deal"))));
            Files.writeString(work.resolve("deal.json"),
                "{\"languageVersion\":\"1.6\",\"stdlib\":\"1.6\",\"moduleRoots\":[\"src\"]}\n");
            String cp = Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(p -> root.resolve(p).normalize().toString()).reduce((a,b) -> a + java.io.File.pathSeparator + b).orElseThrow();
            var invocation = deal.semantic.CompilerProfileProvider.resolve(
                deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
            var orchestrator = new deal.module.CompilationOrchestrator(source, work.resolve("oracle"),
                false, false, false, false, deal.codegen.Backend.JS, Map.of(),
                List.of(source.getParent()), null, null, invocation);
            if (!orchestrator.compile()) throw new AssertionError("Oracle fixture compile rejected");
            var project = Objects.requireNonNull(orchestrator.producedProject());
            var facts = new deal.semantic.ir.SemanticIrValidator.ComparisonFacts(
                project.interfaceIndex().interfaceIndexDigest(), project.semanticProfile(),
                invocation.capabilityRegistryHash());
            var textFailure = deal.semantic.ir.SemanticIrValidator.validateText(
                deal.semantic.ir.SemanticIrValidator.toProjectText(project), facts);
            if (textFailure.isPresent()) throw new AssertionError(textFailure.get());
            String raw = deal.semantic.ir.SemanticIrValidator.toProjectText(project);
            if (fixture.equals("core")) {
            rejectsRaw(raw, "\"target\":null", "\"target\":42", facts);
            rejectsRaw(raw, "\"selector\":\"INSTANCEOF\",\"target\":\"Error\"",
                "\"selector\":\"INSTANCEOF\",\"target\":null", facts);
            rejectsRaw(raw, "\"selector\":\"INSTANCEOF\",\"target\":\"Error\"",
                "\"selector\":\"INSTANCEOF\",\"target\":\"number\"", facts);
            rejectsRaw(raw, "\"dynamicKey\":null,\"key\":\"missing\"",
                "\"dynamicKey\":null,\"key\":null", facts);
            rejectsRaw(raw, "\"dynamicKey\":null,\"key\":\"missing\"",
                "\"dynamicKey\":42,\"key\":\"missing\"", facts);
            var noValues = new deal.semantic.ir.CanonicalJson.Arr(List.of());
            var missingValue = deal.semantic.ir.ContractSnapshotCanonicalizer.semanticIdJson(
                new deal.semantic.ir.ValueId(Integer.MAX_VALUE));
            rejectsOp(raw, "UNARY", op -> field(field(op, "operands", noValues), "operandTypes", noValues), facts);
            rejectsOp(raw, "UNARY", op -> field(op, "operands",
                new deal.semantic.ir.CanonicalJson.Arr(List.of(missingValue))), facts);
            rejectsOp(raw, "UNARY", op -> field(op, "operandTypes",
                new deal.semantic.ir.CanonicalJson.Arr(List.of(new deal.semantic.ir.CanonicalJson.Str("string")))), facts);
            rejectsOp(raw, "UNARY", op -> field(op, "resultType",
                new deal.semantic.ir.CanonicalJson.Str("unknown")), facts);
            rejectsOp(raw, "HAS_FIELD", op -> field(op, "payload",
                field(field(payload(op), "key", new deal.semantic.ir.CanonicalJson.Str("both")),
                    "dynamicKey", missingValue)), facts);
            rejectsOp(raw, "HAS_FIELD", op -> field(op, "payload",
                field(payload(op), "receiver", missingValue)), facts);
            rejectsOp(raw, "HAS_FIELD", op -> field(op, "payload",
                field(field(payload(op), "key", deal.semantic.ir.CanonicalJson.Null.INSTANCE),
                    "dynamicKey", missingValue)), facts);
            }
            var tables = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.StructuredBodyTable>();
            var registries = new LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.ClassFactoryRegistry>();
            project.modules().forEach((id, unit) -> { tables.put(id, unit.bodyTable()); registries.put(id, unit.classFactories()); });
            var oracle = deal.semantic.SemanticOracle.executeProjectInits(project, tables, registries,
                new deal.semantic.SemanticOracle.HostResponder() {});
            if (!(oracle.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success) || !oracle.effects().isEmpty())
                throw new AssertionError(oracle.comparisonReport());
            for (String backend : List.of("js", "lua", "jvm")) {
                Path out = work.resolve(backend);
                Process compile = new ProcessBuilder("java", "-cp", cp, Main.class.getName(), "compile",
                    source.toString(), "--backend", backend, "--output", out.toString())
                    .directory(root.toFile()).redirectErrorStream(true).start();
                if (!compile.waitFor(90, TimeUnit.SECONDS)) { compile.destroyForcibly(); throw new AssertionError("Compiler timeout"); }
                String output = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (compile.exitValue() != 0 || !output.matches("(?s)Compilation successful: [1-9][0-9]* module\\(s\\)\\s*")) throw new AssertionError(output);
                switch (backend) {
                    case "js" -> {
                        run(out, "node", "main.js");
                        if (fixture.equals("core")) {
                        run(out, "node", "-e", """
                            const rt=require('./deal/runtime'),entry=require('./main');
                            rt.invokeAsyncExport(entry,'exportedNumbers','[number]').then(result=>{
                              if(!result.$ok) throw Error('completion');
                              const a=result.$value;
                              if(rt.checkType('[number]',a)!==a) throw Error('identity');
                              let rejected=false;
                              try {rt.checkType('[unknown]',a)} catch(e) {rejected=e.code==='E8001'}
                              if(!rejected) throw Error('projected array lost descriptor');
                            }).catch(e=>{console.error(e);process.exit(1)});
                            """);
                        run(out, "node", "-e", "const rt=require('./deal/runtime'); const entry=require('./main'); rt.invokeAsyncExport(entry,'run','number').then(v=>{if(!v.$ok||v.$value!==7){console.error(v);process.exit(1)}},e=>{console.error(e);process.exit(1)});");
                        }
                    }
                    case "lua" -> {
                        run(out, "luajit", "main.lua");
                        if (fixture.equals("core")) {
                        for (String loadRuntime : List.of("", "require('deal.runtime');")) {
                            run(out, "luajit", "-e", loadRuntime + """
                                local entry=dofile('main.lua')
                                local json=require('std.json')
                                local instance=entry.exportedNode.f()
                                assert(json.stringify.f(instance)=='{"first":true,"tail":null}')
                                local rt=require('deal.runtime'); local k=rt.__keys
                                instance[k.f].next=instance; instance[k.p].next=true
                                local ok,e=pcall(json.stringify.f,instance)
                                assert(not ok and e.code=='E8001')
                                instance[k.p].next=nil
                                assert(json.stringify.f(instance)=='{"first":true,"tail":null}')
                                """);
                        }
                        var result = new deal.codegen.lua.LuaJitAsyncExportInvoker().invoke(
                            new deal.codegen.lua.AsyncExportInvocationRequest(out.resolve("main.lua"), "run", "number"));
                        if (!(result instanceof deal.codegen.lua.LuaJitAsyncExportInvoker.Result.Value value)
                                || Double.parseDouble(value.valueJson()) != 7) throw new AssertionError(result);
                        }
                    }
                    case "jvm" -> {
                        run(root, "javac", "--release", "25", "-proc:none", "-cp", cp, "-d", out.toString(), out.resolve("Main.java").toString());
                        run(root, "java", "-cp", out + java.io.File.pathSeparator + cp, "Main");
                        if (fixture.equals("core")) {
                        var result = new deal.codegen.jvm.JvmAsyncExportInvoker().invoke(
                            new deal.codegen.lua.AsyncExportInvocationRequest(out.resolve("Main.class"), "run", "number"));
                        if (!(result instanceof deal.codegen.jvm.JvmAsyncExportInvoker.Result.Value value)
                                || Double.parseDouble(value.valueJson()) != 7) throw new AssertionError(result);
                        }
                    }
                    default -> throw new AssertionError(backend);
                }
            }
            // Exercise shipped modules directly too: generated Lua/JVM
            // stdlib realizations cannot stand in for the deployed wrappers.
            run(root, "node", "-e", """
                const rt=require('./deal/runtime'),s=require('./std/string'),t=require('./std/table');
                if(rt.checkType('unknown','\\uD800')!=='\\uD800') throw Error('lone unit ingress');
                for (const bad of [()=>{}, Promise.resolve(1), {}, Symbol('foreign')]) {
                  let rejected=false;
                  try {rt.checkType('unknown',bad)} catch(e) {rejected=e.code==='E8001'}
                  if(!rejected) throw Error('unsupported unknown ingress');
                }
                const json=require('./std/json');
                const untouched=[];
                const invalid=new Map([['bad',{$kind:'function',$sig:'()->number',$f:null}],['array',untouched]]);
                let invalidRejected=false;
                try {rt.checkType('unknown',invalid)} catch(e) {invalidRejected=e.code==='E8001'}
                if(!invalidRejected) throw Error('malformed unread callable');
                // Failed graph validation must not publish an unknown[] descriptor.
                const typed=rt.hostFunction('()->[number]',()=>untouched).$f();
                if(typed!==untouched || rt.checkType('[number]',typed)!==typed) throw Error('failed ingress published descriptor');
                const parsed=json.parse.$f('{"a":[1]}').get('a');
                rt.hostAsyncFunction('async([unknown])->record',async a=>new Map([['child',a]])).$f(parsed)
                  .then(result=>{if(result.get('child')!==parsed) throw Error('async recursive reverse identity')})
                  .catch(error=>{console.error(error);process.exitCode=1;});
                const instance=rt.classPlan('@m/Node',[
                  {name:'first',descriptor:'boolean',optional:false},
                  {name:'next',descriptor:'unknown',optional:true},
                  {name:'tail',descriptor:'?string',optional:false}],{tail:null,first:true});
                if(json.stringify.$f(instance)!=='{"first":true,"tail":null}') throw Error('class field order');
                instance.next=instance;
                let cycle=false; try {json.stringify.$f(instance)} catch(e) {cycle=e.code==='E8001'}
                if(!cycle) throw Error('class cycle');
                delete instance.next;
                const fields=[{name:'first',jtype:'boolean',optional:false,nullable:false},
                  {name:'tail',jtype:'string',optional:false,nullable:true}];
                const decoded=rt.jsonFromJson('@m/Decoded',fields,'{"tail":null,"first":true}');
                if(decoded===null || json.stringify.$f(decoded)!=='{"first":true,"tail":null}') throw Error('decoded class metadata');
                rt.hostFunction('(@m/Decoded)->void',value=>{
                  if(json.stringify.$f(value)!=='{"first":true,"tail":null}') throw Error('projected class metadata');
                }).$f(decoded);
                if(json.stringify.$f(new Map([['a',instance],['b',instance]]))!==
                    '{"a":{"first":true,"tail":null},"b":{"first":true,"tail":null}}') throw Error('class DAG');
                let deep=instance;
                for(let i=0;i<600;i++) deep=rt.classPlan('@m/Node',[
                  {name:'first',descriptor:'boolean',optional:false},
                  {name:'next',descriptor:'unknown',optional:true},
                  {name:'tail',descriptor:'?string',optional:false}],{tail:null,first:true,next:deep});
                if((json.stringify.$f(deep).match(/"first":true/g)||[]).length!==601) throw Error('deep class frames');
                if(json.stringify.$f(rt.errorValue('C','m'))!=='{"code":"C","message":"m"}') throw Error('Error fields');
                const callable=rt.checkType('unknown',s.split);
                if(callable!==s.split) throw Error('function identity');
                for (const a of [s.split.$f('a,b',','),s.split.$f('',','),
                    s.split.$f('ab',''),t.keys.$f(new Map([['a',true]])),t.keys.$f(new Map())]) {
                  if (rt.checkType('[string]',a)!==a) throw Error('identity');
                  let rejected=false;
                  try { rt.checkType('[unknown]',a); } catch(e) { rejected=e.code==='E8001'; }
                  if (!rejected) throw Error('persistent descriptor');
                }
                """);
            run(root, "luajit", "-e", """
                package.path='./?.lua;'..package.path
                local rt=require('deal.runtime'); local s=require('std.string'); local t=require('std.table')
                local ffi=require('ffi')
                assert(rt.check_type('unknown',string.char(0xed,0xa0,0x80))==string.char(0xed,0xa0,0x80))
                for _,bad in ipairs({function() end, coroutine.create(function() end), ffi.new('int',1), string.char(0xe2,0x82)}) do
                  local ok,e=pcall(rt.check_type,'unknown',bad)
                  assert(not ok and e.code=='E8001')
                end
                local json=require('std.json')
                local instance=rt.class_plan_('@m/Node',{
                  {name='first',descriptor='boolean',optional=false},
                  {name='next',descriptor='unknown',optional=true},
                  {name='tail',descriptor='?string',optional=false}},{tail=rt.__NULL,first=true})
                assert(json.stringify.f(instance)=='{"first":true,"tail":null}')
                instance.next=instance
                local ok,e=pcall(json.stringify.f,instance)
                assert(not ok and e.code=='E8001')
                instance.next=nil
                setmetatable(instance,{__index=function() error('JSON invoked user hook') end})
                assert(json.stringify.f(instance)=='{"first":true,"tail":null}')
                local fields={{name='first',jtype='boolean',optional=false,nullable=false},
                  {name='tail',jtype='string',optional=false,nullable=true}}
                local decoded=rt.json_from_json('@m/Decoded',{tail=rt.__NULL,first=true},fields)
                assert(decoded and json.stringify.f(decoded)=='{"first":true,"tail":null}')
                local plan={{name='first',descriptor='boolean',optional=false},
                  {name='tail',descriptor='?string',optional=false}}
                local planned=rt.json_from_plan('@m/Planned',plan,{tail=rt.__NULL,first=true})
                assert(planned and json.stringify.f(planned)=='{"first":true,"tail":null}')
                assert(json.stringify.f({a=instance,b=instance})==
                  '{"a":{"first":true,"tail":null},"b":{"first":true,"tail":null}}')
                local deep=instance
                for i=1,600 do deep=rt.class_plan_('@m/Node',{
                  {name='first',descriptor='boolean',optional=false},
                  {name='next',descriptor='unknown',optional=true},
                  {name='tail',descriptor='?string',optional=false}},{tail=rt.__NULL,first=true,next=deep}) end
                local _,count=string.gsub(json.stringify.f(deep),'"first":true','')
                assert(count==601)
                assert(json.stringify.f(rt.error_value('C','m'))=='{"code":"C","message":"m"}')
                assert(rt.check_type('unknown',s.split)==s.split)
                for _,a in ipairs({s.split.f('a,b',','),s.split.f('',','),
                    s.split.f('ab',''),t.keys.f({a=true}),t.keys.f({})}) do
                  assert(rt.check_type('[string]',a)==a)
                  local ok,e=pcall(rt.check_type,'[unknown]',a)
                  assert(not ok and e.code=='E8001')
                end
                """);
        } finally {
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
