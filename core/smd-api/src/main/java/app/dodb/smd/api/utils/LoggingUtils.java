package app.dodb.smd.api.utils;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Collection;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;

public class LoggingUtils {

    public static String logClasses(Collection<Class<?>> classes) {
        return classes.stream()
            .map(LoggingUtils::logClass)
            .collect(joining(", "));
    }

    public static String logClass(Type type) {
        if (type == null) {
            return "null";
        }
        return type.getTypeName();
    }

    public static String logMethod(Method method) {
        return "%s#%s(%s)".formatted(
            method.getDeclaringClass().getName(),
            method.getName(),
            stream(method.getParameterTypes())
                .map(Class::getSimpleName)
                .collect(joining(", "))
        );
    }
}
