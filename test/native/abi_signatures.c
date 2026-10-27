/*
 * test/native/abi_signatures.c — the declared entry signatures of the
 * published ABI.
 *
 * A compile-only, strict-C11 consumer: it includes only the published headers
 * (`deal/native/deal_rt.h` through the group headers) and names every declared
 * entry through a function pointer of its pinned C type, so a changed
 * parameter list, return type or calling shape no longer compiles. It links
 * nothing and calls nothing: entries this task declares without implementing
 * (the H2 host value surface, the async completion intake and the native host
 * load entry) are asserted at the same strength as the implemented ones, and
 * the linked build-shape cases prove the definitions.
 */
#include "deal/native/deal_rt.h"
#include "deal/native/entry.h"

#include <stddef.h>
#include <stdint.h>

static deal_status (*const host_null)(deal_value *) = deal_null;
static deal_status (*const host_undefined)(deal_value *) = deal_undefined;
static deal_status (*const host_nothing)(deal_value *) = deal_nothing;
static deal_status (*const host_absent)(deal_value *) = deal_absent;
static deal_status (*const host_boolean)(int, deal_value *) = deal_boolean;
static deal_status (*const host_number)(double, deal_value *) = deal_number;
static deal_status (*const host_string)(const void *, size_t, deal_value *) =
    deal_string_new;
static deal_status (*const host_array)(int64_t, deal_value *) = deal_array_new;
static deal_status (*const host_array_typed)(int64_t, const char *, deal_value *) =
    deal_array_new_typed;
static deal_status (*const host_table)(deal_value *) = deal_table_new;
static deal_status (*const host_class_atom)(const char *, deal_value *) =
    deal_class_atom;
static deal_status (*const host_class_new)(deal_value, deal_value *) =
    deal_class_new;
static deal_status (*const host_function_new)(deal_host_function, const char *,
                                              deal_value *) = deal_function_new;
static deal_status (*const host_async_operation)(deal_async_token, deal_value *) =
    deal_async_operation;
static deal_status (*const host_kind)(deal_value, deal_value_kind *) =
    deal_kind_of;
static deal_status (*const host_as_boolean)(deal_value, int *) = deal_as_boolean;
static deal_status (*const host_as_number)(deal_value, double *) = deal_as_number;
static deal_status (*const host_as_string)(deal_value, const void **, size_t *) =
    deal_as_string;
static deal_status (*const host_same)(deal_value, deal_value, int *) = deal_same;
static deal_status (*const host_array_length)(deal_value, int64_t *) =
    deal_array_length;
static deal_status (*const host_array_get)(deal_value, int64_t, deal_value *) =
    deal_array_get;
static deal_status (*const host_array_set)(deal_value, int64_t, deal_value) =
    deal_array_set;
static deal_status (*const host_table_get)(deal_value, const void *, size_t,
                                           deal_value *) = deal_table_get;
static deal_status (*const host_table_set)(deal_value, const void *, size_t,
                                           deal_value) = deal_table_set;
static deal_status (*const host_table_has)(deal_value, const void *, size_t,
                                           int *) = deal_table_has;
static deal_status (*const host_table_delete)(deal_value, const void *, size_t) =
    deal_table_delete;
static deal_status (*const host_class_identity)(deal_value, const char **) =
    deal_class_identity;
static deal_status (*const host_field_get)(deal_value, const char *, deal_value *) =
    deal_field_get;
static deal_status (*const host_field_set)(deal_value, const char *, deal_value) =
    deal_field_set;
static deal_status (*const host_field_has)(deal_value, const char *, int *) =
    deal_field_has;
static deal_status (*const host_field_delete)(deal_value, const char *) =
    deal_field_delete;
static deal_status (*const host_function_signature)(deal_value, const char **) =
    deal_function_signature;
static deal_status (*const host_apply)(deal_value, const deal_value *, size_t,
                                       deal_host_result *) = deal_apply;
static deal_status (*const host_retain)(deal_value, deal_handle *) = deal_retain;
static deal_status (*const host_handle_value)(deal_handle, const char *,
                                              deal_value *) = deal_handle_value;
static deal_status (*const host_release)(deal_handle) = deal_release;
static deal_status (*const host_discard)(deal_error_token) = deal_failure_discard;
static deal_status (*const host_error_from_handle)(deal_handle,
                                                   deal_host_failure *) =
    deal_error_from_handle;
static deal_status (*const async_complete_value)(deal_async_token, deal_value) =
    deal_async_complete_value;
static deal_status (*const async_complete_error)(deal_async_token,
                                                 const deal_host_failure *) =
    deal_async_complete_error;
static deal_status (*const async_post)(deal_async_token,
                                       const deal_host_completion *) =
    deal_async_post;

/* The C3 v1.5 nullish representation authority (value.h). */
static int (*const value_is_null)(const void *) = deal_value_is_null;
static int (*const value_is_undefined)(const void *) = deal_value_is_undefined;
static void *const *const null_singleton = &deal_null_singleton;
static void *const *const undefined_singleton = &deal_undefined_singleton;

static deal_outcome (*const project_start)(void) = deal_project_start;
static deal_outcome (*const project_run_main)(void) = deal_project_run_main;
static deal_module *(*const project_exports)(const char *) = deal_project_exports;
static deal_export_entry *(*const export_lookup)(deal_module *, const char *) =
    deal_export_lookup;
static deal_outcome (*const export_invoke)(deal_export_entry *) =
    deal_export_invoke;
static deal_outcome (*const async_export_invoke)(deal_export_entry *) =
    deal_async_export_invoke;
static int (*const project_main)(void) = deal_project_main;
static int (*const accessor_code)(deal_error *, const char **) = deal_error_code;
static int (*const accessor_message)(deal_error *, const char **) =
    deal_error_message;
static int (*const accessor_file)(deal_error *, const char **) =
    deal_error_source_file;
static int (*const accessor_line)(deal_error *, int32_t *) = deal_error_line;
static int (*const accessor_column)(deal_error *, int32_t *) = deal_error_column;
static int (*const accessor_expected)(deal_error *, const char **) =
    deal_error_expected;
static int (*const accessor_actual)(deal_error *, const char **) =
    deal_error_actual;
static int (*const accessor_frame_count)(deal_error *, int32_t *) =
    deal_error_frame_count;
static int (*const accessor_frame)(deal_error *, int32_t, const char **) =
    deal_error_frame;
static int (*const accessor_cause)(deal_error *, const char **) =
    deal_error_cause_code;

static int (*const arm_install)(const deal_arm_table *) = deal_arm_table_install;
static const deal_arm_table *(*const arm_installed)(void) =
    deal_arm_table_installed;

/*
 * Every declared entry is referenced once, so an unused file-scope declaration
 * cannot hide a signature that no longer matches its declaration, and no
 * build of this unit can drop the assertion.
 */
void deal_abi_signature_references(void);

void deal_abi_signature_references(void) {
    volatile int referenced = 0;
    if (host_null == NULL || host_undefined == NULL || host_nothing == NULL
            || host_absent == NULL
            || host_boolean == NULL || host_number == NULL || host_string == NULL
            || host_array == NULL || host_array_typed == NULL
            || host_table == NULL
            || host_class_atom == NULL || host_class_new == NULL
            || host_function_new == NULL || host_async_operation == NULL
            || host_kind == NULL || host_as_boolean == NULL
            || host_as_number == NULL || host_as_string == NULL
            || host_same == NULL || host_array_length == NULL
            || host_array_get == NULL || host_array_set == NULL
            || host_table_get == NULL || host_table_set == NULL
            || host_table_has == NULL || host_table_delete == NULL
            || host_class_identity == NULL || host_field_get == NULL
            || host_field_set == NULL || host_field_has == NULL
            || host_field_delete == NULL || host_function_signature == NULL
            || host_apply == NULL || host_retain == NULL
            || host_handle_value == NULL || host_release == NULL
            || host_discard == NULL || host_error_from_handle == NULL
            || async_complete_value == NULL || async_complete_error == NULL
            || async_post == NULL) {
        referenced += 1;
    }
    if (project_start == NULL || project_run_main == NULL
            || project_exports == NULL || export_lookup == NULL
            || export_invoke == NULL || async_export_invoke == NULL
            || project_main == NULL) {
        referenced += 1;
    }
    if (accessor_code == NULL || accessor_message == NULL
            || accessor_file == NULL || accessor_line == NULL
            || accessor_column == NULL || accessor_expected == NULL
            || accessor_actual == NULL || accessor_frame_count == NULL
            || accessor_frame == NULL || accessor_cause == NULL) {
        referenced += 1;
    }
    if (arm_install == NULL || arm_installed == NULL) {
        referenced += 1;
    }
    if (value_is_null == NULL || value_is_undefined == NULL
            || null_singleton == NULL || undefined_singleton == NULL) {
        referenced += 1;
    }
    (void)referenced;
}
