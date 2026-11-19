package deal.test;

import deal.codegen.Backend;
import java.nio.file.*;
import java.util.List;

/** Arithmetic assignment type rules and single-evaluation contracts. */
public final class CompoundAssignmentTest {
    public static void main(String[] args) throws Exception {
        for (String body : List.of("let n=1;n+=2;n-=1;n*=3;n/=2;n%=2;",
                "let s=\"a\";s+=\"b\";", "let n:unknown=1;n+=2;"))
            TypeLegalityFlowTest.check("function f():void{"+body+"}", null);
        for (String body : List.of("let n=1;n+=\"s\";", "let s=\"a\";s-=\"b\";",
                "let r:Record<string,unknown>={n:1};r.n+=1;"))
            TypeLegalityFlowTest.check("function f():void{"+body+"}", "E3010");
        TypeLegalityFlowTest.check("function f():void{const n=1;n+=1;}", "E3017");
        TypeLegalityFlowTest.check("function f(n:number|null):void{n+=1;}", "E3010");
        String source = """
            class Cell { n:number; }
            function check(ok:boolean):void{if(!ok){throw {name:"FAIL",message:"compound assignment"};}}
            export function main():void{
              let n=10;check((n+=2)===12);check((n-=4)===8);check((n*=3)===24);
              check((n/=2)===12);check((n%=5)===2);
              let s="a";check((s+="b")==="ab");
              let a=[10];let calls=0;let trace="";
              let receiver=():number[]=>{calls=calls+1;trace=trace+"r";return a;};
              let key=():number=>{trace=trace+"k";return 0;};
              let rhs=():number=>{trace=trace+"v";a[0]=100;return 3;};
              check((receiver()[key()]+=rhs())===13);check(a[0]===13);check(calls===1);check(trace==="rkv");
              let c:Cell={n:5};let other:Cell={n:90};
              let field=():Cell=>{calls=calls+1;return c;};
              let change=():number=>{c=other;return 2;};let original=c;
              check((field().n*=change())===10);check(original.n===10);check(c.n===90);check(calls===2);
              let bad=[1];let effects=0;
              let effect=():number=>{effects=effects+1;return 1;};
              let caught=false;try{bad[1]+=effect();}catch(e){caught=true;}
              check(caught);check(effects===0);check(bad.length===1);
              let clear=():number=>{bad.pop();return 2;};check((bad[0]+=clear())===3);check(bad[0]===3);
              let x:unknown=4;check((x+=2)===6);
              let z=-0;z*=2;check(1/z===-1/0);
              let inf=1;inf/=0;check(inf===1/0);inf%=2;check(Number.isNaN(inf));
              let left=1;let right=2;check((left+=right*=3)===7);check(right===6);
              let stale:number|null=1;let erase=():void=>{stale=null;};erase();
              let before=effects;caught=false;try{stale+=effect();}catch(e){caught=true;}
              check(caught);check(effects===before);
              let fail=():number=>{throw {name:"RHS",message:"stop"};};
              caught=false;try{n+=fail();}catch(e){caught=true;}check(caught);check(n===2);
              let retained=():number=>{n=40;throw {name:"RHS",message:"effects"};};
              try{n+=retained();}catch(e){}check(n===40);
            }
            """;
        Path root=Files.createTempDirectory("deal-compound-");
        try {
            CompositionProductionTest.execute(source,"",root,null,List.of(Backend.JS,Backend.LUAJIT,Backend.JVM,Backend.LLVM));
        } finally { CompositionProductionTest.remove(root); }
        System.out.println("Compound assignment: frontend and all production backends passed");
    }
}
