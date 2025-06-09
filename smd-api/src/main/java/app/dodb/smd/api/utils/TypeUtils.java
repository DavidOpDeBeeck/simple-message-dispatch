package app.dodb.smd.api.utils;

import com.google.common.reflect.TypeToken;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Objects;
import java.util.Optional;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;

public class TypeUtils {

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
        if (Objects.equals(resolved, Void.class)) {
            return Void.TYPE;
        }
        return resolved instanceof TypeVariable<?> ? null : resolved;
    }

    public static <T extends Annotation> Optional<T> getAnnotationOnMethodOrClass(Method method, Class<T> annotationClass) {
        T annotationOnMethod = method.getAnnotation(annotationClass);
        if (annotationOnMethod != null) {
            return Optional.of(annotationOnMethod);
        }
        return Optional.ofNullable(method.getDeclaringClass().getAnnotation(annotationClass));
    }
}
