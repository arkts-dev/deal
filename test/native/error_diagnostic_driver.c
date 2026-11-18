/* Bounded terminal projection of existing Error metadata, not language values. */
#include "deal/native/error.h"
#include "deal/native/memory.h"
#include <stdio.h>
#include <string.h>

int main(void) {
    deal_root_scope_push();
    for (unsigned int i = 0; i < 40u; i += 1u) deal_call_frame_push("frame");
    char message[8200];
    memset(message, 'x', sizeof message);
    /* The byte cap lands inside this four-byte sequence. */
    memcpy(message + 8190, "\xF0\x9F\x98\x80", 4u);
    message[8194] = 0;
    deal_error *root = deal_error_create("Root", message, NULL);
    (void)deal_root_scope_hold(root);
    for (unsigned int i = 0; i < 40u; i += 1u) deal_call_frame_pop();
    deal_error *previous = root;
    for (unsigned int i = 0; i < 10u; i += 1u) {
        deal_error *next = deal_error_create("Cause", "leaf", NULL);
        (void)deal_root_scope_hold(next);
        deal_error_set_cause(previous, next);
        previous = next;
    }
    char *text = deal_error_diagnostic(root);
    puts(text);
    deal_error_snapshot_release(text);
    /* A cause cycle is bounded without changing identity or requiring GC work. */
    deal_error_set_cause(root, root);
    text = deal_error_diagnostic(root);
    puts(text);
    deal_error_snapshot_release(text);
    deal_root_scope_pop();
    return 0;
}
