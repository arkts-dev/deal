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
    public record Failure(String message, SourceScalarRange anchorRange, deal.diagnostics.DiagnosticFacts facts) {
        public Failure(String message, SourceScalarRange anchorRange) {
            this(message, anchorRange, deal.diagnostics.DiagnosticFacts.EMPTY);
        }
    }

    /**
     * Validates the strictly walked document into a {@link ProjectManifest}.
     * Deterministic: the result is a pure function of the document.
     *
     */
    public static Outcome validate(StrictJsonDocument document) {
        // 1. languageVersion — required, exactly the JSON string "1.6".
        if (document.root() == null) {
            return constraint("deal.json: missing required member 'languageVersion'",
                DOCUMENT_START, "config.required-member", "languageVersion", "string \"1.6\"", "absent", null);
        }
        if (!(document.root() instanceof StrictJsonValue.ObjectVal rootObj)) {
            return constraint("deal.json: manifest root must be a JSON object",
                rangeOf(document, document.root()), "config.root-kind", "", "object", observed(document.root()), null);
        }
        Map<String, StrictJsonValue> rootMembers = rootObj.members();
        StrictJsonValue languageVersionValue = rootMembers.get("languageVersion");
        if (languageVersionValue == null) {
            return constraint("deal.json: missing required member 'languageVersion'",
                rangeOf(document, rootObj), "config.required-member", "languageVersion", "string \"1.6\"", "absent", null);
        }
        if (!(languageVersionValue instanceof StrictJsonValue.StringVal languageVersion)) {
            return constraint("deal.json: 'languageVersion' must be the JSON string \"1.6\"",
                rangeOf(document, languageVersionValue), "config.value-kind", "languageVersion", "string \"1.6\"",
                observed(languageVersionValue), null);
        }
        if (!ProjectLocator.LANGUAGE_VERSION.equals(languageVersion.value())) {
            return constraint("deal.json: 'languageVersion' must be exactly \"1.6\", got: '"
                    + languageVersion.value() + "'",
                rangeOf(document, languageVersionValue), "config.language-version", "languageVersion", "1.6",
                languageVersion.value(), null);
        }

        // 2. backend — exactly "luajit" | "jvm" | "js" | "llvm"; absence defaults
        // to luajit.
        String backend = "luajit";
        StrictJsonValue backendValue = rootMembers.get("backend");
        if (backendValue != null) {
            if (!(backendValue instanceof StrictJsonValue.StringVal backendString)) {
                return constraint("deal.json: 'backend' must be a string",
                    rangeOf(document, backendValue), "config.value-kind", "backend", "string",
                    observed(backendValue), "luajit");
            }
            if (!backendString.value().equals("luajit")
                    && !backendString.value().equals("jvm")
                    && !backendString.value().equals("js")
                    && !backendString.value().equals("llvm")) {
                return constraint("deal.json: unsupported backend '" + backendString.value()
                        + "'. Supported backends: 'luajit', 'jvm', 'js', 'llvm'",
                    rangeOf(document, backendValue), "config.backend", "backend", "luajit | jvm | js | llvm",
                    backendString.value(), "luajit");
            }
            backend = backendString.value();
        }

        // 3. moduleRoots — value-level only; absence is the empty list.
        List<ManifestString> moduleRoots = List.of();
        StrictJsonValue moduleRootsValue = rootMembers.get("moduleRoots");
        if (moduleRootsValue != null) {
            if (!(moduleRootsValue instanceof StrictJsonValue.ArrayVal moduleRootsArray)) {
                return constraint("deal.json: 'moduleRoots' must be an array of relative strings",
                    rangeOf(document, moduleRootsValue), "config.value-kind", "moduleRoots",
                    "array of relative path strings", observed(moduleRootsValue), null);
            }
            List<ManifestString> entries = new ArrayList<>();
            for (StrictJsonValue element : moduleRootsArray.elements()) {
                SourceScalarRange elementRange = rangeOf(document, element);
                if (!(element instanceof StrictJsonValue.StringVal elementString)) {
                    return constraint("deal.json: 'moduleRoots' entries must be strings",
                        elementRange, "config.value-kind", "moduleRoots", "relative path string", observed(element), null);
                }
                Failure textFailure = pathTextFailure("'moduleRoots' entry",
                    elementString.value(), elementRange, List.of("moduleRoots"));
                if (textFailure != null) {
                    return new Outcome(null, textFailure);
                }
                if (elementString.value().startsWith("/")) {
                    return constraint("deal.json: 'moduleRoots' entries must be relative paths,"
                            + " got: '" + elementString.value() + "'",
                        elementRange, "config.relative-path", "moduleRoots", "relative path", elementString.value(), null);
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
                return constraint("deal.json: 'output' must be a non-empty string",
                    rangeOf(document, outputValue), "config.value-kind", "output", "non-empty path string",
                    observed(outputValue), null);
            }
            Failure textFailure = pathTextFailure("'output'", outputString.value(),
                rangeOf(document, outputValue), List.of("output"));
            if (textFailure != null) {
                return new Outcome(null, textFailure);
            }
            output = new ManifestString(outputString.value(), rangeOf(document, outputValue));
        }

        // 5. stdlib — exactly "1.6"; absence defaults to "1.6".
        String stdlib = ProjectLocator.LANGUAGE_VERSION;
        StrictJsonValue stdlibValue = rootMembers.get("stdlib");
        if (stdlibValue != null) {
            if (!(stdlibValue instanceof StrictJsonValue.StringVal stdlibString)) {
                return constraint("deal.json: 'stdlib' must be a string",
                    rangeOf(document, stdlibValue), "config.value-kind", "stdlib", "string \"1.6\"",
                    observed(stdlibValue), "1.6");
            }
            if (!ProjectLocator.LANGUAGE_VERSION.equals(stdlibString.value())) {
                return constraint("deal.json: 'stdlib' must be exactly \"1.6\", got: '"
                        + stdlibString.value() + "'",
                    rangeOf(document, stdlibValue), "config.stdlib-version", "stdlib", "1.6",
                    stdlibString.value(), "1.6");
            }
            stdlib = stdlibString.value();
        }

        // 6. dependencies — when present must be a JSON object; contents opaque.
        StrictJsonValue.ObjectVal dependencies = null;
        StrictJsonValue dependenciesValue = rootMembers.get("dependencies");
        if (dependenciesValue != null) {
            if (!(dependenciesValue instanceof StrictJsonValue.ObjectVal dependenciesObject)) {
                return constraint("deal.json: 'dependencies' must be a JSON object",
                    rangeOf(document, dependenciesValue), "config.value-kind", "dependencies", "object",
                    observed(dependenciesValue), null);
            }
            dependencies = dependenciesObject;
        }

        // 7. externals — must be an object; entries in member order.
        Map<String, ExternalEntrySpec> externals = new LinkedHashMap<>();
        StrictJsonValue externalsValue = rootMembers.get("externals");
        if (externalsValue != null) {
            if (!(externalsValue instanceof StrictJsonValue.ObjectVal externalsObject)) {
                return constraint("deal.json: 'externals' must be an object mapping import"
                        + " specifiers to entry objects",
                    rangeOf(document, externalsValue), "config.value-kind", "externals", "object of declaration entries",
                    observed(externalsValue), null);
            }
            for (Map.Entry<String, StrictJsonValue> entry : externalsObject.members().entrySet()) {
                String specifier = entry.getKey();
                StrictJsonValue entryValue = entry.getValue();
                SourceScalarRange entryRange = rangeOf(document, entryValue);
                if (!(entryValue instanceof StrictJsonValue.ObjectVal entryObject)) {
                    return constraint("deal.json: externals entry '" + specifier
                            + "' must be an object with a 'declaration' string",
                        entryRange, "config.value-kind", List.of("externals", specifier), "object with declaration string",
                        observed(entryValue));
                }
                Map<String, StrictJsonValue> entryMembers = entryObject.members();

                StrictJsonValue declarationValue = entryMembers.get("declaration");
                if (declarationValue == null) {
                    return constraint("deal.json: externals entry '" + specifier
                            + "' is missing required member 'declaration'",
                        entryRange, "config.required-member", List.of("externals", specifier, "declaration"),
                        "host declaration path string", "absent");
                }
                SourceScalarRange declarationRange = rangeOf(document, declarationValue);
                if (!(declarationValue instanceof StrictJsonValue.StringVal declarationString)) {
                    return constraint("deal.json: externals entry '" + specifier
                            + "': 'declaration' must be a string",
                        declarationRange, "config.value-kind", List.of("externals", specifier, "declaration"),
                        "host declaration path string", observed(declarationValue));
                }
                Failure declarationFailure = pathTextFailure("externals entry '"
                        + specifier + "' declaration",
                    declarationString.value(), declarationRange, List.of("externals", specifier, "declaration"));
                if (declarationFailure != null) {
                    return new Outcome(null, declarationFailure);
                }
                if (!declarationString.value().endsWith(".d.deal")) {
                    return constraint("deal.json: externals entry '" + specifier
                            + "': 'declaration' must be a host declaration file (.d.deal), got: '"
                            + declarationString.value() + "'",
                        declarationRange, "config.declaration-suffix", List.of("externals", specifier, "declaration"),
                        "path ending in .d.deal", declarationString.value());
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
                                           SourceScalarRange range, List<String> path) {
        if (value.isEmpty()) {
            return constraint("deal.json: " + what + " must be a non-empty string", range,
                    "config.empty-path", path, "non-empty path string", value).failure();
        }
        if (hasUnpairedSurrogate(value)) {
            return constraint("deal.json: " + what
                + " must be scalar-valid (no unpaired UTF-16 surrogates)", range,
                    "config.path-unicode", path, "scalar-valid path string", value).failure();
        }
        if (value.indexOf('\u0000') >= 0) {
            return constraint("deal.json: " + what + " must not contain NUL", range,
                    "config.path-nul", path, "NUL-free path string", value).failure();
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

    private static String observed(StrictJsonValue value) {
        if (value == null) return "absent";
        return switch (value) {
            case StrictJsonValue.StringVal string -> string.value();
            case StrictJsonValue.NumberVal number -> number.sourceText();
            case StrictJsonValue.BoolVal bool -> Boolean.toString(bool.value());
            case StrictJsonValue.NullVal ignored -> "null";
            case StrictJsonValue.ArrayVal ignored -> "array";
            case StrictJsonValue.ObjectVal ignored -> "object";
        };
    }

    private static Outcome constraint(String message, SourceScalarRange range, String reason, String member,
                                      String expected, String actual, String defaultValue) {
        var guidance = defaultValue == null ? List.<String>of()
                : List.of("When '" + member + "' is absent its default is '" + defaultValue
                        + "'; an invalid explicit value is never replaced by that default.");
        var path = member.isEmpty() ? List.<deal.diagnostics.DiagnosticFacts.PathSegment>of()
                : List.of(new deal.diagnostics.DiagnosticFacts.PathSegment(deal.diagnostics.DiagnosticFacts.PathKind.FIELD, member));
        return new Outcome(null, new Failure(message, range, new deal.diagnostics.DiagnosticFacts(
                reason, "manifest.validate", expected, actual, guidance, path)));
    }

    private static Outcome constraint(String message, SourceScalarRange range, String reason, List<String> path,
                                      String expected, String actual) {
        return new Outcome(null, new Failure(message, range, new deal.diagnostics.DiagnosticFacts(
                reason, "manifest.validate", expected, actual, List.of(), path.stream().map(name ->
                        new deal.diagnostics.DiagnosticFacts.PathSegment(deal.diagnostics.DiagnosticFacts.PathKind.FIELD, name)).toList())));
    }
}
