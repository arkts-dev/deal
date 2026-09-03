package deal.ffi;

import deal.ast.ArrayLiteralExpr;
import deal.ast.AwaitExpression;
import deal.ast.BinaryExpr;
import deal.ast.CallExpr;
import deal.ast.ClassDeclaration;
import deal.ast.ClassField;
import deal.ast.DeclarationDirective;
import deal.ast.ExportDeclaration;
import deal.ast.ExpressionNode;
import deal.ast.FunctionDeclaration;
import deal.ast.FunctionExpr;
import deal.ast.HasExpr;
import deal.ast.IdentifierExpr;
import deal.ast.IndexExpr;
import deal.ast.LiteralExpr;
import deal.ast.MemberAccessExpr;
import deal.ast.ObjectLiteralExpr;
import deal.ast.ProgramNode;
import deal.ast.StatementNode;
import deal.ast.TemplateLiteralExpr;
import deal.ast.TypeNode;
import deal.ast.UnaryExpr;
import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticRange;
import deal.module.LexicalDeclarationIdentity;
import deal.module.ModuleIdentityAssembly;
import deal.module.SemanticModuleIdentity;
import deal.module.SemanticResourceIdentity;
import deal.module.SourceModuleLocation;
import deal.semantic.ir.CanonicalJson;
import deal.types.Type;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The declaration semantic/ABI validator of the extern-C metadata seam
 * (design source {@code deal-v1.2-directives-and-c-ffi-declarations}
 * D4): runs after semantic/graph success, never evaluates defaults, and
 * publishes exactly one source-ordered immutable
 * {@link FfiModuleDescriptor} when the declaration is valid.
 *
 * <p>Enforced policy (every violation is E7002 at the offending
 * declaration/type/field/expression range, before any metadata or
 * artifact publication):</p>
 * <ul>
 *   <li>synchronous functions only — an async exported function is a
 *       compile-time error;</li>
 *   <li>exact export/C names — the exported DEAL name is by definition
 *       the C symbol name ({@code cSymbol == dealName} enforced by the
 *       descriptor row); duplicate export names and plan-key collisions
 *       are rejected;</li>
 *   <li>the parameter allowlist — {@code int}, {@code number},
 *       {@code boolean}, {@code string}, {@code bytes}, same-file
 *       {@code @c-struct}, same-file {@code @c-pointer} — and the
 *       return allowlist — the same rows plus {@code null}, with
 *       {@code bytes} parameter-only;</li>
 *   <li>same-file class references only: a referenced class must be
 *       declared in this file and carry exactly one C marker;</li>
 *   <li>required/defaulted source-order struct fields: no optional
 *       fields, every field defaulted, field types exactly
 *       {@code int}/{@code number}/{@code boolean}/same-file
 *       {@code @c-pointer};</li>
 *   <li>pointer emptiness and non-constructibility: a non-empty
 *       {@code @c-pointer} body and an object-literal construction of a
 *       pointer value in a default are compile-time errors.</li>
 * </ul>
 *
 * <p>Marker cardinality and placement, {@code @deal-version}
 * compatibility, and the extern-C file placement are owned by the
 * parser/directive phases (E7002/E1045/E1046) and are prerequisites of
 * this phase.</p>
 */
public final class FfiDeclarationValidator {

    private FfiDeclarationValidator() {
        // Static entry; no instances.
    }

    /**
     * The resolved import surface of the validated module: one entry per
     * import alias with the provider's dotted module path and its
     * extracted export map.
     */
    public record ImportTarget(String modulePath, Map<String, Type> exports) {

        public ImportTarget {
            Objects.requireNonNull(modulePath, "modulePath");
            exports = Map.copyOf(Objects.requireNonNull(exports, "exports"));
        }
    }

    /**
     * The validation result: diagnostics, the published descriptor
     * (null on any error), and the frozen graph-ordered imported
     * references.
     */
    public record Result(
        List<CompilerDiagnostic> diagnostics,
        FfiModuleDescriptor descriptor,
        List<FfiImportedFunctionReference> importedFunctions,
        List<FfiImportedClassPlanReference> importedClassPlans) {

        public Result {
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics,
                "diagnostics"));
            importedFunctions = List.copyOf(Objects.requireNonNull(
                importedFunctions, "importedFunctions"));
            importedClassPlans = List.copyOf(Objects.requireNonNull(
                importedClassPlans, "importedClassPlans"));
        }

        /** True when any diagnostic is an error (no descriptor then). */
        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(
                d -> "error".equals(d.severity()));
        }
    }

    /**
     * Validates one extern-C declaration module and publishes its
     * descriptor.
     *
     * @param program                        the parsed program with
     *                                       effective
     *                                       {@code FileDirectives.externC}
     * @param modulePath                     the module's dotted module
     *                                       path
     * @param location                       the module's resolved source
     *                                       location (private semantic
     *                                       identity)
     * @param canonicalExternalModuleIdentity the externals descriptor
     *                                       namespace text
     *                                       {@code @$external/&lt;raw&gt;}
     * @param identityAssembly               the compilation's identity
     *                                       assembly (class identities
     *                                       registered in phase 1)
     * @param descriptorEncoder              the compilation's single
     *                                       Type&rarr;text encoder over the
     *                                       identity index (provider
     *                                       canonical descriptors)
     * @param nativeLibraryKind              the classified library kind,
     *                                       or null
     * @param nativeLibraryLoaderText        the exact loader text, or
     *                                       null
     * @param dependencyOrder                the compilation's check order
     *                                       (module paths in dependency
     *                                       order)
     * @param importTargets                  import alias &rarr; resolved
     *                                       provider surface
     * @return the diagnostics plus the descriptor (null on error) and
     *         the graph-ordered imported references
     */
    public static Result validate(
            ProgramNode program,
            String modulePath,
            SourceModuleLocation location,
            String canonicalExternalModuleIdentity,
            ModuleIdentityAssembly identityAssembly,
            CanonicalRuntimeTypeDescriptor descriptorEncoder,
            String nativeLibraryKind,
            String nativeLibraryLoaderText,
            List<String> dependencyOrder,
            Map<String, ImportTarget> importTargets) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(modulePath, "modulePath");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(canonicalExternalModuleIdentity,
            "canonicalExternalModuleIdentity");
        Objects.requireNonNull(identityAssembly, "identityAssembly");
        Objects.requireNonNull(descriptorEncoder, "descriptorEncoder");
        Objects.requireNonNull(dependencyOrder, "dependencyOrder");
        Objects.requireNonNull(importTargets, "importTargets");
        if ((nativeLibraryKind == null) != (nativeLibraryLoaderText == null)) {
            throw new IllegalArgumentException(
                "nativeLibraryKind and nativeLibraryLoaderText must both be"
                    + " present or both absent");
        }

        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        List<FfiImportedFunctionReference> functionRefs = new ArrayList<>();
        List<FfiImportedClassPlanReference> classPlanRefs = new ArrayList<>();

        // ---- Declarations in source order, with duplicate-name checks.
        Map<String, ClassInfo> classes = new LinkedHashMap<>();
        List<ClassInfo> classOrder = new ArrayList<>();
        List<FunctionDeclaration> functions = new ArrayList<>();
        Map<String, FunctionDeclaration> functionsByName =
            new LinkedHashMap<>();
        Set<String> declaredNames = new LinkedHashSet<>();

        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ExportDeclaration exp) {
                if (exp.declaration() instanceof FunctionDeclaration fd) {
                    functions.add(fd);
                    functionsByName.putIfAbsent(fd.name(), fd);
                    if (!declaredNames.add(fd.name())) {
                        diagnostics.add(error(
                            "Invalid C FFI declaration: duplicate exported"
                                + " function name '" + fd.name() + "'",
                            fd.span().range()));
                    }
                } else if (exp.declaration() instanceof ClassDeclaration cd) {
                    ClassInfo info = classInfoOf(cd, true, location,
                        identityAssembly, diagnostics);
                    if (!declaredNames.add(cd.name())) {
                        diagnostics.add(error(
                            "Invalid C FFI declaration: duplicate class name '"
                                + cd.name() + "'", cd.span().range()));
                    }
                    classes.put(cd.name(), info);
                    classOrder.add(info);
                }
            } else if (stmt instanceof FunctionDeclaration fd) {
                functionsByName.putIfAbsent(fd.name(), fd);
            } else if (stmt instanceof ClassDeclaration cd) {
                ClassInfo info = classInfoOf(cd, false, location,
                    identityAssembly, diagnostics);
                classes.put(cd.name(), info);
                classOrder.add(info);
            }
        }

        // ---- Classes first (struct field validation publishes the
        // validated field rows the function ABI rows resolve struct
        // ctypes from): struct fields, pointer emptiness.
        List<FfiClassDescriptor> classRows = new ArrayList<>();
        Map<String, FfiCompilerClassDefaultPlan> plans = new LinkedHashMap<>();
        Map<String, List<FfiFieldDescriptor>> structFieldsByName =
            new LinkedHashMap<>();
        Set<String> planKeys = new LinkedHashSet<>();
        for (ClassInfo info : classOrder) {
            if (info.kind == null) {
                continue; // unmarked non-exported class: not an FFI class
            }
            if (info.kind == FfiClassDescriptor.ClassKind.C_POINTER) {
                if (!info.declaration.fields().isEmpty()) {
                    diagnostics.add(error(
                        "Invalid C FFI declaration: @c-pointer class '"
                            + info.declaration.name()
                            + "' must have an empty body",
                        info.declaration.span().range()));
                    continue;
                }
                classRows.add(new FfiClassDescriptor(
                    info.declaration.name(), info.identityText,
                    info.identityText,
                    FfiClassDescriptor.ClassKind.C_POINTER,
                    List.of(), null));
                continue;
            }
            ClassPlanBuilder planBuilder = buildStructPlan(info, classes,
                modulePath, location, importTargets, dependencyOrder,
                functionsByName, functionRefs, classPlanRefs,
                descriptorEncoder, diagnostics);
            if (planBuilder == null) {
                continue;
            }
            String planKey = info.declaration.name() + "_plan";
            if (!planKeys.add(planKey)) {
                diagnostics.add(error(
                    "Invalid C FFI declaration: duplicate plan key '"
                        + planKey + "'", info.declaration.span().range()));
                continue;
            }
            FfiCompilerClassDefaultPlan plan = planBuilder.plan;
            plans.put(info.identityText, plan);
            structFieldsByName.put(info.declaration.name(),
                planBuilder.fields);
            classRows.add(new FfiClassDescriptor(
                info.declaration.name(), info.identityText,
                info.identityText, FfiClassDescriptor.ClassKind.C_STRUCT,
                planBuilder.fields, plan));
        }

        // ---- Exported functions: sync, allowlists, exact names.
        List<FfiFunctionDescriptor> functionRows = new ArrayList<>();
        String moduleKey = FfiModuleDescriptor.moduleKeyOf(
            canonicalExternalModuleIdentity);
        int functionOrdinal = 0;
        for (FunctionDeclaration fd : functions) {
            List<FfiType> params = new ArrayList<>();
            boolean functionOk = true;
            if (fd.isAsync()) {
                diagnostics.add(error(
                    "Invalid C FFI declaration: async function declaration '"
                        + fd.name() + "' (C FFI functions must be"
                        + " synchronous)",
                    fd.span().range()));
                functionOk = false;
            }
            for (deal.ast.Parameter p : fd.params()) {
                ResolvedType resolved = resolveParameterType(p.type(),
                    classes, diagnostics);
                if (resolved == null) {
                    functionOk = false;
                } else {
                    params.add(resolved.type());
                }
            }
            ResolvedType returnType = resolveReturnType(fd.returnType(),
                classes, diagnostics);
            if (returnType == null) {
                functionOk = false;
            }
            if (functionOk) {
                // A resolved C_STRUCT parameter/return whose class failed
                // its own struct-field validation has no validated field
                // rows, so the row cannot be built: the function is
                // invalid too. The class's own E7002 is already
                // reported; this guard turns the case into clean
                // diagnostics instead of a raw ctype NPE.
                if (referencesUnvalidatedStruct(params, structFieldsByName)
                        || referencesUnvalidatedStruct(
                            List.of(returnType.type()),
                            structFieldsByName)) {
                    functionOk = false;
                }
            }
            if (functionOk) {
                functionOrdinal++;
                functionRows.add(buildFunctionRow(fd, params,
                    returnType.type(), functionOrdinal, moduleKey,
                    structFieldsByName));
            }
        }

        // Plan-key / export-name collisions (the runtime plan-entry
        // convention <C>_plan must never collide with a declared export).
        for (FfiFunctionDescriptor fn : functionRows) {
            if (planKeys.contains(fn.dealName())) {
                diagnostics.add(error(
                    "Invalid C FFI declaration: exported function name '"
                        + fn.dealName()
                        + "' collides with a class plan entry key",
                    fdRangeOf(functions, fn.dealName())));
            }
        }

        if (hasError(diagnostics)) {
            return new Result(diagnostics, null,
                List.copyOf(functionRefs), List.copyOf(classPlanRefs));
        }

        // ---- Imported references in dependency (graph) order: stable
        // sort by the provider module's check-order position, source
        // order preserved within one provider.
        functionRefs.sort(Comparator.comparingInt(
            FfiImportedFunctionReference::graphOrder));
        classPlanRefs.sort(Comparator.comparingInt(
            FfiImportedClassPlanReference::graphOrder));

        String canonicalPlanContent = canonicalModuleContent(moduleKey,
            canonicalExternalModuleIdentity,
            location.semanticModuleIdentity(),
            nativeLibraryKind, nativeLibraryLoaderText,
            functionRows, classRows, plans);
        FfiModuleDescriptor descriptor = new FfiModuleDescriptor(
            moduleKey,
            location.semanticModuleIdentity(),
            canonicalExternalModuleIdentity,
            nativeLibraryKind,
            nativeLibraryLoaderText,
            classRows,
            functionRows,
            plans,
            canonicalPlanContent,
            FfiContentSerializer.sha256(canonicalPlanContent));
        return new Result(diagnostics, descriptor,
            List.copyOf(functionRefs), List.copyOf(classPlanRefs));
    }

    // =========================================================================
    // Internals
    // =========================================================================

    /** The validator-side class view. */
    private static final class ClassInfo {
        final ClassDeclaration declaration;
        final boolean exported;
        final FfiClassDescriptor.ClassKind kind;
        final String identityText;

        ClassInfo(ClassDeclaration declaration, boolean exported,
                  FfiClassDescriptor.ClassKind kind, String identityText) {
            this.declaration = declaration;
            this.exported = exported;
            this.kind = kind;
            this.identityText = identityText;
        }
    }

    /** A validated struct-plan build: fields and plan. */
    private static final class ClassPlanBuilder {
        final List<FfiFieldDescriptor> fields;
        final FfiCompilerClassDefaultPlan plan;

        ClassPlanBuilder(List<FfiFieldDescriptor> fields,
                         FfiCompilerClassDefaultPlan plan) {
            this.fields = fields;
            this.plan = plan;
        }
    }

    /** One resolved FFI type row plus its same-file class info. */
    private record ResolvedType(FfiType type, ClassInfo classInfo) {
    }

    /**
     * Builds the class view: derives the marker kind (exactly one C
     * marker for exported classes by the parser's cardinality gate),
     * requires the public class identity, and merges defensive E2010
     * failures.
     */
    private static ClassInfo classInfoOf(ClassDeclaration cd, boolean exported,
            SourceModuleLocation location,
            ModuleIdentityAssembly identityAssembly,
            List<CompilerDiagnostic> diagnostics) {
        FfiClassDescriptor.ClassKind kind = markerKind(cd);
        ModuleIdentityAssembly.ClassIdentityResult identity =
            identityAssembly.requireClassIdentity(location, cd.name(),
                cd.span());
        if (identity instanceof ModuleIdentityAssembly.ClassIdentityResult.Failure f) {
            diagnostics.add(f.diagnostic());
            return new ClassInfo(cd, exported, kind, null);
        }
        return new ClassInfo(cd, exported, kind,
            ((ModuleIdentityAssembly.ClassIdentityResult.Identity) identity)
                .descriptorText());
    }

    /** The marker kind of a class declaration, or null when unmarked. */
    private static FfiClassDescriptor.ClassKind markerKind(ClassDeclaration cd) {
        boolean struct = cd.directives().contains(DeclarationDirective.C_STRUCT);
        boolean pointer = cd.directives().contains(
            DeclarationDirective.C_POINTER);
        if (struct && !pointer) {
            return FfiClassDescriptor.ClassKind.C_STRUCT;
        }
        if (pointer && !struct) {
            return FfiClassDescriptor.ClassKind.C_POINTER;
        }
        return null;
    }

    /**
     * Resolves one parameter type node against the allowlist. Emits one
     * E7002 at the type node's range on violation and returns null.
     */
    private static ResolvedType resolveParameterType(TypeNode tn,
            Map<String, ClassInfo> classes,
            List<CompilerDiagnostic> diagnostics) {
        if (tn instanceof deal.ast.NamedType named) {
            if ("null".equals(named.name())) {
                diagnostics.add(error(
                    "Invalid C FFI declaration: null is not a valid C FFI"
                        + " parameter type (int, number, boolean, string,"
                        + " bytes, same-file @c-struct, same-file"
                        + " @c-pointer)",
                    tn.span().range()));
                return null;
            }
            return resolveNamed(named, classes, diagnostics);
        }
        diagnostics.add(error(
            "Invalid C FFI declaration: parameter type "
                + typeNodeText(tn)
                + " is not in the C FFI parameter allowlist (int, number,"
                + " boolean, string, bytes, same-file @c-struct,"
                + " same-file @c-pointer)",
            tn.span().range()));
        return null;
    }

    /**
     * Resolves one return type node against the allowlist. Emits one
     * E7002 at the type node's range on violation and returns null.
     */
    private static ResolvedType resolveReturnType(TypeNode tn,
            Map<String, ClassInfo> classes,
            List<CompilerDiagnostic> diagnostics) {
        if (tn instanceof deal.ast.NamedType named) {
            if ("bytes".equals(named.name())) {
                diagnostics.add(error(
                    "Invalid C FFI declaration: bytes is not a valid C FFI"
                        + " return type (bytes is parameter-only)",
                    tn.span().range()));
                return null;
            }
            return resolveNamed(named, classes, diagnostics);
        }
        diagnostics.add(error(
            "Invalid C FFI declaration: return type " + typeNodeText(tn)
                + " is not in the C FFI return allowlist (int, number,"
                + " boolean, string, null, same-file @c-struct,"
                + " same-file @c-pointer)",
            tn.span().range()));
        return null;
    }

    /** Resolves a named type: primitive rows and same-file classes. */
    private static ResolvedType resolveNamed(deal.ast.NamedType named,
            Map<String, ClassInfo> classes,
            List<CompilerDiagnostic> diagnostics) {
        String name = named.name();
        FfiType primitive = switch (name) {
            case "int" -> new FfiType(FfiType.Kind.INT, "int", null);
            case "number" -> new FfiType(FfiType.Kind.NUMBER, "number", null);
            case "boolean" -> new FfiType(FfiType.Kind.BOOLEAN, "boolean", null);
            case "string" -> new FfiType(FfiType.Kind.STRING, "string", null);
            case "bytes" -> new FfiType(FfiType.Kind.BYTES, "bytes", null);
            case "null" -> new FfiType(FfiType.Kind.NULL, "null", null);
            default -> null;
        };
        if (primitive != null) {
            return new ResolvedType(primitive, null);
        }
        ClassInfo info = classes.get(name);
        if (info == null) {
            diagnostics.add(error(
                "Invalid C FFI declaration: type '" + name
                    + "' is not declared in this file (C FFI types must be"
                    + " same-file @c-struct/@c-pointer classes)",
                named.span().range()));
            return null;
        }
        if (info.identityText == null) {
            return null; // identity failure already diagnosed
        }
        if (info.kind == null) {
            diagnostics.add(error(
                "Invalid C FFI declaration: class '" + name
                    + "' carries no C marker (referenced C FFI types must"
                    + " be same-file @c-struct or @c-pointer classes)",
                named.span().range()));
            return null;
        }
        FfiType.Kind kind = info.kind == FfiClassDescriptor.ClassKind.C_STRUCT
            ? FfiType.Kind.C_STRUCT : FfiType.Kind.C_POINTER;
        return new ResolvedType(new FfiType(kind, info.identityText,
            info.identityText), info);
    }

    /** The readable text of a type node for diagnostics. */
    private static String typeNodeText(TypeNode tn) {
        return switch (tn) {
            case deal.ast.NamedType n -> n.name();
            case deal.ast.QualifiedType q ->
                q.moduleName() + "." + q.typeName();
            case deal.ast.ArrayType ignored -> "array";
            case deal.ast.NullableType ignored -> "nullable";
            case deal.ast.FunctionType ignored -> "function";
        };
    }

    /**
     * Builds one function descriptor row with the generated private
     * function-pointer type: the bare private typedef name for
     * scalar/string/bytes/pointer functions, the full anonymous
     * {@code RET (*)(...)} spelling when a C_STRUCT participates (the
     * runtime splitter contract).
     */
    private static FfiFunctionDescriptor buildFunctionRow(
            FunctionDeclaration fd, List<FfiType> params, FfiType returnType,
            int ordinal, String moduleKey,
            Map<String, List<FfiFieldDescriptor>> structFieldsByName) {
        String signatureDigest = LuaFfiBindingGenerator.functionSignatureDigest(
            fd.name(), params, returnType);
        String typedefName = LuaFfiBindingGenerator.functionPointerTypeName(
            fd.name(), ordinal, signatureDigest);
        boolean structInvolved = returnType.kind() == FfiType.Kind.C_STRUCT
            || params.stream().anyMatch(
                p -> p.kind() == FfiType.Kind.C_STRUCT);
        String privateFunctionPointerType = typedefName;
        if (structInvolved) {
            List<String> cParams = new ArrayList<>();
            for (FfiType p : params) {
                cParams.addAll(LuaFfiBindingGenerator.cParameterTypes(p,
                    moduleKey, structFieldsByName));
            }
            String ret = LuaFfiBindingGenerator.cReturnType(returnType,
                moduleKey, structFieldsByName);
            String paramsText = cParams.isEmpty()
                ? "void" : String.join(", ", cParams);
            privateFunctionPointerType = ret + " (*)(" + paramsText + ")";
        }
        return new FfiFunctionDescriptor(fd.name(), fd.name(),
            privateFunctionPointerType, params, returnType);
    }

    /**
     * Builds the struct plan of one C_STRUCT class: validates fields
     * (required, defaulted, scalar/same-file-pointer types), detects
     * pointer construction, serializes the canonical plan content, and
     * collects the default's imported references. Returns null when any
     * E7002 fired for the class.
     */
    private static ClassPlanBuilder buildStructPlan(
            ClassInfo info,
            Map<String, ClassInfo> classes,
            String modulePath,
            SourceModuleLocation location,
            Map<String, ImportTarget> importTargets,
            List<String> dependencyOrder,
            Map<String, FunctionDeclaration> functionsByName,
            List<FfiImportedFunctionReference> functionRefs,
            List<FfiImportedClassPlanReference> classPlanRefs,
            CanonicalRuntimeTypeDescriptor descriptorEncoder,
            List<CompilerDiagnostic> diagnostics) {
        ClassDeclaration cd = info.declaration;
        List<FfiFieldDescriptor> fieldRows = new ArrayList<>();
        List<FfiCompilerClassDefaultPlan.Entry> planEntries =
            new ArrayList<>();
        Map<String, List<String>> providerDigestsByField =
            new LinkedHashMap<>();
        Set<String> fieldNames = new LinkedHashSet<>();
        boolean ok = true;
        int ordinal = 0;
        for (ClassField field : cd.fields()) {
            if (!fieldNames.add(field.name())) {
                diagnostics.add(error(
                    "Invalid C FFI declaration: duplicate field '"
                        + field.name() + "' in @c-struct class '"
                        + cd.name() + "'",
                    field.span().range()));
                ok = false;
                continue;
            }
            if (field.optional()) {
                diagnostics.add(error(
                    "Invalid C FFI declaration: field '" + field.name()
                        + "' of @c-struct class '" + cd.name()
                        + "' must be required (not optional)",
                    field.span().range()));
                ok = false;
            }
            if (field.defaultExpr().isEmpty()) {
                diagnostics.add(error(
                    "Invalid C FFI declaration: field '" + field.name()
                        + "' of @c-struct class '" + cd.name()
                        + "' must carry a default",
                    field.span().range()));
                ok = false;
            }
            ResolvedType resolved = resolveFieldType(field.type(), classes,
                diagnostics);
            if (resolved == null) {
                ok = false;
                continue;
            }
            FfiType ffiType = resolved.type();
            ExpressionNode defaultExpr = field.defaultExpr().orElse(null);
            if (defaultExpr != null
                    && ffiType.kind() == FfiType.Kind.C_POINTER) {
                ObjectLiteralExpr construction = findPointerConstruction(
                    defaultExpr, ffiType.canonicalDescriptor(), classes,
                    functionsByName, importTargets, descriptorEncoder);
                if (construction != null) {
                    diagnostics.add(error(
                        "Invalid C FFI declaration: object-literal"
                            + " construction of @c-pointer value '"
                            + resolved.classInfo.declaration.name()
                            + "' (pointer tokens come from C only)",
                        construction.span().range()));
                    ok = false;
                }
            }
            FfiFieldDescriptor fieldRow = new FfiFieldDescriptor(
                field.name(), ordinal, ffiType);
            fieldRows.add(fieldRow);

            // Per-field canonical content: evaluator serialization,
            // semantic resource identity digest, provider digests, range.
            List<FfiImportedFunctionReference> fieldFunctionRefs =
                new ArrayList<>();
            List<FfiImportedClassPlanReference> fieldClassPlanRefs =
                new ArrayList<>();
            if (defaultExpr != null) {
                collectImportedReferences(defaultExpr, importTargets,
                    dependencyOrder, descriptorEncoder, fieldFunctionRefs,
                    fieldClassPlanRefs);
            }
            List<String> providerDigests = new ArrayList<>();
            for (FfiImportedFunctionReference ref : fieldFunctionRefs) {
                functionRefs.add(ref);
                providerDigests.add(ref.providerContractDigest());
            }
            for (FfiImportedClassPlanReference ref : fieldClassPlanRefs) {
                classPlanRefs.add(ref);
                providerDigests.add(ref.providerContractDigest());
            }
            providerDigestsByField.put(field.name(),
                List.copyOf(providerDigests));
            String evaluatorContent = defaultExpr == null ? null
                : FfiContentSerializer.text(
                    FfiContentSerializer.expression(defaultExpr));
            planEntries.add(new FfiCompilerClassDefaultPlan.Entry(
                field.name(), ffiType.canonicalDescriptor(), false,
                defaultExpr != null, evaluatorContent));
            ordinal++;
        }
        if (!ok) {
            return null;
        }
        FfiCompilerClassDefaultPlan plan = buildPlan(info, planEntries,
            location.semanticModuleIdentity(), modulePath,
            providerDigestsByField);
        return new ClassPlanBuilder(fieldRows, plan);
    }

    /**
     * Resolves one struct-field type node: {@code int}/{@code number}/
     * {@code boolean} and same-file {@code @c-pointer} classes only.
     */
    private static ResolvedType resolveFieldType(TypeNode tn,
            Map<String, ClassInfo> classes,
            List<CompilerDiagnostic> diagnostics) {
        if (!(tn instanceof deal.ast.NamedType named)) {
            diagnostics.add(error(
                "Invalid C FFI declaration: @c-struct field type "
                    + typeNodeText(tn)
                    + " is not in the struct-field allowlist (int, number,"
                    + " boolean, same-file @c-pointer)",
                tn.span().range()));
            return null;
        }
        FfiType primitive = switch (named.name()) {
            case "int" -> new FfiType(FfiType.Kind.INT, "int", null);
            case "number" -> new FfiType(FfiType.Kind.NUMBER, "number", null);
            case "boolean" -> new FfiType(FfiType.Kind.BOOLEAN, "boolean", null);
            default -> null;
        };
        if (primitive != null) {
            return new ResolvedType(primitive, null);
        }
        ClassInfo info = classes.get(named.name());
        if (info == null || info.identityText == null || info.kind == null) {
            diagnostics.add(error(
                "Invalid C FFI declaration: struct field type '"
                    + named.name()
                    + "' is not in the struct-field allowlist (int, number,"
                    + " boolean, same-file @c-pointer)",
                named.span().range()));
            return null;
        }
        if (info.kind != FfiClassDescriptor.ClassKind.C_POINTER) {
            diagnostics.add(error(
                "Invalid C FFI declaration: struct field type '"
                    + named.name()
                    + "' is not in the struct-field allowlist (only"
                    + " same-file @c-pointer classes; @c-struct fields"
                    + " cannot nest)",
                named.span().range()));
            return null;
        }
        return new ResolvedType(new FfiType(FfiType.Kind.C_POINTER,
            info.identityText, info.identityText), info);
    }

    /**
     * Walks one default expression of a {@code @c-pointer}-typed field
     * and returns the first object literal whose resolved constructed
     * class is a {@code @c-pointer} class. The walk is type-directed:
     * the expected descriptor at each position comes from the field
     * type, a resolved call's parameter type, a struct field type, or a
     * function expression's declared return type. Object literals that
     * construct other classes — for example a {@code @c-struct} literal
     * passed as a call argument — are legal and are only descended into
     * through the constructed class's field types; unresolvable
     * positions are never flagged.
     */
    private static ObjectLiteralExpr findPointerConstruction(
            ExpressionNode expr,
            String expectedDescriptor,
            Map<String, ClassInfo> classes,
            Map<String, FunctionDeclaration> functionsByName,
            Map<String, ImportTarget> importTargets,
            CanonicalRuntimeTypeDescriptor descriptorEncoder) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof ObjectLiteralExpr ole) {
            ClassInfo constructed = classInfoForDescriptor(
                expectedDescriptor, classes);
            if (constructed != null && constructed.kind
                    == FfiClassDescriptor.ClassKind.C_POINTER) {
                return ole;
            }
            if (constructed != null && constructed.kind
                    == FfiClassDescriptor.ClassKind.C_STRUCT) {
                Map<String, String> fieldDescriptors =
                    structFieldDescriptors(constructed, classes);
                for (deal.ast.Property property : ole.properties()) {
                    ObjectLiteralExpr nested = findPointerConstruction(
                        property.value(),
                        fieldDescriptors.get(property.name()),
                        classes, functionsByName, importTargets,
                        descriptorEncoder);
                    if (nested != null) {
                        return nested;
                    }
                }
            }
            return null;
        }
        if (expr instanceof CallExpr ce) {
            List<String> parameterDescriptors = callParameterDescriptors(
                ce.callee(), classes, functionsByName, importTargets,
                descriptorEncoder);
            for (int i = 0; i < ce.args().size(); i++) {
                String expected = parameterDescriptors != null
                    && i < parameterDescriptors.size()
                    ? parameterDescriptors.get(i) : null;
                ObjectLiteralExpr nested = findPointerConstruction(
                    ce.args().get(i), expected, classes, functionsByName,
                    importTargets, descriptorEncoder);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        if (expr instanceof FunctionExpr fe) {
            String returnDescriptor = typeNodeDescriptor(fe.returnType(),
                classes);
            return findPointerConstructionInStatements(
                fe.body().statements(), returnDescriptor, classes,
                functionsByName, importTargets, descriptorEncoder);
        }
        if (expr instanceof BinaryExpr be) {
            ObjectLiteralExpr left = findPointerConstruction(be.left(),
                expectedDescriptor, classes, functionsByName,
                importTargets, descriptorEncoder);
            return left != null ? left : findPointerConstruction(
                be.right(), expectedDescriptor, classes, functionsByName,
                importTargets, descriptorEncoder);
        }
        if (expr instanceof UnaryExpr ue) {
            return findPointerConstruction(ue.expr(), expectedDescriptor,
                classes, functionsByName, importTargets, descriptorEncoder);
        }
        if (expr instanceof AwaitExpression aw) {
            return findPointerConstruction(aw.callee(), expectedDescriptor,
                classes, functionsByName, importTargets, descriptorEncoder);
        }
        // Member access, indexing, has, template literals, and array
        // literals do not propagate the expected type; nested calls
        // still resolve their own signatures.
        if (expr instanceof MemberAccessExpr mae) {
            return findPointerConstruction(mae.object(), null, classes,
                functionsByName, importTargets, descriptorEncoder);
        }
        if (expr instanceof IndexExpr ie) {
            ObjectLiteralExpr array = findPointerConstruction(ie.array(),
                null, classes, functionsByName, importTargets,
                descriptorEncoder);
            return array != null ? array : findPointerConstruction(
                ie.index(), null, classes, functionsByName, importTargets,
                descriptorEncoder);
        }
        if (expr instanceof ArrayLiteralExpr ale) {
            for (ExpressionNode element : ale.elements()) {
                ObjectLiteralExpr nested = findPointerConstruction(element,
                    null, classes, functionsByName, importTargets,
                    descriptorEncoder);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        if (expr instanceof HasExpr he) {
            return findPointerConstruction(he.object(), null, classes,
                functionsByName, importTargets, descriptorEncoder);
        }
        if (expr instanceof TemplateLiteralExpr tle) {
            for (ExpressionNode part : tle.parts()) {
                ObjectLiteralExpr nested = findPointerConstruction(part,
                    null, classes, functionsByName, importTargets,
                    descriptorEncoder);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        // Identifier/literal/assignment leaves: no constructed literal.
        return null;
    }

    /**
     * Statement-level type-directed walk (function-expression bodies):
     * returns propagate the function's declared return descriptor;
     * variable declarations resolve their declared type; other
     * statements walk nested expressions/blocks with no inherited
     * expected type (nested calls still resolve their own signatures).
     */
    private static ObjectLiteralExpr findPointerConstructionInStatements(
            List<StatementNode> statements,
            String expectedReturnDescriptor,
            Map<String, ClassInfo> classes,
            Map<String, FunctionDeclaration> functionsByName,
            Map<String, ImportTarget> importTargets,
            CanonicalRuntimeTypeDescriptor descriptorEncoder) {
        for (StatementNode stmt : statements) {
            ObjectLiteralExpr hit = findPointerConstructionInStatement(
                stmt, expectedReturnDescriptor, classes, functionsByName,
                importTargets, descriptorEncoder);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** One closure-body statement of the type-directed walk. */
    private static ObjectLiteralExpr findPointerConstructionInStatement(
            StatementNode stmt,
            String expectedReturnDescriptor,
            Map<String, ClassInfo> classes,
            Map<String, FunctionDeclaration> functionsByName,
            Map<String, ImportTarget> importTargets,
            CanonicalRuntimeTypeDescriptor descriptorEncoder) {
        switch (stmt) {
            case deal.ast.ExpressionStatement es -> {
                return findPointerConstruction(es.expr(), null, classes,
                    functionsByName, importTargets, descriptorEncoder);
            }
            case deal.ast.ReturnStatement rs -> {
                return rs.expr().map(e -> findPointerConstruction(e,
                    expectedReturnDescriptor, classes, functionsByName,
                    importTargets, descriptorEncoder)).orElse(null);
            }
            case deal.ast.VariableDeclaration vd -> {
                String expected = vd.typeAnnotation().map(t ->
                    typeNodeDescriptor(t, classes)).orElse(null);
                return findPointerConstruction(vd.initializer(), expected,
                    classes, functionsByName, importTargets,
                    descriptorEncoder);
            }
            case deal.ast.ThrowStatement ts -> {
                return findPointerConstruction(ts.expr(), null, classes,
                    functionsByName, importTargets, descriptorEncoder);
            }
            case deal.ast.DeleteStatement ds -> {
                return findPointerConstruction(ds.target(), null, classes,
                    functionsByName, importTargets, descriptorEncoder);
            }
            case deal.ast.Block b -> {
                return findPointerConstructionInStatements(
                    b.statements(), expectedReturnDescriptor, classes,
                    functionsByName, importTargets, descriptorEncoder);
            }
            case deal.ast.FunctionDeclaration fd -> {
                String returnDescriptor = typeNodeDescriptor(
                    fd.returnType(), classes);
                return findPointerConstructionInStatements(
                    fd.body().statements(), returnDescriptor, classes,
                    functionsByName, importTargets, descriptorEncoder);
            }
            case deal.ast.IfStatement ifs -> {
                ObjectLiteralExpr hit = findPointerConstruction(
                    ifs.condition(), null, classes, functionsByName,
                    importTargets, descriptorEncoder);
                if (hit != null) {
                    return hit;
                }
                hit = findPointerConstructionInStatements(
                    ifs.thenBlock().statements(), expectedReturnDescriptor,
                    classes, functionsByName, importTargets,
                    descriptorEncoder);
                if (hit != null) {
                    return hit;
                }
                if (ifs.elseBranch().isPresent()) {
                    deal.ast.Either<deal.ast.IfStatement, deal.ast.Block>
                        branch = ifs.elseBranch().get();
                    if (branch instanceof deal.ast.Either.Left<
                            deal.ast.IfStatement, deal.ast.Block> left) {
                        return findPointerConstructionInStatement(
                            left.value(), expectedReturnDescriptor, classes,
                            functionsByName, importTargets,
                            descriptorEncoder);
                    }
                    if (branch instanceof deal.ast.Either.Right<
                            deal.ast.IfStatement, deal.ast.Block> right) {
                        return findPointerConstructionInStatements(
                            right.value().statements(),
                            expectedReturnDescriptor, classes,
                            functionsByName, importTargets,
                            descriptorEncoder);
                    }
                }
                return null;
            }
            case deal.ast.WhileStatement ws -> {
                ObjectLiteralExpr hit = findPointerConstruction(
                    ws.condition(), null, classes, functionsByName,
                    importTargets, descriptorEncoder);
                return hit != null ? hit
                    : findPointerConstructionInStatements(
                        ws.body().statements(), expectedReturnDescriptor,
                        classes, functionsByName, importTargets,
                        descriptorEncoder);
            }
            case deal.ast.ForStatement fs -> {
                if (fs.init().isPresent()) {
                    ObjectLiteralExpr init = switch (fs.init().get()) {
                        case deal.ast.ForInit.VarDecl vd ->
                            findPointerConstructionInStatement(vd.decl(),
                                expectedReturnDescriptor, classes,
                                functionsByName, importTargets,
                                descriptorEncoder);
                        case deal.ast.ForInit.AssignExpr ae ->
                            findPointerConstruction(ae.expr(), null, classes,
                                functionsByName, importTargets,
                                descriptorEncoder);
                    };
                    if (init != null) {
                        return init;
                    }
                }
                if (fs.condition().isPresent()) {
                    ObjectLiteralExpr hit = findPointerConstruction(
                        fs.condition().get(), null, classes,
                        functionsByName, importTargets, descriptorEncoder);
                    if (hit != null) {
                        return hit;
                    }
                }
                if (fs.update().isPresent()) {
                    ObjectLiteralExpr hit = findPointerConstruction(
                        fs.update().get(), null, classes, functionsByName,
                        importTargets, descriptorEncoder);
                    if (hit != null) {
                        return hit;
                    }
                }
                return findPointerConstructionInStatements(
                    fs.body().statements(), expectedReturnDescriptor,
                    classes, functionsByName, importTargets,
                    descriptorEncoder);
            }
            case deal.ast.ForOfStatement fos -> {
                ObjectLiteralExpr hit = findPointerConstruction(
                    fos.iterable(), null, classes, functionsByName,
                    importTargets, descriptorEncoder);
                return hit != null ? hit
                    : findPointerConstructionInStatements(
                        fos.body().statements(), expectedReturnDescriptor,
                        classes, functionsByName, importTargets,
                        descriptorEncoder);
            }
            case deal.ast.TryStatement ts -> {
                ObjectLiteralExpr hit = findPointerConstructionInStatements(
                    ts.tryBlock().statements(), expectedReturnDescriptor,
                    classes, functionsByName, importTargets,
                    descriptorEncoder);
                return hit != null ? hit
                    : findPointerConstructionInStatements(
                        ts.catchBlock().statements(),
                        expectedReturnDescriptor, classes, functionsByName,
                        importTargets, descriptorEncoder);
            }
            default -> {
                // Remaining statements carry no object-literal
                // construction position.
                return null;
            }
        }
    }

    /** The same-file class whose identity descriptor text matches, or null. */
    private static ClassInfo classInfoForDescriptor(String descriptor,
            Map<String, ClassInfo> classes) {
        if (descriptor == null) {
            return null;
        }
        for (ClassInfo info : classes.values()) {
            if (descriptor.equals(info.identityText)) {
                return info;
            }
        }
        return null;
    }

    /** The canonical descriptor of one type node through the class map. */
    private static String typeNodeDescriptor(TypeNode tn,
            Map<String, ClassInfo> classes) {
        if (tn instanceof deal.ast.NamedType named) {
            String primitive = switch (named.name()) {
                case "int" -> "int";
                case "number" -> "number";
                case "boolean" -> "boolean";
                case "string" -> "string";
                case "bytes" -> "bytes";
                case "null" -> "null";
                default -> null;
            };
            if (primitive != null) {
                return primitive;
            }
            ClassInfo info = classes.get(named.name());
            return info == null ? null : info.identityText;
        }
        return null;
    }

    /** Field name &rarr; canonical descriptor of one struct class's fields. */
    private static Map<String, String> structFieldDescriptors(ClassInfo info,
            Map<String, ClassInfo> classes) {
        Map<String, String> descriptors = new LinkedHashMap<>();
        for (ClassField field : info.declaration.fields()) {
            descriptors.put(field.name(),
                typeNodeDescriptor(field.type(), classes));
        }
        return descriptors;
    }

    /**
     * The resolved parameter descriptors of one callee: a same-file
     * function declaration by name, or an imported provider function
     * through the import surface. Null when the callee does not resolve.
     */
    private static List<String> callParameterDescriptors(
            ExpressionNode callee,
            Map<String, ClassInfo> classes,
            Map<String, FunctionDeclaration> functionsByName,
            Map<String, ImportTarget> importTargets,
            CanonicalRuntimeTypeDescriptor descriptorEncoder) {
        if (callee instanceof IdentifierExpr id) {
            FunctionDeclaration fd = functionsByName.get(id.name());
            if (fd == null) {
                return null;
            }
            List<String> descriptors = new ArrayList<>();
            for (deal.ast.Parameter parameter : fd.params()) {
                descriptors.add(typeNodeDescriptor(parameter.type(),
                    classes));
            }
            return descriptors;
        }
        if (callee instanceof MemberAccessExpr mae
                && mae.object() instanceof IdentifierExpr id) {
            ImportTarget target = importTargets.get(id.name());
            if (target == null) {
                return null;
            }
            Type exportType = target.exports().get(mae.field());
            if (!(exportType instanceof Type.Func fn)) {
                return null;
            }
            List<String> descriptors = new ArrayList<>();
            for (Type parameter : fn.paramTypes()) {
                descriptors.add(encodeProviderType(parameter,
                    descriptorEncoder));
            }
            return descriptors;
        }
        return null;
    }

    /**
     * The canonical descriptor of one provider-side resolved type;
     * unregistered identities (which cannot be this file's pointer
     * class) resolve to null so the position is never flagged.
     */
    private static String encodeProviderType(Type type,
            CanonicalRuntimeTypeDescriptor encoder) {
        try {
            return encoder.encode(type);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /**
     * True when any row is a {@code C_STRUCT} whose class has no
     * validated field rows (its own struct-field validation failed).
     */
    private static boolean referencesUnvalidatedStruct(List<FfiType> rows,
            Map<String, List<FfiFieldDescriptor>> structFieldsByName) {
        for (FfiType type : rows) {
            if (type.kind() == FfiType.Kind.C_STRUCT) {
                String identity = type.canonicalClassIdentity();
                String className = identity == null ? null
                    : identity.substring(identity.lastIndexOf('/') + 1);
                if (className == null
                        || !structFieldsByName.containsKey(className)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Collects the imported function-wrapper and class-plan references
     * of one default expression: member accesses (and their calls) on
     * import aliases whose member is an exported function, plus any
     * exported-class member reference.
     */
    private static void collectImportedReferences(
            ExpressionNode expr,
            Map<String, ImportTarget> importTargets,
            List<String> dependencyOrder,
            CanonicalRuntimeTypeDescriptor descriptorEncoder,
            List<FfiImportedFunctionReference> functionRefs,
            List<FfiImportedClassPlanReference> classPlanRefs) {
        if (expr instanceof MemberAccessExpr mae) {
            if (mae.object() instanceof IdentifierExpr id
                    && importTargets.containsKey(id.name())) {
                ImportTarget target = importTargets.get(id.name());
                Type exportType = target.exports().get(mae.field());
                if (exportType instanceof Type.Func) {
                    String canonical = providerDescriptor(descriptorEncoder,
                        exportType);
                    functionRefs.add(new FfiImportedFunctionReference(
                        id.name(), mae.field(), target.modulePath(),
                        canonical,
                        providerFunctionDigest(target.modulePath(),
                            mae.field(), canonical),
                        graphOrderOf(target.modulePath(), dependencyOrder),
                        mae.span().range()));
                } else if (exportType instanceof Type.Class) {
                    String canonical = providerDescriptor(descriptorEncoder,
                        exportType);
                    classPlanRefs.add(new FfiImportedClassPlanReference(
                        id.name(), mae.field(), target.modulePath(),
                        canonical,
                        providerClassPlanDigest(target.modulePath(),
                            mae.field(), canonical),
                        graphOrderOf(target.modulePath(), dependencyOrder),
                        mae.span().range()));
                }
            } else {
                collectImportedReferences(mae.object(), importTargets,
                    dependencyOrder, descriptorEncoder, functionRefs,
                    classPlanRefs);
            }
            return;
        }
        if (expr instanceof CallExpr ce) {
            collectImportedReferences(ce.callee(), importTargets,
                dependencyOrder, descriptorEncoder, functionRefs,
                classPlanRefs);
            for (ExpressionNode arg : ce.args()) {
                collectImportedReferences(arg, importTargets,
                    dependencyOrder, descriptorEncoder, functionRefs,
                    classPlanRefs);
            }
            return;
        }
        if (expr instanceof BinaryExpr be) {
            collectImportedReferences(be.left(), importTargets,
                dependencyOrder, descriptorEncoder, functionRefs,
                classPlanRefs);
            collectImportedReferences(be.right(), importTargets,
                dependencyOrder, descriptorEncoder, functionRefs,
                classPlanRefs);
            return;
        }
        if (expr instanceof UnaryExpr ue) {
            collectImportedReferences(ue.expr(), importTargets,
                dependencyOrder, descriptorEncoder, functionRefs,
                classPlanRefs);
            return;
        }
        if (expr instanceof IndexExpr ie) {
            collectImportedReferences(ie.array(), importTargets,
                dependencyOrder, descriptorEncoder, functionRefs,
                classPlanRefs);
            collectImportedReferences(ie.index(), importTargets,
                dependencyOrder, descriptorEncoder, functionRefs,
                classPlanRefs);
            return;
        }
        if (expr instanceof ArrayLiteralExpr ale) {
            for (ExpressionNode element : ale.elements()) {
                collectImportedReferences(element, importTargets,
                    dependencyOrder, descriptorEncoder, functionRefs,
                    classPlanRefs);
            }
            return;
        }
        if (expr instanceof ObjectLiteralExpr ole) {
            for (deal.ast.Property property : ole.properties()) {
                collectImportedReferences(property.value(), importTargets,
                    dependencyOrder, descriptorEncoder, functionRefs,
                    classPlanRefs);
            }
            return;
        }
        if (expr instanceof FunctionExpr fe) {
            for (StatementNode stmt : fe.body().statements()) {
                collectStatementReferences(stmt, importTargets,
                    dependencyOrder, descriptorEncoder, functionRefs,
                    classPlanRefs);
            }
            return;
        }
        if (expr instanceof HasExpr he) {
            collectImportedReferences(he.object(), importTargets,
                dependencyOrder, descriptorEncoder, functionRefs,
                classPlanRefs);
            return;
        }
        if (expr instanceof AwaitExpression aw) {
            collectImportedReferences(aw.callee(), importTargets,
                dependencyOrder, descriptorEncoder, functionRefs,
                classPlanRefs);
            return;
        }
        if (expr instanceof TemplateLiteralExpr tle) {
            for (ExpressionNode part : tle.parts()) {
                collectImportedReferences(part, importTargets,
                    dependencyOrder, descriptorEncoder, functionRefs,
                    classPlanRefs);
            }
            return;
        }
        if (expr instanceof deal.ast.AssignmentExpr ae) {
            collectImportedReferences(ae.value(), importTargets,
                dependencyOrder, descriptorEncoder, functionRefs,
                classPlanRefs);
        }
        // Identifier/literal leaves carry no imported references.
    }

    /**
     * Statement-level reference collection (function-expression bodies
     * inside defaults): expression statements, returns, throws, and
     * nested blocks reachable from them.
     */
    private static void collectStatementReferences(
            StatementNode stmt,
            Map<String, ImportTarget> importTargets,
            List<String> dependencyOrder,
            CanonicalRuntimeTypeDescriptor descriptorEncoder,
            List<FfiImportedFunctionReference> functionRefs,
            List<FfiImportedClassPlanReference> classPlanRefs) {
        switch (stmt) {
            case deal.ast.ExpressionStatement es ->
                collectImportedReferences(es.expr(), importTargets,
                    dependencyOrder, descriptorEncoder, functionRefs,
                    classPlanRefs);
            case deal.ast.ReturnStatement rs -> rs.expr().ifPresent(e ->
                collectImportedReferences(e, importTargets, dependencyOrder,
                    descriptorEncoder, functionRefs, classPlanRefs));
            case deal.ast.ThrowStatement ts ->
                collectImportedReferences(ts.expr(), importTargets,
                    dependencyOrder, descriptorEncoder, functionRefs,
                    classPlanRefs);
            case deal.ast.VariableDeclaration vd ->
                collectImportedReferences(vd.initializer(), importTargets,
                    dependencyOrder, descriptorEncoder, functionRefs,
                    classPlanRefs);
            case deal.ast.Block b -> {
                for (StatementNode nested : b.statements()) {
                    collectStatementReferences(nested, importTargets,
                        dependencyOrder, descriptorEncoder, functionRefs,
                        classPlanRefs);
                }
            }
            case deal.ast.DeleteStatement ds ->
                collectImportedReferences(ds.target(), importTargets,
                    dependencyOrder, descriptorEncoder, functionRefs,
                    classPlanRefs);
            default -> { /* leaves and control flow: no new references */ }
        }
    }

    /**
     * The canonical descriptor of a provider type through the
     * compilation's single encoder; the intrinsic {@code Error} class —
     * whose identity the checker synthesizes outside the identity
     * assembly — projects to the pinned {@code @$builtin/Error} atom.
     * Any other unregistered identity is an internal invariant
     * violation and propagates.
     */
    private static String providerDescriptor(
            CanonicalRuntimeTypeDescriptor encoder, Type type) {
        try {
            return encoder.encode(type);
        } catch (IllegalStateException e) {
            if (type instanceof Type.Class cls
                    && "Error".equals(cls.name())
                    && cls.identity().moduleIdentity()
                        instanceof deal.identity.CanonicalModuleIdentity
                            .BuiltinModule) {
                return ModuleIdentityAssembly.INTRINSIC_ERROR_DESCRIPTOR_TEXT;
            }
            throw e;
        }
    }

    /** The provider function contract digest over the canonical signature. */
    private static String providerFunctionDigest(String modulePath,
            String exportName, String canonicalDescriptor) {
        return FfiContentSerializer.sha256("FUNCTION_PROVIDER\0"
            + modulePath + "\0" + exportName + "\0" + canonicalDescriptor);
    }

    /** The provider class-plan contract digest over the canonical identity. */
    private static String providerClassPlanDigest(String modulePath,
            String className, String canonicalDescriptor) {
        return FfiContentSerializer.sha256("CLASS_PLAN_PROVIDER\0"
            + modulePath + "\0" + className + "\0" + canonicalDescriptor);
    }

    /** The dependency-order position of a provider module. */
    private static int graphOrderOf(String modulePath,
                                    List<String> dependencyOrder) {
        int index = dependencyOrder.indexOf(modulePath);
        return index < 0 ? dependencyOrder.size() : index;
    }

    /**
     * The semantic resource identity digest of one class default: SHA-256
     * over the canonical length-separated components of the declaring
     * class's private semantic resource identity (module deployment
     * identity, canonical source URI, declaration kind/name/path/range).
     * Only the digest enters plan content — never the raw URI.
     */
    private static String semanticResourceIdentityDigest(
            SemanticModuleIdentity moduleIdentity, String className,
            String modulePath, DiagnosticRange range) {
        SemanticResourceIdentity identity = new SemanticResourceIdentity(
            moduleIdentity, LexicalDeclarationIdentity.DeclarationKind.CLASS,
            new LexicalDeclarationIdentity(
                LexicalDeclarationIdentity.DeclarationKind.CLASS, className,
                modulePath, range));
        String canonical = "SEMANTIC_RESOURCE\0"
            + identity.semanticModuleIdentity().projectDeploymentIdentity()
                .canonicalManifestUri() + "\0"
            + identity.semanticModuleIdentity().projectDeploymentIdentity()
                .validatedManifestContentDigest() + "\0"
            + identity.semanticModuleIdentity().canonicalResolvedSourceUri()
            + "\0"
            + identity.resourceKind().name() + "\0"
            + identity.lexicalDeclarationIdentity().declaredName() + "\0"
            + identity.lexicalDeclarationIdentity()
                .enclosingLexicalDeclarationPath() + "\0"
            + identity.lexicalDeclarationIdentity().sourceScalarRange()
                .startScalarOffset() + "\0"
            + identity.lexicalDeclarationIdentity().sourceScalarRange()
                .endScalarOffset();
        return FfiContentSerializer.sha256(canonical);
    }

    /**
     * Builds the immutable per-class plan record with the three
     * canonical content strings and the plan digest.
     */
    private static FfiCompilerClassDefaultPlan buildPlan(
            ClassInfo info,
            List<FfiCompilerClassDefaultPlan.Entry> entries,
            SemanticModuleIdentity moduleIdentity,
            String modulePath,
            Map<String, List<String>> providerDigestsByField) {
        ClassDeclaration cd = info.declaration;
        List<CanonicalJson.Entry> entryValues = new ArrayList<>();
        List<CanonicalJson.Entry> semanticValues = new ArrayList<>();
        List<CanonicalJson.Entry> evaluatorValues = new ArrayList<>();
        for (FfiCompilerClassDefaultPlan.Entry entry : entries) {
            String resourceDigest = semanticResourceIdentityDigest(
                moduleIdentity, cd.name(), modulePath, cd.span().range());
            List<String> providerDigests = providerDigestsByField
                .getOrDefault(entry.name(), List.of());
            List<CanonicalJson.Value> digestValues = new ArrayList<>();
            for (String digest : providerDigests) {
                digestValues.add(CanonicalJson.str(digest));
            }
            CanonicalJson.Entry providerEntry = CanonicalJson.e(
                "providerDigests", CanonicalJson.arr(digestValues));
            entryValues.add(CanonicalJson.e(entry.name(), CanonicalJson.obj(
                CanonicalJson.e("descriptor",
                    CanonicalJson.str(entry.canonicalDescriptor())),
                CanonicalJson.e("optional",
                    CanonicalJson.bool(entry.optional())),
                CanonicalJson.e("hasDefault",
                    CanonicalJson.bool(entry.hasDefaultEvaluator())),
                CanonicalJson.e("evaluator",
                    entry.hasDefaultEvaluator()
                        ? CanonicalJson.str(entry.evaluatorContent())
                        : CanonicalJson.nullValue()),
                CanonicalJson.e("semanticResourceIdentityDigest",
                    CanonicalJson.str(resourceDigest)),
                providerEntry)));
            semanticValues.add(CanonicalJson.e(entry.name(), CanonicalJson.obj(
                CanonicalJson.e("descriptor",
                    CanonicalJson.str(entry.canonicalDescriptor())),
                CanonicalJson.e("optional",
                    CanonicalJson.bool(entry.optional())),
                CanonicalJson.e("hasDefault",
                    CanonicalJson.bool(entry.hasDefaultEvaluator())),
                CanonicalJson.e("semanticResourceIdentityDigest",
                    CanonicalJson.str(resourceDigest)),
                providerEntry)));
            evaluatorValues.add(CanonicalJson.e(entry.name(),
                CanonicalJson.obj(
                    CanonicalJson.e("hasDefault",
                        CanonicalJson.bool(entry.hasDefaultEvaluator())),
                    CanonicalJson.e("content",
                        entry.hasDefaultEvaluator()
                            ? CanonicalJson.str(entry.evaluatorContent())
                            : CanonicalJson.nullValue()))));
        }
        String canonicalPlanContent = FfiContentSerializer.text(
            CanonicalJson.obj(
                CanonicalJson.e("serializerVersion",
                    CanonicalJson.intValue(
                        FfiContentSerializer.SERIALIZER_VERSION)),
                CanonicalJson.e("classIdentity",
                    CanonicalJson.str(info.identityText)),
                CanonicalJson.e("entries", CanonicalJson.obj(entryValues))));
        String semanticDefaultContents = FfiContentSerializer.text(
            CanonicalJson.obj(
                CanonicalJson.e("serializerVersion",
                    CanonicalJson.intValue(
                        FfiContentSerializer.SERIALIZER_VERSION)),
                CanonicalJson.e("classIdentity",
                    CanonicalJson.str(info.identityText)),
                CanonicalJson.e("entries",
                    CanonicalJson.obj(semanticValues))));
        String evaluatorImplementationContents = FfiContentSerializer.text(
            CanonicalJson.obj(
                CanonicalJson.e("serializerVersion",
                    CanonicalJson.intValue(
                        FfiContentSerializer.SERIALIZER_VERSION)),
                CanonicalJson.e("classIdentity",
                    CanonicalJson.str(info.identityText)),
                CanonicalJson.e("entries",
                    CanonicalJson.obj(evaluatorValues))));
        return new FfiCompilerClassDefaultPlan(
            info.identityText, entries, canonicalPlanContent,
            semanticDefaultContents, evaluatorImplementationContents,
            FfiContentSerializer.sha256(canonicalPlanContent));
    }

    /** The complete canonical module content (descriptor + plans). */
    private static String canonicalModuleContent(
            String moduleKey,
            String canonicalExternalModuleIdentity,
            SemanticModuleIdentity moduleIdentity,
            String nativeLibraryKind,
            String nativeLibraryLoaderText,
            List<FfiFunctionDescriptor> functions,
            List<FfiClassDescriptor> classes,
            Map<String, FfiCompilerClassDefaultPlan> plans) {
        List<CanonicalJson.Value> functionValues = new ArrayList<>();
        for (FfiFunctionDescriptor fn : functions) {
            List<CanonicalJson.Value> params = new ArrayList<>();
            for (FfiType p : fn.orderedParams()) {
                params.add(row(p));
            }
            functionValues.add(CanonicalJson.obj(
                CanonicalJson.e("dealName", CanonicalJson.str(fn.dealName())),
                CanonicalJson.e("cSymbol", CanonicalJson.str(fn.cSymbol())),
                CanonicalJson.e("privateFunctionPointerType",
                    CanonicalJson.str(fn.privateFunctionPointerType())),
                CanonicalJson.e("orderedParams", CanonicalJson.arr(params)),
                CanonicalJson.e("returnType", row(fn.returnType()))));
        }
        List<CanonicalJson.Value> classValues = new ArrayList<>();
        for (FfiClassDescriptor cls : classes) {
            List<CanonicalJson.Value> fields = new ArrayList<>();
            for (FfiFieldDescriptor field : cls.orderedFields()) {
                fields.add(CanonicalJson.obj(
                    CanonicalJson.e("dealName",
                        CanonicalJson.str(field.dealName())),
                    CanonicalJson.e("fieldOrdinal",
                        CanonicalJson.intValue(field.fieldOrdinal())),
                    CanonicalJson.e("type", row(field.type()))));
            }
            classValues.add(CanonicalJson.obj(
                CanonicalJson.e("name", CanonicalJson.str(cls.name())),
                CanonicalJson.e("canonicalClassIdentity",
                    CanonicalJson.str(cls.canonicalClassIdentity())),
                CanonicalJson.e("qualifiedDealDescriptor",
                    CanonicalJson.str(cls.qualifiedDealDescriptor())),
                CanonicalJson.e("kind",
                    CanonicalJson.str(cls.kind().name())),
                CanonicalJson.e("orderedFields", CanonicalJson.arr(fields)),
                CanonicalJson.e("planContent",
                    cls.compilerDefaultPlan() == null
                        ? CanonicalJson.nullValue()
                        : CanonicalJson.str(cls.compilerDefaultPlan()
                            .canonicalPlanContent()))));
        }
        return FfiContentSerializer.text(CanonicalJson.obj(
            CanonicalJson.e("serializerVersion",
                CanonicalJson.intValue(FfiContentSerializer.SERIALIZER_VERSION)),
            CanonicalJson.e("moduleKey", CanonicalJson.str(moduleKey)),
            CanonicalJson.e("canonicalExternalModuleIdentity",
                CanonicalJson.str(canonicalExternalModuleIdentity)),
            CanonicalJson.e("nativeLibraryKind",
                nativeLibraryKind == null
                    ? CanonicalJson.nullValue()
                    : CanonicalJson.str(nativeLibraryKind)),
            CanonicalJson.e("nativeLibraryLoaderText",
                nativeLibraryLoaderText == null
                    ? CanonicalJson.nullValue()
                    : CanonicalJson.str(nativeLibraryLoaderText)),
            CanonicalJson.e("functions", CanonicalJson.arr(functionValues)),
            CanonicalJson.e("classes", CanonicalJson.arr(classValues)),
            CanonicalJson.e("plans",
                CanonicalJson.obj(plans.entrySet().stream()
                    .map(e -> CanonicalJson.e(e.getKey(),
                        CanonicalJson.str(e.getValue()
                            .canonicalPlanContent())))
                    .toList()))));
    }

    /** One canonical FfiType row. */
    private static CanonicalJson.Value row(FfiType type) {
        return CanonicalJson.obj(
            CanonicalJson.e("kind", CanonicalJson.str(type.kind().name())),
            CanonicalJson.e("canonicalDescriptor",
                CanonicalJson.str(type.canonicalDescriptor())),
            CanonicalJson.e("canonicalClassIdentity",
                type.canonicalClassIdentity() == null
                    ? CanonicalJson.nullValue()
                    : CanonicalJson.str(type.canonicalClassIdentity())));
    }

    /** The range of the function declaration with the given name. */
    private static DiagnosticRange fdRangeOf(List<FunctionDeclaration> functions,
                                             String name) {
        for (FunctionDeclaration fd : functions) {
            if (fd.name().equals(name)) {
                return fd.span().range();
            }
        }
        return DiagnosticRange.synthetic("");
    }

    private static boolean hasError(List<CompilerDiagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(
            d -> "error".equals(d.severity()));
    }

    private static CompilerDiagnostic error(String message,
                                            DiagnosticRange range) {
        return CompilerDiagnostic.error(DiagnosticCode.E7002, message, range);
    }
}
