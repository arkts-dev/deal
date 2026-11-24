package deal.diagnostics;

import deal.amend.CompilerProtocol;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared diagnostic projection governed by docs/diagnostics-protocol-v3.md. */
public final class DiagnosticJson {
    private record Snapshot(String file, String revision, String kind) {}

    private final Path projectRoot;
    private final List<Map<String, Object>> files = new ArrayList<>();
    private final List<Map<String, Object>> context = new ArrayList<>();
    private final Map<Snapshot, Integer> fileIndexes = new LinkedHashMap<>();
    private final Map<String, Snapshot> current = new LinkedHashMap<>();
    private Snapshot primary;

    public DiagnosticJson(Path projectRoot, DiagnosticSourceBatch batch) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        if (batch != null) for (var source : batch.sources())
            current.put(source.file(), new Snapshot(source.file(), source.sourceRevision(), kind(source.snapshotKind())));
    }

    private static String kind(DiagnosticOccurrence.SnapshotKind kind) {
        return kind == DiagnosticOccurrence.SnapshotKind.CURRENT_SOURCE ? "current" : "candidate";
    }

    private int file(Snapshot snapshot) {
        return fileIndexes.computeIfAbsent(snapshot, key -> {
            int index = files.size();
            files.add(object("file", path(key.file()), "revision", key.revision(), "snapshot", key.kind()));
            return index;
        });
    }

    private String path(String file) {
        Path path = Path.of(file).normalize();
        if (projectRoot != null && path.isAbsolute() && path.startsWith(projectRoot))
            path = projectRoot.relativize(path);
        return path.toString().replace(java.io.File.separatorChar, '/');
    }

    /** Locations without retained snapshot identity remain unavailable. */
    public Map<String, Object> location(DiagnosticRange range) {
        if (range == null || range.file() == null || range.file().isEmpty()) return null;
        Snapshot snapshot = primary != null && primary.file().equals(range.file()) ? primary : current.get(range.file());
        if (snapshot == null) return null;
        var value = object("file", file(snapshot), "at", List.of(range.startLine(), range.startColumn(), range.endLine(), range.endColumn()));
        if (range.hasScalarOffsets()) value.put("offset", List.of(range.startScalarOffset(), range.endScalarOffset()));
        if (range.origin() == RangeOrigin.SYNTHETIC) value.put("origin", "synthetic");
        return value;
    }

    public Map<String, Object> compiler(List<CompilerDiagnostic> diagnostics, List<OperationalDiagnostic> failures,
                                        CompilationOutcome outcome) {
        var items = new ArrayList<Map<String, Object>>();
        for (var diagnostic : diagnostics) items.add(diagnostic(diagnostic));
        linkCauses(items, diagnostics.stream().map(CompilerDiagnostic::occurrence).toList());
        String status = !failures.isEmpty() ? "failed"
                : diagnostics.stream().anyMatch(d -> d.severity().equals("error")) ? "rejected"
                : outcome == null || outcome.published() ? "accepted" : "failed";
        var report = report(status, items);
        if (status.equals("failed")) {
            var entries = new ArrayList<Map<String, Object>>();
            for (var failure : failures) entries.add(object("reason", failure.reason(), "responsibility", failure.responsibility(), "message", failure.message()));
            if (entries.isEmpty()) entries.add(object("reason", "compilation.incomplete", "responsibility", "COMPILER_CONTRACT",
                    "message", "Compilation did not complete publication"));
            report.put("failures", entries);
        }
        return report;
    }

    public Map<String, Object> amendment(List<CompilerProtocol.StructuredDiagnostic> diagnostics, boolean accepted) {
        var items = new ArrayList<Map<String, Object>>();
        for (var d : diagnostics) {
            var value = diagnostic(d.code(), d.severity(), d.message(), d.range(), d.classification(), d.facts(),
                    d.notes(), d.missingSymbols(), d.excerpt(), d.occurrence(), d.editOffer());
            if (d.ownerId() != null) value.put("owner", d.ownerId().value());
            put(value, "related", d.relatedIds().stream().filter(id -> !Objects.equals(id, d.ownerId())).map(CompilerProtocol.SemanticId::value).distinct().toList());
            put(value, "repairs", d.repairScopes().stream().map(scope -> object("operation", scope.operation(), "owner", scope.ownerId().value())).toList());
            if (d.operationIndex() != null) value.put("operationIndex", d.operationIndex());
            items.add(value);
        }
        linkCauses(items, diagnostics.stream().map(CompilerProtocol.StructuredDiagnostic::occurrence).toList());
        return report(accepted ? "accepted" : "rejected", items);
    }

    private Map<String, Object> report(String status, List<Map<String, Object>> items) {
        var result = object("version", 3, "status", status, "files", files, "diagnostics", items);
        put(result, "context", context);
        // Canonical JSON writes keys in lexical order: index files by that reference walk.
        var order = new LinkedHashMap<Integer, Integer>();
        reindexFiles(result, order);
        var ordered = new ArrayList<Map<String, Object>>();
        for (int index : order.keySet()) ordered.add(files.get(index));
        result.put("files", ordered);
        return result;
    }

    private static void reindexFiles(Object value, Map<Integer, Integer> order) {
        if (value instanceof Map<?, ?> map) {
            for (var key : map.keySet().stream().map(String.class::cast).sorted().toList()) {
                Object item = map.get(key);
                if (key.equals("file") && item instanceof Integer index) {
                    @SuppressWarnings("unchecked") var entry = (Map<String, Object>) map;
                    entry.put(key, order.computeIfAbsent(index, ignored -> order.size()));
                } else if (!key.equals("files")) reindexFiles(item, order);
            }
        } else if (value instanceof List<?> list) for (var item : list) reindexFiles(item, order);
    }

    private static void linkCauses(List<Map<String, Object>> values, List<DiagnosticOccurrence> occurrences) {
        for (int i = 0; i < occurrences.size(); i++) {
            var occurrence = occurrences.get(i);
            if (occurrence == null || occurrence.rootCauseId() == null) continue;
            int match = -1;
            for (int j = 0; j < occurrences.size(); j++) {
                var other = occurrences.get(j);
                if (j != i && other != null && occurrence.sourceRevision().equals(other.sourceRevision())
                        && occurrence.snapshotKind() == other.snapshotKind() && occurrence.rootCauseId().equals(other.occurrenceId())) {
                    if (match >= 0) { match = -1; break; }
                    match = j;
                }
            }
            if (match >= 0) values.get(i).put("cause", match);
        }
    }

    private Map<String, Object> diagnostic(CompilerDiagnostic d) {
        return diagnostic(d.code(), d.severity(), d.message(), d.range(), DiagnosticClassification.forCode(d.code()),
                d.facts(), d.notes(), d.missingSymbols(), d.excerpt(), d.occurrence(), d.editOffer());
    }

    private Map<String, Object> diagnostic(String code, String severity, String message, DiagnosticRange range,
            DiagnosticClassification classification, DiagnosticFacts facts, List<DiagnosticNote> notes,
            List<CompilerDiagnostic.MissingSymbol> missing, DiagnosticExcerpt excerpt, DiagnosticOccurrence occurrence,
            DiagnosticEditOffer edit) {
        if (occurrence != null) occurrence.sourceRevisions().forEach((file, revision) ->
                current.put(file, new Snapshot(file, revision, "current")));
        primary = occurrence != null && range != null && !range.file().isEmpty()
                ? new Snapshot(range.file(), occurrence.sourceRevision(), kind(occurrence.snapshotKind())) : null;
        var value = object("code", code, "severity", severity, "message", message,
                "responsibility", classification == null ? "COMPILER_CONTRACT" : classification.responsibility().name());
        put(value, "range", location(range));
        put(value, "reason", facts.reason()); put(value, "operation", facts.operation());
        put(value, "expected", facts.expected()); put(value, "actual", facts.actual()); put(value, "guidance", facts.guidance());
        put(value, "evidence", evidence(facts, missing));
        var difference = facts.languageDifference();
        if (difference != null) value.put("difference", object("reference", difference.reference().name(), "kind", difference.kind().name(), "feature", difference.feature()));
        put(value, "notes", notes.stream().map(note -> {
            var entry = object("message", note.message()); put(entry, "range", location(note.range())); return entry;
        }).toList());
        if (excerpt != null && primary != null) {
            var entry = object("file", file(primary), "start", excerpt.startScalarOffset(), "text", excerpt.text());
            if (excerpt.primaryTruncated()) entry.put("primaryTruncated", true);
            int index = -1;
            for (int i = 0; i < context.size(); i++) {
                var retained = context.get(i);
                if (retained.get("file").equals(entry.get("file")) && retained.get("start").equals(entry.get("start"))
                        && retained.get("text").equals(entry.get("text"))) { index = i; break; }
            }
            if (index < 0) { index = context.size(); context.add(entry); }
            else if (excerpt.primaryTruncated()) context.get(index).put("primaryTruncated", true);
            value.put("context", index);
        }
        if (edit != null && primary != null && edit.sourceRevision().equals(primary.revision()) && edit.file().equals(primary.file()))
            value.put("edit", object("file", file(primary), "offset", List.of(edit.startUtf16Offset(), edit.endUtf16Offset()), "expected", edit.expectedText(), "text", edit.replacement()));
        primary = null;
        return value;
    }

    private Map<String, Object> evidence(DiagnosticFacts f, List<CompilerDiagnostic.MissingSymbol> missing) {
        var e = object();
        put(e, "path", f.path().stream().map(p -> p.kind() == DiagnosticFacts.PathKind.FIELD
                ? object("kind", "FIELD", "name", p.name()) : object("kind", "ARRAY_ELEMENT")).toList());
        if (f.mismatch() != null) {
            var m = f.mismatch(); var entry = object("rule", m.rule());
            put(entry, "path", m.path().stream().map(p -> p.kind() == DiagnosticFacts.TypeComponentKind.FUNCTION_PARAMETER
                    ? object("kind", p.kind().name(), "index", p.index()) : object("kind", p.kind().name())).toList());
            if (!m.expected().equals(f.expected())) put(entry, "expected", m.expected());
            if (!m.actual().equals(f.actual())) put(entry, "actual", m.actual());
            if (m.truncated()) entry.put("truncated", true);
            e.put("mismatch", entry);
        }
        if (f.call() != null) {
            var c = f.call(); var entry = object("callee", c.callee(), "signature", c.signature(), "argumentIndex", c.argumentIndex(), "expectedCount", c.expectedCount(), "actualCount", c.actualCount());
            put(entry, "parameterName", c.parameterName()); put(entry, "declarationRange", location(c.declarationRange())); put(entry, "parameterRange", location(c.parameterRange())); e.put("call", entry);
        }
        if (f.operator() != null) {
            var o = f.operator(); var entry = object("token", o.token(), "operands", o.operands().stream().map(p -> {
                var operand = object("role", p.role(), "type", p.type()); put(operand, "range", location(p.range())); return operand;
            }).toList()); put(entry, "acceptedCombinations", o.acceptedCombinations()); e.put("operator", entry);
        }
        if (f.syntax() != null) {
            var s = f.syntax(); var entry = object("context", s.context(), "foundKind", s.foundKind());
            put(entry, "expectedKinds", s.expectedKinds()); put(entry, "lexeme", s.lexeme());
            if (s.truncated()) entry.put("truncated", true); e.put("syntax", entry);
        }
        if (f.names() != null) {
            var n = f.names(); var entry = object();
            entry.put("candidates", bounded(n.candidates().stream().map(c -> {
                var candidate = object("name", c.name(), "kind", c.kind(), "type", c.type());
                put(candidate, "declarationRange", location(c.declarationRange())); return candidate;
            }).toList(), n.matchingCount()));
            entry.put("observations", bounded(n.observations().stream().map(o -> {
                var observation = object("name", o.name(), "kind", o.kind(), "status", o.status().name());
                put(observation, "declarationRange", location(o.declarationRange())); return observation;
            }).toList(), n.observationCount())); e.put("names", entry);
        }
        if (f.construction() != null) {
            var c = f.construction(); var entry = object("className", c.className(), "provider", c.provider());
            put(entry, "declarationRange", location(c.declarationRange())); put(entry, "fieldName", c.fieldName());
            if (c.optional() != null) entry.put("optional", c.optional());
            put(entry, "fieldRange", location(c.fieldRange())); put(entry, "annotationRange", location(c.annotationRange())); e.put("construction", entry);
        }
        if (f.exports() != null) {
            var x = f.exports(); var entry = object("alias", x.alias(), "requested", x.requested(), "available", bounded(x.available(), x.availableCount()));
            put(entry, "importRange", location(x.importRange())); e.put("exports", entry);
        }
        if (f.resolution() != null) {
            var r = f.resolution(); e.put("resolution", object("specifier", r.specifier(), "mode", r.mode(), "attempted", bounded(r.attempted(), r.attemptedCount())));
        }
        if (f.cycle() != null && !f.cycle().edges().isEmpty()) {
            var c = f.cycle(); var entry = object("edges", c.edges().stream().map(edge -> {
                var item = object("from", edge.from(), "to", edge.to()); put(item, "importRange", location(edge.importRange())); return item;
            }).toList()); if (c.truncated()) entry.put("omitted", c.edgeCount() - c.edges().size()); e.put("cycle", entry);
        }
        if (f.cause() != null) {
            var c = f.cause(); var range = location(c.range());
            if (range != null) e.put("invalidation", object("reason", c.reason(), "operation", c.operation(), "range", range, "previous", c.previous()));
        }
        put(e, "missingSymbols", missing.stream().map(m -> {
            var entry = object("kind", m.kind(), "name", m.name()); put(entry, "namespace", m.namespace()); put(entry, "constraints", m.constraints()); return entry;
        }).toList());
        return e;
    }

    private static Map<String, Object> bounded(List<?> values, int total) {
        var value = object("items", values);
        if (total > values.size()) value.put("omitted", total - values.size());
        return value;
    }

    public static Map<String, Object> object(Object... fields) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value == null || value instanceof String s && s.isEmpty() || value instanceof List<?> l && l.isEmpty()
                || value instanceof Map<?, ?> m && m.isEmpty()) return;
        map.put(key, value);
    }
}
