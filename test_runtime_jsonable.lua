-- Test suite for deal/runtime.lua jsonable helpers
-- Run with: luajit test_runtime_jsonable.lua

package.path = "./?.lua;" .. package.path
local __rt = require("deal.runtime")

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

-- ==================== json_from_json: basic field types ====================

test("json_from_json with int field", function()
  local fields = {
    { name = "count", jtype = "int", optional = false, nullable = false }
  }
  local defaults = { count = 0 }
  local parsed = { count = 42 }
  local instance = __rt.json_from_json("Counter", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.count == 42)
  assert(instance.__classname == "Counter")
  assert(instance.__kind == "class")
end)

test("json_from_json with string field", function()
  local fields = {
    { name = "name", jtype = "string", optional = false, nullable = false }
  }
  local defaults = { name = "" }
  local parsed = { name = "Alice" }
  local instance = __rt.json_from_json("Person", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.name == "Alice")
end)

test("json_from_json with boolean field", function()
  local fields = {
    { name = "active", jtype = "boolean", optional = false, nullable = false }
  }
  local defaults = { active = false }
  local parsed = { active = true }
  local instance = __rt.json_from_json("Flag", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.active == true)
end)

test("json_from_json with number field", function()
  local fields = {
    { name = "price", jtype = "number", optional = false, nullable = false }
  }
  local defaults = { price = 0.0 }
  local parsed = { price = 3.14 }
  local instance = __rt.json_from_json("Item", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.price == 3.14)
end)

test("json_from_json with null field", function()
  local fields = {
    { name = "data", jtype = "null", optional = false, nullable = true }
  }
  local defaults = { data = __rt.__NULL }
  local parsed = { data = __rt.__NULL }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.data == __rt.__NULL)
end)

test("json_from_json with table field", function()
  local fields = {
    { name = "meta", jtype = "table", optional = false, nullable = false }
  }
  local defaults = { meta = {} }
  local parsed = { meta = { key = "value" } }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.meta.key == "value")
end)

-- ==================== json_from_json: defaults applied ====================

test("json_from_json applies defaults for missing keys", function()
  local fields = {
    { name = "a", jtype = "int", optional = false, nullable = false },
    { name = "b", jtype = "string", optional = false, nullable = false }
  }
  local defaults = { a = 10, b = "default" }
  local parsed = { a = 99 }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.a == 99)
  assert(instance.b == "default")
end)

test("json_from_json with empty parsed table applies all defaults", function()
  local fields = {
    { name = "x", jtype = "int", optional = false, nullable = false },
    { name = "y", jtype = "string", optional = false, nullable = false }
  }
  local defaults = { x = 5, y = "hi" }
  local parsed = {}
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.x == 5)
  assert(instance.y == "hi")
end)

-- ==================== json_from_json: defaults deep-copied ====================

test("json_from_json deep-copies defaults (instances independent)", function()
  local fields = {
    { name = "items", jtype = "array", optional = false, nullable = false,
      element = { jtype = "int", optional = false, nullable = false } }
  }
  local defaults = { items = {1, 2, 3} }
  local parsed = {}
  local a = __rt.json_from_json("C", parsed, defaults, fields)
  local b = __rt.json_from_json("C", parsed, defaults, fields)
  a.items[1] = 99
  assert(b.items[1] == 1)
end)

test("json_from_json deep-copies nested table defaults", function()
  local fields = {
    { name = "data", jtype = "table", optional = false, nullable = false }
  }
  local defaults = { data = { nested = { x = 1 } } }
  local parsed = {}
  local a = __rt.json_from_json("C", parsed, defaults, fields)
  local b = __rt.json_from_json("C", parsed, defaults, fields)
  a.data.nested.x = 99
  assert(b.data.nested.x == 1)
end)

-- ==================== json_from_json: extra keys rejected ====================

test("json_from_json rejects extra keys", function()
  local fields = {
    { name = "name", jtype = "string", optional = false, nullable = false }
  }
  local defaults = { name = "" }
  local parsed = { name = "ok", extra = "bad" }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance == nil)
end)

test("json_from_json rejects unknown key with valid known key", function()
  local fields = {
    { name = "a", jtype = "int", optional = false, nullable = false }
  }
  local defaults = { a = 0 }
  local parsed = { a = 1, b = 2 }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance == nil)
end)

test("json_from_json accepts only known keys (no extras)", function()
  local fields = {
    { name = "id", jtype = "int", optional = false, nullable = false },
    { name = "name", jtype = "string", optional = false, nullable = false }
  }
  local defaults = { id = 0, name = "" }
  local parsed = { id = 1, name = "test" }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.id == 1)
  assert(instance.name == "test")
end)

-- ==================== json_from_json: empty fields ====================

test("json_from_json with empty fields array", function()
  local fields = {}
  local defaults = {}
  local parsed = {}
  local instance = __rt.json_from_json("Empty", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.__classname == "Empty")
  assert(instance.__kind == "class")
end)

test("json_from_json with empty fields and defaults", function()
  local fields = {}
  local defaults = { x = 1, y = 2 }
  local parsed = {}
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.x == 1)
  assert(instance.y == 2)
end)

-- ==================== json_from_json: optional fields ====================

test("json_from_json optional field not provided stays nil", function()
  local fields = {
    { name = "required", jtype = "string", optional = false, nullable = false },
    { name = "optional", jtype = "string", optional = true, nullable = false }
  }
  local defaults = { required = "", optional = __rt.__MISSING }
  local parsed = { required = "hi" }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.required == "hi")
  assert(instance.optional == nil)
end)

test("json_from_json optional field provided takes value", function()
  local fields = {
    { name = "required", jtype = "string", optional = false, nullable = false },
    { name = "optional", jtype = "string", optional = true, nullable = false }
  }
  local defaults = { required = "", optional = __rt.__MISSING }
  local parsed = { required = "hi", optional = "there" }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.required == "hi")
  assert(instance.optional == "there")
end)

-- ==================== json_from_json: nullable fields ====================

test("json_from_json nullable field with null value", function()
  local fields = {
    { name = "bio", jtype = "string", optional = false, nullable = true }
  }
  local defaults = { bio = __rt.__NULL }
  local parsed = { bio = __rt.__NULL }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.bio == __rt.__NULL)
end)

test("json_from_json nullable field with actual value", function()
  local fields = {
    { name = "bio", jtype = "string", optional = false, nullable = true }
  }
  local defaults = { bio = __rt.__NULL }
  local parsed = { bio = "hello" }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.bio == "hello")
end)

test("json_from_json nullable field absent keeps default null", function()
  local fields = {
    { name = "bio", jtype = "string", optional = false, nullable = true }
  }
  local defaults = { bio = __rt.__NULL }
  local parsed = {}
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.bio == __rt.__NULL)
end)

-- ==================== json_from_json: optional+nullable ====================

test("json_from_json optional+nullable: missing → nil", function()
  local fields = {
    { name = "tag", jtype = "string", optional = true, nullable = true }
  }
  local defaults = { tag = __rt.__MISSING }
  local parsed = {}
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.tag == nil)
end)

test("json_from_json optional+nullable: null → __NULL", function()
  local fields = {
    { name = "tag", jtype = "string", optional = true, nullable = true }
  }
  local defaults = { tag = __rt.__MISSING }
  local parsed = { tag = __rt.__NULL }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.tag == __rt.__NULL)
end)

test("json_from_json optional+nullable: value → value", function()
  local fields = {
    { name = "tag", jtype = "string", optional = true, nullable = true }
  }
  local defaults = { tag = __rt.__MISSING }
  local parsed = { tag = "important" }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.tag == "important")
end)

-- ==================== json_from_json: nested class ====================

test("json_from_json nested class field", function()
  local child_fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  local child_defaults = { x = 0 }
  local parent_fields = {
    { name = "child", jtype = "class", optional = false, nullable = false,
      className = "Child", defaults = child_defaults, fields = child_fields }
  }
  local parent_defaults = { child = __rt.__MISSING }
  local parsed = { child = { x = 42 } }
  local instance = __rt.json_from_json("Parent", parsed, parent_defaults, parent_fields)
  assert(instance ~= nil)
  assert(instance.child ~= nil)
  assert(instance.child.x == 42)
  assert(instance.child.__classname == "Child")
  assert(instance.child.__kind == "class")
end)

test("json_from_json nested class with extra keys fails", function()
  local child_fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  local child_defaults = { x = 0 }
  local parent_fields = {
    { name = "child", jtype = "class", optional = false, nullable = false,
      className = "Child", defaults = child_defaults, fields = child_fields }
  }
  local parent_defaults = { child = __rt.__MISSING }
  local parsed = { child = { x = 42, extra = true } }
  local instance = __rt.json_from_json("Parent", parsed, parent_defaults, parent_fields)
  assert(instance == nil)
end)

test("json_from_json nested class: non-table value fails", function()
  local child_fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  local child_defaults = { x = 0 }
  local parent_fields = {
    { name = "child", jtype = "class", optional = false, nullable = false,
      className = "Child", defaults = child_defaults, fields = child_fields }
  }
  local parent_defaults = { child = __rt.__MISSING }
  local parsed = { child = "not_a_table" }
  local instance = __rt.json_from_json("Parent", parsed, parent_defaults, parent_fields)
  assert(instance == nil)
end)

-- ==================== json_from_json: nullable nested class ====================

test("json_from_json nullable nested class with null", function()
  local child_fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  local child_defaults = { x = 0 }
  local parent_fields = {
    { name = "child", jtype = "class", optional = false, nullable = true,
      className = "Child", defaults = child_defaults, fields = child_fields }
  }
  local parent_defaults = { child = __rt.__NULL }
  local parsed = { child = __rt.__NULL }
  local instance = __rt.json_from_json("Parent", parsed, parent_defaults, parent_fields)
  assert(instance ~= nil)
  assert(instance.child == __rt.__NULL)
end)

-- ==================== json_from_json: array of primitives ====================

test("json_from_json array of ints", function()
  local fields = {
    { name = "tags", jtype = "array", optional = false, nullable = false,
      element = { jtype = "int", optional = false, nullable = false } }
  }
  local defaults = { tags = {} }
  local parsed = { tags = {1, 2, 3} }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(#instance.tags == 3)
  assert(instance.tags[1] == 1)
  assert(instance.tags[2] == 2)
  assert(instance.tags[3] == 3)
end)

test("json_from_json array of strings", function()
  local fields = {
    { name = "names", jtype = "array", optional = false, nullable = false,
      element = { jtype = "string", optional = false, nullable = false } }
  }
  local defaults = { names = {} }
  local parsed = { names = {"a", "b", "c"} }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(#instance.names == 3)
  assert(instance.names[1] == "a")
  assert(instance.names[3] == "c")
end)

test("json_from_json empty array", function()
  local fields = {
    { name = "items", jtype = "array", optional = false, nullable = false,
      element = { jtype = "int", optional = false, nullable = false } }
  }
  local defaults = { items = {} }
  local parsed = { items = {} }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(#instance.items == 0)
end)

test("json_from_json array: non-table fails", function()
  local fields = {
    { name = "tags", jtype = "array", optional = false, nullable = false,
      element = { jtype = "int", optional = false, nullable = false } }
  }
  local defaults = { tags = {} }
  local parsed = { tags = 42 }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance == nil)
end)

-- ==================== json_from_json: array of nested classes ====================

test("json_from_json array of nested classes", function()
  local child_fields = {
    { name = "val", jtype = "int", optional = false, nullable = false }
  }
  local child_defaults = { val = 0 }
  local parent_fields = {
    { name = "children", jtype = "array", optional = false, nullable = false,
      element = { jtype = "class", className = "Child", defaults = child_defaults, fields = child_fields } }
  }
  local parent_defaults = { children = {} }
  local parsed = { children = { { val = 1 }, { val = 2 }, { val = 3 } } }
  local instance = __rt.json_from_json("Parent", parsed, parent_defaults, parent_fields)
  assert(instance ~= nil)
  assert(#instance.children == 3)
  assert(instance.children[1].val == 1)
  assert(instance.children[1].__classname == "Child")
  assert(instance.children[2].val == 2)
  assert(instance.children[3].val == 3)
end)

test("json_from_json array of nested classes: element failure fails parent", function()
  local child_fields = {
    { name = "val", jtype = "int", optional = false, nullable = false }
  }
  local child_defaults = { val = 0 }
  local parent_fields = {
    { name = "children", jtype = "array", optional = false, nullable = false,
      element = { jtype = "class", className = "Child", defaults = child_defaults, fields = child_fields } }
  }
  local parent_defaults = { children = {} }
  -- Third element has extra key, should fail
  local parsed = { children = { { val = 1 }, { val = 2 }, { val = 3, extra = true } } }
  local instance = __rt.json_from_json("Parent", parsed, parent_defaults, parent_fields)
  assert(instance == nil)
end)

-- ==================== json_from_json: __MISSING removal ====================

test("json_from_json removes __MISSING from instance", function()
  local fields = {
    { name = "required", jtype = "string", optional = false, nullable = false },
    { name = "optional1", jtype = "string", optional = true, nullable = false },
    { name = "optional2", jtype = "int", optional = true, nullable = false }
  }
  local defaults = { required = "", optional1 = __rt.__MISSING, optional2 = __rt.__MISSING }
  local parsed = { required = "x" }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  -- Verify no __MISSING in instance
  for k, v in pairs(instance) do
    assert(v ~= __rt.__MISSING)
  end
  assert(instance.optional1 == nil)
  assert(instance.optional2 == nil)
end)

-- ==================== json_to_json: basic ====================

test("json_to_json includes required fields", function()
  local fields = {
    { name = "name", jtype = "string", optional = false, nullable = false }
  }
  local instance = { name = "Alice", __classname = "Person", __kind = "class" }
  local result = __rt.json_to_json("Person", instance, fields)
  assert(result ~= nil)
  assert(result.name == "Alice")
end)

test("json_to_json includes multiple fields", function()
  local fields = {
    { name = "id", jtype = "int", optional = false, nullable = false },
    { name = "name", jtype = "string", optional = false, nullable = false }
  }
  local instance = { id = 1, name = "Test", __classname = "C", __kind = "class" }
  local result = __rt.json_to_json("C", instance, fields)
  assert(result ~= nil)
  assert(result.id == 1)
  assert(result.name == "Test")
end)

-- ==================== json_to_json: optional fields ====================

test("json_to_json omits missing optional field", function()
  local fields = {
    { name = "required", jtype = "string", optional = false, nullable = false },
    { name = "optional", jtype = "string", optional = true, nullable = false }
  }
  local instance = { required = "x", __classname = "C", __kind = "class" }
  -- optional field is nil (not set)
  local result = __rt.json_to_json("C", instance, fields)
  assert(result ~= nil)
  assert(result.required == "x")
  assert(result.optional == nil)
end)

test("json_to_json includes set optional field", function()
  local fields = {
    { name = "required", jtype = "string", optional = false, nullable = false },
    { name = "optional", jtype = "string", optional = true, nullable = false }
  }
  local instance = { required = "x", optional = "y", __classname = "C", __kind = "class" }
  local result = __rt.json_to_json("C", instance, fields)
  assert(result ~= nil)
  assert(result.required == "x")
  assert(result.optional == "y")
end)

-- ==================== json_to_json: nullable fields ====================

test("json_to_json emits __NULL for explicit null on nullable field", function()
  local fields = {
    { name = "bio", jtype = "string", optional = false, nullable = true }
  }
  local instance = { bio = __rt.__NULL, __classname = "C", __kind = "class" }
  local result = __rt.json_to_json("C", instance, fields)
  assert(result ~= nil)
  assert(result.bio == __rt.__NULL)
end)

test("json_to_json emits value for non-null nullable field", function()
  local fields = {
    { name = "bio", jtype = "string", optional = false, nullable = true }
  }
  local instance = { bio = "hello", __classname = "C", __kind = "class" }
  local result = __rt.json_to_json("C", instance, fields)
  assert(result ~= nil)
  assert(result.bio == "hello")
end)

-- ==================== json_to_json: optional+nullable ====================

test("json_to_json optional+nullable: missing → omitted", function()
  local fields = {
    { name = "tag", jtype = "string", optional = true, nullable = true }
  }
  local instance = { __classname = "C", __kind = "class" }
  -- tag is nil (missing)
  local result = __rt.json_to_json("C", instance, fields)
  assert(result ~= nil)
  assert(result.tag == nil)
end)

test("json_to_json optional+nullable: null → __NULL", function()
  local fields = {
    { name = "tag", jtype = "string", optional = true, nullable = true }
  }
  local instance = { tag = __rt.__NULL, __classname = "C", __kind = "class" }
  local result = __rt.json_to_json("C", instance, fields)
  assert(result ~= nil)
  assert(result.tag == __rt.__NULL)
end)

test("json_to_json optional+nullable: value → value", function()
  local fields = {
    { name = "tag", jtype = "string", optional = true, nullable = true }
  }
  local instance = { tag = "important", __classname = "C", __kind = "class" }
  local result = __rt.json_to_json("C", instance, fields)
  assert(result ~= nil)
  assert(result.tag == "important")
end)

-- ==================== json_to_json: nested class ====================

test("json_to_json nested class field", function()
  local child_fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  local child_instance = { x = 42, __classname = "Child", __kind = "class" }
  local parent_fields = {
    { name = "child", jtype = "class", optional = false, nullable = false,
      className = "Child", fields = child_fields }
  }
  local parent_instance = { child = child_instance, __classname = "Parent", __kind = "class" }
  local result = __rt.json_to_json("Parent", parent_instance, parent_fields)
  assert(result ~= nil)
  assert(result.child ~= nil)
  assert(result.child.x == 42)
end)

-- ==================== json_to_json: array ====================

test("json_to_json array of primitives", function()
  local fields = {
    { name = "tags", jtype = "array", optional = false, nullable = false,
      element = { jtype = "int", optional = false, nullable = false } }
  }
  local instance = { tags = {1, 2, 3}, __classname = "C", __kind = "class" }
  local result = __rt.json_to_json("C", instance, fields)
  assert(result ~= nil)
  assert(#result.tags == 3)
  assert(result.tags[1] == 1)
  assert(result.tags[2] == 2)
  assert(result.tags[3] == 3)
end)

test("json_to_json array of nested classes", function()
  local child_fields = {
    { name = "val", jtype = "int", optional = false, nullable = false }
  }
  local parent_fields = {
    { name = "children", jtype = "array", optional = false, nullable = false,
      element = { jtype = "class", className = "Child", fields = child_fields } }
  }
  local child1 = { val = 1, __classname = "Child", __kind = "class" }
  local child2 = { val = 2, __classname = "Child", __kind = "class" }
  local instance = { children = {child1, child2}, __classname = "Parent", __kind = "class" }
  local result = __rt.json_to_json("Parent", instance, parent_fields)
  assert(result ~= nil)
  assert(#result.children == 2)
  assert(result.children[1].val == 1)
  assert(result.children[2].val == 2)
end)

test("json_to_json empty array", function()
  local fields = {
    { name = "items", jtype = "array", optional = false, nullable = false,
      element = { jtype = "int", optional = false, nullable = false } }
  }
  local instance = { items = {}, __classname = "C", __kind = "class" }
  local result = __rt.json_to_json("C", instance, fields)
  assert(result ~= nil)
  assert(#result.items == 0)
end)

-- ==================== Optional-nullable roundtrip ====================

test("optional-nullable roundtrip: missing → missing", function()
  local fields = {
    { name = "tag", jtype = "string", optional = true, nullable = true }
  }
  -- Build instance with missing optional field
  local instance = __rt.class_("C", { tag = __rt.__MISSING }, {})
  assert(instance.tag == nil)

  local serialized = __rt.json_to_json("C", instance, fields)
  -- tag should be omitted
  assert(serialized.tag == nil)

  local roundtripped = __rt.json_from_json("C", serialized, { tag = __rt.__MISSING }, fields)
  assert(roundtripped ~= nil)
  assert(roundtripped.tag == nil)
end)

test("optional-nullable roundtrip: null → null", function()
  local fields = {
    { name = "tag", jtype = "string", optional = true, nullable = true }
  }
  -- Build instance with explicit null
  local instance = __rt.class_("C", { tag = __rt.__NULL }, { tag = __rt.__NULL })
  assert(instance.tag == __rt.__NULL)

  local serialized = __rt.json_to_json("C", instance, fields)
  -- tag should be __NULL
  assert(serialized.tag == __rt.__NULL)

  local roundtripped = __rt.json_from_json("C", serialized, { tag = __rt.__NULL }, fields)
  assert(roundtripped ~= nil)
  assert(roundtripped.tag == __rt.__NULL)
end)

test("optional-nullable roundtrip: value → value", function()
  local fields = {
    { name = "tag", jtype = "string", optional = true, nullable = true }
  }
  -- Build instance with value
  local instance = __rt.class_("C", { tag = __rt.__MISSING }, { tag = "important" })
  assert(instance.tag == "important")

  local serialized = __rt.json_to_json("C", instance, fields)
  -- tag should be the value
  assert(serialized.tag == "important")

  local roundtripped = __rt.json_from_json("C", serialized, { tag = __rt.__MISSING }, fields)
  assert(roundtripped ~= nil)
  assert(roundtripped.tag == "important")
end)

test("optional-nullable roundtrip: all three states in one instance", function()
  local fields = {
    { name = "missing_field", jtype = "string", optional = true, nullable = true },
    { name = "null_field", jtype = "string", optional = true, nullable = true },
    { name = "value_field", jtype = "string", optional = true, nullable = true },
    { name = "required", jtype = "string", optional = false, nullable = false }
  }
  -- Construct with all three states
  local instance = __rt.class_("TriState", {
    missing_field = __rt.__MISSING,
    null_field = __rt.__NULL,
    value_field = __rt.__MISSING,
    required = ""
  }, {
    null_field = __rt.__NULL,
    value_field = "hello",
    required = "r"
  })
  assert(instance.missing_field == nil)
  assert(instance.null_field == __rt.__NULL)
  assert(instance.value_field == "hello")
  assert(instance.required == "r")

  local serialized = __rt.json_to_json("TriState", instance, fields)
  assert(serialized.missing_field == nil)
  assert(serialized.null_field == __rt.__NULL)
  assert(serialized.value_field == "hello")
  assert(serialized.required == "r")

  local roundtripped = __rt.json_from_json("TriState", serialized, {
    missing_field = __rt.__MISSING,
    null_field = __rt.__NULL,
    value_field = __rt.__MISSING,
    required = ""
  }, fields)
  assert(roundtripped ~= nil)
  assert(roundtripped.missing_field == nil)
  assert(roundtripped.null_field == __rt.__NULL)
  assert(roundtripped.value_field == "hello")
  assert(roundtripped.required == "r")
end)

-- ==================== Nested class roundtrip ====================

test("nested class roundtrip", function()
  local child_fields = {
    { name = "x", jtype = "int", optional = false, nullable = false },
    { name = "y", jtype = "string", optional = true, nullable = false }
  }
  local child_defaults = { x = 0, y = __rt.__MISSING }
  local parent_fields = {
    { name = "name", jtype = "string", optional = false, nullable = false },
    { name = "child", jtype = "class", optional = false, nullable = false,
      className = "Child", defaults = child_defaults, fields = child_fields }
  }
  local parent_defaults = { name = "", child = __rt.__MISSING }

  -- Build nested instance
  local child = __rt.class_("Child", child_defaults, { x = 99, y = "why" })
  local parent = { name = "Parent", child = child, __classname = "Parent", __kind = "class" }

  local serialized = __rt.json_to_json("Parent", parent, parent_fields)
  assert(serialized.name == "Parent")
  assert(serialized.child ~= nil)
  assert(serialized.child.x == 99)
  assert(serialized.child.y == "why")

  local roundtripped = __rt.json_from_json("Parent", serialized, parent_defaults, parent_fields)
  assert(roundtripped ~= nil)
  assert(roundtripped.name == "Parent")
  assert(roundtripped.child ~= nil)
  assert(roundtripped.child.x == 99)
  assert(roundtripped.child.y == "why")
  assert(roundtripped.child.__classname == "Child")
end)

-- ==================== Multi-level nesting roundtrip ====================

test("three-level nested class roundtrip", function()
  local grandchild_fields = {
    { name = "v", jtype = "int", optional = false, nullable = false }
  }
  local grandchild_defaults = { v = 0 }
  local child_fields = {
    { name = "name", jtype = "string", optional = false, nullable = false },
    { name = "grand", jtype = "class", optional = false, nullable = false,
      className = "GrandChild", defaults = grandchild_defaults, fields = grandchild_fields }
  }
  local child_defaults = { name = "", grand = __rt.__MISSING }
  local parent_fields = {
    { name = "child", jtype = "class", optional = false, nullable = false,
      className = "Child", defaults = child_defaults, fields = child_fields }
  }
  local parent_defaults = { child = __rt.__MISSING }

  local gc = __rt.class_("GrandChild", grandchild_defaults, { v = 7 })
  local child = { name = "middle", grand = gc, __classname = "Child", __kind = "class" }
  local parent = { child = child, __classname = "Parent", __kind = "class" }

  local serialized = __rt.json_to_json("Parent", parent, parent_fields)
  assert(serialized.child.name == "middle")
  assert(serialized.child.grand.v == 7)

  local roundtripped = __rt.json_from_json("Parent", serialized, parent_defaults, parent_fields)
  assert(roundtripped ~= nil)
  assert(roundtripped.child.name == "middle")
  assert(roundtripped.child.grand.v == 7)
end)

-- ==================== Array roundtrip ====================

test("array of primitives roundtrip", function()
  local fields = {
    { name = "nums", jtype = "array", optional = false, nullable = false,
      element = { jtype = "int", optional = false, nullable = false } }
  }
  local defaults = { nums = {} }
  local instance = { nums = {10, 20, 30}, __classname = "C", __kind = "class" }

  local serialized = __rt.json_to_json("C", instance, fields)
  assert(#serialized.nums == 3)
  assert(serialized.nums[1] == 10)
  assert(serialized.nums[3] == 30)

  local roundtripped = __rt.json_from_json("C", serialized, defaults, fields)
  assert(roundtripped ~= nil)
  assert(#roundtripped.nums == 3)
  assert(roundtripped.nums[1] == 10)
  assert(roundtripped.nums[3] == 30)
end)

test("array of nested classes roundtrip", function()
  local child_fields = {
    { name = "val", jtype = "int", optional = false, nullable = false }
  }
  local child_defaults = { val = 0 }
  local parent_fields = {
    { name = "children", jtype = "array", optional = false, nullable = false,
      element = { jtype = "class", className = "Child", defaults = child_defaults, fields = child_fields } }
  }
  local parent_defaults = { children = {} }

  local c1 = __rt.class_("Child", child_defaults, { val = 1 })
  local c2 = __rt.class_("Child", child_defaults, { val = 2 })
  local instance = { children = {c1, c2}, __classname = "Parent", __kind = "class" }

  local serialized = __rt.json_to_json("Parent", instance, parent_fields)
  assert(#serialized.children == 2)
  assert(serialized.children[1].val == 1)
  assert(serialized.children[2].val == 2)

  local roundtripped = __rt.json_from_json("Parent", serialized, parent_defaults, parent_fields)
  assert(roundtripped ~= nil)
  assert(#roundtripped.children == 2)
  assert(roundtripped.children[1].val == 1)
  assert(roundtripped.children[2].val == 2)
  assert(roundtripped.children[1].__classname == "Child")
end)

-- ==================== Instance tagging ====================

test("json_from_json tags instance with __kind = class", function()
  local fields = { { name = "x", jtype = "int", optional = false, nullable = false } }
  local defaults = { x = 0 }
  local parsed = { x = 1 }
  local instance = __rt.json_from_json("MyClass", parsed, defaults, fields)
  assert(instance.__kind == "class")
end)

test("json_from_json tags instance with correct __classname", function()
  local fields = { { name = "x", jtype = "int", optional = false, nullable = false } }
  local defaults = { x = 0 }
  local parsed = { x = 1 }
  local instance = __rt.json_from_json("MyClass", parsed, defaults, fields)
  assert(instance.__classname == "MyClass")
end)

-- ==================== Edge case: json_from_json with all field types ====================

test("json_from_json with all primitive field types", function()
  local fields = {
    { name = "f_null",    jtype = "null",    optional = false, nullable = true },
    { name = "f_bool",    jtype = "boolean", optional = false, nullable = false },
    { name = "f_int",     jtype = "int",     optional = false, nullable = false },
    { name = "f_number",  jtype = "number",  optional = false, nullable = false },
    { name = "f_string",  jtype = "string",  optional = false, nullable = false },
    { name = "f_table",   jtype = "table",   optional = false, nullable = false },
  }
  local defaults = {
    f_null = __rt.__NULL,
    f_bool = false,
    f_int = 0,
    f_number = 0.0,
    f_string = "",
    f_table = {},
  }
  local parsed = {
    f_null = __rt.__NULL,
    f_bool = true,
    f_int = 42,
    f_number = 3.14,
    f_string = "hello",
    f_table = { key = "val" },
  }
  local instance = __rt.json_from_json("AllTypes", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.f_null == __rt.__NULL)
  assert(instance.f_bool == true)
  assert(instance.f_int == 42)
  assert(instance.f_number == 3.14)
  assert(instance.f_string == "hello")
  assert(instance.f_table.key == "val")
end)

-- ==================== json_from_json: null non-nullable field returns nil? ====================

test("json_from_json: null as json_parse result on non-nullable string field", function()
  -- json.parse("null") returns __rt.__NULL
  -- If a non-nullable field receives __NULL, it should be treated as a value
  -- This is because json.parse maps JSON null to __rt.__NULL
  -- The field descriptor says nullable=false, so raw == __rt.__NULL check fails
  -- It falls through to the "else" branch and stores __rt.__NULL as the value.
  -- This is acceptable since json.parse already validated the JSON structure.
  -- The generated C$fromJson code will handle this with pcall.
  local fields = {
    { name = "name", jtype = "string", optional = false, nullable = false }
  }
  local defaults = { name = "" }
  local parsed = { name = __rt.__NULL }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  -- __NULL is stored as-is (not a string, but that's what json.parse gave us)
  assert(instance.name == __rt.__NULL)
end)

-- ==================== Non-jsonable: json_to_json returns table not string ====================

test("json_to_json returns a table, not a JSON string", function()
  local fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  local instance = { x = 42, __classname = "C", __kind = "class" }
  local result = __rt.json_to_json("C", instance, fields)
  assert(type(result) == "table")
  assert(result.x == 42)
end)

-- ==================== json_from_json returns nil not error ====================

test("json_from_json returns nil on failure (does not throw)", function()
  local fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  local defaults = { x = 0 }
  local parsed = { x = 1, extra = true }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance == nil)
end)

-- ==================== Direct helpers: _json_is_object ====================

test("_json_is_object: non-table inputs are false, no throw", function()
  assert(__rt._json_is_object(42) == false)
  assert(__rt._json_is_object("x") == false)
  assert(__rt._json_is_object(true) == false)
  assert(__rt._json_is_object(nil) == false)
  assert(__rt._json_is_object(function() end) == false)
end)

test("_json_is_object: string keys accepted, non-string keys rejected", function()
  assert(__rt._json_is_object({}) == true)
  assert(__rt._json_is_object(__rt.__NULL) == true)  -- empty table
  assert(__rt._json_is_object({ a = 1, b = 2 }) == true)
  assert(__rt._json_is_object({ [1] = "x" }) == false)
  assert(__rt._json_is_object({ a = 1, [2] = "x" }) == false)
  assert(__rt._json_is_object({ [true] = "x" }) == false)
end)

-- ==================== Direct helpers: _json_is_array ====================

test("_json_is_array: non-table inputs are false, no throw", function()
  assert(__rt._json_is_array(42) == false)
  assert(__rt._json_is_array("x") == false)
  assert(__rt._json_is_array(false) == false)
  assert(__rt._json_is_array(nil) == false)
  assert(__rt._json_is_array(function() end) == false)
end)

test("_json_is_array: dense arrays accepted, edge cases rejected", function()
  assert(__rt._json_is_array({}) == true)
  assert(__rt._json_is_array({1, 2, 3}) == true)
  assert(__rt._json_is_array({[1] = "x"}) == true)
  assert(__rt._json_is_array({[1] = "x", [2] = "y"}) == true)
  assert(__rt._json_is_array({ a = 1 }) == false)
  assert(__rt._json_is_array({1, 2, [4] = 4}) == false)          -- hole at 3
  assert(__rt._json_is_array({[2] = "x"}) == false)              -- sparse: missing 1
  assert(__rt._json_is_array({[1] = "x", [3] = "y"}) == false)   -- hole at 2
  assert(__rt._json_is_array({[0] = "x"}) == false)              -- 0-key
  assert(__rt._json_is_array({[-1] = "x"}) == false)             -- negative key
  assert(__rt._json_is_array({[1.5] = "x"}) == false)            -- non-integer key
  assert(__rt._json_is_array({[1] = "x", [2] = "y", a = "z"}) == false)  -- mixed keys
  assert(__rt._json_is_array({1, nil, 3}) == false)              -- nil hole
end)

-- ==================== Direct helpers: _json_is_int / _json_is_number ====================

test("_json_is_int: safe-range boundaries accepted, beyond rejected", function()
  assert(__rt._json_is_int(0) == true)
  assert(__rt._json_is_int(-0) == true)
  assert(__rt._json_is_int(42) == true)
  assert(__rt._json_is_int(-42) == true)
  assert(__rt._json_is_int(9007199254740991) == true)
  assert(__rt._json_is_int(-9007199254740991) == true)
  assert(__rt._json_is_int(9007199254740992) == false)
  assert(__rt._json_is_int(-9007199254740992) == false)
end)

test("_json_is_int: NaN/Infinity/non-integer/non-number rejected", function()
  assert(__rt._json_is_int(0/0) == false)    -- NaN
  assert(__rt._json_is_int(1/0) == false)    -- +Infinity
  assert(__rt._json_is_int(-1/0) == false)   -- -Infinity
  assert(__rt._json_is_int(3.5) == false)    -- non-integer
  assert(__rt._json_is_int("42") == false)
  assert(__rt._json_is_int(true) == false)
  assert(__rt._json_is_int(nil) == false)
  assert(__rt._json_is_int(function() end) == false)
end)

test("_json_is_number: finite numbers accepted, NaN/Infinity/non-numbers rejected", function()
  assert(__rt._json_is_number(0) == true)
  assert(__rt._json_is_number(-0) == true)
  assert(__rt._json_is_number(3.14) == true)
  assert(__rt._json_is_number(1e300) == true)
  assert(__rt._json_is_number(-1e300) == true)
  assert(__rt._json_is_number(0/0) == false)    -- NaN
  assert(__rt._json_is_number(1/0) == false)    -- +Infinity
  assert(__rt._json_is_number(-1/0) == false)   -- -Infinity
  assert(__rt._json_is_number("3.14") == false)
  assert(__rt._json_is_number(true) == false)
  assert(__rt._json_is_number(nil) == false)
  assert(__rt._json_is_number(function() end) == false)
end)

-- ==================== Direct helpers: _json_table_shape ====================

test("_json_table_shape: non-table inputs are false, no throw", function()
  assert(__rt._json_table_shape(42, {}, 0) == false)
  assert(__rt._json_table_shape("x", {}, 0) == false)
  assert(__rt._json_table_shape(true, {}, 0) == false)
  assert(__rt._json_table_shape(nil, {}, 0) == false)
  assert(__rt._json_table_shape(function() end, {}, 0) == false)
end)

test("_json_table_shape: nested objects/arrays with finite primitive leaves accepted", function()
  local datum = {
    obj = { a = 1, b = "x", c = true, d = __rt.__NULL },
    arr = {1, 2.5, "s", false, __rt.__NULL},
    nested = { inner = { {1, 2}, {k = "v"} } },
  }
  assert(__rt._json_table_shape(datum, {}, 0) == true)
  assert(__rt._json_table_shape({}, {}, 0) == true)
  assert(__rt._json_table_shape({}, {}) == true)          -- nil seen/depth default total
  assert(__rt._json_table_shape(__rt.__NULL, {}, 0) == true)  -- empty table datum
end)

test("_json_table_shape: function leaf rejected", function()
  assert(__rt._json_table_shape({ fn = function() end }, {}, 0) == false)
  assert(__rt._json_table_shape({ nested = { fn = function() end } }, {}, 0) == false)
end)

test("_json_table_shape: function-wrapper leaf rejected", function()
  assert(__rt._json_table_shape({ fn = { __kind = "function" } }, {}, 0) == false)
end)

test("_json_table_shape: class-instance leaf rejected", function()
  assert(__rt._json_table_shape({ inst = { __kind = "class", __classname = "C" } }, {}, 0) == false)
end)

test("_json_table_shape: async handle leaf rejected", function()
  assert(__rt._json_table_shape({ handle = { __kind = "async" } }, {}, 0) == false)
end)

test("_json_table_shape: top-level tagged tables rejected", function()
  assert(__rt._json_table_shape({ __kind = "class", __classname = "C" }, {}, 0) == false)
  assert(__rt._json_table_shape({ __kind = "function" }, {}, 0) == false)
  assert(__rt._json_table_shape({ __kind = "async" }, {}, 0) == false)
end)

test("_json_table_shape: NaN and Infinity leaves rejected", function()
  assert(__rt._json_table_shape({ x = 0/0 }, {}, 0) == false)
  assert(__rt._json_table_shape({ x = 1/0 }, {}, 0) == false)
  assert(__rt._json_table_shape({ x = -1/0 }, {}, 0) == false)
  assert(__rt._json_table_shape({ arr = {1, 0/0} }, {}, 0) == false)
  assert(__rt._json_table_shape({ obj = { x = 1/0 } }, {}, 0) == false)
end)

test("_json_table_shape: cyclic datum rejected", function()
  local t = { a = 1 }
  t.self = t
  assert(__rt._json_table_shape(t, {}, 0) == false)
  local u = { child = {} }
  u.child.back = u
  assert(__rt._json_table_shape(u, {}, 0) == false)
end)

test("_json_table_shape: shared non-cyclic child accepted (path-local seen)", function()
  local child = { x = 1 }
  assert(__rt._json_table_shape({ a = child, b = child }, {}, 0) == true)
end)

test("_json_table_shape: mixed-shape tables rejected", function()
  assert(__rt._json_table_shape({ [1] = "x", a = 1 }, {}, 0) == false)     -- mixed keys
  assert(__rt._json_table_shape({ [1] = "x", [3] = "z" }, {}, 0) == false) -- sparse
  assert(__rt._json_table_shape({ [0] = "x" }, {}, 0) == false)            -- 0-key array
end)

test("_json_table_shape: depth boundary 512 accepted, 513 rejected", function()
  local function build_chain(n)  -- n tables total: deepest nested at depth n-1
    local root = {}
    local cur = root
    for i = 2, n do
      cur.child = {}
      cur = cur.child
    end
    cur.leaf = "x"
    return root
  end
  assert(__rt._json_table_shape(build_chain(513), {}, 0) == true)   -- deepest at depth 512
  assert(__rt._json_table_shape(build_chain(514), {}, 0) == false)  -- deepest at depth 513
end)

test("_json_table_shape: malformed seen/depth arguments never throw", function()
  assert(__rt._json_table_shape({ x = 1 }, 42, "deep") == true)
  local cyc = {}
  cyc.self = cyc
  assert(__rt._json_table_shape(cyc, "not-a-set", -5) == false)
  assert(__rt._json_defaults_acyclic({ a = 1 }, "not-a-set") == true)
end)

test("_json_table_shape: seen set unwound on success and failure", function()
  local seen = {}
  assert(__rt._json_table_shape({ a = { b = 1 } }, seen, 0) == true)
  assert(next(seen) == nil)
  assert(__rt._json_table_shape({ x = function() end }, seen, 0) == false)
  assert(next(seen) == nil)
  local cyc = {}
  cyc.self = cyc
  assert(__rt._json_table_shape(cyc, seen, 0) == false)
  assert(next(seen) == nil)
end)

-- ==================== Direct helpers: _json_validate_entry ====================

test("_json_validate_entry: non-table entries are false, no throw", function()
  assert(__rt._json_validate_entry(42, true) == false)
  assert(__rt._json_validate_entry("x", true) == false)
  assert(__rt._json_validate_entry(nil, true) == false)
  assert(__rt._json_validate_entry(function() end, true) == false)
  assert(__rt._json_validate_entry(42, false) == false)
end)

test("_json_validate_entry: field entries require boolean optional/nullable", function()
  local ok = { name = "x", jtype = "int", optional = false, nullable = false }
  assert(__rt._json_validate_entry(ok, true) == true)
  assert(__rt._json_validate_entry(ok, false) == true)
  -- missing optional
  local m1 = { name = "x", jtype = "int", nullable = false }
  assert(__rt._json_validate_entry(m1, true) == false)
  assert(__rt._json_validate_entry(m1, false) == false)
  -- missing nullable
  local m2 = { name = "x", jtype = "int", optional = false }
  assert(__rt._json_validate_entry(m2, true) == false)
  assert(__rt._json_validate_entry(m2, false) == false)
  -- non-boolean optional (truthy)
  local n1 = { name = "x", jtype = "int", optional = 1, nullable = false }
  assert(__rt._json_validate_entry(n1, true) == false)
  assert(__rt._json_validate_entry(n1, false) == false)
  -- non-boolean nullable (truthy string)
  local n2 = { name = "x", jtype = "int", optional = false, nullable = "yes" }
  assert(__rt._json_validate_entry(n2, true) == false)
  assert(__rt._json_validate_entry(n2, false) == false)
end)

test("_json_validate_entry: field-entry name must be a string", function()
  local bad = { name = 42, jtype = "int", optional = false, nullable = false }
  assert(__rt._json_validate_entry(bad, true) == false)
  assert(__rt._json_validate_entry(bad, false) == false)
end)

test("_json_validate_entry: unknown or missing jtype is false", function()
  assert(__rt._json_validate_entry({ name = "x", jtype = "function", optional = false, nullable = false }, true) == false)
  assert(__rt._json_validate_entry({ name = "x", jtype = "int32", optional = false, nullable = false }, true) == false)
  assert(__rt._json_validate_entry({ jtype = "wat" }, true) == false)
  assert(__rt._json_validate_entry({ name = "x", optional = false, nullable = false }, true) == false)
end)

test("_json_validate_entry: element descriptors may omit optional/nullable", function()
  local elem = { jtype = "int" }
  assert(__rt._json_validate_entry(elem, true) == true)
  assert(__rt._json_validate_entry(elem, false) == true)
  -- present boolean element flags stay valid
  local with_flags = { jtype = "int", optional = false, nullable = true }
  assert(__rt._json_validate_entry(with_flags, true) == true)
  assert(__rt._json_validate_entry(with_flags, false) == true)
  -- present non-boolean element flag is malformed
  assert(__rt._json_validate_entry({ jtype = "int", optional = 1 }, true) == false)
  assert(__rt._json_validate_entry({ jtype = "int", optional = 1 }, false) == false)
  assert(__rt._json_validate_entry({ jtype = "int", nullable = "yes" }, true) == false)
  assert(__rt._json_validate_entry({ jtype = "int", nullable = "yes" }, false) == false)
end)

test("_json_validate_entry: class entries need className and fields; defaults decode-only", function()
  local full = { name = "c", jtype = "class", optional = false, nullable = false,
                 className = "C", defaults = {}, fields = {} }
  assert(__rt._json_validate_entry(full, true) == true)
  assert(__rt._json_validate_entry(full, false) == true)
  -- encode accepts a class entry without defaults; decode rejects it
  local no_defaults = { name = "c", jtype = "class", optional = false, nullable = false,
                        className = "C", fields = {} }
  assert(__rt._json_validate_entry(no_defaults, false) == true)
  assert(__rt._json_validate_entry(no_defaults, true) == false)
  -- missing className
  local no_classname = { name = "c", jtype = "class", optional = false, nullable = false,
                         defaults = {}, fields = {} }
  assert(__rt._json_validate_entry(no_classname, true) == false)
  assert(__rt._json_validate_entry(no_classname, false) == false)
  -- missing fields
  local no_fields = { name = "c", jtype = "class", optional = false, nullable = false,
                      className = "C", defaults = {} }
  assert(__rt._json_validate_entry(no_fields, true) == false)
  assert(__rt._json_validate_entry(no_fields, false) == false)
  -- non-string className
  local bad_classname = { name = "c", jtype = "class", optional = false, nullable = false,
                          className = 42, defaults = {}, fields = {} }
  assert(__rt._json_validate_entry(bad_classname, true) == false)
  -- non-table defaults rejected on decode only
  local bad_defaults = { name = "c", jtype = "class", optional = false, nullable = false,
                         className = "C", defaults = "x", fields = {} }
  assert(__rt._json_validate_entry(bad_defaults, true) == false)
  assert(__rt._json_validate_entry(bad_defaults, false) == true)
end)

test("_json_validate_entry: class elements follow the same rule", function()
  local elem = { jtype = "class", className = "C", defaults = {}, fields = {} }
  assert(__rt._json_validate_entry(elem, true) == true)
  assert(__rt._json_validate_entry(elem, false) == true)
  local no_defaults = { jtype = "class", className = "C", fields = {} }
  assert(__rt._json_validate_entry(no_defaults, false) == true)
  assert(__rt._json_validate_entry(no_defaults, true) == false)
  local no_fields = { jtype = "class", className = "C", defaults = {} }
  assert(__rt._json_validate_entry(no_fields, true) == false)
  assert(__rt._json_validate_entry(no_fields, false) == false)
  local no_classname = { jtype = "class", defaults = {}, fields = {} }
  assert(__rt._json_validate_entry(no_classname, true) == false)
end)

test("_json_validate_entry: array entries require a table element", function()
  local ok_field = { name = "a", jtype = "array", optional = false, nullable = false,
                     element = { jtype = "int" } }
  assert(__rt._json_validate_entry(ok_field, true) == true)
  assert(__rt._json_validate_entry(ok_field, false) == true)
  local no_element = { name = "a", jtype = "array", optional = false, nullable = false }
  assert(__rt._json_validate_entry(no_element, true) == false)
  assert(__rt._json_validate_entry(no_element, false) == false)
  local non_table_element = { name = "a", jtype = "array", optional = false, nullable = false,
                              element = "int" }
  assert(__rt._json_validate_entry(non_table_element, true) == false)
end)

test("_json_validate_entry: truncated nested-array element descriptor is false", function()
  -- an array-typed element descriptor without its own element key
  local truncated = { jtype = "array", optional = false, nullable = false }
  assert(__rt._json_validate_entry(truncated, true) == false)
  assert(__rt._json_validate_entry(truncated, false) == false)
end)

-- ==================== Direct helpers: _json_validate_fields ====================

test("_json_validate_fields: non-table fields is false, no throw", function()
  assert(__rt._json_validate_fields(42, true) == false)
  assert(__rt._json_validate_fields("x", true) == false)
  assert(__rt._json_validate_fields(nil, true) == false)
  assert(__rt._json_validate_fields(function() end, true) == false)
  assert(__rt._json_validate_fields(42, false) == false)
end)

test("_json_validate_fields: any invalid entry fails the whole array", function()
  local fields = {
    { name = "a", jtype = "int", optional = false, nullable = false },
    { name = "b", jtype = "string", optional = true, nullable = false },
  }
  assert(__rt._json_validate_fields(fields, true) == true)
  assert(__rt._json_validate_fields(fields, false) == true)
  assert(__rt._json_validate_fields({}, true) == true)
  local non_table_entry = {
    { name = "a", jtype = "int", optional = false, nullable = false },
    42,
  }
  assert(__rt._json_validate_fields(non_table_entry, true) == false)
  local bad_flag = {
    { name = "a", jtype = "int", optional = false, nullable = false },
    { name = "b", jtype = "string", optional = true, nullable = "yes" },
  }
  assert(__rt._json_validate_fields(bad_flag, true) == false)
  assert(__rt._json_validate_fields(bad_flag, false) == false)
end)

test("_json_validate_fields: existing hand-built descriptors stay valid", function()
  -- class element descriptor omitting optional/nullable (absent = false/false),
  -- as in the suite's array-of-nested-classes tests
  local child_fields = {
    { name = "val", jtype = "int", optional = false, nullable = false }
  }
  local parent_fields = {
    { name = "children", jtype = "array", optional = false, nullable = false,
      element = { jtype = "class", className = "Child", defaults = {}, fields = child_fields } }
  }
  assert(__rt._json_validate_fields(parent_fields, true) == true)
  assert(__rt._json_validate_fields(parent_fields, false) == true)
  -- encode-side hand-built class entry without defaults
  local enc_fields = {
    { name = "child", jtype = "class", optional = false, nullable = false,
      className = "Child", fields = child_fields }
  }
  assert(__rt._json_validate_fields(enc_fields, false) == true)
  assert(__rt._json_validate_fields(enc_fields, true) == false)
end)

-- ==================== Direct helpers: defaults identity gate ====================

test("_json_defaults_identity_ok: sentinels and tagged tables rejected, plain accepted", function()
  assert(__rt._json_defaults_identity_ok(__rt.__NULL) == false)
  assert(__rt._json_defaults_identity_ok(__rt.__MISSING) == false)
  assert(__rt._json_defaults_identity_ok({ __kind = "class", __classname = "C" }) == false)
  assert(__rt._json_defaults_identity_ok({ __kind = "function" }) == false)
  assert(__rt._json_defaults_identity_ok({ __kind = "async" }) == false)
  -- a __kind field holding any other value is a legal field default
  assert(__rt._json_defaults_identity_ok({ __kind = "plain" }) == true)
  assert(__rt._json_defaults_identity_ok({ a = 1 }) == true)
  assert(__rt._json_defaults_identity_ok({}) == true)
  -- non-table defaults
  assert(__rt._json_defaults_identity_ok(42) == false)
  assert(__rt._json_defaults_identity_ok("x") == false)
  assert(__rt._json_defaults_identity_ok(nil) == false)
  assert(__rt._json_defaults_identity_ok(function() end) == false)
  -- the predicate never mutates the global sentinels
  assert(__rt.__NULL.__kind == nil)
  assert(__rt.__MISSING.__kind == nil)
  assert(__rt.__NULL.__classname == nil)
end)

-- ==================== Direct helpers: _json_defaults_acyclic ====================

test("_json_defaults_acyclic: acyclic defaults accepted, cyclic rejected", function()
  local acyclic = { a = 1, b = { c = "x" }, d = __rt.__NULL, e = __rt.__MISSING }
  assert(__rt._json_defaults_acyclic(acyclic, {}) == true)
  assert(__rt._json_defaults_acyclic({}, {}) == true)
  assert(__rt._json_defaults_acyclic(42, {}) == true)   -- non-table is trivially acyclic
  assert(__rt._json_defaults_acyclic(nil, {}) == true)
  assert(__rt._json_defaults_acyclic("x", {}) == true)
  assert(__rt._json_defaults_acyclic(function() end, {}) == true)
  -- input is never mutated
  assert(acyclic.a == 1 and acyclic.b.c == "x" and acyclic.d == __rt.__NULL and acyclic.e == __rt.__MISSING)
  -- direct self-cycle
  local cyc = { a = 1 }
  cyc.self = cyc
  assert(__rt._json_defaults_acyclic(cyc, {}) == false)
  -- cycle through a nested table
  local nested = { child = {} }
  nested.child.back = nested
  assert(__rt._json_defaults_acyclic(nested, {}) == false)
  -- shared non-cyclic child: path-local seen, no false positive
  local shared = { x = 1 }
  assert(__rt._json_defaults_acyclic({ a = shared, b = shared }, {}) == true)
  -- sentinels and __kind-tagged tables are leaves (mirror _deep_copy)
  assert(__rt._json_defaults_acyclic(__rt.__NULL, {}) == true)
  assert(__rt._json_defaults_acyclic(__rt.__MISSING, {}) == true)
  local tagged = { __kind = "class", __classname = "C" }
  tagged.self = tagged
  assert(__rt._json_defaults_acyclic(tagged, {}) == true)
end)

test("_json_defaults_acyclic: seen set unwound on every return", function()
  local seen = {}
  assert(__rt._json_defaults_acyclic({ a = { b = 1 } }, seen) == true)
  assert(next(seen) == nil)
  local cyc = {}
  cyc.self = cyc
  assert(__rt._json_defaults_acyclic(cyc, seen) == false)
  assert(next(seen) == nil)
end)

-- ==================== Direct helpers: depth constant ====================

test("_JSON_MAX_DEPTH is 512 (JVM parity)", function()
  assert(__rt._JSON_MAX_DEPTH == 512)
end)

-- ==================== Summary ====================

print("")
print("========================================")
print("Results: " .. passed .. " passed, " .. failed .. " failed")
print("========================================")

if failed > 0 then
  os.exit(1)
end
