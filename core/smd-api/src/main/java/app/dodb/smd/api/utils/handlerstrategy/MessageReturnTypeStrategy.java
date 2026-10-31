package app.dodb.smd.api.utils.handlerstrategy;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Type;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static app.dodb.smd.api.utils.TypeUtils.haveSameBounds;
import static app.dodb.smd.api.utils.TypeUtils.resolveGenericType;
import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;

public record MessageReturnTypeStrategy(Class<?> messageType) implements HandlerValidationStrategy {

    public MessageReturnTypeStrategy {
        requireNonNull(messageType);
    }

    @Override
    public void validate(Executable method) {
        var messageParameterType = stream(method.getParameterTypes())
            .filter(messageType::isAssignableFrom)
            .findFirst();
        if (messageParameterType.isEmpty()) {
            return;
        }

        Type messageReturnType = resolveGenericType(messageParameterType.get(), messageType);
        Type methodReturnType = ((Method) method).getGenericReturnType();
        if (!haveSameBounds(methodReturnType, messageReturnType)) {
            throw new IllegalArgumentException("""
                Invalid handler: return type mismatch.

                    Method:
                    %s

                    Expected:
                    %s

                    Found:
                    %s
                """.formatted(logMethod(method), logClass(messageReturnType), logClass(methodReturnType)));
        }
    }
}
