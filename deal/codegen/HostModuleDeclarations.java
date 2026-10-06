package deal.codegen;

import deal.ast.ClassField;
import deal.types.Type;

import java.util.List;
import java.util.Map;
import java.util.Objects;

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
