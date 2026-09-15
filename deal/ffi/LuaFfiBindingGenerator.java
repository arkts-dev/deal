package deal.ffi;

import deal.semantic.ir.CanonicalJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The private cdef/generated-binding producer of the extern-C metadata
 * seam (design source
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D7,
 * {@code luajit-ffi-generated-content-seam} S1–S6): consumes one
 * validated immutable {@link FfiModuleDescriptor} and produces the
 * generated bundle/loader inputs —
 *
 * <ul>
 *   <li>the {@link FfiCdefBundle} — full-digest private function-pointer
 *       and struct typedefs, source-order {@code deal_fN} members,
 *       complete canonical {@code fullContent}, exact entry text and
 *       owned-name metadata;</li>
 *   <li>the retained per-class plan records (deferred evaluators, never
 *       invoked);</li>
 *   <li>the {@link FfiForwardBindings} — same-module cells created
 *       {@code UNBOUND} before any plan content work, plus the frozen
 *       graph-ordered imported references.</li>
 * </ul>
 *
 * <p><b>Boundary:</b> this generator never calls {@code ffi.cdef},
 * opens libraries, resolves symbols, or performs ABI calls — it
 * produces data only, and no evaluator executes during generation.</p>
 *
 * <p>Generated names are C-safe by construction: every name starts with
 * the pinned {@code deal_ffi_} prefix followed by the full 64-hex
 * SHA-256 module/signature digest, so no DEAL identifier — including a
 * DEAL name spelled like a C keyword — can ever poison a cdef, and
 * DEAL names themselves never appear in struct members (fields expose
 * the ordinal members {@code deal_fN} only).</p>
 */
public final class LuaFfiBindingGenerator {

    private LuaFfiBindingGenerator() {
        // Static entry; no instances.
    }

    /**
     * The generated binding inputs of one extern-C module: the cdef
     * bundle, the retained plan records (keyed by class identity text),
     * and the forward bindings.
     */
    public record GeneratedBindings(
        FfiCdefBundle cdefBundle,
        Map<String, FfiCompilerClassDefaultPlan> plans,
        FfiForwardBindings bindings) {

        public GeneratedBindings {
            Objects.requireNonNull(cdefBundle, "cdefBundle");
            Objects.requireNonNull(bindings, "bindings");
            plans = Map.copyOf(Objects.requireNonNull(plans, "plans"));
        }
    }

    // =========================================================================
    // Private name formulas (shared with the validator, pure)
    // =========================================================================

    /**
     * The full module/signature digest of one function: SHA-256 over the
     * pinned {@code deal-ffi-function} domain tag, the deal name, and the
     * canonical serialization of the ordered parameter and return rows.
     */
    public static String functionSignatureDigest(String dealName,
            List<FfiType> orderedParams, FfiType returnType) {
        Objects.requireNonNull(dealName, "dealName");
        Objects.requireNonNull(orderedParams, "orderedParams");
        Objects.requireNonNull(returnType, "returnType");
        List<CanonicalJson.Value> params = new ArrayList<>();
        for (FfiType p : orderedParams) {
            params.add(canonicalRow(p));
        }
        String signature = FfiContentSerializer.text(CanonicalJson.obj(
            CanonicalJson.e("params", CanonicalJson.arr(params)),
            CanonicalJson.e("return", canonicalRow(returnType))));
        return FfiContentSerializer.sha256("deal-ffi-function\0"
            + dealName + "\0" + signature);
    }

    /**
     * The private function-pointer typedef name:
     * {@code deal_ffi_<fullSignatureDigest>_fn_<4-digit ordinal>} — the
     * full digest plus the declaration order ordinal.
     */
    public static String functionPointerTypeName(String dealName, int ordinal,
            String signatureDigest) {
        Objects.requireNonNull(dealName, "dealName");
        Objects.requireNonNull(signatureDigest, "signatureDigest");
        if (ordinal < 1) {
            throw new IllegalArgumentException(
                "declaration ordinals start at 1, got " + ordinal);
        }
        return "deal_ffi_" + signatureDigest + "_fn_"
            + String.format("%04d", ordinal);
    }

    /**
     * The private struct typedef name:
     * {@code deal_ffi_<fullStructDigest>_<sanitizedClassName>_t} — the
     * full module/signature digest plus the C-sanitized (lowercase,
     * non-alphanumeric-underscore mapped to {@code _}) DEAL class name.
     * The digest prefix guarantees C-safety for every DEAL spelling,
     * including C keywords.
     */
    public static String structTypeName(String moduleKey, String className,
            List<FfiFieldDescriptor> orderedFields) {
        Objects.requireNonNull(moduleKey, "moduleKey");
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(orderedFields, "orderedFields");
        List<CanonicalJson.Value> fields = new ArrayList<>();
        for (FfiFieldDescriptor field : orderedFields) {
            fields.add(CanonicalJson.obj(
                CanonicalJson.e("name", CanonicalJson.str(field.dealName())),
                CanonicalJson.e("ordinal",
                    CanonicalJson.intValue(field.fieldOrdinal())),
                CanonicalJson.e("kind",
                    CanonicalJson.str(field.type().kind().name()))));
        }
        String signature = FfiContentSerializer.text(CanonicalJson.arr(fields));
        String digest = FfiContentSerializer.sha256(
            "deal-ffi-struct\0" + moduleKey + "\0" + className + "\0"
                + signature);
        return "deal_ffi_" + digest + "_" + sanitizeCIdentifier(className)
            + "_t";
    }

    /** The C-sanitized spelling of a DEAL identifier (lowercase, safe). */
    private static String sanitizeCIdentifier(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = Character.toLowerCase(name.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    /**
     * The C ABI type expression of one FFI row: {@code int32_t},
     * {@code double}, {@code _Bool}, {@code const char *},
     * {@code void *}, {@code void} (null return), or the referenced
     * class's private struct typedef name ({@code C_STRUCT}).
     * {@code BYTES} never yields a single expression — callers expand it
     * into the pinned two-slot form.
     *
     * @param type               the FFI row
     * @param moduleKey          the descriptor's module key (struct-name
     *                           digest domain)
     * @param structFieldsByName class name &rarr; validated struct field
     *                           rows of this module
     * @return the C type expression
     */
    public static String ctypeOf(FfiType type, String moduleKey,
            Map<String, List<FfiFieldDescriptor>> structFieldsByName) {
        Objects.requireNonNull(type, "type");
        return switch (type.kind()) {
            case INT -> "int32_t";
            case NUMBER -> "double";
            case BOOLEAN -> "_Bool";
            case STRING -> "const char *";
            case C_POINTER -> "void *";
            case NULL -> "void";
            case C_STRUCT -> {
                String className = classNameOf(type);
                List<FfiFieldDescriptor> fields = structFieldsByName == null
                    ? null : structFieldsByName.get(className);
                Objects.requireNonNull(fields, "no validated struct field"
                    + " rows for referenced class '" + className + "'");
                yield structTypeName(moduleKey, className, fields);
            }
            case BYTES -> throw new IllegalArgumentException(
                "BYTES has no single C type expression (expand to the"
                    + " pinned two-slot form)");
        };
    }

    /**
     * The ordered C parameter type expressions of one parameter row:
     * one expression for every kind except {@code BYTES}, which expands
     * to the pinned pair {@code const uint8_t *} immediately followed by
     * {@code int32_t}.
     */
    public static List<String> cParameterTypes(FfiType param,
            String moduleKey,
            Map<String, List<FfiFieldDescriptor>> structFieldsByName) {
        Objects.requireNonNull(param, "param");
        if (param.kind() == FfiType.Kind.BYTES) {
            return List.of("const uint8_t *", "int32_t");
        }
        return List.of(ctypeOf(param, moduleKey, structFieldsByName));
    }

    /** The C return type expression of one return row. */
    public static String cReturnType(FfiType returnType, String moduleKey,
            Map<String, List<FfiFieldDescriptor>> structFieldsByName) {
        Objects.requireNonNull(returnType, "returnType");
        if (returnType.kind() == FfiType.Kind.BYTES) {
            throw new IllegalArgumentException(
                "BYTES is not a C FFI return type");
        }
        return ctypeOf(returnType, moduleKey, structFieldsByName);
    }

    /** The class name component of a class-identity descriptor text. */
    private static String classNameOf(FfiType type) {
        String identity = type.canonicalClassIdentity();
        Objects.requireNonNull(identity, "class identity");
        int slash = identity.lastIndexOf('/');
        return slash < 0 ? identity : identity.substring(slash + 1);
    }

    // =========================================================================
    // Generation
    // =========================================================================

    /**
     * Generates the bundle/loader inputs of one validated descriptor.
     *
     * <p>Ordering discipline: the same-module forward cells are created
     * {@code UNBOUND} first — before any plan or cdef content work — and
     * the imported reference lists are frozen in the graph order the
     * validator established. No evaluator is invoked anywhere.</p>
     *
     * @param descriptor           the validated immutable descriptor
     * @param importedFunctions    the graph-ordered imported
     *                             function-wrapper references
     * @param importedClassPlans   the graph-ordered imported class-plan
     *                             references
     * @return the generated bundle, retained plans, and bindings
     */
    public static GeneratedBindings generate(FfiModuleDescriptor descriptor,
            List<FfiImportedFunctionReference> importedFunctions,
            List<FfiImportedClassPlanReference> importedClassPlans) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(importedFunctions, "importedFunctions");
        Objects.requireNonNull(importedClassPlans, "importedClassPlans");

        // 1. Same-module cells first, before evaluator/plan content work:
        // one UNBOUND cell per exported FFI function, in source order.
        Map<String, ForwardFunctionCell> cells = new LinkedHashMap<>();
        for (FfiFunctionDescriptor fn : descriptor.functions()) {
            cells.put(fn.dealName(), new ForwardFunctionCell(fn.dealName()));
        }

        // 2. The forward bindings: frozen UNBOUND cells plus the
        // graph-ordered imported references.
        FfiForwardBindings bindings = new FfiForwardBindings(
            descriptor.moduleKey(), FfiBindingState.UNBOUND, cells,
            importedFunctions, importedClassPlans);

        // 3. Cdef entries in dependency order: struct typedefs first
        // (source order), then function typedefs (declaration order).
        Map<String, List<FfiFieldDescriptor>> structFieldsByName =
            new LinkedHashMap<>();
        List<FfiCdefEntry> entries = new ArrayList<>();
        for (FfiClassDescriptor cls : descriptor.classes()) {
            if (cls.kind() == FfiClassDescriptor.ClassKind.C_STRUCT) {
                structFieldsByName.put(cls.name(), cls.orderedFields());
                String name = structTypeName(descriptor.moduleKey(),
                    cls.name(), cls.orderedFields());
                List<String> members = new ArrayList<>();
                for (FfiFieldDescriptor field : cls.orderedFields()) {
                    members.add(ctypeOf(field.type(),
                        descriptor.moduleKey(), structFieldsByName)
                        + " deal_f" + field.fieldOrdinal() + ";");
                }
                String fullText = "typedef struct { "
                    + String.join(" ", members) + " } " + name + ";";
                entries.add(new FfiCdefEntry(
                    FfiContentSerializer.sha256(fullText), fullText,
                    List.of(name)));
            }
        }
        int ordinal = 0;
        for (FfiFunctionDescriptor fn : descriptor.functions()) {
            ordinal++;
            String signatureDigest = functionSignatureDigest(fn.dealName(),
                fn.orderedParams(), fn.returnType());
            String typedefName = functionPointerTypeName(fn.dealName(),
                ordinal, signatureDigest);
            List<String> cParams = new ArrayList<>();
            for (FfiType p : fn.orderedParams()) {
                cParams.addAll(cParameterTypes(p, descriptor.moduleKey(),
                    structFieldsByName));
            }
            String ret = cReturnType(fn.returnType(), descriptor.moduleKey(),
                structFieldsByName);
            String paramsText = cParams.isEmpty()
                ? "void" : String.join(", ", cParams);
            String fullText = "typedef " + ret + " (*" + typedefName + ")("
                + paramsText + ");";
            entries.add(new FfiCdefEntry(
                FfiContentSerializer.sha256(fullText), fullText,
                List.of(typedefName)));
        }

        // 4. The complete canonical cdef content: every entry text in
        // order, newline-terminated — fully available for collision
        // checks.
        StringBuilder fullContent = new StringBuilder();
        for (FfiCdefEntry entry : entries) {
            fullContent.append(entry.fullText()).append('\n');
        }
        String fullContentText = fullContent.toString();

        FfiCdefBundle bundle = new FfiCdefBundle(
            FfiContentSerializer.sha256(fullContentText),
            FfiContentSerializer.sha256(descriptor.canonicalPlanContent()),
            fullContentText,
            entries,
            descriptor.nativeLibraryKind(),
            descriptor.nativeLibraryLoaderText(),
            descriptor.functions(),
            descriptor.classes());

        return new GeneratedBindings(bundle, descriptor.runtimeDefaultPlans(),
            bindings);
    }

    /** One canonical FfiType row (name-sharing with the validator). */
    private static CanonicalJson.Value canonicalRow(FfiType type) {
        return CanonicalJson.obj(
            CanonicalJson.e("kind", CanonicalJson.str(type.kind().name())),
            CanonicalJson.e("canonicalDescriptor",
                CanonicalJson.str(type.canonicalDescriptor())),
            CanonicalJson.e("canonicalClassIdentity",
                type.canonicalClassIdentity() == null
                    ? CanonicalJson.nullValue()
                    : CanonicalJson.str(type.canonicalClassIdentity())));
    }
}
