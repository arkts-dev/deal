# LuaJIT Backend

Render validated semantic IR through LuaSemanticEmitter; preserve ABI, evaluation order, host loading, async completion, errors and source origins. Verify Oracle parity through real luajit execution; never restore AST emission.

## Carriers and boundaries

Use process-wide private `__dealCarrierKeys` shared with the deployed runtime. User string keys cannot select or forge carrier identity, presence, length or order. Normalize documented host shapes only at ingress.

Keep canonical descriptors separate from internal representations and JSON-plan/diagnostic tokens: canonical record translates explicitly to table where required, never through a descriptor alias. Persistent array descriptors use canonical text, including independent nullish flags and async callable identity; prefer the recorded canonical trailer over reconstruction.

Null and undefined admission are independent. Normalize missing to undefined; absent optional fields and deleted array slots never become null because an element/field is nullable. Recognize sentinels before table dispatch and preserve them before numeric/array/function projection. Strip both nullish prefixes for representation selection. Typed failures use closed semantic kinds; raw host carrier nil remains a distinct protocol token.

ASSERTION preserves identity and reports E8001 at the assertion origin; ordinary boundaries retain their parameter/signature/element failures. Unknown ingress validates Unicode and rejects raw functions, threads, userdata, cdata and async handles while retaining valid tagged functions. Trace Error independently of declared unknown/ref types; undefined is not a heap allocation. Format numeric/index atoms through canonical binary64 hexadecimal rendering.

## Host projection

Delegate crossings to the shared inbound/outbound factory helpers. Projection is descriptor-directed at declared positions, value-directed inside dynamic content, memoized per crossing, and leaves failure composition to the recorded boundary cell. Preserve aliases, persistent array descriptors, class identities, presence and order; never inspect an irrelevant deep subtree at a scalar position.

Accept dense host storage as arrays only at declared array positions. Leave sparse/map/non-array carriers for the owning typed check rather than truncating or fabricating arrays. Function adapters project parameters, run parameter-entry cells, and project results through their recorded shape; raw functions do not acquire language identity. Class construction uses supplied fields only and never consults defaults metadata. Async completions use one inbound projection before the single completion check. Sync no-result admits void only. Read the async-export driver from the source checkout.

## Control flow and JSON

Use explicit branches when selecting possibly false operands; Lua and/or is not a conditional expression. Preserve admitted operands and evaluated-once iterables, live array traversal, fresh captured iteration/catch cells, Error identity and linked causes.

Generic stringify, declared-number encoding and dynamic Record-content walkers have different contracts. Generic object undefined/functions omit; arrays and nonfinite numbers substitute null. Declared numbers use null for nonfinite values; dynamic Record walkers reject unsupported leaves, including undefined/missing, at their own paths. Unrepresentable completion roots fail before envelope serialization.

Class frames read private declaration-order/presence storage and detect cycles by original identity without hooks or projection copies. Error exposes code/message only. Publish layouts in both chunk-local and shared private registries so deployed stringify handles either runtime load order. Preserve class storage through async completion projection; decoding attaches exact typed-array descriptors recursively, including empty arrays. Test deployed wrappers separately from emitted preludes.
