package deal.codegen.lua;

import deal.ast.ExpressionNode;
import deal.ast.LiteralExpr;
import deal.ast.LiteralValue;
import deal.ast.UnaryExpr;
import deal.ast.UnaryOp;
import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.identity.CanonicalClassIdentity;
import deal.module.IdentityDigests;
import deal.types.Type;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The LuaJIT FFI binding generator (emitter page D6, Architecture item 5):
 * owns the {@code // @extern-c} import emission — the private cdef bundle
 * texts with digest-qualified names and {@code deal_fN} ordinal members,
 * the private function-pointer cast spellings, the C-struct plan/evaluator
 * metadata, and the forward-binding cells — serialized into the settled
 * seam shapes of {@code luajit-ffi-generated-content-seam} S1–S6 and
 * consumed by the delegated runtime half {@code __rt.load_ffi(moduleKey,
 * cdefBundle, plans, bindings, file, line, column)}.
 *
 * <p>The generator is the compile-side serializer owner (seam S7(c)):
 * it consumes the declaration module's checked facts (function signatures,
 * class markers/fields, the classified native-library reference) through
 * {@link FfiModuleInput} and emits the four argument literals of the
 * {@code load_ffi} call. It never emits {@code ffi.C[...]} access, never
 * raises {@code FFI_UNSUPPORTED_BACKEND} (LuaJIT is the capable backend),
 * and never computes descriptor text locally — every canonical descriptor
 * comes from the supplied {@link CanonicalRuntimeTypeDescriptor} service.
 *
 * <p>Determinism: all emission iterates source order only; digests are
 * SHA-256 hex indexes carried opaquely by the runtime (adopted D1), and
 * the {@code fullContent} serialization is the complete canonical UTF-8
 * bundle content participating in identity equality.
 */
public final class LuaFfiBindingGenerator {

    private LuaFfiBindingGenerator() {
        // Stateless generator — no instances.
    }

    // =========================================================================
    // Input records (the FFIGEN-owned consumption seam)
    // =========================================================================

    /**
     * One declared exported FFI function: the DEAL export name doubles as
     * the C symbol name (spec-v1.2 §C FFI declaration files), and the
     * resolved function type drives the ABI mapping.
     *
     * @param dealName the exported DEAL function name (also {@code cSymbol})
     * @param type     the resolved function type
     */
    public record FfiFunctionInput(String dealName, Type.Func type) {}

    /**
     * One declared FFI class field with its source-order ordinal and its
     * resolved type; the default expression is present for scalar fields
     * (required-with-defaults, spec §C struct classes).
     *
     * @param optional {@code true} for an optional field — a spec-illegal
     *                 C-struct shape the generator rejects defensively
     */
    public record FfiFieldInput(String dealName, int ordinal, Type type,
                                Optional<ExpressionNode> defaultExpr,
                                boolean optional) {}

    /**
     * One declared FFI class ({@code @c-struct} or {@code @c-pointer}).
     *
     * @param kind {@code "C_STRUCT"} or {@code "C_POINTER"}
     */
    public record FfiClassInput(String name, CanonicalClassIdentity identity,
                                String kind, List<FfiFieldInput> fields) {}

    /**
     * One {@code // @extern-c} import's complete binding content, assembled
     * by the orchestrator from the declaration module's checked facts.
     *
     * @param moduleKey         the adopted module key ({@code "ffi:"} plus
     *                          the canonical external module identity text)
     * @param nativeLibraryKind the classified loader kind
     *                          ({@code BARE_NAME}/{@code ABSOLUTE_PATH}/
     *                          {@code MANIFEST_RELATIVE_PATH})
     * @param loaderText        the exact normalized loader text
     * @param functions         declared functions in source order
     * @param classes           declared classes in source order
     */
    public record FfiModuleInput(String moduleKey, String nativeLibraryKind,
                                 String loaderText,
                                 List<FfiFunctionInput> functions,
                                 List<FfiClassInput> classes) {}

    // =========================================================================
    // Generation result
    // =========================================================================

    /** One unrepresentable FFI shape (a defensive config failure). */
    public record FfiFailure(String message) {}

    /**
     * The four argument literals of the {@code load_ffi} call, in the
     * pinned five-position envelope order (the span triplet is appended
     * by the backend from the import node's span).
     */
    public record LoadCallParts(String moduleKeyLiteral, String bundleLiteral,
                                String plansLiteral, String bindingsLiteral) {}

    /** Either the failure or the complete call parts. */
    public record Generation(FfiFailure failure, LoadCallParts parts) {}

    // =========================================================================
    // Per-function ABI derivation (spec §C FFI ABI mapping)
    // =========================================================================

    /** The FFI type facts of one parameter/return position. */
    private record FfiTypeFacts(String kind, String canonicalDescriptor,
                                String canonicalClassIdentity, String ctype) {}

    /** The generator-owned FFI facts of one declared class. */
    private static final class ClassFacts {
        final FfiClassInput input;
        final String atom;
        /** The private struct typedef name (C_STRUCT only). */
        String structName = null;

        ClassFacts(FfiClassInput input, String atom) {
            this.input = input;
            this.atom = atom;
        }
    }

    /**
     * Maps one DEAL {@link Type} at a parameter or return position to the
     * adopted FFI kind facts (adopted D4 + spec ABI table). A position
     * outside the spec FFI type table (e.g. {@code bytes} return,
     * {@code null} parameter, array/nullable/table, an unknown class)
     * returns a failure.
     */
    private static Object[] ffiTypeFacts(Type type,
                                         CanonicalRuntimeTypeDescriptor descriptors,
                                         Map<CanonicalClassIdentity, ClassFacts> classIndex,
                                         boolean parameterPosition,
                                         String context) {
        String desc = descriptors.encode(type);
        if (type instanceof Type.Int) {
            return new Object[] { new FfiTypeFacts("INT", desc, null, "int32_t") };
        } else if (type instanceof Type.Number) {
            return new Object[] { new FfiTypeFacts("NUMBER", desc, null, "double") };
        } else if (type instanceof Type.Boolean) {
            return new Object[] { new FfiTypeFacts("BOOLEAN", desc, null, "_Bool") };
        } else if (type instanceof Type.String) {
            return new Object[] { new FfiTypeFacts("STRING", desc, null, "const char *") };
        } else if (type instanceof Type.Bytes) {
            if (!parameterPosition) {
                return new Object[] { new FfiFailure(
                    context + ": bytes is not a legal FFI return type") };
            }
            return new Object[] { new FfiTypeFacts("BYTES", desc, null, "const uint8_t *") };
        } else if (type instanceof Type.Null) {
            if (parameterPosition) {
                return new Object[] { new FfiFailure(
                    context + ": null is not a legal FFI parameter type") };
            }
            return new Object[] { new FfiTypeFacts("NULL", desc, null, "void") };
        } else if (type instanceof Type.Class cls) {
            ClassFacts facts = classIndex.get(cls.identity());
            if (facts == null) {
                return new Object[] { new FfiFailure(
                    context + ": class '" + cls.name()
                        + "' is not a same-file @c-struct or @c-pointer declaration") };
            }
            if (facts.input.kind().equals("C_STRUCT")) {
                return new Object[] { new FfiTypeFacts("C_STRUCT",
                    facts.atom, facts.atom, facts.structName) };
            }
            return new Object[] { new FfiTypeFacts("C_POINTER",
                facts.atom, facts.atom, "void *") };
        }
        return new Object[] { new FfiFailure(
            context + ": type '" + desc + "' is not a legal C FFI type") };
    }

    // =========================================================================
    // Generation entry
    // =========================================================================

    /**
     * Generates the complete {@code load_ffi} argument literals for one
     * extern-c import. Total over representable inputs; unrepresentable
     * shapes return a {@link FfiFailure} (the backend reports it as a
     * defensive config failure at the import statement — frontend FFI
     * declaration validation is the owning gate).
     */
    public static Generation generate(FfiModuleInput input,
                                      CanonicalRuntimeTypeDescriptor descriptors) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(descriptors, "descriptors");

        String moduleDigest = sha256Hex(input.moduleKey());
        String digest8 = moduleDigest.length() >= 8
            ? moduleDigest.substring(0, 8) : moduleDigest;

        // Class facts in source order (the descriptor service projects the
        // canonical atom text; the identity index owns the projection).
        Map<CanonicalClassIdentity, ClassFacts> classIndex = new LinkedHashMap<>();
        List<ClassFacts> classFacts = new ArrayList<>();
        for (FfiClassInput c : input.classes()) {
            if (!c.kind().equals("C_STRUCT") && !c.kind().equals("C_POINTER")) {
                return new Generation(new FfiFailure(
                    "class '" + c.name() + "' of FFI module '" + input.moduleKey()
                        + "' has unknown kind '" + c.kind() + "'"), null);
            }
            ClassFacts facts = new ClassFacts(c,
                descriptors.encode(new Type.Class(c.name(), c.identity())));
            classFacts.add(facts);
            classIndex.put(c.identity(), facts);
        }

        // Private struct typedef names with declaration ordinals
        // (adopted D2): deal_<moduleDigest>_st_<ordinal>.
        int structOrdinal = 0;
        for (ClassFacts facts : classFacts) {
            if (facts.input.kind().equals("C_STRUCT")) {
                structOrdinal++;
                facts.structName = String.format("deal_%s_st_%04d",
                    digest8, structOrdinal);
            }
        }

        // Function ordinals (adopted D2): deal_<moduleDigest>_fn_<ordinal>.
        List<String> fnTypeNames = new ArrayList<>();
        List<FfiTypeFacts[]> fnParamFacts = new ArrayList<>();
        List<FfiTypeFacts> fnReturnFacts = new ArrayList<>();
        int fnOrdinal = 0;
        for (FfiFunctionInput fn : input.functions()) {
            fnOrdinal++;
            String context = "function '" + fn.dealName() + "' of FFI module '"
                + input.moduleKey() + "'";
            if (fn.type().isAsync()) {
                return new Generation(new FfiFailure(
                    context + ": async functions are not legal FFI declarations"), null);
            }
            FfiTypeFacts[] params = new FfiTypeFacts[fn.type().paramTypes().size()];
            for (int i = 0; i < params.length; i++) {
                Object[] res = ffiTypeFacts(fn.type().paramTypes().get(i),
                    descriptors, classIndex, true,
                    context + " parameter " + (i + 1));
                if (res[0] instanceof FfiFailure f) {
                    return new Generation(f, null);
                }
                params[i] = (FfiTypeFacts) res[0];
            }
            Object[] retRes = ffiTypeFacts(fn.type().returnType(), descriptors,
                classIndex, false, context + " return type");
            if (retRes[0] instanceof FfiFailure f) {
                return new Generation(f, null);
            }
            FfiTypeFacts ret = (FfiTypeFacts) retRes[0];
            fnTypeNames.add(String.format("deal_%s_fn_%04d", digest8, fnOrdinal));
            fnParamFacts.add(params);
            fnReturnFacts.add(ret);
        }

        // =====================================================================
        // Cdef entries (settled seam S2; adopted D2): struct typedefs first
        // (dependency order — functions may reference them), then function
        // typedefs. Entries never declare a real target-function prototype;
        // struct members are the ordinal names deal_fN; DEAL field names
        // never appear in cdef text.
        // =====================================================================
        List<String[]> structEntries = new ArrayList<>();
        for (ClassFacts facts : classFacts) {
            if (!facts.input.kind().equals("C_STRUCT")) {
                continue;
            }
            StringBuilder text = new StringBuilder("typedef struct {");
            int memberOrdinal = 0;
            for (FfiFieldInput field : facts.input.fields()) {
                Object[] res = ffiTypeFacts(field.type(), descriptors,
                    classIndex, false,
                    "field '" + field.dealName() + "' of class '"
                        + facts.input.name() + "' of FFI module '" + input.moduleKey() + "'");
                if (res[0] instanceof FfiFailure f) {
                    return new Generation(f, null);
                }
                FfiTypeFacts ft = (FfiTypeFacts) res[0];
                if (!ft.kind().equals("INT") && !ft.kind().equals("NUMBER")
                        && !ft.kind().equals("BOOLEAN")
                        && !ft.kind().equals("C_POINTER")) {
                    return new Generation(new FfiFailure(
                        "field '" + field.dealName() + "' of class '"
                            + facts.input.name() + "' of FFI module '"
                            + input.moduleKey() + "' has illegal C-struct field type '"
                            + ft.canonicalDescriptor() + "'"), null);
                }
                if (memberOrdinal > 0) {
                    text.append(" ");
                }
                text.append(ft.ctype()).append(" deal_f")
                    .append(field.ordinal()).append(";");
                memberOrdinal++;
            }
            text.append(" } ").append(facts.structName).append(";");
            structEntries.add(new String[] {
                sha256Hex(text.toString()), text.toString(), facts.structName });
        }
        List<String[]> fnEntries = new ArrayList<>();
        for (int i = 0; i < input.functions().size(); i++) {
            FfiFunctionInput fn = input.functions().get(i);
            FfiTypeFacts[] params = fnParamFacts.get(i);
            FfiTypeFacts ret = fnReturnFacts.get(i);
            String typeName = fnTypeNames.get(i);
            StringBuilder text = new StringBuilder("typedef ")
                .append(ret.ctype()).append(" (*").append(typeName).append(")(");
            boolean first = true;
            for (FfiTypeFacts p : params) {
                if (!first) {
                    text.append(", ");
                }
                first = false;
                text.append(p.ctype());
                if (p.kind().equals("BYTES")) {
                    // The ABI table's two-position bytes row: the pointer
                    // immediately followed by the signed-int32 length.
                    text.append(", int32_t");
                }
            }
            if (first) {
                text.append("void");
            }
            text.append(");");
            fnEntries.add(new String[] {
                sha256Hex(text.toString()), text.toString(), typeName });
        }

        // =====================================================================
        // Function-pointer cast spellings (adopted D2/D4): a bare private
        // typedef name for functions without C-struct positions (the runtime
        // casts verbatim); the full anonymous RET (*)(P1, ...) spelling for
        // C-struct-bearing functions (the runtime splitter rejects a bare
        // typedef name there).
        // =====================================================================
        List<String> fnCasts = new ArrayList<>();
        for (int i = 0; i < input.functions().size(); i++) {
            FfiTypeFacts[] params = fnParamFacts.get(i);
            FfiTypeFacts ret = fnReturnFacts.get(i);
            boolean needsSplit = ret.kind().equals("C_STRUCT");
            for (FfiTypeFacts p : params) {
                if (p.kind().equals("C_STRUCT")) {
                    needsSplit = true;
                }
            }
            if (!needsSplit) {
                fnCasts.add(fnTypeNames.get(i));
                continue;
            }
            StringBuilder cast = new StringBuilder(ret.ctype()).append(" (*)(");
            boolean first = true;
            for (FfiTypeFacts p : params) {
                if (!first) {
                    cast.append(", ");
                }
                first = false;
                cast.append(p.ctype());
                if (p.kind().equals("BYTES")) {
                    cast.append(", int32_t");
                }
            }
            if (first) {
                cast.append("void");
            }
            cast.append(")");
            fnCasts.add(cast.toString());
        }

        // =====================================================================
        // fullContent: the complete canonical UTF-8 bundle content (the
        // identity field cdefBundleContent; adopted D1). FFIGEN-owned
        // deterministic serialization; the runtime never parses it.
        // =====================================================================
        StringBuilder content = new StringBuilder();
        content.append("module\t").append(input.moduleKey()).append('\n');
        content.append("library\t").append(input.nativeLibraryKind())
            .append('\t').append(input.loaderText()).append('\n');
        for (String[] entry : structEntries) {
            content.append("entry\t").append(entry[1]).append('\n');
        }
        for (String[] entry : fnEntries) {
            content.append("entry\t").append(entry[1]).append('\n');
        }
        for (int i = 0; i < input.functions().size(); i++) {
            FfiFunctionInput fn = input.functions().get(i);
            FfiTypeFacts[] params = fnParamFacts.get(i);
            content.append("fn\t").append(fn.dealName()).append('\t')
                .append(fn.dealName()).append('\t').append(fnCasts.get(i));
            for (FfiTypeFacts p : params) {
                content.append('\t').append(p.kind()).append('\t')
                    .append(p.canonicalDescriptor());
            }
            content.append("\t->\t").append(fnReturnFacts.get(i).kind())
                .append('\t').append(fnReturnFacts.get(i).canonicalDescriptor())
                .append('\n');
        }
        for (ClassFacts facts : classFacts) {
            content.append("class\t").append(facts.input.name()).append('\t')
                .append(facts.atom).append('\t').append(facts.input.kind());
            for (FfiFieldInput field : facts.input.fields()) {
                content.append('\t').append(field.ordinal()).append('\t')
                    .append(field.dealName()).append('\t')
                    .append(descriptors.encode(field.type()));
            }
            content.append('\n');
        }
        String fullContent = content.toString();
        String bundleDigest = sha256Hex(fullContent);
        String identityDigest = sha256Hex(input.moduleKey() + "\n"
            + fullContent + "\n" + input.nativeLibraryKind() + "\n"
            + input.loaderText());

        // =====================================================================
        // Plans (settled seam S5; adopted D1/D6): one plan record per
        // C_STRUCT class under its canonical atom; scalar required fields
        // carry evaluator closures over their literal defaults (never
        // invoked at load), C_POINTER fields carry evaluator = nil (their
        // inbound conversion always provides the value — the settled
        // battery shape).
        // =====================================================================
        List<String> planRecordTexts = new ArrayList<>();
        for (ClassFacts facts : classFacts) {
            if (!facts.input.kind().equals("C_STRUCT")) {
                continue;
            }
            StringBuilder plan = new StringBuilder("{");
            StringBuilder planContent = new StringBuilder();
            StringBuilder semanticContent = new StringBuilder();
            StringBuilder evaluatorContent = new StringBuilder();
            boolean firstEntry = true;
            for (FfiFieldInput field : facts.input.fields()) {
                if (!firstEntry) {
                    plan.append(", ");
                }
                firstEntry = false;
                if (field.optional()) {
                    return new Generation(new FfiFailure(
                        "field '" + field.dealName() + "' of class '"
                            + facts.input.name() + "' of FFI module '"
                            + input.moduleKey() + "' is optional (C-struct fields must be required)"), null);
                }
                String fieldDesc = descriptors.encode(field.type());
                String evaluator = null;
                Object[] fieldRes = ffiTypeFacts(field.type(), descriptors,
                    classIndex, false,
                    "field '" + field.dealName() + "' of class '"
                        + facts.input.name() + "' of FFI module '"
                        + input.moduleKey() + "'");
                if (fieldRes[0] instanceof FfiFailure f) {
                    return new Generation(f, null);
                }
                FfiTypeFacts ft = (FfiTypeFacts) fieldRes[0];
                if (!ft.kind().equals("C_POINTER")) {
                    if (field.defaultExpr().isEmpty()) {
                        return new Generation(new FfiFailure(
                            "field '" + field.dealName() + "' of class '"
                                + facts.input.name() + "' of FFI module '"
                                + input.moduleKey() + "' must carry a default"), null);
                    }
                    Object[] def = renderLiteralDefault(field.defaultExpr().get(),
                        "default of field '" + field.dealName() + "' of class '"
                            + facts.input.name() + "' of FFI module '"
                            + input.moduleKey() + "'");
                    if (def[0] instanceof FfiFailure f) {
                        return new Generation(f, null);
                    }
                    evaluator = "function() return " + def[0] + " end";
                }
                // Positional plan entries (the settled battery shape):
                // the runtime walks the plan list with ipairs, so the
                // entries must occupy the array part — never keyed by
                // field name.
                plan.append("{ "
                    + LuaAbi.tableField("name", LuaAbi.stringLiteral(field.dealName()))
                    + ", " + LuaAbi.tableField("descriptor",
                        LuaAbi.stringLiteral(fieldDesc))
                    + ", optional = false"
                    + ", " + LuaAbi.tableField("evaluator",
                        evaluator == null ? "nil" : evaluator)
                    + " }");
                planContent.append(field.dealName()).append('|')
                    .append(fieldDesc).append("|required;");
                semanticContent.append(field.dealName()).append('=')
                    .append(field.defaultExpr().isPresent()
                        ? literalSource(field.defaultExpr().get()) : "<none>")
                    .append(';');
                evaluatorContent.append(field.dealName()).append('=')
                    .append(evaluator == null ? "<none>" : evaluator)
                    .append(';');
            }
            plan.append("}");
            String recordContent = "plan:" + planContent + "\n"
                + "semantic:" + semanticContent + "\n"
                + "evaluators:" + evaluatorContent;
            String planDigest = sha256Hex(recordContent);
            planRecordTexts.add(
                LuaAbi.tableField(facts.atom, "{ "
                    + LuaAbi.tableField("plan", plan.toString())
                    + ", " + LuaAbi.tableField("canonicalPlanContent",
                        LuaAbi.stringLiteral(planContent.toString()))
                    + ", " + LuaAbi.tableField("semanticDefaultContents",
                        LuaAbi.stringLiteral(semanticContent.toString()))
                    + ", " + LuaAbi.tableField("evaluatorImplementationContents",
                        LuaAbi.stringLiteral(evaluatorContent.toString()))
                    + ", " + LuaAbi.tableField("planDigest",
                        LuaAbi.stringLiteral(planDigest))
                    + " }"));
        }

        // =====================================================================
        // Bundle literal (settled seam S1–S4).
        // =====================================================================
        StringBuilder bundle = new StringBuilder("{ ");
        bundle.append(LuaAbi.tableField("bundleDigest",
            LuaAbi.stringLiteral(bundleDigest)));
        bundle.append(", ").append(LuaAbi.tableField("identityDigest",
            LuaAbi.stringLiteral(identityDigest)));
        bundle.append(", ").append(LuaAbi.tableField("fullContent",
            LuaAbi.stringLiteral(fullContent)));
        bundle.append(", ").append(LuaAbi.tableField("nativeLibrary", "{ "
            + LuaAbi.tableField("kind", LuaAbi.stringLiteral(input.nativeLibraryKind()))
            + ", " + LuaAbi.tableField("loaderText",
                LuaAbi.stringLiteral(input.loaderText()))
            + " }"));

        // Entries: structs first (dependency order), then functions.
        StringBuilder entries = new StringBuilder("{ ");
        boolean firstE = true;
        for (String[] entry : structEntries) {
            if (!firstE) {
                entries.append(", ");
            }
            firstE = false;
            entries.append("{ ").append(LuaAbi.tableField("entryDigest",
                LuaAbi.stringLiteral(entry[0])));
            entries.append(", ").append(LuaAbi.tableField("fullText",
                LuaAbi.stringLiteral(entry[1])));
            entries.append(", ").append(LuaAbi.tableField("ownedNames",
                "{ " + LuaAbi.stringLiteral(entry[2]) + " }"));
            entries.append(" }");
        }
        for (String[] entry : fnEntries) {
            if (!firstE) {
                entries.append(", ");
            }
            firstE = false;
            entries.append("{ ").append(LuaAbi.tableField("entryDigest",
                LuaAbi.stringLiteral(entry[0])));
            entries.append(", ").append(LuaAbi.tableField("fullText",
                LuaAbi.stringLiteral(entry[1])));
            entries.append(", ").append(LuaAbi.tableField("ownedNames",
                "{ " + LuaAbi.stringLiteral(entry[2]) + " }"));
            entries.append(" }");
        }
        entries.append(" }");
        bundle.append(", ").append(LuaAbi.tableField("entries",
            entries.toString()));

        // Functions metadata.
        StringBuilder functions = new StringBuilder("{ ");
        for (int i = 0; i < input.functions().size(); i++) {
            if (i > 0) {
                functions.append(", ");
            }
            FfiFunctionInput fn = input.functions().get(i);
            FfiTypeFacts[] params = fnParamFacts.get(i);
            StringBuilder paramsText = new StringBuilder("{ ");
            for (int j = 0; j < params.length; j++) {
                if (j > 0) {
                    paramsText.append(", ");
                }
                paramsText.append(ffiTypeLiteral(params[j]));
            }
            paramsText.append(" }");
            functions.append("{ ").append(LuaAbi.tableField("dealName",
                LuaAbi.stringLiteral(fn.dealName())));
            functions.append(", ").append(LuaAbi.tableField("cSymbol",
                LuaAbi.stringLiteral(fn.dealName())));
            functions.append(", ").append(LuaAbi.tableField("privateFunctionPointerType",
                LuaAbi.stringLiteral(fnCasts.get(i))));
            functions.append(", ").append(LuaAbi.tableField("orderedParams",
                paramsText.toString()));
            functions.append(", ").append(LuaAbi.tableField("returnType",
                ffiTypeLiteral(fnReturnFacts.get(i))));
            functions.append(" }");
        }
        functions.append(" }");
        bundle.append(", ").append(LuaAbi.tableField("functions",
            functions.toString()));

        // Classes metadata.
        StringBuilder classes = new StringBuilder("{ ");
        int classI = 0;
        for (ClassFacts facts : classFacts) {
            if (classI > 0) {
                classes.append(", ");
            }
            classI++;
            StringBuilder fieldsText = new StringBuilder("{ ");
            for (int j = 0; j < facts.input.fields().size(); j++) {
                if (j > 0) {
                    fieldsText.append(", ");
                }
                FfiFieldInput field = facts.input.fields().get(j);
                FfiTypeFacts ft = (FfiTypeFacts) ffiTypeFacts(field.type(),
                    descriptors, classIndex, false, "")[0];
                fieldsText.append("{ ").append(LuaAbi.tableField("dealName",
                    LuaAbi.stringLiteral(field.dealName())));
                fieldsText.append(", ").append(LuaAbi.tableField("fieldOrdinal",
                    Integer.toString(field.ordinal())));
                fieldsText.append(", ").append(LuaAbi.tableField("type",
                    ffiTypeLiteral(ft)));
                fieldsText.append(" }");
            }
            fieldsText.append(" }");
            classes.append("{ ").append(LuaAbi.tableField("name",
                LuaAbi.stringLiteral(facts.input.name())));
            classes.append(", ").append(LuaAbi.tableField("canonicalClassIdentity",
                LuaAbi.stringLiteral(facts.atom)));
            classes.append(", ").append(LuaAbi.tableField("qualifiedDealDescriptor",
                LuaAbi.stringLiteral(facts.atom)));
            classes.append(", ").append(LuaAbi.tableField("kind",
                LuaAbi.stringLiteral(facts.input.kind())));
            classes.append(", ").append(LuaAbi.tableField("orderedFields",
                fieldsText.toString()));
            classes.append(" }");
        }
        classes.append(" }");
        bundle.append(", ").append(LuaAbi.tableField("classes",
            classes.toString()));
        bundle.append(" }");

        // =====================================================================
        // Plans literal and forward bindings (settled seam S5–S6).
        // =====================================================================
        StringBuilder plans = new StringBuilder("{ ");
        for (int i = 0; i < planRecordTexts.size(); i++) {
            if (i > 0) {
                plans.append(", ");
            }
            plans.append(planRecordTexts.get(i));
        }
        plans.append(" }");

        StringBuilder bindings = new StringBuilder("{ ");
        bindings.append(LuaAbi.tableField("moduleKey",
            LuaAbi.stringLiteral(input.moduleKey())));
        bindings.append(", ").append(LuaAbi.tableField("state",
            LuaAbi.stringLiteral("UNBOUND")));
        StringBuilder cells = new StringBuilder("{ ");
        for (int i = 0; i < input.functions().size(); i++) {
            if (i > 0) {
                cells.append(", ");
            }
            FfiFunctionInput fn = input.functions().get(i);
            cells.append(LuaAbi.tableField(fn.dealName(),
                "{ state = \"UNBOUND\", wrapper = nil, errorValue = nil }"));
        }
        cells.append(" }");
        bindings.append(", ").append(LuaAbi.tableField("cells",
            cells.toString()));
        bindings.append(" }");

        return new Generation(null, new LoadCallParts(
            LuaAbi.stringLiteral(input.moduleKey()),
            bundle.toString(), plans.toString(), bindings.toString()));
    }

    // =========================================================================
    // Literal helpers
    // =========================================================================

    /** The settled FfiType table literal { kind, canonicalDescriptor,
     * canonicalClassIdentity }. */
    private static String ffiTypeLiteral(FfiTypeFacts facts) {
        return "{ " + LuaAbi.tableField("kind",
            LuaAbi.stringLiteral(facts.kind()))
            + ", " + LuaAbi.tableField("canonicalDescriptor",
                LuaAbi.stringLiteral(facts.canonicalDescriptor()))
            + ", " + LuaAbi.tableField("canonicalClassIdentity",
                facts.canonicalClassIdentity() == null ? "nil"
                    : LuaAbi.stringLiteral(facts.canonicalClassIdentity()))
            + " }";
    }

    /**
     * Renders a C-struct scalar default expression as a Lua literal
     * ({@code [1] == null} carries the rendered text, else the
     * {@link FfiFailure}). The restricted grammar: int/number/boolean
     * literals with an optional unary minus — the declarable defaults of
     * an extern-C declaration file.
     */
    private static Object[] renderLiteralDefault(ExpressionNode expr, String context) {
        if (expr instanceof LiteralExpr lit) {
            String rendered = renderLiteral(lit);
            if (rendered != null) {
                return new Object[] { rendered };
            }
        } else if (expr instanceof UnaryExpr un
                && un.op() == UnaryOp.NEG
                && un.expr() instanceof LiteralExpr lit) {
            String inner = renderLiteral(lit);
            if (inner != null) {
                return new Object[] { "(-" + inner + ")" };
            }
        }
        return new Object[] { new FfiFailure(
            context + " is not a literal int/number/boolean expression") };
    }

    /** The literal source text used in semanticDefaultContents. */
    private static String literalSource(ExpressionNode expr) {
        if (expr instanceof LiteralExpr lit) {
            String rendered = renderLiteral(lit);
            if (rendered != null) {
                return rendered;
            }
        } else if (expr instanceof UnaryExpr un
                && un.op() == UnaryOp.NEG
                && un.expr() instanceof LiteralExpr lit) {
            String inner = renderLiteral(lit);
            if (inner != null) {
                return "(-" + inner + ")";
            }
        }
        return "<non-literal>";
    }

    /** One int/number/boolean literal to Lua source, or null. */
    private static String renderLiteral(LiteralExpr lit) {
        return switch (lit.value()) {
            case LiteralValue.IntLiteral i -> Long.toString(i.value());
            case LiteralValue.NumberLiteral n -> {
                double v = n.value();
                if (Double.isNaN(v)) {
                    yield "(0/0)";
                }
                if (Double.isInfinite(v)) {
                    yield v > 0 ? "(1/0)" : "(-1/0)";
                }
                yield Double.toString(v);
            }
            case LiteralValue.BooleanLiteral b -> b.value() ? "true" : "false";
            default -> null;
        };
    }

    /**
     * SHA-256 hex digest of a UTF-8 text (opaque index only; adopted D1) —
     * computed through the compiler-wide digest facility
     * {@link IdentityDigests} so the SHA-256 implementation site stays
     * the pinned single facility.
     */
    private static String sha256Hex(String text) {
        return IdentityDigests.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }
}
