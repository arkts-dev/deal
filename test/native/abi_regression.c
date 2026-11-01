/* Public host carriers exercised by LlvmHostAbiTest's executed DEAL checks. */
#include "host/probe.h"
#include <string.h>

#define REQUIRE(call) do { if ((call) != DEAL_OK) { \
    result.failed = 1; result.failure.kind = DEAL_FAILURE_HOST_TEXT; \
    result.failure.code = "TEST_STATUS"; result.failure.message = #call; \
    return result; } } while (0)
#define EXPECT(condition) do { if (!(condition)) { \
    result.failed = 1; result.failure.kind = DEAL_FAILURE_HOST_TEXT; \
    result.failure.code = "TEST_VALUE"; result.failure.message = #condition; \
    return result; } } while (0)

static deal_host_result aliases(const deal_value *args, size_t argc) {
    (void)argc;
    deal_host_result result = {0};
    deal_value child, absent, number, read;
    int present = 0;
    int64_t length = -1;
    REQUIRE(deal_table_new(&child));
    REQUIRE(deal_absent(&absent));
    REQUIRE(deal_number(7, &number));
    REQUIRE(deal_table_set(child, "self", 4, child));
    REQUIRE(deal_table_set(child, "value", 5, absent));
    /* A failed conversion must not cache a partial shell or grow the target. */
    EXPECT(deal_array_set(args[0], 0, child) == DEAL_STATUS_KIND);
    REQUIRE(deal_array_length(args[0], &length));
    EXPECT(length == 0);
    REQUIRE(deal_table_set(child, "value", 5, number));
    REQUIRE(deal_array_set(args[0], 0, child));
    REQUIRE(deal_array_set(args[0], 1, child));
    REQUIRE(deal_table_set(args[1], "a", 1, child));
    REQUIRE(deal_table_set(args[1], "b", 1, child));
    /* Host edits after publication update the same managed identity, but do
     * not rewrite boxed members with managed-view carriers. */
    REQUIRE(deal_table_set(child, "later", 5, number));
    REQUIRE(deal_table_set(child, "deleted", 7, number));
    REQUIRE(deal_table_delete(child, "deleted", 7));
    REQUIRE(deal_table_get(child, "self", 4, &read));
    EXPECT(read.slot == child.slot && read.generation == child.generation);
    EXPECT(deal_table_set(child, "value", 5, absent) == DEAL_STATUS_KIND);
    REQUIRE(deal_table_has(child, "deleted", 7, &present));
    EXPECT(present == 0);
    REQUIRE(deal_table_set(args[1], "self", 4, args[1]));
    result.value = child;
    return result;
}

static deal_host_result error_fields(const deal_value *args, size_t argc) {
    (void)argc;
    deal_host_result result = {0};
    deal_value text, borrowed, wrong, absent, invalid;
    const unsigned char surrogate[] = {0xed, 0xa0, 0x80};
    REQUIRE(deal_string_new("B", 1, &text));
    REQUIRE(deal_field_set(args[0], "code", text));
    REQUIRE(deal_field_set(args[0], "message", text));
    REQUIRE(deal_field_get(args[0], "message", &borrowed));
    REQUIRE(deal_field_set(args[0], "code", borrowed));
    REQUIRE(deal_number(3, &wrong));
    REQUIRE(deal_absent(&absent));
    REQUIRE(deal_string_new(surrogate, sizeof surrogate, &invalid));
    EXPECT(deal_field_set(args[0], "code", wrong) == DEAL_STATUS_KIND);
    EXPECT(deal_field_set(args[0], "message", absent) == DEAL_STATUS_KIND);
    EXPECT(deal_field_set(args[0], "message", invalid) == DEAL_STATUS_KIND);
    EXPECT(deal_field_set(args[0], "other", text) == DEAL_STATUS_UNKNOWN_FIELD);
    result.value = args[0];
    return result;
}

static deal_host_result scalar_same(const deal_value *args, size_t argc) {
    (void)argc;
    deal_host_result result = {0};
    deal_value a, b;
    int same = -1;
    REQUIRE(deal_number(3, &a));
    REQUIRE(deal_number(3, &b));
    REQUIRE(deal_same(a, b, &same));
    EXPECT(same == 1);
    REQUIRE(deal_number(4, &b));
    REQUIRE(deal_same(a, b, &same));
    EXPECT(same == 0);
    REQUIRE(deal_number(-0.0, &a));
    REQUIRE(deal_same(a, args[2], &same));
    EXPECT(same == 1);
    REQUIRE(deal_same(args[1], args[1], &same));
    EXPECT(same == 0);
    REQUIRE(deal_string_new("x", 1, &a));
    REQUIRE(deal_string_new("x", 1, &b));
    REQUIRE(deal_same(a, b, &same));
    EXPECT(same == 1);
    REQUIRE(deal_same(a, args[0], &same));
    EXPECT(same == 1);
    REQUIRE(deal_string_new("y", 1, &b));
    REQUIRE(deal_same(a, b, &same));
    EXPECT(same == 0);
    REQUIRE(deal_string_new("", 0, &a));
    REQUIRE(deal_string_new("", 0, &b));
    REQUIRE(deal_same(a, b, &same));
    EXPECT(same == 1);
    REQUIRE(deal_boolean(1, &a));
    REQUIRE(deal_boolean(7, &b));
    REQUIRE(deal_same(a, b, &same));
    EXPECT(same == 1);
    REQUIRE(deal_boolean(0, &b));
    REQUIRE(deal_same(a, b, &same));
    EXPECT(same == 0);
    REQUIRE(deal_null(&a));
    REQUIRE(deal_null(&b));
    REQUIRE(deal_same(a, b, &same));
    EXPECT(same == 1);
    REQUIRE(deal_undefined(&b));
    REQUIRE(deal_same(a, b, &same));
    EXPECT(same == 0);
    REQUIRE(deal_undefined(&a));
    REQUIRE(deal_same(a, b, &same));
    EXPECT(same == 1);
    REQUIRE(deal_table_new(&a));
    REQUIRE(deal_table_new(&b));
    REQUIRE(deal_same(a, b, &same));
    EXPECT(same == 0);
    REQUIRE(deal_same(a, a, &same));
    EXPECT(same == 1);
    REQUIRE(deal_boolean(1, &result.value));
    return result;
}

static const deal_host_export exports[] = {
    {"aliases", "([unknown],record)->unknown", DEAL_HOST_EXPORT_FUNCTION,
     0, aliases, NULL, NULL},
    {"errorFields", "(Error)->Error", DEAL_HOST_EXPORT_FUNCTION,
     0, error_fields, NULL, NULL},
    {"scalarSame", "(string,number,number)->boolean", DEAL_HOST_EXPORT_FUNCTION,
     0, scalar_same, NULL, NULL}
};
const deal_host_module deal_host_module_probe = {
    DEAL_HOST_SPECIFIER_PROBE, exports, sizeof exports / sizeof exports[0],
    NULL, 0, NULL
};
