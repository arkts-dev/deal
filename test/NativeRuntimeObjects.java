package deal.test;

import deal.toolchain.NativeRuntime;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Run-local build preparation, never a cache of test executions or verdicts. */
final class NativeRuntimeObjects {
    private NativeRuntimeObjects() {}

    private static byte[] compilerIdentity;
    private static Path cacheDirectory;

    private static Path cacheDirectory() throws IOException {
        if (cacheDirectory == null) {
            String shared = System.getProperty("deal.test.native.objects");
            if (shared != null) {
                cacheDirectory = Path.of(shared);
            } else {
                // Standalone suites never consume preparation from an earlier
                // invocation (system headers/toolchain libraries may have changed).
                cacheDirectory = Files.createTempDirectory(
                    NativeCompiler.repository().resolve("build"), "native-test-objects-");
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try (var paths = Files.walk(cacheDirectory)) {
                        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(path);
                        }
                    } catch (IOException failure) {
                        System.err.println("cannot remove native test preparation: " + failure);
                    }
                }, "native-test-object-cleanup"));
            }
        }
        return cacheDirectory;
    }

    // These macros select committed fixture cases, not runtime configurations.
    // Retain a definition if a runtime source/header ever starts consuming it.
    private static final java.util.regex.Pattern FIXTURE_DEFINE =
        java.util.regex.Pattern.compile(
            "-D(DEAL_(?:STUB|FRAME|QUEUE|MEMORY|TEXT|CROSSING|JSON|GC|BOUNDARY)_CASE)=.*");

    /** Replaces only deployed runtime sources; order and duplicate owners survive. */
    static synchronized List<Path> prepare(Path root, int level, List<Path> sources,
                                           List<String> extraFlags) {
        Path absolute = root.toAbsolutePath().normalize();
        boolean usesRuntime = sources.stream().anyMatch(p -> runtimeName(absolute, p) != null);
        // Unknown preprocessing/search/plugin options can introduce dependencies
        // outside the deployed runtime set. Leave those builds entirely cold.
        boolean closedFlags = extraFlags.stream().allMatch(flag ->
            flag.matches("-D[A-Za-z_][A-Za-z_0-9]*(?:=.*)?")
                || flag.matches("-fsanitize=[a-z,]+")
                || flag.equals("-fno-sanitize-recover=all")
                || flag.equals("-fno-omit-frame-pointer") || flag.equals("-g"));
        if (!usesRuntime || !closedFlags || Boolean.getBoolean("deal.test.native.cold")) {
            return sources;
        }
        try {
            // Read bytes on every request: tests deliberately damage and restore
            // publications. A path, timestamp or previous verdict is not a key.
            Map<String, byte[]> tree = new LinkedHashMap<>();
            for (String name : NativeRuntime.FILES) {
                Path file = absolute.resolve(name);
                if (!Files.isRegularFile(file)) {
                    return sources; // Let the real compiler diagnose incomplete input.
                }
                tree.put(name, Files.readAllBytes(file));
            }
            List<String> runtimeFlags = new ArrayList<>();
            String text = tree.values().stream()
                .map(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("\n"));
            if (java.util.regex.Pattern.compile(
                    "\\b__(?:FILE|BASE_FILE|INCLUDE_LEVEL|DATE|TIME|TIMESTAMP)__\\b")
                    .matcher(text).find()) {
                return sources; // Relocation or temporal macros are not reusable.
            }
            var includes = java.util.regex.Pattern.compile(
                "(?m)^\\s*#\\s*include\\s+\"([^\"]+)\"").matcher(text);
            while (includes.find()) {
                if (!tree.containsKey(includes.group(1))) return sources;
            }
            for (String flag : extraFlags) {
                var fixture = FIXTURE_DEFINE.matcher(flag);
                if (!fixture.matches() || java.util.regex.Pattern.compile(
                        "\\b" + fixture.group(1) + "\\b").matcher(text).find()) {
                    runtimeFlags.add(flag);
                }
            }
            MessageDigest digest = sha256();
            add(digest, NativeCompiler.compiler().path());
            // The compiler is preflighted once per suite; hash its executable
            // once as well, not hundreds of megabytes for every fixture.
            if (compilerIdentity == null) {
                compilerIdentity = sha256().digest(Files.readAllBytes(
                    Path.of(NativeCompiler.compiler().path())));
            }
            add(digest, compilerIdentity);
            add(digest, Integer.toString(level));
            for (String flag : deal.toolchain.NativeToolchain.PINNED_FLAGS) add(digest, flag);
            for (String flag : runtimeFlags) add(digest, flag);
            // Includes compiler include/search-path and reproducibility controls.
            System.getenv().entrySet().stream().filter(e -> !e.getKey().equals("_"))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> { add(digest, e.getKey()); add(digest, e.getValue()); });
            for (var entry : tree.entrySet()) {
                add(digest, entry.getKey());
                add(digest, entry.getValue());
            }
            Path cache = cacheDirectory().resolve(HexFormat.of().formatHex(digest.digest()));
            Files.createDirectories(cache);
            // Sequential suites share preparation; standalone/concurrent invocations
            // cannot observe a half-written object. The gate deletes build first.
            try (FileChannel channel = FileChannel.open(cache.resolve("lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var lock = channel.lock()) {
                if (!lock.isValid()) throw new IOException("native object lock was lost");
                Path published = cache.resolve("published");
                for (var entry : tree.entrySet()) {
                    Path target = published.resolve(entry.getKey());
                    if (!Files.exists(target)) {
                        Files.createDirectories(target.getParent());
                        Files.write(target, entry.getValue());
                    }
                }
                List<Path> prepared = new ArrayList<>();
                for (Path source : sources) {
                    String name = runtimeName(absolute, source);
                    if (name == null) {
                        prepared.add(source);
                        continue;
                    }
                    Path object = cache.resolve(Path.of(name).getFileName() + ".o");
                    if (!Files.isRegularFile(object)) {
                        Path partial = object.resolveSibling(object.getFileName() + ".partial");
                        try {
                            NativeCompiler.Result compiled = NativeCompiler.compileOnly(
                                published, level, published.resolve(name), runtimeFlags, partial);
                            if (!compiled.succeeded() || !compiled.stderr().isEmpty()) {
                                // Preserve exact diagnostic behavior for negative builds.
                                return sources;
                            }
                            Files.move(partial, object,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                        } finally {
                            Files.deleteIfExists(partial);
                        }
                    }
                    prepared.add(object);
                }
                return List.copyOf(prepared);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot prepare native test objects", failure);
        }
    }

    private static String runtimeName(Path root, Path source) {
        Path path = source.toAbsolutePath().normalize();
        if (!path.startsWith(root)) return null;
        String name = root.relativize(path).toString().replace('\\', '/');
        return NativeRuntime.TRANSLATION_UNITS.contains(name) ? name : null;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void add(MessageDigest digest, String value) {
        add(digest, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void add(MessageDigest digest, byte[] value) {
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }
}
