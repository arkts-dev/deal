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

-- ==================== Summary ====================

print("")
print("========================================")
print("Results: " .. passed .. " passed, " .. failed .. " failed")
print("========================================")

if failed > 0 then
  os.exit(1)
end
