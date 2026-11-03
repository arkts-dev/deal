#include "host/carrieruntyped.h"
#include "native_helpers.h"
#include <stdio.h>

static deal_value copy_table(deal_value source) {
    deal_check_input input; deal_error *cause=NULL;
    checked(deal_host_value_presentation(source,&input,&cause));
    if(input.datum.tag!=DEAL_DATA_TABLE)abort();
    deal_value out; checked(deal_table_new(&out));
    deal_table *table=input.datum.reference;
    for(size_t i=0;i<deal_container_table_count(table);i++){
        deal_string *key=deal_container_table_key_at(table,i);
        const void *bytes=deal_text_bytes(key);size_t n=deal_text_byte_length(key);
        deal_value value;checked(deal_table_get(source,bytes,n,&value));
        checked(deal_table_set(out,bytes,n,value));
    }
    return out;
}
static void copy_string(deal_value value,char *out,size_t capacity){
    const void *p;size_t n;checked(deal_as_string(value,&p,&n));
    if(n>=capacity)abort();memcpy(out,p,n);out[n]=0;
}
static void point_report(deal_value point,char *out,size_t capacity){
    deal_value_kind kind;checked(deal_kind_of(point,&kind));
    if(kind!=DEAL_KIND_CLASS){snprintf(out,capacity,"false|-");return;}
    const char *atom;checked(deal_class_identity(point,&atom));size_t n=strlen(atom);
    int classed=n>=6u&&!strcmp(atom+n-6u,"/Point");
    snprintf(out,capacity,"%s|%.17g,%.17g",classed?"true":"false",
        as_number(field(point,"x")),as_number(field(point,"y")));
}
static deal_host_result impl_roundTrip(const deal_value *args,size_t argc){
    (void)argc;char point[256],step[256]="none",error[512]="none",buffer[1100];
    deal_value p=get(args[0],"point"),f=get(args[0],"step"),e=get(args[0],"err");
    point_report(p,point,sizeof point);int same;checked(deal_same(p,get(args[0],"twin"),&same));
    deal_value_kind kind;checked(deal_kind_of(f,&kind));
    if(kind==DEAL_KIND_FUNCTION){deal_value arg=number(41);deal_host_result r=apply(f,&arg,1u);
        if(r.failed){char code[128];copy_string(field(r.value,"code"),code,sizeof code);
            snprintf(step,sizeof step,"false:%s",code);checked(deal_failure_discard(r.failure.error));}
        else snprintf(step,sizeof step,"true:%.17g",as_number(r.value));}
    checked(deal_kind_of(e,&kind));if(kind==DEAL_KIND_CLASS){const char *atom;checked(deal_class_identity(e,&atom));
        if(!strcmp(atom,"Error")){char code[128],message[256];copy_string(field(e,"code"),code,sizeof code);
            copy_string(field(e,"message"),message,sizeof message);snprintf(error,sizeof error,"true:%s:%s",code,message);}}
    int n=snprintf(buffer,sizeof buffer,"%s|%s|%s|%s",point,same?"true":"false",step,error);
    if(n<0||(size_t)n>=sizeof buffer)abort();deal_value out=copy_table(args[0]);set(out,"observed",text(buffer));return result(out);
}
static deal_host_result impl_roundTripArray(const deal_value *args,size_t argc){
    (void)argc;int64_t count;checked(deal_array_length(args[0],&count));deal_value out=array("record",count);
    deal_value first=count?get(at(args[0],0),"point"):null_value();
    for(int64_t i=0;i<count;i++){deal_value item=at(args[0],i),p=get(item,"point"),entry=copy_table(item);
        char point[256],buffer[300];point_report(p,point,sizeof point);int same;checked(deal_same(p,first,&same));
        int n=snprintf(buffer,sizeof buffer,"%s|%s",point,i==0?"self":same?"true":"false");
        if(n<0||(size_t)n>=sizeof buffer)abort();set(entry,"observed",text(buffer));checked(deal_array_set(out,i,entry));}
    return result(out);
}
const deal_host_export deal_host_exports_carrieruntyped[] = {
  {"roundTrip", "(record)->record", DEAL_HOST_EXPORT_FUNCTION, 0, impl_roundTrip, NULL, NULL},
  {"roundTripArray", "([record])->[record]", DEAL_HOST_EXPORT_FUNCTION, 0, impl_roundTripArray, NULL, NULL},
};
const deal_host_module deal_host_module_carrieruntyped = {DEAL_HOST_SPECIFIER_CARRIERUNTYPED, deal_host_exports_carrieruntyped, sizeof deal_host_exports_carrieruntyped / sizeof deal_host_exports_carrieruntyped[0], NULL, 0u, NULL};
