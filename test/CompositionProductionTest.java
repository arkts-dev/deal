package deal.test;

import deal.codegen.Backend;
import deal.codegen.jvm.JvmNames;
import deal.module.CompilationOrchestrator;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.test.conformance.JvmCompilation;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Executes independently specified generated compositions on deployed artifacts. */
public final class CompositionProductionTest {
    private static final List<Backend> BACKENDS = List.of(Backend.JS, Backend.LUAJIT, Backend.JVM);
    private static int executions;
    private static NativeKnownSkips knownSkips = NativeKnownSkips.none();
    private static final java.util.Set<String> skippedCompositions = new java.util.LinkedHashSet<>();
    private static final java.util.Set<Path> subjects = new java.util.LinkedHashSet<>();

    static void execute(String source, String expected, Path root, Path host) throws Exception {
        execute(source, expected, root, host, BACKENDS);
    }

    static void execute(String source, String expected, Path root, Path host,
                        List<Backend> backends) throws Exception {
        execute(source, expected, "", root, host, backends);
    }

    static void execute(String source, String expected, String expectedStderr,
                        Path root, Path host, List<Backend> backends) throws Exception {
        execute(source, expected, expectedStderr, root, host, backends, Map.of());
    }

    static void execute(String source, String expected, String expectedStderr,
                        Path root, Path host, List<Backend> backends, Map<String, String> modules) throws Exception {
        execute(source, expected, expectedStderr, root, host, backends, modules, host != null);
    }

    private static void execute(String source, String expected, String expectedStderr,
                        Path root, Path host, List<Backend> backends, Map<String, String> modules,
                        boolean nativeSanitizers) throws Exception {
        for (Backend backend : backends) {
            Path project = root.resolve(backend.name());
            Path src = project.resolve("src");
            Path out = project.resolve("out");
            Files.createDirectories(src);
            Path entry = src.resolve("main.deal");
            Files.writeString(entry, source);
            for (var module : modules.entrySet()) {
                Path file = src.resolve(module.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, module.getValue());
            }
            if (host != null) {
                Files.createDirectories(src.resolve("host"));
                Files.copy(host.resolve("probe.d.deal"), src.resolve("host/probe.d.deal"));
            }
            var invocation = CompilerProfileProvider.resolve(ReleaseConfiguration.CURRENT_RELEASE_STATE,
                ReleaseConfiguration.releaseCapabilityRegistry());
            var compiler = new CompilationOrchestrator(entry.toAbsolutePath(), out, false, false,
                false, false, backend, host == null ? null
                    : Map.of("host/probe", src.resolve("host/probe.d.deal").toAbsolutePath().toString()),
                List.of(src.toAbsolutePath()),
                Path.of("").toAbsolutePath(), null, invocation);
            compiler.compile();
            if (!compiler.diagnostics().isEmpty() || compiler.semanticEmissionCount() != 1)
                throw new AssertionError("SOURCE_REJECTION " + backend + ": " + compiler.diagnostics());
            if (backend == Backend.LLVM) {
                String subject = root.getFileName().toString();
                if (knownSkips.contains("composition", subject)) {
                    knownSkips.report("composition", subject);
                    skippedCompositions.add(subject);
                    continue; // No native execution or pass credit; common lanes already ran.
                }
                List<Path> hostUnits = new ArrayList<>();
                if (host != null) {
                    Path nativeHost = project.resolve("hosts");
                    Files.createDirectories(nativeHost);
                    Path unit = nativeHost.resolve("probe.c");
                    Files.copy(host.resolve("probe.c"), unit);
                    for (String helper : List.of("native_helpers.h", "native_hostile.h"))
                        Files.copy(Path.of("test/conformance/host-fixtures/" + helper), nativeHost.resolve(helper));
                    hostUnits.add(unit);
                }
                boolean[] instrumentation = nativeSanitizers
                    ? new boolean[] {false, true} : new boolean[] {false};
                for (int level : new int[] {0, 2}) {
                    for (boolean sanitized : instrumentation) {
                        Path product = project.resolve("native" + level + (sanitized ? "-sanitized" : ""));
                        NativeCompiler.build(new deal.toolchain.NativeBuildRequest(
                            out, product, level, hostUnits, null),
                            sanitized ? NativeCompiler.SANITIZER_FLAGS : List.of());
                        for (boolean stress : new boolean[] {false, true}) {
                            var environment = new java.util.HashMap<String, String>();
                            environment.put("DEAL_GC_STRESS", stress ? "1" : "0");
                            if (sanitized) environment.putAll(NativeCompiler.sanitizerEnvironment());
                            System.out.println("Executing LLVM O" + level + " stress=" + stress
                                + " sanitized=" + sanitized + ": " + root.getFileName());
                            var result = sanitized
                                ? NativeCompiler.runSanitized(product, project, environment).result()
                                : NativeCompiler.execute(product, project, environment);
                            if (result.exitCode() != 0 || !result.stderr().equals(expectedStderr)
                                    || !result.stdout().equals(expected))
                                throw new AssertionError("RUNTIME_OR_RESULT_FAILURE LLVM O" + level
                                    + " stress=" + stress + " sanitized=" + sanitized + ": " + result.describe()
                                    + " expected stdout=" + expected + " source=" + entry);
                            executions++;
                            subjects.add(root);
                        }
                    }
                }
                continue;
            }
            List<String> launch;
            if (backend == Backend.JVM) {
                if (host != null) Files.copy(host.resolve("probe.java"), out.resolve("HostProbe.java"));
                Path classes = out.resolve("classes");
                Files.createDirectories(classes);
                String cp = TestClasspath.absolute(TestClasspath.EmptyEntry.OMIT);
                var command = new ArrayList<>(List.of("javac", "--release", "25", "-proc:none",
                    "-cp", cp, "-d", classes.toAbsolutePath().toString()));
                try (var files = Files.walk(out)) {
                    files.filter(p -> p.toString().endsWith(".java")).sorted()
                        .forEach(p -> command.add(p.toAbsolutePath().toString()));
                }
                var built = JvmCompilation.compile(command, out);
                if (built.exitCode() != 0) throw new AssertionError("ARTIFACT_BUILD_FAILURE: " + built.stderr());
                launch = List.of("java", "-cp", classes.toAbsolutePath() + File.pathSeparator + cp,
                    JvmNames.classNameFor("main"));
            } else {
                String extension = backend == Backend.JS ? "js" : "lua";
                if (host != null) {
                    Files.createDirectories(out.resolve("host"));
                    Files.copy(host.resolve("probe." + extension), out.resolve("host/probe." + extension));
                }
                launch = List.of(backend == Backend.JS ? "node" : "luajit", "main." + extension);
            }
            var result = BoundedProcess.runCaptured(launch, out, Map.of());
            if (result.stdoutTruncated() || result.stderrTruncated() || result.exitCode() != 0
                    || !result.stderr().equals(expectedStderr) || !result.stdout().equals(expected))
                throw new AssertionError("RUNTIME_OR_RESULT_FAILURE " + backend + ": " + result
                    + " expected stdout=" + expected + " source=" + entry);
            executions++;
            subjects.add(root);
        }
    }

    static void remove(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    static void jsonDeclarationComposition(Path root) throws Exception {
        Path host = Files.createDirectories(root.resolve("json-schema-host"));
        for (String extension : List.of("d.deal", "js", "lua", "java", "c"))
            Files.copy(Path.of("test/conformance/host-fixtures/boundaries/boundary_json_schema." + extension),
                host.resolve("probe." + extension));
        execute("""
            import * as host from "host/probe";
            import * as model from "./model";
            function check(ok:boolean,step:number):void{if(!ok){throw {code:"TEST"+String(step),message:"declaration schema"};}}
            export function main():null{
              let leaf:host.Leaf=JSON.parse(`{"value":8,"child":{"value":9,"next":{"value":10}}}`);
              check(host.read(leaf)===8,1);
              check((JSON.stringify(leaf) as string)===`{"value":8,"child":{"value":9,"next":{"value":10}}}`,2);
              let box:model.Box=JSON.parse(`{"item":{"value":7,"foreign":{"value":11,"child":{"value":12}}}}`);
              check(model.read(box)===11,3);
              let omitted:model.Box=JSON.parse(`{"item":{"value":7}}`);
              check(!model.hasForeign(omitted),4);
              let original=box;let caught=false;
              try{box=JSON.parse(`{"item":{"value":7,"foreign":{"value":11,"child":{"value":"bad"}}}}`);}
              catch(e){check((e as Error).code==="E8001",5);caught=true;}
              check(caught && box===original && model.read(box)===11,6);
              return null;
            }
            """, "", "", root.resolve("json-parse-declaration-provider"), host,
            List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM), Map.of(
                "model.deal", "import * as host from \"host/probe\";"
                    + "class Item{value:number;foreign?:host.Leaf;}export class Box{item:Item;}"
                    + "export function read(box:Box):number{return host.read(box.item.foreign as host.Leaf);}"
                    + "export function hasForeign(box:Box):boolean{return Object.hasOwn(box.item,\"foreign\");}"));
    }

    public static void main(String[] args) throws Exception {
        // Bridge used by Hypothesis: one minimized source is tested against an
        // independent transcript on every lane, never against another backend.
        if (args.length == 3) {
            Path root = Files.createTempDirectory("deal-stateful-");
            try { execute(Files.readString(Path.of(args[0])), Files.readString(Path.of(args[1])),
                    root, Path.of(args[2])); }
            finally { remove(root); }
            return;
        }
        if (args.length == 0) knownSkips = NativeKnownSkips.none();
        else if (args.length == 2 && args[0].equals("--known-skips"))
            knownSkips = NativeKnownSkips.load(Path.of(args[1]));
        else throw new IllegalArgumentException("expected no arguments or --known-skips <path>");
        executions = 0;
        subjects.clear();
        skippedCompositions.clear();
        Path root = Files.createTempDirectory("deal-compositions-");
        try {
            String fixture = "control-flow/for-of-nested-compositions.deal";
            execute(ConformanceHarnessMetadata.productionCorpusSource(Files.readString(
                Path.of("test/conformance/backend-runtime").resolve(fixture)), fixture),
                "NESTED_OK\n", root.resolve("nested"), null);
            for (String commas : List.of("trailing-argument-comma", "trailing-parameter-comma")) {
                Path fixturePath = Path.of("test/conformance/backend-runtime/functions/" + commas + ".deal");
                execute(ConformanceHarnessMetadata.productionCorpusSource(Files.readString(fixturePath),
                    fixturePath.toString()), "", root.resolve(commas), null,
                    List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            }
            Path popHost = Files.createDirectories(root.resolve("pop-host"));
            for (String extension : List.of("d.deal", "js", "lua", "java", "c"))
                Files.copy(Path.of("test/conformance/host-fixtures/array_pop." + extension),
                    popHost.resolve("probe." + extension));
            execute(ConformanceHarnessMetadata.productionCorpusSource(Files.readString(Path.of(
                "test/conformance/backend-runtime/arrays/pop-live-retry.deal")), "arrays/pop-live-retry.deal"),
                "", root.resolve("pop-live"), popHost,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            execute(ConformanceHarnessMetadata.productionCorpusSource(Files.readString(Path.of(
                "test/conformance/backend-runtime/arrays/splice-live-retry.deal")), "arrays/splice-live-retry.deal"),
                "", root.resolve("splice-live"), popHost,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            String popCallable = "import * as host from \"host/probe\";export function main():null{"
                + "let fn=():number=>9;let fs:((()=>number)|null)[]=[fn];"
                + "let r:Record<string,unknown>={f:fn};let rs:Record<string,unknown>[]=[r];let us:unknown[]=[r];"
                + "host.poison(fn);let caught=0;try{fs.pop();}catch(e){if((e as Error).code===\"E8003\"){caught=caught+1;}}"
                + "try{rs.pop();}catch(e){if((e as Error).code===\"E8003\"){caught=caught+1;}}"
                + "try{us.pop();}catch(e){if((e as Error).code===\"E8003\"){caught=caught+1;}}"
                + "if(caught!==3 || fs.length!==1 || rs.length!==1 || us.length!==1){throw {code:\"POISON\",message:\"pop changed storage\"};}"
                + "host.restoreFunction();let got=fs.pop() as ()=>number;"
                + "if(got!==fn || got()!==9 || (rs.pop() as Record<string,unknown>)!==r || (us.pop() as Record<string,unknown>)!==r){"
                + "throw {code:\"RETRY\",message:\"pop identity\"};}return null;}";
            execute(popCallable, "", root.resolve("pop-callable"), popHost,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            execute(popCallable.replace("host.poison(fn)", "host.poisonSignature(fn)"), "",
                root.resolve("pop-callable-signature"), popHost,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            execute(popCallable.replace(".pop()", ".splice(0,1)[0]"), "", root.resolve("splice-callable"), popHost,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            execute(popCallable.replace(".pop()", ".splice(0,1)[0]").replace("host.poison(fn)", "host.poisonSignature(fn)"), "",
                root.resolve("splice-callable-signature"), popHost,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            String decodedPop = "export function main():null{"
                + "let r:Record<string,unknown>=JSON.parse(\"{\\\"xs\\\":[1,-0,null,2]}\");let xs=r.xs as unknown[];let alias=xs;"
                + "if((xs.pop() as number)!==2 || xs.pop()!==null || 1/(xs.pop() as number)!==-1/0 || xs.length!==1){"
                + "throw {code:\"DECODED\",message:\"pop values\"};}"
                + "xs[xs.length]=3;if(alias.length!==2 || (alias.pop() as number)!==3 || (xs.pop() as number)!==1"
                + " || xs.pop()!==undefined || (JSON.stringify(r) as string)!==\"{\\\"xs\\\":[]}\"){"
                + "throw {code:\"DECODED\",message:\"pop metadata\"};}return null;}";
            execute(decodedPop, "", root.resolve("pop-decoded"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            String decodedSplice = "export function main():null{"
                + "let r:Record<string,unknown>=JSON.parse(\"{\\\"xs\\\":[1,-0,null,2,3]}\");let xs=r.xs as unknown[];let alias=xs;"
                + "let out=xs.splice(1,2);if(out.length!==2 || 1/(out[0] as number)!==-1/0 || out[1]!==null"
                + " || alias.length!==3 || (alias[1] as number)!==2 || (JSON.stringify(r) as string)!==\"{\\\"xs\\\":[1,2,3]}\"){"
                + "throw {code:\"DECODED\",message:\"splice shift\"};}xs[xs.length]=4;out[out.length]=9;"
                + "if((alias.splice(1,9)[2] as number)!==4 || (JSON.stringify(r) as string)!==\"{\\\"xs\\\":[1]}\")"
                + "{throw {code:\"DECODED\",message:\"splice append\"};}return null;}";
            execute(decodedSplice, "", root.resolve("splice-decoded"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            execute(ConformanceHarnessMetadata.productionCorpusSource(Files.readString(Path.of(
                "test/conformance/backend-runtime/tables/object-keys-live-fields.deal")), "tables/object-keys-live-fields.deal"),
                "", root.resolve("object-keys-live"), popHost,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            execute(ConformanceHarnessMetadata.stripClassificationHeaders(Files.readString(Path.of(
                "test/conformance/backend-runtime/tables/builtin-has-own.deal"))),
                "", root.resolve("has-own"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            execute(ConformanceHarnessMetadata.stripClassificationHeaders(Files.readString(Path.of(
                "test/conformance/backend-runtime/control-flow/conditional-expression.deal"))),
                "", root.resolve("conditional"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            execute(ConformanceHarnessMetadata.stripClassificationHeaders(Files.readString(Path.of(
                "test/conformance/backend-runtime/arrays/from-live.deal"))),
                "", root.resolve("array-from"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            execute(ConformanceHarnessMetadata.stripClassificationHeaders(Files.readString(Path.of(
                "test/conformance/backend-runtime/arrays/from-live-host.deal"))),
                "", root.resolve("array-from-host"), popHost,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            String keysFixture = "tables/builtin-object-keys.deal";
            execute(ConformanceHarnessMetadata.productionCorpusSource(Files.readString(
                Path.of("test/conformance/backend-runtime").resolve(keysFixture)), keysFixture),
                "", root.resolve("object-keys"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            String receiverFixture = "stdlib/string/receiver-intrinsics.deal";
            execute(ConformanceHarnessMetadata.productionCorpusSource(Files.readString(
                Path.of("test/conformance/backend-runtime").resolve(receiverFixture)), receiverFixture),
                "", root.resolve("receiver-corpus"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            String catchPaths = "function value(fail: boolean): number {"
                + "let xs: number[] = [7]; try {if(fail){return xs[2];}return xs[0];}"
                + "catch(e) {} return 9;}"
                + "function done(): void {try {return;}catch(e){}}"
                + "async function asyncValue(fail: boolean): Promise<number> {"
                + "let f: (fail: boolean) => number = function(fail: boolean): number {"
                + "try {return value(fail);}catch(e){} return 11;};return f(fail);}"
                + "export async function probe(): Promise<number> {return await asyncValue(true);}"
                + "export function main(): null {done();"
                + "if(value(false)!==7 || value(true)!==9){throw {code:\"TEST\",message:\"catch path\"};}"
                + "let f: () => number = function(): number {try {throw {code:\"X\",message:\"x\"};}"
                + "catch(e){}return 13;};"
                + "if(f()!==13){throw {code:\"TEST\",message:\"catch expression\"};}return null;}";
            execute(catchPaths, "", root.resolve("catch-paths"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            String constBindings = "class Cell {value:number;}"
                + "export function main():null {const base:number=3;const xs:number[]=[1,2,3]; const c:Cell={value:0};"
                + "const r:Record<string,unknown>={old:1}; r.next=base; delete r.old;"
                + "xs[0]=4; c.value=xs[0];"
                + "let fs:(()=>number)[]=[]; for(const x of xs){"
                + "const read:()=>number=function():number{return x;};fs[fs.length]=read;}"
                + "const down:(n:number)=>number=function(n:number):number{"
                + "if(n===0){return base;}return down(n-1);};"
                + "if(fs[0]()!==4 || fs[1]()!==2 || fs[2]()!==3 || c.value!==4 || down(5)!==3){"
                + "throw {code:\"TEST\",message:\"const identity or capture\"};}return null;}";
            execute(constBindings, "", root.resolve("const-bindings"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            String remainders = "export function main():null {"
                + "let a:number[]=[5,-5,5,-5,5.5,-5.5,0,-0,1,0,1/0,1,1];"
                + "let b:number[]=[2,2,-2,-2,2,2,2,2,0,0,2,1/0,0/0];"
                + "let want:number[]=[1,-1,1,-1,1.5,-1.5,0,-0,0/0,0/0,0/0,1,0/0];"
                + "let i:number=0;while(i<a.length){let got:number=a[i]%b[i];let expected:number=want[i];"
                + "if(expected!==expected){if(got===got){throw {code:\"TEST\",message:\"remainder NaN\"};}}"
                + "else {if(got!==expected || (got===0 && 1/got!==1/expected)){"
                + "throw {code:\"TEST\",message:\"remainder sign/value\"};}}i=i+1;}"
                + "if(20%6*2!==4 || 20/2%3!==1){throw {code:\"TEST\",message:\"remainder precedence\"};}return null;}";
            execute(remainders, "", root.resolve("remainders"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            String arrowFixture = "functions/typed-arrows.deal";
            String arrows = ConformanceHarnessMetadata.productionCorpusSource(Files.readString(
                Path.of("test/conformance/backend-runtime").resolve(arrowFixture)), arrowFixture);
            execute(arrows, "", root.resolve("arrows"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            String escapes = "export function main():null{"
                + "if(\"\\r\\x41\\u0042\\u{1f600}\\uD800\" !== `\\rAB😀\\uD800`"
                + " || \"\\u{000000000000000000000000041}\" !== \"A\""
                + " || '\\x27' !== \"'\" || \"\\x22\" !== '\"'){"
                + "throw {code:\"TEST\",message:\"escape decoding\"};}return null;}";
            execute(escapes, "", root.resolve("string-escapes"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            // Node supplies an independent ECMAScript reference, never a host
            // default formatter from the implementation under test.
            var random = new java.util.Random(1616);
            var numberSamples = new ArrayList<Double>();
            numberSamples.addAll(List.of(0.0,-0.0,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,
                1e-6,1e-7,1e20,1e21,1000000000000000100.0));
            for (int i = 0; i < 256; i++) numberSamples.add(Double.longBitsToDouble(random.nextLong()));
            for (int e : new int[] {-1074,-1022,-100,-20,-1,0,1,20,52,53,100,1023}) {
                double v = Math.scalb(1.0, e);
                numberSamples.add(v); numberSamples.add(Math.nextDown(v)); numberSamples.add(Math.nextUp(v));
            }
            String referenceScript = "const a=" + numberSamples.stream()
                .map(v -> "\"" + Long.toUnsignedString(Double.doubleToRawLongBits(v),16) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"))
                + ";for(const s of a){const b=Buffer.alloc(8);b.writeBigUInt64BE(BigInt('0x'+s));"
                + "console.log(String(b.readDoubleBE()));console.log(JSON.stringify(b.readDoubleBE()));}";
            var reference = BoundedProcess.runCaptured(List.of("node", "-e", referenceScript), Path.of("").toAbsolutePath());
            if (reference.exitCode()!=0 || reference.stdoutTruncated() || reference.stderrTruncated()
                    || !reference.stderr().isEmpty()) throw new AssertionError(reference);
            var referenceLines = reference.stdout().lines().toList();
            if (referenceLines.size()!=2*numberSamples.size()) throw new AssertionError("number reference population");
            var expectedNumbers = new ArrayList<String>();
            var expectedJson = new ArrayList<String>();
            for (int i=0;i<numberSamples.size();i++) {
                expectedNumbers.add(referenceLines.get(2*i));
                expectedJson.add(referenceLines.get(2*i+1));
            }
            StringBuilder numericSource = new StringBuilder("class Numeric{v:number;}export function main():null{let values:number[]=[");
            for (int i=0;i<numberSamples.size();i++) {
                if(i>0) numericSource.append(',');
                double value = numberSamples.get(i);
                numericSource.append(Double.isNaN(value) ? "0/0" : value==Double.POSITIVE_INFINITY ? "1/0"
                    : value==Double.NEGATIVE_INFINITY ? "-1/0" : Double.toString(value));
                String actual = deal.semantic.ir.NumberSemantics.toString(value);
                if(!actual.equals(expectedNumbers.get(i))) throw new AssertionError("Oracle number text: " + value + " " + actual);
            }
            numericSource.append("];let expected:string[]=[");
            for(int i=0;i<expectedNumbers.size();i++) {
                if(i>0) numericSource.append(',');
                numericSource.append('"').append(expectedNumbers.get(i)).append('"');
            }
            numericSource.append("];let jsonExpected:string[]=[");
            for(int i=0;i<expectedNumbers.size();i++) {
                if(i>0) numericSource.append(',');
                numericSource.append('"').append(expectedJson.get(i)).append('"');
            }
            numericSource.append("];let i=0;while(i<values.length){let item:Numeric={v:values[i]};if(`${values[i]}`!==expected[i] || String(values[i])!==expected[i]"
                + " || (JSON.stringify(values[i]) as string)!==jsonExpected[i]"
                + " || (JSON.stringify([values[i]]) as string)!==\"[\"+jsonExpected[i]+\"]\""
                + " || (JSON.stringify(item) as string)!==\"{\\\"v\\\":\"+jsonExpected[i]+\"}\"){"
                + "throw {code:\"NUMBER_TEXT\",message:`${i}: ${values[i]}`};}i=i+1;}return null;}");
            execute(numericSource.toString(), "", root.resolve("number-text-reference"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            String interpolation = "export function main():null{"
                + "let values:number[]=[0,-0,1,-1,1.5,0/0,1/0,-1/0,1e-6,1e-7,1e20,1e21,"
                + "5e-324,1.7976931348623157e308,1000000000000000100,2.2250738585072014e-308];"
                + "let expected:string[]=[\"0\",\"0\",\"1\",\"-1\",\"1.5\",\"NaN\",\"Infinity\",\"-Infinity\","
                + "\"0.000001\",\"1e-7\",\"100000000000000000000\",\"1e+21\",\"5e-324\","
                + "\"1.7976931348623157e+308\",\"1000000000000000100\",\"2.2250738585072014e-308\"];"
                + "let i=0;while(i<values.length){if(`${values[i]}`!==expected[i]){"
                + "throw {code:\"TEXT\",message:`${i}: ${values[i]}`};}i=i+1;}"
                + "let state=0;let step=(n:number):number=>{state=state*10+n;return n;};"
                + "if(`a${step(1)}b${step(2)}c`!==\"a1b2c\" || state!==12){"
                + "throw {code:\"ORDER\",message:\"interpolation order\"};}"
                + "let fail=():number=>{throw {code:\"STOP\",message:\"stop\"};};"
                + "try{let text=`${step(3)}${fail()}${step(4)}`;}catch(e){}"
                + "if(state!==123){throw {code:\"ABRUPT\",message:\"interpolation failure\"};}"
                + "if(String(step(4))!==\"4\" || state!==1234){throw {code:\"STRING\",message:\"String effects\"};}"
                + "try{let text=String(fail());}catch(e){}"
                + "let refined:unknown=1.5;if(typeof refined===\"number\"){if(String(refined)!==\"1.5\"){"
                + "throw {code:\"REFINED\",message:\"String refinement\"};}}return null;}";
            execute(interpolation, "", root.resolve("numeric-templates"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            String builtins = "export function main():null{"
                + "if(Math.floor(1.5)!==1 || Math.ceil(-1.5)!==-1 || Math.abs(-2)!==2 || Math.sqrt(4)!==2"
                + " || 1/Math.ceil(-0.1)!==-1/0 || 1/Math.abs(-0)!==1/0 || 1/Math.sqrt(-0)!==-1/0"
                + " || Math.sqrt(-1)===Math.sqrt(-1) || Date.now()<0){throw {code:\"MATH\",message:\"builtin values\"};}"
                + "let state=0;let arg=():number=>{state=state+1;return -2;};"
                + "if(Math.abs(arg())!==2 || state!==1){throw {code:\"ORDER\",message:\"builtin effect\"};}"
                + "console.log(String(Math.abs(-1/0)));console.error(\"BUILTIN\");return null;}";
            execute(builtins, "Infinity\n", "BUILTIN\n", root.resolve("builtin-namespaces"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            String arrayPop = "class Item{v:number;}export function main():null{"
                + "const xs:number[]=[1,-0,0/0];let alias=xs;let nan=xs.pop() as number;"
                + "if(nan===nan || xs.length!==2 || alias.length!==2 || 1/(alias.pop() as number)!==-1/0"
                + " || (xs.pop() as number)!==1 || xs.length!==0 || xs.pop()!==undefined || xs.length!==0){"
                + "throw {code:\"POP\",message:\"numeric/empty/alias\"};}"
                + "xs[xs.length]=7;if((alias.pop() as number)!==7){throw {code:\"GROW\",message:\"persistent array\"};}"
                + "let item:Item={v:3};let items:Item[]=[item];if((items.pop() as Item)!==item){throw {code:\"ID\",message:\"class\"};}"
                + "let fn=():number=>9;let fns:(()=>number)[]=[fn];let got=fns.pop() as ()=>number;"
                + "if(got!==fn || got()!==9){throw {code:\"FN\",message:\"function\"};}"
                + "let nulls:(number|null|undefined)[]=[null,undefined,5];"
                + "if((nulls.pop() as number)!==5 || nulls.pop()!==undefined || nulls.pop()!==null || nulls.length!==0){"
                + "throw {code:\"NULLISH\",message:\"elements\"};}"
                + "let flags:boolean[]=[false,true];if((flags.pop() as boolean)!==true || (flags.pop() as boolean)!==false || flags.pop()!==undefined){throw {code:\"BOOL\",message:\"pop\"};}"
                + "let optionalFns:((()=>number)|null|undefined)[]=[fn,null,undefined];"
                + "if(optionalFns.pop()!==undefined || optionalFns.pop()!==null || (optionalFns.pop() as ()=>number)!==fn){throw {code:\"OPT_FN\",message:\"pop\"};}"
                + "let texts:string[]=[\"\\uD800\",\"ok\"];if((texts.pop() as string)!==\"ok\" || (texts.pop() as string)!==\"\\uD800\"){throw {code:\"TEXT\",message:\"pop\"};}"
                + "let count=0;let receiver=():number[]=>{count=count+1;return xs;};receiver().pop();"
                + "if(count!==1){throw {code:\"ONCE\",message:\"receiver\"};}"
                + "let maybe:number[]|null=[3];let invalidate=():void=>{maybe=null;};let caught=false;"
                + "if(maybe!==null){invalidate();try{maybe.pop();}catch(e){if((e as Error).code===\"E8001\"){caught=true;}}}"
                + "if(!caught){throw {code:\"STALE\",message:\"receiver\"};}"
                + "let nils:(null|undefined)[]=[null,undefined];if(nils.pop()!==undefined || nils.pop()!==null || nils.pop()!==undefined){"
                + "throw {code:\"NIL\",message:\"pop\"};}return null;}";
            execute(arrayPop, "", root.resolve("array-pop"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            String jsonEncoding = "class Item{z:number;a?:string;b:null;c:undefined;}"
                + "export function main():null{let f=():number=>1;let mixed:unknown[]=[undefined,f,0/0,1/0,-0];"
                + "if(JSON.stringify(undefined)!==undefined || JSON.stringify(f)!==undefined"
                + " || (JSON.stringify(null) as string)!==\"null\" || (JSON.stringify(true) as string)!==\"true\""
                + " || (JSON.stringify(mixed) as string)!==\"[null,null,null,null,0]\"){"
                + "throw {code:\"ROOTS\",message:\"JSON roots and substitution\"};}"
                + "let r:Record<string,unknown>={b:1};r[\"10\"]=10;r[\"2\"]=2;r.a=null;r.drop=f;r.u=undefined;"
                + "if((JSON.stringify(r) as string)!==\"{\\\"2\\\":2,\\\"10\\\":10,\\\"b\\\":1,\\\"a\\\":null}\"){"
                + "throw {code:\"ORDER\",message:\"JSON own keys/omission\"};}"
                + "let c:Item={c:undefined,b:null,z:1e21};"
                + "if((JSON.stringify(c) as string)!==\"{\\\"z\\\":1e+21,\\\"b\\\":null}\"){"
                + "throw {code:\"CLASS\",message:\"JSON declaration order/presence\"};}"
                + "let child:Record<string,unknown>={v:1};let shared:Record<string,unknown>={x:child,y:child};"
                + "if((JSON.stringify(shared) as string)!==\"{\\\"x\\\":{\\\"v\\\":1},\\\"y\\\":{\\\"v\\\":1}}\"){"
                + "throw {code:\"SHARED\",message:\"JSON shared acyclic children\"};}"
                + "let caught=0;child.cycle=child;try{JSON.stringify(shared);}catch(e){caught=1;}"
                + "if(caught!==1){throw {code:\"CYCLE\",message:\"JSON cycle\"};}delete child.cycle;"
                + "let state=0;let arg=():number=>{state=state+1;return 2;};"
                + "if((JSON.stringify(arg()) as string)!==\"2\" || state!==1){throw {code:\"ONCE\",message:\"JSON argument\"};}"
                + "let fail=():unknown=>{throw {code:\"ARG\",message:\"stop\"};};"
                + "try{JSON.stringify(fail());}catch(e){if(e instanceof Error){if(e.code!==\"ARG\"){throw e;}state=2;}}"
                + "if(state!==2 || JSON.stringify(shared)===undefined){throw {code:\"RETRY\",message:\"JSON repair\"};}return null;}";
            execute(jsonEncoding, "", root.resolve("json-encoding"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            String parseFixture = "stdlib/json/contextual-parse.deal";
            execute(ConformanceHarnessMetadata.productionCorpusSource(Files.readString(
                Path.of("test/conformance/backend-runtime").resolve(parseFixture)), parseFixture),
                "", root.resolve("json-parse-corpus"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            execute("""
                import * as left from "./left";
                import * as again from "././left";
                import * as right from "./right";
                class Envelope { first:left.Node; second:right.Node; }
                function verify(ok:boolean):void{if(!ok){throw {code:"TEST",message:"parse provider"};}}
                export function main():null {
                  let a:left.Node=JSON.parse(`{"z":1,"a":2,"next":{"z":3,"a":4}}`);
                  let b:right.Node=JSON.parse(`{"z":5,"a":6}`);
                  let c:again.Node=a;
                  verify(left.read(c)===1 && right.read(b)===6);
                  let keys=Object.keys(a);verify(keys[0]==="z" && keys[1]==="a");
                  keys=Object.keys(b);verify(keys[0]==="a" && keys[1]==="z");
                  let nested:Envelope=JSON.parse(`{"first":{"z":7,"a":8},"second":{"a":9,"z":10}}`);
                  verify(left.read(nested.first)===7 && right.read(nested.second)===9);
                  let rawA:unknown=a;let rawB:unknown=b;
                  let caught=0;try{let wrong:right.Node=rawA as right.Node;}catch(e){caught=caught+1;}
                  try{let wrong:left.Node=rawB as left.Node;}catch(e){caught=caught+1;}
                  verify(caught===2);
                  verify((JSON.stringify(a) as string)===`{"z":1,"a":2,"next":{"z":3,"a":4}}`);
                  return null;
                }
                """, "", "", root.resolve("json-parse-providers"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM), Map.of(
                    "left.deal", "export class Node{z:number;a:number;next?:Node;}export function read(n:Node):number{return n.z;}",
                    "right.deal", "export class Node{a:number;z:number;next?:Node;}export function read(n:Node):number{return n.a;}"));
            execute("import * as model from \"./model\";class Local{box:model.Box;}export function main():null{"
                + "let box:model.Box=JSON.parse(`{\"item\":{\"value\":7}}`);"
                + "let local:Local=JSON.parse(`{\"box\":{\"item\":{\"value\":8,\"next\":{\"value\":9}}}}`);"
                + "if(model.read(box)!==7 || model.read(local.box)!==8){throw {code:\"TEST\",message:\"private schema provider\"};}return null;}",
                "", "", root.resolve("json-parse-private-provider"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM), Map.of(
                    "model.deal", "class Item{value:number;next?:Item;}export class Box{item:Item;}"
                        + "export function read(box:Box):number{return box.item.value;}"));
            execute("import * as model from \"./model\";class Local{box:model.Box;}export function main():null{"
                + "let local:Local=JSON.parse(`{\"box\":{\"item\":{\"payload\":{\"node\":{\"value\":7,\"next\":{\"value\":8}}}}}}`);"
                + "if(model.read(local.box)!==15){throw {code:\"TEST\",message:\"transitive private schema\"};}"
                + "let original=local;let caught=false;"
                + "try{local=JSON.parse(`{\"box\":{\"item\":{\"payload\":{\"node\":{\"value\":7,\"next\":{\"value\":\"bad\"}}}}}}`);}"
                + "catch(e){if((e as Error).code!==\"E8001\"){throw e as Error;}caught=true;}"
                + "if(!caught || local!==original || model.read(local.box)!==15){throw {code:\"TEST\",message:\"transitive rollback\"};}return null;}",
                "", "", root.resolve("json-parse-transitive-private"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM), Map.of(
                    "model.deal", "import * as leaf from \"./leaf\";class Item{payload:leaf.Payload;}export class Box{item:Item;}"
                        + "export function read(box:Box):number{return leaf.read(box.item.payload);}",
                    "leaf.deal", "class Node{value:number;next?:Node;}export class Payload{node:Node;}"
                        + "export function read(payload:Payload):number{return payload.node.value+(payload.node.next as Node).value;}"));
            jsonDeclarationComposition(root);
            execute(Files.readString(Path.of("skills/write-deal/examples/serialization/main.deal")),
                "", "", root.resolve("json-serialization-example"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM), Map.of(
                    "settings.deal", Files.readString(Path.of("skills/write-deal/examples/serialization/settings.deal"))));
            execute("import * as data from \"./data\";export function main():null{return data.exercise();}",
                "", "", root.resolve("json-data-example"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM), Map.of(
                    "data.deal", Files.readString(Path.of("skills/write-deal/examples/src/data.deal")),
                    "check.deal", Files.readString(Path.of("skills/write-deal/examples/src/check.deal"))));
            // Large documents are source chunks rather than runtime-built
            // strings: construction-loop tracing would measure repeated text
            // rendering instead of reconstruction and boundary stack safety.
            String opening = "{\"value\":1,\"next\":";
            String classInput = "`" + opening.repeat(2500) + "`+\n`"
                + opening.repeat(2500) + "null" + "}".repeat(5000) + "`";
            String arrayInput = "`" + "[".repeat(20000) + "-0" + "]".repeat(20000) + "`";
            String deepParse = "class Node{value:number;next:Node|null;} export function main():null{"
                + "let text=" + classInput + ";\nlet node:Node|null=JSON.parse(text);"
                + "if((JSON.stringify(node) as string)!==text){throw {code:\"TEST\",message:\"deep class\"};}"
                + "\ntext=" + arrayInput + ";\nlet value:unknown=JSON.parse(text);"
                + "let output=JSON.stringify(value) as string;"
                + "if(output.length!==40001 || !output.includes(\"0\")){throw {code:\"TEST\",message:\"deep array\"};}"
                + "return null;}";
            execute(deepParse, "", "", root.resolve("json-parse-deep"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM), Map.of(), true);
            String failedClassInput = "`" + opening.repeat(2500) + "`+\n`"
                + opening.repeat(2500) + "{\"value\":\"bad\",\"next\":null}" + "}".repeat(5000) + "`";
            String deepFailure = "class Node{value:number;next:Node|null;} export function main():null{"
                + "let original:Node={value:7,next:null};let destination=original;let rejected=false;"
                + "try{destination=JSON.parse(" + failedClassInput + ");}catch(e){"
                + "if(e instanceof Error){rejected=e.code===\"E8001\" && e.message===\"expected number\";}}"
                + "if(!rejected || destination!==original || destination.value!==7 || destination.next!==null){"
                + "throw {code:\"TEST\",message:\"deep parse failure lost rollback or leaf\"};}return null;}";
            execute(deepFailure, "", "", root.resolve("json-parse-deep-failure"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM), Map.of(), true);
            execute("export function main():null{try{JSON.parse(`bad`);}catch(e){}return null;}",
                "", root.resolve("json-parse-builtin-only"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            String jsonFixture = "stdlib/json/builtin-stringify.deal";
            execute(ConformanceHarnessMetadata.productionCorpusSource(Files.readString(
                Path.of("test/conformance/backend-runtime").resolve(jsonFixture)), jsonFixture),
                "", root.resolve("json-corpus"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            String receiverStrings = "export function main():null{let state=0;"
                + "let receiver=():string=>{state=state*10+1;return \"  a😀b  \";};"
                + "let index=(n:number):number=>{state=state*10+n;return n;};"
                + "let text=receiver().substring(index(2),index(3));"
                + "if(state!==123 || text!==\"a\" || receiver().length!==8){throw {code:\"ORDER\",message:text};}"
                + "let s=\"a😀b\";if(s.length!==4 || s.substring(3,1)!==\"😀\" || s.substring(-1,1/0)!==s"
                + " || !s.includes(\"\\ud83d\") || !s.startsWith(\"a\") || !s.endsWith(\"b\")"
                + " || s.replace(\"😀\",\"$&$&\")!==\"a😀😀b\" || \"  x\\r\\n\".trim()!==\"x\"){"
                + "throw {code:\"STRINGS\",message:\"receiver algorithms\"};}"
                + "let parts=s.split(\"\");if(parts.length!==4 || parts[1]!==\"\\ud83d\" || parts[2]!==\"\\ude00\"){"
                + "throw {code:\"UNITS\",message:\"split\"};}"
                + "let broken=():number=>{state=7;throw {code:\"STOP\",message:\"argument\"};};"
                + "try{receiver().substring(broken(),index(8));}catch(e){}"
                + "if(state!==7){throw {code:\"ABRUPT\",message:\"argument order\"};}"
                + "let maybe:string|null=s;if(maybe!==null){if(maybe.trim()!==s){throw {code:\"REFINE\",message:\"receiver\"};}}"
                + "let invalidated:string|null=s;let clear=():void=>{invalidated=null;};"
                + "if(invalidated!==null){clear();try{invalidated.trim();throw {code:\"MISSED\",message:\"stale\"};}catch(e){"
                + "if(e instanceof Error){if(e.code!==\"E8001\"){throw e;}}}}return null;}";
            execute(receiverStrings, "", root.resolve("receiver-strings"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            String multiline = "export function main():null{"
                + "if(`A\r\nB\rC\nD` !== \"A\\nB\\nC\\nD\""
                + " || `A\\\r\nB` !== \"AB\" || `x${`a\nb`}y` !== \"xa\\nby\"){"
                + "throw {code:\"TEST\",message:\"multiline decoding\"};}return null;}";
            execute(multiline, "", root.resolve("multiline-templates"), null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            // Each seed changes values and composition depth, not the language
            // contract. Integer arithmetic here is exactly representable binary64.
            for (int seed = 0; seed < 4; seed++) {
                int base = seed + 1, delta = seed + 3, depth = seed + 1;
                String body = "let captured:()=>number=function():number{return 0;};"
                    + "let original:Error={code:\"C\",message:\"old\"};"
                    + "try{throw original;}catch(e){"
                    + "captured=function():number{if((e as Error)===original){return state;}return -1000;};"
                    + "(e as Error).message=\"new\";}"
                    + "state=state+" + delta + ";"
                    + "if(original.message!==\"new\"){throw {code:\"TEST\",message:\"identity\"};}"
                    + "let text=JSON.stringify({value:captured(),drop:captured});"
                    + "if(text===undefined){throw {code:\"TEST\",message:\"json\"};}"
                    + "let parsed:Record<string,unknown>=JSON.parse(text as string);"
                    + "if(Object.hasOwn(parsed,\"drop\")){throw {code:\"TEST\",message:\"omission\"};}"
                    + "return parsed.value as number;";
                for (int i = 0; i < depth; i++)
                    body = "let f:()=>number=function():number{" + body + "};return f();";
                String source = "import * as c from \"std/console\";"
                    + "function run():number{let state:number=" + base + ";" + body + "}"
                    + "export function main():null{if(run()!==" + (base + delta)
                    + "){throw {code:\"TEST\",message:\"value\"};}c.log(\"COMPOSE_OK\");return null;}";
                execute(source, "COMPOSE_OK\n", root.resolve("seed" + seed), null);
            }
            knownSkips.remaining("composition", skippedCompositions);
            System.out.println("Composition production: " + subjects.size()
                + " source subjects and " + executions + " verdict-checked executions passed; "
                + skippedCompositions.size() + " LLVM compositions omitted (no execution credit)");
        } finally { remove(root); }
    }
}
