package deal.codegen.llvm;

import deal.semantic.DescriptorService;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.ConstructionProvider;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleImportKind;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The declared host-module facts of one project and the generated declaration
 * header of each imported host declaration module
 * ({@code llvm-native-host-abi-and-async} H1, H2, H7).
 *
 * <p>The facts are the declaration surface's own records — the declared export
 * names with their canonical descriptor text in declaration order and the
 * declared classes with their declared fields — reached through the one
 * {@code Type}&rarr;descriptor producer, plus the raw import specifier the
 * importing unit records. Nothing here is re-derived from source text: the
 * declaration surface is the authority and this class projects it for the emitted
 * static data and the generated header.</p>
 *
 * <p>The generated header declares, for one imported host declaration module,
 * exactly the H2 declaration surface a host implementation includes: the
 * module-specifier macro, the registration symbol
 * {@code deal_host_module_<stem>}, the declared class metadata records and the
 * inline typed wrappers over the H2 accessors (one class constructor and one
 * typed field pair per declared field). It includes only
 * {@code deal/native/deal_rt.h}, so a host unit compiles against the published
 * declaration surface and nothing else.</p>
 */
final class LlvmHostModules {

    /** One declared export of one host declaration module. */
    record Export(String name, String descriptor, boolean isAsync) {

        Export {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(descriptor, "descriptor must not be null");
        }

        /** 1 when the declared export names a class rather than a function. */
        boolean isClass() {
            return descriptor.startsWith("@") || descriptor.equals("Error");
        }
    }

    /** One declared field of one declared host class. */
    record Field(String name, String descriptor, boolean optional,
                 RuntimeDescriptor declared) {

        Field {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(descriptor, "descriptor must not be null");
            Objects.requireNonNull(declared, "declared must not be null");
        }
    }

    /** One declared host class: its canonical identity and its declared fields. */
    record DeclaredClass(String name, String identity, List<Field> fields) {

        DeclaredClass {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(identity, "identity must not be null");
            fields = List.copyOf(fields);
        }

        /** The emitted instance layout of one declared host class (H1/R2). */
        ClassLayout layout(String specifier) {
            List<ClassLayout.FieldLayout> declaredFields = new ArrayList<>();
            for (Field field : fields) {
                declaredFields.add(new ClassLayout.FieldLayout(field.name(),
                    field.declared(), !field.optional(),
                    ConstructionProvider.HOST_DECLARATION));
            }
            return new ClassLayout(new ClassId(externalModulePath(specifier),
                name), declaredFields);
        }
    }

    /**
     * One imported host declaration module: the raw specifier the importing
     * unit records (the load's diagnostic identity and the registry pairing),
     * the declaration module's module-id path (the identity its declared
     * classes carry and the path frame labels and diagnostics name), the
     * implementation stem, and the declared surface. The import's published
     * module record answers the raw specifier, which is the text a driver and
     * the generated registry share.
     */
    record Module(ModuleId moduleId, String specifier, String modulePath,
                  String stem, List<Export> exports,
                  List<DeclaredClass> classes) {

        Module {
            Objects.requireNonNull(moduleId, "moduleId must not be null");
            Objects.requireNonNull(specifier, "specifier must not be null");
            Objects.requireNonNull(modulePath, "modulePath must not be null");
            Objects.requireNonNull(stem, "stem must not be null");
            exports = List.copyOf(exports);
            classes = List.copyOf(classes);
        }

        /** The publication-relative path of this module's generated header. */
        String headerPath() {
            return specifier + ".h";
        }

        /** The registration symbol the matched host unit defines exactly once. */
        String registrationSymbol() {
            return "deal_host_module_" + stem;
        }

        /** The macro suffix of this module's declared constants. */
        String macroStem() {
            return stem.toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "_");
        }

        /** The declaration index of one declared export, or -1. */
        int exportIndex(String name) {
            for (int index = 0; index < exports.size(); index++) {
                if (exports.get(index).name().equals(name)) {
                    return index;
                }
            }
            return -1;
        }

        /** The declared class of one canonical identity, or null. */
        DeclaredClass declaredClass(String identity) {
            for (DeclaredClass declared : classes) {
                if (declared.identity().equals(identity)) {
                    return declared;
                }
            }
            return null;
        }
    }

    private final List<Module> modules;
    private final Map<String, Module> bySpecifier;

    /** The empty fact set: a project that imports no host declaration module. */
    LlvmHostModules() {
        this(List.of());
    }

    private LlvmHostModules(List<Module> modules) {
        this.modules = List.copyOf(modules);
        Map<String, Module> index = new LinkedHashMap<>();
        for (Module module : this.modules) {
            index.putIfAbsent(module.specifier(), module);
        }
        this.bySpecifier = Map.copyOf(index);
    }

    /** Every imported host declaration module, in imported-module order. */
    List<Module> modules() {
        return modules;
    }

    /** The facts of one raw specifier, or null when it names no host module. */
    Module bySpecifier(String specifier) {
        return bySpecifier.get(specifier);
    }

    /** The facts of one declaration module identity, or null. */
    Module byModule(ModuleId moduleId) {
        for (Module module : modules) {
            if (module.moduleId().equals(moduleId)) {
                return module;
            }
        }
        return null;
    }

    /**
     * The imported host declaration modules of one project, in imported-module
     * order: every {@code MODULE_IMPORT} of kind {@code HOST} in project, unit
     * and op order, first occurrence per declaration module.
     */
    static LlvmHostModules of(List<ModuleId> modules,
                              Map<ModuleId, LoweredModuleUnit> units,
                              HostDeclarationSurface surface) {
        Objects.requireNonNull(modules, "modules must not be null");
        Objects.requireNonNull(units, "units must not be null");
        Objects.requireNonNull(surface, "surface must not be null");
        List<Module> facts = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (ModuleId module : modules) {
            LoweredModuleUnit unit = units.get(module);
            if (unit == null) {
                continue;
            }
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.MODULE_IMPORT
                        || !(op.payload() instanceof KindPayload
                            .ModuleImportPayload payload)
                        || payload.kind() != ModuleImportKind.HOST) {
                    continue;
                }
                ModuleId declaration = payload.resolvedModule();
                if (!seen.add(declaration.path())) {
                    continue;
                }
                HostDeclarationSurface.DeclarationFacts declarationFacts =
                    surface.modules().get(declaration);
                if (declarationFacts == null) {
                    // An uncovered declaration import: the lowering's
                    // declaration-fact agreement reports it, and this
                    // projection never defaults a missing fact.
                    continue;
                }
                facts.add(new Module(declaration, payload.rawSpecifier(),
                    externalModulePath(payload.rawSpecifier()),
                    stemOf(payload.rawSpecifier()),
                    exportsOf(declaration, declarationFacts),
                    classesOf(payload.rawSpecifier(), declarationFacts)));
            }
        }
        return new LlvmHostModules(facts);
    }

    /** The declaration's exported declarations in declaration order. */
    private static List<Export> exportsOf(ModuleId moduleId,
            HostDeclarationSurface.DeclarationFacts facts) {
        List<Export> exports = new ArrayList<>();
        for (Map.Entry<String, deal.types.Type> entry
                : facts.exports().entrySet()) {
            RuntimeDescriptor declared = DescriptorService.describe(
                entry.getValue());
            exports.add(new Export(entry.getKey(),
                declared.canonicalSpecText(),
                declared instanceof RuntimeDescriptor.Func function
                    && function.isAsync()));
        }
        return exports;
    }

    /** The declaration's declared classes in declaration order. */
    private static List<DeclaredClass> classesOf(String specifier,
            HostDeclarationSurface.DeclarationFacts facts) {
        List<DeclaredClass> classes = new ArrayList<>();
        for (Map.Entry<String, HostDeclarationSurface.DeclaredClass> entry
                : facts.classes().entrySet()) {
            HostDeclarationSurface.DeclaredClass declared = entry.getValue();
            String identity = "@" + externalModulePath(specifier) + "/"
                + entry.getKey();
            List<Field> fields = new ArrayList<>();
            for (HostDeclarationSurface.DeclaredField field
                    : declared.fields()) {
                RuntimeDescriptor declaredField =
                    DescriptorService.describe(field.type());
                fields.add(new Field(field.declaration().name(),
                    declaredField.canonicalSpecText(),
                    field.declaration().optional(), declaredField));
            }
            classes.add(new DeclaredClass(entry.getKey(), identity, fields));
        }
        return classes;
    }

    /** The module path of one external declaration's classes (D4 identity). */
    static String externalModulePath(String specifier) {
        return "$external/" + specifier;
    }

    /** Every declared host class of the project, in imported-module order. */
    List<ClassLayout> classLayouts() {
        List<ClassLayout> layouts = new ArrayList<>();
        for (Module module : modules) {
            for (DeclaredClass declared : module.classes()) {
                layouts.add(declared.layout(module.specifier()));
            }
        }
        return layouts;
    }

    /** The implementation stem of one raw specifier: its last segment. */
    static String stemOf(String specifier) {
        int slash = specifier.lastIndexOf('/');
        return slash < 0 ? specifier : specifier.substring(slash + 1);
    }

    /**
     * The generated declaration header of one imported host declaration module
     * (H1): the specifier macro, the registration extern, the declared class
     * metadata and the inline typed wrappers over the H2 accessors.
     */
    String header(Module module) {
        StringBuilder out = new StringBuilder();
        String macro = module.macroStem();
        String guard = "DEAL_HOST_" + macro + "_H";
        out.append("/*\n")
            .append(" * ").append(module.headerPath())
            .append(" -- generated by the DEAL native backend.\n")
            .append(" *\n")
            .append(" * The declaration surface of the host module '")
            .append(module.specifier()).append("' (H1/H2). A host\n")
            .append(" * implementation includes this header, defines\n")
            .append(" * `").append(module.registrationSymbol())
            .append("` and the declared metadata arrays exactly once, and\n")
            .append(" * is compiled with the published-root include path.\n")
            .append(" */\n");
        out.append("#ifndef ").append(guard).append('\n');
        out.append("#define ").append(guard).append("\n\n");
        out.append("#include \"deal/native/deal_rt.h\"\n\n");
        out.append("/* The raw module specifier the registry entry pairs with this"
            + " header. */\n");
        out.append("#define DEAL_HOST_SPECIFIER_").append(macro).append(" \"")
            .append(module.specifier()).append("\"\n");
        out.append("#define DEAL_HOST_MODULE_PATH_").append(macro).append(" \"")
            .append(module.modulePath()).append("\"\n");
        out.append("#define DEAL_HOST_EXPORT_COUNT_").append(macro).append(' ')
            .append(module.exports().size()).append('\n');
        out.append("#define DEAL_HOST_CLASS_COUNT_").append(macro).append(' ')
            .append(module.classes().size()).append("\n\n");
        out.append("/* The registration record the loader resolves this specifier"
            + " against. */\n");
        out.append("extern const deal_host_module ")
            .append(module.registrationSymbol()).append(";\n\n");
        out.append("/* The declared export metadata, in declaration order. */\n");
        out.append("extern const deal_host_export deal_host_exports_")
            .append(module.stem()).append("[];\n\n");
        for (DeclaredClass declared : module.classes()) {
            String classMacro = macro + "_"
                + declared.name().toUpperCase(java.util.Locale.ROOT);
            out.append("/* The declared class ").append(declared.identity())
                .append(". */\n");
            out.append("#define DEAL_HOST_CLASS_ATOM_").append(classMacro)
                .append(" \"").append(declared.identity()).append("\"\n");
            out.append("#define DEAL_HOST_FIELD_COUNT_").append(classMacro)
                .append(' ').append(declared.fields().size()).append('\n');
            if (!declared.fields().isEmpty()) {
                out.append("extern const deal_host_field deal_host_fields_")
                    .append(module.stem()).append('_').append(declared.name())
                    .append("[];\n");
            }
            out.append("extern const deal_host_class deal_host_class_")
                .append(module.stem()).append('_').append(declared.name())
                .append(";\n\n");
            out.append("/** Construct one ").append(declared.name())
                .append(" with every declared field absent. */\n");
            out.append("static inline deal_status deal_host_")
                .append(module.stem()).append('_').append(declared.name())
                .append("_new(deal_value *out) {\n");
            out.append("  deal_value atom;\n");
            out.append("  deal_status status = deal_class_atom(")
                .append("DEAL_HOST_CLASS_ATOM_").append(classMacro)
                .append(", &atom);\n");
            out.append("  if (status != DEAL_OK) {\n    return status;\n  }\n");
            out.append("  return deal_class_new(atom, out);\n}\n\n");
            for (Field field : declared.fields()) {
                String prefix = "deal_host_" + module.stem() + "_"
                    + declared.name() + "_" + field.name();
                out.append("/** Write the declared field '").append(field.name())
                    .append("' (").append(field.descriptor()).append("). */\n");
                out.append("static inline deal_status ").append(prefix)
                    .append("_set(deal_value object, deal_value value) {\n");
                out.append("  return deal_field_set(object, \"")
                    .append(field.name()).append("\", value);\n}\n");
                out.append("/** Read the declared field '").append(field.name())
                    .append("'. */\n");
                out.append("static inline deal_status ").append(prefix)
                    .append("_get(deal_value object, deal_value *out) {\n");
                out.append("  return deal_field_get(object, \"")
                    .append(field.name()).append("\", out);\n}\n");
            }
            out.append('\n');
        }
        out.append("/* The declared class metadata array, in declaration order. */\n");
        out.append("extern const deal_host_class deal_host_classes_")
            .append(module.stem()).append("[];\n\n");
        out.append("#endif /* ").append(guard).append(" */\n");
        return out.toString();
    }

    /** One declared export's descriptor text, or null when it is undeclared. */
    static String descriptorOf(Module module, String name) {
        int index = module.exportIndex(name);
        return index < 0 ? null : module.exports().get(index).descriptor();
    }
}
