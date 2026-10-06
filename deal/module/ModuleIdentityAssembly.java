package deal.module;

import deal.ast.Span;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticNote;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalClassIdentityIndex;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.project.ExternalEntry;
import deal.project.ProjectContext;
import deal.source.SourceScalarRange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ModuleIdentityAssembly {

    /** The pinned class name of the intrinsic builtin {@code Error} class. */
    public static final String INTRINSIC_ERROR_CLASS_NAME = "Error";

    /**
     * The pinned descriptor-text projection of the intrinsic
     * {@code Error} class: {@code @$builtin/Error}.
     */
    public static final String INTRINSIC_ERROR_DESCRIPTOR_TEXT =
        "@$builtin/Error";

    /** The pinned external-declaration descriptor namespace component. */
    private static final String EXTERNAL_NAMESPACE = "$external";

    /** The validated immutable project context of this compilation. */
    private final ProjectContext context;

    /** Registered identities → descriptor text, in registration order. */
    private final Map<CanonicalClassIdentity, String> descriptorTexts =
        new LinkedHashMap<>();

    /** Registered descriptor texts → identity, in registration order. */
    private final Map<String, CanonicalClassIdentity> identitiesByText =
        new LinkedHashMap<>();

    /** The read-only index view over the two registration maps. */
    private final CanonicalClassIdentityIndex index = new Index();

    /** The synthesized intrinsic Error identity, cached after first use. */
    private CanonicalClassIdentity intrinsicError;

    /**
     * Creates the identity assembly over one validated project context.
     *
     */
    public ModuleIdentityAssembly(ProjectContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    // =========================================================================
    // Result carriers
    // =========================================================================

    /**
     * The result of the declaration gate: {@link Ok} (no identity is
     * demanded at the declaration) or {@link Failure} (the unconditional
     * rule-(d) E2010, or a defensive fail-closed state).
     */
    public sealed interface DeclarationResult
            permits DeclarationResult.Ok, DeclarationResult.Failure {

        /** The declaration is admitted; no public identity is demanded. */
        record Ok() implements DeclarationResult {
            public static final Ok INSTANCE = new Ok();
        }

        /**
         * The unconditional E2010 at the class-name span; no class
         * metadata or artifact may be published for this declaration.
         */
        record Failure(CompilerDiagnostic diagnostic) implements DeclarationResult {
            public Failure {
                Objects.requireNonNull(diagnostic, "diagnostic");
            }
        }
    }

    /**
     * The result of the required-identity gate: {@link Identity} (the
     * assembled identity plus its index-registered descriptor text) or
     * {@link Failure} (one E2010; no metadata or artifact may be
     * published).
     */
    public sealed interface ClassIdentityResult
            permits ClassIdentityResult.Identity, ClassIdentityResult.Failure {

        /**
         * The assembled {@link CanonicalClassIdentity} and its registered
         * projection text, byte-for-byte as held by the index.
         */
        record Identity(CanonicalClassIdentity identity, String descriptorText)
                implements ClassIdentityResult {
            public Identity {
                Objects.requireNonNull(identity, "identity");
                Objects.requireNonNull(descriptorText, "descriptorText");
            }
        }

        /** One E2010 at the class-name span; nothing was registered. */
        record Failure(CompilerDiagnostic diagnostic) implements ClassIdentityResult {
            public Failure {
                Objects.requireNonNull(diagnostic, "diagnostic");
            }
        }
    }

    // =========================================================================
    // The intrinsic Error synthesis (c-intrinsic)
    // =========================================================================

    /**
     * Synthesizes the intrinsic builtin {@code Error} class identity with
     * no resolved source: exactly
     * {@code CanonicalClassIdentity(BuiltinModule, "Error")}, the only
     * intrinsic class identity. The identity is registered in the
     * compilation's index with its pinned projection
     * {@link #INTRINSIC_ERROR_DESCRIPTOR_TEXT}; repeated calls return an
     * equal identity and never re-register.
     *
     */
    public CanonicalClassIdentity intrinsicErrorIdentity() {
        if (intrinsicError == null) {
            intrinsicError = new CanonicalClassIdentity(
                CanonicalModuleIdentity.BuiltinModule.INSTANCE,
                INTRINSIC_ERROR_CLASS_NAME);
            register(intrinsicError, INTRINSIC_ERROR_DESCRIPTOR_TEXT);
        }
        return intrinsicError;
    }

    // =========================================================================
    // The declaration gate (unconditional rule (d))
    // =========================================================================

    /**
     * The declaration gate: invoked when a class declaration is processed
     * in a resolved source. For a source with no public module identity —
     * an out-of-root relative source, a rooted non-externals
     * {@code .d.deal} source, or a non-spec-listed {@code .d.deal} inside
     * the std directory — this fires E2010 at the class-name span
     * <b>unconditionally at the declaration</b>, before any
     * class/export/default/FFI metadata or artifact is published (rule
     * (d); no deferred-to-first-use behavior). For every other
     * classification the gate admits the declaration: public identity is
     * demanded only when a consumer requires it (rules (a)–(c)), so
     * class-free code — and class-bearing code whose identity is never
     * required — in those sources is unaffected. The defensive
     * equal-root tie and stdlib/externals overlap states also fail only
     * when identity is required.
     *
     * <p>The gate registers nothing and demands no identity.</p>
     *
     */
    public DeclarationResult gateClassDeclaration(SourceModuleLocation location,
                                                  String className,
                                                  Span classNameSpan) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(classNameSpan, "classNameSpan");
        ModuleIdentityResolver.ModuleClassification classification =
            classificationOf(location);
        if (classification.moduleIdentity() == null
                && classification.issue() == ModuleIdentityResolver.Issue.NONE) {
            return declarationFailure(
                "Class '" + className + "' cannot be represented: the declaring"
                    + " module has no public module identity (an out-of-root"
                    + " relative source, a rooted non-externals .d.deal"
                    + " declaration, or a non-spec-listed .d.deal inside the"
                    + " std directory)",
                classNameSpan);
        }
        return DeclarationResult.Ok.INSTANCE;
    }

    // =========================================================================
    // The required-identity gate (rules (a)–(c) and the defensive re-gates)
    // =========================================================================

    /**
     * The required-identity gate: a consumer that requires public class
     * identity (descriptor emission, FFI metadata, default plans, export
     * metadata) obtains the {@link CanonicalClassIdentity} exclusively
     * through this call. The identity is assembled from the published
     * classification, validated against the pinned representability
     * predicates, and registered in the compilation's
     * {@link CanonicalClassIdentityIndex} together with its descriptor
     * text.
     *
     * <ul>
     *   <li><b>Project form (a):</b> E2010 at the class-name span when
     *       the identity is unrepresentable (class name not
     *       identifier-shaped, reserved first root component, forbidden
     *       characters, whitespace/control scalars, {@code .}/{@code ..}
     *       or empty components, contiguous {@code ->}) or when the
     *       containment is ambiguous (the defensive equal-root tie).
     *       Success returns
     *       {@code @<configuredRootText>/<relativeModuleComponents>/<ClassName>}
     *       (empty relative path → {@code @<rootText>/<ClassName>}).</li>
     *   <li><b>Externals form (b):</b> E2010 at the class-name span when
     *       the raw import specifier or the class name is
     *       unrepresentable, with a note carrying the externals entry's
     *       manifest value range. Success returns
     *       {@code @$external/<rawImportSpecifier>/<ClassName>}.</li>
     *   <li><b>Builtin form (c):</b> E2010 at the class-name span for any
     *       required builtin class identity other than {@code Error},
     *       with the pinned missing-projection note. A required builtin
     *       identity named exactly {@code Error} yields the synthesized
     *       intrinsic identity ({@link #intrinsicErrorIdentity()}).</li>
     *   <li><b>Defensive re-gates:</b> a source with no public module
     *       identity fails exactly like the declaration gate (rule (d));
     *       the defensive stdlib/externals overlap state fails with its
     *       own E2010.</li>
     * </ul>
     *
     * <p>The gate is idempotent and deterministic: equal calls return
     * equal results and register each identity once, and the returned
     * identity is the compilation's single registered instance for that
     * {@code (moduleIdentity, className)} — the index's reverse lookup
     * for the returned text yields exactly that instance.</p>
     *
     */
    public ClassIdentityResult requireClassIdentity(SourceModuleLocation location,
                                                    String className,
                                                    Span classNameSpan) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(classNameSpan, "classNameSpan");
        ModuleIdentityResolver.ModuleClassification classification =
            classificationOf(location);
        CanonicalModuleIdentity moduleIdentity = classification.moduleIdentity();
        if (moduleIdentity == null) {
            if (classification.issue()
                    == ModuleIdentityResolver.Issue.EQUAL_ROOT_TIE) {
                return failure("Class '" + className + "' cannot be"
                    + " represented: the containing configured root is"
                    + " ambiguous (equal-root tie)", classNameSpan,
                    List.of());
            }
            if (classification.issue()
                    == ModuleIdentityResolver.Issue.STDLIB_EXTERNAL_OVERLAP) {
                return failure("Class '" + className + "' cannot be"
                    + " represented: the module's public classification is"
                    + " ambiguous (stdlib/externals overlap)",
                    classNameSpan, List.of());
            }
            return failure("Class '" + className + "' cannot be represented:"
                + " the declaring module has no public module identity (an"
                + " out-of-root relative source, a rooted non-externals"
                + " .d.deal declaration, or a non-spec-listed .d.deal inside"
                + " the std directory)", classNameSpan, List.of());
        }
        if (moduleIdentity instanceof CanonicalModuleIdentity.ProjectModule
                project) {
            return requireProject(project.projectIdentity(), className,
                classNameSpan);
        }
        if (moduleIdentity instanceof CanonicalModuleIdentity.ExternalModule
                external) {
            return requireExternal(external.rawImportSpecifier(), className,
                classNameSpan);
        }
        return requireBuiltin(className, classNameSpan);
    }

    // =========================================================================
    // The index
    // =========================================================================

    /**
     * The compilation's canonical class-identity index: keyed by
     * {@code (moduleIdentity, className)} and by descriptor text, with
     * byte-for-byte text keys. Every identity registered by this assembly
     * (through the required-identity gate or the intrinsic synthesis) is
     * available through both lookup directions; lookups for absent keys
     * are internal invariant violations and throw
     * {@link IllegalStateException} (never fallback text, never
     * reconstruction). Consumers use this index and never reverse-parse a
     * root/relative-path boundary.
     *
     * <p>The returned view is read-only; its contents are deterministic
     * for identical {@code (ProjectContext, resolved-source stream)}
     * inputs.</p>
     *
     */
    public CanonicalClassIdentityIndex index() {
        return index;
    }

    // =========================================================================
    // Per-classification require paths
    // =========================================================================

    /**
     * Rule (a): the project form. Checks, in pinned deterministic order:
     * the configured root text (T5 configured-root predicate, including
     * the {@code $external}/{@code $builtin} exact-first-component
     * reservation), the relative module path components (per-component
     * predicate), and the class-name identifier shape. A failure is E2010
     * at the class-name span; success assembles and registers
     * {@code @<rootText>/<components>/<ClassName>}.
     */
    private ClassIdentityResult requireProject(
            ProjectModuleIdentity projectIdentity, String className,
            Span classNameSpan) {
        if (!ModuleIdentityResolver.isRepresentableConfiguredRootText(
                projectIdentity.configuredRootText())) {
            return failure("Class '" + className + "' cannot be represented"
                + " in the project descriptor form: the configured root '"
                + projectIdentity.configuredRootText()
                + "' is not representable", classNameSpan, List.of());
        }
        String badComponent = firstUnrepresentableComponent(
            projectIdentity.relativeModuleComponents());
        if (badComponent != null) {
            return failure("Class '" + className + "' cannot be represented"
                + " in the project descriptor form: the relative module path"
                + " component '" + badComponent + "' is not representable",
                classNameSpan, List.of());
        }
        if (!ModuleIdentityResolver.isIdentifierShapedClassName(className)) {
            return failure("Class '" + className + "' cannot be represented:"
                + " the class name is not identifier-shaped", classNameSpan,
                List.of());
        }
        CanonicalClassIdentity identity = new CanonicalClassIdentity(
            new CanonicalModuleIdentity.ProjectModule(projectIdentity),
            className);
        String descriptorText = projectDescriptorText(projectIdentity, className);
        register(identity, descriptorText);
        return new ClassIdentityResult.Identity(
            identitiesByText.get(descriptorText), descriptorText);
    }

    /**
     * Rule (b): the {@code @$external} form. Checks, in pinned
     * deterministic order: the class-name identifier shape and the raw
     * import specifier (per-component predicate). A failure is E2010 at
     * the class-name span with the externals-entry note carrying the
     * entry's manifest value range; success assembles and registers
     * {@code @$external/<rawImportSpecifier>/<ClassName>}.
     */
    private ClassIdentityResult requireExternal(String rawImportSpecifier,
                                                String className,
                                                Span classNameSpan) {
        List<DiagnosticNote> notes = externalsEntryNote(rawImportSpecifier);
        if (!ModuleIdentityResolver.isIdentifierShapedClassName(className)) {
            return failure("Class '" + className + "' cannot be represented"
                + " in the '@$external' descriptor form: the class name is"
                + " not identifier-shaped", classNameSpan, notes);
        }
        if (!ModuleIdentityResolver.isRepresentableExternalSpecifier(
                rawImportSpecifier)) {
            return failure("Class '" + className + "' cannot be represented"
                + " in the '@$external' descriptor form: the externals raw"
                + " import specifier '" + rawImportSpecifier
                + "' is not representable", classNameSpan, notes);
        }
        CanonicalClassIdentity identity = new CanonicalClassIdentity(
            new CanonicalModuleIdentity.ExternalModule(rawImportSpecifier),
            className);
        String descriptorText = "@" + EXTERNAL_NAMESPACE + "/"
            + rawImportSpecifier + "/" + className;
        register(identity, descriptorText);
        return new ClassIdentityResult.Identity(
            identitiesByText.get(descriptorText), descriptorText);
    }

    /**
     * Rule (c): the builtin form. The stdlib surface declares no classes
     * today, so a required builtin class identity other than {@code Error}
     * is E2010 at the class-name span with the pinned missing-projection
     * note. A required builtin identity named exactly {@code Error}
     * yields the synthesized intrinsic identity (the only representable
     * builtin class identity).
     */
    private ClassIdentityResult requireBuiltin(String className,
                                               Span classNameSpan) {
        if (INTRINSIC_ERROR_CLASS_NAME.equals(className)) {
            CanonicalClassIdentity identity = intrinsicErrorIdentity();
            return new ClassIdentityResult.Identity(identity,
                INTRINSIC_ERROR_DESCRIPTOR_TEXT);
        }
        return failure("Class '" + className + "' cannot be represented: no"
            + " descriptor projection is pinned for a builtin module class"
            + " other than 'Error' (the stdlib surface declares no classes"
            + " today)", classNameSpan,
            List.of(new DiagnosticNote("missing projection: the projection of"
                + " a future stdlib class is delegated to the"
                + " stdlib/descriptor epics", null)));
    }

    // =========================================================================
    // Classification resolution (consuming the resolved-source stream)
    // =========================================================================

    /**
     * Resolves the classification to gate on: the location's recorded
     * classification — the provenance of T6's single classifier
     * invocation per resolved source — is authoritative when present;
     * when the location records no classification, the classification is
     * re-derived purely lexically from the location's canonical resolved
     * source URI (no filesystem access) so the defensive equal-root tie
     * and stdlib/externals overlap states — unreachable for a valid
     * {@code ProjectContext} — still map to their pinned
     * E2010-when-required behavior instead of the unconditional rule-(d)
     * declaration failure.
     *
     * <p>The location must belong to this assembly's project deployment
     * (its private deployment identity must equal the context's); a
     * location from another deployment is a caller error and fails
     * closed.</p>
     */
    private ModuleIdentityResolver.ModuleClassification classificationOf(
            SourceModuleLocation location) {
        if (!location.semanticModuleIdentity().projectDeploymentIdentity()
                .equals(context.projectDeploymentIdentity())) {
            throw new IllegalArgumentException(
                "SourceModuleLocation is not from this assembly's project"
                    + " deployment: " + location.semanticModuleIdentity()
                        .projectDeploymentIdentity()
                    + " vs " + context.projectDeploymentIdentity());
        }
        CanonicalModuleIdentity recorded = location.moduleClassification();
        if (recorded != null) {
            return new ModuleIdentityResolver.ModuleClassification(recorded,
                location.projectIdentity(), ModuleIdentityResolver.Issue.NONE);
        }
        return ModuleIdentityResolver.classify(context,
            location.semanticModuleIdentity().canonicalResolvedSourceUri());
    }

    // =========================================================================
    // Projection and registration internals
    // =========================================================================

    /**
     * The pinned project descriptor-text projection:
     * {@code @<configuredRootText>/<relativeModuleComponents>/<ClassName>}
     * with the source suffix and file stem omitted; an empty relative
     * module path yields {@code @<configuredRootText>/<ClassName>}.
     */
    private static String projectDescriptorText(
            ProjectModuleIdentity projectIdentity, String className) {
        StringBuilder sb = new StringBuilder("@")
            .append(projectIdentity.configuredRootText());
        for (String component : projectIdentity.relativeModuleComponents()) {
            sb.append('/').append(component);
        }
        return sb.append('/').append(className).toString();
    }

    /**
     * The first relative module path component failing the pinned
     * per-component representability predicate, or null when every
     * component is representable.
     */
    private static String firstUnrepresentableComponent(
            List<String> relativeModuleComponents) {
        for (String component : relativeModuleComponents) {
            if (!ModuleIdentityResolver.isRepresentableDescriptorComponent(
                    component)) {
                return component;
            }
        }
        return null;
    }

    /**
     * The pinned externals-entry note for rule (b): a note naming the
     * entry and carrying its manifest value range when the context's
     * entry carries one (a SOURCE range in the manifest file); a
     * message-only note otherwise (defensive for fabricated contexts).
     */
    private List<DiagnosticNote> externalsEntryNote(String rawImportSpecifier) {
        ExternalEntry entry = context.externals().get(rawImportSpecifier);
        if (entry == null || entry.sourceRange() == null) {
            return List.of(new DiagnosticNote(
                "externals entry '" + rawImportSpecifier + "'", null));
        }
        SourceScalarRange range = entry.sourceRange();
        DiagnosticRange manifestRange = new DiagnosticRange(
            context.manifestPath(),
            range.startLine(), range.startColumn(),
            range.endLine(), range.endColumn(),
            range.startScalarOffset(), range.endScalarOffset(),
            range.scalarLength(), RangeOrigin.SOURCE);
        return List.of(new DiagnosticNote(
            "externals entry '" + rawImportSpecifier + "'", manifestRange));
    }

    /**
     * Registers one assembled identity with its projection text. The
     * registration is idempotent (an identity already registered with
     * the same text is left as-is) and a bijection: registering a text
     * already bound to a different identity — or an identity already
     * bound to a different text — is an internal invariant violation and
     * fails closed with {@link IllegalStateException}, never a silent
     * overwrite.
     */
    private void register(CanonicalClassIdentity identity, String descriptorText) {
        String existingText = descriptorTexts.get(identity);
        if (existingText != null) {
            if (!existingText.equals(descriptorText)) {
                throw new IllegalStateException("identity already registered"
                    + " with a different descriptor text: " + identity
                    + " -> '" + existingText + "' vs '" + descriptorText + "'");
            }
            return;
        }
        CanonicalClassIdentity existingIdentity =
            identitiesByText.get(descriptorText);
        if (existingIdentity != null && !existingIdentity.equals(identity)) {
            throw new IllegalStateException("descriptor text '"
                + descriptorText + "' is already registered for a different"
                + " identity: " + existingIdentity + " vs " + identity);
        }
        descriptorTexts.put(identity, descriptorText);
        identitiesByText.put(descriptorText, identity);
    }

    /**
     * The read-only index view over this assembly's registration maps:
     * lookups are byte-for-byte, an absent key is an internal invariant
     * violation for the caller ({@link IllegalStateException}), and
     * {@code identityForDescriptorText} returns the exact registered
     * instance — never reconstructed structure.
     */
    private final class Index implements CanonicalClassIdentityIndex {

        @Override
        public String descriptorTextFor(CanonicalClassIdentity identity) {
            Objects.requireNonNull(identity, "identity");
            String text = descriptorTexts.get(identity);
            if (text == null) {
                throw new IllegalStateException(
                    "identity absent from the index: " + identity);
            }
            return text;
        }

        @Override
        public CanonicalClassIdentity identityForDescriptorText(
                String descriptorText) {
            Objects.requireNonNull(descriptorText, "descriptorText");
            CanonicalClassIdentity identity =
                identitiesByText.get(descriptorText);
            if (identity == null) {
                throw new IllegalStateException(
                    "descriptor text absent from the index: \""
                        + descriptorText + "\"");
            }
            return identity;
        }
    }

    // =========================================================================
    // Diagnostic helpers
    // =========================================================================

    /**
     * One E2010 at the class-name span with the pinned notes appended
     * after the carrier's anchor note (when the span yields the synthetic
     * shape). The message never contains a private identity URI.
     */
    private static ClassIdentityResult failure(String message, Span classNameSpan,
                                               List<DiagnosticNote> notes) {
        return new ClassIdentityResult.Failure(
            diagnostic(message, classNameSpan, notes));
    }

    /**
     * One unconditional declaration-gate E2010 at the class-name span.
     */
    private static DeclarationResult declarationFailure(String message,
                                                        Span classNameSpan) {
        return new DeclarationResult.Failure(
            diagnostic(message, classNameSpan, List.of()));
    }

    /**
     * Builds the E2010 carrier: the span→range conversion supplies the
     * complete SOURCE range (or the canonical synthetic shape plus the
     * anchor note), and the pinned notes are appended.
     */
    private static CompilerDiagnostic diagnostic(String message,
                                                 Span classNameSpan,
                                                 List<DiagnosticNote> notes) {
        CompilerDiagnostic base =
            CompilerDiagnostic.error(DiagnosticCode.E2010, message,
                classNameSpan);
        if (notes.isEmpty()) {
            return base;
        }
        List<DiagnosticNote> combined = new ArrayList<>(base.notes());
        combined.addAll(notes);
        return new CompilerDiagnostic(base.code(), base.severity(),
            base.message(), base.range(), combined, base.diagnosticCode());
    }
}
