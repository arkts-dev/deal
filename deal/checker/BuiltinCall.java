package deal.checker;

import deal.types.Type;
import java.util.List;

/** Direct-only builtin namespace calls; Array.from specializes its inferred signature. */
public enum BuiltinCall {
    CONSOLE_LOG("console", "log", List.of(Type.String.INSTANCE), Type.Void.INSTANCE),
    CONSOLE_ERROR("console", "error", List.of(Type.String.INSTANCE), Type.Void.INSTANCE),
    MATH_FLOOR("Math", "floor", List.of(Type.Number.INSTANCE), Type.Number.INSTANCE),
    MATH_CEIL("Math", "ceil", List.of(Type.Number.INSTANCE), Type.Number.INSTANCE),
    MATH_SQRT("Math", "sqrt", List.of(Type.Number.INSTANCE), Type.Number.INSTANCE),
    MATH_ABS_NUMBER("Math", "abs", List.of(Type.Number.INSTANCE), Type.Number.INSTANCE),
    TIME_NOW_MILLIS("Date", "now", List.of(), Type.Number.INSTANCE),
    ARRAY_FROM("Array", "from", List.of(Type.Unknown.INSTANCE, Type.Unknown.INSTANCE), Type.Unknown.INSTANCE),
    OBJECT_KEYS("Object", "keys", List.of(Type.Unknown.INSTANCE), new Type.Array(Type.String.INSTANCE)),
    JSON_STRINGIFY("JSON", "stringify", List.of(Type.Unknown.INSTANCE),
        new Type.Nullable(Type.String.INSTANCE, false, true));

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
