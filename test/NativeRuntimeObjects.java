package deal.test;

import deal.toolchain.NativeRuntime;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Gate-local preparation; fixture compilation and execution always run. */
final class NativeRuntimeObjects {
    private NativeRuntimeObjects() {}
    private static Path directory;
    private static byte[] compilerIdentity;
    private static final java.util.regex.Pattern SENSITIVE_MACRO = java.util.regex.Pattern.compile(
        "\\b__(FILE|BASE_FILE|INCLUDE_LEVEL|DATE|TIME|TIMESTAMP)__\\b");
    private static final java.util.regex.Pattern LOCAL_INCLUDE = java.util.regex.Pattern.compile(
        "(?m)^\\s*#\\s*include\\s+\"([^\"]+)\"");
    // One content-keyed analysis, not a path/mtime cache. Every request still reads
    // and hashes the complete current tree; failed eligibility is cached too.
    private record Analysis(String fingerprint, String text, boolean eligible) {}
    private static Analysis analysis;

    static synchronized List<Path> prepare(Path root, int level, List<Path> sources,
                                           List<String> flags) {
        root = root.toAbsolutePath().normalize();
        Path normalizedRoot = root;
        if (Boolean.getBoolean("deal.test.native.cold") || sources.stream()
                .noneMatch(p -> NativeRuntime.TRANSLATION_UNITS.contains(relative(p, normalizedRoot)))) {
            return sources;
        }
        // Extra search paths, plugins and linker interposition stay cold.
        if (flags.stream().anyMatch(f -> !f.matches("-D\\w+(?:=.*)?|-fsanitize=[a-z,]+"
                + "|-fno-sanitize-recover=all|-fno-omit-frame-pointer|-g"))) return sources;
        try {
            Map<String, byte[]> tree = new LinkedHashMap<>();
            for (String name : NativeRuntime.FILES) {
                Path file = root.resolve(name);
                if (!Files.isRegularFile(file)) return sources;
                tree.put(name, Files.readAllBytes(file));
            }
            Analysis current = analyze(tree);
            if (!current.eligible()) return sources;
            String text = current.text();
            List<String> runtimeFlags = flags.stream().filter(f -> {
                if (!f.matches("-DDEAL_(STUB|FRAME|QUEUE|MEMORY|TEXT|CROSSING|JSON|GC|BOUNDARY)_CASE=.*")) return true;
                String macro = f.substring(2, f.indexOf('='));
                return text.contains(macro);
            }).toList();
            MessageDigest hash = sha256();
            for (String flag : NativeCompiler.flags(root, level, false)) {
                if (!flag.startsWith("-I")) add(hash, flag.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            Path compiler = Path.of(NativeCompiler.compiler().path());
            add(hash, compiler.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (compilerIdentity == null) compilerIdentity = sha256().digest(Files.readAllBytes(compiler));
            add(hash, compilerIdentity);
            System.getenv().entrySet().stream().filter(e -> !e.getKey().equals("_"))
                .sorted(Map.Entry.comparingByKey()).forEach(e -> {
                    add(hash, e.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    add(hash, e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                });
            for (String flag : runtimeFlags) add(hash, flag.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (var file : tree.entrySet()) {
                add(hash, file.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                add(hash, file.getValue());
            }
            Path cache = directory().resolve(HexFormat.of().formatHex(hash.digest()));
            Files.createDirectories(cache);
            // Suites share keys across JVMs: protect snapshots and object publication.
            try (var channel = java.nio.channels.FileChannel.open(cache.resolve("lock"),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                    var lock = channel.lock()) {
            if (!lock.isValid()) throw new IOException("native object lock was lost");
            Path published = cache.resolve("published");
            for (var file : tree.entrySet()) {
                Path target = published.resolve(file.getKey());
                if (!Files.exists(target)) {
                    Files.createDirectories(target.getParent());
                    Files.write(target, file.getValue());
                }
            }
            Map<String, Path> missing = new LinkedHashMap<>();
            List<Path> result = new ArrayList<>();
            for (Path source : sources) {
                String name = relative(source, root);
                if (!NativeRuntime.TRANSLATION_UNITS.contains(name)) {
                    result.add(source);
                    continue;
                }
                Path object = cache.resolve(source.getFileName() + ".o");
                if (!Files.isRegularFile(object)) missing.put(name, object);
                result.add(object);
            }
            if (!missing.isEmpty()) {
                Path staging = Files.createTempDirectory(cache, "partial-");
                try {
                    List<String> command = new ArrayList<>();
                    command.add(compiler.toString());
                    command.addAll(NativeCompiler.flags(published, level, false));
                    command.addAll(runtimeFlags);
                    command.add("-c");
                    missing.keySet().forEach(name -> command.add(published.resolve(name).toString()));
                    var compiled = NativeCompiler.run(command, staging);
                    if (!compiled.succeeded() || !compiled.stderr().isEmpty()) return sources;
                    for (var entry : missing.entrySet()) {
                        String basename = Path.of(entry.getKey()).getFileName().toString();
                        Files.move(staging.resolve(basename.substring(0, basename.length() - 2) + ".o"),
                            entry.getValue());
                    }
                } finally {
                    try (var paths = Files.walk(staging)) {
                        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                    }
                }
            }
            return List.copyOf(result);
            }
        } catch (IOException error) {
            throw new UncheckedIOException("cannot prepare native runtime", error);
        }
    }

    private static Analysis analyze(Map<String, byte[]> tree) {
        MessageDigest hash = sha256();
        for (var file : tree.entrySet()) {
            add(hash, file.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            add(hash, file.getValue());
        }
        String fingerprint = HexFormat.of().formatHex(hash.digest());
        if (analysis != null && analysis.fingerprint().equals(fingerprint)) return analysis;
        String text = tree.values().stream().map(b -> new String(b,
            java.nio.charset.StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("\n"));
        boolean eligible = !SENSITIVE_MACRO.matcher(text).find();
        var includes = LOCAL_INCLUDE.matcher(text);
        while (eligible && includes.find()) eligible = tree.containsKey(includes.group(1));
        analysis = new Analysis(fingerprint, text, eligible);
        return analysis;
    }

    private static Path directory() throws IOException {
        if (directory == null) {
            String shared = System.getProperty("deal.test.native.objects");
            directory = shared == null ? Files.createTempDirectory(
                NativeCompiler.repository().resolve("build"), "native-objects-")
                : Path.of(shared).toAbsolutePath().normalize();
            // The gate clears build before any suite; standalone
            // suites use fresh preparation, so system/compiler changes cannot leak.
            if (shared == null) Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                } catch (IOException error) { throw new UncheckedIOException(error); }
            }));
        }
        return directory;
    }

    private static String relative(Path source, Path root) {
        return root.relativize(source.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }

    private static void add(MessageDigest hash, byte[] bytes) {
        hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        hash.update(bytes);
    }
}
