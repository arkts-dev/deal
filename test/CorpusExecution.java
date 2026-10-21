package deal.test;

import deal.ast.*;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmNames;
import deal.test.conformance.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Backend-specific artifact launch only; discovery, preparation and verdict belong to the corpus driver. */
final class CorpusExecution {
    record Probe(String name, boolean async) {}
    record Source(String corpusPath, int headers) {}
    static final String RECEIPT = "__corpus_calls.txt";

    static void controls() throws Exception {
        String source = "export function first(): int { return 1; } "
            + "export async function second(): int { return 2; } export function main(): null {}";
        var parsed = new deal.parser.Parser(new deal.lexer.Lexer(source, "control.deal").tokenize().tokens(), "control.deal").parse();
        if (!probes(parsed.program(), false).equals(List.of(new Probe("main", false),
                new Probe("first", false), new Probe("second", true)))) throw new AssertionError("dropped/reordered parsed probe");
        boolean rejected = false;
        try { probes(parsed.program(), true); } catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("expected failure may hide a subsequent probe");
        Path root = Files.createTempDirectory("corpus-receipt-control-");
        try {
            List<Probe> plan = List.of(new Probe("main", false), new Probe("check", true));
            List<String> complete = List.of("load:start", "load:done", "main:start", "main:done", "check:start", "check:done");
            Files.write(root.resolve(RECEIPT), complete);
            if (receiptVerdict(root, plan, false, false).isPresent()) throw new AssertionError("valid consumer receipt refused");
            for (int omitted = 0; omitted < complete.size(); omitted++) {
                var damaged = new ArrayList<>(complete); damaged.remove(omitted); Files.write(root.resolve(RECEIPT), damaged);
                if (receiptVerdict(root, plan, false, false).isEmpty()) throw new AssertionError("lost invocation/completion accepted");
            }
            Files.write(root.resolve(RECEIPT), complete.subList(0, complete.size()-1));
            if (receiptVerdict(root, plan, true, false).isPresent()) throw new AssertionError("expected final throw refused");
            Files.write(root.resolve(RECEIPT), complete.subList(0, 3));
            if (receiptVerdict(root, plan, true, false).isEmpty()) throw new AssertionError("early throw hid a consumer");
        } finally { Files.deleteIfExists(root.resolve(RECEIPT)); Files.delete(root); }
    }

    static List<Probe> probes(ProgramNode program, boolean failure) {
        List<Probe> probes = new ArrayList<>();
        FunctionDeclaration main = null;
        for (StatementNode statement : program.statements()) {
            if (statement instanceof ExportDeclaration export
                    && export.declaration() instanceof FunctionDeclaration fn
                    && fn.params().isEmpty() && !fn.name().contains("$")) {
                if (fn.name().equals("main")) main = fn;
                else probes.add(new Probe(fn.name(), fn.isAsync()));
            }
        }
        if (main == null || main.isAsync()) throw new IllegalArgumentException("missing synchronous main(): null");
        if (failure && !probes.isEmpty() && !noOp(main.body())) throw new IllegalArgumentException(
            "negative main may hide exported probes; use an empty main and one explicit failing probe");
        if (failure && probes.size() > 1) throw new IllegalArgumentException(
            "negative fixture has multiple independent probes; split it so an expected throw cannot hide a consumer");
        probes.addFirst(new Probe("main", false));
        return List.copyOf(probes);
    }

    private static boolean noOp(Block body) {
        return body.statements().isEmpty() || body.statements().size() == 1
            && body.statements().getFirst() instanceof ReturnStatement r
            && (r.expr().isEmpty() || r.expr().get() instanceof LiteralExpr l
                && l.value() instanceof LiteralValue.NullLiteral);
    }

    static Optional<String> receiptVerdict(Path out, List<Probe> probes, boolean failure,
            boolean loadFailure) throws Exception {
        List<String> actual = Files.exists(out.resolve(RECEIPT))
            ? Files.readAllLines(out.resolve(RECEIPT)) : List.of();
        List<String> expected = new ArrayList<>(List.of("load:start", "load:done"));
        for (Probe probe : probes) { expected.add(probe.name() + ":start"); expected.add(probe.name() + ":done"); }
        if (failure) {
            if (loadFailure && (actual.equals(List.of("load:start"))
                    || actual.equals(List.of("load:start", "load:done", "main:start")))) return Optional.empty();
            expected.removeLast(); // only the designated final invocation may terminate with the pinned error
        }
        return actual.equals(expected) ? Optional.empty()
            : Optional.of("CORPUS_CONSUMER_LOSS: expected " + expected + ", observed " + actual);
    }

    static BoundedProcess.BoundedResult run(Backend backend, Path out, String module,
            List<Probe> probes, Map<String, Source> sources, Map<String, String> tokens,
            List<String> optionals, Set<String> hosts, Path hostRoot) throws Exception {
        out = out.toAbsolutePath().normalize();
        Files.deleteIfExists(out.resolve(RECEIPT));
        if (backend == Backend.LUAJIT) {
            for (String host : hosts) {
                Path target = out.resolve("host/" + host + ".lua");
                Files.createDirectories(target.getParent()); Files.copy(hostRoot.resolve(host + ".lua"), target);
            }
            Files.writeString(out.resolve("CorpusRunner.lua"), lua(module, probes, sources, tokens, optionals));
            return BoundedProcess.runCaptured(List.of("luajit", "CorpusRunner.lua"), out,
                Map.of("DEAL_DEFER_MAIN", "1"));
        }
        String className = JvmNames.classNameFor(module);
        List<String> command = new ArrayList<>(List.of("javac", "--release", "25", "-proc:none",
            "-cp", classpath(), "-d", out.resolve("classes").toString()));
        Files.createDirectories(out.resolve("classes"));
        try (var files = Files.walk(out)) {
            files.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> command.add(p.toString()));
        }
        for (String host : hosts) {
            Path target = out.resolve(JvmNames.classNameFor("host/" + host) + ".java");
            Files.copy(hostRoot.resolve(host + ".java"), target); command.add(target.toString());
        }
        String runner = "CorpusRunner_" + UUID.randomUUID().toString().replace("-", "");
        Path file = out.resolve(runner + ".java");
        Files.writeString(file, jvm(runner, className, module, probes, sources, tokens, optionals));
        command.add(file.toString());
        JvmCompilation.Result compiled = JvmCompilation.compile(command, out);
        if (compiled.exitCode() != 0) throw new IllegalStateException("generated JVM sources rejected: " + compiled.stderr());
        return BoundedProcess.runCaptured(List.of("java", "-cp",
            out.resolve("classes") + java.io.File.pathSeparator + classpath(), runner), out,
            Map.of("DEAL_DEFER_MAIN", "1"));
    }

    private static String classpath() {
        return String.join(java.io.File.pathSeparator, Arrays.stream(System.getProperty("java.class.path")
            .split(Pattern.quote(java.io.File.pathSeparator))).map(p -> Path.of(p).toAbsolutePath().toString()).toList());
    }
    static String quote(String text) {
        StringBuilder result = new StringBuilder("\"");
        for (int c : text.codePoints().toArray()) switch (c) {
            case '"' -> result.append("\\\""); case '\\' -> result.append("\\\\");
            case '\n' -> result.append("\\n"); case '\r' -> result.append("\\r"); case '\t' -> result.append("\\t");
            default -> { if (c < 32) result.append(String.format("\\u%04X", c)); else result.appendCodePoint(c); }
        }
        return result.append('"').toString();
    }

    private static String jvm(String runner, String cls, String module, List<Probe> probes,
            Map<String, Source> sources, Map<String, String> tokens, List<String> optionals) {
        StringBuilder s = new StringBuilder("import java.nio.file.*; import java.util.*; import deal.codegen.jvm.JvmRuntime;\n"
            + "public final class " + runner + " {\n"
            + "static void mark(String s) throws Exception {Files.writeString(Path.of(" + quote(RECEIPT)
            + "),s+\"\\n\",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}\n"
            + "public static void main(String[] args) throws Exception {try {mark(\"load:start\");\n"
            + "Class.forName(" + quote(cls) + "); mark(\"load:done\");\n");
        for (Probe probe : probes) {
            s.append("mark(").append(quote(probe.name() + ":start")).append(");\n");
            if (probe.name().equals("main")) s.append(cls).append(".dealMain();\n");
            else if (probe.async()) {
                s.append("{ var field = ").append(cls).append(".class.getDeclaredField(\"$asyncExports\"); field.setAccessible(true);\n")
                    .append("@SuppressWarnings(\"unchecked\") var rows = (List<JvmRuntime.AsyncExportRow>)field.get(null);\n")
                    .append("var row = rows.stream().filter(r -> r.name().equals(").append(quote(probe.name())).append("))").append(".findFirst().orElseThrow();\n")
                    .append("if(row.invoke()==null) throw new IllegalStateException(\"missing async entry\"); row.invoke().invoke(new Object[0]);}\n");
            } else s.append("{ Object value = JvmRuntime.exportSurface(").append(quote(module)).append(").read(")
                .append(quote(probe.name())).append("); if (!(value instanceof JvmRuntime.FunctionValue fn)) throw new IllegalStateException(\"missing probe\"); fn.fn.invoke(new Object[0]); }\n");
            s.append("mark(").append(quote(probe.name() + ":done")).append(");\n");
        }
        s.append("}catch(Throwable t){while ((t instanceof ExceptionInInitializerError || t instanceof java.lang.reflect.InvocationTargetException) && t.getCause()!=null)t=t.getCause();\n")
            .append("if (!(t instanceof JvmRuntime.DealError e)) {t.printStackTrace();System.exit(2);return;}\n")
            .append("String file=null; Integer line=null,column=null; var m=java.util.regex.Pattern.compile(\"^(.*):([0-9]+):([0-9]+)$\").matcher(e.origin==null?\"\":e.origin);\n")
            .append("if(m.matches()){file=m.group(1);line=Integer.valueOf(m.group(2));column=Integer.valueOf(m.group(3));}\n");
        for (var entry : sources.entrySet()) s.append("if(").append(quote(entry.getKey())).append(".equals(file)) {file=")
            .append(quote(entry.getValue().corpusPath())).append(";line+=").append(entry.getValue().headers()).append(";}\n");
        s.append("var fields=new deal.test.conformance.SidecarExpectations.ErrorExpectation(e.code, normalize(e.msg), file,line,column,")
            .append(optionals.contains("expected") ? "Optional.ofNullable(normalize(e.expected))" : "Optional.empty()") .append(',')
            .append(optionals.contains("actual") ? "Optional.ofNullable(normalize(e.actual))" : "Optional.empty()") .append(',')
            .append(optionals.contains("frames") ? "Optional.of(e.frames==null || e.frames.isEmpty() || e.frames.equals(\"-\")?0:e.frames.split(\",\").length)" : "Optional.empty()") .append(',')
            .append(optionals.contains("cause") ? "Optional.ofNullable(e.cause==null?null:e.cause.code)" : "Optional.empty()") .append(");\n")
            .append("System.out.println(\"DEAL_ERROR_CODE: \"+e.code); System.out.println(\"DEAL_ERROR_SNAPSHOT: \"+deal.test.conformance.ErrorSnapshot.canonicalJson(fields));System.exit(1);}}\n")
            .append("static String normalize(String s){if(s==null)return null;\n");
        // Longest module paths first: exact token boundaries prevent rewriting a sibling atom.
        for (var entry : tokens.entrySet().stream().sorted((a,b) -> Integer.compare(b.getKey().length(), a.getKey().length())).toList()) s.append("s=s.replace(").append(quote("@conformance/" + entry.getKey() + "/"))
            .append(',').append(quote("@conformance/" + entry.getValue() + "/")).append(");\n");
        return s.append("return s;}}\n").toString();
    }

    private static String lua(String module, List<Probe> probes, Map<String, Source> sources,
            Map<String, String> tokens, List<String> optionals) {
        StringBuilder s = new StringBuilder("local function mark(s) local f=assert(io.open(" + quote(RECEIPT)
            + ",\"a\"));f:write(s,\"\\n\");f:close() end\n"
            + "local function esc(s) s=tostring(s);local a={'\\\"'};for i=1,#s do local b=s:byte(i);if b==34 then a[#a+1]='\\\\\\\"' elseif b==92 then a[#a+1]='\\\\\\\\' elseif b==10 then a[#a+1]='\\\\n' elseif b==13 then a[#a+1]='\\\\r' elseif b==9 then a[#a+1]='\\\\t' elseif b==8 then a[#a+1]='\\\\b' elseif b==12 then a[#a+1]='\\\\f' elseif b<32 then a[#a+1]=string.format('\\\\u%04X',b) else a[#a+1]=string.char(b) end end;a[#a+1]='\\\"';return table.concat(a) end\n"
            + "local function normalize(s) if s==nil then return nil end\n");
        for (var entry : tokens.entrySet().stream().sorted((a,b) -> Integer.compare(b.getKey().length(), a.getKey().length())).toList()) s.append("do local from,to=").append(quote("@conformance/" + entry.getKey() + "/"))
            .append(',').append(quote("@conformance/" + entry.getValue() + "/"))
            .append(";local p=1;while true do local a,b=string.find(s,from,p,true);if not a then break end;s=string.sub(s,1,a-1)..to..string.sub(s,b+1);p=a+#to end end\n");
        s.append("return s end\nlocal ok,err=pcall(function() mark('load:start');dofile(")
            .append(quote(module.replace('.', '/') + ".lua")).append(");mark('load:done')\n");
        for (Probe probe : probes) {
            s.append("mark(").append(quote(probe.name() + ":start")).append(")\n");
            if (probe.name().equals("main")) s.append("local ok,e=__dealMain();if not ok then error(e,0) end\n");
            else if (probe.async()) s.append("assert(__asyncEntries[").append(quote(module + "#" + probe.name())).append("], 'missing async probe')('-',true)\n");
            else s.append("do local f=assert(__exportSurfaces[").append(quote(module)).append("][")
                .append(quote(probe.name())).append("], 'missing probe');assert(type(f.f)=='function','probe is not callable');f.f() end\n");
            s.append("mark(").append(quote(probe.name() + ":done")).append(")\n");
        }
        s.append("end)\nif not ok then\nif type(err)~='table' or (not err[__dealCarrierKeys.d] and not (type(err.code)=='string' and err.code:match('^FFI_') and type(err.message)=='string')) then io.stderr:write(tostring(err),'\\n');os.exit(2) end\n")
            .append("local file,line,column=tostring(err.o or ''):match('^(.*):(%d+):(%d+)$');line=tonumber(line);column=tonumber(column);if not file then file=err.file;line=err.line;column=err.column end\n");
        for (var entry : sources.entrySet()) s.append("if file==").append(quote(entry.getKey())).append(" then file=")
            .append(quote(entry.getValue().corpusPath())).append(";line=line+").append(entry.getValue().headers()).append(" end\n");
        s.append("local fields={'\\\"code\\\":'..esc(err.code),'\\\"message\\\":'..esc(normalize(err.m or err.message or ''))}\n")
            .append("if file then fields[#fields+1]='\\\"sourceFile\\\":'..esc(file);fields[#fields+1]='\\\"line\\\":'..line;fields[#fields+1]='\\\"column\\\":'..column end\n");
        for (String key : optionals) {
            String value = switch(key) {case "expected" -> "err.e or err.expected"; case "actual" -> "err.a or err.actual"; case "frames" -> "(err.f==nil or err.f=='-') and 0 or select(2,err.f:gsub(',',','))+1"; default -> "err.cause and err.cause.code";};
            s.append("do local v=").append(value).append(";if v~=nil then fields[#fields+1]=")
                .append(quote("\"" + key + "\":")) .append("..")
                .append(key.equals("frames") ? "tostring(v)" : "esc(normalize(v))").append(" end end\n");
        }
        return s.append("print('DEAL_ERROR_CODE: '..err.code);print('DEAL_ERROR_SNAPSHOT: {'..table.concat(fields,',')..'}');os.exit(1) end\n").toString();
    }
}
