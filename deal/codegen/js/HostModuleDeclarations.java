package deal.codegen.js;

import deal.ast.ClassField;
import deal.types.Type;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One host-module declaration record (ISSUE-0328,
 * js-v12-host-abi-completion D1): the declared export map plus the
 * declaration AST's class-field records with their orchestrator-resolved
 * declared types, carried from the orchestrator's hostModules gather
 * ({@code CompilationOrchestrator.codegenAllJs}) to the JS emitter's
 * declared-map rendering ({@code JsBackend}).
 *
 * <p>The {@code exports} map is the host declaration's public surface —
 * export name &rarr; declared {@link Type} — exactly the map the
 * LuaJIT/JVM host-module gathers already carry. The {@code classFields}
 * map carries, per class export name, the field records in declaration
 * order: the declaration AST's {@link ClassField} (name, optional,
 * nullable, default-expression presence) plus the resolved field
 * {@link Type} the emitter encodes as the entry's canonical {@code $d}
 * descriptor. The resolution runs at gather time in the declaring
 * module's own context, so a same-module class field type (e.g.
 * {@code endpoint: Endpoint} inside {@code cfg.d.deal}) resolves to the
 * declaring module's class identity — the importing module's symbol
 * table has no binding for the bare name.
 */
public record HostModuleDeclarations(
        Map<String, Type> exports,
        Map<String, List<HostField>> classFields) {

    public HostModuleDeclarations {
        exports = Map.copyOf(exports);
        classFields = Map.copyOf(classFields);
    }

    /**
     * One declared host-class field: the declaration AST's
     * {@link ClassField} record plus the orchestrator-resolved declared
     * type. The emitter renders {@code name}/{@code optional}/
     * {@code nullable}/{@code hasDefault} from the AST record and the
     * canonical {@code $d} descriptor from the resolved type.
     */
    public record HostField(ClassField declaration, Type type) {
        public HostField {
            Objects.requireNonNull(declaration,
                "declaration must not be null");
            Objects.requireNonNull(type, "type must not be null");
        }
    }
}
