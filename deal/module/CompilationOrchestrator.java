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

    private final Map<String, ModuleInfo> modules = new LinkedHashMap<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private boolean hasErrors = false;

    private static final List<String> STDLIB_MODULES = List.of(
        "std/console", "std/string", "std/table", "std/json", "std/math"
    );

    private static final class ModuleInfo {
        final String sourcePath;
        final String modulePath;
        final boolean isDeclarationFile;
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

    public boolean compile() throws IOException {
        long startTime = System.currentTimeMillis();

        log("Phase 0: Module discovery and parsing");
        discoverAndParse();
        if (hasErrors) { printDiagnostics(); return false; }

        log("Phase 1: Export signature extraction");
        extractSignatures();
        if (hasErrors) { printDiagnostics(); return false; }

        log("Phase 2: Dependency graph and ordering");
        List<String> checkOrder = buildCheckOrder();
        if (checkOrder == null) { printDiagnostics(); return false; }

        log("Phase 3: Type checking (" + checkOrder.size() + " modules)");
        typeCheckAll(checkOrder);
        if (hasErrors) { printDiagnostics(); return false; }

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
        String entrySourcePath = entryFile.toString();
        Queue<String> pending = new ArrayDeque<>();
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
            }

            String modulePath = computeModulePath(file);
            ModuleInfo info = new ModuleInfo(sourcePath, modulePath, isDecl);
            info.rawAst = parseResult.program();
            info.parseResult = parseResult;
            modules.put(sourcePath, info);

            for (StatementNode stmt : parseResult.program().statements()) {
                if (stmt instanceof ImportDeclaration imp) {
                    String importPath = imp.modulePath();
                    String resolved = resolveImportPath(importPath, file);
                    if (resolved != null && !modules.containsKey(resolved)) {
                        pending.add(resolved);
                    }
                }
            }
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
                ExportExtractor extractor = new ExportExtractor(info.modulePath, true);
                info.exports = extractor.extract(info.rawAst);
                diagnostics.addAll(extractor.diagnostics());
                if (extractor.diagnostics().stream().anyMatch(
                        d -> "error".equals(d.severity()))) {
                    hasErrors = true;
                }
            } else {
                ExportExtractor extractor = new ExportExtractor(info.modulePath, false);
                info.exports = extractor.extract(info.rawAst);
                diagnostics.addAll(extractor.diagnostics());
            }
        }
    }

    // =========================================================================
    // Phase 2: Dependency graph and topological ordering
    // =========================================================================

    private List<String> buildCheckOrder() {
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

        List<String> order = new ArrayList<>();
        Set<String> remaining = new LinkedHashSet<>(modules.keySet());

        while (!remaining.isEmpty()) {
            boolean found = false;
            for (Iterator<String> it = remaining.iterator(); it.hasNext(); ) {
                String src = it.next();
                Set<String> imports = deps.get(src);
                if (order.containsAll(imports)) {
                    order.add(src);
                    it.remove();
                    found = true;
                }
            }
            if (!found) {
                return handleCycle(deps, remaining);
            }
        }
        return order;
    }

    private List<String> handleCycle(Map<String, Set<String>> deps,
                                      Set<String> remaining) {
        List<String> cycle = new ArrayList<>();
        String start = remaining.iterator().next();
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        findCycle(deps, start, visited, stack, cycle);
        if (cycle.isEmpty()) {
            cycle.addAll(remaining);
        }

        if (isDeclarationOnlyCycle(cycle, deps)) {
            List<String> order = new ArrayList<>();
            Set<String> cycleSet = new LinkedHashSet<>(cycle);
            Set<String> allRemaining = new LinkedHashSet<>(remaining);
            Set<String> nonCycle = new LinkedHashSet<>(allRemaining);
            nonCycle.removeAll(cycleSet);

            for (String src : nonCycle) {
                Set<String> imports = deps.get(src);
                if (order.containsAll(imports)) {
                    order.add(src);
                }
            }
            order.addAll(cycle);
            return order;
        }

        StringBuilder cyclePath = new StringBuilder();
        for (int i = 0; i < cycle.size(); i++) {
            if (i > 0) cyclePath.append(" -> ");
            cyclePath.append(cycle.get(i));
        }

        error("E2005", "Circular import with runtime dependency: " + cyclePath,
            cycle.get(0), 1, 1);
        return null;
    }

    private boolean isDeclarationOnlyCycle(List<String> cycle,
                                            Map<String, Set<String>> deps) {
        Set<String> cycleSet = new LinkedHashSet<>(cycle);

        for (String modulePath : cycle) {
            ModuleInfo info = modules.get(modulePath);
            if (info == null || info.rawAst == null) continue;

            for (StatementNode stmt : info.rawAst.statements()) {
                if (stmt instanceof ImportDeclaration imp) {
                    String resolved = resolveImportPath(imp.modulePath(),
                        Path.of(modulePath));
                    if (resolved != null && cycleSet.contains(resolved)) {
                        if (usesImportAtRuntime(info.rawAst, imp.alias())) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private boolean usesImportAtRuntime(ProgramNode program, String alias) {
        for (StatementNode stmt : program.statements()) {
            if (hasRuntimeImportUsage(stmt, alias)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRuntimeImportUsage(StatementNode stmt, String alias) {
        return switch (stmt) {
            case VariableDeclaration vd -> {
                if (vd.initializer() != null
                        && exprReferencesImport(vd.initializer(), alias)) {
                    yield true;
                }
                yield false;
            }
            case ExpressionStatement es -> {
                yield exprReferencesImport(es.expr(), alias);
            }
            case ReturnStatement rs -> {
                if (rs.expr().isPresent()
                        && exprReferencesImport(rs.expr().get(), alias)) {
                    yield true;
                }
                yield false;
            }
            case IfStatement is -> {
                if (exprReferencesImport(is.condition(), alias)) yield true;
                if (hasRuntimeImportUsage(is.thenBlock(), alias)) yield true;
                if (is.elseBranch().isPresent()) {
                    Either<IfStatement, Block> eb = is.elseBranch().get();
                    if (eb instanceof Either.Left<IfStatement, Block> left) {
                        if (hasRuntimeImportUsage(left.value(), alias)) yield true;
                    } else if (eb instanceof Either.Right<IfStatement, Block> right) {
                        if (hasRuntimeImportUsage(right.value(), alias)) yield true;
                    }
                }
                yield false;
            }
            case WhileStatement ws -> {
                if (exprReferencesImport(ws.condition(), alias)) yield true;
                if (hasRuntimeImportUsage(ws.body(), alias)) yield true;
                yield false;
            }
            case ForStatement fs -> {
                if (fs.init().isPresent()
                        && fs.init().get() instanceof ForInit.AssignExpr ie) {
                    AssignmentExpr ae = ie.expr();
                    if (exprReferencesImport(ae.target(), alias)) yield true;
                    if (exprReferencesImport(ae.value(), alias)) yield true;
                }
                if (fs.condition().isPresent()
                        && exprReferencesImport(fs.condition().get(), alias)) yield true;
                if (fs.update().isPresent()
                        && exprReferencesImport(fs.update().get(), alias)) yield true;
                if (hasRuntimeImportUsage(fs.body(), alias)) yield true;
                yield false;
            }
            case Block b -> {
                for (StatementNode s : b.statements()) {
                    if (hasRuntimeImportUsage(s, alias)) yield true;
                }
                yield false;
            }
            case TryStatement ts -> {
                if (hasRuntimeImportUsage(ts.tryBlock(), alias)) yield true;
                if (ts.catchBlock() != null
                        && hasRuntimeImportUsage(ts.catchBlock(), alias)) yield true;
                yield false;
            }
            case ThrowStatement th -> {
                yield exprReferencesImport(th.expr(), alias);
            }
            case ExportDeclaration ed -> {
                yield hasRuntimeImportUsage(ed.declaration(), alias);
            }
            case FunctionDeclaration fd -> false;
            case ClassDeclaration cd -> false;
            case ImportDeclaration id -> false;
            case DeleteStatement ds -> false;
            case BreakStatement bs -> false;
            case ContinueStatement cs -> false;
        };
    }

    private boolean exprReferencesImport(ExpressionNode expr, String alias) {
        return switch (expr) {
            case IdentifierExpr id -> id.name().equals(alias);
            case MemberAccessExpr ma -> {
                if (exprReferencesImport(ma.object(), alias)) yield true;
                yield false;
            }
            case CallExpr ce -> {
                if (exprReferencesImport(ce.callee(), alias)) yield true;
                for (ExpressionNode arg : ce.args()) {
                    if (exprReferencesImport(arg, alias)) yield true;
                }
                yield false;
            }
            case BinaryExpr be -> {
                if (exprReferencesImport(be.left(), alias)) yield true;
                if (exprReferencesImport(be.right(), alias)) yield true;
                yield false;
            }
            case UnaryExpr ue -> {
                yield exprReferencesImport(ue.expr(), alias);
            }
            case IndexExpr ie -> {
                if (exprReferencesImport(ie.array(), alias)) yield true;
                if (exprReferencesImport(ie.index(), alias)) yield true;
                yield false;
            }
            case ArrayLiteralExpr al -> {
                for (ExpressionNode e : al.elements()) {
                    if (exprReferencesImport(e, alias)) yield true;
                }
                yield false;
            }
            case ObjectLiteralExpr ol -> {
                for (Property p : ol.properties()) {
                    if (exprReferencesImport(p.value(), alias)) yield true;
                }
                yield false;
            }
            case AssignmentExpr ae -> {
                if (exprReferencesImport(ae.target(), alias)) yield true;
                if (exprReferencesImport(ae.value(), alias)) yield true;
                yield false;
            }
            case HasExpr he -> {
                yield exprReferencesImport(he.object(), alias);
            }
            case FunctionExpr fe -> false;
            case LiteralExpr le -> false;
        };
    }

    private boolean findCycle(Map<String, Set<String>> deps, String current,
                               Set<String> visited, Deque<String> stack,
                               List<String> cycle) {
        if (stack.contains(current)) {
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
        ModuleResolverImpl resolver = new ModuleResolverImpl(modules, diagnostics);

        for (String sourcePath : order) {
            ModuleInfo info = modules.get(sourcePath);
            if (info.isDeclarationFile) continue;

            log("  Checking: " + sourcePath);

            NameResolver nr = new NameResolver(info.modulePath, resolver);
            info.nameResolver = nr;
            SymbolTable symTable = nr.resolve(info.rawAst);
            info.symbolTable = symTable;

            diagnostics.addAll(nr.diagnostics());
            if (hasNameErrors(nr.diagnostics())) {
                hasErrors = true;
                continue;
            }

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

        for (ModuleInfo info : modules.values()) {
            if (info.isDeclarationFile) continue;

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

            String luaSource = LuaBackend.generateWithImports(
                info.rawAst, info.checkResult, info.sourcePath, importResolutions);

            Path outputPath = outputRoot.resolve(info.modulePath + ".lua");
            Files.createDirectories(outputPath.getParent());
            Files.writeString(outputPath, luaSource);

            log("  Generated: " + outputPath);
        }

        copyRuntimeLibrary();
        copyStdlibModules();
    }

    private void copyRuntimeLibrary() throws IOException {
        Path runtimeDest = outputRoot.resolve("deal/runtime.lua");
        if (Files.exists(runtimeDest)) return;

        Files.createDirectories(runtimeDest.getParent());

        InputStream runtimeStream = getClass().getClassLoader()
            .getResourceAsStream("deal/runtime.lua");
        if (runtimeStream != null) {
            Files.copy(runtimeStream, runtimeDest);
            runtimeStream.close();
            log("  Copied runtime: " + runtimeDest);
            return;
        }

        Path runtimeSrc = Path.of("deal/runtime.lua");
        if (Files.exists(runtimeSrc)) {
            Files.copy(runtimeSrc, runtimeDest);
            log("  Copied runtime: " + runtimeDest);
            return;
        }

        error("E6000", "Runtime library not found: deal/runtime.lua", "", 1, 1);
    }

    private void copyStdlibModules() throws IOException {
        for (String stdlibModule : STDLIB_MODULES) {
            Path destFile = outputRoot.resolve(stdlibModule + ".lua");
            if (Files.exists(destFile)) continue;

            if (stdlibDir != null) {
                Path srcFile = stdlibDir.resolve(stdlibModule + ".lua");
                if (Files.exists(srcFile)) {
                    Files.createDirectories(destFile.getParent());
                    Files.copy(srcFile, destFile);
                    log("  Copied stdlib: " + stdlibModule);
                    continue;
                }
                srcFile = stdlibDir.resolve(stdlibModule + ".d.deal");
                if (Files.exists(srcFile)) continue;
            }

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

    public String resolveImportPath(String importPath, Path fromFile) {
        List<String> candidates = new ArrayList<>();

        if (importPath.startsWith("./") || importPath.startsWith("../")) {
            Path resolved = fromFile.getParent().resolve(importPath).normalize();
            addCandidates(candidates, resolved.toString());
        } else {
            for (Path root : moduleRoots) {
                Path resolved = root.resolve(importPath).normalize();
                addCandidates(candidates, resolved.toString());
            }
            if (stdlibDir != null) {
                Path resolved = stdlibDir.resolve(importPath).normalize();
                addCandidates(candidates, resolved.toString());
            }
            Path resolved = Path.of("").toAbsolutePath().resolve(importPath).normalize();
            addCandidates(candidates, resolved.toString());
        }

        for (String candidate : candidates) {
            if (Files.exists(Path.of(candidate))) {
                return candidate;
            }
        }

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

    private String computeModulePath(Path sourceFile) {
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
            if (path.endsWith(".d.deal")) {
                path = path.substring(0, path.length() - ".d.deal".length());
            } else if (path.endsWith(".deal")) {
                path = path.substring(0, path.length() - ".deal".length());
            }
            return path.replace('/', '.').replace('\\', '.');
        }

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
        hasErrors = true;
    }

    private void printDiagnostics() {
        for (Diagnostic d : diagnostics) {
            System.err.println(d.file() + ":" + d.line() + ":" + d.column()
                + ": " + d.severity() + " " + d.code() + ": " + d.message());
        }
        long errorCount = diagnostics.stream()
            .filter(d -> "error".equals(d.severity())).count();
        long warnCount = diagnostics.stream()
            .filter(d -> "warning".equals(d.severity())).count();
        System.err.println(errorCount + " error(s), " + warnCount + " warning(s)");
    }

    // =========================================================================
    // ModuleResolverImpl
    // =========================================================================

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
            Path importingFile = null;
            for (ModuleInfo info : modules.values()) {
                if (info.modulePath.equals(importingModule)
                        || info.sourcePath.equals(importingModule)) {
                    importingFile = Path.of(info.sourcePath);
                    break;
                }
            }
            if (importingFile == null) {
                importingFile = Path.of(importingModule);
            }

            for (ModuleInfo info : modules.values()) {
                if (isMatch(modulePath, importingFile, info)) {
                    return info.exports != null ? info.exports : Map.of();
                }
            }

            throw new ModuleNotFoundException("Module not found: " + modulePath);
        }

        private boolean isMatch(String importPath, Path fromFile, ModuleInfo info) {
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
                if (info.modulePath.equals(importPath.replace('/', '.'))) return true;
            }

            return false;
        }
    }
}
