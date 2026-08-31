-- Host-module fixture for the runtime host-loader unit tests (test_runtime.lua).
-- Returns a raw export table exercised by __rt.load_host end to end:
-- raw functions, pre-wrapped exports, sync null returns, nullable/array
-- returns, async operations, class identity (canonical
-- @$external/host.cfg/<Name> atoms), defaults, and the optional _fields
-- passthrough. v1.2 declares no rest parameters, so the legacy rest
-- entries are removed, not migrated.
--
-- This fixture is a third-party host for unit-testing purposes: it may
-- require deal.runtime for the __NULL sentinel (documented host contract).

local __rt = require("deal.runtime")

local M = {}

-- ()->int — correct raw function.
M.answer = function() return 42 end

-- (int,int)->int — parameter enforcement.
M.add = function(a, b) return a + b end

-- (boolean)->string|null — nullable return; __NULL or a string.
M.find = function(flag)
  if flag then return __rt.__NULL end
  return "found"
end

-- ()->string|null — nullable return violating the declared type.
M.find_bad = function() return 42 end

-- ()->string[] — array return.
M.split = function() return { "a", "b" } end

-- ()->string[] — array return violating the declared type.
M.split_bad = function() return "not an array" end

-- ()->null — sync null return with the sentinel.
M.ping = function() return __rt.__NULL end

-- ()->null — sync null return violating the sentinel contract.
M.ping_bad = function() return "junk" end

-- (?(int)->int)->null — nullable-function parameter.
M.register = function(cb)
  if cb ~= __rt.__NULL then
    cb(41)  -- cb arrives adapted: a plain Lua function
  end
  return __rt.__NULL
end

-- async()->string — async operation.
M.fetch = function()
  return __rt.async_start(function() return "data" end)
end

-- async()->string — async export violating the operation-shape contract.
M.fetch_bad = function() return "not an async op" end

-- ()->int — pre-wrapped with correct sig and conforming behavior.
M.prewrapped_good = { __kind = "function", sig = "()->int", f = function() return 7 end }

-- ()->int — pre-wrapped with matching sig but junk return (E8010 at call).
M.prewrapped_bad = { __kind = "function", sig = "()->int", f = function() return "junk" end }

-- ()->null — pre-wrapped sync null export with sentinel return.
M.prewrapped_null = { __kind = "function", sig = "()->null", f = function() return __rt.__NULL end }

-- ()->null — pre-wrapped sync null export with junk return (E8010 at call).
M.prewrapped_null_bad = { __kind = "function", sig = "()->null", f = function() return 1 end }

-- Pre-wrapped with a missing sig (E8011 at load).
M.prewrapped_nosig = { __kind = "function", f = function() return 1 end }

-- Pre-wrapped with a non-function .f (E8011 at load).
M.prewrapped_badf = { __kind = "function", sig = "()->int", f = 42 }

-- Non-function export for a function descriptor (E8011 at load).
M.not_a_function = 42

-- Class export with defaults and optional _fields.
M.ServerConfig = { __kind = "class", __classname = "@$external/host.cfg/ServerConfig" }
M.ServerConfig_defaults = { port = 80 }
M.ServerConfig_fields = {
  { name = "port", jtype = "int", nullable = false, optional = false }
}

-- Class whose meta identity does not match the declared descriptor.
M.WrongName = { __kind = "class", __classname = "@$external/host.cfg/Other" }

-- Non-class export for a class descriptor (E8011 at load).
M.not_a_class = { plain = true }

-- Class without a _defaults artifact (E8011 at load).
M.NoDefaults = { __kind = "class", __classname = "@$external/host.cfg/NoDefaults" }

-- Class with a non-table _defaults artifact (E8011 at load).
M.BadDefaults = { __kind = "class", __classname = "@$external/host.cfg/BadDefaults" }
M.BadDefaults_defaults = 42

-- Class with a present-but-non-table _fields artifact (E8011 at load).
M.BadFields = { __kind = "class", __classname = "@$external/host.cfg/BadFields" }
M.BadFields_defaults = {}
M.BadFields_fields = 42

-- Class without _fields (absence tolerated).
M.NoFields = { __kind = "class", __classname = "@$external/host.cfg/NoFields" }
M.NoFields_defaults = {}

-- Class tagged with the retired dotted emission shape: the canonical
-- declared descriptor never byte-matches it (E8011 identity mismatch —
-- the dedicated dotted-negative pin).
M.DottedLegacy = { __kind = "class", __classname = "@host.cfg/DottedLegacy" }
M.DottedLegacy_defaults = {}

-- Extra export — must be structurally dropped by the loader.
M.extra_export = 123

return M
