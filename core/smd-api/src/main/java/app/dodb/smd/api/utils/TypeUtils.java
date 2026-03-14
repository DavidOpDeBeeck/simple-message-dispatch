package app.dodb.smd.api.utils;

import com.google.common.reflect.TypeToken;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static com.google.common.collect.Sets.difference;
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

    public static Set<Class<?>> unrelatedTypes(Set<Class<?>> left, Set<Class<?>> right) {
        var relatedTypes = new HashSet<>();
        for (Class<?> leftClass : left) {
            for (Class<?> rightClass : right) {
                if (leftClass.isAssignableFrom(rightClass) || rightClass.isAssignableFrom(leftClass)) {
                    relatedTypes.add(leftClass);
                    break;
                }
            }
        }
        return difference(left, relatedTypes);
    }

    public static boolean haveSameBounds(Type left, Type right) {
        if (left instanceof ParameterizedType l && right instanceof ParameterizedType r) {
            if (!Objects.equals(l.getRawType(), r.getRawType())) {
                return false;
            }
            var typeArguments = l.getActualTypeArguments();
            for (int i = 0; i < typeArguments.length; i++) {
                if (!haveSameBounds(typeArguments[i], r.getActualTypeArguments()[i])) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof TypeVariable<?> l && right instanceof TypeVariable<?> r) {
            return Arrays.equals(l.getBounds(), r.getBounds());
        }
        return Objects.equals(left, right);
    }

    public static Type resolveGenericType(Class<?> clazz, Class<?> genericType) {
        var genericTypeParameters = genericType.getTypeParameters();
        if (genericTypeParameters.length != 1) {
            throw new IllegalArgumentException("One type parameter must be present on (%s)".formatted(logClass(genericType)));
        }

        var genericTypeParameter = genericTypeParameters[0];
        return TypeToken.of(clazz).getTypes().interfaces().stream()
            .filter(type -> type.getRawType().equals(genericType))
            .findFirst()
            .map(type -> type.resolveType(genericTypeParameter).getType())
            .map(TypeUtils::normalizeToPrimitive)
            .orElseThrow(() -> new IllegalArgumentException("Class (%s) must implement or extend type (%s)".formatted(logClass(clazz), logClass(genericType))));
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
