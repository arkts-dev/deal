-- Async/await nesting stress test
-- Tests the behavior of deeply nested async/await chains at 50, 100, 150, and 200 levels.
-- Stack overflow at >200 is acceptable (LuaJIT LJ_MAX_XLEVEL limit).

local passed = 0
local failed = 0

local function assert_eq(actual, expected, label)
  if actual == expected then
    passed = passed + 1
    print("  OK: " .. label)
  else
    failed = failed + 1
    print("  FAIL: " .. label .. " — expected " .. tostring(expected) .. ", got " .. tostring(actual))
  end
end

-- Load the DEAL runtime
local __rt = require("deal.runtime")

-- Build a chain of N async functions where each awaits the next.
-- Returns a function that, when called, invokes the outermost async function.
local function build_async_chain(n)
  -- We'll create: async_1 awaits async_2 awaits ... async_n returns n
  -- Each async function is wrapped in __rt.function_ with "async()->int" descriptor

  -- Start with the innermost: async_n() returns n
  local inner_fn = __rt.function_("async()->int", function()
    return __rt.async_start(function()
      return n
    end)
  end)

  -- Build outward: async_i awaits async_{i+1}
  for i = n - 1, 1, -1 do
    local next_fn = inner_fn
    local function make_level(level, next)
      return __rt.function_("async()->int", function()
        return __rt.async_start(function()
          local handle = next.f()
          local result = coroutine.yield(handle)
          return result
        end)
      end)
    end
    inner_fn = make_level(i, next_fn)
  end

  return inner_fn
end

print("=== Async Nesting Stress Test ===")
print()

-- Test at various depths
local depths = {50, 100, 150, 200}

for _, depth in ipairs(depths) do
  print("Testing depth " .. depth .. "...")
  local ok, result = pcall(function()
    local fn = build_async_chain(depth)
    -- Call the outermost async function and collect the result
    -- Since we can't use await from Lua, we need to step through manually
    local handle = fn.f()
    -- The handle should eventually resolve to the value `depth`
    -- We need to pump the async machinery
    -- For now, let's just verify the handle was created without error
    return handle
  end)

  if ok then
    -- Verify the handle has the right shape
    local handle = result
    if type(handle) == "table" and handle.__kind == "async" then
      -- Pump the handle to completion
      local function pump(h)
        if h.__done then
          return h.__result
        end
        __rt.async_step(h)
        if h.__done then
          return h.__result
        end
        -- Should not need multiple steps for synchronous chain
        error("async chain did not complete synchronously at depth " .. depth)
      end

      local ok2, final = pcall(pump, handle)
      if ok2 then
        assert_eq(final, depth, "depth " .. depth .. " returns " .. depth)
      else
        failed = failed + 1
        print("  FAIL: depth " .. depth .. " pump error: " .. tostring(final))
      end
    else
      failed = failed + 1
      print("  FAIL: depth " .. depth .. " did not return async handle, got " .. type(result))
    end
  else
    -- Stack overflow is acceptable at >200
    if depth > 200 then
      passed = passed + 1
      print("  OK: depth " .. depth .. " overflowed as expected (LJ_MAX_XLEVEL limit)")
    else
      failed = failed + 1
      print("  FAIL: depth " .. depth .. " errored: " .. tostring(result))
    end
  end
end

print()
print("=== Nesting Stress Summary ===")
print("Passed: " .. passed .. ", Failed: " .. failed)
if failed > 0 then
  os.exit(1)
end
