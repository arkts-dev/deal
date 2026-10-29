# LuaJIT Backend

LuaSemanticEmitter renders validated IR, preserving ABI, evaluation order, host loading, async completion, errors and origins. Verify Oracle parity through luajit; never restore AST emission.

## Carriers and boundaries

Share private `__dealCarrierKeys` and undefined sentinel across deployed/emitted runtimes. User string keys cannot select or forge carrier identity, presence, length or order. Normalize host shapes only at ingress; producer defects remain fail-closed.

Keep canonical descriptors separate from internal representations and JSON-plan/diagnostic tokens: canonical record translates explicitly to table where required, never through a descriptor alias. Persistent array descriptors use canonical text, including independent nullish flags and async callable identity; prefer the recorded canonical trailer over reconstruction.

Null and undefined admission are independent. Normalize missing to undefined; absent optional fields never become null through nullability; array deletion rejects. Recognize sentinels before table dispatch and preserve them before numeric/array/function projection. Strip both nullish prefixes for representation selection. Typed failures use closed semantic kinds; raw host carrier nil remains a distinct protocol token.

ASSERTION preserves identity, reporting E8001 at its origin; ordinary boundaries retain parameter/signature/element failures. Recursively validate unknown ingress Unicode; reject raw functions, threads, userdata, cdata and async handles. Tagged functions require callable payloads and valid canonical signatures. Revalidate complete source/external nominal field plans after mutation using operation-local cycle guards. Trace Error independently of unknown/ref declarations; undefined is not heap-allocated. Render numeric/index atoms as canonical binary64 hexadecimal.

## Host projection

Delegate crossings to the shared inbound/outbound factory helpers. Projection is descriptor-directed at declared positions, value-directed inside dynamic content, memoized per crossing, and leaves failure composition to the recorded boundary cell. Preserve aliases, persistent array descriptors, class identities, presence and order; never inspect an irrelevant deep subtree at a scalar position.

Accept dense host storage as arrays only at declared array positions. Leave sparse/map/non-array carriers for the owning typed check rather than truncating or fabricating arrays. Function adapters project parameters, run parameter-entry cells, and project results through their recorded shape; raw functions do not acquire language identity. Class construction uses supplied fields, never defaults metadata. Async completions project inbound once before their single check. Sync no-result admits void only. Read async-export drivers from the checkout.

## Control flow and JSON

Use explicit branches when selecting possibly false operands; Lua and/or is not a conditional expression. Preserve admitted operands and evaluated-once iterables, live array traversal, fresh captured iteration/catch cells, Error identity and linked causes.

Generic stringify and generated Record-content walkers share ordinary JSON value rules: object undefined/missing/functions omit; array positions and nonfinite numbers substitute null; numeric keys precede other own keys. Declared class fields retain declaration order. Malformed Unicode and cycles reject. Unrepresentable completion roots fail before envelope serialization.

Class frames read private declaration-order/presence storage and detect cycles by original identity without hooks or projection copies. Error exposes code/message only. Publish layouts in both chunk-local and shared private registries so deployed stringify handles either runtime load order. Preserve class storage through async completion projection; decoding attaches exact typed-array descriptors recursively, including empty arrays. Test deployed wrappers separately from emitted preludes.
