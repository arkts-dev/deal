package deal.codegen.jvm;

import deal.codegen.AsyncExportEnvelope;
import deal.codegen.lua.AsyncExportInvocationRequest;
import deal.codegen.lua.LuaJitAsyncExportInvoker;
import deal.codegen.lua.LuaJitAsyncExportInvoker.EnvelopeJson;
import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.descriptors.DescriptorAst;
import deal.descriptors.DescriptorParseResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class JvmAsyncExportInvoker {

    /**
     * The reserved argv marker of the generated host mode: byte-equal to
     * the backend's emitted dispatch marker
     * ({@link JvmBackend#ASYNC_EXPORT_HOST_ARG}).
     */
    public static final String HOST_ARG = JvmBackend.ASYNC_EXPORT_HOST_ARG;

    /**
     * The binary-name suffix of the reserved generated host-export
     * launcher ({@link JvmBackend#ASYNC_EXPORT_HOST_CLASS_SUFFIX}).
     */
    public static final String HOST_CLASS_SUFFIX =
        JvmBackend.ASYNC_EXPORT_HOST_CLASS_SUFFIX;

    /**
     * The exact stdout marker prefix of the shared envelope protocol:
     * byte-equal to {@code LuaJitAsyncExportInvoker.RESULT_PREFIX}, so
     * one host runner consumes both backends' envelopes.
     */
    public static final String RESULT_PREFIX =
        LuaJitAsyncExportInvoker.RESULT_PREFIX;

    /** Temp-directory prefix for the per-invocation capture files. */
    static final String TEMP_DIR_PREFIX = "deal-jvm-async-export-invoker-";

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * The sealed invocation result: exactly three variants and no others,
     * so mistaking a host or infrastructure failure for a DEAL code is
     * impossible at the type level.
     */
    public sealed interface Result
            permits Result.Value, Result.DealError, Result.HostFailure {

        /**
         * The matcher-validated completion.
         *
         */
        record Value(String returnDescriptor, String valueJson)
                implements Result {
            public Value {
                Objects.requireNonNull(returnDescriptor,
                    "returnDescriptor must not be null");
                Objects.requireNonNull(valueJson, "valueJson must not be null");
            }
        }

        /**
         * A DEAL Error raised by module initialization, {@code main()},
         * the operation, or the completion matcher — propagated with code
         * and message unchanged. The JVM emitted errors carry no source
         * location, so the location fields stay {@code null}.
         */
        record DealError(String code, String message, String file,
                         Integer line, Integer column) implements Result {
            public DealError {
                Objects.requireNonNull(code, "code must not be null");
                Objects.requireNonNull(message, "message must not be null");
            }
        }

        /**
         * The parent's {@code HostInvocationFailure}: a missing, sync,
         * parameterized, duplicate, descriptor-mismatched, non-wrapper,
         * or malformed-protocol export — with the runtime half's pinned
         * reason string verbatim. Never a DEAL code.
         */
        record HostFailure(String reason) implements Result {
            public HostFailure {
                Objects.requireNonNull(reason, "reason must not be null");
            }
        }
    }

    /**
     * Invokes one exact async export under one fresh JVM process. See the
     * class contract for the full outcome mapping.
     *
     */
    public Result invoke(AsyncExportInvocationRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        Path entryArtifact = request.entryArtifact().toAbsolutePath().normalize();
        // A missing or non-.class entry artifact is a hard failure before
        // any spawn (the LuaJIT sibling's entry validation analog).
        if (!Files.isRegularFile(entryArtifact)) {
            throw new JvmAsyncExportInvocationException(
                "entry artifact not found: " + entryArtifact, null, null);
        }
        String fileName = entryArtifact.getFileName().toString();
        if (!fileName.endsWith(".class") || fileName.length() == 6) {
            throw new JvmAsyncExportInvocationException(
                "entry artifact must be a compiled .class file: "
                    + entryArtifact, null, null);
        }
        String className = fileName.substring(0, fileName.length() - 6);

        // The reserved generated host-export launcher must sit next to the
        // entry artifact in the orchestrator's flat JVM output layout.
        // The launcher is the entry class's nested $AsyncExportHost: javac
        // names a nested class file <Outer>$<SimpleName>.class, and the
        // simple name itself begins with $, so the binary name carries
        // the outer-separator $ plus the $-prefixed simple name
        // (<ClassName>$$AsyncExportHost) — the exact launcher-class text
        // the java launcher resolves.
        Path artifactRoot = entryArtifact.getParent();
        String launcherBinary = className + "$" + HOST_CLASS_SUFFIX;
        Path launcher = artifactRoot.resolve(launcherBinary + ".class");
        if (!Files.isRegularFile(launcher)) {
            throw new JvmAsyncExportInvocationException(
                "no async-export host entry: missing " + launcher
                    + " next to the entry artifact (the entry artifact was"
                    + " not emitted by the v1.2 entry-module surface)",
                null, null);
        }

        // Protocol validation: the runtime half rejects a non-canonical
        // return descriptor before selection; the production canonical
        // parser is the JVM-side single authority, and the failure maps
        // to the runtime half's pinned host-failure reason — byte-equal
        // to LuaJIT's — with no process spawned.
        DescriptorParseResult parsed = CanonicalRuntimeTypeDescriptor.parse(
            request.returnDescriptor());
        if (!(parsed instanceof DescriptorAst)) {
            return new Result.HostFailure(
                "return descriptor is not a canonical descriptor: "
                    + request.returnDescriptor());
        }

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory(TEMP_DIR_PREFIX);
        } catch (IOException e) {
            throw new JvmAsyncExportInvocationException(
                "cannot create the per-invocation temp directory: "
                    + e.getMessage(), null, null, e);
        }

        String stdout = null;
        String stderr = null;
        try {
            Path stdoutFile = tempDir.resolve("stdout.txt");
            Path stderrFile = tempDir.resolve("stderr.txt");

            // Exactly one non-detached java child per invoke, argv only
            // (no shell), working directory = artifact root, capture files
            // instead of pipes (no pipe deadlock), no deadline, no
            // containment.
            ProcessBuilder pb = new ProcessBuilder("java",
                "-cp", artifactRoot.toString(),
                launcherBinary,
                HOST_ARG,
                request.exportName(),
                request.returnDescriptor());
            pb.directory(artifactRoot.toFile());
            pb.redirectOutput(stdoutFile.toFile());
            pb.redirectError(stderrFile.toFile());

            final Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                throw new JvmAsyncExportInvocationException(
                    "cannot spawn java: " + e.getMessage(), null, null, e);
            }

            final int exit;
            try {
                exit = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JvmAsyncExportInvocationException(
                    "interrupted while waiting for the java child",
                    null, null, e);
            }

            try {
                stdout = AsyncExportEnvelope.readCapture(stdoutFile);
                stderr = AsyncExportEnvelope.readCapture(stderrFile);
            } catch (IOException e) {
                throw new JvmAsyncExportInvocationException(
                    "cannot read the java capture files: " + e.getMessage(),
                    null, null, e);
            }

            return mapOutcome(exit, stdout, stderr);
        } finally {
            AsyncExportEnvelope.deleteRecursively(tempDir);
        }
    }

    // =========================================================================
    // Envelope mapping
    // =========================================================================

    /**
     * Maps process exit + captured streams to an invocation result or a
     * hard failure. The artifact's envelope line is always the last
     * stdout write of the host path, so user {@code console.log} output
     * interleaving on both streams never corrupts the frame.
     */
    private Result mapOutcome(int exit, String stdout, String stderr) {
        String line = AsyncExportEnvelope.lastMarkerLine(stdout, RESULT_PREFIX);
        if (line == null) {
            throw new JvmAsyncExportInvocationException(
                "no " + RESULT_PREFIX + " envelope line on stdout (java"
                    + " exited " + exit + ")", stdout, stderr);
        }

        final AsyncExportEnvelope.Envelope envelope;
        try {
            envelope = AsyncExportEnvelope.decodeEnvelope(line.substring(RESULT_PREFIX.length()));
        } catch (EnvelopeJson.ParseException e) {
            throw new JvmAsyncExportInvocationException(
                "malformed async-export envelope on stdout (java exited "
                    + exit + "): " + e.getMessage(), stdout, stderr, e);
        }

        // Status dispatch owns the hard-failure mapping: the three
        // invocation envelopes map regardless of the exit code (the
        // artifact's host path exits 0 for them), the representation- and
        // infrastructure-failure envelopes throw regardless of the exit
        // code, and every other shape was already rejected fail-closed by
        // the codec.
        return switch (envelope.status) {
            case AsyncExportEnvelope.STATUS_VALUE -> new Result.Value(
                envelope.descriptor, envelope.value);
            case AsyncExportEnvelope.STATUS_DEAL_ERROR -> new Result.DealError(
                envelope.code, envelope.message, envelope.file, envelope.line,
                envelope.column);
            case AsyncExportEnvelope.STATUS_HOST_FAILURE -> new Result.HostFailure(
                envelope.reason);
            case AsyncExportEnvelope.STATUS_REPRESENTATION_FAILURE ->
                throw new JvmAsyncExportInvocationException(
                    "value-representation failure: " + envelope.reason,
                    stdout, stderr);
            case AsyncExportEnvelope.STATUS_INFRASTRUCTURE_FAILURE ->
                throw new JvmAsyncExportInvocationException(
                    "infrastructure failure: " + envelope.reason,
                    stdout, stderr);
            default ->
                throw new JvmAsyncExportInvocationException(
                    "unknown envelope status: " + envelope.status,
                    stdout, stderr);
        };
    }
}
