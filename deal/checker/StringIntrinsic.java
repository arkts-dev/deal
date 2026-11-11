package deal.checker;

import deal.types.Type;
import java.util.List;

/** Direct receiver operations selected solely by the checked string type. */
public enum StringIntrinsic {
    SUBSTRING("substring", List.of(Type.Number.INSTANCE, Type.Number.INSTANCE), Type.String.INSTANCE),
    SLICE("slice", List.of(Type.Number.INSTANCE, Type.Number.INSTANCE), Type.String.INSTANCE),
    INDEX_OF("indexOf", List.of(Type.String.INSTANCE), Type.Number.INSTANCE),
    LAST_INDEX_OF("lastIndexOf", List.of(Type.String.INSTANCE), Type.Number.INSTANCE),
    INCLUDES("includes", List.of(Type.String.INSTANCE), Type.Boolean.INSTANCE),
    STARTS_WITH("startsWith", List.of(Type.String.INSTANCE), Type.Boolean.INSTANCE),
    ENDS_WITH("endsWith", List.of(Type.String.INSTANCE), Type.Boolean.INSTANCE),
    REPLACE("replace", List.of(Type.String.INSTANCE, Type.String.INSTANCE), Type.String.INSTANCE),
    SPLIT("split", List.of(Type.String.INSTANCE), new Type.Array(Type.String.INSTANCE)),
    TRIM("trim", List.of(), Type.String.INSTANCE);

    private final String member;
    private final Type.Func signature;

    StringIntrinsic(String member, List<Type> arguments, Type result) {
        this.member = member;
        this.signature = new Type.Func(arguments, result);
    }

    public Type.Func signature(int arity) {
        if ((this == SUBSTRING || this == SLICE) && arity == 1)
            return new Type.Func(List.of(Type.Number.INSTANCE), Type.String.INSTANCE);
        if (arity == 2 && (this == INCLUDES || this == STARTS_WITH || this == ENDS_WITH
                || this == INDEX_OF || this == LAST_INDEX_OF))
            return new Type.Func(List.of(Type.String.INSTANCE, Type.Number.INSTANCE), signature.returnType());
        return signature;
    }

    public static StringIntrinsic forMember(String member) {
        for (StringIntrinsic intrinsic : values()) if (intrinsic.member.equals(member)) return intrinsic;
        return null;
    }
}
