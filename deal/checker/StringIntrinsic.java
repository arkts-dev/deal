package deal.checker;

import deal.types.Type;
import java.util.List;

/** Direct receiver operations selected solely by the checked string type. */
public enum StringIntrinsic {
    SUBSTRING("substring", List.of(Type.Number.INSTANCE, Type.Number.INSTANCE), Type.String.INSTANCE),
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

    public Type.Func signature() { return signature; }

    public static StringIntrinsic forMember(String member) {
        for (StringIntrinsic intrinsic : values()) if (intrinsic.member.equals(member)) return intrinsic;
        return null;
    }
}
