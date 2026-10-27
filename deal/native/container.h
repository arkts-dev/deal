/*
 * deal/native/container.h — the `container` service group.
 *
 * Arrays and records (`llvm-native-runtime-value-model` R2). An array is
 * zero-based typed element storage with an exact element count and its emitted
 * element descriptor; a record is an insertion-ordered store of boxed values
 * keyed by a scalar-valid string, where storing `null` or `undefined` makes a
 * key present and deletion removes it, so `has` distinguishes absence from
 * every stored value. Source-language indexes are `number` and are validated
 * (finiteness, integrality, bounds) by the emitted code before any native
 * offset conversion; these entries take an already validated native index. The
 * C spelling `deal_table` is the retained internal name for a record, never a
 * source `table` type.
 *
 * The group's entries land with the runtime-data child, which extends this
 * header; the host-visible container surface is H2's entries of `deal_rt.h`.
 */
#ifndef DEAL_NATIVE_CONTAINER_H
#define DEAL_NATIVE_CONTAINER_H

#include "deal/native/deal_rt.h"

/* A zero-based typed array with `length` slots and its emitted element layout. */
deal_array *deal_container_array_new(uint32_t element_layout, size_t length);

/* The exact element count and one element slot. */
size_t deal_container_array_count(const deal_array *array);
void *deal_container_array_element(const deal_array *array, size_t index);

/* Stores one element through the insertion barrier (M2). */
void deal_container_array_store(deal_array *array, size_t index, void *element);

/* Appends one element, growing the storage when required. */
void deal_container_array_append(deal_array *array, void *element);

/* An empty insertion-ordered table. */
deal_table *deal_container_table_new(void);

/* The number of present keys. */
size_t deal_container_table_count(const deal_table *table);

/* The value stored under `key`, or the null singleton when the key is absent. */
deal_value deal_container_table_read(const deal_table *table, deal_string *key);

/* Stores one value through the insertion barrier; `null` stays present. */
void deal_container_table_write(deal_table *table, deal_string *key,
                                deal_value value);

/* 1 when the key is present (with any value, including null), else 0. */
int deal_container_table_has(const deal_table *table, deal_string *key);

/* Removes the key when present; an absent key is not an error. */
void deal_container_table_delete(deal_table *table, deal_string *key);

#endif /* DEAL_NATIVE_CONTAINER_H */
