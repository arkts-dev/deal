package deal.test;

import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.SemanticOpKind;
import java.nio.file.*;
import java.util.*;

/** Exact LLVM-only omissions. Never consulted by the common conformance lanes. */
final class NativeKnownSkips {
    private final Map<String, Map<String, String>> entries = new LinkedHashMap<>();

    static NativeKnownSkips none() { return new NativeKnownSkips(); }

    static NativeKnownSkips load(Path path) throws Exception {
        return parse(Files.readAllLines(path));
    }

    private static NativeKnownSkips parse(List<String> lines) {
        var skips = none();
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] cells = line.split("\t", -1);
            if (cells.length != 3 || cells[1].isBlank() || cells[2].isBlank()
                    || cells[2].contains("\n") || cells[2].contains("\r"))
                throw new IllegalArgumentException("LLVM skip requires kind, exact key and reason: " + line);
            String kind = cells[0], key = cells[1];
            switch (kind) {
                case "case" -> {
                    if (!key.startsWith("backend-runtime/") || !key.endsWith(".deal"))
                        throw new IllegalArgumentException("invalid LLVM case key: " + key);
                }
                case "host" -> {
                    if (key.endsWith(".c") || key.endsWith(".d.deal"))
                        throw new IllegalArgumentException("LLVM host key must be a stem: " + key);
                }
                case "operation" -> SemanticOpKind.valueOf(key);
                case "boundary" -> BoundaryKind.valueOf(key);
                default -> throw new IllegalArgumentException("unknown LLVM skip kind: " + kind);
            }
            if (!key.matches("[A-Za-z0-9_./-]+") || key.contains("..") || key.startsWith("/"))
                throw new IllegalArgumentException("LLVM skip must be an exact relative key: " + key);
            if (skips.entries.computeIfAbsent(kind, unused -> new LinkedHashMap<>())
                    .putIfAbsent(key, cells[2]) != null)
                throw new IllegalArgumentException("duplicate LLVM skip: " + kind + " " + key);
        }
        return skips;
    }

    boolean contains(String kind, String key) {
        return entries.getOrDefault(kind, Map.of()).containsKey(key);
    }

    void report(String kind, String key) {
        System.out.println("SKIP LLVM " + kind + " " + key + ": " + entries.get(kind).get(key));
    }

    void validateCases(Set<String> discovered) {
        Set<String> stale = new TreeSet<>(entries.getOrDefault("case", Map.of()).keySet());
        stale.removeAll(discovered);
        if (!stale.isEmpty()) throw new AssertionError("unknown LLVM skipped cases: " + stale);
    }

    /** Missing requirements stay visible and earn no execution credit. */
    <T> Set<T> remaining(String kind, Set<T> missing) {
        Set<String> stale = new TreeSet<>(entries.getOrDefault(kind, Map.of()).keySet());
        Set<T> remaining = new LinkedHashSet<>();
        for (T key : missing) {
            if (contains(kind, key.toString())) {
                stale.remove(key.toString());
                report(kind, key.toString());
            } else remaining.add(key);
        }
        if (!stale.isEmpty()) throw new AssertionError("stale LLVM " + kind + " skips: " + stale);
        return remaining;
    }

    static void controls() {
        for (String invalid : List.of("case\tbackend-runtime/a.deal\t", "case\t../a.deal\tx",
                "host\t/a\tx", "host\ta*\tx", "suite\tall\tx", "operation\tINVENTED\tx",
                "boundary\tINVENTED\tx", "host\ta\tx\nwrong")) {
            boolean rejected = false;
            try { parse(List.of(invalid)); } catch (IllegalArgumentException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("accepted invalid LLVM skip: " + invalid);
        }
        boolean rejected = false;
        try { parse(List.of("host\ta\tx", "host\ta\ty")); }
        catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("accepted duplicate LLVM skip");
        var skips = parse(List.of("case\tbackend-runtime/a.deal\tknown", "host\ta\tknown"));
        skips.validateCases(Set.of("backend-runtime/a.deal", "backend-runtime/b.deal"));
        if (!skips.contains("case", "backend-runtime/a.deal")
                || skips.contains("case", "backend-runtime/b.deal")
                || !skips.remaining("host", Set.of("a", "b")).equals(Set.of("b")))
            throw new AssertionError("LLVM skip matched outside its exact key");
        rejected = false;
        try { skips.validateCases(Set.of("backend-runtime/b.deal")); }
        catch (AssertionError expected) { rejected = true; }
        if (!rejected) throw new AssertionError("accepted unknown LLVM skipped case");
        rejected = false;
        try { skips.remaining("host", Set.of()); }
        catch (AssertionError expected) { rejected = true; }
        if (!rejected) throw new AssertionError("accepted stale LLVM requirement skip");
    }

    public static void main(String[] args) throws Exception {
        controls();
        var skips = load(Path.of("test/llvm-known-skips.tsv"));
        var fixtures = CorpusConformanceTest.nativeCorpusFixtures(Path.of("test/conformance"));
        Set<String> discovered = new HashSet<>();
        for (var fixture : fixtures)
            if (fixture.classification().kind() != deal.test.conformance.CorpusDiscovery.Kind.COMPANION)
                discovered.add(fixture.corpusPath());
        skips.validateCases(discovered);
        System.out.println("LLVM known-skip controls passed");
    }
}
