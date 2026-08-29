package deal.semantic;

import deal.diagnostics.CompilerDiagnostic;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticIrValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * The stage → validate → publish driver of the common-lowering foundation
 * (foundation F6; parent "Shared module emission and publication"
 * contract):
 *
 * <ol>
 *   <li><b>Stage.</b> Every routed module's {@link ModuleEmissionResult}
 *       is written into a fresh staging tree
 *       {@code S = <publicationRoot>.deal-stage-<planId>-<nonce>} — a
 *       sibling of the publication root, same filesystem — where the
 *       nonce is a per-invocation unique suffix (process id plus random
 *       UUID) and {@code planId} comes from the route-plan record. The
 *       nonce exists only in on-disk tree names, never in the plan, any
 *       record, or any hash. Nothing is written into the live artifact
 *       set during staging. A module whose emission reported failure has
 *       no staged artifact: its outputs are never written and any partial
 *       stage directory for it is discarded with the whole staging tree.</li>
 *   <li><b>Validate.</b> Every unit is validated through the closed
 *       {@link SemanticIrValidator} rules <b>before staging</b> (invalid
 *       semantic IR is the validator's E6005, parent D11), and every
 *       mixed edge through {@link TargetAbiValidator} on complete records
 *       after staging.</li>
 *   <li><b>Publish.</b> All-or-nothing: with a prior set ({@code live}
 *       present) the stager atomically moves {@code live → retired},
 *       {@code S → live}, then deletes the retired set — restoring
 *       {@code retired → live} and reporting failure when the second move
 *       fails; with a fresh root ({@code live} absent) the retire step is
 *       a no-op and the single {@code S → live} move publishes — a failed
 *       fresh-root move removes {@code S} and leaves the root without a
 *       live set. Any failure publishes none and leaves the prior set
 *       untouched (an absent prior set stays absent); the staging tree is
 *       removed. Observers never see a mixed set: the swap window exposes
 *       either the retired set under its recovery name or no live set,
 *       never a blend of old and new files. No within-run fallback
 *       exists: a failed emission/validation never reroutes or re-emits a
 *       module in the same invocation.</li>
 * </ol>
 *
 * <p><b>Concurrency and crash recovery.</b> The whole sequence is
 * serialized per publication root by an exclusive per-root lock
 * ({@link #rootLock(Path)}) held from staging start to publish end;
 * concurrent identical invocations (identical {@code planId}) serialize
 * instead of sharing trees, and their nonce-suffixed tree names are
 * unique. Crash recovery runs only while holding the lock at publish
 * start ({@link #recoverStaleSiblings(Path, Path)}): a sibling matching
 * {@code .deal-stage-*} or {@code .deal-retired-*} observed under the
 * lock belongs to a dead invocation — when {@code live} is absent and a
 * retired set is present, recovery restores {@code retired → live}
 * <b>and removes every observed {@code .deal-stage-*} sibling at the
 * same publish start</b>; otherwise it removes the stale trees. A live
 * concurrent invocation's trees can never be removed because it holds
 * the lock for its whole sequence.</p>
 *
 * <p><b>ISSUE-0239 boundary contract (enforced structurally).</b> Mixed
 * invocations flow all artifacts — retained and shared — through the
 * stager; retained sides stage first and complete each legacy
 * dependency's {@code TargetModuleAbi} realization fields from the staged
 * artifacts; {@code TargetAbiValidator} validates every mixed edge before
 * dependent shared modules' emission is consumed; and the stager rejects
 * an invocation that both stages and direct-writes the same root: the
 * live set is fingerprinted at staging start and re-checked at publish
 * start under the lock, and any drift raises E6005
 * ({@code STAGE_DIRECT_WRITE_CONFLICT}). In this epic public builds are
 * all-LEGACY, so the stager never sits on the public path and the
 * retained emitters' direct writes are untouched; internal
 * {@code COMMON_SHADOW} tests drive the stager with synthetic units and
 * synthetically completed ABI records, and a routed module's unit is
 * admitted only for SHARED (incl. shadow) route entries.</p>
 */
public final class ProjectArtifactStager {

    private ProjectArtifactStager() {
        // Static driver; no instances.
    }

    /** The on-disk staging-tree name marker (foundation F6). */
    public static final String STAGE_TREE_MARKER = ".deal-stage-";

    /** The on-disk retired-tree name marker (foundation F6). */
    public static final String RETIRED_TREE_MARKER = ".deal-retired-";

    // Stager rule ids (LoweringFailureDetail.validatorRule; producer-defect classes).
    /** A SHARED-routed module without an emission result. */
    public static final String STAGE_MISSING_UNIT = "STAGE_MISSING_UNIT";
    /** An emission result whose unit is not routed SHARED (or duplicated). */
    public static final String STAGE_UNIT_ROUTE_MISMATCH = "STAGE_UNIT_ROUTE_MISMATCH";
    /** A successful emission result without its emitted ABI manifest. */
    public static final String STAGE_MISSING_EMITTED_ABI = "STAGE_MISSING_EMITTED_ABI";
    /** An emitted ABI manifest contradicting its unit (owner/identity mismatch). */
    public static final String STAGE_EMITTED_ABI_MISMATCH = "STAGE_EMITTED_ABI_MISMATCH";
    /** Two staged artifacts carrying the same name. */
    public static final String STAGE_ARTIFACT_NAME_COLLISION =
        "STAGE_ARTIFACT_NAME_COLLISION";
    /** A publication with no routed module and no staged artifact. */
    public static final String STAGE_NO_STAGED_MODULES = "STAGE_NO_STAGED_MODULES";
    /** The live artifact set changed during staging (a direct write into the root). */
    public static final String STAGE_DIRECT_WRITE_CONFLICT = "STAGE_DIRECT_WRITE_CONFLICT";
    /** An atomic publish move failed. */
    public static final String STAGE_PUBLISH_MOVE_FAILED = "STAGE_PUBLISH_MOVE_FAILED";
    /** A staging/publication I/O failure. */
    public static final String STAGE_IO_FAILURE = "STAGE_IO_FAILURE";
    /** The live path exists but is not a directory. */
    public static final String STAGE_LIVE_NOT_DIRECTORY = "STAGE_LIVE_NOT_DIRECTORY";

    /** The pinned IR version carried by every stager-failure detail. */
    public static final String IR_VERSION = "deal.semantic-ir/1";

    /**
     * The publication outcome: whether the set was published, the staging
     * tree name the invocation used (null when no tree was created), the
     * published artifact set on success, and the diagnostics on failure.
     *
     * @param published     whether the invocation's artifact set replaced
     *                      the live set atomically
     * @param stageTreeName the on-disk staging tree name (planId plus
     *                      per-invocation nonce), or null when the flow
     *                      failed before creating the tree
     * @param liveSet       the published artifact set on success; empty
     *                      on failure
     * @param diagnostics   the failure diagnostics (emission diagnostics
     *                      or E6005); empty on success
     */
    public record PublicationOutcome(
        boolean published,
        String stageTreeName,
        List<StagedArtifact> liveSet,
        List<CompilerDiagnostic> diagnostics
    ) {

        public PublicationOutcome {
            liveSet = List.copyOf(liveSet);
            diagnostics = List.copyOf(diagnostics);
            if (published && stageTreeName == null) {
                throw new IllegalArgumentException(
                    "a successful publication records its staging tree name");
            }
            if (published && !diagnostics.isEmpty()) {
                throw new IllegalArgumentException(
                    "a successful publication carries no diagnostics");
            }
            if (!published && diagnostics.isEmpty()) {
                throw new IllegalArgumentException(
                    "a failed publication carries at least one diagnostic");
            }
        }

        /** A successful publication of the given set. */
        public static PublicationOutcome success(String stageTreeName,
                                                 List<StagedArtifact> liveSet) {
            return new PublicationOutcome(true, stageTreeName, liveSet, List.of());
        }

        /** A failed publication carrying the given diagnostics. */
        public static PublicationOutcome failure(List<CompilerDiagnostic> diagnostics) {
            return new PublicationOutcome(false, null, List.of(), diagnostics);
        }
    }

    // =========================================================================
    // Test-only deterministic fault seam for the pinned publish-recovery branches
    // =========================================================================

    /**
     * The deterministic fault seam the stage tests install to prove the
     * pinned publish/recovery branches that no filesystem state can force
     * reliably (foundation Verification 5): a fresh-root publish whose
     * single {@code S → live} move fails, and a prior-set publish whose
     * second move fails and must restore {@code retired → live}. The seam
     * is test-only: the production path installs no fault (the default is
     * a no-op) and no production call site references the installer.
     */
    @FunctionalInterface
    public interface PublishFault {

        /** The no-op default; the production path never installs a fault. */
        PublishFault NONE = step -> { };

        /**
         * Invoked at the named pinned step, optionally throwing the
         * IOException the stager must survive per the F6 recovery
         * branches.
         *
         * @param step the pinned step name
         * @throws IOException the injected failure
         */
        void at(String step) throws IOException;
    }

    /** The pinned seam step invoked immediately after the staging tree is created. */
    public static final String FAULT_STEP_STAGING_BEGAN = "after-staging-begin";

    /** The pinned seam step invoked after validation, before the publish fingerprint. */
    public static final String FAULT_STEP_POST_STAGING = "post-staging";

    /** The pinned seam step invoked before the {@code S → live} move (both branches). */
    public static final String FAULT_STEP_STAGE_TO_LIVE = "stage-to-live";

    private static volatile PublishFault fault = PublishFault.NONE;

    /** Installs the test-only publish fault (never called by production code). */
    public static void installPublishFault(PublishFault injected) {
        fault = Objects.requireNonNull(injected, "injected must not be null");
    }

    /** Clears the test-only publish fault (restores the production no-op). */
    public static void clearPublishFault() {
        fault = PublishFault.NONE;
    }

    // =========================================================================
    // Per-root serialization
    // =========================================================================

    private static final ConcurrentHashMap<String, ReentrantLock> ROOT_LOCKS =
        new ConcurrentHashMap<>();

    /**
     * The exclusive per-root lock (foundation F6): one lock per canonical
     * publication-root path, held from staging start to publish end. Two
     * concurrent invocations of the same root — identical
     * {@code planId} or not — serialize on this lock instead of sharing
     * staging/retired trees.
     *
     * @param publicationRoot the publication root; non-null
     * @return the per-root lock
     */
    public static ReentrantLock rootLock(Path publicationRoot) {
        Objects.requireNonNull(publicationRoot, "publicationRoot must not be null");
        String key = publicationRoot.toAbsolutePath().normalize().toString();
        return ROOT_LOCKS.computeIfAbsent(key, k -> new ReentrantLock());
    }

    // =========================================================================
    // The stage → validate → publish sequence
    // =========================================================================

    /**
     * Runs the full stage → validate → publish sequence for one
     * invocation under the per-root lock: unit validation (T6) before
     * staging, staging into the nonce-suffixed tree, complete-record
     * mixed-edge validation (TargetAbiValidator), drift rejection, crash
     * recovery at publish start, and the all-or-nothing atomic swap with
     * restore.
     *
     * @param publicationRoot the publication root whose live set is
     *                        atomically replaced; non-null
     * @param invocation      the release-owned invocation (T1) supplying
     *                        the project profile and the capability-
     *                        registry hash for the validator's comparison
     *                        facts; non-null
     * @param routePlan       the deterministic route plan (T10) whose
     *                        {@code planId} names the staging tree;
     *                        non-null
     * @param emissionResults the routed modules' emission results in
     *                        dependency order (synthetic in this epic);
     *                        non-null
     * @param abiEdges        the completed {@code TargetModuleAbi} records
     *                        (plan-time records completed during staging
     *                        plus emitted manifests); non-null
     * @param interfaceIndex  the project interface index (T8) the units
     *                        and ABI records were checked against; non-null
     * @return the publication outcome; never null
     */
    public static PublicationOutcome stageValidatePublish(
            Path publicationRoot,
            CompilerInvocation invocation,
            ModuleRoutePlan routePlan,
            List<ModuleEmissionResult> emissionResults,
            List<TargetModuleAbi> abiEdges,
            ProjectInterfaceIndex interfaceIndex) {
        Objects.requireNonNull(publicationRoot, "publicationRoot must not be null");
        Objects.requireNonNull(invocation, "invocation must not be null");
        Objects.requireNonNull(routePlan, "routePlan must not be null");
        Objects.requireNonNull(emissionResults, "emissionResults must not be null");
        Objects.requireNonNull(abiEdges, "abiEdges must not be null");
        Objects.requireNonNull(interfaceIndex, "interfaceIndex must not be null");

        Path root = publicationRoot.toAbsolutePath().normalize();
        ReentrantLock lock = rootLock(root);
        lock.lock();
        Path stageTree = null;
        String stageTreeName = null;
        try {
            // Live-set fingerprint before staging: the direct-write
            // rejection baseline (ISSUE-0239 boundary contract).
            Map<String, String> liveBefore = fingerprintLive(root);

            // Phase 1: the closed semantic-IR validator gate (T6) — every
            // unit is validated before any staging.
            Optional<CompilerDiagnostic> unitFailure = validateUnits(
                emissionResults, interfaceIndex, invocation);
            if (unitFailure.isPresent()) {
                return PublicationOutcome.failure(List.of(unitFailure.get()));
            }

            // Emission-coverage checks against the route plan (deterministic).
            Optional<CompilerDiagnostic> coverageFailure = verifyEmissionCoverage(
                routePlan, emissionResults, invocation);
            if (coverageFailure.isPresent()) {
                return PublicationOutcome.failure(List.of(coverageFailure.get()));
            }

            // Phase 2: staging into a fresh per-invocation tree.
            String nonce = "pid" + ProcessHandle.current().pid() + "-" + UUID.randomUUID();
            stageTreeName = root.getFileName() + STAGE_TREE_MARKER
                + routePlan.planId() + "-" + nonce;
            stageTree = root.resolveSibling(stageTreeName);
            Files.createDirectory(stageTree);
            fault.at(FAULT_STEP_STAGING_BEGAN);

            List<StagedArtifact> staged = new ArrayList<>();
            Set<String> stagedNames = new LinkedHashSet<>();
            for (ModuleEmissionResult result : emissionResults) {
                if (result.failed()) {
                    // Mid-stage emission failure: this module's outputs are
                    // never written, any partial stage directory for it is
                    // discarded, and the whole staging tree is removed.
                    deleteRecursively(stageTree);
                    return PublicationOutcome.failure(List.copyOf(result.diagnostics()));
                }
                for (StagedArtifact artifact : result.stagedArtifacts()) {
                    if (!stagedNames.add(artifact.name())) {
                        deleteRecursively(stageTree);
                        return PublicationOutcome.failure(List.of(stagingE6005(
                            invocation, moduleName(result),
                            STAGE_ARTIFACT_NAME_COLLISION)));
                    }
                    writeArtifact(stageTree, artifact);
                    staged.add(artifact);
                }
            }
            if (staged.isEmpty()) {
                deleteRecursively(stageTree);
                return PublicationOutcome.failure(List.of(stagingE6005(
                    invocation, "", STAGE_NO_STAGED_MODULES)));
            }
            StagedArtifactSet stagedSet = new StagedArtifactSet(staged);

            // Phase 3: complete-record mixed-edge validation.
            List<TargetModuleAbi> allAbiRecords = new ArrayList<>(abiEdges);
            for (ModuleEmissionResult result : emissionResults) {
                if (result.emittedAbiManifest() != null) {
                    allAbiRecords.add(result.emittedAbiManifest());
                }
            }
            Optional<CompilerDiagnostic> abiFailure = TargetAbiValidator.validate(
                stagedSet, routePlan, allAbiRecords, interfaceIndex, invocation.semanticProfile());
            if (abiFailure.isPresent()) {
                deleteRecursively(stageTree);
                return PublicationOutcome.failure(List.of(abiFailure.get()));
            }

            // Phase 4: publish (all-or-nothing).
            fault.at(FAULT_STEP_POST_STAGING);

            // Direct-write rejection: the live set must be byte-identical
            // to the staging-start fingerprint (nothing but recovery may
            // have touched it — and recovery runs only after this check).
            Map<String, String> liveNow = fingerprintLive(root);
            if (!liveNow.equals(liveBefore)) {
                deleteRecursively(stageTree);
                return PublicationOutcome.failure(List.of(stagingE6005(
                    invocation, "", STAGE_DIRECT_WRITE_CONFLICT)));
            }

            // Crash recovery at the same publish start, under the lock.
            recoverStaleSiblings(root, stageTree);

            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                // Prior set exists: retire, swap, delete retired; restore
                // on a failed second move.
                String retiredName = root.getFileName() + RETIRED_TREE_MARKER
                    + routePlan.planId() + "-" + nonce;
                Path retired = root.resolveSibling(retiredName);
                atomicMove(root, retired);
                try {
                    fault.at(FAULT_STEP_STAGE_TO_LIVE);
                    atomicMove(stageTree, root);
                } catch (IOException moveFailure) {
                    // Second move failed: restore retired -> live and
                    // report failure. A failed restore leaves the retired
                    // set under its recovery name for the next publish
                    // start's recovery.
                    try {
                        atomicMove(retired, root);
                    } catch (IOException restoreFailure) {
                        // Torn state: live absent + retired present; the
                        // next publish start restores it under the lock.
                    }
                    deleteRecursively(stageTree);
                    return PublicationOutcome.failure(List.of(stagingE6005(
                        invocation, "", STAGE_PUBLISH_MOVE_FAILED)));
                }
                // The swap is complete: the retired set is deleted. A
                // failed delete leaves a retired sibling beside the live
                // set — the post-swap crash window F6's recovery removes
                // at the next publish start; the live set is the new set.
                deleteRecursivelyIfPresent(retired);
                return PublicationOutcome.success(stageTreeName, List.copyOf(staged));
            }

            // Fresh root: the retire step is a no-op and the single
            // S -> live move publishes.
            try {
                fault.at(FAULT_STEP_STAGE_TO_LIVE);
                atomicMove(stageTree, root);
            } catch (IOException moveFailure) {
                deleteRecursively(stageTree);
                return PublicationOutcome.failure(List.of(stagingE6005(
                    invocation, "", STAGE_PUBLISH_MOVE_FAILED)));
            }
            return PublicationOutcome.success(stageTreeName, List.copyOf(staged));
        } catch (IOException ioFailure) {
            if (stageTree != null) {
                deleteRecursivelyIfPresent(stageTree);
            }
            return PublicationOutcome.failure(List.of(stagingE6005(
                invocation, "", STAGE_IO_FAILURE)));
        } finally {
            lock.unlock();
        }
    }

    // =========================================================================
    // Under-lock crash recovery
    // =========================================================================

    /**
     * Crash recovery for one publication root, running <b>only while the
     * caller holds the per-root lock</b> (foundation F6): any
     * {@code .deal-stage-*} or {@code .deal-retired-*} sibling observed
     * under the lock belongs to a dead invocation and is restored or
     * removed deterministically. When {@code live} is absent and a
     * retired set is present (a torn publish after the first move),
     * recovery restores {@code retired → live} — the deterministically
     * chosen retired tree, lexicographically smallest name — <b>and
     * removes every observed {@code .deal-stage-*} sibling at the same
     * publish start</b> (the dead invocation's stage tree from the
     * crashed publish); otherwise it removes the stale trees. The current
     * invocation's own stage tree (a live tree created inside the same
     * critical section, never a dead sibling) is excluded by name.
     *
     * @param publicationRoot  the publication root; non-null
     * @param currentStageTree the current invocation's own staging tree,
     *                         or null (e.g. a direct recovery call)
     * @throws IOException             on a failed restore or removal
     * @throws IllegalStateException   when the per-root lock is not held
     *                                 by the current thread (fail closed:
     *                                 recovery can never remove a live
     *                                 concurrent invocation's trees)
     */
    public static void recoverStaleSiblings(Path publicationRoot, Path currentStageTree)
            throws IOException {
        Objects.requireNonNull(publicationRoot, "publicationRoot must not be null");
        Path root = publicationRoot.toAbsolutePath().normalize();
        ReentrantLock lock = rootLock(root);
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException(
                "crash recovery runs only while holding the per-root lock for '"
                    + root + "' (a live concurrent invocation's trees can never be removed)");
        }
        Path parent = root.getParent();
        if (parent == null) {
            return; // the filesystem root has no sibling namespace
        }
        String rootName = root.getFileName().toString();
        List<Path> stageTrees = siblingTrees(parent, rootName + STAGE_TREE_MARKER);
        List<Path> retiredTrees = siblingTrees(parent, rootName + RETIRED_TREE_MARKER);

        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS) && !retiredTrees.isEmpty()) {
            // Torn publish after the first move: restore retired -> live …
            Path toRestore = retiredTrees.get(0);
            atomicMove(toRestore, root);
            retiredTrees = retiredTrees.subList(1, retiredTrees.size());
        }
        // … and remove every observed stale sibling at the same publish
        // start (the dead invocation's stage tree included).
        for (Path tree : stageTrees) {
            if (currentStageTree != null && tree.equals(currentStageTree)) {
                continue; // the current invocation's own live staging tree
            }
            deleteRecursively(tree);
        }
        for (Path tree : retiredTrees) {
            deleteRecursively(tree);
        }
    }

    private static List<Path> siblingTrees(Path parent, String prefix) throws IOException {
        List<Path> trees = new ArrayList<>();
        try (Stream<Path> entries = Files.list(parent)) {
            for (Path entry : entries.toList()) {
                if (entry.getFileName().toString().startsWith(prefix)
                        && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    trees.add(entry);
                }
            }
        }
        trees.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return trees;
    }

    // =========================================================================
    // Internal phases
    // =========================================================================

    /**
     * Phase 1 (T6 gate): every unit of a successful emission result is
     * validated through the closed {@link SemanticIrValidator} rules with
     * the invocation's comparison facts before any staging. The first
     * failing unit in result order is the rejection — invalid semantic IR
     * is the validator's E6005 (parent D11), never a staging artifact
     * write.
     */
    private static Optional<CompilerDiagnostic> validateUnits(
            List<ModuleEmissionResult> emissionResults,
            ProjectInterfaceIndex interfaceIndex,
            CompilerInvocation invocation) {
        SemanticIrValidator.ComparisonFacts facts = new SemanticIrValidator.ComparisonFacts(
            interfaceIndex.interfaceIndexDigest(),
            invocation.semanticProfile(),
            invocation.capabilityRegistryHash());
        for (ModuleEmissionResult result : emissionResults) {
            if (result.failed() || result.unit() == null) {
                continue; // a failed module publishes nothing, and a retained-side
                // artifact carrier has no common unit to validate (synthetic in
                // this epic; ISSUE-0239 supplies production retained results)
            }
            LoweredModuleUnit unit = result.unit();
            Optional<CompilerDiagnostic> failure = SemanticIrValidator.validate(unit, facts);
            if (failure.isPresent()) {
                return failure;
            }
        }
        return Optional.empty();
    }

    /**
     * Emission coverage against the route plan (deterministic, before
     * staging): every SHARED (incl. shadow) route entry has exactly one
     * emission result whose unit carries that module id, every result's
     * unit is routed SHARED, and every successful result carries its
     * emitted ABI manifest ({@code SHARED} owner, same module). In this
     * epic a routed module's unit is admitted only for SHARED route
     * entries — retained-side emission results stage with ISSUE-0239's
     * mixed-route production.
     */
    private static Optional<CompilerDiagnostic> verifyEmissionCoverage(
            ModuleRoutePlan routePlan,
            List<ModuleEmissionResult> emissionResults,
            CompilerInvocation invocation) {
        Set<String> coveredModules = new LinkedHashSet<>();
        for (ModuleEmissionResult result : emissionResults) {
            if (result.unit() == null) {
                // A retained-side artifact carrier (no common unit, no emitted
                // manifest — synthetic in this epic; ISSUE-0239 supplies
                // production retained results). A failed unattributable result
                // surfaces its own diagnostics mid-stage during staging.
                if (!result.failed() && result.emittedAbiManifest() != null) {
                    return Optional.of(stagingE6005(invocation, "",
                        STAGE_EMITTED_ABI_MISMATCH));
                }
                continue;
            }
            String module = result.unit().moduleId().toString();
            if (!coveredModules.add(module)) {
                return Optional.of(stagingE6005(invocation, module,
                    STAGE_UNIT_ROUTE_MISMATCH));
            }
            ModuleRoute route = routePlan.entries().get(result.unit().moduleId());
            if (route != ModuleRoute.SHARED) {
                return Optional.of(stagingE6005(invocation, module,
                    STAGE_UNIT_ROUTE_MISMATCH));
            }
            if (!result.failed()) {
                // Only a successful emission must carry its emitted ABI
                // manifest; a failed one surfaces its own diagnostics
                // mid-stage.
                TargetModuleAbi manifest = result.emittedAbiManifest();
                if (manifest == null) {
                    return Optional.of(stagingE6005(invocation, module,
                        STAGE_MISSING_EMITTED_ABI));
                }
                if (manifest.artifactOwner() != ArtifactOwner.SHARED
                        || !manifest.moduleId().equals(result.unit().moduleId())) {
                    return Optional.of(stagingE6005(invocation, module,
                        STAGE_EMITTED_ABI_MISMATCH));
                }
            }
        }
        for (Map.Entry<ModuleId, ModuleRoute> entry : routePlan.entries().entrySet()) {
            if (entry.getValue() == ModuleRoute.SHARED
                    && !coveredModules.contains(entry.getKey().toString())) {
                return Optional.of(stagingE6005(invocation, entry.getKey().toString(),
                    STAGE_MISSING_UNIT));
            }
        }
        return Optional.empty();
    }

    // =========================================================================
    // Filesystem helpers
    // =========================================================================

    /**
     * Writes one artifact into the staging tree at its relative path,
     * creating parent directories. The artifact name grammar
     * ({@link StagedArtifact}) guarantees the resolved target stays
     * inside the tree; the normalize/startsWith check is
     * defense-in-depth.
     */
    private static void writeArtifact(Path stageTree, StagedArtifact artifact)
            throws IOException {
        Path target = stageTree.resolve(artifact.name()).normalize();
        if (!target.startsWith(stageTree)) {
            throw new IOException("artifact name escapes the staging tree: \""
                + artifact.name() + "\"");
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(target, artifact.bytes());
    }

    /**
     * Fingerprints the live artifact set: every regular file under the
     * root mapped by relative path to its SHA-256 content digest
     * (through the single canonical digest facility). An absent live set
     * fingerprints as the empty map; a live path that is not a directory
     * fails closed.
     */
    private static Map<String, String> fingerprintLive(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("the live path exists and is not a directory: " + root);
        }
        Map<String, String> digests = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(path ->
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String relative = root.relativize(file).toString()
                    .replace(java.io.File.separatorChar, '/');
                digests.put(relative, CanonicalJson.sha256Hex(Files.readAllBytes(file)));
            }
        }
        return digests;
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void deleteRecursively(Path tree) throws IOException {
        if (!Files.exists(tree, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(tree)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void deleteRecursivelyIfPresent(Path tree) {
        try {
            deleteRecursively(tree);
        } catch (IOException ignored) {
            // A leftover sibling is inert: the next publish start's
            // recovery removes it under the lock.
        }
    }

    // =========================================================================
    // E6005 construction (registry-owned message; capability MODULES)
    // =========================================================================

    /** The attributable module of an emission result ("" for a unit-less retained carrier). */
    private static String moduleName(ModuleEmissionResult result) {
        return result.unit() == null ? "" : result.unit().moduleId().toString();
    }

    private static CompilerDiagnostic stagingE6005(CompilerInvocation invocation,
                                                   String module,
                                                   String rule) {
        return FailureContractRegistry.e6005(new LoweringFailureDetail(
            module, SemanticCapability.MODULES, rule, invocation.semanticProfile(),
            IR_VERSION, "ProjectArtifactStager"));
    }
}
