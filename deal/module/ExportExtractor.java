package deal.module;

import deal.ast.*;
import deal.diagnostics.CompilerDiagnostic;
import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.types.Type;
import deal.types.Types;

import java.util.function.Function;

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
    private final List<CompilerDiagnostic> diagnostics = new ArrayList<>();
    private final Map<String, Type> exports = new LinkedHashMap<>();
    private boolean isDeclarationFile;

    /**
     * Class declarations by name (the first pass of
     * {@link #extract(ProgramNode)}), retained so the post-extraction
     * {@link #resolveFieldType(TypeNode)} surface can resolve bare
     * class names in the module's own declaration context.
     */
    private Map<String, List<ClassField>> classMap = Map.of();

    /**
     * Maps import aliases (local names like "V") to the resolved module path
     * (like "cc_class"). Populated before extraction for correct qualified-type
     * resolution in exported function signatures.
     */
    private Map<String, String> importModulePaths = Map.of();

    /**
     * Legacy convenience constructor: the single-module standalone
     * classification adapter (module path &rarr; project module with the
     * path as its configured root text; the empty path &rarr; the
     * intrinsic builtin module).  Identity-aware callers pass the
     * compilation's module-path classification.
     */
    public ExportExtractor(String modulePath, boolean isDeclarationFile) {
        this(modulePath, isDeclarationFile, standaloneClassification(modulePath));
    }

    /**
     * The module-identity-layer-seeded constructor
     * (descriptor-identity-propagation D1): every class type this
     * extractor produces obtains its {@link CanonicalClassIdentity}
     * exclusively through the supplied classification — never from
     * dotted-path reconstruction.
     */
    public ExportExtractor(String modulePath, boolean isDeclarationFile,
                           Function<String, CanonicalModuleIdentity> moduleClassification) {
        this.modulePath = modulePath;
        this.isDeclarationFile = isDeclarationFile;
        this.moduleClassification = Objects.requireNonNull(
            moduleClassification, "moduleClassification must not be null");
    }

    /** The supplied module-path classification (never null). */
    private final Function<String, CanonicalModuleIdentity> moduleClassification;

    private static Function<String, CanonicalModuleIdentity> standaloneClassification(
            String modulePath) {
        Map<String, CanonicalModuleIdentity> map = new HashMap<>();
        map.put("", CanonicalModuleIdentity.BuiltinModule.INSTANCE);
        String effective = modulePath == null ? "" : modulePath;
        if (!effective.isEmpty()) {
            map.put(effective,
                new CanonicalModuleIdentity.ProjectModule(
                    new ProjectModuleIdentity(effective, effective, List.of())));
        }
        return map::get;
    }

    /**
     * Builds the class identity for a class declared in the module with
     * the given wiring path, exclusively through the classification.  A
     * module without a public identity fails closed.
     */
    private CanonicalClassIdentity classIdentityFor(String wiringPath,
                                                    String className) {
        String mp = wiringPath == null ? "" : wiringPath;
        CanonicalModuleIdentity moduleIdentity = moduleClassification.apply(mp);
        if (moduleIdentity == null) {
            throw new IllegalStateException(
                "no canonical public module identity for module path '" + mp
                    + "': a class there can never carry an identity "
                    + "(internal invariant violation)");
        }
        return new CanonicalClassIdentity(moduleIdentity, className);
    }

    private Type.Class classTypeFor(String name, String wiringPath) {
        return Types.classType(name, classIdentityFor(wiringPath, name));
    }

    private Type.Class errorClassType() {
        return Types.classType("Error", new CanonicalClassIdentity(
            CanonicalModuleIdentity.BuiltinModule.INSTANCE, "Error"));
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
        Map<String, List<ClassField>> collected = new HashMap<>();
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ClassDeclaration cd) {
                collected.put(cd.name(), cd.fields());
            } else if (stmt instanceof ExportDeclaration exp
                    && exp.declaration() instanceof ClassDeclaration cd) {
                collected.put(cd.name(), cd.fields());
            }
        }
        classMap = collected;

        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ExportDeclaration exp) {
                extractExport(exp, classMap);
            } else if (isDeclarationFile && !isAllowedDeclarationFileStmt(stmt)) {
                diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E7001,
                    "Declaration files may only contain export declarations and class declarations",
                    stmt.span()));
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
                Type clsType = classTypeFor(cd.name(), modulePath);

                // C$fromJson: (string) -> C | null
                Type.Func fromJsonType = new Type.Func(
                    List.of(Type.String.INSTANCE),
                    Types.nullable(clsType));
                exports.put(cd.name() + "$fromJson", fromJsonType);

                // C$toJson: (C) -> string
                Type.Func toJsonType = new Type.Func(
                    List.of(clsType),
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
                    diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E7001,
                        "Exported function '" + fd.name()
                            + "' in declaration file must not have a body",
                        fd.span()));
                }
                Type funcType = resolveFuncType(fd, classMap);
                exports.put(fd.name(), funcType);
            }
            case ClassDeclaration cd -> {
                exports.put(cd.name(), classTypeFor(cd.name(), modulePath));
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

        return new Type.Func(paramTypes, retType, fd.isAsync());
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
                yield classTypeFor(qt.typeName(), resolvedModulePath);
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
                yield new Type.Func(pts, ret, ft.isAsync());
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
            case "bytes" -> Type.Bytes.INSTANCE;
            case "Error" -> errorClassType();
            default -> {
                if (classMap.containsKey(name)) {
                    yield classTypeFor(name, modulePath);
                }
                // Unknown type — will be resolved during type checking
                yield classTypeFor(name, "");
            }
        };
    }

    /**
     * Resolves one type node in this module's own declaration context —
     * primitives (the v1.2 {@code bytes} included), arrays, nullables,
     * function types, locally declared classes, and alias-qualified
     * classes through {@link #importModulePaths}. The JS host
     * declared-map gather consumes this for host-class field descriptor
     * types (ISSUE-0328, js-v12-host-abi-completion D1): a declaration
     * file never runs the name-resolver pass, so its field types must
     * resolve structurally against the declaration's own class map.
     * Callers invoke it after {@link #extract(ProgramNode)}.
     *
     * @param typeNode the field type annotation to resolve
     * @return the resolved type ({@code Type.Error} for an unresolvable
     *         inner node)
     */
    public Type resolveFieldType(TypeNode typeNode) {
        return resolveTypeNodeSimple(typeNode, classMap);
    }

    public List<CompilerDiagnostic> diagnostics() {
        return diagnostics;
    }
}
