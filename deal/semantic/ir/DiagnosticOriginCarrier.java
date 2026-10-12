package deal.semantic.ir;

import deal.diagnostics.DiagnosticRange;

/**
 * The common optional-source-attribution contract of one phase-owned
 * failure signal (design {@code shared-lowering-diagnostic-origins} D2/D4):
 * a signal that identified the offending construct exposes its immutable
 * diagnostic range; {@code null} denotes "no identified source anchor".
 *
 * <p>The carried value is pure data: an identified valid source range is
 * copied unchanged, and a genuinely originless failure keeps {@code null}
 * so the registry's canonical empty-file synthetic projection stays the
 * one-argument E6005 surface. Signals that do not know their source
 * construct simply do not implement this contract; a more specific nested
 * signal keeps its own range and classification when it is the cause.</p>
 *
 */
public interface DiagnosticOriginCarrier {

    /**
     * The offending construct's own source range, or {@code null} when the
     * signal identified no source anchor (never a guessed one).
     */
    DiagnosticRange diagnosticRange();
}
