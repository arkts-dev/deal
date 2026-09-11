package deal.module;

import java.util.Objects;

/**
 * The epic-owned runtime plan/evaluator ABI version (design source
 * {@code runtime-default-evaluators-and-construction-phases} D3):
 *
 * <pre>
 * DefaultRuntimeAbiVersion(version)
 * </pre>
 *
 * <p>One version covers the whole runtime plan/evaluator ABI: the entry
 * shape {@code {name, descriptor, optional, evaluator}}, the phase
 * contract (the four normal construction phases and the JSON phases
 * 0-6), and the construction entries. Any incompatible change to that
 * ABI bumps the constant; compatible changes keep it. The initial
 * version is {@code "1"}.</p>
 *
 * <p>The FFI cache identity consumes this version together with the
 * semantic-default contents and the evaluator-implementation contents
 * ({@code deal-v1.2-luajit-c-ffi-runtime-and-conformance} D1): changing
 * any of them invalidates the cache identity before mutation or reuse.
 * The version is distinct from the serializer version
 * ({@code DefaultSemanticSerializer}'s versioned canonical grammars) and
 * from the semantic and implementation digests
 * ({@code runtime-default-evaluators-and-construction-phases} D3).</p>
 *
 * <p>Immutable and deterministic; the constant is compiler-internal and
 * never appears in runtime descriptors, diagnostic type names, public
 * export keys, or source-language values.</p>
 *
 * @param version the version text (non-null, non-empty)
 */
public record DefaultRuntimeAbiVersion(String version) {

    /** The initial runtime plan/evaluator ABI version. */
    public static final DefaultRuntimeAbiVersion CURRENT =
        new DefaultRuntimeAbiVersion("1");

    public DefaultRuntimeAbiVersion {
        Objects.requireNonNull(version, "version");
        if (version.isEmpty()) {
            throw new IllegalArgumentException("version must not be empty");
        }
    }
}
