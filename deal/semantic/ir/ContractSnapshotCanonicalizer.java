package deal.semantic.ir;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The single operation-contract snapshot canonicalizer of
 * {@code deal.semantic-ir/1} (schema S2; foundation F8): the only
 * production implementation that maps {@link OperationContractSnapshot}
 * records (and every schema record they embed — kind payloads, semantic
 * IDs, descriptors, scalars, bindings, realizations, adapt-source refs,
 * tokens, layouts) onto the single {@link CanonicalJson} value model and
 * computes the pinned digest. The validator, dumper, and trace harness
 * consume canonicalization through this one component and the one
 * {@code CanonicalJson} facility — no consumer serializes for itself.
 *
 * <p>{@code canonicalDigest = SHA-256(canonical JSON bytes)} over exactly
 * {@code {version, opKind, resultType, operandTypes, selector?, payload,
 * failurePolicy, referencedSemanticIds}} — the eight pinned fields of the
 * snapshot record, with the optional {@code selector} rendered as an
 * explicit {@code null} when absent (S2 explicit-nulls rule). The
 * snapshot's carried {@code canonicalDigest} field is itself <em>not</em>
 * part of the digest input; {@code opId}, source coordinates, and trace
 * phase live on {@link SemanticOp}/{@link SourceOrigin} outside the
 * snapshot and never enter the digest, while behavior-referenced IDs
 * (class/function/binding/block/module …) are inside it.</p>
 *
 * <p>Pinned JSON shapes (all keys ASCII; the serializer sorts object
 * keys): closed enum values render as their names; descriptors render as
 * their canonical spec text ({@code "int"}, {@code "@src/app/User"},
 * {@code "(int,string)->boolean"}); scalars render as canonical JSON
 * scalars (decimal signed32, unique hex floats, explicit null); optional
 * record components render as explicit {@code null}s; semantic IDs render
 * as discriminated objects ({@code {"type":"module","path":"a.b"}}, …);
 * payloads render as one object per closed kind shape with the record
 * component names as keys. The parser inverts these shapes exactly and
 * returns a {@link SnapshotJsonRecord} whose enum positions carry raw
 * strings (no enum conversion at parse time).</p>
 */
public final class ContractSnapshotCanonicalizer {

    private ContractSnapshotCanonicalizer() {
        // Static utility; no instances.
    }

    // =========================================================================
    // Pinned snapshot field names
    // =========================================================================

    /** The pinned snapshot field name {@code "version"}. */
    public static final String FIELD_VERSION = "version";

    /** The pinned snapshot field name {@code "opKind"}. */
    public static final String FIELD_OP_KIND = "opKind";

    /** The pinned snapshot field name {@code "resultType"}. */
    public static final String FIELD_RESULT_TYPE = "resultType";

    /** The pinned snapshot field name {@code "operandTypes"}. */
    public static final String FIELD_OPERAND_TYPES = "operandTypes";

    /** The pinned snapshot field name {@code "selector"}. */
    public static final String FIELD_SELECTOR = "selector";

    /** The pinned snapshot field name {@code "payload"}. */
    public static final String FIELD_PAYLOAD = "payload";

    /** The pinned snapshot field name {@code "failurePolicy"}. */
    public static final String FIELD_FAILURE_POLICY = "failurePolicy";

    /** The pinned snapshot field name {@code "referencedSemanticIds"}. */
    public static final String FIELD_REFERENCED_SEMANTIC_IDS = "referencedSemanticIds";

    /** The exact eight pinned snapshot field names (the digest field set). */
    public static final List<String> SNAPSHOT_FIELDS = List.of(
        FIELD_VERSION, FIELD_OP_KIND, FIELD_RESULT_TYPE, FIELD_OPERAND_TYPES,
        FIELD_SELECTOR, FIELD_PAYLOAD, FIELD_FAILURE_POLICY,
        FIELD_REFERENCED_SEMANTIC_IDS);

    // =========================================================================
    // Snapshot canonicalization and digest
    // =========================================================================

    /**
     * Maps the snapshot onto the single canonical JSON value model. The
     * carried {@code canonicalDigest} field is not part of the mapping —
     * the digest is computed over the eight pinned fields only.
     *
     * @param snapshot the snapshot; non-null
     * @return the canonical JSON object
     */
    public static CanonicalJson.Value toJson(OperationContractSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return CanonicalJson.obj(
            CanonicalJson.e(FIELD_FAILURE_POLICY, CanonicalJson.str(snapshot.failurePolicy().name())),
            CanonicalJson.e(FIELD_OP_KIND, CanonicalJson.str(snapshot.opKind().name())),
            CanonicalJson.e(FIELD_OPERAND_TYPES, CanonicalJson.arr(
                snapshot.operandTypes().stream().map(d -> (CanonicalJson.Value) descriptorText(d)).toList())),
            CanonicalJson.e(FIELD_PAYLOAD, payloadJson(snapshot.payload())),
            CanonicalJson.e(FIELD_REFERENCED_SEMANTIC_IDS, CanonicalJson.arr(
                snapshot.referencedSemanticIds().stream().map(ContractSnapshotCanonicalizer::semanticIdJson).toList())),
            CanonicalJson.e(FIELD_RESULT_TYPE, resultTypeJson(snapshot.resultType())),
            CanonicalJson.e(FIELD_SELECTOR,
                snapshot.selector() == null ? CanonicalJson.nullValue()
                    : CanonicalJson.str(selectorName(snapshot.selector()))),
            CanonicalJson.e(FIELD_VERSION, CanonicalJson.intValue(snapshot.version())));
    }

    /**
     * Serializes the snapshot to its canonical JSON bytes (UTF-8, sorted
     * keys, explicit nulls).
     *
     * @param snapshot the snapshot; non-null
     * @return the canonical JSON bytes
     */
    public static byte[] serialize(OperationContractSnapshot snapshot) {
        return CanonicalJson.serializeBytes(toJson(snapshot));
    }

    /** Serializes the snapshot to canonical JSON text (debug/trace surface only). */
    public static String serializeText(OperationContractSnapshot snapshot) {
        return CanonicalJson.serializeText(toJson(snapshot));
    }

    /**
     * Computes the pinned digest: {@code SHA-256(canonical JSON bytes)}
     * over exactly {@code {version, opKind, resultType, operandTypes,
     * selector?, payload, failurePolicy, referencedSemanticIds}}. Changing
     * any behavior field changes the digest; changing only {@code opId},
     * source coordinates, or trace phase (fields that live outside the
     * snapshot) does not.
     *
     * @param snapshot the snapshot; non-null
     * @return the lowercase 64-character hex digest
     */
    public static String digest(OperationContractSnapshot snapshot) {
        return CanonicalJson.sha256Hex(serialize(snapshot));
    }

    /**
     * Recomputes the digest and reports whether it equals the snapshot's
     * carried {@code canonicalDigest} (R-DIGEST's recomputation, without
     * being a validator rule itself).
     *
     * @param snapshot the snapshot; non-null
     * @return true iff the carried digest equals the recomputed digest
     */
    public static boolean matchesCarriedDigest(OperationContractSnapshot snapshot) {
        return digest(snapshot).equals(snapshot.canonicalDigest());
    }

    // =========================================================================
    // Parser (exact inverse)
    // =========================================================================

    /**
     * Parses a serialized snapshot back through the single canonical JSON
     * parser: the pinned field names and JSON value types are mapped into a
     * {@link SnapshotJsonRecord} whose enum positions carry raw strings.
     * No closed-enum, reserved-name, policy, boundary-assignment, or
     * profile validation happens here.
     *
     * @param utf8 the serialized snapshot bytes; non-null
     * @return the intermediate record
     * @throws SemanticIrTextDecodeException on malformed JSON, invalid
     *         UTF-8, a duplicate/unknown/missing field, a wrong value type,
     *         or a wrong version
     */
    public static SnapshotJsonRecord parseSnapshot(byte[] utf8) {
        CanonicalJson.Value value = CanonicalJson.parse(utf8);
        if (!(value instanceof CanonicalJson.Obj obj)) {
            throw new SemanticIrTextDecodeException(
                "the top-level snapshot value must be an object, got "
                    + jsonKindName(value));
        }
        return snapshotFromJson(obj);
    }

    /**
     * Parses a serialized snapshot text back through the single canonical
     * JSON parser.
     *
     * @param text the serialized snapshot text; non-null
     * @return the intermediate record
     * @throws SemanticIrTextDecodeException as in {@link #parseSnapshot(byte[])}
     */
    public static SnapshotJsonRecord parseSnapshot(String text) {
        return parseSnapshot(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Maps the intermediate record back onto the canonical JSON value model. */
    public static CanonicalJson.Value toJson(SnapshotJsonRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        if (!(record.payload() instanceof CanonicalJson.Obj)) {
            throw new IllegalArgumentException("SnapshotJsonRecord.payload must be a JSON object");
        }
        return CanonicalJson.obj(
            CanonicalJson.e(FIELD_FAILURE_POLICY, CanonicalJson.str(record.failurePolicy())),
            CanonicalJson.e(FIELD_OP_KIND, CanonicalJson.str(record.opKind())),
            CanonicalJson.e(FIELD_OPERAND_TYPES, CanonicalJson.arr(
                record.operandTypes().stream().map(v -> (CanonicalJson.Value) CanonicalJson.str(v)).toList())),
            CanonicalJson.e(FIELD_PAYLOAD, record.payload()),
            CanonicalJson.e(FIELD_REFERENCED_SEMANTIC_IDS,
                CanonicalJson.arr(record.referencedSemanticIds())),
            CanonicalJson.e(FIELD_RESULT_TYPE,
                record.resultType() == null ? CanonicalJson.nullValue()
                    : CanonicalJson.str(record.resultType())),
            CanonicalJson.e(FIELD_SELECTOR,
                record.selector() == null ? CanonicalJson.nullValue()
                    : CanonicalJson.str(record.selector())),
            CanonicalJson.e(FIELD_VERSION, CanonicalJson.intValue(record.version())));
    }

    /** Serializes the intermediate record to canonical JSON bytes. */
    public static byte[] serialize(SnapshotJsonRecord record) {
        return CanonicalJson.serializeBytes(toJson(record));
    }

    /** Serializes the intermediate record to canonical JSON text (debug/trace surface only). */
    public static String serializeText(SnapshotJsonRecord record) {
        return CanonicalJson.serializeText(toJson(record));
    }

    private static SnapshotJsonRecord snapshotFromJson(CanonicalJson.Obj obj) {
        for (String key : knownKeys(obj)) {
            if (!SNAPSHOT_FIELDS.contains(key)) {
                throw new SemanticIrTextDecodeException(
                    "unknown snapshot field \"" + key + "\" (pinned field set is "
                        + SNAPSHOT_FIELDS + ")");
            }
        }
        int version = expectInt(field(obj, FIELD_VERSION), FIELD_VERSION);
        if (version != OperationContractSnapshot.VERSION) {
            throw new SemanticIrTextDecodeException(
                "version mismatch: expected " + OperationContractSnapshot.VERSION
                    + " in deal.semantic-ir/1, got " + version);
        }
        String opKind = expectString(field(obj, FIELD_OP_KIND), FIELD_OP_KIND);
        String resultType = expectStringOrNull(field(obj, FIELD_RESULT_TYPE), FIELD_RESULT_TYPE);
        CanonicalJson.Arr operandTypes = expectArray(field(obj, FIELD_OPERAND_TYPES),
            FIELD_OPERAND_TYPES);
        List<String> operandTypeTexts = new ArrayList<>();
        for (int i = 0; i < operandTypes.items().size(); i++) {
            operandTypeTexts.add(expectString(operandTypes.items().get(i),
                FIELD_OPERAND_TYPES + "[" + i + "]"));
        }
        String selector = expectStringOrNull(field(obj, FIELD_SELECTOR), FIELD_SELECTOR);
        CanonicalJson.Value payload = field(obj, FIELD_PAYLOAD);
        if (!(payload instanceof CanonicalJson.Obj)) {
            throw new SemanticIrTextDecodeException(
                "the \"" + FIELD_PAYLOAD + "\" field must be an object, got "
                    + jsonKindName(payload));
        }
        String failurePolicy = expectString(field(obj, FIELD_FAILURE_POLICY), FIELD_FAILURE_POLICY);
        CanonicalJson.Arr referenced = expectArray(
            field(obj, FIELD_REFERENCED_SEMANTIC_IDS), FIELD_REFERENCED_SEMANTIC_IDS);
        for (int i = 0; i < referenced.items().size(); i++) {
            if (!(referenced.items().get(i) instanceof CanonicalJson.Obj)) {
                throw new SemanticIrTextDecodeException(
                    "the " + FIELD_REFERENCED_SEMANTIC_IDS + "[" + i
                        + "] entry must be a semantic-ID object, got "
                        + jsonKindName(referenced.items().get(i)));
            }
        }
        return new SnapshotJsonRecord(version, opKind, resultType,
            List.copyOf(operandTypeTexts), selector, payload, failurePolicy,
            List.copyOf(referenced.items()));
    }

    private static List<String> knownKeys(CanonicalJson.Obj obj) {
        List<String> keys = new ArrayList<>();
        for (CanonicalJson.Entry entry : obj.entries()) {
            keys.add(entry.key());
        }
        return keys;
    }

    private static CanonicalJson.Value field(CanonicalJson.Obj obj, String name) {
        for (CanonicalJson.Entry entry : obj.entries()) {
            if (entry.key().equals(name)) {
                return entry.value();
            }
        }
        throw new SemanticIrTextDecodeException(
            "missing snapshot field \"" + name + "\" (pinned field set is "
                + SNAPSHOT_FIELDS + ")");
    }

    private static int expectInt(CanonicalJson.Value value, String field) {
        if (value instanceof CanonicalJson.Int i) {
            return i.value();
        }
        throw new SemanticIrTextDecodeException(
            "the \"" + field + "\" field must be a canonical signed32 integer, got "
                + jsonKindName(value));
    }

    private static String expectString(CanonicalJson.Value value, String field) {
        if (value instanceof CanonicalJson.Str s) {
            return s.value();
        }
        throw new SemanticIrTextDecodeException(
            "the \"" + field + "\" field must be a string, got " + jsonKindName(value));
    }

    private static String expectStringOrNull(CanonicalJson.Value value, String field) {
        if (value instanceof CanonicalJson.Null) {
            return null;
        }
        return expectString(value, field);
    }

    private static CanonicalJson.Arr expectArray(CanonicalJson.Value value, String field) {
        if (value instanceof CanonicalJson.Arr a) {
            return a;
        }
        throw new SemanticIrTextDecodeException(
            "the \"" + field + "\" field must be an array, got " + jsonKindName(value));
    }

    private static String jsonKindName(CanonicalJson.Value value) {
        return switch (value) {
            case CanonicalJson.Null ignored -> "null";
            case CanonicalJson.Bool b -> "boolean (" + b.value() + ")";
            case CanonicalJson.Int i -> "integer (" + i.value() + ")";
            case CanonicalJson.Num n -> "number";
            case CanonicalJson.Str s -> "string (\"" + s.value() + "\")";
            case CanonicalJson.Arr a -> "array (" + a.items().size() + " items)";
            case CanonicalJson.Obj o -> "object (" + o.entries().size() + " entries)";
        };
    }

    // =========================================================================
    // Shared shape mappings (the single production implementation)
    // =========================================================================

    /** Renders a runtime descriptor as its canonical spec text (parent D6). */
    public static CanonicalJson.Str descriptorText(RuntimeDescriptor descriptor) {
        return CanonicalJson.str(Objects.requireNonNull(descriptor, "descriptor must not be null")
            .canonicalSpecText());
    }

    /** Renders an op result type: descriptor text, internal sentinel name, or explicit null. */
    public static CanonicalJson.Value resultTypeJson(OpResultType resultType) {
        if (resultType == null) {
            return CanonicalJson.nullValue();
        }
        if (resultType instanceof RuntimeDescriptor descriptor) {
            return descriptorText(descriptor);
        }
        if (resultType instanceof InternalResultType internal) {
            return CanonicalJson.str(internal.name());
        }
        throw new IllegalArgumentException("unknown result type: " + resultType.getClass());
    }

    /** Renders a canonical scalar. */
    public static CanonicalJson.Value scalarJson(ScalarValue scalar) {
        Objects.requireNonNull(scalar, "scalar must not be null");
        return switch (scalar) {
            case ScalarValue.Null ignored -> CanonicalJson.nullValue();
            case ScalarValue.Boolean b -> CanonicalJson.bool(b.value());
            case ScalarValue.Int i -> CanonicalJson.intValue(i.value());
            case ScalarValue.Number n -> CanonicalJson.number(n.value());
            case ScalarValue.String s -> CanonicalJson.str(s.value());
        };
    }

    /** Renders a semantic ID as its pinned discriminated object. */
    public static CanonicalJson.Value semanticIdJson(SemanticId id) {
        Objects.requireNonNull(id, "id must not be null");
        return switch (id) {
            case ModuleId m -> CanonicalJson.obj(
                CanonicalJson.e("path", CanonicalJson.str(m.path())),
                CanonicalJson.e("type", CanonicalJson.str("module")));
            case ClassId c -> CanonicalJson.obj(
                CanonicalJson.e("modulePath", CanonicalJson.str(c.modulePath())),
                CanonicalJson.e("name", CanonicalJson.str(c.name())),
                CanonicalJson.e("type", CanonicalJson.str("class")));
            case FunctionId f -> CanonicalJson.obj(
                CanonicalJson.e("id", idValue(f.id(), "FunctionId")),
                CanonicalJson.e("type", CanonicalJson.str("function")));
            case BindingId b -> CanonicalJson.obj(
                CanonicalJson.e("id", idValue(b.id(), "BindingId")),
                CanonicalJson.e("type", CanonicalJson.str("binding")));
            case ValueId v -> CanonicalJson.obj(
                CanonicalJson.e("id", idValue(v.id(), "ValueId")),
                CanonicalJson.e("type", CanonicalJson.str("value")));
            case AnchorId a -> CanonicalJson.obj(
                CanonicalJson.e("id", idValue(a.id(), "AnchorId")),
                CanonicalJson.e("type", CanonicalJson.str("anchor")));
            case BlockId b -> CanonicalJson.obj(
                CanonicalJson.e("id", idValue(b.id(), "BlockId")),
                CanonicalJson.e("type", CanonicalJson.str("block")));
            case OpId o -> CanonicalJson.obj(
                CanonicalJson.e("id", idValue(o.id(), "OpId")),
                CanonicalJson.e("modulePath", CanonicalJson.str(o.module().path())),
                CanonicalJson.e("type", CanonicalJson.str("op")));
            case ClassFactoryId c -> CanonicalJson.obj(
                CanonicalJson.e("id", idValue(c.id(), "ClassFactoryId")),
                CanonicalJson.e("type", CanonicalJson.str("classFactory")));
            case FunctionAllocationIdentity f -> CanonicalJson.obj(
                CanonicalJson.e("id", idValue(f.id(), "FunctionAllocationIdentity")),
                CanonicalJson.e("type", CanonicalJson.str("functionAllocation")));
            case AsyncTokenId.Canonical c -> CanonicalJson.obj(
                CanonicalJson.e("owner", CanonicalJson.str(c.owner().name())),
                CanonicalJson.e("tokenId", idValue(c.tokenId(), "tokenId")),
                CanonicalJson.e("type", CanonicalJson.str("tokenCanonical")));
            case AsyncTokenId.Alias a -> CanonicalJson.obj(
                CanonicalJson.e("linkKind", CanonicalJson.str(a.linkKind().name())),
                CanonicalJson.e("referent", semanticIdJson(a.referent())),
                CanonicalJson.e("tokenId", idValue(a.tokenId(), "tokenId")),
                CanonicalJson.e("type", CanonicalJson.str("tokenAlias")));
        };
    }

    /** Renders a boundary realization as its pinned discriminated object. */
    public static CanonicalJson.Value realizationJson(BoundaryRealization realization) {
        Objects.requireNonNull(realization, "realization must not be null");
        return switch (realization) {
            case BoundaryRealization.RuntimeValidation r -> CanonicalJson.obj(
                CanonicalJson.e("checkId", CanonicalJson.str(r.checkId())),
                CanonicalJson.e("type", CanonicalJson.str("runtimeValidation")));
            case BoundaryRealization.RepresentationProof p -> CanonicalJson.obj(
                CanonicalJson.e("proofKind", CanonicalJson.str(p.proofKind())),
                CanonicalJson.e("type", CanonicalJson.str("representationProof")));
        };
    }

    /** Renders a function execution binding as its pinned discriminated object. */
    public static CanonicalJson.Value bindingJson(FunctionExecutionBinding binding) {
        Objects.requireNonNull(binding, "binding must not be null");
        return switch (binding) {
            case FunctionExecutionBinding.LoweredBody b -> CanonicalJson.obj(
                CanonicalJson.e("blockId", semanticIdJson(b.blockId())),
                CanonicalJson.e("functionId", semanticIdJson(b.functionId())),
                CanonicalJson.e("type", CanonicalJson.str("loweredBody")));
            case FunctionExecutionBinding.AdapterBinding b -> CanonicalJson.obj(
                CanonicalJson.e("adaptOpId", semanticIdJson(b.adaptOpId())),
                CanonicalJson.e("captureMode", CanonicalJson.str(b.captureMode().name())),
                CanonicalJson.e("sourceRef", sourceRefJson(b.sourceRef())),
                CanonicalJson.e("sourceSignature", descriptorText(b.sourceSignature())),
                CanonicalJson.e("targetSignature", descriptorText(b.targetSignature())),
                CanonicalJson.e("type", CanonicalJson.str("adapter")));
            case FunctionExecutionBinding.HostFunction b -> CanonicalJson.obj(
                CanonicalJson.e("descriptor", descriptorText(b.descriptor())),
                CanonicalJson.e("exportName", CanonicalJson.str(b.exportName())),
                CanonicalJson.e("hostModuleId", semanticIdJson(b.hostModuleId())),
                CanonicalJson.e("type", CanonicalJson.str("hostFunction")));
            case FunctionExecutionBinding.HostFunctionValue b -> CanonicalJson.obj(
                CanonicalJson.e("descriptor", descriptorText(b.descriptor())),
                CanonicalJson.e("hostModuleId", semanticIdJson(b.hostModuleId())),
                CanonicalJson.e("materializingBoundaryOpId",
                    semanticIdJson(b.materializingBoundaryOpId())),
                CanonicalJson.e("type", CanonicalJson.str("hostFunctionValue")));
            case FunctionExecutionBinding.ExternalFunction b -> CanonicalJson.obj(
                CanonicalJson.e("descriptor", descriptorText(b.descriptor())),
                CanonicalJson.e("executionOwner", CanonicalJson.str(b.executionOwner().name())),
                CanonicalJson.e("exportName", CanonicalJson.str(b.exportName())),
                CanonicalJson.e("moduleId", semanticIdJson(b.moduleId())),
                CanonicalJson.e("type", CanonicalJson.str("externalFunction")));
        };
    }

    /** Renders a call callee reference as its pinned discriminated object. */
    public static CanonicalJson.Value calleeJson(KindPayload.CallCallee callee) {
        Objects.requireNonNull(callee, "callee must not be null");
        return switch (callee) {
            case KindPayload.CallCallee.Static s -> CanonicalJson.obj(
                CanonicalJson.e("binding", bindingJson(s.binding())),
                CanonicalJson.e("type", CanonicalJson.str("static")));
            case KindPayload.CallCallee.Indirect i -> CanonicalJson.obj(
                CanonicalJson.e("callee", semanticIdJson(i.callee())),
                CanonicalJson.e("type", CanonicalJson.str("indirect")));
        };
    }

    /** Renders an adapter source reference as its pinned discriminated object. */
    public static CanonicalJson.Value sourceRefJson(AdaptSourceRef sourceRef) {
        Objects.requireNonNull(sourceRef, "sourceRef must not be null");
        return switch (sourceRef) {
            case AdaptSourceRef.SharedCell s -> CanonicalJson.obj(
                CanonicalJson.e("binding", semanticIdJson(s.binding())),
                CanonicalJson.e("generation", idValue(s.generation(), "generation")),
                CanonicalJson.e("type", CanonicalJson.str("sharedCell")));
            case AdaptSourceRef.Thunk t -> CanonicalJson.obj(
                CanonicalJson.e("blockId", semanticIdJson(t.blockId())),
                CanonicalJson.e("capturedBindings", CanonicalJson.arr(
                    t.capturedBindings().stream().map(ContractSnapshotCanonicalizer::bindingGenerationJson).toList())),
                CanonicalJson.e("type", CanonicalJson.str("thunk")));
            case AdaptSourceRef.Value v -> CanonicalJson.obj(
                CanonicalJson.e("type", CanonicalJson.str("value")),
                CanonicalJson.e("value", semanticIdJson(v.value())));
        };
    }

    /** Renders a binding generation reference. */
    public static CanonicalJson.Value bindingGenerationJson(BindingGeneration generation) {
        Objects.requireNonNull(generation, "generation must not be null");
        return CanonicalJson.obj(
            CanonicalJson.e("binding", semanticIdJson(generation.binding())),
            CanonicalJson.e("generation", idValue(generation.generation(), "generation")));
    }

    /** Renders a binding immutability proof. */
    public static CanonicalJson.Value immutabilityProofJson(BindingImmutabilityProof proof) {
        Objects.requireNonNull(proof, "proof must not be null");
        return CanonicalJson.obj(
            CanonicalJson.e("binding", semanticIdJson(proof.binding())),
            CanonicalJson.e("generation", idValue(proof.generation(), "generation")));
    }

    /** Renders an external async-linkage record. */
    public static CanonicalJson.Value externalAsyncLinkJson(ExternalAsyncLink link) {
        Objects.requireNonNull(link, "link must not be null");
        return CanonicalJson.obj(
            CanonicalJson.e("calleeModuleId", semanticIdJson(link.calleeModuleId())),
            CanonicalJson.e("calleeTokenId", semanticIdJson(link.calleeTokenId())),
            CanonicalJson.e("exportName", CanonicalJson.str(link.exportName())));
    }

    /** Renders a class layout. */
    public static CanonicalJson.Value layoutJson(ClassLayout layout) {
        Objects.requireNonNull(layout, "layout must not be null");
        return CanonicalJson.obj(
            CanonicalJson.e("classId", semanticIdJson(layout.classId())),
            CanonicalJson.e("fields", CanonicalJson.arr(
                layout.fields().stream().map(ContractSnapshotCanonicalizer::fieldLayoutJson).toList())));
    }

    /** Renders one class field layout entry. */
    public static CanonicalJson.Value fieldLayoutJson(ClassLayout.FieldLayout field) {
        Objects.requireNonNull(field, "field must not be null");
        return CanonicalJson.obj(
            CanonicalJson.e("defaultOwner", CanonicalJson.str(field.defaultOwner().name())),
            CanonicalJson.e("descriptor", descriptorText(field.descriptor())),
            CanonicalJson.e("name", CanonicalJson.str(field.name())),
            CanonicalJson.e("required", CanonicalJson.bool(field.required())));
    }

    /** Renders one {@code TABLE_NEW} entry. */
    public static CanonicalJson.Value tableEntryJson(KindPayload.TableEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        return CanonicalJson.obj(
            CanonicalJson.e("key", CanonicalJson.str(entry.key())),
            CanonicalJson.e("value", semanticIdJson(entry.value())));
    }

    /** Renders one {@code CLASS_NEW} provided field. */
    public static CanonicalJson.Value providedFieldJson(KindPayload.ProvidedField field) {
        Objects.requireNonNull(field, "field must not be null");
        return CanonicalJson.obj(
            CanonicalJson.e("name", CanonicalJson.str(field.name())),
            CanonicalJson.e("valueOpId", semanticIdJson(field.valueOpId())));
    }

    /** Renders one {@code CLASS_NEW} field boundary. */
    public static CanonicalJson.Value fieldBoundaryJson(KindPayload.FieldBoundary boundary) {
        Objects.requireNonNull(boundary, "boundary must not be null");
        return CanonicalJson.obj(
            CanonicalJson.e("boundaryOpId", semanticIdJson(boundary.boundaryOpId())),
            CanonicalJson.e("field", CanonicalJson.str(boundary.field())),
            CanonicalJson.e("kind", CanonicalJson.str(boundary.kind().name())));
    }

    /** Renders a closed selector as its raw enum name. */
    public static String selectorName(ClosedSelector selector) {
        Objects.requireNonNull(selector, "selector must not be null");
        if (selector instanceof UnarySelector u) {
            return u.name();
        }
        if (selector instanceof BinarySelector b) {
            return b.name();
        }
        if (selector instanceof StdlibFunctionId s) {
            return s.name();
        }
        throw new IllegalArgumentException("unknown selector: " + selector.getClass());
    }

    private static CanonicalJson.Int idValue(long id, String what) {
        if (id > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                what + " " + id + " exceeds the canonical JSON signed32 integer range");
        }
        return CanonicalJson.intValue((int) id);
    }


    // =========================================================================
    // Closed kind-payload shapes (one JSON object per SemanticOpKind)
    // =========================================================================

    /**
     * Renders the closed kind payload as its pinned JSON object: one
     * object per {@link SemanticOpKind} with the record component names as
     * keys, enum values as names, descriptors as canonical spec text,
     * semantic IDs as discriminated objects, and optional components as
     * explicit nulls. The single production payload mapping — the dumper
     * and validator consume it through this component only.
     *
     * @param payload the payload; non-null
     * @return the pinned JSON object
     */
    public static CanonicalJson.Value payloadJson(KindPayload payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        return switch (payload) {
            case KindPayload.ConstPayload p -> CanonicalJson.obj(
                CanonicalJson.e("value", scalarJson(p.value())));
            case KindPayload.UnaryPayload p -> CanonicalJson.obj(
                CanonicalJson.e("selector", CanonicalJson.str(p.selector().name())));
            case KindPayload.BinaryPayload p -> CanonicalJson.obj(
                CanonicalJson.e("innerDescriptor", p.innerDescriptor() == null
                    ? CanonicalJson.nullValue() : descriptorText(p.innerDescriptor())),
                CanonicalJson.e("selector", CanonicalJson.str(p.selector().name())),
                CanonicalJson.e("side", p.side() == null
                    ? CanonicalJson.nullValue() : CanonicalJson.str(p.side().name())));
            case KindPayload.StringConcatPayload p -> CanonicalJson.obj(
                CanonicalJson.e("fragments", CanonicalJson.arr(
                    p.fragments().stream().map(ContractSnapshotCanonicalizer::semanticIdJson).toList())));
            case KindPayload.ArrayNewPayload p -> CanonicalJson.obj(
                CanonicalJson.e("elementBoundaryOpIds", CanonicalJson.arr(
                    p.elementBoundaryOpIds().stream().map(ContractSnapshotCanonicalizer::semanticIdJson).toList())),
                CanonicalJson.e("elementDescriptor", descriptorText(p.elementDescriptor())),
                CanonicalJson.e("values", CanonicalJson.arr(
                    p.values().stream().map(ContractSnapshotCanonicalizer::semanticIdJson).toList())));
            case KindPayload.TableNewPayload p -> CanonicalJson.obj(
                CanonicalJson.e("entries", CanonicalJson.arr(
                    p.entries().stream().map(ContractSnapshotCanonicalizer::tableEntryJson).toList())));
            case KindPayload.ArrayLengthPayload p -> CanonicalJson.obj(
                CanonicalJson.e("arrayValue", semanticIdJson(p.arrayValue())));
            case KindPayload.MemberReadPayload p -> CanonicalJson.obj(
                CanonicalJson.e("key", CanonicalJson.str(p.key())),
                CanonicalJson.e("table", semanticIdJson(p.table())));
            case KindPayload.MemberWritePayload p -> CanonicalJson.obj(
                CanonicalJson.e("key", CanonicalJson.str(p.key())),
                CanonicalJson.e("table", semanticIdJson(p.table())),
                CanonicalJson.e("value", semanticIdJson(p.value())));
            case KindPayload.MemberDeletePayload p -> CanonicalJson.obj(
                CanonicalJson.e("key", CanonicalJson.str(p.key())),
                CanonicalJson.e("table", semanticIdJson(p.table())));
            case KindPayload.IndexNormalizePayload p -> CanonicalJson.obj(
                CanonicalJson.e("currentLength", semanticIdJson(p.currentLength())),
                CanonicalJson.e("mode", CanonicalJson.str(p.mode().name())),
                CanonicalJson.e("rawKey", semanticIdJson(p.rawKey())));
            case KindPayload.IndexReadPayload p -> CanonicalJson.obj(
                CanonicalJson.e("container", semanticIdJson(p.container())),
                CanonicalJson.e("elementBoundaryOpId", semanticIdJson(p.elementBoundaryOpId())),
                CanonicalJson.e("slot", semanticIdJson(p.slot())));
            case KindPayload.IndexWritePayload p -> CanonicalJson.obj(
                CanonicalJson.e("container", semanticIdJson(p.container())),
                CanonicalJson.e("slot", semanticIdJson(p.slot())),
                CanonicalJson.e("value", semanticIdJson(p.value())));
            case KindPayload.IndexDeletePayload p -> CanonicalJson.obj(
                CanonicalJson.e("container", semanticIdJson(p.container())),
                CanonicalJson.e("slot", semanticIdJson(p.slot())));
            case KindPayload.OptionalReadPayload p -> CanonicalJson.obj(
                CanonicalJson.e("descriptor", descriptorText(p.descriptor())),
                CanonicalJson.e("present", CanonicalJson.bool(p.present())),
                CanonicalJson.e("value", p.value() == null
                    ? CanonicalJson.nullValue() : semanticIdJson(p.value())));
            case KindPayload.HasFieldPayload p -> CanonicalJson.obj(
                CanonicalJson.e("key", CanonicalJson.str(p.key())),
                CanonicalJson.e("receiver", semanticIdJson(p.receiver())));
            case KindPayload.BoundaryPayload p -> CanonicalJson.obj(
                CanonicalJson.e("descriptor", descriptorText(p.descriptor())),
                CanonicalJson.e("input", semanticIdJson(p.input())),
                CanonicalJson.e("kind", CanonicalJson.str(p.kind().name())),
                CanonicalJson.e("realization", realizationJson(p.realization())));
            case KindPayload.BindingAllocPayload p -> CanonicalJson.obj(
                CanonicalJson.e("binding", semanticIdJson(p.binding())),
                CanonicalJson.e("cellKind", CanonicalJson.str(p.cellKind().name())),
                CanonicalJson.e("generation", idValue(p.generation(), "generation")),
                CanonicalJson.e("mutable", CanonicalJson.bool(p.mutable())),
                CanonicalJson.e("scope", semanticIdJson(p.scope())));
            case KindPayload.BindingInitPayload p -> CanonicalJson.obj(
                CanonicalJson.e("binding", semanticIdJson(p.binding())),
                CanonicalJson.e("generation", idValue(p.generation(), "generation")),
                CanonicalJson.e("value", p.value() == null
                    ? CanonicalJson.nullValue() : semanticIdJson(p.value())));
            case KindPayload.BindingLoadPayload p -> CanonicalJson.obj(
                CanonicalJson.e("binding", semanticIdJson(p.binding())),
                CanonicalJson.e("generation", idValue(p.generation(), "generation")));
            case KindPayload.BindingStorePayload p -> CanonicalJson.obj(
                CanonicalJson.e("binding", semanticIdJson(p.binding())),
                CanonicalJson.e("generation", idValue(p.generation(), "generation")),
                CanonicalJson.e("value", p.value() == null
                    ? CanonicalJson.nullValue() : semanticIdJson(p.value())));
            case KindPayload.RecursiveGroupInitPayload p -> CanonicalJson.obj(
                CanonicalJson.e("bindings", CanonicalJson.arr(
                    p.bindings().stream().map(ContractSnapshotCanonicalizer::semanticIdJson).toList())),
                CanonicalJson.e("functions", CanonicalJson.arr(
                    p.functions().stream().map(ContractSnapshotCanonicalizer::semanticIdJson).toList())));
            case KindPayload.ClosureNewPayload p -> CanonicalJson.obj(
                CanonicalJson.e("binding", bindingJson(p.binding())),
                CanonicalJson.e("captures", CanonicalJson.arr(
                    p.captures().stream().map(ContractSnapshotCanonicalizer::semanticIdJson).toList())),
                CanonicalJson.e("function", semanticIdJson(p.function())),
                CanonicalJson.e("signature", descriptorText(p.signature())));
            case KindPayload.FunctionAdaptPayload p -> CanonicalJson.obj(
                CanonicalJson.e("mode", CanonicalJson.str(p.mode().name())),
                CanonicalJson.e("proof", p.proof() == null
                    ? CanonicalJson.nullValue() : immutabilityProofJson(p.proof())),
                CanonicalJson.e("source", sourceRefJson(p.source())),
                CanonicalJson.e("sourceSignature", descriptorText(p.sourceSignature())),
                CanonicalJson.e("targetSignature", descriptorText(p.targetSignature())));
            case KindPayload.AssignPayload p -> CanonicalJson.obj(
                CanonicalJson.e("childOps", CanonicalJson.arr(
                    p.childOps().stream().map(ContractSnapshotCanonicalizer::semanticIdJson).toList())),
                CanonicalJson.e("targetKind", CanonicalJson.str(p.targetKind().name())));
            case KindPayload.DeletePayload p -> CanonicalJson.obj(
                CanonicalJson.e("childOps", CanonicalJson.arr(
                    p.childOps().stream().map(ContractSnapshotCanonicalizer::semanticIdJson).toList())),
                CanonicalJson.e("targetKind", CanonicalJson.str(p.targetKind().name())));
            case KindPayload.CallPayload p -> CanonicalJson.obj(
                CanonicalJson.e("bodyBlock", p.bodyBlock() == null
                    ? CanonicalJson.nullValue() : semanticIdJson(p.bodyBlock())),
                CanonicalJson.e("callee", calleeJson(p.callee())),
                CanonicalJson.e("externalEntryRef", p.externalEntryRef() == null
                    ? CanonicalJson.nullValue() : semanticIdJson(p.externalEntryRef())),
                CanonicalJson.e("mode", CanonicalJson.str(p.mode().name())),
                CanonicalJson.e("parameterBoundaryOpIds", CanonicalJson.arr(
                    p.parameterBoundaryOpIds().stream().map(ContractSnapshotCanonicalizer::semanticIdJson).toList())),
                CanonicalJson.e("returnBoundaryOpId", p.returnBoundaryOpId() == null
                    ? CanonicalJson.nullValue() : semanticIdJson(p.returnBoundaryOpId())),
                CanonicalJson.e("signature", descriptorText(p.signature())));
            case KindPayload.ExternalEntryPayload p -> CanonicalJson.obj(
                CanonicalJson.e("async", CanonicalJson.bool(p.async())),
                CanonicalJson.e("completionDescriptor", p.completionDescriptor() == null
                    ? CanonicalJson.nullValue() : descriptorText(p.completionDescriptor())),
                CanonicalJson.e("exportName", CanonicalJson.str(p.exportName())),
                CanonicalJson.e("function", semanticIdJson(p.function())),
                CanonicalJson.e("returnBoundaryOpId", semanticIdJson(p.returnBoundaryOpId())),
                CanonicalJson.e("signature", descriptorText(p.signature())));
            case KindPayload.CallbackInvokePayload p -> CanonicalJson.obj(
                CanonicalJson.e("descriptor", descriptorText(p.descriptor())),
                CanonicalJson.e("function", semanticIdJson(p.function())),
                CanonicalJson.e("parameterBoundaryOpIds", CanonicalJson.arr(
                    p.parameterBoundaryOpIds().stream().map(ContractSnapshotCanonicalizer::semanticIdJson).toList())),
                CanonicalJson.e("returnBoundaryOpId", semanticIdJson(p.returnBoundaryOpId())));
            case KindPayload.IntrinsicCallPayload p -> CanonicalJson.obj(
                CanonicalJson.e("input", semanticIdJson(p.input())),
                CanonicalJson.e("kind", CanonicalJson.str(p.kind().name())));
            case KindPayload.StdlibCallPayload p -> CanonicalJson.obj(
                CanonicalJson.e("args", CanonicalJson.arr(
                    p.args().stream().map(ContractSnapshotCanonicalizer::semanticIdJson).toList())),
                CanonicalJson.e("effectCapability", CanonicalJson.str(p.effectCapability().name())),
                CanonicalJson.e("function", CanonicalJson.str(p.function().name())));
            case KindPayload.AsyncStartPayload p -> CanonicalJson.obj(
                CanonicalJson.e("callee", calleeJson(p.callee())),
                CanonicalJson.e("completionDescriptor", descriptorText(p.completionDescriptor())),
                CanonicalJson.e("externalAsyncLink", p.externalAsyncLink() == null
                    ? CanonicalJson.nullValue() : externalAsyncLinkJson(p.externalAsyncLink())),
                CanonicalJson.e("hostOperationLabel", p.hostOperationLabel() == null
                    ? CanonicalJson.nullValue() : CanonicalJson.str(p.hostOperationLabel())),
                CanonicalJson.e("parameterBoundaryMode",
                    CanonicalJson.str(p.parameterBoundaryMode().name())),
                CanonicalJson.e("parameterBoundaryOpIds", CanonicalJson.arr(
                    p.parameterBoundaryOpIds().stream().map(ContractSnapshotCanonicalizer::semanticIdJson).toList())),
                CanonicalJson.e("returnBoundaryOpId", p.returnBoundaryOpId() == null
                    ? CanonicalJson.nullValue() : semanticIdJson(p.returnBoundaryOpId())),
                CanonicalJson.e("source", CanonicalJson.str(p.source().name())));
            case KindPayload.AwaitPayload p -> CanonicalJson.obj(
                CanonicalJson.e("completionBoundaryOpId", semanticIdJson(p.completionBoundaryOpId())),
                CanonicalJson.e("completionDescriptor", descriptorText(p.completionDescriptor())),
                CanonicalJson.e("token", semanticIdJson(p.token())));
            case KindPayload.BranchPayload p -> CanonicalJson.obj(
                CanonicalJson.e("alternateBlock", p.alternateBlock() == null
                    ? CanonicalJson.nullValue() : semanticIdJson(p.alternateBlock())),
                CanonicalJson.e("condition", semanticIdJson(p.condition())),
                CanonicalJson.e("selectedBlock", semanticIdJson(p.selectedBlock())),
                CanonicalJson.e("selector", CanonicalJson.str(p.selector().name())));
            case KindPayload.LoopPayload p -> CanonicalJson.obj(
                CanonicalJson.e("bodyBlock", semanticIdJson(p.bodyBlock())),
                CanonicalJson.e("condition", semanticIdJson(p.condition())),
                CanonicalJson.e("initBlock", p.initBlock() == null
                    ? CanonicalJson.nullValue() : semanticIdJson(p.initBlock())),
                CanonicalJson.e("selector", CanonicalJson.str(p.selector().name())),
                CanonicalJson.e("updateBlock", p.updateBlock() == null
                    ? CanonicalJson.nullValue() : semanticIdJson(p.updateBlock())));
            case KindPayload.ForEachPayload p -> CanonicalJson.obj(
                CanonicalJson.e("binding", semanticIdJson(p.binding())),
                CanonicalJson.e("body", semanticIdJson(p.body())),
                CanonicalJson.e("generation", idValue(p.generation(), "generation")),
                CanonicalJson.e("iterable", semanticIdJson(p.iterable())),
                CanonicalJson.e("mode", CanonicalJson.str(p.mode().name())));
            case KindPayload.TryCatchPayload p -> CanonicalJson.obj(
                CanonicalJson.e("catchBinding", semanticIdJson(p.catchBinding())),
                CanonicalJson.e("catchBlock", semanticIdJson(p.catchBlock())),
                CanonicalJson.e("tryBlock", semanticIdJson(p.tryBlock())));
            case KindPayload.ThrowPayload p -> CanonicalJson.obj(
                CanonicalJson.e("errorValue", semanticIdJson(p.errorValue())));
            case KindPayload.ReturnPayload p -> CanonicalJson.obj(
                CanonicalJson.e("enclosingInvocationOpId", semanticIdJson(p.enclosingInvocationOpId())),
                CanonicalJson.e("function", semanticIdJson(p.function())),
                CanonicalJson.e("returnBoundaryOpId", semanticIdJson(p.returnBoundaryOpId())),
                CanonicalJson.e("value", p.value() == null
                    ? CanonicalJson.nullValue() : semanticIdJson(p.value())));
            case KindPayload.BreakPayload p -> CanonicalJson.obj(
                CanonicalJson.e("loopId", semanticIdJson(p.loopId())));
            case KindPayload.ContinuePayload p -> CanonicalJson.obj(
                CanonicalJson.e("loopId", semanticIdJson(p.loopId())));
            case KindPayload.DiscardPayload p -> CanonicalJson.obj(
                CanonicalJson.e("value", semanticIdJson(p.value())));
            case KindPayload.ClassDefaultPayload p -> CanonicalJson.obj(
                CanonicalJson.e("classId", semanticIdJson(p.classId())),
                CanonicalJson.e("defaultBlock", semanticIdJson(p.defaultBlock())),
                CanonicalJson.e("field", CanonicalJson.str(p.field())));
            case KindPayload.ClassNewPayload p -> CanonicalJson.obj(
                CanonicalJson.e("classDefaultOpIds", CanonicalJson.arr(
                    p.classDefaultOpIds().stream().map(ContractSnapshotCanonicalizer::semanticIdJson).toList())),
                CanonicalJson.e("classFactoryRef", p.classFactoryRef() == null
                    ? CanonicalJson.nullValue() : semanticIdJson(p.classFactoryRef())),
                CanonicalJson.e("classId", semanticIdJson(p.classId())),
                CanonicalJson.e("defaultOwner", CanonicalJson.str(p.defaultOwner().name())),
                CanonicalJson.e("fieldBoundaries", CanonicalJson.arr(
                    p.fieldBoundaries().stream().map(ContractSnapshotCanonicalizer::fieldBoundaryJson).toList())),
                CanonicalJson.e("layout", layoutJson(p.layout())),
                CanonicalJson.e("providedFields", CanonicalJson.arr(
                    p.providedFields().stream().map(ContractSnapshotCanonicalizer::providedFieldJson).toList())));
            case KindPayload.ClassFactoryPayload p -> CanonicalJson.obj(
                CanonicalJson.e("callerOpRef", semanticIdJson(p.callerOpRef())),
                CanonicalJson.e("classDefaultOpIds", CanonicalJson.arr(
                    p.classDefaultOpIds().stream().map(ContractSnapshotCanonicalizer::semanticIdJson).toList())),
                CanonicalJson.e("classId", semanticIdJson(p.classId())));
            case KindPayload.FieldReadPayload p -> CanonicalJson.obj(
                CanonicalJson.e("classId", semanticIdJson(p.classId())),
                CanonicalJson.e("classValue", semanticIdJson(p.classValue())),
                CanonicalJson.e("field", CanonicalJson.str(p.field())));
            case KindPayload.FieldWritePayload p -> CanonicalJson.obj(
                CanonicalJson.e("classId", semanticIdJson(p.classId())),
                CanonicalJson.e("classValue", semanticIdJson(p.classValue())),
                CanonicalJson.e("field", CanonicalJson.str(p.field())),
                CanonicalJson.e("value", semanticIdJson(p.value())));
            case KindPayload.FieldDeletePayload p -> CanonicalJson.obj(
                CanonicalJson.e("classId", semanticIdJson(p.classId())),
                CanonicalJson.e("classValue", semanticIdJson(p.classValue())),
                CanonicalJson.e("field", CanonicalJson.str(p.field())));
            case KindPayload.JsonFromClassPayload p -> CanonicalJson.obj(
                CanonicalJson.e("jsonString", semanticIdJson(p.jsonString())),
                CanonicalJson.e("layout", layoutJson(p.layout())));
            case KindPayload.JsonToClassPayload p -> CanonicalJson.obj(
                CanonicalJson.e("classValue", semanticIdJson(p.classValue())),
                CanonicalJson.e("layout", layoutJson(p.layout())));
            case KindPayload.ModuleInitPayload p -> CanonicalJson.obj(
                CanonicalJson.e("imports", CanonicalJson.arr(
                    p.imports().stream().map(ContractSnapshotCanonicalizer::semanticIdJson).toList())),
                CanonicalJson.e("initBlock", semanticIdJson(p.initBlock())),
                CanonicalJson.e("module", semanticIdJson(p.module())));
            case KindPayload.ModuleImportPayload p -> CanonicalJson.obj(
                CanonicalJson.e("kind", CanonicalJson.str(p.kind().name())),
                CanonicalJson.e("rawSpecifier", CanonicalJson.str(p.rawSpecifier())),
                CanonicalJson.e("resolvedModule", semanticIdJson(p.resolvedModule())));
            case KindPayload.ExportReadPayload p -> CanonicalJson.obj(
                CanonicalJson.e("descriptor", descriptorText(p.descriptor())),
                CanonicalJson.e("module", semanticIdJson(p.module())),
                CanonicalJson.e("name", CanonicalJson.str(p.name())),
                CanonicalJson.e("value", semanticIdJson(p.value())));
            case KindPayload.ExportPublishPayload p -> CanonicalJson.obj(
                CanonicalJson.e("descriptor", descriptorText(p.descriptor())),
                CanonicalJson.e("module", semanticIdJson(p.module())),
                CanonicalJson.e("name", CanonicalJson.str(p.name())),
                CanonicalJson.e("value", semanticIdJson(p.value())));
            case KindPayload.EntryInvokePayload p -> CanonicalJson.obj(
                CanonicalJson.e("mainFunction", semanticIdJson(p.mainFunction())),
                CanonicalJson.e("module", semanticIdJson(p.module())));
        };
    }
}
