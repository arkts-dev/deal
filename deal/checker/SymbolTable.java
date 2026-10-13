package deal.checker;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hierarchical symbol table with lexical scoping.
 *
 * <p>Each scope (module, block, function) has a {@code Map<String, Symbol>}.
 * Resolution walks the scope chain from innermost to outermost.
 * The module scope is the root (parent == null).</p>
 */
public final class SymbolTable {

    private final SymbolTable parent;
    // Preserve definition order across JVM restarts and JDK versions.
    private final Map<String, Symbol> symbols = new LinkedHashMap<>();

    public SymbolTable() {
        this.parent = null;
    }

    public SymbolTable(SymbolTable parent) {
        this.parent = parent;
    }

    public SymbolTable parent() {
        return parent;
    }

    /**
     * Defines a symbol in this scope. Throws if the name is already defined
     * in this scope. (Shadowing outer scopes is allowed.)
     *
     */
    public void define(String name, Symbol symbol) {
        if (symbols.containsKey(name)) {
            throw new IllegalStateException(
                "Redeclaration of '" + name + "' in the same scope");
        }
        symbols.put(name, symbol);
    }

    /**
     * Defines a symbol, silently overwriting any existing binding in this scope.
     * Use only for internal adjustments (e.g. narrowing).
     */
    void defineReplace(String name, Symbol symbol) {
        symbols.put(name, symbol);
    }

    /**
     * Removes a symbol from this exact scope. Used for E2006 correction
     * when an import is removed in favor of a declaration.
     */
    public void remove(String name) {
        symbols.remove(name);
    }

    /**
     * Resolves a name by walking the scope chain.
     *
     */
    public Symbol resolve(String name) {
        Symbol s = symbols.get(name);
        if (s != null) return s;
        if (parent != null) return parent.resolve(name);
        return null;
    }

    /**
     * Resolves a name, but only within this scope (no parent walk).
     */
    public Symbol resolveLocal(String name) {
        return symbols.get(name);
    }

    /**
     * Checks whether a name is defined in this exact scope.
     */
    public boolean containsLocally(String name) {
        return symbols.containsKey(name);
    }

    /**
     * Creates a child scope linked to this one.
     */
    public SymbolTable enterScope() {
        return new SymbolTable(this);
    }

    /**
     * Returns all symbols defined in this scope (for exports, etc.).
     *
     * <p>Returns a defensive copy in {@code define()} insertion order;
     * mutations of the copy never affect this table.</p>
     *
     */
    public Map<String, Symbol> symbols() {
        return new LinkedHashMap<>(symbols);
    }
}
