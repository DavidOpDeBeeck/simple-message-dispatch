package app.dodb.smd.api.utils.parameterstrategy;

import app.dodb.smd.api.metadata.MetadataValue;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static java.util.Arrays.stream;

public record MetadataValueParameterStrategy() implements ParameterValidationStrategy {

    @Override
    public void validate(Method method, Parameter[] parameters) {
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
