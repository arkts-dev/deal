package deal.toolchain;

import deal.distribution.DistributionHome;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The closed ordered C11 runtime deployment set
 * ({@code llvm-native-backend-integration} D6).
 *
 * <p>This constant is the sole runtime file-list authority: {@link #FILES} is
 * the complete set the LLVM arm stages, at exactly these relative paths under
 * the publication root, and it is the set {@code tools/build-release.sh}
 * requires and ships. Every name resolves through
 * {@link DistributionHome#resolveRuntimeSource(String)} — no project-local
 * location is pinned for runtime files, so a packaged distribution resolves
 * classpath-first and a checkout resolves through the documented CWD tier.</p>
 *
 * <p>The set is closed and ordered: the header first, then one
 * {@code .c}/{@code .h} pair per service group in dependency order
 * ({@code platform} ... {@code entry}), and the product build's process-entry
 * translation unit last. {@code main.c} belongs to no service group and is
 * omitted from a driver build. Generated artifact sources — the scratch
 * {@code hosts_registry.c} translation unit and generated driver translation
 * units — are never part of this set, and neither is this Java file (the closed
 * constant, not the directory listing, is the authority).</p>
 *
 * <p>The six spec-listed stdlib modules are realized by the runtime's closed
 * {@code std} operation surface, not by per-module artifacts; the spec-listed
 * {@code std/} declaration modules remain the compile-time type authority.</p>
 *
 * <p><b>Package spelling.</b> The native contract pages name this authority
 * {@code deal.native.NativeRuntime}, but {@code native} is a reserved Java
 * keyword, so no Java package can carry it. The toolchain seam therefore lives
 * in {@code deal.toolchain} ({@code NativeRuntime} here and
 * {@code NativeToolchain} in the build seam); the class names, the closed
 * ordered constant and every consumer relationship are unchanged.</p>
 */
public final class NativeRuntime {

    /** The pinned relative path of the product build's process entry. */
    public static final String PROCESS_ENTRY_FILE = "deal/native/main.c";

    /**
     * The closed ordered runtime set (D6): dead last is the product build's
     * process-entry translation unit.
     */
    public static final List<String> FILES = List.of(
        "deal/native/deal_rt.h",
        "deal/native/platform.c", "deal/native/platform.h",
        "deal/native/value.c", "deal/native/value.h",
        "deal/native/memory.c", "deal/native/memory.h",
        "deal/native/text.c", "deal/native/text.h",
        "deal/native/container.c", "deal/native/container.h",
        "deal/native/descriptor.c", "deal/native/descriptor.h",
        "deal/native/error.c", "deal/native/error.h",
        "deal/native/json.c", "deal/native/json.h",
        "deal/native/std.c", "deal/native/std.h",
        "deal/native/host.c", "deal/native/host.h",
        "deal/native/async.c", "deal/native/async.h",
        "deal/native/entry.c", "deal/native/entry.h",
        PROCESS_ENTRY_FILE
    );

    /**
     * The published translation units in their pinned link order: every
     * {@code .c} file of {@link #FILES}, including the process entry.
     */
    public static final List<String> TRANSLATION_UNITS = translationUnits();

    /** The pinned runtime directory (the deployment root's relative prefix). */
    public static final String RUNTIME_DIRECTORY = "deal/native/";

    private NativeRuntime() {
        // Static authority; no instances.
    }

    /** One resolved deployment file: its pinned relative name and source. */
    public record DeploymentFile(String relativeName,
                                 DistributionHome.ResolvedSource source) {
        public DeploymentFile {
            Objects.requireNonNull(relativeName, "relativeName");
            Objects.requireNonNull(source, "source");
        }

        /** The deployment bytes, read from the resolved source. */
        public byte[] bytes() throws IOException {
            try (InputStream stream = source.open()) {
                return stream.readAllBytes();
            }
        }
    }

    private static List<String> translationUnits() {
        List<String> units = new ArrayList<>();
        for (String file : FILES) {
            if (file.endsWith(".c")) {
                units.add(file);
            }
        }
        return List.copyOf(units);
    }

    /** True when {@code relativeName} belongs to the closed deployment set. */
    public static boolean isDeploymentFile(String relativeName) {
        return FILES.contains(relativeName);
    }

    /** True when {@code relativeName} is the product build's process entry. */
    public static boolean isProcessEntry(String relativeName) {
        return PROCESS_ENTRY_FILE.equals(relativeName);
    }

    /**
     * Resolves every deployment file through {@code home}, in the pinned order.
     *
     * @throws IllegalStateException with the pinned
     *     {@code Runtime library not found: <path>} diagnostic when a file
     *     resolves at no tier; the caller's publication path reports it as the
     *     E6000 runtime-not-found failure and publishes nothing.
     */
    public static List<DeploymentFile> resolve(DistributionHome home) {
        Objects.requireNonNull(home, "home");
        List<DeploymentFile> resolved = new ArrayList<>(FILES.size());
        for (String relativeName : FILES) {
            Optional<DistributionHome.ResolvedSource> source =
                home.resolveRuntimeSource(relativeName);
            if (source.isEmpty()) {
                throw new IllegalStateException(
                    "Runtime library not found: " + relativeName);
            }
            resolved.add(new DeploymentFile(relativeName, source.get()));
        }
        return List.copyOf(resolved);
    }
}
