package deal.test;

import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.UnicodeScalars;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

/** Independent edge-case pins for shared mechanical lookups, not oracle parity. */
public final class SharedLookupTest {
    public static void main(String[] args) {
        var fields = ClassLayout.BUILTIN_ERROR.fields();
        var layout = new ClassLayout(ClassLayout.BUILTIN_ERROR.classId(),
            List.of(fields.get(1), fields.get(0), fields.get(1)));
        if (layout.fieldOf("message") != fields.get(1) || layout.fieldOf("missing") != null)
            throw new AssertionError("field lookup changed order/absence");
        int[] text = {97, 0x1F600, 97, 0x1F600};
        if (!UnicodeScalars.matchAt(text, new int[]{97, 0x1F600}, 2)
                || UnicodeScalars.matchAt(text, new int[]{98}, 0)
                || UnicodeScalars.indexOfSubsequence(text, new int[]{0x1F600}, 2) != 3
                || UnicodeScalars.indexOfSubsequence(text, new int[]{97}, -4) != 0
                || UnicodeScalars.indexOfSubsequence(text, new int[]{98}, 0) != -1
                || UnicodeScalars.indexOfSubsequence(text, new int[]{}, 4) != 4
                || UnicodeScalars.indexOfSubsequence(text, new int[]{}, 5) != -1
                || UnicodeScalars.indexOfSubsequence(text, new int[]{}, -1) != -1)
            throw new AssertionError("scalar search changed edge positions");
        String cwd = Path.of("").toAbsolutePath().normalize().toString();
        String one = Path.of("one").toAbsolutePath().normalize().toString();
        String input = File.pathSeparator + "one" + File.pathSeparator;
        if (!TestClasspath.absolute(input, TestClasspath.EmptyEntry.OMIT).equals(one)
                || !TestClasspath.absolute(input, TestClasspath.EmptyEntry.CURRENT_DIRECTORY)
                    .equals(cwd + File.pathSeparator + one)
                || !TestClasspath.absolute("", TestClasspath.EmptyEntry.OMIT).isEmpty()
                || !TestClasspath.absolute("", TestClasspath.EmptyEntry.CURRENT_DIRECTORY).equals(cwd))
            throw new AssertionError("empty classpath entry policy changed");
        System.out.println("Shared lookup edge contracts passed");
    }
}
