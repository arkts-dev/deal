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

    static java.util.List<String> assignment(deal.types.Type target, deal.types.Type actual) {
        var advice = new java.util.ArrayList<String>();
        if (actual instanceof deal.types.Type.Unknown) advice.add(unknownRead());
        if (actual instanceof deal.types.Type.Null || actual instanceof deal.types.Type.Undefined
                || actual instanceof deal.types.Type.Nullable)
            advice.add("Handle each remaining null/undefined member with an explicit guard or intentional fallback; presence alone does not narrow values. Do not invent a default or widen the declared contract implicitly.");
        var targetBase = target instanceof deal.types.Type.Nullable n ? n.inner() : target;
        var actualBase = actual instanceof deal.types.Type.Nullable n ? n.inner() : actual;
        if (targetBase instanceof deal.types.Type.Array to && actualBase instanceof deal.types.Type.Array from) {
            if (!to.readonly() && from.readonly())
                advice.add("A readonly array view cannot become writable; retain readonly in the receiving contract or create an intentional mutable copy.");
            else advice.add("Writable arrays are invariant in their element type. Choose a compatible readonly view for widening, or construct a fresh array under the intended element annotation; an assertion does not retag existing storage.");
        }
        if (targetBase instanceof deal.types.Type.Class && actualBase instanceof deal.types.Type.Table
                || targetBase instanceof deal.types.Type.Table && actualBase instanceof deal.types.Type.Class)
            advice.add("Classes and Record<string, unknown> do not implicitly convert. Construct the intended target explicitly with its required fields; a checked assertion is not a conversion.");
        if (targetBase instanceof deal.types.Type.Func && actualBase instanceof deal.types.Type.Func)
            advice.add("Function compatibility requires matching async status, identical results and corresponding parameter types, with no more source parameters than target parameters. Choose an explicit wrapper if behavior must adapt.");
        if (advice.isEmpty()) advice.add("Supply a value compatible with the declared type; no coercion, placeholder or signature widening is inferred.");
        return java.util.List.copyOf(advice);
    }

    static String recordKey() {
        return "Choose an intended string key; no implicit key conversion is performed.";
    }
}
