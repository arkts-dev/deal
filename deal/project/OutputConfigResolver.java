package deal.project;

import deal.source.SourceScalarRange;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The pure backend-selection and output-classification resolver (design
 * source {@code strict-project-context-resolution-identity} D3,
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D11).
 *
 * <p>This resolver owns exactly two decisions over <b>validated
 * values</b> and is otherwise pure:</p>
 *
 * <ol>
 *   <li><b>Backend selection</b> (owned here): validated CLI alias
 *       {@code lua|luajit|jvm} &gt; validated manifest value
 *       {@code luajit|jvm} &gt; default {@code luajit}. The D1 step-3 CLI
 *       validity predicate (trim + lowercase + membership in the three
 *       aliases) runs in ProjectLocator; this resolver canonicalizes the
 *       validated alias ({@code lua} → {@code luajit}) as part of the
 *       selection rule.</li>
 *   <li><b>Output precedence and classification</b> (owned here):
 *       validated CLI output &gt; validated manifest output &gt; default
 *       {@code <manifestDirectory>/build/lua} or
 *       {@code <manifestDirectory>/build/jvm} according to the effective
 *       backend. A leading {@code /} is {@link Kind#ABSOLUTE_PATH} under
 *       both sources; otherwise a MANIFEST-source value resolves from the
 *       manifest directory and a CLI-source value resolves from the
 *       process CWD, both recorded as
 *       {@link Kind#MANIFEST_RELATIVE_PATH} (the pinned parent D11 kind
 *       names).</li>
 * </ol>
 *
 * <p><b>Conversion.</b> The winning value is converted through the D4
 * output row, {@link ProtectedPathOps#normalizePrefixResolved(String)}:
 * absolute + lexical normalization + symlink resolution of the longest
 * existing directory prefix, with the remaining suffix appended
 * lexically. Existence is not required — a fresh project's default
 * {@code build/lua} path and any not-yet-existing manifest output
 * directory are normal values, never an {@link OutputPathFailure}.
 * {@link OutputRef#absoluteNormalizedPath()} carries the prefix-resolved
 * result.</p>
 *
 * <p><b>Residual boundary.</b> A conversion failure — NUL or
 * unpaired-surrogate (host-unrepresentable) text in the winning value, or
 * an I/O failure while resolving the confirmed existing prefix — returns
 * {@link OutputPathFailure(source, reason, sourceRange)}. Schema-side
 * output defects never reach this resolver (D2), invalid CLI values never
 * reach it (D1 step 3), and this conversion re-check is the residual
 * boundary. Mapping the failure — MANIFEST-source → E2010, CLI-source →
 * CliDiagnostic — is ProjectLocator's D1 step-5 duty, never this
 * module's.</p>
 *
 * <p><b>Purity.</b> This module emits no diagnostic, creates no
 * directory, and performs no filesystem mutation: the only filesystem
 * interaction is the read-only prefix probe inside
 * {@link ProtectedPathOps#normalizePrefixResolved(String)}. Identical
 * inputs over an unchanged filesystem produce identical results, and no
 * state exists between calls.</p>
 *
 * <p>Input contract (validated values only):
 * <ul>
 *   <li>{@code manifestBackend}: {@code "luajit"} or {@code "jvm"} as
 *       validated by D2 (absence is already defaulted to
 *       {@code "luajit"} by the parser);</li>
 *   <li>{@code manifestOutput}: the D2-validated output value, or null
 *       when the member is absent;</li>
 *   <li>{@code cliBackendAlias}: the D1 step-3-validated alias
 *       {@code "lua"}, {@code "luajit"}, or {@code "jvm"} (post-trim,
 *       post-lowercase), or null when absent;</li>
 *   <li>{@code cliOutput}: the D1 step-3-validated, trimmed CLI output
 *       string, or null when absent (an empty/whitespace-only override is
 *       already a CliDiagnostic in ProjectLocator);</li>
 *   <li>{@code manifestDirectory} and {@code processCwd}: non-null
 *       directory inputs.</li>
 * </ul>
 * A present-but-invalid backend value (unreachable from a correct
 * caller) is a programming error and throws
 * {@link IllegalArgumentException}; the output conversion itself never
 * throws for any text.</p>
 */
public final class OutputConfigResolver {

    private OutputConfigResolver() {
    }

    /** The canonical LuaJIT backend name. */
    private static final String BACKEND_LUAJIT = "luajit";

    /** The canonical JVM backend name. */
    private static final String BACKEND_JVM = "jvm";

    /** The pinned default output text for the LuaJIT backend. */
    private static final String DEFAULT_OUTPUT_LUAJIT = "build/lua";

    /** The pinned default output text for the JVM backend. */
    private static final String DEFAULT_OUTPUT_JVM = "build/jvm";

    // =========================================================================
    // Pinned shapes (D3 / parent D11)
    // =========================================================================

    /**
     * The winning output value's provenance.
     */
    public enum Source {
        /** A validated manifest {@code output} value (or the derived default). */
        MANIFEST,
        /** A validated CLI {@code --output} override. */
        CLI
    }

    /**
     * The pinned parent-D11 output path kinds.
     */
    public enum Kind {
        /** The winning text starts with {@code /} (under both sources). */
        ABSOLUTE_PATH,
        /**
         * The winning text does not start with {@code /}: resolved from
         * the manifest directory (MANIFEST source) or the process CWD
         * (CLI source).
         */
        MANIFEST_RELATIVE_PATH
    }

    /**
     * The classified, converted effective output (parent D11 shape).
     *
     * @param source                 the winning value's provenance
     * @param kind                   the pinned classification
     * @param decodedText            the winning decoded text: the manifest
     *                               spelling, the trimmed CLI override, or
     *                               the backend-dependent default
     *                               {@code build/lua}/{@code build/jvm}
     * @param absoluteNormalizedPath the protected prefix-resolved
     *                               absolute path (D4 output row:
     *                               longest-existing-directory-prefix
     *                               symlink resolution; existence never
     *                               required)
     * @param sourceRange            the manifest value range for a
     *                               MANIFEST-source result; null for a
     *                               CLI-source result (no source scalar
     *                               range exists) and for the derived
     *                               default
     */
    public record OutputRef(Source source, Kind kind, String decodedText,
                            String absoluteNormalizedPath,
                            SourceScalarRange sourceRange) {
        public OutputRef {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(decodedText, "decodedText");
            Objects.requireNonNull(absoluteNormalizedPath, "absoluteNormalizedPath");
        }
    }

    /**
     * A protected-conversion failure of the winning output value. Mapping
     * into E2010 (MANIFEST source) or CliDiagnostic (CLI source) is
     * ProjectLocator's D1 step-5 duty.
     *
     * @param source      the failing value's provenance
     * @param reason      the pinned deterministic reason of the D4 output
     *                    row conversion
     * @param sourceRange the manifest value range for a MANIFEST-source
     *                    failure; null for a CLI-source failure (no
     *                    source scalar range exists)
     */
    public record OutputPathFailure(Source source, String reason,
                                    SourceScalarRange sourceRange) {
        public OutputPathFailure {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The sole resolver result channel: the effective backend plus
     * exactly one of {@code output} and {@code failure}. Backend
     * selection is independent of output conversion, so the effective
     * backend is always present — also on a failure resolution (the
     * caller publishes no context in that case).
     */
    public record Resolution(String effectiveBackend, OutputRef output,
                             OutputPathFailure failure) {
        public Resolution {
            Objects.requireNonNull(effectiveBackend, "effectiveBackend");
            if ((output == null) == (failure == null)) {
                throw new IllegalArgumentException(
                    "exactly one of output and failure must be non-null");
            }
        }
    }

    // =========================================================================
    // Resolve
    // =========================================================================

    /**
     * Selects the effective backend and classifies + converts the
     * effective output from validated values.
     *
     * @param manifestBackend   the D2-validated manifest backend
     *                          {@code "luajit"} | {@code "jvm"} (null is
     *                          treated as the absent-field default
     *                          {@code "luajit"})
     * @param manifestOutput    the D2-validated manifest output value, or
     *                          null when absent
     * @param cliBackendAlias   the D1 step-3-validated CLI backend alias
     *                          {@code "lua"} | {@code "luajit"} |
     *                          {@code "jvm"}, or null when absent
     * @param cliOutput         the D1 step-3-validated, trimmed CLI
     *                          output string, or null when absent
     * @param manifestDirectory the manifest's directory (non-null;
     *                          normalized absolute before joining)
     * @param processCwd        the process working directory (non-null;
     *                          normalized absolute before joining)
     * @return the effective backend plus the classified output or the
     *         classified conversion failure; never null, never emits a
     *         diagnostic, never creates a directory
     */
    public static Resolution resolve(String manifestBackend,
                                     ManifestString manifestOutput,
                                     String cliBackendAlias,
                                     String cliOutput,
                                     Path manifestDirectory,
                                     Path processCwd) {
        Objects.requireNonNull(manifestDirectory, "manifestDirectory");
        Objects.requireNonNull(processCwd, "processCwd");

        // 1. Backend selection: validated CLI alias > validated manifest
        // value > default luajit.
        String effectiveBackend = selectBackend(manifestBackend, cliBackendAlias);

        // 2. Output precedence: validated CLI output > validated manifest
        // output > the backend-dependent default under manifestDirectory.
        Source source;
        String decodedText;
        SourceScalarRange sourceRange;
        Path base;
        if (cliOutput != null) {
            source = Source.CLI;
            decodedText = cliOutput;
            sourceRange = null;
            base = processCwd;
        } else if (manifestOutput != null) {
            source = Source.MANIFEST;
            decodedText = manifestOutput.value();
            sourceRange = manifestOutput.sourceRange();
            base = manifestDirectory;
        } else {
            source = Source.MANIFEST;
            decodedText = BACKEND_JVM.equals(effectiveBackend)
                ? DEFAULT_OUTPUT_JVM
                : DEFAULT_OUTPUT_LUAJIT;
            sourceRange = null;
            base = manifestDirectory;
        }

        // 3. Classification: leading '/' is absolute under both sources;
        // otherwise the value resolves from the source's base directory.
        Kind kind = decodedText.startsWith("/")
            ? Kind.ABSOLUTE_PATH
            : Kind.MANIFEST_RELATIVE_PATH;

        // 4. Protected conversion through the D4 output row (existence
        // never required; the NUL/host-representability re-check is the
        // residual boundary).
        String absoluteText = kind == Kind.ABSOLUTE_PATH
            ? decodedText
            : joinPathText(normalizeBase(base), decodedText);
        ProtectedPathOps.PathResult converted =
            ProtectedPathOps.normalizePrefixResolved(absoluteText);
        if (converted instanceof ProtectedPathOps.PathResult.Failure conversionFailure) {
            return new Resolution(effectiveBackend, null,
                new OutputPathFailure(source, conversionFailure.reason(), sourceRange));
        }
        ProtectedPathOps.PathResult.Success success =
            (ProtectedPathOps.PathResult.Success) converted;
        return new Resolution(effectiveBackend,
            new OutputRef(source, kind, decodedText,
                success.resolvedPath().toString(), sourceRange), null);
    }

    // =========================================================================
    // Selection and joining helpers
    // =========================================================================

    /**
     * The owned backend-selection rule over validated values. A
     * present-but-invalid backend value is a programming error
     * (unreachable from a correct caller: D2 and the D1 step-3 predicate
     * reject it) and throws immediately instead of silently falling
     * back.
     */
    private static String selectBackend(String manifestBackend, String cliBackendAlias) {
        if (cliBackendAlias != null) {
            return switch (cliBackendAlias) {
                case "lua", "luajit" -> BACKEND_LUAJIT;
                case "jvm" -> BACKEND_JVM;
                default -> throw new IllegalArgumentException(
                    "CLI backend alias did not pass the D1 step-3 validity predicate: '"
                        + cliBackendAlias + "'");
            };
        }
        if (manifestBackend == null) {
            return BACKEND_LUAJIT;
        }
        return switch (manifestBackend) {
            case "luajit", "jvm" -> manifestBackend;
            default -> throw new IllegalArgumentException(
                "manifest backend did not pass the D2 schema validation: '"
                    + manifestBackend + "'");
        };
    }

    /**
     * Normalizes a base directory to absolute lexical form before
     * joining (defensive: the input contract already supplies absolute
     * directories).
     */
    private static String normalizeBase(Path base) {
        return base.toAbsolutePath().normalize().toString();
    }

    /**
     * Joins a relative output text onto a base path text with exactly
     * one separator (a trailing {@code /} on the base is not doubled; the
     * filesystem root {@code "/"} needs no separator).
     */
    private static String joinPathText(String baseText, String relativeText) {
        if (baseText.isEmpty() || baseText.endsWith("/")) {
            return baseText + relativeText;
        }
        return baseText + "/" + relativeText;
    }
}
