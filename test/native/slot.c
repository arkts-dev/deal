/*
 * test/native/slot.c — a host unit whose declared class carries a
 * function-typed field (`llvm-native-host-abi-and-async` H2, H4;
 * `llvm-native-runtime-value-model` R4/R11).
 *
 * The unit includes only the generated declaration header `host/slot.h` and
 * defines the registration exactly once. Its one declared class has the
 * function-typed slot `run: (x: number) => number`, and its two exports drive
 * the two directions of that slot:
 *
 *   - `makeAction` builds a host-built class carrier whose function-typed field
 *     names a host-created function value, so the crossing materializes the
 *     graph and DEAL reads and applies the declared function slot;
 *   - `probeFunctionSlot` stores into a view of that DEAL-held instance, so the
 *     store validates at the slot's declared descriptor — a canonical function
 *     descriptor, which belongs to the `FUNCTION_SIGNATURE` boundary and not the
 *     typed boundary — commits through the insertion barrier when it is
 *     admitted, and answers the closed `kind` status for a wrong-kind or
 *     wrong-signature value without changing the slot.
 */
#include "host/slot.h"

#include <string.h>

/* The host function value both crossings carry; it answers its argument plus
 * one, so an applied slot is distinguishable from the argument itself. */
static deal_host_result run_impl(const deal_value *args, size_t argc) {
  deal_host_result result;
  double argument = 0.0;
  if (argc != 1 || deal_as_number(args[0], &argument) != DEAL_OK) {
    return (deal_host_result){.failed = 1,
        .failure = {.kind = DEAL_FAILURE_HOST_TEXT, .code = "E_HOST_SLOT",
            .message = "the function slot's argument"}};
  }
  deal_value answer;
  if (deal_number(argument + 1.0, &answer) != DEAL_OK) {
    return (deal_host_result){.failed = 1,
        .failure = {.kind = DEAL_FAILURE_HOST_TEXT, .code = "E_HOST_SLOT",
            .message = "the function slot's result"}};
  }
  result.failed = 0;
  result.value = answer;
  result.failure.kind = DEAL_FAILURE_HOST_TEXT;
  result.failure.code = NULL;
  result.failure.message = NULL;
  result.failure.expected = NULL;
  result.failure.actual = NULL;
  result.failure.error = 0;
  return result;
}

static deal_host_result host_failure(const char *message) {
  deal_host_result result;
  result.failed = 1;
  result.failure.kind = DEAL_FAILURE_HOST_TEXT;
  result.failure.code = "E_HOST_SLOT";
  result.failure.message = message;
  result.failure.expected = NULL;
  result.failure.actual = NULL;
  result.failure.error = 0;
  result.value.slot = 0;
  result.value.generation = 0;
  return result;
}

static deal_host_result number_result(double value) {
  deal_host_result result;
  if (deal_number(value, &result.value) != DEAL_OK) {
    return host_failure("number construction failed");
  }
  result.failed = 0;
  result.failure.kind = DEAL_FAILURE_HOST_TEXT;
  result.failure.code = NULL;
  result.failure.message = NULL;
  result.failure.expected = NULL;
  result.failure.actual = NULL;
  result.failure.error = 0;
  return result;
}

/* The declared function value of the class field: one host function value whose
 * canonical signature is the field's declared descriptor (H4). */
static deal_status host_function(deal_value *out) {
  return deal_function_new(run_impl, "(number)->number", out);
}

static deal_host_result make_action_impl(const deal_value *args, size_t argc) {
  (void)args;
  (void)argc;
  deal_value object;
  if (deal_host_slot_Action_new(&object) != DEAL_OK) {
    return host_failure("Action construction failed");
  }
  deal_value function;
  if (host_function(&function) != DEAL_OK) {
    return host_failure("the function slot's value");
  }
  if (deal_host_slot_Action_run_set(object, function) != DEAL_OK) {
    return host_failure("the host-built function slot store");
  }
  deal_host_result result;
  result.failed = 0;
  result.value = object;
  result.failure.kind = DEAL_FAILURE_HOST_TEXT;
  result.failure.code = NULL;
  result.failure.message = NULL;
  result.failure.expected = NULL;
  result.failure.actual = NULL;
  result.failure.error = 0;
  return result;
}

static deal_host_result probe_function_slot_impl(const deal_value *args,
                                                size_t argc) {
  if (argc != 1) {
    return host_failure("the Action argument");
  }
  deal_value function;
  if (host_function(&function) != DEAL_OK) {
    return host_failure("the function slot's value");
  }
  /* The declared function slot of the DEAL-held instance: the store validates
   * against the slot's own descriptor and commits through the barrier (R11). */
  if (deal_field_set(args[0], "run", function) != DEAL_OK) {
    return host_failure("the host view store of the declared function slot");
  }
  deal_value number;
  if (deal_number(1.0, &number) != DEAL_OK) {
    return host_failure("number construction failed");
  }
  if (deal_field_set(args[0], "run", number) != DEAL_STATUS_KIND) {
    return host_failure("a wrong-kind slot value is not a kind status");
  }
  deal_value other;
  if (deal_function_new(run_impl, "()->number", &other) != DEAL_OK) {
    return host_failure("the mismatched function value");
  }
  if (deal_field_set(args[0], "run", other) != DEAL_STATUS_KIND) {
    return host_failure("a wrong-signature slot value is not a kind status");
  }
  /* The rejected stores changed nothing: the slot still holds the admitted
   * function value, which DEAL applies after the crossing. */
  deal_value read;
  if (deal_host_slot_Action_run_get(args[0], &read) != DEAL_OK) {
    return host_failure("the declared function slot read");
  }
  const char *signature = NULL;
  if (deal_function_signature(read, &signature) != DEAL_OK
      || signature == NULL || strcmp(signature, "(number)->number") != 0) {
    return host_failure("the rejected stores changed the declared slot");
  }
  return number_result(1.0);
}

/* The declared class metadata: one required function-typed field (H2). */
const deal_host_field deal_host_fields_slot_Action[] = {
    {"run", "(number)->number", 0},
};

const deal_host_class deal_host_classes_slot[] = {
    {DEAL_HOST_CLASS_ATOM_SLOT_ACTION, deal_host_fields_slot_Action,
     sizeof deal_host_fields_slot_Action
         / sizeof deal_host_fields_slot_Action[0]},
};

/* The registered class record (declared by the header). */
const deal_host_class deal_host_class_slot_Action = {
    DEAL_HOST_CLASS_ATOM_SLOT_ACTION, deal_host_fields_slot_Action,
    sizeof deal_host_fields_slot_Action
        / sizeof deal_host_fields_slot_Action[0]};

const deal_host_export deal_host_exports_slot[] = {
    {"Action", DEAL_HOST_CLASS_ATOM_SLOT_ACTION, DEAL_HOST_EXPORT_CLASS, 0,
     NULL, NULL, &deal_host_classes_slot[0]},
    {"makeAction", "()->@$external/host/slot/Action",
     DEAL_HOST_EXPORT_FUNCTION, 0, make_action_impl, NULL, NULL},
    {"probeFunctionSlot", "(@$external/host/slot/Action)->number",
     DEAL_HOST_EXPORT_FUNCTION, 0, probe_function_slot_impl, NULL, NULL},
};

const deal_host_module deal_host_module_slot = {DEAL_HOST_SPECIFIER_SLOT,
                                                 deal_host_exports_slot,
                                                 sizeof deal_host_exports_slot
                                                     / sizeof deal_host_exports_slot[0],
                                                 deal_host_classes_slot,
                                                 sizeof deal_host_classes_slot
                                                     / sizeof deal_host_classes_slot[0],
                                                 NULL};
