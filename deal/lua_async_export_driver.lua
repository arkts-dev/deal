-- ===========================================================================
-- DEAL v1.4 production async-export host driver (luajit-async-export-invoker
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
--   * the entry chunk runs exactly once via dofile(entryArtifact). With
--     the invoker's DEAL_DEFER_MAIN marker the chunk publishes only its
--     surfaces and this driver then drives the chunk's own __dealMain
--     entry, which runs module initialization and the chunk-end main()
--     together (D4); without the marker the chunk already ran both at
--     load and the drive is the state guard's no-op. This driver never
--     calls main itself and never re-loads the chunk.
--   * __rt.invoke_async_export(exports, exportName, returnDescriptor) is
--     called once with the dofile-published exports table and the argv
--     name/descriptor verbatim (D9); the driver performs no descriptor or
--     export-shape pre-validation, so a non-canonical return descriptor
--     maps to the runtime's pinned host-failure reason, never a
--     driver-side hard failure.
--   * the checked completion is encoded as JSON text over the
--     JSON-encodable surface (D7) by the entry artifact's own shared value
--     walk, which the chunk publishes as __dealAsyncExportEncode with
--     the async-export wrapper: array carriers keep their JSON array
--     shape at every depth (an empty array included), table carriers
--     keep their insertion order, the language null renders null, and
--     the walker's closed rejections (function carriers, class
--     instances, cycles, non-finite numbers) are the representation
--     failures. The committed std/json remains the envelope emitter, so
--     the artifact's value algorithm never diverges from its own JSON
--     surface.
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
--       envelope with the code/message, any present position fields, and
--       the expected/actual pair plus ordered frame text
--       byte-for-byte (the runtime half's
--       `message`/file/line/column/expected/actual or the emitted
--       artifact's `m` plus the `file:line:column` origin text in `o`,
--       its `e`/`a` pair and its `f` frames, both mapped to the same
--       envelope fields), exit 0;
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

--- Completion encoding (D7): the entry artifact's own shared JSON text
-- walk (`__dealAsyncExportEncode`, the chunk's JSON_STRINGIFY algorithm
-- over the shared value model — array carriers keep their JSON array
-- shape at every depth, table carriers their insertion order, a cycle is
-- the walker's cycle rejection, and every non-encodable value is the
-- walker's closed JSON_STRINGIFY_UNSUPPORTED rejection). Any error
-- raised here — the DEAL-shaped arm carriers included — is classified
-- representation-failure by the phase gate in the handler below, never a
-- DEAL code.
local function encode_completion(value)
  return __dealAsyncExportEncode(value)
end

-- One protected execution: runtime require, exactly one dofile of the
-- entry chunk (which publishes the module surfaces and, unless the
-- invoker's DEAL_DEFER_MAIN marker is set, runs the chunk-end module
-- initialization plus main call), the deferred-main drive when the
-- chunk exposes the deferred entry, one runtime-entry call, then
-- completion encoding. The phase flag distinguishes operation errors
-- from encoding errors in the handler.
local phase = "execute"

local ok, payload = xpcall(function()
  local __rt = require("deal.runtime")
  local exports = dofile(entryArtifact)
  -- The deferred-main drive: with DEAL_DEFER_MAIN set (the production
  -- invoker always sets it), the chunk published its surfaces without
  -- running module initialization or main, so this protected step runs
  -- both exactly once in the same runtime instance and a DEAL error
  -- raised by either classifies as a deal-error envelope instead of the
  -- chunk's own DEAL_ERROR_CODE terminal (which would exit the process
  -- before the envelope). The emitted chunk's own state guard makes a
  -- repeated drive a no-op, so a chunk that already ran its terminal
  -- stays correct; a chunk without the deferred entry already ran its
  -- initialization and main at load.
  if type(__dealMain) == "function" then
    local __mainOk, __mainErr = __dealMain()
    if not __mainOk then
      error(__mainErr, 0)
    end
  end
  local value = __rt.invoke_async_export(exports, exportName,
      returnDescriptor)
  -- The shared completion encoder the entry artifact publishes: it is
  -- co-emitted with the async-export wrapper, so a value without it is a
  -- non-production artifact — the phase stays "execute", so the hard
  -- failure classifies infrastructure, never a value-representation
  -- mismatch.
  if type(__dealAsyncExportEncode) ~= "function" then
    error("the entry artifact publishes no shared value walk", 0)
  end
  phase = "encode"
  return encode_completion(value)
end, function(e)
  return e
end)

local function errorDetail(err)
  local detail = {code = err.code, message = err.message or err.m}
  local file, line, column = err.file, err.line, err.column
  if file == nil and type(err.o) == "string" then
    local f, l, c = string.match(err.o, "^(.*):(%d+):(%d+)$")
    if f ~= nil then file, line, column = f, tonumber(l), tonumber(c) end
  end
  detail.file, detail.line, detail.column = file, line, column
  detail.expected, detail.actual = err.expected or err.e, err.actual or err.a
  detail.frames = err.frames or err.f
  if err.cause ~= nil then detail.cause = errorDetail(err.cause) end
  return detail
end

if ok then
  emit({ status = "value", descriptor = returnDescriptor, value = payload },
      0)
end

-- Error classification (D8), in the pinned order.
if phase == "encode" then
  -- Both encoder failure shapes: the deployed std/json member carries
  -- `message` (__rt._err), while the artifact's closed failure arms
  -- carry the rendered message in `m` (__failExpr). The reason is the
  -- rendered text either way.
  local reason = nil
  if type(payload) == "table" then
    if payload.message ~= nil then reason = payload.message end
    if reason == nil then reason = payload.m end
  end
  if reason == nil then reason = payload end
  emit({
    status = "representation-failure",
    reason = tostring(reason),
  }, 1)
elseif type(payload) == "table"
    and payload.__hostInvocationFailure == true then
  emit({
    status = "host-failure",
    reason = type(payload.message) == "string" and payload.message
        or tostring(payload),
  }, 0)
elseif type(payload) == "table" and type(payload.code) == "string" then
  -- The two DEAL error shapes of the production path: the deployed
  -- runtime half carries `message` plus optional file/line/column
  -- positions (__rt._err), while the emitted artifact's closed failure
  -- arms carry the rendered message in `m` and the origin text
  -- `file:line:column` in `o` (__failExpr). Both map to the same
  -- envelope fields; a runtime error that already carries positions
  -- keeps them byte-for-byte.
  local fields = {
    status = "deal-error",
    code = payload.code,
    message = payload.message ~= nil and payload.message or payload.m,
  }
  local file, line, column = payload.file, payload.line, payload.column
  if file == nil and type(payload.o) == "string" then
    local oFile, oLine, oColumn = string.match(payload.o, "^(.*):(%d+):(%d+)$")
    if oFile ~= nil then
      file, line, column = oFile, tonumber(oLine), tonumber(oColumn)
    end
  end
  if file ~= nil then fields.file = file end
  if line ~= nil then fields.line = line end
  if column ~= nil then fields.column = column end
  -- The complete ErrorDetail projection: the expected/actual pair and the
  -- ordered frame text travel with code/message/origin. The runtime
  -- half's own shape spells them `expected`/`actual`, the emitted
  -- artifact's closed failure arms `e`/`a`, and the frames `f`; an
  -- absent field stays absent.
  local expected, actual = payload.expected, payload.actual
  if expected == nil then expected = payload.e end
  if actual == nil then actual = payload.a end
  local frames = payload.frames
  if frames == nil then frames = payload.f end
  if expected ~= nil then fields.expected = expected end
  if actual ~= nil then fields.actual = actual end
  if frames ~= nil then fields.frames = frames end
  if payload.cause ~= nil then fields.cause = errorDetail(payload.cause) end
  emit(fields, 0)
else
  emit({
    status = "infrastructure-failure",
    reason = tostring(payload),
  }, 1)
end
