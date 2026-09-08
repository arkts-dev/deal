package deal.test.feature;

import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.descriptors.DescriptorAst;
import deal.descriptors.DescriptorParseResult;
import deal.descriptors.DescriptorSyntaxError;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The strict {@code V12FeatureMetadata} sidecar record and parser
 * (declarations page D12): metadata version 1 with the exact closed field
 * set, the conditional invocation/expectation/oracle rules, canonical
 * sync/async function-descriptor validation, and fail-closed unknown-field
 * rejection.
 *
 * <p>A sidecar is a JSON object; every field is validated with exact
 * types and shapes; duplicate JSON member names, malformed escapes,
 * unpaired surrogates, and trailing text are parsing failures (the
 * permissive-reading faults declarations page D5 rejects for the project
 * manifest apply to catalog metadata equally: silent defaults or
 * last-wins duplicates could rename a required backend or hide an
 * oracle). A failed parse yields a {@link CatalogFailure} naming the
 * sidecar path and the first defect; no partial record is published.</p>
 *
 * <p>The canonical descriptor service owns oracle-signature validation:
 * {@link CanonicalRuntimeTypeDescriptor#parse(String)} rejects legacy
 * spellings, nested-nullable ambiguities, and incomplete input, and the
 * record distinguishes the exact sync form from the exact async form
 * (the {@code async} prefix in the function-descriptor text). The
 * gate passes the descriptor text through to the production runtime
 * byte-for-byte (the runtime half stays the single completion-checking
 * authority).</p>
 *
 * <p>The optional {@code providerVariants} block pins the
 * changed-provider-identity row: the gate compiles the same unchanged
 * fixture twice per backend, differing only in the resolved provider's
 * content, and requires the pinned oracle result per variant — a
 * changed provider flows through the production compilation, never a
 * vacuous same-input rerun.</p>
 *
 * <p>The optional {@code manifestErrorFragment} pins the exact text a
 * malformed-manifest record's E2010 message must carry: the gate
 * publishes the record's {@code manifest-inject.json} bytes as the
 * project {@code deal.json} and requires that pinned fragment, so a
 * manifest-discovery E2010 can never masquerade as the exercised
 * manifest-schema failure (a code-only match would be vacuous).</p>
 */
public record V12FeatureMetadata(
        int version,
        FeatureId feature,
        String spec,
        String description,
        Expectation expected,
        List<String> backends,
        Invocation invocation,
        Oracle oracle,
        List<String> support,
        String linkedRecord,
        String manifestErrorFragment,
        ManifestPolicy manifestPolicy,
        NativeBlock nativePlan,
        IdentityArtifact identityArtifact,
        ProviderVariants providerVariants) {

    /** The exact sidecar member set (version 1). */
    static final Set<String> CLOSED_MEMBERS = Set.of(
        "version", "feature", "spec", "description", "expected",
        "backends", "invocation", "oracle", "support", "linkedRecord",
        "manifestErrorFragment", "manifestPolicy", "native",
        "identityArtifact", "providerVariants");

    /** The exact backend spellings (declarations page D12). */
    static final Set<String> BACKEND_NAMES = Set.of("luajit", "jvm");

    /** The exact invocation spellings (declarations page D12). */
    public enum Invocation {
        COMPILE_ONLY("compile-only"),
        DIRECT_MAIN("direct-main"),
        SYNTHETIC_MAIN("synthetic-main"),
        ASYNC_EXPORT("async-export");

        private final String text;

        Invocation(String text) {
            this.text = text;
        }

        public String canonicalText() {
            return text;
        }

        static Invocation parse(String text) {
            for (Invocation value : values()) {
                if (value.text.equals(text)) {
                    return value;
                }
            }
            return null;
        }
    }

    /**
     * The closed temporary-project manifest materialization policy:
     * {@code generated} (the gate writes the standard exact-v1.2
     * manifest), {@code inject} (the record's root
     * {@code manifest-inject.json} bytes are published verbatim as the
     * project {@code deal.json}, exercising the pinned manifest-schema
     * E2010), {@code missing} (no {@code deal.json} is written — the
     * zero-manifest discovery E2010), or {@code multiple} (every
     * {@code manifest-inject.json} in the record is published as
     * {@code deal.json} beside it — the multiple-ancestor-manifest
     * discovery E2010).
     */
    public enum ManifestPolicy {
        GENERATED("generated"),
        INJECT("inject"),
        MISSING("missing"),
        MULTIPLE("multiple");

        private final String text;

        ManifestPolicy(String text) {
            this.text = text;
        }

        public String canonicalText() {
            return text;
        }

        static ManifestPolicy parse(String text) {
            for (ManifestPolicy value : values()) {
                if (value.text.equals(text)) {
                    return value;
                }
            }
            return null;
        }
    }

    /**
     * One production extern-C native-library entry of a C_FFI record:
     * the manifest externals key ({@code importSpecifier}, e.g.
     * {@code native/math}), the record-relative declaration path
     * ({@code declaration}, ends {@code .d.deal}), and the library
     * reference ({@code library}) — one of the exact closed shapes:
     * <ul>
     *   <li>ends with {@code .c} and contains no {@code /}: a repository
     *       C fixture under {@code test/fixtures/} the gate compiles with
     *       contained GCC into a shared object and writes as the absolute
     *       path into the manifest (the gate-compiled absolute rows);</li>
     *   <li>starts with {@code /}: a pinned absolute path used verbatim
     *       (the valid-but-unloadable classification row);</li>
     *   <li>contains {@code /} without a leading one and ends with
     *       {@code .so}: a manifest-relative classification row — the
     *       gate compiles the same-stem repository {@code .c} fixture
     *       ({@code <stem>.c}) and places the shared object at exactly
     *       that manifest-relative path inside the temporary project,
     *       so the production classifier resolves and dlopens it (a
     *       library present at the relative location);</li>
     *   <li>contains no {@code /}: a bare loader-name classification
     *       row used verbatim (dlopen's loader search, never the
     *       process CWD).</li>
     * </ul>
     */
    public record NativeEntry(String importSpecifier, String declaration,
                              String library) {
        public NativeEntry {
            Objects.requireNonNull(importSpecifier, "importSpecifier");
            Objects.requireNonNull(declaration, "declaration");
            Objects.requireNonNull(library, "library");
        }
    }

    /** The ordered externals plan of one C_FFI record. */
    public record NativeBlock(List<NativeEntry> entries) {
        public NativeBlock {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

    /**
     * The optional public/private identity-separation pin (int32/bytes
     * page D5-D6, {@code strict-project-context-resolution-identity}
     * D4): the exact canonical public class descriptor every backend's
     * emitted artifacts must carry, while the private project
     * deployment identity (canonical manifest URI + validated
     * manifest-content digest) must never appear in artifact text.
     */
    public record IdentityArtifact(String classDescriptor) {
        public IdentityArtifact {
            java.util.Objects.requireNonNull(classDescriptor,
                "classDescriptor");
        }
    }

    /**
     * The optional changed-provider-identity plan (int32/bytes page
     * D5-D6): the gate compiles the record's unchanged fixture twice per
     * backend with the resolved provider replaced between compilations —
     * the provider path ({@code providerPath}, the fixture's import
     * spelling), the two record-relative provider sources
     * ({@code variantA}, {@code variantB}), and the pinned oracle result
     * each compilation must produce ({@code oracleResults}, exactly two
     * signed-int32 values).
     */
    public record ProviderVariants(String providerPath, String variantA,
                                   String variantB, List<Integer> oracleResults) {
        public ProviderVariants {
            Objects.requireNonNull(providerPath, "providerPath");
            Objects.requireNonNull(variantA, "variantA");
            Objects.requireNonNull(variantB, "variantB");
            oracleResults = List.copyOf(
                Objects.requireNonNull(oracleResults, "oracleResults"));
        }
    }

    /** The closed expectation shape (declarations page D12). */
    public sealed interface Expectation
            permits CompileOk, RuntimeOk, CompileError, RuntimeError {

        /** The exact expectation spellings. */
        String canonicalText();
    }

    /** {@code compile-ok}. */
    public record CompileOk() implements Expectation {
        public static final CompileOk INSTANCE = new CompileOk();

        @Override public String canonicalText() {
            return "compile-ok";
        }
    }

    /** {@code runtime-ok}. */
    public record RuntimeOk() implements Expectation {
        public static final RuntimeOk INSTANCE = new RuntimeOk();

        @Override public String canonicalText() {
            return "runtime-ok";
        }
    }

    /** {@code {"compile-error": code}}. */
    public record CompileError(String code) implements Expectation {
        public CompileError {
            Objects.requireNonNull(code, "code");
        }

        @Override public String canonicalText() {
            return "compile-error " + code;
        }
    }

    /** {@code {"runtime-error": code}}. */
    public record RuntimeError(String code) implements Expectation {
        public RuntimeError {
            Objects.requireNonNull(code, "code");
        }

        @Override public String canonicalText() {
            return "runtime-error " + code;
        }
    }

    /**
     * The optional oracle ({@code {exportName, functionDescriptor}}):
     * required for {@code synthetic-main} and {@code async-export},
     * forbidden for every other invocation.
     */
    public record Oracle(String exportName, String functionDescriptor) {
        public Oracle {
            Objects.requireNonNull(exportName, "exportName");
            Objects.requireNonNull(functionDescriptor, "functionDescriptor");
        }
    }

    /**
     * Parses and validates one sidecar. Every failure is a
     * {@link CatalogFailure}; the parser never throws for sidecar
     * content.
     *
     * @param sidecarPath the relative path used in failure messages
     * @param text        the strictly UTF-8-decoded sidecar text
     * @return the validated record, never null
     */
    public static V12FeatureMetadata parse(String sidecarPath, String text) {
        if (text == null) {
            return fail(sidecarPath, "sidecar text is null");
        }
        final JsonValue root;
        try {
            root = JsonReader.parse(text);
        } catch (JsonReader.JsonFailure e) {
            return fail(sidecarPath, e.getMessage());
        }
        if (!(root instanceof JsonValue.ObjectVal obj)) {
            return fail(sidecarPath,
                "sidecar top level must be a JSON object, got " + root.kindName());
        }
        Map<String, JsonValue> fields = obj.members();
        for (String key : fields.keySet()) {
            if (!CLOSED_MEMBERS.contains(key)) {
                return fail(sidecarPath, "unknown field '" + key
                    + "' (closed member set: " + CLOSED_MEMBERS + ")");
            }
        }

        JsonValue versionValue = fields.get("version");
        if (!(versionValue instanceof JsonValue.NumberVal num)
                || !num.sourceText().equals("1")) {
            return fail(sidecarPath,
                "version must be exactly the JSON number 1");
        }

        FeatureId feature = FeatureId.parseCanonical(
            requireString(fields, sidecarPath, "feature"));
        if (feature == null) {
            return fail(sidecarPath, "unknown feature id '"
                + requireString(fields, sidecarPath, "feature")
                + "' (canonical set: " + FeatureId.canonicalNames() + ")");
        }
        String spec = requireString(fields, sidecarPath, "spec");
        String description = requireString(fields, sidecarPath, "description");

        Expectation expected = parseExpectation(sidecarPath,
            fields.get("expected"));
        if (expected == null) {
            return fail(sidecarPath, "malformed expected field");
        }

        List<String> backends = parseBackends(sidecarPath,
            fields.get("backends"));
        if (backends == null) {
            return fail(sidecarPath, "malformed backends field");
        }

        Invocation invocation = Invocation.parse(
            requireString(fields, sidecarPath, "invocation"));
        if (invocation == null) {
            return fail(sidecarPath, "invocation must be one of "
                + "compile-only|direct-main|synthetic-main|async-export");
        }

        Oracle oracle = parseOracle(sidecarPath, fields.get("oracle"));
        if (oracle == null && fields.get("oracle") != null) {
            return fail(sidecarPath, "malformed oracle field");
        }

        List<String> support = parseSupport(sidecarPath,
            fields.get("support"));
        if (support == null) {
            return fail(sidecarPath, "malformed support field");
        }

        String linkedRecord = null;
        if (fields.get("linkedRecord") != null) {
            linkedRecord = requireString(fields, sidecarPath, "linkedRecord");
        }

        String manifestErrorFragment = null;
        if (fields.get("manifestErrorFragment") != null) {
            manifestErrorFragment = requireString(fields, sidecarPath,
                "manifestErrorFragment");
            if (manifestErrorFragment.isEmpty()) {
                return fail(sidecarPath,
                    "field 'manifestErrorFragment' must be a non-empty string");
            }
        }

        ManifestPolicy manifestPolicy = ManifestPolicy.GENERATED;
        if (fields.get("manifestPolicy") != null) {
            manifestPolicy = ManifestPolicy.parse(
                requireString(fields, sidecarPath, "manifestPolicy"));
            if (manifestPolicy == null) {
                return fail(sidecarPath, "field 'manifestPolicy' must be one of "
                    + "generated|inject|missing|multiple");
            }
        }

        NativeBlock nativePlan = null;
        if (fields.get("native") != null) {
            nativePlan = parseNative(sidecarPath, fields.get("native"));
            if (nativePlan == null) {
                return fail(sidecarPath, "malformed native field");
            }
        }

        IdentityArtifact identityArtifact = null;
        if (fields.get("identityArtifact") != null) {
            identityArtifact = parseIdentityArtifact(sidecarPath,
                fields.get("identityArtifact"));
            if (identityArtifact == null) {
                return fail(sidecarPath, "malformed identityArtifact field");
            }
        }

        ProviderVariants providerVariants = null;
        if (fields.get("providerVariants") != null) {
            providerVariants = parseProviderVariants(sidecarPath,
                fields.get("providerVariants"));
            if (providerVariants == null) {
                return fail(sidecarPath, "malformed providerVariants field");
            }
        }

        V12FeatureMetadata record = new V12FeatureMetadata(1, feature, spec,
            description, expected, backends, invocation, oracle, support,
            linkedRecord, manifestErrorFragment, manifestPolicy, nativePlan,
            identityArtifact, providerVariants);

        String violation = validateConditionalRules(record);
        if (violation != null) {
            return fail(sidecarPath, violation);
        }
        return record;
    }

    /**
     * The exact conditional rules (declarations page D12):
     * compile outcomes require {@code compile-only} and forbid an oracle;
     * runtime outcomes forbid {@code compile-only}; {@code direct-main}
     * forbids an oracle; {@code synthetic-main} requires one oracle with a
     * canonical sync {@code ()->R}; {@code async-export} requires one
     * oracle with a canonical {@code async()->R}; a pinned
     * {@code manifestErrorFragment} requires a compile-error expectation
     * (the fragment pins the manifest-schema E2010 text the gate must
     * observe). A record outside these rules is a catalog failure before
     * any compilation.
     */
    static String validateConditionalRules(V12FeatureMetadata record) {
        Invocation invocation = record.invocation();
        boolean isCompileOutcome = record.expected() instanceof CompileOk
            || record.expected() instanceof CompileError;
        boolean isRuntimeOutcome = record.expected() instanceof RuntimeOk
            || record.expected() instanceof RuntimeError;

        if (isCompileOutcome && invocation != Invocation.COMPILE_ONLY) {
            return "a compile outcome requires invocation compile-only";
        }
        if (isRuntimeOutcome && invocation == Invocation.COMPILE_ONLY) {
            return "a runtime outcome forbids invocation compile-only";
        }
        if (isCompileOutcome && record.oracle() != null) {
            return "a compile outcome forbids an oracle";
        }
        if (record.manifestErrorFragment() != null
                && !(record.expected() instanceof CompileError)) {
            return "manifestErrorFragment requires a compile-error expectation";
        }
        switch (record.manifestPolicy()) {
            case GENERATED -> {
                if (record.manifestErrorFragment() != null) {
                    return "manifestPolicy generated forbids a "
                        + "manifestErrorFragment pin";
                }
            }
            case INJECT, MISSING, MULTIPLE -> {
                if (!(record.expected() instanceof CompileError)
                        || record.manifestErrorFragment() == null) {
                    return "manifestPolicy "
                        + record.manifestPolicy().canonicalText()
                        + " requires a compile-error expectation with a "
                        + "pinned manifestErrorFragment";
                }
            }
        }
        boolean isCffi = record.feature() == FeatureId.C_FFI;
        if (isCffi && record.nativePlan() == null) {
            return "feature c-ffi requires a native block (externals plan)";
        }
        if (!isCffi && record.nativePlan() != null) {
            return "only feature c-ffi records may carry a native block";
        }
        // identityArtifact inspects emitted artifacts, so it needs a
        // successful compile; every non-GENERATED manifest policy
        // already forces a compile-error expectation, so the GENERATED
        // requirement is enforced transitively through this one rule.
        if (record.identityArtifact() != null
                && !(record.expected() instanceof CompileOk)
                && !(record.expected() instanceof RuntimeOk)) {
            return "identityArtifact requires a compile-ok or runtime-ok "
                + "expectation (artifact inspection needs a successful "
                + "compile, and every malformed-manifest policy forces a "
                + "compile-error)";
        }
        if (record.providerVariants() != null) {
            if (record.feature() != FeatureId.BYTES_DEFAULTS) {
                return "providerVariants is the changed-provider row of "
                    + "feature bytes-defaults only";
            }
            if (!(record.expected() instanceof RuntimeOk)) {
                return "providerVariants requires a runtime-ok expectation";
            }
            if (invocation != Invocation.SYNTHETIC_MAIN) {
                return "providerVariants requires synthetic-main invocation";
            }
            if (record.oracle() == null
                    || !record.oracle().functionDescriptor().equals("()->int")) {
                return "providerVariants requires a canonical ()->int oracle";
            }
            if (record.providerVariants().oracleResults().size() != 2) {
                return "providerVariants requires exactly two pinned oracle "
                    + "results";
            }
        }
        switch (invocation) {
            case COMPILE_ONLY, DIRECT_MAIN -> {
                if (record.oracle() != null) {
                    return "invocation " + invocation.canonicalText()
                        + " forbids an oracle";
                }
            }
            case SYNTHETIC_MAIN, ASYNC_EXPORT -> {
                if (record.oracle() == null) {
                    return "invocation " + invocation.canonicalText()
                        + " requires an oracle";
                }
                DescriptorParseResult parsed = CanonicalRuntimeTypeDescriptor
                    .parse(record.oracle().functionDescriptor());
                if (parsed instanceof DescriptorSyntaxError error) {
                    return "oracle function descriptor is not canonical: "
                        + error.message();
                }
                if (!(parsed instanceof DescriptorAst)) {
                    return "oracle function descriptor did not parse to an AST";
                }
                String descriptor = record.oracle().functionDescriptor();
                if (invocation == Invocation.SYNTHETIC_MAIN
                        && !descriptor.startsWith("()->")) {
                    return "synthetic-main requires a canonical sync ()->R "
                        + "oracle descriptor, got " + descriptor;
                }
                if (invocation == Invocation.ASYNC_EXPORT
                        && !descriptor.startsWith("async()->")) {
                    return "async-export requires a canonical async()->R "
                        + "oracle descriptor, got " + descriptor;
                }
            }
        }
        return null;
    }

    /**
     * The closed {@code identityArtifact} block: an object with exactly
     * one member {@code classDescriptor} — the pinned canonical public
     * class descriptor (a non-empty string starting with {@code @}) that
     * every backend's emitted artifact set must carry, with the private
     * deployment identity never appearing in artifact text.
     */
    private static IdentityArtifact parseIdentityArtifact(String path,
                                                          JsonValue value) {
        if (!(value instanceof JsonValue.ObjectVal obj)) {
            return null;
        }
        Map<String, JsonValue> members = obj.members();
        if (members.size() != 1
                || !(members.get("classDescriptor")
                    instanceof JsonValue.StringVal descriptor)) {
            return null;
        }
        if (descriptor.value().isEmpty()
                || !descriptor.value().startsWith("@")) {
            fail(path, "identityArtifact classDescriptor must be a "
                + "non-empty canonical @-prefixed class descriptor");
            return null;
        }
        return new IdentityArtifact(descriptor.value());
    }

    /**
     * The closed {@code providerVariants} block: an object with exactly
     * the members {@code providerPath}, {@code variantA},
     * {@code variantB} (non-empty strings) and {@code oracleResults} (a
     * JSON array of exactly two integers).
     */
    private static ProviderVariants parseProviderVariants(String path,
                                                          JsonValue value) {
        if (!(value instanceof JsonValue.ObjectVal obj)) {
            return null;
        }
        Map<String, JsonValue> members = obj.members();
        if (members.size() != 4
                || !(members.get("providerPath")
                    instanceof JsonValue.StringVal providerPath)
                || !(members.get("variantA")
                    instanceof JsonValue.StringVal variantA)
                || !(members.get("variantB")
                    instanceof JsonValue.StringVal variantB)
                || !(members.get("oracleResults")
                    instanceof JsonValue.ArrayVal results)) {
            return null;
        }
        if (providerPath.value().isEmpty()) {
            fail(path, "providerVariants providerPath must be a non-empty "
                + "string");
            return null;
        }
        if (variantA.value().isEmpty() || variantB.value().isEmpty()) {
            fail(path, "providerVariants variantA and variantB must be "
                + "non-empty strings");
            return null;
        }
        List<Integer> oracleResults = new ArrayList<>();
        for (JsonValue element : results.elements()) {
            if (!(element instanceof JsonValue.NumberVal num)
                    || num.sourceText().contains(".")
                    || num.sourceText().contains("e")
                    || num.sourceText().contains("E")) {
                fail(path, "providerVariants oracleResults must be JSON "
                    + "integers");
                return null;
            }
            try {
                oracleResults.add(Integer.parseInt(num.sourceText()));
            } catch (NumberFormatException e) {
                fail(path, "providerVariants oracleResults must be signed "
                    + "32-bit integers");
                return null;
            }
        }
        if (oracleResults.size() != 2) {
            fail(path, "providerVariants oracleResults must carry exactly "
                + "two integers");
            return null;
        }
        return new ProviderVariants(providerPath.value(), variantA.value(),
            variantB.value(), oracleResults);
    }

    /**
     * The closed {@code native} block: an object with exactly one member
     * {@code entries} — a non-empty array of entry objects with exactly
     * the members {@code importSpecifier} (non-empty string containing
     * {@code /}), {@code declaration} (string ending {@code .d.deal}),
     * and {@code library} (non-empty string; a {@code .c} suffix selects
     * the gate-compiled repository C fixture, any other value must be an
     * absolute path used verbatim).
     */
    private static NativeBlock parseNative(String path, JsonValue value) {
        if (!(value instanceof JsonValue.ObjectVal obj)) {
            return null;
        }
        Map<String, JsonValue> members = obj.members();
        if (members.size() != 1
                || !(members.get("entries") instanceof JsonValue.ArrayVal arr)
                || arr.elements().isEmpty()) {
            return null;
        }
        List<NativeEntry> entries = new ArrayList<>();
        for (JsonValue element : arr.elements()) {
            if (!(element instanceof JsonValue.ObjectVal entryObj)) {
                return null;
            }
            Map<String, JsonValue> entryMembers = entryObj.members();
            if (entryMembers.size() != 3
                    || !(entryMembers.get("importSpecifier")
                        instanceof JsonValue.StringVal specifier)
                    || !(entryMembers.get("declaration")
                        instanceof JsonValue.StringVal declaration)
                    || !(entryMembers.get("library")
                        instanceof JsonValue.StringVal library)) {
                return null;
            }
            if (specifier.value().isEmpty()
                    || !specifier.value().contains("/")) {
                fail(path, "native entry importSpecifier must be a "
                    + "non-empty import path containing '/'");
                return null;
            }
            if (!declaration.value().endsWith(".d.deal")) {
                fail(path, "native entry declaration must be a "
                    + "record-relative .d.deal path, got '"
                    + declaration.value() + "'");
                return null;
            }
            if (library.value().isEmpty()) {
                fail(path, "native entry library must be a non-empty "
                    + "string");
                return null;
            }
            boolean fixture = library.value().endsWith(".c")
                && !library.value().contains("/");
            boolean absolute = library.value().startsWith("/");
            boolean manifestRelative = !absolute
                && library.value().contains("/");
            if (library.value().endsWith(".c")
                    && library.value().contains("/")) {
                fail(path, "native entry library fixture names must not "
                    + "contain '/' (repository C fixtures live directly "
                    + "under test/fixtures), got '" + library.value() + "'");
                return null;
            }
            if (manifestRelative && !library.value().endsWith(".so")) {
                fail(path, "native entry library manifest-relative paths "
                    + "must end with .so (the same-stem repository C "
                    + "fixture), got '" + library.value() + "'");
                return null;
            }
            entries.add(new NativeEntry(specifier.value(),
                declaration.value(), library.value()));
        }
        return new NativeBlock(entries);
    }

    private static Expectation parseExpectation(String path, JsonValue value) {
        if (value instanceof JsonValue.StringVal s) {
            return switch (s.value()) {
                case "compile-ok" -> CompileOk.INSTANCE;
                case "runtime-ok" -> RuntimeOk.INSTANCE;
                default -> null;
            };
        }
        if (value instanceof JsonValue.ObjectVal obj) {
            Map<String, JsonValue> members = obj.members();
            if (members.size() != 1) {
                return null;
            }
            Map.Entry<String, JsonValue> only =
                members.entrySet().iterator().next();
            if (!(only.getValue() instanceof JsonValue.StringVal code)) {
                return null;
            }
            return switch (only.getKey()) {
                case "compile-error" -> new CompileError(code.value());
                case "runtime-error" -> new RuntimeError(code.value());
                default -> null;
            };
        }
        return null;
    }

    private static List<String> parseBackends(String path, JsonValue value) {
        if (!(value instanceof JsonValue.ArrayVal arr) || arr.elements().isEmpty()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (JsonValue element : arr.elements()) {
            if (!(element instanceof JsonValue.StringVal s)) {
                return null;
            }
            if (!BACKEND_NAMES.contains(s.value()) || result.contains(s.value())) {
                return null;
            }
            result.add(s.value());
        }
        return List.copyOf(result);
    }

    private static Oracle parseOracle(String path, JsonValue value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof JsonValue.ObjectVal obj)) {
            return null;
        }
        Map<String, JsonValue> members = obj.members();
        if (members.size() != 2
                || !(members.get("exportName") instanceof JsonValue.StringVal name)
                || !(members.get("functionDescriptor")
                    instanceof JsonValue.StringVal descriptor)) {
            return null;
        }
        return new Oracle(name.value(), descriptor.value());
    }

    private static List<String> parseSupport(String path, JsonValue value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof JsonValue.ArrayVal arr)) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (JsonValue element : arr.elements()) {
            if (!(element instanceof JsonValue.StringVal s)) {
                return null;
            }
            result.add(s.value());
        }
        return List.copyOf(result);
    }

    private static String requireString(Map<String, JsonValue> fields,
                                        String path, String key) {
        JsonValue value = fields.get(key);
        if (value instanceof JsonValue.StringVal s) {
            return s.value();
        }
        fail(path, "field '" + key + "' must be a JSON string");
        return null; // unreachable: fail throws
    }

    private static V12FeatureMetadata fail(String path, String reason) {
        throw new CatalogFailure("sidecar " + path + ": " + reason);
    }

    /**
     * A catalog-sidecar validation failure: never a DEAL diagnostic,
     * never a skip, never a partial record.
     */
    public static final class CatalogFailure extends RuntimeException {
        public CatalogFailure(String message) {
            super(message);
        }
    }

    /**
     * A minimal strict conventional-JSON reader (objects, arrays,
     * strings with the RFC 8259 escape set including surrogate-pair
     * combination, conventional decimal numbers, booleans, null).
     * Duplicate member names, raw control characters, unpaired
     * surrogates, malformed numbers, and trailing text fail closed.
     */
    static final class JsonReader {

        static final class JsonFailure extends RuntimeException {
            JsonFailure(String message) {
                super(message);
            }
        }

        private JsonReader() {
        }

        static JsonValue parse(String text) {
            Parser parser = new Parser(text);
            JsonValue value = parser.parseValue();
            parser.skipWhitespace();
            if (!parser.atEnd()) {
                throw new JsonFailure("trailing content at offset " + parser.pos);
            }
            return value;
        }

        private static final class Parser {
            private final String text;
            private int pos;

            Parser(String text) {
                this.text = text;
            }

            boolean atEnd() {
                return pos >= text.length();
            }

            void skipWhitespace() {
                while (!atEnd()) {
                    char c = text.charAt(pos);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                        pos++;
                    } else {
                        return;
                    }
                }
            }

            JsonFailure err(String reason) {
                return new JsonFailure(reason + " at offset " + pos);
            }

            JsonValue parseValue() {
                skipWhitespace();
                if (atEnd()) {
                    throw err("unexpected end of input");
                }
                char c = text.charAt(pos);
                return switch (c) {
                    case '{' -> parseObject();
                    case '[' -> parseArray();
                    case '"' -> new JsonValue.StringVal(parseString());
                    case 't' -> parseLiteral("true", new JsonValue.BoolVal(true));
                    case 'f' -> parseLiteral("false", new JsonValue.BoolVal(false));
                    case 'n' -> parseLiteral("null", new JsonValue.NullVal());
                    default -> {
                        if (c == '-' || (c >= '0' && c <= '9')) {
                            yield parseNumber();
                        }
                        throw err("unexpected character");
                    }
                };
            }

            private JsonValue parseLiteral(String word, JsonValue value) {
                if (!text.startsWith(word, pos)) {
                    throw err("malformed literal");
                }
                pos += word.length();
                if (!atEnd() && !delimiter(text.charAt(pos))) {
                    throw err("malformed literal");
                }
                return value;
            }

            private static boolean delimiter(char c) {
                return c == ' ' || c == '\t' || c == '\n' || c == '\r'
                    || c == ',' || c == ']' || c == '}';
            }

            private JsonValue.ObjectVal parseObject() {
                pos++;
                Map<String, JsonValue> members = new LinkedHashMap<>();
                skipWhitespace();
                if (!atEnd() && text.charAt(pos) == '}') {
                    pos++;
                    return new JsonValue.ObjectVal(members);
                }
                while (true) {
                    skipWhitespace();
                    if (atEnd() || text.charAt(pos) != '"') {
                        throw err("expected a string member name");
                    }
                    String key = parseString();
                    if (members.containsKey(key)) {
                        throw err("duplicate member '" + key + "'");
                    }
                    skipWhitespace();
                    if (atEnd() || text.charAt(pos) != ':') {
                        throw err("expected ':' after member name");
                    }
                    pos++;
                    members.put(key, parseValue());
                    skipWhitespace();
                    if (atEnd()) {
                        throw err("unterminated object");
                    }
                    char c = text.charAt(pos);
                    if (c == ',') {
                        pos++;
                        continue;
                    }
                    if (c == '}') {
                        pos++;
                        return new JsonValue.ObjectVal(members);
                    }
                    throw err("expected ',' or '}'");
                }
            }

            private JsonValue.ArrayVal parseArray() {
                pos++;
                List<JsonValue> elements = new ArrayList<>();
                skipWhitespace();
                if (!atEnd() && text.charAt(pos) == ']') {
                    pos++;
                    return new JsonValue.ArrayVal(elements);
                }
                while (true) {
                    skipWhitespace();
                    elements.add(parseValue());
                    skipWhitespace();
                    if (atEnd()) {
                        throw err("unterminated array");
                    }
                    char c = text.charAt(pos);
                    if (c == ',') {
                        pos++;
                        continue;
                    }
                    if (c == ']') {
                        pos++;
                        return new JsonValue.ArrayVal(elements);
                    }
                    throw err("expected ',' or ']'");
                }
            }

            private String parseString() {
                pos++;
                StringBuilder sb = new StringBuilder();
                while (true) {
                    if (atEnd()) {
                        throw err("unterminated string");
                    }
                    char c = text.charAt(pos++);
                    if (c == '"') {
                        return sb.toString();
                    }
                    if (c == '\\') {
                        if (atEnd()) {
                            throw err("unterminated escape");
                        }
                        char e = text.charAt(pos++);
                        switch (e) {
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case '/' -> sb.append('/');
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case 'u' -> sb.append(parseUnicodeEscape());
                            default -> throw err("invalid escape");
                        }
                    } else if (c < 0x20) {
                        throw err("raw control character in string");
                    } else {
                        sb.append(c);
                    }
                }
            }

            private String parseUnicodeEscape() {
                if (pos + 4 > text.length()) {
                    throw err("truncated \\u escape");
                }
                int value = readHex4();
                if (value >= 0xD800 && value <= 0xDBFF) {
                    if (pos + 6 > text.length() || text.charAt(pos) != '\\'
                            || text.charAt(pos + 1) != 'u') {
                        throw err("unpaired high surrogate");
                    }
                    pos += 2;
                    int low = readHex4();
                    if (low < 0xDC00 || low > 0xDFFF) {
                        throw err("unpaired high surrogate");
                    }
                    int codePoint = 0x10000 + ((value - 0xD800) << 10)
                        + (low - 0xDC00);
                    return new String(Character.toChars(codePoint));
                }
                if (value >= 0xDC00 && value <= 0xDFFF) {
                    throw err("unpaired low surrogate");
                }
                return String.valueOf((char) value);
            }

            private int readHex4() {
                int value = 0;
                for (int i = 0; i < 4; i++) {
                    if (pos >= text.length()) {
                        throw err("truncated \\u escape");
                    }
                    int digit = Character.digit(text.charAt(pos++), 16);
                    if (digit < 0) {
                        throw err("non-hex digit in \\u escape");
                    }
                    value = (value << 4) | digit;
                }
                return value;
            }

            private JsonValue.NumberVal parseNumber() {
                int start = pos;
                if (!atEnd() && text.charAt(pos) == '-') {
                    pos++;
                }
                if (atEnd()) {
                    throw err("malformed number");
                }
                char c = text.charAt(pos);
                if (c == '0') {
                    pos++;
                } else if (c >= '1' && c <= '9') {
                    while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                        pos++;
                    }
                } else {
                    throw err("malformed number");
                }
                if (!atEnd() && text.charAt(pos) == '.') {
                    pos++;
                    if (atEnd() || !Character.isDigit(text.charAt(pos))) {
                        throw err("malformed number fraction");
                    }
                    while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                        pos++;
                    }
                }
                if (!atEnd() && (text.charAt(pos) == 'e'
                        || text.charAt(pos) == 'E')) {
                    pos++;
                    if (!atEnd() && (text.charAt(pos) == '+'
                            || text.charAt(pos) == '-')) {
                        pos++;
                    }
                    if (atEnd() || !Character.isDigit(text.charAt(pos))) {
                        throw err("malformed number exponent");
                    }
                    while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                        pos++;
                    }
                }
                return new JsonValue.NumberVal(text.substring(start, pos));
            }
        }
    }

    /** The closed JSON value model of the sidecar reader. */
    sealed interface JsonValue permits JsonValue.ObjectVal, JsonValue.ArrayVal,
            JsonValue.StringVal, JsonValue.NumberVal, JsonValue.BoolVal,
            JsonValue.NullVal {

        default String kindName() {
            return switch (this) {
                case ObjectVal ignored -> "object";
                case ArrayVal ignored -> "array";
                case StringVal ignored -> "string";
                case NumberVal ignored -> "number";
                case BoolVal ignored -> "boolean";
                case NullVal ignored -> "null";
            };
        }

        record ObjectVal(Map<String, JsonValue> members) implements JsonValue {
            public ObjectVal {
                members = Map.copyOf(new LinkedHashMap<>(members));
            }
        }

        record ArrayVal(List<JsonValue> elements) implements JsonValue {
            public ArrayVal {
                elements = List.copyOf(elements);
            }
        }

        record StringVal(String value) implements JsonValue {
            public StringVal {
                Objects.requireNonNull(value, "value");
            }
        }

        record NumberVal(String sourceText) implements JsonValue {
            public NumberVal {
                Objects.requireNonNull(sourceText, "sourceText");
            }
        }

        record BoolVal(boolean value) implements JsonValue {
        }

        record NullVal() implements JsonValue {
        }
    }
}
