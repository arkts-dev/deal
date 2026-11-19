package deal.checker;

/** Shared wording for repeated rules; producers own applicability and evidence. */
final class RuleGuidance {
    private RuleGuidance() {}

    private static final String EXTRACTION =
            "An expected type does not extract an unknown value; choose a checked assertion or supported binding narrowing. ";
    private static final String PRESENCE =
            "Presence alone does not distinguish a missing key from a present undefined value or narrow its type. ";
    private static final String NARROWING_RULE =
            "docs/spec-v1.6.md#narrowing-and-checked-assertions";

    static String rawRecordReadNote() {
        return "This raw Record read has type unknown. " + EXTRACTION + PRESENCE;
    }

    static String unknownRead() {
        return EXTRACTION
                + "Direct typeof tests narrow undefined/boolean/number/string; nominal instanceof tests narrow to the selected class. "
                + "typeof object/function does not infer a structural type. " + PRESENCE
                + "See " + NARROWING_RULE + " and #reading-and-writing-fields; schematic example: "
                + "if (typeof value === \"string\") { consume(value); }. Choose the intended consumer contract explicitly.";
    }

    static String nullishReceiver() {
        return "Establish a non-nullish receiver with a supported guard or choose optional chaining when skipped access is intended. "
                + PRESENCE + "See " + NARROWING_RULE + " and #nullish-coalescing-and-optional-chaining; "
                + "schematic example: if (value !== null && value !== undefined) { consume(value); }. "
                + "Choose the intended guard or skipped-access behavior explicitly. Guard each admitted nullish member; "
                + "optional access deliberately adds undefined for skipped non-void results.";
    }

    static String recordKey() {
        return "Choose an intended string key; no implicit key conversion is performed.";
    }
}
