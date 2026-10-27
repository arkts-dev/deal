package deal.test;

import deal.module.CheckoutResources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Project overrides and source-checkout lookup without installation state. */
public final class CheckoutResourcesTest {
    public static void main(String[] args) throws Exception {
        Path project = Files.createTempDirectory("deal-checkout-resources-");
        try {
            CheckoutResources resources = CheckoutResources.forManifestDirectory(project.toString());
            Path checkoutStd = Path.of("std").toAbsolutePath().normalize();
            assert resources.resolveStdlibSurface().orElseThrow().equals(checkoutStd);
            assert resources.resolveStdlibImplementation("console", "js").orElseThrow()
                .equals(checkoutStd.resolve("console.js"));
            assert resources.resolveRuntimeSource("deal/runtime.lua").orElseThrow()
                .equals(Path.of("deal/runtime.lua").toAbsolutePath().normalize());
            assert resources.resolveRuntimeSource("deal/absent-runtime.lua").isEmpty();
            assert resources.resolveStdlibImplementation("absent-module", "js").isEmpty();

            // A file named std is not a directory surface.
            Path localStd = project.resolve("std");
            Files.writeString(localStd, "not a directory");
            assert resources.resolveStdlibSurface().orElseThrow().equals(checkoutStd);
            Files.delete(localStd);
            Files.createDirectory(localStd);
            Path localConsole = localStd.resolve("console.js");
            Files.writeString(localConsole, "project override");
            assert resources.resolveStdlibSurface().orElseThrow().equals(localStd);
            assert resources.resolveStdlibImplementation("console", "js").orElseThrow()
                .equals(localConsole);
            assert resources.resolveStdlibImplementation("time", "js").orElseThrow()
                .equals(checkoutStd.resolve("time.js"));

            // Runtime lookup never selects a project-local runtime.
            Files.createDirectory(project.resolve("deal"));
            Files.writeString(project.resolve("deal/runtime.lua"), "not the runtime");
            assert resources.resolveRuntimeSource("deal/runtime.lua").orElseThrow()
                .equals(Path.of("deal/runtime.lua").toAbsolutePath().normalize());

            // No global cache hides removal or replacement between compilations.
            Files.delete(localConsole);
            assert resources.resolveStdlibImplementation("console", "js").orElseThrow()
                .equals(checkoutStd.resolve("console.js"));
            Files.writeString(localConsole, "replacement override");
            assert resources.resolveStdlibImplementation("console", "js").orElseThrow()
                .equals(localConsole);
            System.out.println("Checkout resource tests passed");
        } finally {
            try (var files = Files.walk(project)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
}
