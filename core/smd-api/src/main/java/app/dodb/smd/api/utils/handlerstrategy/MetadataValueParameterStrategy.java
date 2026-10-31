package app.dodb.smd.api.utils.handlerstrategy;

import app.dodb.smd.api.metadata.MetadataValue;

import java.lang.reflect.Executable;

import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static java.util.Arrays.stream;

public record MetadataValueParameterStrategy() implements HandlerValidationStrategy {

    @Override
    public void validate(Executable method) {
        var parameters = method.getParameters();

        stream(parameters)
            .filter(parameter -> String.class.isAssignableFrom(parameter.getType()))
            .filter(parameter -> !parameter.isAnnotationPresent(MetadataValue.class))
            .findFirst()
            .ifPresent(parameter -> {
                throw new IllegalArgumentException("""
                    Invalid handler: metadata value parameter must be annotated with @MetadataValue.
                    
                        Method:
                        %s
                    
                        Parameter:
                        %s
                    """.formatted(logMethod(method), parameter.getName()));
            });

        stream(parameters)
            .filter(parameter -> parameter.isAnnotationPresent(MetadataValue.class))
            .filter(parameter -> !String.class.isAssignableFrom(parameter.getType()))
            .findFirst()
            .ifPresent(parameter -> {
                throw new IllegalArgumentException("""
                    Invalid handler: only parameters of type String can be annotated with @MetadataValue.
                    
                        Method:
                        %s
                    
                        Parameter:
                        %s
                    """.formatted(logMethod(method), parameter.getName()));
            });
    }
}
