package deal.module;

import deal.ast.*;
import deal.lexer.Diagnostic;
import deal.types.Type;
import deal.types.Types;

import java.util.*;
import deal.diagnostics.DiagnosticCode;

/**
 * Extracts export signatures from a parsed DEAL module AST.
 *
 * <p>This is a lightweight pass that runs before full type checking.
 * It hoists class and function declarations and determines their types
 * from annotations only (no type inference). This allows building the
 * dependency graph for topological ordering.
 *
 * <p>For {@code .d.deal} files, executable statements produce E7001 errors.</p>
 */
public final class ExportExtractor {

    private final String modulePath;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Map<String, Type> exports = new LinkedHashMap<>();
    private boolean isDeclarationFile;

    /**
     * Maps import aliases (local names like "V") to the resolved module path
     * (like "cc_class"). Populated before extraction for correct qualified-type
     * resolution in exported function signatures.
     */
    private Map<String, String> importModulePaths = Map.of();

    public ExportExtractor(String modulePath, boolean isDeclarationFile) {
        this.modulePath = modulePath;
        this.isDeclarationFile = isDeclarationFile;
    }

    /**
     * Sets the mapping from import alias to resolved module path.
     * Must be called before {@link #extract(ProgramNode)} for correct
     * resolution of qualified types like {@code V.Vec}.
     */
    public void setImportModulePaths(Map<String, String> importModulePaths) {
        this.importModulePaths = Map.copyOf(importModulePaths);
    }

    /**
     * Extract exports from a parsed program.
     *
     * @return map from export name to its type
     */
    public Map<String, Type> extract(ProgramNode program) {
        exports.clear();
        diagnostics.clear();

        // First pass: collect class declarations (needed for type resolution)
        Map<String, List<ClassField>> classMap = new HashMap<>();
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ClassDeclaration cd) {
                classMap.put(cd.name(), cd.fields());
            } else if (stmt instanceof ExportDeclaration exp
                    && exp.declaration() instanceof ClassDeclaration cd) {
                classMap.put(cd.name(), cd.fields());
            }
        }

        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ExportDeclaration exp) {
                extractExport(exp, classMap);
            } else if (isDeclarationFile && !isAllowedDeclarationFileStmt(stmt)) {
                diagnostics.add(Diagnostic.error(DiagnosticCode.E7001,
                    "Declaration files may only contain export declarations and class declarations",
                    stmt.span().file(), stmt.span().startLine(),
                    stmt.span().startColumn()));
            }
        }

        // NOTE: Non-exported classes in .d.deal files are NOT automatically
        // added to the export map.  Only explicitly exported declarations
        // form the module's public API.  Internal helper classes that are
        // needed by exported function signatures must be explicitly exported
        // or their types must appear via structural interface types.

        // D6: Add synthetic C$fromJson and C$toJson exports for @jsonable classes.
        // These ensure cross-module NameResolver.isFunctionExportedFromModule()
        // queries succeed during Phase 3, particularly when modules are in
        // declaration-only import cycles where the target module's Phase 3
        // correction may not have run yet.
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ExportDeclaration exp
                    && exp.declaration() instanceof ClassDeclaration cd
                    && cd.isJsonable()) {
                Type clsType = Types.classType(cd.name(), modulePath);

                // C$fromJson: (string) -> C | null
                Type.Func fromJsonType = new Type.Func(
                    List.of(Type.String.INSTANCE), Optional.empty(),
                    Types.nullable(clsType));
                exports.put(cd.name() + "$fromJson", fromJsonType);

                // C$toJson: (C) -> string
                Type.Func toJsonType = new Type.Func(
                    List.of(clsType), Optional.empty(),
                    Type.String.INSTANCE);
                exports.put(cd.name() + "$toJson", toJsonType);
            }
        }

        return Collections.unmodifiableMap(exports);
    }

    private boolean isAllowedDeclarationFileStmt(StatementNode stmt) {
        return stmt instanceof ExportDeclaration
            || stmt instanceof ClassDeclaration
            || stmt instanceof ImportDeclaration;
    }

    private void extractExport(ExportDeclaration exp,
                               Map<String, List<ClassField>> classMap) {
        StatementNode decl = exp.declaration();
        switch (decl) {
            case FunctionDeclaration fd -> {
                // In .d.deal files, exported functions must not have bodies
                if (isDeclarationFile && fd.body() != null
                        && fd.body().statements() != null
                        && !fd.body().statements().isEmpty()) {
                    diagnostics.add(Diagnostic.error(DiagnosticCode.E7001,
                        "Exported function '" + fd.name()
                            + "' in declaration file must not have a body",
                        fd.span().file(), fd.span().startLine(),
                        fd.span().startColumn()));
                }
                Type funcType = resolveFuncType(fd, classMap);
                exports.put(fd.name(), funcType);
            }
            case ClassDeclaration cd -> {
                exports.put(cd.name(), Types.classType(cd.name(), modulePath));
            }
            default -> {}
        }
    }

    private Type resolveFuncType(FunctionDeclaration fd,
                                  Map<String, List<ClassField>> classMap) {
        Type retType = resolveTypeNodeSimple(fd.returnType(), classMap);
        List<Type> paramTypes = new ArrayList<>();
        for (Parameter p : fd.params()) {
            paramTypes.add(resolveTypeNodeSimple(p.type(), classMap));
        }
        Optional<Type.Array> restType = fd.restParam()
            .map(rp -> (Type.Array) resolveTypeNodeSimple(rp.type(), classMap));

        return new Type.Func(paramTypes, restType, retType, fd.isAsync());
    }

    /**
     * Simple type node resolution that handles primitives, arrays, nullables,
     * function types, locally-declared classes, and qualified types (with
     * import alias mapping when available). Does not resolve imported
     * types (those need the module resolver).
     */
    private Type resolveTypeNodeSimple(TypeNode tn,
                                        Map<String, List<ClassField>> classMap) {
        return switch (tn) {
            case NamedType nt -> resolveNamedSimple(nt.name(), classMap);
            case QualifiedType qt -> {
                // Resolve the import alias to the actual module path if possible.
                // If not available (e.g., before import resolution), fall back
                // to the alias itself.  The export map will be updated after
                // name resolution in Phase 3 with the correct types.
                String resolvedModulePath = importModulePaths.getOrDefault(
                    qt.moduleName(), qt.moduleName());
                yield Types.classType(qt.typeName(), resolvedModulePath);
            }
            case deal.ast.ArrayType at -> {
                Type elem = resolveTypeNodeSimple(at.elementType(), classMap);
                if (elem == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                yield new Type.Array(elem);
            }
            case deal.ast.NullableType nt2 -> {
                Type inner = resolveTypeNodeSimple(nt2.innerType(), classMap);
                if (inner == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                try {
                    yield Types.nullable(inner);
                } catch (IllegalArgumentException e) {
                    yield Type.Error.INSTANCE;
                }
            }
            case deal.ast.FunctionType ft -> {
                List<Type> pts = new ArrayList<>();
                for (FunctionTypeParam ftp : ft.params()) {
                    pts.add(resolveTypeNodeSimple(ftp.type(), classMap));
                }
                Type ret = resolveTypeNodeSimple(ft.returnType(), classMap);
                Optional<Type.Array> rest = ft.rest()
                    .map(r -> (Type.Array) resolveTypeNodeSimple(r.type(), classMap));
                yield new Type.Func(pts, rest, ret, ft.isAsync());
            }
        };
    }

    private Type resolveNamedSimple(String name,
                                     Map<String, List<ClassField>> classMap) {
        return switch (name) {
            case "null" -> Type.Null.INSTANCE;
            case "boolean" -> Type.Boolean.INSTANCE;
            case "int" -> Type.Int.INSTANCE;
            case "number" -> Type.Number.INSTANCE;
            case "string" -> Type.String.INSTANCE;
            case "table" -> Type.Table.INSTANCE;
            case "Error" -> Types.classType("Error", "");
            default -> {
                if (classMap.containsKey(name)) {
                    yield Types.classType(name, modulePath);
                }
                // Unknown type — will be resolved during type checking
                yield Types.classType(name, "");
            }
        };
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }
}
