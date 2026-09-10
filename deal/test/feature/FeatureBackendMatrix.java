package deal.test.feature;

import java.util.List;

/**
 * The architecture-owned ISSUE-0111 backend/invocation matrix
 * (declarations page D12, the authoritative table): the matrix — not the
 * sidecar — selects the mandatory backends, invocation shape, and linked
 * records for every feature id.
 *
 * <pre>{@code
 * | FeatureId                          | mandatory backends/invocation |
 * |------------------------------------|-------------------------------|
 * | SIGNED_INT32, BYTES_CORE,          | same runtime behavior on      |
 * | BYTES_DEFAULTS, BYTES_DESCRIPTORS, | LuaJIT and JVM; direct-main   |
 * | BYTES_SYNC_FUNCTION                | or typed synthetic-main       |
 * | BYTES_ASYNC_FUNCTION               | same semantic record on       |
 * |                                    | LuaJIT and JVM with           |
 * |                                    | async-export                  |
 * | DIRECTIVES, DIAGNOSTIC_RANGE,      | compiler record on each       |
 * | PROJECT_CONFIG                     | applicable backend pipeline   |
 * | C_FFI                              | LuaJIT runtime record plus    |
 * |                                    | linked JVM compile-error      |
 * |                                    | E6003 record                  |
 * | C_FFI_DECLARATION_ERROR            | compile-error on both         |
 * |                                    | pipelines unless              |
 * |                                    | backend-specific              |
 * }</pre>
 *
 * <p>{@link #validate(V12FeatureMetadata)} is the catalog's pre-compilation
 * gate: a sidecar backend set must equal the matrix result; omission,
 * addition, a missing linked E6003 record, an illegal invocation shape,
 * or a sync invocation for an async record fails catalog validation
 * before any compilation. The matrix is a closed switch — no feature id
 * can pass validation through a default or fallback row.</p>
 */
public final class FeatureBackendMatrix {

    /** The exact dual-backend set of the parity features. */
    public static final List<String> LUAJIT_JVM = List.of("luajit", "jvm");

    private FeatureBackendMatrix() {
        /* Static authority table only. */
    }

    /**
     * Validates one sidecar record against the architecture-owned
     * matrix.
     *
     * @param record    the parsed record (already passed the conditional
     *                  shape rules of {@link V12FeatureMetadata})
     * @param catalog   the catalog for linked-record resolution; non-null
     * @return null on matrix compliance, otherwise a violation message
     */
    public static String validate(V12FeatureMetadata record,
                                  V12FeatureFixtureCatalog catalog) {
        FeatureId feature = record.feature();
        List<String> backends = record.backends();
        V12FeatureMetadata.Invocation invocation = record.invocation();
        return switch (feature) {
            case SIGNED_INT32, BYTES_CORE, BYTES_DEFAULTS, BYTES_DESCRIPTORS,
                    BYTES_SYNC_FUNCTION -> {
                String violation = requireBackends(record, LUAJIT_JVM);
                if (violation != null) {
                    yield violation;
                }
                boolean isRuntime = record.expected()
                        instanceof V12FeatureMetadata.RuntimeOk
                    || record.expected()
                        instanceof V12FeatureMetadata.RuntimeError;
                if (isRuntime && invocation != V12FeatureMetadata.Invocation.DIRECT_MAIN
                        && invocation != V12FeatureMetadata.Invocation.SYNTHETIC_MAIN) {
                    yield feature.canonicalName() + " runtime records require "
                        + "direct-main or typed synthetic-main, got "
                        + invocation.canonicalText();
                }
                yield null;
            }
            case BYTES_ASYNC_FUNCTION -> {
                String violation = requireBackends(record, LUAJIT_JVM);
                if (violation != null) {
                    yield violation;
                }
                if (invocation != V12FeatureMetadata.Invocation.ASYNC_EXPORT) {
                    yield feature.canonicalName() + " requires async-export "
                        + "invocation, got " + invocation.canonicalText();
                }
                if (record.oracle() == null
                        || !record.oracle().functionDescriptor()
                            .startsWith("async()->")) {
                    yield feature.canonicalName() + " requires an "
                        + "async()->R oracle";
                }
                yield null;
            }
            case DIRECTIVES, DIAGNOSTIC_RANGE, PROJECT_CONFIG -> {
                if (backends.isEmpty()
                        || !backends.stream().allMatch(
                            V12FeatureMetadata.BACKEND_NAMES::contains)) {
                    yield feature.canonicalName() + " backends must be a "
                        + "non-empty ordered subset of [luajit, jvm], got "
                        + backends;
                }
                yield null;
            }
            case C_FFI -> {
                if (record.backends().equals(List.of("jvm"))) {
                    // The linked JVM E6003 rejection half (D12): a
                    // JVM-only compile-error E6003 record, never a
                    // runtime record.
                    if (!(record.expected()
                            instanceof V12FeatureMetadata.CompileError error)
                            || !error.code().equals("E6003")) {
                        yield feature.canonicalName() + " JVM-only records "
                            + "must expect compile-error E6003";
                    }
                    if (invocation != V12FeatureMetadata.Invocation.COMPILE_ONLY) {
                        yield feature.canonicalName() + " JVM-only records "
                            + "must use compile-only invocation";
                    }
                    yield null;
                }
                String violation = requireBackends(record, List.of("luajit"));
                if (violation != null) {
                    yield violation;
                }
                if (invocation == V12FeatureMetadata.Invocation.COMPILE_ONLY) {
                    yield feature.canonicalName() + " is a runtime feature; "
                        + "compile-only invocation fails catalog validation";
                }
                String linked = record.linkedRecord();
                if (linked == null || linked.isEmpty()) {
                    yield feature.canonicalName() + " requires a linked JVM "
                        + "compile-error E6003 record (missing linkedRecord)";
                }
                V12FeatureMetadata linkedRecord = catalog == null ? null
                    : catalog.recordById(linked);
                if (linkedRecord == null) {
                    yield feature.canonicalName() + " linkedRecord '" + linked
                        + "' does not resolve in the catalog";
                }
                if (linkedRecord.feature() != FeatureId.C_FFI
                        || !linkedRecord.backends().equals(List.of("jvm"))) {
                    yield feature.canonicalName() + " linkedRecord '" + linked
                        + "' must be a JVM-only C_FFI record";
                }
                if (!(linkedRecord.expected()
                        instanceof V12FeatureMetadata.CompileError error)
                        || !error.code().equals("E6003")) {
                    yield feature.canonicalName() + " linkedRecord '" + linked
                        + "' must expect compile-error E6003";
                }
                yield null;
            }
            case C_FFI_DECLARATION_ERROR -> {
                if (backends.equals(LUAJIT_JVM)) {
                    yield null;
                }
                yield feature.canonicalName() + " requires compile-error "
                    + "records on both pipelines (backends " + LUAJIT_JVM
                    + "), got " + backends;
            }
        };
    }

    private static String requireBackends(V12FeatureMetadata record,
                                          List<String> required) {
        if (!record.backends().equals(required)) {
            return record.feature().canonicalName() + " requires the exact "
                + "backend set " + required + ", got " + record.backends();
        }
        return null;
    }
}
