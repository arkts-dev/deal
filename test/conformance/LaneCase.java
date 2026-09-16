package deal.test.conformance;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One lane dispatch unit: a runtime-classified fixture dispatched to one
 * backend lane against that lane's structured expectation.
 *
 * <p>The gate core derives the compilation set (the fixture plus its
 * transitively imported corpus modules, resolved by module-import
 * resolution over the corpus) and supplies it — module paths plus
 * sources — so the lane can deploy the modules it compiles and normalize
 * captured error {@code file} values through its per-module deployment
 * map (corpus C2).</p>
 */
public record LaneCase(
    String fixturePath,
    String backend,
    Path fixtureFile,
    List<SidecarSchemaValidator.CompilationModule> compilationSet,
    SidecarExpectations.RuntimeExpectation expectation
) {

    public LaneCase {
        Objects.requireNonNull(fixturePath, "fixturePath must not be null");
        Objects.requireNonNull(backend, "backend must not be null");
        Objects.requireNonNull(fixtureFile, "fixtureFile must not be null");
        Objects.requireNonNull(compilationSet, "compilationSet must not be null");
        compilationSet = List.copyOf(compilationSet);
        Objects.requireNonNull(expectation, "expectation must not be null");
    }
}
