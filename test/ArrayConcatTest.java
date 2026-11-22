package deal.test;

import deal.codegen.Backend;
import java.nio.file.*;
import java.util.List;

/** Exact concat types and production/runtime conformance. */
public final class ArrayConcatTest {
    public static void main(String[] args) throws Exception {
        TypeLegalityFlowTest.check("function f():void{let a=[1];let b=a.concat([]);}",null);
        TypeLegalityFlowTest.check("function f():void{let a=[1];a.concat([\"x\"]);}","E3011");
        TypeLegalityFlowTest.check("function f():void{let a=[1];a.concat();}","E5001");
        TypeLegalityFlowTest.check("function f():void{let a=[1];a.concat([2],[3]);}","E5001");
        TypeLegalityFlowTest.check("function f():void{let a=[1];let fn=a.concat;}","E3001");
        String path="backend-runtime/arrays/concat.deal";
        String source=ConformanceHarnessMetadata.productionCorpusSource(Files.readString(Path.of("test/conformance/"+path)),path);
        Path root=Files.createTempDirectory("deal-concat-");
        try { CompositionProductionTest.execute(source,"",root,null,List.of(Backend.JS,Backend.LUAJIT,Backend.JVM,Backend.LLVM)); }
        finally { CompositionProductionTest.remove(root); }
        var corpus=Path.of("test/conformance").toAbsolutePath();
        var fixture=CorpusConformanceTest.nativeCorpusFixtures(corpus).stream().filter(f->f.corpusPath().equals(path)).findFirst().orElseThrow();
        LlvmConformanceTest.runInstrumented(corpus,fixture);
        System.out.println("Concat: frontend and all production backends passed");
    }
}
