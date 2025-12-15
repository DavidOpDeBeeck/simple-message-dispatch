package app.dodb.smd.api.query;

import static java.util.Objects.requireNonNull;

public class QueryHandlerDispatcher {

    private final QueryHandlerRegistry queryHandlerRegistry;

    public QueryHandlerDispatcher(QueryHandlerLocator queryHandlerLocator) {
        this.queryHandlerRegistry = requireNonNull(queryHandlerLocator).locate();
    }

    public <R, Q extends Query<R>> R dispatch(QueryMessage<R, Q> queryMessage) {
        return queryHandlerRegistry.findBy(queryMessage).handle(queryMessage);
    }
}
