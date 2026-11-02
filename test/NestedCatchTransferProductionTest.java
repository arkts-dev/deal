package deal.test;

import java.nio.file.Files;
import java.nio.file.Path;

/** Nested scalar/array loops, escaped catch/cell identity and nearest-loop transfers. */
public final class NestedCatchTransferProductionTest {
    private enum Transfer { NORMAL, CONTINUE, BREAK }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("deal-nested-catch-transfer-");
        try {
            StringBuilder source = new StringBuilder("import * as c from \"std/console\";\n");
            StringBuilder calls = new StringBuilder();
            int cases = 0;
            for (int shape = 0; shape < 8; shape++) {
                for (Transfer transfer : Transfer.values()) {
                    for (boolean closure : new boolean[]{false, true}) {
                        String name = "case" + cases++;
                        String[] iterables = new String[3];
                        for (int level = 0; level < 3; level++) {
                            iterables[level] = ((shape >> level) & 1) == 0
                                ? "[\"a\",\"😀\"]" : "\"a😀\"";
                        }
                        StringBuilder expected = new StringBuilder();
                        // Explicit Cartesian product independent of emitted loop
                        // representation. BREAK affects only the innermost loop;
                        // CONTINUE still visits its second scalar/array element.
                        for (String x : new String[]{"a", "😀"}) {
                            for (String y : new String[]{"a", "😀"}) {
                                for (String z : new String[]{"a", "😀"}) {
                                    String mark = x + y + z;
                                    expected.append(mark).append("?").append(mark).append("!");
                                    if (transfer == Transfer.BREAK) break;
                                }
                            }
                        }
                        String body = "let readers:(()=>string)[]=[];let tails:string=\"\";"
                            + "for(let x of " + iterables[0] + "){for(let y of " + iterables[1]
                            + "){for(let z of " + iterables[2] + "){"
                            + "let original:Error={code:\"C\",message:x+y+z};"
                            + "try{throw original;}catch(e){"
                            + "if((e as Error)!==original){throw {code:\"IDENTITY\",message:\"" + name + "\"};}"
                            + "readers[readers.length]=function():string{"
                            + "if((e as Error)!==original){throw {code:\"ESCAPED_IDENTITY\",message:\"" + name + "\"};}"
                            + "return (e as Error).message+x+y+z;};"
                            + "original.message=original.message+\"?\";z=z+\"!\";"
                            + switch (transfer) {
                                case NORMAL -> "";
                                case CONTINUE -> "continue;";
                                case BREAK -> "break;";
                            }
                            + "}tails=tails+\"t\";}}}"
                            + "let result:string=\"\";for(let f of readers){result=result+f();}"
                            + "if(tails!==\"" + (transfer == Transfer.NORMAL ? "t".repeat(8) : "")
                            + "\"){throw {code:\"TAIL\",message:\"" + name + "\"};}return result;";
                        source.append("function ").append(name).append("():string{")
                            .append(closure ? "let run:()=>string=function():string{" + body + "};return run();" : body)
                            .append("}\n");
                        calls.append("if(").append(name).append("()!==\"").append(expected)
                            .append("\"){throw {code:\"VALUE\",message:\"").append(name).append("\"};}\n");
                    }
                }
            }
            if (cases != 48) throw new AssertionError("combination enumeration changed: " + cases);
            source.append("export function main():null{").append(calls)
                .append("c.log(\"COMBINATIONS_OK\");return null;}\n");
            CompositionProductionTest.execute(source.toString(), "COMBINATIONS_OK\n", root.resolve("run"), null);
            System.out.println("Nested catch/transfer: 48 combinations x 3 executed backends passed");
        } finally {
            CompositionProductionTest.remove(root);
        }
    }
}
