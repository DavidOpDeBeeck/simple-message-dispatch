package app.dodb.smd.api.utils.handlerstrategy;

import java.lang.reflect.Executable;
import java.util.Set;

import static app.dodb.smd.api.utils.LoggingUtils.logClasses;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static app.dodb.smd.api.utils.TypeUtils.unrelatedTypes;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toSet;

public record AllowedParameterTypesStrategy(Set<Class<?>> allowedParameterTypes) implements HandlerValidationStrategy {

    @Override
    public void validate(Executable method) {
        Set<Class<?>> allParameterTypes = stream(method.getParameterTypes()).collect(toSet());
        Set<Class<?>> unsupportedParameterTypes = unrelatedTypes(allParameterTypes, allowedParameterTypes);

        if (!unsupportedParameterTypes.isEmpty()) {
            throw new IllegalArgumentException("""
                Invalid handler: unsupported parameter types found.

                    Method:
                    %s

                    Allowed:
                    %s

                    Found:
                    %s
                """.formatted(logMethod(method), logClasses(allowedParameterTypes), logClasses(unsupportedParameterTypes)));
        }
    }
}
