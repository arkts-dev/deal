package deal.test;

import java.nio.file.Path;
import java.util.List;

/** Required Python/Hypothesis dependency fails closed, never skips a lane. */
public final class BoundaryStatefulTest {
    public static void main(String[] args) throws Exception {
        var result = BoundedProcess.runCaptured(List.of("python3", "test/boundary_stateful.py"),
            Path.of("").toAbsolutePath());
        if (result.exitCode() != 0 || result.stdoutTruncated() || result.stderrTruncated())
            throw new AssertionError(result);
        System.out.print(result.stdout());
        if (!result.stderr().isEmpty()) throw new AssertionError(result.stderr());
    }
}
