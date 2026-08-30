-- ===========================================================================
-- DEAL v1.2 production async-export host driver (luajit-async-export-invoker
-- D1/D4-D9 — the driver half of the marked-line envelope protocol).
--
-- The production Java component LuaJitAsyncExportInvoker materializes this
-- committed distribution resource into a per-invocation temp file and
-- spawns it as:
--
--   luajit <driver> <entryArtifact> <artifactRoot> <exportName> <returnDescriptor>
--
--   arg[1] entryArtifact     path of the compiled entry chunk
--   arg[2] artifactRoot      deployment root with deal/runtime.lua and std/*.lua
--   arg[3] exportName        passed to the runtime entry verbatim
--   arg[4] returnDescriptor  byte-exact canonical descriptor, verbatim
--
-- This driver owns only chunk loading, the runtime-entry call, completion
-- encoding, error classification, and the marked-line envelope. It owns no
-- spawn, no process management, no deadline, and no containment (D3).
--
-- Behavior, exactly once per process:
--   * package.path is set to exactly
--       artifactRoot .. "/?.lua;" .. artifactRoot .. "/std/?.lua;"
--       .. package.path
--     so deal.runtime, generated module imports, stdlib imports, and
--     staged host implementations resolve uniformly (D5).
--   * the entry chunk runs exactly once via dofile(entryArtifact), which
--     executes the module initialization and the chunk-end
--     exports.main.f() together (D4); this driver never calls main itself
--     and never re-loads the chunk.
--   * __rt.invoke_async_export(exports, exportName, returnDescriptor) is
--     called once with the dofile-published exports table and the argv
--     name/descriptor verbatim (D9); the driver performs no descriptor or
--     export-shape pre-validation, so a non-canonical return descriptor
--     maps to the runtime's pinned host-failure reason, never a
--     driver-side hard failure.
--   * the checked completion is encoded as JSON text over the
--     JSON-encodable surface (D7): tables via json.stringify.f(value);
--     scalars via json.stringify.f({value}) with the surrounding array
--     brackets stripped; __NULL encodes null; numbers encode %.17g.
--   * exactly one stdout line DEAL_ASYNC_EXPORT_RESULT:<json> is emitted
--     as the last write of the process (D6); exit 0 for value /
--     deal-error / host-failure, exit 1 for representation-failure /
--     infrastructure-failure.
--
-- Error classification (D8), one xpcall over require + dofile +
-- invoke_async_export + completion encoding:
--   (1) table with __hostInvocationFailure == true  -> host-failure
--       envelope, reason = the runtime's pinned message verbatim, exit 0;
--   (2) table with a non-nil string code            -> deal-error
--       envelope with code/message/file/line/column byte-for-byte and
--       absent location fields omitted, exit 0;
--   (3) anything else raised by dofile/invoke       -> infrastructure-
--       failure envelope, exit 1;
--   (4) any error raised by the completion-encoding step, regardless of
--       its shape (the encoder raises DEAL-shaped errors that are not
--       operation errors)                           -> representation-
--       failure envelope, exit 1.
--
-- When the JSON emitter itself is unavailable (broken/missing std/json)
-- or envelope emission fails, a literal fallback envelope line (still
-- prefixed DEAL_ASYNC_EXPORT_RESULT:) is emitted and the process exits 1
-- — a hard failure at the Java side either way.
-- ===========================================================================

local arg = arg or {}

-- Literal fallback envelope (D6): emitted when the JSON emitter is
-- unavailable or fails on the envelope itself. It is a complete
-- infrastructure-failure envelope, so the Java side maps it to a hard
-- failure exactly like any JSON-encoded failure status.
local LITERAL_FALLBACK =
    'DEAL_ASYNC_EXPORT_RESULT:{"status":"infrastructure-failure",'
    .. '"reason":"JSON envelope emitter unavailable"}'

-- The JSON emitter, loaded from the deployment root's std/json.lua
-- through a pcall; nil when the module is missing or broken.
local json = nil

--- Envelope emission (D6): exactly one stdout line, flushed, then exit.
-- The line is the last stdout write of the process, so user
-- console.log/console.error output interleaving on both streams never
-- corrupts the frame.
local function emit(fields, exitCode)
  local line = nil
  if json ~= nil then
    local ok, text = pcall(function()
      return json.stringify.f(fields)
    end)
    if ok and type(text) == "string" then
      line = "DEAL_ASYNC_EXPORT_RESULT:" .. text
    end
  end
  if line == nil then
    -- Envelope-emission failure: the intended status and reason still
    -- reach stderr for diagnosis; stdout carries the literal fallback
    -- and the process exits hard regardless of the intended status.
    io.stderr:write("deal async export driver: envelope emission failed"
        .. " (status=" .. tostring(fields and fields.status)
        .. ", reason=" .. tostring(fields and fields.reason) .. ")\n")
    line = LITERAL_FALLBACK
    exitCode = 1
  end
  io.write(line, "\n")
  io.flush()
  os.exit(exitCode)
end

-- Configure package.path and load the JSON emitter from the artifact
-- root before the argv guard, so a count-wrong invocation still reports
-- through a real JSON envelope when the root's stdlib is healthy and
-- through the literal fallback when it is not. package.path is set
-- exactly once from the artifact-root argument (D5).
if type(arg) == "table" and #arg >= 2 and type(arg[2]) == "string" then
  local artifactRoot = arg[2]
  package.path = artifactRoot .. "/?.lua;"
      .. artifactRoot .. "/std/?.lua;" .. package.path
  local ok, mod = pcall(require, "std.json")
  if ok and type(mod) == "table" and mod.stringify ~= nil then
    json = mod
  end
end

-- argv contract: exactly four arguments, nothing else.
if #arg ~= 4 then
  emit({
    status = "infrastructure-failure",
    reason = "expected exactly 4 arguments (entryArtifact, artifactRoot,"
        .. " exportName, returnDescriptor), got " .. #arg,
  }, 1)
end

local entryArtifact = arg[1]
local exportName = arg[3]
local returnDescriptor = arg[4]

--- Completion encoding (D7). Any error raised here — including the
-- DEAL-shaped E8001 errors the encoder raises for bytes/cdata, function
-- wrappers, and unsupported types — is classified representation-failure
-- by the phase gate in the handler below, never a DEAL code.
local function encode_completion(value)
  if json == nil then
    error({ message = "JSON envelope emitter unavailable" }, 0)
  end
  if type(value) == "table" then
    return json.stringify.f(value)
  end
  -- Scalar path: wrap in a one-element array and strip the surrounding
  -- brackets, leaving the exact encoded text (D7).
  local wrapped = json.stringify.f({ value })
  if type(wrapped) ~= "string" or #wrapped < 2
      or string.sub(wrapped, 1, 1) ~= "["
      or string.sub(wrapped, -1) ~= "]" then
    error({ message =
        "scalar completion encoding produced a malformed array frame" }, 0)
  end
  return string.sub(wrapped, 2, -2)
end

-- One protected execution: runtime require, exactly one dofile of the
-- entry chunk (module initialization plus the chunk-end main call), one
-- runtime-entry call, then completion encoding. The phase flag
-- distinguishes operation errors from encoding errors in the handler.
local phase = "execute"

local ok, payload = xpcall(function()
  local __rt = require("deal.runtime")
  local exports = dofile(entryArtifact)
  local value = __rt.invoke_async_export(exports, exportName,
      returnDescriptor)
  phase = "encode"
  return encode_completion(value)
end, function(e)
  return e
end)

if ok then
  emit({ status = "value", descriptor = returnDescriptor, value = payload },
      0)
end

-- Error classification (D8), in the pinned order.
if phase == "encode" then
  emit({
    status = "representation-failure",
    reason = (type(payload) == "table" and payload.message ~= nil)
        and tostring(payload.message) or tostring(payload),
  }, 1)
elseif type(payload) == "table"
    and payload.__hostInvocationFailure == true then
  emit({
    status = "host-failure",
    reason = type(payload.message) == "string" and payload.message
        or tostring(payload),
  }, 0)
elseif type(payload) == "table" and type(payload.code) == "string" then
  local fields = {
    status = "deal-error",
    code = payload.code,
    message = payload.message,
  }
  if payload.file ~= nil then fields.file = payload.file end
  if payload.line ~= nil then fields.line = payload.line end
  if payload.column ~= nil then fields.column = payload.column end
  emit(fields, 0)
else
  emit({
    status = "infrastructure-failure",
    reason = tostring(payload),
  }, 1)
end
