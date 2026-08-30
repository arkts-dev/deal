package deal.codegen.lua;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The parent's canonical three-field request for one async-export
 * invocation (luajit-async-export-invoker D2, "Async-export invocation
 * (Java API)" contract).
 *
 * <ul>
 *   <li>{@code entryArtifact} — the compiled entry chunk inside an
 *       orchestrator-shaped deployment root (the module is written as
 *       {@code <modulePath with '.'→'/'>.lua} under the output root,
 *       {@code deal/module/CompilationOrchestrator.java:1629});</li>
 *   <li>{@code exportName} — the export key passed to the runtime entry
 *       verbatim;</li>
 *   <li>{@code returnDescriptor} — byte-exact canonical descriptor text,
 *       passed through verbatim with no Java-side validation (D9): the
 *       landed runtime half remains the single authority for selection,
 *       completion, and protocol validation.</li>
 * </ul>
 *
 * <p>The request carries no output root and no executable name: the
 * invoker derives the deployment root from the entry artifact by the
 * {@code deal/runtime.lua} marker walk (D5) and pins the {@code luajit}
 * executable itself (D3).</p>
 */
public record AsyncExportInvocationRequest(
        Path entryArtifact, String exportName, String returnDescriptor) {

    /**
     * Canonical constructor with fail-closed non-null validation. The
     * descriptor text itself is deliberately unvalidated (byte-exact
     * pass-through, D9).
     */
    public AsyncExportInvocationRequest {
        Objects.requireNonNull(entryArtifact, "entryArtifact must not be null");
        Objects.requireNonNull(exportName, "exportName must not be null");
        Objects.requireNonNull(returnDescriptor,
            "returnDescriptor must not be null");
    }
}
