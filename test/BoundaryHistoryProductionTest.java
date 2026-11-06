package deal.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Exhaustive bounded histories, not random sampling or a second interpreter. */
public final class BoundaryHistoryProductionTest {
    private enum Action { CALLBACK, MUTATE, REJECT, REPAIR, RETRY }
    private static final List<Action> LIFECYCLE = List.of(
        Action.CALLBACK, Action.MUTATE, Action.REJECT, Action.REPAIR, Action.RETRY);

    private static void enumerate(List<List<Action>> traces, List<Action> prefix,
                                  boolean valid, int remaining) {
        if (remaining == 0) {
            traces.add(List.copyOf(prefix));
            return;
        }
        for (Action action : valid
                ? List.of(Action.CALLBACK, Action.MUTATE, Action.REJECT, Action.RETRY)
                : List.of(Action.REPAIR)) {
            prefix.add(action);
            enumerate(traces, prefix, action != Action.REJECT, remaining - 1);
            prefix.removeLast();
        }
    }

    private static String trace(List<Action> continuation, int id) {
        StringBuilder body = new StringBuilder("""
            let a:number[]=[0,99];let alias=a;
            let destination:number[]=[42];let untouched=destination;
            let calls:number=0;host.keep(a);
            let get:()=>number[]=function():number[]{calls=calls+1;return a;};
            let effects:string="";
            let receiver:()=>number[]=function():number[]{effects=effects+"R";return destination;};
            let index:()=>number=function():number{effects=effects+"I";return 9;};
            let rhs:()=>number=function():number{effects=effects+"V";return 7;};
            """);
        List<Action> actions = new ArrayList<>(LIFECYCLE);
        actions.addAll(continuation);
        int value = 0;
        boolean valid = true;
        for (Action action : actions) {
            body.append('{'); // repeated action locals have independent scopes
            switch (action) {
                case CALLBACK -> {
                    value += 2;
                    body.append("check(host.callback(function(x:number):number{a[0]=a[0]+x;return a[0];},2)===")
                        .append(value).append(");");
                }
                case MUTATE -> { value = 7; body.append("host.mutate(7);"); }
                case REPAIR -> { value = -3; valid = true; body.append("host.mutate(-3);"); }
                case REJECT -> {
                    valid = false;
                    body.append("""
                        host.corrupt();let before=calls;let rejected=false;
                        try{destination=get();}catch(e){check((e as Error).code==="E8003");rejected=true;}
                        check(rejected && calls===before+1 && destination===untouched);
                        effects="";rejected=false;
                        try{receiver()[index()]=rhs();}catch(e){check((e as Error).code==="E8002");rejected=true;}
                        check(rejected && effects==="RIV");
                        """);
                }
                case RETRY -> body.append("let before=calls;check(get()===alias && calls===before+1);");
            }
            body.append("check(destination===untouched && destination[0]===42 && destination.length===1);");
            if (valid) {
                body.append("check(a===alias && a[0]===").append(value)
                    .append(" && a[1]===99 && a.length===2);check((host.echo(a) as number[])===a);");
            }
            body.append('}');
        }
        return "function trace" + id + "():void{" + body + "}\n";
    }

    public static void main(String[] args) throws Exception {
        List<List<Action>> traces = new ArrayList<>();
        enumerate(traces, new ArrayList<>(), true, 3);
        if (traces.size() != 43) throw new AssertionError("history enumeration changed: " + traces.size());
        Path root = Files.createTempDirectory("deal-boundary-history-");
        try {
            Path host = Files.createDirectories(root.resolve("host"));
            Path fixture = Path.of("test/conformance/host-fixtures/boundaries/boundary_stateful");
            for (String ext : List.of("d.deal", "java", "js", "lua", "c"))
                Files.copy(Path.of(fixture + "." + ext), host.resolve("probe." + ext));
            // Bound artifact size and subprocess work, not history membership.
            for (int batch = 0; batch < traces.size(); batch += 4) {
                int end = Math.min(batch + 4, traces.size());
                StringBuilder source = new StringBuilder("""
                    import * as c from "std/console";import * as host from "host/probe";
                    function check(ok:boolean):void{if(!ok){throw {code:"HISTORY",message:"mismatch"};}}
                    """);
                for (int i = batch; i < end; i++) source.append(trace(traces.get(i), i));
                source.append("export function main():null{");
                for (int i = batch; i < end; i++) source.append("trace").append(i).append("();");
                source.append("c.log(\"HISTORIES_OK\");return null;}");
                CompositionProductionTest.execute(source.toString(), "HISTORIES_OK\n",
                    root.resolve("batch" + batch), host,
                    List.of(deal.codegen.Backend.JS, deal.codegen.Backend.LUAJIT,
                        deal.codegen.Backend.JVM, deal.codegen.Backend.LLVM));
            }
            System.out.println("Boundary histories: 43 continuations x 4 executed backends passed (LLVM O0/O2 normal/stress/sanitized)");
        } finally {
            CompositionProductionTest.remove(root);
        }
    }
}
