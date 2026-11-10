# LuaJIT Backend

LuaSemanticEmitter renders validated IR, preserving ABI, evaluation order, host loading, async completion, errors and origins. Verify Oracle parity through luajit; never restore AST emission.

## Carriers and boundaries

Share private `__dealCarrierKeys` and undefined sentinel across deployed/emitted runtimes. User string keys cannot select or forge carrier identity, presence, length or order. Normalize admitted host strings, keys and graph data to UTF-16-unit storage on ingress; transport paired units as scalar UTF-8 on egress. Preserve live nodes, cycles and private maps. Producer defects remain fail-closed.

Keep canonical descriptors separate from internal representations and JSON-plan/diagnostic tokens: canonical record translates explicitly to table where required, never through a descriptor alias. Persistent array descriptors use canonical text, including independent nullish flags and async callable identity; prefer the recorded canonical trailer over reconstruction.

Null and undefined admission are independent. Normalize missing to undefined; absent optional fields never become null through nullability; array deletion rejects. Recognize sentinels before dispatch/projection; null comparisons admit null carriers, never undefined/missing. Strip both nullish prefixes for representation selection. Typed failures use closed semantic kinds; raw host carrier nil remains a distinct protocol token.

ASSERTION preserves identity, reporting E8001 at its origin; ordinary boundaries retain parameter/signature/element failures. Recursively validate Record/unknown graphs at every boundary; reject raw functions, threads, userdata, cdata and async handles. Tagged functions require callable payloads and valid canonical signatures. Revalidate complete source/external nominal field plans after mutation using operation-local cycle guards. Trace Error independently of unknown/ref declarations; undefined is not heap-allocated. Render numeric/index atoms as canonical binary64 hexadecimal; trace strings escape UTF-16 surrogate units, including paired units, losslessly.

## Host projection

Delegate crossings to the shared inbound/outbound factory helpers. Projection is descriptor-directed at declared positions, value-directed inside dynamic content, memoized per crossing, and leaves failure composition to the recorded boundary cell. Preserve aliases, persistent array descriptors, class identities, presence and order; raw host-added Record members remain visible through retained aliases. Never inspect an irrelevant deep subtree at a scalar position.

Accept dense host storage as arrays only at declared array positions. Leave sparse/map/non-array carriers for the owning typed check rather than truncating or fabricating arrays. Function adapters project parameters, run parameter-entry cells, and project results through their recorded shape; raw functions do not acquire language identity. Class construction uses supplied fields, never defaults metadata. Async completions project inbound once before their single check. Sync no-result admits void only. Read async-export drivers from the checkout.

## Control flow and JSON

Function carriers protect their own activation slots with balanced save/restore and protected invocation on returns, throws and async drains. Restore cell references without undoing live captured-cell mutations; dynamic invocation cannot rely on static call-site guards.

Object.hasOwn queries private class presence, Error code/message or Record key metadata/raw presence, never a field-value read. Refined dynamic keys must pass their recorded operand boundary before lookup.

Use explicit branches when selecting possibly false operands; Lua and/or is not a conditional expression. Preserve admitted operands and evaluated-once iterables, live array traversal, fresh captured iteration/catch cells, Error identity and linked causes.

Generic stringify and generated Record-content walkers share ordinary JSON value rules: object undefined/missing/functions omit; array positions and nonfinite numbers substitute null; numeric keys precede other own keys. Declared class fields retain declaration order. Malformed Unicode and cycles reject. Unrepresentable completion roots fail before envelope serialization.

Generated JSON parsing delegates grammar, duplicate detection, binary64 conversion and byte-offset failures to the deployed deal/json.lua scanner; keep no duplicate private parser or parsed-tree projection. Re-render its closed failure arm through the artifact's origin/frame authority; pass the chunk's null sentinel explicitly without copying the scanned graph. Target reconstruction selects Record and array shapes by private carrier identity, never Lua's shared table representation. Scalar matching is limited to string/number/boolean; target mismatches fail at the JSON_PARSE origin before later storage admission.

Class frames read private declaration-order/presence storage and detect cycles by original identity without hooks or projection copies. Emitted stringify consumes deployed instances' own private canonical field plans, translating descriptors explicitly; never replace them with same-identity chunk registrations. Validate carried plans as raw dense declaration-order arrays with unique field names and boolean presence flags, without metatable hooks. Recognize deployed missing sentinels in dynamic omission as well as declared presence checks. Error exposes code/message only. Publish layouts in both chunk-local and shared private registries so deployed stringify handles either runtime load order. Preserve class storage through host and async completion projection. When a bridge changes an emitted class into a deployed class, translate its own internal field plan (canonical trailer and required flag) to the deployed canonical descriptor/optional dialect at that transition; an already-deployed instance keeps its exact own plan. Decoding attaches exact typed-array descriptors recursively, including empty arrays. Test deployed algorithms separately from emitted preludes. Public async envelopes and raw hosts use the internal deal/json.lua encoder/target decoder, never std/json function wrappers; stage that runtime resource transactionally.
