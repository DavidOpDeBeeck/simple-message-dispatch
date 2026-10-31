package app.dodb.smd.api.utils.handlerstrategy;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static java.util.Objects.requireNonNull;

public record ReturnTypeStrategy(Class<?> returnType) implements HandlerValidationStrategy {

    public ReturnTypeStrategy {
        requireNonNull(returnType);
    }

    @Override
    public void validate(Executable method) {
        var methodReturnType = ((Method) method).getReturnType();

        if (!returnType.equals(methodReturnType)) {
            throw new IllegalArgumentException("""
                Invalid handler: return type mismatch.

                    Method:
                    %s

                    Expected:
                    %s

                    Found:
                    %s
                """.formatted(logMethod(method), logClass(returnType), logClass(methodReturnType)));
        }
    }
}
