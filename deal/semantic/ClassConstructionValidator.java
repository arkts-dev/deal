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
import deal.semantic.ir.ConstructionProvider;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FieldInterface;
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


    public static final String JSON_LAYOUT_COHERENCE = "JSON_LAYOUT_COHERENCE";

    private ClassConstructionValidator() {
        // Static surface; no instances.
    }

    public static Optional<CompilerDiagnostic> validate(
            LoweredModuleUnit unit,
            StructuredBodyTable table,
            ClassFactoryRegistry factories,
            ExternalModuleInterface ownInterface,
            Map<ClassId, SharedFactoryFacts> sharedFactories) {
        return validate(unit, table, factories, ownInterface,
            sharedFactories, Map.of());
    }

    public static Optional<CompilerDiagnostic> validate(
            LoweredModuleUnit unit,
            StructuredBodyTable table,
            ClassFactoryRegistry factories,
            ExternalModuleInterface ownInterface,
            Map<ClassId, SharedFactoryFacts> sharedFactories,
            Map<ClassId, ClassRegistrationSeeds.ClassRegistration> declarationClasses) {
        return validate(unit, table, factories, ownInterface,
            sharedFactories, declarationClasses, Map.of());
    }

    /**
     * The composed per-module production admission with the supplied
     * provider-keyed shared-factory facts: a canonical class identity can
     * carry more than one declaration only in a harness-synthesized
     * carrier, so the recorded construction entry — never the
     * identity-keyed first match — selects the exact declaring provider
     * of a {@code CLASS_NEW(SHARED_FACTORY)}.
     */
    public static Optional<CompilerDiagnostic> validate(
            LoweredModuleUnit unit,
            StructuredBodyTable table,
            ClassFactoryRegistry factories,
            ExternalModuleInterface ownInterface,
            Map<ClassId, SharedFactoryFacts> sharedFactories,
            Map<ClassId, ClassRegistrationSeeds.ClassRegistration> declarationClasses,
            Map<ClassFactoryId, SharedFactoryFacts> providerFactoryFacts) {
        return validate(unit, table, factories, ownInterface,
            sharedFactories, declarationClasses, providerFactoryFacts, Set.of());
    }

    /**
     * The composed per-module production admission with the project-wide
     * set of canonical class identities that carry more than one
     * plan-bearing ({@code @jsonable}) declaration: the generated JSON
     * plan key is the canonical class identity, so exactly one
     * plan-bearing declaration resolves the nested link exactly while a
     * same-identity declaration without a generated plan registers no
     * plan and never makes the key ambiguous. Only the genuine two-plan
     * collision fails the nested link closed.
     */
    public static Optional<CompilerDiagnostic> validate(
            LoweredModuleUnit unit,
            StructuredBodyTable table,
            ClassFactoryRegistry factories,
            ExternalModuleInterface ownInterface,
            Map<ClassId, SharedFactoryFacts> sharedFactories,
            Map<ClassId, ClassRegistrationSeeds.ClassRegistration> declarationClasses,
            Map<ClassFactoryId, SharedFactoryFacts> providerFactoryFacts,
            Set<ClassId> ambiguousJsonPlanClassIds) {
        return validate(unit, table, factories, ownInterface,
            sharedFactories, declarationClasses, providerFactoryFacts,
            ambiguousJsonPlanClassIds, Map.of());
    }

    /**
     * The declaration one canonical class identity's generated JSON plan
     * carries (the plan owner): its declaring module and, when that
     * declaration is exported, its construction entry. A shared identity
     * (the harness-synthesized pair) still carries one recorded owner, so
     * a nested JSON link resolves through it — never through the
     * unit-local sibling layout an identity-keyed lookup would mask it
     * with.
     */
    public record ClassPlanOwner(ModuleId module, ClassFactoryId constructionEntry) {
        public ClassPlanOwner {
            Objects.requireNonNull(module, "module must not be null");
        }
    }

    /**
     * The composed per-module production admission with the recorded plan
     * owners of the project-wide multiple-declaration class identities:
     * the generated JSON plan table is keyed by the canonical class
     * identity and carries exactly the owner declaration's layout (the
     * unique plan-bearing declaration when one exists, the emission
     * order's last declaration otherwise), so every nested JSON link
     * validates that layout instead of the unit-local sibling.
     */
    public static Optional<CompilerDiagnostic> validate(
            LoweredModuleUnit unit,
            StructuredBodyTable table,
            ClassFactoryRegistry factories,
            ExternalModuleInterface ownInterface,
            Map<ClassId, SharedFactoryFacts> sharedFactories,
            Map<ClassId, ClassRegistrationSeeds.ClassRegistration> declarationClasses,
            Map<ClassFactoryId, SharedFactoryFacts> providerFactoryFacts,
            Set<ClassId> ambiguousJsonPlanClassIds,
            Map<ClassId, ClassPlanOwner> jsonPlanOwners) {
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(factories, "factories must not be null");
        Objects.requireNonNull(ownInterface, "ownInterface must not be null");
        Objects.requireNonNull(sharedFactories, "sharedFactories must not be null");
        Objects.requireNonNull(declarationClasses,
            "declarationClasses must not be null");
        Objects.requireNonNull(providerFactoryFacts,
            "providerFactoryFacts must not be null");
        Objects.requireNonNull(ambiguousJsonPlanClassIds,
            "ambiguousJsonPlanClassIds must not be null");
        Objects.requireNonNull(jsonPlanOwners,
            "jsonPlanOwners must not be null");
        Context context = new Context(unit, table, factories, ownInterface,
            sharedFactories, declarationClasses, providerFactoryFacts,
            ambiguousJsonPlanClassIds, jsonPlanOwners);
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
     *       ({@link ConstructionProvider#HOST_DECLARATION});</li>
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
        if (error != null && (error.owner() != ConstructionProvider.BUILTIN_ERROR
                || !error.layout().equals(ClassLayout.BUILTIN_ERROR))) {
            return Optional.of("the builtin " + ClassId.ERROR.text()
                + " registration carries owner " + error.owner() + " and layout "
                + error.layout() + "; the compiler-owned registration is exactly "
                + ClassLayout.BUILTIN_ERROR + " under " + ConstructionProvider.BUILTIN_ERROR);
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
            if (registration.owner() != ConstructionProvider.HOST_DECLARATION) {
                return Optional.of("the " + key + " registration carries owner "
                    + registration.owner() + "; a declaration class registration is"
                    + " owned by " + ConstructionProvider.HOST_DECLARATION);
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
     * {@code CLASS_NEW} whose {@code constructionProvider} is a declaration owner
     * ({@link ConstructionProvider#HOST_DECLARATION})
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
                String owner = stringPayloadField(op.payload(), "constructionProvider");
                if (!ConstructionProvider.HOST_DECLARATION.name().equals(owner)) {
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
                        "the CLASS_NEW carries constructionProvider " + owner + " for "
                            + classId + ", which no class registration seed registers:"
                            + " a declaration-class construction is never admissible"
                            + " without its registration"));
                }
                if (!registration.owner().name().equals(owner)) {
                    return Optional.of(new SeedModuleViolation(unit, op,
                        "the CLASS_NEW carries constructionProvider " + owner + " for "
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
        RuntimeDescriptor declared = field.descriptor();
        RuntimeDescriptor descriptor = read ? field.readResultDescriptor() : declared;
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

            case RuntimeDescriptor.Number ignored -> "number";
            case RuntimeDescriptor.String ignored -> "string";
            case RuntimeDescriptor.Table ignored -> "table";
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
        private final ExternalModuleInterface ownInterface;
        private final Map<ClassId, SharedFactoryFacts> sharedFactories;
        /**
         * The provider-keyed shared-factory facts of the accumulated
         * project closure (the declared class occurrence's construction
         * entry to its facts): a {@code CLASS_NEW(SHARED_FACTORY)}
         * resolves its exact declaring provider through its recorded
         * construction entry, never through the (possibly ambiguous)
         * canonical class identity alone.
         */
        private final Map<ClassFactoryId, SharedFactoryFacts> providerFactoryFacts;
        /**
         * The project-wide canonical class identities carrying more than
         * one plan-bearing ({@code @jsonable}) declaration: only those make
         * the generated JSON plan key (the canonical identity) unable to
         * select one plan.
         */
        private final Set<ClassId> ambiguousJsonPlanClassIds;
        /**
         * The recorded plan owners of the project-wide
         * multiple-declaration class identities: the declaration the
         * generated JSON plan table carries under the canonical key. A
         * nested JSON link validates this owner's layout rather than the
         * unit-local sibling the identity-keyed context would mask it
         * with.
         */
        private final Map<ClassId, ClassPlanOwner> jsonPlanOwners;
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
                ClassFactoryRegistry factories, ExternalModuleInterface ownInterface,
                Map<ClassId, SharedFactoryFacts> sharedFactories,
                Map<ClassId, ClassRegistrationSeeds.ClassRegistration> declarationClasses,
                Map<ClassFactoryId, SharedFactoryFacts> providerFactoryFacts,
                Set<ClassId> ambiguousJsonPlanClassIds,
                Map<ClassId, ClassPlanOwner> jsonPlanOwners) {
            this.unit = unit;
            this.table = table;
            this.factories = factories;
            this.ownInterface = ownInterface;
            this.sharedFactories = Map.copyOf(sharedFactories);
            this.declarationClasses = Map.copyOf(declarationClasses);
            this.providerFactoryFacts = Map.copyOf(providerFactoryFacts);
            this.ambiguousJsonPlanClassIds = Set.copyOf(ambiguousJsonPlanClassIds);
            this.jsonPlanOwners = Map.copyOf(jsonPlanOwners);
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
                // A unit's own layout (and a declaration class's registered
                // layout) wins over an accumulated foreign provider's for
                // the same canonical identity: a harness-synthesized pair
                // shares it, and the unit-local consumer's own declaration
                // is the lexical one.
                layoutContext.putIfAbsent(entry.getKey(), entry.getValue().layout());
                interfaceContext.putIfAbsent(entry.getKey(),
                    entry.getValue().interfaceEntry());
            }
            for (SharedFactoryFacts facts : this.providerFactoryFacts.values()) {
                // The exact declaring provider's layout/interface entry of
                // one construction occurrence; a same-identity sibling's
                // facts never overwrite the identity view a unit-local
                // consumer resolves (the recorded construction entry is
                // the selection fact).
                layoutContext.putIfAbsent(facts.classId(), facts.layout());
                interfaceContext.putIfAbsent(facts.classId(),
                    facts.interfaceEntry());
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

        /**
         * Builds the E6005 diagnostic of one failing rule naming an
         * offending op: the diagnostic carries that op's own source origin
         * as its range (never a guessed anchor). A failure with no produced
         * offending op keeps the canonical synthetic range.
         */
        private CompilerDiagnostic defect(String rule, String origin,
                                          SemanticOp op) {
            return op == null
                ? defect(rule, origin)
                : FailureContractRegistry.e6005(detail(rule, origin),
                    op.origin().span().range());
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
            return Optional.of(defect(rule, origin, op));
        }

        /** The op named by the id, or {@code null}. */
        private SemanticOp op(OpId opId) {
            return opsById.get(opId);
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
            return field.readResultDescriptor();
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
         * The shared factory facts of one exact declaring provider,
         * selected by the recorded construction entry, or the
         * identity-keyed fallback when no provider-keyed facts carry the
         * entry (the single-provider case).
         */
        private SharedFactoryFacts factsOf(ClassId classId, ClassFactoryId entry) {
            if (entry != null) {
                SharedFactoryFacts provider = providerFactoryFacts.get(entry);
                if (provider != null) {
                    return provider;
                }
            }
            return factsOf(classId);
        }

        /** The unit's own interface entry of one class id, or {@code null}. */
        private ClassInterface ownClassEntry(ClassId classId) {
            for (ClassInterface entry : ownInterface.classes()) {
                if (entry.classId().equals(classId)) {
                    return entry;
                }
            }
            return null;
        }

        /**
         * Whether one canonical class identity carries more than one
         * declaration in the unit's resolution context: the unit's own
         * layout and the declared providers' shared-factory facts. A
         * harness-synthesized identity can still name two declarations, so
         * such an identity has no single layout and a field operation of it
         * must carry its exact declaring-provider reference.
         */
        private boolean identityAmbiguous(ClassId classId) {
            java.util.Set<ClassFactoryId> entries = new java.util.LinkedHashSet<>();
            for (SharedFactoryFacts facts : providerFactoryFacts.values()) {
                if (facts.classId().equals(classId)) {
                    entries.add(facts.interfaceEntry().constructionEntry());
                }
            }
            for (SharedFactoryFacts facts : sharedFactories.values()) {
                if (facts.classId().equals(classId)) {
                    entries.add(facts.interfaceEntry().constructionEntry());
                }
            }
            int count = entries.size();
            if (unit.classLayouts().containsKey(classId)) {
                count++;
            }
            return count > 1;
        }

        /**
         * Whether one canonical class identity carries more than one
         * plan-bearing ({@code @jsonable}) declaration in the project: the
         * generated JSON plan key is the canonical class identity, so
         * exactly one plan-bearing declaration resolves the nested link
         * exactly, while a same-identity declaration without a generated
         * plan registers no plan and cannot collide with it.
         */
        private boolean jsonPlanIdentityAmbiguous(ClassId classId) {
            return ambiguousJsonPlanClassIds.contains(classId);
        }

        /**
         * One field operation's exact declaring-provider layout
         * resolution: the payload's recorded construction entry selects the
         * exact declaring provider's facts (an imported declaration) or the
         * unit's own interface entry (a local exported declaration); a
         * record-less operation of a single-declaration identity keeps the
         * identity-keyed layout. An unresolvable recorded reference or a
         * record-less operation of a multiple-declaration identity fails
         * closed with the named rule — never a first alias or a unit-local
         * mask — and every failure carries the offending operation's own
         * source origin as its range.
         */
        private FieldLayoutResolution fieldLayoutResolution(ClassId classId,
                                                            ClassFactoryId ref,
                                                            String operation,
                                                            SemanticOp op) {
            if (ref != null) {
                SharedFactoryFacts provider = providerFactoryFacts.get(ref);
                if (provider != null) {
                    if (!provider.classId().equals(classId)) {
                        return FieldLayoutResolution.failure(defect(
                            FIELD_OPERATION_SHAPE, operation + " " + op.opId()
                                + " carries classFactoryRef " + ref + " of "
                                + provider.classId() + ", not its own classId "
                                + classId + " (the recorded reference must name the"
                                + " operation's exact declaring provider)", op));
                    }
                    return FieldLayoutResolution.layout(provider.layout());
                }
                ClassInterface own = ownClassEntry(classId);
                if (own != null && own.constructionEntry().equals(ref)) {
                    return FieldLayoutResolution.layout(
                        unit.classLayouts().get(classId));
                }
                return FieldLayoutResolution.failure(defect(
                    FIELD_OPERATION_SHAPE, operation + " " + op.opId()
                        + " carries classFactoryRef " + ref + " which resolves no"
                        + " registered declaration of " + classId + " (an exact"
                        + " declaring provider reference must name a project"
                        + " declaration's construction entry or the unit's own"
                        + " exported class entry)", op));
            }
            if (identityAmbiguous(classId)) {
                ClassLayout own = unit.classLayouts().get(classId);
                if (own != null) {
                    // The unit's own declaration is the only local
                    // candidate; the one lowering records the exact
                    // declaring-provider reference of every ambiguous
                    // imported receiver, so a record-less operation of an
                    // identity the unit itself declares is the unit's own
                    // declaration (never an imported sibling's mask).
                    return FieldLayoutResolution.layout(own);
                }
                return FieldLayoutResolution.failure(defect(
                    FIELD_OPERATION_SHAPE, operation + " " + op.opId() + " of "
                        + classId + " carries no exact declaring-provider reference"
                        + " while the canonical class identity has more than one"
                        + " declared provider (same-directory sibling declarations):"
                        + " the operation fails closed rather than selecting a first"
                        + " alias or an identity-keyed first match", op));
            }
            return FieldLayoutResolution.layout(layoutContext.get(classId));
        }

        /**
         * One field operation's resolved layout ({@code null} for an
         * unresolvable single-declaration identity) or the named failure.
         */
        private record FieldLayoutResolution(ClassLayout layout,
                                             CompilerDiagnostic failure) {

            static FieldLayoutResolution layout(ClassLayout layout) {
                return new FieldLayoutResolution(layout, null);
            }

            static FieldLayoutResolution failure(CompilerDiagnostic failure) {
                return new FieldLayoutResolution(null,
                    Objects.requireNonNull(failure, "failure must not be null"));
            }
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
            // The factory/constructionEntry bijection and the factory
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
                if (!factory.opId().module().equals(unit.moduleId())) {
                    return fail(FACTORY_COHERENCE, "the registered factory op "
                        + factoryOpId + " of " + entry.classId() + " carries module "
                        + "tag " + factory.opId().module() + ", not the owning module "
                        + unit.moduleId() + ": a module's factory registry binds an op"
                        + " of that module (the provider owner is the registry's module"
                        + " and the operation module tag, never a class-id namespace)",
                        factory);
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
            ClassLayout layout = layoutContext.get(entry.classId());
            if (layout == null) {
                return fail(FACTORY_COHERENCE, "exported class " + entry.classId()
                    + " has no layout in the unit's classLayouts (the factory fills the "
                    + "declared layout)");
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
            // The recorded construction entry of a SHARED_FACTORY
            // construction selects the exact declaring provider's layout:
            // a same-identity sibling's layout never masks the payload's (a
            // harness-synthesized identity can carry two declarations).
            SharedFactoryFacts exactProvider = payload.classFactoryRef() == null
                ? null : providerFactoryFacts.get(payload.classFactoryRef());
            if (exactProvider != null
                    && exactProvider.layout().equals(payload.layout())) {
                resolved = exactProvider.layout();
            }
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
            boolean own = unit.classLayouts().containsKey(payload.classId())
                && exactProvider == null;

            // The closed default-owner shape.
            List<String> providedNames = new ArrayList<>();
            Map<String, ValueId> providedIds = new LinkedHashMap<>();
            for (KindPayload.ProvidedField field : payload.providedFields()) {
                providedNames.add(field.name());
                if (providedIds.putIfAbsent(field.name(), field.valueOpId()) != null) {
                    return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                        + " repeats provided field '" + field.name() + "'", op);
                }
            }

            for (String name : providedNames) {
                if (fieldOf(resolved, name) == null) {
                    return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                        + " provides field '" + name + "' which is not a declared "
                        + "field of " + payload.classId(), op);
                }
            }
            for (ClassLayout.FieldLayout field : resolved.fields()) {
                if (field.required() && !providedIds.containsKey(field.name())) {
                    return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                        + " omits required-present field '" + field.name() + "'", op);
                }
            }
            switch (payload.constructionProvider()) {
                case LOCAL -> {
                    if (!own) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " of imported class " + payload.classId() + " carries "
                            + "constructionProvider LOCAL: imported classes construct through "
                            + "the owner's CLASS_FACTORY (SHARED_FACTORY), never LOCAL", op);
                    }
                    if (payload.classFactoryRef() != null) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " carries classFactoryRef " + payload.classFactoryRef()
                            + " under LOCAL: the pinned LOCAL shape carries a null "
                            + "factory ref", op);
                    }


                }
                case SHARED_FACTORY -> {
                    if (own) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " of same-module class " + payload.classId() + " carries "
                            + "constructionProvider SHARED_FACTORY: same-module literals stay "
                            + "LOCAL", op);
                    }
                    if (payload.classFactoryRef() == null) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " of imported class " + payload.classId() + " carries a "
                            + "null classFactoryRef under SHARED_FACTORY: the pinned "
                            + "shape carries the interface's constructionEntry", op);
                    }

                    SharedFactoryFacts facts = factsOf(payload.classId(),
                        payload.classFactoryRef());
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
                }
                case RETAINED_ABI -> {
                    return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                        + " carries constructionProvider RETAINED_ABI: the retained ABI "
                        + "transfer is E10's (ISSUE-0239) and is never produced in "
                        + "this epic — the lowerer defers with RETAINED_ABI_DEFERRED", op);
                }
                case HOST_DECLARATION -> {

                    ClassRegistrationSeeds.ClassRegistration registration =
                        declarationClasses.get(payload.classId());
                    if (registration == null
                            || registration.owner() != ConstructionProvider.HOST_DECLARATION) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " carries constructionProvider HOST_DECLARATION for class "
                            + payload.classId() + ": the host-defaults owner is"
                            + " admissible only for a declared host class of the"
                            + " compilation's class registration seeds — an"
                            + " unregistered class under HOST_DECLARATION is a"
                            + " fail-closed producer defect", op);
                    }
                    if (own) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " of same-module class " + payload.classId()
                            + " carries constructionProvider HOST_DECLARATION: a same-module"
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
                            + " under HOST_DECLARATION: the host construction carries a"
                            + " null factory ref (the loaded defaults are data,"
                            + " never a factory transfer)", op);
                    }

                    for (KindPayload.FieldBoundary entry : payload.fieldBoundaries()) {
                        if (entry.kind() != BoundaryKind.CLASS_LITERAL_FIELD) {
                            return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW "
                                + op.opId() + " field-boundary entry for '"
                                + entry.field() + "' carries kind " + entry.kind()
                                + ", not CLASS_LITERAL_FIELD: the host construction"
                                + " carries exactly one CLASS_LITERAL_FIELD boundary"
                                + " per provided field", op);
                        }
                    }
                }

                case BUILTIN_ERROR -> {

                    if (!ClassId.ERROR.equals(payload.classId())) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " carries constructionProvider BUILTIN_ERROR for class "
                            + payload.classId() + ": the builtin-defaults owner"
                            + " is admissible only for the builtin Error class "
                            + ClassId.ERROR.text() + " (a non-builtin class under"
                            + " BUILTIN_ERROR is a fail-closed producer defect)", op);
                    }
                    if (!payload.layout().equals(ClassLayout.BUILTIN_ERROR)) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " under BUILTIN_ERROR carries a layout other than"
                            + " the compiler-owned builtin Error layout (exactly the"
                            + " two string required-present fields code and"
                            + " message): a foreign layout is a fail-closed"
                            + " producer defect", op);
                    }
                    if (payload.classFactoryRef() != null) {
                        return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                            + " carries classFactoryRef " + payload.classFactoryRef()
                            + " under BUILTIN_ERROR: the builtin Error"
                            + " construction carries a null factory ref (the"
                            + " builtin defaults are compiler constants, never a"
                            + " factory transfer)", op);
                    }

                    for (KindPayload.FieldBoundary entry : payload.fieldBoundaries()) {
                        if (entry.kind() != BoundaryKind.CLASS_LITERAL_FIELD) {
                            return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW "
                                + op.opId() + " field-boundary entry for '"
                                + entry.field() + "' carries kind " + entry.kind()
                                + ", not CLASS_LITERAL_FIELD: the builtin Error"
                                + " construction carries exactly one"
                                + " CLASS_LITERAL_FIELD boundary per provided field"
                                + " (the omitted fields"
                                + " take the compiler constant empty string)", op);
                        }
                    }
                }
                default -> {
                    // Defensive: an owner outside the closed set can only
                    // arrive from a forged payload, and it never gets a
                    // construction shape.
                    return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                        + " carries constructionProvider " + payload.constructionProvider()
                        + ": an owner outside the closed default-ownership set"
                        + " is a fail-closed producer defect", op);
                }
            }

            // Exactly one boundary per provided field, in declaration order.
            // Omitted optional fields have neither a value nor a boundary.
            List<String> expectedBoundaries = new ArrayList<>();
            List<BoundaryKind> expectedKinds = new ArrayList<>();
            for (ClassLayout.FieldLayout field : resolved.fields()) {
                if (providedNames.contains(field.name())) {
                    expectedBoundaries.add(field.name());
                    expectedKinds.add(BoundaryKind.CLASS_LITERAL_FIELD);
                }
            }
            List<KindPayload.FieldBoundary> boundaries = payload.fieldBoundaries();
            if (boundaries.size() != expectedBoundaries.size()) {
                return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId() + " carries "
                    + boundaries.size() + " field-boundary entries for "
                    + expectedBoundaries.size() + " present fields: the pinned shape "
                    + "carries exactly one boundary per provided field in declaration order", op);
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

                ValueId pinnedInput = providedIds.get(entry.field());
                if (pinnedInput == null) {
                    return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                        + " boundary for '" + entry.field() + "' has no pinned input "
                        + "(a literal field without a provided value)", op);
                }
                if (!boundaryPayload.input().equals(pinnedInput)) {
                    return fail(CONSTRUCTION_COHERENCE, "CLASS_NEW " + op.opId()
                        + " boundary " + child.opId() + " carries input "
                        + boundaryPayload.input() + ", not the pinned " + pinnedInput
                        + " (the provided value for CLASS_LITERAL_FIELD)", child);
                }
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
            FieldLayoutResolution resolution = fieldLayoutResolution(
                payload.classId(), payload.classFactoryRef(), "FIELD_READ", op);
            if (resolution.failure() != null) {
                return Optional.of(resolution.failure());
            }
            ClassLayout layout = resolution.layout();
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
            // declared descriptor of the exact declaring provider the
            // operation's recorded construction entry names: the unit's own
            // exported declaration, the compiler-owned builtin Error
            // layout, a declaration class's registered layout, or the exact
            // imported provider's shared-factory facts. A record-less
            // operation resolves the unit's own layout, the builtin Error
            // layout, the declaration classes' registered layouts, or the
            // imported provider's facts — and fails closed on a
            // multiple-declaration identity.
            FieldLayoutResolution resolution = fieldLayoutResolution(
                payload.classId(), payload.classFactoryRef(), "FIELD_WRITE", op);
            if (resolution.failure() != null) {
                return Optional.of(resolution.failure());
            }
            ClassLayout layout = resolution.layout();
            ClassLayout.FieldLayout fieldLayout = layout == null
                ? null : fieldOf(layout, payload.field());
            if (fieldLayout == null) {
                return fail(FIELD_OPERATION_SHAPE, "FIELD_WRITE " + op.opId()
                    + " of " + payload.classId() + "." + payload.field()
                    + " violates the pinned shape: the class must resolve in the unit's "
                    + "own classLayouts, the builtin Error layout, the declaration "
                    + "classes' registered layouts, or the exact declaring provider's "
                    + "shared-factory facts and the field must be a declared "
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
            // produced (checker E4004); the layout is the exact declaring
            // provider's of the operation's recorded construction entry.
            FieldLayoutResolution resolution = fieldLayoutResolution(
                payload.classId(), payload.classFactoryRef(), "FIELD_DELETE", op);
            if (resolution.failure() != null) {
                return Optional.of(resolution.failure());
            }
            ClassLayout layout = resolution.layout();
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

                case RuntimeDescriptor.Number ignored -> Optional.empty();
                case RuntimeDescriptor.String ignored -> Optional.empty();
                case RuntimeDescriptor.Table ignored -> Optional.empty();
                case RuntimeDescriptor.Array array -> checkJsonableDescriptor(owner,
                    field, array.element(), visited);
                case RuntimeDescriptor.Nullable nullable ->
                    checkJsonableDescriptor(owner, field, nullable.inner(), visited);
                case RuntimeDescriptor.Class classDescriptor -> {
                    ClassId nested = classDescriptor.classId();
                    if (jsonPlanIdentityAmbiguous(nested)) {
                        // The generated JSON plan table is keyed by the
                        // canonical class identity and more than one of the
                        // identity's declarations generates a plan, so no
                        // single nested plan is selectable: the link fails
                        // closed rather than emitting a project whose nested
                        // walk may consume a sibling declaration's plan. A
                        // same-identity declaration without a generated plan
                        // registers none and keeps the single-plan link
                        // exact.
                        yield fail(JSON_LAYOUT_COHERENCE, "jsonable class " + owner
                            + " field '" + field + "' references nested class " + nested
                            + " whose canonical identity carries more than one"
                            + " plan-bearing (@jsonable) declaration (same-directory"
                            + " sibling declarations): the generated JSON plan key"
                            + " cannot select the exact declaration, so the link"
                            + " fails closed with no partial project");
                    }
                    ClassPlanOwner planOwner = jsonPlanOwners.get(nested);
                    if (planOwner != null) {
                        // The recorded plan owner of a multiple-declaration
                        // identity: the generated plan table carries exactly
                        // this declaration's layout, so the nested link
                        // validates the owner's own layout (this unit's
                        // declaration) or the owner's exact
                        // shared-factory facts — never the unit-local
                        // sibling an identity-keyed lookup would select.
                        if (planOwner.module().equals(unit.moduleId())) {
                            ClassLayout ownLayout = unit.classLayouts().get(nested);
                            if (ownLayout == null) {
                                yield fail(JSON_LAYOUT_COHERENCE, "jsonable class "
                                    + owner + " field '" + field + "' nested class "
                                    + nested + " is the recorded plan owner of this"
                                    + " unit but the unit declares no such layout (a"
                                    + " fact defect)");
                            }
                            ClassInterface ownEntry = ownClassEntry(nested);
                            if (ownEntry != null
                                    && factories.factoryFor(
                                        ownEntry.constructionEntry()) == null) {
                                yield fail(JSON_LAYOUT_COHERENCE, "jsonable class "
                                    + owner + " field '" + field + "' nested class "
                                    + nested + " is exported but has no registered "
                                    + "factory under its constructionEntry: the nested "
                                    + "JSON decode triggers the nested CLASS_FACTORY");
                            }
                            yield checkNestedJsonable(ownLayout, visited);
                        }
                        SharedFactoryFacts ownerFacts = factsOf(nested,
                            planOwner.constructionEntry());
                        if (ownerFacts == null) {
                            yield fail(JSON_LAYOUT_COHERENCE, "jsonable class "
                                + owner + " field '" + field + "' references imported"
                                + " nested class " + nested + " without shared-factory"
                                + " facts: the SharedFactoryFacts context must cover"
                                + " every imported class the unit's class constructs"
                                + " reference");
                        }
                        if (planOwner.constructionEntry() != null
                                && !planOwner.constructionEntry().equals(
                                    ownerFacts.interfaceEntry().constructionEntry())) {
                            yield fail(JSON_LAYOUT_COHERENCE, "jsonable class "
                                + owner + " field '" + field + "' nested class "
                                + nested + " resolves shared-factory facts of"
                                + " construction entry "
                                + ownerFacts.interfaceEntry().constructionEntry()
                                + ", not the recorded plan owner's "
                                + planOwner.constructionEntry() + " (same-directory"
                                + " sibling declarations): the nested link fails"
                                + " closed rather than selecting another"
                                + " declaration's plan");
                        }
                        yield checkNestedJsonable(ownerFacts.layout(), visited);
                    }
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
                        ClassInterface ownEntry = ownClassEntry(nested);
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
                    // A declaration-file class is not admitted here even
                    // when the checker's visible declared C$fromJson/C$toJson
                    // make it a jsonable field type: a declaration module
                    // carries no implementation lowering (no plan, factory or
                    // default evaluator), and no production consumer realizes
                    // a nested declaration-class walk. The nested link stays
                    // fail closed on every backend instead of compiling on one
                    // lane and failing at runtime on another.
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
                case RuntimeDescriptor.Func ignored -> fail(JSON_LAYOUT_COHERENCE,
                    "jsonable class " + owner + " field '" + field + "' carries a "
                        + "function descriptor: the checker's E4007 allowlist excludes "
                        + "function-typed jsonable fields");
            };
        }
    }
}
