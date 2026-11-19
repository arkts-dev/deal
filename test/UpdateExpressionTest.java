package deal.test;

import deal.codegen.Backend;
import java.nio.file.*;
import java.util.List;

/** Prefix/postfix values, address evaluation and numeric-only updates. */
public final class UpdateExpressionTest {
    public static void main(String[] args) throws Exception {
        for (String body : List.of("let n=1;++n;n++;--n;n--;", "let n=1;let p=- -n;"))
            TypeLegalityFlowTest.check("function f():void{"+body+"}", null);
        for (String body : List.of("let s=\"a\";s++;", "let b=true;--b;"))
            TypeLegalityFlowTest.check("function f():void{"+body+"}", "E3010");
        TypeLegalityFlowTest.check("function f():void{const n=1;n++;}", "E3017");
        TypeLegalityFlowTest.check("function f():void{let n=1;n\n++;}", "E1053");
        TypeLegalityFlowTest.check("function f():void{let n=1;n/*\n*/++;}", "E1053");
        TypeLegalityFlowTest.check("function f():void{++1;}", "E1033");
        TypeLegalityFlowTest.check("class C{n:number;}function f(c:C|null):void{c?.n++;}", "E1033");
        String source="""
            class C{n:number;}
            function check(ok:boolean):void{if(!ok){throw {name:"FAIL",message:"updates"};}}
            export function main():void{
              let n=1;check(n++===1);check(n===2);check(++n===3);check(n--===3);check(--n===1);
              let a=[3];let count=0;let log="";
              let receiver=():number[]=>{count+=1;log+="r";return a;};
              let key=():number=>{log+="k";return 0;};
              check(receiver()[key()]++===3);check(a[0]===4);check(count===1);check(log==="rk");
              check(--receiver()[key()]===3);check(count===2);check(log==="rkrk");
              let c:C={n:7};let field=():C=>{count++;return c;};
              check(field().n--===7);check(c.n===6);check(++field().n===7);check(count===4);
              let zero=-0;let old=zero++;check(1/old===-1/0);check(zero===1);
              let inf=1/0;check(inf++===1/0);check(inf===1/0);
              let nan=0/0;check(Number.isNaN(--nan));
              let caught=false;try{a[1]++;}catch(e){caught=true;}check(caught);check(a.length===1);
              let shared=2;let bump=():number=>shared++;check(bump()===2);check(bump()===3);check(shared===4);
              let step=0;while(step<4){if(step++===1){continue;}}check(step===4);
              check(- -n===n);
            }
            """;
        Path root=Files.createTempDirectory("deal-updates-");
        try {CompositionProductionTest.execute(source,"",root,null,List.of(Backend.JS,Backend.LUAJIT,Backend.JVM,Backend.LLVM));}
        finally {CompositionProductionTest.remove(root);}
        System.out.println("Updates: frontend and all production backends passed");
    }
}
