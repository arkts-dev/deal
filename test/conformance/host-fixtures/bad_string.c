#include "host/bad_string.h"
#include "native_helpers.h"
static deal_value lone_unit(int embedded){deal_value v;const char *s=embedded?"a\xed\xa0\x80" "b":"\xed\xa0\x80";checked(deal_string_new(s,embedded?5u:3u,&v));return v;}
static deal_value strings(void){deal_value v=array("string",1);checked(deal_array_set(v,0,text("ok")));return v;}
static deal_value nested(void){deal_value v=array("[string]",1);checked(deal_array_set(v,0,strings()));return v;}
static deal_value shared(void){deal_value v=array("[string]",2),s=strings();checked(deal_array_set(v,0,s));checked(deal_array_set(v,1,s));return v;}
static deal_value bad_tail(void){deal_value v=array("[string]",2);checked(deal_array_set(v,0,strings()));checked(deal_array_set(v,1,text("not an array")));return v;}
static deal_host_result impl_nativeBadStringArray(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value v=array("string",2);checked(deal_array_set(v,0,text("ok")));deal_value malformed;checked(deal_string_new("\xe2\x82",2u,&malformed));checked(deal_array_set(v,1,malformed));return result(v);}
static deal_host_result impl_rawNullableArrayArray(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value v=array("?[string]",2);checked(deal_array_set(v,0,null_value()));checked(deal_array_set(v,1,strings()));return result(v);}
static deal_host_result impl_nativeDifferingDepthStringArray(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value s=strings(),d=array("[string]",1),c=array("[[string]]",1),e=array("[[[string]]]",1),v=array("[[string]]",2);checked(deal_array_set(d,0,s));checked(deal_array_set(c,0,d));checked(deal_array_set(e,0,c));checked(deal_array_set(v,0,c));checked(deal_array_set(v,1,e));return result(v);}
static deal_host_result impl_badString(const deal_value *args, size_t argc) { (void)args; (void)argc; deal_value v; checked(deal_string_new("a\xff" "b",3u,&v)); return result(v); }
static deal_host_result impl_surrogateString(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(lone_unit(0)); }
static deal_host_result impl_embeddedSurrogateString(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(lone_unit(1)); }
static deal_host_result impl_validString(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(text("ok")); }
static deal_host_result impl_rawStringArray(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(strings()); }
static deal_host_result impl_rawNestedStringArray(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(nested()); }
static deal_host_result impl_rawNullableStringArray(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(strings()); }
static deal_host_result impl_sharedNestedStringArray(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(shared()); }
static deal_host_result impl_badNestedTail(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(bad_tail()); }
static deal_host_result impl_nativeBadNestedTail(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(bad_tail()); }
static deal_host_result impl_nativeStringArray(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(strings()); }
static deal_host_result impl_nativeNestedStringArray(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(nested()); }
static deal_host_result impl_nativeSharedNestedStringArray(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(shared()); }

const deal_host_export deal_host_exports_bad_string[] = {
  {"badString", "()->string", DEAL_HOST_EXPORT_FUNCTION, 0, impl_badString, NULL, NULL},
  {"surrogateString", "()->string", DEAL_HOST_EXPORT_FUNCTION, 0, impl_surrogateString, NULL, NULL},
  {"embeddedSurrogateString", "()->string", DEAL_HOST_EXPORT_FUNCTION, 0, impl_embeddedSurrogateString, NULL, NULL},
  {"validString", "()->string", DEAL_HOST_EXPORT_FUNCTION, 0, impl_validString, NULL, NULL},
  {"rawStringArray", "()->[string]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_rawStringArray, NULL, NULL},
  {"rawNestedStringArray", "()->[[string]]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_rawNestedStringArray, NULL, NULL},
  {"rawNullableStringArray", "()->[?string]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_rawNullableStringArray, NULL, NULL},
  {"rawNullableArrayArray", "()->[?[string]]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_rawNullableArrayArray, NULL, NULL},
  {"sharedNestedStringArray", "()->[[string]]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_sharedNestedStringArray, NULL, NULL},
  {"badNestedTail", "()->[[string]]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_badNestedTail, NULL, NULL},
  {"nativeBadStringArray", "()->[string]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_nativeBadStringArray, NULL, NULL},
  {"nativeBadNestedTail", "()->[[string]]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_nativeBadNestedTail, NULL, NULL},
  {"nativeStringArray", "()->[string]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_nativeStringArray, NULL, NULL},
  {"nativeNestedStringArray", "()->[[string]]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_nativeNestedStringArray, NULL, NULL},
  {"nativeSharedNestedStringArray", "()->[[string]]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_nativeSharedNestedStringArray, NULL, NULL},
  {"nativeDifferingDepthStringArray", "()->[[[[string]]]]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_nativeDifferingDepthStringArray, NULL, NULL},
};
const deal_host_module deal_host_module_bad_string = {DEAL_HOST_SPECIFIER_BAD_STRING, deal_host_exports_bad_string, sizeof deal_host_exports_bad_string / sizeof deal_host_exports_bad_string[0], NULL, 0u, NULL};
