package deal.semantic.ir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The single numeric semantic-id allocator of {@code deal.semantic-ir/1}
 * (parent D10; schema S7): one allocator per project issues the numeric
 * identities of the schema's numeric {@link SemanticId} kinds —
 * {@link BlockId}, {@link FunctionId}, {@link BindingId}, {@link ValueId},
 * {@link AnchorId}, {@link OpId} (module-tagged, numeric component),
 * {@link ClassFactoryId}, {@link FunctionAllocationIdentity}, and the
 * numeric token ids inside {@link AsyncTokenId} — in the pinned order:
 *
 * <pre>{@code
 * dependency order -> source order -> semantic role -> synthetic ordinal
 * }</pre>
 *
 * <p>The allocator <em>enforces</em> the order instead of only
 * documenting it: it is constructed over the module list in dependency
 * order and every allocation declares its coordinates (module, source
 * ordinal, {@link Role}, synthetic ordinal); a request whose coordinate
 * key is not strictly after the previous key raises
 * {@link IllegalStateException} — a producer defect, never silently
 * reordered. Ids are handed out by one counter starting at 0, so they are
 * globally unique within the project and the same allocation sequence in
 * a fresh allocator yields identical ids (deterministic across JVM runs);
 * per-project uniqueness is additionally validator-checked (T6), never
 * construction-enforced.</p>
 *
 * <p>{@link Role} is the closed semantic-role order of this foundation:
 * {@code BLOCK, FUNCTION, BINDING, VALUE, ANCHOR, OP, CLASS_FACTORY,
 * FUNCTION_ALLOCATION, TOKEN}. Within one source ordinal, allocations
 * proceed role by role in that order, and the synthetic ordinal
 * disambiguates every further allocation at the same coordinates.</p>
 *
 * <p>{@link ClassFactoryId} values are allocatable through exactly this
 * contract so the index-build-time {@code constructionEntry} derivation
 * (foundation F2 — dependency order, exported classes in declaration
 * (source) order, role {@code CLASS_FACTORY}, synthetic ordinal 0) and
 * the later lowering epics share one ordering: a shared owner registers
 * its {@code CLASS_FACTORY} op under the pre-allocated id when it
 * lowers, and the deterministic route-independent id stays stable across
 * routing decisions.</p>
 */
public final class SemanticIdAllocator {

    /**
     * The closed semantic-role order of {@code deal.semantic-ir/1}
     * (parent D10; schema S7): allocation proceeds role by role in this
     * declaration order within one source ordinal.
     */
    public enum Role {
        BLOCK,
        FUNCTION,
        BINDING,
        VALUE,
        ANCHOR,
        OP,
        CLASS_FACTORY,
        FUNCTION_ALLOCATION,
        TOKEN
    }

    /** The pinned dependency-ordered module list. */
    private final List<ModuleId> modules;

    /** Module path to dependency-order index. */
    private final Map<String, Integer> moduleIndex;

    /** The next numeric id to hand out (single global counter). */
    private long next = 0;

    private int lastModuleIndex = -1;
    private long lastSourceOrdinal = -1;
    private int lastRoleOrdinal = -1;
    private long lastSyntheticOrdinal = -1;

    private SemanticIdAllocator(List<ModuleId> dependencyOrderedModules) {
        Objects.requireNonNull(dependencyOrderedModules, "dependencyOrderedModules must not be null");
        List<ModuleId> copy = new ArrayList<>(dependencyOrderedModules);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("the allocator requires at least one module");
        }
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < copy.size(); i++) {
            ModuleId module = Objects.requireNonNull(copy.get(i), "modules must not contain null");
            if (index.putIfAbsent(module.path(), i) != null) {
                throw new IllegalArgumentException(
                    "duplicate module \"" + module.path() + "\" in the dependency order");
            }
        }
        this.modules = List.copyOf(copy);
        this.moduleIndex = Map.copyOf(index);
    }

    /**
     * Creates one allocator over the given module list in dependency
     * order.
     *
     * @param dependencyOrderedModules the module identities in dependency
     *                                 order; non-null, non-empty, distinct
     * @return a fresh allocator handing out ids from 0
     */
    public static SemanticIdAllocator over(List<ModuleId> dependencyOrderedModules) {
        return new SemanticIdAllocator(dependencyOrderedModules);
    }

    /**
     * Creates one allocator over the project's complete implementation
     * closure in the project's dependency order (the project record
     * preserves module insertion order).
     *
     * @param project the executable lowered project; non-null
     * @return a fresh allocator handing out ids from 0
     */
    public static SemanticIdAllocator over(ExecutableLoweredProject project) {
        Objects.requireNonNull(project, "project must not be null");
        return new SemanticIdAllocator(new ArrayList<>(project.modules().keySet()));
    }

    /** The pinned dependency-ordered module list of this allocator. */
    public List<ModuleId> modules() {
        return modules;
    }

    /**
     * Allocates one numeric id at the given coordinates and advances the
     * counter. The coordinate key must be strictly after every previous
     * allocation's key in the pinned lexicographic order (dependency
     * order, source order, semantic role, synthetic ordinal).
     *
     * @param module           the allocating module (must be in the pinned
     *                         dependency order); non-null
     * @param sourceOrdinal    the source-order position within the module;
     *                         non-negative
     * @param role             the semantic role; non-null
     * @param syntheticOrdinal the synthetic ordinal within the same
     *                         module/source-position/role coordinates;
     *                         non-negative
     * @return the fresh globally unique numeric id
     * @throws IllegalArgumentException on an unknown module or negative
     *         ordinals
     * @throws IllegalStateException    when the request is out of the
     *         pinned allocation order (a producer defect)
     */
    public long next(ModuleId module, long sourceOrdinal, Role role, long syntheticOrdinal) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(role, "role must not be null");
        if (sourceOrdinal < 0) {
            throw new IllegalArgumentException("sourceOrdinal must be >= 0, got " + sourceOrdinal);
        }
        if (syntheticOrdinal < 0) {
            throw new IllegalArgumentException("syntheticOrdinal must be >= 0, got " + syntheticOrdinal);
        }
        Integer index = moduleIndex.get(module.path());
        if (index == null) {
            throw new IllegalArgumentException(
                "module \"" + module.path() + "\" is not part of this allocator's dependency order "
                    + modules);
        }
        int roleOrdinal = role.ordinal();
        boolean ordered = index > lastModuleIndex
            || (index == lastModuleIndex && sourceOrdinal > lastSourceOrdinal)
            || (index == lastModuleIndex && sourceOrdinal == lastSourceOrdinal
                && roleOrdinal > lastRoleOrdinal)
            || (index == lastModuleIndex && sourceOrdinal == lastSourceOrdinal
                && roleOrdinal == lastRoleOrdinal && syntheticOrdinal > lastSyntheticOrdinal);
        if (!ordered) {
            throw new IllegalStateException(
                "allocation out of the pinned order (dependency order -> source order -> "
                    + "semantic role -> synthetic ordinal): requested (" + module.path() + ", "
                    + sourceOrdinal + ", " + role + ", " + syntheticOrdinal
                    + ") after (" + (lastModuleIndex < 0 ? "-" : modules.get(lastModuleIndex).path())
                    + ", " + lastSourceOrdinal + ", "
                    + (lastRoleOrdinal < 0 ? "-" : Role.values()[lastRoleOrdinal])
                    + ", " + lastSyntheticOrdinal + ")");
        }
        lastModuleIndex = index;
        lastSourceOrdinal = sourceOrdinal;
        lastRoleOrdinal = roleOrdinal;
        lastSyntheticOrdinal = syntheticOrdinal;
        return next++;
    }

    /** Allocates a {@link BlockId} (role {@code BLOCK}). */
    public BlockId nextBlockId(ModuleId module, long sourceOrdinal, long syntheticOrdinal) {
        return new BlockId(next(module, sourceOrdinal, Role.BLOCK, syntheticOrdinal));
    }

    /** Allocates a {@link FunctionId} (role {@code FUNCTION}). */
    public FunctionId nextFunctionId(ModuleId module, long sourceOrdinal, long syntheticOrdinal) {
        return new FunctionId(next(module, sourceOrdinal, Role.FUNCTION, syntheticOrdinal));
    }

    /** Allocates a {@link BindingId} (role {@code BINDING}). */
    public BindingId nextBindingId(ModuleId module, long sourceOrdinal, long syntheticOrdinal) {
        return new BindingId(next(module, sourceOrdinal, Role.BINDING, syntheticOrdinal));
    }

    /** Allocates a {@link ValueId} (role {@code VALUE}). */
    public ValueId nextValueId(ModuleId module, long sourceOrdinal, long syntheticOrdinal) {
        return new ValueId(next(module, sourceOrdinal, Role.VALUE, syntheticOrdinal));
    }

    /** Allocates an {@link AnchorId} (role {@code ANCHOR}). */
    public AnchorId nextAnchorId(ModuleId module, long sourceOrdinal, long syntheticOrdinal) {
        return new AnchorId(next(module, sourceOrdinal, Role.ANCHOR, syntheticOrdinal));
    }

    /**
     * Allocates an {@link OpId} (role {@code OP}) tagged with the
     * allocating module so cross-unit references are unambiguous.
     */
    public OpId nextOpId(ModuleId module, long sourceOrdinal, long syntheticOrdinal) {
        return new OpId(module, next(module, sourceOrdinal, Role.OP, syntheticOrdinal));
    }

    /**
     * Allocates a {@link ClassFactoryId} (role {@code CLASS_FACTORY}) —
     * the index-build-time {@code constructionEntry} derivation consumes
     * this exact method with dependency-ordered modules, exported classes
     * in declaration (source) order, and synthetic ordinal 0 (foundation
     * F2).
     */
    public ClassFactoryId nextClassFactoryId(ModuleId module, long sourceOrdinal,
                                             long syntheticOrdinal) {
        return new ClassFactoryId(next(module, sourceOrdinal, Role.CLASS_FACTORY, syntheticOrdinal));
    }

    /** Allocates a {@link FunctionAllocationIdentity} (role {@code FUNCTION_ALLOCATION}). */
    public FunctionAllocationIdentity nextAllocationIdentity(ModuleId module, long sourceOrdinal,
                                                             long syntheticOrdinal) {
        return new FunctionAllocationIdentity(
            next(module, sourceOrdinal, Role.FUNCTION_ALLOCATION, syntheticOrdinal));
    }

    /**
     * Allocates the numeric token id of an {@link AsyncTokenId} (role
     * {@code TOKEN}); the caller wraps it into {@code CANONICAL} or
     * {@code ALIAS}.
     */
    public long nextTokenId(ModuleId module, long sourceOrdinal, long syntheticOrdinal) {
        return next(module, sourceOrdinal, Role.TOKEN, syntheticOrdinal);
    }
}
