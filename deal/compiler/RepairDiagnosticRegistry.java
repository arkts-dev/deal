package deal.compiler;

import deal.diagnostics.DiagnosticCode;
import java.util.*;

/** Versioned diagnostic inventory. Registration is not a promise that a particular scope can repair it. */
public final class RepairDiagnosticRegistry {
    public static final String VERSION = "repair-diagnostics-v1";
    public enum Category { LANGUAGE, PROTOCOL, CONSTRUCTOR, UI_SYNTAX, UI_CONTRACT, RUNTIME }
    private final Map<String, Category> codes;
    private RepairDiagnosticRegistry(Map<String, Category> codes) { this.codes = Map.copyOf(codes); }
    public static RepairDiagnosticRegistry core() {
        var codes = new TreeMap<String, Category>();
        for (DiagnosticCode code : DiagnosticCode.values()) codes.put(code.code(), Category.LANGUAGE);
        for (String code : List.of("CP1001", "CP1002", "CP1003", "CP1004", "CP1005", "CP1006", "CP1007",
                "CP1010", "CP1011", "CP1012", "CP1013", "CP1020", "CP1021", "CP1022", "CP1023",
                "CP1024", "CP1025", "CP1026", "CP1027", "CP1028", "CP1029", "CP1030")) codes.put(code, Category.PROTOCOL);
        for (String code : List.of("CC1001", "CC1002", "CC1003", "CC1004", "CC1005")) codes.put(code, Category.CONSTRUCTOR);
        return new RepairDiagnosticRegistry(codes);
    }
    public RepairDiagnosticRegistry extend(Category category, List<String> additional) {
        var result = new TreeMap<>(codes);
        for (String code : additional) {
            var previous = result.putIfAbsent(code, category);
            if (previous != null && previous != category) throw new IllegalArgumentException("Conflicting diagnostic category: " + code);
        }
        return new RepairDiagnosticRegistry(result);
    }
    public Map<String, Category> codes() { return codes; }
    public boolean contains(String code) { return codes.containsKey(code); }
}
