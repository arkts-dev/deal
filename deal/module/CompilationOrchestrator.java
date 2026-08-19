package deal.module;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.lua.LuaBackend;
import deal.ir.IrDumper;
import deal.lexer.*;
import deal.parser.*;
import deal.types.Type;
import deal.types.Types;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import deal.diagnostics.DiagnosticCode;

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
    private final boolean dumpIr;
    private final boolean sourceMap;

    /**
     * True when {@code --source-map} was explicitly requested, distinct
     * from {@link #sourceMap} (which is also derived from {@code --dump-ir}
     * because IR hardening enables source maps with it). The JVM
     * source-map warning fires only on the explicit request, never on a
     * {@code --dump-ir}-derived one (ISSUE-0091 rework).
     */
    private final boolean sourceMapExplicit;
    private final Backend backend;
    private final List<Path> moduleRoots;
    private final Path stdlibDir;

    private final Map<String, ModuleInfo> modules = new LinkedHashMap<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private boolean hasErrors = false;

    // Host externals (ISSUE-0082, host-module-abi D5):
    // - externalsDeclarations: raw import path as written → absolute
    //   declaration source path (manifest-relative resolution).  The
    //   declaration is authoritative for that name — on-disk candidates are
    //   not consulted.
    // - externalsModulePaths: declaration source path → dotted module path
    //   (the externals key with '/' → '.' — the typing/class-identity name,
    //   e.g. "host.cfg"; the require path stays the raw key "host/cfg").
    private final Map<String, String> externalsDeclarations;
    private final Map<String, String> externalsModulePaths;

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
        this(entryFile, outputRoot, verbose, false, false, Backend.LUAJIT,
            config, moduleRoots, stdlibDir);
    }

    public CompilationOrchestrator(Path entryFile, Path outputRoot, boolean verbose,
                                    boolean dumpIr,
                                    DealConfig config, List<Path> moduleRoots,
                                    Path stdlibDir) {
        this(entryFile, outputRoot, verbose, dumpIr, false, Backend.LUAJIT,
            config, moduleRoots, stdlibDir);
    }

    public CompilationOrchestrator(Path entryFile, Path outputRoot, boolean verbose,
                                    boolean dumpIr, boolean sourceMap,
                                    DealConfig config, List<Path> moduleRoots,
                                    Path stdlibDir) {
        this(entryFile, outputRoot, verbose, dumpIr, sourceMap, Backend.LUAJIT,
            config, moduleRoots, stdlibDir);
    }

    /**
     * Backend-selection entry point (ISSUE-0091). LuaJIT remains the default:
     * all overloads above delegate with {@link Backend#LUAJIT}.
     *
     * <p>This overload treats {@code sourceMap} as the explicit request
     * (pre-ISSUE-0091 callers pass the single source-map flag).
     *
     * @param backend the code-generation backend ({@code lua}/{@code luajit}
     *                or {@code jvm}) selected by the CLI or {@code deal.json}
     */
    public CompilationOrchestrator(Path entryFile, Path outputRoot, boolean verbose,
                                    boolean dumpIr, boolean sourceMap, Backend backend,
                                    DealConfig config, List<Path> moduleRoots,
                                    Path stdlibDir) {
        this(entryFile, outputRoot, verbose, dumpIr, sourceMap, sourceMap,
            backend, config, moduleRoots, stdlibDir);
    }

    /**
     * Backend-selection entry point (ISSUE-0091). LuaJIT remains the default:
     * all overloads above delegate with {@link Backend#LUAJIT}.
     *
     * @param backend the code-generation backend ({@code lua}/{@code luajit}
     *                or {@code jvm}) selected by the CLI or {@code deal.json}
     * @param sourceMapExplicit true when {@code --source-map} was explicitly
     *                requested; {@code sourceMap} is the effective flag
     *                (also derived from {@code --dump-ir})
     */
    public CompilationOrchestrator(Path entryFile, Path outputRoot, boolean verbose,
                                    boolean dumpIr, boolean sourceMap,
                                    boolean sourceMapExplicit, Backend backend,
                                    DealConfig config, List<Path> moduleRoots,
                                    Path stdlibDir) {
        this.backend = backend;
        this.entryFile = entryFile.toAbsolutePath().normalize();
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
        this.verbose = verbose;
        this.dumpIr = dumpIr;
        this.sourceMap = sourceMap;
        this.sourceMapExplicit = sourceMapExplicit;
        this.moduleRoots = moduleRoots;
        this.stdlibDir = stdlibDir;

        Map<String, String> declarations = new HashMap<>();
        Map<String, String> modulePaths = new HashMap<>();
        if (config != null) {
            Path manifestDir = config.configFile() != null
                ? config.configFile().toAbsolutePath().normalize().getParent()
                : null;
            for (Map.Entry<String, String> entry : config.externals().entrySet()) {
                Path declaration = Path.of(entry.getValue());
                if (manifestDir != null) {
                    declaration = manifestDir.resolve(declaration);
                }
                String sourcePath = declaration.normalize().toString();
                declarations.put(entry.getKey(), sourcePath);
                modulePaths.put(sourcePath, entry.getKey().replace('/', '.'));
            }
        }
        this.externalsDeclarations = Map.copyOf(declarations);
        this.externalsModulePaths = Map.copyOf(modulePaths);
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
        long phaseStart = System.currentTimeMillis();
        String entrySourcePath = entryFile.toString();
        Queue<String> pending = new ArrayDeque<>();
        pending.add(entrySourcePath);

        while (!pending.isEmpty()) {
            String sourcePath = pending.poll();
            if (modules.containsKey(sourcePath)) continue;

            Path file = Path.of(sourcePath);
            if (!Files.exists(file)) {
                error(DiagnosticCode.E2003, "Module not found: " + sourcePath,
                    file.toString(), 1, 1);
                continue;
            }

            long modStart = System.currentTimeMillis();
            boolean isDecl = sourcePath.endsWith(".d.deal");

            String source;
            try {
                source = Files.readString(file);
            } catch (IOException e) {
                error(DiagnosticCode.E2003, "Cannot read module: " + sourcePath + " (" + e.getMessage() + ")",
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

            // Externals-listed host declarations carry the dotted externals
            // key as their typing/class-identity module path (e.g. "host.cfg"
            // for the raw import path "host/cfg").
            String externalsModulePath = externalsModulePaths.get(sourcePath);
            String modulePath = externalsModulePath != null
                ? externalsModulePath : computeModulePath(file);
            ModuleInfo info = new ModuleInfo(sourcePath, modulePath, isDecl);
            info.rawAst = parseResult.program();
            info.parseResult = parseResult;
            modules.put(sourcePath, info);

            long modElapsed = System.currentTimeMillis() - modStart;
            log("  Parsed: " + sourcePath + " (" + modElapsed + "ms)");

            // Process imports for discovery.  Use tryResolveImportPath to
            // avoid emitting E2003 here — we will emit it in Phase 3 where
            // we have precise source locations (or here with the import span).
            for (StatementNode stmt : parseResult.program().statements()) {
                if (stmt instanceof ImportDeclaration imp) {
                    String importPath = imp.modulePath();
                    String resolved = tryResolveImportPath(importPath, file);
                    if (resolved == null) {
                        // Record E2003 with the import statement's span for
                        // accurate error location.
                        error(DiagnosticCode.E2003,
                            "Module not found: '" + importPath
                                + "'. Searched in: " + describeSearchPaths(importPath, file)
                                + externalsDeclarationNote(importPath),
                            imp.span().file(), imp.span().startLine(),
                            imp.span().startColumn());
                    } else if (isUndeclaredExternalHostModule(importPath, resolved)) {
                        // E2009: a bare import whose resolution lands on a
                        // non-stdlib declaration file that is not listed in
                        // deal.json externals (host-module-abi D5(3)).
                        error(DiagnosticCode.E2009,
                            "Import of external host module '" + importPath
                                + "' is not declared in deal.json externals",
                            imp.span().file(), imp.span().startLine(),
                            imp.span().startColumn());
                    } else if (!modules.containsKey(resolved)) {
                        pending.add(resolved);
                    }
                }
            }
        }

        long phaseElapsed = System.currentTimeMillis() - phaseStart;
        if (verbose) {
            System.out.println("  Phase 0 total: " + phaseElapsed + "ms");
        }
    }

    private boolean hasLexErrors(LexResult lex) {
        return lex.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()));
    }

    // =========================================================================
    // Phase 1: Signature extraction
    // =========================================================================

    private void extractSignatures() {
        long phaseStart = System.currentTimeMillis();

        for (ModuleInfo info : modules.values()) {
            long modStart = System.currentTimeMillis();

            // Build import alias → module path mapping for qualified type
            // resolution in export signatures (e.g., V.Vec → "cc_class".Vec).
            Map<String, String> importAliasMap = new HashMap<>();
            if (info.rawAst != null) {
                for (StatementNode stmt : info.rawAst.statements()) {
                    if (stmt instanceof ImportDeclaration imp) {
                        String resolvedSource = resolveImportPath(
                            imp.modulePath(), Path.of(info.sourcePath));
                        if (resolvedSource != null) {
                            ModuleInfo imported = modules.get(resolvedSource);
                            if (imported != null) {
                                importAliasMap.put(imp.alias(), imported.modulePath);
                            }
                        }
                    }
                }
            }

            ExportExtractor extractor = new ExportExtractor(info.modulePath,
                info.isDeclarationFile);
            extractor.setImportModulePaths(importAliasMap);
            info.exports = extractor.extract(info.rawAst);
            diagnostics.addAll(extractor.diagnostics());
            if (extractor.diagnostics().stream().anyMatch(
                    d -> "error".equals(d.severity()))) {
                hasErrors = true;
            }

            // IR dump for declaration files
            if (dumpIr && info.isDeclarationFile && info.rawAst != null) {
                try {
                    String irText = IrDumper.dump(info.rawAst, info.symbolTable, info.modulePath);
                    if (!irText.isEmpty()) {
                        writeIrDump(info.modulePath, irText);
                    }
                } catch (Exception e) {
                    error(DiagnosticCode.E6001, "IR dump failed for " + info.sourcePath
                        + ": " + e.getMessage(), info.sourcePath, 1, 1);
                }
            }

            long modElapsed = System.currentTimeMillis() - modStart;
            log("  Signatures extracted: " + info.sourcePath + " (" + modElapsed + "ms)");
        }

        long phaseElapsed = System.currentTimeMillis() - phaseStart;
        if (verbose) {
            System.out.println("  Phase 1 total: " + phaseElapsed + "ms");
        }
    }

    // =========================================================================
    // Phase 2: Dependency graph and topological ordering
    // ... (unchanged)
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
        // Find the first cycle
        List<String> cycle = new ArrayList<>();
        String start = remaining.iterator().next();
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        findCycle(deps, start, visited, stack, cycle);
        if (cycle.isEmpty()) {
            cycle.addAll(remaining);
        }

        // Check if the first cycle has runtime dependencies
        if (!isDeclarationOnlyCycle(cycle, deps)) {
            StringBuilder cyclePath = new StringBuilder();
            for (int i = 0; i < cycle.size(); i++) {
                if (i > 0) cyclePath.append(" -> ");
                cyclePath.append(cycle.get(i));
            }
            error(DiagnosticCode.E2005, "Circular import with runtime dependency: " + cyclePath,
                cycle.get(0), 1, 1);
            return null;
        }

        // First cycle is declaration-only. Now check non-cycle modules
        // for additional cycles (multiple disconnected SCCs).
        Set<String> allCycleNodes = new LinkedHashSet<>(cycle);
        Set<String> nonCycle = new LinkedHashSet<>(remaining);
        nonCycle.removeAll(allCycleNodes);

        // Iteratively find and check additional cycles in the non-cycle set
        List<String> additionalCycle;
        while ((additionalCycle = findCycleInSet(deps, nonCycle)) != null
                && !additionalCycle.isEmpty()) {
            if (!isDeclarationOnlyCycle(additionalCycle, deps)) {
                StringBuilder cyclePath = new StringBuilder();
                for (int i = 0; i < additionalCycle.size(); i++) {
                    if (i > 0) cyclePath.append(" -> ");
                    cyclePath.append(additionalCycle.get(i));
                }
                error(DiagnosticCode.E2005, "Circular import with runtime dependency: " + cyclePath,
                    additionalCycle.get(0), 1, 1);
                return null;
            }
            allCycleNodes.addAll(additionalCycle);
            nonCycle.removeAll(additionalCycle);
        }

        // All cycles are declaration-only. Build the order.
        List<String> order = new ArrayList<>();

        // Step 1: non-cycle modules with all deps already in order
        boolean progress;
        do {
            progress = false;
            for (Iterator<String> it = nonCycle.iterator(); it.hasNext(); ) {
                String src = it.next();
                Set<String> imports = deps.get(src);
                if (order.containsAll(imports)) {
                    order.add(src);
                    it.remove();
                    progress = true;
                }
            }
        } while (progress);

        // Step 2: all cycle modules
        order.addAll(allCycleNodes);

        // Step 3: non-cycle modules whose deps are now satisfied
        do {
            progress = false;
            for (Iterator<String> it = nonCycle.iterator(); it.hasNext(); ) {
                String src = it.next();
                Set<String> imports = deps.get(src);
                if (order.containsAll(imports)) {
                    order.add(src);
                    it.remove();
                    progress = true;
                }
            }
        } while (progress);

        // Step 4: any remaining modules (deps not fully satisfied)
        if (!nonCycle.isEmpty()) {
            for (String src : nonCycle) {
                order.add(src);
            }
        }

        return order;
    }

    /**
     * Finds a cycle in the given set of modules. Returns the cycle nodes
     * (with duplicates removed via LinkedHashSet), or an empty list if
     * no cycle is found.
     */
    private List<String> findCycleInSet(Map<String, Set<String>> deps,
                                         Set<String> candidates) {
        if (candidates.isEmpty()) return null;

        for (String start : candidates) {
            List<String> cycle = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            Deque<String> stack = new ArrayDeque<>();
            if (findCycleRestricted(deps, start, candidates, visited, stack, cycle)) {
                List<String> unique = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (String s : cycle) {
                    if (seen.add(s)) {
                        unique.add(s);
                    }
                }
                return unique;
            }
        }
        return new ArrayList<>();
    }

    private boolean findCycleRestricted(Map<String, Set<String>> deps,
                                         String current, Set<String> allowed,
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
                if (!allowed.contains(imp)) continue;
                if (findCycleRestricted(deps, imp, allowed, visited, stack, cycle))
                    return true;
            }
        }

        stack.removeLast();
        return false;
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

    /**
     * Checks whether a module uses a given import alias at runtime
     * (i.e., in top-level executable statements, not just in type positions).
     *
     * <p><b>Known limitation (v0.6):</b> Indirect runtime dependencies are not
     * detected.  If a module defines a function that uses the cyclic import and
     * then calls that function at the top level, the cycle will be incorrectly
     * classified as declaration-only:
     * <pre>{@code
     *   import * as B from "./b"
     *   function helper(): int { return B.getValue(); }
     *   let x: int = helper();  // indirect runtime use of B — not detected
     * }</pre>
     * A full fix requires data-flow analysis, planned for a future release.
     */
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
                if (fs.init().isPresent()) {
                    ForInit init = fs.init().get();
                    if (init instanceof ForInit.VarDecl vd) {
                        if (exprReferencesImport(vd.decl().initializer(), alias)) yield true;
                    } else if (init instanceof ForInit.AssignExpr ie) {
                        AssignmentExpr ae = ie.expr();
                        if (exprReferencesImport(ae.target(), alias)) yield true;
                        if (exprReferencesImport(ae.value(), alias)) yield true;
                    }
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
            case ForOfStatement fos -> {
                if (exprReferencesImport(fos.iterable(), alias)) yield true;
                if (hasRuntimeImportUsage(fos.body(), alias)) yield true;
                yield false;
            }
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
            case AwaitExpression await -> {
                yield exprReferencesImport(await.callee(), alias);
            }
            case TemplateLiteralExpr tl -> {
                for (ExpressionNode part : tl.parts()) {
                    if (exprReferencesImport(part, alias)) yield true;
                }
                yield false;
            }
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
        long phaseStart = System.currentTimeMillis();
        ModuleResolverImpl resolver = new ModuleResolverImpl(modules, diagnostics);

        for (String sourcePath : order) {
            ModuleInfo info = modules.get(sourcePath);
            if (info.isDeclarationFile) continue;

            long modStart = System.currentTimeMillis();

            NameResolver nr = new NameResolver(info.modulePath, resolver);
            info.nameResolver = nr;
            SymbolTable symTable = nr.resolve(info.rawAst);
            info.symbolTable = symTable;

            // F1 fix: Rebuild exports with correctly resolved types from name
            // resolution.  The ExportExtractor in Phase 1 uses import aliases
            // for qualified types (e.g., "V" instead of "cc_class"), which are
            // local to the exporting module and meaningless to downstream
            // importers.  After name resolution, we know the correct module
            // paths and can produce accurate export types.
            Map<String, Type> correctedExports = new LinkedHashMap<>();
            for (StatementNode stmt : info.rawAst.statements()) {
                if (stmt instanceof ExportDeclaration exp) {
                    String exportName = null;
                    if (exp.declaration() instanceof FunctionDeclaration fd) {
                        exportName = fd.name();
                    } else if (exp.declaration() instanceof ClassDeclaration cd) {
                        exportName = cd.name();
                    }
                    if (exportName != null) {
                        Symbol sym = symTable.resolve(exportName);
                        if (sym != null) {
                            if (sym instanceof Symbol.FunctionSymbol fs) {
                                correctedExports.put(exportName, fs.funcType());
                            } else if (sym instanceof Symbol.ClassSymbol cs) {
                                correctedExports.put(exportName,
                                    Types.classType(cs.name(), cs.modulePath()));
                            }
                        }
                    }
                }
            }

            // D6: Append synthetic C$fromJson and C$toJson exports for @jsonable
            // classes.  These are added after the symbol-table-based correction
            // so that synthetic entries are present in the final export map
            // consumed by downstream modules.
            for (StatementNode stmt : info.rawAst.statements()) {
                if (stmt instanceof ExportDeclaration exp
                        && exp.declaration() instanceof ClassDeclaration cd
                        && cd.isJsonable()) {
                    Symbol clsSym = symTable.resolve(cd.name());
                    if (clsSym instanceof Symbol.ClassSymbol cs) {
                        Type clsType = Types.classType(cs.name(), cs.modulePath());

                        // C$fromJson: (string) -> C | null
                        Type.Func fromJsonType = new Type.Func(
                            List.of(Type.String.INSTANCE), Optional.empty(),
                            Types.nullable(clsType));
                        correctedExports.put(cd.name() + "$fromJson", fromJsonType);

                        // C$toJson: (C) -> string
                        Type.Func toJsonType = new Type.Func(
                            List.of(clsType), Optional.empty(),
                            Type.String.INSTANCE);
                        correctedExports.put(cd.name() + "$toJson", toJsonType);
                    }
                }
            }

            info.exports = correctedExports;

            diagnostics.addAll(nr.diagnostics());
            if (hasNameErrors(nr.diagnostics())) {
                hasErrors = true;
                long modElapsed = System.currentTimeMillis() - modStart;
                log("  Checked: " + sourcePath + " (name resolution error, " + modElapsed + "ms)");
                continue;
            }

            CheckResult result = TypeChecker.check(info.modulePath, symTable,
                nr, info.rawAst);
            info.checkResult = result;
            diagnostics.addAll(result.diagnostics());
            if (result.hasErrors()) {
                hasErrors = true;
            }

            // IR dump for full modules (after type checking, before codegen)
            if (dumpIr && !result.hasErrors() && info.rawAst != null) {
                try {
                    String irText = IrDumper.dump(info.rawAst, result, info.modulePath);
                    if (!irText.isEmpty()) {
                        writeIrDump(info.modulePath, irText);
                    }
                } catch (Exception e) {
                    error(DiagnosticCode.E6001, "IR dump failed for " + info.sourcePath
                        + ": " + e.getMessage(), info.sourcePath, 1, 1);
                }
            }

            long modElapsed = System.currentTimeMillis() - modStart;
            log("  Checked: " + sourcePath + " (" + modElapsed + "ms)");
        }

        long phaseElapsed = System.currentTimeMillis() - phaseStart;
        if (verbose) {
            System.out.println("  Phase 3 total: " + phaseElapsed + "ms");
        }
    }

    private boolean hasNameErrors(List<Diagnostic> diags) {
        return diags.stream().anyMatch(d -> "error".equals(d.severity()));
    }

    // =========================================================================
    // Phase 4: Code generation
    // =========================================================================

    private void codegenAll() throws IOException {
        long phaseStart = System.currentTimeMillis();
        Files.createDirectories(outputRoot);

        if (backend == Backend.JVM) {
            // JVM use site (ISSUE-0091): emit one .java module class per
            // module. Import resolution and the Lua runtime copies are
            // LuaJIT-specific and skipped here.
            codegenAllJvm();
        } else {
            // Lua use site: the existing LuaJIT emitter, unchanged.
            for (ModuleInfo info : modules.values()) {
                if (info.isDeclarationFile) continue;
                codegenLuaModule(info);
            }
            copyRuntimeLibrary();
            copyStdlibModules();
        }

        long phaseElapsed = System.currentTimeMillis() - phaseStart;
        if (verbose) {
            System.out.println("  Phase 4 total: " + phaseElapsed + "ms");
        }
    }

    /**
     * Lua use site: emits the module via {@link LuaBackend}, with the
     * resolved import map and host-module declarations (unchanged behavior;
     * ISSUE-0091 moved the pre-existing body here verbatim).
     */
    private void codegenLuaModule(ModuleInfo info) throws IOException {
        long modStart = System.currentTimeMillis();

        Map<String, String> importResolutions = new HashMap<>();
        Map<String, Map<String, Type>> hostModules = new HashMap<>();
        for (StatementNode stmt : info.rawAst.statements()) {
            if (stmt instanceof ImportDeclaration imp) {
                String resolvedSource = resolveImportPath(imp.modulePath(),
                    Path.of(info.sourcePath));
                if (resolvedSource != null) {
                    ModuleInfo imported = modules.get(resolvedSource);
                    if (imported != null) {
                        importResolutions.put(imp.modulePath(),
                            imported.modulePath);
                        // Host modules (ISSUE-0082, host-module-abi D5):
                        // declaration files that are not spec stdlib
                        // modules load through __rt.load_host with the
                        // raw import path verbatim as the require key.
                        if (imported.isDeclarationFile
                                && !isSpecStdlibModuleInfo(imported)) {
                            hostModules.put(imp.modulePath(),
                                imported.exports != null
                                    ? imported.exports : Map.of());
                        }
                    }
                }
            }
        }

        String filePath = info.modulePath.replace('.', '/') + ".lua";
        Path outputPath = outputRoot.resolve(filePath);
        Files.createDirectories(outputPath.getParent());

        if (sourceMap) {
            // Use generateToFile with emitSourceMap=true and the resolved
            // import map to produce both the .lua file and the .deal.map.json
            // sidecar.  importResolutions are required so that multi-module
            // projects compile correctly when --source-map is active.
            // info.modulePath is the same value seeded into NameResolver,
            // so emitted class identity tags stay byte-identical to the
            // checker's descriptors (runtime-class-identity D2(0)).
            LuaBackend.generateToFile(info.rawAst, info.checkResult,
                info.sourcePath, info.modulePath, outputRoot, outputPath, true,
                importResolutions, hostModules);
        } else {
            String luaSource = LuaBackend.generateWithImports(
                info.rawAst, info.checkResult, info.sourcePath,
                info.modulePath, importResolutions, hostModules);
            Files.writeString(outputPath, luaSource);
        }

        long modElapsed = System.currentTimeMillis() - modStart;
        log("  Generated: " + outputPath + " (" + modElapsed + "ms)");
    }

    /**
     * JVM use site (ISSUE-0091, ISSUE-0096): emits one {@code
     * <ClassName>.java} module class per module, with the per-module import
     * resolution map (raw import path → imported module path) built exactly
     * like the LuaJIT use site — only non-declaration modules are entries,
     * so an import of a declaration/host module reaches the backend
     * unresolved. Backend diagnostics (E6000 for out-of-slice constructs —
     * including imports other than {@code std/console} and compiled project
     * modules, at the import statement itself) fail the compilation with
     * the standard diagnostic report; no artifact is written for a module
     * the backend rejects. Class names are derived from the full module
     * path ({@code app/main} → {@code AppMain}), and a collision between
     * two modules mapping to the same class name (e.g. a case-only
     * difference) is an E6000 error — never a silent artifact overwrite.
     */
    private void codegenAllJvm() throws IOException {
        if (sourceMapExplicit) {
            // Source-map sidecars (.deal.map.json) are produced only by the
            // LuaJIT emitter; surface that to the CLI user instead of
            // silently producing no sidecars (ISSUE-0091 rework round 3).
            // Fired only when --source-map was explicitly requested: a
            // --dump-ir-derived sourceMap flag (IR hardening enables source
            // maps with dumps) must not print the warning.
            System.err.println("Warning: --source-map produces no source-map "
                + "sidecars with the JVM backend (source maps are "
                + "LuaJIT-only)");
        }
        // Pass 1: generate every module and merge diagnostics. Rejected
        // modules write no artifact.
        List<ModuleInfo> cleanModules = new ArrayList<>();
        Map<ModuleInfo, JvmBackend.JvmCodegenResult> results = new LinkedHashMap<>();
        for (ModuleInfo info : modules.values()) {
            if (info.isDeclarationFile) continue;
            // Import resolutions (ISSUE-0096): raw import path → module
            // path of the imported COMPILED module. Declaration files are
            // skipped above and never become entries, so an import of a
            // declaration/host module reaches the backend unresolved and is
            // rejected with E6000 at the import statement — declaration and
            // host modules stay out of the JVM slice (host ABI is deferred).
            // ISSUE-0109: the same discovery pass collects each imported
            // module's class declarations (module path → name →
            // declaration) so the backend can emit imported-class types,
            // construction, and nominal checks against the declaring
            // module's generated nested classes.
            Map<String, String> importResolutions = new HashMap<>();
            Map<String, Map<String, ClassDeclaration>> importedClasses =
                new HashMap<>();
            for (StatementNode stmt : info.rawAst.statements()) {
                if (stmt instanceof ImportDeclaration imp) {
                    String resolvedSource = resolveImportPath(imp.modulePath(),
                        Path.of(info.sourcePath));
                    if (resolvedSource != null) {
                        ModuleInfo imported = modules.get(resolvedSource);
                        if (imported != null && !imported.isDeclarationFile) {
                            importResolutions.put(imp.modulePath(),
                                imported.modulePath);
                            Map<String, ClassDeclaration> classes =
                                new LinkedHashMap<>();
                            for (StatementNode importedStmt
                                    : imported.rawAst.statements()) {
                                ClassDeclaration cd = null;
                                if (importedStmt instanceof ClassDeclaration c) {
                                    cd = c;
                                } else if (importedStmt instanceof ExportDeclaration ed
                                        && ed.declaration() instanceof ClassDeclaration c) {
                                    cd = c;
                                }
                                if (cd != null) {
                                    classes.putIfAbsent(cd.name(), cd);
                                }
                            }
                            importedClasses.put(imported.modulePath, classes);
                        }
                    }
                }
            }
            JvmBackend.JvmCodegenResult res = JvmBackend.generate(
                info.rawAst, info.checkResult, info.sourcePath, info.modulePath,
                importResolutions, importedClasses);
            for (Diagnostic d : res.diagnostics()) {
                diagnostics.add(d);
                hasErrors = true;
            }
            if (res.hasErrors()) {
                log("  JVM backend rejected " + info.modulePath + ": "
                    + res.diagnostics());
                continue;
            }
            cleanModules.add(info);
            results.put(info, res);
        }

        // Pass 2: write artifacts for clean modules, rejecting class-name
        // collisions instead of silently overwriting an earlier module's
        // artifact.
        Map<String, String> classOwners = new LinkedHashMap<>();
        for (ModuleInfo info : cleanModules) {
            JvmBackend.JvmCodegenResult res = results.get(info);
            String className = res.className();
            String previousOwner = classOwners.putIfAbsent(className, info.modulePath);
            if (previousOwner != null) {
                error(DiagnosticCode.E6000,
                    "JVM backend: modules '" + previousOwner + "' and '"
                        + info.modulePath + "' both derive the class name '"
                        + className + "' (rename one module)",
                    info.sourcePath, 1, 1);
                continue;
            }
            Path outputPath = outputRoot.resolve(className + ".java");
            Files.createDirectories(outputPath.getParent());
            Files.writeString(outputPath, res.source());
            log("  Generated: " + outputPath);
        }
    }

    /**
     * True when the module is a spec-listed stdlib declaration module
     * (filesystem-discovered under the stdlib directory or registered as a
     * classpath resource).  Stdlib imports stay on the trusted raw-require
     * path (ISSUE-0082, host-module-abi D5(5)).
     */
    private boolean isSpecStdlibModuleInfo(ModuleInfo info) {
        if (info.sourcePath.startsWith("classpath:")) {
            return true;
        }
        return StdlibModuleResolver.isSpecStdlibModule(
            info.modulePath.replace('.', '/'));
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

        error(DiagnosticCode.E6000, "Runtime library not found: deal/runtime.lua", "", 1, 1);
    }

    /**
     * Copies the spec-listed stdlib .lua implementation files to the output.
     * The module list is derived from the 6 spec-listed stdlib modules.
     */
    private void copyStdlibModules() throws IOException {
        for (String stdlibModule : StdlibModuleResolver.SPEC_STDLIB_MODULES) {
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
            }

            // Fallback: try classpath resource for bundled stdlib .lua files
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
    // IR dump helper
    // =========================================================================

    /**
     * Writes an IR dump string to the output directory.
     * The file is placed at {@code <outputRoot>/<module-path>.ir.txt}.
     */
    private void writeIrDump(String modulePath, String irText) throws IOException {
        String filePath = modulePath.replace('.', '/') + ".ir.txt";
        Path outputPath = outputRoot.resolve(filePath);
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, irText);
        log("  IR dumped: " + outputPath);
    }

    // =========================================================================
    // Import resolution
    // =========================================================================

    /**
     * Resolves an import path to a source file.  Emits E2003 with the
     * given file location (defaulting to 1:1) if resolution fails.
     *
     * @return the resolved source path, or {@code null} if not found
     */
    public String resolveImportPath(String importPath, Path fromFile) {
        return resolveImportPath(importPath, fromFile, fromFile.toString(), 1, 1);
    }

    /**
     * Resolves an import path to a source file, using the given location
     * for any E2003 diagnostic.
     */
    private String resolveImportPath(String importPath, Path fromFile,
                                      String errorFile, int errorLine, int errorCol) {
        String resolved = tryResolveImportPath(importPath, fromFile);
        if (resolved == null) {
            StringBuilder msg = new StringBuilder("Module not found: '" + importPath
                + "'. Attempted: ");
            List<String> candidates = buildCandidates(importPath, fromFile);
            for (int i = 0; i < candidates.size(); i++) {
                if (i > 0) msg.append(", ");
                msg.append(candidates.get(i));
            }
            msg.append(externalsDeclarationNote(importPath));
            error(DiagnosticCode.E2003, msg.toString(), errorFile, errorLine, errorCol);
        }
        return resolved;
    }

    /**
     * Tries to resolve an import path without emitting diagnostics.
     * Returns the resolved source file path, or {@code null} if not found.
     *
     * <p>For bare imports that look like stdlib module paths (e.g.,
     * {@code "std/io"}), only spec-listed stdlib modules are resolved.
     * Non-spec modules like {@code std/io} and {@code std/coroutine} are
     * rejected with {@code null}, resulting in an E2003 diagnostic.
     */
    private String tryResolveImportPath(String importPath, Path fromFile) {
        // Externals-listed bare imports (ISSUE-0082, host-module-abi D5):
        // the manifest declaration is authoritative for that name — on-disk
        // candidates are not consulted.  A missing declaration file yields
        // null (→ E2003 at the import site).
        String externalsDeclaration = externalsDeclarations.get(importPath);
        if (externalsDeclaration != null) {
            if (Files.exists(Path.of(externalsDeclaration))) {
                return externalsDeclaration;
            }
            return null;
        }

        // Reject non-spec stdlib modules at the discovery/import-resolution level.
        // Bare imports that start with "std/" but are not in the spec list
        // should not resolve (they are not valid stdlib modules).
        if (importPath.startsWith("std/")
                && !importPath.startsWith("./")
                && !importPath.startsWith("../")) {
            if (!StdlibModuleResolver.isSpecStdlibModule(importPath)) {
                return null;
            }
        }

        List<String> candidates = buildCandidates(importPath, fromFile);

        // Try filesystem candidates
        for (String candidate : candidates) {
            if (Files.exists(Path.of(candidate))) {
                return candidate;
            }
        }

        // Fallback: try JAR/classpath resources for bare imports
        if (!importPath.startsWith("./") && !importPath.startsWith("../")) {
            String resourcePath = importPath + ".d.deal";
            InputStream stream = getClass().getClassLoader()
                .getResourceAsStream(resourcePath);
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) {}
                return registerResourceModule(resourcePath, importPath);
            }
        }

        return null;
    }

    /**
     * True when a bare import resolved to a non-stdlib declaration file that
     * is not listed in deal.json externals (ISSUE-0082, host-module-abi
     * D5(3)-(4)): bare host modules must be declared in externals, while
     * relative ({@code ./}, {@code ../}) declaration imports and the stdlib
     * trusted path are unchanged.
     */
    private boolean isUndeclaredExternalHostModule(String importPath, String resolved) {
        if (importPath.startsWith("./") || importPath.startsWith("../")) {
            return false;
        }
        if (importPath.startsWith("std/")) {
            return false;
        }
        if (resolved.startsWith("classpath:")) {
            return false;
        }
        if (externalsDeclarations.containsKey(importPath)) {
            return false;
        }
        return resolved.endsWith(".d.deal");
    }

    private List<String> buildCandidates(String importPath, Path fromFile) {
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
        return candidates;
    }

    /**
     * Describes the search paths used for a bare import, for use in
     * E2003 diagnostic messages.
     */
    private String describeSearchPaths(String importPath, Path fromFile) {
        List<String> candidates = buildCandidates(importPath, fromFile);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(candidates.get(i));
        }
        return sb.toString();
    }

    /**
     * Names the authoritative externals declaration path for an import
     * listed in deal.json externals, for E2003 messages.  The manifest
     * declaration is authoritative for that name and on-disk candidates
     * are not consulted (host-module-abi D5(2)), so a missing declaration
     * file must point the user at the configured path.  Returns an empty
     * string for imports without an externals entry.
     */
    private String externalsDeclarationNote(String importPath) {
        String declaration = externalsDeclarations.get(importPath);
        return declaration != null
            ? " (externals declaration: " + declaration + ")" : "";
    }

    private String registerResourceModule(String resourcePath, String importPath) {
        String syntheticPath = "classpath:" + resourcePath;
        if (modules.containsKey(syntheticPath)) return syntheticPath;

        try {
            InputStream stream = getClass().getClassLoader()
                .getResourceAsStream(resourcePath);
            if (stream == null) return null;

            String source = new String(stream.readAllBytes());
            stream.close();

            String modulePath = importPath.replace('/', '.');

            LexResult lex = new Lexer(source, syntheticPath).tokenize();
            if (hasLexErrors(lex)) {
                diagnostics.addAll(lex.diagnostics());
                hasErrors = true;
                return null;
            }

            Parser parser = new Parser(lex.tokens(), syntheticPath);
            ParseResult parseResult = parser.parse();
            diagnostics.addAll(parseResult.diagnostics());
            if (parseResult.hasErrors()) {
                hasErrors = true;
            }

            ModuleInfo info = new ModuleInfo(syntheticPath, modulePath, true);
            info.rawAst = parseResult.program();
            info.parseResult = parseResult;
            modules.put(syntheticPath, info);

            return syntheticPath;
        } catch (IOException e) {
            return null;
        }
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

        // Fallback: use relative path from current working directory.
        // If the source is under the CWD, use the relative path to avoid
        // collisions from filename-only resolution.
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (absFile.startsWith(cwd)) {
            Path relative = cwd.relativize(absFile);
            String path = relative.toString();
            if (path.endsWith(".d.deal")) {
                path = path.substring(0, path.length() - ".d.deal".length());
            } else if (path.endsWith(".deal")) {
                path = path.substring(0, path.length() - ".deal".length());
            }
            return path.replace('/', '.').replace('\\', '.');
        }

        // Absolute fallback: just the filename (used only when the file is
        // outside both module roots and CWD — typically a test scenario).
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

    private void error(DiagnosticCode code, String message, String file, int line, int col) {
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
                if (info.sourcePath.startsWith("classpath:")) {
                    if (info.modulePath.equals(importPath.replace('/', '.'))) return true;
                }
            }

            return false;
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                                                      String modulePath,
                                                      String importingModule)
                throws ModuleNotFoundException {
            for (ModuleInfo info : modules.values()) {
                if (info.modulePath.equals(modulePath)) {
                    if (info.symbolTable != null) {
                        Symbol sym = info.symbolTable.resolve(className);
                        if (sym instanceof Symbol.ClassSymbol cs) return cs;
                    }
                    return null;
                }
            }
            return null;
        }
    }
}
