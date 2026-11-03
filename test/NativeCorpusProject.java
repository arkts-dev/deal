package deal.test;

import deal.ast.*;
import deal.codegen.Backend;
import deal.lexer.Lexer;
import deal.parser.Parser;
import deal.module.*;
import deal.project.*;
import deal.semantic.*;
import deal.test.conformance.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** An isolated, corpus-coordinate production project; no expectation rewriting. */
final class NativeCorpusProject implements AutoCloseable {
    final Path scratch, root, entry, output;
    final CorpusDiscovery.Fixture fixture;
    final Map<Path, Path> written = new LinkedHashMap<>();
    final Map<String, Path> hosts = new LinkedHashMap<>();
    final Map<String, CorpusExecution.Source> sources = new LinkedHashMap<>();
    final Map<String, String> tokens = new LinkedHashMap<>();
    final Path corpus;
    CompilationOrchestrator compiler;
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+.*?\\s+from\\s+\"([^\"]+)\"");

    NativeCorpusProject(Path corpus, CorpusDiscovery.Fixture fixture,
            SidecarExpectations.StructuredExpectationSidecar sidecar) throws Exception {
        this.corpus = corpus.toAbsolutePath().normalize();
        this.fixture = fixture;
        scratch = NativeCompiler.executableScratch("native-corpus");
        root = scratch.resolve("conformance");
        entry = root.resolve(fixture.corpusPath()).resolve("main.deal");
        output = scratch.resolve("out");
        try {
            copy(fixture.file().toAbsolutePath().normalize());
            Set<String> names = new LinkedHashSet<>();
            for (Path target : written.values()) {
                var matches = IMPORT.matcher(Files.readString(target));
                while (matches.find()) if (matches.group(1).startsWith("host/")) names.add(matches.group(1));
            }
            String selected = sidecar == null ? "" : sidecar.hostFixture();
            if (!selected.isEmpty() && !names.equals(Set.of("host/probe")))
                throw new IllegalArgumentException("hostFixture must select exactly host/probe");
            StringBuilder external = new StringBuilder();
            for (String name : names) {
                String stem = selected.isEmpty() ? name.substring(5) : selected;
                Path source = this.corpus.resolve("host-fixtures/" + stem + ".d.deal");
                Path target = root.resolve("bindings/" + name.substring(5) + ".d.deal");
                Files.createDirectories(target.getParent());
                Files.writeString(target, ConformanceHarnessMetadata.productionCorpusSource(Files.readString(source), source.toString()));
                if (!external.isEmpty()) external.append(',');
                external.append(CorpusExecution.quote(name)).append(":{\"declaration\":")
                    .append(CorpusExecution.quote("conformance/bindings/" + name.substring(5) + ".d.deal")).append('}');
                hosts.put(name, this.corpus.resolve("host-fixtures/" + stem + ".c"));
            }
            Files.writeString(scratch.resolve("deal.json"), "{\"languageVersion\":\"1.5\",\"stdlib\":\"1.5\","
                + "\"moduleRoots\":[\"conformance\"],\"backend\":\"llvm\",\"output\":\"out\",\"externals\":{" + external + "}}");
        } catch (Throwable e) { close(); throw e; }
    }

    private void copy(Path original) throws Exception {
        original = original.toAbsolutePath().normalize();
        if (written.containsKey(original)) return;
        Path base = fixture.file().toAbsolutePath().normalize().getParent();
        Path rel = base.relativize(original);
        Path target = original.equals(fixture.file().toAbsolutePath().normalize()) ? entry
            : root.resolve(fixture.corpusPath()).resolve(rel);
        if (!target.normalize().startsWith(root)) throw new IllegalArgumentException("companion escapes project: " + original);
        String raw = Files.readString(original);
        String source = ConformanceHarnessMetadata.productionCorpusSource(raw, original.toString());
        Files.createDirectories(target.getParent()); Files.writeString(target, source);
        written.put(original, target);
        String coordinate = corpus.relativize(original).toString().replace('\\', '/');
        var pin = new CorpusExecution.Source(coordinate, raw.split("\n", -1).length - source.split("\n", -1).length);
        String projectPath = root.relativize(target).toString().replace('\\', '/');
        sources.put(projectPath, pin);
        sources.put("conformance/" + projectPath, pin);
        sources.put(target.toString(), pin);
        tokens.put(root.relativize(target).toString().replace('\\', '/'), coordinate);
        var matches = IMPORT.matcher(source);
        while (matches.find()) {
            String name = matches.group(1);
            if (!name.startsWith(".")) continue;
            Path resolved = null;
            for (String suffix : List.of("", ".deal", ".d.deal")) {
                Path candidate = original.getParent().resolve(name + suffix).normalize();
                if (Files.isRegularFile(candidate)) { resolved = candidate; break; }
            }
            if (resolved == null) throw new IllegalArgumentException("missing companion: " + name);
            copy(resolved);
            if (name.endsWith(".deal")) {
                Path copied = written.get(resolved.toAbsolutePath().normalize());
                Path alias = copied.resolveSibling(copied.getFileName() + ".deal");
                Files.writeString(alias, Files.readString(copied));
                sources.put(root.relativize(alias).toString().replace('\\', '/'), sources.get(copied.toString()));
                sources.put("conformance/" + root.relativize(alias).toString().replace('\\', '/'), sources.get(copied.toString()));
                tokens.put(root.relativize(alias).toString().replace('\\', '/'), corpus.relativize(resolved).toString().replace('\\', '/'));
            }
        }
    }

    boolean compile() throws Exception {
        var located = ProjectLocator.locate(entry.toString(), new CliOverrides("llvm", output.toString()));
        if (located.context() == null) throw new IllegalStateException("HARNESS_DEFECT: project resolution: " + located);
        // Use the same corpus orchestrator input as the other three backends.
        // Their ordinary declarations have a dotted identity alias ahead of
        // the slash import key; selected probes intentionally have only slash.
        Map<String, ExternalEntry> externals = new LinkedHashMap<>();
        for (var host : located.context().externals().entrySet()) {
            var value = host.getValue();
            if (!host.getKey().equals("host/probe")
                    && hosts.get(host.getKey()).getParent().equals(corpus.resolve("host-fixtures"))) {
                String alias = host.getKey().replace('/', '.');
                externals.put(alias, new ExternalEntry(alias, value.declarationPath(), value.sourceRange()));
            }
            externals.put(host.getKey(), value);
        }
        var base = located.context();
        var context = new ProjectContext(base.manifestPath(), base.projectRoot(), base.manifestDirectory(),
            base.languageVersion(), base.configuredModuleRoots(), base.outputPath(), base.backend(), externals,
            base.stdlibVersion(), base.stdlibSurfacePath(), base.stdlibDeclarationFiles(), base.projectDeploymentIdentity());
        compiler = new CompilationOrchestrator(context, entry, false, false, false, false, null,
            CompilerProfileProvider.resolve(ReleaseConfiguration.CURRENT_RELEASE_STATE,
                ReleaseConfiguration.releaseCapabilityRegistry()));
        boolean ok = compiler.compile();
        if (ok) {
            if (!Files.isRegularFile(output.resolve((fixture.corpusPath() + "/main").replace('.', '/') + ".ll")))
                throw new IllegalStateException("ARTIFACT_MISSING: entry LLVM module");
            for (String file : deal.toolchain.NativeRuntime.FILES)
                if (!Files.isRegularFile(output.resolve(file))) throw new IllegalStateException("ARTIFACT_MISSING: " + file);
            for (String host : hosts.keySet())
                if (!Files.isRegularFile(output.resolve(host + ".h"))) throw new IllegalStateException("ARTIFACT_MISSING: " + host);
        }
        return ok;
    }

    ProgramNode program() throws Exception {
        var result = new Parser(new Lexer(Files.readString(entry), entry.toString()).tokenize().tokens(), entry.toString()).parse();
        if (result.hasErrors()) throw new IllegalStateException("HARNESS_DEFECT: " + result.diagnostics());
        return result.program();
    }

    String module() { return (fixture.corpusPath() + "/main").replace('/', '.'); }

    List<Path> hostUnits() throws Exception {
        List<Path> units = new ArrayList<>();
        for (var host : hosts.entrySet()) {
            if (!Files.isRegularFile(host.getValue())) throw new IllegalStateException("ARTIFACT_MISSING: native host " + host.getValue());
            Path target = scratch.resolve("hosts/" + host.getKey().substring(5) + ".c");
            Files.createDirectories(target.getParent());
            for (String helper : List.of("native_helpers.h", "native_hostile.h"))
                Files.copy(corpus.resolve("host-fixtures/" + helper), target.getParent().resolve(helper), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(host.getValue(), target, StandardCopyOption.REPLACE_EXISTING);
            units.add(target);
        }
        return units;
    }

    public void close() throws Exception {
        if (Files.exists(scratch)) try (var paths = Files.walk(scratch)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
