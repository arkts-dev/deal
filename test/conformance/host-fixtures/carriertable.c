#include "host/carriertable.h"
#include "native_helpers.h"
#include <stdio.h>
static deal_handle last_table,last_widget,last_action;
static deal_host_result step(const deal_value *args,size_t argc){(void)args;(void)argc;return result(number(42));}
static deal_value function(void){deal_value v;checked(deal_function_new(step,"(number)->number",&v));return v;}
static deal_value widget(const char *label,double weight){deal_value v;checked(deal_host_carriertable_Widget_new(&v));checked(deal_field_set(v,"label",text(label)));checked(deal_field_set(v,"weight",number(weight)));checked(deal_field_set(v,"action",function()));return v;}
static void copy_string(deal_value value,char *out,size_t capacity){const void *p;size_t n;checked(deal_as_string(value,&p,&n));if(n>=capacity)abort();memcpy(out,p,n);out[n]=0;}
static void callback_text(deal_value callback,char *out,size_t capacity){deal_value_kind kind;checked(deal_kind_of(callback,&kind));if(kind!=DEAL_KIND_FUNCTION){if(capacity<5u)abort();memcpy(out,"none",5u);return;}deal_value arg=number(41);deal_host_result r=apply(callback,&arg,1u);if(r.failed){char code[128];copy_string(field(r.value,"code"),code,sizeof code);snprintf(out,capacity,"false:%s",code);checked(deal_failure_discard(r.failure.error));}else snprintf(out,capacity,"true:%.17g",as_number(r.value));}
static deal_host_result impl_describeAsync(deal_async_token token,const deal_value *args,size_t argc){(void)argc;char label[256],g[256],buffer[600];copy_string(field(args[0],"label"),label,sizeof label);callback_text(args[1],g,sizeof g);int n=snprintf(buffer,sizeof buffer,"W|%s|%.17g|%s",label,as_number(field(args[0],"weight")),g);if(n<0||(size_t)n>=sizeof buffer)abort();return complete(token,text(buffer));}
static deal_value make_table(void){deal_value v,w=widget("made",9),f=function();checked(deal_table_new(&v));set(v,"widget",w);set(v,"action",f);if(last_table)checked(deal_release(last_table));if(last_widget)checked(deal_release(last_widget));if(last_action)checked(deal_release(last_action));checked(deal_retain(v,&last_table));checked(deal_retain(w,&last_widget));checked(deal_retain(f,&last_action));return v;}
static deal_host_result impl_makeTable(const deal_value *args,size_t argc){(void)args;(void)argc;return result(make_table());}
static deal_host_result impl_tableIntact(const deal_value *args,size_t argc){(void)args;(void)argc;if(!last_table)return result(boolean(0));deal_value t=retained(last_table,"record"),w=retained(last_widget,DEAL_HOST_CLASS_ATOM_CARRIERTABLE_WIDGET),f=retained(last_action,"(number)->number"),tw=get(t,"widget"),tf=get(t,"action");int same_w,same_f;checked(deal_same(w,tw,&same_w));checked(deal_same(f,tf,&same_f));const char *atom;checked(deal_class_identity(tw,&atom));const char *sig;checked(deal_function_signature(tf,&sig));return result(boolean(same_w&&same_f&&!strcmp(atom,DEAL_HOST_CLASS_ATOM_CARRIERTABLE_WIDGET)&&!strcmp(sig,"(number)->number")));}
static deal_host_result impl_describeTable(const deal_value *args,size_t argc){(void)argc;deal_value p=get(args[0],"point"),t=get(args[0],"twin");int same;checked(deal_same(p,t,&same));deal_value_kind kind;checked(deal_kind_of(p,&kind));const char *atom="";char fields[256]="-",g[256],buffer[600];int classed=0;if(kind==DEAL_KIND_CLASS){checked(deal_class_identity(p,&atom));size_t n=strlen(atom);classed=n>=6u&&!strcmp(atom+n-6u,"/Point");snprintf(fields,sizeof fields,"%.17g,%.17g",as_number(field(p,"x")),as_number(field(p,"y")));}callback_text(get(args[0],"step"),g,sizeof g);int n=snprintf(buffer,sizeof buffer,"T|%s|%s|%s|%s",classed?"true":"false",fields,same?"true":"false",g);if(n<0||(size_t)n>=sizeof buffer)abort();return result(text(buffer));}
static deal_host_result impl_makeBoard(const deal_value *args,size_t argc){(void)args;(void)argc;deal_value v,meta,xs=array(DEAL_HOST_CLASS_ATOM_CARRIERTABLE_WIDGET,1);checked(deal_host_carriertable_Board_new(&v));checked(deal_table_new(&meta));set(meta,"widget",widget("w1",5));set(meta,"action",function());checked(deal_array_set(xs,0,widget("w0",9)));checked(deal_field_set(v,"title",text("board")));checked(deal_field_set(v,"widgets",xs));checked(deal_field_set(v,"meta",meta));return result(v);}
const deal_host_field deal_host_fields_carriertable_Widget[] = {
  {"label", "string", 0},
  {"weight", "number", 0},
  {"action", "(number)->number", 0},
};
const deal_host_class deal_host_class_carriertable_Widget = {DEAL_HOST_CLASS_ATOM_CARRIERTABLE_WIDGET, deal_host_fields_carriertable_Widget, 3u};
const deal_host_field deal_host_fields_carriertable_Board[] = {
  {"title", "string", 0},
  {"widgets", "[" DEAL_HOST_CLASS_ATOM_CARRIERTABLE_WIDGET "]", 0},
  {"meta", "record", 0},
};
const deal_host_class deal_host_class_carriertable_Board = {DEAL_HOST_CLASS_ATOM_CARRIERTABLE_BOARD, deal_host_fields_carriertable_Board, 3u};
const deal_host_class deal_host_classes_carriertable[] = {
  {DEAL_HOST_CLASS_ATOM_CARRIERTABLE_WIDGET, deal_host_fields_carriertable_Widget, 3u},
  {DEAL_HOST_CLASS_ATOM_CARRIERTABLE_BOARD, deal_host_fields_carriertable_Board, 3u},
};
const deal_host_export deal_host_exports_carriertable[] = {
  {"Widget", DEAL_HOST_CLASS_ATOM_CARRIERTABLE_WIDGET, DEAL_HOST_EXPORT_CLASS, 0, NULL, NULL, &deal_host_classes_carriertable[0]},
  {"Board", DEAL_HOST_CLASS_ATOM_CARRIERTABLE_BOARD, DEAL_HOST_EXPORT_CLASS, 0, NULL, NULL, &deal_host_classes_carriertable[1]},
  {"describeAsync", "async(" DEAL_HOST_CLASS_ATOM_CARRIERTABLE_WIDGET ",(number)->number)->string", DEAL_HOST_EXPORT_ASYNC_FUNCTION, 1, NULL, impl_describeAsync, NULL},
  {"makeTable", "()->record", DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeTable, NULL, NULL},
  {"tableIntact", "()->boolean", DEAL_HOST_EXPORT_FUNCTION, 0, impl_tableIntact, NULL, NULL},
  {"describeTable", "(record)->string", DEAL_HOST_EXPORT_FUNCTION, 0, impl_describeTable, NULL, NULL},
  {"makeBoard", "()->" DEAL_HOST_CLASS_ATOM_CARRIERTABLE_BOARD, DEAL_HOST_EXPORT_FUNCTION, 0, impl_makeBoard, NULL, NULL},
};
const deal_host_module deal_host_module_carriertable = {DEAL_HOST_SPECIFIER_CARRIERTABLE, deal_host_exports_carriertable, sizeof deal_host_exports_carriertable / sizeof deal_host_exports_carriertable[0], deal_host_classes_carriertable, 2u, NULL};
