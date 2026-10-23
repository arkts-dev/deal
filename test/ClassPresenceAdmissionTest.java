package deal.test;

import deal.module.CompilationOrchestrator;
import deal.project.ProjectLocator;
import deal.semantic.CapabilityRegistry;
import deal.semantic.ClassConstructionValidator;
import deal.semantic.ir.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independent typed, raw and canonical-text mutations of structural class facts. */
public final class ClassPresenceAdmissionTest {
    private static int passed;
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        passed++;
    }

    private static SemanticOp rebuild(SemanticOp op, KindPayload payload) {
        var seed = new OperationContractSnapshot(OperationContractSnapshot.VERSION, op.kind(),
            op.resultType(), op.operandTypes(), null, payload, op.failurePolicy(), List.of(), "seed");
        var contract = new OperationContractSnapshot(OperationContractSnapshot.VERSION, op.kind(),
            op.resultType(), op.operandTypes(), null, payload, op.failurePolicy(), List.of(),
            ContractSnapshotCanonicalizer.digest(seed));
        return new SemanticOp(op.opId(), op.kind(), op.origin(), op.result(), op.resultType(),
            op.operands(), op.operandTypes(), payload, op.failurePolicy(), contract);
    }

    private static LoweredModuleUnit replace(LoweredModuleUnit unit, SemanticOp replacement) {
        return new LoweredModuleUnit(unit.formatVersion(), unit.semanticProfile(), unit.moduleId(),
            unit.interfaceHash(), unit.loweringContextHash(), unit.requiredCapabilities(),
            unit.constructCoverage(), unit.classLayouts(), unit.functions(), unit.moduleInit(),
            unit.exportPlan(), unit.functionBindings(), unit.ops().stream()
                .map(op -> op.opId().equals(replacement.opId()) ? replacement : op).toList(),
            unit.bodyTable(), unit.classFactories(), unit.classBindingFacts());
    }

    private static CanonicalJson.Obj append(CanonicalJson.Obj object, String key,
                                            CanonicalJson.Value value) {
        var entries = new ArrayList<>(object.entries());
        entries.add(CanonicalJson.e(key, value));
        return new CanonicalJson.Obj(entries);
    }

    private static CanonicalJson.Value mutateOp(CanonicalJson.Value value, OpId target,
            java.util.function.UnaryOperator<CanonicalJson.Obj> mutation) {
        if (value instanceof CanonicalJson.Obj object) {
            var id = object.entries().stream().filter(e -> e.key().equals("opId"))
                .map(CanonicalJson.Entry::value).findFirst().orElse(null);
            if (ContractSnapshotCanonicalizer.semanticIdJson(target).equals(id)) return mutation.apply(object);
            return new CanonicalJson.Obj(object.entries().stream()
                .map(e -> CanonicalJson.e(e.key(), mutateOp(e.value(), target, mutation))).toList());
        }
        if (value instanceof CanonicalJson.Arr array) return new CanonicalJson.Arr(array.items()
            .stream().map(v -> mutateOp(v, target, mutation)).toList());
        return value;
    }

    private static CanonicalJson.Obj change(CanonicalJson.Obj object, String key,
                                            CanonicalJson.Value value) {
        return new CanonicalJson.Obj(object.entries().stream()
            .map(e -> e.key().equals(key) ? CanonicalJson.e(key, value) : e).toList());
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("deal-class-presence-admission-");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("deal.json"), """
            {"languageVersion":"1.3","moduleRoots":["src"],"backend":"js","output":"out"}
            """);
        Files.writeString(root.resolve("src/model.deal"), """
            export class Shared { required: number; nullable: string | null; optional?: number; }
            """);
        Files.writeString(root.resolve("src/main.deal"), """
            import * as model from "./model";
            class Record { required: number; nullable: number | null; optional?: number; }
            export function main(): null {
              let record: Record = {required: 1, nullable: null};
              let error: Error = {code: "TEST", message: "complete"};
              let imported: model.Shared = {required: 2, nullable: null};
              return null;
            }
            """);
        Path source = root.resolve("src/main.deal");
        var located = ProjectLocator.locate(source.toString(), null);
        var owner = new CompilationOrchestrator(located.context(), source, false, false,
            false, false, null, ConformanceHarnessMetadata.invocation());
        check(owner.compile(), "baseline production compile: " + owner.diagnostics());
        var project = owner.producedProject();
        var facts = new SemanticIrValidator.ComparisonFacts(project.interfaceIndex()
            .interfaceIndexDigest(), project.semanticProfile(),
            CapabilityRegistry.releaseRegistry().capabilityRegistryHash());
        check(SemanticIrValidator.validate(project, facts).isEmpty(), "baseline typed admission");
        String text = SemanticIrValidator.toProjectText(project);
        check(SemanticIrValidator.validateText(text, facts).isEmpty(), "baseline text admission");
        LoweredModuleUnit unit = project.modules().get(project.entryModule());
        var own = project.interfaceIndex().modules().get(unit.moduleId());
        Map<ClassId, SharedFactoryFacts> shared = new LinkedHashMap<>();
        for (var provider : project.modules().values()) {
            for (var entry : project.interfaceIndex().modules().get(provider.moduleId()).classes()) {
                OpId factoryId = provider.classFactories().factoryFor(entry.constructionEntry());
                SemanticOp factory = provider.ops().stream().filter(op -> op.opId().equals(factoryId))
                    .findFirst().orElseThrow();
                shared.put(entry.classId(), new SharedFactoryFacts(entry.classId(), entry,
                    provider.classLayouts().get(entry.classId()), factory.opId(), (ValueId) factory.result()));
            }
        }
        check(ClassConstructionValidator.validate(unit, unit.bodyTable(), unit.classFactories(),
            own, shared, project.classSeeds().registrations()).isEmpty(),
            "baseline construction owner");

        List<SemanticOp> constructions = unit.ops().stream()
            .filter(op -> op.kind() == SemanticOpKind.CLASS_NEW).toList();
        check(constructions.size() == 3, "local, builtin Error and imported constructions");
        check(constructions.stream().anyMatch(op -> ((KindPayload.ClassNewPayload) op.payload())
            .constructionProvider() == ConstructionProvider.SHARED_FACTORY), "imported fixture exercises provider route");
        int omitted = 0;
        for (SemanticOp op : constructions) {
            var payload = (KindPayload.ClassNewPayload) op.payload();
            var duplicate = new ArrayList<>(payload.providedFields());
            duplicate.add(payload.providedFields().getFirst());
            var duplicateOp = rebuild(op, new KindPayload.ClassNewPayload(payload.classId(),
                payload.layout(), duplicate, payload.constructionProvider(), payload.classFactoryRef(),
                payload.fieldBoundaries()));
            var duplicateUnit = replace(unit, duplicateOp);
            var duplicateFailure = ClassConstructionValidator.validate(duplicateUnit, unit.bodyTable(),
                unit.classFactories(), own, shared,
                project.classSeeds().registrations());
            check(duplicateFailure.isPresent() && duplicateFailure.get().code().equals("E6005")
                && duplicateFailure.get().message().contains("repeats provided field '"),
                "duplicate field rejected independently on " + payload.constructionProvider());
            var duplicateModules = new LinkedHashMap<>(project.modules());
            duplicateModules.put(unit.moduleId(), duplicateUnit);
            var duplicateProject = new ExecutableLoweredProject(project.semanticProfile(),
                project.interfaceIndex(), duplicateModules, project.entryModule(), project.classSeeds());
            check(SemanticIrValidator.validate(duplicateProject, facts).isPresent(),
                "typed duplicate rejects on " + payload.constructionProvider());
            check(SemanticIrValidator.validateText(SemanticIrValidator.toProjectText(duplicateProject),
                facts).isPresent(), "text duplicate rejects on " + payload.constructionProvider());
            for (ClassLayout.FieldLayout field : payload.layout().fields()) {
                if (!field.required()) continue;
                var provided = payload.providedFields().stream()
                    .filter(p -> !p.name().equals(field.name())).toList();
                var forged = rebuild(op, new KindPayload.ClassNewPayload(payload.classId(),
                    payload.layout(), provided, payload.constructionProvider(), payload.classFactoryRef(),
                    payload.fieldBoundaries()));
                LoweredModuleUnit malformed = replace(unit, forged);
                var rejected = ClassConstructionValidator.validate(malformed, unit.bodyTable(),
                    unit.classFactories(), own, shared,
                    project.classSeeds().registrations());
                check(rejected.isPresent() && rejected.get().code().equals("E6005")
                    && rejected.get().message().contains("omits required-present field '" + field.name() + "'"),
                    "required omission rejected by construction owner: " + field.name() + ": " + rejected);
                var modules = new LinkedHashMap<>(project.modules());
                modules.put(unit.moduleId(), malformed);
                var badProject = new ExecutableLoweredProject(project.semanticProfile(),
                    project.interfaceIndex(), modules, project.entryModule(), project.classSeeds());
                check(SemanticIrValidator.validate(badProject, facts).isPresent(),
                    "typed project rejects omission of " + field.name());
                check(SemanticIrValidator.validateText(SemanticIrValidator.toProjectText(badProject),
                    facts).isPresent(), "text project rejects omission of " + field.name());
                omitted++;
            }
        }
        check(omitted == 6, "local, builtin and imported required fields independently mutated");

        // Removed payload keys are not ignored by raw materialization or project text admission.
        var json = (CanonicalJson.Obj) ContractSnapshotCanonicalizer.parseValidationText(text);
        for (LoweredModuleUnit provider : project.modules().values()) {
            var rawJson = ContractSnapshotCanonicalizer.toJson(RawUnit.fromTyped(provider));
            for (var rows : List.of(CanonicalJson.arr(List.of()), CanonicalJson.arr(List.of(
                    CanonicalJson.obj(CanonicalJson.e("jsonFromClassOp",
                        ContractSnapshotCanonicalizer.semanticIdJson(provider.ops().getFirst().opId())),
                        CanonicalJson.e("classDefaultOps", CanonicalJson.arr(List.of()))))))) {
                var retired = append((CanonicalJson.Obj) rawJson, "jsonDefaults", rows);
                boolean rejected = false;
                try { ContractSnapshotCanonicalizer.parseUnit(retired); }
                catch (SemanticIrTextDecodeException expected) { rejected = true; }
                check(rejected, "raw decoding rejects retired JSON companion even with empty rows");
                boolean textRejected = false;
                try { textRejected = SemanticIrValidator.validateText(
                    CanonicalJson.serializeText(retired), facts).isPresent(); }
                catch (SemanticIrTextDecodeException expected) { textRejected = true; }
                check(textRejected, "text admission rejects retired JSON companion even with empty rows");
            }
        }
        int factoryMutations = 0;
        for (LoweredModuleUnit provider : project.modules().values()) {
          for (SemanticOp op : provider.ops()) {
            if (op.kind() != SemanticOpKind.CLASS_NEW && op.kind() != SemanticOpKind.CLASS_FACTORY)
                continue;
            if (op.kind() == SemanticOpKind.CLASS_FACTORY) factoryMutations++;
            CanonicalJson.Obj oldPayload = append((CanonicalJson.Obj) ContractSnapshotCanonicalizer.payloadJson(op.payload()),
                "classDefaultOpIds", CanonicalJson.arr(List.of()));
            CanonicalJson.Value malformed = mutateOp(json, op.opId(), o -> change(o, "payload", oldPayload));
            check(SemanticIrValidator.validateText(CanonicalJson.serializeText(malformed), facts).isPresent(),
                "text rejects removed default-child key on " + op.kind());
            var rawJson = ContractSnapshotCanonicalizer.toJson(RawUnit.fromTyped(provider));
            var badUnit = (CanonicalJson.Obj) mutateOp(rawJson, op.opId(),
                o -> change(o, "payload", oldPayload));
            boolean rejected = false;
            try { RawUnitMaterializer.materialize(ContractSnapshotCanonicalizer.parseUnit(badUnit)); }
            catch (RawUnitMaterializer.Defect expected) { rejected = true; }
            check(rejected, "raw inverse rejects removed default-child key on " + op.kind());
          }
        }
        check(factoryMutations == 1, "exported provider factory mutation actually executes");
        SemanticOp subject = constructions.getFirst();
        var removedOp = mutateOp(json, subject.opId(),
            o -> change(o, "kind", CanonicalJson.str("CLASS_DEFAULT")));
        check(SemanticIrValidator.validateText(CanonicalJson.serializeText(removedOp), facts).isPresent(),
            "removed CLASS_DEFAULT opcode is not admitted");
        SemanticOp ordinary = constructions.stream().filter(op ->
            ((KindPayload.ClassNewPayload) op.payload()).constructionProvider() == ConstructionProvider.LOCAL)
            .findFirst().orElseThrow();
        var ordinaryPayload = (KindPayload.ClassNewPayload) ordinary.payload();
        OpId fieldBoundaryId = ordinaryPayload.fieldBoundaries().getFirst().boundaryOpId();
        var retiredBoundary = mutateOp(json, fieldBoundaryId, o -> change(o, "payload",
            change((CanonicalJson.Obj) o.entries().stream().filter(e -> e.key().equals("payload"))
                .findFirst().orElseThrow().value(), "kind", CanonicalJson.str("CLASS_DEFAULT_FIELD"))));
        check(SemanticIrValidator.validateText(CanonicalJson.serializeText(retiredBoundary), facts).isPresent(),
            "canonical-text admission rejects the removed CLASS_DEFAULT_FIELD boundary spelling");
        var rawBoundary = mutateOp(ContractSnapshotCanonicalizer.toJson(RawUnit.fromTyped(unit)), fieldBoundaryId,
            o -> change(o, "payload", change((CanonicalJson.Obj) o.entries().stream()
                .filter(e -> e.key().equals("payload")).findFirst().orElseThrow().value(),
                "kind", CanonicalJson.str("CLASS_DEFAULT_FIELD"))));
        boolean retiredRejected = false;
        try { RawUnitMaterializer.materialize(ContractSnapshotCanonicalizer.parseUnit((CanonicalJson.Obj) rawBoundary)); }
        catch (RawUnitMaterializer.Defect expected) { retiredRejected = true; }
        check(retiredRejected, "raw inverse rejects the removed CLASS_DEFAULT_FIELD boundary spelling");
        List<KindPayload.FieldBoundary> original = ordinaryPayload.fieldBoundaries();
        var reversed = new ArrayList<>(original.reversed());
        var repeated = new ArrayList<>(original);
        repeated.add(original.getFirst());
        for (var boundaries : List.of(List.<KindPayload.FieldBoundary>of(), reversed, repeated)) {
            var forged = rebuild(ordinary, new KindPayload.ClassNewPayload(ordinaryPayload.classId(),
                ordinaryPayload.layout(), ordinaryPayload.providedFields(), ordinaryPayload.constructionProvider(),
                ordinaryPayload.classFactoryRef(), boundaries));
            var malformed = replace(unit, forged);
            var rejected = ClassConstructionValidator.validate(malformed, unit.bodyTable(),
                unit.classFactories(), own, shared,
                project.classSeeds().registrations());
            check(rejected.isPresent() && rejected.get().code().equals("E6005"),
                "construction owner rejects absent/reordered/repeated boundary: " + boundaries);
            var modules = new LinkedHashMap<>(project.modules());
            modules.put(unit.moduleId(), malformed);
            var badProject = new ExecutableLoweredProject(project.semanticProfile(), project.interfaceIndex(),
                modules, project.entryModule(), project.classSeeds());
            check(SemanticIrValidator.validate(badProject, facts).isPresent(), "typed boundary mutation rejects");
            check(SemanticIrValidator.validateText(SemanticIrValidator.toProjectText(badProject), facts)
                .isPresent(), "canonical-text boundary mutation rejects");
        }
        System.out.println("Class presence admission: " + passed + " checks passed");
    }
}
