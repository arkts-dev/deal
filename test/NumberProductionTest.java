package deal.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Sole-number arithmetic and integral-position contracts through the public compiler. */
public final class NumberProductionTest {
    private static final String SOURCE = """
        import * as output from "std/console";
        import * as text from "std/string";

        function ensure(ok: boolean): null {
          if (!ok) { throw {name: "TEST_FAIL", message: "number contract"}; }
          return null;
        }
        export function main(): void {
          ensure(7 / 2 === 3.5);
          ensure(2147483647 + 1 === 2147483648);
          ensure(9007199254740993 === 9007199254740992);
          let inf: number = 1 / 0;
          let nan: number = 0 / 0;
          ensure(inf > 1e300);
          ensure(nan !== nan);
          let a: number[] = [10, 20];
          ensure(a[-0] === 10);
          for (let index of [0.5, nan, inf, -inf, -1, 3]) {
            let failed: boolean = false;
            try { let value: number = a[index]; }
            catch (e) { let error = e as Error; ensure(error.name === "E8002"); failed = true; }
            ensure(failed);
            failed = false;
            try { a[index] = 30; }
            catch (e) { let error = e as Error; ensure(error.name === "E8002"); failed = true; }
            ensure(failed);
            ensure(a.length === 2);
            ensure(a[0] === 10);
            ensure(a[1] === 20);
          }
          a[2] = 30;
          ensure(a.length === 3);
          a[2] = 30;
          ensure(a.length === 3);
          ensure(text.length("a😀b") === 4);
          ensure(text.substring("a😀b", 1, 3) === "😀");
          ensure(text.substring("a😀b", 1e100, 1e100) === "");
          ensure(text.substring("a😀b", -1e100, 1e100) === "a😀b");
          for (let bound of [0.5, nan, -inf]) {
            ensure(text.substring("a😀b", bound, 1) === "a");
            ensure(text.substring("a😀b", 0, bound) === "");
          }
          ensure(text.substring("a😀b", inf, 1) === "😀b");
          ensure(text.substring("a😀b", 0, inf) === "a😀b");
          let parsed: Record<string, unknown> = JSON.parse("{\\\"a\\\":9007199254740993,\\\"z\\\":-0,\\\"b\\\":2147483647.00000001}");
          let rounded: number = parsed.a as number;
          let zero: number = parsed.z as number;
          let near: number = parsed.b as number;
          if (rounded !== 9007199254740992) { throw {name: "JSON_ROUND", message: "rounding"}; }
          if (!(1 / zero < 0)) { throw {name: "JSON_ZERO", message: "negative zero"}; }
          if (near !== 2147483647) { throw {name: "JSON_NEAR", message: "near integer"}; }
          let zeroObject: Record<string, unknown> = {z: -0};
          if ((JSON.stringify(zeroObject) as string) !== "{\\\"z\\\":0}") { throw {name: "JSON_ENCODE_ZERO", message: "zero encoding"}; }
          let int: (x: number) => number = function(x: number): number { return x + 10; };
          let number: (x: number) => number = function(x: number): number { return x + 20; };
          ensure(int(1) === 11);
          ensure(number(2) === 22);
          output.log("number-ok");
          return ;
        }
        """;

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static BoundedProcess.BoundedResult run(List<String> command, Path cwd)
            throws Exception {
        var result = BoundedProcess.runCaptured(command, cwd);
        require(!result.stdoutTruncated() && !result.stderrTruncated(),
            "incomplete output: " + command);
        require(result.exitCode() == 0,
            "failed " + command + "\n" + result.stdout() + result.stderr());
        return result;
    }

    private static String conversionReference(Path cwd) throws Exception {
        var inputs = new java.util.ArrayList<String>(List.of("-0", "1e309", "-1e-9999", "2.4703282292062327e-324", "2.4703282292062328e-324"));
        var random = new java.util.Random(0x570aL);
        for (int radix : new int[]{2, 8, 16}) for (int i = 0; i < 30; i++) {
            String prefix = radix == 2 ? "0b" : radix == 8 ? "0o" : "0x";
            inputs.add(prefix + new java.math.BigInteger(1 + random.nextInt(1300), random).toString(radix));
        }
        for (int i = 0; i < 60; i++) inputs.add((i % 2 == 0 ? "-" : "")
            + new java.math.BigInteger(1 + random.nextInt(1400), random).toString() + "e" + (random.nextInt(900) - 600));
        var command = new java.util.ArrayList<String>(List.of("node", "-e",
            "for(const s of process.argv.slice(1)){const n=Number(s);console.log(Object.is(n,-0)?'-0':String(n));}", "--"));
        command.addAll(inputs);
        var expected = run(command, cwd).stdout().lines().toList();
        require(expected.size() == inputs.size(), "complete Node StringToNumber reference");
        StringBuilder source = new StringBuilder("function conversionReference():void{\n");
        for (int i = 0; i < inputs.size(); i++) {
            String text = inputs.get(i), value = expected.get(i);
            double number = value.equals("Infinity") ? Double.POSITIVE_INFINITY : value.equals("-Infinity")
                ? Double.NEGATIVE_INFINITY : Double.parseDouble(value);
            require(Double.doubleToRawLongBits(deal.semantic.ir.NumberSemantics.fromString(text))
                == Double.doubleToRawLongBits(number), "shared StringToNumber differs from Node: " + text);
            String expression = value.equals("Infinity") ? "1/0" : value.equals("-Infinity") ? "-1/0" : value;
            source.append("check(Number(\"").append(text).append("\")===").append(expression).append(");\n");
            if (number == 0) source.append("check(1/Number(\"").append(text).append("\")===")
                .append(value.startsWith("-") ? "-1/0" : "1/0").append(");\n");
        }
        var numbers = new java.util.ArrayList<Double>(List.of(-0.0, 0.0, -0.001, 1.25, 2.55,
            1e21, 1e20, 1000000000000000128.0, Double.NaN, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.MIN_VALUE));
        for (int i = 0; i < 80; i++) numbers.add(Double.longBitsToDouble(random.nextLong()));
        for (double number : numbers) for (int places : new int[]{0, 1, 2, 6, 50, 100}) {
            String literal = Double.isNaN(number) ? "(0/0)" : number == Double.POSITIVE_INFINITY ? "(1/0)"
                : number == Double.NEGATIVE_INFINITY ? "(-1/0)" : Double.toString(number);
            String expectedFixed = run(List.of("node", "-e", "console.log((" + literal + ").toFixed(" + places + "))"), cwd).stdout().strip();
            require(deal.semantic.ir.NumberSemantics.toFixed(number, places).equals(expectedFixed), "shared toFixed reference " + literal);
            source.append("check((").append(literal).append(").toFixed(").append(places)
                .append(")===\"").append(expectedFixed).append("\");\n");
        }
        return source.append("}\n").toString();
    }

    private static void numericBuiltins() throws Exception {
        Path fixture = Path.of("test/conformance/backend-runtime/arithmetic/number-predicates-math.deal");
        Path corpus = Path.of("test/conformance");
        var discovery = deal.test.conformance.CorpusDiscovery.discover(corpus);
        require(discovery.failures().isEmpty(), "numeric corpus discovery: " + discovery.failures());
        var subject = discovery.corpusByPath().get("backend-runtime/arithmetic/number-predicates-math.deal");
        require(subject != null && subject.runtimeClassified(), "numeric fixture must be runtime-classified");
        var context = new deal.test.conformance.SidecarSchemaValidator.ValidationContext(
            subject.corpusPath(), subject.expected(),
            deal.test.conformance.CorpusDiscovery.compilationSet(subject, discovery.corpusByPath(), corpus),
            discovery.corpusModuleIndex());
        require(deal.test.conformance.SidecarSchemaValidator.validate(context,
            Files.readString(subject.sidecarPath(corpus))).isEmpty(), "numeric fixture sidecar must validate");
        Path root = Files.createTempDirectory("deal-numeric-builtins-");
        try {
            String source = ConformanceHarnessMetadata.productionCorpusSource(
                Files.readString(fixture), fixture.toString()).replace("export function main():void {",
                    "export function main():void {conversionReference();") + conversionReference(root);
            Path entry = Files.createDirectories(root.resolve("oracle/src")).resolve("main.deal");
            Files.writeString(entry, source.replace("export function main()", "export function run()")
                + "\nexport function main():void{return ;}\n");
            Files.writeString(root.resolve("oracle/deal.json"),
                "{\"languageVersion\":\"1.6\",\"stdlib\":\"1.6\",\"moduleRoots\":[\"src\"]}");
            var invocation = deal.semantic.CompilerProfileProvider.resolve(
                deal.semantic.ReleaseConfiguration.CURRENT_RELEASE_STATE,
                deal.semantic.ReleaseConfiguration.releaseCapabilityRegistry());
            var compiler = new deal.module.CompilationOrchestrator(entry, root.resolve("oracle/out"),
                false, false, false, false, deal.codegen.Backend.JS, null,
                List.of(entry.getParent()), Path.of("").toAbsolutePath(), null, invocation);
            require(compiler.compile(), "numeric builtin Oracle source rejected: " + compiler.diagnostics());
            var project = compiler.producedProject();
            var tables = new java.util.LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.StructuredBodyTable>();
            var factories = new java.util.LinkedHashMap<deal.semantic.ir.ModuleId, deal.semantic.ir.ClassFactoryRegistry>();
            project.modules().forEach((id, unit) -> { tables.put(id, unit.bodyTable()); factories.put(id, unit.classFactories()); });
            var result = deal.semantic.SemanticOracle.invokeSyncEntry(project, tables, factories,
                new deal.semantic.SemanticOracle.HostResponder() {}, project.entryModule(), "run", List.of());
            require(result.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success
                && result.effects().isEmpty(), "numeric builtin Oracle: " + result.comparisonReport());
            CompositionProductionTest.execute(source, "", root, null, List.of(
                deal.codegen.Backend.JS, deal.codegen.Backend.LUAJIT,
                deal.codegen.Backend.JVM, deal.codegen.Backend.LLVM));
            for (int level : new int[] {0, 2}) {
                Path product = root.resolve("numeric-sanitized-o" + level);
                NativeCompiler.build(new deal.toolchain.NativeBuildRequest(root.resolve("LLVM/out"),
                    product, level, List.of(), null), NativeCompiler.SANITIZER_FLAGS);
                for (boolean stress : new boolean[] {false, true}) {
                    var environment = new java.util.HashMap<>(NativeCompiler.sanitizerEnvironment());
                    environment.put("DEAL_GC_STRESS", stress ? "1" : "0");
                    var observed = NativeCompiler.runSanitized(product, root, environment).result();
                    require(observed.exitCode() == 0 && observed.stdout().isEmpty() && observed.stderr().isEmpty(),
                        "numeric builtins LLVM O" + level + " sanitized stress=" + stress + ": " + observed.describe());
                }
            }
        } finally { CompositionProductionTest.remove(root); }
    }

    static void conversionOnlyPrograms() throws Exception {
        Path root = Files.createTempDirectory("deal-conversion-only-");
        try {
            int index = 0;
            for (String statement : List.of("console.log(String(0));", "console.log(String(Number(\"7\")));",
                    "Number(\"0\");", "String(0);", "console.log(String(-0));")) {
                String expected = statement.startsWith("console")
                    ? (statement.contains("7") ? "7\n" : "0\n") : "";
                CompositionProductionTest.execute("export function main():void{" + statement + "}", expected,
                    root.resolve("case-" + index++), null, List.of(deal.codegen.Backend.JS,
                        deal.codegen.Backend.LUAJIT, deal.codegen.Backend.JVM, deal.codegen.Backend.LLVM));
            }
        } finally { CompositionProductionTest.remove(root); }
    }

    public static void main(String[] args) throws Exception {
        conversionOnlyPrograms();
        numericBuiltins();
        Path home = Path.of("").toAbsolutePath();
        Path project = Files.createTempDirectory("deal-number-production-");
        Path source = project.resolve("src/main.deal");
        Files.createDirectories(source.getParent());
        Files.writeString(source, SOURCE);
        Files.writeString(project.resolve("deal.json"), """
            {"languageVersion":"1.6","moduleRoots":["src"],"output":"out",
             "backend":"js","stdlib":"1.6","dependencies":{},"externals":{}}
            """);
        String javaCommand = Path.of(System.getProperty("java.home"), "bin/java").toString();
        String javac = Path.of(System.getProperty("java.home"), "bin/javac").toString();
        String classpath = String.join(java.io.File.pathSeparator,
            java.util.Arrays.stream(System.getProperty("java.class.path")
                .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .map(p -> home.resolve(p).normalize().toString()).toList());
        for (String backend : List.of("js", "luajit", "jvm")) {
            Path output = project.resolve("out-" + backend);
            run(List.of(javaCommand, "-Ddeal.home=" + home, "-cp", classpath, "deal.Main",
                "compile", source.toString(), "--backend", backend,
                "--output", output.toString()), home);
            List<String> command;
            if (backend.equals("js")) {
                command = List.of("node", output.resolve("main.js").toString());
            } else if (backend.equals("luajit")) {
                command = List.of("luajit", output.resolve("main.lua").toString());
            } else {
                Path classes = project.resolve("jvm-classes");
                Files.createDirectories(classes);
                run(List.of(javac, "--release", "25", "-proc:none", "-cp", classpath,
                    "-d", classes.toString(), output.resolve("Main.java").toString()), home);
                command = List.of(javaCommand, "-cp", classes + java.io.File.pathSeparator
                    + classpath, "Main");
            }
            var result = run(command, output);
            require(result.stdout().equals("number-ok\n"), backend + ": wrong transcript");
            require(result.stderr().isEmpty(), backend + ": unexpected stderr");
        }
        System.out.println("Number production tests passed; numeric builtins on Oracle, JS, LuaJIT, JVM and LLVM O0/O2 normal/stress/plain/sanitized");
    }
}
