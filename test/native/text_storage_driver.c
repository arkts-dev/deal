/*
 * test/native/text_storage_driver.c — the R2/R8 text storage acceptance.
 *
 * A strict-C11 consumer of the published runtime: strict UTF-8 scalar-sequence
 * validation, immutable string storage with its exact byte length, scalar count
 * and cached hash, and the one scalar-value sequence comparison including BMP,
 * astral, prefix, equal and unequal cases. The ordering case `U+FFFF` versus
 * `U+1F600` distinguishes scalar order from UTF-16 code-unit order.
 *
 * `-DDEAL_TEXT_CASE=1` is the malformed-carrier negative: bytes that are not a
 * Unicode scalar sequence never become an admitted string, so the entry
 * terminates with one deterministic stderr line and exit 2 instead of
 * publishing malformed text.
 */
#include "deal/native/deal_rt.h"
#include "deal/native/memory.h"
#include "deal/native/platform.h"
#include "deal/native/text.h"

#include <stdio.h>
#include <string.h>

#ifndef DEAL_TEXT_CASE
#define DEAL_TEXT_CASE 0
#endif

#if DEAL_TEXT_CASE == 0

static int failures;

static void check(int condition, const char *what) {
    if (!condition) {
        printf("FAIL: %s\n", what);
        failures += 1;
    }
}

static deal_string *string_of(const char *text) {
    return deal_text_new(text, strlen(text));
}

static int same_bytes(const deal_string *string, const void *bytes,
                      size_t length) {
    if (deal_text_byte_length(string) != length) {
        return 0;
    }
    if (length == 0u) {
        return 1;
    }
    return memcmp(deal_text_bytes(string), bytes, length) == 0;
}

static void check_validation(void) {
    static const char two_byte[] = "\xC3\xA9";                 /* U+00E9   */
    static const char three_byte[] = "\xE2\x82\xAC";           /* U+20AC   */
    static const char four_byte[] = "\xF0\x9F\x98\x80";        /* U+1F600  */
    static const char overlong_two[] = "\xC0\x80";
    static const char overlong_three[] = "\xE0\x80\x80";
    static const char surrogate[] = "\xED\xA0\x80";            /* U+D800   */
    static const char above_maximum[] = "\xF4\x90\x80\x80";    /* U+110000 */
    static const char truncated[] = "\xE2\x82";
    static const char lone_continuation[] = "\x80";
    static const char never_valid[] = "\xF5\x80\x80\x80";

    check(deal_text_is_scalar_sequence(NULL, 0u) == 1,
          "the empty byte sequence is a scalar sequence");
    check(deal_text_is_scalar_sequence("plain", 5u) == 1,
          "ASCII is a scalar sequence");
    check(deal_text_is_scalar_sequence(two_byte, 2u) == 1,
          "a two-byte scalar is a scalar sequence");
    check(deal_text_is_scalar_sequence(three_byte, 3u) == 1,
          "a three-byte BMP scalar is a scalar sequence");
    check(deal_text_is_scalar_sequence(four_byte, 4u) == 1,
          "a four-byte astral scalar is a scalar sequence");
    check(deal_text_is_scalar_sequence("ab\xC3\xA9z", 5u) == 1,
          "a mixed scalar sequence is valid");
    check(deal_text_is_scalar_sequence(NULL, 1u) == 0,
          "null bytes with a non-zero length are malformed");
    check(deal_text_is_scalar_sequence(overlong_two, 2u) == 0,
          "an overlong two-byte form is malformed");
    check(deal_text_is_scalar_sequence(overlong_three, 3u) == 0,
          "an overlong three-byte form is malformed");
    check(deal_text_is_scalar_sequence(surrogate, 3u) == 0,
          "an unpaired surrogate is malformed");
    check(deal_text_is_scalar_sequence(above_maximum, 4u) == 0,
          "a code point above U+10FFFF is malformed");
    check(deal_text_is_scalar_sequence(truncated, 2u) == 0,
          "a truncated sequence is malformed");
    check(deal_text_is_scalar_sequence(lone_continuation, 1u) == 0,
          "a lone continuation byte is malformed");
    check(deal_text_is_scalar_sequence(never_valid, 4u) == 0,
          "a never-valid lead byte is malformed");
}

static void check_storage(void) {
    static const char bmp[] = "\xE2\x82\xAC";           /* U+20AC, 3 bytes */
    static const char astral[] = "\xF0\x9F\x98\x80";    /* U+1F600, 4 bytes */
    char mutable_text[] = "copied";
    deal_string *copied = deal_text_new(mutable_text, strlen(mutable_text));
    deal_string *same = deal_text_new(mutable_text, strlen(mutable_text));
    deal_string *empty = deal_text_new(NULL, 0u);
    deal_string *bmp_string = deal_text_new(bmp, 3u);
    deal_string *astral_string = deal_text_new(astral, 4u);

    check(deal_text_byte_length(copied) == 6u,
          "the string caches its exact byte length");
    check(deal_text_scalar_length(copied) == 6u,
          "an ASCII string has one scalar per byte");
    check(deal_text_scalar_length(bmp_string) == 1u,
          "a three-byte BMP scalar counts once");
    check(deal_text_byte_length(bmp_string) == 3u,
          "a three-byte BMP scalar keeps three bytes");
    check(deal_text_scalar_length(astral_string) == 1u,
          "a four-byte astral scalar counts once");
    check(deal_text_byte_length(astral_string) == 4u,
          "a four-byte astral scalar keeps four bytes");
    check(deal_text_byte_length(empty) == 0u
              && deal_text_scalar_length(empty) == 0u,
          "the empty string has no bytes and no scalars");

    /* the storage is immutable and owns its own copy of the bytes. */
    check(same_bytes(copied, "copied", 6u),
          "a string holds exactly the bytes it was built from");
    mutable_text[0] = 'X';
    check(same_bytes(copied, "copied", 6u),
          "a string copies its bytes and never aliases the caller buffer");
    check(same_bytes(same, "copied", 6u),
          "a second string keeps its own bytes");

    /* the hash is a cached content fact, not an identity or a seed. */
    check(deal_text_hash(copied) == deal_text_hash(same),
          "equal content hashes equally");
    check(deal_text_hash(string_of("a")) != deal_text_hash(string_of("b")),
          "different content hashes differently");
    check(deal_text_hash(empty) == deal_text_hash(deal_text_new(NULL, 0u)),
          "the empty string hash is deterministic");
    check(deal_text_hash(bmp_string) != deal_text_hash(astral_string),
          "different scalars hash differently");
    check(deal_root_frame_depth() == 0u && deal_root_scope_depth() == 0u,
          "the text entries leave the root depth restored");
}

static void check_comparison(void) {
    static const char noncharacter[] = "\xEF\xBF\xBF";   /* U+FFFF */
    static const char astral[] = "\xF0\x9F\x98\x80";     /* U+1F600 */
    static const char accented[] = "\xC3\xA9";           /* U+00E9 */
    static const char euro[] = "\xE2\x82\xAC";           /* U+20AC */
    deal_string *empty = deal_text_new(NULL, 0u);
    deal_string *letter_a = string_of("a");
    deal_string *letters_ab = string_of("ab");
    deal_string *letter_b = string_of("b");
    deal_string *same_a = string_of("a");
    deal_string *accented_string = deal_text_new(accented, 2u);
    deal_string *euro_string = deal_text_new(euro, 3u);
    deal_string *astral_string = deal_text_new(astral, 4u);
    deal_string *noncharacter_string = deal_text_new(noncharacter, 3u);

    check(deal_text_compare(letter_a, same_a) == 0,
          "equal scalar sequences compare equal");
    check(deal_text_compare(empty, empty) == 0,
          "the empty string equals itself");
    check(deal_text_compare(empty, letter_a) == -1,
          "the empty string sorts before a non-empty string");
    check(deal_text_compare(letter_a, empty) == 1,
          "a non-empty string sorts after the empty string");
    check(deal_text_compare(letter_a, letters_ab) == -1,
          "a proper prefix sorts first");
    check(deal_text_compare(letters_ab, letter_a) == 1,
          "the extended string sorts after its prefix");
    check(deal_text_compare(letter_a, letter_b) == -1, "a sorts before b");
    check(deal_text_compare(letter_b, letter_a) == 1, "b sorts after a");
    check(deal_text_compare(letter_b, accented_string) == -1,
          "ASCII sorts before a Latin-1 BMP scalar");
    check(deal_text_compare(accented_string, euro_string) == -1,
          "scalar order separates U+00E9 and U+20AC");
    check(deal_text_compare(euro_string, noncharacter_string) == -1,
          "scalar order separates U+20AC and U+FFFF");
    /* UTF-16 code-unit order would place the astral scalar before U+FFFF
     * (D83D < FFFF); Unicode scalar order places U+FFFF first. */
    check(deal_text_compare(noncharacter_string, astral_string) == -1,
          "the comparison orders by Unicode scalar value, not UTF-16 units");
    check(deal_text_compare(astral_string, euro_string) == 1,
          "an astral scalar sorts after a BMP scalar");
    check(deal_text_compare(letters_ab, accented_string) == -1,
          "a byte prefix sorts before a longer scalar sequence");
    check(deal_root_frame_depth() == 0u && deal_root_scope_depth() == 0u,
          "the comparison entries leave the root depth restored");
}

int main(void) {
    check_validation();
    check_storage();
    check_comparison();
    printf(failures == 0 ? "text storage ok\n" : "text storage failed\n");
    return failures == 0 ? 0 : 1;
}

#else
int main(void) {
    static const char overlong[] = "\xC0\x80";
    (void)deal_text_new(overlong, 2u);
    printf("unreachable\n");
    return 0;
}
#endif
