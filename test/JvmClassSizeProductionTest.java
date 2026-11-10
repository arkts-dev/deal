package deal.test;

import deal.codegen.Backend;
import deal.codegen.jvm.JvmAsyncExportInvoker;
import deal.codegen.lua.AsyncExportInvocationRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Many small methods must not overflow the aggregate class constant pool. */
public final class JvmClassSizeProductionTest {
    private static String source(int functions, int width) {
        StringBuilder source = new StringBuilder("import * as c from \"std/console\";\n");
        for (int f = 0; f < functions; f++) {
            source.append("class Item").append(f).append("{value:number;next?:Item").append(f).append(";}\n");
            source.append("function f").append(f).append("(depth:number):number{\n")
                .append("let decoded:Item").append(f).append("=JSON.parse(`{\"value\":")
                .append(f).append("}`);if(decoded.value!==").append(f)
                .append(" || Object.hasOwn(decoded,\"next\")){throw {code:\"PLAN\",message:\"partitioned schema\"};}\n");
            for (int i = 0; i < width; i++) {
                source.append("let x").append(i).append(":number=").append(i).append("+depth;\n");
            }
            source.append("let captured:()=>number=function():number{return x0;};")
                .append("if(depth>0){let child=f").append(f).append("(depth-1);")
                .append("if(child!==").append(width * (width - 1) / 2).append("+")
                .append(width).append("*(depth-1)){throw {code:\"RECURSE\",message:\"state\"};}}")
                .append("return captured()");
            for (int i = 1; i < width; i++) source.append("+x").append(i);
            source.append(";}\n");
        }
        // Async dispatch and recursive snapshot restoration must also resolve
        // factories in their new owning classes, including escaped catch cells.
        source.append("""
            async function recurse(depth:number):Promise<number>{
              let read:()=>number=function():number{return -100;};
              let original:Error={code:"C",message:"before"};
              try{throw original;}catch(e){
                read=function():number{if((e as Error)===original){return depth;}return -100;};
              }
              if(depth>0){
                let child:number=await recurse(depth-1);
                if(child!==depth-1){throw {code:"ASYNC",message:"recursive state"};}
              }
              return read();
            }
            export async function drive():Promise<number>{return await recurse(3);}
            export function main():null{let sum:number=0;
            """);
        for (int f = 0; f < functions; f++) source.append("sum=sum+f").append(f).append("(1);\n");
        source.append("if(sum!==").append(functions * (width * (width - 1) / 2 + width))
            .append("){throw {code:\"SUM\",message:\"result\"};}c.log(\"SIZE_OK\");return null;}");
        return source.toString();
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("deal-jvm-class-size-");
        try {
            // A cold small control and the same computation above the former
            // class-wide limit. Each individual function remains small.
            for (int functions : List.of(2, 100)) {
                Path bundle = root.resolve("functions" + functions);
                CompositionProductionTest.execute(source(functions, 32), "SIZE_OK\n",
                    bundle, null, List.of(Backend.JVM));
                var result = new JvmAsyncExportInvoker().invoke(new AsyncExportInvocationRequest(
                    bundle.resolve("JVM/out/classes/Main.class"), "drive", "number"));
                if (!(result instanceof JvmAsyncExportInvoker.Result.Value value)
                        || Double.parseDouble(value.valueJson()) != 3)
                    throw new AssertionError("aggregate-size async completion: " + result);
            }
            System.out.println("JVM class size: small/large sync, contextual schemas and recursive async executions passed");
        } finally {
            CompositionProductionTest.remove(root);
        }
    }
}
