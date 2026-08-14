# Design-Page Locator Audit — ISSUE-0090 (C8) completion

This audit satisfies the C8 acceptance criterion "Design-page locator audit: the
design pages' cited evidence locators still point at the actual sites at
completion, or the pages are corrected to the shifted locators."

`forge/wiki/` is **not** part of this workdir's tree (the workdir contains only
`deal/`, `test/`, `std/`, `docs/` and harness files), so the pages themselves
cannot be corrected here. This document is the audit artifact the review
repair requested: it enumerates **every** evidence locator cited by the six
ISSUE-0082 design pages at their pre-implementation HEAD `35e5429` anchor
state, verified one-by-one against the final implementation tree (this
workdir), as an old→new pair, `UNCHANGED` when still exact, or `RETIRED` when
the site no longer exists. The last section lists the page-correction
obligation per page so a future wiki edit can re-anchor mechanically.

Method: each site was located by content (the distinctive emission/statement
the page describes), not by line, and the line in the final tree recorded.
"Sites" = the exact statement/region the page's claim points at.

Legend: `A → B` = old cite (HEAD `35e5429`) → final-tree location;
`UNCHANGED` = identical line(s); `RETIRED` = site removed by the
implementation.

## 1. runtime-class-identity

Production:

| Old cite | Final tree | Disposition |
|---|---|---|
| `deal/types/Types.java:93-97` (Type.Class branch, name :95, modulePath :96) | `deal/types/Types.java:93-97` | UNCHANGED |
| `deal/codegen/lua/LuaBackend.java:568-571` (typeDescriptor class / check-site descriptor) | `:769-775` (Type.Class case at :769) | shifted |
| `LuaBackend.java:625` (emitCheckExpr Type.Class branch) | `:845-847` | shifted |
| `LuaBackend.java:555-590` (typeDescriptor) | `:742-797` | shifted |
| `deal/runtime.lua:567-611` (`__rt.class_`) | `:775-810` | shifted |
| `deal/runtime.lua:604-611` (`__rt.export_class`) | `:812-814` | shifted |
| `deal/runtime.lua:310-341` (check_type class branch), `:337-340` (Error) | `:339-353` | shifted |
| `deal/runtime.lua:684-780` (`__rt.json_from_json`) | `:891-1000` | shifted |
| `LuaBackend.java:1792-1835` (emitClassConstruction) | `:2031-2085` | shifted |
| `LuaBackend.java:745-760` (META export; module-level :747, nested :759) | `visit(ClassDeclaration)` `:939-989` (module-level META :967, nested META :979) | shifted |
| `LuaBackend.java:2438-2493` (emitFromJson/emitToJson) | `:2689-2807` (emitFromJson :2689, emitToJson :2727) | shifted |
| `LuaBackend.java:2439,2477` (jsonable sig strings) | `:2692` (fromJson sig) / `:2730` (toJson sig) | shifted |
| `LuaBackend.java:2264-2350` (field descriptors) | `:2504-2610` (emitFieldDescriptor :2504, emitSingleFieldDescriptor :2524) | shifted |
| `LuaBackend.java:2375-2383` (classNameFromTypeNode) | `:2622-2648` | shifted |
| `LuaBackend.java:2399-2411` (fieldsRefForTypeNode QualifiedType) | `:2650-2687` | shifted |
| `deal/checker/NameResolver.java:492` (nested ClassSymbol modulePath) | `deal/checker/NameResolver.java:492` | UNCHANGED |
| `deal/checker/NameResolver.java:125` / `:116-125` (Error seeding) | `:125` / `:115-125` | UNCHANGED |
| `deal/module/CompilationOrchestrator.java:716` (NameResolver seeding) | `:761` | shifted |
| `LuaBackend.java:339-343` (public instance ctor, assignment :342) | `:504-510` (`this.modulePath = sourcePath;` at :508) | shifted |
| `LuaBackend.java:348` (generateFromInstance) | `:514` | shifted |
| `LuaBackend.java:366` (generateFromInstanceWithSourceMap) | `:532` | shifted |
| `LuaBackend.java:183,204` (static entry points) | `:187-263` (generateWithImports overloads; modulePath plumbed :248-263) | shifted |
| `LuaBackend.java:2107` (JsonableClassMeta.moduleLevel) | `:2335-2352` (moduleLevel field :2346) | shifted |
| `LuaBackend.java:1254,1282-1283` (visit(ExportDeclaration) jsonable registration) | `:1489-1545` (deferredJsonables.add :1531) | shifted |
| `LuaBackend.java:455-464` (resolveClassExportValue) | `:625-645` | shifted |
| `LuaBackend.java:33` (symbols field) | `:33` | UNCHANGED |
| `LuaBackend.java:1842-1864` (findImportAliasForClass) | `:2087-2117` | shifted |
| `LuaBackend.java:605-630` / `:619-621` / `:622-623` / `:624-626` (emitCheckExpr switch) | `:815-852` (Array :839-841, Nullable :842-844, Class :845-847) | shifted |
| `LuaBackend.java:321-322` (header Error_defaults) | `:582` | shifted |
| `LuaBackend.java:401-406` (KNOWN LIMIT intrinsic aliases) | `:570-575` (wrapper emission in emitHeader :559; KNOWN LIMIT comments removed) | RETIRED/replaced |
| `LuaBackend.java:1694-1715`, `:1702-1709` (emitCall intrinsic branch) | `:1934-1956` (intrinsic `.f` branch :1947-1954) | shifted |
| `LuaBackend.java:1341-1424` (visit(TryStatement); binding :1404-1412) | `:1590-1718` (binding :1654-1661) | shifted |
| `LuaBackend.java:1472-1513` (visit(ThrowStatement); :1481-1508, :1509-1510) | `:1721-1750` (object-literal branch :1725-1745) | shifted |
| `LuaBackend.java:2573-2582` (resolveTypeNode QualifiedType branch, case :2573) | `:2827-2836` | shifted |
| `LuaBackend.java:2564` (NamedType "Error" case) | `:2818` | shifted |
| `LuaBackend.java:2002-2016` (append idiom; guard :2008-2010, emission :2011-2012) | `:2243-2255` (guard :2248-2250, emission :2251-2252) | shifted |
| `deal/runtime.lua:17-26` (`_err`) | `:19-27` | shifted |
| `deal/runtime.lua:301-310` (check_type function branch) | `:329-337` | shifted |
| `deal/runtime.lua:627-652` (int_convert/number_convert) | `:833-862` | shifted |
| `deal/runtime.lua:504-541` (async_step) | `:712-773` | shifted |

Harness/test evidence:

| Old cite | Final tree | Disposition |
|---|---|---|
| `test/LuaAbiBackendTest.java:63-90` (compile() seeding) | `:80-93` (NameResolver :82, generate :93) | shifted |
| `test/LuaBackendTest.java:40-68` (compile() seeding) | `:49-77` | shifted |
| `test/LuaBackendIntegrationTest.java:63-70` | `:47-69` | shifted |
| `test/BackendConformanceTest.java:294-301` | `:295-300` | shifted |
| `test/ConformanceTest.java:809-821` | `:868-879` | shifted |
| `test/LuaBackendTest.java:104-105` (instance ctor + generateFromInstance) | `:104-105` | UNCHANGED |
| `test/LuaBackendTest.java:1604-1605` (testTryBreakContinueInFunction instance-ctor use) | `:1538-1539` | shifted |
| `test/LuaBackendTest.java:575` (`"User"` → qualified) | `:577` (`"@test.deal/User"` pin; instance-path pins :601/:603) | shifted |
| `test/LuaBackendTest.java:557` (`__deal["User_meta"]` prefix) | `:559` | shifted |
| `test/LuaBackendTest.java:1202` (`check_type("@test.deal/User"`) | `:1236` | shifted |
| `test/LuaBackendTest.java:860` / `:881` / `:882` / `:899` / `:900` (catch pins) | `:892` / `:913` / `:914` / `:931` / `:932` | shifted |
| `test/LuaBackendTest.java:913-915` / `:926-939` (throw pins; count check `== 1`) | `:945-946` / `:957-973` (countOccurrences `== 1` at :973) | shifted |
| `test/LuaBackendTest.java:1297` (Error_defaults header pin) | `:1331` | shifted |
| `test/LuaBackendTest.java:1383` (`__rt.class_("Error"`) | `:1416` | shifted |
| `test/LuaBackendTest.java:1913,1929` (jsonable sigs qualified) | `:2001,2017` | shifted |
| `test/LuaAbiBackendTest.java:229-230` / `:237` / `:239-240` / `:244` (User jsonable pins) | `:233` / `:241` / `:243-244` / `:248` | shifted |
| `test/LuaAbiBackendTest.java:232` (`export_class("User")`) | `:244` | shifted |
| `test/LuaAbiBackendTest.java:260` / `:262` | `:281` / `:283` | shifted |
| `test/LuaAbiBackendTest.java:277` / `:279` | `:281` / `:283` | shifted |
| `test/LuaAbiBackendTest.java:307` / `:328` | `:311` / `:332` | shifted |
| `test/LuaAbiBackendTest.java:351` / `:365-366` (shadowedModuleClassNameConstructionKeepsScopeLocalDefaults) | `:355` / `:369-370` | shifted |
| `test/LuaAbiBackendTest.java:385` / `:414,418,420` (thenBranchClassShadowKeepsPerBranchScopeFidelity) | `:389` / `:418,422,424` | shifted |
| `test/LuaAbiBackendTest.java:437` / `:449` (catchBlockClassShadowDoesNotLeakPastTheTry) | `:441` / `:453` | shifted |
| `test/LuaAbiBackendTest.java:464` / `:476,478` (moduleLevelCatchBlockClassShadowDoesNotLeakPastTheTry) | `:468` / `:480,482` | shifted |
| `test/LuaAbiBackendTest.java:500` / `:507-515` / `:523` (blockNestedExportClassExportsTheScopeLocalArtifacts) | `:504` / `:511-521` / `:527` | shifted |
| `test/LuaAbiBackendTest.java:538` / `:545-547` / `:548-553` / `:554` / `:559` (blockNestedJsonableClassRoundTripsViaScopeLocalArtifacts) | `:542` / `:549-551` / `:552-557` / `:558` / `:559-560` | shifted |
| `test/LuaAbiBackendTest.java:565` (nestedJsonableShadowingModuleLevelJsonableKeepsExportsNonNil) | `:588` | shifted |
| `test/LuaAbiBackendTest.java:632` / `:659` (sameNameBlockThenModuleExportResolvesAllKeysToTheModuleDeclaration) | `:637` / `:663` | shifted |
| `test/LuaAbiBackendTest.java:697` / `:701` (nonExportedChunkLevelShadowWinsTheExportResolution) | `:683` / `:705` | shifted |
| `test/LuaAbiBackendTest.java:734` / `:738` (functionScopedExportDoesNotOverwriteTheChunkVisibleDeclaration) | `:720` / `:742` | shifted |
| `test/LuaAbiBackendTest.java:766-790` (functionScopedOnlyExportKeepsTheBareNilResolution) | `:756-820` | shifted |
| `test/LuaAbiBackendTest.java:790` / `:804,807` (blockNestedShadowingClassKeepsScopeLocalDefaultsAtTheConstructionSite) | `:787` / `:808,811` | shifted |
| `test/LuaAbiBackendTest.java:795-845` (hasCheckOnReservedFieldUsesBracketForm) | `:826-849` | shifted |
| `test/LuaAbiBackendTest.java:850-867` (exportKeysFollowThePerKindRule) | `:851-871` | shifted |
| `test/LuaAbiBackendTest.java:875-878` (Error pins; bare `"Error"` stays) | `:879-886` (header :879-880, class_ :881-882, negative :884-886) | shifted |
| `test/LuaAbiBackendTest.java:915-936` (jsonableTopologicalOrderPreserved) | `:924-953` | shifted |
| `test/LuaAbiBackendTest.java:943-962` (dollarPropertyNameEmitsBracketKey) | `:901-923` | shifted |
| `test/LuaAbiBackendTest.java:965-986` (forLetShadowAndArrayIndexShapesUnchanged) | `:954-987` | shifted |
| `test/LuaAbiBackendTest.java:988-990` (emissionIsDeterministic) | `:1110-1116` | shifted |
| `test/LuaAbiBackendTest.java:219-221` (reserved-word tests) | `:183-214` (reservedWordTableFieldsEmitBracketKeys) | shifted |
| `test_runtime.lua:498-510` / `:723-737` (identity tests, bare-descriptor passthrough) | `:498-512` / `:740-802` (qualified-identity block; passthrough tests unchanged) | shifted |

## 2. host-module-abi

Production:

| Old cite | Final tree | Disposition |
|---|---|---|
| `deal/codegen/lua/LuaBackend.java:1230-1237` (import emission) | `:1451-1486` (visit(ImportDeclaration) :1451; host branch :1458-1478; raw require :1482-1486) | shifted |
| `LuaBackend.java:1047-1052` (cycle-2 (attempt 2) mis-cite, already re-anchored on the page) | `:1451-1486` | shifted |
| `LuaBackend.java:555-590` (typeDescriptor) | `:742-797` | shifted |
| `LuaBackend.java:558` (Type.Null emission) | `:745` | shifted |
| `LuaBackend.java:565` (array branch) | `:754-757` | shifted |
| `LuaBackend.java:566` (nullable branch) | `:759-767` | shifted |
| `LuaBackend.java:584` (rest arm) | `:789` | shifted |
| `LuaBackend.java:1702-1715` (call emission) | `:1934-1956` | shifted |
| `LuaBackend.java:1819-1834` (imported-class construction defaults) | `:2031-2117` (emitClassConstruction :2031, findImportAliasForClass :2087) | shifted |
| `LuaBackend.java:2284-2320` (emitSingleFieldDescriptor) | `:2524-2610` | shifted |
| `LuaBackend.java:2399-2411` (fieldsRefForTypeNode QualifiedType) | `:2650-2687` | shifted |
| `deal/module/ExportExtractor.java:93-114` (C$fromJson/C$toJson synthetics) | `:86-113` | shifted |
| `deal/module/ExportExtractor.java:145-156` (resolveFuncType; rest cast :152-153) | `:145-164` (cast :153) | UNCHANGED |
| `deal/module/ExportExtractor.java:186-195` (cycle-2 (attempt 2) mis-cite, already re-anchored) | `resolveTypeNodeSimple` `:164-199` | shifted |
| `deal/module/CompilationOrchestrator.java:826-900` (codegenAll) | `:871-924` | shifted |
| `deal/module/CompilationOrchestrator.java:835-846` (importResolutions) | `:889-898` | shifted |
| `deal/module/CompilationOrchestrator.java:997-1058` (discovery candidates) | `:1040-1085` (buildCandidates :1052) | shifted |
| `deal/module/DealConfig.java:60,78` (externals) | `:27` (field) / `:65` (parse) / `:88` (accessor) / `:103` (parseExternals) | shifted |
| `deal/checker/NameResolver.java:747-756` (E3005 catch) | `:751-755` | shifted |
| `deal/checker/Symbol.java:32` (ClassSymbol) | `:30` | shifted |
| `deal/checker/Symbol.java:37` (ModuleSymbol) | `:37` | UNCHANGED |
| `deal/checker/TypeChecker.java:1296-1316` (checkClassConstruction) | `:1312-1344` | shifted |
| `deal/checker/TypeChecker.java:1290-1302` (resolveFieldTypeInClassModule) | `:1298-1310` | shifted |
| `deal/checker/TypeChecker.java:1145-1155` (member access) | `:1118-1135` | shifted |
| `deal/codegen/lua/LuaAbi.java:207-218` (tableField) | `:207-218` | UNCHANGED |
| `deal/ir/IrDumper.java:133-135` (rest `...[T]`) | `:133-135` | UNCHANGED |
| `deal/types/Type.java:66-68` (Nullable invariant) | `:66-68` | UNCHANGED |
| `deal/types/Types.java:190-192` (Types.nullable) | `:190` | UNCHANGED |
| `deal/runtime.lua:12` (__NULL) | `:12` | UNCHANGED |
| `deal/runtime.lua:13-24` (DEALRuntimeError shape) | `:12-19` | shifted |
| `deal/runtime.lua:33-38` (check_null) | `:50-56` | shifted |
| `deal/runtime.lua:93-96` (check_nullable) | `:110-117` | shifted |
| `deal/runtime.lua:97-110` (array_element_descriptor) | `:120-133` | shifted |
| `deal/runtime.lua:139-264` (parse_descriptor) | `:168-296` | shifted |
| `deal/runtime.lua:146-168` / `:157` (`|null` scan) | `:239-251` (scan :250) | shifted |
| `deal/runtime.lua:194+` (function branch) | `:214-232` | shifted |
| `deal/runtime.lua:235` (async ret assignment) | `:232` | shifted |
| `deal/runtime.lua:301-310` (check_type function branch) | `:329-337` | shifted |
| `deal/runtime.lua:386-395` (as_lua_function) | `:402-408` | shifted |
| `deal/runtime.lua:397-479` (from_lua_function) | `:450-608` | shifted |
| `deal/runtime.lua:457-458` (result packing) | `:544-546` | shifted |
| `deal/runtime.lua:462-469` (ret=="null" skip) | `:548-588` (replaced by the three-way dispatch) | RETIRED/replaced |
| `deal/runtime.lua:504-541` (async_step) | `:712-773` | shifted |
| `deal/runtime.lua:687,700,789` (ipairs(fields)) | `:894,907,996` | shifted |
| `deal/runtime.lua:554,559` (E8010 async-shape) | `:554,559` | UNCHANGED (site named by the review; exact) |

Harness/test evidence:

| Old cite | Final tree | Disposition |
|---|---|---|
| `test/CheckerTest.java:1359-1363` (E3005 pin) | `:1431-1436` (testInvalidNullable) | shifted |
| `test/ConformanceTest.java:450-464` (resolveCompanionPath null for bare) | `:470-490` | shifted |
| `test/ConformanceTest.java:578-586` ($-gate) | `:618-628` (dollarOnlyInQuotedKeys) | shifted |
| `test/ConformanceTest.java:588-640` (xpcall runner) | `:635-692` | shifted |
| `test/ConformanceTest.java:597,621` (package.path) | `:637,661` | shifted |
| `test/ConformanceTest.java:606,630` ($-skip) | `:646,670` | shifted |
| `test/ConformanceTest.java:855-905` (bare imports resolve stdlib) | `:1030-1085` | shifted |
| `test/ConformanceTest.java:881-915` (resolveClassSymbol/resolveTypeNodeInModule) | `:1083-1135` (methods :1083, :1111) | shifted |
| `test/DiagnosticClassificationTest.java:223-290` (coverage map) | `:223-290` (E2009 :239, E3017 :257, E8011 :287) | UNCHANGED |
| `test/IrDumperTest.java:748-749` (rest `...[int]` pin) | `:513` | shifted |
| `test/TypeDescriptorTest.java:513-514` | `:513` | UNCHANGED |
| `test/ModuleSystemTest.java:336-350` (DealConfig externals pins) | `:336-372` | shifted |
| `test/StdlibContractTest.java:74-160` (stdlib contract test) | `:74-160` (testModule) | UNCHANGED |
| `test_runtime.lua:819-823` (`?string[]` pin) | `:880-888` (re-anchored `string|null[]` at :883) | shifted |
| `test_runtime.lua:831-836` (`?int[]` pin) | `:894-902` (re-anchored `int|null[]` at :897) | shifted |
| `test_runtime.lua:779-798` (rest pins) | `:837-870` | shifted |
| `test_runtime.lua:1006-1015` (async wrap pin) | `:1069-1078` | shifted |
| `test_runtime.lua:1046-1056` (nullable async descriptor) | `:1117-1130` | shifted |
| `test_runtime.lua:1065-1072` (sync `(int)->string|null`) | `:1141-1160` | shifted |
| `test_runtime.lua:1077-1086` (non-operation E8010) | `:1079-1087` | shifted |
| `test_runtime.lua:1098-1104` (sig comparison) | `:1088-1106` | shifted |
| `test_runtime.lua:1355-1372` (real-handle async sibling) | `:1175-1200` | shifted |
| `test_runtime.lua:294-299` (legacy `string|null[]` pin) | `:295-301` | shifted |
| `test_runtime.lua:365-376` (plain `?string`/`?int` checks) | `:366-376` | UNCHANGED |

## 3. typed-boundary-enforcement

| Old cite | Final tree | Disposition |
|---|---|---|
| `deal/checker/IntrinsicResolvers.java:25-80` | `:26-80` (INT :32, NUMBER :57) | shifted |
| `deal/checker/NameResolver.java:95-112` (seedIntrinsics) | `:95-106` | shifted |
| `deal/checker/TypeChecker.java:393-455` (async return checks) | `:405-455` (insideAsyncFunction :406, checkReturnStatement :431) | shifted |
| `deal/checker/TypeChecker.java:738-807` (checkAwaitExpression) | `:746-790` | shifted |
| `deal/checker/TypeChecker.java:1114-1117` (array length reads) | `:1125-1128` (inside checkMemberAccess :1118) | shifted |
| `deal/checker/TypeChecker.java:1251-1259` (checkArrayLiteral E3011) | `:1242-1267` | shifted |
| `deal/checker/TypeChecker.java:1479-1530` (checkAssignmentExpr) | `:1487-1520` (E3017 :1495-1503) | shifted |
| `deal/checker/TypeChecker.java:658` (checkDeleteStatement) | `:658` (E3017 :668-680) | UNCHANGED |
| `deal/codegen/lua/LuaBackend.java:401-406` (KNOWN LIMIT) | `:570-575` (wrappers; comments removed) | RETIRED/replaced |
| `LuaBackend.java:1694-1715` (emitCall) | `:1934-1956` | shifted |
| `LuaBackend.java:1547` (AwaitExpression emission) | `:1786` | shifted |
| `LuaBackend.java:1767-1773` (cycle-1 (attempt 2) mis-cite, already corrected to :2002-2016) | `:2243-2255` | shifted |
| `LuaBackend.java:2002-2016` (append idiom; guard :2008-2010, emission :2011-2012) | `:2243-2255` (guard :2248-2250, emission :2251-2252) | shifted |
| `LuaBackend.java:605-630` / `:619-621` / `:622-623` (emitCheckExpr) | `:815-852` (Array :839-841, Nullable :842-844) | shifted |
| `LuaBackend.java:637-712` (function/async return-site emission) | `:1018-1090` (visit(FunctionDeclaration) :1018) | shifted |
| `deal/runtime.lua:301-310` (check_type function branch) | `:329-337` | shifted |
| `deal/runtime.lua:504-541` (async_step) | `:712-773` | shifted |
| `deal/runtime.lua:627-652` (int/number convert) | `:833-862` | shifted |
| `deal/parser/Parser.java:1452-1507` (parseObjectLiteral/parseProperty) | `:1452-1507` (parseProperty :1486) | UNCHANGED |
| `deal/types/Type.java:66-68` | `:66-68` | UNCHANGED |
| `test/DiagnosticClassificationTest.java:223-290` | `:223-290` | UNCHANGED |
| `test/LuaBackendTest.java:652-654` (int pins incl. retired assertNotContains :653) | `:675-683` (int.f(3.0 :681, span :682, wrapper :683; assertNotContains RETIRED) | shifted |
| `test/LuaBackendTest.java:667-669` (number pins incl. retired :668) | `:691-699` (number.f(42 :697, span :698, wrapper :699; RETIRED) | shifted |
| `test/LuaBackendTest.java:683-684` (testIntrinsicIndirectUse rationale) | `:707-714` (limitation wording retired) | shifted |
| `test/LuaBackendTest.java:1827,1879` (coroutine.yield pins) | `:1861,1913` (sibling :1872) | shifted |
| `test/CheckerTest.java:1359-1363` | `:1431-1436` | shifted |
| `test/ConformanceTest.java:606,630` ($-skip) | `:646,670` | shifted |
| `test/ConformanceTest.java:851` (stdlibExports call) | `:1039` | shifted |
| `deal/module/StdlibModuleResolver.java:52` | `:52` | UNCHANGED |
| `test/LuaBackendTest.java:104-105` | `:104-105` | UNCHANGED |

## 4. lua-abi-emission-layer

| Old cite | Final tree | Disposition |
|---|---|---|
| `deal/codegen/lua/LuaBackend.java:2438-2493` (emitFromJson/emitToJson) | `:2689-2807` | shifted |
| `LuaBackend.java:455-464` (resolveClassExportValue) | `:625-645` | shifted |
| `deal/lexer/Lexer.java:32-57` (KEYWORDS) | `:32-57` | UNCHANGED |
| `deal/checker/NameResolver.java:116-125` (Error seeding) | `:115-125` | UNCHANGED |
| `test/LuaAbiBackendTest.java:305-310` (scope-local negative pins) | `:310-313` (topLevelBareBlockClassKeepsScopeLocalArtifacts :304) | shifted |
| `test/LuaAbiBackendTest.java:507-515` (block-nested export pins) | `:511-521` | shifted |
| `test/LuaAbiBackendTest.java:875-876` (Error_defaults pins) | `:879-882` | shifted |
| `test/ConformanceTest.java:606,630` ($-skip) | `:646,670` | shifted |
| `test/ConformanceTest.java:851` (stdlibExports call) | `:1039` | shifted |
| `deal/module/StdlibModuleResolver.java:52` | `:52` | UNCHANGED |
| `test/LuaBackendTest.java:104-105` | `:104-105` | UNCHANGED |

## 5. conformance-test-architecture

| Old cite | Final tree | Disposition |
|---|---|---|
| `test/ConformanceTest.java:106-127` (discoverTests) | `:119-131` (host-fixtures skip :123-130) | shifted |
| `test/ConformanceTest.java:597,621` (package.path) | `:637,661` | shifted |
| `test/ConformanceTest.java:761-783` (cycle-3 mis-cite; already re-anchored to :851 on the page) | CompanionCatalog.compile `:811-900` | shifted |
| `test/ConformanceTest.java:851` (stdlibExports call) | `:1039` | shifted |
| `test/ConformanceTest.java:450-464` (resolveCompanionPath) | `:470-490` | shifted |
| `test/ConformanceTest.java:606,630` ($-skip) | `:646,670` | shifted |
| `test/ConformanceTest.java:809-821` (harness seeding) | `:868-879` | shifted |
| `test/LuaBackendIntegrationTest.java:3183-3215` (testJsonableNestedClass) | `:3255-3295` (Child_fields pins :3281, :3285) | shifted |
| `deal/parser/Parser.java:1452-1507` | `:1452-1507` | UNCHANGED |
| `deal/checker/TypeChecker.java:1296-1316` | `:1312-1344` | shifted |
| `deal/checker/Symbol.java:32` (ClassSymbol) | `:30` | shifted |
| `deal/module/StdlibModuleResolver.java:52` | `:52` | UNCHANGED |
| `deal/module/ExportExtractor.java:145-156` | `:145-164` | UNCHANGED |
| `deal/codegen/lua/LuaBackend.java:1230-1237` | `:1451-1486` | shifted |
| `test_runtime.lua:819-823` | `:880-888` | shifted |

## 6. type-descriptor-mapping-table

| Old cite | Final tree | Disposition |
|---|---|---|
| `deal/codegen/lua/LuaBackend.java:555-590` (typeDescriptor) | `:742-797` | shifted |
| `deal/codegen/lua/LuaBackend.java:1230-1237` | `:1451-1486` | shifted |
| `deal/ir/IrDumper.java:133-135` | `:133-135` | UNCHANGED |
| `deal/checker/NameResolver.java:747-756` | `:751-755` | shifted |
| `deal/types/Type.java:66-68` | `:66-68` | UNCHANGED |
| `deal/runtime.lua:97-110` (array_element_descriptor) | `:120-133` | shifted |
| `deal/runtime.lua:157` (`|null` scan) | `:250` | shifted |
| `deal/runtime.lua:462` (skip) | `:548-588` (three-way dispatch) | RETIRED/replaced |
| `deal/module/StdlibModuleResolver.java:52` | `:52` | UNCHANGED |
| `deal/module/ExportExtractor.java:145-156` | `:145-164` | UNCHANGED |
| `test/CheckerTest.java:1359-1363` | `:1431-1436` | shifted |
| `test/IrDumperTest.java:748-749` | `:513` | shifted |
| `test/TypeDescriptorTest.java:513-514` | `:513` | UNCHANGED |
| `test/ConformanceTest.java:606,630` | `:646,670` | shifted |
| `test/ConformanceTest.java:851` | `:1039` | shifted |
| `test/LuaBackendTest.java:104-105` | `:104-105` | UNCHANGED |
| `test_runtime.lua:819-823` / `:831-836` | `:880-888` / `:894-902` | shifted |

## Cross-check against the review's independent audit

Every pair the review listed is confirmed by this audit: `LuaBackend.java:558`
→ `:745`; `:1230-1237` → `:1451`; `:1341-1424`/`:1404-1412` → `:1590-1718`/
`:1654-1661`; `:1472-1513` → `:1721-1750`; `:2573-2582` → `:2827-2836`;
`:2002-2016` → `:2243-2255`; `ConformanceTest.java:606,630` → `:646,670`;
`:851` → `:1039`; `LuaBackendTest.java:1604-1605` → `:1538-1539`; and the
unchanged set (`ExportExtractor.java:145-156`, `Types.java:93-97`,
`StdlibModuleResolver.java:52`, `LuaBackendTest.java:104-105`) re-verified
unchanged.

## Page-correction obligation (for a future forge/wiki edit)

Since `forge/wiki/` lives outside this workdir, the page edits cannot be made
in this MR. The correction obligation per page, derivable mechanically from
the tables above:

1. **runtime-class-identity**: re-anchor the Context evidence bullet, the D2
   emission-site inventory (META/construction/@jsonable/field-descriptor
   sites), the D3 try/catch/throw cites, the cycle-3 (attempt 2) six-locator
   revision (try/catch `:1590-1718`/`:1721-1750`, `resolveTypeNode`
   QualifiedType `:2827-2836`, instance-constructor tests `:104-105`/`:1538-1539`),
   the cycle-8 re-verification statement, and the full D4 pin inventory (every
   `LuaAbiBackendTest`/`LuaBackendTest`/`test_runtime.lua` pin line above).
2. **host-module-abi**: re-anchor the Verified evidence bullet (import emission
   `:1451-1486`; `typeDescriptor` `:742-797` with `Type.Null` `:745`; call
   emission `:1934-1956`; `ExportExtractor` `:145-164`; `DealConfig` `:27`/
   `:65`/`:88`; runner locators `:470-490`, `:618-628`, `:635-692`, `:637,661`,
   `:646,670`, `:1030-1085`, `:1083-1135`), the runtime.lua locator list
   (parse_descriptor `:168-296` with the `|null` scan at `:250`; packing
   `:544-546`; dispatch `:548-588`; `array_element_descriptor` `:120-133`;
   `async_step` `:712-773`; `check_null` `:50-56`; `as_lua_function`
   `:402-408`; `from_lua_function` `:450-608`; `ipairs(fields)` `:894,907,996`),
   the cycle-7 sharpening, and the Migration test_runtime.lua pin lines.
3. **typed-boundary-enforcement**: re-anchor the Context evidence bullet
   (`TypeChecker` sites `:405-455`, `:746-790`, `:1125-1128`, `:1242-1267`,
   `:1487-1520`, `:658`; `LuaBackend` sites `:1018-1090`, `:1786`, `:1934-1956`,
   `:2243-2255`, `:570-575`, `:815-852`), the cycle-1 (attempt 2) append-idiom
   re-anchor (`:2243-2255`), and the cycle-8 six-pin intrinsic Migration lines
   (`:675-683`, `:691-699`, `:707-714`).
4. **lua-abi-emission-layer**: re-anchor `:2438-2493` → `:2689-2807`,
   `:455-464` → `:625-645`, the runner `$`-skip → `:646,670`, stdlib call →
   `:1039`, and the `LuaAbiBackendTest` pins (`:309-317`, `:511-521`,
   `:879-882`).
5. **conformance-test-architecture**: re-anchor `:106-127` → `:119-131`,
   `:597,621` → `:637,661`, `:450-464` → `:470-490`, `:606,630` → `:646,670`,
   `:809-821` → `:868-879`, `:851` → `:1039`, `LuaBackendIntegrationTest`
   `:3183-3215` → `:3255-3295`, `Symbol.java:32` → `:30`.
6. **type-descriptor-mapping-table**: re-anchor `LuaBackend.java:555-590` →
   `:742-797`, `NameResolver.java:747-756` → `:751-755`, `runtime.lua:97-110`
   → `:120-133`, `:157` → `:250`, `:462` → `:548-588`, `IrDumperTest.java:748-749`
   → `:513`, `CheckerTest.java:1359-1363` → `:1431-1436`, runner `$`-skip →
   `:646,670`, stdlib call → `:1039`, `test_runtime.lua:819-823/:831-836` →
   `:880-888/:894-902`.

Note: with the lone exception of the stale `LuaBackendTest.java:1604-1605`
instance-constructor cite (site moved to `:1538-1539`), every claim's
*substance* re-verified true at its new location — the drift is line numbers
only, no factual claim of the pages was invalidated by the implementation.
