package deal.toolchain;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One planned native build ({@code llvm-native-backend-integration} D4): the
 * preflighted request, the build scratch location, the generated translation
 * units' text, and the exact pinned argument vector the one seam invocation
 * uses.
 *
 * <p>A plan is a value: it allocates no directory and creates no file. It is
 * the single place the argument vector is composed, so the product and driver
 * shapes differ only in the documented translation-unit membership
 * ({@code main.c} included for the product shape, omitted for the driver
 * shape) and in the generated driver unit's presence. {@link
 * NativeToolchain#build(NativeBuildPlan)} materializes the plan's generated
 * units, runs the vector verbatim, and places the product.</p>
 */
public record NativeBuildPlan(
        NativeBuildRequest request,
        Map<String, String> environment,
        Path scratchDirectory,
        Path registryUnit,
        Path driverUnit,
        String registrySource,
        String driverSource,
        Path entryModule,
        List<Path> runtimeUnits,
        List<Path> hostUnits,
        List<String> command) {

    public NativeBuildPlan {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(scratchDirectory, "scratchDirectory");
        Objects.requireNonNull(registryUnit, "registryUnit");
        Objects.requireNonNull(registrySource, "registrySource");
        Objects.requireNonNull(entryModule, "entryModule");
        Objects.requireNonNull(runtimeUnits, "runtimeUnits");
        Objects.requireNonNull(hostUnits, "hostUnits");
        Objects.requireNonNull(command, "command");
        if (request.driverShape() != (driverSource != null)
                || request.driverShape() != (driverUnit != null)) {
            throw new IllegalArgumentException(
                "the driver shape carries exactly the generated driver unit and"
                    + " its text; the product shape carries neither");
        }
        environment = Map.copyOf(environment);
        runtimeUnits = List.copyOf(runtimeUnits);
        hostUnits = List.copyOf(hostUnits);
        command = List.copyOf(command);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("a build plan carries its"
                + " argument vector");
        }
    }

    /** The durable, caller-owned product path (never derived from the root). */
    public Path productPath() {
        return request.productPath();
    }

    /** The product's fixed pre-rename partial path, beside the product. */
    public Path partialPath() {
        return NativeToolchain.partialPathOf(request.productPath());
    }
}
