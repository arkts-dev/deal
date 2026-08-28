package deal.test;

import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalClassIdentityIndex;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.CanonicalModuleIdentity.BuiltinModule;
import deal.identity.CanonicalModuleIdentity.ExternalModule;
import deal.identity.CanonicalModuleIdentity.ProjectModule;
import deal.identity.ProjectModuleIdentity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Unit tests for the neutral JDK-only {@code deal.identity} carrier
 * package (ISSUE-0309): equality/hashCode/round-trip for every
 * {@link CanonicalModuleIdentity} variant and the
 * {@link CanonicalClassIdentityIndex} accessor contract exercised through
 * a contract-conformant in-repo test index over the pinned example
 * identities ({@code @lib/utils/User}, {@code @$external/pkg/Cls},
 * {@code @$external/host.cfg/ServerConfig}, {@code @$builtin/Error}).
 *
 * <p>Runs via main() using assertions. Enable with -ea JVM flag.</p>
 */
public class CanonicalIdentityTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // Contract-conformant in-repo test index
    // =========================================================================

    /**
     * Contract-conformant test index over the pinned example identities.
     * The pinned example texts are stored as registered index entries (the
     * real index is E2's artifact); this test proves the accessor contract
     * is implementable and usable and that lookups are byte-for-byte.
     */
    private static final class ExampleIdentityIndex
            implements CanonicalClassIdentityIndex {

        private final Map<CanonicalClassIdentity, String> textByIdentity =
            new LinkedHashMap<>();
        private final Map<String, CanonicalClassIdentity> identityByText =
            new LinkedHashMap<>();

        ExampleIdentityIndex() {
            register(new CanonicalClassIdentity(
                    new ProjectModule(new ProjectModuleIdentity(
                        "lib", "/normalized/roots/lib", List.of("utils"))),
                    "User"),
                "@lib/utils/User");
            register(new CanonicalClassIdentity(
                    new ExternalModule("pkg"), "Cls"),
                "@$external/pkg/Cls");
            register(new CanonicalClassIdentity(
                    new ExternalModule("host.cfg"), "ServerConfig"),
                "@$external/host.cfg/ServerConfig");
            register(new CanonicalClassIdentity(
                    BuiltinModule.INSTANCE, "Error"),
                "@$builtin/Error");
        }

        private void register(CanonicalClassIdentity identity, String text) {
            textByIdentity.put(identity, text);
            identityByText.put(text, identity);
        }

        @Override
        public String descriptorTextFor(CanonicalClassIdentity identity) {
            Objects.requireNonNull(identity, "identity");
            String text = textByIdentity.get(identity);
            if (text == null) {
                throw new IllegalStateException(
                    "identity absent from the index: " + identity);
            }
            return text;
        }

        @Override
        public CanonicalClassIdentity identityForDescriptorText(
                String descriptorText) {
            Objects.requireNonNull(descriptorText, "descriptorText");
            CanonicalClassIdentity identity = identityByText.get(descriptorText);
            if (identity == null) {
                throw new IllegalStateException(
                    "descriptor text absent from the index: \""
                    + descriptorText + "\"");
            }
            return identity;
        }
    }

    // =========================================================================
    // Carriers: ProjectModuleIdentity
    // =========================================================================

    private static void testProjectModuleIdentity() {
        ProjectModuleIdentity a = new ProjectModuleIdentity(
            "src", "/roots/src", List.of("models", "util"));
        ProjectModuleIdentity b = new ProjectModuleIdentity(
            "src", "/roots/src", List.of("models", "util"));
        check(a.equals(b) && b.equals(a),
            "ProjectModuleIdentity: equal fields compare equal");
        check(a.hashCode() == b.hashCode(),
            "ProjectModuleIdentity: equal fields have equal hashCodes");
        check(a.configuredRootText().equals("src")
                && a.normalizedRootPath().equals("/roots/src")
                && a.relativeModuleComponents().equals(List.of("models", "util")),
            "ProjectModuleIdentity: accessors return the pinned inputs");

        // Structural list equality: a fresh list with equal elements is equal.
        ProjectModuleIdentity c = new ProjectModuleIdentity(
            "src", "/roots/src", new ArrayList<>(List.of("models", "util")));
        check(a.equals(c) && a.hashCode() == c.hashCode(),
            "ProjectModuleIdentity: component list equality is structural");

        // Each distinct field breaks equality.
        check(!a.equals(new ProjectModuleIdentity(
                "lib", "/roots/src", List.of("models", "util"))),
            "ProjectModuleIdentity: distinct configuredRootText is unequal");
        check(!a.equals(new ProjectModuleIdentity(
                "src", "/other/src", List.of("models", "util"))),
            "ProjectModuleIdentity: distinct normalizedRootPath is unequal");
        check(!a.equals(new ProjectModuleIdentity(
                "src", "/roots/src", List.of("models"))),
            "ProjectModuleIdentity: distinct relativeModuleComponents is unequal");
        check(!a.equals(new ProjectModuleIdentity(
                "src", "/roots/src", List.of())),
            "ProjectModuleIdentity: empty components is unequal to non-empty");

        // Empty component list round-trips.
        ProjectModuleIdentity empty = new ProjectModuleIdentity(
            "root", "/roots/root", List.of());
        check(empty.equals(new ProjectModuleIdentity(
                "root", "/roots/root", List.of())),
            "ProjectModuleIdentity: empty components round-trips");
        check(empty.relativeModuleComponents().isEmpty(),
            "ProjectModuleIdentity: empty components stay empty");

        // Defensive copy: mutating the input list does not mutate the record.
        List<String> mutable = new ArrayList<>(List.of("x", "y"));
        ProjectModuleIdentity d = new ProjectModuleIdentity(
            "r", "/r", mutable);
        mutable.clear();
        check(d.relativeModuleComponents().equals(List.of("x", "y"))
                && !d.relativeModuleComponents().isEmpty(),
            "ProjectModuleIdentity: component list is defensively copied");
        try {
            d.relativeModuleComponents().add("z");
            fail("ProjectModuleIdentity: components list must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            check(true, "ProjectModuleIdentity: components list is unmodifiable");
        }

        // Null rejection.
        try {
            new ProjectModuleIdentity(null, "/r", List.of());
            fail("ProjectModuleIdentity: null configuredRootText must be rejected");
        } catch (NullPointerException expected) {
            check(true, "ProjectModuleIdentity: null configuredRootText rejected");
        }
        try {
            new ProjectModuleIdentity("r", null, List.of());
            fail("ProjectModuleIdentity: null normalizedRootPath must be rejected");
        } catch (NullPointerException expected) {
            check(true, "ProjectModuleIdentity: null normalizedRootPath rejected");
        }
        try {
            new ProjectModuleIdentity("r", "/r", null);
            fail("ProjectModuleIdentity: null components list must be rejected");
        } catch (NullPointerException expected) {
            check(true, "ProjectModuleIdentity: null components list rejected");
        }
        try {
            new ProjectModuleIdentity("r", "/r", List.of("a", null));
            fail("ProjectModuleIdentity: null component element must be rejected");
        } catch (NullPointerException expected) {
            check(true, "ProjectModuleIdentity: null component element rejected");
        }
    }

    // =========================================================================
    // Carriers: CanonicalModuleIdentity variants
    // =========================================================================

    private static void testModuleIdentityVariants() {
        ProjectModuleIdentity pmA = new ProjectModuleIdentity(
            "src", "/roots/src", List.of("utils"));
        ProjectModuleIdentity pmB = new ProjectModuleIdentity(
            "src", "/roots/src", new ArrayList<>(List.of("utils")));
        ProjectModuleIdentity pmOther = new ProjectModuleIdentity(
            "src", "/roots/src", List.of("other"));

        ProjectModule p1 = new ProjectModule(pmA);
        ProjectModule p2 = new ProjectModule(pmB);
        ProjectModule p3 = new ProjectModule(pmOther);
        check(p1.equals(p2) && p1.hashCode() == p2.hashCode(),
            "ProjectModule: structural equality over the project identity");
        check(!p1.equals(p3) && !p1.equals(null),
            "ProjectModule: distinct project identities are unequal");

        ExternalModule e1 = new ExternalModule("host.cfg");
        ExternalModule e2 = new ExternalModule("host.cfg");
        ExternalModule e3 = new ExternalModule("host-config");
        check(e1.equals(e2) && e1.hashCode() == e2.hashCode(),
            "ExternalModule: equal raw import specifiers compare equal");
        check(e1.rawImportSpecifier().equals("host.cfg"),
            "ExternalModule: accessor returns the raw import specifier");
        check(!e1.equals(e3) && !e1.equals(null),
            "ExternalModule: distinct specifiers are unequal");

        // BuiltinModule singleton: reference-identical, self-equal.
        check(BuiltinModule.INSTANCE == BuiltinModule.INSTANCE,
            "BuiltinModule: exactly one singleton instance");
        check(BuiltinModule.INSTANCE.equals(BuiltinModule.INSTANCE)
                && BuiltinModule.INSTANCE.hashCode()
                   == BuiltinModule.INSTANCE.hashCode(),
            "BuiltinModule: singleton equals itself");

        // Cross-variant inequality in every direction.
        check(!p1.equals(e1) && !e1.equals(p1),
            "ProjectModule vs ExternalModule: unequal both ways");
        check(!p1.equals(BuiltinModule.INSTANCE)
                && !BuiltinModule.INSTANCE.equals(p1),
            "ProjectModule vs BuiltinModule: unequal both ways");
        check(!e1.equals(BuiltinModule.INSTANCE)
                && !BuiltinModule.INSTANCE.equals(e1),
            "ExternalModule vs BuiltinModule: unequal both ways");

        // Null rejection.
        try {
            new ProjectModule(null);
            fail("ProjectModule: null project identity must be rejected");
        } catch (NullPointerException expected) {
            check(true, "ProjectModule: null project identity rejected");
        }
        try {
            new ExternalModule(null);
            fail("ExternalModule: null raw import specifier must be rejected");
        } catch (NullPointerException expected) {
            check(true, "ExternalModule: null raw import specifier rejected");
        }
    }

    // =========================================================================
    // Carriers: CanonicalClassIdentity
    // =========================================================================

    private static void testClassIdentity() {
        // The pinned intrinsic builtin identity is constructible.
        CanonicalClassIdentity error1 = new CanonicalClassIdentity(
            BuiltinModule.INSTANCE, "Error");
        CanonicalClassIdentity error2 = new CanonicalClassIdentity(
            BuiltinModule.INSTANCE, "Error");
        check(error1.equals(error2) && error1.hashCode() == error2.hashCode(),
            "CanonicalClassIdentity: intrinsic (BuiltinModule, \"Error\") "
            + "constructs and compares equal");
        check(error1.moduleIdentity() == BuiltinModule.INSTANCE
                && error1.className().equals("Error"),
            "CanonicalClassIdentity: accessors return the pinned components");

        // Structural equality across equal module identities built separately.
        CanonicalClassIdentity user1 = new CanonicalClassIdentity(
            new ProjectModule(new ProjectModuleIdentity(
                "lib", "/roots/lib", List.of("utils"))),
            "User");
        CanonicalClassIdentity user2 = new CanonicalClassIdentity(
            new ProjectModule(new ProjectModuleIdentity(
                "lib", "/roots/lib", new ArrayList<>(List.of("utils")))),
            "User");
        check(user1.equals(user2) && user1.hashCode() == user2.hashCode(),
            "CanonicalClassIdentity: structural equality over the module "
            + "identity and class name");

        // Distinct class name breaks equality.
        check(!user1.equals(new CanonicalClassIdentity(
                new ProjectModule(new ProjectModuleIdentity(
                    "lib", "/roots/lib", List.of("utils"))),
                "Admin")),
            "CanonicalClassIdentity: distinct class name is unequal");

        // Distinct module identity breaks equality.
        check(!user1.equals(new CanonicalClassIdentity(
                new ProjectModule(new ProjectModuleIdentity(
                    "lib", "/roots/lib", List.of("other"))),
                "User")),
            "CanonicalClassIdentity: distinct module identity is unequal");

        // Distinct module variant breaks equality.
        check(!user1.equals(new CanonicalClassIdentity(
                new ExternalModule("lib"), "User")),
            "CanonicalClassIdentity: distinct module variant is unequal");
        check(!user1.equals(error1) && !error1.equals(user1),
            "CanonicalClassIdentity: project vs builtin identities unequal "
            + "both ways");

        // Null rejection.
        try {
            new CanonicalClassIdentity(null, "User");
            fail("CanonicalClassIdentity: null module identity must be rejected");
        } catch (NullPointerException expected) {
            check(true, "CanonicalClassIdentity: null module identity rejected");
        }
        try {
            new CanonicalClassIdentity(BuiltinModule.INSTANCE, null);
            fail("CanonicalClassIdentity: null class name must be rejected");
        } catch (NullPointerException expected) {
            check(true, "CanonicalClassIdentity: null class name rejected");
        }
    }

    // =========================================================================
    // Index accessor contract
    // =========================================================================

    private static void testIndexAccessorContract() {
        ExampleIdentityIndex index = new ExampleIdentityIndex();

        CanonicalClassIdentity libUser = new CanonicalClassIdentity(
            new ProjectModule(new ProjectModuleIdentity(
                "lib", "/normalized/roots/lib", List.of("utils"))),
            "User");
        CanonicalClassIdentity extCls = new CanonicalClassIdentity(
            new ExternalModule("pkg"), "Cls");
        CanonicalClassIdentity extCfg = new CanonicalClassIdentity(
            new ExternalModule("host.cfg"), "ServerConfig");
        CanonicalClassIdentity builtinError = new CanonicalClassIdentity(
            BuiltinModule.INSTANCE, "Error");

        // descriptorTextFor returns exactly the pinned example texts.
        check("@lib/utils/User".equals(index.descriptorTextFor(libUser)),
            "Index: descriptorTextFor(lib User) == \"@lib/utils/User\"");
        check("@$external/pkg/Cls".equals(index.descriptorTextFor(extCls)),
            "Index: descriptorTextFor(pkg Cls) == \"@$external/pkg/Cls\"");
        check("@$external/host.cfg/ServerConfig"
                .equals(index.descriptorTextFor(extCfg)),
            "Index: descriptorTextFor(host.cfg ServerConfig) == "
            + "\"@$external/host.cfg/ServerConfig\"");
        check("@$builtin/Error".equals(index.descriptorTextFor(builtinError)),
            "Index: descriptorTextFor(intrinsic Error) == \"@$builtin/Error\"");

        // identityForDescriptorText round-trips byte-for-byte.
        check(libUser.equals(index.identityForDescriptorText("@lib/utils/User")),
            "Index: identityForDescriptorText round-trips the project identity");
        check(extCls.equals(index.identityForDescriptorText("@$external/pkg/Cls")),
            "Index: identityForDescriptorText round-trips the externals identity");
        check(extCfg.equals(
                index.identityForDescriptorText("@$external/host.cfg/ServerConfig")),
            "Index: identityForDescriptorText round-trips the dotted-externals "
            + "identity");
        check(builtinError.equals(
                index.identityForDescriptorText("@$builtin/Error")),
            "Index: identityForDescriptorText round-trips the builtin identity");

        // Both accessor directions compose to byte identity.
        check(index.descriptorTextFor(
                index.identityForDescriptorText("@lib/utils/User"))
                .equals("@lib/utils/User"),
            "Index: text -> identity -> text is byte-identical");
        check(index.identityForDescriptorText(
                index.descriptorTextFor(builtinError)).equals(builtinError),
            "Index: identity -> text -> identity round-trips");

        // Byte-for-byte keying: no spelling ever crosses slash vs dot, no
        // case folding, no trailing-space trimming.
        for (String absentText : new String[] {
                "@lib.utils/User",   // dot in the root/path spelling
                "@lib/utils/user",   // case delta
                "@lib/utils/User ",  // trailing space
                "@lib/utils/Userx",  // trailing scalar
                "@$external/pkg/cls",
                "@$external/host-cfg/ServerConfig",
                "@$builtin/error",
                "@lib/utils/User/Extra",  // extra component
                "lib/utils/User"          // missing leading '@'
        }) {
            try {
                index.identityForDescriptorText(absentText);
                fail("Index: absent text must raise the invariant violation: \""
                    + absentText + "\"");
            } catch (IllegalStateException expected) {
                check(true, "Index: absent text raises IllegalStateException: \""
                    + absentText + "\"");
            }
        }

        // Absent identities: never a silent fallback, never invented text.
        for (CanonicalClassIdentity absentIdentity : List.of(
                new CanonicalClassIdentity(new ExternalModule("pkg"), "Other"),
                new CanonicalClassIdentity(BuiltinModule.INSTANCE, "Nope"),
                new CanonicalClassIdentity(new ProjectModule(
                    new ProjectModuleIdentity("other", "/roots/other",
                        List.of())), "User"))) {
            try {
                index.descriptorTextFor(absentIdentity);
                fail("Index: absent identity must raise the invariant "
                    + "violation: " + absentIdentity);
            } catch (IllegalStateException expected) {
                check(true, "Index: absent identity raises "
                    + "IllegalStateException: " + absentIdentity);
            }
        }

        // Null arguments are rejected by the conformant index.
        try {
            index.descriptorTextFor(null);
            fail("Index: null identity must be rejected");
        } catch (NullPointerException expected) {
            check(true, "Index: null identity rejected");
        }
        try {
            index.identityForDescriptorText(null);
            fail("Index: null descriptor text must be rejected");
        } catch (NullPointerException expected) {
            check(true, "Index: null descriptor text rejected");
        }
    }

    // =========================================================================
    // Runner
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Identity Carrier Package Tests (ISSUE-0309) ===\n");

        testProjectModuleIdentity();
        testModuleIdentityVariants();
        testClassIdentity();
        testIndexAccessorContract();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
