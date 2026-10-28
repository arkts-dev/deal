# LuaJIT Backend

Render validated semantic IR through LuaSemanticEmitter; preserve ABI, evaluation order, host loading, async completion, errors and source origins. Verify Oracle parity through real luajit execution; never restore AST emission.

## Carriers and boundaries

Share private `__dealCarrierKeys` and undefined sentinel across deployed/emitted runtimes. User string keys cannot select or forge carrier identity, presence, length or order. Normalize host shapes only at ingress; producer defects remain fail-closed.

Keep canonical descriptors separate from internal representations and JSON-plan/diagnostic tokens: canonical record translates explicitly to table where required, never through a descriptor alias. Persistent array descriptors use canonical text, including independent nullish flags and async callable identity; prefer the recorded canonical trailer over reconstruction.

Null and undefined admission are independent. Normalize missing to undefined; absent optional fields never become null through nullability; array deletion rejects. Recognize sentinels before table dispatch and preserve them before numeric/array/function projection. Strip both nullish prefixes for representation selection. Typed failures use closed semantic kinds; raw host carrier nil remains a distinct protocol token.

ASSERTION preserves identity and reports E8001 at the assertion origin; ordinary boundaries retain their parameter/signature/element failures. Recursively validate unknown ingress Unicode; reject raw functions, threads, userdata, cdata and async handles; retain tagged functions only with callable payload and valid canonical signature. Nominal checks validate complete source/external field plans after mutation, with cycle guards local to the validation operation. Trace Error independently of declared unknown/ref types; undefined is not a heap allocation. Format numeric/index atoms through canonical binary64 hexadecimal rendering.

## Host projection

Delegate crossings to the shared inbound/outbound factory helpers. Projection is descriptor-directed at declared positions, value-directed inside dynamic content, memoized per crossing, and leaves failure composition to the recorded boundary cell. Preserve aliases, persistent array descriptors, class identities, presence and order; never inspect an irrelevant deep subtree at a scalar position.

Accept dense host storage as arrays only at declared array positions. Leave sparse/map/non-array carriers for the owning typed check rather than truncating or fabricating arrays. Function adapters project parameters, run parameter-entry cells, and project results through their recorded shape; raw functions do not acquire language identity. Class construction uses supplied fields only and never consults defaults metadata. Async completions use one inbound projection before the single completion check. Sync no-result admits void only. Read the async-export driver from the source checkout.

## Control flow and JSON

Use explicit branches when selecting possibly false operands; Lua and/or is not a conditional expression. Preserve admitted operands and evaluated-once iterables, live array traversal, fresh captured iteration/catch cells, Error identity and linked causes.

Generic stringify and generated Record-content walkers share ordinary JSON value rules: object undefined/missing/functions omit; array positions and nonfinite numbers substitute null; numeric keys precede other own keys. Declared class fields retain declaration order. Malformed Unicode and cycles reject. Unrepresentable completion roots fail before envelope serialization.

Class frames read private declaration-order/presence storage and detect cycles by original identity without hooks or projection copies. Error exposes code/message only. Publish layouts in both chunk-local and shared private registries so deployed stringify handles either runtime load order. Preserve class storage through async completion projection; decoding attaches exact typed-array descriptors recursively, including empty arrays. Test deployed wrappers separately from emitted preludes.
