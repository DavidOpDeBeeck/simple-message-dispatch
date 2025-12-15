package app.dodb.smd.api.utils;

import com.google.common.reflect.TypeToken;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Map;
import java.util.Optional;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static java.util.Optional.ofNullable;

public class TypeUtils {

    private static final Map<Type, Type> WRAPPER_TO_PRIMITIVE = Map.of(
        Boolean.class, Boolean.TYPE,
        Byte.class, Byte.TYPE,
        Short.class, Short.TYPE,
        Integer.class, Integer.TYPE,
        Long.class, Long.TYPE,
        Float.class, Float.TYPE,
        Double.class, Double.TYPE,
        Character.class, Character.TYPE,
        Void.class, Void.TYPE
    );

    public static Type resolveGenericType(Class<?> clazz, Class<?> genericType) {
        var genericTypeParameters = genericType.getTypeParameters();
        if (genericTypeParameters.length != 1) {
            throw new IllegalArgumentException("One type parameter must be present on (%s)".formatted(logClass(genericType)));
        }

        TypeToken<?> genericTypeToken = TypeToken.of(clazz).getTypes().interfaces()
            .stream()
            .filter(type -> type.getRawType().equals(genericType))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Class (%s) must implement or extend type (%s)".formatted(logClass(clazz), logClass(genericType))));

        Type resolved = genericTypeToken.resolveType(genericTypeParameters[0]).getType();
        if (resolved instanceof TypeVariable<?>) {
            throw new IllegalArgumentException("Type parameter (%s) on (%s) must be bound to a concrete type".formatted(logClass(resolved), logClass(clazz)));
        }
        return normalizeToPrimitive(resolved);
    }

    public static <T extends Annotation> Optional<T> getAnnotationOnMethodOrClass(Method method, Class<T> annotationClass) {
        T annotationOnMethod = method.getAnnotation(annotationClass);
        T annotationOnClass = method.getDeclaringClass().getAnnotation(annotationClass);

        return ofNullable(annotationOnMethod).or(() -> ofNullable(annotationOnClass));
    }

    private static Type normalizeToPrimitive(Type type) {
        return WRAPPER_TO_PRIMITIVE.getOrDefault(type, type);
    }
}
