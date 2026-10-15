package deal.semantic;

import deal.diagnostics.CompilerDiagnostic;
import deal.semantic.ir.BindingGeneration;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryRealization;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.ClassFactoryId;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassInterface;
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.DefaultOwner;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FieldInterface;
import deal.semantic.ir.JsonDefaultChildTable;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.RawOp;
import deal.semantic.ir.RawUnit;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SharedFactoryFacts;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ClassConstructionValidator {

    public static final String FACTORY_COHERENCE = "FACTORY_COHERENCE";

    public static final String CONSTRUCTION_COHERENCE = "CONSTRUCTION_COHERENCE";

    public static final String FIELD_OPERATION_SHAPE = "FIELD_OPERATION_SHAPE";

    public static final String DEFAULT_BLOCK_ADMISSION = "DEFAULT_BLOCK_ADMISSION";

    public static final String JSON_LAYOUT_COHERENCE = "JSON_LAYOUT_COHERENCE";

    private ClassConstructionValidator() {
        // Static surface; no instances.
    }

    public static Optional<CompilerDiagnostic> validate(
            LoweredModuleUnit unit,
            StructuredBodyTable table,
            ClassFactoryRegistry factories,
            JsonDefaultChildTable jsonDefaults,
            ExternalModuleInterface ownInterface,
            Map<ClassId, SharedFactoryFacts> sharedFactories) {
        return validate(unit, table, factories, jsonDefaults, ownInterface,
            sharedFactories, Map.of());
    }

    public static Optional<CompilerDiagnostic> validate(
            LoweredModuleUnit unit,
            StructuredBodyTable table,
            ClassFactoryRegistry factories,
            JsonDefaultChildTable jsonDefaults,
            ExternalModuleInterface ownInterface,
            Map<ClassId, SharedFactoryFacts> sharedFactories,
            Map<ClassId, ClassRegistrationSeeds.ClassRegistration> declarationClasses) {
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(factories, "factories must not be null");
        Objects.requireNonNull(jsonDefaults, "jsonDefaults must not be null");
        Objects.requireNonNull(ownInterface, "ownInterface must not be null");
        Objects.requireNonNull(sharedFactories, "sharedFactories must not be null");
        Objects.requireNonNull(declarationClasses,
            "declarationClasses must not be null");
        Context context = new Context(unit, table, factories, jsonDefaults, ownInterface,
            sharedFactories, declarationClasses);
        Optional<CompilerDiagnostic> failure = context.checkFactoryCoherence();
        if (failure.isPresent()) {
            return failure;
        }
        failure = context.checkConstructionCoherence();
        if (failure.isPresent()) {
            return failure;
        }
        failure = context.checkFieldOperationShapes();
        if (failure.isPresent()) {
            return failure;
        }
        failure = context.checkDefaultBlockAdmission();
        if (failure.isPresent()) {
            return failure;
        }
        return context.checkJsonLayoutCoherence();
    }

    // =========================================================================
    // 6. Project seed/interface coherence (the transported declaration facts)
    // =========================================================================

    /**
     * The project-level seed/interface coherence rule of the transported
     * declaration facts (ISSUE-0818): the class registration seeds and the
     * {@code deal.semantic-interface/1} index are two representations of
     * the same declaration surface, so a registration that contradicts the
     * supplied interface facts is rejected content, never a second class
     * authority. The class validation owner applies this join over the two
     * records:
     *
     * <ul>
     *   <li>a registration's key names its contained layout — one class
     *       identity per registration — and carries a declaration owner
     *       ({@link DefaultOwner#HOST_DEFAULTS}/{@link DefaultOwner#FFI_PLAN});</li>
     *   <li>a carried builtin {@code Error} registration is exactly the
     *       compiler-owned layout under the builtin owner (an absent entry is
     *       not by itself a contradiction: the {@code /2} seed collection
     *       carries its empty form as a real fact);</li>
     *   <li>every exported class of a host declaration index entry
     *       carries exactly one registration;</li>
     *   <li>a registration the index declares repeats that interface
     *       class's fields exactly: the same names in the same order,
     *       {@code required} = {@code !optional}, and a descriptor whose
     *       declared-type rendering is the entry's {@code declaredType}.
     *       A descriptor class reference resolves through the exact class
     *       identity the carried registration seeds name — never through a
     *       spelling that merely renders the same dotted text — and a
     *       descriptor that names a class no carried registration covers
     *       is such a contradiction;</li>
     *   <li>a registration resolves to the declared class of a host
     *       declaration entry, never to an implementation or stdlib class.
     *       A registration that resolves to no interface class is not by
     *       itself a contradiction: the index declares exported classes
     *       only, while a declaration module's non-exported class carries
     *       a registration too.</li>
     * </ul>
     *
     * <p>The check only reads the supplied records: layouts are compared,
     * never merged into a unit's {@code classLayouts} and never repaired,
     * and nothing is added to the transported content. The reason is a
     * transport-level fact report — the decode surface owns the exception
     * type.</p>
     *
     * @return the first violation, or empty when the seeds and the index
     *         agree on every supplied fact
     */
    public static Optional<String> checkProjectSeedCoherence(
            ProjectInterfaceIndex index, ClassRegistrationSeeds seeds) {
        Objects.requireNonNull(index, "index must not be null");
        Objects.requireNonNull(seeds, "seeds must not be null");

        // (a) A carried builtin Error registration: exact layout, exact owner —
        // the compiler-owned constant, never a module-owned registration. An
        // absent entry is a legal empty class fact of the /2 seed collection,
        // not a contradiction between two supplied records.
        ClassRegistrationSeeds.ClassRegistration error =
            seeds.registrationFor(ClassId.ERROR);
        if (error != null && (error.owner() != DefaultOwner.BUILTIN_DEFAULTS
                || !error.layout().equals(ClassLayout.BUILTIN_ERROR))) {
            return Optional.of("the builtin " + ClassId.ERROR.text()
                + " registration carries owner " + error.owner() + " and layout "
                + error.layout() + "; the compiler-owned registration is exactly "
                + ClassLayout.BUILTIN_ERROR + " under " + DefaultOwner.BUILTIN_DEFAULTS);
        }

        // (b) Every exported class of a host declaration entry carries exactly
        // one registration.
        for (ExternalModuleInterface module : index.modules().values()) {
            if (module.kind() != ExternalModuleKind.HOST) {
                continue;
            }
            for (ClassInterface classEntry : module.classes()) {
                int matches = 0;
                for (ClassId key : seeds.registrations().keySet()) {
                    if (key.equals(ClassId.ERROR)) {
                        continue;
                    }
                    LocatedDeclaredClass declared = locateDeclaredClass(index, key);
                    if (declared != null
                            && declared.classEntry().equals(classEntry)) {
                        matches++;
                    }
                }
                if (matches == 0) {
                    return Optional.of("the host declaration class "
                        + classEntry.classId() + " of " + module.moduleId()
                        + " has no class registration seed");
                }
                if (matches > 1) {
                    return Optional.of("the host declaration class "
                        + classEntry.classId() + " of " + module.moduleId()
                        + " carries " + matches + " class registration seeds;"
                        + " one declared class carries exactly one registration");
                }
            }
        }

        // (c) Every registration: one identity per registration, a declaration
        // owner, and — where the supplied index declares the class — the exact
        // declared fields in declaration order.
        for (Map.Entry<ClassId, ClassRegistrationSeeds.ClassRegistration> entry
                : seeds.registrations().entrySet()) {
            ClassId key = entry.getKey();
            ClassRegistrationSeeds.ClassRegistration registration = entry.getValue();
            if (key.equals(ClassId.ERROR)) {
                continue;
            }
            if (!key.equals(registration.layout().classId())) {
                return Optional.of("the " + key + " registration carries layout "
                    + registration.layout().classId()
                    + "; a registration's key and its contained layout name one "
                    + "class identity");
            }
            if (registration.owner() != DefaultOwner.HOST_DEFAULTS
                    && registration.owner() != DefaultOwner.FFI_PLAN) {
                return Optional.of("the " + key + " registration carries owner "
                    + registration.owner() + "; a declaration class registration is"
                    + " owned by " + DefaultOwner.HOST_DEFAULTS + " or "
                    + DefaultOwner.FFI_PLAN);
            }
            LocatedDeclaredClass declared = locateDeclaredClass(index, key);
            if (declared == null) {
                continue;
            }
            if (declared.module().kind() != ExternalModuleKind.HOST) {
                return Optional.of("the " + key + " registration names the "
                    + declared.module().kind() + " module "
                    + declared.module().moduleId()
                    + "; a declaration class registration covers host declaration"
                    + " classes only");
            }
            Optional<String> fields =
                checkSeedFields(key, registration.layout(), declared, index, seeds);
            if (fields.isPresent()) {
                return fields;
            }
        }
        return Optional.empty();
    }

    /**
     * One project seed/module coherence violation of the carried class
     * facts: the referring unit, the referring construction/field operation and
     * the transport fact report. The referring operation is the
     * responsible subject of the violation, so the admission surface
     * carries its retained origin.
     *
     */
    public record SeedModuleViolation(RawUnit referringUnit, RawOp referringOp,
                                      String detail) {

        public SeedModuleViolation {
            Objects.requireNonNull(referringUnit, "referringUnit must not be null");
            Objects.requireNonNull(referringOp, "referringOp must not be null");
            Objects.requireNonNull(detail, "detail must not be null");
        }

        /** The violation as the transport decode surface reports it. */
        public String reason() {
            return "module '" + referringUnit.modulePath() + "' "
                + referringOp.opId() + ": " + detail;
        }
    }

    /**
     * The project-level seed/module join of the transported class facts:
     * every carried construction of a declaration class — a
     * {@code CLASS_NEW} whose {@code defaultOwner} is a declaration owner
     * ({@link DefaultOwner#HOST_DEFAULTS} or {@link DefaultOwner#FFI_PLAN})
     * — refers to exactly one carried class registration seed, and that
     * registration repeats the referring operation's owner and contained
     * layout. The transport carries the seeds and the modules as separate
     * records, so a disagreement between the two is rejected content:
     * neither record is repaired and neither becomes an independent class
     * authority.
     *
     * <p>The check only reads the supplied records: the registration's
     * layout is compared through the canonical layout rendering (the same
     * rendering the carried operation payload uses), never merged into a
     * unit's {@code classLayouts}, and a payload shape outside the carried
     * construction record is left to the closed rule engine that owns
     * it.</p>
     *
     * @return the first violation in supplied unit order and source
     *         operation order, or empty when the seeds and the referring
     *         module class facts agree
     */
    public static Optional<SeedModuleViolation> checkProjectSeedModuleCoherence(
            ClassRegistrationSeeds seeds, List<RawUnit> units) {
        Objects.requireNonNull(seeds, "seeds must not be null");
        Objects.requireNonNull(units, "units must not be null");
        for (RawUnit unit : units) {
            Objects.requireNonNull(unit, "units must not contain null");
            Map<OpId, List<RawOp>> boundaries = new LinkedHashMap<>();
            for (RawOp child : unit.ops()) {
                if (child.parentOpId() != null
                        && SemanticOpKind.BOUNDARY.name().equals(child.kind())) {
                    boundaries.computeIfAbsent(child.parentOpId(), ignored -> new ArrayList<>())
                        .add(child);
                }
            }
            for (RawOp op : unit.ops()) {
                if (SemanticOpKind.FIELD_READ.name().equals(op.kind())
                        || SemanticOpKind.FIELD_WRITE.name().equals(op.kind())
                        || SemanticOpKind.FIELD_DELETE.name().equals(op.kind())) {
                    Optional<SeedModuleViolation> fieldFailure =
                        checkSeedFieldOperation(seeds, unit, op,
                            boundaries.getOrDefault(op.opId(), List.of()));
                    if (fieldFailure.isPresent()) {
                        return fieldFailure;
                    }
                }
                if (!SemanticOpKind.CLASS_NEW.name().equals(op.kind())) {
                    continue;
                }
                String owner = stringPayloadField(op.payload(), "defaultOwner");
                if (!DefaultOwner.HOST_DEFAULTS.name().equals(owner)
                        && !DefaultOwner.FFI_PLAN.name().equals(owner)) {
                    continue;
                }
                ClassId classId = classIdPayloadField(op.payload(), "classId");
                if (classId == null) {
                    continue; // the closed rules own a malformed payload shape
                }
                ClassRegistrationSeeds.ClassRegistration registration =
                    seeds.registrationFor(classId);
                if (registration == null) {
                    return Optional.of(new SeedModuleViolation(unit, op,
                        "the CLASS_NEW carries defaultOwner " + owner + " for "
                            + classId + ", which no class registration seed registers:"
                            + " a declaration-class construction is never admissible"
                            + " without its registration"));
                }
                if (!registration.owner().name().equals(owner)) {
                    return Optional.of(new SeedModuleViolation(unit, op,
                        "the CLASS_NEW carries defaultOwner " + owner + " for "
                            + classId + " while its class registration seed carries"
                            + " owner " + registration.owner()
                            + ": the referring module class fact and the seed"
                            + " disagree"));
                }
                CanonicalJson.Value layout = payloadField(op.payload(), "layout");
                if (layout == null || !layout.equals(
                        ContractSnapshotCanonicalizer.layoutJson(
                            registration.layout()))) {
                    return Optional.of(new SeedModuleViolation(unit, op,
                        "the CLASS_NEW carries a layout that differs from the"
                            + " registered declaration layout of " + classId
                            + " (the registered fields in declaration order, their"
                            + " presence and default owners): the referring module"
                            + " class fact and the seed disagree"));
                }
            }
        }
        return Optional.empty();
    }

    /** Declaration seeds and field operations describe the same layout, even
     * when a private declaration class has no construction or index export. */
    private static Optional<SeedModuleViolation> checkSeedFieldOperation(
            ClassRegistrationSeeds seeds, RawUnit unit, RawOp op,
            List<RawOp> boundaries) {
        ClassId classId = classIdPayloadField(op.payload(), "classId");
        ClassRegistrationSeeds.ClassRegistration registration = classId == null
            ? null : seeds.registrationFor(classId);
        if (registration == null) {
            return Optional.empty(); // other owners resolve non-seed classes
        }
        String name = stringPayloadField(op.payload(), "field");
        if (name == null) {
            return Optional.empty(); // closed payload rules own malformed shapes
        }
        ClassLayout.FieldLayout field = registration.layout().fields().stream()
            .filter(candidate -> candidate.name().equals(name)).findFirst().orElse(null);
        if (field == null) {
            return Optional.of(new SeedModuleViolation(unit, op,
                "the " + op.kind() + " names unregistered field " + classId + "." + name));
        }
        boolean read = SemanticOpKind.FIELD_READ.name().equals(op.kind());
        boolean delete = SemanticOpKind.FIELD_DELETE.name().equals(op.kind());
        if (delete && field.required()) {
            return Optional.of(new SeedModuleViolation(unit, op,
                "the FIELD_DELETE names required declaration field " + classId + "." + name));
        }
        RuntimeDescriptor descriptor = field.descriptor();
        if (read && !field.required() && !(descriptor instanceof RuntimeDescriptor.Nullable)) {
            descriptor = new RuntimeDescriptor.Nullable(descriptor);
        }
        String expected = descriptor.canonicalSpecText();
        if (read && !expected.equals(op.resultType())) {
            return Optional.of(new SeedModuleViolation(unit, op,
                "the FIELD_READ result for " + classId + "." + name
                    + " disagrees with its registered declaration descriptor " + expected));
        }
        String boundaryKind = read ? BoundaryKind.OPTIONAL_FIELD_READ.name()
            : delete ? null : BoundaryKind.CLASS_FIELD_ASSIGNMENT.name();
        for (RawOp child : boundaries) {
            if (boundaryKind != null
                    && boundaryKind.equals(stringPayloadField(child.payload(), "kind"))
                    && !expected.equals(stringPayloadField(child.payload(), "descriptor"))) {
                return Optional.of(new SeedModuleViolation(unit, child,
                    "the " + boundaryKind + " for " + classId + "." + name
                        + " disagrees with its registered declaration descriptor " + expected));
            }
        }
        return Optional.empty();
    }

    /** One payload member of a carried operation payload, or null. */
    private static CanonicalJson.Value payloadField(CanonicalJson.Obj payload, String key) {
        for (CanonicalJson.Entry entry : payload.entries()) {
            if (entry.key().equals(key)) {
                return entry.value();
            }
        }
        return null;
    }

    /** One string payload member, or null when absent or not a string. */
    private static String stringPayloadField(CanonicalJson.Obj payload, String key) {
        return payloadField(payload, key) instanceof CanonicalJson.Str str
            ? str.value()
            : null;
    }

    /** One class-identity payload member, or null when absent or malformed. */
    private static ClassId classIdPayloadField(CanonicalJson.Obj payload, String key) {
        if (!(payloadField(payload, key) instanceof CanonicalJson.Obj classId)) {
            return null;
        }
        try {
            return ContractSnapshotCanonicalizer.parseClassId(classId);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** The {@code $external/} descriptor spelling prefix of external modules. */
    private static final String EXTERNAL_MODULE_PREFIX = "$external/";

    /**
     * One interface class entry a registration resolves to, with its owning
     * module entry.
     */
    private record LocatedDeclaredClass(ExternalModuleInterface module,
                                        ClassInterface classEntry) {
    }

    /**
     * The interface class entry one registration key names: the entry whose
     * class identity the key carries, or — for the externals spelling
     * {@code @$external/<specifier>/<Name>} — the same-named exported class
     * of the declaration module the raw specifier resolves to (the dotted
     * module path is the specifier with {@code '/' → '.'}, the module layer's
     * pinned external module path). Absent for a key the index declares at
     * no entry.
     */
    private static LocatedDeclaredClass locateDeclaredClass(ProjectInterfaceIndex index,
                                                           ClassId key) {
        for (ExternalModuleInterface module : index.modules().values()) {
            for (ClassInterface entry : module.classes()) {
                if (entry.classId().equals(key)) {
                    return new LocatedDeclaredClass(module, entry);
                }
            }
        }
        if (!key.modulePath().startsWith(EXTERNAL_MODULE_PREFIX)) {
            return null;
        }
        String specifier =
            key.modulePath().substring(EXTERNAL_MODULE_PREFIX.length());
        if (specifier.isEmpty()) {
            return null;
        }
        ModuleId declaredModule = new ModuleId(specifier.replace('/', '.'));
        ExternalModuleInterface module = index.modules().get(declaredModule);
        if (module == null) {
            return null;
        }
        for (ClassInterface entry : module.classes()) {
            if (entry.classId().name().equals(key.name())) {
                return new LocatedDeclaredClass(module, entry);
            }
        }
        return null;
    }

    /**
     * The registered layout repeated against the interface class entry: field
     * names and order, required/optional presence, and the descriptor's
     * declared-type rendering.
     */
    private static Optional<String> checkSeedFields(ClassId key, ClassLayout layout,
            LocatedDeclaredClass declared, ProjectInterfaceIndex index,
            ClassRegistrationSeeds seeds) {
        List<ClassLayout.FieldLayout> fields = layout.fields();
        List<FieldInterface> declaredFields = declared.classEntry().fields();
        if (fields.size() != declaredFields.size()) {
            return Optional.of("the " + key + " registration carries "
                + fields.size() + " field(s) while its interface class "
                + declared.classEntry().classId() + " declares "
                + declaredFields.size());
        }
        for (int i = 0; i < fields.size(); i++) {
            ClassLayout.FieldLayout field = fields.get(i);
            FieldInterface declaredField = declaredFields.get(i);
            if (!field.name().equals(declaredField.name())) {
                return Optional.of("the " + key + " registration names field '"
                    + field.name() + "' at position " + i + " while its interface class "
                    + declared.classEntry().classId() + " declares '"
                    + declaredField.name() + "' there (declaration order)");
            }
            if (field.required() == declaredField.optional()) {
                return Optional.of("the " + key + " registration marks field '"
                    + field.name() + "' required=" + field.required()
                    + " while its interface class declares optional="
                    + declaredField.optional()
                    + "; a registered field is required exactly when it is not"
                    + " optional");
            }
            String declaredText = declaredTypeText(field.descriptor(), index, seeds);
            if (declaredText == null || !declaredText.equals(declaredField.declaredType())) {
                return Optional.of("the " + key + " registration carries field '"
                    + field.name() + "' with descriptor '"
                    + field.descriptor().canonicalSpecText() + "', whose declared type is "
                    + (declaredText == null
                        ? "unresolvable in the interface index"
                        : "'" + declaredText + "'")
                    + ", while its interface class declares '"
                    + declaredField.declaredType() + "'");
            }
        }
        return Optional.empty();
    }

    /**
     * The interface's declared-type text of one descriptor (the pinned
     * {@code CanonicalTypeText} grammar over the descriptor's structural
     * type), or null when the descriptor names a class the supplied index
     * declares at no entry. A class reference resolves through the exact
     * identity the carried class registration seeds name, never through a
     * spelling that merely renders the same dotted text: the declaration
     * surface registers every declared class of a declaration module,
     * exported or not, so an unregistered alternate spelling is
     * unresolvable even when its rendering matches. The declared text
     * itself renders through the referenced module's dotted module path
     * (the declaration surface's rendering, distinct from the transported
     * descriptor's semantic module path {@code @$external/<specifier>/<Name>}).
     */
    private static String declaredTypeText(RuntimeDescriptor descriptor,
                                          ProjectInterfaceIndex index,
                                          ClassRegistrationSeeds seeds) {
        return switch (descriptor) {
            case RuntimeDescriptor.Null ignored -> "null";
            case RuntimeDescriptor.Boolean ignored -> "boolean";
            case RuntimeDescriptor.Int ignored -> "int";
            case RuntimeDescriptor.Number ignored -> "number";
            case RuntimeDescriptor.String ignored -> "string";
            case RuntimeDescriptor.Table ignored -> "table";
            case RuntimeDescriptor.Bytes ignored -> "bytes";
            case RuntimeDescriptor.Class classDescriptor ->
                classReferenceText(classDescriptor.classId(), index, seeds);
            case RuntimeDescriptor.Array array -> {
                String element = declaredTypeText(array.element(), index, seeds);
                if (element == null) {
                    yield null;
                }
                yield array.element() instanceof RuntimeDescriptor.Func
                        || array.element() instanceof RuntimeDescriptor.Nullable
                    ? "(" + element + ")[]"
                    : element + "[]";
            }
            case RuntimeDescriptor.Nullable nullable -> {
                String inner = declaredTypeText(nullable.inner(), index, seeds);
                if (inner == null) {
                    yield null;
                }
                yield nullable.inner() instanceof RuntimeDescriptor.Func
                    ? "(" + inner + ") | null"
                    : inner + " | null";
            }
            case RuntimeDescriptor.Func func -> {
                List<String> params = new ArrayList<>(func.paramTypes().size());
                for (RuntimeDescriptor param : func.paramTypes()) {
                    String text = declaredTypeText(param, index, seeds);
                    if (text == null) {
                        yield null;
                    }
                    params.add(text);
                }
                String result = declaredTypeText(func.returnType(), index, seeds);
                if (result == null) {
                    yield null;
                }
                yield (func.isAsync() ? "async " : "") + "("
                    + String.join(", ", params) + ") => " + result;
            }
        };
    }

    /**
     * The declared-type text of one class reference: the pinned builtin
     * spelling, or the dotted declaration module path of the exact carried
     * registration the reference names, or the owning interface of an exact
     * implementation-class identity. Declaration references still require a
     * seed: an alternate external spelling cannot stand in for its identity.
     */
    private static String classReferenceText(ClassId classId, ProjectInterfaceIndex index,
                                            ClassRegistrationSeeds seeds) {
        if (classId.equals(ClassId.ERROR)) {
            return ClassId.ERROR.text();
        }
        // Implementation classes have an exact carried interface identity, not
        // declaration seeds. Render through the owning module only after that
        // nominal identity resolves; never accept a same-spelled class.
        LocatedDeclaredClass indexed = locateDeclaredClass(index, classId);
        if (indexed != null && indexed.module().kind() == ExternalModuleKind.IMPLEMENTATION
                && indexed.classEntry().classId().equals(classId)) {
            return "@" + indexed.module().moduleId().path() + "/" + classId.name();
        }
        // Declaration references still require the exact registration, including
        // non-exported classes; an alternate external spelling cannot substitute.
        if (seeds.registrationFor(classId) == null) {
            return null;
        }
        LocatedDeclaredClass declared = locateDeclaredClass(index, classId);
        if (declared != null) {
            return "@" + declared.module().moduleId().path() + "/" + classId.name();
        }
        // A registered declaration class the index declares at no class entry
        // (a non-exported class): its declaration module's own entry renders
        // the reference.
        if (classId.modulePath().startsWith(EXTERNAL_MODULE_PREFIX)) {
            String specifier =
                classId.modulePath().substring(EXTERNAL_MODULE_PREFIX.length());
            if (!specifier.isEmpty()) {
                ExternalModuleInterface module =
                    index.modules().get(new ModuleId(specifier.replace('/', '.')));
                if (module != null) {
                    return "@" + module.moduleId().path() + "/" + classId.name();
                }
            }
        }
        return null;
    }

    // =========================================================================
    // The per-validation context (pure, deterministic)
    // =========================================================================

    private static final class Context {

        private final LoweredModuleUnit unit;
        private final StructuredBodyTable table;
        private final ClassFactoryRegistry factories;
        private final JsonDefaultChildTable jsonDefaults;
        private final ExternalModuleInterface ownInterface;
        private final Map<ClassId, SharedFactoryFacts> sharedFactories;
        /**
         * The declaration classes' registered layouts and owner members of
         * the project's class registration seeds (K10): a host declaration
         * class's payload layout and its field operations resolve through
         * exactly these entries.
         */
        private final Map<ClassId, ClassRegistrationSeeds.ClassRegistration>
            declarationClasses;

        /** The unit's produced ops by {@link OpId} (pinned lookup). */
        private final Map<OpId, SemanticOp> opsById = new LinkedHashMap<>();

        private final Map<ClassId, ClassLayout> layoutContext = new LinkedHashMap<>();

        /**
         * The interface-resolution context: the module's own interface
         * entries plus the imported classes' interface entries.
         */
        private final Map<ClassId, ClassInterface> interfaceContext = new LinkedHashMap<>();

        Context(LoweredModuleUnit unit, StructuredBodyTable table,
                ClassFactoryRegistry factories, JsonDefaultChildTable jsonDefaults,
                ExternalModuleInterface ownInterface,
                Map<ClassId, SharedFactoryFacts> sharedFactories,
                Map<ClassId, ClassRegistrationSeeds.ClassRegistration> declarationClasses) {
            this.unit = unit;
            this.table = table;
            this.factories = factories;
            this.jsonDefaults = jsonDefaults;
            this.ownInterface = ownInterface;
            this.sharedFactories = Map.copyOf(sharedFactories);
            this.declarationClasses = Map.copyOf(declarationClasses);
            for (SemanticOp op : unit.ops()) {
                opsById.put(op.opId(), op);
            }
            layoutContext.putAll(unit.classLayouts());

            for (Map.Entry<ClassId, ClassRegistrationSeeds.ClassRegistration> entry
                    : this.declarationClasses.entrySet()) {
                layoutContext.putIfAbsent(entry.getKey(), entry.getValue().layout());
            }

            layoutContext.put(ClassId.ERROR, ClassLayout.BUILTIN_ERROR);
            for (Map.Entry<ClassId, SharedFactoryFacts> entry : this.sharedFactories
                    .entrySet()) {
                layoutContext.put(entry.getKey(), entry.getValue().layout());
                interfaceContext.put(entry.getKey(), entry.getValue().interfaceEntry());
            }
            for (ClassInterface entry : ownInterface.classes()) {
                interfaceContext.put(entry.classId(), entry);
            }
        }

        /** Builds the E6005 detail of one failing rule. */
        private LoweringFailureDetail detail(String rule, String origin) {
            return new LoweringFailureDetail(
                unit.moduleId().path(), SemanticCapability.CLASSES, rule,
                unit.semanticProfile(), LoweredModuleUnit.FORMAT_VERSION,
                "ClassConstructionValidator " + rule + " (" + origin + ")");
        }

        /** Builds the E6005 diagnostic of one failing rule. */
        private CompilerDiagnostic defect(String rule, String origin) {
            return FailureContractRegistry.e6005(detail(rule, origin));
        }

        private Optional<CompilerDiagnostic> fail(String rule, String origin) {
            return Optional.of(defect(rule, origin));
        }

        /**
         * One failing rule naming an offending op: the E6005 diagnostic
         * carries that op's own source origin as its range (never a
         * guessed anchor). A failure with no produced offending op keeps
         * the canonical synthetic range.
         */
        private Optional<CompilerDiagnostic> fail(String rule, String origin,
                                                  SemanticOp op) {
            return Optional.of(op == null
                ? defect(rule, origin)
                : FailureContractRegistry.e6005(detail(rule, origin),
                    op.origin().span().range()));
        }

        /** The op named by the id, or {@code null}. */
        private SemanticOp op(OpId opId) {
            return opsById.get(opId);
        }

        /**
         * The unit's {@code CLASS_DEFAULT} op of one class and field, or
         * {@code null}. At most one such op may exist (the uniqueness is
         * {@link #FACTORY_COHERENCE}'s check, run before every consumer).
         */
        private SemanticOp defaultOp(ClassId classId, String field) {
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.CLASS_DEFAULT) {
                    continue;
                }
                KindPayload.ClassDefaultPayload payload =
                    (KindPayload.ClassDefaultPayload) op.payload();
                if (payload.classId().equals(classId) && payload.field().equals(field)) {
                    return op;
                }
            }
            return null;
        }

        /** The declared field of one layout, or {@code null}. */
        private static ClassLayout.FieldLayout fieldOf(ClassLayout layout, String name) {
            for (ClassLayout.FieldLayout field : layout.fields()) {
                if (field.name().equals(name)) {
                    return field;
                }
            }
            return null;
        }

        private static RuntimeDescriptor readResultDescriptorOf(
                ClassLayout.FieldLayout field) {
            RuntimeDescriptor declared = field.descriptor();
            if (!field.required() && !(declared instanceof RuntimeDescriptor.Nullable)) {
                return new RuntimeDescriptor.Nullable(declared);
            }
            return declared;
        }

        /** The descriptor-kind rule policy of one checked descriptor. */
        private static FailurePolicyId descriptorKindPolicy(RuntimeDescriptor descriptor) {
            return descriptor instanceof RuntimeDescriptor.Func
                ? FailurePolicyId.FUNCTION_SIGNATURE : FailurePolicyId.TYPE_DESCRIPTOR;
        }

        /** The class descriptor of one class id (the canonical nominal descriptor). */
        private static RuntimeDescriptor.Class classDescriptor(ClassId classId) {
            return new RuntimeDescriptor.Class(classId);
        }

        /** The boundary ops parented to one op, in unit op order. */
        private List<SemanticOp> boundaryChildren(SemanticOp owner) {
            List<SemanticOp> children = new ArrayList<>();
            for (SemanticOp op : unit.ops()) {
                if (op.kind() == SemanticOpKind.BOUNDARY
                        && owner.opId().equals(op.origin().parentOpId())) {
                    children.add(op);
                }
            }
            return children;
        }

        /** The shared factory facts of one imported class, or {@code null}. */
        private SharedFactoryFacts factsOf(ClassId classId) {
            return sharedFactories.get(classId);
        }

        /**
         * The pinned boundary-child shape (kind, descriptor, input,
         * descriptor-kind policy, runtime-validation realization,
         * parentage). {@code null} on the pass path, otherwise the
         * failing origin text. A {@code null} {@code input} argument
         * skips the input comparison (the caller checks the input
         * wiring separately).
         */
        private String boundaryShapeViolation(SemanticOp child, SemanticOp owner,
                                              BoundaryKind kind,
                                              RuntimeDescriptor descriptor,
                                              ValueId input) {
            if (child.kind() != SemanticOpKind.BOUNDARY) {
                return "child " + child.opId() + " of " + owner.kind() + " " + owner.opId()
                    + " is " + child.kind() + ", not BOUNDARY";
            }
            if (!owner.opId().equals(child.origin().parentOpId())) {
                return "boundary child " + child.opId() + " of " + owner.kind() + " "
                    + owner.opId() + " records parentOpId "
                    + child.origin().parentOpId() + ", not the owning op";
            }
            KindPayload.BoundaryPayload payload = (KindPayload.BoundaryPayload) child.payload();
            if (payload.kind() != kind) {
                return "boundary child " + child.opId() + " of " + owner.kind() + " "
                    + owner.opId() + " carries kind " + payload.kind() + ", not " + kind;
            }
            if (!payload.descriptor().equals(descriptor)) {
                return "boundary child " + child.opId() + " of " + owner.kind() + " "
                    + owner.opId() + " carries descriptor "
                    + payload.descriptor().canonicalSpecText() + ", not "
                    + descriptor.canonicalSpecText();
            }
            if (input != null && !payload.input().equals(input)) {
                return "boundary child " + child.opId() + " of " + owner.kind() + " "
                    + owner.opId() + " carries input " + payload.input() + ", not " + input;
            }
            if (child.failurePolicy() != descriptorKindPolicy(descriptor)) {
                return "boundary child " + child.opId() + " of " + owner.kind() + " "
                    + owner.opId() + " carries policy " + child.failurePolicy()
                    + ", not the descriptor-kind rule's " + descriptorKindPolicy(descriptor);
            }
            if (!(payload.realization() instanceof BoundaryRealization.RuntimeValidation)) {
                return "boundary child " + child.opId() + " of " + owner.kind() + " "
                    + owner.opId() + " carries realization " + payload.realization()
                    + ", not a runtime validation (every class-op boundary is a "
                    + "RuntimeValidation cell)";
            }
            return null;
        }

        // =====================================================================
        // 1. FACTORY_COHERENCE
        // =====================================================================

        Optional<CompilerDiagnostic> checkFactoryCoherence() {
            // (a) Exactly one CLASS_DEFAULT op per defaulted (classId,
            // field): a duplicate default child is a malformed
            // construction shape (every consumer — the factory payload,
            // the LOCAL CLASS_NEW child list, and the JSON default-child
            // table — names exactly one op per field).
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.CLASS_DEFAULT) {
                    continue;
                }
                KindPayload.ClassDefaultPayload payload =
                    (KindPayload.ClassDefaultPayload) op.payload();
                SemanticOp other = defaultOp(payload.classId(), payload.field());
                if (!other.opId().equals(op.opId())) {
                    return fail(FACTORY_COHERENCE, "two CLASS_DEFAULT ops (" + other.opId()
                        + " and " + op.opId() + ") exist for " + payload.classId() + "."
                        + payload.field() + ": the pinned shape carries exactly one "
                        + "default child per defaulted field", op);
                }
            }

            // (b) The exported classes' default-op coverage against the
            // interface hasDefault facts: every defaulted field carries
            // exactly one CLASS_DEFAULT op, every non-defaulted field
            // none.
            for (ClassInterface entry : ownInterface.classes()) {
                for (deal.semantic.ir.FieldInterface field : entry.fields()) {
                    SemanticOp defaultOp = defaultOp(entry.classId(), field.name());
                    if (field.hasDefault() && defaultOp == null) {
                        return fail(FACTORY_COHERENCE, "defaulted field '" + field.name()
                            + "' of exported class " + entry.classId() + " has no "
                            + "CLASS_DEFAULT op in the unit: the declaration arm emits "
                            + "one per defaulted field");
                    }
                    if (!field.hasDefault() && defaultOp != null) {
                        return fail(FACTORY_COHERENCE, "non-defaulted field '"
                            + field.name() + "' of exported class " + entry.classId()
                            + " carries a CLASS_DEFAULT op (" + defaultOp.opId()
                            + "): only defaulted fields produce default ops", defaultOp);
                    }
                }
            }

            // (c) The factory/constructionEntry bijection and the factory
            // payload shape for every exported class.
            Map<ClassFactoryId, ClassId> entryOwners = new LinkedHashMap<>();
            for (ClassInterface entry : ownInterface.classes()) {
                ClassFactoryId constructionEntry = entry.constructionEntry();
                if (entryOwners.containsKey(constructionEntry)) {
                    return fail(FACTORY_COHERENCE, "two exported classes ("
                        + entryOwners.get(constructionEntry) + " and " + entry.classId()
                        + ") share constructionEntry " + constructionEntry
                        + ": the pre-allocated ids are a bijection");
                }
                entryOwners.put(constructionEntry, entry.classId());
                OpId factoryOpId = factories.factoryFor(constructionEntry);
                if (factoryOpId == null) {
                    return fail(FACTORY_COHERENCE, "exported class " + entry.classId()
                        + " has no factory registered under constructionEntry "
                        + constructionEntry + ": one CLASS_FACTORY per exported class");
                }
                SemanticOp factory = op(factoryOpId);
                if (factory == null) {
                    return fail(FACTORY_COHERENCE, "the registered factory op "
                        + factoryOpId + " of " + entry.classId() + " is not a produced "
                        + "op of the unit");
                }
                Optional<CompilerDiagnostic> shape = checkFactoryShape(factory, entry);
                if (shape.isPresent()) {
                    return shape;
                }
            }
            for (ClassFactoryId constructionEntry : factories.factories().keySet()) {
                if (!entryOwners.containsKey(constructionEntry)) {
                    return fail(FACTORY_COHERENCE, "constructionEntry " + constructionEntry
                        + " is registered in the factory registry but names no exported "
                        + "class of the module's interface (the registry keys are exactly "
                        + "the exported classes' entries)");
                }
            }

            // (d) Every produced CLASS_FACTORY op is registered under
            // exactly one construction entry.
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.CLASS_FACTORY) {
                    continue;
                }
                KindPayload.ClassFactoryPayload payload =
                    (KindPayload.ClassFactoryPayload) op.payload();
                boolean registered = false;
                for (Map.Entry<ClassFactoryId, OpId> binding
                        : factories.factories().entrySet()) {
                    if (binding.getValue().equals(op.opId())) {
                        if (registered) {
                            return fail(FACTORY_COHERENCE, "factory op " + op.opId()
                                + " is registered under two construction entries", op);
                        }
                        registered = true;
                        ClassId owner = entryOwners.get(binding.getKey());
                        if (!payload.classId().equals(owner)) {
                            return fail(FACTORY_COHERENCE, "factory op " + op.opId()
                                + " of class " + payload.classId() + " is registered "
                                + "under " + binding.getKey() + " (the entry of "
                                + owner + ")", op);
                        }
                    }
                }
                if (!registered) {
                    return fail(FACTORY_COHERENCE, "factory op " + op.opId() + " of "
                        + payload.classId() + " is not registered in the factory "
                        + "registry: every produced CLASS_FACTORY is the construction "
                        + "entry of exactly one exported class", op);
                }
            }
            return Optional.empty();
        }

        /** The pinned factory shape of one exported class's factory op. */
        private Optional<CompilerDiagnostic> checkFactoryShape(SemanticOp factory,
                                                               ClassInterface entry) {
            if (factory.kind() != SemanticOpKind.CLASS_FACTORY) {
                return fail(FACTORY_COHERENCE, "the registered op " + factory.opId()
                    + " of " + entry.classId() + " is " + factory.kind()
                    + ", not CLASS_FACTORY", factory);
            }
            if (factory.failurePolicy() != FailurePolicyId.CLASS_CONSTRUCTION) {
                return fail(FACTORY_COHERENCE, "factory op " + factory.opId() + " of "
                    + entry.classId() + " carries policy " + factory.failurePolicy()
                    + ", not the pinned CLASS_CONSTRUCTION", factory);
            }
            KindPayload.ClassFactoryPayload payload =
                (KindPayload.ClassFactoryPayload) factory.payload();
            if (!payload.classId().equals(entry.classId())) {
                return fail(FACTORY_COHERENCE, "factory op " + factory.opId()
                    + " registered under " + entry.classId() + "'s constructionEntry "
                    + "carries classId " + payload.classId(), factory);
            }
            if (factory.origin().parentOpId() != null) {
                return fail(FACTORY_COHERENCE, "factory op " + factory.opId()
                    + " records parentOpId " + factory.origin().parentOpId()
                    + ": the factory is a detached static op, never parented (its "
                    + "executed parentOpId is the triggering caller op at execution)", factory);
            }
            if (!(factory.result() instanceof ValueId)) {
                return fail(FACTORY_COHERENCE, "factory op " + factory.opId()
                    + " publishes result " + factory.result() + ", not a ValueId "
                    + "(the default-filled transfer instance)", factory);
            }
            if (!(factory.resultType() instanceof RuntimeDescriptor.Class resultClass)
                    || !resultClass.classId().equals(entry.classId())) {
                return fail(FACTORY_COHERENCE, "factory op " + factory.opId()
                    + " publishes result type " + factory.resultType() + ", not the "
                    + "class descriptor of " + entry.classId(), factory);
            }
            if (!boundaryChildren(factory).isEmpty()) {
                return fail(FACTORY_COHERENCE, "factory op " + factory.opId()
                    + " has boundary children: the factory runs zero boundaries and "
                    + "zero return boundaries", factory);
            }
            // classDefaultOpIds = exactly the required-present defaulted
            // fields' CLASS_DEFAULT op ids in declaration order.
            List<String> expected = new ArrayList<>();
            for (deal.semantic.ir.FieldInterface field : entry.fields()) {
                if (!field.optional() && field.hasDefault()) {
                    expected.add(field.name());
                }
            }
            List<OpId> listed = payload.classDefaultOpIds();
            if (listed.size() != expected.size()) {
                return fail(FACTORY_COHERENCE, "factory op " + factory.opId() + " lists "
                    + listed.size() + " CLASS_DEFAULT children for " + expected.size()
                    + " required-present defaulted fields: the pinned payload lists "
                    + "exactly those fields' op ids in declaration order", factory);
            }
            ClassLayout layout = layoutContext.get(entry.classId());
            if (layout == null) {
                return fail(FACTORY_COHERENCE, "exported class " + entry.classId()
                    + " has no layout in the unit's classLayouts (the factory fills the "
                    + "declared layout)");
            }
            for (int i = 0; i < expected.size(); i++) {
                String fieldName = expected.get(i);
                SemanticOp child = op(listed.get(i));
                if (child == null) {
                    return fail(FACTORY_COHERENCE, "factory op " + factory.opId()
                        + " lists CLASS_DEFAULT child " + listed.get(i) + " (field '"
                        + fieldName + "') which is not a produced op of the unit", factory);
                }
                if (child.kind() != SemanticOpKind.CLASS_DEFAULT) {
                    return fail(FACTORY_COHERENCE, "factory op " + factory.opId()
                        + " lists child " + listed.get(i) + " of kind " + child.kind()
                        + " for field '" + fieldName + "', not CLASS_DEFAULT", child);
                }
                KindPayload.ClassDefaultPayload childPayload =
                    (KindPayload.ClassDefaultPayload) child.payload();
                if (!childPayload.classId().equals(entry.classId())
                        || !childPayload.field().equals(fieldName)) {
                    return fail(FACTORY_COHERENCE, "factory op " + factory.opId()
                        + " lists child " + listed.get(i) + " for "
                        + childPayload.classId() + "." + childPayload.field()
                        + ", not " + entry.classId() + "." + fieldName, child);
                }
                if (child.failurePolicy() != FailurePolicyId.NO_DEAL_FAILURE) {
                    return fail(FACTORY_COHERENCE, "factory op " + factory.opId()
                        + " lists CLASS_DEFAULT child " + listed.get(i) + " carrying "
                        + "policy " + child.failurePolicy() + ", not NO_DEAL_FAILURE", child);
                }
                ClassLayout.FieldLayout fieldLayout = fieldOf(layout, fieldName);
                if (fieldLayout == null || !fieldLayout.required()) {
                    return fail(FACTORY_COHERENCE, "factory op " + factory.opId()
                        + " lists CLASS_DEFAULT child " + listed.get(i) + " for field '"
                        + fieldName + "' which is not a required declared field of "
                        + entry.classId(), child);
                }
            }
            return Optional.empty();
        }

        // =====================================================================
        // 2. CONSTRUCTION_COHERENCE
        // =====================================================================

        Optional<CompilerDiagnostic> checkConstructionCoherence() {
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.CLASS_NEW) {
                    continue;
                }
                Optional<CompilerDiagnostic> failure = checkClassNew(op);
                if (failure.isPresent()) {
                    return failure;
                }
            }
            return Optional.empty();
        }

        private Optional<CompilerDiagnostic> checkClassNew(SemanticOp op) {
            if (op.failurePolicy() != FailurePolicyId.CLASS_CONSTRUCTION) {
                return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                    + " carries policy " + op.failurePolicy()
                    + ", not the pinned CLASS_CONSTRUCTION", op);
            }
            KindPayload.ClassNewPayload payload =
                (KindPayload.ClassNewPayload) op.payload();
            // Class/layout identity: the layout carries the payload's
            // classId and resolves to exactly the payload's layout.
            if (!payload.classId().equals(payload.layout().classId())) {
                return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                    + " carries classId " + payload.classId() + " and a layout of "
                    + payload.layout().classId() + ": the layout must carry the "
                    + "payload's classId", op);
            }
            ClassLayout resolved = layoutContext.get(payload.classId());
            if (resolved == null) {
                return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                    + " classId " + payload.classId() + " does not resolve in the "
                    + "layout context (the unit's classLayouts plus the imported "
                    + "classes' layouts and the declaration classes' registered "
                    + "layouts)", op);
            }
            if (!resolved.equals(payload.layout())) {
                return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                    + " carries a layout that differs from the resolved layout of "
                    + payload.classId() + ": the payload's layout must be exactly the "
                    + "resolved layout", op);
            }
            boolean own = unit.classLayouts().containsKey(payload.classId());

            // The closed default-owner shape.
            List<String> providedNames = new ArrayList<>();
            Map<String, ValueId> providedIds = new LinkedHashMap<>();
            for (KindPayload.ProvidedField field : payload.providedFields()) {
                providedNames.add(field.name());
                providedIds.put(field.name(), field.valueOpId());
            }

            for (String name : providedNames) {
                if (fieldOf(resolved, name) == null) {
                    return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                        + " provides field '" + name + "' which is not a declared "
                        + "field of " + payload.classId(), op);
                }
            }
            List<String> expectedDefaults = new ArrayList<>();
            ValueId sharedFactoryResult = null;
            switch (payload.defaultOwner()) {
                case LOCAL -> {
                    if (!own) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " of imported class " + payload.classId() + " carries "
                            + "defaultOwner LOCAL: imported classes construct through "
                            + "the owner's CLASS_FACTORY (SHARED_FACTORY), never LOCAL", op);
                    }
                    if (payload.classFactoryRef() != null) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " carries classFactoryRef " + payload.classFactoryRef()
                            + " under LOCAL: the pinned LOCAL shape carries a null "
                            + "factory ref", op);
                    }
                    for (ClassLayout.FieldLayout field : resolved.fields()) {
                        if (field.required() && defaultOp(payload.classId(),
                                field.name()) != null
                                && !providedNames.contains(field.name())) {
                            expectedDefaults.add(field.name());
                        }
                    }
                    if (payload.classDefaultOpIds().size() != expectedDefaults.size()) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " (LOCAL) lists " + payload.classDefaultOpIds().size()
                            + " CLASS_DEFAULT children for " + expectedDefaults.size()
                            + " omitted required-present defaulted fields: the pinned "
                            + "child list names exactly those fields' op ids in "
                            + "declaration order", op);
                    }
                    for (int i = 0; i < expectedDefaults.size(); i++) {
                        Optional<CompilerDiagnostic> child =
                            checkDefaultChild(op, payload.classDefaultOpIds().get(i),
                                expectedDefaults.get(i), resolved);
                        if (child.isPresent()) {
                            return child;
                        }
                    }
                }
                case SHARED_FACTORY -> {
                    if (own) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " of same-module class " + payload.classId() + " carries "
                            + "defaultOwner SHARED_FACTORY: same-module literals stay "
                            + "LOCAL", op);
                    }
                    if (payload.classFactoryRef() == null) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " of imported class " + payload.classId() + " carries a "
                            + "null classFactoryRef under SHARED_FACTORY: the pinned "
                            + "shape carries the interface's constructionEntry", op);
                    }
                    if (!payload.classDefaultOpIds().isEmpty()) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " (SHARED_FACTORY) lists " + payload.classDefaultOpIds()
                            + ": the pinned imported shape carries an empty child list "
                            + "(defaults transfer to the owner's factory)", op);
                    }
                    SharedFactoryFacts facts = factsOf(payload.classId());
                    if (facts == null) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " of imported class " + payload.classId() + " has no "
                            + "shared-factory facts in the context: SHARED_FACTORY "
                            + "construction without the owner's factory facts is never "
                            + "produced (the RETAINED_ABI deferral)", op);
                    }
                    if (!facts.interfaceEntry().constructionEntry()
                            .equals(payload.classFactoryRef())) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " carries classFactoryRef " + payload.classFactoryRef()
                            + ", not the interface's constructionEntry "
                            + facts.interfaceEntry().constructionEntry() + " of "
                            + payload.classId(), op);
                    }
                    sharedFactoryResult = facts.factoryResult();
                    for (ClassLayout.FieldLayout field : resolved.fields()) {
                        if (field.required() && !providedNames.contains(field.name())) {
                            expectedDefaults.add(field.name());
                        }
                    }
                }
                case RETAINED_ABI -> {
                    return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                        + " carries defaultOwner RETAINED_ABI: the retained ABI "
                        + "transfer is E10's (ISSUE-0239) and is never produced in "
                        + "this epic — the lowerer defers with RETAINED_ABI_DEFERRED", op);
                }
                case HOST_DEFAULTS -> {

                    ClassRegistrationSeeds.ClassRegistration registration =
                        declarationClasses.get(payload.classId());
                    if (registration == null
                            || registration.owner() != DefaultOwner.HOST_DEFAULTS) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " carries defaultOwner HOST_DEFAULTS for class "
                            + payload.classId() + ": the host-defaults owner is"
                            + " admissible only for a declared host class of the"
                            + " compilation's class registration seeds — an"
                            + " unregistered class under HOST_DEFAULTS is a"
                            + " fail-closed producer defect", op);
                    }
                    if (own) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " of same-module class " + payload.classId()
                            + " carries defaultOwner HOST_DEFAULTS: a same-module"
                            + " class never carries a declaration-class owner", op);
                    }
                    if (!registration.layout().equals(payload.layout())) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " carries a layout that differs from the registered"
                            + " declaration layout of " + payload.classId()
                            + " (the declaration surface's fields in declaration"
                            + " order): a foreign layout is a fail-closed producer"
                            + " defect", op);
                    }
                    if (payload.classFactoryRef() != null) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " carries classFactoryRef " + payload.classFactoryRef()
                            + " under HOST_DEFAULTS: the host construction carries a"
                            + " null factory ref (the loaded defaults are data,"
                            + " never a factory transfer)", op);
                    }
                    if (!payload.classDefaultOpIds().isEmpty()) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " (HOST_DEFAULTS) lists " + payload.classDefaultOpIds()
                            + ": the host construction carries an empty child list"
                            + " (the omitted fields' values come from the loaded"
                            + " {@code <C>_defaults} entry — no in-project default"
                            + " expression is evaluated)", op);
                    }
                    for (KindPayload.FieldBoundary entry : payload.fieldBoundaries()) {
                        if (entry.kind() != BoundaryKind.CLASS_LITERAL_FIELD) {
                            return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW "
                                + op.opId() + " field-boundary entry for '"
                                + entry.field() + "' carries kind " + entry.kind()
                                + ", not CLASS_LITERAL_FIELD: the host construction"
                                + " carries exactly one CLASS_LITERAL_FIELD boundary"
                                + " per provided field and no CLASS_DEFAULT_FIELD", op);
                        }
                    }
                }
                case FFI_PLAN -> {

                    ClassRegistrationSeeds.ClassRegistration registration =
                        declarationClasses.get(payload.classId());
                    if (registration == null
                            || registration.owner() != DefaultOwner.FFI_PLAN) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " carries defaultOwner FFI_PLAN for class "
                            + payload.classId() + ": the plan owner is"
                            + " admissible only for a declared extern-C C-struct"
                            + " class of the compilation's class registration"
                            + " seeds — an unregistered class under FFI_PLAN is a"
                            + " fail-closed producer defect", op);
                    }
                    if (own) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " of same-module class " + payload.classId()
                            + " carries defaultOwner FFI_PLAN: a same-module"
                            + " class never carries a declaration-class owner", op);
                    }
                    if (!registration.layout().equals(payload.layout())) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " carries a layout that differs from the registered"
                            + " extern-C declaration layout of " + payload.classId()
                            + " (the validated plan's ordered fields in class"
                            + " source order): a foreign layout is a fail-closed"
                            + " producer defect", op);
                    }
                    if (payload.classFactoryRef() != null) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " carries classFactoryRef " + payload.classFactoryRef()
                            + " under FFI_PLAN: the C-struct construction carries"
                            + " a null factory ref (the loaded plan is the single"
                            + " default authority, never a factory transfer)", op);
                    }
                    if (!payload.classDefaultOpIds().isEmpty()) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " (FFI_PLAN) lists " + payload.classDefaultOpIds()
                            + ": the C-struct construction carries an empty child"
                            + " list (the omitted fields' evaluators are the"
                            + " loaded {@code <C>_plan} entry's — no in-project"
                            + " default expression is evaluated)", op);
                    }
                    for (KindPayload.FieldBoundary entry : payload.fieldBoundaries()) {
                        if (entry.kind() != BoundaryKind.CLASS_LITERAL_FIELD) {
                            return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW "
                                + op.opId() + " field-boundary entry for '"
                                + entry.field() + "' carries kind " + entry.kind()
                                + ", not CLASS_LITERAL_FIELD: the C-struct"
                                + " construction carries exactly one"
                                + " CLASS_LITERAL_FIELD boundary per provided"
                                + " field and no CLASS_DEFAULT_FIELD (the omitted"
                                + " fields are the loaded plan's defaults)", op);
                        }
                    }
                }
                case BUILTIN_DEFAULTS -> {

                    if (!ClassId.ERROR.equals(payload.classId())) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " carries defaultOwner BUILTIN_DEFAULTS for class "
                            + payload.classId() + ": the builtin-defaults owner"
                            + " is admissible only for the builtin Error class "
                            + ClassId.ERROR.text() + " (a non-builtin class under"
                            + " BUILTIN_DEFAULTS is a fail-closed producer defect)", op);
                    }
                    if (!payload.layout().equals(ClassLayout.BUILTIN_ERROR)) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " under BUILTIN_DEFAULTS carries a layout other than"
                            + " the compiler-owned builtin Error layout (exactly the"
                            + " two string required-present fields code and"
                            + " message): a foreign layout is a fail-closed"
                            + " producer defect", op);
                    }
                    if (payload.classFactoryRef() != null) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " carries classFactoryRef " + payload.classFactoryRef()
                            + " under BUILTIN_DEFAULTS: the builtin Error"
                            + " construction carries a null factory ref (the"
                            + " builtin defaults are compiler constants, never a"
                            + " factory transfer)", op);
                    }
                    if (!payload.classDefaultOpIds().isEmpty()) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " (BUILTIN_DEFAULTS) lists "
                            + payload.classDefaultOpIds()
                            + ": the builtin Error construction carries an empty"
                            + " child list (the omitted fields take the compiler"
                            + " constant empty string at the construction site)", op);
                    }
                    for (KindPayload.FieldBoundary entry : payload.fieldBoundaries()) {
                        if (entry.kind() != BoundaryKind.CLASS_LITERAL_FIELD) {
                            return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW "
                                + op.opId() + " field-boundary entry for '"
                                + entry.field() + "' carries kind " + entry.kind()
                                + ", not CLASS_LITERAL_FIELD: the builtin Error"
                                + " construction carries exactly one"
                                + " CLASS_LITERAL_FIELD boundary per provided field"
                                + " and no CLASS_DEFAULT_FIELD (the omitted fields"
                                + " take the compiler constant empty string)", op);
                        }
                    }
                }
                default -> {
                    // Defensive: an owner outside the closed set can only
                    // arrive from a forged payload, and it never gets a
                    // construction shape.
                    return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                        + " carries defaultOwner " + payload.defaultOwner()
                        + ": an owner outside the closed default-ownership set"
                        + " is a fail-closed producer defect", op);
                }
            }

            // The pinned field-boundary list: exactly one entry per
            // provided field (CLASS_LITERAL_FIELD) and per omitted
            // required-present defaulted field (CLASS_DEFAULT_FIELD) in
            // declaration order; omitted optionals get no boundary.
            List<String> expectedBoundaries = new ArrayList<>();
            List<BoundaryKind> expectedKinds = new ArrayList<>();
            for (ClassLayout.FieldLayout field : resolved.fields()) {
                if (providedNames.contains(field.name())) {
                    expectedBoundaries.add(field.name());
                    expectedKinds.add(BoundaryKind.CLASS_LITERAL_FIELD);
                } else if (expectedDefaults.contains(field.name())) {
                    expectedBoundaries.add(field.name());
                    expectedKinds.add(BoundaryKind.CLASS_DEFAULT_FIELD);
                }
            }
            List<KindPayload.FieldBoundary> boundaries = payload.fieldBoundaries();
            if (boundaries.size() != expectedBoundaries.size()) {
                return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId() + " carries "
                    + boundaries.size() + " field-boundary entries for "
                    + expectedBoundaries.size() + " present fields: the pinned shape "
                    + "carries exactly one boundary per provided field and per "
                    + "omitted required-present defaulted field in declaration order", op);
            }
            List<SemanticOp> parentedChildren = boundaryChildren(op);
            if (parentedChildren.size() != boundaries.size()) {
                return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId() + " parents "
                    + parentedChildren.size() + " boundary children for "
                    + boundaries.size() + " payload entries: the op's boundary children "
                    + "are exactly the payload's listed ids", op);
            }
            for (int i = 0; i < boundaries.size(); i++) {
                KindPayload.FieldBoundary entry = boundaries.get(i);
                if (!entry.field().equals(expectedBoundaries.get(i))) {
                    return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                        + " field-boundary entry " + i + " names '" + entry.field()
                        + "': the pinned declaration order names '"
                        + expectedBoundaries.get(i) + "'", op);
                }
                if (entry.kind() != expectedKinds.get(i)) {
                    return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                        + " field-boundary entry for '" + entry.field() + "' carries "
                        + "kind " + entry.kind() + ", not " + expectedKinds.get(i), op);
                }
                SemanticOp child = op(entry.boundaryOpId());
                if (child == null) {
                    return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                        + " names boundary child " + entry.boundaryOpId() + " which is "
                        + "not a produced op of the unit", op);
                }
                if (!child.opId().equals(parentedChildren.get(i).opId())) {
                    return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                        + " payload boundary order does not match the op's parented "
                        + "children: entry " + i + " names " + entry.boundaryOpId()
                        + " but the i-th parented child is "
                        + parentedChildren.get(i).opId(), op);
                }
                ClassLayout.FieldLayout fieldLayout = fieldOf(resolved, entry.field());
                String shape = boundaryShapeViolation(child, op, entry.kind(),
                    fieldLayout.descriptor(), null);
                if (shape != null) {
                    return fail(CONSTRUCTION_COHERENCE, shape, child);
                }
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) child.payload();

                ValueId pinnedInput;
                if (entry.kind() == BoundaryKind.CLASS_LITERAL_FIELD) {
                    pinnedInput = providedIds.get(entry.field());
                } else {
                    if (payload.defaultOwner() == DefaultOwner.SHARED_FACTORY) {
                        pinnedInput = sharedFactoryResult;
                    } else {
                        SemanticOp defaultChild = defaultOp(payload.classId(),
                            entry.field());
                        if (defaultChild == null || !(defaultChild.result()
                                instanceof ValueId resultId)) {
                            return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                                + " names CLASS_DEFAULT_FIELD for '" + entry.field()
                                + "' without a CLASS_DEFAULT child publishing a "
                                + "ValueId result", op);
                        }
                        pinnedInput = resultId;
                    }
                }
                if (pinnedInput == null) {
                    return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                        + " boundary for '" + entry.field() + "' has no pinned input "
                        + "(a literal field without a provided value)", op);
                }
                if (!boundaryPayload.input().equals(pinnedInput)) {
                    return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                        + " boundary " + child.opId() + " carries input "
                        + boundaryPayload.input() + ", not the pinned " + pinnedInput
                        + " (the provided value op for CLASS_LITERAL_FIELD; the "
                        + "field's CLASS_DEFAULT child result for LOCAL; the owner "
                        + "factory result for SHARED_FACTORY)", child);
                }
            }
            return Optional.empty();
        }

        /** The pinned LOCAL CLASS_DEFAULT child of one field. */
        private Optional<CompilerDiagnostic> checkDefaultChild(SemanticOp owner,
                                                               OpId childId,
                                                               String fieldName,
                                                               ClassLayout layout) {
            SemanticOp child = op(childId);
            if (child == null) {
                return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + owner.opId()
                    + " lists CLASS_DEFAULT child " + childId + " (field '" + fieldName
                    + "') which is not a produced op of the unit", owner);
            }
            if (child.kind() != SemanticOpKind.CLASS_DEFAULT) {
                return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + owner.opId()
                    + " lists child " + childId + " of kind " + child.kind()
                    + " for field '" + fieldName + "', not CLASS_DEFAULT", child);
            }
            KindPayload.ClassDefaultPayload payload =
                (KindPayload.ClassDefaultPayload) child.payload();
            if (!payload.classId().equals(layout.classId())
                    || !payload.field().equals(fieldName)) {
                return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + owner.opId()
                    + " lists child " + childId + " for " + payload.classId() + "."
                    + payload.field() + ", not " + layout.classId() + "." + fieldName, child);
            }
            if (child.failurePolicy() != FailurePolicyId.NO_DEAL_FAILURE) {
                return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + owner.opId()
                    + " lists CLASS_DEFAULT child " + childId + " carrying policy "
                    + child.failurePolicy() + ", not NO_DEAL_FAILURE", child);
            }
            return Optional.empty();
        }

        // =====================================================================
        // 3. FIELD_OPERATION_SHAPE
        // =====================================================================

        Optional<CompilerDiagnostic> checkFieldOperationShapes() {
            for (SemanticOp op : unit.ops()) {
                switch (op.kind()) {
                    case FIELD_READ -> {
                        Optional<CompilerDiagnostic> failure = checkFieldRead(op);
                        if (failure.isPresent()) {
                            return failure;
                        }
                    }
                    case FIELD_WRITE -> {
                        Optional<CompilerDiagnostic> failure = checkFieldWrite(op);
                        if (failure.isPresent()) {
                            return failure;
                        }
                    }
                    case FIELD_DELETE -> {
                        Optional<CompilerDiagnostic> failure = checkFieldDelete(op);
                        if (failure.isPresent()) {
                            return failure;
                        }
                    }
                    default -> {
                        // Not a field op.
                    }
                }
            }
            return Optional.empty();
        }

        private Optional<CompilerDiagnostic> checkFieldRead(SemanticOp op) {
            if (op.failurePolicy() != FailurePolicyId.NO_DEAL_FAILURE) {
                return fail(FIELD_OPERATION_SHAPE, "FIELD_READ " + op.opId()
                    + " carries policy " + op.failurePolicy()
                    + ", not the pinned NO_DEAL_FAILURE", op);
            }
            KindPayload.FieldReadPayload payload =
                (KindPayload.FieldReadPayload) op.payload();
            List<SemanticOp> children = boundaryChildren(op);
            if (children.size() != 2) {
                return fail(FIELD_OPERATION_SHAPE, "FIELD_READ " + op.opId() + " parents "
                    + children.size() + " boundary children: the pinned shape is "
                    + "exactly [UNTYPED_CLASS_INPUT, OPTIONAL_FIELD_READ]", op);
            }
            String receiver = boundaryShapeViolation(children.get(0), op,
                BoundaryKind.UNTYPED_CLASS_INPUT, classDescriptor(payload.classId()),
                payload.classValue());
            if (receiver != null) {
                return fail(FIELD_OPERATION_SHAPE, receiver, children.get(0));
            }
            if (!(op.result() instanceof ValueId readResult)) {
                return fail(FIELD_OPERATION_SHAPE, "FIELD_READ " + op.opId()
                    + " publishes result " + op.result() + ", not a ValueId (the "
                    + "OPTIONAL_FIELD_READ input is the op's own result)", op);
            }
            // The pinned OPTIONAL_FIELD_READ descriptor is the read's
            // checked result descriptor — the op's own resultType (the
            // checker's optional-read wrap). When the layout resolves, the
            // wrap must agree with the field's declared descriptor too.
            if (!(op.resultType() instanceof RuntimeDescriptor readDescriptor)) {
                return fail(FIELD_OPERATION_SHAPE, "FIELD_READ " + op.opId()
                    + " publishes result type " + op.resultType() + ", not a "
                    + "descriptor: the pinned OPTIONAL_FIELD_READ descriptor is the "
                    + "read's checked result descriptor", op);
            }
            String field = boundaryShapeViolation(children.get(1), op,
                BoundaryKind.OPTIONAL_FIELD_READ, readDescriptor, readResult);
            if (field != null) {
                return fail(FIELD_OPERATION_SHAPE, field, children.get(1));
            }
            ClassLayout layout = layoutContext.get(payload.classId());
            ClassLayout.FieldLayout fieldLayout = layout == null
                ? null : fieldOf(layout, payload.field());
            if (fieldLayout == null) {
                // An unresolvable layout/field is admitted for the
                // cross-module read arm (the receiver guard is the
                // runtime's); the checker's declared-field fact is the
                // declaration-side authority.
                return Optional.empty();
            }
            if (!readResultDescriptorOf(fieldLayout).equals(readDescriptor)) {
                return fail(FIELD_OPERATION_SHAPE, "FIELD_READ " + op.opId()
                    + " of " + payload.classId() + "." + payload.field()
                    + " publishes result descriptor "
                    + readDescriptor.canonicalSpecText() + ", not the checker's "
                    + "optional-read wrap "
                    + readResultDescriptorOf(fieldLayout).canonicalSpecText(), op);
            }
            return Optional.empty();
        }

        private Optional<CompilerDiagnostic> checkFieldWrite(SemanticOp op) {
            if (op.failurePolicy() != FailurePolicyId.NO_DEAL_FAILURE) {
                return fail(FIELD_OPERATION_SHAPE, "FIELD_WRITE " + op.opId()
                    + " carries policy " + op.failurePolicy()
                    + ", not the pinned NO_DEAL_FAILURE", op);
            }
            KindPayload.FieldWritePayload payload =
                (KindPayload.FieldWritePayload) op.payload();
            // The CLASS_FIELD_ASSIGNMENT descriptor is the field's
            // declared descriptor from the unit's local layout (the
            // lowerer produces class-field writes of locally declared
            // classes, of the compiler-owned builtin Error class, and of
            // the project's declaration classes — the host declaration
            // class's registered layout is the same entry K10 resolves).
            ClassLayout layout = unit.classLayouts().get(payload.classId());
            if (layout == null && ClassId.ERROR.equals(payload.classId())) {

                layout = ClassLayout.BUILTIN_ERROR;
            }
            if (layout == null) {
                ClassRegistrationSeeds.ClassRegistration declaration =
                    declarationClasses.get(payload.classId());
                if (declaration != null) {

                    layout = declaration.layout();
                }
            }
            ClassLayout.FieldLayout fieldLayout = layout == null
                ? null : fieldOf(layout, payload.field());
            if (fieldLayout == null) {
                return fail(FIELD_OPERATION_SHAPE, "FIELD_WRITE " + op.opId()
                    + " of " + payload.classId() + "." + payload.field()
                    + " violates the pinned shape: the class must resolve in the unit's "
                    + "own classLayouts, the builtin Error layout, or the declaration "
                    + "classes' registered layouts and the field must be a declared "
                    + "field (the CLASS_FIELD_ASSIGNMENT descriptor is the field's "
                    + "declared descriptor from that layout)", op);
            }
            List<SemanticOp> children = boundaryChildren(op);
            if (children.size() != 2) {
                return fail(FIELD_OPERATION_SHAPE, "FIELD_WRITE " + op.opId() + " parents "
                    + children.size() + " boundary children: the pinned shape is "
                    + "exactly [UNTYPED_CLASS_INPUT, CLASS_FIELD_ASSIGNMENT]", op);
            }
            String receiver = boundaryShapeViolation(children.get(0), op,
                BoundaryKind.UNTYPED_CLASS_INPUT, classDescriptor(payload.classId()),
                payload.classValue());
            if (receiver != null) {
                return fail(FIELD_OPERATION_SHAPE, receiver, children.get(0));
            }
            String field = boundaryShapeViolation(children.get(1), op,
                BoundaryKind.CLASS_FIELD_ASSIGNMENT, fieldLayout.descriptor(),
                payload.value());
            if (field != null) {
                return fail(FIELD_OPERATION_SHAPE, field, children.get(1));
            }
            return Optional.empty();
        }

        private Optional<CompilerDiagnostic> checkFieldDelete(SemanticOp op) {
            if (op.failurePolicy() != FailurePolicyId.NO_DEAL_FAILURE) {
                return fail(FIELD_OPERATION_SHAPE, "FIELD_DELETE " + op.opId()
                    + " carries policy " + op.failurePolicy()
                    + ", not the pinned NO_DEAL_FAILURE", op);
            }
            KindPayload.FieldDeletePayload payload =
                (KindPayload.FieldDeletePayload) op.payload();
            List<SemanticOp> children = boundaryChildren(op);
            if (children.size() != 1) {
                return fail(FIELD_OPERATION_SHAPE, "FIELD_DELETE " + op.opId() + " parents "
                    + children.size() + " boundary children: the pinned shape is "
                    + "exactly [UNTYPED_CLASS_INPUT]", op);
            }
            String receiver = boundaryShapeViolation(children.get(0), op,
                BoundaryKind.UNTYPED_CLASS_INPUT, classDescriptor(payload.classId()),
                payload.classValue());
            if (receiver != null) {
                return fail(FIELD_OPERATION_SHAPE, receiver, children.get(0));
            }
            // When the layout resolves, a required-field delete is never
            // produced (checker E4004).
            ClassLayout layout = layoutContext.get(payload.classId());
            ClassLayout.FieldLayout fieldLayout = layout == null
                ? null : fieldOf(layout, payload.field());
            if (fieldLayout != null && fieldLayout.required()) {
                return fail(FIELD_OPERATION_SHAPE, "FIELD_DELETE " + op.opId()
                    + " deletes required field '" + payload.field() + "' of "
                    + payload.classId() + ": required-field deletes never reach the IR "
                    + "(checker E4004)", op);
            }
            return Optional.empty();
        }

        // =====================================================================
        // 4. DEFAULT_BLOCK_ADMISSION
        // =====================================================================

        Optional<CompilerDiagnostic> checkDefaultBlockAdmission() {
            // The producing block of every binding-producing allocation
            // kind (the B2 producing-allocation kinds): BINDING_ALLOC,
            // FOR_EACH (the iteration binding), RECURSIVE_GROUP_INIT (the
            // group members).
            Map<BindingId, BlockId> producingBlocks = new LinkedHashMap<>();
            for (SemanticOp op : unit.ops()) {
                switch (op.kind()) {
                    case BINDING_ALLOC -> {
                        KindPayload.BindingAllocPayload payload =
                            (KindPayload.BindingAllocPayload) op.payload();
                        BlockId block = table.opBlocks().get(op.opId());
                        if (block != null) {
                            producingBlocks.put(payload.binding(), block);
                        }
                    }
                    case FOR_EACH -> {
                        KindPayload.ForEachPayload payload =
                            (KindPayload.ForEachPayload) op.payload();
                        BlockId block = table.opBlocks().get(op.opId());
                        if (block != null) {
                            producingBlocks.put(payload.binding(), block);
                        }
                    }
                    case RECURSIVE_GROUP_INIT -> {
                        KindPayload.RecursiveGroupInitPayload payload =
                            (KindPayload.RecursiveGroupInitPayload) op.payload();
                        BlockId block = table.opBlocks().get(op.opId());
                        if (block != null) {
                            for (BindingId binding : payload.bindings()) {
                                producingBlocks.put(binding, block);
                            }
                        }
                    }
                    default -> {
                        // Not a binding producer.
                    }
                }
            }
            BlockId moduleInitBlock = unit.moduleInit().initBlock();
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.CLASS_DEFAULT) {
                    continue;
                }
                KindPayload.ClassDefaultPayload payload =
                    (KindPayload.ClassDefaultPayload) op.payload();
                if (!table.blockOps().containsKey(payload.defaultBlock())) {
                    return fail(DEFAULT_BLOCK_ADMISSION, "CLASS_DEFAULT " + op.opId()
                        + " of " + payload.classId() + "." + payload.field()
                        + " references block " + payload.defaultBlock() + " which is not "
                        + "a block of the membership table", op);
                }
                Set<BlockId> closure = defaultClosure(payload.defaultBlock());
                if (closure == null) {
                    return fail(DEFAULT_BLOCK_ADMISSION, "CLASS_DEFAULT " + op.opId()
                        + " of " + payload.classId() + "." + payload.field()
                        + " references a payload block outside the membership table", op);
                }
                Set<BlockId> admitted = new LinkedHashSet<>(closure);
                admitted.add(moduleInitBlock);
                for (SemanticOp member : unit.ops()) {
                    BlockId block = table.opBlocks().get(member.opId());
                    if (block == null || !closure.contains(block)) {
                        continue;
                    }
                    switch (member.kind()) {
                        case BINDING_LOAD -> {
                            KindPayload.BindingLoadPayload load =
                                (KindPayload.BindingLoadPayload) member.payload();
                            Optional<CompilerDiagnostic> failure = admitReference(op,
                                member, load.binding(), producingBlocks, admitted);
                            if (failure.isPresent()) {
                                return failure;
                            }
                        }
                        case CLOSURE_NEW -> {
                            KindPayload.ClosureNewPayload closurePayload =
                                (KindPayload.ClosureNewPayload) member.payload();
                            for (BindingGeneration capture : closurePayload.captures()) {
                                Optional<CompilerDiagnostic> failure = admitReference(op,
                                    member, capture.binding(), producingBlocks, admitted);
                                if (failure.isPresent()) {
                                    return failure;
                                }
                            }
                        }
                        case FUNCTION_ADAPT -> {
                            KindPayload.FunctionAdaptPayload adaptPayload =
                                (KindPayload.FunctionAdaptPayload) member.payload();
                            if (adaptPayload.source()
                                    instanceof deal.semantic.ir.AdaptSourceRef.Thunk thunk) {
                                for (BindingGeneration capture
                                        : thunk.capturedBindings()) {
                                    Optional<CompilerDiagnostic> failure = admitReference(op,
                                        member, capture.binding(), producingBlocks, admitted);
                                    if (failure.isPresent()) {
                                        return failure;
                                    }
                                }
                            }
                        }
                        default -> {
                            // Other op kinds carry no binding references.
                        }
                    }
                }
            }
            return Optional.empty();
        }

        /**
         * Admits one binding reference against the closed admission set.
         * The message names the owning {@code CLASS_DEFAULT}
         * ({@code defaultOp}); the diagnostic range belongs to the
         * offending {@code reference} — the produced op that actually
         * carries the rejected binding reference — never the enclosing
         * default expression.
         */
        private Optional<CompilerDiagnostic> admitReference(SemanticOp defaultOp,
                                                            SemanticOp reference,
                                                            BindingId binding,
                                                            Map<BindingId, BlockId>
                                                                producingBlocks,
                                                            Set<BlockId> admitted) {
            BlockId producing = producingBlocks.get(binding);
            if (producing == null || !admitted.contains(producing)) {
                return fail(DEFAULT_BLOCK_ADMISSION, "CLASS_DEFAULT " + defaultOp.opId()
                    + " of " + ((KindPayload.ClassDefaultPayload) defaultOp.payload())
                        .classId() + "." + ((KindPayload.ClassDefaultPayload)
                        defaultOp.payload()).field()
                    + " references binding " + binding + " whose producing allocation "
                    + (producing == null ? "is not a block member" : "sits in block "
                        + producing) + ": a default block admits only allocations inside "
                    + "the default closure itself and module-level bindings resolved "
                    + "through the module-init block (an enclosing-region free "
                    + "reference is invalid — K-D3)", reference);
            }
            return Optional.empty();
        }

        /**
         * The block closure of one default block: the block itself plus
         * every block referenced by a payload position of an op whose
         * membership block is in the closure (a fixpoint over the
         * table). {@code null} when a referenced block is not a table
         * block.
         */
        private Set<BlockId> defaultClosure(BlockId root) {
            Set<BlockId> closure = new LinkedHashSet<>();
            closure.add(root);
            boolean extended = true;
            while (extended) {
                extended = false;
                for (SemanticOp op : unit.ops()) {
                    BlockId block = table.opBlocks().get(op.opId());
                    if (block == null || !closure.contains(block)) {
                        continue;
                    }
                    for (BlockId referenced : payloadBlocks(op.payload())) {
                        if (!table.blockOps().containsKey(referenced)) {
                            return null;
                        }
                        if (closure.add(referenced)) {
                            extended = true;
                        }
                    }
                }
            }
            return closure;
        }

        /** The block ids referenced by one payload's closed block positions. */
        private static List<BlockId> payloadBlocks(KindPayload payload) {
            List<BlockId> blocks = new ArrayList<>();
            switch (payload) {
                case KindPayload.ClassDefaultPayload p ->
                    blocks.add(p.defaultBlock());
                case KindPayload.BranchPayload p -> {
                    blocks.add(p.selectedBlock());
                    if (p.alternateBlock() != null) {
                        blocks.add(p.alternateBlock());
                    }
                }
                case KindPayload.LoopPayload p -> {
                    if (p.initBlock() != null) {
                        blocks.add(p.initBlock());
                    }
                    blocks.add(p.bodyBlock());
                    if (p.updateBlock() != null) {
                        blocks.add(p.updateBlock());
                    }
                }
                case KindPayload.ForEachPayload p -> blocks.add(p.body());
                case KindPayload.TryCatchPayload p -> {
                    blocks.add(p.tryBlock());
                    blocks.add(p.catchBlock());
                }
                case KindPayload.CallPayload p -> {
                    if (p.bodyBlock() != null) {
                        blocks.add(p.bodyBlock());
                    }
                }
                case KindPayload.BindingAllocPayload p -> blocks.add(p.scope());
                case KindPayload.FunctionAdaptPayload p -> {
                    if (p.source() instanceof deal.semantic.ir.AdaptSourceRef.Thunk thunk) {
                        blocks.add(thunk.blockId());
                    }
                }
                case KindPayload.ClosureNewPayload p -> blocks.add(p.binding().blockId());
                default -> {
                    // No block position.
                }
            }
            return blocks;
        }

        // =====================================================================
        // 5. JSON_LAYOUT_COHERENCE
        // =====================================================================

        Optional<CompilerDiagnostic> checkJsonLayoutCoherence() {
            for (SemanticOp op : unit.ops()) {
                switch (op.kind()) {
                    case JSON_FROM_CLASS -> {
                        Optional<CompilerDiagnostic> failure = checkJsonFromClass(op);
                        if (failure.isPresent()) {
                            return failure;
                        }
                    }
                    case JSON_TO_CLASS -> {
                        Optional<CompilerDiagnostic> failure = checkJsonToClass(op);
                        if (failure.isPresent()) {
                            return failure;
                        }
                    }
                    default -> {
                        // Not a JSON op.
                    }
                }
            }
            // No orphan JsonDefaultChildTable entries: every key is a
            // produced JSON_FROM_CLASS op.
            for (OpId key : jsonDefaults.defaultChildren().keySet()) {
                SemanticOp op = op(key);
                if (op == null || op.kind() != SemanticOpKind.JSON_FROM_CLASS) {
                    return fail(JSON_LAYOUT_COHERENCE, "the JsonDefaultChildTable "
                        + "records a default-child entry for " + key + " which is not "
                        + "a produced JSON_FROM_CLASS op of the unit", op);
                }
            }
            return Optional.empty();
        }

        /** The shared JSON-op preamble: policy and the unit's own layout. */
        private ClassLayout jsonOpLayout(SemanticOp op, FailurePolicyId pinnedPolicy,
                                         ClassLayout payloadLayout) {
            if (op.failurePolicy() != pinnedPolicy) {
                return null;
            }
            ClassLayout ownLayout = unit.classLayouts().get(payloadLayout.classId());
            if (ownLayout == null || !ownLayout.equals(payloadLayout)) {
                return null;
            }
            return ownLayout;
        }

        private Optional<CompilerDiagnostic> checkJsonFromClass(SemanticOp op) {
            KindPayload.JsonFromClassPayload payload =
                (KindPayload.JsonFromClassPayload) op.payload();
            ClassLayout layout = jsonOpLayout(op, FailurePolicyId.JSON_FROM_NULL,
                payload.layout());
            if (layout == null) {
                return fail(JSON_LAYOUT_COHERENCE, "JSON_FROM_CLASS " + op.opId()
                    + " violates the pinned shape: policy must be JSON_FROM_NULL and "
                    + "the payload layout must be exactly the unit's own layout of "
                    + payload.layout().classId(), op);
            }
            // The per-site default children: exactly the class's
            // required-present defaulted fields' CLASS_DEFAULT op ids in
            // declaration order.
            List<String> expected = new ArrayList<>();
            for (ClassLayout.FieldLayout field : layout.fields()) {
                if (field.required() && defaultOp(layout.classId(), field.name())
                        != null) {
                    expected.add(field.name());
                }
            }
            List<OpId> children = jsonDefaults.childrenOf(op.opId());
            if (children == null) {
                children = List.of();
            }
            if (children.size() != expected.size()) {
                return fail(JSON_LAYOUT_COHERENCE, "JSON_FROM_CLASS " + op.opId()
                    + " records " + children.size() + " per-site CLASS_DEFAULT "
                    + "children for " + expected.size() + " required-present defaulted "
                    + "fields: the pinned table entry lists exactly those fields' op "
                    + "ids in declaration order", op);
            }
            for (int i = 0; i < expected.size(); i++) {
                SemanticOp child = op(children.get(i));
                if (child == null) {
                    return fail(JSON_LAYOUT_COHERENCE, "JSON_FROM_CLASS " + op.opId()
                        + " lists CLASS_DEFAULT child " + children.get(i) + " (field '"
                        + expected.get(i) + "') which is not a produced op of the unit", op);
                }
                if (child.kind() != SemanticOpKind.CLASS_DEFAULT) {
                    return fail(JSON_LAYOUT_COHERENCE, "JSON_FROM_CLASS " + op.opId()
                        + " lists child " + children.get(i) + " of kind " + child.kind()
                        + " for field '" + expected.get(i) + "', not CLASS_DEFAULT", child);
                }
                KindPayload.ClassDefaultPayload childPayload =
                    (KindPayload.ClassDefaultPayload) child.payload();
                if (!childPayload.classId().equals(layout.classId())
                        || !childPayload.field().equals(expected.get(i))) {
                    return fail(JSON_LAYOUT_COHERENCE, "JSON_FROM_CLASS " + op.opId()
                        + " lists child " + children.get(i) + " for "
                        + childPayload.classId() + "." + childPayload.field() + ", not "
                        + layout.classId() + "." + expected.get(i), child);
                }
                if (child.failurePolicy() != FailurePolicyId.NO_DEAL_FAILURE) {
                    return fail(JSON_LAYOUT_COHERENCE, "JSON_FROM_CLASS " + op.opId()
                        + " lists CLASS_DEFAULT child " + children.get(i) + " carrying "
                        + "policy " + child.failurePolicy() + ", not NO_DEAL_FAILURE", child);
                }
            }
            return checkNestedJsonable(layout, new LinkedHashSet<>());
        }

        private Optional<CompilerDiagnostic> checkJsonToClass(SemanticOp op) {
            KindPayload.JsonToClassPayload payload =
                (KindPayload.JsonToClassPayload) op.payload();
            ClassLayout layout = jsonOpLayout(op, FailurePolicyId.JSON_TO_ERROR,
                payload.layout());
            if (layout == null) {
                return fail(JSON_LAYOUT_COHERENCE, "JSON_TO_CLASS " + op.opId()
                    + " violates the pinned shape: policy must be JSON_TO_ERROR and "
                    + "the payload layout must be exactly the unit's own layout of "
                    + payload.layout().classId(), op);
            }
            return checkNestedJsonable(layout, new LinkedHashSet<>());
        }

        private Optional<CompilerDiagnostic> checkNestedJsonable(ClassLayout layout,
                                                                 Set<ClassId> visited) {
            if (!visited.add(layout.classId())) {
                // A visited layout is the checker-rejected same-module
                // jsonable cycle (E4008); the recursion guard never
                // silently admits a cycle, but re-entry with a pinned
                // layout set is tolerated for robustness of the pure
                // walk. (The checker's E4008 gate makes a produced cycle
                // unreachable.)
                return Optional.empty();
            }
            for (ClassLayout.FieldLayout field : layout.fields()) {
                Optional<CompilerDiagnostic> failure = checkJsonableDescriptor(
                    layout.classId(), field.name(), field.descriptor(), visited);
                if (failure.isPresent()) {
                    return failure;
                }
            }
            return Optional.empty();
        }

        private Optional<CompilerDiagnostic> checkJsonableDescriptor(ClassId owner,
                                                                     String field,
                                                                     RuntimeDescriptor
                                                                         descriptor,
                                                                     Set<ClassId> visited) {
            return switch (descriptor) {
                case RuntimeDescriptor.Null ignored -> Optional.empty();
                case RuntimeDescriptor.Boolean ignored -> Optional.empty();
                case RuntimeDescriptor.Int ignored -> Optional.empty();
                case RuntimeDescriptor.Number ignored -> Optional.empty();
                case RuntimeDescriptor.String ignored -> Optional.empty();
                case RuntimeDescriptor.Table ignored -> Optional.empty();
                case RuntimeDescriptor.Array array -> checkJsonableDescriptor(owner,
                    field, array.element(), visited);
                case RuntimeDescriptor.Nullable nullable ->
                    checkJsonableDescriptor(owner, field, nullable.inner(), visited);
                case RuntimeDescriptor.Class classDescriptor -> {
                    ClassId nested = classDescriptor.classId();
                    ClassLayout nestedLayout = layoutContext.get(nested);
                    if (nestedLayout == null) {
                        yield fail(JSON_LAYOUT_COHERENCE, "jsonable class " + owner
                            + " field '" + field + "' references nested class " + nested
                            + " which does not resolve in the layout context (the "
                            + "unit's classLayouts plus the imported classes' "
                            + "layouts)");
                    }
                    if (unit.classLayouts().containsKey(nested)) {
                        // An own nested class: its layout is the unit's.
                        if (!unit.classLayouts().get(nested).equals(nestedLayout)) {
                            yield fail(JSON_LAYOUT_COHERENCE, "jsonable class " + owner
                                + " field '" + field + "' nested class " + nested
                                + " resolves to a layout that differs from the unit's "
                                + "own layout");
                        }
                        ClassInterface ownEntry = interfaceContext.get(nested);
                        if (ownEntry != null) {

                            if (factories.factoryFor(ownEntry.constructionEntry())
                                    == null) {
                                yield fail(JSON_LAYOUT_COHERENCE, "jsonable class "
                                    + owner + " field '" + field + "' nested class "
                                    + nested + " is exported but has no registered "
                                    + "factory under its constructionEntry: the nested "
                                    + "JSON decode triggers the nested CLASS_FACTORY");
                            }
                        }
                        yield checkNestedJsonable(nestedLayout, visited);
                    }
                    // An imported nested class: the SharedFactoryFacts
                    // context must carry its interface entry and layout
                    // (the nested decode resolves the owner factory
                    // through the project's factory registries).
                    SharedFactoryFacts facts = factsOf(nested);
                    if (facts == null) {
                        yield fail(JSON_LAYOUT_COHERENCE, "jsonable class " + owner
                            + " field '" + field + "' references imported nested class "
                            + nested + " without shared-factory facts: the "
                            + "SharedFactoryFacts context must cover every imported "
                            + "class the unit's class constructs reference");
                    }
                    if (!facts.layout().equals(nestedLayout)) {
                        yield fail(JSON_LAYOUT_COHERENCE, "jsonable class " + owner
                            + " field '" + field + "' nested class " + nested
                            + " resolves to a layout that differs from the imported "
                            + "facts' layout");
                    }
                    yield checkNestedJsonable(nestedLayout, visited);
                }
                case RuntimeDescriptor.Bytes ignored -> fail(JSON_LAYOUT_COHERENCE,
                    "jsonable class " + owner + " field '" + field + "' carries a "
                        + "bytes descriptor: the checker's E4007 allowlist excludes "
                        + "bytes-typed jsonable fields");
                case RuntimeDescriptor.Func ignored -> fail(JSON_LAYOUT_COHERENCE,
                    "jsonable class " + owner + " field '" + field + "' carries a "
                        + "function descriptor: the checker's E4007 allowlist excludes "
                        + "function-typed jsonable fields");
            };
        }
    }
}
