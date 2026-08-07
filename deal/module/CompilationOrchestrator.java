package deal.module;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.lua.LuaBackend;
import deal.lexer.*;
import deal.parser.*;
import deal.types.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Orchestrates multi-module compilation: discovery, parsing, type checking,
 * and code generation for an entire DEAL project.
 *
 * <p>Pipeline phases:
 * <ol>
 *   <li>Discovery: resolve entry module, parse it, find imports, parse
 *       imported modules, repeat depth-first until all modules parsed</li>
 *   <li>Signature extraction: extract export signatures from each parsed
 *       module (lightweight, no full type checking)</li>
 *   <li>Dependency graph: build graph from import relationships, topological
 *       sort, detect circular runtime dependencies</li>
 *   <li>Type checking: in topological order, run Pass 1 (NameResolver) and
 *       Pass 2 (TypeChecker) on each module</li>
 *   <li>Code generation: emit .lua files for each module and copy runtime</li>
 * </ol>
 */
public final class CompilationOrchestrator {

    private final Path entryFile;
    private final Path outputRoot;
    private final boolean verbose;
    private final DealConfig config;
    private final List<Path> moduleRoots;
    private final Path stdlibDir;

    // Phase 0-1: discovery and parsing
    private final Map<String, ModuleInfo> modules = new LinkedHashMap<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private boolean hasErrors = false;

    // Built-in stdlib paths (bundled with compiler)
    private static final List<String> STDLIB_MODULES = List.of(
        "std/console", "std/string", "std/table", "std/json", "std/math"
    );

    /**
     * Internal record for tracking each module throughout compilation.
     */
    private static final class ModuleInfo {
        final String sourcePath;    // resolved file path (e.g. "/abs/path/src/main.deal")
        final String modulePath;    // module path for require (e.g. "main")
        final boolean isDeclarationFile; // .d.deal file
        ProgramNode rawAst;
        Map<String, Type> exports;
        SymbolTable symbolTable;
        CheckResult checkResult;
        ParseResult parseResult;
        NameResolver nameResolver;

        ModuleInfo(String sourcePath, String modulePath, boolean isDeclarationFile) {
            this.sourcePath = sourcePath;
            this.modulePath = modulePath;
            this.isDeclarationFile = isDeclarationFile;
        }
    }

    public CompilationOrchestrator(Path entryFile, Path outputRoot, boolean verbose,
                                    DealConfig config, List<Path> moduleRoots,
                                    Path stdlibDir) {
        this.entryFile = entryFile.toAbsolutePath().normalize();
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
        this.verbose = verbose;
        this.config = config;
        this.moduleRoots = moduleRoots;
        this.stdlibDir = stdlibDir;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Run the full compilation pipeline.
     *
     * @return true if compilation succeeded (no errors), false otherwise
     */
    public boolean compile() throws IOException {
        long startTime = System.currentTimeMillis();

        // Phase 0: Discover and parse all modules
        log("Phase 0: Module discovery and parsing");
        discoverAndParse();
        if (hasErrors) { printDiagnostics(); return false; }

        // Phase 1: Extract export signatures
        log("Phase 1: Export signature extraction");
        extractSignatures();
        if (hasErrors) { printDiagnostics(); return false; }

        // Phase 2: Build dependency graph and order
        log("Phase 2: Dependency graph and ordering");
        List<String> checkOrder = buildCheckOrder();
        if (checkOrder == null) { printDiagnostics(); return false; }

        // Phase 3: Type check in topological order
        log("Phase 3: Type checking (" + checkOrder.size() + " modules)");
        typeCheckAll(checkOrder);
        if (hasErrors) { printDiagnostics(); return false; }

        // Phase 4: Code generation
        log("Phase 4: Code generation");
        codegenAll();
        if (hasErrors) { printDiagnostics(); return false; }

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println("Compilation successful: " + modules.size() + " module(s)");
        if (verbose) {
            System.out.println("Total time: " + elapsed + "ms");
        }
        return true;
    }

    public List<Diagnostic> diagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    // =========================================================================
    // Phase 0: Discovery and parsing
    // =========================================================================

    private void discoverAndParse() throws IOException {
        // Resolve entry module
        String entrySourcePath = entryFile.toString();
        String entryModulePath = computeModulePath(entryFile);

        // Queue the entry module
        Queue<String> pending = new ArrayDeque<>();
        Set<String> inProgress = new HashSet<>();
        pending.add(entrySourcePath);

        while (!pending.isEmpty()) {
            String sourcePath = pending.poll();
            if (modules.containsKey(sourcePath)) continue;

            Path file = Path.of(sourcePath);
            if (!Files.exists(file)) {
                error("E2003", "Module not found: " + sourcePath,
                    file.toString(), 1, 1);
                continue;
            }

            boolean isDecl = sourcePath.endsWith(".d.deal");

            log("  Parsing: " + sourcePath);

            // Read and parse
            String source;
            try {
                source = Files.readString(file);
            } catch (IOException e) {
                error("E2003", "Cannot read module: " + sourcePath + " (" + e.getMessage() + ")",
                    sourcePath, 1, 1);
                continue;
            }

            LexResult lex = new Lexer(source, sourcePath).tokenize();
            diagnostics.addAll(lex.diagnostics());
            if (hasLexErrors(lex)) {
                hasErrors = true;
                continue;
            }

            Parser parser = new Parser(lex.tokens(), sourcePath);
            ParseResult parseResult = parser.parse();
            diagnostics.addAll(parseResult.diagnostics());
            if (parseResult.hasErrors()) {
                hasErrors = true;
                // Continue parsing other modules even if this one has errors
            }

            String modulePath = computeModulePath(file);
            ModuleInfo info = new ModuleInfo(sourcePath, modulePath, isDecl);
            info.rawAst = parseResult.program();
            info.parseResult = parseResult;
            modules.put(sourcePath, info);

            // Discovery: find all imports in this module
            for (StatementNode stmt : parseResult.program().statements()) {
                if (stmt instanceof ImportDeclaration imp) {
                    String importPath = imp.modulePath();
                    String resolved = resolveImportPath(importPath, file);
                    if (resolved != null && !modules.containsKey(resolved)) {
                        pending.add(resolved);
                    }
                }
            }

            // Also scan ExportDeclarations' bodies for nested imports (rare but possible)
            // No — imports are only at module level in DEAL.
        }
    }

    private boolean hasLexErrors(LexResult lex) {
        return lex.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()));
    }

    // =========================================================================
    // Phase 1: Signature extraction
    // =========================================================================

    private void extractSignatures() {
        for (ModuleInfo info : modules.values()) {
            if (info.isDeclarationFile) {
                // .d.deal files: extract export signatures, validate no executable stmts
                ExportExtractor extractor = new ExportExtractor(
                    info.modulePath, true);
                info.exports = extractor.extract(info.rawAst);
                diagnostics.addAll(extractor.diagnostics());
                if (extractor.diagnostics().stream().anyMatch(
                        d -> "error".equals(d.severity()))) {
                    hasErrors = true;
                }
            } else {
                ExportExtractor extractor = new ExportExtractor(
                    info.modulePath, false);
                info.exports = extractor.extract(info.rawAst);
                diagnostics.addAll(extractor.diagnostics());
            }
        }
    }

    // =========================================================================
    // Phase 2: Dependency graph and topological ordering
    // =========================================================================

    private List<String> buildCheckOrder() {
        // Build adjacency: for each module, find which modules it imports
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        for (Map.Entry<String, ModuleInfo> entry : modules.entrySet()) {
            String sourcePath = entry.getKey();
            ModuleInfo info = entry.getValue();
            Set<String> imports = new LinkedHashSet<>();

            for (StatementNode stmt : info.rawAst.statements()) {
                if (stmt instanceof ImportDeclaration imp) {
                    String resolved = resolveImportPath(imp.modulePath(),
                        Path.of(sourcePath));
                    if (resolved != null && modules.containsKey(resolved)) {
                        imports.add(resolved);
                    }
                }
            }
            deps.put(sourcePath, imports);
        }

        // Topological sort (Kahn's algorithm)
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (String src : modules.keySet()) {
            inDegree.put(src, 0);
        }
        for (Set<String> imports : deps.values()) {
            for (String imp : imports) {
                inDegree.merge(imp, 1, Integer::sum);
            }
        }

        // Wait — topological order should put dependencies FIRST.
        // If A imports B, B must be checked before A.
        // So edges go from importer to imported: A → B (A depends on B).
        // B has no outgoing edge to A; A has an outgoing edge to B.
        // In Kahn's: compute in-degree. B's in-degree = number of modules that depend on B.
        // We want B (dependency) to come first. So we want nodes with 0 dependencies (no imports) first.

        // Let me re-think. The dependency direction:
        // A imports B → A depends on B. B should be checked before A.
        // So edges: A → B means "A depends on B".
        // In topo sort, if we follow edges, dependencies come first.
        // Edges from A to B means B must come before A.

        // Compute in-degree: how many modules depend on this one?
        Map<String, Integer> depCount = new LinkedHashMap<>();
        for (String src : modules.keySet()) {
            depCount.put(src, 0);
        }
        for (Map.Entry<String, Set<String>> e : deps.entrySet()) {
            for (String imp : e.getValue()) {
                depCount.merge(e.getKey(), 1, Integer::sum);
            }
        }

        // Hmm, let me simplify. Topological order with edges depender → dependee:
        // Nodes with no dependencies (empty imports set) go first.
        // Then nodes whose dependencies are already processed.

        List<String> order = new ArrayList<>();
        Set<String> remaining = new LinkedHashSet<>(modules.keySet());

        while (!remaining.isEmpty()) {
            boolean found = false;
            for (Iterator<String> it = remaining.iterator(); it.hasNext(); ) {
                String src = it.next();
                Set<String> imports = deps.get(src);
                // Check if all imports are already in order
                if (order.containsAll(imports)) {
                    order.add(src);
                    it.remove();
                    found = true;
                }
            }
            if (!found) {
                // Cycle detected — all remaining modules have unsatisfied dependencies
                return handleCycle(deps, remaining);
            }
        }

        return order;
    }

    private List<String> handleCycle(Map<String, Set<String>> deps,
                                      Set<String> remaining) {
        // Find the cycle
        List<String> cycle = new ArrayList<>();
        String start = remaining.iterator().next();
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        findCycle(deps, start, visited, stack, cycle);
        if (cycle.isEmpty()) {
            cycle.addAll(remaining);
        }

        // Determine if this is a declaration-only cycle (allowed)
        // or a runtime dependency cycle (E2005).
        // For now, we treat any cycle as E2005 since we can't determine
        // at this stage whether it's declaration-only.
        StringBuilder cyclePath = new StringBuilder();
        for (int i = 0; i < cycle.size(); i++) {
            if (i > 0) cyclePath.append(" -> ");
            cyclePath.append(cycle.get(i));
        }

        error("E2005", "Circular import with runtime dependency: " + cyclePath,
            cycle.get(0), 1, 1);
        return null;
    }

    private boolean findCycle(Map<String, Set<String>> deps, String current,
                               Set<String> visited, Deque<String> stack,
                               List<String> cycle) {
        if (stack.contains(current)) {
            // Found cycle — extract it
            boolean found = false;
            for (String s : stack) {
                if (s.equals(current)) found = true;
                if (found) cycle.add(s);
            }
            cycle.add(current);
            return true;
        }
        if (visited.contains(current)) return false;

        visited.add(current);
        stack.addLast(current);

        Set<String> imports = deps.get(current);
        if (imports != null) {
            for (String imp : imports) {
                if (findCycle(deps, imp, visited, stack, cycle)) return true;
            }
        }

        stack.removeLast();
        return false;
    }

    // =========================================================================
    // Phase 3: Type checking
    // =========================================================================

    private void typeCheckAll(List<String> order) {
        // Build a module resolver that uses the pre-extracted exports
        ModuleResolverImpl resolver = new ModuleResolverImpl(modules, diagnostics);

        for (String sourcePath : order) {
            ModuleInfo info = modules.get(sourcePath);
            if (info.isDeclarationFile) {
                // Declaration files are not type-checked (no bodies)
                continue;
            }

            log("  Checking: " + sourcePath);

            // Pass 1: Name resolution
            NameResolver nr = new NameResolver(info.modulePath, resolver);
            info.nameResolver = nr;
            SymbolTable symTable = nr.resolve(info.rawAst);
            info.symbolTable = symTable;

            diagnostics.addAll(nr.diagnostics());
            if (hasNameErrors(nr.diagnostics())) {
                hasErrors = true;
                continue;
            }

            // Pass 2: Type checking
            CheckResult result = TypeChecker.check(info.modulePath, symTable,
                nr, info.rawAst);
            info.checkResult = result;
            diagnostics.addAll(result.diagnostics());
            if (result.hasErrors()) {
                hasErrors = true;
            }
        }
    }

    private boolean hasNameErrors(List<Diagnostic> diags) {
        return diags.stream().anyMatch(d -> "error".equals(d.severity()));
    }

    // =========================================================================
    // Phase 4: Code generation
    // =========================================================================

    private void codegenAll() throws IOException {
        Files.createDirectories(outputRoot);

        // Build import resolution map: for each ImportDeclaration, map
        // the raw import path to the resolved require path.
        for (ModuleInfo info : modules.values()) {
            if (info.isDeclarationFile) continue; // no codegen for .d.deal

            // Build require-path mapping for this module's imports
            Map<String, String> importResolutions = new HashMap<>();
            for (StatementNode stmt : info.rawAst.statements()) {
                if (stmt instanceof ImportDeclaration imp) {
                    String resolvedSource = resolveImportPath(imp.modulePath(),
                        Path.of(info.sourcePath));
                    if (resolvedSource != null) {
                        ModuleInfo imported = modules.get(resolvedSource);
                        if (imported != null) {
                            importResolutions.put(imp.modulePath(),
                                imported.modulePath);
                        }
                    }
                }
            }

            // Generate Lua source
            String luaSource = LuaBackend.generateWithImports(
                info.rawAst, info.checkResult, info.sourcePath, importResolutions);

            // Write to output
            Path outputPath = outputRoot.resolve(info.modulePath + ".lua");
            Files.createDirectories(outputPath.getParent());
            Files.writeString(outputPath, luaSource);

            log("  Generated: " + outputPath);
        }

        // Copy runtime library
        copyRuntimeLibrary();

        // Copy stdlib Lua implementations if needed
        copyStdlibModules();
    }

    private void copyRuntimeLibrary() throws IOException {
        Path runtimeDest = outputRoot.resolve("deal/runtime.lua");
        if (Files.exists(runtimeDest)) return;

        Files.createDirectories(runtimeDest.getParent());

        // Try classpath resource first
        InputStream runtimeStream = getClass().getClassLoader()
            .getResourceAsStream("deal/runtime.lua");
        if (runtimeStream != null) {
            Files.copy(runtimeStream, runtimeDest);
            runtimeStream.close();
            log("  Copied runtime: " + runtimeDest);
            return;
        }

        // Fallback: look relative to current directory
        Path runtimeSrc = Path.of("deal/runtime.lua");
        if (Files.exists(runtimeSrc)) {
            Files.copy(runtimeSrc, runtimeDest);
            log("  Copied runtime: " + runtimeDest);
            return;
        }

        error("E6000", "Runtime library not found: deal/runtime.lua",
            "", 1, 1);
    }

    private void copyStdlibModules() throws IOException {
        for (String stdlibModule : STDLIB_MODULES) {
            Path destFile = outputRoot.resolve(stdlibModule + ".lua");
            if (Files.exists(destFile)) continue;

            // Try stdlib directory first
            if (stdlibDir != null) {
                Path srcFile = stdlibDir.resolve(stdlibModule + ".lua");
                if (Files.exists(srcFile)) {
                    Files.createDirectories(destFile.getParent());
                    Files.copy(srcFile, destFile);
                    log("  Copied stdlib: " + stdlibModule);
                    continue;
                }
                // Try .d.deal
                srcFile = stdlibDir.resolve(stdlibModule + ".d.deal");
                if (Files.exists(srcFile)) {
                    // .d.deal — only declaration, no runtime
                    continue;
                }
            }

            // Try classpath resource
            String resourcePath = "std/" + stdlibModule.substring(4) + ".lua";
            InputStream stream = getClass().getClassLoader()
                .getResourceAsStream(resourcePath);
            if (stream != null) {
                Files.createDirectories(destFile.getParent());
                Files.copy(stream, destFile);
                stream.close();
                log("  Copied stdlib: " + stdlibModule);
            }
        }
    }

    // =========================================================================
    // Import resolution
    // =========================================================================

    /**
     * Resolve an import path to a source file path.
     *
     * @param importPath the import path from source (e.g. "./lib" or "std/console")
     * @param fromFile   the file containing the import
     * @return resolved absolute file path, or null if not found
     */
    public String resolveImportPath(String importPath, Path fromFile) {
        List<String> candidates = new ArrayList<>();

        if (importPath.startsWith("./") || importPath.startsWith("../")) {
            // Relative import
            Path resolved = fromFile.getParent().resolve(importPath).normalize();
            addCandidates(candidates, resolved.toString());
        } else {
            // Bare import — search module roots
            for (Path root : moduleRoots) {
                Path resolved = root.resolve(importPath).normalize();
                addCandidates(candidates, resolved.toString());
            }
            // Also search stdlib dir
            if (stdlibDir != null) {
                Path resolved = stdlibDir.resolve(importPath).normalize();
                addCandidates(candidates, resolved.toString());
            }
            // Also search current directory
            Path resolved = Path.of("").toAbsolutePath().resolve(importPath).normalize();
            addCandidates(candidates, resolved.toString());
        }

        for (String candidate : candidates) {
            if (Files.exists(Path.of(candidate))) {
                return candidate;
            }
        }

        // Not found — record diagnostic with attempted paths
        StringBuilder msg = new StringBuilder("Module not found: '" + importPath
            + "'. Attempted: ");
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) msg.append(", ");
            msg.append(candidates.get(i));
        }
        error("E2003", msg.toString(), fromFile.toString(), 1, 1);
        return null;
    }

    private void addCandidates(List<String> candidates, String basePath) {
        candidates.add(basePath + ".deal");
        candidates.add(basePath + "/index.deal");
        candidates.add(basePath + ".d.deal");
        candidates.add(basePath + "/index.d.deal");
    }

    // =========================================================================
    // Path computation
    // =========================================================================

    /**
     * Compute the module path for require() calls.
     * Converts a file path like /abs/path/src/main.deal to "main".
     */
    private String computeModulePath(Path sourceFile) {
        // Find the best-matching module root for this file
        Path absFile = sourceFile.toAbsolutePath().normalize();
        Path bestRoot = null;
        int bestLength = -1;

        for (Path root : moduleRoots) {
            Path absRoot = root.toAbsolutePath().normalize();
            if (absFile.startsWith(absRoot)) {
                int len = absRoot.toString().length();
                if (len > bestLength) {
                    bestLength = len;
                    bestRoot = absRoot;
                }
            }
        }

        if (bestRoot != null) {
            Path relative = bestRoot.relativize(absFile);
            String path = relative.toString();
            // Remove .deal or .d.deal extension
            if (path.endsWith(".d.deal")) {
                path = path.substring(0, path.length() - ".d.deal".length());
            } else if (path.endsWith(".deal")) {
                path = path.substring(0, path.length() - ".deal".length());
            }
            // Normalize separators to dots for Lua require()
            return path.replace('/', '.').replace('\\', '.');
        }

        // Fallback: use filename without extension
        String name = sourceFile.getFileName().toString();
        if (name.endsWith(".d.deal")) {
            return name.substring(0, name.length() - ".d.deal".length());
        } else if (name.endsWith(".deal")) {
            return name.substring(0, name.length() - ".deal".length());
        }
        return name;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void log(String msg) {
        if (verbose) System.out.println(msg);
    }

    private void error(String code, String message, String file, int line, int col) {
        diagnostics.add(Diagnostic.error(code, message, file, line, col));
        if ("error".equals("error")) hasErrors = true;
    }

    private void printDiagnostics() {
        for (Diagnostic d : diagnostics) {
            if ("error".equals(d.severity())) {
                System.err.println(d.file() + ":" + d.line() + ":" + d.column()
                    + ": error " + d.code() + ": " + d.message());
            }
        }
        long errorCount = diagnostics.stream()
            .filter(d -> "error".equals(d.severity())).count();
        long warnCount = diagnostics.stream()
            .filter(d -> "warning".equals(d.severity())).count();
        System.err.println(errorCount + " error(s), " + warnCount + " warning(s)");
    }

    // =========================================================================
    // ModuleResolverImpl for NameResolver
    // =========================================================================

    /**
     * Implementation of ModuleResolver that provides pre-computed export
     * types to the NameResolver during type checking.
     */
    final class ModuleResolverImpl implements ModuleResolver {

        private final Map<String, ModuleInfo> modules;
        private final List<Diagnostic> diagnostics;

        ModuleResolverImpl(Map<String, ModuleInfo> modules,
                           List<Diagnostic> diagnostics) {
            this.modules = modules;
            this.diagnostics = diagnostics;
        }

        @Override
        public Map<String, Type> resolveModule(String modulePath,
                                                String importingModule,
                                                Set<String> modulesInProgress)
                throws ModuleNotFoundException {
            // Look up by resolving the import path
            Path importingFile = null;
            for (ModuleInfo info : modules.values()) {
                if (info.modulePath.equals(importingModule) || info.sourcePath.equals(importingModule)) {
                    importingFile = Path.of(info.sourcePath);
                    break;
                }
            }
            if (importingFile == null) {
                // Try matching by source path
                importingFile = Path.of(importingModule);
            }

            // Resolve the import path relative to the importing file
            // We need the orchestrator's resolveImportPath, but we don't
            // have it directly. Instead, iterate over all modules and
            // find the one that matches.
            // The module path stored in ImportDeclaration is the raw import path.
            // We need to find which resolved module corresponds to it.

            // Strategy: for each known module, check if its sourcePath
            // is the resolution of this importPath from the importing file.
            for (ModuleInfo info : modules.values()) {
                // Check if info.sourcePath could be the resolution
                // This is a heuristic: check if the module path matches
                // the import path structure
                if (isMatch(modulePath, importingFile, info)) {
                    return info.exports != null ? info.exports : Map.of();
                }
            }

            throw new ModuleNotFoundException("Module not found: " + modulePath);
        }

        private boolean isMatch(String importPath, Path fromFile, ModuleInfo info) {
            // Check if the source path matches what we'd get from resolving
            // the import path relative to fromFile
            if (fromFile == null) return false;

            Path fromDir = fromFile.getParent();
            if (importPath.startsWith("./") || importPath.startsWith("../")) {
                Path resolved = fromDir.resolve(importPath).normalize();
                String resolvedBase = resolved.toString();

                String sourcePath = info.sourcePath;
                if (sourcePath.equals(resolvedBase + ".deal")) return true;
                if (sourcePath.equals(resolvedBase + "/index.deal")) return true;
                if (sourcePath.equals(resolvedBase + ".d.deal")) return true;
                if (sourcePath.equals(resolvedBase + "/index.d.deal")) return true;
            } else {
                // Bare import — check various roots
                Path absSource = Path.of(info.sourcePath).toAbsolutePath().normalize();
                for (Path root : moduleRoots) {
                    Path resolved = root.resolve(importPath).normalize();
                    if (absSource.equals(resolved.resolveSibling(
                            resolved.getFileName() + ".deal"))) return true;
                    // Simpler: check if the module path (not source path) matches
                }
                // Check module path
                if (info.modulePath.equals(importPath.replace('/', '.'))) return true;
            }

            return false;
        }
    }
}
