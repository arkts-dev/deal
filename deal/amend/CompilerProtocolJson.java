package deal.amend;

import deal.semantic.ir.CanonicalJson;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Canonical JSON projection for transport-neutral compiler protocol records. */
public final class CompilerProtocolJson {
    private CompilerProtocolJson() {}

    public static String encode(Object value) {
        return CanonicalJson.serializeText(toValue(value));
    }

    public static CanonicalJson.Value decode(String value) {
        return CanonicalJson.parse(value);
    }

    public static CanonicalJson.Value toValue(Object value) {
        if (value == null) return CanonicalJson.nullValue();
        if (value instanceof CanonicalJson.Value canonical) return canonical;
        if (value instanceof String text) return CanonicalJson.str(text);
        if (value instanceof Boolean flag) return CanonicalJson.bool(flag);
        if (value instanceof Integer number) return CanonicalJson.intValue(number);
        if (value instanceof Long number) {
            if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Protocol integer is outside signed32: " + number);
            }
            return CanonicalJson.intValue(number.intValue());
        }
        if (value instanceof Double number) return CanonicalJson.number(number);
        if (value instanceof Float number) return CanonicalJson.number(number.doubleValue());
        if (value instanceof Enum<?> item) return CanonicalJson.str(item.name());
        if (value instanceof CompilerProtocol.SemanticId id) return CanonicalJson.str(id.value());
        Object response = diagnosticResponse(value);
        if (response != value) return toValue(response);
        if (value instanceof List<?> items) {
            return CanonicalJson.arr(items.stream().map(CompilerProtocolJson::toValue).toList());
        }
        if (value instanceof Map<?, ?> map) {
            List<CanonicalJson.Entry> entries = new ArrayList<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text)) {
                    throw new IllegalArgumentException("Protocol map keys must be strings");
                }
                entries.add(CanonicalJson.e(text, toValue(item)));
            });
            entries.sort(Comparator.comparing(CanonicalJson.Entry::key));
            return CanonicalJson.obj(entries);
        }
        if (value.getClass().isRecord()) return recordValue(value);
        CanonicalJson.Obj desugaredRecord = desugaredRecordValue(value);
        if (desugaredRecord != null) return desugaredRecord;
        throw new IllegalArgumentException("Unsupported protocol JSON value: " + value.getClass().getName());
    }

    public static CanonicalJson.Obj requireObject(CanonicalJson.Value value, String context) {
        if (value instanceof CanonicalJson.Obj object) return object;
        throw new IllegalArgumentException(context + " must be a JSON object");
    }

    public static CanonicalJson.Arr requireArray(CanonicalJson.Value value, String context) {
        if (value instanceof CanonicalJson.Arr array) return array;
        throw new IllegalArgumentException(context + " must be a JSON array");
    }

    public static CanonicalJson.Value field(CanonicalJson.Obj object, String name) {
        return object.entries().stream()
                .filter(entry -> entry.key().equals(name))
                .map(CanonicalJson.Entry::value)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing protocol field '" + name + "'"));
    }

    public static String stringField(CanonicalJson.Obj object, String name) {
        CanonicalJson.Value value = field(object, name);
        if (value instanceof CanonicalJson.Str text) return text.value();
        throw new IllegalArgumentException("Protocol field '" + name + "' must be a string");
    }

    public static int intField(CanonicalJson.Obj object, String name) {
        CanonicalJson.Value value = field(object, name);
        if (value instanceof CanonicalJson.Int number) return number.value();
        throw new IllegalArgumentException("Protocol field '" + name + "' must be an integer");
    }

    private static Object diagnosticResponse(Object value) {
        var projection = new deal.diagnostics.DiagnosticJson(null, null);
        if (value instanceof RepairWorkspaceProtocol.Offer offer) {
            var response = projection.amendment(offer.diagnostics(), offer.disposition() != RepairWorkspaceProtocol.Disposition.UNSUPPORTED);
            response.put("workspaceDigest", offer.workspaceDigest());
            response.put("disposition", offer.disposition());
            response.put("slots", offer.slots());
            response.put("obligations", offer.obligations());
            response.put("expansions", offer.expansions());
            if (!offer.reason().isEmpty()) response.put("reason", offer.reason());
            return response;
        }
        if (value instanceof RepairWorkspaceProtocol.Grant grant)
            return deal.diagnostics.DiagnosticJson.object("version", 3, "workspaceDigest", grant.workspaceDigest(),
                    "slots", grant.slots(), "obligations", grant.obligations(), "expansions", grant.expansions(), "digest", grant.digest());
        if (value instanceof CompilerProtocol.RepairWorkspaceResult result) {
            var diagnostics = new ArrayList<>(result.diagnostics());
            if (result.workspace() != null) for (var slot : result.workspace().slots())
                for (var diagnostic : slot.diagnostics()) if (!diagnostics.contains(diagnostic)) diagnostics.add(diagnostic);
            var response = projection.amendment(diagnostics, result.accepted());
            response.put("source", result.source());
            response.put("sourceDigest", result.sourceDigest());
            response.put("workspace", workspace(result.workspace(), diagnostics));
            return response;
        }
        if (value instanceof CompilerProtocol.StructuredDiagnostic diagnostic)
            return projection.amendment(List.of(diagnostic), !diagnostic.severity().equals("error"));
        if (value instanceof CompilerProtocol.Inspection inspection) {
            var response = projection.amendment(inspection.diagnostics(), inspection.diagnostics().stream().noneMatch(d -> d.severity().equals("error")));
            response.put("sourceDigest", inspection.sourceDigest());
            response.put("moduleId", inspection.moduleId());
            response.put("symbols", inspection.symbols()); response.put("nodes", inspection.nodes());
            response.put("appInterface", inspection.appInterface()); response.put("allowedOperations", inspection.allowedOperations());
            return response;
        }
        if (value instanceof CompilerProtocol.ChangeInspection inspection) {
            var response = projection.amendment(inspection.diagnostics(), inspection.diagnostics().isEmpty());
            response.put("revision", inspection.revision()); response.put("inspectionDigest", inspection.inspectionDigest());
            response.put("allowedOperations", inspection.allowedOperations()); return response;
        }
        if (value instanceof CompilerProtocol.ChangeResult result) {
            var response = projection.amendment(result.diagnostics(), result.accepted());
            response.put("source", result.source()); response.put("sourceDigest", result.sourceDigest());
            if (result.inspection() != null) {
                response.put("moduleId", result.inspection().moduleId());
                response.put("symbols", result.inspection().symbols()); response.put("nodes", result.inspection().nodes());
                response.put("appInterface", result.inspection().appInterface()); response.put("allowedOperations", result.inspection().allowedOperations());
            }
            return response;
        }
        if (value instanceof CompilerProtocol.ContextResult result) {
            var response = projection.amendment(result.diagnostics(), result.accepted());
            response.put("revision", result.revision()); if (result.slice() != null) response.put("slice", result.slice()); return response;
        }
        return value;
    }

    private static Map<String, Object> workspace(CompilerProtocol.RepairWorkspaceSnapshot workspace,
                                                List<CompilerProtocol.StructuredDiagnostic> diagnostics) {
        if (workspace == null) throw new IllegalArgumentException("Repair response requires a workspace");
        var slots = new ArrayList<Map<String, Object>>();
        for (var slot : workspace.slots()) {
            var indexes = new ArrayList<Integer>();
            for (var diagnostic : slot.diagnostics()) {
                int index = diagnostics.indexOf(diagnostic);
                if (index < 0) throw new IllegalArgumentException("Slot diagnostic missing from response");
                indexes.add(index);
            }
            slots.add(deal.diagnostics.DiagnosticJson.object("slotId", slot.slotId(), "operation", slot.operation(),
                    "targetId", slot.targetId().value(), "targetFingerprint", slot.targetFingerprint(), "payload", slot.payload(),
                    "payloadFingerprint", slot.payloadFingerprint(), "status", slot.status(), "dependencyGroupId", slot.dependencyGroupId(), "diagnostics", indexes));
        }
        return deal.diagnostics.DiagnosticJson.object("workspaceId", workspace.workspaceId(), "workspaceDigest", workspace.workspaceDigest(),
                "baseRevision", workspace.baseRevision(), "inspectionDigest", workspace.inspectionDigest(), "precondition", workspace.precondition(),
                "slots", slots, "groups", workspace.groups(), "repairRound", workspace.repairRound());
    }

    private static CanonicalJson.Obj recordValue(Object record) {
        List<CanonicalJson.Entry> entries = new ArrayList<>();
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            try {
                entries.add(CanonicalJson.e(component.getName(), toValue(component.getAccessor().invoke(record))));
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Cannot access protocol record " + component.getName(), failure);
            } catch (InvocationTargetException failure) {
                throw new IllegalStateException("Protocol record accessor failed " + component.getName(), failure.getCause());
            }
        }
        return CanonicalJson.obj(entries);
    }

    /** Android D8 lowers records to final fields plus same-named accessors. */
    private static CanonicalJson.Obj desugaredRecordValue(Object value) {
        List<java.lang.reflect.Field> fields = List.of(value.getClass().getDeclaredFields()).stream()
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> Modifier.isFinal(field.getModifiers()))
                .sorted(Comparator.comparing(java.lang.reflect.Field::getName))
                .toList();
        if (fields.isEmpty()) return null;
        List<CanonicalJson.Entry> entries = new ArrayList<>();
        for (java.lang.reflect.Field field : fields) {
            try {
                var accessor = value.getClass().getMethod(field.getName());
                if (accessor.getParameterCount() != 0) return null;
                entries.add(CanonicalJson.e(field.getName(), toValue(accessor.invoke(value))));
            } catch (NoSuchMethodException | IllegalAccessException failure) {
                return null;
            } catch (InvocationTargetException failure) {
                throw new IllegalStateException(
                        "Protocol accessor failed " + field.getName(), failure.getCause());
            }
        }
        return CanonicalJson.obj(entries);
    }
}
