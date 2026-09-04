package deal.ffi;

import java.util.List;
import java.util.Objects;

/**
 * One immutable generated cdef entry (design source
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D7,
 * {@code luajit-ffi-generated-content-seam} S2):
 * {@code CdefEntry(entryDigest, fullText, ownedNames)}.
 *
 * <ul>
 *   <li>{@code fullText} — the exact complete cdef text of the entry;
 *       the registry compares full texts for preflight and collision
 *       decisions — the text is retained, never reduced to a digest.</li>
 *   <li>{@code entryDigest} — SHA-256 over {@code fullText}; an index
 *       only, never consulted for equality.</li>
 *   <li>{@code ownedNames} — the private generated names this entry
 *       declares (typedef names only; real target-function prototypes
 *       are never declared), in declaration order.</li>
 * </ul>
 */
public record FfiCdefEntry(String entryDigest, String fullText,
                           List<String> ownedNames) {

    public FfiCdefEntry {
        Objects.requireNonNull(entryDigest, "entryDigest");
        Objects.requireNonNull(fullText, "fullText");
        ownedNames = List.copyOf(Objects.requireNonNull(ownedNames,
            "ownedNames"));
        if (fullText.isEmpty()) {
            throw new IllegalArgumentException("fullText must not be empty");
        }
        for (String name : ownedNames) {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException(
                    "ownedNames must not contain empty entries");
            }
        }
    }
}
