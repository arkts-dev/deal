#include "host/carrierclass.h"
#include "native_helpers.h"

#define CONFIG DEAL_HOST_CLASS_ATOM_CARRIERCLASS_CONFIG
#define HOLDER DEAL_HOST_CLASS_ATOM_CARRIERCLASS_HOLDER
static deal_value config(void) {
    deal_value v;
    checked(deal_host_carrierclass_Config_new(&v));
    checked(deal_field_set(v, "value", number(42)));
    return v;
}
static deal_host_result describe_async(deal_async_token t, const deal_value *a, size_t n) {
    (void)n; return complete(t, field(a[0], "value"));
}
static deal_host_result make_async(deal_async_token t, const deal_value *a, size_t n) {
    (void)a; (void)n; return complete(t, config());
}
static deal_host_result make_sync(const deal_value *a, size_t n) {
    (void)a; (void)n; return result(config());
}
static deal_host_result make_all(const deal_value *a, size_t n) {
    (void)a; (void)n;
    deal_value v = config(), xs = array(CONFIG, 2);
    checked(deal_array_set(xs, 0, v)); checked(deal_array_set(xs, 1, v));
    return result(xs);
}
static deal_host_result maybe_make(const deal_value *a, size_t n) {
    (void)n; return result(as_boolean(a[0]) ? config() : null_value());
}
static deal_host_result describe_or_null(const deal_value *a, size_t n) {
    (void)n; deal_value_kind k; checked(deal_kind_of(a[0], &k));
    return result(k == DEAL_KIND_NULL || k == DEAL_KIND_UNDEFINED ? number(-1) : field(a[0], "value"));
}
static deal_host_result maybe_async(deal_async_token t, const deal_value *a, size_t n) {
    (void)n; return complete(t, as_boolean(a[0]) ? config() : null_value());
}
static deal_host_result make_holder(const deal_value *a, size_t n) {
    (void)a; (void)n; deal_value v;
    checked(deal_host_carrierclass_Holder_new(&v));
    checked(deal_field_set(v, "label", text("holder")));
    checked(deal_field_set(v, "primary", config()));
    checked(deal_field_set(v, "optional", null_value()));
    deal_value xs = array(CONFIG, 1); checked(deal_array_set(xs, 0, config()));
    checked(deal_field_set(v, "items", xs)); return result(v);
}
static deal_host_result consume(const deal_value *a, size_t n) {
    (void)n; deal_value arg = config(); deal_host_result r = apply(a[0], &arg, 1u);
    if (r.failed) {
        if (r.failure.kind == DEAL_FAILURE_DEAL_ERROR) checked(deal_failure_discard(r.failure.error));
        return result(number(-4));
    }
    return result(field(r.value, "value"));
}
static deal_host_result wrong_kind(const deal_value *a, size_t n) {
    (void)a; (void)n; return result(number(42));
}
static deal_host_result null_class(const deal_value *a, size_t n) {
    (void)a; (void)n; return result(null_value());
}
static deal_host_result wrong_element(const deal_value *a, size_t n) {
    (void)a; (void)n; deal_value xs = array(CONFIG, 2);
    checked(deal_array_set(xs, 0, config())); checked(deal_array_set(xs, 1, text("junk")));
    return result(xs);
}
static deal_host_result sparse(const deal_value *a, size_t n) {
    (void)a; (void)n; deal_value xs = array(CONFIG, 3);
    checked(deal_array_set(xs, 0, config())); checked(deal_array_set(xs, 2, config())); return result(xs);
}
static deal_host_result make_map(const deal_value *a, size_t n) {
    (void)a; (void)n; deal_value v; checked(deal_table_new(&v)); set(v, "alpha", config()); return result(v);
}
const deal_host_field deal_host_fields_carrierclass_Config[] = {{"value", "number", 0}};
const deal_host_class deal_host_class_carrierclass_Config = {CONFIG, deal_host_fields_carrierclass_Config, 1u};
const deal_host_field deal_host_fields_carrierclass_Holder[] = {
    {"label", "string", 0}, {"primary", CONFIG, 0}, {"optional", "?" CONFIG, 0},
    {"maybe", "?" CONFIG, 1}, {"items", "[" CONFIG "]", 0}
};
const deal_host_class deal_host_class_carrierclass_Holder = {HOLDER, deal_host_fields_carrierclass_Holder, 5u};
const deal_host_class deal_host_classes_carrierclass[] = {
    {CONFIG, deal_host_fields_carrierclass_Config, 1u}, {HOLDER, deal_host_fields_carrierclass_Holder, 5u}
};
const deal_host_export deal_host_exports_carrierclass[] = {
    {"Config", CONFIG, DEAL_HOST_EXPORT_CLASS, 0, NULL, NULL, &deal_host_classes_carrierclass[0]},
    {"Holder", HOLDER, DEAL_HOST_EXPORT_CLASS, 0, NULL, NULL, &deal_host_classes_carrierclass[1]},
    {"describeAsync", "async(" CONFIG ")->number", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, describe_async, NULL},
    {"makeAsync", "async()->" CONFIG, DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, make_async, NULL},
    {"makeSync", "()->" CONFIG, DEAL_HOST_EXPORT_FUNCTION, 0, make_sync, NULL, NULL},
    {"makeAll", "()->[" CONFIG "]", DEAL_HOST_EXPORT_FUNCTION, 0, make_all, NULL, NULL},
    {"maybeMake", "(boolean)->?" CONFIG, DEAL_HOST_EXPORT_FUNCTION, 0, maybe_make, NULL, NULL},
    {"describeOrNull", "(?" CONFIG ")->number", DEAL_HOST_EXPORT_FUNCTION, 0, describe_or_null, NULL, NULL},
    {"maybeMakeAsync", "async(boolean)->?" CONFIG, DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, maybe_async, NULL},
    {"makeHolder", "()->" HOLDER, DEAL_HOST_EXPORT_FUNCTION, 0, make_holder, NULL, NULL},
    {"consume", "((" CONFIG ")->" CONFIG ")->number", DEAL_HOST_EXPORT_FUNCTION, 0, consume, NULL, NULL},
    {"makeWrongKind", "()->" HOLDER, DEAL_HOST_EXPORT_FUNCTION, 0, wrong_kind, NULL, NULL},
    {"makeNullClass", "()->" HOLDER, DEAL_HOST_EXPORT_FUNCTION, 0, null_class, NULL, NULL},
    {"makeWrongElement", "()->[" CONFIG "]", DEAL_HOST_EXPORT_FUNCTION, 0, wrong_element, NULL, NULL},
    {"makeSparse", "()->[" CONFIG "]", DEAL_HOST_EXPORT_FUNCTION, 0, sparse, NULL, NULL},
    {"makeMap", "()->[" CONFIG "]", DEAL_HOST_EXPORT_FUNCTION, 0, make_map, NULL, NULL}
};
const deal_host_module deal_host_module_carrierclass = {
    DEAL_HOST_SPECIFIER_CARRIERCLASS, deal_host_exports_carrierclass,
    sizeof deal_host_exports_carrierclass / sizeof deal_host_exports_carrierclass[0], deal_host_classes_carrierclass, 2u, NULL
};
