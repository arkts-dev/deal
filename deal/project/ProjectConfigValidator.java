package deal.project;

import deal.source.SourceScalarRange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


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
     */
    public static Outcome validate(StrictJsonDocument document) {
        // 1. languageVersion — required, exactly the JSON string "1.4".
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
            return failure("deal.json: 'languageVersion' must be the JSON string \"1.4\"",
                rangeOf(document, languageVersionValue));
        }
        if (!"1.4".equals(languageVersion.value())) {
            return failure("deal.json: 'languageVersion' must be exactly \"1.4\", got: '"
                    + languageVersion.value() + "'",
                rangeOf(document, languageVersionValue));
        }

        // 2. backend — exactly "luajit" | "jvm" | "js"; absence defaults
        // to luajit.
        String backend = "luajit";
        StrictJsonValue backendValue = rootMembers.get("backend");
        if (backendValue != null) {
            if (!(backendValue instanceof StrictJsonValue.StringVal backendString)) {
                return failure("deal.json: 'backend' must be a string",
                    rangeOf(document, backendValue));
            }
            if (!backendString.value().equals("luajit")
                    && !backendString.value().equals("jvm")
                    && !backendString.value().equals("js")) {
                return failure("deal.json: unsupported backend '" + backendString.value()
                        + "'. Supported backends: 'luajit', 'jvm', 'js'",
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

        // 5. stdlib — exactly "1.4"; absence defaults to "1.4".
        String stdlib = "1.4";
        StrictJsonValue stdlibValue = rootMembers.get("stdlib");
        if (stdlibValue != null) {
            if (!(stdlibValue instanceof StrictJsonValue.StringVal stdlibString)) {
                return failure("deal.json: 'stdlib' must be a string",
                    rangeOf(document, stdlibValue));
            }
            if (!"1.4".equals(stdlibString.value())) {
                return failure("deal.json: 'stdlib' must be exactly \"1.4\", got: '"
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

                externals.put(specifier, new ExternalEntrySpec(specifier,
                    new ManifestString(declarationString.value(), declarationRange),
                    entryRange));
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
