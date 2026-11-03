#include "host/jsonabletable.h"
#include "native_hostile.h"
#include <math.h>
#include "deal/native/json.h"
#include "deal/native/container.h"
static deal_value table(void){deal_value v;checked(deal_table_new(&v));return v;}
static deal_value leaf(double n){deal_value v=table();set(v,"a",number(n));return v;}
static deal_value bad_string(void){deal_value v;checked(deal_string_new("\xed\xa0\x80",3u,&v));return v;}
static deal_host_result impl_makeTable(const deal_value *args,size_t argc){(void)args;(void)argc;return result(leaf(1));}
static deal_host_result impl_makeNested(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value v=table(),xs=raw_array(2);set(v,"inner",leaf(1));checked(deal_array_set(xs,0,number(10)));checked(deal_array_set(xs,1,number(20)));set(v,"xs",xs);return result(v);}
static deal_host_result impl_makeParsed(const deal_value *args,size_t argc){
    (void)args; (void)argc;
    deal_value source=text("{\"v\":0.1,\"w\":1e17}"), v=table();
    deal_check_input input, target; deal_error *cause=NULL;
    checked(deal_host_value_presentation(source,&input,&cause));
    checked(deal_host_value_presentation(v,&target,&cause));
    const deal_origin origin={"host/jsonabletable",1,1};
    deal_outcome_data parsed=deal_json_parse(input.datum.reference,&origin);
    if(parsed.error!=NULL||parsed.value.tag!=DEAL_DATA_TABLE)abort();
    deal_root_scope_push(); (void)deal_root_scope_hold(parsed.value.reference);
    deal_table *decoded=parsed.value.reference;
    for(size_t i=0;i<deal_container_table_count(decoded);i++){
        deal_string *key=deal_container_table_key_at(decoded,i);
        deal_data value=deal_container_table_read(decoded,key);
        deal_container_table_write(target.datum.reference,key,&value);
    }
    deal_root_scope_pop(); return result(v);
}
static deal_host_result impl_makeCycle(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value v=table();set(v,"self",v);return result(v);}
static deal_host_result impl_makeInfinite(const deal_value *args,size_t argc){(void)args;(void)argc;return result(leaf(INFINITY));}
static deal_host_result impl_makeNotANumber(const deal_value *args,size_t argc){(void)args;(void)argc;return result(leaf(NAN));}
static deal_host_result impl_makeNestedInfinite(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value v=table();set(v,"inner",leaf(INFINITY));return result(v);}
static deal_host_result impl_makeInfiniteElements(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value v=table(),xs=raw_array(2);checked(deal_array_set(xs,0,number(INFINITY)));checked(deal_array_set(xs,1,number(2)));set(v,"xs",xs);return result(v);}
static deal_value bad_key(void){deal_value v=table();set(v,"key",number(1));fixture_bad_key(v);return v;}
static deal_host_result impl_makeBadKey(const deal_value *args,size_t argc){(void)args;(void)argc;return result(bad_key());}
static deal_host_result impl_makeNestedBadKey(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value v=table();set(v,"inner",bad_key());return result(v);}
static deal_host_result impl_makeBadString(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value v=table();set(v,"a",bad_string());return result(v);}

const deal_host_export deal_host_exports_jsonabletable[] = {
  {"makeTable", "()->record", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeTable, NULL, NULL},
  {"makeNested", "()->record", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeNested, NULL, NULL},
  {"makeParsed", "()->record", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeParsed, NULL, NULL},
  {"makeCycle", "()->record", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeCycle, NULL, NULL},
  {"makeInfinite", "()->record", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeInfinite, NULL, NULL},
  {"makeNotANumber", "()->record", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeNotANumber, NULL, NULL},
  {"makeNestedInfinite", "()->record", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeNestedInfinite, NULL, NULL},
  {"makeInfiniteElements", "()->record", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeInfiniteElements, NULL, NULL},
  {"makeBadKey", "()->record", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeBadKey, NULL, NULL},
  {"makeNestedBadKey", "()->record", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeNestedBadKey, NULL, NULL},
  {"makeBadString", "()->record", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeBadString, NULL, NULL},
};
const deal_host_module deal_host_module_jsonabletable = {DEAL_HOST_SPECIFIER_JSONABLETABLE, deal_host_exports_jsonabletable, sizeof deal_host_exports_jsonabletable / sizeof deal_host_exports_jsonabletable[0], NULL, 0u, NULL};
