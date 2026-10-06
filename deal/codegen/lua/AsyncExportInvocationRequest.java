package deal.codegen.lua;

import java.nio.file.Path;
import java.util.Objects;

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
