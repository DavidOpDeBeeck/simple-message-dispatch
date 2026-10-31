package app.dodb.smd.api.query;

import java.util.HashSet;
import java.util.Set;

import static app.dodb.smd.api.utils.CollectionUtils.combine;
import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

public record QueryHandlerRegistry(Set<QueryHandlerBehaviour<?, ?>> queryHandlers) {

    public static QueryHandlerRegistry empty() {
        return new QueryHandlerRegistry(new HashSet<>());
    }

    public QueryHandlerRegistry {
        requireNonNull(queryHandlers);
        validateUniqueQueryTypeMatchers(queryHandlers);
    }

    @SuppressWarnings("unchecked")
    public <R, Q extends Query<R>> QueryHandlerBehaviour<R, Q> findBy(QueryMessage<R, Q> queryMessage) {
        Object payload = queryMessage.payload();
        return queryHandlers.stream()
            .filter(queryHandler -> queryHandler.queryType().isAssignableFrom(payload.getClass()))
            .map(queryHandler -> (QueryHandlerBehaviour<R, Q>) queryHandler)
            .findAny()
            .orElseThrow(() -> new IllegalArgumentException("No query handler found for " + logClass(payload.getClass())));
    }

    public QueryHandlerRegistry and(QueryHandlerRegistry other) {
        return new QueryHandlerRegistry(combine(queryHandlers, other.queryHandlers));
    }

    private void validateUniqueQueryTypeMatchers(Set<QueryHandlerBehaviour<?, ?>> queryHandlers) {
        for (QueryHandlerBehaviour<?, ?> queryHandler : queryHandlers) {
            var overlappingHandlers = queryHandlers.stream()
                .filter(handler -> queryHandler.queryType().isAssignableFrom(handler.queryType()))
                .toList();

            if (overlappingHandlers.size() > 1) {
                throw new IllegalArgumentException("""
                    Ambiguous query handlers found:

                    Query:
                    %s

                    Handlers:
                    %s
                    """.formatted(
                    queryHandler.queryType().getName(),
                    overlappingHandlers.stream()
                        .map(Object::toString)
                        .collect(joining("\n"))));
            }
        }
    }
}
