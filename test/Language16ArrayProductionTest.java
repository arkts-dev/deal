package deal.test;

import deal.codegen.Backend;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Receiver-array contracts executed on all production backends and native instrumentation. */
public final class Language16ArrayProductionTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("deal-language16-arrays-");
        try {
            Path corpus = Path.of("test/conformance").toAbsolutePath().normalize();
            var fixtures = CorpusConformanceTest.nativeCorpusFixtures(corpus);
            for (String name : List.of("readonly", "push", "push-live-retry", "slice", "slice-live-retry", "search", "search-live-retry", "join", "join-live-retry", "map", "map-live-retry", "find", "find-live-retry", "predicates", "predicates-live-retry")) {
                String path = "backend-runtime/arrays/" + name + ".deal";
                var fixture = fixtures.stream().filter(f -> f.corpusPath().equals(path)).findFirst().orElseThrow();
                Path host = null;
                if (name.endsWith("-live-retry")) {
                    host = Files.createDirectories(root.resolve(name + "-host"));
                    for (String extension : List.of("d.deal", "js", "lua", "java", "c")) {
                        String companion = Files.readString(corpus.resolve("host-fixtures/"
                            + (name.startsWith("join") ? "array_join" : "array_pop") + "." + extension));
                        if (extension.equals("java") && name.startsWith("join"))
                            companion = companion.replace("HostArray_join", "HostProbe");
                        Files.writeString(host.resolve("probe." + extension), companion);
                    }
                }
                CompositionProductionTest.execute(ConformanceHarnessMetadata.productionCorpusSource(
                    Files.readString(corpus.resolve(path)), path), "", root.resolve(name), host,
                    List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
                LlvmConformanceTest.runInstrumented(corpus, fixture);
            }
            System.out.println("v1.6 arrays: all four backends and native instrumentation passed");
        } finally { CompositionProductionTest.remove(root); }
    }
}
