package deal.codegen;

import java.util.Locale;
import java.util.Optional;

public enum Backend {

    /** Emits Lua 5.1/LuaJIT source via {@code deal.codegen.lua.LuaBackend}. */
    LUAJIT("luajit"),

    JVM("jvm"),

    JS("js");

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
     * and {@code "js"} for {@link #JS}; any other value (including
     * {@code null}) yields empty.
     */
    public static Optional<Backend> fromCliName(String name) {
        if (name == null) return Optional.empty();
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "lua", "luajit" -> Optional.of(LUAJIT);
            case "jvm" -> Optional.of(JVM);
            case "js" -> Optional.of(JS);
            default -> Optional.empty();
        };
    }
}
