package app.dodb.smd.api.utils.handlerstrategy;

import java.lang.reflect.Executable;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static java.util.Arrays.stream;

public record ExactlyOneAssignableParameterStrategy(Class<?> parameterType) implements HandlerValidationStrategy {

    @Override
    public void validate(Executable method) {
        long matchingParameters = stream(method.getParameterTypes())
            .filter(parameterType::isAssignableFrom)
            .count();

        if (matchingParameters == 0) {
            throw new IllegalArgumentException("""
                Invalid handler: method must include a parameter of type %s.

                    Method:
                    %s
                """.formatted(logClass(parameterType), logMethod(method)));
        }
        if (matchingParameters > 1) {
            throw new IllegalArgumentException("""
                Invalid handler: method must only include one parameter of type %s.

                    Method:
                    %s
                """.formatted(logClass(parameterType), logMethod(method)));
        }
    }
}
