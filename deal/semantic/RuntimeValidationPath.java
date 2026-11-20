package deal.semantic;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Current validation frames only: no value reads, descriptor checks or inferred field names. */
public record RuntimeValidationPath(RuntimeValidationPath parent, String segment, int depth) {
    public RuntimeValidationPath {
        Objects.requireNonNull(segment, "segment");
        if (depth != (parent == null ? 1 : parent.depth() + 1)) throw new IllegalArgumentException("Invalid validation path depth");
    }
    public static RuntimeValidationPath child(RuntimeValidationPath parent, String segment) {
        return new RuntimeValidationPath(parent, segment, parent == null ? 1 : parent.depth() + 1);
    }
    public static Map<String, String> metadata(RuntimeValidationPath path, boolean missing) {
        int depth = path == null ? 0 : path.depth();
        RuntimeValidationPath[] prefix = new RuntimeValidationPath[Math.min(depth, 32)];
        for (var frame = path; frame != null; frame = frame.parent())
            if (frame.depth() <= 32) prefix[frame.depth() - 1] = frame;
        StringBuilder pointer = new StringBuilder();
        boolean truncated = depth > 32;
        for (var frame : prefix) {
            String key = frame.segment();
            int take = Math.min(key.length(), 128);
            if (take < key.length() && take > 0 && Character.isHighSurrogate(key.charAt(take - 1))
                    && Character.isLowSurrogate(key.charAt(take))) take--;
            truncated |= take < key.length();
            pointer.append('/');
            for (int i = 0; i < take; i++) {
                char c = key.charAt(i);
                if (c == '~') pointer.append("~0");
                else if (c == '/') pointer.append("~1");
                else pointer.append(c);
            }
        }
        var facts = new LinkedHashMap<String, String>();
        facts.put("validationPath", pointer.toString());
        facts.put("validationPathDepth", Integer.toString(depth));
        facts.put("validationPathTruncated", Boolean.toString(truncated));
        facts.put("validationMissingField", Boolean.toString(missing));
        return Map.copyOf(facts);
    }
}
