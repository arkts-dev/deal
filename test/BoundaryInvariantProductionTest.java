package deal.test;

import deal.semantic.ir.CanonicalJson;
import deal.codegen.lua.AsyncExportInvocationRequest;
import deal.codegen.lua.LuaJitAsyncExportInvoker;
import deal.codegen.jvm.JvmAsyncExportInvoker;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Independently specified boundary families, including real public async consumers. */
public final class BoundaryInvariantProductionTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final String CP = Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
        .map(p -> ROOT.resolve(p).normalize().toString()).reduce((a,b) -> a + java.io.File.pathSeparator + b).orElseThrow();
    private static CanonicalJson.Value field(CanonicalJson.Value value, String key) {
        return ((CanonicalJson.Obj)value).entries().stream().filter(e -> e.key().equals(key)).findFirst().orElseThrow().value();
    }
    private static String text(CanonicalJson.Value value) { return ((CanonicalJson.Str)value).value(); }
    private static BoundedProcess.BoundedResult command(Path cwd, String... args) throws Exception {
        return BoundedProcess.runCaptured(List.of(args), cwd);
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static String output(BoundedProcess.BoundedResult result) { return result.stdout() + result.stderr(); }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("async-lua")) {
            var result = new LuaJitAsyncExportInvoker().invoke(new AsyncExportInvocationRequest(Path.of(args[1]), "run", "number"));
            require(result instanceof LuaJitAsyncExportInvoker.Result.Value, "Missing Lua completion: " + result);
            System.out.println("VALUE:" + ((LuaJitAsyncExportInvoker.Result.Value)result).valueJson());
            return;
        }
        if (args.length == 2 && args[0].equals("async-jvm")) {
            var result = new JvmAsyncExportInvoker().invoke(new AsyncExportInvocationRequest(Path.of(args[1]), "run", "number"));
            require(result instanceof JvmAsyncExportInvoker.Result.Value, "Missing JVM completion: " + result);
            System.out.println("VALUE:" + ((JvmAsyncExportInvoker.Result.Value)result).valueJson());
            return;
        }
        CanonicalJson.Value matrix = CanonicalJson.parse(Files.readString(ROOT.resolve("test/fixtures/boundary-invariants.json")));
        var cases = ((CanonicalJson.Arr)field(matrix, "cases")).items();
        require(cases.size() == 267, "Changed independent case count");
        Map<String, Integer> lanes = new TreeMap<>();
        int publicCompletions = 0;
        for (var row : cases) {
            for (var lane : ((CanonicalJson.Arr)field(row, "backends")).items()) {
                lanes.merge(text(lane), 1, Integer::sum);
                if (((CanonicalJson.Bool)field(row, "asyncExport")).value()) publicCompletions++;
            }
        }
        require(lanes.equals(Map.of("js", 267, "lua", 249, "jvm", 232)), "Changed backend obligations: " + lanes);
        require(publicCompletions == 117, "Changed public-completion denominator: " + publicCompletions);
        Path work = Files.createTempDirectory(ROOT.resolve("build"), "boundary-invariants-");
        int jobs = Integer.parseInt(System.getProperty("deal.test.jobs", "1"));
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, jobs));
        int obligations = 0;
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < cases.size(); i++) {
                final int index = i;
                futures.add(pool.submit(() -> execute(cases.get(index), work.resolve(Integer.toString(index)))));
            }
            List<String> failures = new ArrayList<>();
            for (Future<Integer> future : futures) {
                try { obligations += future.get(); }
                catch (ExecutionException failure) { failures.add(failure.getCause().toString()); }
            }
            require(failures.isEmpty(), String.join("\n", failures));
            require(obligations == 748, "Changed invariant denominator: " + obligations);
            System.out.println("BoundaryInvariantProductionTest: " + obligations + "/748 obligations passed");
        } finally {
            pool.shutdownNow();
            require(pool.awaitTermination(60, TimeUnit.SECONDS), "Invariant workers did not stop");
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static boolean numericCompletion(String actual, String expected) {
        // Public invokers serialize binary64; JSON 1 and 1.0 denote the same number.
        String jsonNumber = "-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?";
        if (!actual.matches(jsonNumber) || !expected.matches(jsonNumber)) return false;
        double av = Double.parseDouble(actual);
        double ev = Double.parseDouble(expected);
        return Double.doubleToRawLongBits(av) == Double.doubleToRawLongBits(ev);
    }

    private static int execute(CanonicalJson.Value row, Path work) throws Exception {
        String name = text(field(row, "name"));
        String expected = text(field(row, "expected"));
        boolean async = ((CanonicalJson.Bool)field(row, "asyncExport")).value();
        Path src = Files.createDirectories(work.resolve("src"));
        Files.writeString(src.resolve("main.deal"), text(field(row, "source")));
        CanonicalJson.Value host = field(row, "host");
        boolean hasHost = host instanceof CanonicalJson.Obj;
        if (hasHost) Files.writeString(src.resolve("probe.d.deal"), text(field(host, "decl")));
        Files.writeString(work.resolve("deal.json"), "{\"languageVersion\":\"1.5\",\"stdlib\":\"1.5\",\"moduleRoots\":[\"src\"]"
            + (hasHost ? ",\"externals\":{\"host/probe\":{\"declaration\":\"src/probe.d.deal\"}}" : "") + "}");
        int count = 0;
        for (CanonicalJson.Value backend : ((CanonicalJson.Arr)field(row, "backends")).items()) {
            String lane = text(backend);
            Path out = work.resolve(lane);
            var compile = command(ROOT, "java", "-cp", CP, "deal.Main", "compile", src.resolve("main.deal").toString(), "--backend", lane, "--output", out.toString());
            String context = name + " / " + lane;
            if (expected.equals("CTE")) {
                require(compile.exitCode() != 0 && output(compile).contains("ERROR E3017:") && !output(compile).contains("ERROR E6005"), context + " must reject at the assignability owner: " + output(compile));
                count++;
                continue;
            }
            require(compile.exitCode() == 0, context + " compile: " + output(compile));
            if (hasHost) {
                if (lane.equals("jvm")) Files.writeString(out.resolve("HostProbe.java"), text(field(host, "jvm")));
                else {
                    Files.createDirectories(out.resolve("host"));
                    Files.writeString(out.resolve("host/probe." + (lane.equals("js") ? "js" : "lua")), text(field(host, lane)));
                }
            }
            if (lane.equals("jvm")) {
                List<String> javac = new ArrayList<>(List.of("javac", "--release", "25", "-proc:none", "-cp", CP, "-d", out.toString()));
                try (var paths = Files.list(out)) { paths.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> javac.add(p.toString())); }
                var built = command(ROOT, javac.toArray(String[]::new));
                require(built.exitCode() == 0, context + " javac: " + output(built));
            }
            var initialized = switch (lane) {
                case "js" -> command(out, "node", "main.js");
                case "lua" -> command(out, "luajit", "main.lua");
                case "jvm" -> command(out, "java", "-cp", out + java.io.File.pathSeparator + CP, "Main");
                default -> throw new AssertionError("Unknown backend: " + lane);
            };
            require(initialized.exitCode() == 0, context + " runtime: " + output(initialized));
            if (!async) require(output(initialized).equals(expected), context + " expected " + expected + " got " + output(initialized));
            else {
                var completion = lane.equals("js")
                    ? command(out, "node", "-e", "const m=require('./main.js');if(!m.run||typeof m.run.$f!=='function')throw Error('missing export');Promise.resolve(m.run.$f()).then(v=>console.log('VALUE:'+JSON.stringify(v)),e=>{console.error(e);process.exitCode=1;});")
                    : command(ROOT, "java", "-cp", CP, BoundaryInvariantProductionTest.class.getName(), "async-" + lane, out.resolve(lane.equals("lua") ? "main.lua" : "Main.class").toString());
                require(completion.exitCode() == 0 && completion.stderr().isEmpty(), context + " completion: " + output(completion));
                List<String> receipts = completion.stdout().lines().filter(s -> s.startsWith("VALUE:")).toList();
                require(receipts.size() == 1 && numericCompletion(receipts.getFirst().substring(6), expected), context + " missing/wrong completion: " + output(completion));
            }
            count++;
        }
        return count;
    }
}
