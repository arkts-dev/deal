#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct Pair {
  int32_t left;
  int32_t right;
} Pair;

typedef struct Handle {
  int32_t value;
} Handle;

int32_t ffi_add(int32_t a, int32_t b) { return a + b; }
double ffi_half(double value) { return value / 2.0; }
bool ffi_not(bool value) { return !value; }
int32_t ffi_strlen(const char *value) { return (int32_t)strlen(value); }
const char *ffi_echo(const char *value) { return value; }
int32_t ffi_bytes_sum(const uint8_t *value, int32_t length) {
  int32_t sum = length;
  for (int32_t i = 0; i < length; i++) sum = sum * 257 + value[i];
  return sum;
}
Pair ffi_pair_swap(Pair value) { value.left += 1000; value.right += 2000; return value; }
int32_t ffi_pair_sum(Pair value) { return value.left + value.right; }
void *ffi_handle_new(int32_t value) {
  Handle *handle = malloc(sizeof(Handle));
  if (handle != NULL) handle->value = value;
  return handle;
}
int32_t ffi_handle_value(void *value) { return ((Handle *)value)->value; }
void ffi_handle_free(void *value) { free(value); }
const char *ffi_null_string(void) { return NULL; }
const char *ffi_invalid_utf8(void) { static const char value[] = { (char)0xC3, (char)0x28, 0 }; return value; }
void *ffi_null_handle(void) { return NULL; }
int32_t ffi_identity_int(int32_t value) { return value; }
void ffi_noop(void) {}
static char borrowed_string[] = "first";
const char *ffi_borrowed_string(void) { return borrowed_string; }
void ffi_overwrite_borrowed_string(void) { memcpy(borrowed_string, "other", 6); }
