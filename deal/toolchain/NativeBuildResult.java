package deal.toolchain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of one successful native build
 * ({@code llvm-native-backend-integration} D4/{@code NativeBuild}): the durable,
 * caller-owned product path, the exact argument vector that produced it, and
 * the generated translation units' text the build compiled.
 *
 * <p>The product is never inside the publication root or the build scratch: it
 * is the caller's path, replaced exactly once by the successful link's
 * rename. A failed build produces no result at all — it raises the one
 * {@link NativeBuildException} of its condition and leaves the earlier product
 * untouched.</p>
 */
public record NativeBuildResult(
        Path productPath,
        List<String> command,
        String registrySource,
        String driverSource) {

    public NativeBuildResult {
        Objects.requireNonNull(productPath, "productPath");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(registrySource, "registrySource");
        command = List.copyOf(command);
        if (command.isEmpty()) {
            throw new IllegalArgumentException(
                "a native build result carries the argument vector it ran");
        }
    }

    /** True when this result is the driver shape's product. */
    public boolean driverShape() {
        return driverSource != null;
    }
}
