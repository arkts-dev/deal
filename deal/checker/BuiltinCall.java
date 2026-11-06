package deal.checker;

import deal.types.Type;
import java.util.List;

/** Fixed-signature, direct-only builtin namespace calls. */
public enum BuiltinCall {
    CONSOLE_LOG("console", "log", List.of(Type.String.INSTANCE), Type.Void.INSTANCE),
    CONSOLE_ERROR("console", "error", List.of(Type.String.INSTANCE), Type.Void.INSTANCE),
    MATH_FLOOR("Math", "floor", List.of(Type.Number.INSTANCE), Type.Number.INSTANCE),
    MATH_CEIL("Math", "ceil", List.of(Type.Number.INSTANCE), Type.Number.INSTANCE),
    MATH_SQRT("Math", "sqrt", List.of(Type.Number.INSTANCE), Type.Number.INSTANCE),
    MATH_ABS_NUMBER("Math", "abs", List.of(Type.Number.INSTANCE), Type.Number.INSTANCE),
    TIME_NOW_MILLIS("Date", "now", List.of(), Type.Number.INSTANCE);

    private final String namespace;
    private final String member;
    private final Type.Func signature;

    BuiltinCall(String namespace, String member, List<Type> arguments, Type result) {
        this.namespace = namespace;
        this.member = member;
        this.signature = new Type.Func(arguments, result);
    }

    public Type.Func signature() { return signature; }

    public static BuiltinCall resolve(String namespace, String member) {
        for (BuiltinCall call : values()) {
            if (call.namespace.equals(namespace) && call.member.equals(member)) return call;
        }
        return null;
    }
}
