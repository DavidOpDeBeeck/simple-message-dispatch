package app.dodb.smd.api.utils.handlerstrategy;

import java.lang.reflect.Executable;
import java.util.List;

public sealed interface HandlerValidationStrategy
    permits AllowedParameterTypesStrategy,
    AtLeastOneParameterStrategy,
    AtMostOneAssignableParameterStrategy,
    ExactlyOneAssignableParameterStrategy,
    MessageReturnTypeStrategy,
    MetadataValueParameterStrategy,
    NonStaticStrategy,
    PublicStrategy,
    ReturnTypeStrategy {

    static void validateHandler(Executable method, List<HandlerValidationStrategy> handlerValidationStrategies) {
        handlerValidationStrategies.forEach(strategy -> strategy.validate(method));
    }

    void validate(Executable method);
}
