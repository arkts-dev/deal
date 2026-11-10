-- Executed deployed Lua contextual reconstruction and builtin serialization.
-- Run with: luajit test_runtime_jsonable.lua
package.path = './?.lua;' .. package.path
local rt = require('deal.runtime')
local json = require('deal.json')
local K = rt.__keys
local NULL, UNDEFINED = rt.__NULL, rt.__UNDEFINED
local MODEL, CHILD = '@runtime/Model', '@runtime/Child'
local passed, failed = 0, 0
local function test(name, run)
  local ok, err = pcall(run)
  if ok then passed=passed+1; print('PASS: '..name)
  else failed=failed+1; print('FAIL: '..name..' -- '..tostring(err)) end
end
local function field(name, descriptor, optional)
  return {name=name, descriptor=descriptor, optional=optional==true}
end
local function decode(text, fields, others)
  local plans={[MODEL]=fields}
  for key,value in pairs(others or {}) do plans[key]=value end
  return json.decode(text, MODEL, plans, 'parse.deal', 7, 13)
end
local function encode(value)
  return json.encode(value, 'encode.deal', 11, 17)
end
local function rejects(action, code, message, origin)
  local destination, alias = {untouched=true}, nil
  alias=destination
  local ok, err=pcall(function() destination=action() end)
  assert(not ok and destination==alias and alias.untouched, 'rejection must preserve destination')
  if code then
    assert(type(err)=='table' and err.code==code, 'wrong code: '..tostring(err))
    if message then assert(err.message==message, 'wrong message: '..tostring(err.message)) end
    assert(err.file==origin and err.line==(origin=='parse.deal' and 7 or 11)
      and err.column==(origin=='parse.deal' and 13 or 17), 'wrong origin')
    assert(err.cause==nil, 'unexpected cause')
  end
  return err
end
local types={
  {name='number', text='42', value=42, wrong='"bad"', expected='number'},
  {name='string', text='"Alice"', value='Alice', wrong='false', expected='string'},
  {name='boolean', text='false', value=false, wrong='42', expected='boolean'},
  {name='null', text='null', value=NULL, wrong='42', expected='null'},
  {name='record', text='{"nested":[1,null]}', wrong='[]', expected='table'},
  {name='[number]', text='[1,2]', wrong='{}', expected='array'},
  {name=CHILD, text='{"value":7}', wrong='42', expected='class instance'}}
local childPlans={[CHILD]={field('value','number')}}
-- Every primitive/container position, optional flag and nullable flag is
-- exercised independently for present data, absence, null and wrong data.
for _, kind in ipairs(types) do
  for _, optional in ipairs({false,true}) do
    for _, nullable in ipairs({false,true}) do
      local descriptor=(nullable and kind.name~='null' and '?' or '')..kind.name
      local fields={field('value',descriptor,optional)}
      local label=descriptor..(optional and ' optional' or ' required')
      test(label..': present data and fresh roundtrip',function()
        local text='{"value":'..kind.text..'}'
        local a=decode(text,fields,childPlans)
        local b=decode(text,fields,childPlans)
        assert(a~=b and a[K.classname]==MODEL and a[K.kind]=='class')
        assert(rt.has(a,'value') and encode(a)==text)
        if kind.value~=nil then assert(a.value==kind.value) else
          assert(a.value~=b.value)
          if kind.name==CHILD then assert(a.value.value==7 and a.value[K.classname]==CHILD)
          elseif kind.name=='[number]' then
            assert(a.value[K.elementDescriptor]=='number' and a.value[1]==1 and a.value[2]==2)
            a.value[1]=99; assert(b.value[1]==1)
          else assert(a.value.nested[1]==1 and a.value.nested[2]==NULL) end
        end
      end)
      test(label..': absence preserves optional presence',function()
        if optional then
          local a=decode('{}',fields,childPlans)
          assert(a.value==nil and not rt.has(a,'value') and encode(a)=='{}')
        else
          local err=rejects(function() return decode('{}',fields,childPlans) end,
            'E8001','expected '..kind.expected,'parse.deal')
          assert(err.actual=='undefined')
        end
      end)
      test(label..': explicit null remains distinct from absence',function()
        if nullable or kind.name=='null' then
          local a=decode('{"value":null}',fields,childPlans)
          assert(a.value==NULL and rt.has(a,'value') and encode(a)=='{"value":null}')
        else
          local err=rejects(function() return decode('{"value":null}',fields,childPlans) end,
            'E8001','expected '..kind.expected,'parse.deal')
          assert(err.actual=='null')
        end
      end)
      test(label..': wrong value throws without publication',function()
        rejects(function() return decode('{"value":'..kind.wrong..'}',fields,childPlans) end,
          'E8001','expected '..kind.expected,'parse.deal')
      end)
    end
  end
end

for _, root in ipairs({'42','3.14','true','false','null','"x"','[]','[1,2]'}) do
  test('class target rejects root '..root,function()
    rejects(function() return decode(root,{}) end,'E8001','expected class instance','parse.deal')
  end)
end
test('empty class accepts empty object and has no synthetic exports',function()
  local a=decode('{}',{})
  assert(a[K.classname]==MODEL and encode(a)=='{}')
  assert(rt.json_from_json==nil and rt.json_to_json==nil and rt.json_from_plan==nil)
end)
test('empty object never evaluates hooks or supplies required defaults',function()
  local calls=0
  local plan={field('value','number')}
  plan[1].evaluator=function() calls=calls+1; return 42 end
  rejects(function() return decode('{}',plan) end,'E8001','expected number','parse.deal')
  assert(calls==0)
  assert(decode('{"value":7}',plan).value==7 and calls==0)
end)
test('cyclic and throwing initializer-shaped values are not consulted',function()
  local calls=0
  local plan={field('value','number')}
  plan[1].defaults=plan
  plan[1].evaluator=function() calls=calls+1; error('unexpected hook') end
  assert(decode('{"value":1}',plan).value==1 and calls==0)
end)
for _, key in ipairs({'extra','{actual}','a.0','a}b','a{b','',"a'b",'x\0y'}) do
  test('extras precede missing and wrong fields: '..key,function()
    local escaped=json.encode(key)
    local err=rejects(function()
      return decode('{'..escaped..':1,"value":"bad"}',{field('value','number')})
    end,'E8007',"extra field '"..key.."' in class '"..MODEL.."'",'parse.deal')
    assert(err.expected==nil and err.actual==nil)
  end)
end
for _, text in ipairs({'{"x":1,"x":2}','{"x":1,"\\u0078":2}',
    '{"extra":1,"value":{"x":1,"x":2}}'}) do
  test('decoded duplicate syntax precedes reconstruction: '..text,function()
    local err=rejects(function() return decode(text,{field('value','number')}) end,
      'E8001',nil,'parse.deal')
    assert(string.find(err.message,'duplicate key',1,true))
  end)
end
for _, text in ipairs({'','{','[','01','1.','1e+','true false','[1,]','{"a":1,}',
    '"\\q"','"line\nfeed"','tru','trX'}) do
  test('malformed document throws: '..text,function()
    rejects(function() return decode(text,{}) end,'E8001',nil,'parse.deal')
  end)
end
for _, text in ipairs({'2147483647','2147483648','-2147483648','-2147483649',
    '9007199254740992','3.5','5e-324','1.7976931348623157e308','-0'}) do
  test('binary64 decode/encode: '..text,function()
    local a=decode('{"value":'..text..'}',{field('value','number')})
    assert(a.value==tonumber(text))
    if text=='-0' then assert(1/a.value==-math.huge and encode(a)=='{"value":0}') end
    local b=decode(encode(a),{field('value','number')})
    assert(a.value==b.value)
  end)
end
for _, text in ipairs({'"\\u0000"','"\\uD800"','"\\uDC00"','"\\uD83D\\uDE00"',
    '"apostrophe\' and {actual}"','"\\b\\f\\n\\r\\t\\\\\\\""'}) do
  test('lossless string roundtrip: '..text,function()
    local a=decode('{"value":'..text..'}',{field('value','string')})
    local b=decode(encode(a),{field('value','string')})
    assert(a.value==b.value)
  end)
end
for _, text in ipairs({'null','true','false','42','"x"','[]','{}','[null,1]',
    '{"a":[1,{"x":false}]}','1e400','-1e400'}) do
  test('unknown contextual root: '..text,function()
    local a=json.decode(text,'unknown',{},'parse.deal',7,13)
    local b=json.decode(encode(a),'unknown',{},'parse.deal',7,13)
    if type(a)~='table' and a~=math.huge and a~=-math.huge then assert(a==b) end
    if a==NULL then assert(b==NULL) end
    if type(a)=='table' and a~=NULL then assert(a~=b) end
  end)
end

local malformed={
  'not a table', {'entry'}, {{descriptor='number',optional=false}},
  {{name=1,descriptor='number',optional=false}}, {{name='x',optional=false}},
  {{name='x',descriptor='number'}}, {{name='x',descriptor='number',optional=0}},
  {{name='x',descriptor='number',optional='false'}},
  {field('x','number'),field('x','number')}, {[2]=field('x','number')},
  {field('x','number'),extra=true}, {{name='x',descriptor='number[]',optional=false}},
  {{name='x',descriptor='[number',optional=false}}, {{name='x',descriptor='?void',optional=true}},
  {{name='x',descriptor='()->number',optional=true}}}
for i, plan in ipairs(malformed) do
  test('malformed or inadmissible contextual plan '..i,function()
    rejects(function() return decode('{}',plan) end)
  end)
end
for _, descriptor in ipairs({'void','()->number','[()->number]','?()->number','[?void]'}) do
  test('reachable target restriction including omitted optional: '..descriptor,function()
    rejects(function() return decode('{}',{field('unused',descriptor,true)}) end)
  end)
end
test('missing exact descendant plan fails even with absent optional',function()
  rejects(function() return decode('{}',{field('child',CHILD,true)}) end)
end)
test('Record and Unknown stop schema traversal',function()
  for _, d in ipairs({'record','unknown'}) do
    local a=decode('{"value":{"x":{"y":1}}}',{field('value',d)})
    assert(a.value.x.y==1)
  end
end)
test('raw plan reads invoke no metatable hooks',function()
  local calls=0
  local hooks={__index=function() calls=calls+1; error('hook') end,
    __pairs=function() calls=calls+1; error('hook') end}
  local plan=setmetatable({setmetatable(field('value','number'),hooks)},hooks)
  assert(decode('{"value":7}',plan).value==7 and calls==0)
  rejects(function() return decode('{}',{setmetatable({name='value',descriptor='number'},hooks)}) end)
  assert(calls==0)
end)
test('recursive exact plans preserve independent freshness',function()
  local plans={[CHILD]={field('value','number'),field('next','?'..CHILD,true)}}
  local fields={field('child',CHILD),field('children','['..CHILD..']')}
  local text='{"child":{"value":1,"next":{"value":2}},"children":[{"value":3}]}'
  local a,b=decode(text,fields,plans),decode(text,fields,plans)
  assert(a.child~=b.child and a.child.next~=b.child.next and a.children~=b.children)
  assert(a.child.next.value==2 and a.children[1].value==3 and encode(a)==text)
  a.child.next.value=9; assert(b.child.next.value==2)
end)
test('nested failure leaves existing graph and aliases unchanged',function()
  local fields={field('child',CHILD)}
  local a=decode('{"child":{"value":7}}',fields,childPlans)
  local original,alias=a,a.child
  local ok,err=pcall(function() a=decode('{"child":{"value":"bad"}}',fields,childPlans) end)
  assert(not ok and err.code=='E8001' and err.message=='expected number')
  assert(a==original and a.child==alias and alias.value==7)
end)
test('declaration order differs from input and numeric object order',function()
  local a=decode('{"b":2,"a":1}',{field('a','number'),field('b','number')})
  assert(encode(a)=='{"a":1,"b":2}')
  local record=json.decode('{"z":0,"10":10,"2":2,"a":1}','record')
  assert(encode(record)=='{"2":2,"10":10,"z":0,"a":1}')
end)
test('optional nullable three states coexist in one fresh graph',function()
  local fields={field('missing','?string',true),field('nilValue','?string',true),field('set','?string',true)}
  local a=decode('{"nilValue":null,"set":"yes"}',fields)
  local b=decode(encode(a),fields)
  assert(not rt.has(b,'missing') and rt.has(b,'nilValue') and b.nilValue==NULL and b.set=='yes')
end)
test('nested arrays and class arrays retain exact typed metadata',function()
  local fields={field('grid','[[number]]'),field('children','['..CHILD..']')}
  local a=decode('{"grid":[[1],[]],"children":[{"value":7}]}',fields,childPlans)
  assert(a.grid[K.elementDescriptor]=='[number]' and a.grid[1][K.elementDescriptor]=='number')
  assert(a.children[K.elementDescriptor]==CHILD and a.children[1][K.classname]==CHILD)
  assert(encode(a)=='{"grid":[[1],[]],"children":[{"value":7}]}')
end)
test('Error schema exposes only code/message and preserves text',function()
  local a=json.decode('{"message":"m\\u0000x","code":"C"}','Error')
  assert(encode(a)=='{"code":"C","message":"m\\u0000x"}')
end)

-- Current-storage validation selects failures in declaration/index order.
for _, descriptor in ipairs({'number','string','boolean','null','record','[number]',CHILD}) do
  test('mutated declared position rejects and repairs: '..descriptor,function()
    local kind
    for _,candidate in ipairs(types) do if candidate.name==descriptor then kind=candidate end end
    local a=decode('{"value":'..kind.text..'}',{field('value',descriptor)},childPlans)
    local prior=a.value
    a.value=descriptor=='boolean' and 42 or false
    if descriptor=='null' then a.value=1 end
    rejects(function() return encode(a) end,'E8001','expected '..kind.expected,'encode.deal')
    a.value=prior
    assert(encode(a)=='{"value":'..kind.text..'}')
  end)
end
test('nominal descendant mismatch uses exact class identity',function()
  local a=decode('{"value":{"value":7}}',{field('value',CHILD)},childPlans)
  local prior=a.value
  a.value=decode('{}',{})
  rejects(function() return encode(a) end,'E8001',
    'expected instance of '..CHILD..', got '..MODEL,'encode.deal')
  a.value=prior; assert(encode(a)=='{"value":{"value":7}}')
end)
test('required missing rejects but optional undefined is omitted',function()
  local a=decode('{"value":1}',{field('value','number'),field('unused','!number',true)})
  a.unused=UNDEFINED; assert(encode(a)=='{"value":1}')
  a.value=nil
  rejects(function() return encode(a) end,'E8001','expected class instance','encode.deal')
  a.value=1; assert(encode(a)=='{"value":1}')
end)
test('persistent array descriptor cannot be retagged by stringify',function()
  local a=decode('{"value":[1]}',{field('value','[number]')})
  local prior=a.value
  a.value=json.decode('[1]','[unknown]')
  rejects(function() return encode(a) end,'E8001','expected array','encode.deal')
  assert(a.value[K.elementDescriptor]=='unknown')
  a.value=prior; assert(encode(a)=='{"value":[1]}')
end)
test('nested typed array failures preserve original leaf and operation origin',function()
  local a=decode('{"value":[[1],[2]]}',{field('value','[[number]]')})
  a.value[2][1]=false
  local ok,err=pcall(encode,a)
  assert(not ok and err.code=='E8003' and err.expected=='[number]'
    and err.message=='array element 2 type mismatch')
  assert(err.cause and err.cause.code=='E8001' and err.cause.message=='expected number'
    and err.cause.cause==nil and err.cause.file=='encode.deal' and err.cause.line==11
    and err.cause.column==17)
  a.value[2][1]=2; assert(encode(a)=='{"value":[[1],[2]]}')
end)
test('unknown cycle precedes later typed failure; repair exposes the later failure',function()
  local a=decode('{"first":{},"later":1}',{field('first','unknown'),field('later','number')})
  a.first.self=a.first; a.later=false
  rejects(function() return encode(a) end,'E8001','cyclic value cannot be encoded as JSON','encode.deal')
  a.first.self=nil
  rejects(function() return encode(a) end,'E8001','expected number','encode.deal')
  a.later=1; assert(encode(a)=='{"first":{},"later":1}')
end)
test('earlier typed failure precedes later cycle and leaves graph unchanged',function()
  local a=decode('{"first":1,"later":{}}',{field('first','number'),field('later','unknown')})
  a.first=false; a.later.self=a.later
  rejects(function() return encode(a) end,'E8001','expected number','encode.deal')
  assert(a.first==false and a.later.self==a.later)
  a.first=1
  rejects(function() return encode(a) end,'E8001','cyclic value cannot be encoded as JSON','encode.deal')
  a.later.self=nil; assert(encode(a)=='{"first":1,"later":{}}')
end)
for _,bytes in ipairs({{128},{192,175},{226,130}}) do
  test('malformed string transport precedes later cycle and repairs on same graph '..bytes[1],function()
    local invalid=string.char(unpack(bytes))
    local a=decode('{"first":"kept","later":{}}',
      {field('first','string'),field('later','unknown')})
    local later,plan=a.later,a[K.classPlan]
    a.first=invalid; later.loop=a
    local err=rejects(function() return encode(a) end,'E8001',
      'expected string, got invalid UTF-8 encoding','encode.deal')
    assert(err.expected=='string' and err.actual=='invalid UTF-8 string')
    assert(a.first==invalid and a.later==later and later.loop==a and a[K.classPlan]==plan)
    a.first='kept'
    rejects(function() return encode(a) end,'E8001',
      'cyclic value cannot be encoded as JSON','encode.deal')
    later.loop=nil
    assert(encode(a)=='{"first":"kept","later":{}}' and a.later==later)
  end)
  test('malformed typed array string retains original leaf and identity '..bytes[1],function()
    local a=decode('{"value":["kept"]}',{field('value','[string]')})
    local array=a.value
    local invalid=string.char(unpack(bytes))
    array[1]=invalid
    local err=rejects(function() return encode(a) end)
    assert(err.code=='E8003' and err.message=='array element 1 type mismatch'
      and err.expected=='string' and err.actual=='string'
      and err.file=='encode.deal' and err.line==11 and err.column==17)
    local leaf=err.cause
    assert(leaf and leaf.code=='E8001'
      and leaf.message=='expected string, got invalid UTF-8 encoding'
      and leaf.expected=='string' and leaf.actual=='invalid UTF-8 string'
      and leaf.file=='encode.deal' and leaf.line==11 and leaf.column==17 and leaf.cause==nil)
    assert(a.value==array and array[1]==invalid and array[K.elementDescriptor]=='string')
    array[1]='kept'
    assert(encode(a)=='{"value":["kept"]}' and a.value==array)
  end)
end
test('recursive nominal cycle and same-identity repair',function()
  local fields={field('next','?'..MODEL)}
  local a=decode('{"next":null}',fields)
  a.next=a
  rejects(function() return encode(a) end,'E8001','cyclic value cannot be encoded as JSON','encode.deal')
  a.next=NULL; assert(encode(a)=='{"next":null}')
end)
test('array cycle and shared acyclic children',function()
  local a=json.decode('[{}]','[unknown]')
  local child=a[1]
  a[1]=a
  rejects(function() return encode(a) end,'E8001','cyclic value cannot be encoded as JSON','encode.deal')
  a[1]=child; a[2]=child; a[K.n]=2
  assert(encode(a)=='[{},{}]' and a[1]==a[2])
end)
test('Record accepts nested class with its own plan, not arbitrary carrier keys',function()
  local a=decode('{"value":7}',{field('value','number')})
  local record=json.decode('{"child":null}','record')
  record.child=a
  assert(encode(record)=='{"child":{"value":7}}')
end)
for _, value in ipairs({0/0,math.huge,-math.huge}) do
  test('nonfinite number substitutes null: '..tostring(value),function()
    local a=decode('{"value":1}',{field('value','number')})
    a.value=value; assert(encode(a)=='{"value":null}')
  end)
end
test('callable omission and array substitution invoke no function',function()
  local calls=0
  local f=rt.function_('()->void',function() calls=calls+1; error('must not invoke') end)
  assert(encode(f)==UNDEFINED)
  assert(encode({value=f})=='{}')
  assert(encode({[K.a]=true,[K.n]=2,f,UNDEFINED})=='[null,null]' and calls==0)
end)
for _, bad in ipairs({{[true]=1},{[0]=1},{[1.5]=1},{[1]=1,x=2},
    {[{}]=1}, {value=coroutine.create(function() end)}}) do
  test('unsupported live Record ingress rejects without coercion '..tostring(bad),function()
    rejects(function() return encode(bad) end,'E8001',nil,'encode.deal')
  end)
end
test('sparse array holes substitute null rather than collapse length',function()
  local a=json.decode('[1,2,3]','[unknown]')
  a[2]=nil
  assert(encode(a)=='[1,null,3]' and a[K.n]==3)
end)
test('class serialization ignores initializer-shaped instance data',function()
  local a=decode('{"value":1}',{field('value','number')})
  a.defaults={}; a.defaults.self=a.defaults
  a.evaluator=function() error('must not invoke') end
  assert(encode(a)=='{"value":1}')
end)
test('malformed current class plan fails closed and same-instance repair succeeds',function()
  local a=decode('{"value":1}',{field('value','number')})
  local plan=a[K.classPlan]
  a[K.classPlan]={{name='other',descriptor='number',optional=false}}
  rejects(function() return encode(a) end)
  a[K.classPlan]=plan; assert(encode(a)=='{"value":1}')
end)
local malformedLivePlans={
  {name='extra string key',mutate=function(p) p.extra=field('extra','number',true) end},
  {name='sparse numeric key',mutate=function(p) p[3]=field('extra','number',true) end},
  {name='missing first entry',mutate=function(p) p[1]=nil end},
  {name='duplicate field',mutate=function(p) p[2].name='value' end},
  {name='nonboolean optional',mutate=function(p) p[1].optional='false' end},
  {name='missing presence flag',mutate=function(p) p[1].optional=nil end},
  {name='nonboolean required',mutate=function(p) p[1].required=1 end},
  {name='contradictory flags',mutate=function(p) p[1].required=false end},
  {name='missing descriptor',mutate=function(p) p[1].descriptor=nil end}}
for _,case in ipairs(malformedLivePlans) do
  test('live deployed plan rejects and repairs: '..case.name,function()
    local a=decode('{"value":1,"note":"kept"}',
      {field('value','number'),field('note','string',true)})
    local original,order=a[K.classPlan],a[K.classOrder]
    local plan={field('value','number'),field('note','string',true)}
    a[K.classPlan]=plan
    case.mutate(plan)
    local err=rejects(function() return encode(a) end)
    assert(err=='JSON class field plan/order mismatch')
    assert(a.value==1 and a.note=='kept' and a[K.classOrder]==order and a[K.classPlan]==plan)
    a[K.classPlan]=original
    assert(encode(a)=='{"value":1,"note":"kept"}')
  end)
  test('descendant deployed plan rejects and repairs: '..case.name,function()
    local a=decode('{"child":{"value":1,"note":"kept"}}', {field('child',CHILD)},
      {[CHILD]={field('value','number'),field('note','string',true)}})
    local child=a.child
    local original=child[K.classPlan]
    local plan={field('value','number'),field('note','string',true)}
    child[K.classPlan]=plan
    case.mutate(plan)
    local err=rejects(function() return encode(a) end)
    assert(err=='JSON class field plan/order mismatch')
    assert(a.child==child and child.value==1 and child.note=='kept' and child[K.classPlan]==plan)
    child[K.classPlan]=original
    assert(encode(a)=='{"child":{"value":1,"note":"kept"}}' and a.child==child)
  end)
end
for _,descriptor in ipairs({'','[number','async (number)->number','missing'}) do
  test('absent optional still requires canonical schema: '..descriptor,function()
    local a=decode('{}',{field('value','number',true)})
    a[K.classPlan][1].descriptor=descriptor
    assert(rejects(function() return encode(a) end)=='JSON class field has no canonical descriptor')
    assert(a.value==nil and not rt.has(a,'value'))
    a[K.classPlan][1].descriptor='number'
    assert(encode(a)=='{}')
  end)
end
test('live plans use raw reads and admit inert metatables',function()
  local a=decode('{"value":1}',{field('value','number')})
  local hooks=0
  local mt={__index=function() hooks=hooks+1; error('metadata hook') end,
    __pairs=function() hooks=hooks+1; error('metadata hook') end}
  setmetatable(a[K.classPlan],mt)
  setmetatable(a[K.classPlan][1],mt)
  setmetatable(a[K.classOrder],mt)
  assert(encode(a)=='{"value":1}' and hooks==0)
  a[K.classPlan][1].optional=nil
  rejects(function() return encode(a) end)
  assert(hooks==0)
  a[K.classPlan][1].optional=false
  assert(encode(a)=='{"value":1}' and hooks==0)
end)
for _, depth in ipairs({513,600}) do
  test('deep Record decode/encode '..depth,function()
    local text='{"value":'..string.rep('{"next":',depth)..'1'..string.rep('}',depth)..'}'
    local a=decode(text,{field('value','record')})
    assert(encode(a)==text)
  end)
  test('deep recursive nominal decode/encode '..depth,function()
    local text=string.rep('{"next":',depth)..'null'..string.rep('}',depth)
    local a=decode(text,{field('next','?'..MODEL)})
    assert(encode(a)==text)
  end)
  test('deep typed nested array decode/encode '..depth,function()
    local descriptor=string.rep('[',depth)..'number'..string.rep(']',depth)
    local input=string.rep('[',depth)..'42'..string.rep(']',depth)
    local a=decode('{"value":'..input..'}',{field('value',descriptor)})
    assert(encode(a)=='{"value":'..input..'}')
  end)
end
test('deep 601-level array serialization retains every level',function()
  local text=string.rep('[',601)..'42'..string.rep(']',601)
  local a=json.decode(text,'unknown')
  assert(encode(a)==text)
end)
test('deep 5000-class reconstruction, ordinary check and encode',function()
  local text=string.rep('{"value":1,"next":',5000)..'null'..string.rep('}',5000)
  local a=decode(text,{field('value','number'),field('next','?'..MODEL)})
  assert(rt.check_type(MODEL,a)==a and encode(a)==text)
end)
test('deep 20000-array reconstruction, ordinary check and encode',function()
  local input=string.rep('[',20000)..'-0'..string.rep(']',20000)
  local a=json.decode(input,'unknown')
  assert(rt.check_type('unknown',a)==a)
  assert(encode(a)==string.rep('[',20000)..'0'..string.rep(']',20000))
end)
-- Cross-direction invariants compare values, identities, presence and fixed
-- points independently; serialization never mutates the admitted graph.
local roundtrips={
  {name='three-level class', fields={field('child',CHILD)},
    plans={[CHILD]={field('value','number'),field('next','?'..CHILD,true)}},
    text='{"child":{"value":1,"next":{"value":2,"next":{"value":3}}}}',
    inspect=function(a,b)
      assert(a.child.next.next.value==3 and b.child.next.next.value==3)
      assert(a.child~=b.child and a.child.next~=b.child.next and a.child.next.next~=b.child.next.next)
    end},
  {name='matrix with empty row', fields={field('matrix','[[number]]')},
    text='{"matrix":[[1,2,3],[],[4]]}', inspect=function(a,b)
      assert(a.matrix[2][K.n]==0 and b.matrix[2][K.n]==0)
      assert(a.matrix~=b.matrix and a.matrix[1]~=b.matrix[1] and a.matrix[2]~=b.matrix[2])
      assert(b.matrix[1][3]==3 and b.matrix[3][1]==4)
    end},
  {name='nullable class elements', fields={field('children','[?'..CHILD..']')},
    plans=childPlans, text='{"children":[{"value":1},null,{"value":3}]}',
    inspect=function(a,b)
      assert(a.children[2]==NULL and b.children[2]==NULL)
      assert(a.children[1]~=b.children[1] and a.children[3]~=b.children[3])
      assert(b.children[1][K.classname]==CHILD and b.children[3].value==3)
    end},
  {name='Record with mixed nested arrays', fields={field('data','record')},
    text='{"data":{"config":{"levels":[1,2,3],"label":"cfg"},"tags":["a","b"]}}',
    inspect=function(a,b)
      assert(a.data~=b.data and a.data.config~=b.data.config and a.data.tags~=b.data.tags)
      assert(b.data.config.levels[3]==3 and b.data.config.label=='cfg' and b.data.tags[2]=='b')
    end}}
for _,case in ipairs(roundtrips) do
  test('fresh fixed-point roundtrip: '..case.name,function()
    local a=decode(case.text,case.fields,case.plans)
    local encoded=encode(a)
    local b=decode(encoded,case.fields,case.plans)
    assert(a~=b and a[K.classname]==MODEL and b[K.classname]==MODEL)
    assert(encoded==case.text and encode(b)==encoded and encode(a)==encoded)
    case.inspect(a,b)
  end)
end
for _, descriptor in ipairs({'number','string','boolean','record','[number]',CHILD}) do
  for _, optional in ipairs({false,true}) do
    test('nullable current storage mutation/repair: '..descriptor..'/'..tostring(optional),function()
      local a=decode('{"value":null}',{field('value','?'..descriptor,optional)},childPlans)
      assert(rt.has(a,'value') and a.value==NULL and encode(a)=='{"value":null}')
      a.value=descriptor=='boolean' and 42 or false
      rejects(function() return encode(a) end,'E8001',nil,'encode.deal')
      a.value=NULL; assert(encode(a)=='{"value":null}')
      a.value=nil
      if optional then assert(encode(a)=='{}') else
        rejects(function() return encode(a) end,'E8001','expected class instance','encode.deal')
      end
      a.value=NULL; assert(encode(a)=='{"value":null}')
    end)
  end
end
for index,plan in ipairs(malformed) do
  test('malformed descendant plan fails before publication '..index,function()
    rejects(function()
      return decode('{"child":{}}',{field('child',CHILD)},{[CHILD]=plan})
    end)
  end)
end
for _,text in ipairs({'[null]','[1,false]','[[1]]','[{}]','{"0":1}'}) do
  test('typed array element or shape rejects: '..text,function()
    rejects(function() return decode('{"value":'..text..'}',{field('value','[number]')}) end,
      'E8001',nil,'parse.deal')
  end)
end
test('nullable nested array elements preserve null/value identity',function()
  local text='{"value":[null,[null,1],[]]}'
  local a=decode(text,{field('value','[?[?number]]')})
  local b=decode(encode(a),{field('value','[?[?number]]')})
  assert(a.value[1]==NULL and b.value[2][1]==NULL and b.value[2][2]==1)
  assert(a.value~=b.value and a.value[2]~=b.value[2] and encode(b)==text)
end)
test('encode preserves original nested aliases, slots and metadata',function()
  local a=decode('{"data":{"a":1,"b":{"c":2}},"tags":[1,2]}',
    {field('data','record'),field('tags','[number]')})
  local data,nested,tags,plan=a.data,a.data.b,a.tags,a[K.classPlan]
  local encoded=encode(a)
  assert(encoded=='{"data":{"a":1,"b":{"c":2}},"tags":[1,2]}')
  assert(a.data==data and data.b==nested and nested.c==2 and a.tags==tags)
  assert(tags[1]==1 and tags[2]==2 and tags[K.n]==2 and tags[K.elementDescriptor]=='number')
  assert(a[K.classPlan]==plan and a[K.classname]==MODEL and a[K.kind]=='class')
end)
test('generic roots accept primitive values without requiring class tags',function()
  assert(encode(42)=='42' and encode(true)=='true' and encode('x')=='"x"')
  assert(encode(NULL)=='null' and encode({})=='{}')
end)
test('user metadata-like Record keys cannot forge nominal identity',function()
  local a=json.decode('{"__kind":"class","__classname":"Fake","__a":true}','record')
  assert(a[K.kind]==nil and a[K.classname]==nil)
  assert(encode(a)=='{"__kind":"class","__classname":"Fake","__a":true}')
end)
test('unsupported async handles reject rather than serialize internal storage',function()
  local a=rt.async_create(function() return 1 end)
  rejects(function() return encode(a) end,'E8001',nil,'encode.deal')
end)
test('operation-local cycle state unwinds across repeated success and rejection',function()
  local a=json.decode('{"child":{"value":1}}','record')
  local child=a.child
  for _=1,3 do
    assert(encode(a)=='{"child":{"value":1}}')
    child.loop=a
    rejects(function() return encode(a) end,'E8001','cyclic value cannot be encoded as JSON','encode.deal')
    child.loop=nil
  end
  a.other=child
  assert(encode(a)=='{"child":{"value":1},"other":{"value":1}}' and a.child==a.other)
end)
test('600-level typed descriptor consumes 601-level malformed data without stack bound',function()
  local descriptor=string.rep('[',600)..'number'..string.rep(']',600)
  local text=string.rep('[',601)..'1'..string.rep(']',601)
  rejects(function() return decode('{"value":'..text..'}',{field('value',descriptor)}) end,
    'E8001','expected number','parse.deal')
end)
print('\nResults: '..passed..' passed, '..failed..' failed')
if failed>0 then os.exit(1) end
