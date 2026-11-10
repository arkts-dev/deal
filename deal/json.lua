-- Internal deployed JSON algorithms. These are plain runtime operations,
-- not language function values or stdlib exports.
--
-- Parse accepts RFC 8259 escapes; each four-digit Unicode escape appends
-- one UTF-16 unit, including lone surrogates. Malformed byte encodings and raw
-- controls reject. Stringify escapes lone units and preserves valid pairs.
--
-- Parsing rounds numeric tokens to binary64, preserving negative zero;
-- duplicate decoded keys reject at the second key's opening quote.
-- Scanning accepts any JSON root; an explicit target owns reconstruction.
-- Stringify accepts unknown: undefined/functions omit from objects, become
-- null in arrays and yield undefined at the top level. Nonfinite numbers
-- become null; both zero signs encode as 0. Finite values roundtrip.
-- Private metadata selects carriers without inspecting user hooks. Class
-- fields use declaration order and present fields only; Error exposes
-- code/message, never transport metadata. Own object index keys precede
-- other keys in creation order. Path-local cycles reject, shared children
-- repeat. Explicit frames keep finite depth independent of the call stack.
-- Exact class plans are artifact-local structural declarations. Reconstruction
-- checks extras before fields, requires nonoptional presence and publishes only
-- fresh completed graphs. It never invokes constructors, defaults or hooks.
--
-- Decoded arrays keep their array shape: every
-- array the parser completes carries the private array carrier pair — the
-- array mark and the array's logical length — that the emitted chunk and
-- the LuaJIT host projection write, so a nested array, empty arrays
-- included, remains a JSON array of the decoded table representation
-- instead of collapsing into an indistinguishable {}. stringify reads that
-- mark (and the chunk carrier a declared T[] host parameter crosses into
-- DEAL as) before any numeric-key inference, so a nested decoded empty
-- array encodes as [] and a chunk array carrier encodes as the array of its
-- elements, never as an object holding private carrier keys; unmarked
-- tables keep the existing numeric-key inference and object encoding.
--
-- Read-side numeric carriers encode their binary64 payload, never private
-- transport fields. Record presence and creation order use private maps;
-- numeric slot marks do not affect spelling. Null uses the shared sentinel,
-- independently of absent storage. No user string key selects metadata.

local __rt = require("deal.runtime")

-- The private carrier keys (ISSUE-0830): the emitted chunk, the deployed
-- runtime and this module share one process-wide key set, so no string key
-- of a DEAL table or of a decoded JSON member can be mistaken for a carrier
-- mark and no table can forge a carrier.
local __K = __rt.__keys

local json = {}

-- Encode one decoded UTF-16 unit in the lossless internal byte carrier.
local function utf8_encode(cp)
  if cp < 0x80 then
    return string.char(cp)
  elseif cp < 0x800 then
    return string.char(0xC0 + math.floor(cp / 0x40), 0x80 + cp % 0x40)
  elseif cp < 0x10000 then
    return string.char(
      0xE0 + math.floor(cp / 0x1000),
      0x80 + math.floor(cp / 0x40) % 0x40,
      0x80 + cp % 0x40)
  else
    return string.char(
      0xF0 + math.floor(cp / 0x40000),
      0x80 + math.floor(cp / 0x1000) % 0x40,
      0x80 + math.floor(cp / 0x40) % 0x40,
      0x80 + cp % 0x40)
  end
end

-- The closed carrier-kind projection of one non-JSON value (the shared
-- JSON_STRINGIFY_UNSUPPORTED arm's actual token, the emitted prelude's
-- __stdJsonKind vocabulary): the absent marker is nil, the language-null
-- sentinel keeps its Lua type() shape table, a function carrier — the
-- runtime's function-kind wrapper and the emitted chunk's callable
-- carriers — is function, the bytes carrier is bytes, and every other
-- produced carrier (a class instance in either the runtime's class shape
-- or the emitted chunk's class shape, the builtin Error instance, a
-- decoded async handle) is table. A plain table — the JSON object/array
-- shape, whatever string keys it carries — is table too, so the
-- projection alone never selects.
local function json_carrier_kind(v)
  if v == __rt.__MISSING or v == nil then return 'nil' end
  if v == __rt.__NULL then return 'table' end
  local t = type(v)
  if t == 'table' then
    -- The numeric-variant carrier projects its recorded numeric kind, the
    -- same token the emitted prelude's __stdJsonKind publishes.
    if v[__K.jn] then return 'number' end
    local kind = v[__K.kind]
    -- A class or Error carrier is table even when it also carries the
    -- function markers: an emitted class instance holds both the class
    -- mark and its field map, and the emitted prelude's projection reads
    -- the class mark first.
    if v[__K.c] or v[__K.d] then return 'table' end
    if v[__K.fn] ~= nil or v[__K.f] or v[__K.csig] ~= nil
        or kind == 'function' then
      return 'function'
    end
    return 'table'
  end
  return t
end

-- The closed unsupported-carrier rejection: the JSON_STRINGIFY_UNSUPPORTED
-- arm's own text with the failing value's carrier-kind token. Every
-- non-JSON carrier — the absent marker, a function
-- wrapper, a class or Error instance, a decoded async handle, a raw
-- function, and every other non-table value — renders this one
-- projection, never a silent private-field encode.
local function unsupported(v, file, line, column)
  local actual = json_carrier_kind(v)
  error(__rt._err("E8001", "unsupported type for JSON encoding: " .. actual,
    file, line, column, "string, number, boolean, or table", actual))
end

-- Escape a string for JSON output.  Handles all ASCII control characters
-- (U+0000–U+001F) as well as the required escapes for \", \\, and the
-- common whitespace escapes.
--
-- Malformed byte sequences reject; lone UTF-16 units encode as Unicode
-- escapes, while paired units remain a string pair.
local function escape(s, file, line, column)
  __rt.check_string(s, file, line, column)
  return __rt.string_json_body(s)
end

-- Deployed and emitted encoders share the runtime's ECMAScript spelling.
local function json_number_text(v)
  return __rt.number_text(v)
end

-- The decoded-array mark: every array the parser completes carries the
-- private array carrier pair the emitted chunk and the LuaJIT host
-- projection use — the array mark __K.a and the array's logical length
-- __K.n — so a decoded array keeps its array shape in the JSON-shaped
-- table representation at every depth. An empty array is therefore a
-- marked zero-length array, never the {} a mark-less table collapses
-- into, and the encoder below reads exactly the marks the chunk writes
-- (a host-projected declared T[] carrier and a chunk-constructed array
-- carry them too). The marks are table-object keys, so no user key and no
-- decoded JSON member name can shadow or forge one.
local function mark_decoded_array(arr, length)
  arr[__K.a] = true
  arr[__K.elementDescriptor] = "unknown"
  arr[__K.n] = length
  return arr
end

-- The stack-safe walk: an explicit frame stack replaces the recursion, so
-- a finite nested value has no Lua call-stack bound; the path set keeps
-- the path-local cycle guard (entered on open, removed on close).
--
-- The walk is carrier-complete, exactly the emitted chunk's JSON_STRINGIFY
-- walk: a numeric carrier ({[__K.jn] = true, k = "number",
-- d = value}) renders its binary64 payload with decimal
-- spelling; the chunk table carrier ([__K.t] with [__K.keys]/[__K.order])
-- renders exactly its user string keys in the recorded insertion order;
-- the array carrier ([__K.a]/[__K.n]) renders its elements; and the
-- per-slot number marks ([__K.nK]) do not affect numeric spelling.
-- No private table-object key is ever enumerated as an
-- object member. An unmarked table takes the shared table-form rule: the
-- pure positive-integer-keyed array or the pure string-keyed object, in
-- that order; a table with any other key — a numeric key outside that
-- form, a non-scalar key, or the mixed form only a non-DEAL host table
-- can carry — is not JSON-shaped and takes the closed unsupported arm.
-- Only the language-null carrier __rt.__NULL renders 'null': a raw nil is
-- the missing value in every frame and takes the closed unsupported arm.
local function omitted(v)
  if v == nil or v == __rt.__MISSING or v == __rt.__UNDEFINED or type(v) == 'function' then return true end
  if type(v) ~= 'table' then return false end
  local kind = rawget(v, __K.kind)
  if kind == 'class' or rawget(v, __K.c) or rawget(v, __K.d) then return false end
  return kind == 'function' or rawget(v, __K.fn) ~= nil
    or rawget(v, __K.f) ~= nil or rawget(v, __K.csig) ~= nil
end

local function own_key_order(keys)
  local ranks = {}
  for i = 1, #keys do ranks[keys[i]] = i end
  local function index(key)
    if key ~= '0' and not string.match(key, '^[1-9][0-9]*$') then return nil end
    local n = tonumber(key)
    if n ~= nil and n < 4294967295 then return n end
    return nil
  end
  table.sort(keys, function(a, b)
    local x, y = index(a), index(b)
    if x ~= nil then return y == nil or x < y end
    if y ~= nil then return false end
    return ranks[a] < ranks[b]
  end)
end

local function encode_value(v, file, line, column, path)
  local function rejectIngress(value)
    error(__rt._armErr('TYPED_BOUNDARY_KIND',{kind='unknown'},'E8001','unsupported unknown ingress',
      file,line,column,'unknown',type(value)),0)
  end
  local function shallow(value)
    if value==nil or value==__rt.__NULL or value==__rt.__UNDEFINED or value==__rt.__MISSING then return end
    local t=type(value)
    if t=='string' then __rt.check_string(value,file,line,column)
    elseif t=='number' or t=='boolean' then return
    elseif t~='table' then rejectIngress(value)
    else
      local kind=__rt.typed_kind(value)
      if kind=='async-operation' or kind=='function' and not __rt.valid_function(value) then rejectIngress(value) end
      if getmetatable(value)~=nil and rawget(value,__K.kind)==nil and rawget(value,__K.c)==nil
          and rawget(value,__K.d)==nil and rawget(value,__K.fn)==nil and rawget(value,__K.f)==nil then rejectIngress(value) end
    end
  end
  shallow(v)
  if omitted(v) then return __rt.__UNDEFINED end
  if type(path) ~= "table" then path = {} end
  local out = {}
  local frames = {}
  local arrays
  local function boundary(action)
    local ok, result = pcall(action)
    if ok then return result end
    if type(result) ~= 'table' or result.code == nil then error(result, 0) end
    local leaf = result
    if leaf.code == 'E8003' and leaf.cause ~= nil then leaf = leaf.cause end
    local position = arrays
    while position ~= nil do
      result = __rt._armErr('ARRAY_ELEMENT_KIND', {oneBasedIndex=position.index}, 'E8003',
        'array element '..position.index..' type mismatch', file,line,column,
        position.descriptor.text, __rt.typed_kind(position.value))
      result.cause = leaf
      position = position.parent
    end
    error(result, 0)
  end
  local function kindFailure(kind, value)
    error(__rt._armErr('TYPED_BOUNDARY_KIND', {kind=kind=='class' and 'class instance' or kind},
      'E8001', 'expected '..(kind=='class' and 'class instance' or kind), file,line,column,
      kind, __rt.typed_kind(value)), 0)
  end
  local function position(descriptor, value)
    if descriptor == nil then return nil end
    return boundary(function()
      local d = descriptor
      if d.kind == 'nullable' then
        if d.admitsNull and value == __rt.__NULL
            or d.admitsUndefined and (value == __rt.__UNDEFINED or value == __rt.__MISSING) then return nil end
        d = d.inner
      end
      if d.kind == 'class' then
        if type(value) ~= 'table' or not (rawget(value,__K.kind)=='class'
            or rawget(value,__K.c) or rawget(value,__K.d)) then kindFailure('class',value) end
        local identity=rawget(value,__K.classname) or rawget(value,__K.id)
          or (rawget(value,__K.d) and 'Error')
        if identity == nil then kindFailure('class',value) end
        if identity ~= d.name then error(__rt._armErr('CLASS_IDENTITY',
          {expected=d.name,actual=identity},'E8001','expected instance of '..d.name..', got '..identity,
          file,line,column,d.name,identity),0) end
      elseif d.kind == 'array' then
        if type(value)~='table' or value==__rt.__NULL or value==__rt.__UNDEFINED
            or value==__rt.__MISSING then kindFailure('array',value) end
        if not rawget(value,__K.a) then
          if __rt.typed_kind(value)~='table' then kindFailure('array',value) end
          for key in next,value do
            if type(key)~='number' or key<1 or key%1~=0 then kindFailure('array',value) end
          end
        end
        local persistent=rawget(value,__K.elementDescriptor)
        if persistent~=nil and persistent~=d.element.text then kindFailure('array',value) end
        return d.element
      elseif d.name == 'record' then
        if __rt.typed_kind(value)~='table' or type(value)~='table' or rawget(value,__K.a) then
          kindFailure('table',value)
        end
      elseif d.name ~= 'unknown' then
        __rt.check_canonical_type(d.text,value,file,line,column)
      end
      return nil
    end)
  end
  local emit = function(val, isNumber, descriptor)
    local elementDescriptor = position(descriptor,val)
    shallow(val)
    if omitted(val) then out[#out + 1] = 'null'; return end
    if val == __rt.__NULL then
      out[#out + 1] = 'null'
      return
    end
    -- A raw nil has no JSON encoding: __rt.__NULL is the only null
    -- carrier. An absent value is the missing slot — the marked decoded
    -- array with a deleted element and the inferred sparse table alike —
    -- so it reaches the closed unsupported-carrier arm below with the
    -- nil token, exactly the emitted chunk's missing-slot rejection,
    -- instead of fabricating a present 'null'.
    --
    -- The internal absent marker is never JSON-shaped either (the shared
    -- walk's nil carrier token), never an empty object.
    if val == __rt.__MISSING then
      unsupported(val, file, line, column)
    end
    local t = type(val)
    if t == 'string' then
      out[#out + 1] = '"' .. escape(val, file, line, column) .. '"'
      return
    elseif t == 'number' then
      -- JSON substitutes null for every nonfinite number.
      if val ~= val or val == math.huge or val == -math.huge then
        out[#out + 1] = 'null'; return
      end
      if val == 0 then out[#out + 1] = '0'; return end
      out[#out + 1] = json_number_text(val)
      return
    elseif t == 'boolean' then
      out[#out + 1] = val and 'true' or 'false'
      return
    elseif t == 'table' then
      -- A decoded or read-side numeric carrier holds one binary64 value.
      -- Never enumerate its private transport fields as object members.
      if rawget(val, __K.jn) then
        local carried = val.d
        if type(carried) ~= 'number' then
          unsupported(val, file, line, column)
        end
        if carried ~= carried or carried == math.huge or carried == -math.huge then
          out[#out + 1] = 'null'; return
        end
        if carried == 0 then out[#out + 1] = '0'; return end
        out[#out + 1] = json_number_text(carried)
        return
      end
      -- Nominal carriers serialize only declared fields, without exposing
      -- carrier/transport metadata or losing the original cycle identity.
      -- Callable omission occurs before this arm; async handles reject.
      local carrierKind = rawget(val, __K.kind)
      if carrierKind == 'class' or rawget(val, __K.c) or rawget(val, __K.d) then
        local identity = rawget(val, __K.id) or rawget(val, __K.classname)
        local order = rawget(val, __K.classOrder) or __K.classOrders[identity]
        if identity == 'Error' or rawget(val, __K.d) then order = {'code', 'message'} end
        if type(order) ~= 'table' then unsupported(val, file, line, column) end
        local plan = rawget(val,__K.classPlan)
        if identity=='Error' or rawget(val,__K.d) then plan={
          {name='code',descriptor='string',required=true},
          {name='message',descriptor='string',required=true}} end
        if type(plan)~='table' then error('JSON class value has no exact field plan',0) end
        -- Inspect structural metadata with raw reads only. Lua's border length
        -- does not establish density and ignores nonnumeric entries entirely.
        local count = 0
        for key in next, order do
          if type(key)~='number' or key<1 or key%1~=0 then
            error('JSON class field plan/order mismatch',0)
          end
          count=count+1
        end
        local planCount = 0
        for key in next, plan do
          if type(key)~='number' or key<1 or key%1~=0 or key>count then
            error('JSON class field plan/order mismatch',0)
          end
          planCount=planCount+1
        end
        if planCount~=count then error('JSON class field plan/order mismatch',0) end
        local names = {}
        for i=1,count do
          local key, field = rawget(order,i), rawget(plan,i)
          if type(key)~='string' or names[key] or type(field)~='table'
              or rawget(field,'name')~=key then
            error('JSON class field plan/order mismatch',0)
          end
          local optional, required = rawget(field,'optional'), rawget(field,'required')
          if optional~=nil and type(optional)~='boolean'
              or required~=nil and type(required)~='boolean'
              or optional==nil and required==nil
              or optional~=nil and required~=nil and optional==required
              or type(rawget(field,'canonical') or rawget(field,'descriptor'))~='string' then
            error('JSON class field plan/order mismatch',0)
          end
          if not __rt.parse_canonical_descriptor(rawget(field,'canonical') or rawget(field,'descriptor')) then
            error('JSON class field has no canonical descriptor',0)
          end
          names[key]=true
        end
        if path[val] then
          error(__rt._err('E8001', 'cyclic value cannot be encoded as JSON', file, line, column))
        end
        path[val] = true
        out[#out + 1] = '{'
        frames[#frames + 1] = {kind = 'class', value = val, keys = order, plan=plan, arrays=arrays, index = 1, sorted = true}
        return
      end
      if carrierKind == "function"
          or carrierKind == "class" or carrierKind == "async"
          or val[__K.c] or val[__K.d]
          or val[__K.fn] ~= nil or val[__K.f] or val[__K.csig] ~= nil then
        unsupported(val, file, line, column)
      end
      -- The path-local cycle guard (the walk family's closed cycle arm):
      -- a container re-entering its own active path is the E8001 cycle
      -- failure; a repeated acyclic reference is duplicated, never
      -- rejected. The active set is local per stringify call and every
      -- entry is removed on the way out.
      if path[val] then
        error(__rt._err("E8001", "cyclic value cannot be encoded as JSON", file, line, column, nil, nil))
      end
      path[val] = true
      local marks = type(val[__K.nK]) == 'table' and val[__K.nK] or nil
      -- The private array mark selects array mode before the numeric-key
      -- inference: a decoded JSON array (marked by parse, an empty array
      -- included) and the emitted chunk's own array carrier — the carrier
      -- a declared T[] host parameter crosses into DEAL as — are arrays
      -- even when they carry no numeric key, and their private marks are
      -- never encoded as object members. The recorded length is the
      -- logical length; a carrier without a usable length falls back to
      -- the table's own border, never to object mode.
      if val[__K.a] then
        local markedLen = nil
        local declared = val[__K.n]
        if type(declared) == 'number' and declared >= 0 and declared % 1 == 0 then
          markedLen = declared
        else
          markedLen = #val
        end
        out[#out + 1] = '['
        frames[#frames + 1] = {kind = 'array', value = val, arrays=arrays, descriptor=elementDescriptor, index = 1, maxIdx = markedLen, marks = marks}
        return
      end
      -- The chunk table carrier's own marks ([__K.t], the presence map
      -- __K.keys and the insertion-order list __K.order — __orderAdd
      -- creates the latter two on a table the first time a chunk field
      -- write reaches it, a plain table the chunk did not construct
      -- through TABLE_NEW included): the carrier is an object over exactly
      -- its user string keys, in the recorded insertion order; a carrier
      -- without the order list keeps the string-key order. A table that
      -- carries only the recorded per-slot [__K.nK] marks takes the same
      -- object encoding, so no private mark is ever enumerated. The
      -- presence map is the present-key authority for an object carrier:
      -- the chunk's JSON_PARSE graph records a present null member as a
      -- presence/order key with no stored value, so that member is a JSON
      -- null while a raw nil outside a recorded present key stays the
      -- missing value (the array and inferred-frame rule). The private
      -- marks are table-object keys, so they can never be user keys, and
      -- they are never members of the encoded object.
      if val[__K.t] ~= nil or val[__K.keys] ~= nil
          or val[__K.order] ~= nil or val[__K.nK] ~= nil then
        local keys = {}
        local seen = {}
        local presence = type(val[__K.keys]) == 'table' and val[__K.keys] or nil
        local order = val[__K.order]
        if type(order) == 'table' then
          for i = 1, #order do
            local k = order[i]
            if type(k) == 'string' and not seen[k]
                and (val[k] ~= nil or (presence ~= nil and presence[k] == true)) then
              keys[#keys + 1] = k
              seen[k] = true
            end
          end
        end
        for k, _ in pairs(val) do
          if type(k) == 'string' and not seen[k] then
            keys[#keys + 1] = k
            seen[k] = true
          end
        end
        if presence ~= nil then
          for k, present in pairs(presence) do
            if type(k) == 'string' and present == true and not seen[k] then
              keys[#keys + 1] = k
              seen[k] = true
            end
          end
        end
        out[#out + 1] = '{'
        frames[#frames + 1] = {kind = 'object', value = val, keys = keys, arrays=arrays,
          presence = presence, index = 1, marks = marks}
        return
      end
      -- The shared table-form rule (JsonEncodeV13, the emitted chunk's
      -- own classifier): a plain table is JSON-shaped only as the pure
      -- positive-integer-keyed array or the pure string-keyed object. A
      -- DEAL table has string keys, so a numeric or other key — and the
      -- mixed form a host table can carry — is not JSON-shaped content:
      -- the closed unsupported-carrier arm renders it, never a coerced
      -- member name and never a duplicate JSON member ([1] beside "1").
      local maxIdx = 0
      local objectKey = false
      for k, _ in pairs(val) do
        if type(k) == 'number' then
          if k < 1 or k ~= math.floor(k) then
            rejectIngress(val)
          end
          if k > maxIdx then maxIdx = k end
        elseif type(k) == 'string' then
          objectKey = true
        else
          rejectIngress(val)
        end
      end
      if objectKey and maxIdx > 0 then
        rejectIngress(val)
      end
      if maxIdx > 0 then
        out[#out + 1] = '['
        frames[#frames + 1] = {kind = 'array', value = val, arrays=arrays, descriptor=elementDescriptor, index = 1, maxIdx = maxIdx}
        return
      end
      local keys = {}
      for k, _ in pairs(val) do keys[#keys + 1] = k end
      out[#out + 1] = '{'
      frames[#frames + 1] = {kind = 'object', value = val, keys = keys, arrays=arrays, index = 1}
      return
    end
    -- Unsupported types: reject with runtime error
    unsupported(val, file, line, column)
  end
  emit(v, false)
  while #frames > 0 do
    local frame = frames[#frames]
    arrays = frame.arrays
    if frame.keys ~= nil and not frame.sorted then
      own_key_order(frame.keys)
      frame.sorted = true
    end
    if frame.kind == 'array' then
      if frame.index > frame.maxIdx then
        out[#out + 1] = ']'
        path[frame.value] = nil
        frames[#frames] = nil
      else
        local i = frame.index
        frame.index = i + 1
        if i > 1 then out[#out + 1] = ',' end
        local member = rawget(frame.value,i)
        if frame.descriptor ~= nil then arrays={index=i,descriptor=frame.descriptor,value=member,parent=arrays} end
        emit(member, frame.marks ~= nil and frame.marks[i] == true, frame.descriptor)
      end
    else
      if frame.index > #frame.keys then
        out[#out + 1] = '}'
        path[frame.value] = nil
        frames[#frames] = nil
      else
        local k = frame.keys[frame.index]
        frame.index = frame.index + 1
        local element, descriptor
        if frame.kind == 'class' then
          local fields = rawget(frame.value, __K.f)
          local presence = rawget(frame.value, __K.p)
          if rawget(frame.value, __K.d) then
            if k == 'message' then element = rawget(frame.value, 'm')
            else element = rawget(frame.value, 'code') end
          elseif type(fields) == 'table' then
            if type(presence) ~= 'table' or rawget(presence, k) then
              element = rawget(fields,k)
              if element==nil and type(presence)=='table' and rawget(presence,k) then element=__rt.__NULL end
            end
          else element = rawget(frame.value, k) end
          local field = rawget(frame.plan,frame.index-1)
          if element==nil or element==__rt.__MISSING then
            if rawget(field,'required')==true or rawget(field,'optional')==false then
              boundary(function() kindFailure('class',frame.value) end)
            end
          else
            descriptor=__rt.parse_canonical_descriptor(rawget(field,'canonical') or rawget(field,'descriptor'))
            if descriptor==nil then error('JSON class field has no canonical descriptor',0) end
            position(descriptor,element)
          end
        else element = frame.value[k] end
        shallow(element)
        local presentNull = element == nil and frame.presence ~= nil and frame.presence[k] == true
        if presentNull or not omitted(element) then
          if (frame.emitted or 0) > 0 then out[#out + 1] = ',' end
          frame.emitted = (frame.emitted or 0) + 1
          out[#out + 1] = '"' .. escape(k, file, line, column) .. '":'
          if presentNull then out[#out + 1] = 'null'
          else emit(element, frame.marks ~= nil and frame.marks[k] == true, descriptor) end
        end
      end
    end
  end
  return table.concat(out)
end

--- Encode unknown to string or undefined. The trailing span triplet
-- attributes cycle, invalid Unicode and unsupported-carrier failures.
function json.encode(v, file, line, column)
  return encode_value(v, file, line, column, {})
end

-- Scan a complete document before any target-directed reconstruction.
-- The trailing span triplet belongs to the JSON.parse expression.
function json.scan(s, file, line, column, nullValue)
  __rt.check_string(s, file, line, column)
  nullValue = nullValue or __rt.__NULL
  -- Scalar scanning with an explicit container-frame stack.
  local pos = 1
  local len = #s

  -- Report a parse error at the current position, or at an explicit
  -- position (the duplicate-key defect reports the second key's opening
  -- quote).
  local function parse_error_at(where, msg)
    error(__rt._armErr("JSON_PARSE_ERROR", {oneBasedByteOffset=tostring(where), reason=msg},
      "E8001", "JSON parse error at position " .. where .. ": " .. msg,
      file, line, column, nil, nil), 0)
  end
  local function parse_error(msg)
    parse_error_at(pos, msg)
  end

  -- Advance one hex digit at a time so malformed escapes report the
  -- first bad digit (or EOF), not the preceding 'u'.
  local function read_hex4()
    local v = 0
    for _ = 1, 4 do
      local d = string.byte(s, pos)
      local dv
      if d == nil then parse_error("invalid escape")
      elseif d >= 48 and d <= 57 then dv = d - 48
      elseif d >= 65 and d <= 70 then dv = d - 55
      elseif d >= 97 and d <= 102 then dv = d - 87
      else parse_error("invalid escape") end
      pos = pos + 1
      v = v * 16 + dv
    end
    return v
  end

  local function peek()
    if pos > len then return nil end
    return s:sub(pos, pos)
  end

  local function expect_char(expected)
    if peek() ~= expected then parse_error("missing colon") end
    pos = pos + 1
  end

  local function expect_literal(word)
    for i = 1, #word do
      if pos > len then parse_error("unexpected end of input") end
      if peek() ~= word:sub(i, i) then parse_error("unexpected character") end
      pos = pos + 1
    end
  end

  local skip_ws, parse_value, parse_string, parse_number

  function skip_ws()
    while pos <= len do
      local c = s:sub(pos, pos)
      if c == ' ' or c == '\n' or c == '\r' or c == '\t' then
        pos = pos + 1
      else
        break
      end
    end
  end

  -- One value at the cursor: a scalar returns directly, a container opens
  -- a frame on the stack and returns the __OPENED sentinel. The frame loop
  -- of parse_value then drives the open container.
  local __OPENED = {}
  -- One value at the cursor: a scalar returns its binary64 payload and
  -- numeric marker; a container opens a frame on the stack and
  -- returns the __OPENED sentinel (a container carries no number mark of
  -- its own; its slots record their own).
  local function parse_scalar_or_open(frames)
    skip_ws()
    if pos > len then
      parse_error("unexpected end of input")
    end
    local c = s:sub(pos, pos)

    if c == '{' then
      pos = pos + 1
      frames[#frames + 1] = {kind = 'object',
        obj = {[__K.t]=true, [__K.keys]={}, [__K.order]={}}, seen = {}, state = 0}
      return __OPENED, false
    elseif c == '[' then
      pos = pos + 1
      frames[#frames + 1] = {kind = 'array', arr = {}, state = 4, index = 1}
      return __OPENED, false
    elseif c == '"' then
      return parse_string(), false
    elseif c == 't' then
      expect_literal("true")
      return true, false
    elseif c == 'f' then
      expect_literal("false")
      return false, false
    elseif c == 'n' then
      expect_literal("null")
      return nullValue, false
    elseif c == '-' or (c >= '0' and c <= '9') then
      return parse_number()
    else
      parse_error("unexpected character")
    end
  end

  -- The stack-safe parse: an explicit frame stack replaces the recursive
  -- descent, so a finite nested document has no Lua call-stack bound.
  -- Every transition and defect message is the recursive parser's.
  function parse_value()
    local frames = {}
    local completed
    local completed_number = false
    local have_completed = false
    -- A numeric leaf marks its slot in the private [__K.nK] map, the same
    -- map __numKey writes and the encoder reads. This table-object key
    -- cannot be shadowed by decoded member names or enumerated as data.
    local function mark_number(container, slot)
      local marks = container[__K.nK]
      if type(marks) ~= 'table' then
        marks = {}
        container[__K.nK] = marks
      end
      marks[slot] = true
    end
    while true do
      if have_completed then
        if #frames == 0 then
          return completed
        end
        local parent = frames[#frames]
        if parent.kind == 'array' then
          parent.arr[parent.index] = completed
          if completed_number then
            mark_number(parent.arr, parent.index)
          end
          parent.index = parent.index + 1
          parent.state = 5
        else
          parent.obj[parent.key] = completed
          parent.obj[__K.keys][parent.key] = true
          parent.obj[__K.order][#parent.obj[__K.order] + 1] = parent.key
          if completed_number then
            mark_number(parent.obj, parent.key)
          end
          parent.state = 3
        end
        completed = nil
        completed_number = false
        have_completed = false
      elseif #frames == 0 then
        local parsed, is_number = parse_scalar_or_open(frames)
        if parsed ~= __OPENED then
          completed = parsed
          completed_number = is_number
          have_completed = true
        end
      else
        local frame = frames[#frames]
        if frame.kind == 'object' then
          if frame.state == 0 then
            skip_ws()
            if peek() == '}' then
              pos = pos + 1
              completed = frame.obj
              frames[#frames] = nil
              have_completed = true
            else
              frame.state = 1
            end
          elseif frame.state == 1 then
            skip_ws()
            if pos > len then parse_error("unexpected end of input") end
            if peek() ~= '"' then
              parse_error("missing key")
            end
            -- The duplicate-key defect's position is the second key's
            -- opening quote; decoded equality makes an escaped spelling
            -- of the same key a duplicate too.
            local key_start = pos
            local key = parse_string()
            if frame.seen[key] then
              parse_error_at(key_start, "duplicate key")
            end
            frame.seen[key] = true
            skip_ws()
            expect_char(':')
            frame.key = key
            frame.state = 2
          elseif frame.state == 2 then
            local parsed, is_number = parse_scalar_or_open(frames)
            if parsed ~= __OPENED then
              completed = parsed
              completed_number = is_number
              have_completed = true
            end
          else
            skip_ws()
            local c = peek()
            if c == nil then parse_error("unterminated object") end
            if c == '}' then
              pos = pos + 1
              completed = frame.obj
              frames[#frames] = nil
              have_completed = true
            elseif c == ',' then
              pos = pos + 1
              frame.state = 1
            else
              parse_error("missing comma")
            end
          end
        else
          if frame.state == 4 then
            skip_ws()
            if peek() == ']' then
              pos = pos + 1
              completed = mark_decoded_array(frame.arr, 0)
              frames[#frames] = nil
              have_completed = true
            else
              frame.state = 2
            end
          elseif frame.state == 2 then
            local parsed, is_number = parse_scalar_or_open(frames)
            if parsed ~= __OPENED then
              completed = parsed
              completed_number = is_number
              have_completed = true
            end
          else
            skip_ws()
            local c = peek()
            if c == nil then parse_error("unterminated array") end
            if c == ']' then
              pos = pos + 1
              completed = mark_decoded_array(frame.arr, frame.index - 1)
              frames[#frames] = nil
              have_completed = true
            elseif c == ',' then
              pos = pos + 1
              frame.state = 2
            else
              parse_error("missing comma")
            end
          end
        end
      end
    end
  end

  function parse_string()
    pos = pos + 1 -- skip opening quote
    local parts = {}
    while pos <= len do
      local c = s:sub(pos, pos)
      if c == '"' then
        pos = pos + 1
        return __rt.string_normalize(table.concat(parts))
      elseif c == '\\' then
        pos = pos + 1
        if pos > len then
          parse_error("unterminated string")
        end
        local esc = s:sub(pos, pos)
        if esc == 'n' then parts[#parts+1] = '\n'
        elseif esc == 'r' then parts[#parts+1] = '\r'
        elseif esc == 't' then parts[#parts+1] = '\t'
        elseif esc == 'b' then parts[#parts+1] = '\b'
        elseif esc == 'f' then parts[#parts+1] = '\f'
        elseif esc == '\\' then parts[#parts+1] = '\\'
        elseif esc == '"' then parts[#parts+1] = '"'
        elseif esc == '/' then parts[#parts+1] = '/'
        elseif esc == 'u' then
          -- RFC 8259 section 7: \u must be followed by exactly four
          -- hexadecimal digits (case-insensitive).
          pos = pos + 1
          local v = read_hex4()
          pos = pos - 1 -- common escape-tail advance below
          parts[#parts+1] = utf8_encode(v)
        else
          parse_error("invalid escape")
        end
      else
        -- Raw character: U+0000-U+001F must be escaped (RFC 8259 section 7).
        if string.byte(c) < 0x20 then
          parse_error("unexpected character")
        end
        parts[#parts+1] = c
      end
      pos = pos + 1
    end
    parse_error("unterminated string")
  end

  function parse_number()
    local start = pos
    if s:sub(pos, pos) == '-' then pos = pos + 1 end

    -- Must have at least one digit before decimal point
    if pos > len or not s:sub(pos, pos):match('[0-9]') then
      parse_error("invalid number")
    end
    -- Leading zero check: if first digit is '0', next char must be '.' or 'e'/'E' or end of number
    if s:sub(pos, pos) == '0' then
      pos = pos + 1
      if pos <= len then
        local nc = s:sub(pos, pos)
        if nc:match('[0-9]') then
          parse_error("leading zero")
        end
      end
    else
      while pos <= len and s:sub(pos, pos):match('[0-9]') do pos = pos + 1 end
    end

    if pos <= len and s:sub(pos, pos) == '.' then
      pos = pos + 1
      if pos > len or not s:sub(pos, pos):match('[0-9]') then
        parse_error("invalid number")
      end
      while pos <= len and s:sub(pos, pos):match('[0-9]') do pos = pos + 1 end
    end
    if pos <= len and (s:sub(pos, pos) == 'e' or s:sub(pos, pos) == 'E') then
      pos = pos + 1
      if pos <= len and (s:sub(pos, pos) == '+' or s:sub(pos, pos) == '-') then
        pos = pos + 1
      end
      if pos > len or not s:sub(pos, pos):match('[0-9]') then
        parse_error("invalid number")
      end
      while pos <= len and s:sub(pos, pos):match('[0-9]') do pos = pos + 1 end
    end
    local num_str = s:sub(start, pos - 1)
    if #num_str == 0 then
      parse_error("invalid number: empty")
    end
    local n = tonumber(num_str)
    if n == nil then
      parse_error("invalid number")
    end
    if n == 0 and num_str:sub(1, 1) == "-" then n = -0.0 end
    return n, true
  end

  local result = parse_value()
  skip_ws()
  if pos <= len then
    parse_error("trailing content")
  end
  return result
end

-- Internal target-directed reconstruction. Plans are supplied by the defining
-- artifact; no global class registry or constructor/default hook is consulted.
function json.decode(text, target, plans, file, line, column)
  plans = plans or {}
  assert(type(plans)=="table", "JSON decoding malformed plan map")
  local descriptor = assert(__rt.parse_canonical_descriptor(target), "JSON target descriptor")
  local schemas, pending, seen = {}, {descriptor}, {}
  local function plan_of(name)
    if name == "Error" then return {
      {name="code", descriptor="string", optional=false},
      {name="message", descriptor="string", optional=false}} end
    return assert(rawget(plans, name), "JSON decoding target has no exact class plan: " .. name)
  end
  while #pending > 0 do
    local d = table.remove(pending)
    if d.kind == "function" or (d.kind == "primitive" and d.name == "void") then
      error("JSON decoding target contains a function or void type", 0)
    elseif d.kind == "nullable" then pending[#pending+1] = d.inner
    elseif d.kind == "array" then pending[#pending+1] = d.element
    elseif d.kind == "class" and not seen[d.name] then
      seen[d.name] = true
      local fields, names = {}, {}
      local plan = plan_of(d.name)
      assert(type(plan)=="table", "JSON decoding malformed class plan")
      local count = 0
      for key in next, plan do
        assert(type(key)=="number" and key>=1 and key%1==0,
          "JSON decoding malformed class plan")
        count = count + 1
      end
      for i=1,count do
        local field = rawget(plan,i)
        assert(type(field)=="table", "JSON decoding malformed class plan")
        local name, optional, descriptorText = rawget(field,"name"),
          rawget(field,"optional"), rawget(field,"descriptor")
        assert(type(name)=="string" and __rt.utf8_valid(name) and type(optional)=="boolean"
          and not names[name], "JSON decoding malformed class plan")
        names[name] = true
        local child = assert(__rt.parse_canonical_descriptor(descriptorText), "JSON field descriptor")
        fields[#fields+1] = {name=name, descriptor=child, optional=optional}
        pending[#pending+1] = child
      end
      schemas[d.name] = {fields=fields, names=names}
    end
  end
  local raw = json.scan(text, file, line, column)
  local function mismatch(d, v, missing)
    if d.kind == "nullable" then d = d.inner end
    local expected = d.kind == "array" and "array" or d.kind == "class" and "class" or d.name
    if expected == "record" then expected = "table" end
    local actual = missing and "undefined" or v == __rt.__NULL and "null"
      or type(v)=="table" and rawget(v,__K.a) and "array" or type(v)
    error(__rt._armErr("TYPED_BOUNDARY_KIND", {kind=expected=="class" and "class instance" or expected},
      "E8001", "expected " .. (expected=="class" and "class instance" or expected),
      file, line, column, expected, actual), 0)
  end
  local unknown = __rt.parse_canonical_descriptor("unknown")
  local stack = {{raw=raw, descriptor=descriptor}}
  local completed
  while #stack > 0 do
    local frame = stack[#stack]
    if not frame.open then
      frame.open = true
      local d, value = frame.descriptor, frame.raw
      if frame.missing then mismatch(d, value, true) end
      if d.kind == "nullable" then
        if value == __rt.__NULL and d.admitsNull then frame.value=__rt.__NULL
        else d=d.inner end
      end
      if frame.value == nil then
        if d.kind == "class" then
          if type(value)~="table" or not rawget(value,__K.t) then mismatch(d,value) end
          local schema = schemas[d.name]
          for _, key in ipairs(value[__K.order]) do
            if not schema.names[key] then
              error(__rt._armErr("CLASS_EXTRA_FIELD", {field=key,classId=d.name}, "E8007",
                "extra field '"..key.."' in class '"..d.name.."'", file,line,column), 0)
            end
          end
          frame.class = d.name
          frame.value = {[__K.kind]="class", [__K.classname]=d.name, [__K.classOrder]={}, [__K.classPlan]={}}
          for i,field in ipairs(schema.fields) do frame.value[__K.classPlan][i]={
            name=field.name,descriptor=field.descriptor.text,optional=field.optional} end
          frame.children = {}
          for _, field in ipairs(schema.fields) do
            frame.value[__K.classOrder][#frame.value[__K.classOrder]+1]=field.name
            local present = value[__K.keys][field.name] == true
            if present or not field.optional then frame.children[#frame.children+1]={
              key=field.name, raw=value[field.name], descriptor=field.descriptor, missing=not present} end
          end
        elseif type(value)=="table" and rawget(value,__K.a)
            and (d.kind=="array" or d.name=="unknown") then
          local child = d.kind=="array" and d.element or unknown
          frame.value = {[__K.a]=true, [__K.n]=value[__K.n], [__K.elementDescriptor]=child.text}
          frame.children = {}
          for i=1,value[__K.n] do frame.children[i]={key=i,raw=value[i],descriptor=child} end
        elseif type(value)=="table" and rawget(value,__K.t)
            and (d.name=="unknown" or d.name=="record") then
          frame.value = {[__K.t]=true, [__K.keys]={}, [__K.order]={}}
          frame.children = {}
          for _,key in ipairs(value[__K.order]) do
            frame.value[__K.keys][key]=true
            frame.value[__K.order][#frame.value[__K.order]+1]=key
            frame.children[#frame.children+1]={key=key,raw=value[key],descriptor=unknown}
          end
        elseif d.name=="unknown" or (d.name=="null" and value==__rt.__NULL)
            or ((d.name=="number" or d.name=="string" or d.name=="boolean") and type(value)==d.name) then
          frame.value=value
        else mismatch(d,value) end
      end
      frame.index=1
    end
    if frame.children and frame.index<=#frame.children then
      local child=frame.children[frame.index]
      frame.index=frame.index+1
      stack[#stack+1]=child
    else
      completed=frame.value
      if frame.class=="Error" then completed=__rt.error_value(completed.code,completed.message) end
      stack[#stack]=nil
      if #stack>0 then stack[#stack].value[frame.key]=completed end
    end
  end
  return completed
end

return json
