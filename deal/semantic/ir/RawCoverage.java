package deal.semantic.ir;

import java.util.List;
import java.util.Objects;

/**
 * One recorded construct-coverage row of the validator's raw intermediate
 * model (schema S1/S6): the raw construct name and the raw op-kind names,
 * both carried as raw strings so out-of-set and reserved names reach the
 * validator's rule checks byte-intact. The typed surface produces these
 * rows from T2's closed enums; the text surface produces them through the
 * single canonical JSON parser with no enum conversion.
 *
 * @param construct the raw construct name; non-null
 * @param opKinds   the raw mapped op-kind names; non-null
 */
public record RawCoverage(String construct, List<String> opKinds) {

    public RawCoverage {
        Objects.requireNonNull(construct, "construct must not be null");
        opKinds = List.copyOf(Objects.requireNonNull(opKinds, "opKinds must not be null"));
    }
}
