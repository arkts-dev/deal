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

-- ==================== json_from_json: explicit null on non-nullable field rejected ====================

test("json_from_json: null as json_parse result on non-nullable string field is rejected", function()
  -- json.parse("null") returns __rt.__NULL; an explicit JSON null on a
  -- non-nullable field is a shape violation and fromJson returns nil
  -- (jsonable-runtime-validation D2 — the pre-hardening pin stored the
  -- sentinel as the field value; D2's null gating replaced that
  -- permissiveness with nil-on-failure, no throw).
  local fields = {
    { name = "name", jtype = "string", optional = false, nullable = false }
  }
  local defaults = { name = "" }
  local parsed = { name = __rt.__NULL }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance == nil)
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

test("_json_is_int: int32 boundaries accepted, beyond rejected (D5 narrowing)", function()
  assert(__rt._json_is_int(0) == true)
  assert(__rt._json_is_int(-0) == true)
  assert(__rt._json_is_int(42) == true)
  assert(__rt._json_is_int(-42) == true)
  assert(__rt._json_is_int(2147483647) == true)
  assert(__rt._json_is_int(-2147483648) == true)
  assert(__rt._json_is_int(2147483648) == false)
  assert(__rt._json_is_int(-2147483649) == false)
  -- The former ±(2^53-1) safe-range boundary is outside int32: rejected.
  assert(__rt._json_is_int(9007199254740991) == false)
  assert(__rt._json_is_int(-9007199254740991) == false)
  assert(__rt._json_is_int(1e300) == false)
  assert(__rt._json_is_int(-1e300) == false)
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

test("_json_validate_fields: members must carry a string name", function()
  -- a nameless member would otherwise pass the element rules of
  -- _json_validate_entry and crash the walkers with a raw Lua error
  -- (encode: "attempt to concatenate field 'name' (a nil value)";
  -- decode: "table index is nil" at valid_keys[f.name])
  assert(__rt._json_validate_fields({ { jtype = "int" } }, true) == false)
  assert(__rt._json_validate_fields({ { jtype = "int" } }, false) == false)
  assert(__rt._json_validate_fields(
      { { jtype = "int", optional = true, nullable = false } }, true) == false)
  assert(__rt._json_validate_fields(
      { { jtype = "int", optional = true, nullable = false } }, false) == false)
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

-- ==================== Hardened json_from_json decode matrix (D2) ====================

-- Positive matrix: every primitive jtype with nullable/optional
-- combinations, plus nested class/array/table decoding (Contract 4
-- fromJson columns).

test("decode matrix: positive jtype x nullable x optional combinations", function()
  local fields = {
    { name = "b",  jtype = "boolean", optional = false, nullable = false },
    { name = "i",  jtype = "int",     optional = false, nullable = false },
    { name = "n",  jtype = "number",  optional = false, nullable = false },
    { name = "s",  jtype = "string",  optional = false, nullable = false },
    { name = "t",  jtype = "table",   optional = false, nullable = false },
    { name = "a",  jtype = "array",   optional = false, nullable = false,
      element = { jtype = "int", optional = false, nullable = false } },
    { name = "nb", jtype = "boolean", optional = false, nullable = true },
    { name = "nn", jtype = "number",  optional = false, nullable = true },
    { name = "ob", jtype = "int",     optional = true,  nullable = false },
    { name = "on", jtype = "int",     optional = true,  nullable = true },
  }
  local defaults = {
    b = false, i = 0, n = 0.0, s = "", t = {}, a = {},
    nb = __rt.__NULL, nn = __rt.__NULL, ob = __rt.__MISSING, on = __rt.__MISSING,
  }
  local parsed = {
    b = true, i = -7, n = 2.5, s = "x",
    t = { key = "v", nested = { 1, 2 } },
    a = { 1, 2 },
    nb = __rt.__NULL, nn = 9.5, on = __rt.__NULL,
  }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.b == true)
  assert(instance.i == -7)
  assert(instance.n == 2.5)
  assert(instance.s == "x")
  assert(instance.t.key == "v")
  assert(instance.t.nested[2] == 2)
  assert(#instance.a == 2 and instance.a[1] == 1)
  assert(instance.nb == __rt.__NULL)
  assert(instance.nn == 9.5)
  assert(instance.ob == nil)          -- optional absent → missing
  assert(instance.on == __rt.__NULL)  -- optional+nullable explicit null
end)

test("decode matrix: nullable class/array/table fields accept explicit null", function()
  local child_fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  local child_defaults = { x = 0 }
  local fields = {
    { name = "c", jtype = "class", optional = false, nullable = true,
      className = "Child", defaults = child_defaults, fields = child_fields },
    { name = "a", jtype = "array", optional = false, nullable = true,
      element = { jtype = "int", optional = false, nullable = false } },
    { name = "t", jtype = "table", optional = false, nullable = true },
  }
  local defaults = { c = __rt.__NULL, a = __rt.__NULL, t = __rt.__NULL }
  local parsed = { c = __rt.__NULL, a = __rt.__NULL, t = __rt.__NULL }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.c == __rt.__NULL)
  assert(instance.a == __rt.__NULL)
  assert(instance.t == __rt.__NULL)
end)

test("decode matrix: nested class with value decodes a tagged instance", function()
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
  assert(instance.child.x == 42)
  assert(instance.child.__classname == "Child")
  assert(instance.child.__kind == "class")
end)

test("decode matrix: int[][] nested arrays decode level by level", function()
  local fields = {
    { name = "grid", jtype = "array", optional = false, nullable = false,
      element = { jtype = "array", optional = false, nullable = false,
        element = { jtype = "int", optional = false, nullable = false } } }
  }
  local defaults = { grid = {} }
  local parsed = { grid = { { 1, 2 }, { 3, 4 } } }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(#instance.grid == 2)
  assert(instance.grid[1][1] == 1)
  assert(instance.grid[1][2] == 2)
  assert(instance.grid[2][1] == 3)
  assert(instance.grid[2][2] == 4)
end)

test("decode matrix: nullable class array elements accept explicit null", function()
  local child_fields = {
    { name = "val", jtype = "int", optional = false, nullable = false }
  }
  local child_defaults = { val = 0 }
  local fields = {
    { name = "children", jtype = "array", optional = false, nullable = false,
      element = { jtype = "class", className = "Child",
        defaults = child_defaults, fields = child_fields, nullable = true } }
  }
  local defaults = { children = {} }
  local parsed = { children = { { val = 1 }, __rt.__NULL, { val = 3 } } }
  local instance = __rt.json_from_json("Parent", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(#instance.children == 3)
  assert(instance.children[1].val == 1)
  assert(instance.children[2] == __rt.__NULL)
  assert(instance.children[3].val == 3)
end)

test("decode matrix: element descriptors omitting both flags decode (absent = false/false)", function()
  -- The existing hand-built class element descriptors keep working
  -- unchanged: no optional/nullable keys, element null rejected.
  local child_fields = {
    { name = "val", jtype = "int", optional = false, nullable = false }
  }
  local child_defaults = { val = 0 }
  local fields = {
    { name = "children", jtype = "array", optional = false, nullable = false,
      element = { jtype = "class", className = "Child", defaults = child_defaults, fields = child_fields } }
  }
  local defaults = { children = {} }
  local parsed = { children = { { val = 1 } } }
  local instance = __rt.json_from_json("Parent", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.children[1].val == 1)
  -- absent element flags mean nullable = false: element null rejected
  assert(__rt.json_from_json("Parent", { children = { __rt.__NULL } }, defaults, fields) == nil)
end)

-- Negative matrix: every case returns nil, never throws (the test()
-- harness pcalls each body, so any raw Lua error fails the suite).

test("decode matrix: wrong primitive type per jtype returns nil", function()
  local fields = {
    { name = "i", jtype = "int",     optional = false, nullable = false },
    { name = "b", jtype = "boolean", optional = false, nullable = false },
    { name = "s", jtype = "string",  optional = false, nullable = false },
    { name = "n", jtype = "number",  optional = false, nullable = false },
  }
  local defaults = { i = 0, b = false, s = "", n = 0.0 }
  assert(__rt.json_from_json("C", { i = "x",   b = true,  s = "ok",  n = 1.5 }, defaults, fields) == nil)
  assert(__rt.json_from_json("C", { i = 1,     b = 1,     s = "ok",  n = 1.5 }, defaults, fields) == nil)
  assert(__rt.json_from_json("C", { i = 1,     b = true,  s = 42,    n = 1.5 }, defaults, fields) == nil)
  assert(__rt.json_from_json("C", { i = 1,     b = true,  s = "ok",  n = "true" }, defaults, fields) == nil)
  assert(__rt.json_from_json("C", { i = 1,     b = true,  s = "ok",  n = true }, defaults, fields) == nil)
end)

test("decode matrix: non-nullable null-typed field rejects non-null values", function()
  local fields = {
    { name = "z", jtype = "null", optional = false, nullable = true }
  }
  local defaults = { z = __rt.__NULL }
  assert(__rt.json_from_json("C", { z = __rt.__NULL }, defaults, fields) ~= nil)
  assert(__rt.json_from_json("C", { z = 42 }, defaults, fields) == nil)
  assert(__rt.json_from_json("C", { z = "x" }, defaults, fields) == nil)
  assert(__rt.json_from_json("C", { z = false }, defaults, fields) == nil)
end)

test("decode matrix: explicit null on non-nullable element returns nil", function()
  local fields = {
    { name = "nums", jtype = "array", optional = false, nullable = false,
      element = { jtype = "int", optional = false, nullable = false } }
  }
  local defaults = { nums = {} }
  assert(__rt.json_from_json("C", { nums = { 1, __rt.__NULL, 3 } }, defaults, fields) == nil)
end)

test("decode matrix: non-table raw for class/array/table fields returns nil", function()
  local child_fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  local child_defaults = { x = 0 }
  local fields = {
    { name = "c", jtype = "class", optional = false, nullable = false,
      className = "Child", defaults = child_defaults, fields = child_fields },
    { name = "a", jtype = "array", optional = false, nullable = false,
      element = { jtype = "int", optional = false, nullable = false } },
    { name = "t", jtype = "table", optional = false, nullable = false },
  }
  local defaults = { c = __rt.__MISSING, a = {}, t = {} }
  assert(__rt.json_from_json("C", { c = "not_a_table", a = { 1 }, t = {} }, defaults, fields) == nil)
  assert(__rt.json_from_json("C", { c = { x = 1 }, a = 42, t = {} }, defaults, fields) == nil)
  assert(__rt.json_from_json("C", { c = { x = 1 }, a = { 1 }, t = 42 }, defaults, fields) == nil)
  assert(__rt.json_from_json("C", { c = { x = 1 }, a = { 1 }, t = false }, defaults, fields) == nil)
end)

test("decode matrix: object/array shape cross-wiring returns nil", function()
  local child_fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  local child_defaults = { x = 0 }
  local fields = {
    { name = "c", jtype = "class", optional = false, nullable = false,
      className = "Child", defaults = child_defaults, fields = child_fields },
    { name = "a", jtype = "array", optional = false, nullable = false,
      element = { jtype = "int", optional = false, nullable = false } },
    { name = "t", jtype = "table", optional = false, nullable = false },
  }
  local defaults = { c = __rt.__MISSING, a = {}, t = {} }
  -- object where array expected
  assert(__rt.json_from_json("C", { c = { x = 1 }, a = { key = "v" }, t = {} }, defaults, fields) == nil)
  -- array where class (object) expected: integer keys are not field names
  assert(__rt.json_from_json("C", { c = { 1, 2 }, a = { 1 }, t = {} }, defaults, fields) == nil)
  -- array where table expected (D7: fromJson table fields accept only objects)
  assert(__rt.json_from_json("C", { c = { x = 1 }, a = { 1 }, t = { 1, 2 } }, defaults, fields) == nil)
end)

test("decode matrix: table field accepts objects with nested arrays (D7 asymmetry)", function()
  local fields = {
    { name = "t", jtype = "table", optional = false, nullable = false }
  }
  local defaults = { t = {} }
  local parsed = { t = { values = { { 1, 2 }, { 3, 4 } } } }
  local instance = __rt.json_from_json("C", parsed, defaults, fields)
  assert(instance ~= nil)
  assert(instance.t.values[1][1] == 1)
  assert(instance.t.values[2][2] == 4)
end)

test("decode matrix: table field with non-JSON leaves returns nil", function()
  local fields = {
    { name = "t", jtype = "table", optional = false, nullable = false }
  }
  local defaults = { t = {} }
  assert(__rt.json_from_json("C", { t = { fn = function() end } }, defaults, fields) == nil)
  assert(__rt.json_from_json("C", { t = { w = { __kind = "function" } } }, defaults, fields) == nil)
  assert(__rt.json_from_json("C", { t = { c = { __kind = "class", __classname = "C" } } }, defaults, fields) == nil)
  assert(__rt.json_from_json("C", { t = { x = 0/0 } }, defaults, fields) == nil)
  assert(__rt.json_from_json("C", { t = { x = 1/0 } }, defaults, fields) == nil)
end)

test("decode matrix: non-table parsed (scalar/function/nil) returns nil", function()
  local fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  local defaults = { x = 0 }
  assert(__rt.json_from_json("C", 42, defaults, fields) == nil)
  assert(__rt.json_from_json("C", "x", defaults, fields) == nil)
  assert(__rt.json_from_json("C", true, defaults, fields) == nil)
  assert(__rt.json_from_json("C", function() end, defaults, fields) == nil)
  assert(__rt.json_from_json("C", nil, defaults, fields) == nil)
end)

test("decode matrix: parsed __NULL sentinel returns nil", function()
  local fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  local defaults = { x = 0 }
  assert(__rt.json_from_json("C", __rt.__NULL, defaults, fields) == nil)
end)

test("decode matrix: non-table defaults and fields return nil", function()
  local fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  local defaults = { x = 0 }
  assert(__rt.json_from_json("C", {}, 42, fields) == nil)
  assert(__rt.json_from_json("C", {}, "x", fields) == nil)
  assert(__rt.json_from_json("C", {}, nil, fields) == nil)
  assert(__rt.json_from_json("C", {}, function() end, fields) == nil)
  assert(__rt.json_from_json("C", {}, defaults, 42) == nil)
  assert(__rt.json_from_json("C", {}, defaults, "x") == nil)
  assert(__rt.json_from_json("C", {}, defaults, nil) == nil)
end)

test("decode matrix: sentinel/identity-preserved defaults rejected, sentinels unmutated", function()
  local fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  assert(__rt.json_from_json("C", {}, __rt.__NULL, fields) == nil)
  assert(__rt.json_from_json("C", {}, __rt.__MISSING, fields) == nil)
  local class_instance = { __kind = "class", __classname = "C", x = 1 }
  assert(__rt.json_from_json("C", {}, class_instance, fields) == nil)
  assert(__rt.json_from_json("C", {}, { __kind = "function" }, fields) == nil)
  assert(__rt.json_from_json("C", {}, { __kind = "async" }, fields) == nil)
  -- the global sentinels carry no tag or field value after the calls
  assert(__rt.__NULL.__kind == nil)
  assert(__rt.__NULL.__classname == nil)
  assert(__rt.__MISSING.__kind == nil)
  assert(__rt.__MISSING.__classname == nil)
  assert(__rt.__NULL.x == nil)
  -- the tagged class-instance defaults was neither overlaid nor re-tagged
  assert(class_instance.__kind == "class")
  assert(class_instance.__classname == "C")
  assert(class_instance.x == 1)
end)

test("decode matrix: defaults table with a plain __kind value accepted", function()
  local fields = {}
  local defaults = { __kind = "plain" }
  local instance = __rt.json_from_json("C", {}, defaults, fields)
  assert(instance ~= nil)
  assert(instance.__classname == "C")
  assert(instance.__kind == "class")
end)

test("decode matrix: malformed descriptor entries return nil", function()
  local defaults = {}
  -- non-table entry
  assert(__rt.json_from_json("C", {}, defaults, { 42 }) == nil)
  -- non-string name
  assert(__rt.json_from_json("C", {}, defaults,
      { { name = 42, jtype = "int", optional = false, nullable = false } }) == nil)
  -- unknown jtype
  assert(__rt.json_from_json("C", {}, defaults,
      { { name = "x", jtype = "bytes", optional = false, nullable = false } }) == nil)
  assert(__rt.json_from_json("C", {}, defaults,
      { { name = "x", jtype = "function", optional = false, nullable = false } }) == nil)
  -- missing element
  assert(__rt.json_from_json("C", {}, defaults,
      { { name = "a", jtype = "array", optional = false, nullable = false } }) == nil)
  -- non-table element
  assert(__rt.json_from_json("C", {}, defaults,
      { { name = "a", jtype = "array", optional = false, nullable = false, element = "int" } }) == nil)
  -- class entry missing className / defaults / fields on the decode path
  assert(__rt.json_from_json("C", {}, defaults,
      { { name = "c", jtype = "class", optional = false, nullable = false, defaults = {}, fields = {} } }) == nil)
  assert(__rt.json_from_json("C", {}, defaults,
      { { name = "c", jtype = "class", optional = false, nullable = false, className = "C", fields = {} } }) == nil)
  assert(__rt.json_from_json("C", {}, defaults,
      { { name = "c", jtype = "class", optional = false, nullable = false, className = "C", defaults = {} } }) == nil)
  -- non-string className
  assert(__rt.json_from_json("C", {}, defaults,
      { { name = "c", jtype = "class", optional = false, nullable = false, className = 42, defaults = {}, fields = {} } }) == nil)
end)

test("decode matrix: nameless field entry returns nil (no raw Lua error)", function()
  local defaults = {}
  -- a nameless member would otherwise pass the element rules and crash
  -- the key gate with a raw "table index is nil" at valid_keys[f.name]
  assert(__rt.json_from_json("C", {}, defaults, { { jtype = "int" } }) == nil)
  assert(__rt.json_from_json("C", {}, defaults,
      { { jtype = "int", optional = true, nullable = false } }) == nil)
end)

test("decode matrix: nameless entry inside nested class fields returns nil", function()
  local parent_fields = {
    { name = "child", jtype = "class", optional = false, nullable = false,
      className = "Child", defaults = {}, fields = { { jtype = "int" } } }
  }
  local defaults = { child = __rt.__MISSING }
  assert(__rt.json_from_json("Parent", { child = {} }, defaults, parent_fields) == nil)
end)

test("decode matrix: class element missing className/defaults/fields returns nil", function()
  local child_fields = {
    { name = "val", jtype = "int", optional = false, nullable = false }
  }
  local defaults = { children = {} }
  -- class element missing className (defaults/fields present)
  local no_classname = {
    { name = "children", jtype = "array", optional = false, nullable = false,
      element = { jtype = "class", defaults = { val = 0 }, fields = child_fields } }
  }
  assert(__rt.json_from_json("Parent", { children = { { val = 1 } } }, defaults, no_classname) == nil)
  -- class element missing fields (className/defaults present)
  local no_fields = {
    { name = "children", jtype = "array", optional = false, nullable = false,
      element = { jtype = "class", className = "Child", defaults = { val = 0 } } }
  }
  assert(__rt.json_from_json("Parent", { children = { { val = 1 } } }, defaults, no_fields) == nil)
  -- class element missing defaults on the decode path (className/fields present)
  local no_defaults = {
    { name = "children", jtype = "array", optional = false, nullable = false,
      element = { jtype = "class", className = "Child", fields = child_fields } }
  }
  assert(__rt.json_from_json("Parent", { children = { { val = 1 } } }, defaults, no_defaults) == nil)
  -- non-string element className
  local bad_classname = {
    { name = "children", jtype = "array", optional = false, nullable = false,
      element = { jtype = "class", className = 42, defaults = { val = 0 }, fields = child_fields } }
  }
  assert(__rt.json_from_json("Parent", { children = { { val = 1 } } }, defaults, bad_classname) == nil)
end)

test("decode matrix: field-entry boolean flag violations return nil", function()
  local defaults = { x = 0 }
  -- nullable = "yes" (truthy non-boolean would silently flip semantics)
  assert(__rt.json_from_json("C", {}, defaults,
      { { name = "x", jtype = "int", optional = false, nullable = "yes" } }) == nil)
  -- optional = 1
  assert(__rt.json_from_json("C", {}, defaults,
      { { name = "x", jtype = "int", optional = 1, nullable = false } }) == nil)
  -- omitted optional key
  assert(__rt.json_from_json("C", {}, defaults,
      { { name = "x", jtype = "int", nullable = false } }) == nil)
  -- omitted nullable key
  assert(__rt.json_from_json("C", {}, defaults,
      { { name = "x", jtype = "int", optional = false } }) == nil)
end)

test("decode matrix: malformed field entry inside nested fields array returns nil", function()
  local child_fields = {
    { name = "y", jtype = "int", optional = false, nullable = "yes" }
  }
  local parent_fields = {
    { name = "child", jtype = "class", optional = false, nullable = false,
      className = "Child", defaults = { y = 0 }, fields = child_fields }
  }
  local parent_defaults = { child = __rt.__MISSING }
  local parsed = { child = { y = 1 } }
  assert(__rt.json_from_json("Parent", parsed, parent_defaults, parent_fields) == nil)
end)

test("decode matrix: present non-boolean element flag returns nil", function()
  local fields = {
    { name = "nums", jtype = "array", optional = false, nullable = false,
      element = { jtype = "int", nullable = "yes" } }
  }
  local defaults = { nums = {} }
  local parsed = { nums = { 1, 2 } }
  assert(__rt.json_from_json("C", parsed, defaults, fields) == nil)
  local fields2 = {
    { name = "nums", jtype = "array", optional = false, nullable = false,
      element = { jtype = "int", optional = 1, nullable = false } }
  }
  assert(__rt.json_from_json("C", parsed, defaults, fields2) == nil)
end)

test("decode matrix: truncated nested-array element at depth returns nil (no raw error)", function()
  -- int[][]: the element descriptor is array-typed but lacks its own
  -- element key — the pre-hardening decoder dereferenced
  -- f.element.element.jtype and threw a raw attempt-to-index error.
  local fields = {
    { name = "grid", jtype = "array", optional = false, nullable = false,
      element = { jtype = "array", optional = false, nullable = false } }
  }
  local defaults = { grid = {} }
  local parsed = { grid = { { 1, 2 }, { 3, 4 } } }
  assert(__rt.json_from_json("C", parsed, defaults, fields) == nil)
end)

test("decode matrix: sparse and mixed-key arrays return nil", function()
  local fields = {
    { name = "nums", jtype = "array", optional = false, nullable = false,
      element = { jtype = "int", optional = false, nullable = false } }
  }
  local defaults = { nums = {} }
  assert(__rt.json_from_json("C", { nums = { 1, nil, 3 } }, defaults, fields) == nil)      -- hole
  assert(__rt.json_from_json("C", { nums = { [1] = 1, [3] = 3 } }, defaults, fields) == nil) -- sparse
  assert(__rt.json_from_json("C", { nums = { [1] = 1, a = 2 } }, defaults, fields) == nil)  -- mixed keys
  assert(__rt.json_from_json("C", { nums = { [2] = 1 } }, defaults, fields) == nil)          -- missing 1
  assert(__rt.json_from_json("C", { nums = { [0] = 1 } }, defaults, fields) == nil)          -- 0-key
end)

test("decode matrix: NaN/Infinity int and number values return nil", function()
  local fields = {
    { name = "i", jtype = "int",    optional = false, nullable = false },
    { name = "n", jtype = "number", optional = false, nullable = false },
  }
  local defaults = { i = 0, n = 0.0 }
  assert(__rt.json_from_json("C", { i = 0/0,  n = 1.5 }, defaults, fields) == nil)  -- NaN on int
  assert(__rt.json_from_json("C", { i = 1/0,  n = 1.5 }, defaults, fields) == nil)  -- +Inf on int
  assert(__rt.json_from_json("C", { i = -1/0, n = 1.5 }, defaults, fields) == nil)  -- -Inf on int
  assert(__rt.json_from_json("C", { i = 1.5,  n = 1.5 }, defaults, fields) == nil)  -- non-integer int
  assert(__rt.json_from_json("C", { i = 1,    n = 0/0 }, defaults, fields) == nil)  -- NaN on number
  assert(__rt.json_from_json("C", { i = 1,    n = 1/0 }, defaults, fields) == nil)  -- +Inf on number
  assert(__rt.json_from_json("C", { i = 1,    n = -1/0 }, defaults, fields) == nil) -- -Inf on number
end)

test("decode matrix: cyclic parsed data returns nil (class recursion)", function()
  local a_fields = {}
  local b_fields = {}
  a_fields[1] = { name = "b", jtype = "class", optional = false, nullable = false,
    className = "B", defaults = {}, fields = b_fields }
  b_fields[1] = { name = "a", jtype = "class", optional = false, nullable = false,
    className = "A", defaults = {}, fields = a_fields }
  local a = {}
  local b = {}
  a.b = b
  b.a = a
  assert(__rt.json_from_json("A", a, {}, a_fields) == nil)
  -- direct self-cycle through a class field
  local self_fields = {
    { name = "self", jtype = "class", optional = false, nullable = false,
      className = "S", defaults = {}, fields = {} }
  }
  local self_raw = {}
  self_raw.self = self_raw
  assert(__rt.json_from_json("S", self_raw, {}, self_fields) == nil)
end)

test("decode matrix: cyclic parsed data returns nil (array and table values)", function()
  -- cyclic nested array
  local arr_fields = {
    { name = "grid", jtype = "array", optional = false, nullable = false,
      element = { jtype = "array", optional = false, nullable = false,
        element = { jtype = "int", optional = false, nullable = false } } }
  }
  local grid = {}
  grid[1] = grid
  assert(__rt.json_from_json("C", { grid = grid }, { grid = {} }, arr_fields) == nil)
  -- cyclic table datum
  local tbl_fields = {
    { name = "t", jtype = "table", optional = false, nullable = false }
  }
  local t = {}
  t.self = t
  assert(__rt.json_from_json("C", { t = t }, { t = {} }, tbl_fields) == nil)
  -- table datum referencing the parsed root
  local root = {}
  root.t = {}
  root.t.back = root
  assert(__rt.json_from_json("C", root, { t = {} }, tbl_fields) == nil)
end)

test("decode matrix: cyclic defaults return nil", function()
  local fields = {}
  local defaults = {}
  defaults.self = defaults
  assert(__rt.json_from_json("C", {}, defaults, fields) == nil)
  local nested = { child = {} }
  nested.child.back = nested
  assert(__rt.json_from_json("C", {}, nested, fields) == nil)
end)

test("decode matrix: table-value depth boundary 513 accepted, 514 rejected", function()
  local fields = {
    { name = "data", jtype = "table", optional = false, nullable = false }
  }
  local defaults = { data = {} }
  local function build_chain(n)
    local root = {}
    local cur = root
    for i = 2, n do
      cur.child = {}
      cur = cur.child
    end
    cur.leaf = "x"
    return root
  end
  local ok = __rt.json_from_json("C", { data = build_chain(513) }, defaults, fields)
  assert(ok ~= nil)
  assert(__rt.json_from_json("C", { data = build_chain(514) }, defaults, fields) == nil)
end)

test("decode matrix: nested-array depth boundary 512 accepted, 513 rejected", function()
  local function build(n)
    -- n nested array levels ending in an int element
    local elem = { jtype = "int", optional = false, nullable = false }
    for i = 1, n do
      elem = { jtype = "array", optional = false, nullable = false, element = elem }
    end
    local fields = {
      { name = "grid", jtype = "array", optional = false, nullable = false, element = elem }
    }
    local function build_data(levels)
      if levels == 0 then
        return 1
      end
      return { build_data(levels - 1) }
    end
    -- the field value is one array level plus the element chain's n
    -- levels: the deepest element-array container decodes at depth n.
    return fields, build_data(n + 1)
  end
  local defaults = { grid = {} }
  local f512, d512 = build(512)
  assert(__rt.json_from_json("C", { grid = d512 }, defaults, f512) ~= nil)
  local f513, d513 = build(513)
  assert(__rt.json_from_json("C", { grid = d513 }, defaults, f513) == nil)
end)

test("decode matrix: nested-class depth boundary 513 accepted, 514 rejected", function()
  local function build_chain(n)
    -- n nested class objects; the deepest is an empty object (defaulted).
    -- entries[i] is the single field entry of level i's fields array;
    -- its fields points at the level-(i+1) fields ARRAY.
    local entries = {}
    local defaults = {}
    for i = 1, n do
      defaults[i] = { f = __rt.__MISSING }
      entries[i] = { name = "f", jtype = "class", optional = false, nullable = false,
        className = "L" .. i, defaults = {}, fields = {} }
    end
    for i = 1, n - 1 do
      entries[i].className = "L" .. (i + 1)
      entries[i].defaults = defaults[i + 1]
      entries[i].fields = { entries[i + 1] }
    end
    local parsed = {}
    local cur = parsed
    for i = 1, n - 1 do
      local next_tbl = {}
      cur.f = next_tbl
      cur = next_tbl
    end
    return { entries[1] }, defaults[1], parsed
  end
  local f513, d513, p513 = build_chain(513)
  assert(__rt.json_from_json("L1", p513, d513, f513) ~= nil)
  local f514, d514, p514 = build_chain(514)
  assert(__rt.json_from_json("L1", p514, d514, f514) == nil)
end)

test("decode matrix: empty parsed table decodes as the defaulted instance ([]/{} collapse)", function()
  local fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  local defaults = { x = 5 }
  local from_obj = __rt.json_from_json("C", {}, defaults, fields)
  assert(from_obj ~= nil)
  assert(from_obj.x == 5)
  assert(from_obj.__kind == "class")
  assert(from_obj.__classname == "C")
  -- json.parse("[]") and json.parse("{}") produce the same fresh empty
  -- table, so both decode identically (D2a).
end)

test("decode matrix: []/{} collapse holds one level down", function()
  local child_fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  local child_defaults = { x = 7 }
  local parent_fields = {
    { name = "child", jtype = "class", optional = false, nullable = false,
      className = "Child", defaults = child_defaults, fields = child_fields },
    { name = "items", jtype = "array", optional = false, nullable = false,
      element = { jtype = "int", optional = false, nullable = false } },
    { name = "meta", jtype = "table", optional = false, nullable = false },
  }
  local parent_defaults = { child = __rt.__MISSING, items = {}, meta = {} }
  local parsed = { child = {}, items = {}, meta = {} }
  local instance = __rt.json_from_json("Parent", parsed, parent_defaults, parent_fields)
  assert(instance ~= nil)
  assert(instance.child.x == 7)
  assert(instance.child.__kind == "class")
  assert(#instance.items == 0)
  assert(next(instance.meta) == nil)
end)

-- ==================== json_to_json encode matrix (ISSUE-0187 T3) ====================
-- Every negative case asserts the raised DEAL error's code AND message;
-- a raw Lua error fails the assertion that the error value is a table.

local function assert_error(fn, expected_code, expected_message)
  local ok, err = pcall(fn)
  assert(ok == false, "expected " .. expected_code .. " but no error was raised")
  assert(type(err) == "table",
    "expected DEAL error table, got raw Lua error: " .. tostring(err))
  assert(err.code == expected_code,
    "expected code " .. expected_code .. ", got " .. tostring(err.code))
  if expected_message ~= nil then
    assert(err.message == expected_message,
      "expected message '" .. expected_message .. "', got '"
      .. tostring(err.message) .. "'")
  end
end

-- ---- Top-level identity gate (D3 step 1) ----

test("json_to_json top-level identity: non-table and untagged values raise E8001", function()
  local fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  assert_error(function() __rt.json_to_json("C", 42, fields) end,
    "E8001", "expected class instance")
  assert_error(function() __rt.json_to_json("C", nil, fields) end,
    "E8001", "expected class instance")
  assert_error(function() __rt.json_to_json("C", "s", fields) end,
    "E8001", "expected class instance")
  assert_error(function() __rt.json_to_json("C", { __classname = "C" }, fields) end,
    "E8001", "expected class instance")
  assert_error(function() __rt.json_to_json("C", { __kind = "async", __classname = "C" }, fields) end,
    "E8001", "expected class instance")
end)

test("json_to_json top-level identity: __classname mismatch raises E8001", function()
  local fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  assert_error(function()
    __rt.json_to_json("C", { x = 1, __classname = "Other", __kind = "class" }, fields)
  end, "E8001", "expected instance of C, got Other")
end)

-- ---- Positive matrix: jtype x nullable x optional ----

test("json_to_json encode matrix: null field", function()
  local fields = {
    { name = "data", jtype = "null", optional = false, nullable = true }
  }
  local instance = { data = __rt.__NULL, __classname = "C", __kind = "class" }
  local result = __rt.json_to_json("C", instance, fields)
  assert(result ~= nil)
  assert(result.data == __rt.__NULL)
  -- jtype null accepts only __NULL
  assert_error(function()
    __rt.json_to_json("C", { data = 42, __classname = "C", __kind = "class" }, fields)
  end, "E8001", "expected null")
end)

test("json_to_json encode matrix: boolean field", function()
  local fields = {
    { name = "flag", jtype = "boolean", optional = true, nullable = false }
  }
  local set = { flag = true, __classname = "C", __kind = "class" }
  assert(__rt.json_to_json("C", set, fields).flag == true)
  -- missing optional omitted
  assert(__rt.json_to_json("C", { __classname = "C", __kind = "class" }, fields).flag == nil)
  assert_error(function()
    __rt.json_to_json("C", { flag = 1, __classname = "C", __kind = "class" }, fields)
  end, "E8001", "expected boolean")
end)

test("json_to_json encode matrix: int field", function()
  local fields = {
    { name = "n", jtype = "int", optional = false, nullable = false }
  }
  assert(__rt.json_to_json("C", { n = 42, __classname = "C", __kind = "class" }, fields).n == 42)
  assert(__rt.json_to_json("C", { n = -0, __classname = "C", __kind = "class" }, fields).n == 0)
  assert_error(function()
    __rt.json_to_json("C", { n = "x", __classname = "C", __kind = "class" }, fields)
  end, "E8001", "expected int")
  assert_error(function()
    __rt.json_to_json("C", { n = 3.5, __classname = "C", __kind = "class" }, fields)
  end, "E8001", "expected int, got non-integer number")
  assert_error(function()
    __rt.json_to_json("C", { n = 0/0, __classname = "C", __kind = "class" }, fields)
  end, "E8001", "expected int, got NaN")
  assert_error(function()
    __rt.json_to_json("C", { n = 1/0, __classname = "C", __kind = "class" }, fields)
  end, "E8001", "expected int, got infinity")
  assert_error(function()
    __rt.json_to_json("C", { n = 9007199254740992, __classname = "C", __kind = "class" }, fields)
  end, "E8004", "int out of range")
end)

test("json_to_json encode matrix: number field", function()
  local fields = {
    { name = "p", jtype = "number", optional = false, nullable = false }
  }
  assert(__rt.json_to_json("C", { p = 3.14, __classname = "C", __kind = "class" }, fields).p == 3.14)
  assert_error(function()
    __rt.json_to_json("C", { p = "true", __classname = "C", __kind = "class" }, fields)
  end, "E8001", "expected number")
  assert_error(function()
    __rt.json_to_json("C", { p = 0/0, __classname = "C", __kind = "class" }, fields)
  end, "E8001", "cannot encode NaN as JSON")
  assert_error(function()
    __rt.json_to_json("C", { p = 1/0, __classname = "C", __kind = "class" }, fields)
  end, "E8001", "cannot encode Infinity as JSON")
  assert_error(function()
    __rt.json_to_json("C", { p = -1/0, __classname = "C", __kind = "class" }, fields)
  end, "E8001", "cannot encode Infinity as JSON")
end)

test("json_to_json encode matrix: string field", function()
  local fields = {
    { name = "s", jtype = "string", optional = false, nullable = false }
  }
  assert(__rt.json_to_json("C", { s = "hi", __classname = "C", __kind = "class" }, fields).s == "hi")
  assert_error(function()
    __rt.json_to_json("C", { s = 42, __classname = "C", __kind = "class" }, fields)
  end, "E8001", "expected string")
end)

test("json_to_json encode matrix: table field (object and dense-array datum)", function()
  local fields = {
    { name = "data", jtype = "table", optional = false, nullable = false }
  }
  -- string-keyed object with nested arrays inside object values (D7)
  local obj = { count = 2, name = "x", values = {{1, 2}, {3, 4}}, nil_leaf = __rt.__NULL }
  local result = __rt.json_to_json("C", { data = obj, __classname = "C", __kind = "class" }, fields)
  assert(result.data == obj)  -- emitted by reference, never copied
  -- dense-array datum
  local arr = {1, 2.5, "s", false, __rt.__NULL}
  local result2 = __rt.json_to_json("C", { data = arr, __classname = "C", __kind = "class" }, fields)
  assert(result2.data == arr)
  -- non-table raw
  assert_error(function()
    __rt.json_to_json("C", { data = 42, __classname = "C", __kind = "class" }, fields)
  end, "E8001", "expected table")
end)

test("json_to_json encode matrix: nested class field", function()
  local child_fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  local fields = {
    { name = "child", jtype = "class", optional = false, nullable = false,
      className = "Child", fields = child_fields }
  }
  local child = { x = 5, __classname = "Child", __kind = "class" }
  local result = __rt.json_to_json("Parent",
    { child = child, __classname = "Parent", __kind = "class" }, fields)
  assert(result.child ~= nil and result.child.x == 5)
  assert(result.child ~= child)  -- fresh nested encoding
  assert_error(function()
    __rt.json_to_json("Parent", { child = 42, __classname = "Parent", __kind = "class" }, fields)
  end, "E8001", "expected class instance")
  assert_error(function()
    __rt.json_to_json("Parent",
      { child = { x = 5, __classname = "Other", __kind = "class" },
        __classname = "Parent", __kind = "class" }, fields)
  end, "E8001", "expected instance of Child, got Other")
end)

test("json_to_json encode matrix: array field incl. int[][]", function()
  local fields = {
    { name = "m", jtype = "array", optional = false, nullable = false,
      element = { jtype = "array", optional = false, nullable = false,
        element = { jtype = "int", optional = false, nullable = false } } }
  }
  local instance = { m = {{1, 2}, {3, 4}}, __classname = "C", __kind = "class" }
  local result = __rt.json_to_json("C", instance, fields)
  assert(#result.m == 2)
  assert(result.m[1][1] == 1 and result.m[1][2] == 2)
  assert(result.m[2][1] == 3 and result.m[2][2] == 4)
  -- empty array
  local empty = __rt.json_to_json("C", { m = {}, __classname = "C", __kind = "class" }, fields)
  assert(#empty.m == 0)
  -- non-table raw
  assert_error(function()
    __rt.json_to_json("C", { m = 42, __classname = "C", __kind = "class" }, fields)
  end, "E8001", "expected array")
end)

test("json_to_json encode matrix: nullable __NULL emission for every jtype", function()
  local fields = {
    { name = "f_null",   jtype = "null",    optional = false, nullable = true },
    { name = "f_bool",   jtype = "boolean", optional = false, nullable = true },
    { name = "f_int",    jtype = "int",     optional = false, nullable = true },
    { name = "f_number", jtype = "number",  optional = false, nullable = true },
    { name = "f_string", jtype = "string",  optional = false, nullable = true },
    { name = "f_table",  jtype = "table",   optional = false, nullable = true },
    { name = "f_class",  jtype = "class",   optional = false, nullable = true,
      className = "Child",
      fields = { { name = "x", jtype = "int", optional = false, nullable = false } } },
    { name = "f_array",  jtype = "array",   optional = false, nullable = true,
      element = { jtype = "int" } },
  }
  local instance = {
    f_null = __rt.__NULL, f_bool = __rt.__NULL, f_int = __rt.__NULL,
    f_number = __rt.__NULL, f_string = __rt.__NULL, f_table = __rt.__NULL,
    f_class = __rt.__NULL, f_array = __rt.__NULL,
    __classname = "C", __kind = "class",
  }
  local result = __rt.json_to_json("C", instance, fields)
  for _, name in ipairs({"f_null", "f_bool", "f_int", "f_number",
      "f_string", "f_table", "f_class", "f_array"}) do
    assert(result[name] == __rt.__NULL, "expected __NULL for " .. name)
  end
end)

test("json_to_json encode matrix: explicit null gating", function()
  local fields = {
    { name = "s", jtype = "string", optional = false, nullable = true },
    { name = "n", jtype = "string", optional = false, nullable = false },
  }
  local ok = __rt.json_to_json("C",
    { s = __rt.__NULL, n = "v", __classname = "C", __kind = "class" }, fields)
  assert(ok.s == __rt.__NULL and ok.n == "v")
  assert_error(function()
    __rt.json_to_json("C", { s = "v", n = __rt.__NULL, __classname = "C", __kind = "class" }, fields)
  end, "E8001", "explicit null on non-nullable field 'n'")
  -- element null gating: absent element flag means nullable = false
  local arr_fields = {
    { name = "m", jtype = "array", optional = false, nullable = false,
      element = { jtype = "int" } }
  }
  assert_error(function()
    __rt.json_to_json("C", { m = {1, __rt.__NULL}, __classname = "C", __kind = "class" }, arr_fields)
  end, "E8001", "explicit null on non-nullable array element")
  -- hand-built nullable element accepts __NULL
  local null_arr_fields = {
    { name = "m", jtype = "array", optional = false, nullable = false,
      element = { jtype = "int", nullable = true } }
  }
  local res = __rt.json_to_json("C",
    { m = {1, __rt.__NULL}, __classname = "C", __kind = "class" }, null_arr_fields)
  assert(res.m[1] == 1 and res.m[2] == __rt.__NULL)
end)

test("json_to_json encode matrix: missing required field raises E8001", function()
  local fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  assert_error(function()
    __rt.json_to_json("C", { __classname = "C", __kind = "class" }, fields)
  end, "E8001", "missing required field 'x'")
end)

test("json_to_json encode matrix: dense-array shape violations raise E8001", function()
  local fields = {
    { name = "m", jtype = "array", optional = false, nullable = false,
      element = { jtype = "int" } }
  }
  local holes = { [1] = 1, [3] = 3 }
  assert_error(function()
    __rt.json_to_json("C", { m = holes, __classname = "C", __kind = "class" }, fields)
  end, "E8001", "expected dense array")
  local mixed = { [1] = 1, a = 2 }
  assert_error(function()
    __rt.json_to_json("C", { m = mixed, __classname = "C", __kind = "class" }, fields)
  end, "E8001", "expected dense array")
end)

test("json_to_json encode matrix: non-JSON table leaves raise E8001", function()
  local fields = {
    { name = "data", jtype = "table", optional = false, nullable = false }
  }
  local function leaf_error(leaf)
    assert_error(function()
      __rt.json_to_json("C", { data = { leaf = leaf }, __classname = "C", __kind = "class" }, fields)
    end, "E8001", "value is not JSON-shaped")
  end
  leaf_error(function() end)
  leaf_error({ __kind = "function" })
  leaf_error({ __kind = "class", __classname = "X" })
  leaf_error({ __kind = "async" })
  leaf_error(0/0)     -- NaN
  leaf_error(1/0)     -- +Infinity
  leaf_error(-1/0)    -- -Infinity
  -- top-level tagged datum
  assert_error(function()
    __rt.json_to_json("C",
      { data = { __kind = "class", __classname = "X" }, __classname = "C", __kind = "class" }, fields)
  end, "E8001", "value is not JSON-shaped")
  -- mixed-shape and sparse data
  assert_error(function()
    __rt.json_to_json("C",
      { data = { [1] = "x", a = 1 }, __classname = "C", __kind = "class" }, fields)
  end, "E8001", "value is not JSON-shaped")
  assert_error(function()
    __rt.json_to_json("C",
      { data = { [1] = "x", [3] = "z" }, __classname = "C", __kind = "class" }, fields)
  end, "E8001", "value is not JSON-shaped")
end)

-- ---- Cycles (D4) ----

test("json_to_json encode matrix: cyclic class instance raises E8001", function()
  local fields = {
    { name = "self_ref", jtype = "class", optional = true, nullable = false,
      className = "C", fields = nil }
  }
  fields[1].fields = fields  -- self-referential descriptor: self_ref is a C again
  local instance = { __classname = "C", __kind = "class" }
  instance.self_ref = instance
  assert_error(function() __rt.json_to_json("C", instance, fields) end,
    "E8001", "cyclic value cannot be encoded as JSON")
end)

test("json_to_json encode matrix: cyclic table datum raises E8001", function()
  local fields = {
    { name = "data", jtype = "table", optional = false, nullable = false }
  }
  local instance = { __classname = "W", __kind = "class" }
  local direct = { a = 1 }
  direct.self = direct
  instance.data = direct
  assert_error(function() __rt.json_to_json("W", instance, fields) end,
    "E8001", "cyclic value cannot be encoded as JSON")
  local indirect = { child = {} }
  indirect.child.back = indirect
  instance.data = indirect
  assert_error(function() __rt.json_to_json("W", instance, fields) end,
    "E8001", "cyclic value cannot be encoded as JSON")
end)

test("json_to_json encode matrix: cyclic array container raises E8001", function()
  local fields = {
    { name = "m", jtype = "array", optional = false, nullable = false,
      element = { jtype = "table" } }
  }
  local arr = {}
  arr[1] = arr
  assert_error(function()
    __rt.json_to_json("C", { m = arr, __classname = "C", __kind = "class" }, fields)
  end, "E8001", "cyclic value cannot be encoded as JSON")
end)

test("json_to_json encode matrix: shared non-cyclic child serializes twice", function()
  local child_fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  local child = { x = 7, __classname = "Child", __kind = "class" }
  local fields = {
    { name = "a", jtype = "class", optional = false, nullable = false,
      className = "Child", fields = child_fields },
    { name = "b", jtype = "class", optional = false, nullable = false,
      className = "Child", fields = child_fields },
  }
  local result = __rt.json_to_json("Parent",
    { a = child, b = child, __classname = "Parent", __kind = "class" }, fields)
  assert(result.a ~= nil and result.a.x == 7)
  assert(result.b ~= nil and result.b.x == 7)
  assert(result.a ~= result.b)  -- two fresh encodings, no false positive
end)

-- ---- Depth limit (D5) ----

test("json_to_json encode matrix: depth boundary 512 accepted, 513 rejected (class chain)", function()
  local fields = {
    { name = "child", jtype = "class", optional = true, nullable = false,
      className = "C", fields = nil }
  }
  fields[1].fields = fields
  local function build_chain(n)  -- n instances: deepest at depth n-1
    local chain = {}
    for i = 1, n do
      chain[i] = { __classname = "C", __kind = "class" }
    end
    for i = 1, n - 1 do
      chain[i].child = chain[i + 1]
    end
    return chain[1]
  end
  local ok_result = __rt.json_to_json("C", build_chain(513), fields)
  assert(ok_result ~= nil)
  assert(ok_result.child ~= nil)
  assert_error(function()
    __rt.json_to_json("C", build_chain(514), fields)
  end, "E8001", "maximum JSON nesting depth (512) exceeded")
end)

test("json_to_json encode matrix: depth boundary 512 accepted, 513 rejected (array chain)", function()
  local function build_desc(n)  -- n nested array levels around an int element
    local desc = { jtype = "int" }
    for _ = 1, n - 1 do
      desc = { jtype = "array", element = desc }
    end
    return desc
  end
  local function build_data(n)  -- n nested single-element arrays around 42
    local data = 42
    for _ = 1, n - 1 do
      data = { data }
    end
    return data
  end
  -- build_data(k) has k-1 array levels around 42; the field m is the
  -- outermost level, so the innermost int is processed at walker depth
  -- k-1. 512 array levels put the int exactly at depth 512 (accepted);
  -- 513 array levels put it at depth 513 (rejected).
  local ok_fields = {
    { name = "m", jtype = "array", optional = false, nullable = false,
      element = build_desc(512) }
  }
  local ok_result = __rt.json_to_json("C",
    { m = build_data(513), __classname = "C", __kind = "class" }, ok_fields)
  assert(ok_result ~= nil)
  local over_fields = {
    { name = "m", jtype = "array", optional = false, nullable = false,
      element = build_desc(513) }
  }
  assert_error(function()
    __rt.json_to_json("C",
      { m = build_data(514), __classname = "C", __kind = "class" }, over_fields)
  end, "E8001", "maximum JSON nesting depth (512) exceeded")
end)

-- ---- Descriptor-entry validation (D3 step 2, encode rules) ----

test("json_to_json encode matrix: non-table fields and non-table entries raise E8001", function()
  local instance = { x = 1, __classname = "C", __kind = "class" }
  assert_error(function() __rt.json_to_json("C", instance, 42) end,
    "E8001", "malformed field descriptors")
  assert_error(function() __rt.json_to_json("C", instance, nil) end,
    "E8001", "malformed field descriptors")
  assert_error(function() __rt.json_to_json("C", instance, { 42 }) end,
    "E8001", "malformed field descriptors")
  assert_error(function() __rt.json_to_json("C", instance,
    { { name = 42, jtype = "int", optional = false, nullable = false } }) end,
    "E8001", "malformed field descriptors")
  assert_error(function() __rt.json_to_json("C", instance,
    { { name = "x", jtype = "function", optional = false, nullable = false } }) end,
    "E8001", "malformed field descriptors")
end)

test("json_to_json encode matrix: nameless field entry raises E8001, never a raw error", function()
  local instance = { x = 1, __classname = "C", __kind = "class" }
  -- a nameless member would otherwise be classified under the element
  -- rules and crash the walker with "attempt to concatenate field
  -- 'name' (a nil value)" (missing required) or "table index is nil"
  assert_error(function() __rt.json_to_json("C", instance, { { jtype = "int" } }) end,
    "E8001", "malformed field descriptors")
  -- nameless + optional=true must not be silently accepted either
  assert_error(function() __rt.json_to_json("C", instance,
    { { jtype = "int", optional = true, nullable = false } }) end,
    "E8001", "malformed field descriptors")
end)

test("json_to_json encode matrix: nameless entry inside nested class fields raises E8001", function()
  local parent_fields = {
    { name = "child", jtype = "class", optional = false, nullable = false,
      className = "Child", fields = { { jtype = "int" } } }
  }
  local child = { x = 1, __classname = "Child", __kind = "class" }
  local parent = { child = child, __classname = "Parent", __kind = "class" }
  assert_error(function() __rt.json_to_json("Parent", parent, parent_fields) end,
    "E8001", "malformed field descriptors")
end)

test("json_to_json encode matrix: field entries require boolean optional/nullable", function()
  local instance = { x = 1, __classname = "C", __kind = "class" }
  assert_error(function() __rt.json_to_json("C", instance,
    { { name = "x", jtype = "int", nullable = false } }) end,
    "E8001", "malformed field descriptors")  -- missing optional
  assert_error(function() __rt.json_to_json("C", instance,
    { { name = "x", jtype = "int", optional = false } }) end,
    "E8001", "malformed field descriptors")  -- missing nullable
  assert_error(function() __rt.json_to_json("C", instance,
    { { name = "x", jtype = "int", optional = 1, nullable = false } }) end,
    "E8001", "malformed field descriptors")  -- truthy non-boolean optional
  assert_error(function() __rt.json_to_json("C", instance,
    { { name = "x", jtype = "int", optional = false, nullable = "yes" } }) end,
    "E8001", "malformed field descriptors")  -- truthy non-boolean nullable
end)

test("json_to_json encode matrix: element flags — present non-boolean rejected, omitted accepted", function()
  local fields = {
    { name = "m", jtype = "array", optional = false, nullable = false,
      element = { jtype = "int" } }
  }
  local instance = { m = {1, 2}, __classname = "C", __kind = "class" }
  -- absent element flags mean false/false: valid, encodes
  local res = __rt.json_to_json("C", instance, fields)
  assert(res.m[1] == 1 and res.m[2] == 2)
  -- present non-boolean element flag is malformed
  assert_error(function() __rt.json_to_json("C", instance,
    { { name = "m", jtype = "array", optional = false, nullable = false,
        element = { jtype = "int", nullable = "yes" } } }) end,
    "E8001", "malformed field descriptors")
  assert_error(function() __rt.json_to_json("C", instance,
    { { name = "m", jtype = "array", optional = false, nullable = false,
        element = { jtype = "int", optional = 1 } } }) end,
    "E8001", "malformed field descriptors")
end)

test("json_to_json encode matrix: truncated nested-array element raises E8001, never a raw error", function()
  local fields = {
    { name = "m", jtype = "array", optional = false, nullable = false,
      element = { jtype = "array", optional = false, nullable = false } }
  }
  local instance = { m = {{1, 2}}, __classname = "C", __kind = "class" }
  assert_error(function() __rt.json_to_json("C", instance, fields) end,
    "E8001", "malformed field descriptors")
end)

test("json_to_json encode matrix: malformed entry inside nested class fields raises E8001", function()
  local child_fields = {
    { name = "x", jtype = "int", optional = false, nullable = "yes" }
  }
  local parent_fields = {
    { name = "child", jtype = "class", optional = false, nullable = false,
      className = "Child", fields = child_fields }
  }
  local child = { x = 1, __classname = "Child", __kind = "class" }
  local parent = { child = child, __classname = "Parent", __kind = "class" }
  assert_error(function() __rt.json_to_json("Parent", parent, parent_fields) end,
    "E8001", "malformed field descriptors")
end)

test("json_to_json encode matrix: malformed flags at depth inside nested class fields", function()
  local child_fields = {
    { name = "x", jtype = "int", nullable = false }  -- missing optional at depth
  }
  local parent_fields = {
    { name = "child", jtype = "class", optional = false, nullable = false,
      className = "Child", fields = child_fields }
  }
  local child = { x = 1, __classname = "Child", __kind = "class" }
  local parent = { child = child, __classname = "Parent", __kind = "class" }
  assert_error(function() __rt.json_to_json("Parent", parent, parent_fields) end,
    "E8001", "malformed field descriptors")
end)

-- ---- Descriptor-validation asymmetry pins (Contract 3 encode rules) ----

test("json_to_json asymmetry: class entry with className/fields but no defaults encodes", function()
  local child_fields = {
    { name = "x", jtype = "int", optional = false, nullable = false }
  }
  local fields = {
    { name = "child", jtype = "class", optional = false, nullable = false,
      className = "Child", fields = child_fields }
  }
  local child = { x = 1, __classname = "Child", __kind = "class" }
  local result = __rt.json_to_json("Parent",
    { child = child, __classname = "Parent", __kind = "class" }, fields)
  assert(result.child ~= nil and result.child.x == 1)
  -- missing className -> E8001
  assert_error(function() __rt.json_to_json("Parent",
    { child = child, __classname = "Parent", __kind = "class" },
    { { name = "child", jtype = "class", optional = false, nullable = false,
        fields = child_fields } }) end,
    "E8001", "malformed field descriptors")
  -- missing fields -> E8001
  assert_error(function() __rt.json_to_json("Parent",
    { child = child, __classname = "Parent", __kind = "class" },
    { { name = "child", jtype = "class", optional = false, nullable = false,
        className = "Child" } }) end,
    "E8001", "malformed field descriptors")
end)

test("json_to_json asymmetry: class element with className/fields but no defaults encodes", function()
  local child_fields = {
    { name = "val", jtype = "int", optional = false, nullable = false }
  }
  local fields = {
    { name = "children", jtype = "array", optional = false, nullable = false,
      element = { jtype = "class", className = "Child", fields = child_fields } }
  }
  local c1 = { val = 1, __classname = "Child", __kind = "class" }
  local c2 = { val = 2, __classname = "Child", __kind = "class" }
  local result = __rt.json_to_json("Parent",
    { children = {c1, c2}, __classname = "Parent", __kind = "class" }, fields)
  assert(#result.children == 2)
  assert(result.children[1].val == 1 and result.children[2].val == 2)
  -- missing className on the class element -> E8001
  assert_error(function() __rt.json_to_json("Parent",
    { children = {c1}, __classname = "Parent", __kind = "class" },
    { { name = "children", jtype = "array", optional = false, nullable = false,
        element = { jtype = "class", fields = child_fields } } }) end,
    "E8001", "malformed field descriptors")
  -- missing fields on the class element -> E8001
  assert_error(function() __rt.json_to_json("Parent",
    { children = {c1}, __classname = "Parent", __kind = "class" },
    { { name = "children", jtype = "array", optional = false, nullable = false,
        element = { jtype = "class", className = "Child" } } }) end,
    "E8001", "malformed field descriptors")
end)

test("json_to_json asymmetry at depth: nested class fields entry without defaults encodes", function()
  local grand_fields = {
    { name = "v", jtype = "int", optional = false, nullable = false }
  }
  local child_fields = {
    { name = "grand", jtype = "class", optional = false, nullable = false,
      className = "Grand", fields = grand_fields }  -- no defaults at depth
  }
  local parent_fields = {
    { name = "child", jtype = "class", optional = false, nullable = false,
      className = "Child", fields = child_fields }
  }
  local grand = { v = 3, __classname = "Grand", __kind = "class" }
  local child = { grand = grand, __classname = "Child", __kind = "class" }
  local result = __rt.json_to_json("Parent",
    { child = child, __classname = "Parent", __kind = "class" }, parent_fields)
  assert(result.child ~= nil and result.child.grand ~= nil)
  assert(result.child.grand.v == 3)
end)

-- ---- Post-state (Contract 2) ----

test("json_to_json does not mutate the instance or nested tables", function()
  local fields = {
    { name = "data", jtype = "table", optional = false, nullable = false },
    { name = "tags", jtype = "array", optional = false, nullable = false,
      element = { jtype = "int" } },
  }
  local datum = { a = 1, b = { c = 2 } }
  local tags = { 1, 2 }
  local instance = { data = datum, tags = tags, __classname = "C", __kind = "class" }
  local result = __rt.json_to_json("C", instance, fields)
  assert(result ~= instance)          -- fresh outer table
  assert(result.data == datum)        -- table datum by reference
  assert(result.tags ~= tags)         -- fresh encoded array
  assert(result.tags[1] == 1 and result.tags[2] == 2)
  assert(instance.data == datum and instance.tags == tags)
  assert(datum.a == 1 and datum.b.c == 2 and #tags == 2)
  assert(instance.__classname == "C" and instance.__kind == "class")
end)

-- ==================== ISSUE-0188 cross-direction roundtrip invariants (step 4) ====================
-- Each test below exercises the combined hardened pair end to end:
--   decode    — __rt.json_from_json builds the tagged instance from the
--               parsed JSON input (decode child, ISSUE-0186);
--   encode    — __rt.json_to_json produces the JSON-shaped table from
--               that instance (encode child, ISSUE-0187);
--   re-decode — __rt.json_from_json rebuilds the instance from the
--               encoded table;
--   compare   — the re-decoded instance is deep-equal to the original
--               decoded instance (every field value, the
--               missing/absent state, and the __classname/__kind tags),
--               and re-encoding the re-decoded instance reproduces the
--               first encoded table (fixed point).

-- Local deep-equality for JSON-shaped instance graphs: identity shortcut
-- (covers the __NULL sentinel and by-reference table datum aliasing),
-- then key-symmetric recursive comparison for tables; non-table values
-- compare by ==. A missing key (nil) is equal only to another nil.
local function _jsonable_deep_equal(a, b)
  if a == b then return true end
  if type(a) ~= "table" or type(b) ~= "table" then return false end
  local a_keys, b_keys = 0, 0
  for k, v in pairs(a) do
    a_keys = a_keys + 1
    if not _jsonable_deep_equal(v, b[k]) then return false end
  end
  for k in pairs(b) do
    b_keys = b_keys + 1
    if a[k] == nil then return false end
  end
  return a_keys == b_keys
end

test("ISSUE-0188 roundtrip invariant: optional-nullable three-state", function()
  local fields = {
    { name = "missing_field", jtype = "string", optional = true, nullable = true },
    { name = "null_field", jtype = "string", optional = true, nullable = true },
    { name = "value_field", jtype = "string", optional = true, nullable = true },
    { name = "required", jtype = "string", optional = false, nullable = false },
  }
  local defaults = {
    missing_field = __rt.__MISSING,
    null_field = __rt.__NULL,
    value_field = __rt.__MISSING,
    required = "",
  }
  -- JSON input carrying null and value states; the missing state is
  -- simply absent from the object.
  local parsed = {
    null_field = __rt.__NULL,
    value_field = "hello",
    required = "r",
  }

  -- decode
  local original = __rt.json_from_json("TriState", parsed, defaults, fields)
  assert(original ~= nil)
  assert(original.missing_field == nil)
  assert(original.null_field == __rt.__NULL)
  assert(original.value_field == "hello")
  assert(original.required == "r")

  -- encode
  local encoded = __rt.json_to_json("TriState", original, fields)
  assert(encoded.missing_field == nil)
  assert(encoded.null_field == __rt.__NULL)
  assert(encoded.value_field == "hello")
  assert(encoded.required == "r")

  -- re-decode
  local redecoded = __rt.json_from_json("TriState", encoded, defaults, fields)
  assert(redecoded ~= nil)

  -- compare: the re-decoded instance is deep-equal to the original
  assert(_jsonable_deep_equal(original, redecoded))
  -- fixed point: the re-decoded instance encodes to the same table
  assert(_jsonable_deep_equal(encoded, __rt.json_to_json("TriState", redecoded, fields)))
end)

test("ISSUE-0188 roundtrip invariant: nested class", function()
  local child_fields = {
    { name = "x", jtype = "int", optional = false, nullable = false },
    { name = "y", jtype = "string", optional = true, nullable = false },
  }
  local child_defaults = { x = 0, y = __rt.__MISSING }
  local parent_fields = {
    { name = "name", jtype = "string", optional = false, nullable = false },
    { name = "child", jtype = "class", optional = false, nullable = false,
      className = "Child", defaults = child_defaults, fields = child_fields },
  }
  local parent_defaults = { name = "", child = __rt.__MISSING }
  local parsed = { name = "P", child = { x = 41, y = "yy" } }

  -- decode
  local original = __rt.json_from_json("Parent", parsed, parent_defaults, parent_fields)
  assert(original ~= nil)
  assert(original.child.__classname == "Child")
  assert(original.child.x == 41 and original.child.y == "yy")

  -- encode
  local encoded = __rt.json_to_json("Parent", original, parent_fields)
  assert(encoded.name == "P")
  assert(encoded.child.x == 41 and encoded.child.y == "yy")

  -- re-decode
  local redecoded = __rt.json_from_json("Parent", encoded, parent_defaults, parent_fields)
  assert(redecoded ~= nil)

  -- compare (recurses into the nested child instance and its tags)
  assert(_jsonable_deep_equal(original, redecoded))
  assert(_jsonable_deep_equal(encoded, __rt.json_to_json("Parent", redecoded, parent_fields)))
end)

test("ISSUE-0188 roundtrip invariant: int[][]", function()
  -- Hand-built element descriptors omit optional/nullable: the absent
  -- flags mean false/false in both directions (Contract 3 element rule).
  local fields = {
    { name = "matrix", jtype = "array", optional = false, nullable = false,
      element = { jtype = "array", element = { jtype = "int" } } },
  }
  local defaults = { matrix = {} }
  -- The empty inner row pins the documented []/{} collapse one level
  -- down (D2a) inside the invariant.
  local parsed = { matrix = { { 1, 2, 3 }, {}, { 4 } } }

  -- decode
  local original = __rt.json_from_json("Matrix", parsed, defaults, fields)
  assert(original ~= nil)
  assert(#original.matrix == 3)
  assert(#original.matrix[2] == 0)

  -- encode
  local encoded = __rt.json_to_json("Matrix", original, fields)
  assert(#encoded.matrix == 3)
  assert(#encoded.matrix[2] == 0)
  assert(encoded.matrix[1][3] == 3 and encoded.matrix[3][1] == 4)

  -- re-decode
  local redecoded = __rt.json_from_json("Matrix", encoded, defaults, fields)
  assert(redecoded ~= nil)

  -- compare
  assert(_jsonable_deep_equal(original, redecoded))
  assert(_jsonable_deep_equal(encoded, __rt.json_to_json("Matrix", redecoded, fields)))
end)

test("ISSUE-0188 roundtrip invariant: class arrays", function()
  local child_fields = {
    { name = "val", jtype = "int", optional = false, nullable = false },
  }
  local child_defaults = { val = 0 }
  -- The class element is nullable: a __NULL element must survive the
  -- pair in both directions (Contract 4 element-nullability gate).
  local fields = {
    { name = "children", jtype = "array", optional = false, nullable = false,
      element = { jtype = "class", className = "Child", defaults = child_defaults,
        fields = child_fields, nullable = true } },
  }
  local defaults = { children = {} }
  local parsed = { children = { { val = 1 }, __rt.__NULL, { val = 3 } } }

  -- decode
  local original = __rt.json_from_json("Parent", parsed, defaults, fields)
  assert(original ~= nil)
  assert(#original.children == 3)
  assert(original.children[1].__classname == "Child")
  assert(original.children[2] == __rt.__NULL)
  assert(original.children[3].val == 3)

  -- encode
  local encoded = __rt.json_to_json("Parent", original, fields)
  assert(#encoded.children == 3)
  assert(encoded.children[2] == __rt.__NULL)
  assert(encoded.children[1].val == 1 and encoded.children[3].val == 3)

  -- re-decode
  local redecoded = __rt.json_from_json("Parent", encoded, defaults, fields)
  assert(redecoded ~= nil)

  -- compare (recurses through the nested class elements and the null
  -- element)
  assert(_jsonable_deep_equal(original, redecoded))
  assert(_jsonable_deep_equal(encoded, __rt.json_to_json("Parent", redecoded, fields)))
end)

test("ISSUE-0188 roundtrip invariant: table objects with nested arrays", function()
  local fields = {
    { name = "data", jtype = "table", optional = false, nullable = false },
  }
  local defaults = { data = {} }
  -- Object-shaped table datum with nested arrays inside object values:
  -- valid in both directions (D7 asymmetry, nested arrays stay allowed).
  local parsed = { data = {
    config = { levels = { 1, 2, 3 }, label = "cfg" },
    tags = { "a", "b" },
  } }

  -- decode
  local original = __rt.json_from_json("Holder", parsed, defaults, fields)
  assert(original ~= nil)
  assert(original.data.config.levels[3] == 3)
  assert(original.data.config.label == "cfg")

  -- encode
  local encoded = __rt.json_to_json("Holder", original, fields)
  assert(encoded.data.config.levels[2] == 2)
  assert(encoded.data.tags[1] == "a" and encoded.data.tags[2] == "b")

  -- re-decode
  local redecoded = __rt.json_from_json("Holder", encoded, defaults, fields)
  assert(redecoded ~= nil)

  -- compare (recurses through the nested arrays inside the table datum)
  assert(_jsonable_deep_equal(original, redecoded))
  assert(_jsonable_deep_equal(encoded, __rt.json_to_json("Holder", redecoded, fields)))
end)

-- ==================== Summary ====================

print("")
print("========================================")
print("Results: " .. passed .. " passed, " .. failed .. " failed")
print("========================================")

if failed > 0 then
  os.exit(1)
end
