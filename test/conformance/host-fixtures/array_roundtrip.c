#include "host/array_roundtrip.h"
#include "native_helpers.h"
static double calls; static deal_handle shared;
static deal_host_result impl_echoArray(const deal_value *args,size_t argc){(void)argc;++calls;return result(args[0]);}
static deal_host_result impl_nullableArray(const deal_value *args,size_t argc){(void)argc;++calls;return result(args[0]);}
static deal_host_result impl_makeArray(const deal_value *args,size_t argc){(void)argc;++calls;int64_t n=(int64_t)as_number(args[0]);deal_value a=array("number",n);for(int64_t i=0;i<n;++i)checked(deal_array_set(a,i,number(0)));return result(a);}
static deal_host_result impl_sharedArray(const deal_value *args,size_t argc){(void)args;(void)argc;if(shared==0){deal_value a=array("number",2);checked(deal_array_set(a,0,number(0)));checked(deal_array_set(a,1,number(0)));checked(deal_retain(a,&shared));}return result(retained(shared,"[number]"));}
static deal_host_result impl_readElement(const deal_value *args,size_t argc){(void)argc;++calls;return result(at(args[0],0));}
static deal_host_result impl_callCount(const deal_value *args, size_t argc) { (void)args; (void)argc; return result(number(calls)); }
static deal_host_result impl_badArrayReturn(const deal_value *args,size_t argc){(void)args;(void)argc;++calls;return result(text("not-array"));}
const deal_host_export deal_host_exports_array_roundtrip[] = {
  {"echoArray", "([number])->[number]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_echoArray, NULL, NULL},
  {"nullableArray", "(?[number])->?[number]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_nullableArray, NULL, NULL},
  {"makeArray", "(number)->[number]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeArray, NULL, NULL},
  {"sharedArray", "()->[number]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_sharedArray, NULL, NULL},
  {"readElement", "([number])->number", DEAL_HOST_EXPORT_FUNCTION, 0, impl_readElement, NULL, NULL},
  {"callCount", "()->number", DEAL_HOST_EXPORT_FUNCTION, 0, impl_callCount, NULL, NULL},
  {"badArrayReturn", "()->[number]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_badArrayReturn, NULL, NULL},
};
const deal_host_module deal_host_module_array_roundtrip = {DEAL_HOST_SPECIFIER_ARRAY_ROUNDTRIP, deal_host_exports_array_roundtrip, sizeof deal_host_exports_array_roundtrip / sizeof deal_host_exports_array_roundtrip[0], NULL, 0u, NULL};
