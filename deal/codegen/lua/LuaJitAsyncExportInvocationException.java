package deal.codegen.lua;

/**
 * The hard-failure signal of the LuaJIT async-export host invoker
 * (luajit-async-export-invoker D2): every infrastructure, containment,
 * and value-representation failure of
 * {@link LuaJitAsyncExportInvoker#invoke(AsyncExportInvocationRequest)}
 * throws this unchecked exception, carrying the captured child process
 * output for diagnosis.
 *
 * <p>This is the parent's distinct process-failure signal — never an
 * expected DEAL code and never a {@link LuaJitAsyncExportInvoker.Result}
 * variant. DEAL runtime-error codes appear exclusively inside
 * {@link LuaJitAsyncExportInvoker.Result.DealError}.</p>
 */
public final class LuaJitAsyncExportInvocationException
        extends RuntimeException {

    private final String stdout;
    private final String stderr;

    /**
     * Constructs the hard failure with the captured process output.
     *
     */
    public LuaJitAsyncExportInvocationException(String message,
                                                String stdout,
                                                String stderr) {
        super(message);
        this.stdout = stdout;
        this.stderr = stderr;
    }

    /**
     * Constructs the hard failure with a cause and the captured process
     * output.
     *
     */
    public LuaJitAsyncExportInvocationException(String message,
                                                String stdout,
                                                String stderr,
                                                Throwable cause) {
        super(message, cause);
        this.stdout = stdout;
        this.stderr = stderr;
    }

    /** Captured child stdout, or {@code null} when the process never started. */
    public String stdout() {
        return stdout;
    }

    /** Captured child stderr, or {@code null} when the process never started. */
    public String stderr() {
        return stderr;
    }

    /**
     * Both captured streams concatenated, for diagnosis and log
     * attachment. Returns {@code null} only when neither stream was
     * captured.
     */
    public String capturedOutput() {
        if (stdout == null && stderr == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (stdout != null) {
            sb.append("--- stdout ---\n").append(stdout);
        }
        if (stderr != null) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("--- stderr ---\n").append(stderr);
        }
        return sb.toString();
    }
}
