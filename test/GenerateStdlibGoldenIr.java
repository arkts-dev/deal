package deal.test;

import deal.ast.*;
import deal.checker.SymbolTable;
import deal.ir.IrDumper;
import deal.lexer.*;
import deal.module.StdlibModuleResolver;
import deal.parser.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates the golden IR dump file for stdlib declarations.
 *
 * <p>Usage: {@code java -cp build deal.test.GenerateStdlibGoldenIr [output-path]}
 *
 * <p>If no output path is given, the IR text is printed to stdout.
 * If an output path is given, the file is written there (and parent
 * directories are created).
 */
public class GenerateStdlibGoldenIr {

    public static void main(String[] args) throws Exception {
        String irText = generate();

        if (args.length > 0) {
            Path outputPath = Path.of(args[0]);
            Files.createDirectories(outputPath.getParent());
            Files.writeString(outputPath, irText);
            System.err.println("Golden IR written to: " + outputPath);
        } else {
            System.out.print(irText);
        }
    }

    public static String generate() throws Exception {
        StringBuilder sb = new StringBuilder();

        for (String modulePath : StdlibModuleResolver.SPEC_STDLIB_MODULES) {
            String declFile = modulePath + ".d.deal";
            Path file = Path.of(declFile);

            if (!Files.exists(file)) {
                System.err.println("File not found: " + declFile);
                continue;
            }

            String source = Files.readString(file);
            String filename = file.toString();

            LexResult lex = new Lexer(source, filename).tokenize();
            if (lex.hasErrors()) {
                System.err.println("Lex errors in " + filename);
                continue;
            }

            Parser parser = new Parser(lex.tokens(), filename);
            ParseResult parseResult = parser.parse();
            if (parseResult.hasErrors()) {
                System.err.println("Parse errors in " + filename);
                continue;
            }

            ProgramNode program = parseResult.program();
            SymbolTable symbolTable = new SymbolTable();

            String irText = IrDumper.dump(program, symbolTable, modulePath);
            sb.append(irText);
        }

        return sb.toString();
    }
}
