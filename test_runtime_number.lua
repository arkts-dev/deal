-- Binary64 runtime boundaries preserve values; integral consumers own their gates.
package.path = "./?.lua;" .. package.path
local rt = require("deal.runtime")
local passed, failed = 0, 0
local function test(name, fn)
  local ok, err = pcall(fn)
  if ok then passed = passed + 1 else failed = failed + 1 end
  print((ok and "PASS: " or "FAIL: ") .. name .. (ok and "" or " -- " .. tostring(err)))
end

for _, value in ipairs({2147483647, -2147483648, 2147483648, -2147483649,
    9007199254740992, 1.5, math.huge, -math.huge, 5e-324}) do
  test("number admission " .. tostring(value), function()
    assert(rt.check_number(value) == value)
    assert(rt.typed_kind(value) == "number")
  end)
end
test("NaN remains NaN", function()
  local value = rt.check_number(0 / 0)
  assert(value ~= value and rt.typed_kind(value) == "number")
end)
test("negative zero survives the boundary", function()
  local value = rt.check_number(-0.0)
  assert(value == 0 and 1 / value == -math.huge)
end)
for _, value in ipairs({"1", true, {}, rt.__NULL}) do
  test("wrong kind " .. type(value), function()
    local ok, err = pcall(rt.check_number, value, "number.deal", 3, 7)
    assert(not ok and err.code == "E8001" and err.message == "expected number")
    assert(err.expected == "number")
  end)
end
test("retired numeric runtime helpers are absent", function()
  for _, name in ipairs({"check_int", "int_convert", "number_convert", "int_add",
      "int_sub", "int_mul", "int_div", "int_neg"}) do
    assert(rt[name] == nil, name)
  end
end)
print("Results: " .. passed .. " passed, " .. failed .. " failed")
if failed > 0 then os.exit(1) end
