package deal.compiler;

import java.util.ArrayList;
import java.util.List;

/** Structured source emission with batch-local provenance, never embedded in source text. */
public final class ConstructionProjection {
    private ConstructionProjection() {}
    public sealed interface Fragment permits Text, Concat, Origin {}
    public record Text(String value) implements Fragment {}
    public record Concat(List<Fragment> parts) implements Fragment {
        public Concat { parts = List.copyOf(parts); }
    }
    public record Origin(String handle, DealConstruction.Kind kind, Fragment child) implements Fragment {}
    /** Offsets are UTF-16, matching String and compiler edit offsets; end is exclusive. */
    public record Occurrence(String handle, DealConstruction.Kind kind, int start, int end) {}
    public record Result(String source, List<Occurrence> origins) {
        public Result { origins = List.copyOf(origins); }
        public List<Occurrence> ownersAt(int offset) {
            if (offset < 0 || offset >= source.length()) return List.of();
            return origins.stream().filter(o -> o.start() <= offset && offset < o.end())
                    .sorted(java.util.Comparator.comparingInt(o -> o.end() - o.start())).toList();
        }
    }
    public static Fragment concat(Object... parts) {
        var fragments = new ArrayList<Fragment>();
        for (Object part : parts) {
            if (part instanceof Fragment fragment) fragments.add(fragment);
            else if (part instanceof String text) fragments.add(new Text(text));
            else throw new IllegalArgumentException("Source fragment or text required");
        }
        return new Concat(fragments);
    }
    public static Fragment join(String separator, List<Fragment> parts) {
        var result = new ArrayList<Fragment>();
        for (var part : parts) {
            if (!result.isEmpty()) result.add(new Text(separator));
            result.add(part);
        }
        return new Concat(result);
    }
    public static Result render(Fragment fragment) {
        var text = new StringBuilder();
        var origins = new ArrayList<Occurrence>();
        append(fragment, text, origins);
        return new Result(text.toString(), origins);
    }
    /** Formats an inserted block while preserving constructor ranges in the emitted text. */
    public static Result indentBlock(Result input, String indentation, String closingIndentation) {
        String source = input.source();
        if (source.isBlank()) return new Result("", List.of());
        int[] offsets = new int[source.length() + 1];
        var output = new StringBuilder("\n");
        int cursor = 0;
        while (cursor < source.length()) {
            output.append(indentation);
            int end = cursor;
            while (end < source.length() && source.charAt(end) != '\n' && source.charAt(end) != '\r') end++;
            int content = cursor;
            while (content < end && Character.isWhitespace(source.codePointAt(content))) {
                content += Character.charCount(source.codePointAt(content));
            }
            while (cursor < content) offsets[cursor++] = output.length();
            while (cursor < end) {
                offsets[cursor] = output.length();
                output.append(source.charAt(cursor++));
            }
            offsets[cursor] = output.length();
            if (cursor < source.length()) {
                char newline = source.charAt(cursor++);
                if (newline == '\r' && cursor < source.length() && source.charAt(cursor) == '\n') {
                    offsets[cursor++] = output.length();
                }
            }
            output.append('\n');
        }
        offsets[source.length()] = output.length() - 1;
        output.append(closingIndentation);
        var origins = input.origins().stream().map(origin -> new Occurrence(
                origin.handle(), origin.kind(), offsets[origin.start()], offsets[origin.end()])).toList();
        return new Result(output.toString(), origins);
    }
    private static void append(Fragment fragment, StringBuilder text, List<Occurrence> origins) {
        switch (fragment) {
            case Text literal -> text.append(literal.value());
            case Concat sequence -> sequence.parts().forEach(part -> append(part, text, origins));
            case Origin origin -> {
                int start = text.length();
                append(origin.child(), text, origins);
                origins.add(new Occurrence(origin.handle(), origin.kind(), start, text.length()));
            }
        }
    }
}
