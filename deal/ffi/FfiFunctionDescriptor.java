package deal.ffi;

import java.util.List;
import java.util.Objects;

/**
 * One immutable C FFI function descriptor row (design source
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D4):
 * {@code FfiFunctionDescriptor(dealName, cSymbol,
 * privateFunctionPointerType, orderedParams, returnType)}.
 *
 * <ul>
 *   <li>{@code dealName} — the exported DEAL name exactly as declared;
 *       it is by definition the C symbol name (spec-v1.2: each exported
 *       function name is its C symbol name), so {@code cSymbol} equals
 *       {@code dealName} byte-for-byte.</li>
 *   <li>{@code privateFunctionPointerType} — the generated private
 *       function-pointer type spelling: the bare private typedef name
 *       for functions without struct involvement, or the full anonymous
 *       {@code RET (*)(P1, ...)} spelling when a {@code C_STRUCT}
 *       parameter/return participates (the runtime splitter contract,
 *       {@code luajit-ffi-generated-content-seam} S4/S7).</li>
 *   <li>{@code orderedParams} — the validated parameter rows in
 *       declaration order ({@code BYTES} lowers to two C parameter
 *       slots at emission, not here).</li>
 *   <li>{@code returnType} — the validated return row.</li>
 * </ul>
 *
 * <p>Immutable: the parameter list is defensively copied and frozen.</p>
 */
public record FfiFunctionDescriptor(String dealName, String cSymbol,
                                    String privateFunctionPointerType,
                                    List<FfiType> orderedParams,
                                    FfiType returnType) {

    public FfiFunctionDescriptor {
        Objects.requireNonNull(dealName, "dealName");
        Objects.requireNonNull(cSymbol, "cSymbol");
        Objects.requireNonNull(privateFunctionPointerType,
            "privateFunctionPointerType");
        Objects.requireNonNull(returnType, "returnType");
        if (dealName.isEmpty()) {
            throw new IllegalArgumentException("dealName must not be empty");
        }
        if (!dealName.equals(cSymbol)) {
            throw new IllegalArgumentException(
                "cSymbol must equal the exported DEAL name byte-for-byte: "
                    + "each exported function name is its C symbol name");
        }
        orderedParams = List.copyOf(Objects.requireNonNull(orderedParams,
            "orderedParams"));
    }
}
