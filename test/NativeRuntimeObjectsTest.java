package deal.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Real compiler controls for preparation reuse; executions always run anew. */
public final class NativeRuntimeObjectsTest {
    public static void main(String[] args) throws Exception {
        Path scratch = NativeCompiler.executableScratch("native-object-controls");
        try {
            Path root = Files.createDirectories(scratch.resolve("published"));
            NativeCompiler.deployDeploymentSet(root);
            Path source = root.resolve("deal/native/main.c");
            Path header = root.resolve("deal/native/entry.h");
            Files.writeString(header, "#define TEST_RESULT 3\n");
            Files.writeString(source, "#include \"deal/native/entry.h\"\n"
                + "int main(void) { return TEST_RESULT; }\n");
            var initial = prepare(root, source, 0, List.of());
            check(!initial.getFirst().equals(source), "runtime is prepared as an object");
            check(initial.equals(prepare(root, source, 0, List.of())), "identical bytes reuse object");
            execute(root, source, scratch, 3);

            Path copy = scratch.resolve("second-publication");
            NativeCompiler.deployDeploymentSet(copy);
            Files.writeString(copy.resolve("deal/native/main.c"), Files.readString(source));
            Files.writeString(copy.resolve("deal/native/entry.h"), Files.readString(header));
            check(initial.equals(prepare(copy, copy.resolve("deal/native/main.c"), 0, List.of())),
                "identical publications share objects without depending on deleted fixture paths");

            Files.writeString(header, "#define TEST_RESULT 7\n");
            var changedHeader = prepare(root, source, 0, List.of());
            check(!initial.equals(changedHeader), "header changes invalidate preparation");
            execute(root, source, scratch, 7);
            Files.writeString(header, "#define TEST_RESULT 3\n");
            check(initial.equals(prepare(root, source, 0, List.of())), "restoring bytes restores key");
            Files.writeString(source, "int main(void) { return 11; }\n");
            check(!initial.equals(prepare(root, source, 0, List.of())), "source changes invalidate");
            execute(root, source, scratch, 11);
            check(!prepare(root, source, 0, List.of()).equals(prepare(root, source, 2, List.of())),
                "optimization configurations stay separate");
            check(!prepare(root, source, 0, List.of()).equals(
                prepare(root, source, 0, List.of("-fno-omit-frame-pointer"))),
                "compiler flags stay separate");
            check(!prepare(root, source, 0, List.of()).equals(
                prepare(root, source, 0, NativeCompiler.SANITIZER_FLAGS)),
                "sanitizer instrumentation cannot reuse a plain object");
            check(prepare(root, source, 0, List.of("-I" + scratch)).equals(List.of(source)),
                "untracked include paths use a cold build");
            check(prepare(root, source, 0, List.of("-DDEAL_STUB_CASE=1")).equals(
                prepare(root, source, 0, List.of("-DDEAL_STUB_CASE=2"))),
                "fixture-only definitions do not force runtime rebuilds");
            Files.writeString(source, "int main(void) { return DEAL_STUB_CASE; }\n");
            check(!prepare(root, source, 0, List.of("-DDEAL_STUB_CASE=1")).equals(
                prepare(root, source, 0, List.of("-DDEAL_STUB_CASE=2"))),
                "a macro consumed by the runtime cannot be discarded");
            Files.writeString(source, "int main(void) { return 0; }\n");
            Path fixture = scratch.resolve("fixture.c");
            Files.writeString(fixture, "int fixture(void) { return 0; }\n");
            check(NativeRuntimeObjects.prepare(root, 0, List.of(fixture), List.of())
                .equals(List.of(fixture)), "fixture sources are not replaced");
            var duplicate = NativeCompiler.link(root, 0, List.of(source, source),
                scratch.resolve("duplicate"));
            check(!duplicate.succeeded() && duplicate.stderr().contains("multiple definition"),
                "duplicate ownership is not deduplicated away");
            Path secondSource = root.resolve("deal/native/std.c");
            Files.writeString(secondSource, "int other(void) { return 0; }\n");
            var batch = NativeRuntimeObjects.prepare(root, 2,
                List.of(source, secondSource, source), List.of());
            check(batch.size() == 3 && batch.get(0).equals(batch.get(2))
                && batch.stream().allMatch(Files::isRegularFile),
                "batch preparation preserves every unit and duplicate owner");
            Files.writeString(secondSource, "invalid second translation unit\n");
            check(NativeRuntimeObjects.prepare(root, 2, List.of(source, secondSource), List.of())
                .equals(List.of(source, secondSource)), "one defective unit rejects the whole batch");
            Files.writeString(source, "int main(void) { return __INCLUDE_LEVEL__; }\n");
            check(prepare(root, source, 0, List.of()).equals(List.of(source)),
                "relocation-sensitive sources stay cold");
            Files.writeString(source, "#include \"untracked.h\"\nint main(void) { return 0; }\n");
            check(prepare(root, source, 0, List.of()).equals(List.of(source)),
                "untracked headers stay cold");
            Files.writeString(source, "int main(void) { return 0; }\n");
            Files.delete(header);
            check(prepare(root, source, 0, List.of()).equals(List.of(source)),
                "incomplete publications cannot consume prepared objects");
            Files.writeString(header, "#define TEST_RESULT 3\n");
            Files.writeString(source, "invalid C source\n");
            check(!NativeCompiler.link(root, 0, List.of(source), scratch.resolve("invalid"))
                .succeeded(), "invalid modified source cannot use a previous object");
            System.setProperty("deal.test.native.cold", "true");
            try {
                check(prepare(root, source, 0, List.of()).equals(List.of(source)),
                    "cold control bypasses preparation");
            } finally {
                System.clearProperty("deal.test.native.cold");
            }
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        System.out.println("Native runtime object controls: passed");
    }

    private static List<Path> prepare(Path root, Path source, int level, List<String> flags) {
        return NativeRuntimeObjects.prepare(root, level, List.of(source), flags);
    }

    private static void execute(Path root, Path source, Path scratch, int expected) {
        Path product = scratch.resolve("product");
        var link = NativeCompiler.link(root, 0, List.of(source), product);
        check(link.succeeded(), "prepared program links: " + link.describe());
        var run = NativeCompiler.execute(product, scratch, Map.of());
        check(run.exitCode() == expected, "current bytes execute: " + run.describe());
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
