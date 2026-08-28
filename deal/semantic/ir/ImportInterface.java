package deal.semantic.ir;

import java.util.Objects;

/**
 * One import entry of an {@link ExternalModuleInterface} (parent canonical
 * surfaces; foundation F2): the raw specifier and the resolved module
 * identity from the orchestrator's resolved imports. Pure immutable data
 * of {@code deal.semantic-interface/1}.
 *
 * @param specifier      the raw import specifier; non-null
 * @param resolvedModule the resolved imported module identity; non-null
 */
public record ImportInterface(String specifier, ModuleId resolvedModule) {

    public ImportInterface {
        Objects.requireNonNull(specifier, "specifier must not be null");
        Objects.requireNonNull(resolvedModule, "resolvedModule must not be null");
    }
}
