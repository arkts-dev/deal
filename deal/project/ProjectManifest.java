package deal.project;

import deal.source.SourceScalarRange;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The strictly parsed exact-v1.2 project manifest (design source
 * {@code strict-project-context-resolution-identity} D2,
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D5).
 *
 * <p>Published by {@link StrictManifestParser} after the strict walk and
 * {@link ProjectConfigValidator}'s post-walk validations. Absent fields
 * carry their pinned parser-level defaults after validation:
 * {@code backend} is {@code "luajit"}, {@code stdlib} is {@code "1.2"},
 * {@code moduleRoots} is the empty list (no implicit root), and
 * {@code externals} is the empty map. {@code output} and
 * {@code dependencies} remain absent (null) — the effective output path
 * (including its per-backend default directory form) is the
 * OutputConfigResolver's duty and requires the manifest directory the
 * parser deliberately does not receive.
 *
 * @param languageVersion the validated version, always {@code "1.2"}
 * @param moduleRoots     the strictly decoded root texts with their value
 *                        ranges, in member order; empty when absent
 * @param output          the strictly decoded output text plus its value
 *                        range, or null when the member is absent
 * @param backend         the validated backend {@code "luajit"} or
 *                        {@code "jvm"} after validation (absence defaults
 *                        to {@code "luajit"})
 * @param stdlib          the validated stdlib version {@code "1.2"}
 * @param dependencies    the spec-reserved, structurally strict, opaque
 *                        {@code dependencies} object, or null when absent
 * @param externals       each raw import specifier mapped to its
 *                        parser-side {@link ExternalEntrySpec}, in member
 *                        order; empty when absent
 * @param ranges          each member present in the document mapped to
 *                        its value range, for post-walk re-anchoring by
 *                        later locate steps
 */
public record ProjectManifest(
    String languageVersion,
    List<ManifestString> moduleRoots,
    ManifestString output,
    String backend,
    String stdlib,
    StrictJsonValue.ObjectVal dependencies,
    Map<String, ExternalEntrySpec> externals,
    Map<String, SourceScalarRange> ranges
) {

    public ProjectManifest {
        moduleRoots = List.copyOf(moduleRoots);
        externals = Collections.unmodifiableMap(new LinkedHashMap<>(externals));
        ranges = Collections.unmodifiableMap(new LinkedHashMap<>(ranges));
    }
}
