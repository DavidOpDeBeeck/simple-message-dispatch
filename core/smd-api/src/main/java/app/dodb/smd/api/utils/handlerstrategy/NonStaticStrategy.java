package app.dodb.smd.api.utils.handlerstrategy;

import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;

import static app.dodb.smd.api.utils.LoggingUtils.logMethod;

public record NonStaticStrategy() implements HandlerValidationStrategy {

    @Override
    public void validate(Executable method) {
        if (Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException("""
                Invalid handler: method must not be static.

                    Method:
                    %s
                """.formatted(logMethod(method)));
        }
    }
}
