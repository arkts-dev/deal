package deal.codegen.jvm;

public final class JvmAsyncExportInvocationException
        extends RuntimeException {

    private final String stdout;
    private final String stderr;

    /**
     * Constructs the hard failure with the captured process output.
     *
     */
    public JvmAsyncExportInvocationException(String message,
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
    public JvmAsyncExportInvocationException(String message,
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
