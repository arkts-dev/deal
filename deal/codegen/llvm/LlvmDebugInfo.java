package deal.codegen.llvm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The debug-metadata assembly of the native backend
 * ({@code llvm-native-codegen-cfg-ssa} C8, {@code LlvmDebugInfo}).
 *
 * <p>One {@code DICompileUnit} and one {@code DIFile} per constituent source,
 * one {@code DISubprogram} per generated function carrying both names —
 * {@code DISubprogram.name} is the lowered function's DEAL source name and
 * {@code DISubprogram.linkageName} is the generated {@code F<id>} — and one
 * {@code DILocation} per source-mapped op group, at the op's own one-based
 * line and column.</p>
 *
 * <p>File names are the project-relative display names the production arm
 * supplies, never absolute paths, and the module names no compilation
 * directory of its own: the pinned {@code -fdebug-compilation-dir=.} flag of
 * the toolchain seam keeps the artifact reproducible (D4). Node ids are
 * allocated in creation order, so the metadata bytes are deterministic
 * (C9).</p>
 */
public final class LlvmDebugInfo {

    /** The {@code Debug Info Version} module flag value LLVM 14-16 reads. */
    private static final int DEBUG_INFO_VERSION = 3;

    /** The {@code Dwarf Version} module flag value. */
    private static final int DWARF_VERSION = 5;

    private final List<String> nodes = new ArrayList<>();
    private final Map<String, Integer> files = new LinkedHashMap<>();
    private final Map<String, Integer> units = new LinkedHashMap<>();
    private final Map<String, Integer> subprograms = new LinkedHashMap<>();
    private final Map<String, Integer> locations = new LinkedHashMap<>();
    private final List<Integer> compileUnits = new ArrayList<>();

    /** The node ids of the two module flags ({@code !llvm.module.flags}). */
    private final int dwarfVersionFlag;
    private final int debugInfoVersionFlag;

    public LlvmDebugInfo() {
        // LLVM 14-16 require both module flags for readable DWARF.
        this.dwarfVersionFlag = allocate(
            "!{i32 2, !\"Dwarf Version\", i32 " + DWARF_VERSION + "}");
        this.debugInfoVersionFlag = allocate(
            "!{i32 2, !\"Debug Info Version\", i32 " + DEBUG_INFO_VERSION
                + "}");
    }

    private int allocate(String node) {
        nodes.add(node);
        return nodes.size() - 1;
    }

    /**
     * Declares one constituent source: its {@code DIFile} and its
     * {@code DICompileUnit}, both keyed by the project-relative display name.
     * Repeating one source reuses the same nodes.
     */
    public int compileUnit(String displayName) {
        Objects.requireNonNull(displayName, "displayName must not be null");
        Integer existing = files.get(displayName);
        if (existing != null) {
            return existing;
        }
        int fileId = allocate("!DIFile(filename: "
            + LlvmIrPrinter.metadataString(displayName) + ", directory: \".\")");
        int unitId = allocate("distinct !DICompileUnit(language: DW_LANG_C99,"
            + " file: !" + fileId + ", producer: \"deal\", isOptimized: false,"
            + " runtimeVersion: 0, emissionKind: FullDebug)");
        compileUnits.add(unitId);
        files.put(displayName, fileId);
        units.put(displayName, unitId);
        return fileId;
    }

    /** The {@code DIFile} node id of one constituent source. */
    public int file(String displayName) {
        Integer existing = files.get(displayName);
        if (existing == null) {
            throw new IllegalStateException(
                "no compile unit was declared for " + displayName);
        }
        return existing;
    }

    /**
     * Declares one generated function's {@code DISubprogram}: the DEAL source
     * name as {@code name}, the generated {@code F<id>} as
     * {@code linkageName}, at the function's own source position.
     */
    public int subprogram(String displayName, String sourceName,
                          String linkageName, int line) {
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(linkageName, "linkageName must not be null");
        String key = displayName + "\u0000" + linkageName;
        Integer existing = subprograms.get(key);
        if (existing != null) {
            return existing;
        }
        int fileId = file(displayName);
        int unitId = compileUnitOf(displayName);
        int lineNumber = Math.max(1, line);
        int id = allocate("distinct !DISubprogram(name: "
            + LlvmIrPrinter.metadataString(sourceName == null
                ? linkageName : sourceName)
            + ", linkageName: " + LlvmIrPrinter.metadataString(linkageName)
            + ", scope: !" + fileId + ", file: !" + fileId
            + ", line: " + lineNumber
            + ", type: !" + allocate("!DISubroutineType(types: !"
                + allocate("!{null}") + ")")
            + ", scopeLine: " + lineNumber
            + ", spFlags: DISPFlagLocalToUnit | DISPFlagDefinition"
            + ", unit: !" + unitId + ")");
        subprograms.put(key, id);
        return id;
    }

    private int compileUnitOf(String displayName) {
        Integer existing = units.get(displayName);
        if (existing == null) {
            throw new IllegalStateException(
                "no compile unit was declared for " + displayName);
        }
        return existing;
    }

    /**
     * One {@code DILocation} at the given one-based line and column inside
     * one subprogram; identical locations share one node.
     */
    public int location(int line, int column, int scopeId) {
        int lineNumber = Math.max(1, line);
        int columnNumber = Math.max(1, column);
        String key = scopeId + ":" + lineNumber + ":" + columnNumber;
        Integer existing = locations.get(key);
        if (existing != null) {
            return existing;
        }
        int id = allocate("!DILocation(line: " + lineNumber + ", column: "
            + columnNumber + ", scope: !" + scopeId + ")");
        locations.put(key, id);
        return id;
    }

    /** The {@code !dbg} attachment spelling of one location node. */
    public static String attach(int locationId) {
        return "!dbg !" + locationId;
    }

    /**
     * The complete metadata section: the two named node lists, then every
     * numbered node in id order.
     */
    public List<String> render() {
        List<String> lines = new ArrayList<>();
        lines.add("!llvm.module.flags = !{!" + dwarfVersionFlag + ", !"
            + debugInfoVersionFlag + "}");
        StringBuilder units = new StringBuilder("!llvm.dbg.cu = !{");
        for (int i = 0; i < compileUnits.size(); i++) {
            if (i > 0) {
                units.append(", ");
            }
            units.append('!').append(compileUnits.get(i));
        }
        units.append('}');
        lines.add(units.toString());
        for (int id = 0; id < nodes.size(); id++) {
            lines.add("!" + id + " = " + nodes.get(id));
        }
        return lines;
    }

    /** The number of constituent sources declared so far. */
    public int compileUnitCount() {
        return compileUnits.size();
    }
}
