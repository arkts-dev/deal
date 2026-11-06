package deal.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Required presence, optional absence and JSON decoding through deployed artifacts. */
public final class ClassPresenceProductionTest {
    private static final String SOURCE = """
        import * as output from "std/console";
        import * as model from "model";
        import * as host from "host/record";
        // @jsonable
        export class Outer { item: model.Item; }
        function ensure(ok: boolean): null {
          if (!ok) { throw {code: "CLASS_TEST", message: "presence contract"}; }
          return null;
        }
        export function main(): null {
          let hosted: host.HostRecord = {value: 11, nullable: null};
          ensure(hosted.value === 11);
          ensure(hosted.nullable === null);
          ensure(!Object.hasOwn(hosted, "optional"));
          hosted.optional = 12;
          ensure(Object.hasOwn(hosted, "optional"));
          let optionalValue: number | undefined = hosted.optional;
          if (optionalValue !== undefined) { ensure(optionalValue === 12); }
          else { throw {code: "CLASS_HOST", message: "provided optional"}; }
          delete hosted.optional;
          ensure(!Object.hasOwn(hosted, "optional"));
          let item: model.Item = {value: 7, nullable: null};
          ensure(item.value === 7);
          ensure(item.nullable === null);
          ensure(!Object.hasOwn(item, "optional"));
          ensure(!Object.hasOwn(item, "optionalNullable"));
          item.optionalNullable = null;
          ensure(Object.hasOwn(item, "optionalNullable"));
          delete item.optionalNullable;
          ensure(!Object.hasOwn(item, "optionalNullable"));
          ensure(model.Item$fromJson("{}") === null);
          ensure(model.Item$fromJson("{\\"value\\":1}") === null);
          ensure(model.Item$fromJson("{\\"value\\":1,\\"nullable\\":null,\\"extra\\":2}") === null);
          ensure(model.Item$fromJson("{\\"value\\":\\"bad\\",\\"nullable\\":null}") === null);
          let decoded: model.Item | null = model.Item$fromJson("{\\"nullable\\":null,\\"value\\":3}");
          if (decoded !== null) {
            ensure(decoded.value === 3);
            ensure(decoded.nullable === null);
            ensure(!Object.hasOwn(decoded, "optional"));
            ensure(!Object.hasOwn(decoded, "optionalNullable"));
          } else { throw {code: "CLASS_DECODE", message: "required fields"}; }
          ensure(Outer$fromJson("{\\"item\\":{}}") === null);
          let outer: Outer | null = Outer$fromJson("{\\"item\\":{\\"value\\":4,\\"nullable\\":null}}");
          if (outer !== null) {
            ensure(outer.item.value === 4);
            ensure(!Object.hasOwn(outer.item, "optionalNullable"));
          } else { throw {code: "CLASS_NESTED", message: "nested fields"}; }
          let present: model.Item | null = model.Item$fromJson("{\\"value\\":5,\\"nullable\\":null,\\"optionalNullable\\":null}");
          if (present !== null) {
            ensure(Object.hasOwn(present, "optionalNullable"));
            ensure((present.optionalNullable as number | null) === null);
          } else { throw {code: "CLASS_NULL", message: "present null"}; }
          output.log("class-presence-ok");
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

    /** JS/Lua raw tables can represent hostile states excluded by JVM typed storage. */
    private static void rawHostFieldMutations() throws Exception {
        Path root = Files.createTempDirectory("raw-host-fields-");
        try {
            Path host = root.resolve("host");
            Files.createDirectories(host);
            String imports = "import * as output from \"std/console\";import * as host from \"host/probe\";";
            int cases = 0;
            for (String field : List.of("number", "unknown")) {
                for (String parameter : List.of("C", "unknown")) {
                    Files.writeString(host.resolve("probe.d.deal"), "export class C{x:" + field
                        + ";}export function mutate(v:" + parameter + "):void;");
                    rawHost(host, "C", "delete v.x;", "v.x=nil;");
                    CompositionProductionTest.execute(imports + "export function main():null{"
                        + "let n:host.C={x:1};host.mutate(n);if(Object.hasOwn(n,\"x\")){"
                        + "throw {code:\"TEST\",message:\"required field still present\"};}output.log(\"ABSENT\");return null;}",
                        "ABSENT\n", root.resolve("live-" + field + "-" + parameter), host,
                        List.of(deal.codegen.Backend.JS, deal.codegen.Backend.LUAJIT));
                    cases++;
                }
            }
            Files.writeString(host.resolve("probe.d.deal"),
                "export class Node{x:unknown;next:Node|null;}export function mutate(v:Node):void;");
            rawHost(host, "Node", "delete v.x;", "v.x=nil;");
            for (String boundary : List.of("assert", "parameter", "return")) {
                String operation = switch (boundary) {
                    case "assert" -> "let d=n as host.Node;";
                    case "parameter" -> "consume(n);";
                    default -> "let d=get();";
                };
                CompositionProductionTest.execute(imports
                    + "function consume(n:host.Node):void{}function identity(n:host.Node):host.Node{return n;}"
                    + "export function main():null{let n:host.Node={x:1,next:null};n.next=n;"
                    + "let get:()=>host.Node=function():host.Node{output.log(\"RETURN_BODY\");return n;};host.mutate(n);try{"
                    + operation + "output.log(\"OK\");}catch(e){output.log(\"REJECT\");}return null;}",
                    boundary.equals("return") ? "RETURN_BODY\nREJECT\n" : "REJECT\n", root.resolve("cycle-" + boundary), host,
                    List.of(deal.codegen.Backend.JS, deal.codegen.Backend.LUAJIT));
                cases++;
            }
            for (String[] shape : List.of(new String[]{"x?:number", "1", "undefined"},
                    new String[]{"x:number", "1", "delete"}, new String[]{"x:number", "1", "null"},
                    new String[]{"x:number", "1", "undefined"}, new String[]{"x:undefined", "undefined", "delete"},
                    new String[]{"x:unknown", "1", "delete"})) {
                Files.writeString(host.resolve("probe.d.deal"), "export class C{" + shape[0]
                    + ";}export function mutate(v:unknown):void;");
                String js = shape[2].equals("delete") ? "delete v.x;" : "v.x=" + shape[2] + ";";
                String lua = shape[2].equals("delete") ? "v.x=nil;"
                    : "v.x=rt.__" + shape[2].toUpperCase(java.util.Locale.ROOT) + ";";
                rawHost(host, "C", js, lua);
                CompositionProductionTest.execute(imports + "export function main():null{let n:host.C={x:"
                    + shape[1] + "};host.mutate(n);try{let d=n as host.C;output.log(\"OK\");}"
                    + "catch(e){output.log(\"REJECT\");}return null;}", "REJECT\n",
                    root.resolve("presence-" + cases++), host,
                    List.of(deal.codegen.Backend.JS, deal.codegen.Backend.LUAJIT));
            }
            if (cases != 13) throw new AssertionError("lost raw-field mutation check");
            System.out.println("Raw JS/Lua host fields: 13 checks, 26 executions passed");
        } finally { CompositionProductionTest.remove(root); }
    }

    private static void rawHost(Path host, String name, String js, String lua) throws Exception {
        Files.writeString(host.resolve("probe.js"), "module.exports={" + name
            + ":{$kind:\"class\",$classname:\"@$external/host/probe/" + name + "\"},mutate:v=>{" + js + "}};");
        Files.writeString(host.resolve("probe.lua"), "local rt=require(\"deal.runtime\");local K=rt.__keys;return {"
            + name + "={[K.kind]=\"class\",[K.classname]=\"@$external/host/probe/" + name
            + "\"},mutate=function(v)" + lua + "end}");
    }

    public static void main(String[] args) throws Exception {
        rawHostFieldMutations();
        Path home = Path.of("").toAbsolutePath();
        Path project = Files.createTempDirectory("deal-class-presence-");
        Path source = project.resolve("src/main.deal");
        Files.createDirectories(source.getParent());
        Files.writeString(source, SOURCE);
        Files.writeString(source.resolveSibling("model.deal"), MODEL);
        Files.createDirectories(project.resolve("bindings"));
        Files.writeString(project.resolve("bindings/record.d.deal"), """
            export class HostRecord {
              value: number;
              nullable: number | null;
              optional?: number;
            }
            """);
        Files.writeString(project.resolve("deal.json"), """
            {"languageVersion":"1.6","moduleRoots":["src"],"output":"out",
             "backend":"js","stdlib":"1.6","dependencies":{},
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
                    const surface = {HostRecord: {$kind: 'class',
                      $classname: '@$external/host/record/HostRecord'}};
                    Object.defineProperty(surface, 'HostRecord_defaults', {get() {
                      throw new Error('retired default hook was read');
                    }});
                    module.exports = surface;
                    """);
                command = List.of("node", output.resolve("main.js").toString());
            } else if (backend.equals("luajit")) {
                Files.writeString(output.resolve("host/record.lua"), """
                    return setmetatable({HostRecord = {__kind = "class",
                      __classname = "@$external/host/record/HostRecord"}}, {__index = function(_, k)
                        if k == "HostRecord_defaults" then error("retired default hook was read") end
                      end})
                    """);
                command = List.of("luajit", output.resolve("main.lua").toString());
            } else {
                Path classes = project.resolve("jvm-classes");
                Files.createDirectories(classes);
                Path hostSource = project.resolve("HostRecord.java");
                Files.writeString(hostSource, """
                    public final class HostRecord {
                      public static final String HostRecord = "@$external/host/record/HostRecord";
                      public static Object HostRecord_defaults() {
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
                {"import * as h from \"host/record\"; function f(): h.HostRecord { return {nullable: null}; }", "E4001"},
                {"import * as h from \"host/record\"; function f(): h.HostRecord { return {value: 1}; }", "E4001"}
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
