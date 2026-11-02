package deal.test;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Classpaths passed to children with a different working directory. */
public final class TestClasspath {
    public enum EmptyEntry { OMIT, CURRENT_DIRECTORY }

    private TestClasspath() {}

    public static String absolute(EmptyEntry emptyEntry) {
        return absolute(System.getProperty("java.class.path", ""), emptyEntry);
    }

    static String absolute(String classpath, EmptyEntry emptyEntry) {
        List<String> resolved = new ArrayList<>();
        // Match String.split's existing treatment of trailing empty entries.
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isEmpty() && emptyEntry == EmptyEntry.OMIT) continue;
            resolved.add(Path.of(entry).toAbsolutePath().normalize().toString());
        }
        return String.join(File.pathSeparator, resolved);
    }
}
