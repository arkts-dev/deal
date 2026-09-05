package deal.test.conformance;

import deal.test.conformance.V12FeatureMetadata.ExpectedOutcome;
import deal.test.conformance.V12FeatureMetadata.FeatureId;
import deal.test.conformance.V12FeatureMetadata.Invocation;
import deal.test.conformance.V12FeatureMetadata.Metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The architecture-owned feature/backend matrix (ISSUE-0157; design
 * source {@code deal-v1.2-directives-and-c-ffi-declarations} D12's
 * {@code FeatureBackendMatrix} table).
 *
 * <p>The matrix — never an individual sidecar — selects the mandatory
 * backends, invocation, and linked-record requirements of every feature
 * record. A record whose backend set, invocation, expectation shape, or
 * linked-record field diverges from its feature's matrix row is a
 * {@link MatrixFailure} before any compilation. Backend omission,
 * backend addition, a missing linked JVM FFI-unsupported record, and
 * sync invocation for an async record are all matrix failures.</p>
 *
 * <p>Pinned matrix rows (D12 table, closed):</p>
 * <ul>
 *   <li>{@code SIGNED_INT32}, {@code BYTES_CORE}, {@code BYTES_DEFAULTS},
 *       {@code BYTES_DESCRIPTORS}, {@code BYTES_SYNC_FUNCTION} — runtime
 *       outcomes ({@code runtime-ok}/{@code runtime-error}) with
 *       {@code direct-main} or {@code synthetic-main}, on exactly both
 *       backends {@code [luajit, jvm]}; no linked record.</li>
 *   <li>{@code BYTES_ASYNC_FUNCTION} — runtime outcomes with exactly
 *       {@code async-export} (the oracle invokes and awaits a
 *       first-class bytes-bearing async value), on exactly
 *       {@code [luajit, jvm]}; no linked record.</li>
 *   <li>{@code DIRECTIVES}, {@code DIAGNOSTIC_RANGE},
 *       {@code PROJECT_CONFIG} — a compiler record on each applicable
 *       backend pipeline: compile outcomes with {@code compile-only} on
 *       {@code [luajit, jvm]}; runtime configuration behavior where
 *       specified: runtime outcomes with {@code direct-main} on
 *       {@code [luajit, jvm]}; no linked record.</li>
 *   <li>{@code C_FFI} — exactly the two sanctioned record shapes: the
 *       LuaJIT runtime record ({@code runtime-ok}/{@code runtime-error},
 *       {@code direct-main}/{@code synthetic-main}, backends exactly
 *       {@code [luajit]}) whose {@code linkedRecord} names the JVM
 *       compile-error record; and the JVM rejection record (compile
 *       outcome pinning exactly {@code FFI_UNSUPPORTED_BACKEND},
 *       {@code compile-only}, backends exactly {@code [jvm]}). Any other
 *       combination — JVM runtime, dual-backend runtime, a runtime
 *       record without its link — is a matrix failure.</li>
 *   <li>{@code C_FFI_DECLARATION_ERROR} — compile-error records with
 *       {@code compile-only}; a record may name {@code [luajit]},
 *       {@code [jvm]}, or both backends (backend-specific conditions
 *       allowed), and the catalog's records must collectively cover
 *       both pipelines.</li>
 * </ul>
 *
 * <p>The pinned FFI unsupported-backend code of the linked JVM
 * rejection record is {@link #FFI_UNSUPPORTED_BACKEND_CODE}
 * {@code E6003} {@code FFI_UNSUPPORTED_BACKEND} — the exact code the
 * production JVM pipeline emits for {@code @extern-c} before any
 * artifact write (design D8; epic criterion: JVM extern-C emits E6003
 * before artifacts). The production emission site is
 * {@code deal/module/CompilationOrchestrator.java} (the
 * {@code E6003 FFI_UNSUPPORTED_BACKEND} arm at {@code @extern-c}),
 * pinned by {@code ProjectIntegrationGatesTest} and
 * {@code FfiDeclarationValidatorTest}. Any future renumbering of that
 * emission belongs to the backend epic, not to this catalog.</p>
 *
 * <p>Catalog-level matrix rules ({@link #validateCatalog}):</p>
 * <ul>
 *   <li>Every {@link FeatureId} must have at least one record (a
 *       complete catalog closure — no feature may be silently
 *       unverified).</li>
 *   <li>Every {@code C_FFI} runtime record's {@code linkedRecord} must
 *       resolve to an existing {@code C_FFI} JVM rejection record
 *       pinning {@link #FFI_UNSUPPORTED_BACKEND_CODE}; every
 *       {@code C_FFI} rejection record must be the target of at least
 *       one runtime record's link.</li>
 *   <li>The {@code C_FFI_DECLARATION_ERROR} records must collectively
 *       cover both backend pipelines.</li>
 * </ul>
 */
public final class FeatureBackendMatrix {

    /**
     * The pinned FFI unsupported-backend diagnostic code of the linked
     * JVM rejection record: {@code E6003 FFI_UNSUPPORTED_BACKEND} — the
     * exact code the production JVM pipeline emits at {@code @extern-c}
     * before any artifact write (design D8; epic criterion).
     */
    public static final String FFI_UNSUPPORTED_BACKEND_CODE = "E6003";

    private FeatureBackendMatrix() {
        // Static utility; no instances.
    }

    // =========================================================================
    // Result types
    // =========================================================================

    /**
     * One matrix failure: the record id, the offending field, and the
     * exact reason.
     */
    public record MatrixFailure(String recordId, String field, String reason) {

        public MatrixFailure {
            Objects.requireNonNull(recordId, "recordId must not be null");
            Objects.requireNonNull(field, "field must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }

        /** The human-readable failure line: record, field, reason. */
        public String message() {
            return recordId + ": " + field + ": " + reason;
        }

        @Override
        public String toString() {
            return message();
        }
    }

    /**
     * The matrix classification of one validated record — which of the
     * two sanctioned {@code C_FFI} shapes the record is (every other
     * feature has a single row).
     */
    public enum CffiShape {
        /** The LuaJIT runtime record (backends [luajit], runtime outcome). */
        RUNTIME,
        /** The JVM compile-error FFI_UNSUPPORTED_BACKEND record (backends [jvm]). */
        REJECTION,
        /** Not a C_FFI record. */
        NONE
    }

    // =========================================================================
    // Per-record validation
    // =========================================================================

    /**
     * Validates one parsed feature record against its feature's matrix
     * row and returns the first violation, or
     * {@link Optional#empty()} when the record matches its row.
     *
     * @param recordId the canonical feature-record id (for failure naming)
     * @param metadata the shape-validated metadata (conditional rules
     *                 already applied by {@link V12FeatureMetadata})
     * @return the first matrix violation, or empty when compliant
     */
    public static Optional<MatrixFailure> validateRecord(
            String recordId, Metadata metadata) {
        Objects.requireNonNull(recordId, "recordId must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        return switch (metadata.feature()) {
            case SIGNED_INT32, BYTES_CORE, BYTES_DEFAULTS, BYTES_DESCRIPTORS,
                 BYTES_SYNC_FUNCTION -> validateRuntimeParity(recordId, metadata);
            case BYTES_ASYNC_FUNCTION -> validateAsyncBytes(recordId, metadata);
            case DIRECTIVES, DIAGNOSTIC_RANGE, PROJECT_CONFIG ->
                validateCompilerFeature(recordId, metadata);
            case C_FFI -> validateCffi(recordId, metadata);
            case C_FFI_DECLARATION_ERROR ->
                validateCffiDeclarationError(recordId, metadata);
        };
    }

    /**
     * The runtime-parity row: runtime outcomes, direct-main or
     * synthetic-main, exactly both backends, no linked record.
     */
    private static Optional<MatrixFailure> validateRuntimeParity(
            String recordId, Metadata m) {
        Optional<MatrixFailure> fail = requireRuntimeOutcome(recordId, m);
        if (fail.isPresent()) {
            return fail;
        }
        fail = requireInvocation(recordId, m,
            List.of(Invocation.DIRECT_MAIN, Invocation.SYNTHETIC_MAIN));
        if (fail.isPresent()) {
            return fail;
        }
        fail = requireExactBackends(recordId, m, List.of("luajit", "jvm"),
            "the " + m.feature().jsonText() + " row requires the same "
                + "runtime record on exactly LuaJIT and JVM");
        if (fail.isPresent()) {
            return fail;
        }
        return forbidLinkedRecord(recordId, m);
    }

    /**
     * The async-bytes row: runtime outcomes, exactly async-export (a
     * sync invocation for an async record is the pinned matrix failure),
     * exactly both backends, no linked record.
     */
    private static Optional<MatrixFailure> validateAsyncBytes(
            String recordId, Metadata m) {
        Optional<MatrixFailure> fail = requireRuntimeOutcome(recordId, m);
        if (fail.isPresent()) {
            return fail;
        }
        if (m.invocation() != Invocation.ASYNC_EXPORT) {
            return Optional.of(new MatrixFailure(recordId, "invocation",
                "the BYTES_ASYNC_FUNCTION row requires exactly async-export "
                    + "(the oracle invokes and awaits a first-class "
                    + "bytes-bearing async value), got "
                    + m.invocation().jsonText()));
        }
        fail = requireExactBackends(recordId, m, List.of("luajit", "jvm"),
            "the BYTES_ASYNC_FUNCTION row requires the async record on "
                + "exactly LuaJIT and JVM");
        if (fail.isPresent()) {
            return fail;
        }
        return forbidLinkedRecord(recordId, m);
    }

    /**
     * The compiler-feature row ({@code DIRECTIVES},
     * {@code DIAGNOSTIC_RANGE}, {@code PROJECT_CONFIG}): compile records
     * on each applicable backend pipeline (compile-only on
     * {@code [luajit, jvm]}), or runtime configuration behavior where
     * specified (direct-main on {@code [luajit, jvm]}).
     */
    private static Optional<MatrixFailure> validateCompilerFeature(
            String recordId, Metadata m) {
        Optional<MatrixFailure> fail;
        if (m.expected().isCompileOutcome()) {
            fail = requireInvocation(recordId, m, List.of(Invocation.COMPILE_ONLY));
            if (fail.isPresent()) {
                return fail;
            }
        } else {
            fail = requireInvocation(recordId, m, List.of(Invocation.DIRECT_MAIN));
            if (fail.isPresent()) {
                return fail;
            }
        }
        fail = requireExactBackends(recordId, m, List.of("luajit", "jvm"),
            "the " + m.feature().jsonText() + " row runs the record on each "
                + "applicable backend pipeline (luajit and jvm)");
        if (fail.isPresent()) {
            return fail;
        }
        return forbidLinkedRecord(recordId, m);
    }

    /**
     * The C_FFI row: exactly the two sanctioned shapes — the LuaJIT
     * runtime record whose linkedRecord names the JVM rejection record,
     * and the JVM compile-error {@code FFI_UNSUPPORTED_BACKEND} record.
     */
    private static Optional<MatrixFailure> validateCffi(
            String recordId, Metadata m) {
        if (m.expected().isCompileOutcome()) {
            // The sanctioned compile shape: the JVM rejection record.
            if (!(m.expected() instanceof ExpectedOutcome.CompileError error)) {
                return Optional.of(new MatrixFailure(recordId, "expected",
                    "the C_FFI row permits exactly two shapes: the LuaJIT "
                        + "runtime record and the linked JVM compile-error "
                        + FFI_UNSUPPORTED_BACKEND_CODE + " rejection record — "
                        + "compile-ok is neither"));
            }
            if (!FFI_UNSUPPORTED_BACKEND_CODE.equals(error.code())) {
                return Optional.of(new MatrixFailure(recordId, "expected",
                    "the C_FFI rejection record must pin exactly "
                        + FFI_UNSUPPORTED_BACKEND_CODE
                        + " (FFI_UNSUPPORTED_BACKEND), got " + error.code()));
            }
            if (m.invocation() != Invocation.COMPILE_ONLY) {
                return Optional.of(new MatrixFailure(recordId, "invocation",
                    "the C_FFI rejection record is compile-only, got "
                        + m.invocation().jsonText()));
            }
            Optional<MatrixFailure> backends = requireExactBackends(
                recordId, m, List.of("jvm"),
                "the C_FFI rejection record runs exactly on JVM");
            if (backends.isPresent()) {
                return backends;
            }
            if (m.linkedRecord().isPresent()) {
                return Optional.of(new MatrixFailure(recordId, "linkedRecord",
                    "the C_FFI rejection record is the link target and "
                        + "carries no linkedRecord of its own"));
            }
            return Optional.empty();
        }
        // The sanctioned runtime shape: LuaJIT only, direct/synthetic,
        // with the mandatory linked JVM rejection record.
        Optional<MatrixFailure> fail = requireInvocation(recordId, m,
            List.of(Invocation.DIRECT_MAIN, Invocation.SYNTHETIC_MAIN));
        if (fail.isPresent()) {
            return fail;
        }
        fail = requireExactBackends(recordId, m, List.of("luajit"),
            "the C_FFI runtime record runs exactly on LuaJIT (JVM runtime "
                + "for C FFI is unsupported and is a backend-addition "
                + "failure)");
        if (fail.isPresent()) {
            return fail;
        }
        if (m.linkedRecord().isEmpty()) {
            return Optional.of(new MatrixFailure(recordId, "linkedRecord",
                "the C_FFI LuaJIT runtime record must link the JVM "
                    + FFI_UNSUPPORTED_BACKEND_CODE + " compile-error record"));
        }
        return Optional.empty();
    }

    /**
     * The declaration-error row: compile-error records on both
     * pipelines unless a record's condition is backend-specific
     * (single-backend records allowed; catalog-level coverage enforces
     * the pipeline union).
     */
    private static Optional<MatrixFailure> validateCffiDeclarationError(
            String recordId, Metadata m) {
        if (!(m.expected() instanceof ExpectedOutcome.CompileError)) {
            return Optional.of(new MatrixFailure(recordId, "expected",
                "the C_FFI_DECLARATION_ERROR row requires compile-error "
                    + "records, got " + m.expected().mode()));
        }
        if (m.invocation() != Invocation.COMPILE_ONLY) {
            return Optional.of(new MatrixFailure(recordId, "invocation",
                "the C_FFI_DECLARATION_ERROR row is compile-only, got "
                    + m.invocation().jsonText()));
        }
        if (m.oracle().isPresent()) {
            return Optional.of(new MatrixFailure(recordId, "oracle",
                "the C_FFI_DECLARATION_ERROR row carries no oracle"));
        }
        if (m.linkedRecord().isPresent()) {
            return Optional.of(new MatrixFailure(recordId, "linkedRecord",
                "the C_FFI_DECLARATION_ERROR row carries no linked record"));
        }
        for (String backend : m.backends()) {
            if (!"luajit".equals(backend) && !"jvm".equals(backend)) {
                return Optional.of(new MatrixFailure(recordId, "backends",
                    "unknown backend \"" + backend + "\""));
            }
        }
        return Optional.empty();
    }

    // =========================================================================
    // Catalog-level validation
    // =========================================================================

    /**
     * Validates the whole catalog against the matrix's catalog-level
     * rules: every {@link FeatureId} has at least one record; every
     * {@code C_FFI} runtime record links an existing {@code C_FFI}
     * rejection record and every rejection record is linked; the
     * {@code C_FFI_DECLARATION_ERROR} records collectively cover both
     * pipelines.
     *
     * @param records the parsed records by canonical id (already
     *                per-record matrix-validated by the caller)
     * @return the complete failure list in deterministic order (never
     *         null; empty exactly when the catalog matrix-validates)
     */
    public static List<MatrixFailure> validateCatalog(
            Map<String, Metadata> records) {
        Objects.requireNonNull(records, "records must not be null");
        List<MatrixFailure> failures = new ArrayList<>();

        // Feature completeness: the architecture-owned matrix closes the
        // catalog — a feature with zero records is an omission.
        for (FeatureId feature : FeatureId.values()) {
            boolean present = false;
            for (Metadata m : records.values()) {
                if (m.feature() == feature) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                failures.add(new MatrixFailure("<catalog>", "feature",
                    "the matrix requires at least one "
                        + feature.jsonText() + " record — the catalog "
                        + "carries none (feature omission)"));
            }
        }

        // C_FFI links: runtime → existing rejection record; rejection
        // records must be linked by at least one runtime record.
        Set<String> linkedTargets = new LinkedHashSet<>();
        for (Map.Entry<String, Metadata> entry : records.entrySet()) {
            Metadata m = entry.getValue();
            if (m.feature() == FeatureId.C_FFI
                    && m.expected().isRuntimeOutcome()
                    && m.linkedRecord().isPresent()) {
                String target = m.linkedRecord().get();
                Metadata targetMetadata = records.get(target);
                if (targetMetadata == null) {
                    failures.add(new MatrixFailure(entry.getKey(),
                        "linkedRecord",
                        "the linked C_FFI rejection record \"" + target
                            + "\" does not exist in the catalog (missing "
                            + "linked " + FFI_UNSUPPORTED_BACKEND_CODE
                            + " record)"));
                    continue;
                }
                if (!(targetMetadata.feature() == FeatureId.C_FFI
                        && targetMetadata.expected()
                            instanceof ExpectedOutcome.CompileError error
                        && FFI_UNSUPPORTED_BACKEND_CODE.equals(error.code())
                        && targetMetadata.invocation() == Invocation.COMPILE_ONLY
                        && targetMetadata.backends().equals(List.of("jvm")))) {
                    failures.add(new MatrixFailure(entry.getKey(),
                        "linkedRecord",
                        "the linked record \"" + target + "\" is not the "
                            + "JVM compile-error "
                            + FFI_UNSUPPORTED_BACKEND_CODE
                            + " rejection record the C_FFI row requires"));
                    continue;
                }
                linkedTargets.add(target);
            }
        }
        for (Map.Entry<String, Metadata> entry : records.entrySet()) {
            Metadata m = entry.getValue();
            if (m.feature() == FeatureId.C_FFI
                    && m.expected().isCompileOutcome()
                    && !linkedTargets.contains(entry.getKey())) {
                failures.add(new MatrixFailure(entry.getKey(),
                    "linkedRecord",
                    "the C_FFI rejection record is not the target of any "
                        + "C_FFI runtime record's linkedRecord (unused "
                        + "rejection record)"));
            }
        }

        // C_FFI_DECLARATION_ERROR: both pipelines collectively covered.
        Set<String> covered = new LinkedHashSet<>();
        for (Metadata m : records.values()) {
            if (m.feature() == FeatureId.C_FFI_DECLARATION_ERROR) {
                covered.addAll(m.backends());
            }
        }
        if (!covered.containsAll(List.of("luajit", "jvm"))) {
            failures.add(new MatrixFailure("<catalog>", "backends",
                "the C_FFI_DECLARATION_ERROR records must collectively "
                    + "cover both backend pipelines (luajit and jvm), "
                    + "covered: " + (covered.isEmpty()
                        ? "none" : String.join(", ", covered))));
        }

        return List.copyOf(failures);
    }

    // =========================================================================
    // Shared row checks
    // =========================================================================

    private static Optional<MatrixFailure> requireRuntimeOutcome(
            String recordId, Metadata m) {
        if (!m.expected().isRuntimeOutcome()) {
            return Optional.of(new MatrixFailure(recordId, "expected",
                "the " + m.feature().jsonText() + " row requires a runtime "
                    + "outcome (runtime-ok/runtime-error), got "
                    + m.expected().mode()));
        }
        return Optional.empty();
    }

    private static Optional<MatrixFailure> requireInvocation(
            String recordId, Metadata m, List<Invocation> allowed) {
        if (!allowed.contains(m.invocation())) {
            return Optional.of(new MatrixFailure(recordId, "invocation",
                "the " + m.feature().jsonText() + " row permits "
                    + invocationNames(allowed) + ", got "
                    + m.invocation().jsonText()));
        }
        return Optional.empty();
    }

    private static Optional<MatrixFailure> requireExactBackends(
            String recordId, Metadata m, List<String> required, String reason) {
        if (!m.backends().equals(required)) {
            return Optional.of(new MatrixFailure(recordId, "backends",
                reason + " — the record names ["
                    + String.join(", ", m.backends()) + "]"));
        }
        return Optional.empty();
    }

    private static Optional<MatrixFailure> forbidLinkedRecord(
            String recordId, Metadata m) {
        if (m.linkedRecord().isPresent()) {
            return Optional.of(new MatrixFailure(recordId, "linkedRecord",
                "the " + m.feature().jsonText() + " row forbids a linked "
                    + "record (linkedRecord exists only on the C_FFI "
                    + "runtime record)"));
        }
        return Optional.empty();
    }

    private static String invocationNames(List<Invocation> invocations) {
        List<String> names = new ArrayList<>();
        for (Invocation i : invocations) {
            names.add(i.jsonText());
        }
        return String.join("/", names);
    }
}
