package deal.publication;

import deal.distribution.DistributionHome;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * The transactional whole-project artifact publication owner of the
 * public path (design source {@code whole-project-artifact-publication}
 * D1-D6, instantiated per the canonical
 * {@code semantic-profile-route-foundation} F6 stage → validate → swap
 * contract):
 *
 * <ol>
 *   <li><b>Stage.</b> Every output byte of one compilation is written
 *       into a fresh staging tree
 *       {@code S = <root>.deal-stage-<pid>-<nonce>} — a sibling of the
 *       publication root on the same filesystem — where the nonce is a
 *       per-invocation unique suffix (process id plus random UUID) that
 *       exists in on-disk tree names only, never in any artifact
 *       content. Nothing is written into the live root except the
 *       publish step. The exclusive per-root lock
 *       {@code <root>.deal-publish.lock} is held from staging start to
 *       publish end: an in-JVM reentrant gate serializes concurrent
 *       invocations in one JVM (two {@code FileChannel} locks of one
 *       JVM overlap instead of blocking), and the OS file lock
 *       serializes cross-process invocations; a stale lock of a dead
 *       JVM is reclaimed by the OS, so recovery never deletes the lock
 *       file (it stays beside the root, inert).</li>
 *   <li><b>Publish.</b> All-or-nothing, under the lock: crash recovery
 *       runs at publish start; with a prior set ({@code live} present)
 *       the stager atomically moves {@code live → retired},
 *       {@code S → live}, then deletes the retired set — restoring
 *       {@code retired → live} and failing when the second move fails;
 *       with a fresh root ({@code live} absent) the retire step is a
 *       no-op and the single {@code S → live} move publishes — a failed
 *       fresh-root move removes the stage tree and leaves the root
 *       without a live set. Observers never see a mixed set: the swap
 *       window exposes either the retired set under its recovery name
 *       or no live set. No within-run fallback exists: a failed publish
 *       never re-emits or reroutes.</li>
 * </ol>
 *
 * <p><b>Failure semantics (D4).</b> A failed move throws
 * {@link PublishFailure} after restoring per the rules above; a failed
 * retired delete after a successful swap is tolerated (the leftover
 * retired sibling is inert and removed by the next publish start's
 * recovery); a staging write failure surfaces as the {@code IOException}
 * of the staging call. The consuming orchestrator renders every such
 * failure as the pinned deterministic compiler I/O diagnostic
 * {@code deal: cannot publish artifacts to '<root>': <reason>} on
 * stderr with exit 1 — this package produces no diagnostic of its own
 * (no new diagnostic code; E6005 stays reserved).</p>
 *
 * <p><b>Crash recovery (D2/F6).</b> Recovery runs only while holding the
 * per-root lock at publish start: any observed
 * {@code <root>.deal-stage-*}/{@code <root>.deal-retired-*} sibling
 * directory belongs to a dead invocation. When {@code live} is absent
 * and a retired set is present (a torn publish after the first move),
 * recovery restores the lexicographically smallest retired tree to
 * {@code live} <b>and removes every observed stage sibling at the same
 * publish start</b>; otherwise it removes the stale trees. The current
 * invocation's own stage tree is excluded by name.</p>
 *
 * <p><b>Distribution copies (D6).</b> The runtime and stdlib deployment
 * copies resolve through {@link DistributionHome} in the pinned
 * three-tier order — project-local surface first, then the language
 * distribution (classpath resources, then the {@code DEAL_HOME}
 * filesystem layout), then the checkout CWD dev fallback — so the
 * transactional set is content-correct outside the checkout and a
 * project-local {@code std/} override always stages its own bytes.</p>
 */
public final class PublicationStager implements AutoCloseable {

    /** The on-disk staging-tree name marker (D2). */
    public static final String STAGE_TREE_MARKER = ".deal-stage-";

    /** The on-disk retired-tree name marker (D2). */
    public static final String RETIRED_TREE_MARKER = ".deal-retired-";

    /** The per-root lock-file suffix ({@code <root>.deal-publish.lock}). */
    public static final String LOCK_SUFFIX = ".deal-publish.lock";

    // =========================================================================
    // Test-only deterministic fault seam for the pinned publish/recovery
    // branches
    // =========================================================================

    /**
     * The deterministic fault seam the publication tests install to
     * prove the pinned publish/recovery branches no filesystem state
     * can force reliably (D4 verification): a fresh-root publish whose
     * single {@code S → live} move fails, a prior-set publish whose
     * second move fails and must restore {@code retired → live}, a
     * failed retire move, a failed restore, a staging failure, and the
     * tolerated retired delete. The seam is test-only: the production
     * path installs no fault (the default is a no-op) and no production
     * call site references the installer.
     */
    @FunctionalInterface
    public interface PublishFault {

        /** The no-op default; the production path never installs a fault. */
        PublishFault NONE = step -> { };

        /**
         * Invoked at the named pinned step, optionally throwing the
         * IOException the stager must survive per the D2 recovery
         * branches.
         *
         * @param step the pinned step name
         * @throws IOException the injected failure
         */
        void at(String step) throws IOException;
    }

    /** The seam step invoked immediately after the staging tree is created. */
    public static final String FAULT_STEP_STAGING_BEGAN = "after-staging-begin";

    /** The seam step invoked before the {@code live → retired} move. */
    public static final String FAULT_STEP_RETIRE_MOVE = "retire-move";

    /** The seam step invoked before the {@code S → live} move (both branches). */
    public static final String FAULT_STEP_STAGE_TO_LIVE = "stage-to-live";

    /** The seam step invoked before the {@code retired → live} restore. */
    public static final String FAULT_STEP_RESTORE = "restore";

    /** The seam step invoked before the post-swap retired delete (tolerated). */
    public static final String FAULT_STEP_DELETE_RETIRED = "delete-retired";

    private static volatile PublishFault fault = PublishFault.NONE;

    /**
     * Installs the test-only publish fault (never called by production
     * code).
     */
    public static void installPublishFault(PublishFault injected) {
        fault = Objects.requireNonNull(injected, "injected must not be null");
    }

    /** Clears the test-only publish fault (restores the production no-op). */
    public static void clearPublishFault() {
        fault = PublishFault.NONE;
    }

    // =========================================================================
    // The pinned publish failure
    // =========================================================================

    /**
     * A publish-step I/O failure (D4): a move (or recovery move) error
     * after the stager already applied the D2 restore/cleanup rules.
     * The consuming orchestrator renders it as the pinned deterministic
     * compiler I/O diagnostic {@code deal: cannot publish artifacts to
     * '<root>': <reason>} on stderr with exit 1.
     */
    public static final class PublishFailure extends IOException {

        private final Path publicationRoot;

        private PublishFailure(Path publicationRoot, String reason) {
            super(reason == null ? "unknown publish failure" : reason);
            this.publicationRoot = publicationRoot;
        }

        /** The publication root whose live set the failed publish targeted. */
        public Path publicationRoot() {
            return publicationRoot;
        }
    }

    // =========================================================================
    // Per-root in-JVM serialization gate
    // =========================================================================

    private static final ConcurrentHashMap<String, ReentrantLock> ROOT_LOCKS =
        new ConcurrentHashMap<>();

    /**
     * The per-root in-JVM serialization gate: one lock per canonical
     * publication-root path. Two concurrent invocations of the same
     * root in one JVM serialize on this gate (two {@code FileChannel}
     * locks of one JVM on the same file overlap instead of blocking,
     * so the gate is the same-JVM serialization surface while the OS
     * file lock serializes cross-process invocations).
     */
    private static ReentrantLock rootGate(Path publicationRoot) {
        String key = publicationRoot.toAbsolutePath().normalize().toString();
        return ROOT_LOCKS.computeIfAbsent(key, k -> new ReentrantLock());
    }

    // =========================================================================
    // Instance state
    // =========================================================================

    private final Path root;
    private final String nonce;
    private Path stageTree;
    private ReentrantLock jvmGate;
    private FileChannel lockChannel;
    private FileLock lock;
    private final Set<String> stagedPaths = new LinkedHashSet<>();
    private ArtifactSet stagedSnapshot;
    private boolean published;
    private boolean discarded;

    private PublicationStager(Path root) {
        this.root = root;
        this.nonce = "pid" + ProcessHandle.current().pid() + "-"
            + UUID.randomUUID();
    }

    /**
     * The stager for one publication root (absolute, normalized).
     * Nothing is created or locked until the first staging operation —
     * a compilation that writes nothing leaves no residue.
     */
    public static PublicationStager forRoot(Path publicationRoot) {
        Objects.requireNonNull(publicationRoot, "publicationRoot");
        return new PublicationStager(
            publicationRoot.toAbsolutePath().normalize());
    }

    /** The publication root (absolute, normalized). */
    public Path publicationRoot() {
        return root;
    }

    /**
     * The staging tree, or {@code null} before the first staging
     * operation (the tree and the per-root lock are created lazily at
     * staging start).
     */
    public Path stageTree() {
        return stageTree;
    }

    /** True once {@link #publish()} completed the atomic swap. */
    public boolean published() {
        return published;
    }

    /**
     * The modeled set of everything staged so far, read from the live
     * staging tree under its final relative paths (cached: the first
     * call freezes the snapshot, and {@link #publish()} freezes it
     * before the swap).
     */
    public ArtifactSet stagedSet() throws IOException {
        if (stagedSnapshot == null) {
            stagedSnapshot = readStagedSet();
        }
        return stagedSnapshot;
    }

    /**
     * The published set: the frozen snapshot the successful
     * {@link #publish()} swapped into the live root.
     *
     * @throws IllegalStateException when nothing was published yet
     */
    public ArtifactSet publishedSet() {
        if (!published) {
            throw new IllegalStateException(
                "nothing published yet for '" + root + "'");
        }
        return stagedSnapshot;
    }

    // =========================================================================
    // Staging
    // =========================================================================

    /**
     * Stages one artifact at its final relative path under the
     * publication root, creating the staging tree (and acquiring the
     * per-root lock) at the first staging operation of this stager.
     *
     * @param relativePath the final {@code '/'}-separated relative path
     *                     (the {@link Artifact} canonical grammar)
     * @param content      the artifact bytes
     * @throws IOException              on a staging write failure (D4:
     *                                  nothing is published; the caller
     *                                  renders the pinned publish
     *                                  diagnostic)
     * @throws IllegalArgumentException on a non-canonical relative path
     * @throws IllegalStateException    when the stager was discarded or
     *                                  already published
     */
    public void stage(String relativePath, byte[] content)
            throws IOException {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(content, "content");
        requireStagingOpen();
        Artifact.validateRelativePath(relativePath);
        Path target = stageTarget(relativePath);
        Files.write(target, content);
        stagedPaths.add(relativePath);
    }

    /**
     * The staging-tree filesystem path for one final relative path,
     * creating parent directories inside the stage tree. Backend-driven
     * writers (the Lua emitter) write their artifact files at the
     * returned path; the path is registered as staged, so the set's
     * snapshot reads the bytes the writer produced.
     *
     * @param relativePath the final {@code '/'}-separated relative path
     *                     (the {@link Artifact} canonical grammar)
     * @return the stage-tree-resolved path (parents created)
     * @throws IOException              on a staging failure
     * @throws IllegalArgumentException on a non-canonical relative path
     * @throws IllegalStateException    when the stager was discarded or
     *                                  already published
     */
    public Path stagePath(String relativePath) throws IOException {
        Objects.requireNonNull(relativePath, "relativePath");
        requireStagingOpen();
        Artifact.validateRelativePath(relativePath);
        Path target = stageTarget(relativePath);
        stagedPaths.add(relativePath);
        return target;
    }

    /**
     * Stages the runtime library copy ({@code deal/runtime.lua} or
     * {@code deal/runtime.js}) resolved through {@link DistributionHome}
     * in the pinned order — classpath resources, then the
     * {@code DEAL_HOME} filesystem layout, then the checkout CWD dev
     * fallback (no project-local location is pinned for the runtime,
     * D6).
     *
     * @param runtimeFileName the runtime file name ({@code
     *                        deal/runtime.lua} or {@code deal/runtime.js})
     * @param home            the project's distribution resolver
     * @return the resolved source, or empty when the runtime is absent
     *         at every tier (the caller's E6000 owns that failure)
     * @throws IOException on a staging write failure
     */
    public Optional<DistributionHome.ResolvedSource> stageRuntimeCopy(
            String runtimeFileName, DistributionHome home)
            throws IOException {
        Objects.requireNonNull(runtimeFileName, "runtimeFileName");
        Objects.requireNonNull(home, "home");
        Optional<DistributionHome.ResolvedSource> source =
            home.resolveRuntimeSource(runtimeFileName);
        if (source.isEmpty()) {
            return Optional.empty();
        }
        try (InputStream in = source.get().open()) {
            stage(runtimeFileName, in.readAllBytes());
        }
        return source;
    }

    /**
     * Stages one spec-listed stdlib implementation copy
     * ({@code std/<moduleName>.<extension>}) resolved through
     * {@link DistributionHome} in the pinned order — project-local
     * surface first, then the language distribution, then the checkout
     * CWD dev fallback (D6) — so a project-local {@code std/} override
     * always stages its own bytes.
     *
     * @param moduleName the stdlib module bare name ({@code console},
     *                   {@code string}, {@code table}, {@code json},
     *                   {@code math}, {@code time})
     * @param extension  {@code lua} or {@code js}
     * @param home       the project's distribution resolver
     * @return the resolved source, or empty when the module is absent
     *         at every tier (omission semantics, unchanged)
     * @throws IOException on a staging write failure
     */
    public Optional<DistributionHome.ResolvedSource> stageStdlibCopy(
            String moduleName, String extension, DistributionHome home)
            throws IOException {
        Objects.requireNonNull(moduleName, "moduleName");
        Objects.requireNonNull(extension, "extension");
        Objects.requireNonNull(home, "home");
        Optional<DistributionHome.ResolvedSource> source =
            home.resolveStdlibImplementation(moduleName, extension);
        if (source.isEmpty()) {
            return Optional.empty();
        }
        String relativePath = "std/" + moduleName + "." + extension;
        try (InputStream in = source.get().open()) {
            stage(relativePath, in.readAllBytes());
        }
        return source;
    }

    // =========================================================================
    // Publish
    // =========================================================================

    /**
     * Publishes the staged set atomically under the per-root lock
     * (D2/F6): crash recovery at publish start, then the
     * retire → swap → delete sequence with the restore-on-failure
     * branch. On success the live set at the root equals exactly the
     * staged set and the lock is released; on failure the D2 restore
     * rules already ran (the prior live set is untouched or restored,
     * the stage tree is removed) and a {@link PublishFailure} carries
     * the reason.
     *
     * @throws PublishFailure      on a publish-step I/O failure
     * @throws IOException         on a recovery/staging failure
     * @throws IllegalStateException when the stager was discarded or
     *                               already published
     */
    public void publish() throws IOException {
        if (discarded) {
            throw new IllegalStateException(
                "discarded publication stager for '" + root + "'");
        }
        if (published) {
            throw new IllegalStateException(
                "already published for '" + root + "'");
        }
        ensureStaging();
        try {
            // Crash recovery at publish start, under the lock: a torn
            // publish (live absent, retired present) restores the
            // retired set, and every observed stale stage/retired
            // sibling of a dead invocation is removed.
            recoverStaleSiblings();

            // The frozen snapshot of the staged set — read from the
            // live staging tree BEFORE the swap, so the published set
            // the successful publish records is exactly the set that
            // moved into the live root.
            ArtifactSet set = readStagedSet();

            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                    throw new PublishFailure(root,
                        "the live path exists and is not a directory: "
                            + root);
                }
                // Prior set present: retire, swap, delete retired.
                Path retired = retiredSibling();
                fault.at(FAULT_STEP_RETIRE_MOVE);
                atomicMove(root, retired);
                try {
                    fault.at(FAULT_STEP_STAGE_TO_LIVE);
                    atomicMove(stageTree, root);
                } catch (IOException moveFailure) {
                    // Second move failed: restore retired -> live and
                    // fail. A failed restore leaves the retired set
                    // under its recovery name for the next publish
                    // start's recovery.
                    try {
                        fault.at(FAULT_STEP_RESTORE);
                        atomicMove(retired, root);
                    } catch (IOException restoreFailure) {
                        // Torn state: live absent + retired present;
                        // the next publish start restores it under the
                        // lock.
                    }
                    deleteRecursivelyIfPresent(stageTree);
                    throw new PublishFailure(root, moveFailure.getMessage());
                }
                // The swap is complete: the retired set is deleted. A
                // failed delete leaves a retired sibling beside the
                // live set — the post-swap crash window the next
                // publish start's recovery removes; the live set is
                // the new set.
                deleteRetiredBestEffort(retired);
            } else {
                // Fresh root: the retire step is a no-op and the single
                // S -> live move publishes; a failure removes the stage
                // tree and leaves the root without a live set.
                try {
                    fault.at(FAULT_STEP_STAGE_TO_LIVE);
                    atomicMove(stageTree, root);
                } catch (IOException moveFailure) {
                    deleteRecursivelyIfPresent(stageTree);
                    throw new PublishFailure(root, moveFailure.getMessage());
                }
            }
            stagedSnapshot = set;
        } catch (PublishFailure failure) {
            throw failure;
        } catch (IOException failure) {
            throw new PublishFailure(root, failure.getMessage());
        }

        published = true;
        releaseLock();
    }

    /**
     * Removes the staging tree and releases the per-root lock without
     * publishing (D4): a failed compilation leaves the prior live set
     * untouched (an absent prior set stays absent) and no staging
     * residue. Idempotent; a no-op after a successful publish.
     */
    public void discard() {
        if (discarded) {
            return;
        }
        discarded = true;
        deleteRecursivelyIfPresent(stageTree);
        releaseLock();
    }

    /** {@link #discard()} — the stage tree never survives the holder. */
    @Override
    public void close() {
        discard();
    }

    // =========================================================================
    // Under-lock crash recovery
    // =========================================================================

    /**
     * Crash recovery for this publication root, running only while this
     * stager holds the per-root lock (D2/F6): any observed
     * {@code <root>.deal-stage-*}/{@code <root>.deal-retired-*} sibling
     * directory belongs to a dead invocation. When {@code live} is
     * absent and a retired set is present, recovery restores the
     * lexicographically smallest retired tree to {@code live} and
     * removes every observed stage sibling at the same publish start;
     * otherwise it removes the stale trees. This invocation's own stage
     * tree is excluded by name.
     */
    private void recoverStaleSiblings() throws IOException {
        Path parent = root.getParent();
        if (parent == null) {
            return; // the filesystem root has no sibling namespace
        }
        String rootName = root.getFileName().toString();
        List<Path> stageTrees = siblingTrees(parent,
            rootName + STAGE_TREE_MARKER);
        List<Path> retiredTrees = siblingTrees(parent,
            rootName + RETIRED_TREE_MARKER);

        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)
                && !retiredTrees.isEmpty()) {
            // Torn publish after the first move: restore retired -> live …
            Path toRestore = retiredTrees.get(0);
            atomicMove(toRestore, root);
            retiredTrees = retiredTrees.subList(1, retiredTrees.size());
        }
        // … and remove every observed stale sibling at the same publish
        // start (the dead invocation's stage tree included).
        for (Path tree : stageTrees) {
            if (tree.equals(stageTree)) {
                continue; // the current invocation's own live staging tree
            }
            deleteRecursively(tree);
        }
        for (Path tree : retiredTrees) {
            deleteRecursively(tree);
        }
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /** The per-invocation retired sibling ({@code <root>.deal-retired-<pid>-<nonce>}). */
    private Path retiredSibling() {
        return root.resolveSibling(
            root.getFileName() + RETIRED_TREE_MARKER + nonce);
    }

    /**
     * Creates the staging tree and acquires the per-root lock (both
     * lazily, at staging start): the parent directories of the
     * publication root, the in-JVM reentrant gate, the
     * {@code <root>.deal-publish.lock} OS file lock (exclusive), then
     * the nonce-suffixed stage tree. The lock is held until
     * {@link #publish()} or {@link #discard()} releases it.
     */
    private void ensureStaging() throws IOException {
        if (stageTree != null) {
            return;
        }
        Path parent = root.getParent();
        if (parent == null) {
            throw new IOException("the publication root has no sibling"
                + " namespace: " + root);
        }
        // Output directories are created only in the write phase: the
        // parents exist for the sibling staging tree; the live root
        // itself appears only through the publish move.
        Files.createDirectories(parent);
        Path lockPath = root.resolveSibling(
            root.getFileName() + LOCK_SUFFIX);
        jvmGate = rootGate(root);
        jvmGate.lock();
        try {
            lockChannel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.lock();
            String stageName = root.getFileName() + STAGE_TREE_MARKER
                + nonce;
            Path tree = root.resolveSibling(stageName);
            Files.createDirectory(tree);
            stageTree = tree;
            fault.at(FAULT_STEP_STAGING_BEGAN);
        } catch (IOException | RuntimeException failure) {
            if (stageTree != null) {
                deleteRecursivelyIfPresent(stageTree);
                stageTree = null;
            }
            releaseLock();
            throw failure;
        }
    }

    /**
     * Resolves one relative path inside the stage tree, creating parent
     * directories; the canonical grammar plus the normalize/startsWith
     * check guarantee the target never escapes the tree
     * (defense-in-depth).
     */
    private Path stageTarget(String relativePath) throws IOException {
        ensureStaging();
        Path target = stageTree.resolve(relativePath).normalize();
        if (!target.startsWith(stageTree)) {
            throw new IOException(
                "artifact path escapes the staging tree: \""
                    + relativePath + "\"");
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return target;
    }

    /** The staged-path guard of the staging operations. */
    private void requireStagingOpen() {
        if (discarded) {
            throw new IllegalStateException(
                "discarded publication stager for '" + root + "'");
        }
        if (published) {
            throw new IllegalStateException(
                "already published for '" + root + "'");
        }
    }

    /**
     * Snapshots the staged set from the live staging tree: one artifact
     * per registered relative path, in first-registration order.
     */
    private ArtifactSet readStagedSet() throws IOException {
        List<Artifact> artifacts = new ArrayList<>(stagedPaths.size());
        for (String relativePath : stagedPaths) {
            Path target = stageTree.resolve(relativePath).normalize();
            artifacts.add(new Artifact(relativePath,
                Files.readAllBytes(target)));
        }
        return new ArtifactSet(artifacts);
    }

    /**
     * Releases the per-root lock (the OS file lock first, then the
     * in-JVM gate) — idempotent; every terminal path of the stager
     * calls it exactly once.
     */
    private void releaseLock() {
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException ignored) {
                // The channel close below releases the OS lock anyway.
            }
            lock = null;
        }
        if (lockChannel != null) {
            try {
                lockChannel.close();
            } catch (IOException ignored) {
                // Closing is best-effort; the OS reclaims locks of dead
                // JVMs and an open channel holds no lock after release.
            }
            lockChannel = null;
        }
        if (jvmGate != null) {
            jvmGate.unlock();
            jvmGate = null;
        }
    }

    /**
     * The tolerated post-swap retired delete (F6): an injected or real
     * delete failure leaves the retired sibling beside the live set —
     * the post-swap crash window the next publish start's recovery
     * removes; the live set is the new set either way.
     */
    private void deleteRetiredBestEffort(Path retired) {
        try {
            fault.at(FAULT_STEP_DELETE_RETIRED);
        } catch (IOException ignored) {
            // The tolerated (injected) delete failure: the retired
            // sibling stays beside the live set — the post-swap crash
            // window the next publish start's recovery removes; the
            // live set is the new set either way.
            return;
        }
        deleteRecursivelyIfPresent(retired);
    }

    private static void atomicMove(Path source, Path target)
            throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The observed sibling trees under one name prefix, sorted by name. */
    private static List<Path> siblingTrees(Path parent, String prefix)
            throws IOException {
        List<Path> trees = new ArrayList<>();
        try (Stream<Path> entries = Files.list(parent)) {
            for (Path entry : entries.toList()) {
                if (entry.getFileName().toString().startsWith(prefix)
                        && Files.isDirectory(entry,
                            LinkOption.NOFOLLOW_LINKS)) {
                    trees.add(entry);
                }
            }
        }
        trees.sort(Comparator.comparing(
            path -> path.getFileName().toString()));
        return trees;
    }

    private static void deleteRecursively(Path tree) throws IOException {
        if (!Files.exists(tree, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(tree)) {
            List<Path> paths =
                walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void deleteRecursivelyIfPresent(Path tree) {
        if (tree == null) {
            return;
        }
        try {
            deleteRecursively(tree);
        } catch (IOException ignored) {
            // A leftover sibling is inert: the next publish start's
            // recovery removes it under the lock.
        }
    }
}
