package deal.test;

import deal.diagnostics.DiagnosticCode;
import deal.semantic.ir.InvocationPurpose;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticProfile;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Verifies the ISSUE-0281 foundation surface: the E6005 registration in
 * {@link DiagnosticCode}, the {@link LoweringFailureDetail} payload carrier,
 * and the four closed foundation enums
 * ({@link SemanticProfile}, {@link InvocationPurpose}, {@link ReleaseState},
 * {@link SemanticCapability}).
 *
 * <p>Tests:
 * <ol>
 *   <li>E6000..E6005 are the complete E6 code set, all
 *       {@code BACKEND_LOWERING}, in declaration order, with E6005 directly
 *       beside E6000-E6004 and its message template exactly
 *       "Common semantic lowering failed"; E6000 remains the intentional
 *       retained-target rejection code and is never E6005; the E6000-E6004
 *       message templates are unchanged.</li>
 *   <li>Reflection-based exhaustive enumeration of the four closed enums:
 *       exactly the pinned values in the pinned order; enum type, implicit
 *       finality, and constructor surface checked; no open/unknown fallback
 *       member; no static factory or other extension point beyond the
 *       implicit values()/valueOf(String).</li>
 *   <li>{@link LoweringFailureDetail} is an immutable record with exactly
 *       the six pinned components in the pinned order and the pinned
 *       component types, and its accessors return the constructed values.</li>
 * </ol>
 */
public class LoweringFoundationTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // E6005 registration
    // =========================================================================

    static void testE6005Registration() {
        System.out.println("-- E6005 registration --");

        List<String> e6 = new ArrayList<>();
        for (DiagnosticCode dc : DiagnosticCode.values()) {
            if (dc.code().matches("E6\\d{3}")) {
                e6.add(dc.code());
            }
        }
        check(e6.equals(List.of("E6000", "E6001", "E6002", "E6003",
                "E6004", "E6005")),
            "E6 codes are exactly E6000..E6005 in declaration order; got " + e6);

        check(DiagnosticCode.E6005.phase() == DiagnosticCode.Phase.BACKEND_LOWERING,
            "E6005 is BACKEND_LOWERING");
        check("Common semantic lowering failed"
                .equals(DiagnosticCode.E6005.messageTemplate()),
            "E6005 message template is exactly \"Common semantic lowering failed\"");
        check(DiagnosticCode.fromCode("E6005") == DiagnosticCode.E6005,
            "E6005 resolves through fromCode");
        check(DiagnosticCode.isRegistered("E6005"), "E6005 is registered");

        check(DiagnosticCode.E6005.ordinal() == DiagnosticCode.E6004.ordinal() + 1
                && DiagnosticCode.E6004.ordinal() == DiagnosticCode.E6003.ordinal() + 1,
            "E6005 sits directly beside E6000-E6004 (ordinal adjacency)");

        // E6000 remains the intentional retained-target rejection code and is
        // never E6005 (parent D11).
        check(DiagnosticCode.E6000 != DiagnosticCode.E6005,
            "E6000 and E6005 are distinct codes");
        check(DiagnosticCode.E6000.phase() == DiagnosticCode.Phase.BACKEND_LOWERING,
            "E6000 remains BACKEND_LOWERING (retained-target rejection)");

        // No existing E6000-E6004 code was renamed or renumbered: pin the
        // unchanged message templates.
        Map<String, String> pinnedMessages = Map.of(
            "E6000", "Unsupported statement type",
            "E6001", "Continue outside loop",
            "E6002", "Cannot break/continue across try boundary",
            "E6003", "Rest parameters are not part of DEAL v1.2",
            "E6004", "Entry module must export non-async main(): null");
        for (var entry : pinnedMessages.entrySet()) {
            DiagnosticCode dc = DiagnosticCode.fromCode(entry.getKey());
            check(dc != null && entry.getValue().equals(dc.messageTemplate()),
                entry.getKey() + " message template unchanged: "
                    + (dc == null ? "null" : dc.messageTemplate()));
        }
    }

    // =========================================================================
    // Closed enum verification (reflection-based)
    // =========================================================================

    static void testClosedEnum(Class<? extends Enum<?>> type, List<String> pinned,
                               String name) {
        System.out.println("-- Closed enum " + name + " --");

        check(type.isEnum(), name + " is an enum type");
        check(Enum.class.isAssignableFrom(type), name + " extends java.lang.Enum");
        check(Modifier.isFinal(type.getModifiers()),
            name + " is implicitly final (no subclass extension point)");

        Enum<?>[] constants = type.getEnumConstants();
        List<String> actual = new ArrayList<>();
        for (Enum<?> c : constants) {
            actual.add(c.name());
        }
        check(actual.equals(pinned),
            name + " contains exactly " + pinned + " in the pinned order; got " + actual);
        check(constants.length == pinned.size(),
            name + " has exactly " + pinned.size() + " constants (no extra members)");

        // No open/unknown fallback member.
        for (Enum<?> c : constants) {
            String n = c.name();
            check(!n.equals("UNKNOWN") && !n.equals("OPEN") && !n.equals("OTHER")
                    && !n.equals("UNSPECIFIED") && !n.equals("CUSTOM"),
                name + " has no open/unknown fallback member (offending: " + n + ")");
            check(c.getClass() == type,
                name + " constant " + n + " has no constant-specific class body");
        }

        // No external extension point: no public constructor, and the only
        // public static methods returning the enum type (or its array) are
        // the implicit values()/valueOf(String) — the javac-generated
        // private synthetic $values() helper is not a public surface.
        Constructor<?>[] ctors = type.getDeclaredConstructors();
        check(ctors.length == 1 && !Modifier.isPublic(ctors[0].getModifiers()),
            name + " has exactly one private constructor (no public construction)");
        for (Method m : type.getDeclaredMethods()) {
            boolean returnsEnumType = m.getReturnType() == type
                || (m.getReturnType().isArray()
                    && m.getReturnType().getComponentType() == type);
            if (Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers())
                    && returnsEnumType) {
                check(m.getName().equals("values") || m.getName().equals("valueOf"),
                    name + " public static enum-typed surface is exactly "
                        + "values()/valueOf(String); found " + m);
            }
        }

        // Reflection round-trip for every pinned value.
        for (int i = 0; i < pinned.size(); i++) {
            check(valueOfRaw(type, pinned.get(i)) == constants[i],
                name + " valueOf round-trip for " + pinned.get(i)
                    + " hits declaration position " + i);
        }
        try {
            valueOfRaw(type, "UNKNOWN");
            fail(name + " valueOf(\"UNKNOWN\") should have failed (closed set)");
        } catch (IllegalArgumentException expected) {
            check(true, name + " rejects an unknown value by name");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Enum<?> valueOfRaw(Class<? extends Enum<?>> type, String name) {
        return Enum.valueOf((Class) type, name);
    }

    // =========================================================================
    // LoweringFailureDetail
    // =========================================================================

    static void testLoweringFailureDetail() {
        System.out.println("-- LoweringFailureDetail payload carrier --");

        Class<LoweringFailureDetail> type = LoweringFailureDetail.class;
        check(type.isRecord(), "LoweringFailureDetail is an immutable record");

        RecordComponent[] components = type.getRecordComponents();
        String[] pinnedNames = {"module", "capability", "validatorRule",
            "semanticProfile", "irVersion", "origin"};
        Class<?>[] pinnedTypes = {String.class, SemanticCapability.class,
            String.class, SemanticProfile.class, String.class, String.class};
        check(components.length == 6,
            "LoweringFailureDetail has exactly 6 components; got "
                + components.length + " " + Arrays.toString(components));
        for (int i = 0; i < Math.min(components.length, pinnedNames.length); i++) {
            check(pinnedNames[i].equals(components[i].getName()),
                "component " + i + " is named " + pinnedNames[i]
                    + "; got " + components[i].getName());
            check(components[i].getType() == pinnedTypes[i],
                "component " + i + " has type " + pinnedTypes[i].getSimpleName()
                    + "; got " + components[i].getType().getSimpleName());
        }

        Field[] fields = type.getDeclaredFields();
        check(fields.length == 6,
            "LoweringFailureDetail declares exactly 6 fields (no extras); got "
                + fields.length);
        for (Field f : fields) {
            check(Modifier.isPrivate(f.getModifiers()) && Modifier.isFinal(f.getModifiers()),
                "field " + f.getName() + " is private final (immutable carrier)");
        }

        LoweringFailureDetail detail = new LoweringFailureDetail(
            "a.b", SemanticCapability.MODULES, "INDEX_INTERNAL_ERROR_SENTINEL",
            SemanticProfile.DEAL_V1_2_INT32, "deal.semantic-ir/1", "CheckedProjectBuilder");
        check("a.b".equals(detail.module()), "module accessor");
        check(detail.capability() == SemanticCapability.MODULES, "capability accessor");
        check("INDEX_INTERNAL_ERROR_SENTINEL".equals(detail.validatorRule()),
            "validatorRule accessor");
        check(detail.semanticProfile() == SemanticProfile.DEAL_V1_2_INT32,
            "semanticProfile accessor");
        check("deal.semantic-ir/1".equals(detail.irVersion()), "irVersion accessor");
        check("CheckedProjectBuilder".equals(detail.origin()), "origin accessor");
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Lowering Foundation Test (ISSUE-0281) ===\n");

        testE6005Registration();

        testClosedEnum(SemanticProfile.class,
            List.of("LEGACY_SAFE_INT", "DEAL_V1_2_INT32"), "SemanticProfile");
        testClosedEnum(InvocationPurpose.class,
            List.of("PUBLIC_BUILD", "COMMON_SHADOW", "LEGACY_REGRESSION"),
            "InvocationPurpose");
        testClosedEnum(ReleaseState.class,
            List.of("PRE_ACTIVATION", "V1_2_ACTIVE"), "ReleaseState");
        testClosedEnum(SemanticCapability.class,
            List.of("FOUNDATION_VALUES", "SIGNED_INT32", "CONTAINERS_AND_STRINGS",
                "DESCRIPTORS", "BOUNDARIES", "EVALUATION_ORDER", "BINDINGS",
                "CALLS", "STDLIB_SEMANTICS", "STDLIB_TIME_CONFLICT", "CLASSES",
                "MODULES"),
            "SemanticCapability");

        testLoweringFailureDetail();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
