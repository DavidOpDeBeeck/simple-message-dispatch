package app.dodb.smd.api.utils.parameterstrategy;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static app.dodb.smd.api.utils.LoggingUtils.logMethod;

public record AtLeastOneParameterStrategy() implements ParameterValidationStrategy {

    @Override
    public void validate(Method method, Parameter[] parameters) {
        if (parameters.length == 0) {
            throw new IllegalArgumentException("""
                Invalid handler: method must have at least one parameter.
                
                    Method:
                    %s
                """.formatted(logMethod(method)));
        }
    }
}
