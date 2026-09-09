package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.lua.LuaBackend;
import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.descriptors.DescriptorAst;
import deal.descriptors.DescriptorParseResult;
import deal.descriptors.DescriptorSyntaxError;
import deal.diagnostics.CompilerDiagnostic;
import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalClassIdentityIndex;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.ir.IrDumper;
import deal.lexer.*;
import deal.module.ExportExtractor;
import deal.module.ModuleIdentityResolver;
import deal.module.ModuleShapeValidator;
import deal.parser.*;
import deal.semantic.ir.SemanticProfile;
import deal.types.Type;
import deal.types.Types;

import java.util.*;

/**
 * The byte-identity gate (ISSUE-0315 /
 * descriptor-identity-propagation D2, Verification 1): for a pinned type
 * corpus — primitives including bytes-typed trees at depth,
 * arrays/nullables, exact sync/async functions, project/external/builtin
 * classes, and the intrinsic {@code Error} — every {@code Type}&rarr;text
 * producer of the compilation emits byte-identical canonical descriptor
 * text:
 *
 * <ul>
 *   <li>{@link CanonicalRuntimeTypeDescriptor#encode(Type)} — the one
 *       service (the authority);</li>
 *   <li>{@link JvmBackend#typeDescriptor(Type)} — the JVM emitter (now a
 *       pure delegate of the service);</li>
 *   <li>{@link IrDumper} — the resolved-type rows of a compiled
 *       fixture;</li>
 *   <li>{@link LuaBackend} — the emitted Lua wrapper signatures and
 *       boundary checks of the same fixture;</li>
 *   <li>the export metadata — the {@link ExportExtractor}-produced host
 *       declared maps serialized into the generated
 *       {@code __rt.load_host} table (Lua) and the JVM host-boundary
 *       descriptors (the JVM host ABI slice).</li>
 * </ul>
 *
 * <p>The JVM leg runs over the backend's current capability set only:
 * rows without a JVM carrier today (table reads of {@code bytes}/
 * {@code bytes | null}, the intrinsic Error table read, and
 * bytes-carrier function values) are pinned as unsupported outside the
 * JVM parity corpus — never claimed as differential-parity rows.  Their
 * byte-identity is pinned through the service, the Lua producer, the IR
 * rows, and the Lua export metadata.</p>
 *
 * <p>Negative pins prove no producer emits the legacy spellings
 * ({@code T[]}, {@code T|null}, rest sigs, bare-name text, the dotted
 * v1.1 emission shape) under dot-free configured roots, and the legal
 * dot-bearing-root/externals projections
 * ({@code @src.models/User} from root text {@code src.models},
 * {@code @$external/host.cfg/ServerConfig}) remain allowed.</p>
 */
public class DescriptorEmissionByteIdentityTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.out.println("FAIL: " + message); }
    }

    private static void fail(String message) {
        failed++;
        System.out.println("FAIL: " + message);
    }

    private static void checkEq(String actual, String expected,
                                String label) {
        if (expected.equals(actual)) { passed++; }
        else {
            failed++;
            System.out.println("FAIL [" + label + "]: expected '"
                + expected + "', got '" + actual + "'");
        }
    }

    // =========================================================================
    // Identity surface (E2 index + the one service)
    // =========================================================================

    private static final Map<String, CanonicalModuleIdentity> CLASSIFICATION =
        classification();

    private static Map<String, CanonicalModuleIdentity> classification() {
        Map<String, CanonicalModuleIdentity> map = new LinkedHashMap<>();
        map.put("", CanonicalModuleIdentity.BuiltinModule.INSTANCE);
        map.put("main", new CanonicalModuleIdentity.ProjectModule(
            new ProjectModuleIdentity("main", "main", List.of())));
        // The legal dot-bearing configured root: its projection
        // (@src.models/User) remains allowed — dots are legal in
        // non-final components.
        map.put("src.models", new CanonicalModuleIdentity.ProjectModule(
            new ProjectModuleIdentity("src.models", "src.models",
                List.of())));
        // The legal dotted externals specifier projection.
        map.put("host.cfg", new CanonicalModuleIdentity.ExternalModule(
            "host.cfg"));
        return map;
    }

    private static final CanonicalClassIdentityIndex INDEX =
        ModuleIdentityResolver.buildIndex(CLASSIFICATION);

    private static final CanonicalRuntimeTypeDescriptor DESCRIPTORS =
        new CanonicalRuntimeTypeDescriptor(INDEX);

    private static Type.Class classType(String name, String modulePath) {
        return Types.classType(name, new CanonicalClassIdentity(
            CLASSIFICATION.get(modulePath), name));
    }

    private static Type.Class userClass() {
        return classType("User", "main");
    }

    private static Type.Class errorClass() {
        return Types.classType("Error", new CanonicalClassIdentity(
            CanonicalModuleIdentity.BuiltinModule.INSTANCE, "Error"));
    }

    private static Type.Class externalClass() {
        return classType("ServerConfig", "host.cfg");
    }

    private static Type.Class dottedRootClass() {
        return classType("User", "src.models");
    }

    // =========================================================================
    // The pinned corpus
    // =========================================================================

    /** One corpus row: a label and the checked type it pins. */
    private record Row(String label, Type type) {}

    private static List<Row> corpus() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("int", Type.Int.INSTANCE));
        rows.add(new Row("number", Type.Number.INSTANCE));
        rows.add(new Row("string", Type.String.INSTANCE));
        rows.add(new Row("boolean", Type.Boolean.INSTANCE));
        rows.add(new Row("null", Type.Null.INSTANCE));
        rows.add(new Row("table", Type.Table.INSTANCE));
        rows.add(new Row("bytes", Type.Bytes.INSTANCE));
        rows.add(new Row("arr_int", Types.array(Type.Int.INSTANCE)));
        rows.add(new Row("arr_bytes", Types.array(Type.Bytes.INSTANCE)));
        rows.add(new Row("nested_arr",
            Types.array(Types.array(Type.Int.INSTANCE))));
        // bytes-typed trees at depth
        rows.add(new Row("nested_bytes", Types.array(Types.array(
            Types.array(Type.Bytes.INSTANCE)))));
        rows.add(new Row("nullable", Types.nullable(Type.Int.INSTANCE)));
        rows.add(new Row("nullable_bytes",
            Types.nullable(Type.Bytes.INSTANCE)));
        rows.add(new Row("nullable_arr",
            Types.nullable(Types.array(Type.Int.INSTANCE))));
        rows.add(new Row("arr_nullable",
            Types.array(Types.nullable(Type.Int.INSTANCE))));
        rows.add(new Row("arr_nullable_bytes",
            Types.array(Types.nullable(Type.Bytes.INSTANCE))));
        rows.add(new Row("user", userClass()));
        rows.add(new Row("user_nullable", Types.nullable(userClass())));
        rows.add(new Row("user_arr", Types.array(userClass())));
        rows.add(new Row("user_arr_nullable",
            Types.array(Types.nullable(userClass()))));
        rows.add(new Row("error", errorClass()));
        rows.add(new Row("fn",
            Types.func(List.of(Type.Int.INSTANCE), Type.Int.INSTANCE)));
        rows.add(new Row("fn_bytes", Types.func(List.of(Type.Bytes.INSTANCE),
            Type.Bytes.INSTANCE)));
        rows.add(new Row("async_fn", Types.func(List.of(Type.Int.INSTANCE),
            Type.Int.INSTANCE, true)));
        rows.add(new Row("async_bytes", Types.func(
            List.of(Types.array(Type.Bytes.INSTANCE)),
            Types.nullable(Type.Bytes.INSTANCE), true)));
        // externals + dot-bearing-root projections (type-level rows)
        rows.add(new Row("external", externalClass()));
        rows.add(new Row("dotted_root", dottedRootClass()));
        return rows;
    }

    // =========================================================================
    // The compiled fixture (one exported function per corpus row)
    // =========================================================================

    /** The fixture function name &rarr; its function type (the same
     * signature the source declares). */
    private static Map<String, Type> fixtureRowTypes() {
        Map<String, Type> rows = new LinkedHashMap<>();
        rows.put("f_int", Types.func(List.of(Type.Int.INSTANCE),
            Type.Int.INSTANCE));
        rows.put("f_number", Types.func(List.of(Type.Number.INSTANCE),
            Type.Number.INSTANCE));
        rows.put("f_string", Types.func(List.of(Type.String.INSTANCE),
            Type.String.INSTANCE));
        rows.put("f_boolean", Types.func(List.of(Type.Boolean.INSTANCE),
            Type.Boolean.INSTANCE));
        rows.put("f_null", Types.func(List.of(Type.Null.INSTANCE),
            Type.Null.INSTANCE));
        rows.put("f_table", Types.func(List.of(Type.Table.INSTANCE),
            Type.Table.INSTANCE));
        rows.put("f_bytes", Types.func(List.of(Type.Bytes.INSTANCE),
            Type.Bytes.INSTANCE));
        rows.put("f_arr_int", Types.func(List.of(Types.array(Type.Int.INSTANCE)),
            Types.array(Type.Int.INSTANCE)));
        rows.put("f_arr_bytes", Types.func(List.of(Types.array(Type.Bytes.INSTANCE)),
            Types.array(Type.Bytes.INSTANCE)));
        rows.put("f_nested_arr", Types.func(
            List.of(Types.array(Types.array(Type.Int.INSTANCE))),
            Types.array(Types.array(Type.Int.INSTANCE))));
        rows.put("f_nested_bytes", Types.func(
            List.of(Types.array(Types.array(Types.array(Type.Bytes.INSTANCE)))),
            Types.array(Types.array(Types.array(Type.Bytes.INSTANCE)))));
        rows.put("f_nullable", Types.func(
            List.of(Types.nullable(Type.Int.INSTANCE)),
            Types.nullable(Type.Int.INSTANCE)));
        rows.put("f_nullable_bytes", Types.func(
            List.of(Types.nullable(Type.Bytes.INSTANCE)),
            Types.nullable(Type.Bytes.INSTANCE)));
        rows.put("f_nullable_arr", Types.func(
            List.of(Types.nullable(Types.array(Type.Int.INSTANCE))),
            Types.nullable(Types.array(Type.Int.INSTANCE))));
        rows.put("f_arr_nullable", Types.func(
            List.of(Types.array(Types.nullable(Type.Int.INSTANCE))),
            Types.array(Types.nullable(Type.Int.INSTANCE))));
        rows.put("f_arr_nullable_bytes", Types.func(
            List.of(Types.array(Types.nullable(Type.Bytes.INSTANCE))),
            Types.array(Types.nullable(Type.Bytes.INSTANCE))));
        rows.put("f_user", Types.func(List.of(userClass()), userClass()));
        rows.put("f_user_nullable", Types.func(
            List.of(Types.nullable(userClass())),
            Types.nullable(userClass())));
        rows.put("f_user_arr", Types.func(List.of(Types.array(userClass())),
            Types.array(userClass())));
        rows.put("f_user_arr_nullable", Types.func(
            List.of(Types.array(Types.nullable(userClass()))),
            Types.array(Types.nullable(userClass()))));
        rows.put("f_err", Types.func(List.of(errorClass()), errorClass()));
        rows.put("f_fn", Types.func(
            List.of(Types.func(List.of(Type.Int.INSTANCE),
                Type.Int.INSTANCE)),
            Types.func(List.of(Type.Int.INSTANCE), Type.Int.INSTANCE)));
        rows.put("f_fn_bytes", Types.func(
            List.of(Types.func(List.of(Type.Bytes.INSTANCE),
                Type.Bytes.INSTANCE)),
            Types.func(List.of(Type.Bytes.INSTANCE), Type.Bytes.INSTANCE)));
        rows.put("f_async", Types.func(List.of(Type.Int.INSTANCE),
            Type.Int.INSTANCE, true));
        rows.put("f_async_bytes", Types.func(
            List.of(Types.array(Type.Bytes.INSTANCE)),
            Types.nullable(Type.Bytes.INSTANCE), true));
        return rows;
    }

    /** Rows outside the JVM carrier set (table reads of bytes / bytes|null,
     * the intrinsic Error read, and bytes-carrier function values): pinned
     * as unsupported, never claimed as JVM parity rows. */
    private static final Set<String> JVM_UNSUPPORTED_ROWS = Set.of(
        "f_bytes", "f_nullable_bytes", "f_err", "f_fn_bytes");

    /** The shared fixture source: one exported function per corpus row,
     * each reading its parameter back through an untyped table field
     * (the {@code $check} dispatch site of the JVM producer). */
    private static String fixtureSource(boolean jvmSlice) {
        StringBuilder sb = new StringBuilder();
        sb.append("import * as host from \"host.cfg\"\n");
        sb.append("class User { x: int = 0; }\n");
        for (Map.Entry<String, Type> entry : fixtureRowTypes().entrySet()) {
            String name = entry.getKey();
            if (jvmSlice && JVM_UNSUPPORTED_ROWS.contains(name)) {
                continue;
            }
            Type.Func func = (Type.Func) entry.getValue();
            Type param = func.paramTypes().get(0);
            Type ret = func.returnType();
            sb.append("export ").append(func.isAsync() ? "async " : "")
                .append("function ").append(name).append("(x: ")
                .append(sourceType(param)).append("): ")
                .append(sourceType(ret)).append(" {");
            if (param instanceof Type.Null) {
                sb.append(" return x; }");
            } else if ("f_async_bytes".equals(name)) {
                sb.append(" let holder: table = { k: x }; let v: ")
                    .append(sourceType(param))
                    .append(" = holder.k; return null; }");
            } else {
                sb.append(" let holder: table = { k: x }; let v: ")
                    .append(sourceType(param))
                    .append(" = holder.k; return v; }");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** The source spelling of a checked type (identity text for classes). */
    private static String sourceType(Type t) {
        if (t instanceof Type.Class c) {
            if (c.identity().moduleIdentity()
                    instanceof CanonicalModuleIdentity.BuiltinModule) {
                return "Error";
            }
            return c.name();
        }
        if (t instanceof Type.Array a) {
            Type element = a.element();
            if (element instanceof Type.Nullable
                    || element instanceof Type.Func) {
                return "(" + sourceType(element) + ")[]";
            }
            return sourceType(element) + "[]";
        }
        if (t instanceof Type.Nullable n) {
            return sourceType(n.inner()) + " | null";
        }
        if (t instanceof Type.Func f) {
            StringBuilder sb = new StringBuilder();
            if (f.isAsync()) sb.append("async ");
            sb.append("(");
            String[] names = {"a", "b", "c", "d", "e"};
            for (int i = 0; i < f.paramTypes().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(names[i]).append(": ")
                    .append(sourceType(f.paramTypes().get(i)));
            }
            sb.append(") => ").append(sourceType(f.returnType()));
            return sb.toString();
        }
        return switch (t) {
            case Type.Null ignored -> "null";
            case Type.Boolean ignored -> "boolean";
            case Type.Int ignored -> "int";
            case Type.Number ignored -> "number";
            case Type.String ignored -> "string";
            case Type.Bytes ignored -> "bytes";
            case Type.Table ignored -> "table";
            default -> throw new IllegalStateException(
                "no source spelling for " + t);
        };
    }

    // =========================================================================
    // The export-metadata declared map (ExportExtractor over a host
    // declaration file — the externals classification applied)
    // =========================================================================

    /** The full host declaration (Lua export metadata: the load_host
     * declared map accepts every canonical shape incl. class exports). */
    private static Map<String, Type> hostDeclaredExports() {
        return declaredExports("""
            export function host_int(x: int): int;
            export function host_bytes(x: bytes): bytes;
            export function host_arr(x: int[]): int[];
            export function host_nullable(x: string | null): string | null;
            export function host_fn(x: (a: int) => int): (a: int) => int;
            export class ServerConfig {
              port: int;
            }
            """);
    }

    /** The reduced host declaration for the JVM host ABI slice (only the
     * scalar shapes the JVM host boundary supports today — the
     * unsupported export shapes stay pinned outside the JVM parity
     * corpus). */
    private static Map<String, Type> jvmHostDeclaredExports() {
        return declaredExports("""
            export function host_int(x: int): int;
            export function host_str(x: string | null): string | null;
            """);
    }

    private static Map<String, Type> declaredExports(String declaration) {
        LexResult lex = new Lexer(declaration, "host.cfg.d.deal")
            .tokenize();
        ParseResult parse = new Parser(lex.tokens(), "host.cfg.d.deal",
            lex.directiveEvents()).parse();
        if (parse.hasErrors()) {
            throw new IllegalStateException("host declaration parse: "
                + parse.diagnostics());
        }
        CanonicalModuleIdentity hostIdentity =
            new CanonicalModuleIdentity.ExternalModule("host.cfg");
        ExportExtractor extractor = new ExportExtractor("host.cfg", true,
            path -> "host.cfg".equals(path) ? hostIdentity : null);
        Map<String, Type> exports = extractor.extract(parse.program());
        if (!extractor.diagnostics().isEmpty()) {
            throw new IllegalStateException("host declaration diagnostics: "
                + extractor.diagnostics());
        }
        return exports;
    }

    // =========================================================================
    // Frontend compile of the fixture (module path "main")
    // =========================================================================

    private record Frontend(ProgramNode program, CheckResult result) {}

    private static Frontend compileFixture(boolean jvmSlice) {
        LexResult lex = new Lexer(fixtureSource(jvmSlice), "main")
            .tokenize();
        if (lex.hasErrors()) {
            throw new IllegalStateException("fixture lex errors: "
                + lex.diagnostics());
        }
        Parser parser = new Parser(lex.tokens(), "main",
            lex.directiveEvents());
        ParseResult parse = parser.parse();
        if (parse.hasErrors()) {
            throw new IllegalStateException("fixture parse errors: "
                + parse.diagnostics());
        }
        ModuleShapeValidator.validate(parse.program(), "main", false)
            .forEach(d -> {
                if ("error".equals(d.severity())) {
                    throw new IllegalStateException("fixture shape error: "
                        + d);
                }
            });
        StubModuleResolver resolver = new StubModuleResolver();
        Map<String, Type> declared = hostDeclaredExports();
        resolver.register("host.cfg", declared);
        NameResolver nr = new NameResolver("main", resolver);
        SymbolTable symbols = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("main", symbols, nr,
            parse.program());
        List<CompilerDiagnostic> errors = new ArrayList<>();
        errors.addAll(nr.diagnostics());
        errors.addAll(result.diagnostics());
        if (errors.stream().anyMatch(d -> "error".equals(d.severity()))) {
            throw new IllegalStateException("fixture check errors: "
                + errors);
        }
        return new Frontend(parse.program(), result);
    }

    // =========================================================================
    // Gates
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Running DescriptorEmissionByteIdentityTest ===\n");

        testTypeLevelByteIdentity();
        testIrProducerByteIdentity();
        testLuaProducerByteIdentity();
        testJvmProducerByteIdentity();
        testExportMetadataByteIdentity();
        testLegacyNegativePins();
        testAllowedDotBearingProjections();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** The one-service authority: every corpus row encodes canonically,
     * {@code render(parse(encode(T))) == encode(T)}, and the JVM static
     * emitter (a pure service delegate) agrees byte-for-byte. */
    private static void testTypeLevelByteIdentity() {
        System.out.println("  testTypeLevelByteIdentity... ");
        for (Row row : corpus()) {
            String expected = DESCRIPTORS.encode(row.type());
            checkEq(JvmBackend.typeDescriptor(row.type()), expected,
                "JvmBackend.typeDescriptor(" + row.label()
                    + ") == encode(" + row.label() + ")");
            DescriptorParseResult parsed =
                CanonicalRuntimeTypeDescriptor.parse(expected);
            if (!(parsed instanceof DescriptorAst ast)
                    || !CanonicalRuntimeTypeDescriptor.render(ast)
                        .equals(expected)) {
                fail("encode(" + row.label() + ") = '" + expected
                    + "' does not round-trip: " + parsed);
            } else {
                passed++;
            }
        }
        System.out.println("OK (" + corpus().size() + " rows)");
    }

    /** IR producer: the compiled fixture's resolved-type rows spell the
     * one-service text byte-for-byte (the {@code ident x} row of each
     * function body carries the specTypeDescriptor of the checked
     * parameter type — the one-service-driven row). */
    private static void testIrProducerByteIdentity() {
        System.out.println("  testIrProducerByteIdentity... ");
        Frontend f = compileFixture(false);
        String ir = IrDumper.dump(f.program(), f.result(), "main", INDEX);
        for (Map.Entry<String, Type> entry : fixtureRowTypes().entrySet()) {
            Type paramType = entry.getValue() instanceof Type.Func func
                ? func.paramTypes().get(0) : null;
            if (paramType == null) {
                continue;
            }
            String expected = DESCRIPTORS.encode(paramType);
            check(ir.contains("ident x : " + expected + " @main:"),
                "IR row " + entry.getKey() + " spells '" + expected
                    + "' byte-for-byte");
        }
        check(ir.contains("ident v : @main/User"),
            "IR class value row spells '@main/User'");
        check(ir.contains("ident v : @$builtin/Error"),
            "IR Error value row spells '@$builtin/Error'");
        check(ir.contains("class User @main:"),
            "IR class row present");
        System.out.println("OK");
    }

    /** Lua producer: every exported wrapper carries
     * {@code __rt.function_("<canonical sig>", ...)} byte-for-byte and
     * every boundary check carries the canonical descriptor. */
    private static void testLuaProducerByteIdentity() {
        System.out.println("  testLuaProducerByteIdentity... ");
        Frontend f = compileFixture(false);
        ModuleIdentityResolver.IdentityIndex standalone =
            ModuleIdentityResolver.buildIndex(CLASSIFICATION);
        LuaBackend backend = new LuaBackend(f.result().typeMap(),
            f.result().symbolTable(), "main", "main", standalone);
        String lua = backend.generateFromInstance(f.program(), false,
            Map.of(), Map.of("host.cfg", hostDeclaredExports()));
        for (Map.Entry<String, Type> entry : fixtureRowTypes().entrySet()) {
            String expected = DESCRIPTORS.encode(entry.getValue());
            check(lua.contains("__rt.function_(\"" + expected + "\""),
                "Lua wrapper " + entry.getKey() + " carries '" + expected
                    + "' byte-for-byte");
        }
        // Boundary-check texts for the composite rows.
        check(lua.contains("__rt.check_array(\"[int]\", x"),
            "Lua int[] check carries '[int]'");
        check(lua.contains("__rt.check_array(\"[bytes]\", x"),
            "Lua bytes[] check carries '[bytes]'");
        check(lua.contains("__rt.check_nullable(\"int\", x"),
            "Lua nullable check carries 'int'");
        check(lua.contains("__rt.check_nullable(\"bytes\", x"),
            "Lua nullable bytes check carries 'bytes'");
        check(lua.contains("__rt.check_type(\"bytes\", x"),
            "Lua bytes check carries 'bytes'");
        check(lua.contains("__rt.check_type(\"@main/User\", x"),
            "Lua class check carries '@main/User'");
        check(lua.contains("__rt.check_type(\"@$builtin/Error\", x"),
            "Lua Error check carries '@$builtin/Error'");
        check(lua.contains("__rt.check_type(\"(int)->int\", x"),
            "Lua function check carries '(int)->int'");
        System.out.println("OK");
    }

    /** JVM producer (the backend's current carrier set): the emitted
     * artifact's {@code $check} call sites spell the one-service text
     * byte-for-byte for every realizable row; rows without a JVM carrier
     * today stay pinned outside this parity corpus. */
    private static void testJvmProducerByteIdentity() {
        System.out.println("  testJvmProducerByteIdentity... ");
        Frontend f = compileFixture(true);
        JvmBackend.JvmCodegenResult res = JvmBackend.generate(
            f.program(), f.result(), "main", "main", Map.of(), Map.of(),
            Map.of("host.cfg", jvmHostDeclaredExports()), false, true,
            SemanticProfile.LEGACY_SAFE_INT);
        check(!res.hasErrors(),
            "JVM fixture codegen clean: " + res.diagnostics());
        if (res.hasErrors()) return;
        String java = res.source();
        for (Map.Entry<String, Type> entry : fixtureRowTypes().entrySet()) {
            if (JVM_UNSUPPORTED_ROWS.contains(entry.getKey())) {
                continue;
            }
            Type paramType = entry.getValue() instanceof Type.Func func
                ? func.paramTypes().get(0) : null;
            if (paramType != null && !(paramType instanceof Type.Null)) {
                String expected = DESCRIPTORS.encode(paramType);
                check(java.contains("$check(\"" + expected + "\", "),
                    "JVM $check for " + entry.getKey() + " carries '"
                        + expected + "' byte-for-byte");
            }
        }
        check(java.contains("\"@main/User\""),
            "JVM class identity text '@main/User' present");
        // The realized bytes rows stay present through the [bytes]
        // carriers (the unsupported rows are the bare bytes /
        // bytes | null table-read targets only).
        check(java.contains("$check(\"[bytes]\", "),
            "JVM artifact carries the realized '[bytes]' array row");
        System.out.println("OK");
    }

    /** Export metadata producer: the ExportExtractor-built host declared
     * maps serialize byte-for-byte into the generated
     * {@code __rt.load_host} table (Lua — the full declared map) and the
     * JVM host-boundary descriptors (the JVM host ABI slice). */
    private static void testExportMetadataByteIdentity() {
        System.out.println("  testExportMetadataByteIdentity... ");
        Map<String, Type> declared = hostDeclaredExports();
        for (Map.Entry<String, Type> entry : declared.entrySet()) {
            String expected = DESCRIPTORS.encode(entry.getValue());
            DescriptorParseResult parsed =
                CanonicalRuntimeTypeDescriptor.parse(expected);
            check(parsed instanceof DescriptorAst ast
                    && CanonicalRuntimeTypeDescriptor.render(ast)
                        .equals(expected),
                "export metadata descriptor for '" + entry.getKey()
                    + "' = '" + expected + "' is canonical and round-trips");
        }
        Frontend f = compileFixture(false);
        ModuleIdentityResolver.IdentityIndex standalone =
            ModuleIdentityResolver.buildIndex(CLASSIFICATION);
        LuaBackend backend = new LuaBackend(f.result().typeMap(),
            f.result().symbolTable(), "main", "main", standalone);
        String lua = backend.generateFromInstance(f.program(), false,
            Map.of(), Map.of("host.cfg", declared));
        for (Map.Entry<String, Type> entry
                : new TreeMap<>(declared).entrySet()) {
            String expected = DESCRIPTORS.encode(entry.getValue());
            check(lua.contains(deal.codegen.lua.LuaAbi.tableField(
                        entry.getKey(), "\"" + expected + "\"") + ","),
                "load_host declared map row '" + entry.getKey() + " = "
                    + expected + "' emitted byte-for-byte");
        }
        check(lua.contains("ServerConfig = \"@$external/host.cfg/ServerConfig\""),
            "host class export carries the externals projection");
        Frontend jvmF = compileFixture(true);
        JvmBackend.JvmCodegenResult res = JvmBackend.generate(
            jvmF.program(), jvmF.result(), "main", "main", Map.of(),
            Map.of(), Map.of("host.cfg", jvmHostDeclaredExports()), false,
            true, SemanticProfile.LEGACY_SAFE_INT);
        check(!res.hasErrors(),
            "JVM host-metadata fixture codegen clean: " + res.diagnostics());
        if (!res.hasErrors()) {
            String java = res.source();
            check(java.contains("\"(int)->int\""),
                "JVM host-boundary descriptor '(int)->int' present");
            check(java.contains("\"?string\""),
                "JVM host-boundary descriptor '?string' present");
        }
        System.out.println("OK");
    }

    /** Negative pins: no producer emits the legacy spellings under
     * dot-free configured roots. */
    private static void testLegacyNegativePins() {
        System.out.println("  testLegacyNegativePins... ");
        Frontend f = compileFixture(false);
        String ir = IrDumper.dump(f.program(), f.result(), "main", INDEX);
        ModuleIdentityResolver.IdentityIndex standalone =
            ModuleIdentityResolver.buildIndex(CLASSIFICATION);
        LuaBackend backend = new LuaBackend(f.result().typeMap(),
            f.result().symbolTable(), "main", "main", standalone);
        String lua = backend.generateFromInstance(f.program(), false,
            Map.of(), Map.of("host.cfg", hostDeclaredExports()));
        Frontend jvmF = compileFixture(true);
        JvmBackend.JvmCodegenResult res = JvmBackend.generate(
            jvmF.program(), jvmF.result(), "main", "main", Map.of(),
            Map.of(), Map.of("host.cfg", jvmHostDeclaredExports()), false,
            true, SemanticProfile.LEGACY_SAFE_INT);
        String java = res.hasErrors() ? "" : res.source();

        // IR pins (raw text on the service-driven rows).
        check(!ir.contains("int[]"), "IR never emits 'int[]'");
        check(!ir.contains("|null"), "IR never emits '|null'");
        check(!ir.contains("..."), "IR never emits a rest sig");
        check(!ir.contains("@host.cfg/"),
            "IR never emits the dotted legacy emission shape");
        check(!ir.contains("@src.models/"),
            "IR never emits the dotted legacy root spelling");

        // Lua pins (quoted descriptor text).
        check(!lua.contains("\"int[]\""),
            "Lua never emits quoted 'int[]'");
        check(!lua.contains("\"string|null\""),
            "Lua never emits quoted 'string|null'");
        check(!lua.contains("|null"),
            "Lua never emits the legacy '|null' nullable spelling");
        check(!lua.contains("\"(string,..."),
            "Lua never emits a rest sig");
        check(!lua.contains("\"Error\""),
            "Lua never emits a bare 'Error' tag");
        check(!lua.contains("\"User\""),
            "Lua never emits a bare class-name tag");
        check(!lua.contains("@host.cfg/"),
            "Lua never emits the dotted legacy emission shape");
        check(!lua.contains("@src.models/"),
            "Lua never emits the dotted legacy root spelling");

        // JVM pins (quoted descriptor text).
        if (!java.isEmpty()) {
            check(!java.contains("\"int[]\""),
                "JVM never emits quoted 'int[]'");
            check(!java.contains("\"string|null\""),
                "JVM never emits quoted 'string|null'");
            check(!java.contains("\"Error\""),
                "JVM never emits a bare 'Error' tag");
            check(!java.contains("\"User\""),
                "JVM never emits a bare class-name tag");
            check(!java.contains("\"@host.cfg/"),
                "JVM never emits the dotted legacy emission shape");
        }
        System.out.println("OK");
    }

    /** Legal dot-bearing projections remain allowed: the dot-bearing
     * configured root text and the dotted externals specifier parse,
     * round-trip, and byte-match their canonical projections. */
    private static void testAllowedDotBearingProjections() {
        System.out.println("  testAllowedDotBearingProjections... ");
        checkEq(DESCRIPTORS.encode(dottedRootClass()), "@src.models/User",
            "encode(rootText 'src.models', User) == '@src.models/User'");
        checkEq(DESCRIPTORS.encode(externalClass()),
            "@$external/host.cfg/ServerConfig",
            "encode(External 'host.cfg', ServerConfig) == "
                + "'@$external/host.cfg/ServerConfig'");
        for (String legal : List.of("@src.models/User",
                "@$external/host.cfg/ServerConfig", "@lib.utils/User")) {
            DescriptorParseResult parsed =
                CanonicalRuntimeTypeDescriptor.parse(legal);
            check(parsed instanceof DescriptorAst.ClassAtom atom
                    && atom.fullDescriptorText().equals(legal)
                    && CanonicalRuntimeTypeDescriptor.render(atom)
                        .equals(legal),
                "legal dot-bearing projection '" + legal
                    + "' parses and round-trips");
        }
        DescriptorParseResult dotted =
            CanonicalRuntimeTypeDescriptor.parse("@src.models.User");
        check(dotted instanceof DescriptorSyntaxError err
                && err.kind() == DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM,
            "dotted class-name text '@src.models.User' stays rejected");
        System.out.println("OK");
    }
}
