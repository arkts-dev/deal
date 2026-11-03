#ifndef DEAL_CORPUS_NATIVE_HELPERS_H
#define DEAL_CORPUS_NATIVE_HELPERS_H
#include "deal/native/deal_rt.h"
#include <stdlib.h>
#include <string.h>

static inline void checked(deal_status status) {
    if (status != DEAL_OK) abort();
}
static inline deal_value number(double n) { deal_value v; checked(deal_number(n, &v)); return v; }
static inline deal_value text(const char *s) { deal_value v; checked(deal_string_new(s, strlen(s), &v)); return v; }
static inline deal_value raw_array(int64_t n) {
    /* Native raw host arrays have unknown persistent element storage. */
    deal_value v; checked(deal_array_new_typed(n, "unknown", &v)); return v;
}
static inline deal_value null_value(void) { deal_value v; checked(deal_null(&v)); return v; }
static inline deal_value undefined_value(void) { deal_value v; checked(deal_undefined(&v)); return v; }
static inline deal_value boolean(int b) { deal_value v; checked(deal_boolean(b, &v)); return v; }
static inline double as_number(deal_value v) { double n; checked(deal_as_number(v, &n)); return n; }
static inline int as_boolean(deal_value v) { int b; checked(deal_as_boolean(v, &b)); return b; }
static inline deal_host_result result(deal_value v) { deal_host_result r = {0}; r.value = v; return r; }
static inline deal_host_result host_error(const char *code, const char *message) {
    deal_host_result r = {0}; r.failed = 1; r.failure.kind = DEAL_FAILURE_HOST_TEXT;
    r.failure.code = code; r.failure.message = message; return r;
}
static inline deal_value array(const char *descriptor, int64_t n) {
    deal_value v; checked(deal_array_new_typed(n, descriptor, &v)); return v;
}
static inline deal_value at(deal_value v, int64_t i) { deal_value x; checked(deal_array_get(v, i, &x)); return x; }
static inline deal_value get(deal_value v, const char *key) { deal_value x; checked(deal_table_get(v,key,strlen(key),&x)); return x; }
static inline void set(deal_value v, const char *key, deal_value x) { checked(deal_table_set(v,key,strlen(key),x)); }
static inline deal_value field(deal_value v, const char *key) { deal_value x; checked(deal_field_get(v,key,&x)); return x; }
static inline deal_host_result apply(deal_value fn, const deal_value *args, size_t n) {
    deal_host_result r; checked(deal_apply(fn,args,n,&r)); return r;
}
static inline deal_host_result complete(deal_async_token token, deal_value value) {
    checked(deal_async_complete_value(token,value)); deal_value op;
    checked(deal_async_operation(token,&op)); return result(op);
}
static inline deal_value retained(deal_handle handle, const char *descriptor) {
    deal_value value; checked(deal_handle_value(handle,descriptor,&value)); return value;
}
#endif
