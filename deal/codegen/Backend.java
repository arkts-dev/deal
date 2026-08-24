package deal.codegen;

import java.util.Locale;
import java.util.Optional;

/**
 * Backend-selection seam for the DEAL compiler (ISSUE-0091).
 *
 * <p>The compilation pipeline is backend-neutral through type checking; only
 * phase 4 (code generation) is backend-specific. {@link Backend} is the single
 * enum both the CLI ({@code --backend <name>}), the project manifest
 * ({@code deal.json}'s {@code "backend"} field), and
 * {@code CompilationOrchestrator} use to select the emitter.
 *
 * <p>LuaJIT remains the default backend: every existing constructor and CLI
 * invocation without an explicit backend name selects {@link #LUAJIT}.
 */
public enum Backend {

    /** Emits Lua 5.1/LuaJIT source via {@code deal.codegen.lua.LuaBackend}. */
    LUAJIT("luajit"),

    /** Emits Java source (compiled to JVM bytecode by {@code javac}) via
     * {@code deal.codegen.jvm.JvmBackend}. Skeleton scope (ISSUE-0091):
     * functions, primitives, control flow, and {@code std/console} output. */
    JVM("jvm"),

    /** JavaScript backend (ISSUE-0195): the {@code "js"} selection name joins
     * the seam now, but the emitter lands with the emitter epic (ISSUE-0191).
     * Until then {@code CompilationOrchestrator} rejects a phase-4 {@code JS}
     * selection with an E6000 diagnostic and emits no artifacts. */
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
