package deal.test.conformance;

import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.SemanticIrTextDecodeException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The gate's sidecar loading phase (ISSUE-0353): the presence and shape
 * rules of the Zero-Skip Classification Contract plus the T1
 * {@link SidecarSchemaValidator} wiring with the fixture's compilation
 * set.
 *
 * <p>Presence rules: every runtime-classified fixture (including a
 * {@code known-fail} whose underlying mode is a runtime mode) requires a
 * valid three-backend sidecar; a Compile Expectation Sidecar may sit only
 * next to an exact {@code compile-error CODE} fixture (the T1 validator
 * cross-checks the pin against the {@code @expected} code); every other
 * classification carries no sidecar. A missing, malformed, or partial
 * sidecar is a classification failure naming the fixture and field —
 * there is no silent default.</p>
 *
 * <p>Validation wiring: the gate derives the fixture's compilation set
 * via module-import resolution over the corpus
 * ({@link CorpusDiscovery#compilationSet}) and supplies it — module paths
 * plus sources — to {@link SidecarSchemaValidator} for the
 * {@code sourceFile} compilation-graph check and the divergent-form
 * {@code @extern-c} check. The gate then cross-checks the sidecar's
 * runtime mode and error code against the fixture's classification (the
 * uniform {@code expected.mode}, the divergent {@code backends.luajit}
 * leg, and the {@code error.code}) and parses the validated document
 * into the typed expectation model for the comparators.</p>
 */
public final class SidecarGateLoader {

    private SidecarGateLoader() {
        // Static utility; no instances.
    }

    /** One sidecar loading outcome. */
    public record LoadResult(
        Optional<SidecarSchemaValidator.ClassificationFailure> failure,
        Optional<SidecarExpectations.StructuredExpectationSidecar> runtime,
        Optional<SidecarExpectations.CompileDiagnosticPin> compilePin
    ) {

        public LoadResult {
            Objects.requireNonNull(failure, "failure must not be null");
            Objects.requireNonNull(runtime, "runtime must not be null");
            Objects.requireNonNull(compilePin, "compilePin must not be null");
        }

        /** Empty: no sidecar required and none present. */
        public boolean none() {
            return failure.isEmpty() && runtime.isEmpty() && compilePin.isEmpty();
        }

        /** The classification failure is recorded and the sidecar unusable. */
        public boolean failed() {
            return failure.isPresent();
        }
    }

    private static LoadResult none() {
        return new LoadResult(Optional.empty(), Optional.empty(),
            Optional.empty());
    }

    /**
     * Loads the sidecar of one classified fixture (if any) and validates
     * it against schema version 1 with the fixture's compilation set.
     *
     * @param fixture           the classified fixture (never null)
     * @param corpusByPath      the discovered corpus by corpus-relative path
     * @param corpusModuleIndex the corpus module index (all discovered
     *                          corpus-relative paths)
     * @param conformanceRoot   the conformance root (absolute, normalized)
     */
    public static LoadResult load(CorpusDiscovery.Fixture fixture,
            Map<String, CorpusDiscovery.Fixture> corpusByPath,
            Set<String> corpusModuleIndex, Path conformanceRoot) {
        Objects.requireNonNull(fixture, "fixture must not be null");
        Objects.requireNonNull(corpusByPath, "corpusByPath must not be null");
        Objects.requireNonNull(corpusModuleIndex,
            "corpusModuleIndex must not be null");
        Objects.requireNonNull(conformanceRoot, "conformanceRoot must not be null");
        CorpusDiscovery.Classification classification = fixture.classification();
        if (classification == null) {
            return none(); // classification already failed; nothing to load
        }

        Path sidecarPath = fixture.sidecarPath(conformanceRoot);
        boolean exists = Files.exists(sidecarPath);
        boolean runtimeClassified = fixture.runtimeClassified();

        if (!runtimeClassified && !exists) {
            return none();
        }
        if (runtimeClassified && !exists) {
            return failure(fixture, "<sidecar>",
                "missing sidecar " + sidecarPath.getFileName() + " — every "
                    + "runtime-classified fixture requires a valid "
                    + "three-backend sidecar (the gate has no silent default)");
        }
        String text;
        try {
            text = Files.readString(sidecarPath);
        } catch (IOException e) {
            return failure(fixture, "<sidecar>", "cannot read sidecar "
                + sidecarPath.getFileName() + ": " + e.getMessage());
        }

        // Shape detection: a compile sidecar carries mode (and no
        // backends); a runtime sidecar carries backends (and no mode).
        boolean compileShaped = hasField(text, "mode");
        boolean runtimeShaped = hasField(text, "backends");
        if (!runtimeClassified && compileShaped) {
            // The T1 compile validation carries the exact @expected code
            // cross-check (a compile sidecar on a non-exact compile-error
            // fixture is a classification failure naming "@expected").
            return loadCompileSidecar(fixture, text, corpusModuleIndex,
                corpusByPath, conformanceRoot);
        }
        if (!runtimeClassified) {
            return failure(fixture, "<sidecar>",
                "a sidecar exists next to " + fixture.expected()
                    + "-classified fixture " + fixture.corpusPath()
                    + " — sidecars sit only next to runtime-classified "
                    + "fixtures (three-backend runtime sidecar) or exact "
                    + "compile-error fixtures (Compile Expectation Sidecar)");
        }
        if (compileShaped && !runtimeShaped) {
            // Compile-shaped next to a runtime fixture: T1's compile path
            // fails the @expected cross-check with the fixture path.
            Optional<SidecarSchemaValidator.ClassificationFailure> t1Failure =
                SidecarSchemaValidator.validate(contextOf(fixture, corpusByPath,
                    conformanceRoot, corpusModuleIndex), text);
            return new LoadResult(t1Failure, Optional.empty(), Optional.empty());
        }
        if (!runtimeShaped) {
            // Neither key: let T1 name the missing shape field.
            Optional<SidecarSchemaValidator.ClassificationFailure> t1Failure =
                SidecarSchemaValidator.validate(contextOf(fixture, corpusByPath,
                    conformanceRoot, corpusModuleIndex), text);
            return new LoadResult(t1Failure, Optional.empty(), Optional.empty());
        }

        // Runtime-shaped next to a runtime fixture: full T1 validation with
        // the compilation set, then the mode/code cross-checks, then typed
        // parsing.
        Optional<SidecarSchemaValidator.ClassificationFailure> t1Failure =
            SidecarSchemaValidator.validate(contextOf(fixture, corpusByPath,
                conformanceRoot, corpusModuleIndex), text);
        if (t1Failure.isPresent()) {
            return new LoadResult(t1Failure, Optional.empty(), Optional.empty());
        }
        Optional<SidecarSchemaValidator.ClassificationFailure> crossCheck =
            crossCheckRuntimeSidecar(fixture, text);
        if (crossCheck.isPresent()) {
            return new LoadResult(crossCheck, Optional.empty(), Optional.empty());
        }
        try {
            return new LoadResult(Optional.empty(),
                Optional.of(SidecarExpectations.StructuredExpectationSidecar
                    .parse(text)),
                Optional.empty());
        } catch (IllegalArgumentException e) {
            return failure(fixture, "<sidecar>",
                "the schema-validated sidecar failed typed parsing: "
                    + e.getMessage());
        }
    }

    private static LoadResult loadCompileSidecar(CorpusDiscovery.Fixture fixture,
            String text, Set<String> corpusModuleIndex,
            Map<String, CorpusDiscovery.Fixture> corpusByPath, Path conformanceRoot) {
        Optional<SidecarSchemaValidator.ClassificationFailure> t1Failure =
            SidecarSchemaValidator.validate(contextOf(fixture, corpusByPath,
                conformanceRoot, corpusModuleIndex), text);
        if (t1Failure.isPresent()) {
            return new LoadResult(t1Failure, Optional.empty(), Optional.empty());
        }
        try {
            return new LoadResult(Optional.empty(), Optional.empty(),
                Optional.of(SidecarExpectations.CompileDiagnosticPin.parse(text)));
        } catch (IllegalArgumentException e) {
            return failure(fixture, "<sidecar>",
                "the schema-validated sidecar failed typed parsing: "
                    + e.getMessage());
        }
    }

    private static SidecarSchemaValidator.ValidationContext contextOf(
            CorpusDiscovery.Fixture fixture,
            Map<String, CorpusDiscovery.Fixture> corpusByPath,
            Path conformanceRoot, Set<String> corpusModuleIndex) {
        List<SidecarSchemaValidator.CompilationModule> compilationSet =
            CorpusDiscovery.compilationSet(fixture, corpusByPath, conformanceRoot);
        return new SidecarSchemaValidator.ValidationContext(
            fixture.corpusPath(), fixture.expected(), compilationSet,
            corpusModuleIndex);
    }

    private static Optional<SidecarSchemaValidator.ClassificationFailure>
            crossCheckRuntimeSidecar(CorpusDiscovery.Fixture fixture, String text) {
        CorpusDiscovery.Classification classification = fixture.classification();
        String requiredMode = classification.runtimeSidecarMode();
        String requiredCode = classification.runtimeErrorCode();
        // A tracked 'runtime-error any' pins no specific code (the 'any'
        // form is a known-fail-only tracking marker, never a sidecar code).
        if ("any".equals(requiredCode)) {
            requiredCode = null;
        }
        try {
            CanonicalJson.Value root = CanonicalJson.parse(text);
            if (!(root instanceof CanonicalJson.Obj obj)) {
                return Optional.of(new SidecarSchemaValidator.ClassificationFailure(
                    fixture.corpusPath(), "<sidecar>",
                    "the sidecar root must be a JSON object"));
            }
            CanonicalJson.Value backends = fieldOf(obj, "backends");
            if (backends instanceof CanonicalJson.Arr) {
                CanonicalJson.Obj expected =
                    (CanonicalJson.Obj) fieldOf(obj, "expected");
                String mode = stringField(expected, "mode");
                if (!requiredMode.equals(mode)) {
                    return Optional.of(new SidecarSchemaValidator
                        .ClassificationFailure(fixture.corpusPath(),
                        "expected.mode",
                        "the sidecar pins mode \"" + mode + "\" but the "
                            + "fixture is classified " + fixture.expected()));
                }
                if (requiredCode != null) {
                    CanonicalJson.Obj error =
                        (CanonicalJson.Obj) fieldOf(expected, "error");
                    String code = stringField(error, "code");
                    if (!requiredCode.equals(code)) {
                        return Optional.of(new SidecarSchemaValidator
                            .ClassificationFailure(fixture.corpusPath(),
                            "expected.error.code",
                            "the sidecar pins error code " + code + " but the "
                                + "fixture's @expected pins " + requiredCode));
                    }
                }
                return Optional.empty();
            }
            // Divergent form: the sanctioned C6 split anchors the runtime
            // leg on luajit (T1 already validated the split).
            CanonicalJson.Obj entries = (CanonicalJson.Obj) backends;
            CanonicalJson.Obj luajit = (CanonicalJson.Obj) fieldOf(entries,
                "luajit");
            String mode = stringField(luajit, "mode");
            if (!requiredMode.equals(mode)) {
                return Optional.of(new SidecarSchemaValidator
                    .ClassificationFailure(fixture.corpusPath(),
                    "backends.luajit.mode",
                    "the sidecar's luajit leg pins mode \"" + mode
                        + "\" but the fixture is classified "
                        + fixture.expected()));
            }
            if (requiredCode != null) {
                CanonicalJson.Obj error = (CanonicalJson.Obj) fieldOf(luajit,
                    "error");
                String code = stringField(error, "code");
                if (!requiredCode.equals(code)) {
                    return Optional.of(new SidecarSchemaValidator
                        .ClassificationFailure(fixture.corpusPath(),
                        "backends.luajit.error.code",
                        "the sidecar's luajit leg pins error code " + code
                            + " but the fixture's @expected pins "
                            + requiredCode));
                }
            }
            return Optional.empty();
        } catch (SemanticIrTextDecodeException | IllegalArgumentException e) {
            // T1 validated the document already; a parse failure here is a
            // harness defect reported as a classification failure.
            return Optional.of(new SidecarSchemaValidator.ClassificationFailure(
                fixture.corpusPath(), "<sidecar>",
                "the schema-validated sidecar failed the classification "
                    + "cross-check parse: " + e.getMessage()));
        }
    }

    private static boolean hasField(String json, String field) {
        try {
            CanonicalJson.Value root = CanonicalJson.parse(json);
            return root instanceof CanonicalJson.Obj obj
                && fieldOf(obj, field) != null;
        } catch (SemanticIrTextDecodeException | IllegalArgumentException e) {
            return false;
        }
    }

    private static CanonicalJson.Value fieldOf(CanonicalJson.Obj obj,
            String key) {
        for (CanonicalJson.Entry entry : obj.entries()) {
            if (entry.key().equals(key)) {
                return entry.value();
            }
        }
        return null;
    }

    private static String stringField(CanonicalJson.Obj obj, String key) {
        CanonicalJson.Value value = fieldOf(obj, key);
        if (!(value instanceof CanonicalJson.Str str)) {
            throw new IllegalArgumentException(
                key + " must be a JSON string");
        }
        return str.value();
    }

    private static LoadResult failure(CorpusDiscovery.Fixture fixture,
            String field, String reason) {
        return new LoadResult(Optional.of(
            new SidecarSchemaValidator.ClassificationFailure(
                fixture.corpusPath(), field, reason)),
            Optional.empty(), Optional.empty());
    }
}
