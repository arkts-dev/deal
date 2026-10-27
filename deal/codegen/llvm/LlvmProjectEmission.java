package deal.codegen.llvm;

import java.util.Objects;

/**
 * The one artifact of the native backend ({@code llvm-native-backend-
 * integration} D2): the textual LLVM module's publication-relative path and
 * its exact bytes as text.
 *
 * <p>One project emission produces exactly this record; the caller
 * ({@code deal.module.ProductionProjectEmission}) stages it beside the
 * runtime deployment set ({@code NativeRuntime.FILES}) inside the one
 * publication transaction. No per-module artifact and no source-map sidecar
 * exists for this backend (C8).</p>
 */
public record LlvmProjectEmission(String artifactRelativePath, String source) {

    public LlvmProjectEmission {
        Objects.requireNonNull(artifactRelativePath,
            "artifactRelativePath must not be null");
        Objects.requireNonNull(source, "source must not be null");
        if (artifactRelativePath.isBlank() || !artifactRelativePath.endsWith(".ll")) {
            throw new IllegalArgumentException(
                "the LLVM project artifact is one '<entry module path>.ll' file,"
                    + " got '" + artifactRelativePath + "'");
        }
    }
}
