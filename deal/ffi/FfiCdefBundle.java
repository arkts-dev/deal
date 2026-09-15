package deal.ffi;

import java.util.List;
import java.util.Objects;

/**
 * The immutable generated cdef bundle of one extern-C module (design
 * source {@code deal-v1.2-directives-and-c-ffi-declarations} D7,
 * {@code luajit-ffi-generated-content-seam} S1–S4):
 * {@code CdefBundle(bundleDigest, fullContent, orderedEntries)} plus the
 * settled argument-carried identity fields (native-library reference,
 * function/class metadata).
 *
 * <ul>
 *   <li>{@code fullContent} — the complete canonical UTF-8 cdef content
 *       (the concatenation of the ordered entry texts); it participates
 *       byte-exactly in runtime identity equality and remains fully
 *       available for collision checks.</li>
 *   <li>{@code bundleDigest} — SHA-256 over {@code fullContent}; an
 *       index only.</li>
 *   <li>{@code identityDigest} — SHA-256 over the canonical descriptor
 *       content; carried opaque and never consulted for equality.</li>
 *   <li>{@code entries} — the ordered {@link FfiCdefEntry} list in cdef
 *       dependency order (struct typedefs before the function typedefs
 *       that reference them).</li>
 *   <li>{@code nativeLibraryKind}/{@code nativeLibraryLoaderText} — the
 *       compile-side classification carried verbatim for the loader
 *       input ({@code BARE_NAME | ABSOLUTE_PATH |
 *       MANIFEST_RELATIVE_PATH}); {@code null}/{@code null} when the
 *       external entry carries no library (a runtime
 *       {@code FFI_LIBRARY_LOAD} case).</li>
 *   <li>{@code functions}/{@code classes} — the descriptor's frozen
 *       metadata rows, carried as the serialization-ready shapes of the
 *       runtime seam ({@code bundle.functions}/{@code bundle.classes}).</li>
 * </ul>
 */
public record FfiCdefBundle(
    String bundleDigest,
    String identityDigest,
    String fullContent,
    List<FfiCdefEntry> entries,
    String nativeLibraryKind,
    String nativeLibraryLoaderText,
    List<FfiFunctionDescriptor> functions,
    List<FfiClassDescriptor> classes) {

    public FfiCdefBundle {
        Objects.requireNonNull(bundleDigest, "bundleDigest");
        Objects.requireNonNull(identityDigest, "identityDigest");
        Objects.requireNonNull(fullContent, "fullContent");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        functions = List.copyOf(Objects.requireNonNull(functions, "functions"));
        classes = List.copyOf(Objects.requireNonNull(classes, "classes"));
        if ((nativeLibraryKind == null) != (nativeLibraryLoaderText == null)) {
            throw new IllegalArgumentException(
                "nativeLibraryKind and nativeLibraryLoaderText must both be"
                    + " present or both absent");
        }
    }
}
