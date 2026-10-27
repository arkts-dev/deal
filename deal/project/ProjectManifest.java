package deal.project;

import deal.source.SourceScalarRange;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The strictly parsed v1.5 project manifest (docs/spec-v1.5.md).
 *
 * <p>Published by {@link StrictManifestParser} after the strict walk and
 * {@link ProjectConfigValidator}'s post-walk validations. Absent fields
 * carry their pinned parser-level defaults after validation:
 * {@code backend} is {@code "luajit"}, {@code stdlib} is {@code "1.5"},
 * {@code moduleRoots} is the empty list (no implicit root), and
 * {@code externals} is the empty map. {@code output} and
 * {@code dependencies} remain absent (null) — the effective output path
 * (including its per-backend default directory form) is the
 * OutputConfigResolver's duty and requires the manifest directory the
 * parser deliberately does not receive.
 *
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
