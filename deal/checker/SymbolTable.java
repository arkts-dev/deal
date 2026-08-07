package deal.checker;

import java.util.HashMap;
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
    private final Map<String, Symbol> symbols = new HashMap<>();

    public SymbolTable() {
        this.parent = null;
    }

    public SymbolTable(SymbolTable parent) {
        this.parent = parent;
    }

    /**
     * Returns the parent scope, or {@code null} if this is the root (module) scope.
     */
    public SymbolTable parent() {
        return parent;
    }

    /**
     * Defines a symbol in this scope. Throws if the name is already defined
     * in this scope. (Shadowing outer scopes is allowed.)
     *
     * @throws IllegalStateException if the name is already defined in this scope
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
     * @return the symbol, or {@code null} if not found
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
     */
    public Map<String, Symbol> symbols() {
        return new HashMap<>(symbols);
    }
}
