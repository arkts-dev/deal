package deal.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Required presence, optional absence and JSON decoding through deployed artifacts. */
public final class ClassPresenceProductionTest {
    private static final String SOURCE = """
        import * as console from "std/console";
        import * as model from "model";
        import * as host from "host/record";
        // @jsonable
        export class Outer { item: model.Item; }
        function ensure(ok: boolean): null {
          if (!ok) { throw {code: "CLASS_TEST", message: "presence contract"}; }
          return null;
        }
        export function main(): null {
          let hosted: host.Record = {value: 11, nullable: null};
          ensure(hosted.value === 11);
          ensure(hosted.nullable === null);
          ensure(!has(hosted.optional));
          hosted.optional = 12;
          ensure(has(hosted.optional));
          let optionalValue: number | null = hosted.optional;
          if (optionalValue !== null) { ensure(optionalValue === 12); }
          else { throw {code: "CLASS_HOST", message: "provided optional"}; }
          delete hosted.optional;
          ensure(!has(hosted.optional));
          let item: model.Item = {value: 7, nullable: null};
          ensure(item.value === 7);
          ensure(item.nullable === null);
          ensure(!has(item.optional));
          ensure(!has(item.optionalNullable));
          item.optionalNullable = null;
          ensure(has(item.optionalNullable));
          delete item.optionalNullable;
          ensure(!has(item.optionalNullable));
          ensure(model.Item$fromJson("{}") === null);
          ensure(model.Item$fromJson("{\\"value\\":1}") === null);
          ensure(model.Item$fromJson("{\\"value\\":1,\\"nullable\\":null,\\"extra\\":2}") === null);
          ensure(model.Item$fromJson("{\\"value\\":\\"bad\\",\\"nullable\\":null}") === null);
          let decoded: model.Item | null = model.Item$fromJson("{\\"nullable\\":null,\\"value\\":3}");
          if (decoded !== null) {
            ensure(decoded.value === 3);
            ensure(decoded.nullable === null);
            ensure(!has(decoded.optional));
            ensure(!has(decoded.optionalNullable));
          } else { throw {code: "CLASS_DECODE", message: "required fields"}; }
          ensure(Outer$fromJson("{\\"item\\":{}}") === null);
          let outer: Outer | null = Outer$fromJson("{\\"item\\":{\\"value\\":4,\\"nullable\\":null}}");
          if (outer !== null) {
            ensure(outer.item.value === 4);
            ensure(!has(outer.item.optionalNullable));
          } else { throw {code: "CLASS_NESTED", message: "nested fields"}; }
          let present: model.Item | null = model.Item$fromJson("{\\"value\\":5,\\"nullable\\":null,\\"optionalNullable\\":null}");
          if (present !== null) {
            ensure(has(present.optionalNullable));
            ensure(present.optionalNullable === null);
          } else { throw {code: "CLASS_NULL", message: "present null"}; }
          console.log("class-presence-ok");
          return null;
        }
        """;
    private static final String MODEL = """
        // @jsonable
        export class Item {
          value: number;
          nullable: number | null;
          optional?: number;
          optionalNullable?: number | null;
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
        return result;
    }

    private static BoundedProcess.BoundedResult success(List<String> command, Path cwd)
            throws Exception {
        var result = run(command, cwd);
        require(result.exitCode() == 0,
            "failed " + command + "\n" + result.stdout() + result.stderr());
        return result;
    }

    public static void main(String[] args) throws Exception {
        Path home = Path.of("").toAbsolutePath();
        Path project = Files.createTempDirectory("deal-class-presence-");
        Path source = project.resolve("src/main.deal");
        Files.createDirectories(source.getParent());
        Files.writeString(source, SOURCE);
        Files.writeString(source.resolveSibling("model.deal"), MODEL);
        Files.createDirectories(project.resolve("bindings"));
        Files.writeString(project.resolve("bindings/record.d.deal"), """
            export class Record {
              value: number;
              nullable: number | null;
              optional?: number;
            }
            """);
        Files.writeString(project.resolve("deal.json"), """
            {"languageVersion":"1.4","moduleRoots":["src"],"output":"out",
             "backend":"js","stdlib":"1.4","dependencies":{},
             "externals":{"host/record":{"declaration":"bindings/record.d.deal"}}}
            """);
        String javaCommand = Path.of(System.getProperty("java.home"), "bin/java").toString();
        String javac = Path.of(System.getProperty("java.home"), "bin/javac").toString();
        String classpath = String.join(java.io.File.pathSeparator,
            java.util.Arrays.stream(System.getProperty("java.class.path")
                .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .map(p -> home.resolve(p).normalize().toString()).toList());
        for (String backend : List.of("js", "luajit", "jvm")) {
            Path output = project.resolve("out-" + backend);
            List<String> compile = List.of(javaCommand, "-Ddeal.home=" + home, "-cp",
                classpath, "deal.Main", "compile", source.toString(), "--backend", backend,
                "--output", output.toString());
            success(compile, home);
            Files.createDirectories(output.resolve("host"));
            List<String> command;
            if (backend.equals("js")) {
                Files.writeString(output.resolve("host/record.js"), """
                    const surface = {Record: {$kind: 'class',
                      $classname: '@$external/host/record/Record'}};
                    Object.defineProperty(surface, 'Record_defaults', {get() {
                      throw new Error('retired default hook was read');
                    }});
                    module.exports = surface;
                    """);
                command = List.of("node", output.resolve("main.js").toString());
            } else if (backend.equals("luajit")) {
                Files.writeString(output.resolve("host/record.lua"), """
                    return setmetatable({Record = {__kind = "class",
                      __classname = "@$external/host/record/Record"}}, {__index = function(_, k)
                        if k == "Record_defaults" then error("retired default hook was read") end
                      end})
                    """);
                command = List.of("luajit", output.resolve("main.lua").toString());
            } else {
                Path classes = project.resolve("jvm-classes");
                Files.createDirectories(classes);
                Path hostSource = project.resolve("HostRecord.java");
                Files.writeString(hostSource, """
                    public final class HostRecord {
                      public static Object Record_defaults() {
                        throw new AssertionError("retired default hook was invoked");
                      }
                    }
                    """);
                success(List.of(javac, "--release", "25", "-proc:none", "-cp", classpath,
                    "-d", classes.toString(), output.resolve("Main.java").toString(),
                    hostSource.toString()), home);
                command = List.of(javaCommand, "-cp", classes + java.io.File.pathSeparator
                    + classpath, "Main");
            }
            var result = success(command, output);
            require(result.stdout().equals("class-presence-ok\n"), backend + ": wrong transcript");
            require(result.stderr().isEmpty(), backend + ": unexpected stderr");

            // Rejection must not replace any previously published artifact.
            Path artifact = output.resolve(backend.equals("jvm") ? "Main.java" :
                backend.equals("js") ? "main.js" : "main.lua");
            byte[] before = Files.readAllBytes(artifact);
            String[][] invalid = {
                {"class C { x: number = 1; }", "E1007"},
                {"class C { x?: number = 1; }", "E1007"},
                {"class C { x: number | null = null; }", "E1007"},
                {"class C { x?: number | null = null; }", "E1007"},
                {"class C { x: number; } function f(): C { return {}; }", "E4001"},
                {"class C { x: number | null; } function f(): C { return {}; }", "E4001"},
                {"class C { x: number; } function f(): C { return {x: \"bad\"}; }", "E4003"},
                {"function f(): Error { return {message: \"bad\"}; }", "E4001"},
                {"import * as h from \"host/record\"; function f(): h.Record { return {nullable: null}; }", "E4001"},
                {"import * as h from \"host/record\"; function f(): h.Record { return {value: 1}; }", "E4001"}
            };
            for (String[] negative : invalid) {
                Files.writeString(source, negative[0]);
                var rejected = run(compile, home);
                require(rejected.exitCode() != 0, backend + ": admitted " + negative[0]);
                require((rejected.stdout() + rejected.stderr()).contains(negative[1]),
                    backend + ": missing " + negative[1] + ": " + rejected.stdout() + rejected.stderr());
                require(java.util.Arrays.equals(before, Files.readAllBytes(artifact)),
                    backend + ": rejection changed published artifact");
            }
            Files.writeString(source, SOURCE);
        }
        System.out.println("Class presence production tests passed on js, luajit, jvm");
    }
}
