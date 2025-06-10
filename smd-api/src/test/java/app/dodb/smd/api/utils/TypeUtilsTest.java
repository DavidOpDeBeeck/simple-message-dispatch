package app.dodb.smd.api.utils;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;

import static app.dodb.smd.api.utils.TypeUtils.getAnnotationOnMethodOrClass;
import static app.dodb.smd.api.utils.TypeUtils.resolveGenericType;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TypeUtilsTest {

    @Test
    void resolveGenericType_withInheritance() {
        assertThat(resolveGenericType(SubType.class, Root.class)).isEqualTo(String.class);
        assertThat(resolveGenericType(SubSubType.class, Root.class)).isEqualTo(String.class);
    }

    @Test
    void resolveGenericType_withParameterizedTypes() {
        assertThat(resolveGenericType(OptionalSubType.class, Root.class)).isEqualTo(parameterizedType(Optional.class, String.class));
        assertThat(resolveGenericType(OptionalGenericSubSubType.class, Root.class)).isEqualTo(parameterizedType(Optional.class, String.class));
    }

    @Test
    void resolveGenericType_withVoidGenericType() {
        assertThat(resolveGenericType(VoidSubType.class, Root.class)).isEqualTo(Void.TYPE);
    }

    @Test
    void resolveGenericType_withUnboundGenericType() {
        assertThatThrownBy(() -> resolveGenericType(Root.class, Root.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Type parameter (T) on (app.dodb.smd.api.utils.TypeUtilsTest$Root) must be bound to a concrete type");
        assertThatThrownBy(() -> resolveGenericType(UnboundSubType.class, Root.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Type parameter (T) on (app.dodb.smd.api.utils.TypeUtilsTest$UnboundSubType) must be bound to a concrete type");
    }

    @Test
    void resolveGenericType_withoutInheritance() {
        assertThatThrownBy(() -> resolveGenericType(OtherRoot.class, Root.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Class (app.dodb.smd.api.utils.TypeUtilsTest$OtherRoot) must implement or extend type (app.dodb.smd.api.utils.TypeUtilsTest$Root)");
    }

    @Test
    void resolveGenericType_withoutGenerics() {
        assertThatThrownBy(() -> resolveGenericType(RootWithoutGeneric.class, RootWithoutGeneric.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("One type parameter must be present on (app.dodb.smd.api.utils.TypeUtilsTest$RootWithoutGeneric)");
    }

    @Test
    void resolveGenericType_withMultipleGenerics() {
        assertThatThrownBy(() -> resolveGenericType(RootWithMultipleGenerics.class, RootWithMultipleGenerics.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("One type parameter must be present on (app.dodb.smd.api.utils.TypeUtilsTest$RootWithMultipleGenerics)");
    }

    @Test
    void getAnnotationOnMethodOrClass_withAnnotationOnClass() throws NoSuchMethodException {
        var method = ClassWithAnnotationOnClass.class.getMethod("method");
        var expected = ClassWithAnnotationOnClass.class.getAnnotation(MarkerAnnotation.class);

        assertThat(getAnnotationOnMethodOrClass(method, MarkerAnnotation.class)).contains(expected);
    }

    @Test
    void getAnnotationOnMethodOrClass_withAnnotationOnMethod() throws NoSuchMethodException {
        var method = ClassWithAnnotationOnMethod.class.getMethod("method");
        var expected = method.getAnnotation(MarkerAnnotation.class);

        assertThat(getAnnotationOnMethodOrClass(method, MarkerAnnotation.class)).contains(expected);
    }

    @Test
    void getAnnotationOnMethodOrClass_withoutAnnotation() throws NoSuchMethodException {
        var method = ClassWithoutAnnotation.class.getMethod("method");

        assertThat(getAnnotationOnMethodOrClass(method, MarkerAnnotation.class)).isEmpty();
    }

    interface Root<T> {
    }

    interface SubType extends Root<String> {
    }

    record SubSubType() implements SubType {
    }

    interface VoidSubType extends Root<Void> {
    }

    interface UnboundSubType<T> extends Root<T> {
    }

    record OptionalSubType() implements Root<Optional<String>> {
    }

    interface OptionalGenericSubType<T> extends Root<Optional<T>> {
    }

    record OptionalGenericSubSubType() implements OptionalGenericSubType<String> {
    }

    interface OtherRoot<T> {
    }

    interface RootWithoutGeneric {
    }

    interface RootWithMultipleGenerics<T, R> {
    }

    @MarkerAnnotation
    private static class ClassWithAnnotationOnClass {

        public void method() {
        }
    }

    private static class ClassWithAnnotationOnMethod {

        @MarkerAnnotation
        public void method() {
        }
    }

    private static class ClassWithoutAnnotation {

        public void method() {
        }
    }

    @Retention(RUNTIME)
    @Target({TYPE, METHOD})
    @interface MarkerAnnotation {
    }

    private static ParameterizedType parameterizedType(Type rawType, Type actualType) {
        return new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return new Type[]{actualType};
            }

            @Override
            public Type getRawType() {
                return rawType;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }
        };
    }
}