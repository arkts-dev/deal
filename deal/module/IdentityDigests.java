package deal.module;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The shared digest helper of the identity carriers in {@code deal.module}
 * (deterministic, no address/timestamp/ordinal/process-state inputs):
 * SHA-256 over the pinned length-prefixed UTF-8 serialization of identity
 * inputs. The FFI binding generator (emitter page D6) consumes the same
 * facility for its opaque bundle/entry/plan index digests — the adopted
 * D1 rule makes those digests indexes only, and the compiler-wide
 * SHA-256 registry keeps this class the single implementation site.
 */
public final class IdentityDigests {

    private IdentityDigests() {
    }

    /**
     * SHA-256 over the exact input bytes, rendered as 64 lowercase hex
     * chars. Deterministic: identical bytes always produce the identical
     * digest.
     *
     * @param input the exact bytes to hash
     * @return the 64-lowercase-hex-char digest
     */
    public static String sha256Hex(byte[] input) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        byte[] hash = digest.digest(input);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /**
     * The concatenation of two exact byte arrays in order (identity-input
     * framing: each part is its own length-prefixed field).
     */
    static byte[] concat(byte[] first, byte[] second) {
        byte[] combined = new byte[first.length + second.length];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }
}
