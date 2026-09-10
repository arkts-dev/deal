package deal.test.feature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The strict ISSUE-0111 feature fixture catalog (declarations page D12):
 * loads a corpus directory of unchanged DEAL roots with explicit sidecar
 * metadata, validates catalog closure (sidecar/source accounting,
 * duplicate canonical aliases, unresolved support edges, unreachable
 * support-only sources), enforces the architecture-owned
 * {@link FeatureBackendMatrix}, and publishes an ordered record list for
 * production execution.
 *
 * <p>Layout contract: a sidecar is any {@code *.sidecar.json} file under
 * the corpus root. A sidecar's parent directory is the record directory;
 * the canonical record id is the sidecar's corpus-relative path without
 * the {@code .sidecar.json} suffix (e.g. {@code int32/truncating-arith}).
 * Every file under a record directory other than the sidecar is that
 * record's fixture source (copied byte-identically into the temporary
 * exact-v1.2 project at execution time — the gate never rewrites fixture
 * source). A record directory that carries a {@code manifest-inject.json}
 * pins a malformed-manifest project: the gate publishes its exact bytes
 * as the project {@code deal.json}, and the sidecar must pin the exact
 * {@code manifestErrorFragment} the resulting E2010 message carries — a
 * code-only match against a manifest-discovery E2010 would be vacuous, so
 * the pairing is a hard catalog rule in both directions. A directory is a
 * support-only directory when no sidecar lives in its subtree and it is
 * not inside a record directory; support-only directories must be
 * reachable from at least one record's {@code support} closure (a listed
 * path covers its whole subtree). A record directory may itself appear in
 * another record's {@code support} only through the explicit dual-role
 * rule (the listing record imports it). {@code linkedRecord} references
 * resolve against the same catalog by canonical id.</p>
 *
 * <p>Catalog validation failures are hard {@link CatalogFailure}s: a
 * malformed sidecar, a matrix violation, an orphan/unlisted source, an
 * unresolved or unused support edge, a duplicate alias, a broken
 * manifest-inject/fragment pairing, or a broken linked record aborts the
 * gate before any compilation — never a skip, never a partial record
 * list.</p>
 */
public final class V12FeatureFixtureCatalog {

    /** The sidecar file-name suffix. */
    public static final String SIDECAR_SUFFIX = ".sidecar.json";

    /** The pinned malformed-manifest fixture name (the gate's deal.json). */
    public static final String MANIFEST_INJECT = "manifest-inject.json";

    private final Path corpusRoot;
    private final Map<String, RecordEntry> records;
    private final List<RecordEntry> orderedRecords;
    private final Map<String, Path> supportDirectories;

    /** One validated catalog record plus its on-disk layout. */
    public record RecordEntry(String id, Path directory, Path sidecarPath,
                              V12FeatureMetadata metadata) {
    }

    private V12FeatureFixtureCatalog(Path corpusRoot,
                                     Map<String, RecordEntry> records,
                                     List<RecordEntry> orderedRecords,
                                     Map<String, Path> supportDirectories) {
        this.corpusRoot = corpusRoot;
        this.records = records;
        this.orderedRecords = orderedRecords;
        this.supportDirectories = supportDirectories;
    }

    /**
     * Loads and validates the catalog under {@code corpusRoot}.
     *
     * @param corpusRoot the corpus directory; must exist
     * @return the validated catalog
     * @throws CatalogFailure on any closure, metadata, or matrix defect
     */
    public static V12FeatureFixtureCatalog load(Path corpusRoot) {
        Path root = corpusRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new CatalogFailure(
                "feature corpus root does not exist: " + root);
        }
        try {
            return loadInternal(root);
        } catch (IOException e) {
            throw new CatalogFailure(
                "cannot walk the feature corpus " + root + ": " + e.getMessage());
        }
    }

    private static V12FeatureFixtureCatalog loadInternal(Path root)
            throws IOException {
        List<Path> sidecars = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().endsWith(SIDECAR_SUFFIX))
                .sorted()
                .forEach(sidecars::add);
        }
        if (sidecars.isEmpty()) {
            throw new CatalogFailure(
                "feature corpus " + root + " carries no sidecar records");
        }

        Map<String, RecordEntry> records = new LinkedHashMap<>();
        for (Path sidecar : sidecars) {
            String id = canonicalId(root, sidecar);
            if (records.containsKey(id)) {
                throw new CatalogFailure(
                    "duplicate canonical alias '" + id + "' ("
                        + records.get(id).sidecarPath() + " and " + sidecar + ")");
            }
            String text = readText(sidecar);
            V12FeatureMetadata metadata = V12FeatureMetadata.parse(id, text);
            Path directory = sidecar.getParent();
            if (directory == null) {
                throw new CatalogFailure("sidecar without a directory: " + sidecar);
            }
            if (listSourceFiles(directory, sidecar).isEmpty()) {
                throw new CatalogFailure("sidecar " + id
                    + " carries no fixture source");
            }
            // The manifest policy / inject inventory: every
            // manifest-inject.json in the record is cataloged (the gate
            // publishes each as deal.json beside it), and the policy pins
            // the exact inventory shape. A half-pairing would let a
            // discovery E2010 pass on a code-only match (the
            // review-proven vacuity), so every mismatch fails here before
            // any compilation.
            List<Path> injects = listInjectFiles(directory);
            switch (metadata.manifestPolicy()) {
                case GENERATED -> {
                    if (!injects.isEmpty()) {
                        throw new CatalogFailure("record '" + id
                            + "' carries " + MANIFEST_INJECT + " ("
                            + injects.get(0).getFileName()
                            + ") but manifestPolicy is generated");
                    }
                }
                case INJECT -> {
                    if (injects.size() != 1
                            || !injects.get(0).getParent().equals(directory)) {
                        throw new CatalogFailure("record '" + id
                            + "' requires exactly one root "
                            + MANIFEST_INJECT + " under manifestPolicy inject");
                    }
                }
                case MISSING -> {
                    if (!injects.isEmpty()) {
                        throw new CatalogFailure("record '" + id
                            + "' carries " + MANIFEST_INJECT + " but "
                            + "manifestPolicy missing writes no manifest");
                    }
                }
                case MULTIPLE -> {
                    if (injects.size() < 2) {
                        throw new CatalogFailure("record '" + id
                            + "' requires at least two "
                            + MANIFEST_INJECT + " files (root plus nested) "
                            + "under manifestPolicy multiple");
                    }
                }
            }
            // The native plan's declaration files must exist in the record.
            if (metadata.nativePlan() != null) {
                for (V12FeatureMetadata.NativeEntry entry
                        : metadata.nativePlan().entries()) {
                    Path declaration = directory
                        .resolve(entry.declaration()).normalize();
                    if (!declaration.startsWith(directory)
                            || !Files.isRegularFile(declaration)) {
                        throw new CatalogFailure("record '" + id
                            + "' native entry '"
                            + entry.importSpecifier()
                            + "' names a missing declaration '"
                            + entry.declaration() + "'");
                    }
                }
            }
            records.put(id, new RecordEntry(id, directory, sidecar, metadata));
        }

        // Support-directory inventory: every directory that is not inside
        // a record directory and has no sidecar anywhere beneath it is a
        // support-only candidate. Feature group directories (holding
        // sidecar-bearing subtrees) and record subtrees are excluded.
        Map<String, Path> supportCandidates = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isDirectory)
                .filter(dir -> !dir.equals(root))
                .sorted(Comparator.comparing(p ->
                    root.relativize(p).toString()))
                .forEach(dir -> {
                    String rel = relPath(root, dir);
                    if (insideAnyRecord(root, records, dir)) {
                        return;
                    }
                    if (subtreeHasSidecar(dir)) {
                        return;
                    }
                    supportCandidates.putIfAbsent(rel, dir);
                });
        }

        // Direct support edges plus the transitive closure.
        Map<String, Path> supportDirectories = new LinkedHashMap<>();
        Set<String> reachable = new LinkedHashSet<>();
        for (RecordEntry entry : records.values()) {
            for (String support : entry.metadata().support()) {
                if (records.containsKey(support)) {
                    // Explicit dual-role ownership (declarations D12).
                    reachable.add(support);
                    continue;
                }
                Path target = root.resolve(support).normalize();
                if (!target.startsWith(root) || !Files.isDirectory(target)) {
                    throw new CatalogFailure("record '" + entry.id()
                        + "' lists an unresolved support path '" + support + "'");
                }
                supportDirectories.put(support, target);
                reachable.add(support);
            }
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (RecordEntry entry : records.values()) {
                for (String support : entry.metadata().support()) {
                    if (reachable.contains(support)
                            && records.containsKey(support)) {
                        for (String inner : records.get(support).metadata()
                                .support()) {
                            if (reachable.add(inner)) {
                                grew = true;
                            }
                        }
                    }
                }
            }
        }
        // Every support-only candidate must be covered by a reachable
        // support path (a listed path covers its whole subtree).
        for (Map.Entry<String, Path> candidate : supportCandidates.entrySet()) {
            boolean covered = false;
            for (String listed : reachable) {
                Path listedPath = supportDirectories.containsKey(listed)
                    ? supportDirectories.get(listed)
                    : root.resolve(listed);
                if (candidate.getValue().startsWith(listedPath)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                throw new CatalogFailure("support-only directory '"
                    + candidate.getKey()
                    + "' is not reachable from any record's support graph");
            }
        }
        for (Map.Entry<String, Path> listed : supportDirectories.entrySet()) {
            if (listSourceFiles(listed.getValue(), null).isEmpty()) {
                throw new CatalogFailure("support path '" + listed.getKey()
                    + "' lists an empty directory (unused support)");
            }
        }

        V12FeatureFixtureCatalog catalog = new V12FeatureFixtureCatalog(root,
            records, List.copyOf(records.values()), supportDirectories);
        for (RecordEntry entry : records.values()) {
            String violation = FeatureBackendMatrix.validate(entry.metadata(),
                catalog);
            if (violation != null) {
                throw new CatalogFailure("record '" + entry.id()
                    + "' violates the backend matrix: " + violation);
            }
        }
        return catalog;
    }

    private static String relPath(Path root, Path path) {
        return root.relativize(path).toString()
            .replace(path.getFileSystem().getSeparator(), "/");
    }

    private static boolean insideAnyRecord(Path root,
                                           Map<String, RecordEntry> records,
                                           Path dir) {
        for (RecordEntry entry : records.values()) {
            if (dir.startsWith(entry.directory())) {
                return true;
            }
        }
        return false;
    }

    private static boolean subtreeHasSidecar(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.anyMatch(p -> Files.isRegularFile(p)
                && p.getFileName().toString().endsWith(SIDECAR_SUFFIX));
        } catch (IOException e) {
            throw new CatalogFailure(
                "cannot inspect directory " + dir + ": " + e.getMessage());
        }
    }

    private static String canonicalId(Path root, Path sidecar) {
        String rel = relPath(root, sidecar);
        return rel.substring(0, rel.length() - SIDECAR_SUFFIX.length());
    }

    /** Every manifest-inject.json under a record directory, sorted. */
    private static List<Path> listInjectFiles(Path directory)
            throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().equals(MANIFEST_INJECT))
                .sorted()
                .forEach(files::add);
        }
        return files;
    }

    private static List<Path> listSourceFiles(Path directory, Path except)
            throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.equals(except))
                .filter(p -> !p.getFileName().toString()
                    .endsWith(SIDECAR_SUFFIX))
                .sorted()
                .forEach(files::add);
        }
        return files;
    }

    private static String readText(Path file) {
        try {
            return new String(Files.readAllBytes(file),
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CatalogFailure(
                "cannot read sidecar " + file + ": " + e.getMessage());
        }
    }

    /** The ordered validated records (deterministic: sidecar path order). */
    public List<RecordEntry> records() {
        return orderedRecords;
    }

    /** The record with the canonical id, or null. */
    public V12FeatureMetadata recordById(String id) {
        RecordEntry entry = records.get(id);
        return entry == null ? null : entry.metadata();
    }

    /** The corpus root (absolute, normalized). */
    public Path corpusRoot() {
        return corpusRoot;
    }

    /** Every manifest-inject.json of one record, sorted (relative to
     *  the record directory). */
    public List<Path> recordInjectFiles(RecordEntry entry) {
        try {
            return listInjectFiles(entry.directory());
        } catch (IOException e) {
            throw new CatalogFailure("cannot list inject files of record '"
                + entry.id() + "': " + e.getMessage());
        }
    }

    /** All fixture files of one record (sidecar excluded), sorted. */
    public List<Path> recordSourceFiles(RecordEntry entry) {
        try {
            return listSourceFiles(entry.directory(), entry.sidecarPath());
        } catch (IOException e) {
            throw new CatalogFailure("cannot list sources of record '"
                + entry.id() + "': " + e.getMessage());
        }
    }

    /** The on-disk directory of a support path (record or support-only). */
    public Path supportDirectory(String supportPath) {
        if (records.containsKey(supportPath)) {
            return records.get(supportPath).directory();
        }
        return supportDirectories.get(supportPath);
    }

    /** A hard catalog failure: never a skip, never a partial catalog. */
    public static final class CatalogFailure extends RuntimeException {
        public CatalogFailure(String message) {
            super(message);
        }
    }
}
