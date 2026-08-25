package deal.diagnostics;

import deal.ast.Span;
import deal.lexer.Token;

import java.util.ArrayList;
import java.util.List;

/**
 * A compiler diagnostic carrying a complete immutable range.
 *
 * <p>Carrier contract (D1):
 * {@code CompilerDiagnostic(code, severity, message, range, notes,
 * diagnosticCode)}. The compatibility accessors {@link #file()},
 * {@link #line()}, and {@link #column()} derive from the range start;
 * {@link #code()}, {@link #severity()}, {@link #message()}, and
 * {@link #diagnosticCode()} are the record components.
 * {@link #toString()} delegates to the canonical formatter
 * {@link DiagnosticFormatter#format(CompilerDiagnostic)} (D7/D8).</p>
 *
 * <p>The compact constructor implements every D9 normalization rule and
 * never throws for range, path, or encoding defects:
 * <ul>
 *   <li>a null range or a null range file falls back to the canonical
 *       synthetic shape with the empty file;</li>
 *   <li>a range whose scalar offsets are UNKNOWN (negative), or whose
 *       {@code endScalarOffset < startScalarOffset}, normalizes to the
 *       canonical synthetic shape {@code (file,1,1,1,1,0,0,0,SYNTHETIC)}
 *       — never clamped in place as SOURCE;</li>
 *   <li>a range with valid offsets but non-positive or inverted
 *       line/column pairs collapses its end onto the (clamped) start — a
 *       zero-length SOURCE range at the computed start offset (a
 *       non-positive start clamps to line 1 / column 1 while keeping its
 *       computed start offset);</li>
 *   <li>a scalar length that differs from
 *       {@code endScalarOffset - startScalarOffset} recomputes from the
 *       offsets;</li>
 *   <li>a SYNTHETIC-origin range that is not the canonical shape
 *       normalizes to the canonical shape;</li>
 *   <li>a non-null, non-empty severity outside {@code "error"} /
 *       {@code "warning"} becomes {@code "error"}.</li>
 * </ul>
 * Every correction appends a message-only note
 * {@code internal range defect: <reason>} and keeps the originating code,
 * severity, and message. A null or empty code, severity, or message
 * remains an immediate programmer error ({@link IllegalArgumentException}).
 * Notes are copied with {@link List#copyOf}; a null notes list becomes an
 * empty list. {@code diagnosticCode} may be null for test-only pseudo
 * codes.</p>
 */
public record CompilerDiagnostic(
    String code,
    String severity,
    String message,
    DiagnosticRange range,
    List<DiagnosticNote> notes,
    DiagnosticCode diagnosticCode
) {

    public CompilerDiagnostic {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("code must not be null or empty");
        }
        if (severity == null || severity.isEmpty()) {
            throw new IllegalArgumentException("severity must not be null or empty");
        }
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("message must not be null or empty");
        }

        List<DiagnosticNote> normalizedNotes =
            notes == null ? new ArrayList<>() : new ArrayList<>(notes);
        DiagnosticRange r = range;

        if (r == null) {
            // Defect: no range at all -> canonical synthetic shape with no
            // known file.
            r = DiagnosticRange.synthetic("");
            normalizedNotes.add(defect("null range"));
        } else if (r.file() == null) {
            // Defect: null path -> canonical synthetic shape with no known
            // file.
            r = DiagnosticRange.synthetic("");
            normalizedNotes.add(defect("null range file"));
        } else if (r.origin() == null) {
            r = DiagnosticRange.synthetic(r.file());
            normalizedNotes.add(defect("null origin"));
        } else if (r.origin() == RangeOrigin.SYNTHETIC && !r.isCanonicalSynthetic()) {
            // A synthetic range that is not the canonical zero-length shape
            // normalizes to the canonical shape.
            r = DiagnosticRange.synthetic(r.file());
            normalizedNotes.add(defect("non-canonical synthetic range"));
        } else if (r.startScalarOffset() < 0 || r.endScalarOffset() < 0) {
            // UNKNOWN (negative) offsets can never stay SOURCE: normalize
            // to the canonical synthetic shape, never a SOURCE (1,1) clamp.
            r = DiagnosticRange.synthetic(r.file());
            normalizedNotes.add(defect("unknown or negative scalar offsets"));
        } else if (r.endScalarOffset() < r.startScalarOffset()) {
            // Inverted offsets can never stay SOURCE either.
            r = DiagnosticRange.synthetic(r.file());
            normalizedNotes.add(defect("inverted scalar offsets"));
        } else {
            boolean invalidPositions = r.startLine() < 1 || r.startColumn() < 1
                || r.endLine() < 1 || r.endColumn() < 1
                || r.endLine() < r.startLine()
                || (r.endLine() == r.startLine() && r.endColumn() < r.startColumn());
            if (invalidPositions) {
                // Valid offsets with non-positive or inverted line/column
                // pairs: collapse the end onto the (clamped) start — a
                // zero-length SOURCE range at the computed start offset.
                int sLine = Math.max(1, r.startLine());
                int sCol = Math.max(1, r.startColumn());
                r = new DiagnosticRange(r.file(), sLine, sCol, sLine, sCol,
                    r.startScalarOffset(), r.startScalarOffset(), 0, r.origin());
                normalizedNotes.add(defect("invalid line or column positions"));
            }
            int computedLength = r.endScalarOffset() - r.startScalarOffset();
            if (r.scalarLength() != computedLength) {
                r = new DiagnosticRange(r.file(), r.startLine(), r.startColumn(),
                    r.endLine(), r.endColumn(), r.startScalarOffset(),
                    r.endScalarOffset(), computedLength, r.origin());
                normalizedNotes.add(defect("scalar length mismatch"));
            }
        }

        String normalizedSeverity = severity;
        if (!normalizedSeverity.equals("error") && !normalizedSeverity.equals("warning")) {
            normalizedSeverity = "error";
            normalizedNotes.add(defect("unknown severity"));
        }

        // Reassign the constructor parameters; the compact constructor
        // assigns them to the record components afterwards.
        severity = normalizedSeverity;
        range = r;
        notes = List.copyOf(normalizedNotes);
    }

    // =========================================================================
    // Canonical factories (D5)
    // =========================================================================

    /** Creates an error diagnostic from a registered code and a range. */
    public static CompilerDiagnostic error(DiagnosticCode dCode, String message,
                                           DiagnosticRange range) {
        return new CompilerDiagnostic(requireCode(dCode).code(), "error", message,
            range, null, dCode);
    }

    /** Creates a warning diagnostic from a registered code and a range. */
    public static CompilerDiagnostic warning(DiagnosticCode dCode, String message,
                                             DiagnosticRange range) {
        return new CompilerDiagnostic(requireCode(dCode).code(), "warning", message,
            range, null, dCode);
    }

    /**
     * Creates an error diagnostic anchored at a span via
     * {@link Span#range()}. When the conversion yields a SYNTHETIC range,
     * the mandatory D4 anchor note
     * {@code missing anchor: <file>:<startLine>:<startColumn>} is
     * appended.
     */
    public static CompilerDiagnostic error(DiagnosticCode dCode, String message, Span span) {
        return fromSpan(requireCode(dCode), "error", message, span);
    }

    /**
     * Creates a warning diagnostic anchored at a span; same anchor-note
     * rule as {@link #error(DiagnosticCode, String, Span)}.
     */
    public static CompilerDiagnostic warning(DiagnosticCode dCode, String message, Span span) {
        return fromSpan(requireCode(dCode), "warning", message, span);
    }

    /**
     * Creates an error diagnostic anchored at a token via
     * {@link Token#range()} (tokens carry no file, so the range uses the
     * empty file). When the conversion yields a SYNTHETIC range, the
     * mandatory D4 anchor note
     * {@code missing anchor: <file>:<startLine>:<startColumn>} is
     * appended with the empty file.
     */
    public static CompilerDiagnostic error(DiagnosticCode dCode, String message, Token token) {
        return fromToken(requireCode(dCode), "error", message, token);
    }

    /**
     * Creates a warning diagnostic anchored at a token; same anchor-note
     * rule as {@link #error(DiagnosticCode, String, Token)}.
     */
    public static CompilerDiagnostic warning(DiagnosticCode dCode, String message, Token token) {
        return fromToken(requireCode(dCode), "warning", message, token);
    }

    /**
     * Creates an error diagnostic with the canonical synthetic shape
     * {@code (file,1,1,1,1,0,0,0,SYNTHETIC)} plus the provided
     * construct-naming anchor note (D6).
     *
     * @param missingAnchorNote the anchor note naming the missing anchor
     *                          (e.g.
     *                          {@code "missing anchor: class declaration span for cycle node 'A'"});
     *                          must not be null or empty
     */
    public static CompilerDiagnostic syntheticError(DiagnosticCode dCode, String message,
                                                    String file, String missingAnchorNote) {
        return syntheticFrom(requireCode(dCode), "error", message, file, missingAnchorNote);
    }

    /**
     * Creates a warning diagnostic with the canonical synthetic shape plus
     * the provided construct-naming anchor note (D6); same note rule as
     * {@link #syntheticError(DiagnosticCode, String, String, String)}.
     */
    public static CompilerDiagnostic syntheticWarning(DiagnosticCode dCode, String message,
                                                      String file, String missingAnchorNote) {
        return syntheticFrom(requireCode(dCode), "warning", message, file, missingAnchorNote);
    }

    // =========================================================================
    // Deprecated test-only factories (D5)
    // =========================================================================

    /**
     * Creates an error diagnostic from a string code. Unregistered
     * test-only pseudo codes (e.g. E9999) yield a null
     * {@code diagnosticCode}.
     *
     * @deprecated Use {@link #error(DiagnosticCode, String, DiagnosticRange)}
     *             with a registered code.
     */
    @Deprecated
    public static CompilerDiagnostic error(String code, String message, DiagnosticRange range) {
        return new CompilerDiagnostic(code, "error", message, range, null,
            DiagnosticCode.fromCode(code));
    }

    /**
     * Creates a warning diagnostic from a string code. Unregistered
     * test-only pseudo codes (e.g. W0001) yield a null
     * {@code diagnosticCode}.
     *
     * @deprecated Use {@link #warning(DiagnosticCode, String, DiagnosticRange)}
     *             with a registered code.
     */
    @Deprecated
    public static CompilerDiagnostic warning(String code, String message, DiagnosticRange range) {
        return new CompilerDiagnostic(code, "warning", message, range, null,
            DiagnosticCode.fromCode(code));
    }

    /**
     * Creates a test-only diagnostic with a string code, an explicit
     * severity, the canonical synthetic shape, and the provided anchor
     * note.
     *
     * @deprecated Test-only pseudo codes (e.g. E9999/W0001); use
     *             {@link #syntheticError(DiagnosticCode, String, String, String)}
     *             / {@link #syntheticWarning(DiagnosticCode, String, String, String)}
     *             with registered codes.
     */
    @Deprecated
    public static CompilerDiagnostic synthetic(String code, String severity, String message,
                                               String file, String missingAnchorNote) {
        return new CompilerDiagnostic(code, severity, message,
            DiagnosticRange.synthetic(file), List.of(requireAnchorNote(missingAnchorNote)),
            DiagnosticCode.fromCode(code));
    }

    // =========================================================================
    // Compatibility accessors
    // =========================================================================

    /** The range's start file (compatibility accessor). */
    public String file() {
        return range.file();
    }

    /** The range's start line, 1-based (compatibility accessor). */
    public int line() {
        return range.startLine();
    }

    /** The range's start column, 1-based (compatibility accessor). */
    public int column() {
        return range.startColumn();
    }

    /**
     * Delegates to the canonical formatter (D7/D8): the rendered string is
     * identical to {@link DiagnosticFormatter#format(CompilerDiagnostic)}
     * for the same diagnostic and contains the SEVERITY, code, and message
     * substrings.
     */
    @Override
    public String toString() {
        return DiagnosticFormatter.format(this);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static DiagnosticCode requireCode(DiagnosticCode dCode) {
        if (dCode == null) {
            throw new IllegalArgumentException("diagnosticCode must not be null");
        }
        return dCode;
    }

    /** A message-only D9 normalization note. */
    private static DiagnosticNote defect(String reason) {
        return new DiagnosticNote("internal range defect: " + reason, null);
    }

    /** The mandatory D4 anchor note for a span/token converted to SYNTHETIC. */
    private static DiagnosticNote anchorNote(String file, int line, int column) {
        return new DiagnosticNote("missing anchor: " + file + ":" + line + ":" + column, null);
    }

    private static DiagnosticNote requireAnchorNote(String missingAnchorNote) {
        if (missingAnchorNote == null || missingAnchorNote.isEmpty()) {
            throw new IllegalArgumentException("missingAnchorNote must not be null or empty");
        }
        return new DiagnosticNote(missingAnchorNote, null);
    }

    private static CompilerDiagnostic fromSpan(DiagnosticCode dCode, String severity,
                                               String message, Span span) {
        DiagnosticRange r = span.range();
        List<DiagnosticNote> spanNotes = r.origin() == RangeOrigin.SYNTHETIC
            ? List.of(anchorNote(span.file(), span.startLine(), span.startColumn()))
            : null;
        return new CompilerDiagnostic(dCode.code(), severity, message, r, spanNotes, dCode);
    }

    private static CompilerDiagnostic fromToken(DiagnosticCode dCode, String severity,
                                                String message, Token token) {
        DiagnosticRange r = token.range();
        List<DiagnosticNote> tokenNotes = r.origin() == RangeOrigin.SYNTHETIC
            ? List.of(anchorNote("", token.line(), token.column()))
            : null;
        return new CompilerDiagnostic(dCode.code(), severity, message, r, tokenNotes, dCode);
    }

    private static CompilerDiagnostic syntheticFrom(DiagnosticCode dCode, String severity,
                                                    String message, String file,
                                                    String missingAnchorNote) {
        return new CompilerDiagnostic(dCode.code(), severity, message,
            DiagnosticRange.synthetic(file), List.of(requireAnchorNote(missingAnchorNote)),
            dCode);
    }
}
