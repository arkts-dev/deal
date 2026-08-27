package deal.project;

import deal.source.SourceScalarRange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The post-walk field-schema validation of the strict v1.2 manifest
 * (design source {@code strict-project-context-resolution-identity} D2,
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D5).
 *
 * <p>This validator runs only after {@link StrictManifestParser}'s strict
 * walk found no structural defect. It consumes the strictly decoded
 * {@link StrictJsonDocument} and enforces the pinned post-walk
 * validations in canonical order, independent of member document order:
 *
 * <ol>
 *   <li>{@code languageVersion} — required, exactly the JSON string
 *       {@code "1.2"}; missing → failure at the enclosing root-object
 *       range; empty input → the pinned document-start zero-length SOURCE
 *       range {@code (1,1,1,1,0,0)}; wrong value/type → the member value
 *       range;</li>
 *   <li>{@code backend} — when present exactly {@code "luajit"} or
 *       {@code "jvm"} (case/whitespace variants, {@code lua}, {@code js},
 *       empty, and unknown values fail); absence defaults to
 *       {@code "luajit"};</li>
 *   <li>{@code moduleRoots} — value-level only: an array of non-empty,
 *       scalar-valid, NUL-free, host-representable <b>relative</b> strings
 *       (leading {@code /} fails); wrong field/member types and defective
 *       members fail at the member value range; absence is the empty
 *       list; no implicit root;</li>
 *   <li>{@code output} — a non-empty, scalar-valid, NUL-free string
 *       (value-level only; classification and conversion are the output
 *       resolver's duty);</li>
 *   <li>{@code stdlib} — when present exactly {@code "1.2"}, otherwise
 *       defaults to {@code "1.2"};</li>
 *   <li>{@code dependencies} — when present must be a JSON object;
 *       contents are preserved opaquely (structural strictness was
 *       already enforced by the walk);</li>
 *   <li>{@code externals} — must be an object (the legacy list form and
 *       any other shape fail at the {@code externals} value range); each
 *       entry in member order requires a non-empty, scalar-valid,
 *       NUL-free string {@code declaration} ending in {@code .d.deal}
 *       (leading {@code /} is absolute, else manifest-relative; lexical
 *       {@code ..} is permitted) and an optional string
 *       {@code nativeLibrary} classified per
 *       {@link NativeLibraryRef.Kind}.</li>
 * </ol>
 *
 * <p>Content-only: no filesystem access, no byte-level decoding, no
 * {@code deal.diagnostics} dependency. A failure is returned as a
 * {@link Failure} with a message and an anchor range; the caller
 * (StrictManifestParser, which owns the manifest path) converts it into
 * the single E2010 diagnostic.
 */
public final class ProjectConfigValidator {

    private ProjectConfigValidator() {
    }

    /** The pinned document-start zero-length SOURCE range for empty input. */
    private static final SourceScalarRange DOCUMENT_START =
        new SourceScalarRange(1, 1, 1, 1, 0, 0);

    /**
     * The validation outcome: exactly one of {@code manifest} and
     * {@code failure} is non-null.
     */
    public record Outcome(ProjectManifest manifest, Failure failure) {
        public Outcome {
            if ((manifest == null) == (failure == null)) {
                throw new IllegalArgumentException(
                    "exactly one of manifest and failure must be non-null");
            }
        }
    }

    /** A post-walk validation failure: a message plus an anchor range. */
    public record Failure(String message, SourceScalarRange anchorRange) {
    }

    /**
     * Validates the strictly walked document into a {@link ProjectManifest}.
     * Deterministic: the result is a pure function of the document.
     *
     * @param document the strict walk's model (root may be null for empty
     *                 input)
     * @return the manifest, or the first failure in the pinned canonical
     *         order
     */
    public static Outcome validate(StrictJsonDocument document) {
        // 1. languageVersion — required, exactly the JSON string "1.2".
        if (document.root() == null) {
            return failure("deal.json: missing required member 'languageVersion'",
                DOCUMENT_START);
        }
        if (!(document.root() instanceof StrictJsonValue.ObjectVal rootObj)) {
            return failure("deal.json: manifest root must be a JSON object",
                rangeOf(document, document.root()));
        }
        Map<String, StrictJsonValue> rootMembers = rootObj.members();
        StrictJsonValue languageVersionValue = rootMembers.get("languageVersion");
        if (languageVersionValue == null) {
            return failure("deal.json: missing required member 'languageVersion'",
                rangeOf(document, rootObj));
        }
        if (!(languageVersionValue instanceof StrictJsonValue.StringVal languageVersion)) {
            return failure("deal.json: 'languageVersion' must be the JSON string \"1.2\"",
                rangeOf(document, languageVersionValue));
        }
        if (!"1.2".equals(languageVersion.value())) {
            return failure("deal.json: 'languageVersion' must be exactly \"1.2\", got: '"
                    + languageVersion.value() + "'",
                rangeOf(document, languageVersionValue));
        }

        // 2. backend — exactly "luajit" | "jvm"; absence defaults to luajit.
        String backend = "luajit";
        StrictJsonValue backendValue = rootMembers.get("backend");
        if (backendValue != null) {
            if (!(backendValue instanceof StrictJsonValue.StringVal backendString)) {
                return failure("deal.json: 'backend' must be a string",
                    rangeOf(document, backendValue));
            }
            if (!backendString.value().equals("luajit")
                    && !backendString.value().equals("jvm")) {
                return failure("deal.json: unsupported backend '" + backendString.value()
                        + "'. Supported backends: 'luajit', 'jvm'",
                    rangeOf(document, backendValue));
            }
            backend = backendString.value();
        }

        // 3. moduleRoots — value-level only; absence is the empty list.
        List<ManifestString> moduleRoots = List.of();
        StrictJsonValue moduleRootsValue = rootMembers.get("moduleRoots");
        if (moduleRootsValue != null) {
            if (!(moduleRootsValue instanceof StrictJsonValue.ArrayVal moduleRootsArray)) {
                return failure("deal.json: 'moduleRoots' must be an array of relative strings",
                    rangeOf(document, moduleRootsValue));
            }
            List<ManifestString> entries = new ArrayList<>();
            for (StrictJsonValue element : moduleRootsArray.elements()) {
                SourceScalarRange elementRange = rangeOf(document, element);
                if (!(element instanceof StrictJsonValue.StringVal elementString)) {
                    return failure("deal.json: 'moduleRoots' entries must be strings",
                        elementRange);
                }
                Failure textFailure = pathTextFailure("'moduleRoots' entry",
                    elementString.value(), elementRange);
                if (textFailure != null) {
                    return new Outcome(null, textFailure);
                }
                if (elementString.value().startsWith("/")) {
                    return failure("deal.json: 'moduleRoots' entries must be relative paths,"
                            + " got: '" + elementString.value() + "'",
                        elementRange);
                }
                entries.add(new ManifestString(elementString.value(), elementRange));
            }
            moduleRoots = entries;
        }

        // 4. output — a non-empty scalar-valid NUL-free string (value-level only).
        ManifestString output = null;
        StrictJsonValue outputValue = rootMembers.get("output");
        if (outputValue != null) {
            if (!(outputValue instanceof StrictJsonValue.StringVal outputString)) {
                return failure("deal.json: 'output' must be a non-empty string",
                    rangeOf(document, outputValue));
            }
            Failure textFailure = pathTextFailure("'output'", outputString.value(),
                rangeOf(document, outputValue));
            if (textFailure != null) {
                return new Outcome(null, textFailure);
            }
            output = new ManifestString(outputString.value(), rangeOf(document, outputValue));
        }

        // 5. stdlib — exactly "1.2"; absence defaults to "1.2".
        String stdlib = "1.2";
        StrictJsonValue stdlibValue = rootMembers.get("stdlib");
        if (stdlibValue != null) {
            if (!(stdlibValue instanceof StrictJsonValue.StringVal stdlibString)) {
                return failure("deal.json: 'stdlib' must be a string",
                    rangeOf(document, stdlibValue));
            }
            if (!"1.2".equals(stdlibString.value())) {
                return failure("deal.json: 'stdlib' must be exactly \"1.2\", got: '"
                        + stdlibString.value() + "'",
                    rangeOf(document, stdlibValue));
            }
            stdlib = stdlibString.value();
        }

        // 6. dependencies — when present must be a JSON object; contents opaque.
        StrictJsonValue.ObjectVal dependencies = null;
        StrictJsonValue dependenciesValue = rootMembers.get("dependencies");
        if (dependenciesValue != null) {
            if (!(dependenciesValue instanceof StrictJsonValue.ObjectVal dependenciesObject)) {
                return failure("deal.json: 'dependencies' must be a JSON object",
                    rangeOf(document, dependenciesValue));
            }
            dependencies = dependenciesObject;
        }

        // 7. externals — must be an object; entries in member order.
        Map<String, ExternalEntrySpec> externals = new LinkedHashMap<>();
        StrictJsonValue externalsValue = rootMembers.get("externals");
        if (externalsValue != null) {
            if (!(externalsValue instanceof StrictJsonValue.ObjectVal externalsObject)) {
                return failure("deal.json: 'externals' must be an object mapping import"
                        + " specifiers to entry objects",
                    rangeOf(document, externalsValue));
            }
            for (Map.Entry<String, StrictJsonValue> entry : externalsObject.members().entrySet()) {
                String specifier = entry.getKey();
                StrictJsonValue entryValue = entry.getValue();
                SourceScalarRange entryRange = rangeOf(document, entryValue);
                if (!(entryValue instanceof StrictJsonValue.ObjectVal entryObject)) {
                    return failure("deal.json: externals entry '" + specifier
                            + "' must be an object with a 'declaration' string",
                        entryRange);
                }
                Map<String, StrictJsonValue> entryMembers = entryObject.members();

                StrictJsonValue declarationValue = entryMembers.get("declaration");
                if (declarationValue == null) {
                    return failure("deal.json: externals entry '" + specifier
                            + "' is missing required member 'declaration'",
                        entryRange);
                }
                SourceScalarRange declarationRange = rangeOf(document, declarationValue);
                if (!(declarationValue instanceof StrictJsonValue.StringVal declarationString)) {
                    return failure("deal.json: externals entry '" + specifier
                            + "': 'declaration' must be a string",
                        declarationRange);
                }
                Failure declarationFailure = pathTextFailure("externals entry '"
                        + specifier + "' declaration",
                    declarationString.value(), declarationRange);
                if (declarationFailure != null) {
                    return new Outcome(null, declarationFailure);
                }
                if (!declarationString.value().endsWith(".d.deal")) {
                    return failure("deal.json: externals entry '" + specifier
                            + "': 'declaration' must be a host declaration file (.d.deal), got: '"
                            + declarationString.value() + "'",
                        declarationRange);
                }

                NativeLibraryRef nativeLibrary = null;
                StrictJsonValue nativeLibraryValue = entryMembers.get("nativeLibrary");
                if (nativeLibraryValue != null) {
                    SourceScalarRange nativeLibraryRange = rangeOf(document, nativeLibraryValue);
                    if (!(nativeLibraryValue instanceof StrictJsonValue.StringVal
                            nativeLibraryString)) {
                        return failure("deal.json: externals entry '" + specifier
                                + "': 'nativeLibrary' must be a string",
                            nativeLibraryRange);
                    }
                    Failure nativeLibraryFailure = pathTextFailure("externals entry '"
                            + specifier + "' nativeLibrary",
                        nativeLibraryString.value(), nativeLibraryRange);
                    if (nativeLibraryFailure != null) {
                        return new Outcome(null, nativeLibraryFailure);
                    }
                    nativeLibrary = classifyNativeLibrary(nativeLibraryString.value(),
                        nativeLibraryRange);
                }

                externals.put(specifier, new ExternalEntrySpec(specifier,
                    new ManifestString(declarationString.value(), declarationRange),
                    nativeLibrary, entryRange));
            }
        }

        // The published field-to-value-range map for T4 re-anchoring:
        // one entry per member present in the document.
        Map<String, SourceScalarRange> ranges = new LinkedHashMap<>();
        ranges.put("languageVersion", rangeOf(document, languageVersionValue));
        if (backendValue != null) {
            ranges.put("backend", rangeOf(document, backendValue));
        }
        if (moduleRootsValue != null) {
            ranges.put("moduleRoots", rangeOf(document, moduleRootsValue));
        }
        if (outputValue != null) {
            ranges.put("output", rangeOf(document, outputValue));
        }
        if (stdlibValue != null) {
            ranges.put("stdlib", rangeOf(document, stdlibValue));
        }
        if (dependenciesValue != null) {
            ranges.put("dependencies", rangeOf(document, dependenciesValue));
        }
        if (externalsValue != null) {
            ranges.put("externals", rangeOf(document, externalsValue));
        }

        return new Outcome(new ProjectManifest(languageVersion.value(), moduleRoots, output,
            backend, stdlib, dependencies, externals, ranges), null);
    }

    // =========================================================================
    // Shared text checks and classification
    // =========================================================================

    /**
     * The shared non-empty / scalar-valid / NUL-free text checks for
     * {@code moduleRoots} entries, {@code output}, externals
     * {@code declaration}, and externals {@code nativeLibrary} values.
     * NUL and host-representability: a value materialized as a host path
     * must carry no U+0000 and no replacement character; the strict
     * decode (ProjectLocator D1 step 3) guarantees the latter, and the
     * parser re-checks the former here.
     */
    private static Failure pathTextFailure(String what, String value,
                                           SourceScalarRange range) {
        if (value.isEmpty()) {
            return new Failure("deal.json: " + what + " must be a non-empty string", range);
        }
        if (hasUnpairedSurrogate(value)) {
            return new Failure("deal.json: " + what
                + " must be scalar-valid (no unpaired UTF-16 surrogates)", range);
        }
        if (value.indexOf('\u0000') >= 0) {
            return new Failure("deal.json: " + what + " must not contain NUL", range);
        }
        return null;
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The pinned Linux {@code nativeLibrary} classification: leading
     * {@code /} → absolute; otherwise any {@code /} → manifest-relative;
     * no {@code /} → unchanged bare loader name. Backslash is an ordinary
     * filename character.
     */
    private static NativeLibraryRef classifyNativeLibrary(String loaderText,
                                                          SourceScalarRange range) {
        NativeLibraryRef.Kind kind;
        if (loaderText.startsWith("/")) {
            kind = NativeLibraryRef.Kind.ABSOLUTE_PATH;
        } else if (loaderText.contains("/")) {
            kind = NativeLibraryRef.Kind.MANIFEST_RELATIVE_PATH;
        } else {
            kind = NativeLibraryRef.Kind.BARE_NAME;
        }
        return new NativeLibraryRef(kind, loaderText, range);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static SourceScalarRange rangeOf(StrictJsonDocument document,
                                             StrictJsonValue value) {
        return document.rangeOf(value);
    }

    private static Outcome failure(String message, SourceScalarRange range) {
        return new Outcome(null, new Failure(message, range));
    }
}
