/*
 * test/native/host_load_declaration.c — the native host load surface.
 *
 * A strict-C11 consumer that includes only the published headers, names both
 * records of the native host load surface and takes the address of the load
 * entry without linking it (the entry is implemented by the host-sync child).
 * The file is compiled, never linked, so the declaration is exercised exactly as
 * the criterion requires: the names, the record members and the prototype exist
 * in the published header, and a consumer can take the entry's address.
 */
#include "deal/native/host.h"
#include "deal/native/platform.h"

/* The pinned prototype, through a function pointer of the declared type. */
static deal_outcome (*const load_entry)(
    const deal_host_declared_module *declared,
    const deal_origin *origin,
    const deal_host_binding **out) = deal_host_load;

/* Both records are nameable, and their members are H2-declared types. */
static size_t declared_members(void) {
    deal_host_declared_module declared;
    declared.specifier = "probe/host";
    declared.module_path = "probe/host";
    declared.exports = NULL;
    declared.export_count = 0;
    declared.classes = NULL;
    declared.class_count = 0;
    return sizeof(declared.specifier) + sizeof(declared.module_path)
        + sizeof(declared.exports) + sizeof(declared.export_count)
        + sizeof(declared.classes) + sizeof(declared.class_count);
}

static size_t binding_members(void) {
    deal_host_binding binding;
    binding.specifier = "probe/host";
    binding.module = NULL;
    binding.exports = NULL;
    binding.export_count = 0;
    return sizeof(binding.specifier) + sizeof(binding.module)
        + sizeof(binding.exports) + sizeof(binding.export_count);
}

/* Reached when the linker would resolve: this unit is compiled, not linked. */
int main(void) {
    return (int)(declared_members() + binding_members()
        + (load_entry != NULL ? 0U : 1U));
}
