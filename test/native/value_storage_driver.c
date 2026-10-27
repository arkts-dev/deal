/*
 * test/native/value_storage_driver.c — the C3 value storage acceptance.
 *
 * A strict-C11 consumer of the published runtime: the persistent tagged datum of
 * an `unknown` position, the distinct null/undefined data values, the presence
 * metadata of an optional slot, the three independent scalar-nullish tags, the
 * `===` relation, typed array storage with its recorded element layout and
 * insertion-ordered record storage. Every state the value model distinguishes is
 * asserted here: absent versus present-undefined versus present-null versus
 * present-value, ordered key creation, exact element counts, append-at-length,
 * the checked discrete-position conversion (finite, integral, bounds, negative
 * zero) and the unchanged storage of a rejected write. A reference element slot
 * may hold a nullish singleton, so the element walk and the element barrier are
 * tag-aware; an array's own element-block allocation roots its intermediate
 * through one scoped root that leaves the caller's scope and holds untouched.
 *
 * The unit defines the process entry (driver shape): the runtime minus
 * `deal/native/main.c` is linked with it.
 */
#include "deal/native/container.h"
#include "deal/native/deal_rt.h"
#include "deal/native/error.h"
#include "deal/native/memory.h"
#include "deal/native/text.h"
#include "deal/native/value.h"

#include <stdio.h>
#include <string.h>

/* Three emitted-style layout records the driver installs before allocating:
 * a scalar element layout, a two-reference element layout and a one-reference
 * element layout for a nullable reference element type. */
#define TEST_LAYOUT_NUMBER    1024u
#define TEST_LAYOUT_PAIR      1025u
#define TEST_LAYOUT_REFERENCE 1026u

static const uint32_t test_pair_offsets[] = { 0u, 8u };
static const uint32_t test_reference_offsets[] = { 0u };

static const deal_layout test_layouts[] = {
    { TEST_LAYOUT_NUMBER, 0u, 8u, NULL, 0u },
    { TEST_LAYOUT_PAIR, 0u, 16u, test_pair_offsets, 2u },
    { TEST_LAYOUT_REFERENCE, 0u, 8u, test_reference_offsets, 1u }
};

static int failures;

/* The visited references of one precise-map walk, in visit order. */
#define COLLECT_LIMIT 16u

static void *collected[COLLECT_LIMIT];
static size_t collected_count;

static void collect_reference(void *referent, void *context) {
    (void)context;
    if (collected_count < COLLECT_LIMIT) {
        collected[collected_count] = referent;
    }
    collected_count += 1u;
}

static void check(int condition, const char *what) {
    if (!condition) {
        printf("FAIL: %s\n", what);
        failures += 1;
    }
}

static deal_string *string_of(const char *text) {
    return deal_text_new(text, strlen(text));
}

/* One object of the installed two-reference layout. */
static void *pair_object(void) {
    return deal_alloc(TEST_LAYOUT_PAIR, 16u);
}

static void check_data_states(void) {
    deal_data undefined = deal_data_of_undefined();
    deal_data null_value = deal_data_of_null();
    deal_data truth = deal_data_of_boolean(1);
    deal_data falsity = deal_data_of_boolean(0);
    deal_data one = deal_data_of_number(1.0);
    deal_string *left = string_of("abc");
    deal_string *right = string_of("abc");
    deal_string *other = string_of("abd");
    deal_data left_text = deal_data_of_reference(DEAL_DATA_STRING, left);
    deal_data right_text = deal_data_of_reference(DEAL_DATA_STRING, right);
    deal_data other_text = deal_data_of_reference(DEAL_DATA_STRING, other);
    void *pair = pair_object();
    deal_data pair_value = deal_data_of_reference(DEAL_DATA_CLASS, pair);

    /* absence is not a data value: null and undefined are distinct data. */
    check(undefined.tag == DEAL_DATA_UNDEFINED, "the undefined datum keeps its tag");
    check(null_value.tag == DEAL_DATA_NULL, "the null datum keeps its tag");
    check(deal_data_same(&undefined, &undefined) == 1,
          "undefined equals itself");
    check(deal_data_same(&null_value, &null_value) == 1, "null equals itself");
    check(deal_data_same(&undefined, &null_value) == 0,
          "undefined and null are distinct data values");
    check(deal_data_is_nullish(&undefined) == 1 && deal_data_is_nullish(&null_value) == 1,
          "both nullish data values are nullish");
    check(deal_data_is_nullish(&one) == 0 && deal_data_is_nullish(&left_text) == 0,
          "a base value is not nullish");
    check(deal_data_referent(&undefined) == NULL && deal_data_referent(&one) == NULL,
          "a non-reference datum names no referent");
    check(deal_data_is_managed(&undefined) == 0 && deal_data_is_managed(&one) == 0
              && deal_data_is_managed(&truth) == 0,
          "scalars and singletons are not managed payloads");

    /* booleans compare by value; numbers use binary64 equality. */
    deal_data another_truth = deal_data_of_boolean(1);
    deal_data another_one = deal_data_of_number(1.0);
    deal_data negative_zero = deal_data_of_number(-0.0);
    deal_data positive_zero = deal_data_of_number(0.0);
    deal_data not_a_number = deal_data_of_number(0.0 / 0.0);
    deal_data another_not_a_number = deal_data_of_number(0.0 / 0.0);
    deal_data infinity = deal_data_of_number(1.0 / 0.0);
    deal_data another_infinity = deal_data_of_number(1.0 / 0.0);
    check(deal_data_same(&truth, &another_truth) == 1,
          "booleans compare by value");
    check(deal_data_same(&truth, &falsity) == 0, "different booleans differ");
    check(deal_data_same(&truth, &one) == 0, "different kinds differ");
    check(deal_data_same(&one, &another_one) == 1, "equal numbers are equal");
    check(deal_data_same(&one, &negative_zero) == 0,
          "one and negative zero differ");
    check(deal_data_same(&negative_zero, &positive_zero) == 1,
          "signed zeros are equal");
    check(deal_data_same(&not_a_number, &another_not_a_number) == 0,
          "NaN differs from itself");
    check(deal_data_same(&infinity, &another_infinity) == 1,
          "equal infinities are equal");

    /* strings compare by Unicode scalar sequence, not by object identity. */
    check(left != right, "two equal strings are distinct immutable objects");
    check(deal_data_same(&left_text, &right_text) == 1,
          "equal scalar sequences are equal strings");
    check(deal_data_same(&left_text, &other_text) == 0,
          "different scalar sequences differ");
    check(deal_data_same(&left_text, &one) == 0, "a string is not a number");
    check(deal_data_is_managed(&left_text) == 1 && deal_data_is_managed(&pair_value) == 1,
          "strings and class instances are managed payloads");
    check(deal_data_referent(&left_text) == left,
          "the string datum preserves its referent identity");

    /* arrays, records, classes, functions and Errors compare by identity. */
    deal_data another_pair = deal_data_of_reference(DEAL_DATA_CLASS,
                                                   pair_object());
    deal_data same_identity_function = deal_data_of_reference(DEAL_DATA_FUNCTION,
                                                              pair);
    deal_error *error = deal_error_create("E9001", "carried", NULL);
    deal_error *another_error = deal_error_create("E9002", "other", NULL);
    deal_data error_datum = deal_data_of_reference(DEAL_DATA_ERROR, error);
    deal_data same_error_datum = deal_data_of_reference(DEAL_DATA_ERROR, error);
    deal_data another_error_datum = deal_data_of_reference(DEAL_DATA_ERROR,
                                                          another_error);
    check(deal_data_same(&pair_value, &pair_value) == 1,
          "a class instance equals itself");
    check(deal_data_same(&pair_value, &another_pair) == 0,
          "distinct class instances differ");
    check(deal_data_same(&pair_value, &same_identity_function) == 0,
          "a retagged identity is a different kind");

    /* an Error is one managed instance and keeps its identity across carries. */
    check(deal_data_referent(&error_datum) == error,
          "the Error datum names its instance");
    check(deal_data_is_managed(&error_datum) == 1,
          "the Error instance is a managed payload");
    check(deal_data_same(&error_datum, &same_error_datum) == 1,
          "the carried Error keeps its identity");
    check(deal_data_same(&error_datum, &another_error_datum) == 0,
          "two Error instances differ");
    check(deal_data_same(&error_datum, &pair_value) == 0,
          "an Error and another class instance differ");
    check(deal_object_layout(error) == DEAL_LAYOUT_ERROR,
          "the Error instance is a managed object of the Error layout");

    /* the datum holds the referent itself, never a crossing handle (R3/M4). */
    {
        deal_data copy = left_text;
        check(copy.reference == left,
              "the persistent datum stores the referent itself");
        check(copy.reserved == 0u, "the reserved word is always zero");
        check(copy.tag == DEAL_DATA_STRING,
              "the datum keeps its kind through a copy");
    }
}

static void check_scalar_nullish_tags(void) {
    deal_data undefined = deal_data_of_undefined();
    deal_data null_value = deal_data_of_null();
    deal_data one = deal_data_of_number(1.0);
    deal_data text = deal_data_of_reference(DEAL_DATA_STRING, string_of("x"));

    check(DEAL_NULLISH_BASE != DEAL_NULLISH_NULL
              && DEAL_NULLISH_NULL != DEAL_NULLISH_UNDEFINED
              && DEAL_NULLISH_BASE != DEAL_NULLISH_UNDEFINED,
          "the three scalar-nullish tags are independent discriminants");
    check(DEAL_NULLISH_BASE == 0, "the base tag is the zero discriminant");
    check(deal_nullish_of_data(&one) == DEAL_NULLISH_BASE,
          "a base number is the base tag");
    check(deal_nullish_of_data(&text) == DEAL_NULLISH_BASE,
          "a base string is the base tag");
    check(deal_nullish_of_data(&null_value) == DEAL_NULLISH_NULL,
          "the null datum is the null tag");
    check(deal_nullish_of_data(&undefined) == DEAL_NULLISH_UNDEFINED,
          "the undefined datum is the undefined tag");
    check(DEAL_PRESENCE_ABSENT != DEAL_PRESENCE_PRESENT,
          "presence metadata distinguishes absence from presence");
    check(DEAL_PRESENCE_ABSENT == 0 && DEAL_PRESENCE_PRESENT == 1,
          "presence metadata is one independent bit");
}

static void check_records(void) {
    deal_table *table = deal_container_table_new();
    deal_table *other = deal_container_table_new();
    deal_string *alpha = string_of("alpha");
    deal_string *beta = string_of("beta");
    deal_string *gamma = string_of("gamma");
    deal_string *missing = string_of("missing");
    deal_table *alias = table;
    deal_data one = deal_data_of_number(1.0);
    deal_data two = deal_data_of_number(2.0);
    deal_data undefined = deal_data_of_undefined();
    deal_data null_value = deal_data_of_null();
    deal_data read;

    check(deal_container_table_count(table) == 0u, "a fresh record is empty");
    check(deal_container_table_key_at(table, 0u) == NULL,
          "an empty record has no key at index zero");
    check(deal_container_table_has(table, alpha) == 0,
          "an absent key is not present");
    read = deal_container_table_read(table, alpha);
    check(read.tag == DEAL_DATA_UNDEFINED,
          "an absent record read projects undefined");
    check(deal_data_is_nullish(&read) == 1 && read.tag != DEAL_DATA_NULL,
          "the absent read is undefined, not null");

    deal_container_table_write(table, alpha, &one);
    deal_container_table_write(table, beta, &undefined);
    deal_container_table_write(table, gamma, &null_value);
    check(deal_container_table_count(table) == 3u,
          "three writes make three present keys");
    check(deal_container_table_has(table, alpha) == 1
              && deal_container_table_has(table, beta) == 1
              && deal_container_table_has(table, gamma) == 1,
          "every written key is present");
    check(deal_container_table_read(table, beta).tag == DEAL_DATA_UNDEFINED,
          "a present undefined stays a present undefined value");
    check(deal_container_table_read(table, gamma).tag == DEAL_DATA_NULL,
          "a present null stays a present null value");
    check(deal_container_table_key_at(table, 0u) == alpha
              && deal_container_table_key_at(table, 1u) == beta
              && deal_container_table_key_at(table, 2u) == gamma,
          "keys keep their creation order");
    check(deal_container_table_key_at(table, 3u) == NULL,
          "the key order ends after the last present key");

    /* a repeated write keeps the first-insertion position. */
    deal_container_table_write(table, alpha, &two);
    check(deal_container_table_count(table) == 3u,
          "overwriting a key adds no member");
    check(deal_container_table_key_at(table, 0u) == alpha,
          "an overwritten key keeps its position");
    check(deal_container_table_read(table, alpha).number == 2.0,
          "an overwritten key answers the new value");

    /* the same scalar sequence under another string object is the same key. */
    deal_string *alpha_again = string_of("alpha");
    check(alpha_again != alpha, "the equal key is a distinct string object");
    check(deal_container_table_has(table, alpha_again) == 1,
          "key identity is the scalar sequence, not the string object");
    check(deal_container_table_count(table) == 3u,
          "an equal key writes the existing member");

    /* deletion removes presence, and an absent read returns to undefined. */
    deal_container_table_delete(table, beta);
    check(deal_container_table_count(table) == 2u,
          "deletion removes one present key");
    check(deal_container_table_has(table, beta) == 0,
          "a deleted key is no longer present");
    check(deal_container_table_read(table, beta).tag == DEAL_DATA_UNDEFINED,
          "a deleted key reads undefined");
    check(deal_container_table_key_at(table, 0u) == alpha
              && deal_container_table_key_at(table, 1u) == gamma,
          "deletion keeps the remaining creation order");
    deal_container_table_delete(table, missing);
    check(deal_container_table_count(table) == 2u,
          "deleting an absent key changes nothing");

    /* records keep observable reference identity. */
    deal_data table_datum = deal_data_of_reference(DEAL_DATA_TABLE, table);
    deal_data alias_datum = deal_data_of_reference(DEAL_DATA_TABLE, alias);
    deal_data other_datum = deal_data_of_reference(DEAL_DATA_TABLE, other);
    check(alias == table && other != table,
          "a record alias is the same object and a second record is not");
    check(deal_data_same(&table_datum, &alias_datum) == 1,
          "the record datum preserves identity through an alias");
    check(deal_data_same(&table_datum, &other_datum) == 0,
          "distinct records differ");
    check(deal_container_table_count(other) == 0u,
          "a second record has its own members");
    check(deal_root_frame_depth() == 0u && deal_root_scope_depth() == 0u,
          "the record operations leave the root depth restored");
}

static void check_arrays(void) {
    deal_array *empty = deal_container_array_new(TEST_LAYOUT_NUMBER, 0u);
    deal_array *array = deal_container_array_new(TEST_LAYOUT_NUMBER, 3u);
    deal_array *alias = array;
    double element = 0.0;
    size_t offset = 99u;

    check(deal_container_array_count(empty) == 0u,
          "an empty array reports the exact count zero");
    check(deal_container_array_element_layout(empty) == TEST_LAYOUT_NUMBER,
          "an empty array keeps its recorded element layout");
    check(deal_container_array_element_layout(array) == TEST_LAYOUT_NUMBER,
          "an array keeps its recorded element layout");
    check(deal_container_array_element_layout(alias) == TEST_LAYOUT_NUMBER,
          "an array alias keeps the recorded element layout");
    check(deal_container_array_count(array) == 3u,
          "an array reports its exact element count");
    check(deal_alloc(TEST_LAYOUT_NUMBER, 8u) != NULL,
          "the element layout allocates a payload of its size");

    /* the checked discrete-position conversion (R2). */
    check(deal_container_position(0.0, 3u, 0, &offset) == 1 && offset == 0u,
          "position zero addresses the first slot");
    check(deal_container_position(-0.0, 3u, 0, &offset) == 1 && offset == 0u,
          "negative zero addresses zero");
    check(deal_container_position(2.0, 3u, 0, &offset) == 1 && offset == 2u,
          "the last read position is length minus one");
    offset = 99u;
    check(deal_container_position(3.0, 3u, 0, &offset) == 0 && offset == 99u,
          "a read at length is rejected without an offset");
    check(deal_container_position(3.0, 3u, 1, &offset) == 1 && offset == 3u,
          "a write at length appends");
    check(deal_container_position(4.0, 3u, 1, &offset) == 0,
          "a write past length is rejected");
    check(deal_container_position(2.5, 3u, 1, &offset) == 0,
          "a fractional position is rejected");
    check(deal_container_position(0.0 / 0.0, 3u, 1, &offset) == 0,
          "NaN is never a position");
    check(deal_container_position(1.0 / 0.0, 3u, 1, &offset) == 0,
          "positive infinity is never a position");
    check(deal_container_position(-1.0 / 0.0, 3u, 0, &offset) == 0,
          "negative infinity is never a position");
    check(deal_container_position(-1.0, 3u, 0, &offset) == 0,
          "a negative position is rejected");
    check(deal_container_position(1e300, 3u, 1, &offset) == 0,
          "a position beyond binary64 exactness is rejected");
    check(deal_container_position(0.0, 0u, 0, &offset) == 0,
          "an empty array has no read position");
    check(deal_container_position(0.0, 0u, 1, &offset) == 1 && offset == 0u,
          "an empty array appends at zero");

    /* a rejected write leaves the storage unchanged. */
    element = 1.5;
    deal_container_array_store(array, 1u, &element);
    check(deal_container_position(2.5, 3u, 1, &offset) == 0,
          "the rejected write position stays rejected");
    check(*(const double *)deal_container_array_element(array, 1u) == 1.5,
          "the rejected write leaves the stored element unchanged");
    check(deal_container_array_count(array) == 3u,
          "the rejected write leaves the element count unchanged");

    /* append writes at length and keeps every earlier element across growth. */
    for (int index = 0; index < 6; index += 1) {
        double appended = 10.0 + (double)index;
        deal_container_array_append(array, &appended);
    }
    check(deal_container_array_count(array) == 9u,
          "append writes at length and grows the count");
    check(*(const double *)deal_container_array_element(array, 1u) == 1.5,
          "an earlier element survives element-storage growth");
    check(*(const double *)deal_container_array_element(array, 3u) == 10.0,
          "the first appended element is at the previous length");
    check(*(const double *)deal_container_array_element(array, 8u) == 15.0,
          "the last appended element is at the last index");

    /* the element-storage trace follows the recorded element layout. */
    {
        deal_array *pairs = deal_container_array_new(TEST_LAYOUT_PAIR, 2u);
        void *first[2] = { pair_object(), pair_object() };
        void *second[2] = { pair_object(), NULL };
        deal_container_array_store(pairs, 0u, first);
        deal_container_array_store(pairs, 1u, second);
        collected_count = 0u;
        deal_container_array_trace(pairs, collect_reference, NULL);
        check(collected_count == 3u,
              "the element trace visits exactly the present references");
        check(collected[0] == first[0] && collected[1] == first[1]
                  && collected[2] == second[0],
              "the element trace visits index and offset order");
        check(deal_container_array_count(pairs) == 2u,
              "the two-reference array keeps its element count");
        check(deal_container_array_element(pairs, 1u) != NULL,
              "the two-reference array keeps its element slot");
    }

    /* a reference element slot legitimately holds a nullish singleton (C3):
     * the element walk visits neither singleton and the element barrier never
     * shades one, so a nullable element type keeps a precise map (M1, M2). */
    {
        deal_array *nullable =
            deal_container_array_new(TEST_LAYOUT_REFERENCE, 2u);
        void *null_element[1] = { deal_null_singleton };
        void *undefined_element[1] = { deal_undefined_singleton };
        void *managed_element[1] = { pair_object() };

        deal_container_array_store(nullable, 0u, null_element);
        collected_count = 0u;
        deal_container_array_trace(nullable, collect_reference, NULL);
        check(collected_count == 0u,
              "a null singleton element reference is never visited");
        deal_container_array_store(nullable, 1u, undefined_element);
        collected_count = 0u;
        deal_container_array_trace(nullable, collect_reference, NULL);
        check(collected_count == 0u,
              "an undefined singleton element reference is never visited");
        deal_container_array_store(nullable, 1u, managed_element);
        deal_container_array_append(nullable, null_element);
        collected_count = 0u;
        deal_container_array_trace(nullable, collect_reference, NULL);
        check(collected_count == 1u && collected[0] == managed_element[0],
              "the managed element reference beside the singletons is visited");
        check(deal_container_array_count(nullable) == 3u,
              "the appended singleton element keeps the exact count");
        check(((void **)deal_container_array_element(nullable, 0u))[0]
                  == deal_null_singleton,
              "a stored null singleton element keeps its slot bytes");
        check(((void **)deal_container_array_element(nullable, 2u))[0]
                  == deal_null_singleton,
              "an appended null singleton element keeps its slot bytes");
    }

    /* one array's element-block allocation roots its own intermediate (M5):
     * the helper's scope nests inside the caller's and both are restored. */
    {
        void *held = pair_object();
        deal_root_scope_push();
        uint32_t slot = deal_root_scope_hold(held);
        deal_array *guarded = deal_container_array_new(TEST_LAYOUT_PAIR, 2u);
        check(deal_root_scope_depth() == 1u,
              "the array helper restores the caller's scoped-root depth");
        check(deal_root_frame_depth() == 0u,
              "the array helper opens no root frame");
        collected_count = 0u;
        deal_root_sources_enumerate(collect_reference, NULL);
        check(collected_count == 1u && collected[0] == held,
              "the array helper keeps the caller's held root and adds none");
        check(deal_container_array_element_layout(guarded) == TEST_LAYOUT_PAIR,
              "the guarded array keeps its recorded element layout");
        check(deal_container_array_count(guarded) == 2u,
              "the guarded array keeps its exact element count");
        deal_root_scope_release(slot);
        deal_root_scope_pop();
        check(deal_root_scope_depth() == 0u,
              "the array helper leaves no scope open");
    }
    check(deal_root_frame_depth() == 0u && deal_root_scope_depth() == 0u,
          "the array operations leave the root depth restored");
}

int main(void) {
    if (deal_value_is_null(deal_null_singleton) != 1
            || deal_value_is_undefined(deal_undefined_singleton) != 1) {
        printf("FAIL: the nullish singletons are not distinct data values\n");
        return 1;
    }
    deal_layout_install(test_layouts,
                        sizeof(test_layouts) / sizeof(test_layouts[0]));
    check_data_states();
    check_scalar_nullish_tags();
    check_records();
    check_arrays();
    printf(failures == 0 ? "value storage ok\n" : "value storage failed\n");
    return failures == 0 ? 0 : 1;
}
