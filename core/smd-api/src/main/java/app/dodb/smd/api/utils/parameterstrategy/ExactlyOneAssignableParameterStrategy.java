package app.dodb.smd.api.utils.parameterstrategy;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static java.util.Arrays.stream;

public record ExactlyOneAssignableParameterStrategy(Class<?> parameterType) implements ParameterValidationStrategy {

    @Override
    public void validate(Method method, Parameter[] parameters) {
        long matchingParameters = stream(parameters)
            .filter(parameter -> parameterType.isAssignableFrom(parameter.getType()))
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
