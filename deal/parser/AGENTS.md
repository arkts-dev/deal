# Parser

Parse specification grammar with exact UTF-16 spans and deterministic recovery.
Keep checker/backend policy outside parsing; add focused positive/negative fixtures.

- Comments are whitespace, including inside templates; retain their line breaks
  for grammar restrictions. Manifests alone select versions; no directive metadata.
- Preserve identifier token spans beside declaration spans. Object keys may be
  decoded static names or computed expressions; typing decides Record/class
  legality. Keyword properties are legal; async/as/from/of are contextual names.
- Function/async-function expression statements require parentheses. Nonempty
  call/parameter/type lists admit one trailing comma, not holes/comma-only lists.
- Arrows require parenthesized parameters and explicit results. Expression
  parameters may omit annotations for contextual checking; declarations may not.
  Recover malformed annotation tokens locally without treating them as omitted
  contextual parameters or consuming later list elements.
  Preserve block/expression forms for void checks. No line break after async or
  before =>, including comment-contained breaks. Unannotated arrow lookahead must
  recognize a complete result annotation plus =>, not a ternary colon.
- Coalescing is left-associated over equality; reject unparenthesized &&/|| mixing.
  Ternary binds above assignment/below short-circuit, with assignment arms and right
  association. Optional postfix guards and completed wrappers retain chain ownership;
  parentheses end chains. Chains cannot be assignment targets; deletion keeps its
  terminal target.
- Decode validated template/string escapes once while retaining physical spans.
  Remove numeric separators only after lexing; radix integers round once without
  fixed-width overflow.
- Classes contain typed fields/presence only; reject constructor names and field
  initializers (E1007 at =), recovering without initializer AST nodes.
