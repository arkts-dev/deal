package deal.test;

import java.io.IOException;
import java.util.List;

/**
 * Fail-closed required-tool preflight for the suites that drive a real
 * toolchain.
 *
 * <p>Every JS production-artifact suite requires the real Node binary, exactly
 * as the LuaJIT runtime records require LuaJIT: a tool's absence is the named
 * {@code TOOL_MISSING} infrastructure outcome, so the suite fails before it
 * records one case. There is no warning, skip or downgrade path — a required
 * tool's absence never removes a supported case from execution, and a run that
 * only skipped the affected drives can never be reported as a pass.
 * {@code tools/tool-preflight.sh} applies the same contract to the shell
 * runner, which preflights the whole required set before any build work
 * starts.</p>
 */
public final class ToolchainPreflight {

    /** The infrastructure outcome token for an absent required tool. */
    public static final String TOOL_MISSING = "TOOL_MISSING";

    private ToolchainPreflight() {
    }

    /** The verbatim failure record for one absent required tool. */
    public static String missingRecord(String tool, String subject) {
        return TOOL_MISSING + ": " + tool + " is not available; required by "
            + subject + ", so the run fails instead of skipping a supported"
            + " case";
    }

    /** True when the tool answers its version probe with exit 0. */
    public static boolean available(String tool, String versionArgument) {
        try {
            return BoundedProcess.runCaptured(List.of(tool, versionArgument))
                .exitCode() == 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException absentExecutable) {
            return false;
        }
    }

    /**
     * Requires the tool for this suite: an absent tool prints the exact
     * {@code TOOL_MISSING} record to stderr and fails the suite, so a passing
     * run always executed the drives this tool owns. A present but broken tool
     * still fails through its own assertion or the bounded-process deadline —
     * never as a skip.
     */
    public static void require(String tool, String versionArgument,
            String subject) {
        if (available(tool, versionArgument)) {
            return;
        }
        System.err.println(missingRecord(tool, subject));
        System.exit(1);
    }
}
