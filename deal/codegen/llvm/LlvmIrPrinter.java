package deal.codegen.llvm;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The textual LLVM IR printer ({@code llvm-native-codegen-cfg-ssa}
 * Architecture, {@code LlvmIrPrinter}): the one spelling authority for
 * constants, escapes, indentation and line assembly of the emitted module.
 *
 * <p>Every byte the emitter writes goes through this class, so determinism
 * (C9) has exactly one owner: a string constant is a byte array rendered as
 * the LLVM {@code c"..."} escape form, a binary64 constant is rendered as the
 * hexadecimal float literal that preserves its bits (including the signed
 * zero and the infinities), and no host-formatting locale, timestamp or path
 * can reach the output.</p>
 */
public final class LlvmIrPrinter {

    private LlvmIrPrinter() {
        // Static printer only; no instances.
    }

    /** The indentation unit of one nesting level. */
    public static final String INDENT = "  ";

    /** The indentation of one nesting level, repeated {@code depth} times. */
    public static String indent(int depth) {
        return INDENT.repeat(Math.max(0, depth));
    }

    /**
     * The body of one LLVM string constant: printable ASCII stays as-is
     * except the quote and the backslash, which LLVM spells as two hex
     * digits; every other byte is two uppercase hex digits. The trailing NUL
     * is the caller's business (a C string constant carries exactly one more
     * byte than its text).
     */
    public static String cString(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) {
            int unsigned = value & 0xFF;
            if (unsigned >= 0x20 && unsigned <= 0x7E
                    && unsigned != '"' && unsigned != '\\') {
                text.append((char) unsigned);
            } else {
                text.append(String.format("%02X", unsigned));
            }
        }
        return text.toString();
    }

    /** The UTF-8 bytes of one text. */
    public static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The textual IR spelling of one binary64 value: its exact 64-bit
     * hexadecimal form, so every bit (the signed zero and the non-finite
     * values included) survives verbatim, two emissions of one IR fact are
     * byte-identical (C9), and no value is silently rounded (C6).
     */
    public static String doubleLiteral(double value) {
        // The 64-bit hexadecimal form is LLVM's exact binary64 literal: it
        // preserves every bit (the signed zero and the non-finite values
        // included) and no decimal conversion can round it.
        return String.format("0x%016X", Double.doubleToRawLongBits(value));
    }

    /**
     * The {@code i8*}-typed constant expression of one global string array
     * at element zero: the single spelling every emitted C-string reference
     * uses.
     */
    public static String stringConstant(String globalName, int length) {
        return "i8* getelementptr ([" + length + " x i8], ["
            + length + " x i8]* @" + globalName + ", i64 0, i64 0)";
    }

    /**
     * The {@code i8*}-typed constant expression of one global string array
     * at element {@code offset}.
     */
    public static String stringConstant(String globalName, int length,
                                        long offset) {
        return "i8* getelementptr ([" + length + " x i8], ["
            + length + " x i8]* @" + globalName + ", i64 0, i64 "
            + offset + ")";
    }

    /** An IR string literal for metadata and attribute text. */
    public static String metadataString(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for (byte value : utf8(text)) {
            int unsigned = value & 0xFF;
            if (unsigned == '"' || unsigned == '\\') {
                quoted.append('\\').append((char) unsigned);
            } else if (unsigned < 0x20 || unsigned > 0x7E) {
                quoted.append('\\').append(String.format("%02X", unsigned));
            } else {
                quoted.append((char) unsigned);
            }
        }
        return quoted.append('"').toString();
    }

    /** The indented single-line body of one emitted section. */
    public static String joined(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        return text.toString();
    }
}
