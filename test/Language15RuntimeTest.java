package deal.test;

import deal.Main;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real production compilation and execution; no backend may be skipped. */
public final class Language15RuntimeTest {
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
        for (Object unsupported : List.of("\ud800", new Object())) {
            try { deal.codegen.jvm.JvmRuntime.bcheck("unknown", "ref", unsupported);
                throw new AssertionError("unknown accepted unsupported JVM value");
            } catch (deal.codegen.jvm.JvmRuntime.DealError expected) {
                if (!expected.code.equals("E8001")) throw new AssertionError(expected);
            }
        }
        for (String fixture : List.of("core", "refinement", "assertion", "failures", "equality-loops", "json-values", "audit-scalars")) executeFixture(fixture);
        System.out.println("Language15RuntimeTest: passed (Oracle, JS, LuaJIT, JVM; deployed array descriptors)");
    }

    private static void executeFixture(String fixture) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path work = Files.createTempDirectory(root.resolve("build"), "language15-");
        try {
            Path source = Files.createDirectories(work.resolve("src")).resolve("main.deal");
            Path fixtures = root.resolve("test/conformance/backend-runtime/language15");
            Files.writeString(source, ConformanceHarnessMetadata.stripClassificationHeaders(
                Files.readString(fixtures.resolve(fixture + ".deal"))));
            Files.writeString(source.resolveSibling("nominal.deal"),
                ConformanceHarnessMetadata.stripClassificationHeaders(
                    Files.readString(fixtures.resolve("nominal.deal"))));
            Files.writeString(work.resolve("deal.json"),
                "{\"languageVersion\":\"1.5\",\"stdlib\":\"1.5\",\"moduleRoots\":[\"src\"]}\n");
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
                for (const bad of [()=>{}, Promise.resolve(1), {}, '\\uD800', Symbol('foreign')]) {
                  let rejected=false;
                  try {rt.checkType('unknown',bad)} catch(e) {rejected=e.code==='E8001'}
                  if(!rejected) throw Error('unsupported unknown ingress');
                }
                const json=require('./std/json');
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
                for _,bad in ipairs({function() end, coroutine.create(function() end), ffi.new('int',1), string.char(0xed,0xa0,0x80)}) do
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
