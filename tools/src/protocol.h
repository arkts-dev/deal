/*
 * DEALPG4 v4 protocol core.
 *
 * Owns (dealpg4-launcher-artifact-and-integrity D5/D6,
 * dealpg4-protocol-core D1-D10):
 *  - the single canonical framing grammar
 *        Record ::= "DEALPG4" SP Type *(SP Field) LF
 *        Type   ::= 1*(ALPHA / "_")
 *        Field  ::= token without SP / LF / CR
 *    one record per line, a record complete only at LF, CR anywhere
 *    invalid, a line not starting with "DEALPG4 SP" a framing defect;
 *    the INVOKE cwd field (fixed index 1) may be empty: the empty
 *    string is even-length lowercase hex (dealpg4-protocol-core D2), so
 *    an empty cwd is in-class framing-wise and is delivered as a
 *    zero-length field slice -- it decodes to an empty path, which the
 *    semantic check classifies record-level MALFORMED_INVOKE (artifact
 *    page D5/D6 split: framing defects alone close the channel);
 *  - the per-class field encodings (decimal ids/counts/milliseconds,
 *    32-lowercase-hex nonces, even-length lowercase-hex opaque byte
 *    strings, [A-Za-z_]+ tokens) and the per-type size caps (INVOKE <=
 *    131072-byte line with <= 65536 raw argv bytes and a <= 8000-byte
 *    clientTag; OUT chunks <= 65536 hex chars = 32768 raw bytes;
 *    every other record <= 8192 bytes), enforced on read
 *    (PROTOCOL_ERROR) and on write (refusal). The clientTag bound is
 *    sized so that every INVOKED/REJECT echo of a parse-OK INVOKE
 *    always fits the 8192-byte per-type answer cap: the longest echo
 *    (an 18-byte reason token, a 19-digit id, the max tag) is 8055
 *    bytes -- so no well-formed INVOKE can ever produce a silently
 *    dropped answer (an oversize tag is a framing defect and closes
 *    the channel instead, the canonical size-cap split);
 *  - the full 26-type record catalog with per-type field shapes,
 *    encodings, counts, and directions (REPORT fixed 19-field order);
 *  - the framing-vs-record-level validation split: framing defects,
 *    unknown types, oversize records, and records unexpected in the
 *    configured channel-state expectation set classify PROTOCOL_ERROR
 *    (the consumer closes the channel); a well-formed INVOKE that fails
 *    semantic validation and well-formed ACK/CANCEL records that fail the
 *    record-level rules classify record-level outcomes (channel open) —
 *    classification here is side-effect-free, consumers apply the
 *    catalog actions;
 *  - record serialization: one caps-conforming record line with a single
 *    LF terminator; an oversize or class-violating request is refused
 *    (caller defect — a malformed line is never emitted);
 *  - the nonce helper: 16 bytes from getrandom(2) rendered as exactly 32
 *    lowercase hex characters; EINTR retried; unrecoverable failure
 *    returned to the caller (a nonce is never silently zeroed or
 *    derived); no GRND_NONBLOCK.
 *
 * The record catalog contains no deadline-extension record type: an
 * unknown or extension record classifies PROTOCOL_ERROR, so "Java/shell
 * cannot extend native time" is structural (D10).
 *
 * Protocol is a leaf utility (libc only). Per-channel state machines,
 * pending queues, and the application of rejections stay with the
 * consuming supervisors (native-supervisor-containment,
 * outer-coordinator-and-broker); protocol supplies the expectation
 * mechanism and the catalog.
 */
#ifndef DEALPG4_PROTOCOL_H
#define DEALPG4_PROTOCOL_H

#include <stddef.h>
#include <stdint.h>

/* === Size caps (canonical, enforced in both directions) ================= */
#define DEALPG4_MAX_LINE_INVOKE_BYTES      131072
#define DEALPG4_MAX_LINE_OTHER_BYTES         8192
#define DEALPG4_OUT_MAX_HEX_CHARS           65536
/* OUT line bound: the explicit chunk cap (65536 hex chars) plus the
 * longest possible non-chunk prefix "DEALPG4 OUT " + a 19-digit decimal
 * id + " out " + LF; the chunk cap is the operative cap and is enforced
 * independently. */
#define DEALPG4_MAX_LINE_OUT_BYTES (DEALPG4_OUT_MAX_HEX_CHARS + 64)
#define DEALPG4_INVOKE_MAX_ARGV_RAW_BYTES   65536
/* The INVOKE clientTag catalog max. Sized so that every INVOKED/REJECT
 * echo of a parse-OK INVOKE always fits the 8192-byte per-type answer
 * cap: the longest INVOKED echo ("DEALPG4 INVOKED " + a 19-digit
 * decimal id + " " + tag + LF) is 8037 bytes, and the longest REJECT
 * echo (19-digit id, 8000-byte tag, the catalog's longest in-use
 * 18-byte reason token CANCEL_AUTH_FAILED) is 8055 bytes -- both under
 * 8192, so a legal INVOKE can never produce a silently dropped answer.
 * Enforced on read (framing: an oversize tag classifies OVERSIZE_LINE,
 * PROTOCOL_ERROR downstream -- the canonical size-cap split) and on
 * write (the INVOKE tag and the INVOKED/REJECT echo fields are refused
 * past the bound, so a malformed echo line is never emitted). */
#define DEALPG4_INVOKE_CLIENT_TAG_MAX_BYTES 8000
#define DEALPG4_NONCE_BYTES                    16
#define DEALPG4_NONCE_HEX_CHARS                32

/* === Record catalog ==================================================== */

/* All 26 canonical DEALPG4 v4 record types (the complete catalog; no
 * deadline-extension record exists). */
typedef enum dealpg4_record_type {
    DEALPG4_REC_HELLO = 0,          /* coordinator -> outer: nonce */
    DEALPG4_REC_HELLO_OK,           /* outer -> coordinator: version=4, caps */
    DEALPG4_REC_FEATURE_READY,      /* coordinator -> outer: nonce */
    DEALPG4_REC_READY_ACK,          /* outer -> coordinator: nonce */
    DEALPG4_REC_INVOKE,             /* coordinator -> outer: tag, cwd, argc, argv... */
    DEALPG4_REC_INVOKED,            /* outer -> coordinator: id, tag */
    DEALPG4_REC_REJECT,             /* outer -> coord / nested -> outer: id|0, tag|-, reason */
    DEALPG4_REC_STUB_FORKED,        /* nested -> outer: id, pid */
    DEALPG4_REC_STUB_IDENTITY,      /* stub -> supervisor: pid, pgid, sid, nonce */
    DEALPG4_REC_STUB_FAILED,        /* stub -> supervisor: pid, errno */
    DEALPG4_REC_STUB_EXEC_FAILED,   /* stub -> supervisor: pid, errno */
    DEALPG4_REC_RELEASE_RECV,       /* stub -> supervisor: pid */
    DEALPG4_REC_STUB_READY,         /* nested -> outer; outer -> coord: id, pid, pgid, sid, nonce */
    DEALPG4_REC_COORD_READY,        /* coordinator child -> outer: pid, pgid, sid */
    DEALPG4_REC_COORD_EXEC_FAILED,  /* coordinator child -> outer: pid, errno */
    DEALPG4_REC_ACK,                /* coord -> outer -> nested: id, nonce */
    DEALPG4_REC_STARTED,            /* nested -> outer -> coord: id */
    DEALPG4_REC_EXEC_FAILED,        /* nested -> outer -> coord: id, errno */
    DEALPG4_REC_OUT,                /* nested -> outer -> coord: id, out|err, hexChunk */
    DEALPG4_REC_OUT_END,            /* nested -> outer -> coord: id, out|err */
    DEALPG4_REC_CANCEL,             /* coord -> outer; outer -> nested: id, nonce */
    DEALPG4_REC_REPORT,             /* nested -> outer -> coord: fixed 19 fields */
    DEALPG4_REC_CLEAN,              /* nested -> outer -> coord: id, success|cancelled */
    DEALPG4_REC_FAILED,             /* nested -> outer -> coord: id, failureToken */
    DEALPG4_REC_DONE,               /* outer -> coord: clean|failed */
    DEALPG4_REC_BYE,                /* either direction: no fields */
    DEALPG4_REC_COUNT,              /* = 26 */
    DEALPG4_REC_UNKNOWN = DEALPG4_REC_COUNT
} dealpg4_record_type;

/* Catalog cardinality (26 types). */
#define DEALPG4_RECORD_TYPE_COUNT ((size_t)DEALPG4_REC_COUNT)

/* Direction bits of the catalog rows (metadata; the consuming channels
 * enforce their own direction rules). */
#define DEALPG4_DIR_COORD_TO_OUTER     (1u << 0)
#define DEALPG4_DIR_OUTER_TO_COORD     (1u << 1)
#define DEALPG4_DIR_NESTED_TO_OUTER    (1u << 2)
#define DEALPG4_DIR_OUTER_TO_NESTED    (1u << 3)
#define DEALPG4_DIR_STUB_TO_SUPERVISOR (1u << 4)
#define DEALPG4_DIR_CHILD_TO_OUTER     (1u << 5)
#define DEALPG4_DIR_ANY 0x3fu

const char *dealpg4_record_type_name(dealpg4_record_type type);
dealpg4_record_type dealpg4_record_type_from_name(const char *name,
                                                  size_t len);
size_t dealpg4_record_type_count(void);
/* Fixed (non-argv) field count of the type, 0 for UNKNOWN. For INVOKE
 * this is 3 (clientTag, cwd, argc); the argv tail is separate. */
size_t dealpg4_record_fixed_field_count(dealpg4_record_type type);
/* Direction bits of the type, 0 for UNKNOWN. */
unsigned int dealpg4_record_direction(dealpg4_record_type type);

/* === Parsed record and the read path =================================== */

/* A field slice into the parsed input line (the caller keeps the line
 * alive while the parsed record is used). */
typedef struct dealpg4_field_slice {
    const char *p;
    size_t len;
} dealpg4_field_slice;

/* Maximum fixed fields of any catalog record (REPORT has 19). */
#define DEALPG4_MAX_FIXED_FIELDS 19

/* Framing outcomes. Every nonzero status classifies PROTOCOL_ERROR
 * (dealpg4_parse_classify); DEALPG4_PARSE_OK means the record parsed and
 * passed every framing check (prefix, type, caps, field counts, field
 * classes). */
typedef enum dealpg4_parse_status {
    DEALPG4_PARSE_OK = 0,
    DEALPG4_PARSE_ERR_NOT_LF_TERMINATED,  /* record incomplete: no final LF */
    DEALPG4_PARSE_ERR_CR_IN_LINE,         /* CR anywhere */
    DEALPG4_PARSE_ERR_BAD_PREFIX,         /* not starting with "DEALPG4 " */
    DEALPG4_PARSE_ERR_BAD_TYPE_GRAMMAR,   /* type token outside [A-Za-z_]+ */
    DEALPG4_PARSE_ERR_UNKNOWN_TYPE,       /* type token not in the catalog */
    DEALPG4_PARSE_ERR_OVERSIZE_LINE,      /* per-type line cap exceeded */
    DEALPG4_PARSE_ERR_FIELD_COUNT,        /* wrong number of fields */
    DEALPG4_PARSE_ERR_FIELD_ENCODING,     /* field outside its class (incl.
                                             non-hex/odd-length hex, empty
                                             field -- except the INVOKE
                                             cwd, which may be empty --
                                             non-SP separator) */
    DEALPG4_PARSE_ERR_OVERSIZE_OUT_CHUNK, /* OUT hexChunk > 65536 hex chars */
    DEALPG4_PARSE_ERR_OVERSIZE_ARGV       /* INVOKE raw argv > 65536 bytes */
} dealpg4_parse_status;

/* Framing-vs-record-level classification (canonical split). */
typedef enum dealpg4_classification {
    DEALPG4_CLASS_OK = 0,
    DEALPG4_CLASS_PROTOCOL_ERROR = 1,    /* framing-level: consumer closes
                                            the channel (no retry) */
    DEALPG4_CLASS_MALFORMED_INVOKE = 2,  /* record-level reason token */
    DEALPG4_CLASS_AUTH_FAILED = 3,       /* record-level reason token */
    DEALPG4_CLASS_CANCEL_AUTH_FAILED = 4 /* record-level reason token */
} dealpg4_classification;

/* Reason-token text of a classification ("" for OK). The record-level
 * texts are exactly the catalog reason tokens consumers emit. */
const char *dealpg4_classification_name(dealpg4_classification c);
/* OK for DEALPG4_PARSE_OK, PROTOCOL_ERROR for every other status. */
dealpg4_classification dealpg4_parse_classify(dealpg4_parse_status s);
const char *dealpg4_parse_status_name(dealpg4_parse_status s);

/* A parsed, caps-conforming record. For INVOKE the first three fields
 * (clientTag, cwd, argc) are in fields[]; the argv tail is one contiguous
 * region in the line addressed by argv_count/argv_region and located with
 * dealpg4_parsed_argv_element. The INVOKE cwd slice may be zero-length
 * (an empty hex string); the consumer's dealpg4_invoke_semantic_check
 * classifies the empty path MALFORMED_INVOKE. */
typedef struct dealpg4_parsed {
    dealpg4_parse_status status;   /* DEALPG4_PARSE_OK on success */
    dealpg4_record_type type;      /* UNKNOWN when the type token failed */
    size_t nfields;                /* fixed fields stored in fields[] */
    dealpg4_field_slice fields[DEALPG4_MAX_FIXED_FIELDS];
    dealpg4_field_slice argv_region; /* INVOKE argv tail (p = first argv
                                        byte, len to the final LF) */
    size_t argv_count;             /* INVOKE argv field count */
} dealpg4_parsed;

/* Parse one complete record line (the input must be exactly one record
 * terminated by LF; anything else is a framing defect). Fills *out on
 * every call; on failure out->status carries the defect. Synchronous,
 * side-effect-free, no waits. */
dealpg4_parse_status dealpg4_parse(const char *line, size_t len,
                                   dealpg4_parsed *out);

/* Fixed field accessor: index < nfields, or NULL. */
const dealpg4_field_slice *dealpg4_parsed_field(const dealpg4_parsed *p,
                                                size_t idx);
size_t dealpg4_parsed_argv_count(const dealpg4_parsed *p);
/* INVOKE argv element idx (0-based): locates the element in the argv
 * region. Returns 0 and fills *out, or -1 when idx is out of range. */
int dealpg4_parsed_argv_element(const dealpg4_parsed *p, size_t idx,
                                dealpg4_field_slice *out);
/* Sum of the decoded byte lengths of the parsed argv elements. */
int dealpg4_parsed_argv_raw_total(const dealpg4_parsed *p, size_t *total);

/* Decode a decimal field into int64 (digits only, value <= INT64_MAX).
 * Returns 1 and *value on success, 0 otherwise. */
int dealpg4_field_decimal(const dealpg4_field_slice *f, int64_t *value);
/* Decode an even-length lowercase-hex slice into bytes. Returns 0 and
 * *written on success, -1 for invalid hex (odd length / non-hex char),
 * -2 when the output buffer (cap) is too small. */
int dealpg4_hex_decode(const dealpg4_field_slice *f, unsigned char *out,
                       size_t cap, size_t *written);
/* 1 when data is valid UTF-8 (NUL is valid UTF-8; the INVOKE semantic
 * check rejects NUL separately). */
int dealpg4_utf8_valid(const unsigned char *data, size_t len);

/* === Channel-state expectation set ======================================
 * Consumers configure the set of record types expected in the current
 * channel state; a record unexpected in that state classifies
 * PROTOCOL_ERROR (D5). The state machines themselves stay with the
 * consuming supervisors.
 */
typedef struct dealpg4_expectation_set {
    uint32_t bits; /* bit per catalog type */
} dealpg4_expectation_set;

void dealpg4_expectation_set_init(dealpg4_expectation_set *s);
void dealpg4_expectation_set_all(dealpg4_expectation_set *s);
void dealpg4_expectation_set_add(dealpg4_expectation_set *s,
                                 dealpg4_record_type type);
void dealpg4_expectation_set_remove(dealpg4_expectation_set *s,
                                    dealpg4_record_type type);
int dealpg4_expectation_set_contains(const dealpg4_expectation_set *s,
                                     dealpg4_record_type type);
/* OK when the parsed record's type is expected in the current channel
 * state, PROTOCOL_ERROR (state-unexpected) otherwise. */
dealpg4_classification dealpg4_expectation_check(
    const dealpg4_expectation_set *s, const dealpg4_parsed *p);

/* === INVOKE semantic validation (record-level) ==========================
 * A well-formed (parse-OK) INVOKE fails semantic validation when argc is
 * 0, argc != the number of argv fields, the argv is empty, or the cwd
 * hex bytes fail UTF-8 decoding or decode to an empty/NUL-containing
 * path. Classification: record-level MALFORMED_INVOKE (the outer answers
 * REJECT <id> <tag> MALFORMED_INVOKE, the channel stays open). Never
 * returns PROTOCOL_ERROR: framing defects are classified by
 * dealpg4_parse alone (canonical split, D6).
 */
dealpg4_classification dealpg4_invoke_semantic_check(int64_t argc,
                                                     size_t argv_count,
                                                     const unsigned char *cwd,
                                                     size_t cwd_len);

/* === ACK / CANCEL record-level classification ===========================
 * Framing-malformed ACK/CANCEL records classify PROTOCOL_ERROR in
 * dealpg4_parse. A well-formed record is a record-level operation; these
 * predicates return the record-level classification from the consuming
 * supervisor's registry facts. The predicates never return
 * PROTOCOL_ERROR: a well-formed ACK after the nested release write (and
 * any other state-unexpected record) is classified by the expectation
 * set, not here.
 */

typedef struct dealpg4_ack_facts {
    int invocation_id_known;   /* the id names a record the hop knows */
    int record_in_apply_phase; /* nested: invocation live AND pre-release;
                                  outer: record live (not terminal) */
    int nonce_matches;         /* ACK nonce == the record's invocation
                                  nonce */
    int first_ack;             /* no ACK was previously applied to this
                                  record (second ACK = 0) */
} dealpg4_ack_facts;

/* OK when every fact holds. Otherwise record-level AUTH_FAILED —
 * nested side: id/nonce mismatch or a second pre-release ACK (the
 * supervisor publishes FAILED <id> AUTH_FAILED, channel open); outer
 * side: mismatch, unknown id, terminal record, or second ACK (the outer
 * answers REJECT <id> <tag> AUTH_FAILED, broker open, record untouched).
 * A well-formed ACK after the nested release write is state-unexpected:
 * the consumer classifies it with the expectation set (PROTOCOL_ERROR),
 * not with this predicate. */
dealpg4_classification dealpg4_ack_classify(const dealpg4_ack_facts *facts);

typedef struct dealpg4_cancel_facts {
    int invocation_id_known; /* the id names a record the hop knows */
    int record_live;         /* the record is not terminal */
    int nonce_matches;       /* CANCEL nonce == the record's invocation
                                nonce */
} dealpg4_cancel_facts;

/* OK when every fact holds (a CANCEL applies only when the id names a
 * live record and the nonce equals that record's invocation nonce).
 * Otherwise record-level CANCEL_AUTH_FAILED: the rejecting side answers
 * REJECT <id> <tag|-> CANCEL_AUTH_FAILED, the channel stays open, the
 * cancel is not applied, no record state changes. A well-formed CANCEL
 * in the live channel phase is never itself a channel close — this
 * predicate never returns PROTOCOL_ERROR. */
dealpg4_classification dealpg4_cancel_classify(
    const dealpg4_cancel_facts *facts);

/* === Serialization (write path) ======================================== */

/* One field value to serialize. */
typedef struct dealpg4_field_value {
    const char *data; /* field bytes */
    size_t len;
} dealpg4_field_value;

/* Serialize one catalog record into one caps-conforming line with a
 * single LF terminator. Validates type, field count, per-field classes,
 * the INVOKE raw-argv cap, the OUT chunk cap, and the per-type line cap
 * exactly like the parser (write-side symmetry): an oversize or
 * class-violating request is refused with -1/errno=EINVAL — a malformed
 * line is never emitted. The INVOKE cwd field may be empty (write-side
 * symmetry with the parse acceptance); the serialized line round-trips
 * through dealpg4_parse to identical field slices. A conforming record
 * that does not fit the caller's buffer returns -1/errno=ENOBUFS. On
 * success returns 0 and *written = bytes emitted (including the LF). */
int dealpg4_serialize(dealpg4_record_type type,
                      const dealpg4_field_value *fields, size_t nfields,
                      char *out, size_t cap, size_t *written);

/* === Nonce generation ===================================================
 * 16 bytes from getrandom(2) rendered as exactly 32 lowercase hex
 * characters, NUL-terminated (33-byte output). EINTR is retried; any
 * unrecoverable getrandom(2) failure returns -1 with errno — the caller
 * fails its operation, a nonce is never silently zeroed or derived.
 * Blocking getrandom(2): no GRND_NONBLOCK (contract D10).
 */
int dealpg4_nonce_hex(char out[33]);

#endif
