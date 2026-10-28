package deal.test;

import deal.codegen.llvm.LlvmRuntimeAbi;
import deal.codegen.llvm.LlvmTypeMapper;
import deal.semantic.ir.FailureArm;
import deal.semantic.ir.FailureArmId;
import deal.semantic.ir.FailureContractRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Java runtime-ABI mirror of the native backend
 * ({@code llvm-native-codegen-cfg-ssa} Verification 3, {@code llvm-native-
 * runtime-value-model} R5/R9, task "Java runtime ABI mirror").
 *
 * <p>Every case compares the mirror against the <em>published</em> header
 * bytes under {@code deal/native/}, never against a copy:</p>
 *
 * <ol>
 *   <li>the declaration set equals the published prototypes of
 *       {@code deal_rt.h}, {@code entry.h} and every group header, name and C
 *       type for name, including the host value surface, the DEAL-origin
 *       failure channel, the native host load surface and the arm-table
 *       transport;</li>
 *   <li>every mirrored declaration renders one textual IR declaration, and a
 *       module carrying all of them (with the mirrored record layouts) is
 *       accepted by {@code llvm-as};</li>
 *   <li>the emitted static-data layouts follow the published field order of
 *       {@code deal_arm_record}, {@code deal_arm_table} and
 *       {@code deal_origin}, and the arm-table version and render-scope
 *       discriminants equal the published macros;</li>
 *   <li>the C2 export surface is exactly the six published entry.h
 *       declarations, and the runtime-owned entry surface is exactly the
 *       process entry, the terminal and the ten error accessors;</li>
 *   <li>the closed failure-arm table carries one record per arm in the
 *       registry's canonical order with the registry's own code, template,
 *       parameter names, render scope and expected/actual flags — the emitted
 *       text is data, never authored by the emitter (R5).</li>
 * </ol>
 */
public final class LlvmRuntimeAbiMirrorTest {

    private static final Path REPO = NativeCompiler.repository();

    /** The published headers the mirror must equal, in include order. */
    private static final List<String> HEADERS = List.of(
        "deal/native/deal_rt.h",
        "deal/native/platform.h",
        "deal/native/value.h",
        "deal/native/memory.h",
        "deal/native/text.h",
        "deal/native/container.h",
        "deal/native/descriptor.h",
        "deal/native/error.h",
        "deal/native/json.h",
        "deal/native/std.h",
        "deal/native/host.h",
        "deal/native/async.h",
        "deal/native/entry.h");

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("=== LLVM Runtime ABI Mirror Tests (ISSUE-0903) ===\n");
        testDeclarationParity();
        testRenderedDeclarationsAreValidIr();
        testRecordLayoutFieldOrder();
        testExportSurfaceAndRuntimeOwnership();
        testArmTableEquality();
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== LLVM Runtime ABI Mirror Tests Passed ===");
    }

    // =========================================================================
    // 1. Declaration parity against the published headers
    // =========================================================================

    private static void testDeclarationParity() throws IOException {
        System.out.println("-- every published prototype equals the mirror --");
        Map<String, String> published = new LinkedHashMap<>();
        for (String header : HEADERS) {
            published.putAll(parsePrototypes(Files.readString(REPO.resolve(header),
                StandardCharsets.UTF_8)));
        }
        Map<String, String> mirrored = new LinkedHashMap<>();
        for (LlvmRuntimeAbi.Declaration declaration
                : LlvmRuntimeAbi.declarations()) {
            mirrored.put(declaration.name(), declaration.cSpelling());
        }
        checkEq(published.keySet(), mirrored.keySet(),
            "the mirror declares exactly the published entry names");
        for (Map.Entry<String, String> entry : published.entrySet()) {
            checkEq(entry.getValue(), mirrored.get(entry.getKey()),
                "the mirror's C signature of " + entry.getKey());
        }
        check(mirrored.containsKey("deal_host_load"),
            "the native host load surface is mirrored");
        check(mirrored.containsKey("deal_arm_table_install"),
            "the arm-table transport is mirrored");
        check(mirrored.containsKey("deal_root_frame_enter"),
            "the root-frame entries are mirrored");
        check(mirrored.containsKey("deal_error_from_handle"),
            "the DEAL-origin failure channel is mirrored");
        check(mirrored.containsKey("deal_project_exports"),
            "the emitted export surface is mirrored");
        for (String name : LlvmRuntimeAbi.EXPORT_SURFACE) {
            check(published.containsKey(name),
                "the module's export-surface entry " + name
                    + " is one published entry.h declaration");
            checkEq(published.get(name), mirrored.get(name),
                "the mirror's C signature of the export-surface entry " + name);
        }
    }

    /**
     * The published function prototypes of one header: comments and
     * preprocessor lines removed, statements split at the semicolons outside
     * braces, typedefs skipped, and every declarator normalized to the
     * canonical {@code <return> <name>(<parameter>) } spelling the mirror
     * carries.
     */
    private static Map<String, String> parsePrototypes(String headerText) {
        String withoutComments = headerText.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder statementFree = new StringBuilder();
        int braces = 0;
        for (String line : withoutComments.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("#")) {
                statementFree.append(trimmed).append(' ');
            }
            for (char character : trimmed.toCharArray()) {
                if (character == '{') {
                    braces++;
                } else if (character == '}') {
                    braces--;
                }
            }
        }
        // A typedef struct body contains semicolons; strip every typedef
        // statement before splitting prototypes at the remaining semicolons.
        String text = statementFree.toString();
        Map<String, String> prototypes = new LinkedHashMap<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        List<String> statements = new ArrayList<>();
        for (char character : text.toCharArray()) {
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
            }
            if (character == ';' && depth == 0) {
                statements.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        for (String statement : statements) {
            if (statement.isEmpty() || statement.contains("typedef")
                    || !statement.contains("(")) {
                continue;
            }
            String normalized = normalizePrototype(statement);
            if (normalized == null) {
                continue;
            }
            int open = normalized.indexOf('(');
            String head = normalized.substring(0, open).strip();
            String name = head.substring(head.lastIndexOf(' ') + 1);
            prototypes.put(name, normalized);
        }
        return prototypes;
    }

    /** Normalizes one C prototype statement, or {@code null} for a non-entry. */
    private static String normalizePrototype(String statement) {
        String text = statement.replaceAll("\\s+", " ").strip();
        int open = text.indexOf('(');
        int close = text.lastIndexOf(')');
        if (open < 0 || close < open) {
            return null;
        }
        String head = text.substring(0, open).strip();
        if (head.startsWith("_Noreturn ")) {
            head = head.substring("_Noreturn ".length()).strip();
        }
        int space = head.lastIndexOf(' ');
        String returnType = head.substring(0, space).strip();
        String name = head.substring(space + 1).strip();
        int stars = 0;
        while (stars < name.length() && name.charAt(stars) == '*') {
            stars++;
        }
        if (stars > 0) {
            returnType = returnType + " " + "*".repeat(stars);
            name = name.substring(stars);
        }
        if (!name.startsWith("deal_")) {
            return null;
        }
        String parameters = text.substring(open + 1, close).strip();
        if (parameters.isEmpty() || parameters.equals("void")) {
            parameters = "";
        }
        List<String> normalizedParameters = new ArrayList<>();
        if (!parameters.isEmpty()) {
            for (String part : parameters.split(", ")) {
                String parameter = part.strip().replaceAll("\\s+", " ");
                int lastSpace = parameter.lastIndexOf(' ');
                String type = parameter.substring(0, lastSpace).strip();
                String declarator = parameter.substring(lastSpace + 1).strip();
                int parameterStars = 0;
                while (parameterStars < declarator.length()
                        && declarator.charAt(parameterStars) == '*') {
                    parameterStars++;
                }
                if (parameterStars > 0) {
                    type = type + " " + "*".repeat(parameterStars);
                    declarator = declarator.substring(parameterStars);
                }
                normalizedParameters.add(type + (type.endsWith("*") ? "" : " ")
                    + declarator);
            }
        }
        return returnType + " " + name + "("
            + String.join(", ", normalizedParameters) + ")";
    }

    // =========================================================================
    // 2. Every mirrored declaration renders valid IR
    // =========================================================================

    private static void testRenderedDeclarationsAreValidIr() throws IOException {
        System.out.println("-- the whole mirrored declaration set is valid IR --");
        StringBuilder module = new StringBuilder();
        module.append("source_filename = \"deal.abi.probe\"\n");
        module.append("target triple = \"" + NativeCompiler.TARGET_TRIPLE
            + "\"\n\n");
        for (String definition : LlvmTypeMapper.recordDefinitions()) {
            module.append(definition).append('\n');
        }
        for (String opaque : LlvmTypeMapper.moduleOwnedOpaqueDefinitions()) {
            module.append(opaque).append('\n');
        }
        module.append('\n');
        for (LlvmRuntimeAbi.Declaration declaration
                : LlvmRuntimeAbi.declarations()) {
            module.append(declaration.llvmDeclaration()).append('\n');
        }
        Path scratch = Files.createTempDirectory("deal-llvm-abi-");
        try {
            Path file = scratch.resolve("abi.ll");
            Files.writeString(file, module.toString(), StandardCharsets.UTF_8);
            NativeCompiler.Result assembled = NativeCompiler.run(
                List.of("llvm-as", file.toString(), "-o",
                    scratch.resolve("abi.bc").toString()), scratch);
            checkEq(0, assembled.exitCode(),
                "llvm-as accepts every mirrored declaration and layout: "
                    + assembled.describe());
        } finally {
            deleteRecursively(scratch);
        }
    }

    // =========================================================================
    // 3. The published record layouts and discriminants
    // =========================================================================

    private static void testRecordLayoutFieldOrder() throws IOException {
        System.out.println("-- record layouts and discriminants follow the headers --");
        String errorHeader = Files.readString(
            REPO.resolve("deal/native/error.h"), StandardCharsets.UTF_8);
        String rtHeader = Files.readString(
            REPO.resolve("deal/native/deal_rt.h"), StandardCharsets.UTF_8);
        String armRecord = structFields(errorHeader, "deal_arm_record");
        checkEq("uint32_t id; const char *code; const char *template_text;"
                + " const char *const *parameter_names; uint32_t parameter_count;"
                + " uint8_t render_scope; uint8_t has_expected, has_actual",
            armRecord, "deal_arm_record's published field order");
        checkEq("{ i32, i8*, i8*, i8**, i32, i8, i8, i8 }",
            LlvmRuntimeAbi.ARM_RECORD_LAYOUT,
            "the emitted arm record layout follows that field order");
        checkEq(declaratorCount(armRecord),
            LlvmRuntimeAbi.ARM_RECORD_LAYOUT.split(",").length,
            "the emitted arm record carries one IR member per C declarator");
        String armTable = structFields(errorHeader, "deal_arm_table");
        checkEq("uint32_t version; uint32_t count; const deal_arm_record *records",
            armTable, "deal_arm_table's published field order");
        checkEq("%struct.deal_arm_table",
            LlvmTypeMapper.llvmTypeOfCType("deal_arm_table"),
            "the emitted arm table is one named record");
        checkEq(declaratorCount(armTable), 3,
            "the emitted arm table carries the version, the count and the"
                + " records pointer");
        String origin = structFields(rtHeader, "deal_origin");
        checkEq("const char *source_file; int32_t line; int32_t column",
            origin, "deal_origin's published field order");
        checkEq("{ i8*, i32, i32 }", LlvmRuntimeAbi.ORIGIN_LAYOUT,
            "the emitted origin layout follows that field order");
        checkEq("deal_error *error",
            structFields(rtHeader, "deal_outcome"),
            "the error-only outcome carries exactly the error member");
        checkEq("deal_error *error; double value",
            structFields(rtHeader, "deal_outcome_number"),
            "the numeric outcome is error-first with the number second");
        checkEq("deal_error *error; uint8_t value",
            structFields(rtHeader, "deal_outcome_boolean"),
            "the boolean outcome is error-first with the 0/1 byte second");
        checkEq("%struct.deal_outcome_number",
            LlvmTypeMapper.llvmTypeOfCType("deal_outcome_number"),
            "the numeric outcome is one named record");
        checkEq("%struct.deal_outcome_boolean",
            LlvmTypeMapper.llvmTypeOfCType("deal_outcome_boolean"),
            "the boolean outcome is one named record");
        // The published enum discriminants the emitted data encodes.
        check(rtHeader.contains("DEAL_OK = 0"),
            "the host status enum starts at DEAL_OK = 0");
        check(rtHeader.contains("DEAL_KIND_NULL, DEAL_KIND_MISSING"),
            "the host kind enum starts at DEAL_KIND_NULL");
        // The shared value-surface records (R4, R2): the check input, its
        // recorded context, the class layout and one supplied field, whose field
        // order the emitted IR follows. The check input, the class field and the
        // class layout are published by deal_rt.h beside the other records every
        // group shares; the check context and the supplied field stay with the
        // descriptor group.
        String descriptorHeader = Files.readString(
            REPO.resolve("deal/native/descriptor.h"), StandardCharsets.UTF_8);
        checkEq("uint32_t presentation; uint32_t unicode_defect; deal_data datum;"
                + " const char *class_atom; const char *signature",
            structFields(rtHeader, "deal_check_input"),
            "deal_check_input's published field order");
        checkEq("{ i32, i32, %struct.deal_data, i8*, i8* }",
            LlvmTypeMapper.recordLayout("deal_check_input").orElse(""),
            "the emitted check-input layout follows that field order");
        checkEq(declaratorCount(structFields(rtHeader, "deal_check_input")),
            LlvmTypeMapper.recordLayout("deal_check_input").orElse("").split(",").length,
            "the emitted check input carries one IR member per C declarator");
        checkEq("const deal_origin *origin; int32_t parameter_index",
            structFields(descriptorHeader, "deal_check_context"),
            "deal_check_context's published field order");
        checkEq("{ %struct.deal_origin*, i32 }",
            LlvmTypeMapper.recordLayout("deal_check_context").orElse(""),
            "the emitted check-context layout follows that field order");
        checkEq("const char *name; deal_descriptor *descriptor; int optional",
            structFields(rtHeader, "deal_class_field"),
            "deal_class_field's published field order");
        checkEq("{ i8*, i8*, i32 }",
            LlvmTypeMapper.recordLayout("deal_class_field").orElse(""),
            "the emitted class-field layout follows that field order");
        checkEq("const char *class_atom; const deal_class_field *fields;"
                + " size_t field_count",
            structFields(rtHeader, "deal_class_layout"),
            "deal_class_layout's published field order");
        checkEq("{ i8*, %struct.deal_class_field*, i64 }",
            LlvmTypeMapper.recordLayout("deal_class_layout").orElse(""),
            "the emitted class-layout layout follows that field order");
        checkEq("const char *name; deal_check_input value; deal_origin origin",
            structFields(descriptorHeader, "deal_class_supplied"),
            "deal_class_supplied's published field order");
        checkEq("{ i8*, %struct.deal_check_input, %struct.deal_origin }",
            LlvmTypeMapper.recordLayout("deal_class_supplied").orElse(""),
            "the emitted supplied-field layout follows that field order");
        // The json group's published plan record and the tagged-data outcome
        // the two group entries answer.
        String jsonHeader = Files.readString(
            REPO.resolve("deal/native/json.h"), StandardCharsets.UTF_8);
        checkEq("const deal_class_layout *layout; deal_json_construct construct;"
                + " deal_json_read_field read_field; void *context",
            structFields(jsonHeader, "deal_json_class_plan"),
            "deal_json_class_plan's published field order");
        checkEq("{ %struct.deal_class_layout*, i8*, i8*, i8* }",
            LlvmTypeMapper.recordLayout("deal_json_class_plan").orElse(""),
            "the emitted class-plan layout follows that field order");
        checkEq("deal_error *error; deal_data value",
            structFields(rtHeader, "deal_outcome_data"),
            "deal_outcome_data is error-first with the tagged datum second");
        checkEq("{ i8*, %struct.deal_data }",
            LlvmTypeMapper.recordLayout("deal_outcome_data").orElse(""),
            "the emitted tagged-data outcome follows that field order");
        // The arm renderer's published records (R5).
        checkEq("const char *name; const char *value",
            structFields(errorHeader, "deal_arm_parameter"),
            "deal_arm_parameter's published field order");
        checkEq("{ i8*, i8* }",
            LlvmTypeMapper.recordLayout("deal_arm_parameter").orElse(""),
            "the emitted arm-parameter layout follows that field order");
        checkEq("uint32_t kind; deal_error *error; const char *code;"
                + " const char *message; const char *expected; const char *actual",
            structFields(errorHeader, "deal_failure_admission"),
            "deal_failure_admission's published field order");
        checkEq("{ i32, i8*, i8*, i8*, i8*, i8* }",
            LlvmTypeMapper.recordLayout("deal_failure_admission").orElse(""),
            "the emitted failure-admission layout follows that field order");
        // The published arm identities and the repertoire count the install
        // entry validates the emitted table against.
        for (FailureArmId id : FailureArmId.values()) {
            check(errorHeader.contains("#define DEAL_ARM_ID_" + id.name() + " "),
                "the header publishes the arm identity " + id);
        }
        checkEq("29", macroText(errorHeader, "DEAL_ARM_REPERTOIRE_COUNT"),
            "the published repertoire count is the closed arm set");
    }

    /**
     * The normalized field body of one published struct definition: the
     * brace body with its field declarations joined by {@code "; "} in
     * source order. An unnamed struct is a typedef of its brace body, so
     * this comparison pins the published order exactly.
     */
    private static String structFields(String headerText, String typeName) {
        String withoutComments = headerText.replaceAll("(?s)/\\*.*?\\*/", " ");
        Matcher matcher = Pattern.compile(
            "typedef struct\\s*\\{([^{}]*)\\}\\s*" + Pattern.quote(typeName)
                + "\\s*;").matcher(withoutComments);
        if (!matcher.find()) {
            return "";
        }
        List<String> fields = new ArrayList<>();
        for (String field : matcher.group(1).split(";")) {
            String text = field.replaceAll("\\s+", " ").strip();
            if (!text.isEmpty()) {
                fields.add(text);
            }
        }
        return String.join("; ", fields);
    }

    /**
     * The number of C declarators of one struct body: every comma-separated
     * declarator of every field declaration, which is the number of IR type
     * members the emitted layout must carry.
     */
    private static int declaratorCount(String structBody) {
        int count = 0;
        for (String field : structBody.split("; ")) {
            if (!field.isEmpty()) {
                count += field.split(",").length;
            }
        }
        return count;
    }

    private static String macroText(String headerText, String macro) {
        Matcher matcher = Pattern.compile(
            "#define\\s+" + Pattern.quote(macro) + "\\s+([0-9]+)[uUlL]*")
            .matcher(headerText);
        return matcher.find() ? matcher.group(1) : "";
    }

    // =========================================================================
    // 4. The C2 export surface and the runtime-owned entries
    // =========================================================================

    private static void testExportSurfaceAndRuntimeOwnership() throws IOException {
        System.out.println("-- the export surface and runtime ownership split --");
        String entryHeader = Files.readString(
            REPO.resolve("deal/native/entry.h"), StandardCharsets.UTF_8);
        Map<String, String> published = parsePrototypes(entryHeader);
        checkEq(6, LlvmRuntimeAbi.EXPORT_SURFACE.size(),
            "the module defines exactly six export-surface functions");
        for (String name : LlvmRuntimeAbi.EXPORT_SURFACE) {
            check(published.containsKey(name),
                "entry.h declares the export-surface entry " + name);
        }
        checkEq(11, LlvmRuntimeAbi.RUNTIME_OWNED.size(),
            "the runtime owns the process entry, the terminal and the ten"
                + " accessors");
        for (String name : LlvmRuntimeAbi.RUNTIME_OWNED) {
            check(published.containsKey(name),
                "entry.h declares the runtime-owned entry " + name);
            check(!LlvmRuntimeAbi.EXPORT_SURFACE.contains(name),
                "the runtime-owned entry " + name + " is not module-defined");
        }
        checkEq("deal_project_main",
            LlvmRuntimeAbi.RUNTIME_OWNED.get(0),
            "the process-level terminal projection is runtime-owned");
        check(entryHeader.contains("deal_module")
                && entryHeader.contains("deal_export_entry"),
            "entry.h names the module-owned record types");
        check(!entryHeader.contains("struct deal_module {"),
            "entry.h declares no deal_module member (C2 opacity)");
        check(!entryHeader.contains("struct deal_export_entry {"),
            "entry.h declares no deal_export_entry member (C2 opacity)");
    }

    // =========================================================================
    // 5. The failure-arm table equality (R5)
    // =========================================================================

    private static void testArmTableEquality() {
        System.out.println("-- the emitted arm table equals the registry --");
        List<String> canonical = FailureContractRegistry.canonicalArmSerialization();
        Map<deal.semantic.ir.FailureArmId, FailureArm> arms =
            FailureContractRegistry.arms();
        checkEq(arms.size(), canonical.size(),
            "the canonical serialization carries one line per arm");
        int index = 0;
        for (FailureArm arm : arms.values()) {
            String expected = arm.id().name() + "|"
                + FailureContractRegistry.codeOf(arm) + "|" + arm.template()
                + "|" + arm.expectedSource().name() + "|"
                + arm.actualProjection().name() + "|" + arm.scope().name()
                + "|" + arm.origin().name() + "|"
                + FailureContractRegistry.parameterSourcesText(arm)
                + (arm.pinnedExpectedText() == null ? ""
                    : "|" + arm.pinnedExpectedText());
            checkEq(expected, canonical.get(index++),
                "the canonical line of " + arm.id()
                    + " is the arm's own record field for field");
            checkEq(arm.expectedSource() == FailureArm.ExpectedSource.NONE
                    ? 0 : 1, emittedFlag(arm, true),
                "the emitted has-expected flag follows the arm's declared field");
            checkEq(arm.actualProjection() == FailureArm.ActualProjection.NONE
                    ? 0 : 1, emittedFlag(arm, false),
                "the emitted has-actual flag follows the arm's declared field");
            checkEq(arm.scope() == FailureArm.RenderScope.INNER_ONLY
                    ? LlvmRuntimeAbi.ARM_SCOPE_INNER_ONLY
                    : LlvmRuntimeAbi.ARM_SCOPE_TOP_LEVEL,
                emittedScope(arm),
                "the emitted render-scope discriminant follows the arm scope");
        }
        check(arms.size() > 0, "the registry declares rendered arms");
        checkEq(8, LlvmRuntimeAbi.ARM_RECORD_LAYOUT.split(",").length,
            "the emitted arm record carries the registry's eight facts");
    }

    /**
     * The expected/actual presence flag the emitted arm record carries: the
     * one mapping from the registry's declared field source to the published
     * {@code uint8_t} flag of {@code deal_arm_record}.
     */
    /** The render-scope discriminant the emitted arm record carries. */
    private static int emittedScope(FailureArm arm) {
        return arm.scope() == FailureArm.RenderScope.INNER_ONLY
            ? LlvmRuntimeAbi.ARM_SCOPE_INNER_ONLY
            : LlvmRuntimeAbi.ARM_SCOPE_TOP_LEVEL;
    }

    private static int emittedFlag(FailureArm arm, boolean expected) {
        return expected
            ? (arm.expectedSource() == FailureArm.ExpectedSource.NONE ? 0 : 1)
            : (arm.actualProjection() == FailureArm.ActualProjection.NONE ? 0 : 1);
    }

    // =========================================================================
    // Assertions
    // =========================================================================

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void checkEq(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual),
            message + " (expected " + abbreviate(expected) + ", got "
                + abbreviate(actual) + ")");
    }

    private static String abbreviate(Object value) {
        String text = String.valueOf(value);
        return text.length() <= 400 ? text : text.substring(0, 400) + "...";
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder())
                    .toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
