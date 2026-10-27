package deal.toolchain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One native build request ({@code llvm-native-backend-integration} D3/D4).
 *
 * <p>The request is the whole input of {@link NativeToolchain}:</p>
 *
 * <ul>
 *   <li>{@code publicationRoot} — the live publication root that holds the
 *       published {@code <entry module path>.ll} module and the closed D6
 *       runtime deployment set. The seam only reads it.</li>
 *   <li>{@code productPath} — the durable, caller-owned product path. The seam
 *       never derives it from {@code publicationRoot} and never writes
 *       {@code publicationRoot}.</li>
 *   <li>{@code optLevel} — {@code 0} or {@code 2} (the pinned flag is
 *       {@code -O<level>}).</li>
 *   <li>{@code hostUnits} — the closed list of host implementation
 *       translation units, in the caller's boundary order.</li>
 *   <li>{@code driverSource} — nullable generated C text. {@code null}
 *       selects the product shape (the runtime set includes
 *       {@code deal/native/main.c} and there is no driver unit); non-null
 *       selects the driver shape (the runtime set omits exactly
 *       {@code main.c}, the text is written to the scratch
 *       {@code driver.c} and it defines the process entry).</li>
 * </ul>
 *
 * <p>{@code publicationRoot}, {@code productPath} and every host unit are
 * normalized to absolute paths so two equal requests carry equal paths and the
 * pinned argument vector is deterministic.</p>
 */
public record NativeBuildRequest(
        Path publicationRoot,
        Path productPath,
        int optLevel,
        List<Path> hostUnits,
        String driverSource) {

    /** The two accepted optimization levels ({@code -O0} and {@code -O2}). */
    public static final List<Integer> OPT_LEVELS = List.of(0, 2);

    public NativeBuildRequest {
        Objects.requireNonNull(publicationRoot, "publicationRoot");
        Objects.requireNonNull(productPath, "productPath");
        Objects.requireNonNull(hostUnits, "hostUnits");
        publicationRoot = publicationRoot.toAbsolutePath().normalize();
        productPath = productPath.toAbsolutePath().normalize();
        if (!OPT_LEVELS.contains(optLevel)) {
            throw new IllegalArgumentException(
                "the native build accepts the optimization levels 0 and 2 only;"
                    + " got " + optLevel);
        }
        List<Path> normalizedUnits = new java.util.ArrayList<>(hostUnits.size());
        for (Path unit : hostUnits) {
            Objects.requireNonNull(unit, "a host unit must not be null");
            normalizedUnits.add(unit.toAbsolutePath().normalize());
        }
        hostUnits = List.copyOf(normalizedUnits);
        if (driverSource != null && driverSource.isBlank()) {
            // Null selects the product shape; a driver request carries the
            // translation unit's text.
            throw new IllegalArgumentException(
                "a driver request carries the driver translation unit's source"
                    + " text; use null for the product shape");
        }
    }

    /** The product shape ({@code deal/native/main.c}, no driver unit). */
    public boolean productShape() {
        return driverSource == null;
    }

    /** The driver shape (runtime set minus {@code main.c}, generated driver). */
    public boolean driverShape() {
        return driverSource != null;
    }
}
