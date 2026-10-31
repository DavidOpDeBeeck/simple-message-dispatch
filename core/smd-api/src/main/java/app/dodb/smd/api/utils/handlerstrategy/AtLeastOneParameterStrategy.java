package app.dodb.smd.api.utils.handlerstrategy;

import java.lang.reflect.Executable;

import static app.dodb.smd.api.utils.LoggingUtils.logMethod;

public record AtLeastOneParameterStrategy() implements HandlerValidationStrategy {

    @Override
    public void validate(Executable method) {
        if (method.getParameterCount() == 0) {
            throw new IllegalArgumentException("""
                Invalid handler: method must have at least one parameter.

                    Method:
                    %s
                """.formatted(logMethod(method)));
        }
    }
}
