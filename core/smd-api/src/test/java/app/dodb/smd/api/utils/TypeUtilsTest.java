package app.dodb.smd.api.utils;

import app.dodb.smd.api.metadata.MetadataValue;
import app.dodb.smd.api.utils.parameterstrategy.AllowedParameterTypesStrategy;
import app.dodb.smd.api.utils.parameterstrategy.AtLeastOneParameterStrategy;
import app.dodb.smd.api.utils.parameterstrategy.AtMostOneAssignableParameterStrategy;
import app.dodb.smd.api.utils.parameterstrategy.MetadataValueParameterStrategy;
import app.dodb.smd.api.utils.parameterstrategy.ParameterValidationStrategy;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.TypeUtils.getAnnotationOnMethodOrClass;
import static app.dodb.smd.api.utils.TypeUtils.haveSameBounds;
import static app.dodb.smd.api.utils.TypeUtils.resolveGenericType;
import static app.dodb.smd.api.utils.TypeUtils.unrelatedTypes;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.Arrays.stream;
import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TypeUtilsTest {

    @Test
    void validateParameters_whenAtMostOneAssignableParameterStrategyHasDuplicateParameters_throwsDefaultException() throws NoSuchMethodException {
        var method = ParameterFixtures.class.getDeclaredMethod("duplicateStringParameters", String.class, String.class, Integer.class, Integer.class);
        var parameters = parametersOf("duplicateStringParameters");

        assertThatThrownBy(() -> ParameterValidationStrategy.validateParameters(method, parameters, List.of(
            new AtMostOneAssignableParameterStrategy(String.class),
            new AtMostOneAssignableParameterStrategy(Integer.class)
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: method must only include one parameter of type %s.".formatted(logClass(String.class)));
    }

    @Test
    void validateParameters_whenAtLeastOneParameterStrategyHasNoParameters_throwsDefaultException() throws NoSuchMethodException {
        var method = ParameterFixtures.class.getDeclaredMethod("withoutParameters");
        var parameters = parametersOf("withoutParameters");

        assertThatThrownBy(() -> ParameterValidationStrategy.validateParameters(method, parameters, List.of(
            new AtLeastOneParameterStrategy()
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: method must have at least one parameter.");
    }

    @Test
    void validateParameters_whenMetadataValueParameterStrategyHasBareString_throwsDefaultException() throws NoSuchMethodException {
        var method = ParameterFixtures.class.getDeclaredMethod("plainStringParameter", String.class);
        var parameters = parametersOf("plainStringParameter");

        assertThatThrownBy(() -> ParameterValidationStrategy.validateParameters(method, parameters, List.of(
            new MetadataValueParameterStrategy()
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: metadata value parameter must be annotated with @MetadataValue.");
    }

    @Test
    void validateParameters_whenMetadataValueParameterStrategyHasNonStringAnnotatedParameter_throwsDefaultException() throws NoSuchMethodException {
        var method = ParameterFixtures.class.getDeclaredMethod("nonStringMetadataValueParameter", Integer.class);
        var parameters = parametersOf("nonStringMetadataValueParameter");

        assertThatThrownBy(() -> ParameterValidationStrategy.validateParameters(method, parameters, List.of(
            new MetadataValueParameterStrategy()
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: only parameters of type String can be annotated with @MetadataValue.");
    }

    @Test
    void validateParameters_whenAllowedParameterTypesStrategyHasUnsupportedType_throwsDefaultException() throws NoSuchMethodException {
        var method = ParameterFixtures.class.getDeclaredMethod("unsupportedParameterType", Runnable.class, Long.class);
        var parameters = parametersOf("unsupportedParameterType");

        assertThatThrownBy(() -> ParameterValidationStrategy.validateParameters(method, parameters, List.of(
            new AllowedParameterTypesStrategy(
                Set.of(Runnable.class, String.class, Integer.class)
            )
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: unsupported parameter types found.")
            .hasMessageContaining("Long");
    }

    @Test
    void haveSameBounds_whenBothTypeVariablesHaveSameBounds_returnsTrue() {
        var type = resolveGenericType(UnboundSubType.class, Root.class);
        var anotherType = resolveGenericType(AnotherUnboundSubType.class, Root.class);
        assertThat(haveSameBounds(type, anotherType)).isTrue();
    }

    @Test
    void haveSameBounds_whenBothTypeVariablesHaveDifferentBounds_returnsFalse() {
        var type = resolveGenericType(UnboundSubType.class, Root.class);
        var anotherType = resolveGenericType(BoundSubType.class, Root.class);
        assertThat(haveSameBounds(type, anotherType)).isFalse();
    }

    @Test
    void haveSameBounds_whenBothAreParameterizedTypesWithSameBounds_returnsTrue() {
        var type = resolveGenericType(ParameterizedUnboundSubType.class, Root.class);
        var anotherType = resolveGenericType(AnotherParameterizedUnboundSubType.class, Root.class);
        assertThat(haveSameBounds(type, anotherType)).isTrue();
    }

    @Test
    void haveSameBounds_whenBothAreParameterizedTypesWithDifferentRawTypes_returnsFalse() {
        var type = resolveGenericType(ParameterizedUnboundSubType.class, Root.class);
        var anotherType = resolveGenericType(DifferentlyParameterizedUnboundSubType.class, Root.class);
        assertThat(haveSameBounds(type, anotherType)).isFalse();
    }

    @Test
    void haveSameBounds_whenBothAreParameterizedTypesWithDifferentTypeArgumentBounds_returnsFalse() {
        var type = resolveGenericType(ParameterizedUnboundSubType.class, Root.class);
        var anotherType = resolveGenericType(ParameterizedBoundSubType.class, Root.class);
        assertThat(haveSameBounds(type, anotherType)).isFalse();
    }

    @Test
    void haveSameBounds_whenBothAreSameClass_returnsTrue() {
        assertThat(haveSameBounds(Object.class, Object.class)).isTrue();
    }

    @Test
    void haveSameBounds_whenBothAreDifferentClasses_returnsFalse() {
        assertThat(haveSameBounds(String.class, Object.class)).isFalse();
    }

    @Test
    void haveSameBounds_whenOneIsTypeVariableAndOtherIsClass_returnsFalse() {
        var typeVariable = resolveGenericType(UnboundSubType.class, Root.class);
        assertThat(haveSameBounds(typeVariable, String.class)).isFalse();
        assertThat(haveSameBounds(String.class, typeVariable)).isFalse();
    }

    @Test
    void haveSameBounds_whenBothNull_returnsTrue() {
        assertThat(haveSameBounds(null, null)).isTrue();
    }

    @Test
    void haveSameBounds_whenOneIsNull_returnsFalse() {
        assertThat(haveSameBounds(String.class, null)).isFalse();
        assertThat(haveSameBounds(null, String.class)).isFalse();
    }

    @Test
    void resolveGenericType_whenTypeIsWrapperClass_returnsPrimitiveType() {
        assertThat(resolveGenericType(BooleanSubType.class, Root.class)).isEqualTo(Boolean.TYPE);
        assertThat(resolveGenericType(ByteSubType.class, Root.class)).isEqualTo(Byte.TYPE);
        assertThat(resolveGenericType(ShortSubType.class, Root.class)).isEqualTo(Short.TYPE);
        assertThat(resolveGenericType(IntegerSubType.class, Root.class)).isEqualTo(Integer.TYPE);
        assertThat(resolveGenericType(LongSubType.class, Root.class)).isEqualTo(Long.TYPE);
        assertThat(resolveGenericType(FloatSubType.class, Root.class)).isEqualTo(Float.TYPE);
        assertThat(resolveGenericType(DoubleSubType.class, Root.class)).isEqualTo(Double.TYPE);
        assertThat(resolveGenericType(CharacterSubType.class, Root.class)).isEqualTo(Character.TYPE);
        assertThat(resolveGenericType(VoidSubType.class, Root.class)).isEqualTo(Void.TYPE);
    }

    @Test
    void resolveGenericType_whenTypeIsInherited_returnsResolvedType() {
        assertThat(resolveGenericType(SubType.class, Root.class)).isEqualTo(String.class);
        assertThat(resolveGenericType(SubSubType.class, Root.class)).isEqualTo(String.class);
    }

    @Test
    void resolveGenericType_whenTypeIsParameterized_returnsResolvedType() {
        assertThat(resolveGenericType(OptionalSubType.class, Root.class)).isEqualTo(parameterizedType(Optional.class, String.class));
        assertThat(resolveGenericType(OptionalGenericSubSubType.class, Root.class)).isEqualTo(parameterizedType(Optional.class, String.class));
    }

    @Test
    void resolveGenericType_whenClassDoesNotImplementGenericType_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> resolveGenericType(OtherRoot.class, Root.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Class (app.dodb.smd.api.utils.TypeUtilsTest$OtherRoot) must implement or extend type (app.dodb.smd.api.utils.TypeUtilsTest$Root)");
    }

    @Test
    void resolveGenericType_whenGenericTypeHasNoTypeParameters_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> resolveGenericType(RootWithoutGeneric.class, RootWithoutGeneric.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("One type parameter must be present on (app.dodb.smd.api.utils.TypeUtilsTest$RootWithoutGeneric)");
    }

    @Test
    void resolveGenericType_whenGenericTypeHasMultipleTypeParameters_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> resolveGenericType(RootWithMultipleGenerics.class, RootWithMultipleGenerics.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("One type parameter must be present on (app.dodb.smd.api.utils.TypeUtilsTest$RootWithMultipleGenerics)");
    }

    @Test
    void getAnnotationOnMethodOrClass_whenAnnotationIsOnClass_returnsClassAnnotation() throws NoSuchMethodException {
        var method = ClassWithAnnotationOnClass.class.getMethod("method");
        var classAnnotation = ClassWithAnnotationOnClass.class.getAnnotation(MarkerAnnotation.class);

        assertThat(getAnnotationOnMethodOrClass(method, MarkerAnnotation.class)).contains(classAnnotation);
    }

    @Test
    void getAnnotationOnMethodOrClass_whenAnnotationIsOnMethod_returnsMethodAnnotation() throws NoSuchMethodException {
        var method = ClassWithAnnotationOnMethod.class.getMethod("method");
        var methodAnnotation = method.getAnnotation(MarkerAnnotation.class);

        assertThat(getAnnotationOnMethodOrClass(method, MarkerAnnotation.class)).contains(methodAnnotation);
    }

    @Test
    void getAnnotationOnMethodOrClass_whenAnnotationIsOnBothClassAndMethod_returnsMethodAnnotation() throws NoSuchMethodException {
        var method = ClassWithAnnotationOnClassAndMethod.class.getMethod("method");
        var methodAnnotation = method.getAnnotation(MarkerAnnotation.class);

        assertThat(getAnnotationOnMethodOrClass(method, MarkerAnnotation.class)).contains(methodAnnotation);
    }

    @Test
    void getAnnotationOnMethodOrClass_whenNoAnnotationPresent_returnsEmpty() throws NoSuchMethodException {
        var method = ClassWithoutAnnotation.class.getMethod("method");

        assertThat(getAnnotationOnMethodOrClass(method, MarkerAnnotation.class)).isEmpty();
    }

    @Test
    void unrelatedTypes_whenNoRelationshipExists_returnsAllOfLeft() {
        var left = Set.<Class<?>>of(String.class, Integer.class);
        var right = Set.of(Runnable.class, CharSequence.class);

        assertThat(unrelatedTypes(left, right)).containsExactlyInAnyOrder(Integer.class);
    }

    @Test
    void unrelatedTypes_whenLeftIsSupertype_removesLeftEntry() {
        var left = Set.<Class<?>>of(Number.class, String.class);
        var right = Set.<Class<?>>of(Integer.class);

        assertThat(unrelatedTypes(left, right)).containsExactly(String.class);
    }

    @Test
    void unrelatedTypes_whenLeftIsSubtype_removesLeftEntry() {
        var left = Set.<Class<?>>of(Integer.class, String.class);
        var right = Set.<Class<?>>of(Number.class);

        assertThat(unrelatedTypes(left, right)).containsExactly(String.class);
    }

    @Test
    void unrelatedTypes_whenLeftIsSameType_removesLeftEntry() {
        var left = Set.<Class<?>>of(String.class, Integer.class);
        var right = Set.<Class<?>>of(String.class);

        assertThat(unrelatedTypes(left, right)).containsExactly(Integer.class);
    }

    @Test
    void unrelatedTypes_whenAllLeftEntriesAreRelated_returnsEmptySet() {
        var left = Set.<Class<?>>of(Number.class, Integer.class);
        var right = Set.<Class<?>>of(Integer.class, Number.class);

        assertThat(unrelatedTypes(left, right)).isEmpty();
    }

    @Test
    void unrelatedTypes_whenLeftIsEmpty_returnsEmptySet() {
        assertThat(unrelatedTypes(emptySet(), Set.of(String.class))).isEmpty();
    }

    @Test
    void unrelatedTypes_whenRightIsEmpty_returnsAllOfLeft() {
        var left = Set.<Class<?>>of(String.class, Integer.class);

        assertThat(unrelatedTypes(left, emptySet())).containsExactlyInAnyOrder(String.class, Integer.class);
    }

    @Test
    void unrelatedTypes_whenBothEmpty_returnsEmptySet() {
        assertThat(unrelatedTypes(emptySet(), emptySet())).isEmpty();
    }

    interface Root<T> {
    }

    interface SubType extends Root<String> {
    }

    record SubSubType() implements SubType {
    }

    interface BooleanSubType extends Root<Boolean> {
    }

    interface ByteSubType extends Root<Byte> {
    }

    interface ShortSubType extends Root<Short> {
    }

    interface IntegerSubType extends Root<Integer> {
    }

    interface LongSubType extends Root<Long> {
    }

    interface FloatSubType extends Root<Float> {
    }

    interface DoubleSubType extends Root<Double> {
    }

    interface CharacterSubType extends Root<Character> {
    }

    interface VoidSubType extends Root<Void> {
    }

    interface UnboundSubType<T> extends Root<T> {
    }

    interface ParameterizedUnboundSubType<T> extends Root<UnboundSubType<T>> {
    }

    interface AnotherUnboundSubType<T> extends Root<T> {
    }

    interface AnotherParameterizedUnboundSubType<T> extends Root<UnboundSubType<T>> {
    }

    interface DifferentlyParameterizedUnboundSubType<T> extends Root<AnotherUnboundSubType<T>> {
    }

    interface BoundSubType<T extends String> extends Root<T> {
    }

    interface ParameterizedBoundSubType<T extends String> extends Root<UnboundSubType<T>> {
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

    @MarkerAnnotation
    private static class ClassWithAnnotationOnClassAndMethod {

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

    private static Parameter[] parametersOf(String methodName) throws NoSuchMethodException {
        return stream(ParameterFixtures.class.getDeclaredMethods())
            .filter(method -> method.getName().equals(methodName))
            .findFirst()
            .orElseThrow()
            .getParameters();
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

    private static class ParameterFixtures {

        @SuppressWarnings("unused")
        static void duplicateStringParameters(String firstString, String secondString, Integer firstInteger, Integer secondInteger) {
        }

        @SuppressWarnings("unused")
        static void plainStringParameter(String value) {
        }

        @SuppressWarnings("unused")
        static void nonStringMetadataValueParameter(@MetadataValue("value") Integer value) {
        }

        @SuppressWarnings("unused")
        static void unsupportedParameterType(Runnable runnable, Long value) {
        }

        @SuppressWarnings("unused")
        static void withoutParameters() {
        }
    }
}
