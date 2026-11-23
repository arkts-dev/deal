package deal.diagnostics;

import java.util.Objects;

/** A language difference established at one occurrence by its producer, never by code or prose. */
public record LanguageDifference(Reference reference, Kind kind, String feature) {
    public enum Reference { TYPESCRIPT }
    public enum Kind { UNSUPPORTED_SYNTAX, UNSUPPORTED_API, DIFFERENT_SEMANTICS }

    public LanguageDifference {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(kind, "kind");
        if (feature == null || !feature.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*"))
            throw new IllegalArgumentException("Language difference requires a stable feature name");
    }

    public static LanguageDifference syntax(String feature) {
        return new LanguageDifference(Reference.TYPESCRIPT, Kind.UNSUPPORTED_SYNTAX, feature);
    }

    public static LanguageDifference semantics(String feature) {
        return new LanguageDifference(Reference.TYPESCRIPT, Kind.DIFFERENT_SEMANTICS, feature);
    }

    public static LanguageDifference api(String feature) {
        return new LanguageDifference(Reference.TYPESCRIPT, Kind.UNSUPPORTED_API, feature);
    }
}
