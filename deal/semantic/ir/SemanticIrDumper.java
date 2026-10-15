package deal.semantic.ir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import deal.semantic.ClassConstructionValidator;

/**
 * The deterministic semantic dumper of {@code deal.semantic-ir/2}
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
 *   "bodyTable": { "blockOps": [ {"block": <block>, "ops": [<op>, ...]}, ... ],
 *                  "opBlocks": [ {"op": <op>, "block": <block>}, ... ] },
 *   "classBindingFacts": { "importAliases": [<binding>, ...], "parameters": [<binding>, ...] },
 *   "classFactories": [ {"constructionEntry": <classFactory>, "factoryOp": <op>}, ... ],
 *   "classLayouts": [ {"key": <class>, "layout": {classId, fields: [{name, descriptor, required, defaultOwner}, ...]}}, ... ],
 *   "constructCoverage": [ {"construct": name, "opKinds": [name, ...]}, ... ],
 *   "exportPlan": [ {"name": str, "descriptor": str, "publishOpId": <op>}, ... ],
 *   "formatVersion": "deal.semantic-ir/2",
 *   "functionBindings": [ {"allocationId": int, "binding": <binding>}, ... ],
 *   "functions": [ {"key": <function>, "functionId": <function>, "descriptor": str,
 *                   "captures": [ {"binding": <binding>, "generation": int}, ... ],
 *                   "body": <block>, "sourceName": str|null}, ... ],
 *   "interfaceHash": hex,
 *   "jsonDefaults": [ {"jsonFromClassOp": <op>, "classDefaultOps": [<op>, ...]}, ... ],
 *   "loweringContextHash": hex,
 *   "moduleId": "<module path>",
 *   "moduleInit": { "imports": [<module>, ...], "initBlock": <block> },
 *   "ops": [ <op>, ... ],                       // produced operations in source order
 *   "requiredCapabilities": [name, ...],
 *   "semanticProfile": "DEAL_V1_3_INT32"
 * }
 * }</pre>
 *
 * <p><b>Project manifest shape (pinned):</b></p>
 *
 * <pre>{@code
 * {
 *   "entryModule": "<module path>",
 *   "formatVersion": "deal.semantic-ir/2",
 *   "semanticProfile": "DEAL_V1_3_INT32",
 *   "interfaceIndex": { "formatVersion": "deal.semantic-interface/1", "modules": [ ... ] },
 *   "classSeeds": [ { "key": <class>, "layout": {classId, fields: [ ... ]}, "owner": name }, ... ],
 *   "modules": [ "<module path>", ... ]        // dependency order (project record order)
 * }
 * }</pre>
 *
 * <p>The manifest carries the complete project framing with the ordered
 * module-path list in {@code modules}: the interface index and the class
 * registration seeds are the project's own records, and no unit fact — no
 * operation, body membership, function, class layout or companion — is
 * carried here. Combining the manifest with the ordered module documents
 * ({@link #reassembleProject(byte[], List)}) recovers the exact project;
 * the manifest and the module documents cannot establish independent
 * class/body authorities.</p>
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

    /** The pinned manifest field name {@code "interfaceIndex"}. */
    public static final String FIELD_INTERFACE_INDEX = "interfaceIndex";

    /** The pinned manifest field name {@code "classSeeds"}. */
    public static final String FIELD_CLASS_SEEDS = "classSeeds";

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
     */
    public static byte[] dumpModule(LoweredModuleUnit unit) {
        return ContractSnapshotCanonicalizer.serializeJson(toJson(unit));
    }

    /**
     * Dumps one module as canonical JSON text (UTF-8; observation surface
     * only).
     *
     */
    public static String dumpModuleText(LoweredModuleUnit unit) {
        return ContractSnapshotCanonicalizer.serializeText(toJson(unit));
    }

    // =========================================================================
    // Manifest and project mapping
    // =========================================================================

    /**
     * Maps a project onto the pinned manifest shape: the {@code
     * deal.semantic-ir/2} envelope, the entry module, the complete module
     * closure in dependency order (the project record's insertion order)
     * as a plain module-path list, the project's semantic profile, the
     * complete interface index and the class registration seeds. Module
     * references render as plain module paths — the same spelling the unit
     * text protocol uses for {@code moduleId}. A modules map key that
     * disagrees with the unit's own module identity is a producer defect
     * (the map key is the manifest's module path, so the disagreement must
     * fail encoding instead of dissolving).
     *
     */
    public static CanonicalJson.Value manifestJson(ExecutableLoweredProject project) {
        Objects.requireNonNull(project, "project must not be null");
        List<CanonicalJson.Value> modules = new ArrayList<>();
        for (Map.Entry<ModuleId, LoweredModuleUnit> entry : project.modules().entrySet()) {
            if (!entry.getKey().equals(entry.getValue().moduleId())) {
                throw new IllegalArgumentException(
                    "the modules map key " + entry.getKey()
                        + " does not match the unit's moduleId "
                        + entry.getValue().moduleId() + " (a producer defect)");
            }
            modules.add(CanonicalJson.str(entry.getKey().path()));
        }
        return CanonicalJson.obj(
            CanonicalJson.e(FIELD_ENTRY_MODULE,
                CanonicalJson.str(project.entryModule().path())),
            CanonicalJson.e(FIELD_FORMAT_VERSION,
                CanonicalJson.str(LoweredModuleUnit.FORMAT_VERSION)),
            CanonicalJson.e(FIELD_MODULES, CanonicalJson.arr(modules)),
            CanonicalJson.e(FIELD_SEMANTIC_PROFILE,
                CanonicalJson.str(project.semanticProfile().name())),
            CanonicalJson.e(FIELD_INTERFACE_INDEX,
                project.interfaceIndex().toCanonicalJson()),
            CanonicalJson.e(FIELD_CLASS_SEEDS,
                ContractSnapshotCanonicalizer.classSeedsJson(project.classSeeds())));
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
    // Checked reassembly (manifest plus ordered module documents)
    // =========================================================================

    /**
     * Reassembles the exact project from a dumped manifest and the ordered
     * module documents — the inverse of {@link #dumpProject(ExecutableLoweredProject)}
     * on the framing level. The manifest carries the complete project
     * framing ({@code formatVersion}, {@code semanticProfile}, {@code
     * entryModule}, {@code interfaceIndex}, {@code classSeeds}) and the
     * ordered module-path list; each document is one complete unit
     * document ({@link #dumpModule(LoweredUnit)}), supplied in the
     * manifest's order.
     *
     * <p>The document set must match the manifest exactly, and a
     * disagreement is a transport decode failure — the project record is
     * only built after every check passes, so no partial project is ever
     * exposed and no admission is started for a rejected document set:</p>
     *
     * <ul>
     *   <li>a missing document (fewer documents than manifest paths), an
     *       extra or foreign document (a document whose module identity is
     *       not the manifest's path at its position) and a repeated
     *       document all reject;</li>
     *   <li>documents supplied out of the manifest's order reject (the
     *       position is part of the framing, never reordered by the
     *       reassembly);</li>
     *   <li>a document that carries a different {@code semanticProfile} or
     *       an {@code interfaceHash} other than the manifest interface
     *       index digest is a mismatched document and rejects;</li>
     *   <li>the manifest's class registration seeds contradict its own
     *       interface index (a seed key/layout disagreement, an owner or
     *       class identity that is no declaration registration, a missing
     *       registration of an exported declaration class, or a field
     *       layout that differs from the interface class's declared fields
     *       in name, order, presence or declared type) — the seeds and the
     *       index are two representations of one declaration surface, so a
     *       disagreement is a rejected fact and never a second class
     *       authority;</li>
     *   <li>the manifest's class registration seeds contradict a module
     *       document's referring class facts — a carried {@code CLASS_NEW}
     *       of a declaration class whose default owner or contained layout
     *       disagrees with its registration, or whose class no registration
     *       covers — the seeds and the module documents are records of one
     *       construction surface, so the join rejects the disagreement
     *       instead of exposing a project with two class authorities;</li>
     *   <li>a manifest with a repeated module path, a missing entry
     *       module or an unknown/missing/extra framing field rejects.</li>
     * </ul>
     *
     * <p>The manifest carries no unit fact, so the reassembled units are
     * exactly the supplied documents: the manifest and the module records
     * cannot establish independent class/body authorities. Reassembly
     * grants no executable authority — the result is the parsed project,
     * and the existing admission and materialization boundaries keep their
     * own rules. The {@code moduleId} of a supplied {@link ModuleDump} is
     * a file-naming fact only: the document bytes decide which module is
     * being reassembled.</p>
     *
     */
    public static ContractSnapshotCanonicalizer.ParsedProject reassembleProject(
            byte[] manifest, List<byte[]> moduleDocuments) {
        Objects.requireNonNull(manifest, "manifest must not be null");
        Objects.requireNonNull(moduleDocuments, "moduleDocuments must not be null");
        CanonicalJson.Value manifestValue = CanonicalJson.parse(manifest);
        if (!(manifestValue instanceof CanonicalJson.Obj manifestObj)) {
            throw new SemanticIrTextDecodeException(
                "the project manifest must be a canonical JSON object, got "
                    + ContractSnapshotCanonicalizer.jsonKindName(manifestValue));
        }
        ContractSnapshotCanonicalizer.ParsedProjectHeader header =
            ContractSnapshotCanonicalizer.parseProjectHeader(manifestObj);
        Optional<String> incoherent =
            ClassConstructionValidator.checkProjectSeedCoherence(
                header.interfaceIndex(), header.classSeeds());
        if (incoherent.isPresent()) {
            throw new SemanticIrTextDecodeException(
                "the manifest class registration seeds contradict the manifest "
                    + "interface index: " + incoherent.get());
        }
        List<String> modulePaths = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        CanonicalJson.Arr rows = ContractSnapshotCanonicalizer
            .requireArray(manifestObj, FIELD_MODULES);
        for (int i = 0; i < rows.items().size(); i++) {
            String path = ContractSnapshotCanonicalizer.requireStringValue(
                rows.items().get(i), "modules[" + i + "]");
            try {
                new ModuleId(path);
            } catch (IllegalArgumentException e) {
                throw new SemanticIrTextDecodeException(
                    "invalid manifest module path \"" + path + "\" (modules[" + i
                        + "])");
            }
            if (!seen.add(path)) {
                throw new SemanticIrTextDecodeException(
                    "duplicate manifest module path \"" + path + "\" (modules[" + i
                        + "])");
            }
            modulePaths.add(path);
        }
        if (!seen.contains(header.entryModule())) {
            throw new SemanticIrTextDecodeException(
                "entryModule \"" + header.entryModule()
                    + "\" must be present in the manifest modules");
        }
        if (modulePaths.size() != moduleDocuments.size()) {
            throw new SemanticIrTextDecodeException(
                "the manifest lists " + modulePaths.size() + " module(s) but "
                    + moduleDocuments.size() + " module document(s) were supplied");
        }
        String indexDigest = header.interfaceIndex().interfaceIndexDigest();
        List<RawUnit> units = new ArrayList<>();
        for (int i = 0; i < modulePaths.size(); i++) {
            byte[] document = Objects.requireNonNull(moduleDocuments.get(i),
                "moduleDocuments[" + i + "]");
            CanonicalJson.Value documentValue = CanonicalJson.parse(document);
            if (!(documentValue instanceof CanonicalJson.Obj documentObj)) {
                throw new SemanticIrTextDecodeException(
                    "module document " + i + " must be a canonical JSON object, got "
                        + ContractSnapshotCanonicalizer.jsonKindName(documentValue));
            }
            RawUnit unit = ContractSnapshotCanonicalizer.parseUnit(documentObj);
            String expected = modulePaths.get(i);
            if (!unit.modulePath().equals(expected)) {
                throw new SemanticIrTextDecodeException(
                    "module document " + i + " carries moduleId \""
                        + unit.modulePath() + "\", not the manifest's \"" + expected
                        + "\" at that position");
            }
            if (!unit.semanticProfile().equals(header.semanticProfile())) {
                throw new SemanticIrTextDecodeException(
                    "module document " + i + " carries semanticProfile \""
                        + unit.semanticProfile() + "\", not the manifest's \""
                        + header.semanticProfile() + "\"");
            }
            if (!unit.interfaceHash().equals(indexDigest)) {
                throw new SemanticIrTextDecodeException(
                    "module document " + i + " carries interfaceHash \""
                        + unit.interfaceHash()
                        + "\", not the manifest interface index digest \""
                        + indexDigest + "\"");
            }
            units.add(unit);
        }
        Optional<ClassConstructionValidator.SeedModuleViolation> seedModules =
            ClassConstructionValidator.checkProjectSeedModuleCoherence(
                header.classSeeds(), units);
        if (seedModules.isPresent()) {
            throw new SemanticIrTextDecodeException(
                "the manifest class registration seeds contradict the module "
                    + "documents: " + seedModules.get().reason());
        }
        return new ContractSnapshotCanonicalizer.ParsedProject(header.formatVersion(),
            header.semanticProfile(), header.entryModule(), header.interfaceIndex(),
            header.classSeeds(), units);
    }

    /**
     * Reassembles the project of a complete dump record from its manifest
     * and its ordered module documents.
     *
     */
    public static ContractSnapshotCanonicalizer.ParsedProject reassembleProject(
            ProjectDump dump) {
        Objects.requireNonNull(dump, "dump must not be null");
        List<byte[]> documents = new ArrayList<>();
        for (ModuleDump module : dump.modules()) {
            documents.add(module.bytes());
        }
        return reassembleProject(dump.manifest(), documents);
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
