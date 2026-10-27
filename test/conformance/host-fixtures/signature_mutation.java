final class HostSignature_mutation {
  public static Object replaceSignature(Object holder, String descriptor) {
    Object value = ((deal.codegen.jvm.JvmRuntime.Table) holder).read("value");
    if (!(value instanceof deal.codegen.jvm.JvmRuntime.FunctionValue function)) {
      return Boolean.FALSE;
    }
    // Deliberately hostile ingress: keep the admitted object and executable
    // body, invalidate only its carried descriptor. No production API is added.
    try {
      var field = deal.codegen.jvm.JvmRuntime.FunctionValue.class.getDeclaredField("spec");
      field.setAccessible(true);
      field.set(function, descriptor);
      return Boolean.valueOf(descriptor.equals(function.spec));
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Cannot invalidate fixture callable", failure);
    }
  }
}
