package deal.test.conformance;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public final class JvmCompilation {
    public record Result(int exitCode, String stdout, String stderr) {}

    private static final ReentrantLock admission = new ReentrantLock();
    private static volatile Process worker;
    private static volatile boolean shuttingDown;
    private static boolean workerStopping;
    private static DataInputStream input;
    private static DataOutputStream output;
    private static final int MAX_OUTPUT = 4 * 1024 * 1024;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(JvmCompilation::shutdown));
    }

    private JvmCompilation() {}

    public static Result compile(List<String> command, Path directory)
            throws IOException, InterruptedException {
        return compile(command, directory, 120_000L);
    }

    public static Result compile(List<String> command, Path directory,
            long timeoutMillis) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        if (command.isEmpty() || !"javac".equals(command.get(0)) || timeoutMillis <= 0) {
            throw new IllegalArgumentException("expected javac command and positive timeout");
        }
        try {
            if (!admission.tryLock(Math.max(0L, deadline - System.nanoTime()),
                    TimeUnit.NANOSECONDS)) {
                throw new IOException("javac compilation exceeded " + timeoutMillis + "ms");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure;
        }
        try {
            if (deadline - System.nanoTime() <= 0) {
                throw new IOException("javac compilation exceeded " + timeoutMillis + "ms");
            }
            if (shuttingDown) {
                throw new IOException("javac worker is shutting down");
            }
            if (workerStopping) {
                stop();
            }
            if (worker == null || !worker.isAlive()) {
                start();
            }
            var reader = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "deal-javac-response");
                thread.setDaemon(true);
                return thread;
            });
            DataInputStream responseStream = input;
            DataOutputStream requestStream = output;
            try {
                if (deadline - System.nanoTime() <= 0) {
                    throw new TimeoutException();
                }
                var response = reader.submit(() -> {
                    requestStream.writeUTF(directory.toAbsolutePath().normalize().toString());
                    requestStream.writeInt(command.size() - 1);
                    for (String argument : command.subList(1, command.size())) {
                        byte[] bytes = argument.getBytes(StandardCharsets.UTF_8);
                        requestStream.writeInt(bytes.length);
                        requestStream.write(bytes);
                    }
                    requestStream.flush();
                    int exit = responseStream.readInt();
                    int length = responseStream.readInt();
                    if (length < 0 || length > MAX_OUTPUT) {
                        throw new IOException("invalid javac response length: " + length);
                    }
                    byte[] bytes = responseStream.readNBytes(length);
                    if (bytes.length != length) {
                        throw new EOFException("incomplete javac response");
                    }
                    return new Result(exit, "", new String(bytes, StandardCharsets.UTF_8));
                });
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new TimeoutException();
                }
                Result result = response.get(remaining, TimeUnit.NANOSECONDS);
                if (deadline - System.nanoTime() <= 0) {
                    throw new TimeoutException();
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                return result;
            } catch (TimeoutException failure) {
                stopAfterFailure(failure);
                throw new IOException("javac compilation exceeded " + timeoutMillis + "ms", failure);
            } catch (ExecutionException failure) {
                stopAfterFailure(failure);
                throw new IOException("javac worker failed", failure.getCause());
            } catch (InterruptedException failure) {
                try {
                    stopAfterFailure(failure);
                } finally {
                    Thread.currentThread().interrupt();
                }
                throw failure;
            } finally {
                reader.shutdownNow();
            }
        } finally {
            admission.unlock();
        }
    }

    private static void start() throws IOException {
        stop();
        List<String> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(
                java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1)) {
            entries.add(Path.of(entry).toAbsolutePath().normalize().toString());
        }
        worker = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java")
            .toString(), "-Xmx512m", "-cp", String.join(java.io.File.pathSeparator, entries),
            JvmCompilation.class.getName(), "worker").redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
        input = new DataInputStream(worker.getInputStream());
        output = new DataOutputStream(worker.getOutputStream());
        if (shuttingDown) {
            stop();
            throw new IOException("javac worker is shutting down");
        }
    }

    private static void stopAfterFailure(Throwable failure) {
        try {
            stop();
        } catch (IOException cleanup) {
            // Preserve the causal timeout/worker failure. Closing a killed
            // worker's pipe may itself fail; retain that cleanup diagnostic
            // without replacing the bounded operation's primary outcome.
            failure.addSuppressed(cleanup);
        }
    }

    private static void stop() throws IOException {
        Process process = worker;
        if (process == null) {
            return;
        }
        workerStopping = true;
        reap(process);
        worker = null;
        workerStopping = false;
        DataInputStream responseStream = input;
        DataOutputStream requestStream = output;
        input = null;
        output = null;
        IOException failure = null;
        try {
            responseStream.close();
        } catch (IOException cleanup) {
            failure = cleanup;
        }
        try {
            requestStream.close();
        } catch (IOException cleanup) {
            if (failure == null) {
                failure = cleanup;
            } else {
                failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void reap(Process process) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            process.destroyForcibly();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    if (process.isAlive()) {
                        throw new IOException("unable to reap javac worker within 5s");
                    }
                    return;
                }
                try {
                    if (!process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                        throw new IOException("unable to reap javac worker within 5s");
                    }
                    return;
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void shutdown() {
        shuttingDown = true;
        Process process = worker;
        if (process != null) {
            try {
                reap(process);
            } catch (IOException failure) {
                System.err.println("javac worker shutdown failed: " + failure);
            }
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1 || !"worker".equals(args[0])) {
            throw new IllegalArgumentException("expected worker mode");
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("system Java compiler unavailable");
        }
        DataInputStream requests = new DataInputStream(System.in);
        DataOutputStream responses = new DataOutputStream(System.out);
        while (true) {
            Path directory;
            try {
                directory = Path.of(requests.readUTF());
            } catch (EOFException end) {
                return;
            }
            int count = requests.readInt();
            if (count < 1 || count > 10000) {
                throw new IOException("invalid javac argument count");
            }
            List<String> arguments = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int length = requests.readInt();
                if (length < 0 || length > MAX_OUTPUT) {
                    throw new IOException("invalid javac argument length");
                }
                byte[] bytes = requests.readNBytes(length);
                if (bytes.length != length) {
                    throw new EOFException("incomplete javac request");
                }
                arguments.add(new String(bytes, StandardCharsets.UTF_8));
            }
            StringWriter messages = new StringWriter();
            int exit;
            try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null,
                    StandardCharsets.UTF_8)) {
                List<String> options = new ArrayList<>();
                List<Path> sources = new ArrayList<>();
                for (int i = 0; i < arguments.size(); i++) {
                    String argument = arguments.get(i);
                    if (argument.endsWith(".java") && !argument.startsWith("-")) {
                        sources.add(directory.resolve(argument).normalize());
                    } else if (List.of("-cp", "-classpath", "--class-path", "-sourcepath")
                            .contains(argument)) {
                        options.add(argument);
                        List<String> paths = new ArrayList<>();
                        for (String entry : arguments.get(++i).split(
                                java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1)) {
                            paths.add(directory.resolve(entry).normalize().toString());
                        }
                        options.add(String.join(java.io.File.pathSeparator, paths));
                    } else if ("-d".equals(argument)) {
                        options.add(argument);
                        options.add(directory.resolve(arguments.get(++i)).normalize().toString());
                    } else {
                        options.add(argument);
                        if (List.of("--release", "-encoding", "-source", "-target")
                                .contains(argument)) {
                            options.add(arguments.get(++i));
                        }
                    }
                }
                options.addAll(List.of("-encoding", "UTF-8", "-sourcepath", ""));
                exit = Boolean.TRUE.equals(compiler.getTask(messages, files, null, options,
                    null, files.getJavaFileObjectsFromPaths(sources)).call()) ? 0 : 1;
            } catch (RuntimeException failure) {
                exit = 2;
                messages.append(failure.toString());
            }
            byte[] bytes = messages.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_OUTPUT) {
                throw new IOException("javac output exceeds capture limit");
            }
            responses.writeInt(exit);
            responses.writeInt(bytes.length);
            responses.write(bytes);
            responses.flush();
        }
    }
}
