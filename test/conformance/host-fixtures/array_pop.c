#include "host/probe.h"
#include "native_hostile.h"
static deal_handle saved;
static void remember(deal_value value) {
    if (saved) checked(deal_release(saved));
    checked(deal_retain(value, &saved));
}
static size_t count(void) {
    int64_t n; checked(deal_array_length(retained(saved, NULL), &n)); return (size_t)n;
}
static deal_host_result keep(const deal_value *args, size_t argc) {
    (void)argc; remember(args[0]); return result(undefined_value());
}
static deal_host_result corrupt(const deal_value *args, size_t argc) {
    (void)args; (void)argc;
    fixture_number_element(retained(saved, NULL), count()-1u, text("bad"));
    return result(undefined_value());
}
static deal_host_result repair(const deal_value *args, size_t argc) {
    (void)args; (void)argc;
    fixture_number_element(retained(saved, NULL), count()-1u, number(9));
    return result(undefined_value());
}
static deal_host_result size(const deal_value *args, size_t argc) {
    (void)args; (void)argc; return result(number((double)count()));
}
static deal_host_result echo(const deal_value *args, size_t argc) { (void)argc; return result(args[0]); }
static deal_host_result make(const deal_value *args, size_t argc) {
    (void)args; (void)argc; deal_value v=array("number",2);
    checked(deal_array_set(v,0,number(4))); checked(deal_array_set(v,1,number(9)));
    remember(v); return result(v);
}
static deal_host_result grow(const deal_value *args, size_t argc) {
    (void)args; (void)argc; checked(deal_array_set(retained(saved,NULL),(int64_t)count(),number(11)));
    return result(undefined_value());
}
static deal_handle damaged;
static deal_host_result damage(const deal_value *args, size_t argc) {
    (void)argc; int restore; checked(deal_as_boolean(args[1], &restore));
    if (!restore) { if (damaged) checked(deal_release(damaged)); checked(deal_retain(args[0], &damaged)); }
    fixture_class_field(retained(damaged, NULL), "x", restore ? number(9) : text("bad"), 1);
    return result(undefined_value());
}
static deal_handle callable;
static deal_fn_thunk body;
static deal_descriptor *signature;
static deal_host_result poison(const deal_value *args, size_t argc) {
    (void)argc; if (callable) checked(deal_release(callable)); checked(deal_retain(args[0], &callable));
    deal_check_input input; deal_error *cause = NULL;
    checked(deal_host_value_presentation(args[0], &input, &cause)); if (cause) abort();
    memcpy(&body, (unsigned char *)input.datum.reference + offsetof(fixture_function, thunk), sizeof(body));
    memcpy(&signature, (unsigned char *)input.datum.reference + offsetof(fixture_function, signature), sizeof(signature));
    fixture_remove_function_payload(args[0]); return result(undefined_value());
}
static deal_host_result poison_signature(const deal_value *args, size_t argc) {
    (void)poison(args, argc);
    deal_check_input input; deal_error *cause = NULL;
    checked(deal_host_value_presentation(retained(callable,NULL), &input, &cause)); if (cause) abort();
    memcpy((unsigned char *)input.datum.reference + offsetof(fixture_function, thunk), &body, sizeof(body));
    fixture_remove_function_signature(args[0]); return result(undefined_value());
}
static deal_host_result restore_function(const deal_value *args, size_t argc) {
    (void)args; (void)argc;
    deal_check_input input; deal_error *cause = NULL;
    checked(deal_host_value_presentation(retained(callable,NULL), &input, &cause)); if (cause) abort();
    memcpy((unsigned char *)input.datum.reference + offsetof(fixture_function, thunk), &body, sizeof(body));
    memcpy((unsigned char *)input.datum.reference + offsetof(fixture_function, signature), &signature, sizeof(signature));
    return result(undefined_value());
}
const deal_host_export deal_host_exports_probe[] = {
    {"keep", "(unknown)->void", DEAL_HOST_EXPORT_FUNCTION, 0, keep, NULL, NULL},
    {"corrupt", "()->void", DEAL_HOST_EXPORT_FUNCTION, 0, corrupt, NULL, NULL},
    {"repair", "()->void", DEAL_HOST_EXPORT_FUNCTION, 0, repair, NULL, NULL},
    {"size", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, size, NULL, NULL},
    {"echo", "(unknown)->unknown", DEAL_HOST_EXPORT_FUNCTION, 0, echo, NULL, NULL},
    {"make", "()->[number]", DEAL_HOST_EXPORT_FUNCTION, 0, make, NULL, NULL},
    {"grow", "()->void", DEAL_HOST_EXPORT_FUNCTION, 0, grow, NULL, NULL},
    {"damage", "(unknown,boolean)->void", DEAL_HOST_EXPORT_FUNCTION, 0, damage, NULL, NULL},
    {"poison", "(unknown)->void", DEAL_HOST_EXPORT_FUNCTION, 0, poison, NULL, NULL},
    {"poisonSignature", "(unknown)->void", DEAL_HOST_EXPORT_FUNCTION, 0, poison_signature, NULL, NULL},
    {"restoreFunction", "()->void", DEAL_HOST_EXPORT_FUNCTION, 0, restore_function, NULL, NULL}
};
const deal_host_module deal_host_module_probe = {DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, 11u, NULL, 0u, NULL};
