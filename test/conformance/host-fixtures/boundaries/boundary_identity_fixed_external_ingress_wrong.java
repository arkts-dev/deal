final class HostProbe {
  public static final String C="@$external/host/probe/C";
    public static Object f() {
        return new deal.codegen.jvm.JvmRuntime.ClassInstance() {
            public String classIdText() { return "@$external/host/probe/C"; }
            public java.util.List<String> fieldNames() { return java.util.List.of("x"); }
            public java.util.List<deal.codegen.jvm.JvmRuntime.ClassField> boundaryFields() {
                return java.util.List.of(new deal.codegen.jvm.JvmRuntime.ClassField("x", "string", true));
            }
            public boolean isPresent(String field) { return field.equals("x"); }
            public Object read(String field) { return field.equals("x") ? 5.0 : deal.codegen.jvm.JvmRuntime.MISSING; }
            public void write(String field, Object value) { throw new UnsupportedOperationException(); }
            public void delete(String field) { throw new UnsupportedOperationException(); }
        };
    }
}
