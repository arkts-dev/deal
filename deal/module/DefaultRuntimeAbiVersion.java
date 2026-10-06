package deal.module;

import java.util.Objects;

public record DefaultRuntimeAbiVersion(String version) {

    /** The initial runtime plan/evaluator ABI version. */
    public static final DefaultRuntimeAbiVersion CURRENT =
        new DefaultRuntimeAbiVersion("1");

    public DefaultRuntimeAbiVersion {
        Objects.requireNonNull(version, "version");
        if (version.isEmpty()) {
            throw new IllegalArgumentException("version must not be empty");
        }
    }
}
