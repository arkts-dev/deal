package deal.semantic.ir;

import java.util.List;
import java.util.Objects;

/**
 * A class layout of {@code deal.semantic-ir/2} (parent D16; schema S2):
 * the structural field layout a {@code CLASS_NEW}/{@code CLASS_FACTORY}
 * and the {@code JSON_FROM_CLASS}/{@code JSON_TO_CLASS} conversions carry.
 * Fields retain declaration order, presence and declaring-provider routing.
 * Construction requires supplied values for every nonoptional field.
 *
 */
public record ClassLayout(ClassId classId, List<FieldLayout> fields) {

    public static final ClassLayout BUILTIN_ERROR = new ClassLayout(
        ClassId.ERROR, List.of(
            new FieldLayout("code", RuntimeDescriptor.String.INSTANCE, true,
                ConstructionProvider.BUILTIN_ERROR),
            new FieldLayout("message", RuntimeDescriptor.String.INSTANCE, true,
                ConstructionProvider.BUILTIN_ERROR)));

    /**
     * One field layout entry: name, declared descriptor, required-present
     * marker, and its exact declaring construction provider.
     */
    public record FieldLayout(
        String name,
        RuntimeDescriptor descriptor,
        boolean required,
        ConstructionProvider constructionProvider
    ) {

        public FieldLayout {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(descriptor, "descriptor must not be null");
            Objects.requireNonNull(constructionProvider, "constructionProvider must not be null");
        }

        /**
         * The field's read-result descriptor (the checker's optional-read
         * wrap): a required-present field reads as its declared descriptor,
         * and an optional field reads as {@code T | null}. The wrap is the
         * one canonical nullable production
         * ({@link RuntimeDescriptor#nullable}), so an optional field whose
         * declared type is {@code null} reads as that {@code null} atom —
         * {@code null | null} is {@code null}, never the forbidden
         * {@code ?null} wrapper measured with a raw
         * {@code new RuntimeDescriptor.Nullable(...)}.
         */
        public RuntimeDescriptor readResultDescriptor() {
            return required ? descriptor : RuntimeDescriptor.nullable(descriptor);
        }
    }

    public ClassLayout(ClassId classId, List<FieldLayout> fields) {
        this.classId = Objects.requireNonNull(classId, "classId must not be null");
        this.fields = List.copyOf(fields);
    }
}
