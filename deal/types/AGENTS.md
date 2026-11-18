# Types

Define canonical target-neutral types, independent of runtime representation.
Enforce data-position legality at construction: void is result-only; functions
returning void remain data. Preserve the Error recovery sentinel for diagnosed
source errors and snapshot function parameter lists immutably.

Keep equality, invariance, nullability and function rules consistent with checker,
lowering and boundaries. Nominal identity names defining files; bound provider keys
distinguish declarations and never permit retagging. Unbound descriptor/builtin
carriers do not introduce another declaration. Delegate text to descriptor services.

`Types.nullableOrSelf` canonicalizes T|null idempotently, including null itself;
optional null fields read null|undefined without constructing Nullable<Null>.
