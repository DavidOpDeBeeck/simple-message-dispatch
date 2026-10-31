package app.dodb.smd.api.utils.handlerstrategy;

import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;

import static app.dodb.smd.api.utils.LoggingUtils.logMethod;

public record PublicStrategy() implements HandlerValidationStrategy {

    @Override
    public void validate(Executable method) {
        if (!Modifier.isPublic(method.getModifiers())) {
            throw new IllegalArgumentException("""
                Invalid handler: method must be public.

                    Method:
                    %s
                """.formatted(logMethod(method)));
        }
    }
}
