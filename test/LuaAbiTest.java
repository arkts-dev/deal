package deal.test;

import deal.codegen.lua.LuaAbi;
import deal.codegen.lua.LuaAbi.HelperKind;
import deal.codegen.lua.LuaAbi.KeyForm;

import org.junit.Test;

import java.util.Set;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JUnit4 + Hamcrest unit tests for the {@link LuaAbi} emission layer.
 *
 * <p>Pins every public function: the reserved set, the parameterized
 * safe-identifier/key-form decisions (including target-independence for
 * caller-supplied keyword sets), helper-key derivation, the Lua text
 * composers (dot and bracket forms), and the Lua 5.1 key escaping
 * rules.</p>
 */
public class LuaAbiTest {

    // =========================================================================
    // RESERVED set
    // =========================================================================

    @Test
    public void reservedSetHasAllTwentyFourLuaJitWords() {
        assertThat("24-word union set", LuaAbi.RESERVED.size(), is(24));
        for (String word : new String[] {
            "and", "break", "do", "else", "elseif", "end", "false", "for",
            "function", "goto", "if", "in", "local", "nil", "not", "or",
            "repeat", "return", "then", "true", "until", "while",
            "const", "continue"}) {
            assertTrue("reserved word: " + word, LuaAbi.RESERVED.contains(word));
        }
        assertThat("namespace constant", LuaAbi.NAMESPACE, is("__deal"));
    }

    // =========================================================================
    // isReserved
    // =========================================================================

    @Test
    public void isReservedUsesTheCallerSuppliedSet() {
        assertTrue(LuaAbi.isReserved(LuaAbi.RESERVED, "end"));
        assertTrue(LuaAbi.isReserved(LuaAbi.RESERVED, "goto"));
        assertFalse(LuaAbi.isReserved(LuaAbi.RESERVED, "class"));
        assertFalse(LuaAbi.isReserved(LuaAbi.RESERVED, "identifier"));
        // Null name is not reserved (total function).
        assertFalse(LuaAbi.isReserved(LuaAbi.RESERVED, null));
        // A caller-supplied set fully determines the decision.
        assertTrue(LuaAbi.isReserved(Set.of("class"), "class"));
        assertFalse(LuaAbi.isReserved(Set.of("class"), "local"));
        assertFalse(LuaAbi.isReserved(Set.of(), "end"));
    }

    // =========================================================================
    // isSafeIdentifier
    // =========================================================================

    @Test
    public void isSafeIdentifierAcceptsPlainIdentifiers() {
        assertTrue(LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, "user"));
        assertTrue(LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, "User"));
        assertTrue(LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, "_leading"));
        assertTrue(LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, "x_1"));
        assertTrue(LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, "A1b2C3"));
        assertTrue(LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, "_"));
        assertTrue(LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, "__deal"));
        assertTrue(LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, "class"));
        assertTrue(LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, "a1_2b"));
    }

    @Test
    public void isSafeIdentifierRejectsEveryReservedWord() {
        for (String word : LuaAbi.RESERVED) {
            assertFalse("reserved word must be unsafe: " + word,
                LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, word));
        }
    }

    @Test
    public void isSafeIdentifierRejectsBadShapes() {
        assertFalse(LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, null));
        assertFalse(LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, ""));
        // Dollar-containing names (Lua identifiers cannot contain '$').
        assertFalse(LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, "bad$field"));
        assertFalse(LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, "User$fromJson"));
        assertFalse(LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, "$name"));
        // Non-ASCII.
        assertFalse(LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, "na\u00efve"));
        assertFalse(LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, "caf\u00e9"));
        // Leading digit.
        assertFalse(LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, "1abc"));
        // Non-alphanumeric in the middle.
        assertFalse(LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, "a-b"));
        assertFalse(LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, "a.b"));
        assertFalse(LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, "a b"));
    }

    // =========================================================================
    // fieldKeyForm
    // =========================================================================

    @Test
    public void fieldKeyFormDotForSafeNames() {
        assertThat(LuaAbi.fieldKeyForm(LuaAbi.RESERVED, "name"), is(KeyForm.DOT));
        assertThat(LuaAbi.fieldKeyForm(LuaAbi.RESERVED, "_x"), is(KeyForm.DOT));
        assertThat(LuaAbi.fieldKeyForm(LuaAbi.RESERVED, "C1"), is(KeyForm.DOT));
    }

    @Test
    public void fieldKeyFormBracketForUnsafeNames() {
        assertThat(LuaAbi.fieldKeyForm(LuaAbi.RESERVED, "end"), is(KeyForm.BRACKET));
        assertThat(LuaAbi.fieldKeyForm(LuaAbi.RESERVED, "local"), is(KeyForm.BRACKET));
        assertThat(LuaAbi.fieldKeyForm(LuaAbi.RESERVED, "bad$field"), is(KeyForm.BRACKET));
        assertThat(LuaAbi.fieldKeyForm(LuaAbi.RESERVED, "User$fromJson"), is(KeyForm.BRACKET));
        assertThat(LuaAbi.fieldKeyForm(LuaAbi.RESERVED, ""), is(KeyForm.BRACKET));
        assertThat(LuaAbi.fieldKeyForm(LuaAbi.RESERVED, null), is(KeyForm.BRACKET));
    }

    @Test
    public void fieldKeyFormIsTargetIndependent() {
        // The Lua set does not contain Java keywords — and vice versa.
        assertFalse(LuaAbi.isReserved(LuaAbi.RESERVED, "class"));
        assertTrue(LuaAbi.isReserved(Set.of("class"), "class"));
        assertThat(LuaAbi.fieldKeyForm(LuaAbi.RESERVED, "class"), is(KeyForm.DOT));
        assertThat(LuaAbi.fieldKeyForm(Set.of("class"), "class"), is(KeyForm.BRACKET));
        // A JVM-style keyword set decides on its own: "local" is a legal
        // Java identifier, "new" is not.
        Set<String> javaKeywords = Set.of("class", "new", "static");
        assertThat(LuaAbi.fieldKeyForm(javaKeywords, "local"), is(KeyForm.DOT));
        assertThat(LuaAbi.fieldKeyForm(javaKeywords, "new"), is(KeyForm.BRACKET));
        assertThat(LuaAbi.fieldKeyForm(javaKeywords, "static"), is(KeyForm.BRACKET));
    }

    // =========================================================================
    // helperKey
    // =========================================================================

    @Test
    public void helperKeyDerivesAllFiveKinds() {
        assertThat(LuaAbi.helperKey("User", HelperKind.DEFAULTS), is("User_defaults"));
        assertThat(LuaAbi.helperKey("User", HelperKind.META), is("User_meta"));
        assertThat(LuaAbi.helperKey("User", HelperKind.FIELDS), is("User_fields"));
        assertThat(LuaAbi.helperKey("User", HelperKind.FROM_JSON), is("User$fromJson"));
        assertThat(LuaAbi.helperKey("User", HelperKind.TO_JSON), is("User$toJson"));
        // Kinds with every enum constant.
        for (HelperKind kind : HelperKind.values()) {
            assertThat("raw DEAL name preserved per kind",
                LuaAbi.helperKey("C", kind),
                containsString(kind == HelperKind.DEFAULTS ? "_defaults"
                    : kind == HelperKind.META ? "_meta"
                    : kind == HelperKind.FIELDS ? "_fields"
                    : kind == HelperKind.FROM_JSON ? "$fromJson" : "$toJson"));
        }
    }

    // =========================================================================
    // Namespace references
    // =========================================================================

    @Test
    public void generatedRefUsesTheNamespaceTable() {
        assertThat(LuaAbi.generatedRef("User$fromJson"),
            is("__deal[\"User$fromJson\"]"));
        assertThat(LuaAbi.generatedRef("Error_defaults"),
            is("__deal[\"Error_defaults\"]"));
    }

    @Test
    public void helperRefAndNamespaceAssignmentUseNamespaceForms() {
        assertThat(LuaAbi.helperRef("User", HelperKind.FROM_JSON),
            is("__deal[\"User$fromJson\"]"));
        assertThat(LuaAbi.helperRef("User", HelperKind.DEFAULTS),
            is("__deal[\"User_defaults\"]"));
        assertThat(LuaAbi.namespaceAssignment("User_fields", "{ }"),
            is("__deal[\"User_fields\"] = { }"));
        assertThat(LuaAbi.namespaceAssignment("Error_defaults",
                "{ code = \"\", message = \"\" }"),
            is("__deal[\"Error_defaults\"] = { code = \"\", message = \"\" }"));
    }

    // =========================================================================
    // Lua text composers
    // =========================================================================

    @Test
    public void memberAccessDotAndBracketForms() {
        assertThat(LuaAbi.memberAccess("u", "name"), is("u.name"));
        assertThat(LuaAbi.memberAccess("u", "end"), is("u[\"end\"]"));
        assertThat(LuaAbi.memberAccess("u", "local"), is("u[\"local\"]"));
        assertThat(LuaAbi.memberAccess("Lib", "User$fromJson"),
            is("Lib[\"User$fromJson\"]"));
        assertThat(LuaAbi.memberAccess("obj", "_f"), is("obj._f"));
    }

    @Test
    public void hasCheckDotAndBracketForms() {
        assertThat(LuaAbi.hasCheck("u", "nick"), is("u.nick ~= nil"));
        assertThat(LuaAbi.hasCheck("c", "end"), is("c[\"end\"] ~= nil"));
    }

    @Test
    public void tableFieldDotAndBracketForms() {
        assertThat(LuaAbi.tableField("name", "\"Ada\""), is("name = \"Ada\""));
        assertThat(LuaAbi.tableField("end", "4"), is("[\"end\"] = 4"));
        assertThat(LuaAbi.tableField("local", "5"), is("[\"local\"] = 5"));
        assertThat(LuaAbi.tableField("bad$field", "1"), is("[\"bad$field\"] = 1"));
    }

    @Test
    public void exportAssignmentDotAndBracketForms() {
        assertThat(LuaAbi.exportAssignment("greet", "greet"),
            is("exports.greet = greet"));
        assertThat(LuaAbi.exportAssignment("end", "end"),
            is("exports[\"end\"] = end"));
        assertThat(LuaAbi.exportAssignment("User$fromJson", "__deal[\"User$fromJson\"]"),
            is("exports[\"User$fromJson\"] = __deal[\"User$fromJson\"]"));
    }

    // =========================================================================
    // stringKeyLiteral escaping (Lua 5.1 escape set)
    // =========================================================================

    @Test
    public void stringKeyLiteralPlainKeys() {
        assertThat(LuaAbi.stringKeyLiteral("plain"), is("[\"plain\"]"));
        assertThat(LuaAbi.stringKeyLiteral(""), is("[\"\"]"));
    }

    @Test
    public void stringKeyLiteralEscapesQuotesAndBackslashes() {
        assertThat(LuaAbi.stringKeyLiteral("a\"b"), is("[\"a\\\"b\"]"));
        assertThat(LuaAbi.stringKeyLiteral("a\\b"), is("[\"a\\\\b\"]"));
    }

    @Test
    public void stringKeyLiteralEscapesWhitespace() {
        assertThat(LuaAbi.stringKeyLiteral("a\nb"), is("[\"a\\nb\"]"));
        assertThat(LuaAbi.stringKeyLiteral("a\rb"), is("[\"a\\rb\"]"));
        assertThat(LuaAbi.stringKeyLiteral("a\tb"), is("[\"a\\tb\"]"));
    }

    @Test
    public void stringKeyLiteralEscapesBellBackspaceFormfeedVerticalTab() {
        assertThat(LuaAbi.stringKeyLiteral("\u0007"), is("[\"\\a\"]"));
        assertThat(LuaAbi.stringKeyLiteral("\b"), is("[\"\\b\"]"));
        assertThat(LuaAbi.stringKeyLiteral("\f"), is("[\"\\f\"]"));
        assertThat(LuaAbi.stringKeyLiteral("\u000b"), is("[\"\\v\"]"));
    }

    @Test
    public void stringKeyLiteralControlBytesUseDecimalEscapes() {
        // Control byte not followed by a digit: bare decimal escape.
        assertThat(LuaAbi.stringKeyLiteral("\u0001"), is("[\"\\1\"]"));
        assertThat(LuaAbi.stringKeyLiteral("\u001f"), is("[\"\\31\"]"));
        // Control byte followed by a digit: exactly three digits so the
        // following digit is not absorbed into the escape sequence.
        assertThat(LuaAbi.stringKeyLiteral("\u0001" + "2"), is("[\"\\0012\"]"));
        assertThat(LuaAbi.stringKeyLiteral("x\u0007" + "9"), is("[\"x\\a9\"]"));
        // Control byte followed by a non-digit: bare decimal escape, and the
        // digit-shape predicate runs over every outcome (below '0', a digit,
        // above '9').
        assertThat(LuaAbi.stringKeyLiteral("\u0001" + "!"), is("[\"\\1!\"]"));
        assertThat(LuaAbi.stringKeyLiteral("\u0001" + "a"), is("[\"\\1a\"]"));
        assertThat(LuaAbi.stringKeyLiteral("\u0001" + ":"), is("[\"\\1:\"]"));
        // DEL is a control byte (127, already three digits).
        assertThat(LuaAbi.stringKeyLiteral("\u007f"), is("[\"\\127\"]"));
        assertThat(LuaAbi.stringKeyLiteral("\u007f" + "9"), is("[\"\\1279\"]"));
    }

    @Test
    public void stringKeyLiteralKeepsNonAsciiUntouched() {
        assertThat(LuaAbi.stringKeyLiteral("\u00e9"), is("[\"\u00e9\"]"));
    }

    @Test
    public void emissionIsDeterministic() {
        for (String key : new String[] {
            "name", "end", "User$fromJson", "a\"b", "a\\b", "\u0001" + "2"}) {
            assertThat("deterministic for " + key,
                LuaAbi.stringKeyLiteral(key),
                equalTo(LuaAbi.stringKeyLiteral(key)));
        }
        assertThat(LuaAbi.memberAccess("t", "end"),
            equalTo(LuaAbi.memberAccess("t", "end")));
        assertThat(LuaAbi.helperKey("User", HelperKind.TO_JSON),
            equalTo(LuaAbi.helperKey("User", HelperKind.TO_JSON)));
    }

    // =========================================================================
    // Cross-function consistency
    // =========================================================================

    @Test
    public void composersAgreeWithThePredicates() {
        for (String name : new String[] {
            "safe", "_x", "end", "local", "bad$field", "", "1x"}) {
            KeyForm form = LuaAbi.fieldKeyForm(LuaAbi.RESERVED, name);
            String access = LuaAbi.memberAccess("o", name);
            assertEquals("form for " + name, form,
                LuaAbi.isSafeIdentifier(LuaAbi.RESERVED, name)
                    ? KeyForm.DOT : KeyForm.BRACKET);
            if (form == KeyForm.DOT) {
                assertThat(access, is("o." + name));
            } else {
                assertThat(access, not(is("o." + name)));
            }
        }
    }
}
