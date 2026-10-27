package deal.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Sole-number arithmetic and integral-position contracts through the public compiler. */
public final class NumberProductionTest {
    private static final String SOURCE = """
        import * as console from "std/console";
        import * as text from "std/string";
        import * as json from "std/json";
        function ensure(ok: boolean): null {
          if (!ok) { throw {code: "TEST_FAIL", message: "number contract"}; }
          return null;
        }
        export function main(): null {
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
            catch (e) { let error = e as Error; ensure(error.code === "E8002"); failed = true; }
            ensure(failed);
            failed = false;
            try { a[index] = 30; }
            catch (e) { let error = e as Error; ensure(error.code === "E8002"); failed = true; }
            ensure(failed);
            failed = false;
            try { delete a[index]; }
            catch (e) { let error = e as Error; ensure(error.code === "E8002"); failed = true; }
            ensure(failed);
            ensure(a.length === 2);
            ensure(a[0] === 10);
            ensure(a[1] === 20);
          }
          a[2] = 30;
          ensure(a.length === 3);
          delete a[3];
          ensure(a.length === 3);
          ensure(text.length("a😀b") === 3);
          ensure(text.substring("a😀b", 1, 2) === "😀");
          ensure(text.substring("a😀b", 1e100, 1e100) === "");
          ensure(text.substring("a😀b", -1e100, 1e100) === "a😀b");
          for (let bound of [0.5, nan, inf, -inf]) {
            let failed: boolean = false;
            try { text.substring("a😀b", bound, 2); }
            catch (e) {
              let error = e as Error;
              ensure(error.code === "E8001");
              ensure(error.message === "substring bounds must be finite integers");
              failed = true;
            }
            ensure(failed);
            failed = false;
            try { text.substring("a😀b", 0, bound); }
            catch (e) {
              let error = e as Error;
              ensure(error.code === "E8001");
              ensure(error.message === "substring bounds must be finite integers");
              failed = true;
            }
            ensure(failed);
          }
          let parsed: Record<string, unknown> = json.parse("{\\\"a\\\":9007199254740993,\\\"z\\\":-0,\\\"b\\\":2147483647.00000001}");
          let rounded: number = parsed.a as number;
          let zero: number = parsed.z as number;
          let near: number = parsed.b as number;
          if (rounded !== 9007199254740992) { throw {code: "JSON_ROUND", message: "rounding"}; }
          if (!(1 / zero < 0)) { throw {code: "JSON_ZERO", message: "negative zero"}; }
          if (near !== 2147483647) { throw {code: "JSON_NEAR", message: "near integer"}; }
          let zeroObject: Record<string, unknown> = {z: -0};
          if ((json.stringify(zeroObject) as string) !== "{\\\"z\\\":0}") { throw {code: "JSON_ENCODE_ZERO", message: "zero encoding"}; }
          let int: (x: number) => number = function(x: number): number { return x + 10; };
          let number: (x: number) => number = function(x: number): number { return x + 20; };
          ensure(int(1) === 11);
          ensure(number(2) === 22);
          console.log("number-ok");
          return null;
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

    public static void main(String[] args) throws Exception {
        Path home = Path.of("").toAbsolutePath();
        Path project = Files.createTempDirectory("deal-number-production-");
        Path source = project.resolve("src/main.deal");
        Files.createDirectories(source.getParent());
        Files.writeString(source, SOURCE);
        Files.writeString(project.resolve("deal.json"), """
            {"languageVersion":"1.5","moduleRoots":["src"],"output":"out",
             "backend":"js","stdlib":"1.5","dependencies":{},"externals":{}}
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
        System.out.println("Number production tests passed on js, luajit, jvm");
    }
}
