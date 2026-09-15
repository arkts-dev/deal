package deal.semantic;

import deal.semantic.ir.OpId;
import deal.semantic.ir.SemanticOpKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The shared runtime-observation model of the decomposition-tail
 * integration verification (ISSUE-0410): the canonical atom text,
 * the closed runtime-value kinds, the semantic trace event, the ordered
 * effect record, the canonical error snapshot, and the consumer run
 * report. The semantic oracle and both shared emitters produce this
 * model from their own target representations; the differential harness
 * compares the three reports event-for-event (semantic-lowering-
 * differential-conformance D2/D7: every event validates against its
 * exact validated IR operation — snapshot digest, {@code parentOpId}
 * nesting — before ordinary three-way comparison).
 *
 * <p><b>Atom text (canonical, representation-independent).</b> Every
 * observable value in a trace event or effect is one exact atom text:</p>
 *
 * <pre>{@code
 * null                    the language null
 * missing                 an internal missing (past-end array read)
 * bool:true | bool:false  boolean values
 * int:&lt;signed32&gt;         signed32 int values
 * num:&lt;ieee754Hex&gt;       IEEE-754 values via the canonical hex-float spelling
 *                          ({@code CanonicalJson.numberHex}); NaN → "num:nan"
 * str:&lt;escaped&gt;          Unicode scalar strings (\\, \n, \t, and non-printables escaped)
 * ref:&lt;allocId&gt;          an allocation (array/table/function) identity
 * err:&lt;code&gt;:&lt;escaped message&gt;   an Error value's code/message
 * }</pre>
 *
 * <p>Allocation ids are assigned by each consumer's runtime allocation
 * counter in allocation order — identical execution produces identical
 * id sequences, so the three reports compare exactly. The harness
 * additionally re-binds refs by first observation as a normalization
 * safety net (conformance D3's run-wide namespace rule).</p>
 *
 * <p><b>Error snapshots.</b> A DEAL failure is projected as
 * {@link ErrorSnapshot} — code, canonical message, origin
 * ({@code sourceId:line:column}), expected/actual (when the registry row
 * attains them), active frames innermost-first, and the nested cause —
 * exactly the observable projection of the closed failure registry.
 * The canonical message text is the registry-instantiated template; the
 * emitters realize the same templates over their target
 * representations (never invented message text).</p>
 *
 * <p><b>Effects.</b> The closed effect for this domain is the console
 * write ({@link EffectEvent.Kind#CONSOLE_WRITE}): exact scalar text plus
 * channel, in run order. A consumer records one {@link EffectEvent} per
 * executed {@code STDLIB_CALL(CONSOLE_LOG/CONSOLE_ERROR)} terminal.</p>
 */
public final class SemanticRuntimeModel {

    private SemanticRuntimeModel() {
    }

    /** The closed runtime-value kinds observable in this domain. */
    public enum RuntimeValueKind {
        NULL, MISSING, BOOLEAN, INT, NUMBER, STRING, TABLE, ARRAY, FUNCTION, ERROR
    }

    /** Escapes a string into canonical scalar text (UTF-8-safe). */
    public static String escapeString(String text) {
        Objects.requireNonNull(text, "text must not be null");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                case ';' -> out.append("\\u003b");
                case '|' -> out.append("\\u007c");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Unescapes canonical scalar text back to the string value. */
    public static String unescapeString(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                switch (next) {
                    case '\\' -> {
                        out.append('\\');
                        i++;
                    }
                    case 'n' -> {
                        out.append('\n');
                        i++;
                    }
                    case 't' -> {
                        out.append('\t');
                        i++;
                    }
                    case 'r' -> {
                        out.append('\r');
                        i++;
                    }
                    case 'u' -> {
                        if (i + 5 < text.length()) {
                            out.append((char) Integer.parseInt(
                                text.substring(i + 2, i + 6), 16));
                            i += 5;
                        } else {
                            out.append(c);
                        }
                    }
                    default -> out.append(c);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The canonical number atom (IEEE-754 hex; NaN normalizes to {@code num:nan}). */
    public static String numberAtom(double value) {
        if (Double.isNaN(value)) {
            return "num:nan";
        }
        return "num:" + deal.semantic.ir.CanonicalJson.numberHex(value);
    }

    /** The canonical signed32 int atom. */
    public static String intAtom(long value) {
        if (value < -2147483648L || value > 2147483647L) {
            throw new IllegalArgumentException("int atom outside signed32: " + value);
        }
        return "int:" + value;
    }

    /**
     * The canonical origin text ({@code sourceId:line:column}); line and
     * column are 1-based. {@code null} origin yields {@code "-"}.
     */
    public static String originAtom(String sourceId, Integer line, Integer column) {
        if (sourceId == null || line == null || column == null) {
            return "-";
        }
        return sourceId + ":" + line + ":" + column;
    }

    // =========================================================================
    // Trace events
    // =========================================================================

    /** The closed trace phases. */
    public enum Phase { START, SUCCESS, FAILURE }

    /**
     * One semantic trace event: the validated operation's identity
     * (module, op, structural parent), the phase, the operation-contract
     * digest (recomputable from the exact IR op), and the phase payload —
     * completed operand atoms on START, the result atom on SUCCESS, and
     * the exact error snapshot on FAILURE. Every event must validate
     * against the exact validated IR operation before comparison
     * (conformance D2).
     */
    public record TraceEvent(
        long sequence,
        String module,
        OpId op,
        OpId parentOp,
        SemanticOpKind kind,
        Phase phase,
        String contractDigest,
        List<String> inputs,
        String output,
        ErrorSnapshot error
    ) {

        public TraceEvent {
            Objects.requireNonNull(module, "module must not be null");
            Objects.requireNonNull(op, "op must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(phase, "phase must not be null");
            Objects.requireNonNull(contractDigest, "contractDigest must not be null");
            inputs = List.copyOf(inputs);
        }

        /** The canonical one-line text of this event (the comparison form). */
        public String text() {
            StringBuilder out = new StringBuilder();
            out.append(sequence).append('|').append(module).append('|')
                .append(op.module().path()).append('#').append(op.id())
                .append('|').append(phase).append('|').append(kind)
                .append('|').append(contractDigest);
            if (parentOp != null) {
                out.append('|').append(parentOp.module().path()).append('#')
                    .append(parentOp.id());
            } else {
                out.append("|-");
            }
            for (String input : inputs) {
                out.append('|').append(input);
            }
            if (output != null) {
                out.append("|=>").append(output);
            }
            if (error != null) {
                out.append("|!").append(error.text());
            }
            return out.toString();
        }
    }

    /** One ordered external effect of the closed effect protocol. */
    public record EffectEvent(Kind kind, String text) {

        public enum Kind {
            /** A console write (the stdlib console algorithm's ordered effect). */
            CONSOLE_WRITE,
            /** A host call request (the E7 host seam; callee discriminator text). */
            HOST_CALL,
            /** A host call terminal: the returned value (atom text). */
            HOST_RETURN,
            /** A host call terminal: the thrown host error (code text). */
            HOST_THROW,
            /** An async host start (operation-label text). */
            ASYNC_START_OP,
            /** An async host completion by value (atom text). */
            ASYNC_COMPLETE_RETURN,
            /** An async host completion by throw (code text). */
            ASYNC_COMPLETE_THROW
        }

        public EffectEvent {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(text, "text must not be null");
        }

        /** The canonical one-line text of this effect. */
        public String textLine() {
            return kind + ":" + text;
        }
    }

    /**
     * The canonical DEAL error snapshot: code, canonical message, origin,
     * attained expected/actual kind texts, active frames innermost-first,
     * and the nested cause ({@code null} when none).
     */
    public record ErrorSnapshot(
        String code,
        String message,
        String origin,
        String expected,
        String actual,
        List<String> frames,
        ErrorSnapshot cause
    ) {

        public ErrorSnapshot {
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(message, "message must not be null");
            frames = List.copyOf(frames);
        }

        /** The canonical nested text form. */
        public String text() {
            StringBuilder out = new StringBuilder();
            out.append(code).append(';').append(escapeString(message))
                .append(';').append(origin == null ? "-" : origin)
                .append(';').append(expected == null ? "-" : expected)
                .append(';').append(actual == null ? "-" : actual);
            out.append(';').append(frames.isEmpty() ? "-" : String.join(",", frames));
            out.append(';').append(cause == null ? "-" : cause.text());
            return out.toString();
        }

        /** The host-visible projection line (uncaught errors). */
        public String projectionLine() {
            StringBuilder out = new StringBuilder();
            out.append(code).append(": ").append(message);
            if (origin != null && !"-".equals(origin)) {
                out.append(" at ").append(origin);
            }
            return out.toString();
        }
    }

    /** The closed terminal of a consumer run. */
    public sealed interface Terminal {

        /** The run succeeded; the entry result atom is published. */
        record Success(String resultAtom) implements Terminal {
            public Success {
                Objects.requireNonNull(resultAtom, "resultAtom must not be null");
            }
        }

        /** The run failed with the exact DEAL error snapshot. */
        record DealFailure(ErrorSnapshot error) implements Terminal {
            public DealFailure {
                Objects.requireNonNull(error, "error must not be null");
            }
        }
    }

    /**
     * One consumer's complete run report: the validated IR the run
     * consumed (unit identity facts), the contiguous semantic trace, the
     * ordered effects, and the terminal.
     */
    public record ConsumerRun(
        String consumer,
        String module,
        String interfaceHash,
        String loweringContextHash,
        List<TraceEvent> trace,
        List<EffectEvent> effects,
        Terminal terminal,
        String report
    ) {

        public ConsumerRun {
            Objects.requireNonNull(consumer, "consumer must not be null");
            Objects.requireNonNull(module, "module must not be null");
            trace = List.copyOf(trace);
            effects = List.copyOf(effects);
            Objects.requireNonNull(terminal, "terminal must not be null");
            report = report == null ? "" : report;
        }

        /** Builds the per-consumer execution comparison report text. */
        public String comparisonReport() {
            StringBuilder out = new StringBuilder();
            out.append(consumer).append(": ").append(module).append(" events=")
                .append(trace.size()).append(" effects=").append(effects.size())
                .append(" terminal=");
            if (terminal instanceof Terminal.Success success) {
                out.append("success(").append(success.resultAtom()).append(')');
            } else if (terminal instanceof Terminal.DealFailure failure) {
                out.append("deal-failure(").append(failure.error().projectionLine())
                    .append(')');
            }
            return out.toString();
        }

        /** Collects all event lines into a deterministic list (reporting). */
        public List<String> lines() {
            List<String> lines = new ArrayList<>();
            for (TraceEvent event : trace) {
                lines.add(event.text());
            }
            for (EffectEvent effect : effects) {
                lines.add("effect|" + effect.textLine());
            }
            return lines;
        }
    }
}
