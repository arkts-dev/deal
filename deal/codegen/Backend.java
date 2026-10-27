package deal.codegen;

import java.util.Locale;
import java.util.Optional;

public enum Backend {

    /** Emits Lua 5.1/LuaJIT project artifacts from validated semantic IR. */
    LUAJIT("luajit"),

    JVM("jvm"),

    JS("js"),

    /**
     * Emits one deterministic textual LLVM module and stages the C11
     * runtime deployment set ({@code llvm-native-backend-integration}
     * D1/D2/D6). The target consumes the same validated lowered project as
     * the other production arms and adds no {@code deal.semantic.Target}
     * member and no capability route.
     */
    LLVM("llvm");

    private final String cliName;

    Backend(String cliName) {
        this.cliName = cliName;
    }

    /** Canonical name accepted on the CLI and in {@code deal.json}. */
    public String cliName() {
        return cliName;
    }

    /**
     * Parses a CLI/manifest backend name. Accepts {@code "lua"} and
     * {@code "luajit"} for {@link #LUAJIT}, {@code "jvm"} for {@link #JVM},
     * {@code "js"} for {@link #JS}, and {@code "llvm"} for {@link #LLVM};
     * any other value (including {@code null}) yields empty.
     */
    public static Optional<Backend> fromCliName(String name) {
        if (name == null) return Optional.empty();
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "lua", "luajit" -> Optional.of(LUAJIT);
            case "jvm" -> Optional.of(JVM);
            case "js" -> Optional.of(JS);
            case "llvm" -> Optional.of(LLVM);
            default -> Optional.empty();
        };
    }
}
