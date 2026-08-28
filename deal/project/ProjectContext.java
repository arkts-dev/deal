package deal.project;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The single immutable exact-v1.2 project context published by
 * {@link ProjectLocator} (design source
 * {@code strict-project-context-resolution-identity} D1,
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D10).
 *
 * <p>Exactly one ancestor {@code deal.json} with
 * {@code languageVersion: "1.2"} governs the graph; every field below is
 * validated and converted before publication, and a failed locate
 * publishes no context at all. The record is immutable: the list and map
 * components are defensively copied, and every element is itself
 * immutable.</p>
 *
 * <ul>
 *   <li>{@code manifestPath} — the discovered manifest's fully
 *       symlink-resolved absolute path text (D4 manifest row: regular +
 *       readable + full symlink resolution).</li>
 *   <li>{@code projectRoot} — pinned equal to {@code manifestDirectory}
 *       (v1.2 has no separate root field).</li>
 *   <li>{@code manifestDirectory} — the manifest's directory (its
 *       symlink-resolved parent): the base for module-root conversion,
 *       manifest-relative output, externals declarations, and the
 *       project-local stdlib surface probe.</li>
 *   <li>{@code languageVersion} — always {@code "1.2"} (D2 requires it
 *       exactly).</li>
 *   <li>{@code configuredModuleRoots} — the manifest roots in member
 *       order, each completed by step-4 root conversion
 *       ({@code absoluteNormalizedPath} prefix-resolved; existence never
 *       required; duplicates already E2010). No implicit root is
 *       added.</li>
 *   <li>{@code outputPath} — the effective {@code OutputRef} after the
 *       D3 precedence (validated CLI &gt; manifest &gt;
 *       {@code build/lua}/{@code build/jvm} per effective backend),
 *       converted without requiring existence and before any directory
 *       creation.</li>
 *   <li>{@code backend} — the effective backend after the D3 rule
 *       (validated CLI alias &gt; manifest &gt; {@code "luajit"}).</li>
 *   <li>{@code externals} — each raw import specifier (exactly as
 *       written, in member order) mapped to its validated
 *       {@link ExternalEntry} whose declaration file exists, is regular
 *       and readable, and is fully symlink-resolved; declaration paths
 *       are unique across entries and never equal a pinned stdlib file
 *       (D1 step 4(b)).</li>
 *   <li>{@code stdlibVersion} — the manifest {@code stdlib} value after
 *       D2 validation, always {@code "1.2"}.</li>
 *   <li>{@code stdlibSurfacePath} — the protected-resolved absolute
 *       directory of the pinned stdlib surface:
 *       {@code <manifestDirectory>/std} when it exists as a directory,
 *       else {@code <processCWD>/std} when that exists as a directory,
 *       else absent ({@code null}). Absence is not an error.</li>
 *   <li>{@code stdlibDeclarationFiles} — the canonical (fully
 *       symlink-resolved) absolute path texts of the six spec-listed
 *       stdlib declaration files under the pinned surface, in pinned
 *       module order ({@code console}, {@code string}, {@code table},
 *       {@code json}, {@code math}, {@code time}). A file contributes
 *       its {@code toRealPath} text only when it exists as a regular,
 *       fully resolvable file; missing, non-regular, or unresolvable
 *       files are omitted (they have no canonical path and can never
 *       equal a resolved source), and the list is empty when the surface
 *       is absent. These canonical files are the only
 *       {@code BuiltinModule} sources — the classifier keys its stdlib
 *       predicate on this list (file-keyed on both sides), so a
 *       spec-listed file that is a symlink keeps its pinned
 *       {@code BuiltinModule} classification for its resolved
 *       target.</li>
 *   <li>{@code projectDeploymentIdentity} — the private deployment
 *       identity (canonical symlink-resolved manifest URI + SHA-256 of
 *       the exact manifest bytes).</li>
 * </ul>
 *
 * <p>Deterministic: identical deployment inputs (manifest bytes, resolved
 * files, CLI values) produce an equal context; no timestamp, ordinal, or
 * process state enters any component.</p>
 *
 * @param manifestPath               the symlink-resolved manifest path
 *                                   text
 * @param projectRoot                the project root (pinned equal to the
 *                                   manifest directory)
 * @param manifestDirectory          the manifest's directory path text
 * @param languageVersion            always {@code "1.2"}
 * @param configuredModuleRoots      the completed configured roots in
 *                                   member order (no implicit root)
 * @param outputPath                 the effective classified output
 * @param backend                    the effective backend
 * @param externals                  raw import specifier → validated
 *                                   {@link ExternalEntry}, in member order
 * @param stdlibVersion              the validated stdlib version
 *                                   ({@code "1.2"})
 * @param stdlibSurfacePath          the pinned stdlib surface directory
 *                                   path text, or {@code null} when absent
 * @param stdlibDeclarationFiles     the canonical resolved paths of the
 *                                   six spec-listed stdlib declaration
 *                                   files under the pinned surface, in
 *                                   pinned module order; empty when the
 *                                   surface is absent (never null)
 * @param projectDeploymentIdentity  the private deployment identity
 */
public record ProjectContext(
    String manifestPath,
    String projectRoot,
    String manifestDirectory,
    String languageVersion,
    List<ConfiguredModuleRoot> configuredModuleRoots,
    OutputConfigResolver.OutputRef outputPath,
    String backend,
    Map<String, ExternalEntry> externals,
    String stdlibVersion,
    String stdlibSurfacePath,
    List<String> stdlibDeclarationFiles,
    ProjectDeploymentIdentity projectDeploymentIdentity
) {

    public ProjectContext {
        Objects.requireNonNull(manifestPath, "manifestPath");
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(manifestDirectory, "manifestDirectory");
        Objects.requireNonNull(languageVersion, "languageVersion");
        Objects.requireNonNull(configuredModuleRoots, "configuredModuleRoots");
        Objects.requireNonNull(outputPath, "outputPath");
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(externals, "externals");
        Objects.requireNonNull(stdlibVersion, "stdlibVersion");
        Objects.requireNonNull(stdlibDeclarationFiles, "stdlibDeclarationFiles");
        Objects.requireNonNull(projectDeploymentIdentity, "projectDeploymentIdentity");
        // stdlibSurfacePath may be null: absence is a plain value.

        configuredModuleRoots = List.copyOf(configuredModuleRoots);
        externals = Collections.unmodifiableMap(new LinkedHashMap<>(externals));
        stdlibDeclarationFiles = List.copyOf(stdlibDeclarationFiles);
    }
}
