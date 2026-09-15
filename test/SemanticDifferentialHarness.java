package deal.test;

import deal.codegen.jvm.JvmSemanticEmitter;
import deal.codegen.lua.LuaSemanticEmitter;
import deal.semantic.SemanticOracle;
import deal.semantic.SemanticTraceProtocol;
import deal.semantic.SemanticRuntimeModel;

import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.OpId;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The differential harness of the decomposition-tail integration
 * verification (ISSUE-0410): runs the identical validated
 * {@link LoweredModuleUnit} + {@link StructuredBodyTable} through the
 * semantic oracle and both shared emitters' real artifacts and compares
 * the three reports event-for-event (semantic-lowering-differential-
 * conformance D2/D4/D7 — no common-consumer skip, no hollow pass).
 *
 * <p><b>Run sequence per consumer.</b> The oracle executes in-process
 * ({@link SemanticOracle}). The shared LuaJIT artifact is emitted by
 * {@link LuaSemanticEmitter}, written to an isolated workspace, executed
 * by the real {@code luajit} binary with the pinned runtime, and its
 * protocol lines are decoded. The shared JVM artifact is emitted by
 * {@link JvmSemanticEmitter}, compiled with the real {@code javac
 * --release 25 -proc:none}, executed by the real {@code java} on the
 * compiled classes, and decoded the same way. Real console effect bytes
 * on each artifact's stdout are cross-checked against its recorded
 * effects. A tool failure is an infrastructure failure of the harness
 * verdict, never a pass.</p>
 *
 * <p><b>Verdict gates (all must hold).</b></p>
 * <ol>
 *   <li>Every consumer produces a report: three real per-consumer
 *       results with at least one event each (no stubbed/partial
 *       run).</li>
 *   <li>Every event validates against its exact validated IR operation:
 *       module, op id, structural parent, kind, and the recomputed
 *       operation-contract digest — a moved child, a wrong selector, a
 *       missing boundary, or a fabricated event fails even with
 *       coincidental output.</li>
 *   <li>Each op has exactly one START and one terminal event, and the
 *       three traces match event-for-event (canonical text).</li>
 *   <li>Ordered effects match event-for-event across consumers and equal
 *       the seed's pinned expectation (the wiki projection).</li>
 *   <li>Terminals match across consumers and equal the seed's pinned
 *       expectation (post-state/result and error origin).</li>
 * </ol>
 */
public final class SemanticDifferentialHarness {

    private SemanticDifferentialHarness() {
    }

    /** The pinned expectation of one matrix seed (the wiki projection). */
    public sealed interface TerminalExpectation {

        /** The run succeeds with exactly this result atom. */
        record SuccessWith(String resultAtom) implements TerminalExpectation {
            public SuccessWith {
                Objects.requireNonNull(resultAtom, "resultAtom must not be null");
            }
        }

        /** The run fails with exactly this code at exactly this origin. */
        record FailureWith(String code, String originText) implements TerminalExpectation {
            public FailureWith {
                Objects.requireNonNull(code, "code must not be null");
            }
        }
    }

    /** The seed expectation: ordered effects plus the terminal projection. */
    public record Expectation(List<String> effects, TerminalExpectation terminal,
                              String what) {

        public Expectation {
            effects = List.copyOf(effects);
            Objects.requireNonNull(terminal, "terminal must not be null");
            Objects.requireNonNull(what, "what must not be null");
        }

        public static Expectation success(String what, List<String> effects,
                                          String resultAtom) {
            return new Expectation(effects,
                new TerminalExpectation.SuccessWith(resultAtom), what);
        }

        public static Expectation failure(String what, List<String> effects, String code,
                                          String originText) {
            return new Expectation(effects,
                new TerminalExpectation.FailureWith(code, originText), what);
        }
    }

    /** The harness verdict: a real execution comparison report. */
    public record Verdict(
        boolean pass,
        String report,
        List<SemanticRuntimeModel.ConsumerRun> runs,
        List<String> failures
    ) {

        public Verdict {
            runs = List.copyOf(runs);
            failures = List.copyOf(failures);
        }
    }

    /**
     * Runs the three-consumer matrix over the validated unit/table and
     * returns the differential verdict.
     *
     * @param unit        the validated lowered module unit; non-null
     * @param table       the unit's produced block-membership table; non-null
     * @param expectation the seed's pinned expectation; non-null
     * @param workspace   an isolated workspace directory for the emitted
     *                    artifacts (created if absent); non-null
     * @return the verdict with the per-consumer comparison report
     */
    public static Verdict run(LoweredModuleUnit unit, StructuredBodyTable table,
                              Expectation expectation, Path workspace) {
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(expectation, "expectation must not be null");
        Objects.requireNonNull(workspace, "workspace must not be null");
        List<String> failures = new ArrayList<>();
        List<SemanticRuntimeModel.ConsumerRun> runs = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        report.append("== Differential matrix run: ").append(expectation.what())
            .append(" ==\n");

        // 1. The semantic oracle (in-process).
        SemanticRuntimeModel.ConsumerRun oracle = SemanticOracle.execute(unit, table);
        runs.add(oracle);
        report.append(oracle.comparisonReport()).append('\n');

        // 2. The shared LuaJIT emitter artifact.
        SemanticRuntimeModel.ConsumerRun lua = runLua(unit, table, workspace, failures);
        if (lua != null) {
            runs.add(lua);
            report.append(lua.comparisonReport()).append('\n');
        }

        // 3. The shared JVM emitter artifact.
        SemanticRuntimeModel.ConsumerRun jvm = runJvm(unit, table, workspace, failures);
        if (jvm != null) {
            runs.add(jvm);
            report.append(jvm.comparisonReport()).append('\n');
        }

        // Gate 1: three real per-consumer runs.
        if (runs.size() != 3) {
            failures.add("three-consumer gate: only " + runs.size()
                + " of 3 consumers produced a run — the matrix is not a "
                + "stubbed/partial run");
        }
        return verdictFrom(unit, expectation, runs, failures, report);
    }

    // =========================================================================
    // The host-driven callback surface (CALLBACK_INVOKE dispatch entries)
    // =========================================================================

    /** One scripted host argument of a callback invocation. */
    public sealed interface CallbackArg
        permits CallbackArg.Int, CallbackArg.Number, CallbackArg.Str, CallbackArg.Null {

        /** An int32 argument (the closed int carrier). */
        record Int(long value) implements CallbackArg {
        }

        /** A number argument. */
        record Number(double value) implements CallbackArg {
        }

        /** A string argument. */
        record Str(String value) implements CallbackArg {
        }

        /** A language-null argument. */
        record Null() implements CallbackArg {
        }
    }

    /**
     * Runs the three-consumer callback matrix: the semantic oracle's
     * {@code invokeCallback} surface, the shared LuaJIT artifact's
     * dispatch entry driven by a real top-level host script (the module
     * init walk suppressed under the callback-only drive flag), and the
     * shared JVM artifact's static dispatch entry driven by a real host
     * main — scripted arguments never synthesized traces.
     *
     * @param unit          the validated lowered module unit; non-null
     * @param table         the unit's produced block-membership table; non-null
     * @param functionValue the bound function value identity; non-null
     * @param args          the scripted host arguments; non-null
     * @param expectation   the seed's pinned expectation; non-null
     * @param workspace     an isolated workspace directory; non-null
     * @return the verdict with the per-consumer comparison report
     */
    public static Verdict runCallback(LoweredModuleUnit unit, StructuredBodyTable table,
                                      ValueId functionValue, List<CallbackArg> args,
                                      Expectation expectation, Path workspace) {
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(functionValue, "functionValue must not be null");
        Objects.requireNonNull(args, "args must not be null");
        Objects.requireNonNull(expectation, "expectation must not be null");
        Objects.requireNonNull(workspace, "workspace must not be null");
        List<String> failures = new ArrayList<>();
        List<SemanticRuntimeModel.ConsumerRun> runs = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        report.append("== Differential callback matrix run: ")
            .append(expectation.what()).append(" ==\n");

        // 1. The semantic oracle (in-process, top-level invocation).
        List<SemanticOracle.Value> oracleArgs = new ArrayList<>();
        for (CallbackArg arg : args) {
            oracleArgs.add(oracleValueOf(arg));
        }
        SemanticRuntimeModel.ConsumerRun oracle = SemanticOracle.invokeCallback(unit,
            table, functionValue, oracleArgs);
        runs.add(oracle);
        report.append(oracle.comparisonReport()).append('\n');

        // 2. The shared LuaJIT artifact's dispatch entry.
        SemanticRuntimeModel.ConsumerRun lua = runLuaCallback(unit, table, functionValue,
            args, workspace, failures);
        if (lua != null) {
            runs.add(lua);
            report.append(lua.comparisonReport()).append('\n');
        }

        // 3. The shared JVM artifact's dispatch entry.
        SemanticRuntimeModel.ConsumerRun jvm = runJvmCallback(unit, table, functionValue,
            args, workspace, failures);
        if (jvm != null) {
            runs.add(jvm);
            report.append(jvm.comparisonReport()).append('\n');
        }

        if (runs.size() != 3) {
            failures.add("three-consumer gate: only " + runs.size()
                + " of 3 consumers produced a run — the callback matrix is not a "
                + "stubbed/partial run");
        }
        return verdictFrom(unit, expectation, runs, failures, report);
    }

    /** One scripted host argument as the semantic oracle's value. */
    private static SemanticOracle.Value oracleValueOf(CallbackArg arg) {
        return switch (arg) {
            case CallbackArg.Int value -> new SemanticOracle.Value.IntValue(value.value());
            case CallbackArg.Number value -> new SemanticOracle.Value.NumValue(value.value());
            case CallbackArg.Str value -> new SemanticOracle.Value.StrValue(value.value());
            case CallbackArg.Null ignored -> SemanticOracle.Value.NullValue.INSTANCE;
        };
    }

    /** The dispatch entry name of one CALLBACK_INVOKE op (per-unit record). */
    private static String callbackEntryName(OpId opId) {
        return "cb" + opId.id();
    }

    /** The unit's CALLBACK_INVOKE op bound to the given function value. */
    private static SemanticOp callbackOpOf(LoweredModuleUnit unit, ValueId functionValue) {
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.CALLBACK_INVOKE
                    && ((KindPayload.CallbackInvokePayload) op.payload()).function()
                        .equals(functionValue)) {
                return op;
            }
        }
        return null;
    }

    /** The Lua literal of one scripted host argument. */
    private static String luaLiteralOf(CallbackArg arg) {
        return switch (arg) {
            case CallbackArg.Int value -> String.valueOf(value.value());
            case CallbackArg.Number value -> Double.toString(value.value());
            case CallbackArg.Str value -> quoteLua(value.value());
            case CallbackArg.Null ignored -> "nil";
        };
    }

    /** The JVM literal of one scripted host argument (the closed carriers). */
    private static String jvmLiteralOf(CallbackArg arg) {
        return switch (arg) {
            case CallbackArg.Int value -> value.value() + "L";
            case CallbackArg.Number value -> Double.toString(value.value()) + "d";
            case CallbackArg.Str value -> "\"" + value.value()
                .replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            case CallbackArg.Null ignored -> "null";
        };
    }

    /** One Lua string literal. */
    private static String quoteLua(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** The shared LuaJIT artifact's callback dispatch entry, host-driven. */
    private static SemanticRuntimeModel.ConsumerRun runLuaCallback(
            LoweredModuleUnit unit, StructuredBodyTable table, ValueId functionValue,
            List<CallbackArg> args, Path workspace, List<String> failures) {
        SemanticOp callback = callbackOpOf(unit, functionValue);
        if (callback == null) {
            failures.add("shared LuaJIT callback drive: the unit records no "
                + "CALLBACK_INVOKE for " + functionValue);
            return null;
        }
        try {
            Files.createDirectories(workspace);
            String lua = LuaSemanticEmitter.emitModule(unit, table);
            Path artifact = workspace.resolve(unit.moduleId().path().replace('/', '_')
                + "-cb.lua");
            Files.writeString(artifact, lua, StandardCharsets.UTF_8);
            StringBuilder argsText = new StringBuilder();
            for (CallbackArg arg : args) {
                if (argsText.length() > 0) {
                    argsText.append(", ");
                }
                argsText.append(luaLiteralOf(arg));
            }
            Path driver = workspace.resolve("lua-cb-driver.lua");
            Files.writeString(driver,
                "dofile(" + quoteLua(artifact.toAbsolutePath().toString()) + ")\n"
                    + "local __ok, __res = pcall(__callbacks["
                    + quoteLua(callbackEntryName(callback.opId())) + "], "
                    + argsText + ")\n"
                    + "if __ok then\n"
                    + "  io.stderr:write(\"R|success|\"..__callbacks.__hostAtom(__res)"
                    + "..\"\\n\")\n"
                    + "else\n"
                    + "  io.stderr:write(\"R|failure|\"..__callbacks.__errtext(__res)"
                    + "..\"\\n\")\n"
                    + "end\n"
                    + "io.stderr:flush()\n",
                StandardCharsets.UTF_8);
            Path stdout = workspace.resolve("lua-cb-out.txt");
            Path stderr = workspace.resolve("lua-cb-err.txt");
            ProcessBuilder builder = new ProcessBuilder("luajit",
                driver.toAbsolutePath().toString());
            builder.environment().put("DEAL_CALLBACK_ONLY", "1");
            builder.redirectOutput(stdout.toFile());
            builder.redirectError(stderr.toFile());
            Process process = builder.start();
            int exit = process.waitFor();
            List<String> stdoutLines = Files.readAllLines(stdout, StandardCharsets.UTF_8);
            List<String> protocolLines = Files.readAllLines(stderr, StandardCharsets.UTF_8);
            if (exit != 0) {
                failures.add("shared LuaJIT callback drive exited " + exit + ": "
                    + String.join(" / ", protocolLines));
                return null;
            }
            SemanticRuntimeModel.ConsumerRun run =
                decodeRun("shared-luajit", unit, protocolLines, failures);
            crossCheckStdout(run, stdoutLines, "shared-luajit", failures);
            return run;
        } catch (IOException | InterruptedException exception) {
            failures.add("shared LuaJIT callback infrastructure failure: "
                + exception.getMessage());
            return null;
        }
    }

    /** The shared JVM artifact's callback dispatch entry, host-driven. */
    private static SemanticRuntimeModel.ConsumerRun runJvmCallback(
            LoweredModuleUnit unit, StructuredBodyTable table, ValueId functionValue,
            List<CallbackArg> args, Path workspace, List<String> failures) {
        SemanticOp callback = callbackOpOf(unit, functionValue);
        if (callback == null) {
            failures.add("shared JVM callback drive: the unit records no "
                + "CALLBACK_INVOKE for " + functionValue);
            return null;
        }
        try {
            Files.createDirectories(workspace);
            JvmSemanticEmitter.EmissionResult emission =
                JvmSemanticEmitter.emitModule(unit, table);
            Path source = workspace.resolve(emission.className() + ".java");
            Files.writeString(source, emission.source(), StandardCharsets.UTF_8);
            Path classes = workspace.resolve("jvm-cb-classes");
            Files.createDirectories(classes);
            String classpath = System.getProperty("java.class.path", "");
            ProcessBuilder javac = new ProcessBuilder("javac", "--release", "25",
                "-proc:none", "-cp", classpath, "-d", classes.toString(),
                source.toAbsolutePath().toString());
            javac.redirectErrorStream(true);
            Process compile = javac.start();
            String compileOut = new String(compile.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            int compileExit = compile.waitFor();
            if (compileExit != 0) {
                failures.add("shared JVM callback artifact compilation failed ("
                    + compileExit + "): " + compileOut);
                return null;
            }
            KindPayload.CallbackInvokePayload payload =
                (KindPayload.CallbackInvokePayload) callback.payload();
            String returnKind = staticKindOf(payload.descriptor().returnType());
            StringBuilder argsText = new StringBuilder();
            for (CallbackArg arg : args) {
                if (argsText.length() > 0) {
                    argsText.append(", ");
                }
                argsText.append(jvmLiteralOf(arg));
            }
            String driverClass = emission.className() + "CallbackDriver";
            Path driver = workspace.resolve(driverClass + ".java");
            Files.writeString(driver,
                "public class " + driverClass + " {\n"
                    + "  public static void main(String[] args) {\n"
                    + "    try {\n"
                    + "      Object r = " + emission.className() + "."
                    + callbackEntryName(callback.opId()) + "(new Object[]{"
                    + argsText + "});\n"
                    + "      System.err.println(\"R|success|\" + "
                    + "deal.codegen.jvm.JvmRuntime.atom(r, \"" + returnKind
                    + "\"));\n"
                    + "    } catch (deal.codegen.jvm.JvmRuntime.DealError e) {\n"
                    + "      System.err.println(\"R|failure|\" + "
                    + "deal.codegen.jvm.JvmRuntime.errtext(e));\n"
                    + "    }\n"
                    + "    System.err.flush();\n"
                    + "  }\n"
                    + "}\n",
                StandardCharsets.UTF_8);
            ProcessBuilder driverJavac = new ProcessBuilder("javac", "--release", "25",
                "-proc:none", "-cp", classpath + java.io.File.pathSeparator + classes,
                "-d", classes.toString(), driver.toAbsolutePath().toString());
            driverJavac.redirectErrorStream(true);
            Process driverCompile = driverJavac.start();
            String driverOut = new String(driverCompile.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            int driverExit = driverCompile.waitFor();
            if (driverExit != 0) {
                failures.add("shared JVM callback driver compilation failed ("
                    + driverExit + "): " + driverOut);
                return null;
            }
            Path stdout = workspace.resolve("jvm-cb-out.txt");
            Path stderr = workspace.resolve("jvm-cb-err.txt");
            ProcessBuilder javaRun = new ProcessBuilder("java", "-cp",
                classpath + java.io.File.pathSeparator + classes, driverClass);
            javaRun.redirectOutput(stdout.toFile());
            javaRun.redirectError(stderr.toFile());
            Process run = javaRun.start();
            int exit = run.waitFor();
            List<String> stdoutLines = Files.readAllLines(stdout, StandardCharsets.UTF_8);
            List<String> protocolLines = Files.readAllLines(stderr, StandardCharsets.UTF_8);
            if (exit != 0) {
                failures.add("shared JVM callback drive exited " + exit + ": "
                    + String.join(" / ", protocolLines));
                return null;
            }
            SemanticRuntimeModel.ConsumerRun consumerRun =
                decodeRun("shared-jvm", unit, protocolLines, failures);
            crossCheckStdout(consumerRun, stdoutLines, "shared-jvm", failures);
            return consumerRun;
        } catch (IOException | InterruptedException exception) {
            failures.add("shared JVM callback infrastructure failure: "
                + exception.getMessage());
            return null;
        }
    }

    /** The closed static runtime kind of a descriptor (the emitter's map). */
    private static String staticKindOf(RuntimeDescriptor descriptor) {
        if (descriptor instanceof RuntimeDescriptor.Null) {
            return "null";
        }
        if (descriptor instanceof RuntimeDescriptor.Boolean) {
            return "bool";
        }
        if (descriptor instanceof RuntimeDescriptor.Int) {
            return "int";
        }
        if (descriptor instanceof RuntimeDescriptor.Number) {
            return "number";
        }
        if (descriptor instanceof RuntimeDescriptor.String) {
            return "string";
        }
        if (descriptor instanceof RuntimeDescriptor.Nullable nullable) {
            return "nullable:" + staticKindOf(nullable.inner());
        }
        return "ref";
    }

    /** Applies the verdict gates shared by the module and callback matrices. */
    private static Verdict verdictFrom(LoweredModuleUnit unit, Expectation expectation,
                                       List<SemanticRuntimeModel.ConsumerRun> runs,
                                       List<String> failures, StringBuilder report) {
        for (SemanticRuntimeModel.ConsumerRun run : runs) {
            if (run.trace().isEmpty()) {
                failures.add(run.consumer() + " produced no events (a hollow run)");
            }
        }

        // Gate 2: every event validates against the exact validated IR op.
        Map<OpId, SemanticOp> opsById = new HashMap<>();
        for (SemanticOp op : unit.ops()) {
            opsById.put(op.opId(), op);
        }
        for (SemanticRuntimeModel.ConsumerRun run : runs) {
            validateEvents(run, opsById, failures);
        }

        // Gate 3: START/terminal pairing per op and three-way trace equality.
        for (SemanticRuntimeModel.ConsumerRun run : runs) {
            checkPairing(run, failures);
        }
        compareTraces(runs, failures);

        // Gate 4: effect equality across consumers and against the projection.
        compareEffects(runs, expectation, failures);

        // Gate 5: terminal equality across consumers and against the projection.
        compareTerminals(runs, expectation, failures);

        boolean pass = failures.isEmpty();
        report.append("== Verdict: ").append(pass ? "PASS" : "FAIL").append(" ==\n");
        for (String failure : failures) {
            report.append("FAIL: ").append(failure).append('\n');
        }
        return new Verdict(pass, report.toString(), runs, failures);
    }

    // =========================================================================
    // Consumer runners
    // =========================================================================

    private static SemanticRuntimeModel.ConsumerRun runLua(LoweredModuleUnit unit,
                                                           StructuredBodyTable table,
                                                           Path workspace,
                                                           List<String> failures) {
        try {
            Files.createDirectories(workspace);
            String lua = LuaSemanticEmitter.emitModule(unit, table);
            Path script = workspace.resolve(unit.moduleId().path().replace('/', '_')
                + ".lua");
            Files.writeString(script, lua, StandardCharsets.UTF_8);
            Path stdout = workspace.resolve("lua-out.txt");
            Path stderr = workspace.resolve("lua-err.txt");
            ProcessBuilder builder = new ProcessBuilder("luajit",
                script.toAbsolutePath().toString());
            builder.redirectOutput(stdout.toFile());
            builder.redirectError(stderr.toFile());
            Process process = builder.start();
            int exit = process.waitFor();
            List<String> stdoutLines = Files.readAllLines(stdout, StandardCharsets.UTF_8);
            List<String> protocolLines = Files.readAllLines(stderr, StandardCharsets.UTF_8);
            if (exit != 0) {
                failures.add("shared LuaJIT artifact exited " + exit + ": "
                    + String.join(" / ", protocolLines));
                return null;
            }
            SemanticRuntimeModel.ConsumerRun run =
                decodeRun("shared-luajit", unit, protocolLines, failures);
            crossCheckStdout(run, stdoutLines, "shared-luajit", failures);
            return run;
        } catch (IOException | InterruptedException exception) {
            failures.add("shared LuaJIT infrastructure failure: " + exception.getMessage());
            return null;
        }
    }

    private static SemanticRuntimeModel.ConsumerRun runJvm(LoweredModuleUnit unit,
                                                           StructuredBodyTable table,
                                                           Path workspace,
                                                           List<String> failures) {
        try {
            Files.createDirectories(workspace);
            JvmSemanticEmitter.EmissionResult emission =
                JvmSemanticEmitter.emitModule(unit, table);
            Path source = workspace.resolve(emission.className() + ".java");
            Files.writeString(source, emission.source(), StandardCharsets.UTF_8);
            Path classes = workspace.resolve("jvm-classes");
            Files.createDirectories(classes);
            String classpath = System.getProperty("java.class.path", "");
            ProcessBuilder javac = new ProcessBuilder("javac", "--release", "25",
                "-proc:none", "-cp", classpath, "-d", classes.toString(),
                source.toAbsolutePath().toString());
            javac.redirectErrorStream(true);
            Process compile = javac.start();
            String compileOut = new String(compile.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            int compileExit = compile.waitFor();
            if (compileExit != 0) {
                failures.add("shared JVM artifact compilation failed ("
                    + compileExit + "): " + compileOut);
                return null;
            }
            Path stdout = workspace.resolve("jvm-out.txt");
            Path stderr = workspace.resolve("jvm-err.txt");
            ProcessBuilder javaRun = new ProcessBuilder("java", "-cp",
                classpath + java.io.File.pathSeparator + classes,
                emission.className());
            javaRun.redirectOutput(stdout.toFile());
            javaRun.redirectError(stderr.toFile());
            Process run = javaRun.start();
            int exit = run.waitFor();
            List<String> stdoutLines = Files.readAllLines(stdout, StandardCharsets.UTF_8);
            List<String> protocolLines = Files.readAllLines(stderr, StandardCharsets.UTF_8);
            if (exit != 0) {
                failures.add("shared JVM artifact exited " + exit + ": "
                    + String.join(" / ", protocolLines));
                return null;
            }
            SemanticRuntimeModel.ConsumerRun consumerRun =
                decodeRun("shared-jvm", unit, protocolLines, failures);
            crossCheckStdout(consumerRun, stdoutLines, "shared-jvm", failures);
            return consumerRun;
        } catch (IOException | InterruptedException exception) {
            failures.add("shared JVM infrastructure failure: " + exception.getMessage());
            return null;
        }
    }

    /** Decodes the artifact's protocol stream into a consumer run. */
    private static SemanticRuntimeModel.ConsumerRun decodeRun(
            String consumer, LoweredModuleUnit unit, List<String> lines,
            List<String> failures) {
        List<SemanticRuntimeModel.TraceEvent> trace = new ArrayList<>();
        List<SemanticRuntimeModel.EffectEvent> effects = new ArrayList<>();
        SemanticRuntimeModel.Terminal terminal = null;
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            try {
                Object decoded = SemanticTraceProtocol.decode(line);
                if (decoded instanceof SemanticRuntimeModel.TraceEvent event) {
                    trace.add(event);
                } else if (decoded instanceof SemanticRuntimeModel.EffectEvent effect) {
                    effects.add(effect);
                } else if (decoded instanceof SemanticRuntimeModel.Terminal term) {
                    if (terminal != null) {
                        failures.add(consumer + " published two terminals: " + line);
                    }
                    terminal = term;
                }
            } catch (RuntimeException exception) {
                failures.add(consumer + " protocol decode failure on line \"" + line
                    + "\": " + exception.getMessage());
            }
        }
        if (terminal == null) {
            failures.add(consumer + " published no terminal record");
            terminal = new SemanticRuntimeModel.Terminal.Success("null");
        }
        return new SemanticRuntimeModel.ConsumerRun(consumer, unit.moduleId().path(),
            unit.interfaceHash(), unit.loweringContextHash(), trace, effects, terminal,
            consumer + ": decoded " + trace.size() + " events, " + effects.size()
                + " effects");
    }

    /** Cross-checks the real stdout effect bytes against the recorded effects. */
    private static void crossCheckStdout(SemanticRuntimeModel.ConsumerRun run,
                                         List<String> stdoutLines, String consumer,
                                         List<String> failures) {
        List<String> recorded = new ArrayList<>();
        for (SemanticRuntimeModel.EffectEvent effect : run.effects()) {
            recorded.add(effect.text());
        }
        if (!recorded.equals(stdoutLines)) {
            failures.add(consumer + " stdout effect bytes " + stdoutLines
                + " do not equal its recorded effects " + recorded);
        }
    }

    // =========================================================================
    // Gates
    // =========================================================================

    /** Gate 2: event validation against the exact validated IR op. */
    private static void validateEvents(SemanticRuntimeModel.ConsumerRun run,
                                       Map<OpId, SemanticOp> opsById,
                                       List<String> failures) {
        for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
            SemanticOp op = opsById.get(event.op());
            if (op == null) {
                failures.add(run.consumer() + " event " + event.sequence()
                    + " names op " + event.op() + " absent from the validated unit");
                continue;
            }
            if (event.kind() != op.kind()) {
                failures.add(run.consumer() + " event " + event.sequence() + " kind "
                    + event.kind() + " != the validated op kind " + op.kind());
            }
            if (!event.contractDigest().equals(op.contract().canonicalDigest())) {
                failures.add(run.consumer() + " event " + event.sequence()
                    + " contract digest " + event.contractDigest()
                    + " != the validated operation digest "
                    + op.contract().canonicalDigest());
            }
            OpId expectedParent = op.origin().parentOpId();
            if (!Objects.equals(event.parentOp(), expectedParent)) {
                failures.add(run.consumer() + " event " + event.sequence()
                    + " parent " + event.parentOp() + " != the validated op's parent "
                    + expectedParent);
            }
            if (!event.module().equals(unitPathOf(op.opId()))) {
                failures.add(run.consumer() + " event " + event.sequence()
                    + " module " + event.module() + " != the op's module "
                    + unitPathOf(op.opId()));
            }
        }
    }

    private static String unitPathOf(OpId opId) {
        return opId.module().path();
    }

    /** Gate 3a: per op, START/terminal events alternate (shared bodies re-execute). */
    private static void checkPairing(SemanticRuntimeModel.ConsumerRun run,
                                     List<String> failures) {
        Map<OpId, Integer> starts = new HashMap<>();
        Map<OpId, Integer> terminals = new HashMap<>();
        for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
            if (event.phase() == SemanticRuntimeModel.Phase.START) {
                starts.merge(event.op(), 1, Integer::sum);
            } else {
                terminals.merge(event.op(), 1, Integer::sum);
            }
        }
        for (Map.Entry<OpId, Integer> entry : starts.entrySet()) {
            Integer terminalCount = terminals.get(entry.getKey());
            if (terminalCount == null || !terminalCount.equals(entry.getValue())) {
                failures.add(run.consumer() + " op " + entry.getKey() + " has "
                    + entry.getValue() + " START events but " + terminalCount
                    + " terminal events (each START needs exactly one terminal)");
            }
        }
        // Each START is followed by exactly one terminal before the next
        // START of the same op (a started operation ends exactly once).
        Map<OpId, Boolean> open = new HashMap<>();
        for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
            if (event.phase() == SemanticRuntimeModel.Phase.START) {
                Boolean wasOpen = open.put(event.op(), Boolean.TRUE);
                if (Boolean.TRUE.equals(wasOpen)) {
                    failures.add(run.consumer() + " op " + event.op()
                        + " starts again before its previous terminal");
                }
            } else {
                Boolean wasOpen = open.put(event.op(), Boolean.FALSE);
                if (!Boolean.TRUE.equals(wasOpen)) {
                    failures.add(run.consumer() + " op " + event.op()
                        + " terminates without an open START");
                }
            }
        }
    }

    /** Gate 3b: the three traces match event-for-event. */
    private static void compareTraces(List<SemanticRuntimeModel.ConsumerRun> runs,
                                      List<String> failures) {
        if (runs.size() < 2) {
            return;
        }
        SemanticRuntimeModel.ConsumerRun base = runs.get(0);
        for (int i = 1; i < runs.size(); i++) {
            SemanticRuntimeModel.ConsumerRun other = runs.get(i);
            if (base.trace().size() != other.trace().size()) {
                failures.add("trace length mismatch: " + base.consumer() + " has "
                    + base.trace().size() + " events, " + other.consumer() + " has "
                    + other.trace().size());
                continue;
            }
            for (int j = 0; j < base.trace().size(); j++) {
                String baseText = base.trace().get(j).text();
                String otherText = other.trace().get(j).text();
                if (!baseText.equals(otherText)) {
                    failures.add("trace event " + j + " mismatch: "
                        + base.consumer() + " [" + baseText + "] vs " + other.consumer()
                        + " [" + otherText + "]");
                }
            }
        }
    }

    /** Gate 4: effects across consumers and against the projection. */
    private static void compareEffects(List<SemanticRuntimeModel.ConsumerRun> runs,
                                       Expectation expectation, List<String> failures) {
        for (SemanticRuntimeModel.ConsumerRun run : runs) {
            List<String> effects = new ArrayList<>();
            for (SemanticRuntimeModel.EffectEvent effect : run.effects()) {
                effects.add(effect.text());
            }
            if (!effects.equals(expectation.effects())) {
                failures.add(run.consumer() + " effects " + effects
                    + " != the pinned projection " + expectation.effects());
            }
        }
        if (runs.size() >= 2) {
            for (int i = 1; i < runs.size(); i++) {
                if (!runs.get(0).effects().equals(runs.get(i).effects())) {
                    failures.add("effect mismatch: " + runs.get(0).consumer() + " "
                        + runs.get(0).effects() + " vs " + runs.get(i).consumer() + " "
                        + runs.get(i).effects());
                }
            }
        }
    }

    /** Gate 5: terminals across consumers and against the projection. */
    private static void compareTerminals(List<SemanticRuntimeModel.ConsumerRun> runs,
                                         Expectation expectation, List<String> failures) {
        for (SemanticRuntimeModel.ConsumerRun run : runs) {
            switch (expectation.terminal()) {
                case TerminalExpectation.SuccessWith success -> {
                    if (run.terminal() instanceof
                            SemanticRuntimeModel.Terminal.Success terminal
                            && success.resultAtom().equals(terminal.resultAtom())) {
                        // matches
                    } else {
                        failures.add(run.consumer() + " terminal " + terminalText(
                            run.terminal()) + " != the pinned success projection "
                            + success.resultAtom());
                    }
                }
                case TerminalExpectation.FailureWith failure -> {
                    if (run.terminal() instanceof
                            SemanticRuntimeModel.Terminal.DealFailure terminal
                            && failure.code().equals(terminal.error().code())
                            && (failure.originText() == null
                                || failure.originText().equals(terminal.error().origin()))) {
                        // matches
                    } else {
                        failures.add(run.consumer() + " terminal " + terminalText(
                            run.terminal()) + " != the pinned failure projection "
                            + failure.code() + " at " + failure.originText());
                    }
                }
            }
        }
        if (runs.size() >= 2) {
            String base = terminalText(runs.get(0).terminal());
            for (int i = 1; i < runs.size(); i++) {
                if (!base.equals(terminalText(runs.get(i).terminal()))) {
                    failures.add("terminal mismatch: " + runs.get(0).consumer() + " ["
                        + base + "] vs " + runs.get(i).consumer() + " ["
                        + terminalText(runs.get(i).terminal()) + "]");
                }
            }
        }
    }

    private static String terminalText(SemanticRuntimeModel.Terminal terminal) {
        return switch (terminal) {
            case SemanticRuntimeModel.Terminal.Success success ->
                "success(" + success.resultAtom() + ")";
            case SemanticRuntimeModel.Terminal.DealFailure failure ->
                "failure(" + failure.error().code() + "@" + failure.error().origin() + ")";
        };
    }
}
