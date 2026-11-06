package deal.checker;

import deal.ast.ClassField;
import java.util.List;

/** Shared declared-field lookup; Object.hasOwn is checked from HasExpr. */
final class IntrinsicResolvers {
    private IntrinsicResolvers() {}

    static ClassField findField(List<ClassField> fields, String name) {
        for (ClassField f : fields) {
            if (f.name().equals(name)) return f;
        }
        return null;
    }
}
