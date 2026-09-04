package deal.semantic;

import deal.semantic.ir.OpId;
import deal.semantic.ir.SemanticOpKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The decomposition-tail trace/effect/terminal line protocol: the single
 * transport the shared emitters' real artifacts use to publish their
 * execution reports and the differential harness uses to decode them.
 * The semantic oracle produces {@link SemanticRuntimeModel} records
 * directly; the shared LuaJIT artifact re-implements this encoding in its
 * emitted prelude (byte-identical output), and the shared JVM artifact
 * calls these helpers at runtime over its own value model.
 *
 * <p><b>Line grammar (one record per line, {@code |}-separated; every
 * embedded text is scalar-escaped — {@code \\}, {@code \n}, {@code \t},
 * {@code \r}, {@code ;}, {@code |}, and control characters — so parsing
 * is unambiguous):</b></p>
 *
 * <pre>{@code
 * T|&lt;seq&gt;|&lt;module&gt;|&lt;opIdText&gt;|&lt;phase&gt;|&lt;kind&gt;|&lt;digest&gt;|&lt;parentText&gt;
 *   |&lt;input&gt;...|=&gt;&lt;output&gt;|!&lt;error&gt;
 * F|CONSOLE_WRITE|&lt;escaped text&gt;
 * R|success|&lt;resultAtom&gt;      or   R|failure|&lt;error&gt;
 * }</pre>
 *
 * <p>{@code opIdText}/{@code parentText} are {@code module#id};
 * {@code -} marks an absent parent. {@code &lt;error&gt;} is the nested
 * {@link SemanticRuntimeModel.ErrorSnapshot#text()} form. Effects and the
 * terminal publish on the same stream as the trace (the dedicated trace
 * channel — never program output), so the global interleaving is exact;
 * the artifact additionally writes real console effect bytes to its
 * program output, which the harness cross-checks against the recorded
 * effects.</p>
 */
public final class SemanticTraceProtocol {

    private SemanticTraceProtocol() {
    }

    /** The line prefix of a trace event record. */
    public static final String EVENT_PREFIX = "T|";

    /** The line prefix of an effect record. */
    public static final String EFFECT_PREFIX = "F|";

    /** The line prefix of the terminal record. */
    public static final String TERMINAL_PREFIX = "R|";

    /** Encodes one trace event line (the canonical comparison form). */
    public static String encodeEvent(SemanticRuntimeModel.TraceEvent event) {
        return EVENT_PREFIX + event.text();
    }

    /** Encodes one effect line. */
    public static String encodeEffect(SemanticRuntimeModel.EffectEvent effect) {
        return EFFECT_PREFIX + effect.kind() + "|" + SemanticRuntimeModel.escapeString(
            effect.text());
    }

    /** Encodes the terminal line. */
    public static String encodeTerminal(SemanticRuntimeModel.Terminal terminal) {
        return switch (terminal) {
            case SemanticRuntimeModel.Terminal.Success success ->
                TERMINAL_PREFIX + "success|" + success.resultAtom();
            case SemanticRuntimeModel.Terminal.DealFailure failure ->
                TERMINAL_PREFIX + "failure|" + failure.error().text();
        };
    }

    /** Decodes one protocol line into an event, an effect, or the terminal. */
    public static Object decode(String line) {
        Objects.requireNonNull(line, "line must not be null");
        if (line.startsWith(EVENT_PREFIX)) {
            return decodeEvent(line.substring(EVENT_PREFIX.length()));
        }
        if (line.startsWith(EFFECT_PREFIX)) {
            return decodeEffect(line.substring(EFFECT_PREFIX.length()));
        }
        if (line.startsWith(TERMINAL_PREFIX)) {
            return decodeTerminal(line.substring(TERMINAL_PREFIX.length()));
        }
        throw new IllegalArgumentException("unrecognized protocol line: " + line);
    }

    /** Decodes one trace event line back into a {@link SemanticRuntimeModel.TraceEvent}. */
    public static SemanticRuntimeModel.TraceEvent decodeEvent(String body) {
        String[] parts = body.split("\\|", -1);
        if (parts.length < 7) {
            throw new IllegalArgumentException("short event line: " + body);
        }
        long sequence = Long.parseLong(parts[0]);
        String module = parts[1];
        OpId op = parseOpId(parts[2]);
        SemanticRuntimeModel.Phase phase =
            SemanticRuntimeModel.Phase.valueOf(parts[3]);
        SemanticOpKind kind = SemanticOpKind.valueOf(parts[4]);
        String digest = parts[5];
        OpId parent = "-".equals(parts[6]) ? null : parseOpId(parts[6]);
        List<String> inputs = new ArrayList<>();
        String output = null;
        SemanticRuntimeModel.ErrorSnapshot error = null;
        for (int i = 7; i < parts.length; i++) {
            String part = parts[i];
            if (part.startsWith("=>")) {
                output = part.substring(2);
            } else if (part.startsWith("!")) {
                error = decodeError(part.substring(1));
            } else {
                inputs.add(part);
            }
        }
        return new SemanticRuntimeModel.TraceEvent(sequence, module, op, parent, kind,
            phase, digest, inputs, output, error);
    }

    /** Decodes one effect line. */
    public static SemanticRuntimeModel.EffectEvent decodeEffect(String body) {
        String[] parts = body.split("\\|", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("malformed effect line: " + body);
        }
        return new SemanticRuntimeModel.EffectEvent(
            SemanticRuntimeModel.EffectEvent.Kind.valueOf(parts[0]),
            SemanticRuntimeModel.unescapeString(parts[1]));
    }

    /** Decodes the terminal line. */
    public static SemanticRuntimeModel.Terminal decodeTerminal(String body) {
        int split = body.indexOf('|');
        String kind = split < 0 ? body : body.substring(0, split);
        String rest = split < 0 ? "" : body.substring(split + 1);
        return switch (kind) {
            case "success" -> new SemanticRuntimeModel.Terminal.Success(rest);
            case "failure" -> new SemanticRuntimeModel.Terminal.DealFailure(
                decodeError(rest));
            default -> throw new IllegalArgumentException("unknown terminal kind " + kind);
        };
    }

    /** Decodes the nested error text form (the cause field is the remainder). */
    public static SemanticRuntimeModel.ErrorSnapshot decodeError(String text) {
        String[] parts = text.split(";", 7);
        if (parts.length != 7) {
            throw new IllegalArgumentException("malformed error text: " + text);
        }
        String code = parts[0];
        String message = SemanticRuntimeModel.unescapeString(parts[1]);
        String origin = "-".equals(parts[2]) ? null : parts[2];
        String expected = "-".equals(parts[3]) ? null : parts[3];
        String actual = "-".equals(parts[4]) ? null : parts[4];
        List<String> frames = "-".equals(parts[5])
            ? List.of() : List.of(parts[5].split(","));
        SemanticRuntimeModel.ErrorSnapshot cause = "-".equals(parts[6])
            ? null : decodeError(parts[6]);
        return new SemanticRuntimeModel.ErrorSnapshot(code, message, origin, expected,
            actual, frames, cause);
    }

    /** Parses {@code module#id} into an {@link OpId}. */
    public static OpId parseOpId(String text) {
        int hash = text.lastIndexOf('#');
        if (hash < 0) {
            throw new IllegalArgumentException("malformed op id text " + text);
        }
        return new OpId(new deal.semantic.ir.ModuleId(text.substring(0, hash)),
            Long.parseLong(text.substring(hash + 1)));
    }

    /** The canonical {@code module#id} text of an {@link OpId}. */
    public static String opIdText(OpId opId) {
        return opId.module().path() + "#" + opId.id();
    }
}
