package deal.test;

import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.descriptors.DescriptorAst;
import deal.descriptors.DescriptorParseResult;
import deal.descriptors.DescriptorSyntaxError;
import deal.types.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tests for the strict canonical runtime type descriptor grammar
 * (ISSUE-0310): {@link CanonicalRuntimeTypeDescriptor#parse(String)} and
 * {@link CanonicalRuntimeTypeDescriptor#render(DescriptorAst)} with the
 * immutable {@link DescriptorAst} atoms and scalar-offset
 * {@link DescriptorSyntaxError} failures.
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
 * </ul>
 */
public class CanonicalRuntimeTypeDescriptorTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== Running Canonical Runtime Type Descriptor Tests (ISSUE-0310) ===");

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
}
