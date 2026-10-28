/*
 * deal/native/container.h — the `container` service group.
 *
 * Arrays and records (`llvm-native-runtime-value-model` R2, C3). An array is
 * zero-based typed element storage with an exact element count and its recorded
 * element layout; a record is an insertion-ordered store of boxed persistent
 * data values keyed by a scalar-valid string, where storing `undefined` or
 * `null` makes a key present and deletion removes it, so `has` distinguishes
 * absence from every stored value. The C spelling `deal_table` is the retained
 * internal name for a record, never a source `table` type.
 *
 * Discrete positions (R2). Source indexes are `number`, so finiteness,
 * integrality and bounds are validated by `deal_container_position` before any
 * native offset conversion — native storage never rounds or truncates, negative
 * zero addresses zero, an append writes at `length` and `length` answers the
 * exact element count. A read requires `0..length-1`, a write requires
 * `0..length`; a rejected position is reported, never converted, so a rejected
 * write leaves the storage unchanged. A native index outside that range reaching
 * these entries directly is an invariant fault, never a clamped or truncated
 * access.
 *
 * Element storage (C3, M1). An array records the emitted element layout of its
 * descriptor once, for its whole lifetime including an empty array and every
 * alias, and stores the elements in one managed block of `capacity * stride`
 * bytes. Beside the memory layout an array records its persistent element
 * descriptor as the `descriptor` group's opaque atom reference (R4): the
 * descriptor never re-derives an array's element type from its present
 * elements, so an empty array and an array whose elements happen to match a
 * different element type keep the descriptor they were built with. The token
 * is opaque here: this group stores and returns it and never interprets it.
 *
 * `deal_container_array_trace` walks exactly the managed references the
 * element layout declares, so the collector's precise map never guesses. A
 * reference element slot may legitimately hold the null or the undefined
 * singleton (a nullable element type), so both the element walk and the element
 * barrier are tag-aware and never treat a singleton as heap storage.
 *
 * The runtime-owned element layouts (C3) are `DEAL_LAYOUT_ELEMENT_DATUM` (one
 * persistent tagged datum slot), `DEAL_LAYOUT_ELEMENT_REFERENCE` (one managed
 * reference slot), `DEAL_LAYOUT_ELEMENT_NUMBER` (one binary64 slot) and
 * `DEAL_LAYOUT_ELEMENT_BOOLEAN` (one 0/1 slot); a class-instance element layout
 * is the emitted module's own metadata at `DEAL_LAYOUT_EMITTED_BASE` and above.
 * The element's *type* is never derived from its storage layout: it is the
 * array's recorded persistent element descriptor (R4), so an `unknown`
 * descriptor stores tagged datum slots and a `string` descriptor stores managed
 * string references.
 */
#ifndef DEAL_NATIVE_CONTAINER_H
#define DEAL_NATIVE_CONTAINER_H

#include "deal/native/deal_rt.h"

/*
 * Validates one source position against `length` native element slots (R2) and
 * writes the native offset: the position must be finite, integral and, for a
 * read, inside `0..length-1`; `allow_append` admits exactly `length`. Negative
 * zero addresses zero. A rejected position answers 0 and writes nothing.
 */
int deal_container_position(double position, size_t length, int allow_append,
                            size_t *offset);

/* A zero-based array of `length` zero-initialized slots of `element_layout`
 * that records no persistent element descriptor. */
deal_array *deal_container_array_new(uint32_t element_layout, size_t length);

/* A zero-based array of `length` zero-initialized slots of `element_layout`
 * that records `element_descriptor` (a `deal_descriptor` atom reference, 0 for
 * none) for its whole lifetime (R4). */
deal_array *deal_container_array_new_typed(uint32_t element_layout,
                                           uint32_t element_descriptor,
                                           size_t length);

/* The persistent element descriptor the array records, or 0 when it records
 * none. */
uint32_t deal_container_array_element_descriptor(const deal_array *array);

/* The exact element count (a `number` position never reports it truncated). */
size_t deal_container_array_count(const deal_array *array);

/* The element layout the array records for its whole lifetime (C3). */
uint32_t deal_container_array_element_layout(const deal_array *array);

/* The storage address of one present element (a typed slot, never a copy). */
void *deal_container_array_element(const deal_array *array, size_t index);

/*
 * Copies one element into an existing slot and runs the insertion barrier for
 * every managed reference the element layout declares (M2), exactly when the
 * reference slot holds a managed payload. A rejected element leaves the storage
 * unchanged; the caller has already validated the position and the element
 * against the descriptor.
 */
void deal_container_array_store(deal_array *array, size_t index,
                                const void *element);

/* Appends one element at `length`, growing the element storage when required. */
void deal_container_array_append(deal_array *array, const void *element);

/*
 * Visits every managed reference of every present element in index and offset
 * order, using the array's element layout (M1). A null slot and a nullish
 * singleton are never visited.
 */
void deal_container_array_trace(const deal_array *array,
                                deal_reference_visit visit, void *context);

/* An empty insertion-ordered record. */
deal_table *deal_container_table_new(void);

/* The number of present keys (null- and undefined-valued keys included). */
size_t deal_container_table_count(const deal_table *table);

/* The key at insertion order `index`, or NULL past the last present key. */
deal_string *deal_container_table_key_at(const deal_table *table, size_t index);

/*
 * The value stored under `key`, or the `undefined` datum when the key is
 * absent (R2's read projection). A present key holding `undefined` or `null`
 * answers that stored value.
 */
deal_data deal_container_table_read(const deal_table *table, deal_string *key);

/* 1 when the key is present (with any value, including null), else 0. */
int deal_container_table_has(const deal_table *table, deal_string *key);

/*
 * Stores one value under `key` through the insertion barrier: an existing key
 * keeps its first-insertion position, a new key is appended in creation order,
 * and `undefined`/`null` stay present values.
 */
void deal_container_table_write(deal_table *table, deal_string *key,
                                const deal_data *value);

/*
 * Removes the key when present; an absent key is not an error. The removal
 * republishes the neighbours' links, so each of those managed-reference stores
 * runs the insertion barrier first (M2): a deletion while the collector is
 * marking never leaves a surviving member unmarked behind a rewired link.
 */
void deal_container_table_delete(deal_table *table, deal_string *key);

#endif /* DEAL_NATIVE_CONTAINER_H */
