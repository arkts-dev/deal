package deal.project;

/**
 * The raw CLI overrides of a locate call (design source
 * {@code strict-project-context-resolution-identity} D1 step 3,
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D10/D11).
 *
 * <p>Both fields are the CLI strings exactly as supplied — untrimmed and
 * case-preserved — and both are optional (null = absent). Validation is
 * {@link ProjectLocator}'s D1 step-3 duty, not this carrier's: a valid
 * backend alias {@code lua|luajit|jvm|js} (trim + lowercase) overrides
 * the manifest backend; a valid output string (after trimming: non-empty,
 * scalar-valid, NUL-free, host-representable) overrides the manifest
 * output, with the trimmed value as the winning value. An invalid or
 * empty/whitespace-only override is a {@link CliDiagnostic} and publishes
 * no context, and overrides are consulted only after the manifest parsed
 * successfully — an override can never bypass a malformed manifest.</p>
 *
 * @param backend the raw CLI backend value ({@code null} when the flag is
 *                absent)
 * @param output  the raw CLI output value ({@code null} when the flag is
 *                absent)
 */
public record CliOverrides(String backend, String output) {
}
