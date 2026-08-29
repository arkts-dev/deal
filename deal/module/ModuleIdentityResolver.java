package deal.module;

import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.descriptors.DescriptorAst;
import deal.descriptors.DescriptorParseResult;
import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalClassIdentityIndex;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.project.ConfiguredModuleRoot;
import deal.project.ExternalEntry;
import deal.project.ProjectContext;
import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The pure classifier of public module identity plus the pinned descriptor
 * representability predicates (ISSUE-0266 T5, design source
 * {@code strict-project-context-resolution-identity} D6,
 * {@code deal-v1.2-int32-and-bytes-architecture} D6).
 *
 * <p>The classifier below performs <b>no source resolution</b>: it is a
 * pure function of a validated
 * {@link ProjectContext} and canonical (symlink-resolved) {@code file:}
 * source URIs, and it performs no filesystem access — every input is
 * pre-resolved by the upstream components (ProjectLocator for roots,
 * externals declaration paths, and the stdlib surface; SourceModuleResolver
 * for source URIs). The classifier is invoked once per resolved source
 * when a {@code SourceModuleLocation} is published, and its result is the
 * provenance of the location's {@code projectIdentity} plus the
 * module-level classification; {@code CanonicalClassIdentity} assembly,
 * eligibility gates, the {@code CanonicalClassIdentityIndex}, and the
 * intrinsic {@code Error} synthesis consume the resolved-source stream
 * through {@link ModuleIdentityAssembly} — the identity-assembly half
 * of this component (epic sequencing item 7).</p>
 *
 * <p>The identity carriers this classifier produces are the neutral
 * JDK-only shapes pinned by {@code deal.identity}
 * ({@link CanonicalModuleIdentity}, {@link ProjectModuleIdentity}) —
 * this layer is their producer and performs the classification; the
 * carrier package itself pins shapes only.</p>
 *
 * <h2>Classifier rules (D6, fixed order)</h2>
 * <ol>
 *   <li>A source whose canonical URI equals the canonical URI of one of
 *       the six spec-listed stdlib declaration files under the pinned
 *       surface — the fully symlink-resolved pinned files published by
 *       ProjectLocator as
 *       {@code ProjectContext.stdlibDeclarationFiles()}
 *       ({@code std/console}, {@code std/string}, {@code std/table},
 *       {@code std/json}, {@code std/math}, {@code std/time} — the
 *       authoritative filter of
 *       {@link StdlibModuleResolver#SPEC_STDLIB_MODULES}) is
 *       {@link CanonicalModuleIdentity.BuiltinModule}, regardless of how
 *       the source was resolved (a symlinked spec-listed file classifies
 *       by its resolved target, exactly the canonical URI every import
 *       spelling resolves to). If the surface is absent no source
 *       carries {@code BuiltinModule}; a same-named {@code .d.deal} in any
 *       other directory is not builtin.</li>
 *   <li>A source whose canonical URI equals an externals entry's
 *       {@code NormalizedDeclarationPath} is
 *       {@link CanonicalModuleIdentity.ExternalModule} carrying that
 *       entry's raw import specifier, regardless of how the source was
 *       resolved.</li>
 *   <li>A {@code .deal} source (never a {@code .d.deal} declaration file)
 *       contained by exactly one configured root with strictly maximal
 *       containment is
 *       {@link CanonicalModuleIdentity.ProjectModule} with a derived
 *       {@link ProjectModuleIdentity}.</li>
 *   <li>Any other source — a relative out-of-root {@code .deal}, a
 *       relative {@code .d.deal} matching no externals entry and not one
 *       of the six pinned stdlib files, a {@code .d.deal} physically
 *       inside the pinned stdlib directory that is not among the six
 *       spec-listed files, a rooted non-externals {@code .d.deal}, or any
 *       other unclassified source — receives no public module
 *       identity.</li>
 * </ol>
 *
 * <p>Predicates (1) and (2) are mutually exclusive for every valid
 * {@code ProjectContext} (ProjectLocator step 4(b) rejects any externals
 * declaration that resolves to a pinned stdlib file), so no runtime
 * precedence between them exists or is needed. The classifier implements
 * the fixed order and, defensively, detects the both-match state —
 * unreachable for valid inputs — and reports it through the result as
 * {@link Issue#STDLIB_EXTERNAL_OVERLAP} instead of silently publishing
 * either form. Likewise the defensive equal-root tie (two distinct
 * contained roots attaining equal maximal containment — unreachable for a
 * valid manifest because duplicate normalized roots are already E2010 at
 * locate) is detected and reported as {@link Issue#EQUAL_ROOT_TIE} rather
 * than silently picking one root; the identity-assembly half
 * ({@link ModuleIdentityAssembly}) maps these outcomes to the pinned
 * E2010-when-required behavior.</p>
 *
 * <h2>Containment (D6)</h2>
 *
 * <p>Containment is computed on symlink-resolved paths on both sides: a
 * source is contained by a root iff its canonical URI path equals the
 * root's {@code normalizedRootPath} or starts with it followed by a path
 * separator (component-wise). The most-specific root is the contained
 * root with the longest {@code normalizedRootPath}. A source whose file is
 * a symlink pointing outside its lexical root has a canonical URI outside
 * the root prefix and is not contained; non-existent roots contain no
 * sources in filesystem reality. All comparisons are lexical over
 * already-resolved absolute paths — no filesystem access occurs here.</p>
 *
 * <h2>Representability (D6, pure over decoded text components)</h2>
 *
 * <p>Every slash-separated component of a configured root text, of the
 * relative module path, and of an externals raw import specifier must be
 * non-empty; not {@code .} or {@code ..}; free of U+0000, C0 controls,
 * DEL, and Unicode whitespace (the full pinned White_Space property —
 * {@code 0009-000D, 0020, 0085, 00A0, 1680, 2000-200A, 2028, 2029,
 * 202F, 205F, 3000} — checked explicitly, since Java's
 * {@code Character.isWhitespace} excludes U+0085 NEXT LINE); free of
 * {@code @ [ ] ? ( ) ,}; and free of contiguous {@code -}{@code >}.
 * {@code %}, non-reserved {@code $}, and a Linux backslash remain
 * byte-identical and valid. {@code $external} and {@code $builtin} are
 * reserved only as <b>exact first components</b>: a root whose first
 * component is exactly one of them is unrepresentable as a project
 * identity. Class names are identifier-shaped by the grammar,
 * {@code [a-zA-Z_$][a-zA-Z0-9_$]*}.</p>
 * <b>Per-compilation identity-index surface (ISSUE-0317):</b> beyond the
 * pure classifier above, this class also publishes the per-compilation
 * identity artifacts the JS descriptor lane consumes (design source
 * {@code strict-project-context-resolution-identity} D6,
 * {@code canonical-type-system-and-runtime-descriptors} D3/D5).
 *
 * <p>The classification itself — which resolved source carries which
 * {@link CanonicalModuleIdentity} (builtin, externals-listed, configured
 * root) — is computed by the compilation seams that hold the resolution
 * inputs (the orchestrator's module map, the configured roots, and the
 * externals manifest entries); this class owns the compiled identity
 * artifacts those seams publish into:</p>
 *
 * <ul>
 *   <li>the per-compilation {@link IdentityIndex} — a concrete
 *       {@link CanonicalClassIdentityIndex} whose
 *       {@code descriptorTextFor(identity)} returns the pinned
 *       descriptor-text projection byte-for-byte:
 *       {@code @&lt;configuredRootText&gt;/&lt;relativeModuleComponents&gt;/&lt;ClassName&gt;}
 *       for project modules,
 *       {@code @$external/&lt;rawImportSpecifier&gt;/&lt;ClassName&gt;} for
 *       externals-listed declarations, and {@code @$builtin/Error} for the
 *       intrinsic builtin {@code Error} class; and</li>
 *   <li>the module-path classification function the per-compilation
 *       {@link CanonicalRuntimeTypeDescriptor} consumes to resolve a
 *       checked {@code Type.Class#modulePath()} into its module
 *       identity.</li>
 * </ul>
 *
 * <p>The index never recomputes or reverse-parses a root boundary from
 * text: projections are derived from the supplied identity carriers
 * only.  Every produced text is validated through the canonical strict
 * parser ({@link CanonicalRuntimeTypeDescriptor#parse(String)}) and must
 * be a byte-identical {@link DescriptorAst.ClassAtom}; an
 * unrepresentable identity (a component outside the pinned alphabet, a
 * non-identifier-shaped class name, or a builtin class other than the
 * pinned {@code Error} projection) is the pinned internal invariant
 * violation — {@link IllegalStateException}, never invented text and
 * never a silent fallback (the eligibility gate that turns these cases
 * into public E2010 diagnostics at declaration sites is the
 * module-identity layer's, {@code strict-project-context-resolution-identity}
 * D6).</p>
 *
 * <p>The reverse accessor {@code identityForDescriptorText(text)}
 * returns the identity registered for a byte-identical produced text.
 * Registration is production-driven: every text produced by
 * {@code descriptorTextFor} registers both directions, so
 * {@code identityForDescriptorText(descriptorTextFor(id))} always
 * round-trips; a text no identity has been produced for is an absent
 * lookup (pinned invariant violation).</p>

 */
public final class ModuleIdentityResolver {

    private ModuleIdentityResolver() {
    }

    /**
     * The six spec-listed stdlib module names, in pinned order (the
     * authoritative filter of
     * {@link StdlibModuleResolver#SPEC_STDLIB_MODULES}, bare names). The
     * six pinned declaration files under a resolved stdlib surface are
     * exactly {@code <surface>/<name>.d.deal} for these names.
     */
    public static final List<String> SPEC_STDLIB_MODULE_NAMES = List.of(
        "console", "string", "table", "json", "math", "time");

    // =========================================================================
    // The classification result
    // =========================================================================

    /**
     * The defensive issue markers of a classification result. {@link
     * Issue#NONE} is the normal case; the other two are unreachable for
     * every valid {@code ProjectContext} and are reported through the
     * result instead of being silently resolved.
     */
    public enum Issue {
        /** No defensive issue: the classification is a normal outcome. */
        NONE,
        /**
         * Two distinct contained roots attain equal maximal containment
         * (for a valid manifest: duplicate normalized roots, already E2010
         * at locate). No project identity is published.
         */
        EQUAL_ROOT_TIE,
        /**
         * A source satisfies both the stdlib predicate (1) and the
         * externals predicate (2) — the internal invariant-violation state
         * that ProjectLocator step 4(b)'s stdlib-overlap rejection makes
         * unreachable for every valid context. No module identity is
         * published.
         */
        STDLIB_EXTERNAL_OVERLAP
    }

    /**
     * The classifier result for one canonical resolved-source URI: the
     * single canonical module identity (null = no public module
     * identity), the {@link ProjectModuleIdentity} provenance (non-null
     * exactly when the identity is
     * {@link CanonicalModuleIdentity.ProjectModule}), and the defensive
     * issue marker.
     *
     * @param moduleIdentity  the canonical module identity, or null when
     *                        the source has no public module identity
     * @param projectIdentity the project-module provenance; equal to the
     *                        {@code ProjectModule}'s identity when the
     *                        identity is {@code ProjectModule}, null
     *                        otherwise
     * @param issue           {@link Issue#NONE}, or one of the two
     *                        defensive markers (never null)
     */
    public record ModuleClassification(CanonicalModuleIdentity moduleIdentity,
                                       ProjectModuleIdentity projectIdentity,
                                       Issue issue) {
        public ModuleClassification {
            Objects.requireNonNull(issue, "issue");
            if (moduleIdentity instanceof CanonicalModuleIdentity.ProjectModule project) {
                Objects.requireNonNull(projectIdentity, "projectIdentity");
                if (!project.projectIdentity().equals(projectIdentity)) {
                    throw new IllegalArgumentException(
                        "projectIdentity must equal the ProjectModule's identity");
                }
            } else if (projectIdentity != null) {
                throw new IllegalArgumentException(
                    "projectIdentity is present only for the ProjectModule"
                        + " classification");
            }
        }
    }

    // =========================================================================
    // The classifier
    // =========================================================================

    /**
     * Classifies one canonical resolved-source URI under a validated
     * {@link ProjectContext} (D6 fixed order, rules (1)–(4) above).
     *
     * <p>Purity: the result is a pure function of the two inputs — no
     * filesystem access, no shared state, and identical inputs always
     * produce equal results. The {@code canonicalSourceUri} must be the
     * product of protected resolution (a normalized, absolute,
     * symlink-resolved {@code file:} URI); a null or non-{@code file:}
     * URI is not a pinned input and classifies as {@code none} with
     * {@link Issue#NONE} (defensive totality, never an exception).</p>
     *
     * @param context            the validated immutable project context
     * @param canonicalSourceUri the canonical symlink-resolved
     *                           {@code file:} URI text of one resolved
     *                           source
     * @return the single classification (identity or none, provenance,
     *         issue); never null
     */
    public static ModuleClassification classify(ProjectContext context,
                                                String canonicalSourceUri) {
        Objects.requireNonNull(context, "context");

        Path sourcePath = sourcePathOf(canonicalSourceUri);

        // Fixed order: (1) stdlib, (2) externals. The predicates are
        // mutually exclusive for every valid ProjectContext, so no
        // runtime precedence exists; the both-match state is reported as
        // an internal invariant violation instead of silently picking one.
        boolean builtin = matchesStdlibFile(context, sourcePath);
        String externalKey = externalKeyOf(context, sourcePath);
        if (builtin && externalKey != null) {
            return new ModuleClassification(null, null,
                Issue.STDLIB_EXTERNAL_OVERLAP);
        }
        if (builtin) {
            return new ModuleClassification(
                CanonicalModuleIdentity.BuiltinModule.INSTANCE, null,
                Issue.NONE);
        }
        if (externalKey != null) {
            return new ModuleClassification(
                new CanonicalModuleIdentity.ExternalModule(externalKey), null,
                Issue.NONE);
        }

        // (3) Project form: a .deal source (not .d.deal) contained by
        // exactly one configured root with strictly maximal containment.
        if (sourcePath == null || !isPlainDealSource(sourcePath)) {
            return new ModuleClassification(null, null, Issue.NONE);
        }
        RootSelection selection = selectMostSpecificRoot(context, sourcePath);
        if (selection.tie()) {
            return new ModuleClassification(null, null, Issue.EQUAL_ROOT_TIE);
        }
        if (selection.root() == null) {
            return new ModuleClassification(null, null, Issue.NONE);
        }
        ConfiguredModuleRoot root = selection.root();
        ProjectModuleIdentity projectIdentity = new ProjectModuleIdentity(
            root.configuredText(),
            root.absoluteNormalizedPath(),
            relativeModuleComponents(sourcePath,
                pathOf(root.absoluteNormalizedPath())));
        return new ModuleClassification(
            new CanonicalModuleIdentity.ProjectModule(projectIdentity),
            projectIdentity, Issue.NONE);
    }

    // =========================================================================
    // Classifier internals (all purely lexical — no filesystem access)
    // =========================================================================

    /**
     * Derives the lexical absolute path of a canonical {@code file:} URI
     * without any filesystem access, or null when the input is not a
     * hierarchical {@code file:} URI or cannot be materialized as a path
     * (defensive totality for inputs outside the pinned domain).
     */
    private static Path sourcePathOf(String canonicalSourceUri) {
        if (canonicalSourceUri == null) {
            return null; // totality: a null URI is no identity
        }
        URI uri;
        try {
            uri = URI.create(canonicalSourceUri);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (uri.getScheme() == null || !uri.getScheme().equals("file")) {
            return null;
        }
        try {
            return Path.of(uri).normalize();
        } catch (IllegalArgumentException | FileSystemNotFoundException e) {
            return null;
        }
    }

    /**
     * Materializes one protected path text lexically (no filesystem
     * access); null for a text the host cannot materialize.
     */
    private static Path pathOf(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Path.of(text).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * The stdlib predicate (D6 (1)): the source path equals the canonical
     * (fully symlink-resolved) path of one of the six spec-listed stdlib
     * declaration files under the pinned surface. ProjectLocator
     * publishes those canonical paths in
     * {@code ProjectContext.stdlibDeclarationFiles()} (pinned module
     * order; only existing regular fully-resolvable files are listed),
     * so the comparison is canonical-file-keyed on both sides: a
     * spec-listed file that is a symlink keeps its pinned
     * {@code BuiltinModule} classification for its resolved target —
     * exactly the canonical URI every import spelling of that file
     * resolves to. Absent surface (empty list) → false. The classifier
     * itself performs no filesystem access.
     */
    private static boolean matchesStdlibFile(ProjectContext context, Path sourcePath) {
        if (sourcePath == null) {
            return false;
        }
        List<String> pinnedFiles = context.stdlibDeclarationFiles();
        if (pinnedFiles == null || pinnedFiles.isEmpty()) {
            return false;
        }
        for (String pinnedFile : pinnedFiles) {
            Path pinnedPath = pathOf(pinnedFile);
            if (pinnedPath != null && sourcePath.equals(pinnedPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The externals predicate (D6 (2)): the raw import specifier of the
     * first externals entry whose fully symlink-resolved declaration path
     * equals the source path, or null. Declaration paths are unique
     * across entries for every valid context (ProjectLocator step 4
     * rejects duplicates), so the entry is unique; for a fabricated
     * context with duplicates the first in member order wins
     * deterministically.
     */
    private static String externalKeyOf(ProjectContext context, Path sourcePath) {
        if (sourcePath == null) {
            return null;
        }
        for (ExternalEntry entry : context.externals().values()) {
            Path declarationPath =
                pathOf(entry.declarationPath().absoluteNormalizedPath());
            if (declarationPath != null && sourcePath.equals(declarationPath)) {
                return entry.rawImportSpecifier();
            }
        }
        return null;
    }

    /**
     * True iff the source file name denotes a {@code .deal} source that
     * is not a {@code .d.deal} declaration file (D6 (3): the project form
     * applies to {@code .deal} sources only).
     */
    private static boolean isPlainDealSource(Path sourcePath) {
        String fileName = sourcePath.getFileName() == null
            ? ""
            : sourcePath.getFileName().toString();
        return fileName.endsWith(".deal") && !fileName.endsWith(".d.deal");
    }

    /**
     * The selected most-specific root: {@code tie} when two distinct
     * contained roots attain equal maximal containment (unreachable for a
     * valid manifest), otherwise the contained root with the longest
     * normalized path, or null when no configured root contains the
     * source.
     */
    private record RootSelection(ConfiguredModuleRoot root, boolean tie) {
    }

    /**
     * Most-specific containment selection (D6): containment is
     * component-wise path prefixing on symlink-resolved paths on both
     * sides — a source is contained by a root iff its path equals the
     * root's normalized path or starts with it followed by a path
     * separator ({@link Path#startsWith(Path)} covers both). The most
     * specific root is the contained root with the longest normalized
     * root path (most name components).
     */
    private static RootSelection selectMostSpecificRoot(ProjectContext context,
                                                        Path sourcePath) {
        ConfiguredModuleRoot best = null;
        int bestLength = -1;
        boolean tie = false;
        for (ConfiguredModuleRoot root : context.configuredModuleRoots()) {
            Path rootPath = pathOf(root.absoluteNormalizedPath());
            if (rootPath == null || !rootPath.isAbsolute()
                    || !sourcePath.startsWith(rootPath)) {
                continue;
            }
            int length = rootPath.getNameCount();
            if (length > bestLength) {
                best = root;
                bestLength = length;
                tie = false;
            } else if (length == bestLength) {
                // Two contained roots attain the same maximal containment:
                // they must share the same resolved prefix — duplicate
                // normalized roots, already E2010 at locate for a valid
                // manifest. Detected and reported, never silently picked.
                tie = true;
            }
        }
        return new RootSelection(best, tie);
    }

    /**
     * The path components from the root prefix to the defining file's
     * directory (D6): the file name is omitted. A file directly in the
     * root yields an empty list; an empty relative module path projects
     * as {@code @<rootText>/<ClassName>}.
     */
    private static List<String> relativeModuleComponents(Path sourcePath,
                                                         Path rootPath) {
        Path relative = rootPath.relativize(sourcePath);
        List<String> components = new ArrayList<>();
        int count = relative.getNameCount();
        for (int i = 0; i < count - 1; i++) {
            components.add(relative.getName(i).toString());
        }
        return components;
    }

    // =========================================================================
    // Descriptor representability predicates (D6, pure over decoded text)
    // =========================================================================

    /**
     * The pinned per-component representability predicate: a descriptor
     * component is representable iff it is non-empty; not {@code .} or
     * {@code ..}; free of U+0000, C0 controls, DEL, and Unicode
     * whitespace; free of the descriptor metacharacters
     * {@code @ [ ] ? ( ) ,}; and free of contiguous {@code -}{@code >}.
     * {@code %}, non-reserved {@code $}, and a Linux backslash remain
     * byte-identical and valid. A null component is not representable.
     *
     * <p>Unicode whitespace is the full pinned White_Space property
     * ({@code 0009-000D, 0020, 0085, 00A0, 1680, 2000-200A, 2028, 2029,
     * 202F, 205F, 3000}) checked explicitly — never
     * {@code Character.isWhitespace}, which excludes U+0085 NEXT LINE
     * (a White_Space Cc control) since JDK 5.</p>
     *
     * <p>Pure over the decoded text: no filesystem access, no shared
     * state.</p>
     *
     * @param component one slash-separated decoded text component
     * @return true iff the component is representable in a descriptor
     */
    public static boolean isRepresentableDescriptorComponent(String component) {
        if (component == null || component.isEmpty()) {
            return false;
        }
        if (component.equals(".") || component.equals("..")) {
            return false;
        }
        if (component.indexOf("->") >= 0) {
            return false;
        }
        for (int i = 0; i < component.length(); ) {
            int codePoint = component.codePointAt(i);
            if (codePoint == 0x0000
                    || (codePoint >= 0x0001 && codePoint <= 0x001F)
                    || codePoint == 0x007F) {
                return false; // U+0000, C0 controls, DEL
            }
            if (isUnicodeWhiteSpace(codePoint)) {
                return false; // the full pinned White_Space property
            }
            if (codePoint == '@' || codePoint == '[' || codePoint == ']'
                    || codePoint == '?' || codePoint == '(' || codePoint == ')'
                    || codePoint == ',') {
                return false; // descriptor metacharacters
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    /**
     * The full pinned Unicode White_Space property (UAX #44 PropList:
     * {@code White_Space=Yes}): {@code 0009-000D, 0020, 0085, 00A0, 1680,
     * 2000-200A, 2028, 2029, 202F, 205F, 3000}. Checked explicitly
     * because Java's {@code Character.isWhitespace} excludes U+0085 NEXT
     * LINE since JDK 5 (a documented deviation from the Unicode
     * property), which would let a NEL control/whitespace scalar pass
     * the pinned representability criterion.
     */
    private static boolean isUnicodeWhiteSpace(int codePoint) {
        return (codePoint >= 0x0009 && codePoint <= 0x000D)
            || codePoint == 0x0020
            || codePoint == 0x0085
            || codePoint == 0x00A0
            || codePoint == 0x1680
            || (codePoint >= 0x2000 && codePoint <= 0x200A)
            || codePoint == 0x2028
            || codePoint == 0x2029
            || codePoint == 0x202F
            || codePoint == 0x205F
            || codePoint == 0x3000;
    }

    /**
     * Splits one decoded text on {@code /} preserving empty components
     * and validating each; null when any component is unrepresentable.
     * An empty text splits into one empty component and therefore fails.
     */
    private static List<String> splitValidated(String text) {
        List<String> components = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '/') {
                String component = text.substring(start, i);
                if (!isRepresentableDescriptorComponent(component)) {
                    return null;
                }
                components.add(component);
                start = i + 1;
            }
        }
        return components;
    }

    /**
     * The configured-root-text representability predicate: every
     * slash-separated component must satisfy
     * {@link #isRepresentableDescriptorComponent(String)}, and the
     * {@code $external}/{@code $builtin} reservation applies exactly to
     * the first component — a root whose first component is exactly one
     * of them is unrepresentable as a project identity. A null text is
     * not representable.
     *
     * @param configuredRootText the decoded manifest spelling of a
     *                           configured root
     * @return true iff the root text is representable in the project
     *         descriptor form
     */
    public static boolean isRepresentableConfiguredRootText(
            String configuredRootText) {
        if (configuredRootText == null) {
            return false;
        }
        List<String> components = splitValidated(configuredRootText);
        if (components == null) {
            return false;
        }
        String first = components.get(0);
        return !("$external".equals(first) || "$builtin".equals(first));
    }

    /**
     * The relative-module-path representability predicate: every
     * component (each a path component derived from the filesystem) must
     * satisfy {@link #isRepresentableDescriptorComponent(String)}. The
     * {@code $external}/{@code $builtin} reservation does not apply here
     * — it is pinned to exact first components of a root only, and the
     * relative path never contributes the descriptor's first component.
     * A null list is not representable; an empty list (a file directly in
     * its root) is representable.
     *
     * @param relativeModuleComponents the derived directory components
     *                                 from the root to the defining file
     * @return true iff the components are representable in the project
     *         descriptor form
     */
    public static boolean isRepresentableRelativeModuleComponents(
            List<String> relativeModuleComponents) {
        if (relativeModuleComponents == null) {
            return false;
        }
        for (String component : relativeModuleComponents) {
            if (!isRepresentableDescriptorComponent(component)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The externals raw-import-specifier representability predicate:
     * every slash-separated component must satisfy
     * {@link #isRepresentableDescriptorComponent(String)}. As with the
     * relative module path, the {@code $external}/{@code $builtin}
     * reservation does not apply — the specifier never contributes the
     * descriptor's first component. A null specifier is not
     * representable.
     *
     * @param rawImportSpecifier the externals map key exactly as written
     * @return true iff the specifier is representable in the
     *         {@code @$external} descriptor form
     */
    public static boolean isRepresentableExternalSpecifier(
            String rawImportSpecifier) {
        if (rawImportSpecifier == null) {
            return false;
        }
        return splitValidated(rawImportSpecifier) != null;
    }

    /**
     * The combined project-form representability predicate: the
     * identity's configured root text and its relative module components
     * must both be representable. A null identity is not representable.
     *
     * @param projectModuleIdentity the derived project module identity
     * @return true iff the project descriptor form is representable
     */
    public static boolean isRepresentableProjectModuleIdentity(
            ProjectModuleIdentity projectModuleIdentity) {
        return projectModuleIdentity != null
            && isRepresentableConfiguredRootText(
                projectModuleIdentity.configuredRootText())
            && isRepresentableRelativeModuleComponents(
                projectModuleIdentity.relativeModuleComponents());
    }

    /**
     * The class-name shape predicate: a class name is identifier-shaped
     * by the grammar iff it matches {@code [a-zA-Z_$][a-zA-Z0-9_$]*}
     * (the lexer's identifier grammar, {@code deal/lexer/Lexer.java}).
     * A null or empty name is not identifier-shaped.
     *
     * @param className a declared class name
     * @return true iff the name is identifier-shaped by the grammar
     */
    public static boolean isIdentifierShapedClassName(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        char first = className.charAt(0);
        if (!isIdentifierStart(first)) {
            return false;
        }
        for (int i = 1; i < className.length(); i++) {
            char c = className.charAt(i);
            if (!isIdentifierStart(c) && !isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIdentifierStart(char c) {
        return (c >= 'a' && c <= 'z')
            || (c >= 'A' && c <= 'Z')
            || c == '_'
            || c == '$';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * Builds the per-compilation identity index over the compilation's
     * module-path classification (dotted module path &rarr; canonical
     * module identity).  The empty module path entry classifies the
     * intrinsic builtin {@code Error} module (the checker represents the
     * builtin Error class as {@code Type.Class("Error", "")}).
     *
     * @param modulePathIdentities the dotted module path &rarr; module
     *                             identity classification; non-null
     * @return the per-compilation identity index
     */
    public static IdentityIndex buildIndex(
            Map<String, CanonicalModuleIdentity> modulePathIdentities) {
        return new IdentityIndex(modulePathIdentities);
    }

    /**
     * The concrete per-compilation {@link CanonicalClassIdentityIndex}.
     *
     * <p>Immutable over the classification map; deterministic;
     * single-threaded per compilation (the pinned concurrency model of
     * the compilation seams that consume it).</p>
     */
    public static final class IdentityIndex implements CanonicalClassIdentityIndex {

        /** The dotted module path &rarr; module-identity classification. */
        private final Map<String, CanonicalModuleIdentity> byModulePath;

        /** Produced identity &rarr; descriptor text (both directions registered per production). */
        private final Map<CanonicalClassIdentity, String> textByIdentity =
            new HashMap<>();

        /** Produced descriptor text &rarr; identity (byte-for-byte). */
        private final Map<String, CanonicalClassIdentity> identityByText =
            new HashMap<>();

        private IdentityIndex(Map<String, CanonicalModuleIdentity> modulePathIdentities) {
            Objects.requireNonNull(modulePathIdentities,
                "modulePathIdentities must not be null");
            this.byModulePath = Map.copyOf(modulePathIdentities);
        }

        /**
         * The module-path classification function the per-compilation
         * {@link CanonicalRuntimeTypeDescriptor} consumes:
         * {@code Type.Class#modulePath()} &rarr; {@link CanonicalModuleIdentity},
         * {@code null} for a module path the classification does not
         * carry (no public identity).
         *
         * @return the classification function; never null
         */
        public Function<String, CanonicalModuleIdentity> moduleIdentityLookup() {
            return byModulePath::get;
        }

        @Override
        public String descriptorTextFor(CanonicalClassIdentity identity) {
            Objects.requireNonNull(identity, "identity must not be null");
            String cached = textByIdentity.get(identity);
            if (cached != null) {
                return cached;
            }
            String text = projection(identity);
            // Every produced text must be a canonical class atom
            // byte-for-byte (design source
            // canonical-type-system-and-runtime-descriptors D3): the
            // strict parser is the single validation boundary, so no
            // unrepresentable identity can ever be published.
            DescriptorParseResult parsed = CanonicalRuntimeTypeDescriptor.parse(text);
            if (!(parsed instanceof DescriptorAst.ClassAtom atom)
                    || !atom.fullDescriptorText().equals(text)) {
                throw new IllegalStateException(
                    "identity projection '" + text + "' for class '"
                        + identity.className() + "' is not a canonical class "
                        + "descriptor atom: "
                        + (parsed instanceof deal.descriptors.DescriptorSyntaxError err
                            ? err.kind() + "@" + err.scalarOffset() + ": " + err.message()
                            : "unexpected parse result " + parsed)
                        + " (internal invariant violation — the identity is "
                        + "unrepresentable)");
            }
            textByIdentity.put(identity, text);
            identityByText.put(text, identity);
            return text;
        }

        @Override
        public CanonicalClassIdentity identityForDescriptorText(
                String descriptorText) {
            Objects.requireNonNull(descriptorText,
                "descriptorText must not be null");
            CanonicalClassIdentity identity = identityByText.get(descriptorText);
            if (identity == null) {
                throw new IllegalStateException(
                    "descriptor text '" + descriptorText
                        + "' is not registered in the compilation's identity "
                        + "index (absent-lookup contract: internal invariant "
                        + "violation)");
            }
            return identity;
        }

        /**
         * The pinned descriptor-text projection of one class identity,
         * derived from the identity carriers only — never from text.
         */
        private String projection(CanonicalClassIdentity identity) {
            return switch (identity.moduleIdentity()) {
                case CanonicalModuleIdentity.BuiltinModule ignored -> {
                    if (!"Error".equals(identity.className())) {
                        throw new IllegalStateException(
                            "no pinned descriptor-text projection for builtin "
                                + "class '" + identity.className()
                                + "' (only the intrinsic Error class has the "
                                + "pinned @$builtin/Error projection; a "
                                + "required other builtin identity is "
                                + "unrepresentable)");
                    }
                    yield "@$builtin/Error";
                }
                case CanonicalModuleIdentity.ExternalModule ext -> {
                    if (!"$external".equals(firstComponent(ext.rawImportSpecifier()))) {
                        yield "@$external/" + ext.rawImportSpecifier()
                            + "/" + identity.className();
                    }
                    // A specifier whose first component is the reserved
                    // $external text can never be projected (D6
                    // representability: $external/$builtin are reserved as
                    // exact first components).
                    throw new IllegalStateException(
                        "externals raw import specifier '" + ext.rawImportSpecifier()
                            + "' is unrepresentable (reserved first component)");
                }
                case CanonicalModuleIdentity.ProjectModule proj -> {
                    ProjectModuleIdentity projectIdentity = proj.projectIdentity();
                    String rootText = projectIdentity.configuredRootText();
                    // A project root whose first component is one of the
                    // reserved namespaces can never be projected (D6).
                    String first = firstComponent(rootText);
                    if ("$external".equals(first) || "$builtin".equals(first)) {
                        throw new IllegalStateException(
                            "configured root text '" + rootText
                                + "' is unrepresentable (reserved first component)");
                    }
                    StringBuilder sb = new StringBuilder("@").append(rootText);
                    for (String component
                            : projectIdentity.relativeModuleComponents()) {
                        sb.append('/').append(component);
                    }
                    yield sb.append('/').append(identity.className()).toString();
                }
            };
        }

        /** The first slash-separated component of a text, or the text itself. */
        private static String firstComponent(String text) {
            int slash = text.indexOf('/');
            return slash < 0 ? text : text.substring(0, slash);
        }

        /**
         * The dotted module path &rarr; module identity classification of
         * this index, exposed for diagnostics; never mutated.
         *
         * @return the classification map (unmodifiable)
         */
        public Map<String, CanonicalModuleIdentity> modulePathIdentities() {
            return byModulePath;
        }

        /**
         * Convenience: the registered descriptor-text projection for a
         * class declared in the given dotted module path.
         *
         * @param modulePath the declaring module's dotted path
         * @param className  the class name
         * @return the byte-identical canonical descriptor text
         * @throws IllegalStateException for an unclassified module path or
         *         an unrepresentable identity
         */
        public String descriptorTextFor(String modulePath, String className) {
            CanonicalModuleIdentity moduleIdentity = byModulePath.get(modulePath);
            if (moduleIdentity == null) {
                throw new IllegalStateException(
                    "module path '" + modulePath
                        + "' has no canonical public module identity in this "
                        + "compilation (internal invariant violation)");
            }
            return descriptorTextFor(
                new CanonicalClassIdentity(moduleIdentity, className));
        }
    }

    /**
     * Derives the relative-module directory components of a dotted
     * module path whose final component is the source file stem (the
     * orchestrator's module-path shape): all components except the
     * final one are the defining file's directory components below its
     * configured root ({@code strict-project-context-resolution-identity}
     * D6 — the source suffix and file stem are omitted from identity
     * components).
     *
     * @param dottedModulePath the dotted module path; non-null
     * @return the directory components (possibly empty)
     */
    public static List<String> directoryComponents(String dottedModulePath) {
        Objects.requireNonNull(dottedModulePath, "dottedModulePath must not be null");
        String[] parts = dottedModulePath.split("\\.", -1);
        List<String> directories = new java.util.ArrayList<>(
            Math.max(0, parts.length - 1));
        for (int i = 0; i < parts.length - 1; i++) {
            directories.add(parts[i]);
        }
        return directories;
    }
}
