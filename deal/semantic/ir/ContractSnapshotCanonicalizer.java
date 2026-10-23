package deal.semantic.ir;

import deal.semantic.ClassRegistrationSeeds;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The single operation-contract snapshot canonicalizer of
 * {@code deal.semantic-ir/2} (schema S2; foundation F8): the only
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
     */
    public static String digest(OperationContractSnapshot snapshot) {
        return CanonicalJson.sha256Hex(serialize(snapshot));
    }

    /**
     * Recomputes the digest and reports whether it equals the snapshot's
     * carried {@code canonicalDigest} (R-DIGEST's recomputation, without
     * being a validator rule itself).
     *
     */
    public static boolean matchesCarriedDigest(OperationContractSnapshot snapshot) {
        return digest(snapshot).equals(snapshot.canonicalDigest());
    }

    // =========================================================================
    // General canonical JSON serialization (the single serializer entry)
    // =========================================================================

    /**
     * Serializes an arbitrary canonical JSON value to canonical JSON UTF-8
     * bytes through the single serializer of this component. This is the
     * general-value serialization entry every whole-document producer
     * (the dumper, the validator's digest recomputations) consumes: the
     * canonicalizer remains the one component that touches the
     * serializer, so whole documents and snapshots can never diverge in
     * encoding rules (S2; foundation F8).
     *
     */
    public static byte[] serializeJson(CanonicalJson.Value value) {
        return CanonicalJson.serializeBytes(Objects.requireNonNull(value, "value must not be null"));
    }

    /**
     * Serializes an arbitrary canonical JSON value to canonical JSON text
     * (UTF-8; debug/observation surface only).
     *
     */
    public static String serializeText(CanonicalJson.Value value) {
        return new String(serializeJson(value), java.nio.charset.StandardCharsets.UTF_8);
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
                    + " in deal.semantic-ir/2, got " + version);
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

    /** The canonical JSON kind name of a value (decode-failure messages). */
    public static String jsonKindName(CanonicalJson.Value value) {
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
                CanonicalJson.e("importCellId", b.importCell() == null
                    ? CanonicalJson.nullValue()
                    : semanticIdJson(b.importCell())),
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

            case FunctionExecutionBinding.DynamicFunctionValue b -> CanonicalJson.obj(
                CanonicalJson.e("descriptor", descriptorText(b.descriptor())),
                CanonicalJson.e("materializingBoundaryOpId",
                    semanticIdJson(b.materializingOpId())),
                CanonicalJson.e("type", CanonicalJson.str("dynamicFunctionValue")));
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
            case KindPayload.CallCallee.Dynamic d -> CanonicalJson.obj(
                CanonicalJson.e("callee", semanticIdJson(d.callee())),
                CanonicalJson.e("type", CanonicalJson.str("dynamic")));
        };
    }

    /** Renders the dynamic return-boundary set as its pinned three-entry object. */
    public static CanonicalJson.Value dynamicReturnBoundaryJson(
            KindPayload.DynamicReturnBoundary boundary) {
        Objects.requireNonNull(boundary, "boundary must not be null");
        return CanonicalJson.obj(
            CanonicalJson.e("dealBodyBoundaryOpId",
                semanticIdJson(boundary.dealBodyBoundaryOpId())),
            CanonicalJson.e("externalBoundaryOpId",
                semanticIdJson(boundary.externalBoundaryOpId())),
            CanonicalJson.e("hostBoundaryOpId", semanticIdJson(boundary.hostBoundaryOpId())));
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

    /**
     * Renders one raw adapter source reference as its pinned discriminated
     * object: the recognized shapes are emitted from their structured
     * members, and an unknown discriminator emits its retained decoded
     * object (members included).
     */
    public static CanonicalJson.Value rawSourceRefJson(RawSourceRef sourceRef) {
        Objects.requireNonNull(sourceRef, "sourceRef must not be null");
        return switch (sourceRef) {
            case RawSourceRef.SharedCell s -> CanonicalJson.obj(
                CanonicalJson.e("binding", semanticIdJson(s.binding())),
                CanonicalJson.e("generation", idValue(s.generation(), "generation")),
                CanonicalJson.e("type", CanonicalJson.str("sharedCell")));
            case RawSourceRef.Thunk t -> CanonicalJson.obj(
                CanonicalJson.e("blockId", semanticIdJson(t.blockId())),
                CanonicalJson.e("capturedBindings", CanonicalJson.arr(
                    t.capturedBindings().stream().map(ContractSnapshotCanonicalizer::bindingGenerationJson).toList())),
                CanonicalJson.e("type", CanonicalJson.str("thunk")));
            case RawSourceRef.Value v -> CanonicalJson.obj(
                CanonicalJson.e("type", CanonicalJson.str("value")),
                CanonicalJson.e("value", semanticIdJson(v.value())));
            case RawSourceRef.Unknown u -> u.json();
        };
    }

    /**
     * Maps one typed adapter source reference onto the raw registry
     * representation. The typed family is closed, so every typed shape is
     * a recognized raw shape; no {@link RawSourceRef.Unknown} can be
     * produced here.
     */
    public static RawSourceRef rawSourceRefOf(AdaptSourceRef sourceRef) {
        Objects.requireNonNull(sourceRef, "sourceRef must not be null");
        return switch (sourceRef) {
            case AdaptSourceRef.SharedCell s ->
                new RawSourceRef.SharedCell(s.binding(), s.generation());
            case AdaptSourceRef.Thunk t ->
                new RawSourceRef.Thunk(t.blockId(), t.capturedBindings());
            case AdaptSourceRef.Value v -> new RawSourceRef.Value(v.value());
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
            CanonicalJson.e("constructionProvider", CanonicalJson.str(field.constructionProvider().name())),
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
        if (id > Integer.MAX_VALUE || id < Integer.MIN_VALUE) {
            throw new IllegalArgumentException(
                what + " " + id + " is outside the canonical JSON signed32 integer range");
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
                    p.captures().stream().map(
                        ContractSnapshotCanonicalizer::bindingGenerationJson).toList())),
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
                CanonicalJson.e("dynamicReturnBoundary", p.dynamicReturnBoundary() == null
                    ? CanonicalJson.nullValue() : dynamicReturnBoundaryJson(p.dynamicReturnBoundary())),
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

            case KindPayload.ClassNewPayload p -> CanonicalJson.obj(
                CanonicalJson.e("classFactoryRef", p.classFactoryRef() == null
                    ? CanonicalJson.nullValue() : semanticIdJson(p.classFactoryRef())),
                CanonicalJson.e("classId", semanticIdJson(p.classId())),
                CanonicalJson.e("constructionProvider", CanonicalJson.str(p.constructionProvider().name())),
                CanonicalJson.e("fieldBoundaries", CanonicalJson.arr(
                    p.fieldBoundaries().stream().map(ContractSnapshotCanonicalizer::fieldBoundaryJson).toList())),
                CanonicalJson.e("layout", layoutJson(p.layout())),
                CanonicalJson.e("providedFields", CanonicalJson.arr(
                    p.providedFields().stream().map(ContractSnapshotCanonicalizer::providedFieldJson).toList())));
            case KindPayload.ClassFactoryPayload p -> CanonicalJson.obj(
                CanonicalJson.e("callerOpRef", semanticIdJson(p.callerOpRef())),
                CanonicalJson.e("classId", semanticIdJson(p.classId())));
            case KindPayload.FieldReadPayload p -> CanonicalJson.obj(
                CanonicalJson.e("classFactoryRef", p.classFactoryRef() == null
                    ? CanonicalJson.nullValue() : semanticIdJson(p.classFactoryRef())),
                CanonicalJson.e("classId", semanticIdJson(p.classId())),
                CanonicalJson.e("classValue", semanticIdJson(p.classValue())),
                CanonicalJson.e("field", CanonicalJson.str(p.field())));
            case KindPayload.FieldWritePayload p -> CanonicalJson.obj(
                CanonicalJson.e("classFactoryRef", p.classFactoryRef() == null
                    ? CanonicalJson.nullValue() : semanticIdJson(p.classFactoryRef())),
                CanonicalJson.e("classId", semanticIdJson(p.classId())),
                CanonicalJson.e("classValue", semanticIdJson(p.classValue())),
                CanonicalJson.e("field", CanonicalJson.str(p.field())),
                CanonicalJson.e("value", semanticIdJson(p.value())));
            case KindPayload.FieldDeletePayload p -> CanonicalJson.obj(
                CanonicalJson.e("classFactoryRef", p.classFactoryRef() == null
                    ? CanonicalJson.nullValue() : semanticIdJson(p.classFactoryRef())),
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
                CanonicalJson.e("aliasCells", CanonicalJson.arr(
                    p.aliasCells().stream()
                        .map(ContractSnapshotCanonicalizer::semanticIdJson).toList())),
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

    // =========================================================================
    // Unit/project validation text protocol (T6's validator text surface)
    // =========================================================================

    /** The pinned project field set ({@code deal.semantic-ir/2} project text and dump manifest). */
    public static final List<String> PROJECT_FIELDS = List.of("formatVersion",
        "semanticProfile", "entryModule", "interfaceIndex", "classSeeds", "modules");

    /**
     * The parsed project header of {@code deal.semantic-ir/2}: the
     * project framing both project surfaces share. The project text
     * embeds the complete unit documents in {@code modules}; the dumped
     * manifest carries the ordered module-path list there instead. The
     * profile and the entry module stay raw strings (their closed
     * checks are the validator's rules), while the interface index and
     * the class registration seeds decode to their typed records through
     * the complete inverse mapping — every field is retained, no
     * parser-selected fallback is applied, and an unrepresentable
     * shape fails closed as a transport decode failure.
     *
     */
    public record ParsedProjectHeader(String formatVersion, String semanticProfile,
                                      String entryModule,
                                      ProjectInterfaceIndex interfaceIndex,
                                      ClassRegistrationSeeds classSeeds) {

        public ParsedProjectHeader {
            Objects.requireNonNull(formatVersion, "formatVersion must not be null");
            Objects.requireNonNull(semanticProfile, "semanticProfile must not be null");
            Objects.requireNonNull(entryModule, "entryModule must not be null");
            Objects.requireNonNull(interfaceIndex, "interfaceIndex must not be null");
            Objects.requireNonNull(classSeeds, "classSeeds must not be null");
        }
    }

    /**
     * The parsed project of the validator's text surface and the dumper's
     * reassembly: the project header plus the dependency-ordered raw
     * units. The raw units are the complete {@code /2} unit records, so
     * every transported unit fact — both membership directions, the
     * functions and their captures, the complete bindings, the plans and
     * the unit companions — is retained exactly.
     *
     */
    public record ParsedProject(String formatVersion, String semanticProfile,
                                String entryModule,
                                ProjectInterfaceIndex interfaceIndex,
                                ClassRegistrationSeeds classSeeds,
                                List<RawUnit> modules) {

        public ParsedProject {
            Objects.requireNonNull(formatVersion, "formatVersion must not be null");
            Objects.requireNonNull(semanticProfile, "semanticProfile must not be null");
            Objects.requireNonNull(entryModule, "entryModule must not be null");
            Objects.requireNonNull(interfaceIndex, "interfaceIndex must not be null");
            Objects.requireNonNull(classSeeds, "classSeeds must not be null");
            modules = List.copyOf(Objects.requireNonNull(modules, "modules must not be null"));
        }

        /** The carried module paths in supplied (dependency) order. */
        public List<String> modulePaths() {
            List<String> paths = new ArrayList<>();
            for (RawUnit unit : modules) {
                paths.add(unit.modulePath());
            }
            return List.copyOf(paths);
        }
    }

    /**
     * Parses canonical validation text through the single parser. No
     * framing, closed-enum, or profile validation happens here beyond the
     * JSON value model itself — those are the validator's rules (T6).
     *
     */
    public static CanonicalJson.Value parseValidationText(String text) {
        return CanonicalJson.parse(text);
    }

    /**
     * Decodes a complete {@code deal.semantic-ir/2} unit text object into
     * the raw unit record: the exact pinned field set and JSON value
     * types are mapped into {@link RawUnit}; every closed enum position
     * (profile, capabilities, construct names, op kinds, payload leaves,
     * bindings, tokens, layout default owners) is carried as a raw string
     * — no enum conversion at parse time, so out-of-set and reserved
     * names survive byte-intact and reach the validator's rule checks.
     *
     * <p>Missing or unknown framing fields, wrong value types, a version
     * other than {@link LoweredModuleUnit#FORMAT_VERSION} and duplicate
     * entry identities are transport decode failures. An absent mandatory
     * companion is never treated as an empty one; a present companion row
     * with an empty list stays a distinct present fact.</p>
     *
     */
    public static RawUnit parseUnit(CanonicalJson.Obj obj) {
        checkKeys(obj, "formatVersion", "semanticProfile", "moduleId", "interfaceHash",
            "loweringContextHash", "requiredCapabilities", "constructCoverage",
            "classLayouts", "functions", "moduleInit", "exportPlan", "functionBindings",
            "ops", "bodyTable", "classFactories", "classBindingFacts");
        String formatVersion = requireString(obj, "formatVersion");
        if (!LoweredModuleUnit.FORMAT_VERSION.equals(formatVersion)) {
            throw new SemanticIrTextDecodeException(
                "formatVersion mismatch: expected \"" + LoweredModuleUnit.FORMAT_VERSION
                    + "\", got \"" + formatVersion + "\"");
        }
        String modulePath = requireString(obj, "moduleId");
        try {
            new ModuleId(modulePath);
        } catch (IllegalArgumentException e) {
            throw new SemanticIrTextDecodeException("invalid moduleId \"" + modulePath + "\"");
        }
        String semanticProfile = requireString(obj, "semanticProfile");
        String interfaceHash = requireString(obj, "interfaceHash");
        String loweringContextHash = requireString(obj, "loweringContextHash");

        List<String> capabilities = new ArrayList<>();
        for (CanonicalJson.Value item : requireArray(obj, "requiredCapabilities").items()) {
            capabilities.add(requireStringValue(item, "requiredCapabilities entry"));
        }
        List<RawCoverage> coverage = new ArrayList<>();
        Set<String> constructs = new LinkedHashSet<>();
        CanonicalJson.Arr coverageRows = requireArray(obj, "constructCoverage");
        for (int i = 0; i < coverageRows.items().size(); i++) {
            String where = "constructCoverage[" + i + "]";
            CanonicalJson.Value item = coverageRows.items().get(i);
            if (!(item instanceof CanonicalJson.Obj row)) {
                throw new SemanticIrTextDecodeException(
                    "constructCoverage entries must be objects, got " + jsonKindName(item));
            }
            checkKeys(row, "construct", "opKinds");
            String construct = requireString(row, "construct");
            if (!constructs.add(construct)) {
                throw new SemanticIrTextDecodeException(
                    "duplicate constructCoverage construct \"" + construct + "\" ("
                        + where + ")");
            }
            List<String> kinds = new ArrayList<>();
            for (CanonicalJson.Value kind : requireArray(row, "opKinds").items()) {
                kinds.add(requireStringValue(kind, "constructCoverage opKinds entry"));
            }
            coverage.add(new RawCoverage(construct, kinds));
        }
        List<RawUnit.RawClassLayout> classLayouts = parseClassLayouts(obj);
        List<RawUnit.RawFunction> functions = parseFunctions(obj);
        ModuleInitPlan moduleInit = parseModuleInit(obj);
        List<RawUnit.RawExportEntry> exportPlan = parseExportPlan(obj);
        List<RawBinding> bindings = parseBindings(obj);
        List<RawOp> ops = parseOps(obj);
        StructuredBodyTable bodyTable = parseBodyTable(obj);
        ClassFactoryRegistry classFactories = parseClassFactories(obj);
        ClassBindingFacts classBindingFacts = parseClassBindingFacts(obj);
        return new RawUnit(modulePath, semanticProfile, interfaceHash, loweringContextHash,
            capabilities, coverage, classLayouts, functions, moduleInit, exportPlan,
            bindings, ops, bodyTable, classFactories, classBindingFacts);
    }

    /** Decodes the complete class-layout registry entries (separate key and contained identity). */
    private static List<RawUnit.RawClassLayout> parseClassLayouts(CanonicalJson.Obj obj) {
        CanonicalJson.Arr rows = requireArray(obj, "classLayouts");
        List<RawUnit.RawClassLayout> classLayouts = new ArrayList<>();
        Set<ClassId> keys = new LinkedHashSet<>();
        for (int i = 0; i < rows.items().size(); i++) {
            String where = "classLayouts[" + i + "]";
            CanonicalJson.Obj row = requireObjectValue(rows.items().get(i), where);
            checkKeys(row, "key", "layout");
            ClassId key = parseClassId(requireObject(row, "key"));
            if (!keys.add(key)) {
                throw new SemanticIrTextDecodeException(
                    "duplicate classLayouts entry key " + key + " (" + where + ")");
            }
            CanonicalJson.Obj layout = requireObject(row, "layout");
            checkKeys(layout, "classId", "fields");
            ClassId classId = parseClassId(requireObject(layout, "classId"));
            List<RawUnit.RawFieldLayout> fields = new ArrayList<>();
            CanonicalJson.Arr fieldRows = requireArray(layout, "fields");
            for (int j = 0; j < fieldRows.items().size(); j++) {
                String fieldWhere = where + ".layout.fields[" + j + "]";
                CanonicalJson.Obj field = requireObjectValue(fieldRows.items().get(j), fieldWhere);
                checkKeys(field, "constructionProvider", "descriptor", "name", "required");
                fields.add(new RawUnit.RawFieldLayout(requireString(field, "name"),
                    requireString(field, "descriptor"), requireBool(field, "required"),
                    requireString(field, "constructionProvider")));
            }
            classLayouts.add(new RawUnit.RawClassLayout(key,
                new RawUnit.RawLayout(classId, fields)));
        }
        return classLayouts;
    }

    /** Decodes the complete function registry entries (separate key and contained identity). */
    private static List<RawUnit.RawFunction> parseFunctions(CanonicalJson.Obj obj) {
        CanonicalJson.Arr rows = requireArray(obj, "functions");
        List<RawUnit.RawFunction> functions = new ArrayList<>();
        Set<FunctionId> keys = new LinkedHashSet<>();
        for (int i = 0; i < rows.items().size(); i++) {
            String where = "functions[" + i + "]";
            CanonicalJson.Obj row = requireObjectValue(rows.items().get(i), where);
            checkKeys(row, "key", "functionId", "descriptor", "captures", "body",
                "sourceName");
            FunctionId key = parseFunctionId(requireObject(row, "key"));
            if (!keys.add(key)) {
                throw new SemanticIrTextDecodeException(
                    "duplicate functions entry key " + key + " (" + where + ")");
            }
            FunctionId functionId = parseFunctionId(requireObject(row, "functionId"));
            String descriptor = requireString(row, "descriptor");
            List<BindingGeneration> captures = new ArrayList<>();
            CanonicalJson.Arr captureRows = requireArray(row, "captures");
            for (int j = 0; j < captureRows.items().size(); j++) {
                String captureWhere = where + ".captures[" + j + "]";
                CanonicalJson.Obj capture = requireObjectValue(captureRows.items().get(j),
                    captureWhere);
                checkKeys(capture, "binding", "generation");
                captures.add(new BindingGeneration(
                    parseBindingId(requireObject(capture, "binding")),
                    requireGeneration(capture, captureWhere)));
            }
            BlockId body = parseBlockId(requireObject(row, "body"));
            String sourceName = requireStringOrNull(entryValue(row, "sourceName"),
                where + ".sourceName");
            functions.add(new RawUnit.RawFunction(key, functionId, descriptor, captures, body,
                sourceName));
        }
        return functions;
    }

    /** Decodes the module-init plan: ordered imports and the init block. */
    private static ModuleInitPlan parseModuleInit(CanonicalJson.Obj obj) {
        CanonicalJson.Obj moduleInit = requireObject(obj, "moduleInit");
        checkKeys(moduleInit, "imports", "initBlock");
        List<ModuleId> imports = new ArrayList<>();
        CanonicalJson.Arr rows = requireArray(moduleInit, "imports");
        for (int i = 0; i < rows.items().size(); i++) {
            imports.add(parseModuleId(requireObjectValue(rows.items().get(i),
                "moduleInit.imports[" + i + "]")));
        }
        return new ModuleInitPlan(imports, parseBlockId(requireObject(moduleInit, "initBlock")));
    }

    /**
     * Decodes the array-form export plan entries. The export name is the
     * entry identity: a repeated name is a duplicate entry identity and
     * rejects. Distinct rows keep their producer order.
     */
    private static List<RawUnit.RawExportEntry> parseExportPlan(CanonicalJson.Obj obj) {
        CanonicalJson.Arr rows = requireArray(obj, "exportPlan");
        List<RawUnit.RawExportEntry> exportPlan = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < rows.items().size(); i++) {
            String where = "exportPlan[" + i + "]";
            CanonicalJson.Obj row = requireObjectValue(rows.items().get(i), where);
            checkKeys(row, "name", "descriptor", "publishOpId");
            String name = requireString(row, "name");
            if (!names.add(name)) {
                throw new SemanticIrTextDecodeException(
                    "duplicate exportPlan entry name \"" + name + "\" (" + where + ")");
            }
            exportPlan.add(new RawUnit.RawExportEntry(name,
                requireString(row, "descriptor"),
                parseOpId(requireObject(row, "publishOpId"))));
        }
        return exportPlan;
    }

    /** Decodes the complete allocation/binding registry entries. */
    private static List<RawBinding> parseBindings(CanonicalJson.Obj obj) {
        CanonicalJson.Arr rows = requireArray(obj, "functionBindings");
        List<RawBinding> bindings = new ArrayList<>();
        Set<Long> allocationIds = new LinkedHashSet<>();
        for (int i = 0; i < rows.items().size(); i++) {
            String where = "functionBindings[" + i + "]";
            CanonicalJson.Obj entry = requireObjectValue(rows.items().get(i), where);
            checkKeys(entry, "allocationId", "binding");
            long allocationId = requireInt(entry, "allocationId");
            if (allocationId < 0) {
                throw new SemanticIrTextDecodeException(
                    "the functionBindings allocationId must be non-negative, got "
                        + allocationId + " (" + where + ")");
            }
            if (!allocationIds.add(allocationId)) {
                throw new SemanticIrTextDecodeException(
                    "duplicate functionBindings allocationId " + allocationId
                        + " (" + where + ")");
            }
            CanonicalJson.Value bindingValue = entryValue(entry, "binding");
            if (!(bindingValue instanceof CanonicalJson.Obj binding)) {
                throw new SemanticIrTextDecodeException(
                    "functionBindings \"binding\" must be an object, got "
                        + jsonKindName(bindingValue));
            }
            bindings.add(bindingFromText(allocationId, binding));
        }
        return bindings;
    }

    /**
     * Decodes the produced operations in source order. The operation id is
     * the entry identity: a repeated id is a duplicate entry identity and
     * rejects before any identity-keyed view can overwrite one entry.
     */
    private static List<RawOp> parseOps(CanonicalJson.Obj obj) {
        List<RawOp> ops = new ArrayList<>();
        Set<OpId> opIds = new LinkedHashSet<>();
        CanonicalJson.Arr rows = requireArray(obj, "ops");
        for (int i = 0; i < rows.items().size(); i++) {
            String where = "ops[" + i + "]";
            CanonicalJson.Value item = rows.items().get(i);
            if (!(item instanceof CanonicalJson.Obj op)) {
                throw new SemanticIrTextDecodeException(
                    "ops entries must be objects, got " + jsonKindName(item));
            }
            RawOp decoded = opFromText(op);
            if (!opIds.add(decoded.opId())) {
                throw new SemanticIrTextDecodeException(
                    "duplicate ops opId " + decoded.opId() + " (" + where + ")");
            }
            ops.add(decoded);
        }
        return ops;
    }

    /** Decodes the body table: both membership directions are transported independently. */
    private static StructuredBodyTable parseBodyTable(CanonicalJson.Obj obj) {
        CanonicalJson.Obj bodyTable = requireObject(obj, "bodyTable");
        checkKeys(bodyTable, "blockOps", "opBlocks");
        Map<BlockId, List<OpId>> blockOps = new LinkedHashMap<>();
        CanonicalJson.Arr blockRows = requireArray(bodyTable, "blockOps");
        for (int i = 0; i < blockRows.items().size(); i++) {
            String where = "bodyTable.blockOps[" + i + "]";
            CanonicalJson.Obj row = requireObjectValue(blockRows.items().get(i), where);
            checkKeys(row, "block", "ops");
            BlockId block = parseBlockId(requireObject(row, "block"));
            if (blockOps.containsKey(block)) {
                throw new SemanticIrTextDecodeException(
                    "duplicate bodyTable.blockOps entry block " + block + " (" + where + ")");
            }
            List<OpId> ops = new ArrayList<>();
            CanonicalJson.Arr opRows = requireArray(row, "ops");
            for (int j = 0; j < opRows.items().size(); j++) {
                ops.add(parseOpId(requireObjectValue(opRows.items().get(j),
                    where + ".ops[" + j + "]")));
            }
            blockOps.put(block, ops);
        }
        Map<OpId, BlockId> opBlocks = new LinkedHashMap<>();
        CanonicalJson.Arr opRows = requireArray(bodyTable, "opBlocks");
        for (int i = 0; i < opRows.items().size(); i++) {
            String where = "bodyTable.opBlocks[" + i + "]";
            CanonicalJson.Obj row = requireObjectValue(opRows.items().get(i), where);
            checkKeys(row, "op", "block");
            OpId op = parseOpId(requireObject(row, "op"));
            if (opBlocks.containsKey(op)) {
                throw new SemanticIrTextDecodeException(
                    "duplicate bodyTable.opBlocks entry op " + op + " (" + where + ")");
            }
            opBlocks.put(op, parseBlockId(requireObject(row, "block")));
        }
        return new StructuredBodyTable(blockOps, opBlocks);
    }

    /** Decodes the construction-entry → CLASS_FACTORY registry. */
    private static ClassFactoryRegistry parseClassFactories(CanonicalJson.Obj obj) {
        CanonicalJson.Arr rows = requireArray(obj, "classFactories");
        Map<ClassFactoryId, OpId> factories = new LinkedHashMap<>();
        for (int i = 0; i < rows.items().size(); i++) {
            String where = "classFactories[" + i + "]";
            CanonicalJson.Obj row = requireObjectValue(rows.items().get(i), where);
            checkKeys(row, "constructionEntry", "factoryOp");
            ClassFactoryId entry = parseClassFactoryId(requireObject(row, "constructionEntry"));
            if (factories.containsKey(entry)) {
                throw new SemanticIrTextDecodeException(
                    "duplicate classFactories constructionEntry " + entry
                        + " (" + where + ")");
            }
            factories.put(entry, parseOpId(requireObject(row, "factoryOp")));
        }
        return new ClassFactoryRegistry(factories);
    }

    /** Decodes the parameter/import initialization facts. */
    private static ClassBindingFacts parseClassBindingFacts(CanonicalJson.Obj obj) {
        CanonicalJson.Obj facts = requireObject(obj, "classBindingFacts");
        checkKeys(facts, "parameters", "importAliases");
        return new ClassBindingFacts(parseBindingIdSet(facts, "parameters"),
            parseBindingIdSet(facts, "importAliases"));
    }

    private static Set<BindingId> parseBindingIdSet(CanonicalJson.Obj facts, String key) {
        Set<BindingId> ids = new LinkedHashSet<>();
        CanonicalJson.Arr rows = requireArray(facts, key);
        for (int i = 0; i < rows.items().size(); i++) {
            BindingId id = parseBindingId(requireObjectValue(rows.items().get(i),
                key + "[" + i + "]"));
            if (!ids.add(id)) {
                throw new SemanticIrTextDecodeException(
                    "duplicate classBindingFacts." + key + " binding " + id);
            }
        }
        return ids;
    }

    /**
     * Decodes the project framing both project surfaces share: an exact
     * {@link #PROJECT_FIELDS} key set, the {@code deal.semantic-ir/2}
     * envelope check (a {@code /1} project never decodes), the raw
     * project profile and entry module, and the typed interface index and
     * class registration seeds. The {@code modules} value type is the
     * caller's (embedded unit documents for the project text, the ordered
     * module-path list for the dump manifest), so this entry only pins
     * the framing every project carries.
     *
     */
    public static ParsedProjectHeader parseProjectHeader(CanonicalJson.Obj obj) {
        checkKeys(obj, PROJECT_FIELDS.toArray(String[]::new));
        String formatVersion = requireString(obj, "formatVersion");
        if (!LoweredModuleUnit.FORMAT_VERSION.equals(formatVersion)) {
            throw new SemanticIrTextDecodeException(
                "project formatVersion mismatch: expected \""
                    + LoweredModuleUnit.FORMAT_VERSION + "\", got \""
                    + formatVersion + "\"");
        }
        String semanticProfile = requireString(obj, "semanticProfile");
        String entryModule = requireString(obj, "entryModule");
        try {
            new ModuleId(entryModule);
        } catch (IllegalArgumentException e) {
            throw new SemanticIrTextDecodeException(
                "invalid entryModule \"" + entryModule + "\"");
        }
        ProjectInterfaceIndex interfaceIndex =
            parseInterfaceIndex(requireObject(obj, "interfaceIndex"));
        ClassRegistrationSeeds classSeeds =
            parseClassSeeds(requireArray(obj, "classSeeds"));
        return new ParsedProjectHeader(formatVersion, semanticProfile, entryModule,
            interfaceIndex, classSeeds);
    }

    /**
     * Decodes a project text object ({@code {formatVersion,
     * semanticProfile, entryModule, interfaceIndex, classSeeds, modules}})
     * into its raw records: the dependency-ordered embedded unit documents
     * decode through {@link #parseUnit(CanonicalJson.Obj)} and the
     * entry-module framing check runs over the decoded module identities.
     * A repeated module identity is a duplicate entry identity and rejects
     * before any module map could overwrite one module with another.
     *
     */
    public static ParsedProject parseProject(CanonicalJson.Obj obj) {
        ParsedProjectHeader header = parseProjectHeader(obj);
        CanonicalJson.Arr modulesArr = requireArray(obj, "modules");
        List<RawUnit> modules = new ArrayList<>();
        Set<String> modulePaths = new LinkedHashSet<>();
        for (int i = 0; i < modulesArr.items().size(); i++) {
            CanonicalJson.Value item = modulesArr.items().get(i);
            if (!(item instanceof CanonicalJson.Obj unitObj)) {
                throw new SemanticIrTextDecodeException(
                    "modules[" + i + "] must be a unit object, got " + jsonKindName(item));
            }
            RawUnit unit = parseUnit(unitObj);
            if (!modulePaths.add(unit.modulePath())) {
                throw new SemanticIrTextDecodeException(
                    "duplicate project module \"" + unit.modulePath()
                        + "\" (modules[" + i + "])");
            }
            modules.add(unit);
        }
        if (!modulePaths.contains(header.entryModule())) {
            throw new SemanticIrTextDecodeException(
                "entryModule \"" + header.entryModule()
                    + "\" must be present in the project modules");
        }
        return new ParsedProject(header.formatVersion(), header.semanticProfile(),
            header.entryModule(), header.interfaceIndex(), header.classSeeds(), modules);
    }

    /**
     * Decodes the complete {@code deal.semantic-interface/1} index.
     * Every carried field of every entry is retained (the module identity,
     * the closed kind, the resolved imports, the exports, the classes with
     * their ordered fields and construction entries, and the
     * initialization mode); the module map is keyed by the entry's own
     * module identity, a repeated identity is a duplicate entry identity
     * and rejects before the map is built, and the supplied order is
     * preserved. The format version is not bumped: the carried version
     * must be exactly {@link ProjectInterfaceIndex#FORMAT_VERSION}.
     *
     */
    public static ProjectInterfaceIndex parseInterfaceIndex(CanonicalJson.Obj obj) {
        checkKeys(obj, "formatVersion", "modules");
        String formatVersion = requireString(obj, "formatVersion");
        if (!ProjectInterfaceIndex.FORMAT_VERSION.equals(formatVersion)) {
            throw new SemanticIrTextDecodeException(
                "interface formatVersion mismatch: expected \""
                    + ProjectInterfaceIndex.FORMAT_VERSION + "\", got \""
                    + formatVersion + "\"");
        }
        Map<ModuleId, ExternalModuleInterface> modules = new LinkedHashMap<>();
        CanonicalJson.Arr rows = requireArray(obj, "modules");
        for (int i = 0; i < rows.items().size(); i++) {
            String where = "interfaceIndex.modules[" + i + "]";
            CanonicalJson.Obj row = requireObjectValue(rows.items().get(i), where);
            ExternalModuleInterface entry = parseExternalModuleInterface(row, where);
            if (modules.putIfAbsent(entry.moduleId(), entry) != null) {
                throw new SemanticIrTextDecodeException(
                    "duplicate interfaceIndex module " + entry.moduleId()
                        + " (" + where + ")");
            }
        }
        return new ProjectInterfaceIndex(formatVersion, modules);
    }

    /** Decodes one complete {@code deal.semantic-interface/1} module entry. */
    private static ExternalModuleInterface parseExternalModuleInterface(CanonicalJson.Obj row,
                                                                       String where) {
        checkKeys(row, "moduleId", "kind", "imports", "exports", "classes",
            "initialization");
        ModuleId moduleId = parseModuleId(requireObject(row, "moduleId"));
        ExternalModuleKind kind = parseExternalModuleKind(requireString(row, "kind"),
            where + ".kind");
        List<ResolvedImport> imports = new ArrayList<>();
        CanonicalJson.Arr importRows = requireArray(row, "imports");
        for (int i = 0; i < importRows.items().size(); i++) {
            String importWhere = where + ".imports[" + i + "]";
            CanonicalJson.Obj importRow = requireObjectValue(importRows.items().get(i),
                importWhere);
            checkKeys(importRow, "alias", "modulePath", "resolvedModuleId", "kind");
            imports.add(new ResolvedImport(requireString(importRow, "alias"),
                requireString(importRow, "modulePath"),
                parseModuleId(requireObject(importRow, "resolvedModuleId")),
                parseExternalModuleKind(requireString(importRow, "kind"),
                    importWhere + ".kind")));
        }
        List<ExportInterface> exports = new ArrayList<>();
        CanonicalJson.Arr exportRows = requireArray(row, "exports");
        for (int i = 0; i < exportRows.items().size(); i++) {
            String exportWhere = where + ".exports[" + i + "]";
            CanonicalJson.Obj exportRow = requireObjectValue(exportRows.items().get(i),
                exportWhere);
            checkKeys(exportRow, "name", "declaredType");
            exports.add(new ExportInterface(requireString(exportRow, "name"),
                requireString(exportRow, "declaredType")));
        }
        List<ClassInterface> classes = new ArrayList<>();
        CanonicalJson.Arr classRows = requireArray(row, "classes");
        for (int i = 0; i < classRows.items().size(); i++) {
            String classWhere = where + ".classes[" + i + "]";
            CanonicalJson.Obj classRow = requireObjectValue(classRows.items().get(i),
                classWhere);
            checkKeys(classRow, "classId", "fields", "constructionEntry");
            List<FieldInterface> fields = new ArrayList<>();
            CanonicalJson.Arr fieldRows = requireArray(classRow, "fields");
            for (int j = 0; j < fieldRows.items().size(); j++) {
                String fieldWhere = classWhere + ".fields[" + j + "]";
                CanonicalJson.Obj field = requireObjectValue(fieldRows.items().get(j),
                    fieldWhere);
                checkKeys(field, "name", "declaredType", "optional", "nullable");
                fields.add(new FieldInterface(requireString(field, "name"),
                    requireString(field, "declaredType"), requireBool(field, "optional"),
                    requireBool(field, "nullable")));
            }
            classes.add(new ClassInterface(parseClassId(requireObject(classRow, "classId")),
                fields, parseClassFactoryId(requireObject(classRow, "constructionEntry"))));
        }
        InitializationMode initialization = parseInitializationMode(
            requireString(row, "initialization"), where + ".initialization");
        return new ExternalModuleInterface(moduleId, kind, imports, exports, classes,
            initialization);
    }

    /** One closed index kind name, or the documented transport decode failure. */
    private static ExternalModuleKind parseExternalModuleKind(String raw, String what) {
        try {
            return ExternalModuleKind.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new SemanticIrTextDecodeException(
                "unknown " + what + " \"" + raw + "\"");
        }
    }

    /** The one closed initialization mode, or the documented decode failure. */
    private static InitializationMode parseInitializationMode(String raw, String what) {
        try {
            return InitializationMode.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new SemanticIrTextDecodeException(
                "unknown " + what + " \"" + raw + "\"");
        }
    }

    /** One closed default-owner name, or the documented transport decode failure. */
    private static ConstructionProvider parseConstructionProvider(String raw, String what) {
        try {
            return ConstructionProvider.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new SemanticIrTextDecodeException(
                "unknown " + what + " \"" + raw + "\"");
        }
    }

    /** One canonical descriptor text, or the documented transport decode failure. */
    private static RuntimeDescriptor parseFieldDescriptor(String text, String what) {
        try {
            return RuntimeDescriptor.parseCanonicalText(text);
        } catch (SemanticIrTextDecodeException e) {
            throw new SemanticIrTextDecodeException(
                "the " + what + " is not a canonical descriptor text: "
                    + e.getMessage());
        }
    }

    /**
     * Decodes the class registration seeds: one row per registration with
     * the registration key, the contained layout (identity and ordered
     * field rows) and the existing owner record kept as separate facts.
     * A repeated registration key is a duplicate entry identity and
     * rejects before the registration map is built; an owner member that
     * disagrees with a field's default owner is not representable as a
     * {@link ClassRegistrationSeeds.ClassRegistration} and fails closed
     * here rather than leaking a producer exception.
     *
     */
    public static ClassRegistrationSeeds parseClassSeeds(CanonicalJson.Arr rows) {
        Map<ClassId, ClassRegistrationSeeds.ClassRegistration> registrations =
            new LinkedHashMap<>();
        for (int i = 0; i < rows.items().size(); i++) {
            String where = "classSeeds[" + i + "]";
            CanonicalJson.Obj row = requireObjectValue(rows.items().get(i), where);
            checkKeys(row, "key", "layout", "owner");
            ClassId key = parseClassId(requireObject(row, "key"));
            if (registrations.containsKey(key)) {
                throw new SemanticIrTextDecodeException(
                    "duplicate classSeeds registration key " + key + " (" + where + ")");
            }
            CanonicalJson.Obj layout = requireObject(row, "layout");
            checkKeys(layout, "classId", "fields");
            ClassId classId = parseClassId(requireObject(layout, "classId"));
            List<ClassLayout.FieldLayout> fields = new ArrayList<>();
            CanonicalJson.Arr fieldRows = requireArray(layout, "fields");
            for (int j = 0; j < fieldRows.items().size(); j++) {
                String fieldWhere = where + ".layout.fields[" + j + "]";
                CanonicalJson.Obj field = requireObjectValue(fieldRows.items().get(j),
                    fieldWhere);
                checkKeys(field, "constructionProvider", "descriptor", "name", "required");
                fields.add(new ClassLayout.FieldLayout(requireString(field, "name"),
                    parseFieldDescriptor(requireString(field, "descriptor"),
                        fieldWhere + ".descriptor"),
                    requireBool(field, "required"),
                    parseConstructionProvider(requireString(field, "constructionProvider"),
                        fieldWhere + ".constructionProvider")));
            }
            ConstructionProvider owner = parseConstructionProvider(requireString(row, "owner"),
                where + ".owner");
            try {
                registrations.put(key, new ClassRegistrationSeeds.ClassRegistration(
                    new ClassLayout(classId, fields), owner));
            } catch (IllegalArgumentException e) {
                throw new SemanticIrTextDecodeException(
                    "invalid classSeeds registration " + key + " (" + where + "): "
                        + e.getMessage());
            }
        }
        return new ClassRegistrationSeeds(registrations);
    }

    /**
     * Maps a raw unit onto the complete {@code deal.semantic-ir/2} canonical
     * JSON value model (the text surface's transport): every fact the raw
     * unit owns is emitted — header, capabilities, coverage, class-layout
     * entries with their separate key and layout identity, function entries
     * with their separate key and function identity, plan, bindings and
     * operations, plus the complete unit companions. Mandatory fields are
     * always emitted, even when empty; producer order is preserved and no
     * evaluation-bearing array is sorted or collapsed.
     *
     */
    public static CanonicalJson.Value toJson(RawUnit unit) {
        List<CanonicalJson.Value> capabilities = new ArrayList<>();
        for (String capability : unit.requiredCapabilities()) {
            capabilities.add(CanonicalJson.str(capability));
        }
        List<CanonicalJson.Value> coverage = new ArrayList<>();
        for (RawCoverage row : unit.coverage()) {
            List<CanonicalJson.Value> kinds = new ArrayList<>();
            for (String kind : row.opKinds()) {
                kinds.add(CanonicalJson.str(kind));
            }
            coverage.add(CanonicalJson.obj(
                CanonicalJson.e("construct", CanonicalJson.str(row.construct())),
                CanonicalJson.e("opKinds", CanonicalJson.arr(kinds))));
        }
        List<CanonicalJson.Value> classLayouts = new ArrayList<>();
        for (RawUnit.RawClassLayout entry : unit.classLayouts()) {
            classLayouts.add(rawClassLayoutJson(entry));
        }
        List<CanonicalJson.Value> functions = new ArrayList<>();
        for (RawUnit.RawFunction entry : unit.functions()) {
            functions.add(rawFunctionJson(entry));
        }
        List<CanonicalJson.Value> exportPlan = new ArrayList<>();
        for (RawUnit.RawExportEntry entry : unit.exportPlan()) {
            exportPlan.add(CanonicalJson.obj(
                CanonicalJson.e("descriptor", CanonicalJson.str(entry.descriptor())),
                CanonicalJson.e("name", CanonicalJson.str(entry.name())),
                CanonicalJson.e("publishOpId", semanticIdJson(entry.publishOpId()))));
        }
        List<CanonicalJson.Value> bindings = new ArrayList<>();
        for (RawBinding binding : unit.bindings()) {
            bindings.add(CanonicalJson.obj(
                CanonicalJson.e("allocationId", idValue(binding.allocationId(), "allocationId")),
                CanonicalJson.e("binding", bindingJson(binding))));
        }
        List<CanonicalJson.Value> ops = new ArrayList<>();
        for (RawOp op : unit.ops()) {
            ops.add(opJson(op));
        }
        return CanonicalJson.obj(
            CanonicalJson.e("formatVersion", CanonicalJson.str(LoweredModuleUnit.FORMAT_VERSION)),
            CanonicalJson.e("semanticProfile", CanonicalJson.str(unit.semanticProfile())),
            CanonicalJson.e("moduleId", CanonicalJson.str(unit.modulePath())),
            CanonicalJson.e("interfaceHash", CanonicalJson.str(unit.interfaceHash())),
            CanonicalJson.e("loweringContextHash", CanonicalJson.str(unit.loweringContextHash())),
            CanonicalJson.e("requiredCapabilities", CanonicalJson.arr(capabilities)),
            CanonicalJson.e("constructCoverage", CanonicalJson.arr(coverage)),
            CanonicalJson.e("classLayouts", CanonicalJson.arr(classLayouts)),
            CanonicalJson.e("functions", CanonicalJson.arr(functions)),
            CanonicalJson.e("moduleInit", moduleInitJson(unit.moduleInit())),
            CanonicalJson.e("exportPlan", CanonicalJson.arr(exportPlan)),
            CanonicalJson.e("functionBindings", CanonicalJson.arr(bindings)),
            CanonicalJson.e("ops", CanonicalJson.arr(ops)),
            CanonicalJson.e("bodyTable", bodyTableJson(unit.bodyTable())),
            CanonicalJson.e("classFactories", classFactoriesJson(unit.classFactories())),
            CanonicalJson.e("classBindingFacts", classBindingFactsJson(unit.classBindingFacts())));
    }

    /**
     * Renders one raw class-layout registry entry: the registry {@code key}
     * stays a separate fact from the layout's own {@code classId}, so a
     * disagreement is transported instead of normalized away. The layout
     * data uses the existing layout mapping: the contained identity and
     * the ordered field rows (name, descriptor text, presence, default
     * owner).
     *
     */
    public static CanonicalJson.Value rawClassLayoutJson(RawUnit.RawClassLayout entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        return CanonicalJson.obj(
            CanonicalJson.e("key", semanticIdJson(entry.key())),
            CanonicalJson.e("layout", rawLayoutJson(entry.layout())));
    }

    private static CanonicalJson.Value rawLayoutJson(RawUnit.RawLayout layout) {
        List<CanonicalJson.Value> fields = new ArrayList<>();
        for (RawUnit.RawFieldLayout field : layout.fields()) {
            fields.add(CanonicalJson.obj(
                CanonicalJson.e("constructionProvider", CanonicalJson.str(field.constructionProvider())),
                CanonicalJson.e("descriptor", CanonicalJson.str(field.descriptor())),
                CanonicalJson.e("name", CanonicalJson.str(field.name())),
                CanonicalJson.e("required", CanonicalJson.bool(field.required()))));
        }
        return CanonicalJson.obj(
            CanonicalJson.e("classId", semanticIdJson(layout.classId())),
            CanonicalJson.e("fields", CanonicalJson.arr(fields)));
    }

    /**
     * Renders one raw function registry entry: the registry {@code key}
     * stays a separate fact from the contained {@code functionId}, so a
     * disagreement is transported instead of derived away; the descriptor
     * text, the ordered generation-pinned captures, the body block and
     * the source name (explicit null for an anonymous function) complete
     * the entry.
     *
     */
    public static CanonicalJson.Value rawFunctionJson(RawUnit.RawFunction entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        List<CanonicalJson.Value> captures = new ArrayList<>();
        for (BindingGeneration capture : entry.captures()) {
            captures.add(bindingGenerationJson(capture));
        }
        return CanonicalJson.obj(
            CanonicalJson.e("body", semanticIdJson(entry.body())),
            CanonicalJson.e("captures", CanonicalJson.arr(captures)),
            CanonicalJson.e("descriptor", CanonicalJson.str(entry.descriptor())),
            CanonicalJson.e("functionId", semanticIdJson(entry.functionId())),
            CanonicalJson.e("key", semanticIdJson(entry.key())),
            CanonicalJson.e("sourceName", entry.sourceName() == null
                ? CanonicalJson.nullValue() : CanonicalJson.str(entry.sourceName())));
    }

    /** Renders the module-init plan through the existing semantic-ID mapping. */
    private static CanonicalJson.Value moduleInitJson(ModuleInitPlan plan) {
        Objects.requireNonNull(plan, "plan must not be null");
        List<CanonicalJson.Value> imports = new ArrayList<>();
        for (ModuleId module : plan.imports()) {
            imports.add(semanticIdJson(module));
        }
        return CanonicalJson.obj(
            CanonicalJson.e("imports", CanonicalJson.arr(imports)),
            CanonicalJson.e("initBlock", semanticIdJson(plan.initBlock())));
    }

    /**
     * Renders the body table: both membership directions are emitted from
     * their own records and transported independently; the inverse is
     * never rebuilt from the other direction.
     *
     */
    public static CanonicalJson.Value bodyTableJson(StructuredBodyTable table) {
        Objects.requireNonNull(table, "table must not be null");
        List<CanonicalJson.Value> blockOps = new ArrayList<>();
        for (Map.Entry<BlockId, List<OpId>> entry : table.blockOps().entrySet()) {
            List<CanonicalJson.Value> ops = new ArrayList<>();
            for (OpId op : entry.getValue()) {
                ops.add(semanticIdJson(op));
            }
            blockOps.add(CanonicalJson.obj(
                CanonicalJson.e("block", semanticIdJson(entry.getKey())),
                CanonicalJson.e("ops", CanonicalJson.arr(ops))));
        }
        List<CanonicalJson.Value> opBlocks = new ArrayList<>();
        for (Map.Entry<OpId, BlockId> entry : table.opBlocks().entrySet()) {
            opBlocks.add(CanonicalJson.obj(
                CanonicalJson.e("block", semanticIdJson(entry.getValue())),
                CanonicalJson.e("op", semanticIdJson(entry.getKey()))));
        }
        return CanonicalJson.obj(
            CanonicalJson.e("blockOps", CanonicalJson.arr(blockOps)),
            CanonicalJson.e("opBlocks", CanonicalJson.arr(opBlocks)));
    }

    /** Renders the construction-entry → registered CLASS_FACTORY op registry. */
    public static CanonicalJson.Value classFactoriesJson(ClassFactoryRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        List<CanonicalJson.Value> rows = new ArrayList<>();
        for (Map.Entry<ClassFactoryId, OpId> entry : registry.factories().entrySet()) {
            rows.add(CanonicalJson.obj(
                CanonicalJson.e("constructionEntry", semanticIdJson(entry.getKey())),
                CanonicalJson.e("factoryOp", semanticIdJson(entry.getValue()))));
        }
        return CanonicalJson.arr(rows);
    }

    /** Renders the parameter/import initialization facts (insertion order). */
    public static CanonicalJson.Value classBindingFactsJson(ClassBindingFacts facts) {
        Objects.requireNonNull(facts, "facts must not be null");
        return CanonicalJson.obj(
            CanonicalJson.e("importAliases", bindingIdListJson(facts.importAliases())),
            CanonicalJson.e("parameters", bindingIdListJson(facts.parameters())));
    }

    private static CanonicalJson.Value bindingIdListJson(Set<BindingId> ids) {
        List<CanonicalJson.Value> items = new ArrayList<>();
        for (BindingId id : ids) {
            items.add(semanticIdJson(id));
        }
        return CanonicalJson.arr(items);
    }

    /**
     * Renders the class registration seeds: one row per registration with
     * the registration key, the contained layout (identity and ordered
     * field rows) and the owner member as separate facts, in registration
     * order. The seeds stay separate from every unit's own
     * {@code classLayouts} — this mapping never merges a seed layout into a
     * unit's local layouts.
     *
     */
    public static CanonicalJson.Value classSeedsJson(ClassRegistrationSeeds seeds) {
        Objects.requireNonNull(seeds, "seeds must not be null");
        List<CanonicalJson.Value> rows = new ArrayList<>();
        for (Map.Entry<ClassId, ClassRegistrationSeeds.ClassRegistration> entry
                : seeds.registrations().entrySet()) {
            ClassRegistrationSeeds.ClassRegistration registration = entry.getValue();
            rows.add(CanonicalJson.obj(
                CanonicalJson.e("key", semanticIdJson(entry.getKey())),
                CanonicalJson.e("layout", layoutJson(registration.layout())),
                CanonicalJson.e("owner", CanonicalJson.str(registration.owner().name()))));
        }
        return CanonicalJson.arr(rows);
    }

    /**
     * Maps an executable project onto the complete project text protocol
     * value model: the {@code deal.semantic-ir/2} envelope, the raw
     * profile and entry module, the complete interface index, the class
     * registration seeds and the dependency-ordered modules, each through
     * the complete unit mapping. A modules map key that disagrees with the
     * unit's own module identity is a producer defect (the map key is
     * projected away by the value-only module list, so the disagreement
     * must fail encoding instead of being hidden).
     *
     */
    public static CanonicalJson.Value toJson(ExecutableLoweredProject project) {
        Objects.requireNonNull(project, "project must not be null");
        List<CanonicalJson.Value> modules = new ArrayList<>();
        for (Map.Entry<ModuleId, LoweredModuleUnit> entry : project.modules().entrySet()) {
            if (!entry.getKey().equals(entry.getValue().moduleId())) {
                throw new IllegalArgumentException(
                    "the project modules map key " + entry.getKey()
                        + " does not match the unit's moduleId "
                        + entry.getValue().moduleId() + " (a producer defect)");
            }
            modules.add(toJson(RawUnit.fromTyped(entry.getValue())));
        }
        return CanonicalJson.obj(
            CanonicalJson.e("formatVersion", CanonicalJson.str(LoweredModuleUnit.FORMAT_VERSION)),
            CanonicalJson.e("semanticProfile",
                CanonicalJson.str(project.semanticProfile().name())),
            CanonicalJson.e("entryModule", CanonicalJson.str(project.entryModule().path())),
            CanonicalJson.e("interfaceIndex", project.interfaceIndex().toCanonicalJson()),
            CanonicalJson.e("classSeeds", classSeedsJson(project.classSeeds())),
            CanonicalJson.e("modules", CanonicalJson.arr(modules)));
    }

    /**
     * Maps a parsed project back onto the project text protocol value
     * model (the inverse of {@link #parseProject(CanonicalJson.Obj)}):
     * decoding a project text and re-emitting it yields the same canonical
     * bytes whenever the content is unchanged.
     *
     */
    public static CanonicalJson.Value toJson(ParsedProject project) {
        Objects.requireNonNull(project, "project must not be null");
        if (!LoweredModuleUnit.FORMAT_VERSION.equals(project.formatVersion())) {
            throw new IllegalArgumentException(
                "project formatVersion must be \""
                    + LoweredModuleUnit.FORMAT_VERSION + "\", got \""
                    + project.formatVersion() + "\"");
        }
        List<CanonicalJson.Value> modules = new ArrayList<>();
        for (RawUnit unit : project.modules()) {
            modules.add(toJson(unit));
        }
        return CanonicalJson.obj(
            CanonicalJson.e("formatVersion", CanonicalJson.str(project.formatVersion())),
            CanonicalJson.e("semanticProfile",
                CanonicalJson.str(project.semanticProfile())),
            CanonicalJson.e("entryModule", CanonicalJson.str(project.entryModule())),
            CanonicalJson.e("interfaceIndex", project.interfaceIndex().toCanonicalJson()),
            CanonicalJson.e("classSeeds", classSeedsJson(project.classSeeds())),
            CanonicalJson.e("modules", CanonicalJson.arr(modules)));
    }

    /**
     * Recomputes the contract digest over the raw 8-field snapshot object
     * (R-DIGEST's recomputation): {@code SHA-256(canonical JSON bytes)} of
     * exactly the eight pinned snapshot fields.
     *
     */
    public static String recomputeSnapshotDigest(CanonicalJson.Obj snapshot) {
        return CanonicalJson.sha256Hex(CanonicalJson.serializeBytes(snapshot));
    }

    private static CanonicalJson.Value bindingJson(RawBinding binding) {
        return switch (binding.shape()) {
            case "loweredBody" -> CanonicalJson.obj(
                CanonicalJson.e("blockId", semanticIdJson(binding.blockId())),
                CanonicalJson.e("functionId", semanticIdJson(binding.functionId())),
                CanonicalJson.e("type", CanonicalJson.str("loweredBody")));
            case "adapter" -> CanonicalJson.obj(
                CanonicalJson.e("adaptOpId", semanticIdJson(binding.adaptOpId())),
                CanonicalJson.e("captureMode", CanonicalJson.str(binding.captureMode())),
                CanonicalJson.e("sourceRef", rawSourceRefJson(binding.sourceRef())),
                CanonicalJson.e("sourceSignature", CanonicalJson.str(binding.sourceSignature())),
                CanonicalJson.e("targetSignature", CanonicalJson.str(binding.targetSignature())),
                CanonicalJson.e("type", CanonicalJson.str("adapter")));
            case "hostFunction" -> CanonicalJson.obj(
                CanonicalJson.e("descriptor", CanonicalJson.str(binding.descriptor())),
                CanonicalJson.e("exportName", CanonicalJson.str(binding.exportName())),
                CanonicalJson.e("hostModuleId", moduleIdJson(binding.modulePath())),
                CanonicalJson.e("importCellId", binding.importCell() == null
                    ? CanonicalJson.nullValue()
                    : semanticIdJson(binding.importCell())),
                CanonicalJson.e("type", CanonicalJson.str("hostFunction")));
            case "hostFunctionValue" -> CanonicalJson.obj(
                CanonicalJson.e("descriptor", CanonicalJson.str(binding.descriptor())),
                CanonicalJson.e("hostModuleId", moduleIdJson(binding.modulePath())),
                CanonicalJson.e("materializingBoundaryOpId", binding.materializingBoundaryOpId() == null
                    ? CanonicalJson.nullValue()
                    : semanticIdJson(binding.materializingBoundaryOpId())),
                CanonicalJson.e("type", CanonicalJson.str("hostFunctionValue")));
            case "externalFunction" -> CanonicalJson.obj(
                CanonicalJson.e("descriptor", CanonicalJson.str(binding.descriptor())),
                CanonicalJson.e("executionOwner", CanonicalJson.str(binding.executionOwner())),
                CanonicalJson.e("exportName", CanonicalJson.str(binding.exportName())),
                CanonicalJson.e("moduleId", moduleIdJson(binding.modulePath())),
                CanonicalJson.e("type", CanonicalJson.str("externalFunction")));

            case "dynamicFunctionValue" -> CanonicalJson.obj(
                CanonicalJson.e("descriptor", binding.descriptor() == null
                    ? CanonicalJson.nullValue()
                    : CanonicalJson.str(binding.descriptor())),
                CanonicalJson.e("materializingBoundaryOpId", binding.materializingBoundaryOpId() == null
                    ? CanonicalJson.nullValue()
                    : semanticIdJson(binding.materializingBoundaryOpId())),
                CanonicalJson.e("type", CanonicalJson.str("dynamicFunctionValue")));
            default -> CanonicalJson.obj(
                CanonicalJson.e("type", CanonicalJson.str(binding.shape())));
        };
    }

    /**
     * Parses an adapter source reference: the recognized family is
     * sharedCell (binding/generation), thunk (blockId/capturedBindings)
     * and value (value), each with its existing type discriminator and its
     * exact member set. The discriminator itself stays raw: an unknown
     * shape is retained with its complete decoded member set so the
     * closed-position rule (R-ENUM) owns the rejection instead of the
     * decoder.
     */
    public static RawSourceRef parseSourceRef(CanonicalJson.Obj obj) {
        String type = requireString(obj, "type");
        switch (type) {
            case "sharedCell" -> {
                checkKeys(obj, "binding", "generation", "type");
                return new RawSourceRef.SharedCell(
                    parseBindingId(requireObject(obj, "binding")),
                    requireGeneration(obj, "sourceRef.sharedCell"));
            }
            case "thunk" -> {
                checkKeys(obj, "blockId", "capturedBindings", "type");
                BlockId blockId = parseBlockId(requireObject(obj, "blockId"));
                List<BindingGeneration> captured = new ArrayList<>();
                CanonicalJson.Arr rows = requireArray(obj, "capturedBindings");
                for (int i = 0; i < rows.items().size(); i++) {
                    String where = "sourceRef.thunk.capturedBindings[" + i + "]";
                    CanonicalJson.Obj row = requireObjectValue(rows.items().get(i), where);
                    checkKeys(row, "binding", "generation");
                    captured.add(new BindingGeneration(
                        parseBindingId(requireObject(row, "binding")),
                        requireGeneration(row, where)));
                }
                return new RawSourceRef.Thunk(blockId, captured);
            }
            case "value" -> {
                checkKeys(obj, "type", "value");
                return new RawSourceRef.Value(parseValueId(requireObject(obj, "value")));
            }
            default -> {
                return new RawSourceRef.Unknown(type, obj);
            }
        }
    }

    /** The non-negative generation of one generation-pinned capture position. */
    private static long requireGeneration(CanonicalJson.Obj obj, String what) {
        int generation = requireInt(obj, "generation");
        if (generation < 0) {
            throw new SemanticIrTextDecodeException(
                "the " + what + " generation must be non-negative, got " + generation);
        }
        return generation;
    }

    private static RawBinding bindingFromText(long allocationId, CanonicalJson.Obj binding) {
        String shape = requireString(binding, "type");
        String modulePath = null;
        String exportName = null;
        String executionOwner = null;
        String captureMode = null;
        String descriptor = null;
        String sourceSignature = null;
        String targetSignature = null;
        OpId materializingBoundaryOpId = null;
        FunctionId functionId = null;
        BlockId blockId = null;
        OpId adaptOpId = null;
        RawSourceRef sourceRef = null;
        BindingId importCellId = null;
        switch (shape) {
            case "loweredBody" -> {
                checkKeys(binding, "blockId", "functionId", "type");
                blockId = parseBlockId(requireObject(binding, "blockId"));
                functionId = parseFunctionId(requireObject(binding, "functionId"));
            }
            case "adapter" -> {
                checkKeys(binding, "adaptOpId", "captureMode", "sourceRef",
                    "sourceSignature", "targetSignature", "type");
                adaptOpId = parseOpId(requireObject(binding, "adaptOpId"));
                captureMode = requireString(binding, "captureMode");
                sourceRef = parseSourceRef(requireObject(binding, "sourceRef"));
                sourceSignature = requireString(binding, "sourceSignature");
                targetSignature = requireString(binding, "targetSignature");
            }
            case "hostFunction" -> {
                checkKeys(binding, "descriptor", "exportName", "hostModuleId",
                    "importCellId", "type");
                modulePath = modulePathOf(requireObject(binding, "hostModuleId"));
                exportName = requireString(binding, "exportName");
                descriptor = requireString(binding, "descriptor");
                CanonicalJson.Value importCell = entryValue(binding, "importCellId");
                if (importCell instanceof CanonicalJson.Obj importCellObj) {
                    importCellId = parseBindingId(importCellObj);
                } else if (!(importCell instanceof CanonicalJson.Null)) {
                    throw new SemanticIrTextDecodeException(
                        "functionBindings \"importCellId\" must be null or a binding-id "
                            + "object, got " + jsonKindName(importCell));
                }
            }
            case "hostFunctionValue" -> {
                checkKeys(binding, "descriptor", "hostModuleId", "materializingBoundaryOpId",
                    "type");
                modulePath = modulePathOf(requireObject(binding, "hostModuleId"));
                descriptor = requireString(binding, "descriptor");
                CanonicalJson.Value materializing = entryValue(binding, "materializingBoundaryOpId");
                if (materializing instanceof CanonicalJson.Obj materializingObj) {
                    materializingBoundaryOpId = parseOpId(materializingObj);
                } else if (!(materializing instanceof CanonicalJson.Null)) {
                    throw new SemanticIrTextDecodeException(
                        "functionBindings \"materializingBoundaryOpId\" must be null or an "
                            + "op-id object, got " + jsonKindName(materializing));
                }
            }
            case "externalFunction" -> {
                checkKeys(binding, "descriptor", "executionOwner", "exportName", "moduleId",
                    "type");
                modulePath = modulePathOf(requireObject(binding, "moduleId"));
                exportName = requireString(binding, "exportName");
                executionOwner = requireString(binding, "executionOwner");
                descriptor = requireString(binding, "descriptor");
            }

            case "dynamicFunctionValue" -> {
                checkKeys(binding, "descriptor", "materializingBoundaryOpId", "type");
                // The two required positions (the producing op and the
                // materialized descriptor) are decoded as nullable raw
                // positions: the transport never invents a value, and the
                // shape's required positions are the validator's closed
                // clause (R-ENUM), so a doctored text reaching the raw
                // model reports the rule instead of a decode failure.
                descriptor = optionalString(binding, "descriptor");
                materializingBoundaryOpId = optionalOpId(binding,
                    "materializingBoundaryOpId");
            }
            default -> {
                // An unknown binding shape tag is an R-ENUM position —
                // carry it through as the shape string.
            }
        }
        return new RawBinding(allocationId, shape, modulePath, exportName, executionOwner,
            captureMode, descriptor, sourceSignature, targetSignature,
            materializingBoundaryOpId, functionId, blockId, adaptOpId, sourceRef,
            importCellId);
    }

    private static CanonicalJson.Value opJson(RawOp op) {
        List<CanonicalJson.Value> operands = new ArrayList<>();
        for (ValueId value : op.operands()) {
            operands.add(semanticIdJson(value));
        }
        List<CanonicalJson.Value> operandTypes = new ArrayList<>();
        for (String type : op.operandTypes()) {
            operandTypes.add(CanonicalJson.str(type));
        }
        CanonicalJson.Value result;
        if (op.resultValue() != null) {
            result = semanticIdJson(op.resultValue());
        } else if (op.resultToken() != null) {
            result = semanticIdJson(op.resultToken());
        } else {
            result = CanonicalJson.nullValue();
        }
        List<CanonicalJson.Entry> contractEntries = new ArrayList<>(op.snapshot().entries());
        contractEntries.add(CanonicalJson.e("canonicalDigest",
            CanonicalJson.str(op.canonicalDigest())));
        return CanonicalJson.obj(
            CanonicalJson.e("opId", semanticIdJson(op.opId())),
            CanonicalJson.e("kind", CanonicalJson.str(op.kind())),
            CanonicalJson.e("origin", originJson(op)),
            CanonicalJson.e("result", result),
            CanonicalJson.e("resultType", op.resultType() == null
                ? CanonicalJson.nullValue() : CanonicalJson.str(op.resultType())),
            CanonicalJson.e("operands", CanonicalJson.arr(operands)),
            CanonicalJson.e("operandTypes", CanonicalJson.arr(operandTypes)),
            CanonicalJson.e("payload", op.payload()),
            CanonicalJson.e("failurePolicy", CanonicalJson.str(op.failurePolicy())),
            CanonicalJson.e("contract", CanonicalJson.obj(contractEntries)));
    }

    /**
     * Renders one op's retained origin metadata (D3): the complete key set
     * the raw model preserves, so typed-to-text and text-to-text round
     * trips keep the supplied diagnostic coordinates instead of
     * neutralizing them. The parent-op link stays the semantic link the
     * rules consume.
     */
    private static CanonicalJson.Value originJson(RawOp op) {
        RawOrigin origin = op.origin();
        return CanonicalJson.obj(
            CanonicalJson.e("sourceId", origin.sourceId() == null
                ? CanonicalJson.nullValue() : CanonicalJson.str(origin.sourceId())),
            CanonicalJson.e("span", CanonicalJson.obj(
                CanonicalJson.e("file", origin.file() == null
                    ? CanonicalJson.nullValue() : CanonicalJson.str(origin.file())),
                CanonicalJson.e("startLine", CanonicalJson.intValue(origin.startLine())),
                CanonicalJson.e("startColumn", CanonicalJson.intValue(origin.startColumn())),
                CanonicalJson.e("endLine", CanonicalJson.intValue(origin.endLine())),
                CanonicalJson.e("endColumn", CanonicalJson.intValue(origin.endColumn())),
                CanonicalJson.e("startScalarOffset",
                    CanonicalJson.intValue(origin.startScalarOffset())),
                CanonicalJson.e("endScalarOffset",
                    CanonicalJson.intValue(origin.endScalarOffset())))),
            CanonicalJson.e("kind", origin.kind() == null
                ? CanonicalJson.nullValue() : CanonicalJson.str(origin.kind())),
            CanonicalJson.e("anchorId", CanonicalJson.obj(
                CanonicalJson.e("id", idValue(origin.anchorId(), "anchorId")),
                CanonicalJson.e("type", CanonicalJson.str("anchor")))),
            CanonicalJson.e("parentOpId", op.parentOpId() == null
                ? CanonicalJson.nullValue()
                : semanticIdJson(op.parentOpId())));
    }

    /**
     * Decodes one op origin object into the retained diagnostic metadata.
     * The origin is diagnostic-only data: missing or malformed values are
     * carried as-is, never as a transport or semantic rejection (the
     * existing {@link deal.diagnostics.CompilerDiagnostic} normalization
     * owns every malformed range downstream). The parent-op link keeps its
     * own pinned decoding in {@link #opFromText}.
     */
    private static RawOrigin originFromText(CanonicalJson.Obj origin) {
        String sourceId = lenientString(origin, "sourceId");
        String file = null;
        int startLine = 0;
        int startColumn = 0;
        int endLine = 0;
        int endColumn = 0;
        int startScalarOffset = SourceSpan.UNKNOWN_OFFSET;
        int endScalarOffset = SourceSpan.UNKNOWN_OFFSET;
        CanonicalJson.Value spanValue = lenientValue(origin, "span");
        if (spanValue instanceof CanonicalJson.Obj span) {
            file = lenientString(span, "file");
            startLine = lenientInt(span, "startLine", 0);
            startColumn = lenientInt(span, "startColumn", 0);
            endLine = lenientInt(span, "endLine", 0);
            endColumn = lenientInt(span, "endColumn", 0);
            startScalarOffset = lenientInt(span, "startScalarOffset",
                SourceSpan.UNKNOWN_OFFSET);
            endScalarOffset = lenientInt(span, "endScalarOffset",
                SourceSpan.UNKNOWN_OFFSET);
        }
        String kind = lenientString(origin, "kind");
        long anchorId = 0;
        CanonicalJson.Value anchorValue = lenientValue(origin, "anchorId");
        if (anchorValue instanceof CanonicalJson.Obj anchor) {
            anchorId = lenientInt(anchor, "id", 0);
        }
        return new RawOrigin(sourceId, file, startLine, startColumn, endLine, endColumn,
            startScalarOffset, endScalarOffset, kind, anchorId);
    }

    /** A field value of a leniently decoded object, or {@code null} when absent. */
    private static CanonicalJson.Value lenientValue(CanonicalJson.Obj obj, String key) {
        for (CanonicalJson.Entry entry : obj.entries()) {
            if (entry.key().equals(key)) {
                return entry.value();
            }
        }
        return null;
    }

    /** A string field of a leniently decoded object, or {@code null}. */
    private static String lenientString(CanonicalJson.Obj obj, String key) {
        CanonicalJson.Value value = lenientValue(obj, key);
        return value instanceof CanonicalJson.Str str ? str.value() : null;
    }

    /** A signed32 integer field of a leniently decoded object, or the fallback. */
    private static int lenientInt(CanonicalJson.Obj obj, String key, int fallback) {
        CanonicalJson.Value value = lenientValue(obj, key);
        return value instanceof CanonicalJson.Int i ? i.value() : fallback;
    }

    private static RawOp opFromText(CanonicalJson.Obj op) {
        checkKeys(op, "opId", "kind", "origin", "result", "resultType", "operands",
            "operandTypes", "payload", "failurePolicy", "contract");
        OpId opId = parseOpId(requireObject(op, "opId"));
        String kind = requireString(op, "kind");
        String failurePolicy = requireString(op, "failurePolicy");

        CanonicalJson.Obj origin = requireObject(op, "origin");
        checkKeys(origin, "sourceId", "span", "kind", "anchorId", "parentOpId");
        OpId parentOpId = null;
        CanonicalJson.Value parent = entryValue(origin, "parentOpId");
        if (!(parent instanceof CanonicalJson.Null)) {
            parentOpId = parseOpId(requireObjectValue(parent, "origin.parentOpId"));
        }
        RawOrigin retainedOrigin = originFromText(origin);

        ValueId resultValue = null;
        AsyncTokenId resultToken = null;
        CanonicalJson.Value result = entryValue(op, "result");
        if (result instanceof CanonicalJson.Obj resultObj) {
            switch (requireString(resultObj, "type")) {
                case "value" -> resultValue = parseValueId(resultObj);
                case "tokenCanonical", "tokenAlias" -> resultToken = parseToken(resultObj);
                default -> throw new SemanticIrTextDecodeException(
                    "op result type tag \"" + requireString(resultObj, "type")
                        + "\" is not a closed semantic-id shape");
            }
        } else if (!(result instanceof CanonicalJson.Null)) {
            throw new SemanticIrTextDecodeException(
                "op result must be null or a semantic-id object, got " + jsonKindName(result));
        }

        String resultType = null;
        CanonicalJson.Value resultTypeValue = entryValue(op, "resultType");
        if (resultTypeValue instanceof CanonicalJson.Str str) {
            resultType = str.value();
        } else if (!(resultTypeValue instanceof CanonicalJson.Null)) {
            throw new SemanticIrTextDecodeException(
                "op resultType must be null or a string, got " + jsonKindName(resultTypeValue));
        }

        List<ValueId> operands = new ArrayList<>();
        for (CanonicalJson.Value item : requireArray(op, "operands").items()) {
            operands.add(parseValueId(requireObjectValue(item, "operands entry")));
        }
        List<String> operandTypes = new ArrayList<>();
        for (CanonicalJson.Value item : requireArray(op, "operandTypes").items()) {
            operandTypes.add(requireStringValue(item, "operandTypes entry"));
        }
        if (operands.size() != operandTypes.size()) {
            throw new SemanticIrTextDecodeException(
                "operands (" + operands.size() + ") and operandTypes ("
                    + operandTypes.size() + ") must have the same length");
        }

        CanonicalJson.Obj payload = requireObject(op, "payload");

        CanonicalJson.Obj contract = requireObject(op, "contract");
        checkKeys(contract, "version", "opKind", "resultType", "operandTypes", "selector",
            "payload", "failurePolicy", "referencedSemanticIds", "canonicalDigest");
        if (requireInt(contract, "version") != OperationContractSnapshot.VERSION) {
            throw new SemanticIrTextDecodeException(
                "contract version mismatch: expected " + OperationContractSnapshot.VERSION);
        }
        String canonicalDigest = requireString(contract, "canonicalDigest");
        List<CanonicalJson.Entry> snapshotEntries = new ArrayList<>();
        for (CanonicalJson.Entry entry : contract.entries()) {
            if (!"canonicalDigest".equals(entry.key())) {
                snapshotEntries.add(entry);
            }
        }
        CanonicalJson.Obj snapshot = CanonicalJson.obj(snapshotEntries);
        String selector = null;
        CanonicalJson.Value selectorValue = entryValue(snapshot, "selector");
        if (selectorValue instanceof CanonicalJson.Str str) {
            selector = str.value();
        } else if (!(selectorValue instanceof CanonicalJson.Null)) {
            throw new SemanticIrTextDecodeException(
                "contract selector must be null or a string, got "
                    + jsonKindName(selectorValue));
        }
        return new RawOp(opId, kind, failurePolicy, parentOpId, resultValue, resultToken,
            resultType, operands, operandTypes, payload, selector, canonicalDigest, snapshot,
            retainedOrigin);
    }

    // =========================================================================
    // Text protocol framing helpers (pinned field sets, raw-string preservation)
    // =========================================================================

    /**
     * Enforces the pinned object framing: exactly the pinned field set —
     * every present key must be pinned and every pinned key must be
     * present. Any deviation is a transport-level decode failure.
     *
     */
    public static void checkKeys(CanonicalJson.Obj obj, String... pinned) {
        Set<String> set = new LinkedHashSet<>(List.of(pinned));
        for (CanonicalJson.Entry entry : obj.entries()) {
            if (!set.contains(entry.key())) {
                throw new SemanticIrTextDecodeException(
                    "unknown field \"" + entry.key() + "\" (pinned field set is "
                        + List.of(pinned) + ")");
            }
        }
        for (String key : pinned) {
            if (!hasKey(obj, key)) {
                throw new SemanticIrTextDecodeException(
                    "missing field \"" + key + "\" (pinned field set is " + List.of(pinned) + ")");
            }
        }
    }

    /** True iff the object carries the given key. */
    public static boolean hasKey(CanonicalJson.Obj obj, String key) {
        for (CanonicalJson.Entry entry : obj.entries()) {
            if (entry.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** The value of a pinned key (decode failure when absent). */
    public static CanonicalJson.Value entryValue(CanonicalJson.Obj obj, String key) {
        for (CanonicalJson.Entry entry : obj.entries()) {
            if (entry.key().equals(key)) {
                return entry.value();
            }
        }
        throw new SemanticIrTextDecodeException("missing field \"" + key + "\"");
    }

    /** The string value of a pinned key. */
    public static String requireString(CanonicalJson.Obj obj, String key) {
        return requireStringValue(entryValue(obj, key), key);
    }

    /** A string value or a decode failure. */
    public static String requireStringValue(CanonicalJson.Value value, String what) {
        if (value instanceof CanonicalJson.Str str) {
            return str.value();
        }
        throw new SemanticIrTextDecodeException(
            "the \"" + what + "\" field must be a string, got " + jsonKindName(value));
    }

    /** A string-or-explicit-null value or a decode failure. */
    public static String requireStringOrNull(CanonicalJson.Value value, String what) {
        if (value instanceof CanonicalJson.Null) {
            return null;
        }
        return requireStringValue(value, what);
    }

    /** The signed32 integer value of a pinned key. */
    public static int requireInt(CanonicalJson.Obj obj, String key) {
        CanonicalJson.Value value = entryValue(obj, key);
        if (value instanceof CanonicalJson.Int i) {
            return i.value();
        }
        throw new SemanticIrTextDecodeException(
            "the \"" + key + "\" field must be a canonical signed32 integer, got "
                + jsonKindName(value));
    }

    /** The array value of a pinned key. */
    public static CanonicalJson.Arr requireArray(CanonicalJson.Obj obj, String key) {
        CanonicalJson.Value value = entryValue(obj, key);
        if (value instanceof CanonicalJson.Arr arr) {
            return arr;
        }
        throw new SemanticIrTextDecodeException(
            "the \"" + key + "\" field must be an array, got " + jsonKindName(value));
    }

    /** The boolean value of a pinned key. */
    public static boolean requireBool(CanonicalJson.Obj obj, String key) {
        CanonicalJson.Value value = entryValue(obj, key);
        if (value instanceof CanonicalJson.Bool bool) {
            return bool.value();
        }
        throw new SemanticIrTextDecodeException(
            "the \"" + key + "\" field must be a boolean, got " + jsonKindName(value));
    }

    /** The object value of a pinned key. */
    public static CanonicalJson.Obj requireObject(CanonicalJson.Obj obj, String key) {
        return requireObjectValue(entryValue(obj, key), key);
    }

    /** An object value or a decode failure. */
    public static CanonicalJson.Obj requireObjectValue(CanonicalJson.Value value, String what) {
        if (value instanceof CanonicalJson.Obj obj) {
            return obj;
        }
        throw new SemanticIrTextDecodeException(
            "the \"" + what + "\" field must be an object, got " + jsonKindName(value));
    }

    /** The pinned module semantic-id object for a module path. */
    public static CanonicalJson.Value moduleIdJson(String modulePath) {
        return CanonicalJson.obj(
            CanonicalJson.e("path", CanonicalJson.str(modulePath)),
            CanonicalJson.e("type", CanonicalJson.str("module")));
    }

    /**
     * The module path carried by a module semantic-id object; every other
     * semantic-id shape is a decode failure (the projection never
     * rewrites a foreign shape into a module id). Recognized semantic-id
     * objects keep their exact field set.
     */
    public static String modulePathOf(CanonicalJson.Obj moduleIdObj) {
        checkKeys(moduleIdObj, "path", "type");
        String type = requireString(moduleIdObj, "type");
        if (!"module".equals(type)) {
            throw new SemanticIrTextDecodeException(
                "expected a module semantic-id object, got type \"" + type + "\"");
        }
        return requireString(moduleIdObj, "path");
    }

    /**
     * Parses a module semantic-id object. A module-path precondition
     * failure of the typed {@link ModuleId} record is translated into the
     * transport failure contract (the typed producer preconditions stay
     * unchanged).
     */
    public static ModuleId parseModuleId(CanonicalJson.Obj obj) {
        String modulePath = modulePathOf(obj);
        try {
            return new ModuleId(modulePath);
        } catch (IllegalArgumentException e) {
            throw new SemanticIrTextDecodeException(
                "invalid module semantic-id object: " + e.getMessage());
        }
    }

    /** Parses a class semantic-id object. */
    public static ClassId parseClassId(CanonicalJson.Obj obj) {
        checkKeys(obj, "modulePath", "name", "type");
        String type = requireString(obj, "type");
        if (!"class".equals(type)) {
            throw new SemanticIrTextDecodeException(
                "expected a class semantic-id object, got type \"" + type + "\"");
        }
        try {
            return new ClassId(requireString(obj, "modulePath"), requireString(obj, "name"));
        } catch (IllegalArgumentException e) {
            throw new SemanticIrTextDecodeException(
                "invalid class semantic-id object: " + e.getMessage());
        }
    }

    /** Parses a function semantic-id object. */
    public static FunctionId parseFunctionId(CanonicalJson.Obj obj) {
        return new FunctionId(parseNumericId(obj, "function"));
    }

    /** Parses a binding semantic-id object. */
    public static BindingId parseBindingId(CanonicalJson.Obj obj) {
        return new BindingId(parseNumericId(obj, "binding"));
    }

    /** Parses a block semantic-id object. */
    public static BlockId parseBlockId(CanonicalJson.Obj obj) {
        return new BlockId(parseNumericId(obj, "block"));
    }

    /** Parses a class-factory semantic-id object. */
    public static ClassFactoryId parseClassFactoryId(CanonicalJson.Obj obj) {
        return new ClassFactoryId(parseNumericId(obj, "classFactory"));
    }

    private static long parseNumericId(CanonicalJson.Obj obj, String expectedType) {
        checkKeys(obj, "id", "type");
        String type = requireString(obj, "type");
        if (!expectedType.equals(type)) {
            throw new SemanticIrTextDecodeException(
                "expected a " + expectedType + " semantic-id object, got type \"" + type + "\"");
        }
        int id = requireInt(obj, "id");
        if (id < 0) {
            throw new SemanticIrTextDecodeException(
                "the " + expectedType + " semantic-id id must be non-negative, got " + id);
        }
        return id;
    }

    /** Parses an op semantic-id object. */
    public static OpId parseOpId(CanonicalJson.Obj obj) {
        checkKeys(obj, "id", "modulePath", "type");
        String type = requireString(obj, "type");
        if (!"op".equals(type)) {
            throw new SemanticIrTextDecodeException(
                "expected an op semantic-id object, got type \"" + type + "\"");
        }
        try {
            return new OpId(new ModuleId(requireString(obj, "modulePath")), requireInt(obj, "id"));
        } catch (IllegalArgumentException e) {
            throw new SemanticIrTextDecodeException("invalid op semantic-id object: " + e.getMessage());
        }
    }

    /**
     * Parses an optional op semantic-id position: {@code null} for an
     * absent key or a JSON null, an op-id object otherwise, and a decode
     * failure for every other value type. The dynamic materialization
     * shape's required producing-op position is decoded here (the
     * transport carries the raw position byte-intact) so that a doctored
     * text reaches the validator's closed R-ENUM position clause instead
     * of a decode failure.
     */
    private static OpId optionalOpId(CanonicalJson.Obj obj, String key) {
        CanonicalJson.Value value = payloadValue(obj, key);
        if (value == null || value instanceof CanonicalJson.Null) {
            return null;
        }
        if (value instanceof CanonicalJson.Obj opId) {
            return parseOpId(opId);
        }
        throw new SemanticIrTextDecodeException(
            "the \"" + key + "\" field must be null or an op-id object, got "
                + jsonKindName(value));
    }

    /** Parses a value semantic-id object. */
    public static ValueId parseValueId(CanonicalJson.Obj obj) {
        checkKeys(obj, "id", "type");
        String type = requireString(obj, "type");
        if (!"value".equals(type)) {
            throw new SemanticIrTextDecodeException(
                "expected a value semantic-id object, got type \"" + type + "\"");
        }
        int id = requireInt(obj, "id");
        if (id < 0) {
            throw new SemanticIrTextDecodeException(
                "the value semantic-id id must be non-negative, got " + id);
        }
        return new ValueId(id);
    }

    /** Parses a token semantic-id object (recursive alias referents). */
    public static AsyncTokenId parseToken(CanonicalJson.Obj obj) {
        String type = requireString(obj, "type");
        return switch (type) {
            case "tokenCanonical" -> {
                checkKeys(obj, "owner", "tokenId", "type");
                yield new AsyncTokenId.Canonical(requireTokenId(obj),
                    parseTokenOwner(requireString(obj, "owner")));
            }
            case "tokenAlias" -> {
                checkKeys(obj, "linkKind", "referent", "tokenId", "type");
                yield new AsyncTokenId.Alias(requireTokenId(obj),
                    parseToken(requireObject(obj, "referent")),
                    parseLinkKind(requireString(obj, "linkKind")));
            }
            default -> throw new SemanticIrTextDecodeException(
                "expected a token semantic-id object, got type \"" + type
                    + "\"");
        };
    }

    /**
     * The non-negative token id of a token semantic-id object: the typed
     * record's precondition is checked on the transport surface so an
     * untrusted id never leaks a producer exception.
     */
    private static long requireTokenId(CanonicalJson.Obj obj) {
        int tokenId = requireInt(obj, "tokenId");
        if (tokenId < 0) {
            throw new SemanticIrTextDecodeException(
                "the token semantic-id tokenId must be non-negative, got " + tokenId);
        }
        return tokenId;
    }

    private static AsyncTokenOwner parseTokenOwner(String raw) {
        try {
            return AsyncTokenOwner.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new SemanticIrTextDecodeException(
                "unknown async token owner \"" + raw + "\"");
        }
    }

    private static AsyncLinkKind parseLinkKind(String raw) {
        try {
            return AsyncLinkKind.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new SemanticIrTextDecodeException(
                "unknown async link kind \"" + raw + "\"");
        }
    }

    /** The raw string of a payload field, or {@code null} when absent or JSON null. */
    public static String optionalString(CanonicalJson.Obj obj, String key) {
        CanonicalJson.Value value = payloadValue(obj, key);
        if (value == null || value instanceof CanonicalJson.Null) {
            return null;
        }
        if (value instanceof CanonicalJson.Str str) {
            return str.value();
        }
        throw new SemanticIrTextDecodeException(
            "the \"" + key + "\" field must be a string or null, got " + jsonKindName(value));
    }

    /** The value of a payload field, or {@code null} when absent. */
    public static CanonicalJson.Value payloadValue(CanonicalJson.Obj obj, String key) {
        for (CanonicalJson.Entry entry : obj.entries()) {
            if (entry.key().equals(key)) {
                return entry.value();
            }
        }
        return null;
    }

    /** The boolean of a payload field (decode failure on absence/wrong type). */
    public static boolean isTrue(CanonicalJson.Obj obj, String key) {
        CanonicalJson.Value value = payloadValue(obj, key);
        if (value instanceof CanonicalJson.Bool bool) {
            return bool.value();
        }
        throw new SemanticIrTextDecodeException(
            "the \"" + key + "\" field must be a boolean, got " + jsonKindName(value));
    }

    /** Parses an ordered op-id list payload field (empty when absent). */
    public static List<OpId> parseOpIdList(CanonicalJson.Obj obj, String key) {
        List<OpId> result = new ArrayList<>();
        CanonicalJson.Value value = payloadValue(obj, key);
        if (value == null) {
            return result;
        }
        if (!(value instanceof CanonicalJson.Arr arr)) {
            throw new SemanticIrTextDecodeException(
                "the \"" + key + "\" field must be an array, got " + jsonKindName(value));
        }
        for (CanonicalJson.Value item : arr.items()) {
            result.add(parseOpId(requireObjectValue(item, key + " entry")));
        }
        return result;
    }

    /** Parses an optional op-id payload field ({@code null} when absent or JSON null). */
    public static OpId parseOptionalOpId(CanonicalJson.Obj obj, String key) {
        CanonicalJson.Value value = payloadValue(obj, key);
        if (value == null || value instanceof CanonicalJson.Null) {
            return null;
        }
        return parseOpId(requireObjectValue(value, key));
    }
}
