package deal.diagnostics;

import java.util.Objects;

/** Bounded exact text from the producer's source snapshot, never a formatting-time file read. */
public record DiagnosticExcerpt(String text, int startScalarOffset, int endScalarOffset,
                                int sourceLength, int omittedBefore, int omittedAfter,
                                boolean primaryTruncated) {
    public static final int MAX_UNITS = 240;

    public DiagnosticExcerpt {
        Objects.requireNonNull(text, "text");
        if (startScalarOffset < 0 || endScalarOffset < startScalarOffset || endScalarOffset > sourceLength
                || text.length() != endScalarOffset - startScalarOffset || text.length() > MAX_UNITS
                || omittedBefore != startScalarOffset || omittedAfter != sourceLength - endScalarOffset)
            throw new IllegalArgumentException("Invalid source excerpt geometry");
    }

    /** Offsets and lengths are UTF-16 units. Unavailable/synthetic/foreign geometry stays absent. */
    public static DiagnosticExcerpt fromSource(String file, String source, DiagnosticRange range) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(source, "source");
        if (range == null || range.origin() != RangeOrigin.SOURCE || !file.equals(range.file())
                || range.startScalarOffset() < 0 || range.endScalarOffset() < range.startScalarOffset()
                || range.endScalarOffset() > source.length()) return null;
        int start = Math.max(0, range.startScalarOffset() - 80);
        // Do not cut a valid surrogate pair or CRLF at either boundary.
        if (splits(source, start)) start++;
        int end = (int) Math.min((long) source.length(), (long) start + MAX_UNITS);
        if (splits(source, end)) end--;
        return new DiagnosticExcerpt(source.substring(start, end), start, end, source.length(),
                start, source.length() - end, range.startScalarOffset() < start || range.endScalarOffset() > end);
    }

    private static boolean splits(String source, int offset) {
        if (offset <= 0 || offset >= source.length()) return false;
        char before = source.charAt(offset - 1), after = source.charAt(offset);
        return Character.isHighSurrogate(before) && Character.isLowSurrogate(after)
                || before == '\r' && after == '\n';
    }
}
