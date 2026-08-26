package deal.module;

import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.source.JsonRangeLexer;
import deal.source.JsonRangeLexer.JsonLexFault;
import deal.source.JsonRangeLexer.JsonMemberRange;
import deal.source.JsonRangeLexer.JsonRangeLexResult;
import deal.source.JsonRangeLexer.JsonRangeToken;
import deal.source.JsonRangeLexer.JsonTokenKind;
import deal.source.SourceScalarRange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses and holds the contents of a {@code deal.json} project manifest.
 *
 * <p>{@code moduleRoots}, {@code output}, and {@code backend} drive the
 * CLI; {@code externals} (spec map form, import path → manifest-relative
 * declaration) drives host-module resolution and E2009 gating
 * (ISSUE-0082). The remaining fields are parsed but not enforced.</p>
 *
 * <p>Parsing is the tolerant manifest reader (non-lossy-diagnostic-ranges
 * D10): {@link #parse(Path, String)} and {@link #load(Path)} walk the
 * {@link JsonRangeLexer} result and never throw for JSON content. Invalid
 * content yields a {@link DealConfigParseResult} with a null config and
 * exactly one ranged E2012 diagnostic (fail-fast: structural faults stop
 * the walk in scan order, then the post-walk validations run in today's
 * pinned precedence — {@code languageVersion} → {@code externals} →
 * {@code backend}). An absent manifest is not invalid: {@link #load(Path)}
 * returns a null config with empty diagnostics and the CLI proceeds on
 * today's defaults. Today's acceptance semantics are preserved exactly:
 * every tolerated construct (missing-comma truncation, the
 * stop-every-enclosing-container cascade after a completed member value,
 * unclosed constructs, unknown escapes, unpaired surrogates, trailing
 * content, duplicate keys, wrong-typed optional fields, permissive
 * numbers, non-strict whitespace, raw string controls) still parses
 * without a diagnostic.</p>
 */
public final class DealConfig {

    private final List<String> moduleRoots;
    private final String output;
    private final String backend;
    private final String languageVersion;
    private final List<String> permissions;
    private final Limits limits;
    private final Map<String, String> externals;
    private final Dependencies dependencies;
    private final Path configFile;

    private DealConfig(Path configFile, List<String> moduleRoots, String output,
                       String backend, String languageVersion,
                       List<String> permissions, Limits limits,
                       Map<String, String> externals, Dependencies dependencies) {
        this.configFile = configFile;
        this.moduleRoots = moduleRoots;
        this.output = output;
        this.backend = backend;
        this.languageVersion = languageVersion;
        this.permissions = permissions;
        this.limits = limits;
        this.externals = externals;
        this.dependencies = dependencies;
    }

    /**
     * The sole manifest result channel (D10): a parsed config plus the
     * ranged E2012 diagnostics. For invalid content the config is null and
     * {@code diagnostics} holds exactly one E2012; for an absent manifest
     * both are null/empty ({@link #load(Path)}); for valid content the
     * config is non-null and {@code diagnostics} is empty.
     */
    public record DealConfigParseResult(DealConfig config,
                                        List<CompilerDiagnostic> diagnostics) {
        public DealConfigParseResult {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    /**
     * Attempts to load deal.json from the given directory.
     *
     * <p>An absent manifest (today's null return) is the default-config
     * success path: {@code (config = null, diagnostics = empty)}, never an
     * E2012. Filesystem read failures still throw {@link IOException}
     * (explicit exclusion until ISSUE-0154); JSON content never throws.</p>
     */
    public static DealConfigParseResult load(Path projectDir) throws IOException {
        Path configFile = projectDir.resolve("deal.json");
        if (!Files.exists(configFile)) {
            return new DealConfigParseResult(null, List.of());
        }
        String raw = Files.readString(configFile);
        return parse(configFile, raw);
    }

    /**
     * Parses deal.json JSON content through the tolerant reader. Never
     * throws for JSON content — including the historical raw
     * {@code NumberFormatException} path of the hand-rolled number parser;
     * every error condition becomes exactly one ranged E2012 diagnostic.
     */
    public static DealConfigParseResult parse(Path configFile, String json) {
        JsonRangeLexResult lexed = JsonRangeLexer.lex(json);

        // Fail-fast structural walk (D10): the first positional
        // hard-failure fault in scan order is the only diagnostic.
        // Tolerated faults (missing-comma truncation, the after-value
        // cascade, unclosed constructs, escapes, surrogates, trailing
        // content, non-strict whitespace, raw string controls, and the
        // value-at-end-of-input signature) are skipped here.
        for (JsonLexFault fault : lexed.faults()) {
            DealConfigParseResult structural = structuralFault(configFile, fault);
            if (structural != null) {
                return structural;
            }
        }

        Object root = decodeRoot(lexed.orderedTokens());
        if (!(root instanceof Map<?, ?> map)) {
            // Non-object root (scalar/array/null): today's
            // IllegalArgumentException("deal.json: invalid JSON"). Empty
            // and whitespace-only input produce zero tokens, so the anchor
            // is the pinned document-start zero-length SOURCE shape.
            if (!lexed.orderedTokens().isEmpty()) {
                return invalidJson(configFile, lexed.orderedTokens().get(0).range());
            }
            return invalidJsonAtDocumentStart(configFile);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> obj = (Map<String, Object>) map;

        // Post-walk validations in today's pinned precedence (D10):
        // languageVersion -> externals -> backend, independent of member
        // document order. The first failure is the only diagnostic.
        DealConfigParseResult versionFailure = validateLanguageVersion(configFile,
            lexed.members(), getString(obj, "languageVersion"));
        if (versionFailure != null) {
            return versionFailure;
        }

        ExternalsParse externals = parseExternals(configFile, lexed.members(), obj);
        if (externals.failure() != null) {
            return externals.failure();
        }

        DealConfigParseResult backendFailure = validateBackend(configFile,
            lexed.members(), getString(obj, "backend"));
        if (backendFailure != null) {
            return backendFailure;
        }

        List<String> moduleRoots = getStringList(obj, "moduleRoots");
        String output = getString(obj, "output");
        String backend = getString(obj, "backend");
        List<String> permissions = getStringList(obj, "permissions");
        Limits limits = Limits.fromJson(getObject(obj, "limits"));
        Dependencies dependencies = Dependencies.fromJson(getObject(obj, "dependencies"));

        DealConfig config = new DealConfig(configFile, moduleRoots, output, backend,
            "1.2", permissions, limits, externals.map(), dependencies);
        return new DealConfigParseResult(config, List.of());
    }

    public List<String> moduleRoots() { return moduleRoots; }
    public String output() { return output; }
    public String backend() { return backend; }
    /**
     * The project language version.  Absent manifests assume the current
     * compiler version {@code "1.2"}.  DEAL v1.2 is not source-compatible
     * with earlier language versions and this compiler implements no
     * migration rules, so any other declared value is a configuration
     * error (spec-v1.2: Package manifest / Declaration metadata versioning).
     */
    public String languageVersion() { return languageVersion; }
    public List<String> permissions() { return permissions; }
    public Limits limits() { return limits; }
    /**
     * The spec map form of the {@code externals} manifest field: import path
     * as written → declaration file path (relative to the manifest
     * directory).  The legacy list form is retired: it yields an empty map.
     */
    public Map<String, String> externals() { return externals; }
    public Dependencies dependencies() { return dependencies; }
    public Path configFile() { return configFile; }

    // =========================================================================
    // Tolerant reader: fault mapping, decoding, and post-walk validations
    // =========================================================================

    /**
     * Maps one structural fault to the fail-fast E2012 per D10's table.
     * Returns null for every tolerated row.
     *
     * <p>Fault-kind classification is pinned to the scan contexts that
     * produce them: {@code EXPECTED_KEY}/{@code EXPECTED_COLON} occur only
     * at member start and between key and colon (today's {@code expect}
     * throws); {@code EXPECTED_VALUE} with input present or with an
     * undecodable number is today's raw {@code NumberFormatException},
     * while the end-of-input signature is the tolerated null value;
     * {@code UNEXPECTED_CHARACTER} carries its context in the message
     * prefix (member start, key–colon, value position — hard failures;
     * after-value and after-root — tolerated).</p>
     */
    private static DealConfigParseResult structuralFault(Path configFile, JsonLexFault fault) {
        String message = fault.message();
        switch (fault.kind()) {
            case EXPECTED_KEY:
                return failure(configFile, "deal.json: invalid JSON: expected string key at "
                    + fault.range().startLine() + ":" + fault.range().startColumn(),
                    fault.range());
            case EXPECTED_COLON:
                return failure(configFile, "deal.json: invalid JSON: expected ':' at "
                    + fault.range().startLine() + ":" + fault.range().startColumn(),
                    fault.range());
            case EXPECTED_VALUE:
                if (message.equals("expected value at end of input")) {
                    return null; // tolerated: null value at end of input
                }
                return failure(configFile, "deal.json: invalid JSON: expected value at "
                    + fault.range().startLine() + ":" + fault.range().startColumn(),
                    fault.range());
            case UNEXPECTED_CHARACTER:
                if (message.startsWith("expected string key")) {
                    return failure(configFile, "deal.json: invalid JSON: expected string key at "
                        + fault.range().startLine() + ":" + fault.range().startColumn(),
                        fault.range());
                }
                if (message.startsWith("expected ':'")) {
                    return failure(configFile, "deal.json: invalid JSON: expected ':' at "
                        + fault.range().startLine() + ":" + fault.range().startColumn(),
                        fault.range());
                }
                if (message.startsWith("expected value")) {
                    return failure(configFile, "deal.json: invalid JSON: expected value at "
                        + fault.range().startLine() + ":" + fault.range().startColumn(),
                        fault.range());
                }
                return null; // after-value truncation / after-root — tolerated
            default:
                // EXPECTED_COMMA_OR_END, EXPECTED_END, INVALID_ESCAPE,
                // UNPAIRED_SURROGATE, TRAILING_CONTENT,
                // NON_STRICT_WHITESPACE, RAW_CONTROL_IN_STRING — tolerated.
                return null;
        }
    }

    /**
     * Validates the {@code languageVersion} manifest field: only
     * {@code "1.2"} is supported by this compiler; anything else —
     * including older versions like {@code "1.1"} and newer major
     * versions — is a configuration error. Absent or wrong-typed values
     * default to {@code "1.2"} (tolerated, exactly as today). Anchors at
     * the {@code languageVersion} member value range.
     */
    private static DealConfigParseResult validateLanguageVersion(Path configFile,
            List<JsonMemberRange> members, String languageVersion) {
        if (languageVersion == null) {
            return null;
        }
        String trimmed = languageVersion.trim();
        if (!"1.2".equals(trimmed)) {
            return failure(configFile,
                "deal.json: unsupported languageVersion '" + languageVersion
                    + "'. DEAL v1.2 is not source-compatible with earlier"
                    + " language versions; this compiler supports only"
                    + " languageVersion '1.2'",
                memberValueRange(members, "languageVersion"));
        }
        return null;
    }

    /**
     * Parses the {@code externals} manifest field (spec map form):
     * {@code "externals": { "host/cfg": { "declaration": "bindings/host-cfg.d.deal" } }}.
     * The key is the import path as written; the declaration path is
     * manifest-relative and must name a host declaration file ({@code .d.deal}
     * suffix).  The legacy list form is retired (empty map).
     * Any other shape — scalars, strings, booleans — and malformed entries
     * (missing, empty, or non-{@code .d.deal} declaration paths) are
     * configuration errors (E2012); nothing is silently absorbed. Entry
     * errors anchor at the offending entry {@code memberRange}; the shape
     * error anchors at the {@code externals} value range (D10).
     */
    private static ExternalsParse parseExternals(Path configFile,
            List<JsonMemberRange> members, Map<String, Object> root) {
        Object v = root.get("externals");
        if (v == null) {
            return new ExternalsParse(Map.of(), null);
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                String declaration = null;
                if (e.getValue() instanceof Map<?, ?> entry) {
                    Object d = entry.get("declaration");
                    if (d instanceof String ds) declaration = ds;
                }
                SourceScalarRange entryRange = entryAnchor(members, key);
                if (declaration == null) {
                    return new ExternalsParse(null, failure(configFile,
                        "deal.json: externals entry '" + key
                            + "' must be an object with a 'declaration' string",
                        entryRange));
                }
                if (declaration.isEmpty()) {
                    return new ExternalsParse(null, failure(configFile,
                        "deal.json: externals entry '" + key
                            + "' has an empty 'declaration' path",
                        entryRange));
                }
                if (!declaration.endsWith(".d.deal")) {
                    return new ExternalsParse(null, failure(configFile,
                        "deal.json: externals entry '" + key
                            + "' declaration must be a host declaration file"
                            + " (.d.deal), got: '" + declaration + "'",
                        entryRange));
                }
                result.put(key, declaration);
            }
            return new ExternalsParse(Collections.unmodifiableMap(result), null);
        }
        // Legacy list form — retired: no externals gating surface.
        if (v instanceof List<?>) {
            return new ExternalsParse(Map.of(), null);
        }
        // Anything else (scalars, strings, booleans) is a config error: only
        // the spec map form and the retired legacy list form are legal.
        return new ExternalsParse(null, failure(configFile,
            "deal.json: 'externals' must be an object (import path → "
                + "{ \"declaration\": ... }) or the legacy array form, got: "
                + (v instanceof String s ? "\"" + s + "\"" : String.valueOf(v)),
            memberValueRange(members, "externals")));
    }

    /**
     * Validates the {@code backend} manifest field (ISSUE-0091: the JVM
     * skeleton backend is now selectable; LuaJIT remains the default when
     * the field is absent). The check is case-insensitive (trim +
     * lowercase), mirroring Backend.fromCliName. Anchors at the
     * {@code backend} member value range.
     */
    private static DealConfigParseResult validateBackend(Path configFile,
            List<JsonMemberRange> members, String backend) {
        if (backend == null) {
            return null;
        }
        String normalizedBackend = backend.trim().toLowerCase(Locale.ROOT);
        if (!normalizedBackend.equals("luajit")
                && !normalizedBackend.equals("lua")
                && !normalizedBackend.equals("jvm")
                && !normalizedBackend.equals("js")) {
            return failure(configFile,
                "deal.json: unsupported backend '" + backend
                    + "'. Supported backends: 'luajit' (or 'lua'), 'jvm', 'js'",
                memberValueRange(members, "backend"));
        }
        return null;
    }

    // =========================================================================
    // Decoded value tree (permissive, mirrors the retired hand-rolled parser)
    // =========================================================================

    /** Walks the ordered tokens back into a decoded value tree. */
    private static Object decodeRoot(List<JsonRangeToken> tokens) {
        return decodeValue(new TokenStream(tokens));
    }

    private static Object decodeValue(TokenStream ts) {
        if (ts.atEnd()) {
            return null;
        }
        JsonRangeToken t = ts.next();
        return switch (t.kind()) {
            case OBJECT_START -> decodeObject(ts);
            case ARRAY_START -> decodeArray(ts);
            case STRING, NUMBER, TRUE, FALSE, NULL -> t.decodedValue();
            default -> null;
        };
    }

    /**
     * Decodes one object from the token stream. A missing close token (the
     * lexer truncates at the after-value cascade or at end of input) closes
     * the object implicitly, exactly like today's loop break.
     */
    private static Map<String, Object> decodeObject(TokenStream ts) {
        Map<String, Object> map = new LinkedHashMap<>();
        while (!ts.atEnd() && ts.peek().kind() != JsonTokenKind.OBJECT_END) {
            JsonRangeToken t = ts.next();
            if (t.kind() != JsonTokenKind.KEY) {
                break;
            }
            String key = (String) t.decodedValue();
            if (!ts.atEnd() && ts.peek().kind() == JsonTokenKind.COLON) {
                ts.next();
            }
            map.put(key, decodeValue(ts)); // duplicate keys: last-wins
            if (!ts.atEnd() && ts.peek().kind() == JsonTokenKind.COMMA) {
                ts.next();
                continue;
            }
            break; // OBJECT_END ahead, or truncation at end of tokens
        }
        if (!ts.atEnd() && ts.peek().kind() == JsonTokenKind.OBJECT_END) {
            ts.next();
        }
        return map;
    }

    /** Decodes one array from the token stream (same truncation rule). */
    private static List<Object> decodeArray(TokenStream ts) {
        List<Object> list = new ArrayList<>();
        while (!ts.atEnd() && ts.peek().kind() != JsonTokenKind.ARRAY_END) {
            list.add(decodeValue(ts));
            if (!ts.atEnd() && ts.peek().kind() == JsonTokenKind.COMMA) {
                ts.next();
                continue;
            }
            break; // ARRAY_END ahead, or truncation at end of tokens
        }
        if (!ts.atEnd() && ts.peek().kind() == JsonTokenKind.ARRAY_END) {
            ts.next();
        }
        return list;
    }

    /** An index cursor over the ordered token list. */
    private static final class TokenStream {
        private final List<JsonRangeToken> tokens;
        private int index;

        TokenStream(List<JsonRangeToken> tokens) {
            this.tokens = tokens;
        }

        boolean atEnd() {
            return index >= tokens.size();
        }

        JsonRangeToken peek() {
            return tokens.get(index);
        }

        JsonRangeToken next() {
            return tokens.get(index++);
        }
    }

    // =========================================================================
    // Decoded-tree getters (today's permissive JsonObject surface)
    // =========================================================================

    private static String getString(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v instanceof String s ? s : null;
    }

    private static Integer getInt(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (v instanceof Long l) return l.intValue();
        if (v instanceof Integer i) return i;
        return null;
    }

    private static List<String> getStringList(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (v instanceof List<?> l) {
            List<String> result = new ArrayList<>();
            for (Object item : l) {
                if (item instanceof String s) result.add(s);
            }
            return result;
        }
        return List.of();
    }

    private static Map<String, Object> getObject(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (v instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            return cast;
        }
        return null;
    }

    // =========================================================================
    // Anchor helpers (T5 record -> T3 carrier conversion, D10)
    // =========================================================================

    /**
     * The pinned {@code SourceScalarRange} → {@code DiagnosticRange}
     * conversion (D10): every JsonRangeLexer range is cursor-computed, so
     * the origin is always SOURCE with exact known offsets and
     * {@code scalarLength == endScalarOffset - startScalarOffset}; the
     * tolerant reader supplies {@code file} from the manifest path it
     * already holds (the lexer receives content only). This conversion
     * never produces a SYNTHETIC range and never carries UNKNOWN offsets.
     */
    private static DiagnosticRange manifestRange(Path configFile, SourceScalarRange r) {
        return new DiagnosticRange(configFile.toString(),
            r.startLine(), r.startColumn(), r.endLine(), r.endColumn(),
            r.startScalarOffset(), r.endScalarOffset(),
            r.endScalarOffset() - r.startScalarOffset(), RangeOrigin.SOURCE);
    }

    /**
     * The pinned document-start zero-length SOURCE range for empty and
     * whitespace-only input (D10): {@code (file,1,1,1,1,0,0,0,SOURCE)} with
     * known offsets (0,0). Never synthetic, no anchor note — the anchor
     * (document start) is real.
     */
    private static DiagnosticRange documentStart(Path configFile) {
        return new DiagnosticRange(configFile.toString(),
            1, 1, 1, 1, 0, 0, 0, RangeOrigin.SOURCE);
    }

    /** One ranged E2012 with a null config (the sole failure shape). */
    private static DealConfigParseResult failure(Path configFile, String message,
                                                 SourceScalarRange range) {
        return new DealConfigParseResult(null,
            List.of(CompilerDiagnostic.error(DiagnosticCode.E2012, message,
                manifestRange(configFile, range))));
    }

    private static DealConfigParseResult invalidJson(Path configFile,
                                                     SourceScalarRange firstToken) {
        return failure(configFile, "deal.json: invalid JSON", firstToken);
    }

    private static DealConfigParseResult invalidJsonAtDocumentStart(Path configFile) {
        return new DealConfigParseResult(null,
            List.of(CompilerDiagnostic.error(DiagnosticCode.E2012,
                "deal.json: invalid JSON", documentStart(configFile))));
    }

    /** The last member record with the given path (last-wins). */
    private static JsonMemberRange lastMember(List<JsonMemberRange> members,
                                              List<String> path) {
        JsonMemberRange found = null;
        for (JsonMemberRange m : members) {
            if (m.path().equals(path)) {
                found = m;
            }
        }
        return found;
    }

    /** The value range of the last root member with the given key. */
    private static SourceScalarRange memberValueRange(List<JsonMemberRange> members,
                                                      String key) {
        JsonMemberRange m = lastMember(members, List.of(key));
        if (m == null) {
            // Defensive: a string-typed field implies a completed member;
            // fall back to the pinned document start if one is missing.
            return new SourceScalarRange(1, 1, 1, 1, 0, 0);
        }
        return m.valueRange();
    }

    /** The member range of the last externals entry with the given key. */
    private static SourceScalarRange entryAnchor(List<JsonMemberRange> members,
                                                 String key) {
        JsonMemberRange m = lastMember(members, List.of("externals", key));
        if (m == null) {
            return new SourceScalarRange(1, 1, 1, 1, 0, 0);
        }
        return m.memberRange();
    }

    /** Parsed externals plus an optional E2012 failure. */
    private record ExternalsParse(Map<String, String> map,
                                  DealConfigParseResult failure) {
    }

    /** Reserved for future use. */
    public record Limits(Integer maxMemory, Integer maxCpuTime) {
        static Limits fromJson(Map<String, Object> obj) {
            if (obj == null) return null;
            return new Limits(getInt(obj, "maxMemory"), getInt(obj, "maxCpuTime"));
        }
    }

    /** Reserved for future use. */
    public record Dependencies(List<String> items) {
        static Dependencies fromJson(Map<String, Object> obj) {
            if (obj == null) return null;
            return new Dependencies(getStringList(obj, "items"));
        }
    }
}
