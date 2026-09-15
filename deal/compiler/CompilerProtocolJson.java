package deal.compiler;

import deal.semantic.ir.CanonicalJson;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Canonical JSON projection for transport-neutral compiler protocol records. */
public final class CompilerProtocolJson {
    private CompilerProtocolJson() {}

    public static String encode(Object value) {
        return CanonicalJson.serializeText(toValue(value));
    }

    public static CanonicalJson.Value decode(String value) {
        return CanonicalJson.parse(value);
    }

    public static CanonicalJson.Value toValue(Object value) {
        if (value == null) return CanonicalJson.nullValue();
        if (value instanceof CanonicalJson.Value canonical) return canonical;
        if (value instanceof String text) return CanonicalJson.str(text);
        if (value instanceof Boolean flag) return CanonicalJson.bool(flag);
        if (value instanceof Integer number) return CanonicalJson.intValue(number);
        if (value instanceof Long number) {
            if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Protocol integer is outside signed32: " + number);
            }
            return CanonicalJson.intValue(number.intValue());
        }
        if (value instanceof Double number) return CanonicalJson.number(number);
        if (value instanceof Float number) return CanonicalJson.number(number.doubleValue());
        if (value instanceof Enum<?> item) return CanonicalJson.str(item.name());
        if (value instanceof List<?> items) {
            return CanonicalJson.arr(items.stream().map(CompilerProtocolJson::toValue).toList());
        }
        if (value instanceof Map<?, ?> map) {
            List<CanonicalJson.Entry> entries = new ArrayList<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text)) {
                    throw new IllegalArgumentException("Protocol map keys must be strings");
                }
                entries.add(CanonicalJson.e(text, toValue(item)));
            });
            entries.sort(Comparator.comparing(CanonicalJson.Entry::key));
            return CanonicalJson.obj(entries);
        }
        if (value.getClass().isRecord()) return recordValue(value);
        CanonicalJson.Obj desugaredRecord = desugaredRecordValue(value);
        if (desugaredRecord != null) return desugaredRecord;
        throw new IllegalArgumentException("Unsupported protocol JSON value: " + value.getClass().getName());
    }

    public static CanonicalJson.Obj requireObject(CanonicalJson.Value value, String context) {
        if (value instanceof CanonicalJson.Obj object) return object;
        throw new IllegalArgumentException(context + " must be a JSON object");
    }

    public static CanonicalJson.Arr requireArray(CanonicalJson.Value value, String context) {
        if (value instanceof CanonicalJson.Arr array) return array;
        throw new IllegalArgumentException(context + " must be a JSON array");
    }

    public static CanonicalJson.Value field(CanonicalJson.Obj object, String name) {
        return object.entries().stream()
                .filter(entry -> entry.key().equals(name))
                .map(CanonicalJson.Entry::value)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing protocol field '" + name + "'"));
    }

    public static String stringField(CanonicalJson.Obj object, String name) {
        CanonicalJson.Value value = field(object, name);
        if (value instanceof CanonicalJson.Str text) return text.value();
        throw new IllegalArgumentException("Protocol field '" + name + "' must be a string");
    }

    public static int intField(CanonicalJson.Obj object, String name) {
        CanonicalJson.Value value = field(object, name);
        if (value instanceof CanonicalJson.Int number) return number.value();
        throw new IllegalArgumentException("Protocol field '" + name + "' must be an integer");
    }

    private static CanonicalJson.Obj recordValue(Object record) {
        List<CanonicalJson.Entry> entries = new ArrayList<>();
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            try {
                entries.add(CanonicalJson.e(component.getName(), toValue(component.getAccessor().invoke(record))));
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Cannot access protocol record " + component.getName(), failure);
            } catch (InvocationTargetException failure) {
                throw new IllegalStateException("Protocol record accessor failed " + component.getName(), failure.getCause());
            }
        }
        return CanonicalJson.obj(entries);
    }

    /** Android D8 lowers records to final fields plus same-named accessors. */
    private static CanonicalJson.Obj desugaredRecordValue(Object value) {
        List<java.lang.reflect.Field> fields = List.of(value.getClass().getDeclaredFields()).stream()
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> Modifier.isFinal(field.getModifiers()))
                .sorted(Comparator.comparing(java.lang.reflect.Field::getName))
                .toList();
        if (fields.isEmpty()) return null;
        List<CanonicalJson.Entry> entries = new ArrayList<>();
        for (java.lang.reflect.Field field : fields) {
            try {
                var accessor = value.getClass().getMethod(field.getName());
                if (accessor.getParameterCount() != 0) return null;
                entries.add(CanonicalJson.e(field.getName(), toValue(accessor.invoke(value))));
            } catch (NoSuchMethodException | IllegalAccessException failure) {
                return null;
            } catch (InvocationTargetException failure) {
                throw new IllegalStateException(
                        "Protocol accessor failed " + field.getName(), failure.getCause());
            }
        }
        return CanonicalJson.obj(entries);
    }
}
