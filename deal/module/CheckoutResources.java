package deal.module;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Source-tree runtime files and project-local or checkout stdlib files. */
public final class CheckoutResources {
    private final Path projectDirectory;

    private CheckoutResources(String projectDirectory) {
        this.projectDirectory = Path.of(Objects.requireNonNull(projectDirectory))
            .toAbsolutePath().normalize();
    }

    public static CheckoutResources forManifestDirectory(String directory) {
        return new CheckoutResources(directory);
    }

    /** A project-local stdlib directory takes precedence over the checkout. */
    public Optional<Path> resolveStdlibSurface() {
        Path local = projectDirectory.resolve("std");
        if (Files.isDirectory(local)) return Optional.of(local);
        Path checkout = Path.of("std").toAbsolutePath().normalize();
        return Files.isDirectory(checkout) ? Optional.of(checkout) : Optional.empty();
    }

    /** Per-file overrides preserve checkout implementations for other modules. */
    public Optional<Path> resolveStdlibImplementation(String module, String extension) {
        String relative = "std/" + module + "." + extension;
        Path local = projectDirectory.resolve(relative);
        return Files.isRegularFile(local) ? Optional.of(local) : checkoutFile(relative);
    }

    public Optional<Path> resolveRuntimeSource(String relativeName) {
        return checkoutFile(relativeName);
    }

    private static Optional<Path> checkoutFile(String relativeName) {
        Path file = Path.of(relativeName).toAbsolutePath().normalize();
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }
}
