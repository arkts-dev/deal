#ifndef DEAL_CORPUS_NATIVE_HOSTILE_H
#define DEAL_CORPUS_NATIVE_HOSTILE_H
#include "native_helpers.h"

/* Fixture-only mirror of crossing-owned carriers. Public constructors reject
 * these hostile mutations; tests change the existing object, never production
 * admission or a replacement carrier. Keep this prefix aligned with host.c. */
typedef struct {
    void *key_bytes;
    size_t key_length;
    deal_value value;
    deal_value raw_key;
    uint32_t has_raw_key;
} fixture_member;
typedef struct {
    int kind, is_async, boolean;
    double number;
    void *bytes;
    size_t length;
    const char *signature;
    deal_descriptor *element_descriptor;
    void *slots;
    size_t slot_count, slot_capacity;
    fixture_member *members;
    size_t member_count, member_capacity;
    deal_host_function function;
    deal_async_token token;
    uint32_t unicode_defect;
    int materialized;
    deal_data managed;
} fixture_box;
static inline fixture_box *fixture_box_of(deal_value value) {
    void *box = NULL;
    checked(deal_value_crossing_resolve(deal_value_crossing_active(), value, &box));
    if (box == NULL) abort();
    return box;
}
static inline deal_host_result fixture_seven(const deal_value *args, size_t argc) {
    (void)args; (void)argc;
    return result(number(7));
}
static inline deal_value fixture_bad_function(int bad_signature) {
    deal_value function;
    checked(deal_function_new(fixture_seven, "()->number", &function));
    fixture_box *box = fixture_box_of(function);
    if (bad_signature) box->signature = "garbage";
    else box->function = NULL;
    return function;
}
/* A raw host callable keeps its native entry, but has no DEAL signature. */
static inline deal_value fixture_raw_function(deal_host_function entry) {
    deal_value value;
    checked(deal_function_new(entry, "(number)->number", &value));
    const char *missing = NULL;
    memcpy((unsigned char *)fixture_box_of(value) + offsetof(fixture_box, signature),
        &missing, sizeof(missing));
    deal_value_kind kind;
    checked(deal_kind_of(value, &kind));
    const char *signature = "unchanged";
    deal_handle handle = 0u;
    if (kind != DEAL_KIND_FUNCTION
            || deal_function_signature(value, &signature) != DEAL_STATUS_KIND
            || strcmp(signature, "unchanged") != 0
            || deal_retain(value, &handle) != DEAL_STATUS_KIND || handle != 0u) abort();
    return value;
}
/* The managed function shape is mirrored only to remove the payload of an
 * already stored callable. Its signature, environment and identity survive. */
typedef struct {
    deal_fn_thunk thunk;
    deal_descriptor *signature;
    void *environment;
    uint32_t capture_count, is_async;
} fixture_function;
static inline void fixture_remove_function_payload(deal_value value) {
    deal_check_input input;
    deal_error *cause = NULL;
    checked(deal_host_value_presentation(value, &input, &cause));
    if (cause != NULL || input.presentation != DEAL_PRESENT_DATUM
            || input.datum.tag != DEAL_DATA_FUNCTION) abort();
    deal_fn_thunk missing = NULL;
    memcpy((unsigned char *)input.datum.reference + offsetof(fixture_function, thunk),
        &missing, sizeof(missing));
}
static inline void fixture_remove_function_signature(deal_value value) {
    deal_check_input input;
    deal_error *cause = NULL;
    checked(deal_host_value_presentation(value, &input, &cause));
    if (cause != NULL || input.datum.tag != DEAL_DATA_FUNCTION) abort();
    deal_descriptor *missing = NULL;
    memcpy((unsigned char *)input.datum.reference + offsetof(fixture_function, signature),
        &missing, sizeof(missing));
}
typedef struct {
    size_t byte_length, scalar_count;
    uint64_t hash;
    unsigned char bytes[];
} fixture_string;
static inline void fixture_surrogate_string(deal_value value) {
    deal_check_input input;
    deal_error *cause = NULL;
    checked(deal_host_value_presentation(value, &input, &cause));
    if (cause != NULL || input.datum.tag != DEAL_DATA_STRING) abort();
    unsigned char *string = input.datum.reference;
    size_t length;
    memcpy(&length, string + offsetof(fixture_string, byte_length), sizeof(length));
    if (length != 3u) abort();
    memcpy(string + offsetof(fixture_string, bytes), "\xed\xa0\x80", 3u);
    size_t scalars = 1u;
    memcpy(string + offsetof(fixture_string, scalar_count), &scalars, sizeof(scalars));
}
/* Error text is platform-owned. Replace only the selected field; retain the
 * old pointer for repair, without making invalid text into a DEAL string. */
typedef struct {
    char *code, *message;
    size_t code_length, message_length;
} fixture_error_text;
static inline char *fixture_error_text_replace(deal_value value, int message,
        char *replacement) {
    deal_check_input input;
    deal_error *cause = NULL;
    checked(deal_host_value_presentation(value, &input, &cause));
    if (cause != NULL || input.datum.tag != DEAL_DATA_ERROR) abort();
    unsigned char *record = input.datum.reference;
    unsigned char *slot = record + (message ? offsetof(fixture_error_text, message)
        : offsetof(fixture_error_text, code));
    char *previous;
    memcpy(&previous, slot, sizeof(previous));
    memcpy(slot, &replacement, sizeof(replacement));
    size_t length = replacement == NULL ? 0u : strlen(replacement);
    memcpy(record + (message ? offsetof(fixture_error_text, message_length)
        : offsetof(fixture_error_text, code_length)), &length, sizeof(length));
    return previous;
}
/* Mirror emitted field storage only here: atom, presence bytes, aligned
 * deal_data slots. Bypass checked setters without changing admission APIs. */
static inline void fixture_class_field(deal_value value, const char *name,
        deal_value replacement, int present) {
    deal_check_input input;
    deal_error *cause = NULL;
    checked(deal_host_value_presentation(value, &input, &cause));
    if (cause != NULL || input.datum.tag != DEAL_DATA_CLASS) abort();
    const deal_host_class_access *access = deal_host_class_access_of(input.class_atom);
    if (access == NULL) abort();
    size_t count = access->declared->field_count, index = 0u;
    while (index < count && strcmp(access->declared->fields[index].name, name)) index++;
    if (index == count) abort();
    deal_check_input next;
    checked(deal_host_value_presentation(replacement, &next, &cause));
    if (cause != NULL || next.presentation != DEAL_PRESENT_DATUM) abort();
    size_t offset = (sizeof(void *) + count + 7u) & ~(size_t)7u;
    unsigned char *base = input.datum.reference;
    deal_data *slot = (deal_data *)(void *)(base + offset + index * sizeof(deal_data));
    deal_data datum = present ? next.datum : deal_data_of_undefined();
    memcpy(slot, &datum, sizeof(datum));
    deal_store_barrier_data(base, slot);
    uint8_t flag = present != 0;
    memcpy(base + sizeof(void *) + index, &flag, sizeof(flag));
}
static inline void fixture_undefined_field_to_null(deal_value value, const char *name) {
    fixture_class_field(value, name, null_value(), 1);
}
/* Preserve the array, recorded number descriptor, capacity and all other
 * elements. The tagged slot can retain a hostile value until its consumer. */
static inline void fixture_number_element(deal_value value, size_t index,
        deal_value replacement) {
    deal_check_input input, next;
    deal_error *cause = NULL;
    checked(deal_host_value_presentation(value, &input, &cause));
    if (cause != NULL || input.datum.tag != DEAL_DATA_ARRAY) abort();
    deal_array *array = input.datum.reference;
    if (deal_container_array_element_layout(array) != DEAL_LAYOUT_ELEMENT_DATUM
            || index >= deal_container_array_count(array)
            || strcmp(deal_descriptor_text(deal_descriptor_of_reference(
                deal_container_array_element_descriptor(array))), "number")) abort();
    checked(deal_host_value_presentation(replacement, &next, &cause));
    if (cause != NULL || next.presentation != DEAL_PRESENT_DATUM) abort();
    void *slot = deal_container_array_element(array, index);
    memcpy(slot, &next.datum, sizeof(next.datum));
    deal_store_barrier_data(array, slot);
}
/* Test-owned opaque host object, allocated with a private leaf layout. It
 * remains live in the original Record but is never admitted as DEAL unknown. */
static inline deal_data fixture_opaque_datum(void) {
    static const deal_layout layout = {DEAL_LAYOUT_EMITTED_BASE - 1u, 0u,
        sizeof(void *), NULL, 0u};
    deal_layout_define(&layout);
    void *object = deal_alloc(layout.id, sizeof(void *));
    const char *identity = "@host/Opaque";
    memcpy(object, &identity, sizeof(identity));
    return deal_data_of_reference(DEAL_DATA_OPAQUE, object);
}
typedef struct fixture_table_entry {
    deal_string *key;
    deal_data value;
    struct fixture_table_entry *next, *previous;
} fixture_table_entry;
typedef struct { size_t count; fixture_table_entry *head, *tail; } fixture_table;
static inline void fixture_record_opaque(deal_value table) {
    set(table, "bad", number(0));
    deal_check_input input; deal_error *cause = NULL;
    checked(deal_host_value_presentation(table, &input, &cause));
    if (cause != NULL || input.datum.tag != DEAL_DATA_TABLE) abort();
    deal_data opaque = fixture_opaque_datum();
    fixture_table_entry *last;
    memcpy(&last, (unsigned char *)input.datum.reference + offsetof(fixture_table, tail), sizeof(last));
    deal_data *slot = (deal_data *)(void *)((unsigned char *)last + offsetof(fixture_table_entry, value));
    memcpy(slot, &opaque, sizeof(opaque)); deal_store_barrier_data(last, slot);
}
static inline void fixture_record_raw_key(deal_value table, deal_value key) {
    fixture_box *box = fixture_box_of(table);
    if (box->member_count == 0u) abort();
    fixture_member *last = &box->members[box->member_count - 1u];
    memcpy(&last->raw_key, &key, sizeof(key)); last->has_raw_key = 1u;
}
static inline void fixture_bad_key(deal_value table) {
    /* Existing key storage has equal size; preserve member/value/order. */
    fixture_box *box = fixture_box_of(table);
    if (box->member_count != 1u || box->members[0].key_length != 3u) abort();
    memcpy(box->members[0].key_bytes, "\xed\xa0\x80", 3u);
}
#endif
