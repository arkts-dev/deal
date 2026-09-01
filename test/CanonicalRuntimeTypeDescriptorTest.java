package deal.test;

import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.descriptors.DescriptorAst;
import deal.descriptors.DescriptorParseResult;
import deal.descriptors.DescriptorSyntaxError;
import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalClassIdentityIndex;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.types.Type;
import deal.types.Types;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Tests for the strict canonical runtime type descriptor service
 * (ISSUE-0310/0311): {@link CanonicalRuntimeTypeDescriptor#parse(String)}
 * and {@link CanonicalRuntimeTypeDescriptor#render(DescriptorAst)} with
 * the immutable {@link DescriptorAst} atoms and scalar-offset
 * {@link DescriptorSyntaxError} failures, plus the class-free
 * {@link CanonicalRuntimeTypeDescriptor#encode(Type)} surface with its
 * property round-trip corpus.
 *
 * <p>Coverage:</p>
 * <ul>
 *   <li>Pinned {@code bytes} atom tie with {@code Type.Bytes.INSTANCE}.</li>
 *   <li>Round trips of every primitive and the canonical compound shapes.</li>
 *   <li>The full pinned rejection corpus with the exact syntax-error kind
 *       and 0-based scalar offset per case (legacy {@code T[]}/{@code T|null}/
 *       rest sigs, bare class names, nested nullables, {@code ?null}, empty/
 *       malformed arrays and functions, invalid class atoms, dotted
 *       class-name text, trailing content).</li>
 *   <li>Text-opaque class atoms: distinct {@code /} vs {@code .} spellings,
 *       byte-for-byte equality only to themselves, dotted non-final
 *       components, delimiter-bounded termination, no boundary inference.</li>
 *   <li>Property-style round trips over deterministically generated deep
 *       canonical texts (nesting depth &gt;= 50, multi-component roots,
 *       exact sync/async functions, bytes at arbitrary depth).</li>
 *   <li>Scalar offsets (not UTF-16 code units) for astral input, and the
 *       never-throw contract.</li>
 *   <li>The encode surface (ISSUE-0311 + the ISSUE-0317 identity-carriage
 *       consumption): the per-compilation service over the pinned
 *       (index, module-path classification) constructor contract,
 *       primitives verbatim, {@code [D]}/{@code ?D}, exact sync/async
 *       functions, the class branch through the identity index
 *       ({@code @lib/utils/User} and the {@code @$builtin/Error} builtin
 *       projection), the {@code Type.Error}/{@code Type.Class}
 *       absent-identity internal invariant violations, and the
 *       property-style corpus asserting
 *       {@code render(parse(encode(T))) == encode(T)} byte-identically
 *       for every generated tree with per-member legacy-spelling negative
 *       pins, bytes at depth &gt;= 50, and bytes through sync/async
 *       function parameter/return positions.</li>
 *   <li>The class-bearing encode totality (ISSUE-0314): the pinned
 *       example identities ({@code @lib/utils/User},
 *       {@code @$external/pkg/Cls},
 *       {@code @$external/host.cfg/ServerConfig},
 *       {@code @$builtin/Error}) plus grammatical-but-arbitrary
 *       anti-hollow and dotted-project entries registered in a
 *       contract-conformant in-repo test index; a property corpus over
 *       class-bearing trees nested in arrays/nullables/functions with
 *       byte-identical {@code render(parse(encode(T))) == encode(T)}
 *       round trips, index-text verbatim pass-through, per-member
 *       legacy/bare/dotted negative pins, T2 reverse-lookup pins, and
 *       index-miss invariant violations at any nesting depth.</li>
 * </ul>
 */
public class CanonicalRuntimeTypeDescriptorTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== Running Canonical Runtime Type Descriptor Tests (ISSUE-0310/0311/0314) ===");

        testBytesAtomTie();
        testPrimitiveRoundTrips();
        testValidShapeCorpus();
        testClassAtomShapes();
        testOpaqueClassAtoms();
        testDelimiterTermination();
        testExactSyncAsyncMarkers();
        testRenderOfHandBuiltAtoms();
        testRejectionCorpus();
        testScalarOffsets();
        testHostileInputsNeverThrow();
        testAtomImmutability();
        testPropertyRoundTrips();
        testDeepNesting();

        testEncodeServiceContract();
        testEncodePrimitives();
        testEncodePinnedShapes();
        testEncodeInvariantViolations();
        testEncodeClassBranch();
        testEncodeClassBearingIndexContract();
        testEncodeClassBearingPins();
        testEncodeClassBearingIndexMiss();
        testEncodeClassBearingCorpus();
        testEncodeClassBearingDeepNesting();
        testEncodePropertyCorpus();
        testEncodeDeepNesting();

        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void check(boolean condition, String label) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL [" + label + "]");
        }
    }

    private static void fail(String label, String detail) {
        failed++;
        System.out.println("FAIL [" + label + "]: " + detail);
    }

    private static DescriptorParseResult parseResult(String text) {
        DescriptorParseResult result = CanonicalRuntimeTypeDescriptor.parse(text);
        if (result == null) {
            failed++;
            System.out.println("FAIL: parse returned null for " + quote(text));
        }
        return result;
    }

    private static DescriptorAst atomOf(String text) {
        DescriptorParseResult result = parseResult(text);
        return result instanceof DescriptorAst ast ? ast : null;
    }

    /**
     * Asserts that {@code text} parses to a complete atom,
     * {@code render(parse(text))} equals {@code text} byte-for-byte, and
     * {@code parse(render(ast))} round-trips to an equal atom.
     */
    private static void assertAtom(String text, String label) {
        DescriptorParseResult result = parseResult(text);
        if (!(result instanceof DescriptorAst ast)) {
            fail(label, "expected a parsed atom for " + quote(text)
                + " but got " + result);
            return;
        }
        check(true, label + ": parses");
        String rendered = CanonicalRuntimeTypeDescriptor.render(ast);
        if (!text.equals(rendered)) {
            fail(label, "render(parse(...)) mismatch for " + quote(text)
                + ": rendered " + quote(rendered));
        } else {
            check(true, label + ": render is byte-identical");
        }
        DescriptorParseResult again = parseResult(rendered);
        if (!(again instanceof DescriptorAst ast2) || !ast2.equals(ast)) {
            fail(label, "parse(render(ast)) did not round-trip to an equal atom for "
                + quote(text) + ": " + again);
        } else {
            check(true, label + ": parse(render(ast)) round-trips");
        }
    }

    /** Asserts the exact pinned kind and 0-based scalar offset of a rejection. */
    private static void assertError(String text, DescriptorSyntaxError.Kind kind,
                                    int offset, String label) {
        DescriptorParseResult result = parseResult(text);
        if (!(result instanceof DescriptorSyntaxError error)) {
            fail(label, "expected DescriptorSyntaxError " + kind + "@" + offset
                + " for " + quote(text) + " but got atom " + result);
            return;
        }
        if (error.kind() != kind || error.scalarOffset() != offset) {
            fail(label, "for " + quote(text) + " expected " + kind + "@" + offset
                + " but got " + error.kind() + "@" + error.scalarOffset()
                + " (" + error.message() + ")");
        } else {
            check(true, label + ": " + kind + "@" + offset + " for " + quote(text));
        }
        if (error.message() == null || error.message().isEmpty()) {
            fail(label, "empty message for " + quote(text));
        } else {
            check(true, label + ": message present for " + quote(text));
        }
    }

    private static String quote(String text) {
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x20 && c <= 0x7E) {
                sb.append(c);
            } else {
                sb.append(String.format("\\u%04X", (int) c));
            }
        }
        return sb.append('\'').toString();
    }

    /** Maximum nesting depth of an atom tree (each wrapper/param adds one level). */
    private static int maxDepth(DescriptorAst ast) {
        if (ast instanceof DescriptorAst.ArrayAtom a) {
            return 1 + maxDepth(a.element());
        }
        if (ast instanceof DescriptorAst.NullableAtom n) {
            return 1 + maxDepth(n.inner());
        }
        if (ast instanceof DescriptorAst.FunctionAtom f) {
            int depth = 1 + maxDepth(f.returnType());
            for (DescriptorAst param : f.params()) {
                depth = Math.max(depth, 1 + maxDepth(param));
            }
            return depth;
        }
        return 0;
    }

    // =========================================================================
    // Pinned bytes atom tie with Type.Bytes.INSTANCE (combined check with T1)
    // =========================================================================

    static void testBytesAtomTie() {
        System.out.println("-- bytes atom tie with Type.Bytes.INSTANCE --");

        check(Type.Bytes.INSTANCE != null && Type.Bytes.INSTANCE instanceof Type.Bytes,
            "Type.Bytes.INSTANCE is the bytes singleton");
        check(Type.Bytes.INSTANCE == Type.Bytes.INSTANCE,
            "Type.Bytes.INSTANCE reference identity");
        check("bytes".equals(CanonicalRuntimeTypeDescriptor.BYTES_DESCRIPTOR),
            "pinned BYTES_DESCRIPTOR constant equals \"bytes\"");

        DescriptorParseResult result = parseResult(CanonicalRuntimeTypeDescriptor.BYTES_DESCRIPTOR);
        check(result instanceof DescriptorAst.PrimitiveAtom p
                && p.name().equals(CanonicalRuntimeTypeDescriptor.BYTES_DESCRIPTOR),
            "parse(BYTES_DESCRIPTOR) yields PrimitiveAtom whose name is the pinned constant");
        String rendered = result instanceof DescriptorAst ast
            ? CanonicalRuntimeTypeDescriptor.render(ast) : null;
        check("bytes".equals(rendered), "bytes atom renders back byte-identically");
        check(new DescriptorAst.PrimitiveAtom("bytes").equals(result),
            "bytes atom equals PrimitiveAtom(\"bytes\")");
    }

    // =========================================================================
    // Primitive round trips
    // =========================================================================

    static void testPrimitiveRoundTrips() {
        System.out.println("-- primitive round trips --");

        String[] primitives = {
            "null", "boolean", "int", "number", "string", "bytes", "table"
        };
        for (String primitive : primitives) {
            assertAtom(primitive, "primitive " + primitive);
            DescriptorAst atom = atomOf(primitive);
            check(atom instanceof DescriptorAst.PrimitiveAtom p
                    && p.name().equals(primitive),
                "primitive " + primitive + " has the exact atom name");
        }
    }

    // =========================================================================
    // Canonical compound shapes
    // =========================================================================

    static void testValidShapeCorpus() {
        System.out.println("-- valid canonical shapes --");

        String[] valid = {
            // Arrays / nullables, nested.
            "[int]", "?int", "[?int]", "?[int]", "[[int]]", "[?[?[?[bytes]]]]",
            "[bytes]", "?bytes", "[?bytes]", "?[bytes]",
            // Spec examples (docs/spec-v1.2.md runtime descriptor format).
            "?[@src/app/User]", "[?@src/app/User]", "(int,string)->boolean",
            "()->null", "async(int)->string",
            // Exact sync/async functions over bytes at depth.
            "(bytes)->bytes", "async(bytes)->bytes",
            "(int,string,?[bytes])->table", "async(?int)->[bytes]",
            "((bytes)->bytes)->null", "async((bytes)->bytes)->[bytes]",
            // Class atoms in every position.
            "(@lib/utils/User)->null", "()->@lib/utils/User",
            "[@lib/utils/User]", "?@lib/utils/User",
            "[?@$external/host.cfg/ServerConfig]",
            // Multi-component roots stay text-opaque.
            "@lib/utils/User", "@lib/utils/models/User",
            // Dots are legal inside non-final components.
            "@lib.utils/User", "@src.models/User", "@$external/host.cfg/ServerConfig",
            // Hyphens are legal component scalars; only contiguous -> is not.
            "@a-b/c",
            "@$builtin/Error", "@$external/pkg/Cls",
        };
        for (String text : valid) {
            assertAtom(text, "valid shape " + quote(text));
        }
    }

    // =========================================================================
    // Class atom shapes
    // =========================================================================

    static void testClassAtomShapes() {
        System.out.println("-- class atom shapes --");

        check(atomOf("@lib/utils/User") instanceof DescriptorAst.ClassAtom c
                && c.fullDescriptorText().equals("@lib/utils/User"),
            "multi-component root atom carries the full text with the leading '@'");
        check(atomOf("@$builtin/Error") instanceof DescriptorAst.ClassAtom c
                && c.fullDescriptorText().equals("@$builtin/Error"),
            "builtin atom carries '@$builtin/Error' verbatim");
        check(atomOf("@lib.utils/User") instanceof DescriptorAst.ClassAtom c
                && c.fullDescriptorText().equals("@lib.utils/User"),
            "dotted non-final component atom carries '@lib.utils/User' verbatim");
    }

    // =========================================================================
    // Text-opaque atoms: byte-for-byte equality, no boundary inference
    // =========================================================================

    static void testOpaqueClassAtoms() {
        System.out.println("-- opaque class atoms --");

        DescriptorAst slashSpelling = atomOf("@lib/utils/User");
        DescriptorAst dotSpelling = atomOf("@lib.utils/User");
        DescriptorAst slashSpelling2 = atomOf("@lib/utils/User");

        check(slashSpelling != null && dotSpelling != null,
            "both '@lib/utils/User' and '@lib.utils/User' parse");
        check(!slashSpelling.equals(dotSpelling),
            "'@lib/utils/User' and '@lib.utils/User' are distinct atoms");
        check(slashSpelling.equals(slashSpelling2),
            "each atom equals only its own byte-for-byte text");
        check(!slashSpelling.equals(atomOf("@lib/utils/UserX")),
            "a text delta makes atoms unequal");

        check(!atomOf("@a.b/c").equals(atomOf("@a/b.c")),
            "equality never crosses '/' vs '.' spellings");
        check(atomOf("@a.b/c").equals(atomOf("@a.b/c")),
            "dotted non-final spelling equals itself byte-for-byte");

        // Dotted class-name-position text is parse-rejected while the v1.1
        // dotted-root emission shape stays a grammatically valid opaque atom.
        assertError("@src.models.User", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM,
            1, "dotted class-name '@src.models.User' rejected");
        assertAtom("@src.models/User", "dotted root emission shape parses as opaque");
        assertError("@host.cfg.ServerConfig", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM,
            1, "dotted class-name '@host.cfg.ServerConfig' rejected");
        assertAtom("@$external/host.cfg/ServerConfig",
            "dotted externals projection '@$external/host.cfg/ServerConfig' is valid");
    }

    // =========================================================================
    // Atoms end exactly at ']', ')', ',', and end-of-input
    // =========================================================================

    static void testDelimiterTermination() {
        System.out.println("-- delimiter termination --");

        // ']' — array elements.
        DescriptorAst array = atomOf("[@a/b]");
        check(array instanceof DescriptorAst.ArrayAtom a
                && a.element() instanceof DescriptorAst.ClassAtom c
                && c.fullDescriptorText().equals("@a/b"),
            "class atom inside [D] ends exactly at ']'");

        // ')' — function parameters.
        DescriptorAst func = atomOf("(@a/b)->null");
        check(func instanceof DescriptorAst.FunctionAtom f
                && f.params().size() == 1
                && f.params().get(0) instanceof DescriptorAst.ClassAtom c
                && c.fullDescriptorText().equals("@a/b"),
            "class atom inside parameters ends exactly at ')'");

        // ',' — parameter lists.
        DescriptorAst two = atomOf("(@a/b,@c/d)->null");
        check(two instanceof DescriptorAst.FunctionAtom f
                && f.params().size() == 2
                && f.params().get(0) instanceof DescriptorAst.ClassAtom c0
                && c0.fullDescriptorText().equals("@a/b")
                && f.params().get(1) instanceof DescriptorAst.ClassAtom c1
                && c1.fullDescriptorText().equals("@c/d"),
            "first class atom inside parameters ends exactly at ','");

        // End of input.
        check(atomOf("@a/b") instanceof DescriptorAst.ClassAtom c
                && c.fullDescriptorText().equals("@a/b"),
            "top-level class atom ends exactly at end of input");

        // Nested delimiter positions.
        assertAtom("[[@a/b]]", "double-nested array delimits the class atom");
        assertAtom("[?@a/b]", "nullable class atom inside an array");
        assertAtom("[(@a/b)->null]", "function with class param inside an array");
        assertAtom("(?@a/b)->null", "nullable class atom as a function parameter");
        assertAtom("(@lib/utils/User,@$builtin/Error)->[bytes]",
            "multi-component and builtin atoms in one parameter list");
    }

    // =========================================================================
    // Exact sync/async markers
    // =========================================================================

    static void testExactSyncAsyncMarkers() {
        System.out.println("-- exact sync/async markers --");

        DescriptorAst sync = atomOf("(int)->int");
        DescriptorAst async = atomOf("async(int)->int");
        check(sync instanceof DescriptorAst.FunctionAtom sf && !sf.isAsync(),
            "sync function atom carries isAsync=false");
        check(async instanceof DescriptorAst.FunctionAtom af && af.isAsync(),
            "async function atom carries isAsync=true");
        check(!sync.equals(async), "sync and async atoms are distinct");
        check(atomOf("(int)->int").equals(atomOf("(int)->int")),
            "sync atom equals itself");
        check(atomOf("async(int)->int").equals(atomOf("async(int)->int")),
            "async atom equals itself");
        assertAtom("async(int)->string", "async marker round-trips verbatim");
        assertAtom("()->null", "zero-parameter function round-trips verbatim");
        assertAtom("async()->null", "async zero-parameter function round-trips verbatim");
    }

    // =========================================================================
    // Verbatim rendering of hand-built atoms
    // =========================================================================

    static void testRenderOfHandBuiltAtoms() {
        System.out.println("-- verbatim rendering of hand-built atoms --");

        DescriptorAst fn = new DescriptorAst.FunctionAtom(
            true,
            List.of(new DescriptorAst.PrimitiveAtom("int"),
                    new DescriptorAst.ArrayAtom(new DescriptorAst.PrimitiveAtom("bytes"))),
            new DescriptorAst.ClassAtom("@lib/utils/User"));
        check("async(int,[bytes])->@lib/utils/User".equals(
                CanonicalRuntimeTypeDescriptor.render(fn)),
            "render rebuilds async(int,[bytes])->@lib/utils/User verbatim");
        DescriptorParseResult reparsed = parseResult(CanonicalRuntimeTypeDescriptor.render(fn));
        check(reparsed.equals(fn), "parse(render(hand-built atom)) equals the atom");

        DescriptorAst opaque = new DescriptorAst.ClassAtom("@lib.utils/User");
        check("@lib.utils/User".equals(CanonicalRuntimeTypeDescriptor.render(opaque)),
            "ClassAtom renders its full text unchanged");
    }

    // =========================================================================
    // Full pinned rejection corpus with exact kinds and scalar offsets
    // =========================================================================

    static void testRejectionCorpus() {
        System.out.println("-- pinned rejection corpus --");

        // Legacy T[] / T|null / rest-parameter spellings.
        assertError("int[]", DescriptorSyntaxError.Kind.TRAILING_CONTENT, 3,
            "legacy int[] rejected");
        assertError("int|null", DescriptorSyntaxError.Kind.TRAILING_CONTENT, 3,
            "legacy int|null rejected");
        assertError("table[]", DescriptorSyntaxError.Kind.TRAILING_CONTENT, 5,
            "legacy table[] rejected");
        assertError("int...", DescriptorSyntaxError.Kind.TRAILING_CONTENT, 3,
            "legacy int... rejected");
        assertError("User[]", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER, 4,
            "legacy User[] rejected");
        assertError("User|null", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER, 4,
            "legacy User|null rejected");
        assertError("?User[]", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER, 5,
            "legacy ?User[] rejected");
        assertError("?table[]", DescriptorSyntaxError.Kind.TRAILING_CONTENT, 6,
            "legacy ?table[] rejected");
        assertError("...int[]", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER, 0,
            "rest-shaped ...int[] rejected");
        assertError("(int...)", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER, 4,
            "rest parameter sig (int...) rejected");
        assertError("(...int[])", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER, 1,
            "rest parameter sig (...int[]) rejected");
        assertError("[...int]", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER, 1,
            "rest-shaped array element rejected");

        // Bare class names.
        assertError("Error", DescriptorSyntaxError.Kind.BARE_CLASS_NAME, 0,
            "bare builtin Error rejected");
        assertError("User", DescriptorSyntaxError.Kind.BARE_CLASS_NAME, 0,
            "bare class name User rejected");
        assertError("?Error", DescriptorSyntaxError.Kind.BARE_CLASS_NAME, 1,
            "bare Error inside a nullable rejected");
        assertError("(Error)->null", DescriptorSyntaxError.Kind.BARE_CLASS_NAME, 1,
            "bare Error as a parameter rejected");
        assertError("()->Error", DescriptorSyntaxError.Kind.BARE_CLASS_NAME, 4,
            "bare Error as a return rejected");

        // Nested nullables and null inners.
        assertError("??int", DescriptorSyntaxError.Kind.NESTED_NULLABLE, 1,
            "nested nullable ??int rejected");
        assertError("??bytes", DescriptorSyntaxError.Kind.NESTED_NULLABLE, 1,
            "nested nullable ??bytes rejected");
        assertError("?null", DescriptorSyntaxError.Kind.NULL_INNER, 1,
            "nullable of null rejected");
        assertError("[??int]", DescriptorSyntaxError.Kind.NESTED_NULLABLE, 2,
            "nested nullable inside an array rejected");
        assertError("[?null]", DescriptorSyntaxError.Kind.NULL_INNER, 2,
            "nullable of null inside an array rejected");

        // Empty and malformed arrays.
        assertError("[]", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER, 1,
            "empty array [] rejected");
        assertError("[int", DescriptorSyntaxError.Kind.UNEXPECTED_END, 4,
            "unterminated array rejected");

        // Malformed functions.
        assertError("(int)int", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER, 5,
            "missing arrow (int)int rejected");
        assertError("(int)", DescriptorSyntaxError.Kind.UNEXPECTED_END, 5,
            "missing arrow (int) rejected");
        assertError("()", DescriptorSyntaxError.Kind.UNEXPECTED_END, 2,
            "missing arrow () rejected");
        assertError("(int,)", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER, 5,
            "trailing comma (int,) rejected");
        assertError("(int)->", DescriptorSyntaxError.Kind.UNEXPECTED_END, 7,
            "missing return type rejected");

        // Malformed async markers.
        assertError("async[int]", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER, 5,
            "async not immediately followed by '(' rejected");
        assertError("async", DescriptorSyntaxError.Kind.UNEXPECTED_END, 5,
            "bare async rejected");
        assertError("Async(...)", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER, 5,
            "capitalized Async(...) rejected");

        // Invalid class atoms.
        assertError("@", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM, 1,
            "lone @ rejected");
        assertError("@a//b", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM, 3,
            "empty component @a//b rejected");
        assertError("@a/", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM, 3,
            "trailing slash @a/ rejected");
        assertError("@.", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM, 1,
            "'.' component rejected");
        assertError("@..", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM, 1,
            "'..' component rejected");
        assertError("@a->b", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM, 2,
            "contiguous -> component rejected");
        assertError("@a/b->c", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM, 4,
            "contiguous -> inside a later component rejected");
        assertError("@Foo", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM, 4,
            "single-component @Foo rejected");
        assertError("@a b/c", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM, 2,
            "whitespace-containing single component rejected");
        assertError("@a\u0085b/C", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM, 2,
            "U+0085 NEXT LINE component rejected (pinned White_Space property)");
        assertError("@a\u00A0b/C", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM, 2,
            "U+00A0 NO-BREAK SPACE component rejected");

        // Dotted class-name-position forms.
        assertError("@src.models.User", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM, 1,
            "dotted class-name @src.models.User rejected");
        assertError("@a/b.C", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM, 3,
            "dotted class-name @a/b.C rejected");
        assertError("@host.cfg.ServerConfig", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM, 1,
            "dotted class-name @host.cfg.ServerConfig rejected");
        assertError("@a/User.", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM, 3,
            "trailing dot in final component rejected");
        assertError("@a/b-c", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM, 3,
            "hyphenated final component rejected");

        // Trailing content after a complete descriptor.
        assertError("[int][int]", DescriptorSyntaxError.Kind.TRAILING_CONTENT, 5,
            "two descriptors are trailing content");
        assertError("nullx", DescriptorSyntaxError.Kind.TRAILING_CONTENT, 4,
            "nullx has trailing content");
        assertError("@a/b c", DescriptorSyntaxError.Kind.TRAILING_CONTENT, 4,
            "trailing junk after a class atom rejected");
        assertError("@a/b\u0000c", DescriptorSyntaxError.Kind.TRAILING_CONTENT, 4,
            "NUL after a class atom rejected");
        assertError("int\u2028", DescriptorSyntaxError.Kind.TRAILING_CONTENT, 3,
            "line separator after int rejected");

        // Empty and malformed starts.
        assertError("", DescriptorSyntaxError.Kind.UNEXPECTED_END, 0,
            "empty input rejected");
        assertError("?", DescriptorSyntaxError.Kind.UNEXPECTED_END, 1,
            "lone ? rejected");
        assertError("[", DescriptorSyntaxError.Kind.UNEXPECTED_END, 1,
            "lone [ rejected");
        assertError(" ", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER, 0,
            "space rejected");
        assertError(")", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER, 0,
            "lone ) rejected");
        assertError(",", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER, 0,
            "lone , rejected");
        assertError("->", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER, 0,
            "lone arrow rejected");
        assertError("\u0000", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER, 0,
            "NUL rejected");
        assertError("\uD800", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER, 0,
            "lone surrogate rejected");
        assertError("@a/\uD800", DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM, 3,
            "lone surrogate component rejected");
    }

    // =========================================================================
    // 0-based scalar offsets, not UTF-16 code units
    // =========================================================================

    static void testScalarOffsets() {
        System.out.println("-- scalar offsets over astral input --");

        String emoji = "\uD83D\uDE00"; // U+1F600 as a surrogate pair

        // '[', emoji (one scalar, two UTF-16 units), ']': the error offset is
        // the scalar offset 1, not the UTF-16 code-unit offset 2.
        assertError("[" + emoji + "]", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER,
            1, "astral scalar inside an array reported at scalar offset 1");

        // '(', '@', 'x', '/', 'y', ',', emoji, ')': scalar offsets 0..7; the
        // emoji parameter starts at scalar offset 6.
        assertError("(@x/y," + emoji + ")", DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER,
            6, "astral scalar in a parameter list reported at scalar offset 6");

        // A valid astral scalar inside a non-final component round-trips.
        assertAtom("@" + emoji + "/User", "astral non-final component round-trips");

        // The final component must be identifier-shaped: an astral scalar fails.
        assertError("@a/" + emoji, DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM,
            3, "astral final component rejected at its scalar offset");

        // Trailing astral content after int sits at scalar offset 3.
        assertError("int" + emoji, DescriptorSyntaxError.Kind.TRAILING_CONTENT, 3,
            "astral trailing content reported at scalar offset 3");
    }

    // =========================================================================
    // parse never throws
    // =========================================================================

    static void testHostileInputsNeverThrow() {
        System.out.println("-- never-throw contract --");

        String[] hostile = {
            "", " ", "\u0000", "\u0007", "\u007F", "\u0085", "\u00A0", "\u2028",
            "\uFEFF", "\uD800", "\uD800\uD800", "\uDFFF",
            "?", "??", "???", "[", "]", "[[]", "[[[]]]", "(((", ")))", ",", ",,,",
            "->", "-->", "@", "@@", "@@@", "@a/", "@a//b", "@a/b/", "@a/b//",
            "@a/\uD800", "@a/b\uD800", "async", "async(", "async->", "async[",
            "async\u0000(", "(int", "(int,", "(int,,int)", "(int)->", "(int)->x",
            "(int)x", "[@a/b", "[@a/b[", "int\u0000", "?", "😀", "int😀",
            "@a/b,", ",@a/b", "-\uD800>", "@a\u00A0b/c", "@a/b\u2028",
            "boolean\u0000", "\u0085int",
        };
        for (String text : hostile) {
            DescriptorParseResult result = null;
            Throwable thrown = null;
            try {
                result = CanonicalRuntimeTypeDescriptor.parse(text);
            } catch (Throwable t) {
                thrown = t;
            }
            check(thrown == null && result != null
                    && (result instanceof DescriptorAst
                        || result instanceof DescriptorSyntaxError),
                "parse returns a value and never throws for " + quote(text)
                    + (thrown != null ? " (threw " + thrown + ")" : ""));
        }
    }

    // =========================================================================
    // Atom immutability
    // =========================================================================

    static void testAtomImmutability() {
        System.out.println("-- atom immutability --");

        List<DescriptorAst> params = new ArrayList<>();
        params.add(new DescriptorAst.PrimitiveAtom("int"));
        DescriptorAst.FunctionAtom fn = new DescriptorAst.FunctionAtom(
            false, params, new DescriptorAst.PrimitiveAtom("null"));
        params.clear();
        check(fn.params().size() == 1,
            "FunctionAtom defensively copies its parameter list");

        boolean throwsOnMutate = false;
        try {
            fn.params().add(new DescriptorAst.PrimitiveAtom("string"));
        } catch (UnsupportedOperationException e) {
            throwsOnMutate = true;
        }
        check(throwsOnMutate, "FunctionAtom parameter list is immutable");
    }

    // =========================================================================
    // Property-style round trips over generated canonical texts
    // =========================================================================

    private static final String[] CLASS_ATOMS = {
        "@lib/utils/User",
        "@lib.utils/User",
        "@src.models/User",
        "@$external/pkg/Cls",
        "@$external/host.cfg/ServerConfig",
        "@$builtin/Error",
    };

    private static final String[] LEAVES = {
        "null", "boolean", "int", "number", "string", "bytes", "table"
    };

    private static final String[] NON_NULL_LEAVES = {
        "boolean", "int", "number", "string", "bytes", "table"
    };

    private static String leaf(Random rnd) {
        if (rnd.nextInt(3) == 0) {
            return CLASS_ATOMS[rnd.nextInt(CLASS_ATOMS.length)];
        }
        return LEAVES[rnd.nextInt(LEAVES.length)];
    }

    private static String leafOrClass(Random rnd) {
        if (rnd.nextInt(3) == 0) {
            return CLASS_ATOMS[rnd.nextInt(CLASS_ATOMS.length)];
        }
        return NON_NULL_LEAVES[rnd.nextInt(NON_NULL_LEAVES.length)];
    }

    private static String genFunction(Random rnd, int depth) {
        boolean async = rnd.nextBoolean();
        int count = rnd.nextInt(4);
        List<String> params = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            params.add(genType(rnd, depth - 1));
        }
        String ret = genType(rnd, depth - 1);
        return (async ? "async" : "") + "(" + String.join(",", params) + ")->" + ret;
    }

    private static String genNonNullable(Random rnd, int depth) {
        if (depth <= 0) {
            return leafOrClass(rnd);
        }
        switch (rnd.nextInt(4)) {
            case 0:
                return leafOrClass(rnd);
            case 1:
                return "[" + genType(rnd, depth - 1) + "]";
            case 2:
                return genFunction(rnd, depth - 1);
            default:
                return CLASS_ATOMS[rnd.nextInt(CLASS_ATOMS.length)];
        }
    }

    private static String genType(Random rnd, int depth) {
        if (depth <= 0) {
            return leaf(rnd);
        }
        switch (rnd.nextInt(4)) {
            case 0:
                return leaf(rnd);
            case 1:
                return "[" + genType(rnd, depth - 1) + "]";
            case 2:
                // Nullable only ever wraps a non-nullable, non-null inner.
                return "?" + genNonNullable(rnd, depth - 1);
            default:
                return genFunction(rnd, depth - 1);
        }
    }

    static void testPropertyRoundTrips() {
        System.out.println("-- property round trips over generated canonical texts --");

        Random rnd = new Random(0x5EED_CAFE_0310L);
        for (int i = 0; i < 400; i++) {
            String text = genType(rnd, 10);
            assertAtom(text, "generated round trip " + i);
        }
    }

    // =========================================================================
    // Deep nesting (depth >= 50)
    // =========================================================================

    static void testDeepNesting() {
        System.out.println("-- deep nesting (depth >= 50) --");

        final int depth = 60;

        // Pure array nesting around bytes.
        String arrayText = "[".repeat(depth) + "bytes" + "]".repeat(depth);
        assertAtom(arrayText, "array nesting at depth " + depth);
        check(maxDepth(atomOf(arrayText)) >= depth,
            "array atom tree reaches depth " + depth);

        // Alternating array/nullable nesting around bytes.
        String alternated = "bytes";
        for (int i = 0; i < depth; i++) {
            alternated = (i % 2 == 0) ? "[" + alternated + "]" : "?" + alternated;
        }
        assertAtom(alternated, "array/nullable alternation at depth " + depth);
        check(maxDepth(atomOf(alternated)) >= depth,
            "alternated atom tree reaches depth " + depth);

        // Exact sync/async function nesting with bytes through params/returns.
        String functions = "bytes";
        for (int i = 0; i < depth; i++) {
            functions = (i % 2 == 0 ? "async(" : "(") + functions + ")->bytes";
        }
        assertAtom(functions, "sync/async function nesting at depth " + depth);
        check(maxDepth(atomOf(functions)) >= depth,
            "function atom tree reaches depth " + depth);

        // Mixed array/nullable/function nesting around bytes.
        String mixed = "bytes";
        for (int i = 0; i < depth; i++) {
            mixed = (i % 3 == 0) ? "[" + mixed + "]"
                : (i % 3 == 1) ? "?" + mixed
                : "async(" + mixed + ")->bytes";
        }
        assertAtom(mixed, "mixed nesting at depth " + depth);
        check(maxDepth(atomOf(mixed)) >= depth,
            "mixed atom tree reaches depth " + depth);
    }

    // =========================================================================
    // Class-free encode (ISSUE-0311): the one Type->text authority
    // =========================================================================

    /**
     * The per-compilation service instance for the class-free encode
     * corpus.  The index is empty and contract-conformant: every lookup
     * is an absent lookup and raises the pinned invariant violation.  No
     * class-free corpus member consults the index because the generated
     * type trees contain no {@link Type.Class}; the class-branch pins
     * use the registered index of {@link #testEncodeClassBranch()}.
     */
    private static final CanonicalClassIdentityIndex CLASS_FREE_INDEX =
        new CanonicalClassIdentityIndex() {
            @Override
            public String descriptorTextFor(CanonicalClassIdentity identity) {
                throw new IllegalStateException(
                    "absent from the class-free test index: " + identity);
            }

            @Override
            public CanonicalClassIdentity identityForDescriptorText(
                    String descriptorText) {
                throw new IllegalStateException(
                    "absent from the class-free test index: \""
                    + descriptorText + "\"");
            }
        };

    /** The per-compilation service under test: the v1.2 identity-carriage
     * service takes only the identity index — class text comes from
     * {@code index.descriptorTextFor(identity)} on the class type's
     * carried identity. */
    private static final CanonicalRuntimeTypeDescriptor ENCODER =
        new CanonicalRuntimeTypeDescriptor(CLASS_FREE_INDEX);

    /** The canonical primitive keyword set (encode never emits a bare name). */
    private static final Set<String> PRIMITIVE_KEYWORDS_SET =
        Set.of("null", "boolean", "int", "number", "string", "bytes", "table");

    private static final Type[] PRIMITIVE_TYPES = {
        Type.Null.INSTANCE,
        Type.Boolean.INSTANCE,
        Type.Int.INSTANCE,
        Type.Number.INSTANCE,
        Type.String.INSTANCE,
        Type.Bytes.INSTANCE,
        Type.Table.INSTANCE,
    };

    private static final Type[] NON_NULL_PRIMITIVE_TYPES = {
        Type.Boolean.INSTANCE,
        Type.Int.INSTANCE,
        Type.Number.INSTANCE,
        Type.String.INSTANCE,
        Type.Bytes.INSTANCE,
        Type.Table.INSTANCE,
    };

    private static Type genLeafTypeTree(Random rnd) {
        // Bytes bias: every third leaf is bytes so the corpus covers bytes
        // in every structural position.
        if (rnd.nextInt(3) == 0) {
            return Type.Bytes.INSTANCE;
        }
        return PRIMITIVE_TYPES[rnd.nextInt(PRIMITIVE_TYPES.length)];
    }

    private static Type genFunctionTypeTree(Random rnd, int depth) {
        boolean async = rnd.nextBoolean();
        int count = rnd.nextInt(4);
        List<Type> params = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            params.add(genTypeTree(rnd, depth - 1));
        }
        return new Type.Func(params, genTypeTree(rnd, depth - 1), async);
    }

    /** A non-null, non-nullable type (a legal {@code ?D} inner). */
    private static Type genNonNullableTypeTree(Random rnd, int depth) {
        if (depth <= 0) {
            return NON_NULL_PRIMITIVE_TYPES[rnd.nextInt(NON_NULL_PRIMITIVE_TYPES.length)];
        }
        switch (rnd.nextInt(4)) {
            case 0:
                return NON_NULL_PRIMITIVE_TYPES[rnd.nextInt(NON_NULL_PRIMITIVE_TYPES.length)];
            case 1:
                return new Type.Array(genTypeTree(rnd, depth - 1));
            case 2:
                return genFunctionTypeTree(rnd, depth - 1);
            default:
                return NON_NULL_PRIMITIVE_TYPES[rnd.nextInt(NON_NULL_PRIMITIVE_TYPES.length)];
        }
    }

    /** Generates a legal class-free type tree within the depth budget. */
    private static Type genTypeTree(Random rnd, int depth) {
        if (depth <= 0) {
            return genLeafTypeTree(rnd);
        }
        switch (rnd.nextInt(4)) {
            case 0:
                return genLeafTypeTree(rnd);
            case 1:
                return new Type.Array(genTypeTree(rnd, depth - 1));
            case 2:
                return new Type.Nullable(genNonNullableTypeTree(rnd, depth - 1));
            default:
                return genFunctionTypeTree(rnd, depth - 1);
        }
    }

    /**
     * The full per-member encode verification: encodes the legal type
     * (no exception), applies the explicit legacy-spelling negative pins
     * to the produced text, proves {@code render(parse(encode(T))) ==
     * encode(T)} byte-identically through T3's strict parser (never
     * self-approval), and round-trips {@code parse(render(ast))}.
     * Returns the parsed atom (or null after a counted failure).
     */
    private static DescriptorAst assertEncodeMember(Type type, String label) {
        String text;
        try {
            text = ENCODER.encode(type);
        } catch (Throwable t) {
            fail(label, "encode threw for legal class-free type " + type + ": " + t);
            return null;
        }
        check(text != null && !text.isEmpty(), label + ": encoded text present");
        if (text == null || text.isEmpty()) {
            return null;
        }

        // Explicit negative pins per corpus member: never a legacy spelling.
        check(text.indexOf("[]") < 0,
            label + ": no legacy T[] spelling in " + quote(text));
        check(text.indexOf('|') < 0,
            label + ": no legacy T|null spelling in " + quote(text));
        check(text.indexOf("...") < 0,
            label + ": no rest-parameter spelling in " + quote(text));
        check(text.indexOf('.') < 0,
            label + ": no dotted spelling in " + quote(text));
        check(text.indexOf('@') < 0,
            label + ": no class atom for a class-free type in " + quote(text));
        boolean structural = text.indexOf('[') >= 0
            || text.indexOf('?') >= 0
            || text.indexOf('(') >= 0
            || text.indexOf(',') >= 0
            || text.indexOf('-') >= 0;
        check(structural || PRIMITIVE_KEYWORDS_SET.contains(text),
            label + ": no bare-name spelling in " + quote(text));

        // Pure: a repeated encode is byte-identical.
        check(text.equals(ENCODER.encode(type)),
            label + ": encode is pure and deterministic");

        // Proven against T3's strict parse/render, not self-approval.
        DescriptorParseResult result = parseResult(text);
        if (!(result instanceof DescriptorAst ast)) {
            fail(label, "strict parse rejected encode(T) " + quote(text)
                + ": " + result);
            return null;
        }
        String rendered = CanonicalRuntimeTypeDescriptor.render(ast);
        if (!text.equals(rendered)) {
            fail(label, "render(parse(encode(T))) != encode(T) for "
                + quote(text) + ": rendered " + quote(rendered));
        } else {
            check(true, label + ": render(parse(encode(T))) == encode(T) "
                + "byte-identically");
        }
        DescriptorParseResult again = parseResult(rendered);
        if (!(again instanceof DescriptorAst ast2) || !ast2.equals(ast)) {
            fail(label, "parse(render(ast)) did not round-trip for "
                + quote(text));
        } else {
            check(true, label + ": parse(render(ast)) round-trips");
        }
        return ast;
    }

    static void testEncodeServiceContract() {
        System.out.println("-- encode: per-compilation service contract --");

        CanonicalRuntimeTypeDescriptor service =
            new CanonicalRuntimeTypeDescriptor(CLASS_FREE_INDEX);
        check(service != null,
            "service constructs with the pinned identity-index signature");

        try {
            new CanonicalRuntimeTypeDescriptor(null);
            fail("constructor: null index must be rejected", "no exception was thrown");
        } catch (NullPointerException expected) {
            check(true, "constructor: null index rejected");
        }

        try {
            ENCODER.encode(null);
            fail("encode: null type must be rejected", "no exception was thrown");
        } catch (NullPointerException expected) {
            check(true, "encode: null type rejected");
        }
    }

    static void testEncodePrimitives() {
        System.out.println("-- encode: primitives verbatim --");

        Type[] types = {
            Type.Null.INSTANCE, Type.Boolean.INSTANCE, Type.Int.INSTANCE,
            Type.Number.INSTANCE, Type.String.INSTANCE, Type.Bytes.INSTANCE,
            Type.Table.INSTANCE,
        };
        String[] texts = {
            "null", "boolean", "int", "number", "string", "bytes", "table",
        };
        for (int i = 0; i < types.length; i++) {
            DescriptorAst ast = assertEncodeMember(types[i], "primitive " + texts[i]);
            check(ast instanceof DescriptorAst.PrimitiveAtom p
                    && p.name().equals(texts[i]),
                "primitive " + texts[i] + " encodes to the exact keyword");
        }
        check(CanonicalRuntimeTypeDescriptor.BYTES_DESCRIPTOR.equals(
                ENCODER.encode(Type.Bytes.INSTANCE)),
            "bytes encodes to the pinned BYTES_DESCRIPTOR constant");
    }

    static void testEncodePinnedShapes() {
        System.out.println("-- encode: pinned canonical shapes --");

        check("[int]".equals(ENCODER.encode(new Type.Array(Type.Int.INSTANCE))),
            "Array(int) encodes to [int]");
        check("[?bytes]".equals(ENCODER.encode(
                new Type.Array(new Type.Nullable(Type.Bytes.INSTANCE)))),
            "Array(Nullable(bytes)) encodes to [?bytes]");
        check("?[bytes]".equals(ENCODER.encode(
                new Type.Nullable(new Type.Array(Type.Bytes.INSTANCE)))),
            "Nullable(Array(bytes)) encodes to ?[bytes]");
        check("(int,string)->boolean".equals(ENCODER.encode(
                new Type.Func(List.of(Type.Int.INSTANCE, Type.String.INSTANCE),
                    Type.Boolean.INSTANCE))),
            "sync function encodes with the exact marker-less shape");
        check("()->null".equals(ENCODER.encode(
                new Type.Func(List.of(), Type.Null.INSTANCE))),
            "zero-parameter sync function encodes to ()->null");
        check("async(bytes)->bytes".equals(ENCODER.encode(
                new Type.Func(List.of(Type.Bytes.INSTANCE), Type.Bytes.INSTANCE,
                    true))),
            "async bytes function encodes to async(bytes)->bytes");
        check("async(?int)->[bytes]".equals(ENCODER.encode(
                new Type.Func(List.of(new Type.Nullable(Type.Int.INSTANCE)),
                    new Type.Array(Type.Bytes.INSTANCE), true))),
            "async function params/return encode recursively");

        assertEncodeMember(new Type.Array(Type.Int.INSTANCE), "pinned shape [int]");
        assertEncodeMember(new Type.Nullable(new Type.Array(Type.Bytes.INSTANCE)),
            "pinned shape ?[bytes]");
        assertEncodeMember(new Type.Func(
                List.of(Type.Bytes.INSTANCE, new Type.Nullable(Type.Int.INSTANCE)),
                new Type.Array(Type.Bytes.INSTANCE), true),
            "pinned shape async(bytes,?int)->[bytes]");
    }

    static void testEncodeInvariantViolations() {
        System.out.println("-- encode: internal invariant violations --");

        IllegalStateException sentinelFailure = null;
        try {
            ENCODER.encode(Type.Error.INSTANCE);
        } catch (IllegalStateException expected) {
            sentinelFailure = expected;
        }
        check(sentinelFailure != null
                && sentinelFailure.getMessage() != null
                && !sentinelFailure.getMessage().isEmpty(),
            "Type.Error encode is an explicit internal invariant violation "
            + "(internal error, never fallback text)");

        IllegalStateException classFailure = null;
        try {
            ENCODER.encode(deal.test.IdentityTestFixtures.classType(
                "User", "lib.utils"));
        } catch (IllegalStateException expected) {
            classFailure = expected;
        }
        check(classFailure != null
                && classFailure.getMessage() != null
                && !classFailure.getMessage().isEmpty(),
            "Type.Class encode for an identity-absent class is an explicit "
            + "internal invariant violation (the identity is absent from "
            + "the index, never fallback text)");
    }

    static void testEncodeClassBranch() {
        System.out.println("-- encode: class branch through the identity index --");

        // The registered index of this pin: a project module identity and
        // the builtin Error module identity, projected to the canonical
        // class atoms byte-for-byte.
        CanonicalModuleIdentity projectModule =
            new CanonicalModuleIdentity.ProjectModule(
                new ProjectModuleIdentity("lib", "/lib", List.of("utils")));
        CanonicalClassIdentityIndex registeredIndex =
            new CanonicalClassIdentityIndex() {
                @Override
                public String descriptorTextFor(CanonicalClassIdentity identity) {
                    if (CanonicalModuleIdentity.BuiltinModule.INSTANCE
                            .equals(identity.moduleIdentity())) {
                        check("Error".equals(identity.className()),
                            "builtin index entry is only ever the Error class");
                        return "@$builtin/Error";
                    }
                    if (projectModule.equals(identity.moduleIdentity())) {
                        check("User".equals(identity.className()),
                            "project index entry is only ever the User class");
                        return "@lib/utils/User";
                    }
                    throw new IllegalStateException(
                        "absent from the registered test index: " + identity);
                }

                @Override
                public CanonicalClassIdentity identityForDescriptorText(
                        String descriptorText) {
                    throw new IllegalStateException(
                        "reverse lookup not exercised by this pin: \""
                        + descriptorText + "\"");
                }
            };
        CanonicalRuntimeTypeDescriptor service =
            new CanonicalRuntimeTypeDescriptor(registeredIndex);

        // v1.2 identity carriage: class types carry their canonical
        // identity; encode resolves the index projection from the
        // identity — never from a module path.
        CanonicalClassIdentity projectClass =
            new CanonicalClassIdentity(projectModule, "User");
        CanonicalClassIdentity builtinError =
            new CanonicalClassIdentity(
                CanonicalModuleIdentity.BuiltinModule.INSTANCE, "Error");
        check("@lib/utils/User".equals(
                service.encode(new Type.Class("User", projectClass))),
            "Type.Class encodes to the index-registered project atom "
            + "@lib/utils/User");
        check("@$builtin/Error".equals(
                service.encode(new Type.Class("Error", builtinError))),
            "the builtin Error class encodes to @$builtin/Error");
        check("@lib/utils/User".equals(service.encode(
                Types.classType("User", projectClass))),
            "Types.classType encodes to the same index-registered atom");

        String classText = service.encode(
            new Type.Class("User", projectClass));
        DescriptorParseResult classParse = parseResult(classText);
        check(classParse instanceof DescriptorAst.ClassAtom c
                && "@lib/utils/User".equals(c.fullDescriptorText()),
            "class encode round-trips through parse/render byte-for-byte: "
                + classText + " -> " + classParse);
        check(classText.equals(CanonicalRuntimeTypeDescriptor.render(
                (DescriptorAst) classParse)),
            "render(parse(encode(class))) == encode(class) byte-identically");

        IllegalStateException absentFailure = null;
        try {
            service.encode(new Type.Class("User",
                new CanonicalClassIdentity(
                    new CanonicalModuleIdentity.ProjectModule(
                        new ProjectModuleIdentity("unclassified",
                            "unclassified", List.of())),
                    "User")));
        } catch (IllegalStateException expected) {
            absentFailure = expected;
        }
        check(absentFailure != null
                && absentFailure.getMessage() != null
                && !absentFailure.getMessage().isEmpty(),
            "a class whose identity is absent from the index is the pinned "
            + "absent-identity invariant violation");
    }

    static void testEncodePropertyCorpus() {
        System.out.println("-- encode: class-free property corpus --");

        Random rnd = new Random(0x0311_CAFE_5EEDL);
        for (int i = 0; i < 600; i++) {
            Type tree = genTypeTree(rnd, 12);
            assertEncodeMember(tree, "encode corpus member " + i);
        }
    }

    static void testEncodeDeepNesting() {
        System.out.println("-- encode: deep nesting (depth >= 50) --");

        final int depth = 60;

        // Pure array nesting around bytes.
        Type arrayDeep = Type.Bytes.INSTANCE;
        for (int i = 0; i < depth; i++) {
            arrayDeep = new Type.Array(arrayDeep);
        }
        DescriptorAst arrayAst = assertEncodeMember(arrayDeep,
            "encode: array nesting at depth " + depth);
        check(Types.containsBytes(arrayDeep), "arrayDeep structurally contains bytes");
        check(arrayAst != null && maxDepth(arrayAst) >= depth,
            "encode: array atom tree reaches depth " + depth);

        // Alternating array/nullable nesting around bytes.
        Type alternated = Type.Bytes.INSTANCE;
        for (int i = 0; i < depth; i++) {
            alternated = (i % 2 == 0)
                ? new Type.Array(alternated)
                : new Type.Nullable(alternated);
        }
        DescriptorAst altAst = assertEncodeMember(alternated,
            "encode: array/nullable alternation at depth " + depth);
        check(Types.containsBytes(alternated),
            "alternated type structurally contains bytes");
        check(altAst != null && maxDepth(altAst) >= depth,
            "encode: alternated atom tree reaches depth " + depth);

        // Bytes through sync and async function parameter/return positions,
        // nested to depth >= 50: every level carries bytes in the parameter
        // and in the return position, alternating the exact marker.
        Type funcs = Type.Bytes.INSTANCE;
        for (int i = 0; i < depth; i++) {
            boolean async = (i % 2 == 0);
            funcs = new Type.Func(List.of(funcs), Type.Bytes.INSTANCE, async);
        }
        DescriptorAst funcAst = assertEncodeMember(funcs,
            "encode: sync/async function nesting at depth " + depth);
        check(Types.containsBytes(funcs),
            "function-nested type structurally contains bytes");
        check(funcAst != null && maxDepth(funcAst) >= depth,
            "encode: function atom tree reaches depth " + depth);

        // Mixed array/nullable/function nesting around bytes with a bytes
        // parameter and a bytes return at every function level.
        Type mixed = Type.Bytes.INSTANCE;
        for (int i = 0; i < depth; i++) {
            switch (i % 3) {
                case 0 -> mixed = new Type.Array(mixed);
                case 1 -> mixed = new Type.Nullable(mixed);
                default -> mixed = new Type.Func(
                    List.of(mixed, Type.Bytes.INSTANCE),
                    Type.Bytes.INSTANCE, i % 2 == 0);
            }
        }
        DescriptorAst mixedAst = assertEncodeMember(mixed,
            "encode: mixed nesting at depth " + depth);
        check(Types.containsBytes(mixed), "mixed type structurally contains bytes");
        check(mixedAst != null && maxDepth(mixedAst) >= depth,
            "encode: mixed atom tree reaches depth " + depth);
    }
    // =========================================================================
    // Class-bearing encode (ISSUE-0314): totality through the class branch and
    // the class-bearing round-trip property corpus over a contract-conformant
    // in-repo test index (T2's contract; the real index is E2's)
    // =========================================================================

    /** Pinned example identity: project module {@code lib} + relative
     * component {@code utils}, class {@code User} → {@code @lib/utils/User}. */
    private static final CanonicalClassIdentity PROJECT_USER_IDENTITY =
        new CanonicalClassIdentity(
            new CanonicalModuleIdentity.ProjectModule(
                new ProjectModuleIdentity("lib", "/lib", List.of("utils"))),
            "User");

    /** Pinned example identity: externals module {@code pkg}, class
     * {@code Cls} → {@code @$external/pkg/Cls}. */
    private static final CanonicalClassIdentity EXTERNAL_PKG_CLS_IDENTITY =
        new CanonicalClassIdentity(
            new CanonicalModuleIdentity.ExternalModule("pkg"), "Cls");

    /** Pinned example identity: dotted externals specifier {@code host.cfg},
     * class {@code ServerConfig} → {@code @$external/host.cfg/ServerConfig}. */
    private static final CanonicalClassIdentity EXTERNAL_HOST_CFG_SERVER_CONFIG_IDENTITY =
        new CanonicalClassIdentity(
            new CanonicalModuleIdentity.ExternalModule("host.cfg"),
            "ServerConfig");

    /** Pinned example identity: the intrinsic builtin {@code Error} →
     * {@code @$builtin/Error}. */
    private static final CanonicalClassIdentity BUILTIN_ERROR_IDENTITY =
        new CanonicalClassIdentity(
            CanonicalModuleIdentity.BuiltinModule.INSTANCE, "Error");

    /**
     * Anti-hollow entry: a grammatical-but-arbitrary projection text
     * unrelated to the identity's class name ({@code DeclaredName} vs
     * {@code VaultedName}) and to any module spelling.  Emitting it
     * verbatim proves the class branch never recomputes text from the
     * class name or a path; the round trips below prove parse/render
     * agreement rather than self-approval.
     */
    private static final CanonicalClassIdentity ANTI_HOLLOW_IDENTITY =
        new CanonicalClassIdentity(
            new CanonicalModuleIdentity.ExternalModule("hollow"),
            "DeclaredName");
    private static final String ANTI_HOLLOW_TEXT = "@$external/hollow/VaultedName";

    /** A dot-bearing multi-component project identity whose index text
     * ({@code @root.two/sub.one/Other}) differs from its dotted
     * modulePath spelling ({@code @root.two.sub.one/Other}) — the
     * text-opacity / verbatim-pass-through pin. */
    private static final CanonicalClassIdentity DOTTED_PROJECT_IDENTITY =
        new CanonicalClassIdentity(
            new CanonicalModuleIdentity.ProjectModule(
                new ProjectModuleIdentity("root.two", "/root.two",
                    List.of("sub.one"))),
            "Other");
    private static final String DOTTED_PROJECT_TEXT = "@root.two/sub.one/Other";

    /** The registered (identity → text) half of the class-bearing test
     * index bijection (structurally keyed by record equality). */
    private static final Map<CanonicalClassIdentity, String> CLASS_BEARING_TEXTS =
        Map.of(
            PROJECT_USER_IDENTITY, "@lib/utils/User",
            EXTERNAL_PKG_CLS_IDENTITY, "@$external/pkg/Cls",
            EXTERNAL_HOST_CFG_SERVER_CONFIG_IDENTITY,
                "@$external/host.cfg/ServerConfig",
            BUILTIN_ERROR_IDENTITY, "@$builtin/Error",
            ANTI_HOLLOW_IDENTITY, ANTI_HOLLOW_TEXT,
            DOTTED_PROJECT_IDENTITY, DOTTED_PROJECT_TEXT);

    /** The registered (text → identity) half of the class-bearing test
     * index bijection (keyed byte-for-byte). */
    private static final Map<String, CanonicalClassIdentity> CLASS_BEARING_IDENTITIES =
        Map.of(
            "@lib/utils/User", PROJECT_USER_IDENTITY,
            "@$external/pkg/Cls", EXTERNAL_PKG_CLS_IDENTITY,
            "@$external/host.cfg/ServerConfig",
                EXTERNAL_HOST_CFG_SERVER_CONFIG_IDENTITY,
            "@$builtin/Error", BUILTIN_ERROR_IDENTITY,
            ANTI_HOLLOW_TEXT, ANTI_HOLLOW_IDENTITY,
            DOTTED_PROJECT_TEXT, DOTTED_PROJECT_IDENTITY);

    /** The contract-conformant in-repo test index (T2's contract): forward
     * and reverse lookups over the pinned entries; absent lookups are the
     * pinned invariant violation, never null and never invented text. */
    private static final CanonicalClassIdentityIndex CLASS_BEARING_INDEX =
        new CanonicalClassIdentityIndex() {
            @Override
            public String descriptorTextFor(CanonicalClassIdentity identity) {
                String text = CLASS_BEARING_TEXTS.get(identity);
                if (text == null) {
                    throw new IllegalStateException(
                        "absent from the class-bearing test index: " + identity);
                }
                return text;
            }

            @Override
            public CanonicalClassIdentity identityForDescriptorText(
                    String descriptorText) {
                CanonicalClassIdentity identity =
                    CLASS_BEARING_IDENTITIES.get(descriptorText);
                if (identity == null) {
                    throw new IllegalStateException(
                        "absent from the class-bearing test index: \""
                            + descriptorText + "\"");
                }
                return identity;
            }
        };

    /** The per-compilation service over the class-bearing index: every
     * class-free and class-bearing corpus member runs through this one
     * instance (the one Type→text authority). */
    private static final CanonicalRuntimeTypeDescriptor CLASS_BEARING_ENCODER =
        new CanonicalRuntimeTypeDescriptor(CLASS_BEARING_INDEX);

    /** The class leaf pool: the pinned example identities plus the
     * anti-hollow and dotted-project entries. */
    private static final Type.Class[] CLASS_BEARING_LEAVES = {
        new Type.Class("User", PROJECT_USER_IDENTITY),
        new Type.Class("Cls", EXTERNAL_PKG_CLS_IDENTITY),
        new Type.Class("ServerConfig", EXTERNAL_HOST_CFG_SERVER_CONFIG_IDENTITY),
        new Type.Class("Error", BUILTIN_ERROR_IDENTITY),
        new Type.Class("DeclaredName", ANTI_HOLLOW_IDENTITY),
        new Type.Class("Other", DOTTED_PROJECT_IDENTITY),
    };

    /** Every class name the corpus can carry (plus the anti-hollow
     * projection name): the bare-name scan set. */
    private static final Set<String> CLASS_BEARING_NAMES = Set.of(
        "User", "Cls", "ServerConfig", "Error", "DeclaredName", "Other",
        "VaultedName");

    /** A class-bearing or class-free leaf (class leaves biased 3:1 so the
     * mixed generator covers class atoms in every position). */
    private static Type genClassBearingLeaf(Random rnd) {
        if (rnd.nextInt(4) == 0) {
            return PRIMITIVE_TYPES[rnd.nextInt(PRIMITIVE_TYPES.length)];
        }
        return CLASS_BEARING_LEAVES[rnd.nextInt(CLASS_BEARING_LEAVES.length)];
    }

    /** A non-null, non-nullable leaf for {@code ?D} inner positions. */
    private static Type genClassBearingNonNullableLeaf(Random rnd,
                                                       boolean forceClass) {
        if (forceClass || rnd.nextInt(4) != 0) {
            return CLASS_BEARING_LEAVES[rnd.nextInt(CLASS_BEARING_LEAVES.length)];
        }
        return NON_NULL_PRIMITIVE_TYPES[rnd.nextInt(NON_NULL_PRIMITIVE_TYPES.length)];
    }

    private static Type genClassBearingFunction(Random rnd, int depth,
                                                boolean forceClass) {
        boolean async = rnd.nextBoolean();
        int count = rnd.nextInt(4);
        List<Type> params = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            params.add(genClassBearingTypeTree(rnd, depth - 1, forceClass));
        }
        return new Type.Func(params,
            genClassBearingTypeTree(rnd, depth - 1, forceClass), async);
    }

    private static Type genClassBearingNonNullable(Random rnd, int depth,
                                                   boolean forceClass) {
        if (depth <= 0) {
            return genClassBearingNonNullableLeaf(rnd, forceClass);
        }
        switch (rnd.nextInt(3)) {
            case 0:
                return genClassBearingNonNullableLeaf(rnd, forceClass);
            case 1:
                return new Type.Array(
                    genClassBearingTypeTree(rnd, depth - 1, forceClass));
            default:
                return genClassBearingFunction(rnd, depth - 1, forceClass);
        }
    }

    /** Generates a legal type tree; when {@code forceClass} every leaf is a
     * class leaf (guaranteed class-bearing members), otherwise leaves are a
     * biased class/primitive mix (class-free members remain possible). */
    private static Type genClassBearingTypeTree(Random rnd, int depth,
                                                boolean forceClass) {
        if (depth <= 0) {
            return forceClass
                ? CLASS_BEARING_LEAVES[rnd.nextInt(CLASS_BEARING_LEAVES.length)]
                : genClassBearingLeaf(rnd);
        }
        switch (rnd.nextInt(4)) {
            case 0:
                return forceClass
                    ? CLASS_BEARING_LEAVES[rnd.nextInt(CLASS_BEARING_LEAVES.length)]
                    : genClassBearingLeaf(rnd);
            case 1:
                return new Type.Array(
                    genClassBearingTypeTree(rnd, depth - 1, forceClass));
            case 2:
                return new Type.Nullable(
                    genClassBearingNonNullable(rnd, depth - 1, forceClass));
            default:
                return genClassBearingFunction(rnd, depth - 1, forceClass);
        }
    }

    /** Whether a type tree carries at least one {@link Type.Class} leaf. */
    private static boolean containsClass(Type type) {
        return switch (type) {
            case Type.Class ignored -> true;
            case Type.Array a -> containsClass(a.element());
            case Type.Nullable n -> containsClass(n.inner());
            case Type.Func f -> {
                for (Type param : f.paramTypes()) {
                    if (containsClass(param)) {
                        yield true;
                    }
                }
                yield containsClass(f.returnType());
            }
            default -> false;
        };
    }

    /** The class-leaf text substitution of one spelling builder. */
    @FunctionalInterface
    private interface ClassTextMapper {
        String classText(Type.Class c);
    }

    /** Rebuilds canonical descriptor text independently, with class leaves
     * substituted through the given spelling mapper. */
    private static String buildDescriptorText(Type type, ClassTextMapper classText) {
        if (type instanceof Type.Class c) {
            return classText.classText(c);
        }
        if (type instanceof Type.Array a) {
            return "[" + buildDescriptorText(a.element(), classText) + "]";
        }
        if (type instanceof Type.Nullable n) {
            return "?" + buildDescriptorText(n.inner(), classText);
        }
        if (type instanceof Type.Func f) {
            StringBuilder sb = new StringBuilder();
            if (f.isAsync()) {
                sb.append("async");
            }
            sb.append('(');
            for (int i = 0; i < f.paramTypes().size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(buildDescriptorText(f.paramTypes().get(i), classText));
            }
            sb.append(")->").append(buildDescriptorText(f.returnType(), classText));
            return sb.toString();
        }
        return primitiveKeyword(type);
    }

    /** The canonical keyword of a primitive type (encode's primitive arm). */
    private static String primitiveKeyword(Type type) {
        if (type == Type.Null.INSTANCE) {
            return "null";
        }
        if (type == Type.Boolean.INSTANCE) {
            return "boolean";
        }
        if (type == Type.Int.INSTANCE) {
            return "int";
        }
        if (type == Type.Number.INSTANCE) {
            return "number";
        }
        if (type == Type.String.INSTANCE) {
            return "string";
        }
        if (type == Type.Bytes.INSTANCE) {
            return CanonicalRuntimeTypeDescriptor.BYTES_DESCRIPTOR;
        }
        if (type == Type.Table.INSTANCE) {
            return "table";
        }
        throw new IllegalArgumentException("not a primitive: " + type);
    }

    /** The v1.1 dotted modulePath spelling of a class leaf:
     * {@code @<rootText[.relative...]|specifier>/<Name>}; the bare name
     * for the builtin. */
    private static String dottedModuleLeaf(Type.Class c) {
        CanonicalClassIdentity id = c.identity();
        if (id.moduleIdentity() instanceof CanonicalModuleIdentity.ProjectModule pm) {
            StringBuilder dotted =
                new StringBuilder(pm.projectIdentity().configuredRootText());
            for (String component : pm.projectIdentity().relativeModuleComponents()) {
                dotted.append('.').append(component);
            }
            return "@" + dotted + "/" + id.className();
        }
        if (id.moduleIdentity() instanceof CanonicalModuleIdentity.ExternalModule em) {
            return "@" + em.rawImportSpecifier() + "/" + id.className();
        }
        return id.className();
    }

    /** The dotted class-name-position spelling of a class leaf:
     * {@code @<dotted module run>.<Name>}; the bare name for the builtin. */
    private static String dottedClassNameLeaf(Type.Class c) {
        CanonicalClassIdentity id = c.identity();
        if (id.moduleIdentity() instanceof CanonicalModuleIdentity.ProjectModule pm) {
            StringBuilder dotted =
                new StringBuilder(pm.projectIdentity().configuredRootText());
            for (String component : pm.projectIdentity().relativeModuleComponents()) {
                dotted.append('.').append(component);
            }
            return "@" + dotted + "." + id.className();
        }
        if (id.moduleIdentity() instanceof CanonicalModuleIdentity.ExternalModule em) {
            return "@" + em.rawImportSpecifier() + "." + id.className();
        }
        return id.className();
    }

    /** Whether the text contains any corpus class name as an identifier run. */
    private static boolean containsAnyClassName(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isIdentifierChar(c)) {
                int start = i;
                while (i < text.length() && isIdentifierChar(text.charAt(i))) {
                    i++;
                }
                if (CLASS_BEARING_NAMES.contains(text.substring(start, i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isIdentifierChar(char c) {
        return (c >= 'A' && c <= 'Z')
            || (c >= 'a' && c <= 'z')
            || (c >= '0' && c <= '9')
            || c == '_';
    }

    /** The carried class identities of every class leaf, in tree order. */
    private static List<CanonicalClassIdentity> classLeafIdentities(Type type) {
        List<CanonicalClassIdentity> out = new ArrayList<>();
        if (type instanceof Type.Class c) {
            out.add(c.identity());
        } else if (type instanceof Type.Array a) {
            out.addAll(classLeafIdentities(a.element()));
        } else if (type instanceof Type.Nullable n) {
            out.addAll(classLeafIdentities(n.inner()));
        } else if (type instanceof Type.Func f) {
            for (Type param : f.paramTypes()) {
                out.addAll(classLeafIdentities(param));
            }
            out.addAll(classLeafIdentities(f.returnType()));
        }
        return out;
    }

    /** The full texts of every class atom in a parsed atom tree. */
    private static List<String> classAtomTexts(DescriptorAst ast) {
        List<String> out = new ArrayList<>();
        if (ast instanceof DescriptorAst.ClassAtom c) {
            out.add(c.fullDescriptorText());
        } else if (ast instanceof DescriptorAst.ArrayAtom a) {
            out.addAll(classAtomTexts(a.element()));
        } else if (ast instanceof DescriptorAst.NullableAtom n) {
            out.addAll(classAtomTexts(n.inner()));
        } else if (ast instanceof DescriptorAst.FunctionAtom f) {
            for (DescriptorAst param : f.params()) {
                out.addAll(classAtomTexts(param));
            }
            out.addAll(classAtomTexts(f.returnType()));
        }
        return out;
    }

    /**
     * The full per-member class-bearing verification: encode totality,
     * byte-identity with the index-driven reference, legacy/bare/dotted
     * negative pins, purity, the strict-parse round trip (T3), the
     * verbatim class-atom pass-through, and the T2 reverse lookup.
     * Returns the parsed atom (or null after a counted failure).
     */
    private static DescriptorAst assertClassBearingEncodeMember(Type type,
                                                                String label) {
        boolean hasClass = containsClass(type);
        String text;
        try {
            text = CLASS_BEARING_ENCODER.encode(type);
        } catch (Throwable t) {
            fail(label, "encode threw for legal type " + type + ": " + t);
            return null;
        }
        check(text != null && !text.isEmpty(), label + ": encoded text present");
        if (text == null || text.isEmpty()) {
            return null;
        }

        // Legacy-spelling negative pins per corpus member.
        check(text.indexOf("[]") < 0,
            label + ": no legacy T[] spelling in " + quote(text));
        check(text.indexOf('|') < 0,
            label + ": no legacy T|null spelling in " + quote(text));
        check(text.indexOf("...") < 0,
            label + ": no rest-parameter spelling in " + quote(text));

        // Byte-identity with the index: class leaves are the index text
        // verbatim (multi-component roots stay text-opaque; never
        // recomputed from the class name or a path).
        String reference = buildDescriptorText(
            type, c -> CLASS_BEARING_INDEX.descriptorTextFor(c.identity()));
        if (!text.equals(reference)) {
            fail(label, "encode(T) is not byte-identical to the index-driven "
                + "reference: " + quote(text) + " vs " + quote(reference));
        } else {
            check(true, label + ": byte-identical to the index-driven reference");
        }

        if (hasClass) {
            check(text.indexOf('@') >= 0, label + ": class atom present");
            // Never the v1.1 dotted modulePath spelling (@<dotted>/<Name>
            // or the bare builtin name).
            String dotted = buildDescriptorText(
                type, CanonicalRuntimeTypeDescriptorTest::dottedModuleLeaf);
            check(!text.equals(dotted),
                label + ": never the dotted modulePath spelling " + quote(dotted));
            // Never the dotted class-name-position spelling.
            String dottedClassName = buildDescriptorText(
                type, CanonicalRuntimeTypeDescriptorTest::dottedClassNameLeaf);
            check(!text.equals(dottedClassName),
                label + ": never the dotted class-name spelling "
                    + quote(dottedClassName));
        } else {
            check(text.indexOf('@') < 0,
                label + ": no class atom for a class-free member in " + quote(text));
        }

        // Never a bare class name: after removing every index-registered
        // atom text from the emission, no corpus class name may remain.
        String remainder = text;
        for (String atom : CLASS_BEARING_TEXTS.values()) {
            remainder = remainder.replace(atom, "");
        }
        check(!containsAnyClassName(remainder),
            label + ": no bare class-name text remains in " + quote(text));

        // Pure: a repeated encode is byte-identical.
        check(text.equals(CLASS_BEARING_ENCODER.encode(type)),
            label + ": encode is pure and deterministic");

        // The combined check through T3's strict parser: round trips prove
        // parse/render agreement, never self-approval.
        DescriptorParseResult result = parseResult(text);
        if (!(result instanceof DescriptorAst ast)) {
            fail(label, "strict parse rejected encode(T) " + quote(text)
                + ": " + result);
            return null;
        }
        String rendered = CanonicalRuntimeTypeDescriptor.render(ast);
        if (!text.equals(rendered)) {
            fail(label, "render(parse(encode(T))) != encode(T) for "
                + quote(text) + ": rendered " + quote(rendered));
        } else {
            check(true, label + ": render(parse(encode(T))) == encode(T) "
                + "byte-identically");
        }
        DescriptorParseResult again = parseResult(rendered);
        if (!(again instanceof DescriptorAst ast2) || !ast2.equals(ast)) {
            fail(label, "parse(render(ast)) did not round-trip for " + quote(text));
        } else {
            check(true, label + ": parse(render(ast)) round-trips");
        }

        // Verbatim class-atom pass-through: the emitted atoms are exactly
        // the tree's index texts, and T2's reverse lookup maps each back
        // to the tree's carried identity.
        List<String> atomTexts = classAtomTexts(ast);
        List<String> expectedTexts = new ArrayList<>();
        Map<String, CanonicalClassIdentity> textToIdentity = new HashMap<>();
        for (CanonicalClassIdentity identity : classLeafIdentities(type)) {
            String atomText = CLASS_BEARING_INDEX.descriptorTextFor(identity);
            expectedTexts.add(atomText);
            textToIdentity.put(atomText, identity);
        }
        atomTexts.sort(String::compareTo);
        expectedTexts.sort(String::compareTo);
        if (!atomTexts.equals(expectedTexts)) {
            fail(label, "emitted class atoms differ from the tree's index texts: "
                + atomTexts + " vs " + expectedTexts);
        } else {
            check(true, label + ": every class atom is exactly an index-registered text");
        }
        for (String atomText : atomTexts) {
            CanonicalClassIdentity identity;
            try {
                identity = CLASS_BEARING_INDEX.identityForDescriptorText(atomText);
            } catch (Throwable t) {
                fail(label, "T2 reverse lookup threw for " + quote(atomText)
                    + ": " + t);
                continue;
            }
            CanonicalClassIdentity expected = textToIdentity.get(atomText);
            check(expected != null && expected.equals(identity),
                label + ": reverse lookup of " + quote(atomText)
                    + " returns the tree's carried identity");
        }
        return ast;
    }

    // =========================================================================
    // Class-bearing index contract (T2 bijection)
    // =========================================================================

    static void testEncodeClassBearingIndexContract() {
        System.out.println("-- encode: class-bearing index contract (T2 bijection) --");

        // Structural keying: equal (moduleIdentity, className) pairs key
        // the same text regardless of object identity.
        check(ANTI_HOLLOW_TEXT.equals(CLASS_BEARING_INDEX.descriptorTextFor(
                new CanonicalClassIdentity(
                    new CanonicalModuleIdentity.ExternalModule("hollow"),
                    "DeclaredName"))),
            "structurally equal identity values key the same index text");

        // The forward/reverse bijection over every registered entry.
        String[] texts = {
            "@lib/utils/User",
            "@$external/pkg/Cls",
            "@$external/host.cfg/ServerConfig",
            "@$builtin/Error",
            ANTI_HOLLOW_TEXT,
            DOTTED_PROJECT_TEXT,
        };
        CanonicalClassIdentity[] identities = {
            PROJECT_USER_IDENTITY,
            EXTERNAL_PKG_CLS_IDENTITY,
            EXTERNAL_HOST_CFG_SERVER_CONFIG_IDENTITY,
            BUILTIN_ERROR_IDENTITY,
            ANTI_HOLLOW_IDENTITY,
            DOTTED_PROJECT_IDENTITY,
        };
        for (int i = 0; i < texts.length; i++) {
            check(texts[i].equals(
                    CLASS_BEARING_INDEX.descriptorTextFor(identities[i])),
                "forward lookup " + identities[i] + " -> " + texts[i]);
            check(identities[i].equals(
                    CLASS_BEARING_INDEX.identityForDescriptorText(texts[i])),
                "reverse lookup " + texts[i] + " -> " + identities[i]);
        }

        // Absent lookups are the pinned invariant violation, never null.
        CanonicalClassIdentity absentIdentity = new CanonicalClassIdentity(
            new CanonicalModuleIdentity.ExternalModule("unregistered"), "User");
        IllegalStateException forwardFailure = null;
        try {
            CLASS_BEARING_INDEX.descriptorTextFor(absentIdentity);
        } catch (IllegalStateException expected) {
            forwardFailure = expected;
        }
        check(forwardFailure != null && forwardFailure.getMessage() != null
                && !forwardFailure.getMessage().isEmpty(),
            "absent forward lookup is the pinned invariant violation");

        IllegalStateException reverseFailure = null;
        try {
            CLASS_BEARING_INDEX.identityForDescriptorText("@unregistered/User");
        } catch (IllegalStateException expected) {
            reverseFailure = expected;
        }
        check(reverseFailure != null && reverseFailure.getMessage() != null
                && !reverseFailure.getMessage().isEmpty(),
            "absent reverse lookup is the pinned invariant violation");
    }

    // =========================================================================
    // Class-bearing pinned example identities and negative spellings
    // =========================================================================

    static void testEncodeClassBearingPins() {
        System.out.println("-- encode: class-bearing pinned example identities --");

        check("@lib/utils/User".equals(CLASS_BEARING_ENCODER.encode(
                new Type.Class("User", PROJECT_USER_IDENTITY))),
            "project User encodes to the pinned @lib/utils/User");
        check("@$external/pkg/Cls".equals(CLASS_BEARING_ENCODER.encode(
                new Type.Class("Cls", EXTERNAL_PKG_CLS_IDENTITY))),
            "externals Cls encodes to the pinned @$external/pkg/Cls");
        check("@$external/host.cfg/ServerConfig".equals(CLASS_BEARING_ENCODER.encode(
                new Type.Class("ServerConfig",
                    EXTERNAL_HOST_CFG_SERVER_CONFIG_IDENTITY))),
            "dotted externals specifier encodes to @$external/host.cfg/ServerConfig");
        check("@$builtin/Error".equals(CLASS_BEARING_ENCODER.encode(
                new Type.Class("Error", BUILTIN_ERROR_IDENTITY))),
            "builtin Error encodes to @$builtin/Error");
        check("@lib/utils/User".equals(CLASS_BEARING_ENCODER.encode(
                Types.classType("User", PROJECT_USER_IDENTITY))),
            "Types.classType carries the identity into the class branch");

        // Multi-component roots stay text-opaque: three components, the
        // index text passed through verbatim, never the dotted spellings.
        String projectText = CLASS_BEARING_ENCODER.encode(
            new Type.Class("User", PROJECT_USER_IDENTITY));
        check(projectText.indexOf('/') == 4 && projectText.lastIndexOf('/') == 10,
            "@lib/utils/User keeps its two separators (three opaque components)");
        check(!"@lib.utils/User".equals(projectText),
            "the dotted modulePath spelling @lib.utils/User is never emitted");
        check(!"@lib.utils.User".equals(projectText),
            "the dotted class-name spelling @lib.utils.User is never emitted");
        DescriptorAst projectAtom = atomOf(projectText);
        check(projectAtom instanceof DescriptorAst.ClassAtom c
                && "@lib/utils/User".equals(c.fullDescriptorText()),
            "the project atom round-trips byte-for-byte with its full text");

        // The v1.1 dotted emission shape @host.cfg/ServerConfig is never
        // the emission for the dotted-externals identity.
        String hostText = CLASS_BEARING_ENCODER.encode(
            new Type.Class("ServerConfig",
                EXTERNAL_HOST_CFG_SERVER_CONFIG_IDENTITY));
        check(!"@host.cfg/ServerConfig".equals(hostText),
            "the v1.1 dotted emission shape @host.cfg/ServerConfig is never emitted");
        check(!"@host.cfg.ServerConfig".equals(hostText),
            "the dotted class-name shape @host.cfg.ServerConfig is never emitted");

        // The builtin never emits the bare v1.1 Error spelling.
        String builtinText = CLASS_BEARING_ENCODER.encode(
            new Type.Class("Error", BUILTIN_ERROR_IDENTITY));
        check(!"Error".equals(builtinText),
            "the bare v1.1 Error spelling is never emitted");
        check(!"@$builtin.Error".equals(builtinText),
            "the dotted class-name shape @$builtin.Error is never emitted");

        // Anti-hollow: the arbitrary-but-grammatical projection is emitted
        // verbatim — no recomputation from the class name or the module
        // spelling.
        String hollowText = CLASS_BEARING_ENCODER.encode(
            new Type.Class("DeclaredName", ANTI_HOLLOW_IDENTITY));
        check(ANTI_HOLLOW_TEXT.equals(hollowText),
            "the anti-hollow entry is emitted byte-for-byte: " + quote(hollowText));
        check(hollowText.indexOf("DeclaredName") < 0,
            "no recomputation from the carried class name");
        check(hollowText.indexOf("@hollow/") < 0,
            "no recomputation from a module-path spelling");
        assertAtom(ANTI_HOLLOW_TEXT,
            "the anti-hollow text is grammatical and round-trips");
        check(ANTI_HOLLOW_IDENTITY.equals(
                CLASS_BEARING_INDEX.identityForDescriptorText(ANTI_HOLLOW_TEXT)),
            "the anti-hollow text reverse-looks-up to its identity");

        // The dot-bearing multi-component project text stays opaque and
        // verbatim; the fully dotted spellings are never the emission.
        String dottedText = CLASS_BEARING_ENCODER.encode(
            new Type.Class("Other", DOTTED_PROJECT_IDENTITY));
        check(DOTTED_PROJECT_TEXT.equals(dottedText),
            "the multi-component project text @root.two/sub.one/Other is emitted verbatim");
        check(!"@root.two.sub.one/Other".equals(dottedText),
            "the dotted modulePath spelling @root.two.sub.one/Other is never emitted");
        check(!"@root.two.sub.one.Other".equals(dottedText),
            "the dotted class-name spelling @root.two.sub.one.Other is never emitted");
    }

    // =========================================================================
    // Index-miss invariant violations (no fallback text at any depth)
    // =========================================================================

    static void testEncodeClassBearingIndexMiss() {
        System.out.println("-- encode: index-miss invariant violations (no fallback text) --");

        // Same class name, unregistered module.
        Type.Class unregisteredModule = new Type.Class("User",
            new CanonicalClassIdentity(
                new CanonicalModuleIdentity.ExternalModule("other"), "User"));
        try {
            CLASS_BEARING_ENCODER.encode(unregisteredModule);
            fail("index-miss: unregistered module must fail",
                "no exception was thrown");
        } catch (IllegalStateException expected) {
            check(expected.getMessage() != null
                    && expected.getMessage().contains("User"),
                "index-miss: unregistered module is the pinned invariant violation");
        }

        // Registered module, unregistered class name.
        Type.Class unregisteredClass = new Type.Class("Other",
            new CanonicalClassIdentity(
                new CanonicalModuleIdentity.ExternalModule("pkg"), "Other"));
        try {
            CLASS_BEARING_ENCODER.encode(unregisteredClass);
            fail("index-miss: unregistered class must fail",
                "no exception was thrown");
        } catch (IllegalStateException expected) {
            check(expected.getMessage() != null
                    && expected.getMessage().contains("Other"),
                "index-miss: unregistered class is the pinned invariant violation");
        }

        // The same miss nested inside arrays/nullables/functions: the
        // failure propagates and no partial text is produced at any
        // nesting depth.
        Type[] nested = {
            new Type.Array(unregisteredModule),
            new Type.Nullable(unregisteredModule),
            new Type.Func(List.of(unregisteredModule), Type.Int.INSTANCE),
            new Type.Func(List.of(), unregisteredModule, true),
            new Type.Array(new Type.Nullable(new Type.Func(
                List.of(unregisteredModule),
                new Type.Array(unregisteredModule)))),
        };
        for (int i = 0; i < nested.length; i++) {
            final int index = i;
            try {
                CLASS_BEARING_ENCODER.encode(nested[index]);
                fail("index-miss nested case " + index + " must fail",
                    "no exception was thrown");
            } catch (IllegalStateException expected) {
                check(true, "index-miss nested case " + index
                    + " propagates as the invariant violation");
            }
        }
    }

    // =========================================================================
    // The class-bearing property corpus (the combined check)
    // =========================================================================

    static void testEncodeClassBearingCorpus() {
        System.out.println("-- encode: class-bearing property corpus --");

        Random rnd = new Random(0x0314_CAFE_5EEDL);
        int classMembers = 0;
        int classFreeMembers = 0;
        for (int i = 0; i < 400; i++) {
            Type tree;
            if (i % 4 == 0) {
                // Forced class-bearing members: every leaf is a class.
                tree = genClassBearingTypeTree(rnd, 12, true);
            } else if (i % 4 == 1) {
                // Class-free members through the same encoder (T4).
                tree = genTypeTree(rnd, 12);
            } else {
                // Mixed members: a biased class/primitive mix.
                tree = genClassBearingTypeTree(rnd, 12, false);
            }
            if (containsClass(tree)) {
                classMembers++;
            } else {
                classFreeMembers++;
            }
            assertClassBearingEncodeMember(tree, "class-bearing corpus member " + i);
        }
        check(classMembers >= 100,
            "at least a quarter of the corpus members are class-bearing ("
                + classMembers + ")");
        check(classFreeMembers >= 100,
            "the corpus also exercises the class-free encode path ("
                + classFreeMembers + ")");
    }

    // =========================================================================
    // Class-bearing deep nesting (depth >= 50)
    // =========================================================================

    static void testEncodeClassBearingDeepNesting() {
        System.out.println("-- encode: class-bearing deep nesting (depth >= 50) --");

        final int depth = 60;
        Type user = new Type.Class("User", PROJECT_USER_IDENTITY);

        // Pure array nesting around a class leaf.
        Type arrayDeep = user;
        for (int i = 0; i < depth; i++) {
            arrayDeep = new Type.Array(arrayDeep);
        }
        DescriptorAst arrayAst = assertClassBearingEncodeMember(arrayDeep,
            "encode: class array nesting at depth " + depth);
        check(arrayAst != null && maxDepth(arrayAst) >= depth,
            "encode: class array atom tree reaches depth " + depth);

        // Alternating array/nullable nesting around a class leaf.
        Type alternated = user;
        for (int i = 0; i < depth; i++) {
            alternated = (i % 2 == 0)
                ? new Type.Array(alternated)
                : new Type.Nullable(alternated);
        }
        DescriptorAst altAst = assertClassBearingEncodeMember(alternated,
            "encode: class array/nullable alternation at depth " + depth);
        check(altAst != null && maxDepth(altAst) >= depth,
            "encode: class alternated atom tree reaches depth " + depth);

        // Exact sync/async function nesting with the class leaf in the
        // parameter and in the return position at every level.
        Type funcs = user;
        for (int i = 0; i < depth; i++) {
            funcs = new Type.Func(List.of(funcs), user, i % 2 == 0);
        }
        DescriptorAst funcAst = assertClassBearingEncodeMember(funcs,
            "encode: class sync/async function nesting at depth " + depth);
        check(funcAst != null && maxDepth(funcAst) >= depth,
            "encode: class function atom tree reaches depth " + depth);

        // Mixed array/nullable/function nesting with a class parameter and
        // a class return at every function level.
        Type mixed = user;
        for (int i = 0; i < depth; i++) {
            switch (i % 3) {
                case 0 -> mixed = new Type.Array(mixed);
                case 1 -> mixed = new Type.Nullable(mixed);
                default -> mixed = new Type.Func(
                    List.of(mixed, Type.Bytes.INSTANCE), user, i % 2 == 0);
            }
        }
        DescriptorAst mixedAst = assertClassBearingEncodeMember(mixed,
            "encode: class mixed nesting at depth " + depth);
        check(mixedAst != null && maxDepth(mixedAst) >= depth,
            "encode: class mixed atom tree reaches depth " + depth);
    }

}