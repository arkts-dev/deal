/*
 * DEALPG4 protocol unit acceptance suite (tools/test/protocol-unit-tests.c).
 *
 * The seven case groups of dealpg4-protocol-core Verification 1-7, run
 * against tools/src/protocol.c compiled with the pinned flags
 * (tools/test/run-protocol-unit-tests.sh). Permanent and re-runnable;
 * lives outside the tools/src/ build glob so the pinned artifact
 * build is unchanged.
 *
 * Group 1: framing defects classify PROTOCOL_ERROR (channel close):
 *   unknown record type, malformed fields, oversized records (each cap
 *   class), records unexpected in the configured channel state.
 * Group 2: well-formed INVOKE semantic defects classify record-level
 *   MALFORMED_INVOKE with the channel open (argc != argv count, empty
 *   argv, undecodable / empty / NUL-containing cwd).
 * Group 3: framing-defective INVOKE (non-hex or odd-length hex,
 *   field-count violation, size violation) classifies PROTOCOL_ERROR.
 * Group 4: ACK classification: framing-malformed -> PROTOCOL_ERROR;
 *   well-formed mismatch / second pre-release (nested side) and
 *   mismatch / unknown / terminal / second (outer side) -> record-level
 *   AUTH_FAILED; post-release ACK -> state-unexpected PROTOCOL_ERROR.
 * Group 5: CANCEL classification: nonce mismatch / terminal record /
 *   unknown id -> record-level CANCEL_AUTH_FAILED with the channel open;
 *   a well-formed CANCEL in the live phase is never a channel close.
 * Group 6: serialization: oversize / class-violating requests refused;
 *   every catalog record round-trips through the parser to identical
 *   field values.
 * Group 7: nonce helper emits exactly 32 lowercase hex per 16 kernel
 *   entropy bytes; the 26-type catalog is closed -- no
 *   deadline-extension record exists.
 */
#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <stdio.h>
#include <string.h>

#include "../src/protocol.h"

static int g_checks;
static int g_failures;

#define CHECK(cond)                                                     \
    do {                                                                \
        g_checks++;                                                     \
        if (!(cond)) {                                                  \
            g_failures++;                                               \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__,     \
                    #cond);                                             \
        }                                                               \
    } while (0)

static dealpg4_classification classify_line(const char *line)
{
    dealpg4_parsed p;

    (void)dealpg4_parse(line, strlen(line), &p);
    return dealpg4_parse_classify(p.status);
}

static dealpg4_parse_status parse_line(const char *line, dealpg4_parsed *p)
{
    return dealpg4_parse(line, strlen(line), p);
}

static int field_slices_equal(const dealpg4_field_slice *a,
                              const dealpg4_field_value *b)
{
    return a->len == b->len && memcmp(a->p, b->data, b->len) == 0;
}

/* === Group 1: framing defects close the channel ======================== */

static void group1_framing_defects_close_channel(void)
{
    char line[DEALPG4_MAX_LINE_INVOKE_BYTES + 64];
    dealpg4_parsed p;
    dealpg4_expectation_set expected;
    size_t pos;

    /* Unknown record types (the catalog is closed). */
    CHECK(classify_line("DEALPG4 NOPE 1\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(classify_line("DEALPG4 EXTEND_DEADLINE 1\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    /* A line not starting with "DEALPG4 SP". */
    CHECK(classify_line("XDEALPG4 STARTED 1\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(classify_line("DEALPG4X STARTED 1\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(classify_line("STARTED 1\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    /* A record is complete only at LF; CR anywhere is invalid. */
    CHECK(classify_line("DEALPG4 STARTED 1")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(classify_line("DEALPG4 STARTED 1\r\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    /* Bad type grammar. */
    CHECK(classify_line("DEALPG4 STARTED1 1\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(classify_line("DEALPG4  1\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    /* Malformed fields (outside their class). */
    CHECK(classify_line("DEALPG4 STARTED abc\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(classify_line(
              "DEALPG4 ACK 1 ABCDEF0123456789ABCDEF0123456789\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR); /* uppercase hex nonce */
    CHECK(classify_line("DEALPG4 ACK 1 0123\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR); /* short nonce */
    CHECK(classify_line("DEALPG4 OUT 1 sideways 6869\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR); /* bad stream */
    CHECK(classify_line("DEALPG4 CLEAN 1 maybe\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR); /* bad final */
    CHECK(classify_line("DEALPG4 DONE cleanish\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR); /* bad outer status */
    /* HELLO_OK version: the VERSION_4 field class is decimal with
     * value exactly 4 -- leading zeros are valid, string equality with
     * "4" is not the check, and a version whose decimal value is not
     * 4, a non-decimal version, or an int64-overflowing version is a
     * framing defect. The Java broker client must classify these
     * identically (cross-validation negative set). */
    CHECK(classify_line("DEALPG4 HELLO_OK 5 31\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(classify_line("DEALPG4 HELLO_OK 40 31\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(classify_line("DEALPG4 HELLO_OK 4x 31\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(classify_line("DEALPG4 HELLO_OK -4 31\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(classify_line("DEALPG4 HELLO_OK 99999999999999999999999999 31\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(parse_line("DEALPG4 HELLO_OK 04 31\n", &p) == DEALPG4_PARSE_OK);
    CHECK(parse_line("DEALPG4 HELLO_OK 004 31\n", &p) == DEALPG4_PARSE_OK);
    /* Field-count violations. */
    CHECK(classify_line("DEALPG4 STARTED 1 2\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(classify_line("DEALPG4 STARTED\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(classify_line("DEALPG4 BYE 1\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    /* Oversized non-INVOKE/OUT record (8192-byte cap). */
    pos = 0;
    memcpy(line + pos, "DEALPG4 DONE ", 13);
    pos += 13;
    memset(line + pos, 'c', 8200);
    pos += 8200;
    line[pos++] = '\n';
    line[pos] = '\0';
    CHECK(classify_line(line) == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(parse_line(line, &p) == DEALPG4_PARSE_ERR_OVERSIZE_LINE);
    /* Oversized OUT record: line over the derived cap
     * (65536 hex chars + 64). */
    pos = 0;
    memcpy(line + pos, "DEALPG4 OUT 1 out ", 18);
    pos += 18;
    memset(line + pos, 'a', 65584);
    pos += 65584;
    line[pos++] = '\n';
    line[pos] = '\0';
    CHECK(classify_line(line) == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(parse_line(line, &p) == DEALPG4_PARSE_ERR_OVERSIZE_LINE);
    /* OUT chunk over the explicit 65536-hex-char cap (line still under
     * the derived line cap). */
    pos = 0;
    memcpy(line + pos, "DEALPG4 OUT 1 out ", 18);
    pos += 18;
    memset(line + pos, 'a', 65538);
    pos += 65538;
    line[pos++] = '\n';
    line[pos] = '\0';
    CHECK(classify_line(line) == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(parse_line(line, &p) == DEALPG4_PARSE_ERR_OVERSIZE_OUT_CHUNK);
    /* Cross-validation negatives (broker-client review finding): the
     * same oversize inputs the Java broker client's read path must
     * reject identically -- an OUT line with an 80000-hex-char chunk
     * (over the 65536+64 line cap) and a REPORT whose 9000-char
     * failureToken pushes the line past the 8192-byte cap. */
    pos = 0;
    memcpy(line + pos, "DEALPG4 OUT 7 out ", 18);
    pos += 18;
    memset(line + pos, 'a', 80000);
    pos += 80000;
    line[pos++] = '\n';
    line[pos] = '\0';
    CHECK(classify_line(line) == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(parse_line(line, &p) == DEALPG4_PARSE_ERR_OVERSIZE_LINE);
    {
        static const char report_prefix[] =
            "DEALPG4 REPORT 0 0 1 2 3 4 5 6 7 8 9 10 11 0 0 1 1 1 ";
        pos = 0;
        memcpy(line + pos, report_prefix, sizeof(report_prefix) - 1);
        pos += sizeof(report_prefix) - 1;
        memset(line + pos, 'c', 9000);
        pos += 9000;
        line[pos++] = '\n';
        line[pos] = '\0';
    }
    CHECK(classify_line(line) == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(parse_line(line, &p) == DEALPG4_PARSE_ERR_OVERSIZE_LINE);
    /* A record unexpected in the configured channel state. */
    CHECK(parse_line("DEALPG4 DONE clean\n", &p) == DEALPG4_PARSE_OK);
    dealpg4_expectation_set_init(&expected);
    dealpg4_expectation_set_add(&expected, DEALPG4_REC_STARTED);
    dealpg4_expectation_set_add(&expected, DEALPG4_REC_FAILED);
    CHECK(dealpg4_expectation_check(&expected, &p)
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(parse_line("DEALPG4 STARTED 7\n", &p) == DEALPG4_PARSE_OK);
    CHECK(dealpg4_expectation_check(&expected, &p) == DEALPG4_CLASS_OK);
}

/* === Group 2: INVOKE semantic defects are record-level ================ */

static dealpg4_classification invoke_semantic_of(const char *line,
                                                 dealpg4_parsed *p)
{
    unsigned char cwd[DEALPG4_MAX_LINE_INVOKE_BYTES / 2];
    const dealpg4_field_slice *argc_field;
    const dealpg4_field_slice *cwd_field;
    size_t cwd_len = 0;
    int64_t argc = 0;

    if (parse_line(line, p) != DEALPG4_PARSE_OK)
        return DEALPG4_CLASS_PROTOCOL_ERROR;
    argc_field = dealpg4_parsed_field(p, 2);
    cwd_field = dealpg4_parsed_field(p, 1);
    if (argc_field == NULL || cwd_field == NULL)
        return DEALPG4_CLASS_PROTOCOL_ERROR;
    (void)dealpg4_field_decimal(argc_field, &argc);
    (void)dealpg4_hex_decode(cwd_field, cwd, sizeof(cwd), &cwd_len);
    return dealpg4_invoke_semantic_check(argc, p->argv_count, cwd,
                                         cwd_len);
}

static void group2_invoke_semantic_defects_record_level(void)
{
    dealpg4_parsed p;

    /* argc != the number of argv fields. */
    CHECK(invoke_semantic_of("DEALPG4 INVOKE tag 2f 2 61\n", &p)
          == DEALPG4_CLASS_MALFORMED_INVOKE);
    /* argc 0. */
    CHECK(invoke_semantic_of("DEALPG4 INVOKE tag 2f 0\n", &p)
          == DEALPG4_CLASS_MALFORMED_INVOKE);
    /* Empty argv (argc 1, no argv fields). */
    CHECK(invoke_semantic_of("DEALPG4 INVOKE tag 2f 1\n", &p)
          == DEALPG4_CLASS_MALFORMED_INVOKE);
    /* Empty cwd (framing-legal empty hex string decodes to an empty
     * path). */
    CHECK(invoke_semantic_of("DEALPG4 INVOKE tag  1 61\n", &p)
          == DEALPG4_CLASS_MALFORMED_INVOKE);
    /* cwd hex fails UTF-8 decoding. */
    CHECK(invoke_semantic_of("DEALPG4 INVOKE tag ff 1 61\n", &p)
          == DEALPG4_CLASS_MALFORMED_INVOKE);
    /* cwd decodes to a NUL-containing path. */
    CHECK(invoke_semantic_of("DEALPG4 INVOKE tag 2f00 1 61\n", &p)
          == DEALPG4_CLASS_MALFORMED_INVOKE);
    /* The channel stays open: a record-level classification is never
     * PROTOCOL_ERROR. */
    CHECK(invoke_semantic_of("DEALPG4 INVOKE tag 2f 2 61\n", &p)
          != DEALPG4_CLASS_PROTOCOL_ERROR);
    /* A well-formed, semantically valid INVOKE passes. */
    CHECK(invoke_semantic_of("DEALPG4 INVOKE tag 2f 1 61\n", &p)
          == DEALPG4_CLASS_OK);
}

/* === Group 3: framing-defective INVOKE closes the channel ============= */

static void group3_invoke_framing_defects_close_channel(void)
{
    char line[DEALPG4_MAX_LINE_INVOKE_BYTES + 64];
    dealpg4_parsed p;
    size_t pos;

    /* Non-hex argv element. */
    CHECK(classify_line("DEALPG4 INVOKE tag 2f 1 zz\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    /* Odd-length argv hex. */
    CHECK(classify_line("DEALPG4 INVOKE tag 2f 1 61a\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    /* Uppercase argv hex. */
    CHECK(classify_line("DEALPG4 INVOKE tag 2f 1 6A\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    /* Non-hex cwd. */
    CHECK(classify_line("DEALPG4 INVOKE tag zz 1 61\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    /* Odd-length cwd hex. */
    CHECK(classify_line("DEALPG4 INVOKE tag 2fa 1 61\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    /* Uppercase cwd hex. */
    CHECK(classify_line("DEALPG4 INVOKE tag 2F 1 61\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    /* Non-decimal argc. */
    CHECK(classify_line("DEALPG4 INVOKE tag 2f one 61\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    /* Field-count violation: fixed fields missing. */
    CHECK(classify_line("DEALPG4 INVOKE tag 2f\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(parse_line("DEALPG4 INVOKE tag 2f\n", &p)
          == DEALPG4_PARSE_ERR_FIELD_COUNT);
    /* Size violation: INVOKE line over the 131072-byte cap. */
    pos = 0;
    memcpy(line + pos, "DEALPG4 INVOKE tag 2f 1 ", 24);
    pos += 24;
    memset(line + pos, 'a', 131050);
    pos += 131050;
    line[pos++] = '\n';
    line[pos] = '\0';
    CHECK(classify_line(line) == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(parse_line(line, &p) == DEALPG4_PARSE_ERR_OVERSIZE_LINE);
    /* Size violation: raw argv at the 64 KiB budget already pushes the
     * line past the 131072-byte cap, so the record classifies
     * PROTOCOL_ERROR either way (the line cap dominates the argv cap
     * on the read path; the argv cap is the write-side belt-and-braces
     * check, group 6). */
    pos = 0;
    memcpy(line + pos, "DEALPG4 INVOKE tag 2f 2 ", 24);
    pos += 24;
    memset(line + pos, 'a', 65536);
    pos += 65536;
    line[pos++] = ' ';
    memset(line + pos, 'a', 65536);
    pos += 65536;
    line[pos++] = '\n';
    line[pos] = '\0';
    CHECK(classify_line(line) == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(parse_line(line, &p) == DEALPG4_PARSE_ERR_OVERSIZE_LINE);
}

/* === Group 4: ACK classification ====================================== */

static void group4_ack_classification(void)
{
    dealpg4_parsed p;
    dealpg4_expectation_set expected;
    dealpg4_ack_facts facts;

    /* Framing-malformed ACK closes the channel. */
    CHECK(classify_line("DEALPG4 ACK 1 zz\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(classify_line("DEALPG4 ACK 1\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(classify_line(
              "DEALPG4 ACK 1 0123456789ABCDEF0123456789ABCDEF\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    CHECK(classify_line("DEALPG4 ACK abc 0123456789abcdef0123456789abcdef\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
    /* A well-formed ACK parses. */
    CHECK(parse_line("DEALPG4 ACK 1 0123456789abcdef0123456789abcdef\n", &p)
          == DEALPG4_PARSE_OK);
    /* Nested side: nonce mismatch -> record-level AUTH_FAILED. */
    facts.invocation_id_known = 1;
    facts.record_in_apply_phase = 1;
    facts.nonce_matches = 0;
    facts.first_ack = 1;
    CHECK(dealpg4_ack_classify(&facts) == DEALPG4_CLASS_AUTH_FAILED);
    CHECK(dealpg4_ack_classify(&facts) != DEALPG4_CLASS_PROTOCOL_ERROR);
    /* Nested side: second ACK while still pre-release -> AUTH_FAILED. */
    facts.nonce_matches = 1;
    facts.first_ack = 0;
    CHECK(dealpg4_ack_classify(&facts) == DEALPG4_CLASS_AUTH_FAILED);
    /* Nested side: every fact holds -> OK. */
    facts.invocation_id_known = 1;
    facts.record_in_apply_phase = 1;
    facts.nonce_matches = 1;
    facts.first_ack = 1;
    CHECK(dealpg4_ack_classify(&facts) == DEALPG4_CLASS_OK);
    /* Outer side: unknown invocationId -> AUTH_FAILED. */
    facts.invocation_id_known = 0;
    facts.record_in_apply_phase = 0;
    facts.nonce_matches = 0;
    facts.first_ack = 1;
    CHECK(dealpg4_ack_classify(&facts) == DEALPG4_CLASS_AUTH_FAILED);
    /* Outer side: terminal record -> AUTH_FAILED. */
    facts.invocation_id_known = 1;
    facts.record_in_apply_phase = 0;
    facts.nonce_matches = 1;
    facts.first_ack = 1;
    CHECK(dealpg4_ack_classify(&facts) == DEALPG4_CLASS_AUTH_FAILED);
    /* Outer side: nonce mismatch -> AUTH_FAILED. */
    facts.record_in_apply_phase = 1;
    facts.nonce_matches = 0;
    CHECK(dealpg4_ack_classify(&facts) == DEALPG4_CLASS_AUTH_FAILED);
    /* Outer side: second ACK for the same live record -> AUTH_FAILED. */
    facts.nonce_matches = 1;
    facts.first_ack = 0;
    CHECK(dealpg4_ack_classify(&facts) == DEALPG4_CLASS_AUTH_FAILED);
    /* Post-release ACK: state-unexpected -> PROTOCOL_ERROR via the
     * expectation set (the nested supervisor excludes ACK from the
     * post-release channel state). */
    dealpg4_expectation_set_init(&expected);
    dealpg4_expectation_set_add(&expected, DEALPG4_REC_STARTED);
    dealpg4_expectation_set_add(&expected, DEALPG4_REC_FAILED);
    CHECK(dealpg4_expectation_check(&expected, &p)
          == DEALPG4_CLASS_PROTOCOL_ERROR);
}

/* === Group 5: CANCEL classification =================================== */

static void group5_cancel_classification(void)
{
    dealpg4_parsed p;
    dealpg4_cancel_facts facts;

    CHECK(parse_line("DEALPG4 CANCEL 1 0123456789abcdef0123456789abcdef\n",
                     &p) == DEALPG4_PARSE_OK);
    /* A well-formed CANCEL for a live record with the matching nonce
     * applies. */
    facts.invocation_id_known = 1;
    facts.record_live = 1;
    facts.nonce_matches = 1;
    CHECK(dealpg4_cancel_classify(&facts) == DEALPG4_CLASS_OK);
    /* ... and is never itself a channel-close event. */
    CHECK(dealpg4_cancel_classify(&facts)
          != DEALPG4_CLASS_PROTOCOL_ERROR);
    /* Nonce mismatch -> record-level CANCEL_AUTH_FAILED, channel open,
     * the cancel not applied. */
    facts.nonce_matches = 0;
    CHECK(dealpg4_cancel_classify(&facts)
          == DEALPG4_CLASS_CANCEL_AUTH_FAILED);
    CHECK(dealpg4_cancel_classify(&facts)
          != DEALPG4_CLASS_PROTOCOL_ERROR);
    /* Terminal record -> CANCEL_AUTH_FAILED. */
    facts.nonce_matches = 1;
    facts.record_live = 0;
    CHECK(dealpg4_cancel_classify(&facts)
          == DEALPG4_CLASS_CANCEL_AUTH_FAILED);
    /* Unknown invocationId -> CANCEL_AUTH_FAILED. */
    facts.invocation_id_known = 0;
    facts.record_live = 0;
    facts.nonce_matches = 0;
    CHECK(dealpg4_cancel_classify(&facts)
          == DEALPG4_CLASS_CANCEL_AUTH_FAILED);
    /* Framing-malformed CANCEL (bad nonce hex) is the channel-close
     * classification, reserved for framing defects. */
    CHECK(classify_line("DEALPG4 CANCEL 1 zz\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);
}

/* === Group 6: serialization =========================================== */

static void roundtrip(dealpg4_record_type type,
                      const dealpg4_field_value *fields, size_t nfields)
{
    char buf[DEALPG4_MAX_LINE_INVOKE_BYTES + 1];
    size_t written = 0;
    size_t i;
    dealpg4_parsed p;

    CHECK(dealpg4_serialize(type, fields, nfields, buf, sizeof(buf),
                            &written) == 0);
    CHECK(written >= 9 && buf[written - 1] == '\n');
    CHECK(dealpg4_parse(buf, written, &p) == DEALPG4_PARSE_OK);
    CHECK(p.type == type);
    if (type == DEALPG4_REC_INVOKE) {
        CHECK(p.nfields == 3);
        CHECK(dealpg4_parsed_argv_count(&p) == nfields - 3);
        for (i = 0; i < 3; i++)
            CHECK(field_slices_equal(&p.fields[i], &fields[i]));
        for (i = 3; i < nfields; i++) {
            dealpg4_field_slice s;

            CHECK(dealpg4_parsed_argv_element(&p, i - 3, &s) == 0);
            CHECK(field_slices_equal(&s, &fields[i]));
        }
    } else {
        CHECK(p.nfields == nfields);
        for (i = 0; i < nfields; i++)
            CHECK(field_slices_equal(&p.fields[i], &fields[i]));
    }
}

#define FIELD(s) { (s), strlen(s) }

static const char *nonce32 = "0123456789abcdef0123456789abcdef";

static void group6_serialization(void)
{
    char buf[64];
    size_t written = 0;
    size_t i;
    /* Every catalog record serializes and round-trips to identical
     * field values. */
    {
        const dealpg4_field_value hello[] = { FIELD(nonce32) };
        roundtrip(DEALPG4_REC_HELLO, hello, 1);
    }
    {
        const dealpg4_field_value hello_ok[] = { FIELD("4"), FIELD("31") };
        roundtrip(DEALPG4_REC_HELLO_OK, hello_ok, 2);
    }
    {
        /* Leading-zero decimal version: the VERSION_4 field class
         * accepts any decimal text whose value is exactly 4. */
        const dealpg4_field_value hello_ok_v04[] = { FIELD("04"), FIELD("31") };
        roundtrip(DEALPG4_REC_HELLO_OK, hello_ok_v04, 2);
    }
    {
        const dealpg4_field_value feature_ready[] = { FIELD(nonce32) };
        roundtrip(DEALPG4_REC_FEATURE_READY, feature_ready, 1);
    }
    {
        const dealpg4_field_value ready_ack[] = { FIELD(nonce32) };
        roundtrip(DEALPG4_REC_READY_ACK, ready_ack, 1);
    }
    {
        const dealpg4_field_value invoke[] = {
            FIELD("client_a"), FIELD("2f7573722f62696e"), FIELD("1"),
            FIELD("6563686f")
        };
        roundtrip(DEALPG4_REC_INVOKE, invoke, 4);
    }
    {
        const dealpg4_field_value invoked[] = {
            FIELD("7"), FIELD("client_a")
        };
        roundtrip(DEALPG4_REC_INVOKED, invoked, 2);
    }
    {
        const dealpg4_field_value reject[] = {
            FIELD("0"), FIELD("-"), FIELD("CANCEL_AUTH_FAILED")
        };
        roundtrip(DEALPG4_REC_REJECT, reject, 3);
    }
    {
        const dealpg4_field_value reject2[] = {
            FIELD("7"), FIELD("client_a"), FIELD("AUTH_FAILED")
        };
        roundtrip(DEALPG4_REC_REJECT, reject2, 3);
    }
    {
        const dealpg4_field_value stub_forked[] = {
            FIELD("1"), FIELD("42")
        };
        roundtrip(DEALPG4_REC_STUB_FORKED, stub_forked, 2);
    }
    {
        const dealpg4_field_value stub_identity[] = {
            FIELD("42"), FIELD("43"), FIELD("41"), FIELD(nonce32)
        };
        roundtrip(DEALPG4_REC_STUB_IDENTITY, stub_identity, 4);
    }
    {
        const dealpg4_field_value stub_failed[] = {
            FIELD("42"), FIELD("1")
        };
        roundtrip(DEALPG4_REC_STUB_FAILED, stub_failed, 2);
    }
    {
        const dealpg4_field_value stub_exec_failed[] = {
            FIELD("42"), FIELD("2")
        };
        roundtrip(DEALPG4_REC_STUB_EXEC_FAILED, stub_exec_failed, 2);
    }
    {
        const dealpg4_field_value release_recv[] = { FIELD("42") };
        roundtrip(DEALPG4_REC_RELEASE_RECV, release_recv, 1);
    }
    {
        const dealpg4_field_value stub_ready[] = {
            FIELD("1"), FIELD("42"), FIELD("43"), FIELD("41"),
            FIELD(nonce32)
        };
        roundtrip(DEALPG4_REC_STUB_READY, stub_ready, 5);
    }
    {
        const dealpg4_field_value coord_ready[] = {
            FIELD("10"), FIELD("11"), FIELD("12")
        };
        roundtrip(DEALPG4_REC_COORD_READY, coord_ready, 3);
    }
    {
        const dealpg4_field_value coord_exec_failed[] = {
            FIELD("10"), FIELD("2")
        };
        roundtrip(DEALPG4_REC_COORD_EXEC_FAILED, coord_exec_failed, 2);
    }
    {
        const dealpg4_field_value ack[] = { FIELD("1"), FIELD(nonce32) };
        roundtrip(DEALPG4_REC_ACK, ack, 2);
    }
    {
        const dealpg4_field_value started[] = { FIELD("1") };
        roundtrip(DEALPG4_REC_STARTED, started, 1);
    }
    {
        const dealpg4_field_value exec_failed[] = {
            FIELD("1"), FIELD("2")
        };
        roundtrip(DEALPG4_REC_EXEC_FAILED, exec_failed, 2);
    }
    {
        const dealpg4_field_value out[] = {
            FIELD("1"), FIELD("out"), FIELD("6869")
        };
        roundtrip(DEALPG4_REC_OUT, out, 3);
    }
    {
        const dealpg4_field_value out_err[] = {
            FIELD("2"), FIELD("err"), FIELD("6572726f72")
        };
        roundtrip(DEALPG4_REC_OUT, out_err, 3);
    }
    {
        const dealpg4_field_value out_end[] = {
            FIELD("1"), FIELD("err")
        };
        roundtrip(DEALPG4_REC_OUT_END, out_end, 2);
    }
    {
        const dealpg4_field_value cancel[] = {
            FIELD("1"), FIELD(nonce32)
        };
        roundtrip(DEALPG4_REC_CANCEL, cancel, 2);
    }
    {
        const dealpg4_field_value report[] = {
            FIELD("0"), FIELD("0"), FIELD("1500"), FIELD("200"),
            FIELD("300"), FIELD("10"), FIELD("5"), FIELD("100"),
            FIELD("50"), FIELD("2"), FIELD("1"), FIELD("4096"),
            FIELD("8192"), FIELD("0"), FIELD("1"), FIELD("0"),
            FIELD("1"), FIELD("1"), FIELD("-")
        };
        roundtrip(DEALPG4_REC_REPORT, report, 19);
    }
    {
        const dealpg4_field_value clean[] = {
            FIELD("1"), FIELD("success")
        };
        roundtrip(DEALPG4_REC_CLEAN, clean, 2);
    }
    {
        const dealpg4_field_value failed[] = {
            FIELD("1"), FIELD("EXECUTION_TIMEOUT")
        };
        roundtrip(DEALPG4_REC_FAILED, failed, 2);
    }
    {
        const dealpg4_field_value done[] = { FIELD("clean") };
        roundtrip(DEALPG4_REC_DONE, done, 1);
    }
    {
        roundtrip(DEALPG4_REC_BYE, NULL, 0);
    }

    /* Oversize and class-violating requests are refused (a malformed
     * line is never emitted). */
    {
        const dealpg4_field_value bad_final[] = {
            FIELD("1"), FIELD("maybe")
        };
        const dealpg4_field_value bad_stream[] = {
            FIELD("1"), FIELD("sideways"), FIELD("6869")
        };
        const dealpg4_field_value bad_hex[] = {
            FIELD("1"), FIELD("out"), FIELD("6zz")
        };
        const dealpg4_field_value bad_dec[] = { FIELD("abc") };
        const dealpg4_field_value too_many[] = { FIELD("1"), FIELD("2") };

        CHECK(dealpg4_serialize(DEALPG4_REC_UNKNOWN, bad_dec, 1, buf,
                                sizeof(buf), &written) == -1);
        CHECK(dealpg4_serialize(DEALPG4_REC_STARTED, too_many, 2, buf,
                                sizeof(buf), &written) == -1);
        CHECK(dealpg4_serialize(DEALPG4_REC_CLEAN, bad_final, 2, buf,
                                sizeof(buf), &written) == -1);
        CHECK(dealpg4_serialize(DEALPG4_REC_OUT, bad_stream, 3, buf,
                                sizeof(buf), &written) == -1);
        CHECK(dealpg4_serialize(DEALPG4_REC_OUT, bad_hex, 3, buf,
                                sizeof(buf), &written) == -1);
        CHECK(dealpg4_serialize(DEALPG4_REC_STARTED, bad_dec, 1, buf,
                                sizeof(buf), &written) == -1);
    }
    /* OUT chunk over the explicit 65536-hex-char cap. */
    {
        char chunk[DEALPG4_OUT_MAX_HEX_CHARS + 4];
        dealpg4_field_value out_oversize[3];

        memset(chunk, 'a', sizeof(chunk) - 1);
        chunk[sizeof(chunk) - 1] = '\0';
        out_oversize[0] = (dealpg4_field_value){ "1", 1 };
        out_oversize[1] = (dealpg4_field_value){ "out", 3 };
        out_oversize[2] = (dealpg4_field_value){
            chunk, DEALPG4_OUT_MAX_HEX_CHARS + 2
        };
        CHECK(dealpg4_serialize(DEALPG4_REC_OUT, out_oversize, 3, buf,
                                sizeof(buf), &written) == -1);
    }
    /* Non-INVOKE/OUT record over the 8192-byte line cap (a class-valid
     * long token). */
    {
        char longtoken[9000];
        dealpg4_field_value report_long[19];

        memset(longtoken, 'z', sizeof(longtoken) - 1);
        longtoken[sizeof(longtoken) - 1] = '\0';
        for (i = 0; i < 13; i++)
            report_long[i] = (dealpg4_field_value){ "0", 1 };
        for (i = 13; i < 18; i++)
            report_long[i] = (dealpg4_field_value){ "1", 1 };
        report_long[18] = (dealpg4_field_value){ longtoken, 8999 };
        CHECK(dealpg4_serialize(DEALPG4_REC_REPORT, report_long, 19, buf,
                                sizeof(buf), &written) == -1);
    }
    /* INVOKE over the 131072-byte line cap. */
    {
        char argv[131056];
        dealpg4_field_value invoke_oversize[4];

        memset(argv, 'a', sizeof(argv));
        invoke_oversize[0] = (dealpg4_field_value){ "tag", 3 };
        invoke_oversize[1] = (dealpg4_field_value){ "2f", 2 };
        invoke_oversize[2] = (dealpg4_field_value){ "1", 1 };
        invoke_oversize[3] = (dealpg4_field_value){ argv, sizeof(argv) };
        CHECK(dealpg4_serialize(DEALPG4_REC_INVOKE, invoke_oversize, 4,
                                buf, sizeof(buf), &written) == -1);
    }
    /* A conforming record that does not fit the caller's buffer is
     * refused with ENOBUFS. */
    {
        const dealpg4_field_value started[] = { FIELD("1") };

        errno = 0;
        CHECK(dealpg4_serialize(DEALPG4_REC_STARTED, started, 1, buf, 8,
                                &written) == -1);
        CHECK(errno == ENOBUFS);
    }
}

/* === Group 7: nonce helper and catalog closure ======================== */

static void group7_nonce_and_catalog_closure(void)
{
    static const char *const canonical[DEALPG4_REC_COUNT] = {
        "HELLO", "HELLO_OK", "FEATURE_READY", "READY_ACK", "INVOKE",
        "INVOKED", "REJECT", "STUB_FORKED", "STUB_IDENTITY",
        "STUB_FAILED", "STUB_EXEC_FAILED", "RELEASE_RECV", "STUB_READY",
        "COORD_READY", "COORD_EXEC_FAILED", "ACK", "STARTED",
        "EXEC_FAILED", "OUT", "OUT_END", "CANCEL", "REPORT", "CLEAN",
        "FAILED", "DONE", "BYE"
    };
    char nonce1[33];
    char nonce2[33];
    size_t i;

    CHECK(dealpg4_record_type_count() == 26);
    CHECK(DEALPG4_REC_COUNT == 26);
    for (i = 0; i < DEALPG4_REC_COUNT; i++) {
        CHECK(dealpg4_record_type_from_name(canonical[i],
                                            strlen(canonical[i]))
              == (dealpg4_record_type)i);
        CHECK(strcmp(dealpg4_record_type_name((dealpg4_record_type)i),
                     canonical[i]) == 0);
    }
    /* The catalog is closed: no deadline-extension record exists, and
     * any unknown type -- extension or otherwise -- is UNKNOWN
     * (PROTOCOL_ERROR downstream). */
    CHECK(dealpg4_record_type_from_name("DEADLINE_EXTENSION",
                                        strlen("DEADLINE_EXTENSION"))
          == DEALPG4_REC_UNKNOWN);
    CHECK(dealpg4_record_type_from_name("EXTEND_DEADLINE",
                                        strlen("EXTEND_DEADLINE"))
          == DEALPG4_REC_UNKNOWN);
    CHECK(classify_line("DEALPG4 DEADLINE_EXTENSION 1\n")
          == DEALPG4_CLASS_PROTOCOL_ERROR);

    /* Direction metadata matches the catalog rows. */
    CHECK(dealpg4_record_direction(DEALPG4_REC_BYE) == DEALPG4_DIR_ANY);
    CHECK(dealpg4_record_direction(DEALPG4_REC_INVOKE)
          == DEALPG4_DIR_COORD_TO_OUTER);
    CHECK(dealpg4_record_direction(DEALPG4_REC_ACK)
          == (DEALPG4_DIR_COORD_TO_OUTER | DEALPG4_DIR_OUTER_TO_NESTED));
    CHECK(dealpg4_record_direction(DEALPG4_REC_REPORT)
          == (DEALPG4_DIR_NESTED_TO_OUTER | DEALPG4_DIR_OUTER_TO_COORD));

    /* Size caps. */
    CHECK(DEALPG4_MAX_LINE_INVOKE_BYTES == 131072);
    CHECK(DEALPG4_MAX_LINE_OTHER_BYTES == 8192);
    CHECK(DEALPG4_OUT_MAX_HEX_CHARS == 65536);
    CHECK(DEALPG4_INVOKE_MAX_ARGV_RAW_BYTES == 65536);
    CHECK(DEALPG4_NONCE_BYTES == 16);
    CHECK(DEALPG4_NONCE_HEX_CHARS == 32);

    /* Nonce helper: 16 kernel-entropy bytes rendered as exactly 32
     * lowercase hex characters; two calls differ. */
    CHECK(dealpg4_nonce_hex(nonce1) == 0);
    CHECK(strlen(nonce1) == 32);
    for (i = 0; i < 32; i++) {
        char c = nonce1[i];

        CHECK((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }
    CHECK(dealpg4_nonce_hex(nonce2) == 0);
    CHECK(strlen(nonce2) == 32);
    CHECK(strcmp(nonce1, nonce2) != 0);
}

/* === Runner =========================================================== */

typedef void (*group_fn)(void);

int main(void)
{
    static const struct {
        group_fn fn;
        const char *name;
    } groups[] = {
        { group1_framing_defects_close_channel,
          "group 1: framing defects / unknown / oversized / "
          "state-unexpected -> PROTOCOL_ERROR" },
        { group2_invoke_semantic_defects_record_level,
          "group 2: well-formed INVOKE semantic defects -> "
          "record-level MALFORMED_INVOKE" },
        { group3_invoke_framing_defects_close_channel,
          "group 3: framing-defective INVOKE -> PROTOCOL_ERROR" },
        { group4_ack_classification,
          "group 4: ACK classification (AUTH_FAILED vs PROTOCOL_ERROR)" },
        { group5_cancel_classification,
          "group 5: CANCEL classification (CANCEL_AUTH_FAILED, "
          "channel open)" },
        { group6_serialization,
          "group 6: serialization refusals and full-catalog round-trip" },
        { group7_nonce_and_catalog_closure,
          "group 7: nonce helper and catalog closure" }
    };
    size_t i;

    for (i = 0; i < sizeof(groups) / sizeof(groups[0]); i++) {
        int before = g_failures;

        groups[i].fn();
        printf("%s %s\n", g_failures == before ? "PASS" : "FAIL",
               groups[i].name);
    }
    if (g_failures != 0) {
        fprintf(stderr,
                "protocol unit cases: %d of %d checks FAILED\n",
                g_failures, g_checks);
        return 1;
    }
    printf("protocol unit cases: all 7 groups passed (%d checks)\n",
           g_checks);
    return 0;
}
