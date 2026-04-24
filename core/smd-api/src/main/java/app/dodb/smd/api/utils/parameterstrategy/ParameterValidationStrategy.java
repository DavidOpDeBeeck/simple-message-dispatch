package app.dodb.smd.api.utils.parameterstrategy;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

public sealed interface ParameterValidationStrategy permits AllowedParameterTypesStrategy, AtLeastOneParameterStrategy, AtMostOneAssignableParameterStrategy, ExactlyOneAssignableParameterStrategy, MetadataValueParameterStrategy {

    static void validateParameters(Method method, Parameter[] parameters, List<ParameterValidationStrategy> parameterValidationStrategies) {
        parameterValidationStrategies.forEach(strategy -> strategy.validate(method, parameters));
    }

    void validate(Method method, Parameter[] parameters);
}
