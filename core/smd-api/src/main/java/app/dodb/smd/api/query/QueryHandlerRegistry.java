package app.dodb.smd.api.query;

import app.dodb.smd.api.utils.LoggingUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static app.dodb.smd.api.utils.CollectionUtils.combine;
import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static java.util.Objects.requireNonNull;

public record QueryHandlerRegistry(Set<AnnotatedQueryHandler<?, ?>> queryHandlers) {

    public static QueryHandlerRegistry empty() {
        return new QueryHandlerRegistry(new HashSet<>());
    }

    public QueryHandlerRegistry {
        requireNonNull(queryHandlers);
        validateUniqueQueryTypeMatchers(queryHandlers);
    }

    @SuppressWarnings("unchecked")
    public <R, C extends Query<R>> QueryHandlerBehaviour<R, C> findBy(QueryMessage<R, C> queryMessage) {
        Object payload = queryMessage.getPayload();
        return queryHandlers.stream()
            .filter(queryHandler -> queryHandler.queryType().isAssignableFrom(payload.getClass()))
            .map(queryHandler -> (QueryHandlerBehaviour<R, C>) queryHandler)
            .findAny()
            .orElseThrow(() -> new IllegalArgumentException("No query handler found for " + logClass(payload.getClass())));
    }

    public QueryHandlerRegistry and(QueryHandlerRegistry other) {
        return new QueryHandlerRegistry(combine(queryHandlers, other.queryHandlers));
    }

    private void validateUniqueQueryTypeMatchers(Set<AnnotatedQueryHandler<?, ?>> queryHandlers) {
        for (AnnotatedQueryHandler<?, ?> queryHandler : queryHandlers) {
            var overlappingHandlers = queryHandlers.stream()
                .filter(handler -> queryHandler.queryType().isAssignableFrom(handler.queryType()))
                .toList();

            if (overlappingHandlers.size() > 1) {
                throw new IllegalArgumentException("""
                    Ambiguous query handlers found:
                    
                    Query:
                    %s
                    
                    Methods:
                    %s
                    """.formatted(
                    queryHandler.queryType().getName(),
                    overlappingHandlers.stream()
                        .map(AnnotatedQueryHandler::method)
                        .map(LoggingUtils::logMethod)
                        .collect(Collectors.joining("\n"))));
            }
        }
    }
}
