package deal.project;

import deal.source.SourceScalarRange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProjectConfigValidator {
    private ProjectConfigValidator() {}

    private static final SourceScalarRange DOCUMENT_START = new SourceScalarRange(1, 1, 1, 1, 0, 0);

    /** Default validation publishes a manifest or the first canonical-order failure. */
    public record Outcome(ProjectManifest manifest, Failure failure) {
        public Outcome {
            if ((manifest == null) == (failure == null))
                throw new IllegalArgumentException("exactly one of manifest and failure must be non-null");
        }
    }

    /** Batch validation never publishes a partially valid manifest. */
    public record BatchOutcome(ProjectManifest manifest, List<Failure> failures) {
        public BatchOutcome {
            failures = List.copyOf(failures);
            if ((manifest != null) != failures.isEmpty())
                throw new IllegalArgumentException("manifest requires no validation failures");
        }
    }

    public record Failure(String message, SourceScalarRange anchorRange, deal.diagnostics.DiagnosticFacts facts) {
        public Failure(String message, SourceScalarRange anchorRange) {
            this(message, anchorRange, deal.diagnostics.DiagnosticFacts.EMPTY);
        }
    }

    public static Outcome validate(StrictJsonDocument document) {
        BatchOutcome result = validate(document, false);
        return new Outcome(result.manifest(), result.failures().isEmpty() ? null : result.failures().get(0));
    }

    /** Independent schema failures only; strict-walk faults must already have been rejected. */
    public static BatchOutcome validateBatch(StrictJsonDocument document) {
        return validate(document, true);
    }

    private static BatchOutcome validate(StrictJsonDocument document, boolean batch) {
        if (document.root() == null)
            return new BatchOutcome(null, List.of(constraint("deal.json: missing required member 'languageVersion'",
                DOCUMENT_START, "config.required-member", "languageVersion", "string \"1.6\"", "absent", null)));
        if (!(document.root() instanceof StrictJsonValue.ObjectVal root))
            return new BatchOutcome(null, List.of(constraint("deal.json: manifest root must be a JSON object",
                document.rangeOf(document.root()), "config.root-kind", "", "object", observed(document.root()), null)));
        Validation walk = new Validation(document, root);
        List<Failure> failures = new ArrayList<>();
        // Same canonical order in both modes. Invalid containers suppress dependent checks.
        for (int field = 0; field < 7; field++) {
            List<Failure> found = walk.field(field, batch);
            failures.addAll(found);
            if (!batch && !found.isEmpty()) break;
        }
        return new BatchOutcome(failures.isEmpty() ? walk.manifest() : null, failures);
    }

    private static final class Validation {
        final StrictJsonDocument document;
        final StrictJsonValue.ObjectVal root;
        final Map<String, StrictJsonValue> members;
        String languageVersion;
        String backend = "luajit";
        List<ManifestString> moduleRoots = new ArrayList<>();
        ManifestString output;
        String stdlib = ProjectLocator.LANGUAGE_VERSION;
        StrictJsonValue.ObjectVal dependencies;
        Map<String, ExternalEntrySpec> externals = new LinkedHashMap<>();

        Validation(StrictJsonDocument document, StrictJsonValue.ObjectVal root) {
            this.document = document;
            this.root = root;
            this.members = root.members();
        }

        List<Failure> field(int field, boolean batch) {
            Failure failure = switch (field) {
                case 0 -> languageVersion();
                case 1 -> backend();
                case 2 -> null;
                case 3 -> output();
                case 4 -> stdlib();
                case 5 -> dependencies();
                case 6 -> null;
                default -> throw new IllegalArgumentException("unknown manifest field");
            };
            if (field == 2) return moduleRoots(batch);
            if (field == 6) return externals(batch);
            return failure == null ? List.of() : List.of(failure);
        }

        Failure languageVersion() {
            StrictJsonValue value = members.get("languageVersion");
            if (value == null) return constraint("deal.json: missing required member 'languageVersion'",
                document.rangeOf(root), "config.required-member", "languageVersion", "string \"1.6\"", "absent", null);
            if (!(value instanceof StrictJsonValue.StringVal text))
                return constraint("deal.json: 'languageVersion' must be the JSON string \"1.6\"",
                    document.rangeOf(value), "config.value-kind", "languageVersion", "string \"1.6\"", observed(value), null);
            if (!ProjectLocator.LANGUAGE_VERSION.equals(text.value()))
                return constraint("deal.json: 'languageVersion' must be exactly \"1.6\", got: '" + text.value() + "'",
                    document.rangeOf(value), "config.language-version", "languageVersion", "1.6", text.value(), null);
            languageVersion = text.value();
            return null;
        }

        Failure backend() {
            StrictJsonValue value = members.get("backend");
            if (value == null) return null;
            if (!(value instanceof StrictJsonValue.StringVal text))
                return constraint("deal.json: 'backend' must be a string", document.rangeOf(value),
                    "config.value-kind", "backend", "string", observed(value), "luajit");
            if (!text.value().equals("luajit") && !text.value().equals("jvm")
                    && !text.value().equals("js") && !text.value().equals("llvm"))
                return constraint("deal.json: unsupported backend '" + text.value()
                    + "'. Supported backends: 'luajit', 'jvm', 'js', 'llvm'", document.rangeOf(value),
                    "config.backend", "backend", "luajit | jvm | js | llvm", text.value(), "luajit");
            backend = text.value();
            return null;
        }

        List<Failure> moduleRoots(boolean batch) {
            StrictJsonValue value = members.get("moduleRoots");
            if (value == null) return List.of();
            if (!(value instanceof StrictJsonValue.ArrayVal array))
                return List.of(constraint("deal.json: 'moduleRoots' must be an array of relative strings",
                    document.rangeOf(value), "config.value-kind", "moduleRoots", "array of relative path strings", observed(value), null));
            List<Failure> failures = new ArrayList<>();
            for (StrictJsonValue element : array.elements()) {
                Failure failure = moduleRoot(element);
                if (failure != null) {
                    failures.add(failure);
                    if (!batch) break;
                }
            }
            return failures;
        }

        Failure moduleRoot(StrictJsonValue element) {
            SourceScalarRange range = document.rangeOf(element);
            if (!(element instanceof StrictJsonValue.StringVal text))
                return constraint("deal.json: 'moduleRoots' entries must be strings", range,
                    "config.value-kind", "moduleRoots", "relative path string", observed(element), null);
            Failure failure = pathTextFailure("'moduleRoots' entry", text.value(), range, List.of("moduleRoots"));
            if (failure != null) return failure;
            if (text.value().startsWith("/"))
                return constraint("deal.json: 'moduleRoots' entries must be relative paths, got: '" + text.value() + "'",
                    range, "config.relative-path", "moduleRoots", "relative path", text.value(), null);
            moduleRoots.add(new ManifestString(text.value(), range));
            return null;
        }

        Failure output() {
            StrictJsonValue value = members.get("output");
            if (value == null) return null;
            if (!(value instanceof StrictJsonValue.StringVal text))
                return constraint("deal.json: 'output' must be a non-empty string", document.rangeOf(value),
                    "config.value-kind", "output", "non-empty path string", observed(value), null);
            Failure failure = pathTextFailure("'output'", text.value(), document.rangeOf(value), List.of("output"));
            if (failure != null) return failure;
            output = new ManifestString(text.value(), document.rangeOf(value));
            return null;
        }

        Failure stdlib() {
            StrictJsonValue value = members.get("stdlib");
            if (value == null) return null;
            if (!(value instanceof StrictJsonValue.StringVal text))
                return constraint("deal.json: 'stdlib' must be a string", document.rangeOf(value),
                    "config.value-kind", "stdlib", "string \"1.6\"", observed(value), "1.6");
            if (!ProjectLocator.LANGUAGE_VERSION.equals(text.value()))
                return constraint("deal.json: 'stdlib' must be exactly \"1.6\", got: '" + text.value() + "'",
                    document.rangeOf(value), "config.stdlib-version", "stdlib", "1.6", text.value(), "1.6");
            stdlib = text.value();
            return null;
        }

        Failure dependencies() {
            StrictJsonValue value = members.get("dependencies");
            if (value == null) return null;
            if (!(value instanceof StrictJsonValue.ObjectVal object))
                return constraint("deal.json: 'dependencies' must be a JSON object", document.rangeOf(value),
                    "config.value-kind", "dependencies", "object", observed(value), null);
            dependencies = object;
            return null;
        }

        List<Failure> externals(boolean batch) {
            StrictJsonValue value = members.get("externals");
            if (value == null) return List.of();
            if (!(value instanceof StrictJsonValue.ObjectVal object))
                return List.of(constraint("deal.json: 'externals' must be an object mapping import specifiers to entry objects",
                    document.rangeOf(value), "config.value-kind", "externals", "object of declaration entries", observed(value), null));
            List<Failure> failures = new ArrayList<>();
            for (var entry : object.members().entrySet()) {
                Failure failure = external(entry.getKey(), entry.getValue());
                if (failure != null) {
                    failures.add(failure);
                    if (!batch) break;
                }
            }
            return failures;
        }

        Failure external(String specifier, StrictJsonValue value) {
            SourceScalarRange range = document.rangeOf(value);
            if (!(value instanceof StrictJsonValue.ObjectVal object))
                return constraint("deal.json: externals entry '" + specifier + "' must be an object with a 'declaration' string",
                    range, "config.value-kind", List.of("externals", specifier), "object with declaration string", observed(value));
            StrictJsonValue declaration = object.members().get("declaration");
            List<String> path = List.of("externals", specifier, "declaration");
            if (declaration == null)
                return constraint("deal.json: externals entry '" + specifier + "' is missing required member 'declaration'",
                    range, "config.required-member", path, "host declaration path string", "absent");
            SourceScalarRange declarationRange = document.rangeOf(declaration);
            if (!(declaration instanceof StrictJsonValue.StringVal text))
                return constraint("deal.json: externals entry '" + specifier + "': 'declaration' must be a string",
                    declarationRange, "config.value-kind", path, "host declaration path string", observed(declaration));
            Failure failure = pathTextFailure("externals entry '" + specifier + "' declaration", text.value(), declarationRange, path);
            if (failure != null) return failure;
            if (!text.value().endsWith(".d.deal"))
                return constraint("deal.json: externals entry '" + specifier
                    + "': 'declaration' must be a host declaration file (.d.deal), got: '" + text.value() + "'",
                    declarationRange, "config.declaration-suffix", path, "path ending in .d.deal", text.value());
            externals.put(specifier, new ExternalEntrySpec(specifier, new ManifestString(text.value(), declarationRange), range));
            return null;
        }

        ProjectManifest manifest() {
            Map<String, SourceScalarRange> ranges = new LinkedHashMap<>();
            for (String field : List.of("languageVersion", "backend", "moduleRoots", "output", "stdlib", "dependencies", "externals")) {
                StrictJsonValue value = members.get(field);
                if (value != null) ranges.put(field, document.rangeOf(value));
            }
            return new ProjectManifest(languageVersion, moduleRoots, output, backend, stdlib, dependencies, externals, ranges);
        }
    }

    private static Failure pathTextFailure(String what, String value, SourceScalarRange range, List<String> path) {
        if (value.isEmpty()) return constraint("deal.json: " + what + " must be a non-empty string", range,
            "config.empty-path", path, "non-empty path string", value);
        if (hasUnpairedSurrogate(value)) return constraint("deal.json: " + what
            + " must be scalar-valid (no unpaired UTF-16 surrogates)", range,
            "config.path-unicode", path, "scalar-valid path string", value);
        if (value.indexOf('\u0000') >= 0) return constraint("deal.json: " + what + " must not contain NUL", range,
            "config.path-nul", path, "NUL-free path string", value);
        return null;
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char unit = value.charAt(i);
            if (Character.isHighSurrogate(unit)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) return true;
                i++;
            } else if (Character.isLowSurrogate(unit)) return true;
        }
        return false;
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

    private static Failure constraint(String message, SourceScalarRange range, String reason, String member,
                                      String expected, String actual, String defaultValue) {
        var guidance = defaultValue == null ? List.<String>of()
            : List.of("When '" + member + "' is absent its default is '" + defaultValue
                + "'; an invalid explicit value is never replaced by that default.");
        var path = member.isEmpty() ? List.<deal.diagnostics.DiagnosticFacts.PathSegment>of()
            : List.of(new deal.diagnostics.DiagnosticFacts.PathSegment(deal.diagnostics.DiagnosticFacts.PathKind.FIELD, member));
        return new Failure(message, range, new deal.diagnostics.DiagnosticFacts(reason, "manifest.validate", expected, actual, guidance, path));
    }

    private static Failure constraint(String message, SourceScalarRange range, String reason, List<String> path,
                                      String expected, String actual) {
        return new Failure(message, range, new deal.diagnostics.DiagnosticFacts(reason, "manifest.validate", expected, actual,
            List.of(), path.stream().map(name -> new deal.diagnostics.DiagnosticFacts.PathSegment(
                deal.diagnostics.DiagnosticFacts.PathKind.FIELD, name)).toList()));
    }
}
