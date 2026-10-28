/*
 * deal/native/std.c — the `std` service group implementation.
 *
 * The closed 18-operation dispatch of `std.h` (`llvm-native-runtime-value-model`
 * R7, R8, R10; `StdlibInvoke`). Every operation is enumerated from the merged
 * v1.5 `StdlibFunctionId` registry, its parameter and result descriptors are the
 * declared `std/` signatures, and every crossing runs the delivered
 * descriptor-driven check, so this unit authors no failure text and no second
 * check authority.
 *
 * Math without libm. The pinned D4 flag list links no `-lm`, so the four `math`
 * operations are computed inside this unit: `absNumber` clears the sign bit,
 * `floor`/`ceil` truncate the significand through the binary64 bit pattern and
 * correct one step, and `sqrt` uses the restoring integer square root on the
 * normalized 53-bit significand with the exact remainder as its rounding sticky
 * bit, which is the correctly rounded (round-to-nearest, ties-to-even) IEEE 754
 * result for every finite input.
 */
#include "deal/native/std.h"

#include <math.h>
#include <string.h>

#include "deal/native/container.h"
#include "deal/native/descriptor.h"
#include "deal/native/error.h"
#include "deal/native/json.h"
#include "deal/native/memory.h"
#include "deal/native/platform.h"
#include "deal/native/text.h"
#include "deal/native/value.h"

/* Holds one freshly created managed object for the invocation (M5). */
static void deal_std_hold(void *referent) {
    (void)deal_root_scope_hold(referent);
}

/* =========================================================================
 * The closed operation table (R7)
 * ========================================================================= */

static const char *const deal_std_parameters_string[1] = {"string"};
static const char *const deal_std_parameters_string_string[2] =
    {"string", "string"};
static const char *const deal_std_parameters_string_string_string[3] =
    {"string", "string", "string"};
static const char *const deal_std_parameters_substring[3] =
    {"string", "number", "number"};
static const char *const deal_std_parameters_record[1] = {"record"};
static const char *const deal_std_parameters_unknown[1] = {"unknown"};
static const char *const deal_std_parameters_number[1] = {"number"};

/* One operation's declared crossing: its parameter descriptors in declared
 * order and its declared result descriptor. A result-only `void` operation
 * carries no result descriptor at all: the result protocol projects `undefined`
 * and no data-type descriptor exists for it (R4, C4). */
typedef struct {
    const char *const *parameters;
    size_t parameter_count;
    const char *result;
} deal_std_operation;

/* The closed operation table, indexed by the `StdlibFunctionId` ordinal. */
static const deal_std_operation deal_std_operations[DEAL_STDLIB_OPERATION_COUNT] = {
    { deal_std_parameters_string, 1u, NULL },                /* CONSOLE_LOG */
    { deal_std_parameters_string, 1u, NULL },                /* CONSOLE_ERROR */
    { deal_std_parameters_string, 1u, "number" },            /* STRING_LENGTH */
    { deal_std_parameters_substring, 3u, "string" },         /* STRING_SUBSTRING */
    { deal_std_parameters_string_string, 2u, "boolean" },    /* STRING_CONTAINS */
    { deal_std_parameters_string_string, 2u, "boolean" },    /* STRING_STARTS_WITH */
    { deal_std_parameters_string_string, 2u, "boolean" },    /* STRING_ENDS_WITH */
    { deal_std_parameters_string_string_string, 3u, "string" },/* STRING_REPLACE */
    { deal_std_parameters_string_string, 2u, "[string]" },   /* STRING_SPLIT */
    { deal_std_parameters_string, 1u, "string" },            /* STRING_TRIM */
    { deal_std_parameters_record, 1u, "[string]" },          /* TABLE_KEYS */
    { deal_std_parameters_string, 1u, "record" },            /* JSON_PARSE */
    { deal_std_parameters_unknown, 1u, "!string" },          /* JSON_STRINGIFY */
    { deal_std_parameters_number, 1u, "number" },            /* MATH_FLOOR */
    { deal_std_parameters_number, 1u, "number" },            /* MATH_CEIL */
    { deal_std_parameters_number, 1u, "number" },            /* MATH_SQRT */
    { deal_std_parameters_number, 1u, "number" },            /* MATH_ABS_NUMBER */
    { NULL, 0u, "number" }                                   /* TIME_NOW_MILLIS */
};

/* =========================================================================
 * The four math operations (R7), computed without libm
 * ========================================================================= */

/* The IEEE absolute value: the sign bit cleared, so `-0.0` is `+0.0` and a NaN
 * keeps a NaN payload of the cleared sign. */
static double deal_std_abs(double value) {
    union { double number; uint64_t bits; } representation;
    representation.number = value;
    representation.bits &= UINT64_C(0x7FFFFFFFFFFFFFFF);
    return representation.number;
}

/* The truncation toward zero of one value whose magnitude is below 2^52. */
static double deal_std_truncate(double value) {
    union { double number; uint64_t bits; } representation;
    representation.number = value;
    int exponent = (int)((representation.bits >> 52) & UINT64_C(0x7FF)) - 1023;
    if (exponent < 0) {
        representation.bits &= UINT64_C(0x8000000000000000);
        return representation.number;
    }
    uint64_t fractional = (UINT64_C(1) << (52 - exponent)) - 1u;
    representation.bits &= ~fractional;
    return representation.number;
}

/* 1 when `value` is finite and integral (its own binary64 value has no
 * fractional part), which is exactly the `substring` bounds rule (R8). Every
 * binary64 value of magnitude at least 2^52 is integral, so only values below
 * that magnitude need the fractional-part test; a NaN or an infinity is never
 * integral. */
static int deal_std_integral(double value) {
    if (!(deal_std_abs(value) <= 1.7976931348623157e308)) {
        return 0;
    }
    return deal_std_abs(value) >= 4503599627370496.0
        || deal_std_truncate(value) == value;
}

static double deal_std_floor(double value) {
    if (!(value == value) || deal_std_abs(value) > 1.7976931348623157e308) {
        return value;
    }
    if (value == 0.0 || deal_std_abs(value) >= 4503599627370496.0) {
        return value;
    }
    double truncated = deal_std_truncate(value);
    if (value < 0.0 && truncated != value) {
        truncated -= 1.0;
    }
    return truncated;
}

static double deal_std_ceil(double value) {
    if (!(value == value) || deal_std_abs(value) > 1.7976931348623157e308) {
        return value;
    }
    if (value == 0.0 || deal_std_abs(value) >= 4503599627370496.0) {
        return value;
    }
    double truncated = deal_std_truncate(value);
    if (value > 0.0 && truncated != value) {
        truncated += 1.0;
    }
    return truncated;
}

/*
 * The correctly rounded square root of one binary64 value (IEEE 754
 * round-to-nearest, ties-to-even). The magnitude is decomposed into its integer
 * significand and exponent, normalized to 53 significant bits with an even
 * exponent, and the integer part of `sqrt(significand * 2^56)` is extracted bit
 * pair by bit pair by the restoring square-root algorithm. The 55-bit root is
 * rounded to the 53-bit significand with the root's own two discarded bits as
 * the guard and round information and the exact remainder as the sticky bit.
 * Every positive finite result is normal, so the assembled significand and
 * exponent need no subnormal step.
 */
static double deal_std_sqrt(double value) {
    if (!(value == value) || value == 0.0) {
        return value;
    }
    if (value < 0.0) {
        union { uint64_t bits; double number; } nan_value;
        nan_value.bits = UINT64_C(0x7FF8000000000000);
        return nan_value.number;
    }
    if (value > 1.7976931348623157e308) {
        return value;            /* +Infinity */
    }
    union { double number; uint64_t bits; } representation;
    representation.number = value;
    uint64_t significand = representation.bits & UINT64_C(0x000FFFFFFFFFFFFF);
    int exponent = (int)((representation.bits >> 52) & UINT64_C(0x7FF));
    int e;
    if (exponent == 0) {
        e = -1074;
    } else {
        significand |= UINT64_C(0x0010000000000000);
        e = exponent - 1075;
    }
    while (significand < UINT64_C(0x0010000000000000)) {
        significand <<= 1;
        e -= 1;
    }
    if (e % 2 != 0) {
        significand <<= 1;
        e -= 1;
    }
    uint64_t root = 0u;
    uint64_t remainder = 0u;
    for (int pair = 54; pair >= 0; pair -= 1) {
        unsigned digits = 0u;
        int low = 2 * pair;
        if (low >= 56) {
            digits = (unsigned)((significand >> (uint64_t)(low - 56)) & 0x3u);
        }
        remainder = (remainder << 2) | digits;
        root <<= 1;
        uint64_t trial = (root << 1) | 1u;
        if (remainder >= trial) {
            remainder -= trial;
            root |= 1u;
        }
    }
    uint64_t guard = (root >> 1) & 1u;
    uint64_t sticky = ((root & 1u) != 0u || remainder != 0u) ? 1u : 0u;
    uint64_t rounded = root >> 2;
    if (guard != 0u && (sticky != 0u || (rounded & 1u) != 0u)) {
        rounded += 1u;
    }
    int result_exponent = e / 2 - 26;
    if (rounded == UINT64_C(0x0020000000000000)) {
        rounded >>= 1;
        result_exponent += 1;
    }
    uint64_t biased = (uint64_t)(result_exponent + 52 + 1023);
    union { double number; uint64_t bits; } result;
    result.bits = (biased << 52) | (rounded & UINT64_C(0x000FFFFFFFFFFFFF));
    return result.number;
}

/* =========================================================================
 * The string operations (R8), over Unicode scalar values
 * ========================================================================= */

/* The first byte offset at or after `from` where `needle` occurs, or SIZE_MAX.
 * Valid UTF-8 is self-synchronizing: a continuation byte never equals a lead
 * byte and a lead byte never occurs inside another scalar's encoding, so a
 * literal byte search is exactly the literal scalar-subsequence search. */
static size_t deal_std_find(const unsigned char *haystack, size_t haystack_length,
                            const unsigned char *needle, size_t needle_length,
                            size_t from) {
    if (needle_length == 0u) {
        return from <= haystack_length ? from : SIZE_MAX;
    }
    if (needle_length > haystack_length) {
        return SIZE_MAX;
    }
    for (size_t index = from; index + needle_length <= haystack_length;
            index += 1u) {
        if (memcmp(haystack + index, needle, needle_length) == 0) {
            return index;
        }
    }
    return SIZE_MAX;
}

/* 1 when `scalar` is in the closed trim set U+0009..U+000D and U+0020. */
static int deal_std_trim_scalar(unsigned char byte) {
    return (byte >= 0x09u && byte <= 0x0Du) || byte == 0x20u;
}

/* One `[string]` array (the runtime's reference element layout with the
 * `string` element descriptor). */
static deal_array *deal_std_string_array(void) {
    return deal_container_array_new_typed(DEAL_LAYOUT_ELEMENT_REFERENCE,
        deal_descriptor_reference(deal_descriptor_intern("string")), 0u);
}

/* Appends one string element to a `[string]` array. */
static void deal_std_string_array_append(deal_array *array, deal_string *value) {
    deal_container_array_append(array, &value);
}

/* =========================================================================
 * The dispatch (R7, `StdlibInvoke`)
 * ========================================================================= */

/* One operation's already checked string argument. */
static deal_string *deal_std_string_argument(const deal_check_input *argument) {
    if (argument->presentation != DEAL_PRESENT_DATUM
            || argument->datum.tag != DEAL_DATA_STRING
            || argument->datum.reference == NULL) {
        deal_platform_fault("std: checked argument is not a string");
    }
    return (deal_string *)argument->datum.reference;
}

/* One operation's already checked number argument. */
static double deal_std_number_argument(const deal_check_input *argument) {
    if (argument->presentation != DEAL_PRESENT_DATUM
            || argument->datum.tag != DEAL_DATA_NUMBER) {
        deal_platform_fault("std: checked argument is not a number");
    }
    return argument->datum.number;
}

/* One string operation's result datum. */
static deal_data deal_std_string_result(deal_string *text) {
    deal_std_hold(text);
    return deal_data_of_reference(DEAL_DATA_STRING, text);
}

/* `std/console`: the argument's exact bytes plus one newline, then the
 * result-only `void` project of `undefined`. */
static deal_data deal_std_console(const deal_check_input *argument, int to_stderr) {
    deal_string *text = deal_std_string_argument(argument);
    const unsigned char *bytes = deal_text_bytes(text);
    size_t length = deal_text_byte_length(text);
    if (to_stderr) {
        deal_platform_write_stderr(bytes, length);
        deal_platform_write_stderr("\n", 1u);
    } else {
        deal_platform_write_stdout(bytes, length);
        deal_platform_write_stdout("\n", 1u);
    }
    return deal_data_of_undefined();
}

/* `std/string.substring`: finite integral bounds first (the SUBSTRING_BOUNDS
 * arm), then clamp to `0..scalar count` and slice by scalar positions. */
static deal_outcome_data deal_std_substring(deal_string *text, double start,
                                            double end, const deal_origin *origin) {
    deal_outcome_data outcome;
    memset(&outcome, 0, sizeof(outcome));
    if (!deal_std_integral(start) || !deal_std_integral(end)) {
        outcome.error = deal_error_materialize(DEAL_ARM_ID_SUBSTRING_BOUNDS,
            NULL, 0u, NULL, NULL, origin, NULL);
        return outcome;
    }
    size_t scalar_length = deal_text_scalar_length(text);
    double limit = (double)scalar_length;
    double lo_value = start < 0.0 ? 0.0 : (start > limit ? limit : start);
    double hi_value = end < 0.0 ? 0.0 : (end > limit ? limit : end);
    size_t lo = (size_t)lo_value;
    size_t hi = (size_t)hi_value;
    if (lo >= hi) {
        deal_string *empty = deal_text_new(NULL, 0u);
        outcome.value = deal_std_string_result(empty);
        return outcome;
    }
    const unsigned char *bytes = deal_text_bytes(text);
    size_t lo_byte = deal_text_scalar_offset(text, lo);
    size_t hi_byte = deal_text_scalar_offset(text, hi);
    deal_string *slice = deal_text_new(bytes + lo_byte, hi_byte - lo_byte);
    outcome.value = deal_std_string_result(slice);
    return outcome;
}

/* `std/string.replace`: every non-overlapping literal occurrence, left to
 * right; an empty search returns the input unchanged. */
static deal_data deal_std_replace(deal_string *text, deal_string *search,
                                  deal_string *replacement) {
    const unsigned char *haystack = deal_text_bytes(text);
    size_t haystack_length = deal_text_byte_length(text);
    const unsigned char *needle = deal_text_bytes(search);
    size_t needle_length = deal_text_byte_length(search);
    if (needle_length == 0u) {
        return deal_data_of_reference(DEAL_DATA_STRING, text);
    }
    const unsigned char *substitute = deal_text_bytes(replacement);
    size_t substitute_length = deal_text_byte_length(replacement);
    /* The output is one managed string, so the pieces are assembled in platform
     * scratch first and copied once. */
    size_t capacity = haystack_length + 1u;
    unsigned char *scratch = deal_platform_allocate(capacity);
    if (scratch == NULL) {
        deal_platform_fault("std: replace scratch allocation failed");
    }
    size_t length = 0u;
    size_t cursor = 0u;
    while (1) {
        size_t found = deal_std_find(haystack, haystack_length, needle,
                                     needle_length, cursor);
        if (found == SIZE_MAX) {
            break;
        }
        size_t piece = found - cursor;
        if (length + piece + substitute_length > capacity) {
            size_t grown_capacity = capacity;
            while (grown_capacity < length + piece + substitute_length) {
                if (grown_capacity > SIZE_MAX / 2u) {
                    deal_platform_fault("std: replace size overflow");
                }
                grown_capacity *= 2u;
            }
            unsigned char *grown = deal_platform_allocate(grown_capacity);
            if (grown == NULL) {
                deal_platform_fault("std: replace scratch allocation failed");
            }
            memcpy(grown, scratch, length);
            deal_platform_release(scratch);
            scratch = grown;
            capacity = grown_capacity;
        }
        if (piece != 0u) {
            memcpy(scratch + length, haystack + cursor, piece);
            length += piece;
        }
        if (substitute_length != 0u) {
            memcpy(scratch + length, substitute, substitute_length);
            length += substitute_length;
        }
        cursor = found + needle_length;
    }
    size_t tail = haystack_length - cursor;
    if (length + tail > capacity) {
        size_t grown_capacity = capacity;
        while (grown_capacity < length + tail) {
            if (grown_capacity > SIZE_MAX / 2u) {
                deal_platform_fault("std: replace size overflow");
            }
            grown_capacity *= 2u;
        }
        unsigned char *grown = deal_platform_allocate(grown_capacity);
        if (grown == NULL) {
            deal_platform_fault("std: replace scratch allocation failed");
        }
        memcpy(grown, scratch, length);
        deal_platform_release(scratch);
        scratch = grown;
        capacity = grown_capacity;
    }
    if (tail != 0u) {
        memcpy(scratch + length, haystack + cursor, tail);
        length += tail;
    }
    deal_string *result = deal_text_new(scratch, length);
    deal_platform_release(scratch);
    return deal_std_string_result(result);
}

/* `std/string.split`: an empty input is `[]`, an empty separator splits into
 * one element per scalar, everything else keeps the empty leading, internal and
 * trailing parts. */
static deal_data deal_std_split(deal_string *text, deal_string *separator) {
    deal_array *array = deal_std_string_array();
    deal_std_hold(array);
    size_t scalar_length = deal_text_scalar_length(text);
    const unsigned char *bytes = deal_text_bytes(text);
    size_t byte_length = deal_text_byte_length(text);
    size_t separator_length = deal_text_byte_length(separator);
    if (scalar_length == 0u) {
        return deal_data_of_reference(DEAL_DATA_ARRAY, array);
    }
    if (separator_length == 0u) {
        for (size_t index = 0; index < scalar_length; index += 1u) {
            size_t from = deal_text_scalar_offset(text, index);
            size_t to = deal_text_scalar_offset(text, index + 1u);
            deal_string *element = deal_text_new(bytes + from, to - from);
            deal_std_hold(element);
            deal_std_string_array_append(array, element);
        }
        return deal_data_of_reference(DEAL_DATA_ARRAY, array);
    }
    const unsigned char *needle = deal_text_bytes(separator);
    size_t cursor = 0u;
    while (1) {
        size_t found = deal_std_find(bytes, byte_length, needle,
                                     separator_length, cursor);
        if (found == SIZE_MAX) {
            break;
        }
        deal_string *element = deal_text_new(bytes + cursor, found - cursor);
        deal_std_hold(element);
        deal_std_string_array_append(array, element);
        cursor = found + separator_length;
    }
    deal_string *tail = deal_text_new(bytes + cursor, byte_length - cursor);
    deal_std_hold(tail);
    deal_std_string_array_append(array, tail);
    return deal_data_of_reference(DEAL_DATA_ARRAY, array);
}

/* `std/string.trim`: the closed set U+0009..U+000D and U+0020 removed from both
 * ends, never from the interior. */
static deal_data deal_std_trim(deal_string *text) {
    const unsigned char *bytes = deal_text_bytes(text);
    size_t length = deal_text_byte_length(text);
    size_t first = 0u;
    while (first < length && deal_std_trim_scalar(bytes[first])) {
        first += 1u;
    }
    size_t last = length;
    while (last > first && deal_std_trim_scalar(bytes[last - 1u])) {
        last -= 1u;
    }
    deal_string *trimmed = deal_text_new(bytes + first, last - first);
    return deal_std_string_result(trimmed);
}

/* `std/table.keys`: the record's present keys in first-insertion order. */
static deal_data deal_std_table_keys(deal_table *table) {
    deal_array *array = deal_std_string_array();
    deal_std_hold(array);
    size_t count = deal_container_table_count(table);
    for (size_t index = 0; index < count; index += 1u) {
        deal_std_string_array_append(array,
                                     deal_container_table_key_at(table, index));
    }
    return deal_data_of_reference(DEAL_DATA_ARRAY, array);
}

/* `std/math.sqrt`: publish the IEEE result, including NaN for negatives. */
static deal_data deal_std_sqrt_result(double value, const deal_origin *origin,
                                      deal_error **error) {
    (void)origin;
    (void)error;
    return deal_data_of_number(deal_std_sqrt(value));
}

/* The operation's own evaluation, after every parameter crossing passed. */
static deal_data deal_std_evaluate(uint32_t operation_id,
                                   const deal_check_input *arguments,
                                   const deal_origin *origin,
                                   deal_error **error) {
    switch (operation_id) {
    case DEAL_STDLIB_CONSOLE_LOG:
        return deal_std_console(&arguments[0], 0);
    case DEAL_STDLIB_CONSOLE_ERROR:
        return deal_std_console(&arguments[0], 1);
    case DEAL_STDLIB_STRING_LENGTH:
        return deal_data_of_number((double)deal_text_scalar_length(
            deal_std_string_argument(&arguments[0])));
    case DEAL_STDLIB_STRING_SUBSTRING: {
        deal_outcome_data outcome = deal_std_substring(
            deal_std_string_argument(&arguments[0]),
            deal_std_number_argument(&arguments[1]),
            deal_std_number_argument(&arguments[2]), origin);
        *error = outcome.error;
        return outcome.value;
    }
    case DEAL_STDLIB_STRING_CONTAINS: {
        deal_string *text = deal_std_string_argument(&arguments[0]);
        deal_string *part = deal_std_string_argument(&arguments[1]);
        size_t found = deal_std_find(deal_text_bytes(text),
            deal_text_byte_length(text), deal_text_bytes(part),
            deal_text_byte_length(part), 0u);
        return deal_data_of_boolean(found != SIZE_MAX);
    }
    case DEAL_STDLIB_STRING_STARTS_WITH: {
        deal_string *text = deal_std_string_argument(&arguments[0]);
        deal_string *part = deal_std_string_argument(&arguments[1]);
        size_t text_length = deal_text_byte_length(text);
        size_t part_length = deal_text_byte_length(part);
        return deal_data_of_boolean(part_length <= text_length
            && memcmp(deal_text_bytes(text), deal_text_bytes(part),
                      part_length) == 0);
    }
    case DEAL_STDLIB_STRING_ENDS_WITH: {
        deal_string *text = deal_std_string_argument(&arguments[0]);
        deal_string *part = deal_std_string_argument(&arguments[1]);
        size_t text_length = deal_text_byte_length(text);
        size_t part_length = deal_text_byte_length(part);
        return deal_data_of_boolean(part_length <= text_length
            && memcmp(deal_text_bytes(text) + (text_length - part_length),
                      deal_text_bytes(part), part_length) == 0);
    }
    case DEAL_STDLIB_STRING_REPLACE:
        return deal_std_replace(deal_std_string_argument(&arguments[0]),
                                deal_std_string_argument(&arguments[1]),
                                deal_std_string_argument(&arguments[2]));
    case DEAL_STDLIB_STRING_SPLIT:
        return deal_std_split(deal_std_string_argument(&arguments[0]),
                              deal_std_string_argument(&arguments[1]));
    case DEAL_STDLIB_STRING_TRIM:
        return deal_std_trim(deal_std_string_argument(&arguments[0]));
    case DEAL_STDLIB_TABLE_KEYS:
        return deal_std_table_keys((deal_table *)arguments[0].datum.reference);
    case DEAL_STDLIB_JSON_PARSE: {
        deal_outcome_data parsed = deal_json_parse(
            deal_std_string_argument(&arguments[0]), origin);
        *error = parsed.error;
        return parsed.value;
    }
    case DEAL_STDLIB_JSON_STRINGIFY: {
        deal_outcome_data encoded = deal_json_stringify(arguments[0].datum, origin);
        *error = encoded.error;
        return encoded.value;
    }
    case DEAL_STDLIB_MATH_FLOOR:
        return deal_data_of_number(deal_std_floor(
            deal_std_number_argument(&arguments[0])));
    case DEAL_STDLIB_MATH_CEIL:
        return deal_data_of_number(deal_std_ceil(
            deal_std_number_argument(&arguments[0])));
    case DEAL_STDLIB_MATH_SQRT:
        return deal_std_sqrt_result(deal_std_number_argument(&arguments[0]),
                                    origin, error);
    case DEAL_STDLIB_MATH_ABS_NUMBER:
        return deal_data_of_number(deal_std_abs(
            deal_std_number_argument(&arguments[0])));
    case DEAL_STDLIB_TIME_NOW_MILLIS:
        return deal_data_of_number(deal_std_now_millis());
    default:
        break;
    }
    deal_platform_fault("std: operation id outside the closed set");
}

double deal_std_now_millis(void) {
    /* Binary64 epoch milliseconds from the platform realtime clock; the
     * nanosecond reading is an exact integer in a binary64 up to the epoch's
     * own magnitude, so the division introduces the only rounding. */
    return (double)deal_platform_realtime_ns() / 1000000.0;
}

deal_outcome_data deal_std_call(uint32_t operation_id,
                                const deal_check_input *arguments,
                                uint32_t argument_count,
                                const deal_origin *origin) {
    deal_outcome_data outcome;
    memset(&outcome, 0, sizeof(outcome));
    if (operation_id >= DEAL_STDLIB_OPERATION_COUNT) {
        deal_platform_fault("std: operation id outside the closed set");
    }
    if (origin == NULL) {
        deal_platform_fault("std: missing call origin");
    }
    const deal_std_operation *operation = &deal_std_operations[operation_id];
    if (argument_count != operation->parameter_count) {
        deal_platform_fault("std: argument count differs from the declared arity");
    }
    if (argument_count != 0u && arguments == NULL) {
        deal_platform_fault("std: missing argument array");
    }
    deal_root_scope_push();
    /* The STDLIB_PARAMETER crossings, left to right, through the delivered
     * descriptor-driven check. */
    deal_check_context context;
    context.origin = origin;
    context.parameter_index = 0;
    for (uint32_t index = 0; index < argument_count; index += 1u) {
        deal_descriptor *expected =
            deal_descriptor_intern(operation->parameters[index]);
        deal_outcome checked = deal_check_value(expected, arguments[index],
            DEAL_BOUNDARY_TYPE_DESCRIPTOR, &context);
        if (checked.error != NULL) {
            outcome.error = checked.error;
            deal_root_scope_pop();
            return outcome;
        }
    }
    deal_error *error = NULL;
    deal_data result = deal_std_evaluate(operation_id, arguments, origin, &error);
    if (error != NULL) {
        outcome.error = error;
        deal_root_scope_pop();
        return outcome;
    }
    /* The STDLIB_RETURN crossing: a result-only `void` completion carries the
     * result protocol's `undefined` (never a data-type descriptor), and every
     * other operation's result passes its declared descriptor. */
    if (operation->result == NULL) {
        if (result.tag != DEAL_DATA_UNDEFINED) {
            deal_platform_fault("std: a result-only operation published a value");
        }
        outcome.value = result;
        deal_root_scope_pop();
        return outcome;
    }
    deal_check_input input;
    memset(&input, 0, sizeof(input));
    input.presentation = DEAL_PRESENT_DATUM;
    input.datum = result;
    deal_outcome checked = deal_check_value(
        deal_descriptor_intern(operation->result), input,
        DEAL_BOUNDARY_TYPE_DESCRIPTOR, &context);
    if (checked.error != NULL) {
        outcome.error = checked.error;
        deal_root_scope_pop();
        return outcome;
    }
    outcome.value = result;
    deal_root_scope_pop();
    return outcome;
}
