# Standard Library

Keep declarations/implementations/shared algorithms equivalent. Verify binary64 math, Unicode, failures and JSON roundtrips across targets. Preserve array descriptors/class presence/order/identity without hooks. Test deployed wrappers separately. Finite JSON numbers use ECMAScript Number::toString text; deployed Lua and emitted encoders share deal.runtime.number_text, including unmarked raw numeric leaves. Records share generic JSON rules; declared fields retain order/decoding descriptors. Synchronize code/policy.
