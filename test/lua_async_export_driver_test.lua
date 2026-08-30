-- Test suite for deal/lua_async_export_driver.lua
-- Run with: luajit test/lua_async_export_driver_test.lua
--
-- Stages entry chunks and a deployment root (copied deal/runtime.lua and
-- std/*.lua) in a per-run temp directory and spawns the committed driver
-- as:
--
--   luajit deal/lua_async_export_driver.lua <entry> <root> <name> <descriptor>
--
-- Every staged case asserts the envelope JSON, the exit code, and that
-- the envelope line is the last stdout line.

package.path = "./?.lua;" .. package.path
local json = require("std.json")

local MARKER = "DEAL_ASYNC_EXPORT_RESULT:"
local LITERAL_FALLBACK =
    'DEAL_ASYNC_EXPORT_RESULT:{"status":"infrastructure-failure",'
    .. '"reason":"JSON envelope emitter unavailable"}'

-- ===========================================================================
-- Paths, temp dir, file helpers
-- ===========================================================================

local pwd_pipe = assert(io.popen("pwd"))
local pwd = assert(pwd_pipe:read("*l"))
pwd_pipe:close()

local script_path = arg and arg[0] or "test/lua_async_export_driver_test.lua"
local script_dir = script_path:match("^(.*)[/\\][^/\\]*$") or "."
-- Absolute repo root: the spawned child runs with its working directory
-- at the artifact root (the production spawn contract), so every path
-- handed to the child must resolve without the test process's CWD.
local repo_root
if script_dir:sub(1, 1) == "/" then
  repo_root = script_dir .. "/.."
else
  repo_root = pwd .. "/" .. script_dir .. "/.."
end
local driver = repo_root .. "/deal/lua_async_export_driver.lua"

local function shell_quote(s)
  return "'" .. tostring(s):gsub("'", "'\\''") .. "'"
end

local function read_file(path)
  local f = assert(io.open(path, "rb"))
  local data = f:read("*a")
  f:close()
  return data or ""
end

local function write_file(path, data)
  local f = assert(io.open(path, "wb"))
  f:write(data)
  f:close()
end

local function copy_file(src, dst)
  write_file(dst, read_file(src))
end

local function mkdir_p(path)
  local status = os.execute("mkdir -p " .. shell_quote(path))
  if not (status == 0 or status == true) then
    error("mkdir failed for " .. path)
  end
end

local tmp = os.tmpname()
os.remove(tmp)
mkdir_p(tmp)
mkdir_p(tmp .. "/entries")

-- Deployment roots.
local root = tmp .. "/root"
mkdir_p(root .. "/deal")
mkdir_p(root .. "/std")
copy_file(repo_root .. "/deal/runtime.lua", root .. "/deal/runtime.lua")
local STDLIB_MODULES = { "console", "json", "math", "string", "table", "time" }
for _, m in ipairs(STDLIB_MODULES) do
  copy_file(repo_root .. "/std/" .. m .. ".lua", root .. "/std/" .. m .. ".lua")
end

-- A root with the stdlib but no deal/runtime.lua (missing runtime marker).
local bare_root = tmp .. "/bare_root"
mkdir_p(bare_root .. "/std")
for _, m in ipairs(STDLIB_MODULES) do
  copy_file(repo_root .. "/std/" .. m .. ".lua",
      bare_root .. "/std/" .. m .. ".lua")
end

-- A root whose std/json.lua is broken (the JSON emitter is unavailable).
local broken_json_root = tmp .. "/broken_json_root"
mkdir_p(broken_json_root .. "/deal")
mkdir_p(broken_json_root .. "/std")
copy_file(repo_root .. "/deal/runtime.lua",
    broken_json_root .. "/deal/runtime.lua")
for _, m in ipairs(STDLIB_MODULES) do
  copy_file(repo_root .. "/std/" .. m .. ".lua",
      broken_json_root .. "/std/" .. m .. ".lua")
end
write_file(broken_json_root .. "/std/json.lua",
    "this std/json.lua is intentionally broken Lua [[[")

-- ===========================================================================
-- Spawning and envelope helpers
-- ===========================================================================

local function write_entry(name, chunk)
  local path = tmp .. "/entries/" .. name .. ".lua"
  write_file(path, chunk)
  return path
end

--- Spawn the committed driver under real luajit with the given argv
-- (after the driver path). Returns stdout, stderr, exit code.
local function spawn(driver_args)
  local out_file = tmp .. "/stdout.txt"
  local err_file = tmp .. "/stderr.txt"
  local code_file = tmp .. "/exit_code.txt"
  -- The production spawn contract (luajit-async-export-invoker D3):
  -- argv only (no shell), working directory = artifact root. The cd
  -- reproduces that directory contract so ./?.lua in the default
  -- package.path resolves inside the staged deployment root, exactly
  -- like the Java component's ProcessBuilder directory.
  local parts = { "cd", shell_quote(driver_args[2] or tmp),
      "&&", "luajit", shell_quote(driver) }
  for _, a in ipairs(driver_args) do
    parts[#parts + 1] = shell_quote(a)
  end
  local cmd = table.concat(parts, " ")
      .. " > " .. shell_quote(out_file)
      .. " 2> " .. shell_quote(err_file)
      .. "; echo $? > " .. shell_quote(code_file)
  local status = os.execute(cmd)
  if not (status == 0 or status == true) then
    error("failed to spawn driver: " .. cmd)
  end
  return read_file(out_file), read_file(err_file),
      tonumber((read_file(code_file):gsub("%s", "")))
end

local function last_line(s)
  s = s:gsub("\n$", "")
  return s:match("([^\n]*)$") or ""
end

local function marker_count(s)
  local _, n = s:gsub(MARKER, "")
  return n
end

--- Asserts exactly one envelope line, that it is the last stdout line,
-- and returns the parsed envelope JSON plus the raw line.
local function envelope_of(stdout)
  local n = marker_count(stdout)
  if n ~= 1 then
    error("expected exactly one envelope line on stdout, found " .. n
        .. " in: " .. stdout)
  end
  local line = last_line(stdout)
  if line:sub(1, #MARKER) ~= MARKER then
    error("the last stdout line is not the envelope marker line: " .. line)
  end
  return json.parse.f(line:sub(#MARKER + 1)), line
end

-- ===========================================================================
-- Test harness
-- ===========================================================================

local passed = 0
local failed = 0

local function test(name, fn)
  local ok, err = pcall(fn)
  if ok then
    passed = passed + 1
    print("PASS: " .. name)
  else
    failed = failed + 1
    print("FAIL: " .. name .. " -- " .. tostring(err))
  end
end

-- ===========================================================================
-- Staged entry chunk templates (entry-shaped: require deal.runtime, build
-- the exports table of wrappers, return exports; main, when present, runs
-- at chunk end exactly like the emitted chunk-end exports.main.f()).
-- ===========================================================================

local CHUNK_VALUE_NULL = [[
local __rt = require("deal.runtime")
local exports = {}
exports.oracle = __rt.function_("async()->null", function()
  return __rt.async_start(function()
    return __rt.__NULL
  end)
end)
return exports
]]

local CHUNK_VALUE_42 = [[
local __rt = require("deal.runtime")
local exports = {}
exports.oracle = __rt.function_("async()->int", function()
  return __rt.async_start(function()
    return 42
  end)
end)
return exports
]]

local CHUNK_DEAL_ERROR_ORACLE = [[
local __rt = require("deal.runtime")
local exports = {}
exports.oracle = __rt.function_("async()->int", function()
  return __rt.async_start(function()
    error(__rt._err("E8005", "integer division by zero",
        "staged.deal", 7, 19))
  end)
end)
return exports
]]

local CHUNK_DEAL_ERROR_MAIN = [[
local __rt = require("deal.runtime")
local exports = {}
exports.main = __rt.function_("()->null", function()
  error(__rt._err("E8004", "int out of range", "staged.deal", 3, 9))
end)
exports.oracle = __rt.function_("async()->int", function()
  return __rt.async_start(function() return 1 end)
end)
exports.main.f()
return exports
]]

local CHUNK_DEAL_ERROR_NO_LOCATION = [[
local __rt = require("deal.runtime")
local exports = {}
exports.oracle = __rt.function_("async()->int", function()
  return __rt.async_start(function()
    return "not an int"
  end)
end)
return exports
]]

local CHUNK_EMPTY_EXPORTS = [[
local __rt = require("deal.runtime")
local exports = {}
return exports
]]

local CHUNK_SYNC = [[
local __rt = require("deal.runtime")
local exports = {}
exports.oracle = __rt.function_("()->int", function() return 1 end)
return exports
]]

local CHUNK_PARAMETERIZED = [[
local __rt = require("deal.runtime")
local exports = {}
exports.oracle = __rt.function_("async(int)->int", function(x)
  return __rt.async_start(function() return x end)
end)
return exports
]]

local CHUNK_DESCRIPTOR_MISMATCH = [[
local __rt = require("deal.runtime")
local exports = {}
exports.oracle = __rt.function_("async()->string", function()
  return __rt.async_start(function() return "x" end)
end)
return exports
]]

local CHUNK_DUPLICATE = [[
local __rt = require("deal.runtime")
local exports = {}
local w = __rt.function_("async()->int", function()
  return __rt.async_start(function() return 1 end)
end)
exports.oracle = w
exports.alias = w
return exports
]]

local CHUNK_NON_WRAPPER = [[
local __rt = require("deal.runtime")
local exports = {}
exports.oracle = 42
return exports
]]

local CHUNK_NON_OPERATION = [[
local __rt = require("deal.runtime")
local exports = {}
exports.oracle = __rt.function_("async()->int", function() return 42 end)
return exports
]]

local CHUNK_BYTES = [[
local __rt = require("deal.runtime")
local exports = {}
exports.oracle = __rt.function_("async()->bytes", function()
  return __rt.async_start(function()
    return __rt.bytes_new(2)
  end)
end)
return exports
]]

local CHUNK_INTERLEAVING = [[
local __rt = require("deal.runtime")
local console = require("std.console")
console.log.f("user stdout before envelope")
console.error.f("user stderr line")
local exports = {}
exports.oracle = __rt.function_("async()->int", function()
  return __rt.async_start(function() return 42 end)
end)
return exports
]]

-- ===========================================================================
-- Cases
-- ===========================================================================

test("value: async()->null completing __NULL yields the exact null envelope", function()
  local entry = write_entry("value_null", CHUNK_VALUE_NULL)
  local stdout, stderr, code = spawn({ entry, root, "oracle", "null" })
  if code ~= 0 then
    error("expected exit 0, got " .. tostring(code)
        .. " stdout=" .. stdout .. " stderr=" .. stderr)
  end
  local parsed, line = envelope_of(stdout)
  assert(line == MARKER .. '{"status":"value","descriptor":"null","value":"null"}',
      "exact envelope mismatch: " .. line)
  assert(parsed.status == "value")
  assert(parsed.descriptor == "null")
  assert(parsed.value == "null")
end)

test("value: async()->int completing 42 yields the scalar bracket-strip envelope", function()
  local entry = write_entry("value_42", CHUNK_VALUE_42)
  local stdout, stderr, code = spawn({ entry, root, "oracle", "int" })
  if code ~= 0 then
    error("expected exit 0, got " .. tostring(code)
        .. " stdout=" .. stdout .. " stderr=" .. stderr)
  end
  local parsed, line = envelope_of(stdout)
  assert(line == MARKER .. '{"status":"value","descriptor":"int","value":"42"}',
      "exact envelope mismatch: " .. line)
  assert(parsed.status == "value")
  assert(parsed.descriptor == "int")
  assert(parsed.value == "42")
end)

test("deal-error: oracle raising E8005 propagates code/message/location unchanged", function()
  local entry = write_entry("deal_error_oracle", CHUNK_DEAL_ERROR_ORACLE)
  local stdout, stderr, code = spawn({ entry, root, "oracle", "int" })
  if code ~= 0 then
    error("expected exit 0, got " .. tostring(code)
        .. " stdout=" .. stdout .. " stderr=" .. stderr)
  end
  local parsed, _ = envelope_of(stdout)
  assert(parsed.status == "deal-error")
  assert(parsed.code == "E8005")
  assert(parsed.message == "integer division by zero")
  assert(parsed.file == "staged.deal")
  assert(parsed.line == 7)
  assert(parsed.column == 19)
end)

test("deal-error: main raising E8004 propagates code/message/location unchanged", function()
  local entry = write_entry("deal_error_main", CHUNK_DEAL_ERROR_MAIN)
  local stdout, stderr, code = spawn({ entry, root, "oracle", "int" })
  if code ~= 0 then
    error("expected exit 0, got " .. tostring(code)
        .. " stdout=" .. stdout .. " stderr=" .. stderr)
  end
  local parsed, _ = envelope_of(stdout)
  assert(parsed.status == "deal-error")
  assert(parsed.code == "E8004")
  assert(parsed.message == "int out of range")
  assert(parsed.file == "staged.deal")
  assert(parsed.line == 3)
  assert(parsed.column == 9)
end)

test("deal-error: completion-matcher failure omits the absent location fields", function()
  local entry = write_entry("deal_error_no_location", CHUNK_DEAL_ERROR_NO_LOCATION)
  local stdout, stderr, code = spawn({ entry, root, "oracle", "int" })
  if code ~= 0 then
    error("expected exit 0, got " .. tostring(code)
        .. " stdout=" .. stdout .. " stderr=" .. stderr)
  end
  local parsed, _ = envelope_of(stdout)
  assert(parsed.status == "deal-error")
  assert(parsed.code == "E8001")
  assert(parsed.message == "expected int")
  assert(parsed.file == nil, "file must be absent")
  assert(parsed.line == nil, "line must be absent")
  assert(parsed.column == nil, "column must be absent")
end)

local function host_failure_case(name, chunk, descriptor, expected_reason)
  test("host-failure: " .. name .. " returns the runtime's pinned reason", function()
    local entry = write_entry("host_" .. name, chunk)
    local stdout, stderr, code = spawn({ entry, root, "oracle", descriptor })
    if code ~= 0 then
      error("expected exit 0, got " .. tostring(code)
          .. " stdout=" .. stdout .. " stderr=" .. stderr)
    end
    local parsed, _ = envelope_of(stdout)
    assert(parsed.status == "host-failure")
    assert(parsed.reason == expected_reason,
        "reason mismatch: got " .. tostring(parsed.reason))
    assert(parsed.code == nil, "a host failure must never carry a DEAL code")
  end)
end

host_failure_case("missing", CHUNK_EMPTY_EXPORTS, "int",
    "missing export 'oracle'")
host_failure_case("sync", CHUNK_SYNC, "int",
    "export 'oracle' is sync: expected 'async()->int', got '()->int'")
host_failure_case("parameterized", CHUNK_PARAMETERIZED, "int",
    "export 'oracle' is parameterized: expected 'async()->int',"
        .. " got 'async(int)->int'")
host_failure_case("descriptor_mismatch", CHUNK_DESCRIPTOR_MISMATCH, "int",
    "export 'oracle' signature mismatch: expected 'async()->int',"
        .. " got 'async()->string'")
host_failure_case("duplicate", CHUNK_DUPLICATE, "int",
    "duplicate export 'oracle': the same wrapper appears under multiple"
        .. " export keys")
host_failure_case("non_wrapper", CHUNK_NON_WRAPPER, "int",
    "export 'oracle' is not a function wrapper")
host_failure_case("non_operation", CHUNK_NON_OPERATION, "int",
    "export 'oracle' did not produce an async operation")

test("host-failure: a non-canonical return descriptor maps to the runtime's pinned reason", function()
  local entry = write_entry("host_non_canonical_descriptor", CHUNK_EMPTY_EXPORTS)
  local stdout, stderr, code = spawn({ entry, root, "oracle", "int[]" })
  if code ~= 0 then
    error("expected exit 0, got " .. tostring(code)
        .. " stdout=" .. stdout .. " stderr=" .. stderr)
  end
  local parsed, _ = envelope_of(stdout)
  assert(parsed.status == "host-failure")
  assert(parsed.reason == "return descriptor is not a canonical descriptor: int[]",
      "reason mismatch: got " .. tostring(parsed.reason))
  assert(parsed.code == nil)
end)

test("representation-failure: a bytes completion exits 1, never a DEAL code", function()
  local entry = write_entry("bytes", CHUNK_BYTES)
  local stdout, stderr, code = spawn({ entry, root, "oracle", "bytes" })
  if code ~= 1 then
    error("expected exit 1, got " .. tostring(code)
        .. " stdout=" .. stdout .. " stderr=" .. stderr)
  end
  local parsed, _ = envelope_of(stdout)
  assert(parsed.status == "representation-failure")
  assert(parsed.reason == "unsupported type for JSON encoding: bytes",
      "reason mismatch: got " .. tostring(parsed.reason))
  assert(parsed.code == nil, "an encoding failure must never carry a DEAL code")
end)

test("infrastructure-failure: count-wrong argv yields a JSON envelope and exit 1", function()
  local entry = write_entry("argc_too_few", CHUNK_VALUE_42)
  local stdout, stderr, code = spawn({ entry, root })
  if code ~= 1 then
    error("expected exit 1, got " .. tostring(code)
        .. " stdout=" .. stdout .. " stderr=" .. stderr)
  end
  local parsed, _ = envelope_of(stdout)
  assert(parsed.status == "infrastructure-failure")
  assert(type(parsed.reason) == "string" and #parsed.reason > 0)
end)

test("infrastructure-failure: an unloadable entryArtifact exits 1", function()
  local stdout, stderr, code = spawn(
      { tmp .. "/entries/does_not_exist.lua", root, "oracle", "int" })
  if code ~= 1 then
    error("expected exit 1, got " .. tostring(code)
        .. " stdout=" .. stdout .. " stderr=" .. stderr)
  end
  local parsed, _ = envelope_of(stdout)
  assert(parsed.status == "infrastructure-failure")
  assert(type(parsed.reason) == "string" and #parsed.reason > 0)
end)

test("infrastructure-failure: a root missing deal/runtime.lua exits 1 with the fallback envelope", function()
  local entry = write_entry("missing_runtime", CHUNK_VALUE_42)
  local stdout, stderr, code = spawn({ entry, bare_root, "oracle", "int" })
  if code ~= 1 then
    error("expected exit 1, got " .. tostring(code)
        .. " stdout=" .. stdout .. " stderr=" .. stderr)
  end
  local line = last_line(stdout)
  assert(marker_count(stdout) == 1,
      "expected exactly one envelope line, got: " .. stdout)
  assert(line == LITERAL_FALLBACK,
      "expected the literal fallback envelope, got: " .. line)
end)

test("fallback: count-wrong argv with a broken std/json emits the literal fallback line and exits 1", function()
  local entry = write_entry("broken_json_argc", CHUNK_VALUE_42)
  local stdout, stderr, code = spawn(
      { entry, broken_json_root, "oracle", "int", "extra" })
  if code ~= 1 then
    error("expected exit 1, got " .. tostring(code)
        .. " stdout=" .. stdout .. " stderr=" .. stderr)
  end
  local line = last_line(stdout)
  assert(marker_count(stdout) == 1,
      "expected exactly one envelope line, got: " .. stdout)
  assert(line == LITERAL_FALLBACK,
      "expected the literal fallback envelope, got: " .. line)
  assert(line:sub(1, #MARKER) == MARKER,
      "the fallback envelope must carry the exact marker prefix")
end)

test("interleaving: user stdout/stderr output never corrupts the envelope frame", function()
  local entry = write_entry("interleaving", CHUNK_INTERLEAVING)
  local stdout, stderr, code = spawn({ entry, root, "oracle", "int" })
  if code ~= 0 then
    error("expected exit 0, got " .. tostring(code)
        .. " stdout=" .. stdout .. " stderr=" .. stderr)
  end
  assert(stdout:find("user stdout before envelope", 1, true) ~= nil,
      "user stdout output must reach stdout")
  assert(stderr:find("user stderr line", 1, true) ~= nil,
      "user stderr output must reach stderr")
  local parsed, line = envelope_of(stdout)
  assert(line == MARKER .. '{"status":"value","descriptor":"int","value":"42"}',
      "exact envelope mismatch: " .. line)
  assert(parsed.status == "value" and parsed.value == "42")
end)

test("exactly-once: one dofile, one chunk-end main call, one oracle invocation", function()
  local probe = tmp .. "/probe_once.txt"
  local chunk = [[
local __rt = require("deal.runtime")
local probe = assert(io.open("@PROBE@", "a"))
probe:write("load\n")
probe:close()
local exports = {}
exports.main = __rt.function_("()->null", function()
  local p = assert(io.open("@PROBE@", "a"))
  p:write("main\n")
  p:close()
  return __rt.__NULL
end)
exports.oracle = __rt.function_("async()->int", function()
  return __rt.async_start(function()
    local p = assert(io.open("@PROBE@", "a"))
    p:write("oracle\n")
    p:close()
    return 42
  end)
end)
exports.main.f()
return exports
]]
  chunk = chunk:gsub("@PROBE@", function() return probe end)
  local entry = write_entry("exactly_once", chunk)
  local stdout, stderr, code = spawn({ entry, root, "oracle", "int" })
  if code ~= 0 then
    error("expected exit 0, got " .. tostring(code)
        .. " stdout=" .. stdout .. " stderr=" .. stderr)
  end
  local parsed, line = envelope_of(stdout)
  assert(line == MARKER .. '{"status":"value","descriptor":"int","value":"42"}',
      "exact envelope mismatch: " .. line)
  assert(parsed.status == "value" and parsed.value == "42")
  assert(read_file(probe) == "load\nmain\noracle\n",
      "expected one module load, one main call, and one oracle call in the"
          .. " same runtime instance, got: " .. read_file(probe))
end)

-- ===========================================================================
-- Summary
-- ===========================================================================

os.execute("rm -rf " .. shell_quote(tmp))

print("")
if failed > 0 then
  print("FAILED: " .. failed .. " of " .. (passed + failed)
      .. " async export driver tests failed")
  os.exit(1)
end
print("All " .. passed .. " async export driver tests passed")
