package deal.semantic.ir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The deterministic semantic dumper of {@code deal.semantic-ir/1}
 * (parent D10; schema S7): emits {@link LoweredModuleUnit} units and
 * {@link ExecutableLoweredProject} projects as canonical JSON — one
 * module at a time; a complete project is a manifest plus the module
 * dumps in dependency order.
 *
 * <p><b>Single-mapping rule (S2/S7, exact):</b> the dumper owns no unit
 * shape of its own. {@link #dumpModule(LoweredModuleUnit)} is
 * byte-for-byte the single canonicalizer's canonical JSON of the unit —
 * {@link ContractSnapshotCanonicalizer#toJson(RawUnit)} over
 * {@link RawUnit#fromTyped(LoweredModuleUnit)} — so a module dump equals
 * the validator's pinned unit text protocol exactly (the same bytes
 * {@link SemanticIrValidator#validateText(String, ComparisonFacts)}
 * consumes) and the dumper adds no reordering of any kind. Every
 * embedded shape (operation payloads, contracts, descriptors, semantic
 * IDs, bindings, tokens) renders through the canonicalizer's single
 * shared mappings; descriptor and enum spellings are exactly the closed
 * definitions of the schema core, and a broken schema serializer breaks
 * the dump goldens. Serialization always flows through
 * {@link ContractSnapshotCanonicalizer#serializeJson} — the one
 * serializer entry — so the dumper never serializes canonical JSON for
 * itself.
 *
 * <p><b>Module dump shape (the canonicalizer's pinned unit text
 * protocol; object keys are sorted by the canonical serializer):</b></p>
 *
 * <pre>{@code
 * {
 *   "constructCoverage": [ {"construct": name, "opKinds": [name, ...]}, ... ],
 *   "formatVersion": "deal.semantic-ir/1",
 *   "functionBindings": [ {"allocationId": int, "binding": <binding>}, ... ],
 *   "interfaceHash": hex,
 *   "loweringContextHash": hex,
 *   "moduleId": "<module path>",
 *   "ops": [ <op>, ... ],                       // produced operations in source order
 *   "requiredCapabilities": [name, ...],
 *   "semanticProfile": "DEAL_V1_2_INT32"
 * }
 * }</pre>
 *
 * <p><b>Project manifest shape (pinned):</b></p>
 *
 * <pre>{@code
 * {
 *   "entryModule": "<module path>",
 *   "formatVersion": "deal.semantic-ir/1",
 *   "modules": [ "<module path>", ... ],        // dependency order (project record order)
 *   "semanticProfile": "DEAL_V1_2_INT32"
 * }
 * }</pre>
 *
 * <p>A complete project dump is the manifest plus one module dump per
 * module in dependency order (parent D10); file emission writes the
 * manifest as {@link #MANIFEST_FILE_NAME} and each module as
 * {@code modules/<modulePath>.json} with UTF-8 and LF-only line endings
 * (each file is the canonical document terminated by exactly one LF, no
 * CR anywhere). Repeated dumps of the same validated project are
 * byte-identical across JVM runs (the canonical serializer fully
 * determines the bytes from the value model; the project record
 * preserves module insertion order).
 *
 * <p><b>Validated-input contract:</b> the dump runs only on validated
 * input — this component performs no validation; the closed validator
 * (T6) rejects invalid IR before any dump, and the dumped unit text is
 * itself re-validatable through the validator's text surface. The
 * existing typed-AST observer {@code deal.ir.IrDumper} and the
 * {@code --dump-ir} surface are untouched: this dumper adds a separate
 * semantic observation surface and shares no code with the old dump.</p>
 */
public final class SemanticIrDumper {

    private SemanticIrDumper() {
        // Static utility; no instances.
    }

    // =========================================================================
    // Pinned manifest field names
    // =========================================================================

    /** The pinned manifest field name {@code "entryModule"}. */
    public static final String FIELD_ENTRY_MODULE = "entryModule";

    /** The pinned manifest field name {@code "formatVersion"}. */
    public static final String FIELD_FORMAT_VERSION = "formatVersion";

    /** The pinned manifest field name {@code "modules"}. */
    public static final String FIELD_MODULES = "modules";

    /** The pinned manifest field name {@code "semanticProfile"}. */
    public static final String FIELD_SEMANTIC_PROFILE = "semanticProfile";

    // =========================================================================
    // Pinned file-emission names
    // =========================================================================

    /** The pinned project manifest file name. */
    public static final String MANIFEST_FILE_NAME = "project.manifest.json";

    /** The pinned module dump subdirectory name. */
    public static final String MODULES_DIR_NAME = "modules";

    // =========================================================================
    // Module dump (the canonicalizer's single unit mapping)
    // =========================================================================

    /**
     * Maps a unit onto the single canonical JSON value model: exactly the
     * canonicalizer's unit mapping
     * ({@link ContractSnapshotCanonicalizer#toJson(RawUnit)} over
     * {@link RawUnit#fromTyped(LoweredModuleUnit)}). The dumper owns no
     * unit shape of its own and adds no reordering.
     *
     * @param unit the unit; non-null
     * @return the canonical JSON object of the unit text protocol
     */
    public static CanonicalJson.Value toJson(LoweredModuleUnit unit) {
        Objects.requireNonNull(unit, "unit must not be null");
        return ContractSnapshotCanonicalizer.toJson(RawUnit.fromTyped(unit));
    }

    /**
     * Dumps one module: the exact canonical JSON bytes of the unit's
     * canonical text protocol (the canonicalizer's unit mapping, no added
     * bytes, no reordering).
     *
     * @param unit the unit; non-null
     * @return the canonical JSON UTF-8 bytes
     */
    public static byte[] dumpModule(LoweredModuleUnit unit) {
        return ContractSnapshotCanonicalizer.serializeJson(toJson(unit));
    }

    /**
     * Dumps one module as canonical JSON text (UTF-8; observation surface
     * only).
     *
     * @param unit the unit; non-null
     * @return the canonical JSON text
     */
    public static String dumpModuleText(LoweredModuleUnit unit) {
        return ContractSnapshotCanonicalizer.serializeText(toJson(unit));
    }

    // =========================================================================
    // Manifest and project mapping
    // =========================================================================

    /**
     * Maps a project onto the pinned manifest shape: the entry module, the
     * pinned format version, the complete module closure in dependency
     * order (the project record's insertion order), and the project's
     * semantic profile. Module references render as plain module paths —
     * the same spelling the unit text protocol uses for {@code moduleId}.
     *
     * @param project the project; non-null
     * @return the canonical JSON manifest object
     */
    public static CanonicalJson.Value manifestJson(ExecutableLoweredProject project) {
        Objects.requireNonNull(project, "project must not be null");
        List<CanonicalJson.Value> modules = new ArrayList<>();
        for (ModuleId module : project.modules().keySet()) {
            modules.add(CanonicalJson.str(module.path()));
        }
        return CanonicalJson.obj(
            CanonicalJson.e(FIELD_ENTRY_MODULE,
                CanonicalJson.str(project.entryModule().path())),
            CanonicalJson.e(FIELD_FORMAT_VERSION,
                CanonicalJson.str(LoweredModuleUnit.FORMAT_VERSION)),
            CanonicalJson.e(FIELD_MODULES, CanonicalJson.arr(modules)),
            CanonicalJson.e(FIELD_SEMANTIC_PROFILE,
                CanonicalJson.str(project.semanticProfile().name())));
    }

    /** Dumps the project manifest: the exact canonical JSON bytes. */
    public static byte[] dumpManifest(ExecutableLoweredProject project) {
        return ContractSnapshotCanonicalizer.serializeJson(manifestJson(project));
    }

    /** Dumps the project manifest as canonical JSON text. */
    public static String dumpManifestText(ExecutableLoweredProject project) {
        return ContractSnapshotCanonicalizer.serializeText(manifestJson(project));
    }

    /** One module dump of a project dump: the module id and its bytes. */
    public record ModuleDump(ModuleId moduleId, byte[] bytes) {

        public ModuleDump(ModuleId moduleId, byte[] bytes) {
            this.moduleId = Objects.requireNonNull(moduleId, "moduleId must not be null");
            this.bytes = Objects.requireNonNull(bytes, "bytes must not be null").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        /** The module dump as canonical JSON text. */
        public String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * A complete project dump: the manifest plus the per-module dumps in
     * dependency order (parent D10).
     */
    public record ProjectDump(byte[] manifest, List<ModuleDump> modules) {

        public ProjectDump(byte[] manifest, List<ModuleDump> modules) {
            this.manifest = Objects.requireNonNull(manifest, "manifest must not be null").clone();
            Objects.requireNonNull(modules, "modules must not be null");
            this.modules = List.copyOf(modules);
        }

        @Override
        public byte[] manifest() {
            return manifest.clone();
        }

        /** The manifest as canonical JSON text. */
        public String manifestText() {
            return new String(manifest, StandardCharsets.UTF_8);
        }
    }

    /**
     * Dumps a complete project: the manifest plus every module dump in
     * dependency order (the project's module insertion order).
     *
     * @param project the project; non-null
     * @return the project dump
     */
    public static ProjectDump dumpProject(ExecutableLoweredProject project) {
        Objects.requireNonNull(project, "project must not be null");
        List<ModuleDump> dumps = new ArrayList<>();
        for (Map.Entry<ModuleId, LoweredModuleUnit> entry : project.modules().entrySet()) {
            if (!entry.getKey().equals(entry.getValue().moduleId())) {
                throw new IllegalArgumentException(
                    "the modules map key " + entry.getKey() + " does not match the unit's moduleId "
                        + entry.getValue().moduleId() + " (a producer defect)");
            }
            dumps.add(new ModuleDump(entry.getKey(), dumpModule(entry.getValue())));
        }
        return new ProjectDump(dumpManifest(project), dumps);
    }

    // =========================================================================
    // File emission (UTF-8, LF line endings)
    // =========================================================================

    /**
     * The pinned module dump file name: {@code "<modulePath>.json"}. DEAL
     * module paths are compiler-controlled dotted paths; the dumper
     * rejects any path outside that closed character set rather than
     * writing an unexpected file name.
     *
     * @param moduleId the module identity; non-null
     * @return the file name
     */
    public static String moduleFileName(ModuleId moduleId) {
        Objects.requireNonNull(moduleId, "moduleId must not be null");
        String path = moduleId.path();
        if (path.isEmpty() || path.startsWith(".") || path.endsWith(".")
                || path.contains("..") || !path.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException(
                "module path \"" + path + "\" is outside the closed dotted module-path form; "
                    + "the semantic dumper refuses to derive a file name from it");
        }
        return path + ".json";
    }

    private static byte[] withLf(byte[] bytes) {
        byte[] result = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, result, 0, bytes.length);
        result[bytes.length] = '\n';
        return result;
    }

    /**
     * Writes one module dump to a file: the canonical document terminated
     * by exactly one LF (UTF-8, LF-only line endings, no CR anywhere).
     *
     * @param file the target file; non-null
     * @param unit the unit; non-null
     * @throws IOException on a write failure
     */
    public static void dumpModuleTo(Path file, LoweredModuleUnit unit) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, withLf(dumpModule(unit)));
    }

    /**
     * Writes a complete project dump into a directory:
     * {@code <dir>/project.manifest.json} plus
     * {@code <dir>/modules/<modulePath>.json} per module in dependency
     * order, each file the canonical document terminated by exactly one
     * LF (UTF-8, LF-only line endings).
     *
     * @param directory the target directory; non-null
     * @param project   the project; non-null
     * @throws IOException on a write failure
     */
    public static void dumpProjectTo(Path directory, ExecutableLoweredProject project)
            throws IOException {
        Objects.requireNonNull(directory, "directory must not be null");
        Files.createDirectories(directory);
        Files.write(directory.resolve(MANIFEST_FILE_NAME), withLf(dumpManifest(project)));
        Path modulesDir = directory.resolve(MODULES_DIR_NAME);
        Files.createDirectories(modulesDir);
        for (ModuleDump dump : dumpProject(project).modules()) {
            Files.write(modulesDir.resolve(moduleFileName(dump.moduleId())), withLf(dump.bytes()));
        }
    }
}
