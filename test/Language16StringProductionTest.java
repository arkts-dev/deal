package deal.test;

import deal.codegen.Backend;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Independent v1.6 text and recursive-cell assertions on deployed products. */
public final class Language16StringProductionTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("deal-language16-strings-");
        try {
            CompositionProductionTest.execute(SOURCE, "A�😀�B\nOK\n", "E�😀�F\n", root, null,
                List.of(Backend.JS, Backend.LUAJIT, Backend.JVM, Backend.LLVM));
            nativeHostTransport(root.resolve("host-transport"));
            nativeOpaqueRepair(root.resolve("opaque-repair"));
            nativeNondataRead(root.resolve("nondata-read"));
            Path corpus = Path.of("test/conformance").toAbsolutePath().normalize();
            var fixtures = CorpusConformanceTest.nativeCorpusFixtures(corpus);
            for (String host : List.of("language16", "audit16")) {
                var fixture = fixtures.stream()
                    .filter(f -> f.corpusPath().equals("backend-runtime/host-abi/host-" + host + ".deal"))
                    .findFirst().orElseThrow();
                LlvmConformanceTest.runInstrumented(corpus, fixture);
            }
            System.out.println("v1.6 text: JS/LuaJIT/JVM and LLVM O0/O2 normal/stress passed; native host transport normal/stress/sanitized passed");
        } finally {
            CompositionProductionTest.remove(root);
        }
    }

    private static void nativeNondataRead(Path root) throws Exception {
        Path host = Files.createDirectories(root.resolve("host"));
        Files.writeString(host.resolve("probe.d.deal"), """
            export function corrupt(v:unknown[], opaque:boolean):void;
            export function repair():void;
            """);
        Files.writeString(host.resolve("probe.c"), """
            #include "host/probe.h"
            #include "native_hostile.h"
            static deal_handle saved;
            static unsigned raw_calls;
            static const unsigned char identity = 0u;
            static deal_host_result raw(const deal_value *args, size_t argc) {
                raw_calls += 1u;
                return fixture_seven(args, argc);
            }
            static deal_host_result corrupt(const deal_value *args, size_t argc) {
                (void)argc;
                int opaque;
                checked(deal_as_boolean(args[1], &opaque));
                if (saved != 0u) checked(deal_release(saved));
                checked(deal_retain(args[0], &saved));
                deal_value bad;
                if (opaque) checked(deal_opaque(&identity, &bad));
                else checked(deal_function_raw(raw, &bad));
                if (deal_array_set(args[0], 0, bad) != DEAL_STATUS_KIND) abort();
                deal_check_input input;
                deal_error *cause = NULL;
                checked(deal_host_value_presentation(args[0], &input, &cause));
                if (cause != NULL || input.datum.tag != DEAL_DATA_ARRAY) abort();
                deal_data datum = fixture_box_of(bad)->managed;
                deal_container_array_store(input.datum.reference, 0u, &datum);
                deal_value out; checked(deal_undefined(&out)); return result(out);
            }
            static deal_host_result repair(const deal_value *args, size_t argc) {
                (void)args; (void)argc;
                if (raw_calls != 0u) abort();
                deal_value array;
                checked(deal_handle_value(saved, NULL, &array));
                checked(deal_array_set(array, 0, number(7)));
                checked(deal_release(saved)); saved = 0u;
                deal_value out; checked(deal_undefined(&out)); return result(out);
            }
            static const deal_host_export exports[] = {
                {"corrupt", "([unknown],boolean)->void", DEAL_HOST_EXPORT_FUNCTION, 0, corrupt, NULL, NULL},
                {"repair", "()->void", DEAL_HOST_EXPORT_FUNCTION, 0, repair, NULL, NULL}
            };
            const deal_host_module deal_host_module_probe = {
                DEAL_HOST_SPECIFIER_PROBE, exports, 2u, NULL, 0u, NULL
            };
            """);
        CompositionProductionTest.execute("""
            import * as host from "host/probe";
            import * as console from "std/console";
            function check(ok:boolean):void {
              if(!ok){throw {code:"TEST",message:"private nondata read"};}
            }
            export function main():null {
              for(let opaque of [false,true]) {
                let graph:unknown[]=[1,9];let alias=graph;
                host.corrupt(graph,opaque);
                let old:unknown=17;let destination=old;let rejected=false;
                try { destination=graph[0]; }
                catch(e) {
                  check((e as Error).code==="E8001");
                  check((e as Error).message==="expected unknown");
                  rejected=true;
                }
                check(rejected && (destination as number)===17);
                host.repair();
                check(graph===alias && (graph[0] as number)===7 && (graph[1] as number)===9);
              }
              console.log("NONDATA_READ_OK");return null;
            }
            """, "NONDATA_READ_OK\n", root.resolve("products"), host, List.of(Backend.LLVM));
    }

    private static void nativeOpaqueRepair(Path root) throws Exception {
        Path host = Files.createDirectories(root.resolve("host"));
        Path fixture = Path.of("test/conformance/host-fixtures/boundaries/boundary_graph_opaque");
        Files.copy(Path.of(fixture + ".d.deal"), host.resolve("probe.d.deal"));
        Files.copy(Path.of(fixture + ".c"), host.resolve("probe.c"));
        CompositionProductionTest.execute("""
            import * as host from "host/probe";
            import * as console from "std/console";
            function check(ok:boolean):void {
              if(!ok){throw {code:"TEST",message:"retained opaque graph"};}
            }
            export function main():null {
              let r:Record<string,unknown>={good:7};r.self=r;
              let old:Record<string,unknown>={marker:9};let destination=old;
              host.corrupt(r);
              let rejected=false;
              try { destination=r; }
              catch(e) {
                check((e as Error).code==="E8001");
                check((e as Error).message==="expected unknown");
                rejected=true;
              }
              check(rejected && destination===old && (old.marker as number)===9);
              host.repair();
              destination=r;
              check(destination===r && (r.self as Record<string,unknown>)===r);
              check((r.good as number)===7 && !Object.hasOwn(r,"bad"));
              console.log("OPAQUE_REPAIR_OK");return null;
            }
            """, "OPAQUE_REPAIR_OK\n", root.resolve("products"), host, List.of(Backend.LLVM));
    }

    private static void nativeHostTransport(Path root) throws Exception {
        Path host = Files.createDirectories(root.resolve("host"));
        Path fixtures = Path.of("test/conformance/host-fixtures");
        Files.copy(fixtures.resolve("bad_string.d.deal"), host.resolve("probe.d.deal"));
        Files.writeString(host.resolve("probe.c"), Files.readString(fixtures.resolve("bad_string.c"))
            .replace("host/bad_string.h", "host/probe.h")
            .replace("deal_host_module_bad_string", "deal_host_module_probe")
            .replace("DEAL_HOST_SPECIFIER_BAD_STRING", "DEAL_HOST_SPECIFIER_PROBE"));
        CompositionProductionTest.execute("""
            import * as host from "host/probe";
            import * as console from "std/console";
            function check(ok:boolean):void {
              if(!ok){throw {code:"TEST",message:"native text transport"};}
            }
            export function main():null {
              check(host.surrogateString()===`\\ud800`);
              check(host.embeddedSurrogateString()===`a\\ud800b`);
              let scalarRejected=false;
              try { host.badString(); }
              catch(e) {
                check((e as Error).code==="E8010");
                check((e as Error).message==="return value 1 type mismatch: expected string, got invalid UTF-8 encoding");
                scalarRejected=true;
              }
              check(scalarRejected);
              let rejected=false;
              let original:string[]=["unchanged"];
              let destination=original;
              try { destination=host.nativeBadStringArray(); }
              catch(e) {
                check((e as Error).code==="E8010");
                check((e as Error).message==="return value 1 type mismatch: array element 2 type mismatch");
                rejected=true;
              }
              check(rejected && destination===original && destination[0]==="unchanged");
              let shared=host.nativeSharedNestedStringArray();
              check(shared[0]===shared[1]);
              shared[0][0]="changed";
              check(shared[1][0]==="changed");
              console.log("HOST_TEXT_OK");return null;
            }
            """, "HOST_TEXT_OK\n", root.resolve("products"), host, List.of(Backend.LLVM));
    }

    private static final String SOURCE = """
        import * as c from "std/console";
        import * as s from "std/string";
        import * as json from "std/json";
        function require(ok:boolean, label:string):void {
          if (!ok) { throw {code:"TEST",message:label}; }
        }
        function fresh(seed:number):()=>number {
          let recur=function():number { seed=seed+1;return seed; };
          let saved=recur;
          recur=function():number{return saved()+10;};
          return recur;
        }
        function recursive(seed:number):(n:number)=>number {
          let recur=(function(n:number):number {
            if(n===0){return seed;}return recur(n-1)+1;
          });
          return recur;
        }
        export function main():null {
          let first=fresh(0);let second=fresh(20);
          require(first()===11 && second()===31 && first()===12,"fresh initializer cells");
          let escaped=recursive(3);let other=recursive(7);
          require(escaped(2)===5 && other(1)===8 && escaped(1)===4,"escaped self cells");
          let remaining=2;
          let adapted:(n:number)=>number=function():number {
            if(remaining===0){return 6;}remaining=remaining-1;return adapted(1)+1;
          };
          require(adapted(9)===8,"adapted recursive initializer");
          let f:(n:number)=>number=function(n:number):number {
            if(n===0){return 1;} return n*f(n-1);
          };
          require(f(5)===120,"self binding");
          let retained=f;
          f=function(n:number):number{return 7;};
          require(retained(2)===14,"mutable recursive binding");
          require(`a\\nb`==="a\\nb","template newline");
          require(`\\x41\\u0042\\u{1f600}`==="AB😀","template escapes");
          require(s.length("a😀b")===4,"UTF-16 length");
          require(s.substring("a😀b",3,1)==="😀","substring swap");
          require(s.substring("abcd",1.9,3.8)==="bc","substring truncation");
          require(s.substring("abcd",0/0,1/0)==="abcd","substring nonfinite");
          require(s.substring("abcd",-1/0,2)==="ab","substring negative infinity");
          let high=s.substring("😀",0,1);
          let low=s.substring("😀",1,2);
          require(s.length(high)===1 && s.length(low)===1,"lone unit lengths");
          require(high+low==="😀","unit concatenation");
          require(`\\ud83d`===high && `\\ude00`===low,"literal lone units");
          require(`\\ud800`!==high,"distinct lone literal units");
          require("😀"<`\\ue000`,"UTF-16 ordering");
          require(s.contains("😀",high) && s.endsWith("😀",low),"unit search");
          require(s.startsWith("😀",high),"unit prefix");
          require(s.replace("aaa","a","X")==="Xaa","first replacement");
          require(s.replace("ab","","X")==="Xab","empty search");
          require(s.replace("abc","b","$$|$&|$`|$'")==="a$|b|a|cc","substitutions");
          let units=s.split("😀","");
          require(units.length===2 && units[0]===high && units[1]===low,"unit split");
          require(s.split("","").length===0 && s.split("",",").length===1,"empty split");
          require(s.trim(`\\u00a0\\u1680\\u2000\\u2028\\u202f\\u205f\\u3000\\ufeffok\\ufeff`)==="ok","trim set");
          require(s.trim(`\\u0085ok\\u0085` )===`\\u0085ok\\u0085`,"non-whitespace");
          let count=0;
          for(let part of high+low){count=count+1;require(part==="😀","paired iteration");}
          require(count===1,"paired iteration count");
          count=0;
          for(let part of low+high){count=count+1;require(s.length(part)===1,"lone iteration");}
          require(count===2,"lone iteration count");
          require((json.parse(`{"value":"\\\\ud83d"}`).value as string)===high,"parse lone high");
          require((json.parse(`{"value":"\\\\ude00"}`).value as string)===low,"parse lone low");
          require((json.stringify(high) as string)===`"\\\\ud83d"`,"encode lone high");
          require((json.stringify(low) as string)===`"\\\\ude00"`,"encode lone low");
          require((json.parse(json.stringify({value:"😀"}) as string).value as string)==="😀","JSON pair");
          let record:Record<string,unknown>={};
          record[high]=low;
          let decoded=json.parse(json.stringify(record) as string);
          require((decoded[high] as string)===low,"JSON lone key");
          c.log(`A\\ud800😀\\udc00B`);
          c.error(`E\\ud800😀\\udc00F`);
          c.log("OK");return null;
        }
        """;
}
