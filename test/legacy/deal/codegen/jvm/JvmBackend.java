package deal.codegen.jvm;

import deal.types.Type;

public final class JvmBackend {

    public static String classNameFor(String modulePath) {
        return JvmNames.classNameFor(modulePath);
    }

    public static String javaName(String dealIdentifier) {
        return JvmNames.javaName(dealIdentifier);
    }

    public static String escapedIdentifier(String text) {
        return JvmNames.escapedIdentifier(text);
    }

    public static String typeDescriptor(Type t) {
        return JvmNames.typeDescriptor(t);
    }

}
