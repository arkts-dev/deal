#include "host/probe.h"
#include "native_helpers.h"
static deal_host_result read_leaf(const deal_value *args, size_t argc) {
    (void)argc;
    return result(field(args[0], "value"));
}
static const deal_host_field inner_fields[] = {
    {"value", "number", 0}, {"next", "@$external/host/probe/Inner", 1}
};
static const deal_host_field leaf_fields[] = {
    {"value", "number", 0}, {"child", "@$external/host/probe/Inner", 1}
};
const deal_host_class deal_host_classes_probe[] = {
    {"@$external/host/probe/Inner", inner_fields, 2u},
    {DEAL_HOST_CLASS_ATOM_PROBE_LEAF, leaf_fields, 2u}
};
const deal_host_field deal_host_fields_probe_Leaf[] = {
    {"value", "number", 0}, {"child", "@$external/host/probe/Inner", 1}
};
const deal_host_class deal_host_class_probe_Leaf = {
    DEAL_HOST_CLASS_ATOM_PROBE_LEAF, deal_host_fields_probe_Leaf, 2u
};
const deal_host_export deal_host_exports_probe[] = {
    {"Leaf", DEAL_HOST_CLASS_ATOM_PROBE_LEAF, DEAL_HOST_EXPORT_CLASS, 0, NULL, NULL, &deal_host_classes_probe[1]},
    {"read", "(" DEAL_HOST_CLASS_ATOM_PROBE_LEAF ")->number", DEAL_HOST_EXPORT_FUNCTION, 0, read_leaf, NULL, NULL}
};
const deal_host_module deal_host_module_probe = {
    DEAL_HOST_SPECIFIER_PROBE, deal_host_exports_probe, 2u, deal_host_classes_probe, 2u, NULL
};
