package deal.diagnostics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Canonical report-time diagnostic ordering (deterministic-diagnostics
 * D1): one total order applied at the two report sites only.
 *
 * <p>The canonical key is
 * {@code (file, startScalarOffset, startLine, startColumn, code, message,
 * severity)} — file path byte order ({@link String#compareTo}, which
 * equals UTF-8 byte order for the BMP paths this compiler reads), scalar
 * offset ascending, then line/column, then diagnostic code, then message
 * text, then severity ascending ("error" before "warning"). The sort is
 * stable: diagnostics with equal keys keep their input relative order.
 * The internal collection order, phase gating, and counting paths are
 * unaffected — this helper is a pure function of one list, consumed at
 * report time only by {@code printDiagnostics} and the
 * {@code --diagnostics-json} write path.</p>
 *
 * <p>JDK-only: the ordering uses a stable comparator over the carrier
 * fields (no {@code HashMap}/{@code HashSet} or any order-unspecified
 * structure participates — D4). The method never mutates the input, never
 * returns the input list, and never throws for D9-normalized carriers
 * (code, severity, message, and range are non-null and offsets are
 * normalized by {@link CompilerDiagnostic}'s compact constructor).</p>
 */
public final class DiagnosticOrder {

    /** No instances: the canonical order is a pure static utility. */
    private DiagnosticOrder() {
    }

    /**
     * Returns a NEW list containing the given diagnostics ordered by the
     * full D1 key ascending (see the class contract). The input list is
     * never mutated and is never returned; a null input yields the empty
     * list. Equal keys are stable (input relative order is preserved —
     * {@link List#sort} over a copy).
     *
     * @param diagnostics the collected diagnostics in internal emission
     *                    order, or null
     * @return a new list in the canonical D1 report order
     */
    public static List<CompilerDiagnostic> canonical(
            List<CompilerDiagnostic> diagnostics) {
        if (diagnostics == null) {
            return List.of();
        }
        List<CompilerDiagnostic> ordered = new ArrayList<>(diagnostics);
        ordered.sort(CANONICAL);
        return ordered;
    }

    /**
     * The D1 key comparator: file ({@code String.compareTo}), then
     * {@code startScalarOffset}, then {@code startLine}, then
     * {@code startColumn}, then {@code code()}, then {@code message()},
     * then {@code severity()} — all ascending. The chained comparator is
     * stateless, so the stable sort of {@link List#sort} keeps equal-key
     * input order.
     */
    private static final Comparator<CompilerDiagnostic> CANONICAL =
        Comparator.<CompilerDiagnostic, String>comparing(
                d -> d.range().file())
            .thenComparingInt(d -> d.range().startScalarOffset())
            .thenComparingInt(d -> d.range().startLine())
            .thenComparingInt(d -> d.range().startColumn())
            .thenComparing(CompilerDiagnostic::code)
            .thenComparing(CompilerDiagnostic::message)
            .thenComparing(CompilerDiagnostic::severity);
}
