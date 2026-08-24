/*
 * DEALPG4 v4 protocol core.
 * Implementation of the canonical grammar, the 26-type record catalog
 * with per-type field validation and size caps, the framing-vs-
 * record-level validation split, serialization, and the getrandom(2)
 * nonce helper. See protocol.h for the contracts
 * (dealpg4-launcher-artifact-and-integrity D5/D6,
 * dealpg4-protocol-core D1-D10).
 */
#define _POSIX_C_SOURCE 200809L

#include "protocol.h"

#include <errno.h>
#include <limits.h>
#include <string.h>
#include <sys/random.h>
#include <unistd.h>

/* === Field classes ===================================================== */

typedef enum dealpg4_field_class {
    DEALPG4_F_DECIMAL,        /* 1*DIGIT, value <= INT64_MAX */
    DEALPG4_F_NONCE,          /* exactly 32 lowercase hex chars */
    DEALPG4_F_HEX,            /* even-length (>= 2) lowercase hex */
    DEALPG4_F_TOKEN,          /* [A-Za-z_]+ */
    DEALPG4_F_DASH_OR_TOKEN,  /* "-" or a token */
    DEALPG4_F_STREAM,         /* "out" | "err" */
    DEALPG4_F_FINAL,          /* "success" | "cancelled" */
    DEALPG4_F_OUTER_STATUS,   /* "clean" | "failed" */
    DEALPG4_F_VERSION_4,      /* decimal, value exactly 4 */
    DEALPG4_F_BIT             /* exactly "0" or "1" */
} dealpg4_field_class;

/* === Catalog =========================================================== */

static const dealpg4_field_class classes_hello[] = {
    DEALPG4_F_NONCE
};
static const dealpg4_field_class classes_hello_ok[] = {
    DEALPG4_F_VERSION_4, DEALPG4_F_DECIMAL
};
static const dealpg4_field_class classes_feature_ready[] = {
    DEALPG4_F_NONCE
};
static const dealpg4_field_class classes_ready_ack[] = {
    DEALPG4_F_NONCE
};
static const dealpg4_field_class classes_invoke[] = {
    DEALPG4_F_TOKEN, DEALPG4_F_HEX, DEALPG4_F_DECIMAL
};
static const dealpg4_field_class classes_invoked[] = {
    DEALPG4_F_DECIMAL, DEALPG4_F_TOKEN
};
static const dealpg4_field_class classes_reject[] = {
    DEALPG4_F_DECIMAL, DEALPG4_F_DASH_OR_TOKEN, DEALPG4_F_TOKEN
};
static const dealpg4_field_class classes_stub_forked[] = {
    DEALPG4_F_DECIMAL, DEALPG4_F_DECIMAL
};
static const dealpg4_field_class classes_stub_identity[] = {
    DEALPG4_F_DECIMAL, DEALPG4_F_DECIMAL, DEALPG4_F_DECIMAL,
    DEALPG4_F_NONCE
};
static const dealpg4_field_class classes_stub_failed[] = {
    DEALPG4_F_DECIMAL, DEALPG4_F_DECIMAL
};
static const dealpg4_field_class classes_stub_exec_failed[] = {
    DEALPG4_F_DECIMAL, DEALPG4_F_DECIMAL
};
static const dealpg4_field_class classes_release_recv[] = {
    DEALPG4_F_DECIMAL
};
static const dealpg4_field_class classes_stub_ready[] = {
    DEALPG4_F_DECIMAL, DEALPG4_F_DECIMAL, DEALPG4_F_DECIMAL,
    DEALPG4_F_DECIMAL, DEALPG4_F_NONCE
};
static const dealpg4_field_class classes_coord_ready[] = {
    DEALPG4_F_DECIMAL, DEALPG4_F_DECIMAL, DEALPG4_F_DECIMAL
};
static const dealpg4_field_class classes_coord_exec_failed[] = {
    DEALPG4_F_DECIMAL, DEALPG4_F_DECIMAL
};
static const dealpg4_field_class classes_ack[] = {
    DEALPG4_F_DECIMAL, DEALPG4_F_NONCE
};
static const dealpg4_field_class classes_started[] = {
    DEALPG4_F_DECIMAL
};
static const dealpg4_field_class classes_exec_failed[] = {
    DEALPG4_F_DECIMAL, DEALPG4_F_DECIMAL
};
static const dealpg4_field_class classes_out[] = {
    DEALPG4_F_DECIMAL, DEALPG4_F_STREAM, DEALPG4_F_HEX
};
static const dealpg4_field_class classes_out_end[] = {
    DEALPG4_F_DECIMAL, DEALPG4_F_STREAM
};
static const dealpg4_field_class classes_cancel[] = {
    DEALPG4_F_DECIMAL, DEALPG4_F_NONCE
};
/* REPORT fixed order: exitCode termSignal elapsedMs startupMs execMs
 * termMs killMs proofMs finalMs reapCount adoptCount stdoutBytes
 * stderrBytes stdoutTruncated stderrTruncated groupProof sessionProof
 * drainEof failureToken (failureToken "-" or a named token). */
static const dealpg4_field_class classes_report[] = {
    DEALPG4_F_DECIMAL, DEALPG4_F_DECIMAL, DEALPG4_F_DECIMAL,
    DEALPG4_F_DECIMAL, DEALPG4_F_DECIMAL, DEALPG4_F_DECIMAL,
    DEALPG4_F_DECIMAL, DEALPG4_F_DECIMAL, DEALPG4_F_DECIMAL,
    DEALPG4_F_DECIMAL, DEALPG4_F_DECIMAL, DEALPG4_F_DECIMAL,
    DEALPG4_F_DECIMAL, DEALPG4_F_BIT, DEALPG4_F_BIT, DEALPG4_F_BIT,
    DEALPG4_F_BIT, DEALPG4_F_BIT, DEALPG4_F_DASH_OR_TOKEN
};
static const dealpg4_field_class classes_clean[] = {
    DEALPG4_F_DECIMAL, DEALPG4_F_FINAL
};
static const dealpg4_field_class classes_failed[] = {
    DEALPG4_F_DECIMAL, DEALPG4_F_TOKEN
};
static const dealpg4_field_class classes_done[] = {
    DEALPG4_F_OUTER_STATUS
};
typedef struct dealpg4_catalog_entry {
    const char *name;
    const dealpg4_field_class *classes;
    size_t fixed_count;    /* exact field count; INVOKE: the 3 fixed
                              fields, argv tail on top */
    int exact;             /* 1: total fields must equal fixed_count;
                              0: INVOKE (total >= fixed_count, argv tail
                              is F_HEX) */
    unsigned int direction;
} dealpg4_catalog_entry;

static const dealpg4_catalog_entry catalog[DEALPG4_REC_COUNT] = {
    [DEALPG4_REC_HELLO] = {
        "HELLO", classes_hello, 1, 1, DEALPG4_DIR_COORD_TO_OUTER
    },
    [DEALPG4_REC_HELLO_OK] = {
        "HELLO_OK", classes_hello_ok, 2, 1, DEALPG4_DIR_OUTER_TO_COORD
    },
    [DEALPG4_REC_FEATURE_READY] = {
        "FEATURE_READY", classes_feature_ready, 1, 1,
        DEALPG4_DIR_COORD_TO_OUTER
    },
    [DEALPG4_REC_READY_ACK] = {
        "READY_ACK", classes_ready_ack, 1, 1, DEALPG4_DIR_OUTER_TO_COORD
    },
    [DEALPG4_REC_INVOKE] = {
        "INVOKE", classes_invoke, 3, 0, DEALPG4_DIR_COORD_TO_OUTER
    },
    [DEALPG4_REC_INVOKED] = {
        "INVOKED", classes_invoked, 2, 1, DEALPG4_DIR_OUTER_TO_COORD
    },
    [DEALPG4_REC_REJECT] = {
        "REJECT", classes_reject, 3, 1,
        DEALPG4_DIR_OUTER_TO_COORD | DEALPG4_DIR_NESTED_TO_OUTER
    },
    [DEALPG4_REC_STUB_FORKED] = {
        "STUB_FORKED", classes_stub_forked, 2, 1,
        DEALPG4_DIR_NESTED_TO_OUTER
    },
    [DEALPG4_REC_STUB_IDENTITY] = {
        "STUB_IDENTITY", classes_stub_identity, 4, 1,
        DEALPG4_DIR_STUB_TO_SUPERVISOR
    },
    [DEALPG4_REC_STUB_FAILED] = {
        "STUB_FAILED", classes_stub_failed, 2, 1,
        DEALPG4_DIR_STUB_TO_SUPERVISOR
    },
    [DEALPG4_REC_STUB_EXEC_FAILED] = {
        "STUB_EXEC_FAILED", classes_stub_exec_failed, 2, 1,
        DEALPG4_DIR_STUB_TO_SUPERVISOR
    },
    [DEALPG4_REC_RELEASE_RECV] = {
        "RELEASE_RECV", classes_release_recv, 1, 1,
        DEALPG4_DIR_STUB_TO_SUPERVISOR
    },
    [DEALPG4_REC_STUB_READY] = {
        "STUB_READY", classes_stub_ready, 5, 1,
        DEALPG4_DIR_NESTED_TO_OUTER | DEALPG4_DIR_OUTER_TO_COORD
    },
    [DEALPG4_REC_COORD_READY] = {
        "COORD_READY", classes_coord_ready, 3, 1,
        DEALPG4_DIR_CHILD_TO_OUTER
    },
    [DEALPG4_REC_COORD_EXEC_FAILED] = {
        "COORD_EXEC_FAILED", classes_coord_exec_failed, 2, 1,
        DEALPG4_DIR_CHILD_TO_OUTER
    },
    [DEALPG4_REC_ACK] = {
        "ACK", classes_ack, 2, 1,
        DEALPG4_DIR_COORD_TO_OUTER | DEALPG4_DIR_OUTER_TO_NESTED
    },
    [DEALPG4_REC_STARTED] = {
        "STARTED", classes_started, 1, 1,
        DEALPG4_DIR_NESTED_TO_OUTER | DEALPG4_DIR_OUTER_TO_COORD
    },
    [DEALPG4_REC_EXEC_FAILED] = {
        "EXEC_FAILED", classes_exec_failed, 2, 1,
        DEALPG4_DIR_NESTED_TO_OUTER | DEALPG4_DIR_OUTER_TO_COORD
    },
    [DEALPG4_REC_OUT] = {
        "OUT", classes_out, 3, 1,
        DEALPG4_DIR_NESTED_TO_OUTER | DEALPG4_DIR_OUTER_TO_COORD
    },
    [DEALPG4_REC_OUT_END] = {
        "OUT_END", classes_out_end, 2, 1,
        DEALPG4_DIR_NESTED_TO_OUTER | DEALPG4_DIR_OUTER_TO_COORD
    },
    [DEALPG4_REC_CANCEL] = {
        "CANCEL", classes_cancel, 2, 1,
        DEALPG4_DIR_COORD_TO_OUTER | DEALPG4_DIR_OUTER_TO_NESTED
    },
    [DEALPG4_REC_REPORT] = {
        "REPORT", classes_report, 19, 1,
        DEALPG4_DIR_NESTED_TO_OUTER | DEALPG4_DIR_OUTER_TO_COORD
    },
    [DEALPG4_REC_CLEAN] = {
        "CLEAN", classes_clean, 2, 1,
        DEALPG4_DIR_NESTED_TO_OUTER | DEALPG4_DIR_OUTER_TO_COORD
    },
    [DEALPG4_REC_FAILED] = {
        "FAILED", classes_failed, 2, 1,
        DEALPG4_DIR_NESTED_TO_OUTER | DEALPG4_DIR_OUTER_TO_COORD
    },
    [DEALPG4_REC_DONE] = {
        "DONE", classes_done, 1, 1, DEALPG4_DIR_OUTER_TO_COORD
    },
    [DEALPG4_REC_BYE] = {
        "BYE", NULL, 0, 1, DEALPG4_DIR_ANY
    }
};

/* The catalog is index-complete: every of the 26 named types has a row.
 * This is also the structural no-extension rule — the set is closed and
 * contains no deadline-extension record. */
_Static_assert(DEALPG4_REC_COUNT == 26,
               "DEALPG4 catalog must contain exactly the 26 canonical types");

/* === Catalog metadata ================================================== */

const char *dealpg4_record_type_name(dealpg4_record_type type)
{
    if (type >= DEALPG4_REC_COUNT)
        return "UNKNOWN";
    return catalog[type].name;
}

dealpg4_record_type dealpg4_record_type_from_name(const char *name,
                                                  size_t len)
{
    size_t i;

    for (i = 0; i < DEALPG4_REC_COUNT; i++) {
        const dealpg4_catalog_entry *e = &catalog[i];

        if (strlen(e->name) == len && memcmp(e->name, name, len) == 0)
            return (dealpg4_record_type)i;
    }
    return DEALPG4_REC_UNKNOWN;
}

size_t dealpg4_record_type_count(void)
{
    return DEALPG4_RECORD_TYPE_COUNT;
}

size_t dealpg4_record_fixed_field_count(dealpg4_record_type type)
{
    if (type >= DEALPG4_REC_COUNT)
        return 0;
    return catalog[type].fixed_count;
}

unsigned int dealpg4_record_direction(dealpg4_record_type type)
{
    if (type >= DEALPG4_REC_COUNT)
        return 0;
    return catalog[type].direction;
}

/* === Class predicates (shared by parse and serialize) ================== */

static int dealpg4_is_lower_hex(char c)
{
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
}

static int dealpg4_is_token_char(char c)
{
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
}

static int dealpg4_is_decimal_text(const char *p, size_t len)
{
    size_t i;

    if (len == 0)
        return 0;
    for (i = 0; i < len; i++) {
        if ((unsigned char)p[i] < (unsigned char)'0' ||
            (unsigned char)p[i] > (unsigned char)'9')
            return 0;
    }
    return 1;
}

/* Decimal digits and value <= INT64_MAX (no overflow accepted). */
static int dealpg4_decimal_value(const dealpg4_field_slice *f, int64_t *value)
{
    int64_t acc = 0;
    size_t i;

    if (!dealpg4_is_decimal_text(f->p, f->len))
        return 0;
    for (i = 0; i < f->len; i++) {
        int d = f->p[i] - '0';

        if (acc > (INT64_MAX - d) / 10)
            return 0; /* overflow */
        acc = acc * 10 + (int64_t)d;
    }
    *value = acc;
    return 1;
}

static int dealpg4_is_nonce_text(const char *p, size_t len)
{
    size_t i;

    if (len != DEALPG4_NONCE_HEX_CHARS)
        return 0;
    for (i = 0; i < len; i++) {
        if (!dealpg4_is_lower_hex(p[i]))
            return 0;
    }
    return 1;
}

/* Opaque byte string: even length (>= 2) lowercase hex of UTF-8 bytes.
 * The framing layer checks the hex grammar only; UTF-8 validity of the
 * decoded cwd is a record-level INVOKE check (D6 split). */
static int dealpg4_is_hex_text(const char *p, size_t len)
{
    size_t i;

    if (len < 2 || (len % 2) != 0)
        return 0;
    for (i = 0; i < len; i++) {
        if (!dealpg4_is_lower_hex(p[i]))
            return 0;
    }
    return 1;
}

static int dealpg4_is_token_text(const char *p, size_t len)
{
    size_t i;

    if (len == 0)
        return 0;
    for (i = 0; i < len; i++) {
        if (!dealpg4_is_token_char(p[i]))
            return 0;
    }
    return 1;
}

static int dealpg4_is_literal(const dealpg4_field_slice *f,
                              const char *literal)
{
    size_t len = strlen(literal);

    return f->len == len && memcmp(f->p, literal, len) == 0;
}

static int dealpg4_field_matches_class(dealpg4_field_class class_,
                                       const dealpg4_field_slice *f)
{
    int64_t v = 0;

    switch (class_) {
    case DEALPG4_F_DECIMAL:
        return dealpg4_decimal_value(f, &v);
    case DEALPG4_F_NONCE:
        return dealpg4_is_nonce_text(f->p, f->len);
    case DEALPG4_F_HEX:
        return dealpg4_is_hex_text(f->p, f->len);
    case DEALPG4_F_TOKEN:
        return dealpg4_is_token_text(f->p, f->len);
    case DEALPG4_F_DASH_OR_TOKEN:
        return dealpg4_is_literal(f, "-") ||
               dealpg4_is_token_text(f->p, f->len);
    case DEALPG4_F_STREAM:
        return dealpg4_is_literal(f, "out") ||
               dealpg4_is_literal(f, "err");
    case DEALPG4_F_FINAL:
        return dealpg4_is_literal(f, "success") ||
               dealpg4_is_literal(f, "cancelled");
    case DEALPG4_F_OUTER_STATUS:
        return dealpg4_is_literal(f, "clean") ||
               dealpg4_is_literal(f, "failed");
    case DEALPG4_F_VERSION_4:
        return dealpg4_decimal_value(f, &v) && v == 4;
    case DEALPG4_F_BIT:
        return dealpg4_is_literal(f, "0") ||
               dealpg4_is_literal(f, "1");
    }
    return 0;
}

/* === Parse ============================================================= */

static dealpg4_parse_status dealpg4_parse_fail(dealpg4_parsed *out,
                                               dealpg4_parse_status status)
{
    out->status = status;
    return status;
}

dealpg4_parse_status dealpg4_parse(const char *line, size_t len,
                                   dealpg4_parsed *out)
{
    const dealpg4_catalog_entry *entry;
    dealpg4_record_type type;
    size_t pos, tlen, fixed = 0, total_fields = 0, i;
    dealpg4_parse_status status;

    memset(out, 0, sizeof(*out));
    out->type = DEALPG4_REC_UNKNOWN;

    if (line == NULL || len == 0 || line[len - 1] != '\n')
        return dealpg4_parse_fail(out,
                                  DEALPG4_PARSE_ERR_NOT_LF_TERMINATED);
    if (memchr(line, '\r', len) != NULL)
        return dealpg4_parse_fail(out, DEALPG4_PARSE_ERR_CR_IN_LINE);
    /* Global line bound: no record may exceed the INVOKE cap. */
    if (len > DEALPG4_MAX_LINE_INVOKE_BYTES)
        return dealpg4_parse_fail(out, DEALPG4_PARSE_ERR_OVERSIZE_LINE);
    if (len < 9 || memcmp(line, "DEALPG4 ", 8) != 0)
        return dealpg4_parse_fail(out, DEALPG4_PARSE_ERR_BAD_PREFIX);

    /* Type token. */
    pos = 8;
    while (pos < len - 1 && line[pos] != ' ')
        pos++;
    tlen = pos - 8;
    if (tlen == 0)
        return dealpg4_parse_fail(out, DEALPG4_PARSE_ERR_BAD_TYPE_GRAMMAR);
    for (i = 0; i < tlen; i++) {
        if (!dealpg4_is_token_char(line[8 + i]))
            return dealpg4_parse_fail(out,
                                      DEALPG4_PARSE_ERR_BAD_TYPE_GRAMMAR);
    }
    type = dealpg4_record_type_from_name(line + 8, tlen);
    if (type == DEALPG4_REC_UNKNOWN)
        return dealpg4_parse_fail(out, DEALPG4_PARSE_ERR_UNKNOWN_TYPE);
    out->type = type;
    entry = &catalog[type];

    /* Per-type line cap (INVOKE bounded by the global check). */
    if (type == DEALPG4_REC_OUT) {
        if (len > DEALPG4_MAX_LINE_OUT_BYTES)
            return dealpg4_parse_fail(out, DEALPG4_PARSE_ERR_OVERSIZE_LINE);
    } else if (type != DEALPG4_REC_INVOKE) {
        if (len > DEALPG4_MAX_LINE_OTHER_BYTES)
            return dealpg4_parse_fail(out, DEALPG4_PARSE_ERR_OVERSIZE_LINE);
    }

    /* Fields. */
    while (pos < len - 1) {
        size_t fstart;

        if (line[pos] != ' ')
            return dealpg4_parse_fail(out, DEALPG4_PARSE_ERR_FIELD_ENCODING);
        pos++;
        fstart = pos;
        while (pos < len - 1 && line[pos] != ' ')
            pos++;
        if (pos == fstart)
            /* Empty field: violates Field ::= token without SP/LF/CR. */
            return dealpg4_parse_fail(out,
                                      DEALPG4_PARSE_ERR_FIELD_ENCODING);
        total_fields++;
        if (!entry->exact && total_fields > entry->fixed_count) {
            /* INVOKE argv tail: one contiguous region, counted only. */
            if (out->argv_count == 0)
                out->argv_region.p = line + fstart;
            out->argv_count++;
        } else {
            if (fixed >= DEALPG4_MAX_FIXED_FIELDS)
                return dealpg4_parse_fail(out,
                                          DEALPG4_PARSE_ERR_FIELD_COUNT);
            out->fields[fixed].p = line + fstart;
            out->fields[fixed].len = pos - fstart;
            fixed++;
        }
    }
    out->nfields = fixed;
    if (out->argv_count > 0)
        out->argv_region.len = (size_t)((line + len - 1)
                                        - out->argv_region.p);

    /* Field counts. */
    if (entry->exact) {
        if (total_fields != entry->fixed_count)
            return dealpg4_parse_fail(out, DEALPG4_PARSE_ERR_FIELD_COUNT);
    } else {
        if (total_fields < entry->fixed_count)
            return dealpg4_parse_fail(out, DEALPG4_PARSE_ERR_FIELD_COUNT);
    }

    /* Fixed-field classes. */
    for (i = 0; i < fixed; i++) {
        if (!dealpg4_field_matches_class(entry->classes[i], &out->fields[i]))
            return dealpg4_parse_fail(out,
                                      DEALPG4_PARSE_ERR_FIELD_ENCODING);
    }

    /* INVOKE argv tail: hex grammar plus the raw-total cap. */
    if (type == DEALPG4_REC_INVOKE && out->argv_count > 0) {
        size_t raw_total = 0;

        for (i = 0; i < out->argv_count; i++) {
            dealpg4_field_slice s;

            if (dealpg4_parsed_argv_element(out, i, &s) != 0)
                return dealpg4_parse_fail(out,
                                          DEALPG4_PARSE_ERR_FIELD_ENCODING);
            if (!dealpg4_field_matches_class(DEALPG4_F_HEX, &s))
                return dealpg4_parse_fail(out,
                                          DEALPG4_PARSE_ERR_FIELD_ENCODING);
            raw_total += s.len / 2;
            if (raw_total > DEALPG4_INVOKE_MAX_ARGV_RAW_BYTES)
                return dealpg4_parse_fail(out,
                                          DEALPG4_PARSE_ERR_OVERSIZE_ARGV);
        }
    }

    /* OUT chunk cap (the explicit OUT cap; the line bound is derived). */
    if (type == DEALPG4_REC_OUT) {
        if (out->fields[2].len > DEALPG4_OUT_MAX_HEX_CHARS)
            return dealpg4_parse_fail(out,
                                      DEALPG4_PARSE_ERR_OVERSIZE_OUT_CHUNK);
    }

    status = DEALPG4_PARSE_OK;
    out->status = status;
    return status;
}

dealpg4_classification dealpg4_parse_classify(dealpg4_parse_status s)
{
    return s == DEALPG4_PARSE_OK ? DEALPG4_CLASS_OK
                                 : DEALPG4_CLASS_PROTOCOL_ERROR;
}

const char *dealpg4_parse_status_name(dealpg4_parse_status s)
{
    switch (s) {
    case DEALPG4_PARSE_OK:
        return "ok";
    case DEALPG4_PARSE_ERR_NOT_LF_TERMINATED:
        return "record not LF-terminated";
    case DEALPG4_PARSE_ERR_CR_IN_LINE:
        return "CR in line";
    case DEALPG4_PARSE_ERR_BAD_PREFIX:
        return "bad prefix";
    case DEALPG4_PARSE_ERR_BAD_TYPE_GRAMMAR:
        return "bad type grammar";
    case DEALPG4_PARSE_ERR_UNKNOWN_TYPE:
        return "unknown record type";
    case DEALPG4_PARSE_ERR_OVERSIZE_LINE:
        return "oversize record line";
    case DEALPG4_PARSE_ERR_FIELD_COUNT:
        return "field-count violation";
    case DEALPG4_PARSE_ERR_FIELD_ENCODING:
        return "field encoding violation";
    case DEALPG4_PARSE_ERR_OVERSIZE_OUT_CHUNK:
        return "oversize OUT chunk";
    case DEALPG4_PARSE_ERR_OVERSIZE_ARGV:
        return "oversize INVOKE argv";
    }
    return "unknown parse status";
}

const char *dealpg4_classification_name(dealpg4_classification c)
{
    switch (c) {
    case DEALPG4_CLASS_OK:
        return "";
    case DEALPG4_CLASS_PROTOCOL_ERROR:
        return "PROTOCOL_ERROR";
    case DEALPG4_CLASS_MALFORMED_INVOKE:
        return "MALFORMED_INVOKE";
    case DEALPG4_CLASS_AUTH_FAILED:
        return "AUTH_FAILED";
    case DEALPG4_CLASS_CANCEL_AUTH_FAILED:
        return "CANCEL_AUTH_FAILED";
    }
    return "UNKNOWN";
}

/* === Parsed-record accessors =========================================== */

const dealpg4_field_slice *dealpg4_parsed_field(const dealpg4_parsed *p,
                                                size_t idx)
{
    if (idx >= p->nfields)
        return NULL;
    return &p->fields[idx];
}

size_t dealpg4_parsed_argv_count(const dealpg4_parsed *p)
{
    return p->argv_count;
}

int dealpg4_parsed_argv_element(const dealpg4_parsed *p, size_t idx,
                                dealpg4_field_slice *out)
{
    const char *s = p->argv_region.p;
    const char *end = s + p->argv_region.len;
    size_t i = 0;

    if (idx >= p->argv_count)
        return -1;
    while (s < end) {
        const char *e = s;

        while (e < end && *e != ' ')
            e++;
        if (i == idx) {
            out->p = s;
            out->len = (size_t)(e - s);
            return 0;
        }
        i++;
        s = (e < end) ? e + 1 : end; /* single-SP separators only */
    }
    return -1;
}

int dealpg4_parsed_argv_raw_total(const dealpg4_parsed *p, size_t *total)
{
    size_t i, raw = 0;

    for (i = 0; i < p->argv_count; i++) {
        dealpg4_field_slice s;

        if (dealpg4_parsed_argv_element(p, i, &s) != 0)
            return -1;
        raw += s.len / 2;
    }
    *total = raw;
    return 0;
}

/* === Field decoders ==================================================== */

int dealpg4_field_decimal(const dealpg4_field_slice *f, int64_t *value)
{
    return dealpg4_decimal_value(f, value);
}

static int dealpg4_hex_nibble(char c)
{
    if (c >= '0' && c <= '9')
        return c - '0';
    if (c >= 'a' && c <= 'f')
        return c - 'a' + 10;
    return -1;
}

int dealpg4_hex_decode(const dealpg4_field_slice *f, unsigned char *out,
                       size_t cap, size_t *written)
{
    size_t i, raw;

    if (f->len % 2 != 0)
        return -1;
    raw = f->len / 2;
    if (raw > cap)
        return -2;
    for (i = 0; i < raw; i++) {
        int hi = dealpg4_hex_nibble(f->p[2 * i]);
        int lo = dealpg4_hex_nibble(f->p[2 * i + 1]);

        if (hi < 0 || lo < 0)
            return -1;
        out[i] = (unsigned char)((hi << 4) | lo);
    }
    *written = raw;
    return 0;
}

int dealpg4_utf8_valid(const unsigned char *data, size_t len)
{
    size_t i = 0;

    while (i < len) {
        unsigned char c = data[i];

        if (c < 0x80) { /* ASCII (NUL is valid UTF-8; callers check NUL
                           separately where required) */
            i++;
            continue;
        }
        if (c < 0xC2)
            return 0; /* stray continuation byte or overlong C0/C1 */
        if (c < 0xE0) { /* 2-byte sequence */
            if (i + 1 >= len || (data[i + 1] & 0xC0) != 0x80)
                return 0;
            i += 2;
            continue;
        }
        if (c < 0xF0) { /* 3-byte sequence */
            if (i + 2 >= len || (data[i + 1] & 0xC0) != 0x80 ||
                (data[i + 2] & 0xC0) != 0x80)
                return 0;
            if (c == 0xE0 && data[i + 1] < 0xA0)
                return 0; /* overlong */
            if (c == 0xED && data[i + 1] > 0x9F)
                return 0; /* surrogate U+D800..U+DFFF */
            i += 3;
            continue;
        }
        if (c < 0xF5) { /* 4-byte sequence */
            if (i + 3 >= len || (data[i + 1] & 0xC0) != 0x80 ||
                (data[i + 2] & 0xC0) != 0x80 ||
                (data[i + 3] & 0xC0) != 0x80)
                return 0;
            if (c == 0xF0 && data[i + 1] < 0x90)
                return 0; /* overlong */
            if (c == 0xF4 && data[i + 1] > 0x8F)
                return 0; /* above U+10FFFF */
            i += 4;
            continue;
        }
        return 0; /* F5..FF never valid */
    }
    return 1;
}

/* === Expectation sets ================================================== */

void dealpg4_expectation_set_init(dealpg4_expectation_set *s)
{
    s->bits = 0;
}

void dealpg4_expectation_set_all(dealpg4_expectation_set *s)
{
    s->bits = (1u << DEALPG4_REC_COUNT) - 1u;
}

void dealpg4_expectation_set_add(dealpg4_expectation_set *s,
                                 dealpg4_record_type type)
{
    if (type < DEALPG4_REC_COUNT)
        s->bits |= 1u << type;
}

void dealpg4_expectation_set_remove(dealpg4_expectation_set *s,
                                    dealpg4_record_type type)
{
    if (type < DEALPG4_REC_COUNT)
        s->bits &= ~(1u << type);
}

int dealpg4_expectation_set_contains(const dealpg4_expectation_set *s,
                                     dealpg4_record_type type)
{
    if (type >= DEALPG4_REC_COUNT)
        return 0;
    return (s->bits & (1u << type)) != 0;
}

dealpg4_classification dealpg4_expectation_check(
    const dealpg4_expectation_set *s, const dealpg4_parsed *p)
{
    if (!dealpg4_expectation_set_contains(s, p->type))
        return DEALPG4_CLASS_PROTOCOL_ERROR;
    return DEALPG4_CLASS_OK;
}

/* === INVOKE semantic validation ======================================== */

dealpg4_classification dealpg4_invoke_semantic_check(int64_t argc,
                                                     size_t argv_count,
                                                     const unsigned char *cwd,
                                                     size_t cwd_len)
{
    /* argc 0 (equivalently: the argv is empty). */
    if (argc == 0)
        return DEALPG4_CLASS_MALFORMED_INVOKE;
    /* argc not equal to the number of argv fields (also catches an
     * empty argv with argc > 0). */
    if (argc != (int64_t)argv_count)
        return DEALPG4_CLASS_MALFORMED_INVOKE;
    if (argv_count == 0)
        return DEALPG4_CLASS_MALFORMED_INVOKE;
    /* cwd: decoded bytes must be a non-empty, NUL-free, valid-UTF-8
     * path. */
    if (cwd_len == 0)
        return DEALPG4_CLASS_MALFORMED_INVOKE;
    if (memchr(cwd, '\0', cwd_len) != NULL)
        return DEALPG4_CLASS_MALFORMED_INVOKE;
    if (!dealpg4_utf8_valid(cwd, cwd_len))
        return DEALPG4_CLASS_MALFORMED_INVOKE;
    return DEALPG4_CLASS_OK;
}

/* === ACK / CANCEL record-level classification ========================== */

dealpg4_classification dealpg4_ack_classify(const dealpg4_ack_facts *facts)
{
    if (!facts->invocation_id_known || !facts->record_in_apply_phase ||
        !facts->nonce_matches || !facts->first_ack)
        return DEALPG4_CLASS_AUTH_FAILED;
    return DEALPG4_CLASS_OK;
}

dealpg4_classification dealpg4_cancel_classify(
    const dealpg4_cancel_facts *facts)
{
    if (!facts->invocation_id_known || !facts->record_live ||
        !facts->nonce_matches)
        return DEALPG4_CLASS_CANCEL_AUTH_FAILED;
    return DEALPG4_CLASS_OK;
}

/* === Serialization ===================================================== */

static int dealpg4_serialize_validate(dealpg4_record_type type,
                                      const dealpg4_field_value *fields,
                                      size_t nfields, size_t *total_len)
{
    const dealpg4_catalog_entry *entry;
    size_t i, len;

    if (type >= DEALPG4_REC_COUNT) {
        errno = EINVAL;
        return -1;
    }
    entry = &catalog[type];

    if (entry->exact) {
        if (nfields != entry->fixed_count) {
            errno = EINVAL;
            return -1;
        }
    } else {
        if (nfields < entry->fixed_count) {
            errno = EINVAL;
            return -1;
        }
    }

    for (i = 0; i < nfields; i++) {
        dealpg4_field_class class_ = (entry->exact || i < entry->fixed_count)
                                         ? entry->classes[i]
                                         : DEALPG4_F_HEX;
        dealpg4_field_slice f;

        /* Bound the validation walk: no record field can exceed the
         * largest record cap, so an oversized caller buffer is refused
         * before any character is inspected. */
        if (fields[i].len > DEALPG4_MAX_LINE_INVOKE_BYTES) {
            errno = EINVAL;
            return -1;
        }
        f.p = fields[i].data;
        f.len = fields[i].len;
        if (!dealpg4_field_matches_class(class_, &f)) {
            errno = EINVAL;
            return -1;
        }
        if (type == DEALPG4_REC_OUT && i == 2 &&
            fields[i].len > DEALPG4_OUT_MAX_HEX_CHARS) {
            errno = EINVAL;
            return -1;
        }
    }

    if (type == DEALPG4_REC_INVOKE) {
        size_t raw_total = 0;

        for (i = entry->fixed_count; i < nfields; i++) {
            raw_total += fields[i].len / 2;
            if (raw_total > DEALPG4_INVOKE_MAX_ARGV_RAW_BYTES) {
                errno = EINVAL;
                return -1;
            }
        }
    }

    len = 8 + strlen(entry->name) + 1; /* "DEALPG4 " + name + LF */
    for (i = 0; i < nfields; i++) {
        len += 1 + fields[i].len;      /* " " + field */
        /* Overflow-safe accumulation: no record may exceed the largest
         * cap, so bail out as soon as the total passes it. */
        if (len > DEALPG4_MAX_LINE_INVOKE_BYTES) {
            errno = EINVAL;
            return -1;
        }
    }

    if (type == DEALPG4_REC_OUT) {
        if (len > DEALPG4_MAX_LINE_OUT_BYTES) {
            errno = EINVAL;
            return -1;
        }
    } else if (type == DEALPG4_REC_INVOKE) {
        if (len > DEALPG4_MAX_LINE_INVOKE_BYTES) {
            errno = EINVAL;
            return -1;
        }
    } else {
        if (len > DEALPG4_MAX_LINE_OTHER_BYTES) {
            errno = EINVAL;
            return -1;
        }
    }
    *total_len = len;
    return 0;
}

int dealpg4_serialize(dealpg4_record_type type,
                      const dealpg4_field_value *fields, size_t nfields,
                      char *out, size_t cap, size_t *written)
{
    const dealpg4_catalog_entry *entry;
    size_t total_len, pos = 0, i;

    if (dealpg4_serialize_validate(type, fields, nfields, &total_len) != 0)
        return -1;
    if (total_len > cap) {
        errno = ENOBUFS;
        return -1;
    }

    entry = &catalog[type];
    memcpy(out + pos, "DEALPG4 ", 8);
    pos += 8;
    memcpy(out + pos, entry->name, strlen(entry->name));
    pos += strlen(entry->name);
    for (i = 0; i < nfields; i++) {
        out[pos++] = ' ';
        memcpy(out + pos, fields[i].data, fields[i].len);
        pos += fields[i].len;
    }
    out[pos++] = '\n';
    *written = pos;
    return 0;
}

/* === Nonce generation ================================================== */

int dealpg4_nonce_hex(char out[33])
{
    static const char hexdigits[16] = "0123456789abcdef";
    unsigned char buf[DEALPG4_NONCE_BYTES];
    size_t got = 0;
    size_t i;

    while (got < sizeof(buf)) {
        ssize_t r = getrandom(buf + got, sizeof(buf) - got, 0);

        if (r < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        if (r == 0) {
            errno = EIO; /* getrandom(2) never returns 0 for len > 0 */
            return -1;
        }
        got += (size_t)r;
    }
    for (i = 0; i < sizeof(buf); i++) {
        out[2 * i] = hexdigits[buf[i] >> 4];
        out[2 * i + 1] = hexdigits[buf[i] & 0x0f];
    }
    out[DEALPG4_NONCE_HEX_CHARS] = '\0';
    return 0;
}
